/**
 * Refusing to do something too often.
 *
 * Until LNL-72 this server had no rate limiting of any kind, and that was
 * survivable for one reason: every side-effecting endpoint was authenticated, so
 * an abuser was a person who could be removed and the cost of abuse was a
 * database write. Passwordless sign-in breaks that property — it needs an
 * endpoint that is unauthenticated by definition, and each call spends real money
 * and real sender reputation on an outbound Resend call to an address the caller
 * chose. Unmetered, that is a free mail cannon pointed at arbitrary third parties
 * from a verified domain, and the fastest route to a blocklisted sending domain.
 *
 * Two pieces, and the second is the one that is quietly wrong for a year if it is
 * not written down:
 *
 *  - [RateLimiter] — a fixed-window counter, in memory, keyed by an opaque string
 *    the caller composes.
 *  - [ApplicationCall.clientIdentity] — who the client *is*, behind a proxy that
 *    can be lied to.
 *
 * ── One instance, one process, and what that costs if that ever changes ─────
 *
 * The counters are a `ConcurrentHashMap` in this process. Lunicle runs as a
 * single instance today, so there is no shared-state problem to solve and nothing
 * here coordinates with anything. **If Lunicle is ever scaled horizontally this
 * becomes per-instance, and the effective limit multiplies by the instance
 * count.** That is not a subtle degradation — a limit of 5 across 4 instances is
 * a limit of 20 — and it will not announce itself, so it is stated here rather
 * than discovered from a Resend bill.
 *
 * ── In memory, deliberately, unlike EmailCodes ─────────────────────────────
 *
 * These two ship together and make opposite calls, which is worth being explicit
 * about. A restart that forgets a rate-limit counter costs an attacker a delay,
 * and the server does not restart on demand. A restart that forgets an issued
 * code strands a real user holding a mail the server no longer recognises — see
 * EmailCodes.sq. A table here would also mean another `.sqm` plus a write per
 * request on the hottest possible path, which is a bad trade for a defense whose
 * entire job is to be cheap.
 *
 * @see EmailCodeService
 */
package se.soderbjorn.lunicle

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

private val logger = LoggerFactory.getLogger("RateLimiter")

/**
 * How many proxy hops in front of this server are trusted to have written
 * `X-Forwarded-For` honestly.
 *
 * Railway is one: its edge terminates TLS, receives from the real client, and
 * appends that client's address to the header before forwarding to the container.
 * A deployment behind a CDN in front of Railway would be two.
 *
 * Read the way every other deployment value in this server is — a system property
 * first, because `:server:run` is a Gradle `JavaExec` inheriting the long-lived
 * daemon's environment rather than the invoking shell's, then an environment
 * variable for the deployed container where `java -jar` sees an exact
 * environment. See [resolveValue]'s twin in OAuthConfig.
 *
 * ── The default is 0, and it used to be 1 (LUS-31) ─────────────────────────
 *
 * A value of 0 means "nothing in front of me", which makes [clientIdentity] ignore
 * the header entirely. It is the right answer for a direct local run, and — as this
 * file already said before anything acted on it — **the only safe answer for a
 * server exposed without a proxy**.
 *
 * Defaulting to 1 was correct for the deployments that exist and wrong for the
 * deployment somebody sets up next. Behind exactly one proxy, 1 reads the address
 * that proxy observed. Exposed directly, with no proxy to append anything, a
 * request arriving with `X-Forwarded-For: 1.2.3.4` has one entry, and 1 reads it —
 * so the attacker-chosen value *is* the identity and every limiter in this server
 * is bypassed by a header the attacker varies per request. A `docker run` or a
 * self-host was therefore misconfigured out of the box, silently, in the direction
 * that removes the defence.
 *
 * Safe-by-default costs a configuration line on a proxied deployment and the
 * failure mode is loud rather than silent — every visitor lands in the proxy's
 * bucket and the sign-in limiter starts refusing, which somebody notices in
 * minutes. The reverse failure is invisible for as long as nobody looks.
 *
 * **So a deployment behind a proxy must now set `LUNICLE_TRUSTED_PROXY_HOPS`**: 1
 * behind a single edge (Railway, Cloud Run), 2 behind a CDN in front of one. See
 * [describeTrustedProxyHops], which the boot log prints so the assumption is stated
 * rather than assumed.
 */
