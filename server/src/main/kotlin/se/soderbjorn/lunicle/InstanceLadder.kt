/**
 * The three things a booting instance has to settle about its own ladder: who is
 * staff, who owns the place (LNL-191), and — where its own configuration has
 * already decided — who it will admit.
 *
 * All three run unconditionally at startup, all are idempotent, and all are safe
 * to be interrupted — which is the property that lets them stand in for the parts
 * of the permission migration SQL and Firestore cannot express.
 *
 * ── Why these are not in the migration ──────────────────────────────────────
 *
 * `users.kind` is derived by matching an address against **the deployment's own
 * domain**, and a migration has no way to know what that is: the domain is
 * configuration, read from the brand manifest at boot. A migration that guessed
 * would be a migration that granted, which 33.sqm exists not to do.
 *
 * Ownership has a subtler reason. On a *migrated* volume 33.sqm does seat it, off
 * `is_sys_admin`, inside the same transaction that drops the column. On a **fresh**
 * volume there is no migration at all and no administrator yet — the first person
 * to sign in becomes one — so seating has to happen after somebody exists, which
 * means at boot, every boot, doing nothing on all but one of them.
 *
 * Admission is the same shape once more, from the other direction: the policy a
 * deployment can honour is a question about `brand.json` and two environment
 * variables, which is exactly what a migration cannot see. See
 * [settleAdmissionPolicy], and note it needs no accounts at all — so unlike the
 * seat above, boot is the only place it is called from.
 *
 * @see UserKind.forEmail
 * @see se.soderbjorn.lunicle.store.InstanceSettings.ownerUserId
 * @see se.soderbjorn.lunicle.store.InstanceSettings.isAdmissionStored
 */
package se.soderbjorn.lunicle

import se.soderbjorn.lunicle.clientserver.AdmissionPolicy
import se.soderbjorn.lunicle.store.InstanceSettingsStore
import se.soderbjorn.lunicle.store.UserStore

/**
 * Re-derive every account's staff/member kind against [domain], writing only the
 * rows that disagree.
 *
 * ── Idempotent by construction, not by remembering ──────────────────────────
 *
 * This applies exactly the rule sign-in applies, from the same function, so a row
 * a sign-in has already stamped is a row this pass finds correct and skips.
 * Re-running it is therefore not merely harmless, it is *silent*: the return value
 * is 0 on every boot after the first. That is also why an interrupted run needs no
 * resume marker — the next boot simply finishes it, and a half-run leaves people
 * on `member`, the lesser answer, rather than on a rung nobody gave them.
 *
 * It is a full pass over the accounts on every boot, which is the right trade
 * while `users` is a table an instance can hold in memory (both backends'
 * `selectAll` already does). If that ever stops being true, the shape to reach for
 * is a marker on the row and not a cleverer rule here — the rule has to stay the
 * one sign-in uses.
 *
 * @param domain [InstanceIdentity.domain] — the deployment's own domain, from
 *   `brand.json`, or null if it has none. Null is the default and makes every account
 *   a member, which is what a deployment that cannot tell its own people apart should
 *   say. It is a field of its own since LNL-192; it briefly rode on the Google chooser
 *   pin, which is a different question that happened to be spelled with the same
 *   string.
 * @return how many rows were corrected, for the startup log.
 */
suspend fun stampUserKinds(users: UserStore, domain: String?): Int {
    var corrected = 0
    users.selectAll().forEach { user ->
        val derived = UserKind.forEmail(user.email, domain)
        if (derived != user.kind) {
            users.setKind(user.id, derived)
            corrected++
        }
    }
    return corrected
}

