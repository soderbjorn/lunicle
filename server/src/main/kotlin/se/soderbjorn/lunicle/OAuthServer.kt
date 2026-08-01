/**
 * Lunicle's OAuth 2.1 authorization server.
 *
 * ── The realization this whole file hangs on ────────────────────────────────
 *
 * Everywhere else in this server, Lunicle is an OAuth **client**: Google is the
 * authorization server, and [authRoutes] consumes the codes it issues. This file
 * is the opposite role. Here Lunicle *is* the authorization server, minting its
 * own tokens whose audience is Lunicle itself.
 *
 * That is not a technicality worth working around. The MCP specification says a
 * client MUST NOT send a token to a server its own authorization server did not
 * issue, and a server MUST validate that a token was issued for it specifically.
 * So none of the shortcuts work, and none should be attempted:
 *
 *  - Handing the agent a Google or GitHub access token: wrong audience, and the
 *    spec forbids accepting it.
 *  - Handing the agent the `lunicle_session` cookie value: not a bearer token, no
 *    expiry check on read, no revocation story, and no client identity — you
 *    would never know *which* agent was acting.
 *  - A pasted personal access token: Framnaflow built exactly this, shipped it,
 *    and then turned it off. Take the result and skip the detour.
 *
 * ── The half that already worked ────────────────────────────────────────────
 *
 * An authorization server's hard problem is "who is this human?", and this
 * server has answered that in production over two providers since Stage 2:
 * `users.upsert(identity)` → `sessions.create(user.id)`. This file is the token
 * half, not the login half. That is why it is one file and not a subsystem.
 *
 * ── Every route here is unauthenticated, by design ──────────────────────────
 *
 * There is no API key, no client secret, and no bearer token on anything below.
 * That is what lets `claude mcp add` work with a URL and nothing else. Security
 * rests on four things, and it is worth naming them because none of them is
 * "the endpoint is hard to find":
 *
 *  1. **PKCE.** The code is useless without a verifier that never left the agent.
 *  2. **Exact redirect-URI matching**, against URIs the client registered first.
 *  3. **A real Lunicle sign-in** — the same session cookie the web app uses.
 *  4. **The consent click.** This is the actual security boundary. Not the
 *     toggle, which is an affordance and a comfort; this.
 *
 * @see OAuthStores
 * @see McpServer
 * @see AuthRoutes
 */
package se.soderbjorn.lunicle

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.clientserver.ApiRoutes

private val logger = LoggerFactory.getLogger("OAuthServer")

/** Where the MCP endpoint lives. The `resource` every token is minted for. */
const val MCP_PATH = "/mcp"

/** How many callbacks one registration may claim. See [registrationRoute]. */
private const val MAX_REDIRECT_URIS = 8

/** How long one of them may be. See [registrationRoute]. */
private const val MAX_REDIRECT_URI_LENGTH = 2048

/** Everything the authorization server, the MCP transport and the Connections section need. */
class McpDependencies(
    val clients: se.soderbjorn.lunicle.store.OAuthClientStore,
    val loginStates: se.soderbjorn.lunicle.store.OAuthLoginStateStore,
    val codes: se.soderbjorn.lunicle.store.OAuthCodeStore,
    val tokens: se.soderbjorn.lunicle.store.OAuthTokenStore,
    /**
     * Turns the session cookie into a person — the half of an authorization server
     * that already worked here, in production, over two providers.
     */
    val sessions: se.soderbjorn.lunicle.store.SessionStore,
    val users: se.soderbjorn.lunicle.store.UserStore,
    /**
     * Read only by [mcpApiRoutes], and never by the token path.
     *
     * The Connections section is a browser session, so a probing owner sees the
     * connections of the account they signed in as, like every other `/api` route.
     * A token names one person forever — see McpServer.resolveMcpUser, where that
     * asymmetry is the point rather than an oversight.
     */
    val impersonation: OwnerImpersonation = OwnerImpersonation(),
    /** Which providers the sign-in page may offer. See [signInPage]. */
    val config: OAuthConfig,
    /**
     * The deployment-wide settings, asked one question: is this account's **tier**
     * permitted to hold agent access (LNL-192)?
     *
     * Here because the permission stopped being a column on the user row and became
     * a switch on the instance — so the five MCP gates, which all read `canUseMcp`,
     * need something to read it *from*. Defaulted to an in-memory store for tests,
     * matching [BoardDependencies]; [Application.module] always passes the persistent
     * one, and deliberately the same object the board routes hold, so an
     * administrator's switch reaches the token path within one request.
     */
    val instanceSettings: se.soderbjorn.lunicle.store.InstanceSettingsStore = InMemoryInstanceSettingsStore(),
    /**
     * The permission oracle, asked one question by [mcpApiRoutes]: may this session's
     * real user be impersonating at all (LNL-197)?
     *
     * Read only through `resolveCaller`, and only for the Connections section's
     * browser-session path — never by the token path, which honours no impersonation.
     * Nullable and defaulted to null because the MCP tests wire a token path and no
     * permission oracle, and because null fails in the safe direction: a stale
     * impersonation is dropped rather than honoured unchecked. [Application.module]
     * passes the same object the board routes hold, so ownership transferred in one
     * place is ownership transferred everywhere within one request.
     */
    val access: AccessControl? = null,
)

/**
 * The `resource` a token is bound to: this server's `/mcp`.
 *
 * Composed from [serverOrigin] and nowhere else, which is the point. The `issuer`
 * in the discovery metadata and the `resource` in a token must agree byte for
 * byte with what a client computes, so there is exactly one function underneath
 * both. See [ApplicationCall.serverOrigin]'s comment for the production-only
 * failure that recomposing scheme + host + port produces.
 *
 * `internal` for that same reason and no other: [mcpApiRoutes] hands this exact
 * string to the user as the URL to paste into their agent, and a second way of
 * computing it would be a copy button that produces a URL the discovery documents
 * disagree with.
 */
internal fun ApplicationCall.mcpResource(): String = serverOrigin() + MCP_PATH

/** Respond with JSON, bypassing ContentNegotiation. See [mcpRoutes] for why that matters. */
private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: JsonElement) {
    respondText(body.toString(), ContentType.Application.Json, status)
}

/**
 * The three server-rendered pages' own `Content-Security-Policy`, replacing the
 * application's (LUS-27, LUS-21).
 *
 * [DefaultHeaders] skips a header the handler already set, so setting this here is
 * a wholesale replacement rather than a merge — the same mechanism the attachment
 * view uses, and the reason both can have a policy that suits them.
 *
 * Two things differ from the app's policy, and both matter.
 *
 * **`frame-ancestors 'none'`, always.** The global policy permits whatever
 * embedder a deployment configured, on *every* route — and the session cookie is
 * `SameSite=None` by design so that the tracker can be framed by a marketing site.
 * Together that means a deployment with an embedder configured could have its
 * consent page framed by that origin, and a compromise of it becomes a clickjack
 * of the Approve button: the one boundary this entire design rests on. There is no
 * legitimate reason to embed a consent page, so it is `'none'` regardless of
 * configuration rather than inheriting a decision made about the app.
 *
 * **Inline script and style are allowed**, because these pages are hand-written
 * HTML with both, and they are that way deliberately: they render before any
 * bundle has loaded, to somebody mid-flow between two applications, and a page
 * whose stylesheet 404s at that moment reads as "this is broken, do not approve".
 * The consent page itself carries no script at all — only the sign-in page does,
 * for the two providers — and every value interpolated into either is escaped. See
 * [String.escapeHtml] and [String.toJsStringLiteral].
 */
