/**
 * Wire types for who is signed in, shared by the server and every client.
 *
 * Stage 2's payload is a name on screen. That sounds trivial and isn't: a
 * display name can only appear if the popup opened, the provider authenticated,
 * the code reached the opener, the server exchanged it, and a session was
 * established. It is the smallest thing that cannot be faked by a client that
 * merely believes it is signed in.
 *
 * @see LunicleApi
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.Serializable

/**
 * Which provider an identity came from.
 *
 * Serialized by name, so these strings are wire format and outlive any rename —
 * they are also the natural provider column in the users table when persistence
 * lands. Changing one is a migration, not a refactor.
 */
@Serializable
enum class AuthProvider {
    GOOGLE,
    GITHUB,

    /**
     * No provider at all: the account was created by proving a mailbox.
     *
     * Adding a case is safe in a way renaming one is not — nothing in storage
     * names `EMAIL` until the first account is created this way, and every reader
     * already tolerates a name it does not recognise (see the server's
     * `parseProvider`, which skips such a row rather than failing).
     *
     * Note this is provenance rather than a live dependency. A row created here
     * and a row created by Google are found the same way once either has a
     * verified address — that is what LNL-73's re-key means — so this records how
     * the account came to exist and drives the "Signed in via …" line, nothing
     * more. Somebody who registered with Google may sign in with a code forever
     * after and stay a `GOOGLE` row.
     */
    EMAIL,
}

/**
 * A signed-in user, as the client is allowed to know them.
 *
 * Deliberately not the provider's full profile: the client renders a name and
 * nothing else, so nothing else crosses. No access token, ever — that stays on
 * the server. Sending one to the browser would put a provider credential in a
 * place any extension can read, in exchange for nothing.
 *
 * @property displayName what to render: the user's own override if they set one,
 *   else what the provider calls them. Never blank — the server substitutes the
 *   email's local part, then a provider-specific fallback, because a signed-in
 *   user rendering as an empty string looks like a bug. Resolved server-side so
 *   every client renders the same name by the same rule.
 * @property provider which provider authenticated them, so the sidebar can say
 *   "Signed in via GitHub as …".
 * @property isSysAdmin whether this is the instance admin.
 *
 *   **An affordance, not a grant.** It exists so the UI does not offer "New
 *   project…" to someone the server will refuse. Setting it to `true` in a
 *   console reveals the buttons and changes nothing else: every mutating route
 *   re-derives admin from the session before it writes. See the server's
 *   `AccessControl` preamble.
 * @property hasDisplayNameOverride whether [displayName] is the user's own
 *   choice rather than the provider's. The client cannot tell from the name
 *   alone — someone may have overridden it to exactly what GitHub calls them —
 *   and the settings field needs to know whether to show a "reset" affordance.
 * @property email the caller's own address, or null when we do not have one.
 *
 *   This is the one place an email crosses the wire, and it is deliberately
 *   narrow: [SignedInUser] is only ever the *effective* caller's own record — the
 *   person looking at the screen (or, under impersonation, the account an admin is
 *   deliberately wearing). It is never a directory. The profile dialog renders it
 *   in the User tab and lets its owner edit it, and the notification toggles are
 *   hidden without it — a toggle that promises an e-mail we have no address to
 *   send is a lie. Other people's addresses still never cross: [UserOption] and
 *   `ProjectMember` remain a name and an id, and the board's author fields remain
 *   a name. See the server's `UserRecord.toSignedInUser`.
 * @property id who this is, as an identity the client can COMPARE — never one it
 *   can act on.
 *
 *   It exists because "who am I acting as?" became a question with a changing
 *   answer when impersonation landed, and the client has to notice the change to
 *   re-fetch the board. A name cannot do that job: two accounts may share one,
 *   and the board would then not reload between them — leaving one user's
 *   projects on screen under another's name, with the wrong things draggable.
 *
 *   Sending it costs nothing. A user id is not a capability here: every route
 *   takes who is asking from the session cookie and never from the body, so
 *   knowing an id — your own or anyone's — grants exactly nothing. See the
 *   server's Impersonations, whose whole subject this is.
 *
 *   The email is the caller's own; see the property doc for why that one address
 *   crossing is not the directory leak this comment used to forbid.
 * @property isEmailVerified whether [email] was ever proved, rather than merely
 *   typed. An affordance like every other flag here — the server decides what an
 *   unverified address may be used for — and it exists so the profile dialog can
 *   say which of the two it is showing. Before LNL-71 there was no difference to
 *   report, because nothing was ever checked.
 */
@Serializable
data class SignedInUser(
    val id: Long,
    val displayName: String,
    val provider: AuthProvider,
    val isSysAdmin: Boolean = false,
    val hasDisplayNameOverride: Boolean = false,
    val email: String? = null,
    val isEmailVerified: Boolean = false,
)

