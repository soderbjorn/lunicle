/**
 * The persistence seams for forum posts and the flat comments on them.
 *
 * Two of the LNL-111 domain store interfaces, kept together because the tables
 * are: a comment reaches its forum only through its post. The reference
 * implementations are the SQLite gateways [se.soderbjorn.lunicle.ForumPostStore]
 * and [se.soderbjorn.lunicle.ForumCommentStore] (each named by its fully-qualified
 * name in its own supertype clause, since interface and gateway share a simple
 * name).
 *
 * These are the low-level persistence; the title/body rules, the draft dance and
 * the delete-the-files-first cascade in `ForumPostRepository` sit on top and are
 * backend-agnostic. So the contracts here are about persistence: a draft is
 * invisible until published, `forForum` lists published posts newest-first,
 * `forPost` lists published comments oldest-first, the by-id-in-container reads
 * refuse a mismatched pair, and a delete removes the row.
 *
 * @see se.soderbjorn.lunicle.store.ForumPostStoreContract
 * @see se.soderbjorn.lunicle.store.ForumCommentStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.ForumCommentRecord
import se.soderbjorn.lunicle.ForumPostListing
import se.soderbjorn.lunicle.ForumPostRecord

interface ForumPostStore {
    /** Create the hidden draft row an inline upload can hang off; returns its id. */
    suspend fun insertDraft(
        forumId: Long,
        author: Author,
        createdAt: Long? = null,
        agentName: String? = null,
    ): Long

    /** Fill in a draft's title and body and make it visible. */
    suspend fun publish(id: Long, title: String, body: String)

    /** Rewrite the body alone, leaving `is_draft` where it was. */
    suspend fun updateBody(id: Long, body: String)

    /** Rewrite everything a published post says about itself, `is_draft` excepted. */
    suspend fun edit(
        id: Long,
        title: String,
        body: String,
        createdAt: Long,
        author: Author,
        agentName: String?,
    )

    suspend fun delete(id: Long)

    suspend fun findById(id: Long): ForumPostRecord?

    /** One post, proving it is this forum's, or null when the pair does not match. */
    suspend fun findByIdInForum(id: Long, forumId: Long): ForumPostRecord?

    /** A forum's published posts, newest first, each with its comment count. */
    suspend fun forForum(forumId: Long): List<ForumPostListing>

    /** Every post that might mention somebody. */
    suspend fun withPossibleMentions(): List<Pair<Long, String>>

    /** Every post whose body might link to an attachment. */
    suspend fun withAttachmentLinks(): List<Pair<Long, String>>
}

interface ForumCommentStore {
    /** Create the hidden draft row an inline upload can hang off; returns its id. */
    suspend fun insertDraft(
        postId: Long,
        author: Author,
        createdAt: Long? = null,
        agentName: String? = null,
    ): Long

    /** Fill in a draft's body and make it visible. */
    suspend fun publish(id: Long, body: String)

    /** Rewrite the body alone, leaving `is_draft` where it was. */
    suspend fun updateBody(id: Long, body: String)

    /** Rewrite everything a published comment says about itself, `is_draft` excepted. */
    suspend fun edit(
        id: Long,
        body: String,
        createdAt: Long,
        author: Author,
        agentName: String?,
    )

    suspend fun delete(id: Long)

    suspend fun findById(id: Long): ForumCommentRecord?

    /** One comment, proving it is this post's, or null when the pair does not match. */
    suspend fun findByIdInPost(id: Long, postId: Long): ForumCommentRecord?

    /** A post's published comments, oldest first. Flat, in order. */
    suspend fun forPost(postId: Long): List<ForumCommentRecord>

    /** Every comment that might mention somebody. */
    suspend fun withPossibleMentions(): List<Pair<Long, String>>

    /** Every comment whose body might link to an attachment. */
    suspend fun withAttachmentLinks(): List<Pair<Long, String>>
}
