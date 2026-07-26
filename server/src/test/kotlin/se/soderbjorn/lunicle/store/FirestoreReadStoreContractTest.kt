/**
 * The Read contract — the LNL-128 hard case — run against the **Firestore**
 * implementation on the emulator, the mirror of [SqlDelightReadStoreContractTest].
 *
 * Same assertions ([ReadStoreContract]), different backend; skipped when no emulator
 * is configured (no `-Dlunicle.firestoreEmulatorHost=…`).
 *
 * **The marks are real; the join is synthetic.** The store keeps its own mark
 * documents on the emulator — those are exercised for real. What it cannot do on a
 * document store is join messages and posts against those marks, so it takes them
 * through two seam lambdas: [FirestoreReadStore.publishedMessagesFor] and
 * [FirestoreReadStore.publishedPostsIn]. Here those are backed by the in-memory
 * tables this fixture builds as the contract seeds — published messages scoped to
 * the conversations a user is in, published posts scoped to a project's forums —
 * exactly the shapes the message/forum stores will supply in production (LNL-122). A
 * draft is simply never added to those tables, mirroring that the production seam is
 * fed by the published-only reads (`forConversation`, `forForum`). Ids are synthetic
 * and monotonic, so a returned message id is a usable read mark.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreReadStore

class FirestoreReadStoreContractTest : ReadStoreContract() {
    private val fixture = FirestoreContractFixture()
    private var seq = 20_000L

    // The synthetic "other collections" the seam joins against — published only.
    private val participants = mutableMapOf<Long, Set<Long>>()
    private val messages = mutableListOf<FirestoreReadStore.UnreadMessage>()
    private val forumProject = mutableMapOf<Long, Long>()
    private val posts = mutableListOf<FirestoreReadStore.UnreadPost>()

    override val store: ReadStore by lazy {
        FirestoreReadStore(
            fixture.firestore,
            publishedMessagesFor = { userId ->
                messages.filter { userId in (participants[it.conversationId] ?: emptySet()) }
            },
            publishedPostsIn = { projectIds ->
                posts.filter { forumProject[it.forumId]?.let { p -> p in projectIds } ?: false }
            },
        )
    }

    override suspend fun newUser(): Long = ++seq

    override suspend fun newProject(): Long = ++seq

    override suspend fun newConversation(participantIds: Set<Long>): Long {
        val id = ++seq
        participants[id] = participantIds
        return id
    }

    override suspend fun postMessage(conversationId: Long, authorId: Long): Long {
        val id = ++seq
        messages += FirestoreReadStore.UnreadMessage(conversationId, id, authorId)
        return id
    }

    override suspend fun postDraftMessage(conversationId: Long, authorId: Long) {
        // A draft never reaches the published-only seam — nothing to record.
    }

    override suspend fun newForum(projectId: Long): Long {
        val id = ++seq
        forumProject[id] = projectId
        return id
    }

    override suspend fun postInForum(forumId: Long, authorId: Long, createdAt: Long): Long {
        val id = ++seq
        posts += FirestoreReadStore.UnreadPost(forumId, createdAt, authorId)
        return id
    }

    override suspend fun postDraftInForum(forumId: Long, authorId: Long, createdAt: Long) {
        // A draft never reaches the published-only seam — nothing to record.
    }

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
