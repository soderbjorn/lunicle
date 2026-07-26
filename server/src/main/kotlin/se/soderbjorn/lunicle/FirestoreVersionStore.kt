/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.VersionStore] — a
 * project's release versions (LNL-134), a `kind == VERSION` row in the shared
 * `vocabulary` collection.
 *
 * The plainest kind, exactly like [FirestoreLabelStore] and reusing all of its
 * shared helpers: an id, a project, a name and a position, read back as a
 * [VocabularyRecord]. A version's done-ness is not its own — that lives on the
 * resolution — so nothing extra rides on a version row. See [FirestoreLabelStore]
 * for the document model and why the constants are addressed through
 * [FirestoreVocabularyStore].
 *
 * @see FirestoreLabelStore
 * @see FirestoreVocabularyStore
 * @see se.soderbjorn.lunicle.store.VersionStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.store.VersionStore

class FirestoreVersionStore(private val firestore: Firestore) : VersionStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(FirestoreVocabularyStore.COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    override suspend fun insert(projectId: Long, name: String, position: Long) {
        insertVocabularyRow(firestore, counters, VocabularyKind.VERSION, projectId, name, position)
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
        doc(id).get().await().takeIf { it.isRowOf(projectId, VocabularyKind.VERSION) }?.toVocabularyRecord()

    override suspend fun forProject(projectId: Long): List<VocabularyRecord> =
        rowsOfKind(collection(), projectId, VocabularyKind.VERSION).map { it.toVocabularyRecord() }
}
