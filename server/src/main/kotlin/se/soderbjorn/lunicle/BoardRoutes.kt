/**
 * Every route that touches a project, an issue, a comment or an attachment.
 *
 * The shape to hold in mind while reading: **parse, AUTHORIZE, respond.** Every
 * handler below resolves the caller from the session cookie, asks
 * [AccessControl] the question, and only then reaches a repository or a store.
 * Nothing here takes the request body's word for who is asking or what they may
 * do — see AccessControl's preamble for why that is the whole ballgame.
 *
 * The other half, easier to forget: reads are **filtered**, not just writes
 * refused. Refusing a write is obvious. Quietly shipping a private project's
 * name to a signed-out visitor and trusting the bundle to hide it is the half
 * that gets forgotten, so every read below narrows what it returns rather than
 * assuming the client will.
 *
 * @see AccessControl
 * @see ProjectRepository
 */
package se.soderbjorn.lunicle

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.slf4j.LoggerFactory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AttachmentRef
import se.soderbjorn.lunicle.clientserver.BoardState
import se.soderbjorn.lunicle.clientserver.CommentDraft
import se.soderbjorn.lunicle.clientserver.CommentUpdate
import se.soderbjorn.lunicle.clientserver.CommentView
import se.soderbjorn.lunicle.clientserver.IssueDetail
import se.soderbjorn.lunicle.clientserver.IssueDraft
import se.soderbjorn.lunicle.clientserver.IssueOrderUpdate
import se.soderbjorn.lunicle.clientserver.IssueSummary
import se.soderbjorn.lunicle.clientserver.IssueUpdate
import se.soderbjorn.lunicle.clientserver.ProjectListState
import se.soderbjorn.lunicle.clientserver.ProjectPermissionsView
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.ProjectUpdate
import se.soderbjorn.lunicle.clientserver.StatusItem
import se.soderbjorn.lunicle.clientserver.StatusUpdate
import se.soderbjorn.lunicle.clientserver.VocabularyItem
import se.soderbjorn.lunicle.clientserver.isInlineImageType
import se.soderbjorn.lunicle.clientserver.tooLargeMessage

private val logger = LoggerFactory.getLogger("BoardRoutes")

/**
 * How long an issue's title may be.
 *
 * Bounded because it is rendered on a card: a caller sending a megabyte of title
 * would not break the server, it would break the board. The description is
 * deliberately unbounded by comparison — that field is meant to hold a lot.
 */
private const val MAX_TITLE_LENGTH = 300

/**
 * `X-Content-Type-Options`, spelled out because `HttpHeaders` does not have it.
 *
 * Ktor 3.1's `HttpHeaders` covers the RFC 7231 set and a handful of `X-Forwarded`
 * ones, and this is neither — it is a browser behaviour that became a de-facto
 * standard. A literal, once, with a name, rather than the string inline at the
 * one place it is used and unfindable by anyone grepping for it.
 */
private const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"

/** The bundle everything below needs. Passed as one object so the route signatures stay readable. */
class BoardDependencies(
    val access: AccessControl,
    val projects: ProjectStore,
    val projectRepository: ProjectRepository,
    /**
     * The grants table, for the settings dialog's privileges section.
     *
     * Here as well as inside [access] on purpose, and it is worth being explicit
     * about why that is not a duplicate: AccessControl holds it to answer
     * *permission* questions from the session, and never exposes it. This one
     * answers the *administrative* question — "who holds what in this project", and
     * "make it so" — which is a different question with a different audience. See
     * RoleStore.grantsForProject.
     */
    val roles: RoleStore,
    /** The rules for changing what is in a project's vocabularies. */
    val vocabularies: VocabularyRepository,
    val labels: LabelStore,
    val components: ComponentStore,
    val statuses: StatusStore,
    val priorities: PriorityStore,
    val resolutions: ResolutionStore,
    val issues: IssueStore,
    val issueRepository: IssueRepository,
    val comments: CommentStore,
    val attachments: AttachmentStore,
    val attachmentRepository: AttachmentRepository,
    /**
     * Live upload tickets, for the one attachment route that has no session.
     *
     * In memory and therefore not a store like the rest of these — see
     * [AttachmentTicketStore] for why that is safe here and exactly when it
     * would stop being.
     */
    val attachmentTickets: AttachmentTicketStore,
    val sessions: SessionStore,
    val users: UserStore,
    // Read on every request, to turn the session cookie into an EFFECTIVE user.
    // See Impersonations.
    val impersonations: Impersonations,
)

/**
 * The caller, or null. Never throws — "signed out" is a legitimate answer here.
 *
 * Returns the **effective** user: the impersonated one when an admin is
 * impersonating, and otherwise simply the session's own user. Every route below
 * calls this and none of them mention impersonation, which is the design — it is
 * resolved once, here, so a route cannot forget. See [resolveCaller].
 */
internal suspend fun ApplicationCall.caller(deps: BoardDependencies): UserRecord? =
    resolveCaller(deps.sessions, deps.users, deps.impersonations).effective

/**
 * Resolve who a request is acting as, and who it really is.
 *
 * The one place that turns a cookie into an identity, shared by these routes and
 * AuthRoutes so there is exactly one implementation of the rule.
 *
 * The `isAdmin` re-check is the important line. An admin who starts impersonating
 * and is then demoted — by another admin, or by a hand-edited database — must not
 * keep acting as somebody else, so the *stored* impersonation is only honoured
 * while the real user is still an admin at this moment. Anything else would mean
 * the admin bit could be taken away without taking away what it granted, which is
 * exactly the shape of a privilege-escalation bug.
 *
 * A stale impersonation is dropped rather than merely ignored: if the target's
 * account is gone, or the impersonator is no longer an admin, the entry is
 * removed so the next request does not have to work it out again.
 */
