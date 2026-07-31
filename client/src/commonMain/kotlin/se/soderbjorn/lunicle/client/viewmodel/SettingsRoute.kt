/**
 * Where in the settings pane somebody is, as a value.
 *
 * There is **one** settings surface since LNL-193 — the instance dialog, the
 * project dialog and the profile dialog converged onto it — so "open settings"
 * stopped being a choice of dialog and became a position inside one pane. This
 * type is that position: which tab, which project the Projects tab is showing,
 * and which section of it.
 *
 * It lives in the client rather than in the web module because it is what the
 * address bar carries (see [se.soderbjorn.lunicle.client.nextSearch]) and what
 * every entry point names — the board's gear, the account corner, a deep link.
 * A view that owned it could not be linked to.
 *
 * ── Why the sections are strings ────────────────────────────────────────────
 *
 * [SettingsRoute.section] is a plain key rather than an enum because the sections
 * are ticket 4's and 5's to name, and a route that resolves to a tab is enough for
 * the shell: an unrecognised section selects nothing and the tab opens at its
 * first section, which is exactly what should happen to a stale bookmark.
 *
 * @see SettingsTab
 */
package se.soderbjorn.lunicle.client.viewmodel

/**
 * The settings pane's tabs, in strip order.
 *
 * The order is the design's and is the same for everybody: a caller who holds
 * nothing an instance tab can act on simply does not see it, and the tabs they do
 * see stay where they were. There is deliberately no second layout for a member —
 * You then Projects is the same strip with three buttons hidden.
 *
 * @property key what the address bar spells it, and therefore wire format: a
 *   bookmark outlives a rename of the label beside it.
 * @property label what the strip draws.
 */
enum class SettingsTab(val key: String, val label: String) {
    /** Your account: name, address, layout, and the agents allowed to act as you. */
    YOU("you", "You"),

    /** Who may hold an account here at all, and what a fresh one arrives holding. */
    ACCESS("access", "Who gets in"),

    /** The account directory: who exists, and what each of them holds. */
    PEOPLE("people", "People"),

    /** Every project, and everything about the one selected. */
    PROJECTS("projects", "Projects"),

    /** The deployment itself — the switches that are true for everybody. */
    INSTANCE("instance", "Instance"),
    ;

    companion object {
        /**
         * The tab [key] names, or null.
         *
         * Null rather than a default, so a caller can tell "no `?settings=` at all"
         * (the pane is not open) from "a `?settings=` naming something this build
         * does not have" — the second is a stale link and lands on [YOU], but that
         * is the *pane's* decision to make and not this function's.
         */
        fun byKey(key: String?): SettingsTab? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One position in the settings pane.
 *
 * @property tab which tab is showing.
 * @property projectId which project the Projects tab has selected, or null —
 *   meaningless on the other four tabs, and carried anyway rather than being
 *   dropped, so that going to Instance and back does not forget which project was
 *   being looked at.
 * @property section which section of the selected project (or of the tab), by key,
 *   or null for "the first one". Ticket 4 owns the rail that draws these; until
 *   then it is carried, written to the URL and otherwise unread.
 */
data class SettingsRoute(
    val tab: SettingsTab = SettingsTab.YOU,
    val projectId: Long? = null,
    val section: String? = null,
) {
    companion object {
        /** A project's own settings, at the section a caller asked for. */
        fun project(projectId: Long, section: String? = null): SettingsRoute =
            SettingsRoute(SettingsTab.PROJECTS, projectId, section)
    }
}

/** The key of the section the board's "Manage access" affordance asks for. */
const val SETTINGS_SECTION_ACCESS: String = "access"

/** The key of a project's General section — where the board's gear lands. */
const val SETTINGS_SECTION_GENERAL: String = "general"
