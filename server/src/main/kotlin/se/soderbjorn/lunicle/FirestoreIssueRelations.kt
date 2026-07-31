/**
 * The Firestore half of relations: the per-project vocabulary that names the ways
 * two issues can be linked, and the links themselves (LNL-215).
 *
 * The SQLite reference is [IssueRelationKindStore]/[IssueRelationStore] in
 * IssueRelations.kt, and the rules above both — same project, no self-relation, no
 * duplicate pair in either direction, both issues published — live in
 * `IssueRepository` and `VocabularyRepository`, which are backend-agnostic. What is
 * decided *here* is only the document model, and two of those decisions are load
 * bearing enough to state up front.
 *
 * ── Decision 1: relation kinds join the shared `vocabulary` collection ──────
 *
 * A relation kind is a `kind == RELATION_KIND` row in the same `vocabulary`
 * collection labels, components, statuses, priorities, resolutions, sprints and
 * versions already share, addressed through [FirestoreVocabularyStore]'s constants,
 * with two extra fields — `inverseName` and `marksBlocked` — riding on the row
 * exactly as `requiresResolution` rides on a status and `completedAt` on a sprint.
 *
 * The alternative was a collection of its own, `issueRelationKinds`, and it is worth
 * naming what that would have cost. The settings editor writes through the generic
 * [FirestoreVocabularyStore] and the board reads through
 * [FirestoreIssueRelationKindStore]; if those two addressed different collections
 * then a kind added in settings would be invisible to the board and a kind seeded at
 * project creation invisible to settings, which is precisely the interop
 * `FirestoreVocabularyInteropTest` exists to pin for the other seven kinds. Sharing
 * the collection also means the project cascade already takes relation kinds with the
 * one `vocabulary` sweep it makes ([projectCascade]'s item 3), and the shared
 * `_counters/vocabulary` id counter keeps a relation kind's id from ever colliding
 * with a status's — which matters because [VocabularyRow] and the settings routes
 * address every kind by a bare `Long`.
 *
 * The price is two fields on every vocabulary document that only one kind can ever
 * mean anything by. That is the price the collection already pays twice over, and it
 * is the cheaper half of this trade by a wide margin.
 *
 * ── Decision 2: the relations themselves are NOT vocabulary ─────────────────
 *
 * They get their own top-level `issueRelations` collection and their own counter.
 * They are not per-project words an administrator edits; they are rows about two
 * issues, they carry no name or position, they are created and destroyed by ordinary
 * editors rather than by admins, and there will be far more of them than of every
 * vocabulary row combined. Putting them in `vocabulary` would make every settings
 * read of a project scan them.
 *
 * ── What a document backend has to do by hand here ──────────────────────────
 *
 * **The cascades.** SQLite declares `ON DELETE CASCADE` on both issue references and
 * on the composite `(kind_id, project_id)` key, so relation rows go with either end
 * or with their kind for free. Firestore has no cascade at all, so
 * [FirestoreIssueRelationStore.deleteForIssue] and
 * [FirestoreIssueRelationStore.deleteForKind] are not the redundant belt-and-braces
 * they are on SQLite — they *are* the guarantee. A missed sweep here leaves relation
 * documents naming ids that resolve to nothing, invisible until somebody opens the
 * issue at the other end.
 *
 * **The reverse read.** `WHERE from_issue_id = ? OR to_issue_id = ?` has no Firestore
 * equivalent — a disjunction over two different fields is not a query this backend
 * can express — so [FirestoreIssueRelationStore.forIssue] runs the two equality
 * queries and merges in Kotlin. Both are served by the automatic single-field
 * indexes, so this adds nothing to LNL-122's composite-index list.
 *
 * @see FirestoreVocabularyStore the generic editor over the same collection.
 * @see IssueRelations.sq the SQLite schema, and the long prose behind every column.
 * @see se.soderbjorn.lunicle.store.IssueRelationStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.FieldPath
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.SetOptions
import com.google.cloud.firestore.Transaction
import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.store.IssueRelationKindStore
import se.soderbjorn.lunicle.store.IssueRelationStore

/**
 * One project's relation kinds, over the shared `vocabulary` collection.
 *
 * The typed counterpart of [FirestoreStatusStore] — same collection, same helpers,
 * same `kind` discriminator — with the two extras a relation kind carries. See the
 * file preamble for why it shares that collection rather than owning one.
 *
 * @param relations the links using these kinds, so [delete] can sweep them. On
 *   SQLite the equivalent parameter is redundant against the schema's own cascade and
 *   is passed anyway so both backends run the same two steps; here it is the entire
 *   cascade. See [se.soderbjorn.lunicle.store.IssueRelationKindStore.delete].
 */