internal suspend fun ApplicationCall.resolveCaller(
    sessions: SessionStore,
    users: UserStore,
    impersonations: Impersonations,
): Caller {
    val sessionId = request.cookies[SESSION_COOKIE] ?: return Caller(null, null)
    val real = sessions.lookup(sessionId) ?: return Caller(null, null)

    val targetId = impersonations.effectiveUserId(sessionId)
        ?: return Caller(effective = real, real = real)

    if (!real.isAdmin) {
        // Demoted while impersonating. Drop it and be themselves.
        impersonations.stop(sessionId)
        return Caller(effective = real, real = real)
    }
    val target = users.findById(targetId) ?: run {
        // The impersonated account was deleted out from under them.
        impersonations.stop(sessionId)
        return Caller(effective = real, real = real)
    }
    return Caller(effective = target, real = real, isImpersonating = true)
}

/** A path segment as a Long, or null if it isn't one. */
internal fun ApplicationCall.longParam(name: String): Long? = parameters[name]?.toLongOrNull()

/**
 * Resolve a project the caller may *read*, or respond and return null.
 *
 * 404 for both "no such project" and "you may not see it", deliberately: a 403
 * would confirm that a private project by that id exists, which is the thing
 * being withheld.
 */
private suspend fun ApplicationCall.readableProject(
    deps: BoardDependencies,
    user: UserRecord?,
    projectId: Long,
): ProjectRecord? {
    val project = deps.projects.findById(projectId)
    if (project == null || !deps.access.canReadProject(user, project)) {
        respond(HttpStatusCode.NotFound, "No such project.")
        return null
    }
    return project
}

/**
 * Names for the accounts among [authors], resolved once per response rather than
 * per row.
 *
 * Only accounts appear here. An [Author.External] carries its name already —
 * there is nothing to look it up in, which is the whole point of the column —
 * and [Author.Nobody] has none. See [displayName], which is the other half.
 */
private suspend fun BoardDependencies.authorNames(authors: Collection<Author>): Map<Long, String> =
    // distinct() first: a board where one person filed forty issues would
    // otherwise be forty identical lookups. Small, but this is the one query in
    // the response that scales with the number of cards.
    authors.mapNotNull { it.accountId }.distinct()
        .mapNotNull { id -> users.findById(id)?.let { id to it.resolvedName } }.toMap()


/**
 * The resolution rule, in one place: is [resolutionId] the right answer for
 * [statusId]?
 *
 * Both halves matter, and both are enforced rather than trusted:
 *
 *  - A status with `requires_resolution` demands one, and it must belong to this
 *    project. Without this, a client that simply omits the field closes an issue
 *    with no reason — and the field is optional on the wire, so "simply omits it"
 *    is the default a new client gets for free.
 *  - Any other status must NOT have one. Not because it would be dangerous, but
 *    because a stale resolution is a lie the board would repeat: an issue dragged
 *    out of Closed and back into In progress, still carrying "Will not fix",
 *    would group under it the moment anyone closed it again. So this returns the
 *    value to *store*, and for an ordinary status that value is null regardless of
 *    what was sent.
 *
 * Shared by the editor's PUT and the board's drag POST deliberately. Closing an
 * issue by dragging it is the common path, and a rule the editor enforced and the
 * board did not would be a rule with a hole exactly where it is used most.
 *
 * `internal` rather than private for the same reason, one transport further out:
 * the MCP tools close and move issues too, and a fourth caller reimplementing
 * this would be a fourth chance to forget that a reopened issue must drop its
 * resolution. See McpTools.
 *
 * @return the resolution id to store, or a [ResolutionRefusal] naming what is
 *   wrong. Never a bare null-or-not: "no resolution" is a legitimate result, so
 *   it cannot also mean "refused".
 */
internal suspend fun BoardDependencies.resolveResolution(
    projectId: Long,
    statusId: Long,
    resolutionId: Long?,
): Result<Long?> {
    val status = statuses.forProject(projectId).firstOrNull { it.id == statusId }
        ?: return Result.failure(ResolutionRefusal("That status does not belong to this project."))

    if (!status.requiresResolution) {
        // Silently dropped rather than refused. A client sending one here is not
        // attacking anything — it is the issue dialog, which keeps the old value
        // in its state while you flip the status back and forth. Refusing would
        // make a legitimate edit fail for a field the user cannot even see.
        return Result.success(null)
    }

    if (resolutionId == null) {
        return Result.failure(ResolutionRefusal("Closing an issue needs a resolution."))
    }
    val valid = resolutions.forProject(projectId).map { it.id }.toSet()
    if (resolutionId !in valid) {
        return Result.failure(ResolutionRefusal("That resolution does not belong to this project."))
    }
    return Result.success(resolutionId)
}

/** A refused resolution, carrying the sentence the client should show. */
internal class ResolutionRefusal(override val message: String) : Exception(message)

