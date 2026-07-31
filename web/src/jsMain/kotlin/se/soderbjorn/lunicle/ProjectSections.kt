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
 * Versions is a sixth section since LNL-196 — lifted out of Structure, where it was one
 * list among the labels, to sit beside Sprints. The two belong together because they are
 * the two whose *presence* is the feature flag: make the first one and something turns on
 * across the whole board. The fixed-version requirement came with it, out of Structure's
 * ticket requirements and under the list it cannot be satisfied without.
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

import kotlinx.browser.window
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
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
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
 * @param onSettingsWritten a settings write landed, so whatever else is on screen has
 *   to catch up. The board reads the display switches and the vocabulary off its own
 *   response, so turning issue numbers off here has to reach it — in the tabbed dialog
 *   that happened when the dialog *closed*, which a pane has no equivalent of. Fired on
 *   the change rather than on every emission, so it is not a board fetch per tick.
 */
class ProjectSections(
    private val viewModel: EditProjectBackingViewModel,
    private val scope: CoroutineScope,
    private val dialogHost: HTMLElement,
    private val onSectionsChanged: () -> Unit,
    private val onSettingsWritten: () -> Unit,
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

    // ── Sprints ──
    private lateinit var sprintSectionElement: HTMLElement
    private lateinit var sprintReadOnly: HTMLElement

    // ── Versions ──
    private lateinit var versionSectionElement: HTMLElement
    private lateinit var versionReadOnly: HTMLElement
    private lateinit var fixedVersionGroup: HTMLElement
    private lateinit var requireFixedVersionToggle: Toggle
    private lateinit var fixedVersionReadOnly: HTMLElement
    private lateinit var fixedVersionCaveat: HTMLElement

    // ── Access ──
    private lateinit var yourAccessElement: HTMLElement
    private lateinit var watchButton: WatchButton
    private lateinit var watchRow: HTMLElement
    private lateinit var notifyHint: HTMLElement
    private lateinit var accessReadOnly: HTMLElement
    private lateinit var audienceList: HTMLElement
    private lateinit var visibilityElement: HTMLElement
    private lateinit var peopleList: HTMLElement
    private lateinit var peopleEmpty: HTMLElement
    private lateinit var adviceElement: HTMLElement
    private lateinit var addPersonButton: HTMLButtonElement
    private lateinit var addPeopleHost: HTMLElement
    private lateinit var accessAdminBlock: HTMLElement
    private lateinit var rungLegend: HTMLElement

    /** What the three rebuilt lists were last built from, so a busy tick does not tear them down. */
    private var audienceSignature: String? = null
    private var peopleSignature: String? = null
    private var rungLegendSignature: String? = null

    /**
     * The section views, by kind, built on the first render that has settings. Kept
     * rather than rebuilt so the add field keeps its text and its focus across a
     * re-render — the field is where the admin is typing when everything else changes.
     */
    private val sectionViews = mutableMapOf<VocabularyKind, SectionView>()

    /**
     * The settings as last rendered, so a write can be told from a redraw.
     *
     * The pair with [EditProjectBackingViewModel.State.hasWrittenSettings]: that flag is a
     * latch and says only "something has been written at some point", so it cannot on its
     * own tell the emission that carried a change from the twenty that followed it.
     */
    private var lastSettings: ProjectSettingsState? = null

    private var vocabularyConfirm: ConfirmDialog? = null
    private var completeSprintDialog: CompleteSprintDialog? = null
    private var deleteConfirm: ConfirmDialog? = null
    /**
     * The people picker, built on first open and then kept (LNL-204).
     *
     * Kept rather than rebuilt for the reason [sectionViews] is: it owns a search field
     * that holds focus and a caret, and a panel rebuilt on a state tick is a panel you
     * cannot type into. It is hidden when the view model reports no picker, not thrown
     * away.
     */
    private var addPeoplePanel: AddPeoplePanel? = null

    /**
     * How many people the open picker had added as of the last render.
     *
     * Only so a *change* in it can scroll the panel back into view — see [renderPeoplePicker].
     * -1 rather than 0 for "no picker has been open", so opening one that somehow starts with
     * additions still counts as a change.
     */
    private var lastPickerAddedCount: Int = -1
    private var alert: AlertDialog? = null
    private var alertMessage: String? = null

    /** Build the panes into [host] and start collecting. */
    fun mount(host: HTMLElement) {
        panes[ProjectSectionKeys.GENERAL] = buildGeneral()
        panes[ProjectSectionKeys.GITHUB] = buildGithub()
        panes[ProjectSectionKeys.STRUCTURE] = buildStructure()
        panes[ProjectSectionKeys.SPRINTS] = buildSprints()
        panes[ProjectSectionKeys.VERSIONS] = buildVersions()
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

    /**
     * Re-fetch this project's settings, because instance configuration changed under them.
     *
     * Several answers in these sections are computed from instance settings rather than from
     * the project row — whether a guest row is in effect, whether a new outside address may
     * be added — so a switch flipped on the pane's instance tabs has to reach here or the
     * section goes on describing the old policy. See EditProjectBackingViewModel.reloadSettings.
     */
    fun reloadSettings() = viewModel.reloadSettings()

    /** Which section is showing — what the address bar writes. */
    fun currentSection(): String = selected

    /** Take the confirmations down with the view. The rail cancels [scope]. */
    fun dismiss() {
        vocabularyConfirm?.dismiss()
        completeSprintDialog?.dismiss()
        deleteConfirm?.dismiss()
        // The people picker needs no dismissal: it is a panel inside this view rather than a
        // layer over it, so it goes when the view does. Its *state* is the view model's, and
        // is dropped when the pane closes with it.
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

    // ── Structure, Sprints and Versions ──────────────────────────────────────

    private fun buildStructure(): HTMLElement {
        structureSectionsElement = element("div", "project-structure")
        requirementsElement = buildRequirementsSection()
        // The vocabularies, then the new-ticket requirements that refer to them
        // (LNL-106): you see the labels and components a project has, then the switches
        // that decide whether a new ticket must use them.
        return element("div", "project-pane").children(structureSectionsElement, requirementsElement)
    }

    /**
     * The sprints, and per row the completion date with the action that sets it
     * (LNL-196).
     *
     * The Complete control used to be a row in the board's scope picker. It ended
     * everybody's columns from a control that reads as a view switch, and it sat within
     * reach of whoever happened to be looking at the board rather than of the people
     * planning it. Here it is one action per row, beside the date, at the rung that owns
     * the sprints — and the board keeps the scope picker, which is about this view.
     */
    private fun buildSprints(): HTMLElement {
        sprintSectionElement = element("div", "project-structure")
        sprintReadOnly = element("p", "admin-note")
        return element("div", "project-pane").children(sprintSectionElement, sprintReadOnly)
    }

    /**
     * The versions, and under them the one rule that cannot be satisfied without one
     * (LNL-196).
     *
     * Its own section, beside Sprints, because it is the other vocabulary whose
     * *presence* is the feature flag: make the first version and two fields appear on
     * every issue, exactly as making the first sprint gives the board a scope picker. It
     * used to be rendered inside Structure among the labels and components, one rung
     * above the caller who is allowed to change it and beside a list of things it does
     * not resemble.
     */
    private fun buildVersions(): HTMLElement {
        versionSectionElement = element("div", "project-structure")
        versionReadOnly = element("p", "admin-note")
        return element("div", "project-pane").children(
            versionSectionElement,
            versionReadOnly,
            buildFixedVersionGroup(),
        )
    }

    /**
     * The new-ticket requirements (LNL-106). Moved from the dialog, less the
     * show-issue-author switch, which went to General's Board display group where it
     * belongs: it decides how the board reads, not what a ticket must carry (LNL-194).
     *
     * Two switches, not three. The fixed-version rule moved to the Versions section
     * (LNL-196) — see [buildFixedVersionGroup] — and this keeps a pointer, because
     * somebody who came to Structure looking for "must have a fixed version" has to be
     * told where it went rather than left concluding it was withdrawn.
     */
    private fun buildRequirementsSection(): HTMLElement {
        requireLabelToggle = Toggle { viewModel.onRequireLabelChanged(it) }
        requireComponentToggle = Toggle { viewModel.onRequireComponentChanged(it) }
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
                "The third requirement — that closing as done must name a fixed version — is in the " +
                    "Versions section, under the list of versions it needs.",
            ),
        )
    }

    /**
     * "Closing as done must have a fixed version", under the list it depends on
     * (LNL-196).
     *
     * It sat in Structure's requirements beside its two siblings, two sections away from
     * the versions that make it satisfiable at all. A switch whose precondition is a
     * list somewhere else is a switch that reads as broken the first time somebody turns
     * it on with an empty list.
     *
     * The rule is still an **administrator's**, and it did not move with the switch — so
     * for a Maintainer this is the one dead control in the section, with the sentence
     * saying whose it is. [fixedVersionCaveat] is the other half: the switch is quietly
     * ignored until there is a version and a resolution ticked "means done", and that
     * has to be said where the switch is rather than discovered by it not working.
     */
    private fun buildFixedVersionGroup(): HTMLElement {
        requireFixedVersionToggle = Toggle { viewModel.onRequireFixedVersionChanged(it) }
        fixedVersionReadOnly = element("p", "admin-note")
        fixedVersionCaveat = element("p", "field-hint")
        fixedVersionGroup = element("div", "project-requirements").children(
            element("div", "settings-section-rule"),
            element("h3", "section-title", "Closing an issue"),
            element(
                "p",
                "field-hint",
                "One requirement lives here rather than with the other two in Structure, because it " +
                    "is about the list above: an issue cannot name a fixed version until this project " +
                    "has one.",
            ),
            toggleRow(requireFixedVersionToggle, "Closing as done must have a fixed version"),
            fixedVersionCaveat,
            fixedVersionReadOnly,
        )
        return fixedVersionGroup
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
        rungLegend = element("div", "rung-legend")

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
        // The answer, under the rows that produce it. Its own element rather than part of
        // the rebuilt list, because it is a statement about the section and not a row —
        // and because the list is torn down and rebuilt on a signature change, which this
        // must survive. Worded by the server; see ProjectAccessState.visibilityLine.
        visibilityElement = element("p", "access-visibility")
        peopleList = element("div", "access-rows")
        peopleEmpty = element(
            "p",
            "field-hint",
            "Nobody. Everybody who can see this board gets in through a row above, which is " +
                "the short list this is meant to be.",
        )
        adviceElement = element("p", "field-hint")
        addPersonButton = button("Add people…", "btn btn-quiet btn-small") {
            viewModel.onAddPeopleOpened()
        }
        // Where the picker mounts, between the button that opens it and the advice line.
        // Empty until the first open; see renderAccess.
        addPeopleHost = element("div", "add-people-host")

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
            visibilityElement,
            element("h3", "section-title", "Extra access for individuals"),
            // Three facts, in the order somebody reads them: what the rows above did, who
            // this list is for, and what being on it can and cannot do. The old wording said
            // the same things but argued for the design in the middle of them — "which is
            // what keeps this list short enough to read as the record it is" — and the
            // sentence that mattered ("the best of the two, never the worst") arrived last
            // and in the vocabulary of the fold rather than of the screen.
            element(
                "p",
                "field-hint",
                "The rows above admit whole audiences. This is for the people who need more " +
                    "than their audience gives them, and only them. Whoever is already covered " +
                    "above is left out, and being listed here can only add — never take away.",
            ),
            peopleList,
            peopleEmpty,
            addPersonButton,
            addPeopleHost,
            adviceElement,
            accessReadOnly,
            // ── The ladder, spelled out (LNL-217) ────────────────────────────
            //
            // Every control above hands out a rung, and until now no screen said what a
            // rung *is*. The picker structurally cannot: `rungPicker` draws rows and not
            // rows with sub-rows, which is why a dead rung's reason has to ride inside its
            // label as an em-dash clause. So the only place the ladder can be explained is
            // beside the rows, and it was not being explained anywhere.
            //
            // Last rather than first, and always open rather than behind a disclosure.
            // Last because it is reference material — you come to it from a row you were
            // already reading, not before you have seen one. Always open because the
            // person who does not know what Maintainer means is precisely the person who
            // will not guess that a collapsed thing is hiding the answer.
            element("div", "settings-section-rule"),
            element("h3", "section-title", "What each rung can do"),
            element(
                "p",
                "field-hint",
                "The same five rungs everywhere on this screen. Each one contains the ones " +
                    "above it in this list, so a Maintainer can do everything a Contributor can.",
            ),
            rungLegend,
        )

        return element("div", "project-pane").children(
            element("h3", "section-title", "Your access"),
            yourAccessElement,
            watchRow,
            notifyHint,
            accessAdminBlock,
        )
    }

    /**
     * The people picker, mounted on its first open and thereafter shown or hidden.
     *
     * Every gesture goes straight to the view model — the panel decides nothing about who
     * may be granted what. Built lazily because most visits to this section are to read it.
     */
    private fun addPeoplePanel(projectName: String): AddPeoplePanel =
        addPeoplePanel ?: AddPeoplePanel(
            projectName = projectName,
            onQueryChanged = { viewModel.onAddPeopleQueryChanged(it) },
            onRoleChanged = { viewModel.onAddPeopleRoleChanged(it) },
            onPicked = { viewModel.onCandidatePicked(it) },
            onNewAddressTaken = { viewModel.onNewAddressAdded() },
            onUndo = { viewModel.onAddedPeopleUndone() },
            onClose = { viewModel.onAddPeopleClosed() },
        ).also {
            addPeoplePanel = it
            addPeopleHost.appendChild(it.mount())
        }

    // ── Rendering ────────────────────────────────────────────────────────────

    private fun render(state: EditProjectBackingViewModel.State) {
        renderGeneral(state)
        renderGithub(state)
        renderStructure(state)
        renderAccess(state)
        renderVocabularyConfirm(state)
        renderSprintCompletion(state)
        renderDeleteConfirm(state)
        renderAlert(state)
        // The rail's second level is the server's list, so it can change under us: a
        // promotion, or the settings simply arriving. Told after the panes are filled, so
        // the rail never points at a pane that has not been rendered yet.
        onSectionsChanged()
        // A write landed — not merely a redraw. The board is what needs telling: it reads
        // the display switches, the vocabularies and the project's name off its own
        // response, and none of that follows a settings write on its own.
        val settings = state.settings
        if (state.hasWrittenSettings && settings != null && settings != lastSettings) {
            onSettingsWritten()
        }
        lastSettings = settings
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
     * The vocabulary sections, across three panes (LNL-196).
     *
     * Two different gates, because the two halves belong to two rungs. The five lists
     * that define what the board *is* are an administrator's and are absent below that,
     * forever: a section that cannot be filled in is worse than no section. The sprints
     * and the versions are a maintainer's, render from Maintainer up, and are read-only
     * below rather than absent — knowing what the releases are is not the same as being
     * able to change them.
     *
     * Which host a section lands in is a lookup on the kind and nothing more. That is the
     * whole of "lift Versions out of Structure": the view already did exactly this for
     * sprints, so the change is a third host, not a change to the view model.
     */
    private fun renderStructure(state: EditProjectBackingViewModel.State) {
        requirementsElement.visible(state.showRequirementsSection)
        if (state.showRequirementsSection) {
            requireLabelToggle.checked = state.requireLabel
            requireLabelToggle.disabled = state.isBusy
            requireComponentToggle.checked = state.requireComponent
            requireComponentToggle.disabled = state.isBusy
        }

        // The fixed-version rule, in the Versions section now. Shown from Maintainer up
        // and live only for an administrator, which is the one dead control down here.
        fixedVersionGroup.visible(state.showFixedVersionRequirement)
        if (state.showFixedVersionRequirement) {
            requireFixedVersionToggle.checked = state.requireFixedVersionOnResolve
            requireFixedVersionToggle.disabled = state.isBusy || !state.canSetRequirements
            fixedVersionReadOnly.setTextIfChanged(state.requirementsReadOnlyReason ?: "")
            fixedVersionReadOnly.visible(state.requirementsReadOnlyReason != null)
            // Said under the switch rather than left to be discovered: with no version
            // above, or no resolution ticked "means done", the rule is quietly ignored —
            // and a switch that is on and does nothing is the thing people file bugs
            // about. Worded by the view model, which is the half that knows what this
            // caller was actually sent; see State.fixedVersionCaveat.
            fixedVersionCaveat.setTextIfChanged(state.fixedVersionCaveat)
        }

        // The maintainer half first, so a Maintainer's two sections fill even though the
        // administrator's five never will.
        if (state.hasPlanningSettings) {
            sprintReadOnly.setTextIfChanged(state.planningReadOnlyReason ?: "")
            sprintReadOnly.visible(state.planningReadOnlyReason != null)
            versionReadOnly.setTextIfChanged(state.planningReadOnlyReason ?: "")
            versionReadOnly.visible(state.planningReadOnlyReason != null)
            state.planningSections.forEach { renderVocabularySection(it) }
        }
        if (!state.hasSettings) return
        state.structureSections.forEach { renderVocabularySection(it) }
    }

    /**
     * Draw one vocabulary section into the pane its kind belongs to.
     *
     * The views are kept rather than rebuilt so the add field keeps its text and its
     * focus across a re-render — see [sectionViews].
     */
    private fun renderVocabularySection(section: VocabularySection) {
        val view = sectionViews.getOrPut(section.kind) {
            SectionView(section.kind).also {
                val host = when (section.kind) {
                    VocabularyKind.SPRINT -> sprintSectionElement
                    VocabularyKind.VERSION -> versionSectionElement
                    else -> structureSectionsElement
                }
                host.appendChild(it.root)
            }
        }
        view.render(section)
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
            rungLegendSignature = null
            return
        }
        renderRungLegend(access)
        accessReadOnly.setTextIfChanged(access.readOnlyReason ?: "")
        accessReadOnly.visible(access.readOnlyReason != null)
        // Set on every render rather than inside renderAudiences' signature guard: it is
        // cheap, and it must not be able to drift from the rows it summarises.
        visibilityElement.setTextIfChanged(access.visibilityLine)
        visibilityElement.visible(access.visibilityLine.isNotEmpty())
        val picker = state.peoplePicker
        // The advice line moves INTO the picker while it is open (it is the offer row's own
        // note there), so showing it underneath as well would be the same sentence twice.
        adviceElement.setTextIfChanged(access.addressAdvice)
        adviceElement.visible(access.canGrant && picker == null)
        // Either the button or the panel, never both: the panel is what the button became.
        addPersonButton.visible(access.canGrant && picker == null, displayValue = "inline-flex")
        addPersonButton.disabled = state.isBusy
        peopleEmpty.visible(access.people.isEmpty())

        // Both lists are torn down and rebuilt when their signature moves, and clearing a
        // seventeen-row list collapses the pane's scroll height for an instant — long
        // enough for the browser to clamp scrollTop to the new maximum, which it then keeps
        // when the rebuild makes the pane tall again. The reader is dumped at the top of the
        // section every time anything is granted.
        //
        // Harmless-looking until the people picker, which is *built* to be used repeatedly:
        // pick somebody, get thrown to the top, scroll back down, pick the next. Found by
        // driving it. Restored around both rebuilds rather than inside one, because either
        // can be the one that collapses the height.
        preservingScroll {
            renderAudiences(access, state.isBusy)
            renderPeople(access, state.isBusy)
        }
        renderPeoplePicker(state, access)
    }

    /**
     * Run [block], leaving the scrolling pane where it was.
     *
     * The scroller is the `.project-pane` a section is drawn into — see the stylesheet —
     * which is [peopleList]'s ancestor rather than [peopleList] itself. Read back before
     * restoring so a genuinely shorter list (somebody's row withdrawn) still settles at its
     * own new maximum instead of being forced to a position that no longer exists.
     */
    private fun preservingScroll(block: () -> Unit) {
        val scroller = peopleList.closest(".project-pane") as? HTMLElement
        val before = scroller?.scrollTop
        block()
        if (scroller != null && before != null && scroller.scrollTop != before) {
            scroller.scrollTop = before
        }
    }

    /**
     * The people picker, when the view model says it is open (LNL-204).
     *
     * Mounted on first open and thereafter shown or hidden — see [addPeoplePanel] for why it
     * is not torn down. Focused on the open that creates it, so the panel can be typed at
     * immediately; not on every open thereafter, which would steal the caret from somebody
     * who had clicked into the list.
     */
    private fun renderPeoplePicker(state: EditProjectBackingViewModel.State, access: ProjectAccessState) {
        val picker = state.peoplePicker
        if (picker == null) {
            addPeopleHost.visible(false)
            return
        }
        val isFirstMount = addPeoplePanel == null
        val panel = addPeoplePanel(state.name.ifEmpty { "this project" })
        addPeopleHost.visible(true)
        panel.render(picker, access, state.isBusy)
        if (isFirstMount) panel.focus()

        // Keep the panel in view when a pick lands, because a pick moves the ground under it:
        // the person list above grows by a row, and the panel is at the very bottom of a long
        // scrolling section. `preservingScroll` above holds the position steady for an
        // ordinary rung edit, and is not enough here — the section is torn down and rebuilt
        // through more than one path on a write, and a panel built to be used four times in a
        // row must not scroll away after the first.
        //
        // Keyed on the added COUNT rather than on every render, so this cannot fight somebody
        // who has deliberately scrolled up to read the list while the panel is open. Typing in
        // the search field writes nothing and therefore never triggers it.
        val added = picker.addedUserIds.size
        if (!isFirstMount && added != lastPickerAddedCount) {
            // On the NEXT frame, not now. A pick produces more than one render — the write's
            // own emission, the candidate re-search behind it, and the board reload that
            // `onSettingsWritten` triggers, which sends the rail through `showSection` and
            // re-shows this pane. Scrolling during any of those is undone by the next one.
            // Waiting a frame puts this after all of them, which is the only position from
            // which it holds.
            window.requestAnimationFrame {
                panel.element.scrollIntoView(js("({ block: 'nearest' })"))
            }
        }
        lastPickerAddedCount = added
    }

    /**
     * The audience rows, rebuilt only when they changed.
     *
     * Rebuilt rather than diffed because each row owns a menu whose *contents* depend on
     * the row, and only when the signature moves — a dropdown rebuilt underneath
     * somebody would close in their face. The signature carries everything rendered,
     * the greying reasons included, so a veto being lifted redraws the row.
     */
    /**
     * The ladder, one row per rung, from what the server sent.
     *
     * ── Rendered from `access.rungs`, never from words in this bundle ───────
     *
     * The labels and the sentences both come off the wire, which is the discipline
     * `toRow` follows in the admin pane and it matters more here than there: this is the
     * screen that *defines* the vocabulary, so a bundle that had its own copy could
     * describe a rolled-back server's ladder in this build's words and be confidently
     * wrong about the thing it exists to explain. The strings are written once, on
     * `ProjectRole`, beside the enum that grants them — see [RungOption.description],
     * whose whole point is that no screen can disagree with the thing handing out rungs.
     *
     * Every rung is drawn, including ones this caller cannot hand out. The legend answers
     * "what does Admin mean", which is a question somebody has whether or not they may
     * make one — and greying rows here would conflate "not yours to give" with "not
     * relevant to you", which the pickers already say properly on the rows themselves.
     *
     * Signature-guarded like the two lists above, for the same reason and not because
     * this one is expensive: it never changes within a session, so a busy tick rebuilding
     * it is pure churn in the middle of a pane somebody is reading.
     */
    private fun renderRungLegend(access: ProjectAccessState) {
        val signature = access.rungs.joinToString("|") { "${it.key}:${it.label}:${it.description}" }
        if (signature == rungLegendSignature) return
        rungLegendSignature = signature

        rungLegend.clear()
        access.rungs.forEach { rung ->
            // Both cells appended straight to the grid, with no per-rung wrapper: that is
            // what puts every description on one left edge without `subgrid`. See the
            // stylesheet, which says what to do if a row ever needs to be an element.
            rungLegend.appendChild(element("div", "rung-legend-name", rung.label))
            rungLegend.appendChild(element("div", "rung-legend-detail", rung.description))
        }
    }

    private fun renderAudiences(access: ProjectAccessState, isBusy: Boolean) {
        val signature = "$isBusy|" + access.audiences.joinToString("|") {
            // The row's own rung list is in the signature too (LNL-202): the greying inside
            // a menu is per audience now, so a menu whose dead rungs moved must be rebuilt
            // even when nothing about the row around it did.
            // The floor's three fields too (LNL-209): lowering the guests row changes what
            // the members row reads and which of its entries are struck, and a row whose
            // menu moved must be rebuilt even when its own rung did not.
            "${it.key}:${it.roleKey}:${it.isSelectable}:${it.unavailableReason}:" +
                "${it.withdrawRefusal}:${it.effectiveLine}:" +
                it.rungs.joinToString(",") { rung -> "${rung.key}/${rung.isSelectable}" }
        }
        if (signature == audienceSignature) return
        audienceSignature = signature
        audienceList.clear()
        access.audiences.forEach { row ->
            audienceList.appendChild(audienceRow(row, isBusy))
        }
    }

    /**
     * One audience row and its menu.
     *
     * The menu is built from **the row's own** rung list rather than the section's shared
     * one (LNL-202), which is what makes the Guests row offer Viewer and show the rest
     * greyed with the reason on them. Nothing here decides that: [rungPicker] already
     * draws a dead rung with its sentence, and the server already decided which are dead.
     * Deliberately so — a ceiling applied in the browser would be a second copy of the
     * ladder beside the one that refuses the write, and the two would part company.
     */
    private fun audienceRow(row: AudienceRow, isBusy: Boolean): HTMLElement {
        val container = element("div", "access-row")
        val text = element("div", "access-row-text").children(
            element("div", "access-row-name", row.title),
            element("div", "access-row-detail", row.subtitle),
        )
        // Where the rung comes from, when a wider row is the one giving it (LNL-209) —
        // beneath the subtitle, exactly where a person row says the same thing about the
        // same rule. A detail line and not a reason: nothing here is refused or dead.
        row.effectiveLine?.let { text.appendChild(element("div", "access-row-detail", it)) }
        container.appendChild(text)
        val picker = rungPicker(
            rungs = row.rungs,
            selectedKey = row.roleKey,
            isEnabled = row.isSelectable && !isBusy,
            withdrawRefusal = row.withdrawRefusal,
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
        } else {
            // The rung as plain text where there is no picker. Without this a read-only
            // reader — a Maintainer, who is told they "can see who is here" — saw a list of
            // names and addresses with no indication of what any of them held, which is the
            // one thing the list is for. Found by driving the app.
            //
            // Blank for a row whose rung is not stored at all (an instance administrator):
            // their note says where their Owner comes from, and a label beside it would
            // claim a row that does not exist.
            val label = person.roleKey?.let { key -> rungs.firstOrNull { it.key == key }?.label }
            if (label != null) container.appendChild(element("span", "access-row-rung", label))
        }
        person.note?.let { container.appendChild(element("p", "access-row-reason", it)) }
        return container
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

    /**
     * "This sprint is over — where does the unfinished work go?" (LNL-196).
     *
     * The same view the board's scope picker used to raise, mounted from here instead.
     * Keyed on presence like the other two confirmations, so a second sprint's prompt
     * cannot open under the first one's.
     */
    private fun renderSprintCompletion(state: EditProjectBackingViewModel.State) {
        val pending = state.pendingSprintCompletion
        if (pending != null && completeSprintDialog == null) {
            completeSprintDialog = CompleteSprintDialog(
                prompt = pending,
                onComplete = { viewModel.onSprintCompletionConfirmed(it) },
                onCancel = { viewModel.onSprintCompletionCancelled() },
            ).also { it.mount(dialogHost) }
        } else if (pending == null && completeSprintDialog != null) {
            completeSprintDialog?.dismiss()
            completeSprintDialog = null
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
        private val readOnly = element("p", "admin-note")
        private val rowsElement = element("div", "vocab-rows")
        private val addField = textField { viewModel.onVocabularyDraftChanged(kind, it) }
        private val addButton = button("Add", "btn btn-quiet vocab-add-btn") { viewModel.onAddVocabularyTapped(kind) }
        private val addRow = element("div", "vocab-add")

        /** The rows as last rendered, so they are rebuilt only when they changed. */
        private var signature: String? = null

        init {
            addRow.children(addField, addButton)
            // The rows and the add field share ONE grid, which is what makes the
            // name fields line up. They are two elements rather than one because
            // the rows are rebuilt wholesale on a change and the add field must
            // survive that — it is where the admin's cursor is when they press
            // Add. `display: contents` on both is what lets the grid see past them
            // to the cells; see .vocab-list.
            root.children(title, hint, readOnly, element("div", "vocab-list").children(rowsElement, addRow))
        }

        fun render(section: VocabularySection) {
            title.setTextIfChanged(section.title)
            hint.setTextIfChanged(section.hint)
            addField.setValueIfChanged(section.draftName)
            addButton.disabled = !section.isAddEnabled
            // The add field goes away entirely for a reader who cannot add, rather than
            // being greyed. It is the one control here where a dead version explains
            // nothing: a greyed Delete at least sits beside the row it would have removed,
            // where an empty text box with a dead Add beside it is just furniture. The
            // sentence under the hint is what says why (LNL-196).
            addRow.visible(section.isEditable, displayValue = "contents")
            readOnly.setTextIfChanged(section.readOnlyReason ?: "")
            readOnly.visible(section.readOnlyReason != null)

            val next = section.rows.joinToString("|") { row ->
                "${row.id}:${row.name}:${row.requiresResolution}:${row.isDone}:" +
                    "${row.isDeletable}/${row.deleteBlockedReason}/${row.deleteBlockedSummary}:" +
                    "${row.canMoveUp}/${row.canMoveDown}:${row.isEditable}:" +
                    "${row.completionLine}/${row.completionActionLabel}"
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
            // Dead for a reader who may not write here, and still shown with the name in
            // it — a list you can read and not change is worth reading (LNL-196).
            nameField.disabled = !row.isEditable
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
                flag.disabled = !row.isEditable
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
                doneFlag.disabled = !row.isEditable
                actions.appendChild(toggleRow(doneFlag, "means done", "vocab-flag"))
            }

            // A sprint's completion, as a date and the one action that changes it
            // (LNL-196). The date first and the button beside it, in that order, because
            // the button's label is a consequence of the date — "Reopen" only makes sense
            // once you have read "Completed 14 Jul". Both are absent for every other kind:
            // the view model sends null, and it is the only thing this branch asks.
            row.completionLine?.let { actions.appendChild(element("span", "vocab-completed", it)) }
            row.completionActionLabel?.let { label ->
                val completion = button(label, "btn btn-quiet vocab-complete") {
                    viewModel.onSprintCompletionTapped(row.id)
                }
                completion.disabled = !row.isEditable
                actions.appendChild(completion)
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
