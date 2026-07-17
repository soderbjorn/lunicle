/**
 * The comment modal — the issue editor's small sibling, and the same editor.
 *
 * @see CommentBackingViewModel
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.client.viewmodel.CommentBackingViewModel

/** Renders the comment modal. */
class CommentDialog(
    private val viewModel: CommentBackingViewModel,
    private val scope: CoroutineScope,
) {
    private val modal = Modal("Comment", onDismiss = { viewModel.onCancelTapped() }, isLarge = true)
    private lateinit var editor: MarkdownEditor
    private lateinit var validationElement: HTMLElement
    private lateinit var errorElement: HTMLElement
    private lateinit var okButton: HTMLButtonElement
    private lateinit var cancelButton: HTMLButtonElement

    fun mount(host: HTMLElement) {
        val editorHost = element("div", "")
        editor = MarkdownEditor(
            scope = scope,
            onChange = { viewModel.onBodyChanged(it) },
            onUpload = { name, mime, bytes -> viewModel.uploadAttachment(name, mime, bytes) },
        )
        editor.mount(editorHost)

        validationElement = element("p", "field-validation")
        errorElement = element("p", "modal-error")
        errorElement.setAttribute("role", "status")

        modal.body.children(editorHost, validationElement, errorElement)

        okButton = button("Post", "btn btn-primary") { viewModel.onOkTapped() } as HTMLButtonElement
        cancelButton = button("Cancel", "btn btn-quiet") { viewModel.onCancelTapped() } as HTMLButtonElement
        modal.footer.children(cancelButton, okButton)

        modal.mount(host)
        scope.launch { viewModel.stateFlow.collect { render(it) } }
        // Creates the draft row the image button needs an owner for. Until it
        // returns, isLoaded is false and OK stays disabled.
        viewModel.start()
    }

    private fun render(state: CommentBackingViewModel.State) {
        modal.setTitle(state.title)
        editor.setValue(state.body)
        editor.setEnabled(state.isLoaded && !state.isBusy)

        validationElement.setTextIfChanged(state.validationMessage ?: "")
        validationElement.visible(state.validationMessage != null)
        errorElement.setTextIfChanged(state.errorMessage ?: "")
        errorElement.visible(state.errorMessage != null)

        okButton.disabled = !state.isOkEnabled
        okButton.setTextIfChanged(if (state.isNew) "Post" else "Save")
        cancelButton.disabled = state.isBusy
    }

    fun dismiss() = modal.dismiss()
}
