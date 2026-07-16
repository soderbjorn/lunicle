/**
 * Shared backing view-model for the counter.
 *
 * Follows the project's `*BackingViewModel` convention: it owns *all* of the
 * logic — fetching the server's count, issuing increments, deciding what a
 * failure looks like — and exposes the result as a single immutable [State]
 * over a [StateFlow]. The platform view (for now, the Kotlin/JS DOM view in
 * `:web`) only renders that state and forwards user intent back through
 * [onIncrementTapped]. Nothing about HTTP, JSON, or the DOM appears on either
 * side of that line.
 *
 * The count itself is *not* held here — it lives on the server, in SQLite on a
 * mounted volume (see the server's `Counters.kt`). This view model holds the
 * last value the server reported for whoever is currently signed in. That
 * distinction is the whole of the exit criterion: the count survives a redeploy
 * because it was never the browser's to keep, and it follows the *user* rather
 * than the browser because it is keyed by their id rather than by a cookie.
 *
 * Debug logging follows the project `println("Tag: …")` convention so the
 * round-trips are visible in DevTools.
 *
 * @see se.soderbjorn.lunicle.clientserver.LunicleApi
 * @see SessionBackingViewModel
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

    /**
     * The last session fact acted on, so a repeated one is ignored.
     *
     * The session flow emits on every one of its own changes — a busy flag
     * flicking during sign-out, an error appearing — and almost none of those
     * are news to the counter. Without this, each would re-fetch a count that
     * cannot have changed.
     */
    private var lastKnownSignedIn: Boolean? = null

    /**
     * Immutable snapshot of the whole counter view.
     *
     * @property count the server's last reported count for the signed-in user;
     *   `null` before the first successful fetch, which the view renders as a
     *   placeholder rather than a misleading zero. "Not known yet" and "zero"
     *   are different facts, and now that a count belongs to someone there is a
     *   third: "not yours to see".
     * @property isSignedIn whether anyone is signed in. The counter belongs to a
     *   user, so this decides whether there is a number at all.
     * @property isSessionKnown whether the session state has been established
     *   yet. Before it has, the view shows no prompt — telling someone to sign
     *   in and then discovering they already are is worse than a moment of
     *   nothing. Mirrors [SessionBackingViewModel.State.isLoaded].
     * @property isBusy true while a request is in flight; the view disables the
     *   button so a double-tap cannot queue a second increment.
     * @property errorMessage a human-readable failure, or `null` when the last
     *   request succeeded.
     */
    data class State(
        val count: Int? = null,
        val isSignedIn: Boolean = false,
        val isSessionKnown: Boolean = false,
        val isBusy: Boolean = false,
        val errorMessage: String? = null,
    ) {
        /**
         * The count as the view should display it, including the placeholder.
         * Kept here rather than in the view so every platform renders the
         * unknown state identically.
         */
        val countLabel: String get() = count?.toString() ?: "—"

        /**
         * Whether the button does anything. A signed-out visitor still sees it,
         * and sees that it is inert — the affordance explaining itself.
         */
        val isIncrementEnabled: Boolean get() = isSignedIn && !isBusy

        /**
         * What to say under the button, or null to say nothing. Only ever
         * appears once the session is known, for the reason on
         * [isSessionKnown].
         */
        val prompt: String? get() = when {
            !isSessionKnown -> null
            !isSignedIn -> "Sign in to start counting."
            else -> null
        }
    }

    /**
     * The session changed: someone signed in, or out, or the first session
     * fetch finally returned.
     *
     * Called by the app bootstrap, which is the only thing that sees both view
     * models. This is the counter's entire trigger — it has no `start()`,
     * because there is nothing to fetch until it knows whose count to ask for.
     *
     * @param isSignedIn whether anyone is signed in.
     * @param isKnown whether the session state has been established at all;
     *   false during the very first fetch.
     */
    fun onSessionChanged(isSignedIn: Boolean, isKnown: Boolean) {
        if (!isKnown) return
        if (lastKnownSignedIn == isSignedIn) return
        lastKnownSignedIn = isSignedIn

        if (isSignedIn) {
            println("Counter: signed in; fetching this user's count")
            _stateFlow.value = _stateFlow.value.copy(isSignedIn = true, isSessionKnown = true)
            scope.launch { refresh() }
        } else {
            // A fresh State, not a copy: the previous count belonged to whoever
            // just signed out, and carrying it over would show one person's
            // number to the next. On a shared machine that is someone else's
            // data left on screen.
            println("Counter: signed out; discarding the count")
            _stateFlow.value = State(isSignedIn = false, isSessionKnown = true)
        }
    }

    /**
     * Increment the counter.
     *
     * Called by the view when the user taps the button. Ignored while a request
     * is already in flight, and ignored when signed out — the button is
     * disabled and the server would refuse it anyway (401), so reaching here
     * signed-out means something other than the button called it.
     */
    fun onIncrementTapped() {
        val current = _stateFlow.value
        if (!current.isSignedIn) {
            println("Counter: increment ignored; nobody is signed in")
            return
        }
        if (current.isBusy) {
            println("Counter: increment ignored; a request is already in flight")
            return
        }
        _stateFlow.value = current.copy(isBusy = true, errorMessage = null)
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
     * Fetch the signed-in user's count and emit it. A failure leaves the last
     * known count in place and surfaces a message — a transient network blip
     * should not blank the display.
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
