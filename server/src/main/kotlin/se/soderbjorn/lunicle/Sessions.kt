/**
 * In-memory session storage for Stage 2's popup slice.
 *
 * Deliberately not persisted. The database is the *next* slice, and mixing it in
 * here would mean debugging OAuth and SQLite at the same time — which is exactly
 * what splitting the stage was meant to avoid. The consequence is honest and
 * visible: a redeploy signs everyone out, the same way it resets the counter
 * today. When the volume lands, this file is what gets replaced.
 *
 * @see AuthRoutes
 */
package se.soderbjorn.lunicle

import se.soderbjorn.lunicle.clientserver.SignedInUser
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** The cookie the browser carries a session id in. */
const val SESSION_COOKIE = "lunicle_session"

/**
 * Session ids, and the users behind them.
 *
 * [ConcurrentHashMap] rather than a plain map because Netty serves requests on
 * many threads and this is shared mutable state; the counter gets away with an
 * atomic, this cannot.
 */
class SessionStore {
    private val sessions = ConcurrentHashMap<String, SignedInUser>()

    // SecureRandom, not Random: a session id is a bearer credential for the
    // whole account. Random is seeded predictably enough that ids could be
    // guessed from one another, which is the entire attack.
    private val random = SecureRandom()

    /**
     * Mint a session id for [user] and remember it.
     *
     * @return the new id, to be handed to the browser as a cookie and never
     *   logged.
     */
    fun create(user: SignedInUser): String {
        // 32 bytes: comfortably past guessing, and url-safe so it survives a
        // cookie round-trip without encoding surprises.
        val bytes = ByteArray(32).also(random::nextBytes)
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions[id] = user
        return id
    }

    /** The user behind [id], or null if it's unknown, expired, or forged. */
    fun lookup(id: String?): SignedInUser? = id?.let(sessions::get)

    /** Forget [id]. Idempotent — signing out twice is not an error. */
    fun destroy(id: String?) {
        id?.let(sessions::remove)
    }

    /** How many sessions are live. For the startup/debug log only. */
    val size: Int get() = sessions.size
}
