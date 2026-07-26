/**
 * The blob-store contract, run against the real volume-backed
 * [DiskAttachmentBlobStore] in a throwaway temp directory.
 */
package se.soderbjorn.lunicle

import java.nio.file.Files
import kotlin.test.AfterTest

class DiskAttachmentBlobStoreContractTest : AttachmentBlobStoreContract() {
    private val directory = Files.createTempDirectory("lunicle-blob-contract").toFile()
    override val store: AttachmentBlobStore = DiskAttachmentBlobStore(directory)

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }
}
