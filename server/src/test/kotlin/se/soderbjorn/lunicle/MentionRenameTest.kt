/**
 * A display name changes; the mentions already written follow it.
 *
 * The grammar itself is pinned in clientServer's MentionsTest. What is tested
 * here is the sweep over stored rows: which tables it reaches, what it refuses
 * to touch, and — the one that would be silently wrong — that it declines
 * entirely when a second account still answers to the old name.
 *
 * @see MentionRenamer
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AuthProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MentionRenameTest {
    private val file: File = Files.createTempFile("lunicle-mention-rename", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)
    private val roles = RoleStore(database)
    private val projects = ProjectStore(database)
    private val statuses = StatusStore(database)
    private val priorities = PriorityStore(database)
    private val issues = IssueStore(database)
    private val comments = CommentStore(database)
    private val attachmentStore = AttachmentStore(database)
    private val attachments = AttachmentRepository(attachmentStore, File(file.parentFile, "attachments-${file.name}"))
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
    private val issueRepository = IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)

    private val renamer = MentionRenamer(users, issues, comments, ForumPostStore(database), ForumCommentStore(database), MessageStore(database))

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    @Test
    fun `an issue description follows the rename`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f, description = "cc @Grace on this")

        renamer.rename(f.graceId, "Grace", "Grace Hopper")

        assertEquals("cc @{Grace Hopper} on this", issues.findById(id)!!.description)
    }

    @Test
    fun `a comment body follows the rename`(): Unit = runBlocking {
        val f = seed()
        val issueId = publish(f)
        val commentId = comment(f, issueId, "thanks @Grace")

        renamer.rename(f.graceId, "Grace", "Grace Hopper")

        assertEquals("thanks @{Grace Hopper}", comments.findById(commentId)!!.body)
    }

    /**
     * The one that would be a data loss rather than a bug: a rename must not drag
     * every issue that mentioned somebody to the top of the board. See Issues.sq's
     * `setDescription`.
     */
    @Test
    fun `a rename does not stamp the issues it rewrites as edited`(): Unit = runBlocking {
        val f = seed()
        val id = publish(f, description = "cc @Grace")
        val before = issues.findById(id)!!.updatedAt

        renamer.rename(f.graceId, "Grace", "Grace Hopper")

        assertEquals(before, issues.findById(id)!!.updatedAt, "A rename claimed the issue had been edited.")
    }

    @Test
    fun `text that is not a mention of that name is left alone`(): Unit = runBlocking {
        val f = seed()
        val prose = "Grace was here, ada@grace.org wrote, cc @Robert"
        val id = publish(f, description = prose)

        renamer.rename(f.graceId, "Grace", "Grace Hopper")

        assertEquals(prose, issues.findById(id)!!.description)
    }

    /**
     * Display names are not unique. If somebody else still answers to the old
     * name, the mentions written under it still reach a real person — and
     * rewriting them would redirect that person's mail to the one who just left
     * the name behind. Declining is the smaller failure.
     */
    @Test
    fun `nothing is rewritten while another account still holds the old name`(): Unit = runBlocking {
        val f = seed()
        // A second account whose provider name is the one being vacated.
        users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-3", "Grace", "grace2@example.com"))
        val id = publish(f, description = "cc @Grace")

        renamer.rename(f.graceId, "Grace", "Grace Hopper")

        assertEquals("cc @Grace", issues.findById(id)!!.description)
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private data class Fixture(val userId: Long, val graceId: Long, val projectId: Long)

    private suspend fun seed(): Fixture {
        roles.seed()
        val user = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-1", "Robert", "robert@example.com"))
        val grace = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-2", "Grace", "grace@example.com"))
        val project = projectRepository.create("Lunicle", "LNL", isPublic = false)
        return Fixture(userId = user.id, graceId = grace.id, projectId = project.id)
    }

    private suspend fun publish(f: Fixture, description: String = ""): Long {
        val (id, _) = issueRepository.createDraft(f.projectId, Author.Account(f.userId))
        val issue = issues.findById(id)!!
        issueRepository.save(
            issue = issue,
            title = "Something",
            description = description,
            statusId = issue.statusId,
            priorityId = issue.priorityId,
            resolutionId = issue.resolutionId,
            assigneeId = issue.assigneeId,
            sprintId = issue.sprintId,
            plannedVersionId = null,
            fixedVersionId = null,
            labelIds = emptyList(),
            componentIds = emptyList(),
            actorId = f.userId,
            actor = Author.Account(f.userId),
            agentName = null,
        )
        return id
    }

    private suspend fun comment(f: Fixture, issueId: Long, body: String): Long {
        val id = comments.insertDraft(issueId, Author.Account(f.userId))
        comments.publish(id, body)
        return id
    }
}
