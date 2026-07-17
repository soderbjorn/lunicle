/**
 * The project dialog — new and edit are the same screen, as the spec asks.
 *
 * A dumb view over [EditProjectBackingViewModel]: every label, every disabled
 * flag, both confirmations and the whole delete message are decided there. This
 * file knows what a checkbox is and nothing about what any of them mean.
 *
 * ── Why the sections are rebuilt, and why not always ─────────────────────────
 *
 * The vocabulary rows and the privileges table are lists that change shape — a
 * row appears, one is deleted, two swap places — so they are rebuilt from the
 * state rather than diffed. But they are rebuilt only when their *signature*
 * changes, and that is not an optimisation: the rows contain text fields that
 * the admin types into, and rebuilding on every emission would destroy the field
 * mid-word on any state change at all. Same reasoning as `setValueIfChanged`,
 * one level up — see Dom.kt.
 *
 * @see EditProjectBackingViewModel
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import se.soderbjorn.lunicle.client.viewmodel.EditProjectBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.MemberRowState
import se.soderbjorn.lunicle.client.viewmodel.VocabularyRowState
import se.soderbjorn.lunicle.client.viewmodel.VocabularySection
import se.soderbjorn.lunicle.clientserver.VocabularyKind

/**
 * Renders the project dialog.
 *
 * @param scope collects the view model's state flow; cancelled by the caller
 *   when the dialog closes.
 */
