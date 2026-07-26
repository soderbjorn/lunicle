/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.IssueStore] — the
 * LNL-111 *hardest aggregate*, and the store that sets the document-model
 * conventions the fan-out tickets follow.
 *
 * **Why this one is the pattern-setter.** In SQLite an issue is a row plus two join
 * tables, and three of its behaviours are exactly where a document backend is most
 * likely to drift out of parity: a draft must be invisible on the board until it is
 * published, labels and components must round-trip through a wholesale replace, and
 * the usage counts the vocabulary editor reads must include drafts. Proving those
 * on the emulator against [se.soderbjorn.lunicle.store.IssueStoreContract] — the
 * same contract the SQLite gateway passes — is what this class exists to do.
 *
 * ── Document model ──────────────────────────────────────────────────────────
 *
 * One document per issue in `issues/{id}`, where `{id}` is the global `Long` id the
 * rest of the system addresses issues by, allocated from `_counters/issues` (see
 * [FirestoreCounters]). The two join tables collapse onto the document: `labelIds`
 * and `componentIds` are arrays *on the issue*, which is what turns
 * [labelsForProject]/[componentsForProject] and [usageByLabel]/[usageByComponent]
 * into a single query over the project's issues rather than a join — the
 * denormalisation the SQLite `IssueStore` preamble flagged as this backend's
 * concern. Every foreign key stays an id, never a resolved name: mapping a status
 * or a label to its display text is the caller's join against the project
 * vocabulary, here as there.
 *
 * ── The one deliberate parity gap: board ordering ───────────────────────────
 *
 * [forProject] returns the right *set* — every published issue of the project,
 * drafts excluded — but not yet the SQLite *order*. That order groups issues by
 * `resolutions.position` then `priorities.position`, columns that live in the
 * vocabulary tables and not on the issue document, so no Firestore query can
 * express it. Rather than fake a different order, this returns the issues sorted by
 * the two keys it *does* hold — manual `sortOrder` then `number` — and leaves the
 * vocabulary-position grouping to the caller that already loads that vocabulary to
 * paint the board. Wiring that up (and declaring the composite index the
 * `projectId == … AND isDraft == false` query needs in production) is LNL-122's
 * job; on the emulator the query is served without an index and the contract does
 * not pin the order, so it passes here regardless.
 *
 * @see FirestoreProvider
 * @see FirestoreCounters
 * @see se.soderbjorn.lunicle.store.IssueStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.store.IssueStore

