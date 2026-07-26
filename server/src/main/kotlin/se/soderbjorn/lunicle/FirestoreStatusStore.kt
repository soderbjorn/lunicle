/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.StatusStore] — the
 * board columns, a `kind == STATUS` row in the shared `vocabulary` collection, and
 * the richest of the five typed vocabulary stores. See [FirestoreLabelStore] for
 * the document model and the shared helpers.
 *
 * ── The two things a status has that a label does not ───────────────────────
 *
 * Its rows carry `requiresResolution` — the "magic" in "Closed is a magic status",
 * read from data rather than the column's name so a renamed column keeps its
 * meaning. [insert] takes it and [update] writes it alongside the name (one write,
 * one decision), the same field [FirestoreVocabularyStore.rename] sets for a status
 * and [FirestoreSprintStore] reads to find the closing columns. And
 * [firstForProject] answers the leftmost column, where a new issue lands — the SQLite
 * `ORDER BY position LIMIT 1`, here the position-sorted list's first row — read
 * rather than hardcoded to "New" so a renamed board still takes an issue.
 *
 * @see FirestoreLabelStore
 * @see FirestoreVocabularyStore
 * @see se.soderbjorn.lunicle.store.StatusStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.store.StatusStore

class FirestoreStatusStore(private val firestore: Firestore) : StatusStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(FirestoreVocabularyStore.COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    override suspend fun insert(projectId: Long, name: String, position: Long, requiresResolution: Boolean) {
        insertVocabularyRow(firestore, counters, VocabularyKind.STATUS, projectId, name, position, requiresResolution)
    }

    /** Rename and set the closing flag together — one write, one decision. */
    override suspend fun update(id: Long, name: String, requiresResolution: Boolean) {
        doc(id).update(
            mapOf(
                FirestoreVocabularyStore.NAME to name,
                FirestoreVocabularyStore.REQUIRES_RESOLUTION to requiresResolution,
            ),
        ).await()
    }

    override suspend fun setPosition(id: Long, position: Long) {
        doc(id).update(FirestoreVocabularyStore.POSITION, position).await()
    }

    override suspend fun delete(id: Long) {
        doc(id).delete().await()
    }

    override suspend fun findByIdInProject(id: Long, projectId: Long): StatusRecord? =
        doc(id).get().await().takeIf { it.isRowOf(projectId, VocabularyKind.STATUS) }?.toStatusRecord()

    override suspend fun forProject(projectId: Long): List<StatusRecord> =
        rowsOfKind(collection(), projectId, VocabularyKind.STATUS).map { it.toStatusRecord() }

    /** The leftmost column, where a new issue lands — position-sorted first row; null for a board with no columns. */
    override suspend fun firstForProject(projectId: Long): StatusRecord? =
        rowsOfKind(collection(), projectId, VocabularyKind.STATUS).firstOrNull()?.toStatusRecord()
}
