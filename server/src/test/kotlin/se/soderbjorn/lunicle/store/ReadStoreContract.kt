/**
 * The behaviour every [ReadStore] implementation must exhibit — the two high-water
 * marks (LNL-30) and the two derived "still new to them" reads.
 *
 * This is the LNL-128 hard case: [ReadStore.unreadMessageCounts] and
 * [ReadStore.hasUnreadPosts] compare a user's mark against messages and posts that
 * live in *other* tables — a JOIN the SQLite reference runs in one statement, and
 * which the Firestore implementation gets through injected lookups. The contract is
 * written once against both. The SQLite subclass seeds real messages and posts
 * through the real stores so the join is genuinely exercised; the Firestore subclass
 * feeds the same shapes into the store's seam lambdas.
 *
 * The semantics pinned here: a mark is monotonic (never moves back); an unread count
 * is the messages above the mark, excluding the reader's own and their drafts, and a
 * conversation with nothing unread is *absent* rather than zero; unread counts are
 * scoped to the conversations the reader is in; a forum mark is null until set, then
 * reads back the high-water; and a project has an unread post exactly when one is
 * newer than its forum's mark, not the reader's own, not a draft — with an empty
 * project set short-circuiting to false.
 *
 * A subclass per backend supplies the store and the seeding hooks; the assertions
 * live here.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

abstract class ReadStoreContract {
    protected abstract val store: ReadStore

    /** A fresh user id. */
    protected abstract suspend fun newUser(): Long

    /** A fresh project id a forum can belong to. */
    protected abstract suspend fun newProject(): Long

    /** A conversation containing exactly [participantIds]. */
    protected abstract suspend fun newConversation(participantIds: Set<Long>): Long

    /** Publish a message in [conversationId] by [authorId]; returns its id (ids rise with time). */
    protected abstract suspend fun postMessage(conversationId: Long, authorId: Long): Long

    /** Start — but do not publish — a message in [conversationId] by [authorId]. */
    protected abstract suspend fun postDraftMessage(conversationId: Long, authorId: Long)

    /** A fresh forum in [projectId]. */
    protected abstract suspend fun newForum(projectId: Long): Long

    /** Publish a post in [forumId] by [authorId] written at [createdAt]; returns its id. */
    protected abstract suspend fun postInForum(forumId: Long, authorId: Long, createdAt: Long): Long

    /** Start — but do not publish — a post in [forumId] by [authorId] at [createdAt]. */
    protected abstract suspend fun postDraftInForum(forumId: Long, authorId: Long, createdAt: Long)

    // ── Conversation unread counts ───────────────────────────────────────────

    @Test
    fun `unread message counts reflect the conversation mark`() = runBlocking {
        val reader = newUser()
        val other = newUser()
        val conv = newConversation(setOf(reader, other))
        postMessage(conv, other)
        val second = postMessage(conv, other)
        val third = postMessage(conv, other)

        assertEquals(mapOf(conv to 3L), store.unreadMessageCounts(reader), "all three are unread at first")

        store.markConversationRead(reader, conv, second)
        assertEquals(mapOf(conv to 1L), store.unreadMessageCounts(reader), "one remains above the mark")

        store.markConversationRead(reader, conv, third)
        assertEquals(emptyMap(), store.unreadMessageCounts(reader), "a caught-up conversation is absent, not zero")
    }

    @Test
    fun `a reader is not unread on their own messages`() = runBlocking {
        val reader = newUser()
        val other = newUser()
        val conv = newConversation(setOf(reader, other))
        postMessage(conv, reader)
        postMessage(conv, other)

        assertEquals(mapOf(conv to 1L), store.unreadMessageCounts(reader), "only the other's message counts")
        assertEquals(mapOf(conv to 1L), store.unreadMessageCounts(other), "and symmetrically for them")
    }

    @Test
    fun `a draft message is not counted`() = runBlocking {
        val reader = newUser()
        val other = newUser()
        val conv = newConversation(setOf(reader, other))
        postDraftMessage(conv, other)

        assertTrue(store.unreadMessageCounts(reader).isEmpty(), "an unsent draft is nobody's unread")
    }

    @Test
    fun `unread counts are scoped to the conversations a reader is in`() = runBlocking {
        val reader = newUser()
        val member = newUser()
        val outsider = newUser()
        val conv = newConversation(setOf(reader, member))
        postMessage(conv, member)

        assertEquals(mapOf(conv to 1L), store.unreadMessageCounts(reader))
        assertTrue(store.unreadMessageCounts(outsider).isEmpty(), "a non-participant counts nothing here")
    }

    @Test
    fun `the conversation mark is monotonic`() = runBlocking {
        val reader = newUser()
        val other = newUser()
        val conv = newConversation(setOf(reader, other))
        val first = postMessage(conv, other)
        postMessage(conv, other)
        val third = postMessage(conv, other)

        store.markConversationRead(reader, conv, third)
        store.markConversationRead(reader, conv, first) // lower — must not move the mark back
        assertEquals(emptyMap(), store.unreadMessageCounts(reader), "the stale, lower mark is ignored")
    }

    // ── Forum marks ──────────────────────────────────────────────────────────

    @Test
    fun `a forum mark is null until set, then reads back the high-water`() = runBlocking {
        val user = newUser()
        val forum = newForum(newProject())

        assertNull(store.forumMark(user, forum), "never read → null, not zero")

        store.markForumRead(user, forum, 100L)
        assertEquals(100L, store.forumMark(user, forum))

        store.markForumRead(user, forum, 50L) // lower — monotonic
        assertEquals(100L, store.forumMark(user, forum), "a lower mark does not move it back")

        store.markForumRead(user, forum, 200L)
        assertEquals(200L, store.forumMark(user, forum))
    }

    // ── Unread posts ─────────────────────────────────────────────────────────

    @Test
    fun `hasUnreadPosts is false for an empty project set without a read`() = runBlocking {
        assertFalse(store.hasUnreadPosts(newUser(), emptyList()))
    }

    @Test
    fun `a post newer than the forum mark is unread, and reading past it clears it`() = runBlocking {
        val reader = newUser()
        val author = newUser()
        val project = newProject()
        val forum = newForum(project)

        assertFalse(store.hasUnreadPosts(reader, listOf(project)), "no posts yet")

        postInForum(forum, author, createdAt = 1_000L)
        assertTrue(store.hasUnreadPosts(reader, listOf(project)), "an unread post shows")

        store.markForumRead(reader, forum, 1_000L)
        assertFalse(store.hasUnreadPosts(reader, listOf(project)), "reading up to it clears the dot")
    }

    @Test
    fun `a reader's own post is not unread`() = runBlocking {
        val reader = newUser()
        val project = newProject()
        val forum = newForum(project)
        postInForum(forum, reader, createdAt = 1_000L)

        assertFalse(store.hasUnreadPosts(reader, listOf(project)), "you are never unread on your own post")
    }

    @Test
    fun `a draft post is not unread`() = runBlocking {
        val reader = newUser()
        val author = newUser()
        val project = newProject()
        val forum = newForum(project)
        postDraftInForum(forum, author, createdAt = 1_000L)

        assertFalse(store.hasUnreadPosts(reader, listOf(project)), "an unpublished draft is nobody's unread")
    }
}
