/**
 * The forum content routes: reading a forum's posts, reading one post with its
 * comments, and writing either.
 *
 * Its own file rather than more handlers in [ForumRoutes], and the split is the
 * same one that file describes: every route *there* runs the project-administrator
 * gate, and every route *here* runs a different one — "signed in and can see the
 * project". Two gates in one file is how a handler ends up under the wrong one.
 *
 * ── The permission questions, and where each is answered ────────────────────
 *
 * Every route below is one of these:
 *
 *  - **Reading** is [ApplicationCall.readableProject], exactly as in ForumRoutes.
 *    A public project's posts are readable by a visitor with no session.
 *  - **Writing** — starting a post, publishing one, commenting — is
 *    [AccessControl.canPostInProject]: reading, minus the signed-out case. There
 *    is no forum role, by LNL-30's decision.
 *  - **Deleting** is [AccessControl.canDeleteForumContent], which is the one rule
 *    in this feature where a project administrator reaches somebody else's words.
 *    That is a decision rather than drift; the reasoning is on the function.
 *  - **Watching** (LNL-63) is reading plus a session plus an address on file, and
 *    it is deliberately not a rule of its own — see the two `…/notification`
 *    routes, which copy `POST /api/issues/{id}/notification`'s shape line for
 *    line. Managing your own inbox is not writing here and is not administering
 *    anything.
 *
 * ── What a published post or comment sets off ───────────────────────────────
 *
 * Publishing is the one write here with consequences beyond the row: the forum's
 * watchers are mailed, the post's author is subscribed to their own post, and a
 * comment mails the post's watchers. All of it hangs off the `PUT`s below through
 * `announcePost`/`announceComment`, gated on the **transition out of draft** —
 * these `PUT`s are also re-saves, and a re-save is not an event. The auto-watch
 * decision LNL-63 asked to be made explicitly is argued on `announcePost`.
 *
 * ── The two-step write, and why every composer route comes in a pair ────────
 *
 * `POST .../posts` creates an **empty** post and answers with its id; `PUT
 * .../posts/{id}` fills it in. Same for a comment. It looks like an extra
 * round-trip and it is the thing that makes attachments work at all: the image
 * button needs a row to hang an attachment off, and a body being typed has no row
 * yet. The issue editor and the comment modal have done exactly this since they
 * were written, and reusing the shape is what let the whole attachment machinery
 * be reused unchanged. See ForumPosts.kt's preamble.
 *
 * A draft is invisible to every reader — `forForum` and `forPost` filter on
 * `is_draft` — so an abandoned one shows nobody anything, and the startup sweep
 * takes its files.
 *
 * ── What is deliberately not here ───────────────────────────────────────────
 *
 * **No history.** LNL-30 settles that posts and comments record no audit trail,
 * so unlike `issueRoutes` nothing below writes an event. The absence is the
 * decision, not the backlog.
 *
 * **No MCP.** LNL-30 is explicit that forums get no MCP tools for now, so
 * `McpTools` is untouched and there is no backfill path into these tables — which
 * is why nothing here ever passes a `createdAt` or an `agentName`, though the
 * columns exist.
 *
 * **No editing of a published post by anyone but its author**, and no UI for it
 * at all in LNL-61. The routes accept a `PUT` from the author because that is the
 * same statement as publishing; there is simply no button.
 *
 * @see ForumPosts
 * @see ForumRoutes
 * @see AccessControl.canPostInProject
 */
package se.soderbjorn.lunicle

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.ForumCommentEdit
import se.soderbjorn.lunicle.clientserver.ForumCommentView
import se.soderbjorn.lunicle.clientserver.ForumDraftRef
import se.soderbjorn.lunicle.clientserver.ForumPostDetail
import se.soderbjorn.lunicle.clientserver.ForumPostEdit
import se.soderbjorn.lunicle.clientserver.ForumPostListState
import se.soderbjorn.lunicle.clientserver.ForumPostSummary
import se.soderbjorn.lunicle.clientserver.NotificationSubscriptionRequest
import se.soderbjorn.lunicle.clientserver.UserOption

