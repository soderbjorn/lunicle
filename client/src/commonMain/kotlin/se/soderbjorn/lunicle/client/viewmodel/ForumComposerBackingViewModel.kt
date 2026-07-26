/**
 * Backing view-model for the forum composer — a new post, or a comment on one.
 *
 * ── One class for both, and why that is not over-generalising ───────────────
 *
 * A post and a comment differ by exactly one field. Everything else is identical
 * and is the part with the subtlety in it: create a draft row so an upload has an
 * owner, keep the editor live through an upload, publish, and delete the draft on
 * cancel. Two classes would be two copies of that dance, and the second copy is
 * the one that gets the `isUploading` distinction wrong — see [State.isUploading],
 * which is a bug this codebase has already had once.
 *
 * So the difference is a sealed [Target] and a nullable title, and nothing else
 * branches.
 *
 * ── The draft contract, and why the row is now created late ─────────────────
 *
 * [CommentBackingViewModel] and [IssueBackingViewModel] create the draft row up
 * front, so the image button has something to attach to; Cancel deletes it. A
 * draft is invisible to every reader, and the server's startup sweep takes the
 * files behind one that was abandoned by closing the tab.
 *
 * This one creates it at the first moment something actually needs one — the
 * first attach, or the submit — because since the composers became **panes**
 * neither of them is short-lived any more:
 *
 *  - the comment composer is an inline bar at the foot of the post window, so it
 *    exists for as long as somebody has the post open. Minting a row on open would
 *    write an empty comment every time anybody merely *read* a thread.
 *  - the new-post pane is a window somebody may leave open while they go and read
 *    something else, which is the same argument one step weaker.
 *
 * Eager creation was right when both were modals, which is what LNL-61 built and
 * what the up-front `start()` served. [MessageComposerBackingViewModel.ensureDraft]
 * reached the same shape one tab over, for the stronger version of the first
 * reason; the two now differ only in what a draft row *is*.
 *
 * The visible consequence is that Post and Comment are live the moment the pane
 * appears, rather than after a round-trip. That is a gain: there is no longer an
 * `isLoaded` gate greying a button on a form nobody has typed into yet.
 *
 * There is one deliberate difference from the comment modal. **There is no
 * `editing` mode.** LNL-61 builds no way to edit a published post or comment —
 * the routes accept it from the author, because publishing and re-saving are the
 * same statement, but nothing offers it. Adding it later is a second [Target]
 * case, not a rewrite.
 *
 * @see se.soderbjorn.lunicle.clientserver.ForumPostDetail
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
import se.soderbjorn.lunicle.client.userMessage
import se.soderbjorn.lunicle.clientserver.ForumPostDetail
import se.soderbjorn.lunicle.clientserver.MAX_POST_TITLE_LENGTH
import se.soderbjorn.lunicle.clientserver.tooLargeMessage

/**
 * Owns the composer.
 *
 * @param projectId, [forumId] where this is being written. Both, because the
 *   routes take both — see [ForumPostBackingViewModel].
 * @param target a new post in this forum, or a comment on one of its posts.
 * @param mentionableNames who the `@` autocomplete may offer, handed in rather
 *   than fetched.
 *
 *   For a comment it came off the post, which was fetched before this could open.
 *   For a **new post** there is no post to have carried it, so it comes off the
 *   forum's post list instead — which is why `ForumPostListState` carries a set of
 *   names that has nothing to do with any post in it. Fetching either one when the
 *   composer opens would put the pane up with an autocomplete that silently does
 *   not work yet.
 * @param onFinished called when the composer is done. Carries the refreshed post
 *   when the server sent one — publishing a comment answers with the whole post —
 *   so the thread above the composer updates without a second round-trip.
 */
