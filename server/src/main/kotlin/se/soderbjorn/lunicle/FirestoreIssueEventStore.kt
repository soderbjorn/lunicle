/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.IssueEventStore] — an
 * issue's append-only history, part of the LNL-127 issue-content fan-out.
 *
 * ── Document model ──────────────────────────────────────────────────────────
 *
 * One flat top-level collection, `issueEvents/{id}`, with a denormalised `issueId` —
 * the flat-collection-with-parent-ref shape [FirestoreIssueStore] set for a
 * project's issues. That is what turns [forIssue] into a single equality query;
 * ordering is applied in memory, so the query needs no composite index. Ids are the
 * global `Long`s the system addresses events by, drawn from `_counters/issueEvents`
 * (the [FirestoreCounters] convention — see [append] on why a block, not a single
 * `next`, is reserved). The set-valued child table `issue_event_values` collapses
 * onto the document as a `values` string array in position order, exactly as
 * [FirestoreIssueStore] collapses an issue's label join onto a `labelIds` array — so
 * an event is one document and the whole of its history is one read.
 *
 * The event stores the *fact* — `kind`, and whatever that kind carries: a snapshot
 * `value`, an array of `values`, an `assigneeId`-shaped `valueUserId` — never a
 * foreign key re-resolved on read, here as in SQLite; see [IssueEventRecord].
 *
 * ── Ordering, and the unrecognised kind ─────────────────────────────────────
 *
 * [forIssue] sorts by **id**, not `createdAt`: same-millisecond events (publishing a
 * draft writes several at once) must keep the order they happened in rather than
 * reshuffle under a reload, and the id is monotonic by construction. A row whose
 * `kind` no constant matches was written by a newer build and is dropped in memory,
 * not thrown — one unknown line must never make the issue unreadable, the same call
 * the SQLite store makes.
 *
 * No composite index is required.
 *
 * @see FirestoreProvider
 * @see FirestoreCounters
 * @see se.soderbjorn.lunicle.store.IssueEventStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.clientserver.IssueEventKind
import se.soderbjorn.lunicle.store.IssueEventStore