/** Mount the forum content routes. Called by [boardRoutes]. */
fun Route.forumPostRoutes(deps: BoardDependencies) {
    /**
     * A forum's posts.
     *
     * Readable by anyone who may read the project. `canPost` rides along so the
     * pane knows whether to draw "New post"; every write below re-derives it.
     */
    get(POSTS_PATTERN) {
        val scope = call.forumReadScope(deps) ?: return@get
        call.respond(deps.postListFor(scope))
    }

    /**
     * Start a post: the empty row an inline image can hang off.
     *
     * Answers with the id and nothing else. Nothing is visible to anyone until
     * the `PUT` below publishes it.
     */
    post(POSTS_PATTERN) {
        val scope = call.forumWriteScope(deps, "post here") ?: return@post
        val id = deps.forumPosts.createPostDraft(scope.forum.id, scope.user.asAuthor())
        call.respond(ForumDraftRef(id))
    }

    /** One post, its comments, and who may be mentioned in it. */
    get(POST_PATTERN) {
        val scope = call.postReadScope(deps) ?: return@get
        // An unpublished post is somebody's unsent text. 404 rather than 403, for
        // readableProject's reason: a 403 would confirm the row exists.
        if (scope.post.isDraft && !deps.access.canEditForumContent(scope.user, scope.post.author)) {
            call.respond(HttpStatusCode.NotFound, "No such post.")
            return@get
        }
        call.respond(deps.postDetailFor(scope))
    }

    /**
     * Publish a post, or re-save one.
     *
     * The author's own, or a system administrator's. **Not** the project
     * administrator's — see [AccessControl.canEditForumContent], and the
     * deliberate asymmetry with `delete` below.
     */
    put(POST_PATTERN) {
        val scope = call.postReadScope(deps) ?: return@put
        if (!deps.access.canEditForumContent(scope.user, scope.post.author)) {
            call.respond(HttpStatusCode.Forbidden, "That is not your post.")
            return@put
        }
        val body = call.receiveOrNull<ForumPostEdit>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed post.")
            return@put
        }
        deps.runPostWrite(call) {
            // Captured before the publish flips it, exactly as IssueRepository.save
            // captures `wasDraft`: this `PUT` is publish *and* re-save, and only the
            // first of those is an event. A re-save that mailed the forum again
            // would turn a typo fix into a second announcement.
            val wasDraft = scope.post.isDraft
            val published = deps.forumPosts.publishPost(scope.post, body.title, body.body)
            if (wasDraft) deps.announcePost(scope, published)
            call.respond(deps.postDetailFor(PostScope(scope.project, scope.forum, scope.user, published)))
        }
    }

    /**
     * Delete a post, its comments, and every file under it.
     *
     * The author, a project administrator, or a system administrator. Answers
     * with the refreshed *list*, not the post — the post is gone, and whoever
     * deleted it is looking at a forum next.
     */
    delete(POST_PATTERN) {
        val scope = call.postReadScope(deps) ?: return@delete
        if (!deps.access.canDeleteForumContent(scope.user, scope.post.author, scope.project.id)) {
            call.respond(HttpStatusCode.Forbidden, "You cannot delete this post.")
            return@delete
        }
        deps.forumPosts.deletePost(scope.post)
        call.respond(deps.postListFor(scope))
    }

    /** Start a comment: the empty row an inline image can hang off. */
    post(COMMENTS_PATTERN) {
        val scope = call.postReadScope(deps) ?: return@post
        val user = scope.user
        if (user == null || !deps.access.canPostInProject(user, scope.project)) {
            call.respond(HttpStatusCode.Forbidden, "You cannot comment here.")
            return@post
        }
        val id = deps.forumPosts.createCommentDraft(scope.post.id, user.asAuthor())
        call.respond(ForumDraftRef(id))
    }

    /** Publish a comment. The author's own, or a system administrator's. */
    put(COMMENT_PATTERN) {
        val scope = call.commentScope(deps) ?: return@put
        if (!deps.access.canEditForumContent(scope.user, scope.comment.author)) {
            call.respond(HttpStatusCode.Forbidden, "That is not your comment.")
            return@put
        }
        val body = call.receiveOrNull<ForumCommentEdit>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed comment.")
            return@put
        }
        deps.runPostWrite(call) {
            val wasDraft = scope.comment.isDraft
            val published = deps.forumPosts.publishComment(scope.comment, body.body)
            if (wasDraft) deps.announceComment(scope.post, published)
            call.respond(deps.postDetailFor(scope.post))
        }
    }

    /** Delete a comment. The author, a project administrator, or a system one. */
    delete(COMMENT_PATTERN) {
        val scope = call.commentScope(deps) ?: return@delete
        if (!deps.access.canDeleteForumContent(scope.user, scope.comment.author, scope.post.project.id)) {
            call.respond(HttpStatusCode.Forbidden, "You cannot delete this comment.")
            return@delete
        }
        deps.forumPosts.deleteComment(scope.comment)
        call.respond(deps.postDetailFor(scope.post))
    }

    /**
     * Record that the caller has read this post.
     *
     * ── What "viewed" means, which LNL-64 asked to be decided out loud ──────
     *
     * A post is viewed **at the moment it is opened into the window**, and that is
     * a decision rather than an obvious reading, because LNL-62's window persists
     * across posts: one constant pane id, children swapped. "The window opened" is
     * therefore not an event most of the time — the window is usually already
     * there — so the event that matters is *the post in it changed*, which is
     * exactly when the client calls this. See
     * `ForumBackingViewModel.onPostOpened`.
     *
     * Three consequences, all deliberate:
     *
     *  - **Re-opening the same post is not a second view.** The client's own
     *    intent is idempotent on the post already open, and this write is
     *    monotonic anyway, so nothing happens twice.
     *  - **Scrolling is not viewing, and neither is closing.** Anything finer
     *    would mean deciding how much of a post has to be on screen for how long,
     *    which is a question with no honest answer and a reader who scrolls past
     *    something they have read.
     *  - **A post that is merely *fetched* is not viewed.** The `GET` above marks
     *    nothing. That is what makes this a `POST` at all; see `ReadStore`.
     *
     * What is written is the **forum's** high-water mark, moved to this post's
     * `created_at`. So opening the newest post in a forum marks every older unread
     * post read as well — which is the one thing a high-water mark cannot express,
     * and LNL-30 asked for such cases to be recorded rather than quietly turned
     * into fan-out rows. It is recorded here, and it is also the reason the mark is
     * the post's timestamp rather than `now`: reading an *older* post then leaves
     * everything newer than it still unread, which is the half of the behaviour
     * worth keeping.
     *
     * The gate is reading, not writing, and not watching: knowing what you have
     * read is not posting here and is certainly not administering anything. A
     * signed-out visitor to a public project is refused because there is nobody to
     * record it against — pointedly a 403 rather than a silent success, so a
     * client that stopped sending its cookie does not appear to work.
     */
    post(POST_READ_PATTERN) {
        val scope = call.postReadScope(deps) ?: return@post
        val user = scope.user ?: run {
            call.respond(HttpStatusCode.Forbidden, "You have to be signed in for that.")
            return@post
        }
        // A draft is somebody's unsent text and appears in no list, so there is
        // nothing to have read. Refused as 404, matching the `GET` and the watch
        // route: a guessed id must answer exactly as an absent one does.
        if (scope.post.isDraft && !deps.access.canEditForumContent(scope.user, scope.post.author)) {
            call.respond(HttpStatusCode.NotFound, "No such post.")
            return@post
        }
        deps.reads.markForumRead(user.id, scope.forum.id, scope.post.createdAt)
        call.respond(deps.postListFor(scope))
    }

    /**
     * Watch or unwatch this forum: e-mail me when somebody posts here.
     *
     * ── The fourth permission question, and why it is not one of the three ───
     *
     * This file's preamble says there are three, and this is deliberately not a
     * fourth rule so much as the *reading* one applied to a different object.
     * Managing your own inbox is not writing in the forum and it is certainly not
     * administering it, so the gate is exactly "you can read this, and you are
     * signed in" — the same position `POST /api/issues/{id}/notification` takes,
     * whose shape this copies line for line including the refusal below.
     *
     * Notably **not** `canPostInProject`, though that happens to be the same
     * predicate today. They are the same sentence by coincidence rather than by
     * meaning: somebody who may read a forum but not post in it should still be
     * able to follow it, and the day a "read-only member" grant exists, this
     * route must not start refusing them.
     */
    post(FORUM_NOTIFICATION_PATTERN) {
        val scope = call.forumReadScope(deps) ?: return@post
        val user = call.requireWatcher(scope.user) ?: return@post
        val body = call.receiveOrNull<NotificationSubscriptionRequest>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        if (!call.allowSubscribe(user, body.subscribed)) return@post
        deps.subscriptions.setForumNewPostSubscription(user.id, scope.forum.id, body.subscribed)
        call.respond(deps.postListFor(scope))
    }

    /** Watch or unwatch this post: e-mail me about new comments. See the route above. */
    post(POST_NOTIFICATION_PATTERN) {
        val scope = call.postReadScope(deps) ?: return@post
        val user = call.requireWatcher(scope.user) ?: return@post
        // A draft is somebody's unsent text and appears in no list, so there is
        // nothing to follow yet. Refused as 404 rather than 403, matching the
        // `GET` above: a guessed id must answer exactly as an absent one does.
        if (scope.post.isDraft && !deps.access.canEditForumContent(scope.user, scope.post.author)) {
            call.respond(HttpStatusCode.NotFound, "No such post.")
            return@post
        }
        val body = call.receiveOrNull<NotificationSubscriptionRequest>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        if (!call.allowSubscribe(user, body.subscribed)) return@post
        deps.subscriptions.setForumPostSubscription(user.id, scope.post.id, body.subscribed)
        call.respond(deps.postDetailFor(scope))
    }
}

