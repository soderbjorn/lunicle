/**
 * The Conversation contract run against the **Firestore** implementation on the
 * emulator, the mirror of [SqlDelightConversationStoreContractTest].
 *
 * Same assertions ([ConversationStoreContract]), different backend; skips when no
 * emulator is configured. Users are synthetic `Long`s — the conversation store
 * stores member ids as a plain array and validates no foreign key — minted above the
 * store's own conversation-id counter so the two never read as the same number.
 *
 * [publishMessage] lands a real published message through a Firestore
 * [FirestoreMessageStore] over the *same* client, because `forUser`'s "has a
 * published message" clause is the one fact the conversation store cannot establish
 * alone — exactly the cross-store seam the SQLite fixture crosses the same way.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.FirestoreConversationStore
import se.soderbjorn.lunicle.FirestoreMessageStore

class FirestoreConversationStoreContractTest : ConversationStoreContract() {
    private val fixture = FirestoreContractFixture()

    private var seq = 1_000L
    private fun nextId(): Long = ++seq

    override val store: ConversationStore by lazy { FirestoreConversationStore(fixture.firestore) }
    private val messages by lazy { FirestoreMessageStore(fixture.firestore) }

    override suspend fun newUser(): Long = nextId()

    override suspend fun publishMessage(conversationId: Long, authorId: Long, at: Long, body: String) {
        val id = messages.insertDraft(conversationId, Author.Account(authorId), createdAt = at)
        messages.publish(id, body)
    }

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
