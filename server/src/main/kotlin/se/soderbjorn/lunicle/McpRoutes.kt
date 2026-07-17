/**
 * The human's view of the agent machinery: `/api/mcp`.
 *
 * Three routes, all session-cookie authenticated like every other `/api` route —
 * and pointedly unlike [oauthRoutes] and [mcpRoutes], which are unauthenticated
 * because they are how an agent *gets* a credential. This is where the person who
 * granted it looks at what they granted.
 *
 * ── The one thing these routes must not do ──────────────────────────────────
 *
 * There is no route here that starts an OAuth flow, and there must never be. The
 * flow is always initiated by the agent: the agent has to be the OAuth client, to
 * hold the PKCE verifier, and to receive the code at its own redirect URI.
 * Lunicle's UI only ever *displays the URL and lists the result*. A "Connect to
 * Claude Code" button cannot exist — it would mean someone had misunderstood which
 * direction this protocol runs in.
 *
 * @see McpState
 * @see OAuthServer
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
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.McpConnection
import se.soderbjorn.lunicle.clientserver.McpEnabledRequest
import se.soderbjorn.lunicle.clientserver.McpState

private val logger = LoggerFactory.getLogger("McpRoutes")

/**
 * Build the caller's [McpState].
 *
 * @param user re-read from the store rather than taken from the caller resolved
 *   at the top of the request, because two of the three routes below have just
 *   written to this row. Rendering the state we *asked for* rather than the state
 *   that *landed* would make the checkbox a claim rather than a report.
 */
private suspend fun ApplicationCall.mcpStateFor(user: UserRecord, deps: McpDependencies): McpState {
    val fresh = deps.users.findById(user.id) ?: user
    return McpState(
        isEnabled = fresh.isMcpEnabled,
        // Computed here, from the origin the browser actually reached, and never
        // in the client. The one place this is rendered is inside an iframe on
        // lunamux.dev, where the client's own idea of its location is a different
        // thing entirely — and a wrong URL here is a copy button that produces
        // something that fails only on the user's machine. See serverOrigin().
        serverUrl = mcpResource(),
        connections = deps.tokens.listGrants(user.id).map {
            McpConnection(
                clientId = it.clientId,
                clientName = it.clientName,
                connectedAt = it.connectedAt,
                lastUsedAt = it.lastUsedAt,
            )
        },
    )
}

/**
 * Who is asking, for the Connections section.
 *
 * The **effective** user, matching every other `/api` route: an admin
 * impersonating somebody sees that person's connections, which is the entire
 * point of impersonation and is why this does not reach for `caller.real`.
 *
 * Note the asymmetry with [mcpRoutes], which resolves a token straight to a
 * [UserRecord] and never honours an impersonation. That is not an inconsistency:
 * this is a browser session, where "acting as" is a real and deliberate state;
 * that is a token, which names one person forever. See McpServer.resolveMcpUser.
 */
private suspend fun ApplicationCall.mcpCaller(deps: McpDependencies): UserRecord? =
    resolveCaller(deps.sessions, deps.users, deps.impersonations).effective

/** Mount the Connections section's routes. */
fun Route.mcpApiRoutes(deps: McpDependencies) {
    get(ApiRoutes.MCP) {
        val user = call.mcpCaller(deps) ?: run {
            // 401 rather than an empty state: unlike SESSION, which renders
            // "signed out" as a legitimate answer, this section only exists for
            // somebody. An empty McpState would be indistinguishable from a user
            // with nothing connected, and the dialog would render a toggle nobody
            // can use.
            call.respond(HttpStatusCode.Unauthorized, "Sign in first.")
            return@get
        }
        call.respond(call.mcpStateFor(user, deps))
    }

    post(ApiRoutes.MCP_ENABLED) {
        val user = call.mcpCaller(deps) ?: run {
            call.respond(HttpStatusCode.Unauthorized, "Sign in first.")
            return@post
        }
        val body = runCatching { call.receive<McpEnabledRequest>() }.getOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }

        // Deliberately does not touch the caller's tokens either way. Off is a
        // gate, not a purge — both /oauth/authorize and /mcp re-read this flag per
        // request, so every agent stops within one request and starts again when
        // it is switched back on, without a second trip through the browser.
        // Revoke is what deletes. Conflating the two would mean a user who turned
        // this off to think about it came back to find every connection gone.
        deps.users.setMcpEnabled(user.id, body.isEnabled)
        logger.info("MCP: user ${user.id} turned agent access ${if (body.isEnabled) "on" else "off"}")
        call.respond(call.mcpStateFor(user, deps))
    }

    delete("${ApiRoutes.MCP}/connections/{clientId}") {
        val user = call.mcpCaller(deps) ?: run {
            call.respond(HttpStatusCode.Unauthorized, "Sign in first.")
            return@delete
        }
        val clientId = call.parameters["clientId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "Which connection?")
            return@delete
        }

        // Scoped to this user, and that is load-bearing rather than tidiness: one
        // oauth_clients row is shared by everyone who connected that agent,
        // because DCR registers the software and not the person. Without the user
        // scope, one person revoking "Claude Code" would disconnect it for the
        // whole instance.
        //
        // Silent on a client id this user has nothing for. There is nothing to
        // report — the requested end state is "this agent cannot act as me", and
        // that is already true. Answering differently would also make this a
        // probe for which agents other people use.
        deps.tokens.revokeForUserAndClient(user.id, clientId)
        call.respond(call.mcpStateFor(user, deps))
    }
}
