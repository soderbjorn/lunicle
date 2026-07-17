/**
 * The issues table and its two join tables.
 *
 * Stores only: SQL in, data class out. The rules — publishing an issue and its
 * labels together or not at all, deleting the attachment *files* a cascade
 * cannot reach — live in [IssueRepository].
 *
 * @see IssueRepository
 * @see Database
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * An issue as this server knows it.
 *
 * @property number the per-project number: the 123 in FOO-123. Distinct from
 *   [id], which is global and an implementation detail.
 * @property title the short description. Named `title` rather than `label`
 *   because `labels` is already a table; see Issues.sq.
 * @property isDraft whether this issue is still being written. A draft appears
 *   on nobody's board — see `forProject`.
 * @property author who wrote it: an account, an imported name, or nobody — see
 *   [Author]. Nobody is what a deleted account leaves behind, and an imported
 *   name is unowned for the same purposes, so both are issues only an admin or
 *   a `change_unowned_issues` holder can edit. That is correct in both cases —
 *   see [AccessControl.canEditIssue].
 */
data class IssueRecord(
    val id: Long,
    val projectId: Long,
    val number: Long,
    val title: String,
    val description: String,
    val statusId: Long,
    val priorityId: Long,
    /** Why it was closed, or null because it is not. See Issues.sq. */
    val resolutionId: Long?,
    val isDraft: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** Manual rank within this issue's board group; 0 means unranked. See Issues.sq. */
    val sortOrder: Long,
    val author: Author,
    /**
     * The agent that filed this on the author's behalf, or null when a human did.
     *
     * Orthogonal to [author], not a fourth kind of it: the issue is still the
     * author's, and this records only that an agent held the pen for them. Set by
     * the MCP tools; a person filing in the web app is not an agent. See Issues.sq.
     */
    val agentName: String?,
)

