/**
 * The behaviour every [RoleStore] implementation must exhibit.
 *
 * The semantics pinned here: [RoleStore.seed] reports the whole [Role] vocabulary
 * and is idempotent; a fresh user holds nothing; [RoleStore.grant] is idempotent
 * and [RoleStore.revoke] takes exactly one role away, leaving the rest;
 * [RoleStore.hasRole] answers a single (user, project, role) grant while
 * [RoleStore.isMember] answers "holds anything here"; [RoleStore.memberIds],
 * [RoleStore.rolesFor] and [RoleStore.grantsForProject] read the grants as sets and
 * maps; and — the parity-critical part — grants are scoped strictly to their own
 * user and their own project.
 *
 * Every grant test seeds first, because the SQLite reference's `grant` resolves a
 * `role_key` against the seeded `roles` table; the Firestore backend stores the key
 * directly and does not need it, but seeding is idempotent and harmless there.
 *
 * A subclass per backend supplies the store and a way to make a user and a project
 * a grant can reference — real rows on SQLite (foreign keys), synthetic ids on
 * Firestore (a document store validates none).
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.Role

abstract class RoleStoreContract {
    protected abstract val store: RoleStore

    /** A fresh user a grant can reference, returning their id. */
    protected abstract suspend fun newUser(): Long

    /** A fresh project a grant can reference, returning its id. */
    protected abstract suspend fun newProject(): Long

    @Test
    fun `seed reports the whole role vocabulary and is idempotent`() = runBlocking {
        assertEquals(Role.entries.size, store.seed(), "seed reports how many roles the instance has")
        assertEquals(Role.entries.size, store.seed(), "and running it again reports the same, changing nothing")
    }

    @Test
    fun `a fresh user holds nothing`() = runBlocking {
        store.seed()
        val user = newUser()
        val project = newProject()
        assertFalse(store.hasRole(user, project, Role.CREATE_ISSUE))
        assertFalse(store.isMember(user, project))
        assertTrue(store.rolesFor(user, project).isEmpty())
        assertTrue(store.memberIds(project).isEmpty())
        assertTrue(store.grantsForProject(project).isEmpty())
    }

    @Test
    fun `grant then hasRole reports the granted role and not another`() = runBlocking {
        store.seed()
        val user = newUser()
        val project = newProject()
        store.grant(user, project, Role.CREATE_ISSUE)
        assertTrue(store.hasRole(user, project, Role.CREATE_ISSUE), "the granted role is held")
        assertFalse(store.hasRole(user, project, Role.COMMENT_ON_ISSUE), "an ungranted role is not")
    }

    @Test
    fun `grant is idempotent`() = runBlocking {
        store.seed()
        val user = newUser()
        val project = newProject()
        store.grant(user, project, Role.CREATE_ISSUE)
        store.grant(user, project, Role.CREATE_ISSUE)
        assertEquals(setOf(Role.CREATE_ISSUE), store.rolesFor(user, project), "granting twice holds one role")
    }

    @Test
    fun `isMember reflects any grant at all`() = runBlocking {
        store.seed()
        val user = newUser()
        val project = newProject()
        assertFalse(store.isMember(user, project))
        store.grant(user, project, Role.VIEW_PROJECT)
        assertTrue(store.isMember(user, project), "holding any role is membership")
    }

    @Test
    fun `memberIds returns everyone holding anything, and nobody who holds nothing`() = runBlocking {
        store.seed()
        val alice = newUser()
        val bob = newUser()
        val carol = newUser()
        val project = newProject()
        store.grant(alice, project, Role.CREATE_ISSUE)
        store.grant(bob, project, Role.VIEW_PROJECT)
        assertEquals(setOf(alice, bob), store.memberIds(project), "granted users are members; carol is not")
        assertFalse(carol in store.memberIds(project))
    }

    @Test
    fun `rolesFor returns every role a user holds in a project`() = runBlocking {
        store.seed()
        val user = newUser()
        val project = newProject()
        store.grant(user, project, Role.CREATE_ISSUE)
        store.grant(user, project, Role.COMMENT_ON_ISSUE)
        assertEquals(setOf(Role.CREATE_ISSUE, Role.COMMENT_ON_ISSUE), store.rolesFor(user, project))
    }

    @Test
    fun `grantsForProject maps each user to the roles they hold`() = runBlocking {
        store.seed()
        val alice = newUser()
        val bob = newUser()
        val project = newProject()
        store.grant(alice, project, Role.CREATE_ISSUE)
        store.grant(alice, project, Role.COMMENT_ON_ISSUE)
        store.grant(bob, project, Role.VIEW_PROJECT)
        assertEquals(
            mapOf(
                alice to setOf(Role.CREATE_ISSUE, Role.COMMENT_ON_ISSUE),
                bob to setOf(Role.VIEW_PROJECT),
            ),
            store.grantsForProject(project),
        )
    }

    @Test
    fun `revoke removes one role and leaves the rest`() = runBlocking {
        store.seed()
        val user = newUser()
        val project = newProject()
        store.grant(user, project, Role.CREATE_ISSUE)
        store.grant(user, project, Role.COMMENT_ON_ISSUE)
        store.revoke(user, project, Role.CREATE_ISSUE)
        assertFalse(store.hasRole(user, project, Role.CREATE_ISSUE), "the revoked role is gone")
        assertTrue(store.hasRole(user, project, Role.COMMENT_ON_ISSUE), "the other role stays")
        assertTrue(store.isMember(user, project), "and they are still a member")
    }

    @Test
    fun `revoke is idempotent`() = runBlocking {
        store.seed()
        val user = newUser()
        val project = newProject()
        store.revoke(user, project, Role.CREATE_ISSUE)
        assertFalse(store.hasRole(user, project, Role.CREATE_ISSUE), "revoking what nobody holds is not an error")
    }

    @Test
    fun `grants are scoped to their project`() = runBlocking {
        store.seed()
        val user = newUser()
        val here = newProject()
        val elsewhere = newProject()
        store.grant(user, here, Role.CREATE_ISSUE)
        assertTrue(store.hasRole(user, here, Role.CREATE_ISSUE))
        assertFalse(store.hasRole(user, elsewhere, Role.CREATE_ISSUE), "a grant in one project does not reach another")
        assertFalse(store.isMember(user, elsewhere))
        assertTrue(store.memberIds(elsewhere).isEmpty())
    }

    @Test
    fun `grants are scoped to their user`() = runBlocking {
        store.seed()
        val alice = newUser()
        val bob = newUser()
        val project = newProject()
        store.grant(alice, project, Role.CREATE_ISSUE)
        assertFalse(store.hasRole(bob, project, Role.CREATE_ISSUE), "alice's grant is not bob's")
        assertTrue(store.rolesFor(bob, project).isEmpty())
    }
}
