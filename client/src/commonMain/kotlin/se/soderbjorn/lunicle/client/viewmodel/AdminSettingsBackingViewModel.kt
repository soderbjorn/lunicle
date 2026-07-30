/**
 * Backing view-model for the settings pane's three instance-wide tabs — **Who gets in**,
 * **People** and **Instance** (LNL-195).
 *
 * The project convention, unchanged: one immutable [State] over a single [StateFlow],
 * every decision made here, and views that render what they are handed and forward intent
 * back. Every string on those three tabs — the headings, the sentence explaining why an
 * administrator's project table is a paragraph, the label on a switch — is written below
 * rather than in the DOM views.
 *
 * ── What these screens can and cannot change ────────────────────────────────
 *
 * The per-project rights on the People tab are **read-only**, and that is a decision
 * rather than an omission. Granting a rung is already the project's own Access section,
 * which is scoped to the project the grant belongs to; a second editor for the same fact,
 * reached from a different tab, would be two places to look when a grant is wrong and two
 * things to keep in step. This tab answers "what does this account have", which is the
 * question that had no home. **That reasoning survives the rework** — only the rendering
 * changed, from seven ticks per project to one rung.
 *
 * Instance-adminship is not editable either, and that one is not a decision this file gets
 * to make: there is no route on the server that sets it. The first account to sign in
 * becomes the administrator and the boot pass seats them as owner. The Instance tab says
 * who holds it rather than offering a control that would 404.
 *
 * **Ownership is the one exception**, and only in one direction (LNL-198): the owner may
 * hand the whole deployment to somebody else. Not "add an owner" — there is one at a time,
 * so the gesture is a move, and the person making it cannot reverse it. See
 * [PendingHandOver] and [handOverPhrase] for the ceremony that follows from that.
 *
 * ── What a new project starts with is a setting, not a policy ───────────────
 *
 * The audience rows on Who-gets-in are copied into a project at creation and never
 * consulted again. Editing them changes nothing about any project that already exists,
 * which the subtitle says out loud — an administrator who expected otherwise would
 * conclude the setting was broken.
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
import se.soderbjorn.lunicle.clientserver.AdmissionOption
import se.soderbjorn.lunicle.clientserver.AdmissionPolicy
import se.soderbjorn.lunicle.clientserver.AdminSettingsState
import se.soderbjorn.lunicle.clientserver.AdminUser
import se.soderbjorn.lunicle.clientserver.AudienceRow
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
import se.soderbjorn.lunicle.clientserver.OwnerCandidate
import se.soderbjorn.lunicle.clientserver.RungOption
import se.soderbjorn.lunicle.clientserver.TierCard

// ── Who gets in ─────────────────────────────────────────────────────────────

/** The line under the tab's title, saying what the tab is and — importantly — is not. */
const val ACCESS_TAB_LEAD: String =
    "What an account is here, and what it may do across the whole instance. Nothing on this " +
        "tab grants access to any project — that is each project's own Access section."

/** The heading over the three admission choices. */
const val ADMISSION_TITLE: String = "Who is allowed an account"

/** What the admission setting decides, under its heading. */
const val ADMISSION_HINT: String =
    "Asked once, when an account is about to be created. It admits somebody to the deployment " +
        "and grants them nothing in it."

/**
 * The line naming the doors, and whose decision they are.
 *
 * The second half matters: an administrator reading a greyed choice needs to know the
 * limit is the *deployment's* — something a redeploy could change — rather than
 * something they have set and could unset from this screen.
 */
fun waysInLine(waysIn: List<String>): String = when {
    waysIn.isEmpty() ->
        "No way in at all. Nobody can sign in to this deployment, which is a deploy-time " +
            "problem rather than a setting on this screen."
    else ->
        "Ways in: ${waysIn.joinToString(" · ")}. Which ways in exist is the deployment's own " +
            "configuration, not this screen's."
}

/** The heading over the read-only staff domain. */
const val STAFF_DOMAIN_TITLE: String = "Staff domain"

/**
 * The staff domain, shown and never editable.
 *
 * Deliberately not a field. "Who is staff here" decides what the staff audience means on
 * every project, so being able to widen it from inside the app would be a way to hand
 * every project's staff row to a new set of people without touching any project.
 */
fun staffDomainLine(domain: String): String =
    "$domain — an account whose address is on it is staff. Set where this deployment is " +
        "configured, not here: widening it from inside the app would quietly widen every " +
        "project's staff row."

/** The heading over the tier cards. */
const val TIERS_TITLE: String = "What each tier may do"

/** What the tier cards are, under their heading. */
const val TIERS_HINT: String =
    "Guests are not listed: a guest is the absence of an account, so there is nothing to " +
        "permit. Instance administrators and the owner may do both regardless — they are " +
        "senior to every tier here."

/** The label beside a tier's project-creation switch. */
const val TIER_CREATE_LABEL: String = "May create projects (becoming its owner)"

/** The label beside a tier's agent-access switch. */
const val TIER_AGENTS_LABEL: String = "Allow agent access (MCP) — permission only"

