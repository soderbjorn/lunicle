/**
 * Backing view-model for the issue modal: read, edit, comment, delete.
 *
 * The draft contract is the thing to understand here, because it is why this
 * screen is not a plain form:
 *
 *   "New issue" creates the row on the server **before** the modal opens (see
 *   [MainScreenBackingViewModel.onNewIssueTapped]). It has to: the editor can
 *   upload a file, an attachment must have an owner, and the schema's
 *   `CHECK` makes an ownerless attachment unrepresentable rather than merely
 *   unlikely. So there is always a real issue id, and "new" is a *flag on an
 *   existing row* ([State.isDraft]) rather than the absence of one.
 *
 *   The consequence: **Cancel on a draft deletes the issue.** Not "discards
 *   changes" — deletes. And closing the tab instead leaves the row behind, which
 *   is exactly what `is_draft` covers: it stays invisible on every board, and
 *   the startup sweep collects the files behind it.
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
 * Owns the issue modal.
 *
 * @param issueId the issue to open. Always real; see the file's preamble.
 * @param board the board it belongs to, for the label/component/status
 *   vocabularies. Passed in rather than re-fetched: MainScreen already has it,
 *   and asking again would be a round-trip to learn what the caller knows.
 * @param onFinished called when the modal is done; true if anything was written.
 */
class IssueBackingViewModel(
    private val issueId: Long,
    private val board: BoardState,
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onFinished: (changed: Boolean) -> Unit,
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
        val errorMessage: String? = null,
        val statuses: List<StatusItem> = emptyList(),
        val priorities: List<StatusItem> = emptyList(),
        val resolutions: List<StatusItem> = emptyList(),
        val labels: List<VocabularyItem> = emptyList(),
        val components: List<VocabularyItem> = emptyList(),
        val prefix: String = "",
    ) {
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
        errorMessage = null,
        statuses = board.statuses,
        priorities = board.priorities,
        resolutions = board.resolutions,
        labels = board.labels,
        components = board.components,
        prefix = board.project.namePrefix,
    )

    // ── Editing ──────────────────────────────────────────────────────────────

    fun onEditTapped() {
        if (!_stateFlow.value.canEdit) return
        _stateFlow.value = _stateFlow.value.copy(isEditing = true)
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
     * OK: publish a draft, or save an edit. One call for both — the server does
     * not distinguish them either.
     */
    fun onOkTapped() {
        val current = _stateFlow.value
        if (!current.isOkEnabled) return
        _stateFlow.value = current.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching {
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
            result.fold(
                onSuccess = {
                    println("Issue: saved $issueId")
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

    /**
     * Cancel.
     *
     * On a draft this **deletes the issue** — the row only ever existed so the
     * editor had something to attach images to, and an abandoned one must not
     * appear on anyone's board. On a published issue it just closes: the fields
     * were never written, so there is nothing to undo.
     */
    fun onCancelTapped() {
        val current = _stateFlow.value
        if (current.isBusy) return
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
                onSuccess = { onFinished(true) },
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
        scope.launch { refresh(startEditingIfDraft = false) }
    }

    fun onDeleteCommentTapped(commentId: Long) {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true)
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
