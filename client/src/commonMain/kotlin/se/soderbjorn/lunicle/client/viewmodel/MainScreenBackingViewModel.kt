/**
 * Backing view-model for MainScreen: the top bar, the project picker, and the
 * issue board.
 *
 * The project convention, and the spec's requirement, in one sentence: one
 * immutable [State] over a single [StateFlow], every decision made here, and a
 * view that only renders what it is handed and forwards intent back. Nothing
 * about HTTP, JSON or the DOM appears on either side of that line.
 *
 * It also owns *which dialog is open*. That looks like view state and is not:
 * "the edit-project dialog is showing project 3" is a fact about what the user
 * is doing, the view has to be told it rather than decide it, and putting it
 * here is what keeps a DOM view from holding a `var currentDialog` that the
 * state flow does not know about. The dialogs' own contents belong to their own
 * view models — see [EditProjectBackingViewModel] and [IssueBackingViewModel].
 *
 * @see StorageRepository
 * @see SessionBackingViewModel
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.client.Ticket
import se.soderbjorn.lunicle.client.userMessage
import se.soderbjorn.lunicle.clientserver.BoardState
import se.soderbjorn.lunicle.clientserver.IssueSummary
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.StatusItem

/**
 * Which modal, if any, MainScreen is showing.
 *
 * Issues are deliberately NOT here any more: an issue opens in its own
 * *window*, several can be open at once, and "which windows are open" is a
 * list, not an either/or — see [MainScreenBackingViewModel.State.openIssueIds].
 * What remains modal is what genuinely blocks: the project form and the
 * resolution question a drag is waiting on.
 */
sealed interface ActiveDialog {
    /** None. */
    data object None : ActiveDialog

    /** The project dialog, creating. */
    data object NewProject : ActiveDialog

    /** The project dialog, editing an existing project. */
    data class EditProject(val project: ProjectSummary) : ActiveDialog

    /**
     * "Why are you closing this?" — the board's half of the resolution rule.
     *
     * A drag into a closing column cannot complete until the question is
     * answered, so the move is held rather than sent. The alternative — move it,
     * then ask — would put the card in Closed while the server refused the write
     * for having no resolution, and then snap it back: a card that moves, sticks,
     * and jumps home while a dialog is open reads as a bug in the drag.
     *
     * @property ticket "LMX-12", so the dialog can name what is being closed. The
     *   board has the prefix and the issue has the number; the dialog has neither
     *   and should not have to look them up.
     * @property resolutions what to offer, from this project's own vocabulary.
     */
    data class ChooseResolution(
        val issueId: Long,
        val statusId: Long,
        val ticket: String,
        val resolutions: List<StatusItem>,
    ) : ActiveDialog
}

/**
 * Whether two cards sit under the same header on the board.
 *
 * The same split the query sorts by and the column renders: in a closing column
 * the group is the resolution, everywhere else it is the priority. Written once
 * here because three places ask it — the drag, the optimistic reorder, and the
 * group rebuild — and three copies of this predicate would be three chances for
 * the board to disagree with itself about what a group is.
 *
 * @see BoardColumn.groups
 */
internal fun IssueSummary.sharesGroupWith(other: IssueSummary): Boolean =
    statusId == other.statusId &&
        if (resolutionId != null) {
            resolutionId == other.resolutionId
        } else {
            other.resolutionId == null && priorityId == other.priorityId
        }

/**
 * One board column, with the cards in it.
 *
 * Computed here rather than in the view: grouping issues by status is a
 * decision, an empty column still has to render, and a view that did its own
 * `groupBy` would be a second place the board's shape is defined.
 */
data class BoardColumn(
    val status: StatusItem,
    val issues: List<IssueSummary>,
    /**
     * The issues, split into labelled runs.
     *
     * A column is not a flat list: the cards in it come grouped, by resolution in
     * a closed column and by priority everywhere else, with a small header over
     * each run. [issues] is kept alongside for the count in the column head,
     * which is about the whole column.
     */
    val groups: List<BoardGroup>,
)

/**
 * One labelled run of cards inside a column.
 *
 * @property label what the header says — a resolution's name in a closing column,
 *   a priority's name in every other. Which of the two is not a property of the
 *   group; it is a property of the column, and the column already knows.
 * @property issues the cards, in the order the server sent them. NOT re-sorted
 *   here: the ordering is `Issues.sq`'s, over columns the client does not have,
 *   and grouping preserves it. See that query's ORDER BY.
 */
