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
    CREATE_ISSUE("create_issue", "Create issues in this project."),
    COMMENT_ON_ISSUE("comment_on_issue", "Post comments on this project's issues."),
    CHANGE_UNOWNED_ISSUES("change_unowned_issues", "Edit issues they did not create."),
}

/**
 * Reads and writes `roles` and `project_roles`.
 *
 * @param database the open database.
 */
class RoleStore(
    private val database: LunicleDatabase,
) {
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
    suspend fun seed(): Int = withContext(DatabaseDispatcher) {
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
    suspend fun hasRole(userId: Long, projectId: Long, role: Role): Boolean =
        withContext(DatabaseDispatcher) {
            database.rolesQueries.hasRole(userId, projectId, role.key).executeAsOne()
        }

    /**
     * Every role [userId] holds in [projectId].
     *
     * For the client's affordances only — one query instead of one per role, so
     * rendering a project's controls costs a single round-trip. Unknown keys
     * are dropped rather than failing the read: a row naming a role this build
     * has never heard of grants nothing, which is the safe reading.
     */
    suspend fun rolesFor(userId: Long, projectId: Long): Set<Role> =
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
    suspend fun grantsForProject(projectId: Long): Map<Long, Set<Role>> =
        withContext(DatabaseDispatcher) {
            database.rolesQueries.grantsForProject(projectId).executeAsList()
                .mapNotNull { row ->
                    Role.entries.firstOrNull { it.key == row.role_key }?.let { row.user_id to it }
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, roles) -> roles.toSet() }
        }

    /** Grant [role] to [userId] in [projectId]. Idempotent. */
    suspend fun grant(userId: Long, projectId: Long, role: Role): Unit =
        withContext(DatabaseDispatcher) {
            database.rolesQueries.grant(userId, projectId, role.key)
        }

    /** Take [role] away. Idempotent — revoking what nobody holds is not an error. */
    suspend fun revoke(userId: Long, projectId: Long, role: Role): Unit =
        withContext(DatabaseDispatcher) {
            database.rolesQueries.revoke(userId, projectId, role.key)
        }
}
