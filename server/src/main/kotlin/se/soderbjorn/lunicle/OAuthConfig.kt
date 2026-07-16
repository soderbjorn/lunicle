/**
 * OAuth provider credentials, read from the deployment.
 *
 * Nothing here talks to Google or GitHub; this is only where the four secrets
 * come from and what happens when they're absent. See docs/oauth-instructions.html
 * for how the values are minted.
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
 * The providers this instance can sign users in with.
 *
 * Either may be null, meaning "not configured here" rather than "broken". A
 * server with neither is Stage 1: it boots, serves the bundle, counts, and
 * offers no sign-in. That is deliberate — the alternative, refusing to start
 * without secrets, would mean the container that is already serving production
 * stops doing so the moment this code deploys and before the Railway variables
 * are set. A deploy should never be able to take the site down over a feature
 * nobody is using yet.
 */
data class OAuthConfig(
    val google: ProviderCredentials?,
    val github: ProviderCredentials?,
) {
    /** Whether to render any sign-in affordance at all. */
    val isSignInAvailable: Boolean get() = google != null || github != null
}

/**
 * Read one value, preferring a system property over an environment variable.
 *
 * The two-tier lookup exists for the same reason [resolveFrameAncestors] has
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
 * Read both providers from the environment.
 *
 * One Google client covers production and localhost, because Google permits
 * several JavaScript origins on a single client. GitHub does not — an OAuth app
 * has exactly one callback URL — so production and local development use two
 * separate apps and therefore two different ids here, selected by which `.env`
 * or Railway variables are in scope. That asymmetry is GitHub's, not ours.
 */
fun resolveOAuthConfig(): OAuthConfig = OAuthConfig(
    google = resolveProvider(
        name = "Google",
        idProperty = "lunicle.googleClientId",
        idEnv = "GOOGLE_CLIENT_ID",
        secretProperty = "lunicle.googleClientSecret",
        secretEnv = "GOOGLE_CLIENT_SECRET",
    ),
    github = resolveProvider(
        name = "GitHub",
        idProperty = "lunicle.githubClientId",
        idEnv = "GITHUB_CLIENT_ID",
        secretProperty = "lunicle.githubClientSecret",
        secretEnv = "GITHUB_CLIENT_SECRET",
    ),
)

/**
 * A one-line summary for the startup log: which providers are live, and never
 * any part of a secret.
 */
fun OAuthConfig.describe(): String = when {
    !isSignInAvailable -> "none (sign-in disabled)"
    else -> listOfNotNull(
        google?.let { "Google" },
        github?.let { "GitHub" },
    ).joinToString(", ")
}
