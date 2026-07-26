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
import se.soderbjorn.lunicle.FirestoreAttachmentStore
import se.soderbjorn.lunicle.FirestoreComponentStore
import se.soderbjorn.lunicle.FirestoreLabelStore
import se.soderbjorn.lunicle.FirestorePriorityStore
import se.soderbjorn.lunicle.FirestoreProjectRepository
import se.soderbjorn.lunicle.FirestoreProjectStore
import se.soderbjorn.lunicle.FirestoreResolutionStore
import se.soderbjorn.lunicle.FirestoreStatusStore
import se.soderbjorn.lunicle.ProjectRecord
import se.soderbjorn.lunicle.StatusRecord
import se.soderbjorn.lunicle.VocabularyRecord

class FirestoreProjectProvisioningContractTest : ProjectProvisioningContract() {
    private val fixture = FirestoreContractFixture()
    private val attachmentsDir = Files.createTempDirectory("lunicle-prov-att-fs").toFile()

    private val projects by lazy { FirestoreProjectStore(fixture.firestore) }
    private val statuses by lazy { FirestoreStatusStore(fixture.firestore) }
    private val priorities by lazy { FirestorePriorityStore(fixture.firestore) }
    private val resolutions by lazy { FirestoreResolutionStore(fixture.firestore) }
    private val labels by lazy { FirestoreLabelStore(fixture.firestore) }
    private val components by lazy { FirestoreComponentStore(fixture.firestore) }

    // Delete collects attachment keys by project before dropping the rows; with no
    // attachments filed this resolves nothing, so a trivial scope resolver suffices.
    private val scopeResolver = object : AttachmentScopeResolver {
        override suspend fun forIssue(issueId: Long) = AttachmentScope(issueId = issueId)
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

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() {
        fixture.close()
        attachmentsDir.deleteRecursively()
    }
}
