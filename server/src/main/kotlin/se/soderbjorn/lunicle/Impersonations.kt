/**
 * Impersonation: **the instance owner** acting as somebody else, to see what they
 * see.
 *
 * ── Who may, and why it is one person (LNL-197) ─────────────────────────────
 *
 * The owner alone, and not an instance administrator. This is the one facility in
 * the product that hands you another person's rights **with their writes attached**,
 * and that is deliberate: it exists to check what a stranger can reach while a board
 * is being built, and "could they file this?" is not a question a read-only preview
 * can answer. Full powers is what makes it useful; owner-only is what makes keeping
 * them acceptable. See `AccessControl.canImpersonate`.
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
 * So the only two things a caller can say are "start impersonating this address" and
 * "stop", and the first is refused unless the **session's real user** owns the
 * instance. See [AuthRoutes]' impersonation routes.
 *
 * ── Keyed on addresses, and what that buys ──────────────────────────────────
 *
 * The menu offers addresses because the permission model keys on them: staff-ness is
 * derived from the address, somebody can hold rungs before an account exists, and an
 * audience row is a statement about what an address *is*. Three of the states worth
 * checking are therefore **not accounts** — a stranger at the staff domain who has
 * never signed in, an outside address with no row at all, and a member who was added
 * and never arrived — so a list of accounts could not name them and a list of display
 * names would be a list of the wrong thing.
 *
 * The address with no row is [Impersonation.AsAddress], and it is a **preview**: no
 * `users` row is written, no `added_at` is set, and it appears in no project's People
 * list. The resolution lives here, in memory, keyed by session, and vanishes on stop.
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
 * Three shapes, because "become that account", "become an address nobody holds" and
 * "become nobody" are genuinely different acts and a nullable user id could tell none
 * of them apart from "not impersonating". A session with no [Impersonation] at all is
 * being itself.
 */
sealed interface Impersonation {
    /**
     * Acting as a specific account.
     *
     * Still an **id**, even though the route now takes an address: the address was
     * resolved to a row once, at the start, and holding the row's identity is what
     * keeps `resolveCaller`'s stale check meaningful — an account deleted out from
     * under the impersonation drops the impersonation, where re-resolving the address
     * every request would quietly turn it into [AsAddress] and carry on.
     */
    data class AsUser(val userId: Long) : Impersonation

    /**
     * Acting as an address that **holds no account** (LNL-197).
     *
     * The first-time arrival: what a person gets on the request after their very
     * first sign-in, before anything has been granted to them personally. It is the
     * only way to see that state, because it is by definition not an account and so
     * cannot be picked from a list of them.
     *
     * **A preview, and nothing is written.** No `users` row, no `added_at`, no
     * appearance in any project's People list. Both facts about the address live
     * right here in memory and go when the session stops impersonating — stop, and
     * there is no trace at all.
     *
     * @property email the address being worn. Also the effective user's name, since
     *   there is no provider to have supplied one — a preview of a person who has
     *   never introduced themselves.
     * @property kind staff or member, derived from the deployment's domain when the
     *   impersonation started rather than on every request. The domain is deploy-time
     *   configuration read once at boot (see [InstanceIdentity]), so it cannot change
     *   under a live impersonation — and a restart, which is the only thing that could
     *   change it, drops every impersonation anyway.
     */
    data class AsAddress(val email: String, val kind: UserKind) : Impersonation

    /**
     * Acting as a signed-out visitor — no account at all (LNL-103).
     *
     * The effective user is null, exactly as a genuinely signed-out caller's is,
     * so the owner sees the public view; only the *real* user stays the owner, so
     * "Stop impersonating" is still reachable. See [Caller] and resolveCaller.
     *
     * Distinct from [AsAddress], which is not the same state: a signed-out visitor
     * matches the `guest` audience and nothing above it, while an unknown address at
     * the staff domain matches `staff` the moment it signs in. Collapsing the two
     * would hide exactly the difference this is for.
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
     * Deliberately takes no owner flag and performs no check. This class cannot
     * do the check safely — it would have to be handed the answer, and a
     * collaborator that trusts its argument is not a boundary. The authorisation
     * lives at the route, which is the only place holding the *session's* real
     * user rather than one it was passed. See AuthRoutes.
     */
    fun start(sessionId: String, userId: Long) {
        bySession[sessionId] = Impersonation.AsUser(userId)
    }

    /**
     * Start acting as an address that holds no account — see
     * [Impersonation.AsAddress].
     *
     * This is the whole of the storage a previewed address gets: one entry in this
     * map. Nothing is inserted anywhere, which is the property the ticket cares most
     * about and the reason this method exists beside [start] rather than being folded
     * into it behind a "create the row if missing" convenience.
     */
    fun startAsAddress(sessionId: String, email: String, kind: UserKind) {
        bySession[sessionId] = Impersonation.AsAddress(email, kind)
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
 *    impersonating owner's effective user is an ordinary user with ordinary
 *    rights — that is the entire point, and an impersonation that kept the owner's
 *    authority would prove nothing about what the impersonated person can see.
 *  - [real] is the answer to "may this request start or stop impersonating?".
 *    Only ever asked by the impersonation routes. It has to be the real user:
 *    while impersonating, the effective user does not own the instance, so an
 *    effective check would make "Stop impersonating" impossible — you would be
 *    locked in as the person you became.
 *
 * @property isImpersonating whether the two differ. Not derived by comparing
 *   them at the call site, because an owner impersonating *themselves* is a
 *   legal no-op that would compare equal — and because an [Impersonation.AsAddress]
 *   effective user is a record no comparison could place.
 * @property canImpersonate whether the **real** user owns this instance, and so may
 *   start or stop (LNL-197).
 *
 *   A stored field rather than the derived property it used to be, and the change is
 *   the ticket: ownership is `instance_settings.owner_user_id` and no [UserRecord]
 *   carries it, so this cannot be read off [real] the way `isInstanceAdmin` could.
 *   `resolveCaller` fills it in from [AccessControl.canImpersonate] — which means the
 *   answer is re-derived on every request rather than remembered from whenever the
 *   impersonation started, and somebody who loses ownership mid-session stops acting
 *   as another person on their next request.
 *
 *   Defaulted to false so the "nobody is signed in" constructions stay one argument
 *   long, and because false is the safe direction for a gate.
 */
data class Caller(
    val effective: UserRecord?,
    val real: UserRecord?,
    val isImpersonating: Boolean = false,
    val canImpersonate: Boolean = false,
)
