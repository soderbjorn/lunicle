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
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * A per-invocation property cannot drift that way. See resolveAllowedFrameAncestors.
 */
private fun resolvePort(): Int =
    System.getProperty("lunicle.port")?.toIntOrNull()
        ?: System.getenv("PORT")?.toIntOrNull()
        ?: 8080

/**
 * The origin(s) permitted to frame this server, as a CSP `frame-ancestors`
 * source list, or null when none is configured — in which case only the
 * server's own origin may frame it (`frame-ancestors 'self'`, see
 * [frameAncestors]). There is no default origin: a deployment that wants to be
 * embedded by another site names it explicitly.
 *
 * Two sources, in priority order:
 *  1. The `lunicle.allowedFrameAncestors` system property, which `:server:run`
 *     sets from `-PallowedFrameAncestors` (see `server/build.gradle.kts`). It
 *     exists because the environment variable below is not a good fit under
 *     Gradle: a `JavaExec` task inherits the long-lived Gradle **daemon's**
 *     environment rather than the invoking shell's, so the daemon's startup
 *     environment would decide a local run's framing policy. A per-invocation
 *     system property cannot drift that way.
 *  2. The `LUNICLE_ALLOWED_FRAME_ANCESTORS` environment variable, for the
 *     deployed container, where the process is `java -jar` with no daemon in
 *     the picture and the environment is exact.
 *
 * Blank is treated as absent throughout, as everywhere else: an empty override
 * falls through to the 'self'-only policy rather than emitting a stray source.
 *
 * @see frameAncestors
 */
private fun resolveAllowedFrameAncestors(): String? =
    System.getProperty("lunicle.allowedFrameAncestors")?.takeIf { it.isNotBlank() }
        ?: System.getenv("LUNICLE_ALLOWED_FRAME_ANCESTORS")?.takeIf { it.isNotBlank() }

/** Ktor's `HttpHeaders` has no constant for this one. */
private const val CONTENT_SECURITY_POLICY = "Content-Security-Policy"

/** Nor this one. Set globally since LUS-27; it used to be on attachments only. */
private const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"

/** Nor this one. */
private const val PERMISSIONS_POLICY = "Permissions-Policy"

/** Nor this one. */
private const val REFERRER_POLICY = "Referrer-Policy"

/**
 * How old a draft issue must be before the startup sweep takes it — seven days.
 *
 * Generous on purpose, because the database cannot tell an abandoned draft from
 * an editor somebody left open: both are a row with `is_draft = 1` and an old
 * `created_at`. Seven days is far longer than any tab survives a laptop's sleep,
 * a browser update or a deploy of this server, and losing a week-old unsaved
 * "New issue" costs a title nobody typed. See IssueStore.sweepAbandonedDrafts.
 */
private const val ABANDONED_DRAFT_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

/**
 * Build the `Content-Security-Policy` value that permits framing from
 * [resolveAllowedFrameAncestors] and nothing else.
 *
 * `frame-ancestors` is the *only* mechanism that works here. The obvious
 * alternative, `X-Frame-Options: ALLOW-FROM`, is dead — no browser honours it,
 * and setting `X-Frame-Options` at all would be actively harmful, since
 * `SAMEORIGIN`/`DENY` would block a configured embed outright. So this server
 * sets CSP and never sets X-Frame-Options.
 *
 * `'self'` is always included so the tracker can frame itself and so that
 * opening it directly is unaffected (frame-ancestors only governs who may
 * *embed* the page, never who may visit it). When [ancestors] is null or blank
 * — no external embedder configured — `'self'` is the whole policy.
 *
 * @param ancestors the permitted framing origin(s), or null for 'self' only.
 * @return the CSP header value.
 */
internal fun frameAncestors(ancestors: String?): String =
    if (ancestors.isNullOrBlank()) "frame-ancestors 'self'" else "frame-ancestors 'self' $ancestors"

/**
 * Where Google Identity Services is loaded from, and talks to.
 *
 * Named once because it appears in three directives and a typo in any of them is
 * a sign-in button that silently does nothing. See `index.html`, which loads the
 * script, and `SignInView`, which checks for the global it defines.
 */
private const val GOOGLE_IDENTITY_ORIGIN = "https://accounts.google.com"

