package se.soderbjorn.lunicle

import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.Session
import kotlinx.coroutines.runBlocking
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The transport seam LNL-92 added: the environment-to-transport resolver, the
 * shape of the SMTP message, and how an SMTP failure is mapped.
 *
 * The resolver is tested through [chooseEmailTransport], which is a pure function
 * of an [EmailEnv] — no System properties, no network — so precedence, half-config
 * and the both-configured tie-break are asserted directly, warnings included.
 *
 * The SMTP send is tested through the two seams [SmtpEmailTransport] exposes for
 * exactly this: [buildMimeMessage] for the wire shape, and an injected
 * [SmtpDispatch] for the last inch, so a message can be captured or made to fail
 * without a live server — the same trick the Resend tests play with `MockEngine`.
 */
class EmailTransportTest {

    // ── Resolver: precedence and unconfigured ────────────────────────────────

    @Test
    fun `only Resend configured resolves to Resend`() {
        val (choice, warnings) = choose(env(resendApiKey = "re_key", from = FROM))
        val resend = assertIs<EmailTransportChoice.Resend>(choice)
        assertEquals("re_key", resend.config.apiKey)
        assertEquals(FROM, resend.config.from)
        assertTrue(warnings.isEmpty(), "a clean Resend config warns about nothing")
    }