class FirestoreIssueRelationKindStore(
    private val firestore: Firestore,
    private val relations: IssueRelationStore,
) : IssueRelationKindStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(FirestoreVocabularyStore.COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    /**
     * Add a kind at [position], allocating its id from the shared `vocabulary`
     * counter inside the transaction that writes it.
     *
     * The shape written is [relationKindFields] — the same one
     * [FirestoreVocabularyStore.add] and [seedRelationKindRow] write — so a kind
     * added here, added in settings, or seeded with the project is one shape read
     * back identically by all three.
     *
     * It does not go through [insertVocabularyRow], the helper the five plain typed
     * stores share, and that is a deliberate split rather than an oversight: that
     * helper writes a fixed field map, and threading a relation kind's two extras
     * through it would give every other kind's insert two more defaulted parameters
     * none of them can ever mean anything by. One field map per shape, each with a
     * single home, is the same call [vocabularyRowFields] made for the other seven.
     */
    override suspend fun insert(
        projectId: Long,
        name: String,
        position: Long,
        inverseName: String?,
        marksBlocked: Boolean,
    ) {
        firestore.runTransaction { txn ->
            val id = counters.next(txn, FirestoreVocabularyStore.COUNTER)
                .getValue(FirestoreVocabularyStore.COUNTER)
            txn.set(doc(id), relationKindFields(id, projectId, name, position, inverseName, marksBlocked))
        }.await()
    }

    /**
     * Both names and the flag in one write, because they are one decision — the
     * mirror of [FirestoreStatusStore.update]. Separate writes could each fail on
     * their own and leave a kind renamed but still marking cards blocked.
     */
    override suspend fun update(id: Long, name: String, inverseName: String?, marksBlocked: Boolean) {
        doc(id).update(
            mapOf(
                FirestoreVocabularyStore.NAME to name,
                FirestoreVocabularyStore.INVERSE_NAME to inverseName,
                FirestoreVocabularyStore.MARKS_BLOCKED to marksBlocked,
            ),
        ).await()
    }

    override suspend fun setPosition(id: Long, position: Long) {
        doc(id).update(FirestoreVocabularyStore.POSITION, position).await()
    }

    /**
     * Delete a kind and the links that used it.
     *
     * The sweep first, then the row — the SQLite gateway's order, and here the order
     * is the one that survives an interruption: a run that stops between the two
     * leaves a kind whose links are gone, which is a kind an admin can simply delete
     * again. The reverse would leave links naming a kind that no longer exists, and
     * nothing would ever look for them.
     */
    override suspend fun delete(id: Long) {
        relations.deleteForKind(id)
        doc(id).delete().await()
    }

    override suspend fun findByIdInProject(id: Long, projectId: Long): IssueRelationKindRecord? =
        doc(id).get().await()
            .takeIf { it.isRowOf(projectId, VocabularyKind.RELATION_KIND) }
            ?.toRelationKindRecord()

    override suspend fun forProject(projectId: Long): List<IssueRelationKindRecord> =
        rowsOfKind(collection(), projectId, VocabularyKind.RELATION_KIND).map { it.toRelationKindRecord() }
}

/**
 * The links between issues — one document per link, from → to, in `issueRelations`.
 *
 * **One row, rendered in both directions**, exactly as IssueRelations.sq states: this
 * store has no "insert the inverse too" and no read that returns a pre-inverted pair.
 * Which end a reader is on is decided where the link is rendered, from
 * [IssueRelationRecord.fromIssueId].
 *
 * Every read below is a single-field equality, so nothing here needs a composite
 * index — including the "who points at me" half, which costs a second query rather
 * than an index. See the file preamble.
 */
