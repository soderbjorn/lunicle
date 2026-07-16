/**
 * Entry point for the Lunamux Issues Kotlin/JS web frontend.
 *
 * Bootstraps the app: constructs the [CounterBackingViewModel], mounts the
 * [CounterView] into `#app`, and pumps the view model's single state flow into
 * the view. That is the whole of it — every decision about what to show lives
 * in the shared view model, and this file only wires the two together.
 *
 * @see CounterView
 * @see CounterBackingViewModel
 */
package se.soderbjorn.issues

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import se.soderbjorn.issues.client.viewmodel.CounterBackingViewModel

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
        println("Issues: #app missing; nothing to mount into")
        return
    }

    val viewModel = CounterBackingViewModel()
    val view = CounterView(viewModel)
    view.mount(host)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    scope.launch {
        viewModel.stateFlow.collect { state -> view.render(state) }
    }

    viewModel.start()
}
