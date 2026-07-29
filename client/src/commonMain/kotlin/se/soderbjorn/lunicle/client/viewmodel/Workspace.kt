/**
 * What a Lunicle tab is made of, and how it is stored.
 *
 * The whole of Lunicle's contribution to the lunula shell's window model.
 * The toolkit owns tabs, panes, the sidebar, focus, splits and drag-reorder;
 * Lunicle owns exactly one question — *what can a pane contain* — and this file
 * is the answer: a board for some project, or an issue.
 *
 * ── Why the ids are derived rather than stored ──────────────────────────────
 *
 * The toolkit deals in opaque pane id strings: it hands one back on a sidebar
 * click, asks for content by one, reports a close by one. Lunicle deals in
 * [PaneRef]s. Rather than keep a map between the two, the id is a *function* of
 * the ref — `board-7`, `issue-402` — so the translation is total in both
 * directions and there is no table to fall out of step with the tabs. It also
 * makes the toolkit's own persisted geometry (which is keyed by pane id) survive
 * a reload for free: the same board gets the same id next time.
 *
 * A consequence worth stating: **one pane per thing per tab**. Two board panes
 * for the same project in one tab would collide on the id, so the model refuses
 * the second and activates the first instead — which is the behaviour anybody
 * would want anyway, and the deep-link rules below already say so.
 *
 * @see WorkspaceBackingViewModel
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one pane holds.
 *
 * Four cases, which is the whole of the pane vocabulary. Adding a fifth (a
 * forum, a conversation) is a case here plus a branch in the shell's content
 * factory, and nothing else — no new tab type, no new route, no new navigation.
 *
 * [Settings] and [Analytics] are the reason that claim is worth making. Both
 * were modal dialogs: a surface you could only be in one of, that covered the
 * board you opened it from, and that no arrangement could keep. As panes they
 * sit beside the board, appear in the sidebar, and come back with the layout —
 * for the cost of one case each.
 */
@Serializable
sealed interface PaneRef {
    /** The toolkit's id for this pane. Stable across reloads; see the file header. */
    val paneId: String

    /**
     * The project this pane belongs to — the board's own, or the issue's.
     *
     * On the interface rather than on each case because it is the one question
     * asked of a pane without caring which kind it is: which boards does this
     * workspace need loaded.
     */
    val projectId: Long

    /**
     * A project's board.
     *
     * @property projectId the project whose board this shows. The pane's whole
     *   argument — everything else about a board (its columns, its filter, its
     *   sprint scope) belongs to the board, not to the pane that frames it.
     */
    @Serializable
    @SerialName("board")
    data class Board(override val projectId: Long) : PaneRef {
        override val paneId: String get() = "$BOARD_PANE_PREFIX$projectId"
    }

    /**
     * One issue, in a window.
     *
     * @property issueId the issue shown.
     * @property projectId the project it belongs to. Redundant with the issue in
     *   the sense that the server could be asked — and load-bearing anyway,
     *   because it is what makes a *restored* issue pane openable: on a fresh
     *   load nothing knows anything about issue 402 yet, and the window cannot be
     *   built without its project's board (statuses, priorities, permissions all
     *   come from there). Storing the project turns "restore this pane" into a
     *   board to fetch rather than a lookup to invent.
     */
    @Serializable
    @SerialName("issue")
    data class Issue(val issueId: Long, override val projectId: Long) : PaneRef {
        override val paneId: String get() = "$ISSUE_PANE_PREFIX$issueId"
    }

    /**
     * A project's settings.
     *
     * The dialog this replaced covered the board it was opened from, which is
     * the wrong shape for a surface people move back and forth to — renaming a
     * column and then looking at what it did to the board meant closing the
     * thing, looking, and opening it again. As a pane it sits beside the board
     * and both are on screen at once.
     */
    @Serializable
    @SerialName("settings")
    data class Settings(override val projectId: Long) : PaneRef {
        override val paneId: String get() = "$SETTINGS_PANE_PREFIX$projectId"
    }

    /** A project's analytics — [Settings]' argument, for the same surface. */
    @Serializable
    @SerialName("analytics")
    data class Analytics(override val projectId: Long) : PaneRef {
        override val paneId: String get() = "$ANALYTICS_PANE_PREFIX$projectId"
    }

    companion object {
        const val BOARD_PANE_PREFIX: String = "board-"
        const val ISSUE_PANE_PREFIX: String = "issue-"
        const val SETTINGS_PANE_PREFIX: String = "settings-"
        const val ANALYTICS_PANE_PREFIX: String = "analytics-"
    }
}

/**
 * The pane id an issue's window has, wherever it is open.
 *
 * The id does not depend on the issue's project — only [PaneRef.Issue] carries
 * that, and only so a restored pane can be rebuilt — so "which pane is issue
 * 402's" is answerable without knowing anything else about it.
 */
fun issuePaneId(issueId: Long): String = "${PaneRef.ISSUE_PANE_PREFIX}$issueId"

/** The issue a pane shows, or null when it is not an issue pane. */
fun issueIdOfPane(paneId: String): Long? =
    paneId.removePrefix(PaneRef.ISSUE_PANE_PREFIX)
        .takeIf { it != paneId }
        ?.toLongOrNull()

/** The project whose board a pane shows, or null when it is not a board pane. */
fun boardProjectIdOfPane(paneId: String): Long? =
    paneId.removePrefix(PaneRef.BOARD_PANE_PREFIX)
        .takeIf { it != paneId }
        ?.toLongOrNull()

/** The project whose settings a pane shows, or null when it is not a settings pane. */
fun settingsProjectIdOfPane(paneId: String): Long? =
    paneId.removePrefix(PaneRef.SETTINGS_PANE_PREFIX)
        .takeIf { it != paneId }
        ?.toLongOrNull()

