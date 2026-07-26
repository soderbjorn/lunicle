/**
 * The behaviour every [ForumPostStore] implementation must exhibit.
 *
 * Persistence-level, since a post's title/body rules and the delete-the-files
 * cascade are backend-agnostic orchestration a layer up in `ForumPostRepository`:
 * a draft is invisible until published, forForum lists a forum's published posts
 * newest-first with a comment count, findByIdInForum refuses a post that is not
 * this forum's, edit rewrites a published post whole, updateBody rewrites the body
 * and leaves the draft flag, delete removes the row, and forForum is isolated per
 * forum.
 *
 * A backend seeding hook is needed because a post hangs off a real forum row:
 * [newForum] mints one (and, under it, a project) however the backend under test
 * makes them.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.Author

abstract class ForumPostStoreContract {
    protected abstract val store: ForumPostStore

    /** A forum row for a post to hang off, made the backend's own way. */
    protected abstract suspend fun newForum(): Long

    @Test
    fun `a draft is invisible until published`() = runBlocking {
        val forum = newForum()
        val id = store.insertDraft(forum, Author.Nobody, createdAt = 1_000)
        // A draft is readable by id but absent from the forum's published list.
        assertTrue(store.findById(id)!!.isDraft)
        assertEquals(emptyList(), store.forForum(forum).map { it.post.id })
        store.publish(id, "Title", "Body")
        val published = store.findById(id)!!
        assertEquals(false, published.isDraft)
        assertEquals("Title", published.title)
        assertEquals(listOf(id), store.forForum(forum).map { it.post.id })
    }

    @Test
    fun `forForum lists published posts newest first`() = runBlocking {
        val forum = newForum()
        val older = publishedPost(forum, createdAt = 1_000)
        val middle = publishedPost(forum, createdAt = 2_000)
        val newer = publishedPost(forum, createdAt = 3_000)
        assertEquals(listOf(newer, middle, older), store.forForum(forum).map { it.post.id })
    }

    @Test
    fun `findByIdInForum refuses a post from another forum`() = runBlocking {
        val forum = newForum()
        val other = newForum()
        val id = publishedPost(forum, createdAt = 1_000)
        assertEquals(id, store.findByIdInForum(id, forum)!!.id)
        assertNull(store.findByIdInForum(id, other))
    }

    @Test
    fun `edit rewrites a published post whole`() = runBlocking {
        val forum = newForum()
        val id = publishedPost(forum, createdAt = 1_000)
        store.edit(id, "Corrected", "New body", createdAt = 5_000, author = Author.External("Imported"), agentName = "agent")
        val read = store.findById(id)!!
        assertEquals("Corrected", read.title)
        assertEquals("New body", read.body)
        assertEquals(5_000, read.createdAt)
        assertEquals(Author.External("Imported"), read.author)
        assertEquals("agent", read.agentName)
        assertEquals(false, read.isDraft)
    }

    @Test
    fun `updateBody rewrites the body and leaves the draft flag`() = runBlocking {
        val forum = newForum()
        val id = publishedPost(forum, createdAt = 1_000)
        store.updateBody(id, "Rewritten by the mention renamer")
        val read = store.findById(id)!!
        assertEquals("Rewritten by the mention renamer", read.body)
        assertEquals(false, read.isDraft)
    }

    @Test
    fun `delete removes the post`() = runBlocking {
        val forum = newForum()
        val id = publishedPost(forum, createdAt = 1_000)
        store.delete(id)
        assertNull(store.findById(id))
        assertEquals(emptyList(), store.forForum(forum).map { it.post.id })
    }

    @Test
    fun `forForum is isolated per forum`() = runBlocking {
        val forum = newForum()
        val other = newForum()
        val mine = publishedPost(forum, createdAt = 1_000)
        publishedPost(other, createdAt = 1_000)
        assertEquals(listOf(mine), store.forForum(forum).map { it.post.id })
    }

    private suspend fun publishedPost(forumId: Long, createdAt: Long): Long {
        val id = store.insertDraft(forumId, Author.Nobody, createdAt = createdAt)
        store.publish(id, "Post $id", "Body $id")
        return id
    }
}
