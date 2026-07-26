/**
 * Backing view-model for **one** forum post: what it says, what people said back,
 * and the two things a reader may do to it.
 *
 * ── Why one post gets a view model of its own ───────────────────────────────
 *
 * [ForumBackingViewModel] knows which post is open and deliberately nothing about
 * what is in it. That split is the seam LNL-62 lifts through: reading a post moves
 * from inline in the Discussion pane into a window, and a window needs an owner of
 * its own state — one that can be constructed from three ids and nothing else,
 * because a window opened from a deep link has no pane behind it to inherit from.
 *
 * So the constructor takes exactly the ids in the URL, and the response carries
 * everything the view needs including the forum's *name*. Nothing here reads the
 * forum list, and nothing here reads the board.
 *
 * ── What it deliberately does not own ───────────────────────────────────────
 *
 * **Writing.** Posting a comment is [ForumComposerBackingViewModel]'s, which owns
 * the draft row and the uploads. This one is the reading surface plus deletion,
 * and deletion is here rather than there because it is the one write that has no
 * composer — it is a button on something already on screen.
 *
 * **Unread state, and windowing.** LNL-64 and LNL-62.
 *
 * Watching *is* here since LNL-63 — [onNotificationToggled] — and it is the one
 * write in this class that is not about the post's contents at all. It lives here
 * rather than in a view model of its own because the two facts it renders arrive
 * on [ForumPostDetail], which this class already owns, and because the pill sits
 * on the post's own title row.
 *
 * @see ForumBackingViewModel
 * @see se.soderbjorn.lunicle.clientserver.ForumPostDetail
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
import se.soderbjorn.lunicle.client.formatTimestamp
import se.soderbjorn.lunicle.client.userMessage
import se.soderbjorn.lunicle.clientserver.ForumCommentView
import se.soderbjorn.lunicle.clientserver.ForumPostDetail

/**
 * Owns one post.
 *
 * @param projectId, [forumId], [postId] the three ids that name it. All three,
 *   rather than the post id alone, because that is what the route takes — the
 *   project and forum in the path are claims the server checks, which is what
 *   stops a post id from another project being readable through this one.
 * @param storage the client's repository; the only collaborator.
 * @param onFinished called when the post is gone, or when the reader is done with
 *   it. `true` if anything was written while it was open, so the host knows the
 *   forum's post list — which carries a comment count per row — is stale.
 */
