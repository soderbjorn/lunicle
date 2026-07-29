/**
 * The sign-in endpoints.
 *
 * **Google** has a JS SDK. The browser opens the popup itself via
 * `initCodeClient`, gets a code in a callback, and POSTs it to
 * [ApiRoutes.AUTH_GOOGLE]. The server never sees the popup.
 *
 * Google refuses to be framed — verified, see docs/oauth-instructions.html — so
 * a popup is the only path.
 *
 * @see OAuthProviders
 * @see SessionStore
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.host
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.request.receive
import io.ktor.util.date.GMTDate
import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.ConfirmEmailRequest
import se.soderbjorn.lunicle.clientserver.EmailSignInRedeemRequest
import se.soderbjorn.lunicle.clientserver.EmailSignInRequest
import se.soderbjorn.lunicle.clientserver.GoogleCodeRequest
import se.soderbjorn.lunicle.clientserver.ImpersonateRequest
import se.soderbjorn.lunicle.clientserver.RequestEmailChangeRequest
import se.soderbjorn.lunicle.clientserver.SessionState
import se.soderbjorn.lunicle.clientserver.SetDisplayNameRequest
import se.soderbjorn.lunicle.clientserver.SetEmailRequest
import se.soderbjorn.lunicle.clientserver.SignedInUser
import se.soderbjorn.lunicle.clientserver.UserOption

private val logger = LoggerFactory.getLogger("AuthRoutes")

/**
 * This server's own origin, as the browser reached it.
 *
 * Google needs to be told where we are, and getting it wrong is a
 * `redirect_uri_mismatch` rather than anything self-explanatory. Derived from
 * the request rather than configured because it legitimately differs —
 * `http://localhost:8080` locally, `https://lunicle.lunamux.dev` deployed — and
 * a config value would be one more thing to set correctly in two places.
 *
 * `X-Forwarded-Proto` is honoured because Railway terminates TLS at its edge and
 * speaks plain HTTP to the container: without it every deployed request looks
 * like `http://` and Google rejects the exchange. That failure only ever appears
 * in production, which is the worst place to first meet it.
 *
 * ── Why this is `internal` rather than private ──────────────────────────────
 *
 * Because the authorization server needs the same answer, and there must be
 * exactly one function that computes it. [oauthRoutes] publishes this origin as
 * the `issuer` in its discovery metadata and binds it into every token as the
 * `resource`; an MCP client compares those byte for byte, and two functions that
 * agree today are two functions that can disagree after one edit.
 *
 * Framnaflow re-implements base-URL resolution in two files and recomposes
 * scheme + host + port to do it. Do not copy that — see the authority comment
 * below for the production-only failure that produces.
 */
internal fun ApplicationCall.serverOrigin(): String {
    // Scheme must come from the forwarded header: Railway terminates TLS at its
    // edge and speaks plain HTTP to the container, so request.local.scheme is
    // "http" on every deployed request and Google would reject the exchange
    // over a scheme mismatch.
    val forwardedProto = request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim()
    val scheme = forwardedProto?.takeIf { it.isNotBlank() } ?: request.local.scheme

    // The authority is taken verbatim from the request rather than rebuilt from
    // a host and a port, because rebuilding it is wrong in production and the
    // way it is wrong is invisible until you get there.
    //
    // Railway forwards to the container as plain HTTP on port 80 while
    // X-Forwarded-Proto says https. Composing scheme + host + port therefore
    // yields "https://lunicle.lunamux.dev:80" — an https URL carrying http's
    // default port. Both providers match redirect_uri by exact string, neither
    // registered a port, and both reject it. Locally the scheme and port agree,
    // so no local run can reproduce this.
    //
    // The Host header is already exactly what we want: the authority the client
    // used, carrying a port only when it is not the scheme's default —
    // "localhost:8080" in development, "lunicle.lunamux.dev" deployed. There is
    // nothing to compute.
    val authority = request.headers["X-Forwarded-Host"]?.substringBefore(',')?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: request.headers[HttpHeaders.Host]?.takeIf { it.isNotBlank() }
        ?: request.host()

    return "$scheme://$authority"
}

/** Read the caller's session cookie. */
private fun ApplicationCall.sessionId(): String? = request.cookies[SESSION_COOKIE]

