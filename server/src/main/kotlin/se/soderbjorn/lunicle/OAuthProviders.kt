/**
 * Talking to Google and GitHub: authorization code in, display name out.
 *
 * Everything provider-specific lives here so [AuthRoutes] can stay about HTTP
 * and sessions. The two providers look similar and differ in every detail that
 * matters, most of which are documented in docs/oauth-instructions.html.
 *
 * @see OAuthConfig
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.clientserver.AuthProvider

private val logger = LoggerFactory.getLogger("OAuthProviders")

/**
 * The client used to call the providers.
 *
 * Its own instance rather than the one [se.soderbjorn.lunicle.clientserver.LunicleApi]
 * builds: that one is the *browser's* view of us, and giving server-to-provider
 * calls their own client keeps the two from sharing configuration by accident.
 * `ignoreUnknownKeys` because both providers return far more than we read and
 * add fields without notice.
 */
internal fun createProviderHttpClient(): HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

/** Google's token response. Only the fields we use. */
@Serializable
private data class GoogleTokenResponse(
    @SerialName("access_token") val accessToken: String,
)

/** Google's userinfo response. */
@Serializable
private data class GoogleUserInfo(
    /**
     * Google's stable, per-account identifier. Not optional and not the email:
     * `sub` is the only field Google documents as immutable and never reused,
     * which is exactly what the users table needs to key on. An account's email
     * and name can both change; this cannot.
     */
    val sub: String,
    val name: String? = null,
    val email: String? = null,
)

/** GitHub's token response. */
@Serializable
private data class GitHubTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

/** GitHub's `/user` response. */
@Serializable
private data class GitHubUser(
    /**
     * GitHub's stable numeric account id. The users table keys on this rather
     * than on `login`, which looks like an identifier and is not: a user can
     * rename themselves at any time, and GitHub then makes the old name
     * available for someone else to take. Keying on `login` would therefore
     * hand a renamed user a brand-new empty account, and — far worse —
     * eventually hand their old account to whoever claimed the freed name.
     */
    val id: Long,
    val name: String? = null,
    val login: String,
)

/**
 * A sign-in that didn't work, with a message safe to show a user.
 *
 * Provider errors are logged in full and summarised on screen: the detail is
 * for us, and echoing a provider's raw error into the UI is how internals leak
 * into screenshots.
 */
class SignInFailure(val userMessage: String, cause: Throwable? = null) :
    Exception(userMessage, cause)

/**
 * Who a provider says someone is.
 *
 * The output of a sign-in, and the input to [UserStore.upsert] — this is the
 * boundary where an OAuth identity stops being a provider's business and starts
 * being an account.
 *
 * Not [SignedInUser], which is what the *client* is told. The difference is
 * [providerId]: the server needs it to find the same person again on their next
 * sign-in, and a client has no use for it whatsoever. Keeping the two types
 * apart is what stops it from drifting onto the wire by accident.
 *
 * @property provider which provider authenticated them.
 * @property providerId the provider's stable id — Google's `sub`, GitHub's
 *   numeric `id`. Stable and never reused, which is the entire reason the users
 *   table keys on it rather than on an email or a name.
 * @property displayName what to render. Never blank; see [displayName].
 */
data class ProviderIdentity(
    val provider: AuthProvider,
    val providerId: String,
    val displayName: String,
)

/**
 * Turn a name, an email and a fallback into something renderable.
 *
 * A signed-in user whose name renders as an empty string reads as a bug, and
 * both providers can return one: Google's `name` is absent for some account
 * types, GitHub's is null whenever the user never filled in their profile —
 * which is extremely common. So: the provider's name, else the local part of
 * the email, else the caller's fallback.
 */
private fun displayName(name: String?, email: String?, fallback: String): String =
    name?.takeIf { it.isNotBlank() }
        ?: email?.substringBefore('@')?.takeIf { it.isNotBlank() }
        ?: fallback

/**
 * Exchange a Google authorization code for the user behind it.
 *
 * @param code the one-time code the popup's callback produced.
 * @param redirectUri **the origin of the page that called `initCodeClient`** —
 *   not a path, and not a URL you'd otherwise call a redirect. In popup mode
 *   there is no redirect; Google's JS reference says `redirect_uri` "defaults to
 *   the origin of the page", and this exchange must send that same value or the
 *   code is rejected. See docs/oauth-instructions.html.
 * @return the identity behind the code, for [UserStore.upsert] to turn into an
 *   account.
 * @throws SignInFailure if Google refuses the code or the profile call fails.
 */
