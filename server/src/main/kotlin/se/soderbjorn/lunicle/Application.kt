/**
 * Ktor server entry point and module wiring for Lunicle, the Lunamux issue tracker.
 *
 *  - [main] reads the deployment's port and starts Netty.
 *  - [Application.module] installs plugins, sets the framing headers, mounts
 *    the auth and board routes, and serves the Kotlin/JS bundle.
 *
 * This is also the one place that sees every store and every repository, so it
 * is where they are wired together — and the only place that knows the whole
 * shape of the server. Read it as the table of contents.
 *
 * @see boardRoutes
 * @see authRoutes
 * @see frameAncestors
 */
package se.soderbjorn.lunicle

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The port to bind.
 *
 * Railway injects `PORT` into the container and routes its edge to it; the value
 * is not stable across deploys, so it must be read at runtime rather than baked
 * in. The 8080 fallback is for local runs.
 *
 * The system property comes first, and exists for the same reason every other
 * one in this server does: `:server:run` is a Gradle `JavaExec`, which inherits
 * the long-lived **daemon's** environment rather than the invoking shell's. So
 * `PORT=9000 ./gradlew :server:run` does not do what it plainly looks like it
 * does — the daemon was started without it, the server binds 8080 anyway, and
 * the only symptom is something else's page answering on the port you expected.
 * A per-invocation property cannot drift that way. See resolveFrameAncestors.
 */
