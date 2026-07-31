/**
 * "Add people…" — a search over the accounts this instance already has (LNL-204).
 *
 * ── What this replaced, and why ──────────────────────────────────────────────
 *
 * A modal with two fields: an address and a rung. It worked, and it asked the wrong
 * question. The Access section's person list is *exceptions only*, so somebody who is not
 * an exception yet has no row to click — and the only route to them was to type their
 * address from memory. For an account the instance is already holding that is absurd, and
 * it is worse than merely tedious: a typo does not fail, it silently creates a second,
 * never-signed-into account beside the real person, holding the rung the real person was
 * supposed to get.
 *
 * So the gesture is a directory search, and typing a whole address is the **fallback** for
 * somebody who genuinely has no account here — a secondary row beneath the results, never
 * competing with them.
 *
 * ── A panel, not a modal ─────────────────────────────────────────────────────
 *
 * It opens in place, under the person list it adds to. That is not decoration: every pick
 * is written immediately and lands in the list above, so the list has to stay visible for
 * the panel to be showing you what it did. A modal would have covered its own feedback.
 *
 * ── Everything unpickable is still shown ─────────────────────────────────────
 *
 * Somebody already on the board, and the instance's owner and administrators, arrive with
 * [PersonCandidate.inertReason] set and are drawn dimmed and struck, not filtered out. A
 * search that silently omits the person you searched for reads as a broken search — the
 * same rule the rung pickers on this screen follow, and the reason the toolkit's
 * `dt-disabled` marker is reused here rather than a bespoke "faded row".
 *
 * ── What it decides: nothing ─────────────────────────────────────────────────
 *
 * Which rungs may be handed out, who is inert and why, whether a new address is refused
 * and in what words — all of it is the server's, carried in [ProjectAccessState] and
 * [PersonCandidate]. This file draws rows and reports gestures. The one judgement it makes
 * is *when* to offer the new-address row, and even that is
 * [se.soderbjorn.lunicle.client.viewmodel.PeoplePicker.isWholeAddress]'s answer.
 *
 * @see se.soderbjorn.lunicle.client.viewmodel.PeoplePicker
 * @see ProjectSections for where this is mounted
 */
package se.soderbjorn.lunicle

import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import se.soderbjorn.lunicle.client.viewmodel.PeoplePicker
import se.soderbjorn.lunicle.clientserver.PersonCandidate
import se.soderbjorn.lunicle.clientserver.ProjectAccessState

/**
 * @param projectName named in the panel's own title, because the panel sits inside a pane
 *   that can be scrolled away from its rail — "Add people" alone leaves you checking which
 *   board you are on.
 * @param onQueryChanged the search field was typed into.
 * @param onRoleChanged the rung every subsequent pick will grant.
 * @param onPicked a directory row was chosen — the account's id, never its address.
 * @param onNewAddressTaken the "no account here yet" row was taken, or Enter was pressed on
 *   a whole address. The panel does not pass the address: the view model already holds the
 *   query, and handing it back would make two copies of "what was typed" that could differ.
 * @param onUndo take back every grant this session made.
 * @param onClose done adding.
 */
