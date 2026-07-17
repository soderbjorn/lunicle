/**
 * Wire types for the Connections section: agent access, and what is connected.
 *
 * The whole section is about one sentence a user has to be able to act on —
 * *"what is currently allowed to act as me, and how do I stop it?"* — so these
 * types carry exactly what answers that and nothing else.
 *
 * @see se.soderbjorn.lunicle.clientserver.LunicleApi.mcpState
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.Serializable

/**
 * One agent that can act as this user.
 *
 * @property clientId what Revoke names. Not a secret — it identifies a
 *   registration, and every route takes who is asking from the session cookie, so
 *   knowing one grants nothing.
 * @property clientName what the agent called itself when it registered.
 *
 *   **Self-reported, and therefore chosen by a stranger.** Anyone can register a
 *   client named "Claude Code"; this is a real, if minor, spoofing surface. The
 *   view must escape it on render and must not let it be the only thing the user
 *   sees before revoking. It is here because a list of opaque ids would be
 *   unusable, not because it is trustworthy.
 * @property connectedAt when this agent was first authorized, epoch millis. The
 *   oldest surviving grant rather than the newest, so a refresh does not reset it
 *   — see the server's OAuthTokens.sq.
 * @property lastUsedAt when it last called, epoch millis, or null if it never has.
 *   The fact people actually act on: "connected in July, never used since" is what
 *   makes someone click Revoke.
 */
@Serializable
data class McpConnection(
    val clientId: String,
    val clientName: String,
    val connectedAt: Long,
    val lastUsedAt: Long? = null,
)

/**
 * The Connections section, whole.
 *
 * Returned in full by all three MCP routes rather than each sending a fragment,
 * matching how [ApiRoutes.SESSION] and [ApiRoutes.SIGN_OUT] both return a whole
 * [SessionState]. The client re-renders from one object and never has to merge
 * two — and a toggle that also revokes, or a revoke that also changes the toggle,
 * cannot produce a client that disagrees with the server about either.
 *
 * @property isEnabled whether this user allows agents to act for them.
 *
 *   Unlike most flags on the wire here, this one is **not** an affordance — it is
 *   a mirror of a real server-side gate. `/oauth/authorize` refuses to start a
 *   flow while it is off and `/mcp` rejects this user's tokens, both re-read per
 *   request. Setting it to `true` in a console changes a checkbox and nothing
 *   else. See the server's AccessControl preamble for the general rule.
 * @property serverUrl the absolute `/mcp` URL to paste into an agent. Absolute,
 *   and computed server-side from the origin the browser actually reached — the
 *   client must not build it from `location`, because the one place this is read
 *   is inside an iframe on lunamux.dev and getting it wrong yields a URL that
 *   fails only for the person who pasted it.
 * @property connections what is connected now, oldest first. Empty is the common
 *   case and is a state worth rendering, not an absence.
 */
@Serializable
data class McpState(
    val isEnabled: Boolean = false,
    val serverUrl: String = "",
    val connections: List<McpConnection> = emptyList(),
)

/**
 * "Turn agent access on, or off."
 *
 * Names the desired state rather than saying "toggle", deliberately. A toggle is
 * a request whose meaning depends on what the server currently thinks, so two of
 * them racing — a double click, a retried request — can land on either answer.
 * This one is idempotent: sending it twice says the same thing twice.
 *
 * @property isEnabled the state to move to.
 */
@Serializable
data class McpEnabledRequest(
    val isEnabled: Boolean,
)
