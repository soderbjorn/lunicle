/**
 * The project settings dialog's routes: the vocabularies, and the grants.
 *
 * Same shape as BoardRoutes — **parse, AUTHORIZE, respond** — with one thing
 * pulled tighter. Every route in this file is admin-only, so the authorization is
 * not a per-route decision that could be forgotten on the seventh handler: it is
 * [adminProject], and there is no path to a store in this file that does not go
 * through it. Six routes, one gate, and the gate returns the project — so
 * forgetting to call it means having nothing to write to.
 *
 * The read is **refused**, not filtered, which is the departure from BoardRoutes'
 * other half. A board narrows itself for a lesser caller because there is a
 * smaller true answer to send. There is no smaller true answer here: the response
 * is a directory of every account on the instance and a set of counts nothing
 * renders unless you can act on them. So a non-admin gets a 403, not a thinner
 * settings page.
 *
 * @see AccessControl
 * @see VocabularyRepository
 * @see se.soderbjorn.lunicle.clientserver.ProjectSettingsState
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
import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.ProjectMember
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.RoleDescription
import se.soderbjorn.lunicle.clientserver.RoleGrant
import se.soderbjorn.lunicle.clientserver.VocabularyAdd
import se.soderbjorn.lunicle.clientserver.VocabularyEdit
import se.soderbjorn.lunicle.clientserver.VocabularyEntry
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.clientserver.VocabularyOrder

private val logger = LoggerFactory.getLogger("ProjectSettingsRoutes")

/**
 * How long a vocabulary name may be.
 *
 * Bounded for [MAX_TITLE_LENGTH]'s reason and more so: a status name is a board
 * column *header*, so a caller sending a kilobyte of it would not break the
 * server, it would make the board unreadable for everyone in the project. Short,
 * because every one of these is rendered in a chip, a header or a dropdown.
 */
private const val MAX_VOCABULARY_NAME_LENGTH = 60

/** Mount the settings routes. Called by [boardRoutes]. */
fun Route.projectSettingsRoutes(deps: BoardDependencies) {
    /**
     * Everything the settings dialog opens with.
     *
     * One response rather than seven, for the board's reason: the dialog cannot
     * render half of it. That costs a dozen small queries per open, which is the
     * right trade for a dialog an admin opens occasionally and a board nobody
     * opens without.
     */
    get("${ApiRoutes.PROJECTS}/{id}/settings") {
        val scope = call.adminProject(deps, "see this project's settings") ?: return@get
        call.respond(deps.buildSettings(scope))
    }

    post("${ApiRoutes.PROJECTS}/{id}/vocabulary/{kind}") {
        val scope = call.adminProject(deps, "change this project's vocabulary") ?: return@post
        val kind = call.vocabularyKind() ?: return@post
        val body = call.receiveOrNull<VocabularyAdd>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed name.")
            return@post
        }
        if (!call.nameIsSane(body.name)) return@post
        deps.runVocabularyWrite(call) {
            val row = deps.vocabularies.add(scope.project.id, kind, body.name)
            logger.info("Vocabulary added: ${kind.key} \"${row.name}\" in project ${scope.project.id}")
            call.respond(deps.buildSettings(scope))
        }
    }

    /**
     * Rename, and set a status's closing flag.
     *
     * `{itemId}` is resolved *within* the project in the path — see
     * [VocabularyRepository.find]. Admin is admin everywhere, so this is not what
     * stops an attacker; it is what stops a confused client from renaming another
     * project's "Closed" because it sent the id it had lying around.
     */
    put("${ApiRoutes.PROJECTS}/{id}/vocabulary/{kind}/{itemId}") {
        val scope = call.adminProject(deps, "change this project's vocabulary") ?: return@put
        val kind = call.vocabularyKind() ?: return@put
        val row = call.vocabularyRow(deps, scope.project, kind) ?: return@put
        val body = call.receiveOrNull<VocabularyEdit>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed name.")
            return@put
        }
        if (!call.nameIsSane(body.name)) return@put
        deps.runVocabularyWrite(call) {
            deps.vocabularies.rename(scope.project.id, kind, row, body.name, body.requiresResolution)
            call.respond(deps.buildSettings(scope))
        }
    }

    /**
     * Delete a row, or be told what it would cost.
     *
     * The refusals are [VocabularyRepository.delete]'s and not this route's, on
     * purpose: the MCP tools are a second front door onto this server, and a rule
     * that lived in a handler would be a rule the other door does not have.
     */
    delete("${ApiRoutes.PROJECTS}/{id}/vocabulary/{kind}/{itemId}") {
        val scope = call.adminProject(deps, "change this project's vocabulary") ?: return@delete
        val kind = call.vocabularyKind() ?: return@delete
        val row = call.vocabularyRow(deps, scope.project, kind) ?: return@delete
        deps.runVocabularyWrite(call) {
            deps.vocabularies.delete(scope.project.id, kind, row)
            logger.info("Vocabulary deleted: ${kind.key} \"${row.name}\" from project ${scope.project.id}")
            call.respond(deps.buildSettings(scope))
        }
    }

    post("${ApiRoutes.PROJECTS}/{id}/vocabulary/{kind}/order") {
        val scope = call.adminProject(deps, "reorder this project's vocabulary") ?: return@post
        val kind = call.vocabularyKind() ?: return@post
        val body = call.receiveOrNull<VocabularyOrder>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed order.")
            return@post
        }
        deps.runVocabularyWrite(call) {
            deps.vocabularies.reorder(scope.project.id, kind, body.ids)
            call.respond(deps.buildSettings(scope))
        }
    }

    /**
     * Grant or revoke one role for one user here.
     *
     * The role is looked up by key rather than trusted: an unknown key is a 400,
     * because `Roles.sq`'s grant is an `INSERT OR IGNORE … SELECT … WHERE role_key
     * = ?`, which inserts *nothing* for a key that names no role. Without this
     * check the response would be a settings state saying the grant did not
     * happen, next to a dialog that just ticked the box.
     *
     * Granting a role to the instance admin is allowed and does nothing: admin
     * short-circuits every check in [AccessControl] before it looks at a role. The
     * row is honest — it says what was asked for — and it becomes load-bearing the
     * moment that account stops being an admin. The dialog explains this rather
     * than the route refusing it.
     */
    post("${ApiRoutes.PROJECTS}/{id}/roles") {
        val scope = call.adminProject(deps, "change this project's privileges") ?: return@post
        val body = call.receiveOrNull<RoleGrant>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed grant.")
            return@post
        }
        val role = Role.entries.firstOrNull { it.key == body.roleKey } ?: run {
            call.respond(HttpStatusCode.BadRequest, "This server has no role called \"${body.roleKey}\".")
            return@post
        }
        val target = deps.users.findById(body.userId) ?: run {
            call.respond(HttpStatusCode.NotFound, "No such user.")
            return@post
        }
        if (body.isGranted) {
            deps.roles.grant(target.id, scope.project.id, role)
        } else {
            deps.roles.revoke(target.id, scope.project.id, role)
        }
        logger.info(
            "Role ${if (body.isGranted) "granted" else "revoked"}: ${role.key} for user " +
                "${target.id} in project ${scope.project.id} by admin ${scope.user.id}",
        )
        call.respond(deps.buildSettings(scope))
    }
}

