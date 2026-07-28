/**
 * The behaviour every [IssueEventStore] implementation must exhibit — an issue's
 * append-only history.
 *
 * The semantics pinned here: [IssueEventStore.append] writes a batch as one moment
 * and [IssueEventStore.forIssue] reads a whole history back oldest-first, in the
 * order the events happened even when a batch shares a millisecond; the set-valued
 * `values` of an event round-trip through [IssueEventStore.forIssue] in order; an
 * empty append is a no-op; the history is isolated per issue;
 * [IssueEventStore.findById] returns one event by id (without its child values, the
 * reattribution path's read); and [IssueEventStore.reattribute] rewrites only who
 * and when an event records, never its `kind` or its values.
 *
 * A backend seeding hook is needed because an event hangs off an issue: [newIssue]
 * mints one however the backend under test makes them. The store validates no
 * foreign key — an event stores its `issueId` as a plain field — so a synthetic id
 * is all a Firestore backend needs; the SQLite backend files a real issue so the
 * `issue_id` foreign key it does have is satisfied.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.NewIssueEvent
import se.soderbjorn.lunicle.clientserver.IssueEventKind

abstract class IssueEventStoreContract {
    protected abstract val store: IssueEventStore

    /** An issue for events to hang off, made the backend's own way. */
    protected abstract suspend fun newIssue(): Long

    @Test
    fun `append then forIssue reads a batch back oldest first`() = runBlocking {
        val issue = newIssue()
        store.append(
            issue,
            listOf(
                NewIssueEvent(IssueEventKind.CREATED),
                NewIssueEvent(IssueEventKind.TITLE_CHANGED, value = "First title"),
                NewIssueEvent(IssueEventKind.LABELS_CHANGED, values = listOf("Bug", "Feature")),
            ),
            author = Author.Nobody,
            createdAt = 1_000,
        )
        val history = store.forIssue(issue)
        assertEquals(
            listOf(IssueEventKind.CREATED, IssueEventKind.TITLE_CHANGED, IssueEventKind.LABELS_CHANGED),
            history.map { it.kind },
            "the batch reads back in the order it was written, even sharing a millisecond",
        )
        assertEquals("First title", history[1].value, "a single-value kind carries its snapshot")
        assertEquals(listOf("Bug", "Feature"), history[2].values, "a set-valued kind round-trips in order")
    }

    @Test
    fun `successive appends accumulate oldest first`() = runBlocking {
        val issue = newIssue()
        store.append(issue, listOf(NewIssueEvent(IssueEventKind.CREATED)), Author.Nobody, createdAt = 1_000)
        store.append(issue, listOf(NewIssueEvent(IssueEventKind.STATUS_CHANGED, value = "In progress")), Author.Nobody, createdAt = 2_000)
        store.append(issue, listOf(NewIssueEvent(IssueEventKind.STATUS_CHANGED, value = "Done")), Author.Nobody, createdAt = 3_000)

        val values = store.forIssue(issue).map { it.value }
        assertEquals(listOf(null, "In progress", "Done"), values, "later appends land after earlier ones")
    }

    @Test
    fun `an empty append writes nothing`() = runBlocking {
        val issue = newIssue()
        store.append(issue, emptyList(), Author.Nobody, createdAt = 1_000)
        assertEquals(emptyList(), store.forIssue(issue), "an empty batch is a no-op")
    }

    @Test
    fun `forIssue is isolated per issue`() = runBlocking {
        val issue = newIssue()
        val other = newIssue()
        store.append(issue, listOf(NewIssueEvent(IssueEventKind.CREATED)), Author.Nobody, createdAt = 1_000)
        store.append(other, listOf(NewIssueEvent(IssueEventKind.CREATED)), Author.Nobody, createdAt = 1_000)
        assertEquals(1, store.forIssue(issue).size, "one issue's history never reaches another's")
    }

    @Test
    fun `append carries author and agent onto every event`() = runBlocking {
        val issue = newIssue()
        store.append(
            issue,
            listOf(NewIssueEvent(IssueEventKind.CREATED), NewIssueEvent(IssueEventKind.TITLE_CHANGED, value = "T")),
            author = Author.External("Imported"),
            agentName = "agent",
            createdAt = 1_000,
        )
        val history = store.forIssue(issue)
        assertTrue(history.all { it.author == Author.External("Imported") }, "the author rides every event in the batch")
        assertTrue(history.all { it.agentName == "agent" }, "so does the agent label")
        assertTrue(history.all { it.createdAt == 1_000L }, "and one timestamp for the whole batch")
    }

    @Test
    fun `findById returns one event without its child values`() = runBlocking {
        val issue = newIssue()
        store.append(
            issue,
            listOf(NewIssueEvent(IssueEventKind.LABELS_CHANGED, values = listOf("Bug", "Feature"))),
            author = Author.Nobody,
            createdAt = 1_000,
        )
        val id = store.forIssue(issue).single().id
        val found = store.findById(id)!!
        assertEquals(IssueEventKind.LABELS_CHANGED, found.kind)
        assertEquals(issue, found.issueId)
        assertEquals(emptyList(), found.values, "findById is the reattribution read and does not fetch child values")
        assertNull(store.findById(-1), "a missing id is null")
    }

    @Test
    fun `reattribute rewrites who and when, never what`() = runBlocking {
        val issue = newIssue()
        store.append(
            issue,
            listOf(NewIssueEvent(IssueEventKind.STATUS_CHANGED, value = "In progress", values = listOf("ignored"))),
            author = Author.Nobody,
            createdAt = 1_000,
        )
        val id = store.forIssue(issue).single().id

        store.reattribute(id, author = Author.External("Imported"), createdAt = 5_000, agentName = "agent")
        val reattributed = store.forIssue(issue).single()
        assertEquals(Author.External("Imported"), reattributed.author, "the author moves")
        assertEquals(5_000, reattributed.createdAt, "the date moves")
        assertEquals("agent", reattributed.agentName, "the agent label moves")
        assertEquals(IssueEventKind.STATUS_CHANGED, reattributed.kind, "but the kind is untouched")
        assertEquals("In progress", reattributed.value, "and the snapshot value is untouched")
        assertEquals(listOf("ignored"), reattributed.values, "and the child values are untouched")
    }

    /**
     * The issue-delete cascade, at this store's scale (LNL-177) — the append-only
     * rule's one non-admin exception, because the issue these events describe is
     * going and there is nothing left for them to be the history of.
     *
     * The events carry child values deliberately: on SQLite those live in a second
     * table reached only *through* the events, so a cascade that dropped the parents
     * first would strand them. Reading the spared issue's values back afterwards is
     * what proves the two-statement order held.
     *
     * The second issue is the load-bearing half, as everywhere else in this suite: a
     * delete keyed on nothing empties the collection and passes the rest.
     */
    @Test
    fun `deleteForIssue takes that issue's history with its values and spares another issue's`() = runBlocking {
        val issue = newIssue()
        val other = newIssue()
        store.append(
            issue,
            listOf(
                NewIssueEvent(IssueEventKind.STATUS_CHANGED, value = "In progress"),
                NewIssueEvent(IssueEventKind.LABELS_CHANGED, values = listOf("Bug", "Feature")),
            ),
            author = Author.Nobody,
            createdAt = 1_000,
        )
        store.append(
            other,
            listOf(NewIssueEvent(IssueEventKind.LABELS_CHANGED, values = listOf("Spared"))),
            author = Author.Nobody,
            createdAt = 2_000,
        )
        val doomedId = store.forIssue(issue).first().id

        store.deleteForIssue(issue)

        assertEquals(emptyList(), store.forIssue(issue).map { it.id }, "the history is gone")
        assertNull(store.findById(doomedId), "and the events with it")

        val survivor = store.forIssue(other).single()
        assertEquals(IssueEventKind.LABELS_CHANGED, survivor.kind, "deleting one issue's history took another's")
        assertEquals(listOf("Spared"), survivor.values, "the surviving event kept its child values")
    }

    @Test
    fun `deleteForIssue on an issue with no history is a no-op`() = runBlocking {
        val issue = newIssue()
        val other = newIssue()
        store.append(
            other,
            listOf(NewIssueEvent(IssueEventKind.STATUS_CHANGED, value = "Kept")),
            author = Author.Nobody,
            createdAt = 1_000,
        )

        store.deleteForIssue(issue)

        assertEquals(1, store.forIssue(other).size, "an empty cascade deleted something")
    }
}
