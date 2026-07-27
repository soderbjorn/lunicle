/**
 * The project dialog — new and edit are the same screen, as the spec asks.
 *
 * A dumb view over [EditProjectBackingViewModel]: every label, every disabled
 * flag, both confirmations and the whole delete message are decided there. This
 * file knows what a checkbox is and nothing about what any of them mean.
 *
 * ── Tabs, and why they are the view's job (LNL-102) ──────────────────────────
 *
 * The settings used to be one long scrolling column. They are now the same tabs
 * the instance settings dialog uses — General, Github, Structure, Sprints,
 * Privileges — built from the very same `.admin-tab` underline strip and, for
 * Privileges, the same `.admin-split` master-detail as [AdminSettingsDialog]'s
 * Users tab, so the two settings surfaces read as one family.
 *
 * Which tabs a caller *sees* is still the view model's decision, not the tab
 * strip's: the strip only draws the tabs [renderTabs] is told are available, and
 * every one of those flags — [EditProjectBackingViewModel.State.showForm],
 * `hasSettings`, `canConfigureRepository` — already existed to gate the sections
 * these tabs now hold. A non-admin gets General alone, and with a single tab the
 * strip hides itself: a lone "General" is chrome, not a choice.
 *
 * The section *contents* did not move between files. General keeps the identity
 * form, Features and Notifications; Github takes the repository fields that used
 * to sit inside the form; Structure and Sprints split the vocabulary sections the
 * view model hands over (sprints last, on their own tab); Privileges turns the
 * old flat members table into a master-detail. Every one of those pieces is built
 * once and shown or hidden — never rebuilt on a tick — for the reason below.
 *
 * ── Why the sections are rebuilt, and why not always ─────────────────────────
 *
 * The vocabulary rows and the privileges detail are lists that change shape — a
 * row appears, one is deleted, two swap places — so they are rebuilt from the
 * state rather than diffed. But they are rebuilt only when their *signature*
 * changes, and that is not an optimisation: the rows contain text fields that
 * the admin types into, and rebuilding on every emission would destroy the field
 * mid-word on any state change at all. Same reasoning as `setValueIfChanged`,
 * one level up — see Dom.kt.
 *
 * @see EditProjectBackingViewModel
 * @see AdminSettingsDialog for the tab strip and the master-detail this copies
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import se.soderbjorn.lunicle.client.viewmodel.EditProjectBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.GITHUB_TOKEN_ENV_HINT
import se.soderbjorn.lunicle.client.viewmodel.GITHUB_TOKEN_ENV_LABEL
import se.soderbjorn.lunicle.client.viewmodel.GITHUB_TOKEN_ENV_PREFIX_EXAMPLE
import se.soderbjorn.lunicle.client.viewmodel.GITHUB_TOKEN_LITERAL_HINT
import se.soderbjorn.lunicle.client.viewmodel.GITHUB_TOKEN_LITERAL_LABEL
import se.soderbjorn.lunicle.client.viewmodel.GITHUB_TOKEN_LITERAL_STORED_PLACEHOLDER
import se.soderbjorn.lunicle.client.viewmodel.GITHUB_TOKEN_MODE_LABEL
import se.soderbjorn.lunicle.client.viewmodel.GITHUB_TOKEN_MODE_OPTIONS
import se.soderbjorn.lunicle.client.viewmodel.MemberRowState
import se.soderbjorn.lunicle.client.viewmodel.REPOSITORY_HINT
import se.soderbjorn.lunicle.client.viewmodel.REPOSITORY_SECTION_TITLE
import se.soderbjorn.lunicle.client.viewmodel.REPOSITORY_URL_LABEL
import se.soderbjorn.lunicle.client.viewmodel.REPOSITORY_URL_PLACEHOLDER
import se.soderbjorn.lunicle.client.viewmodel.VocabularyRowState
import se.soderbjorn.lunicle.client.viewmodel.VocabularySection
import se.soderbjorn.lunicle.clientserver.TokenModes
import se.soderbjorn.lunicle.clientserver.VocabularyKind

/**
 * Renders the project dialog.
 *
 * @param scope collects the view model's state flow; cancelled by the caller
 *   when the dialog closes.
 */
