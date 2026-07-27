/**
 * Owns the user's tabs and what is in them.
 *
 * Lunicle's half of the lunula window model. The toolkit draws the strip,
 * the sidebar tree, the splits and the focus ring and reports gestures back as
 * ids; this decides what those gestures *mean* to a workspace of boards and
 * issues, and remembers the answer under the account.
 *
 * ── Nothing here fetches ────────────────────────────────────────────────────
 *
 * A workspace is a list of names. It says a tab holds project 7's board and
 * issue 402; it does not know what is on that board, whether that issue still
 * exists, or whether the reader may see either. Those are
 * [MainScreenBackingViewModel]'s business, and the bootstrap keeps the two in
 * step by reconciling one against the other — see `main.kt`. The one thing this
 * does ask the server for is its own stored blob.
 *
 * That separation is what makes the deep-link rules below testable as pure
 * functions over a [Workspace], and it is why a project the reader has lost
 * access to is pruned here rather than defended against everywhere: the
 * bootstrap hands over the accessible list, and panes naming anything else go.
 *
 * @see Workspace
 * @see PaneRef
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.UiSettingKeys

/**
 * How long a burst of changes is allowed to settle before it is stored.
 *
 * Dragging a tab across four positions is four state changes and one decision;
 * so is closing three panes in a row. Writing each would be three round trips
 * for a workspace nobody has finished rearranging. Short enough that a reload a
 * second after the last gesture still finds it.
 */
private const val SAVE_DEBOUNCE_MS = 600L

/**
 * @param storage the shared repository; the only collaborator.
 * @param scope the app scope. The debounced save runs in it, so it dies with
 *   the page rather than outliving it.
 */
