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
 *   name is unowned for the same purposes, so both are issues only a maintainer
 *   can edit, there being no authorship clause left for anybody to satisfy. That
 *   is correct in both cases — see [AccessControl.canEditIssue].
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
    /**
     * Who is working on this, or null because nobody is.
     *
     * A bare account id rather than an [Author], and the asymmetry is deliberate:
     * an author can be an imported name with nobody behind it, and an assignee
     * cannot. Assigning is a statement that somebody is expected to act, so it
     * needs an account that can sign in and do so. See Issues.sq's assignee_id.
     */
    val assigneeId: Long?,
    /**
     * Which sprint this is scheduled into, or null because it is in the backlog.
     *
     * Null for every issue in a project that has never made a sprint, which is
     * how a pure-kanban board stays untouched by this field existing. Orthogonal
     * to [statusId]: status is how far along the work is, this is when it is
     * meant to happen. See Issues.sq's sprint_id.
     */
    val sprintId: Long?,
    /**
     * The release this is planned for, and the release it was fixed in, or null
     * for each because it is not set (LNL-134). Both point into the one per-project
     * `versions` list; null for every issue in a project that never uses versions,
     * the way [sprintId] is for one that never uses sprints. Orthogonal facts —
     * intent versus record — see Issues.sq's planned_version_id / fixed_version_id.
     */
    val plannedVersionId: Long?,
    val fixedVersionId: Long?,
    /**
     * The epic this issue belongs under, or null because it belongs under none
     * (LNL-55). An issue with children is an epic; there is no separate kind, just
     * this pointer. Null for the common case, and for every issue in a project
     * nobody has arranged into epics. See Issues.sq's parent_id — the same-project,
     * one-level and no-cycle rules it depends on live in [IssueRepository], not the
     * schema.
     */
    val parentId: Long?,
    /**
     * Manual rank among the siblings under [parentId]; 0 means unranked. A separate
     * axis from [sortOrder]: that is board position, this is position in the epic's
     * work order. Meaningless when [parentId] is null. See Issues.sq's child_order.
     */
    val childOrder: Long,
)