/**
 * Attach a session cookie.
 *
 * `HttpOnly` so no script can read it — the session id is a bearer credential
 * and the browser is the only thing that needs it. `SameSite=None` because this
 * server is loaded in an iframe on lunamux.dev: the cookie rides requests made
 * from a *cross-site* context, and `Lax` — the browser default — would omit it,
 * signing the user out on every navigation for reasons that appear nowhere.
 * `None` demands `Secure`, which is why local development over plain HTTP gets
 * `Lax`: the browser rejects `None` without `Secure`, and rejecting our own
 * cookie would break the local loop entirely.
 *
 * ── `Partitioned` (CHIPS): required for the embed, and safe now ──────────────
 *
 * Inside the lunamux.dev embed this cookie is *third-party* (a different
 * registrable domain from the framing page), and a bare `SameSite=None` cookie is
 * dropped there by Chrome's third-party-cookie restrictions. `Partitioned` (CHIPS)
 * is the one thing that survives: it stores the session in a per-top-level-site jar
 * the browser will deliver inside that frame. It keys the embed's session to
 * lunamux.dev, so the embedded login is separate from the standalone
 * origin's one — the right trade for an embedded tracker.
 *
 * `Partitioned` was blamed and removed once, but the thing it actually broke was
 * GitHub's *popup*: GitHub set the cookie inside the popup's own top-level context,
 * a different partition than the frame that opened it, so the frame never saw it.
 * Google is not that shape — its authorization code is POSTed to `/api/auth/google`
 * by the frame itself, so the cookie is written in, and read from, the frame's own
 * partition. With GitHub sign-in gone, that mismatch cannot happen, and `Partitioned`
 * is simply correct: it makes the session hold both standalone and embedded.
 *
 * The one operational cost: flipping this attribute across deploys leaves a browser
 * holding two cookies of the same name in different jars — a partitioned one and an
 * unpartitioned one — and the server reads whichever comes first, which surfaces as
 * a session that randomly signs out. So this attribute is now fixed and must not be
 * toggled again; a browser that saw an earlier build clears its cookies once.
 *
 * `None`/`Partitioned` both demand `Secure`, which is why local development over
 * plain HTTP gets `Lax` instead: the browser rejects `None` without `Secure`, and
 * local HTTP is first-party anyway, so `Lax` is exactly right there.
 */
private fun ApplicationCall.setSessionCookie(id: String) {
    val isSecure = serverOrigin().startsWith("https://")
    response.headers.append(
        HttpHeaders.SetCookie,
        buildString {
            append("$SESSION_COOKIE=$id; Path=/; HttpOnly")
            append(if (isSecure) "; Secure; SameSite=None; Partitioned" else "; SameSite=Lax")
        },
    )
}

/** Expire the session cookie. */
private fun ApplicationCall.clearSessionCookie() {
    response.cookies.append(
        name = SESSION_COOKIE,
        value = "",
        encoding = CookieEncoding.RAW,
        expires = GMTDate.START,
        path = "/",
    )
}

/**
 * The caller's current [SessionState], given who (if anyone) they are and the
 * deployment's instance switches (LNL-115, LNL-137).
 *
 * The switches are passed in rather than read here because this builder is not
 * suspend and has no store — its two suspend callers read them once from
 * `instanceSettings` and hand the snapshot down, which keeps the one read per
 * request and this function a pure assembly of what it is given. See
 * [instanceSettingsOrDefault].
 */
private fun sessionStateFor(
    user: SignedInUser?,
    config: OAuthConfig,
    settings: se.soderbjorn.lunicle.store.InstanceSettings,
): SessionState =
    SessionState(
        user = user,
        isGoogleAvailable = config.google != null,
        googleClientId = config.google?.clientId,
        // Told to the client rather than compiled into the bundle, for the reason
        // the Google flag is: only the server knows which variables it was given,
        // and a picker must never render a method the server cannot perform.
        isEmailSignInAvailable = config.isEmailAvailable,
        // The deployment-wide gate. Reaches every client including the signed-out
        // one it is drawn for, which is why it rides here rather than only on the
        // admin-only AdminSettingsState. See SessionState.isSignInRequired.
        isSignInRequired = settings.requireSignIn,
        // The display-name switch. Reaches every client because the profile field it
        // hides is one every signed-in user has. See SessionState.isDisplayNameHidden.
        isDisplayNameHidden = settings.hideDisplayName,
    )

/**
 * The instance switches, read from the store, or their defaults when no store is
 * wired (the OAuth tests, which install these routes to exercise the exchange and
 * configure no instance settings).
 *
 * One helper so the three builders read the snapshot the same way and a null store
 * degrades to the pre-LNL-115 defaults — the app usable signed out, the display name
 * offered — rather than a crash. See InstanceSettingsStore.
 */
private suspend fun instanceSettingsOrDefault(
    instanceSettings: se.soderbjorn.lunicle.store.InstanceSettingsStore?,
): se.soderbjorn.lunicle.store.InstanceSettings =
    instanceSettings?.current() ?: se.soderbjorn.lunicle.store.InstanceSettings()

/**
 * The address this user is waiting to confirm, if any and if this deployment can
 * even mail one.
 *
 * Read on every session fetch rather than remembered anywhere, which is what
 * makes a pending change survive the profile dialog being closed — see
 * `SessionState.pendingEmail`. One indexed lookup on a table with at most one row
 * per user, on a query the session endpoint already pays for a user lookup in.
 */
private suspend fun pendingEmailFor(user: UserRecord?, emailCodes: EmailCodeService?): String? =
    if (user == null || emailCodes == null) null
    else emailCodes.pendingFor(user.id, EmailCodePurpose.EMAIL_CHANGE)

/**
 * The caller's current [SessionState], resolved through impersonation.
 *
 * The one builder every route that can *see* an impersonation uses. The plain
 * [sessionStateFor] above stays for the one route that cannot: a sign-out, which
 * produces no user at all and so has nothing to answer these questions about.
 *
 * The user list is fetched only for an admin, and reduced to [UserOption] — a
 * name and an id — before it leaves. `users.selectAll()` returns whole records
 * with emails on them; those must not cross the wire, and the narrowing happens
 * here rather than being left to the client.
 */
