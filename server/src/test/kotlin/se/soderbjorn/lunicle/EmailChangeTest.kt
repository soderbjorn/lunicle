/**
 * An address change has to be proved, and the old route can no longer set one.
 *
 * The hole LNL-71 closed is the kind that passes every test written before it:
 * `POST /api/user/email` did exactly what it said, and what it said was
 * "unverified". So the assertions that matter here are mostly *refusals* — the
 * things that must now be impossible, none of which any earlier test could have
 * noticed becoming possible.
 *
 * Through the real routes with real session cookies, for AdminSettingsTest's
 * reason: a store-level test would pass just as happily against a route that
 * never consulted the session at all. The mail goes through a real [ResendEmailTransport]
 * over `MockEngine`, so the code under test is the code that ships and the code
 * the user would type is readable from the captured subject line.
 *
 * The code lifecycle's own edge cases — the attempt cap, superseding, the
 * zero-pad — belong to EmailCodeTest and are not repeated here.
 *
 * @see authRoutes
 * @see EmailCodeService
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.ConfirmEmailRequest
import se.soderbjorn.lunicle.clientserver.ImpersonateRequest
import se.soderbjorn.lunicle.clientserver.RequestEmailChangeRequest
import se.soderbjorn.lunicle.clientserver.SessionState
import se.soderbjorn.lunicle.clientserver.SetEmailRequest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class EmailChangeTest {
    private val file: File = Files.createTempFile("lunicle-email-change", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)
    private val sessions = SessionStore(database)
    private val impersonations = Impersonations()

    /** Every mail the fake Resend received: recipient, subject, body. */
    private val sent = mutableListOf<Triple<String, String, String>>()

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── The hole itself ──────────────────────────────────────────────────────

    /**
     * The whole point of the ticket, as one assertion.
     *
     * This is the request that used to succeed. If it ever succeeds again, every
     * other protection in this file is decoration: an attacker sets their address
     * to the victim's, and once LNL-73 keys accounts on e-mail the victim's next
     * sign-in lands in the attacker's row.
     */
    @Test
    fun `the old route can no longer set an address`(): Unit = runBlocking {
        val user = user("gh-alice", "Alice")
        val session = sessions.create(user.id)

        withRoutes { client ->
            val response = client.post(ApiRoutes.USER_EMAIL) {
                cookie(SESSION_COOKIE, session)
                contentType(ContentType.Application.Json)
                setBody(SetEmailRequest("victim@example.com"))
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "An address was set with no verification — this is the account-takeover primitive LNL-71 removed.",
            )
        }
        assertNotEquals("victim@example.com", users.findById(user.id)?.email, "The unverified address was written.")
    }

    /**
     * Clearing survives, unproved and immediate.
     *
     * Giving a mailbox up establishes nothing, so requiring proof for it would be
     * ceremony charged to the one person it cannot protect — and it would leave a
     * user unable to stop mail arriving at an address they have lost.
     */
    @Test
    fun `clearing an address needs no proof`(): Unit = runBlocking {
        val user = user("gh-alice", "Alice", email = "alice@example.com")
        val session = sessions.create(user.id)

        withRoutes { client ->
            val state: SessionState = client.post(ApiRoutes.USER_EMAIL) {
                cookie(SESSION_COOKIE, session)
                contentType(ContentType.Application.Json)
                setBody(SetEmailRequest(null))
            }.body()
            assertNull(state.user?.email, "Clearing was refused, or did not take.")
        }
        assertNull(users.findById(user.id)?.email)
    }

    // ── The two-step change ──────────────────────────────────────────────────

    @Test
    fun `requesting a code changes nothing, and confirming it changes everything`(): Unit = runBlocking {
        val user = user("gh-alice", "Alice", email = "old@example.com")
        val session = sessions.create(user.id)

        withRoutes { client ->
            val pending: SessionState = client.requestChange(session, "new@example.com").body()
            assertEquals("new@example.com", pending.pendingEmail, "The request did not come back as pending.")
            assertEquals(
                "old@example.com",
                pending.user?.email,
                "Merely asking for a code moved the address — which is the bug, with an extra step.",
            )

            val confirmed: SessionState = client.confirm(session, codeFromMailTo("new@example.com")).body()
            assertEquals("new@example.com", confirmed.user?.email, "A confirmed address was not written.")
            assertTrue(confirmed.user?.isEmailVerified == true, "A proved address was not marked verified.")
            assertNull(confirmed.pendingEmail, "The pending change survived its own confirmation.")
        }
    }

    /**
     * The pending state is server-side, so it survives the dialog closing.
     *
     * LNL-71 requires this explicitly, and the reason is that going to read your
     * mail *is* leaving the screen. A second session for the same user stands in
     * for a reopened dialog — if the pending change were held anywhere in the
     * client, this would come back null.
     */
    @Test
    fun `a pending change is still there in a new session`(): Unit = runBlocking {
        val user = user("gh-alice", "Alice")
        val first = sessions.create(user.id)
        val second = sessions.create(user.id)

        withRoutes { client ->
            client.requestChange(first, "new@example.com")
            val state: SessionState = client.session(second).body()
            assertEquals(
                "new@example.com",
                state.pendingEmail,
                "The pending change did not survive, so closing the dialog to read the mail loses it.",
            )
        }
    }

    @Test
    fun `a wrong code is refused and the address does not move`(): Unit = runBlocking {
        val user = user("gh-alice", "Alice", email = "old@example.com")
        val session = sessions.create(user.id)

        withRoutes { client ->
            client.requestChange(session, "new@example.com")
            val real = codeFromMailTo("new@example.com")
            val wrong = if (real == "000000") "111111" else "000000"
            assertEquals(HttpStatusCode.BadRequest, client.confirm(session, wrong).status)
        }
        assertEquals("old@example.com", users.findById(user.id)?.email, "A wrong code moved the address.")
    }

    @Test
    fun `a code cannot be spent twice`(): Unit = runBlocking {
        val user = user("gh-alice", "Alice")
        val session = sessions.create(user.id)

        withRoutes { client ->
            client.requestChange(session, "new@example.com")
            val code = codeFromMailTo("new@example.com")
            assertEquals(HttpStatusCode.OK, client.confirm(session, code).status)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.confirm(session, code).status,
                "A spent code was accepted again.",
            )
        }
    }

    /**
     * A sign-in code is not an address-change code.
     *
     * The property that stops the confirmation mail — sent to an address the user
     * has merely *claimed* — from being usable as a way into the account it was
     * being attached to, and vice versa. Purpose is part of the storage key
     * precisely so this cannot work; if it ever does, LNL-74's sign-in endpoint
     * and this one become interchangeable.
     */
    @Test
    fun `a code issued for signing in is refused here`(): Unit = runBlocking {
        val user = user("gh-alice", "Alice")
        val session = sessions.create(user.id)
        val codes = emailCodes()

        withRoutes(codes) { client ->
            client.requestChange(session, "new@example.com")
            // A second code for the same address, under the other purpose. Both
            // are live; only one is redeemable here.
            codes.issue("new@example.com", EmailCodePurpose.SIGN_IN)
            val signInCode = sent.last().second.let { subject ->
                Regex("\\d{6}").find(subject)!!.value
            }
            val changeCode = codeFromMailTo("new@example.com", index = 0)
            if (signInCode == changeCode) return@withRoutes

            assertEquals(
                HttpStatusCode.BadRequest,
                client.confirm(session, signInCode).status,
                "A sign-in code confirmed an address change.",
            )
        }
    }

    @Test
    fun `cancelling drops the pending change`(): Unit = runBlocking {
        val user = user("gh-alice", "Alice")
        val session = sessions.create(user.id)

        withRoutes { client ->
            client.requestChange(session, "new@example.com")
            val code = codeFromMailTo("new@example.com")
            val state: SessionState = client.post(ApiRoutes.USER_EMAIL_CANCEL) {
                cookie(SESSION_COOKIE, session)
            }.body()
            assertNull(state.pendingEmail, "Cancelling left the change pending.")
            assertEquals(
                HttpStatusCode.BadRequest,
                client.confirm(session, code).status,
                "A cancelled code still worked.",
            )
        }
    }

    // ── Impersonation, and the old address ───────────────────────────────────

    /**
     * An admin wearing somebody's face may not redirect their mail.
     *
     * Worth an explicit refusal rather than inheriting whatever `caller.effective`
     * happens to do — which is what the old route did, silently allowing it.
     * Once e-mail is the account key, redirecting where someone's mail goes is
     * redirecting their account.
     */
    @Test
    fun `an impersonating admin cannot change the impersonated user's address`(): Unit = runBlocking {
        val admin = user("gh-admin", "Admin")
        val victim = user("gh-victim", "Victim")
        val session = sessions.create(admin.id)
        assertTrue(admin.isInstanceAdmin, "The first user seeded was not the admin; the fixture is wrong.")

        withRoutes { client ->
            client.post(ApiRoutes.IMPERSONATE) {
                cookie(SESSION_COOKIE, session)
                contentType(ContentType.Application.Json)
                setBody(ImpersonateRequest(victim.id))
            }
            assertEquals(
                HttpStatusCode.Forbidden,
                client.requestChange(session, "attacker@example.com").status,
                "An admin redirected somebody else's mail while wearing their face.",
            )
        }
        assertTrue(sent.none { it.first == "attacker@example.com" }, "A code was mailed on the refused path.")
    }

    /**
     * The address being replaced is told, because after the write it is the only
     * one that still reaches the person it was taken from.
     */
    @Test
    fun `the old address is notified that it has been replaced`(): Unit = runBlocking {
        val user = user("gh-alice", "Alice", email = "old@example.com")
        val session = sessions.create(user.id)

        withRoutes { client ->
            client.requestChange(session, "new@example.com")
            client.confirm(session, codeFromMailTo("new@example.com"))
        }
        val notice = sent.lastOrNull { it.first == "old@example.com" }
        assertTrue(notice != null, "The address that was replaced was never told.")
        assertTrue(
            notice.third.contains("new@example.com"),
            "The notice does not say what the address was changed to, which is what its reader needs.",
        )
    }

    @Test
    fun `a first address produces no notice, because there is nobody to tell`(): Unit = runBlocking {
        val user = user("gh-alice", "Alice")
        val session = sessions.create(user.id)

        withRoutes { client ->
            client.requestChange(session, "new@example.com")
            client.confirm(session, codeFromMailTo("new@example.com"))
        }
        assertEquals(1, sent.size, "Something beyond the code itself was mailed: ${sent.map { it.second }}")
    }

    // ── Rate limiting, and the send failure ──────────────────────────────────

    @Test
    fun `asking for codes too often is refused`(): Unit = runBlocking {
        val user = user("gh-alice", "Alice")
        val session = sessions.create(user.id)

        withRoutes { client ->
            repeat(3) { attempt ->
                assertEquals(
                    HttpStatusCode.OK,
                    client.requestChange(session, "new$attempt@example.com").status,
                    "Request ${attempt + 1} of 3 was refused.",
                )
            }
            val refused = client.requestChange(session, "again@example.com")
            assertEquals(HttpStatusCode.TooManyRequests, refused.status, "A fourth code was mailed inside the window.")
            assertTrue(
                refused.headers[HttpHeaders.RetryAfter] != null,
                "A 429 with no Retry-After leaves the client guessing.",
            )
        }
    }

    /**
     * A refused send is surfaced, and leaves nothing pending.
     *
     * Surfaced rather than swallowed because somebody is watching a spinner:
     * "check your mail" over a message that was never sent is the worst available
     * answer. Nothing pending because LNL-76 stores the code only after the send
     * succeeds — so the user retries and the retry works.
     */
    @Test
    fun `a refused send is reported and leaves nothing pending`(): Unit = runBlocking {
        val user = user("gh-alice", "Alice")
        val session = sessions.create(user.id)

        withRoutes(emailCodes(refusingSender())) { client ->
            assertEquals(HttpStatusCode.BadGateway, client.requestChange(session, "new@example.com").status)
            val state: SessionState = client.session(session).body()
            assertNull(state.pendingEmail, "A failed send left a pending change the user cannot complete.")
        }
    }

    @Test
    fun `a server with no mail configured says so rather than pretending`(): Unit = runBlocking {
        val user = user("gh-alice", "Alice")
        val session = sessions.create(user.id)

        withRoutes(emailCodes(sender = null)) { client ->
            assertEquals(HttpStatusCode.BadRequest, client.requestChange(session, "new@example.com").status)
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private suspend fun user(providerId: String, name: String, email: String? = null): UserRecord {
        val record = users.upsert(ProviderIdentity(AuthProvider.GITHUB, providerId, name, email))
        // Straight to the store, because the whole point of this ticket is that
        // the route will not do it. Unverified, which is what every address that
        // predates LNL-71 is.
        if (email != null) users.setEmail(record.id, email, isVerified = false)
        return users.findById(record.id)!!
    }

    private fun emailCodes(sender: ResendEmailTransport? = capturingSender()) =
        EmailCodeService(database, sender, "https://issues.example.com")

    private fun withRoutes(
        codes: EmailCodeService = emailCodes(),
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing {
                authRoutes(
                    config = OAuthConfig(google = null),
                    sessions = sessions,
                    users = users,
                    impersonations = impersonations,
                    emailCodes = codes,
                    notifications = NotificationDispatcher(users, capturingSender()),
                )
            }
        }
        block(createClient { install(ClientContentNegotiation) { json() } })
    }

    private suspend fun HttpClient.requestChange(session: String, email: String) =
        post(ApiRoutes.USER_EMAIL_REQUEST) {
            cookie(SESSION_COOKIE, session)
            contentType(ContentType.Application.Json)
            setBody(RequestEmailChangeRequest(email))
        }

    private suspend fun HttpClient.confirm(session: String, code: String) =
        post(ApiRoutes.USER_EMAIL_CONFIRM) {
            cookie(SESSION_COOKIE, session)
            contentType(ContentType.Application.Json)
            setBody(ConfirmEmailRequest(code))
        }

    private suspend fun HttpClient.session(session: String) =
        get(ApiRoutes.SESSION) { cookie(SESSION_COOKIE, session) }

    /** The six digits out of the subject of the [index]-th mail sent to [address]. */
    private fun codeFromMailTo(address: String, index: Int = 0): String {
        val subject = sent.filter { it.first == address }.getOrNull(index)?.second
            ?: error("No mail was sent to <$address>. Sent: ${sent.map { it.first }}")
        return Regex("\\d{6}").find(subject)?.value ?: error("No code in the subject: \"$subject\"")
    }

    /** A real [ResendEmailTransport] whose transport records and answers 200. See McpSendEmailTest. */
    private fun capturingSender(): ResendEmailTransport = ResendEmailTransport(
        config = ResendConfig(apiKey = "test-key", from = "Lunicle <noreply@example.com>"),
        httpClient = HttpClient(
            MockEngine { request ->
                val payload = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
                sent += Triple(
                    payload["to"]!!.jsonArray.first().jsonPrimitive.content,
                    payload["subject"]!!.jsonPrimitive.content,
                    payload["html"]!!.jsonPrimitive.content,
                )
                respond(
                    content = """{"id":"test"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ClientContentNegotiation) { json() }
        },
    )

    /** A sender whose provider says no. See McpSendEmailTest. */
    private fun refusingSender(): ResendEmailTransport = ResendEmailTransport(
        config = ResendConfig(apiKey = "test-key", from = "Lunicle <noreply@example.com>"),
        httpClient = HttpClient(
            MockEngine {
                respond(
                    content = """{"name":"domain_not_verified"}""",
                    status = HttpStatusCode.Forbidden,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ClientContentNegotiation) { json() }
        },
    )
}
