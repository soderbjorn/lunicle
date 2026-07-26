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
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * Whether the app is running inside a frame (embedded on another site) rather
 * than as a standalone top-level tab.
 *
 * The reference identity check is deliberately the whole of it: reading almost
 * anything else off `window.top` throws across origins, but comparing the two
 * references never does.
 *
 * Sign-in is not what this gates — that works in the frame too. The embed is a
 * panel inside somebody else's page, boxed to its slot and without its own
 * address bar, so it carries a way out to the same view on the full site; see
 * main.kt's `fullSiteLink`.
 */
fun isEmbedded(): Boolean = window.top !== window.self

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
 * An On/Off pair of buttons — lunula's boolean control, and now Lunicle's.
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

/**
 * The watch pill: an eye and a word, standing in for a labelled toggle row.
 *
 * Two places want exactly this control — an issue ("tell me about updates") and
 * a project ("tell me about new issues") — and both used to spend a full row on
 * a sentence and a switch (LNL-46). A pill says the same thing in a corner,
 * because the sentence was never the point: the state is, and the state is one
 * bit.
 *
 * **The label carries the state, not just the styling.** "Watching" is what it
 * IS; "Watch" is what pressing it DOES. That flip is deliberate and is the whole
 * reason this reads without a caption — a button whose label never changed would
 * need one to say which way round it is. The border follows: strong when
 * watching, quiet when not, so the on state is legible from the far side of the
 * pane without reading anything.
 *
 * Returned as a [WatchButton] rather than a bare element because the caller has
 * to re-render it on every state emission, and the two halves that change (the
 * word and the class) must change together.
 */
class WatchButton(onToggle: (Boolean) -> Unit) {
    private var isWatching: Boolean = false
    private val label: HTMLElement = element("span", "watch-label", WATCH_OFF)

    val element: HTMLButtonElement =
        (document.createElement("button") as HTMLButtonElement).also { el ->
            el.className = "watch-btn"
            el.type = "button"
            el.appendChild(eyeIcon())
            el.appendChild(label)
            el.onclick = { onToggle(!isWatching) }
        }

    /**
     * Show [watching], and whether the control is live.
     *
     * Idempotent and cheap — it is called from render, which runs on every
     * emission. `setTextIfChanged` keeps it from replacing a text node the user
     * may be mid-hover on.
     */
    fun render(watching: Boolean, isEnabled: Boolean) {
        isWatching = watching
        label.setTextIfChanged(if (watching) WATCH_ON else WATCH_OFF)
        element.classList.toggle("watch-on", watching)
        element.disabled = !isEnabled
        // The tooltip says what the press will do, which the label deliberately
        // does not once you are watching.
        element.title = if (watching) "Stop watching — no more notifications about this" else "Watch — notify me about this"
    }

    private companion object {
        const val WATCH_ON = "Watching"
        const val WATCH_OFF = "Watch"
    }
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
 * Lunicle's marker class on an open menu box — [Dropdown]'s and
 * [openContextMenu]'s alike.
 *
 * Nothing in `styles.css` styles it: the chrome is entirely `.dt-hover-menu`'s,
 * and this is here to identify the element in the inspector and to give a future
 * rule something to hang off.
 *
 * ── Why it is not called `dropdown-menu` (LNL-54) ───────────────────────────
 *
 * Because it was, and that name is the toolkit's. lunula's stylesheet defines a
 * `.dropdown-menu` of its own — an `absolute`, `right: 0` popover belonging to
 * its theme picker — and an unprefixed class name in a stylesheet we do not own
 * is not a marker, it is a subscription. What it subscribed us to was `right: 0`
 * on a box that [Dropdown.open] also gives an inline `left`, and a `fixed` box
 * with both edges pinned does not shrink-wrap: it stretches between them. Every
 * dropdown in the app opened a menu the width of the viewport — 2528px for a
 * 200px project picker — and it read as a broken layout rather than as one
 * inherited declaration, because the class looked inert in the source of both
 * files.
 *
 * The prefix is the whole fix, and it is the rule going forward: a class this
 * app puts on an element that also carries toolkit classes gets `lunicle-`, so
 * the next name the toolkit invents cannot land on us silently.
 */
private const val MENU_CLASS = "lunicle-dropdown-menu"

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
 * What opens instead is the lunula toolkit's menu: `.dt-hover-menu` chrome,
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