class FirestoreIssueStore(
    private val firestore: Firestore,
    private val now: () -> Long = System::currentTimeMillis,
) : IssueStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    /**
     * Create a draft and allocate its ids in one transaction.
     *
     * The global id (the document key) and the per-project `number` (the 123 in
     * FOO-123) are drawn from their two counters together with the write of the
     * issue itself, so a crash can never leave a counter advanced past an issue
     * that was never written, and two concurrent creates can never collide on a
     * number. As with the SQLite `MAX+1`, the counter is monotonic: a cancelled
     * draft burns its number, and a board may read FOO-12, FOO-14.
     *
     * The single [timestamp] is bound to both `createdAt` and `updatedAt`, for the
     * same reason the SQLite store binds one value to both columns — two `now()`
     * reads can straddle a millisecond and make an issue claim it was edited before
     * it existed.
     */
    override suspend fun insertDraft(
        projectId: Long,
        title: String,
        statusId: Long,
        priorityId: Long,
        author: Author,
        createdAt: Long?,
        agentName: String?,
    ): Pair<Long, Long> {
        val timestamp = createdAt ?: now()
        return firestore.runTransaction { txn ->
            val allocated = counters.next(txn, GLOBAL_ID_COUNTER, numberCounter(projectId))
            val id = allocated.getValue(GLOBAL_ID_COUNTER)
            val number = allocated.getValue(numberCounter(projectId))
            txn.set(
                doc(id),
                mapOf(
                    ID to id,
                    PROJECT_ID to projectId,
                    NUMBER to number,
                    TITLE to title,
                    DESCRIPTION to "",
                    STATUS_ID to statusId,
                    PRIORITY_ID to priorityId,
                    RESOLUTION_ID to null,
                    IS_DRAFT to true,
                    CREATED_AT to timestamp,
                    UPDATED_AT to timestamp,
                    SORT_ORDER to 0L,
                    CREATED_BY to author.accountId,
                    CREATED_BY_EXTERNAL to author.externalName,
                    AGENT_NAME to agentName,
                    ASSIGNEE_ID to null,
                    SPRINT_ID to null,
                    PLANNED_VERSION_ID to null,
                    FIXED_VERSION_ID to null,
                    PARENT_ID to null,
                    CHILD_ORDER to 0L,
                    LABEL_IDS to emptyList<Long>(),
                    COMPONENT_IDS to emptyList<Long>(),
                ),
            )
            id to number
        }.await()
    }

    /** Write the editor's fields and clear the draft flag — the one write that makes an issue visible. */
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
    ) {
        doc(id).update(
            mapOf(
                TITLE to title,
                DESCRIPTION to description,
                STATUS_ID to statusId,
                PRIORITY_ID to priorityId,
                RESOLUTION_ID to resolutionId,
                ASSIGNEE_ID to assigneeId,
                SPRINT_ID to sprintId,
                PLANNED_VERSION_ID to plannedVersionId,
                FIXED_VERSION_ID to fixedVersionId,
                UPDATED_AT to (updatedAt ?: now()),
                IS_DRAFT to false,
            ),
        ).await()
    }

    override suspend fun setSprint(id: Long, sprintId: Long?) {
        doc(id).update(mapOf(SPRINT_ID to sprintId, UPDATED_AT to now())).await()
    }

    /** Record the fixed version alongside a drag-to-close, or clear it with null. See Issues.sq's setFixedVersion. */
    override suspend fun setFixedVersion(id: Long, fixedVersionId: Long?) {
        doc(id).update(mapOf(FIXED_VERSION_ID to fixedVersionId, UPDATED_AT to now())).await()
    }

    override suspend fun usageBySprint(projectId: Long): Map<Long, Long> =
        countBy(projectId) { it.getLong(SPRINT_ID) }

    /**
     * How many issues reference each version — planned and fixed summed, so an issue
     * naming the same version in both roles counts twice, matching the SQLite
     * `UNION ALL`. Nulls (no version) drop out, like the other usage reads.
     */
    override suspend fun usageByVersion(projectId: Long): Map<Long, Long> =
        issuesOf(projectId)
            .flatMap { listOfNotNull(it.getLong(PLANNED_VERSION_ID), it.getLong(FIXED_VERSION_ID)) }
            .groupingBy { it }.eachCount().mapValues { it.value.toLong() }

    override suspend fun setAssignee(id: Long, assigneeId: Long?) {
        doc(id).update(mapOf(ASSIGNEE_ID to assigneeId, UPDATED_AT to now())).await()
    }

    override suspend fun withPossibleMentions(): List<Pair<Long, String>> = descriptionsContaining("@")

    override suspend fun withAttachmentLinks(): List<Pair<Long, String>> = descriptionsContaining("/api/attachments/")

    /** Replace a description alone, deliberately leaving `updatedAt` untouched — the display-name rewrite's only write. */
    override suspend fun setDescription(id: Long, description: String) {
        doc(id).update(mapOf(DESCRIPTION to description)).await()
    }

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
    ) {
        doc(id).update(
            mapOf(
                TITLE to title,
                DESCRIPTION to description,
                STATUS_ID to statusId,
                PRIORITY_ID to priorityId,
                RESOLUTION_ID to resolutionId,
                SPRINT_ID to sprintId,
                PLANNED_VERSION_ID to plannedVersionId,
                FIXED_VERSION_ID to fixedVersionId,
                UPDATED_AT to now(),
            ),
        ).await()
    }

    override suspend fun editAttribution(id: Long, createdAt: Long, author: Author, agentName: String?) {
        doc(id).update(
            mapOf(
                CREATED_AT to createdAt,
                CREATED_BY to author.accountId,
                CREATED_BY_EXTERNAL to author.externalName,
                AGENT_NAME to agentName,
            ),
        ).await()
    }

    override suspend fun setStatus(id: Long, statusId: Long, resolutionId: Long?) {
        doc(id).update(mapOf(STATUS_ID to statusId, RESOLUTION_ID to resolutionId, UPDATED_AT to now())).await()
    }

    override suspend fun setPriority(id: Long, priorityId: Long) {
        doc(id).update(mapOf(PRIORITY_ID to priorityId, UPDATED_AT to now())).await()
    }

    /**
     * Rank a whole group 1..n in the order given, in one batch — and, like the
     * SQLite `setSortOrder`, the one write here that leaves `updatedAt` alone,
     * because a rank is a fact about the board's arrangement rather than the issue.
     * A single [com.google.cloud.firestore.WriteBatch] applies every renumber
     * atomically, so no reader ever sees two cards claiming the same rank.
     */
    override suspend fun setGroupOrder(issueIds: List<Long>) {
        val batch = firestore.batch()
        issueIds.forEachIndexed { index, id -> batch.update(doc(id), SORT_ORDER, (index + 1).toLong()) }
        batch.commit().await()
    }

    /**
     * Attach an issue to an epic, or detach it with null — setSprint's sibling.
     * The same-project, one-level and no-cycle rules are checked in
     * `IssueRepository` before this runs; here as in SQLite the store only writes.
     * Leaves child rank alone: a freshly attached child arrives unranked.
     *
     * Unlike SQLite there is no `ON DELETE SET NULL` to lean on — deleting an epic
     * document leaves its children pointing at a `parentId` that no longer resolves.
     * `IssueRepository.delete` detaches the children first; see its preamble.
     */
    override suspend fun setParent(id: Long, parentId: Long?) {
        doc(id).update(mapOf(PARENT_ID to parentId, UPDATED_AT to now())).await()
    }

    /**
     * Rank one epic's children 1..n in the order given, in one batch — setGroupOrder
     * for siblings, and, like it, the write that leaves `updatedAt` alone because a
     * work-order is a fact about the epic's arrangement, not any child's edits.
     */
    override suspend fun setChildOrder(childIds: List<Long>) {
        val batch = firestore.batch()
        childIds.forEachIndexed { index, id -> batch.update(doc(id), CHILD_ORDER, (index + 1).toLong()) }
        batch.commit().await()
    }

    /**
     * The children of one epic, in their work order, drafts excluded. One query on
     * `parentId`, sorted in memory by `childOrder` then `number` — the SQLite
     * childrenOf order — for the class preamble's reason: an index-free equality
     * query is served on the emulator whatever the sort, and the ordering the epic
     * set is what matters, not a Firestore ORDER BY.
     */
    override suspend fun childrenOf(parentId: Long): List<IssueRecord> =
        collection()
            .whereEqualTo(PARENT_ID, parentId)
            .whereEqualTo(IS_DRAFT, false)
            .get().await()
            .documents.map { it.toRecord() }
            .sortedWith(compareBy({ it.childOrder }, { it.number }))

    override suspend fun delete(id: Long) {
        doc(id).delete().await()
    }

    override suspend fun findById(id: Long): IssueRecord? =
        doc(id).get().await().takeIf { it.exists() }?.toRecord()

    /** The board read: every published issue of the project, drafts excluded. See the class preamble on ordering. */
    override suspend fun forProject(projectId: Long): List<IssueRecord> =
        collection()
            .whereEqualTo(PROJECT_ID, projectId)
            .whereEqualTo(IS_DRAFT, false)
            .get().await()
            .documents.map { it.toRecord() }
            .sortedWith(compareBy({ it.sortOrder }, { it.number }))

    /** Replace this issue's labels and components wholesale — two array overwrites on the one document. */
    override suspend fun setLabelsAndComponents(
        issueId: Long,
        projectId: Long,
        labelIds: List<Long>,
        componentIds: List<Long>,
    ) {
        // projectId carries no constraint here — with the join tables collapsed onto
        // the document there is no composite foreign key to satisfy — but it stays in
        // the signature so the seam reads identically to the SQLite store.
        doc(issueId).update(mapOf(LABEL_IDS to labelIds, COMPONENT_IDS to componentIds)).await()
    }

    override suspend fun labelsFor(issueId: Long): List<Long> = doc(issueId).get().await().longList(LABEL_IDS)

    override suspend fun componentsFor(issueId: Long): List<Long> = doc(issueId).get().await().longList(COMPONENT_IDS)

    override suspend fun labelsForProject(projectId: Long): Map<Long, List<Long>> = arraysForProject(projectId, LABEL_IDS)

    override suspend fun componentsForProject(projectId: Long): Map<Long, List<Long>> =
        arraysForProject(projectId, COMPONENT_IDS)

    override suspend fun usageByStatus(projectId: Long): Map<Long, Long> = countBy(projectId) { it.getLong(STATUS_ID) }

    override suspend fun usageByPriority(projectId: Long): Map<Long, Long> =
        countBy(projectId) { it.getLong(PRIORITY_ID) }

    override suspend fun usageByResolution(projectId: Long): Map<Long, Long> =
        countBy(projectId) { it.getLong(RESOLUTION_ID) }

    override suspend fun usageByLabel(projectId: Long): Map<Long, Long> = usageByArray(projectId, LABEL_IDS)

    override suspend fun usageByComponent(projectId: Long): Map<Long, Long> = usageByArray(projectId, COMPONENT_IDS)

    // ── Shared reads over a project's issues ─────────────────────────────────
    // Each is one query for the whole project — the document-model answer to the
    // SQLite GROUP BY — and each includes drafts, because a draft holds a status,
    // a priority and its labels like any other issue and it is a draft that makes a
    // vocabulary delete fail; a count that hid it would promise a delete that then
    // explodes. Absent means zero, so these stay plain maps with no zero rows.

    /** Count the project's issues by a nullable scalar key, dropping the nulls (a backlog / open issue is not a group). */
    private suspend fun countBy(projectId: Long, key: (DocumentSnapshot) -> Long?): Map<Long, Long> =
        issuesOf(projectId).mapNotNull(key).groupingBy { it }.eachCount().mapValues { it.value.toLong() }

    /** Count the project's issues by membership in an id array (labels, components) — one issue may count many times. */
    private suspend fun usageByArray(projectId: Long, field: String): Map<Long, Long> =
        issuesOf(projectId).flatMap { it.longList(field) }.groupingBy { it }.eachCount().mapValues { it.value.toLong() }

    /** Every issue's id-array for the project, as issue id → ids, omitting issues that carry none. */
    private suspend fun arraysForProject(projectId: Long, field: String): Map<Long, List<Long>> =
        issuesOf(projectId).associate { it.getLong(ID)!! to it.longList(field) }.filterValues { it.isNotEmpty() }

    private suspend fun issuesOf(projectId: Long): List<DocumentSnapshot> =
        collection().whereEqualTo(PROJECT_ID, projectId).get().await().documents

    /**
     * Every issue whose description contains [needle], as id-to-description pairs.
     *
     * A full-collection scan filtered in memory, because Firestore has no substring
     * predicate to push the SQLite `LIKE '%needle%'` down into. Its two callers —
     * the display-name rewrite and the attachment-link repair — are rare startup
     * maintenance passes over every project at once, so the scan is acceptable
     * where it would not be on a board read.
     */
    private suspend fun descriptionsContaining(needle: String): List<Pair<Long, String>> =
        collection().get().await().documents
            .map { it.getLong(ID)!! to it.getString(DESCRIPTION).orEmpty() }
            .filter { it.second.contains(needle) }

    private companion object {
        const val COLLECTION = "issues"
        const val GLOBAL_ID_COUNTER = "issues"

        /** The per-project FOO-<n> counter, `_counters/issueNumber-<projectId>`. */
        fun numberCounter(projectId: Long) = "issueNumber-$projectId"

        const val ID = "id"
        const val PROJECT_ID = "projectId"
        const val NUMBER = "number"
        const val TITLE = "title"
        const val DESCRIPTION = "description"
        const val STATUS_ID = "statusId"
        const val PRIORITY_ID = "priorityId"
        const val RESOLUTION_ID = "resolutionId"
        const val IS_DRAFT = "isDraft"
        const val CREATED_AT = "createdAt"
        const val UPDATED_AT = "updatedAt"
        const val SORT_ORDER = "sortOrder"
        const val CREATED_BY = "createdBy"
        const val CREATED_BY_EXTERNAL = "createdByExternal"
        const val AGENT_NAME = "agentName"
        const val ASSIGNEE_ID = "assigneeId"
        const val SPRINT_ID = "sprintId"
        const val PLANNED_VERSION_ID = "plannedVersionId"
        const val FIXED_VERSION_ID = "fixedVersionId"
        const val PARENT_ID = "parentId"
        const val CHILD_ORDER = "childOrder"
        const val LABEL_IDS = "labelIds"
        const val COMPONENT_IDS = "componentIds"
    }
}

