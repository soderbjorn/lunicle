/**
 * The address bar's rules, and the parameters it must not touch.
 *
 * Almost every test here is about a parameter [nextSearch] was never told
 * about. That is the failure this function exists to prevent: the embed's
 * `?project=<name>` is written by whoever authored the `<iframe src>`, the forum
 * master toggle `?forums=1` is written by whoever wants to see the feature, and
 * both are destroyed by any implementation that builds the next URL from the
 * parameters it knows rather than from the one it was given.
 *
 * `?forums=1` survived before LNL-58 by accident, because the old `syncUrl`
 * happened to preserve unknown parameters. LNL-58 asks for that accident to be
 * pinned down, which is the point of the test that names it.
 *
 * The other failure these catch is the split between the two kinds of parameter,
 * which is easy to get backwards and behaves oppositely on a null. `?issue=`,
 * `?conversation=`, `?forum=` and `?post=` are **views** — things being looked at,
 * where "none" has to be expressible, so a null clears them. `?projectId=` and
 * `?tab=` are positions the caller may not know yet, so a null leaves them alone.
 * And `?message=` and `?comment=` are positions *inside* a view: read once at
 * load, never written, and therefore indistinguishable here from a parameter this
 * function was never told about.
 *
 * @see nextSearch
 */
package se.soderbjorn.lunicle.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppUrlTest {
    // ── Parameters that are none of our business ─────────────────────────────

    /** The embed's, which is the one with a person's `<iframe src>` behind it. */
    @Test
    fun `an unknown parameter survives a change to a known one`() {
        assertEquals(
            "project=Lunamux&issue=LMX-12",
            nextSearch("?project=Lunamux", ticket = "LMX-12", projectId = null, tab = null),
        )
    }

    /**
     * The master toggle, which used to survive by accident.
     *
     * Before LNL-58 nothing wrote `?tab=`, so `?forums=1` was preserved by the
     * same blanket rule as any other unknown parameter. Now that this function
     * actively writes a parameter *next to* it, the accident is worth a test:
     * an implementation that rebuilt the query from the three names it knows
     * would turn the forum feature off on the first focus change, and would look
     * fine until somebody clicked something.
     */
    @Test
    fun `the forum master toggle survives writing the tab`() {
        val next = nextSearch("?forums=1", ticket = null, projectId = 7, tab = "discussion")
        assertEquals("forums=1&projectId=7&tab=discussion", next)
    }

    /** Several at once, in the order they arrived. */
    @Test
    fun `unknown parameters keep their order and their raw values`() {
        val next = nextSearch(
            "?a=one&project=Luna%20mux&b=two",
            ticket = "LMX-1",
            projectId = null,
            tab = null,
        )
        assertEquals("a=one&project=Luna%20mux&b=two&issue=LMX-1", next)
    }

    /**
     * A value is carried across as the exact substring it arrived as.
     *
     * Decoding and re-encoding would round-trip somebody else's parameter
     * through this build's idea of escaping. `%20` staying `%20` rather than
     * becoming `+` is the cheapest way to assert that nothing decoded it.
     */
    @Test
    fun `a parameter with an encoded value is not re-encoded`() {
        val next = nextSearch("?project=Luna%20mux", ticket = "LMX-1", projectId = null, tab = null)
        assertEquals("project=Luna%20mux&issue=LMX-1", next)
    }

    // ── The parameters it does own ───────────────────────────────────────────

    /** A known parameter is rewritten where it already is, not moved to the end. */
    @Test
    fun `an existing parameter is replaced in place`() {
        val next = nextSearch("?issue=LMX-1&project=Lunamux", ticket = "LMX-2", projectId = null, tab = null)
        assertEquals("issue=LMX-2&project=Lunamux", next)
    }

    /** `issue` is the one a null clears — "nothing is focused" has to be expressible. */
    @Test
    fun `a null ticket removes the issue parameter and nothing else`() {
        val next = nextSearch("?issue=LMX-1&project=Lunamux", ticket = null, projectId = null, tab = null)
        assertEquals("project=Lunamux", next)
    }

    /**
     * A null project id means "leave it alone", which is emphatically not the
     * same as clearing it.
     *
     * The board is null before the first load returns and while a project is
     * being switched to. Clearing on those would lose the very thing being
     * restored if the page were reloaded mid-flight.
     */
    @Test
    fun `a null project id leaves an existing one alone`() {
        val next = nextSearch("?projectId=3", ticket = "LMX-1", projectId = null, tab = null)
        assertEquals("projectId=3&issue=LMX-1", next)
    }

    /** And a null tab likewise — with the toggle off there is no tab to write. */
    @Test
    fun `a null tab leaves an existing one alone`() {
        val next = nextSearch("?tab=discussion", ticket = "LMX-1", projectId = null, tab = null)
        assertEquals("tab=discussion&issue=LMX-1", next)
    }

    // ── Not writing when there is nothing to write ───────────────────────────

    /**
     * Null when the URL already says this.
     *
     * `main.kt` calls this on every state emission, so a function that always
     * answered would mean a `replaceState` per keystroke.
     */
    @Test
    fun `no change answers null`() {
        assertNull(nextSearch("?issue=LMX-1&projectId=3&tab=issues", "LMX-1", 3, "issues"))
        assertNull(nextSearch("?project=Lunamux", ticket = null, projectId = null, tab = null))
        assertNull(nextSearch("", ticket = null, projectId = null, tab = null))
    }

    /** Each of the three, on its own, is enough to answer. */
    @Test
    fun `any one of the three changing is enough`() {
        assertEquals("issue=LMX-2&projectId=3&tab=issues", nextSearch("?issue=LMX-1&projectId=3&tab=issues", "LMX-2", 3, "issues"))
        assertEquals("issue=LMX-1&projectId=4&tab=issues", nextSearch("?issue=LMX-1&projectId=3&tab=issues", "LMX-1", 4, "issues"))
        assertEquals(
            "issue=LMX-1&projectId=3&tab=messages",
            nextSearch("?issue=LMX-1&projectId=3&tab=issues", "LMX-1", 3, "messages"),
        )
    }

    // ── The conversation deep link (LNL-60) ──────────────────────────────────

    /**
     * `?conversation=` behaves like `?issue=`, not like `?projectId=`.
     *
     * The two groups are easy to conflate and behave oppositely on a null: an
     * issue and a conversation are *things being looked at*, so "none" has to be
     * expressible; a project and a tab are *positions*, and a null there means the
     * caller does not know yet rather than that there is nothing.
     */
    @Test
    fun `a conversation is written and cleared like an issue`() {
        assertEquals(
            "forums=1&tab=messages&conversation=9",
            nextSearch("?forums=1&tab=messages", ticket = null, projectId = null, tab = null, conversation = "9"),
        )
        assertEquals(
            "forums=1&tab=messages",
            nextSearch(
                "?forums=1&tab=messages&conversation=9",
                ticket = null,
                projectId = null,
                tab = null,
                conversation = null,
            ),
        )
    }

    /**
     * `?message=` is never written, so it is one of the unknown parameters.
     *
     * A message id is a position *inside* a conversation rather than a view, so
     * writing it back would make the URL change as somebody scrolled. It has to
     * survive a sync, though, or the very link that carried it would lose it
     * before the reader had finished loading the page.
     */
    @Test
    fun `the message parameter is preserved rather than written`() {
        assertEquals(
            "forums=1&tab=messages&conversation=9&message=42&projectId=3",
            nextSearch(
                "?forums=1&tab=messages&conversation=9&message=42",
                ticket = null,
                projectId = 3,
                tab = "messages",
                conversation = "9",
            ),
        )
    }

    /**
     * The whole deep link an e-mail carries, on a cold load, unchanged.
     *
     * The exact URL `EmailNotifier.conversationUrl` builds. It has to survive the
     * first sync untouched or the notification's link would be broken by the page
     * it opened — which is the failure that would only ever be noticed by somebody
     * clicking a real e-mail.
     */
    @Test
    fun `the notification deep link survives its own page load`() {
        assertNull(
            nextSearch(
                "?forums=1&tab=messages&conversation=9&message=42",
                ticket = null,
                projectId = null,
                tab = "messages",
                conversation = "9",
            ),
        )
    }

    // ── The forum deep link (LNL-62) ─────────────────────────────────────────

    /**
     * `?forum=` and `?post=` are views, so they behave like `?issue=`.
     *
     * The same split one tab over, and the same trap: a forum and a post are
     * *things being looked at*, so a null has to be able to say "none" — closing
     * the post window must take `?post=` with it, or the URL left behind reopens
     * the window the reader has just closed.
     */
    @Test
    fun `a forum and a post are written and cleared like an issue`() {
        assertEquals(
            "forums=1&tab=discussion&forum=3&post=17",
            nextSearch(
                "?forums=1&tab=discussion",
                ticket = null,
                projectId = null,
                tab = null,
                forum = "3",
                post = "17",
            ),
        )
        assertEquals(
            "forums=1&tab=discussion&forum=3",
            nextSearch(
                "?forums=1&tab=discussion&forum=3&post=17",
                ticket = null,
                projectId = null,
                tab = null,
                forum = "3",
                post = null,
            ),
        )
    }

    /**
     * `?comment=` is never written, so it is one of the unknown parameters.
     *
     * A comment id is a position *inside* a post rather than a view — `?message=`
     * exactly, one tab over. It has to survive a sync, though, or the very link
     * that carried it would lose it before the reader had finished loading the
     * page and the post view had got round to scrolling anywhere.
     */
    @Test
    fun `the comment parameter is preserved rather than written`() {
        assertEquals(
            "forums=1&tab=discussion&forum=3&post=17&comment=42&projectId=5",
            nextSearch(
                "?forums=1&tab=discussion&forum=3&post=17&comment=42",
                ticket = null,
                projectId = 5,
                tab = "discussion",
                forum = "3",
                post = "17",
            ),
        )
    }

    /**
     * A whole forum deep link, on a cold load, unchanged.
     *
     * The failure this catches is the one only a person clicking a real link would
     * ever see: the page rewriting the address it was opened at, before the thing
     * being linked to has had a chance to appear.
     */
    @Test
    fun `the forum deep link survives its own page load`() {
        assertNull(
            nextSearch(
                "?forums=1&tab=discussion&forum=3&post=17&comment=42",
                ticket = null,
                projectId = null,
                tab = "discussion",
                forum = "3",
                post = "17",
            ),
        )
    }

    /**
     * The Discussion tab's parameters do not disturb the Messages tab's, or the
     * embed's.
     *
     * Four known parameters now share this function, and the way they break is by
     * one of them rebuilding the query from the names *it* knows. Writing a forum
     * while a conversation is open, in an embed, is the case that would catch it.
     */
    @Test
    fun `writing a forum leaves the conversation and the embed alone`() {
        assertEquals(
            "project=Lunamux&forums=1&conversation=9&tab=discussion&forum=3",
            nextSearch(
                "?project=Lunamux&forums=1&conversation=9",
                ticket = null,
                projectId = null,
                tab = "discussion",
                conversation = "9",
                forum = "3",
            ),
        )
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    /** A value containing `=` keeps every one after the first, so it round-trips. */
    @Test
    fun `only the first equals separates a parameter`() {
        assertEquals(listOf("a" to "b=c"), parseQuery("?a=b=c"))
        assertEquals("a=b=c", formatQuery(parseQuery("?a=b=c")))
    }

    /** A bare parameter has no value, and stays bare rather than growing an `=`. */
    @Test
    fun `a valueless parameter round-trips without an equals sign`() {
        assertEquals(listOf("flag" to ""), parseQuery("?flag"))
        assertEquals("flag", formatQuery(parseQuery("?flag")))
    }

    /** With or without the `?`, and empty means empty. */
    @Test
    fun `the leading question mark is optional`() {
        assertEquals(parseQuery("a=1"), parseQuery("?a=1"))
        assertEquals(emptyList(), parseQuery(""))
        assertEquals(emptyList(), parseQuery("?"))
    }

    /** The value reader, which is how `main.kt` finds the toggle and the tab. */
    @Test
    fun `queryValue finds a parameter and answers null for an absent one`() {
        assertEquals("1", queryValue("?forums=1&tab=discussion", "forums"))
        assertEquals("discussion", queryValue("?forums=1&tab=discussion", "tab"))
        assertNull(queryValue("?forums=1", "tab"))
    }
}
