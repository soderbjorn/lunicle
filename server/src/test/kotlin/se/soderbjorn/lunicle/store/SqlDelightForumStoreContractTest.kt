/**
 * The Forum contract, run against the SQLite gateway reference implementation
 * ([se.soderbjorn.lunicle.ForumStore]).
 *
 * The assertions live in [ForumStoreContract]; this file wires SQLite — a temp
 * database, the gateway store over it, and a project seeded through the real
 * [ProjectRepository] so the forum's `project_id` foreign key is satisfied exactly
 * as it is on the volume.
 */
package se.soderbjorn.lunicle.store

import java.nio.file.Files
import kotlin.test.AfterTest
import se.soderbjorn.lunicle.AttachmentRepository
import se.soderbjorn.lunicle.AttachmentStore
import se.soderbjorn.lunicle.ProjectRepository
import se.soderbjorn.lunicle.ProjectStore

class SqlDelightForumStoreContractTest : ForumStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val projects = ProjectStore(db)
    private val attachmentStore = AttachmentStore(db)
    private val attachmentsDir = Files.createTempDirectory("lunicle-contract-att").toFile()
    private val attachments = AttachmentRepository(attachmentStore, attachmentsDir)
    private val projectRepository = ProjectRepository(db, projects, attachments, attachmentStore)

    private var seq = 0

    override val store: ForumStore = se.soderbjorn.lunicle.ForumStore(db)

    override suspend fun newProject(): Long =
        projectRepository.create("Project ${seq}", "FM${seq++}").id

    @AfterTest
    fun tearDown() {
        fixture.close()
        attachmentsDir.deleteRecursively()
    }
}
