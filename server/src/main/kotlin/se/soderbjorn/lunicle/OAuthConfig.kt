/**
 * OAuth provider credentials, read from the deployment.
 *
 * Nothing here talks to Google; this is only where the two secrets come from and
 * what happens when they're absent. See docs/oauth-instructions.html for how the
 * values are minted.
 */
package se.soderbjorn.lunicle

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("OAuthConfig")

/** One provider's public id and its server-side secret. */
data class ProviderCredentials(
    val clientId: String,
    val clientSecret: String,
)

/**
 * The provider this instance can sign users in with.
 *
 * May be null, meaning "not configured here" rather than "broken". A server
 * without it is Stage 1: it boots, serves the bundle, counts, and offers no
 * sign-in. That is deliberate — the alternative, refusing to start without
 * secrets, would mean the container that is already serving production stops
 * doing so the moment this code deploys and before the Railway variables are
 * set. A deploy should never be able to take the site down over a feature nobody
 * is using yet.
 */
data class OAuthConfig(
    val google: ProviderCredentials?,

    /**
     * Whether this deployment offers signing somebody in with a mailed code.
     *
     * Not an OAuth provider and it has no credentials of its own. It is true when
     * two things hold: an [EmailTransport] is configured — Resend
     * (`LUNICLE_RESEND_API_KEY` + `LUNICLE_EMAIL_FROM`) or SMTP — *and* e-mail
     * sign-in has not been switched off with `LUNICLE_EMAIL_SIGN_IN`.
     *
     * ── The flag decouples sending mail from being a sign-in method ─────────────
     *
     * It used to be true *exactly* when mail was configured, which coupled two
     * questions that are not the same: "can this server send" and "may somebody
     * sign in with a code". A Workspace deployment (LNL-92) wants Google OAuth as
     * the only way in while still sending issue notifications, and
     * `LUNICLE_EMAIL_SIGN_IN=off` is what buys that — it lowers *this* flag while
     * the notifier and the address-change lifecycle go on reading the transport
     * directly (see [isEmailConfigured]) and are untouched. The flag defaults to
     * on, so a deployment that says nothing keeps today's behaviour: configured
     * mail is also a sign-in method.
     *
     * It lives on this type because the question every sign-in surface asks is
     * "what may I offer", and that has to be answerable from one object; two of
     * them would be two things to keep in step, which is how a button appears for
     * a method the server cannot perform. The `AUTH_EMAIL_REQUEST` and
     * `AUTH_EMAIL_REDEEM` endpoints gate on this same flag, so hiding the button
     * and refusing a direct POST are one decision, not two.
     *
     * Defaulted to false so the many tests that build an `OAuthConfig(google = …)`
     * say nothing about mail, and get the honest answer for a server with none.
     */
    val isEmailAvailable: Boolean = false,
) {
    /**
     * Whether to render any sign-in affordance at all.
     *
     * **This used to mean "Google is configured", and it must not any more.** The
     * whole point of LNL-74 is that a deployment can have one method, the other,
     * or both, and a surface that gates on Google would hide the e-mail form on a
     * server that has no Google credentials and can perfectly well sign people in.
     */
    val isSignInAvailable: Boolean get() = google != null || isEmailAvailable
}

/**
 * Read one value, preferring a system property over an environment variable.
 *
 * The two-tier lookup exists for the same reason [resolveAllowedFrameAncestors] has
 * one: `:server:run` is a Gradle `JavaExec`, which inherits the long-lived
 * **daemon's** environment rather than the invoking shell's. A secret exported
 * in a shell — or in a `.zshrc` — would therefore be whatever the daemon
 * happened to start with, and rotating it would appear to do nothing until the
 * daemon was killed. Per-invocation system properties cannot drift that way.
 *
 * The deployed container is the other case: a plain `java -jar` where the
 * environment is exact and Railway's variables are the natural home.
 *
 * Blank is treated as absent throughout, matching the rest of this server. An
 * empty Railway variable is a misconfiguration, not a request for an empty
 * client id.
 */
private fun resolveValue(property: String, env: String): String? =
    System.getProperty(property)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

/**
 * Assemble one provider's credentials, or null if it isn't configured.
 *
 * A half-configured provider — an id with no secret, or the reverse — is always
 * a mistake rather than an intention, so it is logged at WARN and then treated
 * as absent. Failing loudly in the log while continuing to serve is the right
 * trade for something whose only symptom would otherwise be a sign-in button
 * that 500s on click.
 */