/** What "permission only" means, said once under the pair. */
const val TIER_AGENTS_HINT: String =
    "Each person still switches agent access on themselves, in the You tab. This decides " +
        "whether they may."

/** The heading over the new-project audience rows. */
const val NEW_PROJECT_TITLE: String = "What a new project starts with"

/**
 * The subtitle that keeps somebody from misreading the rows as live policy.
 *
 * It names LNL-194's default too, because "a new project admits nobody" is surprising
 * enough that an administrator who has just made one and found it empty will come looking
 * for the reason on this screen.
 */
const val NEW_PROJECT_HINT: String =
    "Copied into a project's own Access list when it is created, and never consulted again. " +
        "Editing this changes nothing about a project that already exists. Out of the box a " +
        "new project admits nobody; this is what changes that. Its creator owns it either way."

// ── People ──────────────────────────────────────────────────────────────────

/** What the People tab is, under its title. */
const val PEOPLE_TAB_LEAD: String =
    "Every account on this instance, and what each one reaches in each project."

/** The badge on an account nobody has ever signed into. */
const val NOT_SIGNED_IN_BADGE: String = "NOT SIGNED IN"

/**
 * What a never-signed-in row means, and what does *not* happen to it.
 *
 * Said on the row rather than in a tooltip, because the question it answers — "will this
 * expire?" — is the one somebody has when they see an address that has never arrived.
 * There is no cleanup job and no age warning anywhere in this design: the row is
 * somebody's deliberate act and only a person should remove it.
 */
const val NOT_SIGNED_IN_NOTE: String =
    "Added by somebody here; nobody has signed in to it yet. It does not expire and is never " +
        "swept — remove it by hand if it was a mistake."

/** The heading over one account's per-project rungs. */
const val PEOPLE_RIGHTS_TITLE: String = "In each project"

/**
 * What to say instead of a project table for somebody who runs the instance.
 *
 * Their rows would be a wall of "no access" that means the opposite of what it says:
 * `AccessControl` answers yes to an administrator before it looks at a single rung, so an
 * administrator holding nothing anywhere still reaches Owner everywhere. Rendering the
 * grants honestly and letting the reader draw the wrong conclusion is worse than not
 * rendering them.
 */
const val PEOPLE_RUNS_INSTANCE_NOTE: String =
    "Runs this instance, so reaches Owner on every project here. There is nothing per-project " +
        "to list."

/** Where a rung is granted, said where somebody would otherwise look for a control. */
const val PEOPLE_READ_ONLY_NOTE: String =
    "Read-only here. A rung is granted in that project's own Access section, which is the one " +
        "place it can be changed."

/** What to say when the directory has loaded but no account is picked yet. */
const val PEOPLE_NO_SELECTION: String = "Pick somebody to see what they reach."

// ── Instance ────────────────────────────────────────────────────────────────

/** The heading over the read-only deployment facts. */
const val DEPLOYMENT_TITLE: String = "This deployment"

/** Why the read-only facts are on screen at all. */
const val DEPLOYMENT_HINT: String =
    "Deploy-time configuration. No screen can change any of it — it is here because " +
        "\"why is this person a member?\" and \"why can I not pick that?\" are answered by it."

/** The heading over the instance-wide policy switches. */
const val POLICY_TITLE: String = "Policy"

/** The label beside the public-projects veto. */
const val ALLOW_PUBLIC_LABEL: String = "Allow projects to be public"

/**
 * What the veto does, and that it is off out of the box.
 *
 * Saying "off by default" is load-bearing: with it off every project's Guests row is
 * greyed, and an owner who finds that will otherwise file a bug about their own project
 * rather than come here.
 */
const val ALLOW_PUBLIC_HINT: String =
    "Off out of the box, so every project's Guests row starts greyed. While it is off no " +
        "project can admit people who are not signed in, whatever its owner sets."

/** The label beside the hide-display-name switch (LNL-137). */
const val HIDE_DISPLAY_NAME_LABEL: String = "Hide the display name override in profile settings"

/** What the hide-display-name switch does. */
const val HIDE_DISPLAY_NAME_HINT: String =
    "When on, nobody can override the name their sign-in provider gives; the field disappears " +
        "from everyone's You tab."

/** The heading over the project order. */
const val PROJECT_ORDER_TITLE: String = "Project order"

/**
 * Why the order lives here rather than on the Projects rail.
 *
 * The rail is per-caller — it lists the projects *you* hold something in — so it is
 * exactly the wrong place to set a fact that is the same for everybody.
 */
const val PROJECT_ORDER_HINT: String =
    "The order every picker and rail shows, for everybody. The Projects rail lists only what " +
        "you hold something in, so the order is set here instead. Use the arrows."

/** What to say instead of a list when there is nothing to arrange. */
const val PROJECT_ORDER_EMPTY: String = "No projects on this instance yet."

/** What to say when there is exactly one project and therefore no order. */
const val PROJECT_ORDER_SINGLE: String = "One project, so there is no order to arrange."

