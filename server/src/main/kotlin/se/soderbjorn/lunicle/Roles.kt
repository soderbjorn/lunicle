/**
 * The permission vocabulary — two cumulative ladders — and who stands where.
 *
 * ── Two ladders and one rule (LNL-191) ──────────────────────────────────────
 *
 * A **project role** is one of five rungs: Viewer, Contributor, Maintainer,
 * Admin, Owner. They are cumulative, so a rung answers every question the rungs
 * below it answer, and the rung *name* is the only thing ever stored — one row
 * per person per project in `project_roles`, and not a column per privilege
 * anywhere. What a rung permits is a function in [AccessControl], which is what
 * makes widening one later a deploy rather than a migration: every existing
 * holder gains the widened power the moment the new build runs.
 *
 * An **instance role** is one of five, also ascending: guest (no account at all),
 * member, staff, instance administrator, instance owner. The lowest three are
 * exactly the [Audience]s a project may grant a rung to — one vocabulary, not two,
 * so "who is a member" cannot come to mean one thing on a project and another on
 * the instance.
 *
 * The rule joining them, and the whole of the access model:
 *
 *     effectiveRole(user, project) = max(the audience rows the user matches,
 *                                        the user's own row)
 *
 * A person's own row can raise them above their audience and **never cuts them
 * below it**. That is why the rule is a `max` and not an override: a project that
 * admits every member as a Contributor cannot be made to admit one named person
 * as a Viewer by writing a smaller row, because there is nothing to write that
 * subtracts. Revoking is done by lowering the audience, which is a statement about
 * everybody.
 *
 * Nothing here decides anything. See [AccessControl]'s preamble for where the
 * deciding happens and why it happens in exactly one place.
 *
 * @see AccessControl
 * @see Database
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * What somebody may do in one project: five rungs, each containing the last.
 *
 * Declaration order **is** the ladder — [rank] is the ordinal, and every rule in
 * [AccessControl] is spelled `atLeast(...)`. Reordering these is not a
 * refactor; it is a change to who may do what.
 *
 * @property key what is stored and what crosses the wire. Explicit rather than
 *   `name.lowercase()` so a rename of the constant is not silently a migration of
 *   every row in `project_roles`.
 * @property label what a rung is called on screen — one capitalised word, so the
 *   rail can say "Maintainer" beside a project name without a lookup table in the
 *   client. Beside [description] rather than derived from [key], for [key]'s reason
 *   turned around: the key is wire format and must not move when the wording does.
 * @property description the sentence the settings dialog shows under the rung.
 *   Written once, here, so the dialog cannot describe a rung differently from the
 *   thing granting it.
 */
enum class ProjectRole(val key: String, val label: String, val description: String) {
    /** Read, and nothing else. */
    VIEWER("viewer", "Viewer", "Read this project, without being able to change anything in it."),

    /**
     * File issues, comment on them, and be assignable.
     *
     * The rung that makes somebody a participant rather than an audience. It
     * deliberately does **not** carry editing other people's issues — that is
     * [MAINTAINER] — but it does carry editing your own, which is authorship
     * rather than a rung; see [AccessControl.canEditIssue].
     */
    CONTRIBUTOR("contributor", "Contributor", "File issues, comment on them, and be assigned them."),

    /**
     * Edit anyone's issue, and run the timeboxes: sprints and versions.
     *
     * "Edits anyone's issue" includes moving it between columns and closing it,
     * because a status write is an issue write — see [AccessControl.canEditIssue],
     * which says at length why dragging a card must not become a lighter question
     * than editing one.
     *
     * Sprints and versions sit here rather than with [ADMIN] on purpose: planning
     * the next two weeks is day-to-day work on a board somebody already edits every
     * issue on, where the vocabulary — the set of statuses a project *has* — is a
     * decision about the board's shape.
     */
    MAINTAINER("maintainer", "Maintainer", "Edit anyone's issue here, and manage the sprints and versions."),

