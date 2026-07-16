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
    private lateinit var valueElement: HTMLParagraphElement
    private lateinit var buttonElement: HTMLButtonElement
    private lateinit var errorElement: HTMLParagraphElement

    /**
     * Build the panel and attach it to [host].
     *
     * Called once by [main] before it starts collecting the state flow.
     *
     * @param host the element to render into; its existing content is replaced.
     */
    fun mount(host: HTMLElement) {
        val panel = document.createElement("section") as HTMLElement
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

        val note = document.createElement("p") as HTMLParagraphElement
        note.className = "counter-note"
        note.textContent = "The count lives on the server, in memory. A redeploy resets it."

        panel.appendChild(eyebrow)
        panel.appendChild(valueElement)
        panel.appendChild(buttonElement)
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
        buttonElement.disabled = state.isBusy
        errorElement.textContent = state.errorMessage ?: ""
    }
}
