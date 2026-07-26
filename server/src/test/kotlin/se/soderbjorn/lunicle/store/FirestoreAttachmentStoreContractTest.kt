/**
 * The Attachment-metadata contract, run against the **Firestore** implementation
 * on the emulator — the mirror of [SqlDelightAttachmentStoreContractTest].
 *
 * Same assertions ([AttachmentStoreContract]), different backend. If the emulator
 * is not configured for this run (no `-Dlunicle.firestoreEmulatorHost=…`), every
 * test skips rather than fails, so the SQLite suite is unaffected.
 *
 * ── Why synthetic ids, and no real repositories ─────────────────────────────
 *
 * The SQLite subclass seeds a real (project, issue) through the repositories to
 * satisfy the foreign key. Firestore has no foreign key, so a fresh issue is just
 * a pair of distinct `Long`s. What the Firestore store *does* need is the
 * ancestry: `keysForProject(projectId)` must find an attachment inserted with only
 * an `issueId`. That mapping is what [AttachmentScopeResolver] supplies, and here
 * it is backed by the synthetic ids [newIssue] hands out — the issue's project is
 * whatever [newIssue] paired it with.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.AttachmentScope
import se.soderbjorn.lunicle.AttachmentScopeResolver
import se.soderbjorn.lunicle.FirestoreAttachmentStore

class FirestoreAttachmentStoreContractTest : AttachmentStoreContract() {
    private val fixture = FirestoreContractFixture()
    private var seq = 0L

    /** issueId → its project, recorded by [newIssue] and read back by the resolver. */
    private val projectByIssue = mutableMapOf<Long, Long>()

    private val resolver = object : AttachmentScopeResolver {
        override suspend fun forIssue(issueId: Long) =
            AttachmentScope(projectId = projectByIssue[issueId], issueId = issueId)

        override suspend fun forComment(commentId: Long) = AttachmentScope()
        override suspend fun forForumPost(forumPostId: Long) = AttachmentScope(postId = forumPostId)
        override suspend fun forForumComment(forumCommentId: Long) = AttachmentScope()
        override suspend fun forMessage(messageId: Long) = AttachmentScope()
    }

    override val store: AttachmentStore by lazy { FirestoreAttachmentStore(fixture.firestore, resolver) }

    override suspend fun newIssue(): Pair<Long, Long> {
        val projectId = ++seq
        val issueId = ++seq
        projectByIssue[issueId] = projectId
        return projectId to issueId
    }

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