/** The heading over ownership. */
const val OWNERSHIP_TITLE: String = "Ownership"

/** The label on the transfer button. */
const val HAND_OVER_LABEL: String = "Hand over…"

/** The handover dialog's title. */
const val HAND_OVER_TITLE: String = "Hand over this instance"

/**
 * What the dialog leads with (LNL-198).
 *
 * The consequence before the control, deliberately. Every other dialog in the product
 * leads with what you are about to do; this one leads with what you are about to stop
 * being, because that is the half somebody looking for "how do I add another owner" has
 * not understood — there is no adding, there is only moving.
 */
const val HAND_OVER_LEAD: String =
    "You are the owner of this deployment. One account holds that at a time, so handing it " +
        "over means giving it up."

/**
 * The three things that change, listed rather than summarised.
 *
 * Listed because they are three different kinds of consequence and a paragraph would let a
 * reader carry away only the first: what they gain, what you keep, and what neither of you
 * can undo. The third is the one this whole ceremony exists for.
 */
val HAND_OVER_CONSEQUENCES: List<String> = listOf(
    "They get this instance's settings, every project on it, and the right to promote and " +
        "demote instance administrators.",
    "You become an instance administrator. Every project you own stays yours.",
    "Only they can hand it back. You will not be able to undo this yourself.",
)

/** The label over the successor picker. */
const val HAND_OVER_PICK_LABEL: String = "New owner"

/** What the picker will and will not offer, said beside it rather than discovered. */
const val HAND_OVER_PICK_HINT: String =
    "Staff who have signed in. A member cannot own this deployment, and neither can an address " +
        "somebody added that has never been signed in to."

/** What to show until somebody is picked, in place of the phrase field. */
const val HAND_OVER_PICK_FIRST: String = "Choose who gets it, and this will ask you to type a phrase."

/** The destructive button's label. Says what it does, as every confirmation here does. */
const val HAND_OVER_CONFIRM_LABEL: String = "Hand it over"

/**
 * The sentence somebody has to type to arm the handover.
 *
 * Naming the *successor* rather than a generic phrase, which is the one difference from
 * [DELETE_PROJECT_CONFIRMATION_PHRASE] worth having: the dangerous property here is not
 * that ownership moves, it is that it moves to a specific person and the mover cannot take
 * it back. A phrase that names them cannot be typed by somebody who has misread the picker.
 *
 * Matched case-insensitively and trimmed by [ConfirmDialog]'s rule, so it is a sentence to
 * mean rather than a spelling test — and the name is echoed for copying, so an accented or
 * non-Latin name is not a barrier.
 */
fun handOverPhrase(name: String): String = "hand over to $name"

/** The sentence over the phrase field, so the field is not an unexplained box. */
fun handOverPrompt(name: String): String =
    "This cannot be undone. Type the phrase below to hand this deployment to $name."

/** What to say when nobody owns this instance yet. */
const val NO_OWNER_LINE: String =
    "Nobody owns this instance yet. The first administrator to sign in is seated as its owner."

/** Who owns the place. Second person for the caller, because "Robert Söderbjörn owns this" reads oddly at yourself. */
fun ownerLine(name: String, email: String?, isSelf: Boolean): String {
    val who = if (isSelf) "You own this instance" else "$name owns this instance"
    val address = email?.let { " ($it)" }.orEmpty()
    return "$who$address."
}

/**
 * Who administers it alongside the owner, or that nobody does.
 *
 * Second person when the reader *is* the owner: "administered alongside them" read as being
 * about somebody else on the row that had just said "You own this instance". Found by
 * driving the app.
 */
fun adminsLine(names: List<String>, isOwnerSelf: Boolean): String = when {
    names.isEmpty() -> "No other account administers this instance."
    isOwnerSelf -> "Administered alongside you by ${names.joinToString(", ")}."
    else -> "Administered alongside them by ${names.joinToString(", ")}."
}

// ── Row types ───────────────────────────────────────────────────────────────

/**
 * One row in the People list.
 *
 * @property subtitle the e-mail, or a stand-in when there is none. Under the name rather
 *   than beside it because it is the disambiguator, not the identity — you read it only
 *   when two rows say the same thing.
 * @property badge a short word marking the row: "you", "admin", or both. Null for an
 *   ordinary account, which is most of them.
 * @property tierLabel where they stand on the instance ladder, as the server named it.
 * @property showsNotSignedIn whether the row wears [NOT_SIGNED_IN_BADGE].
 */
data class AdminUserRow(
    val userId: Long,
    val name: String,
    val subtitle: String,
    val badge: String?,
    val tierLabel: String,
    val showsNotSignedIn: Boolean,
    val isSelected: Boolean,
)

/**
 * One project, and the rung this account reaches in it (LNL-195).
 *
 * Replaces the seven-tick `RightState` grid: a person holds one rung per project, so the
 * answer is one rung and — where the two differ — where it comes from.
 *
 * @property rungLabel what they reach, or the words for reaching nothing. Never null, so
 *   the view never has to decide what a blank means.
 * @property isHeld whether they reach anything at all. What the row's colour keys off; the
 *   words carry it too, so the difference is not colour alone.
 * @property note where the rung comes from when it is not their own row — "the members row
 *   gives Contributor." — or null when the own row is the whole story.
 */
