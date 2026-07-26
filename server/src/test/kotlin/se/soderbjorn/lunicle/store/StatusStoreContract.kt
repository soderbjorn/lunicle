/**
 * The behaviour every [StatusStore] implementation must exhibit — the richest of
 * the five typed vocabulary contracts.
 *
 * The common surface (position order out of [forProject], project-scoped
 * [findByIdInProject], insert / setPosition / delete round-trip) plus the two
 * things a status has that a label does not: `requiresResolution` — the "magic" in
 * "Closed is a magic status", which [StatusStore.insert] carries and
 * [StatusStore.update] flips alongside the name — and [StatusStore.firstForProject],
 * the leftmost column where a new issue lands. The trimming, uniqueness and delete
 * refusals are the repository's, not this seam's.
 *
 * A subclass per backend supplies the store and a way to make a project empty of
 * statuses.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

abstract class StatusStoreContract {
    protected abstract val store: StatusStore

    /** A fresh project with no statuses of its own. */
    protected abstract suspend fun newProject(): Long

    @Test
    fun `forProject returns the project's columns in position order`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "In progress", 1)
        store.insert(p, "New", 0)
        store.insert(p, "Closed", 2)

        val rows = store.forProject(p)
        assertEquals(listOf("New", "In progress", "Closed"), rows.map { it.name }, "left to right by position")
        assertEquals(listOf(0L, 1L, 2L), rows.map { it.position })
        assertTrue(rows.all { it.projectId == p })
    }

    @Test
    fun `insert carries requiresResolution and forProject reads it back`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "New", 0, requiresResolution = false)
        store.insert(p, "Closed", 1, requiresResolution = true)

        val rows = store.forProject(p)
        assertFalse(rows.first { it.name == "New" }.requiresResolution, "an ordinary column demands nothing")
        assertTrue(rows.first { it.name == "Closed" }.requiresResolution, "the closing column is magic")
    }

    @Test
    fun `update renames a column and flips its closing flag together`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "Closed", 0, requiresResolution = true)
        val closed = store.forProject(p).single()

        store.update(closed.id, "Done", requiresResolution = false)
        val updated = store.findByIdInProject(closed.id, p)
        assertEquals("Done", updated?.name, "renamed")
        assertEquals(false, updated?.requiresResolution, "and no longer demands a resolution")
    }

    @Test
    fun `findByIdInProject finds a column in its project and refuses another`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "New", 0)
        val row = store.forProject(p).single()

        assertEquals("New", store.findByIdInProject(row.id, p)?.name, "found in its own project")
        assertNull(store.findByIdInProject(row.id, newProject()), "a column of another project is as good as absent")
        assertNull(store.findByIdInProject(row.id + 9_999, p), "an absent id is null")
    }

    @Test
    fun `setPosition moves a column in the order`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "New", 0)
        store.insert(p, "In progress", 1)
        val new = store.forProject(p).first { it.name == "New" }

        store.setPosition(new.id, 2)
        assertEquals(listOf("In progress", "New"), store.forProject(p).map { it.name })
    }

    @Test
    fun `delete removes a column`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "New", 0)
        store.insert(p, "In progress", 1)
        val inProgress = store.forProject(p).first { it.name == "In progress" }

        store.delete(inProgress.id)
        assertNull(store.findByIdInProject(inProgress.id, p))
        assertEquals(listOf("New"), store.forProject(p).map { it.name })
    }

    @Test
    fun `firstForProject is the leftmost column`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "In progress", 1)
        store.insert(p, "New", 0)
        store.insert(p, "Closed", 2)

        assertEquals("New", store.firstForProject(p)?.name, "the lowest position is where a new issue lands")
    }

    @Test
    fun `firstForProject is null for a project with no columns`(): Unit = runBlocking {
        assertNull(store.firstForProject(newProject()))
    }
}
