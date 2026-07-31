/**
 * The delete cascade Firestore does not have — the walk that takes an issue's or a
 * project's contents with it, written by hand because a document store has no
 * foreign keys to declare it with.
 *
 * ── Why this file exists at all ─────────────────────────────────────────────
 *
 * SQLite gets its cascade for free: the schema declares `ON DELETE CASCADE`, the app
 * enables `PRAGMA foreign_keys`, and deleting an issue row takes its comments,
 * events, subscriptions and attachments with it. `ForeignKeyTest` pins that. Firestore
 * has none of it — deleting a document deletes exactly that document — so every
 * cascade the schema states declaratively has to be *performed*, and something has to
 * know the whole shape of what hangs off what.
 *
 * That knowledge is here, in one list, deliberately. Spreading it across eleven
 * stores — each quietly deleting its own collection — is what made LNL-177 possible
 * in the first place: there was no single place a reader could look and ask "what
 * does deleting a project take with it?", so nobody noticed the answer was "the
 * project document, and nothing else". [projectCascade] is that place now. A
 * collection added to the product and forgotten here is a leak, and the only defence
 * against that is that the list is short enough to read.
 *
 * ── What is deliberately NOT cascaded ───────────────────────────────────────
 *
 * The specification is SQLite's schema, not intuition, and two things that look like
 * they should be swept are not — because the reference backend does not sweep them
 * either, and a cascade here that SQLite lacks is a divergence, not a fix:
 *
 *  - **Notifications.** `Notifications.sq` gives `dest_issue_id`, `dest_project_id`
 *    and their siblings no foreign key on purpose: a notification pointing at a
 *    deleted issue is *stale*, the click finds nothing and the client says so, and
 *    cascading would delete a row whose whole value is being a record that something
 *    happened. Notifications cascade on `user_id` — that is account deletion, a
 *    different event entirely.
 *  - **Read marks.** There is no such thing as an issue read mark; `Reads.sq` has
 *    only `conversation_reads` and `forum_reads`. Forum reads *are* swept, but under
 *    the project cascade, via the forums they hang off.
 *
 * ── The 500-write batch limit ───────────────────────────────────────────────
 *
 * Firestore commits at most 500 writes per batch. An issue's contents never approach
 * it, but a project's routinely will — a few hundred issues with their comments and
 * history is thousands of documents — so every delete here goes through [deleteAll],
 * which chunks. Nothing in this file assumes a collection is small.
 *
 * Each query is a **single-field equality**, served by Firestore's automatic
 * single-field indexes, so this file adds no composite index requirement — the
 * property LNL-122 tracks and [FirestoreAttachmentStore] explains at length.
 *
 * ── Not atomic, and why that is the right failure ───────────────────────────
 *
 * A project cascade is far too large for one transaction (500 writes, again), so it
 * cannot be all-or-nothing. It is ordered instead: **contents before container**, so
 * a crash mid-walk leaves the project document still present with some of its
 * contents already gone. That is a project that looks partly emptied and can be
 * deleted again — the delete is idempotent, every step being "delete what matches
 * this id" — rather than the alternative, a vanished project whose contents are
 * unreachable garbage. Re-running finishes the job.
 *
 * @see FirestoreAttachmentStore the same pattern at one collection's scale, and the
 *   long explanation of why the ancestry is denormalised onto the document.
 * @see se.soderbjorn.lunicle.store.ProjectProvisioningContract where both backends
 *   are held to the same emptiness afterwards.
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.CollectionReference
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.QueryDocumentSnapshot

/** Firestore's hard ceiling on writes in one batched commit. */
private const val BATCH_LIMIT = 500

/**
 * Delete every document in [collection] whose [field] equals [value], in batches of
 * at most [BATCH_LIMIT].
 *
 * The one primitive this file is built from. A single-field equality query, so no
 * composite index; chunked, so no collection size is unsafe; a no-op when nothing
 * matches, so every caller can name a collection the data may never have used.
 */
