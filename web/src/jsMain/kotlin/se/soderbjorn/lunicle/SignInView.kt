/**
 * The Kotlin/JS DOM view for signing in.
 *
 * This is the one view in the project that is allowed to know something the
 * backing view model doesn't: **how to open a popup**. That knowledge cannot be
 * shared — Google's SDK is a browser global and GitHub's flow is a
 * `window.open` — so it stops here, and everything downstream of "we have a
 * code" happens in [SessionBackingViewModel], which an iOS client will reuse
 * unchanged.
 *
 * Why a popup at all: both providers refuse to be framed
 * (`X-Frame-Options: DENY`, and GitHub adds `frame-ancestors 'none'`), and
 * Lunicle lives in an iframe on lunamux.dev. There is no header we can set to
 * change that. See docs/oauth-instructions.html.
 *
 * @see SessionBackingViewModel
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLParagraphElement
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event
import se.soderbjorn.lunicle.client.viewmodel.SessionBackingViewModel
import se.soderbjorn.lunicle.clientserver.ApiRoutes

/**
 * Renders the sign-in row — buttons when signed out, a name when signed in.
 *
 * @param viewModel the shared backing view model; the view's only collaborator.
 */
class SignInView(
    private val viewModel: SessionBackingViewModel,
) {
    private lateinit var root: HTMLElement
    private lateinit var greetingElement: HTMLParagraphElement
    private lateinit var googleButton: HTMLButtonElement
    private lateinit var githubButton: HTMLButtonElement
    private lateinit var signOutButton: HTMLButtonElement
    private lateinit var errorElement: HTMLParagraphElement

    /** Google's code client, built lazily — the SDK may not have loaded yet. */
    private var googleCodeClient: dynamic = null

    /** The GitHub popup, held so a second click focuses it rather than opening another. */
    private var githubPopup: dynamic = null

    /**
     * Build the row and attach it to [host].
     *
     * @param host the element to append into.
     */
    fun mount(host: HTMLElement) {
        root = document.createElement("section") as HTMLElement
        root.className = "signin"

        greetingElement = document.createElement("p") as HTMLParagraphElement
        greetingElement.className = "signin-greeting"
        // The name is the whole point of this stage, and it appears without any
        // surrounding text changing — so a screen reader would announce nothing.
        greetingElement.setAttribute("aria-live", "polite")

        googleButton = providerButton("Sign in with Google") { startGoogleSignIn() }
        githubButton = providerButton("Sign in with GitHub") { startGitHubSignIn() }

        signOutButton = document.createElement("button") as HTMLButtonElement
        signOutButton.className = "signin-btn signin-btn-quiet"
        signOutButton.type = "button"
        signOutButton.textContent = "Sign out"
        signOutButton.onclick = { viewModel.onSignOutTapped() }

        errorElement = document.createElement("p") as HTMLParagraphElement
        errorElement.className = "signin-error"
        errorElement.setAttribute("role", "status")

        root.appendChild(greetingElement)
        root.appendChild(googleButton)
        root.appendChild(githubButton)
        root.appendChild(signOutButton)
        root.appendChild(errorElement)
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
        greetingElement.textContent = state.greeting ?: ""
        greetingElement.style.display = if (signedIn) "block" else "none"

        googleButton.style.display = if (!signedIn && state.isGoogleAvailable) "inline-block" else "none"
        githubButton.style.display = if (!signedIn && state.isGitHubAvailable) "inline-block" else "none"
        signOutButton.style.display = if (signedIn) "inline-block" else "none"

        googleButton.disabled = state.isBusy
        githubButton.disabled = state.isBusy
        signOutButton.disabled = state.isBusy

        // A deployment with no credentials renders no sign-in at all, rather
        // than buttons that cannot work. The server decides this, not the
        // bundle — only it knows which variables it was given.
        if (state.isLoaded && !state.isSignInAvailable && !signedIn) {
            greetingElement.style.display = "block"
            greetingElement.textContent = "Sign-in is not configured on this server."
        }

        errorElement.textContent = state.errorMessage ?: ""
    }

    private fun providerButton(label: String, onClick: () -> Unit): HTMLButtonElement {
        val button = document.createElement("button") as HTMLButtonElement
        button.className = "signin-btn"
        button.type = "button"
        button.textContent = label
        button.onclick = { onClick() }
        return button
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
