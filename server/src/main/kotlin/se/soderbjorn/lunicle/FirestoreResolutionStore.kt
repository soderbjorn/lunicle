/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.ResolutionStore] —
 * the reasons an issue is closed, a `kind == RESOLUTION` row in the shared
 * `vocabulary` collection. Its rows read back as [StatusRecord] (an id, a project, a
 * name, a position), the triplet it shares with priorities and statuses;
 * `requiresResolution` is always false here. See [FirestoreLabelStore] for the
 * document model and the shared helpers.
 *
 * @see FirestoreLabelStore
 * @see FirestoreVocabularyStore
 * @see se.soderbjorn.lunicle.store.ResolutionStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.store.ResolutionStore

class FirestoreResolutionStore(private val firestore: Firestore) : ResolutionStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(FirestoreVocabularyStore.COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    override suspend fun insert(projectId: Long, name: String, position: Long) {
        insertVocabularyRow(firestore, counters, VocabularyKind.RESOLUTION, projectId, name, position)
    }

    override suspend fun update(id: Long, name: String, isDone: Boolean) {
        doc(id).update(
            mapOf(
                FirestoreVocabularyStore.NAME to name,
                FirestoreVocabularyStore.IS_DONE to isDone,
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
        doc(id).get().await().takeIf { it.isRowOf(projectId, VocabularyKind.RESOLUTION) }?.toStatusRecord()

    override suspend fun forProject(projectId: Long): List<StatusRecord> =
        rowsOfKind(collection(), projectId, VocabularyKind.RESOLUTION).map { it.toStatusRecord() }
}