private suspend fun impersonationAwareState(
    caller: Caller,
    users: se.soderbjorn.lunicle.store.UserStore,
    config: OAuthConfig,
    emailCodes: EmailCodeService? = null,
    instanceSettings: se.soderbjorn.lunicle.store.InstanceSettingsStore? = null,
): SessionState {
    val base = sessionStateFor(
        caller.effective?.toSignedInUser(),
        config,
        instanceSettingsOrDefault(instanceSettings),
    ).copy(pendingEmail = pendingEmailFor(caller.effective, emailCodes))
    if (!caller.canImpersonate) return base
    val realId = caller.real?.id
    return base.copy(
        isImpersonating = caller.isImpersonating,
        canImpersonate = true,
        impersonatableUsers = users.selectAll().map {
            UserOption(id = it.id, name = it.resolvedName, isSelf = it.id == realId)
        },
    )
}

/**
 * The [SessionState] for an account that has just signed in.
 *
 * ── Why a fresh sign-in cannot use the plain builder ────────────────────────
 *
 * It used to, on the reasoning that a session one request old is definitionally
 * not impersonating anybody. True, and beside the point: [SessionState] carries
 * three impersonation fields, and only one of them is about *doing* it.
 * `canImpersonate` and `impersonatableUsers` are about being an admin, which a
 * returning admin is the instant the exchange finds their row — so answering
 * "no, and nobody" left an admin who had signed out and back in with the
 * Impersonate item gone from their account menu until they reloaded the page,
 * because nothing else re-fetches the session. That is LNL-42.
 *
 * Written out rather than routed through [impersonationAwareState] because there
 * is no [Caller] here to route: the session was minted a line ago and resolving
 * the cookie back out of the response to ask who it belongs to would be a round
 * trip to learn what this function was handed. `isImpersonating` stays false,
 * which is the part of the old reasoning that was always right.
 */
internal suspend fun freshSignInState(
    user: UserRecord,
    users: se.soderbjorn.lunicle.store.UserStore,
    config: OAuthConfig,
    instanceSettings: se.soderbjorn.lunicle.store.InstanceSettingsStore? = null,
): SessionState {
    val base = sessionStateFor(user.toSignedInUser(), config, instanceSettingsOrDefault(instanceSettings))
    if (!user.isInstanceAdmin) return base
    return base.copy(
        canImpersonate = true,
        impersonatableUsers = users.selectAll().map {
            UserOption(id = it.id, name = it.resolvedName, isSelf = it.id == user.id)
        },
    )
}

/**
 * Mount the sign-in routes.
 *
 * @param config which providers are live; an absent provider's endpoints 400
 *   rather than 404, because "not configured here" is a different fact from
 *   "no such route" and the difference matters when debugging a deploy.
 * @param sessions where sessions are kept — on the volume now, so a redeploy no
 *   longer signs anyone out.
 * @param users the users table. Every completed exchange goes through it: an
 *   identity becomes an account before it becomes a session, which is what
 *   gives the session a user id to point at.
 * @param impersonations who each session is acting as. Shared with the board
 *   routes rather than owned here — an impersonation started at these routes has
 *   to be visible to the ones that enforce permissions, or it would be a costume
 *   with nothing behind it. See Impersonations.
 * @param mentions carries written `@mentions` across a change of display name.
 *   Nullable and defaulted, so the OAuth tests — which install these routes to
 *   exercise the exchange and own no issues table — need say nothing about it. A
 *   null one means the rename simply does not follow, which is the behaviour
 *   before this existed, rather than a crash.
 * @param emailCodes the mailbox-proof lifecycle, or null when this deployment has
 *   no mail. Nullable and defaulted for [mentions]' reason — the OAuth tests
 *   install these routes to exercise the exchange and have no mail to configure —
 *   and a null one means the address-change endpoints answer "not configured
 *   here" rather than crashing. See EmailCodes.kt.
 * @param notifications used for exactly one message: telling an address it has
 *   been replaced. Nullable for the same reason, and its habit of swallowing send
 *   failures is right there — see the confirm endpoint.
 */
