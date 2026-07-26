/**
 * The Forum-post contract run against the **Firestore** implementation on the
 * emulator, the mirror of [SqlDelightForumPostStoreContractTest].
 *
 * Same assertions ([ForumPostStoreContract]), different backend; skips when no
 * emulator is configured. The seeded forum is synthetic — the post store stores
 * `forumId` as a plain field and validates no foreign key — a fresh `Long` minted
 * above the store's own post-id counter so the two never read as the same number.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreForumPostStore

class FirestoreForumPostStoreContractTest : ForumPostStoreContract() {
    private val fixture = FirestoreContractFixture()

    private var seq = 1_000L
    private fun nextId(): Long = ++seq

    override val store: ForumPostStore by lazy { FirestoreForumPostStore(fixture.firestore) }

    override suspend fun newForum(): Long = nextId()

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
