/**
 * Entry point for the Lunicle Kotlin/JS web frontend.
 *
 * Bootstraps the app: constructs the two backing view models, mounts their
 * views into `#app`, and pumps each view model's single state flow into its
 * view. Every decision about what to show lives in the shared view models; this
 * file only wires things together.
 *
 * It does own one decision the view models cannot: that the session drives the
 * counter. Neither view model knows the other exists, so the bootstrap — the
 * one place that sees both — forwards the session's outcome to the counter. See
 * the collector in [start].
 *
 * @see CounterView
 * @see CounterBackingViewModel
 * @see SessionBackingViewModel
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
    // Two collectors, not one: a combine() would couple every counter tick to a
    // session re-render for no benefit.
    scope.launch {
        counterViewModel.stateFlow.collect { state -> counterView.render(state) }
    }
    scope.launch {
        sessionViewModel.stateFlow.collect { state ->
            signInView.onState(state)
            // The counter belongs to a user, so the session decides whether
            // there is one to fetch. This is the only coupling between the two
            // view models, and it lives here rather than in either of them:
            // neither should have to know the other exists, and the bootstrap
            // is already the thing that sees both.
            //
            // Only two booleans cross, not the session state itself — the
            // counter has no business knowing who is signed in, only that
            // somebody is. onSessionChanged ignores repeats, so collecting the
            // session's every emission costs nothing.
            counterViewModel.onSessionChanged(
                isSignedIn = state.user != null,
                isKnown = state.isLoaded,
            )
        }
    }

    // Only the session starts. The counter has nothing to ask for until the
    // session says who is asking; it is driven entirely by the collector above.
    sessionViewModel.start()
}
