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
 * @property issueId set exactly when [commentId] is not; the `CHECK` in
 *   Attachments.sq makes an orphan and a doubly-owned row both unrepresentable.
 * @property filename as uploaded, for the download name. Never reaches the
 *   filesystem — see [storageKey].
 * @property storageKey the file's name on the volume. Random, and the only
 *   thing in this system that ever names a path. Nothing a user types reaches
 *   it, which is what makes "../../lunicle.db" as a filename harmless.
 * @property author who uploaded it — an account, an imported name, or nobody.
 *   See [Author].
 */
data class AttachmentRecord(
    val id: Long,
    val issueId: Long?,
    val commentId: Long?,
    val filename: String,
    val mimeType: String,
    val byteSize: Long,
    val storageKey: String,
    val createdAt: Long,
    val author: Author,
)

/** Reads and writes the attachments table. */
class AttachmentStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * @param createdAt when this file should claim to have been uploaded, or
     *   null — every ordinary caller — for now. Only an admin backfilling an
     *   import passes it, and it arrives here rather than being read from the
     *   clock inside because an imported screenshot belongs at the moment its
     *   issue was written, not at the moment the importer ran. Same rule and
     *   same reason as [IssueStore.insertDraft].
     */
    suspend fun insertForIssue(
        issueId: Long,
        filename: String,
        mimeType: String,
        byteSize: Long,
        storageKey: String,
        author: Author,
        createdAt: Long? = null,
    ): Long = withContext(DatabaseDispatcher) {
        database.attachmentsQueries
            .insertForIssue(
                issueId, filename, mimeType, byteSize, storageKey,
                createdAt ?: now(), author.accountId, author.externalName,
            )
            .executeAsOne()
    }

    /** See [insertForIssue] for `createdAt`. */
    suspend fun insertForComment(
        commentId: Long,
        filename: String,
        mimeType: String,
        byteSize: Long,
        storageKey: String,
        author: Author,
        createdAt: Long? = null,
    ): Long = withContext(DatabaseDispatcher) {
        database.attachmentsQueries
            .insertForComment(
                commentId, filename, mimeType, byteSize, storageKey,
                createdAt ?: now(), author.accountId, author.externalName,
            )
            .executeAsOne()
    }

    suspend fun findById(id: Long): AttachmentRecord? = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.findById(id).executeAsOneOrNull()?.let {
            AttachmentRecord(
                id = it.id,
                issueId = it.issue_id,
                commentId = it.comment_id,
                filename = it.filename,
                mimeType = it.mime_type,
                byteSize = it.byte_size,
                storageKey = it.storage_key,
                createdAt = it.created_at,
                author = authorOf(it.created_by, it.created_by_external),
            )
        }
    }

    suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.delete(id)
    }

    /** Every key the volume is allowed to hold. Read by the startup sweep. */
    suspend fun allStorageKeys(): Set<String> = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.allStorageKeys().executeAsList().toSet()
    }

    /**
     * Every file this issue owns, directly or through a comment.
     *
     * Must be read *before* the issue is deleted; see the query's comment.
     */
    suspend fun keysForIssue(issueId: Long): List<String> = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.keysForIssue(issueId, issueId).executeAsList()
    }

    /** Every file this comment owns. Same timing rule as [keysForIssue]. */
    suspend fun keysForComment(commentId: Long): List<String> = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.keysForComment(commentId).executeAsList()
    }

    /** Every file under any issue in this project. Same timing rule as [keysForIssue]. */
    suspend fun keysForProject(projectId: Long): List<String> = withContext(DatabaseDispatcher) {
        database.attachmentsQueries.keysForProject(projectId, projectId).executeAsList()
    }
}
