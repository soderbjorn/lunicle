/**
 * The profile modal, opened by clicking the account corner.
 *
 * It holds the **Connections** section: whether AI agents may act as you, the
 * server URL to paste into one, and the list of what is currently connected.
 *
 * What it is *not* is the account menu. Sign out and impersonation live on hover
 * — see SignInView — and must not be duplicated here; two ways to sign out is two
 * things to keep in step.
 *
 * ── The one thing this dialog must never grow ───────────────────────────────
 *
 * A "Connect to Claude Code" button. The OAuth flow is always initiated by the
 * **agent**: the agent has to be the OAuth client, hold the PKCE verifier, and
 * receive the authorization code at its own redirect URI. This dialog only ever
 * *displays the URL and lists the result*. A connect button here cannot work, and
 * proposing one means someone has misunderstood which direction the protocol runs
 * in. See the server's McpRoutes.
 *
 * A dumb renderer, like every view here: every string and every decision comes
 * from [ConnectionsBackingViewModel], including the copy on the toggle and the
 * confirmation's wording.
 *
 * @see ConnectionsBackingViewModel
 * @see SignInView
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import se.soderbjorn.lunicle.client.viewmodel.CONNECTIONS_TITLE
import se.soderbjorn.lunicle.client.viewmodel.ConnectionsBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.ENABLE_EXPLANATION
import se.soderbjorn.lunicle.client.viewmodel.ENABLE_LABEL
import se.soderbjorn.lunicle.client.viewmodel.SessionBackingViewModel

/**
 * Renders the profile modal.
 *
 * @param viewModel owns the Connections round-trips.
 * @param scope collects the view model's state flow; cancelled by the caller when
 *   the dialog closes.
 * @param onDismiss the user closed it.
 */
