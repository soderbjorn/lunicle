/**
 * The Statistics contract, run against the SQLite reference implementation
 * ([se.soderbjorn.lunicle.StatisticsRepository]).
 *
 * The store is built with its default GitHub client, which is never constructed
 * into a call: no repository is linked, so commit counting short-circuits to
 * Unavailable before it. Issues are filed through the real repository so the
 * IssueStatistics queries count them exactly as in production.
 */
package se.soderbjorn.lunicle.store

import java.nio.file.Files
import kotlin.test.AfterTest
import se.soderbjorn.lunicle.AttachmentRepository
import se.soderbjorn.lunicle.AttachmentStore
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.CommentStore
import se.soderbjorn.lunicle.IssueRepository
import se.soderbjorn.lunicle.IssueStatisticsStore
import se.soderbjorn.lunicle.IssueStore
import se.soderbjorn.lunicle.PriorityStore
import se.soderbjorn.lunicle.ProjectRepository
import se.soderbjorn.lunicle.ProjectStatisticsStore
import se.soderbjorn.lunicle.ProjectStore
import se.soderbjorn.lunicle.StatisticsRepository
import se.soderbjorn.lunicle.StatusStore

class SqlDelightStatisticsStoreContractTest : StatisticsStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val projects = ProjectStore(db)
    private val statuses = StatusStore(db)
    private val priorities = PriorityStore(db)
    private val issues = IssueStore(db)
    private val comments = CommentStore(db)
    private val attachmentStore = AttachmentStore(db)
    private val attachmentsDir = Files.createTempDirectory("lunicle-contract-att").toFile()
    private val attachments = AttachmentRepository(attachmentStore, attachmentsDir)
    private val projectRepository = ProjectRepository(db, projects, attachments, attachmentStore)
    private val issueRepository = IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)

    private var seq = 0

    override val store: StatisticsStore =
        StatisticsRepository(projects, ProjectStatisticsStore(db), IssueStatisticsStore(db))

    override suspend fun newProject(): Long =
        projectRepository.create("Project ${seq}", "ST${seq++}").id

    override suspend fun fileIssue(projectId: Long) {
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
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
        attachmentsDir.deleteRecursively()
    }
}
