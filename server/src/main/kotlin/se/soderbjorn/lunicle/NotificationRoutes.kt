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
     * It used to be one indexed count, which was the whole reason this is its own
     * route, and it is not any more (LUS-14). The count has to be computed the same
     * way the list is — narrowed by present access — because a badge that counts
     * rows the panel withholds is a badge nobody can clear.
     *
     * The cost is bounded by one person's own notifications, which is a small number
     * by construction: this reads nothing shared and scans nothing but rows that
     * already belong to the caller.
     *
     * Zero for a signed-out caller or a null store.
     */
    get(ApiRoutes.NOTIFICATIONS_UNREAD_COUNT) {
        val state = deps.notificationListFor(call.caller(deps))
        call.respond(NotificationCountState(unreadCount = state.unreadCount))
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
 * The caller's notification list and unread count, narrowed to what they may
 * **currently** read, or an empty state for a signed-out caller or a null store.
 *
 * The one place the two are computed, so every response — the count route, the list
 * route and all four writes' refreshed answer — agrees on what "the current state"
 * is. That was already true; what changed is what the state means.
 *
 * ── Why "is it mine" was not enough (LUS-14) ────────────────────────────────
 *
 * A notification row stores the issue or post title **verbatim**, and this file's
 * preamble says every route here runs no project gate at all: a notification
 * belongs to one person, so "may I see this" was answered by "is it mine".
 *
 * That reasoning is sound about *ownership* and silent about *time*. Revoking
 * somebody's rung writes to the roles table and nothing else, so a former
 * Contributor kept a permanent, indexed list of titles from a board they can no
 * longer open. Clicking through correctly 404s — the leak was never the content, it
 * was the metadata. And every other instance-wide read in this codebase narrows by
 * present access: the discussion-unread badge next door re-runs its read check at
 * request time and its comment names this as a lesson already learned once.
 *
 * ── Where the rule lives ────────────────────────────────────────────────────
 *
 * In Kotlin, at the route, over `canReadProject` — so there is one copy of it, and
 * it is the same copy `discussionUnreadFor` and `messageableUsers` use. A SQL
 * predicate would be a second implementation of a rule that already has ladders,
 * audiences and an instance role in it.
 *
 * A notification with no project — a private message — is nobody's project to gate,
 * and the conversation it points at is already scoped to its participants. Those
 * pass through untouched.
 */
private suspend fun BoardDependencies.notificationListFor(user: UserRecord?): NotificationListState {
    val store = notificationStore
    if (user == null || store == null) return NotificationListState()

    val items = store.listForUser(user.id)
    // Resolved once for the whole list rather than per row: a person with fifty
    // notifications across four boards should ask the ladder four times, not fifty.
    val readable = items.mapNotNull { it.projectId }.toSet()
        .filter { id -> projects.findById(id)?.let { access.canReadProject(user, it) } == true }
        .toSet()
    val visible = items.filter { it.projectId == null || it.projectId in readable }

    return NotificationListState(
        items = visible,
        // Counted off the same list, not asked of the store again. Two questions
        // answered by two different rules is exactly the badge-nobody-can-clear bug.
        unreadCount = visible.count { !it.isRead },
    )
}
