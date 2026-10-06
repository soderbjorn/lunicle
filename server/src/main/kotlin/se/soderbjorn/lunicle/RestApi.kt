/**
 * The REST API: `/api/v1`, authenticated by personal access tokens (LNL-222).
 *
 * ── A second transport, not a second implementation ─────────────────────────
 *
 * Every route here is one row of [REST_ROUTES]: an HTTP method and path mapped to the
 * name of an MCP tool. The handler turns the path parameters, query string and JSON
 * body into that tool's arguments — under the same snake_case names — and calls
 * [McpTools.call]. So the REST API and MCP are the same code: the same AccessControl
 * questions, the same refusals in the same words, the same history. That is the whole
 * argument for its safety, and it is McpTools' own argument one level up — the API
 * adds a caller, never a capability.
 *
 * It is also why the two cannot drift apart. A tool added to McpTools is either given
 * a row here or named in [REST_EXCLUDED_TOOLS] with a reason — `RestApiTest` fails the
 * build otherwise — and the OpenAPI description at `/api/v1/openapi.json` is generated
 * from the same table and the tools' own JSON schemas, so the documentation cannot
 * describe an API that is not the one being served.
 *
 * ── What is different from MCP, deliberately ────────────────────────────────
 *
 *  - **Who may reach a project.** A token is the person, not an agent acting for them,
 *    so the agent floor (LNL-217) does not apply: see [ToolSurface] and
 *    `McpTools.reaches`.
 *  - **No agent badge.** `agent_name` is refused rather than accepted — a script is
 *    not an agent, and a badge on its writes would claim one was involved. What the
 *    history records instead is *which token*: see [ApiTokenAttribution].
 *  - **A scope.** A read-only token gets the read routes and a 403 on everything else.
 *  - **Strict arguments.** An argument the tool does not take is a 400 naming it,
 *    because a typo in a script should fail loudly rather than be silently ignored.
 *
 * ── Gates ───────────────────────────────────────────────────────────────────
 *
 * Every request re-reads the token, its user and [canUseApi], so revoking a token,
 * switching API access off, or an administrator withdrawing the tier's permission each
 * stops every request from the next one on — the property McpServer's preamble calls
 * revocation by construction.
 *
 * @see ApiTokenStore
 * @see apiAccessRoutes for the session-authenticated half, where people manage tokens.
 */
package se.soderbjorn.lunicle

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.clientserver.ApiTokenScope
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val logger = LoggerFactory.getLogger("RestApi")

/** Where the API lives. Versioned in the path so a v2 can stand beside it rather than replace it. */
const val REST_API_PATH: String = "/api/v1"

/**
 * The personal access token the current request was made with — carried in the
 * coroutine context so [IssueHistory] can stamp it on every event it writes, without a
 * parameter threaded through every tool and repository in between (LNL-222).
 *
 * Only [restApiRoutes] puts one here. A web or MCP request has none, and its events
 * carry no token — which is the correct answer for them, not a default.
 *
 * @property tokenName the token's name as it stands at the moment of the request; the
 *   history keeps that snapshot. See IssueEvents.sq `via_token`.
 */
class ApiTokenAttribution(val tokenName: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ApiTokenAttribution>
}

/**
 * One path parameter: a placeholder in a route's path, and the tool argument it fills.
 * The placeholder and the argument share a name, always — so `{issue_id}` is the
 * `issue_id` the tool documents, and the OpenAPI description needs no mapping table.
 */
private typealias PathParameter = String

/**
 * One route: a method and path, and the tool behind it.
 *
 * @property path relative to [REST_API_PATH], with `{name}` placeholders that are
 *   argument names of [tool].
 * @property scope the narrowest token scope that may call it.
 * @property summary the one-line summary the OpenAPI description shows. The full
 *   description is the tool's own.
 * @property successStatus 201 for a route that creates something, 200 otherwise.
 * @property hidden arguments the tool takes that this route does not offer, beyond the
 *   ones every route hides ([ALWAYS_HIDDEN]) — the retired forum targets of the upload
 *   tool, say.
 */
data class RestRoute(
    val method: HttpMethod,
    val path: String,
    val tool: String,
    val scope: ApiTokenScope,
    val summary: String,
    val successStatus: HttpStatusCode = HttpStatusCode.OK,
    val hidden: Set<String> = emptySet(),
) {
    /** The `{placeholders}` in [path], in order. */
    val pathParameters: List<PathParameter> =
        Regex("\\{([a-z_]+)}").findAll(path).map { it.groupValues[1] }.toList()

    /** Whether the arguments not in the path arrive as a query string rather than a body. */
    val takesQuery: Boolean get() = method == HttpMethod.Get
}