/** Mount every board route. Called by [Application.module]. */
fun Route.boardRoutes(deps: BoardDependencies) {
    projectRoutes(deps)
    // The admin's half of a project: its vocabularies, and who may do what in it.
    // Its own file because every route in it is admin-only and enforces that
    // through one gate — see ProjectSettingsRoutes' preamble — which is a property
    // worth being able to read off a file rather than infer from six handlers
    // scattered among forty.
    projectSettingsRoutes(deps)
    issueRoutes(deps)
    commentRoutes(deps)
    attachmentRoutes(deps)
}

// ── Projects ─────────────────────────────────────────────────────────────────

private fun Route.projectRoutes(deps: BoardDependencies) {
    /**
     * The picker's list.
     *
     * Filtered, not decorated: a signed-out visitor's response *contains* public
     * projects only. Sending everything and letting the bundle hide the private
     * ones would leak every project's name and prefix to anyone with DevTools.
     */
    get(ApiRoutes.PROJECTS) {
        val user = call.caller(deps)
        val visible = deps.projects.selectAll().filter { deps.access.canReadProject(user, it) }
        call.respond(
            ProjectListState(
                projects = visible.map { it.toSummary() },
                canCreateProject = deps.access.canMutateProjects(user),
            ),
        )
    }

    post(ApiRoutes.PROJECTS) {
        val user = call.caller(deps)
        if (!deps.access.canMutateProjects(user)) {
            call.respond(HttpStatusCode.Forbidden, "Only an admin can create a project.")
            return@post
        }
        val body = call.receiveOrNull<ProjectUpdate>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed project.")
            return@post
        }
        try {
            val created = deps.projectRepository.create(body.name, body.namePrefix, body.isPublic)
            logger.info("Project created: ${created.name} (${created.namePrefix}) by user ${user?.id}")
            call.respond(created.toSummary())
        } catch (conflict: ProjectConflict) {
            // 409 with the repository's own sentence: it knows *which* project
            // holds the name, and the dialog shows this verbatim.
            call.respond(HttpStatusCode.Conflict, conflict.userMessage)
        }
    }

    put("${ApiRoutes.PROJECTS}/{id}") {
        val user = call.caller(deps)
        if (!deps.access.canMutateProjects(user)) {
            call.respond(HttpStatusCode.Forbidden, "Only an admin can change a project.")
            return@put
        }
        val id = call.longParam("id") ?: run {
            call.respond(HttpStatusCode.BadRequest, "Bad project id.")
            return@put
        }
        val body = call.receiveOrNull<ProjectUpdate>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed project.")
            return@put
        }
        if (deps.projects.findById(id) == null) {
            call.respond(HttpStatusCode.NotFound, "No such project.")
            return@put
        }
        try {
            call.respond(deps.projectRepository.update(id, body.name, body.namePrefix, body.isPublic).toSummary())
        } catch (conflict: ProjectConflict) {
            call.respond(HttpStatusCode.Conflict, conflict.userMessage)
        }
    }

    delete("${ApiRoutes.PROJECTS}/{id}") {
        val user = call.caller(deps)
        if (!deps.access.canMutateProjects(user)) {
            call.respond(HttpStatusCode.Forbidden, "Only an admin can delete a project.")
            return@delete
        }
        val id = call.longParam("id") ?: run {
            call.respond(HttpStatusCode.BadRequest, "Bad project id.")
            return@delete
        }
        val project = deps.projects.findById(id) ?: run {
            call.respond(HttpStatusCode.NotFound, "No such project.")
            return@delete
        }
        deps.projectRepository.delete(id)
        logger.info("Project deleted: ${project.name} by user ${user?.id}")
        call.respond(HttpStatusCode.NoContent)
    }

    get("${ApiRoutes.PROJECTS}/{id}/board") {
        val user = call.caller(deps)
        val id = call.longParam("id") ?: run {
            call.respond(HttpStatusCode.BadRequest, "Bad project id.")
            return@get
        }
        val project = call.readableProject(deps, user, id) ?: return@get
        call.respond(deps.buildBoard(project, user))
    }

    /**
     * The embed's `?project=<name>`.
     *
     * A name that resolves to a project this caller may not read answers exactly
     * as if the name did not exist — see [readableProject]. That is the rule
     * that keeps `?project=secret-thing` from being an existence oracle for
     * anyone who can guess a name.
     */
    get(ApiRoutes.BOARD_BY_NAME) {
        val user = call.caller(deps)
        val name = call.request.queryParameters["name"]?.trim()
        if (name.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Name is required.")
            return@get
        }
        val project = deps.projects.findByName(name)
        if (project == null || !deps.access.canReadProject(user, project)) {
            call.respond(HttpStatusCode.NotFound, "No such project.")
            return@get
        }
        call.respond(deps.buildBoard(project, user))
    }
}

/**
 * Assemble one board.
 *
 * Every card's `canEdit` is computed here rather than sent as a project-wide
 * flag, because editing is per issue: authorship is one of the three ways to
 * yes. A single "you may edit things" boolean would either let the client drag
 * cards it will be refused on, or refuse ones it is allowed — and the client
 * must not be reimplementing that rule to find out.
 */
