/**
 * The seam every path to a mailbox goes through, and the one place that decides
 * *which* way mail leaves this deployment.
 *
 * Transactional mail has two possible transports and the rest of the app knows
 * neither: a notification, a sign-in code and an agent's report are all composed
 * and handed to [EmailTransport.send], and whether that becomes an HTTPS POST to
 * Resend or an SMTP conversation is settled once, here, from the environment.
 *
 * Two implementations back it:
 *  - [ResendEmailTransport] — the original, an HTTPS call to Resend's API.
 *  - [SmtpEmailTransport] — a plain SMTP server (a corporate relay, a self-hosted
 *    MTA, or any provider that speaks SMTP), for deployments that cannot or will
 *    not use Resend.
 *
 * [resolveEmailTransport] picks **at most one** from the environment and returns
 * null when none is configured — preserving the guarantee the Resend-only design
 * already made: email being off is a valid state a deploy boots into cleanly, not
 * a failure that takes the site down. See [chooseEmailTransport] for the whole
 * selection, which is a pure function so the precedence rules are testable without
 * touching System properties or the network.
 */
package se.soderbjorn.lunicle

import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("EmailTransport")

/**
 * Sends one transactional email, or throws [EmailSendFailure].
 *
 * The single method every mail feature depends on, and the boundary that keeps
 * them ignorant of the provider underneath. Its contract is exactly what the
 * Resend sender promised before there was a second transport, so an SMTP
 * implementation is a drop-in and no caller changes.
 *
 * @param to the recipient address.
 * @param subject the subject line.
 * @param html the body, as HTML.
 * @param text the plain-text alternative, or null to send HTML alone.
 *
 *   Every message this app sent before LNL-76 was HTML-only with no alternative
 *   part, which spam filters score against. That penalty is worth accepting for a
 *   notification and emphatically not for a sign-in code: a spam-filed
 *   notification is an annoyance, a spam-filed code is a lockout with no fallback.
 *   So the auth mail always passes one, and the parameter is optional so the
 *   existing senders can adopt it without being changed all at once. **Every**
 *   transport must honour it — HTML alone when null, a multipart alternative when
 *   present — or that deliverability contract holds for Resend and quietly breaks
 *   for SMTP.
 * @throws EmailSendFailure when the message cannot be sent. The transport's own
 *   error detail is logged in full and kept off the screen — echoing a provider's
 *   raw error into the UI is how internals leak into screenshots.
 */
interface EmailTransport {
    suspend fun send(to: String, subject: String, html: String, text: String? = null)
}

/** A send a transport refused, with a message safe to show a user. */
class EmailSendFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Read one value, preferring a system property over an environment variable.
 *
 * The same two-tier lookup [resolveValue] in OAuthConfig uses, and for the same
 * reason: `:server:run` is a Gradle `JavaExec` that inherits the long-lived
 * daemon's environment, so a per-invocation system property is the only override
 * that cannot silently go stale. The deployed container is a plain `java -jar`
 * where the environment is exact and Railway's variables are the natural home.
 *
 * Blank is treated as absent throughout.
 */
private fun resolveEmailValue(property: String, env: String): String? =
    System.getProperty(property)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

/**
 * The raw email configuration, already read (property-over-env) and blank-to-null,
 * before any decision is made about it.
 *
 * A plain data holder so the *decision* — [chooseEmailTransport] — is a pure
 * function of its inputs and can be exhaustively tested without setting System
 * properties or reaching a network. [readEmailEnv] fills it from the real
 * environment; a test constructs one by hand.
 */
data class EmailEnv(
    val resendApiKey: String?,
    val from: String?,
    val smtpHost: String?,
    val smtpPort: String?,
    val smtpUsername: String?,
    val smtpPassword: String?,
    val smtpTls: String?,
    val transport: String?,
)

/** Read the whole email configuration from the environment, property tier first. */
private fun readEmailEnv(): EmailEnv = EmailEnv(
    resendApiKey = resolveEmailValue("lunicle.resendApiKey", "LUNICLE_RESEND_API_KEY"),
    from = resolveEmailValue("lunicle.emailFrom", "LUNICLE_EMAIL_FROM"),
    smtpHost = resolveEmailValue("lunicle.smtpHost", "LUNICLE_SMTP_HOST"),
    smtpPort = resolveEmailValue("lunicle.smtpPort", "LUNICLE_SMTP_PORT"),
    smtpUsername = resolveEmailValue("lunicle.smtpUsername", "LUNICLE_SMTP_USERNAME"),
    smtpPassword = resolveEmailValue("lunicle.smtpPassword", "LUNICLE_SMTP_PASSWORD"),
    smtpTls = resolveEmailValue("lunicle.smtpTls", "LUNICLE_SMTP_TLS"),
    transport = resolveEmailValue("lunicle.emailTransport", "LUNICLE_EMAIL_TRANSPORT"),
)

