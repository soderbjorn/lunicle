/**
 * The behaviour every [RoleStore] implementation must exhibit (LNL-191).
 *
 * The semantics pinned here: a fresh user holds nothing and a fresh project admits
 * nobody; [RoleStore.setRole] is single-valued and idempotent, so a person has one
 * rung per project and moving them rewrites it rather than adding a second; null
 * clears; [RoleStore.roleFor], [RoleStore.rolesForUser], [RoleStore.rolesForProject]
 * and [RoleStore.memberIds] read the own rows from either direction;
 * [RoleStore.audienceRoles] and [RoleStore.setAudienceRole] read and write at most
 * one row per [Audience]; and — the parity-critical part — rows are scoped strictly
 * to their own user, their own project, and their own audience.
 *
 * ── The max rule is tested here, and it is not the store's ──────────────────
 *
 * `effective = max(audience, own row)` lives in
 * [se.soderbjorn.lunicle.AccessControl] and nowhere else, so what this suite pins
 * is the *ingredients*: that the two tables are independent, that neither write
 * disturbs the other, and that clearing an own row leaves an audience row standing.
 * The last of those is the storage-level half of "an own row can raise somebody and
 * never cut them below their audience" — a backend where clearing a row also
 * removed somebody from their audience would make that rule unenforceable however
 * carefully AccessControl was written. The rule itself is exercised against the
 * ladder in AccessControlLadderTest.
 *
 * A subclass per backend supplies the store and a way to make a user and a project
 * a row can reference — real rows on SQLite (foreign keys), synthetic ids on
 * Firestore (a document store validates none).
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.Audience
import se.soderbjorn.lunicle.ProjectRole

abstract class RoleStoreContract {
    protected abstract val store: RoleStore

    /** A fresh user a row can reference, returning their id. */
    protected abstract suspend fun newUser(): Long

    /** A fresh project a row can reference, returning its id. */
    protected abstract suspend fun newProject(): Long

    // ── Own rows ─────────────────────────────────────────────────────────────

    @Test
    fun `a fresh user holds nothing and a fresh project admits nobody`() = runBlocking {
        val user = newUser()
        val project = newProject()
        assertNull(store.roleFor(user, project))
        assertTrue(store.rolesForUser(user).isEmpty())
        assertTrue(store.rolesForProject(project).isEmpty())
        assertTrue(store.memberIds(project).isEmpty())
        assertTrue(store.audienceRoles(project).isEmpty())
    }

    @Test
    fun `setRole then roleFor reports the rung`() = runBlocking {
        val user = newUser()
        val project = newProject()
        store.setRole(user, project, ProjectRole.CONTRIBUTOR)
        assertEquals(ProjectRole.CONTRIBUTOR, store.roleFor(user, project))
    }

    @Test
    fun `setRole is idempotent`() = runBlocking {
        val user = newUser()
        val project = newProject()
        store.setRole(user, project, ProjectRole.CONTRIBUTOR)
        store.setRole(user, project, ProjectRole.CONTRIBUTOR)
        assertEquals(mapOf(user to ProjectRole.CONTRIBUTOR), store.rolesForProject(project))
    }

    /** A person has ONE rung per project — the whole point of the shape. */
    @Test
    fun `setRole replaces the rung rather than adding a second`() = runBlocking {
        val user = newUser()
        val project = newProject()
        store.setRole(user, project, ProjectRole.CONTRIBUTOR)
        store.setRole(user, project, ProjectRole.ADMIN)
        assertEquals(ProjectRole.ADMIN, store.roleFor(user, project))
        assertEquals(mapOf(user to ProjectRole.ADMIN), store.rolesForProject(project))
        assertEquals(setOf(user), store.memberIds(project), "still one member, not two rows")
    }

    /** Demotion is a write like any other: nothing here refuses to go down a rung. */
    @Test
    fun `setRole moves somebody down as readily as up`() = runBlocking {
        val user = newUser()
        val project = newProject()
        store.setRole(user, project, ProjectRole.OWNER)
        store.setRole(user, project, ProjectRole.VIEWER)
        assertEquals(ProjectRole.VIEWER, store.roleFor(user, project))
    }

    @Test
    fun `a null rung removes the row, and doing it twice is not an error`() = runBlocking {
        val user = newUser()
        val project = newProject()
        store.setRole(user, project, ProjectRole.MAINTAINER)
        store.setRole(user, project, null)
        assertNull(store.roleFor(user, project))
        assertTrue(store.memberIds(project).isEmpty())
        store.setRole(user, project, null)
        assertNull(store.roleFor(user, project), "clearing what nobody holds is not an error")
    }

    @Test
    fun `rolesForUser reads one person's rungs across projects in one go`() = runBlocking {
        val user = newUser()
        val here = newProject()
        val there = newProject()
        val elsewhere = newProject()
        store.setRole(user, here, ProjectRole.VIEWER)
        store.setRole(user, there, ProjectRole.ADMIN)
        assertEquals(mapOf(here to ProjectRole.VIEWER, there to ProjectRole.ADMIN), store.rolesForUser(user))
        assertTrue(elsewhere !in store.rolesForUser(user).keys)
    }

    @Test
    fun `rolesForProject and memberIds read one project's rows`() = runBlocking {
        val alice = newUser()
        val bob = newUser()
        val carol = newUser()
        val project = newProject()
        store.setRole(alice, project, ProjectRole.CONTRIBUTOR)
        store.setRole(bob, project, ProjectRole.VIEWER)
        assertEquals(
            mapOf(alice to ProjectRole.CONTRIBUTOR, bob to ProjectRole.VIEWER),
            store.rolesForProject(project),
        )
        assertEquals(setOf(alice, bob), store.memberIds(project))
        assertTrue(carol !in store.memberIds(project))
    }

    @Test
    fun `rows are scoped to their project`() = runBlocking {
        val user = newUser()
        val here = newProject()
        val elsewhere = newProject()
        store.setRole(user, here, ProjectRole.ADMIN)
        assertEquals(ProjectRole.ADMIN, store.roleFor(user, here))
        assertNull(store.roleFor(user, elsewhere), "a rung in one project does not reach another")
        assertTrue(store.memberIds(elsewhere).isEmpty())
    }

    @Test
    fun `rows are scoped to their user`() = runBlocking {
        val alice = newUser()
        val bob = newUser()
        val project = newProject()
        store.setRole(alice, project, ProjectRole.ADMIN)
        assertNull(store.roleFor(bob, project), "alice's rung is not bob's")
        assertTrue(store.rolesForUser(bob).isEmpty())
    }

    // ── Audience rows ────────────────────────────────────────────────────────

    @Test
    fun `setAudienceRole then audienceRoles reports the rung`() = runBlocking {
        val project = newProject()
        store.setAudienceRole(project, Audience.MEMBER, ProjectRole.CONTRIBUTOR)
        assertEquals(mapOf(Audience.MEMBER to ProjectRole.CONTRIBUTOR), store.audienceRoles(project))
    }

    @Test
    fun `a project may admit all three audiences at once, at different rungs`() = runBlocking {
        val project = newProject()
        store.setAudienceRole(project, Audience.GUEST, ProjectRole.VIEWER)
        store.setAudienceRole(project, Audience.MEMBER, ProjectRole.CONTRIBUTOR)
        store.setAudienceRole(project, Audience.STAFF, ProjectRole.MAINTAINER)
        assertEquals(
            mapOf(
                Audience.GUEST to ProjectRole.VIEWER,
                Audience.MEMBER to ProjectRole.CONTRIBUTOR,
                Audience.STAFF to ProjectRole.MAINTAINER,
            ),
            store.audienceRoles(project),
        )
    }

    @Test
    fun `setAudienceRole replaces one audience's rung and leaves the others`() = runBlocking {
        val project = newProject()
        store.setAudienceRole(project, Audience.GUEST, ProjectRole.VIEWER)
        store.setAudienceRole(project, Audience.MEMBER, ProjectRole.CONTRIBUTOR)
        store.setAudienceRole(project, Audience.MEMBER, ProjectRole.MAINTAINER)
        assertEquals(
            mapOf(Audience.GUEST to ProjectRole.VIEWER, Audience.MEMBER to ProjectRole.MAINTAINER),
            store.audienceRoles(project),
        )
    }

    @Test
    fun `a null rung shuts an audience out again, and doing it twice is not an error`() = runBlocking {
        val project = newProject()
        store.setAudienceRole(project, Audience.GUEST, ProjectRole.VIEWER)
        store.setAudienceRole(project, Audience.MEMBER, ProjectRole.CONTRIBUTOR)
        store.setAudienceRole(project, Audience.GUEST, null)
        assertEquals(mapOf(Audience.MEMBER to ProjectRole.CONTRIBUTOR), store.audienceRoles(project))
        store.setAudienceRole(project, Audience.GUEST, null)
        assertEquals(mapOf(Audience.MEMBER to ProjectRole.CONTRIBUTOR), store.audienceRoles(project))
    }

    @Test
    fun `audience rows are scoped to their project`() = runBlocking {
        val here = newProject()
        val elsewhere = newProject()
        store.setAudienceRole(here, Audience.GUEST, ProjectRole.VIEWER)
        assertTrue(store.audienceRoles(elsewhere).isEmpty(), "one project's audience is not another's")
    }

    // ── The two tables are independent ───────────────────────────────────────

    /**
     * The storage-level half of "an own row never cuts somebody below their
     * audience": clearing the row leaves the audience standing, so there is nothing
     * a write here can do that would strand somebody outside a room the project
     * still admits them to. See the class preamble.
     */
    @Test
    fun `clearing an own row leaves the audience row standing`() = runBlocking {
        val user = newUser()
        val project = newProject()
        store.setAudienceRole(project, Audience.MEMBER, ProjectRole.CONTRIBUTOR)
        store.setRole(user, project, ProjectRole.ADMIN)
        store.setRole(user, project, null)
        assertNull(store.roleFor(user, project))
        assertEquals(
            mapOf(Audience.MEMBER to ProjectRole.CONTRIBUTOR),
            store.audienceRoles(project),
            "shutting one person's row does not shut the audience they were also in",
        )
    }

    @Test
    fun `shutting an audience out leaves own rows standing`() = runBlocking {
        val user = newUser()
        val project = newProject()
        store.setAudienceRole(project, Audience.GUEST, ProjectRole.VIEWER)
        store.setRole(user, project, ProjectRole.MAINTAINER)
        store.setAudienceRole(project, Audience.GUEST, null)
        assertTrue(store.audienceRoles(project).isEmpty())
        assertEquals(ProjectRole.MAINTAINER, store.roleFor(user, project), "the named person keeps their rung")
    }
}
