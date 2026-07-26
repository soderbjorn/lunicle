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
import se.soderbjorn.lunicle.AttachmentRepository
import se.soderbjorn.lunicle.AttachmentStore
import se.soderbjorn.lunicle.ComponentStore
import se.soderbjorn.lunicle.LabelStore
import se.soderbjorn.lunicle.PriorityStore
import se.soderbjorn.lunicle.ProjectRecord
import se.soderbjorn.lunicle.ProjectRepository
import se.soderbjorn.lunicle.ProjectStore
import se.soderbjorn.lunicle.ResolutionStore
import se.soderbjorn.lunicle.StatusRecord
import se.soderbjorn.lunicle.StatusStore
import se.soderbjorn.lunicle.VocabularyRecord

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

    override val provisioning: ProjectProvisioning = ProjectRepository(db, projects, attachments, attachmentStore)

    override suspend fun statusesOf(projectId: Long): List<StatusRecord> = statuses.forProject(projectId)
    override suspend fun prioritiesOf(projectId: Long): List<StatusRecord> = priorities.forProject(projectId)
    override suspend fun resolutionsOf(projectId: Long): List<StatusRecord> = resolutions.forProject(projectId)
    override suspend fun labelsOf(projectId: Long): List<VocabularyRecord> = labels.forProject(projectId)
    override suspend fun componentsOf(projectId: Long): List<VocabularyRecord> = components.forProject(projectId)
    override suspend fun projectById(id: Long): ProjectRecord? = projects.findById(id)
    override suspend fun allProjects(): List<ProjectRecord> = projects.selectAll()

    @AfterTest
    fun tearDown() {
        fixture.close()
        attachmentsDir.deleteRecursively()
    }
}