class FirestoreIssueRelationStore(
    private val firestore: Firestore,
    private val now: () -> Long = System::currentTimeMillis,
) : IssueRelationStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    /**
     * Write one link and return its id, allocating the id in the same transaction
     * that writes the document — the [FirestoreCounters] rule, so a crash can never
     * leave the counter advanced past a link that was never written.
     *
     * `projectId` is stored on the document even though both issues already know
     * theirs, for the reason IssueRelations.sq gives and which bites hardest on *this*
     * backend: the board's blocked projection is "every relation in this project", and
     * without the field that read would be a join through `issues` — and Firestore has
     * no join, so it would mean reading the project's issues first purely to learn
     * which ids to ask about. It is also what makes [projectCascade] one query.
     */
    override suspend fun insert(
        projectId: Long,
        fromIssueId: Long,
        toIssueId: Long,
        kindId: Long,
        createdAt: Long?,
    ): Long {
        val timestamp = createdAt ?: now()
        return firestore.runTransaction { txn ->
            val id = counters.next(txn, COUNTER).getValue(COUNTER)
            txn.set(
                doc(id),
                mapOf(
                    ID to id,
                    PROJECT_ID to projectId,
                    FROM_ISSUE_ID to fromIssueId,
                    TO_ISSUE_ID to toIssueId,
                    KIND_ID to kindId,
                    CREATED_AT to timestamp,
                ),
            )
            id
        }.await()
    }

    override suspend fun delete(id: Long) {
        doc(id).delete().await()
    }

    override suspend fun findById(id: Long): IssueRelationRecord? =
        doc(id).get().await().takeIf { it.exists() }?.toRecord()

    /**
     * Every link touching one issue, in either direction, oldest first.
     *
     * Two equality queries merged in Kotlin, because SQLite's `WHERE from = ? OR to =
     * ?` is a disjunction over two *different* fields and Firestore cannot express it
     * as one query. The alternative — a denormalised `issueIds` array holding both
     * ends, queried with `arrayContains` — would make this one query and is rejected
     * for the reason IssueRelations.sq rejects storing the inverse row: it is a second
     * copy of the two ids that can disagree with the first, and no backend would ever
     * notice that it had.
     *
     * Sorted by id, matching the SQLite `ORDER BY id`, so a list does not reshuffle
     * itself between two loads when two links share a millisecond. Distinct by id as
     * well, which costs nothing and means a self-relation — forbidden above, but not
     * by anything here — would be listed once rather than twice.
     */
    override suspend fun forIssue(issueId: Long): List<IssueRelationRecord> {
        val from = collection().whereEqualTo(FROM_ISSUE_ID, issueId).get().await().documents
        val to = collection().whereEqualTo(TO_ISSUE_ID, issueId).get().await().documents
        return (from + to).map { it.toRecord() }.distinctBy { it.id }.sortedBy { it.id }
    }

    override suspend fun forProject(projectId: Long): List<IssueRelationRecord> =
        collection().whereEqualTo(PROJECT_ID, projectId).get().await().documents.map { it.toRecord() }

    /**
     * How many links use each of the project's kinds.
     *
     * Counted in memory over the one project-scoped query, the document-model answer
     * to the SQLite `GROUP BY kind_id` — the shape every `usageBy…` on
     * [FirestoreIssueStore] already takes. Absent means zero, so no zero rows.
     */
    override suspend fun usageByKind(projectId: Long): Map<Long, Long> =
        forProject(projectId).groupingBy { it.kindId }.eachCount().mapValues { it.value.toLong() }

    /**
     * Every link naming this issue, in either direction, deleted.
     *
     * Two sweeps for [forIssue]'s reason. Load-bearing rather than redundant here:
     * SQLite has `ON DELETE CASCADE` on both references and this backend has nothing,
     * so skipping either direction leaves relation documents pointing at an issue that
     * is gone — and the *reverse* direction is the one easy to forget, because it is
     * the one the issue's own editor never wrote.
     */
    override suspend fun deleteForIssue(issueId: Long) {
        deleteWhere(collection(), FROM_ISSUE_ID, issueId)
        deleteWhere(collection(), TO_ISSUE_ID, issueId)
    }

    /**
     * Every link using one kind, deleted — the cascade the composite foreign key
     * performs on SQLite and nothing performs here.
     *
     * One equality query, chunked by [deleteWhere], because a project that has used a
     * kind heavily can hold more links under it than a single batch may commit.
     */
    override suspend fun deleteForKind(kindId: Long) = deleteWhere(collection(), KIND_ID, kindId)

    /**
     * The `issueRelations` collection's shape. `internal` — not private — because
     * [projectCascade] sweeps this collection by [PROJECT_ID] and must agree with this
     * store on the field name.
     */
    internal companion object {
        const val COLLECTION = "issueRelations"
        const val COUNTER = "issueRelations"

        const val ID = "id"
        const val PROJECT_ID = "projectId"
        const val FROM_ISSUE_ID = "fromIssueId"
        const val TO_ISSUE_ID = "toIssueId"
        const val KIND_ID = "kindId"
        const val CREATED_AT = "createdAt"
    }
}