/**
 * Whether anyone is signed in, and whether sign-in is on offer if not.
 *
 * The availability flag comes from the server rather than being compiled into
 * the bundle: a deployment without Google credentials must not render a Google
 * button, and only the server knows which variables it was given. See
 * `OAuthConfig.kt`.
 *
 * @property user the signed-in user, or `null` for a signed-out session.
 * @property isGoogleAvailable whether the server can complete a Google sign-in.
 * @property googleClientId the public Google client id, needed by the browser to
 *   open the popup, and `null` when Google isn't configured. Public by design —
 *   it ships in every Google sign-in page on the web. The *secret* never leaves
 *   the server.
 * @property user the **effective** user — the impersonated one while an admin is
 *   impersonating. That is what the profile button shows, and it is not a display
 *   convenience: it is the same user every permission on the server is being
 *   gated on, so the name on screen and the rights in force can never disagree.
 *   Its `isSysAdmin` is the *impersonated* user's, which is why an impersonating
 *   admin loses the admin affordances — see the server's Impersonations.
 * @property isImpersonating whether [user] is somebody other than whoever signed
 *   in. Drives "Stop impersonating" replacing "Impersonate".
 * @property canImpersonate whether the **real** signed-in user is an admin, and
 *   so may start or stop impersonating. Separate from `user.isSysAdmin` on purpose:
 *   while impersonating, `user.isSysAdmin` is false and this is still true — that is
 *   precisely the state in which "Stop impersonating" has to remain reachable. An
 *   affordance like every other flag here; the routes re-derive it from the
 *   session.
 * @property impersonatableUsers everyone this admin could act as. Empty unless
 *   [canImpersonate] — the server does not send the user list to people who
 *   cannot use it, rather than sending it and trusting the menu to stay hidden.
 * @property pendingEmail an address the caller has asked to attach and not yet
 *   confirmed, or null.
 *
 *   **Server-side state, reported rather than remembered**, and that is the whole
 *   point of it being here. LNL-71 requires that a user who closes the profile
 *   dialog to go and read their mail comes back to find the request still
 *   waiting; a flag in a view model would not survive that, and a flag in
 *   local storage would survive the code expiring. The pending change is a row in
 *   `email_codes`, so re-fetching the session is all "did I ask for this?" needs.
 *
 *   Only ever the caller's own, like [SignedInUser.email], and null once the code
 *   is confirmed, cancelled or expired.
 * @property isEmailSignInAvailable whether the server can sign somebody in with a
 *   mailed code — which requires it to be able to send mail at all. From the
 *   server for [isGoogleAvailable]'s reason: only the server knows which variables
 *   it was given, and a surface must never render a method the server cannot
 *   perform. There is no client id beside it because there is no third party.
 * @property isSignInRequired whether this deployment refuses to be used signed out
 *   (LNL-115). An instance-wide switch, the same for everyone who asks, and it
 *   rides here — rather than only on the admin-only [AdminSettingsState] — because
 *   the client it is drawn for is precisely the signed-out one, which has no other
 *   state to read it from. When true and [user] is null, the shell raises a
 *   landing gate. An affordance, not a wall: a genuinely private board is still
 *   guarded per-request by `AccessControl.canReadProject`, so this is the UX that
 *   stops a signed-out visitor being dropped onto an empty-looking app rather than
 *   the thing that keeps private data private. See [InstanceSettingKey].
 * @property isDisplayNameHidden whether this deployment hides the display-name
 *   override in the profile dialog (LNL-137). An instance-wide switch, the same for
 *   everyone who asks, and it rides here — rather than only on the admin-only
 *   [AdminSettingsState] — because the field it hides is one every signed-in user
 *   has, so every client must know whether to draw it. When true the profile
 *   dialog omits the override entirely and each user's name is the one their
 *   sign-in provider gives. See [InstanceSettingKey.HIDE_DISPLAY_NAME].
 */
@Serializable
data class SessionState(
    val user: SignedInUser? = null,
    val isGoogleAvailable: Boolean = false,
    val googleClientId: String? = null,
    val isImpersonating: Boolean = false,
    val canImpersonate: Boolean = false,
    val impersonatableUsers: List<UserOption> = emptyList(),
    val pendingEmail: String? = null,
    val isEmailSignInAvailable: Boolean = false,
    val isSignInRequired: Boolean = false,
    val isDisplayNameHidden: Boolean = false,
) {
    /**
     * Whether to render any sign-in affordance at all.
     *
     * **This used to mean "Google is configured".** It stopped meaning that with
     * LNL-74: a deployment may have either method, both, or neither, and a picker
     * gated on Google would hide a perfectly working e-mail form on a server with
     * no Google credentials.
     */
    val isSignInAvailable: Boolean get() = isGoogleAvailable || isEmailSignInAvailable
}

