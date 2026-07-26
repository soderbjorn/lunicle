/**
 * The behaviour every [AttachmentBlobStore] implementation must exhibit.
 *
 * The byte-layer parallel of the metadata `*StoreContract`s: one set of
 * assertions, run against each backend. A round-trip stores and fetches the same
 * bytes, a fetch of an unknown key misses cleanly rather than throwing, a delete
 * removes the object (and a delete of what is already gone is a no-op), and the
 * orphan sweep removes exactly the keys not in the known set and leaves the rest.
 *
 * A subclass per backend supplies a fresh store. It runs against
 * [DiskAttachmentBlobStore] and [InMemoryAttachmentBlobStore]; the GCS
 * implementation is left to a gated live smoke test, since the suite has no
 * emulator to point it at.
 */
package se.soderbjorn.lunicle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

abstract class AttachmentBlobStoreContract {
    protected abstract val store: AttachmentBlobStore

    @Test
    fun `bytes round-trip through store and fetch`() = runBlocking {
        val bytes = "a screenshot's worth of bytes".toByteArray()
        store.store("key-a", bytes)
        assertTrue(bytes.contentEquals(store.fetch("key-a")))
    }

    @Test
    fun `store overwrites an existing key`() = runBlocking {
        store.store("key-a", "first".toByteArray())
        store.store("key-a", "second".toByteArray())
        assertEquals("second", store.fetch("key-a")?.decodeToString())
    }

    @Test
    fun `fetch of an unknown key misses cleanly`() = runBlocking {
        assertNull(store.fetch("nope"))
    }

    @Test
    fun `delete removes the object, and deleting a missing key is a no-op`() = runBlocking {
        store.store("key-a", "bytes".toByteArray())
        store.delete("key-a")
        assertNull(store.fetch("key-a"))
        store.delete("key-a") // already gone — must not throw
        store.delete("never-existed")
    }

    @Test
    fun `sweepOrphans removes the unknown keys and keeps the known ones`() = runBlocking {
        store.store("keep", "k".toByteArray())
        store.store("drop-1", "d".toByteArray())
        store.store("drop-2", "d".toByteArray())

        val removed = store.sweepOrphans(setOf("keep"))

        assertEquals(2, removed)
        assertTrue("k".toByteArray().contentEquals(store.fetch("keep")))
        assertNull(store.fetch("drop-1"))
        assertNull(store.fetch("drop-2"))
    }

    @Test
    fun `sweepOrphans over an empty store removes nothing`() = runBlocking {
        assertEquals(0, store.sweepOrphans(setOf("anything")))
    }
}