private suspend fun BoardDependencies.buildBoard(project: ProjectRecord, user: UserRecord?): BoardState {
    val issueRows = issues.forProject(project.id)
    // Two queries for the whole board rather than two per card. See
    // IssueStore.labelsForProject.
    val labelsByIssue = issues.labelsForProject(project.id)
    val componentsByIssue = issues.componentsForProject(project.id)
    val names = authorNames(issueRows.map { it.author })
    val permissions = access.permissionsFor(user, project.id)

    return BoardState(
        project = project.toSummary(),
        statuses = statuses.forProject(project.id)
            .map { StatusItem(it.id, it.name, it.position.toInt(), it.requiresResolution) },
        priorities = priorities.forProject(project.id).map { StatusItem(it.id, it.name, it.position.toInt()) },
        resolutions = resolutions.forProject(project.id).map { StatusItem(it.id, it.name, it.position.toInt()) },
        labels = labels.forProject(project.id).map { VocabularyItem(it.id, it.name) },
        components = components.forProject(project.id).map { VocabularyItem(it.id, it.name) },
        // Already ordered — priority first, then the group's own arrangement:
        // manual rank, then issue number. The order comes out of SQL (see
        // Issues.sq's `forProject`) and is preserved by this map, so nothing
        // downstream needs to sort — nor could it, since `sort_order` is not a
        // field on IssueSummary.
        issues = issueRows.map { issue ->
            IssueSummary(
                id = issue.id,
                number = issue.number,
                title = issue.title,
                statusId = issue.statusId,
                priorityId = issue.priorityId,
                resolutionId = issue.resolutionId,
                labelIds = labelsByIssue[issue.id].orEmpty(),
                componentIds = componentsByIssue[issue.id].orEmpty(),
                authorName = issue.author.displayName(names),
                agentName = issue.agentName,
                createdAt = issue.createdAt,
                updatedAt = issue.updatedAt,
                canEdit = access.canEditIssue(user, issue),
            )
        },
        permissions = permissions.toView(),
    )
}

// ── Issues ───────────────────────────────────────────────────────────────────

