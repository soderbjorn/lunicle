/**
 * The `update_comment` tool: who may edit a comment, and how far.
 *
 * The tool is two rules stacked, and every test here pins one of them:
 *
 *  - **May you touch this comment at all?** [AccessControl.canEditComment] — your
 *    own, or anyone's if you are an admin. The web app's `PUT /api/comments/{id}`
 *    asks exactly this, and the MCP tool must not be a softer door onto the same
 *    write. So a non-owner is refused, and an owner is not.
 *  - **May you also re-attribute or re-date it?** The admin-only backfill gate,
 *    [AccessControl.canAttributeWrites], reused verbatim from the create tools.
 *    An ordinary owner may rewrite their words and label the row as agent-written,
 *    but not change whose comment it is or when it was written.
 *
 * The load-bearing pair, held together the way McpAgentNameTest holds its own: an
 * ordinary user setting `agent_name` on their own comment is NOT refused (it is a
 * label, not a privilege), while that same ordinary user passing `author` IS
 * (that is the backfill gate, unmoved). A build that confused the two would pass
 * half of this file and invert the point of the other half.
 *
 * Through the real `/mcp` endpoint with real tokens, for the same reason its
 * siblings are: the claim is about what a caller may send over the wire.
 *
 * @see McpTools.updateComment
 * @see McpAgentNameTest
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class McpCommentEditTest {
    private val file: File = Files.createTempFile("lunicle-comment-edit", ".db").toFile().also { it.delete() }
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

    // ── May you touch it at all ──────────────────────────────────────────────

    /** The ordinary, whole point: an author rewrites their own comment. */
    @Test
    fun `an author edits their own comment`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = published(fixture)
        val commentId = commentBy(issueId, Author.Account(fixture.ordinaryId), body = "frist post")
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(token, "update_comment", """{"comment_id":$commentId,"body":"first post"}""")
            assertTrue(!result.isError, "An author editing their own comment was refused: ${result.text}")
        }

        assertEquals("first post", comments.findById(commentId)!!.body, "The body was not updated.")
    }

    /**
     * Someone else's comment is not yours to rewrite.
     *
     * The comment is the admin's; an ordinary user with the comment role on the
     * same project — able to write their own — is refused editing this one, and
     * nothing changes. `comment_on_issue` is a right to your own words, never a
     * right to edit another account's, exactly as canEditComment says.
     */
    @Test
    fun `editing another account's comment is refused`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = published(fixture)
        val commentId = commentBy(issueId, Author.Account(fixture.adminId), body = "the admin's words")
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(token, "update_comment", """{"comment_id":$commentId,"body":"rewritten"}""")
            assertTrue(result.isError, "An ordinary user rewrote another account's comment.")
            assertTrue(result.text.contains("not your comment"), "The refusal must say why. Got: ${result.text}")
        }

        assertEquals("the admin's words", comments.findById(commentId)!!.body, "A refused edit changed the comment.")
    }

    // ── agent_name is a label, not a privilege ───────────────────────────────

    /**
     * The tool that started all this: an ordinary user stamps their own comment.
     *
     * No admin anywhere. Setting `agent_name` on a comment you may already edit is
     * not the backfill gate and must not be refused as one — the mirror of
     * McpAgentNameTest's first test, on the edit path.
     */
    @Test
    fun `an ordinary user can set agent_name on their own comment`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = published(fixture)
        val commentId = commentBy(issueId, Author.Account(fixture.ordinaryId))
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "update_comment",
                """{"comment_id":$commentId,"agent_name":"Claude Code"}""",
            )
            assertTrue(!result.isError, "An ordinary user labelling their own comment was refused: ${result.text}")
        }

        val comment = comments.findById(commentId)!!
        assertEquals("Claude Code", comment.agentName, "The agent name was not stored.")
        assertEquals(Author.Account(fixture.ordinaryId), comment.author, "Labelling the comment moved its author.")
    }

    /**
     * An omitted field is "leave alone", across the board.
     *
     * Set a body, an agent name and (as admin, at creation) nothing exotic, then
     * edit ONLY the body — the agent name, the author and the date must all
     * survive untouched. This guards the read-modify-write defaulting: a build that
     * wrote nulls for the fields the call did not mention would silently strip the
     * badge and unstamp the date on every body edit.
     */
    @Test
    fun `editing one field leaves the others alone`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = published(fixture)
        val commentId = commentBy(issueId, Author.Account(fixture.ordinaryId), body = "v1", agentName = "Claude Code")
        val createdAt = comments.findById(commentId)!!.createdAt
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            client.callTool(token, "update_comment", """{"comment_id":$commentId,"body":"v2"}""")
        }

        val comment = comments.findById(commentId)!!
        assertEquals("v2", comment.body, "The body was not updated.")
        assertEquals("Claude Code", comment.agentName, "A body-only edit stripped the agent name.")
        assertEquals(Author.Account(fixture.ordinaryId), comment.author, "A body-only edit changed the author.")
        assertEquals(createdAt, comment.createdAt, "A body-only edit re-dated the comment.")
    }

    // ── The backfill gate is unmoved ─────────────────────────────────────────

    /**
     * An ordinary owner may edit, but not re-attribute.
     *
     * The two rules pulled apart: this user CAN edit the comment (it is theirs), so
     * the refusal here is not canEditComment — it is the admin-only attribution
     * gate, refusing `author` for a non-admin exactly as it does on add_comment.
     * Nothing is written.
     */
    @Test
    fun `a non-admin owner cannot re-attribute their comment`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = published(fixture)
        val commentId = commentBy(issueId, Author.Account(fixture.ordinaryId), body = "mine")
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "update_comment",
                """{"comment_id":$commentId,"body":"mine still","author":"Admin"}""",
            )
            assertTrue(result.isError, "A non-admin set an author on a comment edit.")
            assertTrue(result.text.contains("admin"), "The refusal must name the gate. Got: ${result.text}")
        }

        val comment = comments.findById(commentId)!!
        assertEquals("mine", comment.body, "A refused edit still wrote the body.")
        assertEquals(Author.Account(fixture.ordinaryId), comment.author, "A refused edit changed the author.")
    }

    // ── An admin may change everything ───────────────────────────────────────

    /**
     * The "change everything" case: an admin re-attributes to an external author.
     *
     * The comment belonged to an account; an admin gives it to a name with no
     * account behind it — the imported-author case — while its body and badge are
     * left alone. And the consequence is asserted too: the original account can no
     * longer edit the comment, because an [Author.External] is never equal to an
     * account, so what was the author's own is now admin-only. That is the same
     * unowned-after-import rule the create path has, reached by an edit.
     */
    @Test
    fun `an admin re-attributes a comment to an external author`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = published(fixture)
        val commentId = commentBy(issueId, Author.Account(fixture.ordinaryId), body = "kept", agentName = "Claude Code")
        val ordinary = users.findById(fixture.ordinaryId)!!
        assertTrue(access.canEditComment(ordinary, comments.findById(commentId)!!), "Fixture: author should start editable.")

        val adminToken = tokenFor(fixture.adminId)
        withMcp { client ->
            val result = client.callTool(
                adminToken,
                "update_comment",
                """{"comment_id":$commentId,"author_external":"octocat"}""",
            )
            assertTrue(!result.isError, "An admin re-attribution was refused: ${result.text}")
        }

        val comment = comments.findById(commentId)!!
        assertEquals(Author.External("octocat"), comment.author, "The comment was not re-attributed.")
        assertEquals("kept", comment.body, "Re-attribution disturbed the body.")
        assertEquals("Claude Code", comment.agentName, "Re-attribution dropped the agent name.")
        assertFalse(access.canEditComment(ordinary, comment), "The former author can still edit an unowned comment.")
    }

    /**
     * An admin re-dates a comment, and a future date is still refused.
     *
     * The date is now reachable by an edit where before it was creation-only, so
     * the future bound — the one that matters, since a far-future comment sinks to
     * the bottom of its thread forever — must hold at this door too, not just at
     * creation.
     */
    @Test
    fun `an admin re-dates a comment but not into the future`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = published(fixture)
        val commentId = commentBy(issueId, Author.Account(fixture.ordinaryId))
        val adminToken = tokenFor(fixture.adminId)
        val theNineties = 852_076_800_000L // 1997-01-01, comfortably in the past.
        val faroff = System.currentTimeMillis() + 1_000L * 60 * 60 * 24 * 30

        withMcp { client ->
            val backdated = client.callTool(
                adminToken,
                "update_comment",
                """{"comment_id":$commentId,"created_at":$theNineties}""",
            )
            assertTrue(!backdated.isError, "An admin backdating a comment was refused: ${backdated.text}")
            assertEquals(theNineties, comments.findById(commentId)!!.createdAt, "The comment was not re-dated.")

            val future = client.callTool(
                adminToken,
                "update_comment",
                """{"comment_id":$commentId,"created_at":$faroff}""",
            )
            assertTrue(future.isError, "A future created_at was accepted on an edit.")
            assertEquals(
                theNineties,
                comments.findById(commentId)!!.createdAt,
                "A refused future date still moved the comment.",
            )
        }
    }

    // ── A comment that isn't there ───────────────────────────────────────────

    /**
     * A comment id that resolves to nothing answers "No such comment".
     *
     * The readableComment null path: the tool refuses before it reaches
     * canEditComment, with the same deliberately incurious wording the issue tools
     * use — the sentence is the same whether the row never existed or sits in a
     * project the caller cannot see, so an id cannot be turned into a probe.
     */
    @Test
    fun `editing a comment that does not exist is refused`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(token, "update_comment", """{"comment_id":999999,"body":"into the void"}""")
            assertTrue(result.isError, "Editing a nonexistent comment was not refused.")
            assertTrue(result.text.contains("No such comment"), "The refusal must be the incurious one. Got: ${result.text}")
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val adminId: Long, val ordinaryId: Long, val projectId: Long)

    /** As McpAgentNameTest.seed: the instance admin, one ordinary filer, and a private project. */
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

    /** A published issue to hang comments off. Its id. */
    private suspend fun published(fixture: Fixture): Long {
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
        return created.first
    }

    /** A published comment authored by [author], written straight to the store — the state an edit acts on. */
    private suspend fun commentBy(issueId: Long, author: Author, body: String = "original", agentName: String? = null): Long {
        val id = issueRepository.createCommentDraft(issueId, author, agentName = agentName)
        issueRepository.saveComment(id, body)
        return id
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
