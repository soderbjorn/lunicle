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

/** Create a checkbox. */
fun checkbox(onChange: (Boolean) -> Unit): HTMLInputElement {
    val el = document.createElement("input") as HTMLInputElement
    el.type = "checkbox"
    el.className = "checkbox"
    el.onchange = { onChange(el.checked); Unit }
    return el
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
