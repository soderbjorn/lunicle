/**
 * The modal shell, and the confirmation dialog.
 *
 * Both are Lunamux-flavoured: a dimmed backdrop, a bordered panel, a title bar,
 * and a footer whose buttons sit right. Nothing here knows what it is wrapping.
 *
 * @see ProjectDialog
 * @see IssueDialog
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.KeyboardEvent

/**
 * A modal: backdrop, panel, title, body, footer.
 *
 * @param title shown in the title bar.
 * @param onDismiss called for Escape and for a backdrop click — the two ways to
 *   leave a dialog without touching a button. Wired to the same intent as
 *   Cancel, so a draft discarded with Escape is discarded the same way as one
 *   dismissed with the button. Getting that wrong would leave a draft row behind
 *   every time someone hit Escape.
 * @param isLarge for the dialogs that hold *prose* — an issue and its comments,
 *   or a comment being written. They get a fixed, generous panel whose body
 *   scrolls inside itself, rather than the default panel that grows to fit and
 *   takes the backdrop's scrollbar with it. See .modal-large.
 */
class Modal(
    title: String,
    private val onDismiss: () -> Unit,
    isLarge: Boolean = false,
) {
    private val backdrop = element("div", "modal-backdrop")
    private val panel = element("div", if (isLarge) "modal modal-large" else "modal")
    private val titleElement = element("h2", "modal-title", title)

    /** Where the caller puts the dialog's content. */
    val body: HTMLElement = element("div", "modal-body")

    /** Where the caller puts the buttons. */
    val footer: HTMLElement = element("div", "modal-footer")

    private var keyListener: ((org.w3c.dom.events.Event) -> Unit)? = null

    /** Build and attach. */
    fun mount(host: HTMLElement) {
        panel.children(titleElement, body, footer)
        backdrop.appendChild(panel)

        // A click on the backdrop dismisses; a click *inside* the panel must not.
        // Without the target check, every click on a field would close the
        // dialog, because the event bubbles to the backdrop.
        backdrop.onclick = { event ->
            if (event.target == backdrop) onDismiss()
        }

        val listener: (org.w3c.dom.events.Event) -> Unit = { event ->
            // Only the topmost modal answers Escape. Every open modal has a
            // listener on the *document*, so without this check one Escape
            // dismisses the whole stack at once — and that is not just untidy:
            //
            // The comment modal opens over the issue modal. Dismissing the issue
            // tells MainScreen the dialog closed, which cancels the comment
            // dialog's coroutine scope. The comment's own Cancel had just
            // launched a DELETE for its draft row into that scope, so the
            // request dies in flight and the draft is orphaned — invisible on
            // every board, but on the volume forever. That is what `is_draft`
            // was meant to cover for a *crash*, not for pressing Escape.
            //
            // Found by pressing Escape with both modals open and finding an
            // empty draft comment left in the database.
            if ((event as? KeyboardEvent)?.key == "Escape" && isTopmost()) onDismiss()
        }
        keyListener = listener
        // On the document, not the panel: the panel only sees the key when
        // something inside it has focus, and Escape must work regardless.
        document.addEventListener("keydown", listener)

        host.appendChild(backdrop)
    }

    /**
     * Detach, and stop listening.
     *
     * The listener removal is the part that matters: it is on the *document*, so
     * a dialog that forgot to unregister would keep answering Escape forever —
     * and after a few open/close cycles Escape would fire several dead handlers,
     * each trying to dismiss a dialog that is no longer there.
     */
    fun dismiss() {
        keyListener?.let { document.removeEventListener("keydown", it) }
        keyListener = null
        backdrop.remove()
    }

    fun setTitle(value: String) = titleElement.setTextIfChanged(value)

    /**
     * Is this the modal on top?
     *
     * Read from the DOM rather than tracked in a counter here, because the DOM
     * is the thing that decides: modals mount into the same host in open order,
     * so the last `.modal-backdrop` is the one the user is looking at. A counter
     * would be a second source of truth for a fact the document already holds,
     * and it would drift the first time a dialog was dismissed out of order.
     */
    private fun isTopmost(): Boolean {
        val open = document.querySelectorAll(".modal-backdrop")
        // asList(): NodeList's own `get` is nullable-Node indexing that Kotlin's
        // stdlib does not give an identity-comparable type without help.
        return open.asList().lastOrNull() === backdrop
    }
}

/**
 * "That didn't work" — for a failure with nothing to decide.
 *
 * The counterpart to [ConfirmDialog]: same shape, but it asks nothing and offers
 * one way out. It exists because the alternative was a line of red text under the
 * top bar, and a line of red text has two problems that a modal does not:
 *
 *  - **It is missable.** It appears below the thing you clicked, which is not
 *    where you are looking, and the board is busy. "Could not start a new issue."
 *    rendered while the user was already looking at the space where the dialog
 *    should have opened — so the visible outcome of the click was nothing at all.
 *  - **It is unclearable.** It sits there until something else replaces it or the
 *    view model happens to null it out, so a stale failure reads as a current
 *    one. A dialog is dismissed by the person who read it, which is the only
 *    signal that it *was* read.
 *
 * Not a `window.alert`, for the same reasons ConfirmDialog is not a confirm: it
 * blocks the event loop, and inside the lunamux.dev iframe the browser attributes
 * it to the framing page.
 *
 * @param message the sentence to show. The view models already phrase these for
 *   a human — see `userMessage` — so this never decorates or prefixes it.
 */
class AlertDialog(
    private val title: String,
    private val message: String,
    private val onDismiss: () -> Unit,
) {
    private val modal = Modal(title, onDismiss = onDismiss)

    fun mount(host: HTMLElement) {
        modal.body.appendChild(element("p", "modal-message", message))
        // One button, and it is "OK" rather than "Close": there is nothing to
        // cancel, and nothing happens either way. Cancel beside OK on a dialog
        // with no decision in it invites a search for the difference.
        modal.footer.children(
            element("div", "modal-footer-spacer"),
            button("OK", "btn btn-primary") { onDismiss() },
        )
        modal.mount(host)
    }

    fun dismiss() = modal.dismiss()
}

/**
 * "Are you sure?" — for the two things that cannot be undone.
 *
 * A dialog of its own rather than `window.confirm`, and not only for looks: a
 * native confirm blocks the event loop, and inside an iframe the browser
 * attributes it to the *framing* page — so a Lunamux visitor would get a
 * mystery dialog that appears to come from lunamux.dev.
 *
 * @param destructiveLabel the confirming button's text — "Delete", never "OK".
 *   A button that says what it does is the last chance to notice.
 */
class ConfirmDialog(
    private val title: String,
    private val message: String,
    private val destructiveLabel: String,
    private val onConfirm: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private val modal = Modal(title, onDismiss = onCancel)

    fun mount(host: HTMLElement) {
        modal.body.appendChild(element("p", "modal-message", message))
        modal.footer.children(
            button("Cancel", "btn btn-quiet") { onCancel() },
            button(destructiveLabel, "btn btn-danger") { onConfirm() },
        )
        modal.mount(host)
    }

    fun dismiss() = modal.dismiss()
}
