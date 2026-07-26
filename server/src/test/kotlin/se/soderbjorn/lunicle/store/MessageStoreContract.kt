/**
 * The behaviour every [MessageStore] implementation must exhibit.
 *
 * The parity-critical things here are the draft dance and the ordering both
 * backends must agree on: that a message is born a draft and [publish] is what
 * makes it visible, that [forConversation] returns published messages oldest-first
 * and hides drafts, that [findByIdInConversation] refuses a message that is not
 * this conversation's, and that the two bulk-rewrite scans ([withPossibleMentions],
 * [withAttachmentLinks]) see drafts as well as published rows. [maxPublishedId] and
 * [hasPublished] — the two the read-mark and discard rules lean on — are pinned in
 * both their empty and their populated states.
 *
 * A subclass per backend supplies the store, seeds the users a message names, and
 * creates the conversation a message hangs off; the assertions live here.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.Author

abstract class MessageStoreContract {
    protected abstract val store: MessageStore

    /** A fresh user a message can be authored by. */
    protected abstract suspend fun newUser(): Long

    /** A fresh conversation with [participantIds] in it, that a message can hang off. */
    protected abstract suspend fun newConversation(participantIds: Set<Long>): Long

    // ── The draft dance ──────────────────────────────────────────────────────

    @Test
    fun `a message is born a draft with an empty body`() = runBlocking {
        val author = newUser()
        val conversation = newConversation(setOf(author))
        val id = store.insertDraft(conversation, Author.Account(author))
        val message = store.findById(id)
        assertEquals(conversation, message?.conversationId)
        assertEquals("", message?.body)
        assertEquals(Author.Account(author), message?.author)
        assertTrue(message?.isDraft == true, "A freshly inserted message was not a draft.")
    }

    @Test
    fun `publishing sets the body and clears the draft flag`() = runBlocking {
        val author = newUser()
        val id = store.insertDraft(newConversation(setOf(author)), Author.Account(author))
        store.publish(id, "Hello.")
        val message = store.findById(id)
        assertEquals("Hello.", message?.body)
        assertFalse(message?.isDraft == true, "Publishing left the message a draft.")
    }

    @Test
    fun `updateBody rewrites the body without touching the draft flag`() = runBlocking {
        val author = newUser()
        val conversation = newConversation(setOf(author))
        val draft = store.insertDraft(conversation, Author.Account(author))
        store.updateBody(draft, "Still a draft.")
        assertEquals("Still a draft.", store.findById(draft)?.body)
        assertTrue(store.findById(draft)?.isDraft == true, "updateBody published a draft.")

        val published = store.insertDraft(conversation, Author.Account(author))
        store.publish(published, "Sent.")
        store.updateBody(published, "Rewritten.")
        assertEquals("Rewritten.", store.findById(published)?.body)
        assertFalse(store.findById(published)?.isDraft == true, "updateBody un-published a message.")
    }

    @Test
    fun `deleting a message removes it`() = runBlocking {
        val author = newUser()
        val id = store.insertDraft(newConversation(setOf(author)), Author.Account(author))
        store.delete(id)
        assertNull(store.findById(id))
    }

    // ── Reading a thread ─────────────────────────────────────────────────────

    @Test
    fun `forConversation returns published messages oldest first`() = runBlocking {
        val author = newUser()
        val conversation = newConversation(setOf(author))
        publish(conversation, author, at = 300, body = "Third.")
        publish(conversation, author, at = 100, body = "First.")
        publish(conversation, author, at = 200, body = "Second.")
        assertEquals(
            listOf("First.", "Second.", "Third."),
            store.forConversation(conversation).map { it.body },
        )
    }

    @Test
    fun `forConversation hides drafts`() = runBlocking {
        val author = newUser()
        val conversation = newConversation(setOf(author))
        publish(conversation, author, at = 100, body = "Sent.")
        store.insertDraft(conversation, Author.Account(author))
        assertEquals(listOf("Sent."), store.forConversation(conversation).map { it.body })
    }

    @Test
    fun `findByIdInConversation refuses a message from another conversation`() = runBlocking {
        val author = newUser()
        val here = newConversation(setOf(author))
        val elsewhere = newConversation(setOf(author))
        val id = store.insertDraft(here, Author.Account(author))
        assertEquals(id, store.findByIdInConversation(id, here)?.id)
        assertNull(store.findByIdInConversation(id, elsewhere), "A message was found under the wrong conversation.")
    }

    // ── The read-mark and discard facts ──────────────────────────────────────

    @Test
    fun `maxPublishedId is zero until a message is published, then the newest id`() = runBlocking {
        val author = newUser()
        val conversation = newConversation(setOf(author))
        store.insertDraft(conversation, Author.Account(author))
        assertEquals(0, store.maxPublishedId(conversation), "A draft-only conversation had a non-zero mark.")

        val first = store.insertDraft(conversation, Author.Account(author)).also { store.publish(it, "One.") }
        val second = store.insertDraft(conversation, Author.Account(author)).also { store.publish(it, "Two.") }
        assertEquals(maxOf(first, second), store.maxPublishedId(conversation))
    }

    @Test
    fun `hasPublished is false for a draft-only conversation and true once published`() = runBlocking {
        val author = newUser()
        val conversation = newConversation(setOf(author))
        val id = store.insertDraft(conversation, Author.Account(author))
        assertFalse(store.hasPublished(conversation), "A draft counted as something having been sent.")
        store.publish(id, "Sent.")
        assertTrue(store.hasPublished(conversation))
    }

    // ── The bulk-rewrite scans see drafts too ────────────────────────────────

    @Test
    fun `withPossibleMentions finds every at-sign body, drafts included`() = runBlocking {
        val author = newUser()
        val conversation = newConversation(setOf(author))
        val published = store.insertDraft(conversation, Author.Account(author)).also { store.publish(it, "Hi @Ada.") }
        val draft = store.insertDraft(conversation, Author.Account(author)).also { store.updateBody(it, "@Grace?") }
        store.insertDraft(conversation, Author.Account(author)).also { store.publish(it, "No mention here.") }
        assertEquals(setOf(published, draft), store.withPossibleMentions().map { it.first }.toSet())
    }

    @Test
    fun `withAttachmentLinks finds bodies that link to an attachment`() = runBlocking {
        val author = newUser()
        val conversation = newConversation(setOf(author))
        val linked = store.insertDraft(conversation, Author.Account(author))
            .also { store.publish(it, "See /api/attachments/7") }
        store.insertDraft(conversation, Author.Account(author)).also { store.publish(it, "Nothing linked.") }
        assertEquals(listOf(linked), store.withAttachmentLinks().map { it.first })
    }

    /** Land a published message at a chosen time, so ordering assertions are deterministic. */
    private suspend fun publish(conversationId: Long, authorId: Long, at: Long, body: String) {
        val id = store.insertDraft(conversationId, Author.Account(authorId), createdAt = at)
        store.publish(id, body)
    }
}