fun resolveTrustedProxyHops(): Int =
    (
        System.getProperty("lunicle.trustedProxyHops")?.takeIf { it.isNotBlank() }
            ?: System.getenv("LUNICLE_TRUSTED_PROXY_HOPS")?.takeIf { it.isNotBlank() }
        )?.toIntOrNull()?.takeIf { it >= 0 }
        ?: 0

/**
 * What the boot log says about [resolveTrustedProxyHops], in one line.
 *
 * Printed at startup because the value decides whether every rate limit in this
 * server is real, and both ways of getting it wrong are quiet. An operator reading
 * the boot log should not have to know this file exists to find out what the server
 * assumed about its own network.
 */
fun describeTrustedProxyHops(hops: Int): String = when (hops) {
    0 -> "trusted proxy hops: 0 — X-Forwarded-For is IGNORED and rate limits key on the socket peer. " +
        "Correct for a directly exposed server; set LUNICLE_TRUSTED_PROXY_HOPS=1 if this is behind a proxy, " +
        "or every visitor shares one bucket."
    else -> "trusted proxy hops: $hops — rate limits key on the X-Forwarded-For entry $hops from the right."
}

/**
 * Who is asking, as far as a rate limiter can tell.
 *
 * ── Why this is not `call.request.origin.remoteHost` ───────────────────────
 *
 * Because this server is behind Railway's proxy, so the socket peer is the proxy
 * on every deployed request. Keying on it would put every visitor in the world in
 * one bucket, and the first requester of the day would lock out everybody else —
 * a limiter that fails by denying service to exactly the people it exists to
 * serve.
 *
 * ── Why not the first `X-Forwarded-For` entry either ───────────────────────
 *
 * Because the client writes it. `X-Forwarded-For` is built by each proxy
 * *appending* the address it received from, so the leftmost entry is whatever the
 * original request arrived carrying — which an attacker sets to a fresh value per
 * request, and the limiter is bypassed by a header.
 *
 * Only the rightmost entries are trustworthy, and only as many of them as there
 * are proxies you actually trust. So: take the entry [trustedHops] positions from
 * the **right**. With Railway's single hop the header a spoofer sends as
 * `1.2.3.4` arrives as `1.2.3.4, <their real address>`, and this reads the second
 * one.
 *
 * ── Failing closed ─────────────────────────────────────────────────────────
 *
 * Absent header, malformed header, fewer entries than trusted hops, blank
 * entries: every one of those falls back to the socket peer. Never to "no limit"
 * and never to a constant — a resolver that returned null on nonsense would hand
 * an attacker a bypass consisting of a deliberately broken header.
 *
 * @param trustedHops how many proxies in front are trusted. 0 ignores the header.
 * @return an opaque identity string, only ever compared for equality.
 */
internal fun ApplicationCall.clientIdentity(trustedHops: Int = resolveTrustedProxyHops()): String {
    val peer = request.origin.remoteHost
    if (trustedHops <= 0) return peer

    val forwarded = request.headers[HttpHeaders.XForwardedFor] ?: return peer
    val entries = forwarded.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    // Each trusted proxy contributed one entry from the right; the one this many
    // positions in is the address the outermost trusted proxy actually observed.
    // Anything to the left of it was written by something we do not trust.
    val index = entries.size - trustedHops
    return entries.getOrNull(index)?.takeIf { it.isNotBlank() } ?: peer
}

/** What [RateLimiter.tryAcquire] decided. */
sealed interface RateLimitDecision {

    /** There was room. The attempt has been counted. */
    data object Allowed : RateLimitDecision

    /**
     * The bucket is empty.
     *
     * @property retryAfterSeconds how long until the window rolls, rounded up and
     *   never below one — a `Retry-After: 0` invites an immediate retry that would
     *   be refused again.
     */
    data class Refused(val retryAfterSeconds: Long) : RateLimitDecision
}

/**
 * A fixed-window counter per key.
 *
 * Fixed window rather than a token bucket or a sliding log: the failure mode of a
 * fixed window is that a caller can spend two windows' worth of budget across the
 * boundary, which for "how many sign-in codes may one address be mailed" is not a
 * failure worth a smoothing algorithm. It is one long and one integer per key,
 * and it is trivially testable with an injected clock.
 *
 * @param limit how many attempts one key may make per window.
 * @param windowMillis how long a window lasts.
 * @param now the clock. Injected, and nothing in this file reaches for wall time —
 *   the boundary behaviour is the whole thing worth testing, and a test that
 *   slept for a window would be both slow and flaky.
 */
