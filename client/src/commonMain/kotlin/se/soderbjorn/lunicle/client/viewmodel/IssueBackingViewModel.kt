/**
 * Backing view-model for an issue window: read, edit, comment, delete.
 *
 * The draft contract is the thing to understand here, because it is why this
 * screen is not a plain form:
 *
 *   "New issue" creates the row on the server **before** the window opens (see
 *   [MainScreenBackingViewModel.onNewIssueTapped]). It has to: the editor can
 *   upload a file, an attachment must have an owner, and the schema's
 *   `CHECK` makes an ownerless attachment unrepresentable rather than merely
 *   unlikely. So there is always a real issue id, and "new" is a *flag on an
 *   existing row* ([State.isDraft]) rather than the absence of one.
 *
 *   The consequence: **discarding a draft deletes the issue.** Not "discards
 *   changes" — deletes. And closing the tab instead leaves the row behind, which
 *   is exactly what `is_draft` covers: it stays invisible on every board, and
 *   the startup sweep collects the files behind it.
 *
 * There is no Cancel button any more. The window's close control is the way
 * out, and closing with unsaved edits asks Save / Discard / Keep editing —
 * see [onCloseRequested] and [State.confirmingClose]. The same question guards
 * the Edit button's toggle *back* to read mode, because both gestures would
 * otherwise silently drop typed text.
 *
 * @see StorageRepository
 * @see CommentBackingViewModel
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.soderbjorn.lunicle.client.formatTimestamp
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.client.attachmentMarkdown
import se.soderbjorn.lunicle.clientserver.tooLargeMessage
import se.soderbjorn.lunicle.client.userMessage
import se.soderbjorn.lunicle.clientserver.BoardState
import se.soderbjorn.lunicle.clientserver.CommentView
import se.soderbjorn.lunicle.clientserver.IssueEventKind
import se.soderbjorn.lunicle.clientserver.IssueEventView
import se.soderbjorn.lunicle.clientserver.IssueDetail
import se.soderbjorn.lunicle.clientserver.IssueRef
import se.soderbjorn.lunicle.clientserver.SprintItem
import se.soderbjorn.lunicle.clientserver.StatusItem
import se.soderbjorn.lunicle.clientserver.UserOption
import se.soderbjorn.lunicle.clientserver.VocabularyItem
import se.soderbjorn.lunicle.clientserver.VocabularyKind

/**
 * What an unanswered Save / Discard / Keep-editing question is guarding.
 *
 * Two gestures can lose typed text and both must ask first, but what happens
 * after each answer differs: answering for [Window] ends with the window
 * closing, answering for [LeaveEdit] ends with the window still open in read
 * mode. One enum rather than two boolean flags, because the two questions are
 * mutually exclusive and a state that could claim both at once would be a
 * state that lies.
 */
enum class CloseConfirm {
    /** The user clicked the window's close control. */
    Window,

    /** The user toggled the Edit button back toward read mode. */
    LeaveEdit,
}

/**
 * Owns one issue window.
 *
 * Several of these can be alive at once — one per open issue window — which is
 * why nothing in here is shared mutable state beyond the [storage] the caller
 * passes in (a stateless HTTP wrapper).
 *
 * @param issueId the issue to open. Always real; see the file's preamble.
 * @param board the board it belongs to, for the label/component/status
 *   vocabularies. Passed in rather than re-fetched: MainScreen already has it,
 *   and asking again would be a round-trip to learn what the caller knows.
 * @param onFinished called when the window is done and should close; true if
 *   anything was written.
 * @param onWritten called after any write the board might need to reflect — a
 *   save, a delete, a comment — *without* the window closing. The bootstrap
 *   routes it to the board's refresh, which is what keeps a card's title
 *   current the moment its issue is saved rather than when its window closes.
 */
