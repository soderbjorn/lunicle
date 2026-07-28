/**
 * The persistence seam for issues: the `issues` row itself and its two join
 * tables (`issue_labels`, `issue_components`), plus every read the board and the
 * settings dialog run against them.
 *
 * The LNL-111 *hardest aggregate* — an issue is not one row but a row and two
 * sets, and the parity a document backend is most likely to get subtly wrong is
 * exactly here: that a draft is invisible on the board, that publishing makes it
 * visible, that labels and components round-trip through the wholesale
 * delete-then-insert of [setLabelsAndComponents], and that the usage counts the
 * vocabulary editor reads include drafts. The reference implementation is the
 * SQLite gateway [se.soderbjorn.lunicle.IssueStore] (named by its fully-qualified
 * name in that class's supertype clause, since the two share a simple name).
 *
 * This is the low-level persistence; the rules that make several of these move
 * together — publishing an issue with its labels, deleting the attachment *files*
 * a SQL cascade cannot reach — live one layer up in `IssueRepository` and are
 * backend-agnostic.
 *
 * **The board read** is [forProject]: one query returning every *published* issue
 * of a project (drafts excluded) as ready-to-map [IssueRecord] rows — no per-card
 * follow-up. Its companions [labelsForProject] and [componentsForProject] answer
 * the whole board's labels and components in one query each rather than one per
 * card (the classic N+1), so painting a board is three reads whatever its size.
 * The rows carry foreign-key ids, not vocabulary names: resolving a status or a
 * label to its display name is the caller's join against the project vocabulary,
 * not this seam's job.
 *
 * @see se.soderbjorn.lunicle.store.IssueStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.IssueRecord

interface IssueStore {
    /**
     * Create a draft issue and return its id and allocated per-project number.
     * The row is invisible on the board until [publish]; see [forProject].
     */
    suspend fun insertDraft(
        projectId: Long,
        title: String,
        statusId: Long,
        priorityId: Long,
        author: Author,
        createdAt: Long? = null,
        agentName: String? = null,
    ): Pair<Long, Long>

    /** Write the editor's fields and make the issue visible. One statement. */
    suspend fun publish(
        id: Long,
        title: String,
        description: String,
        statusId: Long,
        priorityId: Long,
        resolutionId: Long?,
        assigneeId: Long?,
        sprintId: Long?,
        plannedVersionId: Long?,
        fixedVersionId: Long?,
        updatedAt: Long? = null,
    )

    /** Schedule an issue into a sprint, or send it back to the backlog with null. */
    suspend fun setSprint(id: Long, sprintId: Long?)

    /** Record the fixed version on a drag-to-close, or clear it with null. */
    suspend fun setFixedVersion(id: Long, fixedVersionId: Long?)

    /** How many issues sit in each of a project's sprints. */
    suspend fun usageBySprint(projectId: Long): Map<Long, Long>

    /** How many issues reference each of a project's versions — planned and fixed summed. */
    suspend fun usageByVersion(projectId: Long): Map<Long, Long>

    /** Hand an issue to somebody, or take it back off them with null. */
    suspend fun setAssignee(id: Long, assigneeId: Long?)

    /** Every issue whose description might mention somebody, as id-to-description pairs. */
    suspend fun withPossibleMentions(): List<Pair<Long, String>>

    /** Every issue whose description might link to an attachment, as id-to-description pairs. */
    suspend fun withAttachmentLinks(): List<Pair<Long, String>>

    /** Replace a description without touching anything else — leaves `updated_at` alone. */
    suspend fun setDescription(id: Long, description: String)

    /** Write the editor's fields without publishing. */
    suspend fun update(
        id: Long,
        title: String,
        description: String,
        statusId: Long,
        priorityId: Long,
        resolutionId: Long?,
        sprintId: Long?,
        plannedVersionId: Long?,
        fixedVersionId: Long?,
    )

    /** Rewrite an issue's attribution columns — author, creation date, agent label. */
    suspend fun editAttribution(id: Long, createdAt: Long, author: Author, agentName: String?)

    /** Move an issue to another column, clearing or setting its resolution. Bumps `updated_at`. */
    suspend fun setStatus(id: Long, statusId: Long, resolutionId: Long?)

    /** Move an issue into another priority, and nothing else. */
    suspend fun setPriority(id: Long, priorityId: Long)

    /** Rank a whole board group, in the order given, atomically (positions 1..n). */
    suspend fun setGroupOrder(issueIds: List<Long>)

    /**
     * Attach an issue to an epic, or detach it with null. The same-project,
     * one-level and no-cycle rules are the caller's to enforce; see
     * `IssueRepository.setParent`. Leaves child rank alone.
     */
    suspend fun setParent(id: Long, parentId: Long?)

    /** Rank one epic's children, in the order given, atomically (positions 1..n). */
    suspend fun setChildOrder(childIds: List<Long>)

    /** The children of one epic, in their work order, drafts excluded. One query. */
    suspend fun childrenOf(parentId: Long): List<IssueRecord>

    suspend fun delete(id: Long)

    suspend fun findById(id: Long): IssueRecord?

    /** The board read: every published issue in the project, drafts excluded. One query. */
    suspend fun forProject(projectId: Long): List<IssueRecord>

    /** Replace this issue's labels and components wholesale, in one transaction. */
    suspend fun setLabelsAndComponents(
        issueId: Long,
        projectId: Long,
        labelIds: List<Long>,
        componentIds: List<Long>,
    )

    suspend fun labelsFor(issueId: Long): List<Long>

    suspend fun componentsFor(issueId: Long): List<Long>

    /** Every issue's labels in one project, as issue id → label ids. One query for the board. */
    suspend fun labelsForProject(projectId: Long): Map<Long, List<Long>>

    /** As [labelsForProject], for components. */
    suspend fun componentsForProject(projectId: Long): Map<Long, List<Long>>

    /**
     * How many issues hold each status in one project, as status id → count.
     *
     * Drafts are excluded, like everywhere else. They were counted once, because
     * a draft holds a `status_id` like any other row and it is a draft that makes
     * the delete fail — and that made the leftmost column of a long-lived project
     * undeletable, refused over rows its admin could not see (LNL-183). The
     * counts answer for the visible board; [deleteDraftsWithStatus] and
     * [sweepAbandonedDrafts] deal with the invisible rows.
     */
    suspend fun usageByStatus(projectId: Long): Map<Long, Long>

    /** As [usageByStatus], for priorities. */
    suspend fun usageByPriority(projectId: Long): Map<Long, Long>

    /** As [usageByStatus], for resolutions. */
    suspend fun usageByResolution(projectId: Long): Map<Long, Long>

    /** As [usageByStatus], for labels — a cascade, so a count and never a refusal. */
    suspend fun usageByLabel(projectId: Long): Map<Long, Long>

    /** As [usageByLabel], for components. */
    suspend fun usageByComponent(projectId: Long): Map<Long, Long>

    /**
     * Delete this project's drafts sitting in one status, and say how many went.
     *
     * The other half of leaving drafts out of [usageByStatus]: a draft is an
     * unpublished row nobody but its author has seen, so it cannot be what an
     * admin is told to "move somewhere else first" — deleting the column takes it
     * with the column. Called by `VocabularyRepository.delete` immediately before
     * the status row goes, which keeps SQLite's RESTRICT as the guarantee behind
     * it rather than replacing it.
     *
     * Project-scoped as well as status-scoped: a status id belonging to another
     * project deletes nothing.
     */
    suspend fun deleteDraftsWithStatus(projectId: Long, statusId: Long): Long

    /** As [deleteDraftsWithStatus], for a priority — `priority_id` is NOT NULL too. */
    suspend fun deleteDraftsWithPriority(projectId: Long, priorityId: Long): Long

    /**
     * Startup housekeeping: delete drafts created before [cutoff], and say how
     * many went.
     *
     * "New issue" writes its row before the editor is filled in, so an inline
     * image has an owner from the first keystroke. Cancel deletes that row; a
     * closed tab, a crash or a request that died in flight leaves it behind, and
     * nothing else ever collects it. The caller passes the cutoff — see
     * `Application.module`, which sweeps here just before the attachment sweep so
     * a freed draft's file is collected on the same boot.
     */
    suspend fun sweepAbandonedDrafts(cutoff: Long): Long
}
