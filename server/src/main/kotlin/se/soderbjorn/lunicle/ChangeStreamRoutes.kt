/**
 * The change stream's two endpoints (LNL-224): Server-Sent Events off [ChangeBus].
 *
 *  - `GET /api/v1/projects/{project_id}/events` — one project's changes. The API's
 *    endpoint, for a client watching one board.
 *  - `GET /api/v1/events?projects=1,2,3` — several projects' changes **and** the
 *    caller's own `notification.changed`, on one connection. The web app's endpoint:
 *    it may have several boards open in panes and a bell besides, and a browser on
 *    HTTP/1.1 holds at most six connections to a host — one stream per board would
 *    starve its own requests. The project set is the client's to change by
 *    reconnecting; `Last-Event-ID` makes that seamless, since ids are global.
 *
 * ── Who may listen ───────────────────────────────────────────────────────────
 *
 * A personal access token (`Authorization: Bearer lnl_pat_…`, read scope is enough,
 * through the REST API's own gates), or the web app's signed-in session. A request
 * that sends a bearer header is judged by the token alone, never by a cookie riding
 * along. Each project must be one the caller may read — the per-project endpoint
 * answers 404 otherwise, exactly as the board does, and the combined one leaves it
 * out. Both are asked again on every heartbeat: a revoked token, a signed-out
 * session, or a project the caller can no longer read ends the stream within one
 * heartbeat, and the reconnect is then refused or narrowed.
 *
 * ── The wire ─────────────────────────────────────────────────────────────────
 *
 * ```
 * id: 1791279316477001
 * event: issue.updated
 * data: {"projectId":2,"issueId":774,"updatedAt":1791279648030,"actor":"Linus"}
 * ```
 *
 * `commentId` rides on the comment kinds, `self: true` on an event this same client
 * caused (its `X-Lunicle-Origin` matched the stream's `origin` parameter — the
 * origin itself is never sent to anybody), and `actor` is absent where nobody was
 * signed in. `: ping` every [ChangeStreamDependencies.heartbeatMillis].
 * `event: reset` means "you missed something I can no longer replay — re-read".
 *
 * ── Limits ───────────────────────────────────────────────────────────────────
 *
 * At most [ChangeStreamDependencies.maxStreamsPerCredential] open streams per token or
 * per session, a 429 beyond that. The stream is exempt from the REST API's request
 * budget: one long request is not 600 short ones.
 */
package se.soderbjorn.lunicle

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val logger = LoggerFactory.getLogger("ChangeStream")

/** The path of the combined stream, relative to [REST_API_PATH]. */
const val CHANGE_STREAM_PATH: String = "/events"

/**
 * What the stream needs.
 *
 * @param heartbeatMillis how often an idle stream says `: ping`, and the longest a
 *   revoked caller keeps listening. 25 s keeps the connection under the idle timeouts
 *   of the proxies in front of both deployments.
 */
class ChangeStreamDependencies(
    val bus: ChangeBus,
    val sessions: se.soderbjorn.lunicle.store.SessionStore,
    val impersonation: OwnerImpersonation,
    val access: AccessControl,
    val projects: se.soderbjorn.lunicle.store.ProjectStore,
    val tokens: se.soderbjorn.lunicle.store.ApiTokenStore,
    val users: se.soderbjorn.lunicle.store.UserStore,
    val instanceSettings: se.soderbjorn.lunicle.store.InstanceSettingsStore,
    val heartbeatMillis: Long = 25_000L,
    val maxStreamsPerCredential: Int = 16,
    val maxProjectsPerStream: Int = 50,
) {
    internal val openStreams = ConcurrentHashMap<String, AtomicInteger>()
}

/** Who is listening, and the key their connections are counted under. */
private class StreamCaller(val user: UserRecord, val credential: String)

/**
 * Authenticate a stream request: the bearer token if one is sent, else the session.
 * Null for anything that does not resolve — re-run on every heartbeat.
 */
