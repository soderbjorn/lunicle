/**
 * The ProjectProvisioning contract, run against the SQLite reference
 * implementation ([se.soderbjorn.lunicle.ProjectRepository]).
 *
 * The assertions live in [ProjectProvisioningContract]; this file wires SQLite — a
 * temp database, the real stores over it, provisioning through [ProjectRepository]
 * (which seeds the six tables in one transaction), and the seeded board read back
 * through the concrete gateway stores so the read-backs are exactly the board's.
 */
package se.soderbjorn.lunicle.store

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.AttachmentRepository
import se.soderbjorn.lunicle.AttachmentStore
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.CommentStore
import se.soderbjorn.lunicle.ComponentStore
import se.soderbjorn.lunicle.ForumPostStore
import se.soderbjorn.lunicle.ForumStore
import se.soderbjorn.lunicle.IssueEventStore
import se.soderbjorn.lunicle.IssueStore
import se.soderbjorn.lunicle.LabelStore
import se.soderbjorn.lunicle.NewIssueEvent
import se.soderbjorn.lunicle.PriorityStore
import se.soderbjorn.lunicle.ProjectRecord
import se.soderbjorn.lunicle.ProjectRepository
import se.soderbjorn.lunicle.ProjectStore
import se.soderbjorn.lunicle.ProviderIdentity
import se.soderbjorn.lunicle.ResolutionStore
import se.soderbjorn.lunicle.RoleStore
import se.soderbjorn.lunicle.StatusRecord
import se.soderbjorn.lunicle.StatusStore
import se.soderbjorn.lunicle.SubscriptionStore
import se.soderbjorn.lunicle.UserStore
import se.soderbjorn.lunicle.VocabularyRecord
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.IssueEventKind
import se.soderbjorn.lunicle.ProjectRole

class SqlDelightProjectProvisioningContractTest : ProjectProvisioningContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val projects = ProjectStore(db)
    private val labels = LabelStore(db)
    private val components = ComponentStore(db)
    private val statuses = StatusStore(db)
    private val priorities = PriorityStore(db)
    private val resolutions = ResolutionStore(db)
    private val attachmentStore = AttachmentStore(db)
    private val attachmentsDir = Files.createTempDirectory("lunicle-prov-att").toFile()
    private val attachments = AttachmentRepository(attachmentStore, attachmentsDir)

    // The stores the cascade seed writes through and the assertions read back.
    private val users = UserStore(db)
    private val issues = IssueStore(db)
    private val comments = CommentStore(db)
    private val events = IssueEventStore(db)
    private val subscriptions = SubscriptionStore(db)
    private val roles = RoleStore(db)
    private val forums = ForumStore(db)
    private val posts = ForumPostStore(db)

    override val provisioning: ProjectProvisioning = ProjectRepository(db, projects, attachments, attachmentStore)

    override suspend fun statusesOf(projectId: Long): List<StatusRecord> = statuses.forProject(projectId)
    override suspend fun prioritiesOf(projectId: Long): List<StatusRecord> = priorities.forProject(projectId)
    override suspend fun resolutionsOf(projectId: Long): List<StatusRecord> = resolutions.forProject(projectId)
    override suspend fun labelsOf(projectId: Long): List<VocabularyRecord> = labels.forProject(projectId)
    override suspend fun componentsOf(projectId: Long): List<VocabularyRecord> = components.forProject(projectId)
    override suspend fun projectById(id: Long): ProjectRecord? = projects.findById(id)
    override suspend fun allProjects(): List<ProjectRecord> = projects.selectAll()

    // ── The delete cascade (LNL-177) ─────────────────────────────────────────
    //
    // The seed goes in through the real stores, so every foreign key is satisfied
    // exactly as it is on the volume — which is the point of running this contract
    // here at all: SQLite is the reference for what the cascade must take.

    private var seq = 0

    override suspend fun seedContents(projectId: Long): SeededContents {
        val n = seq++
        val status = statuses.forProject(projectId).first()
        val priority = priorities.forProject(projectId).first()
        val user = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "cascade-$n", "User $n", "cascade-$n@example.com"),
        ).id

        val (issueId, _) = issues.insertDraft(projectId, "Issue $n", status.id, priority.id, Author.Nobody)
        issues.publish(
            id = issueId, title = "Issue $n", description = "", statusId = status.id, priorityId = priority.id,
            resolutionId = null, assigneeId = null, sprintId = null, plannedVersionId = null, fixedVersionId = null,
        )
        val commentId = comments.insertDraft(issueId, Author.Nobody)
        comments.publish(commentId, "a comment")
        events.append(
            issueId,
            listOf(NewIssueEvent(IssueEventKind.STATUS_CHANGED, value = status.name)),
            author = Author.Nobody,
        )
        subscriptions.setIssueUpdateSubscription(user, issueId, true)
        subscriptions.setProjectNewIssueSubscription(user, projectId, true)
        roles.setRole(user, projectId, ProjectRole.CONTRIBUTOR)
        attachmentStore.insertForIssue(
            issueId = issueId, filename = "shot-$n.png", mimeType = "image/png", byteSize = 1,
            storageKey = "cascade-key-$n", publicId = "cascade-pub-$n", author = Author.Nobody,
        )

        val forum = forums.insert(projectId, "Forum $n", null)
        val postId = posts.insertDraft(forum.id, Author.Nobody)
        posts.publish(postId, "Post $n", "")

        return SeededContents(issueId = issueId, forumId = forum.id)
    }

    override suspend fun issueExists(id: Long): Boolean = issues.findById(id) != null
    override suspend fun commentCountOf(issueId: Long): Int = comments.forIssue(issueId).size
    override suspend fun historyCountOf(issueId: Long): Int = events.forIssue(issueId).size
    override suspend fun issueWatcherCountOf(issueId: Long): Int = subscriptions.watchersForIssue(issueId).size
    override suspend fun forumExists(id: Long): Boolean = forums.findById(id) != null
    override suspend fun postCountOf(forumId: Long): Int = posts.forForum(forumId).size
    override suspend fun attachmentCountOf(projectId: Long): Int = attachmentStore.keysForProject(projectId).size
    override suspend fun roleGrantCountOf(projectId: Long): Int = roles.rolesForProject(projectId).size

    override suspend fun projectWatcherCountOf(projectId: Long): Int =
        subscriptions.audienceForProjectNewIssue(projectId, actorId = null).size

    @AfterTest
    fun tearDown() {
        fixture.close()
        attachmentsDir.deleteRecursively()
    }
}
