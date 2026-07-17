/**
 * The comments table.
 *
 * A comment reaches its project through its issue, which is why there is no
 * `project_id` here to read — [AccessControl] resolves the issue first, and
 * that is the only path. Duplicating the project on the comment would be a
 * second source of truth for a fact that already has one, and the two would
 * eventually disagree.
 *
 * @see Database
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * A comment as this server knows it.
 *
 * @property isDraft as on an issue: the comment modal supports inline image
 *   upload, so the row exists before the image does. Cancel deletes it; the
 *   flag covers the closed tab.
 */
data class CommentRecord(
    val id: Long,
    val issueId: Long,
    val body: String,
    val createdAt: Long,
    val createdBy: Long?,
    val isDraft: Boolean,
)

/** Reads and writes the comments table. */
class CommentStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Create the draft row an image upload can hang off.
     *
     * @param createdAt when the comment should claim to have been written, or null
     *   — every caller but one — for now. Only an admin backfilling history over
     *   MCP passes it; see [AccessControl.canAttributeWrites]. A comment has no
     *   `updated_at` to keep in step, so unlike an issue there is nothing else to
     *   bind it to — `publish` below deliberately leaves `created_at` alone.
     */
    suspend fun insertDraft(
        issueId: Long,
        createdBy: Long?,
        createdAt: Long? = null,
    ): Long = withContext(DatabaseDispatcher) {
        database.commentsQueries.insert(issueId, "", createdAt ?: now(), createdBy).executeAsOne()
    }

    suspend fun publish(id: Long, body: String): Unit = withContext(DatabaseDispatcher) {
        database.commentsQueries.publish(body, id)
    }

    suspend fun update(id: Long, body: String): Unit = withContext(DatabaseDispatcher) {
        database.commentsQueries.update(body, id)
    }

    suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.commentsQueries.delete(id)
    }

    suspend fun findById(id: Long): CommentRecord? = withContext(DatabaseDispatcher) {
        database.commentsQueries.findById(id).executeAsOneOrNull()?.toRecord()
    }

    /** Published comments on an issue, oldest first. */
    suspend fun forIssue(issueId: Long): List<CommentRecord> = withContext(DatabaseDispatcher) {
        database.commentsQueries.forIssue(issueId).executeAsList().map { it.toRecord() }
    }
}

private fun se.soderbjorn.lunicle.db.Comments.toRecord(): CommentRecord = CommentRecord(
    id = id,
    issueId = issue_id,
    body = body,
    createdAt = created_at,
    createdBy = created_by,
    isDraft = is_draft != 0L,
)
