/**
 * The Comment contract, run against the SQLite reference implementation
 * ([se.soderbjorn.lunicle.CommentStore]).
 *
 * The assertions live in [CommentStoreContract]; this file wires SQLite — a temp
 * database, the real stores over it, and a real issue filed through
 * [IssueRepository] so a comment's `issue_id` foreign key is satisfied the way
 * production satisfies it.
 *
 * The concrete gateway shares its simple name with the [CommentStore] interface, so
 * it is constructed by its fully-qualified name; `store` and every other reference
 * in this package is the interface.
 */
package se.soderbjorn.lunicle.store

import java.nio.file.Files
import kotlin.test.AfterTest
import se.soderbjorn.lunicle.AttachmentRepository
import se.soderbjorn.lunicle.AttachmentStore
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.IssueRepository
import se.soderbjorn.lunicle.PriorityStore
import se.soderbjorn.lunicle.ProjectRepository
import se.soderbjorn.lunicle.ProjectStore
import se.soderbjorn.lunicle.StatusStore

class SqlDelightCommentStoreContractTest : CommentStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val projects = ProjectStore(db)
    private val statuses = StatusStore(db)
    private val priorities = PriorityStore(db)
    private val issues = se.soderbjorn.lunicle.IssueStore(db)
    private val commentStore = se.soderbjorn.lunicle.CommentStore(db)
    private val attachmentStore = AttachmentStore(db)
    private val attachmentsDir = Files.createTempDirectory("lunicle-contract-att").toFile()
    private val attachments = AttachmentRepository(attachmentStore, attachmentsDir)
    private val projectRepository = ProjectRepository(db, projects, attachments, attachmentStore)
    private val issueRepository = IssueRepository(issues, commentStore, statuses, priorities, attachments, attachmentStore)

    private var seq = 0

    override val store: CommentStore = commentStore

    override suspend fun newIssue(): Long {
        val projectId = projectRepository.create("Project $seq", "CS${seq++}", isPublic = false).id
        val (id, _) = issueRepository.createDraft(projectId, Author.Nobody)
        val issue = issues.findById(id)!!
        issueRepository.save(
            issue = issue,
            title = "Issue $id",
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

    @AfterTest
    fun tearDown() {
        fixture.close()
        attachmentsDir.deleteRecursively()
    }
}
