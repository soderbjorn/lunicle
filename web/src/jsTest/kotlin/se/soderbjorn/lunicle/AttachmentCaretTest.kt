/**
 * An insert always has somewhere to land — asserted in a real browser.
 *
 * ── The bug this file exists to catch (LNL-160) ──────────────────────────────
 *
 * The editor inserts an uploaded file with `document.execCommand("insertHTML")`,
 * which acts on the current selection and, when that selection is not editable,
 * does *nothing whatsoever* — no exception, no false to notice, no mark on the
 * page. The Attach button restores a selection it saved before the file picker
 * opened, and that saved selection is only usually in the surface: press Edit and
 * then Attach without clicking in the description first and it is a paragraph
 * somewhere else on the page. The file uploaded, the row was written, and the
 * link was never inserted — an invisible attachment and an orphaned blob.
 *
 * So the assertion here is not "the caret is where we asked for it". It is the
 * post-condition that actually matters: **after [putCaretBackInto], an insert
 * lands in the surface**, whatever the caller handed it. Each test therefore ends
 * by running the same `insertHTML` the editor runs and looking for the result in
 * the surface, rather than by inspecting a Range and hoping.
 *
 * In a browser, via `js { browser() }`, because selections, focus and
 * `execCommand` are browser behaviour; a fake DOM would answer about the fake.
 *
 * @see putCaretBackInto
 * @see MarkdownEditor
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentCaretTest {

    private val built = mutableListOf<HTMLElement>()

    @AfterTest
    fun removeFixtures() {
        built.forEach { it.remove() }
        built.clear()
    }

    // ── The reason this file exists ──────────────────────────────────────────

    /**
     * A saved caret from somewhere else on the page does not swallow the insert.
     *
     * The reported failure exactly: the selection when Attach was pressed was in
     * a paragraph of the issue behind the editor, and restoring it verbatim sent
     * `insertHTML` at read-only text, where it evaporated.
     */
    @Test
    fun `an insert lands in the surface even when the saved caret was elsewhere`() {
        val surface = surfaceWith("<p>Already written.</p>")
        val elsewhere = paragraphOutside("Some text in the issue behind the editor")

        putCaretBackInto(surface, rangeOver(elsewhere))
        insertMarker()

        assertTrue(
            surface.textContent.orEmpty().contains(MARKER),
            "The insert did not land in the surface: '${surface.textContent}'",
        )
        // And it did not land in the innocent paragraph either, which is the
        // other way this could "pass" — an editable caret in the wrong document.
        assertTrue(MARKER !in elsewhere.textContent.orEmpty(), "The insert landed outside the editor")
    }

    /**
     * With nothing saved at all, the insert still lands.
     *
     * The state on a freshly-mounted editor nobody has clicked in yet: there is
     * no range to restore, and the old code returned early and left the caret
     * wherever the page happened to have it.
     */
    @Test
    fun `an insert lands in the surface when nothing was saved`() {
        val surface = surfaceWith("<p>Already written.</p>")
        select(rangeOver(paragraphOutside("Text somebody selected a moment ago")))

        putCaretBackInto(surface, null)
        insertMarker()

        assertTrue(surface.textContent.orEmpty().contains(MARKER), "The insert did not land in the surface")
    }

    /**
     * The fallback caret is the *end*, so an attachment appends.
     *
     * Not a detail: an attachment that lands at the top pushes itself in front of
     * the sentence explaining it, and the user has to move it by hand every time.
     */
    @Test
    fun `the fallback caret is at the end of what is already there`() {
        val surface = surfaceWith("<p>First.</p><p>Last.</p>")

        putCaretBackInto(surface, rangeOver(paragraphOutside("Elsewhere")))
        insertMarker()

        assertTrue(
            surface.textContent.orEmpty().endsWith(MARKER),
            "Appended somewhere other than the end: '${surface.textContent}'",
        )
    }

    /**
     * A caret that *was* in the surface is still honoured to the character.
     *
     * The guard is a filter, and the risk a filter carries is over-reach: if it
     * dropped every saved range, an attachment dropped into the middle of a
     * description would jump to the end and the drop gesture would be a lie.
     */
    @Test
    fun `a saved caret inside the surface is used exactly`() {
        val surface = surfaceWith("<p>First.</p><p>Last.</p>")
        val firstParagraph = surface.firstElementChild as HTMLElement

        val range = document.createRange().asDynamic()
        range.setStart(firstParagraph.firstChild, 0)
        range.collapse(true)

        putCaretBackInto(surface, range)
        insertMarker()

        assertEquals("${MARKER}First.Last.", surface.textContent, "The saved caret was not honoured")
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    /**
     * An editing surface holding [html], built and attached the way the editor
     * builds one.
     *
     * Attached to the document rather than left floating because focus and
     * selection are document-wide facts: a detached contenteditable cannot be
     * focused, so a test on one would be asserting about nothing.
     */
    private fun surfaceWith(html: String): HTMLElement {
        val surface = document.createElement("div") as HTMLElement
        surface.className = "editor-surface markdown"
        surface.contentEditable = "true"
        surface.innerHTML = html
        document.body?.appendChild(surface)
        built += surface
        return surface
    }

    /** Read-only prose elsewhere on the page — the issue behind the editor. */
    private fun paragraphOutside(text: String): HTMLElement {
        val paragraph = document.createElement("p") as HTMLElement
        paragraph.textContent = text
        document.body?.appendChild(paragraph)
        built += paragraph
        return paragraph
    }

    /**
     * A range spanning [element].
     *
     * Written out rather than through `also`: the receiver is `dynamic`, and a
     * scope function on one compiles to `range.also(...)` — a member call no
     * Range has. See the same note in MarkdownEditor.
     */
    private fun rangeOver(element: HTMLElement): dynamic {
        val range = document.createRange().asDynamic()
        range.selectNodeContents(element)
        return range
    }

    /** Make [range] the page's selection, as a click would have. */
    private fun select(range: dynamic) {
        val selection = window.asDynamic().getSelection()
        selection.removeAllRanges()
        selection.addRange(range)
    }

    /** The editor's own insert, run on whatever selection is current. */
    private fun insertMarker() {
        document.asDynamic().execCommand("insertHTML", false, MARKER)
    }

    private companion object {
        /** Plain text with no markup, so a failure is never an escaping question. */
        const val MARKER = "INSERTED"
    }
}
