/**
 * Ktor server entry point and module wiring for Lunicle, the Lunamux issue tracker.
 *
 *  - [main] reads the deployment's port and starts Netty.
 *  - [Application.module] installs plugins, sets the framing headers, mounts
 *    the counter routes from [counterRoutes], and serves the Kotlin/JS bundle.
 *
 * Stage 1 has no auth and no persistence by design — the counter is set
 * dressing for the infrastructure being proven. See docs/stages.html.
 *
 * @see counterRoutes
 * @see frameAncestors
 */
package se.soderbjorn.lunicle

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The port to bind. Railway injects `PORT` into the container and routes its
 * edge to it; the value is not stable across deploys, so it must be read at
 * runtime rather than baked in. The 8080 fallback is for local runs.
 */
private fun resolvePort(): Int =
    System.getenv("PORT")?.toIntOrNull() ?: 8080

/**
 * The origin permitted to frame this server, as a CSP `frame-ancestors`
 * source. Defaults to the marketing site, which is the only page that should
 * ever embed the tracker.
 *
 * Two overrides, in priority order:
 *  1. The `lunicle.frameAncestors` system property, which `:server:run` always
 *     sets (see `server/build.gradle.kts`). It exists because the environment
 *     variable below is not a good fit under Gradle: a `JavaExec` task inherits
 *     the long-lived Gradle **daemon's** environment rather than the invoking
 *     shell's, so the daemon's startup environment would decide a local run's
 *     framing policy. A per-invocation system property cannot drift that way.
 *  2. The `FRAME_ANCESTORS` environment variable, for the deployed container
 *     (Railway, a preview deploy), where the process is `java -jar` with no
 *     daemon in the picture and the environment is exact.
 *
 * @see frameAncestors
 */
private fun resolveFrameAncestors(): String =
    // Blank is treated as absent throughout: an empty override is a
    // misconfiguration, and silently emitting `frame-ancestors 'self'` would
    // break the embed in a way that looks like a code bug.
    System.getProperty("lunicle.frameAncestors")?.takeIf { it.isNotBlank() }
        ?: System.getenv("FRAME_ANCESTORS")?.takeIf { it.isNotBlank() }
        ?: "https://lunamux.dev"

/** Ktor's `HttpHeaders` has no constant for this one. */
private const val CONTENT_SECURITY_POLICY = "Content-Security-Policy"

/**
 * Build the `Content-Security-Policy` value that permits framing from
 * [resolveFrameAncestors] and nothing else.
 *
 * `frame-ancestors` is the *only* mechanism that works here. The obvious
 * alternative, `X-Frame-Options: ALLOW-FROM`, is dead — no browser honours it,
 * and setting `X-Frame-Options` at all would be actively harmful, since
 * `SAMEORIGIN`/`DENY` would block the lunamux.dev embed outright. So this
 * server sets CSP and never sets X-Frame-Options.
 *
 * `'self'` is included so the tracker can also frame itself, and so that
 * opening lunicle.lunamux.dev directly is unaffected (frame-ancestors only
 * governs who may *embed* the page, never who may visit it).
 *
 * @param ancestors the permitted framing origin.
 * @return the CSP header value.
 */
internal fun frameAncestors(ancestors: String): String =
    "frame-ancestors 'self' $ancestors"

/**
 * Process entry point: start Netty on [resolvePort] and block.
 */
fun main() {
    val port = resolvePort()
    val logger = LoggerFactory.getLogger("Application")
    // Which providers are live is worth a line: "sign-in doesn't appear" is
    // otherwise indistinguishable from "the button is broken", and the answer
    // is usually a variable that didn't reach the process. Names only — never a
    // secret, not even a prefix of one.
    logger.info(
        "Starting Lunicle on port $port; frame-ancestors=${resolveFrameAncestors()}; " +
            "oauth=${resolveOAuthConfig().describe()}",
    )
    embeddedServer(
        factory = Netty,
        port = port,
        // Bind all interfaces: inside a container, localhost-only would be
        // unreachable from Railway's edge proxy and every request would hang.
        host = "0.0.0.0",
        module = Application::module,
    ).start(wait = true)
}

/**
 * Ktor application module: installs plugins, sets framing headers, and mounts
 * the counter routes plus the static web bundle.
 *
 * Static serving has two flows, matching Lunamux's server:
 *  - Dev (`-Dlunicle.webDist=…`, set by `:server:run`): serve the bundle from
 *    disk so a web edit needs no re-jar.
 *  - Packaged (no property): serve it from `/web` inside the jar, staged there
 *    by the `copyWebDistToResources` task.
 */
fun Application.module() {
    install(ContentNegotiation) { json() }
    install(CallLogging)
    install(DefaultHeaders) {
        header(CONTENT_SECURITY_POLICY, frameAncestors(resolveFrameAncestors()))
    }

    val webDistPath = System.getProperty("lunicle.webDist")

    routing {
        counterRoutes()
        if (webDistPath != null) {
            staticFiles("/", File(webDistPath)) {
                default("index.html")
            }
        } else {
            staticResources("/", "web") {
                default("index.html")
            }
        }
    }
}
