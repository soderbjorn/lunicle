/**
 * The Comment contract run against the **Firestore** implementation on the emulator,
 * the mirror of [SqlDelightCommentStoreContractTest].
 *
 * Same assertions ([CommentStoreContract]), different backend; skips when no emulator
 * is configured. The seeded issue is synthetic — the comment store stores `issueId`
 * as a plain field and validates no foreign key — a fresh `Long` minted above the
 * store's own comment-id counter so the two never read as the same number.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreCommentStore

class FirestoreCommentStoreContractTest : CommentStoreContract() {
    private val fixture = FirestoreContractFixture()

    private var seq = 1_000L
    private fun nextId(): Long = ++seq

    override val store: CommentStore by lazy { FirestoreCommentStore(fixture.firestore) }

    override suspend fun newIssue(): Long = nextId()

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