/**
 * The application origin's `Content-Security-Policy` (LUS-27).
 *
 * ── What this used to be ────────────────────────────────────────────────────
 *
 * `frame-ancestors` and nothing else. That is not a weak XSS mitigation, it is
 * **no XSS mitigation at all**: `frame-ancestors` governs who may embed this page
 * and says nothing about what may execute on it. The whole design rested on
 * nothing hostile ever running on this origin — which [Markdown] upholds, and
 * which is a real property rather than a hope — but there was no second line if it
 * ever slipped, and a renderer is exactly the kind of thing that slips.
 *
 * It is cheap here precisely because the app ships one self-hosted bundle. The
 * only third party in the whole page is Google's sign-in script.
 *
 * ── Why each loosening is here ──────────────────────────────────────────────
 *
 *  - `script-src` names [GOOGLE_IDENTITY_ORIGIN] because `index.html` loads GIS
 *    from it, and `frame-src`/`connect-src` because that library opens Google's
 *    own iframe and calls back to it. There is **no `'unsafe-inline'` and no
 *    `'unsafe-eval'` for script**, which is the clause that actually does the
 *    work: the bundle is one external file and the page has no inline script.
 *  - `style-src 'unsafe-inline'` is not optional and is worth being honest about.
 *    The toolkit installs its theme by creating a `<style>` element and setting
 *    its text, and the views set `style` attributes; both are inline styles as far
 *    as CSP is concerned. Removing it would need a nonce threaded from here into
 *    the bundle, which is a real change rather than a header edit. Inline *style*
 *    is a far smaller weapon than inline script.
 *  - `img-src https:` because a person can write `![](https://…)` in an issue and
 *    it renders. Blocking that would be a functional regression sold as hardening.
 *    `data:` and `blob:` are for inline attachments and previews.
 *
 * ── The server-rendered pages are NOT covered by this ───────────────────────
 *
 * The OAuth consent and sign-in pages carry inline `<script>` and inline `<style>`
 * and would be broken by the policy above. They set their own CSP header, and
 * [DefaultHeaders] skips a header the handler already set — which is the same
 * property that lets the attachment view replace this wholesale rather than merge
 * with it. **Keep that property.** See OAuthServer's `page`, and BoardRoutes'
 * `serveAttachment`.
 *
 * @param ancestors the permitted framing origin(s), or null for 'self' only.
 */