data class ProjectRightsRow(
    val projectId: Long,
    val projectName: String,
    val rungLabel: String,
    val isHeld: Boolean,
    val note: String?,
)

/**
 * The right-hand pane of People: one account, in full.
 *
 * @property runsInstanceNote [PEOPLE_RUNS_INSTANCE_NOTE] for an administrator or the
 *   owner, null otherwise. Carried rather than a boolean the view branches on, so the view
 *   keeps making no decisions.
 * @property agentLine what is true of this account's agent access, in a sentence. Both
 *   halves — the tier's permission and their own switch — because "permitted and they have
 *   not switched it on" is the single most likely support question this feature generates.
 *   There is **no switch** here: the permission is per tier, and the person's own answer is
 *   theirs to give.
 * @property projects every project, in the server's order. Empty when the instance has
 *   none, which is a real state on a fresh deployment and is why the view has a message
 *   for it.
 */
data class AdminUserDetail(
    val userId: Long,
    val name: String,
    val subtitle: String,
    val tierLabel: String,
    val notSignedInNote: String?,
    val runsInstanceNote: String?,
    val agentLine: String,
    val isRightsSectionShown: Boolean,
    val projects: List<ProjectRightsRow>,
)

/**
 * One row of the project order (LNL-93, moved to the Instance tab by LNL-195).
 *
 * @property canMoveUp false at the top of the list, and false while a write is in flight,
 *   so a double click cannot queue two reorders against one list. Same treatment the
 *   vocabulary arrows get — see EditProjectBackingViewModel.
 */
data class AdminProjectRow(
    val projectId: Long,
    val name: String,
    val namePrefix: String,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    /**
     * Whether Delete is live. False for an instance administrator who is not the owner:
     * disposing of somebody else's board is the owner's (LNL-191), and the row says so
     * rather than letting the click collect a 403.
     */
    val canDelete: Boolean,
)

/**
 * The pending "delete this project?" confirmation, while it is up.
 *
 * Carries the whole sentence rather than a boolean the view branches on, so the view keeps
 * making no decisions — the shape EditProjectBackingViewModel's confirmations use.
 */
data class PendingProjectDelete(
    val projectId: Long,
    val title: String,
    val message: String,
    /**
     * The phrase an administrator must type to arm Delete (LNL-107). The same safeguard
     * the project's own delete uses, on this copy of it as much as that one: the
     * destruction is identical, so the ceremony guarding it should be.
     */
    val confirmationPhrase: String,
)

/**
 * The pending "hand this instance over" dialog, while it is up (LNL-198).
 *
 * Carries every string and every row the dialog draws, so the view decides nothing — the
 * shape [PendingProjectDelete] uses, one rung more serious.
 *
 * What it deliberately does **not** carry is who is currently picked, or what has been
 * typed. Those live in the dialog for the length of one gesture and are reported once, on
 * confirm, exactly as `AddPersonDialog` handles its address and its rung. Holding them here
 * would mean a state emission per keystroke, and every emission repaints three tabs.
 *
 * @property candidates who it can be handed to, in the server's order. Empty is a real and
 *   expected state — see [emptyReason], which is what the dialog shows instead.
 * @property emptyReason why there is nobody, or null when there is somebody. Exactly one of
 *   this and a non-empty [candidates] is meaningful, so the dialog shows a picker or a
 *   sentence and never both.
 */
data class PendingHandOver(
    val title: String,
    val lead: String,
    val consequences: List<String>,
    val pickLabel: String,
    val pickHint: String,
    val candidates: List<OwnerCandidate>,
    val emptyReason: String?,
    val confirmLabel: String,
)

/**
 * Drives the three instance-wide tabs.
 *
 * @param storage the client's one seam onto the server; defaulted so this is testable
 *   without a browser.
 * @param scope collects nothing itself, but owns the writes; cancelled by the caller when
 *   the pane closes.
 * @param onInstanceSettingChanged run after an instance switch is successfully written.
 *   Some of these ride on [se.soderbjorn.lunicle.clientserver.SessionState] — the
 *   display-name gate (LNL-137) — which the running client fetched once at bootstrap;
 *   without this the change would not land until the next page load. The app wires it to
 *   the session view model's `reload`. Defaults to a no-op so tests need not care.
 */
