/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.ComponentStore] —
 * [FirestoreLabelStore]'s twin, differing only in the [VocabularyKind] it reads and
 * writes. A component is a `kind == COMPONENT` row in the shared `vocabulary`
 * collection; see [FirestoreLabelStore] for the document model and the shared
 * helpers this store leans on.
 *
 * @see FirestoreLabelStore
 * @see FirestoreVocabularyStore
 * @see se.soderbjorn.lunicle.store.ComponentStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.store.ComponentStore

class FirestoreComponentStore(private val firestore: Firestore) : ComponentStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(FirestoreVocabularyStore.COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    override suspend fun insert(projectId: Long, name: String, position: Long) {
        insertVocabularyRow(firestore, counters, VocabularyKind.COMPONENT, projectId, name, position)
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
        doc(id).get().await().takeIf { it.isRowOf(projectId, VocabularyKind.COMPONENT) }?.toVocabularyRecord()

    override suspend fun forProject(projectId: Long): List<VocabularyRecord> =
        rowsOfKind(collection(), projectId, VocabularyKind.COMPONENT).map { it.toVocabularyRecord() }
}
