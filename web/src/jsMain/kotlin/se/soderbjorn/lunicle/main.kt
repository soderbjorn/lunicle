/**
 * Entry point for the Lunicle Kotlin/JS web frontend.
 *
 * Bootstraps the app inside the darkness-toolkit shell: constructs the view
 * models, mounts `mountAppShell` with Lunicle's minimal-chrome configuration,
 * and pumps each view model's single state flow into the shell and the views.
 * Every decision about what to show lives in the shared view models; this file
 * only wires things together.
 *
 * The shell configuration, in redesign terms: no sidebar, no world switcher, no
 * tab strip (one implicit tab exists), no 3D, no bell/info/cogwheel. The "+"
 * menu holds New issue and — permission-gated — New project. The window layout,
 * dark/light, theme and appearance controls stay. The sign-in/profile corner
 * rides in the trailing custom slot; the "issue tracker" brand line rides on
 * the topbar's leading edge, which the minimal chrome would otherwise leave
 * empty, and there is no bottom bar. Nothing is persisted: an in-memory
 * persister makes every reload land on the default layout and the Lunamux
 * Dark theme.
 *
 * Window model: the board is a non-closable pane that opens maximised; every
 * open issue is its own closable pane. The pane list is pushed to the toolkit
 * through a [TabSource] from [MainScreenBackingViewModel.State.openIssueIds] —
 * the toolkit renders whatever the state says exists, and gestures route back
 * as intents, exactly the same contract every view here follows.
 *
 * This file owns the couplings *between* view models, none of which either side
 * should know about: the session drives the board, dialogs get their objects
 * and lifetimes managed, issue windows get created and disposed to match the
 * state, and the address bar follows the focused issue.
 *
 * @see BoardWindow
 * @see IssueWindow
 * @see MainScreenBackingViewModel
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.url.URLSearchParams
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.InMemoryPersister
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.ThemeSnapshotV2
import se.soderbjorn.darkness.web.shell.AppShellSpec
import se.soderbjorn.darkness.web.shell.PaneAddMenuItem
import se.soderbjorn.darkness.web.shell.PaneSnapshotEntry
import se.soderbjorn.darkness.web.shell.TabListSnapshot
import se.soderbjorn.darkness.web.shell.TabSnapshotEntry
import se.soderbjorn.darkness.web.shell.TabSource
import se.soderbjorn.darkness.web.shell.TopbarAction
import se.soderbjorn.darkness.web.shell.mountAppShell
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.client.Ticket
import se.soderbjorn.lunicle.client.parseTicket
import se.soderbjorn.lunicle.client.viewmodel.ActiveDialog
import se.soderbjorn.lunicle.client.viewmodel.CommentBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.EditProjectBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.IssueBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.MainScreenBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.SessionBackingViewModel
import se.soderbjorn.lunicle.clientserver.ProjectSummary

/** The one tab's id — invisible (no tab strip), but the toolkit still needs one. */
private const val TAB_ID = "main"

/** The board pane's id: non-closable, opens maximised. */
private const val BOARD_PANE_ID = "board"

/** An issue pane's id, from the issue it shows. */
private fun issuePaneId(issueId: Long): String = "issue-$issueId"

/** The issue behind an issue pane id, or null for the board (or anything else). */
private fun issueIdOf(paneId: String): Long? =
    paneId.removePrefix("issue-").takeIf { it != paneId }?.toLongOrNull()

/**
 * Kotlin/JS main entry point. Defers [start] to `window.onload` so `#app` is
 * guaranteed to exist regardless of where the bundle's script tag lands.
 */
fun main() {
    window.onload = { start() }
}

/**
 * The embed's `?project=<name>`, if the page was opened with one. A name that
 * does not resolve falls back to no project rather than an error — see
 * `StorageRepository.resolve`.
 */
private fun preferredProjectName(): String? =
    URLSearchParams(window.location.search).get("project")?.takeIf { it.isNotBlank() }

/**
 * The deep link's issue, if the page was opened with one: `?issue=LMX-12`.
 * Read from this frame's own URL in both modes — embedded, that is the iframe's
 * `src`, which is the only URL this code can see (the framing page is another
 * origin). The hash is deliberately not used; embedded, it belongs to the
 * site's router.
 */
private fun preferredTicket(): Ticket? =
    parseTicket(URLSearchParams(window.location.search).get("issue"))