private fun resolveProvider(name: String, idProperty: String, idEnv: String, secretProperty: String, secretEnv: String): ProviderCredentials? {
    val id = resolveValue(idProperty, idEnv)
    val secret = resolveValue(secretProperty, secretEnv)
    return when {
        id != null && secret != null -> ProviderCredentials(id, secret)
        id == null && secret == null -> null
        else -> {
            val present = if (id != null) idEnv else secretEnv
            val missing = if (id != null) secretEnv else idEnv
            logger.warn(
                "$name is half-configured: $present is set but $missing is not, so $name sign-in is disabled. " +
                    "Set both or neither. See docs/oauth-instructions.html.",
            )
            null
        }
    }
}

/**
 * Whether e-mail one-time-code sign-in may be offered.
 *
 * Reads `LUNICLE_EMAIL_SIGN_IN` (property `lunicle.emailSignIn`) and **defaults
 * to on** when unset — the backward-compatible promise, so a deployment that
 * configured mail before this flag existed keeps e-mail as both a notification
 * transport and a sign-in method without touching anything. Set it to an
 * off-value (`off`, `false`, `0`, `no`, `disabled`) to keep the mail but drop
 * e-mail as a *sign-in* method, leaving Google OAuth as the only way in. See
 * [OAuthConfig.isEmailAvailable] for what the flag gates and what it deliberately
 * does not.
 *
 * An unrecognised value is logged at WARN and treated as on. The instinct is to
 * fail closed for a security-shaped toggle, but "closed" here would mean silently
 * disabling sign-in on a typo and locking out a deployment that relies on it; the
 * safe default is the backward-compatible one, and the WARN is what makes the
 * typo visible. Set it to exactly `off` to disable.
 */
internal fun resolveEmailSignInEnabled(): Boolean {
    val raw = resolveValue("lunicle.emailSignIn", "LUNICLE_EMAIL_SIGN_IN")?.trim()?.lowercase()
        ?: return true
    return when (raw) {
        "on", "true", "1", "yes", "enabled" -> true
        "off", "false", "0", "no", "disabled" -> false
        else -> {
            logger.warn(
                "LUNICLE_EMAIL_SIGN_IN is set to \"$raw\", which is neither on nor off; " +
                    "e-mail sign-in stays enabled. Set it to \"off\" to disable e-mail sign-in.",
            )
            true
        }
    }
}

/**
 * Read the provider from the environment.
 *
 * One Google client covers production and localhost, because Google permits
 * several JavaScript origins on a single client — so the same id serves every
 * environment, selected by which `.env` or Railway variables are in scope.
 *
 * @param allowEmailCodeSignIn the brand manifest's `allowEmailCodeSignIn` (LNL-192),
 *   defaulting to on. It is a third term on [OAuthConfig.isEmailAvailable] and can only
 *   ever **narrow** it: a deployment with no transport has no code sign-in whatever the
 *   manifest claims, which is exactly why the three terms are ANDed in one place rather
 *   than asked separately by each surface.
 */
fun resolveOAuthConfig(allowEmailCodeSignIn: Boolean = true): OAuthConfig {
    val emailConfigured = isEmailConfigured()
    val emailSignInEnabled = resolveEmailSignInEnabled() && allowEmailCodeSignIn
    // Mail can be on for notifications while e-mail sign-in is deliberately off;
    // say so once, because "I configured SMTP but the sign-in picker shows only
    // Google" is otherwise a puzzle rather than a setting. See isEmailAvailable.
    if (emailConfigured && !emailSignInEnabled) {
        logger.info(
            "E-mail is configured but e-mail sign-in is disabled via LUNICLE_EMAIL_SIGN_IN; " +
                "sign-in is Google-only. Notifications and address-change codes are unaffected.",
        )
    }
    return OAuthConfig(
        google = resolveProvider(
            name = "Google",
            idProperty = "lunicle.googleClientId",
            idEnv = "LUNICLE_GOOGLE_CLIENT_ID",
            secretProperty = "lunicle.googleClientSecret",
            secretEnv = "LUNICLE_GOOGLE_CLIENT_SECRET",
        ),
        // E-mail sign-in is available when the server can send AND the operator has
        // not withdrawn it as a sign-in method. The notifier and the address-change
        // lifecycle never consult this flag; see isEmailAvailable and
        // resolveEmailSignInEnabled.
        isEmailAvailable = emailConfigured && emailSignInEnabled,
    )
}

/**
 * A one-line summary for the startup log: which methods are live, and never any
 * part of a secret.
 *
 * Worth a line for the reason [main] gives about providers generally — "sign-in
 * doesn't appear" is otherwise indistinguishable from "the button is broken", and
 * the answer is usually a variable that did not reach the process. With two
 * methods that is twice as true, since a deployment can now be half-configured in
 * a way that works.
 */
fun OAuthConfig.describe(): String {
    val methods = listOfNotNull(
        "Google".takeIf { google != null },
        "e-mail code".takeIf { isEmailAvailable },
    )
    return if (methods.isEmpty()) "none (sign-in disabled)" else methods.joinToString(" + ")
}
