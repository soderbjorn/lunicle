/**
 * One forum post, read: the title, who wrote it, the body, and the comments.
 *
 * ── Built to be re-hosted, which is the whole point of it being a class ──────
 *
 * LNL-61 rendered this **inline** in the Discussion pane, under the forum picker,
 * with a Back link. LNL-62 moved it into a window of its own, and the move cost
 * this file nothing: it owns its [root] and nothing about where that root lives,
 * takes no pane id, no window and no host element, and is handed a
 * `ForumPostBackingViewModel` built from three ids. What changed was who appends
 * [root] — `ForumWindows` in main.kt now, rather than `ForumPane` — and that
 * [onBack] is null, because a window is left by closing it.
 *
 * It is still built to be re-hosted, and that is worth keeping: the same class is
 * what a mobile client would put on a screen of its own.
 *
 * `BoardWindow` and `ForumPane` have the same shape for the same reason: no
 * `mount(host)`, because the thing that holds them re-parents [root] on every
 * shell re-render and there is no stable host to be mounted into.
 *
 * ── The composer is inline, which is where the editor belongs ───────────────
 *
 * LNL-61 put an **"Add comment"** primary button in the footer bar, opening the
 * shared modal. The button is gone and the composer itself is in its place: an
 * editor, the design's `@` hint and a **Comment** button, pinned under the thread.
 *
 * That is the owner's instruction after LNL-30's run — *"definitely should be its
 * own pane, that already works great for writing issues so it should work fine here
 * — no modal please"* — and it is [ConversationView] one tab over, a commit earlier,
 * which is the shape being copied rather than reinvented. A reply and a comment are
 * the same gesture on two surfaces that already look alike.
 *
 * What makes it affordable is that this is a whole **pane**: draggable, resizable
 * and maximisable, so how much room the composer has is the reader's to settle with
 * the affordances the toolkit already gives them — and if it is tight, one fewer
 * open pane is the answer. That is the same deal the issue editor has in the issue
 * window, and it is why there is no height arithmetic here and none in the
 * stylesheet: the editor takes its natural size and the thread takes the rest. A cap
 * with an inner scrollbar was rejected one tab over and is not reintroduced here — a
 * scroll region inside a scroll region, sized by a number nobody chose.
 *
 * What is *not* lost by inlining it: attachments, `@` mentions and the formatting
 * toolbar are all [MarkdownEditor]'s, which is why this reuses
 * [ForumComposerBackingViewModel] rather than posting a body itself. The one real
 * cost is a draft row's timing, and it is handled in that view model rather than
 * here — this bar lives as long as the window does, so it must not create a draft
 * comment on open. See `ForumComposerBackingViewModel.ensureDraft`.
 *
 * ── Following the design elsewhere ──────────────────────────────────────────
 *
 * The prototype's post detail is a title row with a **Watch** button on the
 * right, a byline under it, the rendered body, a "COMMENTS" heading and flat comment
 * cards each with an avatar initial and a byline. All of it is here: LNL-61 built
 * everything but the watch button and left the slot in the title row, and LNL-63
 * appended the button to it — which is what reserving it was for.
 *
 * The comment cards are flat and in order, with no reply affordance anywhere,
 * because LNL-30 settled that a post has a list of comments and no tree. There is
 * nothing to collapse and nothing to indent.
 *
 * @see ForumPostBackingViewModel
 * @see ForumPane
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.client.renderMarkdown
import se.soderbjorn.lunicle.client.viewmodel.EditorDirtyRegistry
import se.soderbjorn.lunicle.client.viewmodel.ForumComposerBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.ForumPostBackingViewModel
import se.soderbjorn.lunicle.clientserver.ForumCommentView

/**
 * Renders one post.
 *
 * @param viewModel this post's state and intents.
 * @param scope the view's lifetime. Cancelled by whoever built it when the post
 *   closes — unlike [ForumPane], which lives as long as the app does, this is
 *   made and thrown away per post, so it gets a scope of its own.
 * @param onBack what the "← All posts" link does, or null to omit it entirely.
 *   Null is the window case (LNL-62): a window is left by closing it, and a Back
 *   link inside one would be a second, differently-shaped way to do the same
 *   thing. Inline, it is the only way back.
 * @param storage the client's repository, for the comment composer's view model.
 * @param onWritten a comment was published from here. The forum's post list carries
 *   a comment count and a last-replier column per row, and since LNL-62 that list is
 *   on screen *beside* this window rather than behind it — so refreshing on close
 *   would leave a visibly stale row for as long as somebody kept reading.
 * @param takeScrollTarget the comment `?comment=` asked to land on, or null.
 *   Called **once**, on the first render that has comments in it, and it clears
 *   itself by being read — see `ForumBackingViewModel.takePendingCommentId`. A view
 *   that held the id instead would re-scroll on every later render, over a reader
 *   who had scrolled somewhere themselves. `ConversationView` takes the same
 *   parameter for `?message=`, for the same reasons.
 */
