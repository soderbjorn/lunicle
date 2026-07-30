/**
 * Upload tickets: the capability, and the route that spends it.
 *
 * ── Why this is worth its own file ──────────────────────────────────────────
 *
 * `POST /api/attachments/upload/{token}` is **the only write in this system with
 * no session**, and it stores rows carrying an author it was not told. Both of
 * those are true on purpose — see [AttachmentTicketStore] — and both are exactly
 * the shape of thing that quietly stops being true:
 *
 *  - **The ticket becoming multi-use.** A `containsKey` where there is a
 *    `remove`, and one leaked token uploads forever. Nothing looks wrong; the
 *    happy path is identical.
 *  - **Expiry not being checked.** Same: every test that mints and immediately
 *    redeems passes against a store that never expires anything.
 *  - **The redeem route learning to read attribution from the request.** The one
 *    that matters most. `author_external` is the instance owner's alone because the
 *    *mint* asks [AccessControl.canAttributeWrites]; if the upload ever accepts a
 *    query parameter or a signed-in user's identity, that check stops being the
 *    last word and the owner-only capability is owner-only in name.
 *  - **The mint skipping the edit check.** An upload endpoint gated on "do you
 *    hold a token" and nothing else is an open file host with our name on it —
 *    the thing BoardRoutes' own comment warns about on the session routes.
 *
 * The store's own tests drive it directly, with a fake clock, because the thing
 * being asserted is arithmetic about time and a test that slept for five minutes
 * would be a test nobody runs. The end-to-end tests go through the real `/mcp`
 * and the real upload route, mounted together over one [BoardDependencies] — a
 * ticket minted against one store and redeemed against another is the bug this
 * feature is most likely to ship with, and only a test that shares the object
 * graph the way production does would catch it.
 *
 * @see AttachmentTicketStore
 * @see McpTools
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import se.soderbjorn.lunicle.clientserver.AuthProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import se.soderbjorn.lunicle.store.InstanceSettings

class AttachmentTicketTest {

    // ── The store, against a clock that does what it is told ─────────────────

    /** A ticket is a capability, and a capability that can be spent twice is a bug. */
    @Test
    fun `a ticket can only be redeemed once`() {
        val store = AttachmentTicketStore(now = { 0L })
        val token = store.mint(AttachmentTarget.Issue(1), "a.png", Author.Nobody, null)

        assertNotNull(store.redeem(token), "The first redemption failed.")
        assertNull(store.redeem(token), "The same ticket uploaded twice.")
    }

    /**
     * Expiry is checked, and checked against the clock rather than assumed.
     *
     * One minute in is fine; an hour in is not. The failing version of this
     * passes every test that mints and redeems in the same breath.
     */
    @Test
    fun `a ticket expires`() {
        var clock = 0L
        val store = AttachmentTicketStore(now = { clock })

        val live = store.mint(AttachmentTarget.Issue(1), "a.png", Author.Nobody, null)
        clock = 60_000L
        assertNotNull(store.redeem(live), "A one-minute-old ticket was rejected.")

        val stale = store.mint(AttachmentTarget.Issue(1), "a.png", Author.Nobody, null)
        clock += 60L * 60 * 1000
        assertNull(store.redeem(stale), "An hour-old ticket still worked.")
    }

    /** An expired ticket is dropped rather than kept forever against a redeem that never comes. */
    @Test
    fun `expired tickets do not accumulate`() {
        var clock = 0L
        val store = AttachmentTicketStore(now = { clock })
        repeat(5) { store.mint(AttachmentTarget.Issue(1), "a.png", Author.Nobody, null) }
        assertEquals(5, store.size())

        clock += 60L * 60 * 1000
        store.mint(AttachmentTarget.Issue(1), "fresh.png", Author.Nobody, null)
        assertEquals(1, store.size(), "Minting did not sweep the five expired tickets.")
    }

    /** A token that was never minted is simply no. */
    @Test
    fun `an unknown token is refused`() {
        val store = AttachmentTicketStore(now = { 0L })
        store.mint(AttachmentTarget.Issue(1), "a.png", Author.Nobody, null)
        assertNull(store.redeem("lat_notarealtoken"))
    }

    // ── Minting is a permission check ────────────────────────────────────────

    /**
     * The check the upload route does not do, because this one already did.
     *
     * An ordinary user with no rights on a private project cannot mint against
     * its issue. If this ever passes, the upload endpoint is reachable by anyone
     * who can call `start_attachment_upload`.
     */
    @Test
    fun `a user who cannot edit an issue cannot mint a ticket for it`(): Unit = runBlocking {
        val fixture = seed()
        val (issueId, _) = published(fixture)
        // The admin's issue; `ordinary` is a contributor, so they may file their own
        // but not edit somebody else's — that is the maintainer rung.
        val token = tokenFor(fixture.ordinaryId)

        withServer { client ->
            val result = client.callTool(token, "start_attachment_upload", """{"issue_id":$issueId,"filename":"x.png"}""")
            assertTrue(result.isError, "A user who cannot edit the issue minted an upload ticket for it.")
        }
        assertEquals(0, tickets.size(), "A ticket was minted despite the refusal.")
    }

    /** Exactly one owner, refused at the tool rather than at the CHECK. */
    @Test
    fun `minting for both an issue and a comment, or neither, is refused`(): Unit = runBlocking {
        val fixture = seed()
        val (issueId, _) = published(fixture)
        val token = tokenFor(fixture.adminId)

        withServer { client ->
            val both = client.callTool(
                token,
                "start_attachment_upload",
                """{"issue_id":$issueId,"comment_id":1,"filename":"x.png"}""",
            )
            assertTrue(both.isError, "A ticket was minted for an issue AND a comment.")

            val neither = client.callTool(token, "start_attachment_upload", """{"filename":"x.png"}""")
            assertTrue(neither.isError, "A ticket was minted for nothing at all.")
        }
        assertEquals(0, tickets.size())
    }

    /** `author_external` is the owner's alone here exactly as it is on create_issue. */
    @Test
    fun `a non-admin cannot mint a ticket claiming an external author`(): Unit = runBlocking {
        val fixture = seed()
        val (issueId, _) = published(fixture, author = Author.Account(fixture.ordinaryId))
        val token = tokenFor(fixture.ordinaryId)

        withServer { client ->
            val result = client.callTool(
                token,
                "start_attachment_upload",
                """{"issue_id":$issueId,"filename":"x.png","author_external":"octocat"}""",
            )
            assertTrue(result.isError, "A non-admin minted a ticket attributing a file to an invented author.")
            assertTrue(result.text.contains("Only the instance owner"), "Wrong refusal: ${result.text}")
        }
        assertEquals(0, tickets.size(), "A ticket was minted despite the refusal.")
    }

    /** Its own issue is fine — the tool itself is nobody's exclusive. */
    @Test
    fun `an ordinary user can mint a ticket for their own issue`(): Unit = runBlocking {
        val fixture = seed()
        val (issueId, _) = published(fixture, author = Author.Account(fixture.ordinaryId))
        val token = tokenFor(fixture.ordinaryId)

        withServer { client ->
            val result = client.callTool(token, "start_attachment_upload", """{"issue_id":$issueId,"filename":"x.png"}""")
            assertTrue(!result.isError, "An ordinary user could not attach to their own issue: ${result.text}")
        }
    }

    // ── Mint, upload, and what lands ─────────────────────────────────────────

    /**
     * The whole path, as an agent walks it.
     *
     * Mint over MCP, push the bytes at the URL that came back, and assert the row
     * — including the attribution the *upload* never mentioned. That last part is
     * the point: the file belongs to "octocat" because an admin said so at mint,
     * and the anonymous request that delivered the bytes had no say in it.
     */
    @Test
    fun `an admin can upload a file attributed to somebody with no account`(): Unit = runBlocking {
        val fixture = seed()
        val (issueId, _) = published(fixture)
        val token = tokenFor(fixture.adminId)

        withServer { client ->
            val minted = client.callTool(
                token,
                "start_attachment_upload",
                """{"issue_id":$issueId,"filename":"screenshot.png","author_external":"octocat",""" +
                    """"created_at":$backfilledAt}""",
            )
            assertTrue(!minted.isError, minted.text)
            val uploadUrl = assertNotNull(
                Json.parseToJsonElement(minted.text).jsonObject["upload_url"]?.jsonPrimitive?.contentOrNull,
                "No upload_url in ${minted.text}",
            )

            val response = client.post(uploadUrl.substringAfter("http://localhost")) {
                contentType(ContentType.Image.PNG)
                setBody(PNG)
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val attachmentId = assertNotNull(body["attachment_id"]?.jsonPrimitive?.longOrNull)
            // The URL is built from the public id, NOT the row id — the two are
            // different values since LNL-51, and asserting the URL against
            // attachment_id here would pass only for as long as they were the same.
            val publicId = assertNotNull(body["public_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("/api/attachments/$publicId", body["url"]?.jsonPrimitive?.contentOrNull)
            assertNotEquals(
                attachmentId.toString(),
                publicId,
                "The public id is the row id in decimal, so the URL is still countable.",
            )
            assertEquals(
                true,
                body["renders_inline"]?.jsonPrimitive?.booleanOrNull,
                "A PNG was reported as not rendering inline, so the agent will write a download link.",
            )

            val record = assertNotNull(attachmentStore.findById(attachmentId), "The row was not written.")
            assertEquals(Author.External("octocat"), record.author, "The upload lost the ticket's author.")
            assertEquals(backfilledAt, record.createdAt, "The upload lost the ticket's timestamp.")
            assertEquals("screenshot.png", record.filename)
            assertEquals(issueId, record.issueId)
        }
    }

    /**
     * The filename comes from the ticket, not from whoever has it.
     *
     * A ticket holder renaming the file would be a caller editing a decision made
     * under a permission check — small here, and the same shape as the ones that
     * are not small.
     */
    @Test
    fun `the upload cannot rename the file`(): Unit = runBlocking {
        val fixture = seed()
        val (issueId, _) = published(fixture)
        val token = tokenFor(fixture.adminId)

        withServer { client ->
            val minted = client.callTool(
                token,
                "start_attachment_upload",
                """{"issue_id":$issueId,"filename":"agreed.png"}""",
            )
            val uploadUrl = Json.parseToJsonElement(minted.text)
                .jsonObject["upload_url"]!!.jsonPrimitive.content

            val response = client.post(
                uploadUrl.substringAfter("http://localhost") + "?filename=something-else.png",
            ) {
                contentType(ContentType.Image.PNG)
                setBody(PNG)
            }
            val id = Json.parseToJsonElement(response.bodyAsText())
                .jsonObject["attachment_id"]!!.jsonPrimitive.long
            assertEquals("agreed.png", assertNotNull(attachmentStore.findById(id)).filename)
        }
    }

    /** Single use, through the real route rather than only through the store. */
    @Test
    fun `a spent ticket cannot be uploaded to again`(): Unit = runBlocking {
        val fixture = seed()
        val (issueId, _) = published(fixture)
        val token = tokenFor(fixture.adminId)

        withServer { client ->
            val minted = client.callTool(
                token,
                "start_attachment_upload",
                """{"issue_id":$issueId,"filename":"once.png"}""",
            )
            val path = Json.parseToJsonElement(minted.text)
                .jsonObject["upload_url"]!!.jsonPrimitive.content
                .substringAfter("http://localhost")

            val first = client.post(path) { contentType(ContentType.Image.PNG); setBody(PNG) }
            assertEquals(HttpStatusCode.OK, first.status)

            val second = client.post(path) { contentType(ContentType.Image.PNG); setBody(PNG) }
            assertEquals(HttpStatusCode.NotFound, second.status, "A spent ticket uploaded a second file.")
        }
    }

    /** A made-up token is a 404, and says nothing about which kind of no it is. */
    @Test
    fun `an invented ticket is refused`(): Unit = runBlocking {
        seed()
        withServer { client ->
            val response = client.post("/api/attachments/upload/lat_deadbeef") {
                contentType(ContentType.Image.PNG)
                setBody(PNG)
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private val file: File = Files.createTempFile("lunicle-ticket", ".db").toFile().also { it.delete() }
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
    private val attachmentsDirectory = File(file.parentFile, "attachments-${file.name}")
    private val attachments = AttachmentRepository(attachmentStore, attachmentsDirectory)
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

    /**
     * One store, shared by the tool that mints and the route that spends.
     *
     * A field rather than built inside [boardDependencies] so that the two routes
     * cannot end up with one each — which is the failure this whole file would
     * otherwise miss, since every individual test would still pass.
     */
    private val tickets = AttachmentTicketStore()

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
        attachmentsDirectory.deleteRecursively()
    }

    /** A moment in 2019. Not now, and no clock produces it. */
    private val backfilledAt = 1_550_000_000_000L

    /** The smallest thing `validate` will accept as a PNG: a real 1×1. */
    private val PNG: ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
    )

    private class Fixture(val adminId: Long, val ordinaryId: Long, val projectId: Long)

    private suspend fun seed(): Fixture {
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin", "Admin", "admin@example.com"))
        val ordinary = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", "ordinary@example.com"),
        )
        assertTrue(!ordinary.isInstanceAdmin, "The fixture's second user is somehow an admin.")
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

    private suspend fun published(
        fixture: Fixture,
        author: Author = Author.Account(fixture.adminId),
    ): Pair<Long, Long> {
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
        return created
    }

    private suspend fun tokenFor(userId: Long): String {
        // Both halves of canUseMcp — /mcp re-reads the pair per request, and a
        // token whose owner is missing either is a 401 (invalid_token) that would
        // fail every test here for the wrong reason. mcp_allowed is off by default
        // (6.sqm), so the permission has to be granted; mcp_enabled is the user's
        // own switch.
        users.setMcpEnabled(userId, true)
        val client = clients.register("Test agent", listOf("http://localhost:1234/callback"), listOf("authorization_code"))
        return tokens.issueTokens(userId, client.clientId, "mcp", "http://localhost/mcp").accessToken
    }

    private class ToolOutcome(val text: String, val isError: Boolean)

    private suspend fun HttpClient.callTool(token: String, name: String, arguments: String): ToolOutcome {
        val response = post("/mcp") {
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
     * Both surfaces, one object graph.
     *
     * `mcpRoutes` AND the real `boardRoutes` — which is what carries the upload
     * route — over a single [BoardDependencies], the arrangement production has.
     * Two dependency objects would give the minting tool one ticket store and the
     * upload route another, and every test here would still pass while the
     * feature never worked once. That is the whole reason [tickets] is a field.
     *
     * `boardRoutes` rather than reaching for the private `attachmentRoutes`
     * directly: AttachmentTest already mounts the real thing, and widening a
     * route's visibility so a test can mount half of it is how a test stops
     * exercising what ships.
     */
    private fun withServer(block: suspend (HttpClient) -> Unit) = testApplication {
        val deps = boardDependencies()
        application {
            install(ServerContentNegotiation) { json() }
            routing {
                mcpRoutes(mcpDependencies(), McpTools(deps))
                boardRoutes(deps)
            }
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
        attachmentTickets = tickets,
        sessions = sessions,
        users = users,
        impersonations = Impersonations(),
        subscriptions = SubscriptionStore(database),
        reads = ReadStore(database),
    )
}