class ProfileDialog(
    private val viewModel: ConnectionsBackingViewModel,
    private val sessionViewModel: SessionBackingViewModel,
    private val scope: CoroutineScope,
    private val onDismiss: () -> Unit,
    /**
     * Throw the tabs and panes away and go back to the default layout (LNL-160).
     *
     * Here rather than in the shell's own chrome because it is a fact about the
     * account, alongside the display name and the e-mail: a workspace follows the
     * user, so the place you go to see what the app remembers about you is the
     * place to say "forget that part". The window model has splits, hidden panes
     * and any number of tabs, and putting it back by hand means finding every one
     * of them.
     */
    private val onRestoreDefaultLayout: () -> Unit,
) {
    private val modal = Modal("Profile", onDismiss = { onDismiss() })

    // ── User tab ──
    private lateinit var nameElement: HTMLElement
    private lateinit var displayNameSection: HTMLElement
    private lateinit var layoutSection: HTMLElement
    private lateinit var displayNameField: HTMLInputElement
    private lateinit var emailField: HTMLInputElement
    private lateinit var emailHint: HTMLElement
    private lateinit var pendingSection: HTMLElement
    private lateinit var pendingMessage: HTMLElement
    private lateinit var emailCodeField: HTMLInputElement
    private lateinit var userErrorElement: HTMLElement
    private lateinit var userTab: HTMLElement

    // ── MCP tab ──
    private lateinit var enableBox: Toggle
    private lateinit var explanation: HTMLElement
    private lateinit var setupSection: HTMLElement
    private lateinit var serverUrlValue: HTMLElement
    private lateinit var commandValue: HTMLElement
    private lateinit var followUp: HTMLElement
    private lateinit var connectionsSection: HTMLElement
    private lateinit var connectionsList: HTMLElement
    private lateinit var errorElement: HTMLElement
    private lateinit var mcpTab: HTMLElement

    // ── The tab strip that switches between them ──
    private lateinit var userTabButton: HTMLButtonElement
    private lateinit var mcpTabButton: HTMLButtonElement

    /** Which tab is showing. Tracked rather than read off the DOM. */
    private var userTabSelected: Boolean = true

    private var confirmDialog: ConfirmDialog? = null

    /** Where the confirmation mounts. The same host the modal itself is on. */
    private var host: HTMLElement? = null

    fun mount(host: HTMLElement) {
        this.host = host

        modal.body.children(buildTabStrip(), buildUserTab(), buildMcpTab())
        modal.footer.children(
            element("div", "modal-footer-spacer"),
            button("Close", "btn btn-quiet") { onDismiss() },
        )
        modal.mount(host)

        // The User tab is the default — it is the "who am I" screen, and the MCP
        // half is a capability most sessions never touch.
        selectTab(userSelected = true)

        scope.launch { viewModel.stateFlow.collect { render(it) } }
        viewModel.start()
    }

    /**
     * The two-button strip that flips between the tabs.
     *
     * The same underline tabs (`.admin-tab`) as the instance-settings and
     * project-settings dialogs, so all three settings surfaces read as one family
     * (LNL-108) rather than the profile dialog wearing its own segmented chrome.
     */
    private fun buildTabStrip(): HTMLElement {
        userTabButton = button("User", "admin-tab") { selectTab(userSelected = true) }
        mcpTabButton = button("MCP", "admin-tab") { selectTab(userSelected = false) }
        return element("div", "admin-tabs").children(userTabButton, mcpTabButton)
    }

    /** Show one tab and accent its button; hide the other. */
    private fun selectTab(userSelected: Boolean) {
        userTabSelected = userSelected
        userTab.visible(userSelected)
        mcpTab.visible(!userSelected)
        userTabButton.classList.toggle("admin-tab-selected", userSelected)
        mcpTabButton.classList.toggle("admin-tab-selected", !userSelected)
    }

    /**
     * The User tab: who you are, and the two things you may change about it — the
     * name shown on your issues, and the address notifications reach you at.
     *
     * Every field commits on blur/Enter, never per keystroke: each is a request,
     * and [textFieldCommitting] is exactly the "tell me when they're done" event.
     *
     * ── Why the address takes two steps now ────────────────────────────────
     *
     * Because until LNL-71 this field wrote straight to the account with nothing
     * verified, which was an account-takeover primitive waiting for anything to
     * key on e-mail. Typing an address now mails a code to it and changes
     * nothing; [pendingSection] appears, and confirming is what writes.
     *
     * Everything about *whether* that section shows comes from the session state,
     * never from a flag set here — see [render]. That is what makes the pending
     * request survive the dialog being closed and reopened, which is the case
     * this design exists for: going to read your mail means leaving this screen.
     */
    private fun buildUserTab(): HTMLElement {
        nameElement = element("p", "modal-message")

        displayNameField = textFieldCommitting { sessionViewModel.onDisplayNameCommitted(it) }
        emailField = textFieldCommitting { sessionViewModel.onEmailCommitted(it) }
        // A real e-mail input, so a phone shows the @ keyboard and the browser can
        // hint at the format. The shape is still checked server-side.
        emailField.type = "email"
        emailHint = element("p", "field-hint")

        pendingMessage = element("p", "modal-message")
        emailCodeField = textFieldCommitting { sessionViewModel.onEmailCodeCommitted(it) }
        // Not type="number": a code with a leading zero is legal and a number
        // input eats it, along with offering spinner arrows for a six-digit
        // secret. inputmode gets the numeric keypad on a phone without any of
        // that, and autocomplete lets the browser and OS offer the code straight
        // from the message — which is the whole reason it is in the subject line.
        emailCodeField.setAttribute("inputmode", "numeric")
        emailCodeField.setAttribute("autocomplete", "one-time-code")
        emailCodeField.setAttribute("maxlength", "6")
        emailCodeField.placeholder = "6-digit code"

        pendingSection = element("div", "").children(
            pendingMessage,
            emailCodeField,
            element(
                "p",
                "field-hint",
                "The code is in the subject line of the message, and works for 15 minutes.",
            ),
            button("Cancel this change", "btn btn-quiet btn-small") {
                sessionViewModel.onEmailChangeCancelled()
            },
        )

        userErrorElement = element("p", "modal-error")
        userErrorElement.setAttribute("role", "status")

        // Signed-in only, and hidden rather than disabled for the reason every
        // other gated control here is: a visitor has no stored layout, so the
        // button would name something that does not exist. Shown by [render].
        layoutSection = element("div", "profile-layout-section").children(
            element("label", "field-label", "Window layout"),
            element(
                "p",
                "field-hint",
                "Your tabs and panes follow your account. Restoring gives you one tab per " +
                    "project, each showing that project's board.",
            ),
            button("Restore default layout", "btn btn-quiet btn-small") { onRestoreDefaultLayout() },
        )

        // The override, its label and its hint kept together in one section so the
        // deployment switch that hides it (LNL-137) hides all three at once and
        // never leaves an orphaned label over the e-mail field. Whether it shows is
        // decided in render from the session, never here — see [render].
        displayNameSection = element("div", "").children(
            element("label", "field-label", "Display name"),
            displayNameField,
            element(
                "p",
                "field-hint",
                "Shown on the issues and comments you write. Leave blank to use the name your " +
                    "sign-in provider gives.",
            ),
        )

        // The class is spacing, not identity: this tab is the one place a hint
        // paragraph is followed by a button rather than being the last thing in
        // its group, and .field-hint carries no bottom margin — see
        // .profile-user-tab in styles.css (LNL-185).
        userTab = element("div", "profile-user-tab").children(
            nameElement,
            displayNameSection,
            element("label", "field-label", "E-mail"),
            emailField,
            emailHint,
            pendingSection,
            layoutSection,
            userErrorElement,
        )
        return userTab
    }

    /** The MCP tab: exactly what the whole dialog used to be. */
    private fun buildMcpTab(): HTMLElement {
        enableBox = Toggle { viewModel.onEnabledToggled(it) }
        val enableRow = toggleRow(enableBox, ENABLE_LABEL)
        explanation = element("p", "field-hint", ENABLE_EXPLANATION)

        // ── State B: on, with the things nobody types correctly ──────────────
        serverUrlValue = element("code", "copy-value")
        commandValue = element("code", "copy-value")
        followUp = element(
            "p",
            "field-hint",
            "Then run /mcp in Claude Code and choose Authenticate. " +
                "Other agents: add a custom connector with the server URL above.",
        )
        setupSection = element("div", "connections-setup")
        setupSection.children(
            element("label", "field-label", "Server URL"),
            copyRow(serverUrlValue) { viewModel.stateFlow.value.serverUrl },
            element("label", "field-label", "Claude Code"),
            copyRow(commandValue) { viewModel.stateFlow.value.claudeCodeCommand },
            followUp,
        )

        // ── State C: on, with connections. The point of the screen. ──────────
        connectionsList = element("div", "connections-list")
        connectionsSection = element("div", "")
        connectionsSection.children(
            element("label", "field-label", "Connected agents"),
            connectionsList,
        )

        errorElement = element("p", "modal-error")
        errorElement.setAttribute("role", "status")

        mcpTab = element("div", "").children(
            element("h3", "section-title", CONNECTIONS_TITLE),
            enableRow,
            explanation,
            setupSection,
            connectionsSection,
            errorElement,
        )
        return mcpTab
    }

    /**
     * A value with a copy button beside it.
     *
     * The value is read through a lambda at click time rather than captured, so
     * the button copies what is on screen now — the URL arrives from the server
     * after this row is built, and a captured empty string would copy nothing
     * while looking like it worked.
     */
    private fun copyRow(value: HTMLElement, text: () -> String): HTMLElement {
        val row = element("div", "copy-row")
        lateinit var copyButton: HTMLButtonElement
        // The value is read through the lambda at click time (see this function's
        // doc), and the button is passed in so its label can flip to "Copied".
        copyButton = button("Copy", "btn btn-quiet btn-small") { copy(text(), copyButton) }
        row.children(value, copyButton)
        return row
    }

    /**
     * Copy [value] to the clipboard, by whichever route this context allows.
     *
     * The async `navigator.clipboard` is the modern path, but it is not the only
     * one that matters here: it is `undefined` on an insecure origin (a local
     * http run), and — the bug this fixes — it *rejects* inside a cross-origin
     * iframe that was not granted `clipboard-write`, which is exactly how this app
     * runs, embedded in lunamux.dev. The old code called `writeText` and dropped
     * the promise, so that rejection was silent: the button did nothing and said
     * nothing.
     *
     * So: try the async API, but catch its rejection and fall back to
     * [execCommandCopy], which is scoped to the click gesture rather than the
     * permission and reaches the clipboard where the async API is refused. The
     * "select it by hand" hint is now the genuine last resort it always claimed
     * to be, shown only when both routes fail.
     */
    private fun copy(value: String, sourceButton: HTMLButtonElement) {
        val clipboard = window.navigator.asDynamic().clipboard
        if (clipboard != null && clipboard != undefined) {
            clipboard.writeText(value)
                .then({ confirmCopied(sourceButton) })
                .catch({ fallbackCopy(value, sourceButton) })
        } else {
            fallbackCopy(value, sourceButton)
        }
    }

    private fun fallbackCopy(value: String, sourceButton: HTMLButtonElement) {
        if (execCommandCopy(value)) {
            confirmCopied(sourceButton)
        } else {
            errorElement.setTextIfChanged("Couldn't copy automatically — select the text and copy it.")
            errorElement.visible(true)
        }
    }

    /**
     * The legacy copy: drop the text into an off-screen textarea, select it, and
     * ask the document to copy the selection.
     *
     * `execCommand` is deprecated, but it is gesture-scoped rather than
     * permission-scoped, so it still reaches the clipboard from inside the iframe
     * and on the http origin where the async API will not. Off-screen via
     * `position: fixed`, not `display: none`: a hidden element has no selection to
     * copy. Returns whether the copy took.
     */
    private fun execCommandCopy(value: String): Boolean {
        val textArea = document.createElement("textarea") as HTMLTextAreaElement
        textArea.value = value
        textArea.setAttribute("aria-hidden", "true")
        textArea.style.position = "fixed"
        textArea.style.top = "-9999px"
        document.body?.appendChild(textArea)
        textArea.select()
        val copied = try {
            document.asDynamic().execCommand("copy") as? Boolean ?: false
        } catch (e: Throwable) {
            false
        }
        textArea.remove()
        return copied
    }

    /** Flash "Copied" on the button and clear any prior error. */
    private fun confirmCopied(sourceButton: HTMLButtonElement) {
        errorElement.visible(false)
        sourceButton.textContent = "Copied"
        window.setTimeout({ sourceButton.textContent = "Copy" }, 1200)
    }

    /**
     * Apply the session snapshot to the User tab.
     *
     * Names the **effective** user, which is a useful thing to be able to check
     * while impersonating — and is honest, because the connections listed below
     * are that same user's. See the server's McpRoutes.
     *
     * The fields are synced only while they are not focused. A background state
     * emission — anything else the session view model does — must not yank the
     * caret or discard an edit in progress: the committing fields do not report
     * until blur, so mid-edit the state still holds the old value and a blind
     * re-sync would type over the user. See setValueIfChanged.
     */
    fun render(state: SessionBackingViewModel.State) {
        nameElement.setTextIfChanged("Signed in as ${state.displayName ?: "you"}.")

        // The whole override section, gone when the deployment hides it (LNL-137) —
        // label, field and hint together, so nothing is left dangling over the
        // e-mail field. Each user's name is then the provider's, still shown in the
        // line above. The gate is the server's fact, read straight off the session.
        displayNameSection.visible(!state.isDisplayNameHidden)

        // The stored workspace exists only for a signed-in user, so the control
        // that clears it does too (LNL-160). This dialog is only reachable while
        // signed in, but the state says so explicitly rather than relying on that.
        layoutSection.visible(state.user != null)

        if (!state.isDisplayNameHidden && document.activeElement != displayNameField) {
            // Value only when it is the user's own override; otherwise the field is
            // empty and the provider's name shows as a placeholder — which doubles
            // as the "leave blank to use this" hint.
            displayNameField.setValueIfChanged(if (state.hasDisplayNameOverride) state.displayName ?: "" else "")
            displayNameField.placeholder = if (state.hasDisplayNameOverride) "" else (state.displayName ?: "")
        }
        if (document.activeElement != emailField) {
            // The pending address wins over the stored one while a confirmation is
            // outstanding, so the field shows what a code was actually sent to
            // rather than snapping back to the old address and inviting the user
            // to type the new one again.
            emailField.setValueIfChanged(state.pendingEmail ?: state.email ?: "")
        }

        // Three states, and the hint is the only thing that distinguishes the
        // first two on screen. An unverified address is not an error — nothing
        // before LNL-71 was ever verified — so this reads as an offer rather than
        // a warning.
        emailHint.setTextIfChanged(
            when {
                state.isAwaitingEmailCode ->
                    "Nothing has changed yet. Enter the code below to move your address."
                state.email == null ->
                    "Where notification e-mails are sent. We will mail you a code to confirm it."
                state.isEmailVerified ->
                    "Confirmed. Leave blank to remove it — the notification toggles disappear " +
                        "without an address to reach you at."
                else ->
                    "Not confirmed yet. Notifications still go here; re-enter it to confirm. " +
                        "Leave blank to remove it."
            },
        )

        pendingSection.visible(state.isAwaitingEmailCode)
        pendingMessage.setTextIfChanged(
            state.pendingEmail?.let { "We sent a code to $it." } ?: "",
        )
        if (!state.isAwaitingEmailCode && document.activeElement != emailCodeField) {
            // Cleared once the change lands, so reopening the dialog after a
            // successful confirmation does not show the spent code still sitting
            // in the box.
            emailCodeField.setValueIfChanged("")
        }

        userErrorElement.setTextIfChanged(state.errorMessage ?: "")
        userErrorElement.visible(state.errorMessage != null)
    }

    /** Apply the Connections snapshot. */
    private fun render(state: ConnectionsBackingViewModel.State) {
        // The MCP tab exists only for a user an admin has permitted. When it does
        // not, the whole tab is gone — button and content — not merely disabled: a
        // greyed-out switch here is an invitation to hunt for a permission the user
        // cannot grant themselves. And if the tab happens to be the one showing
        // when permission is withdrawn, fall back to the User tab so the dialog is
        // never left displaying a tab whose button just disappeared.
        mcpTabButton.visible(state.isSectionAvailable, displayValue = "inline-flex")
        if (!state.isSectionAvailable && !userTabSelected) {
            selectTab(userSelected = true)
        }

        // Nothing until the first fetch returns: an unchecked toggle rendered at
        // somebody who has agents connected would be a lie about the one thing
        // this section exists to report.
        enableBox.checked = state.isEnabled
        enableBox.disabled = state.isBusy || !state.isLoaded
        setupSection.visible(state.isLoaded && state.isSetupVisible)
        connectionsSection.visible(state.isLoaded && state.hasConnections)

        serverUrlValue.setTextIfChanged(state.serverUrl)
        commandValue.setTextIfChanged(state.claudeCodeCommand)

        renderConnections(state)
        renderConfirmation(state)

        errorElement.setTextIfChanged(state.errorMessage ?: "")
        errorElement.visible(state.errorMessage != null)
    }

    private fun renderConnections(state: ConnectionsBackingViewModel.State) {
        connectionsList.clear()
        state.connections.forEach { connection ->
            val row = element("div", "connection-row")
            val text = element("div", "connection-text")
            // element(text = …) sets textContent, never innerHTML. That is what
            // makes the self-reported client name safe to render: anyone may
            // register a client called "<img onerror=…>", and this puts it on the
            // page as characters rather than as markup. See McpConnection.
            text.children(
                element("div", "connection-name", connection.name),
                element("div", "connection-detail", connection.detail),
            )
            val revoke = button("Revoke", "btn btn-danger-quiet btn-small") {
                viewModel.onRevokeTapped(connection.clientId)
            }
            revoke.disabled = state.isBusy
            row.children(text, revoke)
            connectionsList.appendChild(row)
        }
    }

    /**
     * Put the "this will stop N agents" confirmation up, or take it down.
     *
     * Turning the toggle off is the one action here whose cost is invisible: the
     * agents that stop are not on screen at the moment of the click, and they are
     * somebody's working setup. Revoke needs no confirmation for the opposite
     * reason — see the view model.
     */
    private fun renderConfirmation(state: ConnectionsBackingViewModel.State) {
        if (state.pendingDisableConfirmation && confirmDialog == null) {
            confirmDialog = ConfirmDialog(
                title = "Turn off agent access?",
                message = state.disableConfirmationMessage,
                // Not "OK": the button says what it does, which is the last
                // chance to notice. And not "Delete" either — nothing is deleted,
                // and saying so would misdescribe a reversible action as the
                // irreversible one beside it.
                destructiveLabel = "Turn off",
                onConfirm = { viewModel.onDisableConfirmed() },
                onCancel = { viewModel.onDisableCancelled() },
            ).also { it.mount(host ?: return) }
        } else if (!state.pendingDisableConfirmation && confirmDialog != null) {
            confirmDialog?.dismiss()
            confirmDialog = null
        }
    }

    fun dismiss() {
        confirmDialog?.dismiss()
        confirmDialog = null
        modal.dismiss()
    }
}
