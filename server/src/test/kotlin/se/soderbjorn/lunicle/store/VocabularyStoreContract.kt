/**
 * The behaviour every [VocabularyStore] implementation must exhibit — the richest
 * parity surface in the suite.
 *
 * These are the semantics a document backend is most likely to get subtly
 * different from SQL, so they are pinned explicitly: add appends at the end of the
 * order (never the front), names are unique case-insensitively, a reorder rewrites
 * positions 0..n-1 (repairing the gap a deleted middle row leaves), a reorder that
 * is not exactly the vocabulary is refused, and delete refuses both the last
 * load-bearing row and a row still in use.
 *
 * A subclass per backend supplies the store, a project seeded with the default
 * vocabularies, and a way to file an issue into the leftmost status.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.VocabularyConflict
import se.soderbjorn.lunicle.VocabularyRefusal
import se.soderbjorn.lunicle.clientserver.VocabularyKind

abstract class VocabularyStoreContract {
    protected abstract val store: VocabularyStore

    /** A fresh project seeded with the default vocabularies. */
    protected abstract suspend fun newProject(): Long

    /** File one issue, which lands in the leftmost status, marking it in use. */
    protected abstract suspend fun fileIssue(projectId: Long): Long

    /** Start an issue and leave it unsaved — a draft, which also lands in the leftmost status. */
    protected abstract suspend fun createDraft(projectId: Long): Long

    /** Whether an issue row is still there, for asserting what a delete took with it. */
    protected abstract suspend fun issueExists(id: Long): Boolean

    @Test
    fun `add appends at the end of the order`(): Unit = runBlocking {
        val project = newProject()
        val added = store.add(project, VocabularyKind.LABEL, "Zeta")
        val rows = store.rows(project, VocabularyKind.LABEL)
        assertEquals(added.id, rows.last().id, "the new row sorts last")
        assertEquals(rows.map { it.position }.max(), rows.last().position, "and holds the largest position")
    }

    @Test
    fun `a duplicate name is refused case-insensitively`(): Unit = runBlocking {
        val project = newProject()
        store.add(project, VocabularyKind.LABEL, "Flake")
        assertFailsWith<VocabularyConflict> { store.add(project, VocabularyKind.LABEL, "flake") }
    }

    @Test
    fun `a blank name is refused`(): Unit = runBlocking {
        val project = newProject()
        assertFailsWith<VocabularyConflict> { store.add(project, VocabularyKind.LABEL, "   ") }
    }

    @Test
    fun `reorder rewrites positions 0 to n-1 and repairs gaps`(): Unit = runBlocking {
        val project = newProject()
        // Make a gap: add two, delete the first of them, so positions are no longer
        // contiguous before the reorder.
        store.add(project, VocabularyKind.LABEL, "Alpha")
        val beta = store.add(project, VocabularyKind.LABEL, "Beta")
        store.delete(project, VocabularyKind.LABEL, beta)

        val ids = store.rows(project, VocabularyKind.LABEL).map { it.id }.reversed()
        store.reorder(project, VocabularyKind.LABEL, ids)

        val after = store.rows(project, VocabularyKind.LABEL)
        assertEquals(ids, after.map { it.id }, "rows now follow the requested order")
        assertEquals((0 until after.size).map { it.toLong() }, after.map { it.position }, "positions are contiguous 0..n-1")
    }

    @Test
    fun `a reorder that is not exactly the vocabulary is refused`(): Unit = runBlocking {
        val project = newProject()
        val ids = store.rows(project, VocabularyKind.LABEL).map { it.id }
        assertFailsWith<VocabularyRefusal> { store.reorder(project, VocabularyKind.LABEL, ids.drop(1)) }
    }

    @Test
    fun `an unused, non-load-bearing row deletes`(): Unit = runBlocking {
        val project = newProject()
        val added = store.add(project, VocabularyKind.LABEL, "Temporary")
        store.delete(project, VocabularyKind.LABEL, added)
        assertTrue(store.rows(project, VocabularyKind.LABEL).none { it.id == added.id })
    }

    @Test
    fun `the last load-bearing row cannot be deleted`(): Unit = runBlocking {
        val project = newProject()
        // Statuses are load-bearing: a project must keep at least one. Delete all
        // but one, then the last must refuse.
        val statuses = store.rows(project, VocabularyKind.STATUS)
        statuses.dropLast(1).forEach { store.delete(project, VocabularyKind.STATUS, it) }
        val last = store.rows(project, VocabularyKind.STATUS).single()
        assertFailsWith<VocabularyRefusal> { store.delete(project, VocabularyKind.STATUS, last) }
    }

    @Test
    fun `a status still in use cannot be deleted`(): Unit = runBlocking {
        val project = newProject()
        fileIssue(project) // lands in the leftmost status
        val leftmost = store.rows(project, VocabularyKind.STATUS).first()
        assertTrue(leftmost.usageCount > 0, "the filed issue counts against its status")
        assertFailsWith<VocabularyRefusal> { store.delete(project, VocabularyKind.STATUS, leftmost) }
    }

    /**
     * A draft does not veto a column, and goes with it (LNL-183).
     *
     * The rule this pins is the whole of that ticket: a draft is an unpublished row
     * nobody but its author has ever seen, so it cannot be what an admin is told to
     * "move somewhere else first" — and since a draft always lands in the LEFTMOST
     * column, counting it made the first column of a long-lived project permanently
     * undeletable. Both halves are asserted here, the count and the delete, because
     * a backend that excluded drafts from the count without clearing them would
     * trade a wrong refusal for a raw constraint violation.
     */
    @Test
    fun `a draft does not block deleting its status, and goes with it`(): Unit = runBlocking {
        val project = newProject()
        val draft = createDraft(project) // lands in the leftmost status, invisibly
        val leftmost = store.rows(project, VocabularyKind.STATUS).first()
        assertEquals(0L, leftmost.usageCount, "a draft is on nobody's board and counts against nothing")

        store.delete(project, VocabularyKind.STATUS, leftmost)

        assertTrue(store.rows(project, VocabularyKind.STATUS).none { it.id == leftmost.id }, "the column went")
        assertTrue(!issueExists(draft), "and took its draft with it, rather than being refused over it")
    }
}
