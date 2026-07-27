/**
 * The Discussion tab's main pane: which forum you are in, and what is in it.
 *
 * The board's counterpart on the discussion side, and built to the same rules —
 * non-closable, opening maximised, and rendering whatever
 * [ForumBackingViewModel.State] says exists. Every gesture goes back as an
 * intent; nothing here decides anything.
 *
 * ── Following the design, and where it was overtaken ────────────────────────
 *
 * The prototype's forum view is a **dropdown picker** for the forum, a gear
 * beside it, and the selected forum's posts below. That shape is kept: a project
 * with a dozen forums would spend the pane's whole width on a list nobody reads
 * twice, and the posts are what people came for.
 *
 * Two things in the prototype are deliberately absent. The **"Filter posts…"
 * box** is dropped, as LNL-30 says. And the gear no longer opens a *per-forum
 * access* panel — that whole idea was replaced by project-level visibility in
 * LNL-57, so there is nobody to switch on or off per forum. It opens the forum
 * *list manager* instead, which is what a project administrator now needs from
 * that position: create, rename, reorder, delete.
 *
 * ── The list, and only the list ─────────────────────────────────────────────
 *
 * LNL-61 gave this pane two faces: the post list, and a [ForumPostView] swapped in
 * over it. LNL-62 took the second one away — reading a post is a **window** now,
 * owned by `ForumWindows` in main.kt — and the prediction in that ticket held: this
 * file shrank rather than being rewritten, and `ForumPostView` was untouched.
 *
 * What is left is the list, which now stays on screen *beside* the post being read
 * rather than being replaced by it. Two consequences are deliberate. "New post" is
 * no longer hidden while a post is open, because it is no longer next to the post
 * — it is above the list, where it has always meant what it says. And a comment
 * published in the window refreshes this list immediately rather than on close,
 * because the row's comment count is now visible while it goes stale.
 *
 * ── There is no composer here any more either ───────────────────────────────
 *
 * LNL-61 kept both composers behind this pane as one **modal**: "New post" and the
 * post window's "Add comment" each opened `ForumComposerDialog`. That file is gone.
 * The WYSIWYG editor belongs in a pane — which is where the issue editor has always
 * lived, and it works there — so a comment is written inline at the foot of the post
 * window ([ForumPostView]) and a new post takes the same window the post does, with
 * a title field above the body where the thread would be ([NewForumPostView]).
 *
 * So "New post" is not a button that opens something; it is a button that puts the
 * Discussion tab into a state — [ForumBackingViewModel.State.isComposingPost] —
 * exactly as clicking a row puts it into the state of having a post open. Which of
 * the two the window shows is `ForumWindows`' business, not this pane's. `MessagesPane`
 * says the same about its own "New message", a commit earlier and one tab over.
 *
 * @see ForumBackingViewModel
 * @see ForumPostView
 * @see BoardWindow
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.client.viewmodel.ForumBackingViewModel
import se.soderbjorn.lunicle.clientserver.ForumPostSummary

/**
 * Renders the forum pane.
 *
 * @param viewModel the tab's state and intents.
 * @param scope the pane's lifetime. The pane is never disposed — it is the tab's
 *   non-closable main pane — so this is the app scope rather than one of its
 *   own, unlike an issue window.
 * @param openManager asks the host to put the forum manager up. Routed out
 *   rather than mounted here for the reason every other modal is: the shared
 *   dialog host is what makes "topmost wins Escape" true.
 *
 * Note what it no longer takes: a `dialogHost`, a `storage`, and a way to reach the
 * post window's view model. All three were here for the composer modal — and the
 * last of them forced `main.kt` to build the pane and the windows with a `var`
 * between them, because each needed the other. None of it has anything to do with a
 * list of rows, which is the clearest evidence the composer was in the wrong place.
 * `MessagesPane` lost the same two a commit earlier.
 */
