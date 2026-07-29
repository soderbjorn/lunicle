/**
 * The instance-wide switches (LNL-115, LNL-137, reshaped by LNL-192), through the real
 * routes with real session cookies — the standard here (see AdminSettingsTest), because
 * the property that matters is that a route *asks* [AccessControl], not that the rule
 * returns the right boolean in isolation.
 *
 * Their surfaces:
 *
 *  - **member/staff-may-create-projects** gates `POST /api/projects` and the affordance
 *    on `GET /api/projects`. The gate is the load-bearing half: a signed-in member is
 *    refused with their tier's switch off and admitted with it on, while a signed-out
 *    caller is refused either way — creating still needs a session.
 *  - **hide-display-name** rides on `GET /api/session` (LNL-137), so every signed-in
 *    client knows to omit the profile override; the test proves the flag flips there.
 *  - **require-sign-in is gone** (LNL-192), and one test here pins that the session no
 *    longer claims a sign-in gate however the instance is configured. What it used to
 *    say is a per-project guest audience row now.
 *  - all are set through `POST /api/admin/instance-settings`, which is admin-only with
 *    no narrowed half, exactly like the rest of AdminRoutes.
 *
 * @see AccessControl.canCreateProject
 * @see se.soderbjorn.lunicle.clientserver.InstanceSettingKey
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AdminSettingsState
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
import se.soderbjorn.lunicle.clientserver.ProjectListState
import se.soderbjorn.lunicle.clientserver.ProjectUpdate
import se.soderbjorn.lunicle.clientserver.SessionState
import se.soderbjorn.lunicle.clientserver.SetInstanceSettingRequest
import se.soderbjorn.lunicle.store.InstanceSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class InstanceSettingsTest {
    private val file: File = Files.createTempFile("lunicle-instance", ".db").toFile().also { it.delete() }
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
    private val attachments = AttachmentRepository(attachmentStore, File(file.parentFile, "attachments-${file.name}"))
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
    private val issueRepository =
        IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)
    private val sprintRepository = SprintRepository(database, sprints, projects, issues, statuses)
    private val vocabularies =
        VocabularyRepository(database, labels, components, statuses, priorities, resolutions, sprints, versions, issues)
    // The real SQLite-backed store, shared by both route bundles below — the whole
    // point of the file is that a switch set through the admin route is the same
    // switch the session and project routes read.
    private val instanceSettings = InstanceSettingsStore(database)
    private val access = AccessControl(roles, instanceSettings)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── the retired require-sign-in blanket ───────────────────────────────────

    /**
     * The retired blanket, pinned as retired (LNL-192).
     *
     * `isSignInRequired` still crosses the wire so a browser on the previous bundle
     * keeps deserialising the session, and it is now always false: there is no switch
     * left to raise it, and what it used to say is a per-project guest audience row.
     * Asserted after flipping every switch there is, because "no combination of
     * settings brings the landing gate back" is the actual claim.
     */
    @Test
    fun `no instance switch can make the session claim a sign-in gate any more`(): Unit = runBlocking {
        seed()
        for (key in InstanceSettingKey.entries) instanceSettings.set(key, true)

        withAuthAndBoard { client ->
            assertFalse(
                client.get("/api/session").body<SessionState>().isSignInRequired,
                "The signed-out session still claims a deployment-wide sign-in gate.",
            )
        }
    }

    // ── hide-display-name reaches the session ─────────────────────────────────

    /**
     * The switch is off by default, and flipping it through the admin route makes
     * `GET /api/session` report it — so every signed-in client knows to drop the
     * profile override (LNL-137). Read with the admin's own cookie, a signed-in
     * caller, because that is who the profile field it hides belongs to.
     */
    @Test
    fun `hide display name defaults off and reaches the session once set`(): Unit = runBlocking {
        val fixture = seed()
        val adminCookie = sessions.create(fixture.adminId)

        withAuthAndBoard { client ->
            assertFalse(
                client.get("/api/session") { cookie(SESSION_COOKIE, adminCookie) }
                    .body<SessionState>().isDisplayNameHidden,
                "A fresh instance already hid the display name.",
            )

            val set = client.post("/api/admin/instance-settings") {
                cookie(SESSION_COOKIE, adminCookie)
                contentType(ContentType.Application.Json)
                setBody(SetInstanceSettingRequest(InstanceSettingKey.HIDE_DISPLAY_NAME, true))
            }
            assertEquals(HttpStatusCode.OK, set.status)
            assertTrue(
                set.body<AdminSettingsState>().hideDisplayName,
                "The write did not report the switch it had just set.",
            )

            assertTrue(
                client.get("/api/session") { cookie(SESSION_COOKIE, adminCookie) }
                    .body<SessionState>().isDisplayNameHidden,
                "The hide-display-name switch did not reach the session.",
            )
        }
    }

    // ── who may change the switches ───────────────────────────────────────────

    /**
     * A signed-in non-admin cannot set an instance switch, and the switch is
     * unchanged afterwards — the store is checked directly, because a 403 that had
     * already written would still read as a 403.
     */
    @Test
    fun `a non-admin cannot change instance settings`(): Unit = runBlocking {
        seed()
        val ordinary = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", null))
        val cookie = sessions.create(ordinary.id)

        withAuthAndBoard { client ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/admin/instance-settings") {
                    cookie(SESSION_COOKIE, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(SetInstanceSettingRequest(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true))
                }.status,
                "A non-admin flipped an instance switch.",
            )
        }
        assertFalse(
            instanceSettings.current().allowPublicProjects,
            "A refused write set the switch anyway.",
        )
    }

    /** No session at all is a 403 too, not a 401 — like the rest of AdminRoutes. */
    @Test
    fun `a signed-out caller cannot change instance settings`(): Unit = runBlocking {
        seed()
        withAuthAndBoard { client ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/admin/instance-settings") {
                    contentType(ContentType.Application.Json)
                    setBody(SetInstanceSettingRequest(InstanceSettingKey.STAFF_MAY_USE_AGENTS, true))
                }.status,
            )
        }
        // Only the switches: the fixture seats an instance owner, as boot does, so
        // `current()` is never the all-defaults value any more.
        val after = instanceSettings.current()
        assertEquals(
            InstanceSettings(ownerUserId = after.ownerUserId),
            after,
            "A signed-out write changed a switch.",
        )
    }

    /** The admin sets both switches, and the directory reports both back. */
    @Test
    fun `the admin sets both switches and the directory reports them`(): Unit = runBlocking {
        val fixture = seed()
        val cookie = sessions.create(fixture.adminId)

        withAuthAndBoard { client ->
            client.post("/api/admin/instance-settings") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(SetInstanceSettingRequest(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true))
            }
            val after = client.post("/api/admin/instance-settings") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(SetInstanceSettingRequest(InstanceSettingKey.MEMBER_MAY_CREATE_PROJECTS, true))
            }.body<AdminSettingsState>()

            assertTrue(after.allowPublicProjects, "The first switch did not survive the second write.")
            assertTrue(after.memberMayCreateProjects, "The second switch was not reported.")
        }
        val stored = instanceSettings.current()
        assertEquals(
            InstanceSettings(
                allowPublicProjects = true,
                memberMayCreateProjects = true,
                ownerUserId = stored.ownerUserId,
            ),
            stored,
        )
    }

    // ── the per-tier create permission gates POST /api/projects ──────────────

    /**
     * With creation closed (the default), a signed-in non-admin is refused and a
     * signed-out caller is refused, while the admin gets through.
     */
    @Test
    fun `with creation closed only the admin may create a project`(): Unit = runBlocking {
        val fixture = seed()
        val ordinary = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", null))
        val ordinaryCookie = sessions.create(ordinary.id)
        val adminCookie = sessions.create(fixture.adminId)

        withAuthAndBoard { client ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/projects") {
                    cookie(SESSION_COOKIE, ordinaryCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ProjectUpdate("Ordinary's", "ORD", isPublic = false))
                }.status,
                "A non-admin created a project with the switch off.",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/projects") {
                    contentType(ContentType.Application.Json)
                    setBody(ProjectUpdate("Nobody's", "NOB", isPublic = false))
                }.status,
                "A signed-out caller created a project.",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.post("/api/projects") {
                    cookie(SESSION_COOKIE, adminCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ProjectUpdate("Admin's", "ADM", isPublic = false))
                }.status,
                "The admin was refused their own create route.",
            )
        }
        assertTrue(projects.selectAll().any { it.namePrefix == "ADM" })
        assertFalse(projects.selectAll().any { it.namePrefix == "ORD" || it.namePrefix == "NOB" })
    }

    /**
     * With creation open, any signed-in user may create — but a signed-out caller
     * still may not, because creating needs a session whatever the switch says.
     */
    @Test
    fun `with creation open a signed-in user may create but a signed-out one may not`(): Unit = runBlocking {
        seed()
        val ordinary = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", null))
        val ordinaryCookie = sessions.create(ordinary.id)
        instanceSettings.set(InstanceSettingKey.MEMBER_MAY_CREATE_PROJECTS, true)

        withAuthAndBoard { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.post("/api/projects") {
                    cookie(SESSION_COOKIE, ordinaryCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ProjectUpdate("Ordinary's", "ORD", isPublic = false))
                }.status,
                "A signed-in user was refused with open creation on.",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/projects") {
                    contentType(ContentType.Application.Json)
                    setBody(ProjectUpdate("Nobody's", "NOB", isPublic = false))
                }.status,
                "A signed-out caller created a project even though creating needs a session.",
            )
        }
        assertTrue(projects.selectAll().any { it.namePrefix == "ORD" })
        assertFalse(projects.selectAll().any { it.namePrefix == "NOB" })
    }

    /**
     * The picker's `canCreateProject` affordance follows the switch for a signed-in
     * non-admin: false while creation is closed, true once it is open.
     *
     * The affordance and the gate above read the switch the same way, so this is
     * what stops the button and the route disagreeing.
     */
    @Test
    fun `the create affordance follows the switch for a non-admin`(): Unit = runBlocking {
        seed()
        val ordinary = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", null))
        val cookie = sessions.create(ordinary.id)

        withAuthAndBoard { client ->
            assertFalse(
                client.get("/api/projects") { cookie(SESSION_COOKIE, cookie) }
                    .body<ProjectListState>().canCreateProject,
                "A non-admin was offered New project with the switch off.",
            )
        }

        instanceSettings.set(InstanceSettingKey.MEMBER_MAY_CREATE_PROJECTS, true)

        withAuthAndBoard { client ->
            assertTrue(
                client.get("/api/projects") { cookie(SESSION_COOKIE, cookie) }
                    .body<ProjectListState>().canCreateProject,
                "A non-admin was not offered New project with the switch on.",
            )
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val adminId: Long, val projectId: Long)

    private suspend fun seed(name: String = "Lunamux", prefix: String = "LMX"): Fixture {
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin", "Admin", null))
        val project = projectRepository.create(name, prefix)
        // Production seats the instance owner at boot (see InstanceLadder.kt), and
        // four rules — creating and managing projects, backfilling authorship, agent
        // mail, out-of-band attachment deletes — are the owner's alone rather than an
        // administrator's. A fixture that skipped this would be testing an instance
        // nobody runs: one with an administrator and no owner.
        seatInstanceOwner(users, instanceSettings)
        return Fixture(admin.id, project.id)
    }

    /**
     * Mount both route bundles over the one instance-settings store, and hand back a
     * client. The session route lives in [authRoutes] and the admin/project routes
     * in [boardRoutes]; both are given the same [instanceSettings] so a switch set
     * through one is read by the other, which is the whole thing under test.
     */
    private fun withAuthAndBoard(block: suspend (HttpClient) -> Unit) = testApplication {
        val impersonations = Impersonations()
        application {
            install(ServerContentNegotiation) { json() }
            routing {
                authRoutes(
                    config = OAuthConfig(google = null, isEmailAvailable = false),
                    sessions = sessions,
                    users = users,
                    impersonations = impersonations,
                    instanceSettings = instanceSettings,
                )
                boardRoutes(dependencies(impersonations))
            }
        }
        block(createClient { install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() } })
    }

    private fun dependencies(impersonations: Impersonations) = BoardDependencies(
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
        conversations = ConversationRepository(
            ConversationStore(database), MessageStore(database), attachments, attachmentStore,
        ),
        labels = labels,
        components = components,
        statuses = statuses,
        priorities = priorities,
        resolutions = resolutions,
        versions = versions,
        // Both take the store.SprintStore interface; the low-level SprintStore
        // gateway does not implement it, the SprintRepository does — so the
        // repository is passed for both, exactly as Application.module does.
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
        impersonations = impersonations,
        instanceSettings = instanceSettings,
        subscriptions = SubscriptionStore(database),
        reads = ReadStore(database),
    )
}
