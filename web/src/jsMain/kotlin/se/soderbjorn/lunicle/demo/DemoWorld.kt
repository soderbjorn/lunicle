/**
 * The in-memory world the browser demo (`?demo=1`, LNL-146) runs against.
 *
 * Lunicle's frontend is a pure renderer of server state — every screen is drawn
 * from a wire DTO the server computes. The demo re-implements that computation in
 * the browser: [DemoWorld] holds the *entities* (users, one or more projects with
 * their vocabularies, issues, comments, history, notifications) as plain mutable
 * objects, and the projection methods below recompute the wire DTOs from them on
 * every call, exactly as the server's routes do. [DemoLunicleApi] is the thin shell
 * that turns each API method into a mutation of this world followed by a projection.
 *
 * Nothing here persists. The world is built once by [seedDemoWorld] when the page
 * loads and lives only in memory, so a reload starts over from the seed — which is
 * the whole point of a demo you can poke at without consequence.
 *
 * The demo user (Captain Janeway) is the **instance owner** — the top of both ladders —
 * so nearly every affordance flag this file projects is simply `true`: that is what
 * surfaces the settings pane, the drag handles and the edit controls a visitor is meant
 * to explore. The real server would re-derive each of those from the session; here there
 * is one fixed session and it may do everything.
 *
 * ── What is derived rather than asserted, and why it matters (LNL-199) ───────
 *
 * "The visitor may do everything" is true and is not a licence to hard-code every answer.
 * Where a screen's whole point is to *show the rule*, the projection re-implements the
 * rule instead: [tierOf] and [permitsAgents] for the instance ladder, [audienceFloor] and
 * [higherRung] for a project's `max(audience row, own row)`. Two things fell out of
 * getting that wrong. The People tab reported every account with no own row as having no
 * access, on a board whose members row admits them; and the guest audience row could be
 * set while the switch that governs public projects read off. Both were a literal
 * standing in for a rule, and both looked fine until you read the screen beside the one
 * it contradicted.
 *
 * This deployment also names a staff domain ([DEMO_STAFF_DOMAIN]), which is what gives
 * the demo a Staff audience row, a Staff tier card and somebody eligible to be handed the
 * instance.
 *
 * @see DemoLunicleApi
 * @see se.soderbjorn.lunicle.clientserver.LunicleApi
 */
package se.soderbjorn.lunicle.demo

import kotlin.js.Date
import se.soderbjorn.lunicle.clientserver.AdminProjectRights
import se.soderbjorn.lunicle.clientserver.AdmissionOption
import se.soderbjorn.lunicle.clientserver.AdmissionPolicy
import se.soderbjorn.lunicle.clientserver.AdmissionState
import se.soderbjorn.lunicle.clientserver.AdminSettingsState
import se.soderbjorn.lunicle.clientserver.AdminUser
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.AudienceRow
import se.soderbjorn.lunicle.clientserver.BoardState
import se.soderbjorn.lunicle.clientserver.DeploymentFacts
import se.soderbjorn.lunicle.clientserver.InstanceOwnership
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
import se.soderbjorn.lunicle.clientserver.CommentView
import se.soderbjorn.lunicle.clientserver.IssueDetail
import se.soderbjorn.lunicle.clientserver.IssueEventKind
import se.soderbjorn.lunicle.clientserver.IssueEventView
import se.soderbjorn.lunicle.clientserver.IssueRef
import se.soderbjorn.lunicle.clientserver.IssueSummary
import se.soderbjorn.lunicle.clientserver.NotificationKind
import se.soderbjorn.lunicle.clientserver.NotificationListState
import se.soderbjorn.lunicle.clientserver.NotificationSummary
import se.soderbjorn.lunicle.clientserver.OwnerCandidate
import se.soderbjorn.lunicle.clientserver.ProjectPermissionsView
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.PersonRow
import se.soderbjorn.lunicle.clientserver.ProjectAccessState
import se.soderbjorn.lunicle.clientserver.ProjectSection
import se.soderbjorn.lunicle.clientserver.ProjectSectionKeys
import se.soderbjorn.lunicle.clientserver.RungOption
import se.soderbjorn.lunicle.clientserver.SessionState
import se.soderbjorn.lunicle.clientserver.SignedInUser
import se.soderbjorn.lunicle.clientserver.SprintItem
import se.soderbjorn.lunicle.clientserver.StatisticWindow
import se.soderbjorn.lunicle.clientserver.StatisticsState
import se.soderbjorn.lunicle.clientserver.ProjectStatistics
import se.soderbjorn.lunicle.clientserver.StatusItem
import se.soderbjorn.lunicle.clientserver.TierCard
import se.soderbjorn.lunicle.clientserver.TokenModes
import se.soderbjorn.lunicle.clientserver.UserOption
import se.soderbjorn.lunicle.clientserver.VocabularyEntry
import se.soderbjorn.lunicle.clientserver.VocabularyItem
import se.soderbjorn.lunicle.clientserver.VocabularyKind

// ── Rung vocabulary ─────────────────────────────────────────────────────────
//
// The server's `ProjectRole` enum lives in the JVM-only `:server` module, so the demo
// carries its own copy of the five rungs the Access section renders. These strings are
// wire format on the real server; here they are just what the pickers show. Declaration
// order is the ladder, weakest first — the same order the real rung menu draws.
//
// This replaced a set of seven privilege keys (LNL-194 following LNL-191): a person
// holds one rung per project now, not a subset of grants, so the demo's own model is a
// key per person per project rather than a set of them.

internal object DemoRungKeys {
    const val VIEWER = "viewer"
    const val CONTRIBUTOR = "contributor"
    const val MAINTAINER = "maintainer"
    const val ADMIN = "admin"
    const val OWNER = "owner"
}

/**
 * The rungs as the demo's pickers offer them — all selectable, because the demo visitor
 * owns every board and there is nobody senior to refuse them anything.
 */
internal val DEMO_RUNGS: List<RungOption> = listOf(
    RungOption(DemoRungKeys.VIEWER, "Viewer", "Read this project, without being able to change anything in it."),
    RungOption(DemoRungKeys.CONTRIBUTOR, "Contributor", "File issues, comment on them, and be assigned them."),
    RungOption(
        DemoRungKeys.MAINTAINER,
        "Maintainer",
        "Edit anyone's issue here, and manage the sprints and versions.",
    ),
    RungOption(
        DemoRungKeys.ADMIN,
        "Admin",
        "Administer this project: its vocabulary, its settings, deleting issues, and granting " +
            "roles up to maintainer.",
    ),
    RungOption(
        DemoRungKeys.OWNER,
        "Owner",
        "Own this project: everything an administrator can do, plus its name, its prefix, its " +
            "repository, its visibility, its deletion, and promoting administrators and owners.",
    ),
)

