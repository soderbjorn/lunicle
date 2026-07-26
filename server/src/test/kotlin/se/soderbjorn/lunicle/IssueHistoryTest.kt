/**
 * What gets recorded, and — mostly — what does not.
 *
 * The derivation is the whole feature, and almost every way it can be wrong is a
 * way it records *too much*. The editor sends its entire field set on every save
 * whether the user touched one field or six, so a build that skipped the
 * comparison would report a typo fix as "changed the title, edited the
 * description, set the labels, set the components, moved this, assigned this" —
 * six lines, all true of the payload and none of them true of what happened.
 * Most of the tests below therefore assert on absence.
 *
 * Written against [IssueHistory] and [IssueRepository] directly rather than
 * through a route: the routes' job is to decide *whether* a write may happen, and
 * this file is about what the history says once it has.
 *
 * @see IssueHistory
 * @see HistoryMigrationTest for the back-fill half
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.IssueEventKind
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IssueHistoryTest {
    private val file: File = Files.createTempFile("lunicle-history", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)
    private val roles = RoleStore(database)
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
    private val history = IssueHistory(events, statuses, labels, components, users)
    private val issueRepository = IssueRepository(
        issues, comments, statuses, priorities, attachments, attachmentStore,
        history = history,
    )

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── Creation ─────────────────────────────────────────────────────────────

    /**
     * Publishing a draft writes CREATED, and only CREATED.
     *
     * The title and labels the editor filled in on the way are deliberately not
     * four more events: a draft is not a thing that existed and was then edited,
     * it was being written. See [IssueHistory.recordCreated].
     */
    @Test
    fun `publishing a draft records creation alone`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f, title = "Login is broken", labelIds = listOf(f.bugLabelId))

        assertEquals(listOf(IssueEventKind.CREATED), history.forIssue(id).map { it.kind })
    }

    /** The event belongs to whoever the write was attributed to. */
    @Test
    fun `the creation event carries the author`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f)

        val event = history.forIssue(id).single()
        assertEquals(Author.Account(f.userId), event.author, "The creation event lost its author.")
        assertNull(event.agentName, "A human filing an issue was recorded as an agent.")
    }

    /**
     * An agent's label rides on the event, not just on the issue.
     *
     * The per-event column is the whole point: `issues.agent_name` can only say an
     * agent touched the issue at some point, never which change was theirs.
     */
    @Test
    fun `an agent's label is recorded on the event`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f, agentName = "Claude Code")

        assertEquals("Claude Code", history.forIssue(id).single().agentName)
    }

    // ── The comparison: what a save does NOT record ──────────────────────────

    /**
     * The central test of the file. A save that changes nothing records nothing.
     *
     * This is what the whole derivation exists for. The editor round-trips every
     * field, so a re-save of an untouched issue arrives here looking exactly like
     * a six-field edit — and a build without the comparison passes every other
     * test in this file while making the history useless in a week.
     */
    @Test
    fun `re-saving an unchanged issue records nothing`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f, title = "Login is broken", labelIds = listOf(f.bugLabelId))
        val before = history.forIssue(id).size

        save(f, id, title = "Login is broken", labelIds = listOf(f.bugLabelId))

        assertEquals(before, history.forIssue(id).size, "An unchanged save invented history.")
    }

    /** One field changed is one event, not one per field the editor sent. */
    @Test
    fun `changing only the title records only a title change`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f, title = "v1", labelIds = listOf(f.bugLabelId))

        save(f, id, title = "v2", labelIds = listOf(f.bugLabelId))

        val latest = history.forIssue(id).drop(1)
        assertEquals(listOf(IssueEventKind.TITLE_CHANGED), latest.map { it.kind })
        assertEquals("v2", latest.single().value, "The new title was not stored with the event.")
    }

    /**
     * The same labels in a different order are the same labels.
     *
     * Labels are a set, and the editor gives no guarantee about ordering. Compared
     * as a list, this would record a LABELS_CHANGED every time somebody saved an
     * issue whose label chips happened to come back in another order — a change
     * nobody made, attributed to whoever saved next.
     */
    @Test
    fun `reordering the same labels records nothing`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f, labelIds = listOf(f.bugLabelId, f.featureLabelId))
        val before = history.forIssue(id).size

        save(f, id, labelIds = listOf(f.featureLabelId, f.bugLabelId))

        assertEquals(before, history.forIssue(id).size, "Reordering a set was recorded as a change.")
    }

    /** Description changes are recorded, and deliberately carry no text. */
    @Test
    fun `a description change records no body`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f)

        save(f, id, description = "Now with steps to reproduce.")

        val event = history.forIssue(id).drop(1).single()
        assertEquals(IssueEventKind.DESCRIPTION_CHANGED, event.kind)
        assertNull(event.value, "The description body was stored; see IssueEventKind.DESCRIPTION_CHANGED.")
    }

    // ── Values, and the snapshot rule ────────────────────────────────────────

    /**
     * A label event carries the whole resulting set, by name.
     *
     * The set, not a delta: a gap in the history — and the back-fill opens one by
     * design — must not corrupt every event after it.
     */
    @Test
    fun `a label change stores the whole resulting set by name`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f)

        save(f, id, labelIds = listOf(f.bugLabelId, f.featureLabelId))

        val event = history.forIssue(id).drop(1).single()
        assertEquals(IssueEventKind.LABELS_CHANGED, event.kind)
        assertEquals(listOf("Bug", "Feature"), event.values)
    }

    /** Clearing a set is a change, and reads as an empty one rather than as nothing. */
    @Test
    fun `clearing the labels is recorded`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f, labelIds = listOf(f.bugLabelId))

        save(f, id, labelIds = emptyList())

        val event = history.forIssue(id).drop(1).single()
        assertEquals(IssueEventKind.LABELS_CHANGED, event.kind)
        assertTrue(event.values.isEmpty(), "Clearing the labels stored values.")
    }

    /**
     * **A renamed label does not rewrite history.**
     *
     * The snapshot rule, and the reason `value_text` is not a foreign key. The
     * event said "Bug" because that is what the label was called when it was
     * applied; renaming it later describes the board from now on, not what
     * happened on Tuesday. See IssueEvents.sq.
     */
    @Test
    fun `renaming a label leaves the recorded event alone`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f)
        save(f, id, labelIds = listOf(f.bugLabelId))

        labels.update(f.bugLabelId, "Defect")

        assertEquals(listOf("Bug"), history.forIssue(id).drop(1).single().values)
    }

    // ── Status ───────────────────────────────────────────────────────────────

    /** A move records the column it landed in, by the name it then had. */
    @Test
    fun `a status move is recorded with the column's name`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f)
        val issue = issues.findById(id)!!
        val target = statuses.forProject(f.projectId)[1]

        issues.setStatus(id, target.id, null)
        history.recordStatusChanged(issue, target.id, Author.Account(f.userId), agentName = null)

        val event = history.forIssue(id).drop(1).single()
        assertEquals(IssueEventKind.STATUS_CHANGED, event.kind)
        assertEquals(target.name, event.value)
    }

    /**
     * Dropping a card back where it started records nothing.
     *
     * Matters most for `move_issue`: an agent re-asserting state it believes to be
     * true is routine, and each one would otherwise be a line in the history.
     */
    @Test
    fun `moving an issue to the column it is already in records nothing`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f)
        val issue = issues.findById(id)!!
        val before = history.forIssue(id).size

        history.recordStatusChanged(issue, issue.statusId, Author.Account(f.userId), agentName = null)

        assertEquals(before, history.forIssue(id).size, "A no-op move was recorded.")
    }

    // ── Assignee ─────────────────────────────────────────────────────────────

    /** Assigning records both the name and the live account link. */
    @Test
    fun `assigning records the person`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f)
        val issue = issues.findById(id)!!

        issues.setAssignee(id, f.otherId)
        history.recordAssigneeChanged(issue, f.otherId, Author.Account(f.userId), agentName = null)

        val event = history.forIssue(id).drop(1).single()
        assertEquals(IssueEventKind.ASSIGNEE_CHANGED, event.kind)
        assertEquals("Other", event.value, "The assignee's name was not snapshotted.")
        assertEquals(f.otherId, event.valueUserId, "The assignee's account link was not stored.")
    }

    /**
     * Unassigning is an event, and its null name is the discriminator.
     *
     * The pair of columns exists precisely so that this row and "assigned to
     * somebody whose account has since been deleted" do not collapse into each
     * other. Here the id is null because nobody was named; there the id is null
     * because `ON DELETE SET NULL` emptied it, and the name survives.
     */
    @Test
    fun `unassigning records a null name`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f)
        issues.setAssignee(id, f.otherId)
        val assigned = issues.findById(id)!!

        issues.setAssignee(id, null)
        history.recordAssigneeChanged(assigned, null, Author.Account(f.userId), agentName = null)

        val event = history.forIssue(id).last()
        assertEquals(IssueEventKind.ASSIGNEE_CHANGED, event.kind)
        assertNull(event.value, "Unassigning stored a name.")
        assertNull(event.valueUserId, "Unassigning stored an account.")
    }

    // ── Ordering ─────────────────────────────────────────────────────────────

    /**
     * Several changes in one save arrive as several events, in a stable order.
     *
     * They share a timestamp by construction — one clock read for the batch — so
     * `created_at` cannot order them and `id` must. A read that sorted on the
     * timestamp would shuffle these between two page loads.
     */
    @Test
    fun `several changes in one save are recorded in a stable order`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f, title = "v1")

        save(f, id, title = "v2", description = "why", labelIds = listOf(f.bugLabelId))

        val kinds = history.forIssue(id).map { it.kind }
        assertEquals(
            listOf(
                IssueEventKind.CREATED,
                IssueEventKind.TITLE_CHANGED,
                IssueEventKind.DESCRIPTION_CHANGED,
                IssueEventKind.LABELS_CHANGED,
            ),
            kinds,
        )
        val stamps = history.forIssue(id).drop(1).map { it.createdAt }.distinct()
        assertEquals(1, stamps.size, "One save produced events at two different times.")
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private class Fixture(
        val userId: Long,
        val otherId: Long,
        val projectId: Long,
        val bugLabelId: Long,
        val featureLabelId: Long,
    )

    private suspend fun seed(): Fixture {
        roles.seed()
        val user = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-1", "Robert", "robert@example.com"))
        val other = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-2", "Other", "other@example.com"))
        val project = projectRepository.create("Lunicle", "LNL", isPublic = false)
        return Fixture(
            userId = user.id,
            otherId = other.id,
            projectId = project.id,
            bugLabelId = label(project.id, "Bug"),
            featureLabelId = label(project.id, "Feature"),
        )
    }

    /**
     * The id of one of the project's seeded labels.
     *
     * Looked up rather than inserted: ProjectRepository.create seeds "Bug" and
     * "Feature" in the same transaction as the project, and `UNIQUE (project_id,
     * name)` refuses a second one. Using the seeded vocabulary is also the more
     * honest fixture — it is what every real project has.
     */
    private suspend fun label(projectId: Long, name: String): Long =
        labels.forProject(projectId).first { it.name == name }.id


    /** A draft, published — the state an edit acts on. Its id. */
    private suspend fun publish(
        f: Fixture,
        title: String = "Something",
        labelIds: List<Long> = emptyList(),
        agentName: String? = null,
    ): Long {
        val (id, _) = issueRepository.createDraft(f.projectId, Author.Account(f.userId), agentName = agentName)
        save(f, id, title = title, labelIds = labelIds, agentName = agentName)
        return id
    }

    /**
     * A save through the repository, defaulting every field to the issue's current
     * value.
     *
     * The defaults are the point: a test that says "change the title" must send
     * everything else back unchanged, because that is exactly what the editor does
     * and exactly the payload the comparison has to see through.
     */
    private suspend fun save(
        f: Fixture,
        id: Long,
        title: String? = null,
        description: String? = null,
        labelIds: List<Long>? = null,
        agentName: String? = null,
    ) {
        val issue = issues.findById(id)!!
        issueRepository.save(
            issue = issue,
            title = title ?: issue.title,
            description = description ?: issue.description,
            statusId = issue.statusId,
            priorityId = issue.priorityId,
            resolutionId = issue.resolutionId,
            assigneeId = issue.assigneeId,
            sprintId = null,
            plannedVersionId = null,
            fixedVersionId = null,
            labelIds = labelIds ?: issues.labelsFor(id),
            componentIds = issues.componentsFor(id),
            actorId = f.userId,
            actor = Author.Account(f.userId),
            agentName = agentName,
        )
    }
}
