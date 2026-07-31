/**
 * The board window's content: the issue board, with a sprint scope and a live
 * filter above it.
 *
 * The successor to the old MainView, re-housed from the page into the body of a
 * lunula floating window — the one window that opens maximised and cannot be
 * closed. The old top bar is gone (the lunula shell owns app chrome now).
 *
 * The project switcher, the project-settings gear and the statistics button used
 * to live here too, in this window's toolbar. LNL-84 moved them up to the shell's
 * top bar, where the picker acts as a top-level navigator to the left of the tabs
 * — see [ProjectBar]. What is left in this window's toolbar is what scopes the
 * board *within* the chosen project: the sprint-scope picker and the filter box.
 *
 * A dumb view, like every view here: it builds its elements once in the
 * constructor, forwards intent to [MainScreenBackingViewModel], and re-renders
 * from whatever state it is handed. The one thing it knows that the view model
 * does not is how to drag — see [makeDraggable].
 *
 * The scope picker is a [Dropdown] — a button opening a themed menu — rather than
 * a `<select>`, whose open list only the operating system may draw. See
 * [Dropdown] for that story; it is not this view's to tell, and the issue
 * editor's three dropdowns are the same control.
 *
 * @see MainScreenBackingViewModel
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import org.w3c.dom.DragEvent
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.Event
import se.soderbjorn.lunicle.client.NO_TICKET_TITLES
import se.soderbjorn.lunicle.client.Ticket
import se.soderbjorn.lunicle.client.TicketTitleLookup
import se.soderbjorn.lunicle.client.renderInlineLinks
import se.soderbjorn.lunicle.client.viewmodel.BoardColumn
import se.soderbjorn.lunicle.client.viewmodel.canReorderOnto
import se.soderbjorn.lunicle.client.viewmodel.MainScreenBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.PriorityEmphasis
import se.soderbjorn.lunicle.clientserver.IssueSummary

/**
 * Renders the board window.
 *
 * @param viewModel the shared backing view model; the view's only collaborator.
 * @param titleFor expands a card title's ticket reference to `PREFIX-N: Title`
 *   (LNL-144), the same resolver every other reading surface uses; see main.kt.
 */
