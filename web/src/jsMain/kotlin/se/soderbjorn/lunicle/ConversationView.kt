/**
 * One private conversation, read and answered: who it is with, what was said, and
 * the composer under it.
 *
 * ── Built to be re-hosted, exactly as ForumPostView is ──────────────────────
 *
 * This class owns its [root] and nothing about where that root lives: it takes no
 * pane id, no window and no host element, and it is handed a
 * [ConversationBackingViewModel] built from one id. LNL-60 rendered it beside the
 * conversation list inside a single pane; LNL-64 put it in a **window** of its own,
 * and — as this file's own doc predicted when `ForumPostView` was lifted the same
 * way — that was a change of who appends [root] rather than a rewrite.
 *
 * No `mount(host)`, for `BoardWindow`'s reason: whatever holds this re-parents
 * [root] on every shell re-render, so there is no stable host to be mounted into.
 *
 * ── The composer is inline, which is the design and is now affordable ───────
 *
 * The prototype puts a text box and a **Send** button pinned under the thread.
 * LNL-60 built a "Reply" button opening the shared modal instead, on an argument it
 * stated honestly: an inline [MarkdownEditor] would compete with the transcript for
 * the height of a pane that was already only half the Messages tab. It ended
 * *"worth revisiting if a thread ever gets a window of its own"*, and it now has
 * one — so this is that revisit, and the answer came out the other way.
 *
 * What makes it affordable is that the thread is now a whole **pane**: draggable,
 * resizable and maximisable like any other window, so how much room the composer
 * has is the reader's to decide with the affordances the toolkit already gives
 * them. That is the same deal the issue editor has in its own window, and it is why
 * there is no height arithmetic anywhere below and none in the stylesheet either —
 * the editor is given its own natural height (`.editor-surface`'s `min-height`) and
 * the transcript takes the rest. A cap with an inner scrollbar was considered and
 * rejected: a scroll region inside a scroll region, sized by a number nobody chose,
 * to solve a problem the window manager already solves.
 *
 * What is *not* lost by inlining it, and would have been by using the prototype's
 * bare `<textarea>`: attachments, `@` mentions and the formatting toolbar are all
 * the editor's, and they are why this reuses [MessageComposerBackingViewModel]
 * rather than posting a body itself.
 *
 * The one real cost is a draft row's timing, and it is handled in the view model
 * rather than here: this bar lives as long as the window does, so it must not
 * create a draft message on open the way a short-lived composer safely could. See
 * `MessageComposerBackingViewModel.ensureDraft`.
 *
 * ── Following the design, elsewhere ─────────────────────────────────────────
 *
 * A header naming who you are talking to, then a column of message cards each with
 * an avatar initial and a byline. Your own messages are drawn differently from
 * everybody else's, as the prototype shows — tinted card, tinted header band,
 * tinted avatar — which needs `isMine` on the wire, because no user ids cross and
 * the client genuinely cannot work it out; see `MessageView`.
 *
 * @see ConversationBackingViewModel
 * @see MessagesPane
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
import se.soderbjorn.lunicle.client.viewmodel.ConversationBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.MessageComposerBackingViewModel
import se.soderbjorn.lunicle.clientserver.MessageView

/**
 * Renders one conversation.
 *
 * @param viewModel this conversation's state and intents.
 * @param scope the view's lifetime. Cancelled by whoever built it when the
 *   conversation closes — this is made and thrown away per conversation, so it
 *   gets a scope of its own.
 * @param conversationId the thread being written into. Passed rather than read off
 *   the state, because the composer is built before the first fetch answers and a
 *   composer that could not be used until the transcript arrived would be a box
 *   that swallows the first thing anybody types.
 * @param storage the client's repository, for the composer's view model.
 * @param takeScrollTarget the message a deep link asked to land on, asked for once
 *   when the thread first loads. A function rather than a value because it is
 *   one-shot on the other side too — see
 *   `MessagesBackingViewModel.takePendingMessageId`, which clears it by being
 *   read. A view that held the id would re-scroll on every later render, over a
 *   reader who had scrolled somewhere themselves.
 * @param onWritten something was sent from here. The conversation *list* carries a
 *   preview, a timestamp, an order and an unread count per row, all four of which
 *   a sent message moves — and since LNL-64 that list is on screen beside this
 *   window rather than behind it, so refreshing on close would leave a visibly
 *   stale row for as long as somebody kept reading.
 */
