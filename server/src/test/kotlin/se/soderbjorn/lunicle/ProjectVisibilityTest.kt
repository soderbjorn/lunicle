/**
 * Per-project visibility: what "private" narrowed to, and where it must hold.
 *
 * Until LNL-57, `private` meant "requires any signed-in account" — every account
 * on the instance could read every project. [AccessControl.canReadProject] now
 * asks for membership instead, and membership is "holds any role in this
 * project". The interesting failures are all of the same kind: a read path that
 * forgot to ask.
 *
 *  - **Every read path, not the obvious ones.** The board, one issue, its
 *    comments, the attachment *bytes*, the project list, and the MCP twins of all
 *    of those. A check that exists in six places is missing from the seventh, and
 *    the seventh here is whichever one nobody thought of — historically the
 *    attachment stream, which is why AttachmentTest has guarded it since before
 *    this change and why it is asserted again below.
 *  - **Membership is "holds something", not "holds `view_project`".** The
 *    narrower reading is the one an implementation drifts towards, and it would
 *    reintroduce exactly the incoherence this change fixes: somebody granted
 *    `create_issue` on a private project who cannot see the project they may file
 *    in. Asserted directly, because nothing else in the suite would catch it —
 *    every other fixture grants `view_project` too.
 *  - **The refusal is 404, never 403.** A 403 confirms that a project by that id
 *    exists, which is the thing being withheld. Asserted by status code rather
 *    than by "it failed".
 *  - **Public projects did not move.** The whole risk of narrowing a read rule is
 *    that it narrows further than intended, and the caller who would notice first
 *    is the signed-out one who has no session to fall back on.
 *  - **A system administrator holds no rows.** `isSysAdmin` short-circuits here as
 *    it does everywhere else in [AccessControl]; a build that made admins grant
 *    themselves membership would lock the instance's owner out of a project on
 *    the day they made it private.
 *
 * Through the real routes with real session cookies, for ProjectAdminTest's
 * reason: a test against [AccessControl] alone would pass on a route that never
 * called it, and every claim here is about a route.
 *
 * @see AccessControl.canReadProject
 * @see Role.VIEW_PROJECT
 * @see RoleStore.isMember
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.ProjectListState
import se.soderbjorn.lunicle.clientserver.RoleGrant
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class ProjectVisibilityTest {
    private val file: File = Files.createTempFile("lunicle-visibility", ".db").toFile().also { it.delete() }
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

    private val clients = OAuthClientStore(database)
    private val loginStates = OAuthLoginStateStore(database)
    private val codes = OAuthCodeStore(database)
    private val tokens = OAuthTokenStore(database)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── The private project, from outside ────────────────────────────────────

    /**
     * The list, which is the one read that must *omit* rather than refuse.
     *
     * Every other path below answers 404 for a project it will not show. This one
     * has no id to refuse — it is asked "what is there?" — so the withholding has
     * to happen in the filter, and a build that dropped it would leak every
     * private project's name and prefix to every account on the instance.
     */
    @Test
    fun `the project list omits a private project the caller holds nothing in`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val listed: ProjectListState =
                client.get(ApiRoutes.PROJECTS) { cookie(SESSION_COOKIE, f.outsiderCookie) }.body()
            assertTrue(
                listed.projects.none { it.id == f.privateId },
                "A private project was listed to a non-member.",
            )
            assertTrue(listed.projects.any { it.id == f.publicId }, "The public project vanished from the list.")
        }
    }

    /** The board itself — 404, so the refusal does not confirm the project exists. */
    @Test
    fun `the board of a private project is 404 to a non-member`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val response = client.get(ApiRoutes.board(f.privateId)) { cookie(SESSION_COOKIE, f.outsiderCookie) }
            assertEquals(
                HttpStatusCode.NotFound,
                response.status,
                "A 403 here would confirm that a private project with this id exists.",
            )
        }
    }

    /** One issue, reached by its own id rather than through its board. */
    @Test
    fun `an issue in a private project is 404 to a non-member`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val response = client.get(ApiRoutes.issue(f.privateIssueId)) { cookie(SESSION_COOKIE, f.outsiderCookie) }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    /**
     * The attachment bytes, which are the path most easily forgotten.
     *
     * The row, the file and the project are three different things, and the only
     * one that knows about permissions is the third. AttachmentTest guards the
     * same line for its own reasons; this asserts it again for the new rule,
     * because "the check is present" and "the check now says no to this caller"
     * are different claims.
     */
    @Test
    fun `attachment bytes in a private project are 404 to a non-member`(): Unit = runBlocking {
        val f = seed()
        val stored = attachments.storeForIssue(
            issueId = f.privateIssueId,
            filename = "secret.txt",
            declaredMimeType = "text/plain",
            bytes = "not for you".toByteArray(),
            author = Author.Account(f.sysAdminId),
        )

        withRoutes { client ->
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.attachment(stored.publicId)) {
                    cookie(SESSION_COOKIE, f.outsiderCookie)
                }.status,
                "A non-member downloaded a private project's file.",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get(ApiRoutes.attachment(stored.publicId)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                }.status,
                "A member could not download a file from their own project.",
            )
        }
    }

    /** And the MCP twin of the list, which is a second implementation of the same filter. */
    @Test
    fun `MCP list_projects omits a private project the caller holds nothing in`(): Unit = runBlocking {
        val f = seed()
        withMcp { client ->
            val result = client.callTool(tokenFor(f.outsiderId), "list_projects", "{}")
            assertFalse(result.isError, result.text)
            assertFalse(result.text.contains("Skunkworks"), "MCP listed a private project to a non-member.")
            assertTrue(result.text.contains("Lunamux"), "MCP dropped the public project.")
        }
    }

    // ── The private project, from inside ─────────────────────────────────────

    /** [Role.VIEW_PROJECT] on its own is enough, which is the whole reason it exists. */
    @Test
    fun `a member holding only view_project reads the board`(): Unit = runBlocking {
        val f = seed()
        roles.grant(f.outsiderId, f.privateId, Role.VIEW_PROJECT)
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.get(ApiRoutes.board(f.privateId)) { cookie(SESSION_COOKIE, f.outsiderCookie) }.status,
                "view_project did not grant the one thing it is for.",
            )
        }
    }

    /**
     * And an issue-scoped role is enough too — the claim that separates
     * "membership" from "holds `view_project`".
     *
     * Somebody granted `create_issue` on a private project and nothing else could
     * not see the project they were meant to file in. That incoherence predates
     * this change; phrasing membership as "holds something here" is what retires
     * it, and this is the only test in the suite that would notice a build which
     * narrowed the rule to the single role instead.
     */
    @Test
    fun `a member holding only an issue-scoped role reads the board`(): Unit = runBlocking {
        val f = seed()
        roles.grant(f.outsiderId, f.privateId, Role.CREATE_ISSUE)
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.get(ApiRoutes.board(f.privateId)) { cookie(SESSION_COOKIE, f.outsiderCookie) }.status,
                "Membership was read as \"holds view_project\" rather than \"holds any role\".",
            )
        }
    }

    /** The system administrator, who holds no row anywhere and reads everything anyway. */
    @Test
    fun `a system administrator reads a private project without holding a role`(): Unit = runBlocking {
        val f = seed()
        assertFalse(
            roles.isMember(f.sysAdminId, f.privateId),
            "The fixture's system administrator holds a row, so this test proves nothing.",
        )
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.get(ApiRoutes.board(f.privateId)) { cookie(SESSION_COOKIE, f.sysAdminCookie) }.status,
            )
        }
    }

    // ── The public project, which must not have moved ────────────────────────

    /**
     * A signed-out visitor still reads a public board.
     *
     * The caller with the most to lose from a rule that narrowed too far: they
     * have no session, so there is no membership they could possibly hold and no
     * fallback for the rule to find.
     */
    @Test
    fun `a signed-out visitor still reads a public project`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            assertEquals(HttpStatusCode.OK, client.get(ApiRoutes.board(f.publicId)).status)
            val listed: ProjectListState = client.get(ApiRoutes.PROJECTS).body()
            assertTrue(
                listed.projects.any { it.id == f.publicId },
                "A public project vanished for a signed-out visitor.",
            )
            assertTrue(listed.projects.none { it.id == f.privateId })
        }
    }

    /** And a signed-in non-member reads it too — `is_public` did not change meaning. */
    @Test
    fun `a public project is readable by a signed-in non-member`(): Unit = runBlocking {
        val f = seed()
        assertFalse(roles.isMember(f.outsiderId, f.publicId), "The fixture's outsider is a member of the public one.")
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.get(ApiRoutes.board(f.publicId)) { cookie(SESSION_COOKIE, f.outsiderCookie) }.status,
            )
        }
    }

    // ── The middle tier: visible to all signed-in users (LNL-138) ────────────

    /**
     * A signed-in non-member reads a board turned on for all signed-in users.
     *
     * The whole feature in one assertion: the outsider holds no role here, so under
     * the old rule this would be a 404, and the new tier is what turns it into a 200.
     */
    @Test
    fun `a signed-in non-member reads a project visible to all signed-in users`(): Unit = runBlocking {
        val f = seed()
        assertFalse(
            roles.isMember(f.outsiderId, f.signedInVisibleId),
            "The fixture's outsider holds a role on the signed-in-visible project.",
        )
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.get(ApiRoutes.board(f.signedInVisibleId)) { cookie(SESSION_COOKIE, f.outsiderCookie) }.status,
                "The signed-in-visibility tier did not admit a signed-in account.",
            )
        }
    }

    /** And it is listed to that non-member, unlike a private project. */
    @Test
    fun `a project visible to all signed-in users is listed to a signed-in non-member`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val listed: ProjectListState =
                client.get(ApiRoutes.PROJECTS) { cookie(SESSION_COOKIE, f.outsiderCookie) }.body()
            assertTrue(
                listed.projects.any { it.id == f.signedInVisibleId },
                "A signed-in-visible project was hidden from a signed-in non-member.",
            )
        }
    }

    /**
     * But a signed-out visitor gets neither the board nor a listing.
     *
     * The tier's edge: it says yes to every account and no to a stranger. A build
     * that widened it one step too far — treating it as public — would leak it to
     * the anonymous web, which is the one thing "signed-in" is meant to prevent.
     */
    @Test
    fun `a project visible to all signed-in users is 404 and unlisted to a signed-out visitor`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.board(f.signedInVisibleId)).status,
                "A signed-in-only project answered a signed-out visitor.",
            )
            val listed: ProjectListState = client.get(ApiRoutes.PROJECTS).body()
            assertTrue(
                listed.projects.none { it.id == f.signedInVisibleId },
                "A signed-in-only project leaked into a signed-out visitor's list.",
            )
        }
    }

    /**
     * The read-only guarantee: the tier grants reading and nothing else.
     *
     * Asserted against [AccessControl] directly rather than through a route, because
     * the claim is precisely the split between two of its functions — the tier is in
     * [AccessControl.canReadProject] and deliberately out of
     * [AccessControl.canPostInProject], so a signed-in reader admitted only by this
     * flag may browse a forum but not answer in it. That distinction is the ticket's
     * "browse … but not make changes", and no route asserts it as sharply as this.
     */
    @Test
    fun `the signed-in-visibility tier grants reading but not forum posting`(): Unit = runBlocking {
        val f = seed()
        val project = projects.findById(f.signedInVisibleId)!!
        val outsider = users.findById(f.outsiderId)!!
        assertTrue(
            access.canReadProject(outsider, project),
            "A signed-in account could not read a project visible to all signed-in users.",
        )
        assertFalse(
            access.canPostInProject(outsider, project),
            "The read-only tier let a non-member post in the forum — it grants reading only.",
        )
    }

    // ── Granting it ──────────────────────────────────────────────────────────

    /**
     * A project administrator hands out visibility, and takes it back.
     *
     * Through the grant route rather than [RoleStore.grant], because the point of
     * reusing `project_roles` was to inherit the existing dialog, wire format and
     * tiering rather than build a second grant surface. If the new role needed
     * anything of its own, this is where that would show.
     */
    @Test
    fun `a project administrator grants and revokes visibility`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val granted = client.post(ApiRoutes.projectRoles(f.privateId)) {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
                contentType(ContentType.Application.Json)
                setBody(RoleGrant(userId = f.outsiderId, roleKey = Role.VIEW_PROJECT.key, isGranted = true))
            }
            assertEquals(HttpStatusCode.OK, granted.status, "A project administrator could not grant visibility.")
            assertTrue(roles.isMember(f.outsiderId, f.privateId))

            val revoked = client.post(ApiRoutes.projectRoles(f.privateId)) {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
                contentType(ContentType.Application.Json)
                setBody(RoleGrant(userId = f.outsiderId, roleKey = Role.VIEW_PROJECT.key, isGranted = false))
            }
            assertEquals(HttpStatusCode.OK, revoked.status)
            assertFalse(roles.isMember(f.outsiderId, f.privateId), "Revoking visibility left the caller a member.")
        }
    }

    /**
     * And the settings dialog is offered the new role without being told about it.
     *
     * The privileges table is built from `Role.entries`, so a role added to the
     * enum appears there for free — that inheritance was the argument for reusing
     * `project_roles` over a `project_members` table, and it is worth one
     * assertion that it actually happened.
     */
    @Test
    fun `the settings dialog offers the visibility role`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val state: ProjectSettingsState = client.get(ApiRoutes.projectSettings(f.privateId)) {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
            }.body()
            assertTrue(
                state.roles.any { it.key == Role.VIEW_PROJECT.key },
                "The privileges table has no row for the role that decides who sees the project.",
            )
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(
        val sysAdminId: Long,
        val sysAdminCookie: String,
        val projectAdminCookie: String,
        val memberCookie: String,
        val outsiderId: Long,
        val outsiderCookie: String,
        val publicId: Long,
        val privateId: Long,
        val privateIssueId: Long,
        val signedInVisibleId: Long,
    )

    private suspend fun seed(): Fixture {
        roles.seed()
        val sysAdmin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-sys", "Sys", "sys@example.com"))
        val projectAdmin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-pa", "Pat", "pat@example.com"))
        val member = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-mem", "Mem", "mem@example.com"))
        val outsider = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-out", "Out", "out@example.com"))
        assertTrue(sysAdmin.isSysAdmin, "The first account is meant to be the system administrator.")
        assertFalse(outsider.isSysAdmin, "The fixture's outsider is a system administrator.")

        val public = projectRepository.create("Lunamux", "LMX", isPublic = true)
        val private = projectRepository.create("Skunkworks", "SKW", isPublic = false)
        // The middle read tier (LNL-138): readable by any signed-in account, no
        // membership granted on it — the outsider holds nothing here, which is what
        // the tier tests below turn on.
        val signedInVisible =
            projectRepository.create("Browsable", "BRW", isPublic = false, visibleToAllSignedIn = true)
        roles.grant(projectAdmin.id, private.id, Role.PROJECT_ADMIN)
        roles.grant(member.id, private.id, Role.VIEW_PROJECT)
        // Deliberately no grant for the outsider anywhere, and none for the
        // system administrator: both of those absences are what tests above
        // assert against.

        val issue = issueRepository.createDraft(private.id, Author.Account(sysAdmin.id))
        issueRepository.save(
            issue = issues.findById(issue.first)!!,
            title = "Something private",
            description = "",
            statusId = statuses.forProject(private.id).first().id,
            priorityId = priorities.defaultForProject(private.id)!!.id,
            resolutionId = null,
            assigneeId = null,
            sprintId = null,
            plannedVersionId = null,
            fixedVersionId = null,
            labelIds = emptyList(),
            componentIds = emptyList(),
        )

        return Fixture(
            sysAdminId = sysAdmin.id,
            sysAdminCookie = sessions.create(sysAdmin.id),
            projectAdminCookie = sessions.create(projectAdmin.id),
            memberCookie = sessions.create(member.id),
            outsiderId = outsider.id,
            outsiderCookie = sessions.create(outsider.id),
            publicId = public.id,
            privateId = private.id,
            privateIssueId = issue.first,
            signedInVisibleId = signedInVisible.id,
        )
    }

    private fun withRoutes(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing {
                boardRoutes(boardDependencies())
                projectSettingsRoutes(boardDependencies())
            }
        }
        block(createClient { install(ClientContentNegotiation) { json() } })
    }

    /** A real access token for [userId], with MCP enabled — see McpDeleteTest.tokenFor. */
    private suspend fun tokenFor(userId: Long): String {
        users.setMcpAllowed(userId, true)
        users.setMcpEnabled(userId, true)
        val client =
            clients.register("Test agent", listOf("http://localhost:1234/callback"), listOf("authorization_code"))
        return tokens.issueTokens(userId, client.clientId, "mcp", "http://localhost/mcp").accessToken
    }

    private class ToolOutcome(val text: String, val isError: Boolean)

    private suspend fun HttpClient.callTool(token: String, name: String, arguments: String): ToolOutcome {
        val response = post(MCP_PATH) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"$name","arguments":$arguments}}
                """.trimIndent(),
            )
        }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["error"] == null, "JSON-RPC error rather than a tool result: $body")
        val result = body["result"]!!.jsonObject
        val text = result["content"]!!.jsonArray
            .joinToString("\n") { (it as JsonObject)["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        return ToolOutcome(text, result["isError"]?.jsonPrimitive?.contentOrNull == "true")
    }

    private fun withMcp(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing { mcpRoutes(mcpDependencies(), McpTools(boardDependencies())) }
        }
        block(createClient { })
    }

    private fun mcpDependencies() = McpDependencies(
        clients = clients,
        loginStates = loginStates,
        codes = codes,
        tokens = tokens,
        sessions = sessions,
        users = users,
        impersonations = Impersonations(),
        config = OAuthConfig(google = null),
    )

    private fun boardDependencies() = BoardDependencies(
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
