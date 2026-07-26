/**
 * Sending transactional email through a plain SMTP server.
 *
 * The second [EmailTransport], for deployments that cannot or will not use Resend
 * — a corporate relay, a self-hosted MTA, or any provider that speaks SMTP.
 * Everything above the seam is unaware this exists: a notification, a sign-in code
 * and an agent's report are composed and handed to [send] exactly as they are for
 * [ResendEmailTransport], and the same `text?`-is-optional / plain-text-alternative
 * contract holds.
 *
 * Ktor has no SMTP client, so this leans on Angus Mail (the Jakarta Mail RI; see
 * the dependency note in `server/build.gradle.kts`). Jakarta Mail is a blocking,
 * connection-per-send API — there is no persistent pool here — so the actual
 * dispatch runs on [Dispatchers.IO], off the request thread.
 *
 * Out of scope, deliberately: per-project SMTP settings, queuing/retry, DKIM
 * signing. This is one deployment-wide transport choice, nothing more.
 *
 * @see EmailTransport
 * @see chooseEmailTransport
 */
package se.soderbjorn.lunicle

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Properties

private val logger = LoggerFactory.getLogger("SmtpEmailTransport")

/** The default SMTP submission port — STARTTLS on 587. */
const val DEFAULT_SMTP_PORT: Int = 587

/**
 * How the connection is secured.
 *
 * @property STARTTLS an unencrypted connection upgraded to TLS with the STARTTLS
 *   command, the modern submission default on port 587.
 * @property IMPLICIT TLS from the first byte ("SMTPS"), on port 465.
 */
enum class SmtpTls {
    STARTTLS,
    IMPLICIT,
    ;

    companion object {
        /** Parse the `LUNICLE_SMTP_TLS` value, or null if it names neither mode. */
        fun fromEnv(raw: String): SmtpTls? = when (raw.trim().lowercase()) {
            "starttls" -> STARTTLS
            "tls", "ssl", "implicit" -> IMPLICIT
            else -> null
        }
    }
}

/**
 * What the SMTP transport needs from the deployment.
 *
 * @property host the SMTP server hostname (`LUNICLE_SMTP_HOST`); its presence is
 *   what turns this transport on.
 * @property port the server port; defaults to [DEFAULT_SMTP_PORT].
 * @property username the auth username.
 * @property password the auth secret. A bearer-grade credential: read from the
 *   environment, never logged, treated exactly like `LUNICLE_RESEND_API_KEY`.
 * @property tls how the connection is secured.
 * @property from the shared `LUNICLE_EMAIL_FROM` header — the same address Resend would
 *   send as; there is deliberately no SMTP-specific from-address.
 */
data class SmtpConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val tls: SmtpTls,
    val from: String,
)

/**
 * The last inch: hand a fully-built message to the mail server.
 *
 * A seam, and the reason is [ResendEmailTransport]'s injectable `httpClient`: a
 * test can capture the [MimeMessage] a send produced, or make one fail, without a
 * live SMTP server. Production uses [Transport.send], the static convenience that
 * connects with the session's properties and the given credentials, sends, and
 * closes — one connection per message.
 */
fun interface SmtpDispatch {
    fun send(message: MimeMessage)
}

/**
 * Sends mail through an SMTP server.
 *
 * @param config host, port, credentials, TLS mode and From.
 * @param session the Jakarta Mail session carrying the connection properties.
 *   Built from [config] by default; holds no connection, so constructing this is
 *   cheap and side-effect-free (which is what lets [isEmailConfigured] build one
 *   just to answer "is mail available?").
 * @param dispatch the last inch, defaulting to a real [Transport.send]. Injectable
 *   for the same reason [ResendEmailTransport] takes an `httpClient`.
 */
class SmtpEmailTransport(
    private val config: SmtpConfig,
    private val session: Session = buildSmtpSession(config),
    private val dispatch: SmtpDispatch = SmtpDispatch { message ->
        Transport.send(message, config.username, config.password)
    },
) : EmailTransport {
    /**
     * Send one email, or throw [EmailSendFailure].
     *
     * @throws EmailSendFailure when the message cannot be built or the server
     *   refuses it. The server's own words (and the port/host in them) are logged;
     *   the credential never is, and the exception message the caller sees is a
     *   generic, user-safe sentence — the same discipline [ResendEmailTransport]
     *   keeps with Resend's error body.
     */
    override suspend fun send(to: String, subject: String, html: String, text: String?) {
        val message = buildMimeMessage(
            session = session,
            from = config.from,
            to = to,
            subject = subject,
            html = html,
            text = text,
        )
        try {
            withContext(Dispatchers.IO) { dispatch.send(message) }
        } catch (t: Throwable) {
            // t.message is the server's response line (e.g. "535 authentication
            // failed") or a connection error — host and port, never the password,
            // which is not in the exception and not logged here.
            logger.warn("SMTP send to <$to> failed: ${t.message}")
            throw EmailSendFailure("The email server refused the message.", t)
        }
    }
}

/**
 * Turn [config] into a Jakarta Mail session — connection properties only, no
 * socket. The TLS mode decides which of the mutually-exclusive property sets is
 * on: STARTTLS *requires* the upgrade (rather than merely enabling it, so a server
 * that cannot upgrade fails loudly instead of sending in the clear), while
 * implicit TLS turns on SSL from the first byte.
 */
fun buildSmtpSession(config: SmtpConfig): Session {
    val props = Properties().apply {
        put("mail.smtp.host", config.host)
        put("mail.smtp.port", config.port.toString())
        put("mail.smtp.auth", "true")
        when (config.tls) {
            SmtpTls.STARTTLS -> {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
            SmtpTls.IMPLICIT -> {
                put("mail.smtp.ssl.enable", "true")
            }
        }
    }
    return Session.getInstance(props)
}

/**
 * Build the MIME message a send puts on the wire.
 *
 * Factored out of [SmtpEmailTransport.send] and left non-private so a test can
 * assert the one thing that matters about the message — its shape — directly:
 * a single `text/html` part when [text] is null, and a `multipart/alternative`
 * with the plain part first and the HTML part last when it is not. RFC 2046 makes
 * the *last* alternative the preferred one, so a client that can render HTML shows
 * HTML and the plain part is the fallback — the same relationship Resend's `html`
 * + `text` fields express.
 */
fun buildMimeMessage(
    session: Session,
    from: String,
    to: String,
    subject: String,
    html: String,
    text: String?,
): MimeMessage {
    val message = MimeMessage(session)
    message.setFrom(InternetAddress.parse(from).first())
    message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
    message.setSubject(subject, "UTF-8")

    if (text == null) {
        message.setContent(html, "text/html; charset=UTF-8")
    } else {
        val alternative = MimeMultipart("alternative")
        alternative.addBodyPart(MimeBodyPart().apply { setText(text, "UTF-8") })
        alternative.addBodyPart(
            MimeBodyPart().apply { setContent(html, "text/html; charset=UTF-8") },
        )
        message.setContent(alternative)
    }

    message.saveChanges()
    return message
}
