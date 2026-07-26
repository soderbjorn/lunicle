/**
 * The forum routes: reading a project's forums, and — for its administrator —
 * creating, editing, deleting and reordering them.
 *
 * Its own file for `ProjectSettingsRoutes`' reason: every write here runs the
 * same gate, and that is a property worth being able to read off a file rather
 * than infer from four handlers scattered among forty. Reading is the exception
 * and it is the first route below, so the split is visible rather than
 * remembered.
 *
 * ── Two things that are deliberately not checked here ───────────────────────
 *
 * **The forum master toggle.** `?forums=1` is a client-side flag whose job is to
 * keep the feature out of public view until it is good enough, not to protect
 * anything; somebody who works out the parameter and types it gets an early look
 * at a feature, which is not a problem worth a server-side gate. LNL-30 settles
 * this explicitly. So these routes answer whether or not the caller's browser
 * has the flag, and that is correct rather than an oversight.
 *
 * **Per-forum access.** The design prototype had a per-member, per-forum access
 * list. LNL-30 replaced it with project-level visibility, so a caller who can
 * read the project can read all of its forums and a caller who cannot read the
 * project can reach none of them. There is no third answer to give here, which
 * is why [readableProject] is the whole read check.
 *
 * @see Forums
 * @see AccessControl.canAdministerProject
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
import se.soderbjorn.lunicle.clientserver.ForumEdit
import se.soderbjorn.lunicle.clientserver.ForumListState
import se.soderbjorn.lunicle.clientserver.ForumOrder
import se.soderbjorn.lunicle.clientserver.ForumSummary

/** Mount the forum routes. Called by [boardRoutes]. */
fun Route.forumRoutes(deps: BoardDependencies) {
    /**
     * A project's forums.
     *
     * Readable by anyone who may read the project, which since LNL-57 means its
     * members and — for a public project — anybody at all, signed in or not.
     * The response carries `canManageForums` so the pane knows whether to draw
     * the administrative controls; every write below re-derives it.
     */
    get(FORUMS_PATTERN) {
        val user = call.caller(deps)
        val projectId = call.longParam("projectId") ?: run {
            call.respond(HttpStatusCode.BadRequest, "Bad project id.")
            return@get
        }
        val project = call.readableProject(deps, user, projectId) ?: return@get
        call.respond(deps.forumListFor(project, user))
    }

    post(FORUMS_PATTERN) {
        val scope = call.forumAdminScope(deps, "create a forum") ?: return@post
        val body = call.receiveOrNull<ForumEdit>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed forum.")
            return@post
        }
        deps.runForumWrite(call) {
            deps.forums.create(scope.project.id, body.name, body.description)
            call.respond(deps.forumListFor(scope.project, scope.user))
        }
    }

    put(FORUM_PATTERN) {
        val scope = call.forumScope(deps, "edit a forum") ?: return@put
        val body = call.receiveOrNull<ForumEdit>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed forum.")
            return@put
        }
        deps.runForumWrite(call) {
            deps.forums.edit(scope.forum, body.name, body.description)
            call.respond(deps.forumListFor(scope.project, scope.user))
        }
    }

    delete(FORUM_PATTERN) {
        val scope = call.forumScope(deps, "delete a forum") ?: return@delete
        deps.runForumWrite(call) {
            deps.forums.delete(scope.forum)
            call.respond(deps.forumListFor(scope.project, scope.user))
        }
    }

    post(FORUM_ORDER_PATTERN) {
        val scope = call.forumAdminScope(deps, "reorder this project's forums") ?: return@post
        val body = call.receiveOrNull<ForumOrder>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed order.")
            return@post
        }
        deps.runForumWrite(call) {
            deps.forums.reorder(scope.project.id, body.ids)
            call.respond(deps.forumListFor(scope.project, scope.user))
        }
    }
}

