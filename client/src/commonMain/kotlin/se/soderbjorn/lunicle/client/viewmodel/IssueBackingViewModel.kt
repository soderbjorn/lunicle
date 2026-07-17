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
import se.soderbjorn.lunicle.clientserver.IssueDetail
import se.soderbjorn.lunicle.clientserver.StatusItem
import se.soderbjorn.lunicle.clientserver.VocabularyItem

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
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onFinished: (changed: Boolean) -> Unit,
    private val onWritten: () -> Unit = {},
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
        val authorName: String? = null,
        val createdAt: Long = 0,
        val comments: List<CommentView> = emptyList(),
        val canEdit: Boolean = false,
        val canDelete: Boolean = false,
        val canComment: Boolean = false,
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
    ) {
        /** The editable fields as they are on screen right now. */
        val fields: Fields get() = Fields(
            title = title,
            description = description,
            statusId = statusId,
            priorityId = priorityId,
            resolutionId = resolutionId,
            labelIds = labelIds,
            componentIds = componentIds,
        )

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

        val heading: String get() = if (isDraft) "New issue ($ticket)" else ticket

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
         * Why OK is disabled, or null.
         *
         * Labels and components are deliberately absent: "one or more" is read
         * as zero-or-more. The editor offers them; nothing rejects an issue
         * without them. A rule that feels tidy on Monday gets sworn at on
         * Friday, and SQL cannot express a join-table minimum anyway — so the
         * strict reading would be a server check on every write, for no gain.
         *
         * The resolution IS here, and the difference is that it is a rule the
         * server actually has: closing without one is refused there, so an OK
         * that let you try would just be a round-trip to the same answer.
         */
        val validationMessage: String? get() = when {
            title.isBlank() -> "An issue needs a title."
            // An affordance, exactly like every other rule in this file: the
            // server checks again and its 400 wins. It is here so OK is visibly
            // refused with a sentence, rather than failing after the round-trip.
            requiresResolution && resolutionId == null -> "Closing an issue needs a resolution."
            else -> null
        }

        val isOkEnabled: Boolean get() = !isBusy && validationMessage == null

        val confirmDeleteMessage: String get() = "Delete $ticket? This cannot be undone."

        val confirmDeleteCommentMessage: String get() = "Delete this comment? This cannot be undone."

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

    private fun IssueDetail.applyTo(previous: State, startEditingIfDraft: Boolean): State = previous.copy(
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
        statusId = statusId,
        priorityId = priorityId,
        resolutionId = resolutionId,
        labelIds = labelIds.toSet(),
        componentIds = componentIds.toSet(),
        authorName = authorName,
        createdAt = createdAt,
        comments = comments,
        canEdit = canEdit,
        canDelete = canDelete,
        canComment = canComment,
        // The freshly fetched fields are the new dirty-baseline: whatever is
        // on screen after this apply is, by definition, unmodified.
        saved = Fields(
            title = title,
            description = description,
            statusId = statusId,
            priorityId = priorityId,
            resolutionId = resolutionId,
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
    )

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
     * does not distinguish them either.
     *
     * The window stays open. Saving lands the issue back in read mode showing
     * what was just written — the window is the issue now, not a form whose
     * job ends at OK — and the board learns about it through [onWritten]
     * rather than through the window closing.
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
                    _stateFlow.value = _stateFlow.value.copy(isEditing = false)
                    // Re-fetch rather than patch: publishing a draft changes
                    // facts the server owns (isDraft off, the real number),
                    // and the fetch also resets the dirty baseline.
                    refresh(startEditingIfDraft = false)
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
}
