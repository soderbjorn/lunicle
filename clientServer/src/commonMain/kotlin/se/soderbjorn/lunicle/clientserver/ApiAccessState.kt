/**
 * Wire types for the API access section: personal access tokens (LNL-222).
 *
 * The section answers the same sentence the Connections section does — *"what can act
 * as me, and how do I stop it?"* — for a different kind of caller. Connections lists
 * AI agents that went through a consent page; this lists tokens the person made by
 * hand for their own scripts and integrations. Two sections because they are two
 * relationships with two switches, and a person may well want one without the other.
 *
 * @see se.soderbjorn.lunicle.clientserver.LunicleApi.apiAccessState
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a token may do.
 *
 * Two values, chosen when the token is made and never changed afterwards — a token
 * that needs more is a new token, so the list always says truthfully what each one
 * could have done.
 *
 * @property key the stored and wire form.
 */
@Serializable
enum class ApiTokenScope(val key: String) {
    /** Every read the API has: projects, boards, issues, vocabulary. Nothing that writes. */
    @SerialName("read")
    READ("read"),

    /** Everything the token's owner could do in the web app, through the API. */
    @SerialName("write")
    WRITE("write"),
    ;

    companion object {
        /**
         * The scope a stored key names, or [READ] for anything unrecognised.
         *
         * The narrower answer on purpose: a row written by a newer build with a scope
         * this one has never heard of must not be read as permission to write.
         */
        fun byKey(key: String?): ApiTokenScope = entries.firstOrNull { it.key == key } ?: READ
    }
}

/**
 * One token, as its owner's list shows it. Never carries the token itself — that
 * exists only in [CreatedApiToken], once.
 *
 * @property id what Revoke names. Not a secret: revoking is scoped to the session's
 *   own user server-side, so knowing somebody else's id revokes nothing.
 * @property name what the person called it.
 * @property prefix the token's first few characters, so the person can match a row to
 *   the value sitting in some config file.
 * @property createdAt epoch millis.
 * @property lastUsedAt epoch millis, or null if it never has been.
 * @property expiresAt epoch millis, or null for a token that never expires.
 */
@Serializable
data class ApiTokenView(
    val id: Long,
    val name: String,
    val prefix: String,
    val scope: ApiTokenScope,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
    val expiresAt: Long? = null,
)

/**
 * The API access section, whole. Returned in full by every route that changes it, for
 * [McpState]'s reason: the client replaces rather than merges.
 *
 * @property isAllowed whether this account's tier is permitted API access at all — an
 *   administrator's decision, made per tier. See the server's `canUseApi`.
 * @property isEnabled whether the person has turned API access on for themselves. A
 *   real server-side gate, re-read on every `/api/v1` request — off stops every token
 *   at once without deleting any.
 * @property baseUrl the absolute `/api/v1` URL. Computed server-side from the origin the
 *   browser reached, for [McpState.serverUrl]'s reason.
 * @property docsUrl the absolute URL of the generated OpenAPI description.
 * @property tokens this person's tokens, oldest first.
 */
@Serializable
data class ApiAccessState(
    val isAllowed: Boolean = false,
    val isEnabled: Boolean = false,
    val baseUrl: String = "",
    val docsUrl: String = "",
    val tokens: List<ApiTokenView> = emptyList(),
)

/** "Turn API access on, or off." The desired state, never a toggle — see [McpEnabledRequest]. */
@Serializable
data class ApiEnabledRequest(
    val isEnabled: Boolean,
)

/**
 * "Make me a token."
 *
 * @property name what to call it. Required and trimmed server-side; a blank name is
 *   refused, because a list of unnamed tokens is a list nobody can safely revoke from.
 * @property scope what it may do.
 * @property expiresInDays how long it lives, or null for never. The server accepts only
 *   the values [API_TOKEN_EXPIRY_CHOICES] offers, so a client cannot mint a token that
 *   lives a thousand years by typing.
 */
@Serializable
data class CreateApiTokenRequest(
    val name: String,
    val scope: ApiTokenScope,
    val expiresInDays: Int? = null,
)

/**
 * The response to [CreateApiTokenRequest]: the new state, plus the token itself.
 *
 * @property token the raw value. **The only time it is ever sent.** The server keeps a
 *   hash; nothing can show it again, and the view must say so before the person
 *   closes it.
 */
@Serializable
data class CreatedApiToken(
    val state: ApiAccessState,
    val token: String,
)

/**
 * The lifetimes a token may be given, in days, with null for "never expires".
 *
 * Shared so the menu the person picks from and the server's check are one list. Ninety
 * days is first because it is the default: long enough that a CI job does not break
 * monthly, short enough that a forgotten token does not live forever.
 */
val API_TOKEN_EXPIRY_CHOICES: List<Int?> = listOf(90, 30, 365, null)

/** The maximum length of a token's name. A label, not a description. */
const val API_TOKEN_NAME_MAX_LENGTH: Int = 80
