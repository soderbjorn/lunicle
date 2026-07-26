/**
 * Entry point for the Lunicle Kotlin/JS web frontend.
 *
 * Bootstraps the app inside the lunula shell: constructs the view
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
 * empty, and there is no bottom bar. The window layout is still not persisted —
 * every reload lands on the default one, deliberately — but the theme is: a
 * signed-in user's appearance, slot choices and hand-built themes follow their
 * account rather than their browser. See [ThemePersister], which is the only
 * thing that distinguishes the two.
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.url.URLSearchParams
import se.soderbjorn.lunula.core.Appearance
import se.soderbjorn.lunula.core.AppearanceShape
import se.soderbjorn.lunula.web.layout.LayoutPreset
import se.soderbjorn.lunula.web.themeeditor.FontKind
import se.soderbjorn.lunula.web.themeeditor.FontPreset
import se.soderbjorn.lunula.web.themeeditor.registerFontPresets
import se.soderbjorn.lunula.web.shell.AppShellSpec
import se.soderbjorn.lunula.web.shell.InitialPaneGeometry
import se.soderbjorn.lunula.web.shell.PaneAddMenuItem
import se.soderbjorn.lunula.web.shell.PaneSnapshotEntry
import se.soderbjorn.lunula.web.shell.TabBadge
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
import se.soderbjorn.lunicle.client.viewmodel.ConversationBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.EditProjectBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.EditorDirtyRegistry
import se.soderbjorn.lunicle.client.viewmodel.ForumBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.ForumComposerBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.ForumPostBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.IssueBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.MainScreenBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.MessageComposerBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.MessagesBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.NotificationsBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.OpenIssueWindow
import se.soderbjorn.lunicle.client.viewmodel.StatisticsBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.UnreadBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.SessionBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.ShellBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.ShellTab
import se.soderbjorn.lunicle.clientserver.NotificationKind
import se.soderbjorn.lunicle.clientserver.NotificationSummary
import se.soderbjorn.lunicle.clientserver.ProjectSummary

/**
 * The one tab's id when there is no tab strip — invisible, but the toolkit still
 * needs one.
 *
 * Used only while the forum master toggle is off, or in an embed of the issue
 * tracker alone. With tabs, each tab's id is its [ShellTab.key], so that the
 * toolkit's notion of "which tab" and the URL's `?tab=` are the same string
 * rather than two spellings that have to be kept in step.
 */
private const val TAB_ID = "main"

/**
 * Whether a toolkit tab id names the tab the issue tracker lives in.
 *
 * Two ids answer yes, which is the whole reason this is a function rather than a
 * comparison written out twice: with a strip it is [ShellTab.ISSUES]'s key, and
 * without one it is [TAB_ID] — the invisible single tab, which *is* the tracker.
 * Getting that second case wrong would take the "+" away from the app as it
 * stands before the forum toggle is even on.
 *
 * @param tabId the id the toolkit reports — our own key coming back.
 * @return true when issue-tracker actions belong on this tab.
 * @see ShellTab
 */
private fun isIssuesTab(tabId: String): Boolean = tabId == TAB_ID || tabId == ShellTab.ISSUES.key

/** The board pane's id: non-closable, opens maximised. */
private const val BOARD_PANE_ID = "board"

/**
 * The Discussion and Messages tabs' main panes.
 *
 * Both are the same shape as the board: exactly one, non-closable, opening
 * maximised. LNL-58 fills them with placeholders; LNL-59 and LNL-60 put the
 * forum list and the conversation list in them.
 */
private const val FORUM_PANE_ID = "forums"
private const val MESSAGES_PANE_ID = "messages"

/**
 * The forum post window's pane id — one constant, not one per post, and that is
 * the whole of LNL-62's departure from the issue tracker.
 *
 * An issue pane is `issue-$id`, so opening a second issue adds a second pane and
 * the board fills up with windows. LNL-30 decided the forum does the opposite:
 * clicking a post **reuses** the window that is already open. Encoding the post id
 * here would make that unexpressible — a different id is a different pane, so the
 * toolkit would tear the old window down and lay a new one out, which is the same
 * thing as opening a new window with extra steps.
 *
 * With one fixed id, reuse is structural rather than something [ForumWindows] has
 * to remember to do: there is at most one forum post pane, its content element is
 * stable, and reading a different post changes what is inside it. Nothing is
 * re-laid-out, and — because no pane appeared or disappeared — the toolkit is not
 * even asked to re-render.
 */
private const val FORUM_POST_PANE_ID = "forum-post"

/**
 * The conversation window's pane id — one constant, exactly as
 * [FORUM_POST_PANE_ID] is, and for the identical reason.
 *
 * LNL-64 moved the Messages tab off a hand-drawn CSS grid and onto real panes. The
 * thread used to be the right-hand column of a `grid-template-columns: 280px
 * minmax(0, 1fr)` inside the *one* Messages pane, with a `border-right` standing in
 * for a splitter — while Discussion, which is the same list-to-detail interaction
 * one tab over, had two real panes. That cost a draggable splitter, pane chrome on
 * the thread, participation in auto layout, focus tracking, the toolkit's "a new
 * pane force-restores a maximized sibling" rule, and visual consistency with the
 * tab beside it.
 *
 * `MessagesPane`'s own doc argued that a layout which *hid* the list to show a
 * thread would make every switch a round trip through a back link. That was true
 * and it was not the alternative: the forum does not hide its list either — the
 * post window is a **sibling**, both visible at once. The option that was rejected
 * was not the option that was available, which is a sequencing artefact rather than
 * a bad judgement: LNL-60 was written before LNL-62 existed.
 *
 * Constant rather than `conversation-$id` for the reason spelled out on
 * [FORUM_POST_PANE_ID]: a different pane id *is* a different pane, so encoding the
 * conversation would make the toolkit tear the window down and lay a new one out —
 * a new window with extra steps. With one id there is at most one such pane, its
 * host element is stable for the app's life, and opening a different conversation
 * swaps this app's own children inside it, causing no shell re-render at all.
 */
private const val CONVERSATION_PANE_ID = "conversation"

/** The panes that cannot be closed, and that fill the whole area — one per tab. */
private val MAIN_PANE_IDS = setOf(BOARD_PANE_ID, FORUM_PANE_ID, MESSAGES_PANE_ID)

/**
 * The default size of a detail window (an issue, a forum post, a conversation),
 * as fractions of the pane area — see [AppShellSpec.paneInitialGeometry]. Slightly
 * wider and much taller than the toolkit's 45 % × 55 % default: these windows are
 * mostly a tall column (title, fields, a description/body editor, then comments),
 * so height is what they want. Position is left to the toolkit's cascade.
 */
private const val DETAIL_WINDOW_WIDTH_PCT = 0.52
private const val DETAIL_WINDOW_HEIGHT_PCT = 0.85

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
 * The project this tab was last on: `?projectId=42`.
 *
 * An id rather than a name, so the link survives the project being renamed —
 * which is the whole reason this exists alongside the embed's `?project=<name>`
 * rather than reusing it. Junk parses to null and the usual resolution order
 * takes over.
 */
private fun preferredProjectId(): Long? =
    URLSearchParams(window.location.search).get("projectId")?.toLongOrNull()?.takeIf { it > 0 }

/**
 * Whether the forum feature is switched on: `?forums=1`.
 *
 * A client-side flag and nothing more. Its job is to keep the forum out of
 * public view until it is good enough, not to protect anything — the server does
 * not gate the API on it, deliberately. See LNL-30, which settles that.
 *
 * Read through [queryValue] rather than `URLSearchParams`, along with `?tab=`
 * below, so that the parameters this file *writes* and the ones it *reads* go
 * through one parser. `?project=` still uses `URLSearchParams` because a project
 * name can contain spaces and genuinely needs decoding; a toggle spelt `1` and a
 * tab key spelt `discussion` never do.
 */
private fun forumsEnabled(): Boolean = queryValue(window.location.search, "forums") == "1"

/**
 * Whether the browser-side demo runs instead of the real app: `?demo=1` (LNL-146).
 *
 * A client-side flag exactly like [forumsEnabled], read through the same parser.
 * When set, [start] hands every view model a [DemoLunicleApi] backed by an
 * in-memory fixture world rather than the real [HttpLunicleApi], so the whole app
 * runs with no server calls, no persistence and no auth — a reload resets to the
 * seed. It changes nothing for `issues.lunicle.dev`, where the flag is never set.
 */
private fun demoEnabled(): Boolean = queryValue(window.location.search, "demo") == "1"

/**
 * The tab this page was opened on: `?tab=issues|discussion|messages`.
 *
 * One parameter doing two jobs, which is what makes tab position deep-linkable
 * for free: on the full site it is the tab to select, and embedded it also picks
 * *which embed this is*. Null for absent and for unrecognised alike — see
 * [ShellBackingViewModel], which decides what that means.
 */
private fun preferredTab(): ShellTab? = ShellTab.fromKey(queryValue(window.location.search, "tab"))

/**
 * The appearance a host page asked this embed to seed on: `?theme=dark|light|auto`.
 *
 * A client-side hint read through the same parser as [forumsEnabled] and
 * [preferredTab], for the one job the toolkit's own defaults cannot do: a browser
 * with nothing stored seeds on Lunicle's Light default (GitHub Light, LNL-149),
 * which clashes with a dark host embedding the tracker in an iframe. The Lunamux
 * site is hard-committed dark and passes `?theme=dark`; [ThemePersister] takes it
 * as the *default* appearance — beneath any signed-in user's saved choice, which
 * still wins — so only an unchosen/signed-out browser is moved.
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
 * The conversation this page was opened on: `?conversation=42`.
 *
 * The Messages tab's `?issue=`, and read at load exactly as that one is — there is
 * no popstate listener anywhere in this app and this does not add one. A link in a
 * notification e-mail is followed in a fresh tab, so load time is the only moment
 * it has to work; see the server's `conversationUrl`, which writes these.
 */
private fun preferredConversation(): Long? =
    queryValue(window.location.search, "conversation")?.toLongOrNull()?.takeIf { it > 0 }

/**
 * The message to land on inside that conversation: `?message=99`.
 *
 * Read once and never written back — unlike `?conversation=`, which [syncUrl]
 * keeps current. A message id is a position *inside* a view rather than a view, so
 * writing it would make the URL change as somebody scrolled, and a link copied
 * mid-thread would reopen at a message nobody chose. `AppUrl.nextSearch` therefore
 * treats it as one of the unknown parameters it preserves, which is exactly the
 * right behaviour for it.
 */
private fun preferredMessage(): Long? =
    queryValue(window.location.search, "message")?.toLongOrNull()?.takeIf { it > 0 }

