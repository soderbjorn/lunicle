/**
 * The issue modal: read it, or edit it, comment on it, delete it.
 *
 * Two faces of one dialog — a reader sees rendered markdown, someone with rights
 * sees the editor. Which one is showing is [IssueBackingViewModel.State.isEditing],
 * decided there and not here.
 *
 * @see IssueBackingViewModel
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import se.soderbjorn.lunicle.client.renderMarkdown
import se.soderbjorn.lunicle.client.viewmodel.CommentBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.IssueBackingViewModel
import se.soderbjorn.lunicle.clientserver.CommentView
import se.soderbjorn.lunicle.clientserver.VocabularyItem

/**
 * Renders the issue modal.
 *
 * @param openComment asks the bootstrap to put a comment dialog up. The issue
 *   dialog does not build one itself: a dialog owning another dialog's lifetime
 *   is how you end up with two modals fighting over Escape.
 */
class IssueDialog(
    private val viewModel: IssueBackingViewModel,
    private val scope: CoroutineScope,
    private val openComment: (editing: CommentBackingViewModel.Existing?) -> Unit,
) {
    private val modal = Modal("Issue", onDismiss = { viewModel.onCancelTapped() }, isLarge = true)

    private lateinit var host: HTMLElement
    private lateinit var titleField: HTMLInputElement
    private lateinit var readTitle: HTMLElement
    private lateinit var byline: HTMLElement
    private lateinit var statusSelect: HTMLSelectElement
    private lateinit var statusRead: HTMLElement
    private lateinit var prioritySelect: HTMLSelectElement
    private lateinit var priorityRead: HTMLElement
    private lateinit var resolutionSelect: HTMLSelectElement
    private lateinit var resolutionRead: HTMLElement
    private lateinit var resolutionCell: HTMLElement
    private lateinit var labelBox: HTMLElement
    private lateinit var componentBox: HTMLElement
    private lateinit var tagsRead: HTMLElement
    private lateinit var descriptionRead: HTMLElement
    private lateinit var editorHost: HTMLElement
    private lateinit var commentsElement: HTMLElement
    private lateinit var commentsHeading: HTMLElement
    private lateinit var addCommentButton: HTMLElement
    private lateinit var validationElement: HTMLElement
    private lateinit var errorElement: HTMLElement
    private lateinit var okButton: HTMLButtonElement
    private lateinit var cancelButton: HTMLButtonElement
    private lateinit var editButton: HTMLElement
    private lateinit var deleteButton: HTMLElement
    private lateinit var editor: MarkdownEditor

    private var confirm: ConfirmDialog? = null
    private var renderedStatusIds: List<Long> = emptyList()
    private var renderedPriorityIds: List<Long> = emptyList()
    private var renderedResolutionIds: List<Long> = emptyList()

    fun mount(host: HTMLElement) {
        this.host = host

        readTitle = element("h3", "issue-title")
        byline = element("p", "issue-byline")
        titleField = textField("Short description") { viewModel.onTitleChanged(it) }

        statusSelect = select { value -> value.toLongOrNull()?.let(viewModel::onStatusChanged) }
        statusRead = element("span", "tag tag-status")

        prioritySelect = select { value -> value.toLongOrNull()?.let(viewModel::onPriorityChanged) }
        priorityRead = element("span", "tag tag-priority")

        resolutionSelect = select { value -> value.toLongOrNull()?.let(viewModel::onResolutionChanged) }
        resolutionRead = element("span", "tag tag-resolution")

        labelBox = element("div", "chip-row")
        componentBox = element("div", "chip-row")
        tagsRead = element("div", "card-tags")

        descriptionRead = element("div", "markdown issue-description")
        editorHost = element("div", "editor-host")
        editor = MarkdownEditor(
            scope = scope,
            onChange = { viewModel.onDescriptionChanged(it) },
            onUpload = { name, mime, bytes -> viewModel.uploadAttachment(name, mime, bytes) },
        )
        editor.mount(editorHost)

        commentsElement = element("div", "comments")
        commentsHeading = element("h4", "comments-heading", "Comments")
        addCommentButton = button("Add comment", "btn btn-quiet") { openComment(null) }

        validationElement = element("p", "field-validation")
        errorElement = element("p", "modal-error")
        errorElement.setAttribute("role", "status")

        // Status and priority share a row: two full-width selects stacked cost a
        // third of the dialog's height to say two words.
        // Each cell carries its name TWICE, and the two are not redundant: the
        // <label> belongs to the select and only appears while editing, the
        // caption sits inline before the tag and only appears while reading.
        // Without the caption the read face is a bare "New" next to a bare
        // "Normal" — two words in the dialog's most prominent row with nothing
        // saying which axis either one is on. On the board the column heading and
        // the priority group say it; in here nothing did.
        val statusCell = element("div", "field-cell")
        statusCell.children(
            element("label", "field-label field-label-edit", "Status"),
            statusSelect,
            element("span", "tag-caption", "Status"),
            statusRead,
        )
        val priorityCell = element("div", "field-cell")
        priorityCell.children(
            element("label", "field-label field-label-edit", "Priority"),
            prioritySelect,
            element("span", "tag-caption", "Priority"),
            priorityRead,
        )
        // Resolution shares the row, and is present only when the chosen status
        // demands one — see render(). The whole cell goes, not just the select: a
        // "Resolution" label over nothing is a field that looks broken.
        resolutionCell = element("div", "field-cell")
        resolutionCell.children(
            element("label", "field-label field-label-edit", "Resolution"),
            resolutionSelect,
            element("span", "tag-caption", "Resolution"),
            resolutionRead,
        )

        val statusPriorityRow = element("div", "field-row")
        statusPriorityRow.children(statusCell, priorityCell, resolutionCell)

        // Three bands, and the split is what makes the scrolling right. See
        // .issue-fields / .issue-scroll in styles.css:
        //
        //  - `fields` never scrolls. It is the identity of the thing you are
        //    looking at, and it should not slide away while you type.
        //  - `editorHost` takes the rest of the height while editing, and the
        //    editor's own surface scrolls inside it.
        //  - `scrollArea` is the reading face — description and comments — which
        //    scrolls as one.
        val fields = element("div", "issue-fields")
        fields.children(
            readTitle,
            byline,
            element("label", "field-label field-label-edit", "Title"),
            titleField,
            statusPriorityRow,
            element("label", "field-label field-label-edit", "Labels"),
            labelBox,
            element("label", "field-label field-label-edit", "Components"),
            componentBox,
            tagsRead,
            element("label", "field-label field-label-edit", "Description"),
        )

        val scrollArea = element("div", "issue-scroll")
        scrollArea.children(
            descriptionRead,
            commentsHeading,
            commentsElement,
            addCommentButton,
        )

        modal.body.children(
            fields,
            editorHost,
            scrollArea,
            validationElement,
            errorElement,
        )

        editButton = button("Edit", "btn") { viewModel.onEditTapped() }
        deleteButton = button("Delete", "btn btn-danger-quiet") { viewModel.onDeleteTapped() }
        okButton = button("OK", "btn btn-primary") { viewModel.onOkTapped() } as HTMLButtonElement
        cancelButton = button("Cancel", "btn btn-quiet") { viewModel.onCancelTapped() } as HTMLButtonElement

        modal.footer.children(
            deleteButton,
            element("div", "modal-footer-spacer"),
            editButton,
            cancelButton,
            okButton,
        )

        modal.mount(host)
        scope.launch { viewModel.stateFlow.collect { render(it) } }
        viewModel.start()
    }

    private fun render(state: IssueBackingViewModel.State) {
        modal.setTitle(state.heading)
        readTitle.setTextIfChanged(state.title.ifBlank { "Untitled" })
        readTitle.visible(!state.isEditing)
        byline.setTextIfChanged(state.byline)
        byline.visible(!state.isEditing && !state.isDraft)

        // Every "edit-only" label rides on one class rather than being toggled
        // individually — there are six of them and forgetting one shows a stray
        // "Components" heading above a read-only issue.
        modal.body.classList.toggle("editing", state.isEditing)

        titleField.setValueIfChanged(state.title)
        editor.setValue(state.description)
        editor.setEnabled(state.isEditing)

        // Every edit-only control is hidden in read mode, not merely disabled.
        // Disabling alone leaves a dead grey textarea above the rendered
        // description and an inert input above the heading — the reader sees
        // each piece of text twice, once formatted and once in a broken-looking
        // form. The labels ride on the `editing` class (see .field-label-edit);
        // these are controls rather than labels, so they say it themselves.
        titleField.visible(state.isEditing)
        editorHost.visible(state.isEditing, displayValue = "flex")

        renderStatuses(state)
        statusRead.setTextIfChanged(state.statusName)
        statusRead.visible(!state.isEditing, displayValue = "inline-block")

        renderPriorities(state)
        priorityRead.setTextIfChanged(state.priorityName)
        priorityRead.visible(!state.isEditing, displayValue = "inline-block")

        // Editing: shown when the chosen status demands a resolution. Reading:
        // shown when the issue actually has one. The two conditions differ on
        // purpose — while editing it must appear the instant the status changes,
        // before anything has been picked, which is exactly when resolutionName is
        // still null.
        renderResolutions(state)
        val showResolution =
            if (state.isEditing) state.requiresResolution else state.resolutionName != null
        resolutionCell.visible(showResolution)
        resolutionRead.setTextIfChanged(state.resolutionName ?: "")
        resolutionRead.visible(!state.isEditing && showResolution, displayValue = "inline-block")

        renderChips(labelBox, state.labels, state.labelIds, state.isEditing) { viewModel.onLabelToggled(it) }
        renderChips(componentBox, state.components, state.componentIds, state.isEditing) { viewModel.onComponentToggled(it) }
        renderReadTags(state)

        // innerHTML with renderMarkdown's output — the one sanctioned use. See
        // Markdown.kt: everything it emits is a tag it built itself, and every
        // byte of the issue's text was escaped before any rule saw it.
        val html = renderMarkdown(state.description)
        if (descriptionRead.innerHTML != html) descriptionRead.innerHTML = html
        descriptionRead.visible(!state.isEditing)

        // Comments belong to the reading face of this dialog, and only to it.
        // Two reasons, and they arrive at the same rule:
        //
        //  - A draft has no comments and cannot have any — it does not exist on
        //    the server yet. What that left was a "Comments" heading over "No
        //    comments yet." on an issue nobody has ever been able to see, which
        //    is not information, it is furniture.
        //  - While editing, the comments are a wall of other people's text under
        //    the field you are typing in. They are read before the Edit button is
        //    pressed and they will be there after Cancel; nothing about them is
        //    editable from here, so they are pushing the footer down the screen
        //    to no purpose.
        //
        // The whole section goes, not just its contents: an empty heading is the
        // same claim made more quietly.
        val showComments = !state.isDraft && !state.isEditing
        renderComments(state)
        commentsHeading.visible(showComments)
        commentsElement.visible(showComments)
        addCommentButton.visible(state.canComment && showComments, displayValue = "inline-flex")

        validationElement.setTextIfChanged(state.validationMessage ?: "")
        validationElement.visible(state.isEditing && state.validationMessage != null)
        errorElement.setTextIfChanged(state.errorMessage ?: "")
        errorElement.visible(state.errorMessage != null)

        editButton.visible(!state.isEditing && state.canEdit, displayValue = "inline-flex")
        deleteButton.visible(state.canDelete && !state.isDraft, displayValue = "inline-flex")
        okButton.visible(state.isEditing, displayValue = "inline-flex")
        okButton.disabled = !state.isOkEnabled
        cancelButton.setTextIfChanged(if (state.isEditing) "Cancel" else "Close")
        cancelButton.disabled = state.isBusy

        renderConfirm(state)
    }

    private fun renderStatuses(state: IssueBackingViewModel.State) {
        val ids = state.statuses.map { it.id }
        if (ids != renderedStatusIds) {
            renderedStatusIds = ids
            statusSelect.clear()
            state.statuses.forEach { status ->
                statusSelect.appendChild(
                    (kotlinx.browser.document.createElement("option") as org.w3c.dom.HTMLOptionElement).apply {
                        value = status.id.toString()
                        textContent = status.name
                    },
                )
            }
        }
        statusSelect.value = state.statusId.toString()
        statusSelect.visible(state.isEditing, displayValue = "block")
    }

    /**
     * The priority dropdown. [renderStatuses]' twin, and rebuilt on the same
     * terms — the id-list guard is what stops the open dropdown closing under the
     * user on an unrelated state tick.
     */
    private fun renderPriorities(state: IssueBackingViewModel.State) {
        val ids = state.priorities.map { it.id }
        if (ids != renderedPriorityIds) {
            renderedPriorityIds = ids
            prioritySelect.clear()
            state.priorities.forEach { priority ->
                prioritySelect.appendChild(
                    (kotlinx.browser.document.createElement("option") as org.w3c.dom.HTMLOptionElement).apply {
                        value = priority.id.toString()
                        textContent = priority.name
                    },
                )
            }
        }
        prioritySelect.value = state.priorityId.toString()
        prioritySelect.visible(state.isEditing, displayValue = "block")
    }

    /** The resolution dropdown. [renderStatuses]' twin; same rebuild guard. */
    private fun renderResolutions(state: IssueBackingViewModel.State) {
        val ids = state.resolutions.map { it.id }
        if (ids != renderedResolutionIds) {
            renderedResolutionIds = ids
            resolutionSelect.clear()
            // A blank first option, and only here of the three dropdowns. Status
            // and priority always have a value; a resolution starts as null and
            // the user must choose. Without a blank, the select would show the
            // first resolution while the state held none — the dialog claiming
            // "Done" while OK is refused for having no resolution.
            resolutionSelect.appendChild(
                (kotlinx.browser.document.createElement("option") as org.w3c.dom.HTMLOptionElement).apply {
                    value = ""
                    textContent = "Choose…"
                },
            )
            state.resolutions.forEach { resolution ->
                resolutionSelect.appendChild(
                    (kotlinx.browser.document.createElement("option") as org.w3c.dom.HTMLOptionElement).apply {
                        value = resolution.id.toString()
                        textContent = resolution.name
                    },
                )
            }
        }
        resolutionSelect.value = state.resolutionId?.toString() ?: ""
        resolutionSelect.visible(state.isEditing, displayValue = "block")
    }

    /**
     * The label/component pickers.
     *
     * Rebuilt on every render, which is fine here and not in the picker: these
     * are buttons rather than a native `<select>`, so there is no open dropdown
     * to close under the user.
     */
    private fun renderChips(
        box: HTMLElement,
        items: List<VocabularyItem>,
        selected: Set<Long>,
        isEditing: Boolean,
        onToggle: (Long) -> Unit,
    ) {
        box.visible(isEditing, displayValue = "flex")
        if (!isEditing) return
        box.clear()
        items.forEach { item ->
            val chip = button(item.name, if (item.id in selected) "chip chip-on" else "chip") { onToggle(item.id) }
            box.appendChild(chip)
        }
    }

    private fun renderReadTags(state: IssueBackingViewModel.State) {
        tagsRead.visible(!state.isEditing, displayValue = "flex")
        if (state.isEditing) return
        tagsRead.clear()
        state.labelNames.forEach { tagsRead.appendChild(element("span", "tag tag-label", it)) }
        state.componentNames.forEach { tagsRead.appendChild(element("span", "tag tag-component", it)) }
    }

    private fun renderComments(state: IssueBackingViewModel.State) {
        commentsElement.clear()
        if (state.comments.isEmpty()) {
            commentsElement.appendChild(element("p", "comments-empty", "No comments yet."))
            return
        }
        state.comments.forEach { comment -> commentsElement.appendChild(renderComment(state, comment)) }
    }

    private fun renderComment(state: IssueBackingViewModel.State, comment: CommentView): HTMLElement {
        val el = element("article", "comment")
        val head = element("div", "comment-head")
        // Author and timestamp are one string from the view model, not a span
        // each: it is one attribution, and splitting it here would put the
        // separator — and the decision to have one — in the view.
        head.appendChild(
            element("span", "comment-author", state.commentByline(comment)),
        )
        if (comment.canEdit) {
            head.children(
                button("Edit", "link-btn") {
                    openComment(CommentBackingViewModel.Existing(comment.id, comment.body))
                },
                button("Delete", "link-btn link-btn-danger") {
                    viewModel.onDeleteCommentTapped(comment.id)
                },
            )
        }
        val body = element("div", "markdown comment-body")
        body.innerHTML = renderMarkdown(comment.body)
        el.children(head, body)
        return el
    }

    private fun renderConfirm(state: IssueBackingViewModel.State) {
        if (state.isConfirmingDelete && confirm == null) {
            confirm = ConfirmDialog(
                title = "Delete issue",
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

    fun dismiss() {
        confirm?.dismiss()
        modal.dismiss()
    }
}
