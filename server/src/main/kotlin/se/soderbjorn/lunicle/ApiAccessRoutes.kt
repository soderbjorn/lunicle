/**
 * The human's view of their personal access tokens: `/api/api-access` (LNL-222).
 *
 * Four routes, session-cookie authenticated like every other `/api` route and like
 * [mcpApiRoutes], which this mirrors — this is where a person looks at, makes and
 * revokes the tokens that `/api/v1` accepts. Nothing here accepts a token.
 *
 * ── Never while impersonating ───────────────────────────────────────────────
 *
 * An owner signed in as somebody through owner impersonation may *look* at that
 * person's tokens — the session genuinely is theirs, as everywhere else — but may not
 * make one, revoke one or flip the switch. Making one is the sharp case: a token is a
 * credential that outlives the session, so minting it during a probe would hand the
 * owner a durable way to act as somebody else after the probe has ended, which is
 * exactly what impersonation is bounded not to do. Revoking and switching are refused
 * with it so that what a probe can change about somebody's credentials is nothing at
 * all, rather than a line drawn through the middle. See [Caller.isProbe].
 *
 * @see ApiAccessState
 * @see restApiRoutes
 */
package se.soderbjorn.lunicle

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.clientserver.API_TOKEN_EXPIRY_CHOICES
import se.soderbjorn.lunicle.clientserver.API_TOKEN_NAME_MAX_LENGTH
import se.soderbjorn.lunicle.clientserver.ApiAccessState
import se.soderbjorn.lunicle.clientserver.ApiEnabledRequest
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.ApiTokenView
import se.soderbjorn.lunicle.clientserver.CreateApiTokenRequest
import se.soderbjorn.lunicle.clientserver.CreatedApiToken

private val logger = LoggerFactory.getLogger("ApiAccessRoutes")

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/**
 * Build the caller's [ApiAccessState], re-read after any write so the state reports
 * what landed rather than what was asked for — [McpRoutes]' reasoning.
 */
private suspend fun ApplicationCall.apiAccessStateFor(user: UserRecord, deps: RestApiDependencies): ApiAccessState {
    val fresh = deps.users.findById(user.id) ?: user
    return ApiAccessState(
        isAllowed = deps.instanceSettings.permitsApiFor(fresh),
        isEnabled = deps.users.isApiEnabled(fresh.id),
        // Computed from the origin the browser reached, never in the client — see
        // McpState.serverUrl for why that matters inside an iframe.
        baseUrl = serverOrigin() + REST_API_PATH,
        docsUrl = serverOrigin() + REST_API_PATH + "/openapi.json",
        tokens = deps.tokens.forUser(fresh.id).map {
            ApiTokenView(
                id = it.id,
                name = it.name,
                prefix = it.prefix,
                scope = it.scope,
                createdAt = it.createdAt,
                lastUsedAt = it.lastUsedAt,
                expiresAt = it.expiresAt,
            )
        },
    )
}

/** Mount the API access section's routes. */
fun Route.apiAccessRoutes(sessionDeps: McpDependencies, deps: RestApiDependencies) {
    suspend fun ApplicationCall.caller(): Caller =
        resolveCaller(sessionDeps.sessions, sessionDeps.impersonation, sessionDeps.access)

    /**
     * Refuse a write made through an impersonation; see the preamble. Answers and
     * returns true when it refused.
     */
    suspend fun ApplicationCall.refuseProbe(caller: Caller): Boolean {
        if (!caller.isProbe) return false
        respond(
            HttpStatusCode.Forbidden,
            "You are signed in as somebody else through owner impersonation. Their API tokens " +
                "can be looked at, but not made, revoked or switched off from here.",
        )
        return true
    }

    get(ApiRoutes.API_ACCESS) {
        val user = call.caller().user ?: run {
            call.respond(HttpStatusCode.Unauthorized, "Sign in first.")
            return@get
        }
        call.respond(call.apiAccessStateFor(user, deps))
    }

    post(ApiRoutes.API_ACCESS_ENABLED) {
        val caller = call.caller()
        val user = caller.user ?: run {
            call.respond(HttpStatusCode.Unauthorized, "Sign in first.")
            return@post
        }
        if (call.refuseProbe(caller)) return@post
        val body = runCatching { call.receive<ApiEnabledRequest>() }.getOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        // Refused for an unpermitted tier rather than stored and left inert, for
        // McpRoutes' reason: a stored "on" would arm itself silently the day an
        // administrator grants the tier.
        if (!deps.instanceSettings.permitsApiFor(user)) {
            call.respond(HttpStatusCode.Forbidden, "An administrator has not given your account API access.")
            return@post
        }
        // A gate, not a purge — tokens are untouched either way. See Users.sq api_enabled.
        deps.users.setApiEnabled(user.id, body.isEnabled)
        logger.info("API: user ${user.id} turned API access ${if (body.isEnabled) "on" else "off"}")
        call.respond(call.apiAccessStateFor(user, deps))
    }

    post(ApiRoutes.API_ACCESS_TOKENS) {
        val caller = call.caller()
        val user = caller.user ?: run {
            call.respond(HttpStatusCode.Unauthorized, "Sign in first.")
            return@post
        }
        if (call.refuseProbe(caller)) return@post
        val body = runCatching { call.receive<CreateApiTokenRequest>() }.getOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        // Permission, not the person's own switch: making a token while API access is
        // switched off is allowed — it is how somebody sets everything up before turning
        // it on — and the token is simply refused at /api/v1 until they do.
        if (!deps.instanceSettings.permitsApiFor(user)) {
            call.respond(HttpStatusCode.Forbidden, "An administrator has not given your account API access.")
            return@post
        }
        val name = body.name.trim()
        if (name.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "Give the token a name, so you can tell it apart later.")
            return@post
        }
        if (name.length > API_TOKEN_NAME_MAX_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, "Keep the name under $API_TOKEN_NAME_MAX_LENGTH characters.")
            return@post
        }
        // Only the lifetimes the menu offers. Anything else is a client inventing one.
        if (body.expiresInDays !in API_TOKEN_EXPIRY_CHOICES) {
            call.respond(HttpStatusCode.BadRequest, "That is not one of the lifetimes a token can have.")
            return@post
        }

        val token = ApiTokenCrypto.mint()
        deps.tokens.create(
            userId = user.id,
            name = name,
            tokenHash = ApiTokenCrypto.hash(token),
            tokenPrefix = ApiTokenCrypto.displayPrefix(token),
            scope = body.scope,
            expiresAt = body.expiresInDays?.let { System.currentTimeMillis() + it * DAY_MILLIS },
        )
        logger.info("API: user ${user.id} made a ${body.scope.key} token")
        // The raw token goes out once, here, and nowhere else ever again.
        call.respond(CreatedApiToken(state = call.apiAccessStateFor(user, deps), token = token))
    }

    delete("${ApiRoutes.API_ACCESS_TOKENS}/{id}") {
        val caller = call.caller()
        val user = caller.user ?: run {
            call.respond(HttpStatusCode.Unauthorized, "Sign in first.")
            return@delete
        }
        if (call.refuseProbe(caller)) return@delete
        val id = call.parameters["id"]?.toLongOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Which token?")
            return@delete
        }
        // Scoped to this user inside the store; silent on an id that is not theirs.
        deps.tokens.revoke(user.id, id)
        call.respond(call.apiAccessStateFor(user, deps))
    }
}
