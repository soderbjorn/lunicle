/**
 * The blob-store contract, run against the [InMemoryAttachmentBlobStore] fake —
 * the same assertions the disk store passes, which is what makes the fake a
 * faithful stand-in for the seam wherever a live byte store is out of reach.
 */
package se.soderbjorn.lunicle

class InMemoryAttachmentBlobStoreContractTest : AttachmentBlobStoreContract() {
    override val store: AttachmentBlobStore = InMemoryAttachmentBlobStore()
}
