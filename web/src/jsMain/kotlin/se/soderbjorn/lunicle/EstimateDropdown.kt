/**
 * The estimate control (LNL-215): "how much work is this?", as a popover.
 *
 * ── Why a popover and not a modal ────────────────────────────────────────────
 *
 * Built on the same `.dt-hover-menu` chrome as [VersionDropdown], and deliberately
 * one of that family rather than a [Modal]. An estimate is one of the seven small
 * facts sitting in the issue editor's field row — beside the status, the sprint and
 * the two versions — and every one of those is answered by opening something under
 * the field and closing it again. A dialog would darken the page and take focus for
 * a number, and it would be the only field in that row that did. The trade is that a
 * popover has no OK/Cancel footer to lean on, so the commit rules have to be stated
 * by the control itself: Enter and the Set button commit, Escape and an outside click
 * abandon. That is the same contract [VersionDropdown]'s inline rename keeps, for the
 * same reason — inside a popover, "click away" already means cancel, so nothing here
 * may commit on blur.
 *
 * The chevron and the closed control are the toolkit's [buildMenuTrigger], never
 * hand-rolled (LNL-168): a field that drew its own arrow would be the one control in
 * the row that did not follow the theme's chrome.
 *
 * ── What it offers depends on the project, and `none` offers nothing ─────────
 *
 * [EstimateMode] decides:
 *
 *  - **TIME** — three fields, days/hours/minutes, stored as whole minutes.
 *  - **POINTS** — one field, stored as whole points.
 *  - **NONE** — nothing at all. The caller does not render the cell, and this control
 *    refuses to open. That is load-bearing rather than tidy: the overwhelming majority
 *    of projects will never configure estimates, and their issue window has to be the
 *    window it was before this feature landed — not a greyed field with an explanation
 *    beside it. See [EstimateMode.NONE], which says the same thing from the wire's side.
 *
 * ── One day is eight hours, and that is not configurable ─────────────────────
 *
 * [DAY_MINUTES] is the whole of the arithmetic here. Jira makes it a setting; nobody
 * has ever changed it, and a knob would only let two boards disagree about what `2d`
 * means while reading the same number out of the same column. The constant is written
 * down in Issues.sq as well, on the storage side of the same decision.
 *
 * @see VersionDropdown for the popover shape this follows
 * @see formatEstimate for the reading side, which the read face shares
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunicle.clientserver.Estimate
import se.soderbjorn.lunicle.clientserver.EstimateMode
import se.soderbjorn.lunicle.clientserver.EstimateUnit
import se.soderbjorn.lunula.web.shell.buildMenuTrigger
import se.soderbjorn.lunula.web.shell.setMenuTriggerLabel
import se.soderbjorn.lunula.web.shell.setMenuTriggerOpen

/**
 * Minutes in one working day. Fixed at eight hours; see this file's preamble for why
 * it is a constant and not a project setting.
 */
const val DAY_MINUTES: Long = 8 * 60

/**
 * An estimate as a person reads it: `2d 4h`, `1h 30m`, `3 points` — or null because
 * there is none.
 *
 * **Normalised, always.** Ninety minutes reads `1h 30m` and never `90m`; twenty hours
 * reads `2d 4h` and never `20h`. The alternative — printing back whatever number was
 * typed — would let two issues estimated identically read differently forever, purely
 * because of which box somebody happened to type into. Zero-valued parts are dropped
 * (`2d`, not `2d 0h 0m`), which is what keeps the common case short.
 *
 * Null in, null out, and null means **render nothing**: no em dash, no "unestimated".
 * An issue nobody has sized is the ordinary case, and a row that appeared on every one
 * of them to say so would be noise on the majority to serve the minority — the same
 * call [IssueWindow.renderVersions] made about an unset planned version.
 *
 * A top-level function rather than a method on the control, because the *read* face
 * needs it too and has no control to ask. A property on [Estimate] would be nicer
 * still, but that type is on the wire in `clientServer`, where a rendering decision
 * about hyphens and pluralisation has no business living.
 */