class BoardWindow(
    /**
     * The project this pane is the board of.
     *
     * Every intent this view raises names it. Since LNL-160 a tab can hold two
     * boards from two projects, so "the board" is not something the view model
     * could work out from a click — the pane is the only thing that knows.
     */
    private val projectId: Long,
    private val viewModel: MainScreenBackingViewModel,
    private val titleFor: TicketTitleLookup = NO_TICKET_TITLES,
    /**
     * Open this project's analytics, as a pane.
     *
     * A lambda rather than a view-model call, because *where a pane goes* is the
     * workspace's decision and this view has never heard of it. See main.kt.
     */
    private val onOpenAnalytics: () -> Unit = {},
    /** ...and its settings. Same reason. */
    private val onOpenSettings: () -> Unit = {},
    /**
     * ...and its access — who may see this project and who may change it (LNL-193).
     *
     * Its own entry rather than a click inside settings, because it is the question a
     * board raises: somebody is looking at work and wonders who else can. Settings is
     * one pane with five tabs now, so this names a section of it instead of opening a
     * second dialog — see main.kt's SettingsRoute.
     */
    private val onManageAccess: () -> Unit = {},
) {
    /** The pane content element the shell mounts. Built eagerly, filled by [render]. */
    val root: HTMLElement = element("div", "board-pane")

    /**
     * The sprint scope, beside the filter box.
     *
     * Built eagerly like everything else here and simply never shown on a board
     * with no sprints — see [render]. That is cheaper than conditional
     * construction and it is what the rest of this class does with the gear
     * button, which is also always built and sometimes hidden.
     */
    private val scopePicker = Dropdown(className = "picker") { viewModel.onSprintScopeSelected(projectId, it) }
    private val filterField: org.w3c.dom.HTMLInputElement =
        textField("Filter issues…") { viewModel.onFilterChanged(projectId, it) }
    private val boardElement: HTMLElement
    private val emptyElement: HTMLElement

    /**
     * The card currently being dragged, or null when nothing of ours is in
     * flight.
     *
     * Needed because `dragover` is where the drop hint has to be decided and
     * `dataTransfer.getData` is deliberately blank there — the drag data is only
     * readable on `drop`, so the source has to be remembered from `dragstart`.
     *
     * It is what makes a drop mean two different things: inside the column it
     * came from, a drag is a placement and the insertion point matters; into any
     * other column it is a status change, where an insertion point would be a
     * promise the board cannot keep (the new column's order is the server's, by
     * priority). Null — a drag that did not start on one of our cards — is
     * treated as "some other column", which is the conservative answer.
     *
     * The whole card rather than just its status id, which is what this held
     * before: deciding whether to draw the insertion line needs the resolution
     * too, since a closing column still refuses a drop across its groups. See
     * [IssueSummary.canReorderOnto], which is the predicate both this view and
     * the view model now ask — the two disagreeing about what a drop meant was
     * LNL-40.
     */
    private var draggingIssue: IssueSummary? = null

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

    /**
     * The filter query the columns were last built from, alongside
     * [renderedBoard]. The board's identity guard is not enough on its own: a
     * keystroke changes which cards show without replacing the board object, so
     * the rebuild has to watch the query too — while still ignoring the focus
     * emissions that change neither.
     */
    private var renderedQuery: String? = null

    /**
     * The sprint scope the columns were last built from, alongside
     * [renderedQuery] and for exactly its reason: picking a different sprint
     * changes which cards show without replacing the board object, so a rebuild
     * guarded on the other two alone would leave the board showing the old
     * scope's cards.
     */
    private var renderedScope: Long? = null

    /**
     * The hidden-column set the board was last built from, alongside the three
     * guards above and for their reason: hiding or restoring a column changes which
     * lanes the board draws without replacing the board object, so a rebuild
     * guarded on the others alone would leave a just-hidden column still on screen.
     * Null until the first render, which no real set equals, so the board always
     * builds once. See [MainScreenBackingViewModel.BoardScreen.hiddenColumnIds].
     */
    private var renderedHidden: Set<Long>? = null

    /**
     * Whether issue numbers were hidden the last time the board was built, alongside
     * the four guards above and for their reason (LNL-105): toggling the number off
     * changes what every card reads without replacing the board object, a filter, or
     * the hidden set, so a rebuild guarded on the others alone would leave the just-
     * hidden numbers on screen. Null until the first render, which no real value
     * equals. See [MainScreenBackingViewModel.BoardScreen.hideIssueNumbers].
     */
    private var renderedHideNumbers: Boolean? = null

    /**
     * How far each column is scrolled, by status id.
     *
     * Kept because the columns' scroll position does not survive a shell
     * re-render: [LayoutRenderer] wipes the pane area and rebuilds every pane
     * subtree, so this pane's content element is detached and re-appended — and
     * a detached element's `scrollTop` resets to 0, silently and without firing
     * a `scroll` event. Opening an issue adds a pane, which is exactly such a
     * re-render, so every click on a card threw the board back to the top. See
     * [restoreScroll], and main.kt's `onAfterRefresh`, which is what calls it.
     *
     * Keyed by status id rather than by element, so it survives the column
     * elements themselves being rebuilt (a filter keystroke, a project switch)
     * and means nothing for a column that has since gone.
     */
    private val columnScroll = mutableMapOf<Long, Double>()

    /**
     * The scrolling element of each column currently on screen, by status id.
     *
     * Rebuilt wholesale by [renderBoard], so it never names a column that is no
     * longer there — which is what lets [restoreScroll] simply walk it.
     */
    private val columnLists = mutableMapOf<Long, HTMLElement>()

    /**
     * The two project entries at the toolbar's trailing edge.
     *
     * Not buttons that toggle something: each OPENS a pane in the current tab,
     * which is how every other surface in this app appears. They say so with the
     * toolkit's new-window mark at the trailing edge of the label — the only
     * thing distinguishing "opens a pane" from "changes this view", which is why
     * both carry it and the filter and scope beside them do not.
     */
    private val analyticsButton = toolButton("Analytics", chartIcon(), onOpenAnalytics)
    private val settingsButton = toolButton("Project settings", gearIcon(), onOpenSettings)
    private val accessButton = toolButton("Manage access", peopleIcon(), onManageAccess)

    init {
        filterField.classList.add("board-filter")

        // The filter is a field with the magnifier INSIDE it rather than beside
        // it: the icon is what the field is for, and a separate glyph would be a
        // second object in a row that already has four.
        val filterBox = element("div", "board-filter-box")
        filterBox.children(searchIcon(), filterField)

        // Two scopes, one row, told apart by the gap alone — no box, no divider.
        // Board scope leads (the filter and, where a project has sprints, the
        // sprint picker: they change what THIS pane shows); the project entries
        // sit at the trailing edge (they open something new).
        val toolbar = element("div", "board-toolbar")
        val projectEntries = element("div", "board-toolbar-project")
        projectEntries.children(analyticsButton, accessButton, settingsButton)
        toolbar.children(filterBox, scopePicker.element, projectEntries)

        boardElement = element("div", "board")
        emptyElement = element("p", "board-empty")

        root.children(toolbar, emptyElement, boardElement)
    }

    /**
     * One trailing toolbar entry: icon, label, and the mark that says it opens a
     * pane.
     *
     * The label is not decoration. Two bare glyphs never said which SCOPE you
     * were acting on — a chart and a cog beside a filter read as three controls
     * over the same thing, when two of them are about the project and one is
     * about this view of it. Words are what separate them; the CSS drops the
     * words only when the pane is too narrow to keep them (see
     * `.board-toolbar`), and they are the last thing to go.
     */
    private fun toolButton(label: String, glyph: HTMLElement, onClick: () -> Unit): HTMLElement {
        val el = button("", "board-tool-btn", onClick)
        el.title = label
        el.children(
            glyph,
            element("span", "board-tool-label", label),
            newPaneIcon().also { it.classList.add("board-tool-open") },
        )
        return el
    }

    /** Apply a state snapshot. Called for every emission of the state flow. */
    fun render(state: MainScreenBackingViewModel.BoardScreen) {
        // Two fixed rows and then the sprints. Rendered before the visibility
        // check rather than inside it, so the control is correct the instant it
        // appears — a project that just had its first sprint made shows the new
        // list, not an empty one it would fill on the next emission.
        scopePicker.render(
            items = state.sprintScopeItems.map { DropdownItem(it.id, it.label) },
            // The effective scope, so the tick and the cards agree even when the
            // sprint somebody else deleted is still in state.sprintScope.
            selectedId = state.effectiveSprintScope,
        )
        // The accent dot: anything but "All issues" means the board in front of
        // you is a subset, and the one question a scoped board has to answer
        // without being asked is "am I seeing everything?". Same mark the filter
        // box wears while a query is live — one language for "this view is
        // narrowed", whichever control did the narrowing.
        scopePicker.setChanged(state.effectiveSprintScope != MainScreenBackingViewModel.SCOPE_ALL)
        // Absent, not disabled, on a board with no sprints. A greyed-out control
        // would tell a kanban project there is a feature here it is not using,
        // which is the thing this whole design is avoiding. See Sprints.sq.
        scopePicker.element.visible(state.showsSprintScope, displayValue = "inline-flex")

        // The box is only useful with a board behind it. setValueIfChanged
        // rather than a one-way bind, so a value the view model sets — the
        // restore, a clear — reaches the DOM without fighting the caret.
        filterField.setValueIfChanged(state.filterQuery)
        // The whole box, magnifier included — hiding the input alone would leave
        // a lone glyph in a pill on a pane with no board behind it.
        (filterField.parentElement as? HTMLElement)?.visible(state.board != null, displayValue = "inline-flex")

        // The two project entries, gated exactly as they were when they lived in
        // the top bar: analytics needs a board, settings needs a board and a
        // signed-in reader. Hidden rather than disabled — a control that cannot
        // do anything reads as broken rather than as forbidden.
        analyticsButton.visible(state.canOpenStatistics, displayValue = "inline-flex")
        settingsButton.visible(state.canOpenProjectSettings, displayValue = "inline-flex")
        // Gated exactly as the gear is: both land in the same pane, one tab apart, so
        // a reader who may open one may open the other.
        accessButton.visible(state.canOpenProjectSettings, displayValue = "inline-flex")

        emptyElement.setTextIfChanged(state.emptyMessage ?: "")
        emptyElement.visible(state.emptyMessage != null)

        renderBoard(state)
    }

    private fun renderBoard(state: MainScreenBackingViewModel.BoardScreen) {
        val hidden = state.hiddenColumnIds
        if (
            state.board === renderedBoard &&
            state.filterQuery == renderedQuery &&
            state.effectiveSprintScope == renderedScope &&
            hidden == renderedHidden &&
            state.hideIssueNumbers == renderedHideNumbers
        ) {
            return
        }
        renderedBoard = state.board
        renderedQuery = state.filterQuery
        renderedScope = state.effectiveSprintScope
        renderedHidden = hidden
        renderedHideNumbers = state.hideIssueNumbers
        boardElement.clear()
        columnLists.clear()
        boardElement.visible(state.board != null, displayValue = "flex")
        // The rail of collapsed columns sits before every full lane, at the board's
        // leading edge (LNL-100). Built only when something is hidden, so an
        // untouched board is exactly the flat row of columns it always was — no
        // empty rail element taking a gap's worth of width.
        val hiddenColumns = state.hiddenColumns
        if (hiddenColumns.isNotEmpty()) {
            boardElement.appendChild(renderHiddenRail(hiddenColumns))
        }
        state.shownColumns.forEach { column ->
            boardElement.appendChild(renderColumn(state, column))
        }
        // The columns are new elements, so they start at the top however far the
        // old ones were scrolled. Put them back where the user left them — a
        // filter keystroke rebuilds the board, and having it jump to the top on
        // every character typed is the same complaint as LNL-45.
        restoreScroll()
    }

    /**
     * The far-left rail of collapsed columns (LNL-100).
     *
     * One box per hidden column, in board order, each showing only the column's
     * name turned on its side. The boxes stack top-to-bottom and, when a stack
     * reaches the board's height, flow into a second stack to its right and so on —
     * so however many columns are hidden, the rail grows sideways a little rather
     * than pushing the live lanes off a tall board. The flow is CSS's (`column`
     * direction, `wrap`) against the rail's own height; this only builds the boxes.
     *
     * A whole click restores a column, so each box is a button. Just the name:
     * the rail is a parking strip, not a second board, and a count on a collapsed
     * lane invites reading numbers off something deliberately set aside — the count
     * is a click away, on the restored column's own head.
     */
    /**
     * One card tag: a coloured dot and the name beside it.
     *
     * @param kindClass the hue class — which axis this word is on. The colour is
     *   the only thing carried over from the filled chips this replaced.
     */
    private fun cardTag(kindClass: String, name: String): HTMLElement {
        val tag = element("span", "card-tag $kindClass")
        tag.children(element("span", "card-tag-dot"), element("span", "card-tag-name", name))
        return tag
    }

    private fun renderHiddenRail(hiddenColumns: List<BoardColumn>): HTMLElement {
        val rail = element("div", "board-hidden-rail")
        hiddenColumns.forEach { column ->
            val box = button("", "hidden-column") { viewModel.onShowColumn(projectId, column.status.id) }
            box.title = "Show ${column.status.name}"
            box.appendChild(element("span", "hidden-column-name", column.status.name))
            rail.appendChild(box)
        }
        return rail
    }

    /**
     * Scroll every column back to where the user left it.
     *
     * Safe to call at any time and as often as the caller likes: it writes
     * positions the columns already hold in the common case, and a column that
     * has since shrunk is clamped by the browser (and re-recorded by the
     * `scroll` event that write fires).
     *
     * Called by main.kt from the shell's `onAfterRefresh`, because the toolkit
     * rebuilds the pane subtree on every re-render and the re-attach silently
     * zeroes the scroll — see [columnScroll]. It must run *after* the re-attach:
     * `scrollTop` on a detached element is a no-op, so restoring from
     * [render] alone would do nothing.
     */
    fun restoreScroll() {
        columnLists.forEach { (statusId, list) ->
            val wanted = columnScroll[statusId] ?: return@forEach
            if (list.scrollTop != wanted) list.scrollTop = wanted
        }
    }

    private fun renderColumn(state: MainScreenBackingViewModel.BoardScreen, column: BoardColumn): HTMLElement {
        val el = element("section", "column")
        val head = element("div", "column-head")
        // Name on the left with the count riding right beside it in a ring, so the
        // number reads as a property of the column rather than drifting to the far
        // edge (LNL-123). The ⋮ trigger sits alone at the trailing edge; it is drawn
        // only for a signed-in user and only shows on hover — the whole of "hide" is
        // signed-in-only, and an always-visible control on every column head would
        // shout on a board nobody is customising. See styles.css `.column-count` and
        // `.column-menu`.
        val leading = element("div", "column-head-leading")
        leading.appendChild(element("span", "column-name", column.status.name))
        // The count sits at the head's TRAILING edge, and the ⋮ takes its place
        // there while the column is hovered — one slot, two things, never both.
        // Two slots would mean the count shifting sideways as the menu appeared,
        // which on a six-column board is six numbers twitching whenever the
        // pointer crosses a lane. Stacked in a fixed-width box so the swap is a
        // fade rather than a reflow; see `.column-head-trailing` in styles.css.
        val trailing = element("div", "column-head-trailing")
        trailing.appendChild(element("span", "column-count", column.issues.size.toString()))
        if (state.isSignedIn) {
            trailing.appendChild(columnMenuButton(column))
        }
        head.children(leading, trailing)

        val list = element("div", "column-list")
        // Remember where this column is scrolled to, so the position can be put
        // back after the shell detaches and re-attaches this pane. Recorded from
        // the event rather than read on demand, because by the time anything
        // asks, the re-attach has already zeroed it.
        columnLists[column.status.id] = list
        list.addEventListener("scroll", { columnScroll[column.status.id] = list.scrollTop })
        column.groups.forEach { group ->
            // The header and its cards carry the same class, so the colour the
            // header states is the colour the rail under it repeats — which is the
            // point, since the header scrolls away and the cards do not. See LNL-49.
            val emphasis = emphasisClass(group.emphasis)
            list.appendChild(element("p", "group-head$emphasis", group.label))
            group.issues.forEach { issue -> list.appendChild(renderCard(state, issue, emphasis)) }
        }

        // The whole column is the drop target, not just the list — see the old
        // MainView for the reasoning; unchanged here.
        //
        // The highlight is suppressed for the column the drag started in: there
        // the cards draw their own insertion point, and two hints at once read as
        // two different drops being offered.
        el.ondragover = { event ->
            event.preventDefault()
            el.classList.toggle("column-drop", draggingIssue?.statusId != column.status.id)
            Unit
        }
        el.ondragleave = { el.classList.remove("column-drop"); Unit }
        el.ondrop = { event ->
            event.preventDefault()
            el.classList.remove("column-drop")
            val id = (event as DragEvent).dataTransfer?.getData("text/plain")?.toLongOrNull()
            // Not onIssueDragged: this fires for the column's blank area and its
            // group headers as well as for another column, and within the card's
            // own column a status change is a no-op — which is why dropping at
            // the bottom of a column used to do nothing at all. The view model
            // sorts the two apart; see onIssueDroppedInColumn.
            id?.let { viewModel.onIssueDroppedInColumn(projectId, it, column.status.id) }
            Unit
        }

        el.children(head, list)
        return el
    }

    /**
     * The ⋮ overflow button for one column's head (LNL-100).
     *
     * A click opens the shared action popover — the same chrome the card's
     * right-click uses — anchored under the button rather than at the pointer so it
     * hangs off the control that summoned it. Two rows today: "Create issue…", which
     * starts a draft already filed in this column (LNL-124), and "Hide column".
     * Create comes first as the doing verb; hide is the housekeeping one.
     *
     * The menu is anchored off the button's own rectangle, read at click time: the
     * button helper reports no pointer event, and anchoring under the control is
     * what a menu opened by a button (as against a right-click) should do anyway.
     */
    private fun columnMenuButton(column: BoardColumn): HTMLElement {
        val menuButton = button("", "icon-btn column-menu") {}
        menuButton.appendChild(ellipsisIcon())
        menuButton.title = "Column actions"
        menuButton.onclick = {
            val rect = menuButton.getBoundingClientRect()
            openActionMenu(
                x = rect.left,
                y = rect.bottom + 2.0,
                actions = listOf(
                    "Create issue…" to { viewModel.onNewIssueTapped(projectId, column.status.id) },
                    "Hide column" to { viewModel.onHideColumn(projectId, column.status.id) },
                ),
            )
            Unit
        }
        return menuButton
    }

    /**
     * The class that draws a group's urgency, or "" for the ordinary majority.
     *
     * Leading space included, so callers can append it to a class string without
     * minding whether there is one. The mapping lives here rather than in the view
     * model for the reason [PriorityEmphasis] gives: a stylesheet is the web's
     * business, not the shared module's.
     */
    private fun emphasisClass(emphasis: PriorityEmphasis?): String = when (emphasis) {
        PriorityEmphasis.URGENT -> " priority-urgent"
        PriorityEmphasis.HIGH -> " priority-high"
        null -> ""
    }

    /**
     * The assignee's initials, as a round mark at the trailing edge of the title line
     * (LNL-215) — or null, which is the whole of what an unassigned card shows.
     *
     * ── Why a profile mark and not a name ────────────────────────────────────
     *
     * The card already spends a full line on the author's name at its foot, and a
     * second name would double the text on a card whose job is to be scanned. A
     * circular two-letter mark is the idiom every tracker uses for "who is on this",
     * it costs one glyph-pair of width whatever the name's length, and it is
     * recognisable across a column without being read. The full name rides on the
     * `title` attribute — the same trick the agent badge already uses on this card,
     * for the same reason: the discoverable half must not cost layout.
     *
     * ── Deliberately NOT gated on showIssueAuthor ────────────────────────────
     *
     * That switch is about the *author*, and the assignee is a different fact — one is
     * who filed the work, the other is who is doing it. A project that hides the author
     * to keep its cards quiet has said nothing about the assignee, and reading it as
     * having done so would silently take a feature away from every board that had that
     * switch off.
     *
     * ── Initials, including for names this file cannot read ──────────────────
     *
     * The first letters of the first two whitespace-separated words, uppercased:
     * "Robert Söderbjörn" → "RS". A single-word name falls back to its own first two
     * characters, so "soderbjorn" → "SO" rather than a lone letter that looks like a
     * bug. Non-Latin scripts get whatever their first characters are and are NOT
     * transliterated — a Latinised initial is a guess about somebody's name that a
     * board card has no standing to make, and taking the characters as given at least
     * produces a mark that person recognises.
     *
     * ── Neutral, never generated per name ────────────────────────────────────
     *
     * A hash-to-hue avatar is the obvious flourish and is wrong here: the priority
     * stripe and the label dots already spend this card's colour budget on facts about
     * the issue, and a third hue that means "this is Robert" would compete with them
     * while carrying no information the letters do not. So the mark takes a theme
     * token; see `.card-avatar`.
     */
    private fun assigneeAvatar(issue: IssueSummary): HTMLElement? {
        val name = issue.assigneeName?.takeIf { it.isNotBlank() } ?: return null
        val mark = element("span", "card-avatar")
        mark.title = if (issue.assigneeIsAgent) "$name's agent" else name
        mark.appendChild(element("span", "card-avatar-initials", initialsOf(name)))
        // The agent mark badges the avatar's bottom-right corner, overlapping it —
        // the status-dot idiom, and not a second element beside it. Beside would read
        // as two people; on the corner it reads as a property of the one, which is what
        // it is: the work is going to this person's agent, through this person's own
        // session. See IssueSummary.assigneeIsAgent.
        if (issue.assigneeIsAgent) {
            val badge = element("span", "card-avatar-agent")
            badge.appendChild(agentIcon())
            mark.appendChild(badge)
        }
        return mark
    }

    /**
     * Two letters for a display name. See [assigneeAvatar] for the rules and why they
     * are what they are.
     *
     * Split on any whitespace run rather than on a single space, because a name pasted
     * out of a directory can carry a tab or a double space, and "R" beside an empty
     * second initial would be the one output that looks broken rather than merely
     * approximate.
     */
    private fun initialsOf(name: String): String {
        val words = name.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val letters = when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}"
            words.size == 1 -> words[0].take(2)
            else -> ""
        }
        return letters.uppercase()
    }

    /**
     * The "blocked" chip for the meta line (LNL-215): the app's own forbidden mark and
     * the word, muted and unfilled.
     *
     * **No fill colour**, unlike the epic badge one line up. A filled chip is a claim on
     * attention, and a blocked card is not more important than an unblocked one — it is
     * *less* actionable, which is the opposite argument. The dim on the card around it
     * is already saying "not now"; a red pill would say "look here" at the same time.
     *
     * The blockers ride on the `title` — "Blocked by LNL-98, LNL-140" — built from the
     * server's [IssueSummary.blockedByNumbers] and the reader's own project prefix,
     * which is the right prefix because a relation's two ends share a project. Naming
     * them on the chip itself was the alternative and does not survive contact with an
     * issue blocked by three things: the line would wrap, and the card would grow the
     * row this design just spent a paragraph avoiding.
     *
     * The glyph is [FORBIDDEN_SVG] rather than a ⛔ emoji, for the reason Icons.kt gives
     * about the agent mark: an emoji renders in the operating system's own colour and
     * weight, neither of which this stylesheet can reach, and it would be the one mark
     * on the board that did not follow the theme.
     */
    private fun blockedChip(state: MainScreenBackingViewModel.BoardScreen, issue: IssueSummary): HTMLElement {
        val chip = element("span", "card-blocked-chip")
        val glyph = element("span", "card-blocked-icon")
        // Sanctioned innerHTML: the markup is Icons.kt's own constant, so there is
        // nothing here a user could have typed. See Icons.kt's FORBIDDEN_SVG.
        glyph.innerHTML = FORBIDDEN_SVG
        chip.children(glyph, element("span", "card-blocked-label", "Blocked"))
        val prefix = state.project?.namePrefix
        val keys = issue.blockedByNumbers.mapNotNull { number -> prefix?.let { "$it-$number" } }
        // "Blocked" on its own when the blockers could not be named — an unprefixed
        // board is not a state this app produces, but a title reading "Blocked by " with
        // nothing after it would be worse than no title at all.
        chip.title = if (keys.isEmpty()) "Blocked" else "Blocked by ${keys.joinToString(", ")}"
        return chip
    }

    private fun renderCard(
        state: MainScreenBackingViewModel.BoardScreen,
        issue: IssueSummary,
        emphasis: String,
    ): HTMLElement {
        val card = element("article", "card$emphasis")
        val label = state.cardLabel(issue)
        val cardTitle = element("p", "card-title")
        // A URL pasted into a title becomes a link, same as one in a description
        // (LNL-112), and so does a reference to any ticket the reader can reach —
        // this project's or another's (LNL-139), whence the whole accessible-project
        // prefix set. renderInlineLinks escapes the label first and emits only its
        // own checked <a>s, so this innerHTML carries nothing the title did not.
        // A card's line leads with its own key ("LMX-12: Title"), so the card's own
        // issue is passed as `self`: its key stays plain text — not linked back to
        // the card you are looking at, nor expanded to "LMX-12: Title: Title" —
        // while every other reference on the card still links and expands (LNL-151,
        // superseding the title-only exclusion of LNL-144).
        val self = state.project?.namePrefix?.let { Ticket(it, issue.number) }
        cardTitle.innerHTML = renderInlineLinks(label, state.prefixes, self = self, titleFor = titleFor)
        // Tooltip stays the plain text — an attribute, never markup.
        cardTitle.title = label
        // A link is its own destination: clicking it must open the URL, not the
        // issue behind the whole card. Every other click on the title falls
        // through to the card's own handler.
        cardTitle.onclick = { event ->
            if ((event.target as? Element)?.closest("a") != null) event.stopPropagation()
            Unit
        }
        // An epic wears a FILLED count badge on the title line (LNL-154), and an
        // assigned card wears an initials avatar at the trailing edge of the same line
        // (LNL-215). The title is line-clamped (display:-webkit-box), which cannot hold
        // an inline sibling — so title, badge and avatar share a flex row instead, the
        // two marks fixed at the end while the title takes the rest.
        //
        // The avatar rides IN the row rather than being absolutely positioned over the
        // card's corner, which was the obvious alternative and is the wrong one twice
        // over: it would sit on top of the epic badge, which already owns that corner,
        // and "the title must never run underneath it" would then be a padding value
        // kept in step with the avatar's width by hand. A flex sibling cannot be run
        // underneath, so the requirement is met by construction rather than by arithmetic.
        //
        // childCount is the server's project-wide count, not a tally of this board's
        // cards; see IssueSummary.childCount.
        val avatar = assigneeAvatar(issue)
        if (issue.childCount > 0 || avatar != null) {
            val head = element("div", "card-head")
            head.appendChild(cardTitle)
            if (issue.childCount > 0) {
                val badge = element("span", "card-epic", "▦ ${issue.childCount}")
                badge.title = if (issue.childCount == 1) "Epic — 1 child" else "Epic — ${issue.childCount} children"
                head.appendChild(badge)
            }
            avatar?.let { head.appendChild(it) }
            card.appendChild(head)
        } else {
            card.appendChild(cardTitle)
        }

        // The meta line under the title, carrying up to two facts about where this card
        // stands rather than what it is: the epic it belongs to (LNL-154) and whether
        // something is blocking it (LNL-215).
        //
        // ONE line for both, on purpose. A blocked card that grew a row of its own
        // would be taller than an unblocked one, so a column with three blocked cards
        // in it would be visibly ragged — and a card gets no more attention for being
        // blocked than the chip itself already asks for. A card that is both a child
        // and blocked reads "↳ LNL-98   ⛔ Blocked" across the one line.
        //
        // A child points back at its epic with the parent's key, muted, clickable. The
        // key and number come from the server (state.parentKey over
        // IssueSummary.parentNumber), so this renders even when the parent is not a
        // card on this board. stopPropagation so opening the parent does not also
        // fire the card's own onclick — the title's inline links do the same above.
        val parentKey = state.parentKey(issue)
        val parentId = issue.parentId
        val showsParent = parentKey != null && parentId != null
        if (showsParent || issue.isBlocked) {
            val meta = element("div", "card-parent")
            if (showsParent) {
                val link = element("a", "card-parent-link", "↳ $parentKey")
                link.title = "Part of $parentKey"
                link.onclick = { event ->
                    event.stopPropagation()
                    viewModel.onIssueOpened(projectId, parentId)
                    Unit
                }
                meta.appendChild(link)
            }
            if (issue.isBlocked) meta.appendChild(blockedChip(state, issue))
            card.appendChild(meta)
        }

        // Blocked cards are dimmed as well as chipped (LNL-215) — see .card-blocked in
        // styles.css for why the treatment had to differ in KIND from anything a closed
        // card wears, and what a closed card actually wears (nothing).
        if (issue.isBlocked) card.classList.add("card-blocked")

        val labelNames = state.board?.labels.orEmpty().filter { it.id in issue.labelIds }.map { it.name }
        val componentNames = state.board?.components.orEmpty().filter { it.id in issue.componentIds }.map { it.name }
        if (labelNames.isNotEmpty() || componentNames.isNotEmpty()) {
            // A dot and a word, not a filled chip (LNL-160). A card is one
            // `surfaceAlt` plane now, and a pill filled with the same tone would
            // be invisible on it — while a pill filled with anything else would be
            // a value no theme declares. What survives is the hue, as a 7px dot,
            // because it is describing the issue rather than the interface: which
            // axis this word is on (a label, or a component).
            val tags = element("div", "card-tags")
            labelNames.forEach { tags.appendChild(cardTag("card-tag-label", it)) }
            componentNames.forEach { tags.appendChild(cardTag("card-tag-component", it)) }
            card.appendChild(tags)
        }

        // The author on a muted footer line at the very bottom (LNL-157), shown only
        // when this project has the display setting on — read off the board it
        // already loads, like every other card affordance. Clear of the title, the
        // priority stripe and the tags above it. When an agent filed it, the agent
        // badge keeps the issue detail view's pill and agentIcon but drops the
        // "Agent · …" text: on a card the label costs more horizontal room than it
        // earns, and the icon alone already reads as "an agent filed this". The agent
        // name rides on the title attribute so it stays discoverable on hover. The
        // badge sits beside the author name rather than in place of it — the issue is
        // still the author's. See IssueWindow.renderComment and IssueBackingViewModel.agentBadge.
        //
        // "Filed by Robert", not a bare "Robert" (LNL-215). The name alone at the foot
        // of a card is unattributed: it could as easily be who is *on* it, and now that
        // the card really does carry an assignee two lines above, the ambiguity is one
        // somebody would have to resolve by clicking. Two words fix it.
        if (state.board?.project?.showIssueAuthor == true && !issue.authorName.isNullOrBlank()) {
            val footer = element("div", "card-author")
            footer.appendChild(element("span", "card-author-name", "Filed by ${issue.authorName}"))
            issue.agentName?.let { agent ->
                val badge = element("span", "agent-badge agent-badge-icononly")
                badge.appendChild(agentIcon())
                badge.title = "Agent · $agent"
                footer.appendChild(badge)
            }
            card.appendChild(footer)
        }

        card.onclick = { viewModel.onIssueOpened(projectId, issue.id) }

        // Right-click schedules. The board's two drag axes are already spent —
        // dropping on a column is status, position within it is order — and
        // overloading a third meaning onto the same gesture would be worse than a
        // menu. See LNL-12.
        //
        // The native menu is left alone when there is nothing to offer: no
        // sprints in this project, nothing this caller may edit, or an issue
        // already in the only place it could go. Suppressing the browser's own
        // menu to show none of our own is the one outcome worse than not handling
        // the gesture at all.
        card.oncontextmenu = handler@{ event ->
            val destinations = viewModel.sprintDestinationsFor(projectId, issue)
            if (destinations.isEmpty()) return@handler true
            event.preventDefault()
            val mouse = event as MouseEvent
            openContextMenu(
                x = mouse.clientX.toDouble(),
                y = mouse.clientY.toDouble(),
                items = destinations.map { DropdownItem(it.id, it.label) },
            ) { viewModel.onIssueSprintChosen(projectId, issue.id, it) }
            false
        }

        makeReorderTarget(card, issue)

        if (issue.canEdit) {
            makeDraggable(card, issue)
        } else {
            card.classList.add("card-readonly")
        }

        return card
    }

    /**
     * Let a card accept a drop from another card. The midpoint decides
     * before/after; see the old MainView for the full reasoning.
     *
     * What a drop on a card *means* depends on where the drag started. Within one
     * column it is a placement, and the insertion point is the whole point of it.
     * From another column it is a status change, and it must behave exactly as if
     * the card had been dropped on the empty part of the column — a drop aimed at
     * a gap between two cards used to be swallowed by `onIssueReordered` and the
     * status silently did not change.
     *
     * The insertion line is drawn if and only if the drop will land, which is
     * [IssueSummary.canReorderOnto] and not "same column" as it used to be. That
     * mismatch was LNL-40: every drag across a priority header drew a line the
     * drop then ignored. A drop across a closing column's resolutions still does
     * not land — and now shows no line either, so it no longer promises one.
     */
    private fun makeReorderTarget(card: HTMLElement, issue: IssueSummary) {
        fun placeBefore(event: DragEvent): Boolean {
            val box = card.getBoundingClientRect()
            return event.clientY < box.top + box.height / 2
        }

        /** Whether the drag in flight can land beside this card. */
        fun lands(): Boolean = draggingIssue?.canReorderOnto(issue) == true

        card.addEventListener("dragover", { event ->
            val drag = event as DragEvent
            drag.preventDefault()
            // Nowhere to land: no insertion hint at all, and the event is left to
            // bubble so the column can highlight as a whole. Dropping into
            // another column always means "put it in this column", not "put it
            // here"; dropping across a closing column's resolutions means
            // nothing, and says so by offering nothing.
            if (!lands()) {
                clearDropHint(card)
                return@addEventListener
            }
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
                ?: return@addEventListener
            if (lands()) {
                viewModel.onIssueReordered(projectId, draggedId, issue.id, placeBefore(drag))
            } else {
                // Handled here rather than by letting it bubble, so the column's
                // handler stays the one true path for one kind of drop only.
                // Harmless when the two are in the same column and the drop
                // simply cannot land: the view model refuses it either way.
                viewModel.onIssueDroppedInColumn(projectId, draggedId, issue.statusId)
            }
        })
    }

    private fun clearDropHint(card: HTMLElement) {
        card.classList.remove("card-drop-before")
        card.classList.remove("card-drop-after")
    }

    private fun makeDraggable(card: HTMLElement, issue: IssueSummary) {
        card.draggable = true
        card.addEventListener("dragstart", { event ->
            (event as DragEvent).dataTransfer?.setData("text/plain", issue.id.toString())
            event.dataTransfer?.effectAllowed = "move"
            draggingIssue = issue
            card.classList.add("card-dragging")
        })
        card.addEventListener("dragend", { _: Event ->
            draggingIssue = null
            card.classList.remove("card-dragging")
            document.querySelectorAll(".card").asList()
                .filterIsInstance<HTMLElement>()
                .forEach { clearDropHint(it) }
            document.querySelectorAll(".column").asList()
                .filterIsInstance<HTMLElement>()
                .forEach { it.classList.remove("column-drop") }
        })
    }

    private companion object {
        /** Any run of whitespace, for splitting a display name into words. See [initialsOf]. */
        val WHITESPACE = Regex("""\s+""")
    }
}
