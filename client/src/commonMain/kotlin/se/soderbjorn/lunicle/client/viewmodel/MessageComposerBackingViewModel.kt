/**
 * Backing view-model for the message composer — a new conversation, or a reply to
 * one.
 *
 * ── One class for both, and why that is not over-generalising ───────────────
 *
 * Exactly the argument [ForumComposerBackingViewModel] makes: the two differ by a
 * recipient picker and share everything with any subtlety in it — create a draft
 * row so an upload has an owner, keep the editor live through an upload, publish,
 * and undo the draft on cancel. Two classes would be two copies of that dance, and
 * the second copy is the one that gets the [State.isUploading] distinction wrong.
 *
 * ── The one genuinely new thing: the draft is created late ──────────────────
 *
 * Every other composer in this app creates its draft row on open. This one cannot,
 * for a **new conversation**: a message needs a conversation, a conversation needs
 * its participants, and the participants are what the person is still choosing.
 * Creating it on open would mean asking who the message is for before opening the
 * composer — a two-step wizard for the commonest thing the feature does.
 *
 * So the row is created at the first moment something actually needs one:
 * attaching a file, or pressing Send. Until then the recipient list is editable
 * and the editor works, because text needs no row.
 *
 * **The moment it is created, the recipients are frozen** — LNL-30 settles that
 * membership is fixed at creation and there is no route to add anybody afterwards.
 * That is [State.areRecipientsLocked], and the view says so out loud rather than
 * greying a control for reasons the user cannot see. It is worth being explicit
 * that this is the schema's rule surfacing rather than a UI convenience: the
 * alternative implementations were a wizard (worse), or creating the conversation
 * on open with nobody in it (not expressible — see the server's
 * `ConversationRepository.startConversation`).
 *
 * A **reply** has none of this. Its conversation exists, so [start] creates the
 * draft immediately and this behaves exactly like the forum's composer — when the
 * host calls [start] at all. LNL-64's **inline** composer deliberately does not:
 * that bar lives for as long as the thread window does rather than for as long as a
 * modal is up, so a draft created on open would mint an empty message row every
 * time somebody merely *read* a conversation. It gets one from [ensureDraft] at the
 * same moment a new conversation does — the first attach, or Send.
 *
 * @see se.soderbjorn.lunicle.clientserver.ConversationDetail
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.client.attachmentMarkdown
import se.soderbjorn.lunicle.client.userMessage
import se.soderbjorn.lunicle.clientserver.ConversationDetail
import se.soderbjorn.lunicle.clientserver.UserOption
import se.soderbjorn.lunicle.clientserver.tooLargeMessage

/**
 * Owns the composer.
 *
 * @param target a new conversation, or a reply to one that exists.
 * @param recipients everybody the caller may write to, for the picker's
 *   autocomplete. Handed in from [MessagesBackingViewModel] rather than fetched,
 *   so the popup works the moment the modal appears. Empty and unread for a reply.
 * @param mentionableNames who the `@` autocomplete may offer inside the body.
 *   Handed in for the same reason. For a reply it came off the conversation; for a
 *   **new** conversation it is empty until there is one — see [State.hasBody] and
 *   the note on [mentionableNames].
 * @param onFinished called when the composer is done. Carries the conversation it
 *   ended up writing into, and the refreshed detail when the server sent one, so
 *   the reader behind the modal updates without a second round-trip.
 */
