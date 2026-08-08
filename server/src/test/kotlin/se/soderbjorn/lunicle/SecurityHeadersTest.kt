/**
 * The application origin's Content-Security-Policy (LUS-27).
 *
 * For a long time the only security-relevant header this server set on its own
 * HTML was `frame-ancestors`, which governs who may *embed* a page and says
 * nothing about what may execute on it — so the policy provided no XSS mitigation
 * whatsoever. The design rested entirely on nothing hostile ever running on this
 * origin, which the renderer upholds and which is a real property; what was
 * missing was a second line for the day that slips.
 *
 * These assertions are deliberately about the *clauses*, not the exact string. A
 * test comparing the whole header would fail on a reordering and pass on a
 * loosening, which is precisely backwards.
 *
 * @see appContentSecurityPolicy
 */
package se.soderbjorn.lunicle

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityHeadersTest {

    @Test
    fun `the app policy closes the classic injection routes`() {
        val policy = appContentSecurityPolicy(null)
        assertContains(policy, "default-src 'self'")
        assertContains(policy, "object-src 'none'")
        assertContains(policy, "base-uri 'none'")
        assertContains(policy, "form-action 'self'")
    }

    /**
     * The clause that does the work: no inline script, no eval.
     *
     * `style-src 'unsafe-inline'` stays, and stays deliberately — the toolkit
     * installs its theme as a `<style>` element and the views set `style`
     * attributes, both of which CSP counts as inline. Removing it needs a nonce
     * threaded into the bundle. Inline *style* is a far smaller weapon than inline
     * script, and conflating the two is how a policy ends up with neither.
     */
    @Test
    fun `script may not be inline or evaluated`() {
        val policy = appContentSecurityPolicy(null)
        val scriptSrc = policy.split("; ").first { it.startsWith("script-src") }
        assertFalse(
            "'unsafe-inline'" in scriptSrc,
            "script-src allows inline script, which is the whole of what this policy is for.",
        )
        assertFalse("'unsafe-eval'" in scriptSrc, "script-src allows eval.")
        assertTrue(
            "'unsafe-inline'" in policy.split("; ").first { it.startsWith("style-src") },
            "style-src lost 'unsafe-inline' — the toolkit's theme element and every style " +
                "attribute in the app stop applying, which is a blank page rather than a fix.",
        )
    }

    /**
     * Google's sign-in script is the only third party, and it needs three clauses.
     *
     * A typo in any of them is a sign-in button that silently does nothing, which
     * is why the origin is named once in the source rather than three times.
     */
    @Test
    fun `the Google sign-in script is loadable`() {
        val policy = appContentSecurityPolicy(null)
        listOf("script-src", "connect-src", "frame-src").forEach { directive ->
            assertContains(
                policy.split("; ").first { it.startsWith(directive) },
                "https://accounts.google.com",
                message = "$directive does not permit Google Identity Services, so sign-in is broken.",
            )
        }
    }

    /** A person can write `![](https://…)` in an issue, and it has to render. */
    @Test
    fun `remote images stay loadable`() {
        assertContains(
            appContentSecurityPolicy(null).split("; ").first { it.startsWith("img-src") },
            "https:",
            message = "Images written into issue descriptions stopped loading — that is a " +
                "functional regression sold as hardening.",
        )
    }

    /** The configured embedder still reaches the app's policy, unchanged. */
    @Test
    fun `the framing policy still carries a configured ancestor`() {
        assertContains(appContentSecurityPolicy(null), "frame-ancestors 'self'")
        assertContains(
            appContentSecurityPolicy("https://lunamux.dev"),
            "frame-ancestors 'self' https://lunamux.dev",
        )
    }
}
