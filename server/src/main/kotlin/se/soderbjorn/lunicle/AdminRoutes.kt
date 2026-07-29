/**
 * The instance settings dialog's routes: the account directory, the deployment-wide
 * switches, admission, and the project list.
 *
 * Same shape as the rest — **parse, AUTHORIZE, respond** — and the authorization
 * here is the simplest in the codebase, because it is the only place where the
 * whole surface is admin-only. [ProjectSettingsRoutes] has to draw a line through
 * the middle of one response (an admin gets the vocabularies, a non-admin gets
 * their own notification toggle and nothing else); nothing in this file belongs to
 * a non-admin, so [adminCaller] refuses and there is no narrowed half to get wrong.
 *
 * ── The per-account MCP route that used to be here (LNL-192) ────────────────
 *
 * `POST /api/admin/users/mcp` set one person's agent-access *permission*. It is
 * gone, along with the wire type and the client call: the permission is per tier
 * now — two switches on this same instance-settings write — and there is no
 * per-person override anywhere in this design. LNL-191 dropped the column it wrote
 * and left the route answering honestly that it stored nothing; leaving it standing
 * any longer would have invited exactly the per-account column this rework removed.
 *
 * The *user's own* switch is untouched and still lives at `POST /api/mcp/enabled`,
 * where it always did. An admin cannot set it, which is not an omission: it is the
 * person's own answer, and a screen that let somebody else give it would record a
 * preference the user never expressed.
 *
 * @see AccessControl
 * @see se.soderbjorn.lunicle.clientserver.AdminSettingsState
 */
package se.soderbjorn.lunicle

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.clientserver.AdminProjectRights
import se.soderbjorn.lunicle.clientserver.AdminSettingsState
import se.soderbjorn.lunicle.clientserver.AdminUser
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.ProjectOrder
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.RoleDescription
import se.soderbjorn.lunicle.clientserver.SetAdmissionPolicyRequest
import se.soderbjorn.lunicle.clientserver.SetInstanceSettingRequest

private val logger = LoggerFactory.getLogger("AdminRoutes")

/** Mount the instance settings routes. Called by [boardRoutes]. */
fun Route.adminRoutes(deps: BoardDependencies) {
    /**
     * The whole account directory.
     *
     * One response rather than "a list, then a detail fetch per click", for
     * [AdminSettingsState]'s reason: the payload is small and a spinner inside a
     * master-detail pane defeats the point of one.
     */
    get(ApiRoutes.ADMIN_SETTINGS) {
        val admin = call.adminCaller(deps, "see the user directory") ?: return@get
        call.respond(deps.buildAdminSettings(admin))
    }

    /**
     * Set one instance-wide switch: whether projects may be published, and what
     * each tier of signed-in person may do (LNL-192). Admin only, like everything
     * in this file.
     *
     * Names the switch and its desired state in the body — a value, not an
     * authorization decision, because every switch here answers to this same one
     * gate. Answers with the refreshed directory so the General tab never merges two
     * objects, exactly as the other writes here do.
     */
    post(ApiRoutes.ADMIN_INSTANCE_SETTINGS) {
        val admin = call.adminCaller(deps, "change instance settings") ?: return@post
        val body = call.receiveOrNull<SetInstanceSettingRequest>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        deps.instanceSettings.set(body.key, body.isEnabled)
        logger.info(
            "Instance setting ${body.key.storageKey} set to ${body.isEnabled} by admin ${admin.id}",
        )
        call.respond(deps.buildAdminSettings(admin))
    }

    /**
     * Set who may hold an account on this deployment (LNL-192). Admin only.
     *
     * Its own route rather than a sixth switch, because it is the one thing on this
     * screen with a **refusal** to make. The deployment's configuration can leave a
     * policy unhonourable — see [InstanceIdentity.admissionState] — and a greyed
     * option that a hand-written POST could still set would make the greying an
     * affordance rather than a rule. The refusal carries the same sentence the
     * greying shows, so an administrator who somehow reaches it reads the same
     * explanation twice rather than two different ones.
     */
    post(ApiRoutes.ADMIN_ADMISSION) {
        val admin = call.adminCaller(deps, "change who may have an account") ?: return@post
        val body = call.receiveOrNull<SetAdmissionPolicyRequest>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        val option = deps.identity.admissionState(body.policy).options.first { it.policy == body.policy }
        if (!option.isSelectable) {
            call.respond(
                HttpStatusCode.Conflict,
                "This deployment cannot admit people that way: ${option.unavailableReason}.",
            )
            return@post
        }
        deps.instanceSettings.setAdmissionPolicy(body.policy)
        logger.info("Admission set to ${body.policy.key} by admin ${admin.id}")
        call.respond(deps.buildAdminSettings(admin))
    }

    /**
     * Put the instance's projects in a given order.
     *
     * Admin only, like everything in this file. The order is the whole list, not a
     * delta, and [ProjectRepository.reorder] refuses one that does not name exactly
     * the projects that exist — a stale dialog that has lost or gained a project is
     * told so rather than half-applied. Answers with the refreshed directory so the
     * dialog never merges two objects.
     */
    post(ApiRoutes.ADMIN_PROJECT_ORDER) {
        val admin = call.adminCaller(deps, "reorder projects") ?: return@post
        val body = call.receiveOrNull<ProjectOrder>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed order.")
            return@post
        }
        try {
            deps.projectRepository.reorder(body.ids)
        } catch (conflict: ProjectConflict) {
            // 409 with the repository's own sentence — the dialog shows it verbatim,
            // the same way the create/rename paths do. The usual cause is a second
            // admin having deleted a project while this dialog was open.
            call.respond(HttpStatusCode.Conflict, conflict.userMessage)
            return@post
        }
        logger.info("Projects reordered by admin ${admin.id}")
        call.respond(deps.buildAdminSettings(admin))
    }

    /**
     * Delete a project — the instance-settings home for a power that used to live in
     * the project dialog (LNL-93). Admin only, and permanent: there is no trash, and
     * everything in the project goes with it. Answers with the refreshed directory.
     */
    delete("${ApiRoutes.ADMIN_PROJECTS}/{id}") {
        val admin = call.adminCaller(deps, "delete a project") ?: return@delete
        val id = call.longParam("id") ?: run {
            call.respond(HttpStatusCode.BadRequest, "Bad project id.")
            return@delete
        }
        val project = deps.projects.findById(id) ?: run {
            call.respond(HttpStatusCode.NotFound, "No such project.")
            return@delete
        }
        deps.projectRepository.delete(id)
        logger.info("Project deleted: ${project.name} by admin ${admin.id}")
        call.respond(deps.buildAdminSettings(admin))
    }
}

