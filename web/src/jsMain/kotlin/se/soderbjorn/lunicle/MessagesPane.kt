/**
 * The Messages tab's main pane: your conversations, as a list.
 *
 * The board's and the forum's counterpart on the third tab, built to the same
 * rules — non-closable, opening maximised, and rendering whatever
 * [MessagesBackingViewModel.State] says exists. Every gesture goes back as an
 * intent; nothing here decides anything.
 *
 * ── The list, and only the list ─────────────────────────────────────────────
 *
 * LNL-60 built this pane as **two hand-drawn columns**: a `.messages-pane` CSS grid
 * of `280px minmax(0, 1fr)`, with a `.conversation-sidebar` whose `border-right`
 * stood in for a splitter and the open thread painted in the right-hand track.
 * LNL-64 took the second column away — reading a conversation is a **window** now,
 * owned by `ConversationWindows` in main.kt — and this file shrank rather than
 * being rewritten, exactly as `ForumPane` did when LNL-62 did the same thing one
 * tab over.
 *
 * The doc this replaced argued that a layout which *hid* the list to show a thread
 * would make every switch a round trip through a back link. That was true, and it
 * was never the alternative: the forum does not hide its list either — its post
 * window is a **sibling**, both visible at once — and the same is now true here.
 * What the hand-drawn grid actually cost was a draggable splitter, pane chrome on
 * the thread, a place in auto layout, focus tracking, the toolkit's "a new pane
 * force-restores a maximized sibling" rule, and looking like the tab beside it. The
 * argument is written out in full on `CONVERSATION_PANE_ID`.
 *
 * ── There is no composer here any more either ───────────────────────────────
 *
 * LNL-60 put both composers behind this pane as **modals**: a "New message" button
 * and a "Reply" button, each opening `MessageComposerDialog`. LNL-64 removed that
 * file. The WYSIWYG editor belongs in a pane — which is where the issue editor has
 * always lived, and it works there — so a reply is written inline at the foot of
 * the thread ([ConversationView]) and a new conversation takes the same pane the
 * thread does, with a recipient picker instead of a transcript
 * ([NewConversationView]).
 *
 * So "New message" is not a button that opens something; it is a button that puts
 * the Messages tab into a state — [MessagesBackingViewModel.State.isComposingNew] —
 * exactly as clicking a row puts it into the state of having a conversation open.
 * Which of the two the window shows is `ConversationWindows`' business, not this
 * pane's.
 *
 * ── Following the design ────────────────────────────────────────────────────
 *
 * The prototype's list is an **article per conversation** — a card with its own
 * surface, border and radius, a 34px avatar circle, the other participants' names,
 * a preview and a time, and an unread pill at the end. That is what
 * [renderList] draws; LNL-60's plain unbordered rows made the list read as a
 * sidebar rather than as the content it is, which was a fair reading of a column
 * that genuinely was a sidebar and stopped being one here.
 *
 * The **recipient picker is a search box, not pills** — LNL-30's explicit
 * departure. That lives in [NewConversationView]; see its preamble.
 *
 * @see MessagesBackingViewModel
 * @see ForumPane
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.client.viewmodel.MessagesBackingViewModel
import se.soderbjorn.lunicle.clientserver.ConversationSummary

/**
 * Renders the Messages pane.
 *
 * @param viewModel the tab's state and intents.
 * @param scope the pane's lifetime. The pane is never disposed — it is the tab's
 *   non-closable main pane, like the board — so this is the app scope rather than
 *   one of its own.
 *
 * Note what it no longer takes: a `dialogHost` and a `storage`. Both were here for
 * the composer modal, and neither has anything to do with a list of rows — which is
 * the clearest evidence that the composer was in the wrong place. This pane now
 * fetches nothing and mounts nothing above itself.
 */
