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
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement

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

/** Create a select. */
fun select(onChange: (String) -> Unit): HTMLSelectElement {
    val el = document.createElement("select") as HTMLSelectElement
    el.className = "field"
    el.onchange = { onChange(el.value); Unit }
    return el
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
