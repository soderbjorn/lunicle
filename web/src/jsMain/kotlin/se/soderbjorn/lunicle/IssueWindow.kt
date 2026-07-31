/**
 * An issue window's content: read it, or edit it, comment on it, delete it.
 *
 * The successor to the old IssueDialog, re-housed from a modal into the body of
 * a lunula floating window. The chrome — title bar, minimise/maximise/close —
 * belongs to the toolkit now; this renders only what goes inside, plus the
 * footer buttons. Two faces of one window — a reader sees rendered markdown,
 * someone with rights sees the editor. Which one is showing is
 * [IssueBackingViewModel.State.isEditing], decided there and not here.
 *
 * There is no Cancel button. The window's close control is the way out, and the
 * Save / Discard / Keep-editing question it can raise arrives through
 * [IssueBackingViewModel.State.confirmingClose] — rendered here as the
 * toolkit's three-way choice dialog, so it looks like every other lunula
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
import se.soderbjorn.lunula.web.DialogChoice
import se.soderbjorn.lunula.web.showChoiceDialog
import se.soderbjorn.lunicle.client.Ticket
import se.soderbjorn.lunicle.client.TicketTitleLookup
import se.soderbjorn.lunicle.client.renderInlineLinks
import se.soderbjorn.lunicle.client.renderMarkdown
import se.soderbjorn.lunicle.client.viewmodel.CommentBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.HistoryBlock
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
 * @param ticketSource ticket references (LNL-139): the accessible-project prefixes
 *   a `PREFIX-NUMBER` in this window's title, description or comments is linked for,
 *   and the `PREFIX-` autocomplete's issue lookup for the description editor. Read
 *   live, since the accessible set can grow while a window stays open.
 */
