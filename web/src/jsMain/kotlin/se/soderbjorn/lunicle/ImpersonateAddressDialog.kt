/**
 * `Any address…` — type an address, see what it resolves to, then wear it (LNL-197).
 *
 * ── Why typing an address is the whole point ────────────────────────────────
 *
 * The account menu's other rows are addresses that already have `users` rows, so they
 * can be listed. Three of the states a permission model has to get right have no row
 * to list: a stranger at the staff domain who has never signed in, an outside address
 * with nothing on file, and — reachable from the list, but only because a row exists —
 * a member who was added and never arrived. Typing an address is the only way to reach
 * the first two, which is why this dialog exists at all.
 *
 * ── Resolve, then commit ────────────────────────────────────────────────────
 *
 * Two steps, deliberately. "See what it resolves to" is a separate press from "become
 * it", and the second is disabled until the first has answered *for the address
 * currently in the field* — see [SessionBackingViewModel.onImpersonateAddressChanged],
 * which throws the resolution away the moment a character changes. A resolution left
 * on screen beside a different address is the one way this surface could lie about
 * what the button would do.
 *
 * Resolving writes nothing server-side — no `users` row, no `added_at`, no appearance
 * in any project's People list — so it is free to ask and free to abandon. See
 * `ApiRoutes.IMPERSONATE_PREVIEW`.
 *
 * ── A dumb renderer ────────────────────────────────────────────────────────
 *
 * Every decision is [SessionBackingViewModel]'s, exactly as in [SignInPickerDialog]:
 * what has been typed, what it resolved to, what is in flight. This file is markup and
 * two clicks, so an iOS client reuses the behaviour and writes only this much again.
 *
 * @see SignInView
 * @see SessionBackingViewModel
 */
package se.soderbjorn.lunicle

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import se.soderbjorn.lunicle.client.viewmodel.SessionBackingViewModel

/**
 * Renders the arbitrary-address prompt.
 *
 * @param viewModel where every decision and every request lives.
 * @param onDismiss the owner closed it.
 */
class ImpersonateAddressDialog(
    private val viewModel: SessionBackingViewModel,
    private val onDismiss: () -> Unit,
) {
    private val modal = Modal(
        title = "Act as an address",
        onDismiss = { onDismiss() },
        panelClass = "modal-narrow",
    )

    private lateinit var addressField: HTMLInputElement
    private lateinit var resolveButton: HTMLButtonElement
    private lateinit var resolution: HTMLElement
    private lateinit var actButton: HTMLButtonElement

    fun mount(host: HTMLElement) {
        // Enter in the field resolves rather than commits, which is the safe of the two
        // intents: an address field is exactly where people press Enter, and the press
        // that becomes somebody else should be a deliberate second one.
        addressField = textFieldCommitting { viewModel.onImpersonateAddressPreviewRequested() }
        addressField.type = "email"
        addressField.placeholder = "someone@example.com"
        // Every keystroke, not just the commit: the resolution has to fall away the
        // instant the address it describes stops being what is in the field.
        addressField.oninput = {
            viewModel.onImpersonateAddressChanged(addressField.value)
            Unit
        }

        resolveButton = button("See what it resolves to", "btn") {
            viewModel.onImpersonateAddressPreviewRequested()
        } as HTMLButtonElement

        // The answer, and the reason the second button is allowed to exist. Empty until
        // something has been resolved; the standing rides in a data attribute so the
        // stylesheet can tell "no account here" from "staff" without this file deciding
        // what either should look like.
        resolution = element("p", "field-hint impersonate-resolution")

        actButton = button("Act as this address", "btn btn-primary") {
            viewModel.lastTypedAddress()?.let { viewModel.onImpersonateTapped(it) }
        } as HTMLButtonElement

        modal.body.children(
            element(
                "p",
                "signin-hint",
                "Any address, whether or not it has an account here. Nothing is created: " +
                    "an address with no account is previewed and leaves no trace.",
            ),
            element("label", "field-label", "Address"),
            addressField,
            resolveButton,
            resolution,
        )
        modal.footer.children(
            button("Cancel", "btn btn-quiet") { onDismiss() },
            element("div", "modal-footer-spacer"),
            actButton,
        )
        modal.mount(host)
    }

    /** Apply a state snapshot. */
    fun render(state: SessionBackingViewModel.State) {
        addressField.setValueIfChanged(state.impersonateAddress ?: "")

        val preview = state.addressPreview
        resolution.setTextIfChanged(
            when {
                state.isPreviewingAddress -> "Resolving…"
                preview != null -> "${preview.standing.label} — ${preview.summary}"
                else -> ""
            },
        )
        // Set rather than toggled, so a stylesheet can key on it and a reader of the DOM
        // can see which of the five states was reported.
        resolution.setAttribute("data-standing", preview?.standing?.name?.lowercase() ?: "")

        val typed = state.impersonateAddress?.trim().orEmpty()
        resolveButton.disabled = state.isBusy || state.isPreviewingAddress || typed.isEmpty()
        // Only once the resolution on screen describes the address in the field. The view
        // model clears the preview on every edit, so this is that rule and not a second
        // copy of it.
        actButton.disabled = state.isBusy || preview == null
    }

    fun dismiss() {
        modal.dismiss()
    }
}

/**
 * The address the resolution on screen belongs to, or null when there is no resolution.
 *
 * Read from the *preview* rather than from the field, which is the point: the server
 * normalised what was typed, and becoming the normalised form is what makes the sentence
 * shown a description of what happened. The view model clears the preview on every edit,
 * so a null here means "nothing has been resolved for what is currently typed".
 */
private fun SessionBackingViewModel.lastTypedAddress(): String? = stateFlow.value.addressPreview?.email