/**
 * The caller, if there is one to subscribe. Responds and returns null otherwise.
 *
 * A separate step from [allowSubscribe] because the two refusals are about
 * different things — "nobody is signed in" and "you are, but there is nowhere to
 * send" — and folding them into one message would tell a signed-in user with no
 * address to sign in.
 */
private suspend fun ApplicationCall.requireWatcher(user: UserRecord?): UserRecord? {
    if (user == null) {
        respond(HttpStatusCode.Forbidden, "You must be signed in to change notifications.")
        return null
    }
    return user
}

/**
 * May [user] take out this subscription? Responds with the refusal if not.
 *
 * The rule LNL-63 asks for in as many words: *a user with no e-mail address is
 * refused when trying to watch, rather than silently subscribed.* Copied from the
 * issue route rather than reasoned out again, message included, because the two
 * are the same promise and a user who meets both should read the same sentence.
 *
 * Only [subscribed] is checked. **Un**subscribing without an address has to keep
 * working: an account that had one, subscribed, and then cleared it would
 * otherwise be locked into a row it cannot remove — and the pill would be a
 * control that refuses to turn off.
 */
private suspend fun ApplicationCall.allowSubscribe(user: UserRecord, subscribed: Boolean): Boolean {
    if (subscribed && user.email == null) {
        respond(
            HttpStatusCode.Forbidden,
            "Add an e-mail address to your profile before subscribing to notifications.",
        )
        return false
    }
    return true
}