fun formatEstimate(estimate: Estimate?): String? {
    val value = estimate ?: return null
    if (value.amount <= 0) return null
    return when (value.unit) {
        EstimateUnit.POINTS -> if (value.amount == 1L) "1 point" else "${value.amount} points"
        EstimateUnit.MINUTES -> {
            val days = value.amount / DAY_MINUTES
            val hours = (value.amount % DAY_MINUTES) / 60
            val minutes = value.amount % 60
            // Built as a list and joined, so the "drop the zeroes" rule is stated once
            // rather than as three nested ifs that each have to remember the separator.
            listOfNotNull(
                days.takeIf { it > 0 }?.let { "${it}d" },
                hours.takeIf { it > 0 }?.let { "${it}h" },
                minutes.takeIf { it > 0 }?.let { "${it}m" },
            ).joinToString(" ")
        }
    }
}

/**
 * Read one typed field as a number of minutes.
 *
 * Two shapes are accepted, and the second is the point:
 *
 *  - **A bare number** — `4` in the hours box is four hours. This is what the three
 *    boxes are for and what nearly everybody types.
 *  - **An expression** — `2d 4h`, `90m`, `1d30m`. Somebody arriving from Jira has that
 *    string in their head and in their clipboard, and making them break it into three
 *    boxes is friction for no gain. Whichever box it is typed into, the units in the
 *    text win outright: `90m` in the hours box is ninety minutes, not ninety hours.
 *    Mixing the two is therefore safe — a field either names its own units or inherits
 *    the box's, never half of each.
 *
 * @param unitMinutes what a bare number in this box counts — 480 for the days box, 60
 *   for hours, 1 for minutes.
 * @return the minutes this field contributes, or null because it says nothing this
 *   function can read. Null and zero are deliberately the same to the caller (both add
 *   nothing); the distinction that matters — "the whole popover is empty" — is decided
 *   over all the fields at once, in [EstimateDropdown.commit].
 */
internal fun parseTimeField(text: String, unitMinutes: Long): Long? {
    val clean = text.trim().lowercase()
    if (clean.isEmpty()) return null
    clean.toLongOrNull()?.let { return it * unitMinutes }
    // An expression: every number-then-unit pair in the string, summed. Anything that
    // is not one of those pairs is ignored rather than refused — a stray comma or the
    // word "about" in a pasted string should not silently zero somebody's estimate,
    // and there is no error surface on a popover to refuse it into.
    var total = 0L
    var matched = false
    EXPRESSION.findAll(clean).forEach { match ->
        val amount = match.groupValues[1].toLongOrNull() ?: return@forEach
        val multiplier = when (match.groupValues[2]) {
            "d" -> DAY_MINUTES
            "h" -> 60L
            else -> 1L
        }
        total += amount * multiplier
        matched = true
    }
    return if (matched) total else null
}

/** `2d`, `4h`, `30m` — a number and the letter that says what it counts. */
private val EXPRESSION = Regex("""(\d+)\s*([dhm])""")

/** A bare whole number, for the points field. Anything else is nothing. */
internal fun parsePoints(text: String): Long? = text.trim().toLongOrNull()?.takeIf { it >= 0 }

/**
 * The control.
 *
 * @param isField true for the editor's field row — the 34px filled variant every other
 *   cell in that row uses.
 * @param onChange the estimate the user committed, or null because they cleared it.
 *   Fires only on a commit; opening and closing the popover reports nothing, which is
 *   what makes Escape mean Escape.
 */
