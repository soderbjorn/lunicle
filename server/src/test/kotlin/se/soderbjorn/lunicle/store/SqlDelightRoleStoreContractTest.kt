/**
 * The Role contract, run against the SQLite reference implementation
 * ([se.soderbjorn.lunicle.RoleStore]).
 *
 * The assertions live in [RoleStoreContract]; this file wires SQLite — a temp
 * database, the real role store over it, and real users and projects through
 * [UserStore] and [ProjectRepository] so that `project_roles`' foreign keys to
 * `users` and `projects` are satisfied exactly as they are on the volume.
 *
 * The concrete gateway shares its simple name with the [RoleStore] interface, so it
 * is constructed by its fully-qualified name.
 */
package se.soderbjorn.lunicle.store

import java.nio.file.Files
import kotlin.test.AfterTest
import se.soderbjorn.lunicle.AttachmentRepository
import se.soderbjorn.lunicle.AttachmentStore
import se.soderbjorn.lunicle.ProjectRepository
import se.soderbjorn.lunicle.ProjectStore
import se.soderbjorn.lunicle.ProviderIdentity
import se.soderbjorn.lunicle.UserStore
import se.soderbjorn.lunicle.clientserver.AuthProvider

class SqlDelightRoleStoreContractTest : RoleStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val users = UserStore(db)
    private val projects = ProjectStore(db)
    private val attachmentStore = AttachmentStore(db)
    private val attachmentsDir = Files.createTempDirectory("lunicle-contract-att").toFile()
    private val attachments = AttachmentRepository(attachmentStore, attachmentsDir)
    private val projectRepository = ProjectRepository(db, projects, attachments, attachmentStore)

    private var seq = 0

    override val store: RoleStore = se.soderbjorn.lunicle.RoleStore(db)

    override suspend fun newUser(): Long {
        val n = seq++
        return users.upsert(ProviderIdentity(AuthProvider.GITHUB, "sub-$n", "User $n", "user$n@example.com")).id
    }

    override suspend fun newProject(): Long =
        projectRepository.create("Project $seq", "RL${seq++}", isPublic = false).id

    @AfterTest
    fun tearDown() {
        fixture.close()
        attachmentsDir.deleteRecursively()
    }
}
