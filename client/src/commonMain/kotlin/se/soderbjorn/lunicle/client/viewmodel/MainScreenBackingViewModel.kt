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

    /**
     * "You have unsaved changes" — the guard on a project switch.
     *
     * LNL-84 makes switching project reset the tabs it leaves: open issue windows
     * close, the Discussion tab reloads. When an editor is mid-edit that reset
     * would throw the work away, so the switch stops here first and offers to
     * abort. Carries nothing — the project it was heading for is held on
     * [pendingSwitchProjectId], not in the dialog, because the dialog is a yes/no
     * and the destination is the view model's to remember. [confirmProjectSwitch]
     * and [cancelProjectSwitch] are the two answers.
     */
    data object ConfirmProjectSwitch : ActiveDialog

    /** The project dialog, creating. */
    data object NewProject : ActiveDialog

    /**
     * The instance settings dialog: the account directory.
     *
     * Carries nothing, unlike [EditProject]. It is not about a project, and the
     * one thing it would otherwise carry — "is this caller an admin" — is not an
     * argument to it: the route it opens onto refuses a non-admin outright, so a
     * dialog opened by one would render its own 403. See
     * [MainScreenBackingViewModel.State.canOpenAdminSettings], which is why that
     * does not happen, and AdminRoutes, which is why it would not matter if it did.
     */
    data object AdminSettings : ActiveDialog

    /**
     * The project dialog, editing an existing project.
     *
     * @property canConfigure whether this caller is an admin here — decides
     *   whether the dialog shows the full settings form or, for a non-admin, only
     *   the notification toggle the cog now opens for everyone. An affordance,
     *   carried from the board's `canMutateProject`; the server re-derives it and
     *   the settings response it sends back confirms it. See
     *   EditProjectBackingViewModel.
     */
    /**
     * @property canConfigure whether the caller administers this project — the
     *   vocabularies, the sprints and the privileges.
     * @property canConfigureIdentity whether they may also rename it, change its
     *   prefix or visibility, or delete it. Narrower: a PROJECT administrator
     *   gets the first and not the second, so the two cannot be one flag. See
     *   ProjectPermissionsView.canMutateProjectIdentity.
     */
    data class EditProject(
        val project: ProjectSummary,
        val canConfigure: Boolean,
        val canConfigureIdentity: Boolean,
    ) : ActiveDialog

    /**
     * The statistics dialog: how much has been happening in this project.
     *
     * Carries the project rather than just its id, so the dialog can title itself
     * without a second lookup — the same reason [EditProject] does.
     *
     * Unlike [EditProject] it carries no permission flag, because there is no
     * narrowed half to decide between: the counts are the same for everybody who
     * can see the project at all. What an admin has extra is the *configuration*
     * behind them, and that lives in the settings dialog rather than here.
     */
    data class Statistics(val project: ProjectSummary) : ActiveDialog

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

    /**
     * "This sprint is over — where does the unfinished work go?"
     *
     * The question has to be asked rather than defaulted, because "roll it into
     * the next sprint" and "put it back in the backlog" are genuinely different
     * intentions and getting it wrong is tedious to undo — the issues are spread
     * across two places and nothing records which ones moved.
     *
     * @property openSprints where the work could go, this one excluded. Empty is
     *   normal and fine: the dialog then offers only the backlog.
     */
    data class CompleteSprint(
        val projectId: Long,
        val sprintId: Long,
        val sprintName: String,
        val unfinishedCount: Int,
        val openSprints: List<SprintItem>,
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
     * The app-wide register of open editors. Read on a project switch to decide
     * whether unsaved work is at stake ([onProjectSelected]), and closed out when
     * the switch goes ahead ([performSwitch]). Defaults to a private instance so
     * callers and tests that do not care about the switch dialog need not supply
     * one; main.kt threads the shared instance it also gives the editors. LNL-84.
     */
    private val editorRegistry: EditorDirtyRegistry = EditorDirtyRegistry(),
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
     * The project a switch is heading for while the unsaved-changes dialog is up.
     *
     * Set when [onProjectSelected] finds dirty editors and stops to ask; spent by
     * [confirmProjectSwitch] or cleared by [cancelProjectSwitch]. Held here rather
     * than on the dialog because the picker is not the source of truth mid-question
     * — [selectedProjectId] is not moved until the switch actually goes ahead, so
     * the picker keeps showing the current project until then. LNL-84.
     */
    private var pendingSwitchProjectId: Long? = null

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
         * The board's live text filter, or empty for "show everything".
         *
         * A view concern in the shallowest sense — it changes nothing on the
         * server and survives no reload worth speaking of — but a filter is a
         * decision about which cards the board shows, and that is [columns]'
         * business, not the view's. Kept here so the one place that builds
         * columns is the one place that hides them, and so switching project can
         * clear it (see [onProjectSelected]).
         */
        val filterQuery: String = "",
        /**
         * Which sprint the board is scoped to — a sprint id, or one of the two
         * sentinels below.
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
         * Set to the project's active sprint when a board loads, so somebody with
         * a sprint running opens onto their sprint. Like [filterQuery] this is a
         * decision about which cards show, so it lives beside it and is [columns]'
         * business — and like it, it is cleared on project switch, because a
         * sprint id from the board being left names nothing on the one arriving.
         */
        val sprintScope: Long = SCOPE_ALL,
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
        /** The project in the top bar, or null. */
        val currentProject: ProjectSummary? get() = board?.project

        /**
         * Whether the sprint scope control renders at all.
         *
         * The whole of what keeps a pure-kanban board untouched: a project with no
         * sprints has an empty list here, so there is no dropdown, no extra
         * control beside the filter box, and nothing to configure off. Presence of
         * sprints is the feature flag — see Sprints.sq.
         */
        val showsSprintScope: Boolean get() = board?.sprints.orEmpty().isNotEmpty()

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
         * Completing and activating are absent on a finished sprint rather than
         * disabled: a greyed-out "Complete" on something already complete is a
         * control explaining a state the row above it already showed.
         */
        val sprintScopeItems: List<ScopeItem> get() {
            val scopes = listOf(
                ScopeItem(SCOPE_ALL, "All issues"),
                ScopeItem(SCOPE_BACKLOG, "Backlog"),
            ) + scopeSprints.map { ScopeItem(it.id, it.name) }
            if (!canEditCurrentProject) return scopes

            val scoped = scopeSprints.firstOrNull { it.id == sprintScope }
            val actions = buildList {
                add(ScopeItem(ACTION_NEW_SPRINT, "New sprint…"))
                if (scoped != null && scoped.isOpen) {
                    add(ScopeItem(ACTION_PLAN_SPRINT, "Plan ${scoped.name}…"))
                    if (board?.activeSprintId != scoped.id) {
                        add(ScopeItem(ACTION_ACTIVATE_SPRINT, "Make ${scoped.name} active"))
                    }
                    add(ScopeItem(ACTION_COMPLETE_SPRINT, "Complete ${scoped.name}…"))
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
         * The status ids this user has hidden on the board on screen (LNL-100).
         *
         * The current project's slice of [projectPrefs], as a set because the board
         * only ever asks "is this column hidden?" — membership, never order. Empty
         * for a board with no project, for a project this user has hidden nothing
         * on, and for a signed-out visitor, all of which draw every column.
         *
         * A set of ids and not of columns on purpose: an id in here that names no
         * current status — a column deleted since it was hidden — simply matches
         * nothing in [columns] and falls away, so a stale preference never has to be
         * cleaned up to stop mattering.
         */
        val hiddenColumnIds: Set<Long> get() =
            currentProject?.id?.let { projectPrefs[it]?.hiddenColumnIds?.toSet() }.orEmpty()

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
         */
        fun issueWindowTitle(issueId: Long): String {
            val open = openIssues.firstOrNull { it.issueId == issueId } ?: return "Issue"
            // Hiding issue numbers (LNL-105) reaches the window's pane label too: it
            // shows the issue's own title in place of its key — the key is what is
            // being hidden. A draft has no title on the board yet, so it keeps "New
            // issue"; a saved issue whose title has not arrived falls back to a word.
            if (hideIssueNumbers) {
                val title = board?.issues?.firstOrNull { it.id == issueId }?.title?.takeIf { it.isNotBlank() }
                return title ?: if (open.ticket == null) "New issue" else "Issue"
            }
            return open.ticket ?: "New issue"
        }

        /**
         * Whether the caller may *configure* the current project — admin only; an
         * affordance. Decides the settings form, not whether the cog appears.
         */
        val canEditCurrentProject: Boolean
            get() = board?.permissions?.canMutateProject == true

        /**
         * Whether the caller may rename this project or delete it, as opposed to
         * merely administering it. A project administrator may not; see
         * ProjectPermissionsView.canMutateProjectIdentity for why those are two
         * questions and not one.
         */
        val canRenameCurrentProject: Boolean
            get() = board?.permissions?.canMutateProjectIdentity == true

        /**
         * Whether to offer the cogwheel at all.
         *
         * Any signed-in user with a board in front of them: the dialog opens for
         * everyone now, showing an admin the settings and everyone else just the
         * new-issue notification toggle. A signed-out visitor gets no cog — they
         * have nothing to set and no address to notify.
         */
        val canOpenProjectSettings: Boolean
            get() = board != null && isSignedIn

        /**
         * Whether to offer the statistics button beside the cogwheel.
         *
         * A board is the whole condition — no sign-in required, unlike
         * [canOpenProjectSettings]. Somebody reading a public project is reading
         * the issues these numbers count, so there is nothing here they are not
         * already looking at. The one exception is the commit count, which is a
         * fact about a repository rather than about the board; that is the
         * accepted trade recorded in StatisticsRoutes, and it is why the token is
         * read-only and scoped to a single repository.
         */
        val canOpenStatistics: Boolean
            get() = board != null

        /**
         * Whether to offer the instance settings button beside the cogwheel.
         *
         * Admins only, and unlike [canOpenProjectSettings] this needs no board:
         * the accounts on a deployment exist before its first project does. An
         * affordance — the route refuses a non-admin regardless.
         */
        val canOpenAdminSettings: Boolean
            get() = isSysAdmin

        /** Whether to offer "New issue". */
        val canCreateIssue: Boolean get() = board?.permissions?.canCreateIssue == true

        /**
         * What to say when there is no board, or null when there is one.
         *
         * Several different nothings, and they mean different things to the
         * person reading them: still loading, nothing chosen, or nothing to show
         * — and that last one has a different next step depending on who is
         * looking. Each branch names the one action its reader can actually take,
         * which is the whole job of an empty state; a message that points at a
         * control the reader does not have is worse than no message, because it
         * reads as the app being broken.
         */
        val emptyMessage: String? get() = when {
            board != null -> null
            !isLoaded -> null
            // The "+" in the top bar, not the picker: "New project…" left the
            // switcher for the shell's add menu (see BoardWindow's preamble), and
            // the picker will not even open while it has no rows. Pointing a brand
            // new admin at it sent them to the one control that cannot do this.
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
            else -> "Pick a project to see its issues."
        }

        /**
         * Render a card's line — "LMX-123: Unable to remove user", or just the
         * title when this user has hidden issue numbers on this project (LNL-105).
         */
        fun cardLabel(issue: IssueSummary): String =
            if (hideIssueNumbers) issue.title
            else "${currentProject?.namePrefix ?: "?"}-${issue.number}: ${issue.title}"

        /**
         * The key of this card's epic — "LMX-98" — or null when it has no parent, or
         * when the parent's number did not travel (LNL-154). The web draws it as the
         * card's "↳ LMX-98" back-reference and opens the parent by
         * [IssueSummary.parentId]. Built from the current project's prefix because a
         * parent shares its child's project, exactly as [cardLabel] forms this card's
         * own key — so the two keys read the same way. Deliberately over
         * [IssueSummary.parentNumber] (the authoritative number the server sent
         * alongside [IssueSummary.parentId]) rather than a scan of [board] for the
         * parent id: the parent may be in a hidden column or scoped out and so absent
         * from the list here, which is the whole reason the number rides on the wire.
         */
        fun parentKey(issue: IssueSummary): String? {
            val number = issue.parentNumber ?: return null
            val prefix = currentProject?.namePrefix ?: return null
            return "$prefix-$number"
        }

        /**
         * The full text a card is searched by — always the number and the title,
         * regardless of [hideIssueNumbers], so the filter box still finds a card by
         * its ticket key even when the key is not drawn. See [columns]' filter.
         */
        fun cardSearchText(issue: IssueSummary): String =
            "${currentProject?.namePrefix ?: "?"}-${issue.number}: ${issue.title}"

        /**
         * Whether this user has hidden the issue number on this project's board and
         * issue detail (LNL-105). A per-user, per-project view choice, read from the
         * same [projectPrefs] blob the hidden columns live in.
         */
        val hideIssueNumbers: Boolean get() =
            currentProject?.id?.let { projectPrefs[it]?.hideIssueNumbers } ?: false
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
     * @param preferredProjectId the address bar's `?projectId=`, if any — the
     *   project this tab was last looking at, so a reload comes back to it. Seeded
     *   straight into [selectedProjectId] because it *is* a selection: the URL is
     *   written from the picker, so reading it back is restoring the user's own
     *   choice rather than honouring somebody else's request. Being an id and not
     *   a name is the point — the project can be renamed and the link still works.
     *   It loses to a deep link, and to the picker the moment it is touched; see
     *   `StorageRepository.resolve`.
     */
    fun start(preferredProjectName: String?, ticket: Ticket? = null, preferredProjectId: Long? = null) {
        preferredName = preferredProjectName?.takeIf { it.isNotBlank() }
        preferredTicket = ticket
        selectedProjectId = preferredProjectId
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
        reload()
    }

    /** Re-fetch everything. */
    fun reload() {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.load(preferredName, selectedProjectId, preferredTicket) }
            // The caller's own view preferences, read on the same trigger as the
            // board and for the same reason: they belong to the effective user, so
            // an identity change — sign-in, sign-out, impersonation — is exactly
            // when they must be re-read, and reload() is what fires on it. A failed
            // read degrades to "no preferences", which is the default board rather
            // than a boot failure; the board itself is what a hard failure below
            // reports on. Read whether or not the board loaded, so signed-in
            // preferences are ready the moment a board does arrive.
            val prefs = runCatching { UserProjectPreferences.decode(storage.uiSettings().settings[UiSettingKeys.PROJECT_PREFS]) }
                .getOrDefault(emptyMap())
            _stateFlow.value = result.fold(
                onSuccess = { loaded ->
                    selectedProjectId = loaded.board?.project?.id
                    val previous = _stateFlow.value
                    // Open what the link asked for, if it is there — a window,
                    // as if the user had clicked the card. Consumed either
                    // way: a ticket that names an issue this caller cannot see
                    // resolves to nothing, and retrying it on every refresh
                    // would never start working.
                    val linkedWindow = deepLinkedWindow(loaded.board)
                    val linked = linkedWindow?.issueId
                    previous.copy(
                        isLoaded = true,
                        isBusy = false,
                        projects = loaded.projects.projects,
                        canCreateProject = loaded.projects.canCreateProject,
                        board = loaded.board,
                        sprintScope = loaded.board?.defaultScope() ?: SCOPE_ALL,
                        // Replaced wholesale, like the board itself: these are the
                        // effective user's preferences, and a reload is where a
                        // different user's arrive over the last one's. Merging would
                        // leave one account holding another's hidden columns.
                        projectPrefs = prefs,
                        // knownIdentity was just set by onSessionChanged (or is
                        // null for a signed-out visitor). null means signed out.
                        isSignedIn = knownIdentity != null,
                        errorMessage = null,
                        // Reconcile first, THEN add the deep link's window, so a
                        // link to an issue whose window is already open re-focuses
                        // it rather than opening a second one on the same issue.
                        openIssues = previous.openIssues.reconciledWith(loaded.board).let { open ->
                            when {
                                linkedWindow == null || open.any { it.issueId == linked } -> open
                                else -> open + linkedWindow
                            }
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
    private fun deepLinkedWindow(board: BoardState?): OpenIssueWindow? {
        val ticket = preferredTicket ?: return null
        preferredTicket = null
        if (board == null) return null
        if (!board.project.namePrefix.equals(ticket.prefix, ignoreCase = true)) return null
        val issue = board.issues.firstOrNull { it.number == ticket.number } ?: return null
        return OpenIssueWindow(
            issueId = issue.id,
            projectId = board.project.id,
            namePrefix = board.project.namePrefix,
            number = issue.number,
        )
    }

    /** The board currently on screen, or null. */
    private fun board(): BoardState? = _stateFlow.value.board

    /** Refresh only the current board, leaving the picker alone. */
    fun refreshBoard() {
        val projectId = selectedProjectId ?: return
        scope.launch {
            runCatching { storage.board(projectId) }
                // The scope is deliberately NOT reset to the board's default here,
                // unlike the two load paths: a refresh is the same board arriving
                // again, and yanking somebody back to the active sprint because a
                // card moved would undo a choice they made on purpose. It is only
                // re-derived when the board being looked at genuinely changes.
                .onSuccess {
                    _stateFlow.value = _stateFlow.value.copy(
                        board = it,
                        // A refresh is how a just-saved draft stops being one: the
                        // row is on the board now, so its window can finally be
                        // titled with the number it was given.
                        openIssues = _stateFlow.value.openIssues.reconciledWith(it),
                    )
                }
                .onFailure { println("MainScreen: board refresh failed: ${it.message}") }
        }
    }

    /**
     * The filter box changed. Live: every keystroke narrows the board.
     *
     * No trip to the server and no request to fail — the board is already all
     * here, and hiding cards is a pure re-derivation of [State.columns]. A
     * repeated value (the box re-emitting the same text) still re-emits state,
     * which is harmless: nothing downstream of an unchanged query changes.
     */
    fun onFilterChanged(query: String) {
        if (_stateFlow.value.filterQuery == query) return
        _stateFlow.value = _stateFlow.value.copy(filterQuery = query)
    }

    /**
     * The user picked a sprint scope. Purely local, like the filter box.
     *
     * No request: every issue the board could show is already here, and scoping
     * is a re-derivation of [State.columns]. Reloading to narrow a list the client
     * is holding would be a round-trip to learn something it already knows —
     * and would lose the cards while it waited.
     */
    fun onSprintScopeSelected(scope: Long) {
        // The action rows share this dropdown, so this is also where they arrive.
        // Split here rather than in the view, so the view stays a renderer: which
        // ids mean "act" rather than "scope" is a fact about the model.
        if (isSprintAction(scope)) {
            onSprintActionSelected(scope)
            return
        }
        if (_stateFlow.value.sprintScope == scope) return
        _stateFlow.value = _stateFlow.value.copy(sprintScope = scope)
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
    fun onHideColumn(statusId: Long) = setColumnHidden(statusId, hidden = true)

    /**
     * Restore a hidden column — a click on its collapsed box in the rail
     * (LNL-100). The inverse of [onHideColumn], through the same path.
     */
    fun onShowColumn(statusId: Long) = setColumnHidden(statusId, hidden = false)

    /**
     * Fold one column's hidden-ness into [State.projectPrefs] and persist.
     *
     * Keyed on the project on screen; a call with no board does nothing, since a
     * hidden column is a fact about a board and there is none to state it about.
     * A no-op change — hiding what is already hidden, showing what is already shown
     * — returns without touching state or the server, so a stray double-click costs
     * neither a repaint nor a write.
     *
     * A project whose last hidden column is shown again drops out of the map
     * entirely rather than lingering as an empty record: "this user has no
     * preferences here" is the absence of an entry, not an entry that happens to
     * hide nothing, and [UserProjectPreferences.encode] prunes on the way out to
     * keep the two the same fact on the wire.
     */
    private fun setColumnHidden(statusId: Long, hidden: Boolean) {
        val state = _stateFlow.value
        val projectId = state.currentProject?.id ?: return
        val current = state.projectPrefs[projectId]?.hiddenColumnIds.orEmpty()
        val next = when {
            hidden && statusId !in current -> current + statusId
            !hidden && statusId in current -> current - statusId
            else -> return
        }
        // copy() rather than a fresh record, so a sibling preference on the same
        // project — the hide-issue-numbers choice (LNL-105) — is carried across a
        // column hide/show rather than reset to its default.
        val record = (state.projectPrefs[projectId] ?: UserProjectPrefs()).copy(hiddenColumnIds = next)
        val nextPrefs = state.projectPrefs.toMutableMap().apply {
            if (record == UserProjectPrefs()) remove(projectId) else put(projectId, record)
        }
        _stateFlow.value = state.copy(projectPrefs = nextPrefs)
        persistProjectPrefs(nextPrefs)
    }

    /** Whether this user is hiding issue numbers on [projectId]'s board (LNL-105). */
    fun isHidingIssueNumbers(projectId: Long): Boolean =
        _stateFlow.value.projectPrefs[projectId]?.hideIssueNumbers ?: false

    /**
     * Turn the issue number off (or back on) for one project, per user (LNL-105).
     *
     * The sibling of [setColumnHidden], through the same [projectPrefs] blob and the
     * same prune-to-absence rule, but keyed on the [projectId] the settings dialog
     * names rather than the board's current project — the dialog can be open on a
     * project the board is not currently showing. A no-op change touches nothing.
     */
    fun setIssueNumbersHidden(projectId: Long, hidden: Boolean) {
        val state = _stateFlow.value
        val existing = state.projectPrefs[projectId] ?: UserProjectPrefs()
        if (existing.hideIssueNumbers == hidden) return
        val record = existing.copy(hideIssueNumbers = hidden)
        val nextPrefs = state.projectPrefs.toMutableMap().apply {
            if (record == UserProjectPrefs()) remove(projectId) else put(projectId, record)
        }
        _stateFlow.value = state.copy(projectPrefs = nextPrefs)
        persistProjectPrefs(nextPrefs)
    }

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

    /**
     * The user picked a project.
     *
     * LNL-84 makes this a top-level move that redraws everything below it: the
     * board reloads and the tabs it leaves reset (see [performSwitch]). Because
     * that reset throws away open windows, a switch first checks the app-wide
     * [editorRegistry] — if anything is being edited unsaved, it stops to ask
     * ([ActiveDialog.ConfirmProjectSwitch]) rather than discarding it silently.
     * With nothing dirty there is nothing to lose, so it switches at once.
     */
    fun onProjectSelected(id: Long) {
        if (selectedProjectId == id) return
        if (editorRegistry.hasDirtyEditors) {
            pendingSwitchProjectId = id
            _stateFlow.value = _stateFlow.value.copy(dialog = ActiveDialog.ConfirmProjectSwitch)
            return
        }
        performSwitch(id)
    }

    /**
     * The unsaved-changes dialog's "Discard changes & switch" was pressed.
     *
     * The destination was parked on [pendingSwitchProjectId] while the question was
     * up; spend it now and go through with the switch, which discards the dirty
     * editors as part of the reset.
     */
    fun confirmProjectSwitch() {
        val id = pendingSwitchProjectId ?: return
        pendingSwitchProjectId = null
        _stateFlow.value = _stateFlow.value.copy(dialog = ActiveDialog.None)
        performSwitch(id)
    }

    /**
     * The unsaved-changes dialog's "Keep editing" was pressed — abort the switch.
     *
     * [selectedProjectId] was never moved, so the picker is already showing the
     * project we are staying on; there is nothing to put back. Just drop the
     * pending destination and close the dialog.
     */
    fun cancelProjectSwitch() {
        pendingSwitchProjectId = null
        _stateFlow.value = _stateFlow.value.copy(dialog = ActiveDialog.None)
    }

    /**
     * Actually switch to [id]: reset the tabs being left, then load the new board.
     *
     * LNL-84's reset-on-switch. The open editors are closed through the registry
     * ([EditorDirtyRegistry.closeAllForSwitch]) rather than by clearing state here,
     * which is what keeps a draft's deletion safe: each issue window and composer
     * runs its own close in its own scope, deleting the row it owns before the
     * window is disposed — force-clearing [openIssues] would cancel those scopes
     * mid-delete and orphan the rows. The windows drop out of [openIssues]
     * themselves as they finish, so it is left untouched here; the Discussion tab
     * resets on its own when the board's new project reaches ForumBackingViewModel
     * (see main.kt's board collector). Messages is instance-wide and deliberately
     * unaffected.
     *
     * The filter goes — it belonged to the board being left, and a search matching
     * three cards there would hide most of a different project's board. Focus goes
     * to the board (null), since every window it could name is closing.
     */
    private fun performSwitch(id: Long) {
        selectedProjectId = id
        // The embed's preference and any deep link have been overridden by a
        // deliberate choice, and must not win the next reload. The ticket goes too:
        // someone who picked a different project is not still asking for an issue
        // in the old one.
        preferredName = null
        preferredTicket = null
        editorRegistry.closeAllForSwitch()
        _stateFlow.value = _stateFlow.value.copy(
            isBusy = true,
            errorMessage = null,
            filterQuery = "",
            focusedIssueId = null,
        )
        scope.launch {
            val result = runCatching { storage.board(id) }
            _stateFlow.value = result.fold(
                onSuccess = {
                    // No reconcile of [openIssues] here, unlike a background reload:
                    // the switch is closing every window through the registry, so the
                    // list empties itself rather than being rewritten against the new
                    // board. Reconciling would rebuild windows against a project they
                    // do not belong to.
                    _stateFlow.value.copy(
                        board = it,
                        isBusy = false,
                        errorMessage = null,
                        sprintScope = it.defaultScope(),
                    )
                },
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
        val state = _stateFlow.value
        val project = state.currentProject ?: return
        // Opens for any signed-in user now; canConfigure is the admin affordance
        // that decides whether they get the settings form or only the toggle.
        _stateFlow.value = state.copy(
            dialog = ActiveDialog.EditProject(
                project,
                canConfigure = state.canEditCurrentProject,
                canConfigureIdentity = state.canRenameCurrentProject,
            ),
        )
    }

    /**
     * The statistics button was tapped.
     *
     * Guarded on the same affordance the button is shown by, for
     * [onAdminSettingsTapped]'s reason: a click that lands after the board has
     * gone opens nothing rather than a dialog with no project to count.
     */
    fun onStatisticsTapped() {
        val state = _stateFlow.value
        val project = state.currentProject ?: return
        if (!state.canOpenStatistics) return
        _stateFlow.value = state.copy(dialog = ActiveDialog.Statistics(project))
    }

    /**
     * The instance settings button was tapped.
     *
     * Guarded on the same affordance the button is shown by, so a stale click —
     * the session changing between render and mouseup — opens nothing rather than
     * a dialog that immediately renders a 403.
     */
    fun onAdminSettingsTapped() {
        val state = _stateFlow.value
        if (!state.canOpenAdminSettings) return
        _stateFlow.value = state.copy(dialog = ActiveDialog.AdminSettings)
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
        // Only ever from the board on screen: a card can only be clicked where it
        // is drawn. That is what makes the row below describable at all — the
        // prefix and number come from the board this issue is on, once, and are
        // then the window's own.
        val board = current.board ?: return
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
    fun onNewIssueTapped(initialStatusId: Long? = null) {
        val projectId = selectedProjectId ?: return
        val namePrefix = _stateFlow.value.currentProject?.namePrefix ?: return
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
                        // was numbered. The project is read from before the
                        // request, not after — a switch during the round-trip
                        // must not file this draft under the project it landed in.
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
    fun onSprintActionSelected(action: Long) {
        val current = _stateFlow.value
        val board = current.board ?: return
        val scoped = board.sprints.firstOrNull { it.id == current.sprintScope }
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
                activate(sprint.id)
            }

            ACTION_COMPLETE_SPRINT -> {
                val sprint = scoped ?: return
                // Counted here rather than in the dialog, because "unfinished"
                // means "not in a column that requires a resolution" and the
                // status list is right here. The dialog gets a number, not a rule.
                val closing = board.statuses.filter { it.requiresResolution }.map { it.id }.toSet()
                _stateFlow.value = current.copy(
                    dialog = ActiveDialog.CompleteSprint(
                        projectId = board.project.id,
                        sprintId = sprint.id,
                        sprintName = sprint.name,
                        unfinishedCount = board.issues
                            .count { it.sprintId == sprint.id && it.statusId !in closing },
                        openSprints = board.sprints.filter { it.isOpen && it.id != sprint.id },
                    ),
                )
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
                    refreshBoard()
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
    fun sprintDestinationsFor(issue: IssueSummary): List<ScopeItem> {
        val board = board() ?: return emptyList()
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
    fun onIssueSprintChosen(issueId: Long, destination: Long) {
        val sprintId = destination.takeIf { it != SCOPE_BACKLOG }
        _stateFlow.value = _stateFlow.value.copy(isBusy = true)
        scope.launch {
            runCatching { storage.setIssueSprint(issueId, sprintId) }
                .onSuccess {
                    _stateFlow.value = _stateFlow.value.copy(isBusy = false, errorMessage = null)
                    refreshBoard()
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
        replaceBoard("Could not save that plan.") {
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

    /** The completion dialog was answered. */
    fun onSprintCompleted(projectId: Long, sprintId: Long, moveUnfinishedTo: Long?) {
        _stateFlow.value = _stateFlow.value.copy(dialog = ActiveDialog.None, isBusy = true)
        replaceBoard("Could not complete that sprint.") {
            storage.completeSprint(projectId, sprintId, moveUnfinishedTo)
        }
    }

    private fun activate(sprintId: Long) {
        val projectId = selectedProjectId ?: return
        _stateFlow.value = _stateFlow.value.copy(isBusy = true)
        // Scoped to the sprint just activated rather than to whatever was on
        // screen: activating IS the act of saying "this is what we are working
        // on", so landing anywhere else would be answering a different question.
        replaceBoard("Could not activate that sprint.", preferredScope = sprintId) {
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
        failureMessage: String,
        preferredScope: Long? = null,
        write: suspend () -> BoardState,
    ) {
        scope.launch {
            runCatching { write() }
                .onSuccess { board ->
                    val current = _stateFlow.value
                    _stateFlow.value = current.copy(
                        board = board,
                        isBusy = false,
                        errorMessage = null,
                        sprintScope = current.scopeAfter(board, preferredScope ?: current.sprintScope),
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
                    versions = board.versions,
                    requireFixedVersion = board.project.requireFixedVersionOnResolve,
                    canManageVersions = board.permissions.canMutateProject,
                ),
            )
            return
        }

        move(issueId, statusId, resolutionId = null)
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
        if (_stateFlow.value.dialog !is ActiveDialog.ChooseResolution) return
        val projectId = _stateFlow.value.board?.project?.id ?: return
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
                    _stateFlow.value.board?.let { b ->
                        _stateFlow.value = _stateFlow.value.copy(board = b.copy(versions = items))
                    }
                },
                onFailure = { t ->
                    _stateFlow.value = _stateFlow.value.copy(errorMessage = t.userMessage("Could not add that version."))
                },
            )
        }
    }

    /** Delete a version from the resolution dialog's picker, after its confirmation. */
    fun onResolutionVersionDeleted(versionId: Long) {
        val projectId = _stateFlow.value.board?.project?.id ?: return
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
                    _stateFlow.value.board?.let { b ->
                        _stateFlow.value = _stateFlow.value.copy(board = b.copy(versions = items))
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
        move(dialog.issueId, dialog.statusId, resolutionId, dialog.selectedFixedVersionId)
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
    private fun move(issueId: Long, statusId: Long, resolutionId: Long?, fixedVersionId: Long? = null) {
        val current = _stateFlow.value
        val board = current.board ?: return
        val issue = board.issues.firstOrNull { it.id == issueId } ?: return

        val previousStatusId = issue.statusId
        val previousResolutionId = issue.resolutionId
        // The resolution moves with the card, so the group header it lands under
        // is right immediately rather than after the next board fetch. The fixed
        // version rides along too when a close carried one, so the server and the
        // optimistic state agree without a refetch.
        _stateFlow.value = current.copy(
            board = board.copy(
                issues = board.issues.map {
                    if (it.id == issueId) it.copy(statusId = statusId, resolutionId = resolutionId) else it
                },
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
        if (!moved.canReorderOnto(target)) return

        // The card as it will be once it has landed: in a cross-group drop it
        // has already taken the destination's priority, which is what makes the
        // grouping below sort it into the right run.
        val landed = if (moved.priorityId == target.priorityId) {
            moved
        } else {
            moved.copy(priorityId = target.priorityId)
        }
        applyDrop(current, board, landed, errorMessage = "Could not reorder that issue.") { rest ->
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
    fun onIssueDroppedInColumn(issueId: Long, statusId: Long) {
        val current = _stateFlow.value
        val board = current.board ?: return
        val moved = board.issues.firstOrNull { it.id == issueId } ?: return
        if (moved.statusId != statusId) {
            onIssueDragged(issueId, statusId)
            return
        }
        if (!moved.canEdit) {
            println("MainScreen: reorder ignored; this issue is not editable by this user")
            return
        }
        applyDrop(current, board, moved, errorMessage = "Could not reorder that issue.") { rest ->
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
        _stateFlow.value = current.copy(board = board.copy(issues = next), errorMessage = null)

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
                    refreshBoard()
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
        const val ACTION_COMPLETE_SPRINT: Long = -5L

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
