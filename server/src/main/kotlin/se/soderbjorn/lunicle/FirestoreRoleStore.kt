/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.RoleStore] — who
 * stands where, over documents.
 *
 * ── Two shapes, because there are two tables ────────────────────────────────
 *
 * **A person's rung in a project** is one document at
 * `projectRoles/{userId}_{projectId}`, whose id is the pair it is about. That id is
 * doing the work `PRIMARY KEY (user_id, project_id)` does on the SQLite side: there
 * is nowhere to put a second rung for the same pair, so "one row per person per
 * project" is structural here too rather than something the store remembers to
 * enforce. [setRole] is an idempotent `set` on that key, clearing is a delete of it
 * (a delete of a missing document is a no-op), and [roleFor] is a single get — no
 * query, and no index.
 *
 * The document also carries `userId` and `projectId` as fields, which looks
 * redundant against the id and is not: it is what makes both directions a
 * **single-field equality** an automatic index already serves. [rolesForUser]
 * filters on `userId`, [rolesForProject] and [memberIds] on `projectId`, so this
 * store needs **no composite index** and no scan of another collection.
 *
 * That is a deliberate reading of "a person's rungs must be readable in one go":
 * what matters is that answering it is one round trip rather than a get per
 * project, and a single-field query is one round trip. The alternative shape — a
 * `{projectId: rung}` map on the user document — is also one read *in that
 * direction*, and turns every project-scoped question ("who is on this board?",
 * which the settings dialog and [ProjectAudience] both ask) into a scan of every
 * account on the instance, because Firestore cannot query a map by a key it does
 * not know in advance. One collection, indexed both ways, is one go in both
 * directions.
 *
 * **A project's audience rows** live on the project document itself, in an
 * `audienceRoles` map of audience key → rung key. There are at most three, they are
 * read whenever the project's own row is being decided about, and a collection of
 * their own would be a join on every permission check. This store reaches into
 * `projects/{id}` to read and write that one field, which is the one place it
 * touches a document it does not own — stated here because it is exactly the kind
 * of reach that is invisible from the other side. See [FirestoreProjectStore] and
 * its `AUDIENCE_ROLES` constant, which names the same field.
 *
 * ── There are no numeric ids to allocate ────────────────────────────────────
 *
 * Unlike most Firestore stores this one uses no [FirestoreCounters]: a rung is
 * addressed by its natural (user, project) key, so nothing here mints a `Long`.
 *
 * @see FirestoreProvider
 * @see se.soderbjorn.lunicle.store.RoleStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import se.soderbjorn.lunicle.store.RoleStore

class FirestoreRoleStore(
    private val firestore: Firestore,
) : RoleStore {
    private fun grants() = firestore.collection(GRANTS)

    private fun projectDoc(projectId: Long) =
        firestore.collection(FirestoreProjectStore.COLLECTION).document(projectId.toString())

    private fun grantKey(userId: Long, projectId: Long) = "${userId}_$projectId"

    /** One get by the pair's natural key — no query. An unknown rung reads as null. */
    override suspend fun roleFor(userId: Long, projectId: Long): ProjectRole? =
        grants().document(grantKey(userId, projectId)).get().await()
            .getString(ROLE)?.let { ProjectRole.byKey(it) }

    /** One single-field query on `userId`. See the class preamble on why this is one go. */
    override suspend fun rolesForUser(userId: Long): Map<Long, ProjectRole> =
        grants().whereEqualTo(USER_ID, userId).get().await()
            .documents.mapNotNull { doc ->
                val projectId = doc.getLong(PROJECT_ID) ?: return@mapNotNull null
                val role = doc.getString(ROLE)?.let { ProjectRole.byKey(it) } ?: return@mapNotNull null
                projectId to role
            }
            .toMap()

    /** One single-field query on `projectId`. */
    override suspend fun rolesForProject(projectId: Long): Map<Long, ProjectRole> =
        grants().whereEqualTo(PROJECT_ID, projectId).get().await()
            .documents.mapNotNull { doc ->
                val userId = doc.getLong(USER_ID) ?: return@mapNotNull null
                val role = doc.getString(ROLE)?.let { ProjectRole.byKey(it) } ?: return@mapNotNull null
                userId to role
            }
            .toMap()

    /**
     * Everyone with an own row here.
     *
     * Deliberately **not** `rolesForProject(projectId).keys`: a document naming a
     * rung this build has never heard of is still somebody with a row, and dropping
     * them from the membership set would make this disagree with SQLite's
     * `SELECT user_id FROM project_roles`, which joins nothing and drops nobody.
     */
    override suspend fun memberIds(projectId: Long): Set<Long> =
        grants().whereEqualTo(PROJECT_ID, projectId).get().await()
            .documents.mapNotNull { it.getLong(USER_ID) }.toSet()

    /** Idempotent: same key, same document. Null deletes it, which is not an error if it is absent. */
    override suspend fun setRole(userId: Long, projectId: Long, role: ProjectRole?) {
        val doc = grants().document(grantKey(userId, projectId))
        if (role == null) {
            doc.delete().await()
        } else {
            doc.set(mapOf(USER_ID to userId, PROJECT_ID to projectId, ROLE to role.key)).await()
        }
    }

    /**
     * The rungs [projectId] hands to whole audiences, off the project document.
     *
     * A missing document, a missing field, an unrecognised audience key and an
     * unrecognised rung key all read as "not admitted", which is the safe direction
     * for every one of them: the failure mode of guessing here is letting somebody
     * in, and there is no guess that keeps somebody out by mistake that is worse.
     */
    override suspend fun audienceRoles(projectId: Long): Map<Audience, ProjectRole> {
        val snapshot = projectDoc(projectId).get().await()
        @Suppress("UNCHECKED_CAST")
        val values = (snapshot.get(FirestoreProjectStore.AUDIENCE_ROLES) as? Map<String, Any?>).orEmpty()
        return values.mapNotNull { (key, value) ->
            val audience = Audience.byKey(key) ?: return@mapNotNull null
            val role = (value as? String)?.let { ProjectRole.byKey(it) } ?: return@mapNotNull null
            audience to role
        }.toMap()
    }

    /**
     * Set (or clear) one audience row, leaving the other two alone.
     *
     * A merge that writes a single map entry, exactly as [FirestoreUiSettingsStore]
     * and [FirestoreInstanceSettingsStore] do — the document-model equivalent of a
     * single-row `ON CONFLICT`. Clearing writes a null rather than removing the key,
     * which reads back identically through the `as? String` above; one write path
     * instead of a delete branch only this backend would have.
     */
    override suspend fun setAudienceRole(projectId: Long, audience: Audience, role: ProjectRole?) {
        projectDoc(projectId).set(
            mapOf(FirestoreProjectStore.AUDIENCE_ROLES to mapOf(audience.key to role?.key)),
            SetOptions.merge(),
        ).await()
    }

    internal companion object {
        const val GRANTS = "projectRoles"

        const val USER_ID = "userId"
        const val PROJECT_ID = "projectId"
        const val ROLE = "role"
    }
}
