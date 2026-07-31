/**
 * The behaviour every [SprintStore] implementation must exhibit.
 *
 * The parity-critical rule is the anti-stranding one: a completed sprint accepts
 * no activation and no membership change, and completing a sprint rolls its
 * unfinished work somewhere reachable (here, the backlog) rather than leaving it
 * behind. Also pinned: activation points the board's default scope at a sprint (or
 * clears it), membership is an exact set rather than a delta, and reopening lifts the
 * stamp — which is the same rule read the other way round, since being plannable again
 * is what "reopened" has to mean.
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

    /**
     * Reopening lifts the stamp, and lifting it un-blocks the writes a finished sprint
     * refuses (LNL-196).
     *
     * Asserted through `setMembership` as well as by reading `completedAt` back, because
     * the stamp is not the contract — being plannable again is. A backend that cleared a
     * field the refusal does not read would pass the first assertion and still strand
     * work.
     */
    @Test
    fun `reopening a sprint makes it plannable again`(): Unit = runBlocking {
        val project = newProject()
        val sprint = newSprint(project, "Q3")
        val issue = fileIssue(project)
        store.complete(project, sprint, moveUnfinishedTo = null)

        store.reopen(project, sprint)

        assertNull(
            store.forProject(project).single { it.id == sprint }.completedAt,
            "the completion stamp survived a reopen",
        )
        store.setMembership(project, sprint, listOf(issue))
        assertEquals(sprint, sprintOfIssue(issue), "a reopened sprint would not take work")
    }

    /**
     * Reopening what is already open writes the same NULL again rather than refusing —
     * `activate`'s rule, for its reason: it is what a second click on a slow connection
     * sends, and an error for a request whose end state already holds is a worse answer
     * than doing nothing.
     */
    @Test
    fun `reopening an open sprint is allowed and changes nothing`(): Unit = runBlocking {
        val project = newProject()
        val sprint = newSprint(project, "Q3")
        store.reopen(project, sprint)
        assertNull(store.forProject(project).single { it.id == sprint }.completedAt)
    }

    /** A sprint in another project is refused, like every other write here. */
    @Test
    fun `reopening a sprint from another project is refused`(): Unit = runBlocking {
        val mine = newProject()
        val theirs = newProject()
        val sprint = newSprint(theirs, "Q3")
        assertFailsWith<SprintRefusal> { store.reopen(mine, sprint) }
    }
}
