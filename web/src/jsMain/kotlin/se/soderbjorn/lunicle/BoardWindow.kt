/**
 * The board window's content: the project switcher and the issue board.
 *
 * The successor to the old MainView, re-housed from the page into the body of a
 * darkness floating window — the one window that opens maximised and cannot be
 * closed. The old top bar is gone (the darkness shell owns app chrome now); what
 * moved in here is the project switcher, per the redesign, with the project
 * settings gear beside it. "New project…" left the switcher for the shell's
 * "+" menu.
 *
 * A dumb view, like every view here: it builds its elements once in the
 * constructor, forwards intent to [MainScreenBackingViewModel], and re-renders
 * from whatever state it is handed. The one thing it knows that the view model
 * does not is how to drag — see [makeDraggable].
 *
 * @see MainScreenBackingViewModel
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import org.w3c.dom.DragEvent
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event
import se.soderbjorn.lunicle.client.viewmodel.BoardColumn
import se.soderbjorn.lunicle.client.viewmodel.MainScreenBackingViewModel
import se.soderbjorn.lunicle.clientserver.IssueSummary

/**
 * Renders the board window.
 *
 * @param viewModel the shared backing view model; the view's only collaborator.
 */
class BoardWindow(
    private val viewModel: MainScreenBackingViewModel,
) {
    /** The pane content element the shell mounts. Built eagerly, filled by [render]. */
    val root: HTMLElement = element("div", "board-pane")

    private val picker: HTMLSelectElement
    private val settingsButton: HTMLElement
    private val boardElement: HTMLElement
    private val emptyElement: HTMLElement

    /**
     * What the picker's option list is derived from, so it is rebuilt when —
     * and only when — it changes. A `<select>` rebuilt on every emission
     * closes itself the instant the user opens it.
     */
    private data class PickerContents(
        val ids: List<Long>,
        val hasCurrentProject: Boolean,
    )

    private var renderedContents: PickerContents? = null

    /**
     * The board object the columns were last built from — by IDENTITY, which is
     * exactly right for an immutable state: the view model replaces the board
     * object when and only when its content changes.
     *
     * The guard is not an optimisation, it is what keeps clicks working. A
     * mousedown anywhere in this pane reports focus (see main.kt), which emits
     * a state whose board is unchanged; rebuilding the columns on that emission
     * would destroy the very card the user is mid-click on, and a click whose
     * element died between mousedown and mouseup never fires.
     */
    private var renderedBoard: se.soderbjorn.lunicle.clientserver.BoardState? = null

    init {
        picker = select { value ->
            value.toLongOrNull()?.let(viewModel::onProjectSelected)
        }
        picker.className = "picker"

        settingsButton = button("", "icon-btn") { viewModel.onProjectSettingsTapped() }
        settingsButton.appendChild(gearIcon())
        settingsButton.title = "Project settings"

        val toolbar = element("div", "board-toolbar")
        toolbar.children(picker, settingsButton)

        boardElement = element("div", "board")
        emptyElement = element("p", "board-empty")

        root.children(toolbar, emptyElement, boardElement)
    }

    /** Apply a state snapshot. Called for every emission of the state flow. */
    fun render(state: MainScreenBackingViewModel.State) {
        renderPicker(state)
        settingsButton.visible(state.canEditCurrentProject, displayValue = "inline-flex")

        emptyElement.setTextIfChanged(state.emptyMessage ?: "")
        emptyElement.visible(state.emptyMessage != null)

        renderBoard(state)
    }

    private fun renderPicker(state: MainScreenBackingViewModel.State) {
        val contents = PickerContents(
            ids = state.projects.map { it.id },
            hasCurrentProject = state.currentProject != null,
        )
        if (contents != renderedContents || picker.options.length == 0) {
            renderedContents = contents
            picker.clear()
            if (state.currentProject == null) {
                picker.appendChild(
                    (document.createElement("option") as org.w3c.dom.HTMLOptionElement).apply {
                        value = ""
                        textContent = "No project"
                    },
                )
            }
            state.projects.forEach { project ->
                picker.appendChild(
                    (document.createElement("option") as org.w3c.dom.HTMLOptionElement).apply {
                        value = project.id.toString()
                        textContent = project.name
                    },
                )
            }
        }
        picker.value = state.currentProject?.id?.toString() ?: ""
    }

    private fun renderBoard(state: MainScreenBackingViewModel.State) {
        if (state.board === renderedBoard) return
        renderedBoard = state.board
        boardElement.clear()
        boardElement.visible(state.board != null, displayValue = "flex")
        state.columns.forEach { column ->
            boardElement.appendChild(renderColumn(state, column))
        }
    }

    private fun renderColumn(state: MainScreenBackingViewModel.State, column: BoardColumn): HTMLElement {
        val el = element("section", "column")
        val head = element("div", "column-head")
        head.children(
            element("span", "column-name", column.status.name),
            element("span", "column-count", column.issues.size.toString()),
        )

        val list = element("div", "column-list")
        column.groups.forEach { group ->
            list.appendChild(element("p", "group-head", group.label))
            group.issues.forEach { issue -> list.appendChild(renderCard(state, issue)) }
        }

        // The whole column is the drop target, not just the list — see the old
        // MainView for the reasoning; unchanged here.
        el.ondragover = { event ->
            event.preventDefault()
            el.classList.add("column-drop")
            Unit
        }
        el.ondragleave = { el.classList.remove("column-drop"); Unit }
        el.ondrop = { event ->
            event.preventDefault()
            el.classList.remove("column-drop")
            val id = (event as DragEvent).dataTransfer?.getData("text/plain")?.toLongOrNull()
            id?.let { viewModel.onIssueDragged(it, column.status.id) }
            Unit
        }

        el.children(head, list)
        return el
    }

    private fun renderCard(state: MainScreenBackingViewModel.State, issue: IssueSummary): HTMLElement {
        val card = element("article", "card")
        val cardTitle = element("p", "card-title", state.cardLabel(issue))
        cardTitle.title = state.cardLabel(issue)
        card.appendChild(cardTitle)

        val labelNames = state.board?.labels.orEmpty().filter { it.id in issue.labelIds }.map { it.name }
        val componentNames = state.board?.components.orEmpty().filter { it.id in issue.componentIds }.map { it.name }
        if (labelNames.isNotEmpty() || componentNames.isNotEmpty()) {
            val tags = element("div", "card-tags")
            labelNames.forEach { tags.appendChild(element("span", "tag tag-label", it)) }
            componentNames.forEach { tags.appendChild(element("span", "tag tag-component", it)) }
            card.appendChild(tags)
        }

        card.onclick = { viewModel.onIssueOpened(issue.id) }

        makeReorderTarget(card, issue.id)

        if (issue.canEdit) makeDraggable(card, issue.id) else card.classList.add("card-readonly")

        return card
    }

    /**
     * Let a card accept a drop from another card, above or below itself. The
     * midpoint decides before/after; see the old MainView for the full
     * reasoning — unchanged here.
     */
    private fun makeReorderTarget(card: HTMLElement, issueId: Long) {
        fun placeBefore(event: DragEvent): Boolean {
            val box = card.getBoundingClientRect()
            return event.clientY < box.top + box.height / 2
        }

        card.addEventListener("dragover", { event ->
            val drag = event as DragEvent
            drag.preventDefault()
            val before = placeBefore(drag)
            card.classList.toggle("card-drop-before", before)
            card.classList.toggle("card-drop-after", !before)
        })
        card.addEventListener("dragleave", { clearDropHint(card) })
        card.addEventListener("drop", { event ->
            val drag = event as DragEvent
            drag.preventDefault()
            drag.stopPropagation()
            clearDropHint(card)
            val draggedId = drag.dataTransfer?.getData("text/plain")?.toLongOrNull()
            if (draggedId != null) {
                viewModel.onIssueReordered(draggedId, issueId, placeBefore(drag))
            }
        })
    }

    private fun clearDropHint(card: HTMLElement) {
        card.classList.remove("card-drop-before")
        card.classList.remove("card-drop-after")
    }

    private fun makeDraggable(card: HTMLElement, issueId: Long) {
        card.draggable = true
        card.addEventListener("dragstart", { event ->
            (event as DragEvent).dataTransfer?.setData("text/plain", issueId.toString())
            event.dataTransfer?.effectAllowed = "move"
            card.classList.add("card-dragging")
        })
        card.addEventListener("dragend", { _: Event ->
            card.classList.remove("card-dragging")
            document.querySelectorAll(".card").asList()
                .filterIsInstance<HTMLElement>()
                .forEach { clearDropHint(it) }
        })
    }
}
