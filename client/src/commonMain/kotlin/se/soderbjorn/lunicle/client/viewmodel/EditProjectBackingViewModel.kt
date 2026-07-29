/**
 * Backing view-model for the project dialog — new and edit are the same screen.
 *
 * Same convention as everything else: one immutable [State] over one
 * [StateFlow], every decision here, nothing in the view.
 *
 * The decision worth naming is [State.isOkEnabled]. The spec asks for OK to be
 * disabled "if we did not fill in a unique project name, or if we try to rename
 * a project to a name that already exists" — so the dialog knows the other
 * projects' names, and answers locally as the user types. That is an
 * affordance: it exists so nobody fills in a form that is going to be refused.
 * The server checks again on submit, and the 409 it sends back is shown
 * verbatim — because the server is the only thing that can *know*, and it also
 * catches the case this cannot see (someone else creating "Lunamux" while this
 * dialog was open).
 *
 * ── Two dialogs in one, and why the halves behave differently ────────────────
 *
 * Editing an existing project also shows its **vocabularies** and its
 * **privileges**, and those sections do not obey the form around them. The form
 * is a draft: nothing is written until OK, and Cancel throws it away. The
 * sections are not — adding a status writes a status, immediately, and there is
 * no OK to press and nothing for Cancel to undo. That is not an inconsistency to
 * fix; it is what the two things are. A vocabulary editor that batched its edits
 * into the OK button would have to hold "these three renames, that delete and a
 * reorder" as pending state and replay it in an order the server accepts — which
 * is a transaction, invented in a browser, on someone else's machine.
 *
 * The consequence is [State.hasWrittenSettings]: Cancel still reports `changed`
 * when a section wrote something, so the board reloads. Otherwise renaming a
 * column and pressing Cancel would leave the old name on the board until a
 * refresh.
 *
 * @see StorageRepository
 * @see MainScreenBackingViewModel
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
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.TokenModes
import se.soderbjorn.lunicle.clientserver.VocabularyEntry
import se.soderbjorn.lunicle.clientserver.VocabularyKind

/**
 * The two senior rungs' wire keys, as the server spells them (LNL-191).
 *
 * Literals here because the client has no copy of the server's `ProjectRole` enum
 * — the keys ARE the wire format and are branched on by name (see Roles.kt, which
 * says so and warns that changing one is a migration). These are the only keys the
 * client has to recognise rather than merely render, because they are the two rows
 * of the privileges table whose availability depends on who is looking: both are
 * handed out only by an owner.
 */
private const val PROJECT_ADMIN_ROLE_KEY = "admin"
private const val PROJECT_OWNER_ROLE_KEY = "owner"

/** The two rows gated on [ProjectSettingsState.canGrantSeniorRoles]. */
private val SENIOR_ROLE_KEYS = setOf(PROJECT_ADMIN_ROLE_KEY, PROJECT_OWNER_ROLE_KEY)

/**
 * The phrase an owner must type to arm the delete-project button (LNL-107).
 *
 * The ticket's words, verbatim and lower-case. Matched case-insensitively and
 * trimmed by the view, so it is a deliberate sentence to type rather than a
 * spelling test — the point is a second, considered action, not a keystroke.
 */
const val DELETE_PROJECT_CONFIRMATION_PHRASE = "i really want to delete this project"

/**
 * The pending project-delete confirmation for the project dialog (LNL-107).
 *
 * Carries the sentence and the phrase the owner must type, rather than a boolean
 * the view branches on, so the view renders a confirmation while deciding nothing
 * — the counterpart to AdminSettingsBackingViewModel.PendingProjectDelete, which
 * the instance-settings dialog uses for the system administrator's copy of this
 * same power. Named apart from that one because they live in the same package and
 * carry different fields: this dialog already knows its project, so it needs no id.
 */
data class ProjectDeletePrompt(
    val title: String,
    val message: String,
    val confirmationPhrase: String,
)

/**
 * One row of one vocabulary, as the dialog renders it.
 *
 * Every decision is already made here, including the two the view would
 * otherwise be tempted to make itself: whether the delete button is live, and
 * what the confirmation will say. Both depend on rules the view has no business
 * knowing — that a status is refused while issues are in it, that a label is not
 * because its links cascade — and a view that worked them out from `usageCount`
 * would be a second, silent implementation of the server's rules.
 *
 * @property isDeletable whether to offer the delete button at all. An affordance;
 *   the server refuses regardless. See the server's VocabularyRepository.
 * @property deleteBlockedReason why it is not offered, in full: the sentence that
 *   says what to do about it. A disabled control with no explanation is the most
 *   annoying thing a dialog can do — the same reasoning as [State.validationMessage].
 * @property deleteBlockedSummary the same fact in two or three words, for the view
 *   to render *beside* the dead button rather than inside a tooltip. LNL-183 was
 *   filed against a Delete that had the sentence and showed nobody: the reason has
 *   to be readable without hovering a control that cannot be hovered, and a full
 *   sentence on every blocked row in an eight-column board is a wall of text. So
 *   the glanceable half is visible and the instruction stays on the title.
 * @property canMoveUp whether this row can go earlier. False for the first row,
 *   and while a write is in flight. Every kind is arrangeable — see LNL-28.
 * @property showsClosingFlag whether to render the "needs a resolution"
 *   checkbox. Statuses only: the flag exists on no other table, and a checkbox
 *   beside a priority would be a control that writes nothing.
 * @property isDone whether this resolution means the work was done (LNL-134) — the
 *   mirror of [requiresResolution], meaningful only for a resolution.
 * @property showsDoneFlag whether to render the "means done" checkbox. Resolutions
 *   only, for [showsClosingFlag]'s reason — it is what makes "require a fixed
 *   version when resolving" precise.
 */
data class VocabularyRowState(
    val id: Long,
    val name: String,
    val requiresResolution: Boolean,
    val isDone: Boolean,
    val isDeletable: Boolean,
    val deleteBlockedReason: String?,
    val deleteBlockedSummary: String?,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val showsClosingFlag: Boolean,
    val showsDoneFlag: Boolean,
)

/**
 * One vocabulary, as a section of the dialog.
 *
 * @property hint the sentence under the heading. Every section has one, because
 *   these five lists look identical and are not: deleting a label unlabels
 *   issues, deleting a status is refused while any issue is in it, and the order
 *   of the priorities decides which one a new issue gets. None of that is
 *   guessable from a list of names.
 * @property isAddEnabled whether the add button is live — false for an empty
 *   field, which is the whole of the local check. Uniqueness is deliberately not
 *   checked here: the server owns it, its 409 names the row that already has the
 *   name, and duplicating the fold on this side buys a marginally faster refusal
 *   in exchange for a second place the rule lives.
 */
data class VocabularySection(
    val kind: VocabularyKind,
    val title: String,
    val hint: String,
    val draftName: String,
    val isAddEnabled: Boolean,
    val rows: List<VocabularyRowState>,
)

/**
 * One role checkbox against one user.
 *
 * Never built for an instance admin — their row carries no toggles at all, only
 * the note saying why. See [State.members].
 *
 * @property isEnabled false while a write is in flight, so a second click cannot
 *   be sent from a state the first has already invalidated.
 */
data class RoleToggle(
    val key: String,
    val description: String,
    val isOn: Boolean,
    val isEnabled: Boolean,
)

