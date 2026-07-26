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
     * @return the new id, to be handed to the browser as a cookie and never
     *   logged.
     */
    suspend fun create(userId: Long): String

    /**
     * The user behind [id], or null if it is null, unknown, or forged.
     *
     * Runs on every request that cares who the caller is, which makes it the
     * hottest read in the server.
     */
    suspend fun lookup(id: String?): UserRecord?

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