/**
 * The transport this deployment should use, decided but not yet built.
 *
 * A step between "the environment says" and "here is a live transport" so the
 * precedence rules — half-config, both-config tie-break, unconfigured — can be
 * asserted against without constructing anything that opens a connection.
 */
sealed interface EmailTransportChoice {
    /** Send through Resend's HTTP API. */
    data class Resend(val config: ResendConfig) : EmailTransportChoice

    /** Send through a configured SMTP server. */
    data class Smtp(val config: SmtpConfig) : EmailTransportChoice

    /** No transport is configured; email is off, which is a valid state. */
    data object None : EmailTransportChoice
}

/** The documented default when both transports are configured but no selector says which. */
private const val DEFAULT_BOTH_CONFIGURED = "resend"

/**
 * Decide which transport to use, warning about anything half-done, as a pure
 * function of [env].
 *
 * The rules, and each one is a line in an acceptance criterion:
 *
 *  - **At most one.** Resend is requested by `LUNICLE_RESEND_API_KEY`; SMTP by
 *    `LUNICLE_SMTP_HOST`. Neither requested → [EmailTransportChoice.None]: email
 *    is off, which is a boot-time non-event, not a failure.
 *  - **`LUNICLE_EMAIL_FROM` is shared.** One From header for whichever transport
 *    is active; there is deliberately no second from-address var. A requested
 *    transport with no From is half-configured.
 *  - **Half-config is a mistake, not an intention.** A requested transport
 *    missing a required value is logged at WARN and treated as absent — the same
 *    branch the Resend-only resolver had, now applied per transport. The symptom
 *    it prevents is a send that 500s instead of cleanly logging "not configured".
 *  - **Both fully configured resolves deterministically.** `LUNICLE_EMAIL_TRANSPORT`
 *    (`resend` | `smtp`) is honoured; absent, it WARNs and falls to the documented
 *    default (Resend, the incumbent, so an existing deploy that later adds SMTP
 *    does not silently switch).
 *
 * @param warn where a misconfiguration goes. A parameter so a test can capture
 *   exactly what was warned about; production passes the logger. Never handed a
 *   secret — the messages name which var is set or missing, never its value.
 */
fun chooseEmailTransport(env: EmailEnv, warn: (String) -> Unit): EmailTransportChoice {
    val resend = resolveResendChoice(env, warn)
    val smtp = resolveSmtpChoice(env, warn)

    return when {
        resend != null && smtp != null -> resolveBothConfigured(env.transport, resend, smtp, warn)
        resend != null -> EmailTransportChoice.Resend(resend)
        smtp != null -> EmailTransportChoice.Smtp(smtp)
        else -> EmailTransportChoice.None
    }
}

/**
 * The Resend half: a config when `LUNICLE_RESEND_API_KEY` is present *and* has an
 * `LUNICLE_EMAIL_FROM` to send as, null otherwise. The half-config — a key with no From —
 * is exactly the branch the Resend-only resolver had before there was a second
 * transport, kept identical so a Resend-only deploy behaves as it always did.
 */
private fun resolveResendChoice(env: EmailEnv, warn: (String) -> Unit): ResendConfig? {
    val apiKey = env.resendApiKey ?: return null
    val from = env.from
    if (from == null) {
        warn(
            "Email is half-configured: LUNICLE_RESEND_API_KEY is set but LUNICLE_EMAIL_FROM is not, " +
                "so Resend is disabled. Set both or neither.",
        )
        return null
    }
    return ResendConfig(apiKey = apiKey, from = from)
}

/**
 * The SMTP half: a config when `LUNICLE_SMTP_HOST` is present and everything the transport
 * cannot work without is too — credentials, a From, a numeric port, a known TLS
 * mode. Any gap is a half-configuration, logged and treated as absent, mirroring
 * the Resend branch. Port defaults to 587 and TLS to STARTTLS when simply unset;
 * a *present but nonsense* value is a typo and disables SMTP rather than being
 * guessed past.
 */