/**
 * The Discussion tab's deep link: `?forum=3&post=17&comment=42`.
 *
 * The same split the Messages tab draws, applied to the forum. `?forum=` and
 * `?post=` are **views** — which room, and which thread in it — so [syncUrl] keeps
 * them current and a null clears them, exactly as `?issue=` and `?conversation=`
 * work. `?comment=` is a **position inside** a view, so it is read here once and
 * never written: writing it back would make the URL change as somebody scrolled,
 * and a link copied mid-thread would reopen at a comment nobody chose.
 *
 * Read at load and only at load, like every other deep link in this app. There is
 * no popstate listener and this adds none.
 */
private fun preferredForum(): Long? =
    queryValue(window.location.search, "forum")?.toLongOrNull()?.takeIf { it > 0 }

private fun preferredPost(): Long? =
    queryValue(window.location.search, "post")?.toLongOrNull()?.takeIf { it > 0 }

private fun preferredComment(): Long? =
    queryValue(window.location.search, "comment")?.toLongOrNull()?.takeIf { it > 0 }

/**
 * Put the focused issue, the current project and the active tab in the address
 * bar, so a reload comes back to what is on screen.
 *
 * `replaceState`, never `pushState` — Back must not walk through every issue
 * ever opened, every project ever picked or every tab ever selected, and
 * embedded, "back" is the site's history. The hash is never touched either;
 * embedded, it belongs to the site's router.
 *
 * The rule itself lives in [nextSearch], which is a pure function over the query
 * string and is tested there. This is only the effect: what is preserved, what
 * is cleared and what "no change" means are all claims about other people's
 * parameters, and they are asserted in `AppUrlTest` rather than here where
 * nothing can reach them.
 *
 * @param tab the active tab's key, or null when there is no tab strip — which
 *   means "leave `?tab=` alone" rather than "remove it", so that a parameter
 *   somebody typed with the toggle off is treated as any other unknown one.
 */
