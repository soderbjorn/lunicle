/**
 * The project administrator: what the new role reaches, and where it stops.
 *
 * LNL-37 split one instance-wide flag into two levels, and almost every
 * interesting claim here is about the SECOND word in "project administrator".
 * The role is easy to get right in the direction that grants — a build where it
 * does nothing fails loudly the first time somebody tries to run a sprint. The
 * failures worth testing are the quiet ones, where it grants too much:
 *
 *  - **It is per-project.** A project administrator of one board must be an
 *    ordinary user on every other. The obvious wrong implementation asks
 *    `hasRole` without a project id, or reuses a check that ignores it, and it
 *    looks perfect on a one-project instance — which is exactly what a dev
 *    machine is.
 *  - **It is not the system administrator.** Creating and deleting projects,
 *    impersonation and MCP backfill authorship stay instance-wide. `isSysAdmin`
 *    short-circuits everything; the new role must not acquire the same habit.
 *  - **It cannot promote a peer.** A role that can grant itself escalates: the
 *    first project administrator makes a second, who makes a third, and the
 *    system administrator who granted the first has no say in it. This is the
 *    one rule with no backstop elsewhere, so it is asserted from the route.
 *  - **The bundle stops at authorship.** The role implies the four issue-scoped
 *    roles, deliberately. It does not imply owning other people's words —
 *    `canEditComment` is authorship, not a grant, and running a board is not a
 *    licence to rewrite what somebody said on it.
 *
 * Through the real routes with real session cookies, for VocabularyTest's
 * reason: a test against [AccessControl] alone would pass on a route that never
 * called it, and the gates that moved here are gates in routes.
 *
 * @see ProjectRole.ADMIN
 * @see AccessControl.canAdministerProject
 * @see AccessControl.canGrant
 */
package se.soderbjorn.lunicle

