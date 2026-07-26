/**
 * The behaviour every [ForumStore] implementation must exhibit.
 *
 * Persistence-level, since a forum's name rules, uniqueness and blank-to-null
 * description normalisation are backend-agnostic orchestration a layer up in
 * `ForumRepository`: a forum round-trips through insert → findById /
 * findByIdInProject, an insert appends to the end of its project's list,
 * findByIdInProject refuses a forum that is not this project's, update rewrites
 * the name and description, forProject follows the order setOrder writes, delete
 * removes the row, and forProject is isolated per project.
 *
 * A backend seeding hook is needed because a forum hangs off a real project row:
 * [newProject] mints one however the backend under test makes projects.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

abstract class ForumStoreContract {
    protected abstract val store: ForumStore

    /** A project row for a forum to hang off, made the backend's own way. */
    protected abstract suspend fun newProject(): Long

    @Test
    fun `an inserted forum round-trips through both reads`() = runBlocking {
        val project = newProject()
        val created = store.insert(project, "General", "Anything goes")
        val byId = store.findById(created.id)!!
        assertEquals("General", byId.name)
        assertEquals("Anything goes", byId.description)
        assertEquals(project, byId.projectId)
        val inProject = store.findByIdInProject(created.id, project)!!
        assertEquals(created.id, inProject.id)
    }

    @Test
    fun `insert appends to the end of the project's list`() = runBlocking {
        val project = newProject()
        val a = store.insert(project, "First", null)
        val b = store.insert(project, "Second", null)
        val c = store.insert(project, "Third", null)
        assertEquals(listOf(a.id, b.id, c.id), store.forProject(project).map { it.id })
        assertEquals(listOf(0L, 1L, 2L), store.forProject(project).map { it.position })
    }

    @Test
    fun `findByIdInProject refuses a forum from another project`() = runBlocking {
        val project = newProject()
        val other = newProject()
        val forum = store.insert(project, "Scoped", null)
        assertNull(store.findByIdInProject(forum.id, other))
    }

    @Test
    fun `update rewrites the name and description`() = runBlocking {
        val project = newProject()
        val forum = store.insert(project, "Old", "old desc")
        store.update(forum.id, "New", null)
        val read = store.findById(forum.id)!!
        assertEquals("New", read.name)
        assertNull(read.description)
    }

    @Test
    fun `forProject follows the order setOrder writes`() = runBlocking {
        val project = newProject()
        val a = store.insert(project, "A", null)
        val b = store.insert(project, "B", null)
        val c = store.insert(project, "C", null)
        // Inserted a,b,c; ask for the reverse.
        store.setOrder(listOf(c.id, b.id, a.id))
        assertEquals(listOf(c.id, b.id, a.id), store.forProject(project).map { it.id })
    }

    @Test
    fun `delete removes the forum`() = runBlocking {
        val project = newProject()
        val forum = store.insert(project, "Doomed", null)
        store.delete(forum.id)
        assertNull(store.findById(forum.id))
        assertEquals(emptyList(), store.forProject(project).map { it.id })
    }

    @Test
    fun `forProject is isolated per project`() = runBlocking {
        val project = newProject()
        val other = newProject()
        val mine = store.insert(project, "Mine", null)
        store.insert(other, "Theirs", null)
        assertEquals(listOf(mine.id), store.forProject(project).map { it.id })
    }
}
