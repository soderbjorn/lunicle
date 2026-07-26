/**
 * `send_email`: the tool that mails the person an agent is acting as, and nobody
 * else.
 *
 * ── What is actually being pinned here ───────────────────────────────────────
 *
 * This is the one tool on this surface with no matching HTTP route — see
 * [McpTools]' preamble — so the usual argument for its safety ("it is a second
 * front door onto code already reasoned about") is not available. What replaces
 * it is a claim about shape: the recipient is not an argument, it is the token
 * user's own address. That claim is exactly what a future edit could break while
 * every other test in this repository stayed green, so it is asserted twice over
 * — on the wire, by capturing what the sender was handed, and on the schema, by
 * asserting there is no recipient property to send.
 *
 * The second claim, from LNL-67, is that only a system administrator can make
 * this instance send at all — see [AccessControl.canSendAgentMail] for why that
 * is the answer. It is pinned in three places because it is enforced in two and
 * described in a third: the tool is absent from a non-admin's `tools/list`, the
 * call is refused even when the name is sent anyway, and the instructions do not
 * describe a tool the caller was not offered.
 *
 * The rest is the refusals. All three of them are ordinary states rather than
 * faults, and each leaves the agent with a different next move, which is why the
 * text of the refusal is asserted and not only its presence.
 *
 * Through the real `/mcp` endpoint with real tokens, as the other Mcp*Tests are:
 * the claim is about what an agent can reach over the wire.
 *
 * @see McpTools.sendEmail
 * @see AgentMailTest
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
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
import se.soderbjorn.lunicle.clientserver.AuthProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class McpSendEmailTest {
    private val file: File = Files.createTempFile("lunicle-sendmail", ".db").toFile().also { it.delete() }
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
    private val access = AccessControl(roles)

    private val clients = OAuthClientStore(database)
    private val loginStates = OAuthLoginStateStore(database)
    private val codes = OAuthCodeStore(database)
    private val tokens = OAuthTokenStore(database)

    /** Every request the sender made, in order. Empty is a meaningful assertion here. */
    private val sent = mutableListOf<SentMail>()

    private class SentMail(val to: List<String>, val subject: String, val html: String)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── The recipient is the token's own user, always ────────────────────────

    /**
     * The test this file exists for.
     *
     * A mail really leaves the tool, and it goes to the address on the account
     * the token resolves to — captured off the wire rather than inferred, because
     * "it passed the right variable" is the thing a refactor changes. Also asserts
     * the two markers a reader relies on: the fixed subject prefix, and the header
     * naming the agent above its own words.
     */
    @Test
    fun `a message goes to the token user's own address, marked as an agent's`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "send_email",
                """{"subject":"Migration finished","body":"Three issues needed hand-editing.",
                    "agent_name":"Acme Assistant"}""",
            )
            assertTrue(!result.isError, "An admin could not mail themselves: ${result.text}")
        }

        val mail = sent.singleOrNull() ?: error("Expected exactly one message, got ${sent.size}.")
        assertEquals(listOf("admin@example.com"), mail.to, "The message did not go to the token user.")
        assertEquals("[Lunicle agent] Migration finished", mail.subject, "The subject lost its prefix.")
        assertTrue(mail.html.contains("Acme Assistant"), "The body did not name the agent: ${mail.html}")
        assertTrue(
            mail.html.contains("Three issues needed hand-editing."),
            "The agent's own text did not reach the body: ${mail.html}",
        )
    }

    /**
     * There is no recipient parameter, and this is the assertion that says so.
     *
     * The safety story is that an agent has nothing to aim somewhere else — not
     * that a supplied address is checked. A well-meant later edit that adds a
     * `to` and validates it would pass the test above and lose the property. This
     * one reads the schema an agent is actually offered.
     */
    @Test
    fun `the tool offers no way to name a recipient`() {
        val tool = McpTools(boardDependencies()).tools.single { it.name == "send_email" }
        val properties = tool.inputSchema["properties"]!!.jsonObject
        assertEquals(
            setOf("subject", "body", "agent_name"),
            properties.keys,
            "send_email grew a parameter. If one of these is an address, the tool is now a relay.",
        )
    }

    /**
     * An extra `to` in the arguments is ignored, not honoured.
     *
     * The schema is a description, not a gate — an agent can send whatever JSON
     * it likes, and a prompt-injected one will. What stops it is that nothing
     * reads the key.
     */
    @Test
    fun `an address the agent invents is ignored`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            client.callTool(
                token,
                "send_email",
                """{"subject":"Hello","body":"Hi","to":"attacker@example.com",
                    "recipient":"attacker@example.com","email":"attacker@example.com"}""",
            )
        }

        assertEquals(
            listOf("admin@example.com"),
            sent.single().to,
            "An address in the arguments reached the sender.",
        )
    }

    // ── Only a system administrator can make the instance send ───────────────

    /**
     * The LNL-67 test: an ordinary user's agent is refused, and nothing leaves.
     *
     * Over the wire with the tool named explicitly, because that is the caller
     * this assumes — an agent that never saw the tool in `tools/list` but read
     * about it somewhere, or one talked into trying. The list is an affordance;
     * this is the enforcement.
     */
    @Test
    fun `an ordinary user is refused, whatever their agent asks for`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(token, "send_email", """{"subject":"Hi","body":"There"}""")
            assertTrue(result.isError, "An ordinary user's agent sent e-mail.")
            assertTrue(
                result.text.contains("system administrators"),
                "The refusal did not say who may do this. Got: ${result.text}",
            )
        }
        assertTrue(sent.isEmpty(), "A refused non-admin send left the building anyway.")
    }

    /**
     * A non-admin is not offered the tool, so a model has nothing to reach for.
     *
     * Asserted alongside the refusal above rather than instead of it: this is why
     * a well-behaved agent never tries, and that is why it stops being refused.
     */
    @Test
    fun `send_email is missing from an ordinary user's tool list, and present for an admin`(): Unit = runBlocking {
        val fixture = seed()
        val ordinary = tokenFor(fixture.ordinaryId)
        val admin = tokenFor(fixture.adminId)

        withMcp { client ->
            assertTrue(
                "send_email" !in client.listTools(ordinary),
                "An ordinary user was offered a tool they cannot use.",
            )
            assertTrue(
                "send_email" in client.listTools(admin),
                "An admin was not offered send_email at all.",
            )
            // The filter must take exactly the admin-gated tools away, not narrow
            // the surface any further. Since LNL-78 that is send_email AND the
            // forum tools, which are gated the same way — and update_history_event,
            // which is whole-tool admin-only for the same reason. So the two lists
            // differ by exactly that set and nothing else. A build that dropped an
            // ordinary tool for an ordinary user would fail here.
            assertEquals(
                client.listTools(admin) - "send_email" - "update_history_event" -
                    "delete_attachment" - forumToolNames,
                client.listTools(ordinary),
                "Filtering the admin-only tools changed something else about the tool list.",
            )
        }
    }

    /**
     * The instructions do not describe a tool the caller was not offered.
     *
     * Otherwise a non-admin's model reads a paragraph about mailing the user, and
     * its options are to hallucinate the call or to promise something it cannot
     * do. Both are worse than not knowing.
     */
    @Test
    fun `the e-mail paragraph reaches an admin's instructions only`(): Unit = runBlocking {
        val fixture = seed()

        withMcp { client ->
            assertTrue(
                !client.instructions(tokenFor(fixture.ordinaryId)).contains("send_email"),
                "An ordinary user was told about a tool that is not in their list.",
            )
            assertTrue(
                client.instructions(tokenFor(fixture.adminId)).contains("send_email"),
                "An admin was offered send_email with no orientation on using it.",
            )
        }
    }

    // ── The refusals, each leaving a different next move ─────────────────────

    /**
     * No address on the account: refused, and the refusal says who fixes it.
     *
     * The user, in the web app — not the agent, and not by retrying. The admin's
     * own address is cleared rather than a second user being seeded, because
     * since LNL-67 an addressless *non*-admin would be refused one check earlier
     * and this case would never be reached.
     */
    @Test
    fun `a user with no e-mail address is refused, and told what would fix it`(): Unit = runBlocking {
        val fixture = seed()
        users.setEmail(fixture.adminId, null, isVerified = false)
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            val result = client.callTool(token, "send_email", """{"subject":"Hi","body":"There"}""")
            assertTrue(result.isError, "A user with no address was allowed to send.")
            assertTrue(
                result.text.contains("no e-mail address on your Lunicle account"),
                "The refusal did not name the missing address. Got: ${result.text}",
            )
        }
        assertTrue(sent.isEmpty(), "A message was sent for a user with no address.")
    }

    /**
     * No mail configured on this deployment: refused, and not silently logged.
     *
     * The local-dev state, and the one place this deliberately diverges from
     * NotificationService — which logs and moves on, because its send rides on a
     * write that already happened. Here the send *is* the task, so an agent must
     * not be able to report success for a message that never existed.
     */
    @Test
    fun `a server with no mail configured refuses rather than pretending`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp(sender = null) { client ->
            val result = client.callTool(token, "send_email", """{"subject":"Hi","body":"There"}""")
            assertTrue(result.isError, "A server with no sender claimed to have sent something.")
            assertTrue(
                result.text.contains("no e-mail configured"),
                "The refusal did not say the server cannot send. Got: ${result.text}",
            )
        }
    }

    /** A subject or a body is not optional, and neither refusal sends anything. */
    @Test
    fun `an empty subject or body is refused`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            assertTrue(
                client.callTool(token, "send_email", """{"body":"There"}""").isError,
                "A message with no subject was sent.",
            )
            assertTrue(
                client.callTool(token, "send_email", """{"subject":"Hi"}""").isError,
                "A message with no body was sent.",
            )
        }
        assertTrue(sent.isEmpty(), "A refused send left the building anyway.")
    }

    /**
     * The bounds exist so a looping model cannot produce an unopenable message.
     *
     * Refused before the send rather than truncated, for [resolveAgentName]'s
     * reason: an agent can act on a refusal and cannot see a truncation.
     */
    @Test
    fun `an over-long subject or body is refused before anything is sent`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            val longSubject = client.callTool(
                token,
                "send_email",
                """{"subject":"${"x".repeat(300)}","body":"There"}""",
            )
            assertTrue(longSubject.isError, "A 300-character subject was accepted.")
            assertTrue(longSubject.text.contains("too long"), "The refusal must say what was wrong: ${longSubject.text}")

            val longBody = client.callTool(
                token,
                "send_email",
                """{"subject":"Hi","body":"${"x".repeat(20_001)}"}""",
            )
            assertTrue(longBody.isError, "A 20,001-character body was accepted.")
        }
        assertTrue(sent.isEmpty(), "A refused send left the building anyway.")
    }

    /**
     * A provider that refuses is reported to the agent, not swallowed.
     *
     * The agent's whole task was to send this; "done" would be a lie. The
     * provider's own words stay off this surface, exactly as they stay out of the
     * UI — see ResendEmailTransport.
     */
    @Test
    fun `a send the provider refuses comes back as an error`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp(sender = refusingSender()) { client ->
            val result = client.callTool(token, "send_email", """{"subject":"Hi","body":"There"}""")
            assertTrue(result.isError, "A refused send was reported as a success.")
            assertTrue(
                !result.text.contains("domain_not_verified"),
                "The provider's raw error reached the agent: ${result.text}",
            )
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val adminId: Long, val ordinaryId: Long, val projectId: Long)

    /**
     * The instance admin — who is the only one who can send — and an ordinary
     * user, who is the refusal.
     *
     * Both assertions below are guards on the fixture rather than on the code:
     * "the first user upserted becomes the instance admin" is the seeding rule
     * everything here rests on, and a test that quietly seeded two admins would
     * pass the refusal test by not exercising it.
     */
    private suspend fun seed(): Fixture {
        roles.seed()
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin", "Admin", "admin@example.com"))
        assertTrue(admin.isSysAdmin, "The fixture's sender is not the instance admin.")
        val ordinary = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", "ordinary@example.com"),
        )
        assertTrue(!ordinary.isSysAdmin, "The fixture's ordinary user is somehow an admin.")
        val project = projectRepository.create("Lunamux", "LMX", isPublic = false)
        return Fixture(admin.id, ordinary.id, project.id)
    }

    /** A real access token for [userId], with MCP enabled — see McpAgentNameTest.tokenFor. */
    private suspend fun tokenFor(userId: Long): String {
        users.setMcpAllowed(userId, true)
        users.setMcpEnabled(userId, true)
        val client = clients.register("Test agent", listOf("http://localhost:1234/callback"), listOf("authorization_code"))
        return tokens.issueTokens(userId, client.clientId, "mcp", "http://localhost/mcp").accessToken
    }

    /**
     * A real [ResendEmailTransport] whose transport answers from a lambda: the request is
     * recorded and a 200 returned, so everything up to the socket runs for real.
     */
    private fun capturingSender(): ResendEmailTransport = ResendEmailTransport(
        config = ResendConfig(apiKey = "test-key", from = "Lunicle <noreply@example.com>"),
        httpClient = HttpClient(
            MockEngine { request ->
                val payload = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
                sent += SentMail(
                    to = payload["to"]!!.jsonArray.map { it.jsonPrimitive.content },
                    subject = payload["subject"]!!.jsonPrimitive.content,
                    html = payload["html"]!!.jsonPrimitive.content,
                )
                respond(
                    content = """{"id":"test"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ClientContentNegotiation) { json() }
        },
    )

    /** A sender whose provider says no, for the failure path. */
    private fun refusingSender(): ResendEmailTransport = ResendEmailTransport(
        config = ResendConfig(apiKey = "test-key", from = "Lunicle <noreply@example.com>"),
        httpClient = HttpClient(
            MockEngine {
                respond(
                    content = """{"name":"domain_not_verified"}""",
                    status = HttpStatusCode.Forbidden,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ClientContentNegotiation) { json() }
        },
    )

    /** Mount the real `/mcp` and hand back a client — see McpAgentNameTest.withMcp. */
    private fun withMcp(
        sender: ResendEmailTransport? = capturingSender(),
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing { mcpRoutes(mcpDependencies(), McpTools(boardDependencies(sender))) }
        }
        block(createClient { })
    }

    private class ToolOutcome(val text: String, val isError: Boolean)

    /** Call one tool over JSON-RPC and unwrap the result — see McpAgentNameTest.callTool. */
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
        val result = assertNotNull(body["result"], "No result in $body").jsonObject
        val text = result["content"]!!.jsonArray
            .joinToString("\n") { (it as JsonObject)["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        return ToolOutcome(text, result["isError"]?.jsonPrimitive?.contentOrNull == "true")
    }

    /**
     * The forum tools, admin-gated the same way `send_email` is (LNL-78).
     *
     * Named here rather than reached from `McpTools`' private set so the test
     * states its own expectation — a build that renamed one there would fail the
     * assertion above rather than silently agree with itself. `McpForumTest`
     * pins the fuller behaviour; this only needs the count right.
     */
    private val forumToolNames = listOf(
        "list_forums", "create_forum", "update_forum", "delete_forum", "reorder_forums",
        "list_forum_posts", "get_forum_post", "create_forum_post", "update_forum_post",
        "delete_forum_post", "create_forum_comment", "update_forum_comment", "delete_forum_comment",
        "watch_forum", "watch_forum_post",
    )

    /** The names of the tools this token is offered, in the order they are listed. */
    private suspend fun HttpClient.listTools(token: String): List<String> =
        rpc(token, """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")["tools"]!!
            .jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    /** The orientation text this token gets in `initialize`. */
    private suspend fun HttpClient.instructions(token: String): String =
        rpc(token, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")["instructions"]
            ?.jsonPrimitive?.contentOrNull.orEmpty()

    /** One JSON-RPC round trip, unwrapped to its `result`. */
    private suspend fun HttpClient.rpc(token: String, body: String): JsonObject {
        val response = post(MCP_PATH) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val parsed = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(parsed["error"] == null, "JSON-RPC error rather than a result: $parsed")
        return assertNotNull(parsed["result"], "No result in $parsed").jsonObject
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

    private fun boardDependencies(sender: ResendEmailTransport? = null) = BoardDependencies(
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
        agentMail = sender,
    )
}