internal suspend fun deleteWhere(collection: CollectionReference, field: String, value: Long) {
    val doomed = collection.whereEqualTo(field, value).get().await().documents
    deleteAll(collection.firestore, doomed)
}

/**
 * The ids of every document in [collection] whose [field] equals [value], read from
 * the `id` field rather than the document name.
 *
 * The cascade's navigation step: a project's issues have to be *named* before their
 * comments can be swept, because a comment carries only its `issueId`. Reading the
 * `id` field keeps this agreeing with the stores, which all write the id as a field
 * as well as using it as the document name.
 */
internal suspend fun idsWhere(collection: CollectionReference, field: String, value: Long): List<Long> =
    collection.whereEqualTo(field, value).get().await().documents.mapNotNull { it.getLong("id") }

/** Delete [docs] in batches of at most [BATCH_LIMIT]. A no-op when empty. */
internal suspend fun deleteAll(firestore: Firestore, docs: List<QueryDocumentSnapshot>) {
    docs.chunked(BATCH_LIMIT).forEach { chunk ->
        val batch = firestore.batch()
        chunk.forEach { batch.delete(it.reference) }
        batch.commit().await()
    }
}

/**
 * Delete everything under one issue except the issue document itself — its comments,
 * its history, and the watches on it.
 *
 * The attachment rows are *not* here: they are swept by
 * [FirestoreAttachmentStore.deleteForIssue], which the caller runs first so it can
 * read the storage keys before the rows naming them go. Splitting it that way keeps
 * the "read the keys, drop the rows, unlink the files" order that
 * `IssueRepository.delete` states once for both backends.
 *
 * Used by the project cascade, where an issue's contents must go without the issue
 * store's own delete path running per issue. `IssueRepository.delete` reaches the same
 * three sweeps through the store interfaces instead, because it is backend-agnostic
 * and must call them on SQLite too.
 *
 * **The issue's relations are not here either, and that is the same split** (LNL-215).
 * Deleting one issue takes its links through
 * [se.soderbjorn.lunicle.store.IssueRelationStore.deleteForIssue], which
 * `IssueRepository.delete` calls on both backends — so the single-issue path is
 * covered where every other backend-agnostic sweep is covered. The project path takes
 * them in [projectCascade] with one query on `projectId` instead of two per issue:
 * relations are the one child collection that carries the project id as well as the
 * issue ids, precisely so the whole project's can go in a single sweep. Running them
 * here as well would double this function's query count on the one path that already
 * has an answer.
 */
internal suspend fun issueContentsCascade(firestore: Firestore, issueId: Long) {
    deleteWhere(firestore.collection(FirestoreCommentStore.COLLECTION), FirestoreCommentStore.ISSUE_ID, issueId)
    deleteWhere(firestore.collection(FirestoreIssueEventStore.COLLECTION), FirestoreIssueEventStore.ISSUE_ID, issueId)
    deleteWhere(firestore.collection(FirestoreSubscriptionStore.ISSUE_UPDATE), FirestoreSubscriptionStore.TARGET_ID, issueId)
}

/**
 * Delete everything under one project except the project document itself.
 *
 * **The list.** A project owns, directly or transitively:
 *
 *  1. its **issues**, and under each of those its comments, history and watches;
 *  2. its **relations** — every link between two of its issues (LNL-215);
 *  3. its **forums**, and under each of those its posts, those posts' comments, the
 *     forum's read marks, and the watches on both forum and post;
 *  4. its **vocabulary** — labels, components, statuses, priorities, resolutions,
 *     sprints, versions and relation kinds, all eight kinds in the one `vocabulary`
 *     collection, so one query takes them all;
 *  5. its **role grants**;
 *  6. its **new-issue watches**;
 *  7. its **statistics snapshot**.
 *
 * Anything the product grows that hangs off a project belongs on that list. There is
 * no cascade to catch an omission; a forgotten collection is silent, permanent
 * garbage.
 *
 * **Attachments are the one child not swept here**, and deliberately: their rows have
 * to go between the key read and the file unlink, so `ProjectProvisioning.delete`
 * sequences all three itself through [se.soderbjorn.lunicle.store.AttachmentStore.deleteForProject]
 * — the same split [FirestoreAttachmentStore.deleteForIssue] has at issue level, and
 * for the same reason: once the rows are gone nothing can name the files. Getting
 * that order wrong leaves the bucket holding every attachment of every deleted
 * project, unidentifiable.
 *
 * **Order.** Contents before container, all the way down: an issue's comments before
 * the issue, a forum's posts before the forum, everything before the project document
 * the caller deletes afterwards. See the file preamble on why a crash mid-walk is the
 * survivable failure and the reverse order is not.
 */
