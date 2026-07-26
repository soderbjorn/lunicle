/**
 * The behaviour every [PriorityStore] implementation must exhibit — position order
 * out of [forProject], project-scoped [findByIdInProject], insert / update /
 * setPosition / delete round-trip, and the one thing a priority has that a label
 * does not: [defaultForProject], the *middle* of the scale a new issue lands on.
 *
 * A priority's rows are [StatusRecord] (`requiresResolution` always false). The
 * middle is `position`-sorted index `count / 2` (integer division), so a 5-row
 * scale defaults to the third and an even scale rounds to the lower half — the
 * calmer of the two middles. The trimming, uniqueness and delete refusals are the
 * repository's, not this seam's.
 *
 * A subclass per backend supplies the store and a way to make a project empty of
 * priorities.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

abstract class PriorityStoreContract {
    protected abstract val store: PriorityStore

    /** A fresh project with no priorities of its own. */
    protected abstract suspend fun newProject(): Long

    private suspend fun seedScale(project: Long, vararg names: String) {
        names.forEachIndexed { index, name -> store.insert(project, name, index.toLong()) }
    }

    @Test
    fun `forProject returns the project's priorities in position order`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "High", 1)
        store.insert(p, "Very high", 0)
        store.insert(p, "Normal", 2)

        val rows = store.forProject(p)
        assertEquals(listOf("Very high", "High", "Normal"), rows.map { it.name }, "ordered by position, highest first")
        assertEquals(listOf(0L, 1L, 2L), rows.map { it.position })
        assertTrue(rows.all { it.projectId == p })
    }

    @Test
    fun `findByIdInProject finds a row in its project and refuses another`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "Normal", 0)
        val row = store.forProject(p).single()

        assertEquals("Normal", store.findByIdInProject(row.id, p)?.name, "found in its own project")
        assertNull(store.findByIdInProject(row.id, newProject()), "a row of another project is as good as absent")
        assertNull(store.findByIdInProject(row.id + 9_999, p), "an absent id is null")
    }

    @Test
    fun `update renames a row`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "Normal", 0)
        val row = store.forProject(p).single()

        store.update(row.id, "Medium")
        assertEquals("Medium", store.findByIdInProject(row.id, p)?.name)
    }

    @Test
    fun `setPosition moves a row in the order`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "Very high", 0)
        store.insert(p, "High", 1)
        val veryHigh = store.forProject(p).first { it.name == "Very high" }

        store.setPosition(veryHigh.id, 2)
        assertEquals(listOf("High", "Very high"), store.forProject(p).map { it.name })
    }

    @Test
    fun `delete removes a row`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "Very high", 0)
        store.insert(p, "High", 1)
        val veryHigh = store.forProject(p).first { it.name == "Very high" }

        store.delete(veryHigh.id)
        assertNull(store.findByIdInProject(veryHigh.id, p))
        assertEquals(listOf("High"), store.forProject(p).map { it.name })
    }

    @Test
    fun `defaultForProject is the middle of an odd scale`(): Unit = runBlocking {
        val p = newProject()
        seedScale(p, "Very high", "High", "Normal", "Low", "Very low")
        assertEquals("Normal", store.defaultForProject(p)?.name, "the third of five is the middle")
    }

    @Test
    fun `defaultForProject rounds an even scale to the lower half`(): Unit = runBlocking {
        val p = newProject()
        seedScale(p, "A", "B", "C", "D")
        assertEquals("C", store.defaultForProject(p)?.name, "four rows default to the third, the calmer middle")
    }

    @Test
    fun `defaultForProject is null for a project with no priorities`(): Unit = runBlocking {
        assertNull(store.defaultForProject(newProject()))
    }
}
