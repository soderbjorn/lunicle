/**
 * The mailbox-proof lifecycle, at the level the security properties live.
 *
 * Every assertion below is about something that fails *silently* if it is wrong.
 * A missing attempt cap does not break a single test of the happy path; neither
 * does a code that stays redeemable after it is spent, nor one purpose's code
 * being accepted for another. The whole reason LNL-76 exists as its own piece,
 * built before either caller, is that these properties are invisible from the
 * features that depend on them.
 *
 * Time is injected throughout and nothing here sleeps. The clock is a `var` the
 * tests move by hand, which is what lets "expired without the sweep having run"
 * be a case at all — see [EmailCodeService]'s `now`.
 *
 * The sender is real: an [ResendEmailTransport] over Ktor's `MockEngine`, so the whole
 * path down to the socket runs and the request body is asserted against. That is
 * `McpSendEmailTest.capturingSender`'s trick, and it is the only way the
 * plain-text alternative and the code-in-the-subject can be checked at all.
 *
 * @see EmailCodeService
 * @see EmailCodes.sq
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.lunicle.clientserver.AuthProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One message the fake Resend received. */
private data class SentMail(
    val to: List<String>,
    val subject: String,
    val html: String,
    val text: String?,
)

class EmailCodeTest {
    private val file: File = Files.createTempFile("lunicle-email-codes", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)

    /** Moved by hand. Nothing in these tests waits for anything. */
    private var clock: Long = 1_700_000_000_000

    private val sent = mutableListOf<SentMail>()

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── The happy path, and what the mail actually contains ──────────────────

    @Test
    fun `an issued code redeems once and is then gone`(): Unit = runBlocking {
        val service = service()
        assertIs<EmailCodeService.IssueOutcome.Sent>(
            service.issue("alice@example.com", EmailCodePurpose.SIGN_IN),
        )

        val redeemed = service.redeem("alice@example.com", EmailCodePurpose.SIGN_IN, code())
        assertIs<EmailCodeRedemption.Redeemed>(redeemed, "The code the user was mailed was refused.")
        assertEquals("alice@example.com", redeemed.address)
        assertNull(redeemed.userId, "A sign-in code has no account behind it yet.")

        // Single use. The second attempt must be refused, and refused as the
        // same anonymous "no" a wrong code gets — see EmailCodeRedemption.
        assertIs<EmailCodeRedemption.Refused>(
            service.redeem("alice@example.com", EmailCodePurpose.SIGN_IN, code()),
            "A spent code was accepted a second time.",
        )
    }

    /**
     * The subject carries the code, and a plain-text part exists.
     *
     * Both are LNL-76 decisions with a stated reason and neither has any other
     * test that would notice their absence: the code would still work if it were
     * body-only, and the mail would still send if it were HTML-only. They would
     * simply be worse mail, which no assertion elsewhere can see.
     */
    @Test
    fun `the mail puts the code in the subject and carries a plain-text part`(): Unit = runBlocking {
        service().issue("alice@example.com", EmailCodePurpose.SIGN_IN)

        val mail = sent.single()
        assertEquals(listOf("alice@example.com"), mail.to)
        assertTrue(
            mail.subject.contains(code()),
            "The code is not in the subject line, so it cannot be read from a notification preview: " +
                "\"${mail.subject}\"",
        )
        val text = assertNotNull(mail.text, "The mail carried no plain-text alternative.")
        assertTrue(text.contains(code()), "The plain-text part does not carry the code.")
        assertTrue(!text.contains('<'), "The plain-text part contains markup: $text")
        assertTrue(mail.html.contains(code()), "The HTML part does not carry the code.")
    }

    /**
     * A code with a leading zero is minted and accepted at its full six digits.
     *
     * The one case a naive implementation gets wrong for free: `nextInt` produces
     * `123`, `toString` keeps it at three digits, and the user is mailed
     * something they cannot type back — while the space silently shrinks by the
     * fraction of values with leading zeros. Rather than fish for one at random,
     * this pins the property that codes are always six characters over enough
     * draws that a missing pad would show.
     */
    @Test
    fun `every issued code is six digits`(): Unit = runBlocking {
        val service = service()
        repeat(200) { i ->
            service.issue("user$i@example.com", EmailCodePurpose.SIGN_IN)
        }
        sent.forEach { mail ->
            val code = codeIn(mail.subject)
            assertEquals(6, code.length, "A code was minted with fewer than six digits: \"$code\"")
            assertTrue(code.all { it.isDigit() }, "A code was minted with a non-digit in it: \"$code\"")
        }
        // And one of them can actually be redeemed at the value that was mailed,
        // which is what would break if the pad were applied only to the display.
        val last = sent.last()
        assertIs<EmailCodeRedemption.Redeemed>(
            service.redeem("user199@example.com", EmailCodePurpose.SIGN_IN, codeIn(last.subject)),
        )
    }