class ForumPostView(
    private val viewModel: ForumPostBackingViewModel,
    private val scope: CoroutineScope,
    private val onBack: (() -> Unit)?,
    private val storage: StorageRepository,
    private val onWritten: () -> Unit,
    /** The open-editor register the inline comment composer joins — LNL-84. */
    private val editorRegistry: EditorDirtyRegistry,
    private val takeScrollTarget: () -> Long? = { null },
    /** The `PREFIX-` autocomplete source for the comment composer (LNL-139). */
    private val ticketSource: TicketSource,
) {
    /** The view's root, appended by whatever is hosting it. */
    val root: HTMLElement = element("div", "forum-post")

    private val backLink = button("← All posts", "link-btn forum-post-back") { onBack?.invoke() }
    private val titleElement = element("h3", "forum-post-title")

    /**
     * The Watch button's slot, at the right-hand end of the title row.
     *
     * LNL-61 reserved this empty and LNL-63 filled it, which is what that
     * reservation was for: the button was appended here rather than a flex row
     * being rebuilt around it. The design puts Watch in exactly this position, and
     * LNL-46 had already moved the *issue* watch control onto its own title row —
     * so the two surfaces agree without either having to be adjusted for the other.
     */
    private val titleActions = element("div", "forum-post-actions")

    /**
     * "Watch" / "Watching", the same pill the issue window has.
     *
     * The shared [WatchButton] rather than a forum-shaped copy: it is the same
     * control saying the same thing about a different object, and a second one
     * would be the place the eye icon and the label eventually stop matching.
     */
    private val watchButton = WatchButton { viewModel.onNotificationToggled(it) }

    private val bylineElement = element("p", "forum-post-byline")
    private val agentBadge = element("span", "agent-badge")
    private val deleteButton = button("Delete", "link-btn link-btn-danger") {
        viewModel.onDeletePostTapped()
    }
    private val bodyElement = element("div", "markdown forum-post-body")
    private val commentsHeading = element("h4", "comments-heading", "Comments")
    private val commentsElement = element("div", "comments")
    private val errorElement = element("p", "modal-error")

    /**
     * The composer under the thread, where LNL-61's "Add comment" button used to be.
     *
     * Where that button sat is where the thing it opened now lives, which is the
     * whole of the change: the design's primary action at the end of the footer bar
     * is still here, but it is the composer's own submit rather than a button whose
     * only job was to summon one. See the class doc.
     */
    private val composerElement = element("div", "forum-post-composer")
    private val composerEditorHost = element("div", "forum-post-composer-editor")
    private val composerValidation = element("p", "field-validation")
    private val composerError = element("p", "modal-error")
    private val commentButton: HTMLButtonElement = button("Comment", "btn btn-primary") {
        composerViewModel?.onOkTapped()
    }

    /**
     * The editor, built once for this view's life.
     *
     * Its callbacks go through [composerViewModel], which is replaced after every
     * successful publish — a published draft cannot be written into twice, so the
     * *model* is new each time while the surface being typed on is not. Rebuilding
     * the editor instead would take the caret and the toolbar state with it every
     * time somebody commented. `ConversationView` says the same about its own.
     */
    private lateinit var editor: MarkdownEditor

    /** The comment currently being written, or null before the first one is set up. */
    private var composerViewModel: ForumComposerBackingViewModel? = null

    /** The current composer's scope, cancelled when it is replaced. */
    private var composerScope: CoroutineScope? = null

    /** The mention names the editor was last given, so it is not told the same list twice. */
    private var mentionNames: List<String>? = null

    private var postConfirm: ConfirmDialog? = null
    private var commentConfirm: ConfirmDialog? = null

    /**
     * Build the tree and start following the state.
     *
     * @param dialogHost where the two confirmations mount. Passed in rather than
     *   found, because this view does not know what is above it — see the class
     *   doc. LNL-62's window passes the same host.
     */
    fun start(dialogHost: HTMLElement) {
        this.dialogHost = dialogHost

        agentBadge.children(agentIcon(), element("span", "agent-badge-label"))
        val header = element("div", "forum-post-header")
        val titleRow = element("div", "forum-post-title-row")
        titleActions.appendChild(watchButton.element)
        titleRow.children(titleElement, titleActions)
        val bylineRow = element("div", "forum-post-byline-row")
        bylineRow.children(bylineElement, agentBadge, deleteButton)
        header.children(titleRow, bylineRow)

        errorElement.setAttribute("role", "status")
        composerError.setAttribute("role", "status")

        editor = MarkdownEditor(
            scope = scope,
            // Through the current composer rather than a captured one: see the
            // field's doc. A keystroke arriving between two composers is not a state
            // this can be in — the replacement is synchronous.
            onChange = { composerViewModel?.onBodyChanged(it) },
            onUpload = { name, mime, bytes -> composerViewModel?.uploadAttachment(name, mime, bytes) },
            placeholder = "Write your comment…",
        )
        editor.mount(composerEditorHost)
        // Typing a known project's "PREFIX-" offers that project's issues (LNL-139).
        editor.setTicketSource(prefixes = ticketSource.prefixes, lookup = ticketSource.lookup)

        val composerActions = element("div", "forum-post-composer-actions")
        composerActions.appendChild(commentButton)
        composerElement.children(
            composerEditorHost,
            mentionHint(),
            composerValidation,
            composerError,
            composerActions,
        )

        val body = element("div", "forum-post-scroll")
        body.children(bodyElement, commentsHeading, commentsElement)

        if (onBack != null) root.appendChild(backLink)
        root.children(header, errorElement, body, composerElement)

        newComposer()
        scope.launch { viewModel.stateFlow.collect(::render) }
        viewModel.start()
    }

    /**
     * Start a fresh comment.
     *
     * Called once at [start] and again after every successful publish. A published
     * draft is not writable a second time — LNL-61 builds no edit route for a
     * comment at all — so "they are writing another one" is genuinely a new composer
     * rather than a reset of the old one, and expressing it as a replacement is what
     * stops a second Comment from trying to publish an id the server has already
     * closed. `ConversationView.newComposer` is the twin.
     */
    private fun newComposer() {
        composerScope?.cancel()
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        composerScope = localScope
        val composer = ForumComposerBackingViewModel(
            projectId = viewModel.projectId,
            forumId = viewModel.forumId,
            target = ForumComposerBackingViewModel.Target.NewComment(viewModel.postId),
            storage = storage,
            scope = localScope,
            // Empty: the popup's names are set on the editor directly from the post's
            // own state, because that arrives after this is built — and after every
            // later replacement too, which is why the editor is told rather than the
            // model.
            mentionableNames = emptyList(),
            editorRegistry = editorRegistry,
            onFinished = { _, detail ->
                if (detail != null) {
                    // Publishing answers with the whole post, so the thread above
                    // updates from what has already arrived rather than asking again
                    // — and the list beside this window is now stale.
                    viewModel.onDetailReceived(detail)
                    onWritten()
                }
                editor.setValue("")
                newComposer()
            },
        )
        composerViewModel = composer
        localScope.launch { composer.stateFlow.collect(::renderComposer) }
    }

    private fun renderComposer(state: ForumComposerBackingViewModel.State) {
        editor.setEnabled(state.isEditorEnabled)
        commentButton.disabled = !state.isOkEnabled
        // Only once there is something to say. An empty box explaining that it is
        // empty is noise under a thread somebody is reading — unlike the modal, where
        // the same line was the answer to "why is Post greyed out" on a form the
        // reader had deliberately opened. `ConversationView` draws the same line.
        val validation = state.validationMessage.takeIf { state.body.isNotEmpty() }
        composerValidation.setTextIfChanged(validation.orEmpty())
        composerValidation.visible(validation != null)
        composerError.setTextIfChanged(state.errorMessage.orEmpty())
        composerError.visible(state.errorMessage != null)
    }

    private var dialogHost: HTMLElement? = null

    private fun render(state: ForumPostBackingViewModel.State) {
        errorElement.setTextIfChanged(state.errorMessage.orEmpty())
        errorElement.visible(state.errorMessage != null)

        val detail = state.detail
        if (detail == null) {
            // Before the first answer, say nothing at all rather than "no
            // comments yet" over a thread that has forty. ForumPane takes the
            // same position about its own first render.
            titleElement.setTextIfChanged("")
            bodyElement.innerHTML = ""
            commentsHeading.visible(false)
            commentsElement.visible(false)
            bylineElement.visible(false)
            deleteButton.visible(false, displayValue = "inline-flex")
            composerElement.visible(false)
            agentBadge.visible(false, displayValue = "inline-flex")
            // Hidden before the first answer, like everything else here: the
            // response is what says whether this reader is watching, and a pill
            // that appeared reading "Watch" and then flipped to "Watching" would
            // be the window telling them something untrue for half a second.
            watchButton.element.visible(false, displayValue = "inline-flex")
            return
        }

        titleElement.setTextIfChanged(detail.title)
        bylineElement.setTextIfChanged(state.byline)
        bylineElement.visible(true, displayValue = "block")

        val badge = state.agentBadge
        (agentBadge.lastChild as? HTMLElement)?.setTextIfChanged(badge.orEmpty())
        agentBadge.visible(badge != null, displayValue = "inline-flex")

        deleteButton.visible(detail.canDelete, displayValue = "inline-flex")
        // Hidden outright for a reader who may not comment, rather than shown
        // disabled: a composer nobody can submit is a box that invites typing into
        // it. The routes refuse them regardless — this is the affordance.
        composerElement.visible(detail.canComment, displayValue = "flex")
        // Only when they have actually changed: setMentionNames hides the popup, so
        // handing it the same list on every tick would close a menu mid-selection.
        if (state.mentionableNames != mentionNames) {
            mentionNames = state.mentionableNames
            editor.setMentionNames(state.mentionableNames)
        }

        // Shown only to somebody with an address to send to — a pill that promised
        // an e-mail this server cannot deliver would be a dead control, which is
        // the position the issue window takes about the same button. Note it is
        // *not* gated on `canComment`: following a thread is not writing in it, and
        // a reader who cannot post here can still want to know when it moves.
        watchButton.element.visible(detail.canReceiveEmailNotifications, displayValue = "inline-flex")
        watchButton.render(watching = detail.notifyOnComments, isEnabled = !state.isBusy)

        // innerHTML, like every other markdown surface here: renderMarkdown
        // sanitises, and it is the only thing that may produce HTML in this app.
        // A PREFIX-NUMBER in a post links to that issue too (LNL-139).
        bodyElement.innerHTML = renderMarkdown(detail.body, ticketSource.prefixes(), titleFor = ticketSource.titleFor)

        commentsHeading.visible(true)
        commentsElement.visible(true, displayValue = "flex")
        renderComments(state)

        renderConfirmations(state)
    }

    /**
     * The comments, in order, and the one-off jump a `?comment=` deep link asks
     * for.
     *
     * Unlike a message thread there is **no** default scroll: a post is read from
     * the top, because the top is the thing being replied to. So the only scroll
     * here is the deep link's, and it happens once — a comment published later must
     * not yank a reader who has scrolled back up to re-read the post.
     */
    private fun renderComments(state: ForumPostBackingViewModel.State) {
        commentsElement.clear()
        val comments = state.detail?.comments.orEmpty()
        if (comments.isEmpty()) {
            commentsElement.appendChild(element("p", "comments-empty", "No comments yet."))
            return
        }
        val cards = comments.associate { it.id to renderComment(state, it) }
        comments.forEach { commentsElement.appendChild(cards.getValue(it.id)) }

        if (hasScrolled) return
        hasScrolled = true
        // Highlighted as well as scrolled to: arriving from a link into the middle
        // of a long thread, "which of these is the one I was sent" is otherwise a
        // guess. The class marks the arrival rather than the comment, so it is
        // applied to this rendering and is gone from the next one.
        takeScrollTarget()?.let { cards[it] }?.let { target ->
            target.classList.add("forum-comment-linked")
            target.scrollIntoView()
        }
    }

    /** Whether the deep link's jump has already been spent. See [renderComments]. */
    private var hasScrolled = false

    /**
     * One comment card: avatar initial, byline, optional agent badge, Delete, body.
     *
     * Rebuilt with the comment on each render rather than kept and patched, which
     * is affordable here for the reason `IssueWindow.renderComment` gives — a
     * thread is tens of rows, not hundreds — and is what lets the conditional
     * Delete button be a plain append instead of a persistent element to toggle.
     *
     * No Edit button, and no reply. Editing is not in LNL-61 at all; replying is
     * not in the feature, by LNL-30's flat-comments decision.
     */
    private fun renderComment(
        state: ForumPostBackingViewModel.State,
        comment: ForumCommentView,
    ): HTMLElement {
        val card = element("article", "comment forum-comment")
        val head = element("div", "comment-head")
        val meta = element("div", "comment-meta")
        meta.appendChild(element("span", "forum-avatar", state.commentInitial(comment)))
        meta.appendChild(element("span", "comment-author", state.commentByline(comment)))
        state.commentAgentBadge(comment)?.let { text ->
            val badge = element("span", "agent-badge")
            badge.children(agentIcon(), element("span", "agent-badge-label", text))
            meta.appendChild(badge)
        }
        head.appendChild(meta)
        if (comment.canDelete) {
            head.appendChild(
                button("Delete", "link-btn link-btn-danger") {
                    viewModel.onDeleteCommentTapped(comment.id)
                },
            )
        }
        val body = element("div", "markdown comment-body")
        body.innerHTML = renderMarkdown(comment.body, ticketSource.prefixes(), titleFor = ticketSource.titleFor)
        card.children(head, body)
        return card
    }

    /**
     * Put a confirmation up, or take it down, following the state.
     *
     * Two of them, and both are driven from state rather than opened at the click
     * — the same shape `IssueWindow` uses — so that a delete confirmed in one
     * place and a delete cancelled in another cannot leave a dialog behind.
     */
    private fun renderConfirmations(state: ForumPostBackingViewModel.State) {
        val host = dialogHost ?: return

        if (state.confirmingDeletePost && postConfirm == null) {
            postConfirm = ConfirmDialog(
                title = "Delete post",
                message = state.confirmDeletePostMessage,
                destructiveLabel = "Delete",
                onConfirm = { viewModel.onDeletePostConfirmed() },
                onCancel = { viewModel.onDeletePostCancelled() },
            ).also { it.mount(host) }
        } else if (!state.confirmingDeletePost && postConfirm != null) {
            postConfirm?.dismiss()
            postConfirm = null
        }

        if (state.confirmingDeleteCommentId != null && commentConfirm == null) {
            commentConfirm = ConfirmDialog(
                title = "Delete comment",
                message = "Delete this comment? This cannot be undone.",
                destructiveLabel = "Delete",
                onConfirm = { viewModel.onDeleteCommentConfirmed() },
                onCancel = { viewModel.onDeleteCommentCancelled() },
            ).also { it.mount(host) }
        } else if (state.confirmingDeleteCommentId == null && commentConfirm != null) {
            commentConfirm?.dismiss()
            commentConfirm = null
        }
    }

    /**
     * Take the confirmations down, and stop the composer.
     *
     * Called by the host when the post closes. The view's own scope cancellation
     * stops its collector but reaches neither of these: a dialog mounted into a host
     * that outlives this view, and a composer scope this class created itself.
     *
     * The composer is not *cancelled*, only stopped. There is nothing to undo unless
     * a file was attached, and a draft comment that owns one is exactly what the
     * server's startup sweep is for — where the Messages tab genuinely has to
     * discard, because an unsent **conversation** would be a row nobody can see and
     * nobody can remove. See `ConversationWindows.dispose`, which does the other
     * thing for that reason.
     */
    fun dispose() {
        postConfirm?.dismiss()
        postConfirm = null
        commentConfirm?.dismiss()
        commentConfirm = null
        composerScope?.cancel()
        composerScope = null
    }
}
