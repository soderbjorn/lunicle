/**
 * The rule the whole permission model rests on (LNL-191):
 *
 *     effectiveRole(user, project) = max(the audience rows they match, their own row)
 *
 * Every other test in this suite goes through a route and asserts a status code,
 * which is right — a permission that is not enforced at the route is not enforced.
 * This one goes at [AccessControl] directly, because the claims here are about the
 * *combination* of two tables and there is no single route whose status code says
 * "the audience won" as opposed to "the own row won".
 *
 * The four things pinned, in the order the ticket states them:
 *
 *  1. the effective rung is the max of the two,
 *  2. an own row can raise somebody **above** their audience,
 *  3. an own row can **never** cut somebody below it,
 *  4. and the two ladders meet where they should — an instance administrator owns
 *     every board, a guest audience reaches a caller with no session at all, and a
 *     staff audience does not reach a member.
 *
 * @see AccessControl.effectiveRole
 */
package se.soderbjorn.lunicle

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.VocabularyKind

class AccessControlLadderTest {
    private val file: File = Files.createTempFile("lunicle-ladder", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)
    private val roles = RoleStore(database)
    private val projects = ProjectStore(database)
    private val attachmentStore = AttachmentStore(database)
    private val attachments =
        AttachmentRepository(attachmentStore, File(file.parentFile, "attachments-${file.name}"))
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
    private val instanceSettings = InstanceSettingsStore(database)
    private val access = AccessControl(roles, instanceSettings)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── The rule ─────────────────────────────────────────────────────────────

    @Test
    fun `with neither an audience nor a row, a project does not exist as far as you are concerned`(): Unit =
        runBlocking {
            val f = seed()
            assertNull(access.effectiveRole(f.member, f.project))
            assertFalse(access.canReadProject(f.member, projects.findById(f.project)!!))
        }

    @Test
    fun `an audience row alone puts somebody on its rung`(): Unit = runBlocking {
        val f = seed()
        roles.setAudienceRole(f.project, Audience.MEMBER, ProjectRole.CONTRIBUTOR)
        assertEquals(ProjectRole.CONTRIBUTOR, access.effectiveRole(f.member, f.project))
    }

    @Test
    fun `an own row alone puts somebody on its rung`(): Unit = runBlocking {
        val f = seed()
        roles.setRole(f.member.id, f.project, ProjectRole.MAINTAINER)
        assertEquals(ProjectRole.MAINTAINER, access.effectiveRole(f.member, f.project))
    }

    /** (1) and (2): the max, and an own row raising somebody above their audience. */
    @Test
    fun `an own row raises somebody above their audience`(): Unit = runBlocking {
        val f = seed()
        roles.setAudienceRole(f.project, Audience.MEMBER, ProjectRole.VIEWER)
        roles.setRole(f.member.id, f.project, ProjectRole.ADMIN)
        assertEquals(ProjectRole.ADMIN, access.effectiveRole(f.member, f.project))
        assertTrue(access.canAdministerProject(f.member, f.project))
        // …and the audience is untouched for everybody else.
        assertEquals(ProjectRole.VIEWER, access.effectiveRole(f.other, f.project))
    }

    /**
     * (3) The half that is a *max* rather than an override, and the one worth
     * failing loudly.
     *
     * A smaller own row is not a demotion — there is nothing to write that
     * subtracts. Lowering somebody is done by lowering the audience, which is a
     * statement about everybody and is visible as one. An implementation that let
     * the own row win would pass every other test in this file.
     */
    @Test
    fun `an own row never cuts somebody below their audience`(): Unit = runBlocking {
        val f = seed()
        roles.setAudienceRole(f.project, Audience.MEMBER, ProjectRole.MAINTAINER)
        roles.setRole(f.member.id, f.project, ProjectRole.VIEWER)
        assertEquals(
            ProjectRole.MAINTAINER,
            access.effectiveRole(f.member, f.project),
            "a smaller own row demoted somebody their audience already admits higher",
        )
        assertTrue(access.canEditVocabulary(f.member, f.project, VocabularyKind.SPRINT))
    }

    /** The widest matching audience wins, not the first or the last written. */
    @Test
    fun `the highest matching audience wins`(): Unit = runBlocking {
        val f = seed()
        roles.setAudienceRole(f.project, Audience.GUEST, ProjectRole.VIEWER)
        roles.setAudienceRole(f.project, Audience.MEMBER, ProjectRole.CONTRIBUTOR)
        assertEquals(ProjectRole.CONTRIBUTOR, access.effectiveRole(f.member, f.project))
        assertEquals(ProjectRole.VIEWER, access.effectiveRole(null, f.project), "a stranger gets the guest row")
    }

