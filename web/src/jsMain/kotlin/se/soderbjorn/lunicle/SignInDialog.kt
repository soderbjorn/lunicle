/**
 * The provider picker: GitHub or Google, with the reason to pick one.
 *
 * A dumb view over [SignInView]'s two callbacks. It owns no popup logic — that
 * stays in SignInView, which is the one place allowed to know what a popup is —
 * and no state; [render] is handed a session snapshot like every other view here.
 *
 * ── Why the copy says what it says ──────────────────────────────────────────
 *
 * The two providers are not interchangeable, and a picker that presents them as
 * a coin flip sends people down the wrong one. The rule the wording encodes is
 * narrower than "developers use GitHub": **if you have a GitHub account, use it**
 * — whether or not you ever intend to contribute. Google is for the people who
 * have no GitHub account at all.
 *
 * That is stronger than a preference, because of what this app does with a
 * provider. The same human signing in via both is *two accounts* here, and there
 * is nothing that merges them — see the greeting in SessionBackingViewModel,
 * which names the provider for exactly this reason. So someone with a GitHub
 * account who picks Google has quietly made a second identity, and finds out
 * when a later screen refuses them, or when their issues are filed under someone
 * who appears to be a stranger. Saying it before the click is the only place it
 * can be said cheaply.
 *
 * @see SignInView
 */
package se.soderbjorn.lunicle

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.client.viewmodel.SessionBackingViewModel

/**
 * Renders the sign-in modal.
 *
 * @param onGoogle ask [SignInView] to open Google's popup.
 * @param onGitHub ask [SignInView] to open GitHub's popup.
 * @param onDismiss the user closed the dialog without choosing.
 */
class SignInDialog(
    private val onGoogle: () -> Unit,
    private val onGitHub: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val modal = Modal("Sign in", onDismiss = { onDismiss() })

    private lateinit var githubOption: HTMLElement
    private lateinit var googleOption: HTMLElement
    private lateinit var githubButton: HTMLButtonElement
    private lateinit var googleButton: HTMLButtonElement
    private lateinit var errorElement: HTMLElement

    fun mount(host: HTMLElement) {
        githubButton = providerButton("Sign in with GitHub", "btn btn-primary") { onGitHub() }
        googleButton = providerButton("Sign in with Google", "btn") { onGoogle() }

        // Each button carries its own reason directly under it, rather than one
        // paragraph of preamble above both. A preamble is read once and then
        // never again by anyone who has seen this dialog before; the line under
        // the button is next to the thing it is about.
        githubOption = option(
            githubButton,
            "Required if you plan to contribute as a developer (but you can choose " +
                "it even if you don't plan to, if you have a GitHub account).",
        )
        googleOption = option(
            googleButton,
            "Use this if you don't have a GitHub account.",
        )

        errorElement = element("p", "modal-error")
        errorElement.setAttribute("role", "status")

        modal.body.children(githubOption, googleOption, errorElement)

        // No OK button. The provider buttons *are* the confirmation — an OK
        // beside them would be a second thing to press for a choice already
        // made. Cancel stays, because leaving without choosing is a real intent
        // and the backdrop alone does not advertise itself.
        modal.footer.children(
            element("div", "modal-footer-spacer"),
            button("Cancel", "btn btn-quiet") { onDismiss() },
        )

        modal.mount(host)
    }

    /**
     * Apply a session snapshot.
     *
     * A provider the server did not configure is hidden rather than disabled: a
     * dead button invites a click and then explains nothing. This mirrors the
     * old sidebar's rule — see the comment on isSignInAvailable in SignInView.
     */
    fun render(state: SessionBackingViewModel.State) {
        githubOption.visible(state.isGitHubAvailable)
        googleOption.visible(state.isGoogleAvailable)

        githubButton.disabled = state.isBusy
        googleButton.disabled = state.isBusy

        errorElement.setTextIfChanged(state.errorMessage ?: "")
        errorElement.visible(state.errorMessage != null)
    }

    private fun option(actionButton: HTMLElement, explanation: String): HTMLElement {
        val row = element("div", "signin-option")
        row.children(actionButton, element("p", "signin-option-why", explanation))
        return row
    }

    private fun providerButton(label: String, className: String, onClick: () -> Unit): HTMLButtonElement =
        button(label, "$className signin-provider-btn", onClick) as HTMLButtonElement

    fun dismiss() {
        modal.dismiss()
    }
}
