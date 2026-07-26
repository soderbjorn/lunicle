/**
 * A live smoke test against the real GCS bucket — **skipped by default**.
 *
 * There is no GCS emulator in this suite (the delete-and-sweep semantics are proven
 * by [AttachmentBlobStoreContract] over the fake and the disk store), so the only
 * way to exercise [GcsAttachmentBlobStore] for real is against a live bucket with
 * live credentials. That is opt-in: this runs only when both a bucket and a project
 * are configured — `-Dlunicle.attachmentsBucket=… -Dlunicle.gcpProject=…` (or their
 * `LUNICLE_ATTACHMENTS_BUCKET` / `GOOGLE_CLOUD_PROJECT` env equivalents) — with ADC
 * present. Absent either, it skips rather than fails, so the suite never depends on
 * GCP creds.
 *
 * It deliberately does **not** call [GcsAttachmentBlobStore.sweepOrphans]: that
 * deletes every object not in the known set, and against a real attachments bucket
 * that is production data. The sweep is proven non-destructively elsewhere; here we
 * only round-trip our own namespaced keys and delete them again.
 */
package se.soderbjorn.lunicle

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

class GcsAttachmentBlobStoreSmokeTest {
    private val bucket: String? =
        System.getProperty("lunicle.attachmentsBucket")?.takeIf { it.isNotBlank() }
            ?: System.getenv("LUNICLE_ATTACHMENTS_BUCKET")?.takeIf { it.isNotBlank() }
    private val project: String? =
        System.getProperty("lunicle.gcpProject")?.takeIf { it.isNotBlank() }
            ?: System.getenv("GOOGLE_CLOUD_PROJECT")?.takeIf { it.isNotBlank() }

    @Test
    fun `store, fetch and delete round-trip against the live bucket`() = runBlocking {
        assumeTrue("GCS bucket/project not configured — live smoke test skipped", bucket != null && project != null)
        val store = GcsAttachmentBlobStore(bucket!!, project!!)

        // A random key so a shared bucket can run this in parallel; a crashed run
        // leaves one stray object the startup sweep would later collect.
        val key = "lunicle-smoke-${UUID.randomUUID()}"
        val bytes = "smoke-test bytes".toByteArray()
        try {
            store.store(key, bytes)
            assertTrue(bytes.contentEquals(store.fetch(key)))
        } finally {
            store.delete(key)
        }
        assertNull(store.fetch(key))
    }
}
