/**
 * The Messages tab: which conversations you are in, and which one you are
 * reading.
 *
 * The project convention, as everywhere in this package: all the logic, one
 * immutable [State] over a single [StateFlow], and no platform in sight.
 *
 * ── Why this one has no project, when every other view model here does ──────
 *
 * [ForumBackingViewModel] takes its project from [MainScreenBackingViewModel],
 * because a forum is in a project and the two must not disagree about which.
 * Conversations are **instance-wide** (LNL-30), so there is nothing to be told:
 * this view model is complete on its own and starts fetching as soon as the
 * session is known. That is why `main.kt` wires it to the session rather than to
 * the board — switching project must not reload this tab, and would look like a
 * bug if it did.
 *
 * ── The split with ConversationBackingViewModel ─────────────────────────────
 *
 * This one knows *which* conversation is open and deliberately nothing about what
 * is in it, exactly as `ForumBackingViewModel` relates to
 * `ForumPostBackingViewModel`. That is what makes the reading surface something
 * that can be built from an id and nothing else — which is what a deep link
 * arriving on a cold load actually has.
 *
 * @see se.soderbjorn.lunicle.clientserver.ConversationListState
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
import se.soderbjorn.lunicle.clientserver.ConversationSummary
import se.soderbjorn.lunicle.clientserver.UserOption

/**
 * Owns the conversation list.
 *
 * @param storage the client's repository; the only collaborator, so this view
 *   model never mentions HTTP.
 * @param scope coroutine scope the requests run in.
 */
