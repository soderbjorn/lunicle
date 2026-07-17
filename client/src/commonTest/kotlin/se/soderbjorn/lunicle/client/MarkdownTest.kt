/**
 * Tests for the markdown renderer.
 *
 * Weighted deliberately towards the attacks rather than the formatting: getting
 * bold wrong is a cosmetic bug someone reports, and getting the escaping wrong
 * is stored XSS in a field every user reads. The formatting tests are here so
 * the escaping tests cannot be trivially satisfied by rendering nothing.
 *
 * @see renderMarkdown
 */
package se.soderbjorn.lunicle.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownTest {

    // ── The reason this file exists ──────────────────────────────────────────

    @Test
    fun `script tags are escaped, not executed`() {
        val out = renderMarkdown("<script>alert(document.cookie)</script>")
        assertFalse("<script" in out, "A script tag reached the output: $out")
        assertTrue("&lt;script&gt;" in out, "Expected the tag escaped, got: $out")
    }

    @Test
    fun `img onerror is escaped`() {
        // The classic payload for a renderer that allows "just a little HTML".
        val out = renderMarkdown("""<img src=x onerror="alert(1)">""")
        assertFalse("onerror" in out && "<img" in out, "An executable img reached the output: $out")
    }

    @Test
    fun `javascript URLs in links are refused`() {
        val out = renderMarkdown("[click me](javascript:alert(1))")
        assertFalse("javascript:" in out, "A javascript: URL reached an href: $out")
        assertTrue("click me" in out, "The label should survive as inert text: $out")
        assertFalse("<a " in out, "No anchor should be emitted at all: $out")
    }

    @Test
    fun `javascript URLs are refused regardless of casing or padding`() {
        // A lowercase-only check is defeated by "JavaScript:", and a
        // startsWith check by a leading space. Both are one-liners to get wrong.
        listOf(
            "[x](JavaScript:alert(1))",
            "[x](  javascript:alert(1))",
            "[x](JAVASCRIPT:alert(1))",
        ).forEach { input ->
            val out = renderMarkdown(input)
            assertFalse("<a " in out, "Anchor emitted for $input: $out")
        }
    }

    @Test
    fun `data URLs are refused`() {
        val out = renderMarkdown("[x](data:text/html,<script>alert(1)</script>)")
        assertFalse("<a " in out, "A data: URL reached an href: $out")
    }

    @Test
    fun `an image with a javascript src renders as its alt text`() {
        val out = renderMarkdown("![boom](javascript:alert(1))")
        assertFalse("<img" in out, "An img with a javascript: src was emitted: $out")
        assertTrue("boom" in out)
    }

    @Test
    fun `quotes in alt text cannot break out of the attribute`() {
        val out = renderMarkdown("""![a" onerror="alert(1)](/api/attachments/1)""")
        assertFalse("onerror=\"alert(1)\"" in out, "Broke out of the alt attribute: $out")
        assertTrue("&quot;" in out, "Expected the quote escaped: $out")
    }

    // ── The one tag that is allowed, and only in its exact form ──────────────

    @Test
    fun `underline is restored`() {
        assertEquals("<p>a <u>b</u> c</p>", renderMarkdown("a <u>b</u> c"))
    }

    @Test
    fun `an underline tag carrying an attribute stays escaped`() {
        // The restore is an exact-match substitution, so there is no attribute
        // to smuggle a handler into. This is the test that pins that.
        val out = renderMarkdown("""<u onmouseover="alert(1)">x</u>""")
        assertFalse("<u onmouseover" in out, "An attributed tag was restored: $out")
        assertTrue("&lt;u onmouseover" in out, "Expected it escaped: $out")
    }

    @Test
    fun `other html tags are not restored`() {
        listOf("<b>x</b>", "<iframe src=x>", "<style>a{}</style>", "<svg onload=alert(1)>").forEach { input ->
            val out = renderMarkdown(input)
            assertFalse(Regex("<(b|iframe|style|svg)").containsMatchIn(out), "$input was restored: $out")
        }
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    @Test
    fun `bold wins over italic`() {
        // Italic first would eat the inner pair and leave "*<em>x</em>*".
        assertEquals("<p><strong>x</strong></p>", renderMarkdown("**x**"))
    }

    @Test
    fun `italic renders`() {
        assertEquals("<p><em>x</em></p>", renderMarkdown("*x*"))
    }

    @Test
    fun `strikethrough renders`() {
        assertEquals("<p><del>x</del></p>", renderMarkdown("~~x~~"))
    }

    @Test
    fun `headings render at every level`() {
        assertEquals("<h1>A</h1>", renderMarkdown("# A"))
        assertEquals("<h6>A</h6>", renderMarkdown("###### A"))
        // Seven hashes is not a heading; there is no <h7>.
        assertTrue(renderMarkdown("####### A").startsWith("<p>"))
    }

    @Test
    fun `an image renders with the attachment URL the editor inserts`() {
        assertEquals(
            """<p><img src="/api/attachments/12" alt="shot.png"></p>""",
            renderMarkdown("![shot.png](/api/attachments/12)"),
        )
    }

    @Test
    fun `a link opens safely in a new tab`() {
        val out = renderMarkdown("[docs](https://lunamux.dev)")
        assertTrue("""href="https://lunamux.dev"""" in out, out)
        // Without noopener the opened page can navigate the tab that opened it.
        assertTrue("""rel="noopener noreferrer"""" in out, out)
    }

    @Test
    fun `an image beside a link does not confuse the two`() {
        val out = renderMarkdown("![a](/x) and [b](https://y.example)")
        assertTrue("<img" in out, out)
        assertTrue("<a " in out, out)
        // The bug this catches: the link rule matching an image's "[a](/x)" half
        // and leaving a stray "!" in front of an anchor.
        assertFalse("!<a" in out, "The image was rendered as ! plus a link: $out")
    }

    @Test
    fun `paragraphs split on a blank line and single newlines become breaks`() {
        assertEquals("<p>a<br>b</p>\n<p>c</p>", renderMarkdown("a\nb\n\nc"))
    }

    @Test
    fun `a bullet list renders`() {
        assertEquals("<ul><li>a</li><li>b</li></ul>", renderMarkdown("- a\n- b"))
    }

    @Test
    fun `empty input renders nothing`() {
        assertEquals("", renderMarkdown(""))
        assertEquals("", renderMarkdown("   \n\n  "))
    }

    // ── Backslash escapes ────────────────────────────────────────────────────
    //
    // The half of the WYSIWYG round-trip that lives on this side. The editor
    // shows no markup, so text that *contains* a marker can only survive by
    // being escaped on the way out — and these pin that it survives coming back
    // in. Each of these is a sentence someone could plausibly type into an
    // issue and be astonished to see change.

    @Test
    fun `an escaped asterisk is text, not italic`() {
        assertEquals("<p>2 * 3 * 4</p>", renderMarkdown("""2 \* 3 \* 4"""))
    }

    @Test
    fun `an escaped hash is not a heading`() {
        assertEquals("<p># 1 sold out</p>", renderMarkdown("""\# 1 sold out"""))
    }

    @Test
    fun `an escaped hyphen is not a list`() {
        assertEquals("<p>- not a list</p>", renderMarkdown("""\- not a list"""))
    }

    @Test
    fun `escaped brackets do not make a link`() {
        val out = renderMarkdown("""\[not a link\](https://x.example)""")
        assertFalse("<a " in out, "An escaped bracket still formed a link: $out")
        assertTrue("[not a link]" in out, out)
    }

    @Test
    fun `an escaped backslash is one backslash`() {
        assertEquals("""<p>a \ b</p>""", renderMarkdown("""a \\ b"""))
    }

    @Test
    fun `a backslash before an ordinary character is kept`() {
        // Swallowing it would corrupt a pasted Windows path, which is a thing
        // that goes in bug reports constantly.
        assertEquals("""<p>C:\Users\rob</p>""", renderMarkdown("""C:\Users\rob"""))
    }

    @Test
    fun `an escaped underline tag stays inert`() {
        // The ordering trap: if the escaped "<" resolved to "&lt;" before
        // restoreAllowedTags ran, it would sit beside the "&gt;" of the
        // unescaped ">" and be restored into a real tag — turning text someone
        // escaped precisely to keep inert back into markup.
        val out = renderMarkdown("""\<u>x\</u>""")
        assertFalse("<u>" in out, "An escaped tag was restored: $out")
        assertTrue("&lt;u&gt;" in out, out)
    }

    @Test
    fun `a private-use placeholder in the input cannot forge an escape`() {
        // The placeholder characters are an implementation detail of the escape
        // pass. An input carrying one must not come out the other end as the
        // character it stands for, having never been escaped.
        val out = renderMarkdown("a\uE000b\uE001c")
        assertFalse("*" in out || "\\" in out, "A placeholder resolved into a marker: $out")
        assertEquals("<p>abc</p>", out)
    }

    @Test
    fun `escapeMarkdown neutralises what the renderer acts on`() {
        assertEquals("""a \* b""", escapeMarkdown("a * b"))
        // Round-trip: escaped, then rendered, is the text you started with.
        assertEquals("<p>a * b</p>", renderMarkdown(escapeMarkdown("a * b")))
        assertEquals("<p>**bold**</p>", renderMarkdown(escapeMarkdown("**bold**")))
    }

    @Test
    fun `escapeMarkdownBlock only guards a line that starts with a marker`() {
        assertEquals("""\# heading-ish""", escapeMarkdownBlock("# heading-ish"))
        assertEquals("""\- item-ish""", escapeMarkdownBlock("- item-ish"))
        // A hyphen inside a word is a hyphen; escaping it would litter the
        // stored document for nothing.
        assertEquals("well-formed", escapeMarkdownBlock("well-formed"))
        assertEquals("a # b", escapeMarkdownBlock("a # b"))
    }

    @Test
    fun `ampersands are escaped once, not twice`() {
        // Double-escaping shows up as literal "&amp;amp;" on screen — the sort
        // of thing that survives review because it only appears with real data.
        assertEquals("<p>Tom &amp; Jerry</p>", renderMarkdown("Tom & Jerry"))
    }

    // ── Attachment links ─────────────────────────────────────────────────────

    /**
     * A link to one of our own uploads is drawn as a download.
     *
     * Asserted as the whole string, because the attributes are the feature: the
     * class is what the CSS draws the box off, and `download` is what stops the
     * click opening a tab that immediately shuts.
     */
    @Test
    fun `a link to an attachment renders as a download`() {
        assertEquals(
            """<p><a href="/api/attachments/12" class="attachment" download rel="noopener noreferrer">""" +
                """report.pdf (2.3 MB)</a></p>""",
            renderMarkdown("[report.pdf (2.3 MB)](/api/attachments/12)"),
        )
    }

    /** The branch keys off the URL, so the risk it adds is over-reach. */
    @Test
    fun `an ordinary link is untouched by the attachment branch`() {
        assertEquals(
            """<p><a href="https://example.com" target="_blank" rel="noopener noreferrer">x</a></p>""",
            renderMarkdown("[x](https://example.com)"),
        )
    }

    /**
     * Only a URL this app could have produced takes the attachment path.
     *
     * The prefix alone is not enough. A `javascript:` URL that merely *contains*
     * the prefix, or a path that walks out of it, must get a link's treatment —
     * or the attachment branch becomes a way to smuggle attributes onto an
     * anchor whose href was never checked the same way.
     */
    @Test
    fun `a URL that only resembles an attachment is not one`() {
        listOf(
            "[x](/api/attachments/)",
            "[x](/api/attachments/abc)",
            "[x](/api/attachments/../../secret)",
            "[x](/api/attachments/1/../2)",
            "[x](https://evil.example/api/attachments/1)",
        ).forEach { input ->
            assertFalse("""class="attachment"""" in renderMarkdown(input), "Treated as an attachment: $input")
        }
    }

    /**
     * The attachment path is still a path through the escaper.
     *
     * The label is user text — it is a filename — so a `"` or a `<` in it must
     * not reach the output as markup just because the anchor around it is drawn
     * differently. The one thing a new branch in a security file must not do is
     * quietly become a second, unescaped way out.
     */
    @Test
    fun `an attachment label is escaped like any other text`() {
        val out = renderMarkdown("""[<script>x</script>"](/api/attachments/1)""")
        assertFalse("<script" in out, "A script tag reached the output: $out")
        assertTrue("&lt;script&gt;" in out, "Expected the tag escaped, got: $out")
        assertTrue("&quot;" in out, "An unescaped quote in a label, next to attributes: $out")
    }
}