private suspend fun ApplicationCall.streamCaller(deps: ChangeStreamDependencies): StreamCaller? {
    val header = request.headers[HttpHeaders.Authorization]
    if (header != null) {
        if (!header.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
        val raw = header.substring(7).trim()
        if (!ApiTokenCrypto.isApiToken(raw)) return null
        val token = deps.tokens.authenticate(ApiTokenCrypto.hash(raw)) ?: return null
        val user = deps.users.findById(token.userId) ?: return null
        if (!deps.instanceSettings.canUseApi(user, deps.users)) return null
        return StreamCaller(user, "token:${token.id}")
    }
    val sessionId = request.cookies[SESSION_COOKIE] ?: return null
    val user = resolveCaller(deps.sessions, deps.impersonation, deps.access).user ?: return null
    return StreamCaller(user, "session:${ApiTokenCrypto.hash(sessionId)}")
}

/** Mount both stream endpoints under `/api/v1`. */
fun Route.changeStreamRoutes(deps: ChangeStreamDependencies) {
    route(REST_API_PATH) {
        get("/projects/{project_id}/events") {
            val caller = call.streamCaller(deps) ?: return@get call.unauthorized()
            val projectId = call.parameters["project_id"]?.toLongOrNull()
            val project = projectId?.let { deps.projects.findById(it) }
            if (project == null || !deps.access.canReadProject(caller.user, project)) {
                return@get call.respondStreamError(HttpStatusCode.NotFound, "not_found", "No such project, or nothing you can see.")
            }
            call.stream(deps, caller, setOf(project.id), includeUserEvents = false)
        }

        get(CHANGE_STREAM_PATH) {
            val caller = call.streamCaller(deps) ?: return@get call.unauthorized()
            val requested = call.request.queryParameters["projects"].orEmpty()
                .split(',').mapNotNull { it.trim().toLongOrNull() }.distinct()
            if (requested.size > deps.maxProjectsPerStream) {
                return@get call.respondStreamError(
                    HttpStatusCode.BadRequest,
                    "invalid_request",
                    "At most ${deps.maxProjectsPerStream} projects per stream.",
                )
            }
            // Unreadable ones are left out rather than refused: this is the web app's
            // endpoint, and a pane on a project that has just been taken away from
            // somebody should not cost them the rest of their live updates.
            val readable = requested.filter { id ->
                deps.projects.findById(id)?.let { deps.access.canReadProject(caller.user, it) } == true
            }.toSet()
            call.stream(deps, caller, readable, includeUserEvents = true)
        }
    }
}

private suspend fun ApplicationCall.unauthorized() {
    if (request.headers[HttpHeaders.Authorization] != null) {
        response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"lunicle\"")
    }
    respondStreamError(
        HttpStatusCode.Unauthorized,
        "invalid_token",
        "Sign in, or send a valid personal access token.",
    )
}

private suspend fun ApplicationCall.respondStreamError(status: HttpStatusCode, code: String, message: String) {
    respondText(
        buildJsonObject {
            put("error", code)
            put("message", message)
        }.toString(),
        ContentType.Application.Json,
        status,
    )
}

