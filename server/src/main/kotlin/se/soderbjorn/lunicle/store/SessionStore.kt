/**
 * The persistence seam for live sessions: the ids browsers carry in their cookie,
 * and who each one is.
 *
 * One of the LNL-111 domain store interfaces. The reference implementation is
 * today's SQLite [se.soderbjorn.lunicle.SessionStore] (named by its
 * fully-qualified name in that class's supertype clause, since the two share a
 * simple name), where a session is a row and [lookup] is a JOIN back to `users`.
 *
 * ── The one join, and why the interface does not expose it ───────────────────
 *
 * [lookup] returns a whole [UserRecord] — in SQLite one primary-key lookup joined
 * to the user row, the single hottest query in the server. A document backend has
 * no such join, so its implementation resolves the user through an *injected*
 * `suspend (Long) -> UserRecord?` seam (wired to the identity store in production,
 * a synthetic map in the contract). That seam is a constructor detail of the
 * Firestore store and stays off this interface: every consumer asks the same
 * question — "the user behind this id, or null" — whichever backend answers it.
 * Same technique [se.soderbjorn.lunicle.store.SubscriptionStore]'s Firestore
 * implementation uses for its contact lookup.
 *
 * A session id is a bearer credential: a value here is enough to be someone, so
 * nothing logs it and nothing returns it except [create]'s caller.
 *
 * @see se.soderbjorn.lunicle.store.SessionStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.UserRecord

interface SessionStore {
    /**
     * Mint a session id for [userId] and store it.
     *
     * @param probeId the owner-impersonation grant this session was minted
     *   against, or null — which is every ordinary sign-in, and so the default.
     *   Stored as a **label**: it records that nobody proved this identity, which
     *   is what puts the marker on screen and what [deleteProbeSessions] sweeps.
     *   The capability itself is in memory and never here — see
     *   `se.soderbjorn.lunicle.ProbeGrants`.
     * @return the new id, to be handed to the browser as a cookie and never
     *   logged.
     */
    suspend fun create(userId: Long, probeId: String? = null): String

    /**
     * The user behind [id], or null if it is null, unknown, or forged.
     *
     * Runs on every request that cares who the caller is, which makes it the
     * hottest read in the server. Deliberately says nothing about [probeIdFor]'s
     * answer: widening the hottest read to carry a column only an armed deployment
     * can ever use would charge every request everywhere for a feature almost no
     * instance has switched on.
     */
    suspend fun lookup(id: String?): UserRecord?

    /**
     * The grant [id] was minted against, or null for a session somebody proved.
     *
     * A **second** read rather than a field on [lookup], for the reason that method
     * gives. `resolveCaller` asks it only when the impersonation gate is on, so a
     * deployment with the flag off pays nothing for the column's existence.
     *
     * Null for an unknown or null id too: a session that does not exist was
     * certainly not minted by a grant, and every caller wants the same answer for
     * both.
     */
    suspend fun probeIdFor(id: String?): String?

    /**
     * Delete every session minted against a grant, and report how many went.
     *
     * Called at startup **unconditionally**, whatever the impersonation gate says.
     * Grants never outlive a process, so every such session found at boot is
     * orphaned by definition and there is nothing to preserve — and gating it would
     * mean that switching the feature off mid-probe left somebody signed in as the
     * person they were wearing, with the marker gone. See
     * `se.soderbjorn.lunicle.resolveOwnerImpersonationEnabled`.
     */
    suspend fun deleteProbeSessions(): Long

    /** Forget [id]. Idempotent — signing out twice, or on a null id, is not an error. */
    suspend fun destroy(id: String?)

    /**
     * Delete sessions past their lifetime, and report how many went.
     *
     * Called once at startup by `Application.module`, so a container that restarts
     * on every deploy is swept often enough without a scheduled job.
     */
    suspend fun deleteExpired(): Long

    /** How many sessions are live. For the startup/debug log only. */
    suspend fun size(): Long
}
