/**
 * The persistence seams for the authorization server: clients, login state,
 * codes, tokens.
 *
 * Four of the LNL-111 domain store interfaces, kept in one file for the same
 * reason their reference implementations are — they are one subsystem, and the
 * four tables only make sense together (a token points at a client and a user, a
 * code carries the login state's PKCE challenge forward, a client is what
 * `/authorize` touches). The reference implementations are today's SQLite
 * gateways in [se.soderbjorn.lunicle] — [se.soderbjorn.lunicle.OAuthClientStore]
 * and its three siblings — which implement these interfaces directly. A document
 * backend is free to model the storage differently as long as the behaviour the
 * contracts pin holds.
 *
 * What crosses these seams is the *storage*, not the crypto. The prefix checks,
 * the SHA-256 hashing, the PKCE verification and the "a secret is returned once
 * and stored only as a hash" rule are backend-agnostic and live in
 * `OAuthCrypto` and the gateways; they are not part of this surface. Nothing here
 * returns a raw code or token from storage, because the reference implementation
 * could not — the tables hold hashes.
 *
 * The nested [se.soderbjorn.lunicle.OAuthTokenStore.RefreshResult] deliberately
 * stays where it is defined, on the reference implementation: it is a result
 * *type* the interface refers to, not part of the storage contract, and moving it
 * here would ripple through `OAuthServer` for no gain.
 *
 * @see se.soderbjorn.lunicle.store.OAuthClientStoreContract
 * @see se.soderbjorn.lunicle.store.OAuthLoginStateStoreContract
 * @see se.soderbjorn.lunicle.store.OAuthCodeStoreContract
 * @see se.soderbjorn.lunicle.store.OAuthTokenStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.IssuedTokens
import se.soderbjorn.lunicle.OAuthClientRecord
import se.soderbjorn.lunicle.OAuthCodeRecord
import se.soderbjorn.lunicle.OAuthGrant
import se.soderbjorn.lunicle.OAuthLoginStateRecord
import se.soderbjorn.lunicle.OAuthTokenRecord
import se.soderbjorn.lunicle.OAuthTokenStore.RefreshResult

/**
 * The persistence seam for registered agents (RFC 7591 dynamic clients).
 *
 * A client row grants nothing on its own — it is a name and a set of callbacks;
 * the consent page is the security boundary. The parity-critical behaviour the
 * contract pins is that a registration round-trips (its name, redirect URIs and
 * grant types come back intact), that [isRegisteredRedirectUri] is exact-match
 * (the one check between this server and being an open redirector), and that
 * [sweepStale] removes an old, token-less, untouched registration while sparing a
 * fresh one.
 */
interface OAuthClientStore {
    /** Register a client and its redirect URIs in one write. */
    suspend fun register(
        clientName: String,
        redirectUris: List<String>,
        grantTypes: List<String>,
    ): OAuthClientRecord

    /** The client with [clientId], or null if unknown (or a wrong-prefix id). */
    suspend fun find(clientId: String): OAuthClientRecord?

    /** Did [clientId] register exactly [redirectUri]? Exact string equality. */
    suspend fun isRegisteredRedirectUri(clientId: String, redirectUri: String): Boolean

    /** Note that this client is still in use. Called at `/authorize`. */
    suspend fun touch(clientId: String)

    /** Delete registrations that never became anything; returns how many. */
    suspend fun sweepStale(): Long

    /** How many clients are registered. For the startup log only. */
    suspend fun size(): Long
}

/**
 * The persistence seam for a pending authorization — the request that lives
 * across a human's consent click.
 *
 * The parity-critical behaviour the contract pins is that expiry is enforced by
 * the lookup itself, not by the caller: a [find] past the row's expiry returns
 * null even though the row is still there, and [deleteExpired] is only the
 * disk-space sweep behind that. Also that [find] tolerates a null id (the consent
 * POST may carry none) and that [delete] is idempotent.
 */
interface OAuthLoginStateStore {
    /** Remember an authorization request across the consent click; returns its id. */
    suspend fun create(
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        resource: String,
        clientState: String,
        scope: String,
        userId: Long,
    ): String

    /** The pending authorization with [id], or null if unknown or expired. */
    suspend fun find(id: String?): OAuthLoginStateRecord?

    /** Forget it. Idempotent. */
    suspend fun delete(id: String)

    /** Sweep pending authorizations that timed out; returns how many. */
    suspend fun deleteExpired(): Long
}

/**
 * The persistence seam for authorization codes — the two-minute secret between
 * "Approve" and a token.
 *
 * The parity-critical behaviour the contract pins is single-use: [consume]
 * returns a code's contents exactly once and null forever after, because the
 * consume *is* the delete and both halves are one transaction. An expired code
 * consumes as null (expiry is in the lookup's WHERE clause), and [deleteExpired]
 * is the sweep behind that.
 */
interface OAuthCodeStore {
    /** Mint a code for an approved authorization; returns the raw code, stored only as a hash. */
    suspend fun create(
        userId: Long,
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        resource: String,
        scope: String,
    ): String

    /** Redeem a code, exactly once; null if unknown, already redeemed, or expired. */
    suspend fun consume(code: String): OAuthCodeRecord?

    /** Sweep codes that timed out; returns how many. */
    suspend fun deleteExpired(): Long
}

/**
 * The persistence seam for access and refresh tokens — what an agent actually
 * holds, and the refresh-family algorithm over them.
 *
 * The parity-critical behaviour the contract pins is the whole shape of a grant's
 * life: [validateAccessToken] accepts a live access token and rejects an expired
 * or wrong-type one (both filters are in the lookup); [rotateRefresh] mints a new
 * pair in the same family and spends the old one, treats a replay of an
 * already-consumed refresh token as theft — revoking the *whole family* — and
 * refuses (consuming nothing) when the owner cannot use MCP; and revoking a token
 * takes its family with it. [deleteExpired] removes an expired token while sparing
 * a live one in the same family.
 *
 * The result type is [se.soderbjorn.lunicle.OAuthTokenStore.RefreshResult].
 */
interface OAuthTokenStore {
    /** Mint an access + refresh pair in a brand new family. What a redeemed code produces. */
    suspend fun issueTokens(
        userId: Long,
        clientId: String,
        scope: String,
        resource: String,
    ): IssuedTokens

    /** Validate an access token; the token's facts, or null if unknown, expired, or wrong type. */
    suspend fun validateAccessToken(token: String): OAuthTokenRecord?

    /** Rotate a refresh token, and treat a replay as theft. See [RefreshResult]. */
    suspend fun rotateRefresh(refreshToken: String): RefreshResult

    /** Kill a whole grant. The theft response. */
    suspend fun revokeFamily(familyId: String)

    /** Revoke a presented token (RFC 7009); silent on an unknown token. */
    suspend fun revokeByToken(token: String)

    /** Every agent [userId] has connected. The Connections list. */
    suspend fun listGrants(userId: Long): List<OAuthGrant>

    /** Revoke one agent, for one user. */
    suspend fun revokeForUserAndClient(userId: Long, clientId: String)

    /** Sweep expired tokens; returns how many. */
    suspend fun deleteExpired(): Long

    /** How many token rows exist. For the startup log only. */
    suspend fun size(): Long
}