/** The rung label for a key, for the rows the demo renders. */
internal fun demoRungLabel(key: String): String =
    DEMO_RUNGS.firstOrNull { it.key == key }?.label ?: key

/** Where a rung sits on the ladder, so the demo can take the same max the server takes. */
internal fun demoRungRank(key: String?): Int =
    if (key == null) -1 else DEMO_RUNGS.indexOfFirst { it.key == key }

/** The senior of two rungs, either of which may be absent. */
internal fun higherRung(a: String?, b: String?): String? =
    if (demoRungRank(a) >= demoRungRank(b)) a else b

// ── The instance ladder ─────────────────────────────────────────────────────
//
// The demo's copy of `InstanceRole`, for the same reason it carries its own rungs: the
// server's enum is JVM-only. Only the four an account can be are here — guest is the
// absence of an account, and there is nothing in this world without one.

internal object DemoTierKeys {
    const val MEMBER = "member"
    const val STAFF = "staff"
    const val ADMIN = "admin"
    const val OWNER = "owner"
}

/** What the People tab calls a tier. One word where one will do, as the server does. */
internal fun demoTierLabel(key: String): String = when (key) {
    DemoTierKeys.MEMBER -> "Member"
    DemoTierKeys.STAFF -> "Staff"
    DemoTierKeys.ADMIN -> "Instance admin"
    DemoTierKeys.OWNER -> "Instance owner"
    else -> key
}

/** Ascending, so "does this account reach that audience" is one comparison. */
private val DEMO_TIER_ORDER: List<String> = listOf(
    DemoAudienceKeys.GUEST,
    DemoTierKeys.MEMBER,
    DemoTierKeys.STAFF,
    DemoTierKeys.ADMIN,
    DemoTierKeys.OWNER,
)

/** Does an account on [tier] reach an audience row written for [audience]? */
internal fun tierReaches(tier: String, audience: String): Boolean =
    DEMO_TIER_ORDER.indexOf(tier) >= DEMO_TIER_ORDER.indexOf(audience)

// ── Audiences, and the domain that makes the middle one real ────────────────

internal object DemoAudienceKeys {
    const val GUEST = "guest"
    const val MEMBER = "member"
    const val STAFF = "staff"
}

/**
 * The domain this demo deployment calls its own — which is what gives it a **staff**
 * tier at all (LNL-199).
 *
 * ── Why the demo names one, having not before ────────────────────────────────
 *
 * A deployment's staff domain is deploy-time configuration, read from `brand.json`, and
 * the demo is a bundle with no server to read one. So the demo world simply declares
 * what a `brand.json` would have said, and derives every account's tier from its address
 * exactly as `UserKind.forEmail` does.
 *
 * The alternative — leaving it unset, which is what LNL-198 found — is a coherent
 * deployment and a poor demo. With no domain there is no staff audience row, no staff
 * tier card and therefore nowhere to see the two per-tier switches, no "Staff domain"
 * fact on the Instance tab, no domain note in the add-a-person dialog, and nobody who
 * may be handed the deployment — so Hand over… opens on a sentence explaining why it
 * cannot be used. That is five screens a visitor cannot see, and all five are the half
 * of the model that distinguishes an instance's own people from everybody else.
 *
 * `voyager.starfleet` because it is already the crew's address (see DemoFixtures), so
 * the split the staff tier draws lands where the story already has a seam: the ship's
 * crew are staff, and the two later projects' people — `kilobyteklinik.se`,
 * `meridian.dev` and the outside contributors on `example.com` — are members. A domain
 * matching nobody would have been a card over an empty set, which is the thing the
 * server refuses to draw.
 */
internal const val DEMO_STAFF_DOMAIN: String = "voyager.starfleet"

// ── Entities ────────────────────────────────────────────────────────────────

/**
 * An account.
 *
 * @property isSysAdmin whether this account holds the instance-**administrator** rung.
 *   Never the owner rung: ownership is [DemoWorld.ownerUserId], a seat rather than a flag,
 *   for the reason the real `users.instance_role` cannot hold it either. Ask
 *   [DemoWorld.tierOf] for the whole answer.
 * @property hasSignedIn whether anybody has ever signed into it (LNL-194). False is a
 *   **placeholder**: a row an administrator created by typing an address, holding a rung
 *   that nobody has collected yet. The Access list badges those NOT SIGNED IN, and the
 *   demo keeps a couple so the badge means something — it read as the default when every
 *   account but the visitor's carried it.
 */
internal class DemoUser(
    val id: Long,
    var name: String,
    val email: String?,
    val provider: AuthProvider,
    val isSysAdmin: Boolean = false,
    val hasSignedIn: Boolean = true,
) {
    /**
     * Where this account stands on the instance ladder, ownership aside.
     *
     * The demo's copy of the server's `storedInstanceRole`, and it stops short of Owner
     * for the same reason that one does — no account carries ownership, so nothing derived
     * from an account alone can report it.
     *
     * The staff/member half is derived from the address against [DEMO_STAFF_DOMAIN] rather
     * than stored per fixture, which is the rule the real server applies: `users.kind` is
     * re-derived from one function on every boot and is never written by hand, so storing
     * it here would be the one place the demo and the rule could disagree.
     */
    val tier: String
        get() = when {
            isSysAdmin -> DemoTierKeys.ADMIN
            isStaff -> DemoTierKeys.STAFF
            else -> DemoTierKeys.MEMBER
        }

    /**
     * Whether this account is on the deployment's own domain.
     *
     * Purely a fact about the address, and deliberately **independent of [tier]** — the
     * server's `users.kind` is too. An owner from outside the domain is a member who runs
     * the place, and the You tab says both things separately for exactly that reason.
     */
    val isStaff: Boolean
        get() = email?.substringAfterLast('@')?.lowercase() == DEMO_STAFF_DOMAIN
}

