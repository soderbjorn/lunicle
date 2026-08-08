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
import kotlin.test.assertNotNull
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

    /**
     * A sweep that would empty the store refuses instead (LUS-25).
     *
     * Neither implementation checked the metadata store's answer for plausibility,
     * so an empty or badly truncated key set against a full store meant every
     * attachment on the instance deleted at startup, one INFO line per object. On
     * the Firestore backend that key list comes from an unindexed full-collection
     * scan, and the database and the bucket are named by *separate* environment
     * variables — so a botched rollback, or a staging deploy inheriting a production
     * value, is one variable away from destroying all user files.
     *
     * Both backends have to refuse identically, which is why this lives in the
     * contract rather than beside one of them.
     */
    @Test
    fun `sweepOrphans refuses to take most of the store`(): Unit = runBlocking {
        repeat(30) { store.store("obj-$it", "x".toByteArray()) }

        // Two known keys against thirty stored objects: the shape a wrong database
        // produces, and nothing a real deployment reaches.
        assertEquals(
            0,
            store.sweepOrphans(setOf("obj-0", "obj-1")),
            "A sweep emptied almost the whole store on the word of a key set that cannot be right.",
        )
        assertNotNull(store.fetch("obj-29"), "The refused sweep deleted objects anyway.")
    }

    /** And an ordinary sweep still runs, however many objects are there. */
    @Test
    fun `a proportionate sweep still runs on a large store`(): Unit = runBlocking {
        repeat(30) { store.store("obj-$it", "x".toByteArray()) }
        val known = (0 until 30).map { "obj-$it" }.toMutableSet()
        known.remove("obj-7")

        assertEquals(1, store.sweepOrphans(known), "An ordinary one-object sweep was refused.")
        assertNull(store.fetch("obj-7"))
        assertNotNull(store.fetch("obj-8"))
    }
}
