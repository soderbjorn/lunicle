/**
 * Wire types shared by the Ktor server and every client, plus the route
 * constants they agree on.
 *
 * The counter is Stage 1's payload — deliberately the smallest possible thing
 * that still forces a real round-trip through the whole stack (browser bundle →
 * HTTP → JVM server and back). It is set dressing; the infrastructure it
 * exercises is the point. See docs/stages.html.
 *
 * @see IssuesApi
 */
package se.soderbjorn.issues.clientserver

import kotlinx.serialization.Serializable

/**
 * The server's authoritative counter value.
 *
 * The count lives on the server (in memory, for Stage 1 — see
 * `CounterRoutes.kt`), not in the browser. That is what makes the Stage 1 exit
 * criterion meaningful: a redeploy resets the count to zero because the
 * container that held it was replaced. A browser-side count would survive a
 * redeploy and prove nothing.
 *
 * @property count the number of increments since the server process started.
 */
@Serializable
data class CounterState(
    val count: Int,
)

/**
 * The HTTP routes the server exposes and the client calls.
 *
 * Shared constants rather than string literals on each side, so a renamed route
 * is a compile error in both modules instead of a 404 discovered in a browser.
 */
object ApiRoutes {
    /** `GET` — returns the current [CounterState]. */
    const val COUNTER: String = "/api/counter"

    /** `POST` — increments the counter and returns the new [CounterState]. */
    const val COUNTER_INCREMENT: String = "/api/counter/increment"
}
