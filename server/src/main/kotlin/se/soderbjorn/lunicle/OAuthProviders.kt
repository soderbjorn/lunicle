/**
 * Talking to Google: authorization code in, display name out.
 *
 * Everything provider-specific lives here so [AuthRoutes] can stay about HTTP
 * and sessions, most of which is documented in docs/oauth-instructions.html.
 *
 * @see OAuthConfig
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
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
 * `ignoreUnknownKeys` because Google returns far more than we read and adds
 * fields without notice.
 */
internal fun createProviderHttpClient(): HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    // Deadlines, which this client had none of (LUS-33).
    //
    // The per-project mutex and freshness window in the statistics repository cap
    // how many *upstream calls* happen, which is the half that costs money. What was
    // uncapped is how many requests sit blocked on an upstream that has stopped
    // answering — and the statistics refresh is deliberately not admin-gated and is
    // reachable anonymously on a project with a guest audience row.
    //
    // Three limits rather than one, because they fail differently: `connect` is a
    // host that is not there, `socket` is a connection that goes quiet mid-body, and
    // `request` is the whole exchange, which is the only one that bounds a server
    // answering slowly on purpose.
    //
    // Fifteen seconds end to end. Google's token endpoint answers in well under a
    // second and GitHub's API in a few; past this is a provider having a bad day,
    // and a caller who waited the full fifteen has already had a worse time than the
    // refusal would have given them.
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 10_000
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

    /**
     * Whether Google has itself confirmed [email].
     *
     * Google has always sent this and nothing here ever read it — `ignoreUnknownKeys`
     * (see [createProviderHttpClient]) ate it silently, so before LNL-73 there was
     * no reference to `email_verified` anywhere in the repository. That was
     * harmless while the address was decorative and is not harmless now that it is
     * the account key: an unconfirmed Google address is a string somebody typed
     * into a profile, and treating it as identity would mean typing a victim's
     * address into a fresh Google account was a way into their Lunicle row.
     *
     * Defaulted to false, which is the safe direction on both edges: an older
     * response that omits it, and a Google that stops sending it, both land on
     * "not proved" and produce an unkeyed account rather than a wrong one.
     */
    @SerialName("email_verified") val emailVerified: Boolean = false,

    /**
     * The Google Workspace hosted domain this account belongs to, e.g.
     * `framna.com` — absent for a personal (`@gmail.com`) account and for any
     * account outside a Workspace org. Read only by the optional hosted-domain
     * gate in [exchangeGoogleCode] (LNL-125); it is not identity and never keys
     * anything. Defaulted to null so a response that omits it is simply "no
     * domain", which the gate treats as not-a-match.
     */
    val hd: String? = null,
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
 * @property providerName what this provider calls them, resolved per provider —
 *   GitHub's `login`, Google's `name`. Never blank; see [resolveProviderName].
 * @property email the address, **normalized and proved, or null**.
 *
 *   The meaning of this field narrowed in LNL-73 and the narrowing is the whole
 *   design. It used to be "what the provider told us", carrying an unconfirmed
 *   address as happily as a confirmed one, and it was explicitly *never* an
 *   identity key. It is the identity key now — so an address the provider has not
 *   itself confirmed arrives here as null rather than as a value with a caveat
 *   attached. There is no `isVerified` beside it, deliberately: a nullable field
 *   plus a boolean is four states for a question that has two, and the two
 *   nonsense states are exactly the ones a caller would eventually construct.
 *
 *   Null still means "we do not know", and an account with no usable address
 *   still works — it becomes an unkeyed row, found again by
 *   `(provider, provider_id)` as before. See [UserStore.upsert].
 */
data class ProviderIdentity(
    val provider: AuthProvider,
    val providerId: String,
    val providerName: String,
    val email: String?,
)

/**
 * Turn a name, an email and a fallback into something renderable.
 *
 * A signed-in user whose name renders as an empty string reads as a bug, and
 * both providers can return one: Google's `name` is absent for some account
 * types, GitHub's is null whenever the user never filled in their profile —
 * which is extremely common. So: the provider's name, else the local part of
 * the email, else the caller's fallback.
 *
 * Every branch terminates, which is what lets `users.provider_name` be NOT NULL
 * and no reader ever have to handle a nameless user.
 */
private fun resolveProviderName(name: String?, email: String?, fallback: String): String =
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
 * @param hostedDomain when non-null, the one Google Workspace domain this
 *   deployment admits (LNL-125) — a branded install may pin sign-in to, say,
 *   `framna.com`. Any account whose `hd` claim does not match is refused here.
 *   Null on every non-branded install, which skips the check entirely and leaves
 *   the flow byte-for-byte as before. This is the real boundary: the matching
 *   `hd` hint on the client's `initCodeClient` only filters the chooser and can
 *   be bypassed, so the domain is enforced server-side or not at all.
 * @return the identity behind the code, for [UserStore.upsert] to turn into an
 *   account.
 * @throws SignInFailure if Google refuses the code, the profile call fails, or a
 *   configured [hostedDomain] does not match the account's `hd`.
 */
suspend fun exchangeGoogleCode(
    httpClient: HttpClient,
    credentials: ProviderCredentials,
    code: String,
    redirectUri: String,
    hostedDomain: String? = null,
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

    // A branded deployment may pin sign-in to one Workspace domain (LNL-125). When
    // configured, an account whose `hd` claim does not match is refused here — the
    // server side of the check, since the client's `hd` hint is only a chooser
    // filter and can be stripped. Compared case-insensitively (domains are), and a
    // personal account (no `hd`) never matches a configured domain. Absent config
    // — every non-branded install — skips this and the flow is unchanged.
    if (hostedDomain != null && !hostedDomain.equals(info.hd, ignoreCase = true)) {
        logger.warn("Google sign-in refused: hd=${info.hd ?: "(none)"} does not match required $hostedDomain")
        throw SignInFailure("Only $hostedDomain Google accounts can sign in to this deployment.")
    }

    // An address Google has not itself confirmed is not an identity, so it does
    // not become one: it is dropped here rather than carried forward with a
    // caveat, and the account resolves by (provider, provider_id) as it always
    // did. See ProviderIdentity.email.
    val verifiedEmail = normalizeEmail(info.email)?.takeIf { info.emailVerified }

    // The sidebar says "Signed in via Google as <Google-name>", so the name is
    // the field that matters here. Note it still falls back to the local part of
    // the *unverified* address: a name is a label, not a claim about who somebody
    // is, and "Google user" for an account that plainly told us a name would be
    // worse rather than safer.
    return ProviderIdentity(
        provider = AuthProvider.GOOGLE,
        providerId = info.sub,
        providerName = resolveProviderName(info.name, info.email, fallback = "Google user"),
        email = verifiedEmail,
    )
}
