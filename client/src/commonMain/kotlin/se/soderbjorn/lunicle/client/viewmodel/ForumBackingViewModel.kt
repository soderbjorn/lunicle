/**
 * The Discussion tab: which forums a project has, which one is selected, and the
 * administrator's controls over the list.
 *
 * The project convention, as everywhere in this package: all the logic, one
 * immutable [State] over a single [StateFlow], and no platform in sight.
 *
 * ── Why the project arrives from outside ────────────────────────────────────
 *
 * This view model never picks a project. Which project the app is on is
 * [MainScreenBackingViewModel]'s answer — it owns the picker, the URL parameter
 * and the resolution order — and a second view model that fetched its own would
 * eventually disagree with the first, which the user would see as a Discussion
 * tab about a project the board is not showing. So the coupling is explicit:
 * `main.kt` tells this one which project the board landed on, through
 * [onProjectChanged], and that is the only way the project ever changes.
 *
 * That also settles what happens with no project at all — a fresh instance, or a
 * signed-out visitor with nothing public to read. [State.projectId] is null, the
 * pane says so, and nothing is fetched.
 *
 * @see se.soderbjorn.lunicle.clientserver.ForumListState
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
import se.soderbjorn.lunicle.client.formatRelative
import se.soderbjorn.lunicle.client.formatTimestamp
import se.soderbjorn.lunicle.client.userMessage
import se.soderbjorn.lunicle.clientserver.ForumListState
import se.soderbjorn.lunicle.clientserver.ForumPostListState
import se.soderbjorn.lunicle.clientserver.ForumPostSummary
import se.soderbjorn.lunicle.clientserver.ForumSummary

/**
 * Owns the forum list for whichever project the app is on.
 *
 * @param storage the client's repository; the only collaborator, so this view
 *   model never mentions HTTP.
 * @param scope coroutine scope the requests run in.
 */
