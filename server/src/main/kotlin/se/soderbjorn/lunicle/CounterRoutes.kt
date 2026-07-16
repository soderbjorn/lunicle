/**
 * The counter endpoints — Stage 1's entire API surface.
 *
 * The count is held in memory, in this process, and is deliberately lost on
 * every redeploy: Stage 1 has no database, and "the count resets to zero" is a
 * stated exit criterion rather than a defect. Stage 2 moves it to SQLite on a
 * Railway volume and gives it an owner. See docs/stages.html.
 *
 * @see se.soderbjorn.lunicle.clientserver.CounterState
 * @see Application.module
 */
package se.soderbjorn.lunicle

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.CounterState
import java.util.concurrent.atomic.AtomicInteger

/**
 * The process-wide counter.
 *
 * [AtomicInteger] rather than a plain `Int`: Netty serves requests on a thread
 * pool, so two increments genuinely can race. This is not theoretical
 * fastidiousness — a lost update here would show up as the button silently
 * doing nothing.
 */
private val count = AtomicInteger(0)

/**
 * Mount `GET /api/counter` and `POST /api/counter/increment`.
 *
 * Called by [Application.module].
 */
fun Route.counterRoutes() {
    get(ApiRoutes.COUNTER) {
        call.respond(CounterState(count.get()))
    }

    post(ApiRoutes.COUNTER_INCREMENT) {
        // incrementAndGet, so the response carries the value this caller
        // produced rather than whatever a concurrent request left behind.
        call.respond(CounterState(count.incrementAndGet()))
    }
}