/**
 * The Ktor patterns, built from [ApiRoutes.PROJECTS] so the shared prefix has one
 * spelling.
 *
 * The segment names are written out rather than taken from the `ApiRoutes`
 * builders, which build a *path* from real ids for the client to call. The two
 * are checked against each other by ForumPostTest, which drives every route below
 * through those builders — a pattern that drifts from the builder is a 404 there
 * rather than in a browser. ForumRoutes says the same about its three.
 */
private const val POSTS_PATTERN = "${ApiRoutes.PROJECTS}/{projectId}/forums/{forumId}/posts"
private const val POST_PATTERN = "$POSTS_PATTERN/{postId}"
private const val POST_READ_PATTERN = "$POST_PATTERN/read"
private const val COMMENTS_PATTERN = "$POST_PATTERN/comments"
private const val COMMENT_PATTERN = "$COMMENTS_PATTERN/{commentId}"

// The two watch routes. Their forum-level twin hangs off `forums/{forumId}` and
// so is spelled out here rather than off POSTS_PATTERN, which already has
// `/posts` on the end.
private const val FORUM_NOTIFICATION_PATTERN =
    "${ApiRoutes.PROJECTS}/{projectId}/forums/{forumId}/notification"
private const val POST_NOTIFICATION_PATTERN = "$POST_PATTERN/notification"

/** A project and a forum inside it, both proved readable by this caller. */
private class ForumReadScope(
    val project: ProjectRecord,
    val forum: ForumRecord,
    val user: UserRecord?,
)

/** ...and a signed-in caller who may write here. */
private class ForumWriteScope(
    val project: ProjectRecord,
    val forum: ForumRecord,
    val user: UserRecord,
)

/** ...and the post named in the path. */
private class PostScope(
    val project: ProjectRecord,
    val forum: ForumRecord,
    val user: UserRecord?,
    val post: ForumPostRecord,
)

