/**
 * The idempotent, resumable, batched collection sweep every non-trivial
 * [FirestoreMigration] is built on.
 *
 * ── The problem it solves ───────────────────────────────────────────────────
 *
 * A Firestore "migration" that changes a document's shape has to read every
 * document of a collection and write it back — potentially thousands of writes,
 * against a service with two hard edges: a batch commits at most 500 writes, and
 * an instance can be recycled at any moment, halfway through. So a naive
 * `for (doc in collection) doc.set(newShape)` is wrong three ways — it may exceed
 * the batch limit, it re-does completed work on a re-run, and an interruption
 * leaves the collection half-migrated with no way to tell which half. This helper
 * removes all three:
 *
 *  - **Batched.** Documents are paginated [pageSize] at a time (default well under
 *    500) and each page's writes commit as one [com.google.cloud.firestore.WriteBatch].
 *  - **Resumable.** Pagination walks a stable cursor over the document id, so a
 *    re-run after a crash picks up the whole collection again from the start and —
 *  - **Idempotent.** — skips every document already migrated, detected by a per-
 *    document [SCHEMA_VERSION_MARKER] (`_sv`) the helper stamps as it goes, or by a
 *    caller-supplied predicate over the new field. A document written by a prior
 *    (interrupted) run is passed over; only the unfinished remainder is rewritten.
 *    Running the whole sweep twice therefore writes nothing the second time.
 *
 * ── Why a full re-scan rather than a "where new-field is null" query ────────
 *
 * Firestore cannot cheaply query for the *absence* of a field, so the resumable
 * approach that needs no index is to order the whole collection by document id and
 * skip the already-done documents in memory. That is O(reads) in the collection
 * size on every run, which is the right trade for a boot-time migration: reads are
 * cheap, correctness is not, and the alternative is a composite index and a
 * sentinel value per migration. A caller with a genuinely huge collection can move
 * the pass out-of-band later; the framework's job is to be correct at current scale.
 *
 * ── When a migration cannot use this ────────────────────────────────────────
 *
 * This sweeps *one* collection and merges fields onto the documents it finds. A step
 * that has to **create** documents in another collection — the relation-kind seed
 * LNL-215 needs, which reads `projects` and writes `vocabulary` — cannot express
 * itself as a [transform]: that function returns fields for the document it was
 * handed, and it is not `suspend`, so it cannot even perform the read that decides
 * whether anything is needed. Such a step borrows the two properties above by hand
 * instead — the same `FieldPath.documentId()` cursor for resumability, and its own
 * guard plus this class's [SCHEMA_VERSION_MARKER] for idempotence — rather than
 * bending the helper into a shape it cannot hold. See [FirestoreRelationKindBackfill],
 * which is written out as exactly that borrowing, and says so.
 *
 * @see FirestoreMigration.apply the method that calls this.
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.FieldPath
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.SetOptions
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("FirestoreBackfill")

/**
 * What a [FirestoreBackfill.run] pass did, for the migration to log.
 *
 * @property scanned every document the sweep read.
 * @property written the documents actually rewritten this pass.
 * @property skipped documents passed over because they were already migrated —
 *   on a clean re-run this equals [scanned] and [written] is 0, which is the
 *   observable proof of idempotence.
 * @property batches how many [com.google.cloud.firestore.WriteBatch] commits it took.
 */
data class BackfillResult(
    val scanned: Int,
    val written: Int,
    val skipped: Int,
    val batches: Int,
)

internal class FirestoreBackfill(private val firestore: Firestore) {
    /**
     * Sweep [collection], rewriting every not-yet-migrated document with the
     * fields [transform] returns merged onto it, and stamping each with the `_sv`
     * marker so a re-run skips it.
     *
     * @param collection the collection to sweep.
     * @param version the schema version this backfill belongs to; written to each
     *   processed document's [SCHEMA_VERSION_MARKER] and used by the default
     *   [alreadyMigrated] predicate. Pass the owning [FirestoreMigration.version].
     * @param pageSize documents per page and per batch commit. Must be in
     *   `1..MAX_BATCH` (Firestore's 500-write ceiling); defaults to
     *   [DEFAULT_PAGE_SIZE], comfortably under it.
     * @param alreadyMigrated whether a document is already done and should be
     *   skipped. Defaults to "its `_sv` is at least [version]" — the marker this
     *   helper itself writes — which makes a re-run resume for free. Override it to
     *   detect by the new field instead (e.g. `{ it.contains("newField") }`) when
     *   migrating documents this framework did not previously stamp.
     * @param transform the fields to merge onto a document, computed from its
     *   current snapshot. Merged with `set(merge=true)`, so it names only the
     *   fields that change and leaves the rest of the document intact; the `_sv`
     *   marker is added automatically. Return an empty map to mark a document done
     *   without otherwise changing it (a pure read-time-default materialisation).
     */
    suspend fun run(
        collection: String,
        version: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        alreadyMigrated: (DocumentSnapshot) -> Boolean = { (it.getLong(SCHEMA_VERSION_MARKER) ?: 0L) >= version },
        transform: (DocumentSnapshot) -> Map<String, Any?>,
    ): BackfillResult {
        require(pageSize in 1..MAX_BATCH) { "pageSize must be in 1..$MAX_BATCH, was $pageSize" }

        var scanned = 0
        var written = 0
        var skipped = 0
        var batches = 0
        // A stable cursor over the document id: always present, needs no index, and
        // orders identically on every run — which is what makes the sweep resumable.
        var cursor: DocumentSnapshot? = null

        while (true) {
            var query: Query = firestore.collection(collection)
                .orderBy(FieldPath.documentId())
                .limit(pageSize)
            if (cursor != null) query = query.startAfter(cursor)

            val page = query.get().await().documents
            if (page.isEmpty()) break

            val batch = firestore.batch()
            var writesInBatch = 0
            for (doc in page) {
                scanned++
                if (alreadyMigrated(doc)) {
                    skipped++
                    continue
                }
                val fields = transform(doc) + (SCHEMA_VERSION_MARKER to version.toLong())
                batch.set(doc.reference, fields, SetOptions.merge())
                writesInBatch++
            }
            if (writesInBatch > 0) {
                batch.commit().await()
                written += writesInBatch
                batches++
            }

            cursor = page.last()
            // A short page is the last page: the collection has no more documents
            // after this cursor, so there is nothing left to resume from.
            if (page.size < pageSize) break
        }

        logger.info(
            "Backfill of '$collection' to v$version: scanned=$scanned written=$written skipped=$skipped batches=$batches",
        )
        return BackfillResult(scanned = scanned, written = written, skipped = skipped, batches = batches)
    }

    companion object {
        /**
         * The per-document schema-version marker, `_sv`. Stamped on every document
         * a backfill rewrites and read by the default skip predicate, so an
         * interrupted sweep resumes over exactly the documents it did not reach.
         * The `_` prefix keeps it clear of domain fields.
         */
        const val SCHEMA_VERSION_MARKER = "_sv"

        /** Firestore's hard ceiling on writes in a single batch commit. */
        const val MAX_BATCH = 500

        /**
         * Default documents per page and per commit — under [MAX_BATCH] with room
         * to spare, and small enough that a single page's reads and one batch
         * commit are a modest unit of progress to lose to an interruption.
         */
        const val DEFAULT_PAGE_SIZE = 300
    }
}