/**
 * Put the focused issue in the address bar, or take it out again.
 *
 * `replaceState`, never `pushState` — Back must not walk through every issue
 * ever opened, and embedded, "back" is the site's history. Every other
 * parameter is preserved: `?project=` is the embed's and must survive.
 */
private fun syncIssueUrl(ticket: String?) {
    val params = URLSearchParams(window.location.search)
    if (params.get("issue") == ticket) return
    if (ticket == null) params.delete("issue") else params.set("issue", ticket)
    val query = params.toString()
    val url = window.location.pathname + (if (query.isEmpty()) "" else "?$query") + window.location.hash
    window.history.replaceState(null, "", url)
}

/** Inline SVG for the "+" menu's New issue row, sized like the toolkit's own. */
private const val ICON_NEW_ISSUE: String =
    "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
        "<rect x=\"4\" y=\"3\" width=\"16\" height=\"18\" rx=\"2\"/>" +
        "<line x1=\"8\" y1=\"8\" x2=\"16\" y2=\"8\"/>" +
        "<line x1=\"8\" y1=\"12\" x2=\"16\" y2=\"12\"/>" +
        "<line x1=\"8\" y1=\"16\" x2=\"12\" y2=\"16\"/></svg>"

/** Inline SVG for the "+" menu's New project row. */
private const val ICON_NEW_PROJECT: String =
    "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
        "<path d=\"M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z\"/></svg>"

