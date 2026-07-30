/**
 * The guest-audience ceiling, over the SQLite reference stores (LNL-202).
 *
 * The assertions live in [GuestAudienceCeilingContract]; this file wires the backend — a
 * temp database, the real role and instance-settings stores over it, and real projects
 * through [se.soderbjorn.lunicle.ProjectRepository] so that `project_audience_roles`'
 * foreign key to `projects` is satisfied exactly as it is on the volume.
 *
 * The concrete gateways share their simple names with the interfaces in this package, so
 * they are constructed by their fully-qualified names.
 */
package se.soderbjorn.lunicle.store

import java.nio.file.Files
import kotlin.test.AfterTest
import se.soderbjorn.lunicle.AttachmentRepository
import se.soderbjorn.lunicle.AttachmentStore
import se.soderbjorn.lunicle.ProjectRepository
import se.soderbjorn.lunicle.ProjectStore
import se.soderbjorn.lunicle.ProviderIdentity
import se.soderbjorn.lunicle.UserRecord
import se.soderbjorn.lunicle.UserStore
import se.soderbjorn.lunicle.clientserver.AuthProvider

class SqlDelightGuestAudienceCeilingContractTest : GuestAudienceCeilingContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val users = UserStore(db)
    private val projects = ProjectStore(db)
    private val attachmentStore = AttachmentStore(db)
    private val attachmentsDir = Files.createTempDirectory("lunicle-ceiling-att").toFile()
    private val attachments = AttachmentRepository(attachmentStore, attachmentsDir)
    private val projectRepository = ProjectRepository(db, projects, attachments, attachmentStore)

    private var seq = 0
    private var accounts = 0

    override val roles: RoleStore = se.soderbjorn.lunicle.RoleStore(db)
    override val instanceSettings: InstanceSettingsStore = se.soderbjorn.lunicle.InstanceSettingsStore(db)

    override suspend fun newProject(): Long = projectRepository.create("Project $seq", "GC${seq++}").id

    /**
     * A real `users` row, because `project_roles` has a foreign key to one.
     *
     * The **second** account onwards, always: `UserStore.upsert` makes the first account on
     * a fresh instance an administrator, and an administrator reaches Owner on every
     * project without a row — which would make every rung assertion here vacuous. So one
     * throwaway is minted first and never used.
     */
    override suspend fun newAccount(): UserRecord {
        if (accounts == 0) {
            accounts++
            users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-first", "First", "first@example.com"))
        }
        val n = accounts++
        return users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-$n", "User $n", "user$n@example.com"))
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
        attachmentsDir.deleteRecursively()
    }
}
