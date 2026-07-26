/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.LabelStore] — the
 * plainest of the five typed vocabulary stores, and the one whose document model
 * the other four follow.
 *
 * ── Document model: the shared `vocabulary` collection ──────────────────────
 *
 * A label is a `kind == LABEL` row in the same `vocabulary` collection the generic
 * [FirestoreVocabularyStore] and [FirestoreSprintStore] read and write, addressed
 * through that store's shared constants so all three agree on every field name.
 * One document per row in `vocabulary/{id}`, `{id}` the `Long` id from the shared
 * `_counters/vocabulary` counter (see [FirestoreCounters]) — shared on purpose,
 * because the document key is the id and rows of different kinds must never collide
 * on one. A `projectId` field scopes each row, so [forProject] is a single equality
 * query on `projectId`, filtered to `kind == LABEL` and ordered by `position` in
 * memory — the same one-query-plus-in-memory-sort the generic store uses, which
 * keeps this off any composite index.
 *
 * This is the low-level persistence only: the trimming, the case-insensitive
 * uniqueness and the delete refusals live one layer up in `VocabularyRepository`
 * (which LNL-122 will wrap over these five stores), exactly as they do over the
 * SQLite gateways. So the parity this proves is what round-trips.
 *
 * @see FirestoreVocabularyStore
 * @see FirestoreCounters
 * @see se.soderbjorn.lunicle.store.LabelStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Transaction
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.store.LabelStore

class FirestoreLabelStore(private val firestore: Firestore) : LabelStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(FirestoreVocabularyStore.COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    override suspend fun insert(projectId: Long, name: String, position: Long) {
        insertVocabularyRow(firestore, counters, VocabularyKind.LABEL, projectId, name, position)
    }

    override suspend fun update(id: Long, name: String) {
        doc(id).update(FirestoreVocabularyStore.NAME, name).await()
    }

    override suspend fun setPosition(id: Long, position: Long) {
        doc(id).update(FirestoreVocabularyStore.POSITION, position).await()
    }

    override suspend fun delete(id: Long) {
        doc(id).delete().await()
    }

    override suspend fun findByIdInProject(id: Long, projectId: Long): VocabularyRecord? =
        doc(id).get().await().takeIf { it.isRowOf(projectId, VocabularyKind.LABEL) }?.toVocabularyRecord()

    override suspend fun forProject(projectId: Long): List<VocabularyRecord> =
        rowsOfKind(collection(), projectId, VocabularyKind.LABEL).map { it.toVocabularyRecord() }
}

// ── Shared helpers for the five typed vocabulary stores ─────────────────────
// Each addresses the same `vocabulary` collection through FirestoreVocabularyStore's
// constants, so a status inserted here is a status the generic store reads, and vice
// versa. Kept as free functions so all five stores share one copy without a base class.

/**
 * Insert one vocabulary row, allocating its id from the shared `vocabulary` counter
 * inside the transaction that writes it — the same shape [FirestoreVocabularyStore.add]
 * writes, so the two interoperate. A new row never demands a resolution unless the
 * caller says so, and never carries a completion.
 */
internal suspend fun insertVocabularyRow(
    firestore: Firestore,
    counters: FirestoreCounters,
    kind: VocabularyKind,
    projectId: Long,
    name: String,
    position: Long,
    requiresResolution: Boolean = false,
    isDone: Boolean = false,
) {
    val collection = firestore.collection(FirestoreVocabularyStore.COLLECTION)
    firestore.runTransaction { txn ->
        val id = counters.next(txn, FirestoreVocabularyStore.COUNTER)
            .getValue(FirestoreVocabularyStore.COUNTER)
        txn.set(
            collection.document(id.toString()),
            vocabularyRowFields(id, kind, projectId, name, position, requiresResolution, isDone),
        )
    }.await()
}

/**
 * Seed one vocabulary row inside a transaction the caller already owns, at a
 * pre-allocated [id] — the block-reservation counterpart to [insertVocabularyRow],
 * used by [FirestoreProjectRepository] to write a whole default board in a single
 * `runTransaction`. Writes the *identical* field map [insertVocabularyRow] does, so
 * a seeded row is indistinguishable from an editor-added one and the concrete stores
 * read it the same way. The caller allocates the id (from a reserved block off the
 * shared `vocabulary` counter) and bumps the counter itself, all before this write,
 * so Firestore's reads-before-writes rule holds.
 */