/**
 * Arguments no route offers.
 *
 * - `agent_name` — refused outright when sent; see the preamble.
 * - `project_name` — every project-scoped route names its project by id in the path,
 *   and a second way to say it in the body would be a second answer that can disagree
 *   with the first.
 */
private val ALWAYS_HIDDEN = setOf("agent_name", "project_name")

private val READ = ApiTokenScope.READ
private val WRITE = ApiTokenScope.WRITE

/**
 * Every route the API has. The single source the handlers, the OpenAPI description and
 * the parity test all read.
 *
 * Ordered by resource and then by verb, which is the order the description lists them in.
 */
val REST_ROUTES: List<RestRoute> = listOf(
    // ── Projects ───────────────────────────────────────────────────────────
    RestRoute(HttpMethod.Get, "/projects", "list_projects", READ, "List the projects you can see"),
    RestRoute(HttpMethod.Get, "/projects/{project_id}/board", "get_board", READ, "Get a project's board"),
    RestRoute(
        HttpMethod.Put, "/projects/{project_id}/estimate-mode", "set_estimate_mode", WRITE,
        "Turn estimates on or off for a project",
    ),

    // ── Vocabulary: statuses, priorities, labels, … ────────────────────────
    RestRoute(HttpMethod.Get, "/projects/{project_id}/vocabulary", "list_vocabulary", READ, "List a project's vocabulary"),
    RestRoute(
        HttpMethod.Post, "/projects/{project_id}/vocabulary/{kind}", "add_vocabulary", WRITE,
        "Add a vocabulary row", successStatus = HttpStatusCode.Created,
    ),
    RestRoute(
        HttpMethod.Put, "/projects/{project_id}/vocabulary/{kind}/order", "reorder_vocabulary", WRITE,
        "Reorder a vocabulary",
    ),
    RestRoute(
        HttpMethod.Patch, "/projects/{project_id}/vocabulary/{kind}/{id}", "rename_vocabulary", WRITE,
        "Rename a vocabulary row or change its flags",
    ),
    RestRoute(
        HttpMethod.Delete, "/projects/{project_id}/vocabulary/{kind}/{id}", "delete_vocabulary", WRITE,
        "Delete a vocabulary row",
    ),

    // ── Sprints ────────────────────────────────────────────────────────────
    RestRoute(
        HttpMethod.Post, "/projects/{project_id}/sprints", "create_sprint", WRITE,
        "Create a sprint", successStatus = HttpStatusCode.Created,
    ),
    RestRoute(
        HttpMethod.Put, "/projects/{project_id}/active-sprint", "set_active_sprint", WRITE,
        "Set or clear a project's active sprint",
    ),
    RestRoute(
        HttpMethod.Post, "/projects/{project_id}/sprints/{sprint}/complete", "complete_sprint", WRITE,
        "Complete a sprint",
    ),

    // ── Issues ─────────────────────────────────────────────────────────────
    RestRoute(
        HttpMethod.Post, "/projects/{project_id}/issues", "create_issue", WRITE,
        "Create an issue", successStatus = HttpStatusCode.Created,
    ),
    RestRoute(HttpMethod.Get, "/issues/{issue_id}", "get_issue", READ, "Get one issue in full"),
    RestRoute(HttpMethod.Patch, "/issues/{issue_id}", "update_issue", WRITE, "Change an issue"),
    RestRoute(HttpMethod.Delete, "/issues/{issue_id}", "delete_issue", WRITE, "Delete an issue"),
    RestRoute(HttpMethod.Post, "/issues/{issue_id}/move", "move_issue", WRITE, "Move an issue to another column"),
    RestRoute(HttpMethod.Put, "/issues/{issue_id}/watch", "watch_issue", WRITE, "Watch or stop watching an issue"),
    RestRoute(
        HttpMethod.Put, "/issues/{issue_id}/children/order", "reorder_children", WRITE,
        "Reorder an epic's children",
    ),
    RestRoute(
        HttpMethod.Post, "/issues/{issue_id}/links", "link_issues", WRITE,
        "Link two issues", successStatus = HttpStatusCode.Created,
    ),
    RestRoute(HttpMethod.Delete, "/issues/{issue_id}/links/{relation_id}", "unlink_issues", WRITE, "Remove a link"),

    // ── Comments ───────────────────────────────────────────────────────────
    RestRoute(
        HttpMethod.Post, "/issues/{issue_id}/comments", "add_comment", WRITE,
        "Comment on an issue", successStatus = HttpStatusCode.Created,
    ),
    RestRoute(HttpMethod.Patch, "/comments/{comment_id}", "update_comment", WRITE, "Edit a comment"),
    RestRoute(HttpMethod.Delete, "/comments/{comment_id}", "delete_comment", WRITE, "Delete a comment"),

    // ── Attachments ────────────────────────────────────────────────────────
    RestRoute(
        HttpMethod.Post, "/attachments/uploads", "start_attachment_upload", WRITE,
        "Start an attachment upload", successStatus = HttpStatusCode.Created,
        // Discussions are retired (LNL-190), so their two upload targets are too.
        hidden = setOf("forum_post_id", "forum_comment_id"),
    ),
    RestRoute(HttpMethod.Delete, "/attachments/{attachment}", "delete_attachment", WRITE, "Delete an attachment"),

    // ── History ────────────────────────────────────────────────────────────
    RestRoute(
        HttpMethod.Patch, "/history-events/{event_id}", "update_history_event", WRITE,
        "Correct a history event's author or date",
    ),

    // ── You ────────────────────────────────────────────────────────────────
    RestRoute(HttpMethod.Post, "/me/email", "send_email", WRITE, "E-mail yourself"),
)

