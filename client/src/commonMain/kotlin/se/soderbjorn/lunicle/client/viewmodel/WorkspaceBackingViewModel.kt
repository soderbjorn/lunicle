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
     *   closed every tab, and would persist that as their layout. Goes back to
     *   false while the account changes under it — see [onSessionChanged] — since
     *   what is on screen at that moment belongs to whoever just left.
     * @property isSettled whether the layout on screen is this identity's last
     *   word — that is, whether a stored workspace is still in flight behind it.
     *   Deliberately NOT [isRestored], which goes true as soon as *something* is
     *   showable and is usually satisfied by the seeded default: the stored
     *   layout lands after it and replaces the whole workspace, active tab
     *   included. Anything that opens a board or an issue in answer to something
     *   OUTSIDE the workspace — the boot deep links, `?projectId=` / `?issue=`
     *   (LNL-165) — has to wait for this one, or it is applied to a layout that
     *   is about to be thrown away. Signed out there is nothing stored and so
     *   nothing to wait for.
     */
    data class State(
        val workspace: Workspace = Workspace(),
        val isRestored: Boolean = false,
        val isSettled: Boolean = false,
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

    /**
     * Whether the project list on file is still the previous account's.
     *
     * Set the moment the identity changes and cleared by the first
     * [onProjectsChanged] after it, which is the first list that can be said to
     * belong to whoever is asking now. While it is true nothing may be seeded —
     * see [restore], which does not seed at all and leaves that to the list.
     */
    private var awaitingProjects: Boolean = false

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
        // Forget the previous account before restoring — all three of these are the
        // same statement, made about the three things that outlive a sign-out.
        //
        // [projects], because the bootstrap hands the new identity over here before
        // the new list has arrived: what is held right now describes what the person
        // who just left could see. A [defaultWorkspace] built from it seeds a
        // signed-out visitor a tab per PRIVATE project, which is how a project's
        // name survived a sign-out in the tab strip that was supposed to forget it.
        //
        // [awaitingProjects], because emptying the list is not the same as knowing
        // it — "nobody may see anything" is a layout too, and seeding one from a
        // list nobody has answered for yet would be the same guess with a different
        // wrong answer. Nothing is seeded until [onProjectsChanged] says what this
        // identity can actually reach.
        //
        // [State.isRestored], because a restore is a fetch and the list is another
        // one, and either can land first. False here means "mid-switch": the
        // bootstrap stops reconciling against a layout that is about to be replaced
        // wholesale, and what is on screen is nobody's workspace until one of the
        // two answers arrives.
        //
        // [State.isSettled] for a narrower reason than its neighbour: it says
        // whether a fetch for a STORED layout is still outstanding, and signed out
        // there is none to make — the default the list seeds is already the last
        // word. Signed in it stays false until [restore] answers, however the
        // default gets seeded in the meantime.
        projects = emptyList()
        awaitingProjects = true
        _stateFlow.value = _stateFlow.value.copy(isRestored = false, isSettled = identity == null)
        restore(identity)
    }

    /**
     * The accessible project list arrived or changed.
     *
     * Prunes panes naming a project that is no longer there — a project deleted,
     * access to it withdrawn, or a sign-out that took the whole private half of
     * the list with it — drops the tabs that leaves hollow, and re-seeds when
     * that leaves nothing. Cheap to call on every tick: an unchanged list that
     * prunes nothing emits nothing.
     */
    fun onProjectsChanged(projects: List<ProjectSummary>) {
        // The `awaitingProjects` disjunct is what makes an *unchanged* list still
        // count as an answer. A visitor who can see nothing and an account that
        // could see nothing produce the same empty list, and without this the
        // identity change between them would be waiting on an emission that never
        // comes — leaving an app with no tabs at all.
        if (this.projects == projects && !awaitingProjects) return
        this.projects = projects
        if (awaitingProjects) {
            awaitingProjects = false
            // The first list for this identity, and the one [restore] declined to
            // guess at. If the stored workspace has not landed (or there is none,
            // or nobody is signed in), this is where the default finally gets
            // built — from a list that is actually theirs. If it HAS landed,
            // isRestored is already true and the stored layout stands; pruning it
            // is then the only thing this list has to say.
            if (!_stateFlow.value.isRestored) {
                commit(defaultWorkspace(), markRestored = true, save = false)
                return
            }
        }
        pruneToAccessible()
    }

    /**
     * Drop everything in the workspace that names a project [projects] does not
     * have, and re-seed if that leaves nothing behind.
     *
     * Its own method because two paths need it and neither is "the list changed":
     * [onProjectsChanged], where the list is the news, and [restore], where the
     * stored layout is the news and the list may already be sitting here. A
     * restore that skipped this would put back a pane for a project the account
     * lost access to since it was stored — and, because the list that would have
     * caught it has already been delivered, nothing would come along to prune it.
     *
     * A no-op before the workspace is restored, and a no-op while
     * [awaitingProjects] — pruning against a list that is not this identity's
     * would clear the workspace against the wrong answer.
     */
    private fun pruneToAccessible() {
        if (awaitingProjects || !_stateFlow.value.isRestored) return
        val allowed = projects.mapTo(mutableSetOf()) { it.id }
        val previous = _stateFlow.value.workspace
        val pruned = ensureNotEmpty(
            previous.copy(
                tabs = previous.tabs.mapNotNull { tab ->
                    val kept = tab.panes.filter { it.projectId in allowed }
                    when {
                        kept.size == tab.panes.size -> tab
                        // Every pane in it named something that has gone, so the tab
                        // is not a working set any more — it is a label for work
                        // the reader cannot reach, and a tab strip still carrying
                        // "Acme rollout" after sign-out tells a visitor the name of a
                        // project they have no business knowing. Note the branch
                        // above: a tab the user emptied *themselves* had no panes to
                        // lose, so it lands there and is kept.
                        kept.isEmpty() -> null
                        else -> tab.copy(
                            panes = kept,
                            activePaneId = tab.activePaneId?.takeIf { id -> kept.any { it.paneId == id } }
                                ?: kept.firstOrNull()?.paneId,
                        )
                    }
                },
            ),
        )
        // A workspace whose every pane named a project that has gone is not a
        // workspace the user built any more; seeding is closer to what they meant
        // than a row of empty tabs would be.
        if (pruned.tabs.all { it.panes.isEmpty() } && projects.isNotEmpty()) {
            commit(defaultWorkspace())
        } else if (pruned != previous) {
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
     * Drop a pane from every tab it is in, by id.
     *
     * For a surface that closes itself rather than being closed: a settings form
     * whose Cancel was pressed, a project deleted out from under its own pane.
     * "Everywhere" because a pane id names one thing however many tabs it was
     * opened into, so closing it closes all of them.
     */
    fun onPaneClosedEverywhere(paneId: String) {
        val ws = _stateFlow.value.workspace
        if (ws.tabs.none { it.pane(paneId) != null }) return
        commit(ws.copy(tabs = ws.tabs.map { it.withoutPane(paneId) }))
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
     * **Open board**.
     *
     * Deliberately NOT [onBoardOpened], and the difference is the whole point of a
     * working set. "Open board" is a statement about *this tab*: it is how a
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
     * Open a project surface — its settings, its analytics — as a pane.
     *
     * The rule the design states, which is the issue rule minus the board
     * affinity: already open in this tab, focus it; open in another tab,
     * activate that tab; otherwise add it to the tab you are on. A second click
     * never opens a second copy, which the derived pane id (see [PaneRef]) makes
     * structural rather than something this has to remember.
     *
     * Deliberately NOT [onBoardAdded]'s always-here rule: a settings pane is one
     * surface per project, not something you would want two of in two tabs, so
     * finding the open one beats making another.
     */
    fun onProjectPaneOpened(pane: PaneRef) {
        val ws = _stateFlow.value.workspace
        val already = ws.tabs.firstOrNull { it.pane(pane.paneId) != null }
        if (already != null) {
            commit(
                ws.mapTab(already.id) { it.copy(activePaneId = pane.paneId) }
                    .copy(activeTabId = already.id),
            )
            return
        }
        val target = ws.activeTab ?: run {
            val fresh = WorkspaceTab(
                id = freshTabId(),
                name = projectName(pane.projectId),
                panes = listOf(pane),
                activePaneId = pane.paneId,
            )
            commit(ws.copy(tabs = ws.tabs + fresh, activeTabId = fresh.id))
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
     * Load the stored workspace for [identity], if there is one.
     *
     * Deliberately only half the job: nothing here seeds a default. Signed out
     * there is nothing stored to ask for, and a signed-in account may have
     * nothing stored either, and in both cases the layout to fall back on is one
     * tab per accessible project — which cannot be built until the accessible
     * projects are known. So both of those cases return having committed nothing
     * and leave the seeding to [onProjectsChanged], which is where the answer
     * arrives. Seeding here instead is what dressed a signed-out visitor in the
     * previous account's tabs: this runs on the identity change itself, a beat
     * before the list that change invalidates.
     */
    private fun restore(identity: Long?) {
        if (identity == null) return
        scope.launch {
            val stored = runCatching { storage.uiSettings().settings[UiSettingKeys.WORKSPACE] }
                .getOrNull()
                ?.let(WorkspaceCodec::decode)
            // The identity may have changed while this was in flight — a sign-out
            // a beat after a sign-in — and this answer is the previous account's
            // layout. Applying it would dress whoever is here now in it, and
            // marking it settled would say the wrong fetch had answered.
            if (loadedFor != identity) return@launch
            // Nothing stored is not a failure and not an empty workspace — it is an
            // account that has never arranged anything, and what it gets is the
            // default. Left to the project list, for the reason in the header: this
            // is the one branch that has no layout of its own to apply, so it is
            // the one branch that has to wait for something to build one from.
            //
            // It still SETTLES, and a failed fetch (which lands here too) settles
            // with it: "there is nothing to restore" and "the server would not say"
            // are both the end of waiting for a stored layout, and anything gated on
            // [State.isSettled] must not hang for ever on an answer that has already
            // arrived.
            if (stored == null) {
                markSettled()
                return@launch
            }
            // A stored workspace is trusted about its own shape but not about the
            // ids inside it, which is why the fresh-id counter is seeded past
            // whatever it holds: reusing one would make a new tab collide with a
            // restored one, and the toolkit's geometry is keyed by tab id.
            nextTabId = stored.tabs
                .mapNotNull { it.id.removePrefix(TAB_ID_PREFIX).toIntOrNull() }
                .maxOrNull()
                ?.plus(1)
                ?: 1
            // Wins over a default the list may already have seeded in the meantime:
            // a stored layout is what the user arranged, and the default is only
            // ever the stand-in for not having one.
            commit(ensureNotEmpty(stored), markRestored = true, markSettled = true, save = false)
            // ...and then checked against what this account can still see. If the
            // list has not arrived this does nothing and the list will do it; if it
            // arrived while this fetch was in flight, this is the only chance, since
            // an unchanged list emits nothing later.
            pruneToAccessible()
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
        // Fresh ids, never reused ones. The toolkit's geometry, layout preset and
        // pane order are all keyed by tab id, and none of it is Lunicle's to
        // delete — so a "default" workspace that handed back tab-1 would inherit
        // whatever splits and preset the old tab-1 had, and "restore" would put
        // the panes back but not their arrangement. Counting on past the ids in
        // play makes every restored tab one the toolkit has never seen, which is
        // what makes it land on [AppShellSpec.defaultLayoutPreset] and the seed
        // geometry. On a first seed the counter is at 1 anyway, so nothing about
        // a fresh account's tab ids changes.
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
        markSettled: Boolean = false,
        save: Boolean = true,
        saveNow: Boolean = false,
    ) {
        val next = _stateFlow.value.copy(
            workspace = workspace,
            isRestored = _stateFlow.value.isRestored || markRestored,
            isSettled = _stateFlow.value.isSettled || markSettled,
        )
        if (next == _stateFlow.value) return
        _stateFlow.value = next
        if (save) persist(immediate = saveNow)
    }

    /**
     * Say that the stored layout has answered, without changing the layout.
     *
     * For the branch that has no workspace to commit — an account with nothing
     * stored, or a fetch that failed — where the only news is that the waiting is
     * over. Guarded so it emits at most once: a state that is already settled must
     * not tick the collectors again for saying so twice.
     */
    private fun markSettled() {
        if (_stateFlow.value.isSettled) return
        _stateFlow.value = _stateFlow.value.copy(isSettled = true)
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
