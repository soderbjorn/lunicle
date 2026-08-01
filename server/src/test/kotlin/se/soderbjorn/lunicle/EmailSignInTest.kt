/**
 * Signing in with a mailed code.
 *
 * Two properties carry most of the weight here, and neither is visible from a
 * happy-path click-through:
 *
 *  - **The request endpoint is not an oracle.** It is unauthenticated, so a
 *    response that differed between "that address has an account" and "it does
 *    not" would let anybody enumerate the instance's users by typing. The
 *    assertion is byte-for-byte sameness across four outcomes, including a send
 *    that failed.
 *  - **A returning user lands in the row they already have.** This is LNL-73's
 *    re-key doing its job through this endpoint, and getting it wrong produces a
 *    *working sign-in* into an empty second account — which looks fine until
 *    somebody notices their issues are gone.
 *
 * The code lifecycle's own edge cases — the attempt cap, expiry, superseding —
 * belong to EmailCodeTest and are not repeated. What is repeated is the purpose
 * separation, because the direction matters and each endpoint has to refuse the
 * other's codes.
 *
 * @see authRoutes
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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
import se.soderbjorn.lunicle.clientserver.EmailSignInRedeemRequest
import se.soderbjorn.lunicle.clientserver.EmailSignInRequest
import se.soderbjorn.lunicle.clientserver.SessionState
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

class EmailSignInTest {
    private val file: File = Files.createTempFile("lunicle-email-signin", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)
    private val sessions = SessionStore(database)
    /**
     * Armed, for one assertion: that the owner's own sign-in response already
     * reports the impersonation affordance the seat just gave them (LNL-42). The
     * gate is a term of `canImpersonate`, so an unarmed fixture would report false
     * for a reason that has nothing to do with the seat this file is about.
     */
    private val impersonation = OwnerImpersonation(isEnabled = true)
    private val instanceSettings = InstanceSettingsStore(database)

    /** Every mail the fake Resend received: recipient and subject. */
    private val sent = mutableListOf<Pair<String, String>>()

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── The happy path ───────────────────────────────────────────────────────

    @Test
    fun `a mailed code buys a session`(): Unit = runBlocking {
        withRoutes { client ->
            assertEquals(HttpStatusCode.NoContent, client.request("alice@example.com").status)
            val response = client.redeem("alice@example.com", code())
            assertEquals(HttpStatusCode.OK, response.status)

            val state: SessionState = response.body()
            val user = assertNotNull(state.user, "A redeemed code produced no signed-in user.")
            assertEquals("alice@example.com", user.email)
            assertTrue(user.isEmailVerified, "A redeemed code did not mark the address verified.")
            assertTrue(
                response.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("$SESSION_COOKIE=") },
                "No session cookie was set, so the sign-in did not actually take.",
            )
        }
    }

    /**
     * A differently-cased address is the same account.
     *
     * Normalization has to happen on both endpoints and match, or a code issued
     * for `Alice@…` is not findable under `alice@…` and the sign-in simply never
     * works for anybody who capitalises their own address.
     */
    @Test
    fun `case and whitespace do not matter across the two calls`(): Unit = runBlocking {
        withRoutes { client ->
            client.request("  Alice@Example.COM ")
            assertEquals(
                HttpStatusCode.OK,
                client.redeem("alice@example.com", code()).status,
                "A code requested for one spelling could not be redeemed with another.",
            )
        }
    }

    /**
     * The whole point of doing LNL-73 first: a returning Google user is reunited
     * with their own row rather than given a second one.
     *
     * Under the old `(provider, provider_id)` key this created a fresh account —
     * a *working* sign-in into an empty row, which is the failure nobody notices
     * until their issues are missing.
     */
    @Test
    fun `a returning Google user lands in their existing row`(): Unit = runBlocking {
        val existing = users.upsert(
            ProviderIdentity(AuthProvider.GOOGLE, "sub-1", "Alice", "alice@example.com"),
        )
        withRoutes { client ->
            client.request("alice@example.com")
            val state: SessionState = client.redeem("alice@example.com", code()).body()
            assertEquals(
                existing.id,
                state.user?.id,
                "Signing in by code created a second account for somebody who already had one.",
            )
        }
        // And it did not rewrite how that account came to exist.
        assertEquals(AuthProvider.GOOGLE, users.findById(existing.id)?.provider)
    }

    /**
     * Asking for a code is also registration, and on a fresh instance the first one
     * through becomes the instance administrator.
     *
     * The same rule Google sign-in has always had, and it has to survive being
     * reached by a second route — it lives as a subquery inside the INSERT
     * precisely so that it cannot be reimplemented differently per entry point.
     */
    @Test
    fun `the first person to redeem a code on a fresh instance is the admin`(): Unit = runBlocking {
        withRoutes { client ->
            client.request("first@example.com")
            val first: SessionState = client.redeem("first@example.com", code()).body()
            assertTrue(first.user?.isSysAdmin == true, "The first account was not made the instance admin.")

            client.request("second@example.com")
            val second: SessionState = client.redeem("second@example.com", code()).body()
            assertFalse(second.user?.isSysAdmin == true, "A second account became an admin too.")
        }
    }

    /**
     * ...and is the **owner** by the time that same response is written.
     *
     * Ownership is not on the account row — it is `instance_settings.owner_user_id`,
     * because there is exactly one owner — so becoming an administrator does not seat
     * anybody. It used to be seated only at boot, and on a fresh instance that boot
     * happens before there is anybody to seat: the first person in became an
     * administrator and the seat stayed empty until some unrelated restart. Everything
     * that asks `ownsInstance` was missing meanwhile — impersonation, handing the
     * instance over, the project order, the cross-project delete.
     *
     * The assertion is deliberately on the **response**, not just the store. Nothing
     * re-fetches the session after a sign-in, so a seat taken a moment too late would
     * still leave the first owner looking at an account menu with no Impersonate in it
     * until they reloaded — which is the shape of LNL-42, one layer down.
     */
    @Test
    fun `the first person to redeem a code is seated as the owner, in that same response`(): Unit = runBlocking {
        withRoutes { client ->
            assertNull(
                instanceSettings.current().ownerUserId,
                "Precondition: a fresh instance has nobody seated.",
            )

            client.request("first@example.com")
            val first: SessionState = client.redeem("first@example.com", code()).body()

            val seated = assertNotNull(
                instanceSettings.current().ownerUserId,
                "The first sign-in left the instance ownerless.",
            )
            assertEquals(first.user?.id, seated, "Somebody other than the person who signed in was seated.")
            assertTrue(
                first.canImpersonate,
                "The owner's own sign-in response did not report the powers it had just given them.",
            )

            // ...and the second person through changes nothing, which is the half that
            // would make this a way in rather than a bootstrap.
            client.request("second@example.com")
            val second: SessionState = client.redeem("second@example.com", code()).body()
            assertEquals(
                seated,
                instanceSettings.current().ownerUserId,
                "A later sign-in moved the seat.",
            )
            assertFalse(second.canImpersonate, "A second account was handed the instance.")
        }
    }

    // ── The oracle, which is the reason this endpoint says nothing ───────────

    /**
     * Four different outcomes, one indistinguishable response.
     *
     * Unknown address, known address, send refused, no mail configured at all. If
     * any of these can be told apart from outside, an unauthenticated stranger can
     * enumerate who has an account on an instance whose projects may be private.
     *
     * Compared on status *and* body, because a difference in either is enough —
     * a distinguishing error string is exactly the shape this leaks in.
     */
    @Test
    fun `the request endpoint cannot be used to ask whether an account exists`(): Unit = runBlocking {
        users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "sub-1", "Known", "known@example.com"))

        val answers = mutableListOf<Pair<HttpStatusCode, String>>()
        withRoutes { client ->
            answers += client.request("known@example.com").let { it.status to it.bodyAsText() }
            answers += client.request("unknown@example.com").let { it.status to it.bodyAsText() }
        }
        withRoutes(emailCodes(refusingSender())) { client ->
            answers += client.request("known@example.com").let { it.status to it.bodyAsText() }
        }
        withRoutes(emailCodes(sender = null)) { client ->
            answers += client.request("known@example.com").let { it.status to it.bodyAsText() }
        }

        assertEquals(
            1,
            answers.toSet().size,
            "The request endpoint answers differently depending on what happened, which makes it an " +
                "account-existence oracle: $answers",
        )
        assertEquals(HttpStatusCode.NoContent, answers.first().first)
    }

    @Test
    fun `a malformed address is refused, because that is a fact about the request`(): Unit = runBlocking {
        withRoutes { client ->
            assertEquals(HttpStatusCode.BadRequest, client.request("not an address").status)
            assertEquals(HttpStatusCode.BadRequest, client.request("  ").status)
        }
    }

    /**
     * A wrong code and a never-issued code get the same refusal.
     *
     * Otherwise the redeem endpoint gives away everything the request endpoint's
     * silence bought: "no code was issued for that address" is "no code was
     * requested for that address", which is one guess away from the account
     * question.
     */
    @Test
    fun `a wrong code and a code that was never issued are the same refusal`(): Unit = runBlocking {
        withRoutes { client ->
            client.request("alice@example.com")
            val real = code()
            val wrong = if (real == "000000") "111111" else "000000"

            val wrongCode = client.redeem("alice@example.com", wrong)
            val noCode = client.redeem("nobody@example.com", real)
            assertEquals(HttpStatusCode.BadRequest, wrongCode.status)
            assertEquals(
                wrongCode.status to wrongCode.bodyAsText(),
                noCode.status to noCode.bodyAsText(),
                "A code for an address nobody asked about is refused differently from a wrong one.",
            )
        }
    }

    // ── Purposes do not cross ────────────────────────────────────────────────

    /**
     * An address-change code cannot sign anybody in.
     *
     * The direction that matters most: LNL-71 mails a code to an address a
     * signed-in user has merely *claimed*. If this endpoint accepted it, typing a
     * victim's address into your own profile would mail you a working sign-in code
     * for their mailbox — and if they already have an account, LNL-73's
     * find-or-create would hand you their row.
     */
    @Test
    fun `an address-change code is refused as a sign-in`(): Unit = runBlocking {
        val user = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "sub-1", "Alice", "alice@example.com"))
        val codes = emailCodes()
        codes.issue("victim@example.com", EmailCodePurpose.EMAIL_CHANGE, userId = user.id)
        val changeCode = codeIn(sent.last().second)

        withRoutes(codes) { client ->
            assertEquals(
                HttpStatusCode.BadRequest,
                client.redeem("victim@example.com", changeCode).status,
                "A confirmation code was accepted as a sign-in — this is an account takeover.",
            )
        }
    }

    // ── Rate limiting ────────────────────────────────────────────────────────

    /**
     * One client cannot walk a list of addresses.
     *
     * The client key is what stops a single host spraying codes at many targets —
     * each of which is a real mail, from a verified domain, to somebody who did
     * not ask.
     */
    @Test
    fun `one client is refused after spending its budget across many addresses`(): Unit = runBlocking {
        withRoutes { client ->
            repeat(5) { attempt ->
                assertEquals(
                    HttpStatusCode.NoContent,
                    client.request("target$attempt@example.com").status,
                    "Request ${attempt + 1} of 5 was refused.",
                )
            }
            val refused = client.request("target-fresh@example.com")
            assertEquals(
                HttpStatusCode.TooManyRequests,
                refused.status,
                "One client mailed six different strangers inside the window.",
            )
            assertTrue(refused.headers[HttpHeaders.RetryAfter] != null, "A 429 with no Retry-After.")
        }
    }

    /**
     * And many clients cannot hammer one address.
     *
     * The address key. Each request here comes from a different forwarded client
     * address, so only the per-address bucket can refuse them — which is exactly
     * the botnet-on-one-target case the composed key exists for.
     *
     * ── And it refuses in SILENCE (LUS-13) ───────────────────────────────────
     *
     * This test used to assert a 429 on the sixth request, which was the review's
     * "weak activity oracle": ask about an address from a fresh client, and the
     * status code told you whether somebody had been asking about it lately. The
     * limiter's own documentation had claimed all along that this endpoint answers
     * with its ordinary response instead of refusing; the code did not, and this
     * test was pinning the code.
     *
     * So the sixth request is a 204 like every other, and what has to be asserted is
     * that **no mail went** — which is the only observable difference there should
     * be, and is not observable from outside at all.
     *
     * Pinned to one trusted proxy hop, or the forwarded addresses mean nothing and
     * this passes for the wrong reason: with the default of zero (LUS-31) every
     * request shares the engine's socket peer, so the *client* bucket would be doing
     * the refusing and the assertion would no longer be about the address at all.
     */
    @Test
    fun `one address is refused, silently, however many clients ask for it`(): Unit = runBlocking {
        withTrustedProxyHops(1) {
            withRoutes { client ->
                repeat(5) { attempt ->
                    assertEquals(
                        HttpStatusCode.NoContent,
                        client.request("victim@example.com", from = "203.0.113.$attempt").status,
                        "Request ${attempt + 1} of 5 was refused.",
                    )
                }
                assertEquals(5, sent.size, "The first five requests did not each send a code.")

                assertEquals(
                    HttpStatusCode.NoContent,
                    client.request("victim@example.com", from = "203.0.113.99").status,
                    "The status code told a fresh client that somebody has been asking about this " +
                        "address — which is the oracle this endpoint's silence exists to prevent.",
                )
                assertEquals(5, sent.size, "A sixth client mailed the same address inside the window.")
            }
        }
    }

    // ── Availability ─────────────────────────────────────────────────────────

    /**
     * The server says which methods it can perform, so no surface renders one it
     * cannot.
     *
     * `isSignInAvailable` used to mean "Google is configured" and must not any
     * more: a deployment with mail and no Google credentials can sign people in
     * perfectly well, and a picker gated on the old meaning would show it nothing.
     */
    @Test
    fun `availability is advertised per method`(): Unit = runBlocking {
        withRoutes { client ->
            val state: SessionState = client.get(ApiRoutes.SESSION).body()
            assertTrue(state.isEmailSignInAvailable, "A server that can send did not advertise e-mail sign-in.")
            assertFalse(state.isGoogleAvailable)
            assertTrue(state.isSignInAvailable, "A server with e-mail sign-in reported no sign-in at all.")
        }
        withRoutes(emailCodes(sender = null)) { client ->
            val state: SessionState = client.get(ApiRoutes.SESSION).body()
            assertFalse(state.isEmailSignInAvailable, "A server with no mail advertised e-mail sign-in.")
            assertFalse(state.isSignInAvailable, "A server with no method at all claimed one.")
        }
    }

    // ── E-mail sign-in withdrawn while mail stays on (LNL-92) ────────────────

    /**
     * A deployment can keep mail for notifications yet refuse e-mail as a way in.
     *
     * `isEmailAvailable = false` with a live sender is exactly the
     * `LUNICLE_EMAIL_SIGN_IN=off` shape: the transport is configured — so
     * notifications and address-change codes still work — but sign-in is meant to
     * be Google-only. Hiding the button is not enough; a stale client or a direct
     * POST must not be able to sign in, so the redeem endpoint itself has to
     * refuse, and it refuses outright rather than checking the code.
     */
    @Test
    fun `redeem is refused when e-mail sign-in is withdrawn`(): Unit = runBlocking {
        withRoutes(emailSignInAvailable = false) { client ->
            val response = client.redeem("alice@example.com", "000000")
            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "A redeem was entertained on a server where e-mail sign-in was withdrawn.",
            )
        }
    }

    /**
     * And no sign-in code is ever minted, so there is nothing to redeem even were
     * the refusal above bypassed.
     *
     * The request endpoint keeps its 204 — the silence it owes an unauthenticated
     * caller must not grow a new tell that says "sign-in is off here" — but it must
     * not send a sign-in code. The proof is that the capturing transport recorded
     * nothing.
     */
    @Test
    fun `no sign-in code is sent when e-mail sign-in is withdrawn`(): Unit = runBlocking {
        withRoutes(emailSignInAvailable = false) { client ->
            assertEquals(
                HttpStatusCode.NoContent,
                client.request("alice@example.com").status,
                "The request endpoint changed its answer, growing the oracle it must not have.",
            )
        }
        assertTrue(sent.isEmpty(), "A sign-in code went out on a server where e-mail sign-in was withdrawn.")
    }

    /** The session state stops advertising e-mail sign-in, so no surface offers it. */
    @Test
    fun `withdrawn e-mail sign-in is not advertised`(): Unit = runBlocking {
        withRoutes(emailSignInAvailable = false) { client ->
            val state: SessionState = client.get(ApiRoutes.SESSION).body()
            assertFalse(
                state.isEmailSignInAvailable,
                "A server that withdrew e-mail sign-in still advertised it.",
            )
        }
    }

    /**
     * The flag defaults to on and disables only on an explicit off-value.
     *
     * Default-on is the backward-compatible promise: a deployment that configured
     * mail before this flag existed keeps e-mail sign-in without touching anything.
     * An unrecognised value stays on rather than locking anyone out on a typo.
     */
    @Test
    fun `LUNICLE_EMAIL_SIGN_IN defaults on and disables only on an off-value`() {
        val property = "lunicle.emailSignIn"
        val saved = System.getProperty(property)
        try {
            System.clearProperty(property)
            assertTrue(resolveEmailSignInEnabled(), "Unset should default to enabled.")
            System.setProperty(property, "off")
            assertFalse(resolveEmailSignInEnabled(), "\"off\" should disable e-mail sign-in.")
            System.setProperty(property, "false")
            assertFalse(resolveEmailSignInEnabled(), "\"false\" should disable e-mail sign-in.")
            System.setProperty(property, "on")
            assertTrue(resolveEmailSignInEnabled(), "\"on\" should enable e-mail sign-in.")
            System.setProperty(property, "banana")
            assertTrue(resolveEmailSignInEnabled(), "An unrecognised value should stay at the safe default of on.")
        } finally {
            if (saved == null) System.clearProperty(property) else System.setProperty(property, saved)
        }
    }

    // ── Redemption is metered too (LUS-11) ───────────────────────────────────

    /**
     * The limiter guarded the endpoint that *issues* a code and not the one that
     * spends it, so a tight loop of wrong guesses ran unbounded.
     *
     * Brute force was never the risk — the issued row caps guesses at five. What
     * ran unbounded was a **targeted lockout**: poll redeem against a victim's
     * address and every code they request dies within milliseconds of arriving, by
     * the fifth wrong guess, while they see only the deliberately uniform "that code
     * is not right". Plus a server-wide stall, since every call runs a SQLite
     * transaction on the single-threaded dispatcher.
     *
     * Ten from one client in one window, then a 429 — which leaks nothing here, on a
     * route that already speaks plainly about e-mail sign-in to a caller mid-flow.
     */
    @Test
    fun `redeem is rate limited`(): Unit = runBlocking {
        withRoutes { client ->
            repeat(10) {
                assertEquals(
                    HttpStatusCode.BadRequest,
                    client.redeem("victim@example.com", "000000", from = "9.9.9.9").status,
                    "A wrong code stopped answering with the uniform refusal.",
                )
            }
            assertEquals(
                HttpStatusCode.TooManyRequests,
                client.redeem("victim@example.com", "000000", from = "9.9.9.9").status,
                "Redeem is still unmetered — a wrong-code loop can destroy every code a " +
                    "victim requests, and stalls the database while it does.",
            )
        }
    }

    /**
     * And it is keyed on the client too, so one host cannot walk a list of addresses.
     *
     * Run with one trusted proxy hop, which is what makes `X-Forwarded-For` mean
     * anything: the default is now zero (LUS-31), under which the header is ignored
     * and every request in a test shares the engine's constant socket peer. Two
     * clients that cannot be told apart cannot demonstrate a per-client bucket.
     */
    @Test
    fun `redeem's limit follows the client across addresses`(): Unit = runBlocking {
        withTrustedProxyHops(1) {
            withRoutes { client ->
                repeat(10) { client.redeem("victim$it@example.com", "000000", from = "9.9.9.9") }
                assertEquals(
                    HttpStatusCode.TooManyRequests,
                    client.redeem("someone-else@example.com", "000000", from = "9.9.9.9").status,
                    "One client walked a list of addresses at full speed.",
                )
                assertEquals(
                    HttpStatusCode.BadRequest,
                    client.redeem("someone-else@example.com", "000000", from = "8.8.8.8").status,
                    "An unrelated client was locked out by somebody else's spending.",
                )
            }
        }
    }

    /**
     * Run [block] with the trusted-hop count pinned, and restore it afterwards.
     *
     * The count is read per call through [resolveTrustedProxyHops], so a system
     * property is enough — and it has to be said out loud now that the default is
     * zero, which is the safe answer for a directly exposed server and the reason a
     * forwarded header means nothing unless a test asks for it to.
     */
    private inline fun withTrustedProxyHops(hops: Int, block: () -> Unit) {
        val property = "lunicle.trustedProxyHops"
        val saved = System.getProperty(property)
        try {
            System.setProperty(property, hops.toString())
            block()
        } finally {
            if (saved == null) System.clearProperty(property) else System.setProperty(property, saved)
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private fun emailCodes(sender: ResendEmailTransport? = capturingSender()) =
        EmailCodeService(database, sender, "https://issues.example.com")

    private fun withRoutes(
        codes: EmailCodeService = emailCodes(),
        // Defaults to tracking the sender, which is the usual case: a server that
        // can send offers e-mail sign-in. Passed explicitly to model
        // LUNICLE_EMAIL_SIGN_IN=off — a live sender (mail on for notifications)
        // with the sign-in flag withdrawn.
        emailSignInAvailable: Boolean = codes.isAvailable,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing {
                authRoutes(
                    // No Google, so `isSignInAvailable` can only be true because of
                    // the e-mail branch — which is the half that used to be
                    // unrepresentable.
                    config = OAuthConfig(google = null, isEmailAvailable = emailSignInAvailable),
                    sessions = sessions,
                    users = users,
                    impersonation = impersonation,
                    emailCodes = codes,
                    // Wired because the redeem seats an owner into a vacancy now, and
                    // an unwired store is the one shape that skips it — see
                    // AuthRoutes.seatOwnerIfVacant. Every other test here is
                    // unaffected: the seat is idempotent and reads nothing they assert.
                    instanceSettings = instanceSettings,
                )
            }
        }
        block(createClient { install(ClientContentNegotiation) { json() } })
    }

    /**
     * Ask for a code, optionally as a particular client.
     *
     * The forwarded header is how a test wears a different client address — the
     * resolver takes the entry one hop from the right, and Ktor's test engine
     * supplies a constant socket peer that every request would otherwise share.
     * See clientIdentity.
     */
    private suspend fun HttpClient.request(email: String, from: String? = null): HttpResponse =
        post(ApiRoutes.AUTH_EMAIL_REQUEST) {
            contentType(ContentType.Application.Json)
            if (from != null) header("X-Forwarded-For", from)
            setBody(EmailSignInRequest(email))
        }

    private suspend fun HttpClient.redeem(email: String, code: String, from: String? = null): HttpResponse =
        post(ApiRoutes.AUTH_EMAIL_REDEEM) {
            contentType(ContentType.Application.Json)
            if (from != null) header("X-Forwarded-For", from)
            setBody(EmailSignInRedeemRequest(email, code))
        }

    /** The code from the most recent mail. */
    private fun code(): String = codeIn(sent.last().second)

    private fun codeIn(subject: String): String =
        Regex("\\d{6}").find(subject)?.value ?: error("No code in the subject: \"$subject\"")

    /** A real [ResendEmailTransport] whose transport records and answers 200. See McpSendEmailTest. */
    private fun capturingSender(): ResendEmailTransport = ResendEmailTransport(
        config = ResendConfig(apiKey = "test-key", from = "Lunicle <noreply@example.com>"),
        httpClient = HttpClient(
            MockEngine { request ->
                val payload = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
                sent += payload["to"]!!.jsonArray.first().jsonPrimitive.content to
                    payload["subject"]!!.jsonPrimitive.content
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
