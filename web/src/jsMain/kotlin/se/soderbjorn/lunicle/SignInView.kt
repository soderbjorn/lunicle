/**
 * The account corner of the top bar, the picker it opens, and the popup behind
 * one branch of that.
 *
 * Signed out it is one "Sign in…" button. Where that leads depends on what the
 * deployment can do: with two methods configured it opens [SignInPickerDialog],
 * with only Google it goes straight to the popup. Signed in it is the user's name
 * and the profile mark: pressing them opens the settings pane at its You tab
 * (LNL-193 — this used to be a profile modal that this file built), and hovering
 * them opens a menu holding sign-out.
 *
 * ── The one privilege this view has, and what is *not* covered by it ────────
 *
 * This is the one view in the project allowed to know something the backing view
 * model doesn't: **how to open a popup**. That knowledge cannot be shared —
 * Google's SDK is a browser global — so it stops here, and everything downstream
 * of "we have a code" happens in [SessionBackingViewModel], which an iOS client
 * reuses unchanged.
 *
 * That exception covers the Google branch and nothing else. LNL-74's e-mail
 * sign-in opens no popup — it is a form and two HTTP calls — so **all of it lives
 * in the view model**, beside `onGoogleCodeReceived`, and this file's share of it
 * is markup. [SignInPickerDialog] holds the Google button's click only so it can
 * hand it straight back here; the popup does not travel into a second file.
 *
 * Why a popup at all: Google's consent screen refuses to be framed
 * (`X-Frame-Options: DENY`, verified) and Lunicle lives in an iframe on
 * lunamux.dev. There is no header we can set to change that. See
 * docs/oauth-instructions.html.
 *
 * @see SessionBackingViewModel
 * @see SignInPickerDialog
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.client.viewmodel.SessionBackingViewModel

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
     * The account corner was pressed: open the settings pane at You (LNL-193).
     *
     * A callback rather than a dialog this view builds. It used to build the
     * profile modal itself, which meant it also had to hold a storage repository, a
     * Connections view model, a scope for it, and the workspace's "restore default
     * layout" — four collaborators a sign-in corner has no business with. Settings
     * is a pane in the workspace now, so this view's whole share of it is reporting
     * the press.
     */
    private val onOpenProfile: () -> Unit = {},
) {
    private lateinit var root: HTMLElement
    private lateinit var signInButton: HTMLButtonElement
    private lateinit var accountButton: HTMLButtonElement
    private lateinit var nameElement: HTMLElement
    private lateinit var menuElement: HTMLElement
    private lateinit var signOutButton: HTMLButtonElement


    /** Google's code client, built lazily — the SDK may not have loaded yet. */
    private var googleCodeClient: dynamic = null

    /** The failure alert while it is up, and the message it is showing. */
    private var alert: AlertDialog? = null
    private var alertMessage: String? = null

    /** The sign-in method picker while it is up. */
    private var signInPicker: SignInPickerDialog? = null

    /**
     * The deployment's brand logo SVG (LNL-110), shown atop the sign-in picker.
     * Set once at boot after the brand loads (see main.kt); null ⇒ the picker
     * carries no logo, exactly as before. Read when a picker is built fresh per
     * opening, so a value set after construction still reaches it.
     */
    var brandLogoSvg: String? = null

    /**
     * The deployment's Google Workspace domain (LNL-125), or null. Set once at boot
     * after the brand loads (see main.kt), like [brandLogoSvg]. When set, it is
     * passed as `hd` to `initCodeClient` so the account chooser is pre-filtered to
     * that one domain instead of offering every signed-in Google account. Null
     * leaves the chooser open — the unbranded default. The hint is UX only; the
     * server's `exchangeGoogleCode` is what actually refuses a wrong domain.
     */
    var googleHostedDomain: String? = null

    /** The account menu's two impersonation controls, never both at once. */
    private lateinit var impersonateItem: HTMLButtonElement
    private lateinit var stopImpersonatingButton: HTMLButtonElement

    /**
     * The strip above the app while an impersonation is armed, and its Cancel.
     *
     * Only ever visible to a caller who is **signed out** — the one state in which
     * nothing else on screen says anything is going on, because everything else is
     * deliberately rendering exactly what a stranger sees.
     */
    private lateinit var armedStrip: HTMLElement

    /** The address dialog while it is up. */
    private var addressDialog: ImpersonateAddressDialog? = null

    /**
     * Build the corner and attach it to [host].
     *
     * @param host the element to append into — the top bar's far right.
     */
    fun mount(host: HTMLElement) {
        root = element("div", "account")

        // Where "Sign in…" leads depends on what the deployment can actually do.
        //
        // This used to say "Google is the only provider now, so this opens its
        // popup directly rather than a one-option picker", and that reasoning
        // survives — it is conditional now. With two methods available it opens
        // the picker; with one it still goes straight there, because a picker with
        // a single option is a click charged for nothing. The view model owns the
        // decision (`hasSignInChoice`), not this.
        //
        // Errors still surface through the same AlertDialog — see renderAlert.
        signInButton = button("Sign in…", "btn account-signin") { startSignIn() } as HTMLButtonElement

        // Name and mark are one button, not a label beside an icon button. The
        // name is the bigger target and reads as the thing you would click, so a
        // click on it doing nothing is the kind of dead spot nobody reports and
        // everybody notices.
        nameElement = element("span", "account-name")
        accountButton = (document.createElement("button") as HTMLButtonElement).apply {
            className = "account-btn"
            type = "button"
            onclick = { onOpenProfile() }
        }
        accountButton.children(nameElement, profileIcon())
        accountButton.setAttribute("aria-haspopup", "menu")

        signOutButton = button("Sign out", MENU_ITEM_CLASS) { viewModel.onSignOutTapped() } as HTMLButtonElement

        // One item, no submenu. There is no list of addresses to fold out any more:
        // the owner is signed out first and then types whichever address they want
        // at a genuine sign-in, so the menu's whole share of this is starting it.
        impersonateItem =
            button("Impersonate…", MENU_ITEM_CLASS) { confirmArm() } as HTMLButtonElement

        stopImpersonatingButton =
            button("Stop impersonating", MENU_ITEM_CLASS) { viewModel.onStopImpersonatingTapped() } as HTMLButtonElement

        menuElement = element("div", "account-menu $MENU_PANEL_CLASS")
        menuElement.setAttribute("role", "menu")
        // Sign out first, then the impersonation control — which of the two
        // appears is decided in render(), never both.
        menuElement.children(signOutButton, impersonateItem, stopImpersonatingButton)

        // The name appears without any surrounding text changing, so a screen
        // reader would announce nothing at all when a sign-in lands. The corner
        // has no visible label to carry that, so the live region is the name.
        nameElement.setAttribute("aria-live", "polite")

        // The armed strip, mounted on <body> rather than on this view's own root.
        //
        // A child of the account corner is where it belongs by ownership and the one
        // place it cannot work: the top bar is its own stacking context, so a
        // `position: fixed` strip inside it is painted *within* that context — and
        // the signed-out landing surface this strip exists to annotate sits at
        // z-index 9000, on top. It rendered, at the right size, in the right colour,
        // underneath. Hoisting it to <body> puts it in the root stacking context
        // where its own z-index means what it says.
        armedStrip = element("div", "impersonation-armed-strip")
        armedStrip.children(
            element("span", "impersonation-armed-text", "Impersonation armed — sign in as anyone."),
            button("Cancel", "btn impersonation-armed-cancel") {
                viewModel.onStopImpersonatingTapped()
            },
        )
        armedStrip.style.display = "none"
        document.body?.appendChild(armedStrip)

        root.children(signInButton, accountButton, menuElement)
        host.appendChild(root)
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

        // A probing owner is genuinely signed in as somebody, so the corner is the
        // ordinary signed-in corner carrying that person's real name — which is the
        // point: it is what they would see. The impersonation is said separately, by
        // the pill and the tint, which cannot be mistaken for the account's own
        // label and cannot be dismissed.
        val signedIn = state.user != null
        val showAccount = signedIn

        nameElement.setTextIfChanged(if (signedIn) state.displayName ?: "" else "")
        // The provider is the tooltip now that the corner shows the bare name.
        // Both providers can supply the same display name, so this is the only
        // thing that answers "which of my two accounts is this?".
        accountButton.title = when {
            state.isImpersonating -> "You are signed in as this account through an impersonation"
            signedIn -> state.greeting ?: ""
            else -> ""
        }
        accountButton.visible(showAccount, displayValue = "inline-flex")

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
        root.classList.toggle("account-signed-in", showAccount)

        // The marker, and it is a CLASS for the reason the line above is one: the
        // stylesheet owns what a tinted frame and a corner pill look like, and an
        // inline style set from here would beat every rule it was meant to cooperate
        // with. It is toggled off the server's answer on every session emission, so
        // it survives a hard reload and there is nothing on screen that dismisses it.
        root.classList.toggle("account-impersonating", state.isImpersonating)
        document.body?.classList?.toggle("app-impersonating", state.isImpersonating)

        // Hidden while impersonating: "Sign out" here would end the probe session
        // and leave the browser holding a live grant with nothing to go back to.
        // The way out is "Stop impersonating", which restores the owner.
        signOutButton.visible(!state.isImpersonating, displayValue = "block")
        signOutButton.disabled = state.isBusy

        // "Impersonate…" and "Stop impersonating" are the same slot, never both.
        //
        // The first hangs off canImpersonate, which already carries BOTH terms — the
        // caller owns the instance and the deployment has the feature switched on —
        // so an unarmed instance offers nothing to anybody, the owner included.
        //
        // The second hangs off isImpersonating alone, deliberately: while probing,
        // the account being worn owns nothing and canImpersonate is false. Gating
        // the way out on the entitlement would take it away exactly when it is the
        // only thing needed. See SessionBackingViewModel.State.
        impersonateItem.visible(state.canImpersonate && !state.isImpersonating, displayValue = "block")
        impersonateItem.disabled = state.isBusy
        stopImpersonatingButton.visible(state.isImpersonating, displayValue = "block")
        stopImpersonatingButton.disabled = state.isBusy

        // The armed state belongs to a SIGNED-OUT browser, so it is the one thing on
        // screen saying anything is going on — everything else is rendering what a
        // stranger sees, which is itself part of what is being checked.
        armedStrip.visible(state.isImpersonationArmed && !signedIn, displayValue = "flex")

        renderAddressDialog(state)

        // A deployment with no credentials renders no sign-in at all, rather
        // than a button that cannot work. The server decides this, not the
        // bundle — only it knows which variables it was given. Not shown while
        // previewing the signed-out view either: the corner is already given over
        // to the account menu carrying "Stop impersonating" (LNL-103).
        signInButton.visible(!showAccount && state.isSignInAvailable, displayValue = "inline-flex")
        signInButton.disabled = state.isBusy

        renderSignInPicker(state)
        renderAlert(state)
    }

    /**
     * Where "Sign in…" goes.
     *
     * One method configured means no picker: a modal offering a single option is a
     * click charged for nothing. Two means the picker. Zero cannot reach here —
     * the button is not rendered at all in that case, see `render`.
     *
     * Public because the corner is no longer the only way in: the empty-tab surface
     * a signed-out visitor lands on carries its own button (see main.kt), and it has
     * to open the very same door rather than a second one that drifts from this. A
     * no-op before the first session response, which is the same beat the corner
     * button is not rendered for.
     */
    fun startSignIn() {
        val state = lastState ?: return
        when {
            // Armed wins over everything. This browser holds a grant, so the button
            // that would ordinarily open Google's popup or the code field opens the
            // impersonation dialog instead — one address, one button, no mail and no
            // code to redeem. It is the same button on purpose: signing in as
            // somebody should go through the door a sign-in goes through.
            state.isImpersonationArmed -> viewModel.onImpersonateAddressPromptOpened()
            state.hasSignInChoice -> viewModel.onSignInPickerOpened()
            state.isGoogleAvailable -> startGoogleSignIn()
            state.isEmailSignInAvailable -> viewModel.onSignInPickerOpened()
            else -> Unit
        }
    }

    /**
     * Open or close the picker to match the state.
     *
     * Built fresh per opening and torn down on dismissal, the shape every modal
     * this file raises uses — and for a simple reason: the dialog holds a half-typed
     * address and a half-typed code, and an instance kept across openings would show
     * the last visitor's attempt to the next one.
     *
     * Mounted on `dialogHost` so it stacks with the other modals and Modal's
     * topmost-wins Escape handling keeps working.
     */
    private fun renderSignInPicker(state: SessionBackingViewModel.State) {
        if (state.isSignInPickerOpen && signInPicker == null) {
            signInPicker = SignInPickerDialog(
                viewModel = viewModel,
                // The popup stays this view's business — it is the one piece of
                // platform knowledge that cannot be shared, so it does not travel
                // into a second file. See SignInPickerDialog's preamble.
                onGoogleTapped = { startGoogleSignIn() },
                onDismiss = { viewModel.onSignInPickerDismissed() },
                brandLogoSvg = brandLogoSvg,
            ).also { it.mount(dialogHost) }
        } else if (!state.isSignInPickerOpen && signInPicker != null) {
            signInPicker?.dismiss()
            signInPicker = null
        }
        signInPicker?.render(state)
    }

    /**
     * Confirm before arming, because arming signs the owner out.
     *
     * The one place in the product where pressing a menu item ends your session, so
     * it says so before it does it. Not a nicety: without the warning, an owner who
     * meant to look at a submenu finds themselves signed out of the instance they
     * run, with no obvious way back beyond signing in again — and on a deployment
     * whose sign-in is a Google popup that is a genuine interruption.
     */
    private fun confirmArm() {
        var dialog: ConfirmDialog? = null
        dialog = ConfirmDialog(
            title = "Impersonate somebody",
            message = "You will be signed out, and can then sign in as any address — for real. " +
                "An address with no account here will get one, and anything you write will be theirs. " +
                "Stop impersonating puts you back.",
            // "Sign me out" rather than "OK", because being signed out is the part
            // somebody would otherwise not expect from a menu item.
            destructiveLabel = "Sign me out and arm it",
            onConfirm = {
                dialog?.dismiss()
                viewModel.onArmImpersonationTapped()
            },
            onCancel = { dialog?.dismiss() },
        ).also { it.mount(dialogHost) }
    }

    /**
     * Raise or dismiss the `Any address…` prompt to match the state.
     *
     * Keyed on presence like the picker and the gate: the view model decides whether
     * it belongs on screen, and this only builds and tears down — which is what makes
     * a completed impersonation close it without a line here mentioning impersonation.
     * Built fresh per opening for [renderSignInPicker]'s reason: it holds a half-typed
     * address, and an instance kept across openings would show the last one.
     */
    private fun renderAddressDialog(state: SessionBackingViewModel.State) {
        if (state.isImpersonateAddressPromptOpen && addressDialog == null) {
            addressDialog = ImpersonateAddressDialog(
                viewModel = viewModel,
                onDismiss = { viewModel.onImpersonateAddressPromptDismissed() },
            ).also { it.mount(dialogHost) }
        } else if (!state.isImpersonateAddressPromptOpen && addressDialog != null) {
            addressDialog?.dismiss()
            addressDialog = null
        }
        addressDialog?.render(state)
    }


    /**
     * Put a sign-in failure up as a modal, or take it down.
     *
     * Covers everything the sign-in flow can fail at now that there is no picker
     * to host an inline error: the Google popup declining to open, the exchange
     * failing, a sign-out that failed, a session fetch that did.
     *
     * Keyed on the message rather than on "is there one", for the reason
     * Dialogs.renderAlert gives: two different failures in a row would otherwise
     * leave the first one on screen describing the second.
     */
    private fun renderAlert(state: SessionBackingViewModel.State) {
        val message = state.errorMessage
        if (message == alertMessage) return
        alert?.dismiss()
        alertMessage = message
        alert = message?.let {
            AlertDialog(
                title = "Something went wrong",
                message = it,
                // Clears the error and the busy flag, which is exactly what
                // dismissing the alert should do.
                onDismiss = { viewModel.onSignInErrorDismissed() },
            ).also { dialog -> dialog.mount(dialogHost) }
        }
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
                // Always force the account picker. Without this, Google silently
                // reuses whatever single session the browser already has — so
                // someone whose active session is the wrong account (a personal
                // Gmail, the wrong Workspace login) is authenticated as that
                // account with no chance to correct it, and on a domain-pinned
                // deployment is thrown straight at the failure dialog. Forcing the
                // chooser costs single-account users one extra click and lets
                // everyone else pick the right account.
                options.select_account = true
                // Pin the chooser to one Workspace domain on a branded deployment
                // (LNL-125). Only set when configured; `hd` is a *hint* that
                // pre-selects/filters the picker to this domain. Absent it, the
                // chooser lists every account, as before.
                googleHostedDomain?.let { options.hd = it }
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

    /** The client id from the last rendered state. */
    private var currentGoogleClientId: String? = null

    /**
     * The last state rendered, so a click can ask what it may do.
     *
     * Kept because `startSignIn` has to know which methods are configured, and a
     * button's `onclick` fires long after `render` returned. The view still holds
     * no *decision* — every question it asks of this is answered by a property the
     * view model computed. See [SessionBackingViewModel.State.hasSignInChoice].
     */
    private var lastState: SessionBackingViewModel.State? = null

    /** Keep the client id the popup will need, without the view holding state. */
    fun onState(state: SessionBackingViewModel.State) {
        currentGoogleClientId = state.googleClientId
        lastState = state
        render(state)
    }

    private companion object {
        /**
         * The account corner's menu is the toolkit's menu — the same panel the
         * shell's "+" and the board's dropdowns drop, marked as raised from the
         * chrome because the corner is chrome (LNL-168).
         *
         * It kept a private copy of that panel longer than anything else in the
         * app: a 4px box of 8px rows with a green-tinted hover, opening a few
         * pixels from a "+" whose menu was a 9px box of 30px rows with an accent
         * one. Both classes stay on every element — the toolkit's for the paint,
         * this app's for the anchoring and the CSS-hover the corner opens on.
         */
        const val MENU_PANEL_CLASS = "dt-hover-menu dt-menu-chrome"
        const val MENU_ITEM_CLASS = "account-menu-item dt-hover-menu-item"
    }
}
