/**
 * Entry point for the Lunicle Kotlin/JS web frontend.
 *
 * Bootstraps the app inside the lunula shell: constructs the view models,
 * mounts `mountAppShell`, and pumps each view model's single state flow into the
 * shell and the views. Every decision about what to show lives in the shared view
 * models; this file only wires things together.
 *
 * ── The window model (LNL-160) ──────────────────────────────────────────────
 *
 * Lunicle does not have a navigation tree of its own. It has the toolkit's: a tab
 * holds panes, panes list themselves in the sidebar, the active pane takes the
 * chrome accent. All Lunicle has to answer is *what can a pane contain*, and the
 * answer is two things — a board for any project, or an issue. The project
 * picker, the view switcher and the custom top-bar section that used to carry
 * them are gone; a project is somewhere you open a board, not a mode the whole
 * window is in.
 *
 * That means a tab is a **working set** — a release, a triage session, a customer
 * — rather than a project, and a signed-in user's tabs and panes follow their
 * account. [WorkspaceBackingViewModel] owns that model and stores it;
 * [MainScreenBackingViewModel] holds one board per open project;
 * `reconcile` below is the one place the two are kept in step.
 *
 * This file owns the couplings *between* view models, none of which either side
 * should know about: the session drives the boards, dialogs get their objects
 * and lifetimes managed, issue windows get created and disposed to match the
 * state, and the address bar follows the focused pane.
 *
 * @see BoardWindow
 * @see IssueWindow
 * @see WorkspaceBackingViewModel
 * @see MainScreenBackingViewModel
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.url.URLSearchParams
import se.soderbjorn.lunula.core.Appearance
import se.soderbjorn.lunula.core.AppearanceShape
import se.soderbjorn.lunula.web.layout.LayoutPreset
import se.soderbjorn.lunula.web.layout.PaneAction
import se.soderbjorn.lunula.web.themeeditor.FontKind
import se.soderbjorn.lunula.web.themeeditor.FontPreset
import se.soderbjorn.lunula.web.themeeditor.registerFontPresets
import se.soderbjorn.lunula.web.shell.AppShellSpec
import se.soderbjorn.lunula.web.shell.InitialPaneGeometry
import se.soderbjorn.lunula.web.shell.PaneAddMenuItem
import se.soderbjorn.lunula.web.shell.paneAddSeparator
import se.soderbjorn.lunula.web.shell.PaneSnapshotEntry
import se.soderbjorn.lunula.web.shell.TabListSnapshot
import se.soderbjorn.lunula.web.shell.TabSnapshotEntry
import se.soderbjorn.lunula.web.shell.TabSource
import se.soderbjorn.lunula.web.shell.TopbarAction
import se.soderbjorn.lunula.web.shell.mountAppShell
import se.soderbjorn.lunula.web.settings.closeNotificationsSidebar
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.demo.DemoLunicleApi
import se.soderbjorn.lunicle.client.nextSearch
import se.soderbjorn.lunicle.client.queryValue
import se.soderbjorn.lunicle.client.Ticket
import se.soderbjorn.lunicle.client.parseTicket
import se.soderbjorn.lunicle.client.viewmodel.ActiveDialog
import se.soderbjorn.lunicle.client.viewmodel.AdminSettingsBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.CommentBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.EditProjectBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.EditorDirtyRegistry
import se.soderbjorn.lunicle.client.viewmodel.IssueBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.MainScreenBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.NotificationsBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.OpenIssueWindow
import se.soderbjorn.lunicle.client.viewmodel.PaneRef
import se.soderbjorn.lunicle.client.viewmodel.StatisticsBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.SessionBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.Workspace
import se.soderbjorn.lunicle.client.viewmodel.WorkspaceBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.analyticsProjectIdOfPane
import se.soderbjorn.lunicle.client.viewmodel.boardProjectIdOfPane
import se.soderbjorn.lunicle.client.viewmodel.settingsProjectIdOfPane
import se.soderbjorn.lunicle.client.viewmodel.issueIdOfPane
import se.soderbjorn.lunicle.client.viewmodel.issuePaneId
import se.soderbjorn.lunicle.clientserver.NotificationKind
import se.soderbjorn.lunicle.clientserver.NotificationSummary
import se.soderbjorn.lunicle.clientserver.ProjectSummary

/**
 * Kotlin/JS main entry point. Defers [start] to `window.onload` so `#app` is
 * guaranteed to exist regardless of where the bundle's script tag lands.
 */
fun main() {
    window.onload = { start() }
}

/**
 * The embed's `?project=<name>`, if the page was opened with one.
 *
 * A name that names no accessible project opens nothing, which is the honest
 * answer: the reader still gets their own workspace.
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
 * A project to open a board for: `?projectId=42`.
 *
 * An id rather than a name, so the link survives the project being renamed —
 * which is the whole reason this exists alongside the embed's `?project=<name>`.
 * Junk parses to null and is ignored.
 */
private fun preferredProjectId(): Long? =
    URLSearchParams(window.location.search).get("projectId")?.toLongOrNull()?.takeIf { it > 0 }

/**
 * Whether the browser-side demo runs instead of the real app: `?demo=1` (LNL-146).
 *
 * A client-side flag, read through the same parser the other switches use. When
 * set, [start] hands every view model a [DemoLunicleApi] backed by an in-memory
 * fixture world rather than the real `HttpLunicleApi`, so the whole app runs with
 * no server calls, no persistence and no auth — a reload resets to the seed. It
 * changes nothing for `issues.lunicle.dev`, where the flag is never set.
 */
private fun demoEnabled(): Boolean = queryValue(window.location.search, "demo") == "1"

/**
 * The appearance a host page asked this embed to seed on: `?theme=dark|light|auto`.
 *
 * A client-side hint, for the one job the toolkit's own defaults cannot do: a
 * browser with nothing stored seeds on Lunicle's Light default (GitHub Light,
 * LNL-149), which clashes with a dark host embedding the tracker in an iframe. The
 * Lunamux site is hard-committed dark and passes `?theme=dark`; [ThemePersister]
 * takes it as the *default* appearance — beneath any signed-in user's saved choice,
 * which still wins — so only an unchosen/signed-out browser is moved.
 *
 * Null for absent and for anything that is not one of the three [Appearance]
 * names, which is then just another parameter the app ignores. `auto` maps to the
 * toolkit's OS-following appearance, so a *light* host could ask the embed to
 * follow the visitor's system instead. The mapping itself is [appearanceFromThemeParam],
 * pure so it is tested without a store.
 */
private fun preferredAppearance(): Appearance? =
    appearanceFromThemeParam(queryValue(window.location.search, "theme"))

/**
 * The themes a host page asked this embed's slots to default to:
 * `?darkTheme=Lunamux%20Dark&lightTheme=Lunamux%20Light`.
 *
 * [preferredAppearance]'s other half, and the half that actually matches colours.
 * Going dark is not enough on its own — the dark slot would still hold GitHub Dark,
 * a neutral grey, inside a host with a look of its own. These name the theme that
 * matches, and correspond exactly to brand.json's `defaultDarkTheme` /
 * `defaultLightTheme`, one tier above them (see [ThemePersister.setEmbedDefaults]).
 *
 * Read through `URLSearchParams` rather than [queryValue], for [preferredProjectName]'s
 * reason: a theme name contains spaces ("Lunamux Dark") and genuinely needs
 * decoding, unlike a toggle spelt `1` or an appearance spelt `dark`.
 *
 * An unknown name is not validated here and does not need to be: the toolkit's
 * `resolve()` already falls back to a built-in for a name it does not have, which
 * is the same path a brand theme that was removed from disk takes.
 */
private fun preferredDarkTheme(): String? =
    URLSearchParams(window.location.search).get("darkTheme")?.takeIf { it.isNotBlank() }

private fun preferredLightTheme(): String? =
    URLSearchParams(window.location.search).get("lightTheme")?.takeIf { it.isNotBlank() }

/**
 * Put the focused pane in the address bar, so a reload comes back to what is on
 * screen.
 *
 * `replaceState`, never `pushState` — Back must not walk through every issue ever
 * opened or every pane ever focused, and embedded, "back" is the site's history.
 * The hash is never touched either; embedded, it belongs to the site's router.
 *
 * The rule itself lives in [nextSearch], which is a pure function over the query
 * string and is tested there. This is only the effect: what is preserved, what is
 * cleared and what "no change" means are all claims about other people's
 * parameters, and they are asserted in `AppUrlTest` rather than here where nothing
 * can reach them.
 *
 * Since LNL-160 the URL names one pane rather than the app's whole position:
 * there is no single project any more, and a link to a *workspace* is not a thing
 * anyone wants to send. What travels is what a reader would paste at somebody —
 * the issue they are looking at, or the board.
 */
