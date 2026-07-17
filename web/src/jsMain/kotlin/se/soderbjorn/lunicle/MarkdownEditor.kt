/**
 * The formatting editor: a toolbar over a contenteditable surface, storing markdown.
 *
 * ── How this stays honest ────────────────────────────────────────────────────
 *
 * WYSIWYG: the surface shows formatted text and never shows markup. Markdown is
 * still the only stored representation — the surface is serialised back to
 * markdown on every edit by [serialiseMarkdown], and reloaded through the same
 * [renderMarkdown] the issue view uses.
 *
 * The round-trip is where editors of this shape lose data, so it is closed
 * rather than trusted, by three things that have to hold together:
 *
 *  - **Paste is plain text, always.** This is the one that does the most work.
 *    A paste from Word carries a document's worth of markup, and no serialiser
 *    survives contact with it. Refusing that markup at the door means the only
 *    elements in the surface are ones this toolbar made.
 *  - **The serialiser is closed over the toolbar.** See MarkdownSerialiser.kt.
 *  - **Text is escaped on the way out.** So text containing a marker stays text.
 *    See Markdown.kt's ESCAPABLE.
 *
 * Formatting goes through `document.execCommand`. It is deprecated and it is
 * also the only thing every engine implements; the alternative is a custom
 * editing engine — selection, input events, undo — which is a project rather
 * than a file. `styleWithCSS` is turned off so it emits tags rather than
 * `<span style>`, because tags are what the serialiser reads.
 *
 * @see serialiseMarkdown
 * @see renderMarkdown
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
// Indexing an Int8Array. Needed alongside org.w3c.files.get below — they are
// different extensions that happen to share a name, and importing only the
// files one makes `array[i]` resolve to FileList's and fail on the receiver.
import org.khronos.webgl.get
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.files.File
import org.w3c.files.FileReader
import org.w3c.files.get
import se.soderbjorn.lunicle.client.isSafeMarkdownUrl
import se.soderbjorn.lunicle.client.renderMarkdown
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * A WYSIWYG editor over markdown.
 *
 * @param scope where the upload coroutine runs.
 * @param onChange called with the full markdown on every edit.
 * @param onUpload hands the picked file's bytes to the view model and returns
 *   the markdown to insert, or null if it failed. The *view* owns the file
 *   picker because opening one is irreducibly platform work; everything past
 *   "we have bytes" happens in the view model, which an iOS client would reuse.
 */