private fun Route.issueRoutes(deps: BoardDependencies) {
    post("${ApiRoutes.PROJECTS}/{id}/issues") {
        val user = call.caller(deps)
        val projectId = call.longParam("id") ?: run {
            call.respond(HttpStatusCode.BadRequest, "Bad project id.")
            return@post
        }
        val project = call.readableProject(deps, user, projectId) ?: return@post
        if (!deps.access.canCreateIssue(user, project.id)) {
            call.respond(HttpStatusCode.Forbidden, "You cannot create issues in this project.")
            return@post
        }
        val (id, number) = deps.issueRepository.createDraft(project.id, user.asAuthor())
        call.respond(IssueDraft(id, number))
    }

    get("/api/issues/{id}") {
        val user = call.caller(deps)
        val issue = call.readableIssue(deps, user) ?: return@get
        call.respond(deps.buildIssueDetail(issue, user))
    }

    put("/api/issues/{id}") {
        val user = call.caller(deps)
        val issue = call.readableIssue(deps, user) ?: return@put
        if (!deps.access.canEditIssue(user, issue)) {
            call.respond(HttpStatusCode.Forbidden, "You cannot edit this issue.")
            return@put
        }
        val body = call.receiveOrNull<IssueUpdate>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed issue.")
            return@put
        }
        val title = body.title.trim()
        if (title.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, "An issue needs a title.")
            return@put
        }
        if (title.length > MAX_TITLE_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, "That title is too long.")
            return@put
        }
        // Every id in the body is checked against *this issue's project* before
        // it is written. The composite foreign keys would refuse a foreign
        // label anyway — that is the point of them — but a constraint violation
        // surfaces as a 500, and "you sent a label from another project" is a
        // 400 that says what happened.
        val validStatuses = deps.statuses.forProject(issue.projectId).map { it.id }.toSet()
        if (body.statusId !in validStatuses) {
            call.respond(HttpStatusCode.BadRequest, "That status does not belong to this project.")
            return@put
        }
        val validPriorities = deps.priorities.forProject(issue.projectId).map { it.id }.toSet()
        if (body.priorityId !in validPriorities) {
            call.respond(HttpStatusCode.BadRequest, "That priority does not belong to this project.")
            return@put
        }
        val resolution = deps.resolveResolution(issue.projectId, body.statusId, body.resolutionId)
            .getOrElse { failure ->
                call.respond(HttpStatusCode.BadRequest, failure.message ?: "Bad resolution.")
                return@put
            }
        val validLabels = deps.labels.forProject(issue.projectId).map { it.id }.toSet()
        val validComponents = deps.components.forProject(issue.projectId).map { it.id }.toSet()
        if (!validLabels.containsAll(body.labelIds) || !validComponents.containsAll(body.componentIds)) {
            call.respond(HttpStatusCode.BadRequest, "Those labels or components do not belong to this project.")
            return@put
        }

        deps.issueRepository.save(
            issue = issue,
            title = title,
            description = body.description,
            statusId = body.statusId,
            priorityId = body.priorityId,
            resolutionId = resolution,
            labelIds = body.labelIds,
            componentIds = body.componentIds,
        )
        val saved = deps.issues.findById(issue.id) ?: run {
            call.respond(HttpStatusCode.NotFound, "That issue no longer exists.")
            return@put
        }
        call.respond(deps.buildIssueDetail(saved, user))
    }

    /**
     * Drag-and-drop.
     *
     * Not a special case, and must not become one: this is a `status_id` write,
     * so it goes through `canEditIssue` — the same function the editor uses. A
     * "just move it" endpoint that skipped the check because dragging feels
     * lighter than editing would let anyone drag anything to Closed.
     */
    post("/api/issues/{id}/status") {
        val user = call.caller(deps)
        val issue = call.readableIssue(deps, user) ?: return@post
        if (!deps.access.canEditIssue(user, issue)) {
            call.respond(HttpStatusCode.Forbidden, "You cannot move this issue.")
            return@post
        }
        val body = call.receiveOrNull<StatusUpdate>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed status.")
            return@post
        }
        // resolveResolution checks the status belongs to this project too, so
        // there is no separate validStatuses check here — one lookup, one answer.
        val resolution = deps.resolveResolution(issue.projectId, body.statusId, body.resolutionId)
            .getOrElse { failure ->
                call.respond(HttpStatusCode.BadRequest, failure.message ?: "Bad resolution.")
                return@post
            }
        deps.issues.setStatus(issue.id, body.statusId, resolution)
        call.respond(HttpStatusCode.NoContent)
    }

    /**
     * Rank a board group, in the order the user dragged it into.
     *
     * `canEditIssue` on the dragged issue, like every other write. Reordering
     * feels lighter than editing and is not: it changes what everyone else sees
     * when they open the board.
     *
     * The group-integrity check is the interesting one. The body is a list of
     * ids, and nothing about a list says "these are one group" — so this proves
     * it rather than trusting it: every id must be a published issue in this
     * project, sharing this issue's status, and sharing its priority (or its
     * resolution, in a closing column). Without that, a caller could rank issues
     * against cards they are never displayed beside, and the ranks would be
     * gibberish that only shows up as cards in an order nobody chose.
     */
    post("/api/issues/{id}/order") {
        val user = call.caller(deps)
        val issue = call.readableIssue(deps, user) ?: return@post
        if (!deps.access.canEditIssue(user, issue)) {
            call.respond(HttpStatusCode.Forbidden, "You cannot reorder this issue.")
            return@post
        }
        val body = call.receiveOrNull<IssueOrderUpdate>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed order.")
            return@post
        }
        if (body.issueIds.isEmpty() || issue.id !in body.issueIds) {
            // The dragged issue must be in its own group. A list without it is a
            // caller ranking a group it is not part of, which the permission check
            // above would then have authorised against the wrong issue entirely.
            call.respond(HttpStatusCode.BadRequest, "That order does not include this issue.")
            return@post
        }
        if (body.issueIds.distinct().size != body.issueIds.size) {
            // A duplicate would take two ranks and leave a gap. Cheap to check,
            // and the alternative is a group that renders in an order the sender
            // did not send.
            call.respond(HttpStatusCode.BadRequest, "That order repeats an issue.")
            return@post
        }

        val siblings = deps.issues.forProject(issue.projectId).associateBy { it.id }
        val allInGroup = body.issueIds.all { id ->
            val other = siblings[id]
            other != null &&
                other.statusId == issue.statusId &&
                // In a closing column the group is the resolution; everywhere else
                // it is the priority. Exactly the split the board renders and the
                // query sorts by — see BoardColumn.groups and Issues.sq.
                if (issue.resolutionId != null) {
                    other.resolutionId == issue.resolutionId
                } else {
                    other.resolutionId == null && other.priorityId == issue.priorityId
                }
        }
        if (!allInGroup) {
            call.respond(HttpStatusCode.BadRequest, "Those issues are not all in one group.")
            return@post
        }

        deps.issues.setGroupOrder(body.issueIds)
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/api/issues/{id}") {
        val user = call.caller(deps)
        val issue = call.readableIssue(deps, user) ?: return@delete
        if (!deps.access.canDeleteIssue(user, issue)) {
            call.respond(HttpStatusCode.Forbidden, "You cannot delete this issue.")
            return@delete
        }
        deps.issueRepository.delete(issue)
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * Resolve an issue whose project this caller may read, or respond and return
 * null.
 *
 * Every issue route starts here: an issue is only as readable as its project,
 * and there is no route that reaches one without asking this question first.
 */
private suspend fun ApplicationCall.readableIssue(
    deps: BoardDependencies,
    user: UserRecord?,
): IssueRecord? {
    val id = longParam("id") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad issue id.")
        return null
    }
    val issue = deps.issues.findById(id)
    if (issue == null) {
        respond(HttpStatusCode.NotFound, "No such issue.")
        return null
    }
    val project = deps.projects.findById(issue.projectId)
    if (project == null || !deps.access.canReadProject(user, project)) {
        respond(HttpStatusCode.NotFound, "No such issue.")
        return null
    }
    return issue
}

private suspend fun BoardDependencies.buildIssueDetail(issue: IssueRecord, user: UserRecord?): IssueDetail {
    val commentRows = comments.forIssue(issue.id)
    val names = authorNames(commentRows.map { it.author } + issue.author)
    return IssueDetail(
        id = issue.id,
        projectId = issue.projectId,
        number = issue.number,
        title = issue.title,
        description = issue.description,
        statusId = issue.statusId,
        priorityId = issue.priorityId,
        resolutionId = issue.resolutionId,
        isDraft = issue.isDraft,
        labelIds = issues.labelsFor(issue.id),
        componentIds = issues.componentsFor(issue.id),
        authorName = issue.author.displayName(names),
        agentName = issue.agentName,
        createdAt = issue.createdAt,
        updatedAt = issue.updatedAt,
        comments = commentRows.map { comment ->
            CommentView(
                id = comment.id,
                body = comment.body,
                authorName = comment.author.displayName(names),
                agentName = comment.agentName,
                createdAt = comment.createdAt,
                canEdit = access.canEditComment(user, comment),
            )
        },
        canEdit = access.canEditIssue(user, issue),
        canDelete = access.canDeleteIssue(user, issue),
        canComment = access.canComment(user, issue.projectId),
    )
}

