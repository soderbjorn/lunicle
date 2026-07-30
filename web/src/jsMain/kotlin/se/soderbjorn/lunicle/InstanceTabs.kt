/**
 * The settings pane's three instance-wide tabs — **Who gets in**, **People** and
 * **Instance** (LNL-195).
 *
 * ── What this is, and where it came from ─────────────────────────────────────
 *
 * `AdminSettingsDialog`'s three tabs, **moved and re-sorted**. That dialog had General
 * (a column of six switches), Users (a master-detail directory) and Projects (an order
 * and a delete). None of those three divisions matched a question anybody arrives with,
 * so the content is the same and the sorting is not:
 *
 *  - **Who gets in** — what an account *is* here, and what it may do instance-wide.
 *    Admission, the staff domain, a card per tier, and what a new project starts with.
 *  - **People** — the account directory, unchanged in purpose and rebuilt in shape: a
 *    rung per project instead of seven ticks, a tier stamp per account, and a
 *    NOT SIGNED IN badge where one is owed.
 *  - **Instance** — the deployment itself: read-only facts, the two policy switches, the
 *    project order, and who owns the place.
 *
 * ── Three views, one view model, one request ─────────────────────────────────
 *
 * All three render from the same [AdminSettingsBackingViewModel] and therefore from one
 * `GET`. That is not a saving so much as a correctness property: every write here
 * returns the *whole* state, so flipping "members may create projects" on Who-gets-in
 * repaints the People tab's tier stamps in the same tick, and no tab can be looking at
 * an older instance than its neighbour.
 *
 * A dumb renderer, like every view here: every string, every disabled flag, every
 * greying and every reason comes from the view model or from the server through it.
 *
 * @see AdminSettingsBackingViewModel
 * @see SettingsPane for the strip these three hang on
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.client.viewmodel.ADMISSION_HINT
import se.soderbjorn.lunicle.client.viewmodel.ADMISSION_TITLE
import se.soderbjorn.lunicle.client.viewmodel.ACCESS_TAB_LEAD
import se.soderbjorn.lunicle.client.viewmodel.ALLOW_PUBLIC_HINT
import se.soderbjorn.lunicle.client.viewmodel.ALLOW_PUBLIC_LABEL
import se.soderbjorn.lunicle.client.viewmodel.AdminProjectRow
import se.soderbjorn.lunicle.client.viewmodel.AdminSettingsBackingViewModel
import se.soderbjorn.lunicle.client.viewmodel.AdminUserDetail
import se.soderbjorn.lunicle.client.viewmodel.HAND_OVER_LABEL
import se.soderbjorn.lunicle.client.viewmodel.HIDE_DISPLAY_NAME_HINT
import se.soderbjorn.lunicle.client.viewmodel.HIDE_DISPLAY_NAME_LABEL
import se.soderbjorn.lunicle.client.viewmodel.NEW_PROJECT_HINT
import se.soderbjorn.lunicle.client.viewmodel.NEW_PROJECT_TITLE
import se.soderbjorn.lunicle.client.viewmodel.NOT_SIGNED_IN_BADGE
import se.soderbjorn.lunicle.client.viewmodel.OWNERSHIP_TITLE
import se.soderbjorn.lunicle.client.viewmodel.PEOPLE_RIGHTS_TITLE
import se.soderbjorn.lunicle.client.viewmodel.PEOPLE_READ_ONLY_NOTE
import se.soderbjorn.lunicle.client.viewmodel.PEOPLE_TAB_LEAD
import se.soderbjorn.lunicle.client.viewmodel.POLICY_TITLE
import se.soderbjorn.lunicle.client.viewmodel.PROJECT_ORDER_HINT
import se.soderbjorn.lunicle.client.viewmodel.PROJECT_ORDER_TITLE
import se.soderbjorn.lunicle.client.viewmodel.ProjectRightsRow
import se.soderbjorn.lunicle.client.viewmodel.STAFF_DOMAIN_TITLE
import se.soderbjorn.lunicle.client.viewmodel.TIERS_HINT
import se.soderbjorn.lunicle.client.viewmodel.TIERS_TITLE
import se.soderbjorn.lunicle.client.viewmodel.TIER_AGENTS_HINT
import se.soderbjorn.lunicle.client.viewmodel.TIER_AGENTS_LABEL
import se.soderbjorn.lunicle.client.viewmodel.TIER_CREATE_LABEL
import se.soderbjorn.lunicle.client.viewmodel.DEPLOYMENT_HINT
import se.soderbjorn.lunicle.client.viewmodel.DEPLOYMENT_TITLE
import se.soderbjorn.lunicle.clientserver.AudienceRow
import se.soderbjorn.lunicle.clientserver.TierCard

/**
 * Builds and drives the three tabs.
 *
 * One object rather than three, because they share a view model and a state flow: three
 * collectors over one flow would repaint three times per tick, and the tabs would have to
 * be handed the view model separately anyway. It hands [SettingsPane] three elements and
 * keeps the wiring.
 *
 * @param viewModel owns the one request and every write these tabs make.
 * @param scope collects the state flow; cancelled by the pane when it closes.
 * @param dialogHost where the delete confirmation layers — the app's modal host rather
 *   than a pane element, because a confirmation inside a scrolling pane scrolls with it.
 * @param onProjectsChanged a reorder or a delete landed, so the project list every other
 *   surface draws is stale. Fired on the change rather than on every emission.
 */
