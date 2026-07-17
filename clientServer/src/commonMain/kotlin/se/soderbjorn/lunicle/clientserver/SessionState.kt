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
 * @property isAdmin whether this is the instance admin.
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
 *   The email is deliberately absent from this type. It never crosses the wire
 *   at all; nothing on any screen renders one.
 */
@Serializable
data class SignedInUser(
    val id: Long,
    val displayName: String,
    val provider: AuthProvider,
    val isAdmin: Boolean = false,
    val hasDisplayNameOverride: Boolean = false,
)

/**
 * Whether anyone is signed in, and which providers are on offer if not.
 *
 * The provider flags come from the server rather than being compiled into the
 * bundle: a deployment without GitHub credentials must not render a GitHub
 * button, and only the server knows which variables it was given. See
 * `OAuthConfig.kt`.
 *
 * @property user the signed-in user, or `null` for a signed-out session.
 * @property isGoogleAvailable whether the server can complete a Google sign-in.
 * @property isGitHubAvailable whether the server can complete a GitHub sign-in.
 * @property googleClientId the public Google client id, needed by the browser to
 *   open the popup, and `null` when Google isn't configured. Public by design —
 *   it ships in every Google sign-in page on the web. The *secret* never leaves
 *   the server.
 * @property user the **effective** user — the impersonated one while an admin is
 *   impersonating. That is what the profile button shows, and it is not a display
 *   convenience: it is the same user every permission on the server is being
 *   gated on, so the name on screen and the rights in force can never disagree.
 *   Its `isAdmin` is the *impersonated* user's, which is why an impersonating
 *   admin loses the admin affordances — see the server's Impersonations.
 * @property isImpersonating whether [user] is somebody other than whoever signed
 *   in. Drives "Stop impersonating" replacing "Impersonate".
 * @property canImpersonate whether the **real** signed-in user is an admin, and
 *   so may start or stop impersonating. Separate from `user.isAdmin` on purpose:
 *   while impersonating, `user.isAdmin` is false and this is still true — that is
 *   precisely the state in which "Stop impersonating" has to remain reachable. An
 *   affordance like every other flag here; the routes re-derive it from the
 *   session.
 * @property impersonatableUsers everyone this admin could act as. Empty unless
 *   [canImpersonate] — the server does not send the user list to people who
 *   cannot use it, rather than sending it and trusting the menu to stay hidden.
 */
@Serializable
data class SessionState(
    val user: SignedInUser? = null,
    val isGoogleAvailable: Boolean = false,
    val isGitHubAvailable: Boolean = false,
    val googleClientId: String? = null,
    val isImpersonating: Boolean = false,
    val canImpersonate: Boolean = false,
    val impersonatableUsers: List<UserOption> = emptyList(),
) {
    /** Whether to render any sign-in affordance at all. */
    val isSignInAvailable: Boolean get() = isGoogleAvailable || isGitHubAvailable
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
 * @property userId the account to act as. Refused unless the calling session's
 *   real user is an admin.
 */
@Serializable
data class ImpersonateRequest(
    val userId: Long,
)
