/**
 * The behaviour every [ResolutionStore] implementation must exhibit — position
 * order out of [forProject], project-scoped [findByIdInProject], and insert /
 * update / setPosition / delete round-trip. A resolution's rows are [StatusRecord]
 * (an id, a project, a name, a position; `requiresResolution` always false). The
 * trimming, uniqueness and delete refusals are the repository's, not this seam's.
 *
 * A subclass per backend supplies the store and a way to make a project empty of
 * resolutions.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

abstract class ResolutionStoreContract {
    protected abstract val store: ResolutionStore

    /** A fresh project with no resolutions of its own. */
    protected abstract suspend fun newProject(): Long

    @Test
    fun `forProject returns the project's resolutions in position order`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "Won't fix", 1)
        store.insert(p, "Fixed", 0)
        store.insert(p, "Duplicate", 2)

        val rows = store.forProject(p)
        assertEquals(listOf("Fixed", "Won't fix", "Duplicate"), rows.map { it.name }, "ordered by position")
        assertEquals(listOf(0L, 1L, 2L), rows.map { it.position })
        assertTrue(rows.all { it.projectId == p })
        assertFalse(rows.any { it.requiresResolution }, "requiresResolution is meaningless here and stays false")
    }

    @Test
    fun `findByIdInProject finds a row in its project and refuses another`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "Fixed", 0)
        val row = store.forProject(p).single()

        assertEquals("Fixed", store.findByIdInProject(row.id, p)?.name, "found in its own project")
        assertNull(store.findByIdInProject(row.id, newProject()), "a row of another project is as good as absent")
        assertNull(store.findByIdInProject(row.id + 9_999, p), "an absent id is null")
    }

    @Test
    fun `update renames a row`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "Fixed", 0)
        val row = store.forProject(p).single()

        store.update(row.id, "Resolved", isDone = false)
        assertEquals("Resolved", store.findByIdInProject(row.id, p)?.name)
    }

    @Test
    fun `is_done starts false, round-trips through update, and reads back on both paths`(): Unit = runBlocking {
        // LNL-134: a resolution's done flag is what "require a fixed version when
        // resolving" consults. A new one is never born done; update sets it; and it
        // must read back the same on forProject and findByIdInProject in every backend.
        val p = newProject()
        store.insert(p, "Fixed", 0)
        val row = store.forProject(p).single()
        assertFalse(row.isDone, "a new resolution is not done")

        store.update(row.id, "Fixed", isDone = true)
        assertTrue(store.findByIdInProject(row.id, p)!!.isDone, "findByIdInProject reads the flag")
        assertTrue(store.forProject(p).single().isDone, "forProject reads the flag")

        store.update(row.id, "Fixed", isDone = false)
        assertFalse(store.findByIdInProject(row.id, p)!!.isDone, "and it can be turned back off")
    }

    @Test
    fun `setPosition moves a row in the order`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "Fixed", 0)
        store.insert(p, "Won't fix", 1)
        val fixed = store.forProject(p).first { it.name == "Fixed" }

        store.setPosition(fixed.id, 2)
        assertEquals(listOf("Won't fix", "Fixed"), store.forProject(p).map { it.name }, "Fixed now sits last")
    }

    @Test
    fun `delete removes a row`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "Fixed", 0)
        store.insert(p, "Won't fix", 1)
        val fixed = store.forProject(p).first { it.name == "Fixed" }

        store.delete(fixed.id)
        assertNull(store.findByIdInProject(fixed.id, p))
        assertEquals(listOf("Won't fix"), store.forProject(p).map { it.name })
    }
}
