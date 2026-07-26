/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.PriorityStore] — a
 * `kind == PRIORITY` row in the shared `vocabulary` collection, reading back as
 * [StatusRecord] like a resolution or a status (`requiresResolution` always false).
 * See [FirestoreLabelStore] for the document model and the shared helpers.
 *
 * ── The one thing a priority has that a label does not ──────────────────────
 *
 * [defaultForProject] is what a new issue gets — the *middle* of the scale, not the
 * top, because a new issue born "Very high" is a lie someone must correct on every
 * issue where the middle claims nothing. The SQLite query is `ORDER BY position
 * LIMIT 1 OFFSET (COUNT / 2)`; here it is the position-sorted list indexed at
 * `size / 2` (integer division, so a 5-row scale gives the third row and an even
 * scale rounds to the calmer, lower half). Read rather than hardcoded to "Normal",
 * so a renamed scale still takes an issue.
 *
 * @see FirestoreLabelStore
 * @see FirestoreVocabularyStore
 * @see se.soderbjorn.lunicle.store.PriorityStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.store.PriorityStore

class FirestorePriorityStore(private val firestore: Firestore) : PriorityStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(FirestoreVocabularyStore.COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    override suspend fun insert(projectId: Long, name: String, position: Long) {
        insertVocabularyRow(firestore, counters, VocabularyKind.PRIORITY, projectId, name, position)
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

    override suspend fun findByIdInProject(id: Long, projectId: Long): StatusRecord? =
        doc(id).get().await().takeIf { it.isRowOf(projectId, VocabularyKind.PRIORITY) }?.toStatusRecord()

    override suspend fun forProject(projectId: Long): List<StatusRecord> =
        rowsOfKind(collection(), projectId, VocabularyKind.PRIORITY).map { it.toStatusRecord() }

    /** The middle of the scale — position-sorted, indexed at `size / 2`; null for an empty scale. */
    override suspend fun defaultForProject(projectId: Long): StatusRecord? {
        val rows = rowsOfKind(collection(), projectId, VocabularyKind.PRIORITY)
        return rows.getOrNull(rows.size / 2)?.toStatusRecord()
    }
}
