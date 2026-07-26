/**
 * The Message contract run against the **Firestore** implementation on the emulator,
 * the mirror of [SqlDelightMessageStoreContractTest].
 *
 * Same assertions ([MessageStoreContract]), different backend; skips when no
 * emulator is configured. Users and the conversation a message hangs off are both
 * synthetic `Long`s — the message store stores `conversationId` and the author as
 * plain fields and validates no foreign key, and never reads a conversation document
 * — minted above the store's own message-id counter so the two never collide.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreMessageStore

class FirestoreMessageStoreContractTest : MessageStoreContract() {
    private val fixture = FirestoreContractFixture()

    private var seq = 1_000L
    private fun nextId(): Long = ++seq

    override val store: MessageStore by lazy { FirestoreMessageStore(fixture.firestore) }

    override suspend fun newUser(): Long = nextId()

    override suspend fun newConversation(participantIds: Set<Long>): Long = nextId()

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