/**
 * Make sure somebody owns this deployment, and change nothing if somebody already
 * does.
 *
 * ── The one grant this is allowed to make, and why it is safe ───────────────
 *
 * An instance with no owner cannot hand out any rights at all: creating projects,
 * attributing writes and managing the project list are all the owner's, so an
 * ownerless deployment is a brick with a sign-in page. So this seats one — but
 * only ever into a *vacancy*, and only ever an account that already holds
 * [InstanceRole.ADMIN], which on a fresh volume means the first person to sign in
 * and on a migrated one means nobody (33.sqm leaves `instance_role` null for
 * everyone and seats the owner itself).
 *
 * Two things keep that from being an escalation. It never overwrites a seated
 * owner, so a deliberate transfer stays transferred. And it never promotes
 * somebody who was not already an administrator, so it cannot invent authority —
 * it can only finish giving out authority the instance already recorded.
 *
 * A deployment with no accounts at all, or none with the administrator flag, is
 * left ownerless, which is correct: there is nobody to seat.
 *
 * ── Called at boot AND after every sign-in ──────────────────────────────────
 *
 * Boot alone was not enough, and a fresh instance is exactly where it failed. That
 * boot happens before anybody exists, so it correctly seats nobody — and then the
 * first person signs in, becomes an administrator, and the seat stays empty until
 * some unrelated restart. Everything that asks `ownsInstance` is missing meanwhile:
 * impersonation, handing the instance over, the project order, the cross-project
 * delete. The person the deployment belongs to spends their first session without
 * the powers it gives them.
 *
 * The two properties above are what make a second call site free rather than a
 * second rule to keep in step — it is idempotent and it cannot promote anybody the
 * instance has not already made an administrator. The vacancy check is also a single
 * lookup and returns before the account scan, so a seated instance pays almost
 * nothing for asking. See `AuthRoutes.seatOwnerIfVacant`.
 *
 * @return the id just seated, or null if nothing was done — which is every call but
 *   one in a deployment's life.
 */
suspend fun seatInstanceOwner(users: UserStore, instanceSettings: InstanceSettingsStore): Long? {
    val current = instanceSettings.current().ownerUserId
    // A stored id that names nobody is a vacancy too — an owner whose account was
    // deleted leaves the setting pointing at a hole, and refusing to re-seat there
    // would leave the instance permanently unadministrable.
    if (current != null && users.findById(current) != null) return null
    val candidate = users.selectAll().filter { it.isInstanceAdmin }.minByOrNull { it.id } ?: return null
    instanceSettings.setOwnerUserId(candidate.id)
    return candidate.id
}

/**
 * Give a deployment that has never chosen an admission policy one it can honour.
 *
 * ── The default that described somebody else's deployment ───────────────────
 *
 * [AdmissionPolicy.ANYONE] is the standing default, and it is the right one: an
 * unbranded install has every way in available and nothing restricted. On a
 * deployment that pins its Google chooser to one domain and cannot mail a code, it
 * is a sentence about a deployment this is not. Who-gets-in opens on "Anyone who
 * can sign in", selected and greyed, on an instance where nobody outside the domain
 * can reach a sign-in at all — and an administrator reads that as a setting they
 * have somehow been given rather than as one nobody ever chose.
 *
 * It is also the more consequential half of a dormant setting waking up. Unpin the
 * chooser or turn mail on, and a stored-by-nobody ANYONE becomes live: the instance
 * starts taking all comers on a configuration change that was about sign-in
 * ergonomics. Settling it here means the policy in force is always one somebody
 * either chose or could have.
 *
 * ── Only into a vacancy, and only downwards from the default ────────────────
 *
 * [InstanceSettings.isAdmissionStored] is the whole gate: a deployment that has
 * *chosen* ANYONE and then pinned itself keeps that choice, greyed, which is the
 * stranded-choice case `AdmissionState.selected` exists to report. This only fills
 * in for a deployment that has said nothing.
 *
 * And it can only ever land on a policy the deployment can honour — in practice
 * exactly one, because the two outward-facing policies live and die together (see
 * [InstanceIdentity.outsiderCanArrive]), so where ANYONE is unavailable
 * [AdmissionPolicy.STAFF_DOMAIN_ONLY] is the only candidate there is. Nothing is
 * written when ANYONE is honourable, and nothing is written on a deployment with no
 * door at all, where every policy is unavailable and the missing environment
 * variable is the thing to fix.
 *
 * @return the policy just settled, or null if nothing was done — which is every
 *   boot but the first, and every boot of an unbranded install.
 */
suspend fun settleAdmissionPolicy(
    instanceSettings: InstanceSettingsStore,
    identity: InstanceIdentity,
): AdmissionPolicy? {
    if (instanceSettings.current().isAdmissionStored) return null
    val options = identity.admissionState(AdmissionPolicy.ANYONE).options
    if (options.first { it.policy == AdmissionPolicy.ANYONE }.isSelectable) return null
    val honourable = options.filter { it.isSelectable }.map { it.policy }
    val settled = honourable.singleOrNull() ?: return null
    instanceSettings.setAdmissionPolicy(settled)
    return settled
}
