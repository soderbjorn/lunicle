/**
 * The project owner: the tier senior to the administrator, and where it stops.
 *
 * LNL-107 adds a second per-project level above the administrator, so this file is
 * the mirror of [ProjectAdminTest] one rung up. The administrator tests prove a
 * role that must not grant too much; these prove a role that must grant *exactly*
 * the things the administrator could not, and must still be confined to one board:
 *
 *  - **It reaches identity, repository and deletion.** Renaming, re-scoping,
 *    configuring the repository and deleting the project — all refused for an
 *    administrator ([ProjectAdminTest]), all granted here. Through the real routes,
 *    because those are gates in routes and a test against [AccessControl] alone
 *    would pass on a route that never called it.
 *  - **It may promote.** An owner hands out `project_admin` and `project_owner`,
 *    the two the administrator's [AccessControl.canGrant] tier refuses. That the
 *    escalation stops with the owner rather than the administrator is the decision
 *    LNL-107 made, so it is asserted from the grant route.
 *  - **It is per-project.** An owner of one board is an ordinary user on every
 *    other — the same quiet failure [ProjectAdminTest] guards against, one level
 *    up, and just as invisible on a one-project dev machine.
 *  - **The literal token never comes back.** A stored token is write-only on the
 *    wire, and a blank field on save keeps it. Both are asserted, because both are
 *    the difference between "a secret an owner set" and "a secret on every screen".
 *
 * @see Role.PROJECT_OWNER
 * @see AccessControl.canOwnProject
 * @see AccessControl.canGrant
 */
package se.soderbjorn.lunicle

