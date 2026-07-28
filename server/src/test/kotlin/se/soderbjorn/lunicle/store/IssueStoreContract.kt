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
 * reads include filed issues and leave drafts out, while
 * [IssueStore.deleteDraftsWithStatus] and [IssueStore.sweepAbandonedDrafts] are
 * what clear those drafts away (LNL-183); and [IssueStore.setGroupOrder] ranks a
 * whole group 1..n.
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

    /**
     * The counts answer for the visible board — LNL-183's half of the fix.
     *
     * A draft used to count, which meant one abandoned "New issue" made the
     * leftmost column of a project undeletable forever: the settings dialog
     * refused over a row nobody could see, find or move. Backends that can only
     * count what they are asked to count get this wrong in the quiet direction, so
     * it is pinned with a published issue standing next to the draft — a count of
     * zero here would pass a test that only had the draft.
     */
    @Test
    fun `usage counts leave drafts out`() = runBlocking {
        val p = newProject()
        val status = p.statusIds.first()
        fileIssue(p, status)
        createDraft(p) // lands in the leftmost status, which is where a draft goes

        assertEquals(1L, store.usageByStatus(p.projectId)[status], "only the published issue counts")
        assertEquals(1L, store.usageByPriority(p.projectId)[p.priorityId], "and the same for its priority")
    }

    /**
     * The other half: the drafts a column delete takes with it.
     *
     * Both scopes asserted, because a statement missing either one is a bug that
     * looks like a working feature — one that ignores `is_draft` deletes somebody's
     * filed issue, and one that ignores `project_id` reaches into another project
     * on an id an admin of *this* one supplied.
     */
    @Test
    fun `deleteDraftsWithStatus takes this project's drafts in that status and nothing else`(): Unit = runBlocking {
        val p = newProject()
        val status = p.statusIds.first()
        val published = fileIssue(p, status)
        val draft = createDraft(p)
        val elsewhere = createDraft(newProject())

        assertEquals(1L, store.deleteDraftsWithStatus(p.projectId, status), "the one draft in that column went")
        assertEquals(null, store.findById(draft), "and it is gone")
        assertNotNull(store.findById(published), "the published issue in the same column survived")
        assertNotNull(store.findById(elsewhere), "another project's draft is not this delete's business")

        assertEquals(0L, store.deleteDraftsWithStatus(p.projectId, status), "and a second pass finds nothing")
    }

    /**
     * The startup sweep, which is what stops them accumulating in the first place.
     *
     * The cutoff is the caller's — `Application.module` passes seven days — so what
     * the store owes is the comparison and nothing else: older goes, newer stays.
     * The old draft is inserted with an explicit `createdAt` rather than by waiting.
     */
    @Test
    fun `sweepAbandonedDrafts takes drafts older than the cutoff and leaves the rest`(): Unit = runBlocking {
        val p = newProject()
        val status = p.statusIds.first()
        val old = store.insertDraft(
            p.projectId, "Abandoned", status, p.priorityId, Author.Nobody, createdAt = 1_000,
        ).first
        val fresh = createDraft(p)
        val published = fileIssue(p, status)

        assertEquals(1L, store.sweepAbandonedDrafts(cutoff = 2_000), "only the draft older than the cutoff went")
        assertEquals(null, store.findById(old))
        assertNotNull(store.findById(fresh), "a draft somebody may still be typing into stays")
        assertNotNull(store.findById(published), "and a published issue is never a sweep's business")
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
