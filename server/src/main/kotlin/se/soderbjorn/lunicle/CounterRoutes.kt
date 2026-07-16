/**
 * The counter endpoints — now owned, and now persistent.
 *
 * Stage 1's count was an `AtomicInteger` in this file: one number, shared by
 * everyone, gone on redeploy. It is now a row keyed by user id in SQLite on a
 * mounted volume. Those two changes together are the stage's exit criterion —
 * sign in, increment, redeploy, sign back in, and the count is still yours.
 *
 * The counter is still the POC payload rather than a feature. It just belongs to
 * someone now. See docs/stages.txt.
 *
 * @see CounterStore
 * @see se.soderbjorn.lunicle.clientserver.CounterState
 * @see Application.module
 */
package se.soderbjorn.lunicle

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.CounterState

/**
 * Resolve the caller, or answer 401 and return null.
 *
 * Unlike `/api/session`, these endpoints *do* 401 for a signed-out caller, and
 * the difference is deliberate. "Nobody is signed in" is a legitimate answer to
 * "who am I?", and a state the view renders. It is not an answer to "what is my
 * count?" — there is no such number, and inventing a zero would be a lie the
 * client would faithfully display.
 *
 * In practice the client never sees this: it knows from the session state not to
 * ask. This is the guard for everything that isn't the client.
 */
private suspend fun ApplicationCall.requireUser(sessions: SessionStore): UserRecord? {
    val user = sessions.lookup(request.cookies[SESSION_COOKIE])
    if (user == null) {
        respond(HttpStatusCode.Unauthorized, "Sign in to use the counter.")
    }
    return user
}

/**
 * Mount `GET /api/counter` and `POST /api/counter/increment`.
 *
 * Called by [Application.module].
 *
 * @param counters the per-user counts.
 * @param sessions used to identify the caller; a counter with no owner is not a
 *   counter this server has.
 */
fun Route.counterRoutes(
    counters: CounterStore,
    sessions: SessionStore,
) {
    get(ApiRoutes.COUNTER) {
        val user = call.requireUser(sessions) ?: return@get
        call.respond(CounterState(counters.get(user.id)))
    }

    post(ApiRoutes.COUNTER_INCREMENT) {
        val user = call.requireUser(sessions) ?: return@post
        // The response carries the value this caller produced — the increment
        // and the read are one statement, so it cannot be anyone else's.
        call.respond(CounterState(counters.increment(user.id)))
    }
}
