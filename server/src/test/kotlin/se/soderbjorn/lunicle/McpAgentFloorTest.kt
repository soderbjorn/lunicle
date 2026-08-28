/**
 * The agent floor (LNL-217): an agent reaches a project only from
 * [AGENT_PROJECT_FLOOR] up.
 *
 * ── Why this is worth a file of its own ──────────────────────────────────────
 *
 * Every other MCP test asserts that an agent has *exactly* its user's rights, which is
 * the design: `McpServer.resolveMcpUser` produces the same [UserRecord] a cookie
 * produces and hands it to the same [AccessControl]. This is the one deliberate place
 * that is not true, so it is the one place a future change can quietly undo without
 * failing anything else — every assertion elsewhere goes on passing if the floor is
 * dropped, because dropping it only *widens* what an agent may do.
 *
 * The claims:
 *
 *  - **A project the person may only view is not merely read-only to their agent, it is
 *    absent.** That is the whole feature: a Viewer's agent could otherwise walk every
 *    board that person can look at — every public one on the deployment included — and
 *    pull the full text of every issue on it into a model's context. Nobody granting a
 *    look at a board meant that.
 *  - **Its refusal is indistinguishable from a project that does not exist.** Asserted
 *    as string equality against a made-up id rather than by matching a phrase, because
 *    the two answers agreeing is the claim. An agent that could tell "you are only a
 *    Viewer here" from "no such project" could enumerate every board on the deployment
 *    by name.
 *  - **An issue id is not a way round it.** Every issue tool resolves through
 *    `readableIssue`, and a build that floored `resolveProject` alone would leave
 *    `get_issue` open to anything whose id could be guessed or was seen once.
 *  - **An instance administrator and the owner clear it everywhere.** They typically
 *    hold no rung row at all, so a floor written against `project_roles` rather than
 *    through [AccessControl.effectiveRole] would strip exactly the two accounts it was
 *    never meant to touch. This is the assertion that pins the implementation shape.
 *  - **An audience row carries it.** A board admitting members as Contributors admits
 *    their agents, with no per-person grant.
 *
 * Driven through the real `/mcp` endpoint with real tokens, for McpBoardFilterTest's
 * reason: the claim is about what an agent gets back over the wire.
 *
 * @see AccessControl.canAgentReachProject
 * @see AGENT_PROJECT_FLOOR
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

class McpAgentFloorTest {
    private val file: File = Files.createTempFile("lunicle-agent-floor", ".db").toFile().also { it.delete() }
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
        VocabularyRepository(database, labels, components, statuses, priorities, resolutions, sprints, versions, issues = issues)
    // Both tiers permitted, as in the other MCP files: this is about what an agent may
    // reach once it exists, not about who may bring one.
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

    // ── A Viewer's project is absent, not read-only ──────────────────────────

    /**
     * The test this file exists for.
     *
     * Both halves in one place deliberately: a build with no floor passes the
     * Contributor half, and a build that floored everything at, say, Maintainer passes
     * the Viewer half. Only the pair pins the line where it was meant to fall.
     */
    @Test
    fun `a project the caller may only view is absent from list_projects`(): Unit = runBlocking {
        val fixture = seed()

        assertEquals(
            emptyList(),
            projectNamesFor(fixture.viewerId),
            "A Viewer's agent was offered a board they can only look at.",
        )
        assertEquals(
            listOf("Lunamux"),
            projectNamesFor(fixture.contributorId),
            "A Contributor's agent lost the board they work on.",
        )
    }

    /**
     * The refusal says exactly what a made-up id says.
     *
     * String equality rather than a phrase match, because the claim is that the two
     * answers *agree*. A build that answered "you are only a Viewer there" would be
     * telling an agent below the floor that the project exists, which is how a board
     * roster gets enumerated one guessed id at a time.
     */
    @Test
    fun `a project below the floor refuses exactly as a project that does not exist`(): Unit = runBlocking {
        val fixture = seed()

        withMcp { client ->
            val token = tokenFor(fixture.viewerId)
            val belowFloor = client.callTool(token, "get_board", """{"project_id":${fixture.projectId}}""")
            val nonexistent = client.callTool(token, "get_board", """{"project_id":987654}""")

            assertTrue(belowFloor.isError, "A board below the agent floor was served.")
            assertEquals(
                nonexistent.text,
                belowFloor.text,
                "A board below the floor refuses differently from one that does not exist, " +
                    "which makes the id a probe for which boards are real.",
            )
        }
    }

    /**
     * `get_issue` is floored too, via `readableIssue`.
     *
     * The plausible half-implementation floors [McpTools.resolveProject] and stops,
     * which leaves every issue tool open: an id is a small integer, and an agent that
     * has ever seen one keeps it.
     */
    @Test
    fun `an issue id is not a way past the floor`(): Unit = runBlocking {
        val fixture = seed()

        withMcp { client ->
            val result = client.callTool(
                tokenFor(fixture.viewerId),
                "get_issue",
                """{"issue_id":${fixture.issueId}}""",
            )
            assertTrue(result.isError, "An issue in a Viewer-only project was served by id.")
        }
    }

    /**
     * Nor is a comment id, on the one branch that used to let one through (LUS-20).
     *
     * `start_attachment_upload`'s `comment_id` branch resolved the comment by hand
     * and gated on `canReadProject` — the Viewer floor — while the check beside it
     * asked about *authorship*. So the two together never reached the agent floor,
     * twenty lines from an `issue_id` branch that gets it right by calling the
     * shared helper.
     *
     * A Viewer who authored the comment could therefore have their agent write bytes
     * into it; and because this branch's refusal strings differed from every other
     * one, an agent below the floor could probe comment-id existence across every
     * board its user can merely view.
     */
    @Test
    fun `a comment id is not a way past the floor`(): Unit = runBlocking {
        val fixture = seed()
        // Authored by the Viewer themselves, so authorship cannot be what refuses
        // this — the floor has to be.
        val commentId = issueRepository.createCommentDraft(fixture.issueId, Author.Account(fixture.viewerId))
        issueRepository.saveComment(commentId, "Mine", actorId = fixture.viewerId)

        withMcp { client ->
            val result = client.callTool(
                tokenFor(fixture.viewerId),
                "start_attachment_upload",
                """{"comment_id":$commentId,"filename":"shot.png","byte_size":10}""",
            )
            assertTrue(
                result.isError,
                "An agent below the Contributor floor uploaded into a comment on a board it may " +
                    "only view.",
            )
            assertEquals(
                "No such comment.",
                result.text,
                "The refusal differs from every other below-the-floor refusal, which makes this " +
                    "branch an oracle for comment ids across every readable board.",
            )
        }
    }

    /**
     * `watch_issue` asks the ownership gate before it resolves a name (LUS-18).
     *
     * It used to ask after, and that ordering was an account-existence oracle:
     * `resolveAuthor` scans every account and returns three distinguishable outcomes
     * — no such account, N accounts by that name, or a fall-through to the ownership
     * refusal — so anyone with an agent and Contributor on one shared board could
     * script it over a list of addresses and read back a membership roster.
     *
     * This contradicts a rule the same file states twice, in the assignee resolver's
     * documentation: "there is no such person" and "they cannot be assigned here"
     * must be the same sentence. The property that closes it is that the refusal is
     * **identical** whether or not the name resolved, so both halves are asserted.
     */
    @Test
    fun `watch_issue refuses a named user the same way whether or not they exist`(): Unit = runBlocking {
        val fixture = seed()

        withMcp { client ->
            val token = tokenFor(fixture.contributorId)
            val real = client.callTool(
                token,
                "watch_issue",
                """{"issue_id":${fixture.issueId},"user":"owner@example.com"}""",
            )
            val invented = client.callTool(
                token,
                "watch_issue",
                """{"issue_id":${fixture.issueId},"user":"nobody-here@example.com"}""",
            )

            assertTrue(real.isError && invented.isError, "A non-owner set somebody else's watch.")
            assertEquals(
                real.text,
                invented.text,
                "Naming a real account and an invented one give different answers, so this tool " +
                    "is an oracle for which addresses have accounts on this instance.",
            )
        }
    }

    /** And naming yourself still works, which is what makes the gate-first ordering free. */
    @Test
    fun `watch_issue still lets a caller name themselves`(): Unit = runBlocking {
        val fixture = seed()

        withMcp { client ->
            val result = client.callTool(
                tokenFor(fixture.contributorId),
                "watch_issue",
                """{"issue_id":${fixture.issueId},"user":"contributor@example.com"}""",
            )
            assertTrue(!result.isError, "A caller naming their own address was refused: ${result.text}")
        }
    }

    // ── Who clears it without a row ──────────────────────────────────────────

    /**
     * The assertion that pins the implementation shape.
     *
     * The owner holds no rung row on this project — nobody granted them one — and
     * reaches [ProjectRole.OWNER] there through [AccessControl.effectiveRole] alone. A
     * floor written as a lookup in `project_roles` would answer "no row, therefore below
     * Contributor" and lock the one account answerable for the deployment out of its own
     * boards. It would also pass every other test in this file.
     */
    @Test
    fun `the instance owner reaches a project they hold no row on`(): Unit = runBlocking {
        val fixture = seed()

        assertEquals(
            emptyMap(),
            roles.rolesForUser(fixture.ownerId),
            "The fixture gave the owner a rung row, so this test would pass for the wrong reason.",
        )
        assertEquals(
            listOf("Lunamux"),
            projectNamesFor(fixture.ownerId),
            "The agent floor locked the instance owner out of a board they own by construction.",
        )
    }

    /**
     * An audience row carries the floor, with no per-person grant anywhere.
     *
     * The other half of asking [AccessControl.effectiveRole] rather than a row: "everybody
     * on this deployment may file bugs here" has to admit their agents too, or the audience
     * mechanism means one thing for browsers and another for tokens.
     */
    @Test
    fun `a members audience row lifts an agent over the floor`(): Unit = runBlocking {
        val fixture = seed()
        val open = projectRepository.create("Open board", "OPN")
        roles.setAudienceRole(open.id, Audience.MEMBER, ProjectRole.CONTRIBUTOR)

        assertEquals(
            null,
            roles.roleFor(fixture.viewerId, open.id),
            "The fixture granted a personal row, so the audience is not what is being tested.",
        )
        assertTrue(
            projectNamesFor(fixture.viewerId).contains("Open board"),
            "A board admitting members as Contributors did not admit their agents.",
        )
    }

    /**
     * A guest row does not, and cannot.
     *
     * [Audience.GUEST]'s ceiling is [ProjectRole.VIEWER], so a public board is *by
     * construction* below the floor for anybody who reaches it only that way. That is
     * the case the feature was written for — a deployment's public boards were the bulk
     * of what a Viewer's agent could previously read — so it is pinned rather than left
     * to follow from the ceiling.
     */
    @Test
    fun `a public board is below the floor for somebody who only matches its guest row`(): Unit = runBlocking {
        val fixture = seed()
        val public = projectRepository.create("Public board", "PUB")
        roles.setAudienceRole(public.id, Audience.GUEST, ProjectRole.VIEWER)

        assertTrue(
            !projectNamesFor(fixture.viewerId).contains("Public board"),
            "A public board was readable by an agent whose user is only its audience.",
        )
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(
        val ownerId: Long,
        val viewerId: Long,
        val contributorId: Long,
        val projectId: Long,
        val issueId: Long,
    )

    /**
     * The instance owner, one Viewer, one Contributor, a project and an issue on it.
     *
     * The owner is whoever signs in first — see Users.sq's upsert — so the order is
     * load-bearing, and they are deliberately given **no rung row** on the project: that
     * absence is what `the instance owner reaches a project they hold no row on` asserts
     * against.
     */
    private suspend fun seed(): Fixture {
        val owner = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-owner", "Owner", "owner@example.com"))
        val viewer = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-viewer", "Viewer", "viewer@example.com"))
        val contributor = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-contributor", "Contributor", "contributor@example.com"),
        )
        assertTrue(!viewer.isInstanceAdmin, "The fixture's second user is somehow an admin.")

        val project = projectRepository.create("Lunamux", "LMX")
        roles.setRole(viewer.id, project.id, ProjectRole.VIEWER)
        roles.setRole(contributor.id, project.id, ProjectRole.CONTRIBUTOR)
        seatInstanceOwner(users, instanceSettings)

        val issueId = publish(project.id, owner.id, "Something to read")
        return Fixture(owner.id, viewer.id, contributor.id, project.id, issueId)
    }

    /** A published issue, so there is something behind an id to be refused. */
    private suspend fun publish(projectId: Long, authorId: Long, title: String): Long {
        val columns = statuses.forProject(projectId)
        val created = issueRepository.createDraft(projectId, Author.Account(authorId))
        issueRepository.save(
            issue = issues.findById(created.first)!!,
            title = title,
            description = "",
            statusId = columns.first().id,
            priorityId = priorities.defaultForProject(projectId)!!.id,
            resolutionId = null,
            assigneeId = null,
            sprintId = null,
            plannedVersionId = null,
            fixedVersionId = null,
            labelIds = emptyList(),
            componentIds = emptyList(),
        )
        return created.first
    }

    /** The project names `list_projects` offers an agent acting as [userId]. */
    private suspend fun projectNamesFor(userId: Long): List<String> {
        var names: List<String>? = null
        withMcp { client ->
            val result = client.callTool(tokenFor(userId), "list_projects", "{}")
            assertTrue(!result.isError, "list_projects was refused: ${result.text}")
            names = Json.parseToJsonElement(result.text).jsonArray
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        }
        return assertNotNull(names, "list_projects returned nothing at all.")
    }

    /** A real access token for [userId], with MCP enabled — see McpBoardFilterTest.tokenFor. */
    private suspend fun tokenFor(userId: Long): String {
        users.setMcpEnabled(userId, true)
        val client = clients.register("Test agent", listOf("http://localhost:1234/callback"), listOf("authorization_code"))
        return tokens.issueTokens(userId, client.clientId, "mcp", "http://localhost/mcp").accessToken
    }

    private class ToolOutcome(val text: String, val isError: Boolean)

    /** Call one tool over JSON-RPC and unwrap the result — see McpBoardFilterTest.callTool. */
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
            .joinToString("\n") { (it as kotlinx.serialization.json.JsonObject)["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        return ToolOutcome(text, result["isError"]?.jsonPrimitive?.contentOrNull == "true")
    }

    /** Mount the real `/mcp` and hand back a client — see McpBoardFilterTest.withMcp. */
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
    )
}