class ForumPane(
    private val viewModel: ForumBackingViewModel,
    private val scope: CoroutineScope,
    private val openManager: () -> Unit,
) {
    /** The pane's root, handed to the toolkit as this pane's content. */
    val root: HTMLElement = element("div", "forum-pane")

    // Built once and kept, which is what makes it safe to re-render on every
    // tick: `Dropdown` owns its own closed control and re-renders in place, so
    // nothing is detached mid-gesture. Rebuilding it would be the same class of
    // bug `deliver()` in main.kt guards the pane list against.
    // `picker` is the board's project-picker styling — same control, same job,
    // one place to restyle both.
    private val picker = Dropdown(className = "picker forum-picker") { viewModel.onForumSelected(it) }
    private val description = element("p", "forum-description")
    private val manageButton = button("Manage forums", "btn forum-manage") { openManager() }
    private val postsHost = element("div", "forum-posts")
    private val emptyMessage = element("p", "forum-empty")
    private val newPostButton =
        button("New post", "btn btn-primary forum-new-post") { viewModel.onNewPostTapped() }

    /**
     * "Watch" / "Watching" for the **selected forum** (LNL-63).
     *
     * ── Where it went, and why here ─────────────────────────────────────────
     *
     * The prototype has no forum-level watch control at all — it puts Watch on a
     * post and stops there — so this position is a decision rather than something
     * being followed. It goes in the header row, at the end, next to "Manage
     * forums": that row is already the row of things you do *to the forum you are
     * in*, as against the list below it, which is things people have said in it.
     *
     * After "Manage forums" rather than before it, so that the one control only a
     * project administrator ever sees does not shuffle position for everybody else
     * depending on who is looking. The same [WatchButton] the post and the issue
     * window use, for that class's reason.
     *
     * The alternative considered was beside the forum's description line, which is
     * closer to "this forum" as an object — and it was rejected because the
     * description is absent for most forums, so the pill would move up and down the
     * pane depending on whether somebody had written one.
     */
    private val watchButton = WatchButton { viewModel.onForumNotificationToggled(it) }

    /**
     * Build the pane's tree and start following the state.
     *
     * No `mount(host)`, unlike the dialogs: this pane is handed to the toolkit as
     * [root] and re-parented on every shell re-render, so there is no stable host
     * to be mounted into. `BoardWindow` has the same shape for the same reason.
     */
    fun start() {
        val header = element("div", "forum-header")
        // The trailing pair, in a group of their own so that exactly one thing in
        // this row carries `margin-left: auto`. Two elements each claiming it
        // would *share* the free space rather than both sit at the end — and
        // "Manage forums" is hidden for everybody but a project administrator, so
        // hanging the alignment off either one individually would move the row's
        // right-hand end depending on who is looking.
        val headerActions = element("div", "forum-header-actions")
        headerActions.children(manageButton, watchButton.element)
        header.children(picker.element, newPostButton, headerActions)
        root.children(header, description, emptyMessage, postsHost)

        scope.launch { viewModel.stateFlow.collect(::render) }
    }

    private fun render(state: ForumBackingViewModel.State) {
        manageButton.visible(state.canManageForums, displayValue = "inline-flex")

        // Before the first answer, say nothing at all. "No forums yet" shown to
        // somebody whose project has six of them, for the half-second the
        // request takes, is worse than a blank.
        if (!state.isLoaded) {
            emptyMessage.visible(false)
            picker.element.visible(false, displayValue = "inline-flex")
            description.visible(false)
            newPostButton.visible(false, displayValue = "inline-flex")
            watchButton.element.visible(false, displayValue = "inline-flex")
            postsHost.clear()
            return
        }

        val message = when {
            state.projectId == null -> "No project is selected, so there are no forums to show."
            state.forums.isEmpty() && state.canManageForums ->
                "This project has no forums yet. Use “Manage forums” to make the first one."
            state.forums.isEmpty() -> "This project has no forums yet."
            else -> null
        }
        emptyMessage.setTextIfChanged(message.orEmpty())
        emptyMessage.visible(message != null, displayValue = "block")

        picker.element.visible(state.forums.isNotEmpty(), displayValue = "inline-flex")
        picker.render(
            items = state.forums.map { DropdownItem(it.id, it.name) },
            selectedId = state.selectedForumId,
        )

        // The selected forum's one-liner, under the picker rather than inside
        // it: a description belongs to the forum you are in, and putting it in
        // the closed control would make the control's width depend on which row
        // is chosen.
        val blurb = state.selectedForum?.description
        description.setTextIfChanged(blurb.orEmpty())
        description.visible(blurb != null, displayValue = "block")

        // Offered only where there is a forum to post in, and only to somebody who
        // may. No longer hidden while a post is open: LNL-61 hid it because the
        // post replaced the list and "New post" beside a post you are reading read
        // as "reply". Since LNL-62 the post is a window elsewhere, and this button
        // is where it always was — above the list it posts into.
        newPostButton.visible(
            state.canPost && state.selectedForum != null,
            displayValue = "inline-flex",
        )

        // Offered where there is a forum to watch, to somebody with an address to
        // send to — and only once the post list has answered, because that is what
        // carries both facts. Without the `arePostsLoaded` guard the pill would
        // appear reading "Watch" while switching forums and then flip, which for
        // half a second tells a watcher they are not watching.
        watchButton.element.visible(
            state.selectedForum != null &&
                state.arePostsLoaded &&
                state.canReceiveEmailNotifications,
            displayValue = "inline-flex",
        )
        watchButton.render(watching = state.notifyOnNewPosts, isEnabled = !state.isBusy)

        renderPosts(state)
    }

    /**
     * What the list was last painted from. See [renderPosts].
     *
     * The list itself is compared by **identity**, not by equality: it is replaced
     * wholesale by every fetch and never edited in place, so a new list is a new
     * object and an unchanged one is the same object. That makes the check free,
     * where comparing tens of data classes field by field on every tick would not
     * be.
     */
    private var paintedPosts: List<ForumPostSummary>? = null
    private var paintedForumId: Long? = null
    private var paintedOpenPostId: Long? = null
    private var paintedLoaded = false

    /**
     * The selected forum's posts, newest first.
     *
     * Rebuilt whole rather than patched: a forum's post list is tens of rows and is
     * re-fetched whole after every write, so there is nothing to diff against that
     * would not first have to be fetched.
     *
     * **But only when something it renders has changed**, and that guard is
     * load-bearing rather than an optimisation. Since LNL-62 this pane's state
     * carries the Discussion tab's *focus* as well as its contents, so a mousedown
     * on a row emits before the mouseup — and a rebuild in that window detaches the
     * very row being pressed, so the click never fires and the post never opens.
     * That is `deliver()`'s hazard in main.kt, one level down, and it bit exactly
     * as described: clicking a second post while one was open did nothing at all.
     *
     * Note what is *not* rendered before the answer arrives. "No posts yet" shown
     * to somebody whose forum has twenty of them, for the half-second the request
     * takes, is worse than a blank — the same position [render] takes about the
     * forum list itself.
     */
    private fun renderPosts(state: ForumBackingViewModel.State) {
        if (state.posts === paintedPosts &&
            state.selectedForumId == paintedForumId &&
            state.openPostId == paintedOpenPostId &&
            state.arePostsLoaded == paintedLoaded
        ) {
            return
        }
        paintedPosts = state.posts
        paintedForumId = state.selectedForumId
        paintedOpenPostId = state.openPostId
        paintedLoaded = state.arePostsLoaded

        postsHost.clear()
        val forum = state.selectedForum ?: return
        if (!state.arePostsLoaded) return
        if (state.posts.isEmpty()) {
            postsHost.appendChild(
                element("p", "forum-posts-empty", "No posts in ${forum.name} yet."),
            )
            return
        }
        state.posts.forEach { post ->
            val row = element("article", "forum-post-row")
            // The post currently in the window, marked. Since LNL-62 the list stays
            // on screen beside the post being read, so "which of these am I in" is
            // a question the list can now be asked — and answering it is what stops
            // a second click on the same row looking like it did nothing.
            if (post.id == state.openPostId) row.classList.add("forum-post-row-open")
            // A dot, never a count — the discussion side gets dots throughout, by
            // LNL-30's decision. Drawn as a class on the row rather than an element
            // in it, so the mark can sit in the card's padding without taking a
            // column from a grid that already has three.
            if (post.isUnread) row.classList.add("forum-post-row-unread")
            row.children(
                postSubject(state, post),
                postCommentColumn(state, post),
                postActivityColumn(state, post),
            )
            // The whole row opens the post, not a link inside it. A post row is
            // one thing to press, exactly as a board card is.
            row.addEventListener("click", { viewModel.onPostOpened(post.id) })
            postsHost.appendChild(row)
        }
    }

    /**
     * The card's first column: the title, with who wrote it and when beneath.
     *
     * The only column that may shrink — the two beside it are sized to their
     * content and a title is the one thing here that can be 200 characters long.
     */
    private fun postSubject(
        state: ForumBackingViewModel.State,
        post: ForumPostSummary,
    ): HTMLElement {
        val column = element("div", "forum-post-row-subject")
        column.appendChild(element("div", "forum-post-row-title", post.title))
        val meta = element("div", "forum-post-row-meta")
        meta.appendChild(element("span", "forum-post-row-byline", state.postByline(post)))
        state.postAgentBadge(post)?.let { text ->
            val badge = element("span", "agent-badge")
            badge.children(agentIcon(), element("span", "agent-badge-label", text))
            meta.appendChild(badge)
        }
        column.appendChild(meta)
        return column
    }

    /**
     * The card's second column: how many replies, over the word COMMENTS.
     *
     * A number with its unit under it rather than "3 comments" on one line, which
     * is the design's shape and is what makes the counts line up down the list as a
     * column to be compared rather than a phrase to be read in each row.
     */
    private fun postCommentColumn(
        state: ForumBackingViewModel.State,
        post: ForumPostSummary,
    ): HTMLElement {
        val column = element("div", "forum-post-row-comments")
        column.children(
            element("div", "forum-post-row-count", state.postCommentCount(post)),
            element("div", "forum-post-row-count-label", "Comments"),
        )
        return column
    }

    /**
     * The card's third column: who last replied, over how long ago.
     *
     * Left **empty** rather than filled with a placeholder when nobody has replied
     * — see `ForumBackingViewModel.postLastCommenter`. The column keeps its width
     * either way, which is what stops a forum where half the threads are quiet from
     * rendering as a ragged list.
     */
    private fun postActivityColumn(
        state: ForumBackingViewModel.State,
        post: ForumPostSummary,
    ): HTMLElement {
        val column = element("div", "forum-post-row-activity")
        val who = state.postLastCommenter(post) ?: return column
        column.children(
            element("div", "forum-post-row-last-author", who),
            element("div", "forum-post-row-last-age", state.postLastCommentAge(post).orEmpty()),
        )
        return column
    }
}
