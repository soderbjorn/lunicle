/**
 * The project settings dialog's routes: the vocabularies, and the grants.
 *
 * Same shape as BoardRoutes — **parse, AUTHORIZE, respond**. The *configuration*
 * routes — vocabularies and grants — are admin-only, and their authorization is
 * not a per-route decision that could be forgotten on the seventh handler: it is
 * [adminProject], and there is no path to a store through those handlers that does
 * not go through it. The gate returns the project, so forgetting to call it means
 * having nothing to write to.
 *
 * Two routes deliberately sit outside that gate: the settings **read**, and the
 * per-user **new-issue notification** toggle. The read is now *narrowed* rather
 * than refused — the dialog opens for every signed-in user (the issue's "what is
 * shown depends on if they are an admin"), and [buildSettings] fills the admin
 * sections only for an admin. A non-admin never receives the member directory or
 * the vocabulary counts; they receive their own notification state and nothing
 * else. The toggle is a caller managing their own inbox, which is not configuring
 * the project — so it checks "signed in and may read the project", not admin.
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
import se.soderbjorn.lunicle.clientserver.NotificationSubscriptionRequest
import se.soderbjorn.lunicle.clientserver.ProjectDisplaySettings
import se.soderbjorn.lunicle.clientserver.ProjectFeatures
import se.soderbjorn.lunicle.clientserver.ProjectRequirements
import se.soderbjorn.lunicle.clientserver.ProjectMember
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.RoleDescription
import se.soderbjorn.lunicle.clientserver.RoleGrant
import se.soderbjorn.lunicle.clientserver.TokenModes
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
        // Openable by any signed-in reader now — the dialog itself is the cog every
        // user sees. What comes back is narrowed by role: [buildSettings] fills the
        // admin sections only for an admin, and the notification fields for
        // everyone. A signed-out visitor has no settings to manage and no toggle to
        // set, so they are refused outright rather than sent an empty shell.
        val user = call.caller(deps) ?: run {
            call.respond(HttpStatusCode.Forbidden, "You must be signed in to see project settings.")
            return@get
        }
        val id = call.longParam("id") ?: run {
            call.respond(HttpStatusCode.BadRequest, "Bad project id.")
            return@get
        }
        val project = call.readableProject(deps, user, id) ?: return@get
        call.respond(deps.buildSettings(project, user))
    }

    /**
     * Subscribe or unsubscribe from this project's new-issue e-mails.
     *
     * Deliberately *not* behind [adminProject]: managing your own subscription is
     * not configuring the project — it is the one thing the issue grants every
     * signed-in user here. Signed in and able to read the project is the check,
     * plus an address to send to.
     */
    post("${ApiRoutes.PROJECTS}/{id}/notifications/new-issue") {
        val user = call.caller(deps) ?: run {
            call.respond(HttpStatusCode.Forbidden, "You must be signed in to change notifications.")
            return@post
        }
        val id = call.longParam("id") ?: run {
            call.respond(HttpStatusCode.BadRequest, "Bad project id.")
            return@post
        }
        val project = call.readableProject(deps, user, id) ?: return@post
        val body = call.receiveOrNull<NotificationSubscriptionRequest>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        if (body.subscribed && user.email == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                "Add an e-mail address to your profile before subscribing to notifications.",
            )
            return@post
        }
        deps.subscriptions.setProjectNewIssueSubscription(user.id, project.id, body.subscribed)
        call.respond(deps.buildSettings(project, user))
    }

    post("${ApiRoutes.PROJECTS}/{id}/vocabulary/{kind}") {
        // The kind is resolved BEFORE the gate, which is the reverse of the order
        // this read until LNL-191 split the vocabulary in two. It has to be: sprints
        // and versions are a maintainer's and the other five are an administrator's,
        // so there is no rung to check against until the kind is known. See
        // AccessControl.canEditVocabulary.
        val kind = call.vocabularyKind() ?: return@post
        val scope = call.vocabularyProject(deps, kind, "change this project's vocabulary") ?: return@post
        val body = call.receiveOrNull<VocabularyAdd>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed name.")
            return@post
        }
        if (!call.nameIsSane(body.name)) return@post
        deps.runVocabularyWrite(call) {
            val row = deps.vocabularies.add(scope.project.id, kind, body.name)
            logger.info("Vocabulary added: ${kind.key} \"${row.name}\" in project ${scope.project.id}")
            call.respond(deps.buildSettings(scope.project, scope.user))
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
        val kind = call.vocabularyKind() ?: return@put
        val scope = call.vocabularyProject(deps, kind, "change this project's vocabulary") ?: return@put
        val row = call.vocabularyRow(deps, scope.project, kind) ?: return@put
        val body = call.receiveOrNull<VocabularyEdit>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed name.")
            return@put
        }
        if (!call.nameIsSane(body.name)) return@put
        deps.runVocabularyWrite(call) {
            deps.vocabularies.rename(scope.project.id, kind, row, body.name, body.requiresResolution, body.isDone)
            call.respond(deps.buildSettings(scope.project, scope.user))
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
        val kind = call.vocabularyKind() ?: return@delete
        val scope = call.vocabularyProject(deps, kind, "change this project's vocabulary") ?: return@delete
        val row = call.vocabularyRow(deps, scope.project, kind) ?: return@delete
        deps.runVocabularyWrite(call) {
            deps.vocabularies.delete(scope.project.id, kind, row)
            logger.info("Vocabulary deleted: ${kind.key} \"${row.name}\" from project ${scope.project.id}")
            call.respond(deps.buildSettings(scope.project, scope.user))
        }
    }

    post("${ApiRoutes.PROJECTS}/{id}/vocabulary/{kind}/order") {
        val kind = call.vocabularyKind() ?: return@post
        val scope = call.vocabularyProject(deps, kind, "reorder this project's vocabulary") ?: return@post
        val body = call.receiveOrNull<VocabularyOrder>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed order.")
            return@post
        }
        deps.runVocabularyWrite(call) {
            deps.vocabularies.reorder(scope.project.id, kind, body.ids)
            call.respond(deps.buildSettings(scope.project, scope.user))
        }
    }

    /**
     * Switch this project's discussions and messages on or off (LNL-96).
     *
     * Answers "off" whatever it is asked for, since LNL-190 retired both features:
     * it still writes the columns, but every read of a project fills the two flags
     * from [PROJECT_FORUM_FEATURES_ENABLED], so the response says off. Left standing
     * with nothing calling it — the Features section is gone from the settings
     * dialog — because a re-enable wants this route exactly as it is.
     *
     * Project administrator only, via [adminProject] — a narrower gate than the
     * `canMutateProjects` the identity PUT uses, because turning a forum off is a
     * project administrator's call, not only the instance owner's. The pair is
     * written together; the response re-reads the project so [buildSettings] sees
     * the new flags rather than the stale record the gate captured.
     */
    post("${ApiRoutes.PROJECTS}/{id}/features") {
        val scope = call.adminProject(deps, "change this project's features") ?: return@post
        val body = call.receiveOrNull<ProjectFeatures>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        deps.projects.setFeatures(scope.project.id, body.discussionsEnabled, body.messagesEnabled)
        logger.info(
            "Project ${scope.project.id} features: discussions=${body.discussionsEnabled}, " +
                "messages=${body.messagesEnabled}",
        )
        val updated = deps.projects.findById(scope.project.id) ?: scope.project
        call.respond(deps.buildSettings(updated, scope.user))
    }

    /**
     * Switch this project's new-ticket requirements — must a ticket carry a label,
     * a component (LNL-106), and must a done close carry a fixed version (LNL-134).
     * Project administrator, exactly as `/features`: the Structure tab is a project
     * administrator's, not only the instance owner's. The set is written together
     * and the project re-read so [buildSettings] reflects the new flags rather than
     * the record the gate captured.
     */
    post("${ApiRoutes.PROJECTS}/{id}/requirements") {
        val scope = call.adminProject(deps, "change this project's ticket requirements") ?: return@post
        val body = call.receiveOrNull<ProjectRequirements>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        deps.projects.setRequirements(
            scope.project.id, body.requireLabel, body.requireComponent, body.requireFixedVersionOnResolve,
        )
        logger.info(
            "Project ${scope.project.id} requirements: label=${body.requireLabel}, " +
                "component=${body.requireComponent}, fixedVersion=${body.requireFixedVersionOnResolve}",
        )
        val updated = deps.projects.findById(scope.project.id) ?: scope.project
        call.respond(deps.buildSettings(updated, scope.user))
    }

    /**
     * Set how this project's board reads — whether cards show the author (LNL-157) and
     * whether the board hides issue numbers (LNL-194).
     *
     * Project administrator, exactly as `/requirements`: how a shared board reads is
     * the same project administrator's, not only the instance owner's. That is the
     * gate the second switch *arrived* at rather than the one it had — it was a
     * per-user preference and needed no gate at all, because it changed nothing for
     * anybody else. Its own route because a display setting is not a requirement.
     * Written, then the project re-read so [buildSettings] reflects the new flags
     * rather than the record the gate captured.
     */
    post("${ApiRoutes.PROJECTS}/{id}/display") {
        val scope = call.adminProject(deps, "change this project's display settings") ?: return@post
        val body = call.receiveOrNull<ProjectDisplaySettings>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        deps.projects.setBoardDisplay(scope.project.id, body.showIssueAuthor, body.hideIssueNumbers)
        logger.info(
            "Project ${scope.project.id} display: showIssueAuthor=${body.showIssueAuthor}, " +
                "hideIssueNumbers=${body.hideIssueNumbers}",
        )
        val updated = deps.projects.findById(scope.project.id) ?: scope.project
        call.respond(deps.buildSettings(updated, scope.user))
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
     * Granting a role to a system administrator is allowed and does nothing:
     * `isSysAdmin` short-circuits every check in [AccessControl] before it looks
     * at a role. The row is honest — it says what was asked for — and it becomes
     * load-bearing the moment that account stops being one. The dialog explains
     * this rather than the route refusing it.
     *
     * **The second gate is the interesting one.** [ApplicationCall.adminProject]
     * above says the caller administers this project, which a project
     * administrator does. That is enough for the issue-scoped roles and NOT
     * enough for `project_admin` itself — see [AccessControl.canGrant], which is
     * asked once the role is known, because it is the role that decides. Without
     * it, the first project administrator could promote a second and the system
     * administrator who granted them would have no say in it.
     */
    post("${ApiRoutes.PROJECTS}/{id}/roles") {
        val scope = call.adminProject(deps, "change this project's privileges") ?: return@post
        val body = call.receiveOrNull<RoleGrant>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed grant.")
            return@post
        }
        val role = ProjectRole.byKey(body.roleKey) ?: run {
            call.respond(HttpStatusCode.BadRequest, "This server has no role called \"${body.roleKey}\".")
            return@post
        }
        if (!deps.access.canGrant(scope.user, scope.project.id, role)) {
            call.respond(
                HttpStatusCode.Forbidden,
                "Only a project owner or system administrator can grant that role.",
            )
            return@post
        }
        val target = deps.users.findById(body.userId) ?: run {
            call.respond(HttpStatusCode.NotFound, "No such user.")
            return@post
        }
        // A rung is single-valued, so "granted" is a move TO it and "not granted" is
        // a move OFF it — and only off the rung named, so a stale dialog unticking a
        // box for a rung this person no longer holds does not knock them off the one
        // they do. The tick-box wire shape is what tickets 3–5 replace; until then
        // this is the honest reading of it.
        if (body.isGranted) {
            deps.roles.setRole(target.id, scope.project.id, role)
        } else if (deps.roles.roleFor(target.id, scope.project.id) == role) {
            deps.roles.setRole(target.id, scope.project.id, null)
        }
        logger.info(
            "Role ${if (body.isGranted) "granted" else "revoked"}: ${role.key} for user " +
                "${target.id} in project ${scope.project.id} by admin ${scope.user.id}",
        )
        call.respond(deps.buildSettings(scope.project, scope.user))
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
    minimumRole: ProjectRole = ProjectRole.ADMIN,
): AdminScope? {
    val user = caller(deps)
    // The effective user, and admin re-derived from the session — so an admin who
    // is impersonating an ordinary user cannot configure a project while wearing
    // their face. That is not an edge case: impersonation exists to see what
    // somebody else sees, and a settings dialog that stayed writable would make
    // the impersonated account the author of every change.
    if (user == null) {
        respond(HttpStatusCode.Forbidden, "Only an administrator can $action.")
        return null
    }
    // The project is resolved BEFORE the permission is asked, which is the
    // reverse of how this read until the check became per-project — it has to
    // be, since there is no per-project answer without a project. It also puts
    // the 404 ahead of the 403, matching adminSprintScope: an id the caller
    // cannot see answers "no such project" rather than confirming one exists by
    // that id.
    val id = longParam("id") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad project id.")
        return null
    }
    val project = deps.projects.findById(id) ?: run {
        respond(HttpStatusCode.NotFound, "No such project.")
        return null
    }
    if (deps.access.effectiveRole(user, project.id)?.atLeast(minimumRole) != true) {
        respond(HttpStatusCode.Forbidden, "Only an administrator of this project can $action.")
        return null
    }
    return AdminScope(user, project)
}

