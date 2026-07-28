/**
 * Wire types for the project settings dialog: the vocabularies, and who may do
 * what.
 *
 * Separate from [BoardState] rather than bolted onto it, and the split is the
 * point. The board sends every reader the vocabulary *names* — a card cannot
 * render "Bug" without them. This sends an admin the vocabulary's **shape**: how
 * many issues hold each row, which row is the magic closing column, and a
 * directory of every account on the instance with its grants. None of that is
 * needed to draw a board, and a signed-out visitor reading a public project has
 * no business receiving a list of everyone who has ever signed in.
 *
 * So this is its own request, refused outright to anyone who is not an admin —
 * not "sent to everyone and hidden by the bundle", which is the failure
 * BoardRoutes' preamble names as the one that gets forgotten.
 *
 * @see BoardState
 * @see se.soderbjorn.lunicle.clientserver.ApiRoutes.projectSettings
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.Serializable

/**
 * Which of a project's six vocabularies a request is about.
 *
 * One enum and one set of routes rather than six near-identical route families,
 * because they differ in very little — what deleting one costs, and whether a
 * project can be without it — and both of those are decisions the *server*
 * makes. Six copies of "add a row, rename a row, delete a row" would be six
 * places to forget the last-status rule.
 *
 * Every kind is ordered. That used to be the other axis they differed on, until
 * labels and components gained a `position` too (see LNL-28); a sprint arrived
 * already carrying one, for a third reason again — not because the order *is*
 * the data, but because planning wants to slot a sprint between two that already
 * exist. See Sprints.sq's `position`.
 *
 * The four properties below are facts about the *schema*, and they live here
 * rather than on the server because both sides need the same answers and there
 * must not be two of them. The server refuses a delete that [restrictsOnUse]
 * covers; the client, holding the same property, declines to offer the button.
 * That is the affordance relationship this codebase runs on — one rule, enforced
 * on one side and rendered on the other — and it only works while it is literally
 * one expression. Two copies would drift, and the drift would show up as a button
 * that is offered and then refused.
 *
 * @property key what appears in the URL. Explicit rather than `name.lowercase()`:
 *   these strings are wire format, so a rename of the constant must not silently
 *   move a route. Changing one is a client-and-server change, which is precisely
 *   what having to edit this line makes you notice.
 * @property noun what to call one of these in a sentence — "There is already a
 *   label called…". Identical to [key] today, and deliberately not the same
 *   property: one is prose an admin reads and the other is a URL segment, and the
 *   day a kind needs a two-word name is the day conflating them would put a space
 *   in a route.
 * @property plural the same word, more than one of it. Carried rather than
 *   computed, because `noun + "s"` produces "statuss" and "prioritys" — which is
 *   not a hypothetical: it shipped as far as a manual pass, in the sentence
 *   refusing a bad reorder. English plurals are data, not a rule.
 */
@Serializable
enum class VocabularyKind(val key: String, val noun: String, val plural: String) {
    LABEL("label", "label", "labels"),
    COMPONENT("component", "component", "components"),
    STATUS("status", "status", "statuses"),
    PRIORITY("priority", "priority", "priorities"),
    RESOLUTION("resolution", "resolution", "resolutions"),

    /**
     * Timeboxes. The one kind a project may legitimately have none of forever —
     * see Sprints.sq. Everything add/rename/delete/reorder about a sprint is
     * this enum's machinery; activating and completing one are their own routes,
     * because neither is a thing you can do to a label.
     */
    SPRINT("sprint", "sprint", "sprints"),

    /**
     * Release versions (LNL-134). A project may have none forever, like sprints —
     * every add/rename/delete/reorder is this enum's machinery. An issue points at
     * one twice (its planned version and its fixed version), but that is a fact
     * about `issues`, not about the version, so nothing extra rides on the kind.
     */
    VERSION("version", "version", "versions");

