/**
 * The Kotlin/JS DOM view for Stage 1's counter.
 *
 * Built with Kotlin DOM APIs — no Compose, no templating, no framework. The
 * view is deliberately dumb: it builds its elements once in [mount], forwards
 * taps to [CounterBackingViewModel.onIncrementTapped], and re-renders from
 * whatever [CounterBackingViewModel.State] it is handed. It holds no state of
 * its own and makes no decisions; everything it displays is computed in the
 * shared view model.
 *
 * @see CounterBackingViewModel
 * @see main
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLParagraphElement
import se.soderbjorn.lunicle.client.viewmodel.CounterBackingViewModel

/**
 * Renders the counter panel into a host element and updates it on each state
 * emission.
 *
 * @param viewModel the shared backing view model; the view's only collaborator.
 */
class CounterView(
    private val viewModel: CounterBackingViewModel,
) {
    /**
     * The card itself, exposed so the bootstrap can mount the sign-in row
     * inside it. Read-only to callers: this view still owns its own DOM, and
     * handing out the element beats having main.kt find it by class name.
     */
    lateinit var panel: HTMLElement
        private set

    private lateinit var valueElement: HTMLParagraphElement
    private lateinit var buttonElement: HTMLButtonElement
    private lateinit var errorElement: HTMLParagraphElement
    private lateinit var promptElement: HTMLParagraphElement

    /**
     * Build the panel and attach it to [host].
     *
     * Called once by [main] before it starts collecting the state flow.
     *
     * @param host the element to render into; its existing content is replaced.
     */
    fun mount(host: HTMLElement) {
        panel = document.createElement("section") as HTMLElement
        panel.className = "counter"

        val eyebrow = document.createElement("p") as HTMLParagraphElement
        eyebrow.className = "counter-eyebrow"
        eyebrow.textContent = "Lunamux / Lunicle"

        valueElement = document.createElement("p") as HTMLParagraphElement
        valueElement.className = "counter-value"
        // aria-live: the count changes without any surrounding text changing,
        // so a screen reader would otherwise announce nothing at all on tap.
        valueElement.setAttribute("aria-live", "polite")

        buttonElement = document.createElement("button") as HTMLButtonElement
        buttonElement.className = "counter-btn"
        buttonElement.type = "button"
        buttonElement.textContent = "Increment"
        buttonElement.onclick = { viewModel.onIncrementTapped() }

        errorElement = document.createElement("p") as HTMLParagraphElement
        errorElement.className = "counter-error"
        errorElement.setAttribute("role", "status")

        // Why the button is inert, for a signed-out visitor. role=status so a
        // screen reader hears it when it appears, rather than leaving a
        // disabled button unexplained.
        promptElement = document.createElement("p") as HTMLParagraphElement
        promptElement.className = "counter-prompt"
        promptElement.setAttribute("role", "status")

        val note = document.createElement("p") as HTMLParagraphElement
        note.className = "counter-note"
        note.textContent = "Your count lives on the server, in SQLite on a mounted volume. It survives a redeploy."

        panel.appendChild(eyebrow)
        panel.appendChild(valueElement)
        panel.appendChild(buttonElement)
        panel.appendChild(promptElement)
        panel.appendChild(errorElement)
        panel.appendChild(note)

        host.innerHTML = ""
        host.appendChild(panel)
    }

    /**
     * Apply a state snapshot to the mounted DOM.
     *
     * Called for every emission of [CounterBackingViewModel.stateFlow].
     *
     * @param state the snapshot to render.
     */
    fun render(state: CounterBackingViewModel.State) {
        valueElement.textContent = state.countLabel
        buttonElement.disabled = !state.isIncrementEnabled
        promptElement.textContent = state.prompt ?: ""
        errorElement.textContent = state.errorMessage ?: ""

        // The whole panel dims while there is nobody to count for, so the
        // counter reads as waiting rather than broken. A class rather than
        // inline styles, so the look stays in styles.css with the rest.
        //
        // The `!isSessionKnown` case is deliberately NOT dimmed: during the
        // first fetch we do not yet know whether this person is signed in, and
        // dimming would flash at someone who turns out to have a session.
        panel.classList.toggle("counter-signed-out", state.isSessionKnown && !state.isSignedIn)
    }
}
