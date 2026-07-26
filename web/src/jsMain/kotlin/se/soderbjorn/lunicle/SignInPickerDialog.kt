/**
 * "How would you like to sign in?" — the picker, back again.
 *
 * A picker existed here once and was removed when GitHub sign-in went away, on
 * the reasoning [SignInView] recorded above `startGoogleSignIn`: *"Google is the
 * only provider now, so 'Sign in…' opens its popup directly rather than a
 * one-option picker."* That reasoning was right and it is conditional now — with
 * LNL-74 a deployment can offer Google, a mailed code, or both — so the view asks
 * `hasSignInChoice` and only builds this when there is genuinely a choice.
 *
 * ── A dumb renderer, and unusually strictly so ─────────────────────────────
 *
 * Every decision here is [SessionBackingViewModel]'s: which methods to offer,
 * which of the two steps to show, what counts as busy. The one thing this holds
 * is the Google button's *click*, which it hands straight back to [SignInView]
 * through [onGoogleTapped] — because opening a popup is the single piece of
 * platform knowledge that view is allowed to have, and it must not leak into a
 * second file.
 *
 * The e-mail branch has no such exception. It is a form and two HTTP calls, all
 * of which live in the view model, so an iOS client reuses the behaviour and
 * writes only this much markup again.
 *
 * Errors are not rendered here at all. They surface through the shell's existing
 * [AlertDialog] path — see `SignInView.renderAlert`, which is keyed on the message
 * so two failures in a row cannot leave the first one on screen describing the
 * second.
 *
 * @see SignInView
 * @see SessionBackingViewModel
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import se.soderbjorn.lunicle.client.viewmodel.SessionBackingViewModel

/**
 * Renders the sign-in method picker.
 *
 * @param viewModel where every decision and every request lives.
 * @param onGoogleTapped opens Google's popup. Handed in rather than done here —
 *   see this file's preamble.
 * @param onDismiss the user closed it.
 */
