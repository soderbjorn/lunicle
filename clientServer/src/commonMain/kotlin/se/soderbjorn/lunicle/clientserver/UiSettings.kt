/**
 * The wire types for the shell settings that follow a signed-in user: their
 * appearance choice (dark / light / auto) and the theme picked for each slot.
 *
 * Everything here is deliberately opaque. The values are the lunula's
 * own persistence blobs, produced and consumed by the toolkit's theme manager at
 * one end and stored as text at the other; neither Lunicle module parses one.
 * That is what keeps a toolkit upgrade that adds a field to a snapshot from
 * being a change to this file, to the database, or to the route between them.
 *
 * What is *not* opaque is which keys are allowed to travel — see [UiSettingKeys].
 *
 * @see se.soderbjorn.lunicle.clientserver.ApiRoutes.USER_UI_SETTINGS
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.Serializable

/**
 * The toolkit persistence keys Lunicle will store, spelled out.
 *
 * These are string copies of `se.soderbjorn.lunula.core.PersistKeys`
 * constants, and they are copies because this module has no business depending
 * on the toolkit: :clientServer is the wire, shared with the JVM server, which
 * has no browser shell in it at all. `ThemeKeysMatchToolkitTest` in :web — the
 * one module that does see both — asserts the copies still match, so a rename
 * upstream fails a test rather than silently stranding everyone's stored theme
 * under a key nothing reads any more.
 *
 * The set is an allowlist, enforced server-side. The alternative — storing
 * whatever key the client names — would turn a settings table into a per-user
 * scratch space that any signed-in browser could fill, and would make the
 * question "what does the server keep about me" unanswerable from the code.
 */
object UiSettingKeys {
    /**
     * `darkness.theme.v2.selection` — the appearance (Auto/Dark/Light) and the
     * theme chosen for each of the dark and light slots.
     *
     * This one key is the whole of what LNL-20 asked for; the next is here so
     * that what it names still exists.
     */
    const val THEME_SELECTION: String = "darkness.theme.v2.selection"

    /**
     * `darkness.theme.v2.custom` — themes the user built in the theme manager.
     *
     * Persisted together with the selection because the selection *names* one of
     * these by string. Storing the choice without the theme it points at would
     * be a reference to nothing: on the next load the toolkit would find an
     * unknown name and fall back to a built-in, so the user would have kept
     * their appearance and lost their colours.
     */
    const val THEME_CUSTOM: String = "darkness.theme.v2.custom"

    /**
     * `lunicle.userProjectPrefs.v1` — the caller's own view choices, per project.
     *
     * Unlike the two above, this key is **Lunicle's own, not the toolkit's**: it
     * does not pass through the lunula `Persister`, so it has no `PersistKeys`
     * counterpart and `ThemeKeysMatchToolkitTest` does not check it against the
     * toolkit — there is nothing upstream to match. The server still stores it
     * verbatim; the *client* is the one that parses it, into a
     * `UserProjectPrefs` per project id.
     *
     * One key holds every project's preferences as a single blob, keyed inside by
     * project id, and each project's record is a small open struct — today just
     * the columns that user has hidden (LNL-100). A future per-user-per-project
     * choice becomes a field on that record rather than another key here, another
     * entry in [persisted], and another round-trip on load: the whole of a user's
     * board preferences arrives and departs in one read and one write.
     *
     * Targeting the *id* of a status, not its name, is deliberate and is what the
     * issue asked for — a hidden column stays hidden across a rename, because the
     * rename leaves `StatusItem.id` untouched. See BoardState.StatusItem.
     */
    const val PROJECT_PREFS: String = "lunicle.userProjectPrefs.v1"

    /** Every key the server will store. Anything else is refused. */
    val persisted: Set<String> = setOf(THEME_SELECTION, THEME_CUSTOM, PROJECT_PREFS)
}

/**
 * Everything the server holds for the caller, and who the caller is.
 *
 * @property userId the effective user these settings belong to, or null when
 *   nobody is signed in. Carried rather than inferred because the client uses it
 *   to answer "are these still the right settings?" without a second request:
 *   the shell is mounted once and the signed-in user can change underneath it —
 *   sign-in, sign-out, an admin starting or stopping impersonation — and this is
 *   the field that says the loaded settings now belong to somebody else. See
 *   ThemePersister.
 * @property settings key to blob, holding only the keys in
 *   [UiSettingKeys.persisted]. Absent keys are absent, not empty strings: "never
 *   chosen" has to stay distinguishable from "chosen, and the value is blank",
 *   because the first means "use the default" and the second does not.
 */
@Serializable
data class UiSettingsState(
    val userId: Long? = null,
    val settings: Map<String, String> = emptyMap(),
)

/**
 * Store one setting.
 *
 * One key per request rather than the whole map, because that is how the toolkit
 * writes: a theme change is two independent `Persister.write` calls, and
 * batching them here would mean this side buffering writes it does not own and
 * guessing when the batch is complete.
 *
 * @property key must be one of [UiSettingKeys.persisted]; anything else is a 400.
 * @property value the toolkit's blob, stored verbatim.
 */
@Serializable
data class SetUiSettingRequest(
    val key: String,
    val value: String,
)
