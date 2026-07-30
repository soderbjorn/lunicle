/**
 * The sprint routes that are not vocabulary routes.
 *
 * Creating, renaming, reordering and deleting a sprint go through
 * `/api/projects/{id}/vocabulary/sprint` with every other kind — see
 * [VocabularyKind]. What is here is the residue: the four verbs a label has no
 * meaning for, plus the per-issue write that schedules one card.
 *
 * ── Two gates, drawn where the rest of the API draws them ───────────────────
 *
 * **Shaping the sprint axis is a maintainer's.** Creating a sprint is already a
 * maintainer's, because it is a vocabulary write and sprints are one of the two
 * vocabularies that sit on [ProjectRole.MAINTAINER]; activating, completing and
 * reopening one are the same kind of act on the same objects, and splitting them
 * would produce the odd result that somebody could end a sprint but not make the
 * next one. So [Route.sprintRoutes]'s first three handlers ask `canEditVocabulary`
 * for [VocabularyKind.SPRINT], exactly as the vocabulary routes do for the sprint
 * rows themselves.
 *
 * This paragraph used to say those handlers ask `canMutateProjects`, "exactly as
 * ProjectSettingsRoutes does", and called the gate admin. It was never that, and
 * `canMutateProjects` has since become the *instance owner* — so the claim was off
 * by four rungs rather than one. Nothing about planning a fortnight on one board
 * belongs with the person answerable for the whole deployment.
 *
 * **Scheduling work into it is `canEditIssue`.** Which sprint an issue is in is a
 * column on that issue, and every other column on it — status, priority, assignee
 * — is written under that check. A separate lighter grant for scheduling would be
 * a way to move somebody's work without the right to edit it; a heavier one would
 * mean the person doing the planning is the one person who cannot.
 *
 * That line falls in a useful place: a maintainer decides *that* there is a sprint,
 * and anybody who can edit the issues — a contributor on their own issue included —
 * decides what goes in it.
 *
 * @see SprintRepository
 * @see ProjectSettingsRoutes
 */
package se.soderbjorn.lunicle

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.clientserver.IssueSprintUpdate
import se.soderbjorn.lunicle.clientserver.SprintActivation
import se.soderbjorn.lunicle.clientserver.SprintCompletion
import se.soderbjorn.lunicle.clientserver.SprintMembership

