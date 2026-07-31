/**
 * The Firestore half of the permission rework — 33.sqm's counterpart (LNL-191).
 *
 * The first real [FirestoreMigration] this codebase has had: the LNL-111 stores
 * wrote the current shape directly, so until now there was no prior shape to move.
 *
 * ── It grants nothing, and that is what makes it safe to interrupt ──────────
 *
 * 33.sqm says at length why no old privilege translates into a new rung, and every
 * word of it applies here. The consequence worth restating in *this* file is the
 * operational one: because nothing is granted, no two writes below have to happen
 * together. There is no cross-collection transaction, there is no ordering that
 * leaves somebody half-privileged, and a run that stops anywhere leaves people
 * with **too little** rather than too much. That is the only failure direction a
 * permission migration is allowed to have.
 *
 * ── Idempotent and resumable, step by step ──────────────────────────────────
 *
 * The runner re-invokes `apply` from the last checkpointed version, so every step
 * has to no-op over the part it already did:
 *
 *  1. [seatOwner] writes the instance owner **first**, while `isSysAdmin` is still
 *     on the user documents, and refuses to overwrite an owner that is already
 *     seated. Everything after it may therefore destroy that field freely.
 *  2. [seatProjectOwners] skips any project that already has a new-shape rung
 *     document, so a re-run does not recompute an answer whose inputs step 3 has
 *     since deleted. This is the one ordering that genuinely matters.
 *  3. [dropOldGrants] deletes the old grant documents, recognised by their
 *     `roleKey` field. Deleting what is not there is a no-op.
 *  4. [dropRoleVocabulary] deletes the near-vestigial `roles` collection, which
 *     described what a role meant back when a role was a set-member rather than a
 *     rung. The descriptions live on the [ProjectRole] enum now.
 *  5. The two [FirestoreBackfill] sweeps carry their own resumability: each stamps
 *     `_sv` per document and skips what it has already stamped.
 *
 * ── What it deliberately does not do ────────────────────────────────────────
 *
 * It does not stamp `users.kind`. That is derived by matching the deployment's own
 * domain, which is configuration a migration cannot see; every account lands on
 * `member`, the lesser answer, and `stampUserKinds` corrects them at boot from the
 * same rule sign-in uses. See InstanceLadder.kt.
 *
 * It does not write a single audience row. `isPublic` would have mapped to
 * `guest → viewer`, and translating it would be granting the entire internet a
 * rung on somebody's board on the strength of a boolean nobody re-consented to.
 *
 * @see FirestoreMigrations
 * @see se.soderbjorn.lunicle.db (33.sqm), the SQLite counterpart
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("FirestorePermissionModelMigration")

/**
 * Version 1: rebuild the permission model, carry the people, grant nothing.
 *
 * @param pageSize documents per page and per batch, injectable so a test can force
 *   the multi-page path without seeding hundreds of documents.
 */
