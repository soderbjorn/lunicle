/**
 * Small DOM helpers.
 *
 * The views are Kotlin DOM calls rather than a framework or a template — same
 * as the rest of this project — and without these every element costs four
 * lines of `createElement` and a cast. Nothing here makes a decision; it is
 * shorthand and only shorthand.
 *
 * @see MainView
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/** Create an element with a class, and optionally some text. */
fun element(tag: String, className: String = "", text: String? = null): HTMLElement {
    val el = document.createElement(tag) as HTMLElement
    if (className.isNotEmpty()) el.className = className
    if (text != null) el.textContent = text
    return el
}

/** Create a button. Always `type=button`: a bare button inside a form submits it. */
fun button(label: String, className: String = "", onClick: () -> Unit): HTMLButtonElement {
    val el = document.createElement("button") as HTMLButtonElement
    el.className = className
    el.type = "button"
    el.textContent = label
    el.onclick = { onClick() }
    return el
}

/** Create a text input that reports every keystroke. */
fun textField(placeholder: String = "", onInput: (String) -> Unit): HTMLInputElement {
    val el = document.createElement("input") as HTMLInputElement
    el.type = "text"
    el.className = "field"
    el.placeholder = placeholder
    el.oninput = { onInput(el.value); Unit }
    return el
}

/**
 * Create a text input that reports only when the user is *done* — on blur, and
 * on Enter.
 *
 * The counterpart to [textField], and the difference is who is listening. A field
 * whose every keystroke is an intent is right when the intent is cheap and local
 * ("is this name taken?"). It is wrong when the intent is a request: the
 * vocabulary rows in the project dialog rename on commit, and an `oninput`
 * version of them would send a PUT per character — "C", "Cl", "Clo" — each one
 * renaming a board column for everybody.
 *
 * `onchange` is exactly this event in the DOM: it fires on blur when the value
 * differs from what it was on focus, and Enter blurs. Nothing more is needed, and
 * a hand-rolled blur/keydown pair would fire twice for Enter.
 */
fun textFieldCommitting(onCommit: (String) -> Unit): HTMLInputElement {
    val el = document.createElement("input") as HTMLInputElement
    el.type = "text"
    el.className = "field"
    el.onchange = { onCommit(el.value); Unit }
    return el
}

/**
 * An On/Off pair of buttons — darkness's boolean control, and now Lunicle's.
 *
 * The toolkit spells a boolean as two buttons with the live one accented (see
 * the settings sidebar's `dt-settings-choice-btn` rows), not as a checkbox, and
 * a Lunicle dialog sitting inside that shell should not disagree about what a
 * boolean looks like. The row it goes in stays a row: [toggleRow] puts the pair
 * where the checkbox used to be, with the label beside it.
 *
 * Reuses the toolkit's own class names rather than restyling a copy, so these
 * follow the toolkit's chrome — and every theme's accent — for free.
 *
 * The rendered state is [checked]'s alone; a click reports the intent and
 * nothing else, exactly as `onchange` did. If the view model refuses (a
 * privilege the server rejects, a request in flight), the next render says so
 * and the buttons never claimed otherwise.
 */
class Toggle(private val onChange: (Boolean) -> Unit) {
    /** The pair, ready to append. */
    val element: HTMLElement = element("div", "toggle dt-settings-button-row")

    private val buttons: List<HTMLButtonElement> = listOf(true, false).map { value ->
        button(if (value) "On" else "Off", "dt-settings-choice-btn") {
            // Guarded, not just visually dead: `disabled` stops the click, but a
            // click on the already-live side is still a no-op worth swallowing —
            // it is not a change, and the view models treat every call as one.
            if (value != checked) onChange(value)
        }.also { element.appendChild(it) }
    }

    /** Which side is live. Set it to render; it is never changed by a click. */
    var checked: Boolean = false
        set(value) {
            field = value
            buttons[0].classList.toggle("dt-selected", value)
            buttons[1].classList.toggle("dt-selected", !value)
        }

    /** Both sides at once: a toggle nobody may move is dead as a whole. */
    var disabled: Boolean = false
        set(value) {
            field = value
            buttons.forEach { it.disabled = value }
        }

