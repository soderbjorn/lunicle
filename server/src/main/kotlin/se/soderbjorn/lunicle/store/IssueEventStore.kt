/**
 * The persistence seam for an issue's history: the `issue_events` row and its
 * set-valued child `issue_event_values`, and the three reads over them.
 *
 * Append-only in all but one narrow, admin-only respect. Nothing is ever deleted
 * except by the cascade when the issue itself goes, and *what happened* — `kind`,
 * the snapshot value, the child values — is never rewritten by anyone. The single
 * exception is [reattribute], which corrects *who and when* a row records for
 * imported history, never its content. There is deliberately no general `update`
 * and no `delete` to reach for by accident. The reference implementation is the
 * SQLite gateway [se.soderbjorn.lunicle.IssueEventStore] (named by its
 * fully-qualified name in that class's supertype clause, since the two share a
 * simple name).
 *
 * *What* counts as an event, and the before/after comparison that decides whether
 * one happened at all, lives one layer up in `IssueHistory` and is
 * backend-agnostic; this seam only stores and reads. An event carries no
 * `project_id` — it reaches its project through its issue — and its value is a
 * *snapshot* (the status's name as it stood), never a foreign key re-resolved on
 * read; see [se.soderbjorn.lunicle.IssueEventRecord].
 *
 * @see se.soderbjorn.lunicle.store.IssueEventStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.IssueEventRecord
import se.soderbjorn.lunicle.NewIssueEvent

interface IssueEventStore {
    /**
     * Append events to an issue's history, atomically.
     *
     * Takes a list rather than one event, because one gesture routinely produces
     * several — saving the editor can change the title, the labels and the assignee
     * at once — and those are facts about one moment that a crash must not split.
     *
     * @param createdAt when these happened, or null — every ordinary caller — for
     *   now. One value for the whole batch, so the events of a single save never
     *   appear to have happened at two different times; ordering within the batch is
     *   the id's job. The one caller that passes it is the MCP backfill path.
     */
    suspend fun append(
        issueId: Long,
        events: List<NewIssueEvent>,
        author: Author,
        agentName: String? = null,
        createdAt: Long? = null,
    )

    /**
     * One issue's history, oldest first, ordered by id — not `createdAt`, because
     * same-millisecond events (publishing a draft writes several at once) must keep
     * the order they happened in rather than reshuffle under a reload. A row whose
     * `kind` no constant matches was written by a newer build and is dropped, not
     * thrown, so one unknown line never makes the issue unreadable.
     */
    suspend fun forIssue(issueId: Long): List<IssueEventRecord>

    /**
     * One event by id, or null — the reattribution path's read half. The child
     * values are not read (reattribution cannot touch them), so the record carries
     * an empty [IssueEventRecord.values]. An unrecognised kind comes back null, for
     * [forIssue]'s reason.
     */
    suspend fun findById(id: Long): IssueEventRecord?

    /**
     * Rewrite who and when an event records — author, date, agent label — never what
     * it records. The append-only rule's one admin-only exception, for correcting
     * imported history; `kind` and the values are not parameters because they are not
     * this method's to change.
     */
    suspend fun reattribute(
        id: Long,
        author: Author,
        createdAt: Long,
        agentName: String?,
    )
}
