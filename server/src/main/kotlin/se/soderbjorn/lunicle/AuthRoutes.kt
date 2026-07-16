/**
 * The sign-in endpoints.
 *
 * Two providers, two shapes, for a reason that is entirely GitHub's:
 *
 *  - **Google** has a JS SDK. The browser opens the popup itself via
 *    `initCodeClient`, gets a code in a callback, and POSTs it here. The server
 *    never sees the popup.
 *  - **GitHub** has no SDK, so we build the popup dance by hand: the browser
 *    opens [ApiRoutes.AUTH_GITHUB_START], we redirect to GitHub, GitHub returns
 *    the popup to [ApiRoutes.AUTH_GITHUB_CALLBACK], and that page posts the
 *    result to its opener and closes itself.
 *
 * Both providers refuse to be framed — verified, see docs/oauth-instructions.html
 * — so a popup is the only path for either.
 *
 * @see OAuthProviders
 * @see SessionStore
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.host
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.request.receive
import io.ktor.util.date.GMTDate
import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.GoogleCodeRequest
import se.soderbjorn.lunicle.clientserver.SessionState
import se.soderbjorn.lunicle.clientserver.SignedInUser
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("AuthRoutes")

/**
 * A GitHub sign-in that has started but not finished: the PKCE verifier we must
 * remember until the callback, keyed by the `state` we sent GitHub.
 *
 * In memory alongside [SessionStore], and for the same reason — this whole slice
 * is deliberately un-persisted. Entries are consumed on use, so a completed
 * sign-in leaves nothing behind.
 */
private class PendingGitHubAuths {
    private val pending = ConcurrentHashMap<String, String>()
    private val random = SecureRandom()

    /** Mint a `state` and a PKCE verifier, remember the pair, return both. */
    fun start(): Pair<String, String> {
        val state = randomToken()
        val verifier = randomToken()
        pending[state] = verifier
        return state to verifier
    }

    /**
     * Take the verifier for [state], removing it.
     *
     * Single-use by construction: a replayed callback finds nothing and is
     * rejected, which is most of what `state` is for.
     */
    fun consume(state: String?): String? = state?.let(pending::remove)

    private fun randomToken(): String =
        ByteArray(32).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}

