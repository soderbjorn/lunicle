/**
 * The one message an agent can send that is not an issue: a note to the person
 * driving it.
 *
 * ── What this is for ────────────────────────────────────────────────────────
 *
 * An agent working through a long task has nowhere to say "the migration
 * finished, three issues needed hand-editing" except the conversation the person
 * has already walked away from. The `send_email` tool gives it somewhere, and
 * this file is the *composition* half of it — the subject line and the body —
 * kept apart from [McpTools] for exactly [newIssueBody]'s reason: what a message
 * says can then be asserted without a database, a token or a network.
 *
 * ── The two things that make it safe to hand an agent ───────────────────────
 *
 * Neither is a check that could be forgotten; both are the shape of the thing.
 *
 *  - **There is no recipient.** Not "a recipient that is validated" — none. The
 *    address comes from the token's own [UserRecord.email], so the only person
 *    an agent can mail is the person whose account it is acting as. A tool that
 *    took an address and compared it to the user's would be one refactor away
 *    from being a mail relay with a Lunicle login.
 *  - **Every message says what it is.** The subject carries
 *    [AGENT_MAIL_SUBJECT_PREFIX] and the body opens with a line naming the agent,
 *    ahead of anything the agent wrote. Someone glancing at their inbox on a
 *    phone must be able to tell this from a message a colleague typed, and the
 *    part an agent controls starts below a rule.
 *
 * @see McpTools.sendEmail
 * @see EmailTransport
 */
package se.soderbjorn.lunicle

/**
 * What every agent-sent subject starts with.
 *
 * Fixed, and not configurable: its whole job is to be the same string in every
 * message so a mail rule can match it and a person can learn to recognise it.
 * Deliberately unlike the `[LNL-18]` a notification carries — that names an
 * issue, and this is not about one.
 */
internal const val AGENT_MAIL_SUBJECT_PREFIX = "[Lunicle agent]"

/** The agent's subject line, prefixed. Whitespace is trimmed so the prefix always abuts one space. */
internal fun agentMailSubject(subject: String): String = "$AGENT_MAIL_SUBJECT_PREFIX ${subject.trim()}"

/**
 * The body: a header saying who this is really from, a rule, then the agent's
 * own words.
 *
 * The header is not decoration. The message arrives at an address its owner
 * gave Lunicle for notifications, and it is being sent because a piece of
 * software decided to send it — so the first thing it says is that, including
 * the agent's own name when it gave one. [agentName] is the same optional label
 * the board shows on an agent-written issue; absent, this falls back to "an
 * agent" rather than inventing a name or quietly reading as a person.
 *
 * @param recipientName the account's display name — who is being written to.
 * @param agentName the agent's own name, or null if it did not say.
 * @param body what the agent wrote. Escaped and paragraphed by [agentMailText];
 *   see there for why it is not rendered as markdown.
 */
internal fun agentMailBody(recipientName: String, agentName: String?, body: String): String = buildString {
    append("<p>Hi ").append(esc(recipientName)).append(",</p>")
    append("<p>This message was sent by ")
        .append(if (agentName == null) "an agent" else esc(agentName))
        .append(" through Lunicle's MCP server, using your account. ")
        .append("No person wrote it, and it can only ever be sent to you.</p>")
    append("<hr>")
    append(agentMailText(body))
}

/**
 * The agent's text as HTML: escaped, with blank lines making paragraphs.
 *
 * ── Why this is not markdown ────────────────────────────────────────────────
 *
 * Everywhere else a body is markdown, and it is rendered by the client — the
 * server has no renderer, and acquiring one so an agent can bold a word in an
 * e-mail is a poor trade. So the tool's description tells the agent plainly that
 * this is plain text, and this function honours that literally: `**bold**`
 * arrives as asterisks rather than as a tag, which is the honest outcome and the
 * one the description promised.
 *
 * What it does handle is the thing that would otherwise look broken, because
 * mail clients collapse whitespace: a blank line becomes a paragraph and a
 * single newline becomes a `<br>`, so text an agent laid out as a short list
 * still reads as one. Everything is escaped first — this is the least
 * trustworthy string that reaches an e-mail body in this codebase, and a `<`
 * from a stack trace pasted into a progress report is an ordinary thing to find
 * in it.
 */
internal fun agentMailText(body: String): String =
    body.trim()
        .split(Regex("\\n\\s*\\n"))
        .filter { it.isNotBlank() }
        .joinToString("") { paragraph ->
            "<p>" + paragraph.trim().lines().joinToString("<br>") { esc(it.trim()) } + "</p>"
        }
