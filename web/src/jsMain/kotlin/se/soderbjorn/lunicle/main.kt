/**
 * Entry point for the Lunicle Kotlin/JS web frontend.
 *
 * Bootstraps the app: constructs the view models, mounts their views into
 * `#app`, and pumps each view model's single state flow into its view. Every
 * decision about what to show lives in the shared view models; this file only
 * wires things together.
 *
 * It owns two things the view models cannot, both for the same reason — they are
 * the coupling *between* view models, and neither side should have to know the
 * other exists:
 *
 *  1. **The session drives the board.** Who you are decides which projects come
 *     back, so the session's outcome is forwarded to MainScreen. See the
 *     collector in [start].
 *  2. **Dialogs.** MainScreen says *which* dialog is open; this file owns the
 *     dialog objects, their scopes, and their lifetimes. A dialog that built its
 *     own children would leave them behind when it closed.
 *
 * @see MainView
 * @see MainScreenBackingViewModel
 * @see SessionBackingViewModel
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
 * Optional by design — "it defaults to a project specified somehow in the embed
 * IF that is provided". Read from this frame's own URL, which is the one the
 * embedding page chose when it wrote the iframe's `src`.
 *
 * A name that does not resolve — because it does not exist, or because it is
 * private and this visitor is signed out, which the server deliberately answers
 * identically — falls back to no project rather than an error. See
 * `StorageRepository.resolve`.
 */
private fun preferredProjectName(): String? =
    URLSearchParams(window.location.search).get("project")?.takeIf { it.isNotBlank() }

/**
 * The deep link's issue, if the page was opened with one: `?issue=LMX-12`.
 *
 * ── Why this reads THIS frame's URL, in both modes ──────────────────────────
 *
 * Standalone, it is the address bar and there is nothing to explain.
 *
 * Embedded, it is the iframe's `src` — which lunamux.dev composes, and which is
 * the only URL this code can see. The framing page is a different origin, so
 * `window.parent.location` is unreadable and would throw; there is no way to read
 * "the URL the human is looking at" from in here, and no amount of trying will
 * produce one. The embed therefore deep-links by putting `?issue=` on the iframe
 * src, exactly as it already does for `?project=` — the mechanism is not new, only
 * the parameter is. See `SITE.issues` in lunamux-web's content.js.
 *
 * The hash is deliberately not used, for the same reason `?project=` does not:
 * embedded, the hash belongs to the *site's* router (`#/issues`), and a tracker
 * writing to it would fight the page framing it.
 */
private fun preferredTicket(): Ticket? =
    parseTicket(URLSearchParams(window.location.search).get("issue"))

/**
 * Put the open issue in the address bar, or take it out again.
 *
 * `replaceState`, never `pushState`. Back would otherwise walk the user through
 * every issue they had opened, and — embedded — "back" is the *site's* history,
 * so each modal would become an entry in a history the tracker does not own. One
 * entry, rewritten, belongs to nobody's history and surprises no one.
 *
 * Embedded, this rewrites the iframe's own URL, which the user never sees. That
 * is not pointless: it costs nothing, it keeps one code path rather than two, and
 * a reload of the frame reopens what was open. The shareable link in that mode is
 * the *site's*, which only the site can compose.
 *
 * Every other parameter is preserved rather than rebuilt — `?project=` is the
 * embed's and dropping it on the first issue anyone opened would silently change
 * which board a reload lands on.
 */
private fun syncIssueUrl(ticket: String?) {
    val params = URLSearchParams(window.location.search)
    if (params.get("issue") == ticket) return
    if (ticket == null) params.delete("issue") else params.set("issue", ticket)
    val query = params.toString()
    val url = window.location.pathname + (if (query.isEmpty()) "" else "?$query") + window.location.hash
    window.history.replaceState(null, "", url)
}

private fun start() {
    val host = document.getElementById("app") as? HTMLElement
    if (host == null) {
        println("Lunicle: #app missing; nothing to mount into")
        return
    }

    // One repository, shared. The view models each get the same instance rather
    // than building their own, so there is exactly one HTTP client and one place
    // that talks to the server.
    val storage = StorageRepository()

    val mainViewModel = MainScreenBackingViewModel(storage)
    val mainView = MainView(mainViewModel)
    mainView.mount(host)

    val sessionViewModel = SessionBackingViewModel(storage)
    // The dialog host is handed over because the provider picker is a modal like
    // any other, and they all stack in the one host — which is what makes Modal's
    // "topmost wins Escape" true rather than approximately true.
    val signInView = SignInView(sessionViewModel, mainView.dialogHost, storage)
    signInView.mount(mainView.accountHost)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val dialogs = Dialogs(mainView.dialogHost, storage, mainViewModel)

    // One document-level listener for every rendered image there will ever be —
    // including the ones that do not exist yet, which is all of them. See
    // Lightbox.
    Lightbox(mainView.dialogHost).install()

    // Separate collectors, not a combine(): a combine would couple every board
    // tick to a sign-in re-render for no benefit.
    scope.launch {
        mainViewModel.stateFlow.collect { state ->
            mainView.render(state)
            dialogs.render(state)
            // The address bar follows the open issue, so the URL is always a link
            // to what is on screen. The view model decides the ticket; this file
            // knows what a URL is. See syncIssueUrl.
            syncIssueUrl(state.openIssueTicket)
        }
    }
    scope.launch {
        sessionViewModel.stateFlow.collect { state ->
            signInView.onState(state)
            // The board belongs to whoever is asking, so the session decides
            // what there is to fetch. This is the only coupling between the two
            // view models, and it lives here rather than in either of them.
            //
            // An identity and a flag cross, not the session state itself — the
            // board has no business knowing WHO is signed in beyond being able
            // to tell that it changed. onSessionChanged ignores repeats, so
            // collecting every emission costs nothing.
            //
            // `identity` rather than `isSignedIn`: an admin impersonating goes
            // from signed-in to signed-in, so a boolean would report no change
            // and the board would keep showing the previous user's projects
            // under the new name. See MainScreenBackingViewModel.onSessionChanged.
            mainViewModel.onSessionChanged(
                identity = state.identity,
                isKnown = state.isLoaded,
            )
        }
    }

    mainViewModel.start(preferredProjectName(), preferredTicket())
    // Only the session starts a request. MainScreen has nothing to ask for until
    // the session says who is asking; it is driven entirely by the collector
    // above.
    sessionViewModel.start()
}