class ProjectDialog(
    private val viewModel: EditProjectBackingViewModel,
    private val scope: CoroutineScope,
) {
    /**
     * Large when editing, small when creating.
     *
     * Read from the initial state rather than waiting for the settings to load,
     * because the panel cannot resize under someone who has started typing. An
     * edit dialog is five vocabulary sections and a privileges table tall — it
     * needs the panel whose body scrolls inside itself — and a new project is four
     * fields, which that panel would render as a mostly-empty screen. See Modal's
     * isLarge.
     */
    private val modal = Modal(
        title = "Project",
        onDismiss = { viewModel.onCancelTapped() },
        isLarge = !viewModel.stateFlow.value.isNew,
    )
    private lateinit var nameField: HTMLInputElement
    private lateinit var prefixField: HTMLInputElement
    private lateinit var prefixHint: HTMLElement
    private lateinit var publicBox: Toggle
    private lateinit var validationElement: HTMLElement
    private lateinit var errorElement: HTMLElement
    private lateinit var okButton: HTMLElement
    private lateinit var deleteButton: HTMLElement
    private var confirm: ConfirmDialog? = null
    private lateinit var host: HTMLElement

    /** Where the vocabulary sections and the privileges table live. */
    private lateinit var settingsElement: HTMLElement
    private lateinit var membersElement: HTMLElement

    /**
     * The section views, by kind, built on the first render that has settings.
     *
     * Kept rather than rebuilt so that the add field keeps its text and its focus
     * across a re-render — the field is where the admin is typing when everything
     * else on the dialog changes.
     */
    private val sectionViews = mutableMapOf<VocabularyKind, SectionView>()

    /** What the members table looked like last time, so it is only rebuilt when it changed. */
    private var membersSignature: String? = null

    /** The vocabulary confirmation and the settings alert, while they are up. */
    private var vocabularyConfirm: ConfirmDialog? = null
    private var alert: AlertDialog? = null
    private var alertMessage: String? = null

    fun mount(host: HTMLElement) {
        this.host = host

        // No placeholders. Both fields carried an example ("Lunamux", "LMX") and
        // both read as pre-filled values rather than as ghost text — the dialog
        // looked like it came with defaults, on a form where the name is the one
        // thing only the user can supply. An empty field cannot lie about that.
        // The prefix keeps its hint line below it, which is where the example
        // now lives.
        nameField = textField { viewModel.onNameChanged(it) }
        prefixField = textField { viewModel.onPrefixChanged(it) }
        prefixHint = element("p", "field-hint")
        publicBox = Toggle { viewModel.onPublicChanged(it) }

        validationElement = element("p", "field-validation")
        errorElement = element("p", "modal-error")
        errorElement.setAttribute("role", "status")

        val publicRow = toggleRow(publicBox, "Public — anyone can read this project's issues without signing in")

        settingsElement = element("div", "project-settings")
        membersElement = element("div", "member-list")

        // This body scrolls as one column. The large panel's default is not to —
        // the issue modal hands that job to an inner band, and inheriting its
        // `overflow: hidden` clipped the privileges section off the bottom of this
        // dialog with no scrollbar to say so. See .settings-body.
        modal.body.classList.add("settings-body")

        modal.body.children(
            element("label", "field-label", "Name"),
            nameField,
            element("label", "field-label", "Ticket prefix"),
            prefixField,
            prefixHint,
            publicRow,
            validationElement,
            errorElement,
            settingsElement,
        )

        deleteButton = button("Delete project", "btn btn-danger-quiet") { viewModel.onDeleteTapped() }
        okButton = button("OK", "btn btn-primary") { viewModel.onOkTapped() }

        modal.footer.children(
            deleteButton,
            element("div", "modal-footer-spacer"),
            button("Cancel", "btn btn-quiet") { viewModel.onCancelTapped() },
            okButton,
        )

        modal.mount(host)
        scope.launch { viewModel.stateFlow.collect { render(it) } }
        nameField.focus()
    }

    private fun render(state: EditProjectBackingViewModel.State) {
        modal.setTitle(state.title)
        nameField.setValueIfChanged(state.name)
        prefixField.setValueIfChanged(state.namePrefix)
        // Hidden until there is a prefix to preview, rather than shown with a
        // stand-in: the hint exists to answer "what does this field do?", and it
        // answers that the moment the user types the first letter.
        prefixHint.setTextIfChanged(
            state.prefixExample?.let { "Issues in this project will be numbered $it" } ?: "",
        )
        prefixHint.visible(state.prefixExample != null)
        publicBox.checked = state.isPublic

        // The validation line and the server's error line are different things
        // and both can be present: one says "you haven't filled this in", the
        // other says "the server refused what you sent". Collapsing them would
        // mean a 409 wiping the message telling you the prefix is empty.
        validationElement.setTextIfChanged(state.validationMessage ?: "")
        validationElement.visible(state.validationMessage != null)
        errorElement.setTextIfChanged(state.errorMessage ?: "")
        errorElement.visible(state.errorMessage != null)

        (okButton as HTMLButtonElement).disabled = !state.isOkEnabled
        deleteButton.visible(state.canDelete, displayValue = "inline-flex")

        renderSettings(state)
        renderConfirm(state)
        renderVocabularyConfirm(state)
        renderAlert(state)
    }

    private fun renderConfirm(state: EditProjectBackingViewModel.State) {
        if (state.isConfirmingDelete && confirm == null) {
            confirm = ConfirmDialog(
                title = "Delete project",
                message = state.confirmDeleteMessage,
                destructiveLabel = "Delete",
                onConfirm = { viewModel.onDeleteConfirmed() },
                onCancel = { viewModel.onDeleteCancelled() },
            ).also { it.mount(host) }
        } else if (!state.isConfirmingDelete && confirm != null) {
            confirm?.dismiss()
            confirm = null
        }
    }

    // ── The settings half ────────────────────────────────────────────────────

    /**
     * The vocabulary sections and the privileges table.
     *
     * Absent entirely until the settings arrive — and for a caller the server
     * refused, forever. See [EditProjectBackingViewModel.loadSettings]: a section
     * that cannot be filled in is worse than no section.
     */
    private fun renderSettings(state: EditProjectBackingViewModel.State) {
        settingsElement.visible(state.hasSettings)
        if (!state.hasSettings) return

        state.sections.forEach { section ->
            val view = sectionViews.getOrPut(section.kind) {
                SectionView(section.kind).also { settingsElement.appendChild(it.root) }
            }
            view.render(section)
        }
        renderMembers(state)
    }

    private fun renderMembers(state: EditProjectBackingViewModel.State) {
        if (membersElement.parentElement == null) {
            settingsElement.children(
                element("h3", "section-title", "Privileges"),
                element("p", "field-hint", state.membersHint),
                membersElement,
            )
        }
        // Rebuilt only when it changed, for the reason this file's preamble gives.
        // The signature is every rendered fact, and it has to stay that way: a fact
        // left out of it is a fact that stops updating on screen, which is the
        // quietest bug this pattern can produce.
        val signature = state.members.joinToString("|") { member ->
            "${member.userId}:${member.name}:${member.note}:" +
                member.roles.joinToString(",") { "${it.key}=${it.isOn}/${it.isEnabled}" }
        }
        if (signature == membersSignature) return
        membersSignature = signature

        membersElement.clear()
        state.members.forEach { membersElement.appendChild(memberRow(it)) }
    }

    private fun memberRow(member: MemberRowState): HTMLElement {
        val row = element("div", "member-row")
        row.appendChild(element("div", "member-name", member.name))
        member.note?.let { row.appendChild(element("p", "field-hint", it)) }

        // An admin's row has no toggles — the note is the whole row. Returning
        // before the toggle, rather than appending an empty one: .role-row carries
        // a margin, and an empty one leaves a gap under the note that reads as a
        // control that failed to render.
        if (member.roles.isEmpty()) return row

        val roles = element("div", "role-row")
        member.roles.forEach { role ->
            val box = Toggle { viewModel.onRoleToggled(member.userId, role.key, it) }
            box.checked = role.isOn
            box.disabled = !role.isEnabled
            roles.appendChild(toggleRow(box, role.description))
        }
        row.appendChild(roles)
        return row
    }

    private fun renderVocabularyConfirm(state: EditProjectBackingViewModel.State) {
        val pending = state.pendingVocabularyDelete
        if (pending != null && vocabularyConfirm == null) {
            vocabularyConfirm = ConfirmDialog(
                title = pending.title,
                message = pending.message,
                destructiveLabel = "Delete",
                onConfirm = { viewModel.onVocabularyDeleteConfirmed() },
                onCancel = { viewModel.onVocabularyDeleteCancelled() },
            ).also { it.mount(host) }
        } else if (pending == null && vocabularyConfirm != null) {
            vocabularyConfirm?.dismiss()
            vocabularyConfirm = null
        }
    }

    /**
     * A refusal from one of the sections, as a modal over this dialog.
     *
     * Keyed on the message rather than on "is there one", for MainScreen's
     * reason: two refusals in a row would otherwise leave the first one on screen
     * describing the second. See main.kt's renderAlert.
     */
    private fun renderAlert(state: EditProjectBackingViewModel.State) {
        val message = state.settingsErrorMessage
        if (message == alertMessage) return
        alert?.dismiss()
        alertMessage = message
        alert = message?.let {
            AlertDialog(
                title = "That did not work",
                message = it,
                onDismiss = { viewModel.onSettingsErrorDismissed() },
            ).also { dialog -> dialog.mount(host) }
        }
    }

    /** Close. The caller cancels [scope]. */
    fun dismiss() {
        confirm?.dismiss()
        vocabularyConfirm?.dismiss()
        alert?.dismiss()
        modal.dismiss()
    }

    /**
     * One vocabulary section: a heading, a hint, the rows, and the add field.
     *
     * A class rather than a function because it owns elements that must survive a
     * re-render — the add field above all, which is where the admin's cursor is
     * when they press Add.
     */
    private inner class SectionView(private val kind: VocabularyKind) {
        val root = element("div", "vocab-section")
        private val title = element("h3", "section-title")
        private val hint = element("p", "field-hint")
        private val rowsElement = element("div", "vocab-rows")
        private val addField = textField { viewModel.onVocabularyDraftChanged(kind, it) }
        private val addButton = button("Add", "btn btn-quiet") { viewModel.onAddVocabularyTapped(kind) }

        /** The rows as last rendered, so they are rebuilt only when they changed. */
        private var signature: String? = null

        init {
            val addRow = element("div", "vocab-add")
            addRow.children(addField, addButton)
            root.children(title, hint, rowsElement, addRow)
        }

        fun render(section: VocabularySection) {
            title.setTextIfChanged(section.title)
            hint.setTextIfChanged(section.hint)
            addField.setValueIfChanged(section.draftName)
            addButton.disabled = !section.isAddEnabled

            val next = section.rows.joinToString("|") { row ->
                "${row.id}:${row.name}:${row.requiresResolution}:" +
                    "${row.isDeletable}/${row.deleteBlockedReason}:${row.canMoveUp}/${row.canMoveDown}"
            }
            if (next == signature) return
            signature = next

            rowsElement.clear()
            section.rows.forEach { rowsElement.appendChild(vocabularyRow(section, it)) }
        }

        private fun vocabularyRow(section: VocabularySection, row: VocabularyRowState): HTMLElement {
            val container = element("div", "vocab-row")

            // Commit on blur, never per keystroke: this field renames a board
            // column for everybody, so an oninput version would send a request per
            // character. See Dom.kt's textFieldCommitting.
            val nameField = textFieldCommitting {
                viewModel.onVocabularyEdited(section.kind, row.id, it, row.requiresResolution)
            }
            nameField.value = row.name
            container.appendChild(nameField)

            if (row.showsClosingFlag) {
                val flag = Toggle {
                    // The name comes from the field rather than from `row`, so that
                    // flipping the toggle does not silently revert an edit the admin
                    // typed and has not blurred out of yet. One row, one write —
                    // the server takes the name and the flag together.
                    viewModel.onVocabularyEdited(section.kind, row.id, nameField.value, it)
                }
                flag.checked = row.requiresResolution
                container.appendChild(toggleRow(flag, "needs a resolution", "vocab-flag"))
            }

            if (section.isOrdered) {
                container.appendChild(
                    moveButton("↑", "Move up", row.canMoveUp) {
                        viewModel.onMoveVocabulary(section.kind, row.id, -1)
                    },
                )
                container.appendChild(
                    moveButton("↓", "Move down", row.canMoveDown) {
                        viewModel.onMoveVocabulary(section.kind, row.id, 1)
                    },
                )
            }

            val delete = button("Delete", "btn btn-danger-quiet vocab-delete") {
                viewModel.onDeleteVocabularyTapped(section.kind, row.id)
            }
            delete.disabled = !row.isDeletable
            // Why the button is dead, on the button. A disabled control that will
            // not say why is the thing people file bugs about — and the view model
            // already wrote the sentence.
            row.deleteBlockedReason?.let { delete.title = it }
            container.appendChild(delete)

            return container
        }

        private fun moveButton(
            glyph: String,
            description: String,
            isEnabled: Boolean,
            onClick: () -> Unit,
        ): HTMLButtonElement {
            val el = button(glyph, "btn btn-quiet vocab-move", onClick)
            el.disabled = !isEnabled
            // The glyph is an arrow and arrows do not read aloud. Everything else
            // in this dialog is a word.
            el.title = description
            el.setAttribute("aria-label", description)
            return el
        }
    }
}
