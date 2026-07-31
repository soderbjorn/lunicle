/**
 * Sign in as an address — the armed browser's replacement for the sign-in picker.
 *
 * ── One field, one button, and no preview ───────────────────────────────────
 *
 * This dialog used to be a two-step: resolve the address, read a sentence about
 * what it would become, and only then commit. That whole ceremony existed because
 * committing produced a *costume* — an in-memory fiction over the owner's session
 * — and the sentence was the only thing that could describe what the fiction would
 * look like.
 *
 * Nothing is a fiction any more. Pressing the button runs the deployment's real
 * sign-in pipeline: the admission gate answers, a `users` row is created if there
 * is not one, the staff/member kind is stamped, the owner seat is taken if it is
 * vacant. So there is nothing left to preview — the honest answer to "what would
 * this address become?" is now "press it and find out, because that is the
 * question you came to have answered". A refusal comes back as the deployment's
 * own refusal, which is one of the behaviours worth checking.
 *
 * What replaces the preview is the warning below it. **A real account will exist
 * afterwards**, and it will still be there when the impersonation stops. That is
 * the point of the change rather than a cost of it, and the copy has to say so —
 * an owner who reads this expecting the old preview would otherwise create rows on
 * their own instance believing they were looking at a simulation.
 *
 * ── Reached from the ordinary sign-in button ────────────────────────────────
 *
 * While a grant is armed the caller is genuinely signed out, and pressing "Sign
 * in…" opens this instead of Google's popup or the code field — see
 * [SignInView.startSignIn]. Same door, different proof.
 *
 * ── A dumb renderer ────────────────────────────────────────────────────────
 *
 * Every decision is [SessionBackingViewModel]'s, exactly as in [SignInPickerDialog]:
 * what has been typed, what is in flight, what came back. This file is markup and
 * one click, so an iOS client reuses the behaviour and writes only this much again.
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
 * Renders the impersonation sign-in prompt.
 *
 * @param viewModel where every decision and every request lives.
 * @param onDismiss the owner closed it.
 */
class ImpersonateAddressDialog(
    private val viewModel: SessionBackingViewModel,
    private val onDismiss: () -> Unit,
) {
    private val modal = Modal(
        title = "Sign in as an address",
        onDismiss = { onDismiss() },
        panelClass = "modal-narrow",
    )

    private lateinit var addressField: HTMLInputElement
    private lateinit var signInButton: HTMLButtonElement

    fun mount(host: HTMLElement) {
        // Enter commits, which it may now that there is only one thing to do: the
        // old dialog reserved Enter for the safe half of a two-step, and with the
        // preview gone there is no safer press to reserve it for.
        addressField = textFieldCommitting { commit() }
        addressField.type = "email"
        addressField.placeholder = "someone@example.com"
        addressField.oninput = {
            viewModel.onImpersonateAddressChanged(addressField.value)
            Unit
        }

        signInButton = button("Sign in as this address", "btn btn-primary") { commit() } as HTMLButtonElement

        modal.body.children(
            element(
                "p",
                "signin-hint",
                "Any address, whether or not it has an account here. This is a real sign-in: " +
                    "an address with no account gets one, and it stays after you stop. " +
                    "An address this instance will not admit is refused, exactly as it would be.",
            ),
            element("label", "field-label", "Address"),
            addressField,
        )
        modal.footer.children(
            button("Cancel", "btn btn-quiet") { onDismiss() },
            element("div", "modal-footer-spacer"),
            signInButton,
        )
        modal.mount(host)
    }

    /** Apply a state snapshot. */
    fun render(state: SessionBackingViewModel.State) {
        addressField.setValueIfChanged(state.impersonateAddress ?: "")
        val typed = state.impersonateAddress?.trim().orEmpty()
        signInButton.disabled = state.isBusy || typed.isEmpty()
        signInButton.textContent = if (state.isBusy) "Signing in…" else "Sign in as this address"
    }

    fun dismiss() {
        modal.dismiss()
    }

    /**
     * Commit what is typed.
     *
     * Read from the field rather than from the state so a commit cannot race the
     * `oninput` that reports it — the server normalises the address anyway, so
     * trimming here is a courtesy rather than the rule.
     */
    private fun commit() {
        val typed = addressField.value.trim()
        if (typed.isNotEmpty()) viewModel.onImpersonateTapped(typed)
    }
}