// ── Comments ─────────────────────────────────────────────────────────────────

private fun Route.commentRoutes(deps: BoardDependencies) {
    post("/api/issues/{id}/comments") {
        val user = call.caller(deps)
        val issue = call.readableIssue(deps, user) ?: return@post
        if (!deps.access.canComment(user, issue.projectId)) {
            call.respond(HttpStatusCode.Forbidden, "You cannot comment on this project's issues.")
            return@post
        }
        call.respond(CommentDraft(deps.issueRepository.createCommentDraft(issue.id, user.asAuthor())))
    }

    put("/api/comments/{id}") {
        val user = call.caller(deps)
        val comment = call.editableComment(deps, user) ?: return@put
        val body = call.receiveOrNull<CommentUpdate>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed comment.")
            return@put
        }
        if (body.body.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, "A comment needs something in it.")
            return@put
        }
        deps.issueRepository.saveComment(comment.id, body.body)
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/api/comments/{id}") {
        val user = call.caller(deps)
        val comment = call.editableComment(deps, user) ?: return@delete
        deps.issueRepository.deleteComment(comment)
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * Resolve a comment this caller may change, or respond and return null.
 *
 * Two gates, not one: the issue must be readable (a 404 otherwise, so a comment
 * id cannot be used to probe a private project), and then the comment must be
 * this caller's or the caller an admin. `comment_on_issue` grants writing your
 * own comments, never editing someone else's words — see
 * [AccessControl.canEditComment].
 */
private suspend fun ApplicationCall.editableComment(
    deps: BoardDependencies,
    user: UserRecord?,
): CommentRecord? {
    val id = longParam("id") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad comment id.")
        return null
    }
    val comment = deps.comments.findById(id)
    if (comment == null) {
        respond(HttpStatusCode.NotFound, "No such comment.")
        return null
    }
    val issue = deps.issues.findById(comment.issueId)
    val project = issue?.let { deps.projects.findById(it.projectId) }
    if (project == null || !deps.access.canReadProject(user, project)) {
        respond(HttpStatusCode.NotFound, "No such comment.")
        return null
    }
    if (!deps.access.canEditComment(user, comment)) {
        respond(HttpStatusCode.Forbidden, "That is not your comment.")
        return null
    }
    return comment
}

// ── Attachments ──────────────────────────────────────────────────────────────

