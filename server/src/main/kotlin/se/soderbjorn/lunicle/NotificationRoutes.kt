/**
 * The in-app notification list (LNL-109): the bell's poll, the panel's list, and
 * the four things the panel can do to a notification.
 *
 * Its own file for [unreadRoutes]' reason, in the strongest form: every route here
 * runs *no project gate at all*. A notification belongs to exactly one person, and
 * the store only ever returns or touches a row for the user id the route passes —
 * so "may I see this" is answered by "is it mine", and mine is the caller's own id.
 * There is nothing to check a path id against: an id that is not the caller's is
 * simply not found, the same silence the store keeps (see [NotificationStore]).
 *
 * ── Signed out, and the null store ──────────────────────────────────────────
 *
 * Reads never 401: a signed-out caller owns nothing, so the list is empty and the
 * count is zero, matching [ApiRoutes.SESSION] and the conversation list — the shell
 * polls the bell before it knows who is signed in. Writes require a caller (there
 * is nothing to mark or clear for nobody) and answer 403 otherwise. A test that
 * leaves [BoardDependencies.notificationStore] null gets the same empty answers,
 * because a store that is not there holds nothing for anybody.
 *
 * ── Writes answer with the refreshed list ───────────────────────────────────
 *
 * Every mutation responds with the whole refreshed [NotificationListState] rather
 * than a bare 204, the house style the read-marking routes established: the panel
 * that just acted needs the new list and the new count, and one round trip beats a
 * mutation followed by a re-fetch that could race another tab.
 *
 * @see NotificationStore
 * @see ApiRoutes.NOTIFICATIONS
 */
package se.soderbjorn.lunicle

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.NotificationCountState
import se.soderbjorn.lunicle.clientserver.NotificationListState

private const val NOTIFICATION_READ_PATTERN = "${ApiRoutes.NOTIFICATIONS}/{id}/read"
private const val NOTIFICATION_PATTERN = "${ApiRoutes.NOTIFICATIONS}/{id}"

/** Mount the in-app notification routes. Called by [boardRoutes]. */
fun Route.notificationRoutes(deps: BoardDependencies) {
    /**
     * The bell's five-minute poll: the caller's unread count alone.
     *
     * One indexed count, never the list — that is the whole reason this is its own
     * route. Zero for a signed-out caller or a null store.
     */
    get(ApiRoutes.NOTIFICATIONS_UNREAD_COUNT) {
        val user = call.caller(deps)
        val store = deps.notificationStore
        val count = if (user == null || store == null) 0 else store.unreadCount(user.id)
        call.respond(NotificationCountState(unreadCount = count))
    }

    /** The panel's list: the caller's notifications, newest first, with the count. */
    get(ApiRoutes.NOTIFICATIONS) {
        val user = call.caller(deps)
        call.respond(deps.notificationListFor(user))
    }

    /** Clear all of the caller's notifications. Requires a caller. */
    delete(ApiRoutes.NOTIFICATIONS) {
        val user = call.requireCaller(deps) ?: return@delete
        deps.notificationStore?.clear(user.id)
        call.respond(deps.notificationListFor(user))
    }

    /** Mark all of the caller's notifications read. Requires a caller. */
    post(ApiRoutes.NOTIFICATIONS_READ_ALL) {
        val user = call.requireCaller(deps) ?: return@post
        deps.notificationStore?.markAllRead(user.id)
        call.respond(deps.notificationListFor(user))
    }

    /**
     * Mark one notification read. The store scopes the write to the caller, so an
     * id that is not theirs marks nothing and the refreshed list simply does not
     * change — no 404 that would confirm the row exists for somebody else.
     */
    post(NOTIFICATION_READ_PATTERN) {
        val user = call.requireCaller(deps) ?: return@post
        val id = call.longParam("id") ?: run {
            call.respond(HttpStatusCode.BadRequest, "Not a notification id.")
            return@post
        }
        deps.notificationStore?.markRead(user.id, id)
        call.respond(deps.notificationListFor(user))
    }

    /** Dismiss (hard-delete) one notification. Caller-scoped, like the mark. */
    delete(NOTIFICATION_PATTERN) {
        val user = call.requireCaller(deps) ?: return@delete
        val id = call.longParam("id") ?: run {
            call.respond(HttpStatusCode.BadRequest, "Not a notification id.")
            return@delete
        }
        deps.notificationStore?.dismiss(user.id, id)
        call.respond(deps.notificationListFor(user))
    }
}

/**
 * The caller, or a 403 already sent.
 *
 * The writes here all need somebody to act as — there is nothing to mark or clear
 * for a signed-out visitor — so this collapses the "signed out is Forbidden" line
 * the four of them would otherwise repeat. Reads do not use it: signed out is a
 * legitimate empty answer there, not a refusal.
 */
private suspend fun io.ktor.server.application.ApplicationCall.requireCaller(deps: BoardDependencies): UserRecord? {
    val user = caller(deps)
    if (user == null) {
        respond(HttpStatusCode.Forbidden, "You have to be signed in for that.")
        return null
    }
    return user
}

/**
 * The caller's whole notification list and unread count, or an empty state for a
 * signed-out caller or a null store.
 *
 * The one place the two reads are paired, so every response — the list route and
 * all four writes' refreshed answer — agrees on what "the current state" is.
 */
private suspend fun BoardDependencies.notificationListFor(user: UserRecord?): NotificationListState {
    val store = notificationStore
    if (user == null || store == null) return NotificationListState()
    return NotificationListState(
        items = store.listForUser(user.id),
        unreadCount = store.unreadCount(user.id),
    )
}
