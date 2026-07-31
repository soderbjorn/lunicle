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
    /**
     * The sprints and versions whose NAMES the three new field events snapshot
     * (LNL-215).
     *
     * Nullable so the tests and deployments that assembled this class with five stores
     * still can: a null store means the event is written with a null value rather than
     * not written at all, which degrades the same way a status whose row cannot be
     * found already does — an event is a record of something that has *already
     * happened*, and refusing to record it because its label is missing trades a
     * slightly poorer history for no history.
     */
    private val sprints: se.soderbjorn.lunicle.store.SprintStore? = null,
    private val versions: se.soderbjorn.lunicle.store.VersionStore? = null,
    /**
     * The issues and projects behind [keyOf] — how a hierarchy or relation event
     * turns an issue id into the `LNL-98` a human reads (LNL-215).
     *
     * Both nullable, like the two above and for the same reason: an assembly without
     * them writes the event with a null key rather than not writing it. A history line
     * that says an issue was moved under *something* is still a record that it moved.
     */
    private val issues: se.soderbjorn.lunicle.store.IssueStore? = null,
    private val projects: se.soderbjorn.lunicle.store.ProjectStore? = null,
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
        sprintId: Long?,
        plannedVersionId: Long?,
        fixedVersionId: Long?,
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
                // The three fields that used to leave no trace at all (LNL-215).
                // Scheduling an issue, planning it for a release and recording which
                // release it shipped in are exactly the changes somebody asks "when did
                // this happen" about, and until now the answer was nowhere.
                if (before.sprintId != sprintId) {
                    add(sprintEvent(before.projectId, sprintId))
                }
                if (before.plannedVersionId != plannedVersionId) {
                    add(versionEvent(IssueEventKind.PLANNED_VERSION_CHANGED, before.projectId, plannedVersionId))
                }
                if (before.fixedVersionId != fixedVersionId) {
                    add(versionEvent(IssueEventKind.FIXED_VERSION_CHANGED, before.projectId, fixedVersionId))
                }
            }
            events.append(before.id, changes, author, agentName, createdAt)
        }
    }

    /**
     * A sprint change made outside the editor — `/api/issues/{id}/sprint`, the card
     * menu, the planning dialog, MCP's `sprint` field (LNL-215).
     *
     * Its own entry point beside [recordStatusChanged] for that method's reason
     * exactly: the write it accompanies touches one column, and handing this path a
     * full before/after issue so it could discover that only the sprint differs would
     * be inventing eight comparisons to throw eight away.
     */
    suspend fun recordSprintChanged(issue: IssueRecord, sprintId: Long?, author: Author, agentName: String?) {
        // Guarded like [recordStatusChanged]: putting an issue in the sprint it is
        // already in is a gesture, not an event.
        if (issue.sprintId == sprintId) return
        record {
            events.append(issue.id, listOf(sprintEvent(issue.projectId, sprintId)), author, agentName)
        }
    }

    /**
     * The fixed version written alongside a drag-to-close, outside the editor
     * (LNL-215). [recordSprintChanged]'s twin, guarded the same way.
     */
    suspend fun recordFixedVersionChanged(
        issue: IssueRecord,
        fixedVersionId: Long?,
        author: Author,
        agentName: String?,
    ) {
        if (issue.fixedVersionId == fixedVersionId) return
        record {
            val event = versionEvent(IssueEventKind.FIXED_VERSION_CHANGED, issue.projectId, fixedVersionId)
            events.append(issue.id, listOf(event), author, agentName)
        }
    }

    /**
     * A reparent, recorded on **both** issues — and on the old epic too (LNL-215).
     *
     * Up to three events for one write, and the asymmetry between them is correct
     * rather than sloppy: an issue has at most one parent, so its own history records a
     * *change*, while an epic has many children and its history records arrivals and
     * departures. Somebody reading an epic needs to see that LNL-9 turned up under it;
     * somebody reading LNL-9 needs to see where it went.
     *
     * Written as three separate `append` calls rather than one, because they are three
     * different issues — [IssueEventStore.append] is per-issue, and its single
     * timestamp per batch is about one save on one issue rather than about one gesture.
     *
     * @param before the parent the issue had, read before the write. Passed in for
     *   [recordSaved.beforeLabelIds]'s reason: by the time this is called the column
     *   has been overwritten and the old value exists nowhere else.
     */
    suspend fun recordParentChanged(
        issue: IssueRecord,
        before: Long?,
        parentId: Long?,
        author: Author,
        agentName: String?,
    ) {
        if (before == parentId) return
        record {
            val newKey = parentId?.let { keyOf(it) }
            events.append(
                issue.id,
                listOf(NewIssueEvent(IssueEventKind.PARENT_CHANGED, value = newKey)),
                author,
                agentName,
            )
            val childKey = keyOf(issue.id)
            before?.let { old ->
                events.append(
                    old,
                    listOf(NewIssueEvent(IssueEventKind.CHILD_REMOVED, value = childKey)),
                    author,
                    agentName,
                )
            }
            parentId?.let { now ->
                events.append(
                    now,
                    listOf(NewIssueEvent(IssueEventKind.CHILD_ADDED, value = childKey)),
                    author,
                    agentName,
                )
            }
        }
    }

    /**
     * A link added or removed, recorded on **both** issues (LNL-215).
     *
     * Two events for one relation row, and this is deliberately not a contradiction of
     * IssueRelations.sq's one-row rule. That rule is about *state*, where a second row
     * would be a second source of truth that can drift; this is per-issue and
     * append-only, and both issues genuinely had something happen to them. Somebody
     * reading B's history needs to see that it now blocks A.
     *
     * Each event carries the label for ITS OWN side — `Blocked by` on the from issue,
     * `Blocks` on the to issue, the same word twice when the kind is symmetric — so
     * each history reads as a sentence about the issue it belongs to.
     *
     * @param added whether this is the arrival or the departure.
     */
    suspend fun recordRelationChanged(
        relation: IssueRelationRecord,
        kind: IssueRelationKindRecord,
        added: Boolean,
        author: Author,
        agentName: String?,
    ) {
        record {
            val eventKind = if (added) IssueEventKind.RELATION_ADDED else IssueEventKind.RELATION_REMOVED
            events.append(
                relation.fromIssueId,
                listOf(
                    NewIssueEvent(
                        eventKind,
                        value = keyOf(relation.toIssueId),
                        relationKind = kind.labelFrom,
                    ),
                ),
                author,
                agentName,
            )
            events.append(
                relation.toIssueId,
                listOf(
                    NewIssueEvent(
                        eventKind,
                        value = keyOf(relation.fromIssueId),
                        relationKind = kind.labelTo,
                    ),
                ),
                author,
                agentName,
            )
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
    /**
     * The sprint's name **as it stands now**, frozen into the event — or null, which
     * means THE BACKLOG rather than "we do not know" (LNL-215).
     *
     * Null is load-bearing exactly as it is on [assigneeEvent]: "moved this to the
     * backlog" is the second most common thing anybody does to a sprint, and a history
     * that could not say it would be silent about half the feature. There is no
     * snapshot/live pair here as there is for an assignee, because a sprint is a
     * project's word for a fortnight rather than a person — see [statusEvent].
     *
     * A sprint that cannot be found, or a deployment with no sprint store wired,
     * leaves the value null rather than failing the write. That collapses into the
     * backlog reading, which is the one wrinkle worth naming and is the cheaper of the
     * two failures: the alternative is refusing to record a change that has already
     * happened.
     */
    private suspend fun sprintEvent(projectId: Long, sprintId: Long?) = NewIssueEvent(
        kind = IssueEventKind.SPRINT_CHANGED,
        value = sprintId?.let { id -> sprints?.forProject(projectId)?.firstOrNull { it.id == id }?.name },
    )

    /** The version's name as it stands now, or null because it was cleared. See [statusEvent]. */
    private suspend fun versionEvent(kind: IssueEventKind, projectId: Long, versionId: Long?) = NewIssueEvent(
        kind = kind,
        value = versionId?.let { id -> versions?.forProject(projectId)?.firstOrNull { it.id == id }?.name },
    )

    /**
     * `LNL-98` for an issue id, or null when it cannot be resolved.
     *
     * A **snapshot**, like every other value on this table: the key is written into the
     * event and never re-resolved, so re-prefixing a project does not rewrite what its
     * histories say happened. That is the same argument [statusEvent] makes about a
     * renamed column, applied to the one identifier a project can change.
     *
     * Two reads per call, and deliberately not cached: hierarchy and relation events
     * are written a handful at a time by one gesture, never per card and never in a
     * loop over a board.
     */
    private suspend fun keyOf(issueId: Long): String? {
        val issue = issues?.findById(issueId) ?: return null
        val prefix = projects?.findById(issue.projectId)?.namePrefix ?: return null
        return "$prefix-${issue.number}"
    }

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
