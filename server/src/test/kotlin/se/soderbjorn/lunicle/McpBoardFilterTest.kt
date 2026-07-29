/**
 * `get_board`'s optional `status` — one column instead of the whole board.
 *
 * ── Why this is its own file ─────────────────────────────────────────────────
 *
 * The parameter exists for a caller that does not want the board: an automation
 * that wakes on a timer, asks what is sitting in one column, and does something
 * about it. On a board of any age the full answer is large enough to be awkward
 * to read in one piece, and every byte past the one column is waste on every
 * tick. So the claims here are about what a *narrowing* call must still be:
 *
 *  - **It narrows the issues and nothing else.** The vocabulary is the reason
 *    get_board exists — it is the only call that says what this project's
 *    statuses, priorities, labels and components are called — and a caller that
 *    filtered would otherwise have to make a second, unfiltered call to learn
 *    them, which is the round-trip the filter was meant to save. A build that
 *    narrowed the vocabulary alongside the issues would look correct on the
 *    issues assertion and quietly break every caller that reads a name from it.
 *  - **An unknown column is refused, not answered with nothing.** The two are
 *    indistinguishable to the caller — both are an empty `issues` array — and
 *    they mean opposite things: "there is no work" versus "you misspelled the
 *    column". An automation that cannot tell them apart goes quietly idle
 *    forever. The refusal must also name the columns that do exist, so the
 *    recovery is not another round-trip.
 *  - **Omitting it is unchanged.** The parameter is additive; every existing
 *    caller sends no `status` and must still get the whole board.
 *
 * Driven through the real `/mcp` endpoint with real tokens, for McpAgentNameTest's
 * reason: the claim is about a parameter an agent sends over the wire.
 *
 * @see McpTools.getBoard
 * @see McpAgentNameTest
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
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import se.soderbjorn.lunicle.store.InstanceSettings

class McpBoardFilterTest {
    private val file: File = Files.createTempFile("lunicle-board-filter", ".db").toFile().also { it.delete() }
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
    // Agent access is permitted per tier and defaults to off (LNL-192). These files
    // are about what an agent may *do*, not about who may bring one, so both tiers are
    // permitted here and the user's own switch stays the interesting half.
    private val instanceSettings = InMemoryInstanceSettingsStore(
        InstanceSettings(staffMayUseAgents = true, memberMayUseAgents = true),
    )
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

    // ── It narrows the issues ────────────────────────────────────────────────

    /**
     * The test this file exists for: one column back, not the board.
     *
     * Parsed rather than substring-matched — the other two issues' titles would
     * be absent from a correct answer and present in a wrong one, but a
     * substring check would also pass on a build that put them under some other
     * key. The assertion is on the `issues` array itself.
     */
    @Test
    fun `a status filter returns only that column`(): Unit = runBlocking {
        val fixture = seed()
        publish(fixture, "Waiting", "Backlog")
        publish(fixture, "Being worked on", "In progress")
        publish(fixture, "Also waiting", "Backlog")

        val board = getBoard(fixture, """"status":"Backlog"""")

        assertEquals(
            listOf("Also waiting", "Waiting"),
            board.titles().sorted(),
            "The filter did not return exactly the Backlog column.",
        )
    }

    /**
     * The vocabulary survives the filter.
     *
     * The load-bearing half of the feature. get_board is the only call that says
     * what this project's columns and priorities are called, so a filtered call
     * that also trimmed the vocabulary would force the second call it was meant
     * to save. Every list is asserted, not just statuses, because the plausible
     * slip — narrowing "everything about the board" rather than "the issues" —
     * takes them all out together.
     */
    @Test
    fun `a filtered board still carries the whole vocabulary`(): Unit = runBlocking {
        val fixture = seed()
        publish(fixture, "Waiting", "Backlog")

        val board = getBoard(fixture, """"status":"Backlog"""")

        assertEquals(
            listOf("New", "Backlog", "Ready for development", "In progress", "Ready for test", "Closed"),
            board["statuses"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content },
            "The filter narrowed the statuses as well as the issues.",
        )
        assertTrue(
            board["priorities"]!!.jsonArray.isNotEmpty(),
            "A filtered board lost its priorities, so a caller cannot order what it got back.",
        )
        assertEquals(
            fixture.projectId,
            board["project"]!!.jsonObject["id"]!!.jsonPrimitive.content.toLong(),
            "A filtered board lost its project header.",
        )
    }

    /**
     * Case-insensitive, for [McpTools.resolveVocabulary]'s reason.
     *
     * An agent that read "Backlog" off a board and sent "backlog" is being
     * reasonable; refusing it would be pedantry that costs a round-trip. Pinned
     * here because this filter resolves the name itself rather than going
     * through the shared helper.
     */
    @Test
    fun `the status name is matched case-insensitively`(): Unit = runBlocking {
        val fixture = seed()
        publish(fixture, "Waiting", "Backlog")

        assertEquals(listOf("Waiting"), getBoard(fixture, """"status":"bAcKlOg"""").titles())
    }

    /** An empty column is an empty array, not a refusal — there is simply no work there. */
    @Test
    fun `a real but empty column comes back empty rather than refused`(): Unit = runBlocking {
        val fixture = seed()
        publish(fixture, "Waiting", "Backlog")

        assertEquals(emptyList(), getBoard(fixture, """"status":"Closed"""").titles())
    }

    // ── An unknown column is refused, with the list ──────────────────────────

    /**
     * The distinction the whole feature turns on: misspelled is not empty.
     *
     * A build that ignored an unresolvable name would answer with the whole
     * board, and one that filtered on a null id would answer with nothing —
     * both indistinguishable, to the caller, from a column that is genuinely
     * quiet. An automation polling on a timer would then either work every
     * ticket at once or go silently idle forever. The refusal must also name the
     * real columns, so recovery does not need another call.
     */
    @Test
    fun `an unknown status is refused and names the real columns`(): Unit = runBlocking {
        val fixture = seed()
        publish(fixture, "Waiting", "Backlog")

        withMcp { client ->
            val result = client.callTool(
                tokenFor(fixture.ordinaryId),
                "get_board",
                """{"project_id":${fixture.projectId},"status":"Ready for AI development"}""",
            )
            assertTrue(result.isError, "A column this project does not have was accepted.")
            assertTrue(
                result.text.contains("Ready for development"),
                "The refusal must list the columns that do exist. Got: ${result.text}",
            )
        }
    }

    // ── Omitting it is unchanged ─────────────────────────────────────────────

    /**
     * The regression guard: the parameter is additive.
     *
     * Every caller written before this existed sends no `status`, and the answer
     * they get must not have changed. Cheap to assert and the one failure that
     * would break everything else at once.
     */
    @Test
    fun `omitting the status returns the whole board`(): Unit = runBlocking {
        val fixture = seed()
        publish(fixture, "Waiting", "Backlog")
        publish(fixture, "Being worked on", "In progress")

        assertEquals(
            listOf("Being worked on", "Waiting"),
            getBoard(fixture, null).titles().sorted(),
            "An unfiltered board no longer returns every issue.",
        )
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val adminId: Long, val ordinaryId: Long, val projectId: Long)

    /** As McpAgentNameTest.seed: the instance admin, one ordinary filer, and a project. */
    private suspend fun seed(): Fixture {
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin", "Admin", "admin@example.com"))
        val ordinary = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", "ordinary@example.com"),
        )
        val project = projectRepository.create("Lunamux", "LMX")
        roles.setRole(ordinary.id, project.id, ProjectRole.CONTRIBUTOR)
        // Production seats the instance owner at boot (see InstanceLadder.kt), and
        // four rules — creating and managing projects, backfilling authorship, agent
        // mail, out-of-band attachment deletes — are the owner's alone rather than an
        // administrator's. A fixture that skipped this would be testing an instance
        // nobody runs: one with an administrator and no owner.
        seatInstanceOwner(users, instanceSettings)
        return Fixture(admin.id, ordinary.id, project.id)
    }

    /** A published issue with [title], parked in the column called [status]. */
    private suspend fun publish(fixture: Fixture, title: String, status: String) {
        val columns = statuses.forProject(fixture.projectId)
        val column = assertNotNull(
            columns.firstOrNull { it.name == status },
            "The fixture project has no \"$status\" column; it has ${columns.map { it.name }}.",
        )
        val created = issueRepository.createDraft(fixture.projectId, Author.Account(fixture.adminId))
        issueRepository.save(
            issue = issues.findById(created.first)!!,
            title = title,
            description = "",
            statusId = column.id,
            priorityId = priorities.defaultForProject(fixture.projectId)!!.id,
            resolutionId = null,
            assigneeId = null,
            sprintId = null,
            plannedVersionId = null,
            fixedVersionId = null,
            labelIds = emptyList(),
            componentIds = emptyList(),
        )
    }

    /** Call `get_board` with [extraArguments] appended, and parse the board it returns. */
    private suspend fun getBoard(fixture: Fixture, extraArguments: String?): JsonObject {
        var board: JsonObject? = null
        withMcp { client ->
            val arguments = listOfNotNull(""""project_id":${fixture.projectId}""", extraArguments).joinToString(",")
            val result = client.callTool(tokenFor(fixture.ordinaryId), "get_board", "{$arguments}")
            assertTrue(!result.isError, "get_board was refused: ${result.text}")
            board = Json.parseToJsonElement(result.text).jsonObject
        }
        return assertNotNull(board, "get_board returned no board.")
    }

    /** The titles on a board, in the order it listed them. */
    private fun JsonObject.titles(): List<String> =
        this["issues"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }

    /** A real access token for [userId], with MCP enabled — see McpAgentNameTest.tokenFor. */
    private suspend fun tokenFor(userId: Long): String {
        users.setMcpEnabled(userId, true)
        val client = clients.register("Test agent", listOf("http://localhost:1234/callback"), listOf("authorization_code"))
        return tokens.issueTokens(userId, client.clientId, "mcp", "http://localhost/mcp").accessToken
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

    /** Mount the real `/mcp` and hand back a client — see McpAgentNameTest.withMcp. */
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
        instanceSettings = instanceSettings,
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
