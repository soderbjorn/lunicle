/**
 * Entry point for the Lunicle Kotlin/JS web frontend.
 *
 * Bootstraps the app: constructs the [CounterBackingViewModel], mounts the
 * [CounterView] into `#app`, and pumps the view model's single state flow into
 * the view. That is the whole of it — every decision about what to show lives
 * in the shared view model, and this file only wires the two together.
 *
 * @see CounterView
 * @see CounterBackingViewModel
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.client.viewmodel.CounterBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.SessionBackingViewModel

/**
 * Kotlin/JS main entry point. Defers [start] to `window.onload` so `#app` is
 * guaranteed to exist regardless of where the bundle's script tag lands.
 */
fun main() {
    window.onload = { start() }
}

/**
 * Mount the UI and start the view model.
 *
 * The collector runs on [Dispatchers.Main] — DOM mutation must happen on the
 * browser's single thread, and the view model emits from wherever its request
 * completed.
 */
private fun start() {
    val host = document.getElementById("app") as? HTMLElement
    if (host == null) {
        println("Lunicle: #app missing; nothing to mount into")
        return
    }

    val counterViewModel = CounterBackingViewModel()
    val counterView = CounterView(counterViewModel)
    counterView.mount(host)

    // Mounted into the counter's panel rather than beside it, so the sign-in row
    // sits inside the same card. The panel is the view's, so ask for it rather
    // than reaching into the DOM by class name from here.
    val sessionViewModel = SessionBackingViewModel()
    val signInView = SignInView(sessionViewModel)
    signInView.mount(counterView.panel)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Two collectors, not one: the two view models are independent and a
    // combine() would couple every counter tick to a session re-render for no
    // benefit.
    scope.launch {
        counterViewModel.stateFlow.collect { state -> counterView.render(state) }
    }
    scope.launch {
        sessionViewModel.stateFlow.collect { state -> signInView.onState(state) }
    }

    counterViewModel.start()
    sessionViewModel.start()
}
