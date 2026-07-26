/**
 * Starting a private conversation: who it is going to, and the first thing said.
 *
 * ── Why this is a pane rather than a modal ──────────────────────────────────
 *
 * LNL-60 wrote this as `MessageComposerDialog`, one modal serving both a new
 * conversation and a reply. LNL-64 deleted that file. The WYSIWYG editor belongs in
 * a **pane** — that is where the issue editor has always lived, in the issue
 * window, and it works — so the reply went inline at the foot of the thread (see
 * [ConversationView]) and this took the thread's own pane, with a recipient picker
 * where the transcript would be.
 *
 * One pane for both is not a coincidence to be tidied up later; it is the correct
 * reading of what the Messages tab is. The tab has a list and a thing you are doing
 * with it, and "starting a conversation" and "reading one" are two answers to the
 * same question — which is why they share `CONVERSATION_PANE_ID`, and why starting
 * one *becomes* reading one the moment it is sent, in the same window, with nothing
 * opening or closing.
 *
 * Nothing here manages its own height. The pane is draggable, resizable and
 * maximisable, so how much of it the editor takes is the reader's to decide with
 * the affordances the toolkit already gives them — the same deal the issue editor
 * has. See [ConversationView], which says this at greater length.
 *
 * ── The recipient picker, which is a deliberate departure from the design ────
 *
 * The prototype picks recipients with a **row of pills**. LNL-30 replaces that, in
 * one sentence: *"Unlike in the design, when writing a new private message we
 * should not have pills to pick the destination user. Instead we have a search box
 * they type into, with autocomplete, very similar to the WYSIWYG editor's `@`
 * mention popup."* So that is what this is, and it is modelled on [MarkdownEditor]'s
 * popup on purpose — same keyboard contract (↑/↓ to move, Enter or Tab to take,
 * Escape to close), same "the row the mouse is over is the row Enter would take"
 * highlight, so the two feel like one control that appears in two places.
 *
 * What survives from the design is what happens *after* a name is taken: the chosen
 * recipients are still shown as a row of removable chips, because a list of who a
 * message is going to has to be visible while the body is being written. The ticket
 * replaces the way names are **found**, not the way they are shown.
 *
 * Two things it deliberately does not share with the mention popup:
 *
 *  - It matches by name and answers with an **id**. See
 *    `MessageComposerBackingViewModel.completions`, which explains why it is not a
 *    call to `mentionCompletions` despite applying its rule.
 *  - It closes when the conversation starts. Membership is fixed at creation, so
 *    once the first attachment has created the row there is nothing left to pick —
 *    the view says so in a sentence rather than greying the box.
 *
 * Escape is **not** handled here beyond closing the popup. In a modal, Escape was
 * "cancel the whole thing"; in a pane it is not, because a pane is closed by its
 * chrome like every other window in this app. See `ConversationWindows`.
 *
 * @see MessageComposerBackingViewModel
 * @see ConversationView
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunicle.client.viewmodel.MessageComposerBackingViewModel
import se.soderbjorn.lunicle.clientserver.UserOption

/**
 * Renders the new-conversation pane.
 *
 * @param viewModel the composer's state and intents. Built by the window, so this
 *   view — like [ConversationView] — knows nothing about where it is mounted.
 * @param scope this view's lifetime, cancelled when the window closes.
 */
