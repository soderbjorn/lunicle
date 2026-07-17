/**
 * Backing view-model for the comment modal.
 *
 * The same draft contract as the issue editor, for the same reason: the comment
 * row is created before the modal opens so an uploaded file has an owner, and
 * Cancel deletes it. See [IssueBackingViewModel]'s preamble — this is the small
 * version of that story, and the only difference is that a comment has one field
 * instead of six.
 *
 * @see StorageRepository
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
import se.soderbjorn.lunicle.client.attachmentMarkdown
import se.soderbjorn.lunicle.clientserver.tooLargeMessage
import se.soderbjorn.lunicle.client.userMessage

/**
 * Owns the comment modal.
 *
 * @param issueId the issue being commented on.
 * @param editing an existing comment's id and body, or null when posting a new
 *   one. Editing skips the draft dance entirely — the row is already published,
 *   so Cancel must not delete it.
 * @param onFinished called when the modal is done; true if anything was written.
 */
class CommentBackingViewModel(
    private val issueId: Long,
    private val editing: Existing? = null,
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onFinished: (changed: Boolean) -> Unit,
) {
    /** An existing comment being edited. */
    data class Existing(val id: Long, val body: String)

    private val _stateFlow = MutableStateFlow(
        State(body = editing?.body.orEmpty(), isNew = editing == null),
    )

    /** The current modal state, observed by the view. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * The row this modal is writing into.
     *
     * For an edit it is known up front. For a new comment it does not exist
     * until [start] creates the draft — which is why the attach button is
     * disabled until [State.isLoaded].
     */
    private var commentId: Long? = editing?.id

    /** Whether a file was uploaded, so Cancel knows the board is stale. */
    private var hasUploaded = false

    data class State(
        val body: String = "",
        val isNew: Boolean = true,
        val isLoaded: Boolean = false,
        val isBusy: Boolean = false,
        val errorMessage: String? = null,
    ) {
        val title: String get() = if (isNew) "New comment" else "Edit comment"

        val validationMessage: String? get() =
            if (body.isBlank()) "A comment needs something in it." else null

        val isOkEnabled: Boolean get() = isLoaded && !isBusy && validationMessage == null

        /**
         * Files can only be attached once there is a row to attach them to.
         * Momentary in practice — the draft is created as the modal opens.
         */
        val isAttachEnabled: Boolean get() = isLoaded && !isBusy
    }

    /** Create the draft row, if this is a new comment. Called by the view on mount. */
    fun start() {
        if (editing != null) {
            _stateFlow.value = _stateFlow.value.copy(isLoaded = true)
            return
        }
        _stateFlow.value = _stateFlow.value.copy(isBusy = true)
        scope.launch {
            runCatching { storage.createCommentDraft(issueId) }.fold(
                onSuccess = { draft ->
                    commentId = draft.id
                    _stateFlow.value = _stateFlow.value.copy(isLoaded = true, isBusy = false)
                },
                onFailure = { t ->
                    println("Comment: could not start a draft: ${t.message}")
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not start a comment."),
                    )
                },
            )
        }
    }

    fun onBodyChanged(value: String) {
        _stateFlow.value = _stateFlow.value.copy(body = value, errorMessage = null)
    }

    fun onOkTapped() {
        val current = _stateFlow.value
        if (!current.isOkEnabled) return
        val id = commentId ?: return
        _stateFlow.value = current.copy(isBusy = true, errorMessage = null)
        scope.launch {
            runCatching { storage.saveComment(id, current.body) }.fold(
                onSuccess = { onFinished(true) },
                onFailure = { t ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not post that comment."),
                    )
                },
            )
        }
    }

    /**
     * Cancel.
     *
     * Deletes the draft row on a new comment — it only existed to own an image.
     * An edit just closes; its row was published before this modal opened.
     */
    fun onCancelTapped() {
        val current = _stateFlow.value
        if (current.isBusy) return
        val id = commentId
        if (editing != null || id == null) {
            onFinished(hasUploaded)
            return
        }
        _stateFlow.value = current.copy(isBusy = true)
        scope.launch {
            runCatching { storage.deleteComment(id) }
                .onFailure {
                    // Same reasoning as discarding an issue draft: the row is a
                    // draft, so it is invisible either way, and the startup
                    // sweep takes its files. Not worth blocking the user on.
                    println("Comment: discarding draft $id failed: ${it.message}")
                }
            onFinished(hasUploaded)
        }
    }

    /**
     * Upload a file of any kind; returns the markdown to insert, or null on
     * failure. The view owns the file picker. See
     * [IssueBackingViewModel.uploadAttachment].
     */
    suspend fun uploadAttachment(filename: String, mimeType: String, bytes: ByteArray): String? {
        val id = commentId ?: return null
        tooLargeMessage(bytes.size.toLong())?.let { message ->
            _stateFlow.value = _stateFlow.value.copy(isBusy = false, errorMessage = message)
            return null
        }
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        return runCatching { storage.uploadCommentAttachment(id, filename, mimeType, bytes) }.fold(
            onSuccess = { attachmentId ->
                hasUploaded = true
                _stateFlow.value = _stateFlow.value.copy(isBusy = false)
                attachmentMarkdown(filename, mimeType, bytes.size.toLong(), attachmentId)
            },
            onFailure = { t ->
                _stateFlow.value = _stateFlow.value.copy(
                    isBusy = false,
                    errorMessage = t.userMessage("Could not upload that file."),
                )
                null
            },
        )
    }
}
