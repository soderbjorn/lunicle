/**
 * Session storage, on the volume.
 *
 * This file used to say that sessions were in memory, that a redeploy signed
 * everyone out, and that when the volume landed this is what would get
 * replaced. The volume has landed. Sessions are rows now, so a redeploy keeps
 * you signed in — the cookie in your browser still names a session that still
 * exists.
 *
 * That is a real change in what a session id *is*. In memory it was a secret
 * that died with the process; on disk it is a secret at rest, and a row here is
 * enough to be someone. Nothing logs it, and nothing returns it except
 * [SessionStore.create]'s caller.
 *
 * @see AuthRoutes
 * @see Database
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.db.LunicleDatabase
import java.security.SecureRandom
import java.util.Base64

/** The cookie the browser carries a session id in. */
const val SESSION_COOKIE = "lunicle_session"

/**
 * How long a session lasts.
 *
 * Sessions expire for two different reasons, and only one is about security.
 * The first is ordinary: a bearer credential that never expires is one a stolen
 * cookie can use forever. The second is that nothing else ever deletes these
 * rows — a user who closes the tab and never signs out leaves their session
 * behind, so without a cutoff this table only grows, on a volume whose trial
 * ceiling is half a gigabyte.
 *
 * Thirty days is a judgement call: long enough that the persistence is visible
 * — the point of the stage is that you come back and are still signed in — and
 * short enough to bound the table.
 */
private const val SESSION_LIFETIME_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

/**
 * Session ids, and the users behind them.
 *
 * @param database the open database.
 * @param now supplies timestamps; injectable so a test can age a session
 *   without waiting a month.
 */
class SessionStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.SessionStore {
    // SecureRandom, not Random: a session id is a bearer credential for the
    // whole account. Random is seeded predictably enough that ids could be
    // guessed from one another, which is the entire attack.
    private val random = SecureRandom()

    /**
     * Mint a session id for [userId] and store it.
     *
     * @return the new id, to be handed to the browser as a cookie and never
     *   logged.
     */
    override suspend fun create(userId: Long): String = withContext(DatabaseDispatcher) {
        // 32 bytes: comfortably past guessing, and url-safe so it survives a
        // cookie round-trip without encoding surprises.
        val bytes = ByteArray(32).also(random::nextBytes)
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        database.sessionsQueries.insert(id = id, user_id = userId, created_at = now())
        id
    }

    /**
     * The user behind [id], or null if it's unknown, expired, or forged.
     *
     * Runs on every request that cares who the caller is, which makes it the
     * hottest query in the server. One primary-key lookup joined to one row.
     */
    override suspend fun lookup(id: String?): UserRecord? {
        if (id == null) return null
        return withContext(DatabaseDispatcher) {
            database.sessionsQueries.findUser(id).executeAsOneOrNull()?.let {
                userRecordOf(
                    it.id, it.provider, it.provider_id, it.provider_name,
                    it.display_name, it.email, it.email_verified, it.kind, it.instance_role, it.mcp_enabled,
                )
            }
        }
    }

    /** Forget [id]. Idempotent — signing out twice is not an error. */
    override suspend fun destroy(id: String?) {
        if (id == null) return
        withContext(DatabaseDispatcher) {
            database.sessionsQueries.delete(id)
        }
    }

    /**
     * Delete sessions past [SESSION_LIFETIME_MILLIS].
     *
     * Called once at startup by `Application.module`. A startup sweep rather
     * than a scheduled job because a container that restarts on every deploy
     * gets swept often enough, and a timer would be a second thing to reason
     * about for a table that gains one row per sign-in.
     *
     * The honest limitation: a server left running for months does not sweep,
     * and [lookup] does not check expiry itself — so an old session stays
     * usable until a restart. That is the next thing to fix if sessions ever
     * carry more than a user id.
     *
     * @return how many were removed.
     */
    override suspend fun deleteExpired(): Long = withContext(DatabaseDispatcher) {
        database.sessionsQueries.deleteOlderThan(now() - SESSION_LIFETIME_MILLIS).value
    }

    /** How many sessions are live. For the startup/debug log only. */
    override suspend fun size(): Long = withContext(DatabaseDispatcher) {
        database.sessionsQueries.countAll().executeAsOne()
    }
}
