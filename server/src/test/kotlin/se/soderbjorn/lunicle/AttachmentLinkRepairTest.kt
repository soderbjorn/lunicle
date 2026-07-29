/**
 * The startup link repair, against a real database.
 *
 * [se.soderbjorn.lunicle.clientserver.AttachmentLinkTest] pins the rewrite
 * itself, which is pure. What it cannot see is everything around it: whether the
 * repair reads the right rows, decides which attachments are viewable from the
 * stored `mime_type` rather than from a filename, writes the result back, reaches
 * comments as well as descriptions, and — the thing this class exists for —
 * leaves `updated_at` alone while doing it.
 *
 * That last one is not a detail. This runs on every boot, over every issue on the
 * instance. A version that stamped `updated_at` would drag every issue holding an
 * HTML attachment to the top of a board sorted on "last touched", once, on the
 * deploy that shipped it, with no edit behind it — and there would be no way to
 * tell afterwards that it had happened.
 *
 * @see AttachmentLinkRepair
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AuthProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentLinkRepairTest {
    private val file: File = Files.createTempFile("lunicle-linkrepair", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)
    private val issues = IssueStore(database)
    private val comments = CommentStore(database)
    private val statuses = StatusStore(database)
    private val priorities = PriorityStore(database)
    private val attachmentStore = AttachmentStore(database)
    private val attachments = AttachmentRepository(attachmentStore, File(file.parentFile, "attachments-${file.name}"))
    private val projects = ProjectRepository(database, ProjectStore(database), attachments, attachmentStore)
    private val issueRepository =
        IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)

    private val repair = AttachmentLinkRepair(attachmentStore, issues, comments, ForumPostStore(database), ForumCommentStore(database), MessageStore(database))

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    /**
     * The case the ticket was reopened about: an HTML report attached before the
     * view route existed, still linked as a download.
     */
    @Test
    fun `an HTML attachment's link is re-spelled to open`(): Unit = runBlocking {
        val fixture = seed()
        val publicId = "pub-key-html"
        attachmentStore.insertForIssue(
            fixture.issueId, "mockup.html", "text/html", 8_000, "key-html", publicId, Author.Account(fixture.userId),
        )
        issues.setDescription(fixture.issueId, "Mockup: [mockup.html (7.8 kB)](/api/attachments/$publicId)")

        repair.run()

        assertEquals(
            "Mockup: [mockup.html (7.8 kB)](/api/attachments/$publicId/view)",
            issues.findById(fixture.issueId)?.description,
        )
    }

    /**
     * The decision comes from the stored `mime_type`, not from the name.
     *
     * A zip called `report.html.zip` and an HTML file called `notes` both exist,
     * and a repair that sniffed the filename would get each of them backwards.
     */
    @Test
    fun `an attachment that is not a document keeps its download link`(): Unit = runBlocking {
        val fixture = seed()
        val publicId = "pub-key-zip"
        attachmentStore.insertForIssue(
            fixture.issueId, "report.html.zip", "application/zip", 8_000, "key-zip", publicId, Author.Account(fixture.userId),
        )
        val before = "Logs: [report.html.zip (7.8 kB)](/api/attachments/$publicId)"
        issues.setDescription(fixture.issueId, before)

        repair.run()

        assertEquals(before, issues.findById(fixture.issueId)?.description)
    }

    /** Comments are documents too, and the same link can be written in one. */
    @Test
    fun `a comment body is repaired as well`(): Unit = runBlocking {
        val fixture = seed()
        val publicId = "pub-key-html"
        attachmentStore.insertForIssue(
            fixture.issueId, "r.html", "text/html", 10, "key-html", publicId, Author.Account(fixture.userId),
        )
        val commentId = comments.insertDraft(fixture.issueId, Author.Account(fixture.userId))
        comments.publish(commentId, "Here: [r.html (10 bytes)](/api/attachments/$publicId)")

        repair.run()

        assertEquals(
            "Here: [r.html (10 bytes)](/api/attachments/$publicId/view)",
            comments.findById(commentId)?.body,
        )
    }

    /**
     * The repair is not an edit, and must not be recorded as one.
     *
     * `updated_at` is what the board sorts on. Stamping it here would reorder
     * every board on the instance, once, on the deploy that shipped this, for a
     * change nobody made.
     */
    @Test
    fun `the repair does not touch updated_at`(): Unit = runBlocking {
        val fixture = seed()
        val publicId = "pub-key-html"
        attachmentStore.insertForIssue(
            fixture.issueId, "r.html", "text/html", 10, "key-html", publicId, Author.Account(fixture.userId),
        )
        issues.setDescription(fixture.issueId, "[r.html](/api/attachments/$publicId)")
        val before = issues.findById(fixture.issueId)?.updatedAt

        repair.run()

        assertEquals(before, issues.findById(fixture.issueId)?.updatedAt)
    }

    /**
     * A second boot changes nothing.
     *
     * This runs unconditionally on every start, so "already repaired" is the state
     * it will be in for the rest of the instance's life. A non-idempotent version
     * would produce `/view/view` on the second boot and 404 on every report.
     */
    @Test
    fun `running it twice is the same as running it once`(): Unit = runBlocking {
        val fixture = seed()
        val publicId = "pub-key-html"
        attachmentStore.insertForIssue(
            fixture.issueId, "r.html", "text/html", 10, "key-html", publicId, Author.Account(fixture.userId),
        )
        issues.setDescription(fixture.issueId, "[r.html](/api/attachments/$publicId)")

        repair.run()
        val once = issues.findById(fixture.issueId)?.description
        repair.run()

        assertEquals(once, issues.findById(fixture.issueId)?.description)
        assertEquals("[r.html](/api/attachments/$publicId/view)", once)
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val userId: Long, val issueId: Long)

    private suspend fun seed(): Fixture {
        val user = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-tester", "Tester", null))
        val project = projects.create("Lunamux", "LMX")
        val (issueId, _) = issueRepository.createDraft(project.id, Author.Account(user.id))
        return Fixture(user.id, issueId)
    }
}