/** The project whose analytics a pane shows, or null when it is not an analytics pane. */
fun analyticsProjectIdOfPane(paneId: String): Long? =
    paneId.removePrefix(PaneRef.ANALYTICS_PANE_PREFIX)
        .takeIf { it != paneId }
        ?.toLongOrNull()

/**
 * One tab: a working set.
 *
 * A release, a triage session, a customer — not a project. Two boards from
 * different projects and the issue being read between them is an ordinary tab,
 * and is the arrangement the whole model exists to allow.
 *
 * @property id stable identifier, ours; the toolkit reports it back verbatim.
 * @property name what the strip shows. Renamable, so it is the user's sentence
 *   rather than a derived one — a tab holding two projects has no name a
 *   computer could pick.
 * @property panes what is open in it, in the order they were opened. Where they
 *   *sit* is the toolkit's business (see `UiSettingKeys.LAYOUT_STATE`); this is
 *   only which of them exist.
 * @property activePaneId the focused pane, or null for none. A pane id rather
 *   than a [PaneRef] because it is compared against what the toolkit reports.
 * @property paneLabels titles the user has typed over a pane's derived one,
 *   keyed by pane id. Absent for every pane by default, which is the point: a
 *   Lunicle pane title is *computed* — "Board · Meridian", an issue's ticket key
 *   — and stays computed, following a project rename or an issue retitle, until
 *   somebody says otherwise. This map is the somebody.
 *
 *   Per **tab**, not per workspace, and deliberately: a pane id here names what
 *   the pane shows rather than one placement of it (see the file header), so
 *   `board-7` is the same id in every tab that holds that board. Naming the copy
 *   in the release tab "Ship blockers" must not rename the copy in the triage
 *   tab, which a workspace-wide map would.
 *
 *   Stored with the rest of the workspace, so a renamed window comes back
 *   renamed. The toolkit persists where a window *sits*; what it is called was
 *   never its to keep.
 */
@Serializable
data class WorkspaceTab(
    val id: String,
    val name: String,
    val panes: List<PaneRef> = emptyList(),
    val activePaneId: String? = null,
    val paneLabels: Map<String, String> = emptyMap(),
) {
    /** The pane with that id, or null. */
    fun pane(paneId: String): PaneRef? = panes.firstOrNull { it.paneId == paneId }

    /** Whether this tab holds a board for [projectId]. */
    fun hasBoardFor(projectId: Long): Boolean =
        panes.any { it is PaneRef.Board && it.projectId == projectId }

    /**
     * The user's title for [paneId] in this tab, or null when they have given it
     * none and the derived title stands.
     *
     * Called by the shell's `paneLabel` before it computes anything, and by
     * [WorkspaceBackingViewModel.onPaneMovedToTab] so a window carries its name
     * with it.
     *
     * @param paneId the pane to look up.
     * @return the override, or null.
     */
    fun paneLabel(paneId: String): String? = paneLabels[paneId]
}

/**
 * Every tab, and which one is showing.
 *
 * @property tabs ordered; empty is a real state only before the first restore
 *   has run (see [WorkspaceBackingViewModel], which never leaves it empty
 *   afterwards).
 * @property activeTabId a member of [tabs], or null when there are none.
 */
@Serializable
data class Workspace(
    val tabs: List<WorkspaceTab> = emptyList(),
    val activeTabId: String? = null,
) {
    /** The showing tab, or null. */
    val activeTab: WorkspaceTab? get() = tabs.firstOrNull { it.id == activeTabId }

    /** Every project any pane refers to — the boards that have to be loaded. */
    val referencedProjectIds: Set<Long>
        get() = tabs.flatMapTo(mutableSetOf()) { tab -> tab.panes.map { it.projectId } }

    /** Every issue with a pane somewhere, paired with its project. */
    val openIssuePanes: List<PaneRef.Issue>
        get() = tabs.flatMap { tab -> tab.panes.filterIsInstance<PaneRef.Issue>() }.distinctBy { it.issueId }

    /** Replace one tab, by id, leaving the rest alone. */
    fun mapTab(tabId: String, transform: (WorkspaceTab) -> WorkspaceTab): Workspace =
        copy(tabs = tabs.map { if (it.id == tabId) transform(it) else it })
}

/**
 * The `lunicle.workspace.v1` blob, read and written.
 *
 * Wrapped in a versioned envelope rather than serialising [Workspace] directly,
 * for the reason `UserProjectPreferences` gives: the stored shape and the
 * in-memory shape are allowed to diverge, and a `version` that nothing reads yet
 * is what makes the first divergence a migration rather than a loss.
 *
 * Never throws. A blob this code cannot read is treated as no blob at all — the
 * user gets the default layout, which is a bad day rather than a broken app, and
 * is the only honest response to bytes we do not understand.
 */
object WorkspaceCodec {

    /** The version this client writes. Read but not yet branched on. */
    private const val VERSION = 1

    @Serializable
    private data class Blob(
        val version: Int = VERSION,
        val workspace: Workspace = Workspace(),
    )

    /**
     * `ignoreUnknownKeys` so a newer client's extra fields do not fail an older
     * one; `encodeDefaults` so a tab whose only interesting field is at its
     * default still round-trips as itself.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    /** Parse the stored blob, or null for absent / unreadable. */
    fun decode(blob: String?): Workspace? {
        val text = blob?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { json.decodeFromString<Blob>(text).workspace }
            .getOrNull()
            ?.takeIf { it.tabs.isNotEmpty() }
    }

    /** Render a workspace to the blob the server stores. */
    fun encode(workspace: Workspace): String = json.encodeToString(Blob(workspace = workspace))
}