/**
 * The Ktor patterns, built from [ApiRoutes.PROJECTS] so that the shared prefix
 * has one spelling.
 *
 * The segment names have to be written out rather than taken from
 * [ApiRoutes.forums], which builds a *path* from real ids for the client to
 * call. The two are checked against each other by ForumTest, which drives every
 * route below through the `ApiRoutes` builders — a pattern that drifts from the
 * builder is a 404 there rather than in a browser.
 */
private const val FORUMS_PATTERN = "${ApiRoutes.PROJECTS}/{projectId}/forums"
private const val FORUM_PATTERN = "${ApiRoutes.PROJECTS}/{projectId}/forums/{forumId}"
private const val FORUM_ORDER_PATTERN = "${ApiRoutes.PROJECTS}/{projectId}/forums/order"

/** A project this caller administers, resolved for a forum write. */
private class ForumAdminScope(val project: ProjectRecord, val user: UserRecord)

/** ...and the forum inside it, for the routes that name one. */
private class ForumScope(val project: ProjectRecord, val user: UserRecord, val forum: ForumRecord)

/**
 * Resolve a project this caller may administer, or respond and return null.
 *
 * 404 before 403, matching `adminProject` and `adminSprintScope`: an id the
 * caller cannot even see answers "no such project" rather than confirming one
 * exists by that id. The order matters more here than it looks, because
 * visibility narrowed in LNL-57 — there are now projects a signed-in caller
 * genuinely cannot see.
 */
private suspend fun ApplicationCall.forumAdminScope(
    deps: BoardDependencies,
    action: String,
): ForumAdminScope? {
    val user = caller(deps)
    val projectId = longParam("projectId") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad project id.")
        return null
    }
    val project = readableProject(deps, user, projectId) ?: return null
    // The effective user, and the answer re-derived from the session — so an
    // administrator who is impersonating an ordinary user cannot reconfigure a
    // project while wearing their face. ProjectSettingsRoutes says the same at
    // greater length; the reasoning is identical and so is the consequence.
    if (user == null || !deps.access.canAdministerProject(user, project.id)) {
        respond(HttpStatusCode.Forbidden, "Only a project administrator can $action.")
        return null
    }
    return ForumAdminScope(project, user)
}

/**
 * As [forumAdminScope], plus the forum named in the path.
 *
 * The forum is looked up *within the project*, so a caller naming another
 * project's forum gets a 404 rather than editing it — the project id in the URL
 * is a claim being checked, not decoration. See Forums.sq's `findByIdInProject`.
 */
private suspend fun ApplicationCall.forumScope(deps: BoardDependencies, action: String): ForumScope? {
    val scope = forumAdminScope(deps, action) ?: return null
    val forumId = longParam("forumId") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad forum id.")
        return null
    }
    val forum = deps.forums.findByIdInProject(forumId, scope.project.id) ?: run {
        respond(HttpStatusCode.NotFound, "No such forum.")
        return null
    }
    return ForumScope(scope.project, scope.user, forum)
}

/**
 * The forum list, with this caller's affordances.
 *
 * @param user null for a signed-out visitor to a public project, who gets the
 *   forums and no controls at all.
 */
private suspend fun BoardDependencies.forumListFor(
    project: ProjectRecord,
    user: UserRecord?,
): ForumListState = ForumListState(
    forums = forums.forProject(project.id).map {
        ForumSummary(id = it.id, name = it.name, description = it.description)
    },
    canManageForums = user != null && access.canAdministerProject(user, project.id),
)

/**
 * Run a forum write, turning a [ForumRefusal] into a sentence the user sees.
 *
 * The same shape as `runVocabularyWrite`: a duplicate name is something the
 * person typing can fix, so it is a 409 with words rather than a 500 with a
 * stack trace. Anything that is not a refusal is a bug and propagates.
 */
private suspend inline fun BoardDependencies.runForumWrite(call: ApplicationCall, block: () -> Unit) {
    try {
        block()
    } catch (refusal: ForumRefusal) {
        call.respond(HttpStatusCode.Conflict, refusal.message ?: "That forum change was refused.")
    }
}
