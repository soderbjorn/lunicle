/**
 * The persistence seam for the permission vocabulary, and who holds what where.
 *
 * One of the LNL-111 domain store interfaces. The reference implementation is
 * today's SQLite [se.soderbjorn.lunicle.RoleStore] (named by its fully-qualified
 * name in that class's supertype clause, since the two share a simple name), which
 * reads and writes `roles` and `project_roles`. [AccessControl] is the only thing
 * that asks the interesting question — "does this user hold this role in this
 * project?"; nothing here decides anything.
 *
 * The [se.soderbjorn.lunicle.Role] enum — the vocabulary itself — is a fact about
 * this build and lives beside the SQLite store, not here: a role invented at
 * runtime would grant nothing, because [AccessControl] branches on each one by
 * name. This seam only *associates* users with those roles, per project, and
 * answers a handful of questions about the grants.
 *
 * The grants are (userId, projectId, role) tuples, modelled so that [hasRole],
 * [isMember], [memberIds], [rolesFor] and [grantsForProject] are each a
 * single-collection read whichever backend answers them.
 *
 * @see se.soderbjorn.lunicle.store.RoleStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.Role

interface RoleStore {
    /**
     * Write the role vocabulary, if it is not there already. Idempotent, so it may
     * run unconditionally at startup whatever state the datastore is in.
     *
     * @return how many roles the instance now has, for the startup log.
     */
    suspend fun seed(): Int

    /**
     * Does [userId] hold [role] in [projectId]? The only question [AccessControl]
     * asks of this seam.
     */
    suspend fun hasRole(userId: Long, projectId: Long, role: Role): Boolean

    /**
     * Does [userId] hold anything at all in [projectId]?
     *
     * Membership — "holds something here", not "holds a particular role" — which is
     * what [AccessControl.canReadProject] asks once `is_public` has said no.
     */
    suspend fun isMember(userId: Long, projectId: Long): Boolean

    /** Everyone who holds anything at all in [projectId] — [isMember] turned around, as a set. */
    suspend fun memberIds(projectId: Long): Set<Long>

    /**
     * Every role [userId] holds in [projectId], for the client's affordances only.
     * A grant naming a role this build has never heard of is dropped, not failed.
     */
    suspend fun rolesFor(userId: Long, projectId: Long): Set<Role>

    /**
     * Every grant in [projectId], as user id → the roles they hold — for the
     * settings dialog's privileges table, and never to decide a permission. Unknown
     * role keys are dropped, as in [rolesFor].
     */
    suspend fun grantsForProject(projectId: Long): Map<Long, Set<Role>>

    /** Grant [role] to [userId] in [projectId]. Idempotent. */
    suspend fun grant(userId: Long, projectId: Long, role: Role)

    /** Take [role] away. Idempotent — revoking what nobody holds is not an error. */
    suspend fun revoke(userId: Long, projectId: Long, role: Role)
}
