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
 * A fifth since LNL-203: the **instance's publish veto is a term in that max**, not a
 * guard on the editor. While "allow projects to be public" is off a guest row grants
 * nothing, so the rule above is really `max(the rows they match *that are in effect*, their
 * own row)` — and turning the switch back on restores the answer exactly, because nothing
 * rewrites a row in either direction.
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
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
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
        // Publishing takes two facts since LNL-203: the row, and a deployment that allows
        // it. The guest half of this assertion is about the row, so the switch is turned on
        // rather than left at its closed default.
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)
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
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)
        roles.setAudienceRole(f.project, Audience.GUEST, ProjectRole.VIEWER)
        assertEquals(ProjectRole.VIEWER, access.effectiveRole(null, f.project))
        assertTrue(access.canReadProject(null, projects.findById(f.project)!!))
    }

    /**
     * And it stops reaching them the moment the deployment says no (LNL-203).
     *
     * The compound failure the ticket describes, at the level of the rule: the row is
     * untouched and the answer changes, both ways, because the veto is a *term* in the
     * computation and not a guard on the picker. Asserted here as well as through the routes
     * (see [ProjectVisibilityTest]) because this is the sentence every gate is built on —
     * a route test would pass on a rule that read the switch in one path and forgot it in
     * another.
     */
    @Test
    fun `the publish veto silences a guest row and restores it, without rewriting it`(): Unit = runBlocking {
        val f = seed()
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)
        roles.setAudienceRole(f.project, Audience.GUEST, ProjectRole.VIEWER)
        val before = roles.audienceRoles(f.project)
        assertEquals(ProjectRole.VIEWER, access.effectiveRole(null, f.project), "precondition: it was public")

        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, false)
        assertNull(
            access.effectiveRole(null, f.project),
            "the switch went off and a stranger still reached the board",
        )
        assertFalse(access.canReadProject(null, projects.findById(f.project)!!))
        assertEquals(before, roles.audienceRoles(f.project), "silencing the row rewrote it")

        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)
        assertEquals(
            ProjectRole.VIEWER,
            access.effectiveRole(null, f.project),
            "turning the switch back on did not restore the access the row still describes",
        )
        assertEquals(before, roles.audienceRoles(f.project), "restoring the access rewrote the row")
    }

    /** The veto is about strangers alone: a member row is unaffected by it. */
    @Test
    fun `the publish veto leaves the member and staff rows alone`(): Unit = runBlocking {
        val f = seed()
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, false)
        roles.setAudienceRole(f.project, Audience.MEMBER, ProjectRole.CONTRIBUTOR)
        roles.setAudienceRole(f.project, Audience.STAFF, ProjectRole.MAINTAINER)
        assertEquals(ProjectRole.CONTRIBUTOR, access.effectiveRole(f.member, f.project))
        assertEquals(ProjectRole.MAINTAINER, access.effectiveRole(f.staff, f.project))
        assertNull(access.effectiveRole(null, f.project), "a stranger got in through a members row")
    }

    /**
     * Withdrawal is allowed while the veto is on, and granting is not (LNL-203).
     *
     * The other half of the practical failure: refusing *any* write to the guest row could
     * not tell granting from revoking, so the control meant to stop public projects took
     * away the only in-app way to close one.
     */
    @Test
    fun `the veto refuses a grant on the guest row and never a withdrawal`(): Unit = runBlocking {
        val f = seed()
        // The board's owner by way of owning the instance, which is the strongest caller
        // there is — so a refusal here is the veto and not a rung.
        instanceSettings.setOwnerUserId(f.member.id)
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, false)
        val owner = f.member

        assertFalse(
            access.canSetAudience(owner, f.project, Audience.GUEST, ProjectRole.VIEWER),
            "a board was published on a deployment that forbids it",
        )
        assertTrue(
            access.canSetAudience(owner, f.project, Audience.GUEST, rung = null),
            "the veto refused a withdrawal, which is the one direction it has no business refusing",
        )
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
     * The instance-scoped powers narrowed to the owner — an administrator is refused
     * every one of them.
     *
     * Four when LNL-191 wrote this and five since LNL-197 added impersonation, which is
     * the sharpest of them: it is the only one that hands the caller another person's
     * rights with their writes attached. Listed here rather than tested apart so
     * widening any of them is one visible diff in one place.
     */
    @Test
    fun `the instance powers are the owner's and not an administrator's`(): Unit = runBlocking {
        val f = seed()
        instanceSettings.setOwnerUserId(f.member.id)
        listOf<Pair<String, suspend (UserRecord?) -> Boolean>>(
            "canMutateProjects" to access::canMutateProjects,
            "canAttributeWrites" to access::canAttributeWrites,
            "canSendAgentMail" to access::canSendAgentMail,
            "canDeleteAttachment" to access::canDeleteAttachment,
            "canImpersonate" to access::canImpersonate,
        ).forEach { (name, rule) ->
            assertTrue(rule(f.member), "$name refused the instance owner.")
            assertFalse(rule(f.admin), "$name let an instance administrator through; it is the owner's.")
            assertFalse(rule(null), "$name let a caller with no session through.")
        }
    }

    /**
     * Administering the instance is an **administrator's**, and is deliberately the one
     * instance-scoped rule that is not the owner's alone (LNL-195).
     *
     * Its own test beside the list above, because the pair is the point and because getting
     * it wrong is invisible from the server: the client offers the three instance tabs to
     * anybody who is an administrator, so a gate one rung too high renders three tabs that
     * are empty apart from a refusal — which is exactly what shipped before this rule
     * existed. Asserted at every rung, so the `==` spelling that would refuse the *owner*
     * fails here too.
     */
    @Test
    fun `administering the instance is an administrator's, from that rung upwards`(): Unit = runBlocking {
        val f = seed()
        instanceSettings.setOwnerUserId(f.member.id)
        assertTrue(access.canAdministerInstance(f.member), "the instance owner was refused.")
        assertTrue(
            access.canAdministerInstance(f.admin),
            "an instance administrator was refused the tabs the client offers precisely to them.",
        )
        assertFalse(access.canAdministerInstance(f.other), "an ordinary account was let through.")
        assertFalse(access.canAdministerInstance(f.staff), "a staff account was let through.")
        assertFalse(access.canAdministerInstance(null), "a caller with no session was let through.")
    }

    /**
     * The comment gate is the ladder too, and was the one place it inverted (LNL-201).
     *
     * `canEditComment` is authorship plus "whoever runs the instance", and it used to ask
     * the account's own row for the second half — which structurally cannot say Owner,
     * because ownership is `instance_settings.owner_user_id` and not a column. So the
     * person who owns the deployment was refused something an ordinary administrator was
     * allowed, at exactly one gate.
     *
     * **The fixture's owner holds `instance_role` NULL**, asserted below, because that is
     * the state the bug needs and the state every volume 33.sqm migrated is in: the
     * migration leaves the column null for everybody *including* the account it seats as
     * owner. An owner who also carried the administrator row would pass this test against
     * the broken code, which is why the assertion above the interesting one is here.
     *
     * All four rungs of the answer, so a fix in either direction is a visible diff: the
     * owner and the administrator through, an ordinary account and no session refused, and
     * the author of the comment through regardless of any of it.
     */
    @Test
    fun `the instance owner edits anybody's comment, on a row that says nothing`(): Unit = runBlocking {
        val f = seed()
        val comments = CommentStore(database)
        val repository = IssueRepository(
            IssueStore(database),
            comments,
            StatusStore(database),
            PriorityStore(database),
            attachments,
            attachmentStore,
        )
        val (issueId, _) = repository.createDraft(f.project, Author.Account(f.other.id))
        val commentId = repository.createCommentDraft(issueId, Author.Account(f.other.id))
        repository.saveComment(commentId, "somebody else's words")
        val theirs = comments.findById(commentId)!!

        instanceSettings.setOwnerUserId(f.member.id)
        assertFalse(
            f.member.isInstanceAdmin,
            "Precondition: the owner's instance_role must be null, which is what a migrated " +
                "volume leaves behind and what this test is about.",
        )
        assertEquals(InstanceRole.OWNER, access.instanceRole(f.member), "the seat did not take")

        assertTrue(
            access.canEditComment(f.member, theirs),
            "The instance owner could not edit somebody else's comment; every other " +
                "instance-scoped gate lets them, and migration work needs this one to.",
        )
        assertTrue(
            access.canEditComment(f.admin, theirs),
            "An instance administrator lost the comment override the owner just gained.",
        )
        assertFalse(access.canEditComment(f.staff, theirs), "An ordinary staff account reached another's words.")
        assertFalse(access.canEditComment(null, theirs), "A caller with no session reached a comment.")
        assertTrue(access.canEditComment(f.other, theirs), "The author lost their own comment.")
    }

    /**
     * The pickers agree with the gate about the owner (LNL-201).
     *
     * `mentionableUsersIn` and `assignableUsers` are the same `max(audience, own row)` rule
     * spelled a second time — over maps, because they answer about every account at once
     * and a store read per row would be an N+1. Both spellings short-circuited on the
     * *stored* row, so on a project admitting nobody the owner fell out of a set an
     * administrator stayed in: an autocomplete that cannot offer the one person who can
     * certainly read the board, and a mailer built from the same function that would then
     * decline to resolve the name it offered.
     *
     * Asserted through the shared function rather than through the routes because the claim
     * is about the *set*, and the two callers differ only in the rung they then filter to.
     */
    @Test
    fun `the mention set holds the owner on a project that admits nobody`(): Unit = runBlocking {
        val f = seed()
        instanceSettings.setOwnerUserId(f.member.id)
        assertNull(roles.roleFor(f.member.id, f.project), "Precondition: the owner holds no row here.")

        val mentionable = mentionableUsersIn(f.project, users, roles, instanceSettings).map { it.id }
        assertTrue(
            f.member.id in mentionable,
            "The instance owner cannot be @mentioned on a board they own by owning the instance.",
        )
        assertTrue(f.admin.id in mentionable, "An instance administrator fell out of the mention set.")
        assertFalse(f.other.id in mentionable, "An account with no route in was offered anyway.")
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
            Triple("set the audience", ProjectRole.OWNER) { u ->
                access.canSetAudience(u, f.project, Audience.MEMBER, ProjectRole.CONTRIBUTOR)
            },
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

    // ── What the veto costs, per read (LNL-203) ──────────────────────────────

    /**
     * A project with no guest row does **not** pay a settings read for the veto.
     *
     * [AccessControl.effectiveRole] is on the hot path for every read of every board, and
     * the veto can only ever change the fate of an `Audience.GUEST` row — which most
     * projects do not have. So the read is skipped where the answer could not change, the
     * same shape ownership already uses, and this counts it rather than trusting the comment
     * that says so. The signed-out caller is the one whose path has nothing else in it: a
     * signed-in caller reads the settings anyway, for ownership.
     */
    @Test
    fun `a project with no guest row costs no settings read`(): Unit = runBlocking {
        val f = seed()
        val counted = CountingInstanceSettings(instanceSettings)
        val countedRoles = CountingRoles(roles)
        val counting = AccessControl(countedRoles, counted)
        roles.setAudienceRole(f.project, Audience.MEMBER, ProjectRole.VIEWER)

        assertNull(counting.effectiveRole(null, f.project), "precondition: a stranger is not admitted here")
        assertEquals(0, counted.reads, "the veto was consulted about a project with no guest row")
        assertEquals(1, countedRoles.audienceReads, "the audience rows were read more than once")
    }

    /** And a project that *does* have one pays exactly one, which is what the veto is worth. */
    @Test
    fun `a guest row costs one settings read and no more`(): Unit = runBlocking {
        val f = seed()
        val counted = CountingInstanceSettings(instanceSettings)
        val counting = AccessControl(roles, counted)
        roles.setAudienceRole(f.project, Audience.GUEST, ProjectRole.VIEWER)

        counting.effectiveRole(null, f.project)
        assertEquals(1, counted.reads, "the veto cost more than the one read it needs")
    }

    /**
     * A settings store that counts [current] and delegates everything else.
     *
     * Only the read is counted: the claim under test is about what one `effectiveRole` costs.
     */
    private class CountingInstanceSettings(
        private val delegate: se.soderbjorn.lunicle.store.InstanceSettingsStore,
    ) : se.soderbjorn.lunicle.store.InstanceSettingsStore by delegate {
        var reads = 0
            private set

        override suspend fun current(): se.soderbjorn.lunicle.store.InstanceSettings {
            reads++
            return delegate.current()
        }
    }

    /** The same, for the one role read `effectiveRole` makes before it decides anything. */
    private class CountingRoles(
        private val delegate: se.soderbjorn.lunicle.store.RoleStore,
    ) : se.soderbjorn.lunicle.store.RoleStore by delegate {
        var audienceReads = 0
            private set

        override suspend fun audienceRoles(projectId: Long): Map<Audience, ProjectRole> {
            audienceReads++
            return delegate.audienceRoles(projectId)
        }
    }
}