class IssueBackingViewModel(
    private val issueId: Long,
    private val board: BoardState,
    /**
     * Whether this user is hiding issue numbers on this project (LNL-105). Read
     * once at open from the board view model's per-project preference and carried
     * into [State] — a per-user view choice, so it rides here rather than on the
     * shared [board]. Governs only the window's heading; the ticket key still names
     * the issue in the URL and identity.
     */
    private val hideIssueNumbers: Boolean = false,
    /**
     * The status a fresh draft should open on (LNL-124), or null to keep the
     * server's default. Set when the window was opened from a board column's
     * "Create issue…". Applied only while the row is still a draft, and folded into
     * the saved baseline so the pre-fill is a starting point, not an unsaved edit —
     * an otherwise-untouched draft opened this way still closes silently.
     */
    private val initialStatusId: Long? = null,
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onFinished: (changed: Boolean) -> Unit,
    private val onWritten: () -> Unit = {},
    private val editorRegistry: EditorDirtyRegistry = EditorDirtyRegistry(),
) {
    private val _stateFlow = MutableStateFlow(State())

    /** The current modal state, observed by the view. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * Whether anything has been written since the modal opened.
     *
     * Tracked rather than inferred from `isDirty`, because a comment posts
     * immediately: the issue can be untouched and the modal still have changed
     * the board.
     */
    private var hasWritten = false

    init {
        // Join the app-wide dirty registry so a project switch can ask whether this
        // window has unsaved edits, and close it through [onSwitchAway] if the user
        // goes ahead. Dropped when this window's scope completes (IssueWindows
        // cancels it on dispose), so a closed window leaves no stale "dirty" entry.
        // See LNL-84 and [EditorDirtyRegistry].
        val registration = editorRegistry.register(
            isDirty = { _stateFlow.value.isDirty },
            discardAndClose = { onSwitchAway() },
        )
        scope.coroutineContext[Job]?.invokeOnCompletion { registration.cancel() }
    }

    /**
     * Immutable snapshot of the modal.
     *
     * @property isEditing whether the fields are live. A reader sees rendered
     *   markdown; someone with rights sees the editor. Starts true for a draft —
     *   a new issue that opened read-only would be absurd.
     * @property isDraft whether this issue has never been published. Drives the
     *   whole Cancel contract; see the preamble.
     */
    /**
     * The editable fields as one value, so "has anything changed" is a single
     * `!=` between what is on screen and what was last loaded or saved —
     * see [State.isDirty]. Field-for-field with the editable half of [State];
     * a field added there and forgotten here would be a field whose edits are
     * silently droppable, which is why they sit adjacent.
     */
    data class Fields(
        val title: String,
        val description: String,
        val statusId: Long,
        val priorityId: Long,
        val resolutionId: Long?,
        /**
         * The assignee, and therefore part of what "unsaved changes" means: the
         * dropdown is staged like every other field, so picking somebody and
         * closing the window has to raise the same Save / Discard question that
         * typing a title does.
         *
         * Note "Assign to me" is NOT one of these edits — it writes immediately
         * through its own route and re-reads the issue, so it moves the baseline
         * rather than dirtying it. See [onAssignToMeTapped].
         */
        val assigneeId: Long?,
        /**
         * The sprint, staged like the assignee and for the same reason: the
         * dropdown is in the editor, so scheduling something and then closing the
         * window has to raise the same Save / Discard question typing does.
         *
         * The card menu's "Move to sprint" is NOT one of these edits — it writes
         * immediately through its own route, exactly as "Assign to me" does.
         */
        val sprintId: Long?,
        /**
         * The planned and fixed release versions, staged like the sprint and for
         * the same reason (LNL-134): both are dropdowns in the editor, so picking
         * one and closing raises the same Save / Discard question. The resolution
         * dialog's fixed-version write is NOT one of these — it goes through the
         * status route immediately, like "Move to sprint".
         */
        val plannedVersionId: Long?,
        val fixedVersionId: Long?,
        val labelIds: Set<Long>,
        val componentIds: Set<Long>,
    )

    data class State(
        val isLoaded: Boolean = false,
        val isBusy: Boolean = false,
        val isDraft: Boolean = false,
        val isEditing: Boolean = false,
        val number: Long = 0,
        val title: String = "",
        val description: String = "",
        val statusId: Long = 0,
        val priorityId: Long = 0,
        val resolutionId: Long? = null,
        val labelIds: Set<Long> = emptySet(),
        val componentIds: Set<Long> = emptySet(),
        /** Who is working on this, or null for nobody. */
        val assigneeId: Long? = null,
        /** Which sprint this is scheduled into, or null for the backlog. */
        val sprintId: Long? = null,
        /**
         * Where this work could be scheduled. Empty for every project with no
         * sprints, which is what hides the field entirely — see [showsSprint].
         */
        val sprints: List<SprintItem> = emptyList(),
        /** Which release this is planned for, and which it was fixed in, or null. LNL-134. */
        val plannedVersionId: Long? = null,
        val fixedVersionId: Long? = null,
        /**
         * This project's release versions, feeding the planned- and fixed-version
         * dropdowns. Empty for every project with none, which hides both fields —
         * see [showsVersions].
         */
        val versions: List<VocabularyItem> = emptyList(),
        /** The assignee's name, resolved by the server. Null when unassigned. */
        val assigneeName: String? = null,
        /** Who the editor's Assignee dropdown may offer. Empty unless [canEdit]. */
        val assignableUsers: List<UserOption> = emptyList(),
        /**
         * Who the `@` autocomplete may offer, in both the description editor and
         * the comment modal. Empty for a reader who can write neither — see
         * IssueDetail.mentionableUsers.
         */
        val mentionableUsers: List<UserOption> = emptyList(),
        /** Whether this caller may hold an issue here — what shows the assign button. */
        val canBeAssigned: Boolean = false,
        /** Who the server says this caller is, for the assign button. See IssueDetail.callerId. */
        val callerId: Long? = null,
        val authorName: String? = null,
        /** The agent that filed this, or null when a human did. Rendered as a badge beside the byline. */
        val agentName: String? = null,
        val createdAt: Long = 0,
        val comments: List<CommentView> = emptyList(),
        /** What has happened to this issue, oldest first. See [historyLine]. */
        val history: List<IssueEventView> = emptyList(),
        val canEdit: Boolean = false,
        val canDelete: Boolean = false,
        val canComment: Boolean = false,
        /** Whether the caller is subscribed to this issue's update e-mails. */
        val notifyOnUpdates: Boolean = false,
        /** The names of everyone watching this issue, shown to any reader. */
        val watchers: List<String> = emptyList(),
        /**
         * The epic this issue belongs under, or null (LNL-55). Not a staged
         * [Fields] member: reparenting writes immediately through its own route and
         * folds the server's truth back, exactly as "Assign to me" does — so it
         * moves the baseline rather than dirtying it, and Cancel has no claim on it.
         */
        val parentId: Long? = null,
        val parent: IssueRef? = null,
        /** This issue's children in their work order, or empty when it is not an epic. */
        val children: List<IssueRef> = emptyList(),
        /**
         * Issues offerable as a *parent* of this one — filtered to the one-level
         * rule client-side (not itself a child, not itself an epic) so the picker
         * never offers a pick the server would refuse. Derived in [foldEpic].
         */
        val parentCandidates: List<IssueRef> = emptyList(),
        /** Issues offerable as a *child* of this epic — not already a child, not themselves an epic. */
        val childCandidates: List<IssueRef> = emptyList(),
        /** The unanswered "detach from parent?" question. See [onRemoveParentRequested]. */
        val confirmingRemoveParent: Boolean = false,
        /** The child whose removal from this epic is being asked about, or null. */
        val confirmingRemoveChildId: Long? = null,
        /**
         * Whether the caller has an e-mail to send to. The toggle is hidden
         * without one — a switch promising mail we cannot send is a dead control.
         */
        val canReceiveEmailNotifications: Boolean = false,
        val isConfirmingDelete: Boolean = false,
        /**
         * The comment whose deletion is being asked about, or null.
         *
         * An id rather than a flag because the question names one comment out
         * of a list, and the answer has to reach that same one. Rendered by the
         * view exactly as [isConfirmingDelete] is.
         */
        val confirmingDeleteCommentId: Long? = null,
        /**
         * The unanswered Save / Discard / Keep-editing question, or null.
         * Set by [onCloseRequested] and the Edit toggle; answered by
         * [onCloseSaveTapped] / [onCloseDiscardTapped] /
         * [onCloseKeepEditingTapped]. The view renders the modal off this,
         * exactly as it renders the delete confirmation off
         * [isConfirmingDelete].
         */
        val confirmingClose: CloseConfirm? = null,
        /**
         * The fields as last loaded or saved — the baseline [isDirty]
         * compares against. Null until the first fetch returns.
         */
        val saved: Fields? = null,
        val errorMessage: String? = null,
        val statuses: List<StatusItem> = emptyList(),
        val priorities: List<StatusItem> = emptyList(),
        val resolutions: List<StatusItem> = emptyList(),
        val labels: List<VocabularyItem> = emptyList(),
        val components: List<VocabularyItem> = emptyList(),
        val prefix: String = "",
        /** Hide the ticket number in this window's heading — a per-user choice (LNL-105). */
        val hideIssueNumbers: Boolean = false,
        /** Whether this project requires a label / a component on a new ticket (LNL-106). */
        val requireLabel: Boolean = false,
        val requireComponent: Boolean = false,
        /** Whether closing with a done resolution must carry a fixed version (LNL-134). */
        val requireFixedVersionOnResolve: Boolean = false,
        /**
         * Whether this caller administers the project (LNL-134). What decides the
         * version dropdown offers "Add new…" and the per-item Delete — managing the
         * version vocabulary is an admin gesture, and the routes behind add/delete
         * are admin-gated, so a non-admin only picks from what exists.
         */
        val canMutateProject: Boolean = false,
    ) {
        /** The editable fields as they are on screen right now. */
        val fields: Fields get() = Fields(
            title = title,
            description = description,
            statusId = statusId,
            priorityId = priorityId,
            resolutionId = resolutionId,
            assigneeId = assigneeId,
            sprintId = sprintId,
            plannedVersionId = plannedVersionId,
            fixedVersionId = fixedVersionId,
            labelIds = labelIds,
            componentIds = componentIds,
        )

        /** Whether the caller currently holds this issue. Drives the button's two labels. */
        val isAssignedToMe: Boolean get() = callerId != null && assigneeId == callerId

        /**
         * "Assigned to Robert", or "Unassigned".
         *
         * Always a sentence rather than hidden when empty, unlike [watchersLabel]:
         * "nobody is on this" is the fact somebody opens an issue to learn, and a
         * line that disappears when the answer is "nobody" answers it by omission —
         * which reads as a missing feature rather than as information.
         */
        val assigneeLabel: String get() =
            assigneeName?.let { "Assigned to $it" } ?: "Unassigned"

        /**
         * "Assign to me" / "Unassign me", or null when the button is not offered.
         *
         * Null for a draft, which has nobody to notify and is about to be published
         * anyway, and null in the editor, where the dropdown is the way to say this
         * — two controls writing one field through two routes, on screen at once,
         * is how you get a click that silently loses a staged edit.
         */
        val assignButtonLabel: String? get() = when {
            !canBeAssigned || isDraft || isEditing -> null
            isAssignedToMe -> "Unassign me"
            else -> "Assign to me"
        }

        /**
         * The assignee dropdown's rows, with "Nobody" first.
         *
         * A real row rather than the [StatusItem] pickers' `placeholder`, and the
         * difference matters: a placeholder is what the control *reads* while
         * nothing is chosen, so it can only ever be left — there is nothing to
         * click to get back to it. Unassigning somebody in the editor has to be a
         * thing you can pick. See [UNASSIGNED_ID] for how it crosses back.
         */
        val assigneeOptions: List<UserOption> get() =
            listOf(UserOption(id = UNASSIGNED_ID, name = "Nobody")) + assignableUsers

        /** What the assignee dropdown should show as live. Never null; see [UNASSIGNED_ID]. */
        val assigneeSelectedId: Long get() = assigneeId ?: UNASSIGNED_ID

        /**
         * Whether to render the sprint field at all.
         *
         * Absence of sprints is the only switch this feature has, so this is the
         * whole of what keeps a kanban issue window unchanged — no empty
         * dropdown, no "Sprint: —" caption, nothing. See Sprints.sq.
         */
        val showsSprint: Boolean get() = sprints.isNotEmpty()

        /** "Nobody" for sprints: the backlog is a choice, not the absence of one. */
        val sprintOptions: List<SprintItem> get() =
            listOf(SprintItem(id = NO_SPRINT_ID, name = "Backlog", position = -1)) + sprints

        /** What the sprint dropdown should show as live. Never null; see [NO_SPRINT_ID]. */
        val sprintSelectedId: Long get() = sprintId ?: NO_SPRINT_ID

        /** The sprint's name for read mode, or "Backlog" because it is in none. */
        val sprintName: String get() =
            sprints.firstOrNull { it.id == sprintId }?.name ?: "Backlog"

        /** Whether this issue is an epic — that is, whether anything points at it. */
        val isEpic: Boolean get() = children.isNotEmpty()

        /**
         * Whether to offer the parent controls in edit mode (LNL-55). Hidden once
         * this issue is itself an epic: an issue with children may not also be a
         * child — epics are one level deep — so offering it a parent would be
         * offering a write the server refuses. Read mode still shows the parent
         * *chip* when one is set; this only gates the picker.
         */
        val showsParentControls: Boolean get() = canEdit && !isEpic

        /**
         * Whether to offer the children controls in edit mode. Hidden once this
         * issue is itself a child: a child may not have children of its own, the
         * mirror of [showsParentControls]. The children *list* still renders in read
         * mode for a genuine epic regardless.
         */
        val showsChildrenControls: Boolean get() = canEdit && parentId == null

        /** The parent key for the read-mode chip, e.g. "LMX-4", or null when it stands alone. */
        val parentTicket: String? get() = parent?.let { "$prefix-${it.number}" }

        /** One child's key, e.g. "LMX-9". */
        fun childTicket(child: IssueRef): String = "$prefix-${child.number}"

        /**
         * Whether closing now would lose typed text.
         *
         * Only meaningful in edit mode: read mode has no editable surface, so
         * whatever [fields] holds there is by construction what was loaded. An
         * untouched draft is NOT dirty — its fields still equal the empty row
         * the server created — which is what lets an abandoned "New issue"
         * close silently instead of asking about work that was never done.
         */
        val isDirty: Boolean get() =
            isEditing && saved != null && fields != saved

        /** "LMX-123", or "New issue" for a draft that has no meaningful number yet. */
        val ticket: String get() = "$prefix-$number"

        /**
         * The window's title line. Normally the ticket key; when this user hides
         * issue numbers on this project (LNL-105) the number steps aside — a draft
         * reads plain "New issue", and a saved issue shows its own title instead of
         * its key. Only the heading changes: [ticket] still names the issue in the
         * URL and the unsaved-changes bookkeeping.
         */
        val heading: String get() = when {
            isDraft && hideIssueNumbers -> "New issue"
            isDraft -> "New issue ($ticket)"
            hideIssueNumbers -> title.ifBlank { "Issue" }
            else -> ticket
        }

        /**
         * "Watched by Alice, Bob", or null when nobody is watching — which is when
         * the line is not shown at all. Every reader sees this; see the server's
         * IssueDetail.watchers.
         */
        val watchersLabel: String? get() =
            watchers.takeIf { it.isNotEmpty() }?.let { "Watched by ${it.joinToString(", ")}" }

        /**
         * Whether the chosen status is one that demands a resolution.
         *
         * Read from the status's own flag, never from its name — see Statuses.sq.
         * The dialog shows the resolution field off this, and [validationMessage]
         * refuses OK without one.
         */
        val requiresResolution: Boolean
            get() = statuses.firstOrNull { it.id == statusId }?.requiresResolution == true

        /**
         * Whether both version fields should render, and whether the fixed one is
         * required right now (LNL-134).
         *
         * [showsVersions] hides the fields entirely for a project with no versions —
         * the same emptiness-is-the-flag contract [showsSprint] keeps. [resolvingAsDone]
         * reads the chosen resolution's own `isDone`, never its name, so a renamed
         * "Done" keeps its meaning; it is what turns the fixed-version dropdown into a
         * required field when the project's [requireFixedVersionOnResolve] is on.
         */
        val showsVersions: Boolean get() = versions.isNotEmpty()

        val resolvingAsDone: Boolean
            get() = resolutionId?.let { id -> resolutions.firstOrNull { it.id == id }?.isDone == true } == true

        /**
         * Why OK is disabled, or null.
         *
         * Labels and components were once deliberately absent — "one or more" read
         * as zero-or-more — but LNL-106 lets a project administrator make either
         * mandatory, so they are here now *conditionally*: only when the project's
         * flag is on, only on a NEW ticket ([isDraft]), and only when the project
         * actually has some to pick — the same three conditions the server enforces
         * at publish, so this is the affordance in front of a rule the server really
         * has, not a client-only opinion. A project that requires nothing behaves
         * exactly as before.
         *
         * The resolution rule is here for the same reason: closing without one is
         * refused server-side, so an OK that let you try would just round-trip to
         * the same answer.
         */
        val validationMessage: String? get() = when {
            title.isBlank() -> "An issue needs a title."
            // An affordance, exactly like every other rule in this file: the
            // server checks again and its 400 wins. It is here so OK is visibly
            // refused with a sentence, rather than failing after the round-trip.
            requiresResolution && resolutionId == null -> "Closing an issue needs a resolution."
            // The fix-version rule, the affordance in front of BoardRoutes'
            // resolveFixedVersion: only when closing as a *done* resolution, the
            // project requires it, and there is a version to pick. Closing as
            // Duplicate or Won't fix never trips it.
            requireFixedVersionOnResolve && resolvingAsDone && showsVersions && fixedVersionId == null ->
                "Closing this issue as done needs a fixed version."
            isDraft && requireLabel && labels.isNotEmpty() && labelIds.isEmpty() ->
                "This project requires a label on a new ticket."
            isDraft && requireComponent && components.isNotEmpty() && componentIds.isEmpty() ->
                "This project requires a component on a new ticket."
            else -> null
        }

        val isOkEnabled: Boolean get() = !isBusy && validationMessage == null

        val confirmDeleteMessage: String get() = "Delete $ticket? This cannot be undone."

        val confirmDeleteCommentMessage: String get() = "Delete this comment? This cannot be undone."

        /** "Remove LMX-3 from its parent LMX-1?" — the child-side detach confirmation (LNL-55). */
        val confirmRemoveParentMessage: String get() =
            "Remove $ticket from ${parentTicket?.let { "its parent $it" } ?: "its parent"}? " +
                "The issue stays; it just no longer belongs under that epic."

        /** "Remove LMX-9 from this epic?" — the epic-side child-removal confirmation. */
        val confirmRemoveChildMessage: String get() {
            val child = children.firstOrNull { it.id == confirmingRemoveChildId }
            val childKey = child?.let { "$prefix-${it.number}" } ?: "that issue"
            return "Remove $childKey from this epic? The issue stays; it just no longer belongs under it."
        }

        /** The status's name, for the read-only view. */
        val statusName: String get() = statuses.firstOrNull { it.id == statusId }?.name ?: "—"

        /** The priority's name, for the read-only view. */
        val priorityName: String get() = priorities.firstOrNull { it.id == priorityId }?.name ?: "—"

        /** The resolution's name, or null when the issue has none — which is most of them. */
        val resolutionName: String? get() = resolutionId?.let { id ->
            resolutions.firstOrNull { it.id == id }?.name
        }

        val labelNames: List<String> get() = labels.filter { it.id in labelIds }.map { it.name }
        val componentNames: List<String> get() = components.filter { it.id in componentIds }.map { it.name }

        /** The planned/fixed version's name, or null when unset — for the read-only view. LNL-134. */
        val plannedVersionName: String? get() = plannedVersionId?.let { id -> versions.firstOrNull { it.id == id }?.name }
        val fixedVersionName: String? get() = fixedVersionId?.let { id -> versions.firstOrNull { it.id == id }?.name }

        /**
         * What to say under the heading in read mode: "Robert · 17 Jul 2026, 14:32".
         *
         * The same shape as [commentByline], and deliberately so — an issue and a
         * comment are both "someone said this, then". It used to read "Filed by
         * Robert", which the heading above it already implies: you are looking at
         * an issue, so it was filed. The name and the date are the two facts that
         * are not already on screen.
         */
        val byline: String get() =
            "${authorName ?: "A deleted account"} · ${formatTimestamp(createdAt)}"

        /**
         * "Watched by …" for the identity line's tail, or null when the only
         * watcher is the caller.
         *
         * The corner pill already tells the caller they are watching, so a tail
         * that only ever repeats their own name is the duplication LNL-14 set out
         * to cut. [notifyOnUpdates] says whether the caller is among the watchers,
         * so "caller and no one else" is `notifyOnUpdates && watchers.size <= 1` —
         * suppressed. When anyone else watches, the full [watchersLabel] shows, its
         * names intact.
         */
        val watchersTail: String? get() =
            if (notifyOnUpdates && watchers.size <= 1) null else watchersLabel

        /**
         * The read face's one-line identity (LNL-14 proposal 3): who filed it and
         * when, who is on it, and — when someone other than the caller watches —
         * who is watching, folded into a single dim line instead of three stacked
         * bands. The assign button is a live control the view trails after this
         * prose; only the words are here.
         */
        val readIdentityLine: String get() = buildString {
            append(byline)
            append(" · ")
            append(assigneeLabel)
            watchersTail?.let {
                append(" · ")
                append(it)
            }
        }

        /**
         * The agent badge's text, or null when a human filed the issue.
         *
         * Beside the byline, never inside it: the issue is still the author's, and
         * this is the separate fact that an agent held the pen. The word "Agent"
         * is in the text and not left to the icon alone — the requirement is that a
         * reader can see *at a glance* a human did not type this, and an icon a
         * reader has to learn is not at-a-glance. See [commentAgentBadge].
         */
        val agentBadge: String? get() = agentName?.let { "Agent · $it" }

        /**
         * One comment's attribution line: "Robert · 17 Jul 2026, 14:32".
         *
         * A function on State rather than a property on [CommentView], because
         * CommentView is the wire type — it is what the server sends, and the
         * server has no opinion about how a date is spelled or what a deleted
         * account is called. Mirrors `MainScreenBackingViewModel.State.cardLabel`,
         * which is the same shape for the same reason.
         *
         * The interpunct rather than a comma: the timestamp already contains a
         * comma, and "Robert, 17 Jul 2026, 14:32" reads as a list of three
         * things.
         */
        fun commentByline(comment: CommentView): String =
            "${comment.authorName ?: "A deleted account"} · ${formatTimestamp(comment.createdAt)}"

        /** One comment's agent badge text, or null when a human wrote it. See [agentBadge]. */
        fun commentAgentBadge(comment: CommentView): String? = comment.agentName?.let { "Agent · $it" }

        /**
         * What one history event says happened, as a sentence.
         *
         * **This is where the wording lives, and it is the whole reason the server
         * sends a [IssueEventKind] instead of a string.** A sentence composed on
         * the server would be frozen into the row at the moment it was written, so
         * a history spanning a phrasing change would speak in two voices forever,
         * and nothing could ever be re-worded, shortened or translated. Composed
         * here, every event in the table re-reads itself the moment this function
         * changes.
         *
         * Written in the past tense and without a subject, because [historyByline]
         * supplies the subject on the line above: "Robert · 17 Jul 2026" then
         * "changed the title to Fix the login bug".
         */
        fun historyDescription(event: IssueEventView): String = when (event.kind) {
            IssueEventKind.CREATED -> "created this issue"
            // The new title quoted, because a title can be a sentence and an
            // unquoted one runs into the words around it.
            IssueEventKind.TITLE_CHANGED -> "changed the title to \"${event.value.orEmpty()}\""
            IssueEventKind.DESCRIPTION_CHANGED -> "edited the description"
            IssueEventKind.LABELS_CHANGED -> describeSet("labels", event.values)
            IssueEventKind.COMPONENTS_CHANGED -> describeSet("components", event.values)
            IssueEventKind.STATUS_CHANGED -> "moved this to ${event.value ?: "another column"}"
            // Null is not missing data here — it is the event. See
            // IssueEventView.value.
            IssueEventKind.ASSIGNEE_CHANGED ->
                event.value?.let { "assigned this to $it" } ?: "unassigned this issue"
        }

        /**
         * "set the labels to Bug, Feature", or "cleared the labels".
         *
         * The empty case is worth its own sentence rather than falling out as "set
         * the labels to " with nothing after it. Clearing a set is a thing people
         * do on purpose, and it should read like one.
         */
        private fun describeSet(noun: String, values: List<String>): String =
            if (values.isEmpty()) "cleared the $noun" else "set the $noun to ${values.joinToString(", ")}"

        /** One event's attribution line. The same shape as [commentByline], for its reasons. */
        fun historyByline(event: IssueEventView): String =
            "${event.authorName ?: "A deleted account"} · ${formatTimestamp(event.createdAt)}"

        /** One event's agent badge text, or null when a human made the change. See [agentBadge]. */
        fun historyAgentBadge(event: IssueEventView): String? = event.agentName?.let { "Agent · $it" }
    }

    /** Fetch the issue. Called once by the view when it mounts. */
    fun start() {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true)
        scope.launch { refresh(startEditingIfDraft = true) }
    }

    private suspend fun refresh(startEditingIfDraft: Boolean) {
        val result = runCatching { storage.issue(issueId) }
        _stateFlow.value = result.fold(
            onSuccess = { detail -> detail.applyTo(_stateFlow.value, startEditingIfDraft) },
            onFailure = { t ->
                println("Issue: fetch failed: ${t.message}")
                _stateFlow.value.copy(isBusy = false, errorMessage = t.userMessage("Could not open that issue."))
            },
        )
    }

    private fun IssueDetail.applyTo(previous: State, startEditingIfDraft: Boolean): State {
        // Seed a column-opened draft on its column (LNL-124). Only while the row is
        // still a draft: once published it carries a real status, and a later
        // refresh (after save) must not drag it back. Folded into `saved` below as
        // well, so the pre-fill is the baseline rather than a phantom unsaved edit.
        val resolvedStatusId = if (isDraft) initialStatusId ?: statusId else statusId
        return previous.copy(
            isLoaded = true,
            isBusy = false,
            isDraft = isDraft,
            // A draft opens in the editor, because it has nothing to read yet.
            // Anything else opens read-only, even for someone who may edit: opening
            // straight into a form makes it too easy to change an issue you only
            // meant to look at.
            isEditing = if (startEditingIfDraft && isDraft) true else previous.isEditing,
            number = number,
            title = title,
            description = description,
            statusId = resolvedStatusId,
            priorityId = priorityId,
            resolutionId = resolutionId,
            labelIds = labelIds.toSet(),
            componentIds = componentIds.toSet(),
            assigneeId = assigneeId,
            sprintId = sprintId,
            sprints = sprints,
            plannedVersionId = plannedVersionId,
            fixedVersionId = fixedVersionId,
            versions = versions,
            assigneeName = assigneeName,
            assignableUsers = assignableUsers,
            mentionableUsers = mentionableUsers,
            canBeAssigned = canBeAssigned,
            callerId = callerId,
            authorName = authorName,
            agentName = agentName,
            createdAt = createdAt,
            comments = comments,
            history = history,
            canEdit = canEdit,
            canDelete = canDelete,
            canComment = canComment,
            notifyOnUpdates = notifyOnUpdates,
            canReceiveEmailNotifications = canReceiveEmailNotifications,
            watchers = watchers,
            parentId = parentId,
            parent = parent,
            children = children,
            parentCandidates = parentCandidatesFrom(linkableIssues, currentParentId = parentId),
            childCandidates = childCandidatesFrom(linkableIssues, currentChildren = children),
            // The freshly fetched fields are the new dirty-baseline: whatever is
            // on screen after this apply is, by definition, unmodified.
            saved = Fields(
                title = title,
                description = description,
                statusId = resolvedStatusId,
                priorityId = priorityId,
                resolutionId = resolutionId,
                assigneeId = assigneeId,
                sprintId = sprintId,
                plannedVersionId = plannedVersionId,
                fixedVersionId = fixedVersionId,
                labelIds = labelIds.toSet(),
                componentIds = componentIds.toSet(),
            ),
            errorMessage = null,
            statuses = board.statuses,
            priorities = board.priorities,
            resolutions = board.resolutions,
            labels = board.labels,
            components = board.components,
            prefix = board.project.namePrefix,
            hideIssueNumbers = hideIssueNumbers,
            requireLabel = board.project.requireLabel,
            requireComponent = board.project.requireComponent,
            requireFixedVersionOnResolve = board.project.requireFixedVersionOnResolve,
            canMutateProject = board.permissions.canMutateProject,
        )
    }

    /**
     * Subscribe or unsubscribe from this issue's update e-mails.
     *
     * Only the two notification fields are taken from the response — never the
     * whole [IssueDetail], which would overwrite an edit in progress with the
     * server's copy. The toggle is available in read and edit mode alike, and a
     * subscription is orthogonal to whatever is being typed. See the server's
     * issueNotification route.
     */
    fun onNotificationToggled(subscribed: Boolean) {
        val current = _stateFlow.value
        if (current.isBusy) return
        _stateFlow.value = current.copy(isBusy = true, errorMessage = null)
        scope.launch {
            runCatching { storage.setIssueNotification(issueId, subscribed) }.fold(
                onSuccess = { detail ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        notifyOnUpdates = detail.notifyOnUpdates,
                        canReceiveEmailNotifications = detail.canReceiveEmailNotifications,
                    )
                },
                onFailure = { t ->
                    println("Issue: notification toggle failed: ${t.message}")
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not change your notification setting."),
                    )
                },
            )
        }
    }

    // ── Epics: the parent link and the children (LNL-55) ───────────────────────
    //
    // Every one of these writes immediately through its own route and folds only
    // the epic fields of the server's reply back — never the whole IssueDetail,
    // which would overwrite a title or description being typed in edit mode. The
    // same shape "Assign to me" and the notification toggle use: the write moves the
    // baseline rather than dirtying it, so Cancel has nothing of theirs to discard.

    /**
     * Issues offerable as a *parent* of this one, filtered to the rules the server
     * would enforce so the picker never offers a doomed pick: not the one already
     * set, not itself already a child, and not itself an epic (epics are one level
     * deep). The board — which carries each card's `parentId` — is what tells us
     * which candidates are children or epics.
     */
    private fun parentCandidatesFrom(linkable: List<IssueRef>, currentParentId: Long?): List<IssueRef> {
        val epics = board.issues.mapNotNull { it.parentId }.toSet()
        val byId = board.issues.associateBy { it.id }
        return linkable.filter { ref ->
            ref.id != currentParentId && byId[ref.id]?.parentId == null && ref.id !in epics
        }
    }

    /** Issues offerable as a *child* of this epic: not already a child of it, and not themselves an epic. */
    private fun childCandidatesFrom(linkable: List<IssueRef>, currentChildren: List<IssueRef>): List<IssueRef> {
        val epics = board.issues.mapNotNull { it.parentId }.toSet()
        val childIds = currentChildren.map { it.id }.toSet()
        return linkable.filter { ref -> ref.id !in childIds && ref.id !in epics }
    }

    /** Fold only the epic fields of a fresh detail back into state — see the section preamble. */
    private fun State.foldEpic(detail: IssueDetail): State = copy(
        isBusy = false,
        parentId = detail.parentId,
        parent = detail.parent,
        children = detail.children,
        parentCandidates = parentCandidatesFrom(detail.linkableIssues, detail.parentId),
        childCandidates = childCandidatesFrom(detail.linkableIssues, detail.children),
    )

    /** The shared runner: busy-guard, run [block], fold the epic fields, report a board write. */
    private fun epicWrite(errorFallback: String, block: suspend () -> IssueDetail) {
        val current = _stateFlow.value
        if (current.isBusy) return
        _stateFlow.value = current.copy(isBusy = true, errorMessage = null)
        scope.launch {
            runCatching { block() }.fold(
                onSuccess = { detail ->
                    _stateFlow.value = _stateFlow.value.foldEpic(detail)
                    // Child counts and parent chips are drawn on the board too, so a
                    // reparent is a board change like a save is. See [onWritten].
                    onWritten()
                },
                onFailure = { t ->
                    println("Issue: epic write failed: ${t.message}")
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage(errorFallback),
                    )
                },
            )
        }
    }

    /** Attach this issue under the chosen epic — the parent picker's selection. */
    fun onParentChosen(parentId: Long) =
        epicWrite("Could not set the parent.") { storage.setIssueParent(issueId, parentId) }

    /** Ask before detaching this issue from its epic — the child-side "remove from parent". */
    fun onRemoveParentRequested() {
        val current = _stateFlow.value
        if (current.isBusy || current.parentId == null) return
        _stateFlow.value = current.copy(confirmingRemoveParent = true)
    }

    fun onRemoveParentConfirmed() {
        _stateFlow.value = _stateFlow.value.copy(confirmingRemoveParent = false)
        epicWrite("Could not remove the parent.") { storage.setIssueParent(issueId, null) }
    }

    fun onRemoveParentCancelled() {
        _stateFlow.value = _stateFlow.value.copy(confirmingRemoveParent = false)
    }

    /**
     * Add an issue as a child of this epic — the add-child autocomplete's selection.
     *
     * The write posts to the *child's* id (its parent becomes this issue), so its
     * reply is the child's detail, not this epic's — a second read of this issue is
     * what refreshes the children list. See the server's issueParent route.
     */
    fun onChildAdded(childId: Long) =
        epicWrite("Could not add that child.") {
            storage.setIssueParent(childId, issueId)
            storage.issue(issueId)
        }

    /** Ask before removing a child from this epic. */
    fun onRemoveChildRequested(childId: Long) {
        val current = _stateFlow.value
        if (current.isBusy) return
        _stateFlow.value = current.copy(confirmingRemoveChildId = childId)
    }

    fun onRemoveChildConfirmed() {
        val childId = _stateFlow.value.confirmingRemoveChildId ?: return
        _stateFlow.value = _stateFlow.value.copy(confirmingRemoveChildId = null)
        epicWrite("Could not remove that child.") {
            storage.setIssueParent(childId, null)
            storage.issue(issueId)
        }
    }

    fun onRemoveChildCancelled() {
        _stateFlow.value = _stateFlow.value.copy(confirmingRemoveChildId = null)
    }

    /**
     * Move a child up (−1) or down (+1) in this epic's work order, then write the
     * whole set. Renumbering the full list is what the server validates against, so
     * the client sends all of it, not a delta.
     */
    fun onMoveChild(childId: Long, delta: Int) {
        val current = _stateFlow.value
        if (current.isBusy) return
        val ids = current.children.map { it.id }.toMutableList()
        val index = ids.indexOf(childId)
        val target = index + delta
        if (index < 0 || target < 0 || target >= ids.size) return
        ids.add(target, ids.removeAt(index))
        epicWrite("Could not reorder the children.") { storage.reorderChildren(issueId, ids) }
    }

    // ── Editing ──────────────────────────────────────────────────────────────

    /**
     * The Edit button — a toggle, both directions.
     *
     * Into edit mode: gated on [State.canEdit], exactly as before. Back toward
     * read mode: silent when nothing changed, otherwise the same Save / Discard
     * / Keep-editing question the close control asks — leaving edit mode drops
     * the same typed text closing does, so it earns the same guard. A draft
     * never toggles back: it has nothing to read yet.
     */
    fun onEditTapped() {
        val current = _stateFlow.value
        if (!current.isEditing) {
            if (!current.canEdit) return
            _stateFlow.value = current.copy(isEditing = true)
            return
        }
        if (current.isDraft || current.isBusy) return
        if (!current.isDirty) {
            _stateFlow.value = current.copy(isEditing = false)
            return
        }
        _stateFlow.value = current.copy(confirmingClose = CloseConfirm.LeaveEdit)
    }

    // ── Closing ──────────────────────────────────────────────────────────────

    /**
     * The window's close control was clicked.
     *
     * Clean states close at once: a read-mode issue, an edit with nothing
     * typed, and — the draft contract — an untouched "New issue", whose backing
     * row is deleted on the way out exactly as Cancel used to. Only a genuinely
     * dirty edit stops to ask; [State.confirmingClose] carries the question and
     * the three `onClose*Tapped` intents are the answers.
     */
    fun onCloseRequested() {
        val current = _stateFlow.value
        if (current.isBusy) return
        if (current.isDirty) {
            _stateFlow.value = current.copy(confirmingClose = CloseConfirm.Window)
            return
        }
        closeDiscarding(current)
    }

    /** Save, then do what the confirmed gesture wanted. */
    fun onCloseSaveTapped() {
        val current = _stateFlow.value
        val intent = current.confirmingClose ?: return
        // A save that cannot succeed — a blank title, a close without a
        // resolution — turns into the validation sentence with the question
        // dismissed: the user is back in the editor looking at why, which
        // beats a Save button that silently does nothing inside a modal.
        val validation = current.validationMessage
        if (validation != null) {
            _stateFlow.value = current.copy(confirmingClose = null, errorMessage = validation)
            return
        }
        _stateFlow.value = current.copy(confirmingClose = null, isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { saveCurrent(_stateFlow.value) }
            result.fold(
                onSuccess = {
                    hasWritten = true
                    onWritten()
                    when (intent) {
                        CloseConfirm.Window -> onFinished(true)
                        CloseConfirm.LeaveEdit -> {
                            _stateFlow.value = _stateFlow.value.copy(isEditing = false)
                            refresh(startEditingIfDraft = false)
                        }
                    }
                },
                onFailure = { t ->
                    println("Issue: save failed: ${t.message}")
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not save that issue."),
                    )
                },
            )
        }
    }

    /** Drop the typed text, then do what the confirmed gesture wanted. */
    fun onCloseDiscardTapped() {
        val current = _stateFlow.value
        val intent = current.confirmingClose ?: return
        when (intent) {
            CloseConfirm.Window -> closeDiscarding(current.copy(confirmingClose = null))
            CloseConfirm.LeaveEdit -> {
                // Back to read mode showing what is actually saved. The saved
                // baseline is authoritative here — restoring from it rather
                // than re-fetching, because nothing was written.
                val saved = current.saved ?: return
                _stateFlow.value = current.copy(
                    confirmingClose = null,
                    isEditing = false,
                    title = saved.title,
                    description = saved.description,
                    statusId = saved.statusId,
                    priorityId = saved.priorityId,
                    resolutionId = saved.resolutionId,
                    assigneeId = saved.assigneeId,
                    sprintId = saved.sprintId,
                    labelIds = saved.labelIds,
                    componentIds = saved.componentIds,
                )
            }
        }
    }

    /** Never mind — stay in the editor. */
    fun onCloseKeepEditingTapped() {
        _stateFlow.value = _stateFlow.value.copy(confirmingClose = null)
    }

    /**
     * Close without saving.
     *
     * On a draft this **deletes the issue** — the row only ever existed so the
     * editor had something to attach images to, and an abandoned one must not
     * appear on anyone's board. On a published issue it just closes: nothing
     * was written, so there is nothing to undo.
     */
    private fun closeDiscarding(current: State) {
        if (!current.isDraft) {
            onFinished(hasWritten)
            return
        }
        _stateFlow.value = current.copy(isBusy = true)
        scope.launch {
            runCatching { storage.deleteIssue(issueId) }
                .onFailure {
                    // Not worth stopping the user over, and not worth an error
                    // they cannot act on: the row is a draft, so it is invisible
                    // either way, and the startup sweep takes its files. Logged
                    // because a rash of these would mean something real.
                    println("Issue: discarding draft $issueId failed: ${it.message}")
                }
            // `hasWritten`, not false: a comment posted into a draft that is now
            // being discarded still changed nothing visible — but an image
            // uploaded to it did, and the board's issue list is stale either
            // way once a row has come and gone.
            onFinished(hasWritten)
        }
    }

    /**
     * Close this window because the project is being switched out from under it.
     *
     * The reset-on-switch path (LNL-84), invoked by [EditorDirtyRegistry.closeAllForSwitch]
     * once the switch is going ahead. It does not ask — the switch already asked,
     * once, for the whole app — so it goes straight to [closeDiscarding], which
     * still does the one thing that must not be skipped: deleting a draft's row on
     * the way out, in this window's own scope, before the window is disposed. Any
     * open close-confirm question is cleared first so a stale one cannot resurface.
     */
    fun onSwitchAway() {
        closeDiscarding(_stateFlow.value.copy(confirmingClose = null))
    }

    fun onTitleChanged(value: String) {
        _stateFlow.value = _stateFlow.value.copy(title = value, errorMessage = null)
    }

    fun onDescriptionChanged(value: String) {
        _stateFlow.value = _stateFlow.value.copy(description = value)
    }

    /**
     * The status changed. Clear the resolution if the new column has no use for
     * one.
     *
     * Only the clearing half is done here — a status that DEMANDS a resolution
     * does not get one chosen for it, because picking a default would be the
     * dialog answering a question only the user can. [validationMessage] then
     * blocks OK until they do. See [requiresResolution].
     *
     * The clear matters more than it looks: without it, closing an issue as "Will
     * not fix", changing your mind, and moving it back to In progress would leave
     * the resolution sitting in the state, invisible, ready to be saved. The
     * server drops it anyway (see resolveResolution), so this is about the dialog
     * not lying to itself rather than about the write.
     */
    fun onStatusChanged(id: Long) {
        val state = _stateFlow.value
        val closing = state.statuses.firstOrNull { it.id == id }?.requiresResolution == true
        _stateFlow.value = state.copy(
            statusId = id,
            resolutionId = if (closing) state.resolutionId else null,
        )
    }

    fun onPriorityChanged(id: Long) {
        _stateFlow.value = _stateFlow.value.copy(priorityId = id)
    }

    fun onResolutionChanged(id: Long) {
        _stateFlow.value = _stateFlow.value.copy(resolutionId = id)
    }

    /**
     * The editor's Assignee dropdown. Staged, like every other field here.
     *
     * [UNASSIGNED_ID] comes back as null, which is the only translation this
     * function does — the sentinel exists so a dropdown built on non-null ids can
     * offer "Nobody", and it must not escape past this line into the state or the
     * request. See [State.assigneeOptions].
     */
    /**
     * Stage a sprint choice. Written on Save with everything else.
     *
     * [NO_SPRINT_ID] maps back to null here, which is the one place that sentinel
     * is allowed to stop existing — see its comment.
     */
    fun onSprintChanged(id: Long) {
        _stateFlow.value = _stateFlow.value.copy(sprintId = id.takeIf { it != NO_SPRINT_ID })
    }

    /**
     * Stage a planned- or fixed-version choice, written on Save with everything
     * else (LNL-134). Null clears it — the dropdown's "None" maps here.
     */
    fun onPlannedVersionChanged(id: Long?) {
        _stateFlow.value = _stateFlow.value.copy(plannedVersionId = id)
    }

    fun onFixedVersionChanged(id: Long?) {
        _stateFlow.value = _stateFlow.value.copy(fixedVersionId = id)
    }

    /**
     * Add a version from the dropdown's inline "Add new…" and select it into the
     * field that asked (LNL-134). Writes immediately through the vocabulary route —
     * a project's versions are shared, not this issue's staged edit — so it is
     * gated on [State.canMutateProject], the admin affordance the route enforces.
     * The two entry points differ only in which field the new version lands in.
     */
    fun onPlannedVersionAdded(name: String) = addVersion(name) { copy(plannedVersionId = it) }

    fun onFixedVersionAdded(name: String) = addVersion(name) { copy(fixedVersionId = it) }

    private fun addVersion(name: String, select: State.(Long) -> State) {
        val clean = name.trim()
        if (clean.isBlank()) return
        scope.launch {
            runCatching { storage.addVocabulary(board.project.id, VocabularyKind.VERSION, clean) }.fold(
                onSuccess = { settings ->
                    val items = settings.versions.map { VocabularyItem(it.id, it.name) }
                    val added = settings.versions.firstOrNull { it.name.equals(clean, ignoreCase = true) }
                    val next = _stateFlow.value.copy(versions = items)
                    _stateFlow.value = added?.let { next.select(it.id) } ?: next
                    onWritten()
                },
                onFailure = { t ->
                    _stateFlow.value = _stateFlow.value.copy(
                        errorMessage = t.userMessage("Could not add that version."),
                    )
                },
            )
        }
    }

    /**
     * Delete a version from the dropdown's per-item menu (LNL-134), after the
     * view's confirmation. Admin-gated like [addVersion]. The deleted version is
     * released from this issue's staged planned/fixed choice too, so the dropdown
     * does not keep offering a version that no longer exists.
     */
    fun onVersionDeleted(id: Long) {
        scope.launch {
            runCatching { storage.deleteVocabulary(board.project.id, VocabularyKind.VERSION, id) }.fold(
                onSuccess = { settings ->
                    val items = settings.versions.map { VocabularyItem(it.id, it.name) }
                    val state = _stateFlow.value
                    _stateFlow.value = state.copy(
                        versions = items,
                        plannedVersionId = state.plannedVersionId?.takeIf { it != id },
                        fixedVersionId = state.fixedVersionId?.takeIf { it != id },
                    )
                    onWritten()
                },
                onFailure = { t ->
                    _stateFlow.value = _stateFlow.value.copy(
                        errorMessage = t.userMessage("Could not delete that version."),
                    )
                },
            )
        }
    }

    fun onAssigneeChanged(id: Long) {
        _stateFlow.value = _stateFlow.value.copy(assigneeId = id.takeIf { it != UNASSIGNED_ID })
    }

    /**
     * "Assign to me", and its undo.
     *
     * Writes immediately rather than staging, and that is the deliberate
     * difference from the dropdown above: this button lives on the *reading* face,
     * where there is no Save to press and no unsaved-changes question to raise. A
     * read-mode control that quietly waited for a save that never comes would
     * simply not work.
     *
     * It goes through its own route for the reason that route exists — being
     * assignable is not being allowed to edit — so it must not be reachable while
     * editing, where it would race the staged field. [State.assignButtonLabel]
     * returns null there and the view renders nothing; this re-checks rather than
     * trusting that, because a view model that depends on a view having hidden a
     * control is a view model with an unenforced precondition.
     */
    fun onAssignToMeTapped() {
        val current = _stateFlow.value
        if (current.isBusy || current.isEditing || current.isDraft) return
        if (!current.canBeAssigned) return
        val me = current.callerId ?: return
        // Toggle: let go if I am holding it, take it otherwise.
        val target = if (current.isAssignedToMe) null else me

        _stateFlow.value = current.copy(isBusy = true, errorMessage = null)
        scope.launch {
            runCatching { storage.setIssueAssignee(issueId, target) }.fold(
                onSuccess = { detail ->
                    hasWritten = true
                    // The board shows nothing about assignees today, but this write
                    // stamps updated_at — so the board's ordering and any card that
                    // learns to show a name later are both stale without this.
                    onWritten()
                    // The whole detail, not a patched field: unlike the notification
                    // toggle this genuinely changes the issue, so there is no
                    // in-progress edit to protect (the guard above proved we are not
                    // editing) and the server's copy is the one to believe.
                    _stateFlow.value = detail.applyTo(_stateFlow.value, startEditingIfDraft = false)
                },
                onFailure = { t ->
                    println("Issue: assignment failed: ${t.message}")
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not change who this issue is assigned to."),
                    )
                },
            )
        }
    }

    fun onLabelToggled(id: Long) {
        val current = _stateFlow.value
        _stateFlow.value = current.copy(labelIds = current.labelIds.toggle(id))
    }

    fun onComponentToggled(id: Long) {
        val current = _stateFlow.value
        _stateFlow.value = current.copy(componentIds = current.componentIds.toggle(id))
    }

    private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

    /**
     * Save: publish a draft, or save an edit. One call for both — the server
     * does not distinguish them either, and neither does what happens after:
     * saving closes the window.
     *
     * **Creating a new ticket** (a draft) closes the window (LNL-99): the card is
     * on the board the moment [onWritten] fires, and the read view of a just-typed
     * draft is a window the person was about to close by hand.
     *
     * **Editing an existing issue** now closes it too (LNL-104). Committing an
     * edit is the same gesture — the change is on the board through [onWritten],
     * and leaving the window open in read mode just made the person close it by
     * hand a second time. To go back to read mode without closing, the edit/view
     * toggle is still there; only saving ends the window.
     *
     * Either way the board learns about the write through [onWritten], not through
     * the window closing.
     */
    fun onOkTapped() {
        val current = _stateFlow.value
        if (!current.isOkEnabled) return
        _stateFlow.value = current.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { saveCurrent(current) }
            result.fold(
                onSuccess = {
                    println("Issue: saved $issueId")
                    hasWritten = true
                    onWritten()
                    // Draft or edit, saving closes the window (LNL-99, LNL-104).
                    onFinished(true)
                },
                onFailure = { t ->
                    println("Issue: save failed: ${t.message}")
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not save that issue."),
                    )
                },
            )
        }
    }

    /** The one save call, shared by the Save button and the close dialog's Save. */
    private suspend fun saveCurrent(current: State) {
        storage.saveIssue(
            id = issueId,
            title = current.title,
            description = current.description,
            statusId = current.statusId,
            priorityId = current.priorityId,
            resolutionId = current.resolutionId,
            assigneeId = current.assigneeId,
            sprintId = current.sprintId,
            plannedVersionId = current.plannedVersionId,
            fixedVersionId = current.fixedVersionId,
            labelIds = current.labelIds.toList(),
            componentIds = current.componentIds.toList(),
        )
    }

    fun onDeleteTapped() {
        if (!_stateFlow.value.canDelete) return
        _stateFlow.value = _stateFlow.value.copy(isConfirmingDelete = true)
    }

    fun onDeleteCancelled() {
        _stateFlow.value = _stateFlow.value.copy(isConfirmingDelete = false)
    }

    fun onDeleteConfirmed() {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, isConfirmingDelete = false)
        scope.launch {
            runCatching { storage.deleteIssue(issueId) }.fold(
                onSuccess = {
                    onWritten()
                    onFinished(true)
                },
                onFailure = { t ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not delete that issue."),
                    )
                },
            )
        }
    }

    // ── Comments ─────────────────────────────────────────────────────────────

    /**
     * A comment was posted or deleted by [CommentBackingViewModel].
     *
     * Re-fetches rather than patching the list locally: the server resolves the
     * author's name and whether this caller may edit it, and inventing either
     * here would be the client deciding something the server owns.
     */
    fun onCommentsChanged() {
        hasWritten = true
        onWritten()
        scope.launch { refresh(startEditingIfDraft = false) }
    }

    fun onDeleteCommentTapped(commentId: Long) {
        val comment = _stateFlow.value.comments.firstOrNull { it.id == commentId } ?: return
        if (!comment.canEdit) return
        _stateFlow.value = _stateFlow.value.copy(confirmingDeleteCommentId = commentId)
    }

    fun onDeleteCommentCancelled() {
        _stateFlow.value = _stateFlow.value.copy(confirmingDeleteCommentId = null)
    }

    fun onDeleteCommentConfirmed() {
        val commentId = _stateFlow.value.confirmingDeleteCommentId ?: return
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, confirmingDeleteCommentId = null)
        scope.launch {
            runCatching { storage.deleteComment(commentId) }
                .onFailure { t ->
                    _stateFlow.value = _stateFlow.value.copy(
                        errorMessage = t.userMessage("Could not delete that comment."),
                    )
                }
            hasWritten = true
            refresh(startEditingIfDraft = false)
        }
    }

    /**
     * Upload a file and get back the markdown the editor should insert.
     *
     * The view owns the file picker — that is irreducibly platform work — and
     * hands the bytes here, so nothing downstream knows a `<input type=file>`
     * exists. An iOS client would pass the same three arguments.
     *
     * Any file, not only an image: an issue is routinely half-explained by a
     * crash log, a PDF of the spec, or a screen recording of the thing going
     * wrong, and an editor that takes only screenshots sends all of those to
     * Slack instead. [attachmentMarkdown] decides how each one is spelled.
     *
     * @return the markdown to insert, or null if the upload failed (the error is
     *   already on screen by then).
     */
    suspend fun uploadAttachment(filename: String, mimeType: String, bytes: ByteArray): String? {
        tooLargeMessage(bytes.size.toLong())?.let { message ->
            _stateFlow.value = _stateFlow.value.copy(isBusy = false, errorMessage = message)
            return null
        }
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        return runCatching { storage.uploadIssueAttachment(issueId, filename, mimeType, bytes) }.fold(
            onSuccess = { id ->
                hasWritten = true
                _stateFlow.value = _stateFlow.value.copy(isBusy = false)
                attachmentMarkdown(filename, mimeType, bytes.size.toLong(), id)
            },
            onFailure = { t ->
                println("Issue: upload failed: ${t.message}")
                _stateFlow.value = _stateFlow.value.copy(
                    isBusy = false,
                    errorMessage = t.userMessage("Could not upload that file."),
                )
                null
            },
        )
    }

    /** The board, for a comment dialog that needs it. */
    fun boardState(): BoardState = board

    /** This issue's id, for the comment dialog to hang a draft off. */
    fun currentIssueId(): Long = issueId

    companion object {
        /**
         * The "Nobody" row's id in the assignee dropdown.
         *
         * Zero, because the [Dropdown] this feeds is built on non-null `Long` ids
         * and user ids come from `AUTOINCREMENT`, which starts at 1 — so 0 is a
         * value no account can ever have. The same reasoning the server uses for
         * `-1` in its subscription queries, arriving at a different number because
         * this one also has to be distinguishable from "unset".
         *
         * It exists only between the dropdown and [onAssigneeChanged], which maps
         * it back to null. Nothing in [State.assigneeId], in a request, or in the
         * database ever holds it.
         */
        const val UNASSIGNED_ID: Long = 0L

        /**
         * The "Backlog" row's id in the sprint dropdown.
         *
         * Zero, for [UNASSIGNED_ID]'s reason exactly: `sprints.id` is
         * `AUTOINCREMENT` and starts at 1, so 0 is a value no sprint can hold.
         * It exists only between the dropdown and [onSprintChanged], which maps it
         * back to the null that means the backlog.
         */
        const val NO_SPRINT_ID: Long = 0L
    }
}
