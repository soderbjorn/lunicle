/**
 * Backing view-model for the instance settings dialog: the account directory.
 *
 * The project convention, unchanged: one immutable [State] over a single
 * [StateFlow], every decision made here, and a view that renders what it is handed
 * and forwards intent back. Every string on that screen — the headings, the
 * sentence explaining why an admin's rights table is empty, the label on the
 * toggle — is written below rather than in the DOM view.
 *
 * ── What this screen can and cannot change ──────────────────────────────────
 *
 * One thing: whether an account may connect an agent. The per-project rights are
 * **read-only here**, and that is a decision rather than an omission. Granting a
 * role is already the project settings dialog's members section, which is scoped
 * to the project the grant belongs to; a second editor for the same fact, reached
 * from a different dialog, would be two places to look when a grant is wrong and
 * two things to keep in step. This screen answers "what does this account have",
 * which is the question that had no home.
 *
 * Admin-ness is not editable either, and that one is not a decision this file
 * gets to make: there is no route on the server that sets `is_admin` at all. The
 * first account to sign in is the instance admin and that is the whole mechanism.
 * The detail pane says so rather than offering a switch that would 404.
 *
 * @see AdminSettingsState
 * @see StorageRepository
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.client.userMessage
import se.soderbjorn.lunicle.clientserver.AdminSettingsState
import se.soderbjorn.lunicle.clientserver.AdminUser
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
import se.soderbjorn.lunicle.clientserver.RoleKeys

/** The dialog's title. */
const val ADMIN_SETTINGS_TITLE: String = "Settings"

/**
 * The instance-switches tab, named on its button (LNL-115).
 *
 * "General", because that is what a deployment-wide switch is — not about one
 * account (Users) or one project (Projects), but about the workspace as a whole.
 * The tab the dialog's one-tab strip was always going to grow; see
 * AdminSettingsDialog's preamble.
 */
const val ADMIN_GENERAL_TAB: String = "General"

/** The label beside the public-projects switch (LNL-192). */
const val ADMIN_ALLOW_PUBLIC_LABEL: String = "Allow projects to be public"

/** What the public-projects switch does, under it. */
const val ADMIN_ALLOW_PUBLIC_HINT: String =
    "When off, no project can admit people who are not signed in, whatever its owner sets."

/** The label beside the staff project-creation switch (LNL-192). */
const val ADMIN_STAFF_CREATE_LABEL: String = "Let staff create projects"

/** The label beside the member project-creation switch (LNL-192). */
const val ADMIN_MEMBER_CREATE_LABEL: String = "Let members create projects"

/** What the two project-creation switches do, under them. */
const val ADMIN_CREATE_HINT: String =
    "Deleting and reordering projects stay the instance owner's either way."

/** The label beside the staff agent-access switch (LNL-192). */
const val ADMIN_STAFF_AGENTS_LABEL: String = "Let staff use agent access (MCP)"

/** The label beside the member agent-access switch (LNL-192). */
const val ADMIN_MEMBER_AGENTS_LABEL: String = "Let members use agent access (MCP)"

/** What the two agent-access switches do, under them. */
const val ADMIN_AGENTS_HINT: String =
    "Permission only — each person still switches it on themselves, in their own profile."

/** The label beside the hide-display-name switch (LNL-137). */
const val ADMIN_HIDE_DISPLAY_NAME_LABEL: String = "Hide the display name override in profile settings"

/** What the hide-display-name switch does, under it. */
const val ADMIN_HIDE_DISPLAY_NAME_HINT: String =
    "When on, no one can override the name their sign-in provider gives; the field is removed from everyone's profile."

/** The account-directory tab, named on its button. */
const val ADMIN_USERS_TAB: String = "Users"

/**
 * The project-arranging tab, named on its button (LNL-93).
 *
 * The second tab the dialog's one-tab strip was built to receive — see
 * AdminSettingsDialog's preamble. Reorders the picker and is the only place a
 * project can be deleted.
 */
const val ADMIN_PROJECTS_TAB: String = "Projects"