/** ...and a comment on it. */
private class CommentScope(
    val post: PostScope,
    val user: UserRecord?,
    val comment: ForumCommentRecord,
)

/**
 * Resolve a forum this caller may read, or respond and return null.
 *
 * Both ids in the path are checked, and neither is decoration: the project is
 * resolved through [ApplicationCall.readableProject] — which answers 404 for
 * "cannot see it" as well as "does not exist", so a private project's existence is
 * not confirmed — and the forum is then looked up *within* it, so naming another
 * project's forum here is a 404 rather than a read of it.
 */
private suspend fun ApplicationCall.forumReadScope(deps: BoardDependencies): ForumReadScope? {
    val user = caller(deps)
    val projectId = longParam("projectId") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad project id.")
        return null
    }
    val project = readableProject(deps, user, projectId) ?: return null
    val forumId = longParam("forumId") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad forum id.")
        return null
    }
    val forum = deps.forums.findByIdInProject(forumId, project.id) ?: run {
        respond(HttpStatusCode.NotFound, "No such forum.")
        return null
    }
    return ForumReadScope(project, forum, user)
}

/**
 * As [forumReadScope], plus "and you are signed in and may write here".
 *
 * 404 before 403, matching every other scope helper in this codebase: an id the
 * caller cannot even see answers "no such thing" rather than confirming one exists
 * by that id.
 */
private suspend fun ApplicationCall.forumWriteScope(
    deps: BoardDependencies,
    action: String,
): ForumWriteScope? {
    val scope = forumReadScope(deps) ?: return null
    val user = scope.user
    if (user == null || !deps.access.canPostInProject(user, scope.project)) {
        respond(HttpStatusCode.Forbidden, "You cannot $action.")
        return null
    }
    return ForumWriteScope(scope.project, scope.forum, user)
}

/**
 * As [forumReadScope], plus the post named in the path.
 *
 * A draft **is** found here, and that is deliberate rather than an oversight: the
 * publish `PUT` addresses the row it is about to fill in, and there is no other
 * way for it to name it. What protects a draft is not this lookup but the two
 * facts around it — it appears in no list, so the only way to learn its id is to
 * be the caller the server just minted it for, and [ForumPostRepository]'s reads
 * for the list and the comment count both filter `is_draft` out.
 *
 * The `GET` handler adds the one guard that follows from this: reading a draft is
 * refused as 404 unless the reader is its author, so a guessed id answers exactly
 * as an absent one does.
 */
private suspend fun ApplicationCall.postReadScope(deps: BoardDependencies): PostScope? {
    val scope = forumReadScope(deps) ?: return null
    val postId = longParam("postId") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad post id.")
        return null
    }
    val post = deps.forumPosts.findPostInForum(postId, scope.forum.id) ?: run {
        respond(HttpStatusCode.NotFound, "No such post.")
        return null
    }
    return PostScope(scope.project, scope.forum, scope.user, post)
}

/** As [postReadScope], plus the comment named in the path. */
private suspend fun ApplicationCall.commentScope(deps: BoardDependencies): CommentScope? {
    val scope = postReadScope(deps) ?: return null
    val commentId = longParam("commentId") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad comment id.")
        return null
    }
    val comment = deps.forumPosts.findCommentInPost(commentId, scope.post.id) ?: run {
        respond(HttpStatusCode.NotFound, "No such comment.")
        return null
    }
    return CommentScope(scope, scope.user, comment)
}

/**
 * A forum's post list, with this caller's affordances.
 *
 * @receiver the dependencies; a `BoardDependencies` extension for the reason
 *   `forumListFor` is one — these read four stores and belong to no single class.
 */