fun Route.sprintRoutes(deps: BoardDependencies) {
    /**
     * Point the board at a sprint, or at none.
     *
     * A `POST` to a fixed `/sprints/active` carrying the id, rather than a `POST`
     * to `/sprints/{sid}/activate`. The difference matters because deactivating
     * is a real request this API has to be able to express, and the second shape
     * has nowhere to put it — there is no sprint id to name when the answer is
     * "none of them". One route that takes a nullable id says both things; two
     * routes would say one each and the second would be a `DELETE` on a
     * collection endpoint meaning something other than delete.
     */
    post("${ApiRoutes.PROJECTS}/{id}/sprints/active") {
        val (project, _) = call.sprintScope(deps) ?: return@post
        val body = call.receiveOrNull<SprintActivation>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed activation.")
            return@post
        }
        deps.runSprintWrite(call) {
            deps.sprintRepository.activate(project.id, body.sprintId)
            call.respond(deps.buildBoard(project, call.caller(deps)))
        }
    }

    /** Finish a sprint and roll its unfinished work forward. See SprintRepository.complete. */
    post("${ApiRoutes.PROJECTS}/{id}/sprints/{sid}/complete") {
        val (project, sprintId) = call.sprintScope(deps, needsSprintId = true) ?: return@post
        val body = call.receiveOrNull<SprintCompletion>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed completion.")
            return@post
        }
        deps.runSprintWrite(call) {
            deps.sprintRepository.complete(project.id, sprintId!!, body.moveUnfinishedTo)
            call.respond(deps.buildBoard(project, call.caller(deps)))
        }
    }

    /**
     * Un-finish a sprint. See SprintRepository.reopen for what it deliberately leaves
     * alone.
     *
     * No body, so nothing to malform: the sprint is in the path and there is exactly
     * one thing to do to it. The same maintainer gate as completing one — the two are
     * the same act on the same object, and a rung that could end a sprint but not
     * un-end it would be a rung that can only make the mistake.
     */
    post("${ApiRoutes.PROJECTS}/{id}/sprints/{sid}/reopen") {
        val (project, sprintId) = call.sprintScope(deps, needsSprintId = true) ?: return@post
        deps.runSprintWrite(call) {
            deps.sprintRepository.reopen(project.id, sprintId!!)
            call.respond(deps.buildBoard(project, call.caller(deps)))
        }
    }

    /**
     * Set exactly which issues are in a sprint — the planning dialog's save.
     *
     * `canEditIssue` rather than the project-wide rung the handlers above ask for,
     * and checked against *every* issue in the request rather than against the
     * project: editing is per issue in this codebase (authorship is one of the two
     * ways to yes), so a caller who may edit four of five named issues must be
     * refused rather than partly obeyed.
     *
     * Checked before anything is written, not per issue as the write goes, so a
     * refusal leaves the sprint exactly as it was. A half-applied plan is worse
     * than a rejected one: the dialog would show a set nobody chose and the user
     * would have no way to tell which half took.
     */
    post("${ApiRoutes.PROJECTS}/{id}/sprints/{sid}/issues") {
        val user = call.caller(deps)
        val projectId = call.longParam("id") ?: run {
            call.respond(HttpStatusCode.BadRequest, "Bad project id.")
            return@post
        }
        val project = call.readableProject(deps, user, projectId) ?: return@post
        val sprintId = call.longParam("sid") ?: run {
            call.respond(HttpStatusCode.BadRequest, "Bad sprint id.")
            return@post
        }
        val body = call.receiveOrNull<SprintMembership>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed membership.")
            return@post
        }

        // The issues currently in the sprint matter as much as the ones being put
        // there: removing an issue is editing it too, so a caller who may not edit
        // what is already in the sprint may not empty it either.
        val current = deps.issues.forProject(project.id).filter { it.sprintId == sprintId }
        val incoming = body.issueIds.distinct().mapNotNull { deps.issues.findById(it) }
        for (issue in (current + incoming).distinctBy { it.id }) {
            if (!deps.access.canEditIssue(user, issue)) {
                call.respond(HttpStatusCode.Forbidden, "You cannot schedule all of those issues.")
                return@post
            }
        }

        deps.runSprintWrite(call) {
            deps.sprintRepository.setMembership(project.id, sprintId, body.issueIds)
            call.respond(deps.buildBoard(project, call.caller(deps)))
        }
    }

    /**
     * Schedule one issue, from the card menu.
     *
     * The editor writes the same column through the `PUT`, staged with everything
     * else until Save. Two gestures, one column — the same arrangement `status`
     * has with `/issues/{id}/status`, and for the same reason: a menu has no Save
     * button to stage against.
     */
    post("/api/issues/{id}/sprint") {
        val user = call.caller(deps)
        val issue = call.readableIssue(deps, user) ?: return@post
        if (!deps.access.canEditIssue(user, issue)) {
            call.respond(HttpStatusCode.Forbidden, "You cannot schedule this issue.")
            return@post
        }
        val body = call.receiveOrNull<IssueSprintUpdate>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed sprint.")
            return@post
        }
        deps.runSprintWrite(call) {
            deps.sprintRepository.setIssueSprint(issue, body.sprintId)
            // Scheduling is an update to the issue, and it goes through the store
            // directly rather than issueRepository.save — so the notification is
            // fired here, exactly as the drag and assign routes do. See
            // BoardDependencies.notifications.
            user?.let { deps.notifications.issueUpdated(issue, it.id, "scheduled") }
            val saved = deps.issues.findById(issue.id) ?: run {
                call.respond(HttpStatusCode.NotFound, "That issue no longer exists.")
                return@runSprintWrite
            }
            call.respond(deps.buildIssueDetail(saved, user))
        }
    }
}

/**
 * Resolve the project — and optionally the sprint id — for a sprint write only a
 * maintainer may make, or respond and return null.
 *
 * Readability first and the rung second, so an id the caller may not see answers 404
 * rather than 403: a 403 here would confirm a private project by that id exists,
 * which is the thing [readableProject] withholds.
 */
private suspend fun ApplicationCall.sprintScope(
    deps: BoardDependencies,
    needsSprintId: Boolean = false,
): Pair<ProjectRecord, Long?>? {
    val user = caller(deps)
    val projectId = longParam("id") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad project id.")
        return null
    }
    val project = readableProject(deps, user, projectId) ?: return null
    // A maintainer's, not an administrator's, since LNL-191: planning the next two
    // weeks is work on a board somebody already edits every issue on. See
    // ProjectRole.MAINTAINER and AccessControl.canEditVocabulary, which draws the
    // same line for the sprint rows themselves.
    if (!deps.access.canEditVocabulary(user, project.id, VocabularyKind.SPRINT)) {
        respond(HttpStatusCode.Forbidden, "You cannot configure this project's sprints.")
        return null
    }
    if (!needsSprintId) return project to null
    val sprintId = longParam("sid") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad sprint id.")
        return null
    }
    return project to sprintId
}

/**
 * Turn a [SprintRefusal] into the 400 it is, and let everything else through.
 *
 * The same shape as `runVocabularyWrite`, with one branch rather than two: a
 * sprint write has no 409 case. There is no name to collide over here — the only
 * name a sprint has is written through the vocabulary routes, which do have one.
 */
private suspend inline fun BoardDependencies.runSprintWrite(
    call: ApplicationCall,
    write: () -> Unit,
) {
    try {
        write()
    } catch (refusal: SprintRefusal) {
        call.respond(HttpStatusCode.BadRequest, refusal.userMessage)
    }
}
