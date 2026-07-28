/**
 * Deleting an issue takes its files with it — the rows *and* the bytes, on either
 * byte store (LNL-145).
 *
 * ── Why this file exists ─────────────────────────────────────────────────────
 *
 * The rows were already safe: [ForeignKeyTest] pins the `ON DELETE CASCADE` that
 * takes an issue's attachment rows with it. Nothing pinned the other half — that
 * the *files* those rows named are unlinked — and that half cannot be caught by
 * anything else, because getting it wrong fails silently: the volume simply keeps
 * every screenshot of every deleted issue, for ever, with nothing left able to
 * identify them. `IssueRepository.delete` reads the keys before the rows go for
 * exactly that reason, and a refactor that moved the read after the delete would
 * leave every other test in this repository passing.
 *
 * The second test is the one that found a live bug. The cascade used to unlink a
 * `java.io.File` through `AttachmentRepository.fileFor`, which is disk-only and
 * *throws* on any other byte store — so on the Cloud-Run/GCS backend, deleting an
 * issue that had a file on it would have failed outright rather than deleting
 * anything. Running the same cascade over [InMemoryAttachmentBlobStore] is how
 * that stays fixed without a live bucket; see [AttachmentBlobStore].
 *
 * @see IssueRepository.delete
 * @see AttachmentRepository.deleteBlob
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IssueAttachmentCascadeTest {
    private val file: File = Files.createTempFile("lunicle-issue-attachments", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)
    private val projects = ProjectStore(database)
    private val statuses = StatusStore(database)
    private val priorities = PriorityStore(database)
    private val issues = IssueStore(database)
    private val comments = CommentStore(database)
    private val attachmentStore = AttachmentStore(database)
    private val directory = File(file.parentFile, "attachments-${file.name}")

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
        directory.deleteRecursively()
    }

    /**
     * The volume-backed cascade: an issue's own file and its comment's file both
     * leave the disk, and neither row survives.
     */
    @Test
    fun `deleting an issue unlinks its file and its comments file`(): Unit = runBlocking {
        val attachments = AttachmentRepository(attachmentStore, directory)
        val fixture = seed(attachments)

        attachments.storeForIssue(fixture.issueId, "shot.png", "image/png", PNG_BYTES, fixture.author)
        val commentId = fixture.repository.createCommentDraft(fixture.issueId, fixture.author)
        attachments.storeForComment(commentId, "reply.png", "image/png", PNG_BYTES, fixture.author)
        assertEquals(2, directory.listFiles()?.size, "The two uploads did not land on the volume.")

        fixture.repository.delete(issues.findById(fixture.issueId)!!)

        assertEquals(0, directory.listFiles()?.size ?: 0, "Deleting an issue left its files on the volume.")
        assertEquals(emptySet(), attachmentStore.allStorageKeys(), "Attachment rows outlived their issue.")
    }

    /**
     * The same cascade with no disk under it at all — the GCS shape.
     *
     * Before LNL-145 this did not merely leave the objects behind: `fileFor` threw,
     * so the delete itself blew up half-done, with the issue's rows gone and the
     * caller looking at a 500.
     */
    @Test
    fun `the cascade unlinks through the blob store, not through a File`(): Unit = runBlocking {
        val blobs = InMemoryAttachmentBlobStore()
        val attachments = AttachmentRepository(attachmentStore, blobs)
        val fixture = seed(attachments)

        val issueFile = attachments.storeForIssue(fixture.issueId, "shot.png", "image/png", PNG_BYTES, fixture.author)
        val commentId = fixture.repository.createCommentDraft(fixture.issueId, fixture.author)
        val commentFile = attachments.storeForComment(commentId, "reply.png", "image/png", PNG_BYTES, fixture.author)
        val issueKey = attachmentStore.findById(issueFile.id)!!.storageKey
        val commentKey = attachmentStore.findById(commentFile.id)!!.storageKey
        assertTrue(blobs.fetch(issueKey) != null && blobs.fetch(commentKey) != null, "The uploads did not land.")

        fixture.repository.delete(issues.findById(fixture.issueId)!!)

        assertNull(blobs.fetch(issueKey), "Deleting an issue left its object in the blob store.")
        assertNull(blobs.fetch(commentKey), "Deleting an issue left its comment's object in the blob store.")
        assertEquals(emptySet(), attachmentStore.allStorageKeys(), "Attachment rows outlived their issue.")
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val issueId: Long, val author: Author, val repository: IssueRepository)

    /**
     * A project with one draft issue on it, wired to whichever byte store the test
     * is exercising — the repository under test is built here so both tests reach
     * the one `IssueRepository.delete` the routes and the MCP tools both call.
     */
    private suspend fun seed(attachments: AttachmentRepository): Fixture {
        val user = users.upsert(
            ProviderIdentity(
                provider = se.soderbjorn.lunicle.clientserver.AuthProvider.GITHUB,
                providerId = "gh-cascade",
                providerName = "tester",
                email = null,
            ),
        )
        val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
        val project = projectRepository.create("Lunamux", "LMX", isPublic = false)
        val repository = IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)
        val (issueId, _) = repository.createDraft(project.id, Author.Account(user.id))
        return Fixture(issueId, Author.Account(user.id), repository)
    }
}

/** The smallest thing a browser will call a PNG. See ForumPostTest's copy. */
private val PNG_BYTES = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
)