suspend fun exchangeGoogleCode(
    httpClient: HttpClient,
    credentials: ProviderCredentials,
    code: String,
    redirectUri: String,
): ProviderIdentity {
    val token = runCatching {
        httpClient.submitForm(
            url = "https://oauth2.googleapis.com/token",
            formParameters = Parameters.build {
                append("code", code)
                append("client_id", credentials.clientId)
                append("client_secret", credentials.clientSecret)
                append("redirect_uri", redirectUri)
                append("grant_type", "authorization_code")
            },
        ).body<GoogleTokenResponse>()
    }.getOrElse { t ->
        logger.warn("Google token exchange failed (redirect_uri=$redirectUri): ${t.message}")
        throw SignInFailure("Google would not complete the sign-in.", t)
    }

    // The userinfo endpoint rather than decoding the id_token: we received this
    // token directly from Google over TLS, so there is no third party to
    // distrust and nothing a signature check would tell us that we don't
    // already know. Verifying a JWT here would be ceremony, and hand-rolled JWT
    // verification is a classic source of real bugs.
    val info = runCatching {
        httpClient.get("https://www.googleapis.com/oauth2/v3/userinfo") {
            header(HttpHeaders.Authorization, "Bearer ${token.accessToken}")
        }.body<GoogleUserInfo>()
    }.getOrElse { t ->
        logger.warn("Google userinfo failed: ${t.message}")
        throw SignInFailure("Signed in with Google, but could not read the profile.", t)
    }

    return ProviderIdentity(
        provider = AuthProvider.GOOGLE,
        providerId = info.sub,
        displayName = displayName(info.name, info.email, fallback = "Google user"),
    )
}

/**
 * Exchange a GitHub authorization code for the user behind it.
 *
 * @param redirectUri must match the OAuth app's registered callback exactly —
 *   which is why production and local development need two separate apps. See
 *   docs/oauth-instructions.html.
 * @return the identity behind the code, for [UserStore.upsert] to turn into an
 *   account.
 * @throws SignInFailure if GitHub refuses the code or the profile call fails.
 */
suspend fun exchangeGitHubCode(
    httpClient: HttpClient,
    credentials: ProviderCredentials,
    code: String,
    redirectUri: String,
    codeVerifier: String,
): ProviderIdentity {
    val token = runCatching {
        httpClient.submitForm(
            url = "https://github.com/login/oauth/access_token",
            formParameters = Parameters.build {
                append("client_id", credentials.clientId)
                append("client_secret", credentials.clientSecret)
                append("code", code)
                append("redirect_uri", redirectUri)
                append("code_verifier", codeVerifier)
            },
        ) {
            // Without this GitHub answers `access_token=…&scope=…` as form
            // encoding, with a 200 and a content type that makes the JSON parser
            // throw something that sounds like a credentials problem. This one
            // line is the difference between a working exchange and an hour.
            header(HttpHeaders.Accept, "application/json")
        }.body<GitHubTokenResponse>()
    }.getOrElse { t ->
        logger.warn("GitHub token exchange failed: ${t.message}")
        throw SignInFailure("GitHub would not complete the sign-in.", t)
    }

    // GitHub reports failure as a 200 with an error field, so a successful HTTP
    // call is not a successful exchange.
    if (token.accessToken == null) {
        logger.warn("GitHub token exchange refused: ${token.error} — ${token.errorDescription}")
        throw SignInFailure("GitHub would not complete the sign-in.")
    }

    val user = runCatching {
        httpClient.get("https://api.github.com/user") {
            header(HttpHeaders.Authorization, "Bearer ${token.accessToken}")
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }.body<GitHubUser>()
    }.getOrElse { t ->
        logger.warn("GitHub /user failed: ${t.message}")
        throw SignInFailure("Signed in with GitHub, but could not read the profile.", t)
    }

    // `login` is the fallback rather than the email: unlike Google, GitHub's
    // /user does not reliably carry one — a private address comes back null,
    // and the real addresses need a second call to /user/emails with the
    // user:email scope. That call belongs with the users table, which needs an
    // email to store; a display name doesn't. See docs/oauth-instructions.html.
    return ProviderIdentity(
        provider = AuthProvider.GITHUB,
        // Long → String: the users table stores every provider's id as TEXT,
        // because Google's is not a number. See provider_id in Users.sq.
        providerId = user.id.toString(),
        displayName = displayName(user.name, email = null, fallback = user.login),
    )
}