        val box = element("div", "$MENU_CLASS dt-hover-menu")
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

/**
 * A menu at a point, rather than under a control.
 *
 * The same rows, the same styling and the same dismissal as [Dropdown]'s menu —
 * deliberately, because it is the same *object*: a popup list of choices. What
 * differs is only where it is anchored, and that difference is the whole reason
 * this could not simply be a [Dropdown]. A dropdown hangs off a button whose
 * `getBoundingClientRect()` says where it goes; a context menu hangs off the
 * pointer, and there is no element to measure.
 *
 * Deliberately not a class. A [Dropdown] persists — it *is* the closed control,
 * and holds the selection between openings. This has no closed state to be: it
 * is created by a gesture, answers once, and is gone. A class here would be an
 * object whose only method is called immediately after its constructor.
 *
 * The caller decides whether to open one at all. That matters: suppressing the
 * browser's own context menu to show nothing is worse than not handling the
 * gesture, so `BoardWindow` only calls this when there is actually something to
 * offer, and lets the native menu through otherwise.
 *
 * @param x where the pointer was, viewport-relative.
 * @param y likewise. The menu is flipped up when it would fall off the bottom —
 *   near the foot of a long column, which is exactly where the cards are.
 */
fun openContextMenu(x: Double, y: Double, items: List<DropdownItem>, onSelect: (Long) -> Unit) {
    if (items.isEmpty()) return

    val box = element("div", "$MENU_CLASS dt-hover-menu")
    box.setAttribute("role", "menu")

    var dismiss: (() -> Unit)? = null
    // Set by close(), read by the deferred registration below. Without it, a menu
    // dismissed inside the same tick it opened — a row clicked by script, say —
    // would run close() before the listeners existed, and the timer would then
    // attach them to a box that is no longer in the document.
    var closed = false
    fun close() {
        closed = true
        box.remove()
        dismiss?.invoke()
    }

    items.forEach { item ->
        val row = element("div", "dt-hover-menu-item dt-world-row")
        row.setAttribute("role", "menuitem")
        // No check column, unlike Dropdown.row: there is no current selection to
        // mark. Every row here is a destination, and the issue is not "in" any of
        // them yet — the one it IS in is left out by the caller.
        row.children(element("span", "dt-hover-menu-label", item.label))
        row.onclick = {
            close()
            onSelect(item.id)
            Unit
        }
        box.appendChild(row)
    }

    // Mounted before measuring, as Dropdown.open does: an unmounted box has no
    // width or height to clamp against.
    document.body?.appendChild(box)
    val rect = box.getBoundingClientRect()
    val left = x.coerceAtMost(window.innerWidth - rect.width - 4.0).coerceAtLeast(4.0)
    // Flip above the pointer rather than clamping to the bottom edge. Clamping
    // would slide the menu up until its rows sit under the cursor, so the release
    // of the very right-click that opened it would land on a row.
    val top = if (y + rect.height > window.innerHeight - 4.0) {
        (y - rect.height).coerceAtLeast(4.0)
    } else {
        y
    }
    box.style.left = "${left}px"
    box.style.top = "${top}px"

    val outside: (Event) -> Unit = handler@{ event ->
        // `Element`, not `HTMLElement`. An <svg> is not an HTMLElement in
        // Kotlin/JS, so the narrower cast fails on exactly the clicks most likely
        // to be aimed elsewhere — the icon buttons in the toolbar and the top bar
        // — and the menu would sit there floating over whatever they opened.
        val target = event.target as? Element ?: return@handler
        if (box.contains(target)) return@handler
        close()
    }
    val escape: (Event) -> Unit = { event ->
        if ((event as? KeyboardEvent)?.key == "Escape") close()
    }
    // Registered on the NEXT tick, not now. This runs from inside the handler for
    // the very `contextmenu` event that opened the menu, and that event is still
    // bubbling — it reaches `document`, its target is the card rather than the
    // box, and `outside` would close the menu before it had been painted. The
    // symptom is a menu that never appears at all, which reads as a dead gesture
    // rather than as a listener bug.
    //
    // [Dropdown.installDismissal] has the same hazard and answers it differently:
    // it ignores events on the control it hangs off. That is not available here,
    // because the "control" is the card, and a card is a thing you can click for
    // its own reasons. Deferring by a tick is the version of that guard which
    // needs no anchor.
    //
    // "contextmenu" as well as "click": a second right-click elsewhere must not
    // leave two menus up, and it produces no click event for the handler above.
    window.setTimeout({
        if (!closed) {
            document.addEventListener("click", outside)
            document.addEventListener("contextmenu", outside)
            document.addEventListener("keydown", escape)
        }
    }, 0)
    dismiss = {
        document.removeEventListener("click", outside)
        document.removeEventListener("contextmenu", outside)
        document.removeEventListener("keydown", escape)
    }
}

/**
 * The same popover as [openContextMenu], but where each row carries its own
 * action instead of a shared id-keyed callback.
 *
 * [openContextMenu] keys rows on a [Long] id — the right shape when every row is
 * the same kind of thing (a status to move to, a sprint to file under) and the id
 * names which one. A column's menu is not that: "Create issue" and "Hide column"
 * are different verbs that happen to sit on the same column, so an id cannot tell
 * them apart. This reuses the popover wholesale, tagging each row with its index
 * as a throwaway id and routing the select back to that row's own lambda.
 */
fun openActionMenu(x: Double, y: Double, actions: List<Pair<String, () -> Unit>>) {
    openContextMenu(
        x = x,
        y = y,
        items = actions.mapIndexed { index, (label, _) -> DropdownItem(index.toLong(), label) },
    ) { id -> actions[id.toInt()].second() }
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
