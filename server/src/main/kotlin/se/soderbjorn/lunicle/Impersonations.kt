/**
 * Admin impersonation: an admin acting as somebody else, to see what they see.
 *
 * ── The rule this file exists to make unbreakable ────────────────────────────
 *
 * **The client never says who it is.** Impersonation is a fact the server holds,
 * keyed by the session cookie, and every request re-derives it. The tempting
 * alternative — an `X-Act-As: 7` header, or a userId in the request body — would
 * hand every signed-in user the ability to become anyone by editing one line in
 * devtools. There is no amount of client-side checking that fixes that, because
 * the check would also be in the client.
 *
 * So the only two things a caller can say are "start impersonating this id" and
 * "stop", and the first is refused unless the **session's real user** is an
 * admin. See [AuthRoutes]' impersonation routes.
 *
 * ── Why in memory ───────────────────────────────────────────────────────────
 *
 * Because impersonation is a thing you are *doing*, not a thing you *are*. It
 * lasts as long as you are looking, and it should not be able to outlive the
 * process and surprise you: an admin who impersonated a user last Tuesday and
 * comes back today should be themselves. A restart being an implicit "stop" is a
 * feature.
 *
 * The trade is that a redeploy drops everyone back to their own account
 * mid-session. That is the correct direction to fail — the effective user gets
 * *narrower* on restart, never wider, so nothing can be done under a restart
 * that could not be done before it.
 *
 * Not a database column for the same reason, plus one more: `sessions` rows are
 * the thing an attacker with a stolen cookie already has. Keeping impersonation
 * out of them means a leaked backup of that table cannot describe anyone as
 * anything other than themselves.
 */
package se.soderbjorn.lunicle

import java.util.concurrent.ConcurrentHashMap

/**
 * What a session is impersonating, when it is impersonating at all.
 *
 * Two shapes, because "become that account" and "become nobody" are genuinely
 * different acts and a nullable user id could not tell them apart from "not
 * impersonating". A session with no [Impersonation] at all is being itself.
 */
sealed interface Impersonation {
    /** Acting as a specific account. */
    data class AsUser(val userId: Long) : Impersonation

    /**
     * Acting as a signed-out visitor — no account at all (LNL-103).
     *
     * The effective user is null, exactly as a genuinely signed-out caller's is,
     * so the admin sees the public view; only the *real* user stays the admin, so
     * "Stop impersonating" is still reachable. See [Caller] and resolveCaller.
     */
    data object AsSignedOut : Impersonation
}

/**
 * Who each session is currently acting as.
 *
 * Session id → [Impersonation]. A session with no entry is being itself, which is
 * every session almost always.
 *
 * [ConcurrentHashMap] rather than a plain map with a lock: Netty serves on a
 * pool and this is read on *every* request — it is the one piece of per-request
 * state in this server that is not behind [DatabaseDispatcher]'s single thread.
 */
class Impersonations {
    private val bySession = ConcurrentHashMap<String, Impersonation>()

    /**
     * Start acting as [userId].
     *
     * Deliberately takes no admin flag and performs no check. This class cannot
     * do the check safely — it would have to be handed the answer, and a
     * collaborator that trusts its argument is not a boundary. The authorisation
     * lives at the route, which is the only place holding the *session's* real
     * user rather than one it was passed. See AuthRoutes.
     */
    fun start(sessionId: String, userId: Long) {
        bySession[sessionId] = Impersonation.AsUser(userId)
    }

    /** Start acting as a signed-out visitor — see [Impersonation.AsSignedOut]. */
    fun startSignedOut(sessionId: String) {
        bySession[sessionId] = Impersonation.AsSignedOut
    }

    /**
     * Stop, and go back to being yourself.
     *
     * Idempotent, and nullable-tolerant, mirroring [SessionStore.destroy]: "stop"
     * on a session that never started — or on a caller with no cookie at all — is
     * a no-op rather than something for the caller to check first. Every call site
     * would otherwise repeat the same null guard to reach the same nothing.
     */
    fun stop(sessionId: String?) {
        if (sessionId != null) bySession.remove(sessionId)
    }

    /** What this session is acting as, or null when it is being itself — or has no session. */
    fun current(sessionId: String?): Impersonation? = sessionId?.let(bySession::get)

    /**
     * Forget every impersonation by [sessionId]'s owner.
     *
     * Called on sign-out. Without it, a session id could in principle be reissued
     * to a later session — SessionStore's ids are random and this is
     * vanishingly unlikely, but "vanishingly unlikely" is not a security
     * property, and the cost of being certain is one line.
     */
    fun clear(sessionId: String?) {
        if (sessionId != null) bySession.remove(sessionId)
    }
}

/**
 * Who a request is *acting as*, and who it really is.
 *
 * Both, because the two answers authorise different things and conflating them
 * is the bug this type exists to prevent:
 *
 *  - [effective] is the answer to "what may this request do?". Every
 *    [AccessControl] call takes it, and it is the name the UI shows. An
 *    impersonating admin's effective user is an ordinary user with ordinary
 *    rights — that is the entire point, and an impersonation that kept the admin
 *    bit would prove nothing about what the impersonated user can see.
 *  - [real] is the answer to "may this request start or stop impersonating?".
 *    Only ever asked by the impersonation routes. It has to be the real user:
 *    while impersonating, the effective user is not an admin, so an effective
 *    check would make "Stop impersonating" impossible — you would be locked in
 *    as the user you became.
 *
 * @property isImpersonating whether the two differ. Not derived by comparing
 *   them at the call site, because an admin impersonating *themselves* is a
 *   legal no-op that would compare equal.
 */
data class Caller(
    val effective: UserRecord?,
    val real: UserRecord?,
    val isImpersonating: Boolean = false,
) {
    /** Whether the *real* user may impersonate. The menu's gate, and the routes'. */
    val canImpersonate: Boolean get() = real?.isSysAdmin == true
}
