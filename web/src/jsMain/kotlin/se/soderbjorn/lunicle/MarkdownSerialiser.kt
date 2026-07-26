/**
 * DOM → markdown. The other half of the WYSIWYG editor.
 *
 * ── Why this file is the dangerous one ───────────────────────────────────────
 *
 * [MarkdownEditor] shows formatted text and stores markdown, so something has to
 * turn the edited DOM back into markdown on every keystroke. That round-trip is
 * where WYSIWYG editors lose data: the serialiser emits markdown that renders
 * back as something *else*, the user's text quietly changes under them, and the
 * bug is unreproducible because it depends on exactly what was typed or pasted.
 *
 * Two rules keep that from happening here, and both are load-bearing:
 *
 *  1. **Closed over the toolbar.** This handles exactly the constructs
 *     [MarkdownEditor] can produce and [renderMarkdown] can read, and nothing
 *     else. An unknown element contributes its text and no markup — never a
 *     guess. That is why paste is forced to plain text: if arbitrary HTML could
 *     not enter the document, there is no arbitrary HTML to mis-serialise.
 *  2. **Text is escaped, never trusted.** Every text node goes through
 *     [escapeMarkdown], so a literal "2 * 3 * 4" comes back as "2 \* 3 \* 4"
 *     and renders as what was typed instead of turning "3" italic. This is the
 *     half of the answer that lives on this side; see Markdown.kt's ESCAPABLE.
 *
 * The pair is what makes the round-trip closed: serialise ∘ render is identity
 * on everything the toolbar can make.
 *
 * @see MarkdownEditor
 * @see renderMarkdown
 */
package se.soderbjorn.lunicle

import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.Text
import org.w3c.dom.asList
import se.soderbjorn.lunicle.client.escapeMarkdown
import se.soderbjorn.lunicle.client.escapeMarkdownBlock
import se.soderbjorn.lunicle.clientserver.mentionMarkdown

/**
 * The elements that end a paragraph.
 *
 * `div` is here because browsers produce them freely — a plain Enter in a
 * contenteditable makes a `div` in some engines and a `p` in others — and both
 * mean "new block" to the person typing.
 */
private val BLOCK_TAGS = setOf(
    "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote", "pre",
)

/**
 * Serialise an edited document to markdown.
 *
 * @param root the contenteditable host. Its own tag is not examined; only its
 *   children are.
 */
fun serialiseMarkdown(root: HTMLElement): String {
    val blocks = mutableListOf<String>()
    collectBlocks(root, blocks)
    return blocks.filter { it.isNotBlank() }.joinToString("\n\n")
}

/**
 * Walk [parent]'s children, appending one markdown block per block-level child.
 *
 * Inline children between blocks are gathered into a paragraph of their own —
 * browsers leave bare text directly under the host after some edits, and
 * dropping it would silently eat what the user typed.
 */
private fun collectBlocks(parent: Node, out: MutableList<String>) {
    val pending = StringBuilder()

    fun flushParagraph() {
        val text = pending.toString().trim()
        pending.clear()
        if (text.isNotBlank()) out.add(escapeMarkdownBlock(text))
    }

    parent.childNodes.asList().forEach { node ->
        val tag = (node as? Element)?.tagName?.lowercase()
        if (tag == null || tag !in BLOCK_TAGS) {
            pending.append(serialiseInline(node))
            return@forEach
        }

        // A block starts here, so whatever inline content preceded it is a
        // paragraph in its own right and has to be emitted before this one.
        flushParagraph()
        val element = node as Element
        when (tag) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = tag.drop(1).toInt()
                val text = inlineOf(element).trim()
                // An empty heading is not a heading; "#" alone renders as a
                // literal hash and would appear from nowhere.
                if (text.isNotBlank()) out.add("#".repeat(level) + " " + text)
            }

            "ul", "ol" -> {
                // Ordered lists serialise as bullets: the toolbar has no
                // numbered-list button and renderMarkdown has no rule for one,
                // so emitting "1." would produce a list that reads back as a
                // paragraph beginning with a number. A browser can still make an
                // <ol> — Enter inside a pasted list, say — and a bullet is the
                // closest thing this format can actually hold.
                val items = element.childNodes.asList()
                    .filterIsInstance<Element>()
                    .filter { it.tagName.lowercase() == "li" }
                    .map { inlineOf(it).trim() }
                    .filter { it.isNotBlank() }
                if (items.isNotEmpty()) out.add(items.joinToString("\n") { "- $it" })
            }

            else -> {
                // p, div, li, blockquote, pre. A block that contains further
                // blocks — a div of divs, which is a shape browsers produce on
                // their own — recurses, so its children each become a block
                // rather than being flattened into one run-on paragraph.
                if (hasBlockChild(element)) {
                    collectBlocks(element, out)
                } else {
                    val text = inlineOf(element).trim()
                    if (text.isNotBlank()) out.add(escapeMarkdownBlock(text))
                }
            }
        }
    }

    flushParagraph()
}