class EstimateDropdown(
    isField: Boolean = false,
    className: String = "",
    private val onChange: (Estimate?) -> Unit,
) {
    /** The closed control — the toolkit's trigger, as [VersionDropdown]'s is. */
    val element: HTMLButtonElement =
        buildMenuTrigger(isField = isField, extraClass = "dropdown estimate-dropdown $className".trim())

    /**
     * What the project offers. A var rather than a constructor constant for
     * [VersionDropdown.canManage]'s reason: the issue window learns the project's mode
     * only once the board loads, and sets it on every render.
     */
    private var mode: EstimateMode = EstimateMode.NONE
    private var estimate: Estimate? = null

    private var menu: HTMLElement? = null
    private var dismiss: (() -> Unit)? = null

    /** The live fields while the popover is up, so [commit] can read them back. */
    private var dayField: HTMLInputElement? = null
    private var hourField: HTMLInputElement? = null
    private var minuteField: HTMLInputElement? = null
    private var pointField: HTMLInputElement? = null

    init {
        element.onclick = { if (menu != null) close() else open(); Unit }
    }

    /**
     * Render the closed control.
     *
     * The label is the formatted estimate, or "None" dim — [VersionDropdown.render]'s
     * exact convention, and for its reason: a field that merely looked empty would be
     * indistinguishable from one that failed to load, but in a row of seven fields the
     * useful glance is which ones are still unanswered.
     */
    fun render(mode: EstimateMode, estimate: Estimate?) {
        this.mode = mode
        this.estimate = estimate
        val label = formatEstimate(estimate)
        setMenuTriggerLabel(element, text = label ?: "None", isUnset = label == null)
        // A mode that offers nothing cannot have a popover open over it. The caller
        // hides the whole cell, so this is belt and braces — but a control that could
        // be opened into an empty box by a stale click is a control that has an empty
        // box, and the promise of this feature is that `none` has no surface at all.
        if (mode == EstimateMode.NONE) close()
    }

    /** Tear the popover down, listeners included. A no-op when already closed. */
    fun close() {
        menu?.remove()
        menu = null
        dayField = null
        hourField = null
        minuteField = null
        pointField = null
        setMenuTriggerOpen(element, false)
        dismiss?.invoke()
        dismiss = null
    }

    private fun open() {
        if (mode == EstimateMode.NONE) return
        val box = element("div", "$MENU_CLASS dt-hover-menu estimate-menu")
        box.setAttribute("role", "dialog")

        val first = when (mode) {
            EstimateMode.TIME -> buildTimeFields(box)
            EstimateMode.POINTS -> buildPointsField(box)
            EstimateMode.NONE -> null
        }
        box.appendChild(buildActions())

        document.body?.appendChild(box)
        anchorUnder(box)
        menu = box
        setMenuTriggerOpen(element, true)
        installDismissal(box)
        // Last, after anchorUnder — which can flip the whole box above the control.
        // Focusing before that scrolls the page to a position the field is about to
        // leave; [VersionDropdown.open] makes the same note about its rename field.
        first?.let { it.focus(); it.select() }
    }

    /**
     * Days, hours and minutes, pre-filled from the current estimate.
     *
     * Three boxes rather than one, because three is how the number is *thought* about
     * — "two days and a bit" — and one combined box would make the common case a
     * spelling exercise. The typed expression ([parseTimeField]) is the escape hatch
     * for the people who think in the string instead, and the hint under the row is
     * what tells them it is there; it is one line and it is the only documentation this
     * control can carry.
     *
     * @return the field to focus.
     */
    private fun buildTimeFields(box: HTMLElement): HTMLInputElement {
        val minutes = estimate?.takeIf { it.unit == EstimateUnit.MINUTES }?.amount ?: 0
        val days = numberField("Days", "d", (minutes / DAY_MINUTES).takeIf { it > 0 }?.toString().orEmpty())
        val hours = numberField("Hours", "h", ((minutes % DAY_MINUTES) / 60).takeIf { it > 0 }?.toString().orEmpty())
        val mins = numberField("Minutes", "m", (minutes % 60).takeIf { it > 0 }?.toString().orEmpty())
        dayField = days
        hourField = hours
        minuteField = mins

        val row = element("div", "estimate-fields")
        row.children(labelled("Days", days), labelled("Hours", hours), labelled("Minutes", mins))
        box.children(row, element("p", "estimate-hint", "…or type 2d 4h into any box."))
        return days
    }

    /** One box, and the word beside it. Points have no sub-units and need no hint. */
    private fun buildPointsField(box: HTMLElement): HTMLInputElement {
        val points = estimate?.takeIf { it.unit == EstimateUnit.POINTS }?.amount
        val field = numberField("Points", "3", points?.toString().orEmpty())
        pointField = field
        val row = element("div", "estimate-fields")
        row.children(labelled("Points", field))
        box.appendChild(row)
        return field
    }

    /**
     * One committing box.
     *
     * `type="text"` with `inputmode="numeric"`, not `type="number"`: a number input
     * would refuse the `2d 4h` this control goes out of its way to accept, and its
     * spinner arrows are furniture in a box three characters wide. The inputmode is
     * what still brings a phone's number pad up.
     *
     * @param description what a screen reader hears. The captions above the boxes are
     *   `<span>`s inside a `<label>`, which does associate them — but only the time
     *   fields have captions, so stating it here is what names every box the same way
     *   whichever mode built it.
     * @param placeholder the unit's letter in time mode (`d`, `h`, `m`) and an example
     *   number in points mode. Not the same kind of hint, on purpose: three identical
     *   empty boxes need to say which is which even with the captions right above them,
     *   where a single box does not — what a lone points box needs is a sense of the
     *   SCALE, since "3" and "300" are both things teams mean by a point.
     */
    private fun numberField(description: String, placeholder: String, value: String): HTMLInputElement {
        val field = document.createElement("input") as HTMLInputElement
        field.type = "text"
        field.className = "field estimate-field"
        field.setAttribute("inputmode", "numeric")
        field.setAttribute("aria-label", description)
        field.placeholder = placeholder
        field.value = value
        field.onkeydown = { event ->
            when (event.key) {
                "Enter" -> commit()
                "Escape" -> close()
                else -> Unit
            }
        }
        return field
    }

    /** A caption over a box, so three identical boxes are told apart by more than order. */
    private fun labelled(caption: String, field: HTMLInputElement): HTMLElement {
        val cell = element("label", "estimate-cell")
        cell.children(element("span", "estimate-caption", caption), field)
        return cell
    }

    /**
     * Set and Clear.
     *
     * Clear is its own button rather than "empty every box and press Set", even though
     * the two do the same thing: clearing an estimate is a decision somebody makes on
     * purpose, and making them delete three numbers to express it is the interaction
     * equivalent of not offering it. It is shown whether or not there is an estimate to
     * clear — a button that came and went as the value changed would move Set sideways
     * under the pointer.
     */
    private fun buildActions(): HTMLElement {
        val row = element("div", "estimate-actions")
        row.children(
            button("Clear", "btn btn-quiet btn-small") { close(); onChange(null) },
            button("Set", "btn btn-primary btn-small") { commit() },
        )
        return row
    }

    /**
     * Read the boxes, close, and report.
     *
     * An empty popover commits **null** rather than zero, and the two are genuinely
     * different: `0m` is an estimate somebody made, and no estimate is the absence of
     * one. Since the boxes cannot express `0m` distinctly from empty, a total of zero
     * is read as "cleared" — which matches what somebody who emptied every box meant,
     * and is also what [formatEstimate] would have rendered as nothing anyway.
     */
    private fun commit() {
        val next = when (mode) {
            EstimateMode.NONE -> null
            EstimateMode.POINTS -> parsePoints(pointField?.value.orEmpty())
                ?.takeIf { it > 0 }
                ?.let { Estimate(amount = it, unit = EstimateUnit.POINTS) }
            EstimateMode.TIME -> {
                val total = listOfNotNull(
                    parseTimeField(dayField?.value.orEmpty(), DAY_MINUTES),
                    parseTimeField(hourField?.value.orEmpty(), 60),
                    parseTimeField(minuteField?.value.orEmpty(), 1),
                ).sum()
                total.takeIf { it > 0 }?.let { Estimate(amount = it, unit = EstimateUnit.MINUTES) }
            }
        }
        close()
        onChange(next)
    }

    /** Under the control's left edge, clamped to the viewport — [VersionDropdown]'s math. */
    private fun anchorUnder(box: HTMLElement) {
        val anchor = element.getBoundingClientRect()
        box.style.minWidth = "${anchor.width}px"
        val laid = box.getBoundingClientRect()
        val left = anchor.left.coerceAtMost(window.innerWidth - laid.width - 4.0).coerceAtLeast(4.0)
        box.style.left = "${left}px"
        box.style.top = "${anchorTop(anchor, laid.height)}px"
    }

    /**
     * Closes on Escape or an outside click — [VersionDropdown.installDismissal], verbatim
     * in intent, and with the same consequence: both routes out abandon what was typed.
     * A popover that saved on an outside click would make "click away to cancel" and
     * "click away to save" the identical gesture.
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

    private companion object {
        /** The same marker every popover here uses; see Dom.kt's MENU_CLASS for the prefix rule. */
        const val MENU_CLASS = "lunicle-dropdown-menu"
    }
}