/** Hold the stream open until the client goes, or its right to listen does. */
private suspend fun ApplicationCall.stream(
    deps: ChangeStreamDependencies,
    caller: StreamCaller,
    projectIds: Set<Long>,
    includeUserEvents: Boolean,
) {
    val counter = deps.openStreams.computeIfAbsent(caller.credential) { AtomicInteger() }
    if (counter.incrementAndGet() > deps.maxStreamsPerCredential) {
        counter.decrementAndGet()
        response.header(HttpHeaders.RetryAfter, "30")
        return respondStreamError(
            HttpStatusCode.TooManyRequests,
            "too_many_streams",
            "Too many open change streams for this ${caller.credential.substringBefore(':')}. " +
                "Close one, or try again later.",
        )
    }

    val ownOrigin = ChangeOrigin.sanitise(request.queryParameters["origin"])
    val lastEventId = (request.headers["Last-Event-ID"] ?: request.queryParameters["last_event_id"])?.trim()?.toLongOrNull()
    val opened = deps.bus.open(lastEventId)

    fun wanted(event: ChangeEvent): Boolean =
        (event.projectId != null && event.projectId in projectIds) ||
            (includeUserEvents && event.userId != null && event.userId == caller.user.id)

    response.header(HttpHeaders.CacheControl, "no-cache")
    // nginx and friends buffer responses by default, which would hold every event
    // until the buffer filled.
    response.header("X-Accel-Buffering", "no")
    // The cleanup lives INSIDE the writer: an engine may return from respondTextWriter
    // as soon as the headers are out and run the body afterwards, and a finally out
    // here would then close the listener under a stream that has only just begun.
    respondTextWriter(ContentType.Text.EventStream) {
        try {
            // How long a browser's EventSource waits before reconnecting after a drop.
            write("retry: 3000\n: connected\n\n")
            var lastSent = lastEventId ?: 0L
            if (opened.replay == null) {
                lastSent = writeReset(opened.listener.events, lastSent)
            } else {
                opened.replay.filter(::wanted).forEach { writeEvent(it, ownOrigin) }
                opened.replay.lastOrNull()?.let { lastSent = it.id }
            }
            flush()

            var lastCheck = System.currentTimeMillis()
            while (true) {
                val event = withTimeoutOrNull(deps.heartbeatMillis) { opened.listener.events.receive() }
                val now = System.currentTimeMillis()
                if (now - lastCheck >= deps.heartbeatMillis) {
                    if (!stillAllowed(deps, caller, projectIds)) break
                    lastCheck = now
                }
                when {
                    opened.listener.overflowed -> {
                        opened.listener.overflowed = false
                        lastSent = writeReset(opened.listener.events, maxOf(lastSent, event?.id ?: 0L))
                    }
                    event == null -> write(": ping\n\n")
                    else -> {
                        lastSent = event.id
                        if (wanted(event)) writeEvent(event, ownOrigin)
                    }
                }
                flush()
            }
        } catch (gone: Exception) {
            // The client went away — the ordinary end of every stream.
            logger.debug("Change stream for user ${caller.user.id} closed: ${gone.javaClass.simpleName}")
            if (gone is kotlinx.coroutines.CancellationException) throw gone
        } finally {
            deps.bus.close(opened.listener)
            counter.decrementAndGet()
        }
    }
}

/** The caller still resolves and may still read every project — re-asked each heartbeat. */
private suspend fun ApplicationCall.stillAllowed(
    deps: ChangeStreamDependencies,
    caller: StreamCaller,
    projectIds: Set<Long>,
): Boolean {
    val now = streamCaller(deps) ?: return false
    if (now.user.id != caller.user.id) return false
    return projectIds.all { id ->
        deps.projects.findById(id)?.let { deps.access.canReadProject(now.user, it) } == true
    }
}

/**
 * Tell the client it missed something: drain whatever is queued (it is about to
 * re-read anyway) and stamp the reset with the newest id seen, so a reconnect resumes
 * from after it.
 */
private fun Writer.writeReset(queued: ReceiveChannel<ChangeEvent>, lastSent: Long): Long {
    var newest = lastSent
    while (true) {
        val next = queued.tryReceive().getOrNull() ?: break
        newest = maxOf(newest, next.id)
    }
    write(if (newest > 0) "id: $newest\nevent: reset\ndata: {}\n\n" else "event: reset\ndata: {}\n\n")
    return newest
}

private fun Writer.writeEvent(event: ChangeEvent, ownOrigin: String?) {
    val data = buildJsonObject {
        event.projectId?.let { put("projectId", it) }
        event.issueId?.let { put("issueId", it) }
        event.commentId?.let { put("commentId", it) }
        if (event.issueId != null) put("updatedAt", event.updatedAt)
        event.actor?.let { put("actor", it) }
        if (ownOrigin != null && event.origin == ownOrigin) put("self", true)
    }
    write("id: ${event.id}\nevent: ${event.kind.key}\ndata: $data\n\n")
}