class FirestoreIssueEventStore(
    private val firestore: Firestore,
    private val now: () -> Long = System::currentTimeMillis,
) : IssueEventStore {
    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    /**
     * Append events atomically, reserving a contiguous block of ids in the same
     * transaction that writes them.
     *
     * [FirestoreCounters.next] bumps a counter by one, which is right for the
     * one-document stores; a history append writes several events as facts about one
     * moment, so it reserves several ids at once. This reads the same
     * `_counters/issueEvents` document [FirestoreCounters] would — the counter format
     * is shared, via [FirestoreCounters.COLLECTION]/[FirestoreCounters.VALUE] — and
     * bumps it by the batch size, all before any write so Firestore's
     * reads-before-writes rule holds. The single [timestamp] is bound to every event
     * in the batch, so the events of one save never appear to have happened at two
     * different times; their order is the block's ascending ids.
     */
    override suspend fun append(
        issueId: Long,
        events: List<NewIssueEvent>,
        author: Author,
        agentName: String?,
        createdAt: Long?,
    ) {
        if (events.isEmpty()) return
        val timestamp = createdAt ?: now()
        firestore.runTransaction { txn ->
            val counterRef = firestore.collection(FirestoreCounters.COLLECTION).document(COUNTER)
            val base = txn.get(counterRef).get().getLong(FirestoreCounters.VALUE) ?: 0L
            txn.set(counterRef, mapOf(FirestoreCounters.VALUE to base + events.size))
            events.forEachIndexed { index, event ->
                val id = base + 1 + index
                txn.set(
                    doc(id),
                    mapOf(
                        ID to id,
                        ISSUE_ID to issueId,
                        KIND to event.kind.name,
                        VALUE_TEXT to event.value,
                        VALUES to event.values,
                        VALUE_USER_ID to event.valueUserId,
                        VALUE_RELATION_KIND to event.relationKind,
                        CREATED_AT to timestamp,
                        CREATED_BY to author.accountId,
                        CREATED_BY_EXTERNAL to author.externalName,
                        AGENT_NAME to agentName,
                    ),
                )
            }
            events.size
        }.await()
    }

    /** One issue's history, oldest first (by id), unrecognised kinds dropped. */
    override suspend fun forIssue(issueId: Long): List<IssueEventRecord> =
        collection()
            .whereEqualTo(ISSUE_ID, issueId)
            .get().await()
            .documents.mapNotNull { it.toRecord(includeValues = true) }
            .sortedBy { it.id }

    /**
     * One event by id, or null. Mirrors the SQLite store's `findById`: the child
     * values are not returned (reattribution never touches them), so the record
     * carries an empty [IssueEventRecord.values] even though the array is right there
     * on the document. An unrecognised kind comes back null.
     */
    override suspend fun findById(id: Long): IssueEventRecord? =
        doc(id).get().await().takeIf { it.exists() }?.toRecord(includeValues = false)

    /** Correct who and when — author, date, agent label — never what the event records. */
    override suspend fun reattribute(
        id: Long,
        author: Author,
        createdAt: Long,
        agentName: String?,
    ) {
        doc(id).update(
            mapOf(
                CREATED_AT to createdAt,
                CREATED_BY to author.accountId,
                CREATED_BY_EXTERNAL to author.externalName,
                AGENT_NAME to agentName,
            ),
        ).await()
    }

    /**
     * An issue's whole history — one equality on `issueId`, chunked.
     *
     * No second step for the values, unlike SQLite: they are an array on the event
     * document, so deleting the document takes them. See the interface's comment.
     */
    override suspend fun deleteForIssue(issueId: Long) = deleteWhere(collection(), ISSUE_ID, issueId)

    /**
     * A document to a record, or null when its `kind` matches no constant — a row a
     * newer build wrote, dropped rather than thrown for the class preamble's reason.
     *
     * @param includeValues whether to carry the `values` array through; [findById]
     *   passes false to mirror the SQLite store, which does not fetch them there.
     */
    private fun DocumentSnapshot.toRecord(includeValues: Boolean): IssueEventRecord? {
        val kind = IssueEventKind.entries.firstOrNull { it.name == getString(KIND) } ?: return null
        return IssueEventRecord(
            id = getLong(ID)!!,
            issueId = getLong(ISSUE_ID)!!,
            kind = kind,
            value = getString(VALUE_TEXT),
            values = if (includeValues) stringList(VALUES) else emptyList(),
            valueUserId = getLong(VALUE_USER_ID),
            createdAt = getLong(CREATED_AT)!!,
            author = authorOf(getLong(CREATED_BY), getString(CREATED_BY_EXTERNAL)),
            agentName = getString(AGENT_NAME),
            // Read even when [includeValues] is false, unlike the `values` array: this
            // is a scalar on the event, not a child table, so there is no SQLite read
            // that omits it and nothing here should either. See VALUE_RELATION_KIND.
            relationKind = getString(VALUE_RELATION_KIND),
        )
    }

    /** A string-array field read back, or empty when absent. */
    private fun DocumentSnapshot.stringList(field: String): List<String> {
        @Suppress("UNCHECKED_CAST")
        return (get(field) as? List<String>).orEmpty()
    }

    internal companion object {
        const val COLLECTION = "issueEvents"
        const val COUNTER = "issueEvents"

        const val ID = "id"
        const val ISSUE_ID = "issueId"
        const val KIND = "kind"
        const val VALUE_TEXT = "valueText"
        const val VALUES = "values"
        const val VALUE_USER_ID = "valueUserId"

        /**
         * The relation kind's label, for `RELATION_ADDED` / `RELATION_REMOVED`; null
         * on every other kind (LNL-215). Named for the SQLite column
         * `issue_events.value_relation_kind`, like [VALUE_USER_ID] beside it.
         *
         * A **snapshot for this issue's side of the link** — the kind's `name` on the
         * from-side issue, its `inverseName` on the to-side one — frozen at write time
         * for [VALUE_TEXT]'s reason: relation kinds are user-editable vocabulary, so
         * renaming or deleting one must not reach backwards and alter what an event
         * says happened.
         *
         * Its own field rather than a second entry in [VALUES], which is documented as
         * carrying *the whole set* a kind refers to; using that array as a positional
         * tuple would contradict its own contract on both backends at once.
         */
        const val VALUE_RELATION_KIND = "valueRelationKind"
        const val CREATED_AT = "createdAt"
        const val CREATED_BY = "createdBy"
        const val CREATED_BY_EXTERNAL = "createdByExternal"
        const val AGENT_NAME = "agentName"
    }
}
