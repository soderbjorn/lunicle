/**
 * LNL-132: the editor and the board are one vocabulary, on Firestore.
 *
 * On SQLite there is no question to answer: the settings **editor** (the generic
 * `store.VocabularyStore`) and the **board** reads (the concrete
 * `StatusStore`/`LabelStore`/…) are the same `VocabularyRepository` over the same
 * tables. On Firestore they are two implementations — [FirestoreVocabularyStore] for
 * the editor, the five concrete stores for the board — over the one `vocabulary`
 * collection, sharing its counter and its field constants. LNL-130 asserted they
 * *should* interoperate; nothing pinned it.
 *
 * This does. Every write goes through the generic editor path a settings dialog
 * uses; every read-back goes through the concrete `forProject` the board renders
 * from. The load-bearing case is `requiresResolution` — the field the concrete
 * `StatusStore` expects on a status document and the generic `add()` defaults to
 * false: a status added through the editor must read back on the board as *not*
 * closing, and one the editor flags must read back as closing. Position and name
 * round-trip through add, rename and reorder are covered too, and the reverse
 * direction (a concrete insert seen by the generic `rows()`), so neither side can
 * quietly write a shape the other cannot read.
 *
 * Skips when no emulator is configured, like every Firestore contract test.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreComponentStore
import se.soderbjorn.lunicle.FirestoreIssueStore
import se.soderbjorn.lunicle.FirestoreLabelStore
import se.soderbjorn.lunicle.FirestoreStatusStore
import se.soderbjorn.lunicle.FirestoreVocabularyStore
import se.soderbjorn.lunicle.clientserver.VocabularyKind

class FirestoreVocabularyInteropTest {
    private val fixture = FirestoreContractFixture()

    private val issues by lazy { FirestoreIssueStore(fixture.firestore) }

    // The links between issues (LNL-215), which the vocabulary store needs for a
    // relation kind's usage count and for the cascade its delete performs. Real rather
    // than a stub, so this fixture holds the same two stores production wires together.
    private val relations by lazy { se.soderbjorn.lunicle.FirestoreIssueRelationStore(fixture.firestore) }

    // The editor path — one generic VocabularyStore, exactly what the settings dialog holds.
    private val editor by lazy { FirestoreVocabularyStore(fixture.firestore, issues, relations) }

    // The board path — the concrete stores the board reads columns and vocabulary from.
    private val boardStatuses by lazy { FirestoreStatusStore(fixture.firestore) }
    private val boardLabels by lazy { FirestoreLabelStore(fixture.firestore) }
    private val boardComponents by lazy { FirestoreComponentStore(fixture.firestore) }

    private var seq = 5_000L
    private fun nextProject(): Long = ++seq

    @Test
    fun `a status added through the editor is read by the board, not closing`(): Unit = runBlocking {
        val project = nextProject()
        val added = editor.add(project, VocabularyKind.STATUS, "Triage")

        val onBoard = assertNotNull(
            boardStatuses.forProject(project).firstOrNull { it.id == added.id },
            "the editor's status is on the board",
        )
        assertEquals("Triage", onBoard.name)
        assertEquals(added.position, onBoard.position, "position round-trips")
        assertFalse(onBoard.requiresResolution, "a plain added status does not demand a resolution")
    }

    @Test
    fun `the editor flag for requiresResolution reaches the board`(): Unit = runBlocking {
        val project = nextProject()
        val status = editor.add(project, VocabularyKind.STATUS, "Done")

        // The dialog toggles the closing flag on via rename — the only editor write that carries it.
        editor.rename(project, VocabularyKind.STATUS, status, "Done", requiresResolution = true, isDone = false)

        val onBoard = assertNotNull(boardStatuses.findByIdInProject(status.id, project))
        assertTrue(onBoard.requiresResolution, "the board sees the closing flag the editor set")

        // And clearing it again round-trips the other way.
        val reread = editor.rows(project, VocabularyKind.STATUS).single { it.id == status.id }
        editor.rename(project, VocabularyKind.STATUS, reread, "Done", requiresResolution = false, isDone = false)
        assertFalse(assertNotNull(boardStatuses.findByIdInProject(status.id, project)).requiresResolution)
    }

    @Test
    fun `a label renamed through the editor is read by the board`(): Unit = runBlocking {
        val project = nextProject()
        val label = editor.add(project, VocabularyKind.LABEL, "Bug")
        editor.rename(project, VocabularyKind.LABEL, label, "Defect", requiresResolution = false, isDone = false)

        assertEquals(
            "Defect",
            boardLabels.forProject(project).firstOrNull { it.id == label.id }?.name,
            "the board reads the editor's rename",
        )
    }

    @Test
    fun `an editor reorder is the board's column order`(): Unit = runBlocking {
        val project = nextProject()
        val a = editor.add(project, VocabularyKind.STATUS, "A")
        val b = editor.add(project, VocabularyKind.STATUS, "B")
        val c = editor.add(project, VocabularyKind.STATUS, "C")

        editor.reorder(project, VocabularyKind.STATUS, listOf(c.id, a.id, b.id))

        val board = boardStatuses.forProject(project)
        assertEquals(listOf(c.id, a.id, b.id), board.map { it.id }, "the board follows the editor's order")
        assertEquals((0 until board.size).map { it.toLong() }, board.map { it.position }, "positions are contiguous 0..n-1")
    }

    @Test
    fun `a component the board inserts is read by the editor`(): Unit = runBlocking {
        // The reverse direction: a concrete insert (a seed, or another board write) must
        // be a well-formed row the generic editor lists — the two write shapes agree.
        val project = nextProject()
        boardComponents.insert(project, "Server", 0L)

        val row = editor.rows(project, VocabularyKind.COMPONENT).singleOrNull()
        assertEquals("Server", row?.name, "the editor lists the board's inserted component")
    }

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
