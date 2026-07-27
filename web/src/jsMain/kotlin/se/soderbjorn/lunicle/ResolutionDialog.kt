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
 * ── Radios and a Confirm, and a controlled component (LNL-134) ────────────────
 *
 * It used to be a button per resolution — the button *was* the choice, no OK. That
 * stopped working once closing as *done* can demand a fixed version: the dialog now
 * has a second question that only appears after the first is answered, so it needs a
 * moment between "which resolution" and "commit". The resolutions become radios, and
 * picking a *done* one (read from [StatusItem.isDone], never the name) reveals the
 * fixed-version picker below — required, when the project asks for it, before Confirm
 * lights up.
 *
 * The selection lives in the view model's dialog state, not here, so the picker can
 * **add a version mid-flow**: adding re-emits the dialog with a longer version list
 * and the new id chosen, and [render] repaints in place without a remount losing the
 * resolution already picked. That is why this is built once in [mount] and updated by
 * [render] on every emission — the same shape the board's other state-driven views take.
 *
 * @see ConfirmDialog
 * @see VersionDropdown
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import se.soderbjorn.lunicle.client.viewmodel.ActiveDialog
import se.soderbjorn.lunicle.clientserver.StatusItem

class ResolutionDialog(
    private val ticket: String,
    private val resolutions: List<StatusItem>,
    private val onResolutionPicked: (Long) -> Unit,
    private val onFixedVersionPicked: (Long?) -> Unit,
    private val onVersionAdded: (String) -> Unit,
    private val onVersionRenamed: (Long, String) -> Unit,
    private val onVersionDeleted: (Long) -> Unit,
    private val onConfirm: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private val modal = Modal("Close $ticket", onDismiss = { onCancel() })

    private lateinit var versionCell: HTMLElement
    private lateinit var confirmButton: HTMLButtonElement
    private val radioInputs = mutableMapOf<Long, HTMLInputElement>()
    private val versionPicker = VersionDropdown(
        isField = true,
        allowNone = false,
        onSelect = { onFixedVersionPicked(it) },
        onAdd = { onVersionAdded(it) },
        onRename = { id, name -> onVersionRenamed(id, name) },
        onDelete = { onVersionDeleted(it) },
    )

    fun mount(host: HTMLElement) {
        modal.body.appendChild(element("p", "modal-message", "Why is $ticket being closed?"))

        val choices = element("div", "resolution-choices resolution-choices-radio")
        resolutions.forEach { resolution -> choices.appendChild(radioRow(resolution)) }
        modal.body.appendChild(choices)

        // Hidden until a done resolution is picked. Its label says required, so an
        // empty required picker reads as "you still owe me this" rather than optional.
        versionCell = element("div", "field-cell resolution-version-cell")
        versionCell.children(
            element("span", "field-label", "Fixed in version"),
            versionPicker.element,
        )
        modal.body.appendChild(versionCell)

        confirmButton = button("Close issue", "btn btn-primary") { onConfirm() }
        modal.footer.children(
            element("div", "modal-footer-spacer"),
            button("Cancel", "btn btn-quiet") { onCancel() },
            confirmButton,
        )

        modal.mount(host)
    }

    /** Repaint from the view model's dialog state — selection, versions, and Confirm. */
    fun render(dialog: ActiveDialog.ChooseResolution) {
        radioInputs.forEach { (id, input) -> input.checked = id == dialog.selectedResolutionId }

        val showsVersion = dialog.selectedResolutionId
            ?.let { id -> resolutions.firstOrNull { it.id == id }?.isDone == true }
            ?: false
        val hasVersions = dialog.versions.isNotEmpty()
        // The picker shows once a done resolution is chosen and there is something to
        // pick — or, for an admin, so a first version can be added inline.
        val showsCell = showsVersion && (hasVersions || dialog.canManageVersions)
        versionCell.visible(showsCell, displayValue = "flex")
        versionPicker.canManage = dialog.canManageVersions
        versionPicker.render(dialog.versions, dialog.selectedFixedVersionId, placeholder = "Choose a version")

        val needsVersion = showsVersion && hasVersions && dialog.requireFixedVersion &&
            dialog.selectedFixedVersionId == null
        confirmButton.disabled = dialog.selectedResolutionId == null || needsVersion
    }

    private fun radioRow(resolution: StatusItem): HTMLElement {
        val row = element("label", "resolution-choice-radio")
        val input = document.createElement("input") as HTMLInputElement
        input.type = "radio"
        input.name = "resolution-$ticket"
        input.onchange = { onResolutionPicked(resolution.id); Unit }
        radioInputs[resolution.id] = input
        row.children(input, element("span", "resolution-choice-label", resolution.name))
        return row
    }

    fun dismiss() = modal.dismiss()
}
