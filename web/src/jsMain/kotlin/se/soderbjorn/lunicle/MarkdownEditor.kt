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
import se.soderbjorn.lunicle.client.NO_TICKET_TITLES
import se.soderbjorn.lunicle.client.TicketTitleLookup
import se.soderbjorn.lunicle.client.isSafeMarkdownUrl
import se.soderbjorn.lunicle.client.renderMarkdown
import se.soderbjorn.lunicle.clientserver.mentionCompletions
import se.soderbjorn.lunicle.clientserver.mentionMarkdown
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
 * @param placeholder what the empty surface says, and its accessible label.
 *
 *   The one thing in this class that was ever about issues. It said "Describe the
 *   issue…" unconditionally, which is the right sentence in the issue editor and
 *   a wrong one in a forum post — so it became a parameter, defaulted to what it
 *   already said. The default is what keeps this a two-line change rather than an
 *   edit to every existing caller, and the parameter is what stops the forum from
 *   having to either live with the wrong words or fork the editor. Nothing else
 *   here needed touching for LNL-61; see that ticket, which predicted as much.
 */
/**
 * One issue the ticket autocomplete can offer (LNL-139): the reference to insert,
 * its number to filter and rank by, and its title to show beside it.
 */
class TicketOption(val ticket: String, val number: Long, val title: String)

/**
 * Everything an editor (or a reading surface) needs for ticket references
 * (LNL-139), bundled so it passes down the window tree as one thing: the accessible
 * project prefixes to link and complete for, the lookup that turns a prefix
 * into that project's issues, and — for a reading surface — the synchronous title
 * resolver that expands a rendered reference to `PREFIX-N: Title` (LNL-144). Built
 * once in the bootstrap; see main.kt.
 */
class TicketSource(
    val prefixes: () -> List<String>,
    val lookup: suspend (prefix: String) -> List<TicketOption>,
    val titleFor: TicketTitleLookup = NO_TICKET_TITLES,
)

/** At most this many rows in the ticket popup — a longer list buries the text. */
private const val TICKET_OPTION_LIMIT = 8

