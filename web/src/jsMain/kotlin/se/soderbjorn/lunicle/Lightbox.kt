/**
 * Click an image, see it big. Click again, or press Escape, and it goes away.
 *
 * ── Why this is one document listener and not a handler per image ───────────
 *
 * Because the images are not ours to attach handlers to. Every one of them is
 * produced by `renderMarkdown` and written into a container with `innerHTML`, and
 * that happens again on every state emission that changes the text — an issue
 * re-rendering, a comment being edited, a description reloading. Handlers bound
 * to those elements would be thrown away and re-bound constantly, and every
 * missed re-bind is an image that silently stops opening.
 *
 * One listener on the document, matching by ancestry, cannot go stale. It works
 * for images that do not exist yet, which is all of them.
 *
 * ── What it deliberately does not touch ─────────────────────────────────────
 *
 * Images inside the editor's surface. There, a click means "put the caret here",
 * and stealing it to open a viewer would make an image impossible to type around
 * — which is the opposite of the problem being solved. The editor scales its
 * images the same way (see .markdown img) but leaves the click alone.
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.events.Event

/**
 * The full-screen image viewer.
 *
 * @param host where the overlay mounts. The dialog host, so the overlay stacks
 *   above the modals an image is usually being read inside — an image opened from
 *   the issue dialog must appear over it, not behind it.
 */
class Lightbox(private val host: HTMLElement) {
    private var backdrop: HTMLElement? = null

    /** Start listening. Called once by the bootstrap. */
    fun install() {
        document.addEventListener("click", { event ->
            val target = event.target as? HTMLImageElement ?: return@addEventListener
            // Rendered markdown only, and never the editor's copy of it. `closest`
            // rather than a parent check: the image sits inside whatever the
            // markdown put around it — a paragraph, a link — so its distance from
            // the container is not fixed.
            if (target.closest(".markdown") == null) return@addEventListener
            if (target.closest(".editor-surface") != null) return@addEventListener
            event.preventDefault()
            open(target.src, target.alt)
        })
        document.addEventListener("keydown", { event ->
            // Escape closes, like every other layer in this app. Checked before
            // the modals get it — the overlay is on top, so it is what the user
            // means. Modal's own listener runs on the same event, which is why
            // this stops propagation once it has something to close.
            if (event.asDynamic().key as? String != "Escape") return@addEventListener
            if (backdrop == null) return@addEventListener
            event.stopPropagation()
            close()
        })
    }

    /**
     * Show [src] full-screen.
     *
     * The overlay is built fresh each time and destroyed on close rather than
     * being hidden and reused. It holds exactly one `<img>`, and a retained one
     * would keep the last image's bytes alive — and, worse, flash the previous
     * image for a frame before the new `src` decodes.
     */
    private fun open(src: String, alt: String) {
        close()
        val element = element("div", "lightbox")
        // Any click closes, including one on the image. There is nothing to do in
        // here, so every click is a request to leave — a viewer where clicking the
        // thing you clicked to open does nothing is a viewer people get stuck in.
        element.onclick = { close() }
        element.setAttribute("role", "dialog")
        element.setAttribute("aria-modal", "true")

        val image = document.createElement("img") as HTMLImageElement
        image.className = "lightbox-image"
        image.src = src
        // Carried over rather than blanked: the alt text is the only description
        // of the image that exists, and this is the view where the image is the
        // whole content.
        image.alt = alt

        val hint = element("p", "lightbox-hint", "Click anywhere, or press Escape, to close")

        element.children(image, hint)
        host.appendChild(element)
        backdrop = element
    }

    private fun close() {
        backdrop?.remove()
        backdrop = null
    }
}

/** `closest` is not on Kotlin's Element bindings; it is on every browser's. */
private fun HTMLElement.closest(selector: String): HTMLElement? =
    asDynamic().closest(selector) as? HTMLElement
