/**
 * An issue window's content: read it, or edit it, comment on it, delete it.
 *
 * The successor to the old IssueDialog, re-housed from a modal into the body of
 * a darkness floating window. The chrome — title bar, minimise/maximise/close —
 * belongs to the toolkit now; this renders only what goes inside, plus the
 * footer buttons. Two faces of one window — a reader sees rendered markdown,
 * someone with rights sees the editor. Which one is showing is
 * [IssueBackingViewModel.State.isEditing], decided there and not here.
 *
 * There is no Cancel button. The window's close control is the way out, and the
 * Save / Discard / Keep-editing question it can raise arrives through
 * [IssueBackingViewModel.State.confirmingClose] — rendered here as the
 * toolkit's three-way choice dialog, so it looks like every other darkness
 * confirmation.
 *
 * @see IssueBackingViewModel
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import se.soderbjorn.darkness.web.DialogChoice
import se.soderbjorn.darkness.web.showChoiceDialog
import se.soderbjorn.lunicle.client.renderMarkdown
import se.soderbjorn.lunicle.client.viewmodel.CommentBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.IssueBackingViewModel
import se.soderbjorn.lunicle.clientserver.CommentView
import se.soderbjorn.lunicle.clientserver.VocabularyItem

/**
 * Renders one issue window's body.
 *
 * @param dialogHost where the delete confirmation mounts — the shared modal
 *   host, so it stacks with every other dialog and Modal's topmost-wins Escape
 *   keeps working.
 * @param openComment asks the bootstrap to put a comment dialog up. The window
 *   does not build one itself: a view owning another dialog's lifetime is how
 *   you end up with two modals fighting over Escape.
 */
