/**
 * The capability behind owner impersonation: a short-lived grant that lets a
 * **signed-out** browser sign itself in as anybody, once armed by the instance
 * owner.
 *
 * ── What replaced the costume, and why ──────────────────────────────────────
 *
 * This file's predecessor kept a per-session costume: the effective user became
 * somebody else while the real user stayed the owner, and an address with no
 * account got a fabricated record. Nothing ran the sign-in pipeline — no admission
 * check, no `users.upsert`, no [UserKind] stamp, no owner seating, no row. The
 * questions worth asking of a deployment were exactly the ones that design could
 * not answer.
 *
 * So the owner is now signed out and *genuinely signed in* as the target instead.
 * The only thing substituted is the **proof**: a Google code exchange or a mailed
 * one-time code becomes an owner-authorised assertion, and everything downstream of
 * a [ProviderIdentity] is the same function the real paths call. The cost is that
 * probing creates real accounts — which is the point rather than a regret.
 *
 * ── The one deliberate exception to that claim (LUS-2) ──────────────────────
 *
 * `POST /oauth/consent` refuses a probe session. It is written down here rather
 * than left to be rediscovered as an inconsistency, because "the same code path as
 * a real sign-in" is a claim a future maintainer will build on and this is the
 * single place it is not true.
 *
 * The justification is not convenience. Consent is by definition a statement
 * somebody makes about *their own* identity — the consent page names the account
 * out loud and the click is the whole security boundary of the MCP design — and a
 * probe session is exactly the state in which no such statement can be made: the
 * page said the worn account's name and the human who clicked was the owner.
 *
 * What made it worth an exception rather than a note is that approving is the only
 * act of impersonation that **leaves a credential behind**. Everything else a probe
 * does dies with the grant, the session or the process; a token does not. It is an
 * access token plus a thirty-day rotating refresh token bound to the worn user's
 * id, and the token path deliberately honours no impersonation — so it survives the
 * probe ending, the boot sweep, and the feature switch being turned off and
 * applied. That defeats the off-switch guarantee below, which is otherwise whole.
 *
 * The refusal is at the POST only. A probing owner can still drive
 * `GET /oauth/authorize` and watch the client resolve, PKCE and redirect validation
 * pass, `canUseMcp` evaluate and the consent card render — so "can this person
 * connect an agent?" is still most of the way answerable by wearing them. What is
 * lost is only the final, irreversible click, and that is the trade.
 *
 * Note the corollary for revocation, which cuts the other way: the connections
 * routes resolve through `resolveCaller`, so a probe session reaches the worn
 * account's own `DELETE /api/mcp/connections/{clientId}`. Impersonation is
 * currently the **only** administrative revoke surface for agent tokens, and
 * disarming removes it. Check `oauth_tokens` *before* turning impersonation off,
 * not after.
 *
 * ── The rule this file exists to make unbreakable ────────────────────────────
 *
 * **The client never says who it is.** An address in a request body is a petition
 * to *become*, honoured only against a live grant resolved from the probe cookie.
 * There is no header, field or parameter anywhere on this server that asserts
 * identity. The tempting alternative — an `X-Act-As: 7` header — would hand every
 * signed-in user the ability to become anyone by editing one line in devtools, and
 * there is no amount of client-side checking that fixes that, because the check
 * would also be in the client.
 *
 * ── Why in memory ───────────────────────────────────────────────────────────
 *
 * The reasoning the costume gave transfers almost verbatim. Impersonation is a
 * thing you are *doing*, not a thing you *are*: it lasts as long as you are
 * looking, and it should not be able to outlive the process and surprise you. An
 * owner who armed this last Tuesday and comes back today should be themselves. A
 * restart being an implicit "stop" is a feature.
 *
 * It gains one more reason here. This grant is a **bearer credential equal in power
 * to the owner's own session cookie** — it mints the owner's session on demand —
 * and a `sessions` row is the thing an attacker with a stolen database already has.
 * Keeping the capability out of every table means a leaked backup describes nobody
 * as anything other than themselves. What *does* persist is `sessions.probe_id`,
 * and that is a label rather than a capability: it says a session was minted
 * without proof, which is what puts the marker on screen and what
 * `deleteProbeSessions` sweeps at boot. Holding it grants nothing.
 *
 * The trade is that a redeploy drops a probing owner back to signed-out mid-session
 * — the correct direction to fail, and the whole of the gate's off-switch
 * guarantee. See [ImpersonationConfig].
 *
 * @see ProbeGrants
 * @see se.soderbjorn.lunicle.store.SessionStore.deleteProbeSessions
 */