/** Reads and writes `issues`, `issue_labels` and `issue_components`. */
class IssueStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.IssueStore {
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
    override suspend fun insertDraft(
        projectId: Long,
        title: String,
        statusId: Long,
        priorityId: Long,
        author: Author,
        createdAt: Long?,
        agentName: String?,
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
     * @param assigneeId who is to work on it, or null for nobody. Written on every
     *   publish rather than defaulted, and deliberately without a default value on
     *   this parameter: a caller that "forgot" the assignee would silently
     *   unassign the issue, which is a change nobody asked for and nobody would
     *   see until the person it was taken from noticed. Callers that are not
     *   editing the field pass the issue's current value back explicitly. The
     *   route has already checked the id names somebody who may hold it here — see
     *   [AccessControl.canBeAssigned].
     * @param updatedAt what to stamp, or null — every ordinary caller — for now.
     *   The one caller that passes it is an admin backfilling over MCP, and it
     *   passes the SAME value it gave [insertDraft]'s `createdAt`. That is not a
     *   convention, it is the requirement: this statement stamps `updated_at`
     *   unconditionally, so a backfilled issue that published without it would be
     *   created in 2019 and last touched today — the two columns disagreeing is
     *   precisely what Issues.sq's `updated_at` comment forbids, and the board
     *   would sort the imported issue above everything real.
     */
    override suspend fun publish(
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
        updatedAt: Long?,
    ): Unit = withContext(DatabaseDispatcher) {
        database.issuesQueries.publish(
            title, description, statusId, priorityId, resolutionId, assigneeId, sprintId,
            plannedVersionId, fixedVersionId, updatedAt ?: now(), id,
        )
    }

    /**
     * Schedule an issue into a sprint, or send it back to the backlog with null.
     *
     * Separate from [publish] because it is a different gesture, not a different
     * permission: the card menu and the planning dialog write immediately and
     * never open the editor, so there is no Save to stage against. The route has
     * already checked the sprint belongs to this issue's project — nothing in the
     * schema will, see Issues.sq's sprint_id.
     */
    override suspend fun setSprint(id: Long, sprintId: Long?): Unit = withContext(DatabaseDispatcher) {
        database.issuesQueries.setSprint(sprintId, now(), id)
    }

    /**
     * Record the fixed version alongside a drag-to-close, or clear it with null.
     *
     * The resolution dialog's inline picker writes here immediately, in the same
     * gesture as the status move — separate from [publish] for [setSprint]'s
     * reason (no Save to stage against). The route has already checked the version
     * belongs to this issue's project; nothing in the schema will. See Issues.sq's
     * setFixedVersion.
     */
    override suspend fun setFixedVersion(id: Long, fixedVersionId: Long?): Unit = withContext(DatabaseDispatcher) {
        database.issuesQueries.setFixedVersion(fixedVersionId, now(), id)
    }

    /** How many issues sit in each of a project's sprints. See Issues.sq. */
    override suspend fun usageBySprint(projectId: Long): Map<Long, Long> = withContext(DatabaseDispatcher) {
        database.issuesQueries.usageBySprint(projectId).executeAsList()
            .mapNotNull { row -> row.sprint_id?.let { it to row.uses } }
            .toMap()
    }

    /**
     * How many issues reference each of a project's versions — planned and fixed
     * summed, so a version named in both roles by one issue counts twice. Never a
     * refusal, like [usageBySprint]: both references are SET NULL. See Issues.sq's
     * usageByVersion.
     */
    override suspend fun usageByVersion(projectId: Long): Map<Long, Long> = withContext(DatabaseDispatcher) {
        database.issuesQueries.usageByVersion(projectId, projectId).executeAsList()
            .mapNotNull { row -> row.version_id?.let { it to row.uses } }
            .toMap()
    }

    /**
     * Hand an issue to somebody, or take it back off them.
     *
     * The "Assign to me" button's only write, and separate from [publish] because
     * it is reached under a different permission — see Issues.sq's setAssignee.
     * The route decides whether this caller may name this assignee; by the time it
     * gets here that has been settled.
     */
    override suspend fun setAssignee(id: Long, assigneeId: Long?): Unit = withContext(DatabaseDispatcher) {
        database.issuesQueries.setAssignee(assigneeId, now(), id)
    }

    /**
     * Every issue whose description might mention somebody, as id-to-description
     * pairs. See Issues.sq's `withPossibleMentions` for what "might" means.
     */
    override suspend fun withPossibleMentions(): List<Pair<Long, String>> = withContext(DatabaseDispatcher) {
        database.issuesQueries.withPossibleMentions().executeAsList().map { it.id to it.description }
    }

    /**
     * Every issue whose description might link to an attachment, as
     * id-to-description pairs. See [AttachmentLinkRepair].
     */
    override suspend fun withAttachmentLinks(): List<Pair<Long, String>> = withContext(DatabaseDispatcher) {
        database.issuesQueries.withAttachmentLinks().executeAsList().map { it.id to it.description }
    }

    /**
     * Replace a description without touching anything else — the display-name
     * rewrite's only write. Deliberately leaves `updated_at` alone; see
     * Issues.sq's `setDescription`.
     */
    override suspend fun setDescription(id: Long, description: String): Unit = withContext(DatabaseDispatcher) {
        database.issuesQueries.setDescription(description, id)
    }

    /**
     * Write the editor's fields without publishing.
     *
     * Currently called by nothing: every edit path goes through [publish], which
     * is idempotent on an issue that is already visible, so this is the narrower
     * half of a pair that never got used. Kept rather than deleted because
     * `update` is a sensible statement to have, but kept *in step* — it grew
     * `sprintId` and now the two version fields with [publish] rather than being
     * left behind, since the failure mode of a stale dead statement is a future
     * caller that quietly does not write a column it looks like it writes.
     */
    override suspend fun update(
        id: Long,
        title: String,
        description: String,
        statusId: Long,
        priorityId: Long,
        resolutionId: Long?,
        sprintId: Long?,
        plannedVersionId: Long?,
        fixedVersionId: Long?,
    ): Unit = withContext(DatabaseDispatcher) {
        database.issuesQueries.update(
            title, description, statusId, priorityId, resolutionId, sprintId,
            plannedVersionId, fixedVersionId, now(), id,
        )
    }

    /**
     * Rewrite an issue's attribution columns — author, creation date, agent label.
     *
     * The admin-only half of `update_issue`, and the only writer of these three
     * after insert: content goes through [publish]/[update], a move through
     * [setStatus], and none of them touch who filed the issue or when. Kept off
     * [publish] on purpose, so an ordinary edit has no path to them. See
     * McpTools.updateIssue.
     *
     * @param createdAt always concrete here, never null-for-now: an edit that does
     *   not move the date passes the issue's existing one straight back.
     */
    override suspend fun editAttribution(
        id: Long,
        createdAt: Long,
        author: Author,
        agentName: String?,
    ): Unit = withContext(DatabaseDispatcher) {
        database.issuesQueries.editAttribution(createdAt, author.accountId, author.externalName, agentName, id)
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
    override suspend fun setStatus(id: Long, statusId: Long, resolutionId: Long?): Unit =
        withContext(DatabaseDispatcher) {
            database.issuesQueries.setStatus(statusId, resolutionId, now(), id)
        }

    /**
     * Move an issue into another priority, and nothing else.
     *
     * The board's write when a card is dragged across a group header — see
     * BoardRoutes' order route, which is the only caller. The editor still sends
     * the whole issue; this exists so a drag does not have to.
     */
    override suspend fun setPriority(id: Long, priorityId: Long): Unit = withContext(DatabaseDispatcher) {
        database.issuesQueries.setPriority(priorityId, now(), id)
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
    override suspend fun setGroupOrder(issueIds: List<Long>): Unit = withContext(DatabaseDispatcher) {
        database.transaction {
            issueIds.forEachIndexed { index, id ->
                database.issuesQueries.setSortOrder((index + 1).toLong(), id)
            }
        }
    }

    /**
     * Attach this issue to an epic, or detach it with null.
     *
     * setSprint's sibling — an immediate relationship write, not one of the
     * editor's staged fields. The route has already checked the parent is in this
     * issue's project, is not itself a child, and does not close a cycle; nothing
     * in the schema will (see Issues.sq's parent_id). Leaves child_order alone: a
     * freshly attached child arrives unranked and sorts by number until
     * [setChildOrder] arranges it.
     */
    override suspend fun setParent(id: Long, parentId: Long?): Unit = withContext(DatabaseDispatcher) {
        database.issuesQueries.setParent(parentId, now(), id)
    }

    /**
     * Rank one epic's children, in the order given. [setGroupOrder] for siblings
     * rather than a board group: renumber 1..n in one transaction, and — like it —
     * deliberately do not bump `updated_at`, because a work-order is a fact about
     * the epic's arrangement, not about any child being edited.
     */
    override suspend fun setChildOrder(childIds: List<Long>): Unit = withContext(DatabaseDispatcher) {
        database.transaction {
            childIds.forEachIndexed { index, id ->
                database.issuesQueries.setChildOrder((index + 1).toLong(), id)
            }
        }
    }

    /** The children of one epic, in their work order. Drafts excluded. See Issues.sq's childrenOf. */
    override suspend fun childrenOf(parentId: Long): List<IssueRecord> = withContext(DatabaseDispatcher) {
        database.issuesQueries.childrenOf(parentId).executeAsList().map { it.toRecord() }
    }

    override suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.issuesQueries.delete(id)
    }

    override suspend fun findById(id: Long): IssueRecord? = withContext(DatabaseDispatcher) {
        database.issuesQueries.findById(id).executeAsOneOrNull()?.toRecord()
    }

    /** The board: every published issue in the project. Drafts excluded. */
    override suspend fun forProject(projectId: Long): List<IssueRecord> = withContext(DatabaseDispatcher) {
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
    override suspend fun setLabelsAndComponents(
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

    override suspend fun labelsFor(issueId: Long): List<Long> = withContext(DatabaseDispatcher) {
        database.issueLabelsQueries.forIssue(issueId).executeAsList()
    }

    override suspend fun componentsFor(issueId: Long): List<Long> = withContext(DatabaseDispatcher) {
        database.issueComponentsQueries.forIssue(issueId).executeAsList()
    }

    /**
     * Every issue's labels in one project, as issue id → label ids.
     *
     * One query for the whole board. The obvious alternative — asking per card
     * — is a request per issue, which is the classic N+1 and shows up as a
     * board that takes a second to paint once a project has real content.
     */
    override suspend fun labelsForProject(projectId: Long): Map<Long, List<Long>> =
        withContext(DatabaseDispatcher) {
            database.issueLabelsQueries.forProject(projectId).executeAsList()
                .groupBy({ it.issue_id }, { it.label_id })
        }

    /** As [labelsForProject], for components. */
    override suspend fun componentsForProject(projectId: Long): Map<Long, List<Long>> =
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
     * Drafts are excluded. See Issues.sq's usageByStatus for the bug that settled
     * it (LNL-183): counting them meant a project's leftmost column could be
     * refused forever over abandoned rows nobody can see, let alone move.
     * [deleteDraftsWithStatus] and [sweepAbandonedDrafts] are what let this count
     * be about the visible board without promising a delete that then explodes.
     *
     * Absent means zero, which is why these are plain maps rather than maps with
     * a zero row per unused id: a `GROUP BY` has nothing to group for a status
     * nobody uses, and `[id] ?: 0` reads better than a query that manufactures the
     * empty groups.
     */
    override suspend fun usageByStatus(projectId: Long): Map<Long, Long> = withContext(DatabaseDispatcher) {
        database.issuesQueries.usageByStatus(projectId).executeAsList()
            .associate { it.status_id to it.uses }
    }

    /** As [usageByStatus], for priorities. */
    override suspend fun usageByPriority(projectId: Long): Map<Long, Long> = withContext(DatabaseDispatcher) {
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
    override suspend fun usageByResolution(projectId: Long): Map<Long, Long> = withContext(DatabaseDispatcher) {
        database.issuesQueries.usageByResolution(projectId).executeAsList()
            .associate { it.resolution_id to it.uses }
    }

    /** As [usageByStatus], for labels — a cascade, so a count and never a refusal. */
    override suspend fun usageByLabel(projectId: Long): Map<Long, Long> = withContext(DatabaseDispatcher) {
        database.issueLabelsQueries.usageByLabel(projectId).executeAsList()
            .associate { it.label_id to it.uses }
    }

    /** As [usageByLabel], for components. */
    override suspend fun usageByComponent(projectId: Long): Map<Long, Long> = withContext(DatabaseDispatcher) {
        database.issueComponentsQueries.usageByComponent(projectId).executeAsList()
            .associate { it.component_id to it.uses }
    }

    /**
     * Clear one status's drafts out of the way of its delete, and say how many.
     *
     * `.value` is the row count SQLDelight hands back from a DELETE — the same
     * shape the OAuth sweeps use, and the reason none of these need a `changes()`
     * query of their own.
     */
    override suspend fun deleteDraftsWithStatus(projectId: Long, statusId: Long): Long =
        withContext(DatabaseDispatcher) {
            database.issuesQueries.deleteDraftsWithStatus(projectId, statusId).value
        }

    /** As [deleteDraftsWithStatus], for a priority. */
    override suspend fun deleteDraftsWithPriority(projectId: Long, priorityId: Long): Long =
        withContext(DatabaseDispatcher) {
            database.issuesQueries.deleteDraftsWithPriority(projectId, priorityId).value
        }

    /** Startup housekeeping. See [se.soderbjorn.lunicle.store.IssueStore.sweepAbandonedDrafts]. */
    override suspend fun sweepAbandonedDrafts(cutoff: Long): Long = withContext(DatabaseDispatcher) {
        database.issuesQueries.deleteDraftsOlderThan(cutoff).value
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
    assigneeId = assignee_id,
    sprintId = sprint_id,
    plannedVersionId = planned_version_id,
    fixedVersionId = fixed_version_id,
    parentId = parent_id,
    childOrder = child_order,
)
