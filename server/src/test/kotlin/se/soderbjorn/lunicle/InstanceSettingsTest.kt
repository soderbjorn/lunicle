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
 *  - **require-sign-in is gone** (LNL-192), off the wire entirely rather than pinned
 *    false, so there is nothing left here to assert about it. What it used to say is a
 *    per-project guest audience row now.
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
import se.soderbjorn.lunicle.clientserver.AdminSettingsState
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.CommentUpdate
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
            // A tier's permissions ride on its card now (LNL-195), so this is read where the
            // screen reads it — off the Members card rather than off a top-level flag.
            assertTrue(
                after.tiers.first { it.key == "member" }.mayCreateProjects,
                "The second switch was not reported on the tier card that renders it.",
            )
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
                    setBody(ProjectUpdate("Ordinary's", "ORD"))
                }.status,
                "A non-admin created a project with the switch off.",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/projects") {
                    contentType(ContentType.Application.Json)
                    setBody(ProjectUpdate("Nobody's", "NOB"))
                }.status,
                "A signed-out caller created a project.",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.post("/api/projects") {
                    cookie(SESSION_COOKIE, adminCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ProjectUpdate("Admin's", "ADM"))
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
                    setBody(ProjectUpdate("Ordinary's", "ORD"))
                }.status,
                "A signed-in user was refused with open creation on.",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/projects") {
                    contentType(ContentType.Application.Json)
                    setBody(ProjectUpdate("Nobody's", "NOB"))
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

    // ── What a new project starts with (LNL-195) ─────────────────────────────

    /**
     * A member who creates a project **owns** it — with a stored row, not a claim.
     *
     * Nothing wrote that row before LNL-195, which was survivable only while every
     * account that could create was an instance administrator (who reaches Owner
     * everywhere without one). The moment the per-tier switch let a member create, they
     * made a board they held nothing on. Asserted against the store rather than the
     * response, because the response has always claimed Owner regardless.
     */
    @Test
    fun `a member who creates a project holds a stored Owner row on it`(): Unit = runBlocking {
        seed()
        val ordinary = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", null))
        val cookie = sessions.create(ordinary.id)
        instanceSettings.set(InstanceSettingKey.MEMBER_MAY_CREATE_PROJECTS, true)

        withAuthAndBoard { client ->
            client.post("/api/projects") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(ProjectUpdate("Ordinary's", "ORD"))
            }
        }

        val created = projects.selectAll().first { it.namePrefix == "ORD" }
        assertEquals(
            ProjectRole.OWNER,
            roles.roleFor(ordinary.id, created.id),
            "The creator was not seated as the project's owner.",
        )
    }

    /**
     * The audience rows a new project starts with are the instance's, copied once.
     *
     * And they are a **copy**: changing the setting afterwards leaves the project that
     * was already created exactly as it was, which is the whole reason this is a setting
     * rather than a policy layered under every board.
     */
    @Test
    fun `a new project is created with the instance's audience rows, and later edits do not reach it`(): Unit =
        runBlocking {
            val fixture = seed()
            val cookie = sessions.create(fixture.adminId)
            instanceSettings.setNewProjectAudience(Audience.MEMBER, ProjectRole.CONTRIBUTOR)

            withAuthAndBoard { client ->
                client.post("/api/projects") {
                    cookie(SESSION_COOKIE, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(ProjectUpdate("First", "FST"))
                }
            }
            val first = projects.selectAll().first { it.namePrefix == "FST" }
            assertEquals(mapOf(Audience.MEMBER to ProjectRole.CONTRIBUTOR), roles.audienceRoles(first.id))

            // The setting moves; the project that already exists does not.
            instanceSettings.setNewProjectAudience(Audience.MEMBER, ProjectRole.MAINTAINER)
            assertEquals(
                mapOf(Audience.MEMBER to ProjectRole.CONTRIBUTOR),
                roles.audienceRoles(first.id),
                "Editing the setting rewrote a project that already existed.",
            )
        }

    /**
     * With nothing named, a new project admits nobody — LNL-194's default, unchanged.
     */
    @Test
    fun `by default a new project has no audience rows at all`(): Unit = runBlocking {
        val fixture = seed()
        val cookie = sessions.create(fixture.adminId)

        withAuthAndBoard { client ->
            client.post("/api/projects") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(ProjectUpdate("Quiet", "QUI"))
            }
        }
        val created = projects.selectAll().first { it.namePrefix == "QUI" }
        assertTrue(roles.audienceRoles(created.id).isEmpty(), "A new project admitted somebody nobody named.")
    }

    /**
     * The guest row answers to the publish veto even here.
     *
     * Otherwise this setting would be a way to publish boards on a deployment that has
     * forbidden itself from publishing any — the veto is enforced on the project's own
     * write, and it has to be enforced on the copy too.
     */
    @Test
    fun `a guest row is not copied while the publish veto stands`(): Unit = runBlocking {
        val fixture = seed()
        val cookie = sessions.create(fixture.adminId)
        instanceSettings.setNewProjectAudience(Audience.GUEST, ProjectRole.VIEWER)
        instanceSettings.setNewProjectAudience(Audience.MEMBER, ProjectRole.VIEWER)

        withAuthAndBoard { client ->
            client.post("/api/projects") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(ProjectUpdate("Vetoed", "VET"))
            }
        }
        val vetoed = projects.selectAll().first { it.namePrefix == "VET" }
        assertEquals(
            mapOf(Audience.MEMBER to ProjectRole.VIEWER),
            roles.audienceRoles(vetoed.id),
            "A guest row was written on a deployment that forbids public projects.",
        )

        // Lifted, and the next project created honours it.
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)
        withAuthAndBoard { client ->
            client.post("/api/projects") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(ProjectUpdate("Published", "PUB"))
            }
        }
        val published = projects.selectAll().first { it.namePrefix == "PUB" }
        assertEquals(ProjectRole.VIEWER, roles.audienceRoles(published.id)[Audience.GUEST])
    }

    /**
     * A stored guest row above Viewer is capped as it is copied onto a new board (LNL-202).
     *
     * The setting is written straight through the store rather than through its route,
     * because that route refuses it now — this is the value an older build left behind or
     * somebody edited in. It matters because project creation is the one path to a guest row
     * that runs with no picker and no project in existence yet: a board could otherwise be
     * *born* admitting strangers as contributors.
     */
    @Test
    fun `a stored guest row above viewer is capped when a project is created`(): Unit = runBlocking {
        val fixture = seed()
        val cookie = sessions.create(fixture.adminId)
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)
        instanceSettings.setNewProjectAudience(Audience.GUEST, ProjectRole.CONTRIBUTOR)

        withAuthAndBoard { client ->
            client.post("/api/projects") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(ProjectUpdate("Capped", "CAP"))
            }
        }
        val capped = projects.selectAll().first { it.namePrefix == "CAP" }
        assertEquals(
            ProjectRole.VIEWER,
            roles.audienceRoles(capped.id)[Audience.GUEST],
            "A new board was born admitting guests above Viewer, from a setting nothing capped.",
        )
    }

    /**
     * The session says an owner **runs the instance**, even with no administrator row of
     * their own (LNL-198).
     *
     * The settings pane gates all three instance tabs on `session.user.isSysAdmin`, and that
     * flag is derived from `users.instance_role` — which cannot see ownership, because
     * ownership is a setting and 33.sqm deliberately leaves the owner's row null rather than
     * stating one authority twice.
     *
     * So two entirely ordinary deployments would hide the settings pane from the person who
     * owns them: **every migrated volume** (33.sqm nulls the column for everybody), and any
     * instance whose ownership has been handed over. This asserts the seam that folds the two
     * facts together — see AuthRoutes' `sessionStateFor` — and asserts the negative beside
     * it, because a fix that simply returned true for everybody would pass the first half
     * alone.
     */
    @Test
    fun `the session reports an owner with no admin row as running the instance`(): Unit = runBlocking {
        seed()
        // Not the first account, so `upsert` does not hand them the administrator row, and
        // not promoted afterwards: an owner whose only authority is the setting. Exactly the
        // state a handover and a migrated volume leave behind.
        val handedTo = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-heir", "Heir", "heir@example.com"))
        val ordinary = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-plain", "Plain", "plain@example.com"))
        assertFalse(handedTo.isInstanceAdmin, "Precondition: the heir holds no administrator row.")
        instanceSettings.setOwnerUserId(handedTo.id)

        withAuthAndBoard { client ->
            val owner: SessionState = client.get("/api/session") {
                cookie(SESSION_COOKIE, sessions.create(handedTo.id))
            }.body()
            assertTrue(
                owner.user?.isSysAdmin == true,
                "The owner's own session says they do not run the instance, so the settings " +
                    "pane would hide all three instance tabs from them.",
            )

            val plain: SessionState = client.get("/api/session") {
                cookie(SESSION_COOKIE, sessions.create(ordinary.id))
            }.body()
            assertFalse(plain.user?.isSysAdmin == true, "An ordinary account was told it runs the instance.")
        }
    }

    /**
     * The owner edits somebody else's comment **through the route** (LNL-201).
     *
     * Beside the session test above because it is the same deployment: an owner whose only
     * authority is the setting, holding no administrator row, which is the state a
     * hand-over leaves and the state **every volume 33.sqm migrated** is in — that
     * migration nulls `instance_role` for everybody including the account it seats.
     *
     * At the route rather than at [AccessControl] because that is this file's standard, and
     * here it earns it twice: `PUT /api/comments/{id}` runs two gates in order, and a caller
     * who cannot *read* the project is refused by the first with a 404 before the comment
     * gate is asked at all. The owner has to clear both, and clears the first only by owning
     * the instance — the project admits nobody — so only the route shows the whole answer.
     * The body is read back afterwards, because a 204 over an unchanged row is exactly what a
     * half-fix would produce.
     *
     * The account beside them is a **Contributor**, not a stranger, and that is the whole of
     * why: a stranger is refused by the read gate with a 404 and never reaches the comment
     * gate, so it would assert nothing about the rule under test. A contributor may comment
     * here and still may not touch somebody else's words — which is the 403 that says the fix
     * is the ladder rather than a gate that stopped asking.
     */
    @Test
    fun `the instance owner edits somebody elses comment with no admin row of their own`(): Unit = runBlocking {
        val f = seed()
        val heir = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-heir", "Heir", "heir@example.com"))
        val contributor = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-str", "Str", "str@example.com"))
        assertFalse(heir.isInstanceAdmin, "Precondition: the owner holds no administrator row.")
        instanceSettings.setOwnerUserId(heir.id)
        roles.setRole(contributor.id, f.projectId, ProjectRole.CONTRIBUTOR)

        // Somebody else's published comment, on the seeded project.
        val (issueId, _) = issueRepository.createDraft(f.projectId, Author.Account(f.adminId))
        val commentId = issueRepository.createCommentDraft(issueId, Author.Account(f.adminId))
        issueRepository.saveComment(commentId, "not the owner's words")

        withAuthAndBoard { client ->
            val theirs = client.put("/api/comments/$commentId") {
                cookie(SESSION_COOKIE, sessions.create(heir.id))
                contentType(ContentType.Application.Json)
                setBody(CommentUpdate("the owner tidied this up"))
            }
            assertEquals(
                HttpStatusCode.NoContent,
                theirs.status,
                "The instance owner was refused another account's comment. Every other " +
                    "instance-scoped gate lets them through; this was the one that did not.",
            )

            // …and a contributor on the same board still cannot, so the fix is the ladder
            // rather than a gate that stopped asking.
            val refused = client.put("/api/comments/$commentId") {
                cookie(SESSION_COOKIE, sessions.create(contributor.id))
                contentType(ContentType.Application.Json)
                setBody(CommentUpdate("mine now"))
            }
            assertEquals(HttpStatusCode.Forbidden, refused.status, "A contributor edited another person's comment.")
        }

        assertEquals(
            "the owner tidied this up",
            comments.findById(commentId)?.body,
            "The route answered 204 and wrote nothing.",
        )
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
        val impersonation = OwnerImpersonation()
        application {
            install(ServerContentNegotiation) { json() }
            routing {
                authRoutes(
                    config = OAuthConfig(google = null, isEmailAvailable = false),
                    sessions = sessions,
                    users = users,
                    impersonation = impersonation,
                    instanceSettings = instanceSettings,
                )
                boardRoutes(dependencies(impersonation))
            }
        }
        block(createClient { install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() } })
    }

    private fun dependencies(impersonation: OwnerImpersonation) = BoardDependencies(
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
        impersonation = impersonation,
        instanceSettings = instanceSettings,
        subscriptions = SubscriptionStore(database),
        reads = ReadStore(database),
    )
}