private suspend fun BoardDependencies.postListFor(scope: ForumReadScope): ForumPostListState {
    val listings = forumPosts.listing(scope.forum.id)
    // One lookup for every distinct author on the page rather than one per row.
    // See authorNames, which the issue board shares. Both authors a card can name
    // go in together — the poster and whoever last replied — because they are very
    // often the same handful of people and `authorNames` distincts them.
    val names = authorNames(listings.map { it.post.author } + listings.map { it.lastCommentAuthor })
    val canPost = scope.user != null && access.canPostInProject(scope.user, scope.project)
    // Sent with the list because a *new* post has nothing else to have got it
    // from — a comment's autocomplete comes off the post it answers, and a post
    // being written has no parent. Only to somebody who may write; a reader with
    // no composer has no mention to complete. See ForumPostListState.
    val mentionable = if (canPost) audience.forProject(scope.project) else emptyList()
    // The Watch pill's two facts. Only ever about the caller themselves — this is
    // the one thing in the response that is nobody else's business, which is why
    // there is no watcher *list* here to match the issue detail's. See below.
    val watching = scope.user != null &&
        subscriptions.isSubscribedToForumNewPosts(scope.user.id, scope.forum.id)
    // One read of the forum's high-water mark for the whole list, compared per row
    // below rather than asked per row — a post's unreadness is a comparison against
    // one number, so a query per row would be twenty round-trips to learn the same
    // thing twice. Zero for a caller who has never opened this forum, and for a
    // signed-out one, where the `user != null` below is what stops "everything is
    // unread, for ever" from being the answer.
    val mark = scope.user?.let { reads.forumMark(it.id, scope.forum.id) } ?: 0L
    return ForumPostListState(
        posts = listings.map { listing ->
            ForumPostSummary(
                id = listing.post.id,
                title = listing.post.title,
                authorName = listing.post.author.displayName(names),
                agentName = listing.post.agentName,
                createdAt = listing.post.createdAt,
                commentCount = listing.commentCount,
                lastCommentAuthor = listing.lastCommentAuthor.displayName(names),
                lastCommentAt = listing.lastCommentAt,
                // Not your own writing, and not anybody's if nobody is asking.
                isUnread = scope.user != null &&
                    listing.post.createdAt > mark &&
                    !scope.user.wrote(listing.post.author),
                canDelete = access.canDeleteForumContent(scope.user, listing.post.author, scope.project.id),
            )
        },
        canPost = canPost,
        mentionableUsers = mentionable.map {
            UserOption(id = it.id, name = it.resolvedName, isSelf = it.id == scope.user?.id)
        },
        notifyOnNewPosts = watching,
        canReceiveEmailNotifications = scope.user?.email != null,
    )
}

/** As [postListFor], from a scope that has already resolved a post. */
private suspend fun BoardDependencies.postListFor(scope: PostScope): ForumPostListState =
    postListFor(ForumReadScope(scope.project, scope.forum, scope.user))

/**
 * One post, its comments, and everything the reader needs to act on it.
 *
 * One response rather than three, for `buildIssueDetail`'s reason: a view cannot
 * render half of itself, and LNL-62 will open this in a window reachable by deep
 * link with no forum list loaded behind it — which is why [ForumPostDetail]
 * carries the forum's *name* as well as its id.
 */
private suspend fun BoardDependencies.postDetailFor(scope: PostScope): ForumPostDetail {
    val comments = forumPosts.commentsOn(scope.post.id)
    val names = authorNames(comments.map { it.author } + scope.post.author)
    val canPost = scope.user != null && access.canPostInProject(scope.user, scope.project)
    // Only offered to somebody who can actually write here. A reader with no
    // composer has no mention to complete, so shipping them the instance's
    // account list would be a directory for nothing — the same narrowing
    // buildIssueDetail does with `canEdit || canComment`.
    val mentionable = if (canPost) audience.forProject(scope.project) else emptyList()
    val watching = scope.user != null && subscriptions.isSubscribedToForumPost(scope.user.id, scope.post.id)
    return ForumPostDetail(
        id = scope.post.id,
        forumId = scope.forum.id,
        forumName = scope.forum.name,
        title = scope.post.title,
        body = scope.post.body,
        authorName = scope.post.author.displayName(names),
        agentName = scope.post.agentName,
        createdAt = scope.post.createdAt,
        comments = comments.map { comment ->
            ForumCommentView(
                id = comment.id,
                body = comment.body,
                authorName = comment.author.displayName(names),
                agentName = comment.agentName,
                createdAt = comment.createdAt,
                canDelete = access.canDeleteForumContent(scope.user, comment.author, scope.project.id),
            )
        },
        // A name and an id, never the UserRecord: those carry e-mail addresses,
        // and this is the line they stop at. See UserStore.selectAll.
        mentionableUsers = mentionable.map {
            UserOption(id = it.id, name = it.resolvedName, isSelf = it.id == scope.user?.id)
        },
        canComment = canPost,
        canDelete = access.canDeleteForumContent(scope.user, scope.post.author, scope.project.id),
        notifyOnComments = watching,
        canReceiveEmailNotifications = scope.user?.email != null,
    )
}

