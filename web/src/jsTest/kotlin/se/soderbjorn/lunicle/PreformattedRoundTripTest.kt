/**
 * serialise ∘ render is identity for a code fence and for a diagram (LNL-181).
 *
 * ── The bug this file exists to catch ────────────────────────────────────────
 *
 * The renderer gained two block-level constructs, and both of them are made of
 * whitespace. Everything else the serialiser handles is prose, where leading
 * spaces are noise and trimming them is right — which is exactly what the
 * generic block branch does, and what would have quietly destroyed both:
 *
 *  - a **fence** is written by its author, and a serialiser that dropped the
 *    ``` would leave the code sample's survival to a heuristic — so a sample
 *    that no longer looked like a diagram would decay into prose the first time
 *    anyone opened the description and typed a character, taking its indentation
 *    with it;
 *  - a **diagram** was never marked up at all, so it survives only if the
 *    markdown written back still *reads* as one. That is a real claim about the
 *    escaping — [escapeMarkdown] adds backslashes, which would shift every
 *    column right — and the only honest way to test it is to render, serialise,
 *    and render again.
 *
 * Neither failure raises anything. The user finds out when the table they pasted
 * has become a paragraph of run-together words.
 *
 * So every test here is the round trip, and the assertion is **byte-identical**
 * markdown, then the same DOM out the far side. In a browser, via
 * `js { browser() }`, because the subject is a DOM the serialiser walks.
 *
 * @see serialiseMarkdown
 * @see se.soderbjorn.lunicle.client.renderMarkdown
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.client.renderMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreformattedRoundTripTest {

    // ── Fences ───────────────────────────────────────────────────────────────

    @Test
    fun `a fenced block survives the round trip, byte-identical`() {
        val markdown = "```\nfun main() {\n    println(1)\n}\n```"
        assertEquals(markdown, serialiseMarkdown(surfaceWith(markdown)))
    }

    @Test
    fun `a fence keeps its language`() {
        // Dropped, this is a silent downgrade: the block still renders, so
        // nothing looks broken, and the tag is simply gone.
        val markdown = "```kotlin\nval x = 1\n```"
        assertEquals(markdown, serialiseMarkdown(surfaceWith(markdown)))
    }

    @Test
    fun `markdown markers inside a fence are not escaped on the way out`() {
        // The whole point of a fence: what is inside it is not markdown, so the
        // serialiser must not add the backslashes it adds everywhere else.
        val markdown = "```\n**not bold** and *not italic*\n# not a heading\n```"
        assertEquals(markdown, serialiseMarkdown(surfaceWith(markdown)))
    }

    @Test
    fun `prose around a fence keeps its place`() {
        val markdown = "Before it.\n\n```\ncode\n```\n\nAfter it."
        assertEquals(markdown, serialiseMarkdown(surfaceWith(markdown)))
    }

    // ── Diagrams ─────────────────────────────────────────────────────────────

    @Test
    fun `a box-drawn table renders identically after a round trip`() {
        val markdown = "┌──────┬────────┐\n│ Key  │ Status │\n└──────┴────────┘"
        val surface = surfaceWith(markdown)
        val before = surface.innerHTML

        // Serialised markdown may differ from the input — the escaping is
        // allowed to add backslashes — but what it *renders* to must not.
        val serialised = serialiseMarkdown(surface)
        assertEquals(before, renderMarkdown(serialised), "The drawing changed on a round trip")
        assertTrue("<pre>" in renderMarkdown(serialised), "And it is still a drawing: $serialised")
    }

    @Test
    fun `a space-aligned table keeps its columns through two round trips`() {
        // Twice, because the failure mode is drift: escaping that shifts a
        // column by one character survives the first pass looking fine and stops
        // being recognised on the second.
        val markdown = listOf(
            "Name" + " ".repeat(8) + "Status",
            "----" + " ".repeat(8) + "------",
            "foo" + " ".repeat(9) + "open",
        ).joinToString("\n")

        val once = serialiseMarkdown(surfaceWith(markdown))
        val twice = serialiseMarkdown(surfaceWith(once))
        assertEquals(once, twice, "The document is still moving after one pass")

        val rendered = document.createElement("div") as HTMLElement
        rendered.innerHTML = renderMarkdown(twice)
        assertTrue("<pre>" in rendered.innerHTML, "Stopped being a drawing: $twice")
        assertEquals(markdown, rendered.textContent, "The columns moved: $twice")
    }

    @Test
    fun `a diagram containing markdown markers keeps its width`() {
        // The characters escapeMarkdown puts a backslash in front of, in the one
        // place where a backslash would push everything after it out of line.
        val markdown = "| *a* | [b] |\n|-----|-----|"
        val surface = surfaceWith(markdown)
        val before = surface.innerHTML

        val serialised = serialiseMarkdown(surface)
        assertTrue("\\*a\\*" in serialised, "The markers must be escaped in the store: $serialised")
        assertEquals(before, renderMarkdown(serialised), "And render back to the same width")
    }

    @Test
    fun `prose around a diagram is still prose`() {
        val markdown = "Here is the layout:\n┌───┐\n└───┘\nand that is it."
        val rendered = document.createElement("div") as HTMLElement
        rendered.innerHTML = renderMarkdown(serialiseMarkdown(surfaceWith(markdown)))

        assertTrue("<p>Here is the layout:</p>" in rendered.innerHTML, rendered.innerHTML)
        assertTrue("<pre>┌───┐\n└───┘</pre>" in rendered.innerHTML, rendered.innerHTML)
        assertTrue("<p>and that is it.</p>" in rendered.innerHTML, rendered.innerHTML)
    }

    @Test
    fun `an ordinary paragraph is untouched by any of this`() {
        // The regression guard: none of the above may change what a description
        // with no diagram and no fence in it does.
        val markdown = "A sentence.\nAnd another one, with a - dash in it."
        assertEquals(markdown, serialiseMarkdown(surfaceWith(markdown)))
    }

    /**
     * The editor's surface, holding [markdown] the way the editor puts it there.
     *
     * Through [renderMarkdown] and `innerHTML`, which is exactly what
     * `MarkdownEditor.setValue` does. Attached to the document, for the reason
     * given in [AttachmentRoundTripTest].
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
