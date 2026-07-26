/**
 * Backing view-model for **one** private conversation: what was said, and the two
 * things a reader may do to it.
 *
 * ── Why one conversation gets a view model of its own ───────────────────────
 *
 * [MessagesBackingViewModel] knows which conversation is open and deliberately
 * nothing about what is in it. That split is what makes this constructible from a
 * single id — which is what a deep link from an e-mail actually has, landing on a
 * cold page with no list fetched behind it. `ForumPostBackingViewModel` draws the
 * same line for the same reason.
 *
 * ── What it deliberately does not own ───────────────────────────────────────
 *
 * **Writing.** The composer is [MessageComposerBackingViewModel]'s, which owns
 * the draft row and the uploads. This one is the reading surface plus deletion,
 * and deletion is here rather than there because it is the one write with no
 * composer — a button on something already on screen.
 *
 * **Read/unread.** LNL-64. Nothing here marks anything as read, and nothing on
 * [State] says whether anything is.
 *
 * @see MessagesBackingViewModel
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
import se.soderbjorn.lunicle.client.formatTimestamp
import se.soderbjorn.lunicle.client.userMessage
import se.soderbjorn.lunicle.clientserver.ConversationDetail
import se.soderbjorn.lunicle.clientserver.MessageView

/**
 * Owns one conversation.
 *
 * @param conversationId the one id that names it. One, unlike a forum post's
 *   three, because a conversation is not inside anything — see the server's
 *   Conversations.sq.
 * @param storage the client's repository; the only collaborator.
 * @param onFinished called when the reader is done with it. `true` if anything was
 *   written while it was open, so the host knows the list — which carries a
 *   preview, a timestamp and an order — is stale.
 */
class ConversationBackingViewModel(
    val conversationId: Long,
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onFinished: (changed: Boolean) -> Unit = {},
) {
    private val _stateFlow = MutableStateFlow(State())

    /** The current conversation state, observed by the view. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * Whether anything was written while this was open.
     *
     * Kept outside [State] for `ForumPostBackingViewModel`'s reason: it is not
     * something the view renders, only a fact about the session that matters at
     * the moment this closes. Putting it on the state would invite a render to
     * depend on it.
     */
    private var changed = false

    /**
     * Immutable snapshot of one conversation.
     *
     * @property detail the conversation as the server last described it, or null
     *   before the first answer. Null rather than an empty [ConversationDetail],
     *   so "loading" and "a thread with nothing in it" cannot be confused — and
     *   the second is genuinely reachable, since everybody may delete every
     *   message they wrote.
     * @property confirmingDeleteMessageId which message's confirmation is up, or
     *   null. Confirmed rather than immediate because a message cannot be edited
     *   or restored: deleting is the only thing that can happen to one, and it is
     *   final.
     */
    data class State(
        val detail: ConversationDetail? = null,
        val isBusy: Boolean = false,
        val errorMessage: String? = null,
        val confirmingDeleteMessageId: Long? = null,
    ) {
        /** Whether the first fetch has returned. See [detail]. */
        val isLoaded: Boolean get() = detail != null

        /** Who may be `@`-mentioned here, as bare names for the editor. */
        val mentionableNames: List<String> get() = detail?.mentionableUsers?.map { it.name }.orEmpty()

        /**
         * What the thread is called: the other people in it.
         *
         * The same rule [MessagesBackingViewModel.State.heading] applies to a list
         * row, and deliberately not shared with it: this is a heading with a whole
         * pane's width and that is a row with three columns, so "how many names
         * before a count" is a different number in the two places. Sharing it would
         * couple a title bar to a list's layout.
         */
        val heading: String get() {
            val names = detail?.participantNames.orEmpty()
            return when {
                names.isEmpty() -> "Nobody else"
                names.size <= 5 -> names.joinToString(", ")
                else -> names.take(5).joinToString(", ") + " and ${names.size - 5} others"
            }
        }

        /**
         * One message's attribution line: "Robert · 17 Jul 2026, 14:32".
         *
         * Spelled here rather than on the wire type, for `IssueBackingViewModel`'s
         * reason: the server has no opinion about how a date is written or what a
         * deleted account is called.
         */
        fun byline(message: MessageView): String =
            "${message.authorName ?: "A deleted account"} · ${formatTimestamp(message.createdAt)}"

        /** One message's agent badge, or null when a human wrote it. */
        fun agentBadge(message: MessageView): String? = message.agentName?.let { "Agent · $it" }

        /** The initial in a message's avatar circle. See `ForumPostBackingViewModel`. */
        fun initial(message: MessageView): String =
            message.authorName?.trim()?.firstOrNull()?.uppercase() ?: "?"
    }

    /** Fetch the conversation. Called by the view on mount. */
    fun start() = refresh()

    fun refresh() {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            runCatching { storage.conversation(conversationId) }.fold(
                onSuccess = { detail ->
                    _stateFlow.value = _stateFlow.value.copy(detail = detail, isBusy = false)
                },
                onFailure = { failure ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        // Reached by a deep link to a conversation the reader is not
                        // in, which answers 404 — the same answer a conversation that
                        // does not exist gives, deliberately. So the sentence has to
                        // cover both without guessing which; see the server's
                        // `conversationScope`.
                        errorMessage = failure.userMessage("Could not open that conversation."),
                    )
                },
            )
        }
    }

    /**
     * Take a detail this view model did not fetch.
     *
     * Publishing and deleting both answer with the whole conversation, and handing
     * that straight over is what makes a sent message appear without a second
     * round-trip for something the server has already sent.
     */
    fun onDetailReceived(detail: ConversationDetail) {
        changed = true
        _stateFlow.value = _stateFlow.value.copy(detail = detail, isBusy = false, errorMessage = null)
    }

    /** The reader pressed back, or closed the window. */
    fun onCloseTapped() = onFinished(changed)

    fun onDeleteMessageTapped(messageId: Long) {
        _stateFlow.value = _stateFlow.value.copy(confirmingDeleteMessageId = messageId)
    }

    fun onDeleteMessageCancelled() {
        _stateFlow.value = _stateFlow.value.copy(confirmingDeleteMessageId = null)
    }

    fun onDeleteMessageConfirmed() {
        val messageId = _stateFlow.value.confirmingDeleteMessageId ?: return
        _stateFlow.value = _stateFlow.value.copy(confirmingDeleteMessageId = null, isBusy = true)
        scope.launch {
            runCatching { storage.deleteMessage(conversationId, messageId) }.fold(
                // The whole conversation comes back, so there is nothing to patch
                // locally and nothing to be wrong about.
                onSuccess = { onDetailReceived(it) },
                onFailure = { failure ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = failure.userMessage("Could not delete that message."),
                    )
                },
            )
        }
    }

    /** Dismiss the failure line. */
    fun onErrorDismissed() {
        _stateFlow.value = _stateFlow.value.copy(errorMessage = null)
    }
}