    // ── Where the two ladders meet ───────────────────────────────────────────

    @Test
    fun `a staff audience reaches staff and not a member`(): Unit = runBlocking {
        val f = seed()
        roles.setAudienceRole(f.project, Audience.STAFF, ProjectRole.CONTRIBUTOR)
        assertEquals(ProjectRole.CONTRIBUTOR, access.effectiveRole(f.staff, f.project))
        assertNull(access.effectiveRole(f.member, f.project), "a member matched a staff-only audience")
    }

    @Test
    fun `a guest audience reaches a caller with no session at all`(): Unit = runBlocking {
        val f = seed()
        roles.setAudienceRole(f.project, Audience.GUEST, ProjectRole.VIEWER)
        assertEquals(ProjectRole.VIEWER, access.effectiveRole(null, f.project))
        assertTrue(access.canReadProject(null, projects.findById(f.project)!!))
    }

    @Test
    fun `an instance administrator owns every board without holding a row`(): Unit = runBlocking {
        val f = seed()
        assertEquals(ProjectRole.OWNER, access.effectiveRole(f.admin, f.project))
        assertNull(roles.roleFor(f.admin.id, f.project), "the administrator was seated a row rather than short-circuited")
    }

    /**
     * The instance owner is senior to an administrator, and is **not** on the user
     * row — so a build that read only [UserRecord.isInstanceAdmin] would treat the
     * person who owns the deployment as an ordinary member of it.
     */
    @Test
    fun `the instance owner owns every board, and is not an administrator on their row`(): Unit = runBlocking {
        val f = seed()
        instanceSettings.setOwnerUserId(f.member.id)
        assertFalse(f.member.isInstanceAdmin, "the fixture's owner is meant not to carry the admin flag")
        assertEquals(InstanceRole.OWNER, access.instanceRole(f.member))
        assertEquals(ProjectRole.OWNER, access.effectiveRole(f.member, f.project))
    }

    /**
     * The four instance-scoped powers narrowed to the owner (LNL-191) — an
     * administrator is refused all four.
     */
    @Test
    fun `the four instance powers are the owner's and not an administrator's`(): Unit = runBlocking {
        val f = seed()
        instanceSettings.setOwnerUserId(f.member.id)
        listOf<Pair<String, suspend (UserRecord?) -> Boolean>>(
            "canMutateProjects" to access::canMutateProjects,
            "canAttributeWrites" to access::canAttributeWrites,
            "canSendAgentMail" to access::canSendAgentMail,
            "canDeleteAttachment" to access::canDeleteAttachment,
        ).forEach { (name, rule) ->
            assertTrue(rule(f.member), "$name refused the instance owner.")
            assertFalse(rule(f.admin), "$name let an instance administrator through; it is the owner's.")
            assertFalse(rule(null), "$name let a caller with no session through.")
        }
    }

    // ── The rungs themselves ─────────────────────────────────────────────────

    /**
     * The ladder is cumulative, walked once here so a widening is a visible diff.
     *
     * Each row states the *lowest* rung that answers yes, and each rule is asked at
     * every rung — which is what catches a rule written with `==` instead of
     * `atLeast`, the one mistake this shape is otherwise immune to.
     */
    @Test
    fun `each rule answers yes from its rung upwards and no below it`(): Unit = runBlocking {
        val f = seed()
        val rules: List<Triple<String, ProjectRole, suspend (UserRecord) -> Boolean>> = listOf(
            Triple("read", ProjectRole.VIEWER) { u -> access.canReadProject(u, projects.findById(f.project)!!) },
            Triple("create an issue", ProjectRole.CONTRIBUTOR) { u -> access.canCreateIssue(u, f.project) },
            Triple("comment", ProjectRole.CONTRIBUTOR) { u -> access.canComment(u, f.project) },
            Triple("be assigned", ProjectRole.CONTRIBUTOR) { u -> access.canBeAssigned(u, f.project) },
            Triple("edit sprints", ProjectRole.MAINTAINER) { u ->
                access.canEditVocabulary(u, f.project, VocabularyKind.SPRINT)
            },
            Triple("edit the vocabulary", ProjectRole.ADMIN) { u ->
                access.canEditVocabulary(u, f.project, VocabularyKind.STATUS)
            },
            Triple("administer", ProjectRole.ADMIN) { u -> access.canAdministerProject(u, f.project) },
            Triple("own", ProjectRole.OWNER) { u -> access.canOwnProject(u, f.project) },
            Triple("set the audience", ProjectRole.OWNER) { u -> access.canSetAudience(u, f.project) },
        )

        ProjectRole.entries.forEach { rung ->
            roles.setRole(f.member.id, f.project, rung)
            rules.forEach { (name, minimum, rule) ->
                assertEquals(
                    rung.atLeast(minimum),
                    rule(f.member),
                    "\"$name\" answered wrongly for a ${rung.key}; it is ${minimum.key} and above.",
                )
            }
        }
    }