private fun start() {
    val appHost = document.getElementById("app") as? HTMLElement
    if (appHost == null) {
        println("Lunicle: #app missing; nothing to mount into")
        return
    }

    // One repository, shared. The view models each get the same instance rather
    // than building their own, so there is exactly one HTTP client and one place
    // that talks to the server.
    val storage = StorageRepository()
    val mainViewModel = MainScreenBackingViewModel(storage)
    val sessionViewModel = SessionBackingViewModel(storage)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Modals mount outside the shell so they overlay every window: the shared
    // host is what makes Modal's "topmost wins Escape" true rather than
    // approximately true. Appended to <body>, not #app — the shell owns #app's
    // contents now.
    val dialogHost = element("div", "dialog-host")
    document.body?.appendChild(dialogHost)

    val dialogs = Dialogs(dialogHost, storage, mainViewModel)

    // One document-level listener for every rendered image there will ever be —
    // including the ones that do not exist yet, which is all of them.
    Lightbox(dialogHost).install()

    // The sign-in/profile corner, built exactly as before but mounted into the
    // toolkit's trailing top-bar slot instead of Lunicle's own bar.
    val accountHost = element("div", "topbar-account")
    val signInView = SignInView(sessionViewModel, dialogHost, storage)
    signInView.mount(accountHost)

    // ── Snapshot plumbing: view-model state → toolkit pane list ──────────────
    // The toolkit subscribes once (asynchronously, inside mountAppShell's init);
    // pushes before that are held as `latest` and delivered on subscribe.
    var push: ((TabListSnapshot) -> Unit)? = null
    var latest: TabListSnapshot = TabListSnapshot(tabs = emptyList(), activeTabId = null)
    var lastDelivered: TabListSnapshot? = null

    // The pane the user last physically pressed, kept by the bootstrap's own
    // mousedown listeners. This is what lets `deliver` tell a focus REPORT
    // (the state echoing a gesture the toolkit already performed) from a focus
    // COMMAND (open-existing-issue, deep link) — see deliver's comment.
    var lastUserFocusedPane: String? = null

    val boardWindow = BoardWindow(mainViewModel)
    // A mousedown anywhere in the board pane is a focus report: the address bar
    // follows the focused window, and "the board" is spelled null.
    boardWindow.root.addEventListener(
        "mousedown",
        {
            lastUserFocusedPane = BOARD_PANE_ID
            mainViewModel.onIssueWindowFocused(null)
        },
        true,
    )

    val issueWindows = IssueWindows(
        storage = storage,
        mainViewModel = mainViewModel,
        dialogHost = dialogHost,
        scope = scope,
        onPaneMousedown = { paneId -> lastUserFocusedPane = paneId },
        openComment = { issueViewModel, editing -> dialogs.openComment(issueViewModel, editing) },
    )

    fun snapshotOf(state: MainScreenBackingViewModel.State): TabListSnapshot = TabListSnapshot(
        tabs = listOf(
            TabSnapshotEntry(
                id = TAB_ID,
                label = "Lunicle",
                panes = listOf(PaneSnapshotEntry(BOARD_PANE_ID)) +
                    state.openIssueIds.map { PaneSnapshotEntry(issuePaneId(it)) },
                activePaneId = state.focusedIssueId?.let(::issuePaneId) ?: BOARD_PANE_ID,
            ),
        ),
        activeTabId = TAB_ID,
    )

    /**
     * Hand the latest snapshot to the toolkit — selectively, because a push is
     * not free: the toolkit re-renders on every one, and a re-render arriving
     * between a mousedown and its mouseup detaches the very element being
     * clicked, so the click never fires. Two rules keep that from happening:
     *
     * **Focus reports are not pushed.** When the only change is `activePaneId`
     * and it names the pane the user just physically pressed
     * ([lastUserFocusedPane]), the toolkit already moved focus there itself —
     * the state is echoing the gesture back, and pushing the echo would
     * re-render mid-gesture. It is recorded as delivered and skipped. A focus
     * COMMAND — clicking the card of an already-open issue, a deep link —
     * targets a pane the user did *not* just press, and goes through so the
     * toolkit raises that window.
     *
     * **The first delivery is board-only.** The board must be *seeded* before
     * any issue pane arrives: the toolkit's "a new pane force-restores a
     * maximized sibling" rule is what puts a deep-linked issue window on top of
     * the maximised board, and it only fires for panes that arrive after the
     * board exists. The full snapshot follows a tick later.
     */
    fun deliver() {
        val p = push ?: return
        val snap = latest
        val prev = lastDelivered
        if (prev != null) {
            if (snap == prev) return
            val prevTab = prev.tabs.firstOrNull()
            val tab = snap.tabs.firstOrNull()
            val focusOnly = prevTab != null && tab != null &&
                prevTab.panes == tab.panes &&
                prevTab.label == tab.label
            if (focusOnly && tab.activePaneId == lastUserFocusedPane) {
                lastDelivered = snap
                return
            }
        }
        if (prev == null) {
            val tab = snap.tabs.firstOrNull()
            if (tab != null && tab.panes.size > 1) {
                val boardOnly = snap.copy(
                    tabs = listOf(
                        tab.copy(
                            panes = listOf(PaneSnapshotEntry(BOARD_PANE_ID)),
                            activePaneId = BOARD_PANE_ID,
                        ),
                    ),
                )
                lastDelivered = boardOnly
                p(boardOnly)
                scope.launch { deliver() }
                return
            }
        }
        lastDelivered = snap
        p(snap)
    }

    val persister = InMemoryPersister()

    val spec = AppShellSpec(
        rootContainer = appHost,
        title = "Lunicle",
        persister = persister,
        paneContent = { paneId ->
            if (paneId == BOARD_PANE_ID) {
                boardWindow.root
            } else {
                issueWindows.contentFor(paneId)
                    // A pane the registry does not know cannot happen while the
                    // snapshot and the registry are built from the same state,
                    // but an empty div beats a crash if it ever does.
                    ?: element("div", "issue-window-missing")
            }
        },
        tabSource = TabSource(
            subscribe = { p ->
                push = p
                deliver()
            },
            // One invisible tab: there is nothing to select, add, close, rename
            // or reorder, and leaving every callback null is what keeps the
            // toolkit's "New tab" row out of the "+" menu.
            onSelect = {},
            onPaneClose = { _, paneId ->
                // Routed to the issue's own view model, which decides whether
                // this closes silently or stops to ask Save / Discard / Keep
                // editing. The pane disappears when — and only when — the state
                // drops the issue from openIssueIds.
                issueWindows.onCloseClicked(paneId)
            },
            paneAddMenuItems = { _ ->
                // Evaluated fresh on every menu open, so the permission gates
                // reflect the current session.
                val state = mainViewModel.stateFlow.value
                buildList {
                    if (state.canCreateIssue) {
                        add(
                            PaneAddMenuItem(
                                id = "new-issue",
                                label = "New issue",
                                iconHtml = ICON_NEW_ISSUE,
                                onSelect = { mainViewModel.onNewIssueTapped() },
                            ),
                        )
                    }
                    if (state.canCreateProject) {
                        add(
                            PaneAddMenuItem(
                                id = "new-project",
                                label = "New project",
                                iconHtml = ICON_NEW_PROJECT,
                                onSelect = { mainViewModel.onNewProjectTapped() },
                            ),
                        )
                    }
                }
            },
        ),
        paneLabel = { _, paneId ->
            when (val issueId = issueIdOf(paneId)) {
                null -> "Board"
                else -> mainViewModel.stateFlow.value.issueWindowTitle(issueId)
            }
        },
        // Lunicle's minimal chrome: no sidebar (nor its toggle), no tab strip,
        // no world switcher (no worldSource), no settings cogwheel (no
        // appSettingsContent). What remains of the standard cluster — "+",
        // layout, dark/light cycle, theme manager, appearance — is exactly what
        // the redesign keeps.
        showSidebar = false,
        showTabStrip = false,
        // No bottom bar: the brand line was the only thing in it, and it now
        // rides the topbar's leading edge, which the minimal chrome leaves
        // empty (no sidebar toggle, no world switcher). A bar with nothing in
        // it is a draggable edge and a strip of chrome for no content.
        showBottomBar = false,
        topbarLeading = {
            val brand = element("div", "topbar-brand")
            brand.children(
                logoIcon(),
                element("span", "topbar-brand-name", "Lunicle — an issue tracker by Robert Söderbjörn"),
            )
            brand
        },
        // Close is intercepted by the host (unsaved-changes question), so the
        // toolkit's own close-confirm dialog must not also ask.
        confirmPaneClose = false,
        // The board window cannot be closed; issue windows can.
        paneClosable = { _, paneId -> paneId != BOARD_PANE_ID },
        // The board opens maximised. With the in-memory persister that is
        // every launch, which is the redesign's intent.
        paneOpensMaximized = { _, paneId -> paneId == BOARD_PANE_ID },
        // The sign-in/profile corner, after the toolkit's standard cluster.
        extraTopbarTrailing = listOf(TopbarAction.custom(accountHost)),
    )

    scope.launch {
        // Seed the appearance to Dark before the shell reads the persister:
        // "same themes, defaulting to lunamux dark". The theme names need no
        // seeding — Lunamux Dark/Light are the toolkit's slot defaults — and
        // nothing is written back durably, so every reload lands here again.
        persister.write(
            PersistKeys.THEME_V2_SELECTION,
            ThemeSnapshotV2(appearance = Appearance.Dark).selectionJson(),
        )

        val handle = mountAppShell(spec, scope)

        // Separate collectors, not a combine(): a combine would couple every
        // board tick to a sign-in re-render for no benefit.
        launch {
            // The board the pane titles were last painted against. A refresh is
            // a full shell re-render, and re-rendering on emissions that change
            // nothing visible is not merely waste — a re-render between a
            // mousedown and its mouseup swallows the click (see deliver). Only
            // a *board* change can change a pane title (a draft publishing gets
            // its real ticket), so that is the only trigger.
            var titledBoard: se.soderbjorn.lunicle.clientserver.BoardState? = null
            mainViewModel.stateFlow.collect { state ->
                // Registry first, snapshot second: the toolkit asks for pane
                // content the moment a pane appears in the snapshot, and the
                // registry must already hold it.
                issueWindows.sync(state)
                boardWindow.render(state)
                dialogs.render(state)
                latest = snapshotOf(state)
                deliver()
                if (state.board !== titledBoard) {
                    titledBoard = state.board
                    handle.refresh()
                }
                // The address bar follows the focused issue window, so the URL
                // is always a link to what is on screen.
                syncIssueUrl(state.openIssueTicket)
            }
        }
        launch {
            sessionViewModel.stateFlow.collect { state ->
                signInView.onState(state)
                // The board belongs to whoever is asking, so the session drives
                // it. Identity rather than a boolean — impersonation goes
                // signed-in → signed-in and must still reload. See
                // MainScreenBackingViewModel.onSessionChanged.
                mainViewModel.onSessionChanged(
                    identity = state.identity,
                    isKnown = state.isLoaded,
                )
            }
        }

        mainViewModel.start(preferredProjectName(), preferredTicket())
        // Only the session starts a request. MainScreen has nothing to ask for
        // until the session says who is asking.
        sessionViewModel.start()
    }
}