class AdminSettingsBackingViewModel(
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onInstanceSettingChanged: () -> Unit = {},
) {
    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * @property settings the server's answer, or null before the first one lands. Null and
     *   "an instance with no accounts" are different states and the views say different
     *   things about them — which is why this is nullable rather than defaulting to an
     *   empty [AdminSettingsState].
     * @property selectedUserId whose detail is showing on People. Survives a write, because
     *   a write returns a whole new settings object and re-deriving the selection from
     *   scratch would bounce the pane back to the first row every time somebody flipped a
     *   switch.
     */
    data class State(
        val isLoaded: Boolean = false,
        val isBusy: Boolean = false,
        val settings: AdminSettingsState? = null,
        val selectedUserId: Long? = null,
        val errorMessage: String? = null,
        /**
         * Which project a delete is being confirmed for, or null. Held here rather than as a
         * captured lambda so the confirmation survives a re-render — the same reason
         * [selectedUserId] is state and not a field.
         */
        val pendingProjectDeleteId: Long? = null,
        /**
         * Whether the handover dialog is up (LNL-198).
         *
         * A flag rather than a captured lambda, for [pendingProjectDeleteId]'s reason: the
         * dialog has to survive the re-render every write on these tabs causes. It carries
         * no successor, because the successor is picked inside the dialog — see
         * [PendingHandOver].
         */
        val isHandingOver: Boolean = false,
        /**
         * Whether these tabs have reordered or deleted a project since the pane opened.
         *
         * Everything else on screen reads the project order and the very membership of the
         * list from a separate load, so a reorder or a delete here is not visible until that
         * reloads. The caller drives the reload from this. A switch, by contrast, changes
         * nothing any board draws.
         */
        val projectsChanged: Boolean = false,
    ) {
        // ── Who gets in ──────────────────────────────────────────────────────

        /** The admission choices, greyed and captioned exactly as the server sent them. */
        val admissionOptions: List<AdmissionOption> get() = settings?.admission?.options.orEmpty()

        /** Which one is stored — reported even when it is no longer selectable. */
        val selectedAdmission: AdmissionPolicy? get() = settings?.admission?.selected

        /**
         * The sentence under the admission choices, naming the doors this deployment has.
         *
         * Null until the first load: an empty ways-in list before the response lands would
         * read as "nobody can sign in", which is the most alarming thing this screen can say.
         */
        val waysInLine: String? get() = settings?.let { waysInLine(it.deployment.waysIn) }

        /**
         * The staff-domain line, or null on a deployment with no domain.
         *
         * Null hides the whole section rather than showing "(none)": there is no staff tier
         * to describe, and a row saying so would invite somebody to look for the field that
         * sets it. The Members card already says the tier does not exist here.
         */
        val staffDomainLine: String? get() = settings?.deployment?.staffDomain?.let { staffDomainLine(it) }

        /** One card per tier that exists here. See [TierCard]. */
        val tiers: List<TierCard> get() = settings?.tiers.orEmpty()

        /** The audience rows a new project starts with, in the server's order. */
        val newProjectAudiences: List<AudienceRow> get() = settings?.newProjectAudiences.orEmpty()

        /** The rung vocabulary every picker on these tabs renders against. */
        val rungs: List<RungOption> get() = settings?.rungs.orEmpty()

        // ── People ───────────────────────────────────────────────────────────

        /** The account list. */
        val users: List<AdminUserRow>
            get() = settings?.users.orEmpty().map { user ->
                AdminUserRow(
                    userId = user.userId,
                    name = user.name,
                    subtitle = user.subtitle,
                    // "you", and nothing else. It used to say "admin" too, which the tier
                    // stamp beside it now says better and at more resolution — an
                    // administrator and the owner are different rungs and the badge called
                    // both "admin". Two labels saying one thing also wrapped the row onto a
                    // second line. Found by driving the app.
                    badge = "you".takeIf { user.isSelf },
                    tierLabel = user.tierLabel,
                    showsNotSignedIn = !user.hasSignedIn,
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
                    tierLabel = user.tierLabel,
                    notSignedInNote = NOT_SIGNED_IN_NOTE.takeIf { !user.hasSignedIn },
                    runsInstanceNote = PEOPLE_RUNS_INSTANCE_NOTE.takeIf { user.isSysAdmin },
                    agentLine = agentLine(user),
                    /**
                     * False for somebody who runs the instance, and distinct from "[projects]
                     * happens to be empty": one means "this instance has no projects yet", the
                     * other "the question does not arise here". Both would otherwise render as
                     * an empty list, and the first has a message that would be a lie about the
                     * second.
                     */
                    isRightsSectionShown = !user.isSysAdmin,
                    projects = if (user.isSysAdmin) {
                        emptyList()
                    } else {
                        user.projects.map { project -> project.toRow(settings.rungs) }
                    },
                )
            }

        /**
         * What to show instead of a detail pane, or null when there is one.
         *
         * Three different nothings, and they say different things: still loading, an instance
         * with no accounts (which cannot happen — you are one — but is cheap to be honest
         * about), or nothing picked yet.
         */
        val detailPlaceholder: String?
            get() = when {
                detail != null -> null
                !isLoaded -> null
                users.isEmpty() -> "No accounts on this instance yet."
                else -> PEOPLE_NO_SELECTION
            }

        /**
         * What to say in place of the project table when there are no projects.
         *
         * Silent for somebody who runs the instance: their table is empty because the
         * question does not arise, not because the instance is empty, and saying "no projects
         * yet" beside a rail full of them would be plainly wrong.
         */
        val noProjectsMessage: String?
            get() = detail?.let {
                if (it.isRightsSectionShown && it.projects.isEmpty()) {
                    "No projects on this instance yet, so there is nothing to reach."
                } else {
                    null
                }
            }

        // ── Instance ─────────────────────────────────────────────────────────

        /** The read-only facts, as lines. Empty until the first load lands. */
        val deploymentFacts: List<Pair<String, String>>
            get() {
                val deployment = settings?.deployment ?: return emptyList()
                return listOf(
                    "Staff domain" to (deployment.staffDomain ?: "None — every account here is a member"),
                    "Ways in" to deployment.waysIn.joinToString(" · ").ifEmpty { "None — nobody can sign in" },
                    "Google account chooser" to (
                        deployment.googlePin?.let { "Pinned to $it — an account outside it is refused" }
                            ?: "Open — any Google account may sign in"
                        ),
                    "Branding" to (deployment.brandName ?: "None — the default look"),
                )
            }

        /** Whether projects may be published to the world. Off until the first load lands. */
        val allowPublicProjects: Boolean get() = settings?.allowPublicProjects == true

        /** Whether the display-name override is hidden. Off until the first load lands. */
        val hideDisplayName: Boolean get() = settings?.hideDisplayName == true

        /**
         * Whether the switches may be flipped.
         *
         * Off until the first state lands (there is nothing to toggle yet) and while a write
         * is in flight, so a double click cannot queue two intents against one switch — the
         * same treatment the project arrows get.
         */
        val areInstanceTogglesEnabled: Boolean get() = settings != null && !isBusy

        /** Who owns the place, or that nobody does. */
        val ownerLine: String?
            get() {
                val ownership = settings?.ownership ?: return null
                val name = ownership.ownerName ?: return NO_OWNER_LINE
                return ownerLine(name, ownership.ownerEmail, ownership.isOwnerSelf)
            }

        /** Who administers it alongside them. Null until the first load lands. */
        val adminsLine: String?
            get() = settings?.ownership?.let { adminsLine(it.adminNames, it.isOwnerSelf) }

        /** Whether the reader owns the instance — what decides whether Hand over… shows at all. */
        val isOwnerSelf: Boolean get() = settings?.ownership?.isOwnerSelf == true

        /** Whether Hand over… is live. The owner's, and true even when nobody is eligible. */
        val canHandOver: Boolean get() = settings?.ownership?.canHandOver == true

        /** Why it is dead, when it is. Beside the control rather than instead of it. */
        val handOverBlockedReason: String? get() = settings?.ownership?.handOverBlockedReason

        /**
         * The handover dialog to show, or null (LNL-198).
         *
         * Derived from [isHandingOver] and the state in hand, so the candidate list is
         * always the one the last response named — and so a dialog somehow left up after
         * ownership moved resolves to null on the next emission rather than offering a
         * gesture the caller can no longer make.
         */
        val pendingHandOver: PendingHandOver?
            get() {
                if (!isHandingOver) return null
                val ownership = settings?.ownership ?: return null
                if (!ownership.canHandOver) return null
                return PendingHandOver(
                    title = HAND_OVER_TITLE,
                    lead = HAND_OVER_LEAD,
                    consequences = HAND_OVER_CONSEQUENCES,
                    pickLabel = HAND_OVER_PICK_LABEL,
                    pickHint = HAND_OVER_PICK_HINT,
                    candidates = ownership.handOverCandidates,
                    // The server's sentence, which names this deployment's own domain or its
                    // absence. Never written here: the client does not know the domain and
                    // must not guess at why a list it was handed is empty.
                    emptyReason = ownership.handOverEmptyReason,
                    confirmLabel = HAND_OVER_CONFIRM_LABEL,
                )
            }

        /** Whether this caller may reorder or delete across every board. The owner's alone. */
        val canReorderProjects: Boolean get() = settings?.canReorderProjects == true

        /** Why not, when they may not — beside the dead arrows rather than instead of them. */
        val projectSetReadOnlyReason: String? get() = settings?.projectSetReadOnlyReason

        /**
         * The instance's projects, in the arranged order, each told whether it can still
         * move.
         *
         * The ends of the list have a dead arrow apiece, and every arrow is dead while a
         * write is in flight — a second move queued on top of an unsettled one would race the
         * reorder it is based on.
         */
        val projectRows: List<AdminProjectRow>
            get() {
                val projects = settings?.projects.orEmpty()
                return projects.mapIndexed { index, project ->
                    AdminProjectRow(
                        projectId = project.id,
                        name = project.name,
                        namePrefix = project.namePrefix,
                        canMoveUp = index > 0 && !isBusy && canReorderProjects,
                        canMoveDown = index < projects.lastIndex && !isBusy && canReorderProjects,
                        canDelete = !isBusy && canReorderProjects,
                    )
                }
            }

        /**
         * The line shown instead of an order, or null when there is one to arrange.
         *
         * Two different nothings: no projects at all, and exactly one — which has a list
         * worth seeing and no order worth setting, so its arrows would both be dead with no
         * explanation.
         */
        val projectOrderEmptyMessage: String?
            get() = when {
                !isLoaded -> null
                settings?.projects.orEmpty().isEmpty() -> PROJECT_ORDER_EMPTY
                settings?.projects.orEmpty().size == 1 -> PROJECT_ORDER_SINGLE
                else -> null
            }

        /**
         * The delete confirmation to show, or null.
         *
         * Derived from [pendingProjectDeleteId] and the current list so the name in the
         * sentence is always the project the id points at — and so a stale id, left over if
         * another administrator deleted the project first, simply resolves to null rather
         * than a dialog about nothing.
         */
        val pendingProjectDelete: PendingProjectDelete?
            get() {
                val id = pendingProjectDeleteId ?: return null
                val project = settings?.projects?.firstOrNull { it.id == id } ?: return null
                return PendingProjectDelete(
                    projectId = project.id,
                    title = "Delete ${project.name}?",
                    // The same weight the forum manager's delete carries, and heavier: a project
                    // takes its issues, forums, comments and every attached file with it, and
                    // there is no trash to fish any of it back from.
                    message = "Every issue, forum and file in \"${project.name}\" goes with it. " +
                        "This cannot be undone. Type the phrase below to confirm.",
                    confirmationPhrase = DELETE_PROJECT_CONFIRMATION_PHRASE,
                )
            }
    }

    /**
     * Fetch the directory.
     *
     * Called by the view after it mounts rather than from `init`, matching
     * [ConnectionsBackingViewModel.start]: the tabs are on screen before the request goes
     * out, so the empty state is a moment of a rendered pane rather than a moment of
     * nothing.
     */
    fun start() {
        scope.launch {
            runCatching { storage.adminSettings() }.fold(
                onSuccess = { settings ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isLoaded = true,
                        settings = settings,
                        // Land on the first row rather than on an empty pane. The list is
                        // sorted server-side, so this is stable between opens rather than
                        // whichever row the database happened to hand back first.
                        selectedUserId = _stateFlow.value.selectedUserId ?: settings.users.firstOrNull()?.userId,
                        errorMessage = null,
                    )
                },
                onFailure = { t ->
                    println("AdminSettings: load failed: ${t.message}")
                    _stateFlow.value = _stateFlow.value.copy(
                        isLoaded = true,
                        errorMessage = t.userMessage("Could not load the account directory."),
                    )
                },
            )
        }
    }

    /** A name in the People list was clicked. */
    fun onUserSelected(userId: Long) {
        _stateFlow.value = _stateFlow.value.copy(selectedUserId = userId)
    }

    /**
     * An admission choice was picked.
     *
     * Refused locally as well as at the route when the option is not selectable, so a click
     * on a greyed choice does nothing rather than making a request that 409s — and the
     * greying is the server's answer either way, never re-derived here.
     */
    fun onAdmissionPicked(policy: AdmissionPolicy) {
        val option = _stateFlow.value.admissionOptions.firstOrNull { it.policy == policy } ?: return
        if (!option.isSelectable) return
        write("Could not change who may have an account.") { storage.setAdmissionPolicy(policy) }
    }

    /**
     * An instance switch was flipped — a tier's permission, or a policy on the Instance tab.
     *
     * Names the switch and the desired state, not "toggle": the request carries both, so a
     * retry says the same thing. The write returns the whole refreshed settings, so every tab
     * re-renders from the server's answer and never patches its own copy.
     */
    fun onInstanceSettingToggled(key: InstanceSettingKey, isEnabled: Boolean) {
        // afterSuccess re-fetches the session: some of these gate a field the running client
        // drew from its bootstrap session snapshot (LNL-137's display-name override), so
        // without it the administrator who just flipped the switch keeps seeing the old
        // answer until they reload. See onInstanceSettingChanged.
        write("Could not change that setting.", afterSuccess = onInstanceSettingChanged) {
            storage.setInstanceSetting(key, isEnabled)
        }
    }

    /**
     * A new-project audience row was moved to a rung, or cleared.
     *
     * @param roleKey the rung's key, or null for "no access" — which is what a fresh
     *   instance has and is not the same as the lowest rung.
     */
    fun onNewProjectAudienceChanged(audienceKey: String, roleKey: String?) {
        val row = _stateFlow.value.newProjectAudiences.firstOrNull { it.key == audienceKey } ?: return
        // Refused here as well as at the route, so a click on a vetoed row does nothing
        // rather than making a request that 409s. Clearing a row is always allowed: a veto
        // stops a project starting out public, not an administrator undoing it.
        if (!row.isSelectable && roleKey != null) return
        write("Could not change what a new project starts with.") {
            storage.setNewProjectAudience(audienceKey, roleKey)
        }
    }

    /**
     * A project's up or down arrow was pressed on the Instance tab.
     *
     * Builds the whole new order and sends it — the server takes the list, not a "moved X"
     * delta, so this mirrors EditProjectBackingViewModel.onMoveVocabulary exactly. A move
     * against the top or bottom edge computes an out-of-range target and is dropped, which
     * is the arrow the row already disabled being clicked anyway.
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
     * Clears the pending id first so the confirmation closes at once rather than lingering
     * over the busy write. The id comes from state rather than the button, so a confirmation
     * left up while the list changed under it deletes what the sentence named or, if that
     * project is already gone, resolves to a no-op — see [State.pendingProjectDelete].
     */
    fun onDeleteProjectConfirmed() {
        val id = _stateFlow.value.pendingProjectDeleteId ?: return
        _stateFlow.value = _stateFlow.value.copy(pendingProjectDeleteId = null)
        write("Could not delete that project.", marksProjectsChanged = true) {
            storage.deleteProjectAsAdmin(id)
        }
    }

    /** Hand over… was pressed: raise the dialog (LNL-198). */
    fun onHandOverTapped() {
        // Refused locally as well as at the route, so a click on a control the caller may
        // not use does nothing rather than opening a dialog whose confirm would 403.
        if (!_stateFlow.value.canHandOver) return
        _stateFlow.value = _stateFlow.value.copy(isHandingOver = true)
    }

    /** The dialog was dismissed without handing anything over. */
    fun onHandOverCancelled() {
        _stateFlow.value = _stateFlow.value.copy(isHandingOver = false)
    }

    /**
     * The handover was confirmed, with the phrase typed and a successor chosen.
     *
     * Closes the dialog first so it does not linger over the busy write, and re-checks the
     * successor against the candidate list the last response carried: the dialog holds the
     * pick for the length of the gesture, and a list that moved underneath it (another
     * administrator deleting the account, say) would otherwise send an id the route has to
     * refuse. The route re-derives eligibility regardless — see AccessControl and
     * AdminRoutes — so this is the affordance, not the rule.
     */
    fun onHandOverConfirmed(userId: Long) {
        val state = _stateFlow.value
        if (!state.canHandOver) return
        if (state.settings?.ownership?.handOverCandidates.orEmpty().none { it.userId == userId }) return
        _stateFlow.value = state.copy(isHandingOver = false)
        write("Could not hand this instance over.") { storage.handOverInstance(userId) }
    }

    fun onErrorDismissed() {
        _stateFlow.value = _stateFlow.value.copy(errorMessage = null)
    }

    /**
     * Run one write, and take the whole new state back from it.
     *
     * The same helper [EditProjectBackingViewModel] uses, for the same reason: every write
     * here returns a full [AdminSettingsState], so there is nothing to merge and no chance
     * of a pane that agrees with the server about the row it just touched and disagrees
     * about the rest.
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
                        // Latched, never cleared: once a reorder or delete has happened, the
                        // list every other surface draws is stale until the reload on close,
                        // whatever else these tabs do afterwards.
                        projectsChanged = _stateFlow.value.projectsChanged || marksProjectsChanged,
                    )
                    // Only after the write landed and state is settled — a hook that re-reads
                    // server-side facts (the session) must not run against a change that failed.
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
 * An account with no e-mail receives no notifications, which is a thing administrators get
 * asked about — so the absence is written out rather than left as a blank line the reader
 * has to interpret.
 */