class InstanceTabs(
    private val viewModel: AdminSettingsBackingViewModel,
    private val scope: CoroutineScope,
    private val dialogHost: HTMLElement,
    private val onProjectsChanged: () -> Unit,
) {
    // ── Who gets in ──
    private lateinit var accessPane: HTMLElement
    private lateinit var admissionList: HTMLElement
    private lateinit var waysInElement: HTMLElement
    private lateinit var staffDomainSection: HTMLElement
    private lateinit var staffDomainElement: HTMLElement
    private lateinit var tierList: HTMLElement
    private lateinit var newProjectList: HTMLElement
    private lateinit var accessError: HTMLElement

    // ── People ──
    private lateinit var peoplePane: HTMLElement
    private lateinit var peopleList: HTMLElement
    private lateinit var peopleDetail: HTMLElement
    private lateinit var detailName: HTMLElement
    private lateinit var detailSubtitle: HTMLElement
    private lateinit var detailTier: HTMLElement
    private lateinit var detailNotSignedIn: HTMLElement
    private lateinit var detailRunsInstance: HTMLElement
    private lateinit var detailAgentLine: HTMLElement
    private lateinit var rightsTitle: HTMLElement
    private lateinit var rightsReadOnly: HTMLElement
    private lateinit var rightsElement: HTMLElement
    private lateinit var noProjectsElement: HTMLElement
    private lateinit var peoplePlaceholder: HTMLElement
    private lateinit var peopleError: HTMLElement

    // ── Instance ──
    private lateinit var instancePane: HTMLElement
    private lateinit var deploymentList: HTMLElement
    private lateinit var allowPublicToggle: Toggle
    private lateinit var hideDisplayNameToggle: Toggle
    private lateinit var projectOrderHint: HTMLElement
    private lateinit var projectOrderList: HTMLElement
    private lateinit var projectOrderEmpty: HTMLElement
    private lateinit var projectSetReadOnly: HTMLElement
    private lateinit var ownerElement: HTMLElement
    private lateinit var adminsElement: HTMLElement
    private lateinit var handOverButton: HTMLButtonElement
    private lateinit var handOverReason: HTMLElement
    private lateinit var instanceError: HTMLElement

    private var projectDeleteConfirm: ConfirmDialog? = null
    private var handOverDialog: HandOverDialog? = null

    /**
     * What each rebuilt list was last built from, so a busy tick does not tear it down.
     *
     * The signature guard the whole codebase uses on a rebuilt collection, and needed here
     * for its usual reason plus one: every write returns a whole new state, so without it
     * the account list would be torn down on every toggle — losing the scroll position of a
     * list whose whole job is to be scrolled through, and closing any menu that happened to
     * be open. Each signature carries **everything rendered**, greying reasons included,
     * because a fact left out of it is a fact that stops updating on screen.
     */
    private var admissionSignature: String? = null
    private var tierSignature: String? = null
    private var newProjectSignature: String? = null
    private var peopleSignature: String? = null
    private var rightsSignature: String? = null
    private var deploymentSignature: String? = null
    private var projectOrderSignature: String? = null

    /**
     * The project ids as last rendered, so [onProjectsChanged] fires once per actual
     * change rather than once per pane.
     *
     * A boolean latch was wrong twice over: a *second* reorder would never be reported, so
     * the rail and every picker would keep showing the order the first one produced.
     */
    private var lastProjectIds: List<Long>? = null

    /** Build all three panes. Called once, by the pane, in strip order. */
    fun mountAccess(): HTMLElement = buildAccess().also { accessPane = it }

    fun mountPeople(): HTMLElement = buildPeople().also { peoplePane = it }

    fun mountInstance(): HTMLElement = buildInstance().also { instancePane = it }

    /**
     * Start collecting, and fetch.
     *
     * After the panes are mounted rather than from the view model's `init`, matching every
     * other surface here: the tabs are on screen before the request goes out, so the wait
     * is a rendered empty pane rather than a moment of nothing.
     */
    fun start() {
        scope.launch { viewModel.stateFlow.collect { render(it) } }
        viewModel.start()
    }

    /** Take the confirmations down with the pane. The pane cancels [scope]. */
    fun dismiss() {
        projectDeleteConfirm?.dismiss()
        projectDeleteConfirm = null
        handOverDialog?.dismiss()
        handOverDialog = null
    }

    // ── Who gets in ──────────────────────────────────────────────────────────

    /**
     * What an account is here, and what it may do instance-wide.
     *
     * Four sections, in the order the questions arrive: may they have an account at all;
     * which of them are staff; what each tier may do; and what a project they make starts
     * out admitting. The lead line says what the tab does *not* do, because "I gave them
     * agent access and they still cannot see the board" is the misreading this tab invites.
     */
    private fun buildAccess(): HTMLElement {
        admissionList = element("div", "admission-list")
        waysInElement = element("p", "field-hint")

        staffDomainElement = element("p", "field-hint")
        staffDomainSection = element("div", "").children(
            element("div", "settings-section-rule"),
            element("h3", "section-title", STAFF_DOMAIN_TITLE),
            staffDomainElement,
        )

        tierList = element("div", "tier-cards")
        newProjectList = element("div", "access-rows")

        accessError = element("p", "modal-error")
        accessError.setAttribute("role", "status")

        return element("div", "settings-tab-pane").children(
            element("p", "admin-lead", ACCESS_TAB_LEAD),
            element("h3", "section-title", ADMISSION_TITLE),
            element("p", "field-hint", ADMISSION_HINT),
            admissionList,
            waysInElement,
            staffDomainSection,
            element("div", "settings-section-rule"),
            element("h3", "section-title", TIERS_TITLE),
            element("p", "field-hint", TIERS_HINT),
            tierList,
            element("div", "settings-section-rule"),
            element("h3", "section-title", NEW_PROJECT_TITLE),
            element("p", "field-hint", NEW_PROJECT_HINT),
            newProjectList,
            accessError,
        )
    }

    /**
     * The three admission choices, rebuilt only when they changed.
     *
     * Rows rather than a dropdown: an unreachable choice has a sentence beside it, and a
     * menu draws rows and not rows with sub-rows — the same reason a rung's refusal has to
     * ride inside its label. Here there is room, so the reason sits under the choice where
     * it can be read without opening anything.
     */
    private fun renderAdmission(state: AdminSettingsBackingViewModel.State) {
        waysInElement.setTextIfChanged(state.waysInLine ?: "")
        waysInElement.visible(state.waysInLine != null)

        val signature = "${state.selectedAdmission}|${state.isBusy}|" +
            state.admissionOptions.joinToString("|") {
                "${it.policy.key}:${it.isSelectable}:${it.unavailableReason}"
            }
        if (signature == admissionSignature) return
        admissionSignature = signature

        admissionList.clear()
        state.admissionOptions.forEach { option ->
            val row = element("div", "admission-row")
            val choice = button(option.policy.label, "dt-settings-choice-btn admission-choice") {
                viewModel.onAdmissionPicked(option.policy)
            }
            choice.classList.toggle("dt-selected", option.policy == state.selectedAdmission)
            choice.disabled = !option.isSelectable || state.isBusy
            row.appendChild(choice)
            // The reason beside the dead choice rather than instead of it: a control that
            // vanishes reads as a bug, where a dead one with a sentence tells you what to
            // change and where.
            option.unavailableReason?.let {
                row.appendChild(element("p", "access-row-reason", it))
            }
            admissionList.appendChild(row)
        }
    }

    /** One card per tier, rebuilt only when they changed. */
    private fun renderTiers(state: AdminSettingsBackingViewModel.State) {
        val signature = "${state.areInstanceTogglesEnabled}|" + state.tiers.joinToString("|") {
            "${it.key}:${it.accountCount}:${it.mayCreateProjects}/${it.mayUseAgents}:${it.subtitle}"
        }
        if (signature == tierSignature) return
        tierSignature = signature

        tierList.clear()
        state.tiers.forEach { tierList.appendChild(tierCard(it, state.areInstanceTogglesEnabled)) }
    }

    private fun tierCard(tier: TierCard, isEnabled: Boolean): HTMLElement {
        // The count is the reason this is a card and not two switches: "Members" with three
        // accounts and "Members" with three hundred are different decisions, and a column of
        // switches cannot tell you which one you are making.
        val heading = element("div", "tier-card-head").children(
            element("span", "tier-card-title", tier.title),
            element("span", "tier-card-count", accountCountLabel(tier.accountCount)),
        )
        val create = Toggle { viewModel.onInstanceSettingToggled(tier.createKey, it) }
        create.checked = tier.mayCreateProjects
        create.disabled = !isEnabled
        val agents = Toggle { viewModel.onInstanceSettingToggled(tier.agentsKey, it) }
        agents.checked = tier.mayUseAgents
        agents.disabled = !isEnabled
        return element("div", "tier-card").children(
            heading,
            element("p", "field-hint", tier.subtitle),
            toggleRow(create, TIER_CREATE_LABEL),
            toggleRow(agents, TIER_AGENTS_LABEL),
            element("p", "field-hint", TIER_AGENTS_HINT),
        )
    }

    /** "3 accounts", and "1 account" — a count nobody has to decode. */
    private fun accountCountLabel(count: Int): String =
        if (count == 1) "1 account" else "$count accounts"

    /** The new-project audience rows, rebuilt only when they changed. */
    private fun renderNewProject(state: AdminSettingsBackingViewModel.State) {
        val signature = "${state.isBusy}|" + state.newProjectAudiences.joinToString("|") {
            // The row's own rung list too (LNL-202): the greying inside a menu is per
            // audience now, so a menu whose dead rungs moved must be rebuilt even when the
            // row around it did not.
            "${it.key}:${it.roleKey}:${it.isSelectable}:${it.unavailableReason}:" +
                "${it.withdrawRefusal}:${it.effectiveLine}:" +
                it.rungs.joinToString(",") { rung -> "${rung.key}/${rung.isSelectable}" }
        }
        if (signature == newProjectSignature) return
        newProjectSignature = signature

        newProjectList.clear()
        state.newProjectAudiences.forEach { row ->
            newProjectList.appendChild(audienceRow(row, state))
        }
    }

    /**
     * One new-project audience row and its menu.
     *
     * Built from **the row's own** rung list, not the tab's shared one (LNL-202): the
     * Guests row offers Viewer and shows the rest greyed with the reason, because a guest
     * has no account to attribute a write to. The same list a project's Access section is
     * handed, computed by the server for the reason that file gives — the greying belongs
     * where the refusal lives.
     */
    private fun audienceRow(row: AudienceRow, state: AdminSettingsBackingViewModel.State): HTMLElement {
        val container = element("div", "access-row")
        val text = element("div", "access-row-text").children(
            element("div", "access-row-name", row.title),
            element("div", "access-row-detail", row.subtitle),
        )
        // What a wider default already gives this one (LNL-209) — the same line a project's
        // own rows carry, because these rows become a project's rows.
        row.effectiveLine?.let { text.appendChild(element("div", "access-row-detail", it)) }
        container.appendChild(text)
        container.appendChild(
            rungPicker(
                rungs = row.rungs,
                selectedKey = row.roleKey,
                // A vetoed row can still be cleared — the veto stops a project starting out
                // public, not an administrator undoing one. The picker is live so "No access"
                // stays reachable, and the write refuses the rung itself.
                isEnabled = !state.isBusy,
                // A floored row is the one case where it is not reachable, and the entry says
                // so rather than going quiet. See rungPicker.
                withdrawRefusal = row.withdrawRefusal,
                onPick = { key -> viewModel.onNewProjectAudienceChanged(row.key, key) },
            ),
        )
        row.unavailableReason?.let { container.appendChild(element("p", "access-row-reason", it)) }
        return container
    }

    // ── People ───────────────────────────────────────────────────────────────

    /**
     * The account directory: every account down the left, and what the selected one
     * reaches down the right.
     *
     * Master-detail rather than one column, and it keeps `.admin-split`'s two independently
     * scrolling panes: picking the twentieth account must not scroll the project table out
     * of view, and reading a long table must not lose the list.
     */
    private fun buildPeople(): HTMLElement {
        peopleList = element("div", "admin-user-list")
        peoplePlaceholder = element("p", "admin-placeholder")

        detailName = element("h3", "admin-detail-name")
        detailSubtitle = element("p", "admin-detail-subtitle")
        detailTier = element("p", "admin-detail-tier")
        detailNotSignedIn = element("p", "admin-note")
        detailRunsInstance = element("p", "admin-note")
        // A sentence and no switch: the permission is the tier's, on Who gets in, and the
        // other half is the person's own answer. A control here would be one or the other
        // taken away from where it belongs. See the view model's agentLine.
        detailAgentLine = element("p", "field-hint")
        rightsTitle = element("h3", "section-title", PEOPLE_RIGHTS_TITLE)
        rightsReadOnly = element("p", "field-hint", PEOPLE_READ_ONLY_NOTE)
        rightsElement = element("div", "admin-rights")
        noProjectsElement = element("p", "field-hint")

        peopleDetail = element("div", "admin-detail").children(
            detailName,
            detailSubtitle,
            detailTier,
            detailNotSignedIn,
            detailRunsInstance,
            detailAgentLine,
            rightsTitle,
            rightsReadOnly,
            noProjectsElement,
            rightsElement,
        )

        peopleError = element("p", "modal-error")
        peopleError.setAttribute("role", "status")

        val detailPane = element("div", "admin-detail-pane")
            .children(peoplePlaceholder, peopleDetail, peopleError)

        return element("div", "settings-tab-pane admin-split").children(
            element("div", "admin-people-list-side").children(
                element("p", "admin-lead", PEOPLE_TAB_LEAD),
                peopleList,
            ),
            detailPane,
        )
    }

    private fun renderPeople(state: AdminSettingsBackingViewModel.State) {
        val signature = state.users.joinToString("|") {
            "${it.userId}:${it.name}:${it.subtitle}:${it.badge}:${it.tierLabel}:" +
                "${it.showsNotSignedIn}:${it.isSelected}"
        }
        if (signature != peopleSignature) {
            peopleSignature = signature
            peopleList.clear()
            state.users.forEach { user ->
                val row = button("", "admin-user-row") { viewModel.onUserSelected(user.userId) }
                row.classList.toggle("admin-user-selected", user.isSelected)

                // element(text = …) sets textContent, never innerHTML — a display name is
                // user-chosen, so this is what puts "<img onerror=…>" on the page as characters.
                val name = element("span", "admin-user-name", user.name)
                user.badge?.let { name.appendChild(element("span", "admin-user-badge", it)) }

                // The tier every row wears, and the badge only some do — both on the SECOND
                // line, with the address. On the name line they wrapped a 232px row onto two
                // and three lines ("Ada Owner  YOU · ADMIN  INSTANCE / OWNER") and ran the
                // badge straight into the stamp with no gap. Found by driving the app.
                val detail = element("div", "admin-user-detail-line").children(
                    element("span", "admin-user-tier", user.tierLabel),
                    element("span", "admin-user-subtitle", user.subtitle),
                )
                if (user.showsNotSignedIn) {
                    detail.appendChild(element("span", "access-row-badge", NOT_SIGNED_IN_BADGE))
                }

                row.children(name, detail)
                peopleList.appendChild(row)
            }
        }

        val detail = state.detail
        peopleDetail.visible(detail != null)
        peoplePlaceholder.setTextIfChanged(state.detailPlaceholder ?: "")
        peoplePlaceholder.visible(state.detailPlaceholder != null)
        if (detail != null) renderDetail(state, detail)
    }

    private fun renderDetail(state: AdminSettingsBackingViewModel.State, detail: AdminUserDetail) {
        detailName.setTextIfChanged(detail.name)
        detailSubtitle.setTextIfChanged(detail.subtitle)
        detailTier.setTextIfChanged(detail.tierLabel)

        detailNotSignedIn.setTextIfChanged(detail.notSignedInNote ?: "")
        detailNotSignedIn.visible(detail.notSignedInNote != null)
        detailRunsInstance.setTextIfChanged(detail.runsInstanceNote ?: "")
        detailRunsInstance.visible(detail.runsInstanceNote != null)
        detailAgentLine.setTextIfChanged(detail.agentLine)

        noProjectsElement.setTextIfChanged(state.noProjectsMessage ?: "")
        noProjectsElement.visible(state.noProjectsMessage != null)

        // Heading, caption and table together: somebody who runs the instance has no
        // per-project section at all, so a heading with nothing under it would read as a
        // table that failed to load.
        rightsTitle.visible(detail.isRightsSectionShown)
        rightsReadOnly.visible(detail.isRightsSectionShown && detail.projects.isNotEmpty())
        renderRights(detail)
    }

    private fun renderRights(detail: AdminUserDetail) {
        val signature = "${detail.userId}|" + detail.projects.joinToString("|") {
            "${it.projectId}:${it.projectName}:${it.rungLabel}:${it.isHeld}:${it.note}"
        }
        if (signature == rightsSignature) return
        rightsSignature = signature

        rightsElement.clear()
        detail.projects.forEach { rightsElement.appendChild(projectRightsRow(it)) }
    }

    /**
     * One project, and the rung this account reaches in it.
     *
     * A row and not a tick: the answer is one of five rungs plus "no access", which a word
     * says and a mark cannot. Held and not-held are both rendered — "reaches nothing here"
     * is half of what this list is for — and the difference is carried by the word as well
     * as by the colour.
     */
    private fun projectRightsRow(row: ProjectRightsRow): HTMLElement {
        val container = element(
            "div",
            if (row.isHeld) "admin-right admin-right-held" else "admin-right",
        )
        container.appendChild(element("span", "admin-right-text", row.projectName))
        container.appendChild(element("span", "access-row-rung", row.rungLabel))
        row.note?.let { container.appendChild(element("p", "access-row-reason", it)) }
        return container
    }

    // ── Instance ─────────────────────────────────────────────────────────────

    /**
     * The deployment itself: what it is, what is true for everybody, the project order, and
     * who owns it.
     *
     * Top to bottom by how changeable the thing is — facts nobody can edit, then two
     * switches, then an order, then ownership, which is the one thing on this screen that
     * transfers rather than toggles.
     */
    private fun buildInstance(): HTMLElement {
        deploymentList = element("div", "deployment-facts")

        allowPublicToggle = Toggle { isOn ->
            viewModel.onInstanceSettingToggled(
                se.soderbjorn.lunicle.clientserver.InstanceSettingKey.ALLOW_PUBLIC_PROJECTS,
                isOn,
            )
        }
        hideDisplayNameToggle = Toggle { isOn ->
            viewModel.onInstanceSettingToggled(
                se.soderbjorn.lunicle.clientserver.InstanceSettingKey.HIDE_DISPLAY_NAME,
                isOn,
            )
        }

        projectOrderHint = element("p", "field-hint", PROJECT_ORDER_HINT)
        projectOrderList = element("div", "admin-project-list")
        projectOrderEmpty = element("p", "admin-placeholder")
        // Why the arrows are dead, beside them rather than instead of the list: an
        // administrator who is not the owner sees every board and moves none of them, and
        // has to learn whose it is rather than collect a 403 from a live-looking arrow.
        projectSetReadOnly = element("p", "admin-note")

        ownerElement = element("p", "modal-message")
        adminsElement = element("p", "field-hint")
        handOverButton = button(HAND_OVER_LABEL, "btn btn-quiet btn-small") { viewModel.onHandOverTapped() }
        handOverReason = element("p", "admin-note")

        instanceError = element("p", "modal-error")
        instanceError.setAttribute("role", "status")

        return element("div", "settings-tab-pane").children(
            element("h3", "section-title", DEPLOYMENT_TITLE),
            element("p", "field-hint", DEPLOYMENT_HINT),
            deploymentList,
            element("div", "settings-section-rule"),
            element("h3", "section-title", POLICY_TITLE),
            toggleRow(allowPublicToggle, ALLOW_PUBLIC_LABEL),
            element("p", "field-hint", ALLOW_PUBLIC_HINT),
            toggleRow(hideDisplayNameToggle, HIDE_DISPLAY_NAME_LABEL),
            element("p", "field-hint", HIDE_DISPLAY_NAME_HINT),
            element("div", "settings-section-rule"),
            element("h3", "section-title", PROJECT_ORDER_TITLE),
            projectOrderHint,
            projectOrderEmpty,
            projectSetReadOnly,
            projectOrderList,
            element("div", "settings-section-rule"),
            element("h3", "section-title", OWNERSHIP_TITLE),
            ownerElement,
            adminsElement,
            handOverButton,
            handOverReason,
            instanceError,
        )
    }

    private fun renderInstance(state: AdminSettingsBackingViewModel.State) {
        val signature = state.deploymentFacts.joinToString("|") { "${it.first}=${it.second}" }
        if (signature != deploymentSignature) {
            deploymentSignature = signature
            deploymentList.clear()
            state.deploymentFacts.forEach { (label, value) ->
                deploymentList.appendChild(
                    element("div", "deployment-fact").children(
                        element("span", "deployment-fact-label", label),
                        element("span", "deployment-fact-value", value),
                    ),
                )
            }
        }

        // No signature guard on the switches, unlike the lists: two long-lived toggles are
        // cheap to set on every render and cannot lose a scroll position or a pending click.
        allowPublicToggle.checked = state.allowPublicProjects
        allowPublicToggle.disabled = !state.areInstanceTogglesEnabled
        hideDisplayNameToggle.checked = state.hideDisplayName
        hideDisplayNameToggle.disabled = !state.areInstanceTogglesEnabled

        renderProjectOrder(state)

        ownerElement.setTextIfChanged(state.ownerLine ?: "")
        ownerElement.visible(state.ownerLine != null)
        adminsElement.setTextIfChanged(state.adminsLine ?: "")
        adminsElement.visible(state.adminsLine != null)
        // Only the owner sees it at all — an administrator gets the row, and who holds it,
        // and no button. Live for the owner even on a deployment where nobody is eligible:
        // the dialog is where that is explained, because the explanation names the
        // deployment's domain and takes three lines. See InstanceOwnership.canHandOver.
        handOverButton.visible(state.isOwnerSelf, displayValue = "inline-flex")
        handOverButton.disabled = !state.canHandOver
        handOverReason.setTextIfChanged(state.handOverBlockedReason ?: "")
        handOverReason.visible(state.handOverBlockedReason != null && state.isOwnerSelf)
    }

    /**
     * The project order, rebuilt only when it changed.
     *
     * The signature is every rendered fact — id, name, prefix and both arrows' enabled state
     * — so a fact left out of it is a fact that stops updating on screen. The hint hides with
     * the list, so an instance with nothing to arrange shows only the line saying so.
     */
    private fun renderProjectOrder(state: AdminSettingsBackingViewModel.State) {
        val empty = state.projectOrderEmptyMessage
        projectOrderEmpty.setTextIfChanged(empty ?: "")
        projectOrderEmpty.visible(empty != null)
        projectOrderHint.visible(empty == null)
        // A single project still lists — you can see what is there and that there is nothing
        // to move — so the list is hidden only when there is genuinely nothing in it.
        projectOrderList.visible(state.projectRows.isNotEmpty())

        projectSetReadOnly.setTextIfChanged(state.projectSetReadOnlyReason ?: "")
        projectSetReadOnly.visible(state.projectSetReadOnlyReason != null && state.projectRows.isNotEmpty())

        val signature = state.projectRows.joinToString("|") {
            "${it.projectId}:${it.name}:${it.namePrefix}:${it.canMoveUp}/${it.canMoveDown}/${it.canDelete}"
        }
        if (signature == projectOrderSignature) return
        projectOrderSignature = signature

        // ── Keeping the reader's place across a reorder ──────────────────────
        //
        // Pressing ↑ on the last row threw the whole tab back to the top, which is as bad as
        // it sounds on the one control here meant to be pressed repeatedly. Two separate
        // causes, so two restores:
        //
        //  - rebuilding a list inside a scroller clamps its scrollTop to 0 while the list is
        //    momentarily empty, and the browser does not put it back. The synchronous restore
        //    covers that;
        //  - a reorder also tells the app its project list moved, which re-renders the
        //    workspace shell around this pane and zeroes the scroll again, *after* this
        //    method returns. Nothing synchronous can catch that, hence the deferred one.
        //
        // A plain switch on this tab does neither, which is how the two were told apart.
        // Found by driving the app.
        val scrollTop = instancePane.scrollTop
        projectOrderList.clear()
        state.projectRows.forEach { projectOrderList.appendChild(projectOrderRow(it)) }
        instancePane.scrollTop = scrollTop
        kotlinx.browser.window.setTimeout({ instancePane.scrollTop = scrollTop }, 0)
    }

    private fun projectOrderRow(project: AdminProjectRow): HTMLElement {
        val name = element("span", "admin-project-row-name", project.name)
        // The prefix beside the name, muted — the same disambiguator every picker shows, and
        // what tells two similarly-named projects apart before a delete.
        name.appendChild(element("span", "admin-project-row-prefix", project.namePrefix))
        val delete = button("Delete", "btn btn-danger-quiet") {
            viewModel.onDeleteProjectTapped(project.projectId)
        }
        delete.disabled = !project.canDelete
        return element("div", "admin-project-row").children(
            name,
            moveButton("↑", "Move up", project.canMoveUp) { viewModel.onProjectMoved(project.projectId, -1) },
            moveButton("↓", "Move down", project.canMoveDown) { viewModel.onProjectMoved(project.projectId, 1) },
            delete,
        )
    }

    private fun moveButton(
        glyph: String,
        description: String,
        isEnabled: Boolean,
        onClick: () -> Unit,
    ): HTMLButtonElement {
        val el = button(glyph, "btn btn-quiet vocab-move", onClick)
        el.disabled = !isEnabled
        // The glyph is an arrow and arrows do not read aloud — everything else here is a word.
        el.title = description
        el.setAttribute("aria-label", description)
        return el
    }

    /**
     * Raise or dismiss the delete confirmation as the view model's pending id comes and
     * goes. Keyed on presence: two deletes in a row cannot leave the first dialog up
     * describing the second, because the id clears between them.
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
            ).also { it.mount(dialogHost) }
        } else if (pending == null && projectDeleteConfirm != null) {
            projectDeleteConfirm?.dismiss()
            projectDeleteConfirm = null
        }
    }

    /**
     * Raise or dismiss the handover dialog (LNL-198).
     *
     * Keyed on presence, like the delete confirmation above, and here that keying is
     * load-bearing rather than merely tidy: the dialog holds the picked successor and the
     * typed phrase itself, so rebuilding it on an unrelated state emission would throw away
     * a half-typed phrase. Nothing inside it is state the view model can restore.
     */
    private fun renderHandOver(state: AdminSettingsBackingViewModel.State) {
        val pending = state.pendingHandOver
        if (pending != null && handOverDialog == null) {
            handOverDialog = HandOverDialog(
                pending = pending,
                onConfirm = { viewModel.onHandOverConfirmed(it) },
                onCancel = { viewModel.onHandOverCancelled() },
            ).also { it.mount(dialogHost) }
        } else if (pending == null && handOverDialog != null) {
            handOverDialog?.dismiss()
            handOverDialog = null
        }
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private fun render(state: AdminSettingsBackingViewModel.State) {
        renderAdmission(state)
        staffDomainSection.visible(state.staffDomainLine != null)
        staffDomainElement.setTextIfChanged(state.staffDomainLine ?: "")
        renderTiers(state)
        renderNewProject(state)
        renderPeople(state)
        renderInstance(state)
        renderProjectDeleteConfirm(state)
        renderHandOver(state)

        // One message, on all three tabs: a write from any of them can fail, and the reader
        // is looking at whichever one they pressed. Cheaper and more honest than guessing
        // which tab a failure belongs to.
        val message = state.errorMessage ?: ""
        listOf(accessError, peopleError, instanceError).forEach {
            it.setTextIfChanged(message)
            it.visible(state.errorMessage != null)
        }

        // Once per actual change: the list every other surface draws is stale the moment the
        // order or the membership moves, and it moves again on the next arrow press.
        val ids = state.projectRows.map { it.projectId }
        val previous = lastProjectIds
        lastProjectIds = ids
        if (state.projectsChanged && previous != null && previous != ids) onProjectsChanged()
    }
}
