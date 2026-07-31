/**
 * The four rules a link between two issues has to obey, the two events one link
 * writes, and the agent flag's lifecycle (LNL-215).
 *
 * Written against [IssueRepository] directly rather than through a route, for
 * [IssueHistoryTest]'s reason: a route's job is to decide *whether* a write may
 * happen, and this file is about what happens once it may. The permission gate —
 * `canEditIssue` on the issue the link is added **from**, and never on the far one —
 * lives in `BoardRoutes` and is covered where the other issue routes are.
 *
 * ── Why the duplicate rule gets three tests of its own ──────────────────────
 *
 * Because it is the rule with no backstop. Same-project is caught by the composite
 * foreign key, self-relation by an equality, both-published by a boolean — but "the
 * same pair, in either direction, under one kind" is caught by nothing: SQLite's
 * UNIQUE index sees `(from, to, kind)` and cannot tell that `(to, from, kind)` is the
 * same fact, and **Firestore cannot enforce uniqueness at all**. So the check in
 * [IssueRepository.addRelation] is the entire guarantee on both backends, and the
 * reversed case is the half a naive implementation misses.
 *
 * @see IssueRelations.sq
 * @see IssueRepository.addRelation
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.Estimate
import se.soderbjorn.lunicle.clientserver.EstimateUnit
import se.soderbjorn.lunicle.clientserver.IssueEventKind
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IssueRelationTest {
    private val file: File = Files.createTempFile("lunicle-relations", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)
    private val projects = ProjectStore(database)
    private val labels = LabelStore(database)
    private val components = ComponentStore(database)
    private val statuses = StatusStore(database)
    private val priorities = PriorityStore(database)
    private val issues = IssueStore(database)
    private val comments = CommentStore(database)
    private val attachmentStore = AttachmentStore(database)
    private val attachments = AttachmentRepository(attachmentStore, File(file.parentFile, "attachments-${file.name}"))
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
    private val events = IssueEventStore(database)
    private val relations = IssueRelationStore(database)
    private val relationKinds = IssueRelationKindStore(database, relations)
    private val history = IssueHistory(
        events, statuses, labels, components, users,
        issues = issues, projects = projects,
    )
    private val issueRepository = IssueRepository(
        issues, comments, statuses, priorities, attachments, attachmentStore,
        history = history, relations = relations, relationKinds = relationKinds,
    )

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── The seed ─────────────────────────────────────────────────────────────

    /**
     * A brand-new project arrives with the three kinds, in order, flags and all.
     *
     * The same three 36.sqm writes into every migrated project — see
     * [RelationKindMigrationTest], which asserts the other half. Both are pinned
     * because a fresh project and a migrated one that disagreed would be two boards
     * with different vocabularies and nothing on screen to explain why.
     */
    @Test
    fun `a new project is seeded with three relation kinds`(): Unit = runBlocking {
        val f = seed()
        val kinds = relationKinds.forProject(f.projectId)
        assertEquals(listOf("Blocked by", "Duplicate of", "Related to"), kinds.map { it.name })
        assertEquals(listOf("Blocks", "Duplicated by", null), kinds.map { it.inverseName })
        assertEquals(listOf(true, false, false), kinds.map { it.marksBlocked })
    }

    /**
     * "Related to" reads the same from both ends, and that is the null doing the work.
     *
     * [IssueRelationKindRecord.labelTo] is the one place the `?: name` fallback is
     * spelled; every renderer goes through it precisely so nobody has to remember.
     */
    @Test
    fun `a symmetric kind reads the same in both directions`(): Unit = runBlocking {
        val f = seed()
        val related = kind(f, "Related to")
        assertTrue(related.isSymmetric)
        assertEquals("Related to", related.labelFrom)
        assertEquals("Related to", related.labelTo)
    }

    // ── The four rules ───────────────────────────────────────────────────────

    @Test
    fun `an issue cannot be related to itself`(): Unit = runBlocking {
        val f = seed()
        val a = publish(f)
        val refusal = issueRepository.addRelation(issues.findById(a)!!, a, kind(f, "Blocked by").id)
        assertTrue(refusal.isFailure)
        assertEquals("An issue cannot be related to itself.", refusal.exceptionOrNull()?.message)
    }

    @Test
    fun `a related issue must be in the same project`(): Unit = runBlocking {
        val f = seed()
        val other = projectRepository.create("Lunamux", "LMX")
        val here = publish(f)
        val there = publish(f, projectId = other.id)
        val refusal = issueRepository.addRelation(issues.findById(here)!!, there, kind(f, "Blocked by").id)
        assertTrue(refusal.isFailure)
        assertEquals("A related issue must be in the same project.", refusal.exceptionOrNull()?.message)
    }

    /**
     * A draft cannot be linked, in either role.
     *
     * `setParent`'s draft rule, applied to relations for its reason: a link to an issue
     * nobody can see yet renders as a row pointing at nothing, and the draft's author
     * has not decided it exists.
     */
    @Test
    fun `an unpublished issue cannot be linked`(): Unit = runBlocking {
        val f = seed()
        val published = publish(f)
        val (draftId, _) = issueRepository.createDraft(f.projectId, Author.Account(f.userId))
        val refusal = issueRepository.addRelation(issues.findById(published)!!, draftId, kind(f, "Blocked by").id)
        assertTrue(refusal.isFailure)
        assertEquals(
            "That issue is not published yet, so it cannot be linked.",
            refusal.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `the same pair cannot be linked twice under one kind`(): Unit = runBlocking {
        val f = seed()
        val a = publish(f)
        val b = publish(f)
        val blockedBy = kind(f, "Blocked by")
        assertTrue(issueRepository.addRelation(issues.findById(a)!!, b, blockedBy.id).isSuccess)
        val refusal = issueRepository.addRelation(issues.findById(a)!!, b, blockedBy.id)
        assertTrue(refusal.isFailure)
        assertTrue(refusal.exceptionOrNull()?.message.orEmpty().contains("already linked"))
    }

    /**
     * **The one no index catches.** "A blocked by B" and "B blocks A" are the same
     * fact, so adding the second when the first exists must be refused rather than
     * stored — the whole one-row design rests on them not both being written.
     *
     * SQLite's `UNIQUE (from_issue_id, to_issue_id, kind_id)` sees two different tuples
     * here and is perfectly happy. This test is what stands in its place, and it stands
     * in its place on Firestore too, which has no uniqueness at all.
     */
    @Test
    fun `the reversed pair cannot be linked under the same kind`(): Unit = runBlocking {
        val f = seed()
        val a = publish(f)
        val b = publish(f)
        val blockedBy = kind(f, "Blocked by")
        assertTrue(issueRepository.addRelation(issues.findById(a)!!, b, blockedBy.id).isSuccess)
        val refusal = issueRepository.addRelation(issues.findById(b)!!, a, blockedBy.id)
        assertTrue(refusal.isFailure, "B→A under the same kind is the same fact as A→B and must be refused")
        assertEquals(1, relations.forIssue(a).size)
    }

    /**
     * Two *different* kinds over the same pair are fine, and deliberately so: "A is
     * blocked by B" and "A is a duplicate of B" are two statements, and a team that
     * wants to make both is not confused.
     */
    @Test
    fun `the same pair may be linked under two different kinds`(): Unit = runBlocking {
        val f = seed()
        val a = publish(f)
        val b = publish(f)
        assertTrue(issueRepository.addRelation(issues.findById(a)!!, b, kind(f, "Blocked by").id).isSuccess)
        assertTrue(issueRepository.addRelation(issues.findById(a)!!, b, kind(f, "Duplicate of").id).isSuccess)
        assertEquals(2, relations.forIssue(a).size)
    }

    // ── One row, both directions ─────────────────────────────────────────────

    /**
     * One stored row, readable from both ends — the design's headline claim.
     *
     * Storing the inverse as a second row is the trap this asserts against: it would
     * make `forIssue(b)` return one row here too, and every read would look identical
     * right up until a partial failure left the pair disagreeing.
     */
    @Test
    fun `one row is stored and read from both ends`(): Unit = runBlocking {
        val f = seed()
        val a = publish(f)
        val b = publish(f)
        issueRepository.addRelation(issues.findById(a)!!, b, kind(f, "Blocked by").id).getOrThrow()

        assertEquals(1, relations.forProject(f.projectId).size, "a link is ONE row, never two")
        val fromA = relations.forIssue(a).single()
        val fromB = relations.forIssue(b).single()
        assertEquals(fromA.id, fromB.id, "both ends see the same row")
        assertEquals(b, fromA.otherThan(a))
        assertEquals(a, fromB.otherThan(b))
    }

    /** The label a reader sees depends on which end they are standing at. */
    @Test
    fun `each end reads its own side's label`(): Unit = runBlocking {
        val f = seed()
        val a = publish(f)
        val b = publish(f)
        val blockedBy = kind(f, "Blocked by")
        val row = issueRepository.addRelation(issues.findById(a)!!, b, blockedBy.id).getOrThrow()

        assertEquals("Blocked by", blockedBy.labelFor(isFromSide = row.fromIssueId == a))
        assertEquals("Blocks", blockedBy.labelFor(isFromSide = row.fromIssueId == b))
    }

    // ── History: two events, one per issue ───────────────────────────────────

    /**
     * One link, two events — and that does **not** contradict the one-row rule.
     *
     * The rule is about `issue_relations`, where two rows would be two sources of truth
     * that can drift. History is per-issue and append-only: both issues genuinely had
     * something happen to them, and somebody reading B needs to see that it now blocks A.
     */
    @Test
    fun `adding a link records an event on each issue, with each side's label`(): Unit = runBlocking {
        val f = seed()
        val a = publish(f)
        val b = publish(f)
        issueRepository.addRelation(
            issues.findById(a)!!, b, kind(f, "Blocked by").id, Author.Account(f.userId),
        ).getOrThrow()

        val onA = history.forIssue(a).last()
        val onB = history.forIssue(b).last()
        assertEquals(IssueEventKind.RELATION_ADDED, onA.kind)
        assertEquals(IssueEventKind.RELATION_ADDED, onB.kind)
        assertEquals("Blocked by", onA.relationKind)
        assertEquals("Blocks", onB.relationKind)
        // The other issue's KEY, not its id — a snapshot, so re-prefixing the project
        // later does not rewrite what the history says happened.
        assertEquals("LNL-2", onA.value)
        assertEquals("LNL-1", onB.value)
    }

    @Test
    fun `removing a link records the matching pair`(): Unit = runBlocking {
        val f = seed()
        val a = publish(f)
        val b = publish(f)
        val row = issueRepository.addRelation(
            issues.findById(a)!!, b, kind(f, "Blocked by").id, Author.Account(f.userId),
        ).getOrThrow()
        issueRepository.removeRelation(issues.findById(a)!!, row.id, Author.Account(f.userId)).getOrThrow()

        assertEquals(IssueEventKind.RELATION_REMOVED, history.forIssue(a).last().kind)
        assertEquals(IssueEventKind.RELATION_REMOVED, history.forIssue(b).last().kind)
        assertEquals("Blocks", history.forIssue(b).last().relationKind)
        assertTrue(relations.forIssue(a).isEmpty())
    }

    /**
     * The kind's label is frozen at write time, so renaming the kind afterwards does
     * not rewrite the past.
     *
     * `STATUS_CHANGED`'s rule, and it matters more here: relation kinds are
     * user-editable vocabulary, so a reference would face RESTRICT (a kind that ever
     * appeared in history becomes undeletable) or CASCADE (the past is silently
     * rewritten). A name snapshot is the only correct choice.
     */
    @Test
    fun `renaming a kind does not rewrite the events already written`(): Unit = runBlocking {
        val f = seed()
        val a = publish(f)
        val b = publish(f)
        val blockedBy = kind(f, "Blocked by")
        issueRepository.addRelation(issues.findById(a)!!, b, blockedBy.id, Author.Account(f.userId)).getOrThrow()

        relationKinds.update(blockedBy.id, "Waiting on", "Holds up", marksBlocked = true)

        assertEquals("Blocked by", history.forIssue(a).last().relationKind)
    }

    // ── Deletion cascades ────────────────────────────────────────────────────

    /**
     * Deleting a kind takes its links, and the count is available first.
     *
     * A cascade rather than the SET NULL a version takes, because a relation row
     * without its kind is not a weakened statement — it is two issue ids and nothing
     * said about them. The count exists so the confirmation can say what the delete
     * costs; it is a sentence, not a gate.
     */
    @Test
    fun `deleting a kind cascades its links, and the count is readable first`(): Unit = runBlocking {
        val f = seed()
        val a = publish(f)
        val b = publish(f)
        val blockedBy = kind(f, "Blocked by")
        issueRepository.addRelation(issues.findById(a)!!, b, blockedBy.id).getOrThrow()

        assertEquals(mapOf(blockedBy.id to 1L), relations.usageByKind(f.projectId))
        relationKinds.delete(blockedBy.id)
        assertTrue(relations.forProject(f.projectId).isEmpty())
    }

    /** Deleting an issue takes every link naming it, in either direction. */
    @Test
    fun `deleting an issue sweeps its links from both directions`(): Unit = runBlocking {
        val f = seed()
        val a = publish(f)
        val b = publish(f)
        val c = publish(f)
        issueRepository.addRelation(issues.findById(a)!!, b, kind(f, "Blocked by").id).getOrThrow()
        issueRepository.addRelation(issues.findById(c)!!, b, kind(f, "Related to").id).getOrThrow()

        issueRepository.delete(issues.findById(b)!!)

        assertTrue(relations.forProject(f.projectId).isEmpty(), "b was on the from side of one and the to side of the other")
    }

    // ── E: the agent flag's lifecycle ────────────────────────────────────────

    /** Nobody assigned means nobody's agent — a flag about no one is not a state. */
    @Test
    fun `the agent flag is forced false when nobody is assigned`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f)
        save(f, id, assigneeId = null, assigneeIsAgent = true)
        assertFalse(issues.findById(id)!!.assigneeIsAgent)
    }

    /**
     * **Changing the assignee clears it** — the rule this feature turns on.
     *
     * The unstated (`null`) flag is what makes the rule reachable: a caller that is not
     * editing the field says so, rather than passing the old value back and dragging it
     * across the handover. See [IssueRepository.save].
     */
    @Test
    fun `changing the assignee clears an unstated agent flag`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f)
        save(f, id, assigneeId = f.userId, assigneeIsAgent = true)
        assertTrue(issues.findById(id)!!.assigneeIsAgent)

        // A caller that is not editing the flag — MCP's update_issue with the argument
        // absent — hands the issue to somebody else.
        save(f, id, assigneeId = f.otherId, assigneeIsAgent = null)
        assertFalse(issues.findById(id)!!.assigneeIsAgent, "the previous assignee's agent is not on it any more")
    }

    /** An unchanged assignee keeps the flag when the caller says nothing about it. */
    @Test
    fun `an unstated agent flag survives a save that does not move the assignee`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f)
        save(f, id, assigneeId = f.userId, assigneeIsAgent = true)
        save(f, id, title = "Renamed", assigneeId = f.userId, assigneeIsAgent = null)
        assertTrue(issues.findById(id)!!.assigneeIsAgent)
    }

    /**
     * Handing an issue over AND flagging it in one save is allowed.
     *
     * The rule is that the flag does not *survive* a change nobody re-asserted, not
     * that it cannot accompany one — "give this to Ada's agent" is one gesture.
     */
    @Test
    fun `a stated agent flag may accompany a change of assignee`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f)
        save(f, id, assigneeId = f.userId, assigneeIsAgent = true)
        save(f, id, assigneeId = f.otherId, assigneeIsAgent = true)
        assertTrue(issues.findById(id)!!.assigneeIsAgent)
    }

    // ── A: the estimate pair ─────────────────────────────────────────────────

    /** The pair round-trips, and the unit is the issue's own. */
    @Test
    fun `an estimate round-trips as an amount and a unit`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f)
        save(f, id, estimate = Estimate(150, EstimateUnit.MINUTES))
        assertEquals(Estimate(150, EstimateUnit.MINUTES), issues.findById(id)!!.estimate)
    }

    /**
     * Clearing nulls **both** columns, never one.
     *
     * A unit left behind by a cleared amount would be inherited by the next estimate
     * written — reading as minutes on a project that has since moved to points, with
     * nothing on screen to say so. The pair is written by one statement precisely so
     * that cannot happen.
     */
    @Test
    fun `clearing an estimate nulls both columns`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f)
        save(f, id, estimate = Estimate(3, EstimateUnit.POINTS))
        save(f, id, estimate = null)
        assertNull(issues.findById(id)!!.estimate)
    }

    /**
     * The stored unit is the issue's, so switching the *project* reinterprets nothing.
     *
     * This is the whole reason `estimate_unit` is a column rather than a lookup. An
     * issue estimated at 3 points still reads as 3 points after an administrator moves
     * the project to time — where a derived unit would silently turn it into 3 minutes.
     */
    @Test
    fun `switching the project's mode does not reinterpret a stored estimate`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f)
        save(f, id, estimate = Estimate(3, EstimateUnit.POINTS))
        projects.setEstimateMode(f.projectId, se.soderbjorn.lunicle.clientserver.EstimateMode.TIME)
        assertEquals(Estimate(3, EstimateUnit.POINTS), issues.findById(id)!!.estimate)
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private class Fixture(val userId: Long, val otherId: Long, val projectId: Long)

    private suspend fun seed(): Fixture {
        val user = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-1", "Robert", "robert@example.com"))
        val other = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-2", "Ada", "ada@example.com"))
        val project = projectRepository.create("Lunicle", "LNL")
        return Fixture(user.id, other.id, project.id)
    }

    /** One of the project's seeded relation kinds, by name. */
    private suspend fun kind(f: Fixture, name: String): IssueRelationKindRecord =
        relationKinds.forProject(f.projectId).first { it.name == name }

    private suspend fun publish(f: Fixture, projectId: Long = f.projectId, title: String = "Something"): Long {
        val (id, _) = issueRepository.createDraft(projectId, Author.Account(f.userId))
        val issue = issues.findById(id)!!
        issueRepository.save(
            issue = issue,
            title = title,
            description = "",
            statusId = issue.statusId,
            priorityId = issue.priorityId,
            resolutionId = null,
            assigneeId = null,
            sprintId = null,
            plannedVersionId = null,
            fixedVersionId = null,
            labelIds = emptyList(),
            componentIds = emptyList(),
        )
        return id
    }

    /**
     * A save through the repository, defaulting every field to the issue's current
     * value — [IssueHistoryTest]'s helper, and the defaults are its point: a test that
     * changes one field must send everything else back unchanged, because that is
     * exactly what the editor does.
     *
     * [assigneeIsAgent] is the exception, and deliberately: its default is `null`,
     * which means "not editing this" rather than "false". Passing the issue's current
     * value back would be the very thing the lifecycle rule exists to catch.
     */
    private suspend fun save(
        f: Fixture,
        id: Long,
        title: String? = null,
        assigneeId: Long? = null,
        assigneeIsAgent: Boolean? = null,
        estimate: Estimate? = null,
    ) {
        val issue = issues.findById(id)!!
        issueRepository.save(
            issue = issue,
            title = title ?: issue.title,
            description = issue.description,
            statusId = issue.statusId,
            priorityId = issue.priorityId,
            resolutionId = issue.resolutionId,
            assigneeId = assigneeId,
            assigneeIsAgent = assigneeIsAgent,
            sprintId = issue.sprintId,
            plannedVersionId = issue.plannedVersionId,
            fixedVersionId = issue.fixedVersionId,
            estimate = estimate,
            labelIds = issues.labelsFor(id),
            componentIds = issues.componentsFor(id),
            actor = Author.Account(f.userId),
        )
    }
}
