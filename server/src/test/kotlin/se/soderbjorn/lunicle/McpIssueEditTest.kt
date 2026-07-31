/**
 * The attribution half of `update_issue`: agent labels, and admin re-attribution.
 *
 * `update_issue` has always edited an issue's *content* — title, status, labels.
 * This file covers what it gained alongside `update_comment`: the label and the
 * attribution columns, under the same two-part rule.
 *
 *  - **agent_name is a label, not a privilege.** An ordinary owner may stamp their
 *    own issue as agent-written, exactly as they may a comment. Refusing it as if
 *    it were the backfill gate would be the same inversion McpAgentNameTest guards
 *    against, on the edit path.
 *  - **author, author_external and created_at are the backfill gate, unmoved.** An
 *    ordinary owner may edit their issue's words all day and still not change whose
 *    issue it is or when it was filed — that is admin-only, [canAttributeWrites],
 *    the same function create-time attribution asks.
 *  - **created_at and updated_at stay consistent.** The issue-only wrinkle a comment
 *    does not have: moving one must not strand the other before it.
 *
 * Through the real `/mcp` endpoint, for its siblings' reason.
 *
 * @see McpTools.updateIssue
 * @see McpCommentEditTest
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import se.soderbjorn.lunicle.store.InstanceSettings

class McpIssueEditTest {
    private val file: File = Files.createTempFile("lunicle-issue-edit", ".db").toFile().also { it.delete() }
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

    // ── agent_name is a label, not a privilege ───────────────────────────────

    /** An ordinary owner stamps their own issue. No admin, no refusal. */
    @Test
    fun `an ordinary user can set agent_name on their own issue`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = issueBy(fixture, Author.Account(fixture.ordinaryId))
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(token, "update_issue", """{"issue_id":$issueId,"agent_name":"Claude Code"}""")
            assertTrue(!result.isError, "An ordinary user labelling their own issue was refused: ${result.text}")
        }

        val issue = issues.findById(issueId)!!
        assertEquals("Claude Code", issue.agentName, "The agent name was not stored.")
        assertEquals(Author.Account(fixture.ordinaryId), issue.author, "Labelling moved the author.")
    }

    /**
     * A content edit leaves the attribution alone.
     *
     * Change only the title; the agent name, the author and the creation date must
     * all survive. Guards the read-modify-write over the new editAttribution write:
     * a build that wrote nulls for the fields the call did not mention would strip
     * the badge and unstamp the date on every title fix.
     */
    @Test
    fun `editing content leaves attribution alone`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = issueBy(fixture, Author.Account(fixture.ordinaryId), title = "v1", agentName = "Claude Code")
        val createdAt = issues.findById(issueId)!!.createdAt
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            client.callTool(token, "update_issue", """{"issue_id":$issueId,"title":"v2"}""")
        }

        val issue = issues.findById(issueId)!!
        assertEquals("v2", issue.title, "The title was not updated.")
        assertEquals("Claude Code", issue.agentName, "A content edit stripped the agent name.")
        assertEquals(Author.Account(fixture.ordinaryId), issue.author, "A content edit changed the author.")
        assertEquals(createdAt, issue.createdAt, "A content edit re-dated the issue.")
    }

    // ── The backfill gate is unmoved ─────────────────────────────────────────

    /**
     * An ordinary owner may edit, but not re-attribute.
     *
     * This user CAN edit the issue — it is theirs — so the refusal is the attribution
     * gate turning down `author`, which is the instance OWNER's alone, and not
     * canEditIssue. Nothing is written.
     */
    @Test
    fun `an issue's author cannot re-attribute it without owning the instance`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = issueBy(fixture, Author.Account(fixture.ordinaryId), title = "mine")
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "update_issue",
                """{"issue_id":$issueId,"title":"mine still","author":"Admin"}""",
            )
            assertTrue(result.isError, "Somebody who does not own the instance set an author on an issue edit.")
            assertTrue(result.text.contains("owner"), "The refusal must name the gate. Got: ${result.text}")
        }

        val issue = issues.findById(issueId)!!
        assertEquals("mine", issue.title, "A refused edit still wrote the title.")
        assertEquals(Author.Account(fixture.ordinaryId), issue.author, "A refused edit changed the author.")
    }

    // ── An admin may change everything ───────────────────────────────────────

    /**
     * The "change everything" case: an admin re-attributes to an external author.
     *
     * The issue belonged to an account; an admin gives it to a name with no account,
     * leaving its title and badge alone — and the former owner can no longer edit it,
     * because an [Author.External] is unowned, admin-only from here. The create
     * path's unowned-after-import rule, reached by an edit.
     */
    @Test
    fun `an admin re-attributes an issue to an external author`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = issueBy(fixture, Author.Account(fixture.ordinaryId), title = "kept", agentName = "Claude Code")
        val ordinary = users.findById(fixture.ordinaryId)!!
        assertTrue(access.canEditIssue(ordinary, issues.findById(issueId)!!), "Fixture: author should start editable.")

        val adminToken = tokenFor(fixture.adminId)
        withMcp { client ->
            val result = client.callTool(
                adminToken,
                "update_issue",
                """{"issue_id":$issueId,"author_external":"octocat"}""",
            )
            assertTrue(!result.isError, "An admin re-attribution was refused: ${result.text}")
        }

        val issue = issues.findById(issueId)!!
        assertEquals(Author.External("octocat"), issue.author, "The issue was not re-attributed.")
        assertEquals("kept", issue.title, "Re-attribution disturbed the title.")
        assertEquals("Claude Code", issue.agentName, "Re-attribution dropped the agent name.")
        assertFalse(access.canEditIssue(ordinary, issue), "The former author can still edit an unowned issue.")
    }

    /**
     * An admin backdates an issue, and the two stamps stay consistent.
     *
     * `created_at` moves to the past; with no `updated_at` given, the edit keeps
     * them together rather than stamping "last touched" to now — so a 1997 issue
     * does not still claim it was edited today. And the future bound holds at this
     * new door too.
     */
    @Test
    fun `an admin backdates an issue, keeping updated_at in step`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = issueBy(fixture, Author.Account(fixture.ordinaryId))
        val adminToken = tokenFor(fixture.adminId)
        val theNineties = 852_076_800_000L // 1997-01-01
        val faroff = System.currentTimeMillis() + 1_000L * 60 * 60 * 24 * 30

        withMcp { client ->
            val backdated = client.callTool(
                adminToken,
                "update_issue",
                """{"issue_id":$issueId,"created_at":$theNineties}""",
            )
            assertTrue(!backdated.isError, "An admin backdating an issue was refused: ${backdated.text}")
            val issue = issues.findById(issueId)!!
            assertEquals(theNineties, issue.createdAt, "The issue was not re-dated.")
            assertEquals(theNineties, issue.updatedAt, "updated_at was not kept in step with a backdated created_at.")

            val future = client.callTool(
                adminToken,
                "update_issue",
                """{"issue_id":$issueId,"created_at":$faroff}""",
            )
            assertTrue(future.isError, "A future created_at was accepted on an edit.")
            assertEquals(theNineties, issues.findById(issueId)!!.createdAt, "A refused future date still moved the issue.")
        }
    }

    /**
     * updated_at cannot be dragged before the created_at it lands with.
     *
     * The straddle refusal, now judged against the RESULTING created_at: an admin
     * setting both in one call must not put "last edited" before "written", even
     * when created_at is also being moved in the same request.
     */
    @Test
    fun `updated_at before the resulting created_at is refused`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = issueBy(fixture, Author.Account(fixture.ordinaryId))
        val adminToken = tokenFor(fixture.adminId)
        val created = 1_600_000_000_000L
        val earlier = created - 1_000L

        withMcp { client ->
            val result = client.callTool(
                adminToken,
                "update_issue",
                """{"issue_id":$issueId,"created_at":$created,"updated_at":$earlier}""",
            )
            assertTrue(result.isError, "updated_at before the new created_at was accepted.")
            assertTrue(result.text.contains("before it existed"), "The refusal must explain the straddle. Got: ${result.text}")
        }
    }

    // ── assignee ─────────────────────────────────────────────────────────────

    /** The ordinary path: name somebody assignable, by their display name. */
    @Test
    fun `an assignee can be set by display name`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = issueBy(fixture, Author.Account(fixture.ordinaryId))
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "update_issue",
                """{"issue_id":$issueId,"assignee":"Assignable"}""",
            )
            assertTrue(!result.isError, "Assigning to an assignable user was refused: ${result.text}")
        }

        assertEquals(fixture.assignableId, issues.findById(issueId)!!.assigneeId, "The assignee was not stored.")
    }

    /** The email escape hatch, which is the answer offered when a display name is ambiguous. */
    @Test
    fun `an assignee can be set by email address`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = issueBy(fixture, Author.Account(fixture.ordinaryId))
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "update_issue",
                """{"issue_id":$issueId,"assignee":"assignable@example.com"}""",
            )
            assertTrue(!result.isError, "Assigning by email was refused: ${result.text}")
        }

        assertEquals(fixture.assignableId, issues.findById(issueId)!!.assigneeId, "The assignee was not stored.")
    }

    /**
     * Naming somebody who may not be assigned here is refused, and the WHOLE edit is
     * refused with it.
     *
     * The title assertion is the point: silently keeping the old assignee while
     * writing the rest would report success for a change that did not happen. The
     * subject is `ordinary`, who owns the issue and may edit it — so this is
     * `canBeAssigned` turning down the person named, not `canEditIssue` turning down
     * the caller.
     */
    @Test
    fun `assigning someone without the right is refused and writes nothing`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = issueBy(fixture, Author.Account(fixture.ordinaryId), title = "v1")
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "update_issue",
                """{"issue_id":$issueId,"title":"v2","assignee":"Outsider"}""",
            )
            assertTrue(result.isError, "An unassignable user was accepted as an assignee.")
            assertTrue(
                result.text.contains("cannot be assigned"),
                "The refusal must name the gate. Got: ${result.text}",
            )
        }

        val issue = issues.findById(issueId)!!
        assertEquals(null, issue.assigneeId, "A refused edit assigned somebody anyway.")
        assertEquals("v1", issue.title, "A refused assignee still let the rest of the edit through.")
    }

    /** A name nobody has is refused rather than resolved to a near match. */
    @Test
    fun `an unknown assignee is refused`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = issueBy(fixture, Author.Account(fixture.ordinaryId))
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "update_issue",
                """{"issue_id":$issueId,"assignee":"Nobody At All"}""",
            )
            assertTrue(result.isError, "An unknown name was accepted as an assignee.")
        }

        assertEquals(null, issues.findById(issueId)!!.assigneeId, "A refused edit assigned somebody anyway.")
    }

    /**
     * Explicit null unassigns.
     *
     * The case `isPresent` would get wrong: it reads JsonNull as absent, so a build
     * using it here would answer "leave it alone" to an agent that plainly said
     * nobody, and there would be no way to unassign through this tool at all.
     */
    @Test
    fun `an explicit null clears the assignee`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = issueBy(fixture, Author.Account(fixture.ordinaryId), assigneeId = fixture.assignableId)
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(token, "update_issue", """{"issue_id":$issueId,"assignee":null}""")
            assertTrue(!result.isError, "Clearing the assignee was refused: ${result.text}")
        }

        assertEquals(null, issues.findById(issueId)!!.assigneeId, "An explicit null did not unassign.")
    }

    /**
     * An edit that says nothing about the assignee leaves it alone.
     *
     * The counterpart to the test above, and the reason `assigneeId` is passed
     * explicitly into `save` rather than defaulted: `publish` overwrites the column
     * unconditionally, so a forgotten value here would unassign somebody on every
     * unrelated typo fix.
     */
    @Test
    fun `an edit that omits the assignee keeps it`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = issueBy(fixture, Author.Account(fixture.ordinaryId), title = "v1", assigneeId = fixture.assignableId)
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            client.callTool(token, "update_issue", """{"issue_id":$issueId,"title":"v2"}""")
        }

        val issue = issues.findById(issueId)!!
        assertEquals("v2", issue.title, "The title was not updated.")
        assertEquals(fixture.assignableId, issue.assigneeId, "An unrelated edit unassigned somebody.")
    }

    /** A new issue can arrive already assigned, rather than needing a second call. */
    @Test
    fun `create_issue can assign at filing time`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Filed assigned","assignee":"Assignable"}""",
            )
            assertTrue(!result.isError, "Filing with an assignee was refused: ${result.text}")
        }

        val issue = issues.forProject(fixture.projectId).single { it.title == "Filed assigned" }
        assertEquals(fixture.assignableId, issue.assigneeId, "The assignee was not stored at filing time.")
    }

    /** Omitting it still files an unassigned issue, as the web app does. */
    @Test
    fun `create_issue without an assignee files it unassigned`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Filed plain"}""",
            )
            assertTrue(!result.isError, "Filing without an assignee was refused: ${result.text}")
        }

        val issue = issues.forProject(fixture.projectId).single { it.title == "Filed plain" }
        assertEquals(null, issue.assigneeId, "An issue filed without an assignee got one.")
    }

    /**
     * A refused assignee leaves no issue behind.
     *
     * The assignee is resolved before `createDraft` precisely so this holds: refuse
     * afterwards and there is an invisible draft row nobody can see and nobody will
     * clean up. Asserting on the project's issue list rather than on the refusal is
     * the point — the message was never in doubt, the orphan was.
     */
    @Test
    fun `create_issue with an unassignable person writes no draft`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Never filed","assignee":"Outsider"}""",
            )
            assertTrue(result.isError, "An unassignable user was accepted at filing time.")
        }

        assertTrue(
            issues.forProject(fixture.projectId).none { it.title == "Never filed" },
            "A refused create left an issue behind.",
        )
    }

    /**
     * The refusal does not say whether the name is an account.
     *
     * `Outsider` has an account and no rung here; `Outsider Nonexistent` has neither.
     * The two must be indistinguishable, or anyone with write rights on one project
     * can probe this instance's account list a name at a time — the oracle
     * `POST /api/issues/{id}/assignee` collapses for the same reason. This asserts
     * the two refusals are literally the same sentence.
     */
    @Test
    fun `an unassignable person and an unknown name refuse identically`(): Unit = runBlocking {
        val fixture = seed()
        val issueId = issueBy(fixture, Author.Account(fixture.ordinaryId))
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val existing = client.callTool(
                token,
                "update_issue",
                """{"issue_id":$issueId,"assignee":"Outsider"}""",
            )
            val unknown = client.callTool(
                token,
                "update_issue",
                """{"issue_id":$issueId,"assignee":"Outsider Nonexistent"}""",
            )
            assertTrue(existing.isError && unknown.isError, "One of the two was not refused.")
            assertEquals(
                existing.text.replace("Outsider", "X"),
                unknown.text.replace("Outsider Nonexistent", "X"),
                "The refusals differ, so they disclose whether an account exists.",
            )
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(
        val adminId: Long,
        val ordinaryId: Long,
        val assignableId: Long,
        /**
         * Somebody with an account and **no rung here at all** — the unassignable
         * half of the pair `canBeAssigned` exists to tell apart.
         *
         * They used to be `ordinary`, who held `create_issue` and `comment_on_issue`
         * but not `be_assigned_issue`. That distinction is gone (LNL-191): the three
         * are one rung now, so anybody who may file here may be handed work here, and
         * "may write, may not be assigned" is no longer a state the model can express.
         * What it still expresses — and what these tests are actually about — is that
         * somebody outside the project cannot be handed its work.
         */
        val outsiderId: Long,
        val projectId: Long,
    )

    /** As McpAgentNameTest.seed: the instance admin, one ordinary filer, and a private project. */
    private suspend fun seed(): Fixture {
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin", "Admin", "admin@example.com"))
        val ordinary = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", "ordinary@example.com"),
        )
        assertTrue(!ordinary.isInstanceAdmin, "The fixture's second user is somehow an admin.")
        // A third account that may hold issues here, and a fourth that holds nothing
        // in this project at all — the pair covers both answers canBeAssigned can
        // give without either being an administrator. See Fixture.outsiderId.
        val assignable = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-assignable", "Assignable", "assignable@example.com"),
        )
        val outsider = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-outsider", "Outsider", "outsider@example.com"),
        )
        val project = projectRepository.create("Lunamux", "LMX")
        roles.setRole(ordinary.id, project.id, ProjectRole.CONTRIBUTOR)
        roles.setRole(ordinary.id, project.id, ProjectRole.CONTRIBUTOR)
        roles.setRole(assignable.id, project.id, ProjectRole.CONTRIBUTOR)
        // Production seats the instance owner at boot (see InstanceLadder.kt), and
        // four rules — creating and managing projects, backfilling authorship, agent
        // mail, out-of-band attachment deletes — are the owner's alone rather than an
        // administrator's. A fixture that skipped this would be testing an instance
        // nobody runs: one with an administrator and no owner.
        seatInstanceOwner(users, instanceSettings)
        return Fixture(admin.id, ordinary.id, assignable.id, outsider.id, project.id)
    }

    /** A published issue authored by [author], written straight to the store — the state an edit acts on. Its id. */
    private suspend fun issueBy(
        fixture: Fixture,
        author: Author,
        title: String = "Something",
        agentName: String? = null,
        assigneeId: Long? = null,
    ): Long {
        val created = issueRepository.createDraft(fixture.projectId, author, agentName = agentName)
        val issue = issues.findById(created.first)!!
        issueRepository.save(
            issue = issue,
            title = title,
            description = "",
            statusId = statuses.forProject(fixture.projectId).first().id,
            priorityId = priorities.defaultForProject(fixture.projectId)!!.id,
            resolutionId = null,
            assigneeId = assigneeId,
            sprintId = null,
            plannedVersionId = null,
            fixedVersionId = null,
            labelIds = emptyList(),
            componentIds = emptyList(),
        )
        return created.first
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
        subscriptions = SubscriptionStore(database),
        reads = ReadStore(database),
    )
}
