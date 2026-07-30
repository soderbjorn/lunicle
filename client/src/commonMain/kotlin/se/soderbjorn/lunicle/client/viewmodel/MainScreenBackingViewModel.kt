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
import se.soderbjorn.lunicle.clientserver.SprintItem
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.clientserver.StatusItem
import se.soderbjorn.lunicle.clientserver.VocabularyItem
import se.soderbjorn.lunicle.clientserver.UiSettingKeys

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

    // There used to be a ConfirmProjectSwitch here — "you have unsaved changes",
    // the guard on LNL-84's project switch, which reset the whole window when it
    // went ahead. LNL-160 removed the switch itself: a project is a board pane
    // now, and opening one takes nothing away, so there is no longer a destructive
    // act to stop and ask about.

    /** The project dialog, creating. */
    data object NewProject : ActiveDialog

    // EditProject and Statistics used to be cases here. Both are PANES now
    // (LNL-160): a project surface opens beside the board rather than over it, so
    // "which dialog is up" is no longer the question being asked about either.
    // See PaneRef.Settings / PaneRef.Analytics, and the board toolbar that opens
    // them. NewProject stays a dialog — there is no project to hang a pane off
    // until it exists.

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
     * @property resolutions what to offer, from this project's own vocabulary. Each
     *   carries [StatusItem.isDone], which is what decides whether picking it reveals
     *   the fixed-version picker below (LNL-134).
     * @property versions this project's release versions, for the inline fixed-version
     *   picker the dialog shows once a done resolution is chosen. Empty for a project
     *   with none, in which case no picker renders and none is demanded.
     * @property requireFixedVersion whether the project requires a fixed version when
     *   resolving as done — what makes the picker a required field rather than an
     *   optional one. The server re-checks, so this is only the affordance.
     * @property canManageVersions whether the caller may add and delete versions from
     *   the picker — a project administrator. The vocabulary routes are admin-gated,
     *   so a non-admin only picks from what exists.
     * @property selectedResolutionId / [selectedFixedVersionId] the dialog's live
     *   choice. Held on the dialog rather than in the web view so the picker can add a
     *   version mid-flow — the add re-emits this dialog with a longer [versions] and
     *   the new id selected — without a remount losing the resolution already chosen.
     *   That is what makes the dialog a controlled component; see onResolutionPicked.
     */
    data class ChooseResolution(
        /**
         * The project whose board the drag started on.
         *
         * Carried because the dialog outlives the gesture: the move it finishes
         * (see `onResolutionConfirmed`) has to name a board, and since LNL-160
         * "the board" is not a thing the view model can look up on its own.
         */
        val projectId: Long,
        val issueId: Long,
        val statusId: Long,
        val ticket: String,
        val resolutions: List<StatusItem>,
        val versions: List<VocabularyItem>,
        val requireFixedVersion: Boolean,
        val canManageVersions: Boolean,
        val selectedResolutionId: Long? = null,
        val selectedFixedVersionId: Long? = null,
    ) : ActiveDialog

    /**
     * "What is the new sprint called?"
     *
     * Opened from the board's scope picker rather than only from project
     * settings, because the moment you notice you need a sprint is the moment you
     * are looking at the board — and making somebody find the cog, scroll past
     * five vocabularies and come back is enough friction that the sprint gets made
     * badly or not at all.
     *
     * Carries nothing but the project: a name is the only thing a sprint needs,
     * and the new one is deliberately NOT activated. See SprintRepository.
     */
    data class NewSprint(val projectId: Long) : ActiveDialog

    /**
     * Sprint planning: tick the issues that belong in this sprint.
     *
     * A dialog rather than a board gesture because planning is inherently bulk.
     * Fifteen issues one at a time through the detail pane is miserable enough
     * that the feature would go unused, and the board's two drag axes are already
     * spent — column drop is status, card position is order.
     *
     * @property candidates what may be ticked: the backlog, plus whatever is
     *   already in this sprint. Deliberately not the whole board — an issue in
     *   *another* sprint is somebody else's plan, and quietly stealing it from a
     *   list of checkboxes is not a thing this dialog should make easy.
     * @property selected pre-ticked: what is in the sprint right now. The save
     *   sends the whole set, so unticking is how an issue leaves. See
     *   SprintMembership.
     */
    data class PlanSprint(
        val projectId: Long,
        val sprintId: Long,
        val sprintName: String,
        val candidates: List<IssueSummary>,
        val selected: Set<Long>,
        val prefix: String,
    ) : ActiveDialog

    // `CompleteSprint` stood here — "this sprint is over, where does the unfinished work
    // go?" — and is gone (LNL-196). The question is still asked, and by the same view
    // (`CompleteSprintDialog`); it is raised from the project's Sprints section now
    // rather than from this board's scope picker, so the prompt it is built from is
    // `EditProjectBackingViewModel.PendingSprintCompletion`.
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
 * Whether this card may be dropped onto [other] to land beside it.
 *
 * Wider than [sharesGroupWith] on purpose, and the difference is LNL-40. A
 * column reads as one list with subheadings, so dragging a card past a header
 * and dropping it under the next one is a gesture people make constantly — and
 * it used to draw an insertion line and then do nothing, because the drop was
 * checked against the group while the line was drawn from the column. Now the
 * drop lands and the card takes the priority of the group it was dropped into.
 * See [MainScreenBackingViewModel.onIssueReordered].
 *
 * A CLOSING column is the exception, and stays as strict as it was. The groups
 * there are resolutions — "why was this closed" — which is a record of a
 * decision, not a rank. Rewriting one by dropping a card two rows lower is not
 * a reordering gesture that happens to change a field; it is a different claim
 * about the issue, and it belongs in the editor where it can be seen and
 * reconsidered. So a drop across resolutions is still refused, and the view
 * draws no line for it.
 *
 * Predicate rather than a check inside the drop handler because the VIEW needs
 * the same answer to decide whether to promise anything — the two disagreeing
 * is the bug itself.
 */
fun IssueSummary.canReorderOnto(other: IssueSummary): Boolean =
    statusId == other.statusId &&
        if (resolutionId != null || other.resolutionId != null) {
            resolutionId == other.resolutionId
        } else {
            true
        }

/**
 * One board column, with the cards in it.
 *
 * Computed here rather than in the view: grouping issues by status is a
 * decision, an empty column still has to render, and a view that did its own
 * `groupBy` would be a second place the board's shape is defined.
 */
/**
 * One row of the board's scope picker.
 *
 * Deliberately as thin as the dropdown it feeds — an id and a label — because
 * that is all the difference between "the Backlog scope", "Sprint 3" and
 * "New sprint…" amounts to at the point of rendering. Which of those a row
 * actually is lives in [MainScreenBackingViewModel.isSprintAction], on the model
 * side, so the view never has to know.
 */
data class ScopeItem(val id: Long, val label: String)

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
 * How loudly a group's priority should be drawn, or null for not at all.
 *
 * Two values, because the toolkit's palette holds exactly two colours that mean
 * "attend to this" — warn and danger — and a third would have to be invented
 * rather than themed. See LNL-49.
 *
 * Deliberately NOT a CSS class name. This module is shared, and a Compose
 * client would map the same two values onto something that is not a stylesheet;
 * the web view does the mapping, exactly as it already does for label and
 * component chips.
 */
enum class PriorityEmphasis { URGENT, HIGH }

/**
 * One labelled run of cards inside a column.
 *
 * @property label what the header says — a resolution's name in a closing column,
 *   a priority's name in every other. Which of the two is not a property of the
 *   group; it is a property of the column, and the column already knows.
 * @property issues the cards, in the order the server sent them. NOT re-sorted
 *   here: the ordering is `Issues.sq`'s, over columns the client does not have,
 *   and grouping preserves it. See that query's ORDER BY.
 * @property emphasis how urgent this run is, for the header and the cards under
 *   it to draw alike, or null for the ordinary majority. Null in a closing
 *   column too — see [MainScreenBackingViewModel.State.emphasisFor].
 */
data class BoardGroup(
    val label: String,
    val issues: List<IssueSummary>,
    val emphasis: PriorityEmphasis? = null,
)

/**
 * One open issue window, as the screen model knows it.
 *
 * ── Why this is not just an id any more ─────────────────────────────────────
 *
 * It was, until LNL-48. Switching project used to close every window, so an open
 * issue always belonged to the board on screen and everything about it could be
 * looked up there — the prefix from `board.project`, the number from
 * `board.issues`. Windows now survive the switch, so at any moment most of them
 * may belong to projects whose boards this client is not holding, and an id alone
 * cannot title its own window.
 *
 * So each row carries the little it needs to speak for itself. Deliberately only
 * that: the issue's fields, its comments and its vocabularies still live in that
 * window's own [IssueBackingViewModel], which fetches them and owns them. This is
 * a title bar and a URL, not a second copy of the issue.
 *
 * @property projectId which project's board this window came from. What
 *   [MainScreenBackingViewModel] matches on when a board arrives and these rows
 *   need bringing up to date, and what the bootstrap checks before it builds a
 *   window — see main.kt's `IssueWindows.create`.
 * @property namePrefix the project's ticket prefix as of the last board seen for
 *   it. A renamed prefix reaches the windows of that project when its board next
 *   loads; windows of other projects keep theirs, which is the point.
 * @property number the issue's number on its board, or null while it is still a
 *   draft. Null is a real and common state — "New issue" opens a window on a row
 *   nobody else can see — and it is what [ticket] refuses to spell.
 */
