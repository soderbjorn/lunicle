/**
 * The permission vocabulary, and who holds what where.
 *
 * This store answers exactly one interesting question — "does this user hold
 * this role in this project?" — and [AccessControl] is the only thing that asks
 * it. Nothing here decides anything; see AccessControl's preamble for where the
 * deciding happens and why it happens in one place.
 *
 * @see AccessControl
 * @see Database
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.clientserver.RoleKeys
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * The roles this instance has, and what each one grants.
 *
 * Hardcoded rather than a table anyone can write to: every value here is
 * branched on by name in [AccessControl], so a role invented at runtime would
 * grant nothing and a renamed one would silently stop granting what it used to.
 * The table exists to *associate* users with these, not to define them.
 *
 * The keys are also wire format — the client receives the caller's roles as
 * these strings to render affordances with. Changing one is a migration.
 */
enum class Role(val key: String, val description: String) {
    /**
     * See this project at all, and nothing else.
     *
     * The second departure from "a role is an ability", after
     * [BE_ASSIGNED_ISSUE], and worth the same paragraph that one got. Visibility
     * is not something you *do*; it is a fact about whether a private project
     * exists as far as you are concerned. It is a role anyway, and for
     * [BE_ASSIGNED_ISSUE]'s reason: the question is per-project, and
     * `project_roles` is still the only thing that holds a per-project fact about
     * a person. A `project_members` table would have been a second answer to a
     * question this one already answers — and one with no grant UI, no wire
     * format, and no tiering in [AccessControl.canGrant].
     *
     * **It is not what membership means.** [AccessControl.canReadProject] asks
     * "holds any role here", not "holds this one". That is the point: someone
     * granted `create_issue` on a private project could not previously see the
     * project they were meant to file issues in, and phrasing membership as
     * "holds something" retires that incoherence instead of leaving a second
     * box every admin must remember to tick alongside the first. This role
     * exists so that a read-only member — somebody who should follow a board and
     * touch nothing on it — has something to hold.
     *
     * Consequently it grants nothing that any other role does not already imply,
     * and that is not the trap [BE_ASSIGNED_ISSUE] documents. Holding only this
     * one is a coherent, useful state; holding only that one is not.
     *
     * Declared first because the privileges table renders in declaration order,
     * and this is the weakest grant there is — every other row in that table
     * implies it. Note the one visible consequence of the position: on a fresh
     * volume the seed writes this row first, so `roles.id` differs from a
     * migrated volume's. Nothing joins on that id from outside this table —
     * `project_roles` is populated through `grant`, which looks the id up by
     * `role_key` — so the two volumes disagree about a number neither reads.
     */
    VIEW_PROJECT(RoleKeys.VIEW_PROJECT, "See this project, without being able to change anything in it."),

    CREATE_ISSUE("create_issue", "Create issues in this project."),
    COMMENT_ON_ISSUE("comment_on_issue", "Post comments on this project's issues."),
    CHANGE_UNOWNED_ISSUES("change_unowned_issues", "Edit issues they did not create."),

    /**
     * Be nameable as an issue's assignee here.
     *
     * The odd one out, and worth saying why it is a role rather than falling out
     * of the three above. The others describe what somebody may *do*; this
     * describes what may be done *to* them — it is the difference between a
     * permission and an eligibility. It is here anyway, because the question it
     * answers is per-project ("who works on this board?") and `project_roles` is
     * the only thing that holds a per-project fact about a person.
     *
     * The consequence of that shape, stated so it is not discovered: holding this
     * grants nothing on its own. Someone with only this role can be handed an
     * issue and then cannot edit it — which is coherent (they can still comment,
     * if they hold that, and the "Assign to me" button still works) but is not
     * what an admin ticking one box may expect. The settings dialog shows the
     * description below; it does not warn.
     */
    BE_ASSIGNED_ISSUE("be_assigned_issue", "Be assigned this project's issues."),