/** S256, as GitHub's PKCE requires. */
private fun codeChallengeFor(verifier: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(verifier.toByteArray())
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

/**
 * This server's own origin, as the browser reached it.
 *
 * Both providers need to be told where we are, and getting it wrong is a
 * `redirect_uri_mismatch` rather than anything self-explanatory. Derived from
 * the request rather than configured because it legitimately differs —
 * `http://localhost:8080` locally, `https://lunicle.lunamux.dev` deployed — and
 * a config value would be one more thing to set correctly in two places.
 *
 * `X-Forwarded-Proto` is honoured because Railway terminates TLS at its edge and
 * speaks plain HTTP to the container: without it every deployed request looks
 * like `http://` and Google rejects the exchange. That failure only ever appears
 * in production, which is the worst place to first meet it.
 */
private fun ApplicationCall.serverOrigin(): String {
    val forwardedProto = request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim()
    val scheme = forwardedProto?.takeIf { it.isNotBlank() } ?: request.local.scheme
    val host = request.host()
    val port = request.headers["X-Forwarded-Port"]?.substringBefore(',')?.trim()?.toIntOrNull()
        ?: request.local.serverPort
    val isDefaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
    return if (isDefaultPort) "$scheme://$host" else "$scheme://$host:$port"
}

/** Read the caller's session cookie. */
private fun ApplicationCall.sessionId(): String? = request.cookies[SESSION_COOKIE]

/**
 * Attach a session cookie.
 *
 * `HttpOnly` so no script can read it — the session id is a bearer credential
 * and the browser is the only thing that needs it. `SameSite=None` because this
 * server is loaded in an iframe on lunamux.dev: the cookie rides requests made
 * from a *cross-site* context, and `Lax` — the browser default — would omit it,
 * signing the user out on every navigation for reasons that appear nowhere.
 * `None` demands `Secure`, which is why local development over plain HTTP gets
 * `Lax`: the browser rejects `None` without `Secure`, and rejecting our own
 * cookie would break the local loop entirely.
 */
private fun ApplicationCall.setSessionCookie(id: String) {
    val isSecure = serverOrigin().startsWith("https://")
    response.headers.append(
        HttpHeaders.SetCookie,
        buildString {
            append("$SESSION_COOKIE=$id; Path=/; HttpOnly")
            append(if (isSecure) "; Secure; SameSite=None" else "; SameSite=Lax")
        },
    )
}

/** Expire the session cookie. */
private fun ApplicationCall.clearSessionCookie() {
    response.cookies.append(
        name = SESSION_COOKIE,
        value = "",
        encoding = CookieEncoding.RAW,
        expires = GMTDate.START,
        path = "/",
    )
}

/** The caller's current [SessionState], given who (if anyone) they are. */
private fun sessionStateFor(user: SignedInUser?, config: OAuthConfig): SessionState =
    SessionState(
        user = user,
        isGoogleAvailable = config.google != null,
        isGitHubAvailable = config.github != null,
        googleClientId = config.google?.clientId,
    )

/**
 * Mount the sign-in routes.
 *
 * @param config which providers are live; an absent provider's endpoints 400
 *   rather than 404, because "not configured here" is a different fact from
 *   "no such route" and the difference matters when debugging a deploy.
 */
fun Route.authRoutes(
    config: OAuthConfig,
    sessions: SessionStore,
    httpClient: HttpClient = createProviderHttpClient(),
) {
    val pending = PendingGitHubAuths()

    // Never 401s. "Signed out" is a state the view renders, not an error it
    // handles — and an endpoint the whole UI depends on should not have a
    // failure mode for the most common case.
    get(ApiRoutes.SESSION) {
        call.respond(sessionStateFor(sessions.lookup(call.sessionId()), config))
    }

    post(ApiRoutes.SIGN_OUT) {
        sessions.destroy(call.sessionId())
        call.clearSessionCookie()
        call.respond(sessionStateFor(null, config))
    }

    post(ApiRoutes.AUTH_GOOGLE) {
        val credentials = config.google
        if (credentials == null) {
            call.respond(HttpStatusCode.BadRequest, "Google sign-in is not configured on this server.")
            return@post
        }
        val request = runCatching { call.receive<GoogleCodeRequest>() }.getOrNull()
        if (request == null || request.code.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing authorization code.")
            return@post
        }
        try {
            // The origin, not a path — see exchangeGoogleCode's docs.
            val user = exchangeGoogleCode(httpClient, credentials, request.code, call.serverOrigin())
            call.setSessionCookie(sessions.create(user))
            logger.info("Signed in via Google: ${user.displayName}")
            call.respond(sessionStateFor(user, config))
        } catch (failure: SignInFailure) {
            call.respond(HttpStatusCode.BadGateway, failure.userMessage)
        }
    }

    get(ApiRoutes.AUTH_GITHUB_START) {
        val credentials = config.github
        if (credentials == null) {
            call.respondText("GitHub sign-in is not configured on this server.", status = HttpStatusCode.BadRequest)
            return@get
        }
        val (state, verifier) = pending.start()
        val redirectUri = call.serverOrigin() + ApiRoutes.AUTH_GITHUB_CALLBACK
        val url = "https://github.com/login/oauth/authorize" +
            "?client_id=${credentials.clientId.encodeURLParameter()}" +
            "&redirect_uri=${redirectUri.encodeURLParameter()}" +
            "&scope=${"read:user user:email".encodeURLParameter()}" +
            "&state=${state.encodeURLParameter()}" +
            "&code_challenge=${codeChallengeFor(verifier).encodeURLParameter()}" +
            "&code_challenge_method=S256"
        call.respondRedirect(url)
    }

    get(ApiRoutes.AUTH_GITHUB_CALLBACK) {
        val credentials = config.github
        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]
        val verifier = pending.consume(state)

        val failure = when {
            credentials == null -> "GitHub sign-in is not configured on this server."
            // A callback whose state we don't recognise is either a replay, a
            // forgery, or a very stale tab. None of them should mint a session.
            verifier == null -> "This sign-in link has expired. Close this window and try again."
            code.isNullOrBlank() -> "GitHub did not return an authorization code."
            else -> null
        }
        if (failure != null) {
            logger.warn("GitHub callback rejected: $failure")
            call.respondText(popupClosingPage(error = failure), ContentType.Text.Html)
            return@get
        }

        try {
            val user = exchangeGitHubCode(
                httpClient = httpClient,
                credentials = credentials!!,
                code = code!!,
                redirectUri = call.serverOrigin() + ApiRoutes.AUTH_GITHUB_CALLBACK,
                codeVerifier = verifier!!,
            )
            call.setSessionCookie(sessions.create(user))
            logger.info("Signed in via GitHub: ${user.displayName}")
            call.respondText(popupClosingPage(error = null), ContentType.Text.Html)
        } catch (f: SignInFailure) {
            call.respondText(popupClosingPage(error = f.userMessage), ContentType.Text.Html)
        }
    }
}

/**
 * The page GitHub's popup lands on: tell the opener, close.
 *
 * The user should never read this — a correct sign-in shows it for a few
 * milliseconds. It carries no styling for that reason, and says something
 * only when it is about to *not* close, which is the case where a human is
 * suddenly looking at it.
 *
 * `postMessage` is targeted at this server's own origin rather than `*`: the
 * opener is our bundle, on our origin, and a wildcard would broadcast the
 * outcome to whatever else might be listening.
 */
private fun popupClosingPage(error: String?): String {
    val payload = if (error == null) {
        """{ lunicle: "github-signin", ok: true }"""
    } else {
        // JSON-encode via the same escaping the message uses, so a quote in a
        // provider's message cannot break out of the string and into the script.
        """{ lunicle: "github-signin", ok: false, error: ${error.toJsString()} }"""
    }
    return """
        <!doctype html>
        <html lang="en">
        <head><meta charset="utf-8"><title>Signing in…</title></head>
        <body style="background:#04090f;color:#cfe0ee;font:14px ui-monospace,monospace;padding:24px">
        <p id="msg">${if (error == null) "Signed in. You can close this window." else error.escapeHtml()}</p>
        <script>
          (function () {
            var payload = $payload;
            // window.opener is null if COOP severed the link — the exact failure
            // docs/oauth-instructions.html Step 4 is about. Say so rather than
            // closing silently and leaving a sign-in that "does nothing".
            if (window.opener) {
              window.opener.postMessage(payload, window.location.origin);
              window.close();
            } else {
              document.getElementById("msg").textContent =
                "Signed in, but this window could not talk to the page that opened it. " +
                "Close it and reload. (COOP severed window.opener.)";
            }
          })();
        </script>
        </body>
        </html>
    """.trimIndent()
}

/** Minimal HTML escaping for the one place we interpolate a message into markup. */
private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Minimal JS string literal escaping, for the same message inside the script. */
private fun String.toJsString(): String =
    buildString {
        append('"')
        this@toJsString.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                '&' -> append("\\u0026")
                else -> append(c)
            }
        }
        append('"')
    }