class ForumPostBackingViewModel(
    // Public, all three, because the inline comment composer at the foot of the post
    // window is built from exactly the ids that name this post — see
    // `ForumPostView`, which reads them off here rather than being handed the same
    // three a second time by whoever constructed both.
    val projectId: Long,
    val forumId: Long,
    val postId: Long,
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onFinished: (changed: Boolean) -> Unit = {},
) {
    private val _stateFlow = MutableStateFlow(State())

    /** The current post state, observed by the view. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * Whether anything was written while this post was open.
     *
     * Kept outside [State] because it is not something the view renders — it is a
     * fact about the session that only matters at the moment this closes. Putting
     * it on the state would invite a render to depend on it.
     */
    private var changed = false

    /**
     * Immutable snapshot of one post.
     *
     * @property detail the post as the server last described it, or null before the
     *   first answer. Null rather than an empty [ForumPostDetail] with blank
     *   strings, so "loading" and "a post whose title is empty" cannot be confused
     *   — the second is unrepresentable anyway, since the server refuses a blank
     *   title, and a placeholder object would quietly make it look possible.
     * @property isBusy true while a request is in flight.
     * @property errorMessage a human-readable failure, or null.
     * @property confirmingDeletePost whether the "delete this post?" confirmation is
     *   up. A post takes its whole comment thread with it, so it is confirmed —
     *   the issue window confirms a comment delete for the smaller version of the
     *   same reason.
     * @property confirmingDeleteCommentId which comment's confirmation is up, or
     *   null.
     */
    data class State(
        val detail: ForumPostDetail? = null,
        val isBusy: Boolean = false,
        val errorMessage: String? = null,
        val confirmingDeletePost: Boolean = false,
        val confirmingDeleteCommentId: Long? = null,
    ) {
        /** Whether the first fetch has returned. See [detail]. */
        val isLoaded: Boolean get() = detail != null

        /** Who may be `@`-mentioned here, as bare names for the editor. */
        val mentionableNames: List<String> get() = detail?.mentionableUsers?.map { it.name }.orEmpty()

        /**
         * The post's attribution line: "by Robert · 17 Jul 2026, 14:32 · in General".
         *
         * Spelled here rather than on the wire type, for `IssueBackingViewModel`'s
         * reason: the server has no opinion about how a date is written or what a
         * deleted account is called.
         *
         * The forum's name is on the end, and since LNL-62 it earns its place
         * rather than merely matching the design: a post opened from a deep link is
         * a **window**, with no forum list behind it to say which room this is.
         * [ForumPostDetail] carries `forumName` for exactly that. Omitted when the
         * server did not send one, which is only ever an older build answering — a
         * trailing "· in " would be worse than the fact being absent.
         */
        val byline: String get() {
            val who = detail?.authorName ?: "A deleted account"
            val forum = detail?.forumName?.takeIf { it.isNotBlank() }
            return "by $who · ${formatTimestamp(detail?.createdAt ?: 0)}" +
                (forum?.let { " · in $it" } ?: "")
        }

        /** The agent badge's text, or null when a human wrote it. See [byline]. */
        val agentBadge: String? get() = detail?.agentName?.let { "Agent · $it" }

        /** One comment's attribution line. Same shape as [byline], for its reasons. */
        fun commentByline(comment: ForumCommentView): String =
            "${comment.authorName ?: "A deleted account"} · ${formatTimestamp(comment.createdAt)}"

        /** One comment's agent badge, or null. */
        fun commentAgentBadge(comment: ForumCommentView): String? = comment.agentName?.let { "Agent · $it" }

        /**
         * The initial in a comment's avatar circle, as the design shows.
         *
         * A letter rather than a picture: no provider avatar is stored anywhere in
         * this schema, and adding one to render a 24px circle would be a column, a
         * fetch and a cache for decoration. "?" for a deleted account, which is the
         * honest answer rather than a blank circle that reads as a loading state.
         */
        fun commentInitial(comment: ForumCommentView): String =
            comment.authorName?.trim()?.firstOrNull()?.uppercase() ?: "?"

        /** What the confirmation asks. Names the thread's size, because it goes too. */
        val confirmDeletePostMessage: String get() {
            val comments = detail?.comments?.size ?: 0
            return when (comments) {
                0 -> "Delete this post? This cannot be undone."
                1 -> "Delete this post and its one comment? This cannot be undone."
                else -> "Delete this post and its $comments comments? This cannot be undone."
            }
        }
    }

    /** Fetch the post. Called by the view on mount. */
    fun start() = refresh()

    fun refresh() {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            runCatching { storage.forumPost(projectId, forumId, postId) }.fold(
                onSuccess = { detail ->
                    _stateFlow.value = _stateFlow.value.copy(detail = detail, isBusy = false)
                },
                onFailure = { failure ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = failure.userMessage("Could not load that post."),
                    )
                },
            )
        }
    }

    /**
     * Take a detail this view model did not fetch.
     *
     * The composer answers with the whole post after publishing a comment, and
     * handing that straight over is what makes a new comment appear without a
     * second round-trip for something the server has already sent. It also marks
     * the post as changed, so closing it refreshes the list's comment count.
     */
    fun onDetailReceived(detail: ForumPostDetail) {
        changed = true
        _stateFlow.value = _stateFlow.value.copy(detail = detail, isBusy = false, errorMessage = null)
    }

    /** The reader pressed back, or closed the window. */
    fun onCloseTapped() = onFinished(changed)

    fun onDeletePostTapped() {
        _stateFlow.value = _stateFlow.value.copy(confirmingDeletePost = true)
    }

    fun onDeletePostCancelled() {
        _stateFlow.value = _stateFlow.value.copy(confirmingDeletePost = false)
    }

    /**
     * Delete the post.
     *
     * Ends by calling [onFinished] rather than re-rendering: the thing this view
     * model is about no longer exists, and a state that described it would be a
     * view onto rows the server has cascaded away.
     */
    fun onDeletePostConfirmed() {
        _stateFlow.value = _stateFlow.value.copy(confirmingDeletePost = false, isBusy = true)
        scope.launch {
            runCatching { storage.deleteForumPost(projectId, forumId, postId) }.fold(
                onSuccess = { onFinished(true) },
                onFailure = { failure ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = failure.userMessage("Could not delete that post."),
                    )
                },
            )
        }
    }

    fun onDeleteCommentTapped(commentId: Long) {
        _stateFlow.value = _stateFlow.value.copy(confirmingDeleteCommentId = commentId)
    }

    fun onDeleteCommentCancelled() {
        _stateFlow.value = _stateFlow.value.copy(confirmingDeleteCommentId = null)
    }

    fun onDeleteCommentConfirmed() {
        val commentId = _stateFlow.value.confirmingDeleteCommentId ?: return
        _stateFlow.value = _stateFlow.value.copy(confirmingDeleteCommentId = null, isBusy = true)
        scope.launch {
            runCatching { storage.deleteForumComment(projectId, forumId, postId, commentId) }.fold(
                // The whole post comes back, comments included, so there is nothing
                // to patch locally and nothing to be wrong about.
                onSuccess = { onDetailReceived(it) },
                onFailure = { failure ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = failure.userMessage("Could not delete that comment."),
                    )
                },
            )
        }
    }

    /**
     * The Watch pill was pressed: follow this post's comments, or stop.
     *
     * Takes the whole refreshed [ForumPostDetail] back rather than flipping a
     * local flag, exactly as the delete paths do. The state that matters is the
     * server's — an author arrives at their own post already watching it, without
     * anybody having pressed anything, so a client that maintained its own answer
     * would start out disagreeing.
     *
     * Deliberately does **not** set [changed]. Nothing about the post or its
     * comments moved, so the forum list behind the window is not stale — and
     * re-fetching it because somebody managed their own inbox would be a request
     * for nothing.
     */
    fun onNotificationToggled(subscribed: Boolean) {
        val current = _stateFlow.value
        if (current.isBusy) return
        _stateFlow.value = current.copy(isBusy = true, errorMessage = null)
        scope.launch {
            runCatching { storage.setForumPostNotification(projectId, forumId, postId, subscribed) }.fold(
                onSuccess = { detail ->
                    _stateFlow.value = _stateFlow.value.copy(detail = detail, isBusy = false)
                },
                onFailure = { failure ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        // Usually "add an e-mail address to your profile first",
                        // which is actionable — so it is shown, not logged.
                        errorMessage = failure.userMessage("Could not change your notification setting."),
                    )
                },
            )
        }
    }

    /** Dismiss the failure line. */
    fun onErrorDismissed() {
        _stateFlow.value = _stateFlow.value.copy(errorMessage = null)
    }
}
