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
