/**
 * Writing a new forum post: what it is called, and what it says.
 *
 * ── Why this is a pane rather than a modal ──────────────────────────────────
 *
 * LNL-61 wrote this as `ForumComposerDialog`, one modal serving both a new post and
 * a comment. That file is gone. The WYSIWYG editor belongs in a **pane** — that is
 * where the issue editor has always lived, in the issue window, and it works — so
 * the comment composer went inline at the foot of the thread (see [ForumPostView])
 * and the new post took the post window's own pane, with a title field above the
 * body where the thread would be.
 *
 * That is [NewConversationView] one tab over, deliberately: LNL-64 had already done
 * exactly this to the Messages composers a commit earlier, and the two surfaces are
 * the same shape, so matching it is worth more than any choice made fresh here. Both
 * share their tab's window pane rather than claiming one of their own, and for the
 * same reason — the tab has a list and a thing you are doing with it, and "writing a
 * post" and "reading one" are two answers to the same question. Which is why they
 * share `FORUM_POST_PANE_ID`, and why writing one *becomes* reading it the moment it
 * is published, in the same window, with nothing opening or closing.
 *
 * Nothing here manages its own height. The pane is draggable, resizable and
 * maximisable, so how much of it the editor takes is the writer's to decide with the
 * affordances the toolkit already gives them — and if it is cramped, the answer is a
 * taller window or one fewer pane, which is the deal the issue editor has had all
 * along. There is no `max-height`, no inner cap and no arithmetic anywhere below or
 * in the stylesheet. See [ConversationView], which argues it at greater length.
 *
 * ── What is taken from the design, and what is not ──────────────────────────
 *
 * Taken: the composer's *contents* — the "Post title" label with "What's your post
 * about?" under the cursor, the editor with its toolbar, the "Type @ to mention
 * another member." line under it, and **Post** as the submit. Not taken: the modal
 * container those things sat in, which is the one thing the owner overruled.
 *
 * There is no Cancel button either, and its absence is the same decision. A modal
 * needs one because a modal has no other way out; a pane is closed by its chrome
 * like every other window in this app, and closing it *is* the cancel — see
 * `ForumWindows.onCloseClicked`. Escape is likewise not handled here.
 *
 * @see ForumComposerBackingViewModel
 * @see NewConversationView
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import se.soderbjorn.lunicle.client.viewmodel.ForumComposerBackingViewModel

/**
 * The line under every forum composer's editor, as the design writes it.
 *
 * Built here and used by both composers rather than typed out twice: it is one
 * sentence about one feature, and two copies is how the new-post pane and the
 * comment bar would eventually end up describing the same `@` popup differently.
 *
 * Three spans rather than one string because the design puts the `@` in the accent
 * colour — it is the character you actually type, and a sentence about a symbol
 * reads better when the symbol is shown rather than named.
 */
internal fun mentionHint(): HTMLElement {
    val hint = element("p", "composer-hint")
    hint.children(
        element("span", "", "Type "),
        element("span", "composer-hint-key", "@"),
        element("span", "", " to mention another member."),
    )
    return hint
}

/**
 * Renders the new-post pane.
 *
 * @param viewModel the composer's state and intents. Built by the window, so this
 *   view — like [ForumPostView] — knows nothing about where it is mounted.
 * @param scope this view's lifetime, cancelled when the window closes.
 */
