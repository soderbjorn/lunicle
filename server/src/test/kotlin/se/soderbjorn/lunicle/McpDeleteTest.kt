/**
 * The `delete_issue` and `delete_comment` tools: who may destroy what.
 *
 * These are the only two tools on this surface whose mistakes cannot be walked
 * back, so what is pinned here is narrower and stricter than "it works":
 *
 *  - **The two gates are NOT the same, and must not converge.** Deleting an issue
 *    is [AccessControl.canDeleteIssue], which a project administrator satisfies —
 *    a rung above the maintainer who edits anybody's issue, since LNL-191 split the
 *    two. Deleting a comment is [AccessControl.canEditComment], which no project
 *    rung satisfies at all: it is authorship, and running a board has never meant
 *    "and you may erase what other people wrote". Every project that has handed a
 *    senior rung out would be widened by a build that used one function for both,
 *    silently and retroactively. The pair of tests in "the roles diverge" is the
 *    whole point of this file.
 *  - **A refusal must not destroy anything.** Asserted on both sides of every
 *    refusal: the tool said no *and* the row is still there. A check that runs
 *    after the write refuses just as convincingly.
 *  - **Which refusal comes back for an issue in someone else's project.** "No
 *    such issue" when the caller is not a member of it, and the permission
 *    refusal when they are — asserted as a pair, because a build that answered
 *    the first to everything would satisfy the first test alone. This flipped in
 *    LNL-57, when reading narrowed from "has an account" to membership; the tests
 *    say so out loud rather than leaving today's answer looking like an
 *    oversight.
 *  - **The cascade really cascades.** Comments go with their issue. A delete that
 *    left them behind would leave rows pointing at nothing, and nothing in the UI
 *    would ever show it.
 *
 * Through the real `/mcp` endpoint with real tokens, like its siblings: the claim
 * is about what a caller may send over the wire.
 *
 * @see McpTools.deleteIssue
 * @see McpTools.deleteComment
 * @see McpCommentEditTest
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
import se.soderbjorn.lunicle.store.InstanceSettings

class McpDeleteTest {
    private val file: File = Files.createTempFile("lunicle-mcp-delete", ".db").toFile().also { it.delete() }
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

    // ── Issues ───────────────────────────────────────────────────────────────

    /** The ordinary case: you filed it, so you may unfile it. */
    @Test
    fun `an author deletes their own issue`(): Unit = runBlocking {
        val f = seed()
        val issueId = published(f, author = Author.Account(f.ordinaryId))

        withMcp { client ->
            val result = client.callTool(tokenFor(f.ordinaryId), "delete_issue", """{"issue_id":$issueId}""")
            assertTrue(!result.isError, "An author deleting their own issue was refused: ${result.text}")
            assertTrue(
                result.text.contains("LMX-"),
                "The answer should name the ticket, not just an id nobody can look up afterwards: ${result.text}",
            )
        }
        assertNull(issues.findById(issueId), "The issue survived its own deletion.")
    }

    /**
     * Somebody else's issue, and no role granting otherwise. Refused, and the
     * issue is still there — a check that ran after the delete would pass the
     * first assertion and fail the second.
     */
    @Test
    fun `an unprivileged user cannot delete another persons issue`(): Unit = runBlocking {
        val f = seed()
        val issueId = published(f, author = Author.Account(f.adminId))

        withMcp { client ->
            val result = client.callTool(tokenFor(f.ordinaryId), "delete_issue", """{"issue_id":$issueId}""")
            assertTrue(result.isError, "Someone else's issue was deletable.")
            assertEquals("You cannot delete this issue.", result.text)
        }
        assertNotNull(issues.findById(issueId), "The issue was deleted despite the refusal.")
    }

    /** An admin may delete anything, as everywhere else. */
    @Test
    fun `an admin deletes an issue they did not write`(): Unit = runBlocking {
        val f = seed()
        val issueId = published(f, author = Author.Account(f.ordinaryId))

        withMcp { client ->
            val result = client.callTool(tokenFor(f.adminId), "delete_issue", """{"issue_id":$issueId}""")
            assertTrue(!result.isError, "An admin was refused: ${result.text}")
        }
        assertNull(issues.findById(issueId))
    }

    /**
     * The comments go too, and the answer says how many.
     *
     * The count is in the sentence because the agent has to be able to report
     * what it destroyed — "deleted the issue" understates a thread of twelve.
     */
    @Test
    fun `deleting an issue takes its comments with it`(): Unit = runBlocking {
        val f = seed()
        val issueId = published(f, author = Author.Account(f.ordinaryId))
        val a = commentBy(issueId, Author.Account(f.ordinaryId))
        val b = commentBy(issueId, Author.Account(f.adminId))

        withMcp { client ->
            val result = client.callTool(tokenFor(f.ordinaryId), "delete_issue", """{"issue_id":$issueId}""")
            assertTrue(!result.isError, result.text)
            assertTrue(result.text.contains("2 comments"), "The count was not reported: ${result.text}")
        }
        assertNull(comments.findById(a), "A comment outlived the issue it hung off.")
        assertNull(comments.findById(b), "A comment outlived the issue it hung off.")
    }

    /** An id that names nothing is "no such issue", not a crash and not a 500. */
    @Test
    fun `an issue that does not exist is refused as no such issue`(): Unit = runBlocking {
        val f = seed()
        withMcp { client ->
            val result = client.callTool(tokenFor(f.ordinaryId), "delete_issue", """{"issue_id":999999}""")
            assertTrue(result.isError)
            assertEquals("No such issue.", result.text)
        }
    }

    /**
     * A private project this caller holds nothing in is INVISIBLE, so the refusal
     * is "no such issue" rather than the permission one.
     *
     * This test used to assert the opposite, and the day it flipped was written
     * into its own doc: *"The day reading narrows to a per-project grant, this
     * test flips to expecting 'No such issue.'"* LNL-57 is that day. Reading was
     * `isPublic || user != null` on the argument that this instance's users are a
     * known set and per-project read grants were not in the MVP; membership now
     * decides, so there IS a hidden project to probe for and the conflation
     * McpTools.noSuchProject describes now bites an ordinary caller too.
     *
     * Which is the correct answer, and the reason the conflation exists: a
     * caller who cannot see a project must not be able to tell "this issue is not
     * mine to delete" from "this issue does not exist". The first sentence
     * confirms the project, its size, and that somebody filed something in it.
     * That is precisely the leak the old doc could argue away and this one
     * cannot, so the two refusals must stay indistinguishable from out here.
     *
     * @see AccessControl.canReadProject
     */
    @Test
    fun `a private project the caller holds nothing in is invisible, so the refusal hides it`(): Unit =
        runBlocking {
            val f = seed()
            val other = projectRepository.create("Secret", "SEC")
            val created = issueRepository.createDraft(other.id, Author.Account(f.adminId))

            withMcp { client ->
                val result =
                    client.callTool(tokenFor(f.ordinaryId), "delete_issue", """{"issue_id":${created.first}}""")
                assertTrue(result.isError, "An issue in another project was deletable.")
                assertEquals(
                    "No such issue.",
                    result.text,
                    "The refusal named the issue as existing, which tells a stranger a private project has one.",
                )
            }
            assertNotNull(issues.findById(created.first), "An issue in another project was deleted.")
        }

    /**
     * And a member of that private project gets the *permission* refusal, which
     * is what proves the test above is about visibility rather than about the
     * delete rule having quietly swallowed everything.
     *
     * Without this pair, a build that answered "No such issue." to every failed
     * delete would pass.
     */
    @Test
    fun `a member of a private project gets the permission refusal, not the invisible one`(): Unit =
        runBlocking {
            val f = seed()
            val other = projectRepository.create("Secret", "SEC")
            val created = issueRepository.createDraft(other.id, Author.Account(f.adminId))
            roles.setRole(f.ordinaryId, other.id, ProjectRole.VIEWER)

            withMcp { client ->
                val result =
                    client.callTool(tokenFor(f.ordinaryId), "delete_issue", """{"issue_id":${created.first}}""")
                assertTrue(result.isError, "A read-only member deleted somebody else's issue.")
                assertEquals("You cannot delete this issue.", result.text)
            }
            assertNotNull(issues.findById(created.first), "A read-only member deleted somebody else's issue.")
        }

    // ── Comments ─────────────────────────────────────────────────────────────

    /** Your own words are yours to withdraw. */
    @Test
    fun `an author deletes their own comment`(): Unit = runBlocking {
        val f = seed()
        val issueId = published(f, author = Author.Account(f.adminId))
        val commentId = commentBy(issueId, Author.Account(f.ordinaryId))

        withMcp { client ->
            val result = client.callTool(tokenFor(f.ordinaryId), "delete_comment", """{"comment_id":$commentId}""")
            assertTrue(!result.isError, "An author deleting their own comment was refused: ${result.text}")
        }
        assertNull(comments.findById(commentId))
        assertNotNull(issues.findById(issueId), "Deleting a comment deleted its issue.")
    }

    /** Somebody else's words are not. */
    @Test
    fun `an ordinary user cannot delete another persons comment`(): Unit = runBlocking {
        val f = seed()
        val issueId = published(f, author = Author.Account(f.adminId))
        val commentId = commentBy(issueId, Author.Account(f.adminId))

        withMcp { client ->
            val result = client.callTool(tokenFor(f.ordinaryId), "delete_comment", """{"comment_id":$commentId}""")
            assertTrue(result.isError, "Someone else's comment was deletable.")
            assertEquals("That is not your comment.", result.text)
        }
        assertNotNull(comments.findById(commentId), "The comment was deleted despite the refusal.")
    }

    // ── The roles diverge ────────────────────────────────────────────────────

    /**
     * A maintainer edits anyone's issue and does **not** delete one; an administrator
     * does.
     *
     * This test used to read "change_unowned_issues lets you delete someone else's
     * issue", because deleting was the same rule as editing on the argument that
     * anyone who can open the modal can already empty the issue of everything it
     * said. LNL-191 draws the line one rung higher instead: emptying an issue leaves
     * a row somebody can still find and argue with, and deleting it does not. So the
     * claim is inverted rather than dropped, and the administrator half is asserted
     * in the same test so the pair cannot drift into "nobody can delete".
     */
    @Test
    fun `a maintainer does not delete someone elses issue, and an administrator does`(): Unit = runBlocking {
        val f = seed()
        roles.setRole(f.ordinaryId, f.projectId, ProjectRole.MAINTAINER)
        val issueId = published(f, author = Author.Account(f.adminId))

        withMcp { client ->
            val refused = client.callTool(tokenFor(f.ordinaryId), "delete_issue", """{"issue_id":$issueId}""")
            assertTrue(refused.isError, "A maintainer deleted somebody else's issue.")
        }
        assertNotNull(issues.findById(issueId), "A refused delete removed the issue anyway.")

        roles.setRole(f.ordinaryId, f.projectId, ProjectRole.ADMIN)
        withMcp { client ->
            val allowed = client.callTool(tokenFor(f.ordinaryId), "delete_issue", """{"issue_id":$issueId}""")
            assertTrue(!allowed.isError, "An administrator could not delete an issue: ${allowed.text}")
        }
        assertNull(issues.findById(issueId))
    }

    /**
     * …and stops at a comment. THE test in this file.
     *
     * Same user, same project, same role, one step narrower a target. A build
     * that gated both tools on `canDeleteIssue` — the obvious-looking
     * simplification, since they are both "delete a thing" — would pass every
     * other test here and quietly hand everyone holding this role the power to
     * erase other people's words in projects that granted it long ago.
     */
    @Test
    fun `a maintainer does NOT delete someone elses comment`(): Unit = runBlocking {
        val f = seed()
        roles.setRole(f.ordinaryId, f.projectId, ProjectRole.MAINTAINER)
        val issueId = published(f, author = Author.Account(f.adminId))
        val commentId = commentBy(issueId, Author.Account(f.adminId), body = "words that are not yours")

        withMcp { client ->
            val result = client.callTool(tokenFor(f.ordinaryId), "delete_comment", """{"comment_id":$commentId}""")
            assertTrue(
                result.isError,
                "The maintainer rung reached someone else's COMMENT. That rung is a grant over " +
                    "issues; widening it to other people's words is retroactive for every project " +
                    "that has already handed it out.",
            )
        }
        assertEquals(
            "words that are not yours",
            comments.findById(commentId)?.body,
            "The comment was deleted despite the refusal.",
        )
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val adminId: Long, val ordinaryId: Long, val projectId: Long)

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

    /** A published issue written by [author]. Its id. */
    private suspend fun published(fixture: Fixture, author: Author): Long {
        val created = issueRepository.createDraft(fixture.projectId, author)
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

    private suspend fun commentBy(issueId: Long, author: Author, body: String = "original"): Long {
        val id = issueRepository.createCommentDraft(issueId, author)
        issueRepository.saveComment(id, body)
        return id
    }

    /** A real access token for [userId], with MCP enabled — see McpCommentEditTest.tokenFor. */
    private suspend fun tokenFor(userId: Long): String {
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