internal fun seedVocabularyRow(
    txn: Transaction,
    firestore: Firestore,
    id: Long,
    kind: VocabularyKind,
    projectId: Long,
    name: String,
    position: Long,
    requiresResolution: Boolean,
    isDone: Boolean = false,
) {
    val collection = firestore.collection(FirestoreVocabularyStore.COLLECTION)
    txn.set(
        collection.document(id.toString()),
        vocabularyRowFields(id, kind, projectId, name, position, requiresResolution, isDone),
    )
}

/**
 * The `vocabulary` document a row becomes — one shape, shared by [insertVocabularyRow]
 * (its own transaction) and [seedVocabularyRow] (a caller's transaction), so the two
 * write paths cannot drift and every concrete store reads the same fields back.
 */
private fun vocabularyRowFields(
    id: Long,
    kind: VocabularyKind,
    projectId: Long,
    name: String,
    position: Long,
    requiresResolution: Boolean,
    isDone: Boolean,
): Map<String, Any?> = mapOf(
    FirestoreVocabularyStore.ID to id,
    FirestoreVocabularyStore.PROJECT_ID to projectId,
    FirestoreVocabularyStore.KIND to kind.name,
    FirestoreVocabularyStore.NAME to name,
    FirestoreVocabularyStore.POSITION to position,
    FirestoreVocabularyStore.REQUIRES_RESOLUTION to requiresResolution,
    FirestoreVocabularyStore.IS_DONE to isDone,
    FirestoreVocabularyStore.COMPLETED_AT to null,
)

/** One project's rows of one kind, in render order — one equality query, sorted in memory. */
internal suspend fun rowsOfKind(
    collection: com.google.cloud.firestore.CollectionReference,
    projectId: Long,
    kind: VocabularyKind,
): List<DocumentSnapshot> =
    collection.whereEqualTo(FirestoreVocabularyStore.PROJECT_ID, projectId).get().await()
        .documents
        .filter { it.getString(FirestoreVocabularyStore.KIND) == kind.name }
        .sortedBy { it.getLong(FirestoreVocabularyStore.POSITION) ?: 0L }

/**
 * Whether this snapshot is a live row of the given project and kind — the
 * document-store answer to the SQLite `findByIdInProject`'s `WHERE id = ? AND
 * project_id = ?`, with the kind added because the five share a collection. A row
 * of the wrong project or kind is as good as absent.
 */
internal fun DocumentSnapshot.isRowOf(projectId: Long, kind: VocabularyKind): Boolean =
    exists() &&
        getLong(FirestoreVocabularyStore.PROJECT_ID) == projectId &&
        getString(FirestoreVocabularyStore.KIND) == kind.name

internal fun DocumentSnapshot.toVocabularyRecord(): VocabularyRecord = VocabularyRecord(
    id = getLong(FirestoreVocabularyStore.ID)!!,
    projectId = getLong(FirestoreVocabularyStore.PROJECT_ID)!!,
    name = getString(FirestoreVocabularyStore.NAME).orEmpty(),
    position = getLong(FirestoreVocabularyStore.POSITION) ?: 0L,
)

/**
 * A row read back as a [StatusRecord] — the shape priorities, resolutions and
 * statuses share. `requiresResolution` is meaningful only for a status; it is false
 * for the other two, which never write the flag as anything else.
 */
internal fun DocumentSnapshot.toStatusRecord(): StatusRecord = StatusRecord(
    id = getLong(FirestoreVocabularyStore.ID)!!,
    projectId = getLong(FirestoreVocabularyStore.PROJECT_ID)!!,
    name = getString(FirestoreVocabularyStore.NAME).orEmpty(),
    position = getLong(FirestoreVocabularyStore.POSITION) ?: 0L,
    requiresResolution = getBoolean(FirestoreVocabularyStore.REQUIRES_RESOLUTION) ?: false,
    isDone = getBoolean(FirestoreVocabularyStore.IS_DONE) ?: false,
)
