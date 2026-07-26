/**
 * The behaviour every [LabelStore] implementation must exhibit.
 *
 * Pinned here: [LabelStore.forProject] returns a project's labels in position
 * order; [LabelStore.findByIdInProject] finds a row in its own project and refuses
 * one from another (or an absent id); and insert / update / setPosition / delete
 * round-trip. The trimming, uniqueness and delete refusals are the repository's
 * layer above, not this seam's, so they are not pinned here.
 *
 * A subclass per backend supplies the store and a way to make an empty project —
 * empty of labels, so the rows a test inserts are the only ones [forProject]
 * returns and their order is the store's to prove.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

abstract class LabelStoreContract {
    protected abstract val store: LabelStore

    /** A fresh project with no labels of its own. */
    protected abstract suspend fun newProject(): Long

    @Test
    fun `forProject returns the project's labels in position order`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "Bravo", 1)
        store.insert(p, "Alpha", 0)
        store.insert(p, "Charlie", 2)

        val rows = store.forProject(p)
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), rows.map { it.name }, "ordered by position, not insertion")
        assertEquals(listOf(0L, 1L, 2L), rows.map { it.position })
        assertTrue(rows.all { it.projectId == p })
    }

    @Test
    fun `findByIdInProject finds a row in its project and refuses another`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "Alpha", 0)
        val row = store.forProject(p).single()

        assertEquals("Alpha", store.findByIdInProject(row.id, p)?.name, "found in its own project")
        assertNull(store.findByIdInProject(row.id, newProject()), "a row of another project is as good as absent")
        assertNull(store.findByIdInProject(row.id + 9_999, p), "an absent id is null")
    }

    @Test
    fun `update renames a row`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "Alpha", 0)
        val row = store.forProject(p).single()

        store.update(row.id, "Renamed")
        assertEquals("Renamed", store.findByIdInProject(row.id, p)?.name)
    }

    @Test
    fun `setPosition moves a row in the order`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "Alpha", 0)
        store.insert(p, "Bravo", 1)
        val alpha = store.forProject(p).first { it.name == "Alpha" }

        store.setPosition(alpha.id, 2)
        assertEquals(listOf("Bravo", "Alpha"), store.forProject(p).map { it.name }, "Alpha now sits after Bravo")
    }

    @Test
    fun `delete removes a row`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "Alpha", 0)
        store.insert(p, "Bravo", 1)
        val alpha = store.forProject(p).first { it.name == "Alpha" }

        store.delete(alpha.id)
        assertNull(store.findByIdInProject(alpha.id, p))
        assertEquals(listOf("Bravo"), store.forProject(p).map { it.name })
    }
}