    // ── The refusals ─────────────────────────────────────────────────────────

    @Test
    fun `a wrong code is refused and leaves the real one usable`(): Unit = runBlocking {
        val service = service()
        service.issue("alice@example.com", EmailCodePurpose.SIGN_IN)

        assertIs<EmailCodeRedemption.Refused>(
            service.redeem("alice@example.com", EmailCodePurpose.SIGN_IN, wrongCode()),
        )
        assertIs<EmailCodeRedemption.Redeemed>(
            service.redeem("alice@example.com", EmailCodePurpose.SIGN_IN, code()),
            "One wrong guess killed a code that should have survived four more.",
        )
    }

    /**
     * The attempt cap, which is the single most important assertion in this file.
     *
     * Without it a six-digit code is a 10^6 online guess a script finishes in
     * minutes, and *nothing else in this suite fails*. The fifth wrong attempt
     * must kill the code outright — not merely refuse that guess — so the correct
     * code presented afterwards is refused too.
     */
    @Test
    fun `the fifth wrong attempt kills the code`(): Unit = runBlocking {
        val service = service()
        service.issue("alice@example.com", EmailCodePurpose.SIGN_IN)

        repeat(5) { attempt ->
            assertIs<EmailCodeRedemption.Refused>(
                service.redeem("alice@example.com", EmailCodePurpose.SIGN_IN, wrongCode()),
                "Wrong guess ${attempt + 1} was accepted.",
            )
        }
        assertIs<EmailCodeRedemption.Refused>(
            service.redeem("alice@example.com", EmailCodePurpose.SIGN_IN, code()),
            "The attempt cap did not kill the code: the right one still worked after five wrong ones.",
        )
    }

    /**
     * Expiry is enforced by the lookup, not by the sweep.
     *
     * The clock moves past fifteen minutes and `deleteExpired` is deliberately
     * **not** called, because that is the production case: the sweep runs at
     * startup and a long-lived server never reaches it. Sessions.kt documents
     * exactly this trap for itself; this asserts that `email_codes` does not
     * inherit it.
     */
    @Test
    fun `a code expires without the sweep having run`(): Unit = runBlocking {
        val service = service()
        service.issue("alice@example.com", EmailCodePurpose.SIGN_IN)

        clock += 15L * 60 * 1000 + 1
        assertIs<EmailCodeRedemption.Refused>(
            service.redeem("alice@example.com", EmailCodePurpose.SIGN_IN, code()),
            "An expired code was honoured because nothing had swept it yet.",
        )
    }

    @Test
    fun `the sweep reclaims expired rows`(): Unit = runBlocking {
        val service = service()
        service.issue("alice@example.com", EmailCodePurpose.SIGN_IN)
        service.issue("bob@example.com", EmailCodePurpose.SIGN_IN)
        assertEquals(0L, service.deleteExpired(), "Live codes were swept.")

        clock += 15L * 60 * 1000 + 1
        assertEquals(2L, service.deleteExpired(), "The sweep did not reclaim both expired codes.")
        assertEquals(0L, service.size(), "The sweep reported two but left rows behind.")
    }

    // ── Superseding, and purposes ────────────────────────────────────────────

    @Test
    fun `issuing again invalidates the code in the older mail`(): Unit = runBlocking {
        val service = service()
        service.issue("alice@example.com", EmailCodePurpose.SIGN_IN)
        val first = codeIn(sent.first().subject)
        service.issue("alice@example.com", EmailCodePurpose.SIGN_IN)
        val second = codeIn(sent.last().subject)

        // Drawn at random, so on rare runs the two agree. The property under test
        // is only meaningful when they differ.
        if (first == second) return@runBlocking

        assertIs<EmailCodeRedemption.Refused>(
            service.redeem("alice@example.com", EmailCodePurpose.SIGN_IN, first),
            "A superseded code from an older mail was still accepted.",
        )
        assertIs<EmailCodeRedemption.Redeemed>(
            service.redeem("alice@example.com", EmailCodePurpose.SIGN_IN, second),
        )
    }

