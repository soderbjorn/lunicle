/**
 * Shared backing view-model for who is signed in.
 *
 * Follows the same convention as [CounterBackingViewModel]: all the logic, one
 * immutable [State] over a [StateFlow], and no platform in sight.
 *
 * The line this file has to hold carefully is the popup. Opening one is
 * irreducibly platform work — Google's SDK is browser-only and GitHub's flow is
 * a `window.open` — so the *view* owns the popup and reports back through
 * [onGoogleCodeReceived] and [onGitHubPopupSucceeded]. This view model never
 * learns that a popup exists. An iOS client would use `ASWebAuthenticationSession`
 * against the same two intents and reuse every line below.
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
import se.soderbjorn.lunicle.clientserver.ApiFailure
import se.soderbjorn.lunicle.clientserver.LunicleApi
import se.soderbjorn.lunicle.clientserver.SessionState
import se.soderbjorn.lunicle.clientserver.SignedInUser

/**
 * What to show the user for a failure.
 *
 * The server writes its sign-in refusals for a human — "GitHub would not
 * complete the sign-in" — so prefer that to a generic line invented here, which
 * is by definition less specific than what the server already knows. Anything
 * that isn't an [ApiFailure] is a transport or parse problem with no message
 * worth showing, so it gets the fallback.
 */
private fun Throwable.userMessage(fallback: String): String =
    (this as? ApiFailure)?.serverMessage?.takeIf { it.isNotBlank() } ?: fallback

/**
 * Owns the session round-trips and exposes the result as a [StateFlow].
 *
 * @param api the server transport; defaults to a same-origin [LunicleApi].
 * @param scope coroutine scope the requests run in.
 */
class SessionBackingViewModel(
    private val api: LunicleApi = LunicleApi(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _stateFlow = MutableStateFlow(State())

    /** The current session state, observed by the view. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    private var started = false

    /**
     * Immutable snapshot of the whole sign-in area.
     *
     * @property user who is signed in, or null. Stage 2's entire proof: a name
     *   here means the popup, the provider, the exchange and the session all
     *   worked, because nothing else can produce one.
     * @property isLoaded whether the first session fetch has returned. Before it
     *   has, the view shows neither buttons nor a name — flashing "Sign in" at a
     *   user who is already signed in is worse than a moment of nothing.
     * @property isBusy true while a request is in flight.
     * @property errorMessage a human-readable failure, or null.
     * @property googleClientId the public client id the view needs to open
     *   Google's popup; null when Google isn't configured here.
     */
    data class State(
        val user: SignedInUser? = null,
        val isLoaded: Boolean = false,
        val isBusy: Boolean = false,
        val errorMessage: String? = null,
        val isGoogleAvailable: Boolean = false,
        val isGitHubAvailable: Boolean = false,
        val googleClientId: String? = null,
    ) {
        /** Whether to render any sign-in affordance at all. */
        val isSignInAvailable: Boolean get() = isGoogleAvailable || isGitHubAvailable

        /** What to render where the user's name goes. */
        val greeting: String? get() = user?.let { "Signed in as ${it.displayName}" }
    }

    /**
     * Fetch the current session. Idempotent.
     *
     * Called once by the app bootstrap.
     */
    fun start() {
        if (started) return
        started = true
        println("Session: fetching current session")
        scope.launch { refresh() }
    }

    /**
     * A Google authorization code arrived from the popup.
     *
     * Called by the view. The code is useless without the client secret, which
     * only the server has — which is exactly why the browser is allowed to
     * handle it at all.
     */
    fun onGoogleCodeReceived(code: String) {
        println("Session: exchanging Google code")
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { api.signInWithGoogle(code) }
            _stateFlow.value = result.fold(
                onSuccess = { it.applyTo(_stateFlow.value).copy(isBusy = false) },
                onFailure = { t ->
                    println("Session: Google sign-in failed: ${t.message ?: t::class.simpleName}")
                    _stateFlow.value.copy(isBusy = false, errorMessage = t.userMessage("Google sign-in did not complete."))
                },
            )
        }
    }

    /**
     * The GitHub popup reported success.
     *
     * Unlike Google, there's no code to carry: GitHub's popup hit our callback
     * route directly, so the session cookie is already set by the time the
     * browser hears about it. All that's left is to ask the server who we now
     * are — which also means the client never takes the popup's word for it.
     */
    fun onGitHubPopupSucceeded() {
        println("Session: GitHub popup reported success; confirming with the server")
        scope.launch { refresh() }
    }

    /**
     * The popup failed, or the user closed it.
     *
     * @param message what to show, or null to fail quietly — a user who closed
     *   the popup deliberately doesn't need an error about it.
     */
    fun onSignInFailed(message: String?) {
        println("Session: sign-in failed: ${message ?: "cancelled"}")
        _stateFlow.value = _stateFlow.value.copy(isBusy = false, errorMessage = message)
    }

    /** The view is about to open a popup; reflect that immediately. */
    fun onSignInStarted() {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
    }

    /** Sign out. Called by the view. */
    fun onSignOutTapped() {
        if (_stateFlow.value.isBusy) return
        println("Session: signing out")
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { api.signOut() }
            _stateFlow.value = result.fold(
                onSuccess = { it.applyTo(_stateFlow.value).copy(isBusy = false) },
                onFailure = { t ->
                    println("Session: sign-out failed: ${t.message ?: t::class.simpleName}")
                    _stateFlow.value.copy(isBusy = false, errorMessage = "Could not sign out.")
                },
            )
        }
    }

    /**
     * Ask the server who we are and emit it.
     *
     * A failure here leaves [State.isLoaded] false, so the view keeps showing
     * nothing rather than guessing at signed-out — which would offer a sign-in
     * button to someone who already has a session.
     */
    private suspend fun refresh() {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        val result = runCatching { api.session() }
        _stateFlow.value = result.fold(
            onSuccess = { session ->
                println("Session: server reports ${session.user?.displayName ?: "signed out"}")
                session.applyTo(_stateFlow.value).copy(isBusy = false)
            },
            onFailure = { t ->
                println("Session: fetch failed: ${t.message ?: t::class.simpleName}")
                _stateFlow.value.copy(isBusy = false, errorMessage = "Could not reach the server.")
            },
        )
    }

    /** Fold a server [SessionState] into the view state, marking it loaded. */
    private fun SessionState.applyTo(previous: State): State = previous.copy(
        user = user,
        isLoaded = true,
        errorMessage = null,
        isGoogleAvailable = isGoogleAvailable,
        isGitHubAvailable = isGitHubAvailable,
        googleClientId = googleClientId,
    )
}