/** Reads and writes `issues`, `issue_labels` and `issue_components`. */
class IssueStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Create a draft issue and return its id and allocated number.
     *
     * "New issue" inserts immediately so that an inline image upload has an
     * issue to attach to. The row is a draft until the editor's OK; see
     * [publish].
     *
     * @param createdAt when this issue should claim to have been written, or null
     *   — which is every caller but one — for now. Only an admin backfilling
     *   history over MCP passes it; see [AccessControl.canAttributeWrites]. It is
     *   bound to `updated_at` as well, for the reason below, so a backfilled issue
     *   sorts on the board where its history says it belongs rather than at the top.
     * @param agentName the agent that filed it on the author's behalf, or null when
     *   a human did. Only the MCP path passes a name; the web path leaves it null.
     * @return the new issue's id and its FOO-<number>.
     */
    suspend fun insertDraft(
        projectId: Long,
        title: String,
        statusId: Long,
        priorityId: Long,
        author: Author,
        createdAt: Long? = null,
        agentName: String? = null,
    ): Pair<Long, Long> = withContext(DatabaseDispatcher) {
        // The number is allocated inside the INSERT, by MAX+1, so a gap after a
        // cancelled draft is possible and a reused number is not. See Issues.sq.
        //
        // ONE value, bound to both created_at and updated_at — never two calls and
        // never two arguments. Two `now()` calls can straddle a millisecond, and an
        // issue whose updated_at is one tick after its created_at would claim to
        // have been edited before it existed. A supplied timestamp goes through the
        // same single binding rather than beside it, which is what makes the
        // backfill path unable to reintroduce the straddle by hand.
        val timestamp = createdAt ?: now()
        val row = database.issuesQueries
            .insert(
                projectId, projectId, title, "", statusId, priorityId, timestamp, timestamp,
                author.accountId, author.externalName, agentName,
            )
            .executeAsOne()
        row.id to row.number
    }

    /**
     * Write the editor's fields and make the issue visible. One statement.
     *
     * @param updatedAt what to stamp, or null — every ordinary caller — for now.
     *   The one caller that passes it is an admin backfilling over MCP, and it
     *   passes the SAME value it gave [insertDraft]'s `createdAt`. That is not a
     *   convention, it is the requirement: this statement stamps `updated_at`
     *   unconditionally, so a backfilled issue that published without it would be
     *   created in 2019 and last touched today — the two columns disagreeing is
     *   precisely what Issues.sq's `updated_at` comment forbids, and the board
     *   would sort the imported issue above everything real.
     */
    suspend fun publish(
        id: Long,
        title: String,
        description: String,
        statusId: Long,
        priorityId: Long,
        resolutionId: Long?,
        updatedAt: Long? = null,
    ): Unit = withContext(DatabaseDispatcher) {
        database.issuesQueries.publish(
            title, description, statusId, priorityId, resolutionId, updatedAt ?: now(), id,
        )
    }

    suspend fun update(
        id: Long,
        title: String,
        description: String,
        statusId: Long,
        priorityId: Long,
        resolutionId: Long?,
    ): Unit = withContext(DatabaseDispatcher) {
        database.issuesQueries.update(title, description, statusId, priorityId, resolutionId, now(), id)
    }

    /**
     * Move an issue to another column.
     *
     * Drag-and-drop's only write, and deliberately not a special case: the
     * route gates it with the same `canEditIssue` the editor uses. See §2.
     *
     * It bumps `updated_at` — a drag is an edit, and the most common one. See
     * Issues.sq's `setStatus`.
     */
    suspend fun setStatus(id: Long, statusId: Long, resolutionId: Long?): Unit =
        withContext(DatabaseDispatcher) {
            database.issuesQueries.setStatus(statusId, resolutionId, now(), id)
        }

    /**
     * Rank a whole group, in the order given.
     *
     * Takes the group rather than one card, because a rank is only meaningful
     * against its neighbours: "put this third" is a statement about all of them.
     * Renumbering everything 1..n is also what keeps a group from drifting into
     * fractional ranks or exhausting the gaps between integers — the classic
     * failure of "insert between A and B" schemes — at the cost of one UPDATE per
     * card in a group, which is a handful.
     *
     * From 1, not 0: 0 is reserved for "never ranked", and a new issue arrives
     * with it. That is what floats a new card to the top of a group somebody has
     * already arranged — an arrangement cannot have an opinion about a card that
     * did not exist when it was made, and the top is where it will be noticed.
     * Note this differs from a group nobody has ranked, where everything is 0 and
     * a new card lands at the bottom on `number`.
     *
     * In one transaction. A half-renumbered group is a group with two cards
     * claiming rank 3, which the board would order by a tie-break nobody asked
     * for — and the user would see their drag half-applied.
     *
     * Deliberately does NOT touch `updated_at`; see Issues.sq's setSortOrder.
     */
    suspend fun setGroupOrder(issueIds: List<Long>): Unit = withContext(DatabaseDispatcher) {
        database.transaction {
            issueIds.forEachIndexed { index, id ->
                database.issuesQueries.setSortOrder((index + 1).toLong(), id)
            }
        }
    }

    suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.issuesQueries.delete(id)
    }

    suspend fun findById(id: Long): IssueRecord? = withContext(DatabaseDispatcher) {
        database.issuesQueries.findById(id).executeAsOneOrNull()?.toRecord()
    }

    /** The board: every published issue in the project. Drafts excluded. */
    suspend fun forProject(projectId: Long): List<IssueRecord> = withContext(DatabaseDispatcher) {
        database.issuesQueries.forProject(projectId).executeAsList().map { it.toRecord() }
    }

    /**
     * Replace this issue's labels and components wholesale.
     *
     * Delete-then-insert rather than a diff: the editor sends the set it wants,
     * and computing the difference would be more code to arrive at the same
     * rows. In one transaction, so a failure mid-way cannot leave an issue
     * carrying half of its old labels and half of its new ones.
     *
     * [projectId] is passed to the join rows because the composite foreign keys
     * need it — it is what makes a label from another project unstorable rather
     * than merely unlikely. See IssueLabels.sq.
     */
    suspend fun setLabelsAndComponents(
        issueId: Long,
        projectId: Long,
        labelIds: List<Long>,
        componentIds: List<Long>,
    ): Unit = withContext(DatabaseDispatcher) {
        database.transaction {
            database.issueLabelsQueries.deleteForIssue(issueId)
            labelIds.forEach { database.issueLabelsQueries.insert(issueId, it, projectId) }
            database.issueComponentsQueries.deleteForIssue(issueId)
            componentIds.forEach { database.issueComponentsQueries.insert(issueId, it, projectId) }
        }
    }

    suspend fun labelsFor(issueId: Long): List<Long> = withContext(DatabaseDispatcher) {
        database.issueLabelsQueries.forIssue(issueId).executeAsList()
    }

    suspend fun componentsFor(issueId: Long): List<Long> = withContext(DatabaseDispatcher) {
        database.issueComponentsQueries.forIssue(issueId).executeAsList()
    }

    /**
     * Every issue's labels in one project, as issue id → label ids.
     *
     * One query for the whole board. The obvious alternative — asking per card
     * — is a request per issue, which is the classic N+1 and shows up as a
     * board that takes a second to paint once a project has real content.
     */
    suspend fun labelsForProject(projectId: Long): Map<Long, List<Long>> =
        withContext(DatabaseDispatcher) {
            database.issueLabelsQueries.forProject(projectId).executeAsList()
                .groupBy({ it.issue_id }, { it.label_id })
        }

    /** As [labelsForProject], for components. */
    suspend fun componentsForProject(projectId: Long): Map<Long, List<Long>> =
        withContext(DatabaseDispatcher) {
            database.issueComponentsQueries.forProject(projectId).executeAsList()
                .groupBy({ it.issue_id }, { it.component_id })
        }

    /**
     * How many issues hold each vocabulary row in one project, as row id → count.
     *
     * What the settings dialog is asking is "what does deleting this cost?", and
     * the answer differs by kind: for a status or a priority the count is a
     * refusal (the database will not let the row go while an issue points at it),
     * and for a label it is a sentence (the row goes, the issues are unlabelled).
     * [VocabularyRepository] knows which is which; this only counts.
     *
     * Drafts are included. See Issues.sq's usageByStatus — a draft holds a
     * status_id like any other row, so it is a draft that makes the delete fail,
     * and a count that hid it would promise a delete that then explodes.
     *
     * Absent means zero, which is why these are plain maps rather than maps with
     * a zero row per unused id: a `GROUP BY` has nothing to group for a status
     * nobody uses, and `[id] ?: 0` reads better than a query that manufactures the
     * empty groups.
     */
    suspend fun usageByStatus(projectId: Long): Map<Long, Long> = withContext(DatabaseDispatcher) {
        database.issuesQueries.usageByStatus(projectId).executeAsList()
            .associate { it.status_id to it.uses }
    }

    /** As [usageByStatus], for priorities. */
    suspend fun usageByPriority(projectId: Long): Map<Long, Long> = withContext(DatabaseDispatcher) {
        database.issuesQueries.usageByPriority(projectId).executeAsList()
            .associate { it.priority_id to it.uses }
    }

    /**
     * As [usageByStatus], for resolutions.
     *
     * `resolution_id` is the one nullable reference on `issues` — and it arrives
     * here as a non-null `Long` anyway, because the query's `IS NOT NULL` is
     * something SQLDelight reads: it narrows the column's type from the WHERE
     * clause. So there is no null to handle, and code written to handle one would
     * be dead the day it was written. See Issues.sq's usageByResolution for why
     * the clause is there at all.
     */
    suspend fun usageByResolution(projectId: Long): Map<Long, Long> = withContext(DatabaseDispatcher) {
        database.issuesQueries.usageByResolution(projectId).executeAsList()
            .associate { it.resolution_id to it.uses }
    }

    /** As [usageByStatus], for labels — a cascade, so a count and never a refusal. */
    suspend fun usageByLabel(projectId: Long): Map<Long, Long> = withContext(DatabaseDispatcher) {
        database.issueLabelsQueries.usageByLabel(projectId).executeAsList()
            .associate { it.label_id to it.uses }
    }

    /** As [usageByLabel], for components. */
    suspend fun usageByComponent(projectId: Long): Map<Long, Long> = withContext(DatabaseDispatcher) {
        database.issueComponentsQueries.usageByComponent(projectId).executeAsList()
            .associate { it.component_id to it.uses }
    }
}

private fun se.soderbjorn.lunicle.db.Issues.toRecord(): IssueRecord = IssueRecord(
    id = id,
    projectId = project_id,
    number = number,
    title = title,
    description = description,
    statusId = status_id,
    priorityId = priority_id,
    resolutionId = resolution_id,
    isDraft = is_draft != 0L,
    createdAt = created_at,
    updatedAt = updated_at,
    sortOrder = sort_order,
    author = authorOf(created_by, created_by_external),
    agentName = agent_name,
)
