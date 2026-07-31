/**
 * Settings — all of them, in one pane.
 *
 * There were three: the instance settings modal (the account directory and the
 * deployment switches), the project settings pane (one per project), and the
 * profile modal (your name, your address, your agents). Three surfaces meant
 * three answers to "where do I change this", and two of them were modals that
 * covered the thing being configured. LNL-193 converges them onto this one pane,
 * on the frame the project settings already had.
 *
 * ── Five tabs, and who has which ────────────────────────────────────────────
 *
 * **You · Who gets in · People · Projects · Instance**, in that order for
 * everybody. A tab appears only if the caller holds something it can act on:
 * everyone has You, anyone with a project has Projects, and the three instance
 * tabs belong to an administrator. A member therefore sees You and Projects — the
 * same strip with three buttons hidden, in the same places, and **not** a second
 * layout with a project-named tab in it. That is the whole of the permission
 * story here; see [renderTabs].
 *
 * ── Where each tab's content lives ──────────────────────────────────────────
 *
 * You is built here, because it is the only tab whose subject is the reader and whose
 * two view models the pane already holds. The other four are their own views, handed
 * in: Projects is a [ProjectsTab] (LNL-194), and Who gets in, People and Instance are
 * the three panes of one [InstanceTabs] (LNL-195), which share a view model and a
 * single request. `AdminSettingsDialog` — the modal all three came from — is **gone**;
 * its content was moved rather than rewritten, and the last of it landed with LNL-195.
 *
 * ── No dialog-wide OK ───────────────────────────────────────────────────────
 *
 * The footer is `Close` and nothing else. A single OK cannot mean anything across
 * five tabs and every project on the instance — half the pane would commit and
 * half would not — so **every edit applies immediately**, which is what the
 * project dialog's Privileges tab already did. There is no Delete in the footer
 * either: it belongs to the project it deletes, and sits at the bottom of that
 * project's General section (LNL-194).
 *
 * ── Tabs inside tabs ────────────────────────────────────────────────────────
 *
 * The profile dialog had its own User/MCP strip. It is gone: the outer strip is
 * the only navigation this pane has, and the two halves of You are told apart by
 * a section rule. Every string and every decision still comes from the view
 * models — this file knows what a toggle is and nothing about what any of them
 * mean.
 *
 * @see SettingsRoute for the position this pane is addressed by
 * @see ConnectionsBackingViewModel
 * @see SessionBackingViewModel
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
import se.soderbjorn.lunicle.client.viewmodel.SettingsRoute
import se.soderbjorn.lunicle.client.viewmodel.SettingsTab

/**
 * Renders the settings pane.
 *
 * @param viewModel owns the Connections round-trips — the second half of You.
 * @param sessionViewModel owns who is signed in and the two fields they may edit.
 * @param scope collects both state flows; cancelled by the caller when the pane
 *   closes.
 * @param shell the frame. A `PaneShell` in the app — this is a pane, beside the
 *   board rather than over it — and the interface rather than the class so a
 *   modal could still host it.
 * @param dialogHost where the confirmation layers, which is the app's modal host
 *   rather than this pane: a confirmation inside a pane would scroll with it.
 * @param onClose the reader pressed Close, or the pane's ×.
 * @param onRestoreDefaultLayout throw the tabs and panes away (LNL-160). Here,
 *   with the display name and the address, because a workspace follows the
 *   account: the place you go to see what the app remembers about you is the
 *   place to say "forget that part".
 * @param onRouteChanged the reader moved within the pane — pressed a tab. The
 *   address bar follows; see main.kt's syncUrl.
 * @param hasProjects whether this caller has any project at all, read fresh on
 *   every render. Half of whether the Projects tab is on offer.
 * @param canCreateProject whether this caller may make a project, read fresh for
 *   [hasProjects]'s reason. The other half: the tab holds the only place a project
 *   is made, so gating it on having one first left a fresh instance's owner with
 *   the right and no door (LNL-211).
 */
