/**
 * Wire types for the instance-wide switches an administrator sets for the whole
 * deployment (LNL-115): whether signing in is required to use the app at all,
 * whether any signed-in user may create a project, and whether the display-name
 * override is hidden (LNL-137).
 *
 * These are not per-user preferences ([UiSettings] is that) and they are not a
 * per-project setting ([ProjectSettingsState] is that). They are facts about the
 * deployment, the same for everyone who asks, and only a system administrator may
 * change them. The *values* reach two very different audiences: the admin sees
 * them all, in the Settings dialog's General tab (they ride on [AdminSettingsState]);
 * the require-sign-in and hide-display-name flags also reach every client on
 * [SessionState], because the audience each is drawn for — a signed-out visitor for
 * the gate, every signed-in user for the profile field — has no other state to read
 * it from.
 *
 * @see se.soderbjorn.lunicle.clientserver.ApiRoutes.ADMIN_INSTANCE_SETTINGS
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.Serializable

/**
 * Which instance-wide switch a write is naming.
 *
 * A closed enum rather than a free-form key string, for the reason
 * [UiSettingKeys] is an allowlist: the set of switches this deployment has is the
 * server's to define, not something any signed-in browser can invent a new one of.
 * The server stores each under [storageKey]; that string is the database column
 * value and outlives any rename of the constant, exactly like [AuthProvider]'s
 * wire names.
 *
 * @property storageKey the key this switch is persisted under, server-side. Kept
 *   distinct from the constant's own name so a refactor of the Kotlin name is not
 *   a migration of the stored rows.
 */
@Serializable
enum class InstanceSettingKey(val storageKey: String) {
    /**
     * `require_sign_in` — when on, the app refuses to be used signed out: a
     * signed-out visitor is met by a landing gate with a Sign in button and
     * nothing else. Off (the default, and every deployment before LNL-115) leaves
     * the app usable signed out, showing whatever is public.
     */
    REQUIRE_SIGN_IN("require_sign_in"),

    /**
     * `anyone_can_create_project` — when on, any signed-in user may create a
     * project, not only a system administrator. Off (the default) keeps creation
     * an administrator's power, which is what it has always been. Note this widens
     * *creating* only: renaming, deleting and reordering projects stay where they
     * were. See the server's `AccessControl.canCreateProject`.
     */
    ANYONE_CAN_CREATE_PROJECT("anyone_can_create_project"),

    /**
     * `hide_display_name` — when on, the display-name override in the profile dialog
     * is hidden, so every user's name is the one their sign-in provider gives and
     * cannot be overridden here (LNL-137). Off (the default) leaves the override
     * offered, which is what it has always been. Like [REQUIRE_SIGN_IN] this reaches
     * every client, not only the admin, because the field it hides is one every
     * signed-in user has — it rides on [SessionState.isDisplayNameHidden].
     */
    HIDE_DISPLAY_NAME("hide_display_name"),
}

/**
 * "Set this instance switch to this state."
 *
 * Names the desired state rather than saying "toggle", for [UserMcpAccess]'s
 * reason: a retry says the same thing, and two admins with the dialog open cannot
 * flip one switch back and forth by both clicking once.
 *
 * Note what it does not carry: who is asking. That comes from the session cookie,
 * server-side, on every request — see [UserMcpAccess], the same shape for the same
 * reason.
 *
 * @property key which switch to change.
 * @property isEnabled the state to move it to.
 */
@Serializable
data class SetInstanceSettingRequest(
    val key: InstanceSettingKey,
    val isEnabled: Boolean,
)