private fun DocumentSnapshot.toRecord(): IssueRelationRecord = IssueRelationRecord(
    id = getLong(FirestoreIssueRelationStore.ID)!!,
    projectId = getLong(FirestoreIssueRelationStore.PROJECT_ID)!!,
    fromIssueId = getLong(FirestoreIssueRelationStore.FROM_ISSUE_ID)!!,
    toIssueId = getLong(FirestoreIssueRelationStore.TO_ISSUE_ID)!!,
    kindId = getLong(FirestoreIssueRelationStore.KIND_ID)!!,
    createdAt = getLong(FirestoreIssueRelationStore.CREATED_AT) ?: 0L,
)

// ── The one shape a relation-kind document takes ────────────────────────────

/**
 * The `vocabulary` document a relation kind becomes — the [vocabularyRowFields] of
 * this kind, and its single home.
 *
 * Written by three paths that must not drift: [FirestoreIssueRelationKindStore.insert]
 * (its own transaction), [seedRelationKindRow] (a caller's transaction, at project
 * creation and in the back-fill), and [FirestoreVocabularyStore.add] (the settings
 * editor, which composes the same two extras onto its generic map). All three
 * therefore produce a row the board and the editor read back identically.
 *
 * The three per-kind flags the other vocabularies carry — `requiresResolution`,
 * `isDone`, `completedAt` — are deliberately **absent** rather than written as
 * false/null. Every reader of them defaults an absent field (`getBoolean(…) ?: false`),
 * so writing them would add three fields to say what their absence already says. The
 * mirror holds in the other direction: a status document carries no `inverseName`, and
 * [DocumentSnapshot.toRelationKindRecord] never asks one for it.
 *
 * @param inverseName the to-side label, or null for a kind that reads the same in both
 *   directions. **Null is the whole encoding of symmetry** — stored as an explicit
 *   null rather than an omitted field so an update that clears it and an insert that
 *   never set it produce the same document.
 */
internal fun relationKindFields(
    id: Long,
    projectId: Long,
    name: String,
    position: Long,
    inverseName: String?,
    marksBlocked: Boolean,
): Map<String, Any?> = mapOf(
    FirestoreVocabularyStore.ID to id,
    FirestoreVocabularyStore.PROJECT_ID to projectId,
    FirestoreVocabularyStore.KIND to VocabularyKind.RELATION_KIND.name,
    FirestoreVocabularyStore.NAME to name,
    FirestoreVocabularyStore.POSITION to position,
    FirestoreVocabularyStore.INVERSE_NAME to inverseName,
    FirestoreVocabularyStore.MARKS_BLOCKED to marksBlocked,
)

