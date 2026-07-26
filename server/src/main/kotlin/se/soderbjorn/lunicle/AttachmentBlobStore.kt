/**
 * The file-bytes seam behind [AttachmentRepository] — where an attachment's bytes
 * actually live, once its metadata row has been written.
 *
 * [AttachmentRepository] pairs a metadata store with a byte store and keeps the two
 * in step (write file → insert row; delete row → unlink file; sweep orphans at
 * startup). This interface is the byte half of that pair, extracted so the bytes
 * can move off local disk without touching the repository's non-atomic-write
 * choreography. Two implementations sit behind it: [DiskAttachmentBlobStore], the
 * volume-backed store Railway has always run and the default, byte-for-byte the
 * behaviour that used to live inside the repository; and [GcsAttachmentBlobStore]
 * for the Cloud-Run-native deploy, where there is no disk.
 *
 * ── The one operation that is deliberately *not* here ───────────────────────
 *
 * Serving a download. The download route streams straight from disk with Ktor's
 * `LocalFileContent`, which never lands the bytes in the JVM heap — the whole
 * reason attachments are files and not BLOBs (a 10 MB screenshot through a
 * free-trial-sized heap is what Attachments.sq's preamble is about). That
 * zero-copy path is disk-only and stays disk-only: it reaches a `File` through
 * [DiskAttachmentBlobStore.fileFor], not through this interface. [fetch] exists for
 * the reconcile-and-verify paths (and for a future GCS download wiring), but the
 * default disk download is untouched. See [AttachmentRepository.fileFor].
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("AttachmentBlobStore")

interface AttachmentBlobStore {
    /** Write [bytes] under [storageKey], overwriting any existing object. */
    suspend fun store(storageKey: String, bytes: ByteArray)

    /** The bytes under [storageKey], or null if there are none — a clean miss, never a throw. */
    suspend fun fetch(storageKey: String): ByteArray?

    /** Remove the object under [storageKey]. A no-op, not an error, if it is already gone. */
    suspend fun delete(storageKey: String)

    /**
     * Delete every stored object whose key is not in [known] — the reconcile side
     * of orphan sweeping, run once at startup.
     *
     * It collects three things, exactly as the disk sweep always has: objects whose
     * row never landed, objects whose row was deleted by a cascade the repository
     * never saw, and the objects behind abandoned drafts.
     *
     * @return how many objects were removed, for the log.
     */
    suspend fun sweepOrphans(known: Set<String>): Int
}

/**
 * The volume-backed byte store — today's behaviour, lifted out of
 * [AttachmentRepository] unchanged and still the default.
 *
 * @param directory where the files go. Derived from the same [DatabaseLocation]
 *   the database uses, so attachments cannot land on a different disk from the
 *   rows describing them.
 */
class DiskAttachmentBlobStore(private val directory: File) : AttachmentBlobStore {
    init {
        // The volume is mounted at its mount path, but nothing guarantees a
        // subdirectory below it exists, and nothing else will create this one.
        directory.mkdirs()
    }

    /**
     * The file behind a storage key, for the download route to stream.
     *
     * Disk-only and not on [AttachmentBlobStore]: it is the zero-copy download
     * path, and there is no `File` behind a GCS object. See the interface preamble.
     */
    fun fileFor(storageKey: String): File = File(directory, storageKey)

    override suspend fun store(storageKey: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        fileFor(storageKey).writeBytes(bytes)
    }

    override suspend fun fetch(storageKey: String): ByteArray? = withContext(Dispatchers.IO) {
        fileFor(storageKey).takeIf { it.isFile }?.readBytes()
    }

    override suspend fun delete(storageKey: String): Unit = withContext(Dispatchers.IO) {
        fileFor(storageKey).delete()
    }

    override suspend fun sweepOrphans(known: Set<String>): Int = withContext(Dispatchers.IO) {
        val files = directory.listFiles() ?: return@withContext 0
        var removed = 0
        files.forEach { file ->
            if (file.isFile && file.name !in known) {
                // Logged individually and at INFO: this deletes user data that is
                // *believed* unreferenced, and if the belief is ever wrong, the log
                // is the only record of what went.
                logger.info("Sweeping orphaned attachment file: ${file.name}")
                if (file.delete()) removed++
            }
        }
        removed
    }
}