/**
 * A status, priority or resolution — all three are an id, a name and an order, so
 * all three ride on this, mirroring the wire's [StatusItem]. [requiresResolution]
 * is meaningful only for a status, [isDone] only for a resolution.
 */
internal class DemoStatus(
    val id: Long,
    var name: String,
    var position: Int,
    var requiresResolution: Boolean = false,
    var isDone: Boolean = false,
)

/** A label, component or version: an id, a name and an order. */
internal class DemoNamed(
    val id: Long,
    var name: String,
    var position: Int,
)

/** A timebox. [completedAt] null means still open. */
internal class DemoSprint(
    val id: Long,
    var name: String,
    var position: Int,
    var completedAt: Long? = null,
)

/** One issue. Mutable, because the demo edits it in place. */
internal class DemoIssue(
    val id: Long,
    val number: Long,
    var title: String,
    var description: String,
    var statusId: Long,
    var priorityId: Long,
    var resolutionId: Long? = null,
    val labelIds: MutableList<Long> = mutableListOf(),
    val componentIds: MutableList<Long> = mutableListOf(),
    var authorId: Long? = null,
    var agentName: String? = null,
    var assigneeId: Long? = null,
    var sprintId: Long? = null,
    var parentId: Long? = null,
    var plannedVersionId: Long? = null,
    var fixedVersionId: Long? = null,
    var isDraft: Boolean = false,
    var createdAt: Long = 0,
    var updatedAt: Long = 0,
    var notify: Boolean = false,
    // Order within a board group (status × priority). Reassigned on drag.
    var sortIndex: Double = 0.0,
    // Order among an epic's children (LNL-55). Reassigned on reorder.
    var childIndex: Double = 0.0,
)

/** A comment. A blank body is an unpublished draft and is never projected. */
internal class DemoComment(
    val id: Long,
    val issueId: Long,
    var body: String,
    var authorId: Long? = null,
    var agentName: String? = null,
    var createdAt: Long = 0,
)

/** One thing that happened to an issue, for its history sidebar. */
internal class DemoEvent(
    val id: Long,
    val issueId: Long,
    val kind: IssueEventKind,
    val value: String? = null,
    val values: List<String> = emptyList(),
    val authorId: Long? = null,
    val agentName: String? = null,
    val createdAt: Long = 0,
)

/** One stored notification, for the bell and its panel. */
internal class DemoNotification(
    val id: Long,
    val kind: NotificationKind,
    val title: String,
    val createdAt: Long,
    var isRead: Boolean = false,
    val projectId: Long? = null,
    val issueId: Long? = null,
)

/**
 * One project, with everything scoped to it — its vocabularies, its issues and
 * their comments and history, its sprints, and who holds what in it.
 */
internal class DemoProject(
    val id: Long,
    var name: String,
    var prefix: String,
    // isPublic / visibleToAllSignedIn were here and are gone (LNL-194) — visibility is
    // audience rows now, and the demo grants the visitor Owner on everything anyway.
    var discussionsEnabled: Boolean = false,
    var messagesEnabled: Boolean = false,
    var requireLabel: Boolean = false,
    var requireComponent: Boolean = false,
    var requireFixedVersionOnResolve: Boolean = false,
    var showIssueAuthor: Boolean = false,
    var hideIssueNumbers: Boolean = false,
    var notifyOnNewIssue: Boolean = false,
    val statuses: MutableList<DemoStatus> = mutableListOf(),
    val priorities: MutableList<DemoStatus> = mutableListOf(),
    val resolutions: MutableList<DemoStatus> = mutableListOf(),
    val labels: MutableList<DemoNamed> = mutableListOf(),
    val components: MutableList<DemoNamed> = mutableListOf(),
    val versions: MutableList<DemoNamed> = mutableListOf(),
    val sprints: MutableList<DemoSprint> = mutableListOf(),
    var activeSprintId: Long? = null,
    val issues: MutableList<DemoIssue> = mutableListOf(),
    val comments: MutableList<DemoComment> = mutableListOf(),
    val events: MutableList<DemoEvent> = mutableListOf(),
    var nextNumber: Long = 1,
    // userId -> the role keys they hold here.
    /**
     * Who holds what here: one rung per person, by key (LNL-194).
     *
     * A map to a single key rather than to a set of privilege keys, because that is what
     * the real model is now — see [DemoRungKeys].
     */
    val members: MutableMap<Long, String> = mutableMapOf(),
    /**
     * What each audience arrives as, by audience key, or absent for "no access"
     * (LNL-194). Seeded to admit members as viewers, so the demo's Access section opens
     * with something to look at rather than an empty board nobody can see.
     *
     * A board created *in* the demo takes the instance's own new-project list instead —
     * see [provisionProject], which passes it explicitly rather than inheriting this.
     */
    val audiences: MutableMap<String, String> = mutableMapOf(DemoAudienceKeys.MEMBER to DemoRungKeys.VIEWER),
)

// ── The world ───────────────────────────────────────────────────────────────

/**
 * The whole in-memory world, plus the projections that turn it into wire DTOs.
 *
 * Instantiated once via [seedDemoWorld]. Everything mutable is a `var` or a
 * `MutableList`, mutated directly by [DemoLunicleApi]; ids come from a single
 * [allocId] counter so nothing ever collides.
 */
internal class DemoWorld {
    /** When the page opened, the anchor every fixture timestamp is relative to. */
    val startedAt: Long = Date().getTime().toLong()

    val users: MutableList<DemoUser> = mutableListOf()
    val projects: MutableList<DemoProject> = mutableListOf()
    val notifications: MutableList<DemoNotification> = mutableListOf()

    /** Toolkit theme blobs, stored in-session so a theme change sticks until reload. */
    val uiSettings: MutableMap<String, String> = mutableMapOf()

    var allowPublicProjects: Boolean = false
    var staffMayCreateProjects: Boolean = false
    var memberMayCreateProjects: Boolean = false
    var staffMayUseAgents: Boolean = false
    var memberMayUseAgents: Boolean = false
    var admission: AdmissionPolicy = AdmissionPolicy.ANYONE
    var hideDisplayName: Boolean = false

    /**
     * Whether the visitor has switched agent access on for their own account (LNL-199).
     *
     * The person's own answer, and only half of it: [permitsAgents] is the other, and both
     * have to be true for an agent to connect. Kept in the world rather than returned
     * fixed, so the switch on the You tab actually moves and the People tab's MCP column
     * follows it.
     *
     * Starts off. Somebody who has never asked for agent access has not got it, and the
     * interesting thing to show is the switch working rather than a connection already
     * there.
     */
    var mcpEnabled: Boolean = false

