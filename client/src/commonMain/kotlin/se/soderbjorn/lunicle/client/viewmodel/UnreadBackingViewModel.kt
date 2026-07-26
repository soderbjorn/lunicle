/**
 * The Discussion tab's badge: whether there is anything new anywhere.
 *
 * ── Why this is its own view model, and why Messages has none ───────────────
 *
 * The Messages badge is a number, and that number is the sum of the counts already
 * on [se.soderbjorn.lunicle.clientserver.ConversationSummary] — so it is derived
 * from a list `MessagesBackingViewModel` has already fetched, and a second source
 * for it would be a second thing to keep in step with the first. `main.kt` sums it
 * where the snapshot is built.
 *
 * The Discussion badge cannot be derived that way, and the reason is not laziness:
 * it spans **every project the caller can see**, and `ForumBackingViewModel` knows
 * about one — the project the board happens to be on. A dot driven from that list
 * would go out when somebody switched project, which reads as "you have read it".
 * See `ApiRoutes.DISCUSSION_UNREAD`, where that is argued from the other side.
 *
 * ── What refreshes it, and what deliberately does not ───────────────────────
 *
 * This app polls nothing, anywhere — there is no socket and no timer in it — so a
 * badge is only ever as fresh as the last thing that asked. That is honest rather
 * than a gap: what makes a dot appear is somebody else writing a post, which this
 * browser cannot learn about without being told, and inventing a poll here would be
 * the first one in the codebase.
 *
 * So it is refreshed at the moments the answer can actually have changed for *this*
 * reader: the session resolving (including a sign-out, which must take the previous
 * account's dot with it), and reading a post. `main.kt` wires both.
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

/**
 * Owns the Discussion tab's unread dot.
 *
 * @param storage the client's repository; the only collaborator.
 * @param scope coroutine scope the request runs in.
 */
class UnreadBackingViewModel(
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _stateFlow = MutableStateFlow(State())

    /** Whether the Discussion tab should wear a dot. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * @property hasUnreadPosts a **dot**, not a count. LNL-30 settles that: forum
     *   volume is unbounded and a number there creates inbox-zero pressure for
     *   something nobody is obliged to read. The server answers a boolean too, so
     *   there is no count anywhere to be tempted by.
     *
     *   False before the first answer, which is the right default: a dot that
     *   flashed on every load and then went out would be worse than one that
     *   arrives a beat late.
     */
    data class State(
        val hasUnreadPosts: Boolean = false,
    )

    /**
     * Ask again.
     *
     * A failure leaves the previous answer standing rather than clearing the dot,
     * and does not report itself. There is nowhere to report it — a tab strip has no
     * error line — and "the network failed" is not evidence that somebody has read
     * anything, which is the only thing clearing it would claim.
     */
    fun refresh() {
        scope.launch {
            runCatching { storage.discussionUnread() }
                .onSuccess { _stateFlow.value = State(hasUnreadPosts = it.hasUnreadPosts) }
        }
    }

    /**
     * The session changed: throw the answer away, then ask again.
     *
     * Cleared **first** rather than simply re-fetched, and that is LNL-64's "no
     * badge state leaks between accounts on the same browser after sign-out" in one
     * line: the request takes a round-trip, and for its duration the previous
     * account's dot would otherwise still be on the strip of a signed-out page.
     */
    fun onSessionChanged() {
        _stateFlow.value = State()
        refresh()
    }
}
