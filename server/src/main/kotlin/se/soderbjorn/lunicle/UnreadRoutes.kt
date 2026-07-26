/**
 * The one read/unread question that belongs to no project and no conversation:
 * *is there anything new in the Discussion tab?*
 *
 * Its own file for the reason [ForumRoutes] and [MessageRoutes] each have one, in
 * the negative: it runs neither of their gates. `ForumPostRoutes` resolves a
 * project and a forum out of the path and checks the caller against them;
 * `MessageRoutes` checks membership of a conversation. This route names nothing at
 * all — it asks about the whole instance, and its gate is the *set* of projects the
 * caller can read rather than a yes or no about one.
 *
 * ── Why the badge spans projects, when the tab shows one ────────────────────
 *
 * The Discussion tab always has a project selected, so the obvious place for this
 * boolean was a field on `ForumListState` — one more thing the pane already
 * fetches. It is wrong, because the badge is on the **tab strip**, which is app
 * chrome and outlives whatever project the board's picker happens to be on. A dot
 * that appeared only while the right project was selected would tell the reader
 * nothing the post list under it was not already telling them, and would go out
 * whenever they switched project, which reads as "you have read it".
 *
 * The spanning is also what gives LNL-64's acceptance criterion something to be
 * true about: *losing visibility of a project removes its contribution to every
 * badge.* That is a claim about a project other than the one on screen.
 *
 * ── Where visibility is applied, and where it deliberately is not ───────────
 *
 * Here, in Kotlin, through the same [AccessControl.canReadProject] every read route
 * in this server runs — and **not** in SQL. `ReadStore.hasUnreadPosts` takes the
 * project ids already narrowed and has no opinion about them.
 *
 * That is Subscriptions.sq's position, and LNL-63's lesson: two copies of a
 * visibility rule, one of them in a language nobody looks in, is how the two come
 * to disagree. It is also why this is a **read-time** narrowing rather than
 * anything recorded when a post is written. A role is revoked long afterwards, by a
 * gesture that knows nothing about badges; the only moment the answer can be
 * correct is the moment it is asked.
 *
 * @see ReadStore
 * @see DiscussionUnreadState
 */
package se.soderbjorn.lunicle

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.DiscussionUnreadState

/** Mount the instance-wide unread route. Called by [boardRoutes]. */
fun Route.unreadRoutes(deps: BoardDependencies) {
    /**
     * Whether anything in the Discussion tab is new to the caller.
     *
     * Signed out is `false` rather than a 401, matching [ApiRoutes.SESSION] and the
     * conversation list: the shell asks this before it knows whether anyone is
     * signed in. It is also the *correct* answer rather than a convenient one — a
     * visitor with no account has no read marks anywhere, so every post on the
     * instance would be new to them for ever, and the tab would wear a dot nothing
     * could clear.
     */
    get(ApiRoutes.DISCUSSION_UNREAD) {
        val user = call.caller(deps)
        if (user == null) {
            call.respond(DiscussionUnreadState())
            return@get
        }
        call.respond(DiscussionUnreadState(hasUnreadPosts = deps.discussionUnreadFor(user)))
    }
}

/**
 * Is there an unread post in any project [user] can see?
 *
 * A `BoardDependencies` extension for `messageableUsers`' reason — it reads three
 * things and belongs to no single class — and shaped like that function
 * deliberately: both are "narrow the projects with `canReadProject`, then ask a
 * question of the survivors", which is the only correct way to phrase an
 * instance-wide question in this codebase.
 */
private suspend fun BoardDependencies.discussionUnreadFor(user: UserRecord): Boolean {
    val visible = projects.selectAll().filter { access.canReadProject(user, it) }
    return reads.hasUnreadPosts(user.id, visible.map { it.id })
}
