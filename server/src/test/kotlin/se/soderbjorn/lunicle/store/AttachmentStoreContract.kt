/**
 * The behaviour every [AttachmentStore] (metadata) implementation must exhibit.
 *
 * Exercised through the issue-owned path, which is representative of all five: a
 * row round-trips through insert → findById and findByPublicId, the storage key is
 * reachable both by owner (keysForIssue) and by the owner's project
 * (keysForProject), exactly one of the five owners is set, and delete removes the
 * row from both findById and the allStorageKeys reconcile set.
 *
 * A subclass per backend supplies the store and a seeded (project, issue) pair.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.Author

abstract class AttachmentStoreContract {
    protected abstract val store: AttachmentStore

    /** A fresh (projectId, issueId) an attachment can hang off. */
    protected abstract suspend fun newIssue(): Pair<Long, Long>

    private var seq = 0
    private suspend fun attach(issueId: Long): Long {
        val n = seq++
        return store.insertForIssue(
            issueId = issueId,
            filename = "shot-$n.png",
            mimeType = "image/png",
            byteSize = 1234,
            storageKey = "key-$n",
            publicId = "pub-$n",
            author = Author.Nobody,
        )
    }

    @Test
    fun `an attachment round-trips through findById with exactly one owner`() = runBlocking {
        val (_, issueId) = newIssue()
        val id = attach(issueId)
        val record = store.findById(id)!!
        assertEquals(issueId, record.issueId)
        assertEquals("shot-0.png", record.filename)
        assertEquals("image/png", record.mimeType)
        assertEquals("key-0", record.storageKey)
        assertEquals("pub-0", record.publicId)
        // Exactly one owner: the other four are null.
        assertNull(record.commentId)
        assertNull(record.forumPostId)
        assertNull(record.forumCommentId)
        assertNull(record.messageId)
    }

    @Test
    fun `findByPublicId finds the record and misses cleanly`() = runBlocking {
        val (_, issueId) = newIssue()
        attach(issueId)
        assertEquals("key-0", store.findByPublicId("pub-0")?.storageKey)
        assertNull(store.findByPublicId("nope"))
    }

    @Test
    fun `the storage key is reachable by owner and by project`() = runBlocking {
        val (projectId, issueId) = newIssue()
        attach(issueId)
        assertEquals(listOf("key-0"), store.keysForIssue(issueId))
        assertTrue("key-0" in store.keysForProject(projectId))
    }

    @Test
    fun `allStorageKeys reflects inserts, and delete removes the row`() = runBlocking {
        val (_, issueId) = newIssue()
        val id = attach(issueId)
        assertTrue("key-0" in store.allStorageKeys())
        store.delete(id)
        assertNull(store.findById(id))
        assertTrue("key-0" !in store.allStorageKeys())
    }
}
