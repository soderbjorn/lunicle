/**
 * The alarm bell and its list (LNL-109): how many notifications are unread, and —
 * when the panel is open — what they are.
 *
 * ── Two reads, on purpose ────────────────────────────────────────────────────
 *
 * The bell only ever needs a number, and it asks for one — [refreshCount], one
 * indexed count against `/api/notifications/unread-count`. The whole list is
 * heavier (every row, its title, its destination) and is fetched only when the
 * panel opens, by [refreshList]. Keeping them apart is what lets the bell's
 * five-minute poll stay cheap; see [NotificationCountState] and this app's one
 * deliberate poll below.
 *
 * ── Polling, which this view model does NOT do ──────────────────────────────
 *
 * Unlike [UnreadBackingViewModel] — which refreshes only at moments this browser
 * causes — the notification bell has a genuine liveness need: a notification is
 * usually created by *somebody else's* action, which this browser cannot learn of
 * without asking. So `main.kt` drives a five-minute [refreshCount] loop while
 * signed in (LNL-109's chosen cadence). This view model stays timer-free so the
 * cadence, and the "signed in only" gate, live in one place. Every mutation and
 * the list fetch also refresh the count as a side effect, because each returns the
 * whole refreshed [NotificationListState].
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
import se.soderbjorn.lunicle.clientserver.NotificationListState
import se.soderbjorn.lunicle.clientserver.NotificationSummary

/**
 * Owns the alarm bell's unread count and the notification panel's list.
 *
 * @param storage the client's repository; the only collaborator.
 * @param scope coroutine scope the requests run in.
 */
class NotificationsBackingViewModel(
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _stateFlow = MutableStateFlow(State())

    /** The bell's count and the panel's list. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * @property unreadCount how many notifications are unread — drives the bell:
     *   coloured/active when > 0, and pulsing while it stays so. Zero before the
     *   first answer, the right default: a bell that flashed on every load and then
     *   went quiet would be worse than one that lights a beat late.
     * @property items the panel's rows, newest first. Empty until [refreshList] has
     *   run at least once — the bell can flash long before the panel is ever opened.
     * @property listLoaded whether [items] reflects a real fetch, so the panel can
     *   tell "no notifications" from "not fetched yet" and show a spinner rather
     *   than an empty state on first open.
     */
    data class State(
        val unreadCount: Int = 0,
        val items: List<NotificationSummary> = emptyList(),
        val listLoaded: Boolean = false,
    ) {
        /** Whether the bell should read as having something — and pulse. */
        val hasUnread: Boolean get() = unreadCount > 0
    }

    /**
     * Refresh the count alone — the bell's poll, and the cheap refresh at every
     * moment the answer can have changed for this reader (a sign-in, an action).
     *
     * A failure leaves the previous count standing and does not report itself: a
     * failed network request is not evidence that anything was read, which is the
     * only thing lowering the count would claim. The list is left untouched.
     */
    fun refreshCount() {
        scope.launch {
            runCatching { storage.notificationsUnreadCount() }
                .onSuccess { count ->
                    _stateFlow.value = _stateFlow.value.copy(unreadCount = count.unreadCount)
                }
        }
    }

    /**
     * Refresh the whole list — run when the panel opens. Updates the count too,
     * since the response carries both. A failure leaves the previous state standing.
     */
    fun refreshList() {
        scope.launch {
            runCatching { storage.notifications() }.onSuccess { apply(it) }
        }
    }

    /** Mark one notification read (on click), then take the refreshed list. */
    fun markRead(id: Long) {
        scope.launch {
            runCatching { storage.markNotificationRead(id) }.onSuccess { apply(it) }
        }
    }

    /** Mark every notification read. */
    fun markAllRead() {
        scope.launch {
            runCatching { storage.markAllNotificationsRead() }.onSuccess { apply(it) }
        }
    }

    /** Dismiss (remove) one notification. */
    fun dismiss(id: Long) {
        scope.launch {
            runCatching { storage.dismissNotification(id) }.onSuccess { apply(it) }
        }
    }

    /** Clear every notification. */
    fun clear() {
        scope.launch {
            runCatching { storage.clearNotifications() }.onSuccess { apply(it) }
        }
    }

    /**
     * The session changed: throw the state away, then ask again.
     *
     * Cleared **first** rather than re-fetched, for [UnreadBackingViewModel.onSessionChanged]'s
     * reason — no notification (or its count) may leak onto a signed-out page, or
     * from one account onto the next, across the round-trip the refresh takes. Only
     * the count is re-fetched; the list is fetched lazily when the panel next opens.
     */
    fun onSessionChanged() {
        _stateFlow.value = State()
        refreshCount()
    }

    /** Adopt a refreshed list-and-count response as the whole state. */
    private fun apply(state: NotificationListState) {
        _stateFlow.value = State(
            unreadCount = state.unreadCount,
            items = state.items,
            listLoaded = true,
        )
    }
}
