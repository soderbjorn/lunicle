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

    /**
     * The rest of the cascade — comments, history and watches (LNL-177).
     *
     * The two tests above wire an [IssueRepository] with neither history nor
     * subscriptions, which is the shape most of this repository's tests use and is
     * exactly why this one exists: those two collaborators are nullable, so a
     * cascade call on either is skipped silently when they are absent.
     *
     * ── Why it counts calls as well as reading the end state ─────────────────
     *
     * Because on SQLite the end state proves nothing. `ON DELETE CASCADE` empties
     * all three tables the moment the issue row goes, so every "is it gone?"
     * assertion below passes just as well with the three sweeps deleted from
     * `IssueRepository.delete` outright. The thing that must not regress is that the
     * repository *asks* — that is the whole mechanism keeping the Firestore backend,
     * which has no cascade to fall back on, from orphaning an issue's entire
     * conversation. The store contracts prove both backends answer correctly when
     * asked; nothing but this pins that they are asked at all.
     *
     * So the stores are wrapped in counting delegates. The counts are the real
     * assertion; the emptiness assertions are kept because they are what a reader
     * actually wants to know, and because they would catch a delegate that counted
     * without delegating.
     */
    @Test
    fun `deleting an issue takes its comments, its history and its watches`(): Unit = runBlocking {
        val attachments = AttachmentRepository(attachmentStore, InMemoryAttachmentBlobStore())
        val events = CountingEventStore(IssueEventStore(database))
        val subscriptions = CountingSubscriptionStore(SubscriptionStore(database))
        val fixture = seed(attachments, events, subscriptions, CountingCommentStore(comments))

        val spyComments = fixture.comments

        val commentId = fixture.repository.createCommentDraft(fixture.issueId, fixture.author)
        fixture.repository.saveComment(commentId, "worth keeping until it isn't")
        events.append(
            fixture.issueId,
            listOf(NewIssueEvent(se.soderbjorn.lunicle.clientserver.IssueEventKind.STATUS_CHANGED, value = "New")),
            author = fixture.author,
        )
        val watcher = fixture.author.accountId!!
        subscriptions.setIssueUpdateSubscription(watcher, fixture.issueId, true)

        assertEquals(1, comments.forIssue(fixture.issueId).size, "the comment did not land")
        assertEquals(1, events.forIssue(fixture.issueId).size, "the history did not land")
        assertTrue(subscriptions.isSubscribedToIssueUpdates(watcher, fixture.issueId), "the watch did not land")

        fixture.repository.delete(issues.findById(fixture.issueId)!!)

        // The real assertion: the repository asked each store to sweep. Without this
        // the whole test passes on SQLite's foreign keys alone — see the preamble.
        assertEquals(
            listOf(fixture.issueId), spyComments.sweptIssues,
            "IssueRepository.delete did not ask the comment store to sweep; Firestore would orphan the conversation.",
        )
        assertEquals(
            listOf(fixture.issueId), events.sweptIssues,
            "IssueRepository.delete did not ask the event store to sweep; Firestore would orphan the history.",
        )
        assertEquals(
            listOf(fixture.issueId), subscriptions.sweptIssues,
            "IssueRepository.delete did not ask the subscription store to sweep; Firestore would keep the watches.",
        )

        // And the end state, which is what a reader wants to know — and what would
        // catch a delegate that counted the call without passing it on.
        assertEquals(emptyList(), comments.forIssue(fixture.issueId), "Comments outlived their issue.")
        assertEquals(emptyList(), events.forIssue(fixture.issueId), "History outlived its issue.")
        assertEquals(emptyList(), subscriptions.watchersForIssue(fixture.issueId), "Watches outlived their issue.")
        assertTrue(
            !subscriptions.isSubscribedToIssueUpdates(watcher, fixture.issueId),
            "A watch on a deleted issue survived, and would keep naming an audience for it.",
        )
    }

    // ── Counting delegates ───────────────────────────────────────────────────
    //
    // Each records the issue ids its cascade method was called with and otherwise
    // forwards everything. Hand-written rather than mocked because these are the
    // store interfaces the whole backend seam is built on, and a delegate that
    // fails to compile when one gains a method is a feature, not a chore.

    private class CountingCommentStore(
        private val delegate: se.soderbjorn.lunicle.store.CommentStore,
    ) : se.soderbjorn.lunicle.store.CommentStore by delegate {
        val sweptIssues = mutableListOf<Long>()
        override suspend fun deleteForIssue(issueId: Long) {
            sweptIssues += issueId
            delegate.deleteForIssue(issueId)
        }
    }

    private class CountingEventStore(
        private val delegate: se.soderbjorn.lunicle.store.IssueEventStore,
    ) : se.soderbjorn.lunicle.store.IssueEventStore by delegate {
        val sweptIssues = mutableListOf<Long>()
        override suspend fun deleteForIssue(issueId: Long) {
            sweptIssues += issueId
            delegate.deleteForIssue(issueId)
        }
    }

    private class CountingSubscriptionStore(
        private val delegate: se.soderbjorn.lunicle.store.SubscriptionStore,
    ) : se.soderbjorn.lunicle.store.SubscriptionStore by delegate {
        val sweptIssues = mutableListOf<Long>()
        override suspend fun deleteIssueSubscriptions(issueId: Long) {
            sweptIssues += issueId
            delegate.deleteIssueSubscriptions(issueId)
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(
        val issueId: Long,
        val author: Author,
        val repository: IssueRepository,
        /** The comment store the repository was actually given, so a spy can be read back. */
        val comments: CountingCommentStore,
    )

    /**
     * A project with one draft issue on it, wired to whichever byte store the test
     * is exercising — the repository under test is built here so both tests reach
     * the one `IssueRepository.delete` the routes and the MCP tools both call.
     */
    private suspend fun seed(
        attachments: AttachmentRepository,
        events: se.soderbjorn.lunicle.store.IssueEventStore? = null,
        subscriptions: se.soderbjorn.lunicle.store.SubscriptionStore? = null,
        commentStore: CountingCommentStore = CountingCommentStore(comments),
    ): Fixture {
        val user = users.upsert(
            ProviderIdentity(
                provider = se.soderbjorn.lunicle.clientserver.AuthProvider.GITHUB,
                providerId = "gh-cascade",
                providerName = "tester",
                email = null,
            ),
        )
        val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
        val project = projectRepository.create("Lunamux", "LMX")
        val repository = IssueRepository(
            issues, commentStore, statuses, priorities, attachments, attachmentStore,
            subscriptions = subscriptions,
            history = events?.let {
                IssueHistory(it, statuses, LabelStore(database), ComponentStore(database), users)
            },
        )
        val (issueId, _) = repository.createDraft(project.id, Author.Account(user.id))
        return Fixture(issueId, Author.Account(user.id), repository, commentStore)
    }
}

/** The smallest thing a browser will call a PNG. See ForumPostTest's copy. */
private val PNG_BYTES = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
)