class IssueWindow(
    private val viewModel: IssueBackingViewModel,
    private val scope: CoroutineScope,
    private val dialogHost: HTMLElement,
    private val openComment: (editing: CommentBackingViewModel.Existing?) -> Unit,
    private val ticketSource: TicketSource,
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
    private lateinit var assigneeSelect: Dropdown
    private lateinit var assigneeCell: HTMLElement
    // "Assign to their agent" (LNL-215): a toggle under the assignee dropdown, in the
    // editor only, and only once somebody has been named — see renderAssignee.
    private lateinit var agentAssigneeToggle: Toggle
    private lateinit var agentAssigneeRow: HTMLElement
    // The estimate (LNL-215). Absent entirely on a project that does not estimate;
    // see renderEstimate, which is the whole of the `none` promise on this screen.
    private lateinit var estimateSelect: EstimateDropdown
    private lateinit var estimateRead: HTMLElement
    private lateinit var estimateCell: HTMLElement
    private lateinit var sprintSelect: Dropdown
    private lateinit var sprintRead: HTMLElement
    private lateinit var sprintCell: HTMLElement
    // Planned and fixed version (LNL-134). Both draw from the one version list and
    // are absent entirely on a project with none — see renderVersions.
    private lateinit var plannedVersionSelect: VersionDropdown
    private lateinit var plannedVersionRead: HTMLElement
    private lateinit var plannedVersionCell: HTMLElement
    private lateinit var fixedVersionSelect: VersionDropdown
    private lateinit var fixedVersionRead: HTMLElement
    private lateinit var fixedVersionCell: HTMLElement
    // The read face's one-line identity (LNL-14 proposal 3): byline, assignee and
    // watchers folded into one dim line, with the assign button trailing it. The
    // prose is one text node (state.readIdentityLine); the button stays a live
    // control beside it.
    private lateinit var identityLine: HTMLElement
    private lateinit var assignButton: HTMLButtonElement
    // Held rather than built inline with the rest of the fields because a project
    // that defines no labels — or no components — must not show a heading over a
    // row with nothing in it. See renderChips.
    private lateinit var labelLabel: HTMLElement
    private lateinit var labelBox: HTMLElement
    private lateinit var componentLabel: HTMLElement
    private lateinit var componentBox: HTMLElement
    private lateinit var tagsRead: HTMLElement
    // The epic parent (LNL-55): a clickable chip when one is set, shown in both
    // faces; plus, in edit mode, the picker to set/change it and a button to
    // detach. The whole section hides when there is no parent and no picker to show.
    private lateinit var parentSection: HTMLElement
    private lateinit var parentHeader: HTMLElement
    private lateinit var parentReadChip: HTMLElement
    private lateinit var parentPicker: IssuePicker
    private lateinit var removeParentButton: HTMLButtonElement
    // This issue's children (LNL-55): the ordered, clickable list, shown whenever
    // the issue is an epic; plus, in edit mode, per-row reorder arrows and remove,
    // and the add-child picker.
    private lateinit var childrenSection: HTMLElement
    private lateinit var childrenHeading: HTMLElement
    private lateinit var childrenList: HTMLElement
    private lateinit var childPicker: IssuePicker
    // This issue's links to other issues (LNL-215): the grouped, clickable list in both
    // faces, plus — in edit mode — a kind picker and an issue picker to add one. The
    // whole band folds away for an issue with no links and a reader who cannot add any.
    private lateinit var relationsSection: HTMLElement
    private lateinit var relationsHeading: HTMLElement
    private lateinit var relationsList: HTMLElement
    private lateinit var relationKindSelect: Dropdown
    private lateinit var relationPicker: IssuePicker
    private lateinit var relationAddRow: HTMLElement
    private lateinit var descriptionRead: HTMLElement
    private lateinit var editorHost: HTMLElement
    private lateinit var commentsElement: HTMLElement
    private lateinit var commentsHeading: HTMLElement
    private lateinit var addCommentButton: HTMLElement
    private lateinit var historyElement: HTMLElement
    private lateinit var historyHeading: HTMLElement
    private lateinit var validationElement: HTMLElement
    private lateinit var errorElement: HTMLElement
    private lateinit var saveButton: HTMLButtonElement
    private lateinit var editButton: HTMLElement
    private lateinit var deleteButton: HTMLElement
    private lateinit var watchButton: WatchButton
    private lateinit var titleRow: HTMLElement
    private lateinit var editor: MarkdownEditor
    // The three content bands and the footer, held so the whole window can be
    // swapped for a spinner while the first fetch is in flight (LNL-135). Until
    // the issue arrives its state is the default-empty [State], which renders as
    // a byline reading "A deleted account" and a title reading "Untitled" — a
    // scary flash for the ~second the round-trip takes. See [render].
    private lateinit var fields: HTMLElement
    private lateinit var scrollArea: HTMLElement
    private lateinit var footer: HTMLElement
    private lateinit var loadingElement: HTMLElement

    private var confirm: ConfirmDialog? = null
    private var commentConfirm: ConfirmDialog? = null
    private var parentConfirm: ConfirmDialog? = null
    private var childConfirm: ConfirmDialog? = null
    private var relationConfirm: ConfirmDialog? = null

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

        statusSelect = Dropdown(isField = true) { viewModel.onStatusChanged(it) }
        statusRead = element("span", "tag tag-status")

        prioritySelect = Dropdown(isField = true) { viewModel.onPriorityChanged(it) }
        priorityRead = element("span", "tag tag-priority")

        resolutionSelect = Dropdown(isField = true) { viewModel.onResolutionChanged(it) }
        resolutionRead = element("span", "tag tag-resolution")

        assigneeSelect = Dropdown(isField = true) { viewModel.onAssigneeChanged(it) }
        // The lunula boolean — the same On/Off pair every switch in project settings
        // uses — rather than a bare checkbox, so a boolean looks like a boolean
        // wherever it appears in this app. See Dom.kt's Toggle.
        agentAssigneeToggle = Toggle { viewModel.onAssigneeIsAgentChanged(it) }
        agentAssigneeRow = toggleRow(agentAssigneeToggle, "Assign to their agent", "issue-agent-toggle")

        estimateSelect = EstimateDropdown(isField = true) { viewModel.onEstimateChanged(it) }
        // A chip, like the sprint and the versions: an estimate is one of the project's
        // own measured facts about the issue, which is what the tag styling means here.
        // Its own hue is deliberately NOT introduced — see `.tag-estimate`.
        estimateRead = element("span", "tag tag-estimate")

        sprintSelect = Dropdown(isField = true) { viewModel.onSprintChanged(it) }
        // A chip like status and priority rather than a sentence like the
        // assignee: a sprint is one of the project's own named things, which is
        // what the tag styling means here.
        sprintRead = element("span", "tag tag-sprint")

        // The reusable version dropdown (LNL-134): the same control the resolution
        // dialog uses, once for the planned version and once for the fixed one.
        // Add lands in the field that asked; rename and delete are shared, so both
        // pickers go through the one onVersionRenamed / onVersionDeleted.
        plannedVersionSelect = VersionDropdown(
            isField = true,
            onSelect = { viewModel.onPlannedVersionChanged(it) },
            onAdd = { viewModel.onPlannedVersionAdded(it) },
            onRename = { id, name -> viewModel.onVersionRenamed(id, name) },
            onDelete = { viewModel.onVersionDeleted(it) },
        )
        plannedVersionRead = element("span", "tag tag-version")
        fixedVersionSelect = VersionDropdown(
            isField = true,
            onSelect = { viewModel.onFixedVersionChanged(it) },
            onAdd = { viewModel.onFixedVersionAdded(it) },
            onRename = { id, name -> viewModel.onVersionRenamed(id, name) },
            onDelete = { viewModel.onVersionDeleted(it) },
        )
        fixedVersionRead = element("span", "tag tag-version")
        // The assignee is not a tag like status and priority — those are the
        // project's vocabulary and read as chips; "Assigned to Robert" is a sentence
        // about a person. It now reads as a clause of the identity line rather than
        // a tag or a row of its own (LNL-14); state.readIdentityLine composes it.
        assignButton = button("Assign to me", "btn btn-quiet btn-small") {
            viewModel.onAssignToMeTapped()
        }

        labelLabel = element("label", "field-label field-label-edit", "Labels")
        labelBox = element("div", "chip-row")
        componentLabel = element("label", "field-label field-label-edit", "Components")
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
        // Typing a known project's "PREFIX-" while editing offers that project's
        // issues to complete (LNL-139), the same popup the "@" opens.
        editor.setTicketSource(prefixes = ticketSource.prefixes, lookup = ticketSource.lookup)

        // The parent chip is an anchor styled like an in-prose ticket reference, so
        // the app-wide `.ticket-ref` click handler (main.kt) opens it for free — no
        // wiring of its own. The picker sets or changes the parent; the button
        // detaches, asking first.
        parentReadChip = element("a", "ticket-ref issue-parent-chip")
        parentPicker = IssuePicker("Set a parent issue…") { viewModel.onParentChosen(it) }
        removeParentButton = button("Remove from parent", "btn btn-quiet btn-small") {
            viewModel.onRemoveParentRequested()
        }
        // Detaching belongs in the LABEL row, right-aligned, not under the
        // picker. A button parked beneath a field reads as that field's primary
        // action — "Remove from parent" sat directly below "Set a parent
        // issue…", where the two make a sentence nobody meant. Above and to the
        // right it reads as what it is: something you may do to this section,
        // like the section's own menu.
        parentHeader = element("div", "issue-parent-header")
        parentHeader.children(
            element("span", "field-label field-label-edit", "Parent"),
            element("span", "tag-caption", "Parent"),
            removeParentButton,
        )
        parentSection = element("div", "issue-parent")
        parentSection.children(
            parentHeader,
            element("div", "issue-parent-row").also {
                it.children(parentReadChip, parentPicker.element)
            },
        )

        childrenHeading = element("h4", "comments-heading", "Child issues")
        childrenList = element("div", "issue-children")
        childPicker = IssuePicker("Add a child issue…") { viewModel.onChildAdded(it) }
        childrenSection = element("div", "issue-children-section")
        childrenSection.children(childrenHeading, childrenList, childPicker.element)

        // Links to other issues (LNL-215). The add control is two halves on one row —
        // WHICH KIND of link, then TO WHICH ISSUE — and they are side by side rather
        // than stacked because they are one sentence: "Blocked by | LMX-…". Stacking
        // them would read as two independent settings and would leave the kind picker
        // looking like a filter over the list above it.
        //
        // The kind picker is a plain [Dropdown] and not a [VersionDropdown]: there is no
        // inline "add a kind here", deliberately. Relation kinds are a project's
        // grammar, not its data — you invent a version mid-issue because the release you
        // want does not exist yet, but inventing a way for two issues to be related is a
        // decision about the whole board and belongs in project settings, where it can
        // be named in both directions and flagged as blocking.
        relationsHeading = element("h4", "comments-heading", "Links")
        relationsList = element("div", "issue-relations")
        relationKindSelect = Dropdown(isField = true, className = "issue-relation-kind") {
            viewModel.onRelationKindChosen(it)
        }
        relationPicker = IssuePicker("Link to an issue…") { viewModel.onRelationAdded(it) }
        relationAddRow = element("div", "issue-relation-add")
        relationAddRow.children(relationKindSelect.element, relationPicker.element)
        relationsSection = element("div", "issue-relations-section")
        relationsSection.children(relationsHeading, relationsList, relationAddRow)

        commentsElement = element("div", "comments")
        commentsHeading = element("h4", "comments-heading", "Comments")
        addCommentButton = button("Add comment", "btn btn-quiet") { openComment(null) }
        historyElement = element("div", "history")
        historyHeading = element("h4", "comments-heading", "History")

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

        // In the same row as the other three, because it is the same kind of thing:
        // a single-choice field the editor sets. It is last so that Status and
        // Priority — which every issue has, where an assignee is optional — keep the
        // positions readers already know.
        assigneeCell = element("div", "field-cell")
        assigneeCell.children(
            element("label", "field-label field-label-edit", "Assignee"),
            assigneeSelect.element,
            // Under the dropdown, inside the same cell, because it is a qualification of
            // the answer above it and not a seventh field: "who does this" is one
            // question, and "…or their agent" is a footnote to it. A cell of its own in
            // the grid would put the two on opposite sides of a 14px gutter and invite
            // the reading that the flag stands alone — which it does not, and which the
            // server refuses (see IssueUpdate.assigneeIsAgent).
            agentAssigneeRow,
        )

        // Last in the row, after the assignee, and absent entirely on a project
        // with no sprints — see renderSprint. Last because it is the one field
        // here that most issues in most projects will never have.
        sprintCell = element("div", "field-cell")
        sprintCell.children(
            element("label", "field-label field-label-edit", "Sprint"),
            sprintSelect.element,
            element("span", "tag-caption", "Sprint"),
            sprintRead,
        )

        // After the sprint. Each cell shows or hides on its own — see
        // renderVersions for when. Two cells, planned then fixed, so they read
        // left-to-right as intent-then-record.
        plannedVersionCell = element("div", "field-cell")
        plannedVersionCell.children(
            element("label", "field-label field-label-edit", "Planned version"),
            plannedVersionSelect.element,
            element("span", "tag-caption", "Planned version"),
            plannedVersionRead,
        )
        fixedVersionCell = element("div", "field-cell")
        fixedVersionCell.children(
            element("label", "field-label field-label-edit", "Fixed version"),
            fixedVersionSelect.element,
            element("span", "tag-caption", "Fixed version"),
            fixedVersionRead,
        )

        // Last in the row, after the two versions, and absent entirely on a project that
        // does not estimate — see renderEstimate. Last because it is the newest of the
        // optional fields and the one fewest projects will ever turn on, so the
        // positions readers already know for the other six do not move.
        estimateCell = element("div", "field-cell")
        estimateCell.children(
            element("label", "field-label field-label-edit", "Estimate"),
            estimateSelect.element,
            element("span", "tag-caption", "Estimate"),
            estimateRead,
        )

        // The read-mode label/component chips (tagsRead) ride at the end of this
        // same row (LNL-14 proposal 3), so status, priority and the tags read as one
        // wrapping chip line rather than the tags claiming a band of their own below.
        // In edit mode the row becomes a grid and tagsRead is hidden (renderReadTags),
        // so it never takes a grid cell; the editable chip pickers are separate
        // siblings below.
        val statusPriorityRow = element("div", "field-row")
        statusPriorityRow.children(
            statusCell, priorityCell, resolutionCell, assigneeCell, sprintCell,
            plannedVersionCell, fixedVersionCell, estimateCell, tagsRead,
        )

        // Same three bands as the old dialog, for the same scrolling reasons:
        // identity never scrolls, the editor takes the remaining height while
        // editing, and the reading face scrolls as one.
        // In the identity band, shown in read mode only (LNL-95) — a subscription
        // is not one of the editable fields, it is a standing choice about this
        // issue, and it stays reachable whenever the issue is being read. Its
        // visibility is decided in render from whether the caller has an address
        // and whether the issue is being edited. See
        // IssueBackingViewModel.onNotificationToggled.
        // A pill in the corner rather than a labelled row (LNL-46). The sentence
        // it replaced — "E-mail me about updates to this issue" — spent a full
        // row of the identity band restating what the eye and the word already
        // say, in a band that competes with the description for a short pane's
        // height. See WatchButton for why the label flips rather than the icon.
        //
        // It shares the title's row, which is the whole point: a pill on a row of
        // its own costs exactly the height the labelled row did, so nothing would
        // have been saved. The row is top-aligned, so a title that wraps to two
        // lines grows downwards and leaves the pill where it was.
        watchButton = WatchButton { viewModel.onNotificationToggled(it) }
        titleRow = element("div", "issue-title-row")
        titleRow.children(readTitle, watchButton.element)

        // One dim line for who filed it, who is on it, and who else is watching
        // (LNL-14 proposal 3) — the byline text carries all three (readIdentityLine),
        // the assign button trails it as the one live control. Replaces the three
        // stacked byline / assignee / "Watched by" bands the header used to open with.
        identityLine = element("div", "issue-identity")
        identityLine.children(byline, assignButton)

        fields = element("div", "issue-fields")
        fields.children(
            // Title left, watch pill hard right on the same row — the far right at
            // the top, under the window's own chrome, as the issue asks.
            titleRow,
            identityLine,
            agentBadge,
            element("label", "field-label field-label-edit", "Title"),
            titleField,
            // Parent sits just under the title, not above it: belonging to an epic is
            // metadata about the issue, not part of its heading, and stacking the whole
            // parent band — label, chip, picker, detach — above the title only pushed
            // the title itself down the window for something read far less often.
            parentSection,
            statusPriorityRow,
            labelLabel,
            labelBox,
            componentLabel,
            componentBox,
            element("label", "field-label field-label-edit", "Description"),
        )

        scrollArea = element("div", "issue-scroll")
        scrollArea.children(
            // Above the description: an epic's children are the shape of the work,
            // and someone opening it is here for that as much as the prose.
            childrenSection,
            // And directly under them, for the same reason and one step out: the
            // children say what this work is made of, the links say what it waits on
            // and what waits on it. Both answer "can I start this?", which is the
            // question the description cannot.
            relationsSection,
            descriptionRead,
            commentsHeading,
            commentsElement,
            addCommentButton,
            // Below the comments, not above them: the comments are the
            // conversation and the history is the record underneath it. Someone
            // opening an issue is nearly always here for what people said, and
            // pushing that below a list that grows with every edit would bury it.
            historyHeading,
            historyElement,
        )

        // A centred spinner shown in place of the whole body until the first
        // fetch returns (LNL-135). The container centres its one child — the
        // spinning ring — in the space the content will fill. Hidden by default
        // in CSS; render toggles it.
        loadingElement = element("div", "issue-loading")
        loadingElement.children(element("div", "issue-spinner"))

        // `modal-body`, deliberately: the three-band scroll layout
        // (.modal-body / .issue-fields / .issue-scroll / .editor-host) was
        // written for the old issue dialog and is reused verbatim — the issue
        // window IS that dialog, re-housed. See styles.css.
        body = element("div", "modal-body")
        body.children(
            loadingElement,
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

        footer = element("div", "issue-footer")
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

    /** Titles for the references in this issue's text (LNL-144). */
    private fun IssueBackingViewModel.State.ticketTitles(): TicketTitleLookup =
        ticketSource.titleFor

    /**
     * This very issue, or null while it is still a draft with no number. A
     * self-reference in the body is left as plain text rather than linked back to
     * the page you are already reading, or expanded to "LMX-12: Title" for an issue
     * in front of you (LNL-151, superseding the title-only exclusion of LNL-144).
     */
    private val IssueBackingViewModel.State.self: Ticket?
        get() = if (number > 0) Ticket(prefix, number) else null

    private fun render(state: IssueBackingViewModel.State) {
        // Until the first fetch returns, the state is the default-empty [State]:
        // rendering it would flash a byline reading "A deleted account" and a
        // title reading "Untitled" for the ~second the round-trip takes (LNL-135).
        // Show a spinner in place of the whole body and footer instead, and bail
        // before the field-by-field render below paints that empty face.
        //
        // A failed fetch is not loading — it leaves errorMessage set and no data —
        // so it still hides the content but shows the error rather than spinning
        // forever, which a spinner with no resolution would otherwise do.
        val loaded = state.isLoaded
        val loadFailed = !loaded && state.errorMessage != null
        loadingElement.visible(!loaded && !loadFailed, displayValue = "flex")
        fields.visible(loaded)
        editorHost.visible(loaded && state.isEditing, displayValue = "flex")
        scrollArea.visible(loaded)
        footer.visible(loaded, displayValue = "flex")
        if (!loaded) {
            validationElement.visible(false)
            errorElement.setTextIfChanged(state.errorMessage ?: "")
            errorElement.visible(loadFailed)
            return
        }

        // A URL in the title is a link here too (LNL-112), same as one in the
        // description below. renderInlineLinks escapes first and emits only its
        // own checked <a>s; guarded like the description so a re-render mid-render
        // does not rebuild the node under a selection.
        val titleHtml = renderInlineLinks(
            state.title.ifBlank { "Untitled" },
            ticketSource.prefixes(),
            self = state.self,
            titleFor = state.ticketTitles(),
            gitHubRepository = state.gitHubRepository,
        )
        if (readTitle.innerHTML != titleHtml) readTitle.innerHTML = titleHtml
        readTitle.visible(!state.isEditing)
        // The whole identity line — byline, assignee, watchers — is one dim line in
        // read mode (LNL-14), hidden while editing (the editor owns the height) and
        // on a draft (nothing has been filed yet). The assign button inside it has
        // its own gate in renderAssignee.
        byline.setTextIfChanged(state.readIdentityLine)
        identityLine.visible(!state.isEditing && !state.isDraft, displayValue = "flex")

        // Only on an agent-filed issue, and only in read mode — same as the identity
        // line it sits under. The label drives the visibility: no agent, no badge.
        val agentBadgeText = state.agentBadge
        agentBadgeLabel.setTextIfChanged(agentBadgeText ?: "")
        agentBadge.visible(agentBadgeText != null && !state.isEditing && !state.isDraft, displayValue = "inline-flex")

        body.classList.toggle("editing", state.isEditing)

        // Read mode only (LNL-95): never on a draft (nothing to subscribe to yet)
        // and never without an address to send to, and never while editing — the
        // pill costs identity-band height the editor needs, and stays reachable in
        // read mode where it belongs. The toggle reflects the current subscription;
        // a click is a request the view model turns around. Who else is watching now
        // rides on the identity line (state.readIdentityLine / watchersTail), not a
        // row of its own.
        val showNotify = state.canReceiveEmailNotifications && !state.isDraft && !state.isEditing
        watchButton.element.visible(showNotify, displayValue = "inline-flex")
        watchButton.render(watching = state.notifyOnUpdates, isEnabled = !state.isBusy)
        // The row holds the read title and the pill, both of which are read-mode
        // only, so it reduces to exactly !isEditing.
        titleRow.visible(!state.isEditing, displayValue = "flex")

        titleField.setValueIfChanged(state.title)
        editor.setValue(state.description)
        editor.setEnabled(state.isEditing)
        // Re-set on every render rather than once on load: the list arrives with
        // the fetch, which lands after the editor is mounted, and it can change
        // under a long-open window when an admin grants somebody a role.
        editor.setMentionNames(state.mentionableUsers.map { it.name })

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

        renderAssignee(state)
        renderSprint(state)
        renderVersions(state)
        renderEstimate(state)
        renderParent(state)
        renderChildren(state)
        renderRelations(state)

        renderChips(labelLabel, labelBox, state.labels, state.labelIds, state.isEditing, withDot = true) {
            viewModel.onLabelToggled(it)
        }
        renderChips(componentLabel, componentBox, state.components, state.componentIds, state.isEditing, withDot = false) {
            viewModel.onComponentToggled(it)
        }
        renderReadTags(state)

        // A `#123` here is a link to that pull request when the project is linked to
        // a GitHub repository, and the text it was written as when it is not (LNL-178).
        val html = renderMarkdown(
            state.description,
            ticketSource.prefixes(),
            self = state.self,
            titleFor = state.ticketTitles(),
            gitHubRepository = state.gitHubRepository,
        )
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

        // The same gate as the comments, and for the same two reasons: a draft has
        // no history and cannot, and while editing this is a wall of text under
        // the field being typed in. Deliberately NOT also gated on the list being
        // empty — a published issue always has at least its CREATED event, so an
        // empty history means something failed to load rather than nothing having
        // happened, and silently hiding the heading would hide that.
        renderHistory(state)
        historyHeading.visible(showComments)
        historyElement.visible(showComments, displayValue = "flex")

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
        renderParentConfirm(state)
        renderChildConfirm(state)
        renderRelationConfirm(state)
        renderCloseConfirm(state)
    }

    /**
     * This issue's links to other issues, in both faces (LNL-215).
     *
     * Read face: one heading per direction of per kind, with a clickable
     * `PREFIX-N: Title` per row — the same `.ticket-ref` anchor the parent chip and the
     * children use, so the app-wide click handler in main.kt opens them for free and
     * this needs no wiring of its own. Rendered for **every** reader: the links are part
     * of the issue, and only the ability to *change* them is narrowed.
     *
     * Edit face: each row gains a Remove, gated on the row's own `canRemove` — which is
     * `canEditIssue` on THIS issue, not on the far end, because an issue does not own
     * who points at it. The add control appears when this caller may edit and the
     * project has kinds to link under.
     *
     * The whole band folds away for an issue with no links and no add control, which is
     * the overwhelming majority of issues on the overwhelming majority of boards —
     * [renderChildren]'s rule, applied to a list that will be empty even more often.
     */
    private fun renderRelations(state: IssueBackingViewModel.State) {
        val groups = state.relationGroups
        val editing = state.isEditing && state.showsRelationControls

        relationsList.clear()
        groups.forEach { group ->
            val block = element("div", "issue-relation-group")
            block.appendChild(element("div", "issue-relation-label", group.label))
            group.relations.forEach { relation ->
                val row = element("div", "issue-relation-row")
                val ticket = "${state.prefix}-${relation.other.number}"
                val link = element("a", "ticket-ref issue-relation-link")
                link.setTextIfChanged("$ticket: ${relation.other.title}")
                link.setAttribute("data-ticket", ticket)
                link.setAttribute("href", "?issue=$ticket")
                row.appendChild(link)
                if (editing) {
                    // The child list's gesture, verbatim: a quiet small Remove at the
                    // trailing edge, which asks before it acts. Unlinking is cheap to
                    // undo — the picker is right there — but it is destructive to
                    // somebody ELSE'S issue window, which is exactly the case the
                    // children's confirmation exists for.
                    val remove = button("Remove", "btn btn-quiet btn-small") {
                        viewModel.onRemoveRelationRequested(relation.id)
                    }
                    remove.disabled = state.isBusy || !relation.canRemove
                    row.appendChild(remove)
                }
                block.appendChild(row)
            }
            relationsList.appendChild(block)
        }

        relationKindSelect.render(
            items = state.relationKinds.map { DropdownItem(it.id, it.name) },
            selectedId = state.relationKindSelectedId ?: 0L,
        )
        relationKindSelect.element.visible(editing, displayValue = "flex")
        relationPicker.setItems(
            state.relationCandidates.map { IssuePickerItem(it.id, "${state.prefix}-${it.number}", it.title) },
        )
        relationPicker.setEnabled(!state.isBusy)
        relationAddRow.visible(editing, displayValue = "flex")

        val show = groups.isNotEmpty() || editing
        relationsSection.visible(show)
        relationsHeading.visible(show)
    }

    private fun renderRelationConfirm(state: IssueBackingViewModel.State) {
        if (state.confirmingRemoveRelationId != null && relationConfirm == null) {
            relationConfirm = ConfirmDialog(
                title = "Remove link",
                message = state.confirmRemoveRelationMessage,
                destructiveLabel = "Remove",
                onConfirm = { viewModel.onRemoveRelationConfirmed() },
                onCancel = { viewModel.onRemoveRelationCancelled() },
            ).also { it.mount(dialogHost) }
        } else if (state.confirmingRemoveRelationId == null && relationConfirm != null) {
            relationConfirm?.dismiss()
            relationConfirm = null
        }
    }

    /**
     * The estimate, in both faces — or, on a project that does not estimate, in
     * neither and with no trace (LNL-215).
     *
     * The whole cell disappears when the project's estimate mode is `none`, which is the whole of
     * what this feature costs a project that never configures it: no popover, no greyed
     * control, no "Estimate —" caption to explain away. That is [renderSprint]'s
     * emptiness-is-the-flag contract, held to the letter — a project that has not turned
     * estimates on has an issue window byte-for-byte identical to the one it had before
     * this ticket landed.
     *
     * Read face: shown only when there IS an estimate, unlike the sprint's "Backlog" and
     * like the versions' silence. An unestimated issue is the ordinary case, and a row
     * appearing on every one of them to say "—" would be noise on the majority to serve
     * the minority. See [renderVersions], which decided the same thing for the same
     * reason.
     */
    private fun renderEstimate(state: IssueBackingViewModel.State) {
        if (!state.showsEstimate) {
            estimateCell.visible(false)
            return
        }
        estimateSelect.render(state.estimateMode, state.estimate)
        val label = formatEstimate(state.estimate)
        if (state.isEditing) {
            estimateCell.visible(true)
            estimateSelect.element.visible(true, displayValue = "flex")
            estimateRead.visible(false)
        } else {
            estimateCell.visible(label != null)
            estimateSelect.element.visible(false)
            estimateRead.setTextIfChanged(label ?: "")
            estimateRead.visible(label != null, displayValue = "inline-block")
        }
    }

    /**
     * The epic parent (LNL-55). A clickable chip when one is set, in both faces;
     * the picker and the detach button only while editing, and only when this issue
     * may take a parent at all (it is not itself an epic — see showsParentControls).
     */
    private fun renderParent(state: IssueBackingViewModel.State) {
        val parent = state.parent
        if (parent != null) {
            val ticket = state.parentTicket ?: ""
            parentReadChip.setTextIfChanged("$ticket: ${parent.title}")
            parentReadChip.setAttribute("data-ticket", ticket)
            parentReadChip.setAttribute("href", "?issue=$ticket")
        }
        // The chip is the read-mode view of the parent. While editing it is redundant
        // with the picker and the detach button right below it — and naming the same
        // issue twice, one line apart — so it steps aside for them.
        parentReadChip.visible(parent != null && !state.isEditing, displayValue = "inline-flex")

        val showControls = state.isEditing && state.showsParentControls
        parentPicker.setItems(
            state.parentCandidates.map { IssuePickerItem(it.id, "${state.prefix}-${it.number}", it.title) },
        )
        parentPicker.setEnabled(!state.isBusy)
        parentPicker.element.visible(showControls, displayValue = "block")
        removeParentButton.visible(showControls && parent != null, displayValue = "inline-flex")
        removeParentButton.disabled = state.isBusy
        // Label reads "Parent" only when there is something to label — a chip or the
        // picker. The whole band folds away for an ordinary issue in read mode.
        parentSection.visible(parent != null || showControls)
    }

    /**
     * This issue's children (LNL-55). The ordered list shows whenever the issue is
     * an epic; the reorder arrows, per-child remove and add-child picker show only
     * while editing, and only when this issue may have children (it is not itself a
     * child — see showsChildrenControls).
     */
    private fun renderChildren(state: IssueBackingViewModel.State) {
        val editing = state.isEditing && state.showsChildrenControls
        childrenList.innerHTML = ""
        state.children.forEachIndexed { index, child ->
            val ticket = state.childTicket(child)
            val row = element("div", "issue-child-row")
            // Same `.ticket-ref` anchor as the parent chip — clickable via the global
            // handler, no per-row wiring.
            val link = element("a", "ticket-ref issue-child-link")
            link.setTextIfChanged("$ticket: ${child.title}")
            link.setAttribute("data-ticket", ticket)
            link.setAttribute("href", "?issue=$ticket")
            row.appendChild(link)
            if (editing) {
                val controls = element("div", "issue-child-controls")
                controls.appendChild(
                    arrowButton("↑", "Move up", enabled = index > 0 && !state.isBusy) {
                        viewModel.onMoveChild(child.id, -1)
                    },
                )
                controls.appendChild(
                    arrowButton("↓", "Move down", enabled = index < state.children.size - 1 && !state.isBusy) {
                        viewModel.onMoveChild(child.id, 1)
                    },
                )
                // Detaching a child is editing *that* child, so it is gated on the
                // child's own edit right — canEdit rode along on the reference.
                val remove = button("Remove", "btn btn-quiet btn-small") {
                    viewModel.onRemoveChildRequested(child.id)
                }
                remove.disabled = state.isBusy || !child.canEdit
                controls.appendChild(remove)
                row.appendChild(controls)
            }
            childrenList.appendChild(row)
        }

        childPicker.setItems(
            state.childCandidates.map { IssuePickerItem(it.id, "${state.prefix}-${it.number}", it.title) },
        )
        childPicker.setEnabled(!state.isBusy)
        childPicker.element.visible(editing, displayValue = "block")

        // The section shows for a real epic (the read list) and, in edit mode, for
        // any issue that could become one (so the first child can be added).
        val show = state.isEpic || editing
        childrenSection.visible(show)
        childrenHeading.visible(show)
    }

    /** A small square reorder button, disabled at the ends. The children list's ↑/↓. */
    private fun arrowButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit): HTMLButtonElement {
        val el = button(glyph, "btn btn-quiet btn-arrow", onClick)
        el.setAttribute("aria-label", description)
        el.setAttribute("title", description)
        el.disabled = !enabled
        return el
    }

    private fun renderParentConfirm(state: IssueBackingViewModel.State) {
        if (state.confirmingRemoveParent && parentConfirm == null) {
            parentConfirm = ConfirmDialog(
                title = "Remove from parent",
                message = state.confirmRemoveParentMessage,
                destructiveLabel = "Remove",
                onConfirm = { viewModel.onRemoveParentConfirmed() },
                onCancel = { viewModel.onRemoveParentCancelled() },
            ).also { it.mount(dialogHost) }
        } else if (!state.confirmingRemoveParent && parentConfirm != null) {
            parentConfirm?.dismiss()
            parentConfirm = null
        }
    }

    private fun renderChildConfirm(state: IssueBackingViewModel.State) {
        if (state.confirmingRemoveChildId != null && childConfirm == null) {
            childConfirm = ConfirmDialog(
                title = "Remove child issue",
                message = state.confirmRemoveChildMessage,
                destructiveLabel = "Remove",
                onConfirm = { viewModel.onRemoveChildConfirmed() },
                onCancel = { viewModel.onRemoveChildCancelled() },
            ).also { it.mount(dialogHost) }
        } else if (state.confirmingRemoveChildId == null && childConfirm != null) {
            childConfirm?.dismiss()
            childConfirm = null
        }
    }

    private fun renderStatuses(state: IssueBackingViewModel.State) {
        statusSelect.render(
            items = state.statuses.map { DropdownItem(it.id, it.name) },
            selectedId = state.statusId,
        )
        statusSelect.element.visible(state.isEditing, displayValue = "flex")
    }

    private fun renderPriorities(state: IssueBackingViewModel.State) {
        prioritySelect.render(
            items = state.priorities.map { DropdownItem(it.id, it.name) },
            selectedId = state.priorityId,
        )
        prioritySelect.element.visible(state.isEditing, displayValue = "flex")
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
        resolutionSelect.element.visible(state.isEditing, displayValue = "flex")
    }

    /**
     * The assignee, in both faces.
     *
     * Edit face: the dropdown, hidden entirely when the caller has nobody to
     * choose from. That is not the same as "no assignable users exist" — the server
     * sends an empty list to anyone who may not edit this issue — but both mean the
     * same thing here, which is that there is no choice to offer. A dropdown whose
     * only row is "Nobody" is a control that cannot do anything.
     *
     * Read face: the assignee's name is a clause of the identity line
     * (state.readIdentityLine); here we only place the button, shown when it is
     * theirs to press. See [IssueBackingViewModel.State.assignButtonLabel], which
     * is null in the editor so the two controls are never both live.
     */
    private fun renderAssignee(state: IssueBackingViewModel.State) {
        assigneeSelect.render(
            items = state.assigneeOptions.map { DropdownItem(it.id, it.name) },
            selectedId = state.assigneeSelectedId,
            // "Nobody" is a row you can pick, and it is also this field's way of
            // saying nobody has answered it — so it reads dim in the closed
            // control while staying an ordinary, checkable row in the menu.
            unsetId = IssueBackingViewModel.UNASSIGNED_ID,
        )
        val canChoose = state.assignableUsers.isNotEmpty()
        assigneeCell.visible(state.isEditing && canChoose)
        assigneeSelect.element.visible(state.isEditing && canChoose, displayValue = "flex")

        // "…or their agent" (LNL-215), shown only once somebody is actually named.
        //
        // Absent rather than greyed while the field reads "Nobody", and that is the
        // rule the whole feature keeps: a flag about nobody is not a state — the server
        // forces it false — so a dead switch there would be an invitation to a write
        // that does not exist. It appears the moment an assignee is picked, which is
        // also the moment the question becomes askable.
        val showsAgent = state.isEditing && canChoose && state.assigneeId != null
        agentAssigneeRow.visible(showsAgent, displayValue = "flex")
        agentAssigneeToggle.checked = state.assigneeIsAgent
        agentAssigneeToggle.disabled = state.isBusy

        val buttonLabel = state.assignButtonLabel
        buttonLabel?.let { assignButton.setTextIfChanged(it) }
        assignButton.visible(buttonLabel != null, displayValue = "inline-flex")
        assignButton.disabled = state.isBusy
    }

    /**
     * The sprint, in both faces — or in neither.
     *
     * The whole cell disappears when the project has no sprints, which is the
     * only switch this feature has: a kanban issue window is byte-for-byte the
     * window it was before sprints existed, with no empty dropdown and no
     * "Sprint: —" caption to explain away. See Sprints.sq.
     *
     * The read chip shows "Backlog" rather than an em dash when the issue is in
     * no sprint, unlike the resolution's blank: for a project that uses sprints,
     * "not scheduled" is a real and interesting answer, where "no resolution"
     * only means the issue is open and the status already said so.
     */
    private fun renderSprint(state: IssueBackingViewModel.State) {
        if (!state.showsSprint) {
            sprintCell.visible(false)
            return
        }
        sprintCell.visible(true)
        sprintSelect.render(
            items = state.sprintOptions.map { DropdownItem(it.id, it.name) },
            selectedId = state.sprintSelectedId,
        )
        sprintSelect.element.visible(state.isEditing, displayValue = "flex")
        sprintRead.setTextIfChanged(state.sprintName)
        // The two faces are never both up. The tag needs the same explicit toggle
        // status and priority get above — `.tag-caption` is hidden by CSS while
        // editing, but the chip itself is not, so without this the read chip sits
        // under the live dropdown showing the value it has not been changed to yet.
        sprintRead.visible(!state.isEditing, displayValue = "inline-block")
    }

    /**
     * The planned and fixed version, each face resolved on its own (LNL-134).
     *
     * The two faces answer different questions, so they no longer share one
     * shows-or-hides switch:
     *
     *  - **Edit face.** Both pickers appear whenever there is a version to pick
     *    *or* the viewer may create one — the dropdown's own "Add new version…".
     *    Requiring a version to already exist before the fields would even show
     *    left an administrator no way to add the first one from here, only from
     *    project settings (LNL-134 follow-up). A project with no versions and a
     *    viewer who cannot manage them has neither, so the fields stay absent —
     *    the emptiness-is-the-flag contract [renderSprint] keeps.
     *  - **Read face.** A version row appears only when that version is set. An
     *    unset planned or fixed version is an unremarkable default, not a row
     *    worth an em dash of its own — showing "Planned version —" on every
     *    issue that never named one was noise (LNL-134 follow-up).
     *
     * Add and Delete inside the dropdowns are offered only to a project
     * administrator, the same gate the vocabulary routes enforce.
     */
    private fun renderVersions(state: IssueBackingViewModel.State) {
        plannedVersionSelect.canManage = state.canMutateProject
        fixedVersionSelect.canManage = state.canMutateProject
        plannedVersionSelect.render(state.versions, state.plannedVersionId, placeholder = "None")
        fixedVersionSelect.render(state.versions, state.fixedVersionId, placeholder = "None")

        if (state.isEditing) {
            val editable = state.showsVersions || state.canMutateProject
            plannedVersionCell.visible(editable)
            fixedVersionCell.visible(editable)
            plannedVersionSelect.element.visible(editable, displayValue = "flex")
            fixedVersionSelect.element.visible(editable, displayValue = "flex")
            plannedVersionRead.visible(false)
            fixedVersionRead.visible(false)
        } else {
            val showPlanned = state.plannedVersionName != null
            val showFixed = state.fixedVersionName != null
            plannedVersionCell.visible(showPlanned)
            fixedVersionCell.visible(showFixed)
            plannedVersionSelect.element.visible(false)
            fixedVersionSelect.element.visible(false)
            plannedVersionRead.setTextIfChanged(state.plannedVersionName ?: "")
            fixedVersionRead.setTextIfChanged(state.fixedVersionName ?: "")
            plannedVersionRead.visible(showPlanned, displayValue = "inline-block")
            fixedVersionRead.visible(showFixed, displayValue = "inline-block")
        }
    }

    private fun renderChips(
        label: HTMLElement,
        box: HTMLElement,
        items: List<VocabularyItem>,
        selected: Set<Long>,
        isEditing: Boolean,
        withDot: Boolean,
        onToggle: (Long) -> Unit,
    ) {
        // A project need not define labels or components at all, and when it has
        // not there is nothing to choose: heading and row both go, rather than a
        // "Components" heading standing over empty space. Written as an inline
        // display on the label — which .field-label-edit otherwise hides outside
        // edit mode — so the `isEditing` half has to be repeated here to keep
        // that rule's effect.
        val show = isEditing && items.isNotEmpty()
        label.visible(show)
        box.visible(show, displayValue = "flex")
        if (!show) return
        box.clear()
        items.forEach { item ->
            val chip = button("", if (item.id in selected) "chip chip-on" else "chip") { onToggle(item.id) }
            // Labels lead with the dot the board's cards give them, so the same
            // word reads as the same thing in both places. Components have no
            // colour of their own — see `.chip-dot` — so they get no dot, and
            // the difference is itself the information.
            if (withDot) chip.appendChild(element("span", "chip-dot"))
            chip.appendChild(element("span", "chip-name", item.name))
            box.appendChild(chip)
        }
    }

    private fun renderReadTags(state: IssueBackingViewModel.State) {
        // Same reasoning as renderChips on the reading side: an issue with no
        // labels and no components should not reserve a row's worth of margin for
        // tags that are not there.
        val show = !state.isEditing && (state.labelNames.isNotEmpty() || state.componentNames.isNotEmpty())
        tagsRead.visible(show, displayValue = "flex")
        if (!show) return
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
        // A comment naming another ticket links it too — same reading surface, same
        // accessible-project set (LNL-139) — and the same for a pull request it
        // names as `#123` (LNL-178).
        commentBody.innerHTML = renderMarkdown(
            comment.body,
            ticketSource.prefixes(),
            self = state.self,
            titleFor = state.ticketTitles(),
            gitHubRepository = state.gitHubRepository,
        )
        el.children(head, commentBody)
        return el
    }

    private fun renderHistory(state: IssueBackingViewModel.State) {
        historyElement.clear()
        if (state.history.isEmpty()) {
            historyElement.appendChild(element("p", "comments-empty", "No history yet."))
            return
        }
        state.historyBlocks.forEach { block -> historyElement.appendChild(renderHistoryEvent(state, block)) }
    }

    /**
     * One history block: what happened — possibly several things — then who and when,
     * **once**.
     *
     * Deliberately lighter than [renderComment] — a `div` rather than an
     * `article`, no border, no markdown. A comment is something a person wrote
     * and is worth a card; an event is a line in a log, and fifty of them styled
     * as cards would out-shout the conversation above them.
     *
     * The description leads and the byline follows, which is the opposite order
     * to a comment. A comment's author is the point — you read who is speaking
     * before you read what they said — whereas a history is scanned for *what
     * changed*, with the attribution as the follow-up.
     *
     * ── Why the fold, and why the byline stays at the bottom ─────────────────
     *
     * A single save that changes four fields writes four events, and this function used
     * to give each of them its own byline — the same name and the same timestamp,
     * repeated four times down a column, drowning the four sentences that were the
     * point. It now takes a [HistoryBlock]: a run of events that agree about the author,
     * the agent and the instant (see that type for why the comparison is exact), whose
     * sentences stack and whose attribution is stated once.
     *
     * The byline stays at the **bottom** of the block rather than moving to the top,
     * which is what a grouped list would ordinarily do. The paragraph above is the
     * reason: this function's whole ordering argument is that a history is scanned for
     * what changed, with attribution as the follow-up. Hoisting the byline to head each
     * block would invert exactly what that argument asks for and turn the history back
     * into a list of people.
     */
    private fun renderHistoryEvent(
        state: IssueBackingViewModel.State,
        block: HistoryBlock,
    ): HTMLElement {
        val el = element("div", "history-event")
        // Plain text, never innerHTML: the description is composed from an enum
        // but interpolates values a user typed — a title, a label's name, a relation
        // kind's — and there is no markdown here to justify the risk. element() sets
        // textContent, so this is safe by construction rather than by review.
        block.events.forEach { event ->
            el.appendChild(element("p", "history-what", state.historyDescription(event)))
        }
        val attribution = block.attribution
        val meta = element("div", "history-meta")
        meta.appendChild(element("span", "history-who", state.historyByline(attribution)))
        state.historyAgentBadge(attribution)?.let { badgeText ->
            val badge = element("span", "agent-badge")
            badge.children(agentIcon(), element("span", "agent-badge-label", badgeText))
            meta.appendChild(badge)
        }
        el.appendChild(meta)
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
     * dialog so it matches every other lunula confirmation.
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
        commentConfirm?.dismiss()
        commentConfirm = null
        parentConfirm?.dismiss()
        parentConfirm = null
        childConfirm?.dismiss()
        childConfirm = null
        relationConfirm?.dismiss()
        relationConfirm = null
        // The estimate popover mounts on the BODY, outside this window's pane, so
        // nothing this view re-renders will ever take it down. Closed by hand here for
        // the reason VersionDropdown.confirmDelete gives about its own confirmation
        // (LNL-155): a layer that outlives its opener is a layer left on screen.
        estimateSelect.close()
    }
}