fun Route.authRoutes(
    config: OAuthConfig,
    sessions: se.soderbjorn.lunicle.store.SessionStore,
    users: se.soderbjorn.lunicle.store.UserStore,
    impersonations: Impersonations,
    httpClient: HttpClient = createProviderHttpClient(),
    mentions: MentionRenamer? = null,
    emailCodes: EmailCodeService? = null,
    notifications: NotificationDispatcher? = null,
    instanceSettings: se.soderbjorn.lunicle.store.InstanceSettingsStore? = null,
    // Optional Workspace domain a branded deployment pins Google sign-in to
    // (LNL-125). Null on a non-branded install ⇒ open chooser, no domain gate.
    googleHostedDomain: String? = null,
) {
    /**
     * How often one account may ask for a confirmation code.
     *
     * Per route-installation rather than global, which is the same thing here:
     * these routes are mounted once, at startup. Three in fifteen minutes matches
     * the code's own lifetime — a fourth request inside one code's life is either
     * a mistake or somebody using a verified sending domain to post mail at an
     * address they do not own, and neither is worth serving.
     */
    val emailChangeLimiter = RateLimiter(limit = 3, windowMillis = 15L * 60 * 1000)

    /**
     * How often one address may be mailed a sign-in code, and how often one client
     * may ask for any.
     *
     * One limiter serving both keys — see [RateLimiter.tryAcquire] — so a single
     * window and a single limit govern both questions. Five in fifteen minutes is
     * generous for a person who mistyped their address twice and worth nothing to
     * somebody using a verified sending domain to post mail at strangers.
     *
     * Slightly looser than [emailChangeLimiter] on purpose: that path is
     * authenticated, so there is a person behind every call and one bucket is
     * enough, while this one has to leave room for several people behind one
     * NAT'd client address.
     */
    val signInLimiter = RateLimiter(limit = 5, windowMillis = 15L * 60 * 1000)

    // Never 401s. "Signed out" is a state the view renders, not an error it
    // handles — and an endpoint the whole UI depends on should not have a
    // failure mode for the most common case.
    get(ApiRoutes.SESSION) {
        val caller = call.resolveCaller(sessions, users, impersonations)
        call.respond(impersonationAwareState(caller, users, config, emailCodes, instanceSettings))
    }

    post(ApiRoutes.SIGN_OUT) {
        // Drop the impersonation with the session. It is keyed by session id, and
        // leaving it behind would mean a signed-out session id still had an
        // opinion about who it was.
        impersonations.clear(call.sessionId())
        sessions.destroy(call.sessionId())
        call.clearSessionCookie()
        call.respond(sessionStateFor(null, config, instanceSettingsOrDefault(instanceSettings)))
    }

    /**
     * Become somebody else. Admin only.
     *
     * The authorization is on `caller.real`, never `caller.effective`. Using the
     * effective user would let an admin impersonate a user and then, as that
     * user, impersonate a *third* — the check would be asking permission of the
     * identity that was just borrowed.
     *
     * The 404-shaped 400 for an unknown id is deliberate in the same way the
     * board's 404s are: this route is admin-only, so there is nothing to withhold
     * from the caller — they can read the user list anyway. It is a plain "that
     * is not a user".
     */
    post(ApiRoutes.IMPERSONATE) {
        val caller = call.resolveCaller(sessions, users, impersonations)
        if (!caller.canImpersonate) {
            // 403 and not 404: the route's existence is not a secret, and an
            // admin debugging a failed impersonation deserves to know it was
            // refused rather than misrouted.
            call.respond(HttpStatusCode.Forbidden, "Only a system administrator may impersonate.")
            return@post
        }
        val request = runCatching { call.receive<ImpersonateRequest>() }.getOrNull()
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        // Resolved before touching the session so a bad id changes nothing. Null
        // userId is not an error — it is the request to become a signed-out visitor
        // (LNL-103) — so only a *named* id that matches no account is refused.
        val target = request.userId?.let { userId ->
            users.findById(userId) ?: run {
                call.respond(HttpStatusCode.BadRequest, "No such user.")
                return@post
            }
        }

        val sessionId = call.sessionId() ?: run {
            // Unreachable in practice — canImpersonate above required a session to
            // resolve a real user from. Handled rather than asserted because the
            // cost is three lines and the alternative is a crash on a path nobody
            // has a reproduction for.
            call.respond(HttpStatusCode.Forbidden, "Only a system administrator may impersonate.")
            return@post
        }
        when {
            target == null -> {
                impersonations.startSignedOut(sessionId)
                logger.info("Impersonation: ${caller.real?.resolvedName} is now acting as a signed-out visitor")
            }
            target.id == caller.real?.id -> {
                // Impersonating yourself is "stop", not a self-referential entry that
                // resolveCaller would then have to treat as a special case forever.
                impersonations.stop(sessionId)
            }
            else -> {
                impersonations.start(sessionId, target.id)
                logger.info("Impersonation: ${caller.real?.resolvedName} is now acting as ${target.resolvedName}")
            }
        }
        call.respond(impersonationAwareState(call.resolveCaller(sessions, users, impersonations), users, config, emailCodes, instanceSettings))
    }

    /**
     * Stop, and be yourself again.
     *
     * Gated on `canImpersonate` — the real user being an admin — for the reason
     * ApiRoutes.STOP_IMPERSONATING gives: the effective user is deliberately not
     * an admin while this is in force, so checking them would trap the caller in
     * the identity they borrowed.
     */
    post(ApiRoutes.STOP_IMPERSONATING) {
        val caller = call.resolveCaller(sessions, users, impersonations)
        if (!caller.canImpersonate) {
            call.respond(HttpStatusCode.Forbidden, "Only a system administrator may impersonate.")
            return@post
        }
        impersonations.stop(call.sessionId())
        logger.info("Impersonation: ${caller.real?.resolvedName} stopped impersonating")
        call.respond(impersonationAwareState(call.resolveCaller(sessions, users, impersonations), users, config, emailCodes, instanceSettings))
    }

    /**
     * Set or clear the caller's display-name override.
     *
     * Acts on the *effective* user, like the MCP toggle and every other
     * self-service setting — an admin impersonating someone edits that someone's
     * profile, which is consistent with wearing their face everywhere else. Signed
     * in is the whole check: this is your own name.
     *
     * The name is not only this user's label, it is how everyone else refers to
     * them: `@mentions` match on it. So the write is two writes — the column, and
     * then every mention that was already pointing here. See [MentionRenamer] for
     * why the text moves rather than the reference. It runs before the response so
     * the board the client re-fetches next is already consistent; a rename touches
     * only the rows that mention this one name, so it is not a scan worth
     * deferring.
     */
    post(ApiRoutes.USER_DISPLAY_NAME) {
        val caller = call.resolveCaller(sessions, users, impersonations)
        val user = caller.effective ?: run {
            call.respond(HttpStatusCode.Forbidden, "You must be signed in to change your profile.")
            return@post
        }
        val request = runCatching { call.receive<SetDisplayNameRequest>() }.getOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        // Read before the write: the resolved name is the override or, failing
        // that, the provider's, so clearing an override is a rename too.
        val previousName = user.resolvedName
        users.setDisplayName(user.id, request.displayName)
        users.findById(user.id)?.let { renamed ->
            mentions?.rename(user.id, previousName, renamed.resolvedName)
        }
        call.respond(impersonationAwareState(call.resolveCaller(sessions, users, impersonations), users, config, emailCodes, instanceSettings))
    }

    /**
     * Clear the caller's own e-mail. **Clearing only.**
     *
     * This route used to set an address as well, with no verification of any kind
     * — its own documentation said so — and that was an account-takeover
     * primitive waiting for anything to key on e-mail: claim a victim's address,
     * and their next sign-in resolves to your row. Setting now goes through
     * [ApiRoutes.USER_EMAIL_REQUEST] and a mailed code.
     *
     * Clearing survives here, immediate and unproved, because **giving a mailbox
     * up establishes nothing and so requires nothing**. You cannot take somebody
     * else's account by removing an address from your own, and demanding a code
     * to stop receiving mail would be ceremony charged to the one person it
     * cannot protect.
     *
     * A non-null address is *refused* rather than quietly ignored. An older
     * client still trying to set one has to be told, not silently obeyed and left
     * believing it worked.
     */
    post(ApiRoutes.USER_EMAIL) {
        val caller = call.resolveCaller(sessions, users, impersonations)
        val user = caller.effective ?: run {
            call.respond(HttpStatusCode.Forbidden, "You must be signed in to change your profile.")
            return@post
        }
        val request = runCatching { call.receive<SetEmailRequest>() }.getOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        if (request.email?.isNotBlank() == true) {
            call.respond(
                HttpStatusCode.BadRequest,
                "Setting an address needs a confirmation code. Ask for one first.",
            )
            return@post
        }
        // Cleared, so there is nothing left to have verified — setEmail forces the
        // flag off with the address, and the pending change goes with it: a code
        // in flight toward an address on an account that just gave up its address
        // is a confirmation of something nobody is waiting for.
        users.setEmail(user.id, null, isVerified = false)
        emailCodes?.cancelFor(user.id, EmailCodePurpose.EMAIL_CHANGE)
        call.respond(
            impersonationAwareState(
                call.resolveCaller(sessions, users, impersonations), users, config, emailCodes, instanceSettings,
            ),
        )
    }

    /**
     * Ask for a code that would let the caller attach [RequestEmailChangeRequest]'s
     * address to their account.
     *
     * Writes **nothing** to the account. The address is stored only as a pending
     * row in `email_codes`, under the address-change purpose, and that purpose is
     * load-bearing rather than decorative: this mails a code to an address the
     * caller has merely claimed, so if the sign-in endpoint would accept it, the
     * confirmation mail would be a way *into* the account it was being attached
     * to. See [EmailCodePurpose].
     */
    post(ApiRoutes.USER_EMAIL_REQUEST) {
        val caller = call.resolveCaller(sessions, users, impersonations)
        val user = caller.effective ?: run {
            call.respond(HttpStatusCode.Forbidden, "You must be signed in to change your profile.")
            return@post
        }
        // An admin wearing somebody's face has no business changing where that
        // person's mail goes — and, once e-mail is the account key, redirecting it
        // is redirecting the account. Consistent with how the profile dialog
        // treats other self-only actions, and stated here rather than inherited
        // from `caller.effective` by accident, which is what the old route did.
        if (caller.isImpersonating) {
            call.respond(
                HttpStatusCode.Forbidden,
                "You cannot change somebody else's e-mail address while impersonating them.",
            )
            return@post
        }
        val codes = emailCodes ?: run {
            call.respond(HttpStatusCode.BadRequest, "This server has no e-mail configured, so it cannot send a code.")
            return@post
        }
        val request = runCatching { call.receive<RequestEmailChangeRequest>() }.getOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        val address = request.email.trim()
        if (address.isBlank() || !isPlausibleEmail(address)) {
            call.respond(HttpStatusCode.BadRequest, "That does not look like an e-mail address.")
            return@post
        }

        // Keyed on the user and nothing else. This path is authenticated, so
        // there is a person behind every call and a per-user cooldown is the whole
        // requirement — unlike the sign-in endpoint, which is unauthenticated and
        // has to compose an address key with a client key. LNL-76 deliberately
        // limits nothing itself; the endpoint in front of it decides who may call.
        when (val decision = emailChangeLimiter.tryAcquire("email-change:${user.id}")) {
            is RateLimitDecision.Refused -> {
                // An honest 429 here, unlike the sign-in request endpoint: the
                // caller is already authenticated, so being told they are asking
                // too often reveals nothing they do not already know.
                call.respondRateLimited(decision, "Too many confirmation codes requested. Try again shortly.")
                return@post
            }
            RateLimitDecision.Allowed -> Unit
        }

        when (val outcome = codes.issue(address, EmailCodePurpose.EMAIL_CHANGE, userId = user.id)) {
            EmailCodeService.IssueOutcome.Sent -> Unit
            EmailCodeService.IssueOutcome.NotConfigured -> {
                call.respond(HttpStatusCode.BadRequest, "This server has no e-mail configured, so it cannot send a code.")
                return@post
            }
            // Surfaced rather than swallowed. A notification that fails to send is
            // a courtesy nobody was waiting for; this is a code somebody is
            // watching a spinner for, and "check your mail" over a mail that was
            // never sent is the worst possible answer. LNL-76 guarantees no live
            // code was left behind, so retrying is safe.
            is EmailCodeService.IssueOutcome.SendFailed -> {
                call.respond(HttpStatusCode.BadGateway, outcome.message)
                return@post
            }
        }
        call.respond(
            impersonationAwareState(
                call.resolveCaller(sessions, users, impersonations), users, config, emailCodes, instanceSettings,
            ),
        )
    }

    /**
     * Spend a code and, only then, write the address.
     *
     * The address comes from the pending row rather than from the request body,
     * so what gets written is what was actually mailed to. A body-supplied address
     * would be a second chance for the two to disagree, on the one write that
     * decides identity.
     */
    post(ApiRoutes.USER_EMAIL_CONFIRM) {
        val caller = call.resolveCaller(sessions, users, impersonations)
        val user = caller.effective ?: run {
            call.respond(HttpStatusCode.Forbidden, "You must be signed in to change your profile.")
            return@post
        }
        if (caller.isImpersonating) {
            call.respond(
                HttpStatusCode.Forbidden,
                "You cannot change somebody else's e-mail address while impersonating them.",
            )
            return@post
        }
        val codes = emailCodes ?: run {
            call.respond(HttpStatusCode.BadRequest, "This server has no e-mail configured.")
            return@post
        }
        val request = runCatching { call.receive<ConfirmEmailRequest>() }.getOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        val pending = codes.pendingFor(user.id, EmailCodePurpose.EMAIL_CHANGE) ?: run {
            call.respond(HttpStatusCode.BadRequest, "There is no address waiting to be confirmed. Ask for a code first.")
            return@post
        }

        val redemption = codes.redeem(pending, EmailCodePurpose.EMAIL_CHANGE, request.code.trim())
        if (redemption !is EmailCodeRedemption.Redeemed) {
            // One message for every refusal, which is LNL-76's rule reaching the
            // screen: wrong, expired, exhausted and absent are distinct in the log
            // and identical here.
            call.respond(HttpStatusCode.BadRequest, "That code is not right, or it has expired. Ask for a new one.")
            return@post
        }
        // The proof named a user when it was issued. Re-checking it costs a
        // comparison and closes the case where a session changed hands between the
        // request and the confirm.
        if (redemption.userId != user.id) {
            call.respond(HttpStatusCode.BadRequest, "That code was not issued for this account.")
            return@post
        }

        val previous = user.email
        // The address is unique across accounts since LNL-73, so this write can
        // genuinely fail — somebody else proved the same mailbox in the fifteen
        // minutes since the code was issued, or has held it all along. Caught
        // here rather than left to become a 500, and answered without confirming
        // *which* of those it was: an authenticated user is not owed the fact that
        // a given address is already on this instance, and saying so would make
        // this endpoint an account-existence oracle for anyone with a login.
        val written = runCatching { users.setEmail(user.id, redemption.address, isVerified = true) }
        if (written.isFailure) {
            logger.warn("User ${user.id} confirmed an address that could not be written: ${written.exceptionOrNull()?.message}")
            call.respond(HttpStatusCode.Conflict, "That address cannot be attached to this account.")
            return@post
        }
        logger.info("User ${user.id} confirmed a new e-mail address")

        // The old address is told, so a change the user did not make is visible to
        // them — and after the write it is the only address left that reaches
        // them. Through the dispatcher, whose swallowing of failures is right
        // here for once: this is a courtesy on top of a write that has already
        // succeeded, and a bounced notice must not make a confirmed address
        // un-confirm itself.
        if (previous != null && previous != redemption.address) {
            notifications?.send(
                recipient = EmailRecipient(userId = user.id, email = previous, name = user.resolvedName),
                subject = "Your Lunicle e-mail address has changed",
                html = emailChangedBody(user.resolvedName, redemption.address),
            )
        }
        call.respond(
            impersonationAwareState(
                call.resolveCaller(sessions, users, impersonations), users, config, emailCodes, instanceSettings,
            ),
        )
    }

    /** Drop a pending address change, so a mistyped address is not a fifteen-minute wait. */
    post(ApiRoutes.USER_EMAIL_CANCEL) {
        val caller = call.resolveCaller(sessions, users, impersonations)
        val user = caller.effective ?: run {
            call.respond(HttpStatusCode.Forbidden, "You must be signed in to change your profile.")
            return@post
        }
        emailCodes?.cancelFor(user.id, EmailCodePurpose.EMAIL_CHANGE)
        call.respond(
            impersonationAwareState(
                call.resolveCaller(sessions, users, impersonations), users, config, emailCodes, instanceSettings,
            ),
        )
    }

    /**
     * Mail a sign-in code. **Unauthenticated, and deliberately uninformative.**
     *
     * ── Why this always answers the same thing ──────────────────────────────
     *
     * `204`, no body, whatever happens: address unknown, address known, mail sent,
     * mail refused. Every one of those is the same response, because the
     * alternative is an account-existence oracle that anybody can query by typing.
     * "That address has no account here" is a fact about somebody else, offered to
     * an unauthenticated stranger, on an instance whose projects may be private.
     *
     * The send failure is folded into that silence too, which is a real cost and
     * worth naming: a user whose code never arrives is told nothing distinguishable
     * from a user who mistyped their own address. LNL-76 guarantees no live code
     * was stored, so retrying is safe and works; the operator sees the failure in
     * the log. That is the trade, and it goes the other way from the *authenticated*
     * address-change endpoint above, which reports send failures precisely because
     * it already knows who is asking.
     *
     * The one thing that does leak is a `429`, and it leaks only about the caller:
     * you have been asking too often. That is not a fact about anyone else.
     *
     * ── Registration happens here, and that is the design ──────────────────
     *
     * Not on this endpoint — nothing is created until a code is redeemed — but any
     * address that can receive mail can end up with an account. That is exactly as
     * open as Google sign-in already is, so it is consistent rather than new. On a
     * genuinely fresh instance it does mean the first person to redeem a code
     * becomes the system administrator, which is the same rule Google sign-in has
     * always had; see `Users.sq`.
     */
    post(ApiRoutes.AUTH_EMAIL_REQUEST) {
        // Read and normalized before anything else, because every branch from here
        // returns the same thing and the parsing is the only part that can be
        // told apart from outside.
        val request = runCatching { call.receive<EmailSignInRequest>() }.getOrNull()
        val address = normalizeEmail(request?.email)
        if (address == null || !isPlausibleEmail(address)) {
            // The one honest refusal: a malformed address is a fact about the
            // request, not about who has an account.
            call.respond(HttpStatusCode.BadRequest, "That does not look like an e-mail address.")
            return@post
        }

        // Both keys, and refused if either bucket is empty. Keyed on the client
        // alone, one host walks a list of many target addresses at full speed;
        // keyed on the address alone, a botnet hammers one. LNL-72 exists for
        // exactly this endpoint.
        val decision = signInLimiter.tryAcquire(
            "signin-address:$address",
            "signin-client:${call.clientIdentity()}",
        )
        if (decision is RateLimitDecision.Refused) {
            call.respondRateLimited(decision, "Too many sign-in codes requested. Try again shortly.")
            return@post
        }

        // Only actually mint a sign-in code when e-mail sign-in is on. When it has
        // been withdrawn (LUNICLE_EMAIL_SIGN_IN=off) mail is still configured for
        // notifications, so `emailCodes` is live and serving address-change codes —
        // but issuing a *sign-in* code would reopen the door this deploy closed.
        // The response stays 204 either way: the silence this endpoint owes an
        // unauthenticated caller must not grow a new crack that says "e-mail
        // sign-in is off here". Enforcement is at the redeem endpoint, which is
        // mid-flow and can speak plainly. The outcome is logged and discarded; see
        // this route's doc for the rest of why the caller is told nothing.
        if (config.isEmailAvailable) emailCodes?.issue(address, EmailCodePurpose.SIGN_IN)
        call.respond(HttpStatusCode.NoContent)
    }

    /**
     * Spend a sign-in code: find-or-create the account, and mint a session.
     *
     * The e-mail twin of the Google branch below, and deliberately the same three
     * lines at the end — `sessions.create`, `setSessionCookie`, `freshSignInState`
     * — because everything downstream of a session cookie is already
     * provider-blind. The OAuth machinery never asks which provider a user came
     * from; it asks whether the cookie resolves to one. See OAuthServer.
     *
     * The purpose parameter is what stops an address-change code (LNL-71) being
     * redeemed here. Without it, a confirmation mail sent to an address somebody
     * merely *claimed* would be a way into the account it was being attached to.
     */
    post(ApiRoutes.AUTH_EMAIL_REDEEM) {
        // E-mail sign-in can be switched off (LUNICLE_EMAIL_SIGN_IN=off) while mail
        // stays on for notifications, so this is not the same question as "is a
        // transport configured": a live `emailCodes` may still be serving
        // address-change codes. Refuse plainly rather than checking the code — a
        // redeem is authenticated by the code itself and the caller is mid-sign-in,
        // so naming the reason is a help, not a leak, and it reads the same as the
        // not-configured refusal just below.
        if (!config.isEmailAvailable) {
            call.respond(HttpStatusCode.BadRequest, "E-mail sign-in is not enabled on this server.")
            return@post
        }
        val codes = emailCodes ?: run {
            call.respond(HttpStatusCode.BadRequest, "E-mail sign-in is not configured on this server.")
            return@post
        }
        val request = runCatching { call.receive<EmailSignInRedeemRequest>() }.getOrNull()
        val address = normalizeEmail(request?.email)
        if (request == null || address == null) {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }

        val redemption = codes.redeem(address, EmailCodePurpose.SIGN_IN, request.code.trim())
        if (redemption !is EmailCodeRedemption.Redeemed) {
            // One message for every refusal — wrong, expired, exhausted, never
            // issued — which is also what keeps this from becoming the oracle the
            // request endpoint refuses to be. A distinct "no code was issued for
            // that address" would give away everything the silence above bought.
            call.respond(HttpStatusCode.BadRequest, "That code is not right, or it has expired. Ask for a new one.")
            return@post
        }

        // Find-or-create on the proved address. A returning Google user lands in
        // the row they already have rather than a second one — that is LNL-73's
        // re-key doing its job, and without it this endpoint would fragment
        // exactly the people it is meant to let back in.
        //
        // `provider`/`provider_id` are only consulted when no account holds the
        // address, so they name how the row came to exist. The address is its own
        // provider id: it is stable, it is unique, and there is no third party to
        // ask for anything better.
        val user = users.upsert(
            ProviderIdentity(
                provider = AuthProvider.EMAIL,
                providerId = redemption.address,
                providerName = redemption.address.substringBefore('@'),
                // Non-null means proved, which a redeemed code is — see
                // ProviderIdentity.email. This is what sets `email_verified`.
                email = redemption.address,
            ),
            // Derived here rather than in the store, from the same function the
            // startup stamp uses — see UserKind.forEmail. A code sign-in proves the
            // address, so it is exactly as good a basis for the staff/member answer
            // as Google's is.
            kind = UserKind.forEmail(redemption.address, googleHostedDomain),
        )
        call.setSessionCookie(sessions.create(user.id))
        logger.info("Signed in with an e-mail code: ${user.resolvedName} (user ${user.id})")
        call.respond(freshSignInState(user, users, config, instanceSettings))
    }

    post(ApiRoutes.AUTH_GOOGLE) {
        val credentials = config.google
        if (credentials == null) {
            call.respond(HttpStatusCode.BadRequest, "Google sign-in is not configured on this server.")
            return@post
        }
        val request = runCatching { call.receive<GoogleCodeRequest>() }.getOrNull()
        if (request == null || request.code.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing authorization code.")
            return@post
        }
        try {
            // The origin, not a path — see exchangeGoogleCode's docs. The optional
            // hosted-domain gate rides along; null leaves the flow unchanged.
            val identity = exchangeGoogleCode(
                httpClient, credentials, request.code, call.serverOrigin(), googleHostedDomain,
            )
            // Find-or-create the account, then point a session at it. On a
            // returning user this finds the row written the first time — which
            // is what reunites them with their issues, and their admin bit.
            val user = users.upsert(identity, UserKind.forEmail(identity.email, googleHostedDomain))
            call.setSessionCookie(sessions.create(user.id))
            logger.info("Signed in via Google: ${user.resolvedName} (user ${user.id})")
            call.respond(freshSignInState(user, users, config, instanceSettings))
        } catch (failure: SignInFailure) {
            call.respond(HttpStatusCode.BadGateway, failure.userMessage)
        }
    }
}

