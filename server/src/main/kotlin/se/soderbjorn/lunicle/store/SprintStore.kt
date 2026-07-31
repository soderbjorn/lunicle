/**
 * The persistence seam for the things you do to a sprint that you cannot do to a
 * label: activate it, complete it, and say which issues are in it.
 *
 * One of the LNL-111 domain store interfaces. The reference implementation is
 * [se.soderbjorn.lunicle.SprintRepository]; the interface is named by its
 * fully-qualified name where it appears in that class's supertype clause, because
 * the low-level gateway it delegates to is also called `SprintStore`.
 *
 * Adding/renaming/reordering/deleting a sprint is [VocabularyStore]'s job — a
 * sprint is a per-project named ordered thing — so this interface is only the
 * sprint-specific lifecycle. The parity-critical rule is the one that prevents
 * stranding: a completed sprint accepts no activation and no membership change.
 * The refusal is signalled the same way in every backend, via
 * [se.soderbjorn.lunicle.SprintRefusal].
 *
 * @see se.soderbjorn.lunicle.store.SprintStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.IssueRecord
import se.soderbjorn.lunicle.SprintRecord

interface SprintStore {
    /**
     * Point the project's board at [sprintId], or null to leave it with none.
     *
     * @throws se.soderbjorn.lunicle.SprintRefusal if the sprint is not this project's, or is completed.
     */
    suspend fun activate(projectId: Long, sprintId: Long?)

    /**
     * Finish [sprintId], rolling its unfinished work to [moveUnfinishedTo] (or the
     * backlog when null) and clearing the active sprint if it was this one.
     *
     * @throws se.soderbjorn.lunicle.SprintRefusal if either sprint is not this project's, is already
     *   completed, or the work would move into itself or a finished sprint.
     */
    suspend fun complete(projectId: Long, sprintId: Long, moveUnfinishedTo: Long?)

    /**
     * Clear [sprintId]'s completion stamp, and nothing else (LNL-196).
     *
     * Not the inverse of [complete]: the work that was rolled forward stays where it
     * went, and the project's active sprint is untouched. Both would be guesses — see
     * [se.soderbjorn.lunicle.SprintRepository.reopen].
     *
     * Reopening a sprint that is already open writes the same NULL again rather than
     * being refused — [activate]'s reasoning exactly: it is what a second click on a
     * slow connection sends, and an error for a request whose end state already holds
     * is a worse answer than doing nothing.
     *
     * @throws se.soderbjorn.lunicle.SprintRefusal if the sprint is not this project's.
     */
    suspend fun reopen(projectId: Long, sprintId: Long)

    /**
     * Set exactly which issues are in [sprintId] — the complete set, not a delta.
     *
     * @throws se.soderbjorn.lunicle.SprintRefusal if the sprint is completed or not this project's,
     *   or any issue is not this project's.
     */
    suspend fun setMembership(projectId: Long, sprintId: Long, issueIds: List<Long>)

    /**
     * Schedule one issue into [sprintId], or send it to the backlog when null.
     *
     * @throws se.soderbjorn.lunicle.SprintRefusal if the issue is a draft, or the sprint is not the
     *   issue's project's (or is completed).
     */
    suspend fun setIssueSprint(issue: IssueRecord, sprintId: Long?)

    /** This project's sprints, in planning order. */
    suspend fun forProject(projectId: Long): List<SprintRecord>

    /** Which sprint this project's board scopes to by default, or null. */
    suspend fun activeSprintId(projectId: Long): Long?

    /** Which of this project's columns mean "finished". */
    suspend fun closingStatusIds(projectId: Long): Set<Long>
}
