/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.VocabularyStore] —
 * the richest parity surface in the suite, and the one a document backend is most
 * likely to get subtly wrong: ordering, case-insensitive uniqueness, and the two
 * delete refusals.
 *
 * The SQLite reference is `VocabularyRepository`, which composes six low-level
 * table stores and the issue store. There is no six-store equivalent here: this
 * one class holds the same rules over one collection.
 *
 * ── Document model ──────────────────────────────────────────────────────────
 *
 * One document per row in `vocabulary/{id}`, `{id}` the `Long` id from
 * `_counters/vocabulary` (see [FirestoreCounters]). A `kind` field
 * ([VocabularyKind.name]) discriminates the six vocabularies that lived in six
 * SQLite tables, and a `projectId` field scopes each row — so one project's rows
 * of one kind are a single equality query on `projectId`, filtered to the kind and
 * ordered by `position` in memory. Filtering the kind in memory rather than adding
 * a second `whereEqualTo` keeps this to the automatic single-field index and off
 * LNL-122's composite-index list.
 *
 * A `completedAt` field rides on sprint rows (null while open) — the one column a
 * sprint has that no other vocabulary does. This store never reads it (a
 * [VocabularyRow] does not carry completion), but [FirestoreSprintStore] does, and
 * both address the sprint through this collection's constants, which is why the
 * document constants below are `internal` rather than private.
 *
 * ── Uniqueness and the refusals, reproduced ─────────────────────────────────
 *
 *  - **Case-insensitive uniqueness** has no unique index here. It is enforced the
 *    way the SQLite *repository* enforced it above its `COLLATE NOCASE` index — a
 *    Unicode-aware `lowercase()` comparison against the project's existing rows,
 *    which folds more than the ASCII the index would. The check runs *before* the
 *    write transaction, not inside it, so a [VocabularyConflict] surfaces as itself
 *    rather than wrapped in a transaction failure.
 *  - **The delete refusals** — the last load-bearing row, and a row still in use —
 *    are the same two checks `VocabularyRepository.delete` makes, reading the same
 *    [VocabularyKind.isLoadBearing] / [VocabularyKind.restrictsOnUse] the SQLite
 *    side reads. Usage counts come from an injected [IssueStore], exactly as the
 *    repository takes one.
 *
 * @see FirestoreProvider
 * @see FirestoreCounters
 * @see FirestoreSprintStore
 * @see se.soderbjorn.lunicle.store.VocabularyStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.store.IssueStore
import se.soderbjorn.lunicle.store.VocabularyStore