class MarkdownEditor(
    private val scope: CoroutineScope,
    private val onChange: (String) -> Unit,
    private val onUpload: suspend (filename: String, mimeType: String, bytes: ByteArray) -> String?,
    private val placeholder: String = "Describe the issue…",
) {
    private val root = element("div", "editor")
    private lateinit var surface: HTMLElement
    private lateinit var fileInput: HTMLInputElement
    private lateinit var linkRow: HTMLElement
    private lateinit var linkField: HTMLInputElement

    /**
     * A partly-typed `@mention` under the caret.
     *
     * The node and the two offsets are what the completion has to *replace*, and
     * they are recomputed from the live selection on every keystroke rather than
     * remembered — a remembered offset into a contenteditable is a stale offset
     * one edit later, and replacing the wrong range is how a completion eats the
     * word before it.
     */
    private class MentionTarget(
        /** The text node the `@` is in. `dynamic` for the same reason [savedRange] is. */
        val node: dynamic,
        /** Offset of the `@` itself. */
        val at: Int,
        /** Offset of the caret — the end of what has been typed so far. */
        val caret: Int,
        /** What was typed after the `@`, which may be empty. */
        val query: String,
    )

    /** Who may be mentioned here. Empty disables the whole feature — see [setMentionNames]. */
    private var mentionNames: List<String> = emptyList()

    /** The popup. Built once in [mount], shown and hidden by moving it in and out of the body. */
    private lateinit var mentionMenu: HTMLElement

    /** The mention being typed, or null when the caret is not in one. */
    private var mentionTarget: MentionTarget? = null

    /** The names currently on offer, in the order they are drawn. */
    private var mentionOptions: List<String> = emptyList()

    /** Which of [mentionOptions] the arrow keys have landed on. */
    private var mentionIndex: Int = 0

    /**
     * A partly-typed `PREFIX-<digits>` under the caret (LNL-139).
     *
     * The ticket twin of [MentionTarget]: [start]..[caret] is what a pick replaces,
     * recomputed each keystroke for the same reason a mention's offsets are.
     */
    private class TicketTarget(
        /** The text node the reference is in. `dynamic`, as [MentionTarget.node] is. */
        val node: dynamic,
        /** Offset of the prefix's first character — where a pick starts replacing. */
        val start: Int,
        /** Offset of the caret — the end of what has been typed. */
        val caret: Int,
        /** The project prefix, upper-cased to canonical form. */
        val prefix: String,
        /** The digits typed after the hyphen, which may be empty. */
        val query: String,
    )

    /** The reader's accessible project prefixes; empty disables the popup. See [setTicketSource]. */
    private var ticketPrefixes: () -> List<String> = { emptyList() }

    /** Fetches one project's issues for the popup to offer, or null when the feature is off. */
    private var ticketLookup: (suspend (prefix: String) -> List<TicketOption>)? = null

    /** Issues by prefix once fetched, so keystrokes after the first do not refetch. */
    private val ticketCache = mutableMapOf<String, List<TicketOption>>()

    /** Prefixes whose fetch is in flight, so a burst of keystrokes launches just one. */
    private val ticketFetching = mutableSetOf<String>()

    /** The ticket popup. Built once, shown by moving it in and out of the body, as [mentionMenu] is. */
    private lateinit var ticketMenu: HTMLElement

    /** The reference being typed, or null when the caret is not in one. */
    private var ticketTarget: TicketTarget? = null

    /** The issues currently on offer, in draw order. */
    private var ticketOptions: List<TicketOption> = emptyList()

    /** Which of [ticketOptions] the arrow keys have landed on. */
    private var ticketIndex: Int = 0

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
     *
     * A *hint*, not an instruction: what gets saved is the page's selection, and
     * that is only in the surface when the user had actually been typing there.
     * See [putCaretBackInto], which is where the difference is decided, and which
     * is the whole of LNL-160.
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
        surface.setAttribute("aria-label", placeholder)
        // No placeholder attribute exists for contenteditable; the CSS draws one
        // off :empty. See .editor-surface:empty::before.
        surface.setAttribute("data-placeholder", placeholder)

        surface.oninput = { emit(); refreshMentionMenu(); refreshTicketMenu(); Unit }

        // Whichever popup is open owns four keys while it is, and only while it is —
        // Enter must still make a paragraph, and Escape must still reach the modal,
        // the rest of the time. The ticket popup is checked first, but the two are
        // never open at once (an `@` and a `PREFIX-` cannot both sit under one caret).
        //
        // On the surface in the bubble phase, and stopping propagation on Escape:
        // Escape is listened for at the document level by Modal, and an Escape meant
        // to dismiss an autocomplete must not also throw away the comment it is being
        // typed into.
        surface.addEventListener("keydown", { event ->
            val key = event.asDynamic().key as? String
            if (ticketTarget != null && ticketOptions.isNotEmpty()) {
                when (key) {
                    "ArrowDown" -> { event.preventDefault(); moveTicketSelection(1) }
                    "ArrowUp" -> { event.preventDefault(); moveTicketSelection(-1) }
                    "Enter", "Tab" -> { event.preventDefault(); applyTicket(ticketOptions[ticketIndex]) }
                    "Escape" -> { event.preventDefault(); event.stopPropagation(); hideTicketMenu() }
                }
                return@addEventListener
            }
            if (mentionTarget == null || mentionOptions.isEmpty()) return@addEventListener
            when (key) {
                "ArrowDown" -> {
                    event.preventDefault()
                    moveMentionSelection(1)
                }
                "ArrowUp" -> {
                    event.preventDefault()
                    moveMentionSelection(-1)
                }
                // Tab as well as Enter, because every autocomplete anyone has used
                // takes both, and the surface has nothing else to do with a Tab.
                "Enter", "Tab" -> {
                    event.preventDefault()
                    applyMention(mentionOptions[mentionIndex])
                }
                "Escape" -> {
                    event.preventDefault()
                    event.stopPropagation()
                    hideMentionMenu()
                }
            }
        })

        // Leaving the surface ends either popup: one floating over the page while
        // the caret is somewhere else describes nothing. Each menu's own mousedown
        // is prevented (see buildMentionMenu / buildTicketMenu), so clicking a row
        // does not blur and this does not race the click.
        surface.addEventListener("blur", { hideMentionMenu(); hideTicketMenu() })

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
            val fileList = event.asDynamic().dataTransfer?.files
            val count = fileList?.length as? Int ?: 0
            if (count == 0) return@addEventListener
            event.preventDefault()
            // Snapshot the File handles NOW, synchronously, before the upload
            // coroutine below runs. `dataTransfer` is only live during this event,
            // but `launch` dispatches the upload to a later microtask, and by the
            // time it reads back a FileList the browser has emptied, files[index]
            // is `undefined` — the `as File` cast then throws into a coroutine
            // nobody awaits and the failure is swallowed. The Attach button never
            // hit this because <input>.files persists; copying the handles out
            // here makes the drop path just as stable. A File, once held, reads
            // fine asynchronously. (This is the second of two faults that made a
            // drop do nothing — the caretRangeFromPoint guard just below threw
            // first, on every drop, and hid this one behind it.)
            val files = (0 until count).map { fileList[it] as File }
            // Drop the caret where the file was dropped, so the attachment
            // lands under the pointer rather than wherever the caret last was.
            // caretRangeFromPoint is non-standard-but-everywhere; when a
            // browser lacks it, the current caret (or the end) is still a
            // sane landing place.
            //
            // Guarded by hand, NOT with `?.let`: the receiver is `dynamic`, and on
            // a dynamic value Kotlin compiles `.let` to a JS member call —
            // `caretRangeFromPoint.let(...)` — which no function has, so the whole
            // handler threw `TypeError: x.let is not a function` before the upload
            // could even begin. That fired on EVERY drop; it is the real reason a
            // drop did nothing. A plain null/undefined check reaches the same
            // intent without routing through a stdlib extension that dynamic
            // dispatch cannot see.
            val mouse = event.asDynamic()
            val caretFromPoint = document.asDynamic().caretRangeFromPoint
            savedRange = if (caretFromPoint != null && caretFromPoint != undefined) {
                document.asDynamic().caretRangeFromPoint(mouse.clientX, mouse.clientY)
            } else {
                currentRange()
            }
            // Sequentially, not in parallel: each insert lands at the caret the
            // previous one left behind, so several dropped files end up in
            // drop order rather than in whatever order their reads finished.
            scope.launch {
                for (file in files) {
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
        buildMentionMenu()
        buildTicketMenu()

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
            if (isSelectionInSurface()) {
                refreshToolbar()
                // Not only on input: clicking or arrowing into the middle of a
                // half-typed "@rob" should offer the completions for it, and
                // arrowing out of one should close the popup.
                refreshMentionMenu()
                refreshTicketMenu()
            }
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
        // The rebuild below destroys the text node either popup's offsets point at.
        hideMentionMenu()
        hideTicketMenu()
        // innerHTML with renderMarkdown's output, which is the one place in this
        // app that is allowed — everything it emits is a tag it built itself,
        // and every byte of user input inside it was escaped first. See
        // Markdown.kt's preamble.
        surface.innerHTML = renderMarkdown(value)
    }

    /**
     * Who the `@` autocomplete may offer.
     *
     * Names, not ids: a mention is the literal text `@` + a name, so a name is
     * the whole of what the popup has to insert. See
     * [se.soderbjorn.lunicle.clientserver.mentionedNames] for why.
     *
     * An empty list turns the feature off rather than showing an empty popup —
     * which is exactly what a signed-out reader and a project with no members
     * both get, and in neither case is there anything to say.
     */
    fun setMentionNames(names: List<String>) {
        mentionNames = names
        if (names.isEmpty()) hideMentionMenu()
    }

    /**
     * Turn on ticket autocomplete (LNL-139): typing a known project's `PREFIX-`
     * opens a popup of that project's issues, the same shape as the `@` popup.
     *
     * [prefixes] is read live — the reader's accessible projects — so it can grow
     * mid-session; [lookup] fetches one project's issues on demand, and the current
     * project's come straight from the board it is already holding, so only a
     * cross-project prefix ever costs a request. Not calling this leaves the feature
     * off, which is what the comment and forum editors get.
     */
    fun setTicketSource(prefixes: () -> List<String>, lookup: suspend (prefix: String) -> List<TicketOption>) {
        ticketPrefixes = prefixes
        ticketLookup = lookup
    }

    fun setEnabled(enabled: Boolean) {
        surface.contentEditable = enabled.toString()
        tools.forEach { (it.element as? org.w3c.dom.HTMLButtonElement)?.disabled = !enabled }
        if (!enabled) {
            hideMentionMenu()
            hideTicketMenu()
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

    // ── Mentions ─────────────────────────────────────────────────────────────

    /**
     * The popup, built once and parked out of the document until it is needed.
     *
     * It hangs off `<body>` with `position: fixed` rather than off [root], and
     * that is not a stylistic choice: the editor lives inside a scrolling host
     * (`.editor-host`) inside a modal, and an absolutely-positioned child of
     * either would be clipped by the first `overflow` on the way up — leaving the
     * popup half-drawn, or invisible, exactly when the caret is near the bottom
     * of the surface, which is most of the time. Fixed to the viewport is the one
     * position no ancestor can crop.
     */
    private fun buildMentionMenu() {
        mentionMenu = element("div", "mention-menu")
        mentionMenu.setAttribute("role", "listbox")
        // The same guard the toolbar buttons carry, and for the same reason: a
        // click that moves focus first would collapse the selection this popup
        // exists to edit, and there would be nothing left to replace.
        mentionMenu.addEventListener("mousedown", { event -> event.preventDefault() })
    }

    /**
     * The partly-typed mention under the caret, or null.
     *
     * Scoped to a single text node deliberately. A mention that spans an element
     * boundary — half of it bold — is not something the completion could replace
     * without rebuilding the DOM by hand, and it is not something anybody types;
     * treating it as "no mention" leaves the text alone, which is the harmless
     * answer.
     *
     * The query may contain spaces, and that is the point: display names are
     * "Ada Lovelace", not handles, so a completion that stopped at the first
     * space could never reach one. It stops instead when nothing matches any
     * more — see [refreshMentionMenu] — so a space that leads somewhere keeps
     * the popup open and a space that does not closes it.
     *
     * A leading `{` is skipped, so somebody who types the canonical `@{` by hand
     * — or who edits back into a completion this editor wrote — is completing a
     * name rather than a name that begins with a brace.
     */
    private fun mentionAtCaret(): MentionTarget? {
        val selection = window.asDynamic().getSelection() ?: return null
        if ((selection.rangeCount as? Int ?: 0) == 0) return null
        // A selection with a range in it is not a caret, and completing one would
        // silently delete whatever was selected.
        if (selection.isCollapsed as? Boolean != true) return null
        val node = selection.anchorNode ?: return null
        // 3 is Node.TEXT_NODE. Anything else — the surface itself, an empty
        // paragraph — has no text to have typed an "@" into.
        if (node.nodeType as? Int != 3) return null
        val domNode = node as? org.w3c.dom.Node ?: return null
        if (!surface.contains(domNode)) return null

        val text = domNode.textContent ?: return null
        val caret = selection.anchorOffset as? Int ?: return null
        if (caret > text.length) return null
        val at = text.lastIndexOf('@', caret - 1)
        if (at < 0) return null
        // The same word-boundary rule the server matches with: an "@" glued to
        // the end of a word is an e-mail address, not a mention.
        if (at > 0 && (text[at - 1].isLetterOrDigit() || text[at - 1] == '.' || text[at - 1] == '@')) return null
        val typed = text.substring(at + 1, caret)
        // A newline in the query means the caret has moved on past the mention.
        if (typed.contains('\n')) return null
        // "@{Ada Lo" is the canonical form half-written; the brace is syntax, not
        // part of the name being looked for. A closed one means the mention is
        // already finished and there is nothing left to complete.
        val query = if (typed.startsWith("{")) typed.drop(1) else typed
        if (query.contains('}') || query.contains('{')) return null
        return MentionTarget(node = node, at = at, caret = caret, query = query)
    }

    /**
     * Work out whether a popup belongs on screen right now, and draw it.
     *
     * The one entry point — called from `input` and from `selectionchange` — so
     * there is a single answer to "is a mention being typed", rather than one
     * per gesture that could disagree.
     */
    private fun refreshMentionMenu() {
        if (mentionNames.isEmpty() || surface.contentEditable != "true") {
            hideMentionMenu()
            return
        }
        val target = mentionAtCaret()
        if (target == null) {
            hideMentionMenu()
            return
        }
        val options = mentionCompletions(target.query, mentionNames)
        if (options.isEmpty()) {
            // Nothing matches what has been typed, so this is not a mention after
            // all — an e-mail address mid-sentence, or a name nobody here has.
            hideMentionMenu()
            return
        }
        // Keep the highlight on the same *name* across a keystroke where it
        // survives the filter; otherwise start at the top. Jumping back to the
        // first row on every character makes the arrow keys feel broken.
        val previous = mentionOptions.getOrNull(mentionIndex)
        mentionTarget = target
        mentionOptions = options
        mentionIndex = options.indexOf(previous).takeIf { it >= 0 } ?: 0
        drawMentionMenu()
    }

    /** Rebuild the rows and place the popup under the caret. */
    private fun drawMentionMenu() {
        mentionMenu.innerHTML = ""
        mentionOptions.forEachIndexed { index, name ->
            val row = element("div", "mention-menu-item", name)
            row.setAttribute("role", "option")
            row.setAttribute("aria-selected", (index == mentionIndex).toString())
            if (index == mentionIndex) row.classList.add("mention-menu-item-on")
            // Hovering moves the highlight, so the mouse and the arrow keys never
            // disagree about which row Enter would take. This only repaints the
            // `-on` class rather than rebuilding the list, which matters for the
            // line below: a full rebuild on hover would swap the row out from under
            // a press mid-click.
            row.addEventListener("mouseenter", { setMentionHighlight(index) })
            // Act on mousedown, not click — and this is the fix for "clicking a
            // name does nothing" (LNL-82). A click needs its mousedown and mouseup
            // on the *same* element, and the tiny pointer travel a trackpad press
            // carries used to fire `mouseenter` between them; when that rebuilt the
            // list (as it did), the element was replaced and the click never
            // landed. mousedown fires on the press itself, before any such travel,
            // and the menu's own mousedown-preventDefault keeps the caret from
            // blurring so [applyMention] still has a selection to replace.
            row.addEventListener("mousedown", { event ->
                event.preventDefault()
                applyMention(name)
            })
            mentionMenu.appendChild(row)
        }
        if (mentionMenu.parentNode == null) document.body?.appendChild(mentionMenu)
        positionMentionMenu()
    }

    /**
     * Put the popup just under the `@` that opened it.
     *
     * Measured from a range covering the `@` character itself rather than from
     * the collapsed caret: a collapsed range reports a zero-width — and in some
     * engines an all-zero — rectangle, which would park the popup in the corner
     * of the window. One real character always has a real box.
     *
     * Flipped above the line when there is not room below, so a mention typed on
     * the last visible line still shows its list.
     */
    private fun positionMentionMenu() {
        val target = mentionTarget ?: return
        val range = document.asDynamic().createRange()
        range.setStart(target.node, target.at)
        range.setEnd(target.node, target.at + 1)
        val rect = range.getBoundingClientRect()
        val top = rect.bottom as? Double ?: return
        val left = rect.left as? Double ?: return

        // Measured after it is in the document and has its rows, so the height is
        // the real one rather than a guess.
        val height = mentionMenu.offsetHeight.toDouble()
        val below = top + 4
        val fitsBelow = below + height <= window.innerHeight.toDouble()
        mentionMenu.style.left = "${left.coerceAtMost(window.innerWidth - 220.0).coerceAtLeast(4.0)}px"
        mentionMenu.style.top = if (fitsBelow) "${below}px" else "${(rect.top as Double) - height - 4}px"
    }

    /** Move the highlight, wrapping at both ends. */
    private fun moveMentionSelection(delta: Int) {
        if (mentionOptions.isEmpty()) return
        val size = mentionOptions.size
        setMentionHighlight(((mentionIndex + delta) % size + size) % size)
    }

    /**
     * Move the highlight to [next] without rebuilding the list.
     *
     * Repaints the `-on` class and `aria-selected` on the rows already in the
     * menu rather than tearing them down and recreating them. Both the arrow keys
     * and hover come through here, which is what lets a hovered row survive being
     * pressed: [drawMentionMenu] replaces every row, and doing that on `mouseenter`
     * swapped the element out between a press's mousedown and mouseup, so the click
     * was lost (LNL-82). The rows do not move, so there is nothing to reposition.
     */
    private fun setMentionHighlight(next: Int) {
        if (next == mentionIndex) return
        mentionIndex = next
        val rows = mentionMenu.children
        for (i in 0 until rows.length) {
            val row = rows.item(i) ?: continue
            val on = i == mentionIndex
            if (on) row.classList.add("mention-menu-item-on") else row.classList.remove("mention-menu-item-on")
            row.setAttribute("aria-selected", on.toString())
        }
    }

    /**
     * Replace the typed `@query` with the mention, and close the popup.
     *
     * The spelling is [mentionMarkdown]'s decision, not this function's: a name
     * with a space in it becomes `@{Ada Lovelace}` and one without stays
     * `@grace`. That is the whole of "auto-expand to the braced form when the
     * name needs it" — the user types `@ada lo`, picks a row, and the document
     * gains a mention that says unambiguously where the name ends.
     *
     * Through a selection and `insertText` rather than by writing to the text
     * node: `execCommand` puts the change on the browser's own undo stack, so
     * Ctrl-Z undoes the completion like any other edit, and it leaves the caret
     * after the inserted text where a manual `nodeValue` assignment would leave
     * it wherever the browser felt like.
     *
     * The trailing space is not decoration — it is what closes the mention. With
     * the caret sitting immediately after a *bare* name, [mentionAtCaret] would
     * find the same `@` again on the next selection change and reopen the popup
     * over a mention that is already complete.
     */
    private fun applyMention(name: String) {
        val target = mentionTarget ?: return
        hideMentionMenu()
        val range = document.asDynamic().createRange()
        range.setStart(target.node, target.at)
        range.setEnd(target.node, target.caret)
        val selection = window.asDynamic().getSelection() ?: return
        selection.removeAllRanges()
        selection.addRange(range)
        exec("insertText", mentionMarkdown(name) + " ")
    }

    /** Take the popup out of the document and forget what it was completing. */
    private fun hideMentionMenu() {
        mentionTarget = null
        mentionOptions = emptyList()
        mentionIndex = 0
        // Removed rather than hidden: a display:none element still answers
        // offsetHeight with 0, and positionMentionMenu measures it.
        if (this::mentionMenu.isInitialized) mentionMenu.remove()
    }

    // ── Ticket references ──────────────────────────────────────────────────────
    //
    // The `PREFIX-` autocomplete (LNL-139), the ticket twin of the `@` popup above.
    // It shares that popup's CSS and every one of its mechanics — position under the
    // token, arrow/Enter/Tab/Escape, hover-and-arrows-agree highlight, mousedown-not-
    // click selection — so the comments there carry for here too and are not repeated.
    // The two differences are what a target looks like (a known prefix and a hyphen,
    // not an `@`) and that the options are fetched rather than held in memory.

    /** The popup element, mirror of [buildMentionMenu]. */
    private fun buildTicketMenu() {
        ticketMenu = element("div", "mention-menu ticket-menu")
        ticketMenu.setAttribute("role", "listbox")
        ticketMenu.addEventListener("mousedown", { event -> event.preventDefault() })
    }

    /**
     * The `PREFIX-<digits>` under the caret, or null — the ticket twin of
     * [mentionAtCaret].
     *
     * The digits (which may be empty, the instant after `PREFIX-` is typed) are the
     * query. The prefix must be one the reader can reach and must start a word, the
     * same boundary rule [ticketSpans] applies, so `word-12` and `@LNL-1` do not open
     * a popup. When two accessible prefixes could end at the hyphen the longer wins.
     */
    private fun ticketAtCaret(): TicketTarget? {
        val prefixes = ticketPrefixes()
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .sortedByDescending { it.length }
        if (prefixes.isEmpty()) return null

        val selection = window.asDynamic().getSelection() ?: return null
        if ((selection.rangeCount as? Int ?: 0) == 0) return null
        if (selection.isCollapsed as? Boolean != true) return null
        val node = selection.anchorNode ?: return null
        if (node.nodeType as? Int != 3) return null
        val domNode = node as? org.w3c.dom.Node ?: return null
        if (!surface.contains(domNode)) return null

        val text = domNode.textContent ?: return null
        val caret = selection.anchorOffset as? Int ?: return null
        if (caret > text.length) return null

        // The digits are whatever runs right up to the caret; a hyphen must precede
        // them, and a prefix must precede that.
        var digitsStart = caret
        while (digitsStart > 0 && text[digitsStart - 1].isDigit()) digitsStart--
        val hyphen = digitsStart - 1
        if (hyphen < 0 || text[hyphen] != '-') return null

        for (prefix in prefixes) {
            val start = hyphen - prefix.length
            if (start < 0) continue
            if (!text.regionMatches(start, prefix, 0, prefix.length, ignoreCase = true)) continue
            val before = text.getOrNull(start - 1)
            if (before != null && (before.isLetterOrDigit() || before == '-' || before == '@')) continue
            return TicketTarget(
                node = node,
                start = start,
                caret = caret,
                prefix = prefix.uppercase(),
                query = text.substring(digitsStart, caret),
            )
        }
        return null
    }

    /**
     * Decide whether a ticket popup belongs on screen, and draw it — the twin of
     * [refreshMentionMenu], with one extra step: the first time a prefix is seen its
     * issues are not in memory, so it is fetched and this re-runs when they arrive.
     */
    private fun refreshTicketMenu() {
        if (ticketLookup == null || surface.contentEditable != "true") {
            hideTicketMenu()
            return
        }
        val target = ticketAtCaret()
        if (target == null) {
            hideTicketMenu()
            return
        }
        val cached = ticketCache[target.prefix]
        if (cached == null) {
            // Not loaded yet: fetch it, keep the popup closed until it lands. The
            // fetch re-runs this against the live caret, which then hits the cache.
            fetchTicketOptions(target.prefix)
            return
        }
        val options = filterTicketOptions(cached, target.query)
        if (options.isEmpty()) {
            hideTicketMenu()
            return
        }
        // Keep the highlight on the same ticket across a keystroke where it survives
        // the filter; otherwise start at the top — as the mention popup does.
        val previous = ticketOptions.getOrNull(ticketIndex)?.ticket
        ticketTarget = target
        ticketOptions = options
        ticketIndex = options.indexOfFirst { it.ticket == previous }.takeIf { it >= 0 } ?: 0
        drawTicketMenu()
    }

    /**
     * Fetch a project's issues into [ticketCache], once.
     *
     * Guarded by [ticketFetching] so a burst of keystrokes before the first result
     * lands launches a single request; when it returns, [refreshTicketMenu] runs
     * against wherever the caret is by then. A failure caches an empty list, which
     * simply means "no popup for this prefix" rather than a retry storm.
     */
    private fun fetchTicketOptions(prefix: String) {
        val lookup = ticketLookup ?: return
        if (!ticketFetching.add(prefix)) return
        scope.launch {
            val options = runCatching { lookup(prefix) }.getOrDefault(emptyList())
            ticketCache[prefix] = options
            ticketFetching.remove(prefix)
            if (surface.contentEditable == "true") refreshTicketMenu()
        }
    }

    /**
     * The issues whose number starts with the typed digits — all of them when none
     * are typed yet — highest number first, capped at [TICKET_OPTION_LIMIT].
     *
     * Highest first because the issues most often linked are the recent ones, and
     * capped because a list as tall as the window covers the text being written.
     */
    private fun filterTicketOptions(candidates: List<TicketOption>, query: String): List<TicketOption> {
        val matched = if (query.isEmpty()) candidates else candidates.filter { it.number.toString().startsWith(query) }
        return matched.sortedByDescending { it.number }.take(TICKET_OPTION_LIMIT)
    }

    /** Rebuild the rows and place the popup under the reference. Twin of [drawMentionMenu]. */
    private fun drawTicketMenu() {
        ticketMenu.innerHTML = ""
        ticketOptions.forEachIndexed { index, option ->
            val row = element("div", "mention-menu-item ticket-menu-item")
            row.setAttribute("role", "option")
            row.setAttribute("aria-selected", (index == ticketIndex).toString())
            if (index == ticketIndex) row.classList.add("mention-menu-item-on")
            row.children(
                element("span", "ticket-menu-key", option.ticket),
                element("span", "ticket-menu-title", option.title.ifBlank { "Untitled" }),
            )
            row.addEventListener("mouseenter", { setTicketHighlight(index) })
            row.addEventListener("mousedown", { event ->
                event.preventDefault()
                applyTicket(option)
            })
            ticketMenu.appendChild(row)
        }
        if (ticketMenu.parentNode == null) document.body?.appendChild(ticketMenu)
        positionTicketMenu()
    }

    /** Put the popup just under the reference. Twin of [positionMentionMenu]. */
    private fun positionTicketMenu() {
        val target = ticketTarget ?: return
        val range = document.asDynamic().createRange()
        range.setStart(target.node, target.start)
        range.setEnd(target.node, target.start + 1)
        val rect = range.getBoundingClientRect()
        val top = rect.bottom as? Double ?: return
        val left = rect.left as? Double ?: return

        val height = ticketMenu.offsetHeight.toDouble()
        val below = top + 4
        val fitsBelow = below + height <= window.innerHeight.toDouble()
        ticketMenu.style.left = "${left.coerceAtMost(window.innerWidth - 320.0).coerceAtLeast(4.0)}px"
        ticketMenu.style.top = if (fitsBelow) "${below}px" else "${(rect.top as Double) - height - 4}px"
    }

    /** Move the highlight, wrapping at both ends. Twin of [moveMentionSelection]. */
    private fun moveTicketSelection(delta: Int) {
        if (ticketOptions.isEmpty()) return
        val size = ticketOptions.size
        setTicketHighlight(((ticketIndex + delta) % size + size) % size)
    }

    /** Repaint the highlight without rebuilding the list. Twin of [setMentionHighlight]. */
    private fun setTicketHighlight(next: Int) {
        if (next == ticketIndex) return
        ticketIndex = next
        val rows = ticketMenu.children
        for (i in 0 until rows.length) {
            val row = rows.item(i) ?: continue
            val on = i == ticketIndex
            if (on) row.classList.add("mention-menu-item-on") else row.classList.remove("mention-menu-item-on")
            row.setAttribute("aria-selected", on.toString())
        }
    }

    /**
     * Replace the typed `PREFIX-<digits>` with the canonical reference, and close
     * the popup. Twin of [applyMention]; the trailing space closes the reference so
     * [ticketAtCaret] does not reopen the popup over a completed one.
     */
    private fun applyTicket(option: TicketOption) {
        val target = ticketTarget ?: return
        hideTicketMenu()
        val range = document.asDynamic().createRange()
        range.setStart(target.node, target.start)
        range.setEnd(target.node, target.caret)
        val selection = window.asDynamic().getSelection() ?: return
        selection.removeAllRanges()
        selection.addRange(range)
        exec("insertText", option.ticket + " ")
    }

    /** Take the popup out of the document and forget what it was completing. */
    private fun hideTicketMenu() {
        ticketTarget = null
        ticketOptions = emptyList()
        ticketIndex = 0
        if (this::ticketMenu.isInitialized) ticketMenu.remove()
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
        putCaretBackInto(surface, savedRange)
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
 * Put the caret back inside [surface], wherever it has to come from.
 *
 * ── The bug this exists to stop (LNL-160) ───────────────────────────────────
 *
 * Every insert this editor makes — an attachment, a link — goes through
 * `document.execCommand("insertHTML")`, and that command acts on **the current
 * selection**. Point the selection at something that is not editable and the
 * command does not fail loudly: it does nothing at all, and returns.
 *
 * Which is exactly what happened after the file picker. The Attach button saves
 * the selection on the way out and restores it on the way back, and the saved
 * selection is only *usually* in the surface. Open an issue, press Edit, press
 * Attach without having clicked in the description first, and what gets saved is
 * whatever the page's selection was — a paragraph in the issue behind, a stray
 * click in the sidebar, the leftovers of a select-all. Restoring that put the
 * caret outside the surface, `insertHTML` quietly declined, and the file the user
 * had just waited for was **uploaded, stored, and then invisible**: no link, no
 * error, and an orphaned blob on the volume. "It doesn't get added or seen
 * anywhere" is the whole of the reported symptom, and this is all of it.
 *
 * So a saved range is a *hint*, not an instruction. It is honoured when it points
 * into the surface, and dropped when it does not — and when it is dropped the
 * caret goes to the end of the surface, which is where an attachment appended to
 * a description belongs and where the reader's eye already is. The one thing that
 * must never happen again is an insert with nowhere to land.
 *
 * `contains` rather than comparing ancestors by hand, and asked of the range's
 * `commonAncestorContainer` rather than the selection's anchor: a range that
 * *spans* the surface and something else is not a caret in it. A text node
 * answers `contains` fine — it is `Node.contains`, not an element-only API.
 *
 * @param savedRange the range to prefer, or null when nothing was saved. Typed
 *   `dynamic` for the same reason the field is: a Range's members are reached
 *   through JS, and Kotlin's DOM bindings do not carry the ones used here.
 * @return whether the caret ended up inside the surface at all. Only false when
 *   the browser has no selection API to speak of, which no engine this runs on
 *   is; callers that ignore it are not ignoring a failure they could handle.
 */
internal fun putCaretBackInto(surface: HTMLElement, savedRange: dynamic): Boolean {
    surface.focus()
    val selection = window.asDynamic().getSelection() ?: return false
    val container = savedRange?.commonAncestorContainer as? org.w3c.dom.Node
    val range = if (container != null && surface.contains(container)) {
        savedRange
    } else {
        // Collapsed at the very end of the surface. `selectNodeContents` then
        // `collapse(false)` rather than arithmetic on the last child: the last
        // child may be an element, a text node or nothing at all, and this is the
        // one spelling that gives a caret position in all three.
        //
        // Built statement by statement, NOT with `also`: on a `dynamic` receiver
        // Kotlin compiles a scope function to a member call — `range.also(…)` —
        // and no Range has one, so it would throw at runtime where it reads as
        // ordinary Kotlin. The same trap `.let` sets three hundred lines up.
        val fresh = document.createRange().asDynamic()
        fresh.selectNodeContents(surface)
        fresh.collapse(false)
        fresh
    }
    selection.removeAllRanges()
    selection.addRange(range)
    return true
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
