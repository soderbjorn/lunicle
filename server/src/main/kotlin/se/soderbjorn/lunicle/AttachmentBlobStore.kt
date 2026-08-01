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
 * The sanity check both sweeps run before they delete anything (LUS-25).
 *
 * ── What this is defending against ──────────────────────────────────────────
 *
 * The sweep deletes every object whose key is not in the set the metadata store
 * answered with. Neither implementation checked that answer for plausibility, so
 * an empty or badly truncated key set against a full bucket meant **every
 * attachment on the instance deleted at startup**, one INFO line per object.
 *
 * That is not theoretical on the Firestore backend, where the key list comes from
 * an unindexed full-collection scan and where the *database* and the *bucket* are
 * named by separate environment variables. Pointing a process at a fresh or empty
 * Firestore database while the bucket variable still names the real bucket is a
 * one-variable mistake — a botched rollback, a staging deploy inheriting a
 * production value, a restore that brings up empty metadata — and it destroys all
 * user files before anybody has made a request.
 *
 * ── The two rules ───────────────────────────────────────────────────────────
 *
 * [refusal] is the second one. The first — an empty known set means do not sweep
 * at all — lives in [AttachmentRepository.sweepOrphans], because it needs no
 * listing and belongs where the two stores meet.
 *
 * @property MAX_FRACTION how much of what it finds a sweep may take. Half is
 *   deliberately loose: a sweep is *expected* to be a rounding error, so anything
 *   approaching half the bucket is a symptom rather than a busy week.
 * @property MIN_TOTAL how many objects have to be there before the fraction means
 *   anything. On a store holding three files, one orphan is a third of it, and a
 *   guard that fired there would refuse to do its job on every small deployment.
 */
internal object OrphanSweepGuard {
    const val MAX_FRACTION: Double = 0.5
    const val MIN_TOTAL: Int = 20

    /**
     * Why this sweep should not run, or null to go ahead.
     *
     * @param total how many objects the store holds.
     * @param orphans how many of them are about to be deleted.
     */
    fun refusal(total: Int, orphans: Int): String? {
        if (total < MIN_TOTAL) return null
        if (orphans <= total * MAX_FRACTION) return null
        return "would delete $orphans of $total stored attachment(s), which is more than " +
            "${(MAX_FRACTION * 100).toInt()}% — refusing. This usually means the metadata store " +
            "and the blob store are not describing the same deployment; check that the database " +
            "and bucket settings name the same instance before restarting."
    }
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
        val files = (directory.listFiles() ?: return@withContext 0).filter { it.isFile }
        val orphans = files.filter { it.name !in known }
        // Counted before anything is deleted, so the guard sees the whole picture
        // rather than deciding halfway through. See OrphanSweepGuard.
        OrphanSweepGuard.refusal(total = files.size, orphans = orphans.size)?.let { reason ->
            logger.warn("Attachment sweep refused: $reason")
            return@withContext 0
        }
        var removed = 0
        orphans.forEach { file ->
            // Logged individually and at INFO: this deletes user data that is
            // *believed* unreferenced, and if the belief is ever wrong, the log
            // is the only record of what went.
            logger.info("Sweeping orphaned attachment file: ${file.name}")
            if (file.delete()) removed++
        }
        // A summary at WARN beside the per-object lines (LUS-25). A sweep that takes
        // anything is worth one line somebody scanning a boot log will actually see;
        // the individual names stay at INFO for when they need to know which.
        if (removed > 0) logger.warn("Attachment sweep removed $removed of ${files.size} file(s)")
        removed
    }
}
