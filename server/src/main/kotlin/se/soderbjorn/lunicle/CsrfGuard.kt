/**
 * Where a state-changing request came from, and whether that is somewhere this
 * server accepts requests from (LUS-12).
 *
 * ── What stood here before: nothing, and it nearly worked ───────────────────
 *
 * There was no CSRF token, no `Origin` check and no `Sec-Fetch-Site` check
 * anywhere. The session cookie is issued `Secure; SameSite=None; Partitioned` in
 * production, deliberately, so the tracker can be framed by the marketing site —
 * which removes the defence `SameSite=Lax` would otherwise have given for free.
 *
 * What actually prevented forgery was **incidental**, and worth naming precisely
 * because it is the sort of accident a later refactor removes without noticing:
 *
 *  - every JSON-bodied endpoint requires a content type that forces a CORS
 *    preflight, and no CORS plugin is installed to answer one; and
 *  - `Partitioned` (CHIPS) confines the cookie to the top-level site that set it.
 *
 * The residue is the handful of **bodyless** POSTs — sign-out, arm-impersonation,
 * stop-impersonating, cancel-email-change — which are CORS-simple and need no
 * preflight. They are reachable from any browser that ignores `Partitioned` while
 * still honouring `SameSite=None`, and from any page on a configured framing
 * origin, which is inside the same cookie partition by construction. The sharpest
 * of them silently signs an owner out and leaves their browser armed, or ends a
 * probe in flight.
 *
 * Rated Low in the review because no *reachable* forged request produced
 * attacker-controlled state. That is a property of which endpoints happen to take
 * a body, not a designed boundary. This file makes it a designed boundary.
 *
 * ── The rule, and the one thing it must not break ───────────────────────────
 *
 * Same-origin, plus the framing origins the deployment already names — which is
 * exactly the set of sites permitted to drive this server, resolved once at boot
 * and reused rather than configured a second time.
 *
 * The thing it must not break is every **non-browser** client. Agents at `/mcp`,
 * `/oauth/token` and `/oauth/register`, and anybody with `curl`, send neither
 * header — so a request with neither is allowed. That is not a hole a browser can
 * climb through: a browser attaches `Origin` to every cross-origin request it
 * makes and cannot be told not to. An attacker who can omit headers at will is not
 * running in the victim's browser, and therefore has no session cookie to forge
 * with, which is the whole premise of CSRF.
 *
 * @see frameAncestors
 */
package se.soderbjorn.lunicle

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("CsrfGuard")

/**
 * Refuse a state-changing request that a browser says came from somewhere else.
 *
 * @param allowedOrigins the framing origins this deployment configured, as the
 *   `frame-ancestors` source list — the same value the CSP is built from, split
 *   on whitespace. Null or blank for a deployment that names none, which is then
 *   same-origin only.
 */
fun Application.installOriginCheck(allowedOrigins: String?) {
    // Parsed once at install rather than per request. `'self'` and any other
    // quoted CSP keyword are not origins and are dropped: `'self'` is already
    // covered by the same-origin comparison, and a keyword like `'none'` would
    // never match an Origin header anyway.
    val allowed = allowedOrigins.orEmpty()
        .split(' ', '\t', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("'") }
        .toSet()

    intercept(ApplicationCallPipeline.Plugins) {
        val method = call.request.httpMethod
        // Reads are not guarded, and must not be: a `GET` is where an OAuth
        // redirect lands and where a framed page loads, and refusing a cross-site
        // navigation would break both. This is about requests that *change*
        // something, which is the whole of what CSRF is for.
        if (method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Options) {
            return@intercept
        }

        val refusal = originRefusal(
            secFetchSite = call.request.header(SEC_FETCH_SITE),
            origin = call.request.header(HttpHeaders.Origin),
            serverOrigin = call.serverOrigin(),
            allowedOrigins = allowed,
        ) ?: return@intercept

        logger.warn("Refused a cross-site ${method.value} ${call.request.path()}: $refusal")
        call.respond(HttpStatusCode.Forbidden, "That request did not come from this site.")
        finish()
    }
}

/** Ktor's `HttpHeaders` has no constant for the Fetch Metadata headers. */
private const val SEC_FETCH_SITE = "Sec-Fetch-Site"

/**
 * Why a request should be refused, or null to let it through.
 *
 * A pure function so the decision can be asserted directly — the headers it reads
 * are the ones a test client is least able to set honestly, and the interesting
 * cases are combinations rather than round trips.
 *
 * ── Why `Sec-Fetch-Site` is preferred over `Origin` ─────────────────────────
 *
 * Because the browser computes it, and it answers the question being asked
 * directly rather than by comparison. `same-origin` and `same-site` are this site;
 * `none` is a user-initiated navigation — typing a URL, a bookmark — which no
 * attacker's page can cause; `cross-site` is the case this file exists for.
 *
 * Every browser that sends it has sent it since 2020. A browser old enough to omit
 * it still sends `Origin` on a cross-origin request, so the fall-through is a
 * comparison rather than a gap.
 */
internal fun originRefusal(
    secFetchSite: String?,
    origin: String?,
    serverOrigin: String,
    allowedOrigins: Set<String>,
): String? {
    fun permitted(value: String) = value == serverOrigin || value in allowedOrigins

    when (secFetchSite) {
        // The browser has already answered. `same-site` is admitted alongside
        // `same-origin` because a deployment on a subdomain of its own marketing
        // site is the ordinary shape here, and the framing origin below covers the
        // case where it is not.
        "same-origin", "same-site", "none" -> return null
        "cross-site" ->
            return if (origin != null && permitted(origin)) {
                null
            } else {
                "Sec-Fetch-Site: cross-site from origin ${origin ?: "(absent)"}"
            }
    }

    // No Fetch Metadata. An Origin still settles it when there is one.
    if (origin != null) {
        return if (permitted(origin)) null else "Origin $origin is not this site"
    }

    // Neither header: not a browser. See the file preamble — an attacker who can
    // omit headers is not inside the victim's browser and holds no session cookie.
    return null
}
