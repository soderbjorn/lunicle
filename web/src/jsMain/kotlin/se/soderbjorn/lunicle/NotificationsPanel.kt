/**
 * The body of the notifications sidebar (LNL-109) — the list the alarm bell opens.
 *
 * The toolkit owns the chrome (the slide-in `<aside>`, its header, the close
 * button, Escape, and mutual exclusion with the theme/settings panels); this fills
 * the body slot lunula hands it. See `AppShellSpec.notificationsContent`, wired in
 * `main.kt`.
 *
 * ── Why it keeps a live subscription ────────────────────────────────────────
 *
 * lunula invokes the body factory once each time the panel opens, so a naive body
 * would freeze at the state it was built against — mark one read and the row would
 * not dim until the panel was closed and reopened. Instead [body] launches a
 * collector on [NotificationsBackingViewModel.stateFlow] and re-renders its own
 * contents on every emission, so acting inside the panel (mark read, dismiss, mark
 * all, clear) updates it in place. The previous panel's collector is cancelled when
 * a new body is built, so reopening does not stack subscriptions on detached DOM.
 *
 * Navigation is the host's, not this file's: a row click hands the notification
 * back through [onOpen], which switches tab and opens the destination window from
 * the same view-model entry points a board click uses — no deep-link URL round
 * trip. See `main.kt`'s `navigateToNotification`.
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.client.viewmodel.NotificationsBackingViewModel
import se.soderbjorn.lunicle.clientserver.NotificationSummary

/**
 * Builds and keeps live the notifications sidebar body.
 *
 * @param viewModel the bell/list view model — the only source of rows and the
 *   target of every action.
 * @param scope the app scope the live-render collector runs in.
 * @param onOpen invoked with the clicked notification: the host navigates to it
 *   and closes the panel. Marking it read is done here, before the call.
 */
class NotificationsPanel(
    private val viewModel: NotificationsBackingViewModel,
    private val scope: CoroutineScope,
    private val onOpen: (NotificationSummary) -> Unit,
) {
    /** The collector re-rendering the currently-mounted body, cancelled on rebuild. */
    private var renderJob: Job? = null

    /**
     * The body element for lunula's `notificationsContent` slot.
     *
     * Returns immediately with an empty container and fills it from the flow; the
     * first emission is the current state, so there is no blank frame beyond the
     * one paint.
     */
    fun body(): HTMLElement {
        val container = element("div", "notif-panel")
        renderJob?.cancel()
        renderJob = scope.launch {
            viewModel.stateFlow.collect { state -> render(container, state) }
        }
        return container
    }

    private fun render(container: HTMLElement, state: NotificationsBackingViewModel.State) {
        container.clear()

        // Header: the two list-wide actions. Both are omitted when there is nothing
        // to act on, so an empty panel is not a row of dead buttons.
        if (state.items.isNotEmpty()) {
            val actions = element("div", "notif-actions")
            val markAll = element("button", "notif-action", "Mark all read")
            markAll.setAttribute("type", "button")
            markAll.addEventListener("click", { viewModel.markAllRead() })
            val clearAll = element("button", "notif-action notif-action-danger", "Clear all")
            clearAll.setAttribute("type", "button")
            clearAll.addEventListener("click", { viewModel.clear() })
            actions.children(markAll, clearAll)
            container.appendChild(actions)
        }

        if (state.items.isEmpty()) {
            val empty = element(
                "div",
                "notif-empty",
                if (state.listLoaded) "You have no notifications." else "Loading…",
            )
            container.appendChild(empty)
            return
        }

        val list = element("div", "notif-list")
        for (item in state.items) {
            list.appendChild(row(item))
        }
        container.appendChild(list)
    }

    private fun row(item: NotificationSummary): HTMLElement {
        val row = element("div", "notif-row" + if (item.isRead) "" else " notif-row-unread")

        // The line and its age, stacked. `textContent`, never innerHTML: the title
        // is server-composed but carries user-controlled names and titles, and this
        // is a leaf that shows text, not markup.
        val main = element("button", "notif-row-main")
        main.setAttribute("type", "button")
        val title = element("div", "notif-title")
        title.textContent = item.title
        val time = element("div", "notif-time")
        time.textContent = relativeTime(item.createdAt)
        main.children(title, time)
        // Click: mark read, then let the host navigate + close. Marking first so the
        // row it leaves behind (the panel may stay in the DOM for the close
        // animation) is already dimmed.
        main.addEventListener("click", {
            viewModel.markRead(item.id)
            onOpen(item)
        })

        // Dismiss (×), stopping the click from also opening the destination.
        val dismiss = element("button", "notif-dismiss")
        dismiss.setAttribute("type", "button")
        dismiss.setAttribute("aria-label", "Dismiss")
        dismiss.title = "Dismiss"
        dismiss.appendChild(crossIcon())
        dismiss.addEventListener("click", { ev ->
            ev.stopPropagation()
            viewModel.dismiss(item.id)
        })

        row.children(main, dismiss)
        return row
    }
}

/**
 * A short, glanceable age — "just now", "5m", "3h", "2d", or a date once it is old
 * enough that a relative label stops being useful.
 *
 * Deliberately terse: the row is narrow and the age is secondary to the line above
 * it. Anything past a week reads as an absolute day rather than "9d", which nobody
 * counts in their head.
 */
private fun relativeTime(createdAt: Long): String {
    val now = kotlin.js.Date.now()
    val seconds = ((now - createdAt) / 1000).toLong()
    return when {
        seconds < 45 -> "just now"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3600}h"
        seconds < 604_800 -> "${seconds / 86_400}d"
        else -> {
            val d = kotlin.js.Date(createdAt)
            d.toDateString()
        }
    }
}
