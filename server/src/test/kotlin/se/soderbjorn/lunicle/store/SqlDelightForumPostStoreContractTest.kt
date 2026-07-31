/**
 * The Forum-post contract, run against the SQLite gateway reference implementation
 * ([se.soderbjorn.lunicle.ForumPostStore]).
 *
 * The assertions live in [ForumPostStoreContract]; this file wires SQLite — a temp
 * database, the gateway stores over it, and a project (through the real
 * [ProjectRepository]) with a forum on it (through the gateway [ForumStore]) so
 * each post's `forum_id` foreign key is satisfied exactly as it is on the volume.
 */
package se.soderbjorn.lunicle.store

import java.nio.file.Files
import kotlin.test.AfterTest
import se.soderbjorn.lunicle.AttachmentRepository
import se.soderbjorn.lunicle.AttachmentStore
import se.soderbjorn.lunicle.ProjectRepository
import se.soderbjorn.lunicle.ProjectStore

class SqlDelightForumPostStoreContractTest : ForumPostStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val projects = ProjectStore(db)
    private val forums = se.soderbjorn.lunicle.ForumStore(db)
    private val attachmentStore = AttachmentStore(db)
    private val attachmentsDir = Files.createTempDirectory("lunicle-contract-att").toFile()
    private val attachments = AttachmentRepository(attachmentStore, attachmentsDir)
    private val projectRepository = ProjectRepository(db, projects, attachments, attachmentStore)

    private var seq = 0

    override val store: ForumPostStore = se.soderbjorn.lunicle.ForumPostStore(db)

    override suspend fun newForum(): Long {
        val n = seq++
        val project = projectRepository.create("Project ${n}", "FP${n}").id
        return forums.insert(project, "Forum ${n}", null).id
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
        attachmentsDir.deleteRecursively()
    }
}