    /**
     * What a new project starts out admitting, by audience key (LNL-195).
     *
     * Empty, like a real fresh instance: out of the box a new project admits nobody. The
     * demo never creates one, so this is only ever the setting being looked at and moved.
     */
    val newProjectAudiences: MutableMap<String, String> = mutableMapOf()

    var demoUserId: Long = 0

    /**
     * Which account owns the deployment (LNL-199).
     *
     * A field of its own rather than a flag on the account, because that is the shape the
     * real model has: ownership is one setting naming one id, so that "exactly one owner"
     * needs no constraint to enforce. It also makes handing the instance over a single
     * assignment here — see [DemoLunicleApi.handOverInstance], which could do nothing at
     * all while ownership was a `val` on [DemoUser].
     *
     * Seeded to the visitor. A hand-over moves it, and the visitor keeps the account they
     * signed in as — so the demo can show what the screen looks like from the other side
     * of a transfer, which is the one thing that screen is for.
     */
    var ownerUserId: Long = 0

    /** Is [user] the one account that owns this deployment? */
    fun owns(user: DemoUser): Boolean = user.id == ownerUserId

    /**
     * Where [user] stands, ownership included.
     *
     * [DemoUser.tier] cannot answer this on its own for the same reason the server's
     * `storedInstanceRole` cannot: ownership is a setting, so no account carries it.
     */
    fun tierOf(user: DemoUser): String =
        if (owns(user)) DemoTierKeys.OWNER else user.tier

    /**
     * May an account on this tier hold agent access at all?
     *
     * The per-tier rule LNL-192 introduced, mirroring `InstanceSettings.permitsAgents`:
     * administrators and the owner always, staff and members only where the tier card's
     * switch is on. This is what the You tab's greyed switch and the People tab's
     * read-only "MCP allowed" column both report, and having one function answer both is
     * what keeps them from disagreeing.
     */
    fun permitsAgents(tier: String): Boolean = when (tier) {
        DemoTierKeys.OWNER, DemoTierKeys.ADMIN -> true
        DemoTierKeys.STAFF -> staffMayUseAgents
        else -> memberMayUseAgents
    }

    /**
     * May somebody be handed this deployment?
     *
     * The server's `mayBeHandedTheInstance`: staff, who have actually signed in, and not
     * whoever is asking. The signed-in half is the interesting one — an address typed into
     * an Access list and never claimed cannot be handed a deployment, so the demo's
     * placeholder accounts are legitimately absent from the picker.
     */
    fun mayBeHandedTheInstance(user: DemoUser): Boolean =
        user.id != ownerUserId && user.isStaff && user.hasSignedIn

    private var nextId: Long = 1

    /** A fresh id, unique across every kind of entity in the world. */
    fun allocId(): Long = nextId++

    /** Milliseconds now, so mutations date themselves. */
    fun now(): Long = Date().getTime().toLong()

    // ── Lookups ──────────────────────────────────────────────────────────────

    val demoUser: DemoUser get() = users.first { it.id == demoUserId }

    fun userName(id: Long?): String? = id?.let { uid -> users.firstOrNull { it.id == uid }?.name }

    fun projectById(id: Long): DemoProject? = projects.firstOrNull { it.id == id }

    /** The project and issue for an issue id, or null if it is gone. */
    fun locateIssue(issueId: Long): Pair<DemoProject, DemoIssue>? {
        for (p in projects) {
            val issue = p.issues.firstOrNull { it.id == issueId }
            if (issue != null) return p to issue
        }
        return null
    }

    fun locateComment(commentId: Long): Pair<DemoProject, DemoComment>? {
        for (p in projects) {
            val c = p.comments.firstOrNull { it.id == commentId }
            if (c != null) return p to c
        }
        return null
    }

    // ── Session ──────────────────────────────────────────────────────────────

    fun sessionState(): SessionState {
        val u = demoUser
        return SessionState(
            user = SignedInUser(
                id = u.id,
                displayName = u.name,
                provider = u.provider,
                // "Runs this instance", which is the administrator rung OR the owner seat
                // above it — one flag on the wire for both, so it is asked of the ladder
                // rather than of the account. That is what keeps the instance tabs where
                // they should be after a hand-over: the outgoing owner is left an
                // administrator, exactly as the server's route leaves them, so the tabs
                // stay and only the owner-only controls go.
                isSysAdmin = tierReaches(tierOf(u), DemoTierKeys.ADMIN),
                hasDisplayNameOverride = false,
                email = u.email,
                isEmailVerified = true,
                // Their address is on the deployment's own domain (LNL-199), so the You
                // tab reads "Staff on this instance." beside "You administer this
                // instance." Two separate sentences about two separate things — the
                // account's tier and whether it runs the place — and the demo can now
                // show both being true at once, which is the ordinary case for the person
                // who set a deployment up.
                isStaff = u.isStaff,
            ),
            // No provider is configured in the demo, and none needs to be: the
            // session is already signed in, so the sign-in affordances never show.
            isGoogleAvailable = false,
            googleClientId = null,
            isImpersonating = false,
            // Fixed-account rule (LNL-146): impersonation stays off so the menu is
            // hidden, even though the demo user is an administrator.
            canImpersonate = false,
            impersonatableAddresses = emptyList(),
            pendingEmail = null,
            isEmailSignInAvailable = false,
            isSignInRequired = false,
            isDisplayNameHidden = hideDisplayName,
        )
    }

    // ── Projects and the board ────────────────────────────────────────────────

    fun projectSummary(p: DemoProject): ProjectSummary = ProjectSummary(
        id = p.id,
        name = p.name,
        namePrefix = p.prefix,
        // The visitor's own rung on this board. Asked rather than asserted, so that the
        // sidebar and the Access section cannot drift apart — and so that a hand-over
        // shows up here too: an administrator still reaches Owner everywhere, but the
        // answer now comes from the ladder rather than from a literal.
        roleKey = visitorRung(p),
        roleLabel = demoRungLabel(visitorRung(p)),
        discussionsEnabled = p.discussionsEnabled,
        messagesEnabled = p.messagesEnabled,
        requireLabel = p.requireLabel,
        requireComponent = p.requireComponent,
        requireFixedVersionOnResolve = p.requireFixedVersionOnResolve,
        showIssueAuthor = p.showIssueAuthor,
        hideIssueNumbers = p.hideIssueNumbers,
    )