class ForumBackingViewModel(
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _stateFlow = MutableStateFlow(State())

    /** The current forum state, observed by the pane. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * The forum and post a deep link asked for, held until the forum list arrives.
     *
     * `main.kt` reads `?forum=` and `?post=` at load and hands them over
     * immediately, but at that moment this view model does not yet know which
     * project it is on — the board resolves that, and tells it through
     * [onProjectChanged], which throws the state away. So they are remembered here
     * and applied exactly once, on the first forum list that comes back.
     *
     * That wait is not avoidable the way `MessagesBackingViewModel` avoids its
     * own: a conversation is fetchable from its id alone, and a post needs the
     * project the board is still resolving. It costs one round-trip on a cold deep
     * link, and in exchange the picker behind the window is already pointing at the
     * right forum when the window appears.
     */
    private var pendingForumId: Long? = null
    private var pendingPostId: Long? = null

    /**
     * The comment a deep link asked to land on, held until the post has loaded.
     *
     * Outside [State] for `MessagesBackingViewModel.pendingMessageId`'s reason: it
     * is a one-shot instruction spent by being read, not something a render should
     * be able to depend on. `?comment=` is a position inside a view rather than a
     * view, so it is read at load and never written back — see `AppUrl.nextSearch`.
     */
    private var pendingCommentId: Long? = null

    /**
     * Immutable snapshot of the Discussion tab.
     *
     * @property projectId the project whose forums these are, or null when the
     *   app is on no project at all. Carried rather than implied, because every
     *   write needs it and reading it off the board a second time is how the two
     *   drift apart.
     * @property forums in the administrator's chosen order — never sorted here.
     *   The order is a curation decision the server holds; see Forums.sq.
     * @property selectedForumId which forum's contents the pane shows. Null when
     *   there are none. Kept across a refresh where it still exists, so that
     *   creating a forum does not throw the reader out of the one they were in.
     * @property canManageForums whether to render the administrative controls. An
     *   affordance and nothing more: the routes refuse a caller who lacks it
     *   regardless, so flipping this buys a 403 rather than a forum.
     * @property isLoaded whether the first fetch has returned. Before it has, the
     *   pane shows nothing rather than "no forums yet" — telling somebody their
     *   project is empty before asking is worse than a moment of nothing.
     * @property isBusy true while a request is in flight.
     * @property errorMessage a human-readable failure, or null. The refusals this
     *   surfaces are all things the person typing can fix — a duplicate name, a
     *   blank one — which is why they are shown rather than logged.
     * @property posts the selected forum's posts, newest first — the server's
     *   order, never re-sorted here. Unlike [forums] this is a chronology rather
     *   than somebody's curation; see ForumPosts.sq.
     * @property arePostsLoaded whether the post list for the *current* selection
     *   has come back. Separate from [isLoaded], which is about the forum list: the
     *   two arrive from different requests, and folding them together would make
     *   switching forums flash "no forums yet" over a project that has six.
     * @property canPost whether to offer "New post" and "Add comment". One flag for
     *   both, because LNL-30 gives them one rule. An affordance; the routes refuse
     *   regardless.
     * @property mentionableNames who the new-post composer's `@` autocomplete may
     *   offer — everyone who can see the project. Held here rather than fetched
     *   when the composer opens, because a pane that puts an autocomplete up and
     *   then makes it work a moment later is worse than one that works when it
     *   appears. A *comment's* names come off the post instead; see
     *   `ForumPostBackingViewModel`.
     * @property notifyOnNewPosts whether the caller is watching the **selected**
     *   forum. Follows the selection, because it arrives with the post list — see
     *   `ForumPostListState.notifyOnNewPosts` for why it rides there rather than
     *   on the forum list.
     * @property canReceiveEmailNotifications whether the caller has an address at
     *   all. The Watch pill is hidden without one rather than shown disabled: a
     *   control that can never do anything is worse than no control, and the fix
     *   is somewhere else entirely (the settings pane's You tab). Same position the issue
     *   window takes.
     * @property openPostId the post being read, or null for the list.
     *
     *   An **id** rather than the post itself, and that is the seam LNL-62 lifts
     *   through: this view model knows *which* post is open and nothing about what
     *   is in it. The detail is owned by a `ForumPostBackingViewModel` built for
     *   that id, so moving the reading surface from inline in this pane into a
     *   window of its own is a change of host, not a change of ownership.
     * @property isPostFocused whether the post *window* is the focused pane of the
     *   Discussion tab, rather than the forum list behind it.
     *
     *   Here rather than in `main.kt` for the reason
     *   [MainScreenBackingViewModel.State.focusedIssueId] is on the board's state:
     *   the pane snapshot has to say which pane is active, and if the snapshot's
     *   answer did not follow the window the user actually pressed, the next push
     *   would yank focus back to a window they had just clicked away from. Opening
     *   a post sets it, because opening something is focusing it; the panes'
     *   mousedown listeners maintain it after that.
     * @property isComposingPost whether the reader is writing a **new post** rather
     *   than reading one.
     *
     *   The two are exclusive and share the same window, which is the answer to
     *   "where does the composer go" that the owner gave after LNL-30's run: the
     *   WYSIWYG editor belongs in a pane, because that is where the issue editor
     *   lives and it works there. Writing a post and reading one are two things you
     *   do with this tab's list, so they are two contents of one pane rather than a
     *   pane and a modal over it. `MessagesBackingViewModel.isComposingNew` is the
     *   twin, and the shape is copied from it deliberately rather than reinvented.
     *
     *   Setting either clears the other; see [onNewPostTapped] and [onPostOpened].
     *   A flag rather than a draft id, for the reason the Messages one gives:
     *   nothing is created on the server when this becomes true — see
     *   `ForumComposerBackingViewModel`, whose draft row is now minted at the first
     *   attach or the submit — so there is genuinely no id to hold, and a nullable
     *   one would invite somebody to fetch it.
     */
    data class State(
        val projectId: Long? = null,
        val forums: List<ForumSummary> = emptyList(),
        val selectedForumId: Long? = null,
        val canManageForums: Boolean = false,
        val isLoaded: Boolean = false,
        val isBusy: Boolean = false,
        val errorMessage: String? = null,
        val posts: List<ForumPostSummary> = emptyList(),
        val arePostsLoaded: Boolean = false,
        val canPost: Boolean = false,
        val openPostId: Long? = null,
        val isPostFocused: Boolean = false,
        val isComposingPost: Boolean = false,
        val mentionableNames: List<String> = emptyList(),
        val notifyOnNewPosts: Boolean = false,
        val canReceiveEmailNotifications: Boolean = false,
    ) {
        /** The selected forum, or null. */
        val selectedForum: ForumSummary? get() = forums.firstOrNull { it.id == selectedForumId }

        /**
         * Whether the Discussion tab has a second pane at all.
         *
         * Reading a post and writing a new one share one window — see
         * [isComposingPost] — so "is there a window" is the disjunction rather than
         * either field, and `main.kt` asks it twice (which panes exist, and which is
         * active). Spelled here so those two answers cannot drift apart.
         * `MessagesBackingViewModel.State.hasWindow` is the twin.
         */
        val hasWindow: Boolean get() = openPostId != null || isComposingPost

        /**
         * One post row's byline, under its title: "Robert · 17 Jul 2026, 14:32".
         *
         * Spelled here rather than on [ForumPostSummary] for the reason every other
         * label in this package is: the wire type is what the server sent, and the
         * server has no opinion about how a date is written.
         *
         * The comment count used to be folded in here, and is not any more. The
         * design's card is three columns — this, then how many replies, then who
         * last replied and when — and a count buried at the end of a byline is a
         * thing you read rather than a thing you scan. See [postCommentCount] and
         * [postLastCommenter].
         */
        fun postByline(post: ForumPostSummary): String =
            "${post.authorName ?: "A deleted account"} · ${formatTimestamp(post.createdAt)}"

        /**
         * The number over the word COMMENTS in the card's middle column.
         *
         * A bare numeral, including for zero: the label underneath already supplies
         * the noun, so "0" under "COMMENTS" says "no comments" without needing the
         * singular/plural dance a sentence would.
         */
        fun postCommentCount(post: ForumPostSummary): String = post.commentCount.toString()

        /**
         * Who last replied, for the card's right-hand column — or null when nobody
         * has.
         *
         * Null rather than a dash or "—": the column is what says whether a thread
         * is still moving, and an empty one says "nothing has happened here" more
         * plainly than a placeholder that has to be recognised as meaning nothing.
         */
        fun postLastCommenter(post: ForumPostSummary): String? = when {
            post.lastCommentAt == null -> null
            else -> post.lastCommentAuthor ?: "A deleted account"
        }

        /**
         * How long ago that was: "4m", "2d", "5w".
         *
         * Relative rather than absolute, unlike every other date this app renders,
         * because the question this column answers is "is this thread still
         * moving" — see [formatRelative], which argues it at length. Null exactly
         * when [postLastCommenter] is.
         */
        fun postLastCommentAge(post: ForumPostSummary): String? =
            post.lastCommentAt?.let { formatRelative(it) }

        /** One post row's agent badge, or null when a human wrote it. */
        fun postAgentBadge(post: ForumPostSummary): String? = post.agentName?.let { "Agent · $it" }
    }

    /**
     * Remember what the URL asked for, to be applied when there is a list to apply
     * it to.
     *
     * Called by `main.kt` once, at load, before the board has resolved a project.
     * Nothing is fetched here — this view model never starts anything on its own,
     * because it never picks a project; see the class doc. [onProjectChanged] is
     * what eventually runs, and the pending ids are spent by the list it fetches.
     *
     * There is no popstate listener anywhere in this app and this adds none: a deep
     * link is a load-time instruction. See `main.kt`.
     *
     * @param forumId `?forum=`, or null.
     * @param postId `?post=`, or null. Honoured only alongside a forum, since a post
     *   is addressed through the forum it is in — see `ForumPostBackingViewModel`,
     *   which takes all three ids because the route does.
     * @param commentId `?comment=`, or null. Held until the post view asks for it.
     */
    fun start(forumId: Long? = null, postId: Long? = null, commentId: Long? = null) {
        pendingForumId = forumId
        pendingPostId = postId?.takeIf { forumId != null }
        pendingCommentId = commentId?.takeIf { pendingPostId != null }
    }

    /**
     * The comment a deep link asked to land on, once and then never again.
     *
     * Read by the post view when the post has rendered, and cleared by the reading
     * of it: scrolling somewhere happens once, and a value that survived would
     * re-scroll on every later render — over a reader who had scrolled somewhere
     * themselves. `MessagesBackingViewModel.takePendingMessageId` is the twin.
     */
    fun takePendingCommentId(): Long? {
        val id = pendingCommentId
        pendingCommentId = null
        return id
    }

    /**
     * The app moved to a different project — or to none.
     *
     * Called by `main.kt` from the board's state. Idempotent on the project it is
     * already showing, because the board emits on every tick and re-fetching the
     * forum list forty times while somebody drags a card would be forty requests
     * for an answer that did not change.
     */
    fun onProjectChanged(projectId: Long?) {
        val current = _stateFlow.value
        if (current.projectId == projectId && current.isLoaded) return
        _stateFlow.value = State(projectId = projectId)
        if (projectId == null) {
            // Nothing to fetch, but the pane still has to stop waiting: without
            // this the "no project" case would render the pre-first-fetch blank
            // for ever.
            _stateFlow.value = _stateFlow.value.copy(isLoaded = true)
            return
        }
        refresh()
    }

    /**
     * The session changed — signed in, signed out, or an admin's impersonation
     * starting or stopping.
     *
     * Called by `main.kt` from the session state, exactly as the board and the
     * Messages tab are. The forum list carries the affordances — [State.canManageForums],
     * [State.canPost] — and per-project visibility (LNL-57) can even change *which*
     * forums are visible, so all of it has to be re-fetched under the new identity.
     * The board reloads on the same signal, but its project id does not change, so
     * [onProjectChanged] sees no change and returns: without this the Discussion tab
     * would keep the signed-out answer until a reload, which for a fresh admin is a
     * "no forums yet" that never learns it could make one (LNL-83).
     *
     * A no-op with no project — there is nothing to fetch — which also covers the
     * first, still-resolving session emission that precedes any project.
     */
    fun onSessionChanged() {
        if (_stateFlow.value.projectId == null) return
        refresh()
    }

    /**
     * The user picked a forum from the list.
     *
     * Closes whatever post was open, deliberately: a post belongs to a forum, so
     * leaving the detail up while the picker says somewhere else would have the
     * pane claiming a post is in a forum it is not in.
     *
     * ...and closes a **new post** being written, for the sharper version of the
     * same reason. That composer was built against the forum it was opened from and
     * would publish into it, so leaving it up would mean pressing Post while the
     * picker named somewhere else and having the post land in neither the forum on
     * screen nor anywhere the writer could see. Discarding a half-written body is
     * the lesser harm, and it is a discard the reader caused by navigating.
     */
    fun onForumSelected(forumId: Long) {
        val state = _stateFlow.value
        if (state.forums.none { it.id == forumId }) return
        if (state.selectedForumId == forumId) return
        // A forum picked by hand overrides whatever the URL asked for, comment
        // included: the reader has said where they want to be, and landing them in
        // a post they navigated away from would be the link winning an argument it
        // has already lost.
        pendingForumId = null
        pendingPostId = null
        pendingCommentId = null
        _stateFlow.value = state.copy(
            selectedForumId = forumId,
            openPostId = null,
            isPostFocused = false,
            isComposingPost = false,
        )
        refreshPosts()
    }

    /**
     * Open a post for reading.
     *
     * Opening is focusing, so this sets [State.isPostFocused] too — the window is
     * being put in front of the reader, and a snapshot that opened it without
     * focusing it would leave the toolkit's idea of the active pane and ours
     * disagreeing from the first frame.
     *
     * Idempotent on the post already open, which is what makes clicking the same
     * row twice a focus command rather than a rebuild: see `ForumWindows`, which
     * keeps the window and swaps its contents only when the id actually changes.
     */
    fun onPostOpened(postId: Long) {
        pendingPostId = null
        val state = _stateFlow.value
        if (state.openPostId != postId) {
            pendingCommentId = null
            // **This is what "viewed" means** (LNL-64), and it is decided here
            // rather than in the window because the window is *reused*: since
            // LNL-62 there is one post pane whose children are swapped, so "the
            // window opened" is not an event and "the post in it changed" is. Only
            // on an actual change, which is what makes clicking the open row again
            // a focus command rather than a second view.
            //
            // The route moves the forum's high-water mark to this post's own
            // timestamp; see `forumPostRoutes`, where what that costs is written
            // down.
            markRead(postId)
        }
        _stateFlow.value = state.copy(
            openPostId = postId,
            isPostFocused = true,
            // Reading and writing share the window, so one displaces the other.
            // Whatever was half-written goes with the view being torn down — see
            // `ForumWindows.dispose`, which cancels the composer rather than leaving
            // a draft row behind it.
            isComposingPost = false,
        )
    }

    /**
     * "New post" was pressed.
     *
     * Puts the tab into the composing state rather than opening anything, exactly as
     * `MessagesBackingViewModel.onNewMessageTapped` does: nothing is fetched and
     * nothing is created, and the window that appears is `ForumWindows` reacting to
     * this flag the same way it reacts to a row being clicked.
     *
     * Idempotent apart from focus, for [onPostOpened]'s reason: pressing it again
     * while a post is already being written must raise that window rather than throw
     * the half-written body away.
     */
    fun onNewPostTapped() {
        val state = _stateFlow.value
        if (state.selectedForumId == null) return
        if (state.isComposingPost) {
            if (!state.isPostFocused) _stateFlow.value = state.copy(isPostFocused = true)
            return
        }
        pendingCommentId = null
        _stateFlow.value = state.copy(
            isComposingPost = true,
            openPostId = null,
            isPostFocused = true,
        )
    }

    /** The new-post window was closed, or its composer gave up. */
    fun onComposeCancelled() {
        val state = _stateFlow.value
        if (!state.isComposingPost) return
        _stateFlow.value = state.copy(isComposingPost = false, isPostFocused = false)
    }

    /**
     * A new post was written and published.
     *
     * Opens it as well as refreshing, so the writer lands in the thread they just
     * created rather than back at a list where they have to find it — and that
     * navigation happens **inside one window**, because both contents are
     * `FORUM_POST_PANE_ID`. Nothing opens and nothing closes in front of somebody
     * who has just pressed Post, which is the payoff of the composer being a pane
     * rather than a modal over one. `onConversationStarted` is the twin.
     *
     * Not routed through [onPostOpened], deliberately: that one spends a round-trip
     * marking the post read, and a post you wrote yourself is never unread in the
     * first place — see `forumPostRoutes`, where `isUnread` excludes your own
     * authorship outright.
     */
    fun onPostCreated(postId: Long) {
        pendingCommentId = null
        _stateFlow.value = _stateFlow.value.copy(
            openPostId = postId,
            isPostFocused = true,
            isComposingPost = false,
        )
        refreshPosts()
    }

    /**
     * Tell the server this post has been read, and take the refreshed list.
     *
     * Fired alongside the window being built rather than from inside it, so the two
     * round-trips overlap and the reader waits for neither.
     *
     * A failure is **swallowed**, for `MessagesBackingViewModel.markRead`'s reason:
     * a read mark is bookkeeping about somebody's own attention, and an error line
     * over a post they successfully opened would report a problem they do not have.
     */
    private fun markRead(postId: Long) {
        val state = _stateFlow.value
        val projectId = state.projectId ?: return
        val forumId = state.selectedForumId ?: return
        scope.launch {
            runCatching { storage.markForumPostRead(projectId, forumId, postId) }
                // Through applyPosts, so the "is this still the forum being looked
                // at" guard applies: reading a post and switching forums quickly
                // must not repaint the previous forum's list.
                .onSuccess { applyPosts(forumId, it) }
        }
    }

    /**
     * A pane of the Discussion tab was pressed.
     *
     * The forum list and the post window report their own mousedowns, so the pane
     * snapshot's `activePaneId` follows the window the reader is actually in. See
     * [State.isPostFocused] for what goes wrong without it.
     */
    fun onPostWindowFocused(focused: Boolean) {
        val state = _stateFlow.value
        if (state.isPostFocused == focused) return
        _stateFlow.value = state.copy(isPostFocused = focused)
    }

    /**
     * Back to the list.
     *
     * @param changed whether anything was written while the post was open — a
     *   comment, or the post itself being deleted. The list carries a comment count
     *   per row, so it is stale after either and re-fetching is how the count and
     *   the row's existence stay true. Skipped when nothing happened, because most
     *   closes are somebody who read a thread and pressed back.
     */
    fun onPostClosed(changed: Boolean = false) {
        pendingCommentId = null
        _stateFlow.value = _stateFlow.value.copy(openPostId = null, isPostFocused = false)
        if (changed) refreshPosts()
    }

    /**
     * Something was written in the open post, while it is still open.
     *
     * The list carries a comment count and a last-replier column per row, so a
     * published comment makes the row behind the window stale *immediately* — which
     * it was not before LNL-62, when the post replaced the list rather than sitting
     * beside it. Refreshing only on close would leave a visibly wrong count on
     * screen for as long as somebody kept reading.
     */
    fun onOpenPostChanged() = refreshPosts()

    /**
     * Re-fetch the selected forum's posts.
     *
     * Its own request rather than a field on the forum list, because the two have
     * different lifetimes: the forum list changes when an administrator changes it,
     * and the post list changes whenever anybody writes anything. Folding the posts
     * into `ForumListState` would mean every create-a-forum round-trip also carried
     * every post in the forum the user happens to be looking at.
     */
    fun refreshPosts() {
        val state = _stateFlow.value
        val projectId = state.projectId ?: return
        val forumId = state.selectedForumId ?: run {
            _stateFlow.value = state.copy(posts = emptyList(), arePostsLoaded = true)
            return
        }
        _stateFlow.value = state.copy(arePostsLoaded = false)
        scope.launch {
            runCatching { storage.forumPosts(projectId, forumId) }
                .onSuccess { applyPosts(forumId, it) }
                .onFailure { failure ->
                    _stateFlow.value = _stateFlow.value.copy(
                        arePostsLoaded = true,
                        errorMessage = failure.userMessage("Could not load this forum's posts."),
                    )
                }
        }
    }

    /**
     * Take a post list, but only if it is still the one being looked at.
     *
     * The guard is not paranoia: switching forums twice quickly leaves two requests
     * in flight, and without it the slower answer overwrites the faster one and the
     * pane shows the previous forum's posts under the current forum's name. Nothing
     * else in this class needs it, because every other request answers with the
     * whole forum list rather than something scoped to a selection.
     */
    private fun applyPosts(forumId: Long, result: ForumPostListState) {
        val state = _stateFlow.value
        if (state.selectedForumId != forumId) return
        _stateFlow.value = state.copy(
            posts = result.posts,
            canPost = result.canPost,
            mentionableNames = result.mentionableUsers.map { it.name },
            notifyOnNewPosts = result.notifyOnNewPosts,
            canReceiveEmailNotifications = result.canReceiveEmailNotifications,
            arePostsLoaded = true,
        )
    }

    /**
     * The forum's Watch pill was pressed.
     *
     * Answers with the whole post list, which is more than the pill needs and is
     * the right thing to take: every other write in this class takes the server's
     * whole refreshed answer rather than patching its own copy, and a client
     * holding an opinion about its own subscription is a client that is wrong the
     * moment the same account presses the pill in another tab.
     *
     * Silently ignored with no forum selected — there is nothing to watch, and the
     * pane hides the control in that state anyway.
     */
    fun onForumNotificationToggled(subscribed: Boolean) {
        val state = _stateFlow.value
        val projectId = state.projectId ?: return
        val forumId = state.selectedForumId ?: return
        if (state.isBusy) return
        _stateFlow.value = state.copy(isBusy = true, errorMessage = null)
        scope.launch {
            runCatching { storage.setForumNotification(projectId, forumId, subscribed) }.fold(
                onSuccess = { result ->
                    // Through applyPosts, so the "is this still the forum being
                    // looked at" guard applies here too: pressing Watch and then
                    // switching forums must not repaint the previous forum's list.
                    _stateFlow.value = _stateFlow.value.copy(isBusy = false)
                    applyPosts(forumId, result)
                },
                onFailure = { failure ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        // The 403 this most often carries is "add an e-mail
                        // address first", which is a thing the reader can act on —
                        // so it is shown rather than swallowed. See
                        // `userMessage`, which prefers the server's sentence.
                        errorMessage = failure.userMessage("Could not change your notification setting."),
                    )
                },
            )
        }
    }

    /** Re-fetch the list, keeping the selection if it survived. */
    fun refresh() {
        val projectId = _stateFlow.value.projectId ?: return
        launchWrite { storage.forums(projectId) }
    }

    fun onForumCreated(name: String, description: String?) {
        val projectId = _stateFlow.value.projectId ?: return
        launchWrite { storage.createForum(projectId, name, description) }
    }

    fun onForumEdited(forumId: Long, name: String, description: String?) {
        val projectId = _stateFlow.value.projectId ?: return
        launchWrite { storage.editForum(projectId, forumId, name, description) }
    }

    fun onForumDeleted(forumId: Long) {
        val projectId = _stateFlow.value.projectId ?: return
        launchWrite { storage.deleteForum(projectId, forumId) }
    }

    /**
     * Move a forum one place up or down.
     *
     * Sent as the whole reordered list rather than as a move, because that is
     * what the route takes — it refuses an order that is not exactly this
     * project's forums, which is what stops a stale client from applying a
     * reorder against a list somebody else has since changed. See ForumOrder.
     */
    fun onForumMoved(forumId: Long, by: Int) {
        val projectId = _stateFlow.value.projectId ?: return
        val ids = _stateFlow.value.forums.map { it.id }.toMutableList()
        val from = ids.indexOf(forumId)
        val to = from + by
        if (from < 0 || to !in ids.indices) return
        ids.removeAt(from)
        ids.add(to, forumId)
        launchWrite { storage.reorderForums(projectId, ids) }
    }

    /** Dismiss the failure alert. */
    fun onErrorDismissed() {
        _stateFlow.value = _stateFlow.value.copy(errorMessage = null)
    }

    /**
     * Run a request that answers with the whole refreshed list.
     *
     * Every forum write returns a `ForumListState`, so read and write share one
     * path here: the server decides the order and the affordances, and a client
     * that patched its own copy after a write would be holding an opinion about
     * both — and would be wrong the moment somebody else changed the list.
     *
     * The selection is re-derived rather than preserved blindly: it survives if
     * the forum is still there, and otherwise falls to the first one, so deleting
     * the forum you were reading does not leave the pane pointed at nothing.
     */
    private fun launchWrite(request: suspend () -> ForumListState) {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            runCatching { request() }
                .onSuccess { result ->
                    val state = _stateFlow.value
                    // The deep link, spent here and only here. It outranks the
                    // kept selection because it is the more recent instruction —
                    // there is nothing to keep on the first list anyway — and it
                    // is dropped rather than honoured if it names a forum this
                    // project does not have, which is what a stale or hand-typed
                    // link looks like. Landing on the first forum instead is the
                    // same thing that happens with no link at all.
                    val linked = pendingForumId?.takeIf { id -> result.forums.any { it.id == id } }
                    val linkedPost = linked?.let { pendingPostId }
                    pendingForumId = null
                    pendingPostId = null
                    if (linked == null) pendingCommentId = null
                    val keep = state.selectedForumId?.takeIf { id -> result.forums.any { it.id == id } }
                    val selected = linked ?: keep ?: result.forums.firstOrNull()?.id
                    _stateFlow.value = state.copy(
                        forums = result.forums,
                        selectedForumId = selected,
                        canManageForums = result.canManageForums,
                        isLoaded = true,
                        isBusy = false,
                        // A post that is no longer in a forum this list contains
                        // cannot still be open. Deleting the forum you were reading
                        // in is the case, and leaving the detail up would leave a
                        // window onto rows the server has already cascaded away.
                        openPostId = linkedPost
                            ?: state.openPostId?.takeIf { selected == state.selectedForumId },
                        isPostFocused = linkedPost != null || state.isPostFocused,
                        // A post being written is bound to the forum it was started
                        // in, exactly as an open post is — so it survives a refresh
                        // that leaves the selection alone, and goes when the ground
                        // moves under it. See [onForumSelected], which argues why
                        // the alternative is a post published into nowhere visible.
                        isComposingPost = state.isComposingPost &&
                            linkedPost == null &&
                            selected == state.selectedForumId,
                    )
                    // The forum list and the post list are different requests, so a
                    // change of selection here is what starts the second one.
                    if (selected != null) refreshPosts()
                }
                .onFailure { failure ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isLoaded = true,
                        isBusy = false,
                        errorMessage = failure.userMessage("Could not load this project's forums."),
                    )
                }
        }
    }
}
