/**
 * Sending transactional email through Resend.
 *
 * This is the *real* sender for the Resend transport: it makes an HTTPS call to
 * Resend's API and a message actually leaves the building. It is deliberately
 * small and self-contained, and it is one of the two implementations behind
 * [EmailTransport] — the other being [SmtpEmailTransport]. Which one a deployment
 * uses is settled once, at startup, by [resolveEmailTransport].
 *
 * Named for the provider on purpose. This was `EmailSender` while Resend was the
 * only transport; the name became a liability the moment a second one arrived —
 * "the email sender" reads as *the* way mail leaves, and SMTP is a peer, not a
 * subordinate. `ResendEmailTransport` next to `SmtpEmailTransport` says exactly
 * what each is. `ResendConfig` earns its provider prefix for the same reason a
 * bare `EmailConfig` did not: two transports each have config, and neither owns
 * the plain name.
 *
 * Configuration is two values read from the deployment — a Resend key and the
 * From address — both present or both absent, and absent means "this transport is
 * off here" rather than "the server is broken". A deploy must never take the site
 * down over a feature nobody has configured yet; see [chooseEmailTransport].
 *
 * @see createProviderHttpClient
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ResendEmailTransport")

/**
 * What the Resend transport needs from the deployment: a Resend API key, and the
 * address mail is sent as.
 *
 * @property apiKey the Resend secret. A bearer credential, so it is read from the
 *   environment and never logged, exactly like an OAuth client secret.
 * @property from the `From` header — e.g. `Lunicle <noreply@example.com>`. Must be
 *   an address on a domain verified in Resend, or Resend refuses the send. This is
 *   the shared `LUNICLE_EMAIL_FROM`; SMTP sends as the same address.
 */
data class ResendConfig(
    val apiKey: String,
    val from: String,
)

/**
 * The shape Resend's `POST /emails` wants. Only the fields we send.
 *
 * [text] is the plain-text alternative, and it is omitted from the JSON entirely
 * when absent rather than sent as null — Resend treats a present-but-null field
 * as a malformed request, and `encodeDefaults` is off by default in kotlinx, so
 * a null default simply does not appear. See [ResendEmailTransport.send].
 */
@Serializable
private data class ResendEmailRequest(
    val from: String,
    val to: List<String>,
    val subject: String,
    val html: String,
    val text: String? = null,
)

/**
 * Sends mail through Resend's HTTP API.
 *
 * @param config the key and from-address.
 * @param httpClient the transport; defaults to the same [createProviderHttpClient]
 *   the OAuth code uses — an outbound JSON client, distinct from the browser-facing
 *   one. Held for the process's life: the instance is built once in
 *   [Application.module] and there is nothing to close on a per-send basis.
 */
class ResendEmailTransport(
    private val config: ResendConfig,
    private val httpClient: HttpClient = createProviderHttpClient(),
) : EmailTransport {
    /**
     * Send one email, or throw [EmailSendFailure].
     *
     * @param to the recipient address.
     * @param subject the subject line.
     * @param html the body, as HTML.
     * @param text the plain-text alternative, or null to send HTML alone. See
     *   [EmailTransport.send] for why this contract matters and why it is optional.
     * @throws EmailSendFailure when Resend answers non-2xx or the call cannot be
     *   made. Resend's own error body is logged in full and kept off the screen —
     *   echoing a provider's raw error into the UI is how internals leak into
     *   screenshots, exactly as [SignInFailure] guards against.
     */
    override suspend fun send(to: String, subject: String, html: String, text: String?) {
        val response: HttpResponse = try {
            httpClient.post("https://api.resend.com/emails") {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(
                    ResendEmailRequest(
                        from = config.from,
                        to = listOf(to),
                        subject = subject,
                        html = html,
                        text = text,
                    ),
                )
            }
        } catch (t: Throwable) {
            logger.warn("Resend request failed: ${t.message}")
            throw EmailSendFailure("Could not reach the email provider.", t)
        }
        if (!response.status.isSuccess()) {
            logger.warn("Resend refused the send (${response.status}): ${response.bodyAsText()}")
            throw EmailSendFailure("The email provider refused the message.")
        }
    }
}
