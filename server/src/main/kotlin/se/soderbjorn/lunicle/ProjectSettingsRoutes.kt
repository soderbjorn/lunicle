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
import se.soderbjorn.lunicle.clientserver.AudienceGrant
import se.soderbjorn.lunicle.clientserver.AudienceRow
import se.soderbjorn.lunicle.clientserver.NotificationSubscriptionRequest
import se.soderbjorn.lunicle.clientserver.PersonAdd
import se.soderbjorn.lunicle.clientserver.PersonRow
import se.soderbjorn.lunicle.clientserver.ProjectAccessState
import se.soderbjorn.lunicle.clientserver.ProjectDisplaySettings
import se.soderbjorn.lunicle.clientserver.ProjectFeatures
import se.soderbjorn.lunicle.clientserver.ProjectRequirements
import se.soderbjorn.lunicle.clientserver.ProjectSection
import se.soderbjorn.lunicle.clientserver.ProjectSectionKeys
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.RungGrant
import se.soderbjorn.lunicle.clientserver.RungOption
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
     * with nothing calling it — the Features section is gone from the settings pane
     * — because a re-enable wants this route exactly as it is.
     *
     * Project administrator only, via [adminProject] — one rung below the
     * `canOwnProject` the identity PUT asks for, because turning a forum off is a
     * project administrator's call and renaming the board is not. The pair is
     * written together; the response re-reads the project so [buildSettings] sees
     * the new flags rather than the stale record the gate captured.
     *
     * This named `canMutateProjects` as the identity PUT's gate. It never was, and
     * that function has since become the *instance owner* — managing the project set
     * across the whole deployment — so the sentence was measuring this route against
     * the wrong rung on the wrong ladder.
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
     * Put one person on one rung here, or take them off it.
     *
     * ── Why the second gate is the interesting one ───────────────────────────
     *
     * [ApplicationCall.adminProject] above says the caller administers this project,
     * which is enough for the rungs up to maintainer and **not** enough for Admin or
     * Owner — see [AccessControl.canGrant], asked once the rung is known, because it is
     * the rung that decides. Without it the first Admin could promote a second and the
     * Owner who granted them would have no say.
     *
     * And it is asked **twice**: about the rung being written, and about the rung being
     * replaced. An Admin who could write Viewer over somebody's Owner row would be
     * demoting an Owner with a gesture the ladder says is not theirs — the same
     * escalation, running downhill.
     *
     * Granting a rung to an instance administrator is allowed and does nothing:
     * [AccessControl.effectiveRole] gives them Owner everywhere before it looks at a
     * row. The row is honest — it says what was asked for — and becomes load-bearing
     * the moment that account stops being one. The Access list explains this rather
     * than the route refusing it.
     */
    post("${ApiRoutes.PROJECTS}/{id}/roles") {
        val scope = call.adminProject(deps, "change who this project admits") ?: return@post
        val body = call.receiveOrNull<RungGrant>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed grant.")
            return@post
        }
        // Null is "no access", which is a legal request and removes their row. A
        // non-null key this server does not have is a 400 rather than a silent no-op:
        // the alternative is a response saying nothing happened next to a menu that
        // just showed the new rung.
        val rung = body.roleKey?.let {
            ProjectRole.byKey(it) ?: run {
                call.respond(HttpStatusCode.BadRequest, "This server has no role called \"$it\".")
                return@post
            }
        }
        val target = deps.users.findById(body.userId) ?: run {
            call.respond(HttpStatusCode.NotFound, "No such user.")
            return@post
        }
        val existing = deps.roles.roleFor(target.id, scope.project.id)
        // Both ends of the move, for the reason above. `listOfNotNull` so "no access"
        // asks only about what is being taken away, and a first grant only about what is
        // being given.
        listOfNotNull(rung, existing).forEach { asked ->
            if (!deps.access.canGrant(scope.user, scope.project.id, asked)) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    "Only an owner of this project can hand out or withdraw ${asked.label}.",
                )
                return@post
            }
        }
        deps.roles.setRole(target.id, scope.project.id, rung)
        logger.info(
            "Rung set: user ${target.id} is now ${rung?.key ?: "nothing"} in project " +
                "${scope.project.id}, by user ${scope.user.id}",
        )
        call.respond(deps.buildSettings(scope.project, scope.user))
    }

    /**
     * Say at what rung a whole audience arrives here — guests, members, staff — or
     * withdraw the row (LNL-194).
     *
     * The replacement for `is_public` and `visible_to_all_signed_in`, which could each
     * only say "may look". An **owner's**, through
     * [AccessControl.canSetAudience]: this is the row that can hand the entire internet
     * a rung on the board, so it sits with the person who may also delete it.
     *
     * That function also refuses [Audience.GUEST] outright while the instance's
     * "allow projects to be public" switch is off, whoever asks — and the refusal is
     * here rather than only greyed in the list for the obvious reason: a rule that
     * lives in a screen is a rule a POST goes around.
     *
     * Gated at ADMIN by [adminProject] first and then at the real rung by
     * `canSetAudience`, so an Admin gets "not yours" rather than "no such project".
     */
    post("${ApiRoutes.PROJECTS}/{id}/audience") {
        val scope = call.adminProject(deps, "change who this project admits") ?: return@post
        val body = call.receiveOrNull<AudienceGrant>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        val audience = Audience.byKey(body.audienceKey) ?: run {
            call.respond(
                HttpStatusCode.BadRequest,
                "This server has no audience called \"${body.audienceKey}\".",
            )
            return@post
        }
        val rung = body.roleKey?.let {
            ProjectRole.byKey(it) ?: run {
                call.respond(HttpStatusCode.BadRequest, "This server has no role called \"$it\".")
                return@post
            }
        }
        if (!deps.access.canSetAudience(scope.user, scope.project.id, audience)) {
            call.respond(
                HttpStatusCode.Forbidden,
                if (audience == Audience.GUEST && !deps.instanceSettings.current().allowPublicProjects) {
                    "This deployment does not allow a project to be made public."
                } else {
                    "Only an owner of this project can change who it admits."
                },
            )
            return@post
        }
        deps.roles.setAudienceRole(scope.project.id, audience, rung)
        logger.info(
            "Audience set: ${audience.key} is now ${rung?.key ?: "nothing"} in project " +
                "${scope.project.id}, by user ${scope.user.id}",
        )
        call.respond(deps.buildSettings(scope.project, scope.user))
    }

    /**
     * Add a person by address, holding a rung (LNL-194).
     *
     * ── Nothing is sent ─────────────────────────────────────────────────────
     *
     * No mail, no token, no link, no expiry. The address gets a `users` row that has
     * never been signed into, the rung is written against it, and whoever owns the
     * address picks it up on their first sign-in — `upsert` finds the row by address
     * and adopts it. So there is nothing to deliver for the grant to exist, and nothing
     * to expire if it is never claimed.
     *
     * The consequence an administrator has to be told, and is, in the Access section's
     * advice line: on a deployment that cannot mail a sign-in code, only an address
     * that can sign in with Google will ever arrive, so adding any other is a grant
     * nobody can claim.
     *
     * ── And this is the gesture admission was waiting for ───────────────────
     *
     * [AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED] means "this organisation's domain, plus
     * addresses already added", and until now nothing added anybody, so it behaved
     * exactly like the staff-domain-only policy. A row written here is what makes that
     * `isAlreadyAdded` true at sign-in; see `admissionRefusal`.
     *
     * Admin and above, with the same two-ended rung check as the grant above — except
     * there is no existing rung to withdraw, this address having no row yet.
     */
    post("${ApiRoutes.PROJECTS}/{id}/people") {
        val scope = call.adminProject(deps, "add somebody to this project") ?: return@post
        val body = call.receiveOrNull<PersonAdd>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        val rung = ProjectRole.byKey(body.roleKey) ?: run {
            call.respond(HttpStatusCode.BadRequest, "This server has no role called \"${body.roleKey}\".")
            return@post
        }
        if (!deps.access.canGrant(scope.user, scope.project.id, rung)) {
            call.respond(
                HttpStatusCode.Forbidden,
                "Only an owner of this project can hand out ${rung.label}.",
            )
            return@post
        }
        // Normalised and shape-checked by the same two functions sign-in uses, so the
        // row this writes is the row a sign-in will find — a different spelling here
        // would hand somebody a rung they never pick up. See normalizeEmail.
        val address = normalizeEmail(body.email)
        if (address == null || !isPlausibleEmail(address)) {
            call.respond(HttpStatusCode.BadRequest, "That does not look like an e-mail address.")
            return@post
        }
        // The staff/member answer from the same function sign-in derives it with, so a
        // row added ahead of time and the same row after its owner arrives agree.
        val person = deps.users.addByEmail(address, UserKind.forEmail(address, deps.identity.domain))
        deps.roles.setRole(person.id, scope.project.id, rung)
        logger.info(
            "Person added: user ${person.id} holds ${rung.key} in project ${scope.project.id}, " +
                "added by user ${scope.user.id} (signed in before: ${person.hasSignedIn})",
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
 *   admin can change this project's access" is worth the parameter: a bare
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
    // the 404 ahead of the 403, matching sprintScope: an id the caller
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
 * Returned by every write as well as the read, so a screen never has to guess what
 * its own edit did. That matters most for the things an edit changes *elsewhere*:
 * deleting a status renumbers nothing but changes every other row's "can I be
 * deleted?" answer, and adding a priority moves the middle of the scale that new
 * issues land on. A client that patched its own state locally would be right about
 * the row it touched and wrong about the rest.
 *
 * ── Narrowed by rung, in three steps, and each step is a decision ────────────
 *
 * Everybody who can read the project gets the bottom layer: what *they* hold, their
 * own notification subscription, and how the board reads (not a secret — the board
 * itself reads it to draw a card).
 *
 * From Maintainer up, the Access section — the audience rows and the people who hold
 * something different. It stops there and not lower because the person rows carry
 * addresses, and somebody who merely reads a board has no business receiving the list
 * of exceptions on it.
 *
 * From Admin up, the vocabularies and their usage counts. From Owner, the repository
 * and its token source. Both are **omitted**, not sent-and-flagged: a field the
 * caller may not have does not travel and get hidden by the browser. See this file's
 * preamble and ProjectSettingsState's.
 */
private suspend fun BoardDependencies.buildSettings(
    project: ProjectRecord,
    caller: UserRecord,
): ProjectSettingsState {
    // One rung, asked once, and every gate below is `atLeast` against it — which is
    // the whole point of the ladder replacing a set of keys. A response cannot
    // disagree with itself about what this caller may do.
    val rung = access.effectiveRole(caller, project.id) ?: ProjectRole.VIEWER
    val administers = rung.atLeast(ProjectRole.ADMIN)
    val owns = rung.atLeast(ProjectRole.OWNER)
    // Sprints and versions are a maintainer's — the ladder has said so since LNL-191,
    // via VocabularyKind.minimumRole, and until LNL-196 the settings response did not:
    // both lists were inside the `administers` branch below, so a Maintainer was offered
    // a Sprints section and handed an empty one. Same rung, asked the same way, so the
    // section and the rows cannot disagree.
    val plans = rung.atLeast(ProjectRole.MAINTAINER)

    val base = ProjectSettingsState(
        canMutateProject = administers,
        sections = sectionsFor(rung),
        yourAccessLine = "You are ${rung.label.article()} ${rung.label} here. ${rung.description}",
        // From Maintainer up. Null below, which is what makes the Access section a
        // Viewer sees be the "Your access" line and nothing else.
        access = if (rung.atLeast(ProjectRole.MAINTAINER)) buildAccess(project, caller, rung) else null,
        canDeleteProject = owns,
        canMutateProjectIdentity = owns,
        // Worded for an Admin only — the one rung that would reasonably expect the row
        // and does not get it. Explaining a power three rungs up to a Viewer is noise.
        deleteBlockedReason = "Deleting this project is its owner's."
            .takeIf { !owns && rung == ProjectRole.ADMIN },
        // The one thing a caller at any rung can change here.
        notifyOnNewIssue = subscriptions.isSubscribedToProjectNewIssues(caller.id, project.id),
        canReceiveEmailNotifications = caller.email != null,
        // Both false for everybody since LNL-190 retired them, and still sent rather
        // than dropped: the field is on the wire, and a client reading it deserves the
        // same answer the board gives. See ProjectSettingsState.discussionsEnabled.
        discussionsEnabled = project.discussionsEnabled,
        messagesEnabled = project.messagesEnabled,
        // Not secret — the issue editor and the board read all five off the board they
        // already load — so sent honestly at every rung even though only Admin and above
        // renders them as editable. See ProjectSettingsState.requireLabel.
        requireLabel = project.requireLabel,
        requireComponent = project.requireComponent,
        requireFixedVersionOnResolve = project.requireFixedVersionOnResolve,
        showIssueAuthor = project.showIssueAuthor,
        hideIssueNumbers = project.hideIssueNumbers,
        // ── The two maintainer vocabularies (LNL-196) ────────────────────────
        //
        // In `base` rather than in the administrator's copy below, because the Sprints
        // and Versions sections are offered from Maintainer up and a section has to be
        // able to render. Not a leak: the board already sends every reader the sprint and
        // version *names* — a scope picker cannot draw itself without them — so what is
        // added here is the usage counts and the completion instants, for a caller who
        // may edit both lists.
        sprints = if (plans) sprintEntries(project.id) else emptyList(),
        versions = if (plans) vocabularies.rows(project.id, VocabularyKind.VERSION).map { it.toEntry() } else emptyList(),
        canMutateProjectPlanning = plans,
        // Null in every response this server builds today, because the rung that is
        // offered the two sections is the rung that may edit them. It is sent anyway, and
        // the pane greys off it, so that moving either power leaves a read-only section
        // with a sentence rather than a section whose controls lie.
        planningReadOnlyReason = "Shaping the sprints and the versions is a maintainer's."
            .takeIf { !plans },
    )
    if (!administers) return base

    // ── Why the repository is a SECOND gate, and not the one just above ──────
    //
    // Everything else added here is the project administrator's business. The
    // repository fields are not: the write that sets them goes through
    // `canOwnProject`, and the token field can carry a deployment secret — so
    // narrowing these on `administers` would send a project administrator fields they
    // can see, cannot change, and would be shown as editable right up until the save
    // came back 403.
    //
    // That the two gates are different lines in different files is exactly why this is
    // stated here: a response narrowed on the wrong half of that split leaks a
    // project's repository, and possibly a stored token, to whoever merely runs its
    // board.
    val repository = projects.repositoryConfig(project.id).takeIf { owns }
    return base.copy(
        labels = vocabularies.rows(project.id, VocabularyKind.LABEL).map { it.toEntry() },
        components = vocabularies.rows(project.id, VocabularyKind.COMPONENT).map { it.toEntry() },
        statuses = vocabularies.rows(project.id, VocabularyKind.STATUS).map { it.toEntry() },
        priorities = vocabularies.rows(project.id, VocabularyKind.PRIORITY).map { it.toEntry() },
        resolutions = vocabularies.rows(project.id, VocabularyKind.RESOLUTION).map { it.toEntry() },
        // `sprints` and `versions` are set in `base` above — they are a maintainer's, one
        // rung below everything on this list. See LNL-196.
        //
        // Rendered back as `owner/name` rather than the URL that was pasted, because
        // that is what was stored and echoing a reconstruction would invite the owner to
        // believe their exact spelling round-tripped. See parseRepositoryUrl.
        canConfigureRepository = owns,
        repositoryUrl = repository?.repository?.toString().orEmpty(),
        // The env-variable name is echoed (it is not a secret); a literal token is not
        // (it is). So the mode travels for the radio, and githubTokenEnv carries the name
        // only in env mode. A literal is signalled by the mode alone — which the dialog
        // reads as "one is stored, leave the field blank to keep it".
        githubTokenEnv = (repository?.token as? TokenSource.Env)?.variableName.orEmpty(),
        githubTokenMode = when (repository?.token) {
            is TokenSource.Env -> TokenModes.ENV
            is TokenSource.Literal -> TokenModes.LITERAL
            null, TokenSource.None -> TokenModes.NONE
        },
    )
}

/**
 * Which sections of a project this rung has, in rail order (LNL-194).
 *
 * The one place the answer lives, because the rail draws what it is handed — see
 * [ProjectSection]. Every line below is a rung's powers restated as a screen, so
 * moving a power between rungs moves its section with it.
 *
 * Below Maintainer there is **one** section, and it is Access relabelled "Your
 * access": a Viewer has nothing to configure, and a rail offering them General with
 * every field dead would be four screens of things that are not theirs. The
 * notification toggle rides in that section for every caller, at every rung, because
 * it is the one control here that is about the reader rather than about the project —
 * so it is in the same place for a Viewer and for an Owner.
 *
 * General appears from Maintainer up **read-only**, which is deliberate and is the
 * opposite of hiding it: a Maintainer sees the name, the prefix and how the board
 * reads, and can change none of it, with each group saying whose it is. Hiding what
 * somebody cannot edit only prompts "where did the project name go".
 */
private fun sectionsFor(rung: ProjectRole): List<ProjectSection> {
    if (!rung.atLeast(ProjectRole.MAINTAINER)) {
        return listOf(ProjectSection(ProjectSectionKeys.ACCESS, "Your access"))
    }
    return buildList {
        add(ProjectSection(ProjectSectionKeys.GENERAL, "General"))
        // The repository is part of a project's identity and its token is a deployment
        // secret, so this one is the owner's alone.
        if (rung.atLeast(ProjectRole.OWNER)) add(ProjectSection(ProjectSectionKeys.GITHUB, "Github"))
        // What the board *is*. An administrator's, with the sprints and the versions one
        // rung below.
        if (rung.atLeast(ProjectRole.ADMIN)) add(ProjectSection(ProjectSectionKeys.STRUCTURE, "Structure"))
        // The two vocabularies whose PRESENCE is the feature flag, side by side
        // (LNL-196): make the first sprint and the board gains a scope picker, make the
        // first version and every issue gains its two version fields. Versions used to
        // sit inside Structure among the labels — one rung too high, and beside a list of
        // things it does not resemble.
        add(ProjectSection(ProjectSectionKeys.SPRINTS, "Sprints"))
        add(ProjectSection(ProjectSectionKeys.VERSIONS, "Versions"))
        add(ProjectSection(ProjectSectionKeys.ACCESS, "Access"))
    }
}

/**
 * Who this project admits, as the Access section renders it (LNL-194).
 *
 * Built for a caller at Maintainer or above; see [buildSettings] for why the line is
 * drawn there. Everything about *whether a control is live* is decided here rather
 * than in the screen, because the greying has to agree with the refusal and the
 * refusal lives on this side. See [ProjectAccessState].
 */
private suspend fun BoardDependencies.buildAccess(
    project: ProjectRecord,
    caller: UserRecord,
    rung: ProjectRole,
): ProjectAccessState {
    val canGrant = rung.atLeast(ProjectRole.ADMIN)
    val owns = rung.atLeast(ProjectRole.OWNER)
    val settings = instanceSettings.current()
    val audienceRoles = roles.audienceRoles(project.id)
    val ownRows = roles.rolesForProject(project.id)

    return ProjectAccessState(
        audiences = Audience.entries
            // No staff row on a deployment that has not named its own domain: the
            // audience would match nobody, so the row would be a control that cannot do
            // anything rather than a stricter setting. Two rows, not three.
            .filter { it != Audience.STAFF || identity.hasStaffTier }
            .map { audience ->
                val refusal = audienceRefusal(audience, settings.allowPublicProjects, owns)
                AudienceRow(
                    key = audience.key,
                    title = audience.title,
                    subtitle = audience.subtitle(identity.domain),
                    roleKey = audienceRoles[audience]?.key,
                    isSelectable = refusal == null,
                    unavailableReason = refusal,
                )
            },
        people = peopleRows(project, caller, ownRows, audienceRoles, settings.ownerUserId, canGrant),
        rungs = ProjectRole.entries.map { offered ->
            // The same question the write asks — canGrant — so a rung offered here
            // cannot be refused there and a rung greyed here is genuinely refused.
            val grantable = access.canGrant(caller, project.id, offered)
            RungOption(
                key = offered.key,
                label = offered.label,
                description = offered.description,
                isSelectable = grantable,
                unavailableReason = if (grantable) {
                    null
                } else {
                    // Names the caller's own rung, because the useful part of the refusal
                    // is who to ask rather than that there was one.
                    "You are ${rung.label.article()} ${rung.label} here, so ${offered.label} " +
                        "is not yours to hand out."
                },
            )
        },
        canGrant = canGrant,
        // Parenthesised, and it matters: `"a" + "b".takeIf { … }` binds the takeIf to the
        // second literal alone, so a caller who CAN grant was handed "a" + "null" — a
        // read-only banner over a live section. Found by driving the app.
        readOnlyReason = (
            "Adding people and changing what they hold is for an administrator of this " +
                "project. You can see who is here."
            ).takeIf { !canGrant },
        addressAdvice = addressAdvice(),
        staffDomain = identity.domain,
    )
}

/**
 * Why this audience row is dead, or null because it is live.
 *
 * The publish veto is checked **first and separately**, because it is the refusal that
 * would still stand if the other were fixed: it applies to the guest row whoever asks,
 * an instance administrator and the deployment's own owner included, and
 * [AccessControl.canSetAudience] enforces it on the write. Greying it with the reason
 * is the explanation, not the enforcement.
 */
private fun audienceRefusal(audience: Audience, allowPublic: Boolean, owns: Boolean): String? = when {
    audience == Audience.GUEST && !allowPublic ->
        "This deployment does not allow a project to be made public. An instance " +
            "administrator decides that, in the instance settings."
    !owns -> "Who this project admits is its owner's to change."
    else -> null
}

/**
 * The exception rows: everybody who holds something other than what their audience
 * gives them.
 *
 * Three kinds of row, and nobody else:
 *
 *  - somebody with an **own row** in `project_roles`;
 *  - an **instance administrator**, who reaches Owner everywhere without one;
 *  - the **instance owner**, likewise, and separately because ownership is a setting
 *    rather than a column so no [UserRecord] carries it.
 *
 * Anybody adequately served by an audience row is deliberately absent. That is what
 * keeps this list short enough to *be* the audit — a screen listing every account on
 * the instance answers "who can get in here" with a directory, which is not an answer,
 * and is what the old privileges table did.
 */
private suspend fun BoardDependencies.peopleRows(
    project: ProjectRecord,
    caller: UserRecord,
    ownRows: Map<Long, ProjectRole>,
    audienceRoles: Map<Audience, ProjectRole>,
    instanceOwnerId: Long?,
    canGrant: Boolean,
): List<PersonRow> = users.selectAll()
    .filter { it.id in ownRows.keys || it.instanceRoleWith(instanceOwnerId).atLeast(InstanceRole.ADMIN) }
    .map { person ->
        val own = ownRows[person.id]
        // Where this person stands on the instance, ownership folded in from the id read
        // once above (LNL-201) — the whole ladder, so the two uses below cannot disagree
        // about who the owner is. It used to be `storedInstanceRole` for the audience match
        // and an inline `id == instanceOwnerId` for the rest.
        val instanceRole = person.instanceRoleWith(instanceOwnerId)
        // What their audience gives them anyway, by the same one-comparison rule
        // AccessControl.effectiveRole uses: the instance ladder ascends, so "matches this
        // audience" is `their rank >= the audience's`.
        val floor = audienceRoles
            .filterKeys { instanceRole.atLeast(it.instanceRole) }
            .entries
            .maxByOrNull { it.value.rank }
        val runsInstance = instanceRole.atLeast(InstanceRole.ADMIN)
        val effective = when {
            runsInstance -> ProjectRole.OWNER
            else -> listOfNotNull(own, floor?.value).maxByOrNull { it.rank }
        }
        PersonRow(
            userId = person.id,
            name = person.resolvedName,
            // The address, which does not cross on any other project wire type. It has to
            // here: this list is an audit of who was let in by name, and two accounts can
            // share a display name. See PersonRow.email.
            email = person.email.orEmpty(),
            roleKey = own?.key,
            effectiveLine = when {
                runsInstance -> null
                floor == null -> null
                // Only when the audience is actually carrying some of the weight. Somebody
                // whose own row is already senior to their audience is effectively their own
                // row, and saying so is a sentence restating the picker beside it — which is
                // what it did on the first pass, on every row. Found by driving the app.
                floor.value.rank < (own?.rank ?: -1) -> null
                else -> "The ${floor.key.title.lowercase()} row here already gives " +
                    "${floor.value.label}, so this person is effectively " +
                    "${effective?.label ?: floor.value.label}."
            },
            hasSignedIn = person.hasSignedIn,
            isSelf = person.id == caller.id,
            // An instance administrator's rung here is not stored and cannot be lowered
            // from this screen; and nobody may move somebody off a rung they could not
            // hand out themselves, which is what stops an Admin demoting an Owner.
            isEditable = canGrant && !runsInstance &&
                (own == null || access.canGrant(caller, project.id, own)),
            note = when {
                person.isInstanceAdmin -> "Runs this instance, so holds Owner on every project here."
                person.id == instanceOwnerId -> "Owns this instance, so holds Owner on every project here."
                own != null && canGrant && !access.canGrant(caller, project.id, own) ->
                    // Second person for the caller's own row: "what Adi Admin holds" on the
                    // row labelled "Adi Admin (you)" reads as being about somebody else.
                    if (person.id == caller.id) {
                        "Only an owner of this project can change what you hold here."
                    } else {
                        "Only an owner of this project can change what ${person.resolvedName} holds."
                    }
                else -> null
            },
        )
    }

/**
 * What an administrator needs to know before typing an address into the add dialog.
 *
 * Worded for *this* deployment, and computed here because the answer depends on
 * whether a mail transport is configured — which a screen has no way to know.
 *
 * The second sentence is the one worth having. Adding an address is a grant that has
 * to be **claimed** by somebody signing in, and on a deployment that cannot mail a
 * code the only way to claim one is Google. So adding an address that cannot reach
 * Google is not a slow invitation; it is a grant nobody can ever collect, and saying
 * so here is cheaper than the support conversation.
 */
private fun BoardDependencies.addressAdvice(): String {
    val nothingSent = "Nothing is sent. The address gets an account that can hold a role straight " +
        "away, and whoever owns it picks the role up the first time they sign in."
    return if (identity.isCodeSignInAvailable) {
        nothingSent
    } else {
        "$nothingSent This deployment cannot mail a sign-in code, so only an address that can " +
            "sign in with Google will ever arrive — adding any other is a role nobody can claim."
    }
}

/** What to call an audience on screen. */
private val Audience.title: String
    get() = when (this) {
        Audience.GUEST -> "Guests"
        Audience.MEMBER -> "Members"
        Audience.STAFF -> "Staff"
    }

/**
 * Who that audience is, in one sentence.
 *
 * The staff row's answer names the deployment's own domain, which is why this is
 * written here rather than on the enum: [Audience] is a permission vocabulary and does
 * not hold configuration.
 */
private fun Audience.subtitle(domain: String?): String = when (this) {
    Audience.GUEST -> "Anybody at all, without signing in."
    Audience.MEMBER -> "Everybody with an account on this deployment."
    Audience.STAFF -> domain?.let { "Accounts on $it." } ?: "Accounts on this organisation's domain."
}

/** "an" before Admin and Owner, "a" before the rest. Only ever applied to a rung label. */
private fun String.article(): String = if (firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an" else "a"

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

/**
 * The sprint rows, with the two things only a sprint has (LNL-196).
 *
 * `VocabularyRow` deliberately drops the completion instant — see
 * `VocabularyRepository`'s `SprintRecord.toRow`, which says why widening the shared row
 * shape so one kind in seven could carry one more nullable is the wrong trade. So the
 * join happens here instead, where the Sprints section's response is assembled and
 * where the extra reads are already paid for.
 *
 * Two extra reads, and only for a caller who reaches Maintainer:
 *
 *  - the sprint records, for `completed_at`, which is what puts a date and a
 *    Complete-or-Reopen on each row;
 *  - the project's issues and its closing columns, for how many of each sprint's issues
 *    are **not** finished. That is the number the completion confirmation asks about,
 *    and "unfinished" means "not in a status that requires a resolution" — a rule the
 *    browser cannot apply, because the settings pane does not hold the board.
 */
private suspend fun BoardDependencies.sprintEntries(projectId: Long): List<VocabularyEntry> {
    val rows = vocabularies.rows(projectId, VocabularyKind.SPRINT)
    if (rows.isEmpty()) return emptyList()
    val records = sprintRepository.forProject(projectId).associateBy { it.id }
    val closing = sprintRepository.closingStatusIds(projectId)
    val unfinished = issues.forProject(projectId)
        .filter { it.sprintId != null && it.statusId !in closing }
        .groupingBy { it.sprintId!! }
        .eachCount()
    return rows.map { row ->
        row.toEntry().copy(
            completedAt = records[row.id]?.completedAt,
            unfinishedCount = unfinished[row.id] ?: 0,
        )
    }
}
