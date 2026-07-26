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
        // The brackets stay literal and the label stays text, which is what the
        // escape was for. The URL beside them is now bare prose, and bare prose
        // that spells a URL is autolinked — so the assertion is that the *label*
        // never became the link, not that nothing did.
        assertTrue("[not a link]" in out, out)
        assertFalse(">not a link<" in out, "The escaped label still became link text: $out")
        assertTrue("""href="https://x.example"""" in out, out)
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
     *
     * `/api/attachments/abc` used to be on this list and is deliberately not any
     * more: the guard was "all digits" while the URL carried the row id, and
     * LNL-51 made ids base64url, so `abc` is now a perfectly well-formed one that
     * simply names no row. Nothing was weakened by that — every entry below fails
     * on a character base64url does not contain (`/`, `.`) or on being empty, so
     * the traversal spellings this test was written for are rejected exactly as
     * before. See `isAttachmentUrl` and `ApiRoutes.isAttachmentId`.
     */
    @Test
    fun `a URL that only resembles an attachment is not one`() {
        listOf(
            "[x](/api/attachments/)",
            "[x](/api/attachments/../../secret)",
            "[x](/api/attachments/1/../2)",
            "[x](/api/attachments/a.b)",
            "[x](/api/attachments/one two)",
            "[x](https://evil.example/api/attachments/1)",
        ).forEach { input ->
            assertFalse("""class="attachment"""" in renderMarkdown(input), "Treated as an attachment: $input")
        }
    }

    /**
     * The other half of the guard: an opaque id IS an attachment.
     *
     * Sibling to the test above, and the one that would have caught LNL-51's
     * regression rather than merely surviving it. Before that change the renderer
     * asked for digits, so a real base64url id — letters, `-`, `_` — fell through
     * to the ordinary-link branch and every newly uploaded file rendered as a bare
     * link with no filename, size or download affordance.
     */
    @Test
    fun `an opaque attachment id is recognised`() {
        listOf("kQ7mVx-4Zt_Ab1CdEfGhIj", "12", "a-_Z9").forEach { id ->
            assertTrue(
                """class="attachment"""" in renderMarkdown("[notes.zip (1.0 kB)](/api/attachments/$id)"),
                "Not treated as an attachment: $id",
            )
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

    // ── Mentions ─────────────────────────────────────────────────────────────

    @Test
    fun `a braced mention renders as a name with no braces in sight`() {
        val out = renderMarkdown("hi @{Ada Lovelace}, look")
        assertTrue("""<span class="mention" data-mention="Ada Lovelace">@Ada Lovelace</span>""" in out, out)
        assertFalse("{" in out, "The syntax reached the reader: $out")
        assertFalse("}" in out, "The syntax reached the reader: $out")
    }

    @Test
    fun `a bare mention renders the same way`() {
        // Both spellings have to look identical, or which one the writer used
        // becomes visible to the reader — which is nobody's business but the
        // document's.
        val out = renderMarkdown("thanks @grace")
        assertTrue("""<span class="mention" data-mention="grace">@grace</span>""" in out, out)
    }

    @Test
    fun `an e-mail address does not render as a mention`() {
        val out = renderMarkdown("write to ada@lovelace.org")
        assertFalse("mention" in out, "An address was drawn as a mention: $out")
    }

    @Test
    fun `a mention's name is escaped like any other text`() {
        // The name lands in an attribute AND in element text, so a quote or an
        // angle bracket in a display name must not become markup in either.
        val out = renderMarkdown("""@{<script>"x}""")
        assertFalse("<script" in out, "A script tag reached the output: $out")
        assertTrue("&lt;script&gt;" in out, "Expected the tag escaped, got: $out")
        assertTrue("&quot;" in out, "An unescaped quote inside an attribute: $out")
    }

    @Test
    fun `a mention inside an href is not drawn as one`() {
        // "…/@ada" is an ordinary profile URL, and autolinking puts it inside an
        // attribute. A chip written there is a <span> in the middle of an href —
        // broken markup assembled from a user-supplied string, which is the one
        // thing this file exists to never do.
        val out = renderMarkdown("see https://example.com/@ada for details")
        assertTrue("""href="https://example.com/@ada"""" in out, "The href was rewritten: $out")
    }

    // ── Bare URLs ────────────────────────────────────────────────────────────

    @Test
    fun `a bare URL becomes a link`() {
        val out = renderMarkdown("see https://example.com for details")
        assertTrue(
            """<a href="https://example.com" target="_blank" rel="noopener noreferrer">https://example.com</a>""" in out,
            out,
        )
    }

    @Test
    fun `a full stop after a bare URL stays in the sentence`() {
        // The greedy match takes the stop; splitUrlTail has to give it back, or
        // every URL ending a sentence links to a 404 with a dot on the end.
        val out = renderMarkdown("read https://example.com/page.")
        assertTrue("""href="https://example.com/page"""" in out, "The stop reached the href: $out")
        assertTrue(out.endsWith("</a>.</p>"), "The stop left the sentence: $out")
    }

    @Test
    fun `a bare URL in brackets keeps its closing bracket out of the href`() {
        val out = renderMarkdown("(see https://example.com)")
        assertTrue("""href="https://example.com"""" in out, "The bracket reached the href: $out")
        assertTrue(")" in out.substringAfter("</a>"), "The bracket vanished: $out")
    }

    @Test
    fun `a bare URL keeps balanced parentheses`() {
        // Wikipedia's shape. The trailing bracket closes one the URL opened, so
        // trimming it would break the link it was supposed to rescue.
        val out = renderMarkdown("https://en.wikipedia.org/wiki/Foo_(disambiguation)")
        assertTrue("""href="https://en.wikipedia.org/wiki/Foo_(disambiguation)"""" in out, out)
    }

    @Test
    fun `a quoted bare URL does not swallow the escaped quote`() {
        // By this point a `"` is the five characters `&quot;`, so a trim that
        // only looked at the last character would leave the entity in the href.
        val out = renderMarkdown("""he said "https://example.com" once""")
        assertTrue("""href="https://example.com"""" in out, "An entity reached the href: $out")
    }

    @Test
    fun `a written link is not autolinked a second time`() {
        // The bare-URL branch must never see a URL that is already spoken for:
        // matching inside the parentheses would leave an <a> between literal
        // brackets, and matching inside the emitted href would nest one in an
        // attribute.
        val out = renderMarkdown("[the docs](https://example.com)")
        assertEquals(1, out.split("<a ").size - 1, "The link was rendered twice: $out")
        assertTrue(">the docs</a>" in out, out)
        assertFalse("[" in out, "The markdown syntax survived: $out")
    }

    @Test
    fun `an image URL is not autolinked`() {
        val out = renderMarkdown("![a picture](https://example.com/p.png)")
        assertFalse("<a " in out, "An image was turned into a link: $out")
        assertTrue("""<img src="https://example.com/p.png" alt="a picture">""" in out, out)
    }

    @Test
    fun `a bare javascript URL stays text`() {
        // Not a bare URL to this rule at all — the pattern only knows the two
        // schemes it is willing to link — so it never reaches an href.
        val out = renderMarkdown("javascript:alert(1)")
        assertFalse("<a " in out, "A javascript: URL was linked: $out")
    }

    // ── Bare URLs with no scheme ─────────────────────────────────────────────

    @Test
    fun `a www URL is linked, and gets a scheme only in the href`() {
        // The href needs one or the browser reads it as a path on our own host;
        // the text is what the reader typed.
        val out = renderMarkdown("see www.aftonbladet.se for details")
        assertTrue(
            """<a href="https://www.aftonbladet.se" target="_blank" rel="noopener noreferrer">www.aftonbladet.se</a>""" in out,
            out,
        )
        assertTrue("see " in out && " for details" in out, "The prose around it was eaten: $out")
    }

    @Test
    fun `a www URL opening a sentence is linked`() {
        // The word-boundary group has nothing to match here, so `^` has to carry
        // it — the whole branch is dead at position 0 otherwise.
        val out = renderMarkdown("www.example.com is the site")
        assertTrue("""href="https://www.example.com"""" in out, out)
    }

    @Test
    fun `a www URL is case-insensitive`() {
        val out = renderMarkdown("Www.Example.com opens the sentence")
        assertTrue("""href="https://Www.Example.com"""" in out, out)
    }

    @Test
    fun `a full stop after a www URL stays in the sentence`() {
        val out = renderMarkdown("read www.example.com.")
        assertTrue("""href="https://www.example.com"""" in out, "The stop reached the href: $out")
        assertTrue(out.endsWith("</a>.</p>"), "The stop left the sentence: $out")
    }

    @Test
    fun `a www host inside a full URL is not linked twice`() {
        // The scheme branch matches first and consumes the whole URL, so the www
        // branch never starts inside it — and the href keeps the typed scheme.
        val out = renderMarkdown("see http://www.example.com now")
        assertEquals(1, out.split("<a ").size - 1, "It was linked twice: $out")
        assertTrue("""href="http://www.example.com"""" in out, "The scheme was rewritten: $out")
    }

    @Test
    fun `a www host in an e-mail address is not linked`() {
        // The `@` is exactly what the boundary group exists to refuse: linking
        // the tail would split an address in half.
        val out = renderMarkdown("write to ada@www.example.com today")
        assertFalse("<a " in out, "An address was half-linked: $out")
    }

    @Test
    fun `a written link whose URL has no scheme is not autolinked as well`() {
        val out = renderMarkdown("[the paper](www.example.com)")
        assertEquals(1, out.split("<a ").size - 1, "The link was rendered twice: $out")
        assertTrue(">the paper</a>" in out, out)
    }

    @Test
    fun `a written link to a www host leaves our own site`() {
        // A relative href is legal — every attachment is one — so nothing refuses
        // this; it just quietly resolves against our host and 404s on click.
        val out = renderMarkdown("[the paper](www.example.com)")
        assertTrue("""href="https://www.example.com"""" in out, out)
    }

    @Test
    fun `a written link to a relative path stays relative`() {
        // The other half of that rule: only `www.` is treated as a host. A word
        // with a dot in it is far more often a path.
        val out = renderMarkdown("[the file](/api/attachments/7)")
        assertTrue("""href="/api/attachments/7"""" in out, out)
    }

    @Test
    fun `an image whose URL has no scheme is not autolinked`() {
        val out = renderMarkdown("![a picture](www.example.com/p.png)")
        assertFalse("<a " in out, "An image was turned into a link: $out")
    }

    @Test
    fun `a bare www with no host stays text`() {
        // "see www." ends a sentence; there is no link in it to make.
        val out = renderMarkdown("and so on, see www.")
        assertFalse("<a " in out, "An empty host was linked: $out")
    }

    @Test
    fun `a word ending in www is not a link`() {
        val out = renderMarkdown("the file iswww.txt here")
        assertFalse("<a " in out, "A word was cut in half: $out")
    }

    // ── renderInlineLinks: titles are plain text that only autolinks (LNL-112) ──

    @Test
    fun `an inline bare URL becomes a link`() {
        val out = renderInlineLinks("Ship https://example.com by Friday")
        assertTrue(
            """<a href="https://example.com" target="_blank" rel="noopener noreferrer">https://example.com</a>""" in out,
            out,
        )
    }

    @Test
    fun `an inline www URL gets a scheme only in the href`() {
        val out = renderInlineLinks("Read www.example.com")
        assertTrue("""href="https://www.example.com"""" in out, out)
        assertTrue(">www.example.com</a>" in out, "The text should stay as written: $out")
    }

    @Test
    fun `a title with no URL is returned as plain text`() {
        val out = renderInlineLinks("Fix the login button")
        assertEquals("Fix the login button", out)
    }

    @Test
    fun `markdown formatting in a title is left literal`() {
        // A title has no editor behind it, so "**" and "#" are just characters.
        assertEquals("**bold** and *italic*", renderInlineLinks("**bold** and *italic*"))
        assertEquals("# not a heading", renderInlineLinks("# not a heading"))
    }

    @Test
    fun `a markdown link in a title stays as written`() {
        // Brackets a user typed are brackets, not a hidden-URL link.
        val out = renderInlineLinks("See [the docs](https://example.com)")
        assertFalse("<a " in out, "A plain-text title should not honour link syntax: $out")
        assertTrue("[the docs]" in out, out)
    }

    @Test
    fun `an image in a title does not become an img`() {
        val out = renderInlineLinks("![logo](https://example.com/l.png)")
        assertFalse("<img" in out, "A title must not draw an image: $out")
    }

    @Test
    fun `html in a title is escaped, not executed`() {
        val out = renderInlineLinks("<script>alert(1)</script>")
        assertFalse("<script" in out, "A script tag reached a title: $out")
        assertTrue("&lt;script&gt;" in out, out)
    }

    @Test
    fun `a javascript URL in a title stays text`() {
        val out = renderInlineLinks("javascript:alert(1)")
        assertFalse("<a " in out, "A javascript: URL was linked from a title: $out")
    }

    @Test
    fun `a mention in a title is not drawn as one`() {
        // Titles have no mention machinery; the syntax is just text.
        val out = renderInlineLinks("Ping @{Ada Lovelace} about this")
        assertFalse("mention" in out, out)
        assertFalse("<span" in out, out)
    }

    @Test
    fun `a full stop after a bare URL in a title stays in the sentence`() {
        val out = renderInlineLinks("Down: www.example.com.")
        assertTrue("""href="https://www.example.com"""" in out, "The stop reached the href: $out")
        assertTrue(out.endsWith("</a>."), "The stop left the sentence: $out")
    }

    // ── Ticket references: PREFIX-NUMBER → a link to the issue (LNL-139) ─────────

    @Test
    fun `a ticket reference in a description becomes a link`() {
        val out = renderMarkdown("Blocked by LNL-12, see there", listOf("LNL"))
        assertTrue(
            """<a href="?issue=LNL-12" class="ticket-ref" data-ticket="LNL-12">LNL-12</a>""" in out,
            "Expected a ticket link, got: $out",
        )
    }

    @Test
    fun `a reference is linked only for an accessible project`() {
        // A prefix outside the reader's projects promises a destination that is not
        // there, so it stays text (LNL-139).
        val out = renderMarkdown("See LNL-12 and ABC-99", listOf("LNL"))
        assertTrue("data-ticket=\"LNL-12\"" in out, "An accessible ref should link: $out")
        assertTrue("ABC-99" in out, "The inaccessible ref should survive as text: $out")
        assertFalse("data-ticket=\"ABC-99\"" in out, "An inaccessible prefix must not link: $out")
    }

    @Test
    fun `references to any accessible project are linked, across projects`() {
        // The whole of this change: a ref to another project links too, and the
        // click (navigateToTicket) switches project to reach it.
        val out = renderMarkdown("LNL-12 depends on ABC-9", listOf("LNL", "ABC"))
        assertTrue("data-ticket=\"LNL-12\"" in out, "The home project's ref links: $out")
        assertTrue("data-ticket=\"ABC-9\"" in out, "Another accessible project's ref links too: $out")
    }

    @Test
    fun `with no project prefix nothing is linked as a ticket`() {
        // A forum message and the editor's live copy both render this way.
        val out = renderMarkdown("Blocked by LNL-12")
        assertFalse("ticket-ref" in out, "A prefix-less render must link no tickets: $out")
        assertTrue("LNL-12" in out, "The text should still be there: $out")
    }

    @Test
    fun `a reference matches its prefix case-insensitively`() {
        val out = renderMarkdown("fixed in lnl-7", listOf("LNL"))
        assertTrue("data-ticket=\"LNL-7\"" in out, "The href should be canonical: $out")
        assertTrue(">lnl-7</a>" in out, "The label should stay as written: $out")
    }

    @Test
    fun `a reference in a title is linked too`() {
        val out = renderInlineLinks("Follow-up to LNL-3", listOf("LNL"))
        assertTrue("data-ticket=\"LNL-3\"" in out, "A card/issue title should link its refs: $out")
    }

    @Test
    fun `a reference inside a URL is not double-linked`() {
        // The path segment LNL-12 must stay inside the href, not spawn a nested <a>.
        val out = renderMarkdown("https://example.com/LNL-12", listOf("LNL"))
        assertFalse("ticket-ref" in out, "A URL path is not a ticket reference: $out")
        assertEquals(1, "<a ".findAllCount(out), "Exactly one anchor — the URL: $out")
    }

    @Test
    fun `a reference used as a link label does not nest anchors`() {
        val out = renderMarkdown("[LNL-12](https://example.com)", listOf("LNL"))
        assertFalse("ticket-ref" in out, "No ticket link inside a written link: $out")
        assertEquals(1, "<a ".findAllCount(out), "One anchor only — anchors must not nest: $out")
    }

    @Test
    fun `a reference inside inline code stays literal`() {
        val out = renderMarkdown("`LNL-12`", listOf("LNL"))
        assertFalse("ticket-ref" in out, "Code is shown literally, never linked: $out")
        assertTrue("<code>LNL-12</code>" in out, "The code span should be intact: $out")
    }

    @Test
    fun `an at-mention that reads like a ticket stays a mention`() {
        val out = renderMarkdown("@LNL-12", listOf("LNL"))
        assertFalse("ticket-ref" in out, "@LNL-12 is a mention, not an issue link: $out")
        assertTrue("mention" in out, "It should still render as a mention: $out")
    }

    @Test
    fun `a reference inside bold is still linked`() {
        val out = renderMarkdown("**LNL-12**", listOf("LNL"))
        assertTrue("<strong>" in out, "The bold should survive: $out")
        assertTrue("ticket-ref" in out, "Emphasis does not block a reference: $out")
    }

    @Test
    fun `a prefix that only starts a longer word is not a reference`() {
        assertFalse("ticket-ref" in renderMarkdown("SUPERLNL-12", listOf("LNL")), "A prefix mid-word is not one")
        assertFalse("ticket-ref" in renderMarkdown("LNL-12abc", listOf("LNL")), "A number mid-word is not one")
    }

    @Test
    fun `a reference render owes no new escaping`() {
        // The label is already-escaped text and the href/data are prefix + digits,
        // so a description that both references a ticket and carries a script tag is
        // still safe on both counts.
        val out = renderMarkdown("<script>alert(1)</script> re LNL-1", listOf("LNL"))
        assertFalse("<script" in out, "The tag must still be inert: $out")
        assertTrue("data-ticket=\"LNL-1\"" in out, "The reference still links: $out")
    }

    // ── Expanding the title after a reference (LNL-144) ─────────────────────────

    @Test
    fun `a known title is appended inside the same anchor`() {
        val out = renderMarkdown("Blocked by LNL-12", listOf("LNL")) { "Fix the thing" }
        assertTrue(
            """data-ticket="LNL-12">LNL-12<span class="ticket-ref-title">: Fix the thing</span></a>""" in out,
            "The title should read after the key, inside the one anchor: $out",
        )
        assertEquals(1, "<a ".findAllCount(out), "Still exactly one anchor: $out")
    }

    @Test
    fun `a title is expanded in a plain-text title too`() {
        val out = renderInlineLinks("Follow-up to LNL-3", listOf("LNL")) { "Ship it" }
        assertTrue(
            """>LNL-3<span class="ticket-ref-title">: Ship it</span></a>""" in out,
            "A card/issue title should expand its refs (LNL-144): $out",
        )
    }

    @Test
    fun `an unknown or blank title leaves the reference bare`() {
        // The default lookup and any surface without title data both render this way,
        // exactly as before LNL-144 — the reference is a link, with no title after it.
        assertFalse("ticket-ref-title" in renderMarkdown("See LNL-9", listOf("LNL")), "Default expands nothing")
        assertFalse(
            "ticket-ref-title" in renderMarkdown("See LNL-9", listOf("LNL")) { "" },
            "A blank title expands nothing",
        )
        assertFalse(
            "ticket-ref-title" in renderMarkdown("See LNL-9", listOf("LNL")) { null },
            "A null title expands nothing",
        )
    }

    @Test
    fun `an expanded title is HTML-escaped`() {
        // A title is arbitrary user text — the one thing in the anchor not escaped by
        // the up-front pass — so a script tag in an issue's title must stay inert when
        // another issue references it.
        val out = renderMarkdown("See LNL-1", listOf("LNL")) { "<script>alert(1)</script>" }
        assertFalse("<script" in out, "The title's markup must be inert: $out")
        assertTrue("&lt;script&gt;" in out, "It should render as escaped text: $out")
    }

    @Test
    fun `the issue being rendered is plain text, but others still link and expand`() {
        // A board card leads with its own key and an issue can name itself. Linking
        // that back to the page you are already on goes nowhere useful, and expanding
        // it to "TST-1: Title: Title" is noise, so a reference to `self` is left as
        // plain text — no anchor, no appended title — while every other reference is
        // untouched (LNL-151, superseding the title-only exclusion of LNL-144).
        val titles: TicketTitleLookup = { "Some title" }
        val out = renderMarkdown(
            "TST-1 relates to TST-2",
            listOf("TST"),
            self = Ticket("TST", 1),
            titleFor = titles,
        )
        assertFalse("""data-ticket="TST-1"""" in out, "The rendered issue's own ref is not a link: $out")
        assertFalse(""">TST-1<span""" in out, "The rendered issue's own ref carries no title: $out")
        assertTrue("TST-1" in out, "The self-reference's key is still shown as plain text: $out")
        assertTrue(
            """data-ticket="TST-2"""" in out && """>TST-2<span class="ticket-ref-title">: Some title</span>""" in out,
            "Others still link and expand: $out",
        )
    }

    @Test
    fun `a board card's own leading key is plain text, not a self-link`() {
        // The reported surface (LNL-151): a card's line is "PREFIX-N: Title" and is
        // rendered with renderInlineLinks. The leading key named its own issue, so it
        // was linked back to the very card you are looking at. With the card passed as
        // `self`, that key is plain text; a reference to another ticket in the title
        // still links and expands.
        val titles: TicketTitleLookup = { "Neighbour" }
        val out = renderInlineLinks(
            "TST-1: See also TST-2 for context",
            listOf("TST"),
            self = Ticket("TST", 1),
            titleFor = titles,
        )
        assertFalse("""data-ticket="TST-1"""" in out, "The card's own key must not be a link: $out")
        assertTrue(out.startsWith("TST-1: "), "The key stays as plain leading text: $out")
        assertTrue(
            """data-ticket="TST-2"""" in out && """: Neighbour""" in out,
            "A different ticket in the title still links and expands: $out",
        )
    }

    @Test
    fun `only the referenced issue's title is looked up`() {
        // titleFor is asked for the canonical Ticket, so the lookup can key off it.
        val out = renderMarkdown("LNL-12 and ABC-9", listOf("LNL", "ABC")) { ticket ->
            if (ticket.prefix == "ABC") "Other project" else null
        }
        assertTrue(""">ABC-9<span class="ticket-ref-title">: Other project</span>""" in out, "ABC expands: $out")
        assertFalse(
            """>LNL-12<span""" in out,
            "LNL has no title so it stays bare: $out",
        )
    }
}

/** Count of non-overlapping occurrences of [needle] in [haystack]. */
private fun String.findAllCount(haystack: String): Int {
    var count = 0
    var from = 0
    while (true) {
        val at = haystack.indexOf(this, from)
        if (at < 0) return count
        count++
        from = at + length
    }
}
