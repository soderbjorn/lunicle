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
 */
internal suspend fun ProjectProvisioning.createOpenToAll(
    name: String,
    namePrefix: String,
    roles: RoleStore,
    rung: ProjectRole = ProjectRole.VIEWER,
): ProjectRecord = create(name, namePrefix).also { roles.setAudienceRole(it.id, Audience.GUEST, rung) }
