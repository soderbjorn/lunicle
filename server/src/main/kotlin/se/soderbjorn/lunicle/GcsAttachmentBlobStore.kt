/**
 * The Cloud Storage byte store — attachment bytes on GCS for the Cloud-Run-native
 * deploy, where there is no disk.
 *
 * The GCS counterpart of [DiskAttachmentBlobStore], and it follows the exact
 * Railway-safety rule [FirestoreProvider] rests on: **nothing here opens a client
 * at boot.** The GCP SDK jar ships in the one image, but a `Storage` client reaches
 * for Application Default Credentials the moment it is built — and on Railway there
 * are no credentials and no metadata server to answer. So the client is behind a
 * `lazy`, and this store is only constructed on the `LUNICLE_DB_BACKEND=firestore`
 * branch of module wiring; the first byte operation is what actually opens it.
 *
 * How it authenticates matches [FirestoreProvider]:
 *  - **Cloud Run** — the runtime service account (`lunicle-run@…`, holding
 *    `storage.objectAdmin` on the bucket) plus the metadata server supply ADC.
 *  - **Off-GCP, real GCS** — `GOOGLE_APPLICATION_CREDENTIALS` or a
 *    `gcloud auth application-default login`, both of which ADC finds on its own.
 *
 * The bucket is read from `LUNICLE_ATTACHMENTS_BUCKET` through [resolveAttachmentsBucket],
 * never hardcoded. In production that is `lunicle-503112-lunicle-attachments`
 * (region europe-north2).
 *
 * @see AttachmentBlobStore
 * @see FirestoreProvider
 * @see resolveAttachmentsBucket
 */
package se.soderbjorn.lunicle

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import com.google.cloud.storage.StorageOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("GcsAttachmentBlobStore")

/**
 * The GCS bucket attachment bytes live in.
 *
 * Precedence mirrors [resolveGcpProject] and every other resolver in this server —
 * a system property for local runs (a Gradle `JavaExec` inherits the daemon's
 * environment, so an env var would go stale), then the environment variable the
 * container has.
 *
 * Fatal when unset *and reached*, which only happens under the Firestore/GCS
 * backend: a Cloud-Run deploy with no bucket is a misconfiguration that should
 * refuse to start rather than fail its first upload.
 */
internal fun resolveAttachmentsBucket(): String =
    (System.getProperty("lunicle.attachmentsBucket")?.takeIf { it.isNotBlank() }
        ?: System.getenv("LUNICLE_ATTACHMENTS_BUCKET")?.takeIf { it.isNotBlank() })
        ?: error(
            "LUNICLE_DB_BACKEND=firestore but LUNICLE_ATTACHMENTS_BUCKET is not set. The GCS " +
                "attachment store needs a bucket name. Refusing to start.",
        )

class GcsAttachmentBlobStore(
    private val bucket: String = resolveAttachmentsBucket(),
    private val projectId: String = resolveGcpProject(),
) : AttachmentBlobStore {
    private val storage: Storage by lazy { open() }

    private fun open(): Storage {
        logger.info("GCS attachments: bucket=$bucket, project=$projectId, target=Google (ADC)")
        return StorageOptions.newBuilder().setProjectId(projectId).build().service
    }

    private fun blobId(storageKey: String) = BlobId.of(bucket, storageKey)

    override suspend fun store(storageKey: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        // create() overwrites an object of the same name, matching the disk store's
        // writeBytes. Storage keys are random and never reused, so this is a plain
        // write in practice.
        storage.create(BlobInfo.newBuilder(blobId(storageKey)).build(), bytes)
    }

    override suspend fun fetch(storageKey: String): ByteArray? = withContext(Dispatchers.IO) {
        // get() answers null for a missing object rather than throwing, which is the
        // clean miss the interface promises. getContent() pulls the whole object into
        // heap, so this is for reconcile/verify — never the download hot path.
        storage.get(blobId(storageKey))?.getContent()
    }

    override suspend fun delete(storageKey: String) {
        withContext(Dispatchers.IO) {
            // Returns false when the object was already gone; that is a no-op, not an
            // error, exactly as File.delete() on a missing file is for the disk store.
            storage.delete(blobId(storageKey))
        }
    }

    override suspend fun sweepOrphans(known: Set<String>): Int = withContext(Dispatchers.IO) {
        var removed = 0
        // Page through every object in the bucket; the object name is the storage key.
        storage.list(bucket).iterateAll().forEach { blob ->
            val key = blob.name
            if (key !in known) {
                logger.info("Sweeping orphaned attachment object: $key")
                if (runCatching { blob.delete() }.getOrDefault(false)) removed++
            }
        }
        removed
    }

    /**
     * A once-per-boot check that the bucket answers — the parallel of the Firestore
     * client's first read. Optional: callers that would rather let the first upload
     * be the first contact can skip it. Rethrows a [StorageException] as itself so a
     * misconfigured bucket fails loudly at startup rather than on a user's upload.
     */
    suspend fun verifyReachable() {
        withContext(Dispatchers.IO) {
            try {
                storage.get(bucket)
            } catch (e: StorageException) {
                logger.error("GCS attachments bucket '$bucket' is not reachable", e)
                throw e
            }
        }
    }
}
