/**
 * The attachments table. The bytes are not here; see [AttachmentRepository].
 *
 * @see AttachmentRepository
 * @see Database
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * An attachment's metadata.
 *
 * @property issueId set exactly when the other four owners are not; the `CHECK`
 *   in Attachments.sq makes an orphan and a doubly-owned row both
 *   unrepresentable. The same is true of [commentId], [forumPostId],
 *   [forumCommentId] and [messageId] — exactly one of the five is non-null,
 *   always.
 * @property messageId the odd one out, and worth knowing about before writing
 *   anything that reads this type: it is the only owner that does not reach a
 *   project, so code that resolves "which project is this attachment in" has a
 *   case here with no answer. See `mayReadAttachment`, which is where the
 *   difference is handled.
 * @property filename as uploaded, for the download name. Never reaches the
 *   filesystem — see [storageKey].
 * @property storageKey the file's name on the volume. Random, and the only
 *   thing in this system that ever names a path. Nothing a user types reaches
 *   it, which is what makes "../../lunicle.db" as a filename harmless.
 * @property publicId what this attachment is called in a URL — the only one of
 *   these three identifiers a reader ever sees. Deliberately neither of the
 *   other two: [id] is countable and [storageKey] names a path. See LNL-51 and
 *   the column's comment in Attachments.sq.
 * @property author who uploaded it — an account, an imported name, or nobody.
 *   See [Author].
 */
data class AttachmentRecord(
    val id: Long,
    val issueId: Long?,
    val commentId: Long?,
    val forumPostId: Long?,
    val forumCommentId: Long?,
    val messageId: Long?,
    val filename: String,
    val mimeType: String,
    val byteSize: Long,
    val storageKey: String,
    val publicId: String,
    val createdAt: Long,
    val author: Author,
)

/** Reads and writes the attachments table. */
class AttachmentStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.AttachmentStore {
    /**
     * @param createdAt when this file should claim to have been uploaded, or
     *   null — every ordinary caller — for now. Only an admin backfilling an
     *   import passes it, and it arrives here rather than being read from the
     *   clock inside because an imported screenshot belongs at the moment its
     *   issue was written, not at the moment the importer ran. Same rule and
     *   same reason as [IssueStore.insertDraft].
     */
    override suspend fun insertForIssue(
        issueId: Long,
        filename: String,
        mimeType: String,
        byteSize: Long,
        storageKey: String,
        publicId: String,
        author: Author,
        createdAt: Long?,
    ): Long = withContext(DatabaseDispatcher) {
        database.attachmentsQueries
            .insertForIssue(
                issueId, filename, mimeType, byteSize, storageKey, publicId,
                createdAt ?: now(), author.accountId, author.externalName,
            )
            .executeAsOne()
    }

    /** See [insertForIssue] for `createdAt`. */
    override suspend fun insertForComment(
        commentId: Long,
        filename: String,
        mimeType: String,
        byteSize: Long,
        storageKey: String,
        publicId: String,
        author: Author,
        createdAt: Long?,
    ): Long = withContext(DatabaseDispatcher) {
        database.attachmentsQueries
            .insertForComment(
                commentId, filename, mimeType, byteSize, storageKey, publicId,
                createdAt ?: now(), author.accountId, author.externalName,
            )
            .executeAsOne()
    }

    /** See [insertForIssue] for `createdAt`. */
    override suspend fun insertForForumPost(
        forumPostId: Long,
        filename: String,
        mimeType: String,
        byteSize: Long,
        storageKey: String,
        publicId: String,
        author: Author,
        createdAt: Long?,
    ): Long = withContext(DatabaseDispatcher) {
        database.attachmentsQueries
            .insertForForumPost(
                forumPostId, filename, mimeType, byteSize, storageKey, publicId,
                createdAt ?: now(), author.accountId, author.externalName,
            )
            .executeAsOne()
    }

    /** See [insertForIssue] for `createdAt`. */
    override suspend fun insertForForumComment(
        forumCommentId: Long,
        filename: String,
        mimeType: String,
        byteSize: Long,
        storageKey: String,
        publicId: String,
        author: Author,
        createdAt: Long?,
    ): Long = withContext(DatabaseDispatcher) {
        database.attachmentsQueries
            .insertForForumComment(
                forumCommentId, filename, mimeType, byteSize, storageKey, publicId,
                createdAt ?: now(), author.accountId, author.externalName,
            )
            .executeAsOne()
    }

    /** See [insertForIssue] for `createdAt`. */
    override suspend fun insertForMessage(
        messageId: Long,
        filename: String,
        mimeType: String,
        byteSize: Long,
        storageKey: String,
        publicId: String,
        author: Author,
        createdAt: Long?,
    ): Long = withContext(DatabaseDispatcher) {
        database.attachmentsQueries
            .insertForMessage(
                messageId, filename, mimeType, byteSize, storageKey, publicId,
                createdAt ?: now(), author.accountId, author.externalName,
            )
            .executeAsOne()
    }