    init {
        checked = false
    }
}

/**
 * A [Toggle] and its label on one row — the label leading, the pair trailing.
 *
 * A `div`, not a `label`: a label wrapping a button hands the button's clicks to
 * the label's own activation behaviour, and the pair would fire twice.
 */
fun toggleRow(toggle: Toggle, label: String, className: String = ""): HTMLElement {
    val row = element("div", "toggle-row" + if (className.isEmpty()) "" else " $className")
    row.children(element("span", "toggle-label", label), toggle.element)
    return row
}

/** Create a textarea. */
fun textArea(placeholder: String = "", onInput: (String) -> Unit): HTMLTextAreaElement {
    val el = document.createElement("textarea") as HTMLTextAreaElement
    el.className = "field field-multiline"
    el.placeholder = placeholder
    el.oninput = { onInput(el.value); Unit }
    return el
}

/** One row of a [Dropdown]: the id reported on select, and the text shown. */
data class DropdownItem(val id: Long, val label: String)

/**
 * A dropdown — a button that opens a themed menu.
 *
 * Lunicle's replacement for `<select>`, and it replaced every one of them. The
 * closed control was never the problem: `appearance: none` takes the platform's
 * chrome off and the CSS puts ours on. The problem is the *open* list, which the
 * operating system draws — an Aqua popup with a system-blue selection, in nobody's
 * palette but the OS's, and the one surface in this app a stylesheet cannot reach.
 * It read as a control borrowed from somewhere else because it was.
 *
 * What opens instead is the darkness toolkit's menu: `.dt-hover-menu` chrome,
 * `.dt-world-row` rows, a checkmark on the live one — the same popover the shell's
 * "+" button and world switcher open, reached by reusing the classes rather than
 * restyling a copy. So it matches the shell under every theme, and follows the
 * toolkit's next restyle for free. The toolkit's own builder is private and
 * hardwired to worlds, which is why the chrome is rebuilt here and only the rows
 * differ.
 *
 * The menu exists only while it is open, which is what makes this simpler than the
 * `<select>` it replaced rather than more complicated. A `<select>` had to be
 * guarded against re-rendering — rebuild its options while the user has the list
 * down and it shuts in their face — so every caller carried a "have the ids
 * changed?" field. A menu that is built at open time and thrown away on close
 * cannot be rebuilt underneath anybody, so [render] is free to run on every
 * emission and the guards are gone.
 *
 * @param className extra classes for the closed control — `picker` in the board
 *   toolbar, `field` in a dialog. Both are only about the surface it sits on.
 * @param onSelect fires with the chosen row's id. A click on the live row still
 *   reports: it is the user saying so, and the view models already treat a
 *   no-change as a no-op.
 */
