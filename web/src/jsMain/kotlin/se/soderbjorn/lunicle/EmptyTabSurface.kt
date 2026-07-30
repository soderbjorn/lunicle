/*
 * EmptyTabSurface.kt (jsMain)
 * ---------------------------
 * What Lunicle shows when there is nothing to show (LNL-192).
 *
 * Two shapes of the same card, because two very different readers reach it:
 *
 *  - [emptyTabLanding] — the whole window, chrome and all, for a signed-out
 *    visitor who can reach no project. Nothing in the app's chrome is any use
 *    to them: the tab strip has one empty tab, the sidebar lists it, the "+"
 *    creates what they cannot keep. Covering it is not decoration — it is the
 *    difference between a product that has nothing for you yet and one that
 *    looks broken. Driven from main.kt, not from the toolkit, precisely
 *    because it must outrank the chrome rather than sit inside it.
 *  - [emptyTabSurface] — the canvas alone, for a reader who IS inside the app
 *    and has merely emptied a tab. Their chrome is the way out, so it stays.
 *    Mounted by the toolkit through `AppShellSpec.emptyTabContent`, for
 *    exactly as long as the active tab holds no pane.
 *
 * Which sentence either one carries is [MainScreenBackingViewModel.EmptyTab]'s
 * to decide, worked out from who is reading; see that.
 *
 * Styles ride on the `.empty-tab*` classes in styles.css.
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.client.viewmodel.MainScreenBackingViewModel

/**
 * The full-window landing page: the card, centred on a backdrop that covers the
 * app entirely.
 *
 * Opaque rather than translucent, and sized in viewport units rather than to a
 * parent, so nothing of the shell shows through or around it. The shell keeps
 * running underneath — hiding it outright would have the toolkit re-measure
 * pane geometry against a zero-sized box the moment a project arrives — so the
 * caller marks it inert; see main.kt.
 *
 * @see emptyTabSurface for the in-canvas shape, and for the parameters.
 */
fun emptyTabLanding(
    empty: MainScreenBackingViewModel.EmptyTab,
    brandLogoSvg: String? = null,
    note: String? = null,
    onSignIn: (() -> Unit)? = null,
): HTMLElement = element("div", "empty-tab-landing").children(
    emptyTabCard(empty, brandLogoSvg, note, onSignIn),
)

/**
 * The in-canvas shape: the same card, centred on the tab's empty canvas.
 *
 * @param empty what to say.
 * @param brandLogoSvg the deployment's mark, or null to leave it off — the
 *   caller decides which readers get a hero, since that is a question about the
 *   reader rather than about the drawing.
 * @param note the deployment's own words about itself, or null for none. See
 *   [Brand.landingNote].
 * @param onSignIn the sign-in door, or null when there is none to offer. Null
 *   omits the button rather than disabling it: a control that cannot work reads
 *   as the app being broken, which is the impression this surface exists to
 *   prevent.
 */
fun emptyTabSurface(
    empty: MainScreenBackingViewModel.EmptyTab,
    brandLogoSvg: String? = null,
    note: String? = null,
    onSignIn: (() -> Unit)? = null,
): HTMLElement = element("div", "empty-tab").children(
    emptyTabCard(empty, brandLogoSvg, note, onSignIn),
)

/** The card both shapes centre — mark, headline, instruction, note, way in. */
private fun emptyTabCard(
    empty: MainScreenBackingViewModel.EmptyTab,
    brandLogoSvg: String?,
    note: String?,
    onSignIn: (() -> Unit)?,
): HTMLElement {
    val card = element("div", "empty-tab-card")
    brandLogoSvg?.let {
        card.appendChild(brandLogo(it).also { el -> el.className += " empty-tab-logo" })
    }
    card.children(
        element("h1", "empty-tab-headline", empty.headline),
        element("p", "empty-tab-detail", empty.detail),
    )
    if (onSignIn != null) {
        // The ellipsis matches the top-bar button, and for its reason: this hands
        // off to the picker or an OAuth redirect, it does not sign anybody in on
        // the spot.
        card.appendChild(button("Sign in…", "btn btn-primary empty-tab-action") { onSignIn() })
    }
    // Last, and under a rule: the deployment's words are the footnote to this
    // page, not its subject. Somebody who has an account wants the button, and
    // reading past a paragraph to find it is the wrong order.
    note?.takeIf { it.isNotBlank() }?.let { text ->
        val block = element("div", "empty-tab-note")
        // Lunicle's own mark, at the head of the footnote rather than up top: the
        // hero belongs to whoever runs this instance, and the note is the paragraph
        // that says what the software under it is. Small, and the product's built-in
        // glyph rather than a wordmark — a second mark the size of the first would
        // read as two brands competing for the same page.
        block.appendChild(
            element("div", "empty-tab-note-mark").children(
                logoIcon(),
                element("span", "empty-tab-note-name", "Lunicle"),
            ),
        )
        // A blank line starts a paragraph, the one piece of structure a brand
        // gets. Written as `\n\n` in brand.json, which is what an author reaches
        // for anyway, and it keeps the field plain text rather than a markup
        // dialect the deployment would then have to learn.
        PARAGRAPH_BREAK.split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { block.appendChild(noteParagraph(it)) }
        card.appendChild(block)
    }
    return card
}

/**
 * The brand's note, with bare `http(s)` URLs turned into links.
 *
 * Built out of text nodes and anchors rather than assigned as HTML: the string
 * comes from a deployment's `brand.json`, and while that file is the operator's
 * own, a surface that renders arbitrary markup from a config file is one that
 * will eventually render markup from somewhere else. Trailing sentence
 * punctuation is left OUT of the link — "read more at https://lunicle.dev/."
 * ends in a full stop that belongs to the sentence, not the address.
 */
private fun noteParagraph(text: String): HTMLElement {
    val p = element("p", "empty-tab-note-line")
    var cursor = 0
    for (match in URL_PATTERN.findAll(text)) {
        val href = match.value.trimEnd('.', ',', ';', ':', ')')
        val start = match.range.first
        if (start > cursor) p.appendChild(document.createTextNode(text.substring(cursor, start)))
        val link = document.createElement("a") as HTMLAnchorElement
        link.className = "empty-tab-note-link"
        link.href = href
        // Opened away from the app, and told not to hand the opener a window
        // handle back — the standard pair for any link leaving Lunicle.
        link.target = "_blank"
        link.rel = "noopener noreferrer"
        link.textContent = href
        p.appendChild(link)
        cursor = start + href.length
    }
    if (cursor < text.length) p.appendChild(document.createTextNode(text.substring(cursor)))
    return p
}

/** Bare URLs in prose: everything up to whitespace, trimmed of trailing punctuation above. */
private val URL_PATTERN = Regex("""https?://\S+""")

/** A blank line — one or more newlines with only whitespace between them. */
private val PARAGRAPH_BREAK = Regex("""\n\s*\n""")