/**
 * An authorized caller and the project they are configuring.
 *
 * The two are handed over together because they were established together, and
 * separating them would mean a handler asking "who is this?" a second time — one
 * more session lookup, and one more chance to ask it of something other than the
 * cookie.
 *
 * @property user the **effective** user, never null: [adminProject] does not
 *   return one for a caller it refused.
 */
private data class AdminScope(
    val user: UserRecord,
    val project: ProjectRecord,
)

/**
 * Resolve a project this caller may **configure**, or respond and return null.
 *
 * The one gate in this file, and the reason none of the handlers above mention
 * [AccessControl]: it returns the project, so a route that skipped it would have
 * no project to write to. A check that can be forgotten is a check that will be.
 *
 * 403 before 404, unlike [readableProject], and the difference is deliberate.
 * There a 403 would confirm that a private project by that id exists, which is
 * exactly what is being withheld. Here the caller either is the instance admin —
 * who may see every project's existence anyway — or is being told "not you",
 * which reveals nothing about which projects there are.
 *
 * @param action what the caller was trying to do, dropped into the 403. "Only an
 *   admin can change this project's privileges" is worth the parameter: a bare
 *   "Forbidden" makes a legitimate admin who is signed in as the wrong account go
 *   looking for a bug.
 */
private suspend fun ApplicationCall.adminProject(
    deps: BoardDependencies,
    action: String,
): AdminScope? {
    val user = caller(deps)
    // The effective user, and admin re-derived from the session — so an admin who
    // is impersonating an ordinary user cannot configure a project while wearing
    // their face. That is not an edge case: impersonation exists to see what
    // somebody else sees, and a settings dialog that stayed writable would make
    // the impersonated account the author of every change.
    if (user == null || !deps.access.canMutateProjects(user)) {
        respond(HttpStatusCode.Forbidden, "Only an admin can $action.")
        return null
    }
    val id = longParam("id") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad project id.")
        return null
    }
    val project = deps.projects.findById(id) ?: run {
        respond(HttpStatusCode.NotFound, "No such project.")
        return null
    }
    return AdminScope(user, project)
}

/** The `{kind}` segment as a [VocabularyKind], or respond and return null. */
private suspend fun ApplicationCall.vocabularyKind(): VocabularyKind? {
    val key = parameters["kind"]
    // Matched against the enum's own `key` rather than valueOf(uppercase): the
    // keys are wire format and the constant names are not, so this is the one
    // place the two are allowed to be told apart. See VocabularyKind.
    val kind = VocabularyKind.entries.firstOrNull { it.key == key }
    if (kind == null) {
        respond(HttpStatusCode.NotFound, "There is no vocabulary called \"$key\".")
        return null
    }
    return kind
}