    /**
     * Run the board: its vocabulary, its display settings, its project settings,
     * deleting issues, and handing out rungs up to [MAINTAINER].
     *
     * **Vocabulary is deliberately here and not on [MAINTAINER].** Adding a status
     * changes every board view for everybody and can strand issues in a column
     * nobody planned; renaming a priority rewrites what every card means. Those are
     * decisions about the project, not work inside it.
     *
     * **Deleting an issue is here, where editing one is a rung lower.** A
     * maintainer can already empty an issue of everything it said and close it; the
     * thing they cannot do is make it stop existing, which is the one act with no
     * history and no undo.
     */
    ADMIN(
        "admin",
        "Admin",
        "Administer this project: its vocabulary, its settings, deleting issues, " +
            "and granting roles up to maintainer.",
    ),

    /**
     * Own the project: its identity, its prefix, its repository, its visibility,
     * its deletion — and promoting other Admins and Owners.
     *
     * The top rung, and the only one that can grant itself. That is not an
     * oversight but the place the escalation is allowed to stop: an Admin who could
     * promote a peer could make a second Admin, who could make a third, and nobody
     * senior would have a say. The owner is the project's answerable party. See
     * [AccessControl.canGrant].
     */
    OWNER(
        "owner",
        "Owner",
        "Own this project: everything an administrator can do, plus its name, its prefix, " +
            "its repository, its visibility, its deletion, and promoting administrators and owners.",
    ),
    ;

    /** Where this rung sits on the ladder, 0 lowest. Declaration order. */
    val rank: Int get() = ordinal

    /** Is this rung [other], or senior to it? The shape every rule in [AccessControl] takes. */
    fun atLeast(other: ProjectRole): Boolean = rank >= other.rank