/**
 * Seed one relation kind inside a transaction the caller already owns, at a
 * pre-allocated [id] — the counterpart of [seedVocabularyRow], and used by the same
 * two callers for the same reason: [FirestoreProjectRepository.create] writes a whole
 * default board in one `runTransaction`, and [FirestoreRelationKindBackfill] writes
 * one project's three kinds in one. The caller allocates the id from a reserved block
 * off the shared `vocabulary` counter and bumps that counter before this write, so
 * Firestore's reads-before-writes rule holds.
 */
internal fun seedRelationKindRow(
    txn: Transaction,
    firestore: Firestore,
    id: Long,
    projectId: Long,
    name: String,
    position: Long,
    inverseName: String?,
    marksBlocked: Boolean,
) {
    txn.set(
        firestore.collection(FirestoreVocabularyStore.COLLECTION).document(id.toString()),
        relationKindFields(id, projectId, name, position, inverseName, marksBlocked),
    )
}

/**
 * A `vocabulary` row read back as a relation kind.
 *
 * `inverseName` is read as a nullable with no default — unlike every other read on a
 * vocabulary document — because null is not a fallback here, it is the *meaning*: a
 * kind with no to-side label reads the same in both directions. `marksBlocked`
 * defaults false, which is the state a row written before this field existed was in
 * and the safe one to be wrong about: an un-dimmed card, never a board greyed by a
 * flag nobody set.
 */
internal fun DocumentSnapshot.toRelationKindRecord(): IssueRelationKindRecord = IssueRelationKindRecord(
    id = getLong(FirestoreVocabularyStore.ID)!!,
    projectId = getLong(FirestoreVocabularyStore.PROJECT_ID)!!,
    name = getString(FirestoreVocabularyStore.NAME).orEmpty(),
    inverseName = getString(FirestoreVocabularyStore.INVERSE_NAME),
    marksBlocked = getBoolean(FirestoreVocabularyStore.MARKS_BLOCKED) ?: false,
    position = getLong(FirestoreVocabularyStore.POSITION) ?: 0L,
)

// ── The back-fill: 36.sqm's last three statements, for the document backend ──

private val backfillLogger = LoggerFactory.getLogger("FirestoreRelationKindBackfill")

/**
 * Version 2: seed [DEFAULT_RELATION_KINDS] into every project that already exists —
 * the Firestore counterpart of 36.sqm's three `INSERT … SELECT id FROM projects`
 * statements (LNL-215).
 *
 * **Why this is the one real back-fill in LNL-215.** Every other column the ticket
 * adds is either NOT NULL with a default that reproduces the prior state or nullable
 * and correctly null, so a document that has never been touched is already right —
 * and on this backend "already right" is free, because the readers default an absent
 * field. Relation kinds are different in kind: a project with **no** kinds cannot
 * create a relation at all, since there is no vocabulary to pick from. An unmigrated
 * project would not start the feature empty, it would silently lose it outright. So
 * the rows have to be written.
 *
 * ── Why it does not use [FirestoreBackfill] ─────────────────────────────────
 *
 * That helper is the right tool for the usual Firestore migration — *rewrite each
 * document of a collection in a new shape* — and this is not that. It has to read one
 * collection (`projects`) and **create documents in another** (`vocabulary`), which
 * the helper's `transform` cannot do: it returns fields to merge onto the document it
 * was handed, and it is not `suspend`, so it cannot even perform the read that decides
 * whether a project needs seeding. What this borrows instead is the helper's two load-
 * bearing properties, by hand:
 *
 *  - **Resumability** comes from the same stable `FieldPath.documentId()` cursor,
 *    which needs no index and orders identically on every run — the pagination
 *    [FirestorePermissionModelMigration]'s `dropOldGrants` also copies for the same
 *    reason.
 *  - **Idempotence** comes from two guards that compose to exactly-once. The
 *    [FirestoreBackfill.SCHEMA_VERSION_MARKER] on the project document says "this
 *    project has been through version 2"; the emptiness check says "this project has
 *    no relation kinds". Both must pass before anything is written, and the marker is
 *    stamped after. A run interrupted between the seed and the stamp re-runs, finds
 *    the kinds present, seeds nothing, and stamps — which is the state it was heading
 *    for anyway.
 *
 * The second guard is the one that matters in the long run, and it is not belt and
 * braces: it is what stops the three kinds reappearing on a project whose administrator
 * **deliberately deleted them**. Without it, a marker lost to any future re-numbering
 * would resurrect vocabulary somebody threw away.
 *
 * ── One transaction per project ─────────────────────────────────────────────
 *
 * Three ids off the shared `vocabulary` counter and three document writes, atomically,
 * exactly as [FirestoreProjectRepository.create] seeds a fresh board. A project
 * therefore never ends up with one or two of its three kinds. Per project rather than
 * per run because a run over a large instance is far past the 500-write ceiling a
 * Firestore transaction allows.
 *
 * @param pageSize projects per page, injectable so a test can force the multi-page
 *   path without seeding hundreds of projects — [FirestorePermissionModelMigration]'s
 *   parameter, for its reason.
 */
