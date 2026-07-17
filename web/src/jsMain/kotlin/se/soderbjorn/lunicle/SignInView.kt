/**
 * The account corner of the top bar, and the popups behind it.
 *
 * Signed out it is one "Sign in…" button, which opens [SignInDialog] to choose a
 * provider. Signed in it is the user's name and the profile mark, which open a
 * menu holding sign-out.
 *
 * This is the one view in the project that is allowed to know something the
 * backing view model doesn't: **how to open a popup**. That knowledge cannot be
 * shared — Google's SDK is a browser global and GitHub's flow is a
 * `window.open` — so it stops here, and everything downstream of "we have a
 * code" happens in [SessionBackingViewModel], which an iOS client will reuse
 * unchanged. The dialog is a dumb view over the two callbacks below; it never
 * learns what a popup is either.
 *
 * Why a popup at all: both providers refuse to be framed
 * (`X-Frame-Options: DENY`, and GitHub adds `frame-ancestors 'none'`), and
 * Lunicle lives in an iframe on lunamux.dev. There is no header we can set to
 * change that. See docs/oauth-instructions.html.
 *
 * @see SessionBackingViewModel
 * @see SignInDialog
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.client.viewmodel.ConnectionsBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.SessionBackingViewModel
import se.soderbjorn.lunicle.clientserver.ApiRoutes

/**
 * Renders the account corner.
 *
 * @param viewModel the shared backing view model; the view's only collaborator.
 * @param dialogHost where the provider picker mounts — the same host the issue
 *   and project dialogs use, so the modals stack in one place and Modal's
 *   topmost-wins Escape handling keeps working.
 */
