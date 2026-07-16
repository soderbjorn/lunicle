/**
 * Shared backing view-model for Stage 1's counter.
 *
 * Follows the project's `*BackingViewModel` convention: it owns *all* of the
 * logic — fetching the server's count, issuing increments, deciding what a
 * failure looks like — and exposes the result as a single immutable [State]
 * over a [StateFlow]. The platform view (for Stage 1, the Kotlin/JS DOM view in
 * `:web`) only renders that state and forwards user intent back through
 * [onIncrementTapped]. Nothing about HTTP, JSON, or the DOM appears on either
 * side of that line.
 *
 * The count itself is *not* held here — it lives on the server (see the
 * server's `CounterRoutes.kt`). This view model holds the last value the server
 * reported. That distinction is the whole of Stage 1's exit criterion: a
 * container redeploy resets the count, because the count was never the
 * browser's to keep.
 *
 * Debug logging follows the project `println("Tag: …")` convention so the
 * round-trips are visible in DevTools.
 *
 * @see se.soderbjorn.lunicle.clientserver.LunicleApi
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.soderbjorn.lunicle.clientserver.LunicleApi

/**
 * Owns the counter round-trips and exposes the result as a [StateFlow].
 *
 * @param api the server transport; defaults to a same-origin [LunicleApi], which
 *   is what the browser bundle wants.
 * @param scope coroutine scope the fetches run in; defaults to a
 *   [SupervisorJob] scope so one failed request never tears down the next.
 */
class CounterBackingViewModel(
    private val api: LunicleApi = LunicleApi(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _stateFlow = MutableStateFlow(State())

    /** The current counter state, observed by the view. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    private var started = false

    /**
     * Immutable snapshot of the whole counter view.
     *
     * @property count the server's last reported count; `null` before the first
     *   successful fetch, which the view renders as a placeholder rather than a
     *   misleading zero — "not known yet" and "zero" are different facts.
     * @property isBusy true while a request is in flight; the view disables the
     *   button so a double-tap cannot queue a second increment.
     * @property errorMessage a human-readable failure, or `null` when the last
     *   request succeeded.
     */
    data class State(
        val count: Int? = null,
        val isBusy: Boolean = false,
        val errorMessage: String? = null,
    ) {
        /**
         * The count as the view should display it, including the pre-fetch
         * placeholder. Kept here rather than in the view so every platform
         * renders the unknown state identically.
         */
        val countLabel: String get() = count?.toString() ?: "—"
    }

    /**
     * Fetch the server's current count. Idempotent — repeated calls after the
     * first are ignored, so the view can safely call it on every mount.
     *
     * Called once by the app bootstrap.
     */
    fun start() {
        if (started) return
        started = true
        println("Counter: starting; fetching current count")
        scope.launch { refresh() }
    }

    /**
     * Increment the counter.
     *
     * Called by the view when the user taps the button. Ignored while a request
     * is already in flight.
     */
    fun onIncrementTapped() {
        if (_stateFlow.value.isBusy) {
            println("Counter: increment ignored; a request is already in flight")
            return
        }
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { api.increment() }
            _stateFlow.value = result.fold(
                onSuccess = { state ->
                    println("Counter: incremented to ${state.count}")
                    _stateFlow.value.copy(count = state.count, isBusy = false, errorMessage = null)
                },
                onFailure = { t ->
                    println("Counter: increment failed: ${t.message ?: t::class.simpleName}")
                    _stateFlow.value.copy(isBusy = false, errorMessage = "Could not reach the server.")
                },
            )
        }
    }

    /**
     * Fetch the server's count and emit it. A failure leaves the last known
     * count in place and surfaces a message — a transient network blip should
     * not blank the display.
     */
    private suspend fun refresh() {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        val result = runCatching { api.counter() }
        _stateFlow.value = result.fold(
            onSuccess = { state ->
                println("Counter: server reports ${state.count}")
                _stateFlow.value.copy(count = state.count, isBusy = false, errorMessage = null)
            },
            onFailure = { t ->
                println("Counter: fetch failed: ${t.message ?: t::class.simpleName}")
                _stateFlow.value.copy(isBusy = false, errorMessage = "Could not reach the server.")
            },
        )
    }
}