/**
 * The tools with no route, each with the reason. Read by the parity test, which fails
 * if a tool is in neither this map nor [REST_ROUTES] — so a new tool cannot quietly be
 * MCP-only. Shown in the OpenAPI description too, so nobody has to wonder.
 */
val REST_EXCLUDED_TOOLS: Map<String, String> = listOf(
    "list_forums", "create_forum", "update_forum", "delete_forum", "reorder_forums",
    "list_forum_posts", "get_forum_post", "watch_forum", "watch_forum_post",
    "create_forum_post", "update_forum_post", "delete_forum_post",
    "create_forum_comment", "update_forum_comment", "delete_forum_comment",
).associateWith {
    "Discussions are retired (LNL-190) and offered to nobody over MCP either. They get " +
        "routes here when they come back."
}

/**
 * What the REST API needs.
 *
 * @param tools a [McpTools] built for [ToolSurface.REST_API] — never the instance `/mcp`
 *   uses, which applies the agent floor.
 */
class RestApiDependencies(
    val tokens: se.soderbjorn.lunicle.store.ApiTokenStore,
    val users: se.soderbjorn.lunicle.store.UserStore,
    val instanceSettings: se.soderbjorn.lunicle.store.InstanceSettingsStore,
    val tools: McpTools,
    /**
     * Per-token request budget. Generous — a dashboard polling a few boards is nowhere
     * near it — because its job is to stop a runaway loop in somebody's script from
     * becoming everybody's slow afternoon, not to meter ordinary use. In memory, with
     * RateLimiter's stated single-instance caveat.
     */
    val limiter: RateLimiter = RateLimiter(limit = 600, windowMillis = 60_000L),
)

/** A token, and the person it acts as. */
private class ApiCaller(val token: ApiTokenRecord, val user: UserRecord)

/**
 * The person behind this request's bearer token, or null.
 *
 * Null for a missing or malformed header, an unknown, expired or revoked token, a token
 * whose user no longer resolves, and a user who may not use the API right now — one
 * answer for all of them, as [resolveMcpUser] gives, because telling them apart would
 * tell somebody holding a leaked token which part of it still works.
 */
private suspend fun ApplicationCall.resolveApiCaller(deps: RestApiDependencies): ApiCaller? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    if (!header.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
    val raw = header.substring(7).trim()
    if (!ApiTokenCrypto.isApiToken(raw)) return null
    val token = deps.tokens.authenticate(ApiTokenCrypto.hash(raw)) ?: return null
    val user = deps.users.findById(token.userId) ?: return null
    if (!deps.instanceSettings.canUseApi(user, deps.users)) return null
    return ApiCaller(token, user)
}