/**
 * One address, spelled one way.
 *
 * **Trim and lowercase. That is the whole policy**, and every write path and
 * every lookup goes through this one function — which is the point of it being a
 * function at all. Before LNL-73 nothing lowercased anywhere and the two write
 * paths already disagreed: the OAuth path stored Google's value verbatim while
 * `setEmail` trimmed only, so `Alice@X.com` and `alice@x.com` were two rows and
 * would have been two accounts.
 *
 * ── What this deliberately does NOT do ──────────────────────────────────────
 *
 * Canonicalize Gmail's dots or `+tags`. It is tempting, it is provider-specific
 * behaviour, and it is simply *wrong* for hosts that treat those as significant —
 * where `a.b@host` and `ab@host` are two different people's mailboxes. The
 * failure it produces is the worst one available here: a user types their own
 * address correctly and is signed into somebody else's account. Nothing is worth
 * that, least of all tidiness.
 *
 * Blank becomes null for [UserStore.setEmail]'s reason: an address of "" is not
 * an address, and storing one would make `email IS NOT NULL` — which the
 * notification queries lean on — answer true for someone with nowhere to receive
 * mail.
 *
 * Separate from [isPlausibleEmail], which stays as forgiving as it is: shape and
 * spelling are different questions, and folding them together would mean every
 * caller that wanted one had to accept the other's opinion.
 */
internal fun normalizeEmail(candidate: String?): String? =
    candidate?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

/**
 * Is this the shape of an e-mail address?
 *
 * Deliberately forgiving, and it has to be: the only address validator that is
 * ever *right* is sending a mail and seeing if it arrives, and the classic
 * "correct" RFC 5322 regex rejects real addresses while accepting nonsense. This
 * checks the one thing worth checking before we try to send — a local part, an
 * `@`, and a domain with a dot — and leaves the rest to the confirmation step,
 * which since LNL-71 actually exists. Its job is to catch a typo and a pasted
 * sentence, not to be a parser.
 */
internal fun isPlausibleEmail(candidate: String): Boolean {
    // No whitespace anywhere — a pasted sentence is the common bad input.
    if (candidate.any { it.isWhitespace() }) return false
    val at = candidate.indexOf('@')
    // Exactly one '@', with something before it.
    if (at <= 0 || at != candidate.lastIndexOf('@')) return false
    val domain = candidate.substring(at + 1)
    // A domain with a dot, and the dot neither leads nor trails it — so a bare
    // "nodot" and a trailing-dot "ends.with." are both out, while "sub.co.uk" is
    // in. This is a plausibility check, not a parser; see the doc above.
    return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')
}
