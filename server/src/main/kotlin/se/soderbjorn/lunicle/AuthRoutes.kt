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
import se.soderbjorn.lunicle.clientserver.AddressPreview
import se.soderbjorn.lunicle.clientserver.AddressStanding
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.ConfirmEmailRequest
import se.soderbjorn.lunicle.clientserver.EmailSignInRedeemRequest
import se.soderbjorn.lunicle.clientserver.EmailSignInRequest
import se.soderbjorn.lunicle.clientserver.GoogleCodeRequest
import se.soderbjorn.lunicle.clientserver.ImpersonateRequest
import se.soderbjorn.lunicle.clientserver.ImpersonationTarget
import se.soderbjorn.lunicle.clientserver.RequestEmailChangeRequest
import se.soderbjorn.lunicle.clientserver.SessionState
import se.soderbjorn.lunicle.clientserver.SetDisplayNameRequest
import se.soderbjorn.lunicle.clientserver.SetEmailRequest
import se.soderbjorn.lunicle.clientserver.SignedInUser

private val logger = LoggerFactory.getLogger("AuthRoutes")

/**
 * What the four impersonation refusals say, in one place.
 *
 * One string because they are one fact, and because the fact changed with LNL-197:
 * these used to say "Only a system administrator may impersonate", which was true and
 * is now wrong by exactly the width of the administrator rung. Four copies would have
 * been four chances to leave one of them describing the old rule to somebody trying to
 * work out why they were refused.
 */
private const val IMPERSONATION_REFUSAL: String = "Only the instance owner may impersonate."

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
 *
 * ── Why `isSysAdmin` is rewritten here (LNL-198) ────────────────────────────
 *
 * [UserRecord.toSignedInUser] answers it off `users.instance_role`, which **cannot see
 * ownership**: the owner is `instance_settings.owner_user_id`, and 33.sqm deliberately
 * leaves the owner's `instance_role` null rather than stating one authority twice. So the
 * record alone says "no" about the one person who most certainly does run the instance —
 * and `SettingsPane.renderTabs` gates all three instance tabs on this flag, so an owner
 * who does not separately hold the administrator row finds them missing.
 *
 * Two ways to be in that state, and the second is why this is fixed here rather than
 * papered over at the handover: **every migrated volume** is in it already (33.sqm nulls
 * the column for everybody and seats the owner from `is_sys_admin`), and LNL-198's
 * handover seats an owner who never held the flag. The other repair — writing
 * `instance_role = 'admin'` onto the owner as well — is the one 33.sqm's own comment
 * rejects, on the grounds that two records of a single authority are two records that can
 * disagree after a transfer.
 *
 * So the flag means what its documentation on [SignedInUser] and
 * [se.soderbjorn.lunicle.clientserver.AdminUser] already claims: **runs the instance** —
 * an administrator, or the owner. Fixed in one place, at the seam that already holds the
 * settings snapshot.
 */