/**
 * One account, and what it may do here.
 *
 * @property note the sentence that stands in place of the checkboxes for this row
 *   — "Admin — can do everything in every project" — or null for an ordinary
 *   user. Where there is a note, [roles] is empty: the note is not an annotation
 *   on the boxes, it is what the row says instead of them.
 */
data class MemberRowState(
    val userId: Long,
    val name: String,
    val note: String?,
    val roles: List<RoleToggle>,
)

/**
 * A vocabulary row the admin has asked to delete, held while the confirmation is
 * up.
 *
 * The whole row rather than an id: the confirmation names what is about to
 * happen, and it needs the name and the count to say it. Looking them back up
 * from the id would work until a reload landed between the click and the
 * confirm, at which point the dialog would confirm one thing and delete another.
 */
data class PendingVocabularyDelete(
    val kind: VocabularyKind,
    val id: Long,
    val title: String,
    val message: String,
)

/** The heading over the two repository fields. */
const val REPOSITORY_SECTION_TITLE: String = "GitHub repository"

/** The repository field's label, and the placeholder showing an accepted spelling. */
const val REPOSITORY_URL_LABEL: String = "Repository"
const val REPOSITORY_URL_PLACEHOLDER: String = "owner/name"

/** The token field's label. */
const val GITHUB_TOKEN_ENV_LABEL: String = "Access token variable"

/**
 * The token field's placeholder: a conforming name, not the bare prefix.
 *
 * A whole plausible example rather than "LUNICLE_GITHUB_TOKEN_…", because the
 * shape of an acceptable answer is easier to copy than to infer from a rule, and
 * the trailing ellipsis reads to some people as part of the value.
 */
const val GITHUB_TOKEN_ENV_PREFIX_EXAMPLE: String = "LUNICLE_GITHUB_TOKEN_LUNICLE"

/**
 * The line under the token field.
 *
 * States the prefix rule up front rather than leaving it to the refusal, because
 * this is a field whose correct value the user cannot guess: nothing on screen
 * would otherwise suggest that only certain variable names are accepted, and
 * being told after pressing Save is being told too late.
 *
 * It says the *name* of a variable, twice and plainly, because the mistake worth
 * preventing here is pasting the token itself into a field that would then store
 * it in the database.
 */
const val GITHUB_TOKEN_ENV_HINT: String =
    "The name of an environment variable holding a GitHub token — not the token itself. " +
        "Must start with LUNICLE_GITHUB_TOKEN_."

/**
 * The line under the repository field.
 *
 * Names the one permission the token needs. An admin creating a fine-grained
 * token is choosing from a long list of permissions, and "Contents: Read-only" is
 * both the correct answer and not an obvious one.
 */
const val REPOSITORY_HINT: String =
    "Used for commit counts in Statistics. The token needs Contents: Read-only on this repository."

/** The label over the token-source selector (LNL-107). */
const val GITHUB_TOKEN_MODE_LABEL: String = "Access token"

/**
 * The three token-source choices, in the order the radio shows them, each paired
 * with its [se.soderbjorn.lunicle.clientserver.TokenModes] wire value (LNL-107).
 *
 * A list rather than three constants because the DOM builds one button per entry
 * and the view model reads the same list back to know which is selected — one
 * source of the ordering and the labels, so the control and its logic cannot drift.
 */
val GITHUB_TOKEN_MODE_OPTIONS: List<Pair<String, String>> = listOf(
    TokenModes.NONE to "None",
    TokenModes.ENV to "Environment variable",
    TokenModes.LITERAL to "Paste token",
)

/** The literal-token field's label (LNL-107). */
const val GITHUB_TOKEN_LITERAL_LABEL: String = "Token"

/** The literal-token field's placeholder when one is already stored — never the value. */
const val GITHUB_TOKEN_LITERAL_STORED_PLACEHOLDER: String = "•••••••• — leave blank to keep the stored token"

/**
 * The line under the literal-token field (LNL-107).
 *
 * Says the two things this field's user most needs and cannot see: that the value
 * is stored on the server as typed — the honest warning the env-variable source
 * exists to avoid — and that leaving it blank keeps whatever is already stored,
 * which is the only way the field can be write-only and still editable.
 */
const val GITHUB_TOKEN_LITERAL_HINT: String =
    "Stored on the server as entered. Prefer an environment variable where you can set one. " +
        "Leave blank to keep the token already stored."

/**
 * Owns the project dialog.
 *
 * @param existing the project being edited, or null when creating.
 * @param otherProjects every project this user can see, so the name check can
 *   answer without a round-trip. The one being edited is excluded by id inside
 *   [nameClash] — renaming a project to what it is already called is not a
 *   clash.
 * @param onFinished called when the dialog is done: `changed` is true if
 *   anything was written, so MainScreen knows whether to reload, and `saved` is
 *   the project OK wrote — null for Cancel and for Delete, which leave no
 *   project to look at. The dialog reports its own outcome rather than
 *   MainScreen guessing. `saved` is how a *new* project reaches the board: it is
 *   not in MainScreen's list until the reload this call triggers, so the id has
 *   to be handed over rather than looked up.
 */
