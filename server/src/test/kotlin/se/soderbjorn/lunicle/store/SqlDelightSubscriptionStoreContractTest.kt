/**
 * The Subscription contract, run against the SQLite reference implementation.
 *
 * The assertions live in [SubscriptionStoreContract]; this file wires SQLite to
 * them — a temp database, real stores over it, and the same repository machinery
 * production uses to seed the users, project and issue a subscription points at,
 * so every foreign key is satisfied exactly as it is on the volume.
 */
package se.soderbjorn.lunicle.store

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import se.soderbjorn.lunicle.AttachmentRepository
import se.soderbjorn.lunicle.AttachmentStore
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.CommentStore
import se.soderbjorn.lunicle.IssueRepository
import se.soderbjorn.lunicle.IssueStore
import se.soderbjorn.lunicle.PriorityStore
import se.soderbjorn.lunicle.ProjectRepository
import se.soderbjorn.lunicle.ProjectStore
import se.soderbjorn.lunicle.ProviderIdentity
import se.soderbjorn.lunicle.StatusStore
import se.soderbjorn.lunicle.UserStore
import se.soderbjorn.lunicle.clientserver.AuthProvider

class SqlDelightSubscriptionStoreContractTest : SubscriptionStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val users = UserStore(db)
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

    override val store: SubscriptionStore = se.soderbjorn.lunicle.SubscriptionStore(db)

    override suspend fun newUser(email: String?): Long {
        val n = seq++
        return users.upsert(ProviderIdentity(AuthProvider.GITHUB, "sub-$n", "User $n", email)).id
    }

    override suspend fun newProject(): Long = projectRepository.create("Project ${seq}", "SB${seq++}", isPublic = false).id

    override suspend fun newPublishedIssue(projectId: Long): Long {
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
