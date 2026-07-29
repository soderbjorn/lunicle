/**
 * The Firestore implementation of
 * [se.soderbjorn.lunicle.store.InstanceSettingsStore] (LNL-115) — the deployment-wide
 * switches an administrator sets for the whole instance.
 *
 * Document model: a single, fixed document `instanceSettings/singleton` holding one
 * `values` map of storage-key → boolean. The store is instance-wide, so unlike
 * [FirestoreUiSettingsStore] — which keys a document per user — there is exactly one
 * document here, and its id is a constant rather than an account. The map shape is
 * otherwise identical to that store's, and for the same reasons: the pair is always
 * read together (one document read for [current]) and each switch is written on its
 * own ([set] is one merge that touches a single map entry and leaves the other), so
 * a map field is exactly right. No collection query, and therefore no composite
 * index.
 *
 * Booleans are stored natively rather than as the SQLite reference's "true"/"false"
 * strings — a document field is typed, so there is no reason to stringify it — but
 * the read stays as defensive: a missing key, or a value that is not the boolean
 * `true` (a hand-edited document, a field this build has never heard of), reads as
 * off, which is the safe default for both switches. That is the behaviour the shared
 * [se.soderbjorn.lunicle.store.InstanceSettingsStoreContract] pins on both backends.
 *
 * @see FirestoreProvider
 * @see FirestoreUiSettingsStore
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
import se.soderbjorn.lunicle.store.InstanceSettings

class FirestoreInstanceSettingsStore(
    private val firestore: Firestore,
) : se.soderbjorn.lunicle.store.InstanceSettingsStore {
    private val doc get() = firestore.collection(COLLECTION).document(DOCUMENT)

    override suspend fun current(): InstanceSettings {
        val snapshot = doc.get().await()
        val values = if (!snapshot.exists()) {
            emptyMap()
        } else {
            @Suppress("UNCHECKED_CAST")
            (snapshot.get(VALUES) as? Map<String, Any?>).orEmpty()
        }
        return InstanceSettings(
            requireSignIn = values[InstanceSettingKey.REQUIRE_SIGN_IN.storageKey] == true,
            anyoneCanCreateProject = values[InstanceSettingKey.ANYONE_CAN_CREATE_PROJECT.storageKey] == true,
            hideDisplayName = values[InstanceSettingKey.HIDE_DISPLAY_NAME.storageKey] == true,
            // Not a switch (LNL-191): a user id, stored as a number beside the
            // booleans in the same map. Anything that is not a number — including the
            // absent case — reads as "nobody owns this instance", which withholds
            // authority rather than handing it to whoever has id 0.
            ownerUserId = (values[OWNER_USER_ID_KEY] as? Number)?.toLong(),
        )
    }

    override suspend fun set(key: InstanceSettingKey, isEnabled: Boolean) {
        // Merge, so this writes one entry of the `values` map without disturbing the
        // other — the document-model equivalent of the SQLite upsert's single-row
        // ON CONFLICT, exactly as FirestoreUiSettingsStore.put does. Unconditional and
        // idempotent: a switch has no history worth keeping — last write wins.
        doc.set(mapOf(VALUES to mapOf(key.storageKey to isEnabled)), SetOptions.merge()).await()
    }

    override suspend fun setOwnerUserId(userId: Long?) {
        // The same single-entry merge as `set`. A null writes a null rather than
        // deleting the key, which reads back identically through the `as? Number`
        // above — one write path instead of a delete branch that only Firestore has.
        doc.set(mapOf(VALUES to mapOf(OWNER_USER_ID_KEY to userId)), SetOptions.merge()).await()
    }

    private companion object {
        const val COLLECTION = "instanceSettings"
        const val DOCUMENT = "singleton"
        const val VALUES = "values"

        /** Ownership's key in the map — the same string 33.sqm writes on the SQLite side. */
        const val OWNER_USER_ID_KEY = "owner_user_id"
    }
}