/** Mount `/api/v1`. */
fun Route.restApiRoutes(deps: RestApiDependencies) {
    route(REST_API_PATH) {
        /**
         * The description of this API, generated from [REST_ROUTES] and the tools'
         * schemas. Unauthenticated: it describes the shape of the API and nothing in
         * anybody's data, and a description you need a token to read is one a person
         * cannot read before deciding whether to make a token.
         */
        get("/openapi.json") {
            call.respondJson(HttpStatusCode.OK, openApiDocument(call.serverOrigin(), deps.tools))
        }

        /**
         * Who this token is. Not a tool — there is nothing for an agent to learn here
         * that its own session does not already say — but the first thing anyone
         * setting up an integration wants to check, and the honest answer to "is this
         * token working".
         */
        get("/me") {
            val caller = call.authenticate(deps) ?: return@get
            call.respondJson(
                HttpStatusCode.OK,
                buildJsonObject {
                    putJsonObject("user") {
                        put("id", caller.user.id)
                        put("name", caller.user.resolvedName)
                    }
                    putJsonObject("token") {
                        put("id", caller.token.id)
                        put("name", caller.token.name)
                        put("scope", caller.token.scope.key)
                        caller.token.expiresAt?.let { put("expiresAt", it) }
                    }
                },
            )
        }

        REST_ROUTES.forEach { restRoute ->
            route(restRoute.path, restRoute.method) {
                handle { call.dispatch(restRoute, deps) }
            }
        }
    }
}

/**
 * Authenticate, or answer 401 / 429 and return null.
 *
 * The 401 carries `WWW-Authenticate: Bearer` and the same sentence for every reason,
 * for [resolveApiCaller]'s reason.
 */
private suspend fun ApplicationCall.authenticate(deps: RestApiDependencies): ApiCaller? {
    val caller = resolveApiCaller(deps)
    if (caller == null) {
        response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"lunicle\"")
        respondError(
            HttpStatusCode.Unauthorized,
            "invalid_token",
            "This token is not valid. It may have expired or been revoked, or API access may be " +
                "off for its account — the switch is under Settings → You → API access.",
        )
        return null
    }
    val decision = deps.limiter.tryAcquire("api-token:${caller.token.id}")
    if (decision is RateLimitDecision.Refused) {
        response.header(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
        respondError(
            HttpStatusCode.TooManyRequests,
            "rate_limited",
            "Too many requests with this token. Try again in ${decision.retryAfterSeconds} seconds.",
        )
        return null
    }
    return caller
}

/** One request, end to end: authenticate, check the scope, build the arguments, call the tool, answer. */
private suspend fun ApplicationCall.dispatch(restRoute: RestRoute, deps: RestApiDependencies) {
    val caller = authenticate(deps) ?: return

    if (restRoute.scope == WRITE && caller.token.scope != WRITE) {
        response.header(
            HttpHeaders.WWWAuthenticate,
            "Bearer realm=\"lunicle\", error=\"insufficient_scope\", scope=\"write\"",
        )
        respondError(
            HttpStatusCode.Forbidden,
            "insufficient_scope",
            "This token is read-only. Make a read-write token to change anything.",
        )
        return
    }

    val tool = deps.tools.tools.firstOrNull { it.name == restRoute.tool } ?: run {
        // Unreachable while the parity test passes; a 500 rather than a 404 if not,
        // because a route that exists and cannot run is our fault, not the caller's.
        logger.error("REST route ${restRoute.method.value} ${restRoute.path} names unknown tool ${restRoute.tool}")
        respondError(HttpStatusCode.InternalServerError, "internal_error", "This route is misconfigured.")
        return
    }

    val arguments = buildArguments(restRoute, tool).getOrElse {
        respondError(HttpStatusCode.BadRequest, "invalid_request", it.message ?: "Malformed request.")
        return
    }

    val result = try {
        withContext(ApiTokenAttribution(caller.token.name)) {
            deps.tools.call(caller.user, restRoute.tool, arguments, serverOrigin())
        }
    } catch (failure: Exception) {
        logger.error("REST ${restRoute.method.value} ${restRoute.path} failed for user ${caller.user.id}", failure)
        respondError(HttpStatusCode.InternalServerError, "internal_error", "Something went wrong on our side.")
        return
    }

    val text = result.content.firstNotNullOfOrNull {
        ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
    }.orEmpty()

    if (result.isError) {
        val (status, code) = classifyRefusal(text)
        respondError(status, code, text)
        return
    }
    respondJson(restRoute.successStatus, successBody(text, result.structured))
}