    /**
     * Whether the database refuses to delete one of these while an issue holds it.
     *
     * The three that `issues` points at directly — status, priority, resolution —
     * are composite-keyed with no ON DELETE clause, so SQLite's default RESTRICT
     * fires. Labels and components are reached through `issue_labels` /
     * `issue_components`, whose keys are ON DELETE CASCADE: deleting one of those
     * unlabels the issues and leaves them standing, which is a consequence to warn
     * about rather than a reason to refuse.
     *
     * Sprints are pointed at directly by `issues` like the first three, and are
     * still false here, because their key is `ON DELETE SET NULL`: deleting a
     * sprint un-schedules its issues rather than being refused. That is the
     * whole reason a sprint could join this enum at all — see Sprints.sq for why
     * the reference could not be composite, which is what makes the other three
     * restrict.
     *
     * This mirrors the schema. If an ON DELETE clause ever changes, this is the
     * line that changes with it — and both the server's refusal and the client's
     * disabled button follow, because both read this.
     */
    val restrictsOnUse: Boolean
        get() = when (this) {
            STATUS, PRIORITY, RESOLUTION -> true
            // VERSION joins sprints on the false side, and for the same reason:
            // both issue references are ON DELETE SET NULL, so deleting a version
            // releases the issues that named it rather than being refused. See
            // Versions.sq.
            LABEL, COMPONENT, SPRINT, VERSION -> false
        }

    /**
     * Whether a project stops working without at least one of these.
     *
     * `IssueRepository.createDraft` reads the leftmost status and the middle
     * priority, and errors if either is missing — so a project with none of either
     * cannot take an issue, and cannot be fixed by filing one about it. Deleting
     * the last one is refused for that reason and no other.
     *
     * Nothing else is load-bearing that way. A project with no labels, no
     * components or no resolutions is unremarkable, and every one of them can be
     * added back from the dialog that emptied it. The rule is not "never run out",
     * it is "never become unrepairable".
     *
     * Sprints go further than unremarkable: having none is the *default*, and the
     * state every project that does not want timeboxes stays in permanently. A
     * rule about running out of them would be backwards.
     */
    val isLoadBearing: Boolean
        get() = this == STATUS || this == PRIORITY
}

/**
 * One row of one vocabulary, as the settings dialog needs it.
 *
 * Richer than the [VocabularyItem] the board gets, and deliberately so — see this
 * file's preamble.
 *
 * @property position where it sits in the order, 0 first. Every kind has one:
 *   statuses are board columns, priorities are a scale, and labels and
 *   components are lists somebody curated — alphabetical was never a decision
 *   anyone made, only what you got when nobody had. See Labels.sq.
 * @property requiresResolution whether landing in this column demands a
 *   resolution. Only ever meaningful for a status; see Statuses.sq.
 * @property usageCount how many issues hold this row *right now*. What it means
 *   depends on the kind, and the client must not guess: for a status or a
 *   priority a non-zero count means the delete will be refused, and for a label it
 *   means that many issues will be unlabelled. [VocabularyKind] does not carry
 *   that rule either — [ProjectSettingsState] is rendered by a view model that
 *   phrases both sentences, and the server refuses regardless of what the client
 *   believed.
 *
 *   Published issues only. A draft holds a status like any other row, and counting
 *   one used to be the point — it is a draft that makes a delete fail — but a
 *   draft lands in the leftmost column and is never collected, so that made the
 *   first column of a long-lived project permanently undeletable over issues its
 *   admin could not see (LNL-183). The server clears the drafts out of a deleted
 *   row's way instead; see Issues.sq's usageByStatus.
 */
@Serializable
data class VocabularyEntry(
    val id: Long,
    val name: String,
    val position: Int = 0,
    val requiresResolution: Boolean = false,
    /**
     * Whether this resolution means the work was actually done (LNL-134). Only ever
     * meaningful for a resolution — the mirror of [requiresResolution]; see
     * Resolutions.sq. It is what the Structure tab renders as a checkbox and what
     * the resolution dialog reads to decide whether a fixed version is required.
     */
    val isDone: Boolean = false,
    val usageCount: Int = 0,
)