    companion object {
        /** The rung with this [key], or null — an unknown key grants nothing rather than failing a read. */
        fun byKey(key: String): ProjectRole? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Where somebody stands on the *instance*: five rungs, ascending.
 *
 * The lowest three are the [Audience]s a project grants to, which is the point of
 * having one ladder rather than two — "member" means the same thing when a project
 * admits members as it does when an administrator looks at an account.
 *
 * @property key the stored/wire name where one is stored at all. Note that only
 *   [STAFF] and [MEMBER] are ever written down (as `users.kind`) and only [ADMIN]
 *   is ever written down as `users.instance_role`: [GUEST] is the *absence* of a
 *   user row, and [OWNER] is `instance_settings.owner_user_id` — a single-valued
 *   setting rather than a third value on the column, so "exactly one owner, always"
 *   is structural on a document backend too and not an invariant enforced by a
 *   partial unique index Firestore does not have.
 */
enum class InstanceRole(val key: String) {
    /** Not signed in, and no account. The absence of a row, never a stored value. */
    GUEST("guest"),

    /** Signed in, from outside the deployment's own domain. */
    MEMBER("member"),

    /** Signed in, from the deployment's own domain — see [UserKind]. */
    STAFF("staff"),

    /** Runs the instance: `users.instance_role = 'admin'`. */
    ADMIN("admin"),

    /** Owns the instance. Exactly one, held as a setting; see the class doc. */
    OWNER("owner"),
    ;

    /** Where this rung sits, 0 lowest. */
    val rank: Int get() = ordinal

    /** Is this rung [other], or senior to it? */
    fun atLeast(other: InstanceRole): Boolean = rank >= other.rank
}

/**
 * Who a project grants a rung to wholesale: the bottom three [InstanceRole]s.
 *
 * At most three rows per project in `project_audience_roles`, and they replace
 * `projects.is_public` and `projects.visible_to_all_signed_in` outright. Where
 * those two booleans could only say "readable by strangers" and "readable by
 * accounts", an audience row says *which rung* an audience arrives at — so
 * "everybody on the instance may file bugs here" is one row rather than a grant
 * per person, and "the world may read this" is the same mechanism rather than a
 * special case beside it.
 *
 * @property instanceRole the rung on the instance ladder a person must reach to
 *   match this audience. Because the ladder ascends, a `guest` row matches
 *   everybody, a `member` row matches members and staff, and a `staff` row matches
 *   staff alone.
 * @property ceiling the highest [ProjectRole] this audience may ever hold. See
 *   [GUEST], which is the only one it narrows and the whole of LNL-202.
 */
enum class Audience(val key: String, val instanceRole: InstanceRole, val ceiling: ProjectRole) {
    /**
     * Everybody, including a caller with no session at all — and capped at
     * [ProjectRole.VIEWER] (LNL-202).
     *
     * ── Why this one audience has a ceiling ─────────────────────────────────
     *
     * A guest is *defined* by the absence of an account, and every rung above Viewer
     * describes **writing**: filing an issue, commenting, being handed work. A write
     * needs somebody to attribute it to, and a session-less caller has nobody — no
     * account, no name, not even an address to sign it with. LNL-197 already showed
     * what an authorless row costs: it matches no "you wrote it" clause, so whoever
     * made it can neither publish nor discard it.
     *
     * So this row could always only coherently mean one thing, and the design only
     * ever described it as such: "set it to Viewer and the project is public". Nothing
     * *enforced* that, though, and `project_audience_roles` will hold any rung for any
     * audience — so an owner could set Guests → Contributor and anonymous issue filing
     * was one dropdown away. The ceiling is what closes it, in one place, for the write
     * gate and the read path alike.
     *
     * Note this caps the row rather than the caller: a signed-in member who matches
     * only the guest row is capped too. That is correct and is the reason the ceiling
     * is a property of the audience — a row above Viewer here is not a stricter
     * arrangement to be honoured for whoever *can* be attributed, it is invalid data.
     * "Everybody on this deployment may file bugs" is the **member** row, which has no
     * ceiling.
     *
     * Anonymous contribution is deliberately not built. If it ever ships it needs a
     * decision about attribution — a name field, a captcha, a moderation queue — and it
     * must not arrive by leaving a dropdown unguarded.
     */
    GUEST("guest", InstanceRole.GUEST, ProjectRole.VIEWER),

    /** Everybody with an account. Every rung is expressible: they can be attributed. */
    MEMBER("member", InstanceRole.MEMBER, ProjectRole.OWNER),

    /** Accounts from the deployment's own domain. No ceiling, for [MEMBER]'s reason. */
    STAFF("staff", InstanceRole.STAFF, ProjectRole.OWNER),
    ;

    /** May this audience be handed [role] at all? See [ceiling]. */
    fun permits(role: ProjectRole): Boolean = ceiling.atLeast(role)

    /**
     * [role], or this audience's [ceiling] where [role] is above it.
     *
     * The **read**-side half of the ceiling, and it is not redundant with [permits]: a
     * row above the ceiling can already exist in a database — hand-edited, restored
     * from a backup, or written by a build older than LNL-202 — and a capped write
     * beside an uncapped read is one such row away from the bug all over again. Every
     * place that folds audience rows into a rung goes through [admitting], which
     * applies this.
     */
    fun cap(role: ProjectRole): ProjectRole = if (permits(role)) role else ceiling

    /**
     * Why [role] is more than this audience may hold — or null because it is not.
     *
     * The sentence lives on the vocabulary rather than in a screen because four
     * surfaces show it: a project's Access list, the instance's new-project rows, and
     * the two routes that refuse the write. A rule explained four ways is a rule
     * nobody trusts, and the publish veto beside it is already spelled twice.
     */
    fun refusalFor(role: ProjectRole): String? {
        if (permits(role)) return null
        // A `when` rather than an `else`, so that giving MEMBER or STAFF a ceiling one
        // day has to say why here rather than inheriting a sentence about guests.
        return when (this) {
            GUEST ->
                "A guest has not signed in, so there is nobody to attribute a write to — " +
                    "which is what every rung above ${ceiling.label} is. Guests can only read. " +
                    "To let people file issues, give the members row ${ProjectRole.CONTRIBUTOR.label}."
            MEMBER, STAFF -> "This audience cannot hold more than ${ceiling.label} here."
        }
    }

    companion object {
        /** The audience with this [key], or null — an unknown key grants nothing. */
        fun byKey(key: String): Audience? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The audience rows somebody on [instanceRole] matches, each capped to what its
 * audience may hold (LNL-202).
 *
 * ── One fold, four callers ──────────────────────────────────────────────────
 *
 * `max(the audience rows the user matches, their own row)` is decided in exactly one
 * place — [AccessControl.effectiveRole] — but the *matching* half is spelled four
 * times, because three other places answer it about a whole directory at once from
 * maps already in hand and must not do a store read per account: the assignable and
 * mentionable sets, a project's Access list, and the instance's People tab. Those
 * were four copies of `filterKeys { instanceRole.atLeast(it.instanceRole) }`, which
 * is fine while there is nothing else to remember and became a place to forget the
 * ceiling the moment [Audience.GUEST] got one. So the filter and the cap are one
 * function, and a fifth caller gets both or neither.
 *
 * Because the instance ladder ascends, "matches" is one comparison: a `guest` row
 * matches everybody, a `member` row matches members and staff, a `staff` row matches
 * staff.
 *
 * @return the matching rows, keyed by audience so a caller can still say *which* row
 *   is carrying the weight — the Access list's "the members row here already gives
 *   Contributor" needs the key, not just the rung.
 */
fun Map<Audience, ProjectRole>.admitting(instanceRole: InstanceRole): Map<Audience, ProjectRole> =
    filterKeys { instanceRole.atLeast(it.instanceRole) }
        .mapValues { (audience, role) -> audience.cap(role) }

/**
 * Whether an account belongs to the deployment's own domain.
 *
 * Stored as `users.kind` and **never written by hand**: it is derived at sign-in
 * by matching the address against the deployment's domain, and re-derived by the
 * startup stamp, so the two agree by construction and an interrupted backfill has
 * nothing to reconcile — running the same rule again is a no-op.
 */
enum class UserKind(val key: String, val instanceRole: InstanceRole) {
    MEMBER("member", InstanceRole.MEMBER),
    STAFF("staff", InstanceRole.STAFF),
    ;

    companion object {
        /** The kind with this [key]; anything unrecognised reads as [MEMBER], the lesser answer. */
        fun byKey(key: String?): UserKind = entries.firstOrNull { it.key == key } ?: MEMBER

        /**
         * The kind an address earns on a deployment whose own domain is [domain].
         *
         * The one place the rule lives, called by sign-in and by the startup stamp
         * alike — which is what makes stamping idempotent rather than a second
         * opinion. A deployment with no domain configured has no way to tell its own
         * people apart from anybody else's, so everybody is a [MEMBER]; the `staff`
         * audience is simply unusable there, which is honest rather than broken —
         * and it is the **default**, because [domain] is unset unless a deployment's
         * `brand.json` names one.
         *
         * [domain] is [InstanceIdentity.domain] and nothing else (LNL-192). It was
         * briefly the brand manifest's `googleHostedDomain`, which is the Google
         * chooser pin and only accidentally the same string; the two are separate
         * fields now, and this one is the only thing the identity field feeds.
         */
        fun forEmail(email: String?, domain: String?): UserKind {
            if (domain.isNullOrBlank() || email.isNullOrBlank()) return MEMBER
            val at = email.lastIndexOf('@')
            if (at < 0) return MEMBER
            return if (email.substring(at + 1).equals(domain.trim(), ignoreCase = true)) STAFF else MEMBER
        }
    }
}

/**
 * Where this account stands on the instance ladder, **as far as its own row knows**.
 *
 * Never [InstanceRole.OWNER], and that is the whole caveat: ownership is
 * `instance_settings.owner_user_id`, which no [UserRecord] carries.
 *
 * ── Which kind of caller this is for (LNL-201) ───────────────────────────────
 *
 * There are two questions that look identical here and are not, and reading this
 * property for the wrong one is the bug LNL-201 fixed:
 *
 *  - **A tier read** — "is this account staff, or a member?" Ownership is orthogonal
 *    to the answer: the owner is *also* staff or a member, and a per-tier count or a
 *    per-tier switch genuinely wants the row. **This property is for those.**
 *  - **An authority read** — "is this caller senior enough to do X?" The owner is
 *    senior to every rung there is, so an authority question answered from the row
 *    alone can never say yes to them. **Never use this property for one.** Ask
 *    [AccessControl.instanceRole], or — where the caller has already read the
 *    settings and is deciding about many accounts at once — [instanceRoleWith].
 *
 * The distinction is reachable rather than theoretical: 33.sqm leaves
 * `instance_role` NULL for everybody *including the seated owner*, so on every
 * migrated volume an authority read here says "member" about the person who owns the
 * deployment, and an ordinary administrator outranks them. That is the ladder
 * inverting, and it inverted in exactly one gate before LNL-201 found it.
 *
 * It exists at all because a tier read is the common one and costs nothing: telling
 * guest from member from staff from administrator — which is every audience match —
 * needs no store read, and paying for one on every permission check to learn a fact
 * that changes once a year would be the wrong trade.
 */
val UserRecord?.storedInstanceRole: InstanceRole
    get() = when {
        this == null -> InstanceRole.GUEST
        isInstanceAdmin -> InstanceRole.ADMIN
        else -> kind.instanceRole
    }

/**
 * The whole instance ladder — ownership included — from a row and an [ownerUserId]
 * the caller has **already** read.
 *
 * [AccessControl.instanceRole] is the answer for one caller resolved from a session,
 * and is what a permission gate asks. This is the same answer for code that is
 * deciding about a *set* of accounts and has the owner's id in hand: the People tab
 * mapping every row to a tier, a recipient picker filtering the directory, a project's
 * Access list. Those must not do a store read per account, and they must not fall back
 * to [storedInstanceRole] either — which is what several of them were doing beside an
 * inline `if (id == ownerUserId)`, three times, in three spellings. This is that line,
 * named once.
 *
 * @param ownerUserId `instance_settings.owner_user_id`, or null on a deployment that
 *   has nobody seated — which is a real state, not a missing read: see 33.sqm on an
 *   instance that never had a system administrator.
 */
fun UserRecord?.instanceRoleWith(ownerUserId: Long?): InstanceRole = when {
    this == null -> InstanceRole.GUEST
    ownerUserId != null && id == ownerUserId -> InstanceRole.OWNER
    else -> storedInstanceRole
}

/**
 * Reads and writes `project_roles` and `project_audience_roles`.
 *
 * @param database the open database.
 */
class RoleStore(
    private val database: LunicleDatabase,
) : se.soderbjorn.lunicle.store.RoleStore {
    override suspend fun roleFor(userId: Long, projectId: Long): ProjectRole? =
        withContext(DatabaseDispatcher) {
            database.rolesQueries.roleFor(userId, projectId).executeAsOneOrNull()
                ?.let { ProjectRole.byKey(it) }
        }

    override suspend fun rolesForUser(userId: Long): Map<Long, ProjectRole> =
        withContext(DatabaseDispatcher) {
            database.rolesQueries.rolesForUser(userId).executeAsList()
                .mapNotNull { row -> ProjectRole.byKey(row.role)?.let { row.project_id to it } }
                .toMap()
        }

    override suspend fun rolesForProject(projectId: Long): Map<Long, ProjectRole> =
        withContext(DatabaseDispatcher) {
            database.rolesQueries.rolesForProject(projectId).executeAsList()
                .mapNotNull { row -> ProjectRole.byKey(row.role)?.let { row.user_id to it } }
                .toMap()
        }

    override suspend fun memberIds(projectId: Long): Set<Long> =
        withContext(DatabaseDispatcher) {
            database.rolesQueries.memberIds(projectId).executeAsList().toSet()
        }

    override suspend fun setRole(userId: Long, projectId: Long, role: ProjectRole?): Unit =
        withContext(DatabaseDispatcher) {
            if (role == null) {
                database.rolesQueries.clearRole(userId, projectId)
            } else {
                database.rolesQueries.setRole(userId, projectId, role.key)
            }
        }

    override suspend fun audienceRoles(projectId: Long): Map<Audience, ProjectRole> =
        withContext(DatabaseDispatcher) {
            database.rolesQueries.audienceRoles(projectId).executeAsList()
                .mapNotNull { row ->
                    val audience = Audience.byKey(row.audience) ?: return@mapNotNull null
                    ProjectRole.byKey(row.role)?.let { audience to it }
                }
                .toMap()
        }

    override suspend fun setAudienceRole(projectId: Long, audience: Audience, role: ProjectRole?): Unit =
        withContext(DatabaseDispatcher) {
            if (role == null) {
                database.rolesQueries.clearAudienceRole(projectId, audience.key)
            } else {
                database.rolesQueries.setAudienceRole(projectId, audience.key, role.key)
            }
        }
}
