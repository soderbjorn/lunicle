/**
 * The issue history tables.
 *
 * Stores only: SQL in, data class out. *What* counts as an event, and the
 * before/after comparison that decides whether one happened at all, lives in
 * [IssueHistory].
 *
 * @see IssueHistory
 * @see IssueEvents.sq
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.clientserver.IssueEventKind
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * One thing that happened to an issue.
 *
 * @property kind what happened. The wire enum from `clientServer` rather than a
 *   server-side twin, deliberately: it is stored by name and sent by name, so a
 *   second enum here would exist only to be mapped onto the first, and the
 *   mapping is where the two would eventually disagree. See IssueEvents.sq.
 * @property value the single value this [kind] carries, or null. A *snapshot* for
 *   vocabulary — the status's name as it stood — and never re-resolved. See
 *   IssueEvents.sq's `value_text`.
 * @property valueUserId the account an `ASSIGNEE_CHANGED` points at, or null
 *   because the issue was unassigned *or* because that account has since been
 *   deleted. [value] is what tells those two apart.
 * @property author who did it. Same three-column model as [IssueRecord.author],
 *   so a deleted account degrades identically on an event and on the issue it
 *   belongs to.
 */
data class IssueEventRecord(
    val id: Long,
    val issueId: Long,
    val kind: IssueEventKind,
    val value: String?,
    val values: List<String>,
    val valueUserId: Long?,
    val createdAt: Long,
    val author: Author,
    val agentName: String?,
)

/**
 * What to write when something happens to an issue.
 *
 * Separate from [IssueEventRecord] because a write and a read are not the same
 * shape: an event has no id until it has been written, and the reader needs one.
 * Making that a nullable id on one type would put a `!!` in every render.
 */
data class NewIssueEvent(
    val kind: IssueEventKind,
    val value: String? = null,
    val values: List<String> = emptyList(),
    val valueUserId: Long? = null,
)

/**
 * Reads and writes `issue_events` and `issue_event_values`.
 *
 * The SQLite reference implementation of [se.soderbjorn.lunicle.store.IssueEventStore],
 * named there by its fully-qualified name because interface and gateway share a
 * simple name.
 */
class IssueEventStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.IssueEventStore {
    /**
     * Append events to an issue's history.
     *
     * Takes a list rather than one event, because one gesture routinely produces
     * several: saving the editor can change the title, the labels and the
     * assignee at once, and those are three facts about one moment. Writing them
     * in one transaction is what keeps that moment atomic — a crash must not
     * leave a history claiming the title changed but not the labels, which is a
     * record of something that never happened.
     *
     * @param createdAt when these happened, or null — every ordinary caller — for
     *   now. The one caller that passes it is the MCP backfill path, which is
     *   already writing an issue dated in the past; an event dated today on an
     *   issue dated in 2019 would sort ahead of its own creation.
     *
     *   ONE value for the whole batch rather than one `now()` per event, on
     *   [IssueStore.insertDraft]'s reasoning: two calls can straddle a
     *   millisecond, and the events of a single save must not appear to have
     *   happened at two different times. Ordering within the batch is `id`'s job
     *   anyway — see IssueEvents.sq's `forIssue`.
     */
    override suspend fun append(
        issueId: Long,
        events: List<NewIssueEvent>,
        author: Author,
        agentName: String?,
        createdAt: Long?,
    ): Unit = withContext(DatabaseDispatcher) {
        if (events.isEmpty()) return@withContext
        val timestamp = createdAt ?: now()
        database.transaction {
            events.forEach { event ->
                val eventId = database.issueEventsQueries.insert(
                    issueId,
                    event.kind.name,
                    event.value,
                    event.valueUserId,
                    timestamp,
                    author.accountId,
                    author.externalName,
                    agentName,
                ).executeAsOne()
                event.values.forEachIndexed { index, value ->
                    database.issueEventsQueries.insertValue(eventId, index.toLong(), value)
                }
            }
        }
    }