/**
 * The one role key both halves of the wire have to know by name.
 *
 * The rest of the role vocabulary the client renders blindly — a key it does not
 * recognise is just a checkbox it draws from [RoleDescription.description] without
 * knowing what it means, which is the point of sending descriptions at all. This
 * one is the exception: `view_project` does not mean what "holds this role" means.
 * [se.soderbjorn.lunicle.AccessControl.canReadProject] says a public project is
 * visible to everyone, and holding *any* role implies it — so the admin dialog's
 * "see this project" row is driven off an effective flag, not the raw grant, and
 * both sides need the same string to agree on which row that is.
 *
 * Hoisted here, and referenced by the server's [se.soderbjorn.lunicle.Role] enum
 * rather than restated, so the two literally cannot drift: the enum's key and this
 * constant are the same symbol.
 */
object RoleKeys {
    const val VIEW_PROJECT: String = "view_project"
}

/**
 * The three sources a project's GitHub token can have, as the strings both halves
 * of the wire name it by (LNL-107).
 *
 * Hoisted here for [RoleKeys]' reason: the server's `TokenSource` maps to and from
 * these, the client's radio renders them, and [ProjectUpdate.githubTokenMode]
 * carries one — so the one place they are spelled has to be shared, or a rename on
 * one side becomes a mode nobody recognises on the other.
 */
object TokenModes {
    /** No token — the commit counts go unavailable, everything else answers. */
    const val NONE: String = "none"

    /** The token is the value of a named environment variable, resolved at read time. */
    const val ENV: String = "env"

    /** The token is stored literally on the project. Write-only on the wire. */
    const val LITERAL: String = "literal"
}

/**
 * One role this instance has, and what holding it grants.
 *
 * Sent rather than compiled into the bundle, for the reason the provider flags
 * are: the roles are the server's [se.soderbjorn.lunicle.Role] enum, and a client
 * that hardcoded the list would offer a checkbox for a role a rolled-back server
 * does not have — or, worse, quietly stop offering one it does.
 *
 * @property description the enum's own sentence, shown under the checkbox. Written
 *   once, on the server, so the dialog cannot describe a grant differently from
 *   the thing granting it.
 */
@Serializable
data class RoleDescription(
    val key: String,
    val description: String,
)

/**
 * One account, and what it holds in this project.
 *
 * Every account on the instance, not only the ones with a grant: "assign
 * privileges to other users" needs a list of the users you could assign to, and a
 * table that only showed people who already have a role would have no row to tick
 * for the person you are trying to add.
 *
 * A name and an id, like [UserOption], and for the same reason — no email, no
 * provider. The id is unavoidable: it is what a grant has to name.
 *
 * @property isSysAdmin whether this account is the instance admin. Sent because an
 *   admin's checkboxes would be meaningless — [se.soderbjorn.lunicle.AccessControl]
 *   says yes to an admin before it ever looks at a role — so the dialog gives the
 *   row a sentence instead of boxes, and sorts it to the top.
 * @property isSelf whether this is the caller. The dialog shows it, like the
 *   impersonation menu does, so the table matches the user list an admin is
 *   looking at.
 * @property roleKeys the roles this user holds *here*. Keys rather than an enum,
 *   because the client renders them against [ProjectSettingsState.roles] and has
 *   no business knowing what any of them mean.
 */
@Serializable
data class ProjectMember(
    val userId: Long,
    val name: String,
    val isSysAdmin: Boolean = false,
    val isSelf: Boolean = false,
    val roleKeys: List<String> = emptyList(),
)

