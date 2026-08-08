/**
 * Who a request is, and how it came to be that.
 *
 * ── One user, where there used to be two ────────────────────────────────────
 *
 * This type used to carry an `effective` user and a `real` one, because
 * impersonation was a costume: the owner's session stayed the owner's while the
 * caller answered permission questions as somebody else. Under a probe session
 * there is nothing to tell apart — the owner was signed **out** and then signed in
 * for real as the target — so the two are always equal and only one survives.
 *
 * Keeping both would have been the trap worth naming. A pair of fields that can no
 * longer differ invites the next reader to write a guard against them differing,
 * and it would never fire.
 *
 * ── Which makes [isProbe] load-bearing ──────────────────────────────────────
 *
 * "Is this an impersonation?" used to be the comparison `effective != real`, and
 * under this design that comparison silently reads **false** — which is exactly the
 * wrong answer for the guards that depend on it. So it stopped being a comparison
 * and became a fact the session row carries: `sessions.probe_id`, the label saying
 * this session was minted without anybody proving the identity. It cannot be
 * derived, and it must never be derived again.
 *
 * @see ProbeGrants
 * @see se.soderbjorn.lunicle.store.SessionStore.probeIdFor
 */
package se.soderbjorn.lunicle

/**
 * The signed-in caller behind a request, resolved once per request by
 * `resolveCaller`.
 *
 * @property user who this request is, or null for a signed-out visitor. Every
 *   [AccessControl] call takes it and it is the name the UI shows. An owner
 *   probing as somebody else *is* that somebody, with their rights and no more —
 *   that is the whole point, and an impersonation that kept the owner's authority
 *   would prove nothing about what the person can reach.
 * @property isProbe whether this session was minted by an owner-impersonation
 *   grant rather than by proof of identity.
 *
 *   Read from the session row's `probe_id`, **never** by comparing users. Three
 *   things depend on it: the marker the client cannot dismiss; the guards that
 *   refuse to re-point or clear the worn account's e-mail address — which is the
 *   one thing full impersonation powers must not include, because redirecting an
 *   account's mail is redirecting the account; and the refusal at
 *   `POST /oauth/consent`, which is the single deliberate exception to the claim
 *   that a probe runs the same code as a real sign-in (see [ProbeGrants], where
 *   that exception is written down beside the claim it qualifies).
 *
 *   Note the OAuth routes ask [se.soderbjorn.lunicle.store.SessionStore.probeIdFor]
 *   directly rather than through `resolveCaller`: they authenticate off the cookie
 *   themselves, for the reasons OAuthServer gives, so the fact has to be fetched
 *   rather than inherited.
 * @property canImpersonate whether this caller may arm an impersonation: they own
 *   the instance **and** the deployment has the feature switched on. Both terms,
 *   in one field, so no surface can render the affordance without the gate having
 *   agreed. Defaulted to false because false is the safe direction for a gate, and
 *   because it keeps the "nobody is signed in" constructions one argument long.
 *
 *   Re-derived per request from [AccessControl.canImpersonate] rather than
 *   remembered from whenever anything started, so somebody who loses ownership
 *   mid-probe loses it on their next request.
 */
data class Caller(
    val user: UserRecord?,
    val isProbe: Boolean = false,
    val canImpersonate: Boolean = false,
)

/**
 * The deployment's owner-impersonation facility: whether it exists here, and the
 * grants that are live if it does.
 *
 * One object rather than two parameters threaded side by side, and that is the
 * point: a route cannot pick up the grants without also picking up the gate that
 * says whether it may honour them. Every seam that used to carry the costume map
 * carries this instead.
 *
 * The default is the shape almost every deployment has and every test that does not
 * care: switched off, with an empty store nothing will ever consult.
 *
 * @property isEnabled `LUNICLE_ENABLE_OWNER_IMPERSONATION`, resolved once at boot.
 *   See [resolveOwnerImpersonationEnabled] for why it fails closed.
 * @property grants the live grants. In memory, so a restart is an implicit "stop"
 *   for everybody — see [ProbeGrants].
 */
class OwnerImpersonation(
    val isEnabled: Boolean = false,
    val grants: ProbeGrants = ProbeGrants(),
)
