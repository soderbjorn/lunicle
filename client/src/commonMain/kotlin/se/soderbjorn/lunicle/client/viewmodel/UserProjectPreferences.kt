/**
 * The signed-in user's own view choices, per project — the client-side reading of
 * the [UiSettingKeys.PROJECT_PREFS] blob.
 *
 * The server stores that key verbatim and never looks inside it (see
 * UiSettingsRoutes' note about the one route whose body it does not parse). This
 * file is the parser at the other end: one blob in, a per-project map of
 * preferences out, and back again.
 *
 * ── Why one blob for every project ──────────────────────────────────────────
 *
 * A user has one of these, not one per project, and it holds every project's
 * record keyed by project id. That is a deliberate trade against a key-per-project
 * or a project_id column on the settings table: the whole of a user's board
 * preferences then arrives in the single `uiSettings()` read the app already makes
 * and departs in a single write, and a new kind of per-user-per-project choice is
 * a field on [UserProjectPrefs] rather than another allowlisted key, another
 * server round-trip, and another schema change. The cost is that saving one
 * project's choice rewrites the blob that holds them all — which at a few tens of
 * bytes per project is nothing next to the 256 KB the route already tolerates.
 *
 * ── Totality ────────────────────────────────────────────────────────────────
 *
 * [decode] never throws. A blob that is absent, blank, truncated or written by a
 * newer client than this one yields an empty map — "this user has no preferences
 * I can read", which is exactly the same outcome as a user who has set none, and
 * the same defaulting the theme snapshot does with a malformed appearance. A
 * stored preference the code cannot parse must degrade to the default board, never
 * to a boot failure.
 *
 * @see se.soderbjorn.lunicle.clientserver.UiSettingKeys.PROJECT_PREFS
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One project's preferences for one user.
 *
 * Open on purpose: today it is only [hiddenColumnIds], but it is the record a
 * future per-user-per-project choice grows a field on. Every field defaults, so a
 * blob written before a field existed decodes to that field's default rather than
 * failing — which is what lets this type gain fields without a version bump.
 *
 * @property hiddenColumnIds the ids of the statuses this user has hidden on this
 *   project's board (LNL-100). Ids, not names, so the choice survives a column
 *   rename. Order is not meaningful — the board draws hidden columns in their own
 *   board order, not this list's — so it is compared as a set on the way in.
 *
 * `hideIssueNumbers` was the second field here (LNL-105) and is **gone** (LNL-194):
 * hiding the FOO-123 key describes how a shared board reads, so it moved onto the
 * project row and became an administrator's switch. Removing it from this record is
 * what retires the old rows without a pass over every account — `decode` ignores
 * unknown keys and `encode` does not emit them, so the next time anybody hides a
 * column their blob loses the field on the way through. The values were copied onto
 * the projects first; see the server's copyBoardDisplayFromOwners.
 */
@Serializable
data class UserProjectPrefs(
    val hiddenColumnIds: List<Long> = emptyList(),
)

/**
 * The stored shape: a map from project id to that project's record.
 *
 * Wrapped in a named object with one field rather than being a bare
 * `Map<Long, UserProjectPrefs>` at the top level, so the JSON has somewhere to
 * grow a sibling should a preference ever be about the user across all projects
 * rather than within one.
 */
@Serializable
private data class ProjectPrefsBlob(
    val byProject: Map<Long, UserProjectPrefs> = emptyMap(),
)

/** Reads and writes the [UiSettingKeys.PROJECT_PREFS] blob. Total by contract. */
object UserProjectPreferences {

    /**
     * `ignoreUnknownKeys` so a newer client's extra fields do not fail an older
     * one; `encodeDefaults` so a record whose only field is at its default still
     * round-trips as itself rather than as `{}`.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Parse the stored blob into a per-project map, or the empty map for anything
     * this code cannot read. Never throws — see the file header.
     */
    fun decode(blob: String?): Map<Long, UserProjectPrefs> {
        val text = blob?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return runCatching { json.decodeFromString<ProjectPrefsBlob>(text).byProject }
            .getOrDefault(emptyMap())
    }

    /**
     * Render a per-project map back to the blob the server stores.
     *
     * Projects with an empty record are dropped first: an entry that hides nothing
     * is indistinguishable from absence to every reader, so keeping it would only
     * grow the blob and leave "has this user any preference here?" answered "yes"
     * where the honest answer is no. See the view model, which prunes on the way
     * to calling this so the blob shrinks back to `{}` when the last column is
     * shown again.
     */
    fun encode(byProject: Map<Long, UserProjectPrefs>): String {
        val pruned = byProject.filterValues { it != UserProjectPrefs() }
        return json.encodeToString(ProjectPrefsBlob(pruned))
    }
}