class NewForumPostView(
    private val viewModel: ForumComposerBackingViewModel,
    private val scope: CoroutineScope,
    /** The `PREFIX-` autocomplete source for the post editor (LNL-139). */
    private val ticketSource: TicketSource,
) {
    /** The view's root, appended by whatever is hosting it. */
    val root: HTMLElement = element("div", "forum-post new-forum-post")

    private lateinit var editor: MarkdownEditor

    private val titleField: HTMLInputElement =
        textField("What's your post about?") { viewModel.onTitleChanged(it) }
    // `editor-host` as well as the composer's own class, and that pairing is the
    // whole of "the editor fills the pane": it is the class the issue window puts
    // round its editor, and it carries the four rules — host grows, editor grows
    // inside it, toolbar fixed, surface scrolls — that make a WYSIWYG editor take
    // the slack it is given. Written out again under `.new-forum-post` in the
    // stylesheet before LNL-81, minus the one rule that made the *editor* grow,
    // which is why the writing surface came out the width of its own toolbar.
    private val editorHost = element("div", "editor-host forum-post-composer-editor")
    private val validationElement = element("p", "field-validation")
    private val errorElement = element("p", "modal-error")
    private val postButton: HTMLButtonElement = button("Post", "btn btn-primary") {
        viewModel.onOkTapped()
    }

    /** Build the tree and start following the state. */
    fun start() {
        errorElement.setAttribute("role", "status")

        val header = element("div", "forum-post-header")
        header.appendChild(element("h3", "forum-post-title", "New post"))

        // Appended, never assigned: `textField` hands back an input already
        // wearing `.field`, which is what gives it its width, surface and
        // padding. Overwriting the class dropped all of that, and the input fell
        // back to the browser's default ~147px — which also clipped the
        // placeholder mid-question-mark, so it read as a stray glyph rather than
        // as a field that was too small.
        titleField.className = "field forum-post-title-field"
        // No "Post title" label above it. The design has one, and it is the one
        // place the design labels a field whose own placeholder already asks the
        // question — "What's your post about?" sits in the box, under the cursor,
        // and says more than the label does. Two captions for one field is the
        // label describing the placeholder rather than the field (LNL-81). The
        // row survives as the layout slot; it just holds the one thing in it.
        val titleRow = element("div", "field-row")
        titleRow.appendChild(titleField)

        editor = MarkdownEditor(
            scope = scope,
            onChange = { viewModel.onBodyChanged(it) },
            onUpload = { name, mime, bytes -> viewModel.uploadAttachment(name, mime, bytes) },
            // The editor's only issue-shaped default. A forum post is not an issue
            // and should not be asked to describe one; see MarkdownEditor, where the
            // placeholder became a parameter precisely to remove that assumption.
            placeholder = "Write your post…",
        )
        editor.mount(editorHost)
        // Fixed for this pane's life, and already here when it appears: the names
        // come off the forum's post list, which was fetched before "New post" could
        // be pressed. See ForumComposerBackingViewModel.mentionableNames.
        editor.setMentionNames(viewModel.mentionableNames)
        // Typing a known project's "PREFIX-" offers that project's issues (LNL-139).
        editor.setTicketSource(prefixes = ticketSource.prefixes, lookup = ticketSource.lookup)

        val actions = element("div", "forum-post-composer-actions")
        actions.appendChild(postButton)

        val composer = element("div", "forum-post-composer")
        composer.children(editorHost, mentionHint(), validationElement, errorElement, actions)

        root.children(header, titleRow, composer)

        scope.launch { viewModel.stateFlow.collect(::render) }
        // Deliberately nothing else: since the composers became panes, the draft row
        // is minted at the first attach or at Post rather than on open — a window
        // somebody may leave open should not write an empty post into the forum. See
        // ForumComposerBackingViewModel.
    }

    private fun render(state: ForumComposerBackingViewModel.State) {
        titleField.setValueIfChanged(state.title)
        titleField.disabled = !state.isEditorEnabled

        // Not `!isBusy` alone: an upload must leave the editor live, because that is
        // where the upload's markdown is about to be inserted. See
        // ForumComposerBackingViewModel.State.isUploading.
        editor.setEnabled(state.isEditorEnabled)

        validationElement.setTextIfChanged(state.visibleValidationMessage.orEmpty())
        validationElement.visible(state.visibleValidationMessage != null)
        errorElement.setTextIfChanged(state.errorMessage.orEmpty())
        errorElement.visible(state.errorMessage != null)

        postButton.textContent = state.submitLabel
        postButton.disabled = !state.isOkEnabled
    }
}
