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
 * @property displayName what the provider calls them. Never blank — the server
 *   substitutes the email's local part, then a provider-specific fallback,
 *   because a signed-in user rendering as an empty string looks like a bug.
 * @property provider which provider authenticated them, so the view can say so.
 */
@Serializable
data class SignedInUser(
    val displayName: String,
    val provider: AuthProvider,
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
 */
@Serializable
data class SessionState(
    val user: SignedInUser? = null,
    val isGoogleAvailable: Boolean = false,
    val isGitHubAvailable: Boolean = false,
    val googleClientId: String? = null,
) {
    /** Whether to render any sign-in affordance at all. */
    val isSignInAvailable: Boolean get() = isGoogleAvailable || isGitHubAvailable
}

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
