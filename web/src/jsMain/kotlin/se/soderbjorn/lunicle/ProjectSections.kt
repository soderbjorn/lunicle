/**
 * A project's settings, as the sections under it in the Projects rail (LNL-194).
 *
 * ── What this is, and where it came from ─────────────────────────────────────
 *
 * These are [ProjectDialog]'s tabs, **moved**. General, Github, Structure and Sprints
 * are the same content in the same words; the Privileges master-detail is gone and
 * Access is written fresh around audience rows and person rows. The dialog kept only
 * what a *new* project needs, which is four fields and a modal.
 *
 * The tab strip went with them. One strip and one rail, two levels: the rail lists
 * every project the caller holds something in, and the selected project's sections
 * indent beneath it. So selecting another project keeps you on the same section, and
 * comparing Access across two boards is two clicks. This class draws one project's
 * sections into a host element and shows one at a time; the rail is
 * [SettingsPane]'s.
 *
 * ── Which sections exist is the server's answer ─────────────────────────────
 *
 * Never this file's, and never the rail's: [EditProjectBackingViewModel.State.railSections]
 * is a list the server built from the rung the caller holds *on this project*, so a
 * Maintainer on one board and a Viewer on another get different sections under each
 * name. A view that decided for itself would be a second copy of the ladder, and the
 * two would disagree the first time a power moved between rungs. This file builds
 * every pane and shows the ones it is told about.
 *
 * ── Read-only is not absent ─────────────────────────────────────────────────
 *
 * A Maintainer sees the whole of General and can change none of it — the name and
 * prefix are the Owner's, the board display is an Admin's, and each group says so.
 * Hiding what somebody cannot edit only prompts "where did the project name go". The
 * same rule runs through Access: a rung out of the caller's reach shows with the
 * reason beside it rather than being dropped from the menu.
 *
 * ── Everything commits as it is made ────────────────────────────────────────
 *
 * There is no OK here — see [SettingsPane]'s preamble for why a pane spanning five
 * tabs and every project cannot have one. The name, the prefix and the repository
 * fields commit on blur through the view model's save, which is the same single write
 * the dialog's OK made; every switch and every rung writes on the click.
 *
 * @see SettingsPane for the rail this hangs under
 * @see EditProjectBackingViewModel
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
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
import se.soderbjorn.lunicle.client.viewmodel.REPOSITORY_HINT
import se.soderbjorn.lunicle.client.viewmodel.REPOSITORY_SECTION_TITLE
import se.soderbjorn.lunicle.client.viewmodel.REPOSITORY_URL_LABEL
import se.soderbjorn.lunicle.client.viewmodel.REPOSITORY_URL_PLACEHOLDER
import se.soderbjorn.lunicle.client.viewmodel.VocabularyRowState
import se.soderbjorn.lunicle.client.viewmodel.VocabularySection
import se.soderbjorn.lunicle.clientserver.AudienceRow
import se.soderbjorn.lunicle.clientserver.PersonRow
import se.soderbjorn.lunicle.clientserver.ProjectAccessState
import se.soderbjorn.lunicle.clientserver.ProjectSectionKeys
import se.soderbjorn.lunicle.clientserver.RungOption
import se.soderbjorn.lunicle.clientserver.TokenModes
import se.soderbjorn.lunicle.clientserver.VocabularyKind

/**
 * Renders one project's settings sections.
 *
 * @param viewModel the project's own view model — one per project, built by the rail
 *   and disposed with this view.
 * @param scope collects the view model's state flow; cancelled by the rail when the
 *   selection moves off this project.
 * @param dialogHost where the confirmations and refusals layer. The app's modal host
 *   rather than this view's own element: a confirmation inside a scrolling pane
 *   scrolls with it.
 * @param onSectionsChanged the server's section list arrived or changed — the rail
 *   redraws its second level. Fired on every settings emission rather than only on a
 *   difference, because the rail is cheap to redraw and a missed change is a rail
 *   offering a section that is not there.
 */