    /** Granting is two tiers: up to maintainer is an administrator's, the two senior rungs an owner's. */
    @Test
    fun `an administrator grants up to maintainer and no further`(): Unit = runBlocking {
        val f = seed()
        roles.setRole(f.member.id, f.project, ProjectRole.ADMIN)
        listOf(ProjectRole.VIEWER, ProjectRole.CONTRIBUTOR, ProjectRole.MAINTAINER).forEach {
            assertTrue(access.canGrant(f.member, f.project, it), "an administrator could not grant ${it.key}")
        }
        listOf(ProjectRole.ADMIN, ProjectRole.OWNER).forEach {
            assertFalse(access.canGrant(f.member, f.project, it), "an administrator promoted ${it.key}")
        }

        roles.setRole(f.member.id, f.project, ProjectRole.OWNER)
        ProjectRole.entries.forEach {
            assertTrue(access.canGrant(f.member, f.project, it), "an owner could not grant ${it.key}")
        }
    }

    /**
     * Deleting an issue is an administrator's, editing one a maintainer's — and an
     * author still discards their own draft, which is what keeps the narrowing from
     * stranding a contributor with a half-written issue they cannot get rid of. See
     * AccessControl.canDeleteIssue.
     */
    @Test
    fun `a maintainer edits anyone's issue, an administrator deletes one, an author discards their own`(): Unit =
        runBlocking {
            val f = seed()
            val issues = IssueStore(database)
            val statuses = StatusStore(database)
            val priorities = PriorityStore(database)
            val comments = CommentStore(database)
            val repository = IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)
            val theirs = issues.findById(repository.createDraft(f.project, Author.Account(f.other.id)).first)!!
            val mine = issues.findById(repository.createDraft(f.project, Author.Account(f.member.id)).first)!!

            roles.setRole(f.member.id, f.project, ProjectRole.CONTRIBUTOR)
            assertFalse(access.canEditIssue(f.member, theirs), "a contributor edited somebody else's issue")
            assertTrue(access.canEditIssue(f.member, mine))
            assertTrue(access.canDeleteIssue(f.member, mine), "a contributor could not discard their own draft")
            assertFalse(access.canDeleteIssue(f.member, theirs))

            roles.setRole(f.member.id, f.project, ProjectRole.MAINTAINER)
            assertTrue(access.canEditIssue(f.member, theirs), "a maintainer could not edit somebody else's issue")
            assertFalse(access.canDeleteIssue(f.member, theirs), "a maintainer deleted somebody else's issue")

            roles.setRole(f.member.id, f.project, ProjectRole.ADMIN)
            assertTrue(access.canDeleteIssue(f.member, theirs), "an administrator could not delete an issue")
        }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(
        val admin: UserRecord,
        val member: UserRecord,
        val other: UserRecord,
        val staff: UserRecord,
        val project: Long,
    )

    /**
     * One instance administrator (the first account, by the upsert's own rule), two
     * ordinary members, one staff account, and a project that admits nobody.
     *
     * Ownership is deliberately **not** seated here: the tests that care about it say
     * so, and the ones that do not are testing an instance where the administrator is
     * the only authority — which is a state a fresh deployment is genuinely in for
     * the moment between its first sign-in and its first boot afterwards.
     */
    private suspend fun seed(): Fixture {
        val admin = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-admin", "Admin", "admin@example.com"))
        val member = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-mem", "Mem", "mem@example.com"))
        val other = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-oth", "Oth", "oth@example.com"))
        val staff = users.upsert(
            ProviderIdentity(AuthProvider.GOOGLE, "g-staff", "Staff", "staff@inside.example"),
            kind = UserKind.STAFF,
        )
        assertTrue(admin.isInstanceAdmin, "the first account is meant to be the instance administrator")
        assertFalse(member.isInstanceAdmin)
        val project = projectRepository.create("Lunamux", "LMX")
        return Fixture(admin, member, other, staff, project.id)
    }
}