/** The line above the project list, saying what the order is for. */
const val ADMIN_PROJECTS_HINT: String =
    "Projects appear in this order in the picker, for everyone. Drag is not needed — use the arrows."

/** What to say instead of a list when the instance has no projects to arrange. */
const val ADMIN_PROJECTS_EMPTY: String = "No projects on this instance yet."

/** The label beside the agent-access permission switch in the detail pane. */
const val ADMIN_MCP_LABEL: String = "Permitted agent access (MCP), by tier"

/**
 * What the switch actually does, under it.
 *
 * One line, and it carries the only half of this that surprises anybody: the
 * switch *permits*, it does not enable. An admin who reads it the other way files
 * a bug about the user's agent still not working. Everything else this used to say
 * — that the flag is instance-wide, that withdrawing it cuts live agents off and
 * granting it again restores them without reconnecting — was true, correct, and
 * four lines of prose nobody was reading; the label above already says what the
 * switch is, and the line below already reports whether the user has switched it
 * on. See LNL-53.
 */
const val ADMIN_MCP_EXPLANATION: String =
    "Set per tier on the General tab, not per person. They still switch it on themselves."

/**
 * The read-only line reporting the user's own half of the pair.
 *
 * Shown only while permission is granted. Without it an admin who has just granted
 * access and is then told "my agent still does not work" has nothing on screen to
 * distinguish "they have not switched it on" from a fault — which is the single
 * most likely support question this feature generates.
 */
fun adminMcpUserSwitchNote(isEnabled: Boolean): String = if (isEnabled) {
    "This user has turned agent access on."
} else {
    "This user has not turned agent access on yet, so no agent can act as them."
}

/** The heading over the per-project rights table. */
const val ADMIN_RIGHTS_TITLE: String = "Privileges"

/**
 * What to say instead of a rights table for an instance admin.
 *
 * An admin's rows would be a wall of "No" that means the opposite of what it says:
 * `AccessControl` answers yes to an admin before it looks at a single role, so an
 * admin holding no grants anywhere still has every right everywhere. Rendering the
 * grants honestly and letting the reader draw the wrong conclusion is worse than
 * not rendering them.
 *
 * One line rather than three (LNL-53). The dropped sentence said admin-ness cannot
 * be granted or removed here and that the first account to sign in gets it — true,
 * but it answered a question the screen does not raise: there is no control here to
 * mistake for one, so it was explaining the absence of a thing nobody looked for.
 */
const val ADMIN_IS_ADMIN_NOTE: String =
    "An instance admin: every privilege in every project, so there is nothing to list here."

/**
 * What an admin's row says *instead of* the agent-access switch.
 *
 * ── Why there is no switch here to caption ─────────────────────────────────
 *
 * An admin is permitted by virtue of being one — see the server's
 * `UserRecord.isMcpPermitted` — so the permission this screen grants is one they
 * already hold, whichever way the switch points. It used to be rendered anyway,
 * with a sentence beside it explaining that it decided nothing; that is a control
 * whose own caption tells you not to use it, and the honest version is not to
 * offer it. See [AdminUserDetail.isMcpToggleShown].
 *
 * The sentence stays, because the fact is still worth stating: without it the pane
 * is silent about agent access for exactly the accounts the server lets straight
 * through. It is one line as of LNL-53 — the tail explaining that they must still
 * turn it on themselves was saying what [adminMcpUserSwitchNote], directly below
 * it, already reports as fact rather than as instruction.
 */
const val ADMIN_MCP_INHERENT_NOTE: String =
    "Agent access needs no permission for an admin."

/** What to say when the directory has loaded but no user is picked yet. */
const val ADMIN_NO_SELECTION: String = "Pick a user to see what they have access to."

/**
 * One row in the master list.
 *
 * @property subtitle the e-mail, or a stand-in when there is none. Under the name
 *   rather than beside it because it is the disambiguator, not the identity —
 *   you read it only when two rows say the same thing.
 * @property badge a short word marking the row: "you", "admin", or both. Null for
 *   an ordinary account, which is most of them.
 */