package se.soderbjorn.lunicle

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * How long an armed grant lasts.
 *
 * Thirty minutes: long enough for a session of poking at a deployment without
 * re-arming, short enough that an arm somebody walked away from is not a standing
 * key to the instance. It bounds a credential that cannot be revoked from anywhere
 * but this process, which is why it is minutes rather than the session table's
 * thirty days.
 */
const val PROBE_GRANT_LIFETIME_MILLIS: Long = 30L * 60 * 1000

/**
 * The cookie an armed browser carries its grant in.
 *
 * Separate from [SESSION_COOKIE] and never a substitute for it. The two are
 * independent: while probing, the browser holds both — one saying who it is signed
 * in as, one saying what let it sign in that way — and only the three impersonation
 * routes may look at this one. `resolveCaller` must never read it, because the
 * moment it authenticates anything there are two ways to be signed in and every
 * permission check on the server has two doors.
 */
const val PROBE_COOKIE = "lunicle_probe"

/**
 * One owner's live permission to sign in as anybody.
 *
 * @property ownerUserId who armed it. Read on **every** use to re-ask whether they
 *   still own the instance — the entitlement is never remembered, only the fact
 *   that this person is the one to re-ask about. An owner who loses ownership
 *   mid-probe loses the grant on their next request.
 * @property issuedAt when it was armed. Logged, never compared — [expiresAt] is the
 *   only thing that decides anything.
 * @property expiresAt the moment it stops working.
 */
data class Grant(
    val ownerUserId: Long,
    val issuedAt: Long,
    val expiresAt: Long,
)

/**
 * Every live grant, by probe id.
 *
 * [ConcurrentHashMap] for the reason the costume's map gave: Netty serves on a pool
 * and this is read off the request path, outside [DatabaseDispatcher]'s single
 * thread.
 *
 * @param now supplies timestamps; injectable so a test can age a grant without
 *   waiting half an hour.
 */
class ProbeGrants(private val now: () -> Long = System::currentTimeMillis) {
    private val byProbeId = ConcurrentHashMap<String, Grant>()

    // SecureRandom, not Random, and 32 bytes, exactly as SessionStore mints a
    // session id — this value is worth at least as much as one. A predictably
    // seeded generator would let ids be guessed from one another, which is the
    // entire attack.
    private val random = SecureRandom()

    /**
     * Arm [ownerUserId] and return the probe id to hand back as a cookie.
     *
     * Deliberately takes no owner flag and performs no check, exactly as its
     * predecessor did not: this class cannot do the check safely — it would have to
     * be handed the answer, and a collaborator that trusts its argument is not a
     * boundary. The authorisation lives at the route, which is the only place
     * holding the *session's* user rather than one it was passed.
     *
     * **One live grant per owner.** Any existing one is revoked first, so an arm
     * somebody abandoned cannot sit waiting behind the one they are using, and
     * re-arming genuinely invalidates the old id rather than adding a second key.
     *
     * @return the new id, to be handed to the browser as a cookie and never logged
     *   — see this file's preamble on what it is worth.
     */
    fun arm(ownerUserId: Long): String {
        byProbeId.entries.removeIf { it.value.ownerUserId == ownerUserId }
        val bytes = ByteArray(32).also(random::nextBytes)
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val issuedAt = now()
        byProbeId[id] = Grant(
            ownerUserId = ownerUserId,
            issuedAt = issuedAt,
            expiresAt = issuedAt + PROBE_GRANT_LIFETIME_MILLIS,
        )
        return id
    }

    /**
     * The live grant behind [probeId], or null for absent, unknown or expired.
     *
     * Null-tolerant, mirroring [SessionStore.destroy]: a caller with no probe cookie
     * asks the same question as one with a forged id and gets the same answer,
     * rather than every call site repeating a null guard to reach the same nothing.
     *
     * An expired entry is **removed** rather than merely ignored. This map has no
     * other sweep — there is no timer and no startup pass, because a restart empties
     * it entirely — so resolution is the only thing that can collect litter.
     */
    fun resolve(probeId: String?): Grant? {
        if (probeId == null) return null
        val grant = byProbeId[probeId] ?: return null
        if (grant.expiresAt <= now()) {
            byProbeId.remove(probeId)
            return null
        }
        return grant
    }

    /** Revoke [probeId]. Null-tolerant and idempotent — stopping twice is not an error. */
    fun revoke(probeId: String?) {
        if (probeId != null) byProbeId.remove(probeId)
    }

    /** How many grants are live, expired ones included. For tests and the debug log only. */
    fun size(): Int = byProbeId.size
}