    /**
     * What the visitor effectively holds on [p].
     *
     * The whole rule in one line, and it is the server's: an instance administrator or the
     * owner reaches Owner on every board without a row, and anybody else gets the max of
     * their audience and their own row. The visitor is at the top of the instance ladder,
     * so this answers Owner — but it answers it by asking, which is what makes the answer
     * still correct if the demo ever seats somebody else.
     */
    private fun visitorRung(p: DemoProject): String {
        val me = demoUser
        if (tierReaches(tierOf(me), DemoTierKeys.ADMIN)) return DemoRungKeys.OWNER
        return higherRung(p.members[me.id], audienceFloor(p, tierOf(me))?.second)
            ?: DemoRungKeys.VIEWER
    }

    private fun statusItem(s: DemoStatus): StatusItem =
        StatusItem(s.id, s.name, s.position, s.requiresResolution, s.isDone)

    private fun vocabItem(v: DemoNamed): VocabularyItem = VocabularyItem(v.id, v.name)

    private fun sprintItem(s: DemoSprint): SprintItem = SprintItem(s.id, s.name, s.position, s.completedAt)

    /** Published issues, ordered the way the board groups them: status, priority, then drag order. */
    private fun orderedIssues(p: DemoProject): List<DemoIssue> {
        val statusPos = p.statuses.associate { it.id to it.position }
        val priorityPos = p.priorities.associate { it.id to it.position }
        return p.issues.filter { !it.isDraft }.sortedWith(
            compareBy(
                { statusPos[it.statusId] ?: Int.MAX_VALUE },
                { priorityPos[it.priorityId] ?: Int.MAX_VALUE },
                { it.sortIndex },
            ),
        )
    }

    fun issueSummary(issue: DemoIssue): IssueSummary = IssueSummary(
        id = issue.id,
        number = issue.number,
        title = issue.title,
        statusId = issue.statusId,
        priorityId = issue.priorityId,
        resolutionId = issue.resolutionId,
        labelIds = issue.labelIds.toList(),
        componentIds = issue.componentIds.toList(),
        authorName = userName(issue.authorId),
        agentName = issue.agentName,
        createdAt = issue.createdAt,
        updatedAt = issue.updatedAt,
        canEdit = true,
        sprintId = issue.sprintId,
        parentId = issue.parentId,
    )

    fun boardState(p: DemoProject): BoardState = BoardState(
        project = projectSummary(p),
        statuses = p.statuses.sortedBy { it.position }.map(::statusItem),
        priorities = p.priorities.sortedBy { it.position }.map(::statusItem),
        resolutions = p.resolutions.sortedBy { it.position }.map(::statusItem),
        labels = p.labels.sortedBy { it.position }.map(::vocabItem),
        components = p.components.sortedBy { it.position }.map(::vocabItem),
        sprints = p.sprints.sortedBy { it.position }.map(::sprintItem),
        activeSprintId = p.activeSprintId,
        versions = p.versions.sortedBy { it.position }.map(::vocabItem),
        issues = orderedIssues(p).map(::issueSummary),
        // Owner + sysadmin: every affordance is offered. See the file preamble.
        permissions = ProjectPermissionsView(
            canCreateIssue = true,
            canComment = true,
            canChangeUnownedIssues = true,
            canMutateProject = true,
            canMutateProjectIdentity = true,
            canBeAssigned = true,
        ),
    )

    // ── Issue detail ──────────────────────────────────────────────────────────

    fun issueRef(issue: DemoIssue): IssueRef = IssueRef(
        id = issue.id,
        number = issue.number,
        title = issue.title,
        statusId = issue.statusId,
        resolutionId = issue.resolutionId,
        canEdit = true,
    )

    fun commentView(c: DemoComment): CommentView = CommentView(
        id = c.id,
        body = c.body,
        authorName = userName(c.authorId),
        agentName = c.agentName,
        createdAt = c.createdAt,
        canEdit = true,
    )

    fun eventView(e: DemoEvent): IssueEventView = IssueEventView(
        id = e.id,
        kind = e.kind,
        value = e.value,
        values = e.values,
        authorName = userName(e.authorId),
        agentName = e.agentName,
        createdAt = e.createdAt,
    )

    /** Everyone who may be assigned or @mentioned — the whole crew. */
    fun crewOptions(): List<UserOption> =
        users.map { UserOption(id = it.id, name = it.name, isSelf = it.id == demoUserId) }

    fun issueDetail(p: DemoProject, issue: DemoIssue): IssueDetail {
        val children = p.issues.filter { it.parentId == issue.id }.sortedBy { it.childIndex }
        val parent = issue.parentId?.let { pid -> p.issues.firstOrNull { it.id == pid } }
        // The parent picker / add-child candidates: every published issue; the
        // client filters per gesture, and the demo user may link anything.
        val linkable = p.issues.filter { !it.isDraft && it.id != issue.id }
        // Open sprints, plus the one the issue is currently in even if completed.
        val offerableSprints = p.sprints
            .filter { it.completedAt == null || it.id == issue.sprintId }
            .sortedBy { it.position }
            .map(::sprintItem)
        return IssueDetail(
            id = issue.id,
            projectId = p.id,
            number = issue.number,
            title = issue.title,
            description = issue.description,
            statusId = issue.statusId,
            priorityId = issue.priorityId,
            resolutionId = issue.resolutionId,
            isDraft = issue.isDraft,
            labelIds = issue.labelIds.toList(),
            componentIds = issue.componentIds.toList(),
            authorName = userName(issue.authorId),
            agentName = issue.agentName,
            createdAt = issue.createdAt,
            updatedAt = issue.updatedAt,
            comments = p.comments
                .filter { it.issueId == issue.id && it.body.isNotBlank() }
                .sortedBy { it.createdAt }
                .map(::commentView),
            history = p.events
                .filter { it.issueId == issue.id }
                .sortedBy { it.createdAt }
                .map(::eventView),
            canEdit = true,
            canDelete = true,
            canComment = true,
            notifyOnUpdates = issue.notify,
            canReceiveEmailNotifications = true,
            watchers = if (issue.notify) listOf(demoUser.name) else emptyList(),
            assigneeId = issue.assigneeId,
            assigneeName = userName(issue.assigneeId),
            assignableUsers = crewOptions(),
            mentionableUsers = crewOptions(),
            canBeAssigned = true,
            callerId = demoUserId,
            sprintId = issue.sprintId,
            sprints = offerableSprints,
            plannedVersionId = issue.plannedVersionId,
            fixedVersionId = issue.fixedVersionId,
            versions = p.versions.sortedBy { it.position }.map(::vocabItem),
            parentId = issue.parentId,
            parent = parent?.let(::issueRef),
            children = children.map(::issueRef),
            linkableIssues = linkable.map(::issueRef),
        )
    }

