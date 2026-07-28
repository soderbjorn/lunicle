/**
 * What counts as something happening to an issue.
 *
 * [IssueEventStore] writes events; this decides which ones there were. That is a
 * separation worth stating because the interesting part is entirely here: a save
 * arrives carrying the editor's whole field set whether the user touched one
 * field or all of them, so "the title changed" is never something a caller can
 * tell us — it has to be *derived*, by comparing the issue as it stood against
 * the issue as it is about to stand.
 *
 * ── Why this is not inside IssueRepository ─────────────────────────────────
 *
 * Because only one of the five writes that need it goes through the repository.
 * A drag between columns and an "Assign to me" both reach [IssueStore] straight
 * from the route, and `move_issue` reaches it straight from the MCP tools — the
 * same split [IssueNotifier] already lives with, and which `BoardDependencies`
 * documents. Putting the derivation on the repository would mean the three
 * bypassing call sites each re-deriving it, which is three chances to spell it
 * differently.
 *
 * @see IssueEventStore
 * @see IssueEvents.sq
 */
package se.soderbjorn.lunicle

import se.soderbjorn.lunicle.clientserver.IssueEventKind

/**
 * Derives an issue's history events and appends them.
 *
 * Every method here is best-effort in the same sense [IssueRepository.notify] is
 * — see [record] — and every one takes the *before* state explicitly rather than
 * re-reading it. Re-reading after the write would compare the issue against
 * itself and find that nothing ever happened.
 */
