/**
 * The forum list manager: a project administrator's create, rename, reorder and
 * delete, in one modal.
 *
 * One dialog rather than four gestures scattered across the pane, for the reason
 * the settings dialog is one dialog: these are all answers to "what forums does
 * this project have", they are all rare, and a pane that carried an inline
 * rename field and a delete button per row would spend its chrome on something
 * most readers never touch.
 *
 * ── Reordering is buttons, not drag ─────────────────────────────────────────
 *
 * Up and down per row. LNL-28's reordering is a drag, and this deliberately is
 * not: that one reorders a list you are already looking at inside the settings
 * dialog, where the drag target is obvious, and it took a bug report to get
 * right. A forum list is short — a handful of rows — and two buttons express the
 * same intent with nothing to mis-grab. The wire format is identical either way
 * (the whole order, see [ForumOrder]), so swapping this for a drag later changes
 * this file and nothing else.
 *
 * Everything here is an affordance. The routes refuse a caller who does not
 * administer the project regardless of whether this dialog was reachable — see
 * AccessControl's preamble.
 *
 * @see ForumBackingViewModel
 * @see ForumPane
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import se.soderbjorn.lunicle.client.viewmodel.ForumBackingViewModel
import se.soderbjorn.lunicle.clientserver.ForumSummary

/**
 * Renders the forum manager.
 *
 * @param viewModel the tab's state and intents — the same instance the pane
 *   renders, so a forum created here appears in the picker behind the modal
 *   without this dialog telling it to.
 * @param scope the dialog's own scope, cancelled when it closes.
 * @param onDismiss the user closed it.
 */
class ForumManagerDialog(
    private val viewModel: ForumBackingViewModel,
    private val scope: CoroutineScope,
    private val onDismiss: () -> Unit,
) {
    private val modal = Modal("Forums", onDismiss = onDismiss, isLarge = true)

    private val rows = element("div", "forum-manager-rows")
    private val newName: HTMLInputElement = textField("New forum name") {}
    private val newDescription: HTMLInputElement = textField("Description (optional)") {}
    private val error = element("p", "forum-manager-error")

    /** The list the rows were last drawn from, so typing in a field is not undone by a tick. */
    private var drawn: List<ForumSummary>? = null

    /**
     * Where this dialog was mounted, kept so the delete confirmation can be
     * mounted beside it rather than inside its scrolling body.
     */
    private var host: HTMLElement? = null

    fun mount(host: HTMLElement) {
        this.host = host
        modal.body.children(
            element(
                "p",
                "modal-message",
                "Forums are shown in this order to everyone who can see the project.",
            ),
            rows,
            newForumRow(),
            error,
        )
        modal.footer.children(
            element("div", "modal-footer-spacer"),
            button("Close", "btn") { onDismiss() },
        )
        modal.mount(host)

        scope.launch { viewModel.stateFlow.collect(::render) }
    }

    fun dismiss() = modal.dismiss()

    private fun newForumRow(): HTMLElement {
        val row = element("div", "forum-manager-new")
        row.children(
            newName,
            newDescription,
            button("Add", "btn btn-primary") {
                val name = newName.value.trim()
                if (name.isEmpty()) return@button
                viewModel.onForumCreated(name, newDescription.value.trim().takeIf { it.isNotEmpty() })
                // Cleared optimistically. The write may still be refused — a
                // duplicate name — and the message says so; keeping the text
                // would leave a field that looks like it is still pending.
                newName.value = ""
                newDescription.value = ""
            },
        )
        return row
    }

    private fun render(state: ForumBackingViewModel.State) {
        val message = state.errorMessage
        error.setTextIfChanged(message.orEmpty())
        error.visible(message != null, displayValue = "block")

        // Only redraw when the list itself changed. A redraw replaces the rename
        // fields, so redrawing on every emission would delete a character the
        // moment somebody typed it and the busy flag flipped.
        if (drawn == state.forums) return
        drawn = state.forums

        rows.clear()
        if (state.forums.isEmpty()) {
            rows.appendChild(element("p", "forum-manager-empty", "No forums yet."))
            return
        }
        state.forums.forEachIndexed { index, forum ->
            rows.appendChild(row(forum, isFirst = index == 0, isLast = index == state.forums.lastIndex))
        }
    }

    private fun row(forum: ForumSummary, isFirst: Boolean, isLast: Boolean): HTMLElement {
        val name = textField("Name") {}
        name.value = forum.name
        val description = textField("Description (optional)") {}
        description.value = forum.description.orEmpty()

        val up = button("↑", "btn forum-manager-move") { viewModel.onForumMoved(forum.id, by = -1) }
        val down = button("↓", "btn forum-manager-move") { viewModel.onForumMoved(forum.id, by = 1) }
        up.disabled = isFirst
        down.disabled = isLast

        val save = button("Save", "btn") {
            val next = name.value.trim()
            if (next.isEmpty()) return@button
            viewModel.onForumEdited(forum.id, next, description.value.trim().takeIf { it.isNotEmpty() })
        }
        // Two presses to destroy a forum, and the second one says what goes with
        // it. Posts cascade (see Forums.sq), so this is not a reversible tidy-up
        // — and unlike an issue, there is no trash to fish it out of.
        val remove = button("Delete", "btn btn-danger") {
            var confirm: ConfirmDialog? = null
            confirm = ConfirmDialog(
                title = "Delete ${forum.name}?",
                message = "Everything posted in this forum goes with it. This cannot be undone.",
                destructiveLabel = "Delete",
                onConfirm = {
                    confirm?.dismiss()
                    viewModel.onForumDeleted(forum.id)
                },
                onCancel = { confirm?.dismiss() },
            )
            // Mounted on the modal's own host rather than on its body, so it
            // layers over this dialog instead of scrolling inside it. Modal's
            // "topmost wins Escape" is what makes the pair behave.
            host?.let(confirm::mount)
        }

        val row = element("div", "forum-manager-row")
        row.children(name, description, up, down, save, remove)
        return row
    }
}
