/**
 * The Vocabulary contract, run against the SQLite reference implementation
 * ([se.soderbjorn.lunicle.VocabularyRepository]).
 *
 * The assertions live in [VocabularyStoreContract]; this file wires SQLite —
 * a temp database, the real stores over it, a project seeded with its default
 * vocabularies through [ProjectRepository], and an issue filed through
 * [IssueRepository] so the usage counts the delete refusals read are real.
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
import se.soderbjorn.lunicle.IssueStore
import se.soderbjorn.lunicle.LabelStore
import se.soderbjorn.lunicle.PriorityStore
import se.soderbjorn.lunicle.ProjectRepository
import se.soderbjorn.lunicle.ProjectStore
import se.soderbjorn.lunicle.ResolutionStore
import se.soderbjorn.lunicle.SprintStore
import se.soderbjorn.lunicle.StatusStore
import se.soderbjorn.lunicle.VocabularyRepository

class SqlDelightVocabularyStoreContractTest : VocabularyStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val projects = ProjectStore(db)
    private val labels = LabelStore(db)
    private val components = ComponentStore(db)
    private val statuses = StatusStore(db)
    private val priorities = PriorityStore(db)
    private val resolutions = ResolutionStore(db)
    private val sprints = SprintStore(db)
    private val versions = se.soderbjorn.lunicle.VersionStore(db)
    private val issues = IssueStore(db)
    private val comments = CommentStore(db)
    private val attachmentStore = AttachmentStore(db)
    private val attachmentsDir = Files.createTempDirectory("lunicle-contract-att").toFile()
    private val attachments = AttachmentRepository(attachmentStore, attachmentsDir)
    private val projectRepository = ProjectRepository(db, projects, attachments, attachmentStore)
    private val issueRepository = IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)

    private var seq = 0

    override val store: VocabularyStore =
        VocabularyRepository(db, labels, components, statuses, priorities, resolutions, sprints, versions, issues)

    override suspend fun newProject(): Long =
        projectRepository.create("Project ${seq}", "VC${seq++}", isPublic = false).id

    override suspend fun fileIssue(projectId: Long): Long {
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