private fun syncUrl(
    ticket: String?,
    projectId: Long?,
    tab: String?,
    conversation: Long?,
    forum: Long?,
    post: Long?,
) {
    val query = nextSearch(
        search = window.location.search,
        ticket = ticket,
        projectId = projectId,
        tab = tab,
        conversation = conversation?.toString(),
        forum = forum?.toString(),
        post = post?.toString(),
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
    // that talks to the server — or, in demo mode (LNL-146), one in-memory world
    // and no server at all. The swap is here and only here: `StorageRepository`
    // and every view model above it take the `LunicleApi` interface and never know
    // which implementation they were handed.
    val storage = if (demoEnabled()) StorageRepository(DemoLunicleApi()) else StorageRepository()
    // The one register of open editors, shared by everything that can hold unsaved
    // work — every issue window, the comment modal, the forum composers — and read
    // by the main view model to guard a project switch (LNL-84). One instance,
    // threaded to each below, so the picker's "you have unsaved changes" question
    // sees editors on tabs the picker is not even on.
    val editorRegistry = EditorDirtyRegistry()
    val mainViewModel = MainScreenBackingViewModel(storage, editorRegistry = editorRegistry)
    val sessionViewModel = SessionBackingViewModel(storage)

    // Ticket references (LNL-139), built once and handed to every window family that
    // owns an editor or renders issue text. `prefixes` is the reader's accessible
    // projects, read live so one granted mid-session takes effect; `lookup` turns a
    // prefix into that project's issues for the `PREFIX-` autocomplete — the current
    // board's are already in memory, another accessible project's are fetched once
    // (the editor caches), and an unknown or inaccessible prefix offers nothing.
    // Titles for expanding a rendered reference to "PREFIX-N: Title" (LNL-144).
    // Filled from every board the session loads — the current one live off state
    // below, another accessible project's the once its `lookup` fetches it or the
    // reader visits it — and keyed by the canonical, upper-cased reference so a
    // `lnl-1` written in any case finds it. It never fetches on its own: a title
    // it does not hold yet just leaves that reference bare until the board arrives,
    // which is the pre-LNL-144 behaviour, so no reference is ever worse off.
    val ticketTitles = mutableMapOf<String, String>()
    fun titleKey(prefix: String, number: Long) = "${prefix.uppercase()}-$number"
    fun indexBoardTitles(board: se.soderbjorn.lunicle.clientserver.BoardState?) {
        board ?: return
        board.issues.forEach { ticketTitles[titleKey(board.project.namePrefix, it.number)] = it.title }
    }
    val ticketSource = TicketSource(
        prefixes = { mainViewModel.stateFlow.value.projects.map { it.namePrefix } },
        lookup = { prefix ->
            val current = mainViewModel.stateFlow.value
            val project = current.projects.firstOrNull { it.namePrefix.equals(prefix, ignoreCase = true) }
            val issues = when {
                project == null -> emptyList()
                current.board?.project?.id == project.id -> current.board?.issues.orEmpty()
                else -> runCatching { storage.board(project.id) }.getOrNull()?.issues.orEmpty()
            }
            val namePrefix = project?.namePrefix ?: prefix
            issues.forEach { ticketTitles[titleKey(namePrefix, it.number)] = it.title }
            issues.map { TicketOption("$namePrefix-${it.number}", it.number, it.title) }
        },
        titleFor = { ticket ->
            // The current board is authoritative and live, so read it straight off
            // state; anything else comes from what the cache has accumulated.
            val current = mainViewModel.stateFlow.value
            val onCurrentBoard = current.board?.project?.namePrefix?.equals(ticket.prefix, ignoreCase = true) == true
            if (onCurrentBoard) {
                current.board?.issues?.firstOrNull { it.number == ticket.number }?.title
            } else {
                ticketTitles[titleKey(ticket.prefix, ticket.number)]
            }
        },
    )
    // Which tabs exist, decided once from the URL. Read synchronously below —
    // `showTabStrip` and the pane predicates are part of a spec built before the
    // shell mounts, so this answer cannot arrive asynchronously. See
    // ShellBackingViewModel for why nothing but the *active* tab ever changes.
    // The Discussion tab's forums. Told which project to show by the board's
    // collector below, never fetching one itself — see ForumBackingViewModel.
    val forumViewModel = ForumBackingViewModel(storage)
    // The Messages tab's conversations. Unlike the forum, this is told nothing
    // about which project the board is on: conversations are instance-wide, so
    // there is no project for it to follow and switching one must not reload it.
    // See MessagesBackingViewModel.
    val messagesViewModel = MessagesBackingViewModel(storage)
    // The Discussion tab's badge. Its own view model because it is the one thing on
    // this screen that spans every project the caller can see, where the forum pane
    // knows about the one the board is on. See UnreadBackingViewModel.
    val unreadViewModel = UnreadBackingViewModel(storage)
    val shellViewModel = ShellBackingViewModel(
        forumsEnabled = forumsEnabled(),
        isEmbedded = isEmbedded(),
        preferredTab = preferredTab(),
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Keep every board the reader passes through in the ticket-title cache (LNL-144),
    // so a reference to a project they have already opened expands to its title even
    // once they have moved on to another board. The current board is read live in
    // `titleFor` above and needs no help here; this is what makes the *other* boards
    // resolve. Costs nothing extra — it reuses boards state already holds.
    scope.launch { mainViewModel.stateFlow.collect { indexBoardTitles(it.board) } }

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

    // The board's latest state, kept because the snapshot is now a function of
    // two flows and each collector has to be able to rebuild it from the other's
    // most recent value. Seeded from the flow rather than from a default, so a
    // tab pressed before the first board tick still produces a whole snapshot.
    var boardState: MainScreenBackingViewModel.State = mainViewModel.stateFlow.value

    // ...and the Discussion tab's, for the same reason: the snapshot is a function
    // of three flows now, since the forum post window's pane comes and goes with
    // `openPostId`. Seeded from the flow rather than from a default, so a tab
    // pressed before the first forum tick still produces a whole snapshot.
    var forumState: ForumBackingViewModel.State = forumViewModel.stateFlow.value

    // ...and the Messages tab's, for the reason above: since LNL-64 the thread is a
    // pane of its own, so the snapshot is a function of four flows rather than
    // three. Seeded from the flow rather than from a default, so a tab pressed
    // before the first messages tick still produces a whole snapshot.
    var messagesState: MessagesBackingViewModel.State = messagesViewModel.stateFlow.value

    // ...and the Discussion badge's. A fifth flow, and the smallest of them: one
    // boolean, which is the whole of what the tab strip needs from it.
    var unreadState: UnreadBackingViewModel.State = unreadViewModel.stateFlow.value

    /**
     * The `?tab=` value to write, or null when there is no tab strip.
     *
     * Null rather than `"issues"` in that case: with no strip there is no tab
     * position to remember, and writing one would put a parameter in the URL of
     * an app that has no tabs — visible to anyone who copies the link, and
     * misleading about what they would get back.
     */
    fun activeTabKey(): String? =
        shellViewModel.stateFlow.value.takeIf { it.showTabStrip }?.activeTab?.key

    /**
     * Put everything on screen in the address bar.
     *
     * One function rather than the same six arguments assembled at each of the four
     * collectors that call it: they are all saying the same sentence, and a fifth
     * parameter added at three of four call sites is exactly the sort of drift that
     * leaves one tab's deep link quietly not working.
     *
     * Every value is read fresh from where it lives, including the ones the calling
     * collector has in hand — a collector's own emission is the current value of its
     * own flow, so nothing is stale, and it saves each of them knowing the shape of
     * the other three.
     */
    fun pushUrl() {
        val forum = forumViewModel.stateFlow.value
        syncUrl(
            ticket = boardState.openIssueTicket,
            projectId = boardState.currentProject?.id,
            tab = activeTabKey(),
            conversation = messagesViewModel.stateFlow.value.openConversationId,
            forum = forum.selectedForumId,
            post = forum.openPostId,
        )
    }

    // The pane the user last physically pressed, kept by the bootstrap's own
    // mousedown listeners. This is what lets `deliver` tell a focus REPORT
    // (the state echoing a gesture the toolkit already performed) from a focus
    // COMMAND (open-existing-issue, deep link) — see deliver's comment.
    var lastUserFocusedPane: String? = null

    val boardWindow = BoardWindow(mainViewModel, ticketSource.titleFor)
    // The project navigator that rides the shell's top bar, left of the tabs —
    // LNL-84. Built here beside the board because it is driven by the same state
    // flow (see the board collector below); mounted via topbarLeading. Stable for
    // the app's life, so the toolkit re-appends this element on every top-bar
    // rebuild and the picker's menu listeners survive.
    val projectBar = ProjectBar(mainViewModel)
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


    // The Discussion tab's pane — the forum list and the posts in it. Its lifetime
    // is the app's — it is a tab's non-closable main pane, like the board — so it
    // takes the app scope rather than one of its own.
    //
    // Note there is no circularity here any more, and the Messages tab's pane says
    // the same about itself: the composer lives in the window rather than being a
    // modal this pane opens, so the pane needs nothing from the windows at all. It
    // used to need a `var` declared before it and assigned after.
    val forumPane = ForumPane(
        viewModel = forumViewModel,
        scope = scope,
        openManager = { dialogs.openForumManager(forumViewModel) },
    )
    forumPane.start()
    // A mousedown in the forum list is a focus report, as it is on the board: the
    // pane snapshot's active pane has to follow the window the reader is in, or the
    // next push would raise the post window over a list they had just clicked into.
    forumPane.root.addEventListener(
        "mousedown",
        {
            lastUserFocusedPane = FORUM_PANE_ID
            forumViewModel.onPostWindowFocused(false)
        },
        true,
    )

    // The Messages tab's pane — the conversation *list*, since LNL-64; the thread
    // is a window beside it. Its lifetime is the app's — it is a tab's non-closable
    // main pane, like the board and the forum — so it takes the app scope rather
    // than one of its own.
    //
    //
    // Note there is no circularity here, unlike the Discussion tab's pane and its
    // windows: since LNL-64 the composer lives in the window rather than being a
    // modal this pane opens, so the pane needs nothing from the window at all.
    val messagesPane = MessagesPane(viewModel = messagesViewModel, scope = scope)
    messagesPane.start()
    // A mousedown in the conversation list is a focus report, as it is in the forum
    // list: the pane snapshot's active pane has to follow the window the reader is
    // in, or the next push would raise the thread over a list they just clicked.
    messagesPane.root.addEventListener(
        "mousedown",
        {
            lastUserFocusedPane = MESSAGES_PANE_ID
            messagesViewModel.onConversationWindowFocused(false)
        },
        true,
    )

    val issueWindows = IssueWindows(
        storage = storage,
        mainViewModel = mainViewModel,
        dialogHost = dialogHost,
        scope = scope,
        editorRegistry = editorRegistry,
        onPaneMousedown = { paneId -> lastUserFocusedPane = paneId },
        openComment = { issueViewModel, editing -> dialogs.openComment(issueViewModel, editing) },
        ticketSource = ticketSource,
    )

    // Rebuilding the shell, for the two things that need it from outside the
    // coroutine below: the forum window's title, and nothing else so far. A lambda
    // rather than the handle itself, so nothing out here has to name the toolkit's
    // type — and a no-op until the shell exists, which is the honest answer for a
    // title that changes before there is a top bar to paint it in.
    var refreshShell: () -> Unit = {}

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
     * Take the reader to a notification's destination, then close the panel.
     *
     * The in-app half of LNL-109's "not a deep link": the notification carries the
     * app's own ids, and this switches to the right tab and opens the window from
     * the same view-model entry points a board click uses — no URL is written or
     * re-parsed. Cross-project issue and forum destinations select the project
     * first; the issue window then waits (briefly) for that project's board to
     * arrive before opening, because a card can only be opened where it is drawn.
     */
    fun navigateToNotification(n: NotificationSummary) {
        when (n.kind) {
            NotificationKind.ISSUE_CREATED,
            NotificationKind.ISSUE_UPDATED,
            NotificationKind.ISSUE_ASSIGNED,
            NotificationKind.ISSUE_MENTIONED,
            -> {
                shellViewModel.onTabSelected(ShellTab.ISSUES)
                val projectId = n.projectId
                val issueId = n.issueId
                if (projectId != null && issueId != null) {
                    val current = mainViewModel.stateFlow.value
                    val onProject = current.currentProject?.id == projectId
                    val loaded = current.board?.issues?.any { it.id == issueId } == true
                    if (onProject && loaded) {
                        mainViewModel.onIssueOpened(issueId)
                    } else {
                        if (!onProject) mainViewModel.onProjectSelected(projectId)
                        scope.launch {
                            // Bounded, so a since-deleted issue does not leave a
                            // coroutine waiting for a board row that never comes.
                            withTimeoutOrNull(10_000) {
                                mainViewModel.stateFlow.first { s ->
                                    s.currentProject?.id == projectId &&
                                        s.board?.issues?.any { it.id == issueId } == true
                                }
                                mainViewModel.onIssueOpened(issueId)
                            }
                        }
                    }
                }
            }
            NotificationKind.MESSAGE -> {
                shellViewModel.onTabSelected(ShellTab.MESSAGES)
                // start() handles the deferred open: it records the target and
                // applies it when the conversation list arrives. See
                // MessagesBackingViewModel.start.
                n.conversationId?.let { messagesViewModel.start(it, n.messageId) }
            }
            NotificationKind.FORUM_POST,
            NotificationKind.FORUM_COMMENT,
            -> {
                shellViewModel.onTabSelected(ShellTab.DISCUSSION)
                val projectId = n.projectId
                if (projectId != null && mainViewModel.stateFlow.value.currentProject?.id != projectId) {
                    mainViewModel.onProjectSelected(projectId)
                }
                // start() records the forum/post and spends them on the first list
                // that comes back for the (possibly just-switched) project. See
                // ForumBackingViewModel.start.
                n.forumId?.let { forumViewModel.start(it, n.postId) }
            }
        }
        // Close the panel behind the navigation. Synchronously (the toolkit's
        // force-close, used for mutual exclusion) rather than the animated toggle:
        // navigating fires a burst of shell rebuilds — tab select, project switch,
        // the issue window opening — and an animated close racing those rebuilds
        // gets re-mounted mid-slide and never finishes. A synchronous close flips
        // the state first, so every following rebuild simply omits the panel.
        closeNotificationsSidebar()
        refreshShell()
    }

    /**
     * Open the issue a clicked ticket reference names (LNL-139).
     *
     * The reference carries only a `PREFIX-NUMBER`, but a prefix is unique across
     * the instance, so it alone says which project. This looks that project up,
     * switches to it if the reader is elsewhere, waits (briefly) for its board, and
     * opens the window through the same [MainScreenBackingViewModel.onIssueOpened] a
     * card click uses — no URL is written or re-parsed, the same "not a deep link"
     * as [navigateToNotification]. A reference whose prefix names no project the
     * reader can see, or whose number names no issue on the board, opens nothing:
     * the honest answer, and the one a dead deep link already gives (see
     * MainScreenBackingViewModel.deepLinkedWindow).
     */
    fun navigateToTicket(ticket: Ticket) {
        val current = mainViewModel.stateFlow.value
        val projectId = current.projects
            .firstOrNull { it.namePrefix.equals(ticket.prefix, ignoreCase = true) }
            ?.id ?: return
        shellViewModel.onTabSelected(ShellTab.ISSUES)
        val onProject = current.currentProject?.id == projectId
        val here = current.board?.issues?.firstOrNull { it.number == ticket.number }?.id
        if (onProject && here != null) {
            mainViewModel.onIssueOpened(here)
        } else {
            if (!onProject) mainViewModel.onProjectSelected(projectId)
            scope.launch {
                // Bounded, so a reference to a since-deleted issue does not leave a
                // coroutine waiting for a board row that never comes.
                withTimeoutOrNull(10_000) {
                    val board = mainViewModel.stateFlow.first { s ->
                        s.currentProject?.id == projectId && s.board?.project?.id == projectId
                    }.board
                    board?.issues?.firstOrNull { it.number == ticket.number }?.id?.let {
                        mainViewModel.onIssueOpened(it)
                    }
                }
            }
        }
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

    val windows = ForumWindows(
        storage = storage,
        forumViewModel = forumViewModel,
        dialogHost = dialogHost,
        editorRegistry = editorRegistry,
        onPaneMousedown = { paneId -> lastUserFocusedPane = paneId },
        onTitleChanged = { refreshShell() },
        ticketSource = ticketSource,
    )

    // ...and the Messages tab's, which is `ForumWindows` transposed. See
    // CONVERSATION_PANE_ID for why the Messages tab has windows at all now.
    val conversations = ConversationWindows(
        storage = storage,
        messagesViewModel = messagesViewModel,
        dialogHost = dialogHost,
        onPaneMousedown = { paneId -> lastUserFocusedPane = paneId },
        onTitleChanged = { refreshShell() },
    )

    /**
     * The issue tracker's panes: the board, plus one per open issue.
     *
     * Shared by both shapes below, because they are the same tab — with tabs on
     * it is the Issues one, and with tabs off it is the only one. Building it
     * once is what makes "switching tabs does not tear down open issue windows"
     * true by construction rather than by care: the pane list does not know
     * which tab is active, so selecting Discussion cannot shorten it.
     */
    fun issuePanes(state: MainScreenBackingViewModel.State): List<PaneSnapshotEntry> =
        listOf(PaneSnapshotEntry(BOARD_PANE_ID)) + state.openIssueIds.map { PaneSnapshotEntry(issuePaneId(it)) }

    /**
     * The Discussion tab's panes: the forum list, plus the window when there is one
     * — a post being read, or a post being written.
     *
     * Exactly one window at most, which is [FORUM_POST_PANE_ID]'s whole argument, and
     * "is there one" is `hasWindow` rather than either field because reading and
     * writing share that pane. The list pane is first, as every tab's main pane is —
     * `deliver`'s first delivery relies on that, and so does the toolkit's rule that
     * a pane arriving *after* the main one force-restores it from maximised, which
     * is what lands a deep-linked post window on top of a maximised forum list.
     */
    fun forumPanes(forum: ForumBackingViewModel.State): List<PaneSnapshotEntry> =
        listOf(PaneSnapshotEntry(FORUM_PANE_ID)) +
            (if (forum.hasWindow) listOf(PaneSnapshotEntry(FORUM_POST_PANE_ID)) else emptyList())

    /**
     * The Messages tab's panes: the conversation list, plus the thread when one is
     * open.
     *
     * [forumPanes] one tab over, and deliberately so — the two tabs are the same
     * list-to-detail interaction and this is the whole of what LNL-64 changed about
     * this one. Every clause of that function's comment applies here: exactly one
     * window at most, and the list pane first so the toolkit's force-restore rule
     * lands a deep-linked thread on top of a maximised list.
     */
    fun messagesPanes(messages: MessagesBackingViewModel.State): List<PaneSnapshotEntry> =
        listOf(PaneSnapshotEntry(MESSAGES_PANE_ID)) +
            (
                if (messages.hasWindow) {
                    listOf(PaneSnapshotEntry(CONVERSATION_PANE_ID))
                } else {
                    emptyList()
                }
                )

    fun snapshotOf(
        state: MainScreenBackingViewModel.State,
        shell: ShellBackingViewModel.State,
        forum: ForumBackingViewModel.State,
        messages: MessagesBackingViewModel.State,
        unread: UnreadBackingViewModel.State,
    ): TabListSnapshot {
        // No strip: exactly the snapshot Lunicle produced before LNL-30 — one
        // invisible tab holding the board and the issue windows.
        if (!shell.showTabStrip) {
            return TabListSnapshot(
                tabs = listOf(
                    TabSnapshotEntry(
                        id = TAB_ID,
                        label = "Lunicle",
                        panes = issuePanes(state),
                        activePaneId = state.focusedIssueId?.let(::issuePaneId) ?: BOARD_PANE_ID,
                    ),
                ),
                activeTabId = TAB_ID,
            )
        }
        // Per-project tab hiding (LNL-96). The master toggle (shell.tabs) decides
        // which tabs a deployment has at all; this narrows that to what the current
        // project offers, so a project that switched its discussions or messages
        // off drops that tab while the others keep theirs. Read off the board's own
        // project — the flags ride on ProjectSummary — and left untouched when no
        // board has loaded yet, since there is nothing to narrow to.
        val project = state.board?.project
        val visibleTabs = shell.tabs.filter { tab ->
            when (tab) {
                ShellTab.ISSUES -> true
                ShellTab.DISCUSSION -> project?.discussionsEnabled ?: true
                ShellTab.MESSAGES -> project?.messagesEnabled ?: true
            }
        }
        // If the tab the shell thinks is active was just hidden, highlight the
        // first surviving one — an empty strip highlight is a control that lies,
        // and Issues is always present. The shell's own activeTab is left as it is;
        // this only decides what the strip draws as selected.
        val activeTabId =
            if (visibleTabs.any { it.key == shell.activeTab.key }) shell.activeTab.key
            else visibleTabs.firstOrNull()?.key ?: shell.activeTab.key
        return TabListSnapshot(
            tabs = visibleTabs.map { tab ->
                when (tab) {
                    ShellTab.ISSUES -> TabSnapshotEntry(
                        id = tab.key,
                        label = tab.label,
                        panes = issuePanes(state),
                        activePaneId = state.focusedIssueId?.let(::issuePaneId) ?: BOARD_PANE_ID,
                    )
                    // The forum list, plus at most one post window — LNL-62. The
                    // active pane follows the window the reader is actually in
                    // rather than always naming the post: see
                    // ForumBackingViewModel.State.isPostFocused, and `deliver`'s
                    // focus-report rule, which this is what feeds.
                    ShellTab.DISCUSSION -> TabSnapshotEntry(
                        id = tab.key,
                        label = tab.label,
                        // A dot, never a number — LNL-30 settles that forum volume
                        // is unbounded and a count there creates inbox-zero
                        // pressure for something nobody is obliged to read. Null
                        // rather than a Dot with a false inside it, because the
                        // toolkit's badge is "draw this" rather than "draw this if".
                        badge = if (unread.hasUnreadPosts) TabBadge.Dot else null,
                        panes = forumPanes(forum),
                        activePaneId =
                            if (forum.hasWindow && forum.isPostFocused) FORUM_POST_PANE_ID
                            else FORUM_PANE_ID,
                    )
                    // The conversation list, plus at most one thread window —
                    // LNL-64. The active pane follows the window the reader is
                    // actually in, exactly as Discussion's does: see
                    // MessagesBackingViewModel.State.isConversationFocused.
                    ShellTab.MESSAGES -> TabSnapshotEntry(
                        id = tab.key,
                        label = tab.label,
                        // A number, capped at 99+ by the toolkit. Pushed
                        // unconditionally rather than behind an `if`, because
                        // TabBadge.Count(0) renders nothing — so there is one code
                        // path and no chance of a stale badge surviving because the
                        // branch that clears it was not taken.
                        badge = TabBadge.Count(messages.unreadMessageCount),
                        panes = messagesPanes(messages),
                        activePaneId =
                            if (messages.hasWindow && messages.isConversationFocused) {
                                CONVERSATION_PANE_ID
                            } else {
                                MESSAGES_PANE_ID
                            },
                    )
                }
            },
            activeTabId = activeTabId,
        )
    }

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
     * **The first delivery seeds the main pane only.** A tab's main pane must
     * exist before any window pane arrives: the toolkit's "a new pane
     * force-restores a maximized sibling" rule is what puts a deep-linked issue
     * window on top of the maximised board, and it only fires for panes that
     * arrive after the board exists. The full snapshot follows a tick later.
     *
     * Both rules were written for a single tab and are generalised here rather
     * than left reading the first one. A tab *switch* is a real change and must
     * always be pushed — it is not a focus report, because the user pressed a
     * tab rather than a pane, and suppressing it would leave the strip
     * highlighting one tab while another one's content showed.
     */
    fun deliver() {
        val p = push ?: return
        val snap = latest
        val prev = lastDelivered
        if (prev != null) {
            if (snap == prev) return
            // Everything except which pane is active, across every tab. If the
            // tabs themselves differ in any other way — a pane opened or closed,
            // a label repainted, the active TAB moved — this is not a focus
            // report and goes through.
            val structureUnchanged = prev.activeTabId == snap.activeTabId &&
                prev.tabs.map { it.id to (it.panes to it.label) } ==
                snap.tabs.map { it.id to (it.panes to it.label) }
            val activePane = snap.tabs.firstOrNull { it.id == snap.activeTabId }?.activePaneId
            if (structureUnchanged && activePane != null && activePane == lastUserFocusedPane) {
                lastDelivered = snap
                return
            }
        }
        if (prev == null) {
            val tab = snap.tabs.firstOrNull { it.id == snap.activeTabId }
            if (tab != null && tab.panes.size > 1) {
                // The main pane is the first one, in every tab this file builds.
                val main = tab.panes.first()
                val seeded = snap.copy(
                    tabs = snap.tabs.map {
                        if (it.id == tab.id) it.copy(panes = listOf(main), activePaneId = main.id) else it
                    },
                )
                lastDelivered = seeded
                p(seeded)
                scope.launch { deliver() }
                return
            }
        }
        lastDelivered = snap
        p(snap)
    }

    // The theme follows the account; nothing else does. See ThemePersister for
    // why the window layout is deliberately still forgotten every load.
    val persister = ThemePersister(storage, scope)

    // The deployment's branding (LNL-110), or null when unbranded. Fetched once
    // inside the boot coroutine below, before the shell mounts, and captured here
    // so the spec's late-bound lambdas (topbarCenter's logo) see it. Null leaves
    // everything at Lunicle's own look.
    var brandConfig: Brand? = null

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
        // null on an unbranded instance ⇒ prose falls back to the same monospace
        // stack Lunicle always shipped, byte-identical. Independent of chrome, so
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
        paneContent = { paneId ->
            when (paneId) {
                BOARD_PANE_ID -> boardWindow.root
                FORUM_PANE_ID -> forumPane.root
                MESSAGES_PANE_ID -> messagesPane.root
                // A stable element for the app's whole life, whichever post is in
                // it — which is what lets one window be reused for the next post
                // without the toolkit having to re-render. See ForumWindows.
                FORUM_POST_PANE_ID -> windows.host
                // ...and the same for the thread. See ConversationWindows.
                CONVERSATION_PANE_ID -> conversations.host
                else -> issueWindows.contentFor(paneId)
                    // A pane the registry does not know cannot happen while the
                    // snapshot and the registry are built from the same state,
                    // but an empty div beats a crash if it ever does.
                    ?: element("div", "issue-window-missing")
            }
        },
        // Fixed, always — Lunicle's tabs are the app's, never the user's. With
        // the strip hidden that is the one invisible tab it always was; with it
        // shown it is Issues/Discussion/Messages, which nobody may add to, close
        // or reorder. `TabSource.fixed` will not take the tab-mutation callbacks
        // at all, so the guarantee is structural rather than five omissions that
        // have to stay omitted — including the "New tab" row in the "+" menu,
        // which the toolkit renders from whether `onAdd` is null.
        tabSource = TabSource.fixed(
            subscribe = { p ->
                push = p
                deliver()
            },
            onSelect = { id ->
                // The toolkit reports the id it was given, so this is our own
                // key coming back; an unknown one is dropped rather than
                // guessed at. See ShellBackingViewModel.onTabSelected, which
                // also refuses a tab this shell does not offer.
                ShellTab.fromKey(id)?.let(shellViewModel::onTabSelected)
            },
            onPaneClose = { _, paneId ->
                // Routed to the issue's own view model, which decides whether
                // this closes silently or stops to ask Save / Discard / Keep
                // editing. The pane disappears when — and only when — the state
                // drops the issue from openIssueIds.
                issueWindows.onCloseClicked(paneId)
                // ...and the forum post window, which asks nothing: there is
                // nothing unsaved in a post being read. It still goes through the
                // view model rather than closing here, so that "closed by hand"
                // and "closed because it was deleted" are one path.
                windows.onCloseClicked(paneId)
                // ...and the conversation window, which asks nothing either.
                conversations.onCloseClicked(paneId)
            },
            // Pressing "+" itself, without going near the dropdown, does the
            // common thing: a new issue. The dropdown is then only needed for
            // the rarer New project. Gated on the same permission the menu row
            // is, and read fresh on every press — a user who may not create
            // issues presses a button that does nothing rather than one that
            // fails.
            // Gated on the tab as well, for the same reason the menu below is:
            // the toolkit hides the button on Discussion and Messages, but the
            // callback is also reachable from the "New" hotkey, which is not.
            onPaneAdd = { tabId ->
                if (isIssuesTab(tabId) && mainViewModel.stateFlow.value.canCreateIssue) {
                    mainViewModel.onNewIssueTapped()
                }
            },
            paneAddMenuItems = { tabId ->
                // Evaluated fresh on every menu open — and on every topbar
                // rebuild, which is what makes the emptiness below load-bearing:
                // the toolkit omits the "+" entirely when a host that describes
                // a menu returns nothing for the active tab (see
                // shouldShowNewPaneButton). So returning an empty list here is
                // how "+" disappears on Discussion and Messages (LNL-79) —
                // everything it offers is an issue-tracker action, and a button
                // whose whole menu is about the tab you are not on is chrome
                // pointing somewhere else. The same emptiness is also what
                // hides it from a reader with no create rights (LNL-32).
                val state = mainViewModel.stateFlow.value
                buildList {
                    if (!isIssuesTab(tabId)) return@buildList
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
            when (paneId) {
                BOARD_PANE_ID -> "Board"
                FORUM_PANE_ID -> "Forums"
                MESSAGES_PANE_ID -> "Messages"
                FORUM_POST_PANE_ID -> windows.paneLabel()
                CONVERSATION_PANE_ID -> conversations.paneLabel()
                else -> issueIdOf(paneId)
                    ?.let { mainViewModel.stateFlow.value.issueWindowTitle(it) }
                    ?: "Board"
            }
        },
        // Lunicle's minimal chrome: no sidebar (nor its toggle), no tab strip,
        // no world switcher (no worldSource). What remains of the standard
        // cluster — "+", layout, dark/light cycle, theme manager, appearance,
        // and the settings cogwheel for admins — is exactly what the redesign
        // keeps. The cogwheel opens Lunicle's own modal; see
        // onAppSettingsActivate below.
        showSidebar = false,
        // The strip appears only when the forum master toggle put something in
        // it. With the toggle off — and in an embed of the issue tracker alone —
        // this is `false`, which is exactly the chrome Lunicle had before
        // LNL-30. See ShellBackingViewModel for the four cases.
        showTabStrip = shellViewModel.stateFlow.value.showTabStrip,
        // No bottom bar. The brand/identity line used to ride it (LNL-84), but
        // it now sits centered in the topbar's middle slot — between the
        // project navigator + analytics on the leading edge and the "+" cluster
        // on the trailing edge (see topbarCenter below). With nothing else in
        // the footer, the whole strip goes away.
        showBottomBar = false,
        // The identity line, centered in the topbar's otherwise-empty middle
        // slot. The toolkit only renders this when there is no tab strip
        // claiming that slot (forums off — the common case); with forums on,
        // the Board/Forums/Messages tabs take the middle and this is dropped.
        topbarCenter = {
            val brandEl = element("div", "topbar-brand")
            val brandLogoSvg = brandConfig?.logoSvg
            if (brandLogoSvg != null) {
                // With a deployment brand, the centre becomes an attribution
                // line — "Hosted by <brand>" (LNL-110). The Lunicle wordmark
                // itself moves to the leading slot beside the project picker
                // (see topbarLeading), so the product identity leads and the
                // centre says who runs this instance.
                brandEl.children(
                    element("span", "topbar-brand-name topbar-hosted-by", "Hosted by"),
                    brandLogo(brandLogoSvg),
                )
            } else {
                // No brand: the Lunicle wordmark keeps the centre, unchanged.
                brandEl.children(
                    logoIcon(),
                    element("span", "topbar-brand-name", "Lunicle — an open-source issue tracker"),
                )
            }
            // Embedded, the tracker is a panel in somebody else's page: no
            // address bar of its own, and boxed to whatever slot the site gives
            // it. This is the way out to the full site — the same view, in a
            // window that is all tracker. It rides the brand's slot so it reads
            // as "this is the embed", right where the eye already is.
            if (isEmbedded()) brandEl.appendChild(fullSiteLink())
            brandEl
        },
        // The topbar's leading edge is the project navigator now — LNL-84. The
        // picker acts as top-level tabs (a project switch redraws everything
        // below), with the project gear and statistics button beside it. All
        // three used to sit in the board window's toolbar; see ProjectBar.
        topbarLeading = {
            // When a deployment brand takes the centre (as "Hosted by …"), the
            // Lunicle wordmark is pushed out of it and lands here, left-aligned
            // beside the picker, so the product identity still leads the bar
            // (LNL-110). No brand ⇒ the picker owns this slot alone, as before.
            val brandLogoSvg = brandConfig?.logoSvg
            if (brandLogoSvg != null) {
                element("div", "topbar-leading").children(
                    projectBar.root,
                    element("div", "topbar-brand").children(
                        logoIcon(),
                        element("span", "topbar-brand-name", "Lunicle — an open-source issue tracker"),
                    ),
                )
            } else {
                projectBar.root
            }
        },
        // Close is intercepted by the host (unsaved-changes question), so the
        // toolkit's own close-confirm dialog must not also ask.
        confirmPaneClose = false,
        // Each tab's main pane cannot be closed; the windows opened from it can.
        paneClosable = { _, paneId -> paneId !in MAIN_PANE_IDS },
        // Free-floating, not auto-tiled: opening an issue must NOT reflow the
        // board. Each tab's main pane fills the whole area as a plain floating
        // window (see paneInitialGeometry), and a detail window opens on top of
        // it, leaving it where it is.
        //
        // Deliberately NOT maximized: a maximized pane is force-restored to its
        // small restore box the instant a sibling opens, which is exactly the
        // un-maximize we are avoiding. A full-area *float* has no such trigger.
        // Layout is not persisted, so this is the arrangement on every launch.
        defaultLayoutPreset = LayoutPreset.Custom,
        paneOpensMaximized = { _, _ -> false },
        paneInitialGeometry = { _, paneId ->
            if (paneId in MAIN_PANE_IDS) {
                InitialPaneGeometry(widthPct = 1.0, heightPct = 1.0, xPct = 0.0, yPct = 0.0)
            } else {
                // Issue / forum-post / conversation windows: a tall column, cascaded.
                InitialPaneGeometry(widthPct = DETAIL_WINDOW_WIDTH_PCT, heightPct = DETAIL_WINDOW_HEIGHT_PCT)
            }
        },
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
        // the per-project rights. App chrome rather than board chrome: it is
        // about the deployment, not about whatever project the picker happens to
        // name, so it hangs off the toolkit's own app-settings gear rather than
        // sitting in the board toolbar next to the project gear, where it read as
        // a second, wider-scoped project setting.
        //
        // The gear opens Lunicle's existing modal rather than a toolkit sidebar,
        // which is what onAppSettingsActivate is for — the alternative was
        // cloning the toolkit's glyph into an extraTopbarTrailing button, where
        // it would drift from the real icon and land on the wrong side of the
        // divider.
        onAppSettingsActivate = { mainViewModel.onAdminSettingsTapped() },
        // Re-read on every topbar rebuild. An affordance only; AdminRoutes
        // refuses everyone else regardless. See the refresh trigger below, which
        // is what makes a sign-in reach this.
        isAppSettingsAvailable = { mainViewModel.stateFlow.value.canOpenAdminSettings },
        // Every shell re-render rebuilds the pane subtrees, which detaches and
        // re-appends the board pane's content — and a detach silently resets the
        // columns' scrollTop to 0. Opening an issue adds a pane, so it is such a
        // re-render, which is why a single click on a card used to throw the
        // board back to the top (LNL-45). This hook fires after the rebuild, the
        // only point at which writing scrollTop takes: on a detached element it
        // is a no-op. Idempotent, as the hook requires.
        onAfterRefresh = { boardWindow.restoreScroll() },
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

        // The caller's stored theme — or Lunicle's default — fetched *before*
        // the shell mounts, because the shell reads the persister as part of
        // mounting. One small request ahead of first paint, in exchange for
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
        launch {
            while (true) {
                delay(5 * 60 * 1000L)
                if (sessionViewModel.stateFlow.value.user != null) {
                    notificationsViewModel.refreshCount()
                }
            }
        }

        // Separate collectors, not a combine(): a combine would couple every
        // board tick to a sign-in re-render for no benefit.
        launch {
            // The board the pane titles were last painted against. A refresh is
            // a full shell re-render, and re-rendering on emissions that change
            // nothing visible is not merely waste — a re-render between a
            // mousedown and its mouseup swallows the click (see deliver). So
            // this stays narrow: only the two things a refresh actually repaints
            // trigger one.
            var titledBoard: se.soderbjorn.lunicle.clientserver.BoardState? = null
            // The permissions the top bar was last built against.
            //
            // A refresh rebuilds the top bar too, not just the pane titles, and
            // the toolkit OMITS both permission-gated controls outright rather
            // than disabling them — the "+" when paneAddMenuItems comes back
            // empty, the settings gear when isAppSettingsAvailable says no. A
            // control that cannot do anything reads as broken, so there is no
            // disabled state to fall back on. All three answers are gated here
            // (New issue / New project / instance settings), and permissions
            // change on sign-in, which is not a board change.
            //
            // Watching the board alone deadlocked the first run: with no projects
            // there is no board, `state.board` is null before and after sign-in,
            // so no refresh ever fired, so the "+" built for a signed-out visitor
            // never came back — leaving a fresh instance's admin no way to create
            // the first project. It only looked fine once a project existed,
            // because loading its board fired the refresh as a side effect.
            var topbarGates: Triple<Boolean, Boolean, Boolean>? = null
            // The issue whose window was last lifted to the front. Clicking an
            // already-open issue's card re-focuses its window, but in the
            // free-floating layout a focus alone does not re-stack it, so it stays
            // hidden behind the full-area board — see handle.bringPaneToFront. Only
            // a genuine change of focus raises, so a plain board tick does not.
            var raisedIssueFocus: Long? = null
            mainViewModel.stateFlow.collect { state ->
                // Registry first, snapshot second: the toolkit asks for pane
                // content the moment a pane appears in the snapshot, and the
                // registry must already hold it.
                issueWindows.sync(state)
                boardWindow.render(state)
                // The topbar's project navigator is driven by the same state —
                // the project list, the pick, and whether the gear/statistics
                // buttons show. LNL-84; see ProjectBar. Its element is stable, so
                // this needs no shell refresh: it edits its own children in place.
                projectBar.render(state)
                dialogs.render(state)
                boardState = state
                // The Discussion tab follows the board's project rather than
                // resolving one of its own — see ForumBackingViewModel. Cheap to
                // call on every tick: it returns immediately unless the project
                // actually changed.
                forumViewModel.onProjectChanged(state.currentProject?.id)
                latest = snapshotOf(state, shellViewModel.stateFlow.value, forumState, messagesState, unreadState)
                deliver()
                // Lift the focused issue window to the front when focus actually
                // moves to it — clicking its card while it is open but buried under
                // the full-area board. deliver() has just put its pane on the
                // toolkit's side, so it exists to be raised; a newly-opened window
                // is already on top, so raising it again is a harmless no-op.
                val focusedIssue = state.focusedIssueId
                if (focusedIssue != null && focusedIssue != raisedIssueFocus) {
                    handle.bringPaneToFront(issuePaneId(focusedIssue))
                }
                raisedIssueFocus = focusedIssue
                val gates = Triple(
                    state.canCreateIssue,
                    state.canCreateProject,
                    state.canOpenAdminSettings,
                )
                if (state.board !== titledBoard || gates != topbarGates) {
                    titledBoard = state.board
                    topbarGates = gates
                    handle.refresh()
                }
                // The address bar follows the focused issue window, the picked
                // project, the active tab and — since LNL-62 — the forum and post
                // on screen, so the URL is always a link to what is being looked
                // at, and a reload lands back on it.
                pushUrl()
            }
        }
        launch {
            // The tab strip. Its own collector rather than a branch inside the
            // board's, because the two change for entirely unrelated reasons:
            // pressing a tab must repaint the strip whether or not a board tick
            // happens to follow, and a board tick must not wait on one.
            shellViewModel.stateFlow.collect { shell ->
                latest = snapshotOf(boardState, shell, forumState, messagesState, unreadState)
                deliver()
                // A tab carries its own pane, whose title the top bar renders,
                // so the shell has to be rebuilt — `deliver` only re-renders the
                // pane area.
                handle.refresh()
                pushUrl()
            }
        }
        launch {
            // The Messages tab's own collector, for the reason the tab strip has
            // one: opening a conversation must put it in the address bar whether or
            // not a board tick happens to follow, and a board tick must not wait on
            // one. Since LNL-64 it drives the *pane list* as well, because opening a
            // conversation adds a window — which is the Discussion collector's
            // second reason arriving one tab over.
            //
            // Registry before snapshot, exactly as the other two do it: the toolkit
            // asks for a pane's content the moment the pane appears, so `sync` must
            // already have built it.
            messagesViewModel.stateFlow.collect { messages ->
                conversations.sync(messages)
                messagesState = messages
                latest = snapshotOf(boardState, shellViewModel.stateFlow.value, forumState, messages, unreadState)
                deliver()
                pushUrl()
            }
        }
        launch {
            // The Discussion tab's own collector, for the Messages tab's reason and
            // one more: this one drives the *pane list* as well as the URL, because
            // opening a post adds a window and the board may not tick for hours.
            //
            // Registry before snapshot, exactly as the board's collector does it:
            // the toolkit asks for a pane's content the moment the pane appears, so
            // `sync` must already have built it.
            var badgedPosts: List<se.soderbjorn.lunicle.clientserver.ForumPostSummary>? = null
            forumViewModel.stateFlow.collect { forum ->
                windows.sync(forum)
                // A new post list means somebody read something, wrote something or
                // switched forum — the three things that can move an instance-wide
                // dot from this browser. Compared by identity, as `ForumPane` does
                // it: the list is replaced wholesale by every fetch and never edited
                // in place, so this is free where an equality check on tens of data
                // classes per tick would not be.
                if (forum.posts !== badgedPosts) {
                    badgedPosts = forum.posts
                    unreadViewModel.refresh()
                }
                forumState = forum
                latest = snapshotOf(boardState, shellViewModel.stateFlow.value, forum, messagesState, unreadState)
                deliver()
                pushUrl()
            }
        }
        launch {
            // The Discussion badge's own collector. One boolean, and nothing else
            // in the app depends on it, so it drives the snapshot and nothing more —
            // not `pushUrl`, because a badge is not part of what is on screen in the
            // sense the address bar means.
            unreadViewModel.stateFlow.collect { unread ->
                unreadState = unread
                latest = snapshotOf(
                    boardState,
                    shellViewModel.stateFlow.value,
                    forumState,
                    messagesState,
                    unread,
                )
                deliver()
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
                // The board belongs to whoever is asking, so the session drives
                // it. Identity rather than a boolean — impersonation goes
                // signed-in → signed-in and must still reload. See
                // MainScreenBackingViewModel.onSessionChanged.
                // Conversations belong to whoever is asking, so the session drives
                // them too — and unlike the board they must be thrown away rather
                // than refreshed, because an open thread does not survive becoming
                // somebody else. Only once the session is actually known: the first
                // emission is "still asking", and fetching then would be a request
                // whose answer is always "signed out".
                if (state.isLoaded) messagesViewModel.onSessionChanged(state.identity)
                // ...and the Discussion tab, which the board's own project-change
                // path cannot reach: signing in does not change which project the
                // board is on, so `onProjectChanged` no-ops and the forum list —
                // which carries canManageForums/canPost and, since LNL-57, which
                // forums are even visible — would keep the signed-out answer until a
                // reload (LNL-83). It re-fetches under the new identity instead.
                if (state.isLoaded) forumViewModel.onSessionChanged()
                // ...and the Discussion badge, for the reason above and one more:
                // a dot left over from the previous account is exactly the leak
                // LNL-64's acceptance list names. It clears first and asks second;
                // see UnreadBackingViewModel.onSessionChanged.
                if (state.isLoaded) unreadViewModel.onSessionChanged()
                // ...and the notifications bell (LNL-109), for the same
                // leak-across-accounts reason: it clears first and re-fetches the
                // count second. The signed-in body attribute (which shows or hides
                // the bell entirely) is set here too, so the bell appears the moment
                // a session resolves and disappears on sign-out.
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
                mainViewModel.onSessionChanged(
                    identity = state.identity,
                    isKnown = state.isLoaded,
                    // The effective user's, so an admin who is impersonating loses
                    // the button — matching the route, which refuses them too.
                    isSysAdmin = state.isSysAdmin,
                )
            }
        }

        mainViewModel.start(preferredProjectName(), preferredTicket(), preferredProjectId())
        // The Messages deep link, read once, here, alongside the issue one — this
        // app resolves every deep link at load and has no router. `start` only
        // honours the arguments on this first call; later ones come from
        // `onSessionChanged` with nothing to open. See MessagesBackingViewModel.
        messagesViewModel.start(preferredConversation(), preferredMessage())
        // ...and the Discussion tab's, read once here alongside the other two.
        // Unlike the other two this one only *remembers* — the forum view model
        // never picks a project, so there is nothing it can fetch until the board
        // says which project this is. The pending ids are spent by the first forum
        // list that comes back. See ForumBackingViewModel.start.
        forumViewModel.start(preferredForum(), preferredPost(), preferredComment())
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
        val root: HTMLElement,
    )

    private val entries = LinkedHashMap<Long, Entry>()

    /** Create and dispose windows to match the state. */
    fun sync(state: MainScreenBackingViewModel.State) {
        val wanted = state.openIssueIds.toSet()
        entries.keys.filter { it !in wanted }.toList().forEach { dispose(it) }
        state.openIssues.forEach { open ->
            if (open.issueId !in entries) create(open, state)
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

    private fun create(open: OpenIssueWindow, state: MainScreenBackingViewModel.State) {
        val id = open.issueId
        // No board, no vocabularies, no window. Cannot happen through the UI —
        // every way an issue opens starts from a rendered board.
        val board = state.board ?: return
        // ...and it must be THIS issue's board. Windows outlive the project they
        // were opened from (LNL-48), so the board on screen is not necessarily
        // the one this issue belongs to; building the window from it would hand
        // the editor another project's statuses, priorities and permissions —
        // exactly the lie closing the windows used to prevent. It cannot happen
        // either, for the same reason the check above cannot: a window is only
        // ever added to the state while its own board is the one loaded. It is
        // here because "cannot happen" and "quietly corrupts an issue if it
        // does" is a combination worth one line of code.
        if (open.projectId != board.project.id) return
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
                if (changed) mainViewModel.refreshBoard()
            },
            // Any write the board should reflect — save, delete, comment —
            // refreshes it immediately, window still open. This is the
            // redesign's "saving an issue updates the board" requirement.
            onWritten = { mainViewModel.refreshBoard() },
            editorRegistry = editorRegistry,
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
            // Links for every accessible project's references, and the description
            // editor's "PREFIX-" autocomplete (LNL-139). See the bootstrap's ticketSource.
            ticketSource = ticketSource,
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
 * Owns the Discussion tab's window: the post being read, or the one being written.
 *
 * [IssueWindows] is the template, and the copy is close — a view model, a view and
 * a cancellable scope per open thing, synced against the state rather than opened
 * at the click, so that something closing because it was *deleted* takes the same
 * path as something closed by hand.
 *
 * ── What is deliberately different: there is only ever one ──────────────────
 *
 * `IssueWindows` keeps a map keyed by issue id and adds a pane per entry. This
 * keeps a single nullable entry and contributes a single pane, [FORUM_POST_PANE_ID],
 * because LNL-30 settled that clicking a post **reuses** the window that is open
 * rather than stacking another one. See that constant, which is where the reuse
 * actually lives.
 *
 * ── The stable host, which is what makes reuse cost nothing ────────────────
 *
 * [host] is built once and handed to the toolkit as the pane's content for the
 * app's whole life. Reading a different post swaps this class's own children
 * inside it; the toolkit is never told, because from its side nothing changed.
 *
 * The alternative — returning the current view's root from `paneContent` — would
 * have needed a shell re-render to take effect, and a re-render is exactly what
 * `deliver` goes to such lengths to avoid: one arriving between a mousedown and
 * its mouseup detaches the row being clicked and swallows the click. Here, reading
 * post after post causes no re-render at all.
 *
 * ── Two contents, one pane, and that is deliberate ──────────────────────────
 *
 * This shows either a post ([ForumPostView]) or the new-post form
 * ([NewForumPostView]), because the composers came out of their modal at the owner's
 * instruction after LNL-30's run: the WYSIWYG editor belongs in a pane, which is
 * where the issue editor has always lived. [ConversationWindows] had already been
 * given this shape by LNL-64 a commit earlier, and the two should be read together.
 *
 * They share the pane rather than having one each, and the payoff is visible: a new
 * post *becomes* the post it created, in the same window, with nothing opening or
 * closing and no shell re-render — see `ForumBackingViewModel.onPostCreated`. A
 * second pane id would have made that a window closing and another opening, in front
 * of somebody who had just pressed Post.
 *
 * That is also why [Content] exists rather than a nullable post id: "which of the
 * two, and which post if it is the first" is one value, and `sync` compares it
 * whole. Two nullable fields would be four states, two of which are nonsense.
 *
 * @param onTitleChanged the window's title moved, so the top bar needs rebuilding.
 *   A callback rather than a refresh from in here, because `handle.refresh()` is
 *   the shell's and this class has never seen it.
 */
private class ForumWindows(
    private val storage: StorageRepository,
    private val forumViewModel: ForumBackingViewModel,
    private val dialogHost: HTMLElement,
    /** The app-wide open-editor register the composer joins — LNL-84, see [EditorDirtyRegistry]. */
    private val editorRegistry: EditorDirtyRegistry,
    /** Reports the raw press before the view-model intent — see [main]'s `lastUserFocusedPane`. */
    private val onPaneMousedown: (paneId: String) -> Unit,
    private val onTitleChanged: () -> Unit,
    /** Ticket references for the post and comment editors — the `PREFIX-` autocomplete (LNL-139). */
    private val ticketSource: TicketSource,
) {
    /** The pane's content, for the app's whole life. See the class doc. */
    val host: HTMLElement = element("div", "forum-post-host")

    /** What the window is showing. See the class doc for why this is one value. */
    private sealed interface Content {
        data class Post(val postId: Long) : Content
        data object NewPost : Content
    }

    /**
     * The window as it stands: what it is showing, the views built for it, and the
     * scope they die with.
     *
     * `view` and `composer` are the two shapes, exactly one of which is non-null —
     * `ConversationWindows.Entry` is the twin, down to the reason it is one object:
     * "open" and "closed" are then one state rather than a combination.
     */
    private class Entry(
        val content: Content,
        val scope: CoroutineScope,
        val root: HTMLElement,
        val view: ForumPostView?,
        /**
         * The post's own view model, kept alongside its view so that closing the
         * pane can go through it — it is what knows whether anything was written
         * while the post was open. `ConversationWindows` needs no equivalent: a
         * thread refreshes its list on every send rather than on close.
         */
        val postViewModel: ForumPostBackingViewModel?,
        val composer: ForumComposerBackingViewModel?,
    )

    private var entry: Entry? = null

    /** The window's pane label: the post's title once it has arrived. */
    private var title: String = DEFAULT_TITLE

    init {
        // A mousedown anywhere in the window is a focus report, exactly as it is in
        // an issue window and on the board. Capture phase, so buttons inside still
        // work. Without this the snapshot would go on naming this pane active after
        // the reader had clicked back to the forum list, and the next push would
        // yank them forward again.
        host.addEventListener(
            "mousedown",
            {
                onPaneMousedown(FORUM_POST_PANE_ID)
                forumViewModel.onPostWindowFocused(true)
            },
            true,
        )
    }

    /** Create, keep or dispose the window to match the state. */
    fun sync(state: ForumBackingViewModel.State) {
        val postId = state.openPostId
        val wanted: Content? = when {
            postId != null -> Content.Post(postId)
            state.isComposingPost -> Content.NewPost
            else -> null
        }
        val current = entry
        // The identity check is the reuse rule doing its work: re-rendering while
        // the same thing is open must leave it alone, or every tick would throw away
        // the reader's scroll position — or the half-written post — and restart the
        // fetch.
        if (current?.content == wanted) return
        if (current != null) dispose()
        // Both are known by the time either window can be open — the pane only offers
        // rows and a "New post" button from a loaded list, and a deep link is applied
        // against one. Returning rather than asserting because the state permits the
        // combination and a window with no project is nothing to draw.
        val projectId = state.projectId ?: return
        val forumId = state.selectedForumId ?: return
        when (wanted) {
            null -> return
            is Content.Post -> openPost(projectId, forumId, wanted.postId)
            Content.NewPost -> openComposer(projectId, forumId, state.mentionableNames)
        }
    }

    private fun openPost(projectId: Long, forumId: Long, postId: Long) {
        val windowScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val viewModel = ForumPostBackingViewModel(
            projectId = projectId,
            forumId = forumId,
            postId = postId,
            storage = storage,
            scope = windowScope,
            // Dropping the post from the state is what removes the pane, which is
            // what actually closes the window — the same contract an issue window
            // has with the board.
            onFinished = { changed -> forumViewModel.onPostClosed(changed) },
        )
        val view = ForumPostView(
            viewModel = viewModel,
            scope = windowScope,
            // Null: a window is left by closing it, and a Back link inside one
            // would be a second, differently shaped way to do the same thing.
            onBack = null,
            storage = storage,
            // A comment lands here as well as in the thread: the list row's comment
            // count and its last-replier column both moved, and that list is on
            // screen beside this window rather than behind it.
            onWritten = { forumViewModel.onOpenPostChanged() },
            // The inline comment composer under the thread joins the open-editor
            // register, so a project switch asks before discarding a half-written
            // reply — LNL-84.
            editorRegistry = editorRegistry,
            // `?comment=`, spent by being read. See ForumBackingViewModel.
            takeScrollTarget = { forumViewModel.takePendingCommentId() },
            ticketSource = ticketSource,
        )
        show(
            windowScope,
            view.root,
            Content.Post(postId),
            view = view,
            postViewModel = viewModel,
            composer = null,
        )
        view.start(dialogHost)

        // The title arrives a round-trip after the window does, so it is followed
        // rather than read once. Its own collector rather than a branch in the
        // view's render, because the pane label belongs to the shell and the view
        // knows nothing about panes.
        windowScope.launch {
            viewModel.stateFlow.collect { state ->
                setTitle(state.detail?.title?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE)
            }
        }
    }

    private fun openComposer(projectId: Long, forumId: Long, mentionableNames: List<String>) {
        val windowScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val composer = ForumComposerBackingViewModel(
            projectId = projectId,
            forumId = forumId,
            target = ForumComposerBackingViewModel.Target.NewPost,
            storage = storage,
            scope = windowScope,
            // Everybody who can see this project, handed over from the forum's post
            // list rather than fetched here, so the autocomplete works the moment the
            // pane appears. See ForumPostListState.mentionableUsers, which carries a
            // set of names that has nothing to do with any post in the list.
            mentionableNames = mentionableNames,
            onFinished = { _, detail ->
                // Published: the same window becomes the post it just created.
                // Nothing else — `onPostCreated` clears the composing flag, and the
                // next `sync` swaps the children.
                if (detail != null) {
                    forumViewModel.onPostCreated(detail.id)
                } else {
                    forumViewModel.onComposeCancelled()
                }
            },
            // The new-post composer joins the open-editor register, so a project
            // switch asks before discarding an unpublished post — LNL-84.
            editorRegistry = editorRegistry,
        )
        val view = NewForumPostView(composer, windowScope, ticketSource)
        show(
            windowScope,
            view.root,
            Content.NewPost,
            view = null,
            postViewModel = null,
            composer = composer,
        )
        view.start()
        setTitle(NEW_TITLE)
    }

    private fun show(
        scope: CoroutineScope,
        root: HTMLElement,
        content: Content,
        view: ForumPostView?,
        postViewModel: ForumPostBackingViewModel?,
        composer: ForumComposerBackingViewModel?,
    ) {
        host.clear()
        host.appendChild(root)
        entry = Entry(content, scope, root, view, postViewModel, composer)
        if (content is Content.Post) setTitle(DEFAULT_TITLE)
    }

    /** This pane's label, for the shell's top bar. */
    fun paneLabel(): String = title

    /**
     * The pane chrome's × was clicked.
     *
     * Routed through the view models rather than closed here, so that "closed by
     * hand" and "closed because the thing went away" are one path. A new-post window
     * closing is a **cancel**: the composer undoes whatever it created, which is
     * nothing at all unless a file was attached — see
     * `ForumComposerBackingViewModel.onCancelTapped`.
     */
    fun onCloseClicked(paneId: String) {
        if (paneId != FORUM_POST_PANE_ID) return
        val current = entry ?: return
        // Only the state is touched here; the undoing is `dispose`'s, which runs a
        // moment later when the state comes back round. Cancelling from both would
        // fire the discard request twice.
        // Through the post's own view model, which is what knows whether anything
        // was written while it was open — the forum list's comment counts depend on
        // the answer.
        current.postViewModel?.onCloseTapped()
        if (current.composer != null) forumViewModel.onComposeCancelled()
    }

    private fun setTitle(next: String) {
        if (title == next) return
        title = next
        onTitleChanged()
    }

    private fun dispose() {
        val current = entry ?: return
        entry = null
        // Before the scope dies: the confirmations mount into the shared dialog
        // host, which outlives this view's scope, so cancelling alone would leave
        // one on screen over a window that is gone. This also stops the post's
        // inline comment composer, whose scope the view owns rather than this class.
        current.view?.dispose()
        // A new-post window going away without having been published is a cancel,
        // and it has to be one: an attachment may already have created the draft
        // post, and a draft nobody can see is exactly what `onCancelTapped` removes.
        // Fired before the scope dies, because the request is launched in it.
        current.composer?.onCancelTapped()
        current.scope.cancel()
        current.root.remove()
        setTitle(DEFAULT_TITLE)
    }

    private companion object {
        /** What the pane is called before its post has arrived, and after it has gone. */
        const val DEFAULT_TITLE = "Post"

        /** ...and while a new one is being written, which has no title yet to use. */
        const val NEW_TITLE = "New post"
    }
}

/**
 * Owns the Messages tab's window: the conversation being read, or the one being
 * started.
 *
 * [ForumWindows] transposed to the Messages tab, and the copy is close enough that
 * the two should be read together — one entry rather than a map, one pane
 * ([CONVERSATION_PANE_ID]) rather than one per thing, a [host] that is built once
 * and never replaced, and a title followed rather than read once because it arrives
 * a round-trip after the window does.
 *
 * The transposition is LNL-64's, and the argument for it is on
 * [CONVERSATION_PANE_ID]: what the Messages tab had was the same two-column layout
 * lunula already provides, drawn by hand inside a single pane.
 *
 * ── Two contents, one pane, and that is deliberate ──────────────────────────
 *
 * `ForumWindows` shows one thing: a post. This shows either a thread
 * ([ConversationView]) or the new-message form ([NewConversationView]), because
 * LNL-64 also took the composer out of a modal — the WYSIWYG editor belongs in a
 * pane, which is where the issue editor has always lived.
 *
 * They share the pane rather than having one each, and the payoff is visible: a new
 * conversation *becomes* the thread it created, in the same window, with nothing
 * opening or closing and no shell re-render — see
 * `MessagesBackingViewModel.onConversationStarted`. A second pane id would have made
 * that a window closing and another opening, in front of somebody who pressed Send.
 *
 * That is also why [Content] exists rather than a nullable conversation id: "which
 * of the two, and which conversation if it is the first" is one value, and `sync`
 * compares it whole. Two nullable fields would be four states, two of which are
 * nonsense.
 *
 * What is genuinely different from [ForumWindows] beyond that is only this: nothing
 * here needs a project or a forum, because a conversation is instance-wide and is
 * fetchable from its id alone — which is why `sync` has no early return for a
 * missing parent.
 *
 * @param onTitleChanged the window's title moved, so the top bar needs rebuilding.
 */
private class ConversationWindows(
    private val storage: StorageRepository,
    private val messagesViewModel: MessagesBackingViewModel,
    private val dialogHost: HTMLElement,
    /** Reports the raw press before the view-model intent — see [main]'s `lastUserFocusedPane`. */
    private val onPaneMousedown: (paneId: String) -> Unit,
    private val onTitleChanged: () -> Unit,
) {
    /** The pane's content, for the app's whole life. See the class doc. */
    val host: HTMLElement = element("div", "conversation-host")

    /** What the window is showing. See the class doc for why this is one value. */
    private sealed interface Content {
        data class Thread(val conversationId: Long) : Content
        data object NewConversation : Content
    }

    /**
     * The window as it stands: what it is showing, the views built for it, and the
     * scope they die with.
     *
     * `view` and `composer` are the two shapes, exactly one of which is non-null.
     * Kept as one object rather than as fields on the class, for `MessagesPane`'s
     * reason: "open" and "closed" are then one state rather than a combination.
     */
    private class Entry(
        val content: Content,
        val scope: CoroutineScope,
        val root: HTMLElement,
        val view: ConversationView?,
        val composer: MessageComposerBackingViewModel?,
    )

    private var entry: Entry? = null

    /** The window's pane label: who the conversation is with, once that is known. */
    private var title: String = DEFAULT_TITLE

    init {
        // A mousedown anywhere in the window is a focus report, exactly as it is in
        // a forum post window. Capture phase, so buttons inside still work. Without
        // it the snapshot would go on naming this pane active after the reader had
        // clicked back to the list, and the next push would yank them forward again.
        host.addEventListener(
            "mousedown",
            {
                onPaneMousedown(CONVERSATION_PANE_ID)
                messagesViewModel.onConversationWindowFocused(true)
            },
            true,
        )
    }

    /** Create, keep or dispose the window to match the state. */
    fun sync(state: MessagesBackingViewModel.State) {
        val openId = state.openConversationId
        val wanted: Content? = when {
            openId != null -> Content.Thread(openId)
            state.isComposingNew -> Content.NewConversation
            else -> null
        }
        val current = entry
        // The identity check is the reuse rule doing its work: re-rendering while
        // the same thing is open must leave it alone, or every tick would throw away
        // the reader's scroll position — or the half-written message — and restart
        // the fetch.
        if (current?.content == wanted) return
        if (current != null) dispose()
        when (wanted) {
            null -> return
            is Content.Thread -> openThread(wanted.conversationId)
            Content.NewConversation -> openNewConversation()
        }
    }

    private fun openThread(conversationId: Long) {
        val windowScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val viewModel = ConversationBackingViewModel(
            conversationId = conversationId,
            storage = storage,
            scope = windowScope,
            // Dropping the conversation from the state is what removes the pane,
            // which is what actually closes the window — the same contract the forum
            // post window has with its list.
            onFinished = { changed -> messagesViewModel.onConversationClosed(changed) },
        )
        val view = ConversationView(
            viewModel = viewModel,
            scope = windowScope,
            conversationId = conversationId,
            storage = storage,
            // `?message=`, spent by being read. See MessagesBackingViewModel.
            takeScrollTarget = { messagesViewModel.takePendingMessageId() },
            // A reply lands here as well as in the thread: the list row's preview,
            // its timestamp and its order all moved.
            onWritten = { messagesViewModel.onConversationChanged() },
        )
        show(windowScope, view.root, Content.Thread(conversationId), view = view, composer = null)
        view.start(dialogHost)

        // The title arrives a round-trip after the window does, so it is followed
        // rather than read once. Its own collector rather than a branch in the
        // view's render, because the pane label belongs to the shell and the view
        // knows nothing about panes.
        windowScope.launch {
            viewModel.stateFlow.collect { current ->
                setTitle(current.heading.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE)
            }
        }
    }

    private fun openNewConversation() {
        val windowScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val composer = MessageComposerBackingViewModel(
            target = MessageComposerBackingViewModel.Target.NewConversation,
            storage = storage,
            scope = windowScope,
            // Everybody this caller may write to, handed over from the list rather
            // than fetched here, so the autocomplete works the moment the pane
            // appears. See MessagesBackingViewModel.State.recipients.
            recipients = messagesViewModel.stateFlow.value.recipients,
            onFinished = { conversationId, _ ->
                // Sent: the same window becomes the thread it just created. Nothing
                // else — `onConversationStarted` clears the composing flag, and the
                // next `sync` swaps the children.
                if (conversationId != null) {
                    messagesViewModel.onConversationStarted(conversationId)
                } else {
                    messagesViewModel.onComposeCancelled()
                }
            },
        )
        val view = NewConversationView(composer, windowScope)
        show(windowScope, view.root, Content.NewConversation, view = null, composer = composer)
        view.start()
        setTitle(NEW_TITLE)
    }

    private fun show(
        scope: CoroutineScope,
        root: HTMLElement,
        content: Content,
        view: ConversationView?,
        composer: MessageComposerBackingViewModel?,
    ) {
        host.clear()
        host.appendChild(root)
        entry = Entry(content, scope, root, view, composer)
        if (content is Content.Thread) setTitle(DEFAULT_TITLE)
    }

    /** This pane's label, for the shell's top bar. */
    fun paneLabel(): String = title

    /**
     * The pane chrome's × was clicked.
     *
     * Routed through the view models rather than closed here, so that "closed by
     * hand" and "closed because the thing went away" are one path. A new-message
     * window closing is a **cancel**: the composer undoes whatever it created, which
     * is nothing at all unless a file was attached — see
     * `MessageComposerBackingViewModel.onCancelTapped`, and note that leaving the
     * row would leave a conversation nobody can see, nobody can remove, and whose
     * membership is already frozen.
     */
    fun onCloseClicked(paneId: String) {
        if (paneId != CONVERSATION_PANE_ID) return
        val current = entry ?: return
        // Only the state is touched here; the undoing is `dispose`'s, which runs a
        // moment later when the state comes back round. Cancelling from both would
        // fire the discard request twice.
        if (current.view != null) messagesViewModel.onConversationClosed(false)
        if (current.composer != null) messagesViewModel.onComposeCancelled()
    }

    private fun setTitle(next: String) {
        if (title == next) return
        title = next
        onTitleChanged()
    }

    private fun dispose() {
        val current = entry ?: return
        entry = null
        // Before the scope dies: the delete confirmation mounts into the shared
        // dialog host, which outlives this view's scope, so cancelling alone would
        // leave one on screen over a window that is gone.
        current.view?.dispose()
        // A new-message window going away without having been sent is a cancel, and
        // it has to be one: an attachment may already have created the conversation,
        // and an unsent conversation nobody can see is exactly what
        // `discardUnsentConversation` exists to remove. Fired before the scope dies,
        // because the request is launched in it.
        current.composer?.onCancelTapped()
        current.scope.cancel()
        current.root.remove()
        setTitle(DEFAULT_TITLE)
    }

    private companion object {
        /** What the pane is called before its conversation has arrived, and after it has gone. */
        const val DEFAULT_TITLE = "Conversation"

        /** ...and while a new one is being written, which has no participants to name yet. */
        const val NEW_TITLE = "New message"
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

    private var forumManagerDismiss: (() -> Unit)? = null
    private var forumManagerScope: CoroutineScope? = null

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
            ActiveDialog.ConfirmProjectSwitch -> openConfirmProjectSwitch()
            ActiveDialog.NewProject -> openProject(existing = null, projects = state.projects)
            is ActiveDialog.EditProject ->
                openProject(
                    existing = dialog.project,
                    projects = state.projects,
                    canConfigure = dialog.canConfigure,
                    canConfigureIdentity = dialog.canConfigureIdentity,
                )
            is ActiveDialog.ChooseResolution -> openResolution(dialog)
            ActiveDialog.AdminSettings -> openAdminSettings()
            is ActiveDialog.Statistics -> openStatistics(dialog)
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
     * The forum manager, layered over the Discussion pane.
     *
     * Its own scope and lifetime like the comment modal, and for the same
     * reason: it opens and closes while the pane behind it stays up, so it
     * cannot share the pane's scope without being cancelled at the wrong time —
     * or leaking if it were never cancelled at all.
     *
     * It is handed the pane's OWN view model rather than one of its own, which
     * is what makes a forum created in here appear in the picker behind it with
     * nothing telling it to. Two view models over one list would be two answers
     * to "what forums are there".
     */
    fun openForumManager(forumViewModel: ForumBackingViewModel) {
        closeForumManager()
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val view = ForumManagerDialog(
            viewModel = forumViewModel,
            scope = localScope,
            onDismiss = { closeForumManager() },
        )
        view.mount(host)
        forumManagerScope = localScope
        forumManagerDismiss = { view.dismiss() }
    }

    private fun closeForumManager() {
        forumManagerDismiss?.invoke()
        forumManagerScope?.cancel()
        forumManagerDismiss = null
        forumManagerScope = null
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

    /**
     * "You have unsaved changes" — the guard on a project switch (LNL-84).
     *
     * A plain [ConfirmDialog] with no scope or view model: the decision is the
     * whole of its behaviour, and the main view model already holds the project it
     * would switch to. The confirming button says what it does — the changes are
     * discarded — and Cancel is the "keep editing" that aborts the switch. Escape
     * routes to the same abort, so a dialog dismissed by keyboard leaves the editors
     * exactly as they were.
     */
    private fun openConfirmProjectSwitch() {
        val view = ConfirmDialog(
            title = "Unsaved changes",
            message = "Switching project will close what's open and discard any unsaved changes. Switch anyway?",
            destructiveLabel = "Discard & switch",
            onConfirm = { mainViewModel.confirmProjectSwitch() },
            onCancel = { mainViewModel.cancelProjectSwitch() },
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
     * The statistics dialog. Its own scope and view model, like the others — it
     * fetches, so its `collect` must die with it.
     *
     * `changed = false` on close: this dialog writes nothing a board renders. It
     * does cause a write server-side — a recompiled snapshot — but that is a cache
     * behind this dialog and no card depends on it, so reloading the board would
     * be a round-trip repainting identical pixels.
     */
    private fun openStatistics(dialog: ActiveDialog.Statistics) {
        val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val viewModel = StatisticsBackingViewModel(storage = storage, scope = dialogScope)
        val view = StatisticsDialog(
            viewModel = viewModel,
            projectId = dialog.project.id,
            scope = dialogScope,
            onDismiss = { mainViewModel.onDialogClosed(changed = false) },
        )
        view.mount(host)
        scope = dialogScope
        dismiss = { view.dismiss() }
    }

    private fun openProject(
        existing: ProjectSummary?,
        projects: List<ProjectSummary>,
        canConfigure: Boolean = true,
        canConfigureIdentity: Boolean = canConfigure,
    ) {
        val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val viewModel = EditProjectBackingViewModel(
            existing = existing,
            otherProjects = projects,
            canConfigure = canConfigure,
            canConfigureIdentity = canConfigureIdentity,
            storage = storage,
            scope = dialogScope,
            // `saved?.id` only when creating: a new project is not in the
            // picker's list yet, and this is what puts the board on it.
            onFinished = { changed, saved ->
                mainViewModel.onDialogClosed(changed, selectProjectId = saved?.id)
            },
            // The per-user hide-issue-numbers choice (LNL-105) is owned by the board
            // view model: seed the switch from it, and write a change straight back
            // through it so the board and any open issue window update at once. Only
            // an existing project has a board preference to carry.
            hideIssueNumbers = existing?.let { mainViewModel.isHidingIssueNumbers(it.id) } ?: false,
            persistHideIssueNumbers = { value ->
                existing?.let { mainViewModel.setIssueNumbersHidden(it.id, value) }
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
        closeForumManager()
        dismiss?.invoke()
        scope?.cancel()
        dismiss = null
        scope = null
        resolutionView = null
    }
}

/**
 * The "Open the full site" link shown in the top bar's leading slot when the
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
    link.textContent = "Open the full site ↗"
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
