/**
 * A status's `unblocks` flag: an issue in such a column stops blocking the issues
 * that wait on it, without being closed.
 *
 * ── What is pinned here, and why both readers ───────────────────────────────
 *
 * The blocked projection is computed twice — once for the web board
 * ([buildBoard]) and once for MCP's get_board — over the same stores and through
 * the one [StatusRecord.stopsBlocking]. Two readers of one rule are exactly where a
 * rule drifts, so each is asserted on its own: a build that taught only one of them
 * the flag would leave the web board and an agent disagreeing about whether a card
 * may be started, which nothing on either screen would explain.
 *
 * The three facts per reader:
 *
 *  - a blocker in an ordinary column blocks — the baseline, so the next two cannot
 *    pass vacuously;
 *  - a blocker in an `unblocks` column does not;
 *  - a blocker in a CLOSING column does not, without the flag — the rule is
 *    "requires_resolution OR unblocks", and a project must not have to tick a second
 *    box on Closed to keep the behaviour it always had.
 *
 * And the MCP vocabulary tools round-trip the flag: add_vocabulary takes it,
 * list_vocabulary and get_board report it, and rename_vocabulary changes it only
 * when asked — an omitted flag on a rename means "leave it", never "false".
 *
 * Driven through the real `/mcp` endpoint for [McpBoardFilterTest]'s reason.
 *
 * @see Statuses.sq
 * @see StatusRecord.stopsBlocking
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.store.InstanceSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class UnblockingStatusTest {
    private val file: File = Files.createTempFile("lunicle-unblocks", ".db").toFile().also { it.delete() }
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
    private val relations = IssueRelationStore(database)
    private val relationKinds = IssueRelationKindStore(database, relations)
    private val issueRepository = IssueRepository(
        issues, comments, statuses, priorities, attachments, attachmentStore,
        relations = relations, relationKinds = relationKinds,
    )
    private val sprintRepository = SprintRepository(database, sprints, projects, issues, statuses)
    private val vocabularies = VocabularyRepository(
        database, labels, components, statuses, priorities, resolutions, sprints, versions,
        issues = issues, relations = relations, relationKinds = relationKinds,
    )
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

    // ── The web board's projection ───────────────────────────────────────────

    @Test
    fun `the web board stops counting a blocker once it sits in an unblocking column`(): Unit = runBlocking {
        val f = seed()
        val deps = boardDependencies()
        fun blocked() = runBlocking {
            deps.buildBoard(projects.findById(f.projectId)!!, users.findById(f.ownerId))
                .issues.single { it.id == f.dependentId }
        }

        assertTrue(blocked().isBlocked, "the baseline: a blocker in progress blocks")

        moveTo(f, f.blockerId, "Ready for test")
        assertTrue(blocked().isBlocked, "an ordinary Ready for test still blocks — the flag is off by default")

        armUnblocks(f, "Ready for test")
        assertFalse(blocked().isBlocked, "the same column, armed, no longer blocks")
        assertTrue(blocked().blockedByNumbers.isEmpty(), "and names no blocker")

        val board = deps.buildBoard(projects.findById(f.projectId)!!, users.findById(f.ownerId))
        assertTrue(board.statuses.single { it.name == "Ready for test" }.unblocks, "the column says so on the wire")
        assertFalse(board.statuses.single { it.name == "Closed" }.unblocks, "and Closed is not armed by the seed")
    }

    @Test
    fun `a closing column unblocks without the flag`(): Unit = runBlocking {
        val f = seed()
        moveTo(f, f.blockerId, "Closed")
        val card = boardDependencies()
            .buildBoard(projects.findById(f.projectId)!!, users.findById(f.ownerId))
            .issues.single { it.id == f.dependentId }
        assertFalse(card.isBlocked, "requires_resolution alone stops the blocking, as it always did")
    }

    // ── MCP get_board ────────────────────────────────────────────────────────

    @Test
    fun `get_board agrees with the web board about an unblocking column`(): Unit = runBlocking {
        val f = seed()
        assertTrue(dependentOnMcp(f).containsKey("isBlocked"), "the baseline: blocked while in progress")

        moveTo(f, f.blockerId, "Ready for test")
        assertTrue(dependentOnMcp(f).containsKey("isBlocked"), "still blocked in an unarmed column")

        armUnblocks(f, "Ready for test")
        val card = dependentOnMcp(f)
        assertFalse(card.containsKey("isBlocked"), "no longer blocked once the column unblocks: $card")
        assertFalse(card.containsKey("blockedBy"))

        val columns = getBoard(f)["statuses"]!!.jsonArray.associate {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["unblocks"]!!.jsonPrimitive.boolean
        }
        assertEquals(true, columns["Ready for test"], "get_board reports the flag per status")
        assertEquals(false, columns["In progress"], "and reports it off everywhere else, rather than omitting it")
    }

    // ── MCP vocabulary tools ─────────────────────────────────────────────────

    @Test
    fun `the flag round-trips through add, list and rename_vocabulary`(): Unit = runBlocking {
        val f = seed()
        val token = tokenFor(f.ownerId)
        withMcp { client ->
            val added = client.callTool(
                token, "add_vocabulary",
                """{"project_id":${f.projectId},"kind":"status","name":"Ready for review","unblocks":true}""",
            )
            assertFalse(added.isError, added.text)
            val plain = client.callTool(
                token, "add_vocabulary", """{"project_id":${f.projectId},"kind":"status","name":"Parked"}""",
            )
            assertFalse(plain.isError, plain.text)

            fun listed(name: String): JsonObject = runBlocking {
                val list = client.callTool(token, "list_vocabulary", """{"project_id":${f.projectId},"kind":"status"}""")
                assertFalse(list.isError, list.text)
                Json.parseToJsonElement(list.text).jsonObject["vocabularies"]!!.jsonObject["status"]!!.jsonArray
                    .map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == name }
            }
            assertEquals(true, listed("Ready for review")["unblocks"]!!.jsonPrimitive.boolean, "add took the flag")
            assertEquals(false, listed("Parked")["unblocks"]!!.jsonPrimitive.boolean, "and leaves it off unless sent")
            val id = listed("Ready for review")["id"]!!.jsonPrimitive.content

            // A rename that does not mention the flag must leave it alone — the write
            // underneath sets every flag at once, so this is the reconciliation that
            // matters.
            val renamed = client.callTool(
                token, "rename_vocabulary",
                """{"project_id":${f.projectId},"kind":"status","id":$id,"name":"In review"}""",
            )
            assertFalse(renamed.isError, renamed.text)
            assertEquals(true, listed("In review")["unblocks"]!!.jsonPrimitive.boolean, "an omitted flag is kept")

            val cleared = client.callTool(
                token, "rename_vocabulary",
                """{"project_id":${f.projectId},"kind":"status","id":$id,"unblocks":false}""",
            )
            assertFalse(cleared.isError, cleared.text)
            val after = listed("In review")
            assertEquals(false, after["unblocks"]!!.jsonPrimitive.boolean, "a stated flag is written")
            assertEquals(false, after["requiresResolution"]!!.jsonPrimitive.boolean, "and the closing flag untouched")
        }
    }

    @Test
    fun `the settings rename carries the flag and other kinds ignore it`(): Unit = runBlocking {
        val f = seed()
        val row = vocabularies.rows(f.projectId, VocabularyKind.STATUS).single { it.name == "Backlog" }
        vocabularies.rename(
            f.projectId, VocabularyKind.STATUS, row, "Backlog",
            requiresResolution = false, isDone = false, unblocks = true,
        )
        assertTrue(vocabularies.rows(f.projectId, VocabularyKind.STATUS).single { it.id == row.id }.unblocks)

        // A priority has nowhere to put it, and is not refused for being sent one —
        // the dialog sends back the row it is rendering.
        val priority = vocabularies.rows(f.projectId, VocabularyKind.PRIORITY).first()
        vocabularies.rename(
            f.projectId, VocabularyKind.PRIORITY, priority, priority.name,
            requiresResolution = false, isDone = false, unblocks = true,
        )
        assertFalse(vocabularies.rows(f.projectId, VocabularyKind.PRIORITY).single { it.id == priority.id }.unblocks)
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val ownerId: Long, val projectId: Long, val dependentId: Long, val blockerId: Long)

    /** A project where "Dependent" is blocked by "Blocker", which is in progress. */
    private suspend fun seed(): Fixture {
        val owner = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-owner", "Owner", "owner@example.com"))
        val project = projectRepository.create("Lunamux", "LMX")
        seatInstanceOwner(users, instanceSettings)
        val dependent = publish(project.id, owner.id, "Dependent", "In progress")
        val blocker = publish(project.id, owner.id, "Blocker", "In progress")
        val blockedBy = relationKinds.forProject(project.id).single { it.marksBlocked }
        issueRepository.addRelation(issues.findById(dependent)!!, blocker, blockedBy.id).getOrThrow()
        return Fixture(owner.id, project.id, dependent, blocker)
    }

    private suspend fun publish(projectId: Long, authorId: Long, title: String, status: String): Long {
        val created = issueRepository.createDraft(projectId, Author.Account(authorId))
        save(issues.findById(created.first)!!, title, status)
        return created.first
    }

    private suspend fun moveTo(f: Fixture, issueId: Long, status: String) {
        val issue = issues.findById(issueId)!!
        save(issue, issue.title, status)
    }

    private suspend fun save(issue: IssueRecord, title: String, status: String) {
        val column = statuses.forProject(issue.projectId).single { it.name == status }
        issueRepository.save(
            issue = issue,
            title = title,
            description = "",
            statusId = column.id,
            priorityId = priorities.defaultForProject(issue.projectId)!!.id,
            resolutionId = if (column.requiresResolution) resolutions.forProject(issue.projectId).first().id else null,
            assigneeId = null,
            sprintId = null,
            plannedVersionId = null,
            fixedVersionId = null,
            labelIds = emptyList(),
            componentIds = emptyList(),
        )
    }

    private suspend fun armUnblocks(f: Fixture, status: String) {
        val column = statuses.forProject(f.projectId).single { it.name == status }
        statuses.update(column.id, column.name, column.requiresResolution, unblocks = true)
    }

    private suspend fun getBoard(f: Fixture): JsonObject {
        var board: JsonObject? = null
        withMcp { client ->
            val result = client.callTool(tokenFor(f.ownerId), "get_board", """{"project_id":${f.projectId}}""")
            assertFalse(result.isError, "get_board was refused: ${result.text}")
            board = Json.parseToJsonElement(result.text).jsonObject
        }
        return assertNotNull(board)
    }

    private suspend fun dependentOnMcp(f: Fixture): JsonObject =
        getBoard(f)["issues"]!!.jsonArray.map { it.jsonObject }
            .single { it["title"]!!.jsonPrimitive.content == "Dependent" }

    /** A real access token for [userId], with MCP enabled — see McpAgentNameTest.tokenFor. */
    private suspend fun tokenFor(userId: Long): String {
        users.setMcpEnabled(userId, true)
        val client = clients.register("Test agent", listOf("http://localhost:1234/callback"), listOf("authorization_code"))
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
        val result = assertNotNull(body["result"], "No result in $body").jsonObject
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
        subscriptions = SubscriptionStore(database),
        reads = ReadStore(database),
        issueRelations = relations,
        issueRelationKinds = relationKinds,
    )
}