import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.BoardState
import se.soderbjorn.lunicle.clientserver.ProjectFeatures
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.ProjectUpdate
import se.soderbjorn.lunicle.clientserver.RoleGrant
import se.soderbjorn.lunicle.clientserver.SprintActivation
import se.soderbjorn.lunicle.clientserver.VocabularyAdd
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class ProjectAdminTest {
    private val file: File = Files.createTempFile("lunicle-project-admin", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)
    private val sessions = SessionStore(database)
    private val roles = RoleStore(database)
    private val projects = ProjectStore(database)
    private val labels = LabelStore(database)
    private val components = ComponentStore(database)
    private val statuses = StatusStore(database)
    private val priorities = PriorityStore(database)
    private val resolutions = ResolutionStore(database)
    private val sprints = SprintStore(database)
    private val versions = VersionStore(database)
    private val issues = IssueStore(database)
    private val comments = CommentStore(database)
    private val attachmentStore = AttachmentStore(database)
    private val attachments =
        AttachmentRepository(attachmentStore, File(file.parentFile, "attachments-${file.name}"))
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
    private val issueRepository =
        IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)
    private val sprintRepository = SprintRepository(database, sprints, projects, issues, statuses)
    private val vocabularies =
        VocabularyRepository(database, labels, components, statuses, priorities, resolutions, sprints, versions, issues)
    private val instanceSettings = InMemoryInstanceSettingsStore()
    private val access = AccessControl(roles, instanceSettings)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── What it reaches ──────────────────────────────────────────────────────

    /** The vocabulary, which the "wholesale" answer put in this role's hands. */
    @Test
    fun `a project administrator edits their project's vocabulary`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val response = client.post(vocabularyPath(f.projectId, VocabularyKind.LABEL)) {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
                contentType(ContentType.Application.Json)
                setBody(VocabularyAdd("Regression"))
            }
            assertEquals(HttpStatusCode.OK, response.status, "A project administrator could not add a label.")
        }
        assertTrue(labels.forProject(f.projectId).any { it.name == "Regression" })
    }

    /** The sprint lifecycle — the half the issue names first. */
    @Test
    fun `a project administrator activates a sprint`(): Unit = runBlocking {
        val f = seed()
        val sprint = vocabularies.add(f.projectId, VocabularyKind.SPRINT, "Sprint 1")

        withRoutes { client ->
            val response = client.post("/api/projects/${f.projectId}/sprints/active") {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
                contentType(ContentType.Application.Json)
                setBody(SprintActivation(sprint.id))
            }
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "A project administrator could not activate a sprint — the gate this role exists for.",
            )
        }
        assertEquals(sprint.id, projects.activeSprintId(f.projectId))
    }

    /** And the ordinary issue work, which the role bundles rather than requiring four more boxes. */
    @Test
    fun `a project administrator may file issues without holding create_issue`(): Unit = runBlocking {
        val f = seed()
        val admin = users.findById(f.projectAdminId)!!
        assertFalse(
            setOfNotNull(roles.roleFor(f.projectAdminId, f.projectId)).contains(ProjectRole.CONTRIBUTOR),
            "The fixture granted create_issue outright, so this proves nothing.",
        )
        assertTrue(access.canCreateIssue(admin, f.projectId), "The bundle does not reach create_issue.")
        assertTrue(access.canComment(admin, f.projectId), "The bundle does not reach comment_on_issue.")
        assertTrue(access.canBeAssigned(admin, f.projectId), "The bundle does not reach be_assigned_issue.")

        // change_unowned_issues, asked the way the routes ask it: about an issue.
        val (issueId, _) = issueRepository.createDraft(f.projectId, Author.Account(f.sysAdminId))
        assertTrue(
            access.canEditIssue(admin, issues.findById(issueId)!!),
            "The bundle does not reach change_unowned_issues.",
        )
    }

    /** They may hand out the ordinary roles here — most of what running a board is. */
    @Test
    fun `a project administrator grants an issue-scoped role in their project`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val response = client.post("/api/projects/${f.projectId}/roles") {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
                contentType(ContentType.Application.Json)
                setBody(RoleGrant(userId = f.outsiderId, roleKey = ProjectRole.CONTRIBUTOR.key, isGranted = true))
            }
            assertEquals(HttpStatusCode.OK, response.status, "A project administrator could not grant a role.")
        }
        assertTrue((roles.roleFor(f.outsiderId, f.projectId) == ProjectRole.CONTRIBUTOR))
    }

    // ── Where it stops ───────────────────────────────────────────────────────

    /**
     * THE test of this file: the role cannot grant itself.
     *
     * Without this, one project administrator promotes a second, who promotes a
     * third, and the system administrator who granted the first is no longer the
     * only route in. There is no backstop anywhere else — the grant route is the
     * only door.
     */
    @Test
    fun `a project administrator cannot make somebody else a project administrator`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val response = client.post("/api/projects/${f.projectId}/roles") {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
                contentType(ContentType.Application.Json)
                setBody(RoleGrant(userId = f.outsiderId, roleKey = ProjectRole.ADMIN.key, isGranted = true))
            }
            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "A project administrator promoted a peer. The role can now escalate without a system " +
                    "administrator ever being asked.",
            )
        }
        assertFalse(
            (roles.roleFor(f.outsiderId, f.projectId) == ProjectRole.ADMIN),
            "The grant landed despite the refusal.",
        )
    }

    /** A system administrator is the one who can. The mirror of the test above. */
    @Test
    fun `a system administrator can make somebody a project administrator`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val response = client.post("/api/projects/${f.projectId}/roles") {
                cookie(SESSION_COOKIE, f.sysAdminCookie)
                contentType(ContentType.Application.Json)
                setBody(RoleGrant(userId = f.outsiderId, roleKey = ProjectRole.ADMIN.key, isGranted = true))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        assertTrue((roles.roleFor(f.outsiderId, f.projectId) == ProjectRole.ADMIN))
    }

    /**
     * It is scoped to ONE project, and a one-project instance cannot show that.
     *
     * The implementation that ignores the project id passes every other test in
     * this file. The fixture therefore always builds a second project, and this
     * is what it is for.
     */
    @Test
    fun `a project administrator has no privileges in another project`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val vocabulary = client.post(vocabularyPath(f.otherProjectId, VocabularyKind.LABEL)) {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
                contentType(ContentType.Application.Json)
                setBody(VocabularyAdd("Sneaked in"))
            }
            assertEquals(
                HttpStatusCode.Forbidden,
                vocabulary.status,
                "Administering one project reached another one's vocabulary.",
            )

            val sprintScope = client.post("/api/projects/${f.otherProjectId}/sprints/active") {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
                contentType(ContentType.Application.Json)
                setBody(SprintActivation(null))
            }
            assertEquals(
                HttpStatusCode.Forbidden,
                sprintScope.status,
                "Administering one project reached another one's sprints.",
            )
        }
        assertTrue(labels.forProject(f.otherProjectId).none { it.name == "Sneaked in" })
    }

    /** Projects themselves stay the instance's business. */
    @Test
    fun `a project administrator cannot create or delete projects`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val created = client.post("/api/projects") {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
                contentType(ContentType.Application.Json)
                setBody(ProjectUpdate("Theirs", "THR", isPublic = false))
            }
            assertEquals(HttpStatusCode.Forbidden, created.status, "A project administrator created a project.")
        }
        assertTrue(projects.selectAll().none { it.namePrefix == "THR" })
    }

    /**
     * The bundle reaches roles, not authorship.
     *
     * `canEditComment` is about who wrote it, and the four roles this role
     * implies are all grants. Running a board does not make somebody else's words
     * yours to rewrite — the same line McpDeleteTest draws for
     * `change_unowned_issues`, and for the same reason.
     */
    @Test
    fun `a project administrator cannot edit someone elses comment`(): Unit = runBlocking {
        val f = seed()
        val (issueId, _) = issueRepository.createDraft(f.projectId, Author.Account(f.sysAdminId))
        val commentId = issueRepository.createCommentDraft(issueId, Author.Account(f.sysAdminId))
        issueRepository.saveComment(commentId, "not yours")

        val admin = users.findById(f.projectAdminId)!!
        assertFalse(
            access.canEditComment(admin, comments.findById(commentId)!!),
            "Administering a project reached another person's words.",
        )
    }

    // ── The affordance ───────────────────────────────────────────────────────

    /**
     * The settings dialog opens for a project administrator, with the admin half
     * — and with the one box they may not tick flagged.
     *
     * `canMutateProject` and `canGrantSeniorRoles` differ for exactly this
     * caller and nobody else, which is why they are two fields rather than one.
     */
    @Test
    fun `the settings state tells a project administrator what they may not grant`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val theirs: ProjectSettingsState = client.get("/api/projects/${f.projectId}/settings") {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
            }.body()
            assertTrue(theirs.canMutateProject, "A project administrator got the read-only dialog.")
            assertFalse(
                theirs.canGrantSeniorRoles,
                "The dialog would offer a project administrator the promote box, which the route refuses.",
            )
            assertTrue(theirs.members.isNotEmpty(), "The admin half was omitted.")

            val sysAdmin: ProjectSettingsState = client.get("/api/projects/${f.projectId}/settings") {
                cookie(SESSION_COOKIE, f.sysAdminCookie)
            }.body()
            assertTrue(sysAdmin.canGrantSeniorRoles, "A system administrator lost the promote box.")

            val outsider: ProjectSettingsState = client.get("/api/projects/${f.projectId}/settings") {
                cookie(SESSION_COOKIE, f.outsiderCookie)
            }.body()
            assertFalse(outsider.canMutateProject)
            assertTrue(outsider.members.isEmpty(), "The members directory leaked to a non-administrator.")
        }
    }

    /**
     * The board's permissions tell the dialog which half a project administrator
     * gets — and the two flags differ for exactly this caller.
     *
     * Found in a browser rather than deduced: the settings dialog was offering a
     * project administrator a name field, a public toggle and a Delete button,
     * all of which 403 on save. `canMutateProject` alone could not express it,
     * because "administers the board" and "owns the project's identity in the
     * instance" had become two different answers.
     */
    @Test
    fun `the board tells a project administrator they may not rename or delete the project`(): Unit =
        runBlocking {
            val f = seed()
            val admin = users.findById(f.projectAdminId)!!
            val theirs = access.permissionsFor(admin, f.projectId)
            assertTrue(theirs.canMutateProject, "A project administrator lost the settings sections.")
            assertFalse(
                theirs.canMutateProjectIdentity,
                "The dialog would offer a project administrator a name field and a Delete button " +
                    "that the project routes refuse.",
            )

            val sys = access.permissionsFor(users.findById(f.sysAdminId)!!, f.projectId)
            assertTrue(sys.canMutateProjectIdentity, "A system administrator lost the rename/delete half.")

            val outsider = access.permissionsFor(users.findById(f.outsiderId)!!, f.projectId)
            assertFalse(outsider.canMutateProject)
            assertFalse(outsider.canMutateProjectIdentity)
        }

    // ── Features: disable discussions and messages (LNL-96) ──────────────────

    /**
     * A project administrator switches this project's discussions and messages
     * off, and the change reaches all three places it must: the settings state the
     * dialog re-renders from, the store, and the board's project summary that the
     * tab shell reads to hide the tabs.
     *
     * The board check is the load-bearing one: hiding a tab is a client decision,
     * but it can only be made from a flag the board actually carries. A route that
     * wrote the column but did not thread it onto ProjectSummary would pass the
     * first two assertions and leave the tabs showing.
     */
    @Test
    fun `a project administrator disables discussions and messages`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client ->
            val response = client.post(ApiRoutes.projectFeatures(f.projectId)) {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
                contentType(ContentType.Application.Json)
                setBody(ProjectFeatures(discussionsEnabled = false, messagesEnabled = false))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val settings: ProjectSettingsState = response.body()
            assertFalse(settings.discussionsEnabled, "The settings state did not report discussions off.")
            assertFalse(settings.messagesEnabled, "The settings state did not report messages off.")

            val board: BoardState = client.get("/api/projects/${f.projectId}/board") {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
            }.body()
            assertFalse(board.project.discussionsEnabled, "The board's project still says discussions are on.")
            assertFalse(board.project.messagesEnabled, "The board's project still says messages are on.")
        }

        val stored = projects.findById(f.projectId)
        assertNotNull(stored)
        assertFalse(stored.discussionsEnabled, "The column was not written.")
        assertFalse(stored.messagesEnabled, "The column was not written.")
    }

    /**
     * A project administrator asking for discussions back does not get them.
     *
     * This test read "the two flags move independently" until LNL-190 retired both
     * features: the route still takes the pair and still writes the columns, but
     * every read fills them from `PROJECT_FORUM_FEATURES_ENABLED`, so the answer to
     * "switch discussions on" is now no. The route is unreachable from the web app —
     * the Features section is gone — so this is about the one caller left, somebody
     * posting the old body by hand.
     */
    @Test
    fun `switching a feature back on does not switch it back on`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client ->
            val settings: ProjectSettingsState = client.post(ApiRoutes.projectFeatures(f.projectId)) {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
                contentType(ContentType.Application.Json)
                setBody(ProjectFeatures(discussionsEnabled = true, messagesEnabled = true))
            }.body()
            assertFalse(settings.discussionsEnabled, "Discussions came back on for the asking.")
            assertFalse(settings.messagesEnabled, "Messages came back on for the asking.")

            val board: BoardState = client.get("/api/projects/${f.projectId}/board") {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
            }.body()
            assertFalse(board.project.discussionsEnabled, "The board's project says discussions are on.")
            assertFalse(board.project.messagesEnabled, "The board's project says messages are on.")
        }

        val stored = projects.findById(f.projectId)!!
        assertFalse(stored.discussionsEnabled)
        assertFalse(stored.messagesEnabled)
    }

    /**
     * Someone who does not administer the project cannot change its features.
     *
     * The status is the whole of it now. This used to re-read the store afterwards —
     * a 403 that had already written would still read as a 403 — but since LNL-190
     * every read of the two flags answers false whatever is in the column, so a
     * write is no longer observable from here. The retired feature is pinned by the
     * test above; this one is about the gate.
     */
    @Test
    fun `an outsider cannot change a project's features`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post(ApiRoutes.projectFeatures(f.projectId)) {
                    cookie(SESSION_COOKIE, f.outsiderCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ProjectFeatures(discussionsEnabled = false, messagesEnabled = false))
                }.status,
            )
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(
        val sysAdminId: Long,
        val sysAdminCookie: String,
        val projectAdminId: Long,
        val projectAdminCookie: String,
        val outsiderId: Long,
        val outsiderCookie: String,
        val projectId: Long,
        /** A second project, so "per-project" is testable at all. */
        val otherProjectId: Long,
    )

    private suspend fun seed(): Fixture {
        val sysAdmin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-sys", "Sys", "sys@example.com"))
        val projectAdmin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-pa", "Pat", "pat@example.com"))
        val outsider = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-out", "Out", "out@example.com"))
        assertTrue(sysAdmin.isInstanceAdmin, "The first account is meant to be the system administrator.")
        assertFalse(projectAdmin.isInstanceAdmin, "The fixture's project administrator is a system one.")

        val project = projectRepository.create("Lunamux", "LMX")
        val other = projectRepository.create("Elsewhere", "ELS")
        // The ONLY grant of substance. No create_issue, no comment_on_issue —
        // the bundle has to supply those, or the tests above prove nothing.
        roles.setRole(projectAdmin.id, project.id, ProjectRole.ADMIN)
        // Bare visibility, and nothing else, for the two callers whose refusals
        // this file is about. Since LNL-57 a private project is invisible to
        // somebody holding nothing in it, and an invisible project answers 404
        // to everything — which would satisfy every "…is Forbidden" assertion
        // below without the admin gate existing at all. `view_project` grants
        // no ability whatsoever (see ProjectRole.VIEWER), so these two lines
        // move the refusals back to being about administering rather than about
        // seeing, which is what the tests claim to check.
        roles.setRole(outsider.id, project.id, ProjectRole.VIEWER)
        roles.setRole(projectAdmin.id, other.id, ProjectRole.VIEWER)

        // Production seats the instance owner at boot (see InstanceLadder.kt), and
        // four rules — creating and managing projects, backfilling authorship, agent
        // mail, out-of-band attachment deletes — are the owner's alone rather than an
        // administrator's. A fixture that skipped this would be testing an instance
        // nobody runs: one with an administrator and no owner.
        seatInstanceOwner(users, instanceSettings)
        return Fixture(
            sysAdminId = sysAdmin.id,
            sysAdminCookie = sessions.create(sysAdmin.id),
            projectAdminId = projectAdmin.id,
            projectAdminCookie = sessions.create(projectAdmin.id),
            outsiderId = outsider.id,
            outsiderCookie = sessions.create(outsider.id),
            projectId = project.id,
            otherProjectId = other.id,
        )
    }

    /** The real route helper, not a hand-built string — a typo here is a 404 that reads as a refusal. */
    private fun vocabularyPath(projectId: Long, kind: VocabularyKind) =
        se.soderbjorn.lunicle.clientserver.ApiRoutes.vocabulary(projectId, kind)

    private fun withRoutes(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing {
                boardRoutes(dependencies())
                projectSettingsRoutes(dependencies())
                sprintRoutes(dependencies())
            }
        }
        val client = createClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
        }
        block(client)
    }

    private fun dependencies() = BoardDependencies(
        access = access,
        projects = projects,
        projectRepository = projectRepository,
        roles = roles,
        vocabularies = vocabularies,
        forums = ForumRepository(ForumStore(database), attachments, attachmentStore),
        forumPosts = ForumPostRepository(
            ForumPostStore(database), ForumCommentStore(database), attachments, attachmentStore,
        ),
        audience = ProjectAudience(users, roles, instanceSettings),
        // Not exercised by this file; here because a route bundle is one object
        // and there is no half of it. See MessageTest for the tests that do.
        conversations = ConversationRepository(
            ConversationStore(database), MessageStore(database), attachments, attachmentStore,
        ),
        labels = labels,
        components = components,
        statuses = statuses,
        priorities = priorities,
        resolutions = resolutions,
        versions = versions,
        sprints = sprintRepository,
        sprintRepository = sprintRepository,
        issues = issues,
        issueRepository = issueRepository,
        comments = comments,
        attachments = attachmentStore,
        attachmentRepository = attachments,
        attachmentTickets = AttachmentTicketStore(),
        sessions = sessions,
        users = users,
        impersonations = Impersonations(),
        subscriptions = SubscriptionStore(database),
        reads = ReadStore(database),
    )
}