/**
 * Everything the settings dialog needs, in one round-trip.
 *
 * One state rather than seven endpoints, for [BoardState]'s reason: the dialog
 * cannot render half of this, and seven requests would be seven chances to paint
 * a dialog that is missing a section.
 *
 * @property canMutateProject whether the caller may write the admin half — the
 *   vocabularies, the sprints and the grants. True for a system administrator and
 *   for a project administrator *of this project* (LNL-37); it used to mean the
 *   former alone. For everyone else the server sends this false **and omits those
 *   sections entirely**: the dialog is openable by everyone (the issue's "what is
 *   shown depends on if they are an admin"), so this is not "always true in
 *   practice". What they still receive is the notification fields below, which are
 *   theirs to change. An affordance either way — every write route re-derives the
 *   answer from the session.
 * @property canGrantSeniorRoles whether the caller may tick the `project_admin`
 *   and `project_owner` boxes in the privileges table. Strictly narrower than
 *   [canMutateProject]: a project administrator hands out the issue-scoped roles
 *   but may promote neither a peer nor an owner, so the dialog disables exactly
 *   those two rows for them rather than hiding the table. An owner or a system
 *   administrator (LNL-107). See AccessControl.canGrant.
 * @property notifyOnNewIssue whether the caller has asked to be e-mailed when a
 *   new issue is created in this project. Every signed-in reader gets this,
 *   administrator or not; it is the one thing anyone else can change here.
 * @property canReceiveEmailNotifications whether the caller has an address at all.
 *   The toggle is hidden without it, for [IssueDetail.canReceiveEmailNotifications]'s
 *   reason. Computed from the caller's own record server-side.
 */