private fun DocumentSnapshot.toRecord(): IssueRecord = IssueRecord(
    id = getLong("id")!!,
    projectId = getLong("projectId")!!,
    number = getLong("number")!!,
    title = getString("title").orEmpty(),
    description = getString("description").orEmpty(),
    statusId = getLong("statusId")!!,
    priorityId = getLong("priorityId")!!,
    resolutionId = getLong("resolutionId"),
    isDraft = getBoolean("isDraft") ?: false,
    createdAt = getLong("createdAt")!!,
    updatedAt = getLong("updatedAt")!!,
    sortOrder = getLong("sortOrder") ?: 0L,
    author = authorOf(getLong("createdBy"), getString("createdByExternal")),
    agentName = getString("agentName"),
    assigneeId = getLong("assigneeId"),
    sprintId = getLong("sprintId"),
    plannedVersionId = getLong("plannedVersionId"),
    fixedVersionId = getLong("fixedVersionId"),
    parentId = getLong("parentId"),
    childOrder = getLong("childOrder") ?: 0L,
)

/** An id-array field read back, or empty when absent. Firestore stores integer arrays as `List<Long>`. */
private fun DocumentSnapshot.longList(field: String): List<Long> {
    @Suppress("UNCHECKED_CAST")
    return (get(field) as? List<Long>).orEmpty()
}