private fun Route.attachmentRoutes(deps: BoardDependencies) {
    /**
     * Upload an image into an issue's description.
     *
     * Gated by `canEditIssue` like every other write. An upload endpoint that
     * only checked "are you signed in" would be an open file host with our name
     * on it.
     */
    post("/api/issues/{id}/attachments") {
        val user = call.caller(deps)
        val issue = call.readableIssue(deps, user) ?: return@post
        if (!deps.access.canEditIssue(user, issue)) {
            call.respond(HttpStatusCode.Forbidden, "You cannot attach files to this issue.")
            return@post
        }
        val upload = call.receiveUpload() ?: return@post
        try {
            val id = deps.attachmentRepository.storeForIssue(
                issueId = issue.id,
                filename = upload.filename,
                declaredMimeType = upload.mimeType,
                bytes = upload.bytes,
                author = user.asAuthor(),
            )
            call.respond(AttachmentRef(id))
        } catch (rejected: AttachmentRejected) {
            call.respond(HttpStatusCode.BadRequest, rejected.userMessage)
        }
    }

    post("/api/comments/{id}/attachments") {
        val user = call.caller(deps)
        val comment = call.editableComment(deps, user) ?: return@post
        val upload = call.receiveUpload() ?: return@post
        try {
            val id = deps.attachmentRepository.storeForComment(
                commentId = comment.id,
                filename = upload.filename,
                declaredMimeType = upload.mimeType,
                bytes = upload.bytes,
                author = user.asAuthor(),
            )
            call.respond(AttachmentRef(id))
        } catch (rejected: AttachmentRejected) {
            call.respond(HttpStatusCode.BadRequest, rejected.userMessage)
        }
    }

    /**
     * Redeem an upload ticket: the one attachment route with no session.
     *
     * The token IS the authorisation, and it is the whole of it. Everything this
     * route would otherwise have to decide — may you, which issue, what is it
     * called, whose is it, when does it claim to be from — was decided at mint,
     * under [AccessControl], and is read back out of the ticket rather than out
     * of the request. See [AttachmentTicketStore].
     *
     * So there is deliberately no `caller(deps)` here, and its absence is the
     * design rather than an omission: a signed-in user redeeming a ticket gets
     * the ticket's author, not their own. The alternative — letting the request
     * contribute *anything* to attribution — is how an admin-only capability
     * stops being admin-only, because the mint check would no longer be the last
     * word.
     *
     * 404 rather than 401/403 for a bad token, and one answer for every kind of
     * bad. See [AttachmentTicketStore.redeem].
     */
    post("/api/attachments/upload/{token}") {
        val token = call.parameters["token"]
        val ticket = token?.let { deps.attachmentTickets.redeem(it) }
        if (ticket == null) {
            call.respond(
                HttpStatusCode.NotFound,
                "That upload ticket is not valid. Tickets are single-use and expire after a few " +
                    "minutes — mint a new one and upload again.",
            )
            return@post
        }
        val upload = call.receiveUpload(knownFilename = ticket.filename) ?: return@post
        try {
            val id = when (val target = ticket.target) {
                is AttachmentTarget.Issue -> deps.attachmentRepository.storeForIssue(
                    issueId = target.issueId,
                    filename = upload.filename,
                    declaredMimeType = upload.mimeType,
                    bytes = upload.bytes,
                    author = ticket.author,
                    createdAt = ticket.createdAt,
                )

                is AttachmentTarget.Comment -> deps.attachmentRepository.storeForComment(
                    commentId = target.commentId,
                    filename = upload.filename,
                    declaredMimeType = upload.mimeType,
                    bytes = upload.bytes,
                    author = ticket.author,
                    createdAt = ticket.createdAt,
                )
            }
            call.respond(
                TicketedUpload(
                    attachmentId = id,
                    url = ApiRoutes.attachment(id),
                    rendersInline = isInlineImageType(upload.mimeType),
                ),
            )
        } catch (rejected: AttachmentRejected) {
            // The ticket is already spent, and stays spent. Re-admitting it on a
            // rejection would make "single use" mean "one success", and a caller
            // could sit on a ticket retrying variations of a file until one got
            // past validate.
            call.respond(HttpStatusCode.BadRequest, rejected.userMessage)
        }
    }

    /**
     * Serve the bytes.
     *
     * This URL appears inside rendered markdown, so it is trivially shareable
     * and will end up pasted places. It resolves the owning issue and runs
     * `canReadProject` before streaming a byte. The `storage_key` being random
     * and never appearing in a URL means guessing one buys nothing either — but
     * this check is what makes that a second line of defence rather than the
     * only one.
     */
    get("/api/attachments/{id}") {
        val user = call.caller(deps)
        val id = call.longParam("id") ?: run {
            call.respond(HttpStatusCode.BadRequest, "Bad attachment id.")
            return@get
        }
        val record = deps.attachments.findById(id) ?: run {
            call.respond(HttpStatusCode.NotFound, "No such attachment.")
            return@get
        }
        // An attachment hangs off an issue *or* a comment, and a comment reaches
        // its project through its issue. Either way the question is the same one.
        val issueId = record.issueId ?: record.commentId?.let { deps.comments.findById(it)?.issueId }
        val issue = issueId?.let { deps.issues.findById(it) }
        val project = issue?.let { deps.projects.findById(it.projectId) }
        if (project == null || !deps.access.canReadProject(user, project)) {
            call.respond(HttpStatusCode.NotFound, "No such attachment.")
            return@get
        }

        val file = deps.attachmentRepository.fileFor(record.storageKey)
        if (!file.isFile) {
            // The row exists and the file does not: the write half-failed, or a
            // sweep was wrong. Worth a warning — it is the one failure mode the
            // file-not-BLOB decision accepts, so a rash of these is the signal
            // that the trade went bad.
            logger.warn("Attachment ${record.id} has no file at ${record.storageKey}")
            call.respond(HttpStatusCode.NotFound, "That attachment is missing.")
            return@get
        }
        // ── The most dangerous four lines in the server ──────────────────────
        //
        // Any file can be uploaded now, so this route can be pointed at bytes
        // that are a *document* — an .html, an .svg (SVG carries <script>) — and
        // it serves them from lunicle.lunamux.dev, an origin holding
        // `lunicle_session`. Served inline, those bytes are a page on our own
        // origin, running with the reader's cookie. That is stored XSS, reachable
        // by anyone who can attach a file, through a URL that is deliberately
        // shareable because it appears inside rendered markdown.
        //
        // `Content-Disposition: attachment` is what stands between those two
        // facts. A browser given it saves the file and never parses it, whatever
        // the Content-Type says. So the branch is: the small set of formats that
        // cannot execute — bitmaps, and only bitmaps; see INLINE_IMAGE_MIME_TYPES
        // for why image/svg+xml is not among them — are served inline, because
        // the editor puts them in an <img> and an <img> pointing at a download is
        // a broken image. **Everything else is a download.** There is no third
        // case and there must not be one.
        val isInline = isInlineImageType(record.mimeType)
        val disposition = if (isInline) ContentDisposition.Inline else ContentDisposition.Attachment
        call.response.header(
            HttpHeaders.ContentDisposition,
            // withParameter, never string concatenation. The filename is the one
            // part of this header a user chose, and a quote in it would close the
            // parameter early while a newline would start a header of the
            // attacker's own. Ktor quotes and escapes it here; AttachmentRepository
            // also strips both on the way in, which makes this the second of two
            // rather than the only one.
            disposition.withParameter(ContentDisposition.Parameters.FileName, record.filename).toString(),
        )
        // nosniff on every attachment response, inline or not.
        //
        // It closes the gap the branch above cannot: content sniffing is a
        // browser looking at the *bytes* and overruling the Content-Type it was
        // given. Without this, an .html uploaded as image/png is served as
        // image/png, sniffed as HTML, and executed — and the allow-list above,
        // which only ever reads the declared type, never notices. With it, the
        // declared type is the final word, and a lie about it can only make the
        // uploader's own file fail to open.
        call.response.header(X_CONTENT_TYPE_OPTIONS, "nosniff")

        // The stored type, explicitly — NOT respondFile(file), which infers the
        // type from the file's extension. Our storage keys deliberately have no
        // extension (they are random, so that nothing a user types reaches the
        // filesystem), so respondFile labels every image application/octet-stream
        // and the mime_type we went to the trouble of storing is never used.
        // Browsers sniff an <img> and mostly cope, which is exactly how that
        // survives review — and is exactly what the nosniff above now forbids.
        //
        // Parsed rather than trusted: the column is written through
        // AttachmentRepository's MIME_TYPE_SHAPE, so it cannot be a raw caller
        // string — but if a row ever holds something unparseable, octet-stream is
        // the inert answer, and by then the disposition is already `attachment`.
        val contentType = runCatching { ContentType.parse(record.mimeType) }
            .getOrElse { ContentType.Application.OctetStream }
        call.respond(LocalFileContent(file, contentType))
    }
}