@Serializable
data class ProjectSettingsState(
    val labels: List<VocabularyEntry> = emptyList(),
    val components: List<VocabularyEntry> = emptyList(),
    val statuses: List<VocabularyEntry> = emptyList(),
    val priorities: List<VocabularyEntry> = emptyList(),
    val resolutions: List<VocabularyEntry> = emptyList(),
    /**
     * The project's sprints, oldest-planned first. Empty for every project that
     * has never made one, which is the default and stays the default — the
     * dialog renders the section regardless, because an empty section with an
     * add field is how you make the first one.
     */
    val sprints: List<VocabularyEntry> = emptyList(),
    /**
     * The project's release versions, in the order the admin arranged (LNL-134).
     * Empty for every project that has never made one — the default, and the
     * dialog renders the section regardless so an empty section with an add field
     * is how you make the first one, exactly like [sprints].
     */
    val versions: List<VocabularyEntry> = emptyList(),
    val roles: List<RoleDescription> = emptyList(),
    val members: List<ProjectMember> = emptyList(),
    val canMutateProject: Boolean = false,
    val canGrantSeniorRoles: Boolean = false,
    val notifyOnNewIssue: Boolean = false,
    val canReceiveEmailNotifications: Boolean = false,
    /**
     * Whether this project offers a discussion forum and private messages
     * (LNL-96) — the two switches a project administrator flips in the Features
     * section here. Both default enabled, the state every project had before the
     * switches existed. Sent to every caller who reaches this state, but only the
     * admin half of the dialog renders the section that edits them; see
     * [canMutateProject]. The same two flags ride to the tab shell on
     * [ProjectSummary], which is what actually hides the tabs.
     */
    val discussionsEnabled: Boolean = true,
    val messagesEnabled: Boolean = true,
    /**
     * Whether filing a new ticket must carry a label, and whether it must carry a
     * component (LNL-106) — the two switches a project administrator flips in the
     * Structure tab here. Both default off, the state every project had before the
     * switches existed. Sent to every caller who reaches this state, but only the
     * admin half renders the section that edits them; see [canMutateProject]. The
     * same two flags ride to the issue editor on [ProjectSummary], which is what
     * actually enforces the requirement in the editor.
     */
    val requireLabel: Boolean = false,
    val requireComponent: Boolean = false,
    /**
     * Whether closing an issue with a done resolution must carry a fixed version
     * (LNL-134) — the third requirement toggle in the Structure tab. Default off.
     * Rides to the board and resolution dialog on [ProjectSummary], which is what
     * actually enforces it at close time. See [requireLabel].
     */
    val requireFixedVersionOnResolve: Boolean = false,
    /**
     * Whether the board shows each card's author on a muted footer line (LNL-157) —
     * a display toggle a project administrator flips, alongside the requirement
     * toggles here. Default off, the opt-in state every project had before the
     * column existed. Sent to every caller who reaches this state, but only the
     * admin half renders the section that edits it; see [canMutateProject]. The same
     * flag rides to the board on [ProjectSummary], which is what the card render reads.
     */
    val showIssueAuthor: Boolean = false,
    /**
     * The linked GitHub repository as `owner/name`, or empty because none is.
     *
     * **Admin only, and omitted rather than flagged.** The server sends this
     * empty string to every non-admin whatever the project has configured, on the
     * same principle as the vocabularies above: a field the caller may not see is
     * left out of the response, not sent and hidden by the browser. A secret that
     * reaches the client and is merely not rendered is not withheld — it is one
     * devtools panel away.
     *
     * What is admin-only is this *field* — the one the settings form edits — and
     * not the address itself, which LNL-178 put on [BoardState.gitHubRepository]
     * for every reader of the project: a `#123` in an issue is a link to that pull
     * request, and a link is a URL the client has to be able to build. The
     * distinction that survives is the one that was always doing the work: the
     * token never travels, to anybody. See [BoardState.gitHubRepository].
     */
    val repositoryUrl: String = "",
    /**
     * Whether the caller may see and set the repository fields below.
     *
     * Strictly narrower than [canMutateProject], and a sibling of
     * [canGrantSeniorRoles] in both shape and reason: an **owner or a system
     * administrator** (LNL-107), where the rest of the admin half opened up to
     * project administrators in LNL-37.
     *
     * The split is not tidiness. The repository is part of a project's *identity*
     * — the write goes through `canOwnProject`, the owner's gate — and, more
     * sharply for the token, the fields can carry a deployment secret. Sending
     * them to a project administrator would render them editable and then 403 on
     * save.
     *
     * When false the fields below are **empty regardless of what the project has
     * configured**, for the reason [repositoryUrl] gives: a value the caller may
     * not have does not travel and get hidden, it does not travel.
     */
    val canConfigureRepository: Boolean = false,
    /**
     * The name of the environment variable holding the GitHub token — never the
     * token. Read only when [githubTokenMode] is `env`; empty otherwise.
     *
     * Owner only, for [repositoryUrl]'s reason and more sharply: this names a
     * variable in the deployment, which is a hint about how the deployment is put
     * together and is nobody's business but an owner's.
     *
     * Constrained to a prefix on write; see the server's `parseTokenEnvName` for
     * why an owner may not name any variable they like.
     */
    val githubTokenEnv: String = "",
    /**
     * Which source the token comes from: `none`, `env`, or `literal` — the state
     * of the dialog's radio (LNL-107). See [TokenModes].
     *
     * The literal token itself is **never sent back**: a stored secret does not
     * travel to the browser, so there is no `githubTokenLiteral` here to mirror
     * [githubTokenEnv]. This mode is the whole of what the dialog learns about a
     * literal token — that one is configured — which is enough to select the radio
     * and show the field as a "leave blank to keep it" placeholder. Owner only,
     * `none` for everyone else, for [canConfigureRepository]'s reason.
     */
    val githubTokenMode: String = TokenModes.NONE,
) {
    /** The rows of one kind, so a caller with a [VocabularyKind] does not have to branch. */
    fun entriesFor(kind: VocabularyKind): List<VocabularyEntry> = when (kind) {
        VocabularyKind.LABEL -> labels
        VocabularyKind.COMPONENT -> components
        VocabularyKind.STATUS -> statuses
        VocabularyKind.PRIORITY -> priorities
        VocabularyKind.RESOLUTION -> resolutions
        VocabularyKind.SPRINT -> sprints
        VocabularyKind.VERSION -> versions
    }
}

/** What the "add" field sends. The kind is in the path. */
@Serializable
data class VocabularyAdd(
    val name: String,
)

/**
 * "Switch this project's discussions and messages on or off" (LNL-96).
 *
 * Both flags together, never one at a time — the section renders two toggles but
 * they are one project's feature set, and the server takes the pair so a stale
 * client cannot resurrect the other by omitting it. Names the desired state
 * rather than "toggle", for [VocabularyEdit]'s reason: a retry says the same
 * thing, and two admins cannot flip a project's discussions back and forth by
 * both clicking once. Project administrator only, enforced at the route.
 */