internal class FirestorePermissionModelMigration(
    override val version: Int = 1,
    private val pageSize: Int = FirestoreBackfill.DEFAULT_PAGE_SIZE,
) : FirestoreMigration {

    override suspend fun apply(db: Firestore) {
        seatOwner(db)
        seatProjectOwners(db)
        dropOldGrants(db)
        dropRoleVocabulary(db)
        backfillUsers(db)
        backfillProjects(db)
    }

    /**
     * The system administrator becomes the instance owner — the one grant this
     * migration makes, and the reason an instance comes up able to hand rights back
     * out rather than as a brick with a sign-in page.
     *
     * Runs first, while `isSysAdmin` is still on the documents. Refuses to move an
     * owner that is already seated, which is what makes the re-run a no-op and what
     * stops it from undoing a transfer somebody made deliberately.
     */
    private suspend fun seatOwner(db: Firestore) {
        val settings = db.collection(INSTANCE_SETTINGS).document(SINGLETON)
        val snapshot = settings.get().await()

        @Suppress("UNCHECKED_CAST")
        val values = (snapshot.get(VALUES) as? Map<String, Any?>).orEmpty()
        if (values[OWNER_USER_ID] is Number) return

        // Lowest id, matching 33.sqm's `ORDER BY id LIMIT 1`: on an instance with
        // more than one flagged account the two backends must pick the same person,
        // and "the first one to sign in" is the only tie-break both can express.
        val admin = db.collection(USERS).whereEqualTo(IS_SYS_ADMIN, true).get().await()
            .documents.mapNotNull { it.getLong(ID) }.minOrNull() ?: return

        settings.set(mapOf(VALUES to mapOf(OWNER_USER_ID to admin)), com.google.cloud.firestore.SetOptions.merge()).await()
        logger.info("LNL-191: seated user $admin as the instance owner")
    }

    /**
     * Give every project an Owner, so no board is unadministrable: its old
     * `project_owner` if there is exactly one, else the instance owner.
     *
     * Note what "exactly one" resolves to in practice. 25.sqm granted
     * `project_owner` to the system administrator on every project, and the
     * Firestore backend was seeded from the same rule, so on a real volume the
     * unambiguous old owner **is** the system administrator on nearly every board —
     * both branches land on the same person. The two-branch shape is still right: it
     * is correct where ownership was handed to somebody else, and correct where two
     * people hold it and neither can be picked without inventing a rule.
     *
     * Skips a project that already has a new-shape document, which is what lets this
     * be re-run after [dropOldGrants] has removed its own inputs.
     */
    private suspend fun seatProjectOwners(db: Firestore) {
        val fallback = instanceOwnerId(db)
        val projects = db.collection(FirestoreProjectStore.COLLECTION).get().await().documents
        for (project in projects) {
            val projectId = project.getLong(ID) ?: continue
            // One single-field query per project, which is the same shape every read
            // of this collection takes — see FirestoreRoleStore.
            val existing = db.collection(FirestoreRoleStore.GRANTS)
                .whereEqualTo(FirestoreRoleStore.PROJECT_ID, projectId).get().await().documents
            if (existing.any { it.getString(FirestoreRoleStore.ROLE) != null }) continue

            val oldOwners = existing
                .filter { it.getString(OLD_ROLE_KEY) == OLD_PROJECT_OWNER }
                .mapNotNull { it.getLong(FirestoreRoleStore.USER_ID) }
                .distinct()
            val owner = oldOwners.singleOrNull() ?: fallback ?: continue

            db.collection(FirestoreRoleStore.GRANTS).document("${owner}_$projectId").set(
                mapOf(
                    FirestoreRoleStore.USER_ID to owner,
                    FirestoreRoleStore.PROJECT_ID to projectId,
                    FirestoreRoleStore.ROLE to ProjectRole.OWNER.key,
                ),
            ).await()
        }
    }

    /** The owner just seated, read back rather than threaded, so a re-run sees it too. */
    private suspend fun instanceOwnerId(db: Firestore): Long? {
        val snapshot = db.collection(INSTANCE_SETTINGS).document(SINGLETON).get().await()
        @Suppress("UNCHECKED_CAST")
        val values = (snapshot.get(VALUES) as? Map<String, Any?>).orEmpty()
        return (values[OWNER_USER_ID] as? Number)?.toLong()
    }

    /**
     * Every old grant document, gone — recognised by the `roleKey` field the new
     * shape does not have, so the owner documents step 2 just wrote survive.
     *
     * Paginated with the same document-id cursor [FirestoreBackfill] uses, and for
     * the same reason: it is stable, needs no index, and orders identically on every
     * run, so an interrupted sweep resumes rather than restarts. Deletes cannot go
     * through the backfill helper itself, which merges rather than removes.
     */
    private suspend fun dropOldGrants(db: Firestore) {
        var deleted = 0
        while (true) {
            val page = db.collection(FirestoreRoleStore.GRANTS)
                .orderBy(com.google.cloud.firestore.FieldPath.documentId())
                .limit(pageSize)
                .get().await().documents
            if (page.isEmpty()) break

            val stale = page.filter { it.getString(OLD_ROLE_KEY) != null }
            if (stale.isNotEmpty()) {
                val batch = db.batch()
                stale.forEach { batch.delete(it.reference) }
                batch.commit().await()
                deleted += stale.size
            }
            // Deleting from the front of the ordering means the next page starts
            // where this one did; a page with nothing stale in it is the signal that
            // the front is clean and there is nothing more to remove.
            if (stale.isEmpty()) break
        }
        if (deleted > 0) logger.info("LNL-191: removed $deleted old role grant(s), granting nothing in their place")
    }

    /** The `roles` vocabulary collection, gone: a rung's description is on the enum now. */
    private suspend fun dropRoleVocabulary(db: Firestore) {
        while (true) {
            val page = db.collection(OLD_ROLES).limit(pageSize).get().await().documents
            if (page.isEmpty()) break
            val batch = db.batch()
            page.forEach { batch.delete(it.reference) }
            batch.commit().await()
            if (page.size < pageSize) break
        }
    }

    /**
     * Carry every account forward: address, name, e-mail, the user's own MCP switch,
     * their connected agents. Nobody signs in again.
     *
     * What changes is the shape of the two fields that were privileges. `isSysAdmin`
     * and `mcpAllowed` are deleted outright — the first because being an
     * administrator is a rung now and this migration grants none, the second because
     * per-account MCP permission returns per tier in LNL-192. `createdAt` becomes
     * `addedAt`, a rename with no data movement: what it held was already the moment
     * the row appeared, and what changes is that a row need no longer be the residue
     * of a sign-in.
     *
     * `kind` is written only when absent, so a sign-in that lands mid-sweep and
     * stamps somebody `staff` is not demoted by the page that reaches them after.
     */
    private suspend fun backfillUsers(db: Firestore) {
        FirestoreBackfill(db).run(USERS, version, pageSize) { doc ->
            mapOf(
                KIND to (doc.getString(KIND) ?: UserKind.MEMBER.key),
                INSTANCE_ROLE to null,
                ADDED_AT to (doc.getLong(ADDED_AT) ?: doc.getLong(OLD_CREATED_AT)),
                OLD_CREATED_AT to FieldValue.delete(),
                IS_SYS_ADMIN to FieldValue.delete(),
                OLD_MCP_ALLOWED to FieldValue.delete(),
            )
        }
    }

    /**
     * Project visibility, gone.
     *
     * Not translated into audience rows, for the reason the file preamble gives.
     * Removing the fields rather than leaving them is the point: two answers to "who
     * can see this", only one of them enforced, is exactly the shape of bug the
     * audience table exists to retire.
     */
    private suspend fun backfillProjects(db: Firestore) {
        FirestoreBackfill(db).run(FirestoreProjectStore.COLLECTION, version, pageSize) {
            mapOf(
                OLD_IS_PUBLIC to FieldValue.delete(),
                OLD_VISIBLE_TO_ALL to FieldValue.delete(),
            )
        }
    }

    private companion object {
        const val USERS = "users"
        const val ID = "id"
        const val KIND = "kind"
        const val INSTANCE_ROLE = "instanceRole"
        const val ADDED_AT = "addedAt"
        const val IS_SYS_ADMIN = "isSysAdmin"

        const val INSTANCE_SETTINGS = "instanceSettings"
        const val SINGLETON = "singleton"
        const val VALUES = "values"
        const val OWNER_USER_ID = "owner_user_id"

        // ── The old names, spelled once, here ────────────────────────────────
        //
        // Deliberately literals rather than references to the constants that used to
        // hold them: those constants are gone from the stores, and a migration that
        // followed a live constant would silently change meaning the next time
        // somebody renamed a field. A migration names the past.
        const val OLD_CREATED_AT = "createdAt"
        const val OLD_MCP_ALLOWED = "mcpAllowed"
        const val OLD_ROLE_KEY = "roleKey"
        const val OLD_PROJECT_OWNER = "project_owner"
        const val OLD_ROLES = "roles"
        const val OLD_IS_PUBLIC = "isPublic"
        const val OLD_VISIBLE_TO_ALL = "visibleToAllSignedIn"
    }
}