/**
 * The tool's arguments, from the path, then the query string or body.
 *
 * Refuses — with a sentence naming the argument — anything the tool does not take,
 * `agent_name` in particular, and a body that tries to say something the path already
 * said. The path is the address; a body that disagreed with it would be a request for
 * two different things at once.
 */
private suspend fun ApplicationCall.buildArguments(restRoute: RestRoute, tool: McpTool): Result<JsonObject> {
    val properties = tool.inputSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val offered = properties.keys - ALWAYS_HIDDEN - restRoute.hidden - restRoute.pathParameters.toSet()

    val arguments = linkedMapOf<String, JsonElement>()
    for (name in restRoute.pathParameters) {
        val raw = parameters[name] ?: return Result.failure(IllegalArgumentException("Missing `$name` in the path."))
        arguments[name] = typedValue(name, raw, properties[name] as? JsonObject)
            ?: return Result.failure(IllegalArgumentException("`$name` in the path must be ${typeOf(properties[name])}."))
    }

    val supplied: Map<String, JsonElement> = if (restRoute.takesQuery) {
        request.queryParameters.names().associateWith { name ->
            typedValue(name, request.queryParameters[name].orEmpty(), properties[name] as? JsonObject)
                ?: return Result.failure(IllegalArgumentException("`$name` must be ${typeOf(properties[name])}."))
        }
    } else {
        val text = receiveText()
        if (text.isBlank()) {
            emptyMap()
        } else {
            val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return Result.failure(IllegalArgumentException("The request body must be a JSON object."))
            parsed
        }
    }

    for ((name, value) in supplied) {
        when {
            name == "agent_name" -> return Result.failure(
                IllegalArgumentException(
                    "`agent_name` is not accepted over the API. A personal access token acts as you, " +
                        "and its changes are recorded as made with this token rather than by an agent.",
                ),
            )
            name in restRoute.pathParameters -> return Result.failure(
                IllegalArgumentException("`$name` is already given in the path; leave it out of the request."),
            )
            name !in offered -> return Result.failure(
                IllegalArgumentException(
                    "`$name` is not something this request takes. It takes: " +
                        offered.sorted().joinToString(", ").ifEmpty { "nothing besides the path" } + ".",
                ),
            )
        }
        arguments[name] = value
    }
    return Result.success(JsonObject(arguments))
}

/**
 * A path or query value as the JSON the tool expects.
 *
 * Integers and booleans are converted rather than passed as strings: the tools tolerate
 * strings (models send them), but a REST client gets told when `issue_id` is `abc`
 * instead of hearing "No such issue". Null means the value does not fit the type.
 */
private fun typedValue(name: String, raw: String, spec: JsonObject?): JsonElement? =
    when ((spec?.get("type") as? JsonPrimitive)?.contentOrNull) {
        "integer" -> raw.trim().toLongOrNull()?.let(::JsonPrimitive)
        "boolean" -> when (raw.trim().lowercase()) {
            "true" -> JsonPrimitive(true)
            "false" -> JsonPrimitive(false)
            else -> null
        }
        "array" -> buildJsonArray { raw.split(',').map(String::trim).filter(String::isNotEmpty).forEach { add(it) } }
        else -> JsonPrimitive(raw)
    }.also { if (it == null) logger.debug("REST: `$name`=`$raw` does not fit its type") }