@Serializable
data class ProjectFeatures(
    val discussionsEnabled: Boolean = true,
    val messagesEnabled: Boolean = true,
)

/**
 * "Switch this project's new-ticket requirements on or off" (LNL-106).
 *
 * Both flags together, for [ProjectFeatures]' reason exactly: the Structure tab
 * renders two toggles but they are one project's requirement set, and the server
 * takes the pair so a stale client cannot resurrect the other by omitting it.
 * Names the desired state rather than "toggle". Project administrator only,
 * enforced at the route.
 */
@Serializable
data class ProjectRequirements(
    val requireLabel: Boolean = false,
    val requireComponent: Boolean = false,
    /** Whether closing with a done resolution must carry a fixed version (LNL-134). */
    val requireFixedVersionOnResolve: Boolean = false,
)

/**
 * "Switch this project's board-display settings on or off" (LNL-157).
 *
 * A parallel to [ProjectRequirements], its own request type because a display
 * choice is not a requirement — the two are set through different routes so a
 * stale client toggling one cannot resurrect the other. Names the desired state
 * rather than "toggle", for [ProjectRequirements]' reason. Project administrator
 * only, enforced at the route.
 */
@Serializable
data class ProjectDisplaySettings(
    /** Whether the board shows each card's author on a muted footer line. */
    val showIssueAuthor: Boolean = false,
)

/**
 * What a rename sends.
 *
 * @property requiresResolution the closing flag, for a status. Ignored by the
 *   server for every other kind rather than refused: the dialog sends the row it
 *   is rendering, and a priority row simply has nothing to put here. Refusing
 *   would make the client responsible for knowing which kinds have the flag,
 *   which is the server's rule to keep.
 *
 *   Sent on every rename rather than as its own toggle route, because a status's
 *   name and its flag are one edit — see Statuses.sq's `update`.
 */
@Serializable
data class VocabularyEdit(
    val name: String,
    val requiresResolution: Boolean = false,
    /**
     * The done flag, for a resolution (LNL-134). Ignored by the server for every
     * other kind rather than refused, for [requiresResolution]'s reason exactly —
     * the dialog sends the row it is rendering. Sent on every rename because a
     * resolution's name and its done flag are one edit; see Resolutions.sq's `update`.
     */
    val isDone: Boolean = false,
)

/**
 * A whole vocabulary, in the order the admin just put it in.
 *
 * The whole list, not "move row 3 up one" — [IssueOrderUpdate]'s reasoning, for
 * the same reason: a position is a statement about neighbours, and a server told
 * only about the row that moved would have to reconstruct the rest and guess. The
 * client already knows the order; it is what it just rendered.
 *
 * The server checks that these are exactly this vocabulary's ids — all of them,
 * none repeated, nothing foreign — before it writes. A partial list would leave
 * the rows it omitted holding positions that now collide with the ones it named.
 *
 * @property ids every row of one kind, first to last.
 */
@Serializable
data class VocabularyOrder(
    val ids: List<Long> = emptyList(),
)

/**
 * "Give this user this role here", or take it away.
 *
 * [isGranted] is the state to move to rather than a toggle, for
 * [McpEnabledRequest]'s reason: a retry says the same thing, and two admins with
 * the dialog open cannot flip a grant back and forth by both clicking once.
 *
 * Note what this does not say: who is asking. That comes from the session cookie
 * server-side, on every request. A field for it would be the authorization system
 * asking the caller to authorize themselves — see [ImpersonateRequest], which is
 * the same shape for the same reason.
 *
 * @property userId whose privileges to change.
 * @property roleKey which role, as [RoleDescription.key]. A key this server does
 *   not have is a 400: the alternative is `INSERT OR IGNORE` quietly doing
 *   nothing while the dialog re-renders the box as ticked.
 */
@Serializable
data class RoleGrant(
    val userId: Long,
    val roleKey: String,
    val isGranted: Boolean,
)
