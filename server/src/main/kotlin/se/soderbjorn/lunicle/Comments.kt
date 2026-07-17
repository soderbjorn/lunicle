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
 * @property author who wrote it: an account, an imported name, or nobody. See
 *   [Author].
 * @property agentName the agent that wrote it on the author's behalf, or null
 *   when a human did. Orthogonal to [author], not a fourth kind of it — the
 *   comment is still the author's, and this only records that an agent held the
 *   pen. Set by the MCP tools; a human commenting in the web app is not an agent.
 */
data class CommentRecord(
    val id: Long,
    val issueId: Long,
    val body: String,
    val createdAt: Long,
    val author: Author,
    val agentName: String?,
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
     * @param agentName the agent that wrote it on the author's behalf, or null when
     *   a human did. Only the MCP path passes a name; the web path leaves it null.
     */
    suspend fun insertDraft(
        issueId: Long,
        author: Author,
        createdAt: Long? = null,
        agentName: String? = null,
    ): Long = withContext(DatabaseDispatcher) {
        database.commentsQueries
            .insert(issueId, "", createdAt ?: now(), author.accountId, author.externalName, agentName)
            .executeAsOne()
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
    author = authorOf(created_by, created_by_external),
    agentName = agent_name,
    isDraft = is_draft != 0L,
)