/**
 * Owns the issue windows: one [IssueBackingViewModel] + [IssueWindow] + scope
 * per open issue, created and disposed to match
 * [MainScreenBackingViewModel.State.openIssueIds].
 *
 * The window-registry half of what the old `Dialogs` class did for the single
 * issue modal, now plural. Each window gets its own cancellable scope so a
 * closed window's `collect` does not outlive it, rendering into a detached
 * DOM tree.
 */
private class IssueWindows(
    private val storage: StorageRepository,
    private val mainViewModel: MainScreenBackingViewModel,
    private val dialogHost: HTMLElement,
    private val scope: CoroutineScope,
    /** Reports the raw press before the view-model intent — see [main]'s `lastUserFocusedPane`. */
    private val onPaneMousedown: (paneId: String) -> Unit,
    private val openComment: (IssueBackingViewModel, CommentBackingViewModel.Existing?) -> Unit,
) {
    private class Entry(
        val viewModel: IssueBackingViewModel,
        val view: IssueWindow,
        val scope: CoroutineScope,
        val root: HTMLElement,
    )

    private val entries = LinkedHashMap<Long, Entry>()

    /** Create and dispose windows to match the state. */
    fun sync(state: MainScreenBackingViewModel.State) {
        val wanted = state.openIssueIds.toSet()
        entries.keys.filter { it !in wanted }.toList().forEach { dispose(it) }
        state.openIssueIds.forEach { id ->
            if (id !in entries) create(id, state)
        }
    }

    /** The pane content for [paneId], or null if no such window exists. */
    fun contentFor(paneId: String): HTMLElement? =
        issueIdOf(paneId)?.let { entries[it]?.root }

    /**
     * The pane chrome's × was clicked. Forwarded to the window's own view
     * model, which owns the decision — close silently, or ask about unsaved
     * changes first.
     */
    fun onCloseClicked(paneId: String) {
        issueIdOf(paneId)?.let { entries[it]?.viewModel?.onCloseRequested() }
    }

    private fun create(id: Long, state: MainScreenBackingViewModel.State) {
        // No board, no vocabularies, no window. Cannot happen through the UI —
        // every way an issue opens starts from a rendered board.
        val board = state.board ?: return
        val windowScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val viewModel = IssueBackingViewModel(
            issueId = id,
            board = board,
            storage = storage,
            scope = windowScope,
            onFinished = { changed ->
                // The window is done: drop it from the state, which drops the
                // pane from the next snapshot, which is what actually closes
                // it. The refresh covers the close paths that wrote something
                // without passing through onWritten (a discarded draft whose
                // uploads made the board's attachment counts stale).
                mainViewModel.onIssueWindowClosed(id)
                if (changed) mainViewModel.refreshBoard()
            },
            // Any write the board should reflect — save, delete, comment —
            // refreshes it immediately, window still open. This is the
            // redesign's "saving an issue updates the board" requirement.
            onWritten = { mainViewModel.refreshBoard() },
        )
        val root = element("div", "issue-window-host")
        // A mousedown anywhere in the pane is a focus report; the address bar
        // follows. Capture phase, so fields and buttons inside still work.
        root.addEventListener(
            "mousedown",
            {
                onPaneMousedown(issuePaneId(id))
                mainViewModel.onIssueWindowFocused(id)
            },
            true,
        )
        val view = IssueWindow(
            viewModel = viewModel,
            scope = windowScope,
            dialogHost = dialogHost,
            openComment = { editing -> openComment(viewModel, editing) },
        )
        view.mount(root)
        entries[id] = Entry(viewModel, view, windowScope, root)
    }

    private fun dispose(id: Long) {
        val entry = entries.remove(id) ?: return
        entry.view.dispose()
        entry.scope.cancel()
    }
}