data class AdminUserRow(
    val userId: Long,
    val name: String,
    val subtitle: String,
    val badge: String?,
    val isSelected: Boolean,
)

/** Whether an account holds one particular privilege in one particular project. */
data class RightState(
    val description: String,
    val isHeld: Boolean,
)

/** One project's worth of privileges, for the detail pane. */
data class ProjectRightsRow(
    val projectId: Long,
    val projectName: String,
    val rights: List<RightState>,
)

/**
 * The right-hand pane: one account, in full.
 *
 * @property adminNote [ADMIN_IS_ADMIN_NOTE] for an admin, null otherwise. Carried
 *   rather than a boolean the view branches on, so the view keeps making no
 *   decisions.
 * @property projects every project, in the server's order. Empty when the instance
 *   has no projects, which is a real state on a fresh deployment and is why the
 *   view has an empty message for it.
 */
data class AdminUserDetail(
    val userId: Long,
    val name: String,
    val subtitle: String,
    val adminNote: String?,
    /**
     * [ADMIN_MCP_INHERENT_NOTE] for an admin, null otherwise.
     *
     * Separate from [adminNote], which sits above the whole pane and talks about
     * project privileges. This one stands where the agent-access switch would
     * otherwise be, and for an admin it is the whole of what that slot has to say.
     */
    val mcpInherentNote: String?,
    /**
     * Whether the agent-access switch appears at all.
     *
     * False for an admin, who is permitted whatever it says — a control that
     * decides nothing is worse than no control, because the reader has to work out
     * that it decides nothing before they can ignore it. [mcpInherentNote] takes
     * its place. The explanation under the switch goes with it: it describes what
     * flipping the switch does, and there is nothing to flip.
     */
    val isMcpToggleShown: Boolean,
    /**
     * Whether the privileges section applies to this account at all.
     *
     * False for an admin, and distinct from "[projects] happens to be empty": one
     * means "this instance has no projects yet", the other "the question does not
     * arise here". Both would otherwise render as an empty list, and the first has
     * an explanatory message that would be a lie about the second.
     */
    val isRightsSectionShown: Boolean,
    /**
     * Whether this account's **tier** is permitted agent access (LNL-192).
     *
     * Read-only here. The permission is per tier now — two switches on the General
     * tab — and there is no per-person override anywhere in this design, so this
     * pane reports which side of those switches an account falls on rather than
     * offering one of its own.
     */
    val isMcpAllowed: Boolean,
    val isMcpToggleEnabled: Boolean,
    /**
     * The read-only sentence about the user's own switch, or null when the switch
     * would say nothing worth showing — i.e. while permission is withheld, when
     * the user's own preference is moot.
     */
    val userSwitchNote: String?,
    val projects: List<ProjectRightsRow>,
)

/**
 * One row of the Projects tab: a project, and whether it can move (LNL-93).
 *
 * @property canMoveUp false at the top of the list, and false while a write is in
 *   flight, so a double click cannot queue two reorders against one list. Same
 *   treatment the vocabulary arrows get — see EditProjectBackingViewModel.
 */
