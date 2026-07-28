/**
 * The persistence seam for issue comments: the `comments` row and every read the
 * issue detail view runs against it.
 *
 * The same draft→publish shape as [IssueStore] in miniature — the comment modal
 * supports inline image upload, so the row exists before the image does, is born a
 * draft ([insertDraft]) and is made visible by [publish]. A document backend's
 * concern here is the one [IssueStore]'s is: that a draft is invisible on the read
 * a human sees ([forIssue]), and that [publish] makes it visible. The reference
 * implementation is the SQLite gateway [se.soderbjorn.lunicle.CommentStore] (named
 * by its fully-qualified name in that class's supertype clause, since the two share
 * a simple name).
 *
 * This is the low-level persistence; the rules that make a comment move together
 * with the attachment *files* a SQL cascade cannot reach, and the @mention notify,
 * live one layer up in `IssueRepository`/`CommentRepository` and are
 * backend-agnostic. A comment carries no `project_id`: it reaches its project
 * through its issue, and duplicating that here would be a second source of truth.
 *
 * @see se.soderbjorn.lunicle.store.CommentStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.CommentRecord

interface CommentStore {
    /**
     * Create the draft row an inline image upload can hang off, and return its id.
     * The comment is invisible on [forIssue] until [publish]; see the class preamble.
     *
     * @param createdAt when the comment should claim to have been written, or null —
     *   every caller but one — for now. Only an admin backfilling history over MCP
     *   passes it. A comment has no `updated_at`, so unlike an issue there is nothing
     *   else to bind it to.
     * @param agentName the agent that wrote it on the author's behalf, or null when a
     *   human did. Only the MCP path passes a name; the web path leaves it null.
     */
    suspend fun insertDraft(
        issueId: Long,
        author: Author,
        createdAt: Long? = null,
        agentName: String? = null,
    ): Long

    /** Fill in a draft's body and make it visible. Leaves `created_at` alone. */
    suspend fun publish(id: Long, body: String)

    /** Rewrite the body alone — the web app's edit, leaving everything else in place. */
    suspend fun update(id: Long, body: String)

    /**
     * Rewrite a published comment's editable columns in one write — the MCP
     * `update_comment` path, the only writer that touches more than the body: an
     * admin may re-attribute and re-date an imported comment, and any editor may set
     * its agent label. `is_draft` is deliberately untouched, so a published comment
     * stays published.
     *
     * @param createdAt always concrete here, never null-for-now: an edit that does
     *   not move the date passes the comment's existing one straight back.
     * @param author whose name the comment carries afterwards — the current one again
     *   unless an admin re-attributed it.
     * @param agentName the agent label to store, or null to leave it unmarked.
     */
    suspend fun edit(
        id: Long,
        body: String,
        createdAt: Long,
        author: Author,
        agentName: String?,
    )

    /**
     * Every comment that might mention somebody, as id-to-body pairs — the coarse
     * pre-filter the display-name rewrite runs. Drafts are included: a draft is
     * somebody's unsent comment and must not be the one place an old name survives.
     */
    suspend fun withPossibleMentions(): List<Pair<Long, String>>

    /** Every comment whose body might link to an attachment, as id-to-body pairs. */
    suspend fun withAttachmentLinks(): List<Pair<Long, String>>

    suspend fun delete(id: Long)

    /**
     * Delete every comment on an issue, drafts included — the whole conversation,
     * not just what [forIssue] would return.
     *
     * Redundant on SQLite, where `comments.issue_id` is `ON DELETE CASCADE` and the
     * rows would go with the issue anyway, and load-bearing on Firestore, which has
     * no cascade: deleting the issue document leaves its comment documents behind,
     * hanging off an `issueId` that resolves to nothing. `IssueRepository.delete` is
     * one backend-agnostic function, so it calls this on both — the pattern
     * [AttachmentStore.deleteForIssue] set. See LNL-177.
     *
     * Drafts are included deliberately: an unsent comment on a deleted issue is
     * exactly as unreachable as a published one, and the SQLite cascade makes no
     * distinction either.
     *
     * The *files* on these comments are not this method's business — they are
     * already in the set [AttachmentStore.deleteForIssue] takes, which is why the
     * caller runs that first and unlinks the blobs afterwards.
     */
    suspend fun deleteForIssue(issueId: Long)

    suspend fun findById(id: Long): CommentRecord?

    /** Published comments on an issue, oldest first. Drafts excluded. */
    suspend fun forIssue(issueId: Long): List<CommentRecord>
}