/**
 * One entry in the impersonation menu.
 *
 * A name and an id, and nothing else — no email, no provider, no admin flag. The
 * menu renders a name; anything more would be a directory of everyone's accounts
 * shipped to the browser for no reason. The id is unavoidable: it is what "act as
 * this one" has to name.
 *
 * @property isSelf whether this is the admin's own account. The menu shows it so
 *   the list matches the user table someone is looking at, but it is not worth
 *   picking — see the client's SignInView.
 */
@Serializable
data class UserOption(
    val id: Long,
    val name: String,
    val isSelf: Boolean = false,
)

/**
 * The browser's half of a Google popup sign-in: the authorization code that
 * `initCodeClient`'s callback received.
 *
 * @property code the one-time authorization code. Useless without the client
 *   secret, which is why it may safely cross the wire.
 */
@Serializable
data class GoogleCodeRequest(
    val code: String,
)

/**
 * "Let me act as this user."
 *
 * The only thing a client is permitted to say about identity, and note what it
 * does *not* say: it names who to become, never who is asking. Who is asking
 * comes from the session cookie, server-side, on every request — see the server's
 * Impersonations. A field here for the *acting* user would be the authorization
 * system asking the caller to authorize themselves.
 *
 * @property userId the account to act as, or null to act as a signed-out visitor —
 *   no account at all (LNL-103). Refused either way unless the calling session's
 *   real user is an admin. Null is a deliberate choice, not an omission: it is the
 *   only way to name "become nobody", which is a real thing to preview.
 */
@Serializable
data class ImpersonateRequest(
    val userId: Long? = null,
)

/**
 * "Call me this instead", or clear it.
 *
 * The User tab's display-name field. A blank or absent value clears the override
 * and falls the rendered name back to the provider's — the server normalises ""
 * to null (see `UserStore.setDisplayName`), so the client does not have to.
 *
 * Note what this does not carry: who is asking. That is the session cookie's job,
 * server-side, on every request — a field for it would be the caller authorising
 * themselves. See [ImpersonateRequest], the same shape for the same reason.
 */
@Serializable
data class SetDisplayNameRequest(
    val displayName: String? = null,
)

/**
 * "Clear my address."
 *
 * ── What this used to be, and why it is narrower now ───────────────────────
 *
 * It used to carry a new address as well, and `POST /api/user/email` would write
 * it with no verification of any kind. That was harmless while e-mail was
 * decorative and became an account-takeover primitive the moment anything keyed
 * on it: claim a victim's address, and their next sign-in resolves to your row.
 * LNL-71 closed it, so setting an address now goes through
 * [ApiRoutes.USER_EMAIL_REQUEST] and [ApiRoutes.USER_EMAIL_CONFIRM] and a mailed
 * code.
 *
 * The type survives for the half that needs no proof. **Giving up a mailbox
 * establishes nothing and so requires nothing** — you cannot take somebody else's
 * account by removing an address from your own — so clearing stays immediate.
 * A non-null [email] is refused rather than ignored, so an old client that still
 * tries to set one is told, not silently obeyed.
 */
@Serializable
data class SetEmailRequest(
    val email: String? = null,
)

/**
 * "Send a code to this address, so I can prove I own it."
 *
 * The first half of an address change. Nothing is written to the account by this
 * — the address is stored only as a pending row in `email_codes` until
 * [ConfirmEmailRequest] spends the code — which is exactly what stops a claim
 * from being a change.
 */
@Serializable
data class RequestEmailChangeRequest(
    val email: String,
)

/**
 * "Here is the code you mailed me."
 *
 * The second half. Note what it does *not* carry: the address. That is held
 * server-side with the pending row, so the confirm step writes the address that
 * was actually mailed to rather than one the client repeats back — which would
 * be a second chance for the two to disagree, on the write that decides identity.
 */
@Serializable
data class ConfirmEmailRequest(
    val code: String,
)

/**
 * "Mail me a code so I can sign in."
 *
 * The signed-out twin of [RequestEmailChangeRequest], and the response to it says
 * nothing about whether the address has an account. That is deliberate and it is
 * the reason these are two types rather than one: the address-change endpoint may
 * report a send failure to a caller who is already authenticated, and this one
 * may not report anything at all, because "the code was sent" and "no account
 * here" would be an account-existence oracle for anybody who can type.
 */
@Serializable
data class EmailSignInRequest(
    val email: String,
)

/**
 * "Here is the code you mailed me. Sign me in."
 *
 * Carries the address, unlike [ConfirmEmailRequest] — there is no session to hold
 * a pending request against, so the caller has to say which mailbox they are
 * claiming. Holding it in a cookie instead would be a second piece of state to
 * expire, on the one flow that has to work when nothing is signed in.
 */
@Serializable
data class EmailSignInRedeemRequest(
    val email: String,
    val code: String,
)
