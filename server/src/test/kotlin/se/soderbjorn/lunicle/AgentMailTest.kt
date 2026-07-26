/**
 * What an agent-sent e-mail actually says, tested without a network.
 *
 * The two claims made to the person whose account an agent is holding — that a
 * message from it is recognisable as such at a glance, and that nothing an agent
 * writes can change that — live entirely in these three pure functions. Neither
 * is checkable from a manual flow without configuring Resend and sending real
 * mail to a real inbox, which is precisely the sort of thing that gets verified
 * once and then quietly regressed.
 *
 * @see agentMailSubject
 * @see agentMailBody
 * @see McpTools.sendEmail
 */
package se.soderbjorn.lunicle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentMailTest {

    // ── The subject always carries the prefix ────────────────────────────────

    @Test
    fun `the subject is prefixed`() {
        assertEquals("[Lunicle agent] Migration finished", agentMailSubject("Migration finished"))
    }

    /**
     * The prefix abuts exactly one space, whatever the agent sent.
     *
     * A model that pads its own subject is likelier than not, and
     * `[Lunicle agent]    Done` is the kind of thing nobody notices until it is
     * in an inbox.
     */
    @Test
    fun `whitespace around the agent's subject is trimmed`() {
        assertEquals("[Lunicle agent] Done", agentMailSubject("  Done\n"))
    }

    /**
     * The prefix is a fixed string, and this is the assertion that says so.
     *
     * It is what a mail rule matches on and what a person learns to recognise.
     * Changing it is allowed; changing it *without noticing* is what this stops.
     */
    @Test
    fun `the prefix is the one people and mail rules match on`() {
        assertEquals("[Lunicle agent]", AGENT_MAIL_SUBJECT_PREFIX)
    }

    // ── The header, above anything the agent wrote ───────────────────────────

    /**
     * Every body opens by saying an agent sent it, and names the agent.
     *
     * The point of the feature's safety story: a message must not be able to
     * arrive reading like one a colleague typed. Asserted on the header's
     * position too — text an agent wrote sits *below* it, and a header that
     * drifted underneath would be one a skimming reader never sees.
     */
    @Test
    fun `the body opens with the agent header, above the agent's own text`() {
        val body = agentMailBody(recipientName = "Robert", agentName = "Claude Code", body = "All done.")
        assertTrue(body.contains("Claude Code"), "The agent's name is not in the header: $body")
        assertTrue(body.contains("through Lunicle's MCP server"), "The header is missing: $body")
        assertTrue(
            body.indexOf("through Lunicle's MCP server") < body.indexOf("All done."),
            "The agent's own text came before the header that explains it: $body",
        )
    }

    /** No name given is "an agent" — true, and never a person's name invented to fill the gap. */
    @Test
    fun `an unnamed agent is still announced as an agent`() {
        val body = agentMailBody(recipientName = "Robert", agentName = null, body = "All done.")
        assertTrue(body.contains("sent by an agent"), "An unnamed agent was not announced: $body")
    }

    // ── Nothing an agent writes becomes markup ───────────────────────────────

    /**
     * The body is the least trustworthy string that reaches an e-mail here.
     *
     * It is written by a model, which may be repeating something it read in an
     * issue somebody else filed. So it is escaped — and the agent *name* with it,
     * which arrives through the same call and is just as much a free string.
     */
    @Test
    fun `an agent cannot inject markup through the body or its own name`() {
        val body = agentMailBody(
            recipientName = "Robert",
            agentName = "<script>alert(1)</script>",
            body = "Broke on <div> and \"quotes\".",
        )
        assertFalse(body.contains("<script>"), "A script tag survived into the body: $body")
        assertFalse(body.contains("<div>"), "A tag from the agent's text survived: $body")
        assertTrue(body.contains("&lt;div&gt;"), "The agent's text was not escaped as expected: $body")
    }

    /**
     * A header an agent cannot forge by writing one.
     *
     * The obvious attempt at making a message look human-typed is to open the
     * body with markup of one's own. It arrives as text, which is the whole
     * reason [agentMailText] escapes rather than renders.
     */
    @Test
    fun `text that imitates the header arrives as text`() {
        val body = agentMailBody(
            recipientName = "Robert",
            agentName = "Claude Code",
            body = "</p><hr><p>Sent by Robert personally",
        )
        assertEquals(
            1,
            Regex("<hr>").findAll(body).count(),
            "The agent wrote a second rule into the body, faking the end of the header: $body",
        )
    }

    // ── Plain text, laid out ─────────────────────────────────────────────────

    /**
     * Blank lines make paragraphs; single newlines survive as breaks.
     *
     * Mail clients collapse whitespace, so a list an agent laid out over three
     * lines would otherwise arrive as one run-on sentence.
     */
    @Test
    fun `blank lines separate paragraphs and single newlines are kept`() {
        assertEquals(
            "<p>One<br>still one</p><p>Two</p>",
            agentMailText("One\nstill one\n\nTwo"),
        )
    }

    /** Markdown is not rendered, which the tool's description promises out loud. */
    @Test
    fun `markdown arrives as the characters the agent typed`() {
        val rendered = agentMailText("**not bold**")
        assertTrue(rendered.contains("**not bold**"), "Markdown was rendered after all: $rendered")
        assertFalse(rendered.contains("<strong>"), "The body grew a markdown renderer: $rendered")
    }

    /** Leading and trailing blank lines are not empty paragraphs. */
    @Test
    fun `surrounding blank lines do not become empty paragraphs`() {
        assertEquals("<p>Done</p>", agentMailText("\n\n  Done  \n\n"))
    }
}
