/**
 * "LMX-12" — a project's prefix and an issue's number, which together name one
 * issue across the whole instance.
 *
 * This is the id humans actually use. It is in commit messages, in chat, and now
 * in URLs, and it is the only issue identifier that means anything outside the
 * database — `issues.id` is global, opaque and an implementation detail nobody
 * types.
 *
 * Parsed here in the shared client rather than in the browser's bootstrap,
 * because the shape of a ticket is a fact about Lunicle and not about URLs. An
 * iOS client opening a `lunicle://` link needs the same parse.
 */
package se.soderbjorn.lunicle.client

/**
 * One issue, named the way a person names it.
 *
 * @property prefix the project's `name_prefix`, upper-cased. Unique across every
 *   project — see Projects.sq — which is what lets a ticket resolve without also
 *   naming the project.
 * @property number the per-project number.
 */
data class Ticket(val prefix: String, val number: Long) {
    override fun toString(): String = "$prefix-$number"
}

/**
 * Parse "LMX-12", or null if it is not one.
 *
 * `substringBeforeLast`, not `split`: a prefix may not contain a hyphen today,
 * and this does not depend on that staying true — "MY-APP-12" parses as
 * ("MY-APP", 12) rather than silently becoming ("MY", …) and failing to resolve.
 *
 * Upper-cased because `name_prefix` is stored upper-cased (ProjectRepository
 * does it on the way in) and a URL is typed by hand: `?issue=lmx-12` is the same
 * request as `?issue=LMX-12` and it would be perverse to answer one and not the
 * other. The comparison it feeds is against a column declared COLLATE NOCASE, so
 * this is belt to that brace.
 *
 * @param text the ticket, e.g. from a URL.
 */
fun parseTicket(text: String?): Ticket? {
    val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val prefix = trimmed.substringBeforeLast('-', missingDelimiterValue = "")
    val number = trimmed.substringAfterLast('-').toLongOrNull()
    if (prefix.isEmpty() || number == null || number <= 0) return null
    return Ticket(prefix.uppercase(), number)
}

/**
 * One ticket reference found in a string: where it sits, and which issue it names.
 *
 * @property start index of the reference's first character.
 * @property end index just past its last digit.
 * @property ticket the issue named, with [Ticket.prefix] canonicalised to the
 *   project prefix that was searched for — so `lnl-12` and `LNL-12` both resolve
 *   the same way [parseTicket] makes them.
 */
data class TicketSpan(val start: Int, val end: Int, val ticket: Ticket)

/**
 * Every place in [text] where an issue is named by any of [prefixes], left to
 * right — the shared answer to "what in this prose is a link to a ticket".
 *
 * ── Why it takes prefixes, when [mentionSpans] takes no roster ────────────────
 *
 * A mention is a shape — `@word` — and the shape alone is enough to know one was
 * written; who it names is a later question. A ticket reference is not: `UTF-8`,
 * `COVID-19` and `MP3-320` are all `PREFIX-NUMBER`, and a scan that matched the
 * shape would linkify half the acronyms anybody writes. What makes `LNL-12` a
 * reference is that `LNL` is a *real project's* prefix — so the prefix set is the
 * match, not a filter applied after one.
 *
 * ── Why a set, and not the one project the text lives in (LNL-139) ────────────
 *
 * A reference to another project's issue is worth just as much as one to a
 * sibling — it is still a place to go — so the links work across projects and a
 * click switches project to get there (see the web client's `navigateToTicket`).
 * The set passed is every project the reader can *see*: a reference the reader has
 * no access to resolves to nothing, so linking it would promise a destination that
 * is not there. The caller supplies that set — the accessible projects' prefixes —
 * and a prefix outside it is left as plain text.
 *
 * The rules, each there to stop a false positive:
 *
 *  - **The prefix must start a word.** The character before it, if any, must not
 *    be a letter, a digit, a hyphen or an `@` — so `SUPERLNL-1` is not an `LNL`
 *    reference, `FOO-LNL-1` is not one hiding inside another ticket, and `@LNL-1`
 *    is left for [mentionSpans] rather than linked as an issue.
 *  - **A hyphen and at least one digit must follow**, and the number must be
 *    positive: `LNL-` and `LNL-0` name nothing.
 *  - **The number must end a word too.** `LNL-12a` and `LNL-123x` are some other
 *    token that merely starts like a reference, not a reference to 12 or 123.
 *
 * Deliberately roster-free about *existence*, exactly as [mentionSpans] is: a
 * reference to a number no issue has is still a reference, because the renderer
 * has no board to check against and drawing it as a link that resolves to nothing
 * is the same honest failure a mention of a departed user is. Resolution to an
 * actual issue is the navigation layer's job.
 *
 * @param prefixes the project prefixes a reference may carry — the reader's
 *   accessible projects. Matched case-insensitively, since `lnl-12` and `LNL-12`
 *   are one request. Blank entries and an empty set match nothing. When two
 *   prefixes could both start at one spot the longer wins, so `MY-APP-1` resolves
 *   to `MY-APP` and not to a `MY` that also exists.
 */
fun ticketSpans(text: String, prefixes: Collection<String>): List<TicketSpan> {
    if (text.isEmpty()) return emptyList()
    // Longest first, so a candidate position tries `MY-APP` before `MY`; trimmed
    // and de-duplicated, and blanks dropped so an empty prefix cannot "match" at
    // every character.
    val needles = prefixes.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }
        .toList()
    if (needles.isEmpty()) return emptyList()

    val found = mutableListOf<TicketSpan>()
    var i = 0
    while (i < text.length) {
        val before = text.getOrNull(i - 1)
        if (before != null && before.blocksTicketStart()) {
            i++
            continue
        }
        val span = needles.firstNotNullOfOrNull { needle -> matchTicketAt(text, i, needle) }
        if (span != null) {
            found.add(span)
            i = span.end
        } else {
            i++
        }
    }
    return found
}

/**
 * A ticket reference for [needle] starting exactly at [at], or null.
 *
 * The per-position half of [ticketSpans]: the boundary *before* [at] is the
 * scanner's business (it is the same question whichever prefix is tried), so this
 * only asks whether the prefix, a hyphen, a positive number and a word boundary
 * after it are all present from [at] on.
 */
private fun matchTicketAt(text: String, at: Int, needle: String): TicketSpan? {
    if (!text.regionMatches(at, needle, 0, needle.length, ignoreCase = true)) return null
    var cursor = at + needle.length
    if (text.getOrNull(cursor) != '-') return null
    cursor++

    val numberStart = cursor
    while (cursor < text.length && text[cursor].isDigit()) cursor++
    if (cursor == numberStart) return null

    val after = text.getOrNull(cursor)
    if (after != null && after.isLetterOrDigit()) return null

    val number = text.substring(numberStart, cursor).toLongOrNull() ?: return null
    if (number <= 0) return null

    return TicketSpan(start = at, end = cursor, ticket = Ticket(needle.uppercase(), number))
}

/**
 * Could this character, sitting just before a prefix, mean the prefix is part of
 * something bigger than a ticket reference?
 *
 * Letters and digits because `xLNL-1` is one word; a hyphen because `FOO-LNL-1`
 * is one ticket (of some other project) and not an `LNL` reference buried in it;
 * `@` because `@LNL-1` is a mention for [mentionSpans] to draw, not an issue link.
 */
private fun Char.blocksTicketStart(): Boolean =
    isLetterOrDigit() || this == '-' || this == '@'
