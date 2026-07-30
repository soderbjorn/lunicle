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
import se.soderbjorn.lunicle.clientserver.AddressStanding
import se.soderbjorn.lunicle.clientserver.ImpersonationTarget

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
     * The mandatory landing gate while it is up (LNL-115).
     *
     * A modal with a single Sign in button and no way out but signing in: on a
     * deployment that requires sign-in, a signed-out visitor meets this instead of
     * the app. It lives in this view rather than the shell because it is a sign-in
     * surface like the picker and the corner — its button opens the very same
     * [startSignIn] path, so the picker (or the Google popup) stacks over it and,
     * the moment the session lands, the gate tears itself down. See
     * [renderSignInGate].
     */
    private var signInGate: Modal? = null

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

    /** The impersonation submenu's rows, rebuilt only when the address list changes. */
    private lateinit var impersonateItem: HTMLElement
    private lateinit var impersonateSubmenu: HTMLElement
    private lateinit var stopImpersonatingButton: HTMLButtonElement
    private var renderedTargets: List<ImpersonationTarget> = emptyList()

    /** The `Any address…` prompt while it is up (LNL-197). */
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

        // "Impersonate ▸" and the submenu that folds out of it. The submenu is a
        // child of the item, not a sibling: it opens on hover, and CSS hover only
        // survives the pointer travelling from the item to the submenu if the
        // submenu is inside the thing being hovered.
        impersonateSubmenu = element("div", "account-submenu $MENU_PANEL_CLASS")
        impersonateSubmenu.setAttribute("role", "menu")
        impersonateItem = element("div", "$MENU_ITEM_CLASS account-menu-parent")
        impersonateItem.setAttribute("role", "menuitem")
        impersonateItem.setAttribute("aria-haspopup", "menu")
        impersonateItem.children(
            element("span", "account-menu-label dt-hover-menu-label", "Impersonate"),
            element("span", "account-menu-arrow", "\u25B8"),
            impersonateSubmenu,
        )

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

        root.children(signInButton, accountButton, menuElement)
        // The corner heals itself on the way to being used (LNL-208).
        //
        // This menu is unusual twice over: it is built once and left in the
        // document for the life of the page, and it wears the toolkit's surface
        // class (see MENU_PANEL_CLASS) so it paints like every other menu in the
        // app. That combination made it collateral damage — the toolkit's own
        // topbar dropdown enforced "one menu at a time" by removing every
        // `.dt-hover-menu` in the document, so one hover over the top bar's "+"
        // deleted this panel and the account corner never opened again. Nothing
        // said so: `render` kept writing rows into an element attached to
        // nothing, and the only symptom was a hover that did nothing.
        //
        // The toolkit no longer does that (it closes menus through their owners
        // now), but this corner cannot verify which toolkit build it is running
        // against — Lunicle deploys against the published artifact in libs-repo,
        // not the source tree — and the same trap is open to anything else that
        // ever sweeps the document for a shared class. So the menu is re-attached
        // on the way in, where "attached" is exactly what is about to matter.
        // Cheap (a parent comparison per hover), and it restores the panel while
        // the pointer is still inside `root`, so the `:hover` rule that reveals
        // it is already true and the menu appears in the same gesture.
        root.addEventListener("mouseenter", {
            if (menuElement.parentElement !== root) root.appendChild(menuElement)
        })
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

        val signedIn = state.user != null
        // Impersonating a signed-out visitor: the effective account is null, so
        // there is no name and the app shows the public view — but the corner must
        // stay, or "Stop impersonating" vanishes with it and the admin is stranded
        // in the borrowed identity (LNL-103). So the corner appears whenever there
        // is a menu to show: a real account, or this preview.
        val impersonatingSignedOut = state.isImpersonating && !signedIn
        val showAccount = signedIn || impersonatingSignedOut

        nameElement.setTextIfChanged(
            when {
                signedIn -> state.displayName ?: ""
                impersonatingSignedOut -> "Signed-out visitor"
                else -> ""
            },
        )
        // The provider is the tooltip now that the corner shows the bare name.
        // Both providers can supply the same display name, so this is the only
        // thing that answers "which of my two accounts is this?".
        accountButton.title = when {
            signedIn -> state.greeting ?: ""
            impersonatingSignedOut -> "Previewing the signed-out view"
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
        // Hidden while impersonating (LNL-101): signing out here would mean the
        // admin's real session, not the borrowed identity, and the borrowed
        // identity is what the menu is showing — the way back out is "Stop
        // impersonating" below, not "Sign out". It returns when impersonation ends.
        signOutButton.visible(!state.isImpersonating, displayValue = "block")
        signOutButton.disabled = state.isBusy

        // "Impersonate" and "Stop impersonating" are the same slot, never both.
        // Both hang off canImpersonate — the REAL user owning the instance (LNL-197)
        // — and not off user.isSysAdmin, which is false for the whole time somebody is
        // impersonating and would take "Stop impersonating" away exactly when it
        // is the only thing needed. See SessionBackingViewModel.State.
        impersonateItem.visible(state.canImpersonate && !state.isImpersonating, displayValue = "flex")
        stopImpersonatingButton.visible(state.canImpersonate && state.isImpersonating, displayValue = "block")
        stopImpersonatingButton.disabled = state.isBusy
        renderImpersonatableAddresses(state)
        renderAddressDialog(state)

        // A deployment with no credentials renders no sign-in at all, rather
        // than a button that cannot work. The server decides this, not the
        // bundle — only it knows which variables it was given. Not shown while
        // previewing the signed-out view either: the corner is already given over
        // to the account menu carrying "Stop impersonating" (LNL-103).
        signInButton.visible(!showAccount && state.isSignInAvailable, displayValue = "inline-flex")
        signInButton.disabled = state.isBusy

        renderSignInGate(state)
        renderSignInPicker(state)
        renderAlert(state)
    }

    /**
     * Raise or dismiss the landing gate to match the state (LNL-115).
     *
     * Keyed on presence, like the picker and the alert: the view model decides
     * whether the gate belongs on screen ([SessionBackingViewModel.State.isSignInGateShown]),
     * this only builds it when it should appear and tears it down when it should
     * not — which is what makes a completed sign-in close it without a line here
     * mentioning sign-in at all. Built once and left up; there is nothing inside it
     * that goes stale, so unlike the picker it is not rebuilt per showing.
     */
    private fun renderSignInGate(state: SessionBackingViewModel.State) {
        if (state.isSignInGateShown && signInGate == null) {
            signInGate = buildSignInGate(state)
        } else if (!state.isSignInGateShown && signInGate != null) {
            signInGate?.dismiss()
            signInGate = null
        }
    }

    /**
     * Build the landing gate: a brand mark, a line of explanation, and one big Sign
     * in button — nothing else, and no way to dismiss it.
     *
     * `onDismiss = {}` so Escape does nothing (the backdrop already swallows
     * clicks), and no footer button: the only way past it is to sign in, which is
     * the whole point of a required-sign-in deployment. The button hands off to
     * [startSignIn], the same path the top-bar button takes — so a two-method
     * deployment gets the picker over the gate and a one-method one goes straight to
     * it. When the deployment somehow requires sign-in but offers no method to
     * perform it (a misconfiguration), a line says so rather than a dead button.
     */
    private fun buildSignInGate(state: SessionBackingViewModel.State): Modal {
        val gate = Modal(
            title = "Sign in to continue",
            onDismiss = {},
            panelClass = "modal-narrow modal-signin",
        )
        val methods = element("div", "signin-methods")
        brandLogoSvg?.let {
            methods.appendChild(brandLogo(it).also { el -> el.className += " signin-brand-logo" })
        }
        methods.appendChild(
            element("p", "signin-hint", "This workspace requires you to sign in before you can use it."),
        )
        if (state.isSignInAvailable) {
            // "Sign in…" with the ellipsis, matching the top-bar button (this hands
            // off to the picker or an OAuth redirect, it does not sign you in on the
            // spot) — the gate had the bare label alone (LNL-153).
            methods.appendChild(button("Sign in…", "btn signin-provider") { startSignIn() })
        } else {
            // Required, but no provider configured to satisfy it. Nothing this
            // surface can do about it, so it says the true thing rather than
            // offering a button that cannot work.
            methods.appendChild(
                element("p", "signin-hint", "No sign-in method is configured on this server. Ask an administrator."),
            )
        }
        gate.body.appendChild(methods)
        gate.mount(dialogHost)
        return gate
    }

    /**
     * Where "Sign in…" goes.
     *
     * One method configured means no picker: a modal offering a single option is a
     * click charged for nothing. Two means the picker. Zero cannot reach here —
     * the button is not rendered at all in that case, see `render`.
     */
    private fun startSignIn() {
        val state = lastState ?: return
        when {
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
     * Fill the impersonation submenu.
     *
     * Rebuilt only when the addresses change, like the project picker: this runs on
     * every session emission, and replacing the rows under a pointer that is
     * hovering one of them makes the submenu flicker and lose the hover. Compared
     * whole rather than by address, so a row whose *standing* changed — somebody
     * finally signed in — redraws too.
     *
     * ── Addresses, not names (LNL-197) ─────────────────────────────────────
     *
     * Each row is an address and what it resolves to, because that is what the
     * permission model keys on: staff-ness is derived from the address, somebody can
     * hold rungs before an account exists, and an audience row is a statement about
     * what an address *is*. A list of display names is a list of the wrong thing.
     *
     * The owner's own row is rendered and disabled rather than omitted, so the list
     * matches the account directory they are looking at — and "become myself" is
     * spelled "Stop impersonating", which is a different item.
     */
    private fun renderImpersonatableAddresses(state: SessionBackingViewModel.State) {
        val targets = state.impersonatableAddresses
        if (targets == renderedTargets) return
        renderedTargets = targets
        impersonateSubmenu.clear()
        // A fixed choice, always first and always offered: see the app as a
        // signed-out visitor does — no account at all — rather than as any named
        // address (LNL-103). It stands even when there are no accounts to pick,
        // so the submenu is never truly empty.
        impersonateSubmenu.appendChild(
            button("Signed-out visitor", MENU_ITEM_CLASS) {
                viewModel.onImpersonateSignedOutTapped()
            } as HTMLButtonElement,
        )
        targets.forEach { target ->
            val row = button("${target.email} — ${target.standing.label}", MENU_ITEM_CLASS) {
                viewModel.onImpersonateTapped(target.email)
            } as HTMLButtonElement
            if (target.standing == AddressStanding.SELF) {
                row.disabled = true
                row.title = "This is you."
            }
            impersonateSubmenu.appendChild(row)
        }
        // Last, because it is the general case behind the list rather than one of its
        // rows, and because the three states worth reaching this way have no account
        // and so appear nowhere above (LNL-197). See ImpersonateAddressDialog.
        impersonateSubmenu.appendChild(
            button("Any address…", MENU_ITEM_CLASS) {
                viewModel.onImpersonateAddressPromptOpened()
            } as HTMLButtonElement,
        )
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