import io.ktor.client.call.body
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.ProjectUpdate
import se.soderbjorn.lunicle.clientserver.RoleGrant
import se.soderbjorn.lunicle.clientserver.TokenModes
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class ProjectOwnerTest {
    private val file: File = Files.createTempFile("lunicle-project-owner", ".db").toFile().also { it.delete() }
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
    private val access = AccessControl(roles)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── What it reaches, and the administrator does not ──────────────────────

    /** Renaming and re-scoping the project — the identity PUT an administrator is refused. */
    @Test
    fun `an owner renames the project`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val response = client.put("/api/projects/${f.projectId}") {
                cookie(SESSION_COOKIE, f.ownerCookie)
                contentType(ContentType.Application.Json)
                setBody(ProjectUpdate(name = "Renamed", namePrefix = "LMX", isPublic = true))
            }
            assertEquals(HttpStatusCode.OK, response.status, "An owner could not rename their project.")
        }
        assertEquals("Renamed", projects.findById(f.projectId)!!.name)
    }

    /** Deletion — the whole point of the role over the administrator, refused for an administrator. */
    @Test
    fun `an owner deletes the project`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val response = client.delete("/api/projects/${f.projectId}") {
                cookie(SESSION_COOKIE, f.ownerCookie)
            }
            assertEquals(HttpStatusCode.NoContent, response.status, "An owner could not delete their project.")
        }
        assertEquals(null, projects.findById(f.projectId), "The project outlived its deletion.")
    }

    /** The administrator is still refused both, so the role above them is what granted the owner these. */
    @Test
    fun `a project administrator cannot rename or delete the project`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.put("/api/projects/${f.projectId}") {
                    cookie(SESSION_COOKIE, f.projectAdminCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ProjectUpdate(name = "Nope", namePrefix = "LMX", isPublic = false))
                }.status,
                "A project administrator renamed the project.",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.delete("/api/projects/${f.projectId}") {
                    cookie(SESSION_COOKIE, f.projectAdminCookie)
                }.status,
                "A project administrator deleted the project.",
            )
        }
        assertNotNull(projects.findById(f.projectId), "A refused request changed the project anyway.")
    }

    /** Promotion: an owner grants both senior roles the administrator's tier refuses. */
    @Test
    fun `an owner promotes an administrator and another owner`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            listOf(Role.PROJECT_ADMIN, Role.PROJECT_OWNER).forEach { role ->
                val response = client.post("/api/projects/${f.projectId}/roles") {
                    cookie(SESSION_COOKIE, f.ownerCookie)
                    contentType(ContentType.Application.Json)
                    setBody(RoleGrant(f.outsiderId, role.key, isGranted = true))
                }
                assertEquals(HttpStatusCode.OK, response.status, "An owner could not grant ${role.key}.")
            }
        }
        assertTrue(roles.hasRole(f.outsiderId, f.projectId, Role.PROJECT_ADMIN))
        assertTrue(roles.hasRole(f.outsiderId, f.projectId, Role.PROJECT_OWNER))
    }

    /** The administrator's tier still refuses the senior roles — the escalation stops at the owner. */
    @Test
    fun `a project administrator cannot promote`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/projects/${f.projectId}/roles") {
                    cookie(SESSION_COOKIE, f.projectAdminCookie)
                    contentType(ContentType.Application.Json)
                    setBody(RoleGrant(f.outsiderId, Role.PROJECT_ADMIN.key, isGranted = true))
                }.status,
            )
        }
        assertFalse(roles.hasRole(f.outsiderId, f.projectId, Role.PROJECT_ADMIN))
    }

    // ── Per-project ──────────────────────────────────────────────────────────

    /** An owner of one board is an ordinary user on another — the same PUT is refused there. */
    @Test
    fun `ownership is confined to one project`(): Unit = runBlocking {
        val f = seed()
        assertFalse(
            access.canOwnProject(users.findById(f.ownerId)!!, f.otherProjectId),
            "Owning one project reached another.",
        )
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.delete("/api/projects/${f.otherProjectId}") {
                    cookie(SESSION_COOKIE, f.ownerCookie)
                }.status,
                "An owner deleted a project they do not own.",
            )
        }
    }

    // ── The affordance ───────────────────────────────────────────────────────

    /** The board's permissions hand the owner the identity half the administrator is denied. */
    @Test
    fun `the board tells an owner they may rename and delete`(): Unit = runBlocking {
        val f = seed()
        val owner = access.permissionsFor(users.findById(f.ownerId)!!, f.projectId)
        assertTrue(owner.canMutateProject, "An owner lost the settings sections.")
        assertTrue(owner.canMutateProjectIdentity, "An owner lost the rename/delete half.")
        assertTrue(owner.canGrantSeniorRoles, "An owner lost the promote boxes.")

        // The same owner, elsewhere, is nobody in particular.
        val elsewhere = access.permissionsFor(users.findById(f.ownerId)!!, f.otherProjectId)
        assertFalse(elsewhere.canMutateProjectIdentity, "Ownership of one board leaked to another.")
    }

    /** The settings dialog gives an owner the repository fields; the administrator got none. */
    @Test
    fun `an owner is sent the repository configuration`(): Unit = runBlocking {
        val f = seed()
        projects.setRepositoryConfig(
            f.projectId,
            RepositoryConfig(RepositoryRef("soderbjorn", "lunicle"), TokenSource.Env("LUNICLE_GITHUB_TOKEN_TEST")),
        )
        withRoutes { client ->
            val owner: ProjectSettingsState = client.get("/api/projects/${f.projectId}/settings") {
                cookie(SESSION_COOKIE, f.ownerCookie)
            }.body()
            assertTrue(owner.canConfigureRepository, "An owner lost the GitHub tab.")
            assertEquals("soderbjorn/lunicle", owner.repositoryUrl)
            assertEquals("LUNICLE_GITHUB_TOKEN_TEST", owner.githubTokenEnv)
            assertEquals(TokenModes.ENV, owner.githubTokenMode)

            val admin: ProjectSettingsState = client.get("/api/projects/${f.projectId}/settings") {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
            }.body()
            assertFalse(admin.canConfigureRepository, "A project administrator got the GitHub tab.")
            assertEquals("", admin.repositoryUrl, "The repository leaked to a project administrator.")
        }
    }

    // ── The literal token ────────────────────────────────────────────────────

    /** A literal token is stored, resolved, and never echoed back to the browser. */
    @Test
    fun `a literal token is stored but never returned`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val saved = client.put("/api/projects/${f.projectId}") {
                cookie(SESSION_COOKIE, f.ownerCookie)
                contentType(ContentType.Application.Json)
                setBody(
                    ProjectUpdate(
                        name = "Lunamux",
                        namePrefix = "LMX",
                        isPublic = false,
                        repositoryUrl = "soderbjorn/lunicle",
                        githubTokenMode = TokenModes.LITERAL,
                        githubTokenLiteral = "ghp_secretvalue",
                    ),
                )
            }
            assertEquals(HttpStatusCode.OK, saved.status)

            val settings: ProjectSettingsState = client.get("/api/projects/${f.projectId}/settings") {
                cookie(SESSION_COOKIE, f.ownerCookie)
            }.body()
            assertEquals(TokenModes.LITERAL, settings.githubTokenMode, "The mode did not round-trip.")
            assertEquals("", settings.githubTokenEnv, "A literal token leaked into the env field.")
        }
        // Stored, and readable as a literal by the code entitled to it.
        val stored = projects.repositoryConfig(f.projectId)!!.token
        assertEquals(TokenSource.Literal("ghp_secretvalue"), stored, "The literal token was not stored.")
    }

    /** Saving with the literal field blank keeps the stored token rather than clearing it. */
    @Test
    fun `a blank literal on save keeps the stored token`(): Unit = runBlocking {
        val f = seed()
        projects.setRepositoryConfig(
            f.projectId,
            RepositoryConfig(RepositoryRef("soderbjorn", "lunicle"), TokenSource.Literal("ghp_keepme")),
        )
        withRoutes { client ->
            // The owner edits the repository URL and leaves the write-only token blank.
            val response = client.put("/api/projects/${f.projectId}") {
                cookie(SESSION_COOKIE, f.ownerCookie)
                contentType(ContentType.Application.Json)
                setBody(
                    ProjectUpdate(
                        name = "Lunamux",
                        namePrefix = "LMX",
                        isPublic = false,
                        repositoryUrl = "soderbjorn/other",
                        githubTokenMode = TokenModes.LITERAL,
                        githubTokenLiteral = "",
                    ),
                )
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        val stored = projects.repositoryConfig(f.projectId)!!
        assertEquals(RepositoryRef("soderbjorn", "other"), stored.repository, "The URL edit did not land.")
        assertEquals(TokenSource.Literal("ghp_keepme"), stored.token, "A blank field wiped the stored token.")
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(
        val ownerId: Long,
        val ownerCookie: String,
        val projectAdminId: Long,
        val projectAdminCookie: String,
        val outsiderId: Long,
        val projectId: Long,
        /** A second project the owner has nothing in, so "per-project" is testable. */
        val otherProjectId: Long,
    )

    private suspend fun seed(): Fixture {
        roles.seed()
        // The first account is the system administrator, who owns everything by the
        // flag; this fixture's owner is deliberately NOT that account, so the tests
        // are about the role rather than about isSysAdmin short-circuiting.
        val sysAdmin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-sys", "Sys", "sys@example.com"))
        val owner = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-owner", "Ona", "ona@example.com"))
        val projectAdmin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-pa", "Pat", "pat@example.com"))
        val outsider = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-out", "Out", "out@example.com"))
        assertTrue(sysAdmin.isSysAdmin, "The first account is meant to be the system administrator.")
        assertFalse(owner.isSysAdmin, "The fixture's owner is a system administrator, which defeats the point.")

        val project = projectRepository.create("Lunamux", "LMX", isPublic = false)
        val other = projectRepository.create("Elsewhere", "ELS", isPublic = false)
        roles.grant(owner.id, project.id, Role.PROJECT_OWNER)
        roles.grant(projectAdmin.id, project.id, Role.PROJECT_ADMIN)
        // Bare visibility elsewhere, so the owner can see the other project and its
        // refusals are about ownership rather than about the project being invisible
        // (LNL-57) — the same care ProjectAdminTest's fixture takes.
        roles.grant(owner.id, other.id, Role.VIEW_PROJECT)
        roles.grant(outsider.id, project.id, Role.VIEW_PROJECT)

        return Fixture(
            ownerId = owner.id,
            ownerCookie = sessions.create(owner.id),
            projectAdminId = projectAdmin.id,
            projectAdminCookie = sessions.create(projectAdmin.id),
            outsiderId = outsider.id,
            projectId = project.id,
            otherProjectId = other.id,
        )
    }

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
        audience = ProjectAudience(users, roles),
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