private fun sessionStateFor(
    user: SignedInUser?,
    config: OAuthConfig,
    settings: se.soderbjorn.lunicle.store.InstanceSettings,
): SessionState =
    SessionState(
        // Never widened for an impersonated caller: `user` is the *effective* one, so an
        // owner wearing somebody else's face is compared as that somebody and comes back
        // false. That is the whole point of impersonation and is the answer this has to
        // keep giving — see impersonationAwareState.
        user = user?.let { it.copy(isSysAdmin = it.isSysAdmin || settings.ownerUserId == it.id) },
        isGoogleAvailable = config.google != null,
        googleClientId = config.google?.clientId,
        // Told to the client rather than compiled into the bundle, for the reason
        // the Google flag is: only the server knows which variables it was given,
        // and a picker must never render a method the server cannot perform.
        isEmailSignInAvailable = config.isEmailAvailable,
        // Retired on the wire (LNL-192): the require-sign-in switch is gone, and
        // what it said is now a per-project guest audience row. Sent false rather
        // than removed so a browser running the previous bundle keeps deserialising
        // this state — the landing gate simply never appears, which is exactly what
        // an instance with no blanket means. See InstanceSettingKey.
        isSignInRequired = false,
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
 * makes a pending change survive the settings pane being closed — see
 * `SessionState.pendingEmail`. One indexed lookup on a table with at most one row
 * per user, on a query the session endpoint already pays for a user lookup in.
 */
private suspend fun pendingEmailFor(user: UserRecord?, emailCodes: EmailCodeService?): String? =
    if (user == null || emailCodes == null) null
    else emailCodes.pendingFor(user.id, EmailCodePurpose.EMAIL_CHANGE)

/**
 * Every address on the instance, each with what a sign-in would make of it.
 *
 * ── Addresses, and only the ones that exist ─────────────────────────────────
 *
 * The list the account menu renders (LNL-197). One row per `users` row that carries
 * an address; an account with none has no row here and cannot be impersonated, which
 * is the accepted cost of keying on the thing the permission model keys on. The rows
 * are sorted by address so the menu is a list somebody can scan, rather than by
 * whatever order `selectAll` happens to return.
 *
 * The addresses themselves cross the wire, which they do nowhere else. That is the
 * narrowing from "any administrator" to "the owner" paying for itself: the one person
 * who sees this list can read the account directory anyway. See `SessionState`.
 *
 * @param domain the deployment's own domain, for the staff/member split. Null — an
 *   unbranded install — makes every address a member, exactly as sign-in would.
 */
private suspend fun impersonationTargets(
    users: se.soderbjorn.lunicle.store.UserStore,
    realId: Long?,
    domain: String?,
): List<ImpersonationTarget> =
    users.selectAll()
        .mapNotNull { user -> user.email?.takeIf { it.isNotBlank() }?.let { user to it } }
        .sortedBy { (_, email) -> email.lowercase() }
        .map { (user, email) ->
            ImpersonationTarget(
                email = email,
                standing = when {
                    user.id == realId -> AddressStanding.SELF
                    // Never having arrived outranks the tier as the thing to report: the
                    // tier is derivable from the address on screen, and this is not.
                    !user.hasSignedIn -> AddressStanding.NOT_SIGNED_IN
                    // Re-derived rather than read from `users.kind`, so a row the startup
                    // stamp has not reached yet still reads correctly here. The rule is the
                    // one sign-in uses, from the same function.
                    UserKind.forEmail(email, domain) == UserKind.STAFF -> AddressStanding.STAFF
                    else -> AddressStanding.MEMBER
                },
            )
        }

/**
 * What an address resolves to on this deployment, server-side.
 *
 * The one answer both impersonation paths take: the preview route reports it, and the
 * start route acts on it. Two implementations would be two opinions, and the whole
 * promise of `Any address…` is that the sentence you were shown describes the thing
 * you then became.
 *
 * @property account the `users` row this address already has, or null for none. Null is
 *   the interesting case: it is the first-time arrival, and it is what makes
 *   [Impersonation.AsAddress] necessary.
 * @property kind staff or member, derived from the deployment's domain by the same
 *   function sign-in uses.
 */
private data class AddressResolution(
    val email: String,
    val account: UserRecord?,
    val kind: UserKind,
    val standing: AddressStanding,
    val summary: String,
)

/**
 * Resolve [email] the way a sign-in would, and say so in a sentence.
 *
 * ── Reads only ──────────────────────────────────────────────────────────────
 *
 * Nothing here writes. That is the ticket's central property and it is a property of
 * this function: the preview route calls it and responds, and the start route calls it
 * and puts the answer in memory. No `users` row is inserted, no `added_at` is stamped,
 * and the address appears in no project's People list — so an address previewed and
 * abandoned leaves the deployment exactly as it was.
 *
 * The row is found by scanning `selectAll()` rather than through a store lookup by
 * address, because there is no such lookup on [se.soderbjorn.lunicle.store.UserStore]
 * and adding one would mean two backends and a contract test to answer a question asked
 * on one owner-only path. `users` is a table an instance holds in memory — the startup
 * kind stamp already walks all of it on every boot — so this is the same trade made
 * there. Matching is case-insensitive: addresses are normalised on the way in (see
 * [normalizeEmail]) but rows written before that was true may not be.
 *
 * @param roles read for one number — how many boards the address holds a row on — or
 *   null to leave that out of the sentence. Audience rows are deliberately not counted:
 *   they are a statement about everybody like this address, not about the address, and
 *   folding the two together would make the number impossible to act on.
 */
private suspend fun resolveAddress(
    email: String,
    users: se.soderbjorn.lunicle.store.UserStore,
    identity: InstanceIdentity,
    settings: se.soderbjorn.lunicle.store.InstanceSettings,
    roles: se.soderbjorn.lunicle.store.RoleStore?,
    realId: Long?,
): AddressResolution {
    val account = users.selectAll().firstOrNull { it.email.equals(email, ignoreCase = true) }
    val kind = UserKind.forEmail(email, identity.domain)
    val tier = if (kind == UserKind.STAFF) "staff" else "a member"
    val grants = account?.let { row -> roles?.rolesForUser(row.id)?.size }
    val rowsPhrase = when (grants) {
        null -> ""
        0 -> " It holds no board of its own, so what it reaches is whatever the audiences give it."
        1 -> " It holds a row on 1 board, plus whatever the audiences give it."
        else -> " It holds rows on $grants boards, plus whatever the audiences give it."
    }
    val standing = when {
        account != null && account.id == realId -> AddressStanding.SELF
        account == null -> AddressStanding.NO_ACCOUNT
        !account.hasSignedIn -> AddressStanding.NOT_SIGNED_IN
        kind == UserKind.STAFF -> AddressStanding.STAFF
        else -> AddressStanding.MEMBER
    }
    val summary = when (standing) {
        AddressStanding.SELF -> "This is your own address — you already see what it sees."
        AddressStanding.NO_ACCOUNT -> {
            // The only state where admission is worth mentioning: it is asked once, when a
            // row would be created, so it can only ever refuse an address that has none.
            // See AdmissionPolicy.admitsNewAccount.
            val admitted = settings.admission.admitsNewAccount(email, identity)
            "No account here. A first sign-in would create one as $tier" +
                if (admitted) "." else ", but admission is set to “${settings.admission.label}”, so it could not sign in at all."
        }
        AddressStanding.NOT_SIGNED_IN ->
            "Added as ${account?.resolvedName}, and has never signed in. It would arrive as $tier.$rowsPhrase"
        AddressStanding.STAFF, AddressStanding.MEMBER ->
            "Signs in as ${account?.resolvedName} — $tier" +
                (if (account?.isInstanceAdmin == true) ", and administers this instance" else "") +
                ".$rowsPhrase"
    }
    return AddressResolution(email = email, account = account, kind = kind, standing = standing, summary = summary)
}

/**
 * The caller's current [SessionState], resolved through impersonation.
 *
 * The one builder every route that can *see* an impersonation uses. The plain
 * [sessionStateFor] above stays for the one route that cannot: a sign-out, which
 * produces no user at all and so has nothing to answer these questions about.
 *
 * The address list is fetched only for the owner — see [impersonationTargets], which
 * is also where the narrowing from whole [UserRecord]s happens rather than it being
 * left to the client.
 */
private suspend fun impersonationAwareState(
    caller: Caller,
    users: se.soderbjorn.lunicle.store.UserStore,
    config: OAuthConfig,
    emailCodes: EmailCodeService? = null,
    instanceSettings: se.soderbjorn.lunicle.store.InstanceSettingsStore? = null,
    domain: String? = null,
): SessionState {
    val base = sessionStateFor(
        caller.effective?.toSignedInUser(),
        config,
        instanceSettingsOrDefault(instanceSettings),
    ).copy(pendingEmail = pendingEmailFor(caller.effective, emailCodes))
    if (!caller.canImpersonate) return base
    return base.copy(
        isImpersonating = caller.isImpersonating,
        canImpersonate = true,
        impersonatableAddresses = impersonationTargets(users, caller.real?.id, domain),
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
 * `canImpersonate` and `impersonatableAddresses` are about *being entitled*, which a
 * returning owner is the instant the exchange finds their row — so answering
 * "no, and nobody" left them signed out and back in with the Impersonate item gone
 * from their account menu until they reloaded the page, because nothing else
 * re-fetches the session. That is LNL-42.
 *
 * Written out rather than routed through [impersonationAwareState] because there
 * is no [Caller] here to route: the session was minted a line ago and resolving
 * the cookie back out of the response to ask who it belongs to would be a round
 * trip to learn what this function was handed. `isImpersonating` stays false,
 * which is the part of the old reasoning that was always right.
 *
 * The entitlement is **ownership** since LNL-197, so it is read from the settings
 * rather than off the record: `instance_settings.owner_user_id` is the only place
 * ownership lives, and no [UserRecord] carries it. A null settings store — the OAuth
 * tests, which configure none — therefore reports no owner and so no entitlement,
 * which is the safe direction and matches what those tests assert about the exchange.
 */
internal suspend fun freshSignInState(
    user: UserRecord,
    users: se.soderbjorn.lunicle.store.UserStore,
    config: OAuthConfig,
    instanceSettings: se.soderbjorn.lunicle.store.InstanceSettingsStore? = null,
    domain: String? = null,
): SessionState {
    val settings = instanceSettingsOrDefault(instanceSettings)
    val base = sessionStateFor(user.toSignedInUser(), config, settings)
    if (settings.ownerUserId != user.id) return base
    return base.copy(
        canImpersonate = true,
        impersonatableAddresses = impersonationTargets(users, user.id, domain),
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
 * @param access the permission oracle, asked one question here: does this session's
 *   real user own the instance, and so may they impersonate (LNL-197)? Nullable for
 *   [mentions]' reason, and a null one answers no — so the impersonation routes 403
 *   and no account menu offers the item, which is the safe direction for a gate.
 * @param roles the grants table, read by the address preview alone, to say how many
 *   boards an address already holds a row on. Nullable and defaulted like the rest:
 *   without it the preview simply says less, rather than failing.
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
    access: AccessControl? = null,
    roles: se.soderbjorn.lunicle.store.RoleStore? = null,
    // What this deployment says about itself (LNL-192): its own domain — the sole
    // input to the staff/member kind — and whether the Google chooser is pinned to
    // it. Defaulted to the unbranded shape: no domain, so no staff tier, and an
    // open chooser. See InstanceIdentity.
    identity: InstanceIdentity = InstanceIdentity(),
) {
    // The Google chooser pin, which is a *different* question from the domain and
    // is null unless the manifest asked for both. Named once here so the two
    // exchange paths cannot reach for the identity's domain by mistake.
    val googleHostedDomain = identity.googleHostedDomainPin
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
        val caller = call.resolveCaller(sessions, users, impersonations, access)
        call.respond(impersonationAwareState(caller, users, config, emailCodes, instanceSettings, identity.domain))
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
     * What would signing in with this address produce? Owner only (LNL-197).
     *
     * **Writes nothing**, which is the whole reason it exists beside the route below:
     * the owner is shown what an address resolved to *before* committing to wearing
     * it, and having asked leaves no mark of any kind. See [resolveAddress], which
     * both routes share so the sentence shown and the identity taken cannot disagree.
     */
    post(ApiRoutes.IMPERSONATE_PREVIEW) {
        val caller = call.resolveCaller(sessions, users, impersonations, access)
        if (!caller.canImpersonate) {
            call.respond(HttpStatusCode.Forbidden, IMPERSONATION_REFUSAL)
            return@post
        }
        val address = normalizeEmail(runCatching { call.receive<ImpersonateRequest>() }.getOrNull()?.email)
        if (address == null || !isPlausibleEmail(address)) {
            call.respond(HttpStatusCode.BadRequest, "That is not the shape of an e-mail address.")
            return@post
        }
        val resolved = resolveAddress(
            address, users, identity, instanceSettingsOrDefault(instanceSettings), roles, caller.real?.id,
        )
        call.respond(AddressPreview(email = address, standing = resolved.standing, summary = resolved.summary))
    }

    /**
     * Become an address. The instance owner only (LNL-197).
     *
     * The authorization is on `caller.real`, never `caller.effective`. Using the
     * effective user would let the owner impersonate somebody and then, as that
     * person, impersonate a *third* — the check would be asking permission of the
     * identity that was just borrowed.
     *
     * ── Three outcomes, and the third is the point ──────────────────────────
     *
     *  - **No address** — become a signed-out visitor (LNL-103).
     *  - **An address with a row** — become that account. Stored as its id, so a
     *    deleted account drops the impersonation rather than silently becoming a
     *    preview of an address nobody holds. See Impersonation.AsUser.
     *  - **An address with no row** — become the first-time arrival. Nothing is
     *    written: no `users` row, no `added_at`, no appearance in any project's People
     *    list. It is one entry in an in-memory map, and stopping removes it. This is
     *    the state that cannot be reached from a list of accounts, and it is why the
     *    route takes an address at all.
     *
     * A malformed address is a 400 rather than a silent no-op, in the same spirit the
     * board's plain refusals are written: this route is owner-only, so there is
     * nothing to withhold from the caller.
     */
    post(ApiRoutes.IMPERSONATE) {
        val caller = call.resolveCaller(sessions, users, impersonations, access)
        if (!caller.canImpersonate) {
            // 403 and not 404: the route's existence is not a secret, and somebody
            // debugging a failed impersonation deserves to know it was refused rather
            // than misrouted.
            call.respond(HttpStatusCode.Forbidden, IMPERSONATION_REFUSAL)
            return@post
        }
        val request = runCatching { call.receive<ImpersonateRequest>() }.getOrNull()
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        // Resolved before touching the session so a bad address changes nothing. A
        // null address is not an error — it is the request to become a signed-out
        // visitor (LNL-103) — so only a *named* address that is not an address is
        // refused. An address with no account is perfectly legal and is the whole
        // point; see the route doc.
        val address = request.email?.let { candidate ->
            normalizeEmail(candidate)?.takeIf { isPlausibleEmail(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, "That is not the shape of an e-mail address.")
                return@post
            }
        }

        val sessionId = call.sessionId() ?: run {
            // Unreachable in practice — canImpersonate above required a session to
            // resolve a real user from. Handled rather than asserted because the
            // cost is three lines and the alternative is a crash on a path nobody
            // has a reproduction for.
            call.respond(HttpStatusCode.Forbidden, IMPERSONATION_REFUSAL)
            return@post
        }
        if (address == null) {
            impersonations.startSignedOut(sessionId)
            logger.info("Impersonation: ${caller.real?.resolvedName} is now acting as a signed-out visitor")
        } else {
            val resolved = resolveAddress(
                address, users, identity, instanceSettingsOrDefault(instanceSettings), roles, caller.real?.id,
            )
            val target = resolved.account
            when {
                target != null && target.id == caller.real?.id -> {
                    // Becoming yourself is "stop", not a self-referential entry that
                    // resolveCaller would then have to treat as a special case forever.
                    impersonations.stop(sessionId)
                }
                target != null -> {
                    impersonations.start(sessionId, target.id)
                    logger.info(
                        "Impersonation: ${caller.real?.resolvedName} is now acting as ${target.resolvedName} <$address>",
                    )
                }
                else -> {
                    impersonations.startAsAddress(sessionId, address, resolved.kind)
                    logger.info(
                        "Impersonation: ${caller.real?.resolvedName} is now previewing <$address> " +
                            "as ${resolved.kind.key}, which holds no account here",
                    )
                }
            }
        }
        call.respond(impersonationAwareState(call.resolveCaller(sessions, users, impersonations, access), users, config, emailCodes, instanceSettings, identity.domain))
    }

    /**
     * Stop, and be yourself again.
     *
     * Gated on `canImpersonate` — the real user owning the instance — for the reason
     * ApiRoutes.STOP_IMPERSONATING gives: the effective user deliberately does not
     * own it while this is in force, so checking them would trap the caller in the
     * identity they borrowed.
     */
    post(ApiRoutes.STOP_IMPERSONATING) {
        val caller = call.resolveCaller(sessions, users, impersonations, access)
        if (!caller.canImpersonate) {
            call.respond(HttpStatusCode.Forbidden, IMPERSONATION_REFUSAL)
            return@post
        }
        impersonations.stop(call.sessionId())
        logger.info("Impersonation: ${caller.real?.resolvedName} stopped impersonating")
        call.respond(impersonationAwareState(call.resolveCaller(sessions, users, impersonations, access), users, config, emailCodes, instanceSettings, identity.domain))
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
        val caller = call.resolveCaller(sessions, users, impersonations, access)
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
        call.respond(impersonationAwareState(call.resolveCaller(sessions, users, impersonations, access), users, config, emailCodes, instanceSettings, identity.domain))
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
        val caller = call.resolveCaller(sessions, users, impersonations, access)
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
                call.resolveCaller(sessions, users, impersonations, access), users, config, emailCodes, instanceSettings,
                identity.domain,
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
        val caller = call.resolveCaller(sessions, users, impersonations, access)
        val user = caller.effective ?: run {
            call.respond(HttpStatusCode.Forbidden, "You must be signed in to change your profile.")
            return@post
        }
        // An admin wearing somebody's face has no business changing where that
        // person's mail goes — and, once e-mail is the account key, redirecting it
        // is redirecting the account. Consistent with how the settings pane's You
        // tab treats other self-only actions, and stated here rather than inherited
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
                call.resolveCaller(sessions, users, impersonations, access), users, config, emailCodes, instanceSettings,
                identity.domain,
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
        val caller = call.resolveCaller(sessions, users, impersonations, access)
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
                call.resolveCaller(sessions, users, impersonations, access), users, config, emailCodes, instanceSettings,
                identity.domain,
            ),
        )
    }

    /** Drop a pending address change, so a mistyped address is not a fifteen-minute wait. */
    post(ApiRoutes.USER_EMAIL_CANCEL) {
        val caller = call.resolveCaller(sessions, users, impersonations, access)
        val user = caller.effective ?: run {
            call.respond(HttpStatusCode.Forbidden, "You must be signed in to change your profile.")
            return@post
        }
        emailCodes?.cancelFor(user.id, EmailCodePurpose.EMAIL_CHANGE)
        call.respond(
            impersonationAwareState(
                call.resolveCaller(sessions, users, impersonations, access), users, config, emailCodes, instanceSettings,
                identity.domain,
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
     * becomes the instance administrator, and is seated as its owner at the next
     * boot — the same rule Google sign-in has always had; see `Users.sq` and
     * [seatInstanceOwner].
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
        val provided = ProviderIdentity(
            provider = AuthProvider.EMAIL,
            providerId = redemption.address,
            providerName = redemption.address.substringBefore('@'),
            // Non-null means proved, which a redeemed code is — see
            // ProviderIdentity.email. This is what sets `email_verified`.
            email = redemption.address,
        )
        // Admission, before the row exists (LNL-192). After the code is spent, which
        // is right: the code proves the address, and a deployment refusing an address
        // it has not been shown would be refusing a claim rather than a person.
        admissionRefusal(provided, users, instanceSettings, identity)?.let {
            call.respond(HttpStatusCode.Forbidden, it)
            return@post
        }
        val user = users.upsert(
            provided,
            // Derived here rather than in the store, from the same function the
            // startup stamp uses — see UserKind.forEmail. A code sign-in proves the
            // address, so it is exactly as good a basis for the staff/member answer
            // as Google's is.
            kind = UserKind.forEmail(redemption.address, identity.domain),
        )
        call.setSessionCookie(sessions.create(user.id))
        logger.info("Signed in with an e-mail code: ${user.resolvedName} (user ${user.id})")
        call.respond(freshSignInState(user, users, config, instanceSettings, identity.domain))
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
            val googleIdentity = exchangeGoogleCode(
                httpClient, credentials, request.code, call.serverOrigin(), googleHostedDomain,
            )
            // Find-or-create the account, then point a session at it. On a
            // returning user this finds the row written the first time — which
            // is what reunites them with their issues, and their admin bit.
            admissionRefusal(googleIdentity, users, instanceSettings, identity)?.let {
                call.respond(HttpStatusCode.Forbidden, it)
                return@post
            }
            val user = users.upsert(googleIdentity, UserKind.forEmail(googleIdentity.email, identity.domain))
            call.setSessionCookie(sessions.create(user.id))
            logger.info("Signed in via Google: ${user.resolvedName} (user ${user.id})")
            call.respond(freshSignInState(user, users, config, instanceSettings, identity.domain))
        } catch (failure: SignInFailure) {
            call.respond(HttpStatusCode.BadGateway, failure.userMessage)
        }
    }
}

/**
 * The admission gate: may this sign-in bring a **new** account into existence?
 *
 * ── Asked once, at creation, and it grants nothing ──────────────────────────
 *
 * Both sign-in paths go through here, immediately before their `upsert`, and both
 * short-circuit on an account somebody has **used**: admission is who may *hold* an
 * account, so a policy tightened this morning does not lock out somebody who has had
 * one since last year. Whether an account exists is [UserStore.findExisting]'s answer
 * and not a second copy of upsert's two-step lookup, because a copy that drifted
 * would refuse exactly the returning people this is careful not to.
 *
 * ── The row that exists and has never been used (LNL-194) ───────────────────
 *
 * A row is not proof of an arrival any more. Adding a person to a project writes one
 * for an address nobody has signed in with, so that the rung can be granted before
 * its holder turns up — and that row is exactly the case
 * [AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED] was written for. So the short-circuit
 * asks [UserRecord.hasSignedIn] rather than merely "is there a row", and the row's
 * existence becomes the `isAlreadyAdded` input instead.
 *
 * That is what makes the two staff policies differ, which until now they did not:
 * nothing set `isAlreadyAdded`, so plus-added behaved identically to
 * staff-domain-only. With this, an outside address an administrator has added is
 * admitted under plus-added and still refused under staff-domain-only — which is the
 * whole distinction between the two settings.
 *
 * Somebody admitted here arrives as a member or a staff member with whatever the
 * ladders give that tier, which on a fresh instance is nothing at all. The door is
 * not the room.
 *
 * @return the refusal to send, or null when the sign-in may proceed.
 */
private suspend fun admissionRefusal(
    provided: ProviderIdentity,
    users: se.soderbjorn.lunicle.store.UserStore,
    instanceSettings: se.soderbjorn.lunicle.store.InstanceSettingsStore?,
    identity: InstanceIdentity,
): String? {
    val existing = users.findExisting(provided)
    // Already been here: this is a sign-in, not a creation, and admission has nothing
    // to say about it.
    if (existing?.hasSignedIn == true) return null
    val policy = instanceSettingsOrDefault(instanceSettings).admission
    // A row with no arrival means somebody on this instance named this address on
    // purpose. See this function's doc.
    if (policy.admitsNewAccount(provided.email, identity, isAlreadyAdded = existing != null)) return null
    // Named plainly. The caller is mid-sign-in with a proved address or a completed
    // OAuth exchange, so there is no oracle to protect here — the alternative is
    // somebody staring at a generic failure for a deployment that simply is not open
    // to them, which is the one thing they cannot work out for themselves.
    return "This Lunicle does not accept new accounts for that address. " +
        "Ask an administrator of this instance to add you."
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
