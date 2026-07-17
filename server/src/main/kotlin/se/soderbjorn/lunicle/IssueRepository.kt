/**
 * The rules about issues that belong in neither a route nor a store.
 *
 * Two of them, and both are the same shape — several stores, or a store and the
 * filesystem, that have to move together:
 *
 *  - **Publish** writes the issue, its labels and its components together.
 *  - **Delete** lets the rows cascade, and then the attachment *files* have to
 *    go too. SQLite has no way to reach the filesystem, so a cascade silently
 *    leaves the bytes behind; this is where database and filesystem become one
 *    operation.
 *
 * @see ProjectRepository
 * @see AttachmentRepository
 */
package se.soderbjorn.lunicle

/**
 * Creates, publishes and deletes issues and their comments.
 *
 * @param statuses needed for one question a new issue cannot answer itself:
 *   which column it lands in.
 * @param attachmentStore read directly rather than through [attachments],
 *   because the query that matters here — "every file under this issue,
 *   including its comments'" — spans two tables and belongs to neither
 *   repository.
 */
class IssueRepository(
    private val issues: IssueStore,
    private val comments: CommentStore,
    private val statuses: StatusStore,
    private val priorities: PriorityStore,
    private val attachments: AttachmentRepository,
    private val attachmentStore: AttachmentStore,
) {
    /**
     * Create the hidden draft the editor writes into.
     *
     * The row exists before the editor is filled in so that an inline image
     * upload has an issue to attach to — which is what keeps the `CHECK` in
     * Attachments.sq (exactly one owner) true at every moment rather than
     * eventually. Cancel deletes the row outright; `is_draft` covers the
     * closed-tab case, where the draft simply stays invisible.
     *
     * The issue lands in the board's leftmost column and at the middle of the
     * priority scale, both read from the database rather than hardcoded to "New"
     * and "Normal": the seed names them, and a project whose vocabulary was
     * renamed should still be able to take an issue.
     *
     * @param createdAt when the issue should claim to have been written, or null
     *   for now. Only an admin backfilling history over MCP passes it, and it must
     *   pass the same value to [save] — see that function.
     * @param agentName the agent filing on the author's behalf, or null when a
     *   human is. Only the MCP tools pass it; the web route leaves it null.
     * @return the new issue's id and its number.
     * @throws IllegalStateException if the project has no statuses or no
     *   priorities at all. That would mean [ProjectRepository.create] was
     *   bypassed, since it seeds both in the same transaction as the project row
     *   — and a project with no columns can neither take an issue nor be repaired
     *   from the UI, so failing loudly beats inventing one here.
     */
    suspend fun createDraft(
        projectId: Long,
        author: Author,
        createdAt: Long? = null,
        agentName: String? = null,
    ): Pair<Long, Long> {
        val first = statuses.firstForProject(projectId) ?: error(
            "Project $projectId has no statuses, so it cannot take an issue. Every project gets " +
                "its board columns in the same transaction as its row — see ProjectRepository.create.",
        )
        val priority = priorities.defaultForProject(projectId) ?: error(
            "Project $projectId has no priorities, so it cannot take an issue. Seeded in the same " +
                "transaction as the project row — see ProjectRepository.create — and backfilled for " +
                "every pre-existing project by 2.sqm.",
        )
        return issues.insertDraft(
            projectId,
            title = "",
            statusId = first.id,
            priorityId = priority.id,
            author = author,
            createdAt = createdAt,
            agentName = agentName,
        )
    }

    /**
     * Save the editor's fields, and publish the issue if it was a draft.
     *
     * One call for both, because they are the same write: an edit to a published
     * issue and the first save of a draft differ only in what `is_draft` ends up
     * as, and `publish` sets it to 0 unconditionally — which is already correct
     * for an issue where it is 0 already.
     *
     * The labels and components are replaced in their own transaction rather
     * than sharing this one. That is a real seam: an issue can publish and its
     * labels fail. It is bounded — the issue appears with no labels, which the
     * user fixes by reopening the modal — and closing it would mean threading
     * one transaction through three stores to protect against a dropped
     * connection to a local file.
     *
     * @param updatedAt what to stamp, or null for now — which is every caller but
     *   the backfill one. An admin backfilling over MCP must pass the same value
     *   it gave [createDraft], because publishing stamps `updated_at`
     *   unconditionally and would otherwise drag a 2019 issue's "last touched" to
     *   today, straddling the two columns Issues.sq requires to agree on a
     *   never-edited issue.
     */
    suspend fun save(
        issue: IssueRecord,
        title: String,
        description: String,
        statusId: Long,
        priorityId: Long,
        resolutionId: Long?,
        labelIds: List<Long>,
        componentIds: List<Long>,
        updatedAt: Long? = null,
    ) {
        issues.publish(issue.id, title, description, statusId, priorityId, resolutionId, updatedAt)
        issues.setLabelsAndComponents(issue.id, issue.projectId, labelIds, componentIds)
    }

    /**
     * Delete an issue, its comments, and every file behind either.
     *
     * The rows go by cascade; the files must be *found* first, because once the
     * rows are gone nothing knows which files they were. So: collect the keys,
     * delete the issue, then unlink.
     *
     * A file that fails to unlink is left on the volume and collected by
     * [AttachmentRepository.sweepOrphans] at the next restart — which is why
     * this deliberately does not try to be transactional. It cannot be.
     */
    suspend fun delete(issue: IssueRecord) {
        val doomed = attachmentStore.keysForIssue(issue.id)
        issues.delete(issue.id)
        doomed.forEach { attachments.fileFor(it).delete() }
    }

    /**
     * Create the hidden draft comment an inline image can hang off.
     *
     * @param createdAt when it should claim to have been written, or null for now.
     *   The backfill path's only lever here; see [CommentStore.insertDraft].
     * @param agentName the agent commenting on the author's behalf, or null when a
     *   human is. Only the MCP tools pass it; the web route leaves it null.
     */
    suspend fun createCommentDraft(
        issueId: Long,
        author: Author,
        createdAt: Long? = null,
        agentName: String? = null,
    ): Long = comments.insertDraft(issueId, author, createdAt, agentName)

    /** Save a comment's body and publish it. Same reasoning as [save]. */
    suspend fun saveComment(id: Long, body: String) {
        comments.publish(body = body, id = id)
    }

    /**
     * Delete a comment and the files it owns. Same ordering rule as [delete].
     */
    suspend fun deleteComment(comment: CommentRecord) {
        val doomed = attachmentStore.keysForComment(comment.id)
        comments.delete(comment.id)
        doomed.forEach { attachments.fileFor(it).delete() }
    }
}