class SignInView(
    private val viewModel: SessionBackingViewModel,
    private val dialogHost: HTMLElement,
    /**
     * Handed to the profile modal's view model, which this view builds fresh on
     * every opening — see [renderProfileDialog]. The same shared instance the rest
     * of the app uses, so there is still exactly one HTTP client.
     */
    private val storage: StorageRepository,
) {
    private lateinit var root: HTMLElement
    private lateinit var signInButton: HTMLButtonElement
    private lateinit var accountButton: HTMLButtonElement
    private lateinit var nameElement: HTMLElement
    private lateinit var menuElement: HTMLElement
    private lateinit var signOutButton: HTMLButtonElement

    /** Google's code client, built lazily — the SDK may not have loaded yet. */
    private var googleCodeClient: dynamic = null

    /** The GitHub popup, held so a second click focuses it rather than opening another. */
    private var githubPopup: dynamic = null

    /** The provider picker while it is up, so [render] can tell "open it" from "already open". */
    private var dialog: SignInDialog? = null

    /** The failure alert while it is up, and the message it is showing. */
    private var alert: AlertDialog? = null
    private var alertMessage: String? = null

    /** The profile modal while it is up, and the scope collecting its view model. */
    private var profileDialog: ProfileDialog? = null
    private var profileScope: CoroutineScope? = null

    /** The impersonation submenu's rows, rebuilt only when the user list changes. */
    private lateinit var impersonateItem: HTMLElement
    private lateinit var impersonateSubmenu: HTMLElement
    private lateinit var stopImpersonatingButton: HTMLButtonElement
    private var renderedUserIds: List<Long> = emptyList()

    /**
     * Build the corner and attach it to [host].
     *
     * @param host the element to append into — the top bar's far right.
     */
    fun mount(host: HTMLElement) {
        root = element("div", "account")

        signInButton = button("Sign in…", "btn account-signin") { viewModel.onSignInTapped() } as HTMLButtonElement

        // Name and mark are one button, not a label beside an icon button. The
        // name is the bigger target and reads as the thing you would click, so a
        // click on it doing nothing is the kind of dead spot nobody reports and
        // everybody notices.
        nameElement = element("span", "account-name")
        accountButton = (document.createElement("button") as HTMLButtonElement).apply {
            className = "account-btn"
            type = "button"
            onclick = { viewModel.onAccountTapped() }
        }
        accountButton.children(nameElement, profileIcon())
        accountButton.setAttribute("aria-haspopup", "menu")

        signOutButton = button("Sign out", "account-menu-item") { viewModel.onSignOutTapped() } as HTMLButtonElement

        // "Impersonate ▸" and the submenu that folds out of it. The submenu is a
        // child of the item, not a sibling: it opens on hover, and CSS hover only
        // survives the pointer travelling from the item to the submenu if the
        // submenu is inside the thing being hovered.
        impersonateSubmenu = element("div", "account-submenu")
        impersonateSubmenu.setAttribute("role", "menu")
        impersonateItem = element("div", "account-menu-item account-menu-parent")
        impersonateItem.setAttribute("role", "menuitem")
        impersonateItem.setAttribute("aria-haspopup", "menu")
        impersonateItem.children(
            element("span", "account-menu-label", "Impersonate"),
            element("span", "account-menu-arrow", "\u25B8"),
            impersonateSubmenu,
        )

        stopImpersonatingButton =
            button("Stop impersonating", "account-menu-item") { viewModel.onStopImpersonatingTapped() } as HTMLButtonElement

        menuElement = element("div", "account-menu")
        menuElement.setAttribute("role", "menu")
        // Sign out first, then the impersonation control — which of the two
        // appears is decided in render(), never both.
        menuElement.children(signOutButton, impersonateItem, stopImpersonatingButton)

        // The name appears without any surrounding text changing, so a screen
        // reader would announce nothing at all when a sign-in lands. The corner
        // has no visible label to carry that, so the live region is the name.
        nameElement.setAttribute("aria-live", "polite")

        root.children(signInButton, accountButton, menuElement)
        host.appendChild(root)

        listenForGitHubPopup()
    }

    /**
     * Apply a state snapshot to the mounted DOM.
     *
     * Everything here is a visibility decision the view model already made; this
     * only translates it into the DOM.
     */
    fun render(state: SessionBackingViewModel.State) {
        // Before the first fetch returns, show nothing. Flashing "Sign in" at
        // someone who already has a session is a worse first impression than a
        // beat of empty space.
        root.style.visibility = if (state.isLoaded) "visible" else "hidden"

        val signedIn = state.user != null

        nameElement.setTextIfChanged(state.displayName ?: "")
        // The provider is the tooltip now that the corner shows the bare name.
        // Both providers can supply the same display name, so this is the only
        // thing that answers "which of my two accounts is this?".
        accountButton.title = state.greeting ?: ""
        accountButton.visible(signedIn, displayValue = "inline-flex")

        // A CLASS, never an inline display. The menu opens on hover, in CSS, and
        // `visible()` sets `style.display` — an inline style, which beats the
        // stylesheet unconditionally. Toggling it here pinned the menu to
        // `display: flex` forever: it hung open under the profile button from the
        // moment you signed in and nothing could shut it, because the `:hover`
        // rule it was fighting could never win. It did not read as "the hover rule
        // is being overridden", it read as "the menu never goes away".
        //
        // The class only says *whether a menu exists at all* — signed out, there
        // is nothing to open. When it opens is CSS's business alone.
        root.classList.toggle("account-signed-in", signedIn)
        signOutButton.disabled = state.isBusy

        // "Impersonate" and "Stop impersonating" are the same slot, never both.
        // Both hang off canImpersonate — the REAL user being an admin — and not
        // off user.isAdmin, which is false for the whole time an admin is
        // impersonating and would take "Stop impersonating" away exactly when it
        // is the only thing needed. See SessionBackingViewModel.State.
        impersonateItem.visible(state.canImpersonate && !state.isImpersonating, displayValue = "flex")
        stopImpersonatingButton.visible(state.canImpersonate && state.isImpersonating, displayValue = "block")
        stopImpersonatingButton.disabled = state.isBusy
        renderImpersonatableUsers(state)

        // A deployment with no credentials renders no sign-in at all, rather
        // than a button that cannot work. The server decides this, not the
        // bundle — only it knows which variables it was given.
        signInButton.visible(!signedIn && state.isSignInAvailable, displayValue = "inline-flex")
        signInButton.disabled = state.isBusy

        renderDialog(state)
        renderProfileDialog(state)
        renderAlert(state)
    }

    /**
     * Fill the impersonation submenu.
     *
     * Rebuilt only when the ids change, like the project picker: this runs on
     * every session emission, and replacing the rows under a pointer that is
     * hovering one of them makes the submenu flicker and lose the hover.
     *
     * The admin's own row is rendered and disabled rather than omitted, so the
     * list matches the user table they are looking at — and "become myself" is
     * spelled "Stop impersonating", which is a different item.
     */
    private fun renderImpersonatableUsers(state: SessionBackingViewModel.State) {
        val ids = state.impersonatableUsers.map { it.id }
        if (ids == renderedUserIds) return
        renderedUserIds = ids
        impersonateSubmenu.clear()
        if (state.impersonatableUsers.isEmpty()) {
            impersonateSubmenu.appendChild(element("p", "account-submenu-empty", "No other users yet."))
            return
        }
        state.impersonatableUsers.forEach { option ->
            val row = button(option.name, "account-menu-item") {
                viewModel.onImpersonateTapped(option.id)
            } as HTMLButtonElement
            if (option.isSelf) {
                row.disabled = true
                row.title = "This is you."
            }
            impersonateSubmenu.appendChild(row)
        }
    }

    /**
     * Open or close the profile modal to match the state.
     *
     * A fresh [ConnectionsBackingViewModel] per opening, deliberately: connections
     * are changed by things outside this browser — an agent authorizing, a token
     * expiring — so a view model held across openings would show a list that was
     * true the first time the dialog was opened and stale every time after. Each
     * open re-fetches, and the scope dies with the dialog so a response arriving
     * after it closed has nothing to render into.
     */
    private fun renderProfileDialog(state: SessionBackingViewModel.State) {
        if (state.isProfileDialogOpen && profileDialog == null) {
            val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            profileScope = dialogScope
            profileDialog = ProfileDialog(
                viewModel = ConnectionsBackingViewModel(storage, dialogScope),
                scope = dialogScope,
                onDismiss = { viewModel.onProfileDialogDismissed() },
            ).also { it.mount(dialogHost) }
        } else if (!state.isProfileDialogOpen && profileDialog != null) {
            profileDialog?.dismiss()
            profileDialog = null
            profileScope?.cancel()
            profileScope = null
        }
        profileDialog?.render(state)
    }

    /**
     * Put a failure up as a modal, or take it down.
     *
     * Only the failures the picker cannot show. While the picker is up it owns
     * the error — that is where the click that caused it happened, and an alert
     * stacked on top would cover the buttons it is talking about. This is for
     * what is left: a sign-out that failed, and a session fetch that did.
     *
     * Keyed on the message rather than on "is there one", for the reason
     * Dialogs.renderAlert gives: two different failures in a row would otherwise
     * leave the first one on screen describing the second.
     */
    private fun renderAlert(state: SessionBackingViewModel.State) {
        val message = state.errorMessage?.takeIf { !state.isDialogOpen }
        if (message == alertMessage) return
        alert?.dismiss()
        alertMessage = message
        alert = message?.let {
            AlertDialog(
                title = "Something went wrong",
                message = it,
                // Reusing the picker's dismissal: it clears the error and the
                // busy flag, which is exactly what dismissing this should do.
                // The dialog it names is already closed — that is the condition
                // for being here at all.
                onDismiss = { viewModel.onSignInDialogDismissed() },
            ).also { dialog -> dialog.mount(dialogHost) }
        }
    }

    /** Open or close the provider picker to match the state, and feed it. */
    private fun renderDialog(state: SessionBackingViewModel.State) {
        if (state.isDialogOpen && dialog == null) {
            dialog = SignInDialog(
                onGoogle = { startGoogleSignIn() },
                onGitHub = { startGitHubSignIn() },
                onDismiss = { viewModel.onSignInDialogDismissed() },
            ).also { it.mount(dialogHost) }
        } else if (!state.isDialogOpen && dialog != null) {
            dialog?.dismiss()
            dialog = null
        }
        dialog?.render(state)
    }

    /**
     * Open Google's popup via `initCodeClient`.
     *
     * `ux_mode: 'popup'` with a `callback` — deliberately no `redirect_uri`.
     * Google's JS reference: when the mode is popup, `redirect_uri` "is ignored
     * and defaults to the origin of the page that calls `initCodeClient`". The
     * server sends that same origin in the token exchange; see
     * `exchangeGoogleCode`.
     */
    private fun startGoogleSignIn() {
        val clientId = currentGoogleClientId
        if (clientId == null) {
            viewModel.onSignInFailed("Google sign-in is not configured.")
            return
        }
        val google = window.asDynamic().google
        // The SDK is loaded from accounts.google.com by a script tag in
        // index.html. If the network ate it, say so — the alternative is a
        // button that does nothing, which reads as our bug.
        if (google == undefined || google == null) {
            viewModel.onSignInFailed("Google's sign-in script did not load.")
            return
        }

        viewModel.onSignInStarted()
        // Build and call inside a runCatching: initCodeClient throws
        // synchronously for a malformed client id, and requestCode() can fail
        // without ever reaching either callback below — most notably when
        // Google declines the origin outright. Letting that escape would leave
        // isBusy stuck true and the button disabled forever, which is precisely
        // the "sign-in that silently does nothing" this whole stage is about
        // being able to see.
        val started = runCatching {
            if (googleCodeClient == null) {
                // Build the options object by plain assignment. NOT with
                // .also/.apply: on a `dynamic`, Kotlin dispatches every member
                // call at runtime, so `.also(…)` compiles to a literal
                // JavaScript method lookup on `{}` — which has no such method.
                // It type-checks and then dies on click with
                // "{}.also is not a function". Extension functions do not apply
                // to dynamic values; this is the idiom that works.
                val options: dynamic = js("({})")
                options.client_id = clientId
                options.scope = "openid email profile"
                options.ux_mode = "popup"
                options.callback = { response: dynamic ->
                    val code = response?.code as? String
                    if (code.isNullOrBlank()) {
                        // Includes the user closing the popup, which is not an
                        // error worth shouting about.
                        println("SignIn: Google popup returned no code")
                        viewModel.onSignInFailed(null)
                    } else {
                        viewModel.onGoogleCodeReceived(code)
                    }
                    Unit
                }
                options.error_callback = { error: dynamic ->
                    // GIS reports "popup_closed" for a deliberate close, and
                    // "popup_failed_to_open" for the case this stage exists to
                    // catch.
                    val type = error?.type as? String
                    println("SignIn: Google popup error: $type")
                    viewModel.onSignInFailed(
                        when (type) {
                            "popup_closed", null -> null
                            "popup_failed_to_open" -> "Google's sign-in window could not open."
                            else -> "Google sign-in did not start ($type)."
                        },
                    )
                    Unit
                }
                googleCodeClient = google.accounts.oauth2.initCodeClient(options)
            }
            googleCodeClient.requestCode()
        }
        started.onFailure { t ->
            println("SignIn: requestCode threw: ${t.message}")
            viewModel.onSignInFailed("Google would not open the sign-in window. See the console.")
        }
    }

    /**
     * Open GitHub's popup at our own start route.
     *
     * GitHub publishes no sign-in SDK, so this is the flow by hand: the popup
     * goes to our server, which redirects it to GitHub with `state` and a PKCE
     * challenge; GitHub returns it to our callback, which sets the cookie and
     * posts a message back here.
     *
     * The popup is opened *synchronously in the click handler*. Doing it after
     * an await — say, fetching a URL from the server first — puts the
     * `window.open` outside the user-gesture window and every popup blocker
     * kills it. That failure looks exactly like nothing happening.
     */
    private fun startGitHubSignIn() {
        val existing = githubPopup
        if (existing != null && existing.closed == false) {
            existing.focus()
            return
        }
        viewModel.onSignInStarted()
        githubPopup = window.open(
            ApiRoutes.AUTH_GITHUB_START,
            "lunicle-github-signin",
            "width=520,height=700,menubar=no,toolbar=no",
        )
        if (githubPopup == null) {
            viewModel.onSignInFailed("Your browser blocked the sign-in window.")
        }
    }

    /**
     * Listen for the callback page's `postMessage`.
     *
     * The origin check is the security boundary: any page can post to this
     * window, so a message that isn't from us is ignored rather than trusted.
     * Even then the message is only a *hint* — the view model responds by asking
     * the server who we are, so a forged "ok" buys nothing but a wasted fetch.
     */
    private fun listenForGitHubPopup() {
        window.addEventListener("message", { event: Event ->
            val message = event as? MessageEvent ?: return@addEventListener
            if (message.origin != window.location.origin) return@addEventListener
            val data = message.data.asDynamic()
            if (data == null || data.lunicle != "github-signin") return@addEventListener

            githubPopup = null
            if (data.ok == true) {
                viewModel.onGitHubPopupSucceeded()
            } else {
                viewModel.onSignInFailed(data.error as? String ?: "GitHub sign-in did not complete.")
            }
        })
    }

    /** The client id from the last rendered state. */
    private var currentGoogleClientId: String? = null

    /** Keep the client id the popup will need, without the view holding state. */
    fun onState(state: SessionBackingViewModel.State) {
        currentGoogleClientId = state.googleClientId
        render(state)
    }
}