class FirestoreVocabularyStore(
    private val firestore: Firestore,
    private val issues: IssueStore,
) : VocabularyStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    override suspend fun rows(projectId: Long, kind: VocabularyKind): List<VocabularyRow> {
        val uses = usage(projectId, kind)
        return docsOfKind(projectId, kind).map { it.toRow(uses) }
    }

    override suspend fun find(projectId: Long, kind: VocabularyKind, id: Long): VocabularyRow? {
        val snap = doc(id).get().await()
        // Project-scoped on purpose: a row of the wrong project or the wrong kind is
        // as good as absent, so a route cannot rename another project's column.
        if (!snap.exists() || snap.getLong(PROJECT_ID) != projectId || snap.getString(KIND) != kind.name) {
            return null
        }
        return snap.toRow(usage(projectId, kind))
    }

    /**
     * Add a row at the end of the order.
     *
     * The name is validated *before* the write transaction so a [VocabularyConflict]
     * is thrown as itself. The position is `max + 1` (never `count`, which would
     * collide with a row left after a middle delete — see `VocabularyRepository.add`),
     * and the id is allocated inside the transaction that writes the row.
     */
    override suspend fun add(projectId: Long, kind: VocabularyKind, name: String): VocabularyRow {
        val clean = name.trim()
        val existing = docsOfKind(projectId, kind)
        validateName(kind, clean, existing, renamingId = null)

        val position = (existing.mapNotNull { it.getLong(POSITION) }.maxOrNull() ?: -1L) + 1L
        return firestore.runTransaction { txn ->
            val id = counters.next(txn, COUNTER).getValue(COUNTER)
            txn.set(
                doc(id),
                mapOf(
                    ID to id,
                    PROJECT_ID to projectId,
                    KIND to kind.name,
                    NAME to clean,
                    POSITION to position,
                    // A new column never demands a resolution; a new resolution is
                    // never born done; a sprint is never born completed. See
                    // VocabularyRepository.add.
                    REQUIRES_RESOLUTION to false,
                    IS_DONE to false,
                    COMPLETED_AT to null,
                ),
            )
            VocabularyRow(id, projectId, clean, position, requiresResolution = false, usageCount = 0)
        }.await()
    }

    /**
     * Rename a row, and set its per-kind flag: a status's closing flag, or a
     * resolution's done flag ([isDone]). Each is written only for the kind it
     * belongs to; the other kinds get a name-only update, mirroring
     * VocabularyRepository.rename.
     */
    override suspend fun rename(
        projectId: Long,
        kind: VocabularyKind,
        row: VocabularyRow,
        name: String,
        requiresResolution: Boolean,
        isDone: Boolean,
    ) {
        val clean = name.trim()
        validateName(kind, clean, docsOfKind(projectId, kind), renamingId = row.id)
        val updates = when (kind) {
            VocabularyKind.STATUS -> mapOf(NAME to clean, REQUIRES_RESOLUTION to requiresResolution)
            VocabularyKind.RESOLUTION -> mapOf(NAME to clean, IS_DONE to isDone)
            else -> mapOf(NAME to clean)
        }
        doc(row.id).update(updates).await()
    }

    /**
     * Delete a row, or refuse — the two checks in `VocabularyRepository.delete`'s
     * order: the last load-bearing row first (true even of an empty project), then
     * a row still in use.
     *
     * And, like the repository, the drafts sitting in the row go with it rather
     * than blocking it (LNL-183). There is no foreign key on this backend to
     * refuse the delete, so what a leftover draft would produce here is not a
     * violation but an issue document pointing at a status that no longer exists —
     * which is worse, and invisible until someone opens it.
     */
    override suspend fun delete(projectId: Long, kind: VocabularyKind, row: VocabularyRow) {
        val siblings = docsOfKind(projectId, kind)
        if (siblings.size <= 1 && kind.isLoadBearing) {
            throw VocabularyRefusal(
                "A project needs at least one ${kind.noun}, and \"${row.name}\" is the only one " +
                    "left. Add another before deleting this one.",
            )
        }
        if (kind.restrictsOnUse && row.usageCount > 0) {
            throw VocabularyRefusal(inUseMessage(kind, row))
        }
        when (kind) {
            VocabularyKind.STATUS -> issues.deleteDraftsWithStatus(projectId, row.id)
            VocabularyKind.PRIORITY -> issues.deleteDraftsWithPriority(projectId, row.id)
            // Nothing else can be holding a draft; see VocabularyRepository.clearDrafts.
            else -> Unit
        }
        doc(row.id).delete().await()
    }

    /**
     * Put a whole vocabulary in the order given, positions rewritten 0..n-1 in one
     * batch (which repairs the gap a deleted middle row leaves).
     *
     * [ids] must be exactly this kind's rows in this project — proved before a
     * single write, since a partial list would leave the omitted rows colliding
     * with the ones it named and there is no unique position to catch it.
     */
    override suspend fun reorder(projectId: Long, kind: VocabularyKind, ids: List<Long>) {
        val current = docsOfKind(projectId, kind).mapNotNull { it.getLong(ID) }
        if (ids.size != current.size || ids.toSet() != current.toSet()) {
            throw VocabularyRefusal("That order does not name this project's ${kind.plural}.")
        }
        val batch = firestore.batch()
        ids.forEachIndexed { index, id -> batch.update(doc(id), POSITION, index.toLong()) }
        batch.commit().await()
    }

    /** One project's rows of one kind, in render order — one equality query, sorted in memory. */
    private suspend fun docsOfKind(projectId: Long, kind: VocabularyKind): List<DocumentSnapshot> =
        collection().whereEqualTo(PROJECT_ID, projectId).get().await()
            .documents
            .filter { it.getString(KIND) == kind.name }
            .sortedBy { it.getLong(POSITION) ?: 0L }

    /** Usage counts for one kind, from the issue store — see VocabularyRepository.usage. */
    private suspend fun usage(projectId: Long, kind: VocabularyKind): Map<Long, Long> = when (kind) {
        VocabularyKind.LABEL -> issues.usageByLabel(projectId)
        VocabularyKind.COMPONENT -> issues.usageByComponent(projectId)
        VocabularyKind.STATUS -> issues.usageByStatus(projectId)
        VocabularyKind.PRIORITY -> issues.usageByPriority(projectId)
        VocabularyKind.RESOLUTION -> issues.usageByResolution(projectId)
        VocabularyKind.SPRINT -> issues.usageBySprint(projectId)
        VocabularyKind.VERSION -> issues.usageByVersion(projectId)
    }

    /**
     * Blank, or already another row's? A Unicode-aware `lowercase()` fold, the same
     * one the SQLite repository and the dialog use, so the three agree on what a
     * duplicate is beyond the ASCII a `COLLATE NOCASE` index would fold.
     */
    private fun validateName(
        kind: VocabularyKind,
        name: String,
        existing: List<DocumentSnapshot>,
        renamingId: Long?,
    ) {
        if (name.isBlank()) throw VocabularyConflict("A ${kind.noun} needs a name.")
        val clash = existing.firstOrNull {
            it.getLong(ID) != renamingId && it.getString(NAME)?.lowercase() == name.lowercase()
        }
        if (clash != null) {
            throw VocabularyConflict("There is already a ${kind.noun} called \"${clash.getString(NAME)}\".")
        }
    }

    /** "3 issues are still in that status." — mirrors VocabularyRepository.inUseMessage. */
    private fun inUseMessage(kind: VocabularyKind, row: VocabularyRow): String {
        val isOne = row.usageCount == 1L
        val subject = if (isOne) "1 issue" else "${row.usageCount} issues"
        val verb = when {
            kind == VocabularyKind.STATUS && isOne -> "is still in"
            kind == VocabularyKind.STATUS -> "are still in"
            isOne -> "still has"
            else -> "still have"
        }
        val andThen = if (isOne) "Move it somewhere else first" else "Move them somewhere else first"
        return "$subject $verb that ${kind.noun}. $andThen, and then delete it."
    }

    private fun DocumentSnapshot.toRow(uses: Map<Long, Long>): VocabularyRow = VocabularyRow(
        id = getLong(ID)!!,
        projectId = getLong(PROJECT_ID)!!,
        name = getString(NAME).orEmpty(),
        position = getLong(POSITION) ?: 0L,
        requiresResolution = getBoolean(REQUIRES_RESOLUTION) ?: false,
        isDone = getBoolean(IS_DONE) ?: false,
        usageCount = uses[getLong(ID)] ?: 0L,
    )

    /**
     * The `vocabulary` collection's shape. `internal` — not private — because
     * [FirestoreSprintStore] reads sprint and status rows from the same collection
     * and must agree with this store on every field name. Deliberately kept here,
     * on the store that owns the write path, rather than in a shared helper file.
     */
    internal companion object {
        const val COLLECTION = "vocabulary"
        const val COUNTER = "vocabulary"

        const val ID = "id"
        const val PROJECT_ID = "projectId"
        const val KIND = "kind"
        const val NAME = "name"
        const val POSITION = "position"
        const val REQUIRES_RESOLUTION = "requiresResolution"

        /**
         * A resolution's "the work was done" flag (LNL-134) — the mirror of
         * [REQUIRES_RESOLUTION], meaningful only on resolution rows and false on
         * every other kind. Read back by [toStatusRecord] and [toRow], written by
         * [add]/[rename] here and the shared seed helpers, so the board and the
         * settings editor agree on it. See Resolutions.sq.
         */
        const val IS_DONE = "isDone"
        const val COMPLETED_AT = "completedAt"
    }
}
