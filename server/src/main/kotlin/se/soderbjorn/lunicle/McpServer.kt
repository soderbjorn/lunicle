/**
 * The Model Context Protocol endpoint: JSON-RPC 2.0 over Streamable HTTP.
 *
 * ── Stateless, and why that is worth more than it sounds ────────────────────
 *
 * Every POST is authenticated independently. There is no session, no connection,
 * and nothing that outlives a request — so revocation is instant by construction
 * rather than by a mechanism someone had to remember to build. Delete the token
 * row and the next call 401s; there is no live thing to also go and tear down.
 *
 * ── The principle the whole design rests on ─────────────────────────────────
 *
 * [resolveMcpUser] turns an access token into a [UserRecord] — the same type
 * [SessionStore.lookup] produces from a cookie. That single fact is what lets
 * [McpTools] hand the result straight to the same [AccessControl] the web app
 * uses, and it is why this transport adds no capability. The agent is just
 * another caller.
 *
 * A consequence worth noticing rather than rebuilding: **rung changes propagate
 * instantly.** Because every request re-derives permissions through AccessControl
 * rather than baking them into the token, a project administrator lowering
 * somebody's rung immediately narrows their agent too. No token invalidation is
 * needed and none exists.
 *
 * @see OAuthServer
 * @see McpTools
 * @see AccessControl
 */
package se.soderbjorn.lunicle

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("McpServer")

/**
 * The protocol version to answer with when a client does not name one.
 *
 * Clients send their own in `initialize` and we echo it back — see [processMessage].
 * This is only the fallback for a client that omits it.
 */
private const val DEFAULT_PROTOCOL_VERSION = "2025-06-18"

private const val SERVER_NAME = "lunicle"
private const val SERVER_VERSION = "1.0.0"

/**
 * Turn a bearer token into the user it names.
 *
 * ── Impersonation deliberately does not apply here ──────────────────────────
 *
 * This resolves to a [UserRecord] directly and never builds a [Caller], which is
 * the one place this transport is *narrower* than the cookie path rather than
 * merely different. A token names exactly one user, forever: it was minted for
 * them at a consent page that said their name out loud, and an agent holding it is
 * acting as that person and nobody else.
 *
 * Honouring an impersonation here would be incoherent in both directions.
 * Impersonation is keyed by session id and this request has no session — but more
 * to the point, an owner who starts impersonating in their browser must not
 * silently redirect an agent that a *different* human approved days ago. The
 * effective user is a property of a browser tab; a token is a property of a
 * grant. See Impersonations, whose whole subject is that the client never says
 * who it is.
 *
 * @return the user, or null if the token is missing, malformed, unknown, expired,
 *   revoked, or belongs to somebody who has since turned MCP off. All of those are
 *   one answer — 401 — because distinguishing them would tell a caller holding a
 *   stolen token which part of it still works.
 */