/**
 * Resolve an admin caller, or respond and return null.
 *
 * The one gate in this file. It returns the caller rather than a boolean so that
 * [buildAdminSettings] has somebody to compute `isSelf` against — the same trick
 * [ProjectSettingsRoutes]' `adminProject` plays with the project, and for the same
 * reason: a gate you have to use is a gate you cannot forget.
 *
 * Admin is re-derived from the session's **effective** user, so an admin who is
 * impersonating an ordinary account cannot open this while wearing their face.
 * That is not incidental. Impersonation exists to see what somebody else sees, and
 * the account directory is precisely what they do not see.
 *
 * 403 for a signed-out visitor too, rather than a 401. There is nothing here to
 * sign in *for* unless you are already the admin, and answering differently would
 * make this route a probe for whether a given deployment has one.
 *
 * @param action what the caller was trying to do, dropped into the message —
 *   [ProjectSettingsRoutes]' reasoning: a bare "Forbidden" sends a legitimate admin
 *   signed in as the wrong account looking for a bug.
 */
private suspend fun ApplicationCall.adminCaller(
    deps: BoardDependencies,
    action: String,
): UserRecord? {
    val user = caller(deps)
    if (user == null || !deps.access.canMutateProjects(user)) {
        respond(HttpStatusCode.Forbidden, "Only a system administrator can $action.")
        return null
    }
    return user
}

/**
 * Assemble one instance settings response.
 *
 * Returned by the write as well as the read, so the dialog never has to guess what
 * its own edit did — the rule every settings surface here follows.
 *
 * Costs one `selectAll` per table plus one grants query per project, which is a
 * handful of queries for a screen an admin opens rarely. The alternative — a join
 * — would trade that for a shape this has to un-flatten anyway, and
 * [RoleStore.grantsForProject] is the query that already exists and is already
 * tested.
 *
 * Every project is listed for every user, including the ones where they hold
 * nothing; see [AdminProjectRights] for why an omitted row would be the wrong
 * answer. Private projects are listed too, without filtering by `canReadProject`:
 * the caller is an admin, who may read every project on the instance, so there is
 * nothing here to withhold and a filter would only make the list lie about which
 * projects exist.
 */
