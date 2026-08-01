/**
 * Where a state-changing request is allowed to come from (LUS-12).
 *
 * There was no CSRF token, no Origin check and no Sec-Fetch-Site check anywhere,
 * and the production session cookie is `SameSite=None` by design so the tracker
 * can be framed. What prevented forgery was incidental — a content type that
 * forces a preflight nothing answers, and CHIPS partitioning — and the residue was
 * the bodyless POSTs, which are CORS-simple: sign-out, arm-impersonation,
 * stop-impersonating, cancel-email-change. The sharpest silently signs an owner
 * out and leaves their browser armed.
 *
 * These assertions are against [originRefusal] rather than through a client,
 * because `Origin` and `Sec-Fetch-Site` are exactly the headers a browser computes
 * and a test client cannot honestly forge — and because what matters is the
 * *combinations*, which is a table rather than a round trip.
 *
 * @see installOriginCheck
 */
package se.soderbjorn.lunicle

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CsrfGuardTest {
    private val us = "https://issues.example.com"
    private val embedder = setOf("https://marketing.example.com")

    private fun refusal(secFetchSite: String? = null, origin: String? = null) =
        originRefusal(secFetchSite, origin, us, embedder)

    @Test
    fun `the browser's own answer is taken when it gives one`() {
        assertNull(refusal(secFetchSite = "same-origin"))
        assertNull(refusal(secFetchSite = "same-site"))
        // A typed URL or a bookmark — nothing an attacker's page can cause.
        assertNull(refusal(secFetchSite = "none"))
    }

    @Test
    fun `a cross-site request from an unknown origin is refused`() {
        assertNotNull(
            refusal(secFetchSite = "cross-site", origin = "https://evil.example"),
            "A page on another site could sign an owner out, or end a probe in flight.",
        )
        assertNotNull(
            refusal(secFetchSite = "cross-site", origin = null),
            "A cross-site request with no origin at all was let through.",
        )
    }

    /**
     * The one cross-site case that has to keep working: the site this deployment
     * already names as permitted to frame it.
     */
    @Test
    fun `a configured embedder is allowed`() {
        assertNull(refusal(secFetchSite = "cross-site", origin = "https://marketing.example.com"))
    }

    /** An older browser sends no Fetch Metadata, and Origin settles it instead. */
    @Test
    fun `origin decides when there is no fetch metadata`() {
        assertNull(refusal(origin = us))
        assertNull(refusal(origin = "https://marketing.example.com"))
        assertNotNull(refusal(origin = "https://evil.example"))
    }

    /**
     * Neither header means it is not a browser — an agent at `/mcp`, a token
     * exchange, `curl`.
     *
     * Not a hole a browser climbs through: a browser attaches `Origin` to every
     * cross-origin request and cannot be told not to. Somebody who can omit headers
     * at will is not running inside the victim's browser, and so holds no session
     * cookie to forge with, which is the premise of the whole attack.
     */
    @Test
    fun `a request with neither header is allowed`() {
        assertNull(refusal())
    }

    /** A deployment naming no embedder is same-origin only. */
    @Test
    fun `with no configured embedder only this site is allowed`() {
        assertNull(originRefusal(null, us, us, emptySet()))
        assertNotNull(originRefusal(null, "https://marketing.example.com", us, emptySet()))
    }
}
