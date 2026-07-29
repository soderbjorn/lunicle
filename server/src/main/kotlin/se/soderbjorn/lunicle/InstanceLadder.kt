/**
 * The two things a booting instance has to settle about its own ladder: who is
 * staff, and who owns the place (LNL-191).
 *
 * Both run unconditionally at startup, both are idempotent, and both are safe to
 * be interrupted — which is the property that lets them stand in for the parts of
 * the permission migration SQL and Firestore cannot express.
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
 * @see UserKind.forEmail
 * @see se.soderbjorn.lunicle.store.InstanceSettings.ownerUserId
 */
package se.soderbjorn.lunicle

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
 * @param domain the deployment's own domain, or null if it has none. Null makes
 *   every account a member, which is what a deployment that cannot tell its own
 *   people apart should say.
 *
 *   TODO(LNL-192): the caller passes the brand manifest's `googleHostedDomain`,
 *    which is the Google sign-in restriction and only accidentally the same string
 *    — a deployment that does not use Google sign-in has no way to name its domain
 *    at all today. LNL-192 adds a `domain` config field; this parameter is what it
 *    should be wired to.
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
 * ── The one grant a boot is allowed to make, and why it is safe ─────────────
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
 * left ownerless, which is correct: there is nobody to seat, and the first sign-in
 * creates one.
 *
 * @return the id just seated, or null if nothing was done — which is every boot
 *   but one.
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