    override suspend fun findById(id: Long): AttachmentRecord? = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.findById(id).executeAsOneOrNull()?.toRecord()
    }

    /**
     * The record behind a URL's id, or null.
     *
     * The only lookup here that starts from something a caller supplied, so it is
     * also the only one that must not care what shape that string is. It does not
     * validate: an id that is not in the column simply is not found, and the
     * route's 404 is the same one it gives a reader who may not see the project.
     * Nothing derived from the argument reaches the filesystem — the path comes
     * from the row's own `storage_key` — which is what makes passing an
     * unvalidated string in here safe rather than merely convenient.
     */
    override suspend fun findByPublicId(publicId: String): AttachmentRecord? = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.findByPublicId(publicId).executeAsOneOrNull()?.toRecord()
    }

    override suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.delete(id)
    }

    /**
     * The cascade the schema would have run anyway, a moment early. See the
     * interface's comment on why it is called at all, and the query's on why it
     * is harmless here.
     */
    override suspend fun deleteForIssue(issueId: Long): Unit = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.deleteForIssue(issueId, issueId)
    }

    /** [deleteForIssue]'s project-level twin, and redundant here for the same reason. */
    override suspend fun deleteForProject(projectId: Long): Unit = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.deleteForProject(projectId, projectId, projectId, projectId)
    }

    /** Every key the volume is allowed to hold. Read by the startup sweep. */
    override suspend fun allStorageKeys(): Set<String> = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.allStorageKeys().executeAsList().toSet()
    }

    /**
     * Every attachment's **public** id and declared type. Read by
     * [AttachmentLinkRepair], which rewrites URLs and therefore deals in the id
     * a URL names. See the query's comment.
     */
    override suspend fun allMimeTypes(): List<Pair<String, String>> = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.allMimeTypes().executeAsList().map { it.public_id to it.mime_type }
    }

    /**
     * Every file this issue owns, directly or through a comment.
     *
     * Must be read *before* the issue is deleted; see the query's comment.
     */
    override suspend fun keysForIssue(issueId: Long): List<String> = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.keysForIssue(issueId, issueId).executeAsList()
    }

    /** Every file this comment owns. Same timing rule as [keysForIssue]. */
    override suspend fun keysForComment(commentId: Long): List<String> = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.keysForComment(commentId).executeAsList()
    }

    /**
     * Every file this forum post owns, directly or through one of its comments.
     * Same timing rule as [keysForIssue].
     */
    override suspend fun keysForForumPost(postId: Long): List<String> = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.keysForForumPost(postId, postId).executeAsList()
    }

    /** Every file this forum comment owns. Same timing rule as [keysForIssue]. */
    override suspend fun keysForForumComment(commentId: Long): List<String> = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.keysForForumComment(commentId).executeAsList()
    }

    /**
     * Every file under any post in this forum. Same timing rule as [keysForIssue],
     * and the one that bites hardest: deleting a forum cascades through posts and
     * comments in a single statement, so this is the last moment anything can name
     * those files.
     */
    override suspend fun keysForForum(forumId: Long): List<String> = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.keysForForum(forumId, forumId).executeAsList()
    }

    /** Every file this message owns. Same timing rule as [keysForIssue]. */
    override suspend fun keysForMessage(messageId: Long): List<String> = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.keysForMessage(messageId).executeAsList()
    }

    /**
     * Every file under any message in this conversation. Same timing rule as
     * [keysForIssue], and read on exactly one path: discarding a conversation that
     * was started and never sent. See [ConversationRepository].
     */
    override suspend fun keysForConversation(conversationId: Long): List<String> =
        withContext(DatabaseDispatcher) {
            database.attachmentsQueries.keysForConversation(conversationId).executeAsList()
        }

    /**
     * Every file under any issue **or any forum post** in this project. Same
     * timing rule as [keysForIssue].
     *
     * Messages are deliberately absent: a conversation belongs to no project, so
     * deleting a project cannot cascade into one. See Attachments.sq.
     */
    override suspend fun keysForProject(projectId: Long): List<String> = withContext(DatabaseDispatcher) {
        database.attachmentsQueries
            .keysForProject(projectId, projectId, projectId, projectId)
            .executeAsList()
    }
}

/**
 * The generated row → the record the rest of the server deals in.
 *
 * One mapper for both lookups, which is the whole reason `findById` and
 * `findByPublicId` are `SELECT *`; see their comment in Attachments.sq.
 */
private fun se.soderbjorn.lunicle.db.Attachments.toRecord(): AttachmentRecord = AttachmentRecord(
    id = id,
    issueId = issue_id,
    commentId = comment_id,
    forumPostId = forum_post_id,
    forumCommentId = forum_comment_id,
    messageId = message_id,
    filename = filename,
    mimeType = mime_type,
    byteSize = byte_size,
    storageKey = storage_key,
    publicId = public_id,
    createdAt = created_at,
    author = authorOf(created_by, created_by_external),
)
