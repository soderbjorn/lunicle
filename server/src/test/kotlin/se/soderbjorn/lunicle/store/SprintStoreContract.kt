/**
 * The behaviour every [SprintStore] implementation must exhibit.
 *
 * The parity-critical rule is the anti-stranding one: a completed sprint accepts
 * no activation and no membership change, and completing a sprint rolls its
 * unfinished work somewhere reachable (here, the backlog) rather than leaving it
 * behind. Also pinned: activation points the board's default scope at a sprint (or
 * clears it), and membership is an exact set, not a delta.
 *
 * A subclass per backend supplies the store, a project, a way to add a sprint and
 * file an issue, and a way to read back which sprint an issue is in.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.SprintRefusal

abstract class SprintStoreContract {
    protected abstract val store: SprintStore

    protected abstract suspend fun newProject(): Long
    protected abstract suspend fun newSprint(projectId: Long, name: String): Long
    protected abstract suspend fun fileIssue(projectId: Long): Long

    /** Which sprint the issue is in, read back through the backend under test. */
    protected abstract suspend fun sprintOfIssue(issueId: Long): Long?

    @Test
    fun `activate points the board at a sprint, and null clears it`(): Unit = runBlocking {
        val project = newProject()
        val sprint = newSprint(project, "Q3")
        store.activate(project, sprint)
        assertEquals(sprint, store.activeSprintId(project))
        store.activate(project, null)
        assertNull(store.activeSprintId(project))
    }

    @Test
    fun `setMembership makes the sprint hold exactly the named issues`(): Unit = runBlocking {
        val project = newProject()
        val sprint = newSprint(project, "Q3")
        val a = fileIssue(project)
        val b = fileIssue(project)

        store.setMembership(project, sprint, listOf(a))
        assertEquals(sprint, sprintOfIssue(a))
        assertNull(sprintOfIssue(b), "an unnamed issue is not in the sprint")

        // The complete-set convention: naming only b releases a back to the backlog.
        store.setMembership(project, sprint, listOf(b))
        assertEquals(sprint, sprintOfIssue(b))
        assertNull(sprintOfIssue(a), "an issue dropped from the set returns to the backlog")
    }

    @Test
    fun `completing a sprint clears it as active and rolls unfinished work to the backlog`(): Unit = runBlocking {
        val project = newProject()
        val sprint = newSprint(project, "Q3")
        val issue = fileIssue(project) // lands in the leftmost, unfinished status
        store.setMembership(project, sprint, listOf(issue))
        store.activate(project, sprint)

        store.complete(project, sprint, moveUnfinishedTo = null)

        assertNull(store.activeSprintId(project), "the completed sprint is no longer active")
        assertNull(sprintOfIssue(issue), "its unfinished work rolled to the backlog")
    }

    @Test
    fun `a completed sprint refuses activation`(): Unit = runBlocking {
        val project = newProject()
        val sprint = newSprint(project, "Q3")
        store.complete(project, sprint, moveUnfinishedTo = null)
        assertFailsWith<SprintRefusal> { store.activate(project, sprint) }
    }

    @Test
    fun `a completed sprint refuses membership changes`(): Unit = runBlocking {
        val project = newProject()
        val sprint = newSprint(project, "Q3")
        val issue = fileIssue(project)
        store.complete(project, sprint, moveUnfinishedTo = null)
        assertFailsWith<SprintRefusal> { store.setMembership(project, sprint, listOf(issue)) }
    }
}
