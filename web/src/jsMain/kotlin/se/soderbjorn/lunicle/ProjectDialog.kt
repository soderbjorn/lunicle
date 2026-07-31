/**
 * The new-project dialog: a name, a prefix, and OK.
 *
 * A dumb view over [EditProjectBackingViewModel]: every label and every disabled flag
 * is decided there. This file knows what a text field is and nothing about what any
 * of them mean.
 *
 * ── What used to be here, and where it went (LNL-194) ────────────────────────
 *
 * This was new **and** edit on one screen, with a five-tab strip — General, Github,
 * Structure, Sprints, Privileges — and the whole of a project's settings behind it.
 * All of that moved to [ProjectSections], which draws the same sections under the
 * Projects rail in the settings pane; the Privileges master-detail was replaced there
 * by Access, built around audience rows rather than a checkbox per role per account.
 * `Delete project` left this footer for the bottom of General, where the thing being
 * destroyed is named on screen.
 *
 * What is left is what a project that does not exist yet needs, and it stays a modal
 * for the reason it always was one: there is no project to hang a pane off until it
 * exists, and a two-field form that opens, is answered and goes away is what a modal
 * is for.
 *
 * ── And nothing about visibility ────────────────────────────────────────────
 *
 * The `Public` and `Visible to all signed-in users` switches were on this form and are
 * gone. A new project admits **nobody** until its owner says otherwise in Access, which
 * is the safe default and the one a fresh row has always had — and it means the
 * decision is made on the screen that can also say at what *rung* an audience arrives,
 * rather than as a checkbox on a form about naming.
 *
 * @see ProjectSections for everything an existing project's settings do
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

/**
 * Renders the new-project dialog.
 *
 * @param scope collects the view model's state flow; cancelled by the caller when the
 *   dialog closes.
 */
class ProjectDialog(
    private val viewModel: EditProjectBackingViewModel,
    private val scope: CoroutineScope,
) {
    /**
     * The small panel, always.
     *
     * The large one existed for the settings tabs and left with them: two fields in a
     * panel sized for five vocabulary sections is a mostly-empty screen. See Modal's
     * isLarge.
     */
    private val modal: DialogShell = Modal(
        title = "Project",
        onDismiss = { viewModel.onCancelTapped() },
    )

    private lateinit var nameField: HTMLInputElement
    private lateinit var prefixField: HTMLInputElement
    private lateinit var prefixHint: HTMLElement
    private lateinit var validationElement: HTMLElement
    private lateinit var errorElement: HTMLElement
    private lateinit var okButton: HTMLButtonElement

    fun mount(host: HTMLElement) {
        // No placeholders. Both fields carried an example ("Lunamux", "LMX") and both
        // read as pre-filled values rather than as ghost text — the dialog looked like it
        // came with defaults, on a form where the name is the one thing only the user can
        // supply. An empty field cannot lie about that. The prefix keeps its hint line
        // below it, which is where the example now lives.
        nameField = textField { viewModel.onNameChanged(it) }
        prefixField = textField { viewModel.onPrefixChanged(it) }
        prefixHint = element("p", "field-hint")

        validationElement = element("p", "field-validation")
        errorElement = element("p", "modal-error")
        errorElement.setAttribute("role", "status")

        modal.body.children(
            element("label", "field-label", "Name"),
            nameField,
            element("label", "field-label", "Issue prefix"),
            prefixField,
            prefixHint,
            element(
                "p",
                "field-hint",
                "Nobody but you can see it to begin with. Who else gets in — and as what — is " +
                    "the Access section of the project's settings.",
            ),
            validationElement,
            errorElement,
        )

        okButton = button("OK", "btn btn-primary") { viewModel.onOkTapped() }
        modal.footer.children(
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
        // Hidden until there is a prefix to preview, rather than shown with a stand-in:
        // the hint exists to answer "what does this field do?", and it answers that the
        // moment the user types the first letter.
        prefixHint.setTextIfChanged(
            state.prefixExample?.let { "Issues in this project will be numbered $it" } ?: "",
        )
        prefixHint.visible(state.prefixExample != null)

        // The validation line and the server's error line are different things and both
        // can be present: one says "you haven't filled this in", the other says "the
        // server refused what you sent". Collapsing them would mean a 409 wiping the
        // message telling you the prefix is empty.
        validationElement.setTextIfChanged(state.validationMessage ?: "")
        validationElement.visible(state.validationMessage != null)
        errorElement.setTextIfChanged(state.errorMessage ?: "")
        errorElement.visible(state.errorMessage != null)

        okButton.disabled = !state.isOkEnabled
    }

    /** Close. The caller cancels [scope]. */
    fun dismiss() = modal.dismiss()
}
