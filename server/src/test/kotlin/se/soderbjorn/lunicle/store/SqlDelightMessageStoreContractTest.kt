/**
 * The Message contract, run against the SQLite reference implementation.
 *
 * The assertions live in [MessageStoreContract]; this file wires SQLite to them —
 * a temp database, the concrete message store over it, users seeded through
 * [se.soderbjorn.lunicle.UserStore], and the conversation a message hangs off
 * created through the concrete [se.soderbjorn.lunicle.ConversationStore] so the
 * `conversation_id` foreign key is satisfied exactly as it is on the volume.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.ProviderIdentity
import se.soderbjorn.lunicle.UserStore
import se.soderbjorn.lunicle.clientserver.AuthProvider

class SqlDelightMessageStoreContractTest : MessageStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val users = UserStore(db)
    private val conversations = se.soderbjorn.lunicle.ConversationStore(db)

    private var seq = 0

    override val store: MessageStore = se.soderbjorn.lunicle.MessageStore(db)

    override suspend fun newUser(): Long {
        val n = seq++
        return users.upsert(ProviderIdentity(AuthProvider.GITHUB, "u$n", "U$n", null)).id
    }

    override suspend fun newConversation(participantIds: Set<Long>): Long =
        conversations.insert(Author.Account(participantIds.first()), participantIds)

    @AfterTest
    fun tearDown() = fixture.close()
}