class RateLimiter(
    private val limit: Int,
    private val windowMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** One key's current window: when it opened, and how much has been spent. */
    private data class Window(val startedAt: Long, val count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    /** When the map was last swept. See [sweepIfDue]. */
    @Volatile
    private var lastSweptAt: Long = now()

    /**
     * Take one from every one of [keys], or refuse and take nothing.
     *
     * ── Why several keys and not one ───────────────────────────────────────
     *
     * Because an endpoint that acts on a caller-supplied identifier has two
     * things worth limiting and limiting either alone is a hole. Keyed only on
     * the client address, one host walks a list of many target addresses at full
     * speed. Keyed only on the target, a botnet hammers a single one. So the
     * caller passes both and is refused if *either* bucket is empty.
     *
     * All-or-nothing: a refusal spends nothing, so being refused on one key does
     * not silently burn down the other. Checked in one pass and then consumed in
     * a second, which is not atomic across keys — two concurrent requests can
     * both observe room and both consume, letting one extra request through at a
     * boundary. That is accepted deliberately: the alternative is a lock on the
     * hottest path in the server, to close a gap of one request in a defense
     * whose limits are round numbers chosen by judgement anyway.
     *
     * @return [RateLimitDecision.Refused] carrying the longest wait among the
     *   refusing keys, so a caller told to come back later is not immediately
     *   refused again by a different bucket.
     */
    fun tryAcquire(vararg keys: String): RateLimitDecision {
        sweepIfDue()
        val at = now()

        val refusals = keys.mapNotNull { key ->
            val window = windows[key]
            when {
                window == null || at - window.startedAt >= windowMillis -> null
                window.count < limit -> null
                else -> max(1L, (window.startedAt + windowMillis - at + 999) / 1000)
            }
        }
        if (refusals.isNotEmpty()) {
            return RateLimitDecision.Refused(refusals.max())
        }

        keys.forEach { key ->
            windows.compute(key) { _, existing ->
                if (existing == null || at - existing.startedAt >= windowMillis) {
                    Window(startedAt = at, count = 1)
                } else {
                    existing.copy(count = existing.count + 1)
                }
            }
        }
        return RateLimitDecision.Allowed
    }

    /**
     * Forget every window that has rolled.
     *
     * Without this the map holds one entry per address anyone has ever typed,
     * forever, which is a memory leak an attacker controls the size of. Called
     * opportunistically from [tryAcquire] rather than from a timer: a timer would
     * be a thread and a lifecycle to reason about for a map that only needs
     * tidying when it is being used, and a server nobody is calling has nothing to
     * sweep.
     *
     * @return how many were reclaimed. For tests; nothing logs it.
     */
    fun sweep(): Int {
        val at = now()
        lastSweptAt = at
        val stale = windows.entries.filter { at - it.value.startedAt >= windowMillis }
        stale.forEach { windows.remove(it.key, it.value) }
        return stale.size
    }

    /** How many keys are being tracked. For tests and diagnostics. */
    val size: Int get() = windows.size

    private fun sweepIfDue() {
        if (now() - lastSweptAt >= windowMillis) sweep()
    }
}

/**
 * Refuse this call with a `429` and a `Retry-After`.
 *
 * The honest answer, and the right one wherever the fact of a limit reveals
 * nothing. It is deliberately *not* what every caller does: an endpoint where the
 * refusal itself would leak — "this address is being rate-limited" is "this
 * address exists" — should render its ordinary success response instead and
 * simply not do the work. See the sign-in request endpoint, which does exactly
 * that. That choice belongs to the route, which is why [RateLimiter] returns a
 * decision rather than responding to anything.
 */
internal suspend fun ApplicationCall.respondRateLimited(refusal: RateLimitDecision.Refused, message: String) {
    response.header(HttpHeaders.RetryAfter, refusal.retryAfterSeconds.toString())
    logger.info("Rate limited ${request.local.uri}: retry after ${refusal.retryAfterSeconds}s")
    respond(HttpStatusCode.TooManyRequests, message)
}
