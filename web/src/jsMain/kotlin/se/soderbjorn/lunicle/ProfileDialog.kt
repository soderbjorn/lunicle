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
    private val scope: CoroutineScope,
    private val onDismiss: () -> Unit,
) {
    private val modal = Modal("Profile", onDismiss = { onDismiss() })

    private lateinit var nameElement: HTMLElement
    private lateinit var enableBox: Toggle
    private lateinit var explanation: HTMLElement
    private lateinit var setupSection: HTMLElement
    private lateinit var serverUrlValue: HTMLElement
    private lateinit var commandValue: HTMLElement
    private lateinit var followUp: HTMLElement
    private lateinit var connectionsSection: HTMLElement
    private lateinit var connectionsList: HTMLElement
    private lateinit var errorElement: HTMLElement

    private var confirmDialog: ConfirmDialog? = null

    /** Where the confirmation mounts. The same host the modal itself is on. */
    private var host: HTMLElement? = null

    fun mount(host: HTMLElement) {
        this.host = host
        nameElement = element("p", "modal-message")

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

        modal.body.children(
            nameElement,
            element("h3", "section-title", CONNECTIONS_TITLE),
            enableRow,
            explanation,
            setupSection,
            connectionsSection,
            errorElement,
        )
        modal.footer.children(
            element("div", "modal-footer-spacer"),
            button("Close", "btn btn-quiet") { onDismiss() },
        )
        modal.mount(host)

        scope.launch { viewModel.stateFlow.collect { render(it) } }
        viewModel.start()
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
     * Apply the session snapshot.
     *
     * Names the **effective** user, which is a useful thing to be able to check
     * while impersonating — and is honest, because the connections listed below
     * are that same user's. See the server's McpRoutes.
     */
    fun render(state: SessionBackingViewModel.State) {
        nameElement.setTextIfChanged("Signed in as ${state.displayName ?: "you"}.")
    }

    /** Apply the Connections snapshot. */
    private fun render(state: ConnectionsBackingViewModel.State) {
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
