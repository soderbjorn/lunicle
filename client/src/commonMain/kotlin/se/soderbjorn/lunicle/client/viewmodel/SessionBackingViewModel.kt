/**
 * Shared backing view-model for who is signed in — the account corner of the top
 * bar, and the provider picker it opens.
 *
 * The project convention: all the logic, one immutable [State] over a single
 * [StateFlow], and no platform in sight.
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
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.client.userMessage
import se.soderbjorn.lunicle.clientserver.SessionState
import se.soderbjorn.lunicle.clientserver.SignedInUser
import se.soderbjorn.lunicle.clientserver.UserOption

/**
 * Owns the session round-trips and exposes the result as a [StateFlow].
 *
 * @param storage the client's repository; the only collaborator, so this view
 *   model never mentions HTTP.
 * @param scope coroutine scope the requests run in.
 */
class SessionBackingViewModel(
    private val storage: StorageRepository = StorageRepository(),
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
     * @property isDialogOpen whether the provider-picker modal is up. Here
     *   rather than in the view because it is a decision, not a drawing: sign-in
     *   succeeding closes it (see [applyTo]), and a view holding the flag would
     *   have to be told about that by the view model anyway. An iOS client
     *   presents a sheet off the same boolean.
     * @property isProfileDialogOpen whether the profile modal is up. Opened by
     *   clicking the account corner; the menu is a hover away and needs no state
     *   here, because it is drawn by CSS. See the client's SignInView.
     * @property isImpersonating whether [user] is somebody other than whoever
     *   signed in. Straight from the server — the client cannot work this out,
     *   and must not try.
     * @property canImpersonate whether the REAL user is an admin. Deliberately
     *   not `user.isAdmin`: while impersonating, the effective user is an
     *   ordinary one and `user.isAdmin` is false, but "Stop impersonating" still
     *   has to be reachable. That is the whole distinction, and collapsing the
     *   two would trap the admin as whoever they became.
     * @property impersonatableUsers the accounts on offer. Empty unless
     *   [canImpersonate]; the server does not send it otherwise.
     */
    data class State(
        val user: SignedInUser? = null,
        val isLoaded: Boolean = false,
        val isBusy: Boolean = false,
        val errorMessage: String? = null,
        val isGoogleAvailable: Boolean = false,
        val isGitHubAvailable: Boolean = false,
        val googleClientId: String? = null,
        val isDialogOpen: Boolean = false,
        val isProfileDialogOpen: Boolean = false,
        val isImpersonating: Boolean = false,
        val canImpersonate: Boolean = false,
        val impersonatableUsers: List<UserOption> = emptyList(),
    ) {
        /** Whether to render any sign-in affordance at all. */
        val isSignInAvailable: Boolean get() = isGoogleAvailable || isGitHubAvailable

        /**
         * The effective user's id, or null when signed out.
         *
         * What the bootstrap hands MainScreen so the board re-fetches when this
         * changes — including when it changes because an admin started
         * impersonating, which no boolean would catch. See
         * `MainScreenBackingViewModel.onSessionChanged`.
         */
        val identity: Long? get() = user?.id

        /**
         * The name shown beside the profile mark in the top bar.
         *
         * Just the name — the bar has room for a name and an icon, not a
         * sentence. Which provider it came from moved to [greeting], which is
         * the tooltip.
         */
        val displayName: String? get() = user?.displayName

        /**
         * The full sentence, now the top bar's `title`.
         *
         * "Signed in via GitHub as <github-username>" / "…via Google as
         * <Google-name>" — the provider is named because the same human signing
         * in via both is two accounts here, and the only way to tell which one
         * you are looking at is to say so. That was true when this was a sidebar
         * row and is no less true now that it is a hover away; the name alone
         * cannot answer it, because both providers can supply the same one.
         */
        val greeting: String? get() = user?.let {
            val provider = when (it.provider) {
                se.soderbjorn.lunicle.clientserver.AuthProvider.GITHUB -> "GitHub"
                se.soderbjorn.lunicle.clientserver.AuthProvider.GOOGLE -> "Google"
            }
            "Signed in via $provider as ${it.displayName}"
        }

        /**
         * Whether the signed-in user is the instance admin.
         *
         * An affordance, and only ever that: the server re-derives this from the
         * session on every write. See SignedInUser.isAdmin.
         */
        val isAdmin: Boolean get() = user?.isAdmin == true
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
            val result = runCatching { storage.signInWithGoogle(code) }
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

    /**
     * "Sign in…" was tapped. Put the provider picker up.
     *
     * The error is cleared on the way in: a failure from a previous attempt has
     * been on screen since, and re-opening the dialog to be told about the last
     * one reads as this attempt having failed before it started.
     */
    fun onSignInTapped() {
        if (!_stateFlow.value.isSignInAvailable) return
        _stateFlow.value = _stateFlow.value.copy(isDialogOpen = true, errorMessage = null)
    }

    /**
     * The provider picker was dismissed.
     *
     * [isBusy] is cleared too. Dismissing while a popup is open is a real
     * sequence — the popup is a separate window and this one stays clickable —
     * and leaving the flag set would disable the "Sign in…" button behind it
     * with no dialog left to explain why. The popup that is still open is
     * harmless: its outcome arrives through the same callbacks either way, and a
     * session that lands is a session, dialog or no dialog.
     */
    fun onSignInDialogDismissed() {
        _stateFlow.value = _stateFlow.value.copy(isDialogOpen = false, isBusy = false, errorMessage = null)
    }

    /** The account corner was clicked. Put the profile modal up. */
    fun onAccountTapped() {
        val state = _stateFlow.value
        if (state.user == null) return
        _stateFlow.value = state.copy(isProfileDialogOpen = true)
    }

    /** The profile modal was dismissed. */
    fun onProfileDialogDismissed() {
        _stateFlow.value = _stateFlow.value.copy(isProfileDialogOpen = false)
    }

    /**
     * Act as somebody else.
     *
     * The server decides whether this is allowed and what it means; this only
     * asks. Nothing is assumed about the outcome — the state that comes back is
     * the state, including the case where the server refused and we are still
     * ourselves.
     */
    fun onImpersonateTapped(userId: Long) {
        val state = _stateFlow.value
        if (state.isBusy || !state.canImpersonate) return
        println("Session: asking to impersonate user $userId")
        _stateFlow.value = state.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.impersonate(userId) }
            _stateFlow.value = result.fold(
                onSuccess = { it.applyTo(_stateFlow.value).copy(isBusy = false) },
                onFailure = { t ->
                    println("Session: impersonate failed: ${t.message}")
                    _stateFlow.value.copy(isBusy = false, errorMessage = t.userMessage("Could not impersonate that user."))
                },
            )
        }
    }

    /** Stop impersonating and go back to being yourself. */
    fun onStopImpersonatingTapped() {
        val state = _stateFlow.value
        if (state.isBusy || !state.canImpersonate) return
        println("Session: stopping impersonation")
        _stateFlow.value = state.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.stopImpersonating() }
            _stateFlow.value = result.fold(
                onSuccess = { it.applyTo(_stateFlow.value).copy(isBusy = false) },
                onFailure = { t ->
                    println("Session: stop impersonating failed: ${t.message}")
                    _stateFlow.value.copy(isBusy = false, errorMessage = t.userMessage("Could not stop impersonating."))
                },
            )
        }
    }

    /** Sign out. Called by the view. */
    fun onSignOutTapped() {
        if (_stateFlow.value.isBusy) return
        println("Session: signing out")
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.signOut() }
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
        val result = runCatching { storage.session() }
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

    /**
     * Fold a server [SessionState] into the view state, marking it loaded.
     *
     * The dialog closes exactly when a session arrives with somebody in it. That
     * is the one honest signal: the popup reporting success is only a hint (see
     * [onGitHubPopupSucceeded]), so closing on the hint would dismiss the picker
     * for a sign-in the server had not agreed to. Every path that produces a
     * user goes through here, which is why the rule lives here and not in the
     * three callbacks.
     */
    private fun SessionState.applyTo(previous: State): State = previous.copy(
        user = user,
        isLoaded = true,
        errorMessage = null,
        isGoogleAvailable = isGoogleAvailable,
        isGitHubAvailable = isGitHubAvailable,
        googleClientId = googleClientId,
        isDialogOpen = previous.isDialogOpen && user == null,
        isImpersonating = isImpersonating,
        canImpersonate = canImpersonate,
        impersonatableUsers = impersonatableUsers,
    )
}