data class BoardGroup(
    val label: String,
    val issues: List<IssueSummary>,
)

/**
 * Owns MainScreen's state.
 *
 * @param storage the client's repository; the only collaborator.
 * @param scope coroutine scope the requests run in; a [SupervisorJob] so one
 *   failed request never tears down the next.
 */
class MainScreenBackingViewModel(
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _stateFlow = MutableStateFlow(State())

    /** The current state, observed by the view. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * The embed's `?project=`, remembered across reloads of the list.
     *
     * Only ever used to pick the *initial* project: once the user chooses
     * something in the picker, [selectedProjectId] wins. Otherwise a background
     * refresh would yank them back to whatever the embed asked for, which is the
     * kind of bug that looks like the picker is broken.
     */
    private var preferredName: String? = null
    private var selectedProjectId: Long? = null

    /**
     * The issue a deep link asked for, until it has been opened.
     *
     * One-shot, and cleared the moment it resolves — see [reload]. Left set, it
     * would re-open the same modal on every later board refresh: dismiss the
     * issue, drag a card, and it would spring open again.
     */
    private var preferredTicket: Ticket? = null

    /**
     * The last session fact acted on, so a repeated one is ignored.
     *
     * The session flow emits on every one of its own changes — a busy flag
     * flicking during sign-out, an error appearing — and almost none of those
     * are news to the board. Without this, each would re-fetch a list that
     * cannot have changed.
     */
    /**
     * The effective user the board was last loaded for, and whether it ever was.
     *
     * Two fields rather than a nullable one, because null is a legitimate
     * identity here — "signed out" — and cannot also mean "never loaded". With one
     * field, the very first emission for a signed-out visitor would compare equal
     * to the initial value and the board would never load at all.
     */
    private var knownIdentity: Long? = null
    private var hasLoadedForIdentity: Boolean = false

    /**
     * Immutable snapshot of the whole screen.
     *
     * @property isLoaded whether the first load has returned. Before it has the
     *   view shows nothing rather than an empty board — telling someone there
     *   are no projects and then discovering there are four is worse than a
     *   moment of nothing.
     * @property board the current project's board, or null when no project is
     *   selected. Null is a real state the spec asks for — "We might not be in
     *   any project".
     * @property canCreateProject whether to offer "New project…". An affordance;
     *   the server refuses regardless. Only the admin may create a project.
     */
    data class State(
        val isLoaded: Boolean = false,
        val isBusy: Boolean = false,
        val projects: List<ProjectSummary> = emptyList(),
        val board: BoardState? = null,
        val canCreateProject: Boolean = false,
        val dialog: ActiveDialog = ActiveDialog.None,
        /**
         * The issues with a window open, in the order they were opened.
         *
         * Ids rather than richer objects, because everything else about an
         * open issue lives in that window's own [IssueBackingViewModel] — this
         * list only answers "which windows exist", which is all the shell
         * needs to build panes. Always real ids, drafts included: "New issue"
         * creates the row before the window opens.
         */
        val openIssueIds: List<Long> = emptyList(),
        /**
         * The issue window that has focus, or null when the board does.
         *
         * Drives two things: which pane the shell marks active, and — through
         * [openIssueTicket] — what the address bar says. Must be a member of
         * [openIssueIds] or null; the mutations below maintain that.
         */
        val focusedIssueId: Long? = null,
        val errorMessage: String? = null,
    ) {
        /** The project in the top bar, or null. */
        val currentProject: ProjectSummary? get() = board?.project

        /** What the picker's button says. */
        val pickerLabel: String get() = currentProject?.name ?: "No project"

        /**
         * The columns, in order, each with its cards.
         *
         * Every status becomes a column even with nothing in it: a board that
         * hid its empty columns would rearrange itself as issues moved, and
         * "Closed" vanishing because nothing is closed yet is not helpful.
         */
        val columns: List<BoardColumn> get() {
            val board = board ?: return emptyList()
            val byStatus = board.issues.groupBy { it.statusId }
            return board.statuses.sortedBy { it.position }.map { status ->
                val issues = byStatus[status.id].orEmpty()
                BoardColumn(status, issues, groupsFor(status, issues))
            }
        }

        /**
         * Split a column's cards into labelled runs.
         *
         * Which vocabulary labels them follows the column: a closing column groups
         * by RESOLUTION, everything else by PRIORITY. That is the same rule the
         * server sorted by — "once an issue is closed, how urgent it was is
         * history" — so the grouping and the ordering agree by construction rather
         * than by coincidence. See Issues.sq's `forProject`.
         *
         * `groupBy` preserves encounter order, both of the groups and within them,
         * which is why nothing here sorts: the server already ordered the issues,
         * over `position` columns this client was never sent. Re-sorting would mean
         * re-deriving that order from data we do not have.
         *
         * An issue whose group cannot be named — a resolution or priority id with
         * no matching row, which should not happen and would mean the board and its
         * vocabularies disagree — falls into a group labelled with an em dash
         * rather than vanishing. A card that silently disappears from a board is
         * the worst possible way to render inconsistent data.
         */
        private fun groupsFor(status: StatusItem, issues: List<IssueSummary>): List<BoardGroup> {
            val vocabulary = if (status.requiresResolution) resolutionsById else prioritiesById
            return issues
                .groupBy { if (status.requiresResolution) it.resolutionId else it.priorityId }
                .map { (id, groupIssues) -> BoardGroup(vocabulary[id] ?: "—", groupIssues) }
        }

        private val prioritiesById: Map<Long, String>
            get() = board?.priorities.orEmpty().associate { it.id to it.name }

        private val resolutionsById: Map<Long?, String>
            get() = board?.resolutions.orEmpty().associate { it.id to it.name }

        /**
         * The focused issue window's ticket — "LMX-12" — or null.
         *
         * What the address bar should say, handed to the bootstrap to put there;
         * see main.kt. A *string* rather than a URL, because a URL is a fact about
         * the browser and this view model does not have one — an iOS client would
         * take the same ticket and build a `lunicle://` link out of it.
         *
         * With several windows open the URL follows *focus*: the link is to what
         * the user is looking at, and there is only one address bar.
         *
         * Null for a draft, and that is deliberate rather than incidental: a draft
         * is not on anyone's board, so a link to it would resolve to nothing for
         * everyone including its author. `board.issues` excludes drafts, so this
         * falls out rather than needing a check.
         */
        val openIssueTicket: String? get() {
            val focused = focusedIssueId ?: return null
            val board = board ?: return null
            val issue = board.issues.firstOrNull { it.id == focused } ?: return null
            return "${board.project.namePrefix}-${issue.number}"
        }

        /**
         * The window title for an open issue: "LMX-12", or "New issue" for a
         * draft (which has no public ticket yet — its row is invisible on the
         * board, so the number would name nothing anyone else can see).
         */
        fun issueWindowTitle(issueId: Long): String {
            val board = board ?: return "Issue"
            val issue = board.issues.firstOrNull { it.id == issueId } ?: return "New issue"
            return "${board.project.namePrefix}-${issue.number}"
        }

        /** Whether to offer the cogwheel. Admin only; an affordance. */
        val canEditCurrentProject: Boolean
            get() = board?.permissions?.canMutateProject == true

        /** Whether to offer "New issue". */
        val canCreateIssue: Boolean get() = board?.permissions?.canCreateIssue == true

        /**
         * What to say when there is no board, or null when there is one.
         *
         * Three different nothings, and they mean different things to the person
         * reading them: still loading, nothing to show, or nothing chosen.
         */
        val emptyMessage: String? get() = when {
            board != null -> null
            !isLoaded -> null
            projects.isEmpty() && canCreateProject ->
                "No projects yet. Use the picker to make one."
            projects.isEmpty() ->
                "No projects to show. Sign in if you have an account here."
            else -> "Pick a project to see its issues."
        }

        /** Render "LMX-123: Unable to remove user" for a card. */
        fun cardLabel(issue: IssueSummary): String =
            "${currentProject?.namePrefix ?: "?"}-${issue.number}: ${issue.title}"
    }

    /**
     * Start, with the embed's optional project name.
     *
     * Called by the app bootstrap. Deliberately does not fetch: the session
     * decides which projects come back, so there is nothing to ask for until
     * [onSessionChanged] says who is asking.
     */
    /**
     * Remember what the URL asked for. Called once by the bootstrap, before the
     * session arrives.
     *
     * @param preferredProjectName the embed's `?project=`, if any.
     * @param ticket the deep link's `?issue=`, if any — "LMX-12", already parsed.
     *   It decides both which board to open and which issue to put up.
     */
    fun start(preferredProjectName: String?, ticket: Ticket? = null) {
        preferredName = preferredProjectName?.takeIf { it.isNotBlank() }
        preferredTicket = ticket
    }

    /**
     * The session changed: someone signed in, or out, an admin started or stopped
     * impersonating, or the first session fetch returned.
     *
     * Called by the app bootstrap, which is the only thing that sees both view
     * models. This is the board's entire trigger.
     *
     * Signing out reloads rather than clears, because signed-out is not empty:
     * the public projects are still readable, and blanking the screen would hide
     * content the visitor is entitled to.
     *
     * ── Why this takes an identity and not a boolean ────────────────────────
     *
     * It used to take `isSignedIn: Boolean`, which was right until impersonation
     * existed and then silently wrong. An admin becoming another user goes from
     * signed-in to signed-in, so the boolean never changed, the guard below
     * matched, and the board did not reload — leaving the previous user's
     * projects on screen, with their cards still draggable, under somebody else's
     * name. The screen would have been lying about permissions, which is the one
     * thing it must never do.
     *
     * @param identity the **effective** user's id — the impersonated one while an
     *   admin is impersonating, and null when signed out. Any change to it is a
     *   change to what may be seen, which is exactly when the board must be
     *   re-fetched. Not a name: two accounts can share one, and the board would
     *   not reload between them.
     */
    fun onSessionChanged(identity: Long?, isKnown: Boolean) {
        if (!isKnown) return
        if (knownIdentity == identity && hasLoadedForIdentity) return
        knownIdentity = identity
        hasLoadedForIdentity = true
        println("MainScreen: session changed (effective user=$identity); reloading")
        // Who you are decides which projects exist as far as this screen is
        // concerned, so the selection has to be re-checked rather than kept: a
        // sign-out while looking at a private project must not leave its name in
        // the top bar.
        reload()
    }

    /** Re-fetch everything. */
    fun reload() {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.load(preferredName, selectedProjectId, preferredTicket) }
            _stateFlow.value = result.fold(
                onSuccess = { loaded ->
                    selectedProjectId = loaded.board?.project?.id
                    val previous = _stateFlow.value
                    // Open what the link asked for, if it is there — a window,
                    // as if the user had clicked the card. Consumed either
                    // way: a ticket that names an issue this caller cannot see
                    // resolves to nothing, and retrying it on every refresh
                    // would never start working.
                    val linked = deepLinkedIssueId(loaded.board)
                    previous.copy(
                        isLoaded = true,
                        isBusy = false,
                        projects = loaded.projects.projects,
                        canCreateProject = loaded.projects.canCreateProject,
                        board = loaded.board,
                        errorMessage = null,
                        openIssueIds = when {
                            linked == null || linked in previous.openIssueIds -> previous.openIssueIds
                            else -> previous.openIssueIds + linked
                        },
                        focusedIssueId = linked ?: previous.focusedIssueId,
                    )
                },
                onFailure = { t ->
                    println("MainScreen: load failed: ${t.message ?: t::class.simpleName}")
                    _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not reach the server."),
                    )
                },
            )
        }
    }

    /**
     * The issue a deep link wants a window for, or null.
     *
     * Consumes [preferredTicket] whether or not it resolved — see the field's
     * comment. A link to a deleted issue, or to one in a project this caller may
     * not read, lands on the board with nothing open, which is the honest answer
     * and is what the picker is for.
     */
    private fun deepLinkedIssueId(board: BoardState?): Long? {
        val ticket = preferredTicket ?: return null
        preferredTicket = null
        if (board == null) return null
        if (!board.project.namePrefix.equals(ticket.prefix, ignoreCase = true)) return null
        return board.issues.firstOrNull { it.number == ticket.number }?.id
    }

    /** Refresh only the current board, leaving the picker alone. */
    fun refreshBoard() {
        val projectId = selectedProjectId ?: return
        scope.launch {
            runCatching { storage.board(projectId) }
                .onSuccess { _stateFlow.value = _stateFlow.value.copy(board = it) }
                .onFailure { println("MainScreen: board refresh failed: ${it.message}") }
        }
    }

    /** The user picked a project. */
    fun onProjectSelected(id: Long) {
        if (selectedProjectId == id) return
        selectedProjectId = id
        // The embed's preference and any deep link have been overridden by a
        // deliberate choice, and must not win the next reload. The ticket goes too:
        // someone who picked a different project is not still asking for an issue
        // in the old one.
        preferredName = null
        preferredTicket = null
        // The open windows go too: they belong to the board being left, and a
        // window whose vocabularies and permissions came from another project
        // would be a window quietly lying about both. The shell closes the
        // panes when the list empties; unsaved drafts were the user's to keep
        // or discard before switching, same as before a reload.
        _stateFlow.value = _stateFlow.value.copy(
            isBusy = true,
            errorMessage = null,
            openIssueIds = emptyList(),
            focusedIssueId = null,
        )
        scope.launch {
            val result = runCatching { storage.board(id) }
            _stateFlow.value = result.fold(
                onSuccess = { _stateFlow.value.copy(board = it, isBusy = false, errorMessage = null) },
                onFailure = { t ->
                    _stateFlow.value.copy(isBusy = false, errorMessage = t.userMessage("Could not open that project."))
                },
            )
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    fun onNewProjectTapped() {
        _stateFlow.value = _stateFlow.value.copy(dialog = ActiveDialog.NewProject)
    }

    fun onProjectSettingsTapped() {
        val project = _stateFlow.value.currentProject ?: return
        _stateFlow.value = _stateFlow.value.copy(dialog = ActiveDialog.EditProject(project))
    }

    /**
     * A card was clicked: open its window, or focus the one already open.
     *
     * The re-focus half is the multi-window contract from the redesign: the
     * same issue never gets two windows. The shell reads [State.focusedIssueId]
     * and raises the matching pane.
     */
    fun onIssueOpened(issueId: Long) {
        val current = _stateFlow.value
        _stateFlow.value = current.copy(
            openIssueIds = if (issueId in current.openIssueIds) {
                current.openIssueIds
            } else {
                current.openIssueIds + issueId
            },
            focusedIssueId = issueId,
        )
    }

    /**
     * "New issue": create the hidden draft, then open a window on it.
     *
     * The row exists before the window does, which is what lets the editor
     * upload a file — an attachment needs an owner, and the `CHECK` in the
     * schema means there is no such thing as an attachment without one.
     * Discarding the window deletes the row; see
     * [IssueBackingViewModel.onCloseRequested].
     */
    fun onNewIssueTapped() {
        val projectId = selectedProjectId ?: return
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.createIssueDraft(projectId) }
            _stateFlow.value = result.fold(
                onSuccess = { draft ->
                    val now = _stateFlow.value
                    now.copy(
                        isBusy = false,
                        openIssueIds = now.openIssueIds + draft.id,
                        focusedIssueId = draft.id,
                    )
                },
                onFailure = { t ->
                    _stateFlow.value.copy(isBusy = false, errorMessage = t.userMessage("Could not start a new issue."))
                },
            )
        }
    }

    /**
     * An issue window closed (its view model called `onFinished`).
     *
     * Focus is not reassigned to another window: closing a window lands the
     * user on the board, which is where the closed issue's card is — the
     * natural "where was I" after a close. The shell focuses the board pane
     * when [State.focusedIssueId] goes null.
     */
    fun onIssueWindowClosed(issueId: Long) {
        val current = _stateFlow.value
        _stateFlow.value = current.copy(
            openIssueIds = current.openIssueIds - issueId,
            focusedIssueId = current.focusedIssueId?.takeIf { it != issueId },
        )
    }

    /**
     * The user focused a window — an issue's, or the board's (null).
     *
     * Reported by the shell so the address bar can follow focus; see
     * [State.openIssueTicket]. Ignores ids that have no window: focus events
     * can race a close, and a stale one must not resurrect the URL of a
     * window that is gone.
     */
    fun onIssueWindowFocused(issueId: Long?) {
        val current = _stateFlow.value
        if (issueId != null && issueId !in current.openIssueIds) return
        if (current.focusedIssueId == issueId) return
        _stateFlow.value = current.copy(focusedIssueId = issueId)
    }

    /**
     * A modal dialog closed (the project form or the resolution question —
     * issue windows report through [onIssueWindowClosed] instead).
     *
     * @param changed whether anything was written. Reloading only when something
     *   changed keeps a looked-at-and-dismissed dialog from costing a round-trip.
     * @param selectProjectId a project to switch to first, or null to stay where
     *   we are. Only the project dialog passes one, and only after OK: making a
     *   project and being left looking at the previous one — or, from the empty
     *   state, at "Pick a project to see its issues" — reads as if the thing was
     *   never created. The picker is not a second opinion here; the id is set
     *   *before* [reload] so the load resolves the new project rather than
     *   re-resolving the old selection and then being corrected.
     */
    fun onDialogClosed(changed: Boolean, selectProjectId: Long? = null) {
        _stateFlow.value = _stateFlow.value.copy(dialog = ActiveDialog.None)
        if (selectProjectId != null) {
            selectedProjectId = selectProjectId
            // Same reason as onProjectSelected: an explicit choice has been made,
            // so the embed's ?project= must not win the reload below.
            preferredName = null
        }
        if (changed) reload()
    }

    // ── Drag and drop ────────────────────────────────────────────────────────

    /**
     * A card was dragged into another column.
     *
     * Optimistic: the card moves in the local state immediately and the request
     * follows. Dragging is a direct-manipulation gesture — a card that springs
     * back to its old column for 200ms while the server thinks reads as broken,
     * even though nothing is wrong. A failure puts it back and says why.
     *
     * Not a special permission: this is a `status_id` write, and the server runs
     * the same `canEditIssue` the editor does. [IssueSummary.canEdit] is only
     * the affordance that stops the card from being draggable in the first
     * place.
     */
    fun onIssueDragged(issueId: Long, statusId: Long) {
        val current = _stateFlow.value
        val board = current.board ?: return
        val issue = board.issues.firstOrNull { it.id == issueId } ?: return
        if (issue.statusId == statusId) return
        if (!issue.canEdit) {
            println("MainScreen: drag ignored; this issue is not editable by this user")
            return
        }

        // Dropping into a closing column is a question before it is a move. Ask,
        // and let the answer finish the drag — see onResolutionChosen.
        //
        // Read from the target column's own flag rather than its name; see
        // Statuses.sq. The server checks again regardless, which is what makes
        // this an affordance rather than the rule.
        val target = board.statuses.firstOrNull { it.id == statusId }
        if (target?.requiresResolution == true) {
            _stateFlow.value = current.copy(
                dialog = ActiveDialog.ChooseResolution(
                    issueId = issueId,
                    statusId = statusId,
                    ticket = "${board.project.namePrefix}-${issue.number}",
                    resolutions = board.resolutions,
                ),
            )
            return
        }

        move(issueId, statusId, resolutionId = null)
    }

    /**
     * The resolution dialog was answered. Finish the drag it was holding.
     *
     * The move was never started, so there is nothing to undo if this is
     * cancelled — see [onResolutionCancelled].
     */
    fun onResolutionChosen(issueId: Long, statusId: Long, resolutionId: Long) {
        _stateFlow.value = _stateFlow.value.copy(dialog = ActiveDialog.None)
        move(issueId, statusId, resolutionId)
    }

    /**
     * The resolution dialog was dismissed. The card stays where it was.
     *
     * Nothing to revert: the optimistic move deliberately has not happened yet.
     * That is the whole reason the question is asked before the write rather than
     * after it.
     */
    fun onResolutionCancelled() {
        _stateFlow.value = _stateFlow.value.copy(dialog = ActiveDialog.None)
    }

    /**
     * Move an issue, optimistically.
     *
     * Split out of [onIssueDragged] because there are now two ways in — a plain
     * drag and a drag that had to stop to ask a question — and they must move the
     * card identically. Two copies of an optimistic update and its rollback is two
     * chances to get the rollback wrong.
     */
    private fun move(issueId: Long, statusId: Long, resolutionId: Long?) {
        val current = _stateFlow.value
        val board = current.board ?: return
        val issue = board.issues.firstOrNull { it.id == issueId } ?: return

        val previousStatusId = issue.statusId
        val previousResolutionId = issue.resolutionId
        // The resolution moves with the card, so the group header it lands under
        // is right immediately rather than after the next board fetch.
        _stateFlow.value = current.copy(
            board = board.copy(
                issues = board.issues.map {
                    if (it.id == issueId) it.copy(statusId = statusId, resolutionId = resolutionId) else it
                },
            ),
            errorMessage = null,
        )

        scope.launch {
            runCatching { storage.setIssueStatus(issueId, statusId, resolutionId) }
                .onFailure { t ->
                    println("MainScreen: move failed: ${t.message}")
                    // Put it back. Reading `board` out of the *current* state
                    // rather than closing over the old one, so a second drag
                    // that happened while this request was in flight is not
                    // silently undone as well.
                    val now = _stateFlow.value
                    _stateFlow.value = now.copy(
                        board = now.board?.copy(
                            issues = now.board!!.issues.map {
                                if (it.id == issueId) {
                                    it.copy(
                                        statusId = previousStatusId,
                                        resolutionId = previousResolutionId,
                                    )
                                } else {
                                    it
                                }
                            },
                        ),
                        errorMessage = t.userMessage("Could not move that issue."),
                    )
                }
        }
    }

    /**
     * Reorder within a group: put [issueId] immediately before or after [targetId].
     *
     * The view says "here, relative to that card" rather than "at index 4",
     * because that is what a drop actually knows — an index would mean the view
     * counting positions in a list the view model built, and the two miscounting
     * differently the first time a group changed under a drag.
     *
     * A drop onto a card in a DIFFERENT group is not a reorder and is ignored
     * here: crossing groups means changing an issue's priority or resolution,
     * which is an edit with consequences and belongs in the dialog, not in a
     * gesture. Dropping on the column still moves between statuses — see
     * [onIssueDragged].
     */
    fun onIssueReordered(issueId: Long, targetId: Long, placeBefore: Boolean) {
        if (issueId == targetId) return
        val current = _stateFlow.value
        val board = current.board ?: return
        val moved = board.issues.firstOrNull { it.id == issueId } ?: return
        val target = board.issues.firstOrNull { it.id == targetId } ?: return
        if (!moved.canEdit) {
            println("MainScreen: reorder ignored; this issue is not editable by this user")
            return
        }
        if (!moved.sharesGroupWith(target)) return

        // The group as it is on screen right now, which is the order the server
        // sent — so removing the dragged card and re-inserting it beside the
        // target produces exactly what the user sees after the drop.
        val group = board.issues.filter { it.sharesGroupWith(moved) }
        val without = group.filterNot { it.id == issueId }
        val at = without.indexOfFirst { it.id == targetId }
        if (at < 0) return
        val reordered = without.toMutableList().apply {
            add(if (placeBefore) at else at + 1, moved)
        }
        val ids = reordered.map { it.id }
        if (ids == group.map { it.id }) return // dropped where it already was

        // Optimistic, and the rebuild is the fiddly part: `board.issues` is one
        // flat list in the server's order, and only this group's slice changes.
        // Rewriting the whole list by group would reorder groups against each
        // other; this replaces the moved card's neighbours in place by index.
        val positions = board.issues.withIndex().filter { it.value.sharesGroupWith(moved) }.map { it.index }
        val next = board.issues.toMutableList()
        positions.forEachIndexed { slot, boardIndex -> next[boardIndex] = reordered[slot] }
        _stateFlow.value = current.copy(board = board.copy(issues = next), errorMessage = null)

        scope.launch {
            runCatching { storage.setIssueOrder(issueId, ids) }
                .onFailure { t ->
                    println("MainScreen: reorder failed: ${t.message}")
                    // Re-fetch rather than hand-rolling an undo. The optimistic
                    // edit above permuted a slice of a list; reversing it exactly
                    // means remembering the old slice AND that nothing else moved
                    // meanwhile. The board is one request — ask it.
                    _stateFlow.value = _stateFlow.value.copy(
                        errorMessage = t.userMessage("Could not reorder that issue."),
                    )
                    refreshBoard()
                }
        }
    }

    /** Dismiss the error line. */
    fun onErrorDismissed() {
        _stateFlow.value = _stateFlow.value.copy(errorMessage = null)
    }
}