/**
 * Resolve a project this caller may edit [kind] in, or respond and return null.
 *
 * [adminProject] with a rung that depends on what is being edited: sprints and
 * versions are a maintainer's, the five vocabularies that define the board are an
 * administrator's. See [AccessControl.canEditVocabulary], which is where that split
 * lives — this only passes the kind to it.
 */
private suspend fun ApplicationCall.vocabularyProject(
    deps: BoardDependencies,
    kind: VocabularyKind,
    action: String,
): AdminScope? = adminProject(deps, action, kind.minimumRole)

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
private suspend fun BoardDependencies.buildSettings(
    project: ProjectRecord,
    caller: UserRecord,
): ProjectSettingsState {
    // The notification fields every signed-in caller gets, administrator or not —
    // the one thing a non-administrator can change here.
    val notifyOnNewIssue = subscriptions.isSubscribedToProjectNewIssues(caller.id, project.id)
    val canReceiveEmailNotifications = caller.email != null

    // Anyone else gets *only* those fields. The admin sections are omitted, not
    // sent-and-flagged: the members list is a directory of every account on the
    // instance, and someone who does not administer this project has no business
    // receiving it. This is the "narrow for a lesser caller" the board does,
    // applied here now that the dialog opens for everyone. See
    // ProjectSettingsState's preamble.
    if (!access.canAdministerProject(caller, project.id)) {
        return ProjectSettingsState(
            canMutateProject = false,
            notifyOnNewIssue = notifyOnNewIssue,
            canReceiveEmailNotifications = canReceiveEmailNotifications,
            // Both false for everybody since LNL-190 retired them, and still sent
            // rather than dropped: the field is on the wire, and a client reading it
            // deserves the same answer the board gives. No dialog renders a section
            // to edit them any more. See ProjectSettingsState.discussionsEnabled.
            discussionsEnabled = project.discussionsEnabled,
            messagesEnabled = project.messagesEnabled,
            // Not secret either — the issue editor sees them on the board it loads
            // — so sent honestly even though this caller's dialog renders no
            // section to edit them. See ProjectSettingsState.requireLabel.
            requireLabel = project.requireLabel,
            requireComponent = project.requireComponent,
            requireFixedVersionOnResolve = project.requireFixedVersionOnResolve,
            // Not secret either — the board reads it to render the card footer — so
            // sent honestly even though this caller's dialog renders no section to
            // edit it. See ProjectSettingsState.showIssueAuthor.
            showIssueAuthor = project.showIssueAuthor,
            hideIssueNumbers = project.hideIssueNumbers,
        )
    }

    val rungs = roles.rolesForProject(project.id)
    // ── Why this is a SECOND gate, and not the one just above ───────────────
    //
    // Everything else in this response is the project-administrator's business,
    // which is what the return above filters on. The repository fields are not.
    // The write that sets them goes through `canOwnProject` — an owner or a system
    // administrator (LNL-107) — and the token field can carry a deployment secret,
    // so narrowing these on canAdministerProject would send a project administrator
    // fields they can see, cannot change, and would be shown as editable right up
    // until the save came back 403.
    //
    // The read gate is therefore drawn where the write gate is. That the two are
    // different lines in different files is exactly why this is stated here:
    // LNL-37 split one notion of "admin" into administer-and-own, LNL-107 seated
    // the "own" half in a role, and a response narrowed on the wrong half of that
    // split leaks a project's repository — and possibly a stored token — to whoever
    // merely runs its board.
    val owns = access.canOwnProject(caller, project.id)
    val repository = projects.repositoryConfig(project.id).takeIf { owns }
    return ProjectSettingsState(
        labels = vocabularies.rows(project.id, VocabularyKind.LABEL).map { it.toEntry() },
        components = vocabularies.rows(project.id, VocabularyKind.COMPONENT).map { it.toEntry() },
        statuses = vocabularies.rows(project.id, VocabularyKind.STATUS).map { it.toEntry() },
        priorities = vocabularies.rows(project.id, VocabularyKind.PRIORITY).map { it.toEntry() },
        resolutions = vocabularies.rows(project.id, VocabularyKind.RESOLUTION).map { it.toEntry() },
        // Empty for every project that has never made one, and the section still
        // renders — an empty list with an add field is how the first sprint gets
        // made. See Sprints.sq for why there is no seed.
        sprints = vocabularies.rows(project.id, VocabularyKind.SPRINT).map { it.toEntry() },
        // Empty for every project that has never made one, and the section still
        // renders — an empty list with an add field is how the first version gets
        // made, like sprints. See Versions.sq for why there is no seed.
        versions = vocabularies.rows(project.id, VocabularyKind.VERSION).map { it.toEntry() },
        // The enum, not a table read: `roles` associates users with these, it does
        // not define them, and a row naming a role this build has never heard of
        // grants nothing. See RoleStore.seed.
        roles = ProjectRole.entries.map { RoleDescription(it.key, it.description) },
        members = users.selectAll().map { member ->
            ProjectMember(
                userId = member.id,
                // resolvedName, so the table says what every other screen says.
                // Note what does NOT cross: the email and the provider id are on
                // the UserRecord right here and stop at this line. See Users.kt.
                name = member.resolvedName,
                isSysAdmin = member.isInstanceAdmin,
                isSelf = member.id == caller.id,
                // At most one now — a person holds one rung per project. The wire
                // field stays a list because tickets 3–5 rebuild this table.
                roleKeys = listOfNotNull(rungs[member.id]?.key),
            )
        },
        canMutateProject = true,
        // Narrower than the line above, deliberately: a project administrator
        // reaches this branch and still may promote neither a peer nor an owner.
        // See canGrant.
        canGrantSeniorRoles = owns,
        notifyOnNewIssue = notifyOnNewIssue,
        canReceiveEmailNotifications = canReceiveEmailNotifications,
        // Rendered back as `owner/name` rather than the URL that was pasted,
        // because that is what was stored and echoing a reconstructed URL would
        // invite the owner to believe their exact spelling round-tripped. It is
        // accepted as input again unchanged; see parseRepositoryUrl.
        canConfigureRepository = owns,
        repositoryUrl = repository?.repository?.toString().orEmpty(),
        // The env-variable name is echoed (it is not a secret); a literal token is
        // not (it is). So the mode travels for the radio, and githubTokenEnv carries
        // the name only in env mode. A literal is signalled by the mode alone —
        // there is no field to hold it — which the dialog reads as "one is stored,
        // leave the field blank to keep it". See ProjectSettingsState.githubTokenMode.
        githubTokenEnv = (repository?.token as? TokenSource.Env)?.variableName.orEmpty(),
        githubTokenMode = when (repository?.token) {
            is TokenSource.Env -> TokenModes.ENV
            is TokenSource.Literal -> TokenModes.LITERAL
            null, TokenSource.None -> TokenModes.NONE
        },
        discussionsEnabled = project.discussionsEnabled,
        messagesEnabled = project.messagesEnabled,
        requireLabel = project.requireLabel,
        requireComponent = project.requireComponent,
        requireFixedVersionOnResolve = project.requireFixedVersionOnResolve,
        showIssueAuthor = project.showIssueAuthor,
        hideIssueNumbers = project.hideIssueNumbers,
    )
}

private fun VocabularyRow.toEntry(): VocabularyEntry = VocabularyEntry(
    id = id,
    name = name,
    position = position.toInt(),
    requiresResolution = requiresResolution,
    isDone = isDone,
    // toInt() on a count: a project with two billion issues on one label has
    // problems this cast is not among.
    usageCount = usageCount.toInt(),
)
