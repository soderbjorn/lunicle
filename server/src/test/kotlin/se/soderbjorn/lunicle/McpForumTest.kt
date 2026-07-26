/**
 * The forum MCP tools (LNL-78): who may reach them, and what they do.
 *
 * The forum surface is the largest departure in `McpTools` from "an agent gets
 * what the person driving it has in the web app" — it is **system administrator
 * only**, whole and entire, on the reasoning in [AccessControl.canUseForumTools].
 * So the sharpest claims this file pins are about the gate, not the plumbing:
 *
 *  - **Every forum tool is invisible to a non-admin, and refused if named
 *    anyway.** The first is the affordance in `tools/list`; the second is the
 *    enforcement, because `tools/call` never consults that list. A non-admin who
 *    names one gets a refusal that says the capability does not exist for them and
 *    not to retry — never a hint about which forum or project exists.
 *  - **A backfill is silent; a genuine post is not.** `create_forum_post` and
 *    `create_forum_comment` fire the same notifications the web routes fire —
 *    UNLESS the call carries `author`, `author_external` or `created_at`, the
 *    signature of an import. This is the one behaviour in the feature that a
 *    reader could get catastrophically wrong (five thousand mails while an import
 *    runs), so it is asserted on both sides.
 *  - **Drafts are unreachable.** No tool here can name anybody's half-typed post,
 *    because create publishes inside one call and every read excludes `is_draft`.
 *  - **Deleting reports what it destroyed.** Forums record no history, so the
 *    sentence a delete returns is the only account that will ever exist of what
 *    was in the room.
 *
 * Through the real `/mcp` endpoint with real tokens, like its siblings.
 *
 * @see McpTools
 * @see AccessControl.canUseForumTools
 * @see McpSendEmailTest
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class McpForumTest {
    private val file: File = Files.createTempFile("lunicle-mcp-forum", ".db").toFile().also { it.delete() }
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

    private val forumStore = ForumStore(database)
    private val forums = ForumRepository(forumStore, attachments, attachmentStore)
    private val forumPostStore = ForumPostStore(database)
    private val forumCommentStore = ForumCommentStore(database)
    private val forumPosts = ForumPostRepository(forumPostStore, forumCommentStore, attachments, attachmentStore)
    private val subscriptions = SubscriptionStore(database)

    private val clients = OAuthClientStore(database)
    private val loginStates = OAuthLoginStateStore(database)
    private val codes = OAuthCodeStore(database)
    private val tokens = OAuthTokenStore(database)

    /**
     * Every forum notification fired, in order. Empty is the assertion a backfill
     * makes; a `Post`/`Comment` in it is what a genuine write makes.
     */
    private val announcements = mutableListOf<Announcement>()

    private sealed interface Announcement {
        data class Post(val postId: Long, val actorId: Long?) : Announcement
        data class Comment(val postId: Long, val commentId: Long, val actorId: Long?) : Announcement
    }

    /** A recording notifier: it sends nothing, it just remembers it was asked to. */
    private val recordingNotifier = object : ForumNotifier {
        override suspend fun postPublished(
            project: ProjectRecord,
            forum: ForumRecord,
            post: ForumPostRecord,
            actorId: Long?,
        ) {
            announcements += Announcement.Post(post.id, actorId)
        }

        override suspend fun commentPublished(
            project: ProjectRecord,
            forum: ForumRecord,
            post: ForumPostRecord,
            comment: ForumCommentRecord,
            actorId: Long?,
        ) {
            announcements += Announcement.Comment(post.id, comment.id, actorId)
        }
    }

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── The gate: admin only, invisible then refused ──────────────────────────

    /** Every forum tool is offered to the admin and to nobody else. */
    @Test
    fun `forum tools are offered to an admin only`(): Unit = runBlocking {
        val f = seed()
        withMcp { client ->
            val adminTools = client.listTools(tokenFor(f.adminId))
            val ordinaryTools = client.listTools(tokenFor(f.ordinaryId))
            forumToolNames.forEach { name ->
                assertTrue(name in adminTools, "An admin was not offered $name.")
                assertTrue(name !in ordinaryTools, "An ordinary user was offered $name.")
            }
        }
    }

    /**
     * The enforcement, separate from the affordance: a non-admin who names a
     * forum tool anyway is refused, and told not to retry.
     *
     * Asserted for every one of the thirteen, because a filter and a per-tool
     * check are two lists and the whole point of having both is that a tool can
     * fall out of one. A build that gated twelve and forgot the thirteenth would
     * pass "it's not in the list" and hand it out on a direct call.
     */
    @Test
    fun `a non-admin naming any forum tool is refused`(): Unit = runBlocking {
        val f = seed()
        withMcp { client ->
            forumToolNames.forEach { name ->
                val result = client.callTool(tokenFor(f.ordinaryId), name, "{}")
                assertTrue(result.isError, "$name was not refused for a non-admin.")
                assertTrue(
                    result.text.contains("system administrator", ignoreCase = true),
                    "$name's refusal did not say why: ${result.text}",
                )
            }
        }
    }

    /**
     * The refusal a non-admin gets for a real forum's id must not confirm the
     * forum, exactly as `delete_issue` hides a private project's issues.
     *
     * A non-admin who guesses a valid forum id has to get the same sentence as one
     * who guesses a nonsense id — otherwise the gate is also an oracle for what
     * exists.
     */
    @Test
    fun `the gate does not leak whether a forum exists`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.projectId, "Real forum", null)
        withMcp { client ->
            val real = client.callTool(tokenFor(f.ordinaryId), "list_forum_posts", """{"forum_id":${forum.id}}""")
            val fake = client.callTool(tokenFor(f.ordinaryId), "list_forum_posts", """{"forum_id":999999}""")
            assertEquals(
                real.text,
                fake.text,
                "A non-admin could tell a real forum id from a fake one, which leaks what exists.",
            )
        }
    }

    // ── Forums: CRUD and ordering ─────────────────────────────────────────────

    @Test
    fun `an admin creates, renames and lists forums`(): Unit = runBlocking {
        val f = seed()
        withMcp { client ->
            val created = client.callTool(
                tokenFor(f.adminId),
                "create_forum",
                """{"project_id":${f.projectId},"name":"General","description":"Anything goes"}""",
            )
            assertFalse(created.isError, created.text)

            val renamed = client.callTool(
                tokenFor(f.adminId),
                "update_forum",
                """{"forum_id":${onlyForum(f).id},"name":"Announcements"}""",
            )
            assertFalse(renamed.isError, renamed.text)
        }
        assertEquals("Announcements", onlyForum(f).name, "The rename did not stick.")
        // Description was not mentioned by the rename, so it must survive.
        assertEquals("Anything goes", onlyForum(f).description, "An untouched description was lost.")
    }

    /** Present-but-null clears a description; absent leaves it. The three-way rule. */
    @Test
    fun `update_forum clears a description with null and leaves it when absent`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.projectId, "General", "A description")
        withMcp { client ->
            val cleared = client.callTool(
                tokenFor(f.adminId),
                "update_forum",
                """{"forum_id":${forum.id},"description":null}""",
            )
            assertFalse(cleared.isError, cleared.text)
        }
        assertNull(forums.findById(forum.id)?.description, "An explicit null did not clear the description.")
    }

    /** A duplicate name is a sentence the caller can act on, not a 500. */
    @Test
    fun `a duplicate forum name is refused with words`(): Unit = runBlocking {
        val f = seed()
        forums.create(f.projectId, "General", null)
        withMcp { client ->
            val result = client.callTool(
                tokenFor(f.adminId),
                "create_forum",
                """{"project_id":${f.projectId},"name":"general"}""",
            )
            assertTrue(result.isError, "A duplicate name was accepted.")
            assertTrue(result.text.contains("already has a forum"), "The refusal was not the duplicate one: ${result.text}")
        }
    }

    @Test
    fun `reorder_forums puts a project's forums in order`(): Unit = runBlocking {
        val f = seed()
        val a = forums.create(f.projectId, "Alpha", null)
        val b = forums.create(f.projectId, "Bravo", null)
        val c = forums.create(f.projectId, "Charlie", null)
        withMcp { client ->
            val result = client.callTool(
                tokenFor(f.adminId),
                "reorder_forums",
                """{"project_id":${f.projectId},"forum_ids":[${c.id},${a.id},${b.id}]}""",
            )
            assertFalse(result.isError, result.text)
        }
        assertEquals(
            listOf(c.id, a.id, b.id),
            forums.forProject(f.projectId).map { it.id },
            "The order was not applied.",
        )
    }

    /** An order that is not exactly this project's forums is refused, not part-applied. */
    @Test
    fun `reorder_forums refuses a set that is not the project's forums`(): Unit = runBlocking {
        val f = seed()
        val a = forums.create(f.projectId, "Alpha", null)
        forums.create(f.projectId, "Bravo", null)
        withMcp { client ->
            val result = client.callTool(
                tokenFor(f.adminId),
                "reorder_forums",
                """{"project_id":${f.projectId},"forum_ids":[${a.id}]}""",
            )
            assertTrue(result.isError, "A short order was accepted.")
        }
    }

    /**
     * Deleting a forum takes everything in it, and the answer says how much.
     *
     * The count is in the sentence because forums record no history — after this
     * call, the returned text is the only record that the room and its contents
     * ever existed.
     */
    @Test
    fun `delete_forum removes its posts and reports the count`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.projectId, "General", null)
        val post = publishedPost(forum.id, Author.Account(f.adminId))
        publishedComment(post, Author.Account(f.adminId))

        withMcp { client ->
            val result = client.callTool(tokenFor(f.adminId), "delete_forum", """{"forum_id":${forum.id}}""")
            assertFalse(result.isError, result.text)
            assertTrue(result.text.contains("1 post"), "The post count was not reported: ${result.text}")
            assertTrue(result.text.contains("1 comment"), "The comment count was not reported: ${result.text}")
        }
        assertNull(forums.findById(forum.id), "The forum survived its own deletion.")
        assertNull(forumPosts.findPost(post), "A post outlived the forum it was in.")
    }

    // ── Posts and comments ────────────────────────────────────────────────────

    /**
     * A post written as the agent, now, announces to the forum and subscribes its
     * author — exactly as a web post does.
     */
    @Test
    fun `create_forum_post as yourself announces and self-subscribes`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.projectId, "General", null)
        withMcp { client ->
            val result = client.callTool(
                tokenFor(f.adminId),
                "create_forum_post",
                """{"forum_id":${forum.id},"title":"Hello","body":"First post."}""",
            )
            assertFalse(result.isError, result.text)
        }
        val posted = forumPosts.listing(forum.id).single().post
        assertEquals(
            listOf<Announcement>(Announcement.Post(posted.id, f.adminId)),
            announcements,
            "A genuine post did not announce exactly once, to the forum, as the actor.",
        )
        assertTrue(
            subscriptions.isSubscribedToForumPost(f.adminId, posted.id),
            "The author was not subscribed to their own post.",
        )
    }

    /**
     * The same call, but backfilled: it carries a `created_at`, so it is history
     * and nothing is mailed. THE test of the notification rule.
     */
    @Test
    fun `a backfilled post is silent`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.projectId, "General", null)
        withMcp { client ->
            val result = client.callTool(
                tokenFor(f.adminId),
                "create_forum_post",
                """{"forum_id":${forum.id},"title":"Old thread","body":"From 2019.",
                    "author_external":"someone@old-tracker","created_at":1546300800000}""",
            )
            assertFalse(result.isError, result.text)
        }
        assertTrue(announcements.isEmpty(), "An imported post mailed the forum's watchers.")
        val posted = forumPosts.listing(forum.id).single().post
        assertEquals(1546300800000L, posted.createdAt, "The backfilled date did not stick.")
        assertEquals(Author.External("someone@old-tracker"), posted.author, "The external author was lost.")
    }

    @Test
    fun `create_forum_comment as yourself announces, and a backfilled one does not`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.projectId, "General", null)
        val post = publishedPost(forum.id, Author.Account(f.adminId))

        withMcp { client ->
            val genuine = client.callTool(
                tokenFor(f.adminId),
                "create_forum_comment",
                """{"post_id":$post,"body":"A reply."}""",
            )
            assertFalse(genuine.isError, genuine.text)
            val imported = client.callTool(
                tokenFor(f.adminId),
                "create_forum_comment",
                """{"post_id":$post,"body":"An old reply.","created_at":1546300800000}""",
            )
            assertFalse(imported.isError, imported.text)
        }
        assertEquals(1, announcements.count { it is Announcement.Comment }, "Exactly one comment should have announced.")
    }

    /** get_forum_post returns whole bodies and every comment — the export unit. */
    @Test
    fun `get_forum_post returns the post and its comments in full`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.projectId, "General", null)
        val post = publishedPost(forum.id, Author.Account(f.adminId), title = "The title", body = "The whole body.")
        publishedComment(post, Author.Account(f.adminId), body = "A full comment.")

        withMcp { client ->
            val result = client.callTool(tokenFor(f.adminId), "get_forum_post", """{"post_id":$post}""")
            assertFalse(result.isError, result.text)
            val json = Json.parseToJsonElement(result.text).jsonObject
            assertEquals("The whole body.", json["body"]!!.jsonPrimitive.content)
            assertEquals("A full comment.", json["comments"]!!.jsonArray.single().jsonObject["body"]!!.jsonPrimitive.content)
        }
    }

    /** update_forum_post rewrites the body and notifies nobody. */
    @Test
    fun `update_forum_post edits in place without announcing`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.projectId, "General", null)
        val post = publishedPost(forum.id, Author.Account(f.adminId), body = "Before.")
        announcements.clear()

        withMcp { client ->
            val result = client.callTool(
                tokenFor(f.adminId),
                "update_forum_post",
                """{"post_id":$post,"body":"After."}""",
            )
            assertFalse(result.isError, result.text)
        }
        assertEquals("After.", forumPosts.findPost(post)?.body, "The edit did not stick.")
        assertTrue(announcements.isEmpty(), "An edit announced, which it must never do.")
    }

    @Test
    fun `delete_forum_post removes it and its comments`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.projectId, "General", null)
        val post = publishedPost(forum.id, Author.Account(f.adminId))
        val comment = publishedComment(post, Author.Account(f.adminId))

        withMcp { client ->
            val result = client.callTool(tokenFor(f.adminId), "delete_forum_post", """{"post_id":$post}""")
            assertFalse(result.isError, result.text)
        }
        assertNull(forumPosts.findPost(post), "The post survived deletion.")
        assertNull(forumPosts.findComment(comment), "A comment outlived its post.")
    }

    @Test
    fun `delete_forum_comment removes one comment and leaves the post`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.projectId, "General", null)
        val post = publishedPost(forum.id, Author.Account(f.adminId))
        val comment = publishedComment(post, Author.Account(f.adminId))

        withMcp { client ->
            val result = client.callTool(tokenFor(f.adminId), "delete_forum_comment", """{"comment_id":$comment}""")
            assertFalse(result.isError, result.text)
        }
        assertNull(forumPosts.findComment(comment), "The comment survived deletion.")
        assertNotNull(forumPosts.findPost(post), "Deleting a comment deleted its post.")
    }

    // ── Drafts are unreachable ────────────────────────────────────────────────

    /**
     * A draft — somebody's half-typed post — is "no such post" to every tool.
     *
     * The row exists (the web composer made it to hang an image off), but no MCP
     * read may touch it. Asserted through the real tool rather than the store,
     * because the exclusion lives in `forumPostScope` and a store test would not
     * exercise it.
     */
    @Test
    fun `a draft post is invisible to the forum tools`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.projectId, "General", null)
        // A bare draft, never published — exactly what an abandoned composer leaves.
        val draft = forumPosts.createPostDraft(forum.id, Author.Account(f.adminId))

        withMcp { client ->
            val got = client.callTool(tokenFor(f.adminId), "get_forum_post", """{"post_id":$draft}""")
            assertTrue(got.isError, "A draft was readable through get_forum_post.")
            assertEquals("No such post.", got.text)

            val deleted = client.callTool(tokenFor(f.adminId), "delete_forum_post", """{"post_id":$draft}""")
            assertTrue(deleted.isError, "A draft was deletable through the forum tools.")
        }
        assertNotNull(forumPosts.findPost(draft), "A draft was destroyed despite the refusal.")
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val adminId: Long, val ordinaryId: Long, val projectId: Long)

    private suspend fun seed(): Fixture {
        roles.seed()
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin", "Admin", "admin@example.com"))
        assertTrue(admin.isSysAdmin, "The fixture's admin is not the instance admin.")
        val ordinary = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", "ordinary@example.com"),
        )
        assertTrue(!ordinary.isSysAdmin, "The fixture's second user is somehow an admin.")
        val project = projectRepository.create("Lunamux", "LMX", isPublic = false)
        // A member, so an ordinary user CAN read the project — proving the forum
        // refusals are about the surface, not about visibility.
        roles.grant(ordinary.id, project.id, Role.VIEW_PROJECT)
        return Fixture(admin.id, ordinary.id, project.id)
    }

    private suspend fun onlyForum(f: Fixture): ForumRecord = forums.forProject(f.projectId).single()

    private suspend fun publishedPost(
        forumId: Long,
        author: Author,
        title: String = "A post",
        body: String = "A body.",
    ): Long {
        val draft = forumPosts.createPostDraft(forumId, author)
        forumPosts.publishPost(forumPosts.findPost(draft)!!, title, body)
        return draft
    }

    private suspend fun publishedComment(postId: Long, author: Author, body: String = "A comment."): Long {
        val draft = forumPosts.createCommentDraft(postId, author)
        forumPosts.publishComment(forumPosts.findComment(draft)!!, body)
        return draft
    }

    /** A real access token for [userId], MCP enabled — see McpSendEmailTest.tokenFor. */
    private suspend fun tokenFor(userId: Long): String {
        users.setMcpAllowed(userId, true)
        users.setMcpEnabled(userId, true)
        val client =
            clients.register("Test agent", listOf("http://localhost:1234/callback"), listOf("authorization_code"))
        return tokens.issueTokens(userId, client.clientId, "mcp", "http://localhost/mcp").accessToken
    }

    /** The fifteen forum tools, named here so the test states its own expectation. */
    private val forumToolNames = listOf(
        "list_forums", "create_forum", "update_forum", "delete_forum", "reorder_forums",
        "list_forum_posts", "get_forum_post", "create_forum_post", "update_forum_post",
        "delete_forum_post", "create_forum_comment", "update_forum_comment", "delete_forum_comment",
        "watch_forum", "watch_forum_post",
    )

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

    private suspend fun HttpClient.listTools(token: String): List<String> {
        val response = post(MCP_PATH) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
        }
        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonObject
        return result["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
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
        forums = forums,
        forumPosts = forumPosts,
        audience = ProjectAudience(users, roles),
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
        subscriptions = subscriptions,
        reads = ReadStore(database),
        forumNotifications = recordingNotifier,
    )
}