class MessagesBackingViewModel(
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _stateFlow = MutableStateFlow(State())

    /** The current state of the Messages tab, observed by the pane. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * The message a deep link asked to land on, held until the conversation it is
     * in has been opened.
     *
     * Kept outside [State] because it is not something the view renders — it is a
     * one-shot instruction that is spent the first time the open conversation
     * reports it has loaded. Putting it on the state would invite a render to
     * depend on it, and would make "have we scrolled there yet" a thing to track.
     */
    private var pendingMessageId: Long? = null

    /**
     * The conversation a deep link asked to open, held until the session resolves.
     *
     * `main.kt` reads `?conversation=` at load and hands it over immediately, but
     * the session is still being fetched at that moment — and [onSessionChanged]
     * throws the state away when it arrives, which would take the deep link with
     * it. So it is remembered here and re-applied exactly once, on the first
     * resolution. A *later* session change (signing out, an admin starting
     * impersonation) drops it, which is correct: the link was addressed to whoever
     * clicked it, not to whoever is signed in three changes later.
     */
    private var pendingConversationId: Long? = null

    /**
     * Whether the session has resolved at least once.
     *
     * Not the same as "somebody is signed in": a signed-out visitor is a resolved
     * session. This is what tells the first [onSessionChanged] — the one that
     * completes the boot — from every later one.
     */
    private var hasSession = false

    /** Whose conversations are loaded, so an unchanged session does not re-fetch. */
    private var identity: Long? = null

    /**
     * Immutable snapshot of the Messages tab.
     *
     * @property conversations most recently spoken in first — the server's order,
     *   never re-sorted here.
     * @property recipients everybody this caller may start a conversation with,
     *   for the composer's autocomplete. Held here rather than fetched when the
     *   composer opens, for `ForumBackingViewModel`'s reason: a modal that puts an
     *   autocomplete up and then makes it work a moment later is worse than one
     *   that works when it appears.
     * @property canMessage whether to offer "New message". False when signed out,
     *   and also when the caller shares no project with anybody — which is a real
     *   state on a fresh instance, and one worth rendering as a sentence rather
     *   than as a button that opens an empty picker.
     * @property isLoaded whether the first fetch has returned. Before it has, the
     *   pane shows nothing rather than "no conversations yet" — telling somebody
     *   their inbox is empty before asking is worse than a moment of nothing.
     * @property openConversationId the conversation being read, or null for the
     *   list alone.
     *
     *   An **id** rather than the conversation itself, which is the same seam
     *   `ForumBackingViewModel` draws: the detail is owned by a
     *   [ConversationBackingViewModel] built for that id, so a deep link that
     *   arrives before anything has been fetched can open one immediately.
     * @property isConversationFocused whether the reader is *in* the thread window
     *   rather than in the list beside it.
     *
     *   Purely about which pane the shell should call active, and it exists for the
     *   reason `ForumBackingViewModel.State.isPostFocused` does: driving that from
     *   `openConversationId` alone would go on naming the thread active after the
     *   reader clicked back to the list, and the next push would raise it over them
     *   again. Maintained by the two mousedown listeners in `main.kt`, whose echoes
     *   `deliver`'s focus-report rule suppresses.
     * @property isComposingNew whether the reader is writing a **new** conversation
     *   rather than reading one.
     *
     *   The two are exclusive and share the same window, which is the whole shape
     *   of LNL-64's answer to "where does the composer go": starting a conversation
     *   and reading one are two things you do with this tab's list, so they are two
     *   contents of one pane rather than a pane and a modal over it. Setting either
     *   clears the other; see [onNewMessageTapped] and [onConversationOpened].
     *
     *   Deliberately a flag rather than a nullable draft id. Nothing is created on
     *   the server when this becomes true — a conversation cannot exist before its
     *   participants are chosen (see `MessageComposerBackingViewModel`) — so there
     *   is genuinely no id to hold, and a nullable one would invite somebody to
     *   fetch it.
     * @property errorMessage a human-readable failure, or null.
     */
    data class State(
        val conversations: List<ConversationSummary> = emptyList(),
        val recipients: List<UserOption> = emptyList(),
        val canMessage: Boolean = false,
        val isLoaded: Boolean = false,
        val isBusy: Boolean = false,
        val openConversationId: Long? = null,
        val isConversationFocused: Boolean = false,
        val isComposingNew: Boolean = false,
        val errorMessage: String? = null,
    ) {
        /**
         * What a conversation's row is called: the other people in it.
         *
         * Spelled here rather than on the wire type, for `IssueBackingViewModel`'s
         * reason — the server has no opinion about how wide a list row is. Three
         * names, then a count: a group of eight rendered in full would wrap to
         * three lines and stop being a list, and a group of two must not say "and
         * 0 others".
         *
         * The empty case is reachable and is not a bug: everybody else in the
         * conversation has deleted their account. "Nobody else" is the honest
         * thing to say about a thread you are now alone in.
         */
        fun heading(conversation: ConversationSummary): String {
            val names = conversation.participantNames
            return when {
                names.isEmpty() -> "Nobody else"
                names.size <= 3 -> names.joinToString(", ")
                else -> names.take(3).joinToString(", ") + " and ${names.size - 3} others"
            }
        }

        /**
         * The initial in a conversation row's avatar circle, as the design shows.
         *
         * A letter rather than a picture, for `ForumPostBackingViewModel`'s reason:
         * no provider avatar is stored anywhere in this schema. The *first* other
         * participant's, so a 1:1 shows the person you are talking to; a group
         * shows whoever sorts first, which is stable rather than meaningful and is
         * the best a single circle can do.
         */
        fun initial(conversation: ConversationSummary): String =
            conversation.participantNames.firstOrNull()?.trim()?.firstOrNull()?.uppercase() ?: "?"

        /** When the last message landed, for the row's right-hand end. */
        fun timestamp(conversation: ConversationSummary): String =
            formatTimestamp(conversation.lastMessageAt)

        /**
         * The last message, flattened to one line for the row's preview.
         *
         * The body arrives as whole markdown — the server deliberately does not
         * truncate, because where to cut is a question about this row's width. So
         * the cutting is here: newlines collapse to spaces (a preview is one line
         * by definition) and the result is clipped with an ellipsis.
         *
         * Markdown is **not** rendered, and the syntax is left visible rather than
         * stripped. Stripping it would need a second, lossy parser next to
         * `renderMarkdown`, and a preview reading `![screenshot](…)` is a truer
         * summary of "they sent a picture" than a blank line would be.
         */
        fun preview(conversation: ConversationSummary): String {
            val flat = conversation.lastMessageBody.replace('\n', ' ').replace('\r', ' ').trim()
            return if (flat.length <= PREVIEW_LENGTH) flat else flat.take(PREVIEW_LENGTH).trimEnd() + "…"
        }

        /**
         * Whether the Messages tab has a second pane at all.
         *
         * Reading a thread and writing a new one share one window — see
         * [isComposingNew] — so "is there a window" is the disjunction rather than
         * either field, and `main.kt` asks it twice (which panes exist, and which is
         * active). Spelled here so those two answers cannot drift apart.
         */
        val hasWindow: Boolean get() = openConversationId != null || isComposingNew

        /**
         * The Messages tab's badge: every unread message, across every conversation.
         *
         * Summed from the rows rather than fetched as its own number, so the badge
         * and the pills beside it cannot disagree — one response answers both, and a
         * second request for the total would eventually be a beat behind the list it
         * is meant to summarise.
         *
         * `Int` because that is what the toolkit's `TabBadge.Count` takes, and the
         * cap is the toolkit's too: 99 renders as `99`, 100 as `99+`, with the
         * uncapped number kept in the `aria-label`. Nothing here has to know the
         * number 99 — see main.kt, which pushes this unconditionally because a count
         * of zero draws nothing.
         *
         * `coerceAtMost` on the sum guards the one thing an Int cannot: a total past
         * `Int.MAX_VALUE` would wrap to a negative, which the toolkit renders as
         * nothing at all — a badge that vanishes at the point it is most obviously
         * needed. Unreachable, and one call rather than a comment explaining why it
         * cannot happen.
         */
        val unreadMessageCount: Int
            get() = conversations.sumOf { it.unreadCount }
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()

        /**
         * A conversation row's unread pill, or null when there is nothing to say.
         *
         * The design's pill carries a number, so this is text rather than a boolean.
         * Capped at the same place the tab badge is, because a row is narrower than
         * a tab and a four-digit pill would push the timestamp off the card — the
         * cap is spelled here rather than borrowed from the toolkit because the
         * toolkit's is about a tab strip this does not live in.
         */
        fun unreadPill(conversation: ConversationSummary): String? = when {
            conversation.unreadCount <= 0 -> null
            conversation.unreadCount > UNREAD_PILL_CAP -> "$UNREAD_PILL_CAP+"
            else -> conversation.unreadCount.toString()
        }

        /** The conversation currently open, if it is one this reader has in their list. */
        val openConversation: ConversationSummary? get() =
            conversations.firstOrNull { it.id == openConversationId }
    }

    /**
     * Fetch the list, and open whatever the URL asked for.
     *
     * Called by `main.kt` once the session is known, and again whenever it
     * changes — signing in, signing out, or an admin starting or stopping
     * impersonation. The deep-link arguments are only honoured on the *first*
     * call, which is what the null default expresses: `?conversation=` is a
     * load-time instruction, not a state to restore on every sign-in. See
     * `AppUrl.nextSearch`.
     *
     * The conversation is opened **before** the list arrives rather than after,
     * deliberately. A deep link from an e-mail lands on a cold page, and waiting
     * for the list would leave the reader looking at nothing for a round-trip when
     * the thing they clicked is fetchable from its id alone.
     *
     * @param conversationId `?conversation=`, or null.
     * @param messageId `?message=`, or null. Held until the conversation reports
     *   it has loaded; see [pendingMessageId].
     */
    fun start(conversationId: Long? = null, messageId: Long? = null) {
        if (conversationId != null) {
            pendingConversationId = conversationId
            pendingMessageId = messageId
            _stateFlow.value = _stateFlow.value.copy(openConversationId = conversationId)
        }
        refresh()
    }

    /** Re-fetch the list, keeping whatever is open. */
    fun refresh() {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            runCatching { storage.conversations() }.fold(
                onSuccess = { list ->
                    val current = _stateFlow.value
                    _stateFlow.value = current.copy(
                        conversations = list.conversations,
                        recipients = list.recipients,
                        canMessage = list.canMessage,
                        isLoaded = true,
                        isBusy = false,
                        // Deliberately not narrowed to "a conversation still in the
                        // list". A deep link can name one the list has not been
                        // fetched for yet, and a reader who is not a participant is
                        // not in it at all — see AccessControl.canReadConversation,
                        // which since LNL-190 refuses everybody. What decides whether an
                        // open conversation is readable is the conversation's own
                        // fetch, which 404s if it is not.
                        openConversationId = current.openConversationId,
                    )
                },
                onFailure = { failure ->
                    _stateFlow.value = _stateFlow.value.copy(
                        isBusy = false,
                        isLoaded = true,
                        errorMessage = failure.userMessage("Could not load your messages."),
                    )
                },
            )
        }
    }

    /**
     * The session changed: throw everything away and ask again.
     *
     * Not a refresh. An open conversation belongs to whoever was signed in, and
     * carrying the id across a sign-out would leave the next reader's pane pointed
     * at a thread they will be 404ed from — which renders as an error where a blank
     * list is the truth.
     *
     * The one thing that does survive is the deep link, and only across the *first*
     * resolution: `main.kt` hands `?conversation=` over while the session is still
     * in flight, so the boot's own session change would otherwise discard the very
     * thing the reader clicked. See [pendingConversationId].
     *
     * @param identity the effective user's id, or null for signed out. Compared
     *   rather than trusted to be a change, for `MainScreenBackingViewModel`'s
     *   reason: the session flow emits for things that are not a change of person,
     *   and re-fetching on each would be a request per emission.
     */
    fun onSessionChanged(identity: Long?) {
        if (hasSession && identity == this.identity) return
        val isFirstResolution = !hasSession
        hasSession = true
        this.identity = identity
        val deepLink = if (isFirstResolution) pendingConversationId else null
        if (!isFirstResolution) pendingMessageId = null
        pendingConversationId = null
        _stateFlow.value = State(openConversationId = deepLink)
        refresh()
    }

    /**
     * The reader clicked a conversation.
     *
     * Opening is focusing, so this sets [State.isConversationFocused] too — the
     * window is being put in front of the reader, and a snapshot that opened it
     * without focusing it would leave the toolkit's idea of the active pane and
     * ours disagreeing from the first frame. `ForumBackingViewModel.onPostOpened`
     * says the same one tab over.
     *
     * Idempotent on the conversation already open apart from that focus, which is
     * what makes clicking the same row twice a focus command rather than a rebuild:
     * see `ConversationWindows`, which swaps the window's contents only when the id
     * actually changes.
     */
    fun onConversationOpened(id: Long) {
        pendingConversationId = null
        val state = _stateFlow.value
        if (state.openConversationId == id) {
            if (!state.isConversationFocused) {
                _stateFlow.value = state.copy(isConversationFocused = true)
            }
            return
        }
        // A message to scroll to only ever comes from the URL, so opening one by
        // hand clears any that is still pending — otherwise a reader who clicked
        // away from a deep-linked thread and back would be jumped to a message
        // they did not ask for.
        pendingMessageId = null
        markRead(id)
        _stateFlow.value = state.copy(
            openConversationId = id,
            isConversationFocused = true,
            // Reading and composing share the window, so one displaces the other.
            // Whatever was half-written is discarded by the view being torn down —
            // see `ConversationWindows.dispose`, which cancels the composer rather
            // than leaving an unsent conversation row behind.
            isComposingNew = false,
        )
    }

    /**
     * "New message" was pressed.
     *
     * Puts the tab into the composing state rather than opening anything. Nothing is
     * fetched and nothing is created — a conversation cannot exist before its
     * participants are chosen — so this is one field, and the window that appears is
     * `ConversationWindows` reacting to it, exactly as it reacts to a row being
     * clicked.
     *
     * Idempotent apart from focus, for [onConversationOpened]'s reason: pressing it
     * again while a new message is already being written must raise that window
     * rather than throw the half-written body away.
     */
    fun onNewMessageTapped() {
        val state = _stateFlow.value
        if (state.isComposingNew) {
            if (!state.isConversationFocused) {
                _stateFlow.value = state.copy(isConversationFocused = true)
            }
            return
        }
        pendingMessageId = null
        _stateFlow.value = state.copy(
            isComposingNew = true,
            openConversationId = null,
            isConversationFocused = true,
        )
    }

    /** The new-message window was closed, or its composer gave up. */
    fun onComposeCancelled() {
        val state = _stateFlow.value
        if (!state.isComposingNew) return
        _stateFlow.value = state.copy(isComposingNew = false, isConversationFocused = false)
    }

    /**
     * Tell the server this conversation has been read, and take the fresh counts.
     *
     * **Opening is reading**, for a conversation: the transcript arrives whole and
     * is in front of the reader, so there is no finer moment to wait for. That is
     * genuinely different from a forum post, where the list stays on screen and
     * "opened" has to be decided — see `ForumBackingViewModel.onPostOpened`.
     *
     * Fired from the intent rather than from the conversation's own fetch, so that
     * the two round-trips overlap instead of queueing: the window is being built at
     * the same moment and the reader waits for neither.
     *
     * A failure is **swallowed**, unlike every other request in this class. A read
     * mark is bookkeeping about somebody's own attention; the worst case is a badge
     * that stays up until the next time they open the thread, and putting a red
     * error line over a conversation they successfully opened would be reporting a
     * problem they do not have. Deliberate, and the only silent failure here.
     */
    private fun markRead(id: Long) {
        scope.launch {
            runCatching { storage.markConversationRead(id) }
                .onSuccess { list ->
                    // The whole refreshed list, not a patch: the server decides the
                    // counts and the order, and a client subtracting its own row's
                    // count would be holding an opinion about both.
                    val current = _stateFlow.value
                    _stateFlow.value = current.copy(
                        conversations = list.conversations,
                        recipients = list.recipients,
                        canMessage = list.canMessage,
                        isLoaded = true,
                    )
                }
        }
    }

    /**
     * A pane of the Messages tab was pressed.
     *
     * The conversation list and the thread window report their own mousedowns, so
     * the pane snapshot's `activePaneId` follows the window the reader is actually
     * in. See [State.isConversationFocused] for what goes wrong without it.
     */
    fun onConversationWindowFocused(focused: Boolean) {
        val state = _stateFlow.value
        if (state.isConversationFocused == focused) return
        _stateFlow.value = state.copy(isConversationFocused = focused)
    }

    /** The reader closed the conversation, or it went away under them. */
    fun onConversationClosed(changed: Boolean) {
        pendingMessageId = null
        _stateFlow.value = _stateFlow.value.copy(
            openConversationId = null,
            isConversationFocused = false,
        )
        if (changed) refresh()
    }

    /**
     * Something was written in the open conversation.
     *
     * The list carries a preview and a timestamp per row, so a sent message makes
     * it stale — and the order too, since a conversation spoken in moves to the
     * top. Cheaper than it looks: one request, only after a write.
     */
    fun onConversationChanged() = refresh()

    /**
     * A new conversation was started and its first message sent.
     *
     * Opens it as well as refreshing, so the sender lands in the thread they just
     * created rather than back at a list where they have to find it. This is the
     * one place the composer's result becomes navigation.
     *
     * Since LNL-64 that navigation happens **inside one window**: the pane showing
     * the new-message form becomes the pane showing the thread, with nothing opening
     * or closing, because both are `CONVERSATION_PANE_ID`. That is the payoff of
     * making the composer a pane rather than a modal over one.
     */
    fun onConversationStarted(id: Long) {
        pendingMessageId = null
        _stateFlow.value = _stateFlow.value.copy(
            openConversationId = id,
            isConversationFocused = true,
            isComposingNew = false,
        )
        refresh()
    }

    /**
     * The message a deep link asked to land on, once and then never again.
     *
     * Read by the pane when the open conversation reports that it has loaded, and
     * cleared by the reading of it: scrolling somewhere is a thing that happens
     * once, and a value that survived would re-scroll on every later render — over
     * a reader who had scrolled somewhere else themselves.
     */
    fun takePendingMessageId(): Long? {
        val id = pendingMessageId
        pendingMessageId = null
        return id
    }

    /** Dismiss the failure line. */
    fun onErrorDismissed() {
        _stateFlow.value = _stateFlow.value.copy(errorMessage = null)
    }

    private companion object {
        /**
         * How much of the last message a list row shows.
         *
         * Characters rather than a CSS ellipsis, because the preview shares its row
         * with a heading and a timestamp: letting the text run and clipping it in
         * CSS would make the row's layout depend on a body somebody else wrote.
         */
        const val PREVIEW_LENGTH = 90

        /**
         * The largest number a conversation row's unread pill spells out.
         *
         * The same 99 the toolkit's tab badge uses, and deliberately not imported
         * from it: that constant is about how wide a tab may get, this one is about
         * how wide a list row may get, and they agree today by choice rather than by
         * necessity.
         */
        const val UNREAD_PILL_CAP = 99
    }
}
