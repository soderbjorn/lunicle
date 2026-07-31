/**
 * The forum and private-message checks answer no to everybody, and it is because
 * the **feature is off** — not because the rung is unknown.
 *
 * ── Why this file exists where four test files used to ──────────────────────
 *
 * LNL-190 retired discussions and private messages: it took the tabs away, made
 * every project report both features off, and deliberately left [AccessControl]'s
 * seven forum and message checks alone so that LNL-191 could answer them properly.
 * LNL-191 answers them `false`.
 *
 * That made ForumPostTest, ForumWatchTest, McpForumTest and MessageTest suites of
 * assertions about behaviour the server no longer has — every one of them opened by
 * posting something, and posting is refused. They were removed rather than left
 * failing or quietly `@Ignore`d, and this file is what stands in their place: not a
 * smaller version of them, but the one claim that is actually true now.
 *
 * It is worth having as a test rather than only as a comment for two reasons. It
 * fails the moment somebody re-enables one of these by editing [AccessControl]
 * without meaning to — a plausible edit, since the neighbouring rules all *do*
 * compute something. And it is the list a person re-enabling the features on
 * purpose should read first: seven checks, and each of them is a decision to
 * re-make against the rung ladder rather than a line to uncomment.
 *
 * The routes, the stores and the MCP tool definitions are all still there,
 * untouched. It is only the permission that is gone.
 */
package se.soderbjorn.lunicle

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AuthProvider

class RetiredFeatureAccessTest {
    private val file: File = Files.createTempFile("lunicle-retired", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)
    private val roles = RoleStore(database)
    private val projects = ProjectStore(database)
    private val attachmentStore = AttachmentStore(database)
    private val attachments =
        AttachmentRepository(attachmentStore, File(file.parentFile, "attachments-${file.name}"))
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
    private val instanceSettings = InstanceSettingsStore(database)
    private val access = AccessControl(roles, instanceSettings)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    /**
     * Every one of the seven, asked of the most privileged caller there is.
     *
     * The instance owner is deliberately the subject: they hold
     * [ProjectRole.OWNER] on every board and every other instance-scoped power, so
     * a refusal here cannot be mistaken for a missing grant. There is nothing to
     * grant.
     */
    @Test
    fun `every retired check refuses even the instance owner`(): Unit = runBlocking {
        val owner = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-owner", "Owner", "owner@example.com"))
        seatInstanceOwner(users, instanceSettings)
        val project = projectRepository.create("Lunamux", "LMX")
        roles.setRole(owner.id, project.id, ProjectRole.OWNER)
        val author = Author.Account(owner.id)

        assertFalse(access.canPostInProject(owner, project), "discussions are off; posting was allowed")
        assertFalse(access.canEditForumContent(owner, author), "discussions are off; editing a post was allowed")
        assertFalse(access.canUseForumTools(owner), "discussions are off; the agent door was open")
        assertFalse(
            access.canDeleteForumContent(owner, author, project.id),
            "discussions are off; deleting a post was allowed",
        )
        assertFalse(
            access.canReadConversation(owner, setOf(owner.id)),
            "private messages are off; a conversation was readable",
        )
        assertFalse(
            access.canWriteInConversation(owner, setOf(owner.id)),
            "private messages are off; a message could be sent",
        )
        assertFalse(access.canDeleteMessage(owner, author), "private messages are off; a message could be deleted")
    }
}