data class OpenIssueWindow(
    val issueId: Long,
    val projectId: Long,
    val namePrefix: String,
    val number: Long? = null,
    /**
     * The status a fresh draft opens on, or null for the server's default. Set
     * only when the window was opened from a board column's "Create issue…" so the
     * draft starts filed in that column (LNL-124); carried to the issue window,
     * where it seeds the status field without counting as an edit.
     */
    val initialStatusId: Long? = null,
) {
    /** "LMX-12", or null while this is a draft with no public number. */
    val ticket: String? get() = number?.let { "$namePrefix-$it" }
}

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
    /**
     * The app-wide register of open editors.
     *
     * LNL-84 read it to guard a project switch, which was destructive because a
     * switch tore down every window. LNL-160 removed the switch — a project is a
     * pane now, and opening one takes nothing away — so nothing here asks about
     * dirty editors any more. Kept as a collaborator because the *windows* still
     * join it and the bootstrap still threads one shared instance through.
     */
    @Suppress("unused")
    private val editorRegistry: EditorDirtyRegistry = EditorDirtyRegistry(),
) {
    private val _stateFlow = MutableStateFlow(State())

    /** The current state, observed by the view. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * The projects with a board or issue pane open, pushed by the workspace.
     *
     * The membership rule for [State.boards], held here rather than derived from
     * the state because it has to survive a reload that empties the map: it says
     * what to fetch, and the map says what has arrived.
     *
     * @see onOpenProjectsChanged
     */
    private var openProjectIds: Set<Long> = emptySet()

    /**
     * Ask for a project's board to be opened somewhere sensible.
     *
     * Set by the bootstrap to the workspace's `onBoardOpened`. A lambda rather
     * than a direct call because *where* a board goes is the workspace's
     * decision — the tab that already holds it, or the one in front — and this
     * view model deliberately has no notion of which tab anybody is on. The one
     * caller is [onDialogClosed]: a project that has just been created should be
     * on screen, or making it reads as having failed.
     *
     * A no-op by default, so a test or a caller that has not wired it simply gets
     * no navigation rather than a crash.
     */
    var projectToOpen: (Long) -> Unit = {}

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
        /**
         * Every board that is open somewhere, by project id.
         *
         * The plural is the whole of what LNL-160 changed here. A tab is a
         * working set rather than a project (see [WorkspaceBackingViewModel]),
         * so two boards from two projects can be on screen at once and neither
         * of them is "the" board. What used to be a single nullable field is a
         * map, and everything derived from it moved to [BoardScreen], which is
         * that map's entry plus the state around it.
         *
         * Membership is driven from outside, by [onOpenProjectsChanged]: the
         * workspace says which projects have a pane, and this holds exactly
         * those. A project with a pane but no entry yet is loading; an entry
         * with no pane is dropped, because keeping boards for panes nobody has
         * open is a cache with no eviction rule and a straight path to serving
         * hours-old cards on a tab reopened tomorrow.
         */
        val boards: Map<Long, BoardState> = emptyMap(),
        val canCreateProject: Boolean = false,
        val dialog: ActiveDialog = ActiveDialog.None,
        /**
         * The issues with a window open, in the order they were opened.
         *
         * Windows outlive the board they were opened from (LNL-48), so this can
         * hold issues from several projects at once and each row has to carry
         * enough to title itself without the board. Everything *else* about an
         * open issue still lives in that window's own [IssueBackingViewModel] —
         * this list only answers "which windows exist, and what do their title
         * bars say". Always real ids, drafts included: "New issue" creates the
         * row before the window opens.
         *
         * @see OpenIssueWindow
         */
        val openIssues: List<OpenIssueWindow> = emptyList(),
        /**
         * The issue window that has focus, or null when the board does.
         *
         * Drives two things: which pane the shell marks active, and — through
         * [openIssueTicket] — what the address bar says. Must be a member of
         * [openIssues] or null; the mutations below maintain that.
         */
        val focusedIssueId: Long? = null,
        /**
         * Whether someone is signed in, effective-user-wise. Not a permission —
         * it decides whether the project-settings cog is offered at all, since the
         * dialog now opens for every signed-in user (a non-admin gets only the
         * notification toggle). Set from the identity the board last loaded for.
         */
        val isSignedIn: Boolean = false,
        /**
         * Whether the **effective** user is an instance admin. Decides whether the
         * admin-settings button is offered at all.
         *
         * Effective, not real, which means an admin who is impersonating loses it
         * — deliberately, and matching every other admin affordance here. The
         * server draws the same line on the route, so this is only about not
         * offering a button that would 403. See AdminRoutes.
         *
         * Not read off `board.permissions.canMutateProject`, which is the same fact
         * and was the tempting shortcut: that one is null until a board loads, and
         * this button is about the instance rather than about whatever project
         * happens to be on screen. An admin on a deployment with no projects yet
         * still has accounts to administer.
         */
        val isSysAdmin: Boolean = false,
        val errorMessage: String? = null,
        /**
         * Each open board's live text filter, by project id; absent means "show
         * everything".
         *
         * A view concern in the shallowest sense — it changes nothing on the
         * server and survives no reload worth speaking of — but a filter is a
         * decision about which cards a board shows, and that is
         * [BoardScreen.columns]' business, not the view's.
         *
         * Per project rather than one for the app, because two boards on screen
         * are two searches: filtering one to "auth" while the other keeps showing
         * everything is the ordinary case, and a single field would have made
         * typing in one board silently narrow the other.
         */
        val filterQueries: Map<Long, String> = emptyMap(),
        /**
         * Which sprint each open board is scoped to — a sprint id, or one of the
         * two sentinels below — by project id.
         *
         * A single `Long` rather than a sealed type, because it is exactly what
         * the dropdown deals in and every value it can hold is a row in that
         * dropdown. Two sentinels, both impossible sprint ids (`sprints.id` is
         * `AUTOINCREMENT` and starts at 1):
         *
         *  - [SCOPE_ALL] — every issue, sprinted or not. What a board with no
         *    active sprint shows, and what a kanban board is permanently on.
         *  - [SCOPE_BACKLOG] — the un-scheduled work, `sprintId == null`. Its own
         *    scope rather than "all minus the sprints", because during planning
         *    "what is not scheduled yet" is the question being asked.
         *
         * Set to the project's active sprint when its board first loads, so
         * somebody with a sprint running opens onto their sprint. Per project for
         * [filterQueries]' reason, and more sharply: a sprint id is meaningless
         * on another project's board, so a shared field would not merely be
         * surprising, it would name nothing.
         */
        val sprintScopes: Map<Long, Long> = emptyMap(),
        /**
         * This user's own view choices, for every project they have, keyed by
         * project id — the decoded [UiSettingKeys.PROJECT_PREFS] blob (LNL-100).
         *
         * The *whole* map, not just the current project's slice, and deliberately
         * so. It is loaded once per identity alongside the board (see [reload]) and
         * then carried forward untouched by every `copy` that changes something
         * else — which is what lets a project switch, a filter keystroke or a card
         * move keep the user's hidden columns without re-reading them. The one
         * project on screen reads its own record through [hiddenColumnIds]; the
         * rest ride along so switching to them is instant and needs no request.
         *
         * Empty for a signed-out visitor and for a signed-in user who has hidden
         * nothing anywhere — the two are the same board, which is the point of
         * LNL-100 being signed-in-only: there is nowhere to keep a visitor's choice,
         * so none is offered.
         */
        val projectPrefs: Map<Long, UserProjectPrefs> = emptyMap(),
    ) {
        /**
         * Everything one board pane needs, for the project it shows.
         *
         * The seam LNL-160 turns on. A board pane knows its project and nothing
         * else about the app; handing it one of these gives it the board, the
         * columns, the filter, the scope and the permissions *for that project*,
         * with no way to reach another one's by accident. Cheap — a value over
         * this state, computed on read — so a view may hold it for the length of
         * a render and throw it away.
         *
         * Valid for any project id, including one with no board loaded (yet, or
         * ever): every accessor answers the empty/false/null case, because "the
         * board has not arrived" and "the board is empty" have to be tellable
         * apart by the caller rather than by a crash. See [BoardScreen.isLoading].
         */
        fun screen(projectId: Long): BoardScreen = BoardScreen(this, projectId)

        /**
         * Whether to offer the instance settings gear.
         *
         * Admins only, and unlike [BoardScreen.canOpenProjectSettings] this needs
         * no board: the accounts on a deployment exist before its first project
         * does. An affordance — the route refuses a non-admin regardless.
         */
        val canOpenAdminSettings: Boolean
            get() = isSysAdmin

        /**
         * The ids of [openIssues], in the same order.
         *
         * The shell builds one pane per open issue and knows nothing else about
         * them, so it takes this rather than the rows.
         */
        val openIssueIds: List<Long> get() = openIssues.map { it.issueId }

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
         * Read off the window's own row rather than off the board, because since
         * LNL-48 the focused window need not belong to the board on screen — and
         * a ticket looked up in the wrong project's issue list is either nothing
         * or, worse, a real ticket naming somebody else's issue.
         *
         * Null for a draft, and that is deliberate rather than incidental: a draft
         * is not on anyone's board, so a link to it would resolve to nothing for
         * everyone including its author. A draft's row carries a null number, so
         * this falls out rather than needing a check.
         */
        val openIssueTicket: String? get() =
            focusedIssueId?.let { id -> openIssues.firstOrNull { it.issueId == id } }?.ticket

        /**
         * The window title for an open issue: "LMX-12", or "New issue" for a
         * draft (which has no public ticket yet — its row is invisible on the
         * board, so the number would name nothing anyone else can see).
         *
         * Read against the issue's OWN project's board rather than whatever board
         * happens to be in front — the window outlives the board it was opened
         * from, and since LNL-160 there may be several to be wrong about.
         */
        fun issueWindowTitle(issueId: Long): String {
            val open = openIssues.firstOrNull { it.issueId == issueId } ?: return "Issue"
            val screen = screen(open.projectId)
            // Hiding issue numbers (LNL-105) reaches the window's pane label too: it
            // shows the issue's own title in place of its key — the key is what is
            // being hidden. A draft has no title on the board yet, so it keeps "New
            // issue"; a saved issue whose title has not arrived falls back to a word.
            if (screen.hideIssueNumbers) {
                val title = screen.board?.issues?.firstOrNull { it.id == issueId }
                    ?.title?.takeIf { it.isNotBlank() }
                return title ?: if (open.ticket == null) "New issue" else "Issue"
            }
            return open.ticket ?: "New issue"
        }

        /**
         * What to say when a tab has no panes at all, or null when it has some.
         *
         * The app-level nothing, as against [BoardScreen.emptyMessage], which is
         * one board's. Several different nothings, and they mean different things
         * to the person reading them: still loading, nothing to show, or nothing
         * open — and "nothing to show" has a different next step depending on who
         * is looking. Each branch names the one action its reader can actually
         * take, which is the whole job of an empty state; a message that points at
         * a control the reader does not have is worse than no message, because it
         * reads as the app being broken.
         */
        val emptyTabMessage: String? get() = when {
            !isLoaded -> null
            // The "+" in the top bar: "New project…" lives in the shell's add
            // menu. Pointing a brand new admin anywhere else sends them to a
            // control that cannot do this.
            projects.isEmpty() && canCreateProject ->
                "No projects yet. Use the + button in the top bar to make one."
            // Signed in and still shown nothing: either the instance is empty or
            // every project on it is private to somebody else, and this reader
            // cannot tell those apart and should not have to. Deliberately not
            // told to use the "+" — they do not have it, only an admin does.
            projects.isEmpty() && isSignedIn ->
                "No projects to show. Ask an administrator to create one, " +
                    "or to give you access to an existing one."
            projects.isEmpty() ->
                "No projects to show. Sign in if you have an account here."
            else -> "Nothing open in this tab. Use + to add a board."
        }
    }

    /**
     * One project's board, and everything drawn from it.
     *
     * What a board pane reads. Every accessor that used to hang off `State` and
     * silently mean "the current project" lives here instead, where the project
     * is named — which is the whole of how two boards coexist without either
     * being able to reach the other's cards, filter or permissions.
     *
     * A value, not a store: it holds the state it was made from and derives on
     * read, so it is correct for exactly as long as that state is current and is
     * meant to be rebuilt (`state.screen(id)`) on every render rather than kept.
     *
     * @property state the whole app state; this is a view onto it.
     * @property projectId the project this is the board of. Need not be loaded —
     *   see [isLoading].
     */
    data class BoardScreen(
        private val state: State,
        val projectId: Long,
    ) {
        /** This project's board, or null while it is still on its way. */
        val board: BoardState? get() = state.boards[projectId]

        /**
         * The project itself.
         *
         * Off the board when it has arrived, and off the accessible project list
         * otherwise — so a pane can title itself "Board · Lunamux" during the
         * moment before its cards land, rather than reading "Board · …" and then
         * flickering into a name.
         */
        val project: ProjectSummary?
            get() = board?.project ?: state.projects.firstOrNull { it.id == projectId }

        /**
         * Whether this board is still being fetched.
         *
         * Distinct from "empty" and from "gone": a pane showing a spinner, a
         * pane showing a board with no cards on it, and a pane whose project the
         * reader can no longer see are three different things to draw, and only
         * this one is temporary.
         */
        val isLoading: Boolean get() = board == null && state.projects.any { it.id == projectId }

        /** This board's filter text; empty for "show everything". */
        val filterQuery: String get() = state.filterQueries[projectId].orEmpty()

        /**
         * Whether anybody is signed in — an app fact, forwarded so a board pane
         * needs only this one object to draw itself. The signed-in-only column ⋮
         * menu is the one thing on a board that asks.
         */
        val isSignedIn: Boolean get() = state.isSignedIn

        /**
         * Every accessible project's ticket prefix, for turning a `PREFIX-N` in a
         * card title into a link (LNL-139). App-wide rather than this project's,
         * deliberately: a card may refer to an issue anywhere the reader can see.
         */
        val prefixes: List<String> get() = state.projects.map { it.namePrefix }

        /** This board's sprint scope, as chosen; see [effectiveSprintScope]. */
        val sprintScope: Long get() = state.sprintScopes[projectId] ?: SCOPE_ALL

        /**
         * Whether the sprint scope control renders at all.
         *
         * The whole of what keeps a pure-kanban board untouched: a project with no
         * sprints has an empty list here, so there is no dropdown, no extra
         * control beside the filter box, and nothing to configure off. Presence of
         * sprints is the feature flag — see Sprints.sq.
         */
        val showsSprintScope: Boolean get() = scopeSprints.isNotEmpty()

        /**
         * The sprints the scope dropdown offers, planning order.
         *
         * Completed sprints included, unlike the issue editor's list: "show me
         * what shipped in the last sprint" is a real thing to want to look at,
         * where scheduling new work into a finished sprint is not a real thing to
         * want to do.
         */
        val scopeSprints: List<SprintItem> get() = board?.sprints.orEmpty()

        /**
         * Every row of the scope picker: the scopes, then the actions.
         *
         * One list rather than the view assembling it, because which actions are
         * offered depends on three things the view has no business deciding —
         * whether the caller may configure the project, whether the current scope
         * is a sprint at all, and whether that sprint is still open.
         *
         * Activating and planning are absent on a finished sprint rather than
         * disabled: a greyed-out "Plan" on something already over is a control
         * explaining a state the row above it already showed.
         *
         * **Completing a sprint is not here** (LNL-196). It ended everybody's columns
         * from a control that reads as a view switch, and it was within reach of whoever
         * happened to be looking at the board rather than of the people planning it —
         * so it is a per-row action in the project's Sprints section now, beside the date
         * it sets and at the rung that owns the sprints. What stays here is the three
         * things that are about *this view* or about making the next sprint.
         */
        val sprintScopeItems: List<ScopeItem> get() {
            val scopes = listOf(
                ScopeItem(SCOPE_ALL, "All issues"),
                ScopeItem(SCOPE_BACKLOG, "Backlog"),
            ) + scopeSprints.map { ScopeItem(it.id, it.name) }
            if (!canEditProject) return scopes

            val scoped = scopeSprints.firstOrNull { it.id == sprintScope }
            val actions = buildList {
                add(ScopeItem(ACTION_NEW_SPRINT, "New sprint…"))
                if (scoped != null && scoped.isOpen) {
                    add(ScopeItem(ACTION_PLAN_SPRINT, "Plan ${scoped.name}…"))
                    if (board?.activeSprintId != scoped.id) {
                        add(ScopeItem(ACTION_ACTIVATE_SPRINT, "Make ${scoped.name} active"))
                    }
                }
            }
            return scopes + actions
        }

        /**
         * The scope actually applied, which is [sprintScope] unless that names a
         * sprint the board no longer has.
         *
         * Read by [columns] and by [sprintScopeLabel] both, so the two cannot
         * disagree. They did: a refresh keeps the user's scope on purpose (see
         * `refreshBoard`), so a sprint deleted by somebody else left the picker
         * reading "All issues" while the filter still asked for the missing id —
         * an empty board that reads as "no work" rather than as "wrong sprint",
         * which is the outcome this whole fallback exists to avoid.
         */
        val effectiveSprintScope: Long get() = when {
            sprintScope == SCOPE_ALL || sprintScope == SCOPE_BACKLOG -> sprintScope
            scopeSprints.any { it.id == sprintScope } -> sprintScope
            else -> SCOPE_ALL
        }

        /** What the scope control's button says. */
        val sprintScopeLabel: String get() = when (val scope = effectiveSprintScope) {
            SCOPE_ALL -> "All issues"
            SCOPE_BACKLOG -> "Backlog"
            else -> scopeSprints.firstOrNull { it.id == scope }?.name ?: "All issues"
        }

        /**
         * The columns, in order, each with its cards.
         *
         * Every status becomes a column even with nothing in it: a board that
         * hid its empty columns would rearrange itself as issues moved, and
         * "Closed" vanishing because nothing is closed yet is not helpful.
         */
        val columns: List<BoardColumn> get() {
            val board = board ?: return emptyList()
            // Filter before grouping, so a matched column's count and its groups
            // both describe only what is shown. Matched on the same label the
            // card renders — "LMX-123: title" — so a search hits both the ticket
            // number and the words, which is what someone typing into the box
            // expects. Empty query short-circuits to the whole board.
            val query = filterQuery.trim()
            val visible = if (query.isEmpty()) {
                board.issues
            } else {
                // Against the FULL text — number and words — even when the number is
                // hidden from the card (LNL-105): hiding is a display choice, not a
                // decision to forget the numbers exist, and searching "LMX-123" should
                // still find its card. See cardSearchText, and cardLabel for display.
                board.issues.filter { cardSearchText(it).contains(query, ignoreCase = true) }
            }
            // Then by sprint, as a second predicate on the same pass — a filter,
            // never a sort level. Nothing about the ordering, the grouping or the
            // (status, priority|resolution, sort_order, number) rank the server
            // sent changes: scoping only decides which cards are here at all.
            val scoped = when (val scope = effectiveSprintScope) {
                SCOPE_ALL -> visible
                SCOPE_BACKLOG -> visible.filter { it.sprintId == null }
                else -> visible.filter { it.sprintId == scope }
            }
            val byStatus = scoped.groupBy { it.statusId }
            return board.statuses.sortedBy { it.position }.map { status ->
                val issues = byStatus[status.id].orEmpty()
                BoardColumn(status, issues, groupsFor(status, issues))
            }
        }

        /**
         * The status ids this user has hidden on this board (LNL-100).
         *
         * This project's slice of [State.projectPrefs], as a set because the board
         * only ever asks "is this column hidden?" — membership, never order. Empty
         * for a project this user has hidden nothing on and for a signed-out
         * visitor, both of which draw every column.
         *
         * A set of ids and not of columns on purpose: an id in here that names no
         * current status — a column deleted since it was hidden — simply matches
         * nothing in [columns] and falls away, so a stale preference never has to be
         * cleaned up to stop mattering.
         */
        val hiddenColumnIds: Set<Long> get() =
            state.projectPrefs[projectId]?.hiddenColumnIds?.toSet().orEmpty()

        /**
         * The columns to draw as full lanes: every column this user has not hidden,
         * in board order. What the board's main row iterates.
         */
        val shownColumns: List<BoardColumn> get() =
            columns.filterNot { it.status.id in hiddenColumnIds }

        /**
         * The columns to draw collapsed in the far-left rail, in board order
         * (LNL-100) — NOT in the order they were hidden. A hidden column keeps its
         * place in the board's sequence so restoring it is predictable: it comes
         * back where it always was, and two people who hid the same columns see the
         * rail in the same order regardless of who hid what first.
         *
         * Built from [columns], so a hidden id that names no current status
         * contributes nothing — the same fall-away [hiddenColumnIds] describes.
         */
        val hiddenColumns: List<BoardColumn> get() =
            columns.filter { it.status.id in hiddenColumnIds }

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
                .map { (id, groupIssues) ->
                    BoardGroup(
                        label = vocabulary[id] ?: "—",
                        issues = groupIssues,
                        // Per group rather than per card, which is what keeps a
                        // closing column out of it without naming it: the ids
                        // grouped there are resolutions, and a resolution has no
                        // urgency to draw.
                        emphasis = if (status.requiresResolution) null else emphasisFor(id),
                    )
                }
        }

        /**
         * How loudly to draw a priority — the top of the scale, or the two top of
         * it, or nothing. See LNL-49.
         *
         * By POSITION IN THE SCALE, never by name: priorities are per-project rows
         * that a project may rename or re-count at will (see Priorities.sq), so
         * "High" is not a value this can test for. Position 0 is the highest —
         * sorted here rather than taken on trust, the same way [columns] sorts the
         * statuses it was already sent in order.
         *
         * The two short-scale cases are the ones that matter. There must always be
         * an unemphasised bucket left below — if every card on the board is
         * shouting then none of them is — so a two-priority scale emphasises only
         * its top, and a scale with a single priority draws nothing at all, there
         * being no distinction on it to draw.
         *
         * An id matching no row — the board and its vocabulary disagreeing, which
         * `groupsFor` labels with an em dash — is not emphasised. `indexOfFirst`
         * answers -1 there, and -1 falls through to null with the rest.
         */
        private fun emphasisFor(priorityId: Long?): PriorityEmphasis? {
            val priorities = board?.priorities.orEmpty().sortedBy { it.position }
            if (priorities.size < 2) return null
            return when (priorities.indexOfFirst { it.id == priorityId }) {
                0 -> PriorityEmphasis.URGENT
                1 -> PriorityEmphasis.HIGH.takeIf { priorities.size > 2 }
                else -> null
            }
        }

        private val prioritiesById: Map<Long, String>
            get() = board?.priorities.orEmpty().associate { it.id to it.name }

        private val resolutionsById: Map<Long?, String>
            get() = board?.resolutions.orEmpty().associate { it.id to it.name }

        /**
         * Whether the caller may *configure* this project — admin only; an
         * affordance. Decides the settings form, not whether the cog appears.
         */
        val canEditProject: Boolean
            get() = board?.permissions?.canMutateProject == true

        /**
         * Whether the caller may rename this project or delete it, as opposed to
         * merely administering it. A project administrator may not; see
         * ProjectPermissionsView.canMutateProjectIdentity for why those are two
         * questions and not one.
         */
        val canRenameProject: Boolean
            get() = board?.permissions?.canMutateProjectIdentity == true

        /**
         * Whether to offer the cogwheel at all.
         *
         * Any signed-in user with this board in front of them: the dialog opens
         * for everyone now, showing an admin the settings and everyone else just
         * the new-issue notification toggle. A signed-out visitor gets no cog —
         * they have nothing to set and no address to notify.
         */
        val canOpenProjectSettings: Boolean
            get() = board != null && state.isSignedIn

        /**
         * Whether to offer the statistics button in this board's pane header.
         *
         * A loaded board is the whole condition — no sign-in required, unlike
         * [canOpenProjectSettings]. Somebody reading a public project is reading
         * the issues these numbers count, so there is nothing here they are not
         * already looking at. The one exception is the commit count, which is a
         * fact about a repository rather than about the board; that is the
         * accepted trade recorded in StatisticsRoutes, and it is why the token is
         * read-only and scoped to a single repository.
         */
        val canOpenStatistics: Boolean
            get() = board != null

        /** Whether to offer "New issue" on this board. */
        val canCreateIssue: Boolean get() = board?.permissions?.canCreateIssue == true

        /**
         * What this board's pane should say instead of columns, or null when it
         * has some to draw.
         *
         * One board's nothing, as against [State.emptyTabMessage], which is the
         * app's. Loading is null rather than a message: the pane draws a spinner
         * for that (LNL-135), and a sentence that is about to be replaced by
         * cards reads as an error for the beat it is up.
         */
        val emptyMessage: String? get() = when {
            board != null -> null
            isLoading -> null
            // The project is not in the accessible list at all: deleted, or access
            // withdrawn while the pane was open. The workspace prunes the pane on
            // the next project-list tick, so this is what shows for that beat.
            else -> "This project is no longer available."
        }

        /**
         * Render a card's line — "LMX-123: Unable to remove user", or just the
         * title when this user has hidden issue numbers on this project (LNL-105).
         */
        fun cardLabel(issue: IssueSummary): String =
            if (hideIssueNumbers) issue.title
            else "${project?.namePrefix ?: "?"}-${issue.number}: ${issue.title}"

        /**
         * The key of this card's epic — "LMX-98" — or null when it has no parent, or
         * when the parent's number did not travel (LNL-154). The web draws it as the
         * card's "↳ LMX-98" back-reference and opens the parent by
         * [IssueSummary.parentId]. Built from this project's prefix because a
         * parent shares its child's project, exactly as [cardLabel] forms this card's
         * own key — so the two keys read the same way. Deliberately over
         * [IssueSummary.parentNumber] (the authoritative number the server sent
         * alongside [IssueSummary.parentId]) rather than a scan of [board] for the
         * parent id: the parent may be in a hidden column or scoped out and so absent
         * from the list here, which is the whole reason the number rides on the wire.
         */
        fun parentKey(issue: IssueSummary): String? {
            val number = issue.parentNumber ?: return null
            val prefix = project?.namePrefix ?: return null
            return "$prefix-$number"
        }

        /**
         * The full text a card is searched by — always the number and the title,
         * regardless of [hideIssueNumbers], so the filter box still finds a card by
         * its ticket key even when the key is not drawn. See [columns]' filter.
         */
        fun cardSearchText(issue: IssueSummary): String =
            "${project?.namePrefix ?: "?"}-${issue.number}: ${issue.title}"

        /**
         * Whether this project's board and issue windows hide the issue number
         * (LNL-105, LNL-194).
         *
         * A **project** setting since LNL-194 — read off the board's own summary, the
         * way `showIssueAuthor` beside it is. It was a per-user, per-project view
         * choice in the [State.projectPrefs] blob the hidden columns still live in;
         * moving it means every reader of one board sees the same thing, which is what
         * a shared board should mean.
         */
        val hideIssueNumbers: Boolean get() = project?.hideIssueNumbers == true
    }

    /**
     * The project a name — the embed's `?project=` — refers to, or null.
     *
     * The deep links used to be resolved in here, because there was one board and
     * this owned it. There are several now and none of them is "current", so
     * *placing* a deep link is the workspace's decision and this only answers the
     * lookup half of it. See main.kt, which reads `?project=`, `?projectId=` and
     * `?issue=` and turns each into an `onBoardOpened` / `onIssueOpened`.
     *
     * Matched case-insensitively, as the picker's resolution always did: a name in
     * a URL is typed by a person.
     */
    fun projectIdNamed(name: String?): Long? {
        val wanted = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return _stateFlow.value.projects.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.id
    }

    /**
     * The project whose prefix is [prefix] — "LMX" — or null.
     *
     * A prefix is unique across the instance, so it alone says which project a
     * ticket reference belongs to. What turns a `?issue=LMX-12` or a clicked
     * `LMX-12` in a comment into a board to open.
     */
    fun projectIdWithPrefix(prefix: String): Long? =
        _stateFlow.value.projects.firstOrNull { it.namePrefix.equals(prefix, ignoreCase = true) }?.id

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
     * @param isSysAdmin whether that effective user is an instance admin. Defaulted
     *   so the existing callers and tests that only care about the reload trigger
     *   do not have to say anything about it; the bootstrap passes the real value.
     *   Only decides whether the admin-settings button is offered — see
     *   [State.isSysAdmin].
     */
    fun onSessionChanged(identity: Long?, isKnown: Boolean, isSysAdmin: Boolean = false) {
        if (!isKnown) return
        // Before the guard below, not after. Admin-ness is not what decides whether
        // to reload — the identity is — but it is a fact about the current session
        // and it has to reach the state even on the emissions the guard drops.
        // Setting it only alongside a reload would leave the button missing until
        // something unrelated happened to change who is asking.
        if (_stateFlow.value.isSysAdmin != isSysAdmin) {
            _stateFlow.value = _stateFlow.value.copy(isSysAdmin = isSysAdmin)
        }
        if (knownIdentity == identity && hasLoadedForIdentity) return
        knownIdentity = identity
        hasLoadedForIdentity = true
        println("MainScreen: session changed (effective user=$identity); reloading")
        // Who you are decides which projects exist as far as this screen is
        // concerned, so the selection has to be re-checked rather than kept: a
        // sign-out while looking at a private project must not leave its name in
        // the top bar.
        //
        // Dropped *here* rather than left to [reload]'s answer, and only on an
        // identity change. reload() is a round trip, and until it lands this state
        // still holds the list the previous account could see — so every reader of
        // it in that gap is reading somebody else's projects, and the "+" menu's
        // board flyout would offer a signed-out visitor a private project by name.
        // reload() itself must not do this — it also runs after the project dialog,
        // where blanking the list would flash the whole app empty for a round trip
        // over a change that altered one project.
        //
        // `isBusy` in the SAME copy, not left to reload()'s own line: an empty list
        // said while busy is "asking", and an empty list said while idle is "you
        // may see nothing". They mean opposite things to the workspace, which seeds
        // a layout from the second and waits on the first (see main.kt's project
        // collector), and two separate emissions would briefly publish the first as
        // if it were the second.
        _stateFlow.value = _stateFlow.value.copy(
            projects = emptyList(),
            canCreateProject = false,
            isBusy = true,
        )
        reload()
    }

    /**
     * Re-fetch the project list, the caller's preferences, and every open board.
     *
     * Since LNL-160 the *list* and the *boards* are two separate answers rather
     * than one: which projects exist depends on who is asking, and which of them
     * are on screen depends on the workspace. So this fetches the list, drops
     * boards for anything that just became inaccessible, and re-fetches whatever
     * is still open under the new identity.
     */
    fun reload() {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.projects() }
            // The caller's own view preferences, read on the same trigger as the
            // boards and for the same reason: they belong to the effective user, so
            // an identity change — sign-in, sign-out, impersonation — is exactly
            // when they must be re-read, and reload() is what fires on it. A failed
            // read degrades to "no preferences", which is the default board rather
            // than a boot failure; the boards themselves are what a hard failure
            // below reports on. Read whether or not the list loaded, so signed-in
            // preferences are ready the moment a board does arrive.
            val prefs = runCatching { UserProjectPreferences.decode(storage.uiSettings().settings[UiSettingKeys.PROJECT_PREFS]) }
                .getOrDefault(emptyMap())
            result.fold(
                onSuccess = { projects ->
                    val accessible = projects.projects.mapTo(mutableSetOf()) { it.id }
                    _stateFlow.value = _stateFlow.value.copy(
                        isLoaded = true,
                        isBusy = false,
                        projects = projects.projects,
                        canCreateProject = projects.canCreateProject,
                        // Every board goes: they were loaded for whoever was asking
                        // before, and the permissions baked into them are that
                        // person's. Re-fetched immediately below, so the gap is one
                        // paint of the spinner rather than an empty app.
                        boards = emptyMap(),
                        // Replaced wholesale, like the boards: these are the
                        // effective user's preferences, and a reload is where a
                        // different user's arrive over the last one's. Merging would
                        // leave one account holding another's hidden columns.
                        projectPrefs = prefs,
                        // knownIdentity was just set by onSessionChanged (or is
                        // null for a signed-out visitor). null means signed out.
                        isSignedIn = knownIdentity != null,
                        errorMessage = null,
                        // Windows for projects this identity cannot reach are not
                        // theirs to keep open. The rest survive the reload and are
                        // reconciled against their board as it lands.
                        openIssues = _stateFlow.value.openIssues.filter { it.projectId in accessible },
                    )
                    openProjectIds.filter { it in accessible }.forEach { fetchBoard(it, resetScope = true) }
                },
                onFailure = { t ->
                    println("MainScreen: load failed: ${t.message ?: t::class.simpleName}")
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not reach the server."),
                    )
                },
            )
        }
    }

    /**
     * The workspace says these projects have a pane open.
     *
     * The one input that decides which boards this holds. Fetches what is newly
     * open, drops what is no longer, and leaves the rest alone — so adding a
     * second board to a tab costs one request and touches nothing already on
     * screen.
     *
     * Idempotent and cheap to call on every workspace tick: an unchanged set does
     * nothing at all.
     */
    fun onOpenProjectsChanged(projectIds: Set<Long>) {
        if (openProjectIds == projectIds) return
        val added = projectIds - openProjectIds
        openProjectIds = projectIds
        val state = _stateFlow.value
        val kept = state.boards.filterKeys { it in projectIds }
        if (kept.size != state.boards.size) {
            _stateFlow.value = state.copy(
                boards = kept,
                // The per-board view choices go with the board. A filter typed on
                // a board that has since been closed is not a preference anyone
                // holds; keeping it would have it silently re-apply if the same
                // board were opened again an hour later.
                filterQueries = state.filterQueries.filterKeys { it in projectIds },
                sprintScopes = state.sprintScopes.filterKeys { it in projectIds },
            )
        }
        // Only once the identity is settled: before that there is nobody to fetch
        // as, and reload() will fetch the whole open set the moment there is.
        if (!hasLoadedForIdentity) return
        added.forEach { fetchBoard(it, resetScope = true) }
    }

    /**
     * Re-fetch one board, keeping the reader's place on it.
     *
     * @param resetScope whether the sprint scope is re-derived from the board's
     *   active sprint. True when the board is arriving for the first time — so
     *   somebody with a sprint running opens onto it — and false for a refresh,
     *   where yanking them back to the active sprint because a card moved would
     *   undo a choice they made on purpose.
     */
    private fun fetchBoard(projectId: Long, resetScope: Boolean) {
        scope.launch {
            runCatching { storage.board(projectId) }
                .onSuccess { board ->
                    // The pane may have been closed while this was in flight;
                    // storing the board then would resurrect an entry the eviction
                    // above just removed, and nothing would ever clear it.
                    if (projectId !in openProjectIds) return@onSuccess
                    val current = _stateFlow.value
                    _stateFlow.value = current.copy(
                        boards = current.boards + (projectId to board),
                        sprintScopes =
                            if (resetScope) current.sprintScopes + (projectId to board.defaultScope())
                            else current.sprintScopes,
                        // A refresh is how a just-saved draft stops being one: the
                        // row is on the board now, so its window can finally be
                        // titled with the number it was given.
                        openIssues = current.openIssues.reconciledWith(board),
                    )
                }
                .onFailure { println("MainScreen: board $projectId load failed: ${it.message}") }
        }
    }

    /**
     * Refresh the board an issue belongs to, after a write to that issue.
     *
     * Takes the project rather than assuming one: a window outlives the board it
     * was opened from, and since LNL-160 there may be several open, so "the
     * board" is not a thing this can guess at.
     */
    fun refreshBoard(projectId: Long) {
        if (projectId !in openProjectIds) return
        fetchBoard(projectId, resetScope = false)
    }

    /** Refresh every open board — after a change that could touch any of them. */
    fun refreshAllBoards() {
        openProjectIds.forEach { fetchBoard(it, resetScope = false) }
    }

    /**
     * The filter box changed. Live: every keystroke narrows the board.
     *
     * No trip to the server and no request to fail — the board is already all
     * here, and hiding cards is a pure re-derivation of [State.columns]. A
     * repeated value (the box re-emitting the same text) still re-emits state,
     * which is harmless: nothing downstream of an unchanged query changes.
     */
    fun onFilterChanged(projectId: Long, query: String) {
        val state = _stateFlow.value
        if (state.filterQueries[projectId].orEmpty() == query) return
        _stateFlow.value = state.copy(filterQueries = state.filterQueries + (projectId to query))
    }

    /**
     * The user picked a sprint scope. Purely local, like the filter box.
     *
     * No request: every issue the board could show is already here, and scoping
     * is a re-derivation of [State.columns]. Reloading to narrow a list the client
     * is holding would be a round-trip to learn something it already knows —
     * and would lose the cards while it waited.
     */
    fun onSprintScopeSelected(projectId: Long, scope: Long) {
        // The action rows share this dropdown, so this is also where they arrive.
        // Split here rather than in the view, so the view stays a renderer: which
        // ids mean "act" rather than "scope" is a fact about the model.
        if (isSprintAction(scope)) {
            onSprintActionSelected(projectId, scope)
            return
        }
        val state = _stateFlow.value
        if (state.screen(projectId).sprintScope == scope) return
        _stateFlow.value = state.copy(sprintScopes = state.sprintScopes + (projectId to scope))
    }

    /**
     * Hide a column from this user's board — the ⋮ menu's "Hide" (LNL-100).
     *
     * A change to [State.projectPrefs] for the current project, applied to state
     * at once so the column collapses into the rail on the next paint, and then
     * persisted in the background. No round-trip is waited on: the board already
     * knows what to draw, and a preference that has not finished saving must not
     * hold up the frame that shows it — the same bargain [ThemePersister] strikes
     * for the theme.
     */
    fun onHideColumn(projectId: Long, statusId: Long) = setColumnHidden(projectId, statusId, hidden = true)

    /**
     * Restore a hidden column — a click on its collapsed box in the rail
     * (LNL-100). The inverse of [onHideColumn], through the same path.
     */
    fun onShowColumn(projectId: Long, statusId: Long) = setColumnHidden(projectId, statusId, hidden = false)

    /**
     * Fold one column's hidden-ness into [State.projectPrefs] and persist.
     *
     * Keyed on the project whose board the ⋮ was opened on, which since LNL-160
     * is the pane's own rather than "the" board's. A no-op change — hiding what is
     * already hidden, showing what is already shown — returns without touching
     * state or the server, so a stray double-click costs neither a repaint nor a
     * write.
     *
     * A project whose last hidden column is shown again drops out of the map
     * entirely rather than lingering as an empty record: "this user has no
     * preferences here" is the absence of an entry, not an entry that happens to
     * hide nothing, and [UserProjectPreferences.encode] prunes on the way out to
     * keep the two the same fact on the wire.
     */
    private fun setColumnHidden(projectId: Long, statusId: Long, hidden: Boolean) {
        val state = _stateFlow.value
        val current = state.projectPrefs[projectId]?.hiddenColumnIds.orEmpty()
        val next = when {
            hidden && statusId !in current -> current + statusId
            !hidden && statusId in current -> current - statusId
            else -> return
        }
        // copy() rather than a fresh record, so a sibling preference on the same project
        // would be carried across a column hide/show rather than reset. There is none
        // today — hide-issue-numbers was the other one and became the project's in
        // LNL-194 — and this stays a copy because the record is the shape a future
        // per-user-per-project choice grows on. See UserProjectPrefs.
        val record = (state.projectPrefs[projectId] ?: UserProjectPrefs()).copy(hiddenColumnIds = next)
        val nextPrefs = state.projectPrefs.toMutableMap().apply {
            if (record == UserProjectPrefs()) remove(projectId) else put(projectId, record)
        }
        _stateFlow.value = state.copy(projectPrefs = nextPrefs)
        persistProjectPrefs(nextPrefs)
    }

    // `isHidingIssueNumbers` and `setIssueNumbersHidden` were here (LNL-105) and are
    // gone (LNL-194). The switch is the project's now, so the settings pane writes it
    // through the project-display route like every other project setting, and the
    // board reads the answer off ProjectSummary rather than off this view model's
    // preference blob. See BoardScreen.hideIssueNumbers.

    /**
     * Write the whole preferences blob back to the account, in the background.
     *
     * Failures are swallowed, [ThemePersister]'s reasoning exactly: this is a view
     * choice, the honest consequence of a failed write is that it does not survive
     * the next reload, and an alert about a hidden column would be a worse outcome
     * than the one it reports. Signed out there is no account to write to and the
     * POST 403s — caught here — which is what makes hiding a signed-in-only
     * feature without a second gate: the control is not offered to a visitor, and
     * were it somehow reached, the choice simply would not persist.
     */
    private fun persistProjectPrefs(prefs: Map<Long, UserProjectPrefs>) {
        scope.launch {
            runCatching {
                storage.setUiSetting(UiSettingKeys.PROJECT_PREFS, UserProjectPreferences.encode(prefs))
            }
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    fun onNewProjectTapped() {
        _stateFlow.value = _stateFlow.value.copy(dialog = ActiveDialog.NewProject)
    }

    /**
     * A card was clicked: open its window, or focus the one already open.
     *
     * The re-focus half is the multi-window contract from the redesign: the
     * same issue never gets two windows. The shell reads [State.focusedIssueId]
     * and raises the matching pane.
     */
    fun onIssueOpened(projectId: Long, issueId: Long) {
        val current = _stateFlow.value
        // The board named by the pane the card was clicked in — a card can only be
        // clicked where it is drawn. That is what makes the row below describable
        // at all: the prefix and number come from the board this issue is on,
        // once, and are then the window's own.
        val board = current.boards[projectId] ?: return
        _stateFlow.value = current.copy(
            openIssues = if (current.openIssues.any { it.issueId == issueId }) {
                current.openIssues
            } else {
                val number = board.issues.firstOrNull { it.id == issueId }?.number
                current.openIssues + OpenIssueWindow(
                    issueId = issueId,
                    projectId = board.project.id,
                    namePrefix = board.project.namePrefix,
                    number = number,
                )
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
    /**
     * @param initialStatusId the column a board "Create issue…" was fired from, so
     *   the draft opens filed there (LNL-124); null for the plain "+" / hotkey,
     *   which lets the draft take the server's default status.
     */
    fun onNewIssueTapped(projectId: Long, initialStatusId: Long? = null) {
        val namePrefix = _stateFlow.value.screen(projectId).project?.namePrefix ?: return
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.createIssueDraft(projectId) }
            _stateFlow.value = result.fold(
                onSuccess = { draft ->
                    val now = _stateFlow.value
                    now.copy(
                        isBusy = false,
                        // No number: the row exists but is not on anybody's board
                        // yet, so the window is titled "New issue" until a save
                        // publishes it and the board that comes back says what it
                        // was numbered. The project is the caller's argument
                        // rather than anything read after the round-trip, so a
                        // pane closed mid-request cannot misfile the draft.
                        openIssues = now.openIssues + OpenIssueWindow(
                            issueId = draft.id,
                            projectId = projectId,
                            namePrefix = namePrefix,
                            initialStatusId = initialStatusId,
                        ),
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
            openIssues = current.openIssues.filterNot { it.issueId == issueId },
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
     * @param openProjectId a project whose board should be opened, or null. Only
     *   the project dialog passes one, and only after OK: making a project and
     *   being left looking at the tab you were on reads as if the thing was never
     *   created. Reported to the bootstrap through [projectToOpen] rather than
     *   acted on here — where a board *goes* is the workspace's decision, and this
     *   view model has deliberately stopped having an opinion about which project
     *   is in front of anybody.
     */
    fun onDialogClosed(changed: Boolean, openProjectId: Long? = null) {
        _stateFlow.value = _stateFlow.value.copy(dialog = ActiveDialog.None)
        if (openProjectId != null) projectToOpen(openProjectId)
        if (changed) reload()
    }

    // ── Sprints ──────────────────────────────────────────────────────────────

    /**
     * The scope picker's action rows were chosen rather than a scope.
     *
     * Actions ride in the same dropdown as the scopes, on negative sentinel ids,
     * because that is where you already are when you want them — see
     * [ActiveDialog.NewSprint]. The important half is that none of them changes
     * [State.sprintScope]: picking "New sprint…" must not also silently rescope
     * the board to a sprint that does not exist yet.
     */
    fun onSprintActionSelected(projectId: Long, action: Long) {
        val current = _stateFlow.value
        val board = current.boards[projectId] ?: return
        val scoped = board.sprints.firstOrNull { it.id == current.screen(projectId).sprintScope }
        when (action) {
            ACTION_NEW_SPRINT -> _stateFlow.value =
                current.copy(dialog = ActiveDialog.NewSprint(board.project.id))

            ACTION_PLAN_SPRINT -> {
                val sprint = scoped ?: return
                _stateFlow.value = current.copy(
                    dialog = ActiveDialog.PlanSprint(
                        projectId = board.project.id,
                        sprintId = sprint.id,
                        sprintName = sprint.name,
                        // The backlog plus this sprint's own. An issue in another
                        // sprint is not offered — see PlanSprint.candidates.
                        candidates = board.issues.filter { it.sprintId == null || it.sprintId == sprint.id },
                        selected = board.issues.filter { it.sprintId == sprint.id }.map { it.id }.toSet(),
                        prefix = board.project.namePrefix,
                    ),
                )
            }

            ACTION_ACTIVATE_SPRINT -> {
                val sprint = scoped ?: return
                activate(projectId, sprint.id)
            }

        }
    }

    /** The name dialog was filled in. Creates the sprint; does not activate it. */
    fun onNewSprintNamed(projectId: Long, name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        _stateFlow.value = _stateFlow.value.copy(dialog = ActiveDialog.None, isBusy = true)
        scope.launch {
            // The vocabulary route, not a sprint-specific one: creating a sprint
            // is adding a row to a per-project named ordered thing, which is
            // exactly what that family does. It answers with the settings state,
            // which the board has no use for — hence the refresh rather than a
            // patch. See VocabularyRepository.add.
            runCatching { storage.addVocabulary(projectId, VocabularyKind.SPRINT, clean) }
                .onSuccess {
                    _stateFlow.value = _stateFlow.value.copy(isBusy = false)
                    refreshBoard(projectId)
                }
                .onFailure { t ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not make that sprint."),
                    )
                }
        }
    }

    /**
     * Where a card's context menu may send this issue.
     *
     * Empty when there is nothing to offer — no sprints, or a card this caller
     * cannot edit — and the view uses that emptiness to decide not to suppress
     * the browser's own menu. A context menu that opens onto nothing is worse
     * than no context menu.
     *
     * The sprint the issue is already in is left out, and so are completed ones:
     * this is a list of *destinations*, and neither "where it already is" nor "a
     * sprint that is over" is one. The backlog is offered unless that is where it
     * already is, for the same reason.
     */
    fun sprintDestinationsFor(projectId: Long, issue: IssueSummary): List<ScopeItem> {
        val board = _stateFlow.value.boards[projectId] ?: return emptyList()
        if (!issue.canEdit || board.sprints.isEmpty()) return emptyList()
        val sprints = board.sprints
            .filter { it.isOpen && it.id != issue.sprintId }
            .map { ScopeItem(it.id, "Move to ${it.name}") }
        val backlog =
            if (issue.sprintId != null) listOf(ScopeItem(SCOPE_BACKLOG, "Move to the backlog")) else emptyList()
        return sprints + backlog
    }

    /**
     * A destination was picked from a card's context menu.
     *
     * Its own route rather than the editor's PUT — the menu has an issue and a
     * sprint and nothing else, and a body that could also carry a title would be
     * a way to overwrite fields it never asked about. See IssueSprintUpdate.
     *
     * Not optimistic, unlike the drag: a scheduled card can vanish from the board
     * entirely when a sprint scope is on, and a card that disappears and comes
     * back on failure is worse than one that takes a moment to go.
     */
    fun onIssueSprintChosen(projectId: Long, issueId: Long, destination: Long) {
        val sprintId = destination.takeIf { it != SCOPE_BACKLOG }
        _stateFlow.value = _stateFlow.value.copy(isBusy = true)
        scope.launch {
            runCatching { storage.setIssueSprint(issueId, sprintId) }
                .onSuccess {
                    _stateFlow.value = _stateFlow.value.copy(isBusy = false, errorMessage = null)
                    refreshBoard(projectId)
                }
                .onFailure { t ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not schedule that issue."),
                    )
                }
        }
    }

    /** The planning dialog was saved. Sends the whole set; see SprintMembership. */
    fun onSprintPlanned(projectId: Long, sprintId: Long, issueIds: Set<Long>) {
        _stateFlow.value = _stateFlow.value.copy(dialog = ActiveDialog.None, isBusy = true)
        replaceBoard(projectId, "Could not save that plan.") {
            storage.setSprintIssues(projectId, sprintId, issueIds.toList())
        }
    }

    /**
     * Where the board should be scoped once a sprint write comes back.
     *
     * Keep what the user chose, if it still names something. Anything else undoes
     * a deliberate act: somebody who scoped to Sprint 1 and then planned it wants
     * to see Sprint 1 with the work in it, and bouncing them to "All issues"
     * hides the very change they just made.
     *
     * A completed sprint still counts as naming something. It is offered as a
     * scope on purpose — "what shipped last sprint" is a real thing to look at —
     * so completing the sprint you are looking at leaves you looking at it, with
     * the finished work that stayed behind. Only a scope that names *nothing*
     * falls back, which after these three writes means a sprint somebody else
     * deleted while the dialog was open.
     */
    private fun State.scopeAfter(board: BoardState, preferred: Long): Long = when {
        preferred == SCOPE_ALL || preferred == SCOPE_BACKLOG -> preferred
        board.sprints.any { it.id == preferred } -> preferred
        else -> board.defaultScope()
    }

    // `onSprintCompleted` stood here and is gone with the board's Complete row (LNL-196).
    // The write lives in EditProjectBackingViewModel now, beside the section that raises
    // the question. The board still catches up — a settings write tells it to reload, which
    // is how every other change made in that pane reaches it.

    private fun activate(projectId: Long, sprintId: Long) {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true)
        // Scoped to the sprint just activated rather than to whatever was on
        // screen: activating IS the act of saying "this is what we are working
        // on", so landing anywhere else would be answering a different question.
        replaceBoard(projectId, "Could not activate that sprint.", preferredScope = sprintId) {
            storage.activateSprint(projectId, sprintId)
        }
    }

    /**
     * Run a sprint write that answers with the whole board, and adopt it.
     *
     * Not optimistic, unlike the drag: these writes move issues *between* sprints
     * and the scope decides which of those are on screen, so a local guess would
     * have to reimplement the completion rule to know which cards to move. The
     * server already answered that question — waiting for it is cheaper than
     * being wrong about it.
     *
     * The scope the user is on is kept rather than re-derived — see [scopeAfter]
     * for why, and for the one case that falls back.
     */
    private fun replaceBoard(
        projectId: Long,
        failureMessage: String,
        preferredScope: Long? = null,
        write: suspend () -> BoardState,
    ) {
        scope.launch {
            runCatching { write() }
                .onSuccess { board ->
                    val current = _stateFlow.value
                    val scope = current.scopeAfter(
                        board,
                        preferredScope ?: current.screen(projectId).sprintScope,
                    )
                    _stateFlow.value = current.copy(
                        boards = current.boards + (projectId to board),
                        isBusy = false,
                        errorMessage = null,
                        sprintScopes = current.sprintScopes + (projectId to scope),
                    )
                }
                .onFailure { t ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage(failureMessage),
                    )
                }
        }
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
    fun onIssueDragged(projectId: Long, issueId: Long, statusId: Long) {
        val current = _stateFlow.value
        val board = current.boards[projectId] ?: return
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
                    projectId = projectId,
                    issueId = issueId,
                    statusId = statusId,
                    ticket = "${board.project.namePrefix}-${issue.number}",
                    resolutions = board.resolutions,
                    versions = board.versions,
                    requireFixedVersion = board.project.requireFixedVersionOnResolve,
                    canManageVersions = board.permissions.canManageSprintsAndVersions,
                ),
            )
            return
        }

        move(projectId, issueId, statusId, resolutionId = null)
    }

    /**
     * A resolution radio was picked. Held on the dialog rather than acted on, because
     * a *done* resolution may still owe a fixed version before the move can go (LNL-134).
     * Switching to a resolution that is not done clears any version already chosen, so
     * a fixed version picked for "Done" does not ride out on "Duplicate".
     */
    fun onResolutionPicked(resolutionId: Long) {
        updateResolutionDialog { dialog ->
            val isDone = dialog.resolutions.firstOrNull { it.id == resolutionId }?.isDone == true
            dialog.copy(
                selectedResolutionId = resolutionId,
                selectedFixedVersionId = if (isDone) dialog.selectedFixedVersionId else null,
            )
        }
    }

    /** The fixed-version picker changed. Null clears it. */
    fun onResolutionFixedVersionPicked(versionId: Long?) {
        updateResolutionDialog { it.copy(selectedFixedVersionId = versionId) }
    }

    /**
     * Add a version from the resolution dialog's picker, and select it (LNL-134).
     * Writes immediately through the admin-gated vocabulary route, then re-emits the
     * dialog with the longer version list and the new id chosen — which is why the
     * dialog is controlled: a remount would drop the resolution already selected.
     */
    fun onResolutionVersionAdded(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        val projectId = (_stateFlow.value.dialog as? ActiveDialog.ChooseResolution)?.projectId ?: return
        scope.launch {
            runCatching { storage.addVocabulary(projectId, VocabularyKind.VERSION, clean) }.fold(
                onSuccess = { settings ->
                    val items = settings.versions.map { VocabularyItem(it.id, it.name) }
                    val added = settings.versions.firstOrNull { it.name.equals(clean, ignoreCase = true) }
                    updateResolutionDialog {
                        it.copy(versions = items, selectedFixedVersionId = added?.id ?: it.selectedFixedVersionId)
                    }
                    // The board's own version list learns about it too, so the next
                    // drag-to-close and the editor both offer it without a refetch.
                    _stateFlow.value.boards[projectId]?.let { b ->
                        val now = _stateFlow.value
                        _stateFlow.value = now.copy(boards = now.boards + (projectId to b.copy(versions = items)))
                    }
                },
                onFailure = { t ->
                    _stateFlow.value = _stateFlow.value.copy(errorMessage = t.userMessage("Could not add that version."))
                },
            )
        }
    }

    /**
     * Rename a version from the resolution dialog's picker (LNL-162).
     *
     * Keeps whatever was already chosen: the selection is an id, and renaming does
     * not move it. So unlike [onResolutionVersionAdded] there is nothing to select
     * afterwards, and unlike [onResolutionVersionDeleted] nothing to release.
     */
    fun onResolutionVersionRenamed(versionId: Long, name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        val projectId = (_stateFlow.value.dialog as? ActiveDialog.ChooseResolution)?.projectId ?: return
        scope.launch {
            runCatching {
                storage.editVocabulary(
                    projectId,
                    VocabularyKind.VERSION,
                    versionId,
                    clean,
                    requiresResolution = false,
                    isDone = false,
                )
            }.fold(
                onSuccess = { settings ->
                    val items = settings.versions.map { VocabularyItem(it.id, it.name) }
                    updateResolutionDialog { it.copy(versions = items) }
                    _stateFlow.value.boards[projectId]?.let { b ->
                        val now = _stateFlow.value
                        _stateFlow.value = now.copy(boards = now.boards + (projectId to b.copy(versions = items)))
                    }
                },
                onFailure = { t ->
                    _stateFlow.value =
                        _stateFlow.value.copy(errorMessage = t.userMessage("Could not rename that version."))
                },
            )
        }
    }

    /** Delete a version from the resolution dialog's picker, after its confirmation. */
    fun onResolutionVersionDeleted(versionId: Long) {
        val projectId = (_stateFlow.value.dialog as? ActiveDialog.ChooseResolution)?.projectId ?: return
        scope.launch {
            runCatching { storage.deleteVocabulary(projectId, VocabularyKind.VERSION, versionId) }.fold(
                onSuccess = { settings ->
                    val items = settings.versions.map { VocabularyItem(it.id, it.name) }
                    updateResolutionDialog {
                        it.copy(
                            versions = items,
                            selectedFixedVersionId = it.selectedFixedVersionId?.takeIf { id -> id != versionId },
                        )
                    }
                    _stateFlow.value.boards[projectId]?.let { b ->
                        val now = _stateFlow.value
                        _stateFlow.value = now.copy(boards = now.boards + (projectId to b.copy(versions = items)))
                    }
                },
                onFailure = { t ->
                    _stateFlow.value =
                        _stateFlow.value.copy(errorMessage = t.userMessage("Could not delete that version."))
                },
            )
        }
    }

    /**
     * Confirm was pressed. Finish the drag the dialog was holding, with the chosen
     * resolution and fixed version. The move was never started, so there is nothing
     * to undo if the dialog was cancelled instead — see [onResolutionCancelled].
     */
    fun onResolutionConfirmed() {
        val dialog = _stateFlow.value.dialog as? ActiveDialog.ChooseResolution ?: return
        val resolutionId = dialog.selectedResolutionId ?: return
        _stateFlow.value = _stateFlow.value.copy(dialog = ActiveDialog.None)
        move(dialog.projectId, dialog.issueId, dialog.statusId, resolutionId, dialog.selectedFixedVersionId)
    }

    /** Re-emit the open resolution dialog through [transform]; a no-op if it is not the one showing. */
    private fun updateResolutionDialog(
        transform: (ActiveDialog.ChooseResolution) -> ActiveDialog.ChooseResolution,
    ) {
        val current = _stateFlow.value
        val dialog = current.dialog as? ActiveDialog.ChooseResolution ?: return
        _stateFlow.value = current.copy(dialog = transform(dialog))
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
    private fun move(
        projectId: Long,
        issueId: Long,
        statusId: Long,
        resolutionId: Long?,
        fixedVersionId: Long? = null,
    ) {
        val current = _stateFlow.value
        val board = current.boards[projectId] ?: return
        val issue = board.issues.firstOrNull { it.id == issueId } ?: return

        val previousStatusId = issue.statusId
        val previousResolutionId = issue.resolutionId
        // The resolution moves with the card, so the group header it lands under
        // is right immediately rather than after the next board fetch. The fixed
        // version rides along too when a close carried one, so the server and the
        // optimistic state agree without a refetch.
        _stateFlow.value = current.copy(
            boards = current.boards + (
                projectId to board.copy(
                    issues = board.issues.map {
                        if (it.id == issueId) it.copy(statusId = statusId, resolutionId = resolutionId) else it
                    },
                )
                ),
            errorMessage = null,
        )

        scope.launch {
            runCatching { storage.setIssueStatus(issueId, statusId, resolutionId, fixedVersionId) }
                .onFailure { t ->
                    println("MainScreen: move failed: ${t.message}")
                    // Put it back. Reading `board` out of the *current* state
                    // rather than closing over the old one, so a second drag
                    // that happened while this request was in flight is not
                    // silently undone as well.
                    val now = _stateFlow.value
                    val live = now.boards[projectId]
                    _stateFlow.value = now.copy(
                        boards = if (live == null) {
                            now.boards
                        } else {
                            now.boards + (
                                projectId to live.copy(
                                    issues = live.issues.map {
                                        if (it.id == issueId) {
                                            it.copy(
                                                statusId = previousStatusId,
                                                resolutionId = previousResolutionId,
                                            )
                                        } else {
                                            it
                                        }
                                    },
                                )
                                )
                        },
                        errorMessage = t.userMessage("Could not move that issue."),
                    )
                }
        }
    }

    /**
     * Drop [issueId] immediately before or after [targetId].
     *
     * The view says "here, relative to that card" rather than "at index 4",
     * because that is what a drop actually knows — an index would mean the view
     * counting positions in a list the view model built, and the two miscounting
     * differently the first time a group changed under a drag.
     *
     * Two drops in one, and which it is depends only on where it landed:
     *
     *  - **Within a group** — a pure reorder, the card's fields untouched.
     *  - **Across groups in the same column** — the card lands where the
     *    insertion line promised AND takes the destination group's priority,
     *    because in a column grouped by priority those are the same statement.
     *    This is LNL-40: the line was drawn for the whole column while the drop
     *    was checked against the group, so every drag past a header died
     *    silently. See [canReorderOnto], which is now the one predicate both the
     *    view and this method ask, and which still refuses a drop across
     *    RESOLUTIONS in a closing column.
     *
     * A drop onto a card in another COLUMN is a status change and never reaches
     * here — see [onIssueDragged].
     */
    fun onIssueReordered(projectId: Long, issueId: Long, targetId: Long, placeBefore: Boolean) {
        if (issueId == targetId) return
        val current = _stateFlow.value
        val board = current.boards[projectId] ?: return
        val moved = board.issues.firstOrNull { it.id == issueId } ?: return
        val target = board.issues.firstOrNull { it.id == targetId } ?: return
        if (!moved.canEdit) {
            println("MainScreen: reorder ignored; this issue is not editable by this user")
            return
        }
        if (!moved.canReorderOnto(target)) return

        // The card as it will be once it has landed: in a cross-group drop it
        // has already taken the destination's priority, which is what makes the
        // grouping below sort it into the right run.
        val landed = if (moved.priorityId == target.priorityId) {
            moved
        } else {
            moved.copy(priorityId = target.priorityId)
        }
        applyDrop(current, projectId, board, landed, errorMessage = "Could not reorder that issue.") { rest ->
            val at = rest.indexOfFirst { it.id == targetId }
            if (at < 0) null else if (placeBefore) at else at + 1
        }
    }

    /**
     * A card was dropped on a column itself rather than on one of its cards —
     * its blank area below the last card, or a group header.
     *
     * Into ANOTHER column that is a status change, exactly as before. Into the
     * card's OWN column it used to be nothing at all: the handler was
     * [onIssueDragged], which returns early when the status is unchanged, so
     * "put this at the bottom" — the most natural aim point there is — silently
     * did nothing. Part of LNL-40.
     *
     * Sends the card to the end of its own group rather than to the very bottom
     * of the column. The blank space belongs to no group, so there is no
     * destination to read off the pointer, and moving between groups is a
     * priority change this gesture has not asked for. The end of where it
     * already lives is the reading that promises nothing it cannot keep.
     */
    fun onIssueDroppedInColumn(projectId: Long, issueId: Long, statusId: Long) {
        val current = _stateFlow.value
        val board = current.boards[projectId] ?: return
        val moved = board.issues.firstOrNull { it.id == issueId } ?: return
        if (moved.statusId != statusId) {
            onIssueDragged(projectId, issueId, statusId)
            return
        }
        if (!moved.canEdit) {
            println("MainScreen: reorder ignored; this issue is not editable by this user")
            return
        }
        applyDrop(current, projectId, board, moved, errorMessage = "Could not reorder that issue.") { rest ->
            rest.size
        }
    }

    /**
     * The half of a drop that both entry points share: re-slot [landed] in the
     * board, optimistically, and tell the server the group's new order.
     *
     * @param landed the dragged card as it will be AFTER the drop — the caller
     *   has already applied any priority change, so the grouping here needs no
     *   special case for it.
     * @param indexIn where in the destination group (the group [landed] belongs
     *   to, that group with the dragged card removed) it should be inserted.
     *   Null abandons the drop.
     *
     * Rebuilds the flat list by removing the card and re-inserting it beside its
     * new neighbours, rather than permuting a slice in place as this used to: a
     * cross-group drop changes both groups' sizes, so there is no longer a slice
     * of fixed length to permute. Correct for the same-group case too, and
     * shorter — `board.issues` is ordered so that a group's members are
     * contiguous, so inserting next to a member of the destination group leaves
     * the list correctly grouped without re-sorting it.
     */
    private fun applyDrop(
        current: State,
        projectId: Long,
        board: BoardState,
        landed: IssueSummary,
        errorMessage: String,
        indexIn: (List<IssueSummary>) -> Int?,
    ) {
        val original = board.issues.firstOrNull { it.id == landed.id } ?: return
        // The destination group as it stands, the dragged card excluded — which
        // is what the insertion index is relative to.
        val destination = board.issues.filter { it.id != landed.id && it.sharesGroupWith(landed) }
        val at = indexIn(destination)?.coerceIn(0, destination.size) ?: return
        val ids = destination.map { it.id }.toMutableList().apply { add(at, landed.id) }

        // Dropped where it already was. When the priority is unchanged the
        // destination group IS the card's current group, so comparing the two
        // orders is the whole test; a changed priority is always a real move.
        if (original.priorityId == landed.priorityId &&
            ids == board.issues.filter { it.sharesGroupWith(original) }.map { it.id }
        ) {
            return
        }

        // Re-slot in the flat list. A group's members are contiguous in it (see
        // Issues.sq's ORDER BY), so landing next to a member of the destination
        // group leaves the list correctly grouped with no re-sorting.
        val next = board.issues.filterNot { it.id == landed.id }.toMutableList()
        val slot = when {
            at < destination.size -> next.indexOfFirst { it.id == destination[at].id }
            destination.isNotEmpty() -> next.indexOfFirst { it.id == destination.last().id } + 1
            // An empty destination means the card is alone in its group, which
            // the no-op check above has already returned on for every drop that
            // does not change priority — and one that does always has a target
            // sitting in the destination. Belt and braces.
            else -> next.size
        }
        next.add(slot.coerceIn(0, next.size), landed)
        _stateFlow.value = current.copy(
            boards = current.boards + (projectId to board.copy(issues = next)),
            errorMessage = null,
        )

        scope.launch {
            runCatching { storage.setIssueOrder(landed.id, ids, priorityId = landed.priorityId) }
                .onFailure { t ->
                    println("MainScreen: reorder failed: ${t.message}")
                    // Re-fetch rather than hand-rolling an undo. The optimistic
                    // edit above moved a card and may have changed its priority;
                    // reversing it exactly means remembering both AND that
                    // nothing else moved meanwhile. The board is one request —
                    // ask it.
                    _stateFlow.value = _stateFlow.value.copy(
                        errorMessage = t.userMessage(errorMessage),
                    )
                    refreshBoard(projectId)
                }
        }
    }

    /** Dismiss the error line. */
    fun onErrorDismissed() {
        _stateFlow.value = _stateFlow.value.copy(errorMessage = null)
    }

    companion object {
        /**
         * Show every issue, scheduled or not.
         *
         * Negative, so it cannot collide with a sprint id — those come from
         * `AUTOINCREMENT` and start at 1 — nor with [SCOPE_BACKLOG]. This is what
         * a project with no sprints sits on permanently, and the only value the
         * scope can hold when there is no control on screen to change it.
         */
        const val SCOPE_ALL: Long = -1L

        /**
         * Show only un-scheduled work — the issues whose `sprintId` is null.
         *
         * Zero, for [IssueBackingViewModel.NO_SPRINT_ID]'s reason: no sprint can
         * hold it. The backlog gets its own scope rather than being read off
         * "everything, minus the sprints", because during planning it is the
         * question being asked and not a leftover.
         */
        const val SCOPE_BACKLOG: Long = 0L

        /**
         * The scope picker's action rows.
         *
         * Actions live in the scope dropdown rather than in a control of their
         * own, because that is where you are standing when you want them — see
         * [ActiveDialog.NewSprint]. They are ids in the same `Long` space as the
         * scopes because that is what the dropdown deals in, and they are
         * negative and below [SCOPE_ALL] so no sprint id and no scope can ever
         * collide with one.
         *
         * [State.sprintScopeItems] is what decides which of them are offered; the
         * split between "this is a scope" and "this is an action" is
         * [isSprintAction], and it is the reason picking "New sprint…" does not
         * also rescope the board.
         */
        const val ACTION_NEW_SPRINT: Long = -2L
        const val ACTION_PLAN_SPRINT: Long = -3L
        const val ACTION_ACTIVATE_SPRINT: Long = -4L
        // -5L was ACTION_COMPLETE_SPRINT and is gone (LNL-196). Completing a sprint
        // rewrites what everybody's columns mean, and it was offered from a control that
        // reads as a view switch, within reach of everybody looking at the board rather
        // than of the people planning it. It is a per-row action in the Sprints section
        // now, beside the date it sets. The sentinel is deliberately left unreused.

        /** Whether a picked dropdown id is an action rather than a scope. */
        fun isSprintAction(id: Long): Boolean = id <= ACTION_NEW_SPRINT
    }
}

/**
 * Which scope a freshly loaded board should open on.
 *
 * The project's active sprint when there is one, so somebody with a sprint
 * running lands on their sprint rather than on a board that also holds next
 * quarter's plan. [MainScreenBackingViewModel.SCOPE_ALL] otherwise, which is
 * every board that has no sprints and every project between sprints.
 *
 * Checked against the list rather than trusted: the server clears
 * `activeSprintId` when that sprint is completed or deleted, but a board held
 * open through somebody else's write could still be carrying a stale one, and a
 * scope naming a sprint that is not there shows an empty board — which reads as
 * "no work" rather than as "wrong sprint".
 */
private fun BoardState.defaultScope(): Long {
    val active = activeSprintId ?: return MainScreenBackingViewModel.SCOPE_ALL
    return if (sprints.any { it.id == active }) active else MainScreenBackingViewModel.SCOPE_ALL
}

/**
 * Bring the open windows' title-bar facts up to date from a board that just
 * arrived, and leave every other project's windows exactly as they were.
 *
 * Called from all three places a board lands — the full [reload], a project
 * switch, and [MainScreenBackingViewModel.refreshBoard] — because all three are
 * moments when a window of *this* project may have gone stale and no other
 * project's has. Two things actually change here:
 *
 *  - a draft that has since been saved picks up its number, which is how a
 *    window titled "New issue" becomes "LMX-12" without anybody telling it to;
 *  - a renamed project's prefix reaches its own windows.
 *
 * What deliberately does NOT happen is closing a window whose issue is no longer
 * on the board. It is tempting — a deleted issue's window is showing something
 * that is gone — but the same condition is true of every draft, every time, and
 * a window that vanished mid-edit because a filter-shaped fact changed on the
 * server would be far worse than a stale title. Deleting an issue from its own
 * window already closes it, through `onIssueWindowClosed`.
 *
 * A null board (signed out, or no projects) reconciles nothing rather than
 * clearing anything: it names no project, so it has nothing to say about any
 * window's row.
 */
private fun List<OpenIssueWindow>.reconciledWith(board: BoardState?): List<OpenIssueWindow> {
    if (board == null) return this
    return map { open ->
        if (open.projectId != board.project.id) {
            open
        } else {
            open.copy(
                namePrefix = board.project.namePrefix,
                // Held, not cleared, when the row is absent: a draft has no number
                // and never had one, and an issue that has left this board should
                // keep the ticket its window has been showing all along.
                number = board.issues.firstOrNull { it.id == open.issueId }?.number ?: open.number,
            )
        }
    }
}
