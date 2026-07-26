/**
 * The limiter, and the question it is actually keyed on.
 *
 * The counter half is easy and the identity half is where this goes wrong
 * silently. A limiter keyed on a forgeable header is not a limiter; a limiter
 * keyed on the socket peer behind Railway's proxy locks out the entire internet
 * the first time anybody uses it. Neither failure produces an error — one refuses
 * nobody and one refuses everybody, and both look like they are working from the
 * inside. So the resolver gets the longer half of this file.
 *
 * The clock is injected throughout and nothing sleeps. A test that waited out a
 * window would be slow and, worse, flaky at exactly the boundary it exists to
 * pin.
 *
 * @see RateLimiter
 * @see clientIdentity
 */
package se.soderbjorn.lunicle

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.response.respondText
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RateLimiterTest {

    private var clock: Long = 1_700_000_000_000

    private fun limiter(limit: Int = 3, windowMillis: Long = 60_000) =
        RateLimiter(limit, windowMillis) { clock }

    // ── The counter ──────────────────────────────────────────────────────────

    @Test
    fun `a key spends its budget and is then refused`() {
        val limiter = limiter(limit = 3)
        repeat(3) { attempt ->
            assertIs<RateLimitDecision.Allowed>(
                limiter.tryAcquire("alice"),
                "Attempt ${attempt + 1} of 3 was refused.",
            )
        }
        val refused = limiter.tryAcquire("alice")
        assertIs<RateLimitDecision.Refused>(refused, "The fourth attempt inside the window was allowed.")
        assertTrue(refused.retryAfterSeconds >= 1, "Retry-After was ${refused.retryAfterSeconds}, which invites an immediate retry.")
    }

    /**
     * The window boundary, from both sides.
     *
     * One millisecond short of the window must still refuse and one millisecond
     * past it must allow. An off-by-one here is a limiter that is either a window
     * too generous or one that resets a beat early, and neither shows up in a
     * test that only checks "eventually it lets me back in".
     */
    @Test
    fun `the window rolls at its boundary and not before`() {
        val limiter = limiter(limit = 1, windowMillis = 60_000)
        assertIs<RateLimitDecision.Allowed>(limiter.tryAcquire("alice"))

        clock += 59_999
        assertIs<RateLimitDecision.Refused>(limiter.tryAcquire("alice"), "The window rolled early.")

        clock += 1
        assertIs<RateLimitDecision.Allowed>(limiter.tryAcquire("alice"), "The window did not roll on time.")
    }

    @Test
    fun `two keys do not interfere`() {
        val limiter = limiter(limit = 1)
        assertIs<RateLimitDecision.Allowed>(limiter.tryAcquire("alice"))
        assertIs<RateLimitDecision.Refused>(limiter.tryAcquire("alice"))
        assertIs<RateLimitDecision.Allowed>(
            limiter.tryAcquire("bob"),
            "One key's exhaustion refused another's first attempt.",
        )
    }

    /**
     * A refusal on one key spends nothing on the others.
     *
     * The property that makes the compose-two-keys pattern safe to use: an
     * endpoint limited on both a target address and a client address must not
     * have the client's budget quietly drained every time the address bucket says
     * no, or a burst against one target would lock the caller out of every other.
     */
    @Test
    fun `a refusal on one key leaves the other keys untouched`() {
        val limiter = limiter(limit = 1)
        // Exhaust the address key alone.
        assertIs<RateLimitDecision.Allowed>(limiter.tryAcquire("addr:victim@example.com"))

        // Now ask for both. The address refuses, so the client must not be spent.
        assertIs<RateLimitDecision.Refused>(
            limiter.tryAcquire("addr:victim@example.com", "client:1.2.3.4"),
        )
        assertIs<RateLimitDecision.Allowed>(
            limiter.tryAcquire("client:1.2.3.4"),
            "Being refused on the address key spent the client's budget too.",
        )
    }

    @Test
    fun `refusing on either key refuses the pair`() {
        val limiter = limiter(limit = 1)
        assertIs<RateLimitDecision.Allowed>(limiter.tryAcquire("client:1.2.3.4"))
        assertIs<RateLimitDecision.Refused>(
            limiter.tryAcquire("addr:fresh@example.com", "client:1.2.3.4"),
            "An exhausted client key was let through because the address key had room.",
        )
    }

    // ── The sweep ────────────────────────────────────────────────────────────

    /**
     * The sweep actually reclaims, and only what has rolled.
     *
     * Without it the map holds an entry per address anybody has ever typed at the
     * sign-in form — a leak whose size an attacker chooses.
     */
    @Test
    fun `the sweep reclaims rolled windows and keeps live ones`() {
        val limiter = limiter(limit = 5, windowMillis = 60_000)
        limiter.tryAcquire("old")
        clock += 30_000
        limiter.tryAcquire("recent")
        assertEquals(2, limiter.size)

        clock += 30_001
        assertEquals(1, limiter.sweep(), "The sweep did not reclaim the rolled window.")
        assertEquals(1, limiter.size, "The sweep took a window that was still live.")
    }

    @Test
    fun `acquiring sweeps without anyone asking it to`() {
        val limiter = limiter(limit = 5, windowMillis = 60_000)
        repeat(50) { limiter.tryAcquire("key$it") }
        assertEquals(50, limiter.size)

        clock += 60_001
        limiter.tryAcquire("something-new")
        assertEquals(
            1,
            limiter.size,
            "Fifty rolled windows survived an acquire, so nothing sweeps unless a caller remembers to.",
        )
    }

    // ── The identity, which is the half that fails silently ─────────────────

    /**
     * The forged leftmost entry, which is the whole attack.
     *
     * An attacker sends `X-Forwarded-For: 1.2.3.4`; Railway appends the address
     * it actually received from. Reading the leftmost entry — the obvious and
     * wrong implementation — gives the attacker a fresh bucket per request by
     * changing one header, and the limiter refuses nobody, ever, while appearing
     * to work perfectly in every local test.
     */
    @Test
    fun `a forged leftmost entry is ignored in favour of what the proxy saw`() = testApplication {
        mountIdentity(trustedHops = 1)
        val body = client.get("/who") {
            header("X-Forwarded-For", "1.2.3.4, 203.0.113.7")
        }.bodyAsText()
        assertEquals(
            "203.0.113.7",
            body,
            "The client's own forged X-Forwarded-For entry was trusted, so the limiter is bypassable by a header.",
        )
    }

    @Test
    fun `a legitimate multi-hop chain resolves to the client`() = testApplication {
        // A CDN in front of Railway: two trusted hops, so the entry two from the
        // right is the address the outermost trusted proxy observed.
        mountIdentity(trustedHops = 2)
        val body = client.get("/who") {
            header("X-Forwarded-For", "9.9.9.9, 203.0.113.7, 10.0.0.1")
        }.bodyAsText()
        assertEquals("203.0.113.7", body)
    }

    @Test
    fun `an absent header falls back to the socket peer`() = testApplication {
        mountIdentity(trustedHops = 1)
        val body = client.get("/who").bodyAsText()
        assertTrue(body.isNotBlank(), "An absent header resolved to nothing, which is a bucket everyone shares.")
    }

    /**
     * Malformed input fails closed, never open.
     *
     * A header of commas, or one with fewer entries than there are trusted hops,
     * must land on the socket peer. A resolver that returned null or a constant
     * here would hand an attacker a bypass consisting of a deliberately broken
     * header — which is a strictly easier attack than forging a plausible one.
     */
    @Test
    fun `a malformed header falls back to the socket peer`() = testApplication {
        mountIdentity(trustedHops = 2)
        val peer = client.get("/who").bodyAsText()

        assertEquals(peer, client.get("/who") { header("X-Forwarded-For", " , , ") }.bodyAsText())
        // One entry, two trusted hops: the chain is shorter than it should be, so
        // there is no trustworthy entry to read.
        assertEquals(peer, client.get("/who") { header("X-Forwarded-For", "1.2.3.4") }.bodyAsText())
    }

    @Test
    fun `trusting no hops ignores the header entirely`() = testApplication {
        mountIdentity(trustedHops = 0)
        val peer = client.get("/who").bodyAsText()
        assertEquals(
            peer,
            client.get("/who") { header("X-Forwarded-For", "1.2.3.4, 5.6.7.8") }.bodyAsText(),
            "With nothing in front of the server, a forwarded header is the client talking about itself.",
        )
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.mountIdentity(trustedHops: Int) {
        application {
            routing {
                get("/who") { call.respondText(call.clientIdentity(trustedHops)) }
            }
        }
    }
}
