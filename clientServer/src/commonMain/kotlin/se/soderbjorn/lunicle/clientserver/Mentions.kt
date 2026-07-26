/**
 * What an `@mention` is, in the one place both ends can agree on it.
 *
 * ── The two spellings, and why there are two ─────────────────────────────────
 *
 * A mention is written **`@{Ada Lovelace}`** or **`@grace`**, and the difference
 * is whether the name has whitespace in it:
 *
 *  - **`@{Name}` — the canonical form.** Self-delimiting, so where the name ends
 *    is a fact about the text rather than a question you need the project's
 *    roster to answer. Valid for any name; *required* for a name containing
 *    whitespace, and the form the editor's autocomplete writes for one.
 *  - **`@name` — the bare form.** Only for names with no whitespace, because
 *    without a closing brace the name has to end where the word does. It exists
 *    so that typing a mention by hand works, which the feature would be
 *    half-missing without: somebody who never opens the autocomplete, or who
 *    pastes a sentence containing a mention, still reaches the person.
 *
 * The alternative considered and rejected was markup carrying the account id —
 * `@[Ada Lovelace](user:7)` — which resolves in constant time and survives a
 * rename. It cannot be typed by hand, so it would give two kinds of mention, one
 * of which notifies and one of which only looks like it does; the one that
 * silently does nothing is the one users would produce.
 *
 * Two accounts sharing a display name remain indistinguishable — see
 * [mentionedNames]. A rename no longer orphans the mentions already written:
 * the text is rewritten to the new name, which is [renameMentions]' job and the
 * price of having chosen names over ids.
 *
 * ── Shared, not duplicated ───────────────────────────────────────────────────
 *
 * The server matches this grammar to decide who to mail; the browser matches it
 * to drive the autocomplete, and the renderer matches it to draw a mention as
 * something other than punctuation. Three implementations would be three
 * implementations that disagree — a popup offering a completion that then,
 * silently, notifies nobody. One file, `commonMain`, every caller.
 */
package se.soderbjorn.lunicle.clientserver

/**
 * One mention found in a string: where it sits, and who it names.
 *
 * @property start index of the `@`.
 * @property end index just past the mention — past the `}` for a braced one.
 * @property name what was written between the delimiters. **Syntax only**: this
 *   is whatever the text said, not a name anybody is known to have.
 */
data class MentionSpan(val start: Int, val end: Int, val name: String)

/**
 * Every syntactic mention in [text], left to right, without consulting a roster.
 *
 * The rules, all of which exist to stop a false positive:
 *
 *  - **The `@` must start a word.** The character before it, if any, must not be
 *    one a name can contain. This is what keeps an e-mail address from
 *    mentioning people: in `ada@lovelace.org` the `@` follows an `a`, so it is
 *    not a mention however many users are called "lovelace.org".
 *  - **A braced mention ends at its `}`.** It may hold anything but a newline or
 *    another brace, so a name with spaces, dots or apostrophes in it survives.
 *    An unclosed `@{` is not a mention — it is somebody who typed a brace.
 *  - **A bare mention ends where the word does**, at the first character a name
 *    cannot contain. Whitespace ends it, which is exactly why a name containing
 *    whitespace cannot be written this way.
 *
 * Deliberately roster-free, so that the renderer — which has no roster — asks
 * the same question the server does. Resolution against real accounts is
 * [mentionedNames]'s job, one layer up.
 */
fun mentionSpans(text: String): List<MentionSpan> {
    val found = mutableListOf<MentionSpan>()
    var i = 0
    while (i < text.length) {
        if (text[i] != '@' || (i > 0 && text[i - 1].isMentionCharacter())) {
            i++
            continue
        }
        if (i + 1 < text.length && text[i + 1] == '{') {
            val close = text.indexOf('}', i + 2)
            // An unclosed brace, or one with a newline before it, is not a
            // mention — and must not swallow the rest of the document looking
            // for a closing brace that is not coming.
            val name = if (close < 0) null else text.substring(i + 2, close).takeIf {
                it.isNotBlank() && !it.contains('\n') && !it.contains('{')
            }
            if (name != null) {
                found.add(MentionSpan(start = i, end = close + 1, name = name))
                i = close + 1
                continue
            }
            i++
            continue
        }
        var end = i + 1
        while (end < text.length && text[end].isMentionCharacter() && text[end] != '@') end++
        if (end > i + 1) {
            found.add(MentionSpan(start = i, end = end, name = text.substring(i + 1, end)))
            i = end
        } else {
            i++
        }
    }
    return found
}

/**
 * How [name] must be written for [mentionSpans] to find it again.
 *
 * The one place that decides between the two spellings, so the autocomplete, the
 * serialiser and any future writer all produce the same bytes. Braces exactly
 * when the bare form could not survive the round trip — a name with whitespace
 * in it, or one carrying a character the bare form would stop at.
 */