class MessageComposerBackingViewModel(
    private val target: Target,
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    val recipients: List<UserOption> = emptyList(),
    val mentionableNames: List<String> = emptyList(),
    private val onFinished: (conversationId: Long?, detail: ConversationDetail?) -> Unit,
) {
    /** What is being written. */
    sealed interface Target {
        /** A new conversation, with recipients still to be chosen. */
        data object NewConversation : Target

        /** A reply in [conversationId]. */
        data class Reply(val conversationId: Long) : Target
    }

    private val _stateFlow = MutableStateFlow(
        State(isNewConversation = target is Target.NewConversation),
    )

    /** The current composer state, observed by the view. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /** The conversation this is writing into. Null until one exists. */
    private var conversationId: Long? = (target as? Target.Reply)?.conversationId

    /** The draft message row. Null until one exists. */
    private var draftId: Long? = null

    /** Whether a file was uploaded, so Cancel knows something happened. */
    private var hasUploaded = false

    /**
     * Immutable snapshot of the composer.
     *
     * @property chosen who the message will go to, in the order they were picked.
     *   Empty and unused for a reply.
     * @property query what has been typed into the recipient search box.
     * @property areRecipientsLocked whether the conversation now exists, and with
     *   it the membership. See this class's preamble — this is the schema's "you
     *   cannot add somebody to an existing conversation" arriving in the UI, not a
     *   convenience.
     * @property isUploading an upload is in flight.
     *
     *   Deliberately NOT [isBusy], for the reason `CommentBackingViewModel`
     *   documents at length: the view disables the editor while the composer is
     *   busy, and an upload ends by inserting its markdown *into that editor*, so
     *   folding the two together disables the very surface the insert is aimed at
     *   and the attachment vanishes with no error anywhere. That was the whole of
     *   "attaching a picture to a comment does not work"; this is the third
     *   composer and the trap is the same one.
     */
    data class State(
        val body: String = "",
        val isNewConversation: Boolean = true,
        val chosen: List<UserOption> = emptyList(),
        val query: String = "",
        val areRecipientsLocked: Boolean = false,
        val isBusy: Boolean = false,
        val isUploading: Boolean = false,
        val errorMessage: String? = null,
    ) {
        val heading: String get() = if (isNewConversation) "New message" else "Reply"

        val validationMessage: String? get() = when {
            isNewConversation && chosen.isEmpty() -> "Choose who this is going to."
            body.isBlank() -> "There is nothing in that yet."
            else -> null
        }

        /** Sending mid-upload would save a body the attachment has not landed in yet. */
        val isSendEnabled: Boolean get() = !isBusy && !isUploading && validationMessage == null

        /** The editor stays live through an upload — see [isUploading]. */
        val isEditorEnabled: Boolean get() = !isBusy

        /**
         * Whether the attach button does anything.
         *
         * Needs somebody to send to, for a reason the other composers do not have:
         * attaching is what *creates* the conversation, and a conversation cannot
         * be created with nobody in it. So the button is dead until a recipient is
         * chosen, rather than failing at the moment it is pressed.
         */
        val isAttachEnabled: Boolean get() =
            !isBusy && !isUploading && (!isNewConversation || chosen.isNotEmpty())

        /**
         * The note under the recipient row explaining why it stopped being
         * editable, or null while it still is.
         *
         * A sentence rather than a disabled control on its own: a picker that
         * silently stops accepting names reads as broken, and the reason — that
         * attaching the file started the conversation — is not something anybody
         * would guess.
         */
        val lockNote: String? get() = if (areRecipientsLocked && isNewConversation) {
            "This conversation has started, so who is in it is now fixed. " +
                "To write to somebody else, send a new message."
        } else {
            null
        }
    }

    /**
     * Prepare the composer.
     *
     * For a reply this creates the draft row the image button needs an owner for,
     * exactly like every other composer. For a **new conversation** it does
     * nothing at all — there is nothing to create until somebody has been chosen —
     * which is why, unlike the forum's composer, there is no `isLoaded` gate on
     * this one's Send button. The modal is usable the moment it appears.
     */
    fun start() {
        val existing = (target as? Target.Reply)?.conversationId ?: return
        _stateFlow.value = _stateFlow.value.copy(isBusy = true)
        scope.launch {
            runCatching { storage.createMessageDraft(existing) }.fold(
                onSuccess = { id ->
                    draftId = id
                    _stateFlow.value = _stateFlow.value.copy(isBusy = false)
                },
                onFailure = { failure ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = failure.userMessage("Could not start that."),
                    )
                },
            )
        }
    }

    fun onBodyChanged(value: String) {
        _stateFlow.value = _stateFlow.value.copy(body = value, errorMessage = null)
    }

    fun onQueryChanged(value: String) {
        _stateFlow.value = _stateFlow.value.copy(query = value, errorMessage = null)
    }

    /**
     * The names still worth offering for what has been typed.
     *
     * ── Why this is not `mentionCompletions` ────────────────────────────────
     *
     * It is the same *rule* — a case-insensitive prefix match, so somebody typing
     * "ad" is spelling a name from its start and "Vladimir" is not offered for
     * containing "ad" — and deliberately not a call to that function. The mention
     * matcher works over names, because a mention *is* a name in the text; a
     * recipient picker has to produce an **id**, because two accounts may share a
     * display name and a message has to go to one of them in particular. Mapping
     * names back to options would reintroduce exactly the ambiguity
     * `mentionedNames` documents itself as unable to resolve, in the one place
     * where it can be.
     *
     * Anyone already chosen drops out, so the list is what pressing it would add.
     * An empty query offers everybody, which is the "press the box and see who
     * there is" behaviour rather than a special case above this function.
     */
    fun completions(): List<UserOption> {
        val state = _stateFlow.value
        if (state.areRecipientsLocked) return emptyList()
        val taken = state.chosen.map { it.id }.toSet()
        return recipients.filter { it.id !in taken && it.name.startsWith(state.query, ignoreCase = true) }
    }

    /** The reader picked somebody from the popup. */
    fun onRecipientChosen(option: UserOption) {
        val state = _stateFlow.value
        if (state.areRecipientsLocked || state.chosen.any { it.id == option.id }) return
        _stateFlow.value = state.copy(chosen = state.chosen + option, query = "", errorMessage = null)
    }

    /** The reader took somebody back off the list. Refused once the conversation exists. */
    fun onRecipientRemoved(id: Long) {
        val state = _stateFlow.value
        if (state.areRecipientsLocked) return
        _stateFlow.value = state.copy(chosen = state.chosen.filterNot { it.id == id })
    }

    fun onSendTapped() {
        val current = _stateFlow.value
        if (!current.isSendEnabled) return
        _stateFlow.value = current.copy(isBusy = true, errorMessage = null)
        scope.launch {
            runCatching {
                val ids = ensureDraft() ?: error("The conversation was not created.")
                storage.publishMessage(ids.first, ids.second, current.body)
            }.fold(
                onSuccess = { detail -> onFinished(detail.id, detail) },
                onFailure = { failure ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = failure.userMessage("Could not send that."),
                    )
                },
            )
        }
    }

    /**
     * Cancel.
     *
     * Undoes whatever was created, which is not the same statement in the two
     * cases and is why this is not one call:
     *
     *  - A **reply** deletes its draft message, exactly as the forum's composer
     *    does. The conversation is somebody else's as much as it is yours.
     *  - A **new conversation** discards the conversation, which cascades the
     *    draft and its files with it. Deleting only the message would leave an
     *    empty conversation that nobody can see and nobody can remove, and — worse
     *    — one whose membership is already frozen, so the same two people writing
     *    to each other would get a second thread.
     *
     * A failure either way is swallowed, for `CommentBackingViewModel`'s reason:
     * nothing was published, so it is invisible regardless, and the server's
     * startup sweep takes the files. Blocking somebody inside a modal they have
     * already dismissed, over rows nobody can see, is worse than the leak.
     */
    fun onCancelTapped() {
        val current = _stateFlow.value
        if (current.isBusy) return
        val conversation = conversationId
        val draft = draftId
        if (draft == null || conversation == null) {
            onFinished(null, null)
            return
        }
        _stateFlow.value = current.copy(isBusy = true)
        scope.launch {
            runCatching {
                when (target) {
                    Target.NewConversation -> storage.discardConversation(conversation)
                    is Target.Reply -> storage.deleteMessage(conversation, draft)
                }
            }.onFailure { println("Messages: discarding draft $draft failed: ${it.message}") }
            onFinished(null, null)
        }
    }

    /**
     * Upload a file of any kind; returns the markdown to insert, or null on
     * failure. The view owns the file picker. See
     * [IssueBackingViewModel.uploadAttachment].
     *
     * This is the call that creates a new conversation, if pressing Send has not
     * already — an attachment needs a row to belong to, and this is the first
     * moment one is genuinely required. See this class's preamble.
     */
    suspend fun uploadAttachment(filename: String, mimeType: String, bytes: ByteArray): String? {
        tooLargeMessage(bytes.size.toLong())?.let { message ->
            _stateFlow.value = _stateFlow.value.copy(isUploading = false, errorMessage = message)
            return null
        }
        _stateFlow.value = _stateFlow.value.copy(isUploading = true, errorMessage = null)
        return runCatching {
            val ids = ensureDraft() ?: error("The conversation was not created.")
            storage.uploadMessageAttachment(ids.second, filename, mimeType, bytes)
        }.fold(
            onSuccess = { attachmentId ->
                hasUploaded = true
                _stateFlow.value = _stateFlow.value.copy(isUploading = false)
                attachmentMarkdown(filename, mimeType, bytes.size.toLong(), attachmentId)
            },
            onFailure = { failure ->
                _stateFlow.value = _stateFlow.value.copy(
                    isUploading = false,
                    errorMessage = failure.userMessage("Could not upload that file."),
                )
                null
            },
        )
    }

    /**
     * The conversation and draft message to write into, creating them if this is a
     * new conversation that has not started yet.
     *
     * Idempotent, and it has to be: Send and the attach button both call it, and a
     * second call must not create a second conversation. Once it has run, the
     * recipients are locked — the state change is here rather than at either call
     * site so there is one place where "the conversation now exists" and "the
     * membership is fixed" become true together.
     *
     * @return the conversation id and the draft message id, or null if there is
     *   nothing to write into — which for a new conversation means nobody was
     *   chosen, and is refused by [State.validationMessage] before this is reached.
     */
    private suspend fun ensureDraft(): Pair<Long, Long>? {
        val conversation = conversationId
        val draft = draftId
        if (conversation != null && draft != null) return conversation to draft

        // A reply whose host never called [start] — which is the inline composer in
        // `ConversationView`. It cannot call it: that bar lives for as long as the
        // thread window does, so creating the draft on open would mint an empty
        // message row every time somebody *read* a conversation. Making the row here
        // instead means the two composers differ only in when the same call happens.
        if (conversation != null) {
            val id = storage.createMessageDraft(conversation)
            draftId = id
            return conversation to id
        }

        val chosen = _stateFlow.value.chosen.map { it.id }
        if (chosen.isEmpty()) return null
        val started = storage.startConversation(chosen)
        conversationId = started.conversationId
        draftId = started.messageId
        _stateFlow.value = _stateFlow.value.copy(areRecipientsLocked = true)
        return started.conversationId to started.messageId
    }
}
