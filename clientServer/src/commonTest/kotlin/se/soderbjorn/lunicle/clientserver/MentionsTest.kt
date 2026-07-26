/**
 * The mention grammar, pinned.
 *
 * This is the one piece of the @mention feature that is pure, and it is also the
 * piece whose failures are silent in both directions: a mention that quietly
 * notifies nobody, or an e-mail address in a comment that quietly mails a
 * stranger. Neither shows up in a screenshot, so both are tested here rather
 * than clicked through.
 *
 * @see mentionedNames
 */
package se.soderbjorn.lunicle.clientserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MentionsTest {

    private val board = listOf("Ada", "Ada Lovelace", "Robert Söderbjörn", "grace")

    // ── The bare form ────────────────────────────────────────────────────────

    @Test
    fun `a bare mention is found`() {
        assertEquals(setOf("grace"), mentionedNames("thanks @grace", board))
    }

    @Test
    fun `a bare mention mid-sentence is found, and punctuation after it does not break it`() {
        assertEquals(setOf("Ada"), mentionedNames("could @Ada look, please?", board))
        assertEquals(setOf("Ada"), mentionedNames("(@Ada)", board))
    }

    @Test
    fun `matching is case-insensitive but the board's spelling comes back`() {
        assertEquals(setOf("grace"), mentionedNames("@GRACE", board))
        assertEquals(setOf("Ada"), mentionedNames("@ada", board))
    }

    @Test
    fun `several mentions in one body are all found`() {
        assertEquals(
            setOf("Ada", "grace"),
            mentionedNames("@Ada and @grace should both see this", board),
        )
    }

    @Test
    fun `the same person twice is one mention`() {
        assertEquals(setOf("Ada"), mentionedNames("@Ada @Ada @Ada", board))
    }

    // ── The braced form ──────────────────────────────────────────────────────

    @Test
    fun `a name with a space needs braces, and works with them`() {
        assertEquals(setOf("Ada Lovelace"), mentionedNames("ping @{Ada Lovelace}", board))
        assertEquals(setOf("Robert Söderbjörn"), mentionedNames("@{Robert Söderbjörn} hi", board))
    }

    @Test
    fun `a name with a space written bare reaches only the shorter name`() {
        // The cost of the bare form, stated: "@Ada Lovelace" ends at the space,
        // so it mentions Ada and the word "Lovelace" is prose. This is exactly
        // why the autocomplete writes braces for a name that has a space in it.
        assertEquals(setOf("Ada"), mentionedNames("@Ada Lovelace", board))
    }

    @Test
    fun `braces around a spaceless name are fine too`() {
        assertEquals(setOf("grace"), mentionedNames("@{grace}", board))
    }

    @Test
    fun `an unclosed brace mentions nobody and does not eat the document`() {
        assertTrue(mentionedNames("@{Ada Lovelace and then some", board).isEmpty())
        assertTrue(mentionedNames("@{", board).isEmpty())
    }

    @Test
    fun `a braced mention cannot span a newline`() {
        assertTrue(mentionedNames("@{Ada\nLovelace}", board).isEmpty())
    }

    @Test
    fun `the two forms mix in one body`() {
        assertEquals(
            setOf("Ada Lovelace", "grace"),
            mentionedNames("@{Ada Lovelace} and @grace", board),
        )
    }

    // ── What is not a mention ────────────────────────────────────────────────

    @Test
    fun `an e-mail address mentions nobody`() {
        // The failure this exists to stop: a comment quoting an address must not
        // summon whoever happens to share a name with the domain or the mailbox.
        assertTrue(mentionedNames("write to ada@lovelace.org", board).isEmpty())
        assertTrue(mentionedNames("grace@example.com", board).isEmpty())
    }

    @Test
    fun `a name that only prefixes a longer word is not a mention`() {
        assertTrue(mentionedNames("@Adamson filed it", board).isEmpty())
    }

    @Test
    fun `a bare at-sign mentions nobody`() {
        assertTrue(mentionedNames("@ and @@ and @ ", board).isEmpty())
    }

    @Test
    fun `a name nobody on this board has is not a mention`() {
        assertTrue(mentionedNames("@Charles", board).isEmpty())
        assertTrue(mentionedNames("@{Charles Babbage}", board).isEmpty())
    }

    @Test
    fun `an empty board and an empty body find nothing`() {
        assertTrue(mentionedNames("@Ada", emptyList()).isEmpty())
        assertTrue(mentionedNames("", board).isEmpty())
    }

    @Test
    fun `a blank name never matches`() {
        assertTrue(mentionedNames("@{ }", listOf("  ")).isEmpty())
    }

    // ── The canonical spelling ───────────────────────────────────────────────

    @Test
    fun `a spaceless name is written bare and a spaced one is braced`() {
        assertEquals("@grace", mentionMarkdown("grace"))
        assertEquals("@{Ada Lovelace}", mentionMarkdown("Ada Lovelace"))
        assertEquals("@Robert", mentionMarkdown("Robert"))
    }

    @Test
    fun `whatever mentionMarkdown writes, mentionedNames reads back`() {
        // The round trip the autocomplete depends on: every name on the board,
        // written by the editor, has to resolve to itself.
        board.forEach { name ->
            assertEquals(
                setOf(name),
                mentionedNames("hi ${mentionMarkdown(name)} there", board),
                "\"$name\" did not survive being written and read back.",
            )
        }
    }

    // ── Spans, which the renderer draws from ─────────────────────────────────

    @Test
    fun `spans report where a mention sits and what it names`() {
        val spans = mentionSpans("hi @{Ada Lovelace} and @grace")
        assertEquals(listOf("Ada Lovelace", "grace"), spans.map { it.name })
        // The braces are inside the span, so the renderer replaces them rather
        // than leaving punctuation on screen.
        assertEquals("@{Ada Lovelace}", "hi @{Ada Lovelace} and @grace".substring(spans[0].start, spans[0].end))
        assertEquals("@grace", "hi @{Ada Lovelace} and @grace".substring(spans[1].start, spans[1].end))
    }

    @Test
    fun `spans are syntax only and do not consult a roster`() {
        // What lets the renderer — which has no account list, and must not be
        // shipped one — draw a mention at all.
        assertEquals(listOf("Charles Babbage"), mentionSpans("@{Charles Babbage}").map { it.name })
    }

    // ── The autocomplete's filter ────────────────────────────────────────────

    @Test
    fun `an empty query offers everyone`() {
        assertEquals(board, mentionCompletions("", board))
    }

    @Test
    fun `completions are prefix matches, case-insensitively`() {
        assertEquals(listOf("Ada", "Ada Lovelace"), mentionCompletions("ad", board))
        assertEquals(listOf("Ada Lovelace"), mentionCompletions("Ada L", board))
    }

    @Test
    fun `a query nobody starts with offers nothing`() {
        // What closes the popup: the editor treats an empty result as "this was
        // never a mention" rather than drawing an empty box.
        assertTrue(mentionCompletions("zz", board).isEmpty())
    }

    @Test
    fun `completions are a prefix test, not a substring one`() {
        assertTrue(mentionCompletions("ovelace", board).isEmpty())
    }

    // ── The rule that keeps a re-save from re-mailing ────────────────────────

    @Test
    fun `only the newly added mention differs between two bodies`() {
        // The subtraction NotificationService does. Pinned here because it is the
        // whole of "editing a typo must not re-summon everybody".
        val before = mentionedNames("@{Ada Lovelace} please look", board)
        val after = mentionedNames("@{Ada Lovelace} please look, and @grace too", board)
        assertEquals(setOf("grace"), after - before)
    }

    // ── Following a rename ───────────────────────────────────────────────────

    @Test
    fun `a rename moves both spellings of a mention`() {
        assertEquals(
            "@{Grace Hopper} and @{Grace Hopper} again",
            renameMentions("@grace and @{Grace} again", "grace", "Grace Hopper"),
        )
    }

    @Test
    fun `a renamed mention is re-spelled for the name it now carries`() {
        // A bare mention cannot hold a name with a space, so it must come back
        // braced — otherwise the rewrite would produce a mention that ends at the
        // space and names somebody called "Grace".
        assertEquals("@{Grace Hopper}", renameMentions("@grace", "grace", "Grace Hopper"))
        // And the other way: a braced mention of a one-word name comes back bare,
        // because mentionMarkdown is the single decider of the spelling.
        assertEquals("@ada", renameMentions("@{Ada Lovelace}", "Ada Lovelace", "ada"))
    }

    @Test
    fun `a rename matches case-insensitively, like every other lookup`() {
        assertEquals("@Amazing", renameMentions("@GRACE", "grace", "Amazing"))
    }

    @Test
    fun `a rename leaves everything that is not a mention of that name alone`() {
        // Prose containing the name, an e-mail address ending in it, and a
        // mention of somebody else.
        val text = "grace was here, ada@grace.org wrote, cc @Ada"
        assertEquals(text, renameMentions(text, "grace", "Grace Hopper"))
    }

    @Test
    fun `a rename of several mentions keeps the untouched text between them intact`() {
        assertEquals(
            "start @Hopper middle @Hopper end",
            renameMentions("start @grace middle @{grace} end", "grace", "Hopper"),
        )
    }

    @Test
    fun `a rename to the same name, or from or to nothing, changes nothing`() {
        assertEquals("@grace", renameMentions("@grace", "grace", "grace"))
        assertEquals("@grace", renameMentions("@grace", "", "Hopper"))
        assertEquals("@grace", renameMentions("@grace", "grace", "  "))
    }

    @Test
    fun `a renamed mention is found again under the new name`() {
        // The round trip that is the whole point: rewrite, then resolve.
        val renamed = renameMentions("thanks @grace", "grace", "Grace Hopper")
        assertEquals(setOf("Grace Hopper"), mentionedNames(renamed, listOf("Grace Hopper")))
    }
}