    /**
     * Run this project: its vocabulary, its sprints, and who holds what in it.
     *
     * The per-project half of what used to be one instance-wide flag. A system
     * administrator (`users.is_sys_admin`) administers every project and always
     * did; this is how one person comes to administer *one* board, which the
     * permission vocabulary previously had no way to say. See 11.sqm, which
     * renamed the flag so the two read as distinct.
     *
     * **It implies the other four**, and that is a deliberate departure from how
     * the rest of this enum behaves. Someone who runs a board files issues,
     * comments on them, edits other people's, and gets work assigned to them —
     * requiring five ticked boxes to express "this person runs this project"
     * makes the common grant a checklist, and the one that gets half-done. It
     * also avoids repeating [BE_ASSIGNED_ISSUE]'s trap, documented above: a role
     * that grants nothing on its own is a role people mis-grant.
     *
     * The implication lives in [AccessControl], not in the grant: holding this
     * writes ONE row, and the other four are answered by asking about this one.
     * Expanding it into five rows at grant time would make the bundle a fact
     * about history rather than about the role, so revoking it later would leave
     * four grants nobody chose behind.
     *
     * **What it does not reach.** Renaming or deleting a project, editing its
     * repository, impersonation, MCP backfill authorship, and granting this role
     * or a more senior one. Some of those are instance-wide and stay with the
     * system administrator (see [AccessControl.canMutateProjects]); the rest are
     * this project's own but senior to running it, and belong to [PROJECT_OWNER] —
     * see [AccessControl.canOwnProject] and [AccessControl.canGrant]. A project
     * administrator promoting a peer was once "a different feature, decided rather
     * than inherited"; LNL-107 decided it, and the answer was that promotion is an
     * owner's power, not an administrator's.
     */
    PROJECT_ADMIN("project_admin", "Administer this project: its sprints, its vocabulary and its privileges."),

    /**
     * Own this project outright — everything a system administrator may do *to*
     * it, without being one.
     *
     * The top of the per-project ladder, and the answer LNL-107 gives to the one
     * thing [PROJECT_ADMIN] deliberately could not reach: a project has settings
     * that are not about running the board but about the board's *existence* — its
     * name, its prefix, whether it is public, its linked repository, and whether it
     * goes on existing at all — and until now those stayed with the system
     * administrator because there was no per-project role senior enough to hold
     * them. This is that role.
     *
     * **It implies [PROJECT_ADMIN]**, which implies the other four — so like that
     * one, holding it writes ONE row and every lesser question is answered by
     * asking about this one (see [AccessControl.administers] and
     * [AccessControl.holds]). Someone who owns a board self-evidently also runs it;
     * requiring both rows ticked would be the checklist [PROJECT_ADMIN] already
     * rejected, one rung up.
     *
     * **What it reaches that [PROJECT_ADMIN] does not**, all confined to this one
     * project: renaming and re-prefixing it and flipping its visibility, editing
     * its GitHub configuration, deleting it, and promoting others to
     * [PROJECT_ADMIN] *or* to owner. See [AccessControl.canOwnProject] and
     * [AccessControl.canGrant].
     *
     * **What it still does not reach**, because these are the instance's business
     * and not one project's: bringing a *new* project into existence — there is no
     * project to own before it exists, so creating stays with the system
     * administrator (see [AccessControl.canMutateProjects]) — and everything
     * instance-wide the way impersonation and MCP backfill authorship are. "But
     * not other projects" is not a rule that had to be written: this is a
     * `project_roles` row, scoped to one project like every other role here.
     *
     * On migrating an existing database the system administrator is granted this
     * on every project, so each board has an owner of record from day one; see
     * 25.sqm. That is the only role this instance has ever needed a migration to
     * seat, and only because a grant needs the role row to exist at the moment it
     * is written — the enum row itself still arrives through [RoleStore.seed] like
     * all the others.
     */
    PROJECT_OWNER("project_owner", "Own this project: everything an administrator can do, plus its name, its repository, and deleting it."),
}

/**
 * Reads and writes `roles` and `project_roles`.
 *
 * @param database the open database.
 */