/** An upload's three facts, once the request has been read. */
private class Upload(val filename: String, val mimeType: String, val bytes: ByteArray)

/**
 * What a ticketed upload answers with.
 *
 * Deliberately richer than [AttachmentRef], which the session routes still use.
 * A browser already knows what it sent and has `attachmentMarkdown` to spell the
 * result; an agent holding a ticket has neither, so this hands back the three
 * things it would otherwise have to guess at — where the file now lives, and
 * whether it draws inline or downloads.
 *
 * [rendersInline] is the important one, and it is here rather than left to the
 * agent because it is a *fact this server owns*: [INLINE_IMAGE_MIME_TYPES] is
 * the single list that decides both the `Content-Disposition` on the way back
 * out and whether an `<img>` is honest. An agent guessing from a file extension
 * would be a second copy of that list, in a language model, drifting.
 *
 * Not in :clientServer with the other wire types: nothing on the client reads
 * this. The audience is an agent, and the house rule for that module is "what
 * both sides must agree on", which this is not.
 */
@Serializable
private data class TicketedUpload(
    @SerialName("attachment_id") val attachmentId: Long,
    val url: String,
    @SerialName("renders_inline") val rendersInline: Boolean,
)

/**
 * Read an upload, or respond and return null.
 *
 * Raw bytes with the filename in a query parameter rather than a multipart form:
 * there is exactly one field here — the body *is* the file — and the
 * Content-Type header already says what it is. Multipart would add a boundary,
 * a part header and an encoder to express what those two already do.
 *
 * @param knownFilename the name when the request is not the thing that gets to
 *   say it — a ticketed upload, whose filename was fixed at mint. Null for the
 *   session routes, which read it from the query as they always have. This is
 *   not a default to prefer: a ticket holder naming their own file would be a
 *   caller editing a decision that was made under a permission check.
 */
private suspend fun ApplicationCall.receiveUpload(knownFilename: String? = null): Upload? {
    val filename = knownFilename ?: request.queryParameters["filename"]?.takeIf { it.isNotBlank() } ?: run {
        respond(HttpStatusCode.BadRequest, "A filename is required.")
        return null
    }
    // ── Refused before it is read, not after ────────────────────────────────
    //
    // AttachmentRepository.validate checks the size too, and it is the honest
    // check — it counts bytes that actually arrived. But it runs on a ByteArray,
    // which means `receive` has already pulled the entire body into the heap of
    // a free-trial-sized JVM. A 4 GB upload would therefore be refused by an
    // OutOfMemoryError, some minutes later, having taken the whole server with
    // it — the limit enforced as a crash.
    //
    // Content-Length is a *claim*, and a caller can lie or omit it. That is why
    // this does not replace the check downstream; it only means an honest 4 GB
    // upload is turned away in one round-trip instead of taking the process
    // down. A liar still meets validate, on bytes it has counted itself.
    //
    // A body with no Content-Length at all is refused rather than streamed. Our
    // client always sends one — the body is a ByteArray it already holds — so
    // the only callers this turns away are ones deliberately hiding their size,
    // and "tell me how big it is" is not a hardship to ask of them.
    val declaredLength = request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: run {
        respond(HttpStatusCode.LengthRequired, "An upload has to say how big it is (Content-Length).")
        return null
    }
    tooLargeMessage(declaredLength)?.let { message ->
        respond(HttpStatusCode.PayloadTooLarge, message)
        return null
    }
    val bytes = runCatching { receive<ByteArray>() }.getOrElse {
        respond(HttpStatusCode.BadRequest, "Could not read the upload.")
        return null
    }
    return Upload(
        filename = filename,
        // The caller's *claim* about what these bytes are. AttachmentRepository
        // checks it against an allow-list rather than trusting it.
        mimeType = request.contentType().toString(),
        bytes = bytes,
    )
}

/**
 * Receive a body, or null if it is malformed.
 *
 * Ktor throws on a body that will not deserialize, and an unhandled throw here
 * is a 500 — which says "the server broke" about a request that was simply
 * wrong. Every caller turns this into a 400.
 */
internal suspend inline fun <reified T : Any> ApplicationCall.receiveOrNull(): T? =
    runCatching { receive<T>() }.getOrNull()

private fun ProjectRecord.toSummary(): ProjectSummary =
    ProjectSummary(id = id, name = name, namePrefix = namePrefix, isPublic = isPublic)

private fun ProjectPermissions.toView(): ProjectPermissionsView = ProjectPermissionsView(
    canCreateIssue = canCreateIssue,
    canComment = canComment,
    canChangeUnownedIssues = canChangeUnownedIssues,
    canMutateProject = canMutateProject,
)
