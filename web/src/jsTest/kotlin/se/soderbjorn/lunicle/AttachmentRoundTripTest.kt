/**
 * serialise ∘ render is identity — asserted for an attachment, in a real DOM.
 *
 * ── The bug this file exists to catch ────────────────────────────────────────
 *
 * MarkdownSerialiser is "closed over the toolbar": it handles exactly what the
 * toolbar can make, and an element it does not know contributes its text and no
 * markup. That rule is what keeps the editor honest, and it has a sharp edge —
 * **adding a new kind of element to the surface without teaching the serialiser
 * about it does not fail loudly. It silently deletes the thing.**
 *
 * Attachments are exactly that kind of change. The Attach button now puts an
 * `<a class="attachment" download>` into the surface where only `<img>` used to
 * go. If the serialiser did not already know how to read an anchor, the sequence
 * would be: upload a 20 MB video, see the link appear, type one more character —
 * which re-serialises the surface — and watch the link evaporate. The file stays
 * on the volume, orphaned, and nothing anywhere reports an error. The user finds
 * out when they look for the video and it is not there.
 *
 * So the test is the round trip, not the rendering: put the attachment in the
 * surface the way the editor does, serialise, and demand the markdown back
 * **byte-identical**. Byte-identical rather than "contains the URL", because the
 * ways this breaks are small — a dropped size, an unescaped bracket, a `!` that
 * turns a download into a broken image — and every one of them survives a
 * substring check.
 *
 * In a browser, via `js { browser() }`, because the subject is a DOM: the
 * serialiser walks real nodes, and the browser is the thing whose behaviour is
 * in question.
 *
 * @see serialiseMarkdown
 * @see se.soderbjorn.lunicle.client.renderMarkdown
 * @see MarkdownEditor
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.client.attachmentMarkdown
import se.soderbjorn.lunicle.client.renderMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentRoundTripTest {

    // ── The reason this file exists ──────────────────────────────────────────

    /**
     * A file attachment inserted into the surface survives being saved.
     *
     * The exact sequence the editor performs: the view model returns markdown,
     * [MarkdownEditor.inlineHtmlOf] renders it to a fragment, `insertAttachment`
     * drops that fragment into a paragraph in the surface, and the next keystroke
     * serialises the lot. The assertion is on the last step's output against the
     * first step's input.
     */
    @Test
    fun `a file attachment survives insert then serialise, byte-identical`() {
        val markdown = attachmentMarkdown("report.pdf", "application/pdf", 2_411_724, "12")
        // Guard the fixture: if this ever stops being a link, the round trip
        // below is testing images again and the whole file has quietly lapsed.
        assertEquals("[report.pdf (2.3 MB)](/api/attachments/12)", markdown)

        val surface = surfaceWith(markdown)

        assertEquals(markdown, serialiseMarkdown(surface))
    }

    /**
     * An image attachment still survives too.
     *
     * The path that already worked, kept honest: the change that added file links
     * touched `renderLinks`, which every image's *alt text* does not go through
     * but which sits one line away from `renderImages` in the same function.
     */
    @Test
    fun `an image attachment survives insert then serialise, byte-identical`() {
        val markdown = attachmentMarkdown("screenshot.png", "image/png", 48_216, "7")
        assertEquals("![screenshot.png](/api/attachments/7)", markdown)

        assertEquals(markdown, serialiseMarkdown(surfaceWith(markdown)))
    }

    /**
     * A filename full of markdown metacharacters comes back as itself.
     *
     * `[final].pdf` is a real filename, and an unescaped one would close the
     * link's label early — leaving a broken link, and the rest of the name loose
     * in the document as literal text. The `*` is the other half: unescaped, the
     * pair around "v2" would render the middle of the filename in italics and
     * lose the asterisks. Both are silent.
     */
    @Test
    fun `a filename containing markdown markers survives the round trip`() {
        val markdown = attachmentMarkdown("[final] *v2* (draft).pdf", "application/pdf", 900, "3")
        val serialised = serialiseMarkdown(surfaceWith(markdown))

        assertEquals(markdown, serialised)
        // And the point of the escaping: the name reaches the reader intact,
        // rather than as a fragment with its punctuation eaten.
        val rendered = document.createElement("div") as HTMLElement
        rendered.innerHTML = renderMarkdown(serialised)
        assertEquals("[final] *v2* (draft).pdf (900 bytes)", rendered.textContent?.trim())
    }

    /**
     * Several attachments and prose in one document, together.
     *
     * Each of the tests above is one element alone in a surface, which is the
     * shape that works by accident. This is the shape a real description has, and
     * it is where a serialiser that collapses blocks or drops the text between
     * two links shows up.
     */
    @Test
    fun `attachments mixed with prose all survive`() {
        val markdown = listOf(
            "The crash log and a screenshot:",
            attachmentMarkdown("crash.log", "text/plain", 15_000, "1"),
            attachmentMarkdown("shot.png", "image/png", 2_000, "2"),
            "Reproduced on **every** build since Tuesday.",
        ).joinToString("\n\n")

        assertEquals(markdown, serialiseMarkdown(surfaceWith(markdown)))
    }

    // ── What the reader actually gets ────────────────────────────────────────

    /**
     * The rendered attachment is a download, and says what it is.
     *
     * `download` rather than `target=_blank` is not cosmetic: the URL answers
     * with `Content-Disposition: attachment`, so a new tab would open, download
     * and shut — which reads as a broken link. And the filename and size must be
     * *in the text*, because that is the only thing a reader has to decide on
     * before clicking.
     */
    @Test
    fun `a rendered file attachment is a download link showing name and size`() {
        val host = document.createElement("div") as HTMLElement
        host.innerHTML = renderMarkdown(attachmentMarkdown("notes.zip", "application/zip", 5_242_880, "4"))

        val anchor = host.querySelector("a") ?: error("No anchor was rendered at all")
        assertEquals("/api/attachments/4", anchor.getAttribute("href"))
        assertTrue(anchor.hasAttribute("download"), "Not marked as a download")
        assertEquals("attachment", anchor.getAttribute("class"))
        assertEquals("notes.zip (5.0 MB)", anchor.textContent)
        // A download that opened a tab would flash and vanish. See attachmentLink.
        assertEquals(null, anchor.getAttribute("target"))
    }

    /**
     * An ordinary link is left alone.
     *
     * The attachment branch keys off the URL, so the risk it introduces is
     * over-reach: every link in every issue going through it. A link to
     * somewhere else must still open in a tab, with `rel` intact — and must not
     * offer to download a page from a host we do not control.
     */
    @Test
    fun `an ordinary link is not turned into a download`() {
        val host = document.createElement("div") as HTMLElement
        host.innerHTML = renderMarkdown("[the spec](https://example.com/spec)")

        val anchor = host.querySelector("a") ?: error("No anchor was rendered")
        assertEquals("_blank", anchor.getAttribute("target"))
        assertTrue(!anchor.hasAttribute("download"), "An off-site link was marked as a download")
        assertEquals(null, anchor.getAttribute("class"))
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    /**
     * A contenteditable surface holding [markdown], built the way the editor
     * builds one.
     *
     * Through [renderMarkdown] and `innerHTML`, which is exactly what
     * `MarkdownEditor.setValue` does — the same function, the same assignment. A
     * fixture that assembled the elements by hand would be asserting that the
     * serialiser can read a DOM this test wrote, which is not a question anyone
     * has.
     *
     * Attached to the document rather than left floating: a detached tree answers
     * some layout questions differently, and a test that only passes off-screen
     * is not evidence about the editor.
     */
    private fun surfaceWith(markdown: String): HTMLElement {
        val surface = document.createElement("div") as HTMLElement
        surface.className = "editor-surface markdown"
        surface.contentEditable = "true"
        surface.innerHTML = renderMarkdown(markdown)
        document.body?.appendChild(surface)
        return surface
    }
}
