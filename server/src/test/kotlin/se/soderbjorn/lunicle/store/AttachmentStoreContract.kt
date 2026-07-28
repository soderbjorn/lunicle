/**
 * The behaviour every [AttachmentStore] (metadata) implementation must exhibit.
 *
 * Exercised through the issue-owned path, which is representative of all five: a
 * row round-trips through insert → findById and findByPublicId, the storage key is
 * reachable both by owner (keysForIssue) and by the owner's project
 * (keysForProject), exactly one of the five owners is set, delete removes the row
 * from both findById and the allStorageKeys reconcile set, and deleteForIssue
 * removes one issue's rows without touching another issue's.
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

    /**
     * The cascade every backend has to perform itself (LNL-145).
     *
     * SQLite would do this by `ON DELETE CASCADE` and Firestore not at all, so the
     * one function that deletes an issue asks for it explicitly and both must
     * answer. The second issue is the half that matters: a delete keyed on the
     * wrong field, or on nothing, empties the whole collection and still passes the
     * first three assertions.
     */
    @Test
    fun `deleteForIssue takes that issue's rows and spares another issue's`() = runBlocking {
        val (_, issueId) = newIssue()
        val (_, otherIssueId) = newIssue()
        val doomed = attach(issueId)
        val spared = attach(otherIssueId)

        store.deleteForIssue(issueId)

        assertNull(store.findById(doomed))
        assertEquals(emptyList(), store.keysForIssue(issueId))
        assertTrue("key-0" !in store.allStorageKeys())
        assertEquals(spared, store.findById(spared)?.id, "Deleting one issue's files took another's.")
        assertEquals(listOf("key-1"), store.keysForIssue(otherIssueId))
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