    // ── Project settings ──────────────────────────────────────────────────────

    // Drafts left out, as the server leaves them out (LNL-183): these numbers are
    // what the settings dialog shows beside a Delete it will not offer, and a
    // count that included the half-written issue in the demo's first column would
    // explain a greyed-out button with an issue that is on nobody's board.
    private fun usageOfStatus(p: DemoProject, id: Long) = p.published.count { it.statusId == id }
    private fun usageOfPriority(p: DemoProject, id: Long) = p.published.count { it.priorityId == id }
    private fun usageOfResolution(p: DemoProject, id: Long) = p.published.count { it.resolutionId == id }
    private fun usageOfLabel(p: DemoProject, id: Long) = p.published.count { id in it.labelIds }
    private fun usageOfComponent(p: DemoProject, id: Long) = p.published.count { id in it.componentIds }
    private fun usageOfSprint(p: DemoProject, id: Long) = p.published.count { it.sprintId == id }
    private fun usageOfVersion(p: DemoProject, id: Long) =
        p.published.count { it.plannedVersionId == id || it.fixedVersionId == id }

    private val DemoProject.published: List<DemoIssue> get() = issues.filter { !it.isDraft }

    private fun statusEntry(p: DemoProject, s: DemoStatus, usage: Int) =
        VocabularyEntry(s.id, s.name, s.position, s.requiresResolution, s.isDone, usage)

    private fun namedEntry(v: DemoNamed, usage: Int) =
        VocabularyEntry(v.id, v.name, v.position, usageCount = usage)

    /**
     * A sprint row, with the two fields only a sprint carries (LNL-196): when it was
     * completed, and how many of its issues are not in a closing column — which is what
     * the Sprints section shows beside each row and what its completion confirmation
     * counts.
     */
    private fun sprintEntry(p: DemoProject, s: DemoSprint, usage: Int): VocabularyEntry {
        val closing = p.statuses.filter { it.requiresResolution }.map { it.id }.toSet()
        return VocabularyEntry(
            s.id,
            s.name,
            s.position,
            usageCount = usage,
            completedAt = s.completedAt,
            unfinishedCount = p.published.count { it.sprintId == s.id && it.statusId !in closing },
        )
    }

    /**
     * Who this project admits, as the demo's Access section (LNL-194).
     *
     * Audience rows come from [DemoProject.audiences]; person rows are the exceptions —
     * exactly the accounts with a rung in [DemoProject.members], which is what the real
     * server sends. The visitor may grant anything, being the owner of every demo board.
     */
    fun projectAccessState(p: DemoProject): ProjectAccessState = ProjectAccessState(
        // All three rows, because this deployment names a domain (LNL-199) and so has a
        // staff tier for the middle one to mean something. The server drops the staff row
        // where there is no domain — a control that cannot do anything is worse than one
        // row fewer — and the demo used to be that case.
        audiences = listOf(
            AudienceRow(
                DemoAudienceKeys.GUEST,
                "Guests",
                "Anybody at all, without signing in.",
                p.audiences[DemoAudienceKeys.GUEST],
                // The publish veto, which the demo now honours here as well as on the
                // instance's own default list. It applies to the guest row whoever asks,
                // the owner included, so a demo that let the click through would be
                // teaching a rule the server does not have — and would leave a board
                // public while the switch that governs it reads off.
                isSelectable = allowPublicProjects,
                unavailableReason = if (allowPublicProjects) {
                    null
                } else {
                    "This deployment does not allow a project to be made public. An " +
                        "instance administrator decides that, in the instance settings."
                },
            ),
            AudienceRow(
                DemoAudienceKeys.MEMBER,
                "Members",
                "Everybody with an account on this deployment.",
                p.audiences[DemoAudienceKeys.MEMBER],
            ),
            AudienceRow(
                DemoAudienceKeys.STAFF,
                "Staff",
                "Accounts on $DEMO_STAFF_DOMAIN.",
                p.audiences[DemoAudienceKeys.STAFF],
            ),
        ),
        // The exceptions: everybody holding something other than what their audience gives
        // them, plus whoever runs the instance — who reaches Owner everywhere without a row
        // and is listed so the audit is not silently missing them.
        people = users.filter { it.id in p.members.keys || it.id == ownerUserId }.map { u ->
            val own = p.members[u.id]
            val floor = audienceFloor(p, tierOf(u))
            val runsInstance = owns(u)
            val effective = if (runsInstance) DemoRungKeys.OWNER else higherRung(own, floor?.second)
            PersonRow(
                userId = u.id,
                name = u.name,
                email = u.email.orEmpty(),
                roleKey = own,
                // Only where the audience is actually carrying some of the weight.
                // Somebody whose own row already outranks their audience is effectively
                // their own row, and saying so restates the picker beside it.
                effectiveLine = when {
                    runsInstance -> null
                    floor == null -> null
                    demoRungRank(floor.second) < demoRungRank(own) -> null
                    else -> "The ${audienceTitle(floor.first).lowercase()} row here already gives " +
                        "${demoRungLabel(floor.second)}, so this person is effectively " +
                        "${demoRungLabel(effective ?: floor.second)}."
                },
                hasSignedIn = u.hasSignedIn,
                isSelf = u.id == demoUserId,
                // An instance owner's rung here is not stored and cannot be lowered from
                // this screen.
                isEditable = !runsInstance,
                note = "Owns this instance, so holds Owner on every project here."
                    .takeIf { runsInstance },
            )
        },
        rungs = DEMO_RUNGS,
        canGrant = true,
        addressAdvice = "Nothing is sent. The address gets an account that can hold a role straight " +
            "away, and whoever owns it picks the role up the first time they sign in.",
        // What the add-a-person dialog notes beside an address from somewhere else, so
        // that adding an outsider is a visible decision rather than a typo.
        staffDomain = DEMO_STAFF_DOMAIN,
    )

