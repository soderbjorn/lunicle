/**
 * A small typeahead for choosing one issue by key or title (LNL-55).
 *
 * The epic feature needs a "pick an issue" control the description editor's
 * `PREFIX-NUMBER` popup cannot be: that one is welded to a contenteditable and
 * inserts text, where this returns a chosen id to a callback. So this is its own
 * control — but it borrows that popup's look, reusing the `mention-menu` /
 * `ticket-menu` CSS classes so the two typeaheads read as one thing.
 *
 * Deliberately client-side only: its candidates are handed in whole
 * ([setItems]) — the issue window already holds every linkable issue of the
 * project (IssueDetail.linkableIssues, filtered to the epic rules in the view
 * model) — so filtering is an in-memory substring match with no lookup to await.
 */
package se.soderbjorn.lunicle

import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent

/** One offerable issue: its id to return, its key to show, its title to show and match. */
data class IssuePickerItem(val id: Long, val key: String, val title: String)

class IssuePicker(
    placeholder: String,
    private val onSelect: (Long) -> Unit,
) {
    /** The container — `position: relative` in CSS so the menu can sit under the input. */
    val element: HTMLElement = element("div", "issue-picker")

    private val input: HTMLInputElement = textField(placeholder) { refresh() }
    private val menu: HTMLElement = element("div", "mention-menu ticket-menu issue-picker-menu")

    private var items: List<IssuePickerItem> = emptyList()
    private var shown: List<IssuePickerItem> = emptyList()
    private var highlight: Int = 0

    init {
        menu.style.display = "none"
        element.children(input, menu)
        input.onkeydown = { event -> onKeyDown(event) }
        // Losing focus closes the menu — but after a beat, so a click landing on a
        // row is processed before the menu it is in disappears. The row's own
        // mousedown (below) also guards this, belt and braces.
        input.onblur = {
            kotlinx.browser.window.setTimeout({ hide(); null }, 150)
            Unit
        }
    }

    /** Hand the picker its candidate set. Re-filters against whatever is typed. */
    fun setItems(next: List<IssuePickerItem>) {
        items = next
        if (menu.style.display != "none") refresh() else Unit
    }

    /** Enable or disable the whole control — a picker mid-write must not take a second pick. */
    fun setEnabled(enabled: Boolean) {
        input.disabled = !enabled
        if (!enabled) hide()
    }

    private fun refresh() {
        val query = input.value.trim().lowercase()
        shown = (if (query.isEmpty()) items else items.filter {
            it.key.lowercase().contains(query) || it.title.lowercase().contains(query)
        }).take(OPTION_LIMIT)
        highlight = 0
        draw()
    }

    private fun draw() {
        menu.innerHTML = ""
        if (shown.isEmpty()) {
            hide()
            return
        }
        shown.forEachIndexed { index, item ->
            val row = element("div", "mention-menu-item ticket-menu-item")
            if (index == highlight) row.classList.add("mention-menu-item-on")
            row.children(
                element("span", "ticket-menu-key", item.key),
                element("span", "ticket-menu-title", item.title),
            )
            // mousedown, not click: mousedown fires before the input's blur, so the
            // selection lands before the blur timeout can hide the menu.
            row.onmousedown = { event ->
                event.preventDefault()
                choose(item)
                Unit
            }
            menu.appendChild(row)
        }
        position()
        menu.style.display = "block"
    }

    /**
     * Anchor the (position: fixed) menu to the input's current box — the same
     * escape-the-overflow trick the description editor's popup uses. Fixed rather
     * than absolute because the input sits inside a scrolling pane above a footer,
     * and an absolute menu would be clipped by both. Opens upward when the input is
     * low in the viewport, so the add-child field at the foot of the editor still
     * shows its list.
     */
    private fun position() {
        val rect = input.getBoundingClientRect()
        menu.style.left = "${rect.left}px"
        menu.style.width = "${rect.width}px"
        val below = kotlinx.browser.window.innerHeight - rect.bottom
        if (below < 220 && rect.top > below) {
            menu.style.top = ""
            menu.style.bottom = "${kotlinx.browser.window.innerHeight - rect.top + 2}px"
        } else {
            menu.style.bottom = ""
            menu.style.top = "${rect.bottom + 2}px"
        }
    }

    private fun choose(item: IssuePickerItem) {
        input.value = ""
        hide()
        onSelect(item.id)
    }

    private fun onKeyDown(event: KeyboardEvent) {
        if (menu.style.display == "none") {
            // A press with the menu closed opens it on what is there — so an empty
            // field arrow-down shows the whole (capped) candidate list.
            if (event.key == "ArrowDown") {
                event.preventDefault()
                refresh()
            }
            return
        }
        when (event.key) {
            "ArrowDown" -> {
                event.preventDefault()
                highlight = (highlight + 1).coerceAtMost(shown.size - 1)
                draw()
            }
            "ArrowUp" -> {
                event.preventDefault()
                highlight = (highlight - 1).coerceAtLeast(0)
                draw()
            }
            "Enter" -> {
                event.preventDefault()
                shown.getOrNull(highlight)?.let { choose(it) }
            }
            "Escape" -> {
                event.preventDefault()
                hide()
            }
        }
    }

    private fun hide() {
        menu.style.display = "none"
    }

    private companion object {
        /** Cap, matching the description editor's ticket popup — a menu, not the whole board. */
        const val OPTION_LIMIT = 8
    }
}