class MarkdownEditor(
    private val scope: CoroutineScope,
    private val onChange: (String) -> Unit,
    private val onUpload: suspend (filename: String, mimeType: String, bytes: ByteArray) -> String?,
) {
    private val root = element("div", "editor")
    private lateinit var surface: HTMLElement
    private lateinit var fileInput: HTMLInputElement
    private lateinit var linkRow: HTMLElement
    private lateinit var linkField: HTMLInputElement

    /**
     * A toolbar button and the question "is this what the caret is sitting in?".
     *
     * The pairing is the point. A toolbar that only applies formatting is
     * write-only: it can make text bold and then never admit that it did, so the
     * only way to find out what the caret is in is to look at the text and guess
     * — which is exactly what a WYSIWYG editor exists to save you from. Each
     * button carries its own predicate because only the button knows what it did.
     */
    private class Tool(val element: HTMLElement, val isActive: () -> Boolean)

    private val tools = mutableListOf<Tool>()

    /**
     * The markdown the surface currently holds, as this class last saw it.
     *
     * The whole reason [setValue] can be called on every state emission without
     * destroying the caret. Without it, each keystroke would round-trip through
     * the view model and come back as an `innerHTML` assignment that rebuilds
     * the document under the cursor, and typing would be impossible.
     */
    private var lastMarkdown: String? = null

    /**
     * Where the caret was before focus went somewhere it had to.
     *
     * Opening the file picker or typing in the link field takes the selection
     * away from the surface, and by the time the insert happens the browser has
     * no idea where it was meant to go — the text lands at the start of the
     * document, or nowhere. Saved on the way out, restored on the way back.
     */
    private var savedRange: dynamic = null

    /** Build and attach. */
    fun mount(host: HTMLElement) {
        surface = element("div", "editor-surface markdown")
        surface.contentEditable = "true"
        // The surface is a text field to anything that is not looking at it: a
        // contenteditable div announces as a group of prose without these.
        surface.setAttribute("role", "textbox")
        surface.setAttribute("aria-multiline", "true")
        surface.setAttribute("aria-label", "Description")
        // No placeholder attribute exists for contenteditable; the CSS draws one
        // off :empty. See .editor-surface:empty::before.
        surface.setAttribute("data-placeholder", "Describe the issue…")

        surface.oninput = { emit(); Unit }

        // Drop a file anywhere on the surface to attach it — the same path as
        // the Attach button, so an image lands inline and anything else lands
        // as a download link, with the same size check and the same error
        // reporting. The `onUpload` contract already does the deciding; this
        // only adds the gesture.
        //
        // preventDefault on dragover is what makes the surface a drop target at
        // all; without it the browser navigates to the dropped file, replacing
        // the app with a JPEG. Guarded on a Files payload so dragging *text*
        // across the editor keeps the browser's native text-drag behaviour.
        surface.addEventListener("dragover", { event ->
            val drag = event.asDynamic()
            val types = drag.dataTransfer?.types
            val hasFiles = types != null && (types.indexOf("Files") as? Int ?: -1) >= 0
            if (hasFiles && surface.contentEditable == "true") {
                event.preventDefault()
                root.classList.add("editor-drop")
            }
        })
        surface.addEventListener("dragleave", { root.classList.remove("editor-drop") })
        surface.addEventListener("drop", { event ->
            root.classList.remove("editor-drop")
            if (surface.contentEditable != "true") return@addEventListener
            val files = event.asDynamic().dataTransfer?.files
            val count = files?.length as? Int ?: 0
            if (count == 0) return@addEventListener
            event.preventDefault()
            // Drop the caret where the file was dropped, so the attachment
            // lands under the pointer rather than wherever the caret last was.
            // caretRangeFromPoint is non-standard-but-everywhere; when a
            // browser lacks it, the current caret (or the end) is still a
            // sane landing place.
            val mouse = event.asDynamic()
            savedRange = document.asDynamic().caretRangeFromPoint?.let { _ ->
                document.asDynamic().caretRangeFromPoint(mouse.clientX, mouse.clientY)
            } ?: currentRange()
            // Sequentially, not in parallel: each insert lands at the caret the
            // previous one left behind, so several dropped files end up in
            // drop order rather than in whatever order their reads finished.
            scope.launch {
                for (index in 0 until count) {
                    val file = files[index] as File
                    val bytes = file.readBytes()
                    val markdown = onUpload(file.name, file.type, bytes) ?: continue
                    restoreRange()
                    insertAttachment(inlineHtmlOf(markdown))
                    savedRange = currentRange()
                }
            }
        })

        surface.addEventListener("paste", { event ->
            event.preventDefault()
            // Plain text only. The single most important line in this file: it
            // is what guarantees the surface contains no markup the toolbar did
            // not make, which is what the serialiser's correctness rests on.
            // insertText rather than assigning the text, so it lands at the
            // caret and joins the browser's own undo stack.
            val text = event.asDynamic().clipboardData?.getData("text/plain") as? String
            exec("insertText", text.orEmpty())
        })

        val toolbar = element("div", "editor-toolbar")
        toolbar.children(
            // "Plain" first, and separated from the rest by a rule (see
            // .editor-btn-plain in styles.css): it is the way *out* of every
            // other button here, which makes it the odd one out rather than the
            // twelfth of a set.
            toolButton("Plain", "Plain text — clear formatting", "editor-btn-plain", ::isPlain) { makePlain() },
            toolButton("B", "Bold", "editor-btn-bold", { queryState("bold") }) { exec("bold") },
            toolButton("I", "Italic", "editor-btn-italic", { queryState("italic") }) { exec("italic") },
            // The one button with no markdown spelling. Inline <u> is the
            // accepted resolution, which is exactly why the renderer allow-lists
            // tags instead of passing HTML through. See Markdown.kt.
            toolButton("U", "Underline", "editor-btn-underline", { queryState("underline") }) { exec("underline") },
            toolButton("S", "Strikethrough", "editor-btn-strike", { queryState("strikeThrough") }) { exec("strikeThrough") },
            toolButton("H1", "Heading 1", isActive = { blockTag() == "h1" }) { exec("formatBlock", "<h1>") },
            toolButton("H2", "Heading 2", isActive = { blockTag() == "h2" }) { exec("formatBlock", "<h2>") },
            toolButton("H3", "Heading 3", isActive = { blockTag() == "h3" }) { exec("formatBlock", "<h3>") },
            toolButton("•", "Bullet list", isActive = { queryState("insertUnorderedList") }) { exec("insertUnorderedList") },
            toolButton("`", "Code", isActive = ::isInCode) { insertCode() },
            // Link and Image open something rather than toggling something, so
            // there is no state for them to be in and no predicate to give them.
            toolButton("Link", "Insert a link") { openLinkRow() },
            // One button, not "Image" and "File" beside it. The user does not
            // know which of the two their thing is — a .heic, a .svg, a screen
            // recording — and making them guess is making them guess about our
            // mime-type allow-list. They pick a file; the view model decides how
            // it is spelled. See attachmentMarkdown.
            toolButton("Attach", "Attach a file") { pickFile() },
        )

        buildLinkRow()

        // Hidden, and clicked programmatically by the Attach button: a raw file
        // input cannot be styled to match anything, and this way the toolbar
        // stays one row of consistent buttons.
        fileInput = (document.createElement("input") as HTMLInputElement).apply {
            type = "file"
            // No `accept`. It said "image/*" while images were all this took,
            // and it was never a check — the attribute is trivially bypassed and
            // a file can be renamed anyway, so the rules live on the server. Now
            // that any file is welcome there is nothing left for it to hint at,
            // and an `accept` narrower than what the server takes is worse than
            // none: it greys out files the app would happily have stored, and
            // the user reads that as "not supported" rather than "try it".
            style.display = "none"
            onchange = { onFilePicked(); Unit }
        }

        root.children(toolbar, linkRow, surface, fileInput)
        linkRow.visible(false)
        host.appendChild(root)

        // `selectionchange` is the only event that fires for *every* way the
        // caret moves. Watching keyup and mouseup on the surface instead misses
        // the ones that matter most — a click that lands the caret via the
        // browser's own logic, an arrow key held down, an undo, a
        // select-all — and a toolbar that is right except after those is worse
        // than one that never highlights at all, because you learn to trust it.
        //
        // It only exists on `document`, never on an element, which is why this
        // listener is global and has to filter by where the selection landed.
        document.addEventListener("selectionchange", {
            if (isSelectionInSurface()) refreshToolbar()
        })

        // Tags, not inline styles: execCommand defaults to <span style="..."> in
        // some engines, and the serialiser reads tags. Without this, bold would
        // be applied, look right, and serialise to nothing at all — the text
        // survives, the formatting silently does not.
        exec("styleWithCSS", "false", emitAfter = false)
    }

    /**
     * Set the text from the view model, without fighting the caret.
     *
     * The guard is what makes the render-on-every-emission loop safe: typing
     * emits markdown, the view model hands the same markdown straight back, and
     * this recognises it as already on screen and leaves the DOM — and the
     * selection inside it — alone. Only a value from somewhere else (the initial
     * load) is worth rebuilding for.
     */
    fun setValue(value: String) {
        if (value == lastMarkdown) return
        lastMarkdown = value
        // innerHTML with renderMarkdown's output, which is the one place in this
        // app that is allowed — everything it emits is a tag it built itself,
        // and every byte of user input inside it was escaped first. See
        // Markdown.kt's preamble.
        surface.innerHTML = renderMarkdown(value)
    }

    fun setEnabled(enabled: Boolean) {
        surface.contentEditable = enabled.toString()
        tools.forEach { (it.element as? org.w3c.dom.HTMLButtonElement)?.disabled = !enabled }
        if (!enabled) {
            linkRow.visible(false)
            // Clear the highlights on the way out. A disabled toolbar still
            // showing "B" lit describes a caret that is no longer anywhere.
            tools.forEach { it.element.classList.remove("editor-btn-on") }
        }
        root.classList.toggle("editor-disabled", !enabled)
    }

    // ── Toolbar ──────────────────────────────────────────────────────────────

    private fun toolButton(
        label: String,
        title: String,
        extraClass: String = "",
        isActive: (() -> Boolean)? = null,
        onClick: () -> Unit,
    ): HTMLElement {
        val el = button(label, "editor-btn $extraClass".trim(), onClick)
        el.title = title
        // The surface must keep the selection when a toolbar button is pressed:
        // a plain click moves focus to the button first, collapsing it, and
        // every command would then apply to nothing.
        el.addEventListener("mousedown", { event -> event.preventDefault() })
        // `aria-pressed` only on the buttons that have a state. A toggle button
        // announces as a toggle; Link and Image are not toggles, and claiming
        // they are would have a screen reader offer to un-press "Image".
        if (isActive != null) el.setAttribute("aria-pressed", "false")
        tools.add(Tool(el, isActive ?: { false }))
        return el
    }

    /**
     * Light the buttons whose formatting the caret is currently inside.
     *
     * Called after every command and on every selection change. Cheap enough to
     * run unconditionally: it is a dozen `queryCommandState` calls, all of which
     * read state the engine already has, and `classList.toggle` to the value it
     * already holds is not a mutation.
     */
    private fun refreshToolbar() {
        tools.forEach { tool ->
            val active = tool.isActive()
            tool.element.classList.toggle("editor-btn-on", active)
            if (tool.element.hasAttribute("aria-pressed")) {
                tool.element.setAttribute("aria-pressed", active.toString())
            }
        }
    }

    /**
     * Whether the caret is inside the surface.
     *
     * The `selectionchange` listener is on the document, so it fires for the link
     * field, for the title field, and for a selection in a wholly different
     * dialog. Without this the toolbar would redraw itself from whatever the
     * caret was in last, and — worse — `queryCommandState` answers about *that*
     * selection, so the buttons would light up describing someone else's text.
     */
    private fun isSelectionInSurface(): Boolean {
        val selection = window.asDynamic().getSelection() ?: return false
        val node = selection.anchorNode as? org.w3c.dom.Node ?: return false
        return surface.contains(node)
    }

    /**
     * Run an editing command.
     *
     * `dynamic` because execCommand is not on Kotlin's Document — it is
     * deprecated, so the bindings never grew it — and this is the whole of the
     * workaround rather than an external declaration nobody would find.
     */
    private fun exec(command: String, value: String? = null, emitAfter: Boolean = true) {
        document.asDynamic().execCommand(command, false, value)
        if (emitAfter) {
            surface.focus()
            emit()
            // Explicitly, rather than waiting for `selectionchange`: applying a
            // command does not always move the caret, so the event may never
            // come, and the button the user just pressed would stay unlit until
            // they clicked somewhere. Focusing the surface above can fire it too,
            // which makes this a second call in some paths and a no-op in them.
            refreshToolbar()
        }
    }

    /**
     * Ask the engine whether a toggling command is on at the caret.
     *
     * `queryCommandState` throws rather than returning false for a command the
     * engine does not know, and this runs on every selection change — so an
     * engine that does not implement `strikeThrough` would throw a dozen times a
     * second into the console while the user typed. `false` is the right answer
     * for "cannot tell": the button not lighting up is a smaller lie than it
     * lighting up wrongly.
     */
    private fun queryState(command: String): Boolean =
        runCatching { document.asDynamic().queryCommandState(command) as? Boolean ?: false }.getOrDefault(false)

    /**
     * The tag name of the block the caret is in — "h1", "p", "div", "".
     *
     * Engines disagree about the shape of this: some answer "h1" and some answer
     * "<h1>", hence the trim. Chrome answers "" rather than "div" for a caret in
     * a bare contenteditable with no block wrapper at all, which [isPlain] has to
     * treat as a paragraph.
     */
    private fun blockTag(): String =
        runCatching { document.asDynamic().queryCommandValue("formatBlock") as? String }
            .getOrNull()
            .orEmpty()
            .lowercase()
            .trim('<', '>')

    /** Whether the caret sits inside a `<code>` — which execCommand has no query for. */
    private fun isInCode(): Boolean = closestInSurface("CODE") != null

    /**
     * The nearest ancestor of the caret with [tagName], stopping at the surface.
     *
     * Stopping there matters: the surface itself sits inside a modal that has who
     * knows what around it, and walking past it would start answering questions
     * about the page rather than about the text.
     */
    private fun closestInSurface(tagName: String): org.w3c.dom.Node? {
        val selection = window.asDynamic().getSelection() ?: return null
        var node = selection.anchorNode as? org.w3c.dom.Node ?: return null
        if (!surface.contains(node)) return null
        while (node != surface) {
            if ((node as? org.w3c.dom.Element)?.tagName == tagName) return node
            node = node.parentNode ?: return null
        }
        return null
    }

    /**
     * Whether the caret is in text carrying no formatting at all.
     *
     * Defined as the absence of every other button rather than as a state of its
     * own, because that is what it is — there is no "plain" tag to look for, only
     * the absence of the others. The consequence worth knowing: adding a button
     * to this toolbar without adding it here leaves "Plain" claiming text is
     * unformatted while that new button is lit beside it.
     */
    private fun isPlain(): Boolean =
        blockTag().let { it.isEmpty() || it == "p" || it == "div" } &&
            !queryState("bold") &&
            !queryState("italic") &&
            !queryState("underline") &&
            !queryState("strikeThrough") &&
            !queryState("insertUnorderedList") &&
            !isInCode()

    /**
     * Strip everything back to plain text.
     *
     * Four steps, because no single command does it — each of the four things
     * this toolbar can make is undone a different way:
     *
     *  1. `removeFormat` — the inline tags (b, i, u, strike). It is also the only
     *     one of the four that acts on a *range* rather than a block, so it goes
     *     first, while the selection is still whatever the user made.
     *  2. `<code>` by hand: removeFormat leaves it alone in every engine, because
     *     `<code>` is semantic markup rather than styling as far as the spec is
     *     concerned. The serialiser reads it, so leaving it would produce text
     *     that says it is plain and serialises to backticks.
     *  3. The list, which `formatBlock` cannot undo — a list item is not a block
     *     it recognises, so formatting it as `<p>` leaves the `<li>` in place and
     *     puts a paragraph inside it.
     *  4. `formatBlock <p>` — the headings.
     *
     * Links deliberately survive. A link is content — the URL is information the
     * user typed and cannot get back — where bold is only ever appearance. Losing
     * one to a button labelled "clear formatting" is not a trade anyone would
     * make knowingly.
     */
    private fun makePlain() {
        exec("removeFormat", emitAfter = false)
        unwrapCode()
        if (queryState("insertUnorderedList")) exec("insertUnorderedList", emitAfter = false)
        exec("formatBlock", "<p>")
    }

    /**
     * Replace the `<code>` around the caret with its own text.
     *
     * Only the one the caret is in, not every one the selection touches. A
     * selection spanning several `<code>` runs is a real thing the user can make,
     * and this leaves the others alone — but the alternative is walking the range
     * and rebuilding the DOM under it by hand, which is the custom editing engine
     * this file's preamble explains we are not writing. The caret case is the one
     * people actually hit: they typed something as code and want it back.
     */
    private fun unwrapCode() {
        val code = closestInSurface("CODE") as? HTMLElement ?: return
        val text = document.createTextNode(code.textContent.orEmpty())
        code.parentNode?.replaceChild(text, code)
    }

    /**
     * Wrap the selection in `<code>`.
     *
     * By hand because execCommand has no code command. Inserting a placeholder
     * word for an empty selection rather than an empty tag: an empty `<code>`
     * has nowhere to put the caret and the button would look dead.
     */
    private fun insertCode() {
        val selected = window.asDynamic().getSelection()?.toString() as? String
        val text = selected?.takeIf { it.isNotBlank() } ?: "code"
        exec("insertHTML", "<code>${htmlEscape(text)}</code>")
    }

    // ── Links ────────────────────────────────────────────────────────────────

    /**
     * The link row: a field and an Apply button, under the toolbar.
     *
     * Not `window.prompt`, for the same reason the rest of this app does not use
     * it: it blocks the event loop and, inside the lunamux.dev iframe, presents
     * as a dialog from the framing page. The old markdown editor let the user
     * type the URL into the text as `[label](https://)` — which a WYSIWYG
     * surface cannot do, because the URL is exactly what it does not show.
     */
    private fun buildLinkRow() {
        linkRow = element("div", "editor-link-row")
        linkField = textField("https://") { }
        linkField.addEventListener("keydown", { event ->
            when (event.asDynamic().key as? String) {
                "Enter" -> { event.preventDefault(); applyLink() }
                "Escape" -> {
                    // Stop it here, or the modal's document-level listener sees
                    // it and closes the whole dialog — losing the draft — when
                    // the user only meant to abandon the URL.
                    event.preventDefault()
                    event.stopPropagation()
                    linkRow.visible(false)
                    restoreRange()
                }
            }
        })
        linkRow.children(linkField, button("Apply", "btn btn-quiet") { applyLink() })
    }

    private fun openLinkRow() {
        savedRange = currentRange()
        linkRow.visible(true, displayValue = "flex")
        linkField.value = "https://"
        linkField.focus()
        // Caret at the end, not a selection: "https://" is a prefix to type
        // after, not a value to replace.
        linkField.setSelectionRange(linkField.value.length, linkField.value.length)
    }

    private fun applyLink() {
        val url = linkField.value.trim()
        linkRow.visible(false)
        restoreRange()
        if (url.isBlank() || url == "https://") return
        // The same check renderMarkdown makes, asked here too. Not belt and
        // braces: renderMarkdown refuses a javascript: URL when the issue is
        // *read*, but without this the href would still be live in the document
        // being edited, and this is the one page where the author is the one
        // clicking it.
        if (!isSafeMarkdownUrl(url)) return

        val selection = window.asDynamic().getSelection()
        if (selection == null || selection.isCollapsed as Boolean) {
            // Nothing selected: the URL is its own label, which is what every
            // editor does and what someone pasting a bare link expects.
            exec("insertHTML", """<a href="${htmlEscape(url)}">${htmlEscape(url)}</a>""")
        } else {
            exec("createLink", url)
        }
    }

    // ── Attachments ──────────────────────────────────────────────────────────

    private fun pickFile() {
        savedRange = currentRange()
        fileInput.click()
    }

    private fun onFilePicked() {
        val file = fileInput.files?.get(0) ?: return
        // Reset immediately: without this, picking the same file twice in a row
        // fires no change event the second time, and the button looks dead.
        fileInput.value = ""
        scope.launch {
            val bytes = file.readBytes()
            // `file.type` is the browser's guess, and it is allowed to be "" —
            // for an extension it does not recognise, which after this change is
            // a thing that reaches us. Passed on as-is rather than guessed at
            // here: the view model and the server both treat an unrecognised
            // type as "not an image, so a download", which is the correct answer
            // for a file nobody can identify.
            val markdown = onUpload(file.name, file.type, bytes) ?: return@launch
            restoreRange()
            insertAttachment(inlineHtmlOf(markdown))
        }
    }

    /**
     * Insert an attachment on a line of its own, with an empty line either side.
     *
     * ── The bug this fixes ──────────────────────────────────────────────────
     *
     * An image inserted inline is frequently impossible to get the caret in front
     * of. If it lands as the first thing in the surface, or as the only thing in
     * its block, there is no text position before it — contenteditable places the
     * caret in *text*, and there is none — so clicking to its left does nothing
     * and Home does nothing, and the image can never be typed above. Every editor
     * that allows an image at the top of a document deals with this somehow; the
     * empty paragraph is the oldest and least clever way, and it is the one that
     * needs no per-browser caret arithmetic.
     *
     * So: `<p><br></p>` above, the image in its own `<p>`, `<p><br></p>` below.
     * Both are real, selectable lines. The `<br>` is what makes an empty paragraph
     * have a caret position at all — a bare `<p></p>` has zero height and cannot
     * be clicked into.
     *
     * The cost is two empty lines the user may not want, which they can delete
     * with Backspace like any other empty line. The alternative — an image you
     * cannot type above — is not something they can fix at all.
     *
     * The serialiser turns an empty paragraph into nothing, so this does not
     * litter the stored markdown with blank lines. See MarkdownSerialiser.
     *
     * A non-image attachment is a link, and a link has text either side of it, so
     * none of the caret arithmetic above applies to one. It gets the same
     * treatment anyway: a download is a block-level thing to a reader, sitting
     * between paragraphs rather than inside a sentence, and the alternative is
     * this function taking an argument to do the obviously-right thing to one
     * kind of attachment and something else to the other.
     */
    private fun insertAttachment(attachmentHtml: String) {
        exec("insertHTML", "<p><br></p><p>$attachmentHtml</p><p><br></p>")
    }

    /**
     * Render a fragment of markdown to the HTML that goes into the surface.
     *
     * Through [renderMarkdown] rather than by building an `<img>` here, so the
     * URL passes the same scheme check as everything else and there is one
     * markdown→HTML implementation rather than two. The unwrap is because that
     * function renders blocks: a lone image comes back as `<p><img></p>`, and
     * inserting a paragraph at the caret would split the one being typed in.
     */
    private fun inlineHtmlOf(markdown: String): String {
        val temp = document.createElement("div") as HTMLElement
        temp.innerHTML = renderMarkdown(markdown)
        val only = temp.children.asList().singleOrNull()
        return if (only != null && only.tagName.lowercase() == "p") only.innerHTML else temp.innerHTML
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private fun emit() {
        val markdown = serialiseMarkdown(surface)
        lastMarkdown = markdown
        onChange(markdown)
    }

    private fun currentRange(): dynamic {
        val selection = window.asDynamic().getSelection() ?: return null
        if ((selection.rangeCount as? Int ?: 0) == 0) return null
        return selection.getRangeAt(0)
    }

    private fun restoreRange() {
        surface.focus()
        val range = savedRange ?: return
        val selection = window.asDynamic().getSelection() ?: return
        selection.removeAllRanges()
        selection.addRange(range)
    }

    /**
     * Escape text for insertion as HTML, by letting the DOM do it.
     *
     * `textContent` in, `innerHTML` out: the browser's own escaping, which is by
     * definition the one the browser will parse back correctly. A hand-rolled
     * one here would be a second escaper in a codebase that already has the one
     * in Markdown.kt, and the second one is always the one with the hole.
     */
    private fun htmlEscape(text: String): String {
        val temp = document.createElement("div")
        temp.textContent = text
        return temp.innerHTML
    }
}

/**
 * Read a picked file into a [ByteArray].
 *
 * `FileReader` is callback-based and Kotlin's coroutines are not, so this is the
 * bridge. `readAsArrayBuffer` rather than `readAsDataURL`: a data URL would be
 * base64, which is a third larger and would then have to be decoded back to
 * bytes anyway — the upload sends the raw body.
 */
private suspend fun File.readBytes(): ByteArray = suspendCoroutine { continuation ->
    val reader = FileReader()
    reader.onload = {
        val buffer = reader.result as ArrayBuffer
        val array = Int8Array(buffer)
        continuation.resume(ByteArray(array.length) { index -> array[index] })
    }
    reader.onerror = {
        // An empty array rather than an exception: the server rejects an empty
        // upload with a message the user can read ("That file is empty"), which
        // is a better outcome than a thrown error nobody catches. Rare enough —
        // FileReader fails when the file was moved after being picked.
        println("Editor: could not read the picked file")
        continuation.resume(ByteArray(0))
    }
    reader.readAsArrayBuffer(this)
}
