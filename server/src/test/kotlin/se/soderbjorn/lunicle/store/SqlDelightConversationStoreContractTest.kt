/**
 * The Conversation contract, run against the SQLite reference implementation.
 *
 * The assertions live in [ConversationStoreContract]; this file wires SQLite to
 * them — a temp database, the concrete stores over it, and users seeded through
 * [se.soderbjorn.lunicle.UserStore] so every foreign key is satisfied exactly as
 * it is on the volume. [publishMessage] uses the concrete message store, because
 * `forUser`'s "has a published message" clause is the one fact this store cannot
 * establish alone.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.ProviderIdentity
import se.soderbjorn.lunicle.UserStore
import se.soderbjorn.lunicle.clientserver.AuthProvider

class SqlDelightConversationStoreContractTest : ConversationStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val users = UserStore(db)
    private val messages = se.soderbjorn.lunicle.MessageStore(db)

    private var seq = 0

    override val store: ConversationStore = se.soderbjorn.lunicle.ConversationStore(db)

    override suspend fun newUser(): Long {
        val n = seq++
        return users.upsert(ProviderIdentity(AuthProvider.GITHUB, "u$n", "U$n", null)).id
    }

    override suspend fun publishMessage(conversationId: Long, authorId: Long, at: Long, body: String) {
        val id = messages.insertDraft(conversationId, Author.Account(authorId), createdAt = at)
        messages.publish(id, body)
    }

    @AfterTest
    fun tearDown() = fixture.close()
}