private fun resolvePort(): Int =
    System.getProperty("lunicle.port")?.toIntOrNull()
        ?: System.getenv("PORT")?.toIntOrNull()
        ?: 8080

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
 * the auth and board routes plus the static web bundle.
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
    val oauthConfig = resolveOAuthConfig()

    // Opening the database creates or migrates the schema, so this is also
    // where a bad volume announces itself — see openDatabase(). Deliberately
    // not wrapped in a try/catch: a server that cannot reach its database has
    // nothing to serve, and starting anyway would turn a loud startup failure
    // into a 500 on every request instead.
    //
    // The location is resolved once, here, and used for both the database file
    // and the attachment directory — see DatabaseLocation.attachmentsDirectory
    // for why those must not be two independent settings.
    val location = resolveDatabaseLocation()
    val opened = openDatabase(location)
    val database = opened.database

    val sessions = SessionStore(database)
    val users = UserStore(database)
    val roles = RoleStore(database)
    val projects = ProjectStore(database)
    val labels = LabelStore(database)
    val components = ComponentStore(database)
    val statuses = StatusStore(database)
    val priorities = PriorityStore(database)
    val resolutions = ResolutionStore(database)
    // One instance, shared by the auth routes that start and stop impersonation
    // and the board routes that enforce it. In memory, so a restart is an
    // implicit "stop" for everyone — see Impersonations.
    val impersonations = Impersonations()
    val issues = IssueStore(database)
    val comments = CommentStore(database)
    val attachments = AttachmentStore(database)

    // The authorization server's own stores. Lunicle is an OAuth *client* against
    // Google and GitHub (see authRoutes) and an OAuth *server* here — the two
    // roles share this process and nothing else. See OAuthServer's preamble.
    val oauthClients = OAuthClientStore(database)
    val oauthLoginStates = OAuthLoginStateStore(database)
    val oauthCodes = OAuthCodeStore(database)
    val oauthTokens = OAuthTokenStore(database)

    val attachmentRepository = AttachmentRepository(attachments, location.attachmentsDirectory)
    val projectRepository = ProjectRepository(database, projects, attachmentRepository, attachments)
    val issueRepository =
        IssueRepository(issues, comments, statuses, priorities, attachmentRepository, attachments)
    val vocabularyRepository =
        VocabularyRepository(database, labels, components, statuses, priorities, resolutions, issues)
    val access = AccessControl(roles)

    // Named rather than built inline at the routing block, because two transports
    // now share it: the HTTP board routes and the MCP tools. That sharing is the
    // point — "the MCP surface is a second front door onto code that has already
    // been reasoned about" is only true if it is literally the same object graph,
    // and a second BoardDependencies built for the tools would be the first step
    // toward two subtly different servers. See McpTools.
    val boardDependencies = BoardDependencies(
        access = access,
        projects = projects,
        projectRepository = projectRepository,
        roles = roles,
        vocabularies = vocabularyRepository,
        labels = labels,
        components = components,
        statuses = statuses,
        priorities = priorities,
        resolutions = resolutions,
        issues = issues,
        issueRepository = issueRepository,
        comments = comments,
        attachments = attachments,
        attachmentRepository = attachmentRepository,
        sessions = sessions,
        users = users,
        impersonations = impersonations,
    )

    val mcpDependencies = McpDependencies(
        clients = oauthClients,
        loginStates = oauthLoginStates,
        codes = oauthCodes,
        tokens = oauthTokens,
        sessions = sessions,
        users = users,
        impersonations = impersonations,
        config = oauthConfig,
    )

    // Startup housekeeping, all of it in one launch{} rather than blocking the
    // module: a slow sweep must not hold up the port binding, because Railway's
    // healthcheck is watching and nothing here is a precondition for serving.
    //
    // The one thing that IS ordered: roles are seeded before anything can ask
    // whether someone holds one. In practice the first request cannot beat this
    // — but "in practice" is doing work in that sentence, so the seed goes
    // first inside the coroutine rather than being raced against the sweeps.
    launch {
        // Unconditional and idempotent (INSERT OR IGNORE), which is what lets a
        // fresh volume, a purged one, and one that has been serving for a month
        // all take the same code path. See RoleStore.seed.
        log.info("Roles available: ${roles.seed()}")

        val removed = sessions.deleteExpired()
        if (removed > 0) log.info("Removed $removed expired session(s)")
        log.info("Sessions live: ${sessions.size()}")

        // Collects the files behind cancelled drafts, half-failed writes, and
        // cascades that took an attachment's row but could not reach its bytes.
        // See AttachmentRepository.sweepOrphans.
        val swept = attachmentRepository.sweepOrphans()
        if (swept > 0) log.info("Swept $swept orphaned attachment file(s)")

        // The OAuth sweeps, alongside the session one and for the same reason: a
        // container Railway replaces on every deploy gets swept often enough, and
        // a timer would be a second thing to reason about.
        //
        // Worth being precise about what a missed sweep costs here, because it is
        // NOT the same trade Sessions.kt makes. Every OAuth lookup has
        // `expires_at > ?` in its WHERE clause — see OAuthLoginState.sq — so a
        // stale row is already refused by the query. Sweeping is therefore a
        // disk-space question and never a security one, which is exactly the
        // property that lets it be startup-only on a half-gigabyte volume.
        val expiredCodes = oauthCodes.deleteExpired()
        val expiredTokens = oauthTokens.deleteExpired()
        val expiredLoginStates = oauthLoginStates.deleteExpired()
        if (expiredCodes + expiredTokens + expiredLoginStates > 0) {
            log.info(
                "Removed $expiredCodes expired auth code(s), $expiredTokens token(s), " +
                    "$expiredLoginStates pending authorization(s)",
            )
        }
        // The counterweight to /oauth/register being unauthenticated: a client
        // that registered, was never used, and holds no tokens is a row somebody
        // on the internet created and walked away from. See OAuthClients.sq.
        val staleClients = oauthClients.sweepStale()
        if (staleClients > 0) log.info("Removed $staleClients stale OAuth client registration(s)")
        log.info("MCP: ${oauthClients.size()} client(s), ${oauthTokens.size()} token row(s)")
    }

    // Close the driver when Ktor shuts down, so SQLite can checkpoint the WAL
    // back into the database file and release the lock. Without this the volume
    // keeps a -wal alongside the .db and recovery is left to the next open —
    // which works, but means an unclean file on a volume we may want to copy.
    monitor.subscribe(ApplicationStopping) {
        log.info("Closing the database")
        opened.close()
    }

    routing {
        authRoutes(oauthConfig, sessions, users, impersonations)

        // Lunicle as an authorization server, and the MCP endpoint it protects.
        // Both are deliberately unauthenticated at the route level — see
        // OAuthServer's preamble — while mcpApiRoutes below is the ordinary
        // cookie-authenticated view a human gets of the same machinery.
        oauthRoutes(mcpDependencies)
        mcpRoutes(mcpDependencies, McpTools(boardDependencies))
        mcpApiRoutes(mcpDependencies)

        boardRoutes(boardDependencies)

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