class ProjectDialog(
    private val viewModel: EditProjectBackingViewModel,
    private val scope: CoroutineScope,
    /**
     * The frame, or null to build the modal one below.
     *
     * A [PaneShell] when this is a project's settings PANE — which is how an
     * existing project's settings open since LNL-160, beside the board rather
     * than over it. "New project…" keeps the modal: there is no project to hang
     * a pane off until it exists, and a four-field form that opens, is answered
     * and goes away is what a modal is for. See [DialogShell].
     */
    shell: DialogShell? = null,
) {
    /**
     * The tabs, in the order the strip draws them.
     *
     * General is every caller's landing tab and the only one a non-admin has; the
     * rest are gated in [renderTabs] and simply do not appear when the caller's
     * rights (or a not-yet-loaded settings response) do not warrant them.
     */
    private enum class Tab { GENERAL, GITHUB, STRUCTURE, SPRINTS, PRIVILEGES }

    /**
     * Large when editing, small when creating.
     *
     * Read from the initial state rather than waiting for the settings to load,
     * because the panel cannot resize under someone who has started typing. An
     * edit dialog is five vocabulary sections and a privileges table tall — it
     * needs the panel whose body scrolls inside itself — and a new project is four
     * fields, which that panel would render as a mostly-empty screen. See Modal's
     * isLarge.
     */
    private val modal: DialogShell = shell ?: Modal(
        title = "Project",
        onDismiss = { viewModel.onCancelTapped() },
        // Large only when the admin sections will actually fill it — a non-admin
        // opening the cog sees just the notification toggle, and the large panel
        // would render that as a mostly-empty screen. Both facts are known from the
        // initial state. See Modal's isLarge and EditProjectBackingViewModel.State.
        isLarge = !viewModel.stateFlow.value.isNew && viewModel.stateFlow.value.canConfigure,
    )
    private lateinit var nameField: HTMLInputElement
    private lateinit var prefixField: HTMLInputElement
    private lateinit var prefixHint: HTMLElement
    private lateinit var publicBox: Toggle

    /** The signed-in-visibility toggle and the note shown when "public" makes it redundant (LNL-138). */
    private lateinit var signedInBox: Toggle
    private lateinit var signedInHint: HTMLElement
    private lateinit var repositoryField: HTMLInputElement
    private lateinit var tokenEnvField: HTMLInputElement
    private lateinit var repositorySection: HTMLElement

    // ── The token source, its radio and its two mode-specific fields (LNL-107) ──
    private val tokenModeButtons = mutableMapOf<String, HTMLButtonElement>()
    private lateinit var tokenEnvGroup: HTMLElement
    private lateinit var tokenLiteralField: HTMLInputElement
    private lateinit var tokenLiteralGroup: HTMLElement

    private lateinit var validationElement: HTMLElement
    private lateinit var errorElement: HTMLElement
    private lateinit var okButton: HTMLElement
    private lateinit var deleteButton: HTMLElement
    private lateinit var host: HTMLElement

    /** The name/prefix/public form — hidden for a non-admin, who may not configure. */
    private lateinit var formElement: HTMLElement

    /** The new-issue notification section — shown to everyone the cog opens for. */
    private lateinit var notificationElement: HTMLElement
    private lateinit var watchButton: WatchButton
    private lateinit var watchRow: HTMLElement
    private lateinit var notifyHint: HTMLElement

    /** The Features section (LNL-96): the discussions and messages switches. */
    private lateinit var featuresElement: HTMLElement
    private lateinit var discussionsToggle: Toggle
    private lateinit var messagesToggle: Toggle

    // The per-user display section (LNL-105) — on the General tab beside notifications.
    private lateinit var displayElement: HTMLElement
    private lateinit var hideNumbersToggle: Toggle

    // The Structure tab's new-ticket requirements (LNL-106).
    private lateinit var requirementsElement: HTMLElement
    private lateinit var requireLabelToggle: Toggle
    private lateinit var requireComponentToggle: Toggle
    private lateinit var requireFixedVersionToggle: Toggle
    // The board-display setting (LNL-157) — shows the author on each card. Rides in
    // the same admin-only section as the requirement toggles, but is a display
    // choice, not a requirement.
    private lateinit var showIssueAuthorToggle: Toggle

    // ── The tab strip and the five panes ──
    private lateinit var tabStrip: HTMLElement
    private val tabButtons = mutableMapOf<Tab, HTMLButtonElement>()
    private var selectedTab = Tab.GENERAL

    private lateinit var generalPane: HTMLElement
    private lateinit var githubPane: HTMLElement
    private lateinit var structurePane: HTMLElement
    private lateinit var sprintsPane: HTMLElement
    private lateinit var privilegesPane: HTMLElement

    /** Where the vocabulary sections land — every kind but the sprint. */
    private lateinit var structureSectionsElement: HTMLElement

    /** Where the sprint section lands, alone on its tab. */
    private lateinit var sprintSectionElement: HTMLElement

    // ── The Privileges master-detail (LNL-102) ──
    private lateinit var privilegesListElement: HTMLElement
    private lateinit var privilegesDetailElement: HTMLElement
    private lateinit var privilegesPlaceholder: HTMLElement

    /**
     * Which account the detail pane is showing, by id.
     *
     * View-local rather than a view-model field, exactly like [selectedTab]: the
     * data every account needs is already in `state.members`, so selection only
     * decides which of them the right pane draws — nothing is fetched, nothing is
     * written. It is an id and not an index so it survives the members list being
     * rebuilt around it on every write.
     */
    private var selectedMemberId: Long? = null

    /** What the account list was last built from, so a busy tick does not tear it down. */
    private var privilegesListSignature: String? = null

    /** What the detail pane was last built from. Same reasoning as the list's. */
    private var privilegesDetailSignature: String? = null

    /**
     * The section views, by kind, built on the first render that has settings.
     *
     * Kept rather than rebuilt so that the add field keeps its text and its focus
     * across a re-render — the field is where the admin is typing when everything
     * else on the dialog changes.
     */
    private val sectionViews = mutableMapOf<VocabularyKind, SectionView>()

    /** The vocabulary confirmation and the settings alert, while they are up. */
    private var vocabularyConfirm: ConfirmDialog? = null
    private var alert: AlertDialog? = null
    private var alertMessage: String? = null

    /** The delete-project confirmation, while it is up (LNL-107). */
    private var deleteConfirm: ConfirmDialog? = null

    fun mount(host: HTMLElement) {
        this.host = host

        // No placeholders. Both fields carried an example ("Lunamux", "LMX") and
        // both read as pre-filled values rather than as ghost text — the dialog
        // looked like it came with defaults, on a form where the name is the one
        // thing only the user can supply. An empty field cannot lie about that.
        // The prefix keeps its hint line below it, which is where the example
        // now lives.
        nameField = textField { viewModel.onNameChanged(it) }
        prefixField = textField { viewModel.onPrefixChanged(it) }
        prefixHint = element("p", "field-hint")
        publicBox = Toggle { viewModel.onPublicChanged(it) }
        signedInBox = Toggle { viewModel.onVisibleToAllSignedInChanged(it) }
        signedInHint = element("p", "field-hint")

        validationElement = element("p", "field-validation")
        errorElement = element("p", "modal-error")
        errorElement.setAttribute("role", "status")

        val publicRow = toggleRow(publicBox, "Public — anyone can read this project's issues without signing in")
        // The middle read tier (LNL-138): read-only for every signed-in account. Its
        // caption says "read" and "not change", because the whole point of the tier
        // is a grant that carries no write — the server admits these callers to
        // browse and no write gate widens for them.
        val signedInRow = toggleRow(
            signedInBox,
            "Visible to all signed-in users — anyone with an account can read this project, " +
                "but not change anything",
        )

        repositoryField = textField(REPOSITORY_URL_PLACEHOLDER) { viewModel.onRepositoryUrlChanged(it) }
        tokenEnvField = textField(GITHUB_TOKEN_ENV_PREFIX_EXAMPLE) { viewModel.onGithubTokenEnvChanged(it) }
        tokenLiteralField = textField("") { viewModel.onGithubTokenLiteralChanged(it) }

        // The env-variable and paste-token fields, each in its own group so the
        // radio below can show exactly one. Both carry their own label and hint —
        // the two sources are told apart by what they warn about, not only by which
        // field is visible. `token-field-group` restores the space above the label:
        // it is the group's :first-child, so .field-label's normal top margin is
        // zeroed, and without it the label crowds the button row (LNL-141).
        tokenEnvGroup = element("div", "token-field-group").children(
            element("label", "field-label", GITHUB_TOKEN_ENV_LABEL),
            tokenEnvField,
            element("p", "field-hint", GITHUB_TOKEN_ENV_HINT),
        )
        tokenLiteralGroup = element("div", "token-field-group").children(
            element("label", "field-label", GITHUB_TOKEN_LITERAL_LABEL),
            tokenLiteralField,
            element("p", "field-hint", GITHUB_TOKEN_LITERAL_HINT),
        )

        // The token-source radio: one button per TokenModes option, in the view
        // model's order, reporting the chosen mode. The active one is marked
        // `dt-selected`, the way Toggle marks its live side. Plain
        // `dt-settings-button-row`, not the `toggle` variant On/Off wears: that
        // pares the buttons to zero horizontal padding for two-letter labels, which
        // cramped "Environment variable" against its borders (LNL-141).
        val tokenModeRow = element("div", "dt-settings-button-row")
        GITHUB_TOKEN_MODE_OPTIONS.forEach { (mode, label) ->
            val btn = button(label, "dt-settings-choice-btn") { viewModel.onGithubTokenModeChanged(mode) }
            tokenModeButtons[mode] = btn
            tokenModeRow.appendChild(btn)
        }

        // The Github tab's whole content. Built here and mounted into githubPane
        // below; the tab is what hides it from a caller who may not configure it
        // (canConfigureRepository — an owner or a system administrator), so there is
        // no per-field visibility to keep beyond the token source's own two groups.
        repositorySection = element("div", "").children(
            element("h3", "settings-section-title", REPOSITORY_SECTION_TITLE),
            element("label", "field-label", REPOSITORY_URL_LABEL),
            repositoryField,
            element("p", "field-hint", REPOSITORY_HINT),
            element("label", "field-label", GITHUB_TOKEN_MODE_LABEL),
            tokenModeRow,
            tokenEnvGroup,
            tokenLiteralGroup,
        )

        // The whole editable identity form in one container, so a non-admin — who
        // reaches this dialog now, for the notification toggle alone — can have it
        // hidden wholesale rather than field by field. The repository fields left
        // it for the Github tab (LNL-102).
        formElement = element("div", "").children(
            element("label", "field-label", "Name"),
            nameField,
            element("label", "field-label", "Issue prefix"),
            prefixField,
            prefixHint,
            publicRow,
            signedInRow,
            signedInHint,
            validationElement,
            errorElement,
        )

        notificationElement = buildNotificationSection()
        featuresElement = buildFeaturesSection()
        displayElement = buildDisplaySection()

        // ── The panes ──
        // General: identity form, then Features, then Notifications, then the
        // per-user display choices (LNL-105) — the order the spec asks for (LNL-102).
        generalPane = element("div", "project-pane")
            .children(formElement, featuresElement, notificationElement, displayElement)
        githubPane = element("div", "project-pane").children(repositorySection)

        requirementsElement = buildRequirementsSection()
        structureSectionsElement = element("div", "project-structure")
        // The vocabularies, then the new-ticket requirements that refer to them
        // (LNL-106): you see the labels and components a project has, then the
        // switches that decide whether a new ticket must use them.
        structurePane = element("div", "project-pane").children(structureSectionsElement, requirementsElement)

        sprintSectionElement = element("div", "project-structure")
        sprintsPane = element("div", "project-pane").children(sprintSectionElement)

        privilegesPane = buildPrivilegesPane()

        // The default `.modal-large .modal-body` is already the flex column these
        // panes want — a fixed-height container that scrolls its children, not
        // itself. So no `.settings-body` here (that made the whole body one
        // scrolling column, which is what the single-page dialog needed and the
        // tabbed one must not): each pane owns its own overflow, and the
        // master-detail Privileges tab scrolls its two halves independently, the
        // way AdminSettingsDialog's Users tab does. See .modal-large .modal-body.
        tabStrip = buildTabStrip()
        modal.body.children(tabStrip, generalPane, githubPane, structurePane, sprintsPane, privilegesPane)

        okButton = button("OK", "btn btn-primary") { viewModel.onOkTapped() }

        // A Delete button again since LNL-107, but for the owner rather than every
        // administrator: it rides `showDelete` (the owner's identity tier), sits at
        // the left of the footer away from OK, and raises a typed-phrase confirmation.
        // The system administrator's instance-wide copy still lives in the settings
        // dialog's Projects tab (LNL-93). See EditProjectBackingViewModel.
        deleteButton = button("Delete project", "btn btn-danger-quiet") { viewModel.onDeleteProjectTapped() }

        modal.footer.children(
            deleteButton,
            element("div", "modal-footer-spacer"),
            button("Close", "btn btn-quiet") { viewModel.onCancelTapped() },
            okButton,
        )

        modal.mount(host)
        // Land on General, the tab everyone shares. renderTabs decides on the first
        // emission which others are available and whether the strip is even shown.
        selectTab(Tab.GENERAL)
        scope.launch { viewModel.stateFlow.collect { render(it) } }
        // Only the form has a field to focus, and only an admin (or a new project)
        // sees it. Focusing a hidden input would be a no-op, but asking for it
        // reads as if the dialog expected one.
        if (viewModel.stateFlow.value.showForm) nameField.focus()
    }

    /**
     * The tab strip — the same underline tabs (`.admin-tab`) as the instance
     * settings dialog, so the two settings surfaces read as one. Every tab is
     * always built; [renderTabs] hides the ones a caller may not have.
     */
    private fun buildTabStrip(): HTMLElement {
        fun tab(which: Tab, label: String): HTMLButtonElement =
            button(label, "admin-tab") { selectTab(which) }.also { tabButtons[which] = it }
        return element("div", "admin-tabs").children(
            tab(Tab.GENERAL, "General"),
            tab(Tab.GITHUB, "Github"),
            tab(Tab.STRUCTURE, "Structure"),
            tab(Tab.SPRINTS, "Sprints"),
            tab(Tab.PRIVILEGES, "Privileges"),
        )
    }

    /** Show one pane, hide the rest, and move the selection with it. Copies AdminSettingsDialog. */
    private fun selectTab(tab: Tab) {
        selectedTab = tab
        generalPane.visible(tab == Tab.GENERAL)
        githubPane.visible(tab == Tab.GITHUB)
        structurePane.visible(tab == Tab.STRUCTURE)
        sprintsPane.visible(tab == Tab.SPRINTS)
        // The Privileges pane is `.admin-split`, a flex row — showing it with the
        // default `display: block` would drop its detail pane below the list and
        // stop the split filling the tab. Same treatment its twin gets.
        privilegesPane.visible(tab == Tab.PRIVILEGES, displayValue = "flex")
        tabButtons.forEach { (t, btn) -> btn.classList.toggle("admin-tab-selected", t == tab) }
    }

    /**
     * The Privileges master-detail (LNL-102): the accounts down the left, the
     * selected one's grants down the right — the same `.admin-split` shape as the
     * instance dialog's Users tab.
     */
    private fun buildPrivilegesPane(): HTMLElement {
        privilegesListElement = element("div", "admin-user-list")
        privilegesPlaceholder = element("p", "admin-placeholder", "Select a user.")
        privilegesDetailElement = element("div", "admin-detail")
        val detailPane = element("div", "admin-detail-pane").children(privilegesPlaceholder, privilegesDetailElement)
        return element("div", "admin-split").children(privilegesListElement, detailPane)
    }

    /**
     * The new-issue notification section: the toggle, or a hint when the caller
     * has no address to send to.
     *
     * The one thing a non-admin can do here, and an extra an admin gets too. Its
     * visibility and the toggle-vs-hint choice are decided in [render] from state;
     * this only builds the elements.
     */
    private fun buildNotificationSection(): HTMLElement {
        // The same pill the issue window uses (LNL-46), and deliberately the same
        // control rather than a lookalike: "watching" means one thing to a reader,
        // and it should not be a switch here and a button there. What differs is
        // only what is being watched, which the caption under it says — a project
        // is watched for NEW issues, where an issue is watched for updates, and
        // that distinction is not one an eye can draw on its own.
        watchButton = WatchButton { viewModel.onNewIssueNotificationToggled(it) }
        watchRow = element("div", "project-watch-row").children(
            element("span", "watch-caption", "Notify me when a new issue is created in this project"),
            watchButton.element,
        )
        notifyHint = element(
            "p",
            "field-hint",
            "Add an e-mail address in your profile to receive notifications.",
        )
        return element("div", "project-notifications").children(
            element("h3", "section-title", "Notifications"),
            watchRow,
            notifyHint,
        )
    }

    /**
     * The Features section (LNL-96): switch this project's discussions and
     * messages on or off.
     *
     * Two plain toggles rather than the watch pill — these are settings, not a
     * standing subscription. Shown only to a project administrator (its visibility
     * is decided in [render] from `showFeaturesSection`); the toggles write
     * immediately, so there is no Save here to press. The caption under each says
     * what it hides, because "off" is not obviously "the tab disappears".
     */
    private fun buildFeaturesSection(): HTMLElement {
        discussionsToggle = Toggle { viewModel.onDiscussionsEnabledChanged(it) }
        messagesToggle = Toggle { viewModel.onMessagesEnabledChanged(it) }
        return element("div", "project-features").children(
            element("h3", "section-title", "Features"),
            element(
                "p",
                "field-hint",
                "Switch these off to hide their tab for this project, even where forums are enabled.",
            ),
            toggleRow(discussionsToggle, "Discussions — a project forum for longer conversations"),
            toggleRow(messagesToggle, "Private messages between members"),
        )
    }

    /**
     * The per-user display section (LNL-105): hide the issue number on this
     * project's board cards and issue windows, for the person looking — not the
     * project. A plain toggle beside Notifications, and like it shown to anyone who
     * opens an existing project's settings; its visibility is decided in [render]
     * from `showDisplaySection`.
     */
    private fun buildDisplaySection(): HTMLElement {
        hideNumbersToggle = Toggle { viewModel.onHideIssueNumbersChanged(it) }
        return element("div", "project-display").children(
            element("h3", "section-title", "Display"),
            element(
                "p",
                "field-hint",
                "A choice for you alone, saved per project — it changes nothing for anyone else.",
            ),
            toggleRow(hideNumbersToggle, "Hide issue numbers on the board and in issue windows"),
        )
    }

    /**
     * The Structure tab's new-ticket requirements (LNL-106): must a new ticket
     * carry a label, a component. Two toggles, project administrator only (its
     * visibility rides `showRequirementsSection`). Each row's caption drops to a
     * warning when the project has none of that kind to pick — the requirement is a
     * no-op there rather than a trap, and the caption says so; see [renderRequirements].
     */
    private fun buildRequirementsSection(): HTMLElement {
        requireLabelToggle = Toggle { viewModel.onRequireLabelChanged(it) }
        requireComponentToggle = Toggle { viewModel.onRequireComponentChanged(it) }
        requireFixedVersionToggle = Toggle { viewModel.onRequireFixedVersionChanged(it) }
        showIssueAuthorToggle = Toggle { viewModel.onShowIssueAuthorChanged(it) }
        return element("div", "project-requirements").children(
            element("h3", "section-title", "Ticket requirements"),
            element(
                "p",
                "field-hint",
                "Insist that whoever files a ticket picks a label and/or a component before it is " +
                    "created. A requirement with nothing to pick — no labels, or no components — is " +
                    "quietly ignored, so add some first.",
            ),
            toggleRow(requireLabelToggle, "A new ticket must have a label"),
            toggleRow(requireComponentToggle, "A new ticket must have a component"),
            // The fixed-version requirement is a different moment — resolving, not
            // filing — but it is the same kind of switch, so it rides in the same
            // section. Its own hint, because "what counts as done" and "have a
            // version to pick" are conditions the other two do not share.
            element(
                "p",
                "field-hint",
                "And insist on a fixed version when an issue is closed as done. This asks only for " +
                    "resolutions ticked \"means done\" above, and only once the project has a version " +
                    "to pick — so make a version and mark a done resolution first, or it is quietly " +
                    "ignored.",
            ),
            toggleRow(requireFixedVersionToggle, "Closing as done must have a fixed version"),
            // A display setting, not a requirement, but the same kind of project-
            // administrator switch (LNL-157) — it decides what the board shows, not
            // what a ticket must carry — so it rides in the same admin-only section
            // with its own hint.
            element(
                "p",
                "field-hint",
                "And show who filed each issue on a muted line at the bottom of its card. Off by " +
                    "default, so the board stays sparse until you opt in.",
            ),
            toggleRow(showIssueAuthorToggle, "Show the issue author on board cards"),
        )
    }

    private fun render(state: EditProjectBackingViewModel.State) {
        modal.setTitle(state.title)

        // The editable form and the OK button exist only for someone who may
        // configure the project. A non-admin sees the notification section and the
        // Close button, and nothing that would 403.
        formElement.visible(state.showForm)
        okButton.visible(state.showForm, displayValue = "inline-flex")

        renderNotifications(state)
        renderFeatures(state)
        renderDisplay(state)
        renderRequirements(state)

        nameField.setValueIfChanged(state.name)
        prefixField.setValueIfChanged(state.namePrefix)
        // Hidden until there is a prefix to preview, rather than shown with a
        // stand-in: the hint exists to answer "what does this field do?", and it
        // answers that the moment the user types the first letter.
        prefixHint.setTextIfChanged(
            state.prefixExample?.let { "Issues in this project will be numbered $it" } ?: "",
        )
        prefixHint.visible(state.prefixExample != null)
        publicBox.checked = state.isPublic
        // The signed-in-visibility tier (LNL-138). When "public" is on it is already
        // implied — a public project is readable by everyone signed in — so the
        // toggle shows checked-and-disabled with a note, rather than offering a
        // switch that would change nothing. Its stored value is untouched underneath,
        // so unticking public reveals whatever was chosen here.
        val impliedByPublic = state.signedInVisibilityImpliedByPublic
        signedInBox.checked = state.visibleToAllSignedIn || impliedByPublic
        signedInBox.disabled = impliedByPublic
        signedInHint.setTextIfChanged(
            if (impliedByPublic) "A public project is already readable by everyone signed in." else "",
        )
        signedInHint.visible(impliedByPublic)
        // Kept up to date even while the Github tab is hidden — the fields cost
        // nothing to fill, and doing it here rather than on tab-switch keeps this
        // method the one place state reaches the DOM. Whether the tab is offered at
        // all is renderTabs' call (canConfigureRepository — owner or system admin).
        repositoryField.setValueIfChanged(state.repositoryUrl)
        tokenEnvField.setValueIfChanged(state.githubTokenEnv)
        renderTokenSource(state)

        // The validation line and the server's error line are different things
        // and both can be present: one says "you haven't filled this in", the
        // other says "the server refused what you sent". Collapsing them would
        // mean a 409 wiping the message telling you the prefix is empty.
        validationElement.setTextIfChanged(state.validationMessage ?: "")
        validationElement.visible(state.validationMessage != null)
        errorElement.setTextIfChanged(state.errorMessage ?: "")
        errorElement.visible(state.errorMessage != null)

        (okButton as HTMLButtonElement).disabled = !state.isOkEnabled

        // The owner's Delete button, and its confirmation. Hidden for everyone the
        // identity form is hidden from, so a project administrator's dialog carries
        // no destructive control it would be refused.
        deleteButton.visible(state.showDelete, displayValue = "inline-flex")
        (deleteButton as HTMLButtonElement).disabled = state.isBusy

        renderSettings(state)
        renderPrivileges(state)
        renderTabs(state)
        renderVocabularyConfirm(state)
        renderDeleteConfirm(state)
        renderAlert(state)
    }

    /**
     * The token-source radio and its two mode-specific field groups (LNL-107).
     *
     * The chosen mode marks its button `dt-selected` and shows exactly one of the
     * env / literal groups; `none` shows neither. The literal field is never
     * pre-filled from the server — the token does not travel — so when a literal is
     * already stored it wears a "leave blank to keep it" placeholder instead, which
     * is the whole of how the write-only field says "one is set".
     */
    private fun renderTokenSource(state: EditProjectBackingViewModel.State) {
        tokenModeButtons.forEach { (mode, btn) ->
            btn.classList.toggle("dt-selected", mode == state.githubTokenMode)
        }
        tokenEnvGroup.visible(state.githubTokenMode == TokenModes.ENV)
        tokenLiteralGroup.visible(state.githubTokenMode == TokenModes.LITERAL)
        tokenLiteralField.setValueIfChanged(state.githubTokenLiteral)
        // A stored literal is signalled by the loaded mode being `literal` while the
        // typed field is still empty; that is exactly when the placeholder should
        // promise the stored token is kept. Read off the loaded settings, not the
        // live mode, so toggling the radio to `literal` on a project that never had
        // one does not falsely claim there is something to keep.
        val hasStoredLiteral = state.settings?.githubTokenMode == TokenModes.LITERAL
        tokenLiteralField.placeholder = if (hasStoredLiteral) GITHUB_TOKEN_LITERAL_STORED_PLACEHOLDER else ""
    }

    /**
     * Raise or dismiss the delete confirmation as the view model's pending id comes
     * and goes (LNL-107) — keyed on presence, like the vocabulary confirm's, so two
     * deletes cannot leave one dialog describing the other. The typed phrase arms
     * the button; see ConfirmDialog.
     */
    private fun renderDeleteConfirm(state: EditProjectBackingViewModel.State) {
        val pending = state.pendingProjectDelete
        if (pending != null && deleteConfirm == null) {
            deleteConfirm = ConfirmDialog(
                title = pending.title,
                message = pending.message,
                destructiveLabel = "Delete",
                onConfirm = { viewModel.onDeleteProjectConfirmed() },
                onCancel = { viewModel.onDeleteProjectCancelled() },
                confirmationPhrase = pending.confirmationPhrase,
            ).also { confirm -> confirm.mount(host) }
        } else if (pending == null && deleteConfirm != null) {
            deleteConfirm?.dismiss()
            deleteConfirm = null
        }
    }

    /**
     * The notification section: shown for an existing project once its settings
     * have loaded, as the toggle when the caller has an address and as a hint
     * pointing at the profile dialog when they do not.
     */
    private fun renderNotifications(state: EditProjectBackingViewModel.State) {
        notificationElement.visible(state.showNotificationSection)
        if (!state.showNotificationSection) return
        val canReceive = state.canReceiveEmailNotifications
        watchRow.visible(canReceive, displayValue = "flex")
        notifyHint.visible(!canReceive)
        watchButton.render(watching = state.notifyOnNewIssue, isEnabled = !state.isBusy)
    }

    private fun renderFeatures(state: EditProjectBackingViewModel.State) {
        featuresElement.visible(state.showFeaturesSection)
        if (!state.showFeaturesSection) return
        // Disabled while a write is in flight, so a double click cannot queue two
        // opposite intents against one flag — the same guard the admin dialog's
        // switches use.
        discussionsToggle.checked = state.discussionsEnabled
        discussionsToggle.disabled = state.isBusy
        messagesToggle.checked = state.messagesEnabled
        messagesToggle.disabled = state.isBusy
    }

    /** The per-user hide-issue-numbers toggle (LNL-105). */
    private fun renderDisplay(state: EditProjectBackingViewModel.State) {
        displayElement.visible(state.showDisplaySection)
        if (!state.showDisplaySection) return
        // Not gated on isBusy: this is a fire-and-forget board preference, not a
        // settings write with an in-flight state — the switch stays live.
        hideNumbersToggle.checked = state.hideIssueNumbers
    }

    /** The new-ticket requirement toggles (LNL-106), project administrator only. */
    private fun renderRequirements(state: EditProjectBackingViewModel.State) {
        requirementsElement.visible(state.showRequirementsSection)
        if (!state.showRequirementsSection) return
        requireLabelToggle.checked = state.requireLabel
        requireLabelToggle.disabled = state.isBusy
        requireComponentToggle.checked = state.requireComponent
        requireComponentToggle.disabled = state.isBusy
        requireFixedVersionToggle.checked = state.requireFixedVersionOnResolve
        requireFixedVersionToggle.disabled = state.isBusy
        showIssueAuthorToggle.checked = state.showIssueAuthor
        showIssueAuthorToggle.disabled = state.isBusy
    }

    /**
     * Which tabs are on offer, and whether the strip is worth drawing.
     *
     * Each gate is a flag that already earned its keep hiding a section: General
     * is unconditional (the notification toggle is everyone's), Github rides
     * `canConfigureRepository` (system administrator only, and arrives with the
     * settings load), and Structure / Sprints / Privileges ride `hasSettings` —
     * settings present *and* the caller an admin. With only General available the
     * strip hides: a single tab is chrome, not a choice, and the dialog reads as
     * the plain notification panel a non-admin has always seen.
     */
    private fun renderTabs(state: EditProjectBackingViewModel.State) {
        val shown = mutableSetOf(Tab.GENERAL)
        if (!state.isNew && state.canConfigureRepository) shown += Tab.GITHUB
        if (state.hasSettings) {
            shown += Tab.STRUCTURE
            shown += Tab.SPRINTS
            shown += Tab.PRIVILEGES
        }

        tabButtons.forEach { (t, btn) -> btn.visible(t in shown, displayValue = "inline-flex") }
        tabStrip.visible(shown.size > 1, displayValue = "flex")

        // A tab that is not this caller's — or has not loaded yet — must not stay
        // selected under them; fall back to General, which everyone has. Otherwise
        // re-assert the current selection so a fresh emission cannot leave two
        // panes visible at once.
        selectTab(if (selectedTab in shown) selectedTab else Tab.GENERAL)
    }

    // ── The settings half ────────────────────────────────────────────────────

    /**
     * The vocabulary sections, split across the Structure and Sprints tabs.
     *
     * Absent entirely until the settings arrive — and for a caller the server
     * refused, forever. See [EditProjectBackingViewModel.loadSettings]: a section
     * that cannot be filled in is worse than no section. The sprint section lands
     * on its own tab (LNL-102); the view model already sorts it last, so the split
     * is just "sprint here, everything else there".
     */
    private fun renderSettings(state: EditProjectBackingViewModel.State) {
        if (!state.hasSettings) return

        state.sections.forEach { section ->
            val view = sectionViews.getOrPut(section.kind) {
                SectionView(section.kind).also {
                    val host = if (section.kind == VocabularyKind.SPRINT) sprintSectionElement else structureSectionsElement
                    host.appendChild(it.root)
                }
            }
            view.render(section)
        }
    }

    /**
     * The Privileges tab: every account down the left, the selected one's grants
     * down the right (LNL-102) — the master-detail shape of the instance dialog's
     * Users tab. No-op until the settings load, like the vocabulary sections; the
     * tab itself is hidden until then.
     */
    private fun renderPrivileges(state: EditProjectBackingViewModel.State) {
        if (!state.hasSettings) return
        val members = state.members
        // Keep a live selection: default to the first account, and re-home it if
        // whoever was selected fell out of the list between renders.
        if (members.none { it.userId == selectedMemberId }) {
            selectedMemberId = members.firstOrNull()?.userId
        }
        renderPrivilegesList(members)
        renderPrivilegesDetail(state)
    }

    /**
     * The account list, rebuilt only when it changed.
     *
     * The signature carries every rendered fact — id, name, the admin badge — and
     * the selection, because that is what the highlight renders from; a selection
     * left out of it would be a list that stopped responding to clicks, this
     * pattern's quietest failure. Same guard [AdminSettingsDialog]'s list uses.
     */
    private fun renderPrivilegesList(members: List<MemberRowState>) {
        val signature = "$selectedMemberId|" + members.joinToString("|") {
            "${it.userId}:${it.name}:${isAdminRow(it)}"
        }
        if (signature == privilegesListSignature) return
        privilegesListSignature = signature

        privilegesListElement.clear()
        members.forEach { member ->
            val row = button("", "admin-user-row") {
                selectedMemberId = member.userId
                // Selection is view-local, so nothing pushes a new state — redraw
                // from the current one to move the highlight and repoint the detail.
                renderPrivileges(viewModel.stateFlow.value)
            }
            row.classList.toggle("admin-user-selected", member.userId == selectedMemberId)

            // element(text = …) sets textContent, never innerHTML — a display name
            // is user-chosen, so this is what puts "<img onerror=…>" on the page as
            // characters. Same rule the instance dialog's list follows.
            val name = element("span", "admin-user-name", member.name)
            if (isAdminRow(member)) name.appendChild(element("span", "admin-user-badge", "admin"))
            row.children(name)
            privilegesListElement.appendChild(row)
        }
    }

    /**
     * The selected account's grants, rebuilt only when they changed.
     *
     * An instance admin's row has no toggles at all — the note saying why is the
     * whole of it, exactly as the flat table used to render (see
     * [EditProjectBackingViewModel.State.members]). Everyone else gets the role
     * switches, each writing immediately.
     */
    private fun renderPrivilegesDetail(state: EditProjectBackingViewModel.State) {
        val member = state.members.firstOrNull { it.userId == selectedMemberId }
        privilegesPlaceholder.visible(member == null)
        privilegesDetailElement.visible(member != null)
        if (member == null) {
            privilegesDetailSignature = null
            return
        }

        val signature = "${member.userId}:${member.name}:${member.note}:" +
            member.roles.joinToString(",") { "${it.key}=${it.isOn}/${it.isEnabled}" }
        if (signature == privilegesDetailSignature) return
        privilegesDetailSignature = signature

        privilegesDetailElement.clear()
        privilegesDetailElement.appendChild(element("h3", "admin-detail-name", member.name))

        member.note?.let { privilegesDetailElement.appendChild(element("p", "admin-note", it)) }

        if (member.roles.isNotEmpty()) {
            privilegesDetailElement.appendChild(element("p", "admin-detail-subtitle", state.membersHint))
            val roles = element("div", "role-row")
            member.roles.forEach { role ->
                val box = Toggle { viewModel.onRoleToggled(member.userId, role.key, it) }
                box.checked = role.isOn
                box.disabled = !role.isEnabled
                roles.appendChild(toggleRow(box, role.description))
            }
            privilegesDetailElement.appendChild(roles)
        }
    }

    /**
     * Whether this row is an instance admin — the one with a note and no toggles.
     *
     * The view model gives an admin's row a note in place of its checkboxes (see
     * [EditProjectBackingViewModel.State.members]); this reads that shape back out
     * so the list can badge the row, rather than the view model growing a flag the
     * flat table never needed.
     */
    private fun isAdminRow(member: MemberRowState): Boolean =
        member.roles.isEmpty() && member.note != null

    private fun renderVocabularyConfirm(state: EditProjectBackingViewModel.State) {
        val pending = state.pendingVocabularyDelete
        if (pending != null && vocabularyConfirm == null) {
            vocabularyConfirm = ConfirmDialog(
                title = pending.title,
                message = pending.message,
                destructiveLabel = "Delete",
                onConfirm = { viewModel.onVocabularyDeleteConfirmed() },
                onCancel = { viewModel.onVocabularyDeleteCancelled() },
            ).also { it.mount(host) }
        } else if (pending == null && vocabularyConfirm != null) {
            vocabularyConfirm?.dismiss()
            vocabularyConfirm = null
        }
    }

    /**
     * A refusal from one of the sections, as a modal over this dialog.
     *
     * Keyed on the message rather than on "is there one", for MainScreen's
     * reason: two refusals in a row would otherwise leave the first one on screen
     * describing the second. See main.kt's renderAlert.
     */
    private fun renderAlert(state: EditProjectBackingViewModel.State) {
        val message = state.settingsErrorMessage
        if (message == alertMessage) return
        alert?.dismiss()
        alertMessage = message
        alert = message?.let {
            AlertDialog(
                title = "That did not work",
                message = it,
                onDismiss = { viewModel.onSettingsErrorDismissed() },
            ).also { dialog -> dialog.mount(host) }
        }
    }

    /** Close. The caller cancels [scope]. */
    fun dismiss() {
        vocabularyConfirm?.dismiss()
        alert?.dismiss()
        modal.dismiss()
    }

    /**
     * The frame is being closed from outside — a pane's × (LNL-160).
     *
     * Routed through the view model's Cancel rather than straight to [dismiss],
     * so closing a settings pane takes the same path as pressing Cancel in the
     * modal: whatever that decides about unsaved edits and about a draft project
     * decides here too. The pane goes when the view model says it is finished,
     * not when the button was pressed.
     */
    fun requestClose() = viewModel.onCancelTapped()

    /**
     * One vocabulary section: a heading, a hint, the rows, and the add field.
     *
     * A class rather than a function because it owns elements that must survive a
     * re-render — the add field above all, which is where the admin's cursor is
     * when they press Add.
     */
    private inner class SectionView(private val kind: VocabularyKind) {
        val root = element("div", "vocab-section")
        private val title = element("h3", "section-title")
        private val hint = element("p", "field-hint")
        private val rowsElement = element("div", "vocab-rows")
        private val addField = textField { viewModel.onVocabularyDraftChanged(kind, it) }
        private val addButton = button("Add", "btn btn-quiet vocab-add-btn") { viewModel.onAddVocabularyTapped(kind) }

        /** The rows as last rendered, so they are rebuilt only when they changed. */
        private var signature: String? = null

        init {
            val addRow = element("div", "vocab-add")
            addRow.children(addField, addButton)
            // The rows and the add field share ONE grid, which is what makes the
            // name fields line up. They are two elements rather than one because
            // the rows are rebuilt wholesale on a change and the add field must
            // survive that — it is where the admin's cursor is when they press
            // Add. `display: contents` on both is what lets the grid see past them
            // to the cells; see .vocab-list.
            root.children(title, hint, element("div", "vocab-list").children(rowsElement, addRow))
        }

        fun render(section: VocabularySection) {
            title.setTextIfChanged(section.title)
            hint.setTextIfChanged(section.hint)
            addField.setValueIfChanged(section.draftName)
            addButton.disabled = !section.isAddEnabled

            val next = section.rows.joinToString("|") { row ->
                "${row.id}:${row.name}:${row.requiresResolution}:${row.isDone}:" +
                    "${row.isDeletable}/${row.deleteBlockedReason}:${row.canMoveUp}/${row.canMoveDown}"
            }
            if (next == signature) return
            signature = next

            rowsElement.clear()
            section.rows.forEach { rowsElement.appendChild(vocabularyRow(section, it)) }
        }

        private fun vocabularyRow(section: VocabularySection, row: VocabularyRowState): HTMLElement {
            val container = element("div", "vocab-row")

            // Commit on blur, never per keystroke: this field renames a board
            // column for everybody, so an oninput version would send a request per
            // character. See Dom.kt's textFieldCommitting.
            val nameField = textFieldCommitting {
                viewModel.onVocabularyEdited(section.kind, row.id, it, row.requiresResolution, row.isDone)
            }
            nameField.value = row.name
            container.appendChild(nameField)

            // Everything after the name goes in one cell, so the grid has two
            // columns whether or not this vocabulary has a flag — and the Add
            // button below can be told to fill the same cell without knowing what
            // is in it. See .vocab-row-actions.
            val actions = element("div", "vocab-row-actions")

            if (row.showsClosingFlag) {
                val flag = Toggle {
                    // The name comes from the field rather than from `row`, so that
                    // flipping the toggle does not silently revert an edit the admin
                    // typed and has not blurred out of yet. One row, one write —
                    // the server takes the name and the flag together.
                    viewModel.onVocabularyEdited(section.kind, row.id, nameField.value, it, row.isDone)
                }
                flag.checked = row.requiresResolution
                actions.appendChild(toggleRow(flag, "needs a resolution", "vocab-flag"))
            }

            // A resolution's "means done" flag (LNL-134), the mirror of the closing
            // flag above — one row, one write, the name read from the field for the
            // same reason. It is what "require a fixed version when resolving" keys off.
            if (row.showsDoneFlag) {
                val doneFlag = Toggle {
                    viewModel.onVocabularyEdited(section.kind, row.id, nameField.value, row.requiresResolution, it)
                }
                doneFlag.checked = row.isDone
                actions.appendChild(toggleRow(doneFlag, "means done", "vocab-flag"))
            }

            // Every vocabulary is arrangeable, labels and components included —
            // the row's own canMoveUp/canMoveDown are what disable the ends.
            actions.appendChild(
                moveButton("↑", "Move up", row.canMoveUp) {
                    viewModel.onMoveVocabulary(section.kind, row.id, -1)
                },
            )
            actions.appendChild(
                moveButton("↓", "Move down", row.canMoveDown) {
                    viewModel.onMoveVocabulary(section.kind, row.id, 1)
                },
            )

            val delete = button("Delete", "btn btn-danger-quiet vocab-delete") {
                viewModel.onDeleteVocabularyTapped(section.kind, row.id)
            }
            delete.disabled = !row.isDeletable
            // Why the button is dead, on the button. A disabled control that will
            // not say why is the thing people file bugs about — and the view model
            // already wrote the sentence.
            row.deleteBlockedReason?.let { delete.title = it }
            actions.appendChild(delete)

            container.appendChild(actions)
            return container
        }

        private fun moveButton(
            glyph: String,
            description: String,
            isEnabled: Boolean,
            onClick: () -> Unit,
        ): HTMLButtonElement {
            val el = button(glyph, "btn btn-quiet vocab-move", onClick)
            el.disabled = !isEnabled
            // The glyph is an arrow and arrows do not read aloud. Everything else
            // in this dialog is a word.
            el.title = description
            el.setAttribute("aria-label", description)
            return el
        }
    }
}
