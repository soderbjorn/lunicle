/**
 * The instance settings modal, opened by the sliders button in the board toolbar.
 *
 * One tab, Users: every account on this deployment down the left, and what the
 * selected one has down the right. The tab strip exists with a single tab in it on
 * purpose — this is the screen the next instance-wide setting lands on, and adding
 * a strip later means re-laying-out a dialog people have learned.
 *
 * ── Master-detail, and why this one does not use `.settings-body` ────────────
 *
 * Every other large modal here is one scrolling column and adds `.settings-body`
 * to opt out of the `overflow: hidden` that `.modal-large .modal-body` sets for
 * the issue window's three-band split. This dialog wants the opposite: two panes
 * that scroll *independently*, so picking the twentieth account does not scroll
 * the rights table out of view, and reading a long rights table does not lose the
 * list. So it keeps the inherited flex column and gives each pane its own
 * `overflow-y`, the way the issue window's bands do. Adding `.settings-body` here
 * would collapse both panes into one column and quietly break exactly that.
 *
 * A dumb renderer, like every view here: every string, every disabled flag and the
 * sentence explaining an admin's empty rights table come from
 * [AdminSettingsBackingViewModel].
 *
 * @see AdminSettingsBackingViewModel
 * @see ProjectDialog which shares this `.admin-tab` underline strip; ProfileDialog
 *   does too (LNL-108), so all three settings surfaces read as one family
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.client.viewmodel.ADMIN_ANYONE_CREATE_HINT
import se.soderbjorn.lunicle.client.viewmodel.ADMIN_ANYONE_CREATE_LABEL
import se.soderbjorn.lunicle.client.viewmodel.ADMIN_GENERAL_TAB
import se.soderbjorn.lunicle.client.viewmodel.ADMIN_HIDE_DISPLAY_NAME_HINT
import se.soderbjorn.lunicle.client.viewmodel.ADMIN_HIDE_DISPLAY_NAME_LABEL
import se.soderbjorn.lunicle.client.viewmodel.ADMIN_MCP_EXPLANATION
import se.soderbjorn.lunicle.client.viewmodel.ADMIN_MCP_LABEL
import se.soderbjorn.lunicle.client.viewmodel.ADMIN_PROJECTS_EMPTY
import se.soderbjorn.lunicle.client.viewmodel.ADMIN_PROJECTS_HINT
import se.soderbjorn.lunicle.client.viewmodel.ADMIN_PROJECTS_TAB
import se.soderbjorn.lunicle.client.viewmodel.ADMIN_REQUIRE_SIGN_IN_HINT
import se.soderbjorn.lunicle.client.viewmodel.ADMIN_REQUIRE_SIGN_IN_LABEL
import se.soderbjorn.lunicle.client.viewmodel.ADMIN_RIGHTS_TITLE
import se.soderbjorn.lunicle.client.viewmodel.ADMIN_SETTINGS_TITLE
import se.soderbjorn.lunicle.client.viewmodel.ADMIN_USERS_TAB
import se.soderbjorn.lunicle.client.viewmodel.AdminProjectRow
import se.soderbjorn.lunicle.client.viewmodel.AdminSettingsBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.AdminUserDetail
import se.soderbjorn.lunicle.client.viewmodel.ProjectRightsRow
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey

/**
 * Renders the instance settings modal.
 *
 * @param viewModel owns the directory and the one write this screen can make.
 * @param scope collects the state flow; cancelled by the caller when the dialog
 *   closes.
 * @param onDismiss the user closed it.
 */