private suspend fun BoardDependencies.buildAdminSettings(caller: UserRecord): AdminSettingsState {
    val allProjects = projects.selectAll()
    // Read once per project rather than once per (user, project): the grants query
    // answers for a whole project at a time, and a directory of twenty accounts
    // across five projects is five queries this way and a hundred the other.
    val rungsByProject = allProjects.associate { it.id to roles.rolesForProject(it.id) }
    // The audience rows too, once per project: whether a person can see a board is
    // now "an audience admits them, or they have an own row", and the first half is
    // a fact about the project rather than about the pair.
    val audiencesByProject = allProjects.associate { it.id to roles.audienceRoles(it.id) }

    // The General tab's settings, read together in one query. The write returns this
    // whole state, so a toggle re-renders the tab from the server's answer rather
    // than patching its own copy — the rule every write here follows.
    val switches = instanceSettings.current()

    return AdminSettingsState(
        // The stored policy AND this deployment's verdict on each of the three
        // choices, computed here so the dialog renders what it is handed. A client
        // that re-derived the greying would need to be shown the brand manifest and
        // the mail configuration, and would be a second copy of the rule the write
        // above enforces. See InstanceIdentity.admissionState.
        admission = identity.admissionState(switches.admission),
        allowPublicProjects = switches.allowPublicProjects,
        staffMayCreateProjects = switches.staffMayCreateProjects,
        memberMayCreateProjects = switches.memberMayCreateProjects,
        staffMayUseAgents = switches.staffMayUseAgents,
        memberMayUseAgents = switches.memberMayUseAgents,
        hideDisplayName = switches.hideDisplayName,
        // The enum, not a table read. `roles` associates users with these; it does
        // not define them, and a row naming a role this build has never heard of
        // grants nothing. Same as buildSettings. See RoleStore.seed.
        roles = ProjectRole.entries.map { RoleDescription(it.key, it.description) },
        // The instance's projects, in the arranged order `selectAll` now returns —
        // the same list `allProjects` above already holds, mapped to the wire type
        // the Projects tab reorders and deletes from. See AdminSettingsState.projects.
        projects = allProjects.map {
            ProjectSummary(
                id = it.id,
                name = it.name,
                namePrefix = it.namePrefix,
                // Retired on the wire pending tickets 3–5; see ProjectSummary.
                isPublic = false,
                visibleToAllSignedIn = false,
                discussionsEnabled = it.discussionsEnabled,
                messagesEnabled = it.messagesEnabled,
            )
        },
        // Admins first, then by name. `selectAll` already orders by the resolved
        // display name — see Users.sq — so this only adds the grouping, and
        // `sortedWith` is stable, which is what lets the name order survive it
        // without being restated. Sorted here rather than in the query because
        // this is the one screen that wants it: the impersonation menu reads the
        // same `selectAll` and is a flat list of people, where hoisting admins
        // would be a hierarchy nobody asked that menu to express.
        users = users.selectAll().sortedWith(compareByDescending { it.isInstanceAdmin }).map { user ->
            AdminUser(
                userId = user.id,
                name = user.resolvedName,
                // The one field here that does not cross in ProjectMember. See
                // AdminUser's doc for why it does here — this response reaches
                // admins only, and telling two same-named accounts apart is the
                // screen's job.
                email = user.email,
                isSysAdmin = user.isInstanceAdmin,
                isSelf = user.id == caller.id,
                // Read-only now, and derived: the permission is per tier (LNL-192),
                // so this reports which side of the two switches above this account
                // falls on rather than a box an admin ticks for them. Answered off
                // the snapshot already in hand rather than a read per account.
                isMcpAllowed = switches.permitsAgents(
                    if (switches.ownerUserId == user.id) InstanceRole.OWNER else user.storedInstanceRole,
                ),
                // Read-only on this screen — the user's own answer, reported so an
                // admin can see why a freshly-permitted account still has no agent
                // running. See AdminUser.
                isMcpEnabled = user.isMcpEnabled,
                projects = allProjects.map { project ->
                    val own = rungsByProject[project.id]?.get(user.id)
                    AdminProjectRights(
                        projectId = project.id,
                        projectName = project.name,
                        // At most one now — a person has one rung per project. The
                        // wire field stays a list because tickets 3–5 rebuild this
                        // table; a list of one renders as the row it is.
                        heldRoleKeys = listOfNotNull(own?.key),
                        // The effective read right, mirroring
                        // AccessControl.effectiveRole: an audience row this account
                        // matches, or their own row. Its administrator term does not
                        // arise here — an admin's row is a sentence, not a table (see
                        // AdminSettingsBackingViewModel) — so this is only ever read
                        // for a non-admin. Computed off the two maps already in hand
                        // rather than a per-user effectiveRole call, which would be
                        // two queries per (user, project).
                        canSeeProject = own != null ||
                            audiencesByProject[project.id].orEmpty().keys.any {
                                user.storedInstanceRole.atLeast(it.instanceRole)
                            },
                    )
                },
            )
        },
    )
}
