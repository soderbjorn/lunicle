/**
 * The guest audience stops at Viewer — on both backends (LNL-202).
 *
 * ── Why this is a store contract and not one more test beside the ladder ────
 *
 * [se.soderbjorn.lunicle.AccessControlLadderTest] pins the rule as a function of its
 * inputs, over SQLite, and would catch a cap written wrongly. It would not catch a cap
 * written **once**: the rows this depends on live in `project_audience_roles` on SQLite
 * and in a map on a project document on Firestore, and the whole point of LNL-111's
 * store interfaces is that a rule expressed against the seam is one rule. So the two
 * collaborators [se.soderbjorn.lunicle.AccessControl] actually has —
 * [RoleStore] and [InstanceSettingsStore] — are supplied per backend, and the same
 * assertions run twice.
 *
 * The accounts are minted per backend rather than built here, because they have to be
 * real where the backend says so: `project_roles` has a foreign key to `users` on SQLite,
 * so an owner whose row references an account nobody created is a constraint violation
 * rather than a test. On Firestore there is nothing to satisfy and a plain record will do.
 * Note that only [newAccount]'s *id* is load-bearing — the ticket's subject is a caller
 * with **no session at all**, which is a null `UserRecord` and not an account.
 *
 * ── What is pinned ─────────────────────────────────────────────────────────
 *
 * Both halves of the fix, and the thing it must not break:
 *
 *  1. **The write is refused.** An owner cannot set the guest row above Viewer, and the
 *     refusal carries a reason. Viewer itself is still accepted — publishing a board is
 *     the feature, not the bug.
 *  2. **The read is capped anyway.** A `guest → contributor` (or `→ owner`) row written
 *     *straight into the store*, which is the state a fresh UI can no longer produce and
 *     a hand-edit or an older build can, still leaves a session-less caller at Viewer.
 *     Filing, commenting and being assigned are all refused in both worlds.
 *  3. **Public projects still work.** A guest row at Viewer makes the project readable
 *     with no session, which is what the row is for.
 *
 * A subclass per backend supplies the two stores and a project id their rows can hang
 * off — a real row on SQLite, a synthetic id on Firestore, which validates none.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.AccessControl
import se.soderbjorn.lunicle.Audience
import se.soderbjorn.lunicle.ProjectRole
import se.soderbjorn.lunicle.UserRecord
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey

abstract class GuestAudienceCeilingContract {
    protected abstract val roles: RoleStore
    protected abstract val instanceSettings: InstanceSettingsStore

    /** A fresh project the rows under test hang off, returning its id. */
    protected abstract suspend fun newProject(): Long

    /**
     * A fresh ordinary account — never an instance administrator, which would reach Owner
     * everywhere without a row and so prove nothing about the rung.
     */
    protected abstract suspend fun newAccount(): UserRecord

    private val access: AccessControl by lazy { AccessControl(roles, instanceSettings) }

    /** A board, its owner, on a deployment that allows publishing. */
    private suspend fun publishableProject(): Pair<Long, UserRecord> {
        val project = newProject()
        val owner = newAccount()
        roles.setRole(owner.id, project, ProjectRole.OWNER)
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)
        return project to owner
    }

    // ── The write ────────────────────────────────────────────────────────────

    @Test
    fun `an owner may set the guest row to viewer`() = runBlocking {
        val (project, owner) = publishableProject()
        assertTrue(
            access.canSetAudience(owner, project, Audience.GUEST, ProjectRole.VIEWER),
            "Publishing a board is the feature; the ceiling must not have taken it away.",
        )
    }

    /** The whole defect: an owner one dropdown away from anonymous issue filing. */
    @Test
    fun `an owner may not set the guest row above viewer`() = runBlocking {
        val (project, owner) = publishableProject()
        listOf(
            ProjectRole.CONTRIBUTOR,
            ProjectRole.MAINTAINER,
            ProjectRole.ADMIN,
            ProjectRole.OWNER,
        ).forEach { rung ->
            assertFalse(
                access.canSetAudience(owner, project, Audience.GUEST, rung),
                "An owner handed guests ${rung.key}, which is a write with nobody behind it.",
            )
        }
    }

    /** And the refusal says why, rather than being a bare no. */
    @Test
    fun `the refusal names the reason a guest cannot write`() = runBlocking {
        val reason = Audience.GUEST.refusalFor(ProjectRole.CONTRIBUTOR)
        assertNotNull(reason, "A rung above the ceiling was refused with no reason to show.")
        assertTrue(
            reason.contains("attribute", ignoreCase = true),
            "The reason should say what is actually missing — somebody to attribute the write to.",
        )
        assertEquals(
            null,
            Audience.GUEST.refusalFor(ProjectRole.VIEWER),
            "Viewer is what the row is for; it must not carry a refusal.",
        )
    }

    /** The other two audiences are untouched: they have accounts behind them. */
    @Test
    fun `members and staff may still be handed any rung`() = runBlocking {
        val (project, owner) = publishableProject()
        listOf(Audience.MEMBER, Audience.STAFF).forEach { audience ->
            ProjectRole.entries.forEach { rung ->
                assertTrue(
                    access.canSetAudience(owner, project, audience, rung),
                    "An owner could not hand ${audience.key} ${rung.key}.",
                )
            }
        }
    }

    // ── The read, with the row the UI writes ─────────────────────────────────

    @Test
    fun `a guest row at viewer reads without a session and writes nothing`() = runBlocking {
        val (project, _) = publishableProject()
        roles.setAudienceRole(project, Audience.GUEST, ProjectRole.VIEWER)

        assertEquals(
            ProjectRole.VIEWER,
            access.effectiveRole(null, project),
            "A signed-out visitor lost the read a published board is meant to give them.",
        )
        assertGuestCannotWrite(project, "with the guest row at viewer")
    }

    // ── The read, with a row a fresh UI can no longer produce ────────────────

    /**
     * The defence-in-depth case, and the one that matters most.
     *
     * Written with [RoleStore.setAudienceRole] directly rather than through the gate,
     * because the gate now refuses it — which is exactly the point. This is the row a
     * hand-edit leaves, or a build older than LNL-202 wrote, and a capped write beside an
     * uncapped read would be that one row away from the bug all over again.
     */
    @Test
    fun `a guest row forced above viewer in the store still reads as viewer`() = runBlocking {
        listOf(ProjectRole.CONTRIBUTOR, ProjectRole.MAINTAINER, ProjectRole.ADMIN, ProjectRole.OWNER)
            .forEach { stored ->
                val (project, _) = publishableProject()
                roles.setAudienceRole(project, Audience.GUEST, stored)
                assertEquals(
                    stored,
                    roles.audienceRoles(project)[Audience.GUEST],
                    "Precondition: the store was meant to hold the invalid row verbatim.",
                )
                assertEquals(
                    ProjectRole.VIEWER,
                    access.effectiveRole(null, project),
                    "A stored `guest -> ${stored.key}` row handed a session-less caller more " +
                        "than reading.",
                )
                assertGuestCannotWrite(project, "with a stored guest row at ${stored.key}")
            }
    }

    /**
     * The cap is on the **row**, not on the caller.
     *
     * A signed-in member matching only a `guest → contributor` row is capped too, and that
     * is deliberate: such a row is invalid data rather than a stricter arrangement to be
     * honoured for whoever happens to be attributable. "Everybody here may file bugs" is
     * the members row.
     */
    @Test
    fun `a member matching only an over-ceiling guest row is capped too`() = runBlocking {
        val project = newProject()
        roles.setAudienceRole(project, Audience.GUEST, ProjectRole.CONTRIBUTOR)
        val member = newAccount()

        assertEquals(
            ProjectRole.VIEWER,
            access.effectiveRole(member, project),
            "An account was credited with a rung the guest row was never allowed to give.",
        )
        assertFalse(access.canCreateIssue(member, project))
    }

    /** An own row still raises somebody past what the guest row gives — the max rule holds. */
    @Test
    fun `an own row still raises somebody above the capped guest row`() = runBlocking {
        val project = newProject()
        roles.setAudienceRole(project, Audience.GUEST, ProjectRole.CONTRIBUTOR)
        val member = newAccount()
        roles.setRole(member.id, project, ProjectRole.MAINTAINER)

        assertEquals(
            ProjectRole.MAINTAINER,
            access.effectiveRole(member, project),
            "Capping the audience must not cap the person's own row with it.",
        )
    }

    /** All three gates the ticket names, asked of a caller with no session. */
    private suspend fun assertGuestCannotWrite(project: Long, situation: String) {
        assertFalse(access.canCreateIssue(null, project), "A signed-out visitor filed an issue $situation.")
        assertFalse(access.canComment(null, project), "A signed-out visitor commented $situation.")
        assertFalse(access.canBeAssigned(null, project), "A signed-out visitor was assignable $situation.")
    }
}
