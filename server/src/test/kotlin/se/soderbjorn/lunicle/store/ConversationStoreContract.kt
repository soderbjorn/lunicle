/**
 * The behaviour every [ConversationStore] implementation must exhibit.
 *
 * The parity-critical things here are the ones easy to model differently between a
 * relational and a document backend: that membership is exactly what [insert]
 * froze (no more, no fewer, and readable as a set), that the participant checks
 * never leak one conversation's members into another's, and — the list rules the
 * Messages tab turns on — that [forUser] shows only conversations with a published
 * message, newest-spoken first, and only to the people in them.
 *
 * A subclass per backend supplies the store, seeds the users a conversation names,
 * and knows how to land a published message at a chosen time (the one cross-store
 * fact [forUser] depends on); the assertions live here.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.Author

abstract class ConversationStoreContract {
    protected abstract val store: ConversationStore

    /** A fresh user a conversation can name. */
    protected abstract suspend fun newUser(): Long

    /** Land a published message in [conversationId], written by [authorId] at [at]. */
    protected abstract suspend fun publishMessage(conversationId: Long, authorId: Long, at: Long, body: String)

    // ── Membership is exactly what insert froze ──────────────────────────────

    @Test
    fun `a conversation reports who started it`() = runBlocking {
        val author = newUser()
        val id = store.insert(Author.Account(author), setOf(author, newUser()))
        assertEquals(Author.Account(author), store.findById(id)?.author)
    }

    @Test
    fun `participantIds are exactly those inserted`() = runBlocking {
        val a = newUser()
        val b = newUser()
        val c = newUser()
        val id = store.insert(Author.Account(a), setOf(a, b, c))
        assertEquals(setOf(a, b, c), store.participantIds(id))
    }

    @Test
    fun `isParticipant tells a member from a stranger`() = runBlocking {
        val member = newUser()
        val stranger = newUser()
        val id = store.insert(Author.Account(member), setOf(member))
        assertTrue(store.isParticipant(id, member))
        assertFalse(store.isParticipant(id, stranger))
    }

    @Test
    fun `deleting a conversation removes it and its membership`() = runBlocking {
        val a = newUser()
        val id = store.insert(Author.Account(a), setOf(a))
        store.delete(id)
        assertNull(store.findById(id))
        assertEquals(emptySet(), store.participantIds(id))
    }

    // ── The list rules the Messages tab turns on ─────────────────────────────

    @Test
    fun `a conversation appears in the list only once it has a published message`() = runBlocking {
        val a = newUser()
        val id = store.insert(Author.Account(a), setOf(a))
        assertEquals(emptyList(), store.forUser(a), "An unsent conversation appeared in the list.")

        publishMessage(id, a, at = 100, body = "Hello.")
        val listed = store.forUser(a)
        assertEquals(listOf(id), listed.map { it.id })
        assertEquals(100, listed.single().lastMessageAt)
        assertEquals("Hello.", listed.single().lastMessageBody, "The preview did not follow the last message.")
    }

    @Test
    fun `the list is most recently spoken in first`() = runBlocking {
        val a = newUser()
        val older = store.insert(Author.Account(a), setOf(a))
        val newer = store.insert(Author.Account(a), setOf(a))
        publishMessage(older, a, at = 100, body = "Old.")
        publishMessage(newer, a, at = 200, body = "New.")
        assertEquals(listOf(newer, older), store.forUser(a).map { it.id })
    }

    @Test
    fun `the list is isolated to the people in a conversation`() = runBlocking {
        val a = newUser()
        val b = newUser()
        val outsider = newUser()
        val id = store.insert(Author.Account(a), setOf(a, b))
        publishMessage(id, a, at = 100, body = "Private.")
        assertEquals(listOf(id), store.forUser(a).map { it.id })
        assertEquals(emptyList(), store.forUser(outsider).map { it.id }, "A stranger saw a conversation they are not in.")
    }

    @Test
    fun `participantsForUser groups every member by conversation`() = runBlocking {
        val a = newUser()
        val b = newUser()
        val c = newUser()
        val first = store.insert(Author.Account(a), setOf(a, b))
        val second = store.insert(Author.Account(a), setOf(a, c))
        assertEquals(
            mapOf(first to setOf(a, b), second to setOf(a, c)),
            store.participantsForUser(a),
        )
    }
}