/**
 * Owns the dialog objects and their lifetimes.
 *
 * The state flow says *which* dialog should be open; this opens and closes real
 * ones to match. The subtlety worth naming is the scope: each dialog gets its
 * own, cancelled when it closes. Without that, every dialog's `collect` would
 * outlive it — leaking a coroutine per open, each one still rendering into a
 * detached DOM tree.
 */
private class Dialogs(
    private val host: HTMLElement,
    private val storage: StorageRepository,
    private val mainViewModel: MainScreenBackingViewModel,
) {
    /** The dialog currently up, so [render] can tell "already open" from "open it". */
    private var current: ActiveDialog = ActiveDialog.None
    private var dismiss: (() -> Unit)? = null
    private var scope: CoroutineScope? = null

    /** The open issue's view model, so a comment dialog can report back to it. */
    private var issueViewModel: IssueBackingViewModel? = null
    private var commentDismiss: (() -> Unit)? = null
    private var commentScope: CoroutineScope? = null

    /** The failure alert while it is up, and the message it is showing. */
    private var alert: AlertDialog? = null
    private var alertMessage: String? = null

    fun render(state: MainScreenBackingViewModel.State) {
        // Before the early return below: an error is not an ActiveDialog, so it
        // arrives on emissions where `state.dialog` has not changed — which is
        // most of them, and all of the ones a failure rides in on.
        renderAlert(state)
        if (state.dialog == current) return
        close()
        current = state.dialog
        when (val dialog = state.dialog) {
            ActiveDialog.None -> Unit
            ActiveDialog.NewProject -> openProject(existing = null, projects = state.projects)
            is ActiveDialog.EditProject -> openProject(existing = dialog.project, projects = state.projects)
            is ActiveDialog.Issue -> openIssue(dialog.issueId, state)
            is ActiveDialog.ChooseResolution -> openResolution(dialog)
        }
    }

    /**
     * The resolution picker, held open over a drag that has not been sent yet.
     *
     * No scope and no view model: it renders what the ActiveDialog already
     * carries and reports one of two intents back. There is nothing to collect,
     * so there is nothing to cancel — which is why it is the one dialog here that
     * does not get a scope of its own.
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
     * Put a failure up as a modal, or take it down.
     *
     * Keyed on the *message*, not on "is there one". Two different failures in a
     * row — a move that fails, then a project that will not open — would
     * otherwise leave the first message on screen describing the second, because
     * "an error is showing" was true throughout and nothing rebuilt the dialog.
     *
     * It stacks over whatever is already open rather than replacing it, which is
     * why it goes in the same host as everything else: a save that fails from
     * inside the issue dialog must not close the dialog holding the text that
     * failed to save. Modal's topmost-wins Escape does the rest.
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
            // The dialog answers "is this name taken?" locally as the user
            // types, which needs the other names. An affordance — the server
            // checks again and its 409 wins. See EditProjectBackingViewModel.
            otherProjects = projects,
            storage = storage,
            scope = dialogScope,
            // `saved?.id` only when creating: switching to a project that was
            // merely renamed is a no-op — the cogwheel can only edit the project
            // already on screen — but a *new* one is not in the picker's list
            // yet, and this is what puts the board on it.
            onFinished = { changed, saved ->
                mainViewModel.onDialogClosed(changed, selectProjectId = saved?.id)
            },
        )
        val view = ProjectDialog(viewModel, dialogScope)
        view.mount(host)
        scope = dialogScope
        dismiss = { view.dismiss() }
    }

    private fun openIssue(issueId: Long, state: MainScreenBackingViewModel.State) {
        val board = state.board ?: return
        val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val viewModel = IssueBackingViewModel(
            issueId = issueId,
            board = board,
            storage = storage,
            scope = dialogScope,
            onFinished = { changed -> mainViewModel.onDialogClosed(changed) },
        )
        issueViewModel = viewModel
        val view = IssueDialog(
            viewModel = viewModel,
            scope = dialogScope,
            openComment = { editing -> openComment(issueId, editing) },
        )
        view.mount(host)
        scope = dialogScope
        dismiss = { view.dismiss() }
    }

    /**
     * The comment modal, layered over the issue modal.
     *
     * Its own scope and lifetime, tracked separately: it opens and closes while
     * the issue dialog stays up, so it cannot share the issue's scope without
     * being cancelled at the wrong time — or leaking if it were never cancelled
     * at all.
     */
    private fun openComment(issueId: Long, editing: CommentBackingViewModel.Existing?) {
        closeComment()
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val viewModel = CommentBackingViewModel(
            issueId = issueId,
            editing = editing,
            storage = storage,
            scope = localScope,
            onFinished = { changed ->
                closeComment()
                // Tell the issue rather than the board: the issue owns the
                // comment list, and it re-fetches so the *server* decides the
                // author's name and whether this caller may edit it.
                if (changed) issueViewModel?.onCommentsChanged()
            },
        )
        val view = CommentDialog(viewModel, localScope)
        view.mount(host)
        commentScope = localScope
        commentDismiss = { view.dismiss() }
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
        issueViewModel = null
    }
}
