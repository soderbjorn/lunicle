/**
 * The optional `agent_name` on `create_issue` and `add_comment`.
 *
 * ── Why this is its own file, and not part of McpBackfillTest ────────────────
 *
 * It sits on the same two tools as `author` and `created_at`, and reads almost
 * like a third of them — but it is the opposite kind of thing, and the tests are
 * here to hold that difference in place:
 *
 *  - **Backfill is admin-only and exceptional; agent_name is nobody's privilege
 *    and the norm.** `author` lets an admin write history under a name that is not
 *    the token's; `agent_name` lets any caller truthfully label the row with the
 *    name of the agent that wrote it. So the load-bearing assertion here is the
 *    inverse of that file's: an *ordinary* user sets it and is NOT refused. A
 *    build that copied the backfill gate onto this parameter would pass every
 *    McpBackfillTest and fail the whole point of this one.
 *  - **It is orthogonal to the author, not a fourth value of it.** The issue is
 *    still the token user's; `agent_name` only records that an agent held the pen.
 *    A build that stored the agent name *as* the author — an easy slip, since they
 *    arrive together — would put the right string on the board for the wrong
 *    reason, and `canEditIssue` would then key off a name instead of an account.
 *    So the orthogonality test asserts BOTH columns at once.
 *  - **Absent is the documented override, not a bug.** The instructions tell an
 *    agent to send it normally and omit it only when asked to act purely as the
 *    user. So "omitted" must produce a clean row with no badge, on the read side
 *    too — not a null that leaks into the JSON as `"agentName": null`.
 *
 * Through the real `/mcp` endpoint with real tokens, for McpBackfillTest's reason:
 * the claim is about a parameter an agent sends over the wire, so it is tested
 * over the wire.
 *
 * @see McpTools.resolveAgentName
 * @see McpBackfillTest
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import se.soderbjorn.lunicle.clientserver.AuthProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class McpAgentNameTest {
    private val file: File = Files.createTempFile("lunicle-agent", ".db").toFile().also { it.delete() }
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
    private val instanceSettings = InMemoryInstanceSettingsStore()
    private val access = AccessControl(roles, instanceSettings)

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

    // ── An ordinary agent names itself, and is not refused ───────────────────

    /**
     * The test this file exists for: setting `agent_name` is not a privilege.
     *
     * An ordinary user's token, no admin anywhere, and the write lands. The
     * failure being guarded is the mirror image of McpBackfillTest's first test —
     * a build that treated `agent_name` like `author` and refused a non-admin for
     * asking, which would turn the encouraged default into an error for everyone.
     */
    @Test
    fun `an ordinary agent can name itself without being an admin`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Filed by a bot","agent_name":"Acme Assistant"}""",
            )
            assertTrue(!result.isError, "An ordinary agent naming itself was refused: ${result.text}")
        }

        val issue = issues.forProject(fixture.projectId).single()
        assertEquals("Acme Assistant", issue.agentName, "The agent name was not stored.")
    }

    /**
     * Orthogonal to the author, not instead of it.
     *
     * Both columns asserted in one test, because the slip this guards writes the
     * right string to the wrong field: the issue must still belong to the token's
     * own account — so `canEditIssue` keeps working — while carrying the agent's
     * name beside it. A build that stored the name as an [Author.External] would
     * satisfy a test that only read `agentName`, and quietly unown the issue.
     */
    @Test
    fun `the agent name rides alongside the author, not in place of it`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Ours","agent_name":"Acme Assistant"}""",
            )
        }

        val issue = issues.forProject(fixture.projectId).single()
        assertEquals(Author.Account(fixture.ordinaryId), issue.author, "The agent name displaced the real author.")
        assertEquals("Acme Assistant", issue.agentName)
    }

    /** The comment half, which writes through a different store and was as easy to forget. */
    @Test
    fun `a comment carries its agent name`(): Unit = runBlocking {
        val fixture = seed()
        val (issueId, _) = published(fixture)
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "add_comment",
                """{"issue_id":$issueId,"body":"On it","agent_name":"Acme Assistant"}""",
            )
            assertTrue(!result.isError, result.text)
        }

        val comment = comments.forIssue(issueId).single()
        assertEquals(Author.Account(fixture.ordinaryId), comment.author)
        assertEquals("Acme Assistant", comment.agentName)
    }

    // ── The name reaches a reader ────────────────────────────────────────────

    /**
     * The agent name on both read surfaces, for the issue and its comment.
     *
     * The point of storing it — a name written and then never shown is the board
     * this feature was meant to improve, unchanged. Parsed rather than
     * substring-matched: the tools pretty-print, so the string appears in the
     * output whether or not it is on the right key.
     */
    @Test
    fun `the agent name is rendered over MCP on both surfaces`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Filed by a bot","agent_name":"Acme Assistant"}""",
            )
            val issueId = issues.forProject(fixture.projectId).single().id
            client.callTool(token, "add_comment", """{"issue_id":$issueId,"body":"Reply","agent_name":"Acme Assistant"}""")

            val board = client.callTool(token, "get_board", """{"project_id":${fixture.projectId}}""")
            val card = Json.parseToJsonElement(board.text).jsonObject["issues"]!!.jsonArray.single().jsonObject
            assertEquals(
                "Acme Assistant",
                card["agentName"]?.jsonPrimitive?.contentOrNull,
                "get_board did not report the agent name.",
            )

            val detail = Json.parseToJsonElement(
                client.callTool(token, "get_issue", """{"issue_id":$issueId}""").text,
            ).jsonObject
            assertEquals(
                "Acme Assistant",
                detail["agentName"]?.jsonPrimitive?.contentOrNull,
                "get_issue did not report the agent name.",
            )
            assertEquals(
                "Acme Assistant",
                detail["comments"]!!.jsonArray.single().jsonObject["agentName"]?.jsonPrimitive?.contentOrNull,
                "the comment did not report its agent name.",
            )
        }
    }

    // ── The override: omitting it ────────────────────────────────────────────

    /**
     * No `agent_name` is the documented way to file as the user with no badge.
     *
     * Asserted on the stored row AND the read surface: an omitted name must be a
     * clean null, and it must not surface as `"agentName": null` in the JSON —
     * the read path uses a conditional put precisely so a human-filed issue reads
     * the same as it did before this parameter existed.
     */
    @Test
    fun `omitting agent_name leaves the row and its output unmarked`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            client.callTool(token, "create_issue", """{"project_id":${fixture.projectId},"title":"By a human"}""")

            val issue = issues.forProject(fixture.projectId).single()
            assertNull(issue.agentName, "An issue with no agent_name still got one.")

            val card = Json.parseToJsonElement(
                client.callTool(token, "get_board", """{"project_id":${fixture.projectId}}""").text,
            ).jsonObject["issues"]!!.jsonArray.single().jsonObject
            assertTrue(
                "agentName" !in card,
                "A human-filed issue leaked an agentName key into get_board.",
            )
        }
    }

    /**
     * `null` and blank are absent, not a request.
     *
     * Models fill in every property a schema mentions; an explicit `null`, or a
     * name that is only spaces, is the same as saying nothing. Neither may be
     * stored as a badge, and neither may fail the write.
     */
    @Test
    fun `an explicit null or blank agent_name is treated as absent`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val nulled = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Nulled","agent_name":null}""",
            )
            assertTrue(!nulled.isError, "A spelled-out null agent_name was refused: ${nulled.text}")
            val blank = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Blank","agent_name":"   "}""",
            )
            assertTrue(!blank.isError, "A blank agent_name was refused: ${blank.text}")
        }

        assertTrue(
            issues.forProject(fixture.projectId).all { it.agentName == null },
            "A null or blank agent_name was stored as a name.",
        )
    }

    // ── Clearing a badge, admin-only ─────────────────────────────────────────

    /**
     * A system administrator can strip an agent badge with an empty agent_name.
     *
     * The case a migration forces: an issue imported under a placeholder was never
     * an agent's, and once reattributed to a real person it must not still read as
     * one the assistant filed. An absent agent_name would leave the badge, so
     * clearing has to be an explicit, separate act — an empty value — and this is
     * it. The issue's author is asserted too: clearing the badge does not touch who
     * owns the row.
     */
    @Test
    fun `an admin clears an agent badge with an empty agent_name`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Imported","agent_name":"Migrator"}""",
            )
            val issueId = issues.forProject(fixture.projectId).single().id
            assertEquals("Migrator", issues.findById(issueId)?.agentName, "The badge was not set to begin with.")

            val cleared = client.callTool(token, "update_issue", """{"issue_id":$issueId,"agent_name":""}""")
            assertTrue(!cleared.isError, "Clearing the badge was refused: ${cleared.text}")
        }

        val issue = issues.forProject(fixture.projectId).single()
        assertEquals(null, issue.agentName, "An empty agent_name did not clear the badge.")
        assertEquals(Author.Account(fixture.adminId), issue.author, "Clearing the badge changed who owns the issue.")
    }

    /**
     * A non-admin cannot clear a badge, and a refused attempt leaves it be.
     *
     * The gate is the point: the badge says an agent did this, and removing it
     * rewrites the record of who — an administrative act, like re-authoring. An
     * ordinary user who sends an empty agent_name is refused by name rather than
     * quietly left as-is, and the badge they tried to strip is exactly as it was.
     */
    @Test
    fun `a non-admin is refused when clearing a badge, and it is untouched`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Mine","agent_name":"Acme Assistant"}""",
            )
            val issueId = issues.forProject(fixture.projectId).single().id

            val refused = client.callTool(token, "update_issue", """{"issue_id":$issueId,"agent_name":""}""")
            assertTrue(refused.isError, "A non-admin cleared an agent badge.")
            assertTrue(
                refused.text.contains("system administrator"),
                "The refusal did not say who may do this. Got: ${refused.text}",
            )
        }

        assertEquals(
            "Acme Assistant",
            issues.forProject(fixture.projectId).single().agentName,
            "A refused clear still stripped the badge.",
        )
    }

    // ── A name, not a paragraph ──────────────────────────────────────────────

    /**
     * An over-long value is refused, and nothing is written.
     *
     * The field is a badge, and a model that mistook it for a description of what
     * it did would fill the byline with a paragraph. Refused before the write, so
     * the refusal is a fact the agent can act on rather than a truncated string it
     * cannot see was cut.
     */
    @Test
    fun `an over-long agent_name is refused and no issue is written`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)
        val tooLong = "x".repeat(200)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Wordy","agent_name":"$tooLong"}""",
            )
            assertTrue(result.isError, "A 200-character agent name was accepted.")
            assertTrue(result.text.contains("too long"), "The refusal must say what was wrong. Got: ${result.text}")
        }
        assertEquals(emptyList(), issues.forProject(fixture.projectId), "A refused create_issue wrote an issue anyway.")
    }

    // ── It composes with an admin backfill ───────────────────────────────────

    /**
     * An import can be attributed to a person AND marked as agent-run at once.
     *
     * The two features are independent by construction — different code, different
     * gates — and this pins that they do not interfere: an admin backfilling under
     * someone else's account may still stamp the row with the importing agent's
     * name. The author is the named account; the agent name is the tool that did
     * the importing.
     */
    @Test
    fun `an admin backfill can also carry an agent name`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Imported","author":"Ordinary","agent_name":"Migrator"}""",
            )
            assertTrue(!result.isError, "An admin backfill with an agent name was refused: ${result.text}")
        }

        val issue = issues.forProject(fixture.projectId).single()
        assertEquals(Author.Account(fixture.ordinaryId), issue.author, "The backfill author was lost.")
        assertEquals("Migrator", issue.agentName, "The agent name was lost when combined with a backfill.")
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val adminId: Long, val ordinaryId: Long, val projectId: Long)

    /** As McpBackfillTest.seed: the instance admin, one ordinary filer, and a project. */
    private suspend fun seed(): Fixture {
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin", "Admin", "admin@example.com"))
        val ordinary = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", "ordinary@example.com"),
        )
        assertTrue(!ordinary.isInstanceAdmin, "The fixture's second user is somehow an admin.")
        val project = projectRepository.create("Lunamux", "LMX")
        roles.setRole(ordinary.id, project.id, ProjectRole.CONTRIBUTOR)
        roles.setRole(ordinary.id, project.id, ProjectRole.CONTRIBUTOR)
        // Production seats the instance owner at boot (see InstanceLadder.kt), and
        // four rules — creating and managing projects, backfilling authorship, agent
        // mail, out-of-band attachment deletes — are the owner's alone rather than an
        // administrator's. A fixture that skipped this would be testing an instance
        // nobody runs: one with an administrator and no owner.
        seatInstanceOwner(users, instanceSettings)
        return Fixture(admin.id, ordinary.id, project.id)
    }

    /** A published issue to hang comments off. */
    private suspend fun published(fixture: Fixture): Pair<Long, Long> {
        val created = issueRepository.createDraft(fixture.projectId, Author.Account(fixture.adminId))
        val issue = issues.findById(created.first)!!
        issueRepository.save(
            issue = issue,
            title = "Something",
            description = "",
            statusId = statuses.forProject(fixture.projectId).first().id,
            priorityId = priorities.defaultForProject(fixture.projectId)!!.id,
            resolutionId = null,
            assigneeId = null,
            sprintId = null,
            plannedVersionId = null,
            fixedVersionId = null,
            labelIds = emptyList(),
            componentIds = emptyList(),
        )
        return created
    }

    /** A real access token for [userId], with MCP enabled — see McpBackfillTest.tokenFor. */
    private suspend fun tokenFor(userId: Long): String {
        // Both halves of canUseMcp — resolveMcpUser re-reads the pair on every
        // request, and a token whose owner is missing either is a 401 that would
        // fail every test here identically and for the wrong reason. mcp_allowed
        // is off by default (6.sqm ships it that way), so the permission has to be
        // granted explicitly; mcp_enabled is the user's own switch.
        users.setMcpEnabled(userId, true)
        val client = clients.register("Test agent", listOf("http://localhost:1234/callback"), listOf("authorization_code"))
        return tokens.issueTokens(userId, client.clientId, "mcp", "http://localhost/mcp").accessToken
    }

    private class ToolOutcome(val text: String, val isError: Boolean)

    /** Call one tool over JSON-RPC and unwrap the result — see McpBackfillTest.callTool. */
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

    /** Mount the real `/mcp` and hand back a client — see McpBackfillTest.withMcp. */
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