/**
 * A post has just been published: subscribe its author, then tell the forum.
 *
 * ── The decision LNL-63 asked to be made explicitly: yes, the author is
 * auto-watched on their own post ──────────────────────────────────────────────
 *
 * An issue's author is subscribed at publish — see `IssueRepository.save` — and a
 * post's author now is too, for the same reason and with the same mechanics
 * (keyed on the account, so an [Author.External] import subscribes nobody, and
 * idempotent, so a re-publish is a no-op).
 *
 * The case against was that a forum post is a lighter act than filing an issue
 * and subscribing somebody to anything without asking is a thing to be careful
 * about. What settles it is that the cost is precisely zero on the axis people
 * actually object to: **the actor is excluded from every recipient list**, so
 * this never mails you about your own writing. What it buys is the thing the
 * feature is for — you started a conversation, so you hear when somebody answers
 * it — which is what almost everybody would press the button for anyway, and the
 * button is right there on the post to press again if they would not.
 *
 * The alternative was to auto-watch nobody and let the pill do all the work. It
 * was rejected because it makes the common case require a deliberate action taken
 * *before* anybody has replied, which is exactly the moment nobody is thinking
 * about it — and being silently not-told about replies to your own post is a
 * worse surprise than an unwanted mail with an off switch in it.
 *
 * **Commenting does not auto-watch**, deliberately, and that asymmetry is
 * inherited rather than invented: commenting on an issue does not subscribe you
 * either. Answering somebody is not the same as starting something, and a busy
 * thread would otherwise conscript everybody who ever said anything in it.
 *
 * ── On an author with no e-mail address ─────────────────────────────────────
 *
 * The routes refuse a *pressed* subscription from an addressless account, and
 * this write does not check. That is consistent rather than a hole: the refusal
 * exists so nobody is told "you are subscribed" when nothing can be delivered,
 * and an automatic row makes no such claim. The recipient queries drop an
 * addressless account anyway, so the row is inert until the day they add an
 * address — at which point hearing about replies to their own post is what they
 * would have wanted all along. The issue side behaves identically.
 *
 * ── Not wrapped in a `runCatching`, unlike `IssueRepository.notify` ─────────
 *
 * `MessageRoutes` fires its notifier bare and this matches it, rather than
 * matching the repository's swallow-and-log. The repository's reasoning is about
 * a *sender* failing — and that failure is already swallowed one level down, per
 * recipient, in [NotificationDispatcher.send]. What is left that can throw here
 * is a database read, which is not a transient courtesy failing but the server
 * being unable to read its own tables, and hiding that behind a 200 would make it
 * invisible exactly where it matters.
 */
private suspend fun BoardDependencies.announcePost(scope: PostScope, post: ForumPostRecord) {
    post.author.accountId?.let { authorId ->
        subscriptions.setForumPostSubscription(authorId, post.id, true)
    }
    forumNotifications.postPublished(scope.project, scope.forum, post, scope.user?.id)
}

/**
 * A comment has just been published: tell the post's watchers.
 *
 * Fired from the route rather than from [ForumPostRepository], which is
 * `MessageRoutes`' arrangement and its argument: a comment is published on
 * exactly one path, so a notifier inside the repository would buy nothing and
 * would need the project and the forum threaded into it — when both are already
 * resolved here to answer the permission question. Firing from the scope that
 * authorised the write is what makes "the people mailed are the people the write
 * was checked against" true by construction.
 *
 * `IssueRepository` fires from inside for the opposite reason: an issue is
 * published on several paths.
 */
private suspend fun BoardDependencies.announceComment(scope: PostScope, comment: ForumCommentRecord) {
    forumNotifications.commentPublished(scope.project, scope.forum, scope.post, comment, scope.user?.id)
}

/**
 * Run a post or comment write, turning a [ForumPostRefusal] into a sentence.
 *
 * `runForumWrite`'s twin: a blank title is something the person typing can fix, so
 * it is a 409 with words rather than a 500 with a stack trace. Anything that is
 * not a refusal is a bug and propagates.
 */
private suspend inline fun BoardDependencies.runPostWrite(call: ApplicationCall, block: () -> Unit) {
    try {
        block()
    } catch (refusal: ForumPostRefusal) {
        call.respond(HttpStatusCode.Conflict, refusal.message ?: "That was refused.")
    }
}