    /**
     * Two purposes for one address coexist, and neither is redeemable as the
     * other.
     *
     * This is the property that stops LNL-71's confirmation mail — sent to an
     * address the user has merely *claimed* — from being a way into the account it
     * was being attached to. If `purpose` were decoration rather than part of the
     * key, the first half of this test would pass and the second would silently
     * be a takeover primitive.
     */
    @Test
    fun `two purposes for one address coexist and do not substitute`(): Unit = runBlocking {
        val service = service()
        val alice = user("gh-alice", "Alice")

        service.issue("alice@example.com", EmailCodePurpose.SIGN_IN)
        val signIn = codeIn(sent.last().subject)
        service.issue("alice@example.com", EmailCodePurpose.EMAIL_CHANGE, userId = alice.id)
        val change = codeIn(sent.last().subject)

        if (signIn != change) {
            assertIs<EmailCodeRedemption.Refused>(
                service.redeem("alice@example.com", EmailCodePurpose.SIGN_IN, change),
                "A code issued to confirm an address was accepted as a sign-in.",
            )
        }

        // Issuing the second did not displace the first: both are still live.
        assertIs<EmailCodeRedemption.Redeemed>(
            service.redeem("alice@example.com", EmailCodePurpose.SIGN_IN, signIn),
            "Issuing an address-change code displaced the outstanding sign-in code.",
        )
        val redeemed = service.redeem("alice@example.com", EmailCodePurpose.EMAIL_CHANGE, change)
        assertIs<EmailCodeRedemption.Redeemed>(redeemed)
        assertEquals(alice.id, redeemed.userId, "The account the proof was requested for was not handed back.")
    }

    // ── Sending, and its failure ─────────────────────────────────────────────

    /**
     * A failed send leaves nothing redeemable behind.
     *
     * The order this pins — send, then store — is the whole of why the failure
     * mode is a user asking for another code rather than one staring at a mail
     * that never arrived holding a code the server does not recognise.
     */
    @Test
    fun `a refused send leaves no live code`(): Unit = runBlocking {
        val service = service(sender = refusingSender())
        assertIs<EmailCodeService.IssueOutcome.SendFailed>(
            service.issue("alice@example.com", EmailCodePurpose.SIGN_IN),
        )
        // Nothing was mailed, so nothing could be guessed — but a row would still
        // be a live credential for anyone who could grind six digits.
        assertEquals(0L, service.size(), "A failed send stored a code anyway.")
    }

    @Test
    fun `a deployment with no mail configured stores nothing and says so`(): Unit = runBlocking {
        val service = service(sender = null)
        assertIs<EmailCodeService.IssueOutcome.NotConfigured>(
            service.issue("alice@example.com", EmailCodePurpose.SIGN_IN),
        )
        assertEquals(0L, service.size())
        assertTrue(!service.isAvailable, "A senderless service claims it can send.")
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private fun service(sender: ResendEmailTransport? = capturingSender()) =
        EmailCodeService(database, sender, "https://issues.example.com") { clock }

    /** The code from the most recent mail. */
    private fun code(): String = codeIn(sent.last().subject)

    /** A code that is definitely not [code], for the refusal paths. */
    private fun wrongCode(): String {
        val real = code()
        return if (real == "000000") "111111" else "000000"
    }

    /** The six digits out of a subject line. */
    private fun codeIn(subject: String): String =
        Regex("\\d{6}").find(subject)?.value
            ?: error("No six-digit code in the subject line: \"$subject\"")

    private suspend fun user(providerId: String, name: String): UserRecord =
        users.upsert(ProviderIdentity(AuthProvider.GITHUB, providerId, name, "$providerId@example.com"))

    /** A real [ResendEmailTransport] whose transport records and answers 200. See McpSendEmailTest. */
    private fun capturingSender(): ResendEmailTransport = ResendEmailTransport(
        config = ResendConfig(apiKey = "test-key", from = "Lunicle <noreply@example.com>"),
        httpClient = HttpClient(
            MockEngine { request ->
                val payload = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
                sent += SentMail(
                    to = payload["to"]!!.jsonArray.map { it.jsonPrimitive.content },
                    subject = payload["subject"]!!.jsonPrimitive.content,
                    html = payload["html"]!!.jsonPrimitive.content,
                    text = payload["text"]?.jsonPrimitive?.content,
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