private fun typeOf(spec: JsonElement?): String =
    when (((spec as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull) {
        "integer" -> "a whole number"
        "boolean" -> "true or false"
        else -> "text"
    }

/**
 * Which HTTP status a refusal is.
 *
 * The tools refuse in sentences written for a person — deliberately the same words the
 * web app uses — and carry no code, so the status is read off the sentence's shape. The
 * two that matter most are reliable: "No such …" is how every tool says a thing is
 * absent *or out of reach* (the deliberate conflation that keeps private boards
 * unenumerable), and is a 404 for both. A refusal phrased as what the caller may not do
 * is a 403. Everything else — a bad name, a value that does not fit — is the caller's
 * request being wrong, a 400. The sentence itself is always in `message`, so a client
 * that disagrees with the code still has the reason.
 */
internal fun classifyRefusal(text: String): Pair<HttpStatusCode, String> {
    val sentence = text.trimStart()
    return when {
        sentence.startsWith("No such") || NOT_FOUND_PATTERN.containsMatchIn(sentence) ->
            HttpStatusCode.NotFound to "not_found"
        FORBIDDEN_OPENINGS.any { sentence.startsWith(it, ignoreCase = true) } ||
            FORBIDDEN_PHRASES.any { sentence.contains(it, ignoreCase = true) } ->
            HttpStatusCode.Forbidden to "forbidden"
        else -> HttpStatusCode.BadRequest to "refused"
    }
}

private val FORBIDDEN_OPENINGS = listOf(
    "You cannot", "You can't", "You can not", "You do not have", "You may not", "Only ", "That is not your",
)
private val FORBIDDEN_PHRASES = listOf("not allowed to", "permission", "instance owner only", "It is not yours")

/** "There is no comment 12." — absent by id, the same answer as "No such". Not "There is no label called …". */
private val NOT_FOUND_PATTERN = Regex("^There is no \\w+ \\d+\\.")

/**
 * The body of a successful answer.
 *
 * - A tool that creates something says so in a sentence and hands over the new ids as
 *   [McpToolResult.structured]: both, as `{ "message": …, "id": …, … }`.
 * - A tool that reads answers in JSON already: that JSON, as it is.
 * - Anything else is a sentence: `{ "message": … }`.
 */
private fun successBody(text: String, structured: JsonObject?): JsonElement {
    if (structured != null) {
        return buildJsonObject {
            put("message", text)
            structured.forEach { (key, value) -> put(key, value) }
        }
    }
    val trimmed = text.trimStart()
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        runCatching { Json.parseToJsonElement(text) }.getOrNull()?.let { return it }
    }
    return buildJsonObject { put("message", text) }
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) {
    respondJson(
        status,
        buildJsonObject {
            put("error", code)
            put("message", message)
        },
    )
}

/**
 * Respond with JSON, bypassing ContentNegotiation — for McpServer's reason: a client
 * whose `Accept` header the negotiator dislikes must still get the answer, not a 406
 * that hides it.
 */
private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: JsonElement) {
    respondText(body.toString(), ContentType.Application.Json, status)
}

// ── The OpenAPI description ──────────────────────────────────────────────────

/**
 * An OpenAPI 3.1 description of this API, built from [REST_ROUTES] and each tool's own
 * JSON schema and description.
 *
 * 3.1 rather than 3.0 because 3.1's schema objects *are* JSON Schema, which is what the
 * tools already declare — so each tool's `inputSchema` is used as it is rather than
 * translated. The operationId of every operation is its tool's name, so a description
 * that says "call get_board first" points at an operation that exists.
 *
 * @param origin this server's own origin, for the `servers` entry.
 */
internal fun openApiDocument(origin: String, tools: McpTools): JsonObject = buildJsonObject {
    put("openapi", "3.1.0")
    putJsonObject("info") {
        put("title", "Lunicle API")
        put("version", "1")
        put("description", OPENAPI_DESCRIPTION)
    }
    putJsonArray("servers") { add(buildJsonObject { put("url", origin + REST_API_PATH) }) }
    putJsonArray("security") { add(buildJsonObject { putJsonArray("bearerAuth") {} }) }

    putJsonObject("paths") {
        put("/me", buildJsonObject {
            putJsonObject("get") {
                put("operationId", "me")
                put("summary", "Who this token acts as")
                put("description", "The token's owner and the token itself. Any scope.")
                putJsonObject("responses") { putStandardResponses(HttpStatusCode.OK, includeForbidden = false) }
            }
        })
        REST_ROUTES.groupBy { it.path }.forEach { (path, routes) ->
            putJsonObject(path) {
                routes.forEach { restRoute ->
                    val tool = tools.tools.first { it.name == restRoute.tool }
                    put(restRoute.method.value.lowercase(), operationOf(restRoute, tool))
                }
            }
        }
    }

    putJsonObject("components") {
        putJsonObject("securitySchemes") {
            putJsonObject("bearerAuth") {
                put("type", "http")
                put("scheme", "bearer")
                put(
                    "description",
                    "A personal access token (lnl_pat_…), made under Settings → You → API access. " +
                        "Read-only tokens may call the GET operations; read-write tokens may call all of them.",
                )
            }
        }
        putJsonObject("schemas") {
            putJsonObject("Error") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("error") {
                        put("type", "string")
                        put(
                            "description",
                            "invalid_token, insufficient_scope, invalid_request, not_found, forbidden, " +
                                "refused, rate_limited or internal_error.",
                        )
                    }
                    putJsonObject("message") {
                        put("type", "string")
                        put("description", "The reason, in a sentence written for a person.")
                    }
                }
                putJsonArray("required") { add("error"); add("message") }
            }
        }
    }

    // Not part of OpenAPI; an extension, so the description says what is missing and why.
    put("x-excluded-tools", buildJsonObject { REST_EXCLUDED_TOOLS.forEach { (name, why) -> put(name, why) } })
}