    /**
     * One issue's history, oldest first.
     *
     * Two queries and a join in Kotlin rather than one query returning a row per
     * value: a LEFT JOIN would repeat every event's author and timestamp once per
     * label it carries, and then this function would be de-duplicating them back
     * out. Two queries is also two round-trips *total* rather than per event —
     * the N+1 this is avoiding is the one where each event fetches its own values.
     *
     * ── An unrecognised kind is dropped, not thrown ────────────────────────
     *
     * A row whose `kind` no constant matches was written by a newer build than
     * this one — a rollback, or a mixed deployment mid-rolling-restart. Throwing
     * would make the issue itself unopenable, which is a total failure in
     * exchange for a partial one: a history one line short is worth vastly less
     * than an issue nobody can read. The client does the same thing for the same
     * reason on the way in; see [IssueEventKind].
     */
    override suspend fun forIssue(issueId: Long): List<IssueEventRecord> = withContext(DatabaseDispatcher) {
        val values = database.issueEventsQueries.valuesForIssue(issueId).executeAsList()
            .groupBy({ it.event_id }, { it.value_ })
        database.issueEventsQueries.forIssue(issueId).executeAsList().mapNotNull { row ->
            val kind = IssueEventKind.entries.firstOrNull { it.name == row.kind } ?: return@mapNotNull null
            IssueEventRecord(
                id = row.id,
                issueId = row.issue_id,
                kind = kind,
                value = row.value_text,
                values = values[row.id].orEmpty(),
                valueUserId = row.value_user_id,
                createdAt = row.created_at,
                author = authorOf(row.created_by, row.created_by_external),
                agentName = row.agent_name,
            )
        }
    }

    /**
     * One event by id, or null.
     *
     * The reattribution path's read half: it needs the event's issue to gate the
     * caller against that project, and the event's current author, date and agent
     * label to default whatever the call leaves unspoken. The child values are not
     * read — reattribution cannot touch them — so this does not do [forIssue]'s
     * second query, and the record it returns carries an empty [IssueEventRecord.values].
     *
     * An unrecognised kind comes back null, for [forIssue]'s reason: a row a newer
     * build wrote is not one this build can meaningfully hand to a reattribution.
     */
    override suspend fun findById(id: Long): IssueEventRecord? = withContext(DatabaseDispatcher) {
        database.issueEventsQueries.findById(id).executeAsOneOrNull()?.let { row ->
            val kind = IssueEventKind.entries.firstOrNull { it.name == row.kind } ?: return@let null
            IssueEventRecord(
                id = row.id,
                issueId = row.issue_id,
                kind = kind,
                value = row.value_text,
                values = emptyList(),
                valueUserId = row.value_user_id,
                createdAt = row.created_at,
                author = authorOf(row.created_by, row.created_by_external),
                agentName = row.agent_name,
            )
        }
    }

    /**
     * Rewrite who and when an event records — never what it records.
     *
     * The append-only rule's one admin-only exception, for correcting imported
     * history; see IssueEvents.sq's preamble and `reattribute`'s own SQL comment.
     * `kind` and the values are not parameters here because they are not this
     * method's to change — the change stays exactly what it was, and only its
     * attribution moves.
     */
    override suspend fun reattribute(
        id: Long,
        author: Author,
        createdAt: Long,
        agentName: String?,
    ): Unit = withContext(DatabaseDispatcher) {
        database.issueEventsQueries.reattribute(createdAt, author.accountId, author.externalName, agentName, id)
    }

    /**
     * The cascade the schema would have run anyway, a moment early. See the
     * interface's comment on why it is called at all, and the query's on why it is
     * harmless here.
     *
     * Both statements in one transaction: the values are reached *through* the
     * events, so a crash between them would leave values whose join can no longer
     * find them — the very orphan this method exists to prevent.
     */
    override suspend fun deleteForIssue(issueId: Long): Unit = withContext(DatabaseDispatcher) {
        database.issueEventsQueries.transaction {
            database.issueEventsQueries.deleteValuesForIssue(issueId)
            database.issueEventsQueries.deleteForIssue(issueId)
        }
    }
}