    @Test
    fun `only SMTP configured resolves to SMTP with defaults`() {
        val (choice, warnings) = choose(
            env(from = FROM, smtpHost = "smtp.example.com", smtpUsername = "u", smtpPassword = "p"),
        )
        val smtp = assertIs<EmailTransportChoice.Smtp>(choice)
        assertEquals("smtp.example.com", smtp.config.host)
        assertEquals(DEFAULT_SMTP_PORT, smtp.config.port)
        assertEquals(SmtpTls.STARTTLS, smtp.config.tls)
        assertEquals(FROM, smtp.config.from)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `nothing configured resolves to None without warning`() {
        val (choice, warnings) = choose(env())
        assertIs<EmailTransportChoice.None>(choice)
        assertTrue(warnings.isEmpty(), "email off is a valid state, not a misconfiguration")
    }

    @Test
    fun `SMTP_PORT and SMTP_TLS are honoured`() {
        val (choice, _) = choose(
            env(
                from = FROM,
                smtpHost = "smtp.example.com",
                smtpUsername = "u",
                smtpPassword = "p",
                smtpPort = "465",
                smtpTls = "tls",
            ),
        )
        val smtp = assertIs<EmailTransportChoice.Smtp>(choice)
        assertEquals(465, smtp.config.port)
        assertEquals(SmtpTls.IMPLICIT, smtp.config.tls)
    }

    // ── Resolver: half-configuration is a mistake, treated as absent ──────────

    @Test
    fun `Resend key without a From is half-configured and off`() {
        val (choice, warnings) = choose(env(resendApiKey = "re_key"))
        assertIs<EmailTransportChoice.None>(choice)
        assertTrue(warnings.single().contains("LUNICLE_EMAIL_FROM"))
    }

    @Test
    fun `SMTP host without credentials is half-configured and off`() {
        val (choice, warnings) = choose(env(from = FROM, smtpHost = "smtp.example.com"))
        assertIs<EmailTransportChoice.None>(choice)
        val warning = warnings.single()
        assertTrue(warning.contains("LUNICLE_SMTP_USERNAME"))
        assertTrue(warning.contains("LUNICLE_SMTP_PASSWORD"))
    }

    @Test
    fun `SMTP host without a From is half-configured and off`() {
        val (choice, warnings) = choose(
            env(smtpHost = "smtp.example.com", smtpUsername = "u", smtpPassword = "p"),
        )
        assertIs<EmailTransportChoice.None>(choice)
        assertTrue(warnings.single().contains("LUNICLE_EMAIL_FROM"))
    }

    @Test
    fun `a non-numeric SMTP_PORT disables SMTP`() {
        val (choice, warnings) = choose(
            env(
                from = FROM,
                smtpHost = "smtp.example.com",
                smtpUsername = "u",
                smtpPassword = "p",
                smtpPort = "not-a-port",
            ),
        )
        assertIs<EmailTransportChoice.None>(choice)
        assertTrue(warnings.single().contains("LUNICLE_SMTP_PORT"))
    }

    @Test
    fun `an unknown SMTP_TLS mode disables SMTP`() {
        val (choice, warnings) = choose(
            env(
                from = FROM,
                smtpHost = "smtp.example.com",
                smtpUsername = "u",
                smtpPassword = "p",
                smtpTls = "wat",
            ),
        )
        assertIs<EmailTransportChoice.None>(choice)
        assertTrue(warnings.single().contains("LUNICLE_SMTP_TLS"))
    }

    // ── Resolver: both configured resolves deterministically ──────────────────

    @Test
    fun `both configured with EMAIL_TRANSPORT=smtp picks SMTP, no warning`() {
        val (choice, warnings) = choose(bothConfigured(transport = "smtp"))
        assertIs<EmailTransportChoice.Smtp>(choice)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `both configured with EMAIL_TRANSPORT=resend picks Resend, no warning`() {
        val (choice, warnings) = choose(bothConfigured(transport = "resend"))
        assertIs<EmailTransportChoice.Resend>(choice)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `both configured with no selector defaults to Resend and warns`() {
        val (choice, warnings) = choose(bothConfigured(transport = null))
        assertIs<EmailTransportChoice.Resend>(choice)
        assertTrue(warnings.single().contains("LUNICLE_EMAIL_TRANSPORT"))
    }

    @Test
    fun `both configured with a bogus selector defaults to Resend and warns`() {
        val (choice, warnings) = choose(bothConfigured(transport = "carrier-pigeon"))
        assertIs<EmailTransportChoice.Resend>(choice)
        assertTrue(warnings.single().contains("LUNICLE_EMAIL_TRANSPORT"))
    }

    @Test
    fun `EMAIL_TRANSPORT selector is case-insensitive`() {
        val (choice, warnings) = choose(bothConfigured(transport = "SMTP"))
        assertIs<EmailTransportChoice.Smtp>(choice)
        assertTrue(warnings.isEmpty())
    }

    // ── SMTP message shape ────────────────────────────────────────────────────

    @Test
    fun `HTML-only send is a single text-html part`() {
        val message = buildMimeMessage(
            session = blankSession(),
            from = FROM,
            to = "dest@example.com",
            subject = "Hi",
            html = "<p>hello</p>",
            text = null,
        )
        assertTrue(message.contentType.startsWith("text/html"), message.contentType)
        assertEquals("<p>hello</p>", message.content)
        assertEquals(FROM, message.from.single().toString())
        assertEquals("dest@example.com", message.getRecipients(jakarta.mail.Message.RecipientType.TO).single().toString())
        assertEquals("Hi", message.subject)
    }

    @Test
    fun `send with a plain-text alternative is multipart with plain first, html last`() {
        val message = buildMimeMessage(
            session = blankSession(),
            from = FROM,
            to = "dest@example.com",
            subject = "Your code",
            html = "<p>123456</p>",
            text = "123456",
        )
        val multipart = assertIs<MimeMultipart>(message.content)
        assertTrue(multipart.contentType.startsWith("multipart/alternative"), multipart.contentType)
        assertEquals(2, multipart.count)

        // RFC 2046: the preferred alternative is LAST, so plain is part 0 and the
        // HTML the client should prefer is part 1.
        val plain = multipart.getBodyPart(0)
        assertTrue(plain.contentType.startsWith("text/plain"), plain.contentType)
        assertEquals("123456", plain.content)

        val html = multipart.getBodyPart(1)
        assertTrue(html.contentType.startsWith("text/html"), html.contentType)
        assertEquals("<p>123456</p>", html.content)
    }

    // ── SMTP send: dispatch seam, failure mapping, no secret in logs ──────────

    @Test
    fun `send hands the built message to the dispatcher`() = runBlocking {
        var captured: MimeMessage? = null
        val transport = SmtpEmailTransport(
            config = smtpConfig(),
            dispatch = { captured = it },
        )
        transport.send(to = "dest@example.com", subject = "Hi", html = "<p>x</p>", text = "x")

        val message = assertIs<MimeMessage>(captured)
        assertEquals("dest@example.com", message.getRecipients(jakarta.mail.Message.RecipientType.TO).single().toString())
        assertEquals(FROM, message.from.single().toString())
        assertEquals("Hi", message.subject)
    }

    @Test
    fun `a dispatch failure becomes a user-safe EmailSendFailure`() {
        val transport = SmtpEmailTransport(
            config = smtpConfig(),
            dispatch = { throw jakarta.mail.MessagingException("535 auth failed for user") },
        )
        val failure = assertFailsWith<EmailSendFailure> {
            runBlocking { transport.send(to = "dest@example.com", subject = "Hi", html = "<p>x</p>") }
        }
        // Generic sentence for the UI; the provider's own words stay in the log.
        assertEquals("The email server refused the message.", failure.message)
        assertFalse(
            failure.message!!.contains(SECRET),
            "the password must never reach the message a caller might surface",
        )
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun choose(env: EmailEnv): Pair<EmailTransportChoice, List<String>> {
        val warnings = mutableListOf<String>()
        val choice = chooseEmailTransport(env) { warnings += it }
        return choice to warnings
    }

    private fun env(
        resendApiKey: String? = null,
        from: String? = null,
        smtpHost: String? = null,
        smtpPort: String? = null,
        smtpUsername: String? = null,
        smtpPassword: String? = null,
        smtpTls: String? = null,
        transport: String? = null,
    ) = EmailEnv(
        resendApiKey = resendApiKey,
        from = from,
        smtpHost = smtpHost,
        smtpPort = smtpPort,
        smtpUsername = smtpUsername,
        smtpPassword = smtpPassword,
        smtpTls = smtpTls,
        transport = transport,
    )

    private fun bothConfigured(transport: String?) = env(
        resendApiKey = "re_key",
        from = FROM,
        smtpHost = "smtp.example.com",
        smtpUsername = "u",
        smtpPassword = SECRET,
        transport = transport,
    )

    private fun smtpConfig() = SmtpConfig(
        host = "smtp.example.com",
        port = DEFAULT_SMTP_PORT,
        username = "u",
        password = SECRET,
        tls = SmtpTls.STARTTLS,
        from = FROM,
    )

    private fun blankSession(): Session = Session.getInstance(Properties())

    private companion object {
        const val FROM = "Lunicle <noreply@example.com>"
        const val SECRET = "super-secret-password"
    }
}