private fun operationOf(restRoute: RestRoute, tool: McpTool): JsonObject = buildJsonObject {
    put("operationId", tool.name)
    put("summary", restRoute.summary)
    put(
        "description",
        tool.description + if (restRoute.scope == WRITE) "\n\nNeeds a read-write token." else "",
    )
    putJsonArray("tags") { add(restRoute.path.split('/').firstOrNull { it.isNotEmpty() } ?: "api") }

    val properties = tool.inputSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val required = (tool.inputSchema["required"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
    val offered = properties.filterKeys {
        it !in ALWAYS_HIDDEN && it !in restRoute.hidden && it !in restRoute.pathParameters
    }

    putJsonArray("parameters") {
        restRoute.pathParameters.forEach { name ->
            add(parameterOf(name, "path", properties[name], isRequired = true))
        }
        if (restRoute.takesQuery) {
            offered.forEach { (name, spec) -> add(parameterOf(name, "query", spec, isRequired = name in required)) }
        }
    }

    if (!restRoute.takesQuery && offered.isNotEmpty()) {
        putJsonObject("requestBody") {
            put("required", offered.keys.any { it in required })
            putJsonObject("content") {
                putJsonObject("application/json") {
                    putJsonObject("schema") {
                        put("type", "object")
                        put("additionalProperties", false)
                        put("properties", JsonObject(offered))
                        putJsonArray("required") { offered.keys.filter { it in required }.forEach { add(it) } }
                    }
                }
            }
        }
    }

    putJsonObject("responses") { putStandardResponses(restRoute.successStatus, includeForbidden = true) }
}

private fun parameterOf(name: String, location: String, spec: JsonElement?, isRequired: Boolean): JsonObject =
    buildJsonObject {
        put("name", name)
        put("in", location)
        put("required", isRequired)
        val schema = (spec as? JsonObject) ?: buildJsonObject { put("type", "string") }
        (schema["description"] as? JsonPrimitive)?.contentOrNull?.let { put("description", it) }
        put("schema", JsonObject(schema - "description"))
    }

private fun kotlinx.serialization.json.JsonObjectBuilder.putStandardResponses(
    success: HttpStatusCode,
    includeForbidden: Boolean,
) {
    putJsonObject(success.value.toString()) {
        put(
            "description",
            "Success. A read answers with its JSON; a write answers with `message` (and, when it " +
                "created something, the new `id` and friends).",
        )
        putJsonObject("content") { putJsonObject("application/json") { putJsonObject("schema") { put("type", "object") } } }
    }
    fun error(code: String, description: String) = putJsonObject(code) {
        put("description", description)
        putJsonObject("content") {
            putJsonObject("application/json") {
                putJsonObject("schema") { put("\$ref", "#/components/schemas/Error") }
            }
        }
    }
    error("400", "The request was refused as given — a name that does not exist here, a value that does not fit.")
    error("401", "No valid token. It may have expired or been revoked, or API access may be off for its account.")
    if (includeForbidden) {
        error("403", "A read-only token on a write, or something your account may not do.")
        error("404", "No such thing, or nothing you can see — deliberately the same answer.")
    }
    error("429", "Too many requests with this token. See Retry-After.")
}

private const val OPENAPI_DESCRIPTION: String =
    "Lunicle's REST API. Everything here acts as the person who made the token, through " +
        "exactly the same permission checks as the web app — a token adds no capability its " +
        "owner lacks.\n\n" +
        "Start with `GET /projects`, then `GET /projects/{project_id}/board`, which returns " +
        "the project's vocabulary — its statuses, priorities, resolutions, labels and " +
        "components. Everything else addresses that vocabulary by name.\n\n" +
        "Each operation is the same as the MCP tool of the same name (its operationId), and the " +
        "descriptions are the tools' own, so where one says \"call get_board\", that is " +
        "`GET /projects/{project_id}/board`. Arguments use the tools' snake_case names. An " +
        "argument an operation does not take is refused, as is `agent_name`: changes made " +
        "with a token are recorded as made with that token, not by an agent."
