/**
 * The persistence seam for personal access tokens (LNL-222).
 *
 * One of the domain store interfaces LNL-111 introduced, for a domain that arrived
 * after it. The reference implementation is the SQLite
 * [se.soderbjorn.lunicle.ApiTokenStore]; the document one is
 * [se.soderbjorn.lunicle.FirestoreApiTokenStore].
 *
 * What crosses this seam is storage, not crypto — the same split OAuthStores makes.
 * Minting the raw value and hashing it happen in [se.soderbjorn.lunicle.ApiTokenCrypto],
 * once, above both backends; a store is handed a hash to keep and a hash to look up,
 * and never sees a token. So nothing here can return one: neither table holds it.
 *
 * @see se.soderbjorn.lunicle.store.ApiTokenStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.ApiTokenRecord
import se.soderbjorn.lunicle.clientserver.ApiTokenScope

interface ApiTokenStore {
    /**
     * Keep a new token's hash and facts. Returns the stored record.
     *
     * @param tokenHash SHA-256 of the raw token, hex.
     * @param tokenPrefix the display prefix — see ApiTokens.sq.
     * @param expiresAt epoch millis, or null for never.
     */
    suspend fun create(
        userId: Long,
        name: String,
        tokenHash: String,
        tokenPrefix: String,
        scope: ApiTokenScope,
        expiresAt: Long?,
    ): ApiTokenRecord

    /**
     * The token behind a hash, or null if there is none **or it has expired** — the two
     * are one answer, so a caller cannot forget the expiry check. Records the use as a
     * best-effort side effect: a failure to stamp `lastUsedAt` must not fail the
     * request, so it is never a precondition of the answer.
     */
    suspend fun authenticate(tokenHash: String): ApiTokenRecord?

    /** One person's tokens, oldest first. Expired ones included, so the list can say so. */
    suspend fun forUser(userId: Long): List<ApiTokenRecord>

    /**
     * Revoke one token — **only if it is [userId]'s**. Silent on an id that is not, which
     * is the whole safety of the route above it: ids are guessable, ownership is not.
     */
    suspend fun revoke(userId: Long, id: Long)

    /** Sweep tokens past their expiry. Returns how many. */
    suspend fun deleteExpired(): Long
}
