/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.UiSettingsStore] —
 * and the first store to cross to the document model, so it doubles as the proof
 * that the whole Firestore machinery (client, emulator, contract reuse) works.
 *
 * Document model: one document per user in `uiSettings/{userId}`, holding a single
 * `values` map of persistence-key → blob. The whole set is small and always read
 * together (the client asks for all of it at boot), so a map field is exactly
 * right: [forUser] is one document read, [put] is one merge that touches a single
 * map entry and leaves the rest. No collection query, and therefore no composite
 * index — the opposite end of the modelling spectrum from the board read.
 *
 * @see FirestoreProvider
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions

class FirestoreUiSettingsStore(
    private val firestore: Firestore,
) : se.soderbjorn.lunicle.store.UiSettingsStore {
    private fun doc(userId: Long) = firestore.collection(COLLECTION).document(userId.toString())

    override suspend fun forUser(userId: Long): Map<String, String> {
        val snapshot = doc(userId).get().await()
        if (!snapshot.exists()) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return (snapshot.get(VALUES) as? Map<String, String>).orEmpty()
    }

    override suspend fun put(userId: Long, key: String, value: String) {
        // Merge, so this writes one entry of the `values` map without disturbing
        // the others — the document-model equivalent of the SQLite upsert's
        // single-row ON CONFLICT. Firestore deep-merges nested map fields under
        // SetOptions.merge(), which is what keeps the sibling keys intact.
        doc(userId).set(mapOf(VALUES to mapOf(key to value)), SetOptions.merge()).await()
    }

    private companion object {
        const val COLLECTION = "uiSettings"
        const val VALUES = "values"
    }
}
