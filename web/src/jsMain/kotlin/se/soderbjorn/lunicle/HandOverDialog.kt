/**
 * "Hand over this instance" — a successor, the three consequences, and a typed phrase
 * (LNL-198).
 *
 * ── Why this is not a [ConfirmDialog] ───────────────────────────────────────
 *
 * That dialog asks one question and can gate it behind a typed phrase, which is nearly
 * this. What it cannot do is *change the phrase*: here the phrase names the successor —
 * "hand over to Ada Lovelace" — so it does not exist until somebody has been picked, and it
 * is rewritten every time the pick changes. A picker and a phrase in one dialog is what this
 * class adds, and it borrows ConfirmDialog's exact rule for matching (trimmed,
 * case-insensitive) so the two ceremonies feel like one gesture at two weights.
 *
 * ── The shape, and what it leads with ───────────────────────────────────────
 *
 * The consequence first, then the pick, then the phrase. Leading with "you are the owner,
 * and handing it over means giving it up" is the point: somebody arriving here is often
 * looking for "how do I add a second owner", and there is no such thing — so the dialog
 * answers the question they have before offering the control they asked for.
 *
 * ── An empty picker is a real state ─────────────────────────────────────────
 *
 * Only staff who have signed in may be handed a deployment, so on one that names no domain
 * of its own **nobody** is eligible. That is not an error and not a bug: the dialog shows
 * the server's sentence where the picker would be, and the confirming button never arms.
 * The alternative — greying Hand over… on the Instance tab with three lines of explanation
 * beside it forever — would be read by every owner rather than by the one who went looking.
 *
 * A dumb view like every other here: every string comes from [PendingHandOver], the
 * candidate list is rendered as handed over, and the one decision this makes is whether the
 * typed phrase matches.
 *
 * @see AddPersonDialog for the picker-plus-field shape this follows
 * @see ConfirmDialog for the phrase rule it borrows
 */
package se.soderbjorn.lunicle

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import se.soderbjorn.lunicle.client.viewmodel.HAND_OVER_PICK_FIRST
import se.soderbjorn.lunicle.client.viewmodel.PendingHandOver
import se.soderbjorn.lunicle.client.viewmodel.handOverPhrase
import se.soderbjorn.lunicle.client.viewmodel.handOverPrompt

/**
 * @param pending everything to draw: the lead, the consequences, the candidates and the
 *   labels. See [PendingHandOver].
 * @param onConfirm the chosen successor's id, reported once, when the phrase matches. The
 *   view model re-checks the id against the list and the route re-derives eligibility from
 *   the store — this is the last of three, not the only one.
 */