private val AdminUser.subtitle: String
    get() = email ?: "No e-mail address"

/**
 * Both halves of agent access, in one sentence.
 *
 * The permission is the tier's and the switch is the person's, and an administrator who
 * knows only one of the two cannot answer "why does their agent not work". No control here
 * — see [AdminUserDetail.agentLine].
 */
private fun agentLine(user: AdminUser): String = when {
    !user.isMcpAllowed ->
        "Agent access: their tier is not permitted it, so no agent can act as them. The tier " +
            "cards on Who gets in are what change that."
    user.isMcpEnabled -> "Agent access: permitted, and they have turned it on."
    else ->
        "Agent access: permitted, but they have not turned it on, so no agent can act as them " +
            "yet. Only they can."
}

/**
 * One project row, rendered against the server's rung vocabulary.
 *
 * The labels come from [rungs] rather than from a copy compiled into the bundle, for
 * [RungOption]'s reason: a rolled-back server's rungs must not be described with this
 * build's words. A key that resolves to nothing renders as its own key, which is a visible
 * oddity rather than a silent blank.
 */
private fun se.soderbjorn.lunicle.clientserver.AdminProjectRights.toRow(
    rungs: List<RungOption>,
): ProjectRightsRow {
    fun label(key: String?): String? = key?.let { k -> rungs.firstOrNull { it.key == k }?.label ?: k }
    val effective = label(effectiveRoleKey)
    return ProjectRightsRow(
        projectId = projectId,
        projectName = projectName,
        rungLabel = effective ?: "No access",
        isHeld = effective != null,
        // Where it comes from, when it is not their own row. The `max` rule made visible at
        // the one place somebody would otherwise be surprised by it — and the reason an
        // account with no own row anywhere can still reach every board.
        note = viaAudience?.let { via -> effective?.let { "$via gives $it." } },
    )
}
