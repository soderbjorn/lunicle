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
import kotlinx.coroutines.Job
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
 * @param mentionableNames who the `@` autocomplete may offer here.
 *
 *   Handed in rather than fetched, because the issue this comment belongs to has
 *   already been fetched and carries the list — see `IssueDetail.mentionableUsers`.
 *   A second round-trip would ask the server the same question twice per comment,
 *   and would open the modal with an autocomplete that does not work yet.
 * @param onFinished called when the modal is done; true if anything was written.
 */
class CommentBackingViewModel(
    private val issueId: Long,
    private val editing: Existing? = null,
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    val mentionableNames: List<String> = emptyList(),
    private val onFinished: (changed: Boolean) -> Unit,
    private val editorRegistry: EditorDirtyRegistry = EditorDirtyRegistry(),
) {
    /** An existing comment being edited. */
    data class Existing(val id: Long, val body: String)

    private val _stateFlow = MutableStateFlow(
        State(
            body = editing?.body.orEmpty(),
            isNew = editing == null,
            // The baseline [State.isDirty] compares against: what the comment said
            // when the modal opened (empty for a new one), so a switch only asks
            // about text that was actually typed here.
            savedBody = editing?.body.orEmpty(),
        ),
    )

    init {
        // A comment being typed is unsaved work a project switch would discard — the
        // switch closes the issue window under it — so this modal joins the app-wide
        // dirty registry (LNL-84). It closes through [onCancelTapped], which deletes
        // a new comment's draft row. Dropped when its scope completes. See
        // [EditorDirtyRegistry].
        val registration = editorRegistry.register(
            isDirty = { _stateFlow.value.isDirty },
            discardAndClose = { onCancelTapped() },
        )
        scope.coroutineContext[Job]?.invokeOnCompletion { registration.cancel() }
    }

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
        /** What the comment said when the modal opened; the [isDirty] baseline. */
        val savedBody: String = "",
        val isLoaded: Boolean = false,
        val isBusy: Boolean = false,
        /**
         * An upload is in flight.
         *
         * Deliberately NOT [isBusy], though it is just as much "a request is
         * running". The view disables the editor while the modal is busy, and an
         * upload ends by inserting its markdown *into that editor* — so folding
         * an upload into [isBusy] disabled the very surface the insert was aimed
         * at, and the attachment vanished. (`execCommand("insertHTML")` on a
         * `contenteditable="false"` element does nothing and reports nothing.)
         * That was the whole of "attaching a picture to a comment does not
         * work": the file uploaded fine, and the markdown for it went nowhere.
         *
         * The issue editor never hit this because its editor is enabled by
         * "am I in edit mode", which an upload does not change.
         */
        val isUploading: Boolean = false,
        val errorMessage: String? = null,
    ) {
        val title: String get() = if (isNew) "New comment" else "Edit comment"

        /**
         * Whether anything has been typed here since the modal opened — a new
         * comment with text in it, or an edit whose body has moved off what was
         * saved. Read by [EditorDirtyRegistry] so a project switch asks before
         * throwing this away; blank-and-untouched is not dirty, so an abandoned
         * empty comment closes without a question.
         */
        val isDirty: Boolean get() = body != savedBody

        val validationMessage: String? get() =
            if (body.isBlank()) "A comment needs something in it." else null

        /** Posting mid-upload would save a body the attachment has not landed in yet. */
        val isOkEnabled: Boolean get() = isLoaded && !isBusy && !isUploading && validationMessage == null

        /** The editor stays live through an upload — see [isUploading]. */
        val isEditorEnabled: Boolean get() = isLoaded && !isBusy

        /**
         * Files can only be attached once there is a row to attach them to.
         * Momentary in practice — the draft is created as the modal opens.
         */
        val isAttachEnabled: Boolean get() = isLoaded && !isBusy && !isUploading
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
            _stateFlow.value = _stateFlow.value.copy(isUploading = false, errorMessage = message)
            return null
        }
        _stateFlow.value = _stateFlow.value.copy(isUploading = true, errorMessage = null)
        return runCatching { storage.uploadCommentAttachment(id, filename, mimeType, bytes) }.fold(
            onSuccess = { attachmentId ->
                hasUploaded = true
                _stateFlow.value = _stateFlow.value.copy(isUploading = false)
                attachmentMarkdown(filename, mimeType, bytes.size.toLong(), attachmentId)
            },
            onFailure = { t ->
                _stateFlow.value = _stateFlow.value.copy(
                    isUploading = false,
                    errorMessage = t.userMessage("Could not upload that file."),
                )
                null
            },
        )
    }
}