class SignInPickerDialog(
    private val viewModel: SessionBackingViewModel,
    private val onGoogleTapped: () -> Unit,
    private val onDismiss: () -> Unit,
    /**
     * The deployment's brand logo SVG (LNL-110), inlined above the method choice.
     * Null ⇒ no logo, exactly as before branding existed.
     */
    private val brandLogoSvg: String? = null,
) {
    private val modal = Modal("Sign in", onDismiss = { onDismiss() }, panelClass = "modal-narrow modal-signin")

    // ── Step one: choose a method ──
    private lateinit var methodSection: HTMLElement
    private lateinit var intro: HTMLElement
    private lateinit var googleButton: HTMLButtonElement
    private lateinit var divider: HTMLElement
    private lateinit var emailGroup: HTMLElement
    private lateinit var emailField: HTMLInputElement
    private lateinit var sendCodeButton: HTMLButtonElement

    // ── Step two: type the code ──
    private lateinit var codeSection: HTMLElement
    private lateinit var sentMessage: HTMLElement
    private lateinit var codeField: HTMLInputElement
    private lateinit var submitCodeButton: HTMLButtonElement
    private lateinit var restartButton: HTMLButtonElement

    fun mount(host: HTMLElement) {
        // A one-line steer, so the two paths below read as a choice rather than a
        // stack of controls. Its wording is set in render(), because whether there
        // *is* a choice depends on what the server offers.
        intro = element("p", "signin-hint")

        // The provider path: one full-width button that reads as a way in.
        googleButton = button("Continue with Google", "btn signin-provider") { onGoogleTapped() }

        // "or", with a rule reaching out to each side. Shown only when both paths
        // are on offer — see render().
        divider = element("div", "signin-divider").children(element("span", "", "or"))

        // The e-mail path, as one grouped unit: label, field, action, stacked and
        // evenly spaced so it stands beside the provider button as a peer.
        //
        // Enter in the field is the same intent as the button, which matters more
        // here than it looks: an address field is exactly where people press
        // Enter, and a form that ignored it would read as broken before anybody
        // found the button.
        emailField = textFieldCommitting { viewModel.onEmailSignInRequested(it) }
        emailField.type = "email"
        emailField.placeholder = "you@example.com"
        sendCodeButton = button("E-mail me a code", "btn signin-submit") {
            viewModel.onEmailSignInRequested(emailField.value)
        }
        emailGroup = element("div", "signin-email").children(
            element("label", "field-label", "Sign in with an e-mail code"),
            emailField,
            sendCodeButton,
        )

        methodSection = element("div", "signin-methods").children(
            intro,
            googleButton,
            divider,
            emailGroup,
        )

        // A deployment's brand logo, above the method choice, so the sign-in
        // surface wears the same mark as the topbar (LNL-110). Prepended to the
        // methods so it leads the step-one layout; absent ⇒ nothing added.
        brandLogoSvg?.let { methodSection.insertBefore(brandLogo(it).also { el -> el.className += " signin-brand-logo" }, intro) }

        sentMessage = element("p", "signin-sent")
        codeField = textFieldCommitting { viewModel.onEmailSignInCodeEntered(it) }
        // Not type="number": a code with a leading zero is legal and a number
        // input eats it, along with offering spinner arrows for a six-digit
        // secret. `one-time-code` lets the browser and the OS offer it straight
        // from the message, which is the whole reason the code is in the subject
        // line — see emailCodeSubject.
        codeField.setAttribute("inputmode", "numeric")
        codeField.setAttribute("autocomplete", "one-time-code")
        codeField.setAttribute("maxlength", "6")
        codeField.placeholder = "6-digit code"
        // An explicit action beside the field, so the code step has an obvious
        // "done" button and does not rely on the reader knowing Enter submits. The
        // field still commits on Enter/blur; the view model guards on isBusy so
        // the blur-then-click pair cannot submit twice.
        submitCodeButton = button("Sign in", "btn signin-submit") {
            viewModel.onEmailSignInCodeEntered(codeField.value)
        }
        restartButton = button("Use a different address", "btn btn-quiet btn-small") {
            viewModel.onEmailSignInRestarted()
        }

        codeSection = element("div", "signin-code").children(
            sentMessage,
            codeField,
            element(
                "p",
                "field-hint",
                "The code is in the subject line of the message, and works for 15 minutes.",
            ),
            submitCodeButton,
            restartButton,
        )

        modal.body.children(methodSection, codeSection)
        modal.footer.children(
            element("div", "modal-footer-spacer"),
            button("Cancel", "btn btn-quiet") { onDismiss() },
        )
        modal.mount(host)
    }

    /** Apply a state snapshot. */
    fun render(state: SessionBackingViewModel.State) {
        val onCodeStep = state.isAwaitingSignInCode

        // `displayValue = "flex"` on every one of these that is a flex container,
        // and it is load-bearing rather than cosmetic: visible() sets an INLINE
        // display, which beats the stylesheet, so the default "block" would strip
        // `display: flex` off these groups and take their `gap` with it — the rows
        // would collapse together with no spacing at all. This is the same
        // inline-vs-stylesheet trap the account menu's own comment in SignInView
        // documents.
        methodSection.visible(!onCodeStep, displayValue = "flex")
        codeSection.visible(onCodeStep, displayValue = "flex")

        // A method the server was not configured with is not rendered at all,
        // rather than disabled: a dead button invites a click and explains
        // nothing, and the person looking at it cannot fix the deployment anyway.
        googleButton.visible(state.isGoogleAvailable, displayValue = "inline-flex")
        emailGroup.visible(state.isEmailSignInAvailable, displayValue = "flex")
        // The "or" rule only earns its place when there is genuinely a choice on
        // either side of it.
        divider.visible(state.isGoogleAvailable && state.isEmailSignInAvailable, displayValue = "flex")
        intro.setTextIfChanged(
            when {
                state.isGoogleAvailable && state.isEmailSignInAvailable ->
                    "Continue with Google, or get a one-time code by e-mail."
                state.isGoogleAvailable -> "Continue with your Google account."
                else -> "Enter your e-mail and we'll send you a one-time code."
            },
        )

        googleButton.disabled = state.isBusy
        emailField.disabled = state.isBusy
        sendCodeButton.disabled = state.isBusy
        codeField.disabled = state.isBusy
        submitCodeButton.disabled = state.isBusy
        restartButton.disabled = state.isBusy

        sentMessage.setTextIfChanged(
            state.emailSignInAddress?.let {
                "We sent a code to $it. It works whether or not you have signed in here before."
            } ?: "",
        )

        // Focus the code box the moment the step appears, so the common case —
        // read the code off a notification, come back, type — needs no click. Only
        // when nothing else is focused, so this cannot steal the caret from
        // somebody mid-edit on a background re-render.
        if (onCodeStep && document.activeElement != codeField && codeField.value.isEmpty()) {
            codeField.focus()
        }
        if (!onCodeStep) codeField.setValueIfChanged("")
    }

    fun dismiss() {
        modal.dismiss()
    }
}
