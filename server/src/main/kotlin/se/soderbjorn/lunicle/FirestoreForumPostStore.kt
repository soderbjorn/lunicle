/**
 * The Firestore implementations of [se.soderbjorn.lunicle.store.ForumPostStore] and
 * [se.soderbjorn.lunicle.store.ForumCommentStore] — a forum's posts and the flat
 * comments on them, part of the LNL-117 collaboration fan-out.
 *
 * Kept in one file for the reason the interfaces are (see `ForumPostStore.kt`): a
 * comment reaches its forum only through its post, and [FirestoreForumPostStore
 * .forForum] must read the comment collection to count replies, so the two share
 * the field constants at the foot of this file.
 *
 * ── Document model ──────────────────────────────────────────────────────────
 *
 * Two flat top-level collections rather than subcollections: `forumPosts/{id}` with
 * a denormalised `forumId`, and `forumComments/{id}` with a denormalised `postId`.
 * Ids are the global `Long`s the system addresses these by, from
 * `_counters/forumPosts` and `_counters/forumComments` (see [FirestoreCounters]).
 * The flat-collection-with-parent-ref shape is the one [FirestoreIssueStore] set for
 * a project's issues: "this forum's posts" and "this post's comments" are each a
 * single equality query, and — because the draft filter and the ordering are both
 * applied in memory — neither read needs a composite index.
 *
 * ── The draft dance, visibility and ordering ────────────────────────────────
 *
 * A post/comment is born a draft ([insertDraft], `isDraft = true`, empty title/body)
 * so an inline upload has an owner, and [publish] is the single write that makes it
 * visible — exactly as [FirestoreIssueStore] models an issue draft. [forForum] and
 * [forPost] fetch the container's rows in one query, drop the drafts in memory, and
 * sort in memory: posts **newest-first** (by `createdAt`, ties broken by id),
 * comments **oldest-first**. Sorting in memory is what keeps the equality query
 * index-free; the SQLite store gets the same order from an `ORDER BY`.
 *
 * [FirestoreForumPostStore.forForum] additionally counts each post's published
 * comments and finds its most recent one, one comment query per post — the
 * document-model stand-in for the SQLite `forForum` join. It is the only place a
 * post read touches the comment collection, and the contract does not lean on the
 * count, but computing it keeps the listing at parity with SQLite.
 *
 * No composite index is required by either store.
 *
 * @see FirestoreProvider
 * @see FirestoreCounters
 * @see se.soderbjorn.lunicle.store.ForumPostStoreContract
 * @see se.soderbjorn.lunicle.store.ForumCommentStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore

class FirestoreForumPostStore(
    private val firestore: Firestore,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.ForumPostStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(POSTS_COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    /** Create the hidden draft row an inline upload can hang off; returns its id. */
    override suspend fun insertDraft(
        forumId: Long,
        author: Author,
        createdAt: Long?,
        agentName: String?,
    ): Long {
        val timestamp = createdAt ?: now()
        return firestore.runTransaction { txn ->
            val id = counters.next(txn, POSTS_COUNTER).getValue(POSTS_COUNTER)
            txn.set(
                doc(id),
                mapOf(
                    ID to id,
                    FORUM_ID to forumId,
                    TITLE to "",
                    BODY to "",
                    CREATED_AT to timestamp,
                    CREATED_BY to author.accountId,
                    CREATED_BY_EXTERNAL to author.externalName,
                    AGENT_NAME to agentName,
                    IS_DRAFT to true,
                ),
            )
            id
        }.await()
    }

    override suspend fun publish(id: Long, title: String, body: String) {
        doc(id).update(mapOf(TITLE to title, BODY to body, IS_DRAFT to false)).await()
    }

    override suspend fun updateBody(id: Long, body: String) {
        doc(id).update(mapOf(BODY to body)).await()
    }

    override suspend fun edit(
        id: Long,
        title: String,
        body: String,
        createdAt: Long,
        author: Author,
        agentName: String?,
    ) {
        doc(id).update(
            mapOf(
                TITLE to title,
                BODY to body,
                CREATED_AT to createdAt,
                CREATED_BY to author.accountId,
                CREATED_BY_EXTERNAL to author.externalName,
                AGENT_NAME to agentName,
            ),
        ).await()
    }

    override suspend fun delete(id: Long) {
        doc(id).delete().await()
    }

    override suspend fun findById(id: Long): ForumPostRecord? =
        doc(id).get().await().takeIf { it.exists() }?.toPostRecord()

    override suspend fun findByIdInForum(id: Long, forumId: Long): ForumPostRecord? =
        findById(id)?.takeIf { it.forumId == forumId }

    /** A forum's published posts, newest first, each with its comment count. */
    override suspend fun forForum(forumId: Long): List<ForumPostListing> {
        val posts = collection()
            .whereEqualTo(FORUM_ID, forumId)
            .get().await()
            .documents.map { it.toPostRecord() }
            .filter { !it.isDraft }
            .sortedWith(compareByDescending<ForumPostRecord> { it.createdAt }.thenByDescending { it.id })
        return posts.map { post -> listingFor(post) }
    }

    /**
     * A published post plus the derived comment facts its list card shows.
     *
     * One query over the comment collection per post — the document-model stand-in
     * for the SQLite `forForum` join. Drafts are dropped in memory so the query stays
     * a single equality filter with no index.
     */
    private suspend fun listingFor(post: ForumPostRecord): ForumPostListing {
        val published = firestore.collection(COMMENTS_COLLECTION)
            .whereEqualTo(COMMENT_POST_ID, post.id)
            .get().await()
            .documents.filter { (it.getBoolean(IS_DRAFT) ?: false).not() }
        val latest = published.maxByOrNull { it.getLong(CREATED_AT) ?: 0L }
        return ForumPostListing(
            post = post,
            commentCount = published.size.toLong(),
            lastCommentAt = latest?.getLong(CREATED_AT),
            lastCommentAuthor = latest
                ?.let { authorOf(it.getLong(CREATED_BY), it.getString(CREATED_BY_EXTERNAL)) }
                ?: Author.Nobody,
        )
    }

    override suspend fun withPossibleMentions(): List<Pair<Long, String>> = bodiesContaining(collection(), "@")

    override suspend fun withAttachmentLinks(): List<Pair<Long, String>> =
        bodiesContaining(collection(), "/api/attachments/")

    internal companion object {
        const val POSTS_COUNTER = "forumPosts"

        /** Re-exported for the project cascade, which has to reach these documents by forum. */
        const val COLLECTION = POSTS_COLLECTION
        const val FORUM = FORUM_ID
    }
}