class ForumComposerBackingViewModel(
    private val projectId: Long,
    private val forumId: Long,
    private val target: Target,
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    val mentionableNames: List<String> = emptyList(),
    private val onFinished: (changed: Boolean, detail: ForumPostDetail?) -> Unit,
    private val editorRegistry: EditorDirtyRegistry = EditorDirtyRegistry(),
) {
    init {
        // A forum post/comment in progress is unsaved work a project switch would
        // discard, so this composer joins the app-wide dirty registry — LNL-84. The
        // switch closes it through [onCancelTapped], which deletes any draft row it
        // owns. Dropped when its scope completes, so a closed composer leaves no
        // stale entry. See [EditorDirtyRegistry].
        val registration = editorRegistry.register(
            isDirty = { _stateFlow.value.isDirty },
            discardAndClose = { onCancelTapped() },
        )
        scope.coroutineContext[Job]?.invokeOnCompletion { registration.cancel() }
    }
    /** What is being written. */
    sealed interface Target {
        /** A new post in the forum this composer was opened from. */
        data object NewPost : Target

        /** A comment on [postId]. */
        data class NewComment(val postId: Long) : Target
    }

    private val _stateFlow = MutableStateFlow(State(hasTitle = target is Target.NewPost))

    /** The current composer state, observed by the view. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /** The draft row this is writing into. Null until [ensureDraft] creates it. */
    private var draftId: Long? = null

    /** Whether a file was uploaded, so Cancel knows something happened. */
    private var hasUploaded = false

    data class State(
        val title: String = "",
        val body: String = "",
        val hasTitle: Boolean = true,
        val isBusy: Boolean = false,
        /**
         * An upload is in flight.
         *
         * Deliberately NOT [isBusy], for the reason [CommentBackingViewModel]
         * documents at length: the view disables the editor while the composer is
         * busy, and an upload ends by inserting its markdown *into that editor*, so
         * folding the two together disables the very surface the insert is aimed
         * at and the attachment vanishes with no error anywhere. That was the whole
         * of "attaching a picture to a comment does not work", and it is repeated
         * here because this is the second composer and the trap is the same one.
         */
        val isUploading: Boolean = false,
        val errorMessage: String? = null,
        /**
         * Whether the writer has typed anything into this composer yet.
         *
         * Only [visibleValidationMessage] reads it, and only to stay quiet on an
         * untouched form — see there.
         */
        val isDirty: Boolean = false,
    ) {
        val heading: String get() = if (hasTitle) "New post" else "New comment"

        /**
         * What the submit button says.
         *
         * The design's two words, and they are verbs about the thing being made
         * rather than "Save" twice: the new-post pane says **Post**, the inline bar
         * under a thread says **Comment**. Spelled here rather than in the two views
         * so the pair cannot drift.
         */
        val submitLabel: String get() = if (hasTitle) "Post" else "Comment"

        val validationMessage: String? get() = when {
            hasTitle && title.isBlank() -> "A post needs a title."
            hasTitle && title.trim().length > MAX_POST_TITLE_LENGTH ->
                "A post title may be at most $MAX_POST_TITLE_LENGTH characters."
            body.isBlank() -> "There is nothing in that yet."
            else -> null
        }

        /**
         * The validation message, or null while the composer is still untouched.
         *
         * [validationMessage] answers "why is the submit button disabled", and an
         * empty composer is disabled from the moment it opens — so rendering it
         * raw greets somebody who has just pressed **New post** with "A post needs
         * a title." before they have typed a character. That is scolding a person
         * for not having done a thing they are visibly in the middle of doing.
         *
         * The button is already disabled and already says what it would do, so
         * nothing is lost by waiting. As soon as there is any content the message
         * comes back and does its real job: telling a writer with a body and no
         * title why **Post** will not light up.
         *
         * Kept separate from [validationMessage] rather than folding the check
         * into it, because [isOkEnabled] must go on asking the unconditional
         * question — a form that submitted while empty because nobody had typed
         * yet would be the obvious way to get this wrong.
         */
        val visibleValidationMessage: String? get() = validationMessage.takeIf { isDirty }

        /** Posting mid-upload would save a body the attachment has not landed in yet. */
        val isOkEnabled: Boolean get() = !isBusy && !isUploading && validationMessage == null

        /** The editor stays live through an upload — see [isUploading]. */
        val isEditorEnabled: Boolean get() = !isBusy

        /**
         * Whether the attach button does anything.
         *
         * No longer gated on a row existing: [ensureDraft] makes one when it is
         * pressed, so the answer is only about whether this composer is mid-request.
         */
        val isAttachEnabled: Boolean get() = !isBusy && !isUploading
    }

    fun onTitleChanged(value: String) {
        _stateFlow.value = _stateFlow.value.copy(title = value, errorMessage = null, isDirty = true)
    }

    fun onBodyChanged(value: String) {
        _stateFlow.value = _stateFlow.value.copy(body = value, errorMessage = null, isDirty = true)
    }

    fun onOkTapped() {
        val current = _stateFlow.value
        if (!current.isOkEnabled) return
        _stateFlow.value = current.copy(isBusy = true, errorMessage = null)
        scope.launch {
            runCatching {
                val id = ensureDraft()
                when (target) {
                    Target.NewPost ->
                        storage.publishForumPost(projectId, forumId, id, current.title, current.body)
                    is Target.NewComment ->
                        storage.publishForumComment(projectId, forumId, target.postId, id, current.body)
                }
            }.fold(
                // The whole post comes back either way, and it is handed on rather
                // than discarded: a comment's reader is directly above this composer
                // and can render the new comment from it without asking again.
                onSuccess = { detail -> onFinished(true, detail) },
                onFailure = { failure ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = failure.userMessage("Could not post that."),
                    )
                },
            )
        }
    }

    /**
     * Cancel.
     *
     * Deletes the draft row — it only ever existed to own an upload. The delete is
     * a post delete or a comment delete depending on the target, and both cascade
     * the files with them.
     *
     * Most cancels now have nothing to undo at all, because [ensureDraft] only runs
     * on an attach or a submit — closing a composer somebody typed two words into
     * and thought better of touches the server not at all.
     *
     * A failure here is swallowed for [CommentBackingViewModel]'s reason: the row
     * is a draft, so it is invisible either way, and the startup sweep takes its
     * files. Blocking somebody inside a pane they have already closed, over a row
     * nobody can see, is worse than the leak.
     */
    fun onCancelTapped() {
        val current = _stateFlow.value
        if (current.isBusy) return
        val id = draftId
        if (id == null) {
            onFinished(hasUploaded, null)
            return
        }
        _stateFlow.value = current.copy(isBusy = true)
        scope.launch {
            runCatching {
                when (target) {
                    Target.NewPost -> storage.deleteForumPost(projectId, forumId, id)
                    is Target.NewComment ->
                        storage.deleteForumComment(projectId, forumId, target.postId, id)
                }
            }.onFailure { println("Forum: discarding draft $id failed: ${it.message}") }
            onFinished(hasUploaded, null)
        }
    }

    /**
     * Upload a file of any kind; returns the markdown to insert, or null on
     * failure. The view owns the file picker. See
     * [IssueBackingViewModel.uploadAttachment].
     *
     * This is one of the two calls that creates the draft row, if the submit has
     * not already — an attachment needs something to belong to, and this is the
     * first moment one is genuinely required. See this class's preamble.
     */
    suspend fun uploadAttachment(filename: String, mimeType: String, bytes: ByteArray): String? {
        tooLargeMessage(bytes.size.toLong())?.let { message ->
            _stateFlow.value = _stateFlow.value.copy(isUploading = false, errorMessage = message)
            return null
        }
        _stateFlow.value = _stateFlow.value.copy(isUploading = true, errorMessage = null)
        return runCatching {
            val id = ensureDraft()
            when (target) {
                Target.NewPost -> storage.uploadForumPostAttachment(id, filename, mimeType, bytes)
                is Target.NewComment -> storage.uploadForumCommentAttachment(id, filename, mimeType, bytes)
            }
        }.fold(
            onSuccess = { attachmentId ->
                hasUploaded = true
                _stateFlow.value = _stateFlow.value.copy(isUploading = false)
                attachmentMarkdown(filename, mimeType, bytes.size.toLong(), attachmentId)
            },
            onFailure = { failure ->
                _stateFlow.value = _stateFlow.value.copy(
                    isUploading = false,
                    errorMessage = failure.userMessage("Could not upload that file."),
                )
                null
            },
        )
    }

    /**
     * The draft row to write into, creating it if this composer has not needed one
     * yet.
     *
     * Idempotent, and it has to be: the submit and the attach button both call it,
     * and a second call must not leave a second empty post in the forum.
     *
     * Throws rather than returning null on a failed creation, unlike
     * [MessageComposerBackingViewModel.ensureDraft], which has a null case because a
     * new conversation genuinely may have nobody in it yet. There is no such state
     * here — a post always has a forum to go in — so a failure is a failure, and
     * both call sites already run inside a `runCatching` that turns it into the
     * sentence the reader sees.
     */
    private suspend fun ensureDraft(): Long {
        draftId?.let { return it }
        val id = when (target) {
            Target.NewPost -> storage.createForumPostDraft(projectId, forumId)
            is Target.NewComment -> storage.createForumCommentDraft(projectId, forumId, target.postId)
        }
        draftId = id
        return id
    }
}