class EditProjectBackingViewModel(
    private val existing: ProjectSummary?,
    private val otherProjects: List<ProjectSummary>,
    private val canConfigure: Boolean = true,
    private val canConfigureIdentity: Boolean = canConfigure,
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onFinished: (changed: Boolean, saved: ProjectSummary?) -> Unit,
    /**
     * The caller's current hide-issue-numbers choice for this project (LNL-105),
     * and the sink for a change to it. A per-user view preference owned by the board
     * view model, not a project setting the server keeps — so it is seeded in and
     * written back through [onHideIssueNumbersChanged] rather than through [storage]
     * here. The dialog is where the ticket asks the toggle to live; the board is
     * where the preference lives.
     */
    private val hideIssueNumbers: Boolean = false,
    private val persistHideIssueNumbers: (Boolean) -> Unit = {},
) {
    private val _stateFlow = MutableStateFlow(
        State(
            name = existing?.name.orEmpty(),
            namePrefix = existing?.namePrefix.orEmpty(),
            isPublic = existing?.isPublic ?: false,
            visibleToAllSignedIn = existing?.visibleToAllSignedIn ?: false,
            isNew = existing == null,
            canConfigure = canConfigure,
            canConfigureIdentity = canConfigureIdentity,
            hideIssueNumbers = hideIssueNumbers,
        ),
    )

    /** The current dialog state, observed by the view. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    init {
        // Nothing to configure until there is a project to configure, so a new
        // project's dialog is the plain form and asks for nothing. It also could
        // not ask: the settings route is keyed on a project id, and there is no id
        // until OK has been pressed.
        if (existing != null) loadSettings()
    }

    /**
     * Immutable snapshot of the dialog.
     *
     * @property settings the project's vocabularies and grants, or null while they
     *   are loading — and also for a caller the server refused, which is the
     *   important half. The cogwheel is admin-only, so this should not happen; if
     *   it does, the dialog quietly renders the plain form rather than an empty
     *   settings section, because a section that cannot be filled is worse than no
     *   section. See [loadSettings].
     * @property vocabularyDrafts what has been typed into each section's add
     *   field, keyed by kind. One map rather than five fields, because five
     *   sections are rendered from one loop and five fields would have to be
     *   selected from by kind anyway.
     * @property hasWrittenSettings whether any section has written something. The
     *   reason it exists: the sections write immediately, so Cancel is not "throw
     *   the changes away" for them — the changes are already on the server. This
     *   is what makes Cancel still tell MainScreen to reload the board. See this
     *   file's preamble.
     * @property settingsErrorMessage a refusal from one of the sections, shown as
     *   a modal over this dialog rather than as text inside it. Separate from
     *   [errorMessage], which belongs to the *form* and sits under the fields it
     *   is about: a "3 issues are in that status" printed under the public
     *   checkbox would be nowhere near the button that caused it, and would sit
     *   there afterwards describing something that already happened.
     */
    data class State(
        val name: String = "",
        val namePrefix: String = "",
        val isPublic: Boolean = false,
        /**
         * Whether any signed-in account may read this project — the middle read
         * tier, staged into the form and written on OK beside [isPublic] (LNL-138).
         * A read-only grant: the server admits these callers to browse but no write
         * gate widens for them. Independent of [isPublic] in the model, but the
         * dialog reads [signedInVisibilityImpliedByPublic] to show that turning
         * "public" on already covers it.
         */
        val visibleToAllSignedIn: Boolean = false,
        /**
         * The linked GitHub repository, as typed. Admin only, and empty for
         * everyone else because the server does not send it to them.
         *
         * Populated by the settings load rather than from [existing], because a
         * [ProjectSummary] does not carry it — deliberately, so the picker and
         * every board response stay free of a field only this dialog reads. It
         * therefore appears a moment after the dialog opens, like the vocabularies.
         */
        val repositoryUrl: String = "",
        /** The token's environment variable name — read in `env` mode. Owner only; see [repositoryUrl]. */
        val githubTokenEnv: String = "",
        /**
         * Which source the token comes from: `none`, `env`, or `literal` (LNL-107).
         *
         * Seeded from the server's `githubTokenMode` and then owned by the radio,
         * like the two fields above. A `literal` project arrives here already in
         * `literal` mode with an empty [githubTokenLiteral] — the token is never
         * sent back — which the field renders as a "leave blank to keep it"
         * placeholder. See [TokenModes].
         */
        val githubTokenMode: String = TokenModes.ENV,
        /**
         * The literal token as typed — read in `literal` mode (LNL-107).
         *
         * Never seeded from the server (a stored secret does not travel), so it
         * starts empty on every open and stays empty unless the owner types a new
         * one. An empty value on save means "keep the stored token"; the server
         * decides that, not this. See ProjectUpdate.githubTokenLiteral.
         */
        val githubTokenLiteral: String = "",
        /**
         * Whether to show the repository section at all.
         *
         * From the server's `canConfigureRepository` — an owner or a system
         * administrator (LNL-107), narrower than [canConfigure], which LNL-37 opened
         * to project administrators. False until the settings load returns, so the
         * section does not flash in and out for somebody who may not have it.
         */
        val canConfigureRepository: Boolean = false,
        val isNew: Boolean = true,
        val isBusy: Boolean = false,
        val errorMessage: String? = null,
        val settings: ProjectSettingsState? = null,
        val vocabularyDrafts: Map<VocabularyKind, String> = emptyMap(),
        val pendingVocabularyDelete: PendingVocabularyDelete? = null,
        val settingsErrorMessage: String? = null,
        val hasWrittenSettings: Boolean = false,
        /**
         * Whether this caller may configure the project — admin here. The cog now
         * opens for everyone, so a non-admin reaches this dialog too; when false,
         * the whole settings form and its sections are hidden and only the
         * notification toggle remains. Confirmed by [settings]'s own
         * `canMutateProject`, but held from the start so the dialog renders right
         * on frame one, before the settings load. See ActiveDialog.EditProject.
         */
        val canConfigure: Boolean = true,
        /**
         * Whether they may also rename the project, change its prefix or
         * visibility, or delete it.
         *
         * Narrower than [canConfigure] and held separately because exactly one
         * kind of caller sits between them: a PROJECT administrator, who
         * administers the board and does not own the project's identity in the
         * instance. Defaults to [canConfigure] so every existing caller of this
         * view model — and the new-project path, which is system-administrator
         * only anyway — behaves as it did.
         */
        val canConfigureIdentity: Boolean = true,
        /**
         * Whether THIS user hides issue numbers on this project (LNL-105) — a
         * per-user view choice, shown to any signed-in caller who opens an existing
         * project's settings, alongside the notification toggle. Held here only to
         * drive the switch; the value of record lives on the board view model, which
         * [onHideIssueNumbersChanged] writes through.
         */
        val hideIssueNumbers: Boolean = false,
        /**
         * The name or prefix already being another project's, phrased — or null.
         *
         * Recomputed on every keystroke by [onNameChanged] and [onPrefixChanged],
         * which is the only reason it is a field rather than derived here: the
         * comparison needs the other projects, and [State] deliberately does not
         * carry a list it would only ever fold. It feeds [validationMessage], so
         * a clash disables OK rather than waiting to refuse the press — which is
         * what the class preamble has always said this dialog does.
         */
        val clashMessage: String? = null,
        /**
         * The pending "delete this project?" confirmation, while it is up (LNL-107).
         *
         * Carries the whole sentence and the phrase that must be typed, rather than
         * a boolean the view branches on, so the view keeps making no decisions —
         * the same shape AdminSettingsBackingViewModel.PendingProjectDelete uses for
         * the instance-settings copy of this power.
         */
        val pendingProjectDelete: ProjectDeletePrompt? = null,
    ) {
        val title: String get() = if (isNew) "New project" else "Project settings"

        /**
         * Whether the signed-in-visibility toggle is made redundant by [isPublic]
         * (LNL-138).
         *
         * A public project is readable by everyone including signed-out visitors, so
         * the server's read rule ORs the two — a public project already admits every
         * signed-in account. The dialog reads this to render the second toggle as
         * checked-and-disabled with a caption while public is on, rather than
         * offering a switch that changes nothing. It stays a real, independent stored
         * value underneath, so unticking "public" reveals whatever was chosen here.
         */
        val signedInVisibilityImpliedByPublic: Boolean get() = isPublic

        /**
         * Whether to offer the Delete button in the form's footer.
         *
         * The owner's tier, exactly like [showForm]'s identity fields: deleting a
         * project is [canConfigureIdentity], not [canConfigure]. Never on a new
         * project — there is nothing to delete until OK has made it. See
         * ProjectPermissionsView.canMutateProjectIdentity.
         */
        val showDelete: Boolean get() = !isNew && canConfigureIdentity

        /**
         * Whether to show the name/prefix/public form and the OK button.
         *
         * The form IS the project's identity — name, prefix, visibility — so this
         * follows [canConfigureIdentity] rather than [canConfigure]. Three
         * outcomes, and LNL-107 widened the top one from the system administrator
         * to the owner:
         *
         *  - **Owner or system administrator** — the form, OK, Delete, and the
         *    sections. Identity, repository and deletion are the owner's tier now.
         *  - **Project administrator** — the sections and the notification toggle,
         *    no form and no OK. They administer the board; the project's name and
         *    existence are senior to running it (LNL-37, LNL-107). Showing them a
         *    name field that 403s on save is precisely what this flag prevents.
         *  - **Anyone else** — the notification toggle alone. Unchanged.
         *
         * A new project is always this caller's to fill in, since only a system
         * administrator can create one at all.
         */
        val showForm: Boolean get() = isNew || canConfigureIdentity

        /**
         * Why OK is disabled, or null when it is enabled.
         *
         * A reason rather than a bare boolean, because a disabled button with no
         * explanation is the most annoying thing a dialog can do. The view
         * renders this next to the field.
         */
        val validationMessage: String? get() = when {
            name.isBlank() -> "A project needs a name."
            namePrefix.isBlank() -> "A project needs an issue prefix, like LMX."
            !namePrefix.trim().all { it.isLetterOrDigit() } ->
                "A prefix can only contain letters and digits — it becomes the LMX in LMX-123."
            // Last, so a half-typed prefix reads as "keep typing" rather than as a
            // clash with whichever project happens to share its first letters.
            else -> clashMessage
        }

        val isOkEnabled: Boolean get() = !isBusy && validationMessage == null

        /** What the prefix will actually be stored as; the server upper-cases it. */
        val prefixPreview: String get() = namePrefix.trim().uppercase()

        /**
         * "LMX-123" under the prefix field, so the point of the field is visible
         * — and null while the field is empty, which is why this is nullable.
         *
         * It used to fall back to "LMX", making an empty form claim that issues
         * "will be numbered LMX-123". They will not: with no prefix there is no
         * project, the server rejects it outright (see ProjectRepository). The
         * fallback was a stand-in for a value the user had not chosen yet, and
         * it read as one they had — the same trap as the placeholders this
         * dialog used to carry. A preview of nothing is nothing.
         */
        val prefixExample: String? get() = prefixPreview.ifBlank { null }?.let { "$it-123" }

        // ── The settings half ────────────────────────────────────────────────

        /**
         * Whether to render the vocabulary and privilege sections at all.
         *
         * Both halves matter now that the dialog opens for non-admins: the
         * settings must have loaded, *and* this caller must be an admin. A
         * non-admin's settings load (they get one, thinned) must not paint empty
         * "Board columns" sections with add fields that would 403. See the server's
         * buildSettings.
         */
        val hasSettings: Boolean get() = settings != null && canConfigure

        // ── The Features section (LNL-96) ──

        /**
         * Whether to show the discussions/messages toggles.
         *
         * The same gate as [hasSettings] — a project administrator, settings
         * loaded — because these are project-administrator switches and share the
         * admin half's fate. Read straight from [settings] rather than mirrored
         * into a State field: a toggle writes immediately and takes the whole fresh
         * [ProjectSettingsState] back, so the checkbox reflecting `settings` is
         * always the server's last word. Same shape the vocabulary sections use.
         */
        val showFeaturesSection: Boolean get() = hasSettings

        /** Whether this project currently offers a discussion forum, and private messages. */
        val discussionsEnabled: Boolean get() = settings?.discussionsEnabled ?: true
        val messagesEnabled: Boolean get() = settings?.messagesEnabled ?: true

        // ── The Structure tab's new-ticket requirements (LNL-106) ─────────────

        /** Shown on the Structure tab for a project administrator; same gate as the vocabularies. */
        val showRequirementsSection: Boolean get() = hasSettings

        /** Whether a new ticket must carry a label, and whether it must carry a component. */
        val requireLabel: Boolean get() = settings?.requireLabel ?: false
        val requireComponent: Boolean get() = settings?.requireComponent ?: false

        /** Whether closing an issue with a done resolution must carry a fixed version (LNL-134). */
        val requireFixedVersionOnResolve: Boolean get() = settings?.requireFixedVersionOnResolve ?: false

        /** Whether the board shows each card's author on a muted footer line (LNL-157). */
        val showIssueAuthor: Boolean get() = settings?.showIssueAuthor ?: false

        /**
         * Whether the project has any versions to pick, and any resolution marked
         * done — the two things the fix-version requirement needs to be satisfiable
         * (LNL-134). Like [hasLabels], a toggle with neither would be a trap: the
         * view uses this to explain, next to the switch, that making a version and
         * marking a resolution done is what turns it on in practice.
         */
        val hasVersions: Boolean get() = settings?.versions?.isNotEmpty() ?: false
        val hasDoneResolution: Boolean get() = settings?.resolutions?.any { it.isDone } ?: false

        /**
         * Whether the project has any labels / components at all. A requirement with
         * nothing to pick would make the project unfileable, so the toggle carries a
         * caption saying so and the server no-ops it — mirroring the enforcement,
         * which only bites when there is something to require.
         */
        val hasLabels: Boolean get() = settings?.labels?.isNotEmpty() ?: false
        val hasComponents: Boolean get() = settings?.components?.isNotEmpty() ?: false

        // ── The notification section, for everyone the cog opens for ──────────

        /**
         * Whether to show the new-issue notification toggle.
         *
         * An existing project whose settings have loaded — a new project has no id
         * to subscribe to yet. Shown to admin and non-admin alike; it is the one
         * thing a non-admin came here for.
         */
        val showNotificationSection: Boolean get() = !isNew && settings != null

        /**
         * Whether to show the per-user display section — today the one toggle that
         * hides issue numbers on this project (LNL-105). Same gate as the
         * notification toggle: an existing project whose settings have loaded, shown
         * to admin and non-admin alike, because it is this user's own view choice.
         */
        val showDisplaySection: Boolean get() = !isNew && settings != null

        /** Whether the caller is currently subscribed to this project's new issues. */
        val notifyOnNewIssue: Boolean get() = settings?.notifyOnNewIssue == true

        /**
         * Whether the caller has an e-mail at all. Without one the toggle is
         * replaced by a hint pointing at the profile dialog — a switch that
         * promises mail we cannot send is a dead control.
         */
        val canReceiveEmailNotifications: Boolean get() = settings?.canReceiveEmailNotifications == true

        /**
         * The six vocabularies, in the order the dialog stacks them.
         *
         * Statuses first because they are the board: they are what an admin came
         * here to change, and the one whose order is visible on screen five
         * seconds later. Then resolutions before priorities (LNL-102): both are
         * facets of a closing status, and a resolution is only offered once a
         * status asks for one, so it reads next to the columns it belongs to.
         * Labels and components last because they are the ones an issue merely
         * wears rather than the ones that place it.
         *
         * Sprint is last of all and the view lifts it onto its own tab (LNL-102);
         * everything above shares the Structure tab. All six are arrangeable —
         * labels and components used to be the exception, sorted by name with no
         * arrows, which read as a missing feature rather than a rule, because it
         * was one. See LNL-28.
         */
        val sections: List<VocabularySection> get() = settings?.let { loaded ->
            listOf(
                section(
                    loaded,
                    VocabularyKind.STATUS,
                    title = "Board columns",
                    hint = "Left to right, as the board shows them. A new issue lands in the " +
                        "first one. Tick \"needs a resolution\" on a column that closes an " +
                        "issue — that is what makes the board ask why.",
                ),
                section(
                    loaded,
                    VocabularyKind.RESOLUTION,
                    title = "Resolutions",
                    hint = "Why an issue was closed. Offered when an issue moves into a column " +
                        "that needs a resolution. Tick \"means done\" on the ones that finish " +
                        "the work — that is what \"require a fixed version\" below asks about.",
                ),
                section(
                    loaded,
                    VocabularyKind.PRIORITY,
                    title = "Priorities",
                    hint = "Most urgent first. A new issue gets the middle one, so adding or " +
                        "removing a priority changes what \"default\" means.",
                ),
                section(
                    loaded,
                    VocabularyKind.LABEL,
                    title = "Labels",
                    hint = "In the order they are offered and drawn. Deleting one removes " +
                        "it from the issues that wear it; the issues themselves are untouched.",
                ),
                section(
                    loaded,
                    VocabularyKind.COMPONENT,
                    title = "Components",
                    hint = "Which part of the thing an issue is about, in the order they are " +
                        "offered. Deleting one removes it from its issues, like a label.",
                ),
                // Last, and empty in most projects. The hint has to do more work
                // than the others': every section above describes something the
                // project already has, and this one has to explain what making
                // the first sprint would turn on — including that nothing here
                // changes until somebody does. See Sprints.sq.
                section(
                    loaded,
                    VocabularyKind.SPRINT,
                    title = "Sprints",
                    hint = "Timeboxes, in planning order. Most projects need none — with no " +
                        "sprints the board works exactly as it does now, and nothing about " +
                        "sprints appears anywhere. Make one and the board gains a scope " +
                        "picker. Deleting a sprint releases its issues to the backlog rather " +
                        "than refusing.",
                ),
                // Empty in most projects, like sprints — presence is the flag. The
                // hint carries the same weight: nothing about versions appears until
                // the first one is made. See Versions.sq.
                section(
                    loaded,
                    VocabularyKind.VERSION,
                    title = "Versions",
                    hint = "Releases, in the order the pickers offer them. Most projects need " +
                        "none — with no versions the planned- and fixed-version fields do not " +
                        "appear on any issue. Make one and issues can be planned for it and " +
                        "marked fixed in it. Deleting a version releases the issues that named " +
                        "it rather than refusing.",
                ),
            )
        }.orEmpty()

        /**
         * Every account, and what it holds here.
         *
         * Admins first, and `sortedByDescending` because it is stable — the
         * server's order survives among the rest. They lead because they are the
         * answer to "who can do this?" that outranks every row below them: read
         * top-down, the table now states the blanket permission before the
         * granted ones, which is the order they actually apply in.
         */
        val members: List<MemberRowState> get() = settings?.let { loaded ->
            loaded.members.sortedByDescending { it.isSysAdmin }.map { member ->
                MemberRowState(
                    userId = member.userId,
                    name = if (member.isSelf) "${member.name} (you)" else member.name,
                    note = when {
                        member.isSysAdmin ->
                            "System administrator — can do everything in every project."
                        else -> null
                    },
                    // No boxes at all on an admin's row, rather than boxes that
                    // are ticked-and-dead or unticked-and-dead. Either way they
                    // describe a grant that decides nothing: AccessControl says
                    // yes to an admin before it looks at a role. The note is the
                    // whole truth of the row, so it is the only thing on it.
                    roles = if (member.isSysAdmin) {
                        emptyList()
                    } else {
                        loaded.roles.map { role ->
                            RoleToggle(
                                key = role.key,
                                description = role.description,
                                isOn = role.key in member.roleKeys,
                                // Not while a write is in flight: two clicks on the
                                // same box before the first answer arrives would send
                                // the second from a state that is already stale.
                                //
                                // And never the two senior boxes — project
                                // administrator and project owner — unless the caller
                                // may grant them: an administrator opens this dialog
                                // and hands out the ordinary roles freely, but may
                                // promote neither a peer nor an owner. Shown rather
                                // than hidden, so the row still explains what the role
                                // is and who would have to grant it — see
                                // AccessControl.canGrant, which refuses it regardless.
                                isEnabled = !isBusy && (
                                    role.key !in SENIOR_ROLE_KEYS || loaded.canGrantSeniorRoles
                                    ),
                            )
                        }
                    },
                )
            }
        }.orEmpty()

        /** The heading over the privileges table, and what it is for. */
        val membersHint: String get() =
            "Who may do what in this project. Everyone with an account is listed — " +
                "tick a box to grant, untick to revoke. It takes effect immediately."

        private fun section(
            loaded: ProjectSettingsState,
            kind: VocabularyKind,
            title: String,
            hint: String,
        ): VocabularySection {
            val entries = loaded.entriesFor(kind)
            val draft = vocabularyDrafts[kind].orEmpty()
            return VocabularySection(
                kind = kind,
                title = title,
                hint = hint,
                draftName = draft,
                isAddEnabled = draft.isNotBlank() && !isBusy,
                rows = entries.mapIndexed { index, entry ->
                    entry.toRow(kind, index, entries)
                },
            )
        }

        /**
         * One row's affordances.
         *
         * The three rules mirrored from the server, and mirrored *deliberately* —
         * the point is not to enforce them here (nothing in a browser enforces
         * anything) but to not offer a button that is going to be refused:
         *
         *  - The last status or priority cannot go, because a project without one
         *    cannot take an issue and cannot be repaired from this dialog.
         *  - A status, priority or resolution in use cannot go: the database
         *    refuses to orphan the issues pointing at it.
         *  - A label or a component in use can go, and takes its links with it.
         *    That is the confirm's sentence, not a reason to disable anything.
         *
         * "In use" means *published* issues. A draft is on nobody's board, so it
         * is not something an admin can be told to move — the server does not
         * count one and deletes it with the row instead (LNL-183).
         */
        private fun VocabularyEntry.toRow(
            kind: VocabularyKind,
            index: Int,
            siblings: List<VocabularyEntry>,
        ): VocabularyRowState {
            val isLastOfAKindThatMatters = siblings.size <= 1 && kind.isLoadBearing
            val isBlockedByUse = kind.restrictsOnUse && usageCount > 0
            return VocabularyRowState(
                id = id,
                name = name,
                requiresResolution = requiresResolution,
                isDone = isDone,
                isDeletable = !isLastOfAKindThatMatters && !isBlockedByUse && !isBusy,
                // Two or three words, on the row. The long sentence below says what
                // to do; this says what is true, and it is the half somebody
                // reading a greyed-out Delete actually gets to see.
                deleteBlockedSummary = when {
                    isLastOfAKindThatMatters -> "the only ${kind.noun}"
                    isBlockedByUse -> if (usageCount == 1) "1 issue" else "$usageCount issues"
                    else -> null
                },
                deleteBlockedReason = when {
                    isLastOfAKindThatMatters ->
                        "A project needs at least one ${kind.noun}. Add another first."
                    // The verb agrees with the count, like the server's own
                    // refusal does — this is the same sentence arriving a
                    // round-trip earlier, and "1 issue are in this status" is the
                    // tell that nobody read it.
                    isBlockedByUse -> {
                        val subject = if (usageCount == 1) "1 issue" else "$usageCount issues"
                        val verb = when {
                            kind == VocabularyKind.STATUS && usageCount == 1 -> "is in"
                            kind == VocabularyKind.STATUS -> "are in"
                            usageCount == 1 -> "still has"
                            else -> "still have"
                        }
                        val move = if (usageCount == 1) "Move it first." else "Move them first."
                        "$subject $verb this ${kind.noun}. $move"
                    }
                    else -> null
                },
                canMoveUp = index > 0 && !isBusy,
                canMoveDown = index < siblings.size - 1 && !isBusy,
                showsClosingFlag = kind == VocabularyKind.STATUS,
                showsDoneFlag = kind == VocabularyKind.RESOLUTION,
            )
        }
    }

    fun onNameChanged(value: String) {
        val current = _stateFlow.value
        _stateFlow.value = current.copy(
            name = value,
            errorMessage = null,
            clashMessage = nameClash(value, current.namePrefix),
        )
    }

    fun onPrefixChanged(value: String) {
        val current = _stateFlow.value
        _stateFlow.value = current.copy(
            namePrefix = value,
            errorMessage = null,
            clashMessage = nameClash(current.name, value),
        )
    }

    fun onPublicChanged(value: Boolean) {
        _stateFlow.value = _stateFlow.value.copy(isPublic = value)
    }

    /**
     * The signed-in-visibility toggle was flipped (LNL-138).
     *
     * Staged into the form and written on OK beside [onPublicChanged], because it is
     * the same owner identity write — not a project-administrator setting like the
     * feature toggles, which write immediately. Stored independently of [isPublic]
     * even while public makes it redundant, so unticking public later restores what
     * was chosen here rather than silently clearing it.
     */
    fun onVisibleToAllSignedInChanged(value: Boolean) {
        _stateFlow.value = _stateFlow.value.copy(visibleToAllSignedIn = value)
    }

    /**
     * A Features toggle was flipped (LNL-96).
     *
     * Unlike [onPublicChanged], which stages into the form and writes on OK, these
     * write at once — they are project-administrator settings like the vocabularies
     * and grants, and go through the same [write] helper, so a flip reloads the
     * board on close and the tab appears or disappears. The pair is always sent
     * whole: the flag not being changed is read from the current [State] so the
     * server does not resurrect it from a stale default.
     */
    fun onDiscussionsEnabledChanged(value: Boolean) {
        val project = existing ?: return
        val current = _stateFlow.value
        write("Could not change this project's discussions.") {
            storage.setProjectFeatures(
                project.id,
                discussionsEnabled = value,
                messagesEnabled = current.messagesEnabled,
            )
        }
    }

    fun onMessagesEnabledChanged(value: Boolean) {
        val project = existing ?: return
        val current = _stateFlow.value
        write("Could not change this project's messages.") {
            storage.setProjectFeatures(
                project.id,
                discussionsEnabled = current.discussionsEnabled,
                messagesEnabled = value,
            )
        }
    }

    /**
     * Turn the new-ticket label / component requirements on or off (LNL-106). Like
     * the feature toggles: the pair is always sent whole, the unchanged flag read
     * from the current [State] so the server does not resurrect it from a stale
     * default, and the fresh settings come back so the checkbox is the server's word.
     */
    fun onRequireLabelChanged(value: Boolean) {
        val project = existing ?: return
        val current = _stateFlow.value
        write("Could not change this project's label requirement.") {
            storage.setProjectRequirements(
                project.id,
                requireLabel = value,
                requireComponent = current.requireComponent,
                requireFixedVersionOnResolve = current.requireFixedVersionOnResolve,
            )
        }
    }

    fun onRequireComponentChanged(value: Boolean) {
        val project = existing ?: return
        val current = _stateFlow.value
        write("Could not change this project's component requirement.") {
            storage.setProjectRequirements(
                project.id,
                requireLabel = current.requireLabel,
                requireComponent = value,
                requireFixedVersionOnResolve = current.requireFixedVersionOnResolve,
            )
        }
    }

    /** Turn the require-a-fixed-version-when-resolving switch on or off (LNL-134). See [onRequireLabelChanged]. */
    fun onRequireFixedVersionChanged(value: Boolean) {
        val project = existing ?: return
        val current = _stateFlow.value
        write("Could not change this project's fixed-version requirement.") {
            storage.setProjectRequirements(
                project.id,
                requireLabel = current.requireLabel,
                requireComponent = current.requireComponent,
                requireFixedVersionOnResolve = value,
            )
        }
    }

    /**
     * Turn the show-author-on-cards board-display setting on or off (LNL-157). Like
     * the requirement toggles: it writes at once through [write], and the fresh
     * settings come back so the checkbox is the server's word. A display setting, so
     * it goes through its own storage call rather than the requirements one.
     */
    fun onShowIssueAuthorChanged(value: Boolean) {
        val project = existing ?: return
        write("Could not change this project's display settings.") {
            storage.setProjectDisplaySettings(project.id, showIssueAuthor = value)
        }
    }

    /**
     * Hide or show issue numbers for this project, for THIS user (LNL-105).
     *
     * Unlike the toggles above this writes no project setting: it updates local
     * state so the switch reflects the click, and hands the choice to the board view
     * model through [persistHideIssueNumbers], which owns the per-user preference
     * blob and persists it. Fire-and-forget, like the board's own column-hide — a
     * view choice whose honest failure is not surviving a reload, not an alert.
     */
    fun onHideIssueNumbersChanged(value: Boolean) {
        if (_stateFlow.value.hideIssueNumbers == value) return
        _stateFlow.value = _stateFlow.value.copy(hideIssueNumbers = value)
        persistHideIssueNumbers(value)
    }

    fun onRepositoryUrlChanged(value: String) {
        _stateFlow.value = _stateFlow.value.copy(repositoryUrl = value)
    }

    /**
     * The token variable name changed.
     *
     * Not validated as you type, unlike the project name's clash check. The rule
     * is a prefix, and a half-typed conforming name spends most of its keystrokes
     * failing it — so a live error would be red for the whole time somebody is
     * typing a perfectly good value. The server refuses on save with a sentence
     * naming the required prefix, and [GITHUB_TOKEN_ENV_HINT] says it up front.
     */
    fun onGithubTokenEnvChanged(value: String) {
        _stateFlow.value = _stateFlow.value.copy(githubTokenEnv = value)
    }

    /**
     * The token source radio changed (LNL-107).
     *
     * The other fields are left as they are rather than cleared: someone toggling
     * between `env` and `literal` while comparing the two should not lose what they
     * typed in the one they toggled away from. The server reads only the field the
     * chosen mode owns and ignores the rest, so a stale value in a hidden field
     * cannot leak into what is saved. See parseRepositoryConfig.
     */
    fun onGithubTokenModeChanged(value: String) {
        _stateFlow.value = _stateFlow.value.copy(githubTokenMode = value)
    }

    /** The literal-token field changed (LNL-107). Write-only; see [State.githubTokenLiteral]. */
    fun onGithubTokenLiteralChanged(value: String) {
        _stateFlow.value = _stateFlow.value.copy(githubTokenLiteral = value)
    }

    /**
     * OK.
     *
     * The local name check runs again here rather than trusting
     * [State.clashMessage] alone: that one disables OK as you type, and this one
     * is the guard for the press that got through anyway — a project created in
     * another tab while this dialog sat open, say. A clash the dialog *cannot*
     * see still comes back as a 409, and is shown with the server's own words.
     */
    fun onOkTapped() {
        val current = _stateFlow.value
        if (!current.isOkEnabled) return

        val clash = nameClash(current.name, current.namePrefix)
        if (clash != null) {
            _stateFlow.value = current.copy(errorMessage = clash)
            return
        }

        _stateFlow.value = current.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching {
                if (existing == null) {
                    storage.createProject(
                        current.name,
                        current.namePrefix,
                        current.isPublic,
                        current.visibleToAllSignedIn,
                    )
                } else {
                    storage.updateProject(
                        existing.id,
                        current.name,
                        current.namePrefix,
                        current.isPublic,
                        current.visibleToAllSignedIn,
                        current.repositoryUrl,
                        current.githubTokenEnv,
                        current.githubTokenMode,
                        current.githubTokenLiteral,
                    )
                }
            }
            result.fold(
                onSuccess = {
                    println("EditProject: saved ${it.name}")
                    onFinished(true, it)
                },
                onFailure = { t ->
                    println("EditProject: save failed: ${t.message}")
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not save that project."),
                    )
                },
            )
        }
    }

    /**
     * Cancel.
     *
     * Reports `changed` when a section has written something, even though Cancel
     * threw the *form* away. Not a contradiction — the two halves of this dialog
     * are different things, and the sections' writes already happened. Reporting
     * false here would leave a renamed column showing its old name on the board
     * until somebody refreshed. See this file's preamble.
     */
    fun onCancelTapped() {
        if (_stateFlow.value.isBusy) return
        onFinished(_stateFlow.value.hasWrittenSettings, null)
    }

    // ── Deletion ─────────────────────────────────────────────────────────────
    //
    // Back in this dialog since LNL-107, for the caller LNL-93 could not serve: an
    // owner may delete their own project but cannot open the instance-settings
    // dialog where LNL-93 had put the power. The system administrator's copy still
    // lives there too, over projects at large; this one is one owner deleting one
    // board they hold. Both go through DELETE /api/projects and both are guarded by
    // the typed-phrase confirmation the ticket asked for.

    /** The Delete button was pressed: raise the confirmation, armed by a typed phrase. */
    fun onDeleteProjectTapped() {
        val project = existing ?: return
        _stateFlow.value = _stateFlow.value.copy(
            pendingProjectDelete = ProjectDeletePrompt(
                title = "Delete ${project.name}?",
                message = "This deletes the project and everything in it — every issue, comment, " +
                    "attachment and discussion. It cannot be undone. Type the phrase below to confirm.",
                confirmationPhrase = DELETE_PROJECT_CONFIRMATION_PHRASE,
            ),
        )
    }

    /** The confirmation was dismissed without deleting. */
    fun onDeleteProjectCancelled() {
        _stateFlow.value = _stateFlow.value.copy(pendingProjectDelete = null)
    }

    /**
     * The delete was confirmed — the phrase matched, and the view armed the button.
     *
     * Clears the pending confirmation first so it closes at once rather than
     * lingering over the busy write, then reports the project gone: `saved` is null,
     * for the reason [onFinished] documents, and `changed` is true so MainScreen
     * drops the board this dialog was configuring. A refusal — the affordance and
     * the server disagreeing, or a project a second owner deleted first — surfaces
     * as the form's error rather than as a half-closed dialog.
     */
    fun onDeleteProjectConfirmed() {
        val project = existing ?: return
        if (_stateFlow.value.pendingProjectDelete == null) return
        _stateFlow.value = _stateFlow.value.copy(pendingProjectDelete = null, isBusy = true, errorMessage = null)
        scope.launch {
            runCatching { storage.deleteProject(project.id) }.fold(
                onSuccess = { onFinished(true, null) },
                onFailure = { t ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not delete that project."),
                    )
                },
            )
        }
    }

    // ── The vocabularies ─────────────────────────────────────────────────────

    fun onVocabularyDraftChanged(kind: VocabularyKind, value: String) {
        _stateFlow.value = _stateFlow.value.copy(
            vocabularyDrafts = _stateFlow.value.vocabularyDrafts + (kind to value),
        )
    }

    /**
     * Add.
     *
     * The draft is cleared only on success. A refusal — the name is taken —
     * leaves what was typed in the field, because the fix is to edit it, and a
     * field that emptied itself on a 409 would make the admin retype the whole
     * name to change one letter of it.
     */
    fun onAddVocabularyTapped(kind: VocabularyKind) {
        val project = existing ?: return
        val name = _stateFlow.value.vocabularyDrafts[kind].orEmpty()
        if (name.isBlank()) return
        write("Could not add that ${kind.noun}.") {
            val settings = storage.addVocabulary(project.id, kind, name)
            _stateFlow.value = _stateFlow.value.copy(
                vocabularyDrafts = _stateFlow.value.vocabularyDrafts - kind,
            )
            settings
        }
    }

    /**
     * Rename a row, or set a status's closing flag or a resolution's done flag.
     *
     * One intent for all, because the server takes each pairing in one write — a
     * status's name and its closing flag, a resolution's name and its done flag, are
     * one row and one decision (LNL-134). The view sends whichever it has just
     * changed along with the others' current values; it does not get to send half a
     * row. [requiresResolution] is meaningful only for a status, [isDone] only for a
     * resolution — each is ignored server-side for the kinds it does not belong to.
     *
     * A rename that changes nothing is dropped here rather than sent and ignored:
     * the field commits on blur, so tabbing through the sections would otherwise
     * fire a PUT per row, each one rewriting a name to itself.
     */
    fun onVocabularyEdited(
        kind: VocabularyKind,
        id: Long,
        name: String,
        requiresResolution: Boolean,
        isDone: Boolean = false,
    ) {
        val project = existing ?: return
        val current = _stateFlow.value.settings?.entriesFor(kind)?.firstOrNull { it.id == id } ?: return
        if (current.name == name && current.requiresResolution == requiresResolution && current.isDone == isDone) return
        if (name.isBlank()) {
            // Refused here rather than sent: the server would refuse it too, with
            // the same sentence, but this way the field still holds what was typed
            // and an alert has not stolen focus from it.
            _stateFlow.value = _stateFlow.value.copy(
                settingsErrorMessage = "A ${kind.noun} needs a name.",
            )
            return
        }
        write("Could not save that ${kind.noun}.") {
            storage.editVocabulary(project.id, kind, id, name, requiresResolution, isDone)
        }
    }

    /**
     * Move a row one place earlier or later.
     *
     * Arrows rather than drag-and-drop: this is a settings dialog opened rarely,
     * the lists are five rows long, and a drag needs a drop target, a ghost and a
     * touch story. The board's cards earn that; a list of five statuses does not.
     *
     * The whole new order is sent, not "row 3 moved up" — see [VocabularyOrder].
     * A move that would fall off either end is dropped rather than clamped: the
     * view does not offer the button, so getting here means the state moved under
     * the click, and re-sending the order unchanged would be a write that does
     * nothing.
     */
    fun onMoveVocabulary(kind: VocabularyKind, id: Long, offset: Int) {
        val project = existing ?: return
        val ids = _stateFlow.value.settings?.entriesFor(kind)?.map { it.id } ?: return
        val from = ids.indexOf(id).takeIf { it >= 0 } ?: return
        val to = from + offset
        if (to !in ids.indices) return
        val reordered = ids.toMutableList().apply {
            removeAt(from)
            add(to, id)
        }
        write("Could not reorder those ${kind.plural}.") {
            storage.reorderVocabulary(project.id, kind, reordered)
        }
    }

    /**
     * Ask before deleting, and name what it costs.
     *
     * The message is built here, from the counts the server sent, because it is
     * the whole point of the confirmation: "Delete Bug?" tells an admin nothing
     * they did not know when they clicked. "Delete Bug? 12 issues will lose that
     * label" is the sentence that stops the click that should not have happened.
     */
    fun onDeleteVocabularyTapped(kind: VocabularyKind, id: Long) {
        val entry = _stateFlow.value.settings?.entriesFor(kind)?.firstOrNull { it.id == id } ?: return
        val issues = "${entry.usageCount} ${if (entry.usageCount == 1) "issue" else "issues"}"
        val consequence = when {
            entry.usageCount == 0 -> "Nothing uses it."
            // The only kinds that reach a confirmation while in use: the others
            // are refused before the button lights up. Deleting one of these
            // unlabels the issues; it does not touch the issues themselves. See
            // IssueLabels.sq.
            else -> "$issues will lose it. The issues themselves are not affected."
        }
        _stateFlow.value = _stateFlow.value.copy(
            pendingVocabularyDelete = PendingVocabularyDelete(
                kind = kind,
                id = id,
                title = "Delete ${kind.noun}",
                message = "Delete \"${entry.name}\"? $consequence This cannot be undone.",
            ),
        )
    }

    fun onVocabularyDeleteCancelled() {
        _stateFlow.value = _stateFlow.value.copy(pendingVocabularyDelete = null)
    }

    fun onVocabularyDeleteConfirmed() {
        val project = existing ?: return
        val pending = _stateFlow.value.pendingVocabularyDelete ?: return
        _stateFlow.value = _stateFlow.value.copy(pendingVocabularyDelete = null)
        write("Could not delete that ${pending.kind.noun}.") {
            storage.deleteVocabulary(project.id, pending.kind, pending.id)
        }
    }

    // ── The privileges ───────────────────────────────────────────────────────

    /**
     * Grant or revoke one role for one user.
     *
     * No confirmation, deliberately, and it is the one destructive-looking thing
     * here that does not get one: revoking is instantly reversible by ticking the
     * box again, and nothing is lost when you do. A confirmation on an action that
     * undoes itself teaches people to click through confirmations.
     */
    fun onRoleToggled(userId: Long, roleKey: String, isGranted: Boolean) {
        val project = existing ?: return
        write("Could not change that privilege.") {
            storage.setProjectRole(project.id, userId, roleKey, isGranted)
        }
    }

    // ── The notification toggle (every signed-in caller, not just admins) ──────

    /**
     * Subscribe or unsubscribe from this project's new-issue e-mails.
     *
     * Deliberately not routed through [write]: unlike a vocabulary or role edit,
     * this changes nothing the board renders, so it must not set
     * [State.hasWrittenSettings] and trigger a needless board reload on close. It
     * still takes the whole fresh [ProjectSettingsState] back, so the toggle
     * re-renders from the server's truth.
     */
    fun onNewIssueNotificationToggled(subscribed: Boolean) {
        val project = existing ?: return
        if (_stateFlow.value.isBusy) return
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, settingsErrorMessage = null)
        scope.launch {
            runCatching { storage.setProjectNewIssueNotification(project.id, subscribed) }.fold(
                onSuccess = { _stateFlow.value = _stateFlow.value.copy(isBusy = false, settings = it) },
                onFailure = { t ->
                    println("EditProject: notification toggle failed: ${t.message}")
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        settingsErrorMessage = t.userMessage("Could not change your notification setting."),
                    )
                },
            )
        }
    }

    // ── Loading and writing ──────────────────────────────────────────────────

    /**
     * Fetch the vocabularies and the grants.
     *
     * A failure is swallowed into "no settings section" rather than shown. The
     * only way to open this dialog is the cogwheel, which is admin-only, so a 403
     * here means the client's affordances and the server disagree — and the
     * honest rendering of "you may not configure this" is a dialog that does not
     * offer to. Shouting about it would put an alert over a form the user can
     * still legitimately use.
     */
    private fun loadSettings() {
        val project = existing ?: return
        scope.launch {
            runCatching { storage.projectSettings(project.id) }.fold(
                // The two repository fields are seeded from this response and
                // then owned by the form, like name and prefix — every later
                // settings write returns a fresh ProjectSettingsState, and taking
                // these from it each time would overwrite what the admin is
                // halfway through typing the moment they added a label.
                onSuccess = { settings ->
                    val hasEditedRepository = _stateFlow.value.repositoryUrl.isNotEmpty() ||
                        _stateFlow.value.githubTokenEnv.isNotEmpty() ||
                        _stateFlow.value.githubTokenLiteral.isNotEmpty()
                    _stateFlow.value = _stateFlow.value.copy(
                        settings = settings,
                        canConfigureRepository = settings.canConfigureRepository,
                        repositoryUrl = _stateFlow.value.repositoryUrl.ifEmpty { settings.repositoryUrl },
                        githubTokenEnv = _stateFlow.value.githubTokenEnv.ifEmpty { settings.githubTokenEnv },
                        // The mode is not an .ifEmpty field — its "unset" is not a
                        // blank string but "the owner has not touched the radio". So
                        // it is seeded only while nothing repository-shaped has been
                        // edited yet, matching what the two fields above do the first
                        // time the load lands. The literal is never seeded; see State.
                        githubTokenMode = if (hasEditedRepository) {
                            _stateFlow.value.githubTokenMode
                        } else {
                            settings.githubTokenMode
                        },
                    )
                },
                onFailure = { println("EditProject: settings unavailable: ${it.message}") },
            )
        }
    }

    /**
     * Run one settings write, and take the whole new state from its answer.
     *
     * Every one of these returns the entire [ProjectSettingsState], and this is
     * where that pays: the dialog never patches its own copy, so it cannot be
     * right about the row it touched and wrong about the rest — deleting a status
     * changes whether the last remaining one may be deleted, and adding a priority
     * moves the middle of the scale. See LunicleApi's project settings section.
     *
     * [State.hasWrittenSettings] is set on success and never unset: it means "the
     * board on screen is stale", which stays true no matter what happens
     * afterwards.
     *
     * @param fallback what to say when the failure is not the server's — a dropped
     *   connection has no sentence worth showing. A refusal does, and it wins; see
     *   `userMessage`.
     */
    private fun write(fallback: String, block: suspend () -> ProjectSettingsState) {
        if (_stateFlow.value.isBusy) return
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, settingsErrorMessage = null)
        scope.launch {
            runCatching { block() }.fold(
                onSuccess = { settings ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        settings = settings,
                        hasWrittenSettings = true,
                    )
                },
                onFailure = { t ->
                    println("EditProject: settings write failed: ${t.message}")
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        settingsErrorMessage = t.userMessage(fallback),
                    )
                },
            )
        }
    }

    /** The settings alert has been read. */
    fun onSettingsErrorDismissed() {
        _stateFlow.value = _stateFlow.value.copy(settingsErrorMessage = null)
    }

    /**
     * Is this name or prefix already someone else's?
     *
     * `lowercase()` rather than a case-insensitive compare, and it is the same
     * Unicode-aware fold the server's `ProjectRepository.validate` uses — so the
     * dialog and the server agree about `Ärenden` and `ärenden` being one name.
     * Kotlin/JS and the JVM both implement it over the full Unicode range, which
     * SQLite's `COLLATE NOCASE` does not: that folds ASCII A–Z only.
     *
     * @return the sentence to show, or null if there is no clash.
     */
    private fun nameClash(name: String, prefix: String): String? {
        val cleanName = name.trim().lowercase()
        val cleanPrefix = prefix.trim().lowercase()
        val others = otherProjects.filter { it.id != existing?.id }
        others.firstOrNull { it.name.lowercase() == cleanName }?.let {
            return "There is already a project called \"${it.name}\"."
        }
        others.firstOrNull { it.namePrefix.lowercase() == cleanPrefix }?.let {
            return "\"${it.namePrefix}\" is already used by \"${it.name}\"."
        }
        return null
    }
}
