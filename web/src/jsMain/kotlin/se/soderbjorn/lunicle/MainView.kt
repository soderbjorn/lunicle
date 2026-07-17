/**
 * MainScreen: the top bar with the project picker, the cogwheel and the account
 * corner; the issue board; and the footer under both.
 *
 * A dumb view, like every view here: it builds its elements once in [mount],
 * forwards intent to [MainScreenBackingViewModel], and re-renders from whatever
 * [MainScreenBackingViewModel.State] it is handed. Every label, every enabled
 * flag and every column in it was decided in the shared view model. The one
 * thing it knows that the view model does not is how to drag — see
 * [makeDraggable].
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

/** The picker's sentinel for "New project…", which is not a project id. */
private const val NEW_PROJECT_VALUE = "new"

/**
 * Renders MainScreen.
 *
 * @param viewModel the shared backing view model; the view's only collaborator.
 */
class MainView(
    private val viewModel: MainScreenBackingViewModel,
) {
    /** Where the account corner goes. Exposed so the bootstrap can mount it, rather than finding it by class name. */
    lateinit var accountHost: HTMLElement
        private set

    /** Where dialogs mount. Exposed for the same reason. */
    lateinit var dialogHost: HTMLElement
        private set

    private lateinit var picker: HTMLSelectElement
    private lateinit var settingsButton: HTMLElement
    private lateinit var newIssueButton: HTMLElement
    private lateinit var boardElement: HTMLElement
    private lateinit var emptyElement: HTMLElement

    /**
     * Everything the picker's option list is derived from, so it is rebuilt when
     * — and only when — one of them changes.
     *
     * All three are needed. Keying on the ids alone was a bug with a very
     * specific shape: on a fresh instance with no projects, the ids stay `[]`
     * across sign-in, so the picker built once while signed out (no "New
     * project…", because the session had not landed yet) and never rebuilt. An
     * admin saw a picker that offered them nothing.
     */
    private data class PickerContents(
        val ids: List<Long>,
        val hasCurrentProject: Boolean,
        val canCreateProject: Boolean,
    )

    private var renderedContents: PickerContents? = null

    fun mount(host: HTMLElement) {
        val layout = element("div", "layout")
        val main = element("main", "main")

        // ── Top bar ──
        val topBar = element("header", "topbar")
        picker = select { value ->
            if (value == NEW_PROJECT_VALUE) {
                viewModel.onNewProjectTapped()
                // Put the picker back where it was: "New project…" is an action,
                // not a selection, and leaving it showing would suggest the user
                // is now "in" a project called that.
                renderPickerSelection(viewModel.stateFlow.value)
            } else {
                value.toLongOrNull()?.let(viewModel::onProjectSelected)
            }
        }
        picker.className = "picker"

        // The gear is drawn, not typed. It was "⚙" — an emoji, so its size, weight
        // and baseline were the OS's to choose and not ours, which is why it never
        // sat right in a 34px button. See Icons.kt.
        settingsButton = button("", "icon-btn") { viewModel.onProjectSettingsTapped() }
        settingsButton.appendChild(gearIcon())
        settingsButton.title = "Project settings"

        newIssueButton = button("New issue", "btn") { viewModel.onNewIssueTapped() }

        // Where SignInView mounts. Built here and handed over, so the top bar's
        // order lives in one place rather than half here and half in main.kt.
        accountHost = element("div", "topbar-account")

        // Three groups, not five children: the bar is a `1fr auto 1fr` grid, so
        // the left and right sides have to be single grid items or they would
        // each claim a track and shove the centre off centre. See .topbar.
        val left = element("div", "topbar-left")
        left.children(picker, settingsButton)

        // The centre track is this WRAPPER, and never the button itself.
        //
        // "New issue" is hidden for anyone who may not create one, and a grid
        // item with `display: none` is not laid out — it is removed from the grid
        // outright. With the button as the middle child, hiding it left two items
        // for three tracks, so the account corner slid from the right-hand `1fr`
        // into the centre `auto` and sat in the middle of the bar. The bug only
        // appeared for a caller without create rights, which is precisely the
        // caller nobody is signed in as while building the thing.
        //
        // An always-present wrapper keeps the three items the three tracks
        // require. Empty, it collapses to nothing and the centre track goes to
        // zero width, which is exactly right.
        val center = element("div", "topbar-center")
        center.children(newIssueButton)

        val right = element("div", "topbar-right")
        right.children(accountHost)

        topBar.children(left, center, right)

        // ── Board ──
        boardElement = element("div", "board")
        emptyElement = element("p", "board-empty")

        main.children(topBar, emptyElement, boardElement)

        // ── Footer ──
        // Sticky, so it is on screen with the board rather than below it. The
        // board scrolls sideways and can outrun the viewport downward too; a
        // footer that only appears at the bottom of a scroll is one most people
        // never see.
        val footer = element("footer", "footer")
        footer.children(
            logoIcon(),
            element("span", "footer-name", "Lunicle — a simple issue tracker by Robert Söderbjörn"),
        )

        dialogHost = element("div", "dialog-host")

        layout.children(main, footer)
        host.clear()
        host.children(layout, dialogHost)
    }

    /** Apply a state snapshot. Called for every emission of the state flow. */
    fun render(state: MainScreenBackingViewModel.State) {
        renderPicker(state)
        settingsButton.visible(state.canEditCurrentProject, displayValue = "inline-flex")
        newIssueButton.visible(state.canCreateIssue, displayValue = "inline-flex")

        emptyElement.setTextIfChanged(state.emptyMessage ?: "")
        emptyElement.visible(state.emptyMessage != null)

        // errorMessage is not drawn here any more — it is a modal now, owned by
        // the bootstrap alongside the other dialogs. See Dialogs.renderAlert.
        renderBoard(state)
    }

    /**
     * Rebuild the picker only when its contents change.
     *
     * A `<select>` rebuilt on every emission closes itself the instant the user
     * opens it — the state flow ticks for unrelated reasons, and the options are
     * replaced under the open dropdown. Comparing the ids first is what makes
     * the picker usable at all.
     */
    private fun renderPicker(state: MainScreenBackingViewModel.State) {
        val contents = PickerContents(
            ids = state.projects.map { it.id },
            hasCurrentProject = state.currentProject != null,
            canCreateProject = state.canCreateProject,
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
            if (state.canCreateProject) {
                // The spec asks for spacing before "New project…". A disabled
                // separator option is the only way a native <select> can express
                // that, and it stays unselectable.
                picker.appendChild(
                    (document.createElement("option") as org.w3c.dom.HTMLOptionElement).apply {
                        textContent = "──────────"
                        disabled = true
                    },
                )
                picker.appendChild(
                    (document.createElement("option") as org.w3c.dom.HTMLOptionElement).apply {
                        value = NEW_PROJECT_VALUE
                        textContent = "New project…"
                    },
                )
            }
        }
        renderPickerSelection(state)
    }

    private fun renderPickerSelection(state: MainScreenBackingViewModel.State) {
        picker.value = state.currentProject?.id?.toString() ?: ""
    }

    private fun renderBoard(state: MainScreenBackingViewModel.State) {
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
        // Groups, not a flat run of cards: a header per resolution in a closing
        // column, per priority everywhere else. The view model decided which and
        // in what order — see BoardColumn.groups.
        //
        // The header is drawn even when a column has exactly one group, which
        // looks redundant on a board where every issue is Normal and stops looking
        // redundant the moment two priorities are in play. A header that comes and
        // goes is harder to read than one that is always there.
        column.groups.forEach { group ->
            list.appendChild(element("p", "group-head", group.label))
            group.issues.forEach { issue -> list.appendChild(renderCard(state, issue)) }
        }

        // The whole column is the drop target, not just the list: dropping onto
        // the gap under the last card is the natural gesture for an empty
        // column, and a list with no height has nothing to drop onto.
        el.ondragover = { event ->
            // preventDefault is what *makes* an element a drop target. Without
            // it the browser refuses every drop and the drag silently does
            // nothing — the single most common way to get HTML5 drag wrong.
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
        // The card clamps to three lines (see .card-title), so a long title is
        // cut on the board. The tooltip is what keeps it readable without
        // opening the issue.
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

        // Each card is also a drop target, so a drag can say "before this one" or
        // "after it" rather than only naming a column. The column's own handler
        // still catches drops on the gap below the last card — see renderColumn.
        makeReorderTarget(card, issue.id)

        // Draggable only for someone who may edit *this* issue. An affordance —
        // the server runs canEditIssue on the move regardless — but the right
        // one: a card that drags and then springs back teaches nothing.
        if (issue.canEdit) makeDraggable(card, issue.id) else card.classList.add("card-readonly")

        return card
    }

    /**
     * Let a card accept a drop from another card, above or below itself.
     *
     * ── Why the pointer's Y matters ─────────────────────────────────────────
     *
     * A drop "on" a card is ambiguous: it means before it or after it, and which
     * one is the whole gesture. The midpoint decides — above the middle inserts
     * before, below inserts after — which is what every list that does this uses,
     * because it is what the pointer's position already implies.
     *
     * The class is toggled rather than a line being drawn, so the indicator lives
     * in CSS with the rest of the drag styling. See .card-drop-before/-after.
     *
     * `stopPropagation` on the drop, and only on the drop: the column below is
     * also a target, and without this a drop on a card would reorder AND then be
     * handled again as a move to the column — two writes for one gesture. The
     * dragover deliberately does NOT stop propagating, because the column wants to
     * know a drag is over it too.
     */
    private fun makeReorderTarget(card: HTMLElement, issueId: Long) {
        fun placeBefore(event: DragEvent): Boolean {
            val box = card.getBoundingClientRect()
            return event.clientY < box.top + box.height / 2
        }

        card.addEventListener("dragover", { event ->
            val drag = event as DragEvent
            // preventDefault is what MAKES an element a drop target; without it
            // the browser refuses the drop silently. Same as the column's.
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

    /**
     * Make a card draggable.
     *
     * The id travels in the drag's `text/plain` payload rather than in a field
     * on this class, because a drag can legitimately end over a different
     * column's handler and the payload is the only thing both sides see.
     */
    private fun makeDraggable(card: HTMLElement, issueId: Long) {
        card.draggable = true
        card.addEventListener("dragstart", { event ->
            (event as DragEvent).dataTransfer?.setData("text/plain", issueId.toString())
            event.dataTransfer?.effectAllowed = "move"
            card.classList.add("card-dragging")
        })
        card.addEventListener("dragend", { _: Event ->
            card.classList.remove("card-dragging")
            // Every card's hint, not just this one's: a drag that ends outside any
            // target — dropped on the page, or cancelled with Escape — fires no
            // `dragleave` on the card the pointer was last over, so its indicator
            // would stay drawn until the next drag happened to cross it.
            document.querySelectorAll(".card").asList()
                .filterIsInstance<HTMLElement>()
                .forEach { clearDropHint(it) }
        })
    }
}