class AddPeoplePanel(
    private val projectName: String,
    private val onQueryChanged: (String) -> Unit,
    private val onRoleChanged: (String?) -> Unit,
    private val onPicked: (Long) -> Unit,
    private val onNewAddressTaken: () -> Unit,
    private val onUndo: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val root = element("div", "add-people-panel")

    /**
     * The search field, built once and never rebuilt.
     *
     * Load-bearing: it holds focus and a caret. Rebuilding it on a state tick would take
     * the caret away mid-word on every keystroke, which is the classic way a live-filtered
     * list becomes untypeable. Everything that *does* change on a tick is below it.
     */
    private val searchField: HTMLInputElement = textField("Search by name or address") {
        onQueryChanged(it)
    }

    private val title = element("div", "add-people-title")
    private val subtitle = element("p", "field-hint")
    private val roleHost = element("div", "add-people-role")
    private val ceilingNote = element("span", "add-people-ceiling")
    private val rows = element("div", "add-people-rows")
    private val footerNote = element("span", "add-people-footer-note")
    private val undoButton = button("Undo", "btn btn-quiet btn-small") { onUndo() }
    private val doneButton = button("Done", "btn btn-primary btn-small") { onClose() }

    /**
     * The role picker, rebuilt only when the rungs or the selection actually move.
     *
     * Its own signature for [rows]' reason and one more: this is a [Dropdown], and
     * replacing one while its menu is open shuts the menu in the reader's face.
     */
    private var roleSignature: String? = null
    private var rowsSignature: String? = null

    val element: HTMLElement get() = root

    fun mount(): HTMLElement {
        // Enter on a whole address takes the new-person row without reaching for the mouse.
        // Nothing else in the panel responds to Enter: there is no "submit", because every
        // pick has already been written.
        searchField.type = "text"
        searchField.onkeydown = { event ->
            if (event.key == "Enter") {
                event.preventDefault()
                onNewAddressTaken()
            }
            Unit
        }
        root.children(
            element("div", "add-people-head").children(
                element("div", "add-people-head-text").children(title, subtitle),
                element("div", "add-people-head-role").children(roleHost, ceilingNote),
            ),
            element("div", "add-people-search").children(searchField),
            rows,
            element("div", "add-people-footer").children(footerNote, undoButton, doneButton),
        )
        return root
    }

    /** Focus the field, so the panel can simply be typed at the moment it opens. */
    fun focus() = searchField.focus()

    fun render(picker: PeoplePicker, access: ProjectAccessState, isBusy: Boolean) {
        title.setTextIfChanged("Add people to $projectName")
        subtitle.setTextIfChanged(
            "Everyone with an account here. Pick as many as you like — each is added straight away.",
        )
        // The field is the reader's, not the state's: it is written to only when the view
        // model has actually changed the text under them (clearing it after a successful
        // add), never on every tick, or typing would fight the render.
        if (searchField.value != picker.query) searchField.value = picker.query

        renderRole(picker, access, isBusy)
        renderRows(picker, access, isBusy)
        renderFooter(picker, access)
    }

    /** The one rung chosen at the top, which every pick then grants. */
    private fun renderRole(picker: PeoplePicker, access: ProjectAccessState, isBusy: Boolean) {
        val signature = "${picker.roleKey}|$isBusy|" +
            access.rungs.joinToString(",") { "${it.key}/${it.isSelectable}" }
        if (signature == roleSignature) {
            return
        }
        roleSignature = signature
        roleHost.clear()
        // The same rungPicker the audience and person rows use, so a rung this caller may
        // not hand out is struck through with its reason here too rather than being
        // described differently by a third control.
        roleHost.appendChild(
            rungPicker(
                rungs = access.rungs,
                selectedKey = picker.roleKey,
                isEnabled = !isBusy,
                onPick = onRoleChanged,
            ),
        )
        // The ceiling said once, beside the control, rather than repeated inside the menu —
        // the rule LNL-202 settled for the audience rows, applied to the same question here.
        val top = access.rungs.lastOrNull { it.isSelectable }
        val everything = access.rungs.isNotEmpty() && access.rungs.all { it.isSelectable }
        ceilingNote.setTextIfChanged(
            when {
                top == null -> ""
                everything -> "you may grant any role"
                else -> "up to ${top.label}"
            },
        )
        ceilingNote.visible(ceilingNote.textContent?.isNotEmpty() == true)
    }

    /**
     * The result rows, plus whichever single row follows them: the new-address offer, the
     * refusal, or the empty note. Never more than one of the three — they are three answers
     * to the same question.
     */
    private fun renderRows(picker: PeoplePicker, access: ProjectAccessState, isBusy: Boolean) {
        val trailer = trailerKind(picker, access)
        val signature = buildString {
            append(isBusy).append('|').append(picker.roleKey).append('|')
            append(trailer).append('|').append(picker.query.trim()).append('|')
            picker.candidates.forEach { c ->
                append(c.userId).append(':').append(c.inertReason).append(':')
                    .append(c.heldRoleLabel).append(':').append(c.hasSignedIn).append('|')
            }
        }
        if (signature == rowsSignature) {
            return
        }
        rowsSignature = signature
        rows.clear()
        picker.candidates.forEach { rows.appendChild(candidateRow(it, picker, access, isBusy)) }
        when (trailer) {
            Trailer.OFFER -> rows.appendChild(offerRow(picker, access))
            Trailer.REFUSED -> rows.appendChild(refusedRow(picker, access))
            Trailer.EMPTY -> rows.appendChild(
                element(
                    "p",
                    "add-people-empty",
                    "Nobody here matches that. Type a whole address to add somebody who has " +
                        "not signed in yet.",
                ),
            )
            Trailer.NONE -> Unit
        }
    }

    /** Which of the three mutually exclusive trailing rows belongs under the results. */
    private fun trailerKind(picker: PeoplePicker, access: ProjectAccessState): Trailer {
        val typed = picker.query.trim()
        // Already an account here: the directory row above is the answer, and offering to
        // "add" the address a second time would be offering a duplicate of a row on screen.
        val isKnownAddress = picker.candidates.any { it.email.equals(typed, ignoreCase = true) }
        return when {
            !picker.isWholeAddress ->
                if (picker.candidates.isEmpty() && typed.isNotEmpty()) Trailer.EMPTY else Trailer.NONE
            isKnownAddress -> Trailer.NONE
            // The refusal outranks the offer: a deployment that will not accept an account
            // for this address must not first be shown offering to create one.
            //
            // Asked OF THE TYPED ADDRESS, which is the whole of it. `newAddressRefusal`
            // alone is the deployment's standing sentence about an address off its domain,
            // and reading it as "this address is refused" refused every new address on a
            // domain-restricted instance — including the on-domain ones it exists to admit.
            access.newAddressRefusalFor(typed) != null -> Trailer.REFUSED
            else -> Trailer.OFFER
        }
    }

    private fun candidateRow(
        candidate: PersonCandidate,
        picker: PeoplePicker,
        access: ProjectAccessState,
        isBusy: Boolean,
    ): HTMLElement {
        val inert = candidate.inertReason != null
        // No rung chosen means nothing can be granted, so every row is inert — which is the
        // state on a board where this caller may hand out nothing at all.
        val pickable = !inert && picker.roleKey != null && !isBusy
        val justAdded = candidate.userId in picker.addedUserIds
        val row = element(
            "div",
            "add-people-row" +
                (if (inert) " dt-disabled add-people-row-inert" else "") +
                (if (justAdded) " add-people-row-added" else ""),
        )
        if (inert) row.setAttribute("aria-disabled", "true")

        // An initial, not a photograph: there are no avatars in this product, and a letter
        // in a disc is enough to make a long list scannable by shape.
        val initial = candidate.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val avatar = element("span", "add-people-avatar", initial)

        val nameLine = element("div", "add-people-name-line").children(
            element("span", "add-people-name", candidate.name),
        )
        if (candidate.badge.isNotEmpty()) {
            nameLine.appendChild(element("span", "add-people-badge", candidate.badge))
        }
        // The same badge the person list uses, for the same reason: a rung nobody has
        // collected looks exactly like one that has been.
        if (!candidate.hasSignedIn) {
            nameLine.appendChild(element("span", "add-people-badge add-people-badge-pending", "NOT SIGNED IN"))
        }

        // "Added as Viewer" beats a bare "Added": four picks at three different rungs is a
        // perfectly ordinary thing to do here, and the row is the only place that says which
        // one this person got. Labelled from the rungs the server sent, never from the key.
        val grantedLabel = access.rungs.firstOrNull { it.key == picker.roleKey }?.label
        val state = when {
            justAdded && grantedLabel != null -> "Added as $grantedLabel"
            justAdded -> "Added"
            candidate.inertReason != null -> candidate.inertReason
            else -> ""
        }
        row.children(
            avatar,
            element("div", "add-people-row-text").children(
                nameLine,
                element("div", "add-people-email", candidate.email),
            ),
            element(
                "span",
                "add-people-row-state" + if (justAdded) " add-people-row-state-added" else "",
                state,
            ),
        )
        if (pickable) {
            row.onclick = { onPicked(candidate.userId); Unit }
        }
        return row
    }

    /** "Add nadia@vessel.studio — no account here yet." */
    private fun offerRow(picker: PeoplePicker, access: ProjectAccessState): HTMLElement {
        val typed = picker.query.trim()
        val row = element("div", "add-people-row add-people-row-offer")
        row.children(
            element("span", "add-people-avatar add-people-avatar-new", "+"),
            element("div", "add-people-row-text").children(
                element("div", "add-people-offer-title", "Add $typed — no account here yet"),
                // The deployment's own sentence about what adding does and does not do,
                // written server-side because it depends on whether mail is configured.
                element("div", "add-people-offer-note", access.addressAdvice),
            ),
        )
        row.onclick = { onNewAddressTaken(); Unit }
        return row
    }

    /**
     * "nadia@vessel.studio cannot be added", and why.
     *
     * Drawn instead of the offer, never beside it. The wording is the server's and
     * deliberately does not point at the setting that caused it: whoever is reading a
     * project's Access section may well not be an instance administrator, and advice they
     * cannot act on is worse than none.
     */
    private fun refusedRow(picker: PeoplePicker, access: ProjectAccessState): HTMLElement {
        val typed = picker.query.trim()
        return element("div", "add-people-row add-people-row-refused").children(
            element("span", "add-people-avatar add-people-avatar-refused", "!"),
            element("div", "add-people-row-text").children(
                element("div", "add-people-refused-title", "$typed cannot be added"),
                element("div", "add-people-refused-note", access.newAddressRefusalFor(typed).orEmpty()),
            ),
        )
    }

    /**
     * The footer: what happened, an undo for it, and the way out.
     *
     * Done goes quiet ("Close") until something has actually been added, because a solid
     * primary button on a panel that has done nothing invites a press that means nothing.
     */
    private fun renderFooter(picker: PeoplePicker, access: ProjectAccessState) {
        val added = picker.addedUserIds.size
        val hidden = picker.totalMatches - picker.candidates.size
        footerNote.setTextIfChanged(
            when {
                // The trailer's own answer, not a second reading of the refusal beside it:
                // the two disagreed, and the footer was the half that said "nothing will be
                // added" under a perfectly addable address.
                trailerKind(picker, access) == Trailer.REFUSED ->
                    "Nothing will be added for that address."
                added == 1 -> "1 person added — adjust any role in the list above."
                added > 1 -> "$added people added — adjust any role in the list above."
                hidden > 0 -> "$hidden more match — keep typing to narrow it."
                else -> "Roles are editable afterwards in the list above."
            },
        )
        undoButton.textContent = if (added == 1) "Undo this" else "Undo all $added"
        undoButton.visible(added > 0, displayValue = "inline-flex")
        doneButton.textContent = if (added > 0) "Done" else "Close"
        // btn-primary is the solid one; dropping it leaves the quiet outline.
        doneButton.className = if (added > 0) "btn btn-primary btn-small" else "btn btn-quiet btn-small"
    }

    /** The single row that may follow the results. See [trailerKind]. */
    private enum class Trailer { NONE, OFFER, REFUSED, EMPTY }
}
