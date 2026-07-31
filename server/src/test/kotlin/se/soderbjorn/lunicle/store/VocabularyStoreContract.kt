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
 * The eighth kind, relation kinds (LNL-215), adds the only rule here that spans two
 * columns of two rows — a kind occupies both of its labels — plus the normalisation
 * that makes a blank opposite label the *absence* of one. See the section at the end.
 *
 * A subclass per backend supplies the store, a project seeded with the default
 * vocabularies, and a way to file an issue into the leftmost status.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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

    // ── The eighth kind: relation kinds (LNL-215) ────────────────────────────
    //
    // The richest row this seam carries — two names and a flag — and the only one
    // whose uniqueness rule spans two columns. Everything else about it (appending,
    // reordering, deleting) is the machinery already pinned above, and is deliberately
    // not re-asserted per kind.
    //
    // Note the invented names — "Held up by", "Twinned with", "Caused by" — rather
    // than the obvious "Blocked by" and "Related to". Those two are SEEDED into every
    // project the SQLite fixture makes, because it builds one through the real
    // ProjectRepository, while the Firestore fixture mints a synthetic project with no
    // kinds at all. A test naming a seeded kind would therefore pass on one backend
    // and fail on the other with a conflict, which is a difference in the fixtures and
    // not in the stores. Every assertion below selects its row by id for the same
    // reason: what is under test is the row this test added, not the shape of the
    // list it landed in.

    /**
     * Both labels and the blocking flag round-trip, through add and through rename.
     *
     * The rename half is not padding: a kind's two names and its flag are **one**
     * edit — the settings row sends all three — and a backend that wrote only the name
     * would leave a renamed "Blocked by" still marking cards blocked under its old
     * opposite label, which reads as a working feature until somebody looks at a board.
     */
    @Test
    fun `a relation kind carries both labels and its blocking flag`(): Unit = runBlocking {
        val project = newProject()
        val added = store.add(project, VocabularyKind.RELATION_KIND, "Held up by", "Holds up", marksBlocked = true)
        assertEquals("Holds up", added.inverseName, "the to-side label comes back on the row add returns")
        assertTrue(added.marksBlocked)

        val read = store.rows(project, VocabularyKind.RELATION_KIND).single { it.id == added.id }
        assertEquals("Held up by", read.name, "and on the row the settings editor reads back")
        assertEquals("Holds up", read.inverseName)
        assertTrue(read.marksBlocked)

        store.rename(
            project, VocabularyKind.RELATION_KIND, read, "Waiting for",
            requiresResolution = false, isDone = false, inverseName = "Waited on by", marksBlocked = false,
        )
        val renamed = store.rows(project, VocabularyKind.RELATION_KIND).single { it.id == added.id }
        assertEquals("Waiting for", renamed.name)
        assertEquals("Waited on by", renamed.inverseName, "the opposite label moves with the name")
        assertTrue(!renamed.marksBlocked, "and so does the flag, in the same edit")
    }

    /**
     * A blank opposite label is not an opposite label — it normalises to null.
     *
     * **Null IS symmetry.** If a blank string were stored, "I ticked same-in-both-
     * directions" and "I cleared the field" would become two different stored states
     * that render identically, and every reader resolving the to-side label as
     * `inverseName ?: name` would fall through to an empty word for one of them. Both
     * entry points are covered because both accept the field.
     */
    @Test
    fun `a blank inverse name normalises to null`(): Unit = runBlocking {
        val project = newProject()
        val added = store.add(project, VocabularyKind.RELATION_KIND, "Twinned with", "   ", marksBlocked = false)
        assertNull(added.inverseName, "whitespace is not a label")
        assertNull(
            store.rows(project, VocabularyKind.RELATION_KIND).single { it.id == added.id }.inverseName,
            "and nothing was stored for the next reader to trip over",
        )

        val symmetric = store.rows(project, VocabularyKind.RELATION_KIND).single { it.id == added.id }
        store.rename(
            project, VocabularyKind.RELATION_KIND, symmetric, "Twinned with",
            requiresResolution = false, isDone = false, inverseName = "", marksBlocked = false,
        )
        assertNull(
            store.rows(project, VocabularyKind.RELATION_KIND).single { it.id == added.id }.inverseName,
            "clearing the field on a rename is the same absence, not an empty string",
        )
    }

    /**
     * A relation kind occupies **both** of its labels, and a second kind may take
     * neither.
     *
     * This is the one uniqueness rule in the whole vocabulary family that spans two
     * columns of two different rows, and neither backend has an index that can express
     * it — the SQLite `UNIQUE (project_id, name)` backstops the name half only. So the
     * two implementations *are* the rule, which is precisely why it is pinned here and
     * not in one backend's test.
     *
     * What it protects is the picker: without it "Blocks" could be one kind's opposite
     * and another kind's name, and an editor adding a link would be offered the same
     * word twice meaning two different things. The clash is asserted from both sides —
     * a new name against an existing opposite, and a new opposite against an existing
     * name — because a backend checking only one column would pass a test that tried
     * only one.
     */
    @Test
    fun `a relation kind's name may not collide with another kind's opposite label`(): Unit = runBlocking {
        val project = newProject()
        store.add(project, VocabularyKind.RELATION_KIND, "Held up by", "Holds up", marksBlocked = true)

        assertFailsWith<VocabularyConflict>("a name may not be another kind's opposite label") {
            store.add(project, VocabularyKind.RELATION_KIND, "holds up")
        }
        assertFailsWith<VocabularyConflict>("nor an opposite label another kind's name") {
            store.add(project, VocabularyKind.RELATION_KIND, "Delayed by", "HELD UP BY")
        }
        assertFailsWith<VocabularyConflict>("and a kind cannot be its own opposite") {
            store.add(project, VocabularyKind.RELATION_KIND, "Paired with", "paired with")
        }
        // The rule is exactly as wide as it needs to be: two labels that clash with
        // nothing are fine, and the kind is added.
        val ok = store.add(project, VocabularyKind.RELATION_KIND, "Caused by", "Causes")
        assertEquals("Causes", ok.inverseName)
    }
}