private fun syncUrl(ticket: String?, projectId: Long?) {
    val query = nextSearch(
        search = window.location.search,
        ticket = ticket,
        projectId = projectId,
        // The parameters of features this shell no longer has. Null leaves `?tab=`
        // alone (it is somebody else's parameter now) and clears the three the
        // forum used to write, so a URL from before LNL-160 stops carrying them.
        tab = null,
        conversation = null,
        forum = null,
        post = null,
    ) ?: return
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

/**
 * Inline SVG for the "+" menu's New tab row.
 *
 * Ours rather than the toolkit's, because the whole menu is ours now — see the
 * `paneAddMenuItems` comment. Drawn as the same plain plus the "+" button wears,
 * at the 14×14 the other rows in this menu use.
 */
private const val ICON_NEW_TAB: String =
    "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\">" +
        "<path d=\"M12 5v14M5 12h14\"/></svg>"

/** Inline SVG for the "+" menu's New project row. */
private const val ICON_NEW_PROJECT: String =
    "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
        "<path d=\"M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z\"/></svg>"

/**
 * The pane glyph for a board — a window split into columns, which is what a board
 * is. Drawn at the toolkit's 14×14 chrome size so it sits level with the pane
 * title beside it and with the sidebar rows above and below.
 */
private const val ICON_BOARD_PANE: String =
    "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.7\" stroke-linejoin=\"round\">" +
        "<rect x=\"3.5\" y=\"4.5\" width=\"17\" height=\"15\" rx=\"2\"/>" +
        "<path d=\"M9.2 4.5v15M14.8 4.5v15\"/></svg>"

/** ...and for an issue: a sheet with lines on it. */
private const val ICON_ISSUE_PANE: String =
    "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.7\" stroke-linejoin=\"round\">" +
        "<rect x=\"4.5\" y=\"3.5\" width=\"15\" height=\"17\" rx=\"2\"/>" +
        "<path d=\"M8 8.5h8M8 12h8M8 15.5h5\"/></svg>"

/** ...an analytics pane: a bar chart, matching the toolbar entry that opens it. */
private const val ICON_ANALYTICS_PANE: String =
    "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\">" +
        "<path d=\"M5 19V11M12 19V5M19 19v-6\"/></svg>"

/** ...and a settings pane: the cog, matching its own toolbar entry. */
private const val ICON_SETTINGS_PANE: String =
    "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.7\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
        "<circle cx=\"12\" cy=\"12\" r=\"3\"/>" +
        "<path d=\"M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0" +
        "-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0" +
        "-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 " +
        "2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a" +
        "1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 " +
        "1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 " +
        "1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z\"/></svg>"

/**
 * Whether this tab is on screen, for the poll in [start] to skip when it is not.
 *
 * `visibilityState` is not on the stdlib's `Document`, so it is read dynamically —
 * the same way the ticket-link handler reaches for `closest`. The test is against
 * `"hidden"` rather than for `"visible"` on purpose: the property has a third value
 * ("prerender"), and a browser too old to have it at all yields `undefined`. Both
 * fall on the polling side, which is the harmless direction to be wrong in.
 */
private fun tabIsVisible(): Boolean = document.asDynamic().visibilityState != "hidden"

private fun start() {
    val appHost = document.getElementById("app") as? HTMLElement
    if (appHost == null) {
        println("Lunicle: #app missing; nothing to mount into")
        return
    }

    // The deep links, read ONCE, before anything can rewrite the address bar
    // (LNL-165). `syncUrl` replaces the query string with whatever pane is focused
    // as soon as the first board state arrives, and these are applied later than
    // that — so reading `window.location` at the point of use would read the app's
    // own answer back and ask for the board it is already on. What the page was
    // OPENED with is a fact about this page load, and it is captured here as one.
    val bootProjectId = preferredProjectId()
    val bootProjectName = preferredProjectName()
    val bootTicket = preferredTicket()

    // One repository, shared. The view models each get the same instance rather
    // than building their own, so there is exactly one HTTP client and one place
    // that talks to the server — or, in demo mode (LNL-146), one in-memory world
    // and no server at all. The swap is here and only here: `StorageRepository`
    // and every view model above it take the `LunicleApi` interface and never know
    // which implementation they were handed.
    val storage = if (demoEnabled()) StorageRepository(DemoLunicleApi()) else StorageRepository()
    // The one register of open editors, shared by everything that can hold unsaved
    // work — every issue window, the comment modal. One instance, threaded to each
    // below, so a window on one tab and a comment on another are one register.
    val editorRegistry = EditorDirtyRegistry()
    val mainViewModel = MainScreenBackingViewModel(storage, editorRegistry = editorRegistry)
    val sessionViewModel = SessionBackingViewModel(storage)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // The tabs and what is in them (LNL-160). Its own view model because a
    // workspace is a different question from a board: it says *where* things are,
    // never *what* they contain, and it is the half that is stored under the
    // account. See WorkspaceBackingViewModel.
    val workspaceViewModel = WorkspaceBackingViewModel(storage, scope)

    // Creating a project should leave you looking at it. Where a board goes is the
    // workspace's decision, so the board view model asks rather than acts — see
    // MainScreenBackingViewModel.projectToOpen.
    mainViewModel.projectToOpen = { workspaceViewModel.onBoardOpened(it) }

    // Ticket references (LNL-139), built once and handed to every window family that
    // owns an editor or renders issue text. `prefixes` is the reader's accessible
    // projects, read live so one granted mid-session takes effect; `lookup` turns a
    // prefix into that project's issues for the `PREFIX-` autocomplete — an open
    // board's are already in memory, another accessible project's are fetched once
    // (the editor caches), and an unknown or inaccessible prefix offers nothing.
    // Titles for expanding a rendered reference to "PREFIX-N: Title" (LNL-144).
    // Filled from every board the session loads and keyed by the canonical,
    // upper-cased reference so a `lnl-1` written in any case finds it. It never
    // fetches on its own: a title it does not hold yet just leaves that reference
    // bare until the board arrives, which is the pre-LNL-144 behaviour, so no
    // reference is ever worse off.
    val ticketTitles = mutableMapOf<String, String>()
    fun titleKey(prefix: String, number: Long) = "${prefix.uppercase()}-$number"
    fun indexBoardTitles(state: MainScreenBackingViewModel.State) {
        state.boards.values.forEach { board ->
            board.issues.forEach { ticketTitles[titleKey(board.project.namePrefix, it.number)] = it.title }
        }
    }
    val ticketSource = TicketSource(
        prefixes = { mainViewModel.stateFlow.value.projects.map { it.namePrefix } },
        lookup = { prefix ->
            val current = mainViewModel.stateFlow.value
            val project = current.projects.firstOrNull { it.namePrefix.equals(prefix, ignoreCase = true) }
            val issues = when {
                project == null -> emptyList()
                // An open board is already here and is live; anything else costs
                // one fetch, which the editor then caches.
                current.boards.containsKey(project.id) -> current.boards.getValue(project.id).issues
                else -> runCatching { storage.board(project.id) }.getOrNull()?.issues.orEmpty()
            }
            val namePrefix = project?.namePrefix ?: prefix
            issues.forEach { ticketTitles[titleKey(namePrefix, it.number)] = it.title }
            issues.map { TicketOption("$namePrefix-${it.number}", it.number, it.title) }
        },
        titleFor = { ticket ->
            // An open board is authoritative and live, so read it straight off
            // state; anything else comes from what the cache has accumulated.
            val current = mainViewModel.stateFlow.value
            val open = current.boards.values.firstOrNull {
                it.project.namePrefix.equals(ticket.prefix, ignoreCase = true)
            }
            open?.issues?.firstOrNull { it.number == ticket.number }?.title
                ?: ticketTitles[titleKey(ticket.prefix, ticket.number)]
        },
    )

    // Keep every board the reader passes through in the ticket-title cache (LNL-144),
    // so a reference to a project whose board they have since closed still expands.
    // Costs nothing extra — it reuses boards state already holds.
    scope.launch { mainViewModel.stateFlow.collect { indexBoardTitles(it) } }

    // The alarm bell's count and the notification panel's list (LNL-109). Shares
    // the app scope so its live-render collector and the five-minute poll below
    // die with the page. See NotificationsBackingViewModel.
    val notificationsViewModel = NotificationsBackingViewModel(storage, scope)

    // Modals mount outside the shell so they overlay every window: the shared
    // host is what makes Modal's "topmost wins Escape" true rather than
    // approximately true. Appended to <body>, not #app — the shell owns #app's
    // contents now.
    val dialogHost = element("div", "dialog-host")
    document.body?.appendChild(dialogHost)

    val dialogs = Dialogs(
        dialogHost,
        storage,
        mainViewModel,
        editorRegistry,
        ticketSource,
        // An instance switch flipped in the admin dialog can change what the session
        // reports (LNL-137's display-name gate), and this client took its session
        // once at bootstrap — so re-fetch it when one is written. See
        // AdminSettingsBackingViewModel.onInstanceSettingChanged.
        reloadSession = sessionViewModel::reload,
    )

    // One document-level listener for every rendered image there will ever be —
    // including the ones that do not exist yet, which is all of them.
    Lightbox(dialogHost).install()

    // ── Snapshot plumbing: workspace → toolkit tab/pane list ─────────────────
    // The toolkit subscribes once (asynchronously, inside mountAppShell's init);
    // pushes before that are held as `latest` and delivered on subscribe.
    var push: ((TabListSnapshot) -> Unit)? = null
    var latest: TabListSnapshot = TabListSnapshot(tabs = emptyList(), activeTabId = null)
    var lastDelivered: TabListSnapshot? = null

    // The two flows the snapshot is a function of, each kept because the other's
    // collector has to be able to rebuild it. Seeded from the flows rather than
    // from defaults, so whichever ticks first still produces a whole snapshot.
    var boardState: MainScreenBackingViewModel.State = mainViewModel.stateFlow.value
    var workspaceState: WorkspaceBackingViewModel.State = workspaceViewModel.stateFlow.value

    // The pane the user last physically pressed, kept by the panes' own mousedown
    // listeners. This is what lets `deliver` tell a focus REPORT (the state echoing
    // a gesture the toolkit already performed) from a focus COMMAND (open an
    // already-open issue, a deep link) — see deliver's comment.
    var lastUserFocusedPane: String? = null

    // Rebuilding the shell, for the things that need it from outside the coroutine
    // below. A lambda rather than the handle itself, so nothing out here has to
    // name the toolkit's type — and a no-op until the shell exists, which is the
    // honest answer for chrome that changes before there is a top bar to paint it.
    var refreshShell: () -> Unit = {}

    val boardWindows = BoardWindows(
        mainViewModel = mainViewModel,
        titleFor = ticketSource.titleFor,
        onOpenProjectPane = { workspaceViewModel.onProjectPaneOpened(it) },
        onPaneMousedown = { paneId ->
            lastUserFocusedPane = paneId
            workspaceViewModel.onPaneFocused(paneId)
            // "The board" is spelled null for the address bar: a board pane is not
            // an issue, so nothing about an issue should be in the URL while it has
            // focus.
            mainViewModel.onIssueWindowFocused(null)
        },
    )

    // The two project surfaces, each a pane rather than a modal since LNL-160.
    // Their own registries for BoardWindows' reason: a pane per project, built on
    // demand and disposed with the pane, each with a view model and a scope whose
    // `collect` must not outlive the window it renders into.
    val settingsPanes = SettingsPanes(
        storage = storage,
        mainViewModel = mainViewModel,
        onPaneMousedown = { paneId ->
            lastUserFocusedPane = paneId
            workspaceViewModel.onPaneFocused(paneId)
        },
        onFinished = { projectId -> workspaceViewModel.onPaneClosedEverywhere(PaneRef.Settings(projectId).paneId) },
    )
    val analyticsPanes = AnalyticsPanes(
        storage = storage,
        onPaneMousedown = { paneId ->
            lastUserFocusedPane = paneId
            workspaceViewModel.onPaneFocused(paneId)
        },
        onFinished = { projectId ->
            workspaceViewModel.onPaneClosedEverywhere(PaneRef.Analytics(projectId).paneId)
        },
    )

    val issueWindows = IssueWindows(
        storage = storage,
        mainViewModel = mainViewModel,
        dialogHost = dialogHost,
        scope = scope,
        editorRegistry = editorRegistry,
        onPaneMousedown = { paneId ->
            lastUserFocusedPane = paneId
            workspaceViewModel.onPaneFocused(paneId)
        },
        openComment = { issueViewModel, editing -> dialogs.openComment(issueViewModel, editing) },
        ticketSource = ticketSource,
    )

    // The sign-in/profile corner, mounted into the toolkit's trailing top-bar slot.
    // It also builds the profile modal, which is where "Restore default layout"
    // lives (LNL-160) — hence the workspace lambda threaded through it.
    val accountHost = element("div", "topbar-account")
    val signInView = SignInView(
        viewModel = sessionViewModel,
        dialogHost = dialogHost,
        storage = storage,
        // The default layout is one tab per project holding that project's board
        // — so the issue windows go too, or "restore" would leave the thing it was
        // reached for still on screen. Asked to close rather than dropped: a window
        // with unsaved work stops to ask, exactly as its × does, and one that
        // refuses simply keeps its pane (the reconcile puts it back). The layout is
        // restored either way.
        onRestoreDefaultLayout = {
            issueWindows.closeAll()
            workspaceViewModel.onRestoreDefaultLayout()
        },
    )
    signInView.mount(accountHost)

    // ── Notifications (LNL-109): the alarm bell, its panel, and navigation ────

    // Opens the notifications sidebar. A var rather than a direct call because the
    // shell handle it needs does not exist until mountAppShell below; a no-op until
    // then, the same honest default as refreshShell above.
    var openNotifications: () -> Unit = {}

    // The bell itself — Lunicle's, not the toolkit's: it flashes from Lunicle's own
    // unread state (mirroring Lunamux's appearance/fade in styles.css) and opens the
    // toolkit-owned sidebar. A stable element, re-appended on every top-bar rebuild,
    // so its click listener survives. See extraTopbarTrailing below.
    val notificationBell = element("button", "topbar-bell lnl-notif-bell")
    notificationBell.setAttribute("type", "button")
    notificationBell.setAttribute("aria-label", "Notifications")
    notificationBell.title = "Notifications"
    notificationBell.appendChild(bellIcon())
    notificationBell.addEventListener("click", { openNotifications() })

    /**
     * Open an issue, wherever it belongs.
     *
     * The one path every "show me this issue" takes that is not a card click: a
     * notification, a ticket reference, the `?issue=` deep link. Two steps, and the
     * order matters — the board has to be open before the window can be built, because
     * the window's statuses, priorities and permissions all come from it.
     *
     * Opening the *board* is what makes the issue land next to its context: the
     * workspace's rule puts the issue pane in whichever tab already holds that
     * project's board (see [WorkspaceBackingViewModel.onIssueOpened]), and this is
     * what guarantees there is one. `reconcile` does the rest when the board arrives.
     */
    fun openIssueSomewhere(projectId: Long, issueId: Long) {
        workspaceViewModel.onBoardOpened(projectId)
        workspaceViewModel.onIssueOpened(issueId, projectId)
    }

    /**
     * Take the reader to a notification's destination, then close the panel.
     *
     * The in-app half of LNL-109's "not a deep link": the notification carries the
     * app's own ids, and this opens the pane from the same entry points a board
     * click uses — no URL is written or re-parsed.
     */
    fun navigateToNotification(n: NotificationSummary) {
        when (n.kind) {
            NotificationKind.ISSUE_CREATED,
            NotificationKind.ISSUE_UPDATED,
            NotificationKind.ISSUE_ASSIGNED,
            NotificationKind.ISSUE_MENTIONED,
            -> {
                val projectId = n.projectId
                val issueId = n.issueId
                if (projectId != null && issueId != null) openIssueSomewhere(projectId, issueId)
            }
            // The forum and messages panes left the shell with LNL-160; their
            // notifications have nowhere to go until they come back as pane kinds
            // of their own. Deliberately silent rather than half-navigating.
            NotificationKind.MESSAGE,
            NotificationKind.FORUM_POST,
            NotificationKind.FORUM_COMMENT,
            -> Unit
        }
        // Close the panel behind the navigation. Synchronously (the toolkit's
        // force-close, used for mutual exclusion) rather than the animated toggle:
        // navigating fires a burst of shell rebuilds — a board opening, the issue
        // window arriving — and an animated close racing those rebuilds gets
        // re-mounted mid-slide and never finishes. A synchronous close flips the
        // state first, so every following rebuild simply omits the panel.
        closeNotificationsSidebar()
        refreshShell()
    }

    /**
     * Open the issue a clicked ticket reference names (LNL-139).
     *
     * The reference carries only a `PREFIX-NUMBER`, but a prefix is unique across
     * the instance, so it alone says which project. A reference whose prefix names
     * no project the reader can see opens nothing: the honest answer, and the one a
     * dead deep link already gives.
     *
     * The issue *id* is not in the reference — only its number — so this opens the
     * board and lets `reconcile` resolve the number once that board is loaded. See
     * [pendingTickets].
     */
    val pendingTickets = mutableListOf<Ticket>()
    fun navigateToTicket(ticket: Ticket) {
        val projectId = mainViewModel.projectIdWithPrefix(ticket.prefix) ?: return
        workspaceViewModel.onBoardOpened(projectId)
        pendingTickets.add(ticket)
        refreshShell()
    }

    // One document-level listener for every ticket reference there will ever be —
    // the same bargain Lightbox strikes, and for the same reason: the anchors are
    // re-created by innerHTML on every render, so nothing can be bound to them.
    // Capture phase, because a board card stops the click on its own title's links
    // from bubbling — to keep a link from also opening the card, see
    // BoardWindow.renderCard — and a bubble-phase listener would never see one.
    document.addEventListener("click", { event ->
        val el = event.target as? HTMLElement ?: return@addEventListener
        val anchor = el.asDynamic().closest(".ticket-ref").unsafeCast<HTMLElement?>()
            ?: return@addEventListener
        // Never the editor's live copy: there a click places the caret, and that
        // surface is rendered with no prefix so it carries no ticket anchors anyway.
        if (anchor.asDynamic().closest(".editor-surface") != null) return@addEventListener
        val ticket = parseTicket(anchor.getAttribute("data-ticket")) ?: return@addEventListener
        event.preventDefault()
        event.stopPropagation()
        navigateToTicket(ticket)
    }, true)

    val notificationsPanel = NotificationsPanel(
        viewModel = notificationsViewModel,
        scope = scope,
        onOpen = ::navigateToNotification,
    )

    // ── Keeping the workspace and the boards in step ─────────────────────────

    /**
     * Issue panes restored from storage that have not become windows yet, by issue
     * id.
     *
     * The one direction that runs workspace → boards, and it runs **once per pane**.
     * Everything afterwards runs the other way: the workspace mirrors whichever
     * issues have windows open. Without the one-shot, closing a window would be
     * undone on the next tick — the pane would still be in the workspace for a beat,
     * `reconcile` would read that as "this issue should be open" and re-open it,
     * for ever.
     *
     * An entry is spent the moment its board arrives, whether or not the issue is
     * still on it: an issue deleted since the layout was stored takes its pane with
     * it rather than retrying every tick.
     */
    val pendingRestoredIssues = mutableMapOf<Long, Long>()
    var restoreAdopted = false

    /**
     * The issue whose window the app last raised, so a *change* of focus can be
     * told from focus merely sitting where it already was. See step 5 of
     * [reconcile].
     *
     * Seeded from the state rather than from null: a workspace restored with an
     * issue pane already focused must not be read as a fresh command to raise it.
     */
    var focusRaisedFor: Long? = mainViewModel.stateFlow.value.focusedIssueId

    /**
     * Whether a boot deep link has said what this page is looking at, and what it
     * said — an issue id, or null for "a board, so nothing issue-shaped" (LNL-165).
     *
     * The restore has a focus of its own: adopting a stored issue pane opens its
     * window, and opening a window focuses it (see [reconcile]'s step 2). That is
     * right for a plain reload — it is how `?issue=` comes back in the address bar
     * and how the stored window is raised — and wrong the moment somebody arrived
     * with a link, because the boards it waits on load AFTER the workspace, so it
     * lands last and wins by accident. A link is an explicit request and the stored
     * layout is not, so the link keeps focus and the adoption gives it back.
     *
     * Consulted only where an adoption actually happened, which is a boot-only
     * event: after that pass these two are inert for the life of the page.
     */
    var deepLinkOwnsFocus = false
    var deepLinkFocus: Long? = null

    /**
     * Make the boards and the workspace agree, in one pass.
     *
     * Called from both collectors, because either side can move first: opening a
     * board pane means a board has to be fetched, and closing an issue window means
     * a pane has to go. Idempotent by construction — every branch is a difference
     * between two sets, so a pass with nothing to do does nothing.
     */
    fun reconcile() {
        val ws = workspaceState.workspace
        if (!workspaceState.isRestored) return

        // 1. Which boards have to exist. The one input the board view model takes
        //    from the workspace; it fetches what is new and drops what is gone.
        mainViewModel.onOpenProjectsChanged(ws.referencedProjectIds)

        // 2. Adopt the restored issue panes, once.
        if (!restoreAdopted) {
            restoreAdopted = true
            ws.openIssuePanes.forEach { pendingRestoredIssues[it.issueId] = it.projectId }
        }
        val state = boardState
        var adopted = false
        // `toList()` — pairs, not `entries.toList()`. That copies the LIST but not
        // the entries in it, and a Kotlin/JS map entry is a live view onto the map:
        // removing below invalidates every entry still to come, and reading the
        // next one throws "the backing map has been modified after this entry was
        // obtained" — mid-`reconcile`, so the boards are never asked for and the
        // panes stay empty for ever. It needed two restored issue panes to show,
        // which is why it hid for as long as it did.
        pendingRestoredIssues.toList().forEach { (issueId, projectId) ->
            val board = state.boards[projectId] ?: return@forEach
            pendingRestoredIssues.remove(issueId)
            if (board.issues.any { it.id == issueId }) {
                mainViewModel.onIssueOpened(projectId, issueId)
                adopted = true
            } else {
                // Deleted, or no longer visible to this reader, since the layout
                // was stored. The pane goes rather than sitting there empty.
                workspaceViewModel.onIssueClosed(issueId)
            }
        }
        // Opening a window focuses it, and a restored pane is the layout coming
        // back rather than a request to look at it. A deep link IS such a request
        // and it was made first, so it keeps focus — otherwise the stored layout's
        // issue takes the address bar back and step 5 below hauls the reader over
        // to its tab, which is the second half of LNL-165.
        if (adopted && deepLinkOwnsFocus) mainViewModel.onIssueWindowFocused(deepLinkFocus)

        // 3. A ticket reference or a `?issue=` deep link, waiting on its board.
        //    Resolved by NUMBER, which is all a reference carries.
        pendingTickets.toList().forEach { ticket ->
            val projectId = mainViewModel.projectIdWithPrefix(ticket.prefix)
            val board = projectId?.let { state.boards[it] }
            if (projectId == null) {
                pendingTickets.remove(ticket)
                return@forEach
            }
            if (board == null) return@forEach
            pendingTickets.remove(ticket)
            board.issues.firstOrNull { it.number == ticket.number }
                ?.let {
                    mainViewModel.onIssueOpened(projectId, it.id)
                    // A `?issue=` link carries only a number, so which issue it
                    // meant is not knowable until here. This is where the focus the
                    // link claimed acquires an id — see [deepLinkFocus].
                    if (deepLinkOwnsFocus) deepLinkFocus = it.id
                }
        }

        // 4. Mirror: every window that exists has a pane, and every pane names a
        //    window that exists.
        //
        //    Read LIVE rather than from the `state` captured above, because step 2
        //    may have just opened a window and this pass has to see it. It did not,
        //    once: a restored issue pane was spent out of `pendingRestoredIssues`
        //    in step 2, then found "not open" here against the pre-step-2 snapshot,
        //    and closed — so every reload dropped the issue pane and re-added it on
        //    the next tick, at the END of its tab. The panes came back but the
        //    arrangement did not, which is most of what a stored layout is for.
        val now = mainViewModel.stateFlow.value
        val placed = ws.openIssuePanes.map { it.issueId }.toSet()
        now.openIssues.forEach { open ->
            if (open.issueId !in placed) workspaceViewModel.onIssueOpened(open.issueId, open.projectId)
        }
        val live = now.openIssueIds.toSet()
        placed.forEach { issueId ->
            if (issueId !in live && issueId !in pendingRestoredIssues) {
                workspaceViewModel.onIssueClosed(issueId)
            }
        }

        // 5. Raise the window of the issue that has just taken focus.
        //
        //    Clicking the card of an issue that is ALREADY open creates nothing:
        //    onIssueOpened finds the window in openIssues and only moves
        //    focusedIssueId. Step 4 above is a set difference, so it has nothing
        //    to say about that click either — and the result was a card that did
        //    visibly nothing whenever its window was behind the board or in
        //    another tab. Handing the id to the workspace activates that pane, and
        //    its tab; the workspace collector's bringPaneToFront does the raising.
        //
        //    On the EDGE of focusedIssueId, never on its value. Level-triggered,
        //    an ordinary tick arriving while the reader was on another tab would
        //    haul them back to whichever issue held focus last — and the same
        //    reasoning covers the user's own press on an issue pane, which moves
        //    focus and the active pane together, so the command this fires is one
        //    the workspace has already carried out.
        val focused = now.focusedIssueId
        if (focused != focusRaisedFor) {
            focusRaisedFor = focused
            now.openIssues.firstOrNull { it.issueId == focused }
                ?.let { workspaceViewModel.onIssueOpened(it.issueId, it.projectId) }
        }
    }

    /**
     * Open what the page was opened *for*: `?projectId=`, the embed's `?project=`
     * and `?issue=` (LNL-165).
     *
     * Once per page, and only when BOTH answers it depends on have arrived —
     * exactly the pair, and exactly the reason, that guards [reconcile]:
     *
     *  - the project list, because a link naming a project by id or by name means
     *    nothing before there is a list to resolve it against;
     *  - the workspace, because [WorkspaceBackingViewModel.State.isSettled] is when
     *    the layout stops being replaced wholesale. Gating on the list alone is the
     *    bug this fixes: signed in, the list wins the race, the link opened its
     *    board, and the stored workspace then landed on top of it — active tab and
     *    all — leaving the reader on whatever board they had last, with the address
     *    bar honestly reporting it. `?issue=` failed harder still, since the board
     *    its ticket was waiting on was the one being clobbered.
     *
     * Signed out neither wait is long and nothing was ever broken, which is why the
     * lunamux.dev embed — always a visitor — kept working throughout.
     *
     * Called from both collectors, so it fires on whichever of the two completes
     * the pair last, and the [deepLinksSpent] latch makes the second call a no-op.
     */
    fun applyDeepLinks() {
        if (deepLinksSpent) return
        val state = mainViewModel.stateFlow.value
        if (!state.isLoaded) return
        if (!workspaceState.isRestored || !workspaceState.isSettled) return
        deepLinksSpent = true
        // `?projectId=` and the embed's `?project=` both name a board to open; the
        // id wins, being the one that survives a rename.
        val projectId = bootProjectId?.takeIf { id -> state.projects.any { it.id == id } }
            ?: mainViewModel.projectIdNamed(bootProjectName)
        projectId?.let {
            workspaceViewModel.onBoardOpened(it)
            deepLinkOwnsFocus = true
            // "The board" is spelt null for the address bar, exactly as a press on
            // a board pane spells it (see BoardWindows.onPaneMousedown): the reader
            // asked for a board, so no issue restored behind it belongs in the URL.
            deepLinkFocus = null
            mainViewModel.onIssueWindowFocused(null)
        }
        // ...and `?issue=LMX-12`, which opens its project's board and then waits for
        // it — see navigateToTicket and reconcile's step 3. Its focus is claimed
        // here and named there, once the board says which issue that number is.
        bootTicket?.let {
            deepLinkOwnsFocus = true
            navigateToTicket(it)
        }
    }

    // ── The toolkit snapshot ─────────────────────────────────────────────────

    /** The toolkit's view of one tab: its panes, in order, and the focused one. */
    fun snapshotOf(ws: Workspace): TabListSnapshot = TabListSnapshot(
        tabs = ws.tabs.map { tab ->
            TabSnapshotEntry(
                id = tab.id,
                label = tab.name,
                panes = tab.panes.map { PaneSnapshotEntry(it.paneId) },
                activePaneId = tab.activePaneId ?: tab.panes.firstOrNull()?.paneId,
            )
        },
        activeTabId = ws.activeTabId,
    )

    /**
     * Hand the latest snapshot to the toolkit — selectively, because a push is not
     * free: the toolkit re-renders on every one, and a re-render arriving between a
     * mousedown and its mouseup detaches the very element being clicked, so the
     * click never fires.
     *
     * **Focus reports are not pushed.** When the only change is `activePaneId` and
     * it names the pane the user just physically pressed ([lastUserFocusedPane]),
     * the toolkit already moved focus there itself — the state is echoing the
     * gesture back, and pushing the echo would re-render mid-gesture. It is recorded
     * as delivered and skipped. A focus COMMAND — clicking the card of an
     * already-open issue, a deep link, a sidebar row — targets a pane the user did
     * *not* just press, and goes through so the toolkit raises that window.
     *
     * A tab *switch* is always a real change and always pushed: the user pressed a
     * tab rather than a pane, and suppressing it would leave the strip highlighting
     * one tab while another one's content showed.
     */
    fun deliver() {
        val p = push ?: return
        val snap = latest
        val prev = lastDelivered
        if (prev != null) {
            if (snap == prev) return
            // Everything except which pane is active, across every tab. If the tabs
            // themselves differ in any other way — a pane opened or closed, a label
            // repainted, the active TAB moved — this is not a focus report.
            val structureUnchanged = prev.activeTabId == snap.activeTabId &&
                prev.tabs.map { it.id to (it.panes to it.label) } ==
                snap.tabs.map { it.id to (it.panes to it.label) }
            val activePane = snap.tabs.firstOrNull { it.id == snap.activeTabId }?.activePaneId
            if (structureUnchanged && activePane != null && activePane == lastUserFocusedPane) {
                lastDelivered = snap
                return
            }
        }
        lastDelivered = snap
        p(snap)
    }

    /** The board pane a project id names, for the pane label and the pane actions. */
    fun screenOfPane(paneId: String): MainScreenBackingViewModel.BoardScreen? =
        boardProjectIdOfPane(paneId)?.let { mainViewModel.stateFlow.value.screen(it) }

    // The theme and the window layout follow the account. See ThemePersister.
    val persister = ThemePersister(storage, scope)

    // The deployment's branding (LNL-110), or null when unbranded. Fetched once
    // inside the boot coroutine below, before the shell mounts, and captured here
    // so the spec's late-bound lambdas (the sidebar logo) see it. Null leaves
    // everything at Lunicle's own look.
    var brandConfig: Brand? = null

    // The sidebar's identity line, built on first ask and then re-parented on
    // every rerender — see sidebarHeader. Held here rather than rebuilt because
    // the embed's "open the full site" link inside it carries a listener.
    var sidebarBrand: HTMLElement? = null

    val spec = AppShellSpec(
        rootContainer = appHost,
        title = "Lunicle",
        persister = persister,
        // The deployment's brand font on the shell chrome (LNL-110), when it
        // ships one for the chrome surface. A lambda because branding loads
        // asynchronously after this spec is built; the shell reads it on every
        // font application, so the brand font sits beneath — and yields to — any
        // font the user picks in the theme manager. Unbranded ⇒ null ⇒ default.
        defaultChromeFontFamily = { brandConfig?.chromeFont?.presetKey },
        // The deployment's brand font on the PROPORTIONAL content surface — prose:
        // card text, dialog copy, rendered descriptions (LNL-118).
        // Bound by the toolkit to `--dt-font-prop`, which Lunicle's `--prose`
        // reads; code/mono content (`--dt-font-mono`) is deliberately left alone.
        // A brand opts in with `surfaces: ["prose"]` (or "content"). Like the
        // chrome hook this yields to any font the user picks in Appearance, and is
        // null on an unbranded instance ⇒ prose falls back to the toolkit's own
        // proportional content stack, the face `.dt-pane-content` already paints
        // over every pane (see `--prose` in styles.css). Independent of chrome, so
        // a deployment can brand prose, chrome, or both.
        defaultProseFontFamily = { brandConfig?.proseFont?.presetKey },
        // The deployment's brand font on the DISPLAY (heading) surface — issue
        // titles and board column names. Bound to `--dt-font-display`, which
        // Lunicle's `--display` reads and which falls back to `--prose` when a
        // brand names no display face. A brand opts in with `surfaces: ["display"]`
        // — e.g. a serif for headlines while prose/chrome stay sans. Yields to any
        // font the user picks in Appearance; null on an unbranded instance.
        defaultDisplayFontFamily = { brandConfig?.displayFont?.presetKey },
        // The deployment's shape defaults — corner roundness, spacing density
        // and selection language — from `defaultCornerRadiusPx` /
        // `defaultUiDensity` / `defaultSelectionStyle` in brand.json. Exactly
        // the font seams above in a different medium: each field lands beneath
        // whatever the user picked in Appearance and above the toolkit default,
        // so a branded instance opens looking like itself without ever
        // overriding someone who has stated a preference. Empty (all toolkit
        // defaults) on an unbranded instance.
        defaultAppearanceShape = { brandConfig?.appearanceShape ?: AppearanceShape() },
        // ...and the size half of the same seams. Null on an unbranded
        // instance, so the toolkit's own sizes stand.
        defaultChromeFontSizePx = { brandConfig?.chromeFontSizePx },
        defaultMonoFontSizePx = { brandConfig?.monoFontSizePx },
        defaultProseFontSizePx = { brandConfig?.proseFontSizePx },
        defaultDisplayFontSizePx = { brandConfig?.displayFontSizePx },
        // Two pane kinds, which is the whole of Lunicle's contribution to the
        // window model. Each registry keeps a stable element per pane, so a shell
        // re-render re-parents what is already there rather than rebuilding it.
        paneContent = { paneId ->
            boardProjectIdOfPane(paneId)?.let { boardWindows.contentFor(it) }
                ?: settingsProjectIdOfPane(paneId)?.let { settingsPanes.contentFor(it) }
                ?: analyticsProjectIdOfPane(paneId)?.let { analyticsPanes.contentFor(it) }
                ?: issueIdOfPane(paneId)?.let { issueWindows.contentFor(paneId) }
                // A pane the registries do not know cannot happen while the
                // snapshot and the registries are built from the same state, but
                // an empty div beats a crash if it ever does.
                ?: element("div", "pane-missing")
        },
        // User-owned, unlike the fixed strip Lunicle had before LNL-160: a tab is
        // a working set the reader arranges, so every gesture the toolkit offers —
        // add, close, rename, drag-reorder — is wired to the workspace.
        tabSource = TabSource(
            subscribe = { p ->
                push = p
                deliver()
            },
            onSelect = { workspaceViewModel.onTabSelected(it) },
            // Deliberately NOT wired: the toolkit renders its own "New tab" row at
            // the TOP of the "+" dropdown from this callback, and the order of that
            // menu is Lunicle's (see paneAddMenuItems). The strip's trailing "+"
            // button is off at the mount either way, so nothing else reads it.
            onAdd = null,
            onClose = { workspaceViewModel.onTabClosed(it) },
            onRename = { id, label -> workspaceViewModel.onTabRenamed(id, label) },
            onReorder = { source, target, before ->
                workspaceViewModel.onTabReordered(source, target, before)
            },
            onPaneSelect = { tabId, paneId -> workspaceViewModel.onPaneSelected(tabId, paneId) },
            onPaneClose = { tabId, paneId ->
                // A settings pane may be mid-edit; ask it first, exactly as an
                // issue window is asked. Everything else just goes.
                if (settingsProjectIdOfPane(paneId) != null) {
                    settingsPanes.onCloseClicked(paneId)
                } else if (issueIdOfPane(paneId) != null) {
                    // Routed to the issue's own view model, which decides whether
                    // this closes silently or stops to ask Save / Discard / Keep
                    // editing. The pane disappears when — and only when — the state
                    // drops the issue from openIssues, which `reconcile` mirrors.
                    issueWindows.onCloseClicked(paneId)
                } else {
                    // A board has nothing unsaved in it, so it just goes.
                    workspaceViewModel.onPaneClosed(tabId, paneId)
                }
            },
            // Pressing "+" itself, without going near the dropdown, does the common
            // thing on the pane you are looking at: a new issue on the focused
            // board. Read fresh on every press and gated on the same permission the
            // menu row is, so a reader who may not create issues presses a button
            // that does nothing rather than one that fails.
            onPaneAdd = {
                val projectId = focusedBoardProjectId(workspaceViewModel.stateFlow.value.workspace)
                val screen = projectId?.let { mainViewModel.stateFlow.value.screen(it) }
                if (screen != null && screen.canCreateIssue) {
                    mainViewModel.onNewIssueTapped(screen.projectId)
                }
            },
            paneAddMenuItems = { _ ->
                // Evaluated fresh on every menu open — and on every topbar rebuild,
                // which is what makes the emptiness below load-bearing: the toolkit
                // omits the "+" entirely when a host that describes a menu returns
                // nothing (see shouldShowNewPaneButton). So a reader with nothing to
                // add gets no button rather than one that opens an empty menu.
                //
                // The whole menu is ours, "New tab" included — the toolkit would
                // otherwise render that row first, from TabSource.onAdd, and the
                // order here is deliberate. It reads as two groups, most-used
                // first:
                //
                //   New issue          ← the "+"'s own click, and the common act
                //   Open board ▸       ← not creating anything: a view onto work
                //                        that already exists
                //   New tab
                //   ──────────
                //   New project…       ← rare, and a dialog rather than a pane
                //
                // A separator is only drawn between two groups that both have
                // something in them, so a reader with no create rights gets a menu
                // that reads as one list rather than as a list with gaps where
                // their permissions would have been.
                val state = mainViewModel.stateFlow.value
                val ws = workspaceViewModel.stateFlow.value.workspace
                val screen = focusedBoardProjectId(ws)?.let { state.screen(it) }
                val create = buildList {
                    if (screen != null && screen.canCreateIssue) {
                        add(
                            PaneAddMenuItem(
                                id = "new-issue",
                                label = "New issue",
                                iconHtml = ICON_NEW_ISSUE,
                                // The row the "+" itself does (see onPaneAdd
                                // above, which files an issue on the focused
                                // board). Marked so the menu says so: the
                                // button's own behaviour is otherwise invisible,
                                // and a reader who wanted the common thing has
                                // no way to learn they could have skipped the
                                // menu entirely.
                                isDefault = true,
                                onSelect = { mainViewModel.onNewIssueTapped(screen.projectId) },
                            ),
                        )
                    }
                    if (state.projects.isNotEmpty()) {
                        // "Open board ▸" — the project list, as a flyout. Flat rows
                        // would put one entry per project in the top-level menu and
                        // crowd out everything else as a deployment grows.
                        add(
                            PaneAddMenuItem(
                                id = "new-board",
                                label = "Open board",
                                iconHtml = ICON_BOARD_PANE,
                                children = state.projects.map { project ->
                                    PaneAddMenuItem(
                                        id = "new-board-${project.id}",
                                        label = project.name,
                                        iconHtml = ICON_BOARD_PANE,
                                        // Added to the tab you are ON, never
                                        // jumped to wherever that board already
                                        // is: this menu is how a tab becomes a
                                        // working set. See onBoardAdded, and
                                        // onBoardOpened for the link's rule.
                                        onSelect = { workspaceViewModel.onBoardAdded(project.id) },
                                    )
                                },
                                onSelect = {},
                            ),
                        )
                    }
                    add(
                        PaneAddMenuItem(
                            id = "new-tab",
                            label = "New tab",
                            iconHtml = ICON_NEW_TAB,
                            onSelect = { workspaceViewModel.onTabAdded() },
                        ),
                    )
                }
                val projects = buildList {
                    if (state.canCreateProject) {
                        add(
                            PaneAddMenuItem(
                                id = "new-project",
                                label = "New project…",
                                iconHtml = ICON_NEW_PROJECT,
                                onSelect = { mainViewModel.onNewProjectTapped() },
                            ),
                        )
                    }
                }
                listOf(create, projects)
                    .filter { it.isNotEmpty() }
                    .reduceOrNull { acc, group -> acc + paneAddSeparator("sep-${acc.size}") + group }
                    .orEmpty()
            },
        ),
        // "Board · Lunamux" for a board, and the ticket (or the title, where issue
        // numbers are hidden) for an issue. The pane title is the only place a
        // project name is needed now: an issue pane's key prefix already carries it.
        paneLabel = { _, paneId ->
            // "Board · Lunamux", "Settings · Lunamux", "Analytics · Lunamux". The
            // pane title is the only place a project name is needed: an issue
            // pane's key prefix already carries it.
            fun named(kind: String, projectId: Long): String {
                val name = mainViewModel.stateFlow.value.screen(projectId).project?.name
                return if (name == null) kind else "$kind · $name"
            }
            boardProjectIdOfPane(paneId)?.let { named("Board", it) }
                ?: settingsProjectIdOfPane(paneId)?.let { named("Settings", it) }
                ?: analyticsProjectIdOfPane(paneId)?.let { named("Analytics", it) }
                ?: issueIdOfPane(paneId)?.let { mainViewModel.stateFlow.value.issueWindowTitle(it) }
                ?: "Lunicle"
        },
        paneIcon = { _, paneId ->
            when {
                boardProjectIdOfPane(paneId) != null -> ICON_BOARD_PANE
                settingsProjectIdOfPane(paneId) != null -> ICON_SETTINGS_PANE
                analyticsProjectIdOfPane(paneId) != null -> ICON_ANALYTICS_PANE
                else -> ICON_ISSUE_PANE
            }
        },
        // The sidebar is the navigation now: sections are tabs, rows are panes,
        // and the active row takes the chrome accent. None of it is Lunicle's
        // code — that is the whole point of adopting the toolkit's tree instead
        // of inventing one.
        showSidebar = true,
        // Embedded, the sidebar opens narrower than the toolkit's own 240px —
        // see [EMBEDDED_SIDEBAR_WIDTH_PX]. A seed, not a rule: the toolkit
        // reads the width the user last dragged to and that always wins, so
        // nobody who has set a width of their own is moved by this. Null on
        // the full site, which keeps the toolkit's default.
        defaultSidebarWidthPx = if (isEmbedded()) EMBEDDED_SIDEBAR_WIDTH_PX else null,
        showTabStrip = true,
        // No bottom bar; the identity line rides the topbar's leading edge.
        showBottomBar = false,
        // The product identity sits at the top of the SIDEBAR, above the tabs
        // and panes tree — where Lunamux puts its own. It used to lead the top
        // bar, which put a wordmark and the tab strip on one line and made the
        // strip start somewhere other than the window's edge; and before that it
        // was centred in the middle slot, which the tab strip now claims. The
        // sidebar header is the one place in this chrome that is *about the app*
        // rather than about what is open in it.
        //
        // Cached: the toolkit invokes this on every rerender and re-parents what
        // it is given, so a fresh element each time would be a fresh element to
        // no purpose.
        sidebarHeader = {
            sidebarBrand ?: element("div", "sidebar-brand").also { brandEl ->
                brandEl.children(logoIcon(), element("span", "sidebar-brand-name", "Lunicle"))
                val brandLogoSvg = brandConfig?.logoSvg
                if (brandLogoSvg != null) {
                    // With a deployment brand, the line grows an attribution —
                    // "Hosted by <brand>" (LNL-110) — so the product identity
                    // leads and the badge beneath says who runs this instance.
                    brandEl.appendChild(
                        element("div", "sidebar-brand-host").children(
                            element("span", "sidebar-brand-hosted-by", "Hosted by"),
                            brandLogo(brandLogoSvg),
                        ),
                    )
                }
                // Embedded, the tracker is a panel in somebody else's page: no
                // address bar of its own, and boxed to whatever slot the site
                // gives it. This is the way out to the full site — the same view,
                // in a window that is all tracker.
                if (isEmbedded()) brandEl.appendChild(fullSiteLink())
                sidebarBrand = brandEl
            }
        },
        // Close is intercepted by the host for issue panes (the unsaved-changes
        // question), so the toolkit's own close-confirm dialog must not also ask.
        confirmPaneClose = false,
        // Free-floating, not auto-tiled. Adopting the toolkit's window model
        // (LNL-160) brought its default along with it, and its default is Auto:
        // every pane a tab gains re-tiles every pane it already had. That is right
        // for a terminal, where the panes are peers; here it means opening one
        // issue halves the board behind it, which is not what anybody asked for.
        // In Custom a pane keeps the geometry it was seeded at until the user
        // drags it, so opening something never moves anything else — and the
        // layout dropdown still offers Auto and the rest to anyone who does want
        // a tiled arrangement. Splits and positions the user makes are persisted
        // by the toolkit under LAYOUT_STATE, which rides the same account storage
        // as the workspace, so only tabs the stored state has never heard of land
        // on this default.
        defaultLayoutPreset = LayoutPreset.Custom,
        // A board opens filling the pane area — as an ordinary window, NOT
        // maximised. The distinction is the point: maximised is a mode that
        // suppresses its siblings, so an issue opened over a maximised board would
        // have nowhere to appear. At full size it is simply the bottom of the
        // stack, and everything else lands on top of it.
        //
        // An issue opens at 55 % × 85 %, half again as tall as the toolkit's
        // 45 % × 55 % cascade window and a quarter wider. That default is sized
        // for a pane you glance at; an issue is the thing people read and write
        // in — a description, a comment thread and a history, in a column of
        // prose — and the extra height is what keeps the thread visible without
        // a resize on every open. Both numbers are on the toolkit's 5 % snap
        // grid, so the seed is the same rectangle wherever the cascade puts it.
        // Its ORIGIN is left to that cascade, so two issues opened in a row are
        // offset rather than stacked exactly.
        //
        // Project settings opens at 65 % × 95 % — the largest seed here bar the
        // board, and deliberately so. It is not read through like an issue, but
        // it is the one pane that carries a tab strip with a form under it, and
        // a form is only usable when a whole section is on screen at once: at
        // the toolkit's 45 % × 55 % it arrived a few rows at a time, and at
        // 55 % × 75 % (the first pass at this, LNL-171) it still did. Two steps
        // of the same size — ten points of width, twenty of height — put it
        // here. On the 5 % snap grid, like the rest; the height leaves just
        // enough room for the cascade origin to stay an origin.
        //
        // Analytics keeps the toolkit's own seed.
        paneInitialGeometry = { _, paneId ->
            when {
                boardProjectIdOfPane(paneId) != null ->
                    InitialPaneGeometry(widthPct = 1.0, heightPct = 1.0, xPct = 0.0, yPct = 0.0)
                issueIdOfPane(paneId) != null ->
                    InitialPaneGeometry(widthPct = 0.55, heightPct = 0.85)
                settingsProjectIdOfPane(paneId) != null ->
                    InitialPaneGeometry(widthPct = 0.65, heightPct = 0.95)
                else -> null
            }
        },
        // Everything is closable — including a board, which is just a pane.
        // The alarm bell (LNL-109) and then the sign-in/profile corner, after the
        // toolkit's standard cluster. The bell is Lunicle's own custom element — it
        // flashes from Lunicle's unread state and opens the toolkit's notifications
        // sidebar; see notificationsContent below.
        extraTopbarTrailing = listOf(
            TopbarAction.custom(notificationBell),
            TopbarAction.custom(accountHost),
        ),
        // The notifications sidebar's body. The toolkit owns the slide-in chrome,
        // the close button, Escape and mutual exclusion with the theme/settings
        // panels; Lunicle fills the body with the list. Invoked each time the panel
        // opens, so it renders against a freshly-fetched list. See NotificationsPanel.
        notificationsContent = { notificationsPanel.body() },
        // Instance settings — the account directory, agent-access permission and
        // the per-project rights. App chrome rather than board chrome: it is about
        // the deployment, not about any one project, so it hangs off the toolkit's
        // own app-settings gear rather than sitting in a board pane's header next
        // to that project's gear, where it would read as a second, wider-scoped
        // project setting.
        onAppSettingsActivate = { mainViewModel.onAdminSettingsTapped() },
        // Re-read on every topbar rebuild. An affordance only; AdminRoutes
        // refuses everyone else regardless. See the refresh trigger below, which
        // is what makes a sign-in reach this.
        isAppSettingsAvailable = { mainViewModel.stateFlow.value.canOpenAdminSettings },
        // Every shell re-render rebuilds the pane subtrees, which detaches and
        // re-appends each board pane's content — and a detach silently resets the
        // columns' scrollTop to 0. Opening an issue adds a pane, so it is such a
        // re-render, which is why a single click on a card used to throw the board
        // back to the top (LNL-45). This hook fires after the rebuild, the only
        // point at which writing scrollTop takes: on a detached element it is a
        // no-op. Idempotent, as the hook requires.
        onAfterRefresh = { boardWindows.restoreScroll() },
    )

    scope.launch {
        // The deployment's branding (LNL-110), before anything reads a theme.
        // Injected into the persister so its themes are selectable and its default
        // slot names sit beneath the user's choice, then the chrome font — if the
        // brand names one — is registered with the toolkit and applied. All of it
        // ahead of the shell mount below, which reads the (now brand-merged)
        // persister as it paints. Unbranded ⇒ loadBrand() returns null and none of
        // this runs. Never fatal: a broken brand degrades the look, not the boot.
        brandConfig = runCatching { loadBrand() }.getOrNull()
        brandConfig?.let { brand ->
            persister.setBrand(brand.themes, brand.defaultDarkTheme, brand.defaultLightTheme)
            signInView.brandLogoSvg = brand.logoSvg
            // Pre-filter the Google chooser to the deployment's domain (LNL-125).
            signInView.googleHostedDomain = brand.googleHostedDomain
            // Register every shipped face so its key resolves; the shell adopts each
            // for the surface(s) it covers via AppShellSpec.default{Chrome,Prose,
            // Display}FontFamily, and the Appearance picker lists them as pills.
            registerBrandFonts(brand.fonts)
        }

        // A host embedding the tracker can name both the appearance an unchosen
        // browser seeds on (?theme=) and the themes its slots default to
        // (?darkTheme=/?lightTheme=), so the frame matches the surrounding chrome
        // rather than flashing Lunicle's GitHub Light. Set after the brand above,
        // which it outranks, and before start(), which is where the seed happens —
        // and beneath any stored user choice, which still wins.
        persister.setEmbedDefaults(preferredAppearance(), preferredDarkTheme(), preferredLightTheme())

        // The caller's stored theme and layout — or Lunicle's defaults — fetched
        // *before* the shell mounts, because the shell reads the persister as part
        // of mounting. One small request ahead of first paint, in exchange for
        // never showing the default theme to someone who chose another one: a
        // flinch from dark to light a beat after the page appears is worse than
        // the beat.
        persister.start()

        val handle = mountAppShell(spec, scope)
        // The shell sets document.title from its own spec as it mounts, which
        // clobbers the server-templated brand <title>. Re-apply the brand title
        // after the mount so a deployment's name sticks (LNL-110). Absent ⇒ the
        // shell's "Lunicle" stands.
        brandConfig?.title?.let { document.title = it }
        // Now there is a top bar to repaint. See the declaration above for why this
        // is a lambda handed out rather than the handle itself.
        refreshShell = { handle.refresh() }

        // The bell can open its sidebar now that the shell exists. Opening also
        // fetches the full list (the bell's own poll only ever fetches the count).
        openNotifications = {
            handle.openNotificationsSidebar()
            notificationsViewModel.refreshList()
        }

        // The bell's appearance is driven by two body attributes, Lunamux-style
        // (set on <body> rather than the button, so a top-bar rebuild cannot drop
        // them): whether anyone is signed in — the bell is hidden otherwise — and
        // whether there is anything unread, which colours it and makes it pulse. The
        // CSS in styles.css reads both. See NotificationsBackingViewModel.
        launch {
            notificationsViewModel.stateFlow.collect { st ->
                val signedIn = sessionViewModel.stateFlow.value.user != null
                document.body?.let { body ->
                    if (signedIn && st.hasUnread) {
                        body.setAttribute("data-lnl-notif", "1")
                    } else {
                        body.removeAttribute("data-lnl-notif")
                    }
                }
            }
        }

        // The five-minute poll (LNL-109), signed-in only: a notification is usually
        // somebody else's doing, which this browser learns of only by asking. One
        // cheap count per tick — never the list. The immediate refresh on sign-in is
        // the session collector's job (onSessionChanged, below).
        //
        // A hidden tab asks nothing. Nobody is reading a bell they cannot see, and on
        // a scale-to-zero host the tick is not free the way it looks: each one lands
        // inside the window that keeps a server instance alive, so a tab left open
        // overnight holds one up until morning for a count no one will look at. The
        // listener below closes the gap that skipping opens — coming back refreshes
        // immediately, so a returning tab shows a current count rather than one as
        // old as the moment it was backgrounded.
        launch {
            while (true) {
                delay(5 * 60 * 1000L)
                if (tabIsVisible() && sessionViewModel.stateFlow.value.user != null) {
                    notificationsViewModel.refreshCount()
                }
            }
        }

        document.addEventListener("visibilitychange", {
            if (tabIsVisible() && sessionViewModel.stateFlow.value.user != null) {
                notificationsViewModel.refreshCount()
            }
        })

        // Separate collectors, not a combine(): a combine would couple every
        // board tick to a workspace re-render for no benefit.
        launch {
            // The boards the pane titles were last painted against. A refresh is a
            // full shell re-render, and re-rendering on emissions that change
            // nothing visible is not merely waste — a re-render between a mousedown
            // and its mouseup swallows the click (see deliver). So this stays
            // narrow: only the things a refresh actually repaints trigger one.
            var titledBoards: Map<Long, se.soderbjorn.lunicle.clientserver.BoardState>? = null
            // The permissions the top bar was last built against.
            //
            // A refresh rebuilds the top bar too, and the toolkit OMITS both
            // permission-gated controls outright rather than disabling them — the
            // "+" when paneAddMenuItems comes back empty, the settings gear when
            // isAppSettingsAvailable says no. A control that cannot do anything
            // reads as broken, so there is no disabled state to fall back on.
            // Permissions change on sign-in, which is not a board change.
            var topbarGates: Pair<Boolean, Boolean>? = null
            var openWindows: List<Long>? = null
            mainViewModel.stateFlow.collect { state ->
                // Registries first, snapshot second: the toolkit asks for pane
                // content the moment a pane appears in the snapshot, and the
                // registry must already hold it.
                boardWindows.sync(state, workspaceState.workspace)
                settingsPanes.sync(state, workspaceState.workspace)
                analyticsPanes.sync(workspaceState.workspace)
                issueWindows.sync(state)
                dialogs.render(state)
                boardState = state
                // Before reconciling, so a board the link opens is one this pass
                // can already see. Both collectors call it; see applyDeepLinks.
                applyDeepLinks()
                reconcile()
                latest = snapshotOf(workspaceState.workspace)
                deliver()
                val gates = state.canCreateProject to state.canOpenAdminSettings
                if (state.boards !== titledBoards || gates != topbarGates || state.openIssueIds != openWindows) {
                    titledBoards = state.boards
                    topbarGates = gates
                    openWindows = state.openIssueIds
                    handle.refresh()
                }
                // The address bar follows the focused pane, so the URL is always a
                // link to what is being looked at and a reload lands back on it.
                syncUrl(state.openIssueTicket, focusedBoardProjectId(workspaceState.workspace))
            }
        }
        launch {
            // The workspace's own collector, because the two flows change for
            // entirely unrelated reasons: pressing a tab must repaint the strip
            // whether or not a board tick happens to follow, and a board tick must
            // not wait on one.
            // The pane the focus ring was last moved to by the APP rather than by
            // a press. See below.
            var raisedPane: String? = null
            workspaceViewModel.stateFlow.collect { ws ->
                workspaceState = ws
                // The board registry follows the panes: a board pane that has just
                // appeared needs its view before the toolkit asks for its content.
                boardWindows.sync(boardState, ws.workspace)
                settingsPanes.sync(boardState, ws.workspace)
                analyticsPanes.sync(ws.workspace)
                // The half of the pair the board collector cannot see: signed in,
                // the stored layout lands here, and this is the tick the deep link
                // has been waiting for.
                applyDeepLinks()
                reconcile()
                latest = snapshotOf(ws.workspace)
                deliver()
                // A tab carries panes whose titles the top bar and sidebar render,
                // so the shell has to be rebuilt — `deliver` only re-renders the
                // pane area.
                handle.refresh()
                // Move the focus RING to whatever the app just made active, when
                // that is not the pane the user physically pressed.
                //
                // The toolkit holds focus optimistically on the pane a press
                // landed in, and that hold beats the pushed snapshot for a moment
                // — right nearly always, and wrong for exactly this: the press
                // that opens a project surface lands in the BOARD's toolbar, so
                // the board would keep the ring and the pane that just appeared
                // would open unfocused. `bringPaneToFront` seeds the hold on our
                // pane instead, which is what it is for.
                //
                // Only on a genuine change, so a plain workspace tick cannot steal
                // focus back from wherever the user has since clicked.
                val active = ws.workspace.activeTab?.activePaneId
                if (active != null && active != raisedPane && active != lastUserFocusedPane) {
                    handle.bringPaneToFront(active)
                }
                raisedPane = active
                syncUrl(mainViewModel.stateFlow.value.openIssueTicket, focusedBoardProjectId(ws.workspace))
            }
        }
        launch {
            sessionViewModel.stateFlow.collect { state ->
                signInView.onState(state)
                // The theme belongs to the account, and the account can change
                // under a shell that was mounted once and never again: signing
                // in, signing out, an admin starting or stopping impersonation.
                // The persister holds whose settings it is carrying, so the
                // comparison needs no bookkeeping here — and it is the reason
                // the boot fetch above does not cause a second one, since the
                // session that resolves first is the one it already loaded for.
                //
                // `setThemeSnapshot` is the whole update, not just a repaint:
                // for an app like this one that supplies no `settingsHost`, the
                // toolkit folds a pushed snapshot into the theme manager's own
                // state as well. Before it did, this line painted the new
                // user's theme and the theme manager quietly reverted it from
                // stale state the moment it was touched — see the toolkit's
                // HostThemePushSurvivesManagerTest, which is that bug.
                if (state.isLoaded && state.identity != persister.loadedFor) {
                    handle.setThemeSnapshot(persister.onIdentityChanged())
                }
                // ...and the notifications bell (LNL-109), which clears first and
                // re-fetches the count second so a dot left over from the previous
                // account cannot survive. The signed-in body attribute (which shows
                // or hides the bell entirely) is set here too, so the bell appears
                // the moment a session resolves and disappears on sign-out.
                if (state.isLoaded) {
                    document.body?.let { body ->
                        if (state.user != null) {
                            body.setAttribute("data-lnl-signed-in", "1")
                        } else {
                            body.removeAttribute("data-lnl-signed-in")
                            body.removeAttribute("data-lnl-notif")
                        }
                    }
                    notificationsViewModel.onSessionChanged()
                }
                // The boards belong to whoever is asking, so the session drives
                // them. Identity rather than a boolean — impersonation goes
                // signed-in → signed-in and must still reload.
                mainViewModel.onSessionChanged(
                    identity = state.identity,
                    isKnown = state.isLoaded,
                    // The effective user's, so an admin who is impersonating loses
                    // the button — matching the route, which refuses them too.
                    isSysAdmin = state.isSysAdmin,
                )
                // ...and so does the workspace: a stored layout is the account's,
                // and the signed-out one is discarded rather than merged. Restored
                // panes are adopted by `reconcile` once their boards arrive.
                if (state.isLoaded) {
                    restoreAdopted = false
                    pendingRestoredIssues.clear()
                }
                workspaceViewModel.onSessionChanged(state.identity, isKnown = state.isLoaded)
            }
        }
        launch {
            // The accessible project list reaches the workspace, which needs it for
            // three things: seeding the default layout, naming a tab after its
            // board, and pruning a pane whose project has gone. Its own collector so
            // it fires on the first load rather than waiting for a workspace tick.
            //
            // Only while the board view model is not busy — that is, only when the
            // list is an ANSWER rather than a request in flight. An identity change
            // clears the list and re-fetches it, so a busy tick carries an empty one
            // that means "asking", and the workspace treats the first list after a
            // sign-out as the statement of what a visitor may see. Forwarding the
            // asking-tick would have it seed a layout of no boards at all and then
            // consider the question settled.
            mainViewModel.stateFlow.collect {
                if (!it.isBusy) workspaceViewModel.onProjectsChanged(it.projects)
            }
        }

        // Only the session starts a request. Nothing else has anything to ask for
        // until it says who is asking.
        sessionViewModel.start()
    }
}

/**
 * Whether the `?issue=` / `?projectId=` / `?project=` deep links have been acted
 * on.
 *
 * A page-lifetime one-shot: [start] runs once per page, and the two collectors
 * that can spend it both call `applyDeepLinks`, so whichever completes the pair
 * of conditions last does the work and the other finds it done. Left unset, a
 * link would re-open its board on every later state tick — dismiss the pane, and
 * it would spring back.
 */
private var deepLinksSpent: Boolean = false

/**
 * The project whose board has focus in the active tab, or null.
 *
 * "Which board am I looking at" — the question the app used to answer with a
 * project picker. With a working set it is a property of the *focused pane*: the
 * board that is focused, or, when an issue is, the board its project has in the
 * same tab. Null when the active tab holds no board at all, which is a real state
 * (a fresh tab) and is why the "+" can come back empty.
 */
private fun focusedBoardProjectId(workspace: Workspace): Long? {
    val tab = workspace.activeTab ?: return null
    val active = tab.activePaneId?.let { tab.pane(it) }
    fun boardFor(projectId: Long): Long? =
        tab.panes.filterIsInstance<PaneRef.Board>().firstOrNull { it.projectId == projectId }?.projectId
    return when (active) {
        is PaneRef.Board -> active.projectId
        // Something else about a project has focus — an issue, that project's
        // settings, its analytics: the board it belongs to, if this tab holds
        // one. A "New issue" fired while reading an issue then files it in the
        // same project, which is the only reading that is not a surprise.
        is PaneRef.Issue -> boardFor(active.projectId)
        is PaneRef.Settings -> boardFor(active.projectId)
        is PaneRef.Analytics -> boardFor(active.projectId)
        null -> tab.panes.filterIsInstance<PaneRef.Board>().firstOrNull()?.projectId
    }
}

/**
 * Owns the board panes: one [BoardWindow] per project with a board pane open,
 * created and disposed to match the workspace.
 *
 * [IssueWindows]' sibling, and deliberately the same shape. Each board pane needs
 * a view of its own — it holds a filter box, a scope picker, per-column scroll
 * positions and a drag in flight, none of which two projects can share — so
 * "which boards are open" is a registry rather than a single view that is told to
 * paint something else.
 *
 * There is at most ONE per project, however many tabs hold a board for it. That
 * follows from the pane id being derived from the project (see [PaneRef]): two
 * tabs showing the same board are the same pane id, so the toolkit asks for one
 * element and would re-parent it into whichever tab painted last. One view per
 * project makes that structural rather than a race.
 */
private class BoardWindows(
    private val mainViewModel: MainScreenBackingViewModel,
    private val titleFor: se.soderbjorn.lunicle.client.TicketTitleLookup,
    /** Reports the raw press before the view-model intent — see [main]'s `lastUserFocusedPane`. */
    private val onPaneMousedown: (paneId: String) -> Unit,
    /** Opens a project surface beside the board — see the toolbar's trailing entries. */
    private val onOpenProjectPane: (PaneRef) -> Unit,
) {
    private val views = mutableMapOf<Long, BoardWindow>()

    /** Create and dispose board views to match the panes, then paint them. */
    fun sync(state: MainScreenBackingViewModel.State, workspace: Workspace) {
        val wanted = workspace.tabs
            .flatMap { tab -> tab.panes.filterIsInstance<PaneRef.Board>() }
            .mapTo(mutableSetOf()) { it.projectId }
        views.keys.filterNot { it in wanted }.toList().forEach { views.remove(it) }
        wanted.forEach { projectId ->
            val view = views.getOrPut(projectId) { create(projectId) }
            view.render(state.screen(projectId))
        }
    }

    /** The pane content for [projectId]'s board, building it if this is the first ask. */
    fun contentFor(projectId: Long): HTMLElement = viewFor(projectId).root

    /**
     * The view for [projectId], built on the first ask.
     *
     * Painted as it is handed out: the toolkit asks for a pane's content and its
     * header controls as the pane appears, which can be before the collector's
     * next tick, and an empty board for a frame reads as a board with nothing on
     * it.
     */
    private fun viewFor(projectId: Long): BoardWindow {
        val view = views.getOrPut(projectId) { create(projectId) }
        view.render(mainViewModel.stateFlow.value.screen(projectId))
        return view
    }

    /** Put every open board's columns back where the reader left them — see [BoardWindow.restoreScroll]. */
    fun restoreScroll() = views.values.forEach { it.restoreScroll() }

    private fun create(projectId: Long): BoardWindow {
        val view = BoardWindow(
            projectId = projectId,
            viewModel = mainViewModel,
            titleFor = titleFor,
            onOpenAnalytics = { onOpenProjectPane(PaneRef.Analytics(projectId)) },
            onOpenSettings = { onOpenProjectPane(PaneRef.Settings(projectId)) },
        )
        // A mousedown anywhere in this pane is a focus report; the sidebar's active
        // row and the address bar both follow. Capture phase, so cards and controls
        // inside still work.
        view.root.addEventListener(
            "mousedown",
            { onPaneMousedown(PaneRef.Board(projectId).paneId) },
            true,
        )
        return view
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
    /** The app-wide open-editor register each window joins — LNL-84, see [EditorDirtyRegistry]. */
    private val editorRegistry: EditorDirtyRegistry,
    /** Reports the raw press before the view-model intent — see [main]'s `lastUserFocusedPane`. */
    private val onPaneMousedown: (paneId: String) -> Unit,
    private val openComment: (IssueBackingViewModel, CommentBackingViewModel.Existing?) -> Unit,
    /** Ticket references for each window — links and the `PREFIX-` autocomplete (LNL-139). */
    private val ticketSource: TicketSource,
) {
    private class Entry(
        val viewModel: IssueBackingViewModel,
        val view: IssueWindow,
        val scope: CoroutineScope,
    )

    private val entries = LinkedHashMap<Long, Entry>()

    /**
     * The element each issue pane is filled with, by issue id — empty until the
     * window inside it can be built.
     *
     * Separate from [entries], and that separation is what makes a **restored**
     * layout work. The toolkit asks for a pane's content the instant the pane
     * appears and caches what it is given for the pane's life; on a reload the
     * pane appears as soon as the stored workspace does, which is well before its
     * project's board has been fetched — and an issue window cannot be built
     * without that board (statuses, priorities, permissions all come from it).
     * Answering "nothing yet" there cached an empty div for ever: the pane came
     * back on every reload with no issue in it.
     *
     * So the host is handed over immediately and stays the same element for as
     * long as the pane exists; [create] mounts the window *into* it a tick or two
     * later, when the board lands. Same bargain the forum's window struck for a
     * different reason — see the pane-id constants in the client's PaneRef.
     */
    private val hosts = mutableMapOf<Long, HTMLElement>()

    /** Create and dispose windows to match the state. */
    fun sync(state: MainScreenBackingViewModel.State) {
        val wanted = state.openIssueIds.toSet()
        entries.keys.filter { it !in wanted }.toList().forEach { dispose(it) }
        state.openIssues.forEach { open ->
            if (open.issueId !in entries) create(open, state)
        }
    }

    /**
     * The pane content for [paneId] — the window's host, built empty if the
     * window itself is not ready yet. See [hosts].
     */
    fun contentFor(paneId: String): HTMLElement? =
        issueIdOfPane(paneId)?.let { hostFor(it) }

    private fun hostFor(issueId: Long): HTMLElement = hosts.getOrPut(issueId) {
        val host = element("div", "issue-window-host")
        // A mousedown anywhere in the pane is a focus report; the sidebar's active
        // row and the address bar both follow. Capture phase, so fields and
        // buttons inside still work. On the host rather than on the window's own
        // root, so it survives the window being mounted into it later.
        host.addEventListener(
            "mousedown",
            {
                onPaneMousedown(issuePaneId(issueId))
                mainViewModel.onIssueWindowFocused(issueId)
            },
            true,
        )
        host
    }

    /**
     * The pane chrome's × was clicked. Forwarded to the window's own view
     * model, which owns the decision — close silently, or ask about unsaved
     * changes first.
     */
    fun onCloseClicked(paneId: String) {
        issueIdOfPane(paneId)?.let { entries[it]?.viewModel?.onCloseRequested() }
    }

    /**
     * Ask every open window to close — the profile dialog's "Restore default
     * layout" (LNL-160).
     *
     * Through each window's own close, not a bulk drop, so unsaved work still
     * stops to ask. Iterated over a copy: a window that closes without asking
     * does so synchronously and mutates the map underneath us.
     */
    fun closeAll() {
        entries.values.toList().forEach { it.viewModel.onCloseRequested() }
    }

    private fun create(open: OpenIssueWindow, state: MainScreenBackingViewModel.State) {
        val id = open.issueId
        // No board, no vocabularies, no window. The issue's OWN board, not
        // whichever one is in front: a window outlives the pane it was opened
        // from, and building it from another project's board would hand the editor
        // the wrong statuses, priorities and permissions. Absent while that board
        // is still loading — `sync` runs again on the tick it arrives.
        val board = state.boards[open.projectId] ?: return
        val windowScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val viewModel = IssueBackingViewModel(
            issueId = id,
            board = board,
            // The per-user hide-issue-numbers choice for this project (LNL-105),
            // read once at open from the board view model that owns it.
            hideIssueNumbers = mainViewModel.isHidingIssueNumbers(board.project.id),
            // The column a board "Create issue…" was fired from, so the draft opens
            // filed there (LNL-124); null for the ordinary "+" / hotkey.
            initialStatusId = open.initialStatusId,
            storage = storage,
            scope = windowScope,
            onFinished = { changed ->
                // The window is done: drop it from the state, which drops the
                // pane from the next snapshot, which is what actually closes
                // it. The refresh covers the close paths that wrote something
                // without passing through onWritten (a discarded draft whose
                // uploads made the board's attachment counts stale).
                mainViewModel.onIssueWindowClosed(id)
                if (changed) mainViewModel.refreshBoard(open.projectId)
            },
            // Any write the board should reflect — save, delete, comment —
            // refreshes it immediately, window still open. This is the
            // redesign's "saving an issue updates the board" requirement.
            onWritten = { mainViewModel.refreshBoard(open.projectId) },
            editorRegistry = editorRegistry,
        )
        val view = IssueWindow(
            viewModel = viewModel,
            scope = windowScope,
            dialogHost = dialogHost,
            openComment = { editing -> openComment(viewModel, editing) },
            // Links for every accessible project's references, and the description
            // editor's "PREFIX-" autocomplete (LNL-139). See the bootstrap's ticketSource.
            ticketSource = ticketSource,
        )
        view.mount(hostFor(id))
        entries[id] = Entry(viewModel, view, windowScope)
    }

    private fun dispose(id: Long) {
        val entry = entries.remove(id) ?: return
        entry.view.dispose()
        entry.scope.cancel()
        // The host goes with the window. It is the pane's content and the pane is
        // going too; keeping it would leave a detached element per issue ever
        // opened, and a re-opened issue would mount a second window into a div
        // that still held the first one's DOM.
        hosts.remove(id)
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
    /** The app-wide open-editor register the comment modal joins — LNL-84, see [EditorDirtyRegistry]. */
    private val editorRegistry: EditorDirtyRegistry,
    /** Ticket references for the comment editor — the `PREFIX-` autocomplete (LNL-139). */
    private val ticketSource: TicketSource,
    /**
     * Re-fetch the session, run when the admin dialog writes an instance switch
     * that rides on it (LNL-137). Threaded from `start`'s session view model
     * because this class does not own one. See [openAdminSettings].
     */
    private val reloadSession: () -> Unit,
) {
    private var current: ActiveDialog = ActiveDialog.None
    private var dismiss: (() -> Unit)? = null
    // The resolution dialog while it is up. Held so its state changes (a resolution
    // picked, a version added) repaint it in place rather than remounting — see
    // render() and openResolution (LNL-134).
    private var resolutionView: ResolutionDialog? = null
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
        // The resolution dialog is controlled: while it stays open for the same issue,
        // a changed selection or a freshly added version repaints it in place rather
        // than tearing it down and rebuilding — a remount would drop the resolution
        // already chosen. See ResolutionDialog and MainScreenBackingViewModel (LNL-134).
        val next = state.dialog
        val here = current
        if (next is ActiveDialog.ChooseResolution && here is ActiveDialog.ChooseResolution &&
            next.issueId == here.issueId
        ) {
            current = next
            resolutionView?.render(next)
            return
        }
        if (state.dialog == current) return
        close()
        current = state.dialog
        when (val dialog = state.dialog) {
            ActiveDialog.None -> Unit
            ActiveDialog.NewProject -> openProject(projects = state.projects)
            is ActiveDialog.ChooseResolution -> openResolution(dialog)
            ActiveDialog.AdminSettings -> openAdminSettings()
            is ActiveDialog.NewSprint -> openNewSprint(dialog)
            is ActiveDialog.PlanSprint -> openPlanSprint(dialog)
            is ActiveDialog.CompleteSprint -> openCompleteSprint(dialog)
        }
    }

    /**
     * The instance settings dialog. Its own scope and view model, like the project
     * one — it fetches and writes, so its `collect` must die with it.
     *
     * `changed = false` on close: nothing this dialog writes is on the board. The
     * agent-access flag changes what a *token* may do, not what a card shows, so
     * reloading would be a round-trip that repaints the same pixels.
     */
    private fun openAdminSettings() {
        val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val viewModel = AdminSettingsBackingViewModel(
            storage = storage,
            scope = dialogScope,
            onInstanceSettingChanged = reloadSession,
        )
        val view = AdminSettingsDialog(
            viewModel = viewModel,
            scope = dialogScope,
            // `changed` only when the Projects tab reordered or deleted something:
            // the picker draws its order and membership from a separate load, so
            // that is exactly what a reload has to catch up with. The agent-access
            // toggle changes nothing on the board and still closes at no cost. See
            // AdminSettingsBackingViewModel.State.projectsChanged.
            onDismiss = {
                mainViewModel.onDialogClosed(changed = viewModel.stateFlow.value.projectsChanged)
            },
        )
        view.mount(host)
        scope = dialogScope
        dismiss = { view.dismiss() }
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
            // Borrowed from the window behind this modal rather than fetched
            // again: it is the same project's membership, and the issue has
            // already been loaded. See CommentBackingViewModel.mentionableNames.
            mentionableNames = issueViewModel.stateFlow.value.mentionableUsers.map { it.name },
            onFinished = { changed ->
                closeComment()
                // Tell the issue rather than the board: the issue owns the
                // comment list, and it re-fetches so the *server* decides the
                // author's name and whether this caller may edit it.
                if (changed) issueViewModel.onCommentsChanged()
            },
            // Joins the open-editor register so a project switch asks before
            // discarding a comment being typed — LNL-84.
            editorRegistry = editorRegistry,
        )
        val view = CommentDialog(viewModel, localScope, ticketSource)
        view.mount(host)
        commentScope = localScope
        commentDismiss = { view.dismiss() }
    }

    /**
     * The resolution picker, held open over a drag that has not been sent yet.
     * No scope and no view model: it renders what the ActiveDialog carries and
     * reports one of two intents back.
     */
    /**
     * The three sprint dialogs.
     *
     * No scope and no view model between them, unlike the project and admin ones:
     * each is a form over a decision the main view model already has every fact
     * for, so the intent it calls back into is the whole of its behaviour. That is
     * also why none of them calls `onDialogClosed(changed = true)` on success —
     * the intents replace the board with what the server answered, so a reload
     * would fetch the state that is already on screen.
     */
    private fun openNewSprint(dialog: ActiveDialog.NewSprint) {
        val view = NewSprintDialog(
            projectId = dialog.projectId,
            onNamed = { projectId, name -> mainViewModel.onNewSprintNamed(projectId, name) },
            onCancel = { mainViewModel.onDialogClosed(changed = false) },
        )
        view.mount(host)
        dismiss = { view.dismiss() }
    }

    private fun openPlanSprint(dialog: ActiveDialog.PlanSprint) {
        val view = PlanSprintDialog(
            dialog = dialog,
            onSave = { projectId, sprintId, ids -> mainViewModel.onSprintPlanned(projectId, sprintId, ids) },
            onCancel = { mainViewModel.onDialogClosed(changed = false) },
        )
        view.mount(host)
        dismiss = { view.dismiss() }
    }

    private fun openCompleteSprint(dialog: ActiveDialog.CompleteSprint) {
        val view = CompleteSprintDialog(
            dialog = dialog,
            onComplete = { projectId, sprintId, to -> mainViewModel.onSprintCompleted(projectId, sprintId, to) },
            onCancel = { mainViewModel.onDialogClosed(changed = false) },
        )
        view.mount(host)
        dismiss = { view.dismiss() }
    }

    private fun openResolution(dialog: ActiveDialog.ChooseResolution) {
        val view = ResolutionDialog(
            ticket = dialog.ticket,
            resolutions = dialog.resolutions,
            onResolutionPicked = { mainViewModel.onResolutionPicked(it) },
            onFixedVersionPicked = { mainViewModel.onResolutionFixedVersionPicked(it) },
            onVersionAdded = { mainViewModel.onResolutionVersionAdded(it) },
            onVersionRenamed = { id, name -> mainViewModel.onResolutionVersionRenamed(id, name) },
            onVersionDeleted = { mainViewModel.onResolutionVersionDeleted(it) },
            onConfirm = { mainViewModel.onResolutionConfirmed() },
            onCancel = { mainViewModel.onResolutionCancelled() },
        )
        view.mount(host)
        // The initial paint: the dialog opens with nothing selected, so Confirm starts
        // disabled and the version cell hidden until a resolution is picked.
        view.render(dialog)
        resolutionView = view
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

    /**
     * "New project…" — the one project form that is still a modal.
     *
     * An existing project's settings open as a pane beside its board (LNL-160,
     * see SettingsPanes). This one cannot: there is no project to hang a pane
     * off until it exists, and four fields answered once and dismissed is the
     * shape a modal is for.
     */
    private fun openProject(projects: List<ProjectSummary>) {
        val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val viewModel = EditProjectBackingViewModel(
            existing = null,
            otherProjects = projects,
            canConfigure = true,
            canConfigureIdentity = true,
            storage = storage,
            scope = dialogScope,
            // A project that has just been made should be on screen, so its
            // board is opened in the tab you are on.
            onFinished = { changed, saved ->
                mainViewModel.onDialogClosed(changed, openProjectId = saved?.id)
            },
            // A project that does not exist yet has no per-user view preference
            // to carry; the switch belongs to the settings pane, not here.
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
        resolutionView = null
    }
}

/**
 * How wide the left sidebar opens when the tracker is embedded in another
 * site's page, in CSS pixels (LNL-188).
 *
 * The toolkit's own default is 240px, which is a fair width for a window that
 * is all tracker and too generous for a panel inside somebody else's layout:
 * the board is boxed into whatever the host's slot leaves, and on Lunamux the
 * last column fell off the right edge. The sidebar is the cheapest column to
 * take it out of, because the widest thing in it is chrome rather than content
 * — the brand line and [fullSiteLink]'s way out.
 *
 * The number comes off that line. `.sidebar-brand` wraps, so the brand reads as
 * "Lunicle" over "OPEN FULL SITE ↗", and the wider of the two rows is the link:
 * 138px to its arrow, plus the header's own 12px of trailing padding. That is
 * the cut the ticket asked for — just after the way out — and it is what
 * shortening the wording ("Open the full site" → "Open full site") bought
 * another twenty-odd pixels of.
 *
 * Below this the link would clip, which is the floor rather than a target: the
 * tabs/panes tree below it clips its rows to an ellipsis (with the toolkit's
 * hover tooltip behind them) and would go on being useful much narrower, but
 * the one row that cannot say less is the one offering the way out.
 *
 * A **seed**, handed to the toolkit as `AppShellSpec.defaultSidebarWidthPx`: it
 * applies to a browser that has never dragged the handle, and a width the user
 * did choose is stored under `UiSettingKeys.SIDEBAR_WIDTH` and outranks it. So
 * an embedded reader gets a tighter board while a signed-in user who widened
 * the sidebar keeps what they set — embedded or not.
 */
private const val EMBEDDED_SIDEBAR_WIDTH_PX: Int = 152

/**
 * The "Open full site" link shown in the sidebar's brand line when the
 * app [isEmbedded].
 *
 * The destination is this frame's own URL, not a hard-coded host: the frame is
 * already served from the tracker's own origin, and [syncIssueUrl] keeps
 * `?issue=` on it, so the same address opened top-level is the same project and
 * the same focused issue — the view you were looking at, in a window that is
 * all tracker. Hard-coding a domain would land on someone else's board and
 * would be wrong in local dev besides.
 *
 * `href` is refreshed on every click because [syncIssueUrl] moves the URL under
 * us as issues open and close; the attribute is still kept current enough for
 * middle-click and "copy link address" to be right.
 *
 * `_blank`, never `_top`: the framed page belongs to the embedding site, which
 * is not ours to navigate away. `rel="noopener"` because the new tab has no
 * reason to keep a handle back to this frame.
 */
private fun fullSiteLink(): HTMLElement {
    val link = document.createElement("a") as HTMLAnchorElement
    link.className = "topbar-full-site-link"
    // "Open full site", not "Open the full site": three characters of article
    // are three characters of sidebar width, and the sidebar is what LNL-188
    // is trying to give back to the board.
    link.textContent = "Open full site ↗"
    link.href = window.location.href
    link.target = "_blank"
    link.rel = "noopener"
    link.title = "Open this board in a tab of its own"
    link.addEventListener("click", { link.href = window.location.href })
    return link
}

/**
 * Register a deployment's font faces with the toolkit as injected presets
 * (LNL-110, "approach B").
 *
 * The toolkit's font resolution walks builtin ∪ injected presets (see lunula's
 * FontPresets / ThemeCssVars), so registering the families here is what lets
 * arbitrary company faces reach the sidebar/topbar, tab strip, pane headers,
 * prose content and headings — surfaces a plain Lunicle stylesheet override
 * could not, because they are painted inline on `:root` from a preset key. It
 * is also what makes each face appear as a pickable pill in the Appearance
 * sidebar. The `@font-face` that supplies the actual bytes lives in the
 * deployment's served `fonts.css`, document-wide, so one registration per face
 * covers every surface its key lands on.
 *
 * Registration only *makes each key resolvable*; a surface actually adopts a key
 * through [AppShellSpec.defaultChromeFontFamily] / [AppShellSpec.defaultProseFontFamily]
 * / [AppShellSpec.defaultDisplayFontFamily] (see the spec), which the shell
 * re-applies on every paint — a direct `applySidebarFontFamily` call would be
 * cleared the next time the toolkit reapplies its host font vars.
 *
 * Each [BrandFont.cssStack] ends in its declared generic fallback so a missing
 * `@font-face` degrades to a sane system face of the right category (a serif
 * display font to a system serif, not a sans).
 */
private fun registerBrandFonts(fonts: List<BrandFont>) {
    if (fonts.isEmpty()) return
    registerFontPresets(
        fonts.map { font ->
            FontPreset(
                key = font.presetKey,
                displayName = font.family,
                cssStack = font.cssStack,
                detectFamily = null,
                bundled = true,
                kind = FontKind.Proportional,
            )
        },
    )
}

/**
 * Owns the project-settings panes: one [ProjectDialog] per project with a
 * settings pane open.
 *
 * [BoardWindows]' sibling, and the same shape for the same reasons. What is
 * different is what it is wrapping: [ProjectDialog] was written as a modal, and
 * is a modal still when it is creating a project ("New project…", which has no
 * project to hang a pane off). Here it is handed a [PaneShell] instead — a body
 * and a footer with no backdrop, no title bar and no Escape, because the pane
 * chrome around it already supplies all three.
 */
private class SettingsPanes(
    private val storage: StorageRepository,
    private val mainViewModel: MainScreenBackingViewModel,
    /** Reports the raw press before the view-model intent — see [main]'s `lastUserFocusedPane`. */
    private val onPaneMousedown: (paneId: String) -> Unit,
    /**
     * The view model said it is done — Cancel, or a project deleted. Closes the
     * pane, which is what "done" means when there is no modal to dismiss.
     */
    private val onFinished: (projectId: Long) -> Unit,
) {
    private class Entry(val view: ProjectDialog, val scope: CoroutineScope)

    private val entries = mutableMapOf<Long, Entry>()

    /** The host for each pane, stable for its life — see [IssueWindows.hosts] for why. */
    private val hosts = mutableMapOf<Long, HTMLElement>()

    /** Create and dispose views to match the panes. */
    fun sync(state: MainScreenBackingViewModel.State, workspace: Workspace) {
        val wanted = workspace.tabs
            .flatMap { tab -> tab.panes.filterIsInstance<PaneRef.Settings>() }
            .mapTo(mutableSetOf()) { it.projectId }
        entries.keys.filterNot { it in wanted }.toList().forEach { dispose(it) }
        wanted.forEach { projectId ->
            if (projectId !in entries) create(projectId, state)
        }
    }

    fun contentFor(projectId: Long): HTMLElement {
        val host = hostFor(projectId)
        if (projectId !in entries) create(projectId, mainViewModel.stateFlow.value)
        return host
    }

    /** The pane's × was pressed: let the form decide, exactly as an issue window does. */
    fun onCloseClicked(paneId: String) {
        settingsProjectIdOfPane(paneId)?.let { entries[it]?.view?.requestClose() }
    }

    private fun hostFor(projectId: Long): HTMLElement = hosts.getOrPut(projectId) {
        val host = element("div", "settings-pane-host")
        host.addEventListener(
            "mousedown",
            { onPaneMousedown(PaneRef.Settings(projectId).paneId) },
            true,
        )
        host
    }

    /**
     * Build the form, once this project's board has arrived.
     *
     * The board is what says whether this reader may configure anything — the
     * same two permissions the modal was opened with. Absent while it is still
     * loading, so `sync` runs again on the tick it lands and the pane fills in
     * then; the host is already on screen, which is what keeps the pane from
     * caching an empty div (see [IssueWindows.hosts]).
     */
    private fun create(projectId: Long, state: MainScreenBackingViewModel.State) {
        val screen = state.screen(projectId)
        val project = screen.board?.project ?: return
        val paneScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val viewModel = EditProjectBackingViewModel(
            existing = project,
            otherProjects = state.projects,
            canConfigure = screen.canEditProject,
            canConfigureIdentity = screen.canRenameProject,
            storage = storage,
            scope = paneScope,
            // `changed` refreshes the boards; `saved` is only ever this project.
            // Closing the pane is the "finished" half — there is no modal to
            // dismiss, so the pane is what goes away.
            onFinished = { changed, _ ->
                if (changed) mainViewModel.reload()
                onFinished(projectId)
            },
            // The per-user hide-issue-numbers choice (LNL-105) is owned by the
            // board view model: seed the switch from it, and write a change
            // straight back through it so the board beside this pane updates at
            // once — which is the whole argument for this being a pane.
            hideIssueNumbers = mainViewModel.isHidingIssueNumbers(projectId),
            persistHideIssueNumbers = { mainViewModel.setIssueNumbersHidden(projectId, it) },
        )
        val view = ProjectDialog(viewModel, paneScope, shell = PaneShell())
        view.mount(hostFor(projectId))
        entries[projectId] = Entry(view, paneScope)
    }

    private fun dispose(projectId: Long) {
        val entry = entries.remove(projectId) ?: return
        entry.view.dismiss()
        entry.scope.cancel()
        hosts.remove(projectId)
    }
}

/**
 * Owns the analytics panes — [SettingsPanes] with nothing to save.
 *
 * Simpler in the one way that matters: there is nothing to edit here, so a pane
 * closing asks nobody anything and the view needs no board behind it. It takes a
 * project id and counts.
 */
private class AnalyticsPanes(
    private val storage: StorageRepository,
    private val onPaneMousedown: (paneId: String) -> Unit,
    /**
     * The view's own Close was pressed — closes the pane.
     *
     * It used to dismiss a modal. Left unwired it was a button that did nothing:
     * the view still draws it (it is the same view either way — see
     * [DialogShell]), and in a pane the thing to take away is the pane.
     */
    private val onFinished: (projectId: Long) -> Unit,
) {
    private class Entry(val view: StatisticsDialog, val scope: CoroutineScope)

    private val entries = mutableMapOf<Long, Entry>()
    private val hosts = mutableMapOf<Long, HTMLElement>()

    fun sync(workspace: Workspace) {
        val wanted = workspace.tabs
            .flatMap { tab -> tab.panes.filterIsInstance<PaneRef.Analytics>() }
            .mapTo(mutableSetOf()) { it.projectId }
        entries.keys.filterNot { it in wanted }.toList().forEach { dispose(it) }
        wanted.forEach { projectId -> if (projectId !in entries) create(projectId) }
    }

    fun contentFor(projectId: Long): HTMLElement {
        val host = hostFor(projectId)
        if (projectId !in entries) create(projectId)
        return host
    }

    private fun hostFor(projectId: Long): HTMLElement = hosts.getOrPut(projectId) {
        val host = element("div", "analytics-pane-host")
        host.addEventListener(
            "mousedown",
            { onPaneMousedown(PaneRef.Analytics(projectId).paneId) },
            true,
        )
        host
    }

    private fun create(projectId: Long) {
        val paneScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val view = StatisticsDialog(
            viewModel = StatisticsBackingViewModel(storage = storage, scope = paneScope),
            projectId = projectId,
            scope = paneScope,
            onDismiss = { onFinished(projectId) },
            modal = PaneShell(),
        )
        view.mount(hostFor(projectId))
        entries[projectId] = Entry(view, paneScope)
    }

    private fun dispose(projectId: Long) {
        val entry = entries.remove(projectId) ?: return
        entry.view.dismiss()
        entry.scope.cancel()
        hosts.remove(projectId)
    }
}