class AdminSettingsDialog(
    private val viewModel: AdminSettingsBackingViewModel,
    private val scope: CoroutineScope,
    private val onDismiss: () -> Unit,
) {
    private val modal = Modal(ADMIN_SETTINGS_TITLE, onDismiss = { onDismiss() }, isLarge = true)

    /** The three tabs, in strip order. See [selectTab]. */
    private enum class Tab { GENERAL, USERS, PROJECTS }

    private lateinit var generalTabButton: HTMLButtonElement
    private lateinit var usersTabButton: HTMLButtonElement
    private lateinit var projectsTabButton: HTMLButtonElement

    // ── The three tab panes ──
    private lateinit var generalPane: HTMLElement
    private lateinit var usersPane: HTMLElement
    private lateinit var projectsPane: HTMLElement

    // ── The General tab (LNL-115) ──
    private lateinit var requireSignInToggle: Toggle
    private lateinit var requireSignInRow: HTMLElement
    private lateinit var anyoneCreateToggle: Toggle
    private lateinit var anyoneCreateRow: HTMLElement
    private lateinit var hideDisplayNameToggle: Toggle
    private lateinit var hideDisplayNameRow: HTMLElement

    // ── The Projects tab (LNL-93) ──
    private lateinit var projectsHintElement: HTMLElement
    private lateinit var projectsListElement: HTMLElement
    private lateinit var projectsEmptyElement: HTMLElement

    /** Where this dialog mounted, so the delete confirmation layers over it rather than scrolling inside. */
    private var host: HTMLElement? = null

    /** The project-delete confirmation while it is up. */
    private var projectDeleteConfirm: ConfirmDialog? = null

    /** What the project list was last built from, so a busy tick does not tear it down. */
    private var projectsSignature: String? = null

    // ── The master list ──
    private lateinit var listElement: HTMLElement

    // ── The detail pane ──
    private lateinit var detailElement: HTMLElement
    private lateinit var detailName: HTMLElement
    private lateinit var detailSubtitle: HTMLElement
    private lateinit var adminNote: HTMLElement
    private lateinit var mcpToggle: Toggle
    private lateinit var mcpRow: HTMLElement
    private lateinit var mcpExplanation: HTMLElement
    private lateinit var mcpInherentNote: HTMLElement
    private lateinit var userSwitchNote: HTMLElement
    private lateinit var rightsTitle: HTMLElement
    private lateinit var rightsElement: HTMLElement
    private lateinit var noProjectsElement: HTMLElement
    private lateinit var placeholderElement: HTMLElement

    private lateinit var errorElement: HTMLElement

    /**
     * What the master list was last built from.
     *
     * The signature guard [ProjectDialog] uses on its members table, and needed
     * here for the same reason plus one: every write returns a whole new
     * [se.soderbjorn.lunicle.clientserver.AdminSettingsState], so without this the
     * list would be torn down and rebuilt on every toggle — losing the scroll
     * position of a list whose whole job is to be scrolled through.
     *
     * The selected id is IN the signature. It has to be: selection is what the
     * highlight renders from, and a signature that left it out would be a list
     * that stopped responding to clicks, which is this pattern's quietest failure.
     */
    private var listSignature: String? = null

    /** What the rights table was last built from. Same reasoning as [listSignature]. */
    private var rightsSignature: String? = null

    fun mount(host: HTMLElement) {
        this.host = host
        modal.body.classList.add("admin-body")
        generalPane = buildGeneralTab()
        usersPane = buildUsersTab()
        projectsPane = buildProjectsTab()
        modal.body.children(buildTabStrip(), generalPane, usersPane, projectsPane)
        modal.footer.children(
            element("div", "modal-footer-spacer"),
            button("Close", "btn btn-quiet") { onDismiss() },
        )
        modal.mount(host)
        // Land on Users, the directory this dialog has always opened on — General
        // is a newer, smaller tab (LNL-115) and does not displace the habit.
        selectTab(Tab.USERS)

        scope.launch { viewModel.stateFlow.collect { render(it) } }
        // After mount, not from the view model's init: the panes are on screen
        // before the request goes out, so the wait is a rendered empty pane rather
        // than a moment of nothing. Same as ProfileDialog.
        viewModel.start()
    }

    /**
     * The tab strip — Users and Projects (LNL-93).
     *
     * Underline tabs (`.admin-tab`) rather than the toolkit's boxed
     * segmented-button chrome: two top-level sections of a dialog read as tabs,
     * and the segmented look belongs to the pick-one-of-many controls it is built
     * for. Both tabs are always present: the whole dialog is admin-only, so there
     * is no per-tab gate to draw — an admin who can open this can see both halves.
     */
    private fun buildTabStrip(): HTMLElement {
        generalTabButton = button(ADMIN_GENERAL_TAB, "admin-tab") { selectTab(Tab.GENERAL) }
        usersTabButton = button(ADMIN_USERS_TAB, "admin-tab") { selectTab(Tab.USERS) }
        projectsTabButton = button(ADMIN_PROJECTS_TAB, "admin-tab") { selectTab(Tab.PROJECTS) }
        return element("div", "admin-tabs").children(generalTabButton, usersTabButton, projectsTabButton)
    }

    /** Show one pane, hide the others, and move the selection with it. Copies ProfileDialog. */
    private fun selectTab(selected: Tab) {
        // The Users pane is `.admin-split`, a flex row — showing it with the
        // default `display: block` would drop its detail pane below the list and
        // stop the split filling the dialog. The General and Projects panes are
        // plain blocks.
        generalPane.visible(selected == Tab.GENERAL)
        usersPane.visible(selected == Tab.USERS, displayValue = "flex")
        projectsPane.visible(selected == Tab.PROJECTS)
        generalTabButton.classList.toggle("admin-tab-selected", selected == Tab.GENERAL)
        usersTabButton.classList.toggle("admin-tab-selected", selected == Tab.USERS)
        projectsTabButton.classList.toggle("admin-tab-selected", selected == Tab.PROJECTS)
    }

    /**
     * The General tab: the instance-wide switches (LNL-115).
     *
     * A single scrolling column of switch rows, each a toggle with a line under it
     * saying what it does — the same shape the Users detail pane gives the MCP
     * permission. The rows are wired to the view model's intents here; their
     * checked and enabled state is set in [renderGeneral] from the loaded settings.
     */
    private fun buildGeneralTab(): HTMLElement {
        requireSignInToggle = Toggle { isOn ->
            viewModel.onInstanceSettingToggled(InstanceSettingKey.REQUIRE_SIGN_IN, isOn)
        }
        requireSignInRow = toggleRow(requireSignInToggle, ADMIN_REQUIRE_SIGN_IN_LABEL)

        anyoneCreateToggle = Toggle { isOn ->
            viewModel.onInstanceSettingToggled(InstanceSettingKey.ANYONE_CAN_CREATE_PROJECT, isOn)
        }
        anyoneCreateRow = toggleRow(anyoneCreateToggle, ADMIN_ANYONE_CREATE_LABEL)

        hideDisplayNameToggle = Toggle { isOn ->
            viewModel.onInstanceSettingToggled(InstanceSettingKey.HIDE_DISPLAY_NAME, isOn)
        }
        hideDisplayNameRow = toggleRow(hideDisplayNameToggle, ADMIN_HIDE_DISPLAY_NAME_LABEL)

        return element("div", "admin-general").children(
            requireSignInRow,
            element("p", "field-hint", ADMIN_REQUIRE_SIGN_IN_HINT),
            anyoneCreateRow,
            element("p", "field-hint", ADMIN_ANYONE_CREATE_HINT),
            hideDisplayNameRow,
            element("p", "field-hint", ADMIN_HIDE_DISPLAY_NAME_HINT),
        )
    }

    /** The split: the account list, and the account. */
    private fun buildUsersTab(): HTMLElement {
        listElement = element("div", "admin-user-list")

        placeholderElement = element("p", "admin-placeholder")
        detailElement = buildDetail()

        errorElement = element("p", "modal-error")
        errorElement.setAttribute("role", "status")

        val detailPane = element("div", "admin-detail-pane")
        detailPane.children(placeholderElement, detailElement, errorElement)

        return element("div", "admin-split").children(listElement, detailPane)
    }

    /**
     * The Projects tab: the instance's projects, arrangeable and deletable (LNL-93).
     *
     * A single scrolling column, unlike the master-detail Users tab — a project
     * here is a row and an order, not a subject with a detail pane. The rows are
     * built in [renderProjects] from the view model's list; this only lays out the
     * hint, the list container and the empty line.
     */
    private fun buildProjectsTab(): HTMLElement {
        projectsHintElement = element("p", "modal-message", ADMIN_PROJECTS_HINT)
        projectsListElement = element("div", "admin-project-list")
        projectsEmptyElement = element("p", "admin-placeholder")
        return element("div", "admin-projects").children(
            projectsHintElement,
            projectsEmptyElement,
            projectsListElement,
        )
    }

    private fun buildDetail(): HTMLElement {
        detailName = element("h3", "admin-detail-name")
        detailSubtitle = element("p", "admin-detail-subtitle")
        adminNote = element("p", "admin-note")

        mcpToggle = Toggle { isAllowed ->
            // The id comes from the state rather than from a field captured when
            // this row was built, because this pane is built once and re-pointed
            // at whichever account is selected. A captured id would send the write
            // to whoever happened to be selected at mount.
            val userId = viewModel.stateFlow.value.detail?.userId ?: return@Toggle
            viewModel.onMcpAllowedToggled(userId, isAllowed)
        }
        mcpRow = toggleRow(mcpToggle, ADMIN_MCP_LABEL)
        mcpExplanation = element("p", "field-hint", ADMIN_MCP_EXPLANATION)
        // Directly under the switch and above the general explanation: it
        // qualifies the switch's own position, so it has to be read before the
        // paragraph describing what the switch does in the ordinary case.
        mcpInherentNote = element("p", "admin-note")
        userSwitchNote = element("p", "admin-user-switch")

        rightsTitle = element("h3", "section-title", ADMIN_RIGHTS_TITLE)
        rightsElement = element("div", "admin-rights")
        noProjectsElement = element("p", "field-hint")

        return element("div", "admin-detail").children(
            detailName,
            detailSubtitle,
            adminNote,
            mcpRow,
            mcpInherentNote,
            mcpExplanation,
            userSwitchNote,
            rightsTitle,
            noProjectsElement,
            rightsElement,
        )
    }

    private fun render(state: AdminSettingsBackingViewModel.State) {
        renderGeneral(state)
        renderList(state)

        val detail = state.detail
        detailElement.visible(detail != null)
        placeholderElement.setTextIfChanged(state.detailPlaceholder ?: "")
        placeholderElement.visible(state.detailPlaceholder != null)

        if (detail != null) renderDetail(state, detail)

        errorElement.setTextIfChanged(state.errorMessage ?: "")
        errorElement.visible(state.errorMessage != null)

        renderProjects(state)
        renderProjectDeleteConfirm(state)
    }

    /**
     * The General tab's switches (LNL-115), set from the loaded settings.
     *
     * No signature guard here, unlike the lists: these are two long-lived toggles,
     * not a rebuilt collection, so setting `checked` and `disabled` on each render
     * is cheap and cannot lose scroll position or a pending click. Disabled until
     * the first load lands and while a write is in flight, so a double click cannot
     * queue two intents against one switch — the same rule the MCP toggle follows.
     */
    private fun renderGeneral(state: AdminSettingsBackingViewModel.State) {
        requireSignInToggle.checked = state.requireSignIn
        requireSignInToggle.disabled = !state.areInstanceTogglesEnabled
        anyoneCreateToggle.checked = state.anyoneCanCreateProject
        anyoneCreateToggle.disabled = !state.areInstanceTogglesEnabled
        hideDisplayNameToggle.checked = state.hideDisplayName
        hideDisplayNameToggle.disabled = !state.areInstanceTogglesEnabled
    }

    /**
     * The Projects tab's list, rebuilt only when it changed.
     *
     * The signature is every rendered fact — id, name, prefix, and both arrows'
     * enabled state — so a fact left out of it is a fact that stops updating on
     * screen, the same quiet failure the Users list and the project dialog guard
     * against. The hint hides with the list so an empty instance shows only the
     * empty line, not a sentence about an order there is nothing to arrange.
     */
    private fun renderProjects(state: AdminSettingsBackingViewModel.State) {
        val empty = state.projectsEmptyMessage
        projectsEmptyElement.setTextIfChanged(empty ?: "")
        projectsEmptyElement.visible(empty != null)
        projectsHintElement.visible(empty == null)

        val signature = state.projectRows.joinToString("|") {
            "${it.projectId}:${it.name}:${it.namePrefix}:${it.canMoveUp}/${it.canMoveDown}"
        }
        if (signature == projectsSignature) return
        projectsSignature = signature

        projectsListElement.clear()
        state.projectRows.forEach { projectsListElement.appendChild(projectRow(it)) }
    }

    private fun projectRow(project: AdminProjectRow): HTMLElement {
        val name = element("span", "admin-project-row-name", project.name)
        // The prefix beside the name, muted — the same disambiguator the picker
        // shows, and what tells two similarly-named projects apart before a delete.
        name.appendChild(element("span", "admin-project-row-prefix", project.namePrefix))

        val up = moveButton("↑", "Move up", project.canMoveUp) {
            viewModel.onProjectMoved(project.projectId, -1)
        }
        val down = moveButton("↓", "Move down", project.canMoveDown) {
            viewModel.onProjectMoved(project.projectId, 1)
        }
        val remove = button("Delete", "btn btn-danger-quiet") {
            viewModel.onDeleteProjectTapped(project.projectId)
        }

        return element("div", "admin-project-row").children(name, up, down, remove)
    }

    private fun moveButton(
        glyph: String,
        description: String,
        isEnabled: Boolean,
        onClick: () -> Unit,
    ): HTMLButtonElement {
        val el = button(glyph, "btn btn-quiet vocab-move", onClick)
        el.disabled = !isEnabled
        // The glyph is an arrow and arrows do not read aloud — every other control
        // in this dialog is a word. Same treatment ProjectDialog's arrows get.
        el.title = description
        el.setAttribute("aria-label", description)
        return el
    }

    /**
     * Raise or dismiss the delete confirmation as the view model's pending id
     * comes and goes. Keyed on presence, like ProjectDialog's — two deletes in a
     * row cannot leave the first dialog up describing the second, because the id
     * clears between them.
     */
    private fun renderProjectDeleteConfirm(state: AdminSettingsBackingViewModel.State) {
        val pending = state.pendingProjectDelete
        if (pending != null && projectDeleteConfirm == null) {
            projectDeleteConfirm = ConfirmDialog(
                title = pending.title,
                message = pending.message,
                destructiveLabel = "Delete",
                onConfirm = { viewModel.onDeleteProjectConfirmed() },
                onCancel = { viewModel.onDeleteProjectCancelled() },
                confirmationPhrase = pending.confirmationPhrase,
            ).also { confirm -> host?.let(confirm::mount) }
        } else if (pending == null && projectDeleteConfirm != null) {
            projectDeleteConfirm?.dismiss()
            projectDeleteConfirm = null
        }
    }

    private fun renderList(state: AdminSettingsBackingViewModel.State) {
        val signature = state.users.joinToString("|") {
            "${it.userId}:${it.name}:${it.subtitle}:${it.badge}:${it.isSelected}"
        }
        if (signature == listSignature) return
        listSignature = signature

        listElement.clear()
        state.users.forEach { user ->
            val row = button("", "admin-user-row") { viewModel.onUserSelected(user.userId) }
            row.classList.toggle("admin-user-selected", user.isSelected)

            val name = element("span", "admin-user-name", user.name)
            // element(text = …) sets textContent, never innerHTML — the same rule
            // the connections list follows. A display name is user-chosen, so this
            // is what puts "<img onerror=…>" on the page as characters.
            user.badge?.let { name.appendChild(element("span", "admin-user-badge", it)) }

            row.children(name, element("span", "admin-user-subtitle", user.subtitle))
            listElement.appendChild(row)
        }
    }

    private fun renderDetail(state: AdminSettingsBackingViewModel.State, detail: AdminUserDetail) {
        detailName.setTextIfChanged(detail.name)
        detailSubtitle.setTextIfChanged(detail.subtitle)

        adminNote.setTextIfChanged(detail.adminNote ?: "")
        adminNote.visible(detail.adminNote != null)

        mcpInherentNote.setTextIfChanged(detail.mcpInherentNote ?: "")
        mcpInherentNote.visible(detail.mcpInherentNote != null)

        // The switch and the paragraph describing what it does travel together:
        // an explanation of a control that is not on screen reads as a control
        // that failed to render. Same treatment the privileges heading gets.
        mcpRow.visible(detail.isMcpToggleShown, displayValue = "flex")
        mcpExplanation.visible(detail.isMcpToggleShown)
        mcpToggle.checked = detail.isMcpAllowed
        mcpToggle.disabled = !detail.isMcpToggleEnabled

        userSwitchNote.setTextIfChanged(detail.userSwitchNote ?: "")
        userSwitchNote.visible(detail.userSwitchNote != null)

        noProjectsElement.setTextIfChanged(state.noProjectsMessage ?: "")
        noProjectsElement.visible(state.noProjectsMessage != null)

        // Heading and table together: an admin's row has no privileges section at
        // all, so a "Privileges" header with nothing under it would read as a
        // table that failed to load.
        rightsTitle.visible(detail.isRightsSectionShown)
        renderRights(detail)
    }

    private fun renderRights(detail: AdminUserDetail) {
        val signature = "${detail.userId}|" + detail.projects.joinToString("|") { project ->
            "${project.projectId}:${project.projectName}:" +
                project.rights.joinToString(",") { "${it.description}=${it.isHeld}" }
        }
        if (signature == rightsSignature) return
        rightsSignature = signature

        rightsElement.clear()
        detail.projects.forEach { rightsElement.appendChild(projectBlock(it)) }
    }

    /**
     * One project's privileges, as a block rather than a row of ticks.
     *
     * The same choice `.member-row` made in the project dialog, and the reasoning
     * carries: these rights are sentences, not words, and a matrix with a
     * four-clause sentence over each column is a matrix nobody reads. Held and
     * not-held are both rendered — "does not have" is half of what this screen is
     * for — and the difference is carried by a word, not only by colour.
     */
    private fun projectBlock(project: ProjectRightsRow): HTMLElement {
        val block = element("div", "admin-project")
        block.appendChild(element("div", "admin-project-name", project.projectName))
        project.rights.forEach { right ->
            val row = element("div", if (right.isHeld) "admin-right admin-right-held" else "admin-right")
            // A green tick or a red cross rather than the words "Yes"/"No"
            // (LNL-68): held-or-not is binary and a mark reads it at a glance
            // where a word has to be parsed. The colour is on the mark span
            // (.admin-right-mark), so the icon takes it from `currentColor` and
            // the held/not-held rules below are all that pick green vs red.
            val mark = element("span", "admin-right-mark")
            mark.appendChild(if (right.isHeld) checkIcon() else crossIcon())
            row.children(
                mark,
                element("span", "admin-right-text", right.description),
            )
            block.appendChild(row)
        }
        return block
    }

    fun dismiss() {
        projectDeleteConfirm?.dismiss()
        modal.dismiss()
    }
}
