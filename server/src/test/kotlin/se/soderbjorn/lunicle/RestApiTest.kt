/**
 * The REST API and its personal access tokens, end to end (LNL-222).
 *
 * Driven through the real `/api/v1` and `/api/api-access` routes with real tokens,
 * minted by the real management route, because every claim here is about what a
 * program holding a token gets back over the wire:
 *
 *  - **Parity is structural.** Every MCP tool has a route or a stated reason not to,
 *    and every route's path names arguments its tool actually takes. This is the test
 *    that makes "the API can do what MCP can" stay true when a tool is added.
 *  - **The gates are re-read per request.** A token stops the moment it is revoked,
 *    its owner's switch goes off, or the tier's permission is withdrawn — and all of
 *    those, plus an expired or unknown token, are one indistinguishable 401.
 *  - **The scope is a ceiling.** A read-only token reads and is refused every write.
 *  - **A token is the person, not an agent.** No agent floor — a Viewer's token reads a
 *    board their agent could not — yet the person's own write rules still hold. No
 *    agent badge either; `agent_name` is refused, and history names the token instead.
 *  - **The management routes never mint during an impersonation.**
 *
 * @see RestApi
 * @see ApiAccessRoutes
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import se.soderbjorn.lunicle.clientserver.ApiAccessState
import se.soderbjorn.lunicle.clientserver.ApiTokenScope
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.CreateApiTokenRequest
import se.soderbjorn.lunicle.clientserver.CreatedApiToken
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
import se.soderbjorn.lunicle.clientserver.IssueEventKind
import se.soderbjorn.lunicle.store.InstanceSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class RestApiTest {
    private val file: File = Files.createTempFile("lunicle-rest-api", ".db").toFile().also { it.delete() }
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
    private val events = IssueEventStore(database)
    private val relations = IssueRelationStore(database)
    private val relationKinds = IssueRelationKindStore(database, relations)
    private val attachmentStore = AttachmentStore(database)
    private val attachments = AttachmentRepository(attachmentStore, File(file.parentFile, "attachments-${file.name}"))
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
    private val history = IssueHistory(events, statuses, labels, components, users, issues = issues, projects = projects)
    private val issueRepository = IssueRepository(
        issues, comments, statuses, priorities, attachments, attachmentStore,
        history = history, relations = relations, relationKinds = relationKinds,
    )
    private val sprintRepository = SprintRepository(database, sprints, projects, issues, statuses)
    private val vocabularies =
        VocabularyRepository(database, labels, components, statuses, priorities, resolutions, sprints, versions, issues = issues)

    /**
     * The API permitted for members, which is every account here (no staff domain). The
     * agent switches are left off on purpose: nothing in this file may depend on them,
     * because the API's gate is its own.
     */
    private val instanceSettings = InMemoryInstanceSettingsStore(InstanceSettings(memberMayUseApi = true))
    private val access = AccessControl(roles, instanceSettings)
    private val apiTokens = ApiTokenStore(database)
    private val impersonation = OwnerImpersonation(isEnabled = true, grants = ProbeGrants())

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── Parity ───────────────────────────────────────────────────────────────

    /**
     * The test that keeps the API whole.
     *
     * A tool added to McpTools with no route and no stated exclusion fails here, so "the
     * API can do everything MCP can" is not a promise somebody has to remember.
     */
    @Test
    fun `every MCP tool has a route or a stated reason not to`() {
        val tools = McpTools(boardDependencies()).tools.map { it.name }.toSet()
        val routed = REST_ROUTES.map { it.tool }.toSet()

        val missing = tools - routed - REST_EXCLUDED_TOOLS.keys
        assertEquals(emptySet(), missing, "MCP tools with no REST route and no stated exclusion.")
        assertEquals(emptySet(), routed - tools, "REST routes naming tools that do not exist.")
        assertEquals(emptySet(), routed intersect REST_EXCLUDED_TOOLS.keys, "Tools both routed and excluded.")
    }

    @Test
    fun `every path parameter is an argument its tool takes, and no route is declared twice`() {
        val tools = McpTools(boardDependencies()).tools.associateBy { it.name }
        REST_ROUTES.forEach { route ->
            val properties = (tools.getValue(route.tool).inputSchema["properties"] as JsonObject).keys
            route.pathParameters.forEach { name ->
                assertTrue(name in properties, "${route.path} puts `$name` in the path, but ${route.tool} takes no such argument.")
            }
        }
        val keys = REST_ROUTES.map { "${it.method.value} ${it.path}" }
        assertEquals(keys.size, keys.toSet().size, "A method and path are declared twice: $keys")
    }

    @Test
    fun `the OpenAPI description covers every route and offers no agent_name`() = runBlocking {
        withApi { client ->
            val response = client.get("$REST_API_PATH/openapi.json")
            assertEquals(HttpStatusCode.OK, response.status, "The description needs no token.")
            val document = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val operations = document["paths"]!!.jsonObject.values
                .flatMap { it.jsonObject.values }
                .map { it.jsonObject["operationId"]!!.jsonPrimitive.content }
            assertEquals(operations.size, operations.toSet().size, "Duplicate operationIds.")
            // The tools, plus the three hand-written operations: who the token is, and
            // the two change streams (LNL-224), which are not tools.
            assertEquals((REST_ROUTES.map { it.tool } + "me" + "project_events" + "events").toSet(), operations.toSet())
            assertFalse(response.bodyAsText().contains("\"agent_name\""), "The description offers agent_name.")
            assertFalse(response.bodyAsText().contains("\"project_name\""), "The description offers project_name.")
        }
    }

    // ── Authentication and the gates ─────────────────────────────────────────

    @Test
    fun `no token, a malformed one and an MCP token are all a 401`() = runBlocking {
        seed()
        withApi { client ->
            val bare = client.get("$REST_API_PATH/projects")
            assertEquals(HttpStatusCode.Unauthorized, bare.status)
            assertTrue(bare.headers[HttpHeaders.WWWAuthenticate].orEmpty().startsWith("Bearer"))
            assertEquals("invalid_token", bare.error())

            assertEquals(HttpStatusCode.Unauthorized, client.getWith("lnl_pat_nope", "/projects").status)
            assertEquals(HttpStatusCode.Unauthorized, client.getWith("mcp_at_0123", "/projects").status)
        }
    }

    @Test
    fun `revoking, switching off and withdrawing the tier each stop a live token at once`() = runBlocking {
        val f = seed()
        withApi { client ->
            val token = client.mintToken(f.ownerCookie, "CI", ApiTokenScope.READ)
            assertEquals(HttpStatusCode.OK, client.getWith(token, "/projects").status)

            // The person's own switch: off stops it, on restores it — a gate, not a purge.
            users.setApiEnabled(f.ownerId, false)
            assertEquals(HttpStatusCode.Unauthorized, client.getWith(token, "/projects").status)
            users.setApiEnabled(f.ownerId, true)
            assertEquals(HttpStatusCode.OK, client.getWith(token, "/projects").status)

            // The tier, for a member — the owner is permitted regardless, so use one.
            val memberToken = client.mintToken(f.memberCookie, "Member's", ApiTokenScope.READ)
            users.setApiEnabled(f.memberId, true)
            assertEquals(HttpStatusCode.OK, client.getWith(memberToken, "/projects").status)
            instanceSettings.set(InstanceSettingKey.MEMBER_MAY_USE_API, false)
            assertEquals(HttpStatusCode.Unauthorized, client.getWith(memberToken, "/projects").status)
            instanceSettings.set(InstanceSettingKey.MEMBER_MAY_USE_API, true)

            // Revoking deletes, and the next request is refused.
            val id = apiTokens.forUser(f.ownerId).single().id
            client.delete("/api/api-access/tokens/$id") { cookie(f.ownerCookie) }
            assertEquals(HttpStatusCode.Unauthorized, client.getWith(token, "/projects").status)
        }
    }

    @Test
    fun `the agent switches play no part`() = runBlocking {
        val f = seed()
        withApi { client ->
            val token = client.mintToken(f.memberCookie, "No agents here", ApiTokenScope.READ)
            users.setApiEnabled(f.memberId, true)
            assertFalse(instanceSettings.current().memberMayUseAgents)
            assertFalse(users.findById(f.memberId)!!.isMcpEnabled)
            assertEquals(HttpStatusCode.OK, client.getWith(token, "/projects").status)
        }
    }

    @Test
    fun `me says who the token is`() = runBlocking {
        val f = seed()
        withApi { client ->
            val token = client.mintToken(f.ownerCookie, "Dashboard", ApiTokenScope.READ)
            val me = client.getWith(token, "/me").json()
            assertEquals(f.ownerId, me["user"]!!.jsonObject["id"]!!.jsonPrimitive.long)
            assertEquals("Dashboard", me["token"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals("read", me["token"]!!.jsonObject["scope"]!!.jsonPrimitive.content)
        }
    }

    // ── Scope ────────────────────────────────────────────────────────────────

    @Test
    fun `a read-only token reads and is refused every write`() = runBlocking {
        val f = seed()
        withApi { client ->
            val token = client.mintToken(f.ownerCookie, "Read", ApiTokenScope.READ)
            assertEquals(HttpStatusCode.OK, client.getWith(token, "/projects/${f.projectId}/board").status)
            assertEquals(HttpStatusCode.OK, client.getWith(token, "/issues/${f.issueId}").status)

            val write = client.sendWith(token, "POST", "/projects/${f.projectId}/issues", """{"title":"Nope"}""")
            assertEquals(HttpStatusCode.Forbidden, write.status)
            assertEquals("insufficient_scope", write.error())
            assertTrue(write.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("insufficient_scope"))
            assertEquals(1, issues.forProject(f.projectId).size, "A read-only token wrote anyway.")
        }
    }

    // ── Writing, as the person ───────────────────────────────────────────────

    @Test
    fun `creating an issue answers 201 with its id and key, and history names the token`() = runBlocking {
        val f = seed()
        withApi { client ->
            val token = client.mintToken(f.ownerCookie, "Importer", ApiTokenScope.WRITE)
            val created = client.sendWith(
                token, "POST", "/projects/${f.projectId}/issues",
                """{"title":"Filed by a script","labels":[]}""",
            )
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val body = created.json()
            val id = body["id"]!!.jsonPrimitive.long
            assertTrue(body["key"]!!.jsonPrimitive.content.startsWith("LMX-"))
            assertTrue(body["message"]!!.jsonPrimitive.content.isNotBlank())

            val issue = issues.findById(id)!!
            assertNull(issue.agentName, "A token's issue wears the agent badge.")
            val created1 = history.forIssue(id).single { it.kind == IssueEventKind.CREATED }
            assertEquals("Importer", created1.viaToken, "History does not say which token did it.")
            assertNull(created1.agentName)
            assertEquals(Author.Account(f.ownerId), created1.author, "The token's issue is not its owner's.")

            // And the next change, through a different route, is stamped too.
            val moved = client.sendWith(token, "POST", "/issues/$id/move", """{"status":"In progress"}""")
            assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
            val statusEvent = history.forIssue(id).last { it.kind == IssueEventKind.STATUS_CHANGED }
            assertEquals("Importer", statusEvent.viaToken)

            // get_issue says so too.
            val read = client.getWith(token, "/issues/$id").json()
            val viaTokens = read["history"]!!.jsonArray.mapNotNull { it.jsonObject["viaToken"]?.jsonPrimitive?.contentOrNull }
            assertTrue("Importer" in viaTokens, "get_issue does not report the token: $read")
        }
    }

    @Test
    fun `a change made without a token records none`() = runBlocking {
        val f = seed()
        val recorded = history.forIssue(f.issueId)
        assertTrue(recorded.isNotEmpty(), "The fixture's issue has no history, so this proves nothing.")
        assertTrue(recorded.all { it.viaToken == null }, "A web-made event names a token.")
    }

    @Test
    fun `agent_name, an unknown argument and a path id repeated in the body are refused by name`() = runBlocking {
        val f = seed()
        withApi { client ->
            val token = client.mintToken(f.ownerCookie, "Strict", ApiTokenScope.WRITE)

            val badge = client.sendWith(token, "PATCH", "/issues/${f.issueId}", """{"agent_name":"Bot"}""")
            assertEquals(HttpStatusCode.BadRequest, badge.status)
            assertTrue(badge.message().contains("agent_name"))

            val typo = client.sendWith(token, "PATCH", "/issues/${f.issueId}", """{"titel":"Oops"}""")
            assertEquals(HttpStatusCode.BadRequest, typo.status)
            assertTrue(typo.message().contains("titel"), typo.message())

            val repeated = client.sendWith(token, "PATCH", "/issues/${f.issueId}", """{"issue_id":1,"title":"x"}""")
            assertEquals(HttpStatusCode.BadRequest, repeated.status)

            val notANumber = client.getWith(token, "/issues/abc")
            assertEquals(HttpStatusCode.BadRequest, notANumber.status)

            val notAnObject = client.sendWith(token, "PATCH", "/issues/${f.issueId}", """["title"]""")
            assertEquals(HttpStatusCode.BadRequest, notAnObject.status)

            assertEquals("Something to read", issues.findById(f.issueId)!!.title, "A refused request wrote.")
        }
    }

    @Test
    fun `a comment, a link and an edit go through and report what they made`() = runBlocking {
        val f = seed()
        withApi { client ->
            val token = client.mintToken(f.ownerCookie, "Bot", ApiTokenScope.WRITE)

            val comment = client.sendWith(token, "POST", "/issues/${f.issueId}/comments", """{"body":"From a script"}""")
            assertEquals(HttpStatusCode.Created, comment.status, comment.bodyAsText())
            val commentId = comment.json()["id"]!!.jsonPrimitive.long
            assertEquals("From a script", comments.findById(commentId)!!.body)
            assertNull(comments.findById(commentId)!!.agentName, "A token's comment wears the agent badge.")

            val other = client.sendWith(token, "POST", "/projects/${f.projectId}/issues", """{"title":"Other"}""")
                .json()["id"]!!.jsonPrimitive.long
            val link = client.sendWith(
                token, "POST", "/issues/${f.issueId}/links", """{"to_issue_id":$other,"relation":"Blocked by"}""",
            )
            assertEquals(HttpStatusCode.Created, link.status, link.bodyAsText())
            val relationId = link.json()["relationId"]!!.jsonPrimitive.long
            val unlink = client.sendWith(token, "DELETE", "/issues/${f.issueId}/links/$relationId", "")
            assertEquals(HttpStatusCode.OK, unlink.status, unlink.bodyAsText())

            val edit = client.sendWith(token, "PATCH", "/issues/${f.issueId}", """{"title":"Retitled"}""")
            assertEquals(HttpStatusCode.OK, edit.status, edit.bodyAsText())
            assertEquals("Retitled", issues.findById(f.issueId)!!.title)
        }
    }

    // ── Reach: the person, not an agent ──────────────────────────────────────

    /**
     * The one place the API is deliberately wider than MCP, pinned both ways: the
     * Viewer's token reads the board their agent is not even shown — and is still
     * refused a write, by the same sentence the web app would use.
     */
    @Test
    fun `a Viewer's token reads a board their agent could not, and still cannot write to it`() = runBlocking {
        val f = seed()
        withApi { client ->
            val token = client.mintToken(f.memberCookie, "Viewer's", ApiTokenScope.WRITE)
            users.setApiEnabled(f.memberId, true)

            val listed = client.getWith(token, "/projects").bodyAsText()
            assertTrue(listed.contains("Lunamux"), "A Viewer's token was not shown a board they can view.")
            assertEquals(HttpStatusCode.OK, client.getWith(token, "/projects/${f.projectId}/board").status)

            val write = client.sendWith(token, "POST", "/projects/${f.projectId}/issues", """{"title":"Sneaky"}""")
            assertEquals(HttpStatusCode.Forbidden, write.status, write.bodyAsText())
            assertEquals("forbidden", write.error())
        }
    }

    /**
     * `assignableUsers` (LNL-223): names a picker can offer, for someone who can file
     * here, and no key at all for the Viewer — who could not use a name, and to whom
     * the list would only be a directory of who works on the project. The Viewer is a
     * Viewer, so is not in it; the owner holds every rung, so is.
     */
    @Test
    fun `the board lists who can be assigned, and only to someone who can write`() = runBlocking {
        val f = seed()
        withApi { client ->
            val ownerToken = client.mintToken(f.ownerCookie, "Owner's", ApiTokenScope.READ)
            val memberToken = client.mintToken(f.memberCookie, "Viewer's", ApiTokenScope.READ)

            val ownerBoard = client.getWith(ownerToken, "/projects/${f.projectId}/board").json()
            assertEquals(
                listOf("Owner"),
                ownerBoard["assignableUsers"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content },
            )
            val memberBoard = client.getWith(memberToken, "/projects/${f.projectId}/board").json()
            assertTrue("assignableUsers" !in memberBoard, "A Viewer was handed the assignable list: $memberBoard")
        }
    }

    @Test
    fun `a project out of reach is the same 404 as one that does not exist`() = runBlocking {
        val f = seed()
        val hidden = projectRepository.create("Hidden", "HID")
        withApi { client ->
            val token = client.mintToken(f.memberCookie, "Prober", ApiTokenScope.READ)
            users.setApiEnabled(f.memberId, true)
            val outOfReach = client.getWith(token, "/projects/${hidden.id}/board")
            val absent = client.getWith(token, "/projects/987654/board")
            assertEquals(HttpStatusCode.NotFound, outOfReach.status)
            assertEquals(absent.status, outOfReach.status)
            assertEquals(absent.bodyAsText(), outOfReach.bodyAsText(), "A hidden board refuses differently from a missing one.")
        }
    }

    // ── The management routes ────────────────────────────────────────────────

    @Test
    fun `a token is shown once and never listed`() = runBlocking {
        val f = seed()
        withApi { client ->
            val response = client.post("/api/api-access/tokens") {
                cookie(f.ownerCookie)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(CreateApiTokenRequest.serializer(), CreateApiTokenRequest("Once", ApiTokenScope.READ, 30)))
            }
            val created = Json.decodeFromString(CreatedApiToken.serializer(), response.bodyAsText())
            assertTrue(created.token.startsWith(ApiTokenCrypto.PREFIX))
            val listed = client.get("/api/api-access") { cookie(f.ownerCookie) }.bodyAsText()
            assertFalse(listed.contains(created.token), "The token was listed after it was shown.")
            val state = Json.decodeFromString(ApiAccessState.serializer(), listed)
            assertEquals(listOf("Once"), state.tokens.map { it.name })
            assertTrue(created.token.startsWith(state.tokens.single().prefix))
            assertNotNull(state.tokens.single().expiresAt, "A 30-day token was stored as never expiring.")
        }
    }

    @Test
    fun `making a token refuses a blank name, an invented lifetime and an unpermitted tier`() = runBlocking {
        val f = seed()
        withApi { client ->
            suspend fun make(cookie: String, request: CreateApiTokenRequest) = client.post("/api/api-access/tokens") {
                cookie(cookie)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(CreateApiTokenRequest.serializer(), request))
            }.status

            assertEquals(HttpStatusCode.BadRequest, make(f.ownerCookie, CreateApiTokenRequest("  ", ApiTokenScope.READ, 30)))
            assertEquals(HttpStatusCode.BadRequest, make(f.ownerCookie, CreateApiTokenRequest("Old", ApiTokenScope.READ, 36_500)))
            instanceSettings.set(InstanceSettingKey.MEMBER_MAY_USE_API, false)
            assertEquals(HttpStatusCode.Forbidden, make(f.memberCookie, CreateApiTokenRequest("No", ApiTokenScope.READ, 30)))
            assertEquals(emptyList(), apiTokens.forUser(f.memberId) + apiTokens.forUser(f.ownerId))
        }
    }

    /**
     * The first real use of this feature made a token with the switch off, and then
     * spent a while being told the token was invalid (LNL-222). A token now exists only
     * while it can work — refused with a sentence naming the switch, and nothing stored.
     */
    @Test
    fun `a token cannot be made while the person's own API switch is off`() = runBlocking {
        val f = seed()
        users.setApiEnabled(f.ownerId, false)
        withApi { client ->
            val response = client.post("/api/api-access/tokens") {
                cookie(f.ownerCookie)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(CreateApiTokenRequest.serializer(), CreateApiTokenRequest("Too early", ApiTokenScope.READ, null)))
            }
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("Let your scripts and apps use the API"), response.bodyAsText())
            assertEquals(emptyList(), apiTokens.forUser(f.ownerId), "A token was stored anyway.")
        }
    }

    @Test
    fun `nothing about a person's tokens can be changed through an impersonation`() = runBlocking {
        val f = seed()
        val probeId = impersonation.grants.arm(f.ownerId)
        val probeCookie = sessions.create(f.memberId, probeId)
        withApi { client ->
            val mint = client.post("/api/api-access/tokens") {
                cookie(probeCookie)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(CreateApiTokenRequest.serializer(), CreateApiTokenRequest("Mine now", ApiTokenScope.WRITE, null)))
            }
            assertEquals(HttpStatusCode.Forbidden, mint.status, "A probe minted a durable credential as somebody else.")
            assertEquals(emptyList(), apiTokens.forUser(f.memberId))

            val toggle = client.post("/api/api-access/enabled") {
                cookie(probeCookie)
                contentType(ContentType.Application.Json)
                setBody("""{"isEnabled":true}""")
            }
            assertEquals(HttpStatusCode.Forbidden, toggle.status)
            assertEquals(HttpStatusCode.OK, client.get("/api/api-access") { cookie(probeCookie) }.status, "A probe cannot even look.")
        }
    }

    @Test
    fun `somebody else's token id revokes nothing`() = runBlocking {
        val f = seed()
        withApi { client ->
            val token = client.mintToken(f.ownerCookie, "Owner's", ApiTokenScope.READ)
            val id = apiTokens.forUser(f.ownerId).single().id
            client.delete("/api/api-access/tokens/$id") { cookie(f.memberCookie) }
            assertEquals(HttpStatusCode.OK, client.getWith(token, "/projects").status, "A stranger revoked the owner's token.")
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(
        val ownerId: Long,
        val memberId: Long,
        val ownerCookie: String,
        val memberCookie: String,
        val projectId: Long,
        val issueId: Long,
    )

    /**
     * The instance owner (first to sign in), a member who is a Viewer on the one project,
     * and an issue on it filed through the web path — so it has a history with no token.
     */
    private suspend fun seed(): Fixture {
        val owner = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-owner", "Owner", "owner@example.com"))
        val member = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-member", "Member", "member@example.com"))
        seatInstanceOwner(users, instanceSettings)

        val project = projectRepository.create("Lunamux", "LMX")
        roles.setRole(member.id, project.id, ProjectRole.VIEWER)

        val columns = statuses.forProject(project.id)
        val created = issueRepository.createDraft(project.id, Author.Account(owner.id))
        issueRepository.save(
            issue = issues.findById(created.first)!!,
            title = "Something to read",
            description = "",
            statusId = columns.first().id,
            priorityId = priorities.defaultForProject(project.id)!!.id,
            resolutionId = null,
            assigneeId = null,
            sprintId = null,
            plannedVersionId = null,
            fixedVersionId = null,
            labelIds = emptyList(),
            componentIds = emptyList(),
        )
        // Both have switched API access on: a token can only be made while it is (see
        // `a token cannot be made while the person's own API switch is off`).
        users.setApiEnabled(owner.id, true)
        users.setApiEnabled(member.id, true)
        return Fixture(
            ownerId = owner.id,
            memberId = member.id,
            ownerCookie = sessions.create(owner.id),
            memberCookie = sessions.create(member.id),
            projectId = project.id,
            issueId = created.first,
        )
    }

    /** Make a token through the real management route, as the session's user. */
    private suspend fun HttpClient.mintToken(cookie: String, name: String, scope: ApiTokenScope): String {
        val response = post("/api/api-access/tokens") {
            cookie(cookie)
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreateApiTokenRequest.serializer(), CreateApiTokenRequest(name, scope, null)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Json.decodeFromString(CreatedApiToken.serializer(), response.bodyAsText()).token
    }

    private fun io.ktor.client.request.HttpRequestBuilder.cookie(value: String) =
        header(HttpHeaders.Cookie, "$SESSION_COOKIE=$value")

    private suspend fun HttpClient.getWith(token: String, path: String): HttpResponse =
        get(REST_API_PATH + path) { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun HttpClient.sendWith(token: String, method: String, path: String, body: String): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Authorization, "Bearer $token")
            if (body.isNotEmpty()) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return when (method) {
            "POST" -> post(REST_API_PATH + path, build)
            "PATCH" -> patch(REST_API_PATH + path, build)
            "DELETE" -> delete(REST_API_PATH + path, build)
            else -> error("Unsupported method $method")
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.error(): String? = (json()["error"] as? JsonPrimitive)?.contentOrNull
    private suspend fun HttpResponse.message(): String = (json()["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** Mount the real `/api/v1` and `/api/api-access`, and hand back a client. */
    private fun withApi(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing {
                val deps = RestApiDependencies(
                    tokens = apiTokens,
                    users = users,
                    instanceSettings = instanceSettings,
                    tools = McpTools(boardDependencies(), ToolSurface.REST_API),
                )
                restApiRoutes(deps)
                apiAccessRoutes(mcpDependencies(), deps)
            }
        }
        block(createClient { })
    }

    private fun mcpDependencies() = McpDependencies(
        clients = OAuthClientStore(database),
        loginStates = OAuthLoginStateStore(database),
        codes = OAuthCodeStore(database),
        tokens = OAuthTokenStore(database),
        sessions = sessions,
        users = users,
        impersonation = impersonation,
        config = OAuthConfig(google = null),
        instanceSettings = instanceSettings,
        access = access,
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
        sprints = sprintRepository,
        sprintRepository = sprintRepository,
        issues = issues,
        issueRelations = relations,
        issueRelationKinds = relationKinds,
        issueRepository = issueRepository,
        comments = comments,
        attachments = attachmentStore,
        attachmentRepository = attachments,
        attachmentTickets = AttachmentTicketStore(),
        sessions = sessions,
        users = users,
        subscriptions = SubscriptionStore(database),
        reads = ReadStore(database),
        history = history,
    )
}