internal suspend fun projectCascade(firestore: Firestore, projectId: Long) {
    // ── 1. Issues, each with its own contents ────────────────────────────────
    val issues = firestore.collection(FirestoreIssueStore.COLLECTION)
    val issueIds = idsWhere(issues, FirestoreIssueStore.PROJECT_ID, projectId)
    issueIds.forEach { issueContentsCascade(firestore, it) }
    deleteWhere(issues, FirestoreIssueStore.PROJECT_ID, projectId)

    // ── 2. Every link between two of those issues (LNL-215) ──────────────────
    // One query rather than two per issue, which is what `issue_relations` carries a
    // project id *for* — see IssueRelations.sq. It also catches a link whose issue
    // document has somehow already gone, which the per-issue sweep by definition
    // cannot: a relation naming a deleted issue is exactly the garbage this walk
    // exists to stop.
    deleteWhere(
        firestore.collection(FirestoreIssueRelationStore.COLLECTION),
        FirestoreIssueRelationStore.PROJECT_ID,
        projectId,
    )

    // ── 3. Forums → posts → comments, and the reads and watches on them ──────
    val forums = firestore.collection(FirestoreForumStore.COLLECTION)
    val posts = firestore.collection(FirestoreForumPostStore.COLLECTION)
    val postComments = firestore.collection(FirestoreForumCommentStore.COLLECTION)
    val forumIds = idsWhere(forums, FirestoreForumStore.PROJECT_ID, projectId)
    forumIds.forEach { forumId ->
        idsWhere(posts, FirestoreForumPostStore.FORUM, forumId).forEach { postId ->
            deleteWhere(postComments, FirestoreForumCommentStore.POST, postId)
            deleteWhere(
                firestore.collection(FirestoreSubscriptionStore.FORUM_POST),
                FirestoreSubscriptionStore.TARGET_ID,
                postId,
            )
        }
        deleteWhere(posts, FirestoreForumPostStore.FORUM, forumId)
        deleteWhere(
            firestore.collection(FirestoreSubscriptionStore.FORUM_NEW_POST),
            FirestoreSubscriptionStore.TARGET_ID,
            forumId,
        )
        deleteWhere(
            firestore.collection(FirestoreReadStore.FORUM_READS),
            FirestoreReadStore.CONTAINER_ID,
            forumId,
        )
    }
    deleteWhere(forums, FirestoreForumStore.PROJECT_ID, projectId)

    // ── 4. The whole board vocabulary, all eight kinds in one collection ─────
    // Relation kinds ride along here for free precisely because they share the
    // collection rather than owning one; see FirestoreIssueRelationKindStore.
    deleteWhere(
        firestore.collection(FirestoreVocabularyStore.COLLECTION),
        FirestoreVocabularyStore.PROJECT_ID,
        projectId,
    )

    // ── 5-7. Role grants, project-level watches, the statistics snapshot ─────
    deleteWhere(firestore.collection(FirestoreRoleStore.GRANTS), FirestoreRoleStore.PROJECT_ID, projectId)
    deleteWhere(
        firestore.collection(FirestoreSubscriptionStore.PROJECT_NEW_ISSUE),
        FirestoreSubscriptionStore.TARGET_ID,
        projectId,
    )
    // Keyed by project id as its document name, not a field — so a delete, not a query.
    firestore.collection(FirestoreStatisticsStore.SNAPSHOTS).document(projectId.toString()).delete().await()
}