class WorkspaceBackingViewModel(
    private val storage: StorageRepository,
    private val scope: CoroutineScope,
) {
    private val _stateFlow = MutableStateFlow(State())

    /** The current workspace, observed by the bootstrap. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * @property workspace the tabs and the active one. Empty before [restore]
     *   has run, and never empty afterwards — see [ensureNotEmpty].
     * @property isRestored whether the stored layout (or the seeded default) has
     *   been applied. The bootstrap waits for this before reconciling anything:
     *   acting on an empty workspace would look exactly like a user who had just
     *   closed every tab, and would persist that as their layout.
     */
    data class State(
        val workspace: Workspace = Workspace(),
        val isRestored: Boolean = false,
    )

    /**
     * The projects the reader can see, newest answer wins.
     *
     * Held rather than passed to each call because three separate decisions need
     * it — seeding the default, naming a tab after its board, and pruning panes
     * for a project that has gone away — and threading it through each would put
     * the same argument on half the methods here.
     */
    private var projects: List<ProjectSummary> = emptyList()

    /**
     * The account the loaded workspace belongs to, and whether one was ever
     * loaded.
     *
     * Two fields rather than a nullable one, for the reason
     * [MainScreenBackingViewModel] gives: null is a legitimate identity here —
     * signed out — and cannot also mean "never loaded".
     */
    private var loadedFor: Long? = null
    private var hasLoaded: Boolean = false

    /** The next tab id to hand out. Seeded past anything restored; see [restore]. */
    private var nextTabId: Int = 1

    /** The pending debounced save, cancelled and replaced by each new change. */
    private var saveJob: Job? = null

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * The signed-in user changed (or resolved for the first time): load their
     * workspace, or seed them a default.
     *
     * The design's rule, and it is deliberately one-way: **on sign-in the
     * signed-out layout is discarded**. There is nothing to reconcile — a
     * visitor's arrangement of public boards is not a draft of the account's
     * workspace, and merging the two would produce a layout neither of them
     * asked for. The only case that needs care is an account with nothing
     * stored, which gets the same default a visitor does.
     *
     * @param identity the effective user, or null for nobody.
     * @param isKnown whether the session has resolved. The first emission is
     *   "still asking", and loading then would be a request whose answer is
     *   always "signed out".
     */
    fun onSessionChanged(identity: Long?, isKnown: Boolean) {
        if (!isKnown) return
        if (hasLoaded && identity == loadedFor) return
        loadedFor = identity
        hasLoaded = true
        restore(identity)
    }

    /**
     * The accessible project list arrived or changed.
     *
     * Prunes panes naming a project that is no longer there — a project deleted,
     * or access to it withdrawn — and re-seeds when that leaves nothing. Cheap to
     * call on every tick: an unchanged list that prunes nothing emits nothing.
     */
    fun onProjectsChanged(projects: List<ProjectSummary>) {
        if (this.projects == projects) return
        this.projects = projects
        if (!_stateFlow.value.isRestored) return
        val allowed = projects.mapTo(mutableSetOf()) { it.id }
        val pruned = _stateFlow.value.workspace.let { ws ->
            ws.copy(
                tabs = ws.tabs.map { tab ->
                    val kept = tab.panes.filter { it.projectId in allowed }
                    if (kept.size == tab.panes.size) {
                        tab
                    } else {
                        tab.copy(
                            panes = kept,
                            activePaneId = tab.activePaneId?.takeIf { id -> kept.any { it.paneId == id } }
                                ?: kept.firstOrNull()?.paneId,
                        )
                    }
                },
            )
        }
        // A workspace whose every pane named a project that has gone is not a
        // workspace the user built any more; seeding is closer to what they meant
        // than a row of empty tabs would be.
        if (pruned.tabs.all { it.panes.isEmpty() } && projects.isNotEmpty()) {
            commit(defaultWorkspace())
        } else if (pruned != _stateFlow.value.workspace) {
            commit(pruned)
        }
    }

    /**
     * Throw the arrangement away and start again from the default.
     *
     * Offered in the profile dialog, because a window model with splits, hidden
     * panes and eight tabs has a state you can get lost in, and "put it back"
     * should not require finding every one of them. Stored immediately rather
     * than debounced: this is a deliberate, confirmed act, and a reload a beat
     * later must not find the layout it was just used to discard.
     */
    fun onRestoreDefaultLayout() {
        commit(defaultWorkspace(), saveNow = true)
    }

    // ── Tab gestures, straight from the toolkit ─────────────────────────────

    fun onTabSelected(tabId: String) {
        val ws = _stateFlow.value.workspace
        if (ws.activeTabId == tabId || ws.tabs.none { it.id == tabId }) return
        commit(ws.copy(activeTabId = tabId))
    }

    /** The "+" menu's New tab: an empty working set, activated so it can be filled. */
    fun onTabAdded() {
        val ws = _stateFlow.value.workspace
        val tab = WorkspaceTab(id = freshTabId(), name = "New tab")
        commit(ws.copy(tabs = ws.tabs + tab, activeTabId = tab.id))
    }

    /**
     * Close a tab, and everything in it.
     *
     * The last tab is replaced rather than removed: a workspace with no tabs has
     * no "+" to click and nowhere to put a board, so closing the last one would
     * be a way to lock yourself out of the app until a reload. Replacing it with
     * an empty one is the same gesture with a floor under it.
     */
    fun onTabClosed(tabId: String) {
        val ws = _stateFlow.value.workspace
        val index = ws.tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val remaining = ws.tabs.filterNot { it.id == tabId }
        if (remaining.isEmpty()) {
            val fresh = WorkspaceTab(id = freshTabId(), name = "New tab")
            commit(Workspace(tabs = listOf(fresh), activeTabId = fresh.id))
            return
        }
        // Land on the neighbour, preferring the one to the right — where the eye
        // already is when a tab closes under the cursor.
        val nextActive =
            if (ws.activeTabId == tabId) remaining[index.coerceAtMost(remaining.lastIndex)].id
            else ws.activeTabId
        commit(ws.copy(tabs = remaining, activeTabId = nextActive))
    }

    fun onTabRenamed(tabId: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val ws = _stateFlow.value.workspace
        if (ws.tabs.none { it.id == tabId }) return
        commit(ws.mapTab(tabId) { it.copy(name = clean) })
    }

    /**
     * A tab was dragged onto another.
     *
     * @param before whether it lands to the left of the target. Computed by the
     *   toolkit from where in the target the drop happened.
     */
    fun onTabReordered(sourceId: String, targetId: String, before: Boolean) {
        if (sourceId == targetId) return
        val ws = _stateFlow.value.workspace
        val source = ws.tabs.firstOrNull { it.id == sourceId } ?: return
        val without = ws.tabs.filterNot { it.id == sourceId }
        val targetIndex = without.indexOfFirst { it.id == targetId }
        if (targetIndex < 0) return
        val at = if (before) targetIndex else targetIndex + 1
        commit(ws.copy(tabs = without.toMutableList().apply { add(at, source) }))
    }

    // ── Pane gestures ───────────────────────────────────────────────────────

    /** A sidebar row was clicked: activate that pane, and its tab. */
    fun onPaneSelected(tabId: String, paneId: String) {
        val ws = _stateFlow.value.workspace
        val tab = ws.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.pane(paneId) == null) return
        if (ws.activeTabId == tabId && tab.activePaneId == paneId) return
        commit(ws.mapTab(tabId) { it.copy(activePaneId = paneId) }.copy(activeTabId = tabId))
    }

    /**
     * The focus moved to a pane the user pressed.
     *
     * Distinct from [onPaneSelected] in what it is allowed to change: a press
     * reports where focus *went*, so it must not also move the active tab (the
     * pane is in the active tab by construction — it is the one on screen).
     */
    fun onPaneFocused(paneId: String) {
        val ws = _stateFlow.value.workspace
        val tabId = ws.activeTabId ?: return
        val tab = ws.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.activePaneId == paneId || tab.pane(paneId) == null) return
        commit(ws.mapTab(tabId) { it.copy(activePaneId = paneId) })
    }

    /** A pane's × was clicked — or the thing it showed went away. */
    fun onPaneClosed(tabId: String, paneId: String) {
        val ws = _stateFlow.value.workspace
        val tab = ws.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.pane(paneId) == null) return
        commit(ws.mapTab(tabId) { it.withoutPane(paneId) })
    }

    /**
     * Drop an issue's pane from every tab it is in.
     *
     * The window is one thing however many places it was opened from, so closing
     * it closes all of them. Called by the bootstrap when the issue leaves
     * [MainScreenBackingViewModel.State.openIssues] — by a close, a delete, or a
     * discarded draft.
     */
    fun onIssueClosed(issueId: Long) {
        val paneId = issuePaneId(issueId)
        val ws = _stateFlow.value.workspace
        if (ws.tabs.none { it.pane(paneId) != null }) return
        commit(ws.copy(tabs = ws.tabs.map { it.withoutPane(paneId) }))
    }

    // ── Opening things ──────────────────────────────────────────────────────

    /**
     * Put [projectId]'s board in the tab the user is on — the "+" menu's
     * **New board**.
     *
     * Deliberately NOT [onBoardOpened], and the difference is the whole point of a
     * working set. "New board" is a statement about *this tab*: it is how a
     * release tab comes to hold the two projects the release spans. Jumping to
     * some other tab that already happens to show that board — which is right for
     * a link, where the user asked to *see* something — would make the menu unable
     * to express the one thing it exists for.
     *
     * A tab that already holds this board just focuses it; see `withPane`.
     */
    fun onBoardAdded(projectId: Long) {
        val ws = _stateFlow.value.workspace
        val pane = PaneRef.Board(projectId)
        val target = ws.activeTab ?: run {
            val fresh = WorkspaceTab(
                id = freshTabId(),
                name = projectName(projectId),
                panes = listOf(pane),
                activePaneId = pane.paneId,
            )
            commit(ws.copy(tabs = ws.tabs + fresh, activeTabId = fresh.id))
            return
        }
        commit(ws.mapTab(target.id) { it.withPane(pane) })
    }

    /**
     * Show [projectId]'s board.
     *
     * The design's project rule, in two lines:
     *
     *  1. a tab already shows that board — activate that tab, create nothing;
     *  2. otherwise — add a board pane to the current tab, and focus it.
     *
     * Rule 1 is what keeps "click a project" idempotent: pressing the same entry
     * twice does not end up with the same board twice, here or in another tab.
     */
    fun onBoardOpened(projectId: Long) {
        val ws = _stateFlow.value.workspace
        val pane = PaneRef.Board(projectId)
        val existing = ws.tabs.firstOrNull { it.hasBoardFor(projectId) }
        if (existing != null) {
            commit(
                ws.mapTab(existing.id) { it.copy(activePaneId = pane.paneId) }
                    .copy(activeTabId = existing.id),
            )
            return
        }
        val target = ws.activeTab ?: run {
            val fresh = WorkspaceTab(id = freshTabId(), name = projectName(projectId))
            commit(
                ws.copy(
                    tabs = ws.tabs + fresh.copy(panes = listOf(pane), activePaneId = pane.paneId),
                    activeTabId = fresh.id,
                ),
            )
            return
        }
        commit(ws.mapTab(target.id) { it.withPane(pane) })
    }

    /**
     * Show an issue.
     *
     * The design's issue rule:
     *
     *  1. already open somewhere — activate it rather than opening a second copy;
     *  2. another tab holds a board for its project — open it there and activate
     *     that tab, so the issue lands next to the context it belongs to;
     *  3. otherwise — open it in the current tab.
     *
     * Rule 2 is the one that earns its keep. An issue arriving from a
     * notification, a ticket reference or a deep link has a home even when the
     * reader is looking at something else, and putting it there beats dropping it
     * wherever they happen to be standing.
     */
    fun onIssueOpened(issueId: Long, projectId: Long) {
        val ws = _stateFlow.value.workspace
        val pane = PaneRef.Issue(issueId, projectId)
        val already = ws.tabs.firstOrNull { it.pane(pane.paneId) != null }
        if (already != null) {
            commit(
                ws.mapTab(already.id) { it.copy(activePaneId = pane.paneId) }
                    .copy(activeTabId = already.id),
            )
            return
        }
        // The active tab wins when it already holds the board, so an issue opened
        // from a board never jumps to another tab that happens to hold one too.
        val host = ws.activeTab?.takeIf { it.hasBoardFor(projectId) }
            ?: ws.tabs.firstOrNull { it.hasBoardFor(projectId) }
            ?: ws.activeTab
            ?: return
        commit(ws.mapTab(host.id) { it.withPane(pane) }.copy(activeTabId = host.id))
    }

    // ── Restore, seed, persist ──────────────────────────────────────────────

    /**
     * Load the stored workspace for [identity], or seed the default.
     *
     * Signed out there is nothing stored and nothing to store, so the default is
     * seeded straight away — which still works, because the accessible project
     * list a visitor gets is simply shorter.
     */
    private fun restore(identity: Long?) {
        if (identity == null) {
            commit(defaultWorkspace(), markRestored = true, save = false)
            return
        }
        scope.launch {
            val stored = runCatching { storage.uiSettings().settings[UiSettingKeys.WORKSPACE] }
                .getOrNull()
                ?.let(WorkspaceCodec::decode)
            // A stored workspace is trusted about its own shape but not about the
            // ids inside it, which is why the fresh-id counter is seeded past
            // whatever it holds: reusing one would make a new tab collide with a
            // restored one, and the toolkit's geometry is keyed by tab id.
            if (stored != null) {
                nextTabId = stored.tabs
                    .mapNotNull { it.id.removePrefix(TAB_ID_PREFIX).toIntOrNull() }
                    .maxOrNull()
                    ?.plus(1)
                    ?: 1
                // Restored, but not yet checked against what this account can
                // still see — onProjectsChanged does that when the list arrives.
                commit(ensureNotEmpty(stored), markRestored = true, save = false)
            } else {
                commit(defaultWorkspace(), markRestored = true, save = false)
            }
        }
    }

    /**
     * The layout somebody gets before they have arranged anything: one tab per
     * accessible project, each holding that project's board.
     *
     * So the first thing anyone meets is the familiar one-project-per-view model.
     * Working sets are something they discover by dragging a second board into a
     * tab, not something they must build before the app is usable.
     */
    private fun defaultWorkspace(): Workspace {
        nextTabId = 1
        val tabs = projects.map { project ->
            WorkspaceTab(
                id = freshTabId(),
                name = project.name,
                panes = listOf(PaneRef.Board(project.id)),
                activePaneId = PaneRef.Board(project.id).paneId,
            )
        }
        return ensureNotEmpty(Workspace(tabs = tabs, activeTabId = tabs.firstOrNull()?.id))
    }

    /** A workspace with no tabs has nowhere to put anything; give it one. */
    private fun ensureNotEmpty(workspace: Workspace): Workspace {
        if (workspace.tabs.isNotEmpty()) {
            return workspace.copy(
                activeTabId = workspace.activeTabId?.takeIf { id -> workspace.tabs.any { it.id == id } }
                    ?: workspace.tabs.first().id,
            )
        }
        val fresh = WorkspaceTab(id = freshTabId(), name = "New tab")
        return Workspace(tabs = listOf(fresh), activeTabId = fresh.id)
    }

    private fun freshTabId(): String = "$TAB_ID_PREFIX${nextTabId++}"

    private fun projectName(projectId: Long): String =
        projects.firstOrNull { it.id == projectId }?.name ?: "New tab"

    /**
     * Publish a new workspace and, unless told otherwise, remember it.
     *
     * @param save whether this change is the user's and should be stored. False
     *   for a restore, which is the stored state arriving rather than changing —
     *   writing it back would be a round trip that can only ever store what it
     *   just read, and on the signed-out path would be a request with nowhere to
     *   land.
     * @param saveNow whether to skip the debounce. For a deliberate, confirmed
     *   act (see [onRestoreDefaultLayout]) rather than a gesture in a burst.
     */
    private fun commit(
        workspace: Workspace,
        markRestored: Boolean = false,
        save: Boolean = true,
        saveNow: Boolean = false,
    ) {
        val next = _stateFlow.value.copy(
            workspace = workspace,
            isRestored = _stateFlow.value.isRestored || markRestored,
        )
        if (next == _stateFlow.value) return
        _stateFlow.value = next
        if (save) persist(immediate = saveNow)
    }

    private fun persist(immediate: Boolean) {
        // Signed out, there is nowhere to put it. Not an error and not worth a
        // request: the visitor keeps their arrangement for as long as the page
        // lives, which is the whole of what a signed-out session is.
        if (loadedFor == null) return
        saveJob?.cancel()
        val blob = WorkspaceCodec.encode(_stateFlow.value.workspace)
        saveJob = scope.launch {
            if (!immediate) delay(SAVE_DEBOUNCE_MS)
            runCatching { storage.setUiSetting(UiSettingKeys.WORKSPACE, blob) }
        }
    }

    private companion object {
        /**
         * The prefix every generated tab id carries.
         *
         * Load-bearing on restore, which reads the counter back out of the ids it
         * finds so a new tab cannot collide with a restored one — see [restore].
         */
        const val TAB_ID_PREFIX = "t"
    }
}

/**
 * This tab with [pane] in it and focused — or, if it is already there, just
 * focused.
 *
 * The "one pane per thing per tab" rule from [PaneRef]'s header, in the one
 * place that could break it.
 */
private fun WorkspaceTab.withPane(pane: PaneRef): WorkspaceTab =
    if (this.pane(pane.paneId) != null) {
        copy(activePaneId = pane.paneId)
    } else {
        copy(panes = panes + pane, activePaneId = pane.paneId)
    }

/** This tab without that pane, and with focus moved off it if it had it. */
private fun WorkspaceTab.withoutPane(paneId: String): WorkspaceTab {
    val kept = panes.filterNot { it.paneId == paneId }
    if (kept.size == panes.size) return this
    return copy(
        panes = kept,
        activePaneId = activePaneId?.takeUnless { it == paneId } ?: kept.lastOrNull()?.paneId,
    )
}
