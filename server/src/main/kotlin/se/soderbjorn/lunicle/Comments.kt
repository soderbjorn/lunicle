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

/**
 * Reads and writes the comments table.
 *
 * The SQLite reference implementation of [se.soderbjorn.lunicle.store.CommentStore],
 * named there by its fully-qualified name because interface and gateway share a
 * simple name.
 */
class CommentStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.CommentStore {
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
    override suspend fun insertDraft(
        issueId: Long,
        author: Author,
        createdAt: Long?,
        agentName: String?,
    ): Long = withContext(DatabaseDispatcher) {
        database.commentsQueries
            .insert(issueId, "", createdAt ?: now(), author.accountId, author.externalName, agentName)
            .executeAsOne()
    }

    override suspend fun publish(id: Long, body: String): Unit = withContext(DatabaseDispatcher) {
        database.commentsQueries.publish(body, id)
    }

    override suspend fun update(id: Long, body: String): Unit = withContext(DatabaseDispatcher) {
        database.commentsQueries.update(body, id)
    }

    /**
     * Rewrite a published comment's editable columns in one statement.
     *
     * The MCP `update_comment` path, and the only writer that touches more than
     * the body: an admin editing an imported comment may re-attribute and re-date
     * it, and any editor may set its agent label. There is no web caller — the
     * browser edits the body alone, through [update].
     *
     * @param createdAt always concrete here, never null-for-now: an edit that does
     *   not touch the date passes the comment's existing one straight back, so the
     *   column is rewritten to the value it already held rather than to the clock.
     * @param author whose name the comment carries afterwards — the current one
     *   again unless an admin re-attributed it. See [Author].
     * @param agentName the agent label to store, or null to leave it unmarked.
     */
    override suspend fun edit(
        id: Long,
        body: String,
        createdAt: Long,
        author: Author,
        agentName: String?,
    ): Unit = withContext(DatabaseDispatcher) {
        database.commentsQueries.edit(body, createdAt, author.accountId, author.externalName, agentName, id)
    }

    /**
     * Every comment that might mention somebody, as id-to-body pairs. See
     * Comments.sq's `withPossibleMentions` for what "might" means, and why drafts
     * are in there too.
     */
    override suspend fun withPossibleMentions(): List<Pair<Long, String>> = withContext(DatabaseDispatcher) {
        database.commentsQueries.withPossibleMentions().executeAsList().map { it.id to it.body }
    }

    /**
     * Every comment whose body might link to an attachment, as id-to-body pairs.
     * See [AttachmentLinkRepair].
     */
    override suspend fun withAttachmentLinks(): List<Pair<Long, String>> = withContext(DatabaseDispatcher) {
        database.commentsQueries.withAttachmentLinks().executeAsList().map { it.id to it.body }
    }

    override suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.commentsQueries.delete(id)
    }

    /**
     * The cascade the schema would have run anyway, a moment early. See the
     * interface's comment on why it is called at all, and the query's on why it is
     * harmless here.
     */
    override suspend fun deleteForIssue(issueId: Long): Unit = withContext(DatabaseDispatcher) {
        database.commentsQueries.deleteForIssue(issueId)
    }

    override suspend fun findById(id: Long): CommentRecord? = withContext(DatabaseDispatcher) {
        database.commentsQueries.findById(id).executeAsOneOrNull()?.toRecord()
    }

    /** Published comments on an issue, oldest first. */
    override suspend fun forIssue(issueId: Long): List<CommentRecord> = withContext(DatabaseDispatcher) {
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