internal fun appContentSecurityPolicy(ancestors: String?): String = listOf(
    "default-src 'self'",
    "script-src 'self' $GOOGLE_IDENTITY_ORIGIN",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob: https:",
    "font-src 'self' data:",
    "connect-src 'self' $GOOGLE_IDENTITY_ORIGIN",
    "frame-src 'self' $GOOGLE_IDENTITY_ORIGIN",
    // Nothing here embeds Flash, Java or a PDF plugin, and `object` is the classic
    // way a filtered injection still executes.
    "object-src 'none'",
    // No `<base>` anywhere, and one injected would silently re-point every relative
    // URL on the page — including the bundle.
    "base-uri 'none'",
    "form-action 'self'",
    frameAncestors(ancestors),
).joinToString("; ")

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
        "Starting Lunicle on port $port; backend=${resolveDatabaseBackend().id}; " +
            "frame-ancestors=${resolveAllowedFrameAncestors() ?: "'self' only"}; " +
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
    // Before ContentNegotiation, and the order is the whole point (LUS-30): this
    // refuses an over-size body rather than letting one be buffered into the heap
    // of a small container and parsed. See installRequestBodyCeiling.
    installRequestBodyCeiling()
    install(ContentNegotiation) { json() }
    // An explicit formatter, and the reason is not style (LUS-34).
    //
    // CallLogging installed with no format uses Ktor's default formatter, and that
    // formatter has a special case: on **302 Found** it appends the full `Location`
    // header to the line. `respondRedirect` without `permanent = true` answers 302,
    // and the consent endpoint redirects with `code=…` in the query string — so
    // every successful agent consent wrote a live OAuth authorization code to
    // stdout, and from there into Cloud Logging or the Railway log stream.
    //
    // Lifting a code is not by itself enough: PKCE is mandatory, codes are stored
    // hashed and consumed on first use, so an attacker also needs the verifier that
    // never left the agent and has to win a race inside a two-minute single-use
    // window. What makes it worth fixing promptly is who can read those logs —
    // anyone with project viewer on the branded deployment, and the default service
    // account.
    //
    // Method, path and status, and nothing that came out of a query string or a
    // response header. Note the path is `request.path()` rather than the full URI:
    // a URI would carry the query string, which is where the code was in the first
    // place — the redirect is not the only way it could reach a log line.
    //
    // Any code already in the log window should be treated as spent.
    install(CallLogging) {
        format { call ->
            "${call.request.httpMethod.value} ${call.request.path()} — ${call.response.status()?.value ?: "unhandled"}"
        }
    }
    install(DefaultHeaders) {
        // Every one of these is skipped on a response whose handler already set it,
        // which is what lets the attachment view and the OAuth pages replace the CSP
        // wholesale rather than merge with it. That property is load-bearing — see
        // appContentSecurityPolicy.
        header(CONTENT_SECURITY_POLICY, appContentSecurityPolicy(resolveAllowedFrameAncestors()))
        // Was set on attachment responses only, which is where it matters most and
        // is not where it stops mattering: any route that gets a content type
        // slightly wrong is a route a browser may sniff into something executable.
        header(X_CONTENT_TYPE_OPTIONS, "nosniff")
        // Send the origin to other sites and the full URL only to ourselves. Issue
        // URLs carry ids, and an issue whose description links somewhere should not
        // tell that somewhere which issue the reader was looking at.
        header(REFERRER_POLICY, "strict-origin-when-cross-origin")
        // Ignored by browsers over plain http, so this is inert on a local run and
        // real in production. Two years, subdomains included; no `preload`, which is
        // a submission to a browser list and not a header to set casually.
        header(HttpHeaders.StrictTransportSecurity, "max-age=63072000; includeSubDomains")
        // Nothing in this application asks for a camera, a microphone or a location,
        // so an injection that did would be doing it on its own account.
        header(PERMISSIONS_POLICY, "camera=(), microphone=(), geolocation=(), payment=()")
    }

    val webDistPath = System.getProperty("lunicle.webDist")

    // Deploy-time branding (LNL-110). Read-only config, applied at load and never
    // written to the datastore — see BrandRoutes. Off ⇒ brandInfo is null, no
    // /brand routes are mounted, and index.html is served untouched, so the
    // default deployment is byte-for-byte what it was. The load is defensive: a
    // malformed brand file degrades the look rather than stopping the boot.
    val brandInfo = resolveBrandDir()?.let { loadBrandInfo(it) }
    log.info("Branding: ${brandInfo?.describe() ?: "brand dir: (unset)"}")

    // Sign-in, after branding, because the manifest has a say in it now (LNL-192):
    // `allowEmailCodeSignIn` is a third term on isEmailAvailable beside a configured
    // transport and LUNICLE_EMAIL_SIGN_IN, and it can only ever narrow. The
    // deployment's own identity is then everything the manifest says about itself
    // plus the two facts only this process knows — whether a mailed code actually
    // works here, and whether Google credentials reached it. See InstanceIdentity.
    val oauthConfig = resolveOAuthConfig(brandInfo?.allowEmailCodeSignIn ?: true)
    val instanceIdentity = brandInfo.toInstanceIdentity(
        isCodeSignInAvailable = oauthConfig.isEmailAvailable,
        // Both doors, honestly, because the admission rules ask about both at once
        // (LNL-195): a deployment with neither can honour no policy at all, and one
        // whose only door is a pinned Google chooser can admit no outsider under any.
        isGoogleAvailable = oauthConfig.google != null,
    )
    log.info(
        "Instance identity: domain=${instanceIdentity.domain ?: "(unset — no staff tier)"}; " +
            "google-pin=${instanceIdentity.googleHostedDomainPin ?: "(none)"}; " +
            "ways-in=${instanceIdentity.waysIn.joinToString(" · ").ifEmpty { "(none)" }}",
    )

    // What this process assumes about its own network, said out loud (LUS-31). It
    // decides whether every rate limit in the server keys on the caller or on the
    // proxy in front of them, and both ways of getting it wrong are quiet — so it
    // goes in the boot log rather than being left to be inferred from a bill or
    // from nobody being able to sign in. See resolveTrustedProxyHops.
    val trustedProxyHops = resolveTrustedProxyHops()
    if (trustedProxyHops == 0) {
        log.warn("Rate limiting: ${describeTrustedProxyHops(trustedProxyHops)}")
    } else {
        log.info("Rate limiting: ${describeTrustedProxyHops(trustedProxyHops)}")
    }

    // Which backend this process runs on, chosen once here — see DatabaseBackend.
    // Nothing GCP is touched unless FIRESTORE is selected: on the SQLite/Railway
    // path this is a pure env read and the Firestore branch of the graph below is
    // never entered — the FirestoreProvider rule. LNL-122 wired the second backend,
    // so a value of `firestore` now builds the Firestore graph rather than refusing.
    val backend = resolveDatabaseBackend()

    // The active transactional email transport — Resend, SMTP, or null when the
    // deployment configured neither. Null is Stage-1 behaviour, exactly like a
    // missing OAuth provider: the server boots and serves, the admin test-email
    // route answers "not configured", and notifications are composed and logged
    // rather than sent. Resolved here, before the store graph, because the graph's
    // e-mail-code service is built with it.
    val emailSender = resolveEmailTransport()
    log.info("Email sending: ${describeEmailTransport(emailSender)}")
    // Resolved and logged here because a wrong base URL is invisible until somebody
    // clicks a link in a mail that has already gone out — see resolvePublicBaseUrl().
    val publicBaseUrl = resolvePublicBaseUrl()
    if (publicBaseUrl == null && emailSender != null) {
        // Mail is configured but no base URL is: the deep links in notification
        // mail will be relative and unclickable from a mail client. Loud, because
        // this is invisible until somebody clicks a link in a mail that has already
        // gone out. Sign-in codes still work — they carry no link — so this
        // degrades rather than stops the boot. See resolvePublicBaseUrl().
        log.warn(
            "LUNICLE_PUBLIC_BASE_URL is not set but email is enabled; links in notification " +
                "mail will be relative and may not resolve from a mail client.",
        )
    }
    log.info("Email links point at: ${publicBaseUrl ?: "(unset — relative links)"}")
    // The link-builders take a non-null base; an unset public URL yields relative
    // links (warned about above) rather than a hardcoded fallback origin.
    val emailBaseUrl = publicBaseUrl ?: ""

    // The whole persistence layer, assembled for the selected backend — see
    // StoreGraph. On SQLite this opens the database (creating or migrating the
    // schema, so a bad volume still announces itself loudly through openDatabase);
    // on Firestore it builds the client and the document stores with their injected
    // seams. Deliberately not wrapped in a try/catch: a server that cannot reach its
    // datastore has nothing to serve, and starting anyway would turn a loud startup
    // failure into a 500 on every request.
    val stores = when (backend) {
        DatabaseBackend.SQLITE -> sqliteStoreGraph(resolveDatabaseLocation(), emailSender, emailBaseUrl)
        DatabaseBackend.FIRESTORE -> firestoreStoreGraph(emailSender, emailBaseUrl)
    }

    // Rebind the graph's interface-typed stores as locals, so everything below is
    // written against the store.* interfaces and reads identically on both backends.
    val sessions = stores.sessions
    val users = stores.users
    val roles = stores.roles
    val projects = stores.projects
    val labels = stores.labels
    val components = stores.components
    val statuses = stores.statuses
    val priorities = stores.priorities
    val resolutions = stores.resolutions
    val versions = stores.versions
    val issues = stores.issues
    val comments = stores.comments
    val attachments = stores.attachments
    val subscriptions = stores.subscriptions
    val reads = stores.reads
    val notificationStore = stores.notificationStore
    val uiSettings = stores.uiSettings
    // The deployment-wide switches (LNL-115): open project creation, public
    // projects, hidden display names. Persistent on both backends — unlike the
    // in-memory default the tests fall back to — because a switch that reset itself
    // on every redeploy is one nobody could trust to keep a private instance
    // private. Shared by the auth routes (which read the display-name one into the
    // session) and the board routes (which read the rest). See StoreGraph for the
    // per-backend construction.
    val instanceSettings = stores.instanceSettings
    val oauthClients = stores.oauthClients
    val oauthLoginStates = stores.oauthLoginStates
    val oauthCodes = stores.oauthCodes
    val oauthTokens = stores.oauthTokens
    val forumStore = stores.forums
    val forumPostStore = stores.forumPosts
    val forumCommentStore = stores.forumComments
    val conversationStore = stores.conversations
    val messageStore = stores.messages
    val attachmentRepository = stores.attachmentRepository
    val projectRepository = stores.projectRepository
    val vocabularyRepository = stores.vocabularies
    val sprintRepository = stores.sprints
    val statisticsRepository = stores.statistics
    val emailCodes = stores.emailCodes

    // Owner impersonation: whether this deployment has it at all, and the live
    // grants if it does. One instance, shared by the auth routes that arm and spend
    // a grant and every route that has to know a session was minted by one. The
    // grants are in memory, so a restart is an implicit "stop" for everybody — see
    // ProbeGrants, and OwnerImpersonation for why the gate travels with them.
    val ownerImpersonation = OwnerImpersonation(
        isEnabled = resolveOwnerImpersonationEnabled(),
        grants = ProbeGrants(),
    )
    if (ownerImpersonation.isEnabled) {
        // Said out loud, at INFO, because on a host where this switch is dashboard
        // state committed nowhere — Railway — this line is the only record inside
        // the app that the instance is armed. An instance where any address can be
        // worn should announce it in its own boot log.
        log.info(
            "Owner impersonation is ENABLED (LUNICLE_ENABLE_OWNER_IMPERSONATION): the instance owner " +
                "may sign in as any address, creating accounts as they go. Unset the variable to disable it.",
        )
    }
    // The plumbing every notification shares: who has an address, what the actor
    // is called, and send-or-log. One instance, handed to both notifiers — it is
    // stateless, so that is for this file's readability rather than a requirement.
    // Extracted by LNL-60 as the seam LNL-63 reuses; see EmailNotifier's preamble.
    // It also records the in-app twin of every notification (LNL-109) — hence the
    // store — which is why it is written even when `emailSender` is null: the bell
    // needs no mailbox.
    val notificationDispatcher = NotificationDispatcher(users, emailSender, notificationStore)
    val notifications = NotificationService(
        subscriptions = subscriptions,
        projects = projects,
        users = users,
        // For @mentions: the notifier resolves a typed name against the same
        // per-project membership the editor's autocomplete offered — which since
        // LNL-201 includes whoever owns the deployment, so the settings ride along.
        roles = roles,
        instanceSettings = instanceSettings,
        dispatch = notificationDispatcher,
        baseUrl = emailBaseUrl,
    )
    // The Messages tab's one notification. A second service rather than a wider
    // first one, for the reason EmailNotifier's preamble gives at length.
    val messageNotifications = MessageNotificationService(
        users = users,
        dispatch = notificationDispatcher,
        baseUrl = emailBaseUrl,
    )
    // Who can see a project, as a set — the forum's @ autocomplete, LNL-60's
    // recipient picker, and now LNL-63's send-time visibility check. Named here
    // rather than built inline at BoardDependencies because two things hold it,
    // and two of these would be two objects answering one question. See
    // ProjectAudience.
    val projectAudience = ProjectAudience(users, roles, instanceSettings)
    // The Discussion tab's two notifications. A third service rather than a wider
    // one, for the reason EmailNotifier's preamble gives — and the only one of the
    // three that takes a `ProjectAudience`, because a forum watcher's visibility
    // of the project is re-checked at send time. See ForumNotificationService.
    val forumNotifications = ForumNotificationService(
        subscriptions = subscriptions,
        audience = projectAudience,
        dispatch = notificationDispatcher,
        baseUrl = emailBaseUrl,
    )
    // One instance, handed to both the repository and BoardDependencies — the
    // same sharing `notifications` gets, for the same reason: a save records
    // through the repository, and a drag or an assignment records from its route,
    // because those two never pass through the repository at all. Two instances
    // would work and would be two half-histories waiting to diverge. See
    // IssueHistory.
    // The issue history's event store comes from the graph (SQLite table or Firestore
    // collection); everything else here is backend-agnostic orchestration over the
    // rebound interface-typed stores. One instance of the history, handed to both the
    // repository and BoardDependencies — a save records through the repository, a drag
    // or an assignment records from its route. See IssueHistory.
    // Sprints, versions, issues and projects join the five it already had (LNL-215):
    // the three new field events snapshot a sprint's or a version's NAME, and the
    // hierarchy and relation events snapshot the other issue's KEY. All four are
    // resolved at write time and frozen, for the reason a status's name already is.
    val issueHistory = IssueHistory(
        stores.issueEvents, statuses, labels, components, users,
        sprints = sprintRepository, versions = versions, issues = issues, projects = projects,
    )
    val issueRepository = IssueRepository(
        issues, comments, statuses, priorities, attachmentRepository, attachments, notifications, subscriptions,
        issueHistory, stores.issueRelations, stores.issueRelationKinds,
    )
    val access = AccessControl(roles, instanceSettings)
    // The discussion and Messages repositories — backend-agnostic rules over the
    // graph's forum/post/comment and conversation/message stores (rebound above), the
    // same on either backend.
    val forumRepository = ForumRepository(forumStore, attachmentRepository, attachments)
    val forumPostRepository =
        ForumPostRepository(forumPostStore, forumCommentStore, attachmentRepository, attachments)
    val conversationRepository =
        ConversationRepository(conversationStore, messageStore, attachmentRepository, attachments)

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
        forums = forumRepository,
        forumPosts = forumPostRepository,
        audience = projectAudience,
        // Private conversations. Instance-wide, so — alone in this bundle — it
        // reaches no project. See Conversations.sq.
        conversations = conversationRepository,
        labels = labels,
        components = components,
        statuses = statuses,
        priorities = priorities,
        resolutions = resolutions,
        versions = versions,
        // Both are the store.SprintStore seam now (LNL-122): the concrete SQLite
        // gateway does not implement it, the repository does, and `sprints` is only
        // ever read for forProject — which the repository delegates straight to the
        // gateway, so this is byte-for-byte what it was.
        sprints = sprintRepository,
        sprintRepository = sprintRepository,
        issues = issues,
        issueRelations = stores.issueRelations,
        issueRelationKinds = stores.issueRelationKinds,
        issueRepository = issueRepository,
        comments = comments,
        attachments = attachments,
        attachmentRepository = attachmentRepository,
        // Built here like everything else, and shared with McpTools through the
        // same object graph: the tool that mints a ticket and the route that
        // spends it must be looking at the same map, and two of these would be a
        // feature that silently never works.
        attachmentTickets = AttachmentTicketStore(),
        sessions = sessions,
        users = users,
        impersonation = ownerImpersonation,
        instanceSettings = instanceSettings,
        // Deploy-time configuration, for the admission options the settings dialog
        // renders. See InstanceIdentity.
        identity = instanceIdentity,
        // What the deployment is called when a brand dir names it. The title if the
        // manifest gives one, else the directory's own name, which is what an operator
        // recognises. Null for the default look. See LNL-195's Instance tab.
        brandName = brandInfo?.let { it.title ?: it.dir.name },
        subscriptions = subscriptions,
        reads = reads,
        notificationStore = notificationStore,
        notifications = notifications,
        messageNotifications = messageNotifications,
        forumNotifications = forumNotifications,
        history = issueHistory,
        statistics = statisticsRepository,
        // The same sender the notifier got, for the send_email MCP tool. Null
        // here is not a broken deployment: it is a server with no mail
        // configured, and the tool answers that it cannot send rather than
        // pretending it did. See McpTools.sendEmail.
        agentMail = emailSender,
    )

    val mcpDependencies = McpDependencies(
        clients = oauthClients,
        loginStates = oauthLoginStates,
        codes = oauthCodes,
        tokens = oauthTokens,
        sessions = sessions,
        users = users,
        impersonation = ownerImpersonation,
        config = oauthConfig,
        // The per-tier agent permission the five MCP gates read. The same object the
        // board routes hold, so an administrator's switch reaches the token path
        // within one request. See canUseMcp.
        instanceSettings = instanceSettings,
        // Read only by the Connections section's cookie path, and only to ask whether
        // an impersonation is still the caller's to hold (LNL-197). The same object the
        // board routes hold, for that same one-request reason.
        access = access,
    )

    // ── The one sweep that runs BEFORE this process serves anything (LUS-5) ──
    //
    // Every session an owner-impersonation grant minted, whatever the gate says.
    // Unconditional, which is the whole of the off-switch guarantee: the grants
    // that authorised these rows lived in the previous process's memory and died
    // with it, so every row this finds is orphaned by definition. Gating it on
    // `ownerImpersonation.isEnabled` would leave exactly the hole worth caring
    // about — turn the feature off while somebody is probing and their session
    // survives the restart as an ORDINARY session for the person they were
    // wearing, marker gone and nothing left to notice it. See ImpersonationConfig.
    //
    // Unconditional was never the part that was wrong. **Ordered** was. This used
    // to sit inside the launch{} below, behind `stores.migrate()`, while the
    // routing block was registered synchronously regardless — so between the port
    // binding and this line the server was serving with those sessions still live.
    // During that window `resolveCaller` does precisely what the design warns
    // against: with the gate now off it returns the caller as an ordinary session,
    // `isProbe` reads false, the marker leaves the UI and the e-mail guards go
    // inert, on a deployment whose configuration says the feature is off. On the
    // Firestore backend the migration it sat behind can wait several minutes for an
    // elected peer, and if that wedges the sweep never ran at all while the process
    // kept answering; Cloud Run's request-based CPU billing throttles background
    // coroutines between requests and stretches the window further.
    //
    // So it is hoisted out and blocks the module. It is one indexed delete on a
    // small collection, it does not need the new schema and it does not need to be
    // fast — the healthcheck argument the launch{} below rests on does not reach a
    // statement of this shape. Everything else stays where it was.
    runBlocking {
        val probes = sessions.deleteProbeSessions()
        if (probes > 0) {
            log.info("Removed $probes impersonation session(s) — their grants did not survive the restart")
        }
    }

    // Startup housekeeping, all of it in one launch{} rather than blocking the
    // module: a slow sweep must not hold up the port binding, because Railway's
    // healthcheck is watching and nothing here is a precondition for serving.
    //
    // The one thing that IS ordered: roles are seeded before anything can ask
    // whether someone holds one. In practice the first request cannot beat this
    // — but "in practice" is doing work in that sentence, so the seed goes
    // first inside the coroutine rather than being raced against the sweeps.
    launch {
        // Schema bring-up first, before anything reads or writes. On SQLite this is a
        // no-op — openDatabase already walked the schema — and on Firestore it is the
        // migration runner (single-writer election, checkpoint-per-step; see
        // FirestoreMigrationRunner), which must reach the build's target version before
        // this instance serves. Fatal if a peer migrator wedges, for the same reason
        // openDatabase is fatal on a bad schema: a server behind its build must not serve.
        stores.migrate()

        // All unconditional and all idempotent, which is what lets a fresh volume,
        // a purged one, and one that has been serving for a month all take the same
        // code path — and what makes an interrupted run a non-event. See
        // stampUserKinds, seatInstanceOwner and settleAdmissionPolicy.
        val stamped = stampUserKinds(users, instanceIdentity.domain)
        if (stamped > 0) log.info("Instance: re-derived the staff/member kind of $stamped account(s)")
        seatInstanceOwner(users, instanceSettings)?.let {
            log.info("Instance: seated user $it as the instance owner — nobody held it")
        }
        // Needs the identity rather than the accounts, so unlike the seat above it has
        // everything it needs at boot and nowhere else to be called from.
        settleAdmissionPolicy(instanceSettings, instanceIdentity)?.let {
            log.info(
                "Instance: admission settled to '${it.key}' — nothing was stored, and " +
                    "this deployment cannot admit anybody who can sign in",
            )
        }
        // After the owner is seated, and that ordering is load-bearing: a project with
        // no owner rung falls back to the instance owner's old preference, and on a
        // freshly migrated volume the seat above is what puts somebody there. Same
        // idempotence property as the two lines above, reached differently — see
        // copyBoardDisplayFromOwners.
        val settled = copyBoardDisplayFromOwners(projects, roles, users, uiSettings, instanceSettings)
        if (settled > 0) {
            log.info("Instance: settled the board display of $settled project(s) from their owners")
        }

        val removed = sessions.deleteExpired()
        if (removed > 0) log.info("Removed $removed expired session(s)")

        // The probe-session sweep used to be here. It now runs synchronously above,
        // ahead of the migration and of anything being served — see LUS-5 there.
        log.info("Sessions live: ${sessions.size()}")

        // The mailbox-proof codes, swept beside the sessions. Unlike that sweep
        // this one is purely about disk: EmailCodes.sq puts expiry in the WHERE
        // clause, so a missed sweep leaves litter rather than a usable code — the
        // same property the OAuth sweeps below have, and the reason both can
        // safely be startup-only.
        val expiredEmailCodes = emailCodes.deleteExpired()
        if (expiredEmailCodes > 0) log.info("Removed $expiredEmailCodes expired e-mail code(s)")
        val liveEmailCodes = emailCodes.size()
        if (liveEmailCodes > 0) log.info("E-mail codes outstanding: $liveEmailCodes")

        // The draft issues nobody is writing any more (LNL-183). "New issue"
        // creates its row before the editor is filled in — an inline image needs an
        // owner — and Cancel deletes it, but a closed tab, a crash or a request
        // that died in flight leaves it behind invisible and forever. They cost
        // little on their own; what they cost is a lie in the settings dialog, and
        // they hold attachments.
        //
        // Immediately BEFORE the attachment sweep, so a draft freed here has its
        // inline image collected on the same boot rather than sitting on the volume
        // until the next one.
        val abandonedDrafts = issues.sweepAbandonedDrafts(System.currentTimeMillis() - ABANDONED_DRAFT_AGE_MILLIS)
        if (abandonedDrafts > 0) log.info("Removed $abandonedDrafts abandoned draft issue(s)")

        // Collects the files behind cancelled drafts, half-failed writes, and
        // cascades that took an attachment's row but could not reach its bytes.
        // See AttachmentRepository.sweepOrphans.
        val swept = attachmentRepository.sweepOrphans()
        if (swept > 0) log.info("Swept $swept orphaned attachment file(s)")

        // The sweep's twin on the other side of the wire: that one reconciles the
        // volume with the rows, this one reconciles what documents say about an
        // attachment with what the server will actually do when the link is
        // clicked. Idempotent, so a repaired volume pays two LIKE scans and stops.
        // See AttachmentLinkRepair.
        AttachmentLinkRepair(
            attachments, issues, comments, forumPostStore, forumCommentStore, messageStore,
        ).run()

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

    // Close the datastore when Ktor shuts down. On SQLite this checkpoints the WAL
    // back into the database file and releases the lock — without it the volume keeps
    // a -wal alongside the .db and recovery is left to the next open. On Firestore it
    // shuts the client's gRPC channels down cleanly. See StoreGraph.close.
    monitor.subscribe(ApplicationStopping) {
        log.info("Closing the datastore ($backend)")
        stores.close()
    }

    // ── App-shell cache revalidation (LNL-142) ──────────────────────────────
    // web.js / styles.css keep stable filenames but change every deploy, and
    // were served with no validators — so a browser kept running the stale
    // bundle after a redeploy until a manual hard refresh. Serve them with a
    // content-hash ETag and `Cache-Control: no-cache`: the browser revalidates
    // on every load, gets a cheap 304 while the build is unchanged, and refetches
    // in full the instant a deploy changes the bytes. The hash is of the content,
    // so every instance of one build serves the same ETag — coherent under
    // horizontal scale (LNL-113). index.html gets `no-cache` too (below), so the
    // pointer to the bundle is never itself cached stale.
    fun readShellAsset(name: String): ByteArray? =
        if (webDistPath != null) File(webDistPath, name).takeIf { it.isFile }?.readBytes()
        else this::class.java.classLoader.getResourceAsStream("web/$name")?.use { it.readBytes() }

    fun etagOf(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return "\"" + digest.copyOf(8).joinToString("") { b -> ((b.toInt() and 0xff) + 0x100).toString(16).substring(1) } + "\""
    }

    // Precomputed once at boot: (bytes, etag, contentType), or null if absent.
    val shellAssets: Map<String, Triple<ByteArray, String, ContentType>> =
        listOf(
            "web.js" to ContentType.Text.JavaScript,
            "styles.css" to ContentType.Text.CSS,
        ).mapNotNull { (name, type) ->
            readShellAsset(name)?.let { name to Triple(it, etagOf(it), type) }
        }.toMap()

    routing {
        authRoutes(
            oauthConfig,
            sessions,
            users,
            ownerImpersonation,
            mentions = MentionRenamer(
                users, issues, comments, forumPostStore, forumCommentStore, messageStore,
            ),
            // The verified address change (LNL-71) and, once LNL-74 lands, the
            // e-mail sign-in both hang off these two.
            emailCodes = emailCodes,
            notifications = notificationDispatcher,
            // So every session response carries the instance-wide switches a client
            // has to know about before it draws anything — currently the
            // display-name one. See SessionState.isDisplayNameHidden.
            instanceSettings = instanceSettings,
            // What this deployment says about itself (LNL-192): the domain that
            // decides staff from member, and — separately — whether the Google
            // chooser is pinned to it. Unbranded ⇒ neither, and sign-in behaves
            // exactly as it did.
            identity = instanceIdentity,
            // Who may impersonate — the instance owner alone (LNL-197). The same
            // object every other gate reads, so a transfer of ownership takes effect
            // on the transferee's and the transferor's next request alike, and so a
            // grant armed a minute ago stops working the moment its owner stops
            // being one.
            access = access,
        )

        // Not part of authRoutes despite sharing its three dependencies: what a
        // user's shell looks like is not a fact about signing in. See
        // UiSettingsRoutes.
        uiSettingsRoutes(sessions, ownerImpersonation, uiSettings, access)

        // Lunicle as an authorization server, and the MCP endpoint it protects.
        // Both are deliberately unauthenticated at the route level — see
        // OAuthServer's preamble — while mcpApiRoutes below is the ordinary
        // cookie-authenticated view a human gets of the same machinery.
        oauthRoutes(mcpDependencies)
        mcpRoutes(mcpDependencies, McpTools(boardDependencies))
        mcpApiRoutes(mcpDependencies)

        boardRoutes(boardDependencies)

        // Branding, mounted only when configured. The /brand/* asset endpoints,
        // and — because index.html is static today — a templated "/" that splices
        // the favicon, the font stylesheet and the optional title into <head>.
        // Registered before the static block so the explicit index routes win over
        // its SPA `default("index.html")` fallback. Off ⇒ none of this exists and
        // the static block below serves the original page verbatim.
        if (brandInfo != null) {
            brandRoutes(brandInfo)
            val template = readIndexTemplate(webDistPath)
            if (template != null) {
                val branded = brandedIndexHtml(template, brandInfo)
                get("/") {
                    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                    call.respondText(branded, ContentType.Text.Html)
                }
                get("/index.html") {
                    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                    call.respondText(branded, ContentType.Text.Html)
                }
            }
        }

        // Explicit, revalidatable routes for the app-shell bundle + stylesheet,
        // registered before the static block so they win over it (LNL-142). Each
        // carries a content-hash ETag and `no-cache`, so a redeploy is always
        // picked up while an unchanged build answers If-None-Match with a 304.
        for ((name, asset) in shellAssets) {
            val (bytes, etag, contentType) = asset
            get("/$name") {
                call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                call.response.headers.append(HttpHeaders.ETag, etag)
                if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
                    call.respond(HttpStatusCode.NotModified)
                } else {
                    call.respondBytes(bytes, contentType)
                }
            }
        }

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
