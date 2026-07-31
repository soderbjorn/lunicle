/**
 * Shared backing view-model for who is signed in — the account corner of the top
 * bar, and the provider picker it opens.
 *
 * The project convention: all the logic, one immutable [State] over a single
 * [StateFlow], and no platform in sight.
 *
 * The line this file has to hold carefully is the popup. Opening one is
 * irreducibly platform work — Google's SDK is browser-only — so the *view* owns
 * the popup and reports back through [onGoogleCodeReceived]. This view model never
 * learns that a popup exists. An iOS client would use `ASWebAuthenticationSession`
 * against the same intent and reuse every line below.
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
     * @property isImpersonating whether this session was minted by an impersonation
     *   rather than by somebody proving who they are. Straight from the server — the
     *   client cannot work this out, and must not try: the account on screen IS the
     *   account in force, so there is nothing here to compare.
     * @property isImpersonationArmed whether this signed-out browser holds a live
     *   grant and may sign itself in as anybody. The state between arming and
     *   choosing an address, in which [user] is null and [isImpersonating] false —
     *   everything renders as a stranger sees it, which is part of what is being
     *   checked. It is what makes the ordinary sign-in button open the impersonation
     *   dialog instead of Google's popup.
     * @property canImpersonate whether this caller may arm one: they own the
     *   instance and the deployment has the feature switched on. Deliberately not
     *   `user.isSysAdmin`: while probing, the account being worn is an ordinary one
     *   and its `isSysAdmin` is false, but "Stop impersonating" still has to be
     *   reachable. Collapsing the two would trap the owner as whoever they became.
     * @property isImpersonateAddressPromptOpen whether the address dialog is up.
     * @property impersonateAddress what has been typed into it, or null when it has
     *   never been opened. Held here rather than in the view for the reason
     *   [emailSignInAddress] is: a dialog rebuilt per showing cannot be the thing that
     *   remembers what is in it.
     * @property pendingEmail an address the user has asked to attach and not yet
     *   confirmed, or null. Straight from the server, like [isImpersonating] and
     *   for a stronger version of the same reason: the pending change is a row in
     *   the database, and holding it here instead would lose it the moment the
     *   settings pane closed — which LNL-71 explicitly forbids, because closing it
     *   to go and read the mail is the *expected* thing to do.
     * @property isEmailSignInAvailable whether the server can sign somebody in
     *   with a mailed code. From the server, like [isGoogleAvailable], so a method
     *   the deployment cannot perform is never rendered at all — a dead button
     *   invites a click and explains nothing.
     * @property isSignInPickerOpen whether the method picker is up.
     * @property emailSignInAddress the address a sign-in code was just requested
     *   for, or null when the picker is on its first step. Its presence *is* the
     *   two-step: null means "ask for an address", set means "ask for the code".
     *
     *   Held here and not on the server, unlike [pendingEmail] — the opposite
     *   choice for what looks like the same problem, and the difference is who is
     *   asking. A pending address change belongs to an account, has to survive the
     *   pane closing, and has a row to live in. A signed-out visitor has no
     *   account and no session, so there is nowhere to put this; losing it costs
     *   one retyped address, which is the right price for not inventing a
     *   pre-session cookie to hold it in.
     */
    data class State(
        val user: SignedInUser? = null,
        val isLoaded: Boolean = false,
        val isBusy: Boolean = false,
        val errorMessage: String? = null,
        val isGoogleAvailable: Boolean = false,
        val googleClientId: String? = null,
        val isImpersonating: Boolean = false,
        val isImpersonationArmed: Boolean = false,
        val canImpersonate: Boolean = false,
        val isImpersonateAddressPromptOpen: Boolean = false,
        val impersonateAddress: String? = null,
        val pendingEmail: String? = null,
        val isEmailSignInAvailable: Boolean = false,
        val isSignInPickerOpen: Boolean = false,
        val emailSignInAddress: String? = null,
        /**
         * Whether this deployment hides the display-name override (LNL-137).
         * Straight from the server, like [isEmailSignInAvailable] — the client
         * cannot know this and must not guess. When true the settings pane's You tab
         * omits the override field; see SettingsPane.
         */
        val isDisplayNameHidden: Boolean = false,
    ) {
        /**
         * Whether to render any sign-in affordance at all.
         *
         * **This used to mean "Google is configured".** It stopped meaning that
         * with LNL-74: a deployment may have either method, both, or neither, and
         * gating on Google would hide a working e-mail form on a server with no
         * Google credentials.
         */
        val isSignInAvailable: Boolean get() = isGoogleAvailable || isEmailSignInAvailable

        /**
         * Whether the picker is worth showing at all, rather than going straight
         * to the one method there is.
         *
         * The comment that used to sit above `startGoogleSignIn()` in SignInView
         * said "Google is the only provider now, so 'Sign in…' opens its popup
         * directly rather than a one-option picker". That reasoning survives — it
         * is just conditional now.
         */
        val hasSignInChoice: Boolean get() = isGoogleAvailable && isEmailSignInAvailable

        /** Whether the picker is on its "type the code" step. */
        val isAwaitingSignInCode: Boolean get() = emailSignInAddress != null

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
            when (it.provider) {
                se.soderbjorn.lunicle.clientserver.AuthProvider.GITHUB ->
                    "Signed in via GitHub as ${it.displayName}"
                se.soderbjorn.lunicle.clientserver.AuthProvider.GOOGLE ->
                    "Signed in via Google as ${it.displayName}"
                // Not "via E-mail as …", which would read as a provider that does
                // not exist. This one says how the account was *made*, which is
                // all the column has ever recorded — and since LNL-73 the method
                // used to sign in on any given day may not be this one at all.
                se.soderbjorn.lunicle.clientserver.AuthProvider.EMAIL ->
                    "Signed in as ${it.displayName}"
            }
        }

        /**
         * Whether the signed-in user is the instance admin.
         *
         * An affordance, and only ever that: the server re-derives this from the
         * session on every write. See SignedInUser.isSysAdmin.
         */
        val isSysAdmin: Boolean get() = user?.isSysAdmin == true

        /**
         * The caller's own e-mail, or null. The User tab pre-fills its field with
         * this, and its presence is what decides whether the notification toggles
         * appear anywhere. See SignedInUser.email.
         */
        val email: String? get() = user?.email

        /**
         * Whether [email] was ever proved rather than merely typed.
         *
         * False for every address that predates LNL-71 — nothing was checked
         * before it — so this is not a red flag on the user's screen, it is a
         * "confirm this" affordance. The server decides what an unverified
         * address may be used for; this only labels it.
         */
        val isEmailVerified: Boolean get() = user?.isEmailVerified == true

        /** Whether the User tab should be showing its code field. */
        val isAwaitingEmailCode: Boolean get() = pendingEmail != null

        /**
         * Whether [displayName] is the user's own override rather than the
         * provider's. Drives the User tab's "Reset" affordance — the name alone
         * cannot tell you, since an override may match the provider's exactly.
         */
        val hasDisplayNameOverride: Boolean get() = user?.hasDisplayNameOverride == true

        /**
         * Which rung of the instance this account stands on, as a sentence, or
         * null when nobody is signed in.
         *
         * The first of the two facts the settings pane's You tab states and that
         * nobody can change about themselves (LNL-193). Derived from the address
         * at sign-in and never chosen — see the server's `UserKind.forEmail` — so
         * it is stated rather than offered, and stated *because* everything else
         * on that tab is editable: a screen of switches with no fixed point on it
         * leaves "why can I not do X?" unanswerable.
         */
        val standingLine: String? get() = user?.let {
            if (it.isStaff) "Staff on this instance." else "A member of this instance."
        }

        /**
         * Whether this account administers the instance, as a sentence, or null
         * when nobody is signed in. [standingLine]'s other half.
         *
         * Both answers are written out. "Nothing here says I am an administrator"
         * and "this says I am not one" are the same screen only to somebody who
         * already knows which tabs an administrator has.
         */
        val administrationLine: String? get() = user?.let {
            if (it.isSysAdmin) "You administer this instance." else "You do not administer this instance."
        }

        /**
         * Why the agent-access switch is dead, when it is (LNL-192, LNL-193).
         *
         * Agent access is permitted per **tier** now, so a refusal is only
         * actionable if it names which tier was refused: this sentence says which
         * of the two switches on the Instance tab an administrator would have to
         * flip. The switch is greyed and this sits beside it rather than the whole
         * section vanishing — a missing control reads as a bug, and a greyed one
         * with a reason tells you who to ask.
         */
        val agentsNotPermittedReason: String
            get() = "Not permitted for ${if (user?.isStaff == true) "staff" else "members"} on this instance."
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
     * Re-fetch the session because something server-side changed what it reports
     * for the account already signed in — not a sign-in or sign-out, which have
     * their own paths, but an admin flipping an instance switch that rides on
     * [SessionState]: the display-name gate (LNL-137) is the one left, the
     * require-sign-in blanket having gone with LNL-192. Without this the running
     * client keeps the snapshot it took at
     * [start] and the change only lands on the next page load. Unlike [start] this
     * is not one-shot — it is meant to be called again whenever such a switch is
     * written.
     */
    fun reload() {
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

    // ── Signing in with a mailed code ────────────────────────────────────────
    //
    // All of it lives here, and none of it in the view. That is not the usual
    // "logic goes in the view model" rule being restated — this file has one
    // documented exception, the Google popup, because Google's SDK is a browser
    // global and that knowledge cannot be shared with an iOS client. The e-mail
    // flow adds no popup: it is a form and two HTTP calls, so an iOS client reuses
    // every line below unchanged. See this class's preamble.

    /** The picker was opened, or closed. */
    fun onSignInPickerOpened() {
        val state = _stateFlow.value
        if (state.user != null || state.isBusy) return
        _stateFlow.value = state.copy(isSignInPickerOpen = true, errorMessage = null)
    }

    fun onSignInPickerDismissed() {
        _stateFlow.value = _stateFlow.value.copy(
            isSignInPickerOpen = false,
            emailSignInAddress = null,
            errorMessage = null,
        )
    }

    /**
     * An address was entered on the e-mail branch of the picker: ask for a code.
     *
     * Moves to the code step on **success and only on success**, but note what
     * "success" means here: the server answers identically whether or not the
     * address has an account and whether or not the mail actually left, because
     * anything else would be an account-existence oracle. So this cannot report
     * "no account with that address" and must not try to — the only honest thing
     * to say is "check your mail". A 400 (implausible address) and a 429 (asking
     * too often) are the two real refusals, and both are facts about the request
     * rather than about anybody's account.
     */
    fun onEmailSignInRequested(email: String?) {
        val state = _stateFlow.value
        val address = email?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (state.user != null || state.isBusy) return
        _stateFlow.value = state.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.requestEmailSignIn(address) }
            _stateFlow.value = result.fold(
                onSuccess = {
                    _stateFlow.value.copy(isBusy = false, emailSignInAddress = address, errorMessage = null)
                },
                onFailure = { t ->
                    _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not send a sign-in code."),
                    )
                },
            )
        }
    }

    /**
     * The mailed code was entered: exchange it for a session.
     *
     * The address comes from [State.emailSignInAddress] rather than from the view,
     * so what is redeemed is what a code was actually requested for. Success is a
     * whole [SessionState] with a cookie already set — the same landing the Google
     * branch has, because everything downstream of the cookie is provider-blind.
     */
    fun onEmailSignInCodeEntered(code: String?) {
        val state = _stateFlow.value
        val address = state.emailSignInAddress ?: return
        val trimmed = code?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (state.isBusy) return
        _stateFlow.value = state.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.signInWithEmailCode(address, trimmed) }
            _stateFlow.value = result.fold(
                // Closing the picker and clearing the address is [applyTo]'s job
                // now — it does it for any signed-in session, so both branches of
                // this file and the Google one agree by construction rather than
                // by each remembering to.
                onSuccess = { it.applyTo(_stateFlow.value).copy(isBusy = false) },
                onFailure = { t ->
                    _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("That code was not accepted."),
                    )
                },
            )
        }
    }

    /** Go back from the code step to the address step, to fix a typo. */
    fun onEmailSignInRestarted() {
        _stateFlow.value = _stateFlow.value.copy(emailSignInAddress = null, errorMessage = null)
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
     * A sign-in failure alert was dismissed.
     *
     * [isBusy] is cleared alongside the message. Dismissing while the Google
     * popup is still open is a real sequence — the popup is a separate window and
     * this one stays clickable — and leaving the flag set would disable the "Sign
     * in…" button behind it. The popup is harmless either way: its outcome arrives
     * through [onGoogleCodeReceived] / [onSignInFailed] regardless.
     */
    fun onSignInErrorDismissed() {
        _stateFlow.value = _stateFlow.value.copy(isBusy = false, errorMessage = null)
    }

    // The account corner used to open a profile MODAL, and this view model held a
    // flag saying whether it was up. It opens the settings pane at its You tab now
    // (LNL-193), which is a pane in the workspace rather than a dialog over the
    // board — so "is it open" is the workspace's answer and not a second copy kept
    // here. The corner reports the press straight to the shell; see SignInView's
    // onOpenProfile.

    /**
     * The User tab's display-name field was committed (blur or Enter).
     *
     * Committed rather than per-keystroke, so this is one request per edit, not
     * one per character — see the view's `textFieldCommitting`. A blank clears the
     * override; the server normalises and the fresh [SessionState] it returns
     * re-renders the top bar and the field's "Reset" affordance in one hop.
     */
    fun onDisplayNameCommitted(name: String?) {
        val state = _stateFlow.value
        if (state.user == null || state.isBusy) return
        // No-op if it did not actually change — a blur with nothing edited must not
        // fire a request. Compares against the current override, treating blank and
        // null as the same "no override".
        val normalized = name?.trim()?.takeIf { it.isNotBlank() }
        val current = state.user.takeIf { it.hasDisplayNameOverride }?.displayName
        if (normalized == current) return
        _stateFlow.value = state.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.setDisplayName(normalized) }
            _stateFlow.value = result.fold(
                onSuccess = { it.applyTo(_stateFlow.value).copy(isBusy = false) },
                onFailure = { t -> _stateFlow.value.copy(isBusy = false, errorMessage = t.userMessage("Could not save your name.")) },
            )
        }
    }

    /**
     * The User tab's e-mail field was committed (blur or Enter).
     *
     * ── One field, two very different requests ─────────────────────────────
     *
     * A blank **clears** the address, immediately and with no proof asked for —
     * which also turns off the notification toggles, because there is nowhere
     * left to send. Giving a mailbox up establishes nothing, so it requires
     * nothing.
     *
     * A non-blank address **asks for a code** and writes nothing. Before LNL-71
     * this branch wrote straight to the account with the shape checked and
     * nothing verified, which was an account-takeover primitive waiting for
     * anything to key on e-mail. The address now comes back as
     * [State.pendingEmail] and the user types the code into the second field.
     *
     * Both branches live here rather than in the view, like every other decision
     * in this class: the view has no popup to own on this path, only a form.
     */
    fun onEmailCommitted(email: String?) {
        val state = _stateFlow.value
        if (state.user == null || state.isBusy) return
        val normalized = email?.trim()?.takeIf { it.isNotBlank() }
        // A blur with nothing edited must not fire a request. Compared against
        // the pending address too, so re-committing the field while waiting for a
        // code does not mail a second one.
        if (normalized == state.user.email || normalized == state.pendingEmail) return
        _stateFlow.value = state.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching {
                if (normalized == null) storage.clearEmail() else storage.requestEmailChange(normalized)
            }
            _stateFlow.value = result.fold(
                onSuccess = { it.applyTo(_stateFlow.value).copy(isBusy = false) },
                onFailure = { t ->
                    _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage(
                            if (normalized == null) "Could not remove your e-mail."
                            else "Could not send a confirmation code.",
                        ),
                    )
                },
            )
        }
    }

    /**
     * The code from the confirmation mail was entered.
     *
     * Success is what actually writes the address, so the [SessionState] that
     * comes back is the first moment the account has it. Every refusal — wrong,
     * expired, exhausted, already spent — arrives as the same message, which is
     * the server's decision and not a loss of detail on the way through here.
     */
    fun onEmailCodeCommitted(code: String?) {
        val state = _stateFlow.value
        val trimmed = code?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (state.user == null || state.isBusy || state.pendingEmail == null) return
        _stateFlow.value = state.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.confirmEmailChange(trimmed) }
            _stateFlow.value = result.fold(
                onSuccess = { it.applyTo(_stateFlow.value).copy(isBusy = false) },
                onFailure = { t ->
                    _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("That code was not accepted."),
                    )
                },
            )
        }
    }

    /**
     * Give up on a pending address change.
     *
     * Exists so that a mistyped address is not a fifteen-minute wait. Nothing is
     * undone — the pending request never touched the account — so there is
     * nothing to confirm and no interesting failure.
     */
    fun onEmailChangeCancelled() {
        val state = _stateFlow.value
        if (state.user == null || state.isBusy || state.pendingEmail == null) return
        _stateFlow.value = state.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.cancelEmailChange() }
            _stateFlow.value = result.fold(
                onSuccess = { it.applyTo(_stateFlow.value).copy(isBusy = false) },
                onFailure = { t ->
                    _stateFlow.value.copy(isBusy = false, errorMessage = t.userMessage("Could not cancel that."))
                },
            )
        }
    }

    /**
     * Arm an impersonation. **Signs this browser out.**
     *
     * The server destroys the owner's session and hands the browser a grant, so what
     * comes back is a signed-out state with [State.isImpersonationArmed] set. That
     * is not a failure to handle — it is the state the whole flow runs from, and the
     * app rendering as a stranger sees it is part of what the owner came to look at.
     */
    fun onArmImpersonationTapped() {
        val state = _stateFlow.value
        if (state.isBusy || !state.canImpersonate) return
        println("Session: arming an impersonation")
        _stateFlow.value = state.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.armImpersonation() }
            _stateFlow.value = result.fold(
                onSuccess = { it.applyTo(_stateFlow.value).copy(isBusy = false) },
                onFailure = { t ->
                    println("Session: arming failed: ${t.message}")
                    _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not arm an impersonation."),
                    )
                },
            )
        }
    }

    /**
     * Sign in as an address, on the armed grant's authority.
     *
     * Gated on [State.isImpersonationArmed] rather than on `canImpersonate`, and the
     * difference matters: the caller is **signed out** by the time this is reachable,
     * so they own nothing and `canImpersonate` is false. What authorises it is the
     * probe cookie, which this code cannot see and does not need to — the server
     * refuses without one.
     *
     * A refusal is left on screen with the dialog still up, because the two things
     * that produce one are a mistyped address and a deployment that will not admit
     * it — and the second is a thing the owner came here to find out.
     */
    fun onImpersonateTapped(email: String) {
        val state = _stateFlow.value
        if (state.isBusy || !(state.isImpersonationArmed || state.isImpersonating)) return
        println("Session: asking to sign in as <$email>")
        _stateFlow.value = state.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.impersonate(email) }
            _stateFlow.value = result.fold(
                onSuccess = { it.applyTo(_stateFlow.value).copy(isBusy = false) },
                onFailure = { t ->
                    println("Session: impersonate failed: ${t.message}")
                    _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not sign in as that address."),
                    )
                },
            )
        }
    }

    /** Open the address dialog, empty. */
    fun onImpersonateAddressPromptOpened() {
        val state = _stateFlow.value
        if (!(state.isImpersonationArmed || state.isImpersonating)) return
        _stateFlow.value = state.copy(
            isImpersonateAddressPromptOpen = true,
            impersonateAddress = "",
            errorMessage = null,
        )
    }

    /** Close it, keeping nothing. */
    fun onImpersonateAddressPromptDismissed() {
        _stateFlow.value = _stateFlow.value.copy(
            isImpersonateAddressPromptOpen = false,
            impersonateAddress = null,
        )
    }

    /** The typed address changed. */
    fun onImpersonateAddressChanged(email: String) {
        val state = _stateFlow.value
        if (!state.isImpersonateAddressPromptOpen) return
        _stateFlow.value = state.copy(impersonateAddress = email)
    }

    /**
     * Stop impersonating and go back to being the owner.
     *
     * Gated on [State.isImpersonating], not on `canImpersonate`: the account being
     * worn is an ordinary one that owns nothing, so an entitlement check here would
     * refuse the very person entitled to call it. The grant is what authorises it,
     * server-side.
     */
    fun onStopImpersonatingTapped() {
        val state = _stateFlow.value
        if (state.isBusy || !(state.isImpersonating || state.isImpersonationArmed)) return
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
     * Every path that produces a user goes through here — the Google exchange, a
     * plain refresh, a sign-out — so this is the one place the rendered session is
     * decided, and the callbacks never patch [State.user] themselves.
     *
     * ── Closing the picker belongs here, not on each method ────────────────
     *
     * A signed-in session and an open sign-in picker are contradictory, so the
     * moment `user` becomes non-null the picker has done its job and must close.
     * The e-mail branch used to clear it by hand and the Google branch did not —
     * so after a Google sign-in the picker hung open over a signed-in board. That
     * is exactly the kind of per-method omission this convergence point exists to
     * make impossible: closing it here covers Google, e-mail, and any method
     * added later, at the one place they all funnel through. `emailSignInAddress`
     * is cleared alongside so a half-finished code step cannot outlive the session
     * that replaced it.
     */
    private fun SessionState.applyTo(previous: State): State = previous.copy(
        user = user,
        isLoaded = true,
        errorMessage = null,
        isGoogleAvailable = isGoogleAvailable,
        googleClientId = googleClientId,
        isImpersonating = isImpersonating,
        isImpersonationArmed = isImpersonationArmed,
        canImpersonate = canImpersonate,
        pendingEmail = pendingEmail,
        isEmailSignInAvailable = isEmailSignInAvailable,
        isDisplayNameHidden = isDisplayNameHidden,
        isSignInPickerOpen = if (user != null) false else previous.isSignInPickerOpen,
        emailSignInAddress = if (user != null) null else previous.emailSignInAddress,
        // Every session response converges here, so this is the one place the address
        // dialog can be closed once and for all: a completed impersonation, a stop, a
        // sign-out. Its typed address goes with it, so reopening never shows the last
        // visit's. A *refused* impersonation never reaches here — the failure branch
        // keeps the dialog up with the refusal beside it, which is where somebody who
        // mistyped an address, or who just learned this deployment will not admit
        // one, wants to be.
        isImpersonateAddressPromptOpen = false,
        impersonateAddress = null,
    )
}
