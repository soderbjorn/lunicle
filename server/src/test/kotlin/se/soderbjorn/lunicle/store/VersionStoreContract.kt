/**
 * The behaviour every [VersionStore] implementation must exhibit (LNL-134).
 *
 * The plainest vocabulary contract, the twin of [LabelStoreContract]: a version is
 * an id, a project, a name and a position, with no per-kind flag of its own — its
 * done-ness lives on the resolution, not here. Pinned: [VersionStore.forProject]
 * returns a project's versions in position order; [VersionStore.findByIdInProject]
 * finds a row in its own project and refuses one from another (or an absent id); and
 * insert / update / setPosition / delete round-trip. Trimming, uniqueness and the
 * (absent) delete refusals are the repository's layer above, not this seam's.
 *
 * A subclass per backend supplies the store and a way to make a project empty of
 * versions, so the rows a test inserts are the only ones [forProject] returns.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

abstract class VersionStoreContract {
    protected abstract val store: VersionStore

    /** A fresh project with no versions of its own. */
    protected abstract suspend fun newProject(): Long

    @Test
    fun `forProject returns the project's versions in position order`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "2.0", 1)
        store.insert(p, "1.0", 0)
        store.insert(p, "3.0", 2)

        val rows = store.forProject(p)
        assertEquals(listOf("1.0", "2.0", "3.0"), rows.map { it.name }, "ordered by position, not insertion")
        assertEquals(listOf(0L, 1L, 2L), rows.map { it.position })
        assertTrue(rows.all { it.projectId == p })
    }

    @Test
    fun `findByIdInProject finds a row in its project and refuses another`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "1.0", 0)
        val row = store.forProject(p).single()

        assertEquals("1.0", store.findByIdInProject(row.id, p)?.name, "found in its own project")
        assertNull(store.findByIdInProject(row.id, newProject()), "a row of another project is as good as absent")
        assertNull(store.findByIdInProject(row.id + 9_999, p), "an absent id is null")
    }

    @Test
    fun `update renames a row`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "1.0", 0)
        val row = store.forProject(p).single()

        store.update(row.id, "1.0.1")
        assertEquals("1.0.1", store.findByIdInProject(row.id, p)?.name)
    }

    @Test
    fun `setPosition moves a row in the order`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "1.0", 0)
        store.insert(p, "2.0", 1)
        val first = store.forProject(p).first { it.name == "1.0" }

        store.setPosition(first.id, 2)
        assertEquals(listOf("2.0", "1.0"), store.forProject(p).map { it.name }, "1.0 now sits after 2.0")
    }

    @Test
    fun `delete removes a row`(): Unit = runBlocking {
        val p = newProject()
        store.insert(p, "1.0", 0)
        store.insert(p, "2.0", 1)
        val first = store.forProject(p).first { it.name == "1.0" }

        store.delete(first.id)
        assertNull(store.findByIdInProject(first.id, p))
        assertEquals(listOf("2.0"), store.forProject(p).map { it.name })
    }
}