class HandOverDialog(
    private val pending: PendingHandOver,
    private val onConfirm: (userId: Long) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val modal = Modal(pending.title, onDismiss = onCancel)

    private lateinit var confirmButton: HTMLButtonElement

    /** The phrase to type, echoed for reading and copying. Hidden until somebody is picked. */
    private lateinit var promptElement: HTMLElement
    private lateinit var phraseElement: HTMLElement
    private lateinit var phraseField: HTMLInputElement

    /**
     * Who has been picked, or null until somebody is.
     *
     * Held here rather than in the view model, for [PendingHandOver]'s reason: this lives
     * for the length of one gesture, and reporting each pick would repaint three tabs. Null
     * rather than the first candidate, so the dialog cannot hand a deployment to whoever
     * happened to sort first because nobody opened the menu.
     */
    private var pickedIndex: Int? = null

    private var picker: Dropdown? = null

    fun mount(host: HTMLElement) {
        // `btn-danger`, the same weight a project delete carries. Nothing is destroyed here,
        // but nothing is recoverable either — and the button's colour is the last thing a
        // reader sees before pressing it.
        confirmButton = button(pending.confirmLabel, "btn btn-danger") {
            // Guarded as well as disabled, exactly as ConfirmDialog guards its own: the
            // `disabled` attribute stops the click, and the guard stops a confirm firing if
            // the button is somehow reached while the phrase does not match.
            val index = pickedIndex ?: return@button
            if (!isArmed()) return@button
            onConfirm(pending.candidates[index].userId)
        }
        confirmButton.disabled = true

        promptElement = element("p", "dt-modal-message modal-message")
        phraseElement = element("p", "dt-modal-message modal-message confirm-phrase-prompt")
        phraseField = textField("") { updateArmed() }

        modal.body.children(
            element("p", "dt-modal-message modal-message", pending.lead),
            consequenceList(),
            element("div", "settings-section-rule"),
            element("label", "field-label", pending.pickLabel),
        )
        // A picker, or the sentence saying why there is nothing to pick — never both.
        if (pending.candidates.isEmpty()) {
            modal.body.appendChild(
                element("p", "admin-note", pending.emptyReason ?: HAND_OVER_PICK_FIRST),
            )
        } else {
            val dropdown = Dropdown(isField = true) { id ->
                pickedIndex = id.toInt()
                renderPicker()
                renderPhrase()
                updateArmed()
            }
            picker = dropdown
            renderPicker()
            modal.body.children(
                dropdown.element,
                element("p", "field-hint", pending.pickHint),
                promptElement,
                phraseElement,
                phraseField,
            )
            renderPhrase()
        }

        modal.footer.children(
            button("Cancel", "btn btn-quiet") { onCancel() },
            confirmButton,
        )
        modal.mount(host)
    }

    fun dismiss() = modal.dismiss()

    /**
     * The three consequences, as a list.
     *
     * A real `<ul>` rather than three paragraphs: they are a list, screen readers say how
     * many there are, and "three things change" is itself part of what the dialog is saying.
     */
    private fun consequenceList(): HTMLElement {
        val list = element("ul", "hand-over-consequences")
        pending.consequences.forEach { list.appendChild(element("li", "", it)) }
        return list
    }

    /** Draw the closed control from [pickedIndex]. */
    private fun renderPicker() {
        val items = pending.candidates.mapIndexed { index, candidate ->
            // The address beside the name, because this is the one pick that cannot be
            // undone and two accounts can share a display name. Absent when we have none,
            // which is itself worth seeing on a row about to be handed a deployment.
            DropdownItem(index.toLong(), candidate.email?.let { "${candidate.name} — $it" } ?: candidate.name)
        }
        picker?.render(items, pickedIndex?.toLong(), placeholder = "Choose an account")
    }

    /**
     * The phrase, once there is a name to put in it.
     *
     * Rewritten on every pick, and the field is **cleared** with it: a phrase typed for one
     * person must not arm a handover to another, which is precisely the mistake naming the
     * successor in the phrase exists to catch.
     */
    private fun renderPhrase() {
        val name = pickedIndex?.let { pending.candidates[it].name }
        if (name == null) {
            promptElement.setTextIfChanged(HAND_OVER_PICK_FIRST)
            phraseElement.visible(false)
            phraseField.visible(false)
            return
        }
        promptElement.setTextIfChanged(handOverPrompt(name))
        phraseElement.clear()
        phraseElement.appendChild(element("code", "confirm-phrase", handOverPhrase(name)))
        phraseElement.visible(true)
        phraseField.visible(true)
        phraseField.value = ""
    }

    /** Whether the confirming button may fire: somebody picked, and the phrase typed. */
    private fun isArmed(): Boolean {
        val name = pickedIndex?.let { pending.candidates[it].name } ?: return false
        // ConfirmDialog's rule, borrowed rather than reinvented: trimmed and
        // case-insensitive, so it is a sentence to mean and not a spelling test.
        return phraseField.value.trim().equals(handOverPhrase(name), ignoreCase = true)
    }

    private fun updateArmed() {
        confirmButton.disabled = !isArmed()
    }
}