private fun hasBlockChild(element: Element): Boolean =
    element.childNodes.asList().any { (it as? Element)?.tagName?.lowercase() in BLOCK_TAGS }

private fun inlineOf(node: Node): String =
    node.childNodes.asList().joinToString("") { serialiseInline(it) }

/**
 * One inline node and its descendants.
 *
 * The `isBlank()` guards matter more than they look: `**` with nothing between
 * the pairs is not bold, it is four literal asterisks, and an empty `<strong>`
 * is exactly what a browser leaves behind when someone turns bold on and then
 * off without typing. Without the guard, toggling a button twice would deposit
 * visible punctuation into the document.
 */
private fun serialiseInline(node: Node): String {
    if (node is Text) return escapeMarkdown(node.textContent.orEmpty())
    val element = node as? Element ?: return ""

    return when (val tag = element.tagName.lowercase()) {
        "br" -> "\n"

        // getAttribute rather than the .src/.href properties: those resolve to
        // an absolute URL, so "/api/attachments/12" would be stored as
        // "https://lunamux.dev/api/attachments/12" — a hostname baked into the
        // document, wrong the moment the same database is read anywhere else.
        "img" -> {
            val src = element.getAttribute("src").orEmpty()
            if (src.isBlank()) "" else "![" + escapeMarkdown(element.getAttribute("alt").orEmpty()) + "]($src)"
        }

        "a" -> {
            val href = element.getAttribute("href").orEmpty()
            val label = inlineOf(element)
            if (href.isBlank()) label else "[$label]($href)"
        }

        // A mention chip, which renderMarkdown drew and whose braces it dropped.
        // The name comes from the attribute rather than from the visible text
        // for exactly that reason: the text reads "@Ada Lovelace" and the
        // document has to say "@{Ada Lovelace}", or the mention decays into
        // prose the next time anyone edits around it. mentionMarkdown decides
        // which of the two spellings this name needs — see Mentions.kt.
        //
        // Falls through to the generic span handling below when the attribute is
        // absent, which is every other span the browser ever leaves behind.
        "span" -> element.getAttribute("data-mention")
            ?.takeIf { it.isNotBlank() }
            ?.let { mentionMarkdown(it) }
            ?: inlineOf(element)

        else -> {
            val inner = inlineOf(element)
            if (inner.isBlank()) {
                inner
            } else {
                when (tag) {
                    // Both spellings of each: execCommand emits <b>/<i>/<strike>
                    // in some engines and <strong>/<em>/<s> in others, and the
                    // document also gets <strong>/<em>/<del> straight from
                    // renderMarkdown whenever setValue reloads it. Handling only
                    // one spelling would lose formatting on whichever half of
                    // the pair the running browser happened not to produce.
                    "b", "strong" -> "**$inner**"
                    "i", "em" -> "*$inner*"
                    "u" -> "<u>$inner</u>"
                    "s", "strike", "del" -> "~~$inner~~"
                    "code" -> "`$inner`"
                    // Anything else — a span execCommand left behind, a font tag,
                    // whatever — contributes its text and no markup. Never a
                    // guess: markup this file invents is markup renderMarkdown
                    // may not read back the same way.
                    else -> inner
                }
            }
        }
    }
}
