/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.RoleStore] — the
 * permission vocabulary, and who holds what where, over documents.
 *
 * ── Document model ──────────────────────────────────────────────────────────
 *
 * A grant is a *document's presence*, exactly as `project_roles` is a *row's
 * presence* in SQLite — there is no "granted = 0". One document per (user, project,
 * role) grant in `projectRoles/{userId}_{projectId}_{roleKey}`, so [grant] is an
 * idempotent `set` on a deterministic key, [revoke] a delete of it (a delete of a
 * missing document is a no-op), and [hasRole] a single get by that key — no query
 * at all.
 *
 * Every grant document carries four fields — `userId`, `projectId`, `roleKey`, and
 * the denormalised `userProjectId` (`"{userId}_{projectId}"`) — chosen so that
 * *all* the read queries are single-field equalities an automatic index serves, and
 * this store needs **no composite index**:
 *  - [isMember] and [rolesFor] filter on `userProjectId` (one field), not on the
 *    `userId AND projectId` pair that would have needed a composite index;
 *  - [memberIds] and [grantsForProject] filter on `projectId` (one field).
 *
 * The [se.soderbjorn.lunicle.Role] vocabulary is a fact about this build, not data:
 * a grant stores the role's *key* and [rolesFor]/[grantsForProject] map it back
 * through [Role.entries], dropping a key this build has never heard of — the same
 * reading the SQLite store's `mapNotNull` takes of an unknown `role_key`.
 *
 * ── seed() ──────────────────────────────────────────────────────────────────
 *
 * The SQLite `seed` writes the `roles` table so `grant`'s `role_key → role_id`
 * join has something to hit. This backend needs no such join — a grant stores the
 * key directly — so [seed] here is near-vestigial, but it stays faithful to the
 * seam: it idempotently writes a `roles/{roleKey}` document per role (a batch
 * `set`, so re-running it changes nothing) and returns the vocabulary size, for the
 * startup log, exactly as the reference does.
 *
 * ── There are no numeric ids to allocate ────────────────────────────────────
 *
 * Unlike most Firestore stores this one uses no [FirestoreCounters]: a grant is
 * addressed by its natural (user, project, role) key and a role by its enum key, so
 * nothing here mints a `Long`.
 *
 * @see FirestoreProvider
 * @see se.soderbjorn.lunicle.store.RoleStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.store.RoleStore

class FirestoreRoleStore(
    private val firestore: Firestore,
) : RoleStore {
    private fun grants() = firestore.collection(GRANTS)
    private fun roles() = firestore.collection(ROLES)

    private fun grantKey(userId: Long, projectId: Long, role: Role) = "${userId}_${projectId}_${role.key}"
    private fun userProjectKey(userId: Long, projectId: Long) = "${userId}_$projectId"

    /**
     * Idempotently write the role vocabulary and report its size.
     *
     * A batch `set` of one `roles/{roleKey}` document per [Role], so running it on
     * every startup — fresh datastore or one that has served for a month — writes
     * the same documents and changes nothing, the document form of the reference's
     * `INSERT OR IGNORE`.
     */
    override suspend fun seed(): Int {
        val batch = firestore.batch()
        Role.entries.forEach { batch.set(roles().document(it.key), mapOf(ROLE_KEY to it.key, DESCRIPTION to it.description)) }
        batch.commit().await()
        return Role.entries.size
    }

    /** Does [userId] hold [role] in [projectId]? One get by the grant's natural key — no query. */
    override suspend fun hasRole(userId: Long, projectId: Long, role: Role): Boolean =
        grants().document(grantKey(userId, projectId, role)).get().await().exists()

    /** Does [userId] hold anything at all in [projectId]? A single-field equality on the denormalised pair key. */
    override suspend fun isMember(userId: Long, projectId: Long): Boolean =
        grants().whereEqualTo(USER_PROJECT_ID, userProjectKey(userId, projectId)).limit(1)
            .get().await().documents.isNotEmpty()

    /** Everyone holding anything in [projectId], deduplicated across the roles a person may hold. */
    override suspend fun memberIds(projectId: Long): Set<Long> =
        grants().whereEqualTo(PROJECT_ID, projectId).get().await()
            .documents.mapNotNull { it.getLong(USER_ID) }.toSet()

    /** Every role [userId] holds in [projectId], unknown keys dropped. One single-field query. */
    override suspend fun rolesFor(userId: Long, projectId: Long): Set<Role> =
        grants().whereEqualTo(USER_PROJECT_ID, userProjectKey(userId, projectId)).get().await()
            .documents.mapNotNull { doc -> doc.getString(ROLE_KEY)?.let { key -> Role.entries.firstOrNull { it.key == key } } }
            .toSet()

    /** Every grant in [projectId], as user id → their roles, unknown keys dropped. */
    override suspend fun grantsForProject(projectId: Long): Map<Long, Set<Role>> =
        grants().whereEqualTo(PROJECT_ID, projectId).get().await()
            .documents.mapNotNull { doc ->
                val userId = doc.getLong(USER_ID) ?: return@mapNotNull null
                val role = doc.getString(ROLE_KEY)?.let { key -> Role.entries.firstOrNull { it.key == key } }
                    ?: return@mapNotNull null
                userId to role
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, roles) -> roles.toSet() }

    /** Grant [role] to [userId] in [projectId]. Idempotent — same key, same document. */
    override suspend fun grant(userId: Long, projectId: Long, role: Role) {
        grants().document(grantKey(userId, projectId, role)).set(
            mapOf(
                USER_ID to userId,
                PROJECT_ID to projectId,
                ROLE_KEY to role.key,
                USER_PROJECT_ID to userProjectKey(userId, projectId),
            ),
        ).await()
    }

    /** Take [role] away. Idempotent — a delete of a missing document is a no-op. */
    override suspend fun revoke(userId: Long, projectId: Long, role: Role) {
        grants().document(grantKey(userId, projectId, role)).delete().await()
    }

    private companion object {
        const val GRANTS = "projectRoles"
        const val ROLES = "roles"

        const val USER_ID = "userId"
        const val PROJECT_ID = "projectId"
        const val ROLE_KEY = "roleKey"
        const val USER_PROJECT_ID = "userProjectId"
        const val DESCRIPTION = "description"
    }
}
