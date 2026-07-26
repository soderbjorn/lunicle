/**
 * The Attachment-metadata contract, run against the SQLite gateway reference
 * implementation ([se.soderbjorn.lunicle.AttachmentStore]).
 *
 * The (project, issue) an attachment hangs off is seeded through the real
 * repositories so the foreign key is satisfied exactly as in production.
 */
package se.soderbjorn.lunicle.store

import java.nio.file.Files
import kotlin.test.AfterTest
import se.soderbjorn.lunicle.AttachmentRepository
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.CommentStore
import se.soderbjorn.lunicle.IssueRepository
import se.soderbjorn.lunicle.IssueStore
import se.soderbjorn.lunicle.PriorityStore
import se.soderbjorn.lunicle.ProjectRepository
import se.soderbjorn.lunicle.ProjectStore
import se.soderbjorn.lunicle.StatusStore

class SqlDelightAttachmentStoreContractTest : AttachmentStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val projects = ProjectStore(db)
    private val statuses = StatusStore(db)
    private val priorities = PriorityStore(db)
    private val issues = IssueStore(db)
    private val comments = CommentStore(db)
    private val attachmentStore = se.soderbjorn.lunicle.AttachmentStore(db)
    private val attachmentsDir = Files.createTempDirectory("lunicle-contract-att").toFile()
    private val attachments = AttachmentRepository(attachmentStore, attachmentsDir)
    private val projectRepository = ProjectRepository(db, projects, attachments, attachmentStore)
    private val issueRepository = IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)

    private var seq = 0

    override val store: AttachmentStore = attachmentStore

    override suspend fun newIssue(): Pair<Long, Long> {
        val project = projectRepository.create("Project ${seq}", "AT${seq++}", isPublic = false)
        val (issueId, _) = issueRepository.createDraft(project.id, Author.Nobody)
        val issue = issues.findById(issueId)!!
        issueRepository.save(
            issue = issue,
            title = "Issue $issueId",
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
        return project.id to issueId
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
        attachmentsDir.deleteRecursively()
    }
}