class ConversationView(
    private val viewModel: ConversationBackingViewModel,
    private val scope: CoroutineScope,
    private val conversationId: Long,
    private val storage: StorageRepository,
    private val takeScrollTarget: () -> Long?,
    private val onWritten: () -> Unit,
) {
    /** The view's root, appended by whatever is hosting it. */
    val root: HTMLElement = element("div", "conversation")

    private val headingElement = element("h3", "conversation-heading")
    private val errorElement = element("p", "modal-error")
    private val messagesElement = element("div", "conversation-messages")

    private val composerElement = element("div", "conversation-composer")
    private val composerEditorHost = element("div", "conversation-composer-editor")
    private val composerValidation = element("p", "field-validation")
    private val composerError = element("p", "modal-error")
    private val sendButton: HTMLButtonElement = button("Send", "btn btn-primary") {
        composerViewModel?.onSendTapped()
    }

    /**
     * The editor, built once for this view's life.
     *
     * Its callbacks go through [composerViewModel], which is replaced after every
     * successful send — a published draft cannot be written into twice, so the
     * *model* is new each time while the surface the reader is typing on is not.
     * Rebuilding the editor instead would take the caret and the toolbar state with
     * it every time somebody pressed Send.
     */
    private lateinit var editor: MarkdownEditor

    /** The message currently being written, or null before the first one is set up. */
    private var composerViewModel: MessageComposerBackingViewModel? = null

    /** The current composer's scope, cancelled when it is replaced. */
    private var composerScope: CoroutineScope? = null

    private var confirm: ConfirmDialog? = null
    private var dialogHost: HTMLElement? = null

    /** The mention names the editor was last given, so it is not told the same list twice. */
    private var mentionNames: List<String>? = null

    /**
     * Whether the thread has already been scrolled once.
     *
     * Not on the state, because it is not something anybody renders — it is the
     * difference between "the messages just arrived" and "the messages were
     * already here", and it exists so that a reply landing does not throw the
     * reader back to the deep-linked message they arrived at.
     */
    private var hasScrolled = false

    /**
     * Build the tree and start following the state.
     *
     * @param dialogHost where the delete confirmation mounts. Passed in rather than
     *   found, because this view does not know what is above it — see the class
     *   doc.
     */
    fun start(dialogHost: HTMLElement) {
        this.dialogHost = dialogHost
        errorElement.setAttribute("role", "status")
        composerError.setAttribute("role", "status")

        val header = element("div", "conversation-header")
        header.appendChild(headingElement)

        editor = MarkdownEditor(
            scope = scope,
            // Through the current composer rather than a captured one: see the
            // field's doc. A keystroke arriving between two composers is not a
            // state this can be in — the replacement is synchronous.
            onChange = { composerViewModel?.onBodyChanged(it) },
            onUpload = { name, mime, bytes -> composerViewModel?.uploadAttachment(name, mime, bytes) },
            placeholder = "Write a reply…",
        )
        editor.mount(composerEditorHost)

        val composerActions = element("div", "conversation-composer-actions")
        composerActions.appendChild(sendButton)
        composerElement.children(composerEditorHost, composerValidation, composerError, composerActions)

        root.children(header, errorElement, messagesElement, composerElement)

        newComposer()
        scope.launch { viewModel.stateFlow.collect(::render) }
        viewModel.start()
    }

    private fun render(state: ConversationBackingViewModel.State) {
        errorElement.setTextIfChanged(state.errorMessage.orEmpty())
        errorElement.visible(state.errorMessage != null)

        val detail = state.detail
        if (detail == null) {
            // Before the first answer, say nothing at all rather than "no messages
            // yet" over a thread that has forty. Every other pane in this app takes
            // the same position about its own first render.
            headingElement.setTextIfChanged("")
            messagesElement.clear()
            composerElement.visible(false)
            return
        }

        headingElement.setTextIfChanged(state.heading)
        // False for a system administrator reading somebody else's thread, which is
        // the only case where reading and replying come apart. See
        // AccessControl.canWriteInConversation.
        composerElement.visible(detail.canReply)
        // Only when they have actually changed: setMentionNames hides the popup, so
        // handing it the same list on every tick would close a menu mid-selection.
        if (state.mentionableNames != mentionNames) {
            mentionNames = state.mentionableNames
            editor.setMentionNames(state.mentionableNames)
        }

        renderMessages(state)
        renderConfirmation(state)
    }

    /**
     * Start a fresh message.
     *
     * Called once at [start] and again after every successful send. A published
     * draft is not writable a second time — a message has no edit route at all, for
     * anybody — so "the reader is writing another one" is genuinely a new composer
     * rather than a reset of the old one, and expressing it as a replacement is
     * what stops a second Send from trying to publish an id the server has already
     * closed.
     */
    private fun newComposer() {
        composerScope?.cancel()
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        composerScope = localScope
        val composer = MessageComposerBackingViewModel(
            target = MessageComposerBackingViewModel.Target.Reply(conversationId),
            storage = storage,
            scope = localScope,
            // Empty: the popup's names are set on the editor directly from the
            // conversation's own state, because that arrives after this is built.
            mentionableNames = emptyList(),
            onFinished = { _, detail ->
                if (detail != null) {
                    // The reply answered with the whole conversation, so the
                    // transcript above updates from what has already arrived rather
                    // than asking again — and the list beside this window is stale.
                    viewModel.onDetailReceived(detail)
                    onWritten()
                }
                editor.setValue("")
                newComposer()
            },
        )
        composerViewModel = composer
        // Deliberately no `start()`: that would create the draft row now, and this
        // bar exists for as long as the window does. See the class doc.
        localScope.launch { composer.stateFlow.collect(::renderComposer) }
    }

    private fun renderComposer(state: MessageComposerBackingViewModel.State) {
        editor.setEnabled(state.isEditorEnabled)
        sendButton.disabled = !state.isSendEnabled
        // Only once there is something to say. An empty box explaining that it is
        // empty is noise under a thread somebody is reading — unlike the modal,
        // where the same line is the answer to "why is Send greyed out" on a form
        // the reader deliberately opened.
        val validation = state.validationMessage.takeIf { state.body.isNotEmpty() }
        composerValidation.setTextIfChanged(validation.orEmpty())
        composerValidation.visible(validation != null)
        composerError.setTextIfChanged(state.errorMessage.orEmpty())
        composerError.visible(state.errorMessage != null)
    }

    /**
     * The messages, oldest first.
     *
     * Rebuilt on each render rather than kept and patched, which is affordable for
     * `IssueWindow.renderComment`'s reason — a thread is tens of cards, not
     * hundreds — and is what lets the conditional Delete button be a plain append
     * instead of a persistent element to toggle.
     *
     * The scroll afterwards is the part with a decision in it. A thread is read
     * from the bottom, because the bottom is what is new; a deep link overrides
     * that, because the whole point of `?message=` is to land somewhere specific.
     * Both happen **once**, on the first render that has messages in it — a reply
     * arriving later must not yank a reader who has scrolled up to re-read
     * something.
     */
    private fun renderMessages(state: ConversationBackingViewModel.State) {
        messagesElement.clear()
        val messages = state.detail?.messages.orEmpty()
        if (messages.isEmpty()) {
            messagesElement.appendChild(
                element("p", "conversation-empty", "Nothing has been said here yet."),
            )
            return
        }
        val cards = messages.associate { it.id to renderMessage(state, it) }
        messages.forEach { messagesElement.appendChild(cards.getValue(it.id)) }

        if (hasScrolled) return
        hasScrolled = true
        val target = takeScrollTarget()?.let { cards[it] }
        if (target != null) {
            // Highlighted as well as scrolled to: arriving from an e-mail in the
            // middle of a long thread, "which of these is the one I was told
            // about" is otherwise a guess. The class is permanent for this
            // rendering and goes on the next one, which is the right lifetime —
            // it marks the arrival, not the message.
            target.classList.add("message-card-linked")
            target.scrollIntoView()
        } else {
            messagesElement.scrollTop = messagesElement.scrollHeight.toDouble()
        }
    }

    /**
     * One message card: avatar initial, byline, optional agent badge, Delete, body.
     *
     * No Edit, and not because it was left out: a message cannot be edited by
     * anybody, its author included, and there is no route that would accept one.
     * See the server's Messages.sq.
     *
     * The byline row is banded — its own fill and a rule under it — exactly as a
     * forum comment's is, and for the design's reason: a long thread separates far
     * better for it. Your own messages take the accent-tinted variant of both the
     * band and the card; see `.message-card-mine`.
     */
    private fun renderMessage(
        state: ConversationBackingViewModel.State,
        message: MessageView,
    ): HTMLElement {
        val card = element("article", "message-card")
        if (message.isMine) card.classList.add("message-card-mine")

        val head = element("div", "comment-head")
        val meta = element("div", "comment-meta")
        meta.appendChild(element("span", "forum-avatar", state.initial(message)))
        meta.appendChild(element("span", "comment-author", state.byline(message)))
        state.agentBadge(message)?.let { text ->
            val badge = element("span", "agent-badge")
            badge.children(agentIcon(), element("span", "agent-badge-label", text))
            meta.appendChild(badge)
        }
        head.appendChild(meta)
        if (message.canDelete) {
            head.appendChild(
                button("Delete", "link-btn link-btn-danger") {
                    viewModel.onDeleteMessageTapped(message.id)
                },
            )
        }
        val body = element("div", "markdown comment-body")
        // innerHTML, like every other markdown surface here: renderMarkdown
        // sanitises, and it is the only thing that may produce HTML in this app.
        body.innerHTML = renderMarkdown(message.body)
        card.children(head, body)
        return card
    }

    /**
     * Put the confirmation up, or take it down, following the state.
     *
     * Driven from state rather than opened at the click — the shape `IssueWindow`
     * and `ForumPostView` both use — so that a delete confirmed in one place and
     * cancelled in another cannot leave a dialog behind.
     */
    private fun renderConfirmation(state: ConversationBackingViewModel.State) {
        val host = dialogHost ?: return
        if (state.confirmingDeleteMessageId != null && confirm == null) {
            confirm = ConfirmDialog(
                title = "Delete message",
                // Says what it takes with it, because there is no undo and no
                // history: once this is gone, nothing anywhere recorded that it was
                // ever said.
                message = "Delete this message? Everybody in the conversation will " +
                    "stop seeing it, and this cannot be undone.",
                destructiveLabel = "Delete",
                onConfirm = { viewModel.onDeleteMessageConfirmed() },
                onCancel = { viewModel.onDeleteMessageCancelled() },
            ).also { it.mount(host) }
        } else if (state.confirmingDeleteMessageId == null && confirm != null) {
            confirm?.dismiss()
            confirm = null
        }
    }

    /**
     * Take the confirmation down, and stop the composer.
     *
     * Called by the host when the conversation closes. The view's own scope
     * cancellation stops its collector but reaches neither of these: a dialog
     * mounted into a host that outlives this view, and a composer scope this class
     * created itself.
     */
    fun dispose() {
        confirm?.dismiss()
        confirm = null
        composerScope?.cancel()
        composerScope = null
    }
}