class Dropdown(
    className: String = "",
    private val onSelect: (Long) -> Unit,
) {
    /** The closed control. Append this; the menu mounts on the body. */
    val element: HTMLButtonElement = button("", ("dropdown $className").trim()) {}

    private var items: List<DropdownItem> = emptyList()
    private var selectedId: Long? = null
    private var menu: HTMLElement? = null
    private var dismiss: (() -> Unit)? = null

    init {
        element.setAttribute("aria-haspopup", "menu")
        element.onclick = { if (menu != null) close() else open(); Unit }
    }

    /**
     * Render the closed control, and say what the menu will hold when opened.
     *
     * @param items the rows, in the order they should read.
     * @param selectedId the live row, or null for none — a resolution starts
     *   unset and the user must choose.
     * @param placeholder what the control reads while [selectedId] is null.
     */
    fun render(items: List<DropdownItem>, selectedId: Long?, placeholder: String = "") {
        this.items = items
        this.selectedId = selectedId
        val label = items.firstOrNull { it.id == selectedId }?.label ?: placeholder
        element.setTextIfChanged(label)
    }

    /** Tears the menu down, listeners included. A no-op when already closed. */
    fun close() {
        menu?.remove()
        menu = null
        dismiss?.invoke()
        dismiss = null
    }

    private fun open() {
        if (items.isEmpty()) return

        val box = element("div", "dropdown-menu dt-hover-menu")
        box.setAttribute("role", "menu")
        items.forEach { item -> box.appendChild(row(item)) }

        // Under the control's left edge, clamped to the viewport — the toolkit's
        // own popover math (WorldSwitcher.anchorPopover), so this lands where the
        // shell's menus land. Mounted first because the width the clamp needs is
        // the width the box has once laid out, and an unmounted box has none.
        document.body?.appendChild(box)
        val anchor = element.getBoundingClientRect()
        // At least as wide as the control it drops out of: the toolkit's 180px
        // floor is tuned for a topbar icon's menu, and a menu narrower than its
        // own button reads as a different control's.
        box.style.minWidth = "${anchor.width}px"
        val width = box.getBoundingClientRect().width
        val left = anchor.left.coerceAtMost(window.innerWidth - width - 4.0).coerceAtLeast(4.0)
        box.style.left = "${left}px"
        box.style.top = "${anchor.bottom + 4}px"

        menu = box
        installDismissal(box)
    }

    private fun row(item: DropdownItem): HTMLElement {
        val active = item.id == selectedId
        val row = element("div", "dt-hover-menu-item dt-world-row" + if (active) " dt-active" else "")
        row.setAttribute("role", "menuitem")

        val check = element("span", "dt-hover-menu-icon dt-world-check")
        // Sanctioned innerHTML, on Icons.kt's terms: the argument is that file's
        // own constant, so there is no input to escape and no caller to pass one.
        if (active) check.innerHTML = CHECK_SVG

        row.children(check, element("span", "dt-hover-menu-label", item.label))
        row.onclick = {
            close()
            onSelect(item.id)
            Unit
        }
        return row
    }

    /**
     * Closes on Escape, or on a click outside.
     *
     * The outside handler ignoring clicks on the control is not a nicety: this
     * listener is installed during the very click that opened the menu, and that
     * click is still bubbling. Without the guard the menu would close on the way
     * up from the button that just opened it, and the control would look dead.
     * The control's own handler is what toggles it shut.
     */
    private fun installDismissal(box: HTMLElement) {
        val outside: (Event) -> Unit = handler@{ event ->
            val target = event.target as? HTMLElement ?: return@handler
            if (box.contains(target) || element.contains(target)) return@handler
            close()
        }
        val escape: (Event) -> Unit = { event ->
            if ((event as? KeyboardEvent)?.key == "Escape") close()
        }
        document.addEventListener("click", outside)
        document.addEventListener("keydown", escape)
        dismiss = {
            document.removeEventListener("click", outside)
            document.removeEventListener("keydown", escape)
        }
    }
}

/** Remove every child. */
fun HTMLElement.clear() {
    innerHTML = ""
}

/** Append several children in one call, so a tree reads like a tree. */
fun HTMLElement.children(vararg nodes: HTMLElement): HTMLElement {
    nodes.forEach { appendChild(it) }
    return this
}

/**
 * Show or hide, by `display`.
 *
 * `display` rather than `visibility`: these are whole controls that should take
 * no space when absent, unlike the sign-in row, which reserves its height so the
 * card does not jump when the session resolves.
 */
fun HTMLElement.visible(show: Boolean, displayValue: String = "block") {
    style.display = if (show) displayValue else "none"
}

/**
 * Set text only when it has changed.
 *
 * Assigning `textContent` unconditionally destroys and rebuilds the text node,
 * which drops a selection the user is in the middle of making. The views
 * re-render on every state emission, so this is not a micro-optimisation — it is
 * the difference between being able to select an issue's text and not.
 */
fun HTMLElement.setTextIfChanged(value: String) {
    if (textContent != value) textContent = value
}

/**
 * Set an input's value only when it has changed.
 *
 * Same reason, more sharply: assigning `value` moves the caret to the end. A
 * re-render on every keystroke would make it impossible to edit the middle of a
 * word — you would type one character and be thrown to the end of the field.
 */
fun HTMLInputElement.setValueIfChanged(next: String) {
    if (value != next) value = next
}

/** As above, for a textarea. */
fun HTMLTextAreaElement.setValueIfChanged(next: String) {
    if (value != next) value = next
}