/** The `{itemId}` row, proved to be this project's, or respond and return null. */
private suspend fun ApplicationCall.vocabularyRow(
    deps: BoardDependencies,
    project: ProjectRecord,
    kind: VocabularyKind,
): VocabularyRow? {
    val itemId = longParam("itemId") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad ${kind.key} id.")
        return null
    }
    val row = deps.vocabularies.find(project.id, kind, itemId)
    if (row == null) {
        // 404 for "no such row" and for "that row is another project's" alike:
        // both are, from this project's URL, a request for something that is not
        // there.
        respond(HttpStatusCode.NotFound, "That ${kind.key} does not belong to this project.")
        return null
    }
    return row
}

/**
 * Is this a name worth trying to store?
 *
 * Only the bound is checked here. Blankness and uniqueness are
 * [VocabularyRepository]'s — they are rules about the vocabulary, and the MCP
 * surface must get them too — while a length limit is a fact about a route
 * accepting bytes from the network, which is this layer's business and not the
 * repository's.
 */
private suspend fun ApplicationCall.nameIsSane(name: String): Boolean {
    if (name.trim().length > MAX_VOCABULARY_NAME_LENGTH) {
        respond(
            HttpStatusCode.BadRequest,
            "That name is too long — $MAX_VOCABULARY_NAME_LENGTH characters at most.",
        )
        return false
    }
    return true
}

/**
 * Run a vocabulary write, turning its two refusals into the statuses they mean.
 *
 * Written once because five handlers need it and five copies would be five
 * chances for a refusal to escape as a 500 — which is the exact failure this
 * whole feature exists to prevent, one layer up: an admin who deletes a status
 * that is in use must be told that three issues are in it, not that the server
 * broke.
 *
 * A [VocabularyConflict] is a 409 — the name is taken, possibly by someone else's
 * edit a second ago — and a [VocabularyRefusal] is a 400: nothing raced, the
 * request was wrong when it was sent. Both carry a sentence written for a human,
 * and both are shown verbatim by the dialog.
 */
private suspend inline fun BoardDependencies.runVocabularyWrite(
    call: ApplicationCall,
    write: () -> Unit,
) {
    try {
        write()
    } catch (conflict: VocabularyConflict) {
        call.respond(HttpStatusCode.Conflict, conflict.userMessage)
    } catch (refusal: VocabularyRefusal) {
        call.respond(HttpStatusCode.BadRequest, refusal.userMessage)
    }
}

/**
 * Assemble one settings response.
 *
 * Returned by every write as well as the read, so a dialog never has to guess
 * what its own edit did. That matters most for the things an edit changes
 * *elsewhere*: deleting a status renumbers nothing but changes every other row's
 * "can I be deleted?" answer, and adding a priority moves the middle of the scale
 * that new issues land on. A client that patched its own state locally would be
 * right about the row it touched and wrong about the rest.
 */
private suspend fun BoardDependencies.buildSettings(scope: AdminScope): ProjectSettingsState {
    val project = scope.project
    val grants = roles.grantsForProject(project.id)
    return ProjectSettingsState(
        labels = vocabularies.rows(project.id, VocabularyKind.LABEL).map { it.toEntry() },
        components = vocabularies.rows(project.id, VocabularyKind.COMPONENT).map { it.toEntry() },
        statuses = vocabularies.rows(project.id, VocabularyKind.STATUS).map { it.toEntry() },
        priorities = vocabularies.rows(project.id, VocabularyKind.PRIORITY).map { it.toEntry() },
        resolutions = vocabularies.rows(project.id, VocabularyKind.RESOLUTION).map { it.toEntry() },
        // The enum, not a table read: `roles` associates users with these, it does
        // not define them, and a row naming a role this build has never heard of
        // grants nothing. See RoleStore.seed.
        roles = Role.entries.map { RoleDescription(it.key, it.description) },
        members = users.selectAll().map { user ->
            ProjectMember(
                userId = user.id,
                // resolvedName, so the table says what every other screen says.
                // Note what does NOT cross: the email and the provider id are on
                // the UserRecord right here and stop at this line. See Users.kt.
                name = user.resolvedName,
                isAdmin = user.isAdmin,
                isSelf = user.id == scope.user.id,
                roleKeys = grants[user.id].orEmpty().map { it.key },
            )
        },
        // Always true by construction — adminProject refused everyone else before
        // this ran. Sent anyway because the wire default is false, so a route that
        // one day forgets the gate produces a read-only dialog rather than one
        // that invites writes. See ProjectSettingsState.canMutateProject.
        canMutateProject = true,
    )
}

private fun VocabularyRow.toEntry(): VocabularyEntry = VocabularyEntry(
    id = id,
    name = name,
    position = position.toInt(),
    requiresResolution = requiresResolution,
    // toInt() on a count: a project with two billion issues on one label has
    // problems this cast is not among.
    usageCount = usageCount.toInt(),
)
