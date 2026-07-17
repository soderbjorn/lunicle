/**
 * "Why are you closing this?" — asked when a card is dragged into a closing
 * column.
 *
 * The board's counterpart to the issue editor's resolution field. It exists
 * because closing an issue by dragging it is the common path, and the rule that
 * a closed issue has a reason has to hold there too — the server refuses the move
 * without one (see BoardRoutes' resolveResolution), so the choice is between
 * asking first and showing a card that moves, sticks, and jumps back.
 *
 * Asked *before* the write, which is why cancelling costs nothing: the optimistic
 * move has not happened yet. See MainScreenBackingViewModel.onIssueDragged.
 *
 * @see ConfirmDialog
 */
package se.soderbjorn.lunicle

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.clientserver.StatusItem

/**
 * Renders the resolution picker.
 *
 * @param ticket what is being closed, e.g. "LMX-12".
 * @param resolutions the project's own scale, in its own order — "Done" first.
 * @param onChosen the user picked one.
 * @param onCancel the user backed out; the card stays where it was.
 */
class ResolutionDialog(
    private val ticket: String,
    private val resolutions: List<StatusItem>,
    private val onChosen: (resolutionId: Long) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val modal = Modal("Close $ticket", onDismiss = { onCancel() })

    fun mount(host: HTMLElement) {
        modal.body.appendChild(
            element("p", "modal-message", "Why is $ticket being closed?"),
        )

        // A button per resolution rather than a dropdown and an OK. There are two
        // of them, and a two-item dropdown is three interactions (open, pick,
        // confirm) to express one choice. The buttons ARE the choice, which is the
        // same reasoning SignInDialog's provider buttons follow.
        val choices = element("div", "resolution-choices")
        resolutions.forEach { resolution ->
            // The first — "Done" — is primary. It is the answer most of the time,
            // and the order is the project's own: see Resolutions.sq's position.
            val style = if (resolution.position == 0) "btn btn-primary" else "btn"
            choices.appendChild(
                (button(resolution.name, "$style resolution-choice") { onChosen(resolution.id) } as HTMLButtonElement),
            )
        }
        modal.body.appendChild(choices)

        // No OK. The resolution buttons are the confirmation; an OK beside them
        // would be a second press for a choice already made.
        modal.footer.children(
            element("div", "modal-footer-spacer"),
            button("Cancel", "btn btn-quiet") { onCancel() },
        )

        modal.mount(host)
    }

    fun dismiss() = modal.dismiss()
}