/**
 * Owns the modal dialogs and their lifetimes: the project form, the resolution
 * question, the comment editor, and the failure alert.
 *
 * The state flow says *which* dialog should be open; this opens and closes real
 * ones to match. Each dialog gets its own scope, cancelled when it closes —
 * without that, every dialog's `collect` would outlive it. Issue windows are
 * not here any more; they are windows, owned by [IssueWindows].
 */
private class Dialogs(
    private val host: HTMLElement,
    private val storage: StorageRepository,
    private val mainViewModel: MainScreenBackingViewModel,
) {
    private var current: ActiveDialog = ActiveDialog.None
    private var dismiss: (() -> Unit)? = null
    private var scope: CoroutineScope? = null

    private var commentDismiss: (() -> Unit)? = null
    private var commentScope: CoroutineScope? = null

    /** The failure alert while it is up, and the message it is showing. */
    private var alert: AlertDialog? = null
    private var alertMessage: String? = null

    fun render(state: MainScreenBackingViewModel.State) {
        // Before the early return below: an error is not an ActiveDialog, so it
        // arrives on emissions where `state.dialog` has not changed.
        renderAlert(state)
        if (state.dialog == current) return
        close()
        current = state.dialog
        when (val dialog = state.dialog) {
            ActiveDialog.None -> Unit
            ActiveDialog.NewProject -> openProject(existing = null, projects = state.projects)
            is ActiveDialog.EditProject -> openProject(existing = dialog.project, projects = state.projects)
            is ActiveDialog.ChooseResolution -> openResolution(dialog)
        }
    }

    /**
     * The comment modal, layered over an issue window.
     *
     * Its own scope and lifetime: it opens and closes while the issue window
     * stays up, so it cannot share the window's scope without being cancelled
     * at the wrong time — or leaking if it were never cancelled at all.
     *
     * @param issueViewModel the window's view model, which owns the comment
     *   list and re-fetches it when told something changed.
     */
    fun openComment(issueViewModel: IssueBackingViewModel, editing: CommentBackingViewModel.Existing?) {
        closeComment()
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val viewModel = CommentBackingViewModel(
            issueId = issueViewModel.currentIssueId(),
            editing = editing,
            storage = storage,
            scope = localScope,
            onFinished = { changed ->
                closeComment()
                // Tell the issue rather than the board: the issue owns the
                // comment list, and it re-fetches so the *server* decides the
                // author's name and whether this caller may edit it.
                if (changed) issueViewModel.onCommentsChanged()
            },
        )
        val view = CommentDialog(viewModel, localScope)
        view.mount(host)
        commentScope = localScope
        commentDismiss = { view.dismiss() }
    }

    /**
     * The resolution picker, held open over a drag that has not been sent yet.
     * No scope and no view model: it renders what the ActiveDialog carries and
     * reports one of two intents back.
     */
    private fun openResolution(dialog: ActiveDialog.ChooseResolution) {
        val view = ResolutionDialog(
            ticket = dialog.ticket,
            resolutions = dialog.resolutions,
            onChosen = { resolutionId ->
                mainViewModel.onResolutionChosen(dialog.issueId, dialog.statusId, resolutionId)
            },
            onCancel = { mainViewModel.onResolutionCancelled() },
        )
        view.mount(host)
        dismiss = { view.dismiss() }
    }

    /**
     * Put a failure up as a modal, or take it down. Keyed on the *message*, not
     * on "is there one" — two different failures in a row must not leave the
     * first message describing the second.
     */
    private fun renderAlert(state: MainScreenBackingViewModel.State) {
        val message = state.errorMessage
        if (message == alertMessage) return
        alert?.dismiss()
        alertMessage = message
        alert = message?.let {
            AlertDialog(
                title = "Something went wrong",
                message = it,
                onDismiss = { mainViewModel.onErrorDismissed() },
            ).also { dialog -> dialog.mount(host) }
        }
    }

    private fun openProject(existing: ProjectSummary?, projects: List<ProjectSummary>) {
        val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val viewModel = EditProjectBackingViewModel(
            existing = existing,
            otherProjects = projects,
            storage = storage,
            scope = dialogScope,
            // `saved?.id` only when creating: a new project is not in the
            // picker's list yet, and this is what puts the board on it.
            onFinished = { changed, saved ->
                mainViewModel.onDialogClosed(changed, selectProjectId = saved?.id)
            },
        )
        val view = ProjectDialog(viewModel, dialogScope)
        view.mount(host)
        scope = dialogScope
        dismiss = { view.dismiss() }
    }

    private fun closeComment() {
        commentDismiss?.invoke()
        commentScope?.cancel()
        commentDismiss = null
        commentScope = null
    }

    private fun close() {
        closeComment()
        dismiss?.invoke()
        scope?.cancel()
        dismiss = null
        scope = null
    }
}