class MessagesPane(
    private val viewModel: MessagesBackingViewModel,
    private val scope: CoroutineScope,
) {
    /** The pane's root, handed to the toolkit as this pane's content. */
    val root: HTMLElement = element("div", "messages-pane")

    private val newButton =
        button("New message", "btn btn-primary messages-new") { viewModel.onNewMessageTapped() }
    private val listHost = element("div", "conversation-list")
    private val listEmpty = element("p", "conversation-list-empty")
    private val errorElement = element("p", "modal-error messages-error")

    /**
     * Build the pane's tree and start following the state.
     *
     * No `mount(host)`, unlike the dialogs: this pane is handed to the toolkit as
     * [root] and re-parented on every shell re-render, so there is no stable host
     * to be mounted into. `BoardWindow` and `ForumPane` have the same shape.
     */
    fun start() {
        val header = element("div", "messages-header")
        header.children(element("h2", "messages-heading", "Messages"), newButton)
        errorElement.setAttribute("role", "status")
        root.children(header, errorElement, listEmpty, listHost)

        scope.launch { viewModel.stateFlow.collect(::render) }
    }

    private fun render(state: MessagesBackingViewModel.State) {
        errorElement.setTextIfChanged(state.errorMessage.orEmpty())
        errorElement.visible(state.errorMessage != null)
        newButton.visible(state.canMessage, displayValue = "inline-flex")

        // Before the first answer, say nothing at all. "No conversations yet" shown
        // to somebody who has forty, for the half-second the request takes, is
        // worse than a blank — the position ForumPane takes about its own first
        // render.
        if (!state.isLoaded) {
            listEmpty.visible(false)
            renderList(state)
            return
        }

        val message = when {
            state.conversations.isNotEmpty() -> null
            state.canMessage -> "No conversations yet. Use “New message” to start one."
            // Two different reasons for an empty list, and they need different
            // sentences: one is "nobody has written to you", the other is "there is
            // nobody you could write to", and telling somebody to press a button
            // that is not there would be the worse of the two mistakes.
            else -> "You share no project with anybody, so there is nobody to message yet."
        }
        listEmpty.setTextIfChanged(message.orEmpty())
        listEmpty.visible(message != null)

        renderList(state)
    }

    /**
     * What the list was last painted from. See [renderList].
     *
     * The list itself is compared by **identity**, not by equality: it is replaced
     * wholesale by every fetch and never edited in place, so a new list is a new
     * object and an unchanged one is the same object. That makes the check free,
     * where comparing tens of data classes field by field on every tick would not
     * be. `ForumPane.paintedPosts` is the twin.
     */
    private var paintedConversations: List<ConversationSummary>? = null
    private var paintedOpenId: Long? = null
    private var paintedLoaded = false

    /**
     * The conversation list, most recently spoken in first.
     *
     * Rebuilt whole rather than patched: it is tens of rows and is re-fetched whole
     * after every write, so there is nothing to diff against that would not first
     * have to be fetched. [ForumPane] says the same about its post list.
     *
     * **But only when something it renders has changed**, and that guard is
     * load-bearing rather than an optimisation. Since LNL-64 this pane's state
     * carries the Messages tab's *focus* as well as its contents, so a mousedown on
     * a row emits before the mouseup — and a rebuild in that window detaches the
     * very row being pressed, so the click never fires and the conversation never
     * opens. That is `deliver()`'s hazard in main.kt one level down; LNL-62 hit it
     * for real in `ForumPane.renderPosts`, where it presented as a click doing
     * nothing at all rather than as an error.
     */
    private fun renderList(state: MessagesBackingViewModel.State) {
        if (state.conversations === paintedConversations &&
            state.openConversationId == paintedOpenId &&
            state.isLoaded == paintedLoaded
        ) {
            return
        }
        paintedConversations = state.conversations
        paintedOpenId = state.openConversationId
        paintedLoaded = state.isLoaded

        listHost.clear()
        if (!state.isLoaded) return
        state.conversations.forEach { conversation ->
            listHost.appendChild(conversationCard(state, conversation))
        }
    }

    /**
     * One conversation, as the design's card: avatar, names, time, preview.
     *
     * An `<article>` rather than a `<div>`, matching the prototype and matching
     * `.forum-post-row` one tab over — each row is a self-contained thing, not a
     * line in a table.
     */
    private fun conversationCard(
        state: MessagesBackingViewModel.State,
        conversation: ConversationSummary,
    ): HTMLElement {
        val row = element("article", "conversation-row")
        if (conversation.id == state.openConversationId) row.classList.add("conversation-row-on")
        val pill = state.unreadPill(conversation)
        // Bold title *and* a count, which LNL-30 asks for as a pair. Two cues rather
        // than one because they answer different questions — the weight says "there
        // is something here" at a glance down the list, the number says how much —
        // and because a colour-only cue is one nobody with a colour deficiency can
        // read.
        if (pill != null) row.classList.add("conversation-row-unread")
        row.appendChild(element("span", "conversation-avatar", state.initial(conversation)))

        val text = element("div", "conversation-row-text")
        val top = element("div", "conversation-row-top")
        top.children(
            element("span", "conversation-row-name", state.heading(conversation)),
            element("span", "conversation-row-time", state.timestamp(conversation)),
        )
        if (pill != null) {
            val badge = element("span", "conversation-row-pill", pill)
            // The number alone reads as "3" beside a name. Screen readers get the
            // sentence; the pill's own text stays short because the card is narrow.
            badge.setAttribute("aria-label", "$pill unread")
            top.appendChild(badge)
        }
        text.children(top, element("div", "conversation-row-preview", state.preview(conversation)))
        row.appendChild(text)

        // The whole row opens it, not a link inside it. A conversation row is
        // one thing to press, exactly as a board card and a post row are.
        row.addEventListener("click", { viewModel.onConversationOpened(conversation.id) })
        return row
    }

}
