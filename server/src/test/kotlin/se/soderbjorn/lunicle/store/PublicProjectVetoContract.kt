/**
 * "Allow projects to be public" is a term in the access rule — on both backends (LNL-203).
 *
 * ── The defect this pins ────────────────────────────────────────────────────
 *
 * The switch was consulted in exactly one place, `AccessControl.canSetAudience`, which is
 * to say when somebody tried to *edit* a project's guest row. That is a guard in front of
 * the editor, not a term in the sentence that decides who reads what, so turning it off
 * did nothing at all to boards that were already public — and, because the guard refused
 * *any* write to the row rather than only a grant, it simultaneously took away the only
 * in-app way to close one. The control meant to stop public projects left the board public
 * and removed the owner's ability to make it private.
 *
 * ── Why this is a store contract and not one more test beside the ladder ────
 *
 * [se.soderbjorn.lunicle.AccessControlLadderTest] pins the rule as a function of its
 * inputs, over SQLite. It would not catch the rule being expressed against *one* backend:
 * the guest row lives in `project_audience_roles` on SQLite and in a map on a project
 * document on Firestore, and the switch itself is a settings row on one and a document
 * field on the other. Both of [se.soderbjorn.lunicle.AccessControl]'s collaborators are
 * therefore supplied per backend and the same assertions run twice — the shape
 * [GuestAudienceCeilingContract] established for its neighbouring rule.
 *
 * ── What is pinned ─────────────────────────────────────────────────────────
 *
 *  1. **Veto off, guest row present → a caller with no session cannot read the board.**
 *     The practical failure, at the rule.
 *  2. **Flipping the switch is reversible and rewrites nothing.** The stored rows are read
 *     before and after and compared, both ways. That is what makes this a filter rather
 *     than a migration: nothing is lost while the veto is on, and lifting it restores
 *     exactly the access the row still describes.
 *  3. **Withdrawal is always allowed.** An owner may set the guest row to "No access" while
 *     the veto is on; granting is still refused, whoever asks.
 *  4. **The compound case as one test** — publish, veto, and confirm both halves at once:
 *     strangers are out *and* the owner is not stuck.
 *  5. **A guest row at Viewer with the veto on still reads publicly.** The thing the fix
 *     must not break.
 *  6. **Only strangers are affected.** A member row goes on granting under the veto, and a
 *     member's own row is untouched.
 *
 * A subclass per backend supplies the two stores and a project id their rows can hang off.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.AccessControl
import se.soderbjorn.lunicle.Audience
import se.soderbjorn.lunicle.ProjectRole
import se.soderbjorn.lunicle.UserRecord
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey

abstract class PublicProjectVetoContract {
    protected abstract val roles: RoleStore
    protected abstract val instanceSettings: InstanceSettingsStore

    /** A fresh project the rows under test hang off, returning its id. */
    protected abstract suspend fun newProject(): Long

    /**
     * A fresh ordinary account — never an instance administrator, who reaches Owner
     * everywhere without a row and would make every assertion here vacuous.
     */
    protected abstract suspend fun newAccount(): UserRecord

    private val access: AccessControl by lazy { AccessControl(roles, instanceSettings) }

    /** A board that has been published: an owner, the switch on, and a `guest → viewer` row. */
    private suspend fun publishedProject(): Pair<Long, UserRecord> {
        val project = newProject()
        val owner = newAccount()
        roles.setRole(owner.id, project, ProjectRole.OWNER)
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)
        roles.setAudienceRole(project, Audience.GUEST, ProjectRole.VIEWER)
        return project to owner
    }

    private suspend fun allowPublic(allowed: Boolean) =
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, allowed)

    // ── The read ─────────────────────────────────────────────────────────────

    /** The thing the fix must not break: with the switch on, the row publishes the board. */
    @Test
    fun `a guest row at viewer reads publicly while the deployment allows it`() = runBlocking {
        val (project, _) = publishedProject()
        assertEquals(
            ProjectRole.VIEWER,
            access.effectiveRole(null, project),
            "Publishing a board is the feature; the veto must not have taken it away.",
        )
    }

    /** The practical failure: the switch goes off and the board is still fully public. */
    @Test
    fun `the veto makes an already-published board private to a caller with no session`() = runBlocking {
        val (project, _) = publishedProject()
        allowPublic(false)
        assertNull(
            access.effectiveRole(null, project),
            "The switch is off and a caller with no session at all still reaches this board.",
        )
    }

    /**
     * Reversible, and nothing is rewritten in either direction.
     *
     * The stored rows are compared before and after each flip. That is the whole reason the
     * veto is a filter over the rows rather than a rewrite of them: an administrator who
     * turns the switch off has not destroyed anything, and turning it back on restores
     * exactly the access the row already described, on every board at once.
     */
    @Test
    fun `flipping the switch restores the previous access and rewrites no row`() = runBlocking {
        val (project, _) = publishedProject()
        val stored = roles.audienceRoles(project)
        assertEquals(
            ProjectRole.VIEWER,
            stored[Audience.GUEST],
            "Precondition: the board was meant to be published.",
        )

        allowPublic(false)
        assertNull(access.effectiveRole(null, project))
        assertEquals(stored, roles.audienceRoles(project), "Silencing the guest row rewrote it.")

        allowPublic(true)
        assertEquals(
            ProjectRole.VIEWER,
            access.effectiveRole(null, project),
            "Lifting the veto did not restore the access the row still describes.",
        )
        assertEquals(stored, roles.audienceRoles(project), "Restoring the access rewrote the row.")
    }

    /** A member row is not about strangers, so the veto has nothing to say about it. */
    @Test
    fun `the veto leaves a member row granting`() = runBlocking {
        val project = newProject()
        val member = newAccount()
        allowPublic(false)
        roles.setAudienceRole(project, Audience.MEMBER, ProjectRole.CONTRIBUTOR)

        assertEquals(
            ProjectRole.CONTRIBUTOR,
            access.effectiveRole(member, project),
            "The publish veto closed the members row, which is a statement about accounts.",
        )
        assertNull(access.effectiveRole(null, project), "A stranger got in through a members row.")
    }

    /** And somebody's own row is untouched by it, veto or no veto. */
    @Test
    fun `the veto leaves an own row alone`() = runBlocking {
        val (project, _) = publishedProject()
        val member = newAccount()
        roles.setRole(member.id, project, ProjectRole.MAINTAINER)
        allowPublic(false)

        assertEquals(
            ProjectRole.MAINTAINER,
            access.effectiveRole(member, project),
            "Silencing the guest row also cut somebody their own row seats.",
        )
    }

    // ── The write gate ───────────────────────────────────────────────────────

    /**
     * The withdrawal bug, alone: refusing every write to the row could not tell granting
     * from revoking, so "No access" was refused along with "Viewer".
     */
    @Test
    fun `an owner may withdraw the guest row while the veto is on`() = runBlocking {
        val (project, owner) = publishedProject()
        allowPublic(false)
        assertTrue(
            access.canSetAudience(owner, project, Audience.GUEST, rung = null),
            "The owner of a published board could not close it, which is the whole ticket.",
        )
    }

    /** And granting is still refused, which is the policy the switch actually expresses. */
    @Test
    fun `the veto still refuses a grant on the guest row`() = runBlocking {
        val (project, owner) = publishedProject()
        allowPublic(false)
        assertFalse(
            access.canSetAudience(owner, project, Audience.GUEST, ProjectRole.VIEWER),
            "A board was published on a deployment that forbids publishing.",
        )
    }

    /** The other two audiences are writable throughout: the veto is about strangers. */
    @Test
    fun `the veto refuses nothing on the member and staff rows`() = runBlocking {
        val (project, owner) = publishedProject()
        allowPublic(false)
        listOf(Audience.MEMBER, Audience.STAFF).forEach { audience ->
            assertTrue(
                access.canSetAudience(owner, project, audience, ProjectRole.CONTRIBUTOR),
                "The publish veto closed the ${audience.key} row.",
            )
            assertTrue(access.canSetAudience(owner, project, audience, rung = null))
        }
    }

    // ── The compound case ────────────────────────────────────────────────────

    /**
     * The ticket's own story, start to finish, as one test.
     *
     * Publish a board; turn the veto off; confirm that strangers are out **and** that the
     * owner is not stuck — they can complete the withdrawal, and once they have, the board
     * stays private even if the switch comes back on. Asserted together because the two
     * halves were one defect: the veto did not stop public access, and it removed the only
     * way to stop it by hand.
     */
    @Test
    fun `the owner of a published board can still close it after the veto goes on`() = runBlocking {
        val (project, owner) = publishedProject()

        allowPublic(false)
        assertNull(access.effectiveRole(null, project), "Strangers still read the board.")

        assertTrue(
            access.canSetAudience(owner, project, Audience.GUEST, rung = null),
            "The owner was refused the withdrawal.",
        )
        roles.setAudienceRole(project, Audience.GUEST, null)
        assertNull(roles.audienceRoles(project)[Audience.GUEST], "The withdrawal did not land.")

        // And it is a real withdrawal rather than a silenced row: lifting the veto does not
        // bring the board back, because there is nothing left to bring back.
        allowPublic(true)
        assertNull(
            access.effectiveRole(null, project),
            "A withdrawn guest row reappeared when the deployment allowed public projects again.",
        )
    }
}