data class AdminProjectRow(
    val projectId: Long,
    val name: String,
    val namePrefix: String,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

/**
 * The pending "delete this project?" confirmation, while it is up.
 *
 * Carries the whole sentence rather than a boolean the view branches on, so the
 * view keeps making no decisions — the same shape EditProjectBackingViewModel's
 * confirmations use.
 */
data class PendingProjectDelete(
    val projectId: Long,
    val title: String,
    val message: String,
    /**
     * The phrase the admin must type to arm the Delete button (LNL-107). The same
     * safeguard the project dialog's owner-delete uses; see
     * [DELETE_PROJECT_CONFIRMATION_PHRASE].
     */
    val confirmationPhrase: String,
)

/**
 * Drives the instance settings dialog.
 *
 * @param storage the client's one seam onto the server; defaulted so this is
 *   testable without a browser.
 * @param scope collects nothing itself, but owns the writes; cancelled by the
 *   caller when the dialog closes.
 */
/**
 * @param onInstanceSettingChanged run after an instance switch is successfully
 *   written. Some of these switches ride on [se.soderbjorn.lunicle.clientserver.SessionState]
 *   — the display-name gate (LNL-137), the sign-in gate (LNL-115) — which the
 *   running client fetched once at bootstrap; without this the change would not
 *   land until the next page load. The app wires it to the session view model's
 *   `reload`. Defaults to a no-op so tests and the MCP-only path need not care.
 */
class AdminSettingsBackingViewModel(
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onInstanceSettingChanged: () -> Unit = {},
) {
    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * @property settings the server's answer, or null before the first one lands.
     *   Null and "an instance with no accounts" are different states and the view
     *   says different things about them — which is why this is nullable rather
     *   than defaulting to an empty [AdminSettingsState].
     * @property selectedUserId whose detail is showing. Survives a write, because
     *   a write returns a whole new settings object and re-deriving the selection
     *   from scratch would bounce the pane back to the first row every time
     *   somebody flipped a switch.
     */
    data class State(
        val isLoaded: Boolean = false,
        val isBusy: Boolean = false,
        val settings: AdminSettingsState? = null,
        val selectedUserId: Long? = null,
        val errorMessage: String? = null,
        /**
         * Which project a delete is being confirmed for, or null. Held here rather
         * than as a captured lambda so the confirmation survives a re-render — the
         * same reason [selectedUserId] is state and not a field.
         */
        val pendingProjectDeleteId: Long? = null,
        /**
         * Whether this dialog has reordered or deleted a project since it opened.
         *
         * The picker behind the dialog reads its order and its very membership from
         * a separate load, so a reorder or a delete here is not on screen until the
         * board reloads. The caller passes this to `onDialogClosed` so the reload
         * happens exactly when something the board can see actually changed — the
         * MCP toggle, by contrast, changes nothing on the board and closes with
         * `changed = false`. See MainScreenBackingViewModel.onDialogClosed.
         */
        val projectsChanged: Boolean = false,
    ) {
        /** The master list. */
        val users: List<AdminUserRow>
            get() = settings?.users.orEmpty().map { user ->
                AdminUserRow(
                    userId = user.userId,
                    name = user.name,
                    subtitle = user.subtitle,
                    badge = when {
                        user.isSelf && user.isSysAdmin -> "you · admin"
                        user.isSelf -> "you"
                        user.isSysAdmin -> "admin"
                        else -> null
                    },
                    isSelected = user.userId == selectedUserId,
                )
            }

        /** The detail pane, or null when nothing is picked. */
        val detail: AdminUserDetail?
            get() {
                val settings = settings ?: return null
                val user = settings.users.firstOrNull { it.userId == selectedUserId } ?: return null
                return AdminUserDetail(
                    userId = user.userId,
                    name = user.name,
                    subtitle = user.subtitle,
                    adminNote = ADMIN_IS_ADMIN_NOTE.takeIf { user.isSysAdmin },
                    mcpInherentNote = ADMIN_MCP_INHERENT_NOTE.takeIf { user.isSysAdmin },
                    isMcpToggleShown = !user.isSysAdmin,
                    isRightsSectionShown = !user.isSysAdmin,
                    isMcpAllowed = user.isMcpAllowed,
                    // Disabled while a write is in flight, so a double click cannot
                    // queue two opposite intents against one row.
                    // Never: the permission is per tier and is set on the General
                    // tab, so this reports rather than offers. See isMcpAllowed.
                    isMcpToggleEnabled = false,
                    // Only meaningful once they are permitted — their own switch
                    // does nothing while permission is withheld, so reporting it
                    // then would be noise beside the switch that actually matters.
                    //
                    // An admin is permitted whether or not the column says so, so
                    // their own switch is always worth reporting: it is the only
                    // thing still standing between them and a working agent.
                    userSwitchNote = adminMcpUserSwitchNote(user.isMcpEnabled)
                        .takeIf { user.isMcpAllowed || user.isSysAdmin },
                    // Nothing at all on an admin's row, rather than a grid of
                    // unticked boxes — the same treatment the project dialog's
                    // member list already gives them (see
                    // EditProjectBackingViewModel.members). Every box would be
                    // describing a grant that decides nothing: AccessControl says
                    // yes to an admin before it looks at a role, so an unticked
                    // "Create issues in this project" is not merely uninformative,
                    // it is the opposite of what happens. [adminNote] is the whole
                    // truth of the row and is left to say it alone.
                    projects = if (user.isSysAdmin) {
                        emptyList()
                    } else {
                        user.projects.map { project ->
                            ProjectRightsRow(
                                projectId = project.projectId,
                                projectName = project.projectName,
                                // Every role this server has, held or not — the
                                // screen's whole job is "what they have AND do not
                                // have", so the absent ones are the answer as much
                                // as the present ones. Driven off `settings.roles`
                                // rather than off the held keys, which is what makes
                                // the "not held" rows exist at all.
                                rights = settings.roles.map { role ->
                                    RightState(
                                        description = role.description,
                                        // "See this project" is the one row that is
                                        // not a raw grant: a public board is visible
                                        // to everyone and any other role implies it,
                                        // so it reads the server's effective flag
                                        // (see AdminProjectRights.canSeeProject).
                                        // Without this it shows a cross for a user
                                        // the server would let in. Every other row
                                        // is exactly "do they hold this key".
                                        isHeld = if (role.key == RoleKeys.VIEWER) {
                                            project.canSeeProject
                                        } else {
                                            role.key in project.heldRoleKeys
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }

        /**
         * What to show instead of a detail pane, or null when there is one.
         *
         * Three different nothings, and they say different things: still loading,
         * an instance with no accounts (which cannot happen — you are one — but is
         * cheap to be honest about), or nothing picked yet.
         */
        val detailPlaceholder: String?
            get() = when {
                detail != null -> null
                !isLoaded -> null
                users.isEmpty() -> "No accounts on this instance yet."
                else -> ADMIN_NO_SELECTION
            }

        /**
         * What to say in place of the rights table when there are no projects.
         *
         * Silent for an admin: their table is empty because the section does not
         * apply to them, not because the instance is empty, and saying "no projects
         * on this instance yet" beside a picker full of projects would be plainly
         * wrong. See [AdminUserDetail.isRightsSectionShown].
         */
        val noProjectsMessage: String?
            get() = detail?.let {
                if (it.isRightsSectionShown && it.projects.isEmpty()) {
                    "No projects on this instance yet, so there are no privileges to hold."
                } else {
                    null
                }
            }

        // ── The General tab (LNL-115) ──

        /** Whether projects may be published to the world. Off until the first load lands. */
        val allowPublicProjects: Boolean get() = settings?.allowPublicProjects == true

        /** Whether staff may create projects. Off until the first load lands. */
        val staffMayCreateProjects: Boolean get() = settings?.staffMayCreateProjects == true

        /** Whether members may create projects. Off until the first load lands. */
        val memberMayCreateProjects: Boolean get() = settings?.memberMayCreateProjects == true

        /** Whether staff are permitted agent access. Off until the first load lands. */
        val staffMayUseAgents: Boolean get() = settings?.staffMayUseAgents == true

        /** Whether members are permitted agent access. Off until the first load lands. */
        val memberMayUseAgents: Boolean get() = settings?.memberMayUseAgents == true

        /** Whether the hide-display-name switch is on. Off until the first load lands. */
        val hideDisplayName: Boolean get() = settings?.hideDisplayName == true

        /**
         * Whether the General tab's switches may be flipped.
         *
         * Off until the first state lands (there is nothing to toggle yet) and while
         * a write is in flight, so a double click cannot queue two intents against
         * one switch — the same treatment the MCP toggle and the project arrows get.
         */
        val areInstanceTogglesEnabled: Boolean get() = settings != null && !isBusy

        // ── The Projects tab (LNL-93) ──

        /**
         * The instance's projects, in the arranged order, each told whether it can
         * still move.
         *
         * The ends of the list have a dead arrow apiece, and every arrow is dead
         * while a write is in flight — a second move queued on top of an unsettled
         * one would race the reorder it is based on. Driven off `settings.projects`,
         * which the server returns in `position` order.
         */
        val projectRows: List<AdminProjectRow>
            get() {
                val projects = settings?.projects.orEmpty()
                return projects.mapIndexed { index, project ->
                    AdminProjectRow(
                        projectId = project.id,
                        name = project.name,
                        namePrefix = project.namePrefix,
                        canMoveUp = index > 0 && !isBusy,
                        canMoveDown = index < projects.lastIndex && !isBusy,
                    )
                }
            }

        /** The empty-state line, or null when there is a list to show. */
        val projectsEmptyMessage: String?
            get() = if (isLoaded && settings?.projects.orEmpty().isEmpty()) ADMIN_PROJECTS_EMPTY else null

        /**
         * The delete confirmation to show, or null.
         *
         * Derived from [pendingProjectDeleteId] and the current list so the name in
         * the sentence is always the project the id points at — and so a stale id,
         * left over if another admin deleted the project first, simply resolves to
         * null rather than a dialog about nothing.
         */
        val pendingProjectDelete: PendingProjectDelete?
            get() {
                val id = pendingProjectDeleteId ?: return null
                val project = settings?.projects?.firstOrNull { it.id == id } ?: return null
                return PendingProjectDelete(
                    projectId = project.id,
                    title = "Delete ${project.name}?",
                    // The same weight the forum manager's delete carries, and heavier:
                    // a project takes its issues, forums, comments and every attached
                    // file with it, and there is no trash to fish any of it back from.
                    message = "Every issue, forum and file in \"${project.name}\" goes with it. " +
                        "This cannot be undone. Type the phrase below to confirm.",
                    // The typed-phrase safeguard LNL-107 asked for, on this copy of
                    // the delete as much as the project dialog's — the destruction is
                    // identical, so the ceremony guarding it should be.
                    confirmationPhrase = DELETE_PROJECT_CONFIRMATION_PHRASE,
                )
            }
    }

    /**
     * Fetch the directory.
     *
     * Called by the view after it mounts rather than from `init`, matching
     * [ConnectionsBackingViewModel.start]: the dialog is on screen before the
     * request goes out, so the empty state is a moment of a rendered pane rather
     * than a moment of nothing.
     */
    fun start() {
        scope.launch {
            runCatching { storage.adminSettings() }.fold(
                onSuccess = { settings ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isLoaded = true,
                        settings = settings,
                        // Land on the first row rather than on an empty pane. The
                        // list is sorted by name server-side, so this is stable
                        // between opens rather than whichever row the database
                        // happened to hand back first.
                        selectedUserId = _stateFlow.value.selectedUserId ?: settings.users.firstOrNull()?.userId,
                        errorMessage = null,
                    )
                },
                onFailure = { t ->
                    println("AdminSettings: load failed: ${t.message}")
                    _stateFlow.value = _stateFlow.value.copy(
                        isLoaded = true,
                        errorMessage = t.userMessage("Could not load the user directory."),
                    )
                },
            )
        }
    }

    /** A name in the master list was clicked. */
    fun onUserSelected(userId: Long) {
        _stateFlow.value = _stateFlow.value.copy(selectedUserId = userId)
    }

    /**
     * A General-tab switch was flipped (LNL-115).
     *
     * Names the switch and the desired state, not "toggle" — the request carries
     * both, so a retry says the same thing. The write returns the whole refreshed
     * settings, so the tab re-renders from the server's answer and never patches its
     * own copy. Marks nothing changed on the board: neither switch touches what the
     * picker draws, so the dialog closes with `changed = false`, exactly as the MCP
     * toggle does. See [write].
     */
    fun onInstanceSettingToggled(key: InstanceSettingKey, isEnabled: Boolean) {
        // afterSuccess re-fetches the session: some of these switches gate a field
        // the running client drew from its bootstrap session snapshot (LNL-137's
        // display-name override), so without it the admin who just flipped the
        // switch keeps seeing the old answer until they reload. See
        // onInstanceSettingChanged.
        write("Could not change that setting.", afterSuccess = onInstanceSettingChanged) {
            storage.setInstanceSetting(key, isEnabled)
        }
    }

    /**
     * A project's up or down arrow was pressed on the Projects tab.
     *
     * Builds the whole new order and sends it — the server takes the list, not a
     * "moved X" delta, so this mirrors EditProjectBackingViewModel.onMoveVocabulary
     * exactly. A move against the top or bottom edge computes an out-of-range target
     * and is dropped, which is the arrow the row already disabled being clicked
     * anyway.
     *
     * @param offset -1 for up, +1 for down.
     */
    fun onProjectMoved(projectId: Long, offset: Int) {
        val ids = _stateFlow.value.settings?.projects?.map { it.id } ?: return
        val from = ids.indexOf(projectId)
        if (from < 0) return
        val to = from + offset
        if (to < 0 || to > ids.lastIndex) return
        val reordered = ids.toMutableList().apply {
            removeAt(from)
            add(to, projectId)
        }
        write("Could not reorder the projects.", marksProjectsChanged = true) {
            storage.reorderProjects(reordered)
        }
    }

    /** The Delete button on a project row was pressed: raise the confirmation. */
    fun onDeleteProjectTapped(projectId: Long) {
        _stateFlow.value = _stateFlow.value.copy(pendingProjectDeleteId = projectId)
    }

    /** The confirmation was dismissed without deleting. */
    fun onDeleteProjectCancelled() {
        _stateFlow.value = _stateFlow.value.copy(pendingProjectDeleteId = null)
    }

    /**
     * The delete was confirmed.
     *
     * Clears the pending id first so the confirmation closes at once rather than
     * lingering over the busy write. The id comes from state rather than the
     * button, so a confirmation left up while the list changed under it deletes
     * what the sentence named or, if that project is already gone, resolves to a
     * no-op — see [State.pendingProjectDelete].
     */
    fun onDeleteProjectConfirmed() {
        val id = _stateFlow.value.pendingProjectDeleteId ?: return
        _stateFlow.value = _stateFlow.value.copy(pendingProjectDeleteId = null)
        write("Could not delete that project.", marksProjectsChanged = true) {
            storage.deleteProjectAsAdmin(id)
        }
    }

    fun onErrorDismissed() {
        _stateFlow.value = _stateFlow.value.copy(errorMessage = null)
    }

    /**
     * Run one write, and take the whole new state back from it.
     *
     * The same helper [EditProjectBackingViewModel] uses, for the same reason: every
     * write here returns a full [AdminSettingsState], so there is nothing to merge
     * and no chance of a pane that agrees with the server about the row it just
     * touched and disagrees about the rest.
     */
    private fun write(
        fallback: String,
        marksProjectsChanged: Boolean = false,
        afterSuccess: () -> Unit = {},
        block: suspend () -> AdminSettingsState,
    ) {
        if (_stateFlow.value.isBusy) return
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            runCatching { block() }.fold(
                onSuccess = { settings ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        settings = settings,
                        // Latched, never cleared: once a reorder or delete has
                        // happened, the board the picker draws is stale until the
                        // reload on close, whatever else the dialog does afterwards.
                        projectsChanged = _stateFlow.value.projectsChanged || marksProjectsChanged,
                    )
                    // Only after the write landed and state is settled — a hook that
                    // re-reads server-side facts (the session) must not run against a
                    // change that failed.
                    afterSuccess()
                },
                onFailure = { t ->
                    println("AdminSettings: write failed: ${t.message}")
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage(fallback),
                    )
                },
            )
        }
    }
}

/**
 * The line under a name: their address, or the fact that we do not have one.
 *
 * An account with no e-mail receives no notifications, which is a thing admins get
 * asked about — so the absence is written out rather than left as a blank line the
 * reader has to interpret.
 */
private val AdminUser.subtitle: String
    get() = email ?: "No e-mail address"