class SettingsPane(
    private val viewModel: ConnectionsBackingViewModel,
    private val sessionViewModel: SessionBackingViewModel,
    private val scope: CoroutineScope,
    private val shell: DialogShell,
    private val dialogHost: HTMLElement,
    private val onClose: () -> Unit,
    private val onRestoreDefaultLayout: () -> Unit,
    private val onRouteChanged: (SettingsRoute) -> Unit,
    private val hasProjects: () -> Boolean,
    private val canCreateProject: () -> Boolean,
    /**
     * The Projects tab, built by the caller because it needs the API and the app's
     * "open a new project" gesture — neither of which this pane has any other use for.
     * Handed in rather than constructed here so the pane stays a shell over five tabs.
     */
    private val projectsTab: ProjectsTab,
    /**
     * Who gets in, People and Instance — three panes over one view model (LNL-195).
     *
     * Handed in for [projectsTab]'s reason and one more: it needs the API and the app's
     * "the project list changed" hook, and it must be built once so the three tabs
     * share a single request and can never disagree about which instance they describe.
     */
    private val instanceTabs: InstanceTabs,
) {
    /**
     * Where in the pane the reader is.
     *
     * View-local, like the project dialog's selected tab and for the same reason:
     * nothing is fetched per tab, so the route only decides which pane is drawn.
     * It leaves for the address bar through [onRouteChanged] and comes back
     * through [show] — a deep link, or the board's gear pressed while the pane is
     * already open.
     */
    private var route: SettingsRoute = SettingsRoute()

    // ── The strip ──
    private lateinit var tabStrip: HTMLElement
    private val tabButtons = mutableMapOf<SettingsTab, HTMLButtonElement>()
    private val tabPanes = mutableMapOf<SettingsTab, HTMLElement>()

    // ── You: the account half ──
    private lateinit var standingElement: HTMLElement
    private lateinit var administrationElement: HTMLElement
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

    /** Everything a signed-out reader has nothing to see: shown from the session. */
    private lateinit var accountSection: HTMLElement
    private lateinit var signedOutElement: HTMLElement

    // ── You: the connections half ──
    private lateinit var enableBox: Toggle
    private lateinit var explanation: HTMLElement
    private lateinit var notPermittedElement: HTMLElement
    private lateinit var setupSection: HTMLElement
    private lateinit var serverUrlValue: HTMLElement
    private lateinit var commandValue: HTMLElement
    private lateinit var followUp: HTMLElement
    private lateinit var connectionsSection: HTMLElement
    private lateinit var connectionsList: HTMLElement
    private lateinit var connectionsBlock: HTMLElement
    private lateinit var errorElement: HTMLElement

    private var confirmDialog: ConfirmDialog? = null

    /**
     * Whether the three instance tabs have been told to fetch.
     *
     * Only an administrator's session may ask, so the fetch waits for the session to say
     * so — see [renderTabs]. A latch rather than a re-check because the session emits on
     * every render and the request must go out once.
     */
    private var areInstanceTabsStarted: Boolean = false

    /** The session as last rendered, so the connections half can word its refusal. */
    private var lastSession: SessionBackingViewModel.State = sessionViewModel.stateFlow.value

    fun mount(host: HTMLElement) {
        shell.setTitle("Settings")

        // The body becomes a fixed-height flex column so the strip stays put and each
        // tab scrolls inside itself. Without it the body scrolls and the strip rides
        // off the top — which is what the project settings pane did, and is worse here
        // because there are five tabs to get back to rather than one.
        shell.body.classList.add("settings-shell-body")

        tabStrip = buildTabStrip()
        tabPanes[SettingsTab.YOU] = buildYouTab()
        tabPanes[SettingsTab.ACCESS] = instanceTabs.mountAccess()
        tabPanes[SettingsTab.PEOPLE] = instanceTabs.mountPeople()
        tabPanes[SettingsTab.PROJECTS] = projectsTab.mount()
        tabPanes[SettingsTab.INSTANCE] = instanceTabs.mountInstance()

        shell.body.children(tabStrip, *SettingsTab.entries.map { tabPanes.getValue(it) }.toTypedArray())
        // Close, alone. See this file's preamble: with five tabs and every project
        // behind one of them, a dialog-wide OK would commit half a pane.
        shell.footer.children(
            element("div", "modal-footer-spacer"),
            button("Close", "btn btn-quiet") { onClose() },
        )
        shell.mount(host)

        selectTab(route.tab)

        scope.launch { sessionViewModel.stateFlow.collect { render(it) } }
        scope.launch { viewModel.stateFlow.collect { render(it) } }
        // After mount, not from the view model's init: the panes are on screen
        // before the request goes out, so the wait is a rendered empty pane rather
        // than a moment of nothing.
        viewModel.start()
    }

    /**
     * Go somewhere — a deep link, or an entry point pressed while this pane is
     * already open.
     *
     * Silent about the address bar: the caller is the address bar's source here,
     * and reporting the move back to it would be an echo. Only a press on the
     * strip fires [onRouteChanged].
     */
    fun show(next: SettingsRoute) {
        route = next
        if (::tabStrip.isInitialized) selectTab(next.tab)
        // Carried into the tab even when the tab being shown is another one: the route
        // keeps a project id across a trip to Instance and back, and the tab is where that
        // memory has to live for the trip back to land on it.
        projectsTab.show(next.projectId, next.section)
    }

    /**
     * Where the pane currently is — what the address bar writes.
     *
     * The project and the section are read back off the Projects tab rather than off
     * [route], because the tab settles them: a route naming no project lands on the first
     * one, and a route naming a section this caller's rung does not reach lands on the
     * first section they have. The address bar should say where the reader actually is.
     */
    fun currentRoute(): SettingsRoute = route.copy(
        projectId = projectsTab.currentProjectId() ?: route.projectId,
        section = projectsTab.currentSection(),
    )

    /**
     * Re-decide which tabs are on offer.
     *
     * [hasProjects] is read off the board state, which is a flow this pane does not
     * collect — so a projects list landing after the pane was built would leave the
     * Projects tab hidden until the next *session* emission, and there may not be
     * one. That is not an edge case: on a cold load the pane is built before the
     * project list returns, which is exactly what a `?settings=` deep link does.
     * The caller drives this from the tick it does see; see main.kt's
     * SettingsPanes.sync.
     */
    fun refreshAvailability() {
        if (!::tabStrip.isInitialized) return
        renderTabs(lastSession)
        // The same tick, for the same reason: the Projects rail is drawn from the board
        // state's project list, which is a flow neither this pane nor the tab collects. A
        // project arriving, being renamed or being deleted reaches the rail here.
        projectsTab.render()
    }

    private fun buildTabStrip(): HTMLElement {
        val strip = element("div", "admin-tabs")
        SettingsTab.entries.forEach { tab ->
            val btn = button(tab.label, "admin-tab") {
                route = route.copy(tab = tab)
                selectTab(tab)
                onRouteChanged(currentRoute())
            }
            tabButtons[tab] = btn
            strip.appendChild(btn)
        }
        return strip
    }

    /**
     * Show one pane, hide the rest, and move the underline with it.
     *
     * Two of the five panes are flex rows — Projects (rail beside content) and People
     * (list beside detail) — so showing either with the default `display: block` would
     * drop the right-hand half below the left and stop the split filling the tab.
     */
    private fun selectTab(tab: SettingsTab) {
        tabPanes.forEach { (which, pane) ->
            pane.visible(which == tab, displayValue = if (which in SPLIT_TABS) "flex" else "block")
        }
        tabButtons.forEach { (which, btn) -> btn.classList.toggle("admin-tab-selected", which == tab) }
    }

    // ── You ──────────────────────────────────────────────────────────────────

    /**
     * The You tab: what is true of you, what you may change about yourself, and
     * what may act as you.
     *
     * Everything the profile dialog held, in its own wording, with its User/MCP
     * strip replaced by a section rule. Every field commits on blur/Enter, never
     * per keystroke: each is a request, and [textFieldCommitting] is exactly the
     * "tell me when they're done" event.
     *
     * ── Why the address takes two steps ─────────────────────────────────────
     *
     * Because until LNL-71 this field wrote straight to the account with nothing
     * verified, which was an account-takeover primitive waiting for anything to
     * key on e-mail. Typing an address now mails a code to it and changes nothing;
     * [pendingSection] appears, and confirming is what writes.
     *
     * Everything about *whether* that section shows comes from the session state,
     * never from a flag set here — see [render]. That is what makes the pending
     * request survive the pane being closed and reopened, which is the case this
     * design exists for: going to read your mail means leaving this screen.
     */
    private fun buildYouTab(): HTMLElement {
        // The two facts nobody can change about themselves, above everything that
        // can be changed. See SessionBackingViewModel.State.standingLine.
        standingElement = element("p", "settings-standing-line")
        administrationElement = element("p", "settings-standing-line")

        nameElement = element("p", "modal-message")

        displayNameField = textFieldCommitting { sessionViewModel.onDisplayNameCommitted(it) }
        emailField = textFieldCommitting { sessionViewModel.onEmailCommitted(it) }
        // A real e-mail input, so a phone shows the @ keyboard and the browser can
        // hint at the format. The shape is still checked server-side.
        emailField.type = "email"
        emailHint = element("p", "field-hint")

        pendingMessage = element("p", "modal-message")
        emailCodeField = textFieldCommitting { sessionViewModel.onEmailCodeCommitted(it) }
        // Not type="number": a code with a leading zero is legal and a number input
        // eats it, along with offering spinner arrows for a six-digit secret.
        // inputmode gets the numeric keypad on a phone without any of that, and
        // autocomplete lets the browser and OS offer the code straight from the
        // message — which is the whole reason it is in the subject line.
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

        // The class is spacing, not identity: hints in this tab are prose with a
        // button under them rather than captions on the control above, and
        // .field-hint carries no bottom margin — see .settings-you-tab in
        // styles.css, which says why the rule is scoped here (LNL-185).
        accountSection = element("div", "settings-you-tab").children(
            nameElement,
            displayNameSection,
            element("label", "field-label", "E-mail"),
            emailField,
            emailHint,
            pendingSection,
            layoutSection,
            userErrorElement,
        )
        signedOutElement = element(
            "p",
            "admin-placeholder",
            "Sign in to see and change what this instance knows about you.",
        )

        return element("div", "settings-tab-pane").children(
            standingElement,
            administrationElement,
            accountSection,
            signedOutElement,
            // The rule that replaced a tab strip. Two halves of one screen, told
            // apart by a line rather than by navigation.
            element("div", "settings-section-rule"),
            buildConnectionsSection(),
        )
    }

    /** The connections half of You: exactly what the profile dialog's MCP tab was. */
    private fun buildConnectionsSection(): HTMLElement {
        enableBox = Toggle { viewModel.onEnabledToggled(it) }
        val enableRow = toggleRow(enableBox, ENABLE_LABEL)
        explanation = element("p", "field-hint", ENABLE_EXPLANATION)
        // Why the switch is dead, where the switch is (LNL-193). The section used
        // to disappear whole when a caller was not permitted, on the reasoning that
        // a greyed switch sends people hunting for a permission they cannot grant
        // themselves. That was right while the permission was per-person and
        // invisible; it is per TIER now, so the refusal can name the tier — and a
        // control that vanishes reads as a bug where a greyed one with a sentence
        // beside it tells you who to ask.
        notPermittedElement = element("p", "admin-note")

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

        // ── State C: on, with connections. The point of the section. ─────────
        connectionsList = element("div", "connections-list")
        connectionsSection = element("div", "")
        connectionsSection.children(
            element("label", "field-label", "Connected agents"),
            connectionsList,
        )

        errorElement = element("p", "modal-error")
        errorElement.setAttribute("role", "status")

        connectionsBlock = element("div", "").children(
            element("h3", "section-title", CONNECTIONS_TITLE),
            enableRow,
            notPermittedElement,
            explanation,
            setupSection,
            connectionsSection,
            errorElement,
        )
        return connectionsBlock
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
        copyButton = button("Copy", "btn btn-quiet btn-small") { copy(text(), copyButton) }
        row.children(value, copyButton)
        return row
    }

    /**
     * Copy [value] to the clipboard, by whichever route this context allows.
     *
     * The async `navigator.clipboard` is the modern path, but it is not the only
     * one that matters here: it is `undefined` on an insecure origin (a local http
     * run), and — the bug this fixes — it *rejects* inside a cross-origin iframe
     * that was not granted `clipboard-write`, which is exactly how this app runs,
     * embedded in lunamux.dev. The old code called `writeText` and dropped the
     * promise, so that rejection was silent: the button did nothing and said
     * nothing.
     *
     * So: try the async API, but catch its rejection and fall back to
     * [execCommandCopy], which is scoped to the click gesture rather than the
     * permission and reaches the clipboard where the async API is refused. The
     * "select it by hand" hint is now the genuine last resort it always claimed to
     * be, shown only when both routes fail.
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

    // ── Rendering ────────────────────────────────────────────────────────────

    /**
     * Apply the session snapshot.
     *
     * Names the **effective** user, which is a useful thing to be able to check
     * while impersonating — and is honest, because the connections listed below
     * are that same user's. See the server's McpRoutes.
     *
     * The fields are synced only while they are not focused. A background state
     * emission must not yank the caret or discard an edit in progress: the
     * committing fields do not report until blur, so mid-edit the state still
     * holds the old value and a blind re-sync would type over the user. See
     * setValueIfChanged.
     */
    fun render(state: SessionBackingViewModel.State) {
        lastSession = state
        renderTabs(state)

        standingElement.setTextIfChanged(state.standingLine ?: "")
        standingElement.visible(state.standingLine != null)
        administrationElement.setTextIfChanged(state.administrationLine ?: "")
        administrationElement.visible(state.administrationLine != null)

        accountSection.visible(state.user != null)
        signedOutElement.visible(state.isLoaded && state.user == null)

        nameElement.setTextIfChanged("Signed in as ${state.displayName ?: "you"}.")

        // The whole override section, gone when the deployment hides it (LNL-137) —
        // label, field and hint together, so nothing is left dangling over the
        // e-mail field. Each user's name is then the provider's, still shown in the
        // line above. The gate is the server's fact, read straight off the session.
        displayNameSection.visible(!state.isDisplayNameHidden)

        // The stored workspace exists only for a signed-in user, so the control
        // that clears it does too (LNL-160).
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
            // rather than snapping back to the old address and inviting the user to
            // type the new one again.
            emailField.setValueIfChanged(state.pendingEmail ?: state.email ?: "")
        }

        // Three states, and the hint is the only thing that distinguishes the first
        // two on screen. An unverified address is not an error — nothing before
        // LNL-71 was ever verified — so this reads as an offer rather than a
        // warning.
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
            // Cleared once the change lands, so reopening the pane after a
            // successful confirmation does not show the spent code still sitting in
            // the box.
            emailCodeField.setValueIfChanged("")
        }

        userErrorElement.setTextIfChanged(state.errorMessage ?: "")
        userErrorElement.visible(state.errorMessage != null)

        // The refusal is worded from the tier, which only the session knows, so a
        // session tick has to re-render the connections half too.
        renderConnectionsPermission(viewModel.stateFlow.value)
    }

    /**
     * Which tabs this caller has.
     *
     * Everyone has You. Projects appears for anyone with a project to configure —
     * which is any project they can see, because the tab is where a project's own
     * settings live — and for anyone who may make one, whether or not they have any
     * yet. The second half is not symmetry: the rail's last row is where a project
     * is made, so the has-one test alone shut the door on the one instance that
     * most needs it open, the empty one (LNL-211). Somebody who may neither see a
     * project nor make one still gets no tab, and would indeed be pressing into an
     * empty rail.
     *
     * The other three are the instance's, and belong to whoever administers it.
     *
     * The strip is drawn even with one tab. That is the opposite of the project
     * dialog's rule, and deliberate: there it meant "this dialog is only about
     * General"; here it is a five-tab surface every reader shares, and a strip that
     * appeared once you were promoted would move the content under everybody who
     * had learned where it was.
     */
    private fun renderTabs(state: SessionBackingViewModel.State) {
        val shown = mutableSetOf(SettingsTab.YOU)
        if (hasProjects() || canCreateProject()) shown += SettingsTab.PROJECTS
        if (state.isSysAdmin) {
            shown += SettingsTab.ACCESS
            shown += SettingsTab.PEOPLE
            shown += SettingsTab.INSTANCE
            // Fetched here rather than at mount, and once: the admin state's route is
            // admin-only and refuses everybody else, so asking for it before the session
            // says who this is would 403 in every member's console on every open — and
            // would fill three tabs they cannot see with an error about a request they
            // never made. Latched, because a session tick arrives on every render.
            if (!areInstanceTabsStarted) {
                areInstanceTabsStarted = true
                instanceTabs.start()
            }
        }
        tabButtons.forEach { (tab, btn) -> btn.visible(tab in shown, displayValue = "inline-flex") }

        // A tab this caller does not have — a stale link, or a right withdrawn
        // while the pane was open — must not stay showing under them. You is the
        // one everybody has.
        if (route.tab !in shown) {
            route = route.copy(tab = SettingsTab.YOU)
            selectTab(SettingsTab.YOU)
        }
    }

    /** Apply the Connections snapshot. */
    private fun render(state: ConnectionsBackingViewModel.State) {
        renderConnectionsPermission(state)

        // Nothing until the first fetch returns: an unchecked toggle rendered at
        // somebody who has agents connected would be a lie about the one thing this
        // section exists to report.
        enableBox.checked = state.isEnabled
        setupSection.visible(state.isLoaded && state.isSetupVisible)
        connectionsSection.visible(state.isLoaded && state.hasConnections)

        serverUrlValue.setTextIfChanged(state.serverUrl)
        commandValue.setTextIfChanged(state.claudeCodeCommand)

        renderConnections(state)
        renderConfirmation(state)

        errorElement.setTextIfChanged(state.errorMessage ?: "")
        errorElement.visible(state.errorMessage != null)
    }

    /**
     * The switch's enabled state, and the sentence that explains it when it is
     * dead (LNL-193).
     *
     * Its own method because it is a function of BOTH flows — the permission comes
     * from the MCP state and the tier that words it from the session — so either
     * ticking has to redraw it.
     */
    private fun renderConnectionsPermission(state: ConnectionsBackingViewModel.State) {
        val permitted = !state.isLoaded || state.isAllowed
        enableBox.disabled = state.isBusy || !state.isLoaded || !state.isAllowed
        notPermittedElement.setTextIfChanged(
            if (permitted) "" else lastSession.agentsNotPermittedReason,
        )
        notPermittedElement.visible(!permitted)
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
                // Not "OK": the button says what it does, which is the last chance
                // to notice. And not "Delete" either — nothing is deleted, and
                // saying so would misdescribe a reversible action as the
                // irreversible one beside it.
                destructiveLabel = "Turn off",
                onConfirm = { viewModel.onDisableConfirmed() },
                onCancel = { viewModel.onDisableCancelled() },
            ).also { it.mount(dialogHost) }
        } else if (!state.pendingDisableConfirmation && confirmDialog != null) {
            confirmDialog?.dismiss()
            confirmDialog = null
        }
    }

    fun dismiss() {
        confirmDialog?.dismiss()
        confirmDialog = null
        projectsTab.dispose()
        instanceTabs.dismiss()
        shell.dismiss()
    }

    private companion object {
        /** The two panes that are flex rows rather than blocks. See [selectTab]. */
        val SPLIT_TABS = setOf(SettingsTab.PROJECTS, SettingsTab.PEOPLE)
    }
}