private const val OAUTH_PAGE_CSP: String =
    "default-src 'self'; " +
        "script-src 'self' 'unsafe-inline' https://accounts.google.com; " +
        "style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data:; " +
        "connect-src 'self' https://accounts.google.com; " +
        "frame-src https://accounts.google.com; " +
        "object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"

/** Respond with one of this file's pages, under [OAUTH_PAGE_CSP]. */
private suspend fun ApplicationCall.respondPage(html: String, status: HttpStatusCode = HttpStatusCode.OK) {
    response.header(CONTENT_SECURITY_POLICY, OAUTH_PAGE_CSP)
    respondText(html, ContentType.Text.Html, status)
}

/** Ktor's `HttpHeaders` has no constant for this one. */
private const val CONTENT_SECURITY_POLICY = "Content-Security-Policy"

/** An OAuth error body, as RFC 6749 shapes it. */
private fun oauthError(error: String, description: String? = null): JsonObject = buildJsonObject {
    put("error", error)
    if (description != null) put("error_description", description)
}

/**
 * Minimal HTML escaping for the pages below.
 *
 * Not optional and not ceremony: [OAuthClientRecord.clientName] is a string a
 * stranger chose at an unauthenticated endpoint, and it is interpolated into the
 * consent page. Without this, registering a client named `<script>…` would put
 * that script on a page carrying the user's session cookie, on our origin, right
 * as they are being asked to approve something. See OAuthClients.sq.
 */
private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

/**
 * May a client register this redirect URI?
 *
 * Gates the *shape* at registration time, and only that. The exact value is what
 * matters later, and it is matched exactly against what this call registered —
 * see [OAuthClientStore.isRegisteredRedirectUri].
 *
 * Loopback http is allowed because that is where every CLI agent listens, and a
 * custom app scheme because that is where every desktop one does. Everything else
 * must be https: a plain-http redirect to a real host would carry an
 * authorization code across the internet in cleartext.
 */
internal fun isAllowedRedirectUri(uri: String): Boolean = when {
    uri.startsWith("https://") -> true
    // Loopback only. Note the prefix check is deliberately on the scheme+host and
    // not a `contains`: "http://localhost.evil.com" must not pass, and it does not
    // — but "http://localhost:1234" and "http://localhost/cb" both must.
    uri.startsWith("http://localhost:") || uri == "http://localhost" || uri.startsWith("http://localhost/") -> true
    uri.startsWith("http://127.0.0.1:") || uri == "http://127.0.0.1" || uri.startsWith("http://127.0.0.1/") -> true
    // A custom app scheme, e.g. cursor:// or claude://. Anything that parses as a
    // scheme and is not plain http — http is covered exhaustively above, so
    // reaching here with it means it was neither https nor loopback.
    !uri.startsWith("http://") && Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://").containsMatchIn(uri) -> true
    else -> false
}

/**
 * Mount the authorization server.
 *
 * @param deps the stores, plus the session and user stores that answer "who is
 *   this human?" — which is the half that already worked.
 */
fun Route.oauthRoutes(deps: McpDependencies) {
    // ── Every route below is unauthenticated, so every route below is metered ──
    //
    // Rate limiting existed in this server and covered two endpoints out of the
    // whole of it, neither of them here (LUS-31). Everything in this file is
    // reachable by anyone on the internet with no credential of any kind, which is
    // the design — see the preamble — and the counterweight it was missing.
    //
    // Two limiters rather than one, because registration and the flow are not the
    // same kind of traffic and one limit for both would have to be the looser.
    // Both are per route-installation, which is per process: these routes are
    // mounted once, at startup.
    val limits = OAuthRateLimits()
    discoveryRoutes()
    registrationRoute(deps, limits)
    authorizeRoutes(deps, limits)
    tokenRoute(deps, limits)
    revocationRoute(deps, limits)
}

/**
 * The limiters in front of the authorization server.
 *
 * @property registration how often one client identity may create client rows.
 *   Tight, because this is the only endpoint on the server where an anonymous
 *   caller writes a row: registration is unauthenticated by design (RFC 7591) and
 *   the disk cost was bounded only by a startup sweep. Five in fifteen minutes is
 *   past anything a real agent does — a client registers once and reuses the id —
 *   and worth nothing to somebody filling a volume.
 * @property flow how often one client identity may drive authorize, token and
 *   revoke. Much looser, because these are the endpoints a working agent actually
 *   uses: a refresh every so often, plus a handful of round trips per connection,
 *   and several people can share one NAT'd address. Sixty in fifteen minutes
 *   leaves that comfortably alone while capping a loop.
 *
 *   Credential guessing is not what this is for — a thirty-two-byte secret is not
 *   guessable at sixty attempts a quarter of an hour or at any other rate. It is
 *   for the O(table) work each call costs and for the outbound consequences.
 */
private class OAuthRateLimits(
    val registration: RateLimiter = RateLimiter(limit = 5, windowMillis = 15L * 60 * 1000),
    val flow: RateLimiter = RateLimiter(limit = 60, windowMillis = 15L * 60 * 1000),
)

/**
 * Spend one from [limiter] for this call's client, or answer `429` and return false.
 *
 * An OAuth error body rather than [respondRateLimited]'s plain text, because every
 * other refusal from these endpoints is one and a client that parses JSON should
 * not meet a sentence. `Retry-After` rides along either way.
 */