class IssueWindow(
    private val viewModel: IssueBackingViewModel,
    private val scope: CoroutineScope,
    private val dialogHost: HTMLElement,
    private val openComment: (editing: CommentBackingViewModel.Existing?) -> Unit,
) {
    private lateinit var body: HTMLElement
    private lateinit var titleField: HTMLInputElement
    private lateinit var readTitle: HTMLElement
    private lateinit var byline: HTMLElement
    // The "made by an agent" badge under the byline, and the label inside it that
    // carries the agent's name. Hidden unless the issue has an agentName; see render.
    private lateinit var agentBadge: HTMLElement
    private lateinit var agentBadgeLabel: HTMLElement
    private lateinit var statusSelect: Dropdown
    private lateinit var statusRead: HTMLElement
    private lateinit var prioritySelect: Dropdown
    private lateinit var priorityRead: HTMLElement
    private lateinit var resolutionSelect: Dropdown
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
    private lateinit var saveButton: HTMLButtonElement
    private lateinit var editButton: HTMLElement
    private lateinit var deleteButton: HTMLElement
    private lateinit var editor: MarkdownEditor

    private var confirm: ConfirmDialog? = null
    private var commentConfirm: ConfirmDialog? = null

    /**
     * Whether the Save/Discard/Keep-editing dialog is currently on screen.
     *
     * The toolkit's [showChoiceDialog] is fire-and-forget — it cannot be
     * re-rendered or dismissed from outside — so this view tracks "already
     * open" itself, the same way the old dialog code tracked its confirm. The
     * flag clears in the dialog's own callbacks, which are also what clear
     * [IssueBackingViewModel.State.confirmingClose], so the two stay in step.
     */
    private var choiceShowing = false

    fun mount(host: HTMLElement) {
        readTitle = element("h3", "issue-title")
        byline = element("p", "issue-byline")
        // Icon plus name, so the badge states "agent" both ways — the drawn mark
        // for the glance and the word for the read. See IssueBackingViewModel.agentBadge.
        agentBadgeLabel = element("span", "agent-badge-label")
        agentBadge = element("span", "agent-badge")
        agentBadge.children(agentIcon(), agentBadgeLabel)
        titleField = textField("Short description") { viewModel.onTitleChanged(it) }

        statusSelect = Dropdown("field") { viewModel.onStatusChanged(it) }
        statusRead = element("span", "tag tag-status")

        prioritySelect = Dropdown("field") { viewModel.onPriorityChanged(it) }
        priorityRead = element("span", "tag tag-priority")

        resolutionSelect = Dropdown("field") { viewModel.onResolutionChanged(it) }
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

        // Status and priority share a row — same layout, and the same reasoning,
        // as the old dialog: see the field-cell comments there. The caption/label
        // duplication is deliberate (label for the edit face, caption for the
        // read face).
        val statusCell = element("div", "field-cell")
        statusCell.children(
            element("label", "field-label field-label-edit", "Status"),
            statusSelect.element,
            element("span", "tag-caption", "Status"),
            statusRead,
        )
        val priorityCell = element("div", "field-cell")
        priorityCell.children(
            element("label", "field-label field-label-edit", "Priority"),
            prioritySelect.element,
            element("span", "tag-caption", "Priority"),
            priorityRead,
        )
        resolutionCell = element("div", "field-cell")
        resolutionCell.children(
            element("label", "field-label field-label-edit", "Resolution"),
            resolutionSelect.element,
            element("span", "tag-caption", "Resolution"),
            resolutionRead,
        )

        val statusPriorityRow = element("div", "field-row")
        statusPriorityRow.children(statusCell, priorityCell, resolutionCell)

        // Same three bands as the old dialog, for the same scrolling reasons:
        // identity never scrolls, the editor takes the remaining height while
        // editing, and the reading face scrolls as one.
        val fields = element("div", "issue-fields")
        fields.children(
            readTitle,
            byline,
            agentBadge,
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

        // `modal-body`, deliberately: the three-band scroll layout
        // (.modal-body / .issue-fields / .issue-scroll / .editor-host) was
        // written for the old issue dialog and is reused verbatim — the issue
        // window IS that dialog, re-housed. See styles.css.
        body = element("div", "modal-body")
        body.children(
            fields,
            editorHost,
            scrollArea,
            validationElement,
            errorElement,
        )

        // "Edit" toggles into the editor and back out of it; toggling out with
        // typed text asks first — all decided in the view model. Save keeps the
        // window open, landing back in read mode on what was written.
        editButton = button("Edit", "btn") { viewModel.onEditTapped() }
        deleteButton = button("Delete", "btn btn-danger-quiet") { viewModel.onDeleteTapped() }
        saveButton = button("Save", "btn btn-primary") { viewModel.onOkTapped() } as HTMLButtonElement

        val footer = element("div", "issue-footer")
        footer.children(
            deleteButton,
            element("div", "modal-footer-spacer"),
            editButton,
            saveButton,
        )

        val root = element("div", "issue-window")
        root.children(body, footer)
        host.appendChild(root)

        scope.launch { viewModel.stateFlow.collect { render(it) } }
        viewModel.start()
    }

    private fun render(state: IssueBackingViewModel.State) {
        readTitle.setTextIfChanged(state.title.ifBlank { "Untitled" })
        readTitle.visible(!state.isEditing)
        byline.setTextIfChanged(state.byline)
        byline.visible(!state.isEditing && !state.isDraft)

        // Only on an agent-filed issue, and only in read mode — same as the byline
        // it sits under. The label drives the visibility: no agent, no badge.
        val agentBadgeText = state.agentBadge
        agentBadgeLabel.setTextIfChanged(agentBadgeText ?: "")
        agentBadge.visible(agentBadgeText != null && !state.isEditing && !state.isDraft, displayValue = "inline-flex")

        body.classList.toggle("editing", state.isEditing)

        titleField.setValueIfChanged(state.title)
        editor.setValue(state.description)
        editor.setEnabled(state.isEditing)

        titleField.visible(state.isEditing)
        editorHost.visible(state.isEditing, displayValue = "flex")

        renderStatuses(state)
        statusRead.setTextIfChanged(state.statusName)
        statusRead.visible(!state.isEditing, displayValue = "inline-block")

        renderPriorities(state)
        priorityRead.setTextIfChanged(state.priorityName)
        priorityRead.visible(!state.isEditing, displayValue = "inline-block")

        renderResolutions(state)
        val showResolution =
            if (state.isEditing) state.requiresResolution else state.resolutionName != null
        resolutionCell.visible(showResolution)
        resolutionRead.setTextIfChanged(state.resolutionName ?: "")
        resolutionRead.visible(!state.isEditing && showResolution, displayValue = "inline-block")

        renderChips(labelBox, state.labels, state.labelIds, state.isEditing) { viewModel.onLabelToggled(it) }
        renderChips(componentBox, state.components, state.componentIds, state.isEditing) { viewModel.onComponentToggled(it) }
        renderReadTags(state)

        val html = renderMarkdown(state.description)
        if (descriptionRead.innerHTML != html) descriptionRead.innerHTML = html
        descriptionRead.visible(!state.isEditing)

        // Comments belong to the reading face only — see the old dialog's long
        // comment for why: a draft has none and cannot, and while editing they
        // are a wall of other people's text under the field being typed in.
        val showComments = !state.isDraft && !state.isEditing
        renderComments(state)
        commentsHeading.visible(showComments)
        commentsElement.visible(showComments, displayValue = "flex")
        addCommentButton.visible(state.canComment && showComments, displayValue = "inline-flex")

        validationElement.setTextIfChanged(state.validationMessage ?: "")
        validationElement.visible(state.isEditing && state.validationMessage != null)
        errorElement.setTextIfChanged(state.errorMessage ?: "")
        errorElement.visible(state.errorMessage != null)

        // "Edit" in read mode, "View" in the editor — one toggle, two labels.
        // Hidden on a draft: a draft has nothing to read, so there is nowhere
        // to toggle back to until it is saved.
        editButton.setTextIfChanged(if (state.isEditing) "View" else "Edit")
        editButton.visible(state.canEdit && !state.isDraft, displayValue = "inline-flex")
        deleteButton.visible(state.canDelete && !state.isDraft, displayValue = "inline-flex")
        saveButton.visible(state.isEditing, displayValue = "inline-flex")
        saveButton.disabled = !state.isOkEnabled

        renderDeleteConfirm(state)
        renderDeleteCommentConfirm(state)
        renderCloseConfirm(state)
    }

    private fun renderStatuses(state: IssueBackingViewModel.State) {
        statusSelect.render(
            items = state.statuses.map { DropdownItem(it.id, it.name) },
            selectedId = state.statusId,
        )
        statusSelect.element.visible(state.isEditing, displayValue = "block")
    }

    private fun renderPriorities(state: IssueBackingViewModel.State) {
        prioritySelect.render(
            items = state.priorities.map { DropdownItem(it.id, it.name) },
            selectedId = state.priorityId,
        )
        prioritySelect.element.visible(state.isEditing, displayValue = "block")
    }

    private fun renderResolutions(state: IssueBackingViewModel.State) {
        // The placeholder — "Choose…" — is only ever seen here of the three
        // dropdowns: a resolution starts null and the user must pick one, where a
        // status and a priority always arrive already set. See the old dialog's
        // comment.
        resolutionSelect.render(
            items = state.resolutions.map { DropdownItem(it.id, it.name) },
            selectedId = state.resolutionId,
            placeholder = "Choose…",
        )
        resolutionSelect.element.visible(state.isEditing, displayValue = "block")
    }

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
        // Author and, when an agent wrote it, the badge, as one left-aligned group
        // — the group takes the flex space so the Edit/Delete buttons still sit far
        // right, and the badge hugs the name it is about rather than drifting to the
        // opposite edge. Rebuilt with the comment each render, so a plain
        // conditional append is enough — no persistent element to toggle as there
        // is for the issue byline.
        val meta = element("div", "comment-meta")
        meta.appendChild(element("span", "comment-author", state.commentByline(comment)))
        state.commentAgentBadge(comment)?.let { badgeText ->
            val badge = element("span", "agent-badge")
            badge.children(agentIcon(), element("span", "agent-badge-label", badgeText))
            meta.appendChild(badge)
        }
        head.appendChild(meta)
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
        val commentBody = element("div", "markdown comment-body")
        commentBody.innerHTML = renderMarkdown(comment.body)
        el.children(head, commentBody)
        return el
    }

    private fun renderDeleteConfirm(state: IssueBackingViewModel.State) {
        if (state.isConfirmingDelete && confirm == null) {
            confirm = ConfirmDialog(
                title = "Delete issue",
                message = state.confirmDeleteMessage,
                destructiveLabel = "Delete",
                onConfirm = { viewModel.onDeleteConfirmed() },
                onCancel = { viewModel.onDeleteCancelled() },
            ).also { it.mount(dialogHost) }
        } else if (!state.isConfirmingDelete && confirm != null) {
            confirm?.dismiss()
            confirm = null
        }
    }

    private fun renderDeleteCommentConfirm(state: IssueBackingViewModel.State) {
        if (state.confirmingDeleteCommentId != null && commentConfirm == null) {
            commentConfirm = ConfirmDialog(
                title = "Delete comment",
                message = state.confirmDeleteCommentMessage,
                destructiveLabel = "Delete",
                onConfirm = { viewModel.onDeleteCommentConfirmed() },
                onCancel = { viewModel.onDeleteCommentCancelled() },
            ).also { it.mount(dialogHost) }
        } else if (state.confirmingDeleteCommentId == null && commentConfirm != null) {
            commentConfirm?.dismiss()
            commentConfirm = null
        }
    }

    /**
     * The Save / Discard / Keep-editing question, as the toolkit's three-way
     * dialog so it matches every other darkness confirmation.
     *
     * Escape and a backdrop click mean "Keep editing": the two no-touch ways
     * out of a dialog must be the one answer that changes nothing.
     */
    private fun renderCloseConfirm(state: IssueBackingViewModel.State) {
        if (state.confirmingClose == null || choiceShowing) return
        choiceShowing = true
        showChoiceDialog(
            title = "Unsaved changes",
            message = "${state.heading} has unsaved changes.",
            choices = listOf(
                DialogChoice(id = "keep", label = "Keep editing"),
                DialogChoice(id = "discard", label = "Discard", destructive = true),
                DialogChoice(id = "save", label = "Save", isPrimary = true),
            ),
            onChoice = { id ->
                choiceShowing = false
                when (id) {
                    "save" -> viewModel.onCloseSaveTapped()
                    "discard" -> viewModel.onCloseDiscardTapped()
                    else -> viewModel.onCloseKeepEditingTapped()
                }
            },
            onDismiss = {
                choiceShowing = false
                viewModel.onCloseKeepEditingTapped()
            },
        )
    }

    /** Take down anything this window put up outside its own pane. */
    fun dispose() {
        confirm?.dismiss()
        confirm = null
    }
}