private suspend fun resolveMcpUser(call: ApplicationCall, deps: McpDependencies): UserRecord? {
    val header = call.request.headers[HttpHeaders.Authorization] ?: return null
    // `Bearer ` case-insensitively, per RFC 6750 — the scheme is a token, not a
    // literal, and clients do vary.
    if (!header.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
    val token = header.substring(7).trim().takeIf { it.isNotBlank() } ?: return null

    val record = deps.tokens.validateAccessToken(token) ?: return null
    val user = deps.users.findById(record.userId) ?: return null

    // Re-read on every request, which is what makes this a kill switch rather than
    // a preference. Either half going false stops every connected agent within one
    // request, without any token being deleted — and either half coming back
    // restores them without a second trip through the browser.
    //
    // canUseMcp, never one of its two terms: the account's tier must be permitted
    // AND the user must have switched it on. See canUseMcp.
    if (!deps.instanceSettings.canUseMcp(user)) return null

    // Note what is NOT checked: that record.resource names this server. It does
    // not need to be. The audience guarantee here is structural rather than a
    // string comparison — this table holds only tokens this server minted, and it
    // mints them only for its own /mcp. A resource check would be comparing our
    // own URL against itself and would fail the day a proxy changed a hostname,
    // which is a fragile way to enforce something already true by construction.
    return user
}

/**
 * Mount `/mcp`.
 *
 * @param deps the token and user stores, for authentication.
 * @param tools the tool table, over the same dependencies the board routes use.
 */
fun Route.mcpRoutes(deps: McpDependencies, tools: McpTools) {
    route(MCP_PATH) {
        post { handleMcpPost(call, deps, tools) }

        /**
         * The optional server-initiated SSE stream, which we do not offer.
         *
         * ── This 405 is a trap, and it must stay exactly as it is ───────────
         *
         * It answers with [respondText] and **not** `respond(status, body)`,
         * deliberately, and the difference is the whole thing:
         *
         * When an MCP client opens this optional stream it sends
         * `Accept: text/event-stream`. Lunicle installs
         * `ContentNegotiation { json() }` in Application.module, so negotiating a
         * JSON body against that Accept header produces **406 Not Acceptable** —
         * and clients treat 406 as a fatal connection error rather than as "this
         * optional stream isn't offered". respondText writes the bytes directly
         * and never consults ContentNegotiation, so the client gets the plain 405
         * it knows how to shrug off and carries on.
         *
         * The symptom if this regresses is "the server doesn't work in Cursor",
         * with nothing useful in the log. Framnaflow paid for this one already.
         */
        get {
            call.response.header(HttpHeaders.Allow, "POST")
            call.respondText(
                "Use POST for MCP requests.",
                ContentType.Text.Plain,
                HttpStatusCode.MethodNotAllowed,
            )
        }
    }
}

/**
 * The 401 that starts the whole OAuth dance.
 *
 * `WWW-Authenticate` carrying a `resource_metadata` pointer is what turns a
 * refusal into a discovery step: an agent that gets this knows where to ask who
 * can authorize it, and every later step follows without the user typing
 * anything. Without the header the agent just fails.
 */
private suspend fun respondUnauthorized(call: ApplicationCall) {
    val metadataUrl = "${call.serverOrigin()}/.well-known/oauth-protected-resource"
    call.response.header(HttpHeaders.WWWAuthenticate, "Bearer resource_metadata=\"$metadataUrl\"")
    // respondText rather than respond(), for the GET handler's reason: an agent
    // probing with an odd Accept header must get this 401 and its header, not a
    // 406 that hides it.
    call.respondText(
        buildJsonObject { put("error", "invalid_token") }.toString(),
        ContentType.Application.Json,
        HttpStatusCode.Unauthorized,
    )
}

/**
 * How many MCP requests one account may make in a quarter of an hour (LUS-21).
 *
 * Not about credential guessing — a thirty-two-byte bearer token is not guessable
 * at any rate — but about the work each call costs. Several tools walk a table per
 * call, and an agent in a loop is the ordinary way that happens: a retry that keeps
 * failing, or a plan that re-reads a board between every write.
 *
 * Six hundred is deliberately high. A working session on a busy board makes dozens
 * of calls, and refusing one mid-task is a worse outcome than the load it saves —
 * this is a runaway guard, not a quota.
 *
 * Keyed on the **account** rather than the client address, because an agent's
 * address says nothing useful: several agents on one laptop share one, and the
 * thing being spent is the account's rights either way.
 *
 * A file-level `val` so it survives between requests. See [RateLimiter] for what
 * this becomes if Lunicle is ever scaled horizontally.
 */
private val mcpCallLimiter = RateLimiter(limit = 600, windowMillis = 15L * 60 * 1000)

private suspend fun handleMcpPost(call: ApplicationCall, deps: McpDependencies, tools: McpTools) {
    val user = resolveMcpUser(call, deps)
    if (user == null) {
        respondUnauthorized(call)
        return
    }

    // After authentication and keyed on the account, so there is nothing here for a
    // stranger to exhaust on somebody else's behalf — an unauthenticated caller is
    // already refused above.
    //
    // respondText rather than respond(), for the GET handler's reason: an agent
    // probing with `Accept: text/event-stream` must get this refusal and its
    // Retry-After, not a 406 from ContentNegotiation that hides both.
    val decision = mcpCallLimiter.tryAcquire("mcp-user:${user.id}")
    if (decision is RateLimitDecision.Refused) {
        call.response.header(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
        call.respondText(
            buildJsonObject { put("error", "slow_down") }.toString(),
            ContentType.Application.Json,
            HttpStatusCode.TooManyRequests,
        )
        return
    }

    val body = runCatching { Json.parseToJsonElement(call.receiveText()) }.getOrNull()
    if (body == null) {
        call.respondText(
            jsonRpcError(JsonNull, PARSE_ERROR, "Parse error").toString(),
            ContentType.Application.Json,
        )
        return
    }

    when (body) {
        // A batch. Notifications produce no response, so a batch of nothing but
        // notifications correctly produces no body at all — hence the 202 rather
        // than an empty array, which is not a legal JSON-RPC response.
        is JsonArray -> {
            val responses = body.mapNotNull { element ->
                (element as? JsonObject)?.let { processMessage(user, it, tools, call.serverOrigin()) }
            }
            if (responses.isEmpty()) {
                call.respondText("", ContentType.Application.Json, HttpStatusCode.Accepted)
            } else {
                call.respondText(JsonArray(responses).toString(), ContentType.Application.Json)
            }
        }

        is JsonObject -> {
            val response = processMessage(user, body, tools, call.serverOrigin())
            if (response == null) {
                call.respondText("", ContentType.Application.Json, HttpStatusCode.Accepted)
            } else {
                call.respondText(response.toString(), ContentType.Application.Json)
            }
        }

        else -> call.respondText(
            jsonRpcError(JsonNull, INVALID_REQUEST, "Invalid Request").toString(),
            ContentType.Application.Json,
        )
    }
}

// JSON-RPC 2.0's own codes. Named rather than inline so the three call sites
// cannot drift, and because -32601 means nothing at a glance.
private const val PARSE_ERROR = -32700
private const val INVALID_REQUEST = -32600
private const val METHOD_NOT_FOUND = -32601
private const val INVALID_PARAMS = -32602

/**
 * Handle one JSON-RPC message.
 *
 * @return the response, or null for a notification — a message with no `id`,
 *   which by JSON-RPC's rules must never be answered. Getting that wrong means
 *   sending a response to `notifications/initialized`, which some clients treat
 *   as a protocol violation and drop the connection over.
 */
private suspend fun processMessage(
    user: UserRecord,
    message: JsonObject,
    tools: McpTools,
    origin: String,
): JsonObject? {
    val id: JsonElement? = message["id"]
    val isNotification = id == null
    val method = (message["method"] as? JsonPrimitive)?.contentOrNull
    val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())

    if (method == null) {
        return if (isNotification) null else jsonRpcError(id ?: JsonNull, INVALID_REQUEST, "Invalid Request")
    }
    // Notifications need no response, whatever they say.
    if (method.startsWith("notifications/")) return null

    // Hoisted out of the two builders below because both are permission questions
    // and permission questions read stores (LNL-191) — a `buildJsonObject` lambda is
    // not a suspend context, so the answer has to arrive before the builder starts.
    // Computed unconditionally rather than per branch: two small reads on the two
    // handshake methods is not a cost worth a lazy.
    val instructions = tools.instructionsFor(user)
    val offeredTools = tools.toolsFor(user)

    val result: JsonObject = when (method) {
        "initialize" -> buildJsonObject {
            // Echo the client's version rather than asserting ours. The protocol
            // is negotiated, and a server that insists on its own version refuses
            // clients it could have served.
            put("protocolVersion", (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull ?: DEFAULT_PROTOCOL_VERSION)
            putJsonObject("capabilities") {
                // listChanged=false: the table a given caller is offered does not
                // change while their connection lasts, so there is no such
                // notification to subscribe to and claiming otherwise would
                // promise something we never send. It does vary *between* callers
                // — see McpTools.toolsFor — but a client only ever sees its own,
                // and a user promoted mid-connection reconnects to see the
                // difference, which is what happens for every other right here.
                putJsonObject("tools") { put("listChanged", false) }
            }
            putJsonObject("serverInfo") {
                put("name", SERVER_NAME)
                put("version", SERVER_VERSION)
            }
            // Lands in the agent's system prompt. See MCP_INSTRUCTIONS, and
            // instructionsFor for why the text depends on who is asking.
            put("instructions", instructions)
        }

        "ping" -> JsonObject(emptyMap())

        "tools/list" -> buildJsonObject {
            putJsonArray("tools") {
                offeredTools.forEach { tool ->
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("inputSchema", tool.inputSchema)
                        },
                    )
                }
            }
        }

        "tools/call" -> {
            val name = (params["name"] as? JsonPrimitive)?.contentOrNull
                ?: return if (isNotification) null else jsonRpcError(id ?: JsonNull, INVALID_PARAMS, "Missing tool name")
            val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

            // A tool that throws is this server's bug, not the agent's, and the
            // two must not look alike. Without this, a NullPointerException in a
            // tool would surface as a broken JSON-RPC connection — the agent
            // reconnects, retries, and fails identically forever. As an isError
            // result it is a sentence the agent can report to the person, and the
            // stack trace lands in our log where it belongs.
            val output = try {
                tools.call(user, name, arguments, origin)
            } catch (failure: Exception) {
                logger.error("MCP: tool '$name' failed for user ${user.id}", failure)
                McpToolResult(
                    content = listOf(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "That failed inside Lunicle. It has been logged; this is not something you can fix by retrying.")
                        },
                    ),
                    isError = true,
                )
            }

            buildJsonObject {
                putJsonArray("content") { output.content.forEach { add(it) } }
                if (output.isError) put("isError", true)
            }
        }

        else -> return if (isNotification) null else jsonRpcError(id ?: JsonNull, METHOD_NOT_FOUND, "Method not found: $method")
    }

    if (isNotification) return null
    return buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", result)
    }
}

private fun jsonRpcError(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    putJsonObject("error") {
        put("code", code)
        put("message", message)
    }
}
