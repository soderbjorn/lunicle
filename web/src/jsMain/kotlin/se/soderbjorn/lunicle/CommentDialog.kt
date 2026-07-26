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
    /** The `PREFIX-` autocomplete source for the comment editor (LNL-139). */
    private val ticketSource: TicketSource,
) {
    private val modal = Modal("Comment", onDismiss = { viewModel.onCancelTapped() }, isLarge = true)
    private lateinit var editor: MarkdownEditor
    private lateinit var validationElement: HTMLElement
    private lateinit var errorElement: HTMLElement
    private lateinit var okButton: HTMLButtonElement
    private lateinit var cancelButton: HTMLButtonElement

    fun mount(host: HTMLElement) {
        // The same host class the issue dialog uses, and for the same reason:
        // it is what hands the editor the dialog's leftover height so the
        // surface scrolls and the toolbar stays put. See .editor-host.
        val editorHost = element("div", "editor-host")
        editor = MarkdownEditor(
            scope = scope,
            onChange = { viewModel.onBodyChanged(it) },
            onUpload = { name, mime, bytes -> viewModel.uploadAttachment(name, mime, bytes) },
        )
        editor.mount(editorHost)
        // Fixed for the modal's lifetime: it came off the issue this comment
        // belongs to, which was fetched before the modal could open.
        editor.setMentionNames(viewModel.mentionableNames)
        // Typing a known project's "PREFIX-" offers that project's issues (LNL-139).
        editor.setTicketSource(prefixes = ticketSource.prefixes, lookup = ticketSource.lookup)

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
        // Not `!isBusy`: an upload must leave the editor live, because that is
        // where the upload's markdown is about to be inserted. See
        // CommentBackingViewModel.State.isUploading.
        editor.setEnabled(state.isEditorEnabled)

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