private fun resolveSmtpChoice(env: EmailEnv, warn: (String) -> Unit): SmtpConfig? {
    val host = env.smtpHost ?: return null

    val missing = buildList {
        if (env.smtpUsername == null) add("LUNICLE_SMTP_USERNAME")
        if (env.smtpPassword == null) add("LUNICLE_SMTP_PASSWORD")
        if (env.from == null) add("LUNICLE_EMAIL_FROM")
    }
    if (missing.isNotEmpty()) {
        warn(
            "SMTP is half-configured: LUNICLE_SMTP_HOST is set but ${missing.joinToString(", ")} " +
                "${if (missing.size == 1) "is" else "are"} not, so SMTP is disabled. " +
                "Set the whole block or none of it.",
        )
        return null
    }

    val port = when (val raw = env.smtpPort) {
        null -> DEFAULT_SMTP_PORT
        else -> raw.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
            warn(
                "LUNICLE_SMTP_PORT is not a valid port number ($raw), so SMTP is disabled. " +
                    "Use a number 1-65535, e.g. 587.",
            )
            return null
        }
    }

    val tls = when (val raw = env.smtpTls) {
        null -> SmtpTls.STARTTLS
        else -> SmtpTls.fromEnv(raw) ?: run {
            warn("LUNICLE_SMTP_TLS must be 'starttls' or 'tls' (got '$raw'), so SMTP is disabled.")
            return null
        }
    }

    return SmtpConfig(
        host = host,
        port = port,
        username = env.smtpUsername!!,
        password = env.smtpPassword!!,
        tls = tls,
        from = env.from!!,
    )
}

/**
 * Both transports are fully configured: pick the one `LUNICLE_EMAIL_TRANSPORT` names, or
 * WARN and fall to the documented default. Never guesses silently — an operator
 * who configured two transports gets told which one is winning.
 */
private fun resolveBothConfigured(
    selector: String?,
    resend: ResendConfig,
    smtp: SmtpConfig,
    warn: (String) -> Unit,
): EmailTransportChoice = when (selector?.lowercase()) {
    "resend" -> EmailTransportChoice.Resend(resend)
    "smtp" -> EmailTransportChoice.Smtp(smtp)
    null -> {
        warn(
            "Both Resend and SMTP are fully configured but LUNICLE_EMAIL_TRANSPORT is not set. " +
                "Defaulting to $DEFAULT_BOTH_CONFIGURED; set LUNICLE_EMAIL_TRANSPORT to 'resend' or 'smtp' to choose.",
        )
        defaultBothConfigured(resend, smtp)
    }
    else -> {
        warn(
            "LUNICLE_EMAIL_TRANSPORT must be 'resend' or 'smtp' (got '$selector'). " +
                "Both are configured; defaulting to $DEFAULT_BOTH_CONFIGURED.",
        )
        defaultBothConfigured(resend, smtp)
    }
}

private fun defaultBothConfigured(resend: ResendConfig, smtp: SmtpConfig): EmailTransportChoice =
    when (DEFAULT_BOTH_CONFIGURED) {
        "smtp" -> EmailTransportChoice.Smtp(smtp)
        else -> EmailTransportChoice.Resend(resend)
    }

/**
 * Build the transport this deployment should use, or null when none is configured.
 *
 * The one impure call: it reads the environment and constructs a live transport.
 * All the judgement lives in [chooseEmailTransport]; this only turns a decision
 * into an object. Constructing an [SmtpEmailTransport] opens no connection — it
 * only holds a `Session` — so calling this to answer "is email available?" is
 * cheap and side-effect-free. See [isEmailConfigured].
 */
fun resolveEmailTransport(): EmailTransport? =
    when (val choice = chooseEmailTransport(readEmailEnv()) { logger.warn(it) }) {
        is EmailTransportChoice.Resend -> ResendEmailTransport(choice.config)
        is EmailTransportChoice.Smtp -> SmtpEmailTransport(choice.config)
        EmailTransportChoice.None -> null
    }

/**
 * Whether this deployment can send mail at all, without logging or constructing
 * anything.
 *
 * The availability signal the sign-in surfaces read (see resolveOAuthConfig).
 * Runs the same decision as [resolveEmailTransport] but swallows the warnings —
 * the startup path that actually builds the transport does the logging, and this
 * being called alongside it should not double every misconfiguration warning.
 */
fun isEmailConfigured(): Boolean =
    chooseEmailTransport(readEmailEnv()) {} !is EmailTransportChoice.None

/** A one-line summary for the startup log: which transport is live, and never a secret. */
fun describeEmailTransport(transport: EmailTransport?): String = when (transport) {
    is ResendEmailTransport -> "enabled (Resend)"
    is SmtpEmailTransport -> "enabled (SMTP)"
    else -> "disabled"
}