fun mentionMarkdown(name: String): String =
    if (name.all { it.isMentionCharacter() && it != '@' }) "@$name" else "@{$name}"

/**
 * Every name from [names] that [text] mentions.
 *
 * Resolution, on top of [mentionSpans]'s syntax: a span whose name nobody here
 * has is not a mention of anybody, and is dropped. Matching is case-insensitive,
 * so `@ada` reaches Ada.
 *
 * @param names the display names that may be mentioned here. Two accounts with
 *   the same display name collapse to one entry, and the caller — which knows
 *   the ids — is the one that has to decide it means "mail both". See the
 *   server's `NotificationService.issueMentioned`.
 * @return the matched names, exactly as they were spelled in [names] (not as
 *   they were spelled in [text]), so the caller can look them back up.
 */
fun mentionedNames(text: String, names: Collection<String>): Set<String> {
    if (text.isEmpty() || names.isEmpty()) return emptySet()
    // Blank names are dropped rather than matched: a display name that is
    // whitespace would otherwise be "mentioned" by any stray "@{ }".
    val candidates = names.filter { it.isNotBlank() }.distinct()
    if (candidates.isEmpty()) return emptySet()

    val found = LinkedHashSet<String>()
    mentionSpans(text).forEach { span ->
        candidates.firstOrNull { it.equals(span.name, ignoreCase = true) }?.let { found.add(it) }
    }
    return found
}

/**
 * [text] with every mention of [from] rewritten to name [to], or [text] itself
 * when it mentions nobody by that name.
 *
 * What makes a rename survivable. Mentions match on display names, so an account
 * that changes its name would otherwise leave every `@OldName` already written as
 * inert text — still rendered as a mention, still reading as one, and notifying
 * nobody ever again. Rewriting the text is the honest fix: what the comment says
 * afterwards is what it always meant.
 *
 * Only whole mentions are touched, and only ones this file would have matched in
 * the first place — the same case-insensitive comparison [mentionedNames] uses,
 * over the same spans. Prose that merely contains the old name, an e-mail address
 * that happens to end in it, a mention of somebody else: all untouched.
 *
 * The rewritten mention is spelled by [mentionMarkdown], not by patching the old
 * delimiters, so a bare `@grace` renamed to "Grace Hopper" comes out as
 * `@{Grace Hopper}` rather than as a bare mention that now ends at the space.
 *
 * Built left to right out of the untouched runs between the spans, so no index
 * is ever read after the text under it has moved.
 *
 * @param from the name as it was written; blank matches nothing.
 * @param to the name now. Equal to [from], case included, is a no-op.
 */
fun renameMentions(text: String, from: String, to: String): String {
    if (from.isBlank() || to.isBlank() || from == to) return text
    if (!text.contains('@')) return text
    val spans = mentionSpans(text).filter { it.name.equals(from, ignoreCase = true) }
    if (spans.isEmpty()) return text
    val replacement = mentionMarkdown(to)
    val builder = StringBuilder()
    var cursor = 0
    spans.forEach { span ->
        builder.append(text, cursor, span.start)
        builder.append(replacement)
        cursor = span.end
    }
    builder.append(text, cursor, text.length)
    return builder.toString()
}

/**
 * The names from [names] that could still complete a partly-typed mention.
 *
 * The autocomplete's whole filter. Prefix rather than substring, and
 * case-insensitive: someone who has typed "@ad" is spelling a name from its
 * start, and offering "Vladimir" because it contains "ad" would be a list that
 * changes shape for reasons the typist cannot see.
 *
 * An empty [query] — the moment `@` is pressed and nothing follows — matches
 * everyone, which is the "press @ and a box appears covering all users"
 * behaviour rather than a special case above this function.
 */
fun mentionCompletions(query: String, names: Collection<String>): List<String> =
    names.filter { it.isNotBlank() && it.startsWith(query, ignoreCase = true) }

/**
 * Could this character be *inside* a bare mention's name?
 *
 * Letters and digits, plus the punctuation that turns up inside real display
 * names and e-mail addresses: `.`, `-`, `_` and `@` itself. Deliberately wider
 * than "what a name may contain" — every character added here makes the
 * word-boundary test *stricter*, and a mention that does not fire is a smaller
 * failure than a comment quoting an e-mail address that mails a stranger.
 *
 * Note it treats the whole of Unicode's letters as name characters, so
 * "Söderbjörn" ends a word at the same places "Soderbjorn" does.
 */
private fun Char.isMentionCharacter(): Boolean =
    isLetterOrDigit() || this == '.' || this == '-' || this == '_' || this == '@'
