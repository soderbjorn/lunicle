/**
 * "Add a person…" — an address and a role, and nothing is sent (LNL-194).
 *
 * ── Two fields, and the absence of a third ──────────────────────────────────
 *
 * An address and a rung. **No agent-access switch**: MCP permission is per tier on
 * the instance ladder and there is no per-person override anywhere in this design, so
 * a switch here would be a control that writes nothing. The P4 mock shows one; it is
 * wrong.
 *
 * ── Why there is no "send invitation" ───────────────────────────────────────
 *
 * Because nothing is sent. The address gets an account that has never been signed
 * into, the rung is written against it, and whoever owns the address picks it up the
 * first time they arrive. No token, no link, no expiry — so the button says **Add**
 * rather than "Invite", which would promise a message.
 *
 * The consequence is stated in the dialog rather than left to be discovered: on a
 * deployment that cannot mail a sign-in code, the only way to claim the grant is
 * Google, so adding an address that cannot reach Google is a role nobody can collect.
 * That sentence comes from the server, which is the side that knows whether a mail
 * transport is configured.
 *
 * And if the address is not on the deployment's own domain, this says so — as a fact,
 * not a refusal. Adding an outside address is exactly what the gesture is for; it is
 * also exactly what the instance's admission policy may go on to reject at the door,
 * which is a different screen's decision.
 *
 * @see ProjectSections for where this is raised from
 * @see se.soderbjorn.lunicle.clientserver.PersonAdd
 */
package se.soderbjorn.lunicle

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import se.soderbjorn.lunicle.clientserver.RungOption

/**
 * @param rungs every rung, with the ones this caller may not hand out already greyed
 *   and worded by the server. Rendered as-is: the dialog decides nothing about who may
 *   grant what.
 * @param advice the deployment-specific sentence about what adding somebody does and
 *   does not do. Server-written; see [ProjectAccessState.addressAdvice].
 * @param staffDomain the deployment's own domain, or null when it has none — in which
 *   case there is no "not on the domain" note to make.
 * @param onAdd the address as typed and the chosen rung. Validation is the server's:
 *   this only refuses to fire on an empty field, because a request needs something to
 *   put in it.
 */
class AddPersonDialog(
    private val rungs: List<RungOption>,
    private val advice: String,
    private val staffDomain: String?,
    private val onAdd: (email: String, roleKey: String) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val modal = Modal("Add a person", onDismiss = onCancel)

    private lateinit var emailField: HTMLInputElement
    private lateinit var domainNote: HTMLElement
    private lateinit var addButton: HTMLButtonElement

    /**
     * The rung chosen, or null until one is.
     *
     * Starts null rather than at the lowest rung, so the dialog cannot add somebody as a
     * Viewer because nobody touched the menu. Adding a person with no role would be a
     * way to fill the account table by accident, which is why the server requires one
     * too.
     */
    private var chosenKey: String? = null

    fun mount(host: HTMLElement) {
        emailField = textField("somebody@example.com") { onEmailTyped(it) }
        emailField.type = "email"
        domainNote = element("p", "field-hint")

        val picker = Dropdown(isField = true) { id ->
            val rung = rungs.getOrNull(id.toInt()) ?: return@Dropdown
            // Greyed rungs are in the menu so they can say why — see ProjectSections'
            // rungPicker — so a click on one has to do nothing rather than choose it.
            if (!rung.isSelectable) return@Dropdown
            chosenKey = rung.key
            renderPicker(it = null)
            updateArmed()
        }
        this.picker = picker
        renderPicker(it = null)

        modal.body.children(
            element("p", "dt-modal-message modal-message", advice),
            element("label", "field-label", "E-mail address"),
            emailField,
            domainNote,
            element("label", "field-label", "Role in this project"),
            picker.element,
            element(
                "p",
                "field-hint",
                "Their effective role is the best of this and whatever their audience already " +
                    "gets, never the worst.",
            ),
        )

        addButton = button("Add", "btn btn-primary") {
            val key = chosenKey ?: return@button
            onAdd(emailField.value.trim(), key)
        }
        modal.footer.children(
            button("Cancel", "btn btn-quiet") { onCancel() },
            addButton,
        )
        updateArmed()
        modal.mount(host)
        emailField.focus()
    }

    fun dismiss() = modal.dismiss()

    private var picker: Dropdown? = null

    /**
     * Draw the closed control from [chosenKey].
     *
     * The unused parameter keeps this callable from inside the [Dropdown]'s own
     * `onSelect`, where naming the outer `picker` field would read as a forward
     * reference. It is a `renderPicker()` in every other respect.
     */
    private fun renderPicker(@Suppress("UNUSED_PARAMETER") it: Unit?) {
        val items = rungs.mapIndexed { index, rung ->
            DropdownItem(
                index.toLong(),
                if (rung.isSelectable) rung.label else "${rung.label} — ${rung.unavailableReason}",
            )
        }
        val selected = chosenKey?.let { key -> rungs.indexOfFirst { it.key == key }.takeIf { i -> i >= 0 }?.toLong() }
        picker?.render(items, selected, placeholder = "Choose a role")
    }

    /**
     * The domain note, and the button's armed state.
     *
     * The note is a statement about the address, not a validator: it fires on the first
     * character after an `@` and says nothing at all while there is no domain to compare.
     */
    private fun onEmailTyped(value: String) {
        val at = value.indexOf('@')
        val typedDomain = if (at >= 0) value.substring(at + 1).trim() else ""
        val note = when {
            staffDomain == null || typedDomain.isEmpty() -> ""
            typedDomain.equals(staffDomain, ignoreCase = true) -> ""
            else -> "That is not an address on $staffDomain, so this person will be a member here " +
                "rather than staff — and whether they may hold an account at all is the " +
                "instance's admission setting."
        }
        domainNote.setTextIfChanged(note)
        domainNote.visible(note.isNotEmpty())
        updateArmed()
    }

    /** Add is live once there is something to send: an address and a rung. */
    private fun updateArmed() {
        addButton.disabled = emailField.value.isBlank() || chosenKey == null
    }
}
