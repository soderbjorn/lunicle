/**
 * The small shims the test suite needs now that permissions are two ladders
 * (LNL-191).
 *
 * Both of these existed as *arguments* before: `create(name, prefix, isPublic =
 * true)` and a bundle of role keys. Neither survives, and rather than let every
 * fixture invent its own spelling of "everyone can see this board", the two
 * translations live here once — which is also where a reader can check that the
 * suite is testing the same thing it used to.
 */
package se.soderbjorn.lunicle

import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
import se.soderbjorn.lunicle.store.InstanceSettingsStore
import se.soderbjorn.lunicle.store.ProjectProvisioning
import se.soderbjorn.lunicle.store.RoleStore

/**
 * Create a project every reader can see, signed in or not — the audience-row
 * spelling of the old `isPublic = true`.
 *
 * A `guest → viewer` row, which is exactly what `is_public` meant: readable by a
 * caller with no session, granting nothing else. Every fixture that used to pass
 * `isPublic = true` wanted precisely that, and the ones that also wanted writes
 * granted them separately and still do.
 *
 * The rung is **not** a parameter, and used to be one nobody ever passed (LNL-202). It
 * could only ever have named Viewer and mean anything: the guest audience is capped
 * there, so a fixture handing it Contributor would seed a row the server refuses to write
 * and no longer honours on read — a shared way of building an impossible world. The tests
 * that want that row on purpose write it with `setAudienceRole` and say why.
 *
 * ── Two facts, not one, since LNL-203 ───────────────────────────────────────
 *
 * The row alone no longer publishes anything. "Allow projects to be public" is a term in
 * the access rule now rather than a guard on the picker, so a `guest → viewer` row on a
 * deployment that forbids public projects grants nothing — and forbidding them is the
 * **default**. A board is public when the row says so *and* the deployment permits it, so
 * this fixture writes both, and that is why [instanceSettings] is a parameter. A fixture
 * that seeded only the row would build a board nobody outside it can read while calling
 * itself the open-to-all one, which is exactly the sort of quietly wrong world this file
 * exists to prevent.
 */
internal suspend fun ProjectProvisioning.createOpenToAll(
    name: String,
    namePrefix: String,
    roles: RoleStore,
    instanceSettings: InstanceSettingsStore,
): ProjectRecord {
    instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)
    return create(name, namePrefix).also { roles.setAudienceRole(it.id, Audience.GUEST, ProjectRole.VIEWER) }
}
