/**
 * Personal access tokens on Firestore (LNL-222): the document backend's
 * [se.soderbjorn.lunicle.store.ApiTokenStore].
 *
 * One document per token in `apiTokens/{id}`, the id drawn from `_counters/apiTokens`
 * (see [FirestoreCounters]) so ids mean the same thing on both backends — the Revoke
 * button names one, and it is a number on SQLite.
 *
 * The lookup is by the `tokenHash` field rather than by document id. Keying the
 * document by hash, as `oauthTokens` does, would make the hash the only handle on the
 * row — and revoke is by id, scoped to the owner, which would then need a second index
 * anyway. A single-field equality query is indexed automatically, so this costs nothing.
 *
 * What Firestore does not do for this collection is cascade: deleting a user document
 * leaves their tokens behind. That is safe rather than merely tolerable, because a
 * token whose user no longer resolves is refused by the route above — see
 * RestApi's `resolveApiCaller`, which looks the user up on every request. Nothing in
 * this app deletes users today.
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.clientserver.ApiTokenScope

class FirestoreApiTokenStore(
    private val firestore: Firestore,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.ApiTokenStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    override suspend fun create(
        userId: Long,
        name: String,
        tokenHash: String,
        tokenPrefix: String,
        scope: ApiTokenScope,
        expiresAt: Long?,
    ): ApiTokenRecord {
        val createdAt = now()
        val id = firestore.runTransaction { txn ->
            val id = counters.next(txn, COUNTER).getValue(COUNTER)
            txn.set(
                doc(id),
                mapOf(
                    ID to id,
                    USER_ID to userId,
                    NAME to name,
                    TOKEN_HASH to tokenHash,
                    TOKEN_PREFIX to tokenPrefix,
                    SCOPE to scope.key,
                    CREATED_AT to createdAt,
                    LAST_USED_AT to null,
                    EXPIRES_AT to expiresAt,
                ),
            )
            id
        }.await()
        return ApiTokenRecord(id, userId, name, tokenPrefix, scope, createdAt, null, expiresAt)
    }

    override suspend fun authenticate(tokenHash: String): ApiTokenRecord? {
        val timestamp = now()
        val snapshot = collection().whereEqualTo(TOKEN_HASH, tokenHash).limit(1).get().await()
            .documents.firstOrNull() ?: return null
        val record = snapshot.toRecord() ?: return null
        // Expiry checked here, as the reference's WHERE clause does — an expired token
        // and an absent one are the same answer.
        if (record.expiresAt != null && record.expiresAt <= timestamp) return null
        // Best effort, after the lookup; see the interface.
        runCatching { snapshot.reference.update(LAST_USED_AT, timestamp).await() }
        return record.copy(lastUsedAt = timestamp)
    }

    override suspend fun forUser(userId: Long): List<ApiTokenRecord> =
        // Sorted here rather than by an orderBy, which on a filtered query would need a
        // composite index for a list that is a handful of rows long.
        collection().whereEqualTo(USER_ID, userId).get().await().documents
            .mapNotNull { it.toRecord() }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))

    override suspend fun revoke(userId: Long, id: Long) {
        val snapshot = doc(id).get().await()
        // The owner check is the point — see the interface. Silent either way.
        if (snapshot.exists() && snapshot.getLong(USER_ID) == userId) {
            snapshot.reference.delete().await()
        }
    }

    override suspend fun deleteExpired(): Long {
        val expired = collection().whereLessThanOrEqualTo(EXPIRES_AT, now()).get().await().documents
        if (expired.isEmpty()) return 0L
        val batch = firestore.batch()
        expired.forEach { batch.delete(it.reference) }
        batch.commit().await()
        return expired.size.toLong()
    }

    private fun DocumentSnapshot.toRecord(): ApiTokenRecord? {
        val id = getLong(ID) ?: return null
        val userId = getLong(USER_ID) ?: return null
        return ApiTokenRecord(
            id = id,
            userId = userId,
            name = getString(NAME).orEmpty(),
            prefix = getString(TOKEN_PREFIX).orEmpty(),
            scope = ApiTokenScope.byKey(getString(SCOPE)),
            createdAt = getLong(CREATED_AT) ?: 0L,
            lastUsedAt = getLong(LAST_USED_AT),
            expiresAt = getLong(EXPIRES_AT),
        )
    }

    private companion object {
        const val COLLECTION = "apiTokens"
        const val COUNTER = "apiTokens"

        const val ID = "id"
        const val USER_ID = "userId"
        const val NAME = "name"
        const val TOKEN_HASH = "tokenHash"
        const val TOKEN_PREFIX = "tokenPrefix"
        const val SCOPE = "scope"
        const val CREATED_AT = "createdAt"
        const val LAST_USED_AT = "lastUsedAt"
        const val EXPIRES_AT = "expiresAt"
    }
}