class NewConversationView(
    private val viewModel: MessageComposerBackingViewModel,
    private val scope: CoroutineScope,
) {
    /** The view's root, appended by whatever is hosting it. */
    val root: HTMLElement = element("div", "conversation new-conversation")

    private lateinit var editor: MarkdownEditor

    private val chosenHost = element("div", "recipient-chosen")
    private val searchField: HTMLInputElement =
        textField("Search for someone…") { viewModel.onQueryChanged(it) }
    private val menu = element("div", "recipient-menu")
    private val lockNote = element("p", "recipient-lock-note")
    // `editor-host` for the reason [NewForumPostView] gives: it is the one class
    // that means "this editor fills what it is mounted in", and the pane's copy of
    // those rules is gone from the stylesheet in favour of it.
    private val editorHost = element("div", "editor-host conversation-composer-editor")
    private val validationElement = element("p", "field-validation")
    private val errorElement = element("p", "modal-error")
    private val sendButton: HTMLButtonElement = button("Send", "btn btn-primary") {
        viewModel.onSendTapped()
    }

    /** What the popup is currently offering, and which row is selected. */
    private var options: List<UserOption> = emptyList()
    private var index: Int = 0

    /** Build the tree and start following the state. */
    fun start() {
        errorElement.setAttribute("role", "status")

        val header = element("div", "conversation-header")
        header.appendChild(element("h3", "conversation-heading", "New message"))

        editor = MarkdownEditor(
            scope = scope,
            onChange = { viewModel.onBodyChanged(it) },
            onUpload = { name, mime, bytes -> viewModel.uploadAttachment(name, mime, bytes) },
            placeholder = "Write your message…",
        )
        editor.mount(editorHost)
        // A new conversation has no mentionable names and never gains any while it
        // is being written: who may be mentioned is who is in the room, and the room
        // does not exist until Send. Mentioning somebody in the very message that
        // creates the thread would be naming a person who is, by definition, already
        // being told about it. So the feature is simply off here — see
        // MarkdownEditor.setMentionNames, for which an empty list means exactly that.

        val actions = element("div", "conversation-composer-actions")
        actions.appendChild(sendButton)

        val composer = element("div", "conversation-composer")
        composer.children(editorHost, validationElement, errorElement, actions)

        root.children(header, buildRecipientRow(), composer)

        scope.launch { viewModel.stateFlow.collect(::render) }
        // Deliberately no `start()`: for a new conversation it does nothing at all —
        // there is nothing to create until somebody has been chosen. See
        // MessageComposerBackingViewModel.
    }

    private fun buildRecipientRow(): HTMLElement {
        // `field` kept, not replaced: `textField` hands back an input already
        // wearing `.field`, which is what gives it the panel-dark fill, border and
        // padding every other input here has. Assigning a bare "recipient-search"
        // dropped all of it, so the box fell back to the browser's default — the
        // off, light-grey fill LNL-82 flagged as looking wrong against the pane.
        searchField.className = "field recipient-search"
        menu.setAttribute("role", "listbox")
        // The popup's own mousedown is prevented so that clicking a row does not
        // blur the field first, which would close the popup before the click
        // landed. MarkdownEditor's mention menu does exactly this.
        menu.addEventListener("mousedown", { event -> event.preventDefault() })

        // The popup owns four keys while it is open, and only while it is open —
        // the same contract the mention menu has, so the two controls feel like one
        // thing.
        searchField.addEventListener("keydown", { event ->
            val key = (event as KeyboardEvent).key
            if (options.isEmpty()) return@addEventListener
            when (key) {
                "ArrowDown" -> { event.preventDefault(); move(1) }
                "ArrowUp" -> { event.preventDefault(); move(-1) }
                "Enter", "Tab" -> {
                    event.preventDefault()
                    options.getOrNull(index)?.let { viewModel.onRecipientChosen(it) }
                }
                "Escape" -> { event.stopPropagation(); options = emptyList(); drawMenu() }
                "Backspace" -> {
                    // An empty box plus Backspace removes the last chosen name, which
                    // is the gesture every recipient field has. Only when empty: it
                    // must not eat a character somebody is deleting.
                    if (searchField.value.isEmpty()) {
                        viewModel.stateFlow.value.chosen.lastOrNull()?.let {
                            viewModel.onRecipientRemoved(it.id)
                        }
                    }
                }
            }
        })
        searchField.addEventListener("input", { refreshMenu() })
        searchField.addEventListener("focus", { refreshMenu() })

        val searchWrap = element("div", "recipient-search-wrap")
        searchWrap.children(searchField, menu)

        val row = element("div", "field recipient-row")
        row.children(element("label", "field-label", "To"), chosenHost, searchWrap, lockNote)
        return row
    }

    private fun render(state: MessageComposerBackingViewModel.State) {
        renderChosen(state)
        searchField.disabled = state.areRecipientsLocked || state.isBusy
        searchField.visible(!state.areRecipientsLocked, displayValue = "block")
        searchField.setValueIfChanged(state.query)
        lockNote.setTextIfChanged(state.lockNote.orEmpty())
        lockNote.visible(state.lockNote != null)
        refreshMenu()

        // Not `!isBusy` alone: an upload must leave the editor live, because that is
        // where the upload's markdown is about to be inserted. See
        // MessageComposerBackingViewModel.State.isUploading.
        editor.setEnabled(state.isEditorEnabled)

        validationElement.setTextIfChanged(state.validationMessage.orEmpty())
        validationElement.visible(state.validationMessage != null)
        errorElement.setTextIfChanged(state.errorMessage.orEmpty())
        errorElement.visible(state.errorMessage != null)

        sendButton.disabled = !state.isSendEnabled
    }

    /**
     * The chosen recipients, as removable chips.
     *
     * Rebuilt on each render rather than kept and patched: this is a handful of
     * elements that changes only when somebody presses something, so there is
     * nothing to diff that would not cost more than the rebuild. The × disappears
     * once the conversation exists, because there is then nothing it could do.
     */
    private fun renderChosen(state: MessageComposerBackingViewModel.State) {
        chosenHost.clear()
        chosenHost.visible(state.chosen.isNotEmpty(), displayValue = "flex")
        state.chosen.forEach { option ->
            val chip = element("span", "recipient-chip")
            chip.appendChild(element("span", "recipient-chip-name", option.name))
            if (!state.areRecipientsLocked) {
                chip.appendChild(
                    button("×", "recipient-chip-remove") { viewModel.onRecipientRemoved(option.id) },
                )
            }
            chosenHost.appendChild(chip)
        }
    }

    /**
     * Ask the view model what is still worth offering, and redraw.
     *
     * Driven from the field's own events *and* from every render, so there is a
     * single answer to "what is being offered" rather than one per entry point.
     * `refreshMentionMenu` is arranged the same way for the same reason.
     */
    private fun refreshMenu() {
        val previous = options.getOrNull(index)
        options = if (searchField.disabled) emptyList() else viewModel.completions()
        index = options.indexOfFirst { it.id == previous?.id }.takeIf { it >= 0 } ?: 0
        drawMenu()
    }

    private fun drawMenu() {
        menu.clear()
        // Only while the box has focus. A list hanging under an unfocused field
        // covers the body being written and belongs to a gesture that is over.
        val open = options.isNotEmpty() && document.activeElement === searchField
        menu.visible(open)
        if (!open) return
        options.forEachIndexed { position, option ->
            val row = element("div", "recipient-menu-item", option.name)
            row.setAttribute("role", "option")
            row.setAttribute("aria-selected", (position == index).toString())
            if (position == index) row.classList.add("recipient-menu-item-on")
            // Hover moves the selection, so the row the mouse is over is the row
            // Enter would take — there is only ever one "this one" on screen. It
            // only repaints the highlight, never rebuilding the list, for the same
            // reason the row acts on mousedown below.
            row.addEventListener("mouseenter", { setHighlight(position) })
            // mousedown, not click — the fix for "clicking a person does nothing"
            // (LNL-82). A click needs mousedown and mouseup on the same element,
            // and a trackpad press's tiny travel used to fire `mouseenter` between
            // them, which — when it rebuilt the list — replaced the row and lost
            // the click. mousedown fires on the press itself; the menu's own
            // mousedown-preventDefault keeps the field focused so the popup stays
            // up while the chip is added. The mention popup carries the same fix.
            row.addEventListener("mousedown", { event ->
                event.preventDefault()
                viewModel.onRecipientChosen(option)
            })
            menu.appendChild(row)
        }
    }

    private fun move(delta: Int) {
        if (options.isEmpty()) return
        val size = options.size
        setHighlight(((index + delta) % size + size) % size)
    }

    /**
     * Move the highlight to [next] without rebuilding the list.
     *
     * Repaints the `-on` class and `aria-selected` on the rows already drawn
     * rather than tearing them down, so a hovered row survives being pressed —
     * [drawMenu] replaces every row, and doing that on `mouseenter` swapped the
     * element out between a press's mousedown and mouseup (LNL-82). Both the arrow
     * keys and hover come through here. The rows do not move, so nothing to
     * reposition. MarkdownEditor.setMentionHighlight is the twin.
     */
    private fun setHighlight(next: Int) {
        if (next == index) return
        index = next
        val rows = menu.children
        for (i in 0 until rows.length) {
            val row = rows.item(i) ?: continue
            val on = i == index
            if (on) row.classList.add("recipient-menu-item-on") else row.classList.remove("recipient-menu-item-on")
            row.setAttribute("aria-selected", on.toString())
        }
    }
}