private suspend fun ApplicationCall.withinOAuthLimit(limiter: RateLimiter, key: String): Boolean {
    val decision = limiter.tryAcquire("$key:${clientIdentity()}")
    if (decision !is RateLimitDecision.Refused) return true
    response.header(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
    logger.info("MCP: rate limited ${request.local.uri} — retry after ${decision.retryAfterSeconds}s")
    respondJson(HttpStatusCode.TooManyRequests, oauthError("slow_down", "Too many requests. Try again shortly."))
    return false
}

// ── Discovery (RFC 9728 / RFC 8414) ──────────────────────────────────────────

/**
 * The well-known documents, at every path a client might probe.
 *
 * **Five routes, not two, and that is the trap.** Different clients append
 * `/mcp` to the well-known path, or ask for `openid-configuration` instead of
 * `oauth-authorization-server`. Each alias is one line. Skipping them produces a
 * client that silently cannot discover the authorization server — which looks
 * exactly like the server being down, with nothing in the log to distinguish
 * them.
 */
private fun Route.discoveryRoutes() {
    suspend fun ApplicationCall.protectedResourceMetadata() {
        val origin = serverOrigin()
        respondJson(
            HttpStatusCode.OK,
            buildJsonObject {
                put("resource", mcpResource())
                // We are our own authorization server. That sentence is the whole
                // point of this file.
                putJsonArray("authorization_servers") { add(origin) }
                putJsonArray("bearer_methods_supported") { add("header") }
                putJsonArray("scopes_supported") { add(MCP_SCOPE) }
            },
        )
    }

    suspend fun ApplicationCall.authorizationServerMetadata() {
        val origin = serverOrigin()
        respondJson(
            HttpStatusCode.OK,
            buildJsonObject {
                put("issuer", origin)
                put("authorization_endpoint", "$origin/oauth/authorize")
                put("token_endpoint", "$origin/oauth/token")
                put("registration_endpoint", "$origin/oauth/register")
                put("revocation_endpoint", "$origin/oauth/revoke")
                putJsonArray("response_types_supported") { add("code") }
                putJsonArray("grant_types_supported") { add("authorization_code"); add("refresh_token") }
                // S256 only. "plain" is in the spec and is worthless — the
                // challenge would equal the verifier, so an intercepted
                // authorization request would carry everything needed to redeem
                // the code it produces.
                putJsonArray("code_challenge_methods_supported") { add("S256") }
                // Every MCP client is a public client: a CLI on a laptop cannot
                // keep a secret, so pretending it has one would be theatre. There
                // are no client secrets anywhere in this design, which is why
                // PKCE is load-bearing rather than defence in depth.
                putJsonArray("token_endpoint_auth_methods_supported") { add("none") }
                putJsonArray("scopes_supported") { add(MCP_SCOPE) }
            },
        )
    }

    get("/.well-known/oauth-protected-resource") { call.protectedResourceMetadata() }
    get("/.well-known/oauth-protected-resource/mcp") { call.protectedResourceMetadata() }
    get("/.well-known/oauth-authorization-server") { call.authorizationServerMetadata() }
    get("/.well-known/oauth-authorization-server/mcp") { call.authorizationServerMetadata() }
    // Costs one line and saves a client that only speaks OIDC discovery.
    get("/.well-known/openid-configuration") { call.authorizationServerMetadata() }
}

// ── Dynamic Client Registration (RFC 7591) ───────────────────────────────────

/**
 * Let an agent register itself.
 *
 * Unauthenticated, which is the entire reason no secret is ever pasted — and
 * which means anyone on the internet can write rows here. That is tolerable only
 * because a registration grants nothing: it is a name and a callback address, and
 * it becomes a token only by surviving a real sign-in and a human's consent
 * click. The disk cost is bounded by [OAuthClientStore.sweepStale].
 *
 * RFC 7591 is formally deprecated in favour of Client ID Metadata Documents, and
 * retained for compatibility. It remains the path every shipping client actually
 * takes, which is the only thing that matters here.
 */
private fun Route.registrationRoute(deps: McpDependencies, limits: OAuthRateLimits) {
    post("/oauth/register") {
        if (!call.withinOAuthLimit(limits.registration, "oauth-register")) return@post
        val root = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        if (root == null) {
            call.respondJson(HttpStatusCode.BadRequest, oauthError("invalid_client_metadata"))
            return@post
        }

        val redirectUris = (root["redirect_uris"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        // Refused as a whole rather than filtered down to the acceptable ones. A
        // client that asked for one good callback and one bad one has a bug, and
        // silently registering half its request would surface as a
        // redirect_uri mismatch later, far from the cause.
        if (redirectUris.isEmpty() || redirectUris.any { !isAllowedRedirectUri(it) }) {
            call.respondJson(
                HttpStatusCode.BadRequest,
                oauthError(
                    "invalid_redirect_uri",
                    "redirect_uris must be https, loopback http, or a custom app scheme.",
                ),
            )
            return@post
        }
        // Bounded, unlike the client name beside it, which was already capped
        // (LUS-31). The array was not: an anonymous caller could register a client
        // carrying megabytes of callbacks, stored in one row and swept only at
        // startup — and every authorize on that client then walks the list.
        //
        // Refused rather than truncated, which is the opposite of what the name
        // does one block down, and deliberately: a name is decoration and losing
        // some of it breaks nothing, while silently dropping a callback would
        // surface as a redirect_uri mismatch later, far from the cause. Same
        // reasoning as the whole-request refusal directly above.
        //
        // Eight and two kilobytes are both far past any real client — an agent
        // registers one loopback callback, occasionally two while a port is
        // uncertain — and both are small enough that the worst a caller can store
        // per registration is measured in kilobytes rather than in whatever they
        // felt like sending.
        if (redirectUris.size > MAX_REDIRECT_URIS || redirectUris.any { it.length > MAX_REDIRECT_URI_LENGTH }) {
            call.respondJson(
                HttpStatusCode.BadRequest,
                oauthError(
                    "invalid_redirect_uri",
                    "At most $MAX_REDIRECT_URIS redirect_uris, each under $MAX_REDIRECT_URI_LENGTH characters.",
                ),
            )
            return@post
        }

        // Self-reported, never trusted, and bounded: this string is stored, shown
        // on the consent page and shown in the Connections list, so an agent
        // registering a kilobyte of "name" would be filling a volume and wrecking
        // two layouts. Truncation rather than refusal — the name is decoration,
        // and refusing a registration over it would break a connection for a
        // cosmetic reason.
        val clientName = root["client_name"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf { it.isNotBlank() }?.take(80)
            ?: "An MCP client"
        val grantTypes = (root["grant_types"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("authorization_code", "refresh_token")

        val client = deps.clients.register(clientName, redirectUris, grantTypes)
        call.respondJson(
            HttpStatusCode.Created,
            buildJsonObject {
                put("client_id", client.clientId)
                put("client_id_issued_at", client.createdAt / 1000)
                putJsonArray("redirect_uris") { client.redirectUris.forEach { add(it) } }
                putJsonArray("grant_types") { client.grantTypes.forEach { add(it) } }
                putJsonArray("response_types") { add("code") }
                put("token_endpoint_auth_method", "none")
            },
        )
    }
}

// ── Authorization + consent ──────────────────────────────────────────────────

/**
 * Append a query parameter to a redirect URI that has already been validated.
 *
 * The "already been validated" is doing all the work in that sentence. This is
 * only ever called with a URI confirmed to be one the client registered — see
 * [authorizeRoutes], where the ordering is the point.
 */
private fun redirectWith(redirectUri: String, query: String, clientState: String): String {
    val separator = if ("?" in redirectUri) "&" else "?"
    val state = if (clientState.isNotEmpty()) "&state=${clientState.encodeURLParameter()}" else ""
    return "$redirectUri$separator$query$state"
}

private fun Route.authorizeRoutes(deps: McpDependencies, limits: OAuthRateLimits) {
    /**
     * Where the agent sends the user's browser.
     *
     * ── Why the session cookie is readable here ─────────────────────────────
     *
     * This is a **top-level browser navigation**, not a framed or cross-site
     * fetch. In production the cookie is `SameSite=None; Secure` and rides along;
     * locally it is `SameSite=Lax`, and Lax sends on a top-level GET navigation.
     * Both work with no change to cookie policy. The CSP `frame-ancestors` header
     * is irrelevant — it governs who may *embed* a page, and nobody is embedding
     * this one.
     *
     * ── Why we do not re-federate ───────────────────────────────────────────
     *
     * Framnaflow's equivalent redirects straight to Google, because Google is its
     * only provider. Lunicle has two and cannot blindly pick one: it would need a
     * provider chooser and a second federation callback, with a new redirect URI
     * registered in Google's console. Reading the cookie instead is less code, a
     * better experience, and needs no new provider configuration.
     */
    get("/oauth/authorize") {
        if (!call.withinOAuthLimit(limits.flow, "oauth-flow")) return@get
        val params = call.request.queryParameters
        val clientId = params["client_id"]
        val redirectUri = params["redirect_uri"]
        val clientState = params["state"].orEmpty()

        // ── Validate the client and redirect_uri BEFORE redirecting anywhere ──
        //
        // The ordering here is the whole point, and getting it wrong is an open
        // redirector with our name on it: if client_id or redirect_uri is bad we
        // must render a plain error page, because sending an error TO an
        // unvalidated redirect_uri would make this endpoint forward a browser
        // anywhere an attacker names. Only once the URI is confirmed to be one
        // this client registered may an error be sent there.
        val client = clientId?.let { deps.clients.find(it) }
        if (client == null || redirectUri == null || !deps.clients.isRegisteredRedirectUri(client.clientId, redirectUri)) {
            call.respondPage(
                errorPage(
                    "That link is not valid",
                    "The application did not identify itself correctly, so Lunicle will not " +
                        "continue. Nothing has been shared. Try connecting again from the app " +
                        "that sent you here.",
                ),
                HttpStatusCode.BadRequest,
            )
            return@get
        }

        // Past this line the redirect URI is one the client registered, so
        // errors may be delivered to it.
        if (params["response_type"] != "code") {
            call.respondRedirect(redirectWith(redirectUri, "error=unsupported_response_type", clientState))
            return@get
        }
        val codeChallenge = params["code_challenge"]
        if (codeChallenge.isNullOrBlank() || params["code_challenge_method"] != "S256") {
            call.respondRedirect(redirectWith(redirectUri, "error=invalid_request", clientState))
            return@get
        }

        deps.clients.touch(client.clientId)

        // The effective user is deliberately NOT used here — see resolveMcpUser.
        val user = deps.sessions.lookup(call.request.cookies[SESSION_COOKIE])
        if (user == null) {
            // Sign in first, then come back. No login-state row is written for a
            // visitor who has not signed in: the page below reloads this exact URL
            // once the cookie lands, so the whole request replays and arrives here
            // with a user. A row created now would be a row abandoned by everyone
            // who wanders off at the provider's password prompt.
            call.respondPage(signInPage(deps.config, client.clientName))
            return@get
        }

        if (!deps.instanceSettings.canUseMcp(user)) {
            // Deliberately a page for the human, not `error=access_denied` to the
            // agent. The agent's error would be a one-liner in a terminal — "access
            // denied" — while the person who can actually fix this is looking at
            // this tab, and what they need is the sentence telling them what to do
            // next. The cost is that the agent waits for a callback that never
            // comes and eventually times out; the benefit is that the user knows
            // why. That trade is right for a feature nobody has enabled yet, which
            // is the overwhelmingly common case for this branch.
            //
            // The two halves of canUseMcp get different pages, and that difference
            // is the whole reason this branch is not one sentence. "Not permitted"
            // is not something the reader can fix — there is no switch in their
            // profile to find, because the dialog hides it — so sending them to
            // look for one would have them hunting a control that is not there.
            // "Permitted but off" is theirs to fix in two clicks. Telling somebody
            // to flip a switch they do not have is worse than telling them nothing.
            val (title, message) = if (!deps.instanceSettings.permitsAgentsFor(user)) {
                "Agent access is not available for your account" to
                    "${client.clientName} asked to act on your behalf, but an administrator has not " +
                        "given your account agent access. Ask an administrator of this Lunicle to " +
                        "turn it on for you, then connect again."
            } else {
                "Agent access is turned off" to
                    "${client.clientName} asked to act on your behalf, but you have not turned on " +
                        "agent access. Open Lunicle, click your name, and switch on \"Let AI agents " +
                        "act on your behalf\" — then connect again."
            }
            call.respondPage(
                errorPage(title, message),
                HttpStatusCode.Forbidden,
            )
            return@get
        }

        val loginState = deps.loginStates.create(
            clientId = client.clientId,
            redirectUri = redirectUri,
            codeChallenge = codeChallenge,
            // RFC 8707. Defaulted rather than required: not every client sends
            // one, and the only resource this server issues for is its own /mcp.
            //
            // The caller's value is no longer stored (LUS-21). It was accepted
            // verbatim, written onto the login state and from there onto every token,
            // and **never validated against anything** — this server is both the
            // authorization server and the resource server, and a token is looked up
            // in its own table, so the field decided nothing. An attacker-influenced
            // string that decides nothing is fine right up until somebody reads it
            // as though it means something, which is exactly the shape of bug worth
            // removing before it exists.
            //
            // So: the one resource this server can issue for, always. A client that
            // sends a different one is not refused — RFC 8707 makes the parameter a
            // hint and refusing would break clients that send their own URL with a
            // trailing slash — it simply does not get to name it.
            resource = call.mcpResource(),
            clientState = clientState,
            scope = MCP_SCOPE,
            userId = user.id,
        )
        // "Have you approved this application before?" — one indexed read of the
        // grants this user already holds. See consentPage for why it is on the
        // card at all.
        val isFirstApproval = deps.tokens.listGrants(user.id).none { it.clientId == client.clientId }
        call.respondPage(
            consentPage(
                loginState = loginState,
                clientName = client.clientName,
                userName = user.resolvedName,
                redirectUri = redirectUri,
                isFirstApproval = isFirstApproval,
            ),
        )
    }

    /**
     * Approve or deny.
     *
     * The one click this entire file exists to collect.
     */
    post("/oauth/consent") {
        if (!call.withinOAuthLimit(limits.flow, "oauth-flow")) return@post
        val form = runCatching { call.receiveParameters() }.getOrNull()
        val state = deps.loginStates.find(form?.get("login_state"))
        if (state == null) {
            call.respondPage(
                errorPage(
                    "That took too long",
                    "This authorization has expired. Start again from the app that sent you here.",
                ),
                HttpStatusCode.BadRequest,
            )
            return@post
        }

        // The session must still be the one the consent page was rendered for.
        //
        // Two different bugs, both closed by one comparison. A login_state id
        // lifted from someone else's browser cannot be POSTed from this one to
        // mint a code for them — the ids are random and secret, but "secret" is
        // not a reason to skip a check that costs one lookup. And a user who
        // signed out and back in as somebody else between the page and the click
        // cannot mint a code naming the identity the page showed, which would be
        // this flow authorizing an account its owner never saw named.
        val sessionId = call.request.cookies[SESSION_COOKIE]
        val current = deps.sessions.lookup(sessionId)
        if (current == null || current.id != state.userId) {
            deps.loginStates.delete(state.id)
            call.respondPage(
                errorPage(
                    "You are signed in as somebody else",
                    "Your Lunicle session changed while this page was open, so this authorization " +
                        "has been cancelled. Start again from the app that sent you here.",
                ),
                HttpStatusCode.BadRequest,
            )
            return@post
        }

        // ── A probe session may not consent (LUS-2) ──────────────────────────
        //
        // The deliberate exception to the fidelity claim, and the only one. See
        // ProbeGrants' preamble, where it is written down beside the claim it
        // qualifies: everything downstream of a ProviderIdentity is the function
        // the real paths call — except this click.
        //
        // The reason is that consent is by definition a statement somebody makes
        // about *their own* identity, and a probe session is exactly the state in
        // which no such statement can be made: the page said the worn account's
        // name and the human clicking is the owner. Approving would mint an access
        // token plus a thirty-day rotating refresh token bound to the worn user —
        // a credential that survives the probe, the boot sweep and the feature
        // switch being turned off, which is the one thing the in-memory-grant
        // design exists to prevent.
        //
        // Refused HERE and not at `get("/oauth/authorize")`, which renders the
        // page. A probing owner may still drive authorize and watch the client
        // resolve, PKCE and redirect validation pass, canUseMcp evaluate and the
        // card render — the whole diagnostic value of wearing somebody. Only the
        // irreversible click is stopped.
        //
        // An explicit page rather than falling through to the in-page sign-in: the
        // caller is not signed out, they are wearing somebody, and offering them a
        // sign-in form would invite a confusing recovery.
        //
        // Asked unconditionally, without consulting `impersonation.isEnabled`. It
        // is one indexed read on a cold endpoint, and a gate that has just been
        // turned off is precisely the window in which a live probe session must
        // still be refused.
        if (deps.sessions.probeIdFor(sessionId) != null) {
            deps.loginStates.delete(state.id)
            logger.warn("MCP: refused consent from a probe session for client ${state.clientId}")
            call.respondPage(
                errorPage(
                    "You cannot approve this while impersonating",
                    "This browser is signed in as somebody else through owner impersonation, and " +
                        "approving an application is something only that person can do for " +
                        "themselves. Stop impersonating, sign in as yourself, and connect again.",
                ),
                HttpStatusCode.Forbidden,
            )
            return@post
        }

        // Re-checked at the click, not just at the page. The window is small and
        // the check is two fields on a record already in hand — and the whole claim
        // of these switches is that they are kill switches rather than UI
        // preferences. The window is exactly wide enough to matter now that an
        // *admin* can close it: the user consenting cannot revoke their own
        // permission mid-flow, but somebody else can.
        if (!deps.instanceSettings.canUseMcp(current)) {
            deps.loginStates.delete(state.id)
            call.respondRedirect(redirectWith(state.redirectUri, "error=access_denied", state.clientState))
            return@post
        }

        // Single-use whatever the answer: the id is spent by being decided.
        // Leaving an approved row behind would let one consent click mint codes
        // until it expired.
        deps.loginStates.delete(state.id)

        if (form?.get("decision") != "approve") {
            call.respondRedirect(redirectWith(state.redirectUri, "error=access_denied", state.clientState))
            return@post
        }

        val code = deps.codes.create(
            userId = state.userId,
            clientId = state.clientId,
            redirectUri = state.redirectUri,
            codeChallenge = state.codeChallenge,
            resource = state.resource,
            scope = state.scope,
        )
        logger.info("MCP: user ${state.userId} approved client ${state.clientId}")
        call.respondRedirect(
            redirectWith(state.redirectUri, "code=${code.encodeURLParameter()}", state.clientState),
        )
    }
}

// ── Token ────────────────────────────────────────────────────────────────────

/** The `/token` success body. */
private fun tokenResponse(tokens: IssuedTokens): JsonObject = buildJsonObject {
    put("access_token", tokens.accessToken)
    put("token_type", "Bearer")
    put("expires_in", tokens.expiresInSeconds)
    put("refresh_token", tokens.refreshToken)
    put("scope", tokens.scope)
}

private fun Route.tokenRoute(deps: McpDependencies, limits: OAuthRateLimits) {
    /**
     * Trade a code — or a refresh token — for tokens.
     *
     * Every refusal below is `invalid_grant` with nothing said about which check
     * failed. That is not laziness: distinguishing "no such code" from "wrong
     * client" from "PKCE failed" would turn this endpoint into an oracle for
     * probing codes.
     */
    post("/oauth/token") {
        if (!call.withinOAuthLimit(limits.flow, "oauth-flow")) return@post
        val form = runCatching { call.receiveParameters() }.getOrNull()
        if (form == null) {
            call.respondJson(HttpStatusCode.BadRequest, oauthError("invalid_request"))
            return@post
        }

        when (form["grant_type"]) {
            "authorization_code" -> {
                val code = form["code"]
                val codeVerifier = form["code_verifier"]
                val clientId = form["client_id"]
                val redirectUri = form["redirect_uri"]
                if (code == null || codeVerifier == null || clientId == null || redirectUri == null) {
                    call.respondJson(HttpStatusCode.BadRequest, oauthError("invalid_request"))
                    return@post
                }

                // Consumed first, and unconditionally: a code is spent by being
                // presented, whether or not the rest of this checks out. Validating
                // before consuming would leave a failed exchange's code alive for
                // another attempt, which is exactly what an interceptor wants.
                val record = deps.codes.consume(code)
                if (record == null) {
                    call.respondJson(HttpStatusCode.BadRequest, oauthError("invalid_grant"))
                    return@post
                }
                // The code is bound to the request that produced it. A code minted
                // for one client must not be redeemable by another, and one minted
                // for one callback must not be redeemable against a different one.
                if (record.clientId != clientId || record.redirectUri != redirectUri) {
                    call.respondJson(HttpStatusCode.BadRequest, oauthError("invalid_grant"))
                    return@post
                }
                if (!OAuthCrypto.verifyPkceS256(codeVerifier, record.codeChallenge)) {
                    // The one refusal worth logging. PKCE failing means the code
                    // reached somebody who did not mint the challenge — which is
                    // either a broken client or the interception PKCE exists to
                    // stop, and both are worth knowing about.
                    logger.warn("MCP: PKCE verification failed for client ${record.clientId}")
                    call.respondJson(HttpStatusCode.BadRequest, oauthError("invalid_grant"))
                    return@post
                }

                val user = deps.users.findById(record.userId)
                if (user == null || !deps.instanceSettings.canUseMcp(user)) {
                    call.respondJson(HttpStatusCode.BadRequest, oauthError("access_denied"))
                    return@post
                }

                call.respondJson(
                    HttpStatusCode.OK,
                    tokenResponse(deps.tokens.issueTokens(record.userId, record.clientId, record.scope, record.resource)),
                )
            }

            "refresh_token" -> {
                val refreshToken = form["refresh_token"]
                if (refreshToken == null) {
                    call.respondJson(HttpStatusCode.BadRequest, oauthError("invalid_request"))
                    return@post
                }
                // The re-check that keeps a 30-day refresh token from outliving the
                // toggle lives inside rotateRefresh, deliberately and not here —
                // it has to happen before the old token is consumed, or refusing
                // would strand the agent on a spent token and the next retry would
                // look like theft. See rotateRefresh, which explains that at
                // length.
                when (val result = deps.tokens.rotateRefresh(refreshToken)) {
                    is OAuthTokenStore.RefreshResult.Rotated ->
                        call.respondJson(HttpStatusCode.OK, tokenResponse(result.tokens))

                    // Refused while the owner has MCP switched off. Nothing was
                    // consumed and nothing revoked, so this same token works again
                    // the moment they switch it back on.
                    OAuthTokenStore.RefreshResult.Refused ->
                        call.respondJson(HttpStatusCode.BadRequest, oauthError("access_denied"))

                    // Both answer identically. They are different events — see
                    // RefreshResult.ReuseDetected, which has already revoked the
                    // family and logged it — but telling a caller which one it hit
                    // would tell a thief whether the token they stole was real.
                    OAuthTokenStore.RefreshResult.Invalid,
                    OAuthTokenStore.RefreshResult.ReuseDetected,
                    -> call.respondJson(HttpStatusCode.BadRequest, oauthError("invalid_grant"))
                }
            }

            else -> call.respondJson(HttpStatusCode.BadRequest, oauthError("unsupported_grant_type"))
        }
    }
}

// ── Revocation (RFC 7009) ────────────────────────────────────────────────────

private fun Route.revocationRoute(deps: McpDependencies, limits: OAuthRateLimits) {
    /**
     * Give a token back.
     *
     * **Always 200**, even for a token that was never real. RFC 7009 requires it,
     * and the reason is worth stating: an endpoint that answered differently for a
     * token it recognised would be a free oracle for testing stolen values.
     */
    post("/oauth/revoke") {
        if (!call.withinOAuthLimit(limits.flow, "oauth-flow")) return@post
        val token = runCatching { call.receiveParameters() }.getOrNull()?.get("token")
        if (token != null) deps.tokens.revokeByToken(token)
        call.respondJson(HttpStatusCode.OK, buildJsonObject { put("status", "ok") })
    }
}

// ── The pages ────────────────────────────────────────────────────────────────

/**
 * Shared styling for the three server-rendered pages.
 *
 * Inline rather than a stylesheet link, and deliberately: these pages are the
 * only thing in this server that renders without the bundle, and they are shown
 * to someone mid-flow between two applications. A page whose CSS 404s at that
 * moment reads as "this is broken, do not approve", which is exactly the wrong
 * conclusion to invite at a consent prompt.
 */
private val PAGE_STYLE = """
    :root { color-scheme: dark; }
    body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
        background: #04090f; color: #cfe0ee; }
    .card { background: #0b131c; border: 1px solid #1e2c3a; border-radius: 14px; padding: 32px;
        max-width: 440px; width: 90%; }
    h1 { font-size: 20px; margin: 0 0 12px; color: #e8f2fb; }
    p { color: #90a6b8; line-height: 1.55; margin: 0 0 12px; }
    .app { color: #6ea8fe; font-weight: 600; }
    .who { color: #e8f2fb; font-weight: 600; }
    .actions { display: flex; gap: 12px; margin-top: 24px; }
    button { flex: 1; padding: 11px 16px; border-radius: 8px; border: none; font-size: 15px;
        font-weight: 600; cursor: pointer; font-family: inherit; }
    .approve { background: #2f81f7; color: #fff; }
    .deny { background: #131e29; color: #cfe0ee; border: 1px solid #24384a; }
    .provider { display: block; width: 100%; margin-bottom: 10px; background: #131e29;
        color: #cfe0ee; border: 1px solid #24384a; }
    /* LNL-75's e-mail branch. Two fields and a rule between the methods; the
       colours are the card's own so this needs no second palette. */
    input { display: block; width: 100%; box-sizing: border-box; margin-bottom: 10px;
        padding: 11px 12px; border-radius: 8px; border: 1px solid #24384a;
        background: #04090f; color: #e8f2fb; font-size: 15px; font-family: inherit; }
    input::placeholder { color: #5c7183; }
    .divider { display: flex; align-items: center; gap: 10px; margin: 18px 0 14px;
        color: #5c7183; font-size: 13px; }
    .divider::before, .divider::after { content: ''; flex: 1; height: 1px; background: #1e2c3a; }
    .hint { font-size: 13px; color: #5c7183; }
    .error { color: #ff8080; min-height: 1.2em; }
    /* LUS-17's identity lines on the consent card. Smaller than the sentence
       naming the application, because they are what you check rather than what
       you read — and the warning is the one thing here allowed to be loud. */
    .detail { font-size: 13px; color: #5c7183; margin-bottom: 6px; }
    .host { color: #cfe0ee; font-weight: 600; word-break: break-all; }
    .warn { font-size: 13px; color: #ffbf80; background: #23180c; border: 1px solid #4a3417;
        border-radius: 8px; padding: 10px 12px; margin-top: 12px; }
""".trimIndent()

/** Wrap a card in a document. */
private fun page(title: String, body: String, script: String = ""): String = """
    <!doctype html>
    <html lang="en">
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${title.escapeHtml()}</title>
    <style>$PAGE_STYLE</style>
    </head>
    <body>
    <div class="card">$body</div>
    $script
    </body>
    </html>
""".trimIndent()

/** A dead end, explained. */
private fun errorPage(title: String, message: String): String = page(
    title,
    """
    <h1>${title.escapeHtml()}</h1>
    <p>${message.escapeHtml()}</p>
    """.trimIndent(),
)

/**
 * Where a redirect URI would actually send the code, said in as few characters as
 * a person can check at a glance.
 *
 * @property display scheme and authority, path dropped. The path is noise on a
 *   consent card — what decides whether this is the application you started is the
 *   host, and a long callback path pushes it off the end of the line.
 * @property isRemote the code would leave this machine: an `https` callback to a
 *   host somewhere on the internet.
 *
 *   Loopback is not remote, obviously. Neither is a **custom app scheme** —
 *   `cursor://`, `claude://` — which the operating system hands to a locally
 *   installed application and which cannot carry anything to a server. Warning on
 *   those would put a warning on half the legitimate desktop clients, and a
 *   warning that fires on the ordinary case is one people learn to click past.
 */
private data class RedirectSummary(val display: String, val isRemote: Boolean)

/**
 * Summarise an **already validated** redirect URI for the consent card.
 *
 * Parsed by hand rather than through [java.net.URI], which throws on some of the
 * custom app schemes real clients register and which would turn a display detail
 * into a failed authorization. Everything here has already passed
 * [isAllowedRedirectUri] and exact-match registration, so this is presentation
 * only — it decides nothing.
 *
 * Userinfo is stripped before the host is read. `https://localhost@evil.example/cb`
 * has host `evil.example`, and a card that showed the part before the `@` would be
 * showing the one thing an attacker would put there.
 */
private fun summariseRedirect(redirectUri: String): RedirectSummary {
    if ("://" !in redirectUri) return RedirectSummary(redirectUri, isRemote = false)
    val scheme = redirectUri.substringBefore("://").lowercase()
    val authority = redirectUri.substringAfter("://")
        .substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@')
    val host = authority.substringBefore(':').lowercase()
    val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]"
    // A scheme that is not http(s) is an app on this machine — see RedirectSummary.
    // Its authority is usually empty or a made-up word, so the scheme alone is the
    // honest thing to show.
    if (scheme != "http" && scheme != "https") return RedirectSummary("$scheme://", isRemote = false)
    return RedirectSummary("$scheme://$authority", isRemote = !isLoopback)
}

/**
 * The consent page. **The security boundary of this whole design.**
 *
 * Worth being honest about in review: the toggle in the Connections section is an
 * affordance and a comfort. This page is what actually stands between an agent
 * and someone's account, and it is the only moment a human is asked.
 *
 * It names five things, and each is load-bearing:
 *  - **which application** is asking, escaped, because the name is a string a
 *    stranger chose (see [String.escapeHtml]);
 *  - **who they would act as**, because an impersonating admin or a second
 *    account is exactly the confusion this catches;
 *  - **what it will be able to do**, in the only words that are true — the same
 *    as you. There is no scope to describe because there are no scopes; the token
 *    says who you are and AccessControl says what that means;
 *  - **where the code would go**, and
 *  - **whether this application has been approved here before**.
 *
 * ── Why the last two exist (LUS-17) ─────────────────────────────────────────
 *
 * Registration is unauthenticated by design, per RFC 7591, and takes an arbitrary
 * display name with arbitrary redirect URIs. So for a while this card showed
 * exactly two facts, and one of them — the name — is chosen by the stranger doing
 * the asking. [OAuthClientStore] states the rule that broke: the name is "chosen by
 * a stranger… never let it be the only thing a user sees before approving — anyone
 * may register a client named 'Claude Code'."
 *
 * The attack it invites needs no bug: register `Claude Code` with a callback on a
 * host you own, mint a PKCE pair, send the victim the authorize link. They see a
 * page **on the real Lunicle origin** naming an application they genuinely use and
 * themselves, and approve. Nothing on the old card could have told them apart from
 * the real thing, because the two differed only in the redirect URI, which was
 * never shown.
 *
 * The name is still shown, and still first — it is what somebody expecting a
 * connection recognises. What changed is that it is no longer *alone*.
 */
private fun consentPage(
    loginState: String,
    clientName: String,
    userName: String,
    redirectUri: String,
    isFirstApproval: Boolean,
): String {
    val redirect = summariseRedirect(redirectUri)
    // Stated for every client, not only the suspicious ones. A line that appears
    // only when something is wrong is a line nobody has read before, at the exact
    // moment they most need to already know what it means.
    val destination =
        """<p class="detail">It will be sent back to
           <span class="host">${redirect.display.escapeHtml()}</span>.</p>"""
    val firstUse = if (isFirstApproval) {
        """<p class="detail">You have not approved this application before.</p>"""
    } else {
        """<p class="detail">You have approved this application before.</p>"""
    }
    // Soft, and deliberately not a refusal: an https callback is legitimate for a
    // hosted agent and refusing it would break a supported client. But real MCP
    // clients are overwhelmingly loopback, so this is the shape worth a second look.
    val remoteWarning = if (redirect.isRemote) {
        """<p class="warn">This is not an application on your own computer — approving
           would send your access to that address. Deny unless you started this
           yourself and recognise it.</p>"""
    } else {
        ""
    }
    return page(
        "Authorize ${clientName.escapeHtml()}",
        """
        <h1>Authorize access</h1>
        <p><span class="app">${clientName.escapeHtml()}</span> wants to act on Lunicle as
           <span class="who">${userName.escapeHtml()}</span>.</p>
        <p>It will be able to do exactly what you can — no more. You can disconnect it at any
           time from your profile.</p>
        $destination
        $firstUse
        $remoteWarning
        <form method="post" action="/oauth/consent" class="actions">
          <input type="hidden" name="login_state" value="${loginState.escapeHtml()}">
          <button class="deny" type="submit" name="decision" value="deny">Deny</button>
          <button class="approve" type="submit" name="decision" value="approve">Approve</button>
        </form>
        """.trimIndent(),
    )
}

/**
 * Sign in, then come back — for a visitor who arrived here with no session.
 *
 * ── Why this page duplicates a little of SignInView ─────────────────────────
 *
 * Because it cannot use it. SignInView is inside the Kotlin/JS bundle and lives
 * in the single-page app; this is a bare server-rendered page at a top-level
 * navigation, reached before any of that has loaded. The alternative — bouncing
 * the visitor into the app with a "come back to this URL afterwards" parameter —
 * would spread MCP's flow through main.kt and a view model for the sake of
 * sharing two buttons.
 *
 * What it deliberately does NOT duplicate is any *decision*. It calls the
 * endpoints that already exist — [ApiRoutes.AUTH_GOOGLE], and since LNL-75
 * [ApiRoutes.AUTH_EMAIL_REQUEST] and [ApiRoutes.AUTH_EMAIL_REDEEM] — and it sets
 * the session cookie exactly as it does for the app. No new federation callback,
 * and nothing new registered in Google's console, which is the point of reusing
 * the cookie in the first place.
 *
 * On success it reloads, rather than continuing in JavaScript. The reload replays
 * this same `/oauth/authorize` request with its full query string, so the flow
 * resumes at the top with a session — and the consent branch is reached by
 * exactly one path whether or not a sign-in happened on the way. A JS-driven
 * "continue" would be a second implementation of the same decision. **Both**
 * branches end that way, and neither may stop doing so.
 *
 * ── This page is why the e-mail method is a code and not a link ────────────
 *
 * Not a preference. The mechanism above depends on signing in *in place*: the tab
 * that started the authorization is the tab holding the PKCE `code_challenge`,
 * and it is the tab that has to reload. A magic link opens somewhere else and
 * orphans it, leaving an agent waiting on a loopback callback that will never
 * come. A typed code drops straight into the design; see EmailCodes.kt.
 *
 * ── Two surfaces, two languages, one behaviour ─────────────────────────────
 *
 * The app-side picker is [se.soderbjorn.lunicle.client.viewmodel.SessionBackingViewModel]
 * plus `SignInPickerDialog`, in Kotlin. This is hand-written HTML and JS. They
 * must stay in step: the same methods offered, gated on the same configuration,
 * with a method the server cannot perform rendered by neither.
 */
private fun signInPage(config: OAuthConfig, clientName: String): String {
    val googleClientId = config.google?.clientId
    // A method the server was not configured with is not rendered at all: a dead
    // button invites a click and explains nothing. The same rule the app-side
    // picker follows — see SignInPickerDialog.
    val googleButton = if (googleClientId != null) {
        """<button class="provider" id="google">Sign in with Google</button>"""
    } else {
        ""
    }

    // The e-mail branch: address, then code, in place. Both steps are in the
    // markup from the start and the script shows one at a time — a page this size
    // has nothing to gain from rebuilding its own DOM, and having both present
    // means the second step cannot fail to exist at the moment it is needed.
    val emailForm = if (config.isEmailAvailable) {
        val divider = if (googleClientId != null) """<div class="divider">or</div>""" else ""
        """
        $divider
        <div id="email-step">
          <input type="email" id="email" placeholder="you@example.com" autocomplete="email">
          <button class="provider" id="send-code">E-mail me a code</button>
        </div>
        <div id="code-step" style="display:none">
          <p id="sent"></p>
          <input type="text" id="code" placeholder="6-digit code" inputmode="numeric"
                 autocomplete="one-time-code" maxlength="6">
          <button class="provider" id="redeem">Sign in</button>
          <p class="hint">The code is in the subject line of the message, and works for 15 minutes.</p>
        </div>
        """.trimIndent()
    } else {
        ""
    }

    val noProviders = if (!config.isSignInAvailable) {
        "<p>This Lunicle server has no sign-in configured, so it cannot authorize anything.</p>"
    } else {
        ""
    }

    val script = """
        <script src="https://accounts.google.com/gsi/client" async></script>
        <script>
        (function () {
          var error = document.getElementById('error');
          function fail(message) { error.textContent = message; }

          var google = document.getElementById('google');
          if (google) google.onclick = function () {
            var sdk = window.google;
            if (!sdk || !sdk.accounts) { fail("Google's sign-in script did not load."); return; }
            // ux_mode 'popup' with a callback and no redirect_uri: Google defaults
            // it to this page's origin, which is the origin the server sends in
            // the exchange. See exchangeGoogleCode.
            var client = sdk.accounts.oauth2.initCodeClient({
              client_id: ${(googleClientId ?: "").toJsStringLiteral()},
              scope: 'openid email profile',
              ux_mode: 'popup',
              callback: function (response) {
                if (!response || !response.code) { fail('Google returned no authorization code.'); return; }
                fetch('${ApiRoutes.AUTH_GOOGLE}', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ code: response.code })
                }).then(function (r) {
                  if (r.ok) { window.location.reload(); } else { return r.text().then(fail); }
                }).catch(function () { fail('Could not reach Lunicle.'); });
              },
              error_callback: function () { fail('Google sign-in did not complete.'); }
            });
            client.requestCode();
          };

          // ── The e-mail branch ───────────────────────────────────────────
          //
          // This is why the method is a typed code and not a magic link. The
          // whole mechanism of this page is signing in *in place* and reloading
          // the same /oauth/authorize URL; a link would land in a different tab
          // and orphan this one, which is the tab holding the PKCE
          // code_challenge. See EmailCodes.kt.
          var sendCode = document.getElementById('send-code');
          var emailStep = document.getElementById('email-step');
          var codeStep = document.getElementById('code-step');
          var emailField = document.getElementById('email');
          var codeField = document.getElementById('code');
          var sentMessage = document.getElementById('sent');
          var address = null;

          function requestCode() {
            var value = (emailField.value || '').trim();
            if (!value) { fail('Enter an e-mail address.'); return; }
            fail('');
            fetch('${ApiRoutes.AUTH_EMAIL_REQUEST}', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ email: value })
            }).then(function (r) {
              // The server answers identically whether or not the address has an
              // account and whether or not the mail went — anything else is an
              // account-existence oracle. So "check your mail" is the only thing
              // there is to say, and this page says exactly that.
              if (!r.ok) { return r.text().then(fail); }
              address = value;
              sentMessage.textContent = 'We sent a code to ' + value + '.';
              emailStep.style.display = 'none';
              codeStep.style.display = 'block';
              codeField.focus();
            }).catch(function () { fail('Could not reach Lunicle.'); });
          }

          function redeemCode() {
            var value = (codeField.value || '').trim();
            if (!value) { fail('Enter the code from the message.'); return; }
            fail('');
            fetch('${ApiRoutes.AUTH_EMAIL_REDEEM}', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ email: address, code: value })
            }).then(function (r) {
              // The reload is the whole mechanism, exactly as in the Google
              // branch above: it replays this same /oauth/authorize request with
              // its full query string, so the flow resumes at the top with a
              // session and the consent branch is reached by one path whether or
              // not a sign-in happened on the way. Continuing in JavaScript
              // instead would be a second implementation of that decision.
              if (r.ok) { window.location.reload(); } else { return r.text().then(fail); }
            }).catch(function () { fail('Could not reach Lunicle.'); });
          }

          if (sendCode) {
            sendCode.onclick = requestCode;
            // Enter in either field does the obvious thing. An address box is
            // exactly where people press Enter, and a page that ignored it would
            // read as broken before anybody found the button.
            emailField.onkeydown = function (e) { if (e.key === 'Enter') { e.preventDefault(); requestCode(); } };
            document.getElementById('redeem').onclick = redeemCode;
            codeField.onkeydown = function (e) { if (e.key === 'Enter') { e.preventDefault(); redeemCode(); } };
          }
        })();
        </script>
    """.trimIndent()

    return page(
        "Sign in to Lunicle",
        """
        <h1>Sign in to continue</h1>
        <p><span class="app">${clientName.escapeHtml()}</span> wants to act on Lunicle as you.
           Sign in first, and you will be asked whether to allow it.</p>
        $noProviders
        $googleButton
        $emailForm
        <p class="error" id="error" role="status"></p>
        """.trimIndent(),
        script,
    )
}

/**
 * Escape a string into a JavaScript string literal, quotes included.
 *
 * The Google client id is public and comes from our own configuration, so this is
 * not defending against a hostile value. It is here so that a client id
 * containing a quote — or anything else — is a broken sign-in button rather than
 * a syntax error that takes the whole script down.
 */
private fun String.toJsStringLiteral(): String = buildString {
    append('"')
    this@toJsStringLiteral.forEach { c ->
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
