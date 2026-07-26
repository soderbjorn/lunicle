/**
 * The behaviour every [IssueStore] implementation must exhibit — the LNL-111
 * *hardest aggregate*, because an issue is a row plus two sets and the parity a
 * document backend is most likely to get subtly wrong lives exactly in how those
 * three move together.
 *
 * The semantics pinned here: a draft is invisible on the board read and
 * publishing makes it visible; the board read is one query that returns
 * ready-to-map rows, and its label/component companions answer the whole board in
 * one query each and reflect what [IssueStore.setLabelsAndComponents] wrote;
 * [IssueStore.findById] round-trips a filed issue; [IssueStore.setStatus] and
 * [IssueStore.setSprint] are read back; the usage counts the vocabulary editor
 * reads include filed issues; and [IssueStore.setGroupOrder] ranks a whole group
 * 1..n.
 *
 * A subclass per backend supplies the store and a project seeded — through the
 * real `ProjectRepository`/`IssueRepository` — with its default board columns, a
 * priority, two labels, two components and a sprint, plus the two ways to put an
 * issue there: [fileIssue] (createDraft + save, so it publishes) and
 * [createDraft] (createDraft alone, so it stays a draft).
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.Author

abstract class IssueStoreContract {
    protected abstract val store: IssueStore

    /** A fresh project seeded with board columns, a priority, two labels, two components and a sprint. */
    protected abstract suspend fun newProject(): Seeded

    /** File a *published* issue into [statusId] the real way — createDraft + save through IssueRepository. */
    protected abstract suspend fun fileIssue(project: Seeded, statusId: Long): Long

    /** Create a draft the real way — createDraft through IssueRepository — and leave it unsaved. */
    protected abstract suspend fun createDraft(project: Seeded): Long

    /** The seeded vocabulary a test addresses issues against. */
    protected class Seeded(
        val projectId: Long,
        /** Board columns in order; there are at least two. */
        val statusIds: List<Long>,
        val priorityId: Long,
        /** Two labels created over the store's vocabulary. */
        val labelIds: List<Long>,
        /** Two components. */
        val componentIds: List<Long>,
        val sprintId: Long,
    )

    @Test
    fun `a draft is invisible on the board read until it is published`() = runBlocking {
        val p = newProject()
        val (id, number) = store.insertDraft(p.projectId, "Draft", p.statusIds.first(), p.priorityId, Author.Nobody)
        assertTrue(number > 0, "a per-project number is allocated")
        assertTrue(store.forProject(p.projectId).none { it.id == id }, "a draft appears on nobody's board")

        store.publish(id, "Now visible", "body", p.statusIds.first(), p.priorityId, null, null, null, null, null)
        assertTrue(store.forProject(p.projectId).any { it.id == id }, "publishing puts it on the board read")
    }

    @Test
    fun `a repository-created draft is also excluded from the board read`() = runBlocking {
        val p = newProject()
        val draftId = createDraft(p)
        assertTrue(store.forProject(p.projectId).none { it.id == draftId }, "createDraft alone never reaches the board")
    }

    @Test
    fun `the board read reflects labels and components set on an issue, in one call each`() = runBlocking {
        val p = newProject()
        val id = fileIssue(p, p.statusIds.first())
        store.setLabelsAndComponents(id, p.projectId, p.labelIds, p.componentIds.take(1))

        val onBoard = store.forProject(p.projectId).singleOrNull { it.id == id }
        assertNotNull(onBoard, "the published issue is on the board read")
        assertEquals(p.statusIds.first(), onBoard.statusId, "and carries its status")

        // The whole board's labels and components, one query each.
        assertEquals(p.labelIds.toSet(), store.labelsForProject(p.projectId)[id]?.toSet(), "labels denormalise onto the board")
        assertEquals(p.componentIds.take(1).toSet(), store.componentsForProject(p.projectId)[id]?.toSet())
        // The per-issue reads agree.
        assertEquals(p.labelIds.toSet(), store.labelsFor(id).toSet())
        assertEquals(p.componentIds.take(1).toSet(), store.componentsFor(id).toSet())
    }

    @Test
    fun `setLabelsAndComponents replaces the previous set wholesale`() = runBlocking {
        val p = newProject()
        val id = fileIssue(p, p.statusIds.first())
        store.setLabelsAndComponents(id, p.projectId, p.labelIds, p.componentIds)
        store.setLabelsAndComponents(id, p.projectId, p.labelIds.take(1), emptyList())

        assertEquals(p.labelIds.take(1).toSet(), store.labelsFor(id).toSet(), "the new label set replaces the old")
        assertTrue(store.componentsFor(id).isEmpty(), "clearing components removes them all")
    }

    @Test
    fun `findById round-trips a filed issue`() = runBlocking {
        val p = newProject()
        val id = fileIssue(p, p.statusIds.first())
        val found = store.findById(id)
        assertNotNull(found, "a filed issue is found by id")
        assertEquals(p.projectId, found.projectId)
        assertEquals(p.statusIds.first(), found.statusId)
        assertEquals(p.priorityId, found.priorityId)
        assertTrue(!found.isDraft, "a saved issue is no longer a draft")
        assertTrue(found.number > 0, "and holds its allocated number")
    }

    @Test
    fun `setStatus moves an issue to another column`() = runBlocking {
        val p = newProject()
        val id = fileIssue(p, p.statusIds.first())
        val target = p.statusIds[1]
        store.setStatus(id, target, null)
        assertEquals(target, store.findById(id)?.statusId, "the new status is read back")
        assertTrue(store.forProject(p.projectId).single { it.id == id }.statusId == target, "and shows on the board read")
    }

    @Test
    fun `setSprint schedules an issue and sends it back to the backlog`() = runBlocking {
        val p = newProject()
        val id = fileIssue(p, p.statusIds.first())
        store.setSprint(id, p.sprintId)
        assertEquals(p.sprintId, store.findById(id)?.sprintId, "the sprint is read back")
        assertEquals(mapOf(p.sprintId to 1L), store.usageBySprint(p.projectId), "and counts against the sprint")

        store.setSprint(id, null)
        assertEquals(null, store.findById(id)?.sprintId, "null returns it to the backlog")
    }

    @Test
    fun `usage counts reflect filed issues and their labels`() = runBlocking {
        val p = newProject()
        val status = p.statusIds.first()
        val id = fileIssue(p, status)
        store.setLabelsAndComponents(id, p.projectId, p.labelIds.take(1), emptyList())

        assertEquals(1L, store.usageByStatus(p.projectId)[status], "the filed issue counts against its status")
        assertEquals(1L, store.usageByLabel(p.projectId)[p.labelIds.first()], "and against the label it carries")
        assertTrue(store.usageByPriority(p.projectId)[p.priorityId]!! >= 1L, "and against its priority")
    }

    @Test
    fun `setGroupOrder ranks a whole group one to n`() = runBlocking {
        val p = newProject()
        val status = p.statusIds.first()
        val first = fileIssue(p, status)
        val second = fileIssue(p, status)
        // A new issue arrives unranked (sort_order 0).
        assertEquals(0L, store.findById(first)?.sortOrder, "a fresh issue is unranked")

        store.setGroupOrder(listOf(second, first))
        assertEquals(1L, store.findById(second)?.sortOrder, "the group is renumbered from 1 in the given order")
        assertEquals(2L, store.findById(first)?.sortOrder)
    }

    @Test
    fun `setParent attaches a child to an epic and null detaches it`() = runBlocking {
        val p = newProject()
        val status = p.statusIds.first()
        val epic = fileIssue(p, status)
        val child = fileIssue(p, status)
        assertEquals(null, store.findById(child)?.parentId, "a fresh issue belongs under no epic")

        store.setParent(child, epic)
        assertEquals(epic, store.findById(child)?.parentId, "the parent is read back")
        assertEquals(0L, store.findById(child)?.childOrder, "and a freshly attached child is unranked")

        store.setParent(child, null)
        assertEquals(null, store.findById(child)?.parentId, "null detaches it")
    }

    @Test
    fun `childrenOf returns an epic's published children, drafts excluded`() = runBlocking {
        val p = newProject()
        val status = p.statusIds.first()
        val epic = fileIssue(p, status)
        val a = fileIssue(p, status)
        val b = fileIssue(p, status)
        val draftChild = createDraft(p)
        listOf(a, b, draftChild).forEach { store.setParent(it, epic) }

        val childIds = store.childrenOf(epic).map { it.id }
        assertEquals(listOf(a, b), childIds, "both published children come back, the draft does not")
    }

    @Test
    fun `setChildOrder ranks an epic's children one to n`() = runBlocking {
        val p = newProject()
        val status = p.statusIds.first()
        val epic = fileIssue(p, status)
        val a = fileIssue(p, status)
        val b = fileIssue(p, status)
        store.setParent(a, epic)
        store.setParent(b, epic)

        store.setChildOrder(listOf(b, a))
        assertEquals(1L, store.findById(b)?.childOrder, "children are renumbered from 1 in the given order")
        assertEquals(2L, store.findById(a)?.childOrder)
        assertEquals(listOf(b, a), store.childrenOf(epic).map { it.id }, "and the read reflects the new order")
    }
}
