/**
 * The ProjectProvisioning contract, run against the **Firestore** implementation on
 * the emulator — the mirror of [SqlDelightProjectProvisioningContractTest].
 *
 * Same assertions ([ProjectProvisioningContract]), different backend. If the
 * emulator is not configured for this run (no `-Dlunicle.firestoreEmulatorHost=…`),
 * every test skips rather than fails, so the SQLite suite is unaffected.
 *
 * This is the test that pins LNL-131's atomic seed: [se.soderbjorn.lunicle.FirestoreProjectRepository.create]
 * writes the project document and its whole default board in one transaction, and
 * these read the board back through the concrete Firestore board stores — the exact
 * documents the board serves. No synthetic ids: `create` allocates a real project id
 * and vocabulary block from the shared counters, exactly as production does.
 */
package se.soderbjorn.lunicle.store

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.AttachmentRepository
import se.soderbjorn.lunicle.AttachmentScope
import se.soderbjorn.lunicle.AttachmentScopeResolver
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.FirestoreAttachmentStore
import se.soderbjorn.lunicle.FirestoreCommentStore
import se.soderbjorn.lunicle.FirestoreComponentStore
import se.soderbjorn.lunicle.FirestoreForumPostStore
import se.soderbjorn.lunicle.FirestoreForumStore
import se.soderbjorn.lunicle.FirestoreIssueEventStore
import se.soderbjorn.lunicle.FirestoreIssueStore
import se.soderbjorn.lunicle.FirestoreLabelStore
import se.soderbjorn.lunicle.FirestorePriorityStore
import se.soderbjorn.lunicle.FirestoreProjectRepository
import se.soderbjorn.lunicle.FirestoreProjectStore
import se.soderbjorn.lunicle.FirestoreResolutionStore
import se.soderbjorn.lunicle.FirestoreRoleStore
import se.soderbjorn.lunicle.FirestoreStatusStore
import se.soderbjorn.lunicle.FirestoreSubscriptionStore
import se.soderbjorn.lunicle.NewIssueEvent
import se.soderbjorn.lunicle.ProjectRecord
import se.soderbjorn.lunicle.Role
import se.soderbjorn.lunicle.StatusRecord
import se.soderbjorn.lunicle.VocabularyRecord
import se.soderbjorn.lunicle.clientserver.IssueEventKind

class FirestoreProjectProvisioningContractTest : ProjectProvisioningContract() {
    private val fixture = FirestoreContractFixture()
    private val attachmentsDir = Files.createTempDirectory("lunicle-prov-att-fs").toFile()

    private val projects by lazy { FirestoreProjectStore(fixture.firestore) }
    private val statuses by lazy { FirestoreStatusStore(fixture.firestore) }
    private val priorities by lazy { FirestorePriorityStore(fixture.firestore) }
    private val resolutions by lazy { FirestoreResolutionStore(fixture.firestore) }
    private val labels by lazy { FirestoreLabelStore(fixture.firestore) }
    private val components by lazy { FirestoreComponentStore(fixture.firestore) }

    /**
     * The real ancestry of an issue the cascade seed filed an attachment against.
     *
     * [FirestoreAttachmentStore] denormalises the scope onto the document at insert
     * time, and the project cascade then keys on `scopeProjectId`. A resolver that
     * answered `forIssue` with the issue alone would write that field null, and every
     * attachment assertion would pass whether or not the cascade worked — so the seed
     * records the true scope here and this resolver hands it back.
     */
    private val scopeOverrides = mutableMapOf<Long, AttachmentScope>()

    // Delete collects attachment keys by project before dropping the rows; where the
    // seed filed nothing, a trivial scope resolver suffices.
    private val scopeResolver = object : AttachmentScopeResolver {
        override suspend fun forIssue(issueId: Long) =
            scopeOverrides[issueId] ?: AttachmentScope(issueId = issueId)
        override suspend fun forComment(commentId: Long) = AttachmentScope()
        override suspend fun forForumPost(forumPostId: Long) = AttachmentScope(postId = forumPostId)
        override suspend fun forForumComment(forumCommentId: Long) = AttachmentScope()
        override suspend fun forMessage(messageId: Long) = AttachmentScope()
    }
    private val attachmentStore by lazy { FirestoreAttachmentStore(fixture.firestore, scopeResolver) }
    private val attachments by lazy { AttachmentRepository(attachmentStore, attachmentsDir) }

    override val provisioning: ProjectProvisioning by lazy {
        FirestoreProjectRepository(fixture.firestore, projects, attachments, attachmentStore)
    }

    override suspend fun statusesOf(projectId: Long): List<StatusRecord> = statuses.forProject(projectId)
    override suspend fun prioritiesOf(projectId: Long): List<StatusRecord> = priorities.forProject(projectId)
    override suspend fun resolutionsOf(projectId: Long): List<StatusRecord> = resolutions.forProject(projectId)
    override suspend fun labelsOf(projectId: Long): List<VocabularyRecord> = labels.forProject(projectId)
    override suspend fun componentsOf(projectId: Long): List<VocabularyRecord> = components.forProject(projectId)
    override suspend fun projectById(id: Long): ProjectRecord? = projects.findById(id)
    override suspend fun allProjects(): List<ProjectRecord> = projects.selectAll()

    // ── The delete cascade (LNL-177) ─────────────────────────────────────────
    //
    // The half of this contract that was previously absent, and the reason it was:
    // this backend had no cascade at all, so a deleted project left every one of the
    // collections seeded below behind for good. The seed writes through the real
    // Firestore stores and the assertions read back through them, so what is counted
    // is what the board would actually serve.

    private val issues by lazy { FirestoreIssueStore(fixture.firestore) }
    private val comments by lazy { FirestoreCommentStore(fixture.firestore) }
    private val events by lazy { FirestoreIssueEventStore(fixture.firestore) }
    private val forums by lazy { FirestoreForumStore(fixture.firestore) }
    private val posts by lazy { FirestoreForumPostStore(fixture.firestore) }
    private val roles by lazy { FirestoreRoleStore(fixture.firestore) }

    // The audience seam resolves subscribers to names; the cascade assertions only
    // count them, so a contact per seeded user is all this needs.
    private val contacts = mutableMapOf<Long, FirestoreSubscriptionStore.Contact>()
    private val subscriptions by lazy {
        FirestoreSubscriptionStore(
            fixture.firestore,
            resolveContacts = { ids -> ids.mapNotNull { id -> contacts[id]?.let { id to it } }.toMap() },
        )
    }

    private var seq = 0

    override suspend fun seedContents(projectId: Long): SeededContents {
        val n = seq++
        val status = statuses.forProject(projectId).first()
        val priority = priorities.forProject(projectId).first()
        val user = 7_000L + n
        contacts[user] = FirestoreSubscriptionStore.Contact("User $n", "cascade-$n@example.com")

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
        roles.grant(user, projectId, Role.CREATE_ISSUE)
        // The scope resolver above answers forIssue with issueId only, so this row
        // would carry no scopeProjectId — and the project cascade reads exactly that
        // field. Resolving the real scope here is what makes the attachment assertion
        // mean something rather than pass vacuously.
        scopeOverrides[issueId] = AttachmentScope(projectId = projectId, issueId = issueId)
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
    override suspend fun roleGrantCountOf(projectId: Long): Int = roles.grantsForProject(projectId).size

    override suspend fun projectWatcherCountOf(projectId: Long): Int =
        subscriptions.audienceForProjectNewIssue(projectId, actorId = null).size

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() {
        fixture.close()
        attachmentsDir.deleteRecursively()
    }
}