internal class FirestoreRelationKindBackfill(
    override val version: Int = 2,
    private val pageSize: Int = FirestoreBackfill.DEFAULT_PAGE_SIZE,
) : FirestoreMigration {

    override suspend fun apply(db: Firestore) {
        var seeded = 0
        var cursor: DocumentSnapshot? = null
        while (true) {
            var query: Query = db.collection(FirestoreProjectStore.COLLECTION)
                .orderBy(FieldPath.documentId())
                .limit(pageSize)
            if (cursor != null) query = query.startAfter(cursor)

            val page = query.get().await().documents
            if (page.isEmpty()) break

            for (project in page) {
                val projectId = project.getLong(ID) ?: continue
                if ((project.getLong(FirestoreBackfill.SCHEMA_VERSION_MARKER) ?: 0L) >= version) continue
                if (seedKinds(db, projectId)) seeded++
                // Stamped whether or not anything was written, because "visited" is
                // what the marker means. A project that already had kinds is done.
                project.reference.set(
                    mapOf(FirestoreBackfill.SCHEMA_VERSION_MARKER to version.toLong()),
                    SetOptions.merge(),
                ).await()
            }

            cursor = page.last()
            if (page.size < pageSize) break
        }
        if (seeded > 0) backfillLogger.info("LNL-215: seeded relation kinds into $seeded existing project(s)")
    }

    /**
     * Seed one project's three kinds, or do nothing because it already has some.
     *
     * The emptiness read happens **inside** the transaction, so two instances booting
     * at once cannot both decide the project is empty and both seed it — the
     * transaction's read set is what makes the loser retry and then find the rows.
     * That is a guarantee the marker alone could not give, since the marker is written
     * outside.
     *
     * @return whether it wrote anything.
     */
    private suspend fun seedKinds(db: Firestore, projectId: Long): Boolean {
        val vocabulary = db.collection(FirestoreVocabularyStore.COLLECTION)
        val counter = db.collection(FirestoreCounters.COLLECTION).document(FirestoreVocabularyStore.COUNTER)
        return db.runTransaction { txn ->
            // ── Reads first, all of them ──────────────────────────────────────
            val existing = txn.get(vocabulary.whereEqualTo(FirestoreVocabularyStore.PROJECT_ID, projectId))
                .get().documents
                .any { it.getString(FirestoreVocabularyStore.KIND) == VocabularyKind.RELATION_KIND.name }
            if (existing) return@runTransaction false
            val base = txn.get(counter).get().getLong(FirestoreCounters.VALUE) ?: 0L

            // ── Writes: the counter, then the block it reserved ───────────────
            txn.set(counter, mapOf(FirestoreCounters.VALUE to base + DEFAULT_RELATION_KINDS.size))
            DEFAULT_RELATION_KINDS.forEachIndexed { index, kind ->
                seedRelationKindRow(
                    txn, db, base + 1L + index, projectId, kind.name, index.toLong(), kind.inverse, kind.marksBlocked,
                )
            }
            true
        }.await()
    }

    private companion object {
        const val ID = "id"
    }
}
