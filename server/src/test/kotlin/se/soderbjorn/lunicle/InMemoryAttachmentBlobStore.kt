/**
 * An in-memory [AttachmentBlobStore] — the test double that lets the byte-layer
 * behaviour be asserted without a disk or a live GCS bucket.
 *
 * The GCS emulator story is weaker than Firestore's, so rather than stand one up,
 * the blob-store contract runs its behavioural assertions against this fake *and*
 * the real [DiskAttachmentBlobStore] (see [AttachmentBlobStoreContract]). If both
 * pass the same contract, the seam is honest, and the one implementation the suite
 * cannot reach — [GcsAttachmentBlobStore] — is exercised only by an opt-in live
 * smoke test that skips by default.
 */
package se.soderbjorn.lunicle

import java.util.concurrent.ConcurrentHashMap

class InMemoryAttachmentBlobStore : AttachmentBlobStore {
    private val objects = ConcurrentHashMap<String, ByteArray>()

    override suspend fun store(storageKey: String, bytes: ByteArray) {
        objects[storageKey] = bytes.copyOf()
    }

    override suspend fun fetch(storageKey: String): ByteArray? = objects[storageKey]?.copyOf()

    override suspend fun delete(storageKey: String) {
        objects.remove(storageKey)
    }

    override suspend fun sweepOrphans(known: Set<String>): Int {
        val doomed = objects.keys.filter { it !in known }
        doomed.forEach { objects.remove(it) }
        return doomed.size
    }
}