class FirestoreForumCommentStore(
    private val firestore: Firestore,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.ForumCommentStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(COMMENTS_COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    override suspend fun insertDraft(
        postId: Long,
        author: Author,
        createdAt: Long?,
        agentName: String?,
    ): Long {
        val timestamp = createdAt ?: now()
        return firestore.runTransaction { txn ->
            val id = counters.next(txn, COMMENTS_COUNTER).getValue(COMMENTS_COUNTER)
            txn.set(
                doc(id),
                mapOf(
                    ID to id,
                    COMMENT_POST_ID to postId,
                    BODY to "",
                    CREATED_AT to timestamp,
                    CREATED_BY to author.accountId,
                    CREATED_BY_EXTERNAL to author.externalName,
                    AGENT_NAME to agentName,
                    IS_DRAFT to true,
                ),
            )
            id
        }.await()
    }

    override suspend fun publish(id: Long, body: String) {
        doc(id).update(mapOf(BODY to body, IS_DRAFT to false)).await()
    }

    override suspend fun updateBody(id: Long, body: String) {
        doc(id).update(mapOf(BODY to body)).await()
    }

    override suspend fun edit(
        id: Long,
        body: String,
        createdAt: Long,
        author: Author,
        agentName: String?,
    ) {
        doc(id).update(
            mapOf(
                BODY to body,
                CREATED_AT to createdAt,
                CREATED_BY to author.accountId,
                CREATED_BY_EXTERNAL to author.externalName,
                AGENT_NAME to agentName,
            ),
        ).await()
    }

    override suspend fun delete(id: Long) {
        doc(id).delete().await()
    }

    override suspend fun findById(id: Long): ForumCommentRecord? =
        doc(id).get().await().takeIf { it.exists() }?.toCommentRecord()

    override suspend fun findByIdInPost(id: Long, postId: Long): ForumCommentRecord? =
        findById(id)?.takeIf { it.postId == postId }

    /** A post's published comments, oldest first. Flat, in order. */
    override suspend fun forPost(postId: Long): List<ForumCommentRecord> =
        collection()
            .whereEqualTo(COMMENT_POST_ID, postId)
            .get().await()
            .documents.map { it.toCommentRecord() }
            .filter { !it.isDraft }
            .sortedWith(compareBy<ForumCommentRecord> { it.createdAt }.thenBy { it.id })

    override suspend fun withPossibleMentions(): List<Pair<Long, String>> = bodiesContaining(collection(), "@")

    override suspend fun withAttachmentLinks(): List<Pair<Long, String>> =
        bodiesContaining(collection(), "/api/attachments/")

    internal companion object {
        const val COMMENTS_COUNTER = "forumComments"

        /** Re-exported for the project cascade, which has to reach these documents by post. */
        const val COLLECTION = COMMENTS_COLLECTION
        const val POST = COMMENT_POST_ID
    }
}

// ── Shared document shape ────────────────────────────────────────────────────
// Both collections carry the same author/draft/body columns, and the post store's
// forForum reads the comment collection, so the field names are file-scoped.

private const val POSTS_COLLECTION = "forumPosts"
private const val COMMENTS_COLLECTION = "forumComments"

private const val ID = "id"
private const val FORUM_ID = "forumId"
private const val COMMENT_POST_ID = "postId"
private const val TITLE = "title"
private const val BODY = "body"
private const val CREATED_AT = "createdAt"
private const val CREATED_BY = "createdBy"
private const val CREATED_BY_EXTERNAL = "createdByExternal"
private const val AGENT_NAME = "agentName"
private const val IS_DRAFT = "isDraft"

private fun DocumentSnapshot.toPostRecord() = ForumPostRecord(
    id = getLong(ID)!!,
    forumId = getLong(FORUM_ID)!!,
    title = getString(TITLE).orEmpty(),
    body = getString(BODY).orEmpty(),
    createdAt = getLong(CREATED_AT)!!,
    author = authorOf(getLong(CREATED_BY), getString(CREATED_BY_EXTERNAL)),
    agentName = getString(AGENT_NAME),
    isDraft = getBoolean(IS_DRAFT) ?: false,
)

private fun DocumentSnapshot.toCommentRecord() = ForumCommentRecord(
    id = getLong(ID)!!,
    postId = getLong(COMMENT_POST_ID)!!,
    body = getString(BODY).orEmpty(),
    createdAt = getLong(CREATED_AT)!!,
    author = authorOf(getLong(CREATED_BY), getString(CREATED_BY_EXTERNAL)),
    agentName = getString(AGENT_NAME),
    isDraft = getBoolean(IS_DRAFT) ?: false,
)

/**
 * Every document in [collection] whose body contains [needle], as id-to-body pairs
 * — the full-collection scan the two startup maintenance passes need, since
 * Firestore has no substring predicate to push a SQLite `LIKE '%needle%'` down into.
 * Drafts are included: a body being typed can already mention somebody. See
 * [FirestoreIssueStore] for the same scan over issue descriptions.
 */
private suspend fun bodiesContaining(
    collection: com.google.cloud.firestore.CollectionReference,
    needle: String,
): List<Pair<Long, String>> =
    collection.get().await().documents
        .map { it.getLong(ID)!! to it.getString(BODY).orEmpty() }
        .filter { it.second.contains(needle) }