class IssueHistory(
    private val events: se.soderbjorn.lunicle.store.IssueEventStore,
    private val statuses: se.soderbjorn.lunicle.store.StatusStore,
    private val labels: se.soderbjorn.lunicle.store.LabelStore,
    private val components: se.soderbjorn.lunicle.store.ComponentStore,
    private val users: se.soderbjorn.lunicle.store.UserStore,
) {
    /**
     * One issue's history, oldest first.
     *
     * A pass-through to [IssueEventStore], and here rather than the route reading
     * that store directly so that "has this deployment got a history at all" is
     * one nullable dependency instead of two that must be set together. A
     * `BoardDependencies` holding an [IssueEventStore] but no [IssueHistory]
     * would read events nothing writes, and the opposite would write events
     * nothing shows — both are states nobody wants and neither would fail to
     * compile.
     */
    suspend fun forIssue(issueId: Long): List<IssueEventRecord> = events.forIssue(issueId)

    /**
     * Delete one issue's whole history, because the issue is being deleted.
     *
     * A pass-through to [IssueEventStore.deleteForIssue], and here for exactly the
     * reason [forIssue] is: "has this deployment got a history at all" stays one
     * nullable dependency. `IssueRepository` holds this class, not the event store,
     * so a cascade that reached for the store directly would need a second nullable
     * that must be set in step with this one — the state the comment above rules out.
     *
     * A deployment with no history configured has no events to sweep, so the
     * null case is not a missed cascade; it is an empty one.
     */
    suspend fun deleteForIssue(issueId: Long) = events.deleteForIssue(issueId)

    /**
     * One event by id, or null — the reattribution path's read half.
     *
     * A pass-through to [IssueEventStore], here for [forIssue]'s reason: the MCP
     * surface holds one [IssueHistory] and not the store behind it, so the two
     * reads the reattribution needs live where its write does.
     */
    suspend fun findEvent(id: Long): IssueEventRecord? = events.findById(id)

    /**
     * Correct an event's author, date and agent label — the append-only table's
     * one admin-only exception, for reattaching imported history to a real account.
     *
     * A pass-through, and deliberately NOT routed through [record]. Unlike every
     * other method here this is not a best-effort courtesy on top of a write that
     * already happened — it *is* the write the caller asked for, so a failure must
     * reach them rather than be swallowed into a log. See IssueEvents.sq's preamble
     * and [McpTools.updateHistoryEvent].
     */
    suspend fun reattribute(id: Long, author: Author, createdAt: Long, agentName: String?) =
        events.reattribute(id, author, createdAt, agentName)

    /**
     * The event a freshly published issue starts its history with.
     *
     * Only ever `CREATED`, and deliberately *not* accompanied by a
     * `TITLE_CHANGED` and a `LABELS_CHANGED` for the fields the editor filled in
     * on the way. A draft is not a thing that existed and was then edited — it
     * was being written — so reporting its first save as four changes would
     * describe an authoring session rather than a history. The issue arrived, as
     * it arrived; everything after that is a change.
     */
    suspend fun recordCreated(issue: IssueRecord, author: Author, agentName: String?, createdAt: Long?) {
        record {
            events.append(
                issue.id,
                listOf(NewIssueEvent(IssueEventKind.CREATED)),
                author,
                agentName,
                createdAt,
            )
        }
    }

    /**
     * Compare a save against the issue it is replacing, and record the difference.
     *
     * The comparison is the whole job. The editor sends title, description,
     * status, labels, components and assignee on every save regardless of what
     * the user touched, so without this an ordinary typo fix would write six
     * events claiming everything changed.
     *
     * @param before the issue as it stood, read before the write.
     * @param beforeLabelIds its labels before the write. Passed in rather than
     *   read here, because by the time this is called they have already been
     *   replaced — [IssueStore.setLabelsAndComponents] is wholesale, so the old
     *   set exists nowhere but in whatever the caller kept.
     */
    suspend fun recordSaved(
        before: IssueRecord,
        beforeLabelIds: List<Long>,
        beforeComponentIds: List<Long>,
        title: String,
        description: String,
        statusId: Long,
        assigneeId: Long?,
        labelIds: List<Long>,
        componentIds: List<Long>,
        author: Author,
        agentName: String?,
        createdAt: Long?,
    ) {
        record {
            val changes = buildList {
                if (before.title != title) {
                    add(NewIssueEvent(IssueEventKind.TITLE_CHANGED, value = title))
                }
                if (before.description != description) {
                    // No value: see IssueEventKind.DESCRIPTION_CHANGED.
                    add(NewIssueEvent(IssueEventKind.DESCRIPTION_CHANGED))
                }
                if (before.statusId != statusId) {
                    add(statusEvent(before.projectId, statusId))
                }
                // Sets, so compared as sets: the editor may hand back the same
                // labels in a different order, and a reordering of an unordered
                // thing is not a change anybody made.
                if (beforeLabelIds.toSet() != labelIds.toSet()) {
                    add(labelEvent(before.projectId, labelIds))
                }
                if (beforeComponentIds.toSet() != componentIds.toSet()) {
                    add(componentEvent(before.projectId, componentIds))
                }
                if (before.assigneeId != assigneeId) {
                    add(assigneeEvent(assigneeId))
                }
            }
            events.append(before.id, changes, author, agentName, createdAt)
        }
    }

    /**
     * A drag between columns, or `move_issue`.
     *
     * Its own entry point rather than a trip through [recordSaved], because the
     * write it accompanies is its own too — [IssueStore.setStatus] touches one
     * column, and handing this path a full before/after issue so it could
     * discover that only the status differs would be inventing five comparisons
     * to throw all five away.
     *
     * The resolution is not recorded. It moves with the status in the same
     * statement, so a `STATUS_CHANGED` on the closing column is silent about
     * *why* the issue was closed. See [IssueEventKind] — that is scope.
     */
    suspend fun recordStatusChanged(issue: IssueRecord, statusId: Long, author: Author, agentName: String?) {
        // Guarded, because both callers reach here from a drag, and a drag that
        // lands a card back in the column it started in is a gesture rather than
        // an event. The route above still writes — it bumps updated_at, which is
        // its business — but the history should not gain a line saying the issue
        // moved to where it already was.
        if (issue.statusId == statusId) return
        record {
            events.append(issue.id, listOf(statusEvent(issue.projectId, statusId)), author, agentName)
        }
    }

    /** "Assign to me", and its undo. */
    suspend fun recordAssigneeChanged(issue: IssueRecord, assigneeId: Long?, author: Author, agentName: String?) {
        if (issue.assigneeId == assigneeId) return
        record {
            events.append(issue.id, listOf(assigneeEvent(assigneeId)), author, agentName)
        }
    }

    /**
     * The status's name **as it stands now**, frozen into the event.
     *
     * Resolved here, at write time, rather than stored as an id and looked up on
     * read — which is the decision IssueEvents.sq's `value_text` argues at
     * length. The short version: the column may be renamed or deleted later, and
     * neither of those should reach backwards and alter what this event says
     * happened.
     *
     * A status that cannot be found leaves the value null rather than failing the
     * write. It should be unreachable — the route validated the id against the
     * project before writing — but an event is a record of something that has
     * *already happened*, so refusing to record it because its label is missing
     * would trade a slightly poorer history for no history at all.
     */
    private suspend fun statusEvent(projectId: Long, statusId: Long) = NewIssueEvent(
        kind = IssueEventKind.STATUS_CHANGED,
        value = statuses.forProject(projectId).firstOrNull { it.id == statusId }?.name,
    )

    /** The labels as they now stand, by name. See [statusEvent] for why names. */
    private suspend fun labelEvent(projectId: Long, labelIds: List<Long>) = NewIssueEvent(
        kind = IssueEventKind.LABELS_CHANGED,
        // Ordered by the project's own list rather than by the order the ids
        // arrived in, so two saves of the same set read identically. The client
        // renders these in sequence, and a set that shuffles between two events
        // looks like a change that did not happen.
        values = labels.forProject(projectId).filter { it.id in labelIds }.map { it.name },
    )

    /** As [labelEvent], for components. */
    private suspend fun componentEvent(projectId: Long, componentIds: List<Long>) = NewIssueEvent(
        kind = IssueEventKind.COMPONENTS_CHANGED,
        values = components.forProject(projectId).filter { it.id in componentIds }.map { it.name },
    )

    /**
     * Who the issue was handed to, as both a live reference and a snapshot.
     *
     * Both, and this is the one place the two-column scheme is written down in
     * Kotlin: the id is the durable link to the person, and the name is what
     * survives that account being deleted. A null name is what makes "unassigned"
     * distinguishable from "assigned to somebody who has since left" once
     * `ON DELETE SET NULL` has emptied the id. See IssueEvents.sq.
     */
    private suspend fun assigneeEvent(assigneeId: Long?) = NewIssueEvent(
        kind = IssueEventKind.ASSIGNEE_CHANGED,
        value = assigneeId?.let { users.findById(it)?.resolvedName },
        valueUserId = assigneeId,
    )

    /**
     * Run a recording, swallowing anything it throws.
     *
     * The same bargain [IssueRepository.notify] makes, and it deserves more
     * justification here because this is closer to an audit log, where the usual
     * answer is the opposite one — fail the write rather than lose the record.
     *
     * It is not that. This history is a feature of the issue screen: it tells a
     * team what happened to their ticket. Nothing is authorised against it,
     * nothing is billed from it, and no rule anywhere reads it back. Set against
     * that, the cost of failing loudly is real and lands on the user — a save
     * that already succeeded would report an error, and the person would press
     * Save again and write their change twice trying to make the message go away.
     *
     * So a lost event is a gap in a sidebar, and it is logged. If this ever grows
     * a second reader that decides something, this is the line to revisit — and
     * the write would need to move inside the issue's own transaction, which
     * today it deliberately is not.
     */
    private suspend fun record(block: suspend () -> Unit) {
        runCatching { block() }.onFailure {
            logger.warn("Failed to record issue history; the write it describes is unaffected", it)
        }
    }

    private companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger("IssueHistory")
    }
}
