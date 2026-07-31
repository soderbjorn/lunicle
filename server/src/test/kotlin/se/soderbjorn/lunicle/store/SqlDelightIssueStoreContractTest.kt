/**
 * The Issue contract, run against the SQLite reference implementation
 * ([se.soderbjorn.lunicle.IssueStore]).
 *
 * The assertions live in [IssueStoreContract]; this file wires SQLite — a temp
 * database, the real stores over it, a project seeded with its default board
 * columns through [ProjectRepository], two labels/components and a sprint added
 * over the vocabulary stores, and issues filed through [IssueRepository] so the
 * board reads and usage counts are the ones production would produce.
 *
 * The concrete gateway shares its simple name with the [IssueStore] interface, so
 * it is constructed by its fully-qualified name; `store` and every other
 * reference in this package is the interface.
 */
package se.soderbjorn.lunicle.store

import java.nio.file.Files
import kotlin.test.AfterTest
import se.soderbjorn.lunicle.AttachmentRepository
import se.soderbjorn.lunicle.AttachmentStore
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.CommentStore
import se.soderbjorn.lunicle.ComponentStore
import se.soderbjorn.lunicle.IssueRepository
import se.soderbjorn.lunicle.LabelStore
import se.soderbjorn.lunicle.PriorityStore
import se.soderbjorn.lunicle.ProjectRepository
import se.soderbjorn.lunicle.ProjectStore
import se.soderbjorn.lunicle.SprintStore
import se.soderbjorn.lunicle.StatusStore

class SqlDelightIssueStoreContractTest : IssueStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val projects = ProjectStore(db)
    private val labels = LabelStore(db)
    private val components = ComponentStore(db)
    private val statuses = StatusStore(db)
    private val priorities = PriorityStore(db)
    private val sprints = SprintStore(db)
    private val issues = se.soderbjorn.lunicle.IssueStore(db)
    private val comments = CommentStore(db)
    private val attachmentStore = AttachmentStore(db)
    private val attachmentsDir = Files.createTempDirectory("lunicle-contract-att").toFile()
    private val attachments = AttachmentRepository(attachmentStore, attachmentsDir)
    private val projectRepository = ProjectRepository(db, projects, attachments, attachmentStore)
    private val issueRepository = IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)

    private var seq = 0

    override val store: IssueStore = issues

    override suspend fun newProject(): Seeded {
        val projectId = projectRepository.create("Project $seq", "IS${seq++}").id
        val statusIds = statuses.forProject(projectId).map { it.id }
        val priorityId = priorities.defaultForProject(projectId)!!.id

        val labelBase = labels.forProject(projectId).size.toLong()
        labels.insert(projectId, "Label A", labelBase)
        labels.insert(projectId, "Label B", labelBase + 1)
        val labelIds = labels.forProject(projectId)
            .filter { it.name == "Label A" || it.name == "Label B" }.map { it.id }

        val componentBase = components.forProject(projectId).size.toLong()
        components.insert(projectId, "Component A", componentBase)
        components.insert(projectId, "Component B", componentBase + 1)
        val componentIds = components.forProject(projectId)
            .filter { it.name == "Component A" || it.name == "Component B" }.map { it.id }

        sprints.insert(projectId, "Sprint 1", 0)
        val sprintId = sprints.forProject(projectId).first().id

        return Seeded(projectId, statusIds, priorityId, labelIds, componentIds, sprintId)
    }

    override suspend fun fileIssue(project: Seeded, statusId: Long): Long {
        val (id, _) = issueRepository.createDraft(project.projectId, Author.Nobody)
        val issue = issues.findById(id)!!
        issueRepository.save(
            issue = issue,
            title = "Issue $id",
            description = "",
            statusId = statusId,
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

    override suspend fun createDraft(project: Seeded): Long =
        issueRepository.createDraft(project.projectId, Author.Nobody).first

    @AfterTest
    fun tearDown() {
        fixture.close()
        attachmentsDir.deleteRecursively()
    }
}