class RoleStore(
    private val database: LunicleDatabase,
) : se.soderbjorn.lunicle.store.RoleStore {
    /**
     * Write the [Role] rows, if they aren't there already.
     *
     * Called unconditionally at startup by `Application.module`, which is a
     * deliberate departure from the schema doc: it put this seed in
     * `1.sqm` *and* in `createOrMigrateSchema()`'s create branch, so that a
     * fresh volume and a purged one both ended up with the rows.
     *
     * That is two places that have to agree forever, and the failure when they
     * drift is silent — an instance where nobody can hold a role, because
     * [hasRole] joins against a table that is simply empty. `INSERT OR IGNORE`
     * (see Roles.sq) makes one unconditional call cover the fresh volume, the
     * purged one, and the one that has been serving for a month. No branch, and
     * nothing to keep in step.
     *
     * @return how many roles the instance now has, for the startup log.
     */
    override suspend fun seed(): Int = withContext(DatabaseDispatcher) {
        database.transaction {
            Role.entries.forEach { database.rolesQueries.seed(it.key, it.description) }
        }
        Role.entries.size
    }

    /**
     * Does [userId] hold [role] in [projectId]?
     *
     * The only question [AccessControl] asks of this table.
     */
    override suspend fun hasRole(userId: Long, projectId: Long, role: Role): Boolean =
        withContext(DatabaseDispatcher) {
            database.rolesQueries.hasRole(userId, projectId, role.key).executeAsOne()
        }

    /**
     * Does [userId] hold anything at all in [projectId]?
     *
     * Membership, which is what [AccessControl.canReadProject] asks once
     * `is_public` has said no. Phrased as "holds something" rather than "holds
     * [Role.VIEW_PROJECT]" on purpose — see that role's doc for why the weaker
     * question is the right one.
     *
     * Its own query rather than `rolesFor(...).isNotEmpty()`, because this one
     * runs on every read of every project: listing projects asks it once per
     * project, and `EXISTS` stops at the first row where `rolesFor` builds a set
     * that the caller then discards.
     */
    override suspend fun isMember(userId: Long, projectId: Long): Boolean =
        withContext(DatabaseDispatcher) {
            database.rolesQueries.isMember(userId, projectId).executeAsOne()
        }

    /**
     * Everyone who holds anything at all in [projectId] — [isMember] turned
     * around, answered as a set.
     *
     * The question [ProjectAudience] asks once `is_public` has said no, and the
     * only thing here that answers about a *project* rather than about a person.
     * It exists because "every user who can see project X" is a set, and there was
     * no way to ask for one: [isMember] answers one pair at a time, and a filter
     * over every account calling it per row is a query per user on the instance to
     * answer something one select knows.
     *
     * Phrased as "holds a row" rather than joined to `roles`, so it agrees exactly
     * with [isMember] — and therefore with [AccessControl.canReadProject]. A
     * definition of membership that narrowed here and not there would be an
     * autocomplete that omits somebody who can read every word of the thread.
     */
    override suspend fun memberIds(projectId: Long): Set<Long> =
        withContext(DatabaseDispatcher) {
            database.rolesQueries.memberIds(projectId).executeAsList().toSet()
        }

    /**
     * Every role [userId] holds in [projectId].
     *
     * For the client's affordances only — one query instead of one per role, so
     * rendering a project's controls costs a single round-trip. Unknown keys
     * are dropped rather than failing the read: a row naming a role this build
     * has never heard of grants nothing, which is the safe reading.
     */
    override suspend fun rolesFor(userId: Long, projectId: Long): Set<Role> =
        withContext(DatabaseDispatcher) {
            database.rolesQueries.rolesFor(userId, projectId).executeAsList()
                .mapNotNull { key -> Role.entries.firstOrNull { it.key == key } }
                .toSet()
        }

    /**
     * Every grant in [projectId], as user id → the roles they hold.
     *
     * For the settings dialog's privileges table, and *only* for it: this is the
     * administrative question "who holds what here", never the permission question
     * "may this caller do that". Those are different questions with different
     * audiences, and the day this map is used to decide a write is the day
     * permissions live in two places. [hasRole] is the one that answers a
     * permission, from the session, one user at a time — see AccessControl.
     *
     * Unknown keys are dropped for [rolesFor]'s reason: a row naming a role this
     * build has never heard of grants nothing, so it is nothing to render either.
     */
    override suspend fun grantsForProject(projectId: Long): Map<Long, Set<Role>> =
        withContext(DatabaseDispatcher) {
            database.rolesQueries.grantsForProject(projectId).executeAsList()
                .mapNotNull { row ->
                    Role.entries.firstOrNull { it.key == row.role_key }?.let { row.user_id to it }
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, roles) -> roles.toSet() }
        }

    /** Grant [role] to [userId] in [projectId]. Idempotent. */
    override suspend fun grant(userId: Long, projectId: Long, role: Role): Unit =
        withContext(DatabaseDispatcher) {
            database.rolesQueries.grant(userId, projectId, role.key)
        }

    /** Take [role] away. Idempotent — revoking what nobody holds is not an error. */
    override suspend fun revoke(userId: Long, projectId: Long, role: Role): Unit =
        withContext(DatabaseDispatcher) {
            database.rolesQueries.revoke(userId, projectId, role.key)
        }
}