    /**
     * The best rung [tier] gets from [p]'s audience rows, with the audience it came from.
     *
     * One comparison per row, by the rule `AccessControl.effectiveRole` uses: the instance
     * ladder ascends, so "matches this audience" is "their tier reaches it".
     */
    private fun audienceFloor(p: DemoProject, tier: String): Pair<String, String>? =
        p.audiences.entries
            .filter { tierReaches(tier, it.key) }
            .maxByOrNull { demoRungRank(it.value) }
            ?.let { it.key to it.value }

    /** What to call an audience on screen. */
    private fun audienceTitle(key: String): String = when (key) {
        DemoAudienceKeys.GUEST -> "Guests"
        DemoAudienceKeys.MEMBER -> "Members"
        DemoAudienceKeys.STAFF -> "Staff"
        else -> key
    }

    fun projectSettingsState(p: DemoProject): ProjectSettingsState = ProjectSettingsState(
        labels = p.labels.sortedBy { it.position }.map { namedEntry(it, usageOfLabel(p, it.id)) },
        components = p.components.sortedBy { it.position }.map { namedEntry(it, usageOfComponent(p, it.id)) },
        statuses = p.statuses.sortedBy { it.position }.map { statusEntry(p, it, usageOfStatus(p, it.id)) },
        priorities = p.priorities.sortedBy { it.position }.map { statusEntry(p, it, usageOfPriority(p, it.id)) },
        resolutions = p.resolutions.sortedBy { it.position }.map { statusEntry(p, it, usageOfResolution(p, it.id)) },
        sprints = p.sprints.sortedBy { it.position }.map { sprintEntry(p, it, usageOfSprint(p, it.id)) },
        versions = p.versions.sortedBy { it.position }.map { namedEntry(it, usageOfVersion(p, it.id)) },
        canMutateProject = true,
        // Maintainer and above, which an owner is. The demo has no caller below it, so the
        // read-only halves of Sprints and Versions never show here — see the server's
        // buildSettings (LNL-196).
        canMutateProjectPlanning = true,
        // Every section, because the demo visitor owns every board — the same list the
        // server builds for an owner. See ProjectSectionKeys.
        sections = listOf(
            ProjectSection(ProjectSectionKeys.GENERAL, "General"),
            ProjectSection(ProjectSectionKeys.GITHUB, "Github"),
            ProjectSection(ProjectSectionKeys.STRUCTURE, "Structure"),
            ProjectSection(ProjectSectionKeys.SPRINTS, "Sprints"),
            ProjectSection(ProjectSectionKeys.VERSIONS, "Versions"),
            ProjectSection(ProjectSectionKeys.ACCESS, "Access"),
        ),
        access = projectAccessState(p),
        yourAccessLine = visitorRung(p).let { key ->
            val rung = DEMO_RUNGS.first { it.key == key }
            "You are ${if (key == DemoRungKeys.ADMIN || key == DemoRungKeys.OWNER) "an" else "a"} " +
                "${rung.label} here. ${rung.description}"
        },
        canDeleteProject = true,
        notifyOnNewIssue = p.notifyOnNewIssue,
        canReceiveEmailNotifications = true,
        discussionsEnabled = p.discussionsEnabled,
        messagesEnabled = p.messagesEnabled,
        requireLabel = p.requireLabel,
        requireComponent = p.requireComponent,
        requireFixedVersionOnResolve = p.requireFixedVersionOnResolve,
        showIssueAuthor = p.showIssueAuthor,
        hideIssueNumbers = p.hideIssueNumbers,
        // No repository in the demo — the fields are offered (owner) but empty.
        repositoryUrl = "",
        canConfigureRepository = true,
        githubTokenEnv = "",
        githubTokenMode = TokenModes.NONE,
    )

    // ── Instance administration ───────────────────────────────────────────────