class ProjectSections(
    private val viewModel: EditProjectBackingViewModel,
    private val scope: CoroutineScope,
    private val dialogHost: HTMLElement,
    private val onSectionsChanged: () -> Unit,
) {
    /** The panes, by section key. Built once; shown and hidden by [showSection]. */
    private val panes = mutableMapOf<String, HTMLElement>()

    /** Which section is on screen, so a re-render cannot leave two panes visible. */
    private var selected: String = ProjectSectionKeys.GENERAL

    // ── General ──
    private lateinit var nameField: HTMLInputElement
    private lateinit var prefixField: HTMLInputElement
    private lateinit var prefixHint: HTMLElement
    private lateinit var identityGroup: HTMLElement
    private lateinit var identityReadOnly: HTMLElement
    private lateinit var validationElement: HTMLElement
    private lateinit var errorElement: HTMLElement
    private lateinit var boardDisplayReadOnly: HTMLElement
    private lateinit var showAuthorToggle: Toggle
    private lateinit var hideNumbersToggle: Toggle
    private lateinit var deleteGroup: HTMLElement
    private lateinit var deleteButton: HTMLButtonElement
    private lateinit var deleteReason: HTMLElement

    // ── Github ──
    private lateinit var repositoryField: HTMLInputElement
    private lateinit var tokenEnvField: HTMLInputElement
    private val tokenModeButtons = mutableMapOf<String, HTMLButtonElement>()
    private lateinit var tokenEnvGroup: HTMLElement
    private lateinit var tokenLiteralField: HTMLInputElement
    private lateinit var tokenLiteralGroup: HTMLElement

    // ── Structure ──
    private lateinit var structureSectionsElement: HTMLElement
    private lateinit var requirementsElement: HTMLElement
    private lateinit var requireLabelToggle: Toggle
    private lateinit var requireComponentToggle: Toggle
    private lateinit var requireFixedVersionToggle: Toggle

    // ── Sprints ──
    private lateinit var sprintSectionElement: HTMLElement

    // ── Access ──
    private lateinit var yourAccessElement: HTMLElement
    private lateinit var watchButton: WatchButton
    private lateinit var watchRow: HTMLElement
    private lateinit var notifyHint: HTMLElement
    private lateinit var accessReadOnly: HTMLElement
    private lateinit var audienceList: HTMLElement
    private lateinit var peopleList: HTMLElement
    private lateinit var peopleEmpty: HTMLElement
    private lateinit var adviceElement: HTMLElement
    private lateinit var addPersonButton: HTMLButtonElement
    private lateinit var accessAdminBlock: HTMLElement

    /** What the two rebuilt lists were last built from, so a busy tick does not tear them down. */
    private var audienceSignature: String? = null
    private var peopleSignature: String? = null

    /**
     * The section views, by kind, built on the first render that has settings. Kept
     * rather than rebuilt so the add field keeps its text and its focus across a
     * re-render — the field is where the admin is typing when everything else changes.
     */
    private val sectionViews = mutableMapOf<VocabularyKind, SectionView>()

    private var vocabularyConfirm: ConfirmDialog? = null
    private var deleteConfirm: ConfirmDialog? = null
    private var addPersonDialog: AddPersonDialog? = null
    private var alert: AlertDialog? = null
    private var alertMessage: String? = null

    /** Build the panes into [host] and start collecting. */
    fun mount(host: HTMLElement) {
        panes[ProjectSectionKeys.GENERAL] = buildGeneral()
        panes[ProjectSectionKeys.GITHUB] = buildGithub()
        panes[ProjectSectionKeys.STRUCTURE] = buildStructure()
        panes[ProjectSectionKeys.SPRINTS] = buildSprints()
        panes[ProjectSectionKeys.ACCESS] = buildAccess()
        host.children(*panes.values.toTypedArray())
        showSection(selected)
        scope.launch { viewModel.stateFlow.collect { render(it) } }
    }

    /**
     * Show one section, hide the rest.
     *
     * An unrecognised key — a stale bookmark naming a section this build does not have,
     * or one this caller's rung does not reach — lands on the first section the server
     * offered, which is exactly what should happen to a stale link. It is never
     * "nothing", which would read as a broken pane.
     */
    fun showSection(key: String) {
        val offered = viewModel.stateFlow.value.railSections.map { it.key }
        selected = when {
            key in panes && (offered.isEmpty() || key in offered) -> key
            else -> offered.firstOrNull() ?: ProjectSectionKeys.GENERAL
        }
        panes.forEach { (which, pane) -> pane.visible(which == selected) }
    }

    /** Which section is showing — what the address bar writes. */
    fun currentSection(): String = selected

    /** Take the confirmations down with the view. The rail cancels [scope]. */
    fun dismiss() {
        vocabularyConfirm?.dismiss()
        deleteConfirm?.dismiss()
        addPersonDialog?.dismiss()
        alert?.dismiss()
    }

    // ── General ──────────────────────────────────────────────────────────────

    /**
     * Name, prefix, how the board reads, and — for an owner — deleting it.
     *
     * Three groups, top to bottom in the order of how permanent the thing is: what the
     * project is called, how it reads, and whether it exists. Every group is shown at
     * every rung from Maintainer up and each says whose it is when it is not yours.
     */
    private fun buildGeneral(): HTMLElement {
        // Commit on blur, not per keystroke: each of these is a request, and the name is
        // unique across the instance so a per-character write would 409 its way through
        // every prefix of the new name. onNameChanged keeps the local state (and the
        // clash check) live; the commit is what sends it.
        nameField = textFieldCommitting {
            viewModel.onNameChanged(it)
            viewModel.onOkTapped()
        }
        prefixField = textFieldCommitting {
            viewModel.onPrefixChanged(it)
            viewModel.onOkTapped()
        }
        prefixHint = element("p", "field-hint")
        identityReadOnly = element("p", "admin-note")
        validationElement = element("p", "field-validation")
        errorElement = element("p", "modal-error")
        errorElement.setAttribute("role", "status")

        identityGroup = element("div", "").children(
            element("h3", "section-title", "Identity"),
            element("label", "field-label", "Name"),
            nameField,
            element("label", "field-label", "Issue prefix"),
            prefixField,
            prefixHint,
            identityReadOnly,
            validationElement,
            errorElement,
        )

        showAuthorToggle = Toggle { viewModel.onShowIssueAuthorChanged(it) }
        hideNumbersToggle = Toggle { viewModel.onHideIssueNumbersChanged(it) }
        boardDisplayReadOnly = element("p", "admin-note")
        val boardDisplayGroup = element("div", "project-display").children(
            element("h3", "section-title", "Board display"),
            element(
                "p",
                "field-hint",
                "How this board reads for everybody looking at it — not a choice for you " +
                    "alone. Set by an administrator of this project.",
            ),
            toggleRow(showAuthorToggle, "Show the issue author on board cards"),
            toggleRow(hideNumbersToggle, "Hide issue numbers on the board and in issue windows"),
            boardDisplayReadOnly,
        )

        // The delete row, where the thing being destroyed is named on screen — it was in
        // the dialog's footer, three tabs away from the project's name. It is the Owner's,
        // and an Admin sees the row dead with the rung stated rather than not at all, so
        // they learn why it is not offered to them instead of assuming a bug.
        deleteButton = button("Delete this project", "btn btn-danger-quiet") {
            viewModel.onDeleteProjectTapped()
        }
        deleteReason = element("p", "admin-note")
        deleteGroup = element("div", "").children(
            element("div", "settings-section-rule"),
            element("h3", "section-title", "Delete"),
            element(
                "p",
                "field-hint",
                "Every issue, comment and attachment in this project goes with it, and none of " +
                    "it comes back.",
            ),
            deleteButton,
            deleteReason,
        )

        return element("div", "project-pane").children(identityGroup, boardDisplayGroup, deleteGroup)
    }

    // ── Github ───────────────────────────────────────────────────────────────

    /** The repository and where its token comes from. Moved from the dialog unchanged. */
    private fun buildGithub(): HTMLElement {
        repositoryField = textFieldCommitting {
            viewModel.onRepositoryUrlChanged(it)
            viewModel.onOkTapped()
        }
        repositoryField.placeholder = REPOSITORY_URL_PLACEHOLDER
        tokenEnvField = textFieldCommitting {
            viewModel.onGithubTokenEnvChanged(it)
            viewModel.onOkTapped()
        }
        tokenEnvField.placeholder = GITHUB_TOKEN_ENV_PREFIX_EXAMPLE
        tokenLiteralField = textFieldCommitting {
            viewModel.onGithubTokenLiteralChanged(it)
            viewModel.onOkTapped()
        }

        // Each source in its own group so the radio below can show exactly one. Both carry
        // their own label and hint — the two are told apart by what they warn about, not
        // only by which field is visible. `token-field-group` restores the space above the
        // label, which is otherwise zeroed as the group's :first-child (LNL-141).
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

        val tokenModeRow = element("div", "dt-settings-button-row")
        GITHUB_TOKEN_MODE_OPTIONS.forEach { (mode, label) ->
            val btn = button(label, "dt-settings-choice-btn") {
                viewModel.onGithubTokenModeChanged(mode)
                viewModel.onOkTapped()
            }
            tokenModeButtons[mode] = btn
            tokenModeRow.appendChild(btn)
        }

        return element("div", "project-pane").children(
            element("div", "").children(
                element("h3", "settings-section-title", REPOSITORY_SECTION_TITLE),
                element("label", "field-label", REPOSITORY_URL_LABEL),
                repositoryField,
                element("p", "field-hint", REPOSITORY_HINT),
                element("label", "field-label", GITHUB_TOKEN_MODE_LABEL),
                tokenModeRow,
                tokenEnvGroup,
                tokenLiteralGroup,
            ),
        )
    }

    // ── Structure and Sprints ────────────────────────────────────────────────

    private fun buildStructure(): HTMLElement {
        structureSectionsElement = element("div", "project-structure")
        requirementsElement = buildRequirementsSection()
        // The vocabularies, then the new-ticket requirements that refer to them
        // (LNL-106): you see the labels and components a project has, then the switches
        // that decide whether a new ticket must use them.
        return element("div", "project-pane").children(structureSectionsElement, requirementsElement)
    }

    private fun buildSprints(): HTMLElement {
        sprintSectionElement = element("div", "project-structure")
        return element("div", "project-pane").children(sprintSectionElement)
    }

    /**
     * The new-ticket requirements (LNL-106). Moved from the dialog, less the
     * show-issue-author switch, which went to General's Board display group where it
     * belongs: it decides how the board reads, not what a ticket must carry (LNL-194).
     */
    private fun buildRequirementsSection(): HTMLElement {
        requireLabelToggle = Toggle { viewModel.onRequireLabelChanged(it) }
        requireComponentToggle = Toggle { viewModel.onRequireComponentChanged(it) }
        requireFixedVersionToggle = Toggle { viewModel.onRequireFixedVersionChanged(it) }
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
            element(
                "p",
                "field-hint",
                "And insist on a fixed version when an issue is closed as done. This asks only for " +
                    "resolutions ticked \"means done\" above, and only once the project has a version " +
                    "to pick — so make a version and mark a done resolution first, or it is quietly " +
                    "ignored.",
            ),
            toggleRow(requireFixedVersionToggle, "Closing as done must have a fixed version"),
        )
    }

    // ── Access ───────────────────────────────────────────────────────────────

    /**
     * Who this project admits — and, for a caller below Maintainer, what they
     * themselves hold and nothing else.
     *
     * The notification toggle rides at the top of this section for **every** caller,
     * because it is the one control here that is about the reader rather than about the
     * project — so a Viewer and an Owner find it in the same place. General is then
     * purely about the project.
     */
    private fun buildAccess(): HTMLElement {
        yourAccessElement = element("p", "modal-message")

        watchButton = WatchButton { viewModel.onNewIssueNotificationToggled(it) }
        watchRow = element("div", "project-watch-row").children(
            element("span", "watch-caption", "Notify me when a new issue is created in this project"),
            watchButton.element,
        )
        notifyHint = element(
            "p",
            "field-hint",
            "Add an e-mail address in the You tab to receive notifications.",
        )

        accessReadOnly = element("p", "admin-note")
        audienceList = element("div", "access-rows")
        peopleList = element("div", "access-rows")
        peopleEmpty = element(
            "p",
            "field-hint",
            "Nobody. Everybody who can see this board gets in through a row above, which is " +
                "the short list this is meant to be.",
        )
        adviceElement = element("p", "field-hint")
        addPersonButton = button("Add a person…", "btn btn-quiet btn-small") { openAddPerson() }

        accessAdminBlock = element("div", "").children(
            element("div", "settings-section-rule"),
            element("h3", "section-title", "Who gets in"),
            element(
                "p",
                "field-hint",
                "One row per kind of account on this deployment, each saying what that whole " +
                    "audience arrives as.",
            ),
            audienceList,
            element("h3", "section-title", "People with something different"),
            element(
                "p",
                "field-hint",
                "Exceptions only — somebody who needs more than their audience gives them. " +
                    "Whoever is served by a row above is not listed here, which is what keeps this " +
                    "list short enough to read as the record it is. Somebody's effective role is " +
                    "the best of the two, never the worst.",
            ),
            peopleList,
            peopleEmpty,
            addPersonButton,
            adviceElement,
            accessReadOnly,
        )

        return element("div", "project-pane").children(
            element("h3", "section-title", "Your access"),
            yourAccessElement,
            watchRow,
            notifyHint,
            accessAdminBlock,
        )
    }

    /** Raise the add-a-person dialog. Torn down by its own callbacks. */
    private fun openAddPerson() {
        val access = viewModel.stateFlow.value.access ?: return
        addPersonDialog?.dismiss()
        addPersonDialog = AddPersonDialog(
            rungs = access.rungs,
            advice = access.addressAdvice,
            staffDomain = access.staffDomain,
            onAdd = { email, roleKey ->
                viewModel.onPersonAdded(email, roleKey)
                addPersonDialog?.dismiss()
                addPersonDialog = null
            },
            onCancel = {
                addPersonDialog?.dismiss()
                addPersonDialog = null
            },
        ).also { it.mount(dialogHost) }
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private fun render(state: EditProjectBackingViewModel.State) {
        renderGeneral(state)
        renderGithub(state)
        renderStructure(state)
        renderAccess(state)
        renderVocabularyConfirm(state)
        renderDeleteConfirm(state)
        renderAlert(state)
        // The rail's second level is the server's list, so it can change under us: a
        // promotion, or the settings simply arriving. Told after the panes are filled, so
        // the rail never points at a pane that has not been rendered yet.
        onSectionsChanged()
        // Re-assert, so a section this caller has lost — a right withdrawn while the pane
        // was open — cannot stay showing under them.
        showSection(selected)
    }

    private fun renderGeneral(state: EditProjectBackingViewModel.State) {
        // Never while focused: these commit on blur, so mid-edit the state still holds the
        // old value and a blind re-sync would type over the person editing.
        if (kotlinx.browser.document.activeElement != nameField) nameField.setValueIfChanged(state.name)
        if (kotlinx.browser.document.activeElement != prefixField) {
            prefixField.setValueIfChanged(state.namePrefix)
        }
        nameField.disabled = !state.canConfigureIdentity
        prefixField.disabled = !state.canConfigureIdentity
        prefixHint.setTextIfChanged(
            state.prefixExample?.let { "Issues in this project will be numbered $it" } ?: "",
        )
        prefixHint.visible(state.prefixExample != null)
        identityReadOnly.setTextIfChanged(state.identityReadOnlyReason ?: "")
        identityReadOnly.visible(state.identityReadOnlyReason != null)

        validationElement.setTextIfChanged(state.validationMessage ?: "")
        validationElement.visible(state.validationMessage != null)
        errorElement.setTextIfChanged(state.errorMessage ?: "")
        errorElement.visible(state.errorMessage != null)

        showAuthorToggle.checked = state.showIssueAuthor
        hideNumbersToggle.checked = state.hideIssueNumbers
        showAuthorToggle.disabled = state.isBusy || !state.canSetBoardDisplay
        hideNumbersToggle.disabled = state.isBusy || !state.canSetBoardDisplay
        boardDisplayReadOnly.setTextIfChanged(state.boardDisplayReadOnlyReason ?: "")
        boardDisplayReadOnly.visible(state.boardDisplayReadOnlyReason != null)

        // Offered, offered-and-dead-with-a-reason, or absent. See State.canDeleteProject.
        val showsDelete = state.canDeleteProject || state.deleteBlockedReason != null
        deleteGroup.visible(showsDelete)
        deleteButton.disabled = state.isBusy || !state.canDeleteProject
        deleteReason.setTextIfChanged(state.deleteBlockedReason ?: "")
        deleteReason.visible(state.deleteBlockedReason != null)
    }

    private fun renderGithub(state: EditProjectBackingViewModel.State) {
        if (kotlinx.browser.document.activeElement != repositoryField) {
            repositoryField.setValueIfChanged(state.repositoryUrl)
        }
        if (kotlinx.browser.document.activeElement != tokenEnvField) {
            tokenEnvField.setValueIfChanged(state.githubTokenEnv)
        }
        tokenModeButtons.forEach { (mode, btn) ->
            btn.classList.toggle("dt-selected", mode == state.githubTokenMode)
        }
        tokenEnvGroup.visible(state.githubTokenMode == TokenModes.ENV)
        tokenLiteralGroup.visible(state.githubTokenMode == TokenModes.LITERAL)
        if (kotlinx.browser.document.activeElement != tokenLiteralField) {
            tokenLiteralField.setValueIfChanged(state.githubTokenLiteral)
        }
        // A stored literal is signalled by the loaded mode being `literal` while the typed
        // field is still empty; that is exactly when the placeholder should promise the
        // stored token is kept. Read off the loaded settings, not the live mode, so
        // flipping the radio on a project that never had one does not falsely claim there
        // is something to keep.
        val hasStoredLiteral = state.settings?.githubTokenMode == TokenModes.LITERAL
        tokenLiteralField.placeholder = if (hasStoredLiteral) GITHUB_TOKEN_LITERAL_STORED_PLACEHOLDER else ""
    }

    /**
     * The vocabulary sections, split across Structure and Sprints.
     *
     * Absent entirely until the settings arrive, and for a caller the server refused,
     * forever: a section that cannot be filled in is worse than no section. The view
     * model already sorts sprints last, so the split is "sprint here, everything else
     * there" — and Versions rides on Structure until ticket 6 lifts it out.
     */
    private fun renderStructure(state: EditProjectBackingViewModel.State) {
        requirementsElement.visible(state.showRequirementsSection)
        if (state.showRequirementsSection) {
            requireLabelToggle.checked = state.requireLabel
            requireLabelToggle.disabled = state.isBusy
            requireComponentToggle.checked = state.requireComponent
            requireComponentToggle.disabled = state.isBusy
            requireFixedVersionToggle.checked = state.requireFixedVersionOnResolve
            requireFixedVersionToggle.disabled = state.isBusy
        }
        if (!state.hasSettings) return
        state.sections.forEach { section ->
            val view = sectionViews.getOrPut(section.kind) {
                SectionView(section.kind).also {
                    val host = if (section.kind == VocabularyKind.SPRINT) {
                        sprintSectionElement
                    } else {
                        structureSectionsElement
                    }
                    host.appendChild(it.root)
                }
            }
            view.render(section)
        }
    }

    private fun renderAccess(state: EditProjectBackingViewModel.State) {
        yourAccessElement.setTextIfChanged(state.yourAccessLine)
        yourAccessElement.visible(state.yourAccessLine.isNotEmpty())

        val canReceive = state.canReceiveEmailNotifications
        watchRow.visible(state.showNotificationSection && canReceive, displayValue = "flex")
        notifyHint.visible(state.showNotificationSection && !canReceive)
        watchButton.render(watching = state.notifyOnNewIssue, isEnabled = !state.isBusy)

        val access = state.access
        accessAdminBlock.visible(access != null)
        if (access == null) {
            audienceSignature = null
            peopleSignature = null
            return
        }
        accessReadOnly.setTextIfChanged(access.readOnlyReason ?: "")
        accessReadOnly.visible(access.readOnlyReason != null)
        adviceElement.setTextIfChanged(access.addressAdvice)
        adviceElement.visible(access.canGrant)
        addPersonButton.visible(access.canGrant, displayValue = "inline-flex")
        addPersonButton.disabled = state.isBusy
        peopleEmpty.visible(access.people.isEmpty())

        renderAudiences(access, state.isBusy)
        renderPeople(access, state.isBusy)
    }

    /**
     * The audience rows, rebuilt only when they changed.
     *
     * Rebuilt rather than diffed because each row owns a menu whose *contents* depend on
     * the row, and only when the signature moves — a dropdown rebuilt underneath
     * somebody would close in their face. The signature carries everything rendered,
     * the greying reasons included, so a veto being lifted redraws the row.
     */
    private fun renderAudiences(access: ProjectAccessState, isBusy: Boolean) {
        val signature = "$isBusy|" + access.audiences.joinToString("|") {
            "${it.key}:${it.roleKey}:${it.isSelectable}:${it.unavailableReason}"
        }
        if (signature == audienceSignature) return
        audienceSignature = signature
        audienceList.clear()
        access.audiences.forEach { row ->
            audienceList.appendChild(audienceRow(row, access.rungs, isBusy))
        }
    }

    private fun audienceRow(row: AudienceRow, rungs: List<RungOption>, isBusy: Boolean): HTMLElement {
        val container = element("div", "access-row")
        val text = element("div", "access-row-text").children(
            element("div", "access-row-name", row.title),
            element("div", "access-row-detail", row.subtitle),
        )
        container.appendChild(text)
        val picker = rungPicker(
            rungs = rungs,
            selectedKey = row.roleKey,
            isEnabled = row.isSelectable && !isBusy,
            onPick = { key -> viewModel.onAudienceRungChanged(row.key, key) },
        )
        container.appendChild(picker)
        // The reason, beside the dead control rather than instead of it. A control that
        // vanishes reads as a bug; a dead one with a sentence tells you who to ask.
        row.unavailableReason?.let { container.appendChild(element("p", "access-row-reason", it)) }
        return container
    }

    /** The person rows, rebuilt only when they changed. [renderAudiences]' reasoning. */
    private fun renderPeople(access: ProjectAccessState, isBusy: Boolean) {
        val signature = "$isBusy|" + access.people.joinToString("|") {
            "${it.userId}:${it.name}:${it.roleKey}:${it.hasSignedIn}:${it.isEditable}:${it.note}:${it.effectiveLine}"
        }
        if (signature == peopleSignature) return
        peopleSignature = signature
        peopleList.clear()
        access.people.forEach { person ->
            peopleList.appendChild(personRow(person, access.rungs, isBusy))
        }
    }

    private fun personRow(person: PersonRow, rungs: List<RungOption>, isBusy: Boolean): HTMLElement {
        val container = element("div", "access-row")
        // element(text = …) sets textContent, never innerHTML — a display name is
        // user-chosen, so this is what puts "<img onerror=…>" on the page as characters.
        val name = element("span", "access-row-name", if (person.isSelf) "${person.name} (you)" else person.name)
        val nameLine = element("div", "access-row-name-line").children(name)
        // The badge, because a grant nobody has claimed looks exactly like one that has.
        if (!person.hasSignedIn) {
            nameLine.appendChild(element("span", "access-row-badge", "NOT SIGNED IN"))
        }
        val text = element("div", "access-row-text").children(nameLine)
        if (person.email.isNotBlank()) {
            text.appendChild(element("div", "access-row-detail", person.email))
        }
        person.effectiveLine?.let { text.appendChild(element("div", "access-row-detail", it)) }
        container.appendChild(text)

        if (person.isEditable) {
            container.appendChild(
                rungPicker(
                    rungs = rungs,
                    selectedKey = person.roleKey,
                    isEnabled = !isBusy,
                    onPick = { key -> viewModel.onPersonRungChanged(person.userId, key) },
                ),
            )
        }
        person.note?.let { container.appendChild(element("p", "access-row-reason", it)) }
        return container
    }

    /**
     * A rung menu: every rung the server sent, plus "No access", with the ones this
     * caller may not hand out dead and the reason on them.
     *
     * Ids are positions in [rungs] because [Dropdown] is keyed on Long and a rung is
     * keyed on a string. Local to this call, which is safe: the menu is rebuilt with the
     * row, so an index can never outlive the list it indexes.
     */
    private fun rungPicker(
        rungs: List<RungOption>,
        selectedKey: String?,
        isEnabled: Boolean,
        onPick: (String?) -> Unit,
    ): HTMLElement {
        val items = mutableListOf(DropdownItem(NO_ACCESS_ID, "No access"))
        rungs.forEachIndexed { index, rung ->
            // The reason rides in the label, because the menu draws rows and not rows with
            // sub-rows. It is the only place a dead rung can say why while still being
            // visible — and it must stay visible: "a rung out of the caller's reach shows
            // with the reason, not omitted".
            val label = if (rung.isSelectable) rung.label else "${rung.label} — ${rung.unavailableReason}"
            items.add(DropdownItem(index.toLong(), label))
        }
        lateinit var dropdown: Dropdown
        dropdown = Dropdown(isField = true) { id ->
            when {
                id == NO_ACCESS_ID -> onPick(null)
                else -> {
                    val rung = rungs.getOrNull(id.toInt()) ?: return@Dropdown
                    // Refused here as well as at the route, so a click on a rung the caller
                    // may not hand out does nothing rather than making a request that 403s.
                    if (rung.isSelectable) onPick(rung.key) else dropdown.close()
                }
            }
        }
        val selectedId = selectedKey
            ?.let { key -> rungs.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.toLong() }
            ?: NO_ACCESS_ID
        dropdown.render(items, selectedId, placeholder = "No access", unsetId = NO_ACCESS_ID)
        dropdown.element.disabled = !isEnabled
        return dropdown.element
    }

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
            ).also { it.mount(dialogHost) }
        } else if (pending == null && deleteConfirm != null) {
            deleteConfirm?.dismiss()
            deleteConfirm = null
        }
    }

    private fun renderVocabularyConfirm(state: EditProjectBackingViewModel.State) {
        val pending = state.pendingVocabularyDelete
        if (pending != null && vocabularyConfirm == null) {
            vocabularyConfirm = ConfirmDialog(
                title = pending.title,
                message = pending.message,
                destructiveLabel = "Delete",
                onConfirm = { viewModel.onVocabularyDeleteConfirmed() },
                onCancel = { viewModel.onVocabularyDeleteCancelled() },
            ).also { it.mount(dialogHost) }
        } else if (pending == null && vocabularyConfirm != null) {
            vocabularyConfirm?.dismiss()
            vocabularyConfirm = null
        }
    }

    /**
     * A refusal from one of the sections, as a modal over the pane.
     *
     * Keyed on the message rather than on "is there one": two refusals in a row would
     * otherwise leave the first on screen describing the second.
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
            ).also { dialog -> dialog.mount(dialogHost) }
        }
    }

    private companion object {
        /**
         * The menu id that means "no access".
         *
         * Negative, so it cannot collide with a position in the rung list however long
         * that list grows.
         */
        const val NO_ACCESS_ID = -1L
    }

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
                    "${row.isDeletable}/${row.deleteBlockedReason}/${row.deleteBlockedSummary}:" +
                    "${row.canMoveUp}/${row.canMoveDown}"
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

            // Why the button is dead, VISIBLY (LNL-183). This used to be the
            // button's `title` and nothing else, on the reasoning that a disabled
            // control which will not say why is the thing people file bugs about —
            // which is exactly what got filed, because a tooltip on a greyed-out
            // control is an explanation you have to already suspect is there to go
            // looking for. So the short form sits beside the button where it cannot
            // be missed, and the sentence that says what to do about it stays on
            // the title for whoever does hover.
            row.deleteBlockedSummary?.let { actions.appendChild(element("span", "vocab-blocked", it)) }

            val delete = button("Delete", "btn btn-danger-quiet vocab-delete") {
                viewModel.onDeleteVocabularyTapped(section.kind, row.id)
            }
            delete.disabled = !row.isDeletable
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
