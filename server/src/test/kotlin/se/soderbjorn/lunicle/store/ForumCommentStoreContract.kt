/**
 * The behaviour every [ForumCommentStore] implementation must exhibit.
 *
 * Persistence-level, since a comment's body rule and the delete-the-files cascade
 * are backend-agnostic orchestration a layer up in `ForumPostRepository`: a draft
 * is invisible until published, forPost lists a post's published comments
 * oldest-first (flat, no nesting), findByIdInPost refuses a comment that is not
 * this post's, edit rewrites a published comment whole, delete removes the row,
 * and forPost is isolated per post.
 *
 * A backend seeding hook is needed because a comment hangs off a real post row:
 * [newPost] mints one (and, under it, a forum and a project) however the backend
 * under test makes them.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.Author

abstract class ForumCommentStoreContract {
    protected abstract val store: ForumCommentStore

    /** A published post for a comment to hang off, made the backend's own way. */
    protected abstract suspend fun newPost(): Long

    @Test
    fun `a draft comment is invisible until published`() = runBlocking {
        val post = newPost()
        val id = store.insertDraft(post, Author.Nobody, createdAt = 1_000)
        assertTrue(store.findById(id)!!.isDraft)
        assertEquals(emptyList(), store.forPost(post).map { it.id })
        store.publish(id, "A reply")
        val published = store.findById(id)!!
        assertEquals(false, published.isDraft)
        assertEquals("A reply", published.body)
        assertEquals(listOf(id), store.forPost(post).map { it.id })
    }

    @Test
    fun `forPost lists published comments oldest first`() = runBlocking {
        val post = newPost()
        val first = publishedComment(post, createdAt = 1_000)
        val second = publishedComment(post, createdAt = 2_000)
        val third = publishedComment(post, createdAt = 3_000)
        assertEquals(listOf(first, second, third), store.forPost(post).map { it.id })
    }

    @Test
    fun `findByIdInPost refuses a comment from another post`() = runBlocking {
        val post = newPost()
        val other = newPost()
        val id = publishedComment(post, createdAt = 1_000)
        assertEquals(id, store.findByIdInPost(id, post)!!.id)
        assertNull(store.findByIdInPost(id, other))
    }

    @Test
    fun `edit rewrites a published comment whole`() = runBlocking {
        val post = newPost()
        val id = publishedComment(post, createdAt = 1_000)
        store.edit(id, "Corrected", createdAt = 5_000, author = Author.External("Imported"), agentName = "agent")
        val read = store.findById(id)!!
        assertEquals("Corrected", read.body)
        assertEquals(5_000, read.createdAt)
        assertEquals(Author.External("Imported"), read.author)
        assertEquals("agent", read.agentName)
        assertEquals(false, read.isDraft)
    }

    @Test
    fun `delete removes the comment`() = runBlocking {
        val post = newPost()
        val id = publishedComment(post, createdAt = 1_000)
        store.delete(id)
        assertNull(store.findById(id))
        assertEquals(emptyList(), store.forPost(post).map { it.id })
    }

    @Test
    fun `forPost is isolated per post`() = runBlocking {
        val post = newPost()
        val other = newPost()
        val mine = publishedComment(post, createdAt = 1_000)
        publishedComment(other, createdAt = 1_000)
        assertEquals(listOf(mine), store.forPost(post).map { it.id })
    }

    private suspend fun publishedComment(postId: Long, createdAt: Long): Long {
        val id = store.insertDraft(postId, Author.Nobody, createdAt = createdAt)
        store.publish(id, "Comment $id")
        return id
    }
}