    fun adminSettingsState(): AdminSettingsState = AdminSettingsState(
        rungs = DEMO_RUNGS,
        // Administrators first, then in the order they were seeded, matching the server's
        // sort. It hoists the row a visitor is looking for to the top of a directory of
        // thirty-odd accounts.
        users = users.sortedByDescending { tierReaches(tierOf(it), DemoTierKeys.ADMIN) }.map { u ->
            val tier = tierOf(u)
            AdminUser(
                userId = u.id,
                name = u.name,
                email = u.email,
                tierLabel = demoTierLabel(tier),
                isSysAdmin = tierReaches(tier, DemoTierKeys.ADMIN),
                isSelf = u.id == demoUserId,
                // A grant nobody has claimed looks exactly like one that has, which is what
                // the NOT SIGNED IN badge is for. A handful of accounts here are genuinely
                // placeholders; the rest of the crew have arrived.
                hasSignedIn = u.hasSignedIn,
                // Read-only, and derived: the permission is per tier, so this reports which
                // side of the tier cards an account falls on rather than a box somebody
                // ticks for them. Toggling a card's agent switch moves this column.
                isMcpAllowed = permitsAgents(tier),
                isMcpEnabled = u.id == demoUserId && mcpEnabled,
                projects = projects.map { p ->
                    val own = p.members[u.id]
                    val floor = audienceFloor(p, tier)
                    // The same max the server takes, so the People tab and a project's own
                    // Access list cannot disagree about what somebody effectively holds.
                    // This used to report the own row alone, on the belief that the demo
                    // had no audience rows — it has had a members row on every project
                    // since LNL-194, so every account with no own row read as "no access"
                    // on a board that in fact admits them.
                    val effective = if (tierReaches(tier, DemoTierKeys.ADMIN)) {
                        DemoRungKeys.OWNER
                    } else {
                        higherRung(own, floor?.second)
                    }
                    AdminProjectRights(
                        projectId = p.id,
                        projectName = p.name,
                        roleKey = own,
                        effectiveRoleKey = effective,
                        viaAudience = floor
                            ?.takeIf {
                                !tierReaches(tier, DemoTierKeys.ADMIN) &&
                                    demoRungRank(it.second) >= demoRungRank(own)
                            }
                            ?.let { "the ${audienceTitle(it.first).lowercase()} row" },
                    )
                },
            )
        },
        projects = projects.map(::projectSummary),
        // The facts the greying above is computed from, so a visitor can see why a choice
        // is dead rather than guessing at a file they cannot read. The staff domain is the
        // demo's own (LNL-199); no Google pin, because the demo configures no provider.
        deployment = DeploymentFacts(
            staffDomain = DEMO_STAFF_DOMAIN,
            waysIn = listOf("Google", "mailed code"),
        ),
        // One card per tier that exists here, in the server's order. Guests never get one:
        // a guest has no account to permit. Both cards' switches start off, which is a real
        // fresh instance and is what makes them worth clicking — the People tab's MCP
        // column and the You tab's agent switch both move when they do.
        tiers = listOf(
            TierCard(
                key = DemoTierKeys.STAFF,
                title = "Staff",
                subtitle = "Accounts on $DEMO_STAFF_DOMAIN.",
                accountCount = users.count { tierOf(it) == DemoTierKeys.STAFF },
                mayCreateProjects = staffMayCreateProjects,
                mayUseAgents = staffMayUseAgents,
                createKey = InstanceSettingKey.STAFF_MAY_CREATE_PROJECTS,
                agentsKey = InstanceSettingKey.STAFF_MAY_USE_AGENTS,
            ),
            TierCard(
                key = DemoTierKeys.MEMBER,
                title = "Members",
                subtitle = "Every other account — people from outside $DEMO_STAFF_DOMAIN.",
                accountCount = users.count { tierOf(it) == DemoTierKeys.MEMBER },
                mayCreateProjects = memberMayCreateProjects,
                mayUseAgents = memberMayUseAgents,
                createKey = InstanceSettingKey.MEMBER_MAY_CREATE_PROJECTS,
                agentsKey = InstanceSettingKey.MEMBER_MAY_USE_AGENTS,
            ),
        ),
        newProjectAudiences = listOf(
            AudienceRow(
                DemoAudienceKeys.GUEST,
                "Guests",
                "Anybody at all, without signing in.",
                newProjectAudiences[DemoAudienceKeys.GUEST],
                isSelectable = allowPublicProjects,
                unavailableReason = if (allowPublicProjects) {
                    null
                } else {
                    "This deployment does not allow a project to be made public, so a new " +
                        "one cannot start out admitting guests. The Policy switch on the " +
                        "Instance tab is what changes that."
                },
            ),
            AudienceRow(
                DemoAudienceKeys.MEMBER,
                "Members",
                "Everybody with an account on this deployment.",
                newProjectAudiences[DemoAudienceKeys.MEMBER],
            ),
            AudienceRow(
                DemoAudienceKeys.STAFF,
                "Staff",
                "Accounts on $DEMO_STAFF_DOMAIN.",
                newProjectAudiences[DemoAudienceKeys.STAFF],
            ),
        ),
        // The demo visitor owns the instance, so the order and the delete are theirs.
        canReorderProjects = true,
        ownership = ownershipState(),
        admission = AdmissionState(
            selected = admission,
            options = AdmissionPolicy.entries.map { AdmissionOption(it) },
        ),
        allowPublicProjects = allowPublicProjects,
        hideDisplayName = hideDisplayName,
    )

    /**
     * Who owns this deployment, who administers it alongside them, and who it could go to.
     *
     * The picker is populated now, and that is the change LNL-199 makes here. It was empty
     * on purpose while the demo named no staff domain: nobody was eligible, so the dialog
     * showed the reason instead of a list, and [DemoLunicleApi.handOverInstance] could be
     * an honest no-op because nothing could ever call it. Naming a domain removes that
     * premise rather than papering over it — the crew who have signed in are genuinely
     * staff, so the picker lists them and Confirm genuinely moves [ownerUserId].
     *
     * The empty-list wording is kept for the case it now covers: a deployment that has a
     * domain and nobody on it who has arrived. Only the demo's placeholder accounts are in
     * that state today, but the sentence is the one the server would send and the dialog
     * should read the same in both.
     */
    private fun ownershipState(): InstanceOwnership {
        val owner = users.firstOrNull { it.id == ownerUserId }
        val isSelf = owner != null && owner.id == demoUserId
        val candidates = if (isSelf) {
            users.filter { mayBeHandedTheInstance(it) }
                .map { OwnerCandidate(userId = it.id, name = it.name, email = it.email) }
        } else {
            emptyList()
        }
        return InstanceOwnership(
            ownerName = owner?.name,
            ownerEmail = owner?.email,
            isOwnerSelf = isSelf,
            // Everybody else who runs the place. Nobody, in this world: the demo has one
            // account at the top and a plain administrator beside it would be a second
            // story to tell.
            adminNames = emptyList(),
            canHandOver = isSelf,
            handOverBlockedReason = "Only the instance owner can hand it over.".takeIf { !isSelf },
            handOverCandidates = candidates,
            handOverEmptyReason = if (isSelf && candidates.isEmpty()) {
                "There is nobody to hand this instance to. Only an account on " +
                    "$DEMO_STAFF_DOMAIN that somebody has actually signed in to can own it — " +
                    "an address added ahead of time and never claimed cannot."
            } else {
                null
            },
        )
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    fun notificationListState(): NotificationListState = NotificationListState(
        items = notifications.sortedByDescending { it.createdAt }.map { n ->
            NotificationSummary(
                id = n.id,
                kind = n.kind,
                title = n.title,
                createdAt = n.createdAt,
                isRead = n.isRead,
                projectId = n.projectId,
                issueId = n.issueId,
            )
        },
        unreadCount = notifications.count { !it.isRead },
    )

    fun unreadCount(): Int = notifications.count { !it.isRead }

    // ── Statistics ────────────────────────────────────────────────────────────

    fun statisticsState(p: DemoProject): StatisticsState {
        val closedStatusIds = p.statuses.filter { it.requiresResolution }.map { it.id }.toSet()
        val closed = p.issues.count { !it.isDraft && it.statusId in closedStatusIds }
        val created = p.issues.count { !it.isDraft }
        return StatisticsState(
            statistics = ProjectStatistics(
                computedAt = now(),
                commits = null,
                commitsUnavailable = "No repository is linked in this demo.",
                issuesCreated = StatisticWindow(week = 6, month = 18, allTime = created.toLong()),
                issuesClosed = StatisticWindow(week = 3, month = 9, allTime = closed.toLong()),
            ),
            isStale = false,
        )
    }
}
