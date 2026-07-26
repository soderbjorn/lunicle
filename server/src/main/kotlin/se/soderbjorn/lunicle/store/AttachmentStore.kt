/**
 * The persistence seam for attachment *metadata* — the rows describing an
 * uploaded file, one per owner kind (issue, comment, forum post, forum comment,
 * message).
 *
 * One of the LNL-111 domain store interfaces, and deliberately only half of the
 * attachment story: this is the metadata (storage keys, mime types, owners),
 * which is ordinary database work. The file *bytes* live behind
 * `AttachmentRepository` and today land on disk; moving them to GCS is Phase 3 and
 * needs the Cloud Storage SDK, so that seam is extracted separately when that work
 * lands. Everything here works against SQLite (and Firestore later) with no cloud
 * dependency.
 *
 * The reference implementation is the SQLite gateway
 * [se.soderbjorn.lunicle.AttachmentStore] (named by its fully-qualified name in
 * that class's supertype clause, since the two share a simple name).
 *
 * @see se.soderbjorn.lunicle.store.AttachmentStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.AttachmentRecord
import se.soderbjorn.lunicle.Author

interface AttachmentStore {
    suspend fun insertForIssue(
        issueId: Long, filename: String, mimeType: String, byteSize: Long,
        storageKey: String, publicId: String, author: Author, createdAt: Long? = null,
    ): Long

    suspend fun insertForComment(
        commentId: Long, filename: String, mimeType: String, byteSize: Long,
        storageKey: String, publicId: String, author: Author, createdAt: Long? = null,
    ): Long

    suspend fun insertForForumPost(
        forumPostId: Long, filename: String, mimeType: String, byteSize: Long,
        storageKey: String, publicId: String, author: Author, createdAt: Long? = null,
    ): Long

    suspend fun insertForForumComment(
        forumCommentId: Long, filename: String, mimeType: String, byteSize: Long,
        storageKey: String, publicId: String, author: Author, createdAt: Long? = null,
    ): Long

    suspend fun insertForMessage(
        messageId: Long, filename: String, mimeType: String, byteSize: Long,
        storageKey: String, publicId: String, author: Author, createdAt: Long? = null,
    ): Long

    suspend fun findById(id: Long): AttachmentRecord?

    suspend fun findByPublicId(publicId: String): AttachmentRecord?

    suspend fun delete(id: Long)

    /** Every storage key the database knows about — the reconcile side of orphan sweeping. */
    suspend fun allStorageKeys(): Set<String>

    /** Every (storageKey, mimeType) pair — for the attachment link repair sweep. */
    suspend fun allMimeTypes(): List<Pair<String, String>>

    suspend fun keysForIssue(issueId: Long): List<String>
    suspend fun keysForComment(commentId: Long): List<String>
    suspend fun keysForForumPost(postId: Long): List<String>
    suspend fun keysForForumComment(commentId: Long): List<String>
    suspend fun keysForForum(forumId: Long): List<String>
    suspend fun keysForMessage(messageId: Long): List<String>
    suspend fun keysForConversation(conversationId: Long): List<String>
    suspend fun keysForProject(projectId: Long): List<String>
}
