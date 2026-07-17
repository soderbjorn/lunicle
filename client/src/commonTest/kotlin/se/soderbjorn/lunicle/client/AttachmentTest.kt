/**
 * The decision that decides whether an attachment works at all.
 *
 * ── Why this is worth a file ─────────────────────────────────────────────────
 *
 * [attachmentMarkdown] picks one of two spellings, and the pick has to agree
 * *exactly* with a decision made in a different module by different code — the
 * server's inline-versus-download branch. When they agree, an image renders and a
 * PDF downloads. When they drift, the failure is not an error anywhere: it is a
 * broken image in someone's issue, or a file that opens in the tab instead of
 * saving, and neither is reported by anything but a person.
 *
 * The tests below pin the pick to the shared list rather than to a literal, so
 * the day someone adds a type to [INLINE_IMAGE_MIME_TYPES] both sides move
 * together — and the day someone adds `image/svg+xml` to it, the test that says
 * an SVG is a download fails and asks why.
 *
 * @see attachmentMarkdown
 * @see se.soderbjorn.lunicle.clientserver.INLINE_IMAGE_MIME_TYPES
 */
package se.soderbjorn.lunicle.client

import se.soderbjorn.lunicle.clientserver.INLINE_IMAGE_MIME_TYPES
import se.soderbjorn.lunicle.clientserver.MAX_ATTACHMENT_BYTES
import se.soderbjorn.lunicle.clientserver.formatByteSize
import se.soderbjorn.lunicle.clientserver.tooLargeMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentTest {

    // ── The spelling ─────────────────────────────────────────────────────────

    @Test
    fun `every inline image type is spelled as an image`() {
        INLINE_IMAGE_MIME_TYPES.forEach { type ->
            val markdown = attachmentMarkdown("file", type, 1000, 5)
            assertTrue(markdown.startsWith("!["), "$type should render inline, got: $markdown")
        }
    }

    /**
     * The one that matters. An SVG served inline would be stored XSS, so the
     * server refuses to serve it inline — which means an `<img>` pointing at it
     * is a broken image. It has to be a download on this side too.
     */
    @Test
    fun `an svg is spelled as a download, not an image`() {
        val markdown = attachmentMarkdown("logo.svg", "image/svg+xml", 2048, 5)
        assertEquals("[logo.svg (2.0 kB)](/api/attachments/5)", markdown)
    }

    @Test
    fun `a non-image is spelled as a download carrying its size`() {
        assertEquals(
            "[report.pdf (2.3 MB)](/api/attachments/12)",
            attachmentMarkdown("report.pdf", "application/pdf", 2_411_724, 12),
        )
    }

    /**
     * A browser that could not identify the file sends an empty type, and after
     * the widening that is a thing that reaches us. It must not be mistaken for
     * an image — an `<img>` pointing at a download is broken, and the empty
     * string is not on any allow-list.
     */
    @Test
    fun `an unknown type is spelled as a download`() {
        assertTrue(attachmentMarkdown("thing.xyz", "", 500, 1).startsWith("["))
    }

    /** A browser may send parameters; the identity is the type, not the charset. */
    @Test
    fun `type parameters do not change the spelling`() {
        assertTrue(attachmentMarkdown("a.png", "image/PNG; charset=binary", 10, 1).startsWith("!["))
    }

    /**
     * A filename with markdown in it does not escape its own label.
     *
     * "[final].pdf" unescaped closes the label early: the link breaks and the
     * rest of the name spills into the document as text. Silent, and a real
     * filename.
     */
    @Test
    fun `a filename with markdown markers is escaped`() {
        val markdown = attachmentMarkdown("[final]*v2*.pdf", "application/pdf", 100, 1)
        assertEquals("[\\[final\\]\\*v2\\*.pdf (100 bytes)](/api/attachments/1)", markdown)
    }

    // ── The size ─────────────────────────────────────────────────────────────

    @Test
    fun `sizes read the way a file manager writes them`() {
        assertEquals("0 bytes", formatByteSize(0))
        assertEquals("1 byte", formatByteSize(1))
        assertEquals("999 bytes", formatByteSize(999))
        assertEquals("1.0 kB", formatByteSize(1024))
        assertEquals("2.0 kB", formatByteSize(2048))
        assertEquals("1.0 MB", formatByteSize(1024 * 1024))
        assertEquals("25.0 MB", formatByteSize(MAX_ATTACHMENT_BYTES))
        assertEquals("4.0 GB", formatByteSize(4L * 1024 * 1024 * 1024))
    }

    // ── The limit ────────────────────────────────────────────────────────────

    @Test
    fun `a file at the limit is not refused`() {
        assertNull(tooLargeMessage(MAX_ATTACHMENT_BYTES))
    }

    /**
     * One byte over is refused, and the sentence names both sizes.
     *
     * The exact boundary, because `>` written as `>=` is how this breaks and it
     * is invisible at every other size.
     */
    @Test
    fun `one byte over the limit is refused, in a sentence naming both sizes`() {
        val message = tooLargeMessage(MAX_ATTACHMENT_BYTES + 1)
        assertNotNull(message)
        assertTrue("25.0 MB" in message, "The limit is not in the message: $message")

        val huge = tooLargeMessage(4L * 1024 * 1024 * 1024)
        assertNotNull(huge)
        assertTrue("4.0 GB" in huge, "The file's own size is not in the message: $huge")
        assertTrue("25.0 MB" in huge, "The limit is not in the message: $huge")
    }
}
