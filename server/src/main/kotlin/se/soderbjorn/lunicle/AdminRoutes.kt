/**
 * The routes behind the settings pane's three instance-wide tabs (LNL-195): the account
 * directory, the deployment-wide switches, admission, what a new project starts with,
 * and the project order.
 *
 * Same shape as the rest — **parse, AUTHORIZE, respond** — and the whole surface belongs
 * to whoever runs the instance, so there is no narrowed half of the response to get wrong:
 * [ProjectSettingsRoutes] has to draw a line through the middle of one payload (an
 * administrator gets the vocabularies, everybody else gets their own notification toggle
 * and nothing else), where [adminCaller] simply refuses.
 *
 * ── Three gates, not one (LNL-195, LNL-198) ─────────────────────────────────
 *
 * [adminCaller] is an **instance administrator**, and every route here but three takes it:
 * reading the directory, admission, the per-tier permissions, the policy switches and what
 * a new project starts with are the job of the role. Two exceptions take
 * [projectSetCaller], which is the **owner** — reordering every board on the deployment
 * and deleting somebody else's are what LNL-191 narrowed on purpose, and the response says
 * which of the two the caller is on `canReorderProjects` so the screen greys those controls
 * rather than letting an administrator collect a 403.
 *
 * The third is [ownershipCaller], on the one route that hands the deployment away
 * (LNL-198). Also the owner, and a separate gate rather than a reuse of the second because
 * the two are different questions that happen to have the same answer — see
 * `AccessControl.canHandOverInstance`, and note that this is the one gate whose widening
 * would collapse every narrowing above it.
 *
 * Every route here used the owner gate until LNL-195, which made the three instance tabs
 * owner-only *by accident* — the client offers them to any administrator, so an
 * administrator saw three tabs of empty headings and a refusal.
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
import se.soderbjorn.lunicle.clientserver.AudienceGrant
import se.soderbjorn.lunicle.clientserver.AudienceRow
import se.soderbjorn.lunicle.clientserver.DeploymentFacts
import se.soderbjorn.lunicle.clientserver.HandOverInstanceRequest
import se.soderbjorn.lunicle.clientserver.InstanceOwnership
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
import se.soderbjorn.lunicle.clientserver.OwnerCandidate
import se.soderbjorn.lunicle.clientserver.ProjectOrder
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.RungOption
import se.soderbjorn.lunicle.clientserver.SetAdmissionPolicyRequest
import se.soderbjorn.lunicle.clientserver.SetInstanceSettingRequest
import se.soderbjorn.lunicle.clientserver.TierCard

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
     * each tier of signed-in person may do (LNL-192). An instance administrator's.
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
     * Set who may hold an account on this deployment (LNL-192). An administrator's.
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
     * Say what one audience arrives as in a **newly created** project (LNL-195). An
     * administrator's.
     *
     * ── The one refusal here, and why it is the same one a project makes ─────
     *
     * The guest row answers to the public-projects veto. A deployment that has forbidden
     * itself from publishing boards must not be able to publish every *future* board
     * through this setting instead, so the refusal is made here as well as on a
     * project's own audience write — and it carries the same kind of sentence the
     * greying beside the row shows, rather than a second explanation of the same rule.
     *
     * An unknown audience or rung key is a malformed request rather than a silent no-op:
     * a client that has invented a key has a bug, and answering 200 to it would hide it.
     */
    post(ApiRoutes.ADMIN_NEW_PROJECT_AUDIENCE) {
        val admin = call.adminCaller(deps, "change what a new project starts with") ?: return@post
        val body = call.receiveOrNull<AudienceGrant>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        val audience = Audience.byKey(body.audienceKey) ?: run {
            call.respond(HttpStatusCode.BadRequest, "No such audience.")
            return@post
        }
        // Null is "hand them nothing", which is a legitimate state and not a bad key —
        // so only a non-null key that fails to resolve is a refusal.
        val rung = body.roleKey?.let { key ->
            ProjectRole.byKey(key) ?: run {
                call.respond(HttpStatusCode.BadRequest, "No such role.")
                return@post
            }
        }
        if (audience == Audience.GUEST && rung != null && !deps.instanceSettings.current().allowPublicProjects) {
            call.respond(
                HttpStatusCode.Conflict,
                "This deployment does not allow a project to be made public, so a new project " +
                    "cannot start out admitting guests.",
            )
            return@post
        }
        deps.instanceSettings.setNewProjectAudience(audience, rung)
        logger.info(
            "New-project audience ${audience.key} set to ${rung?.key ?: "(none)"} by admin ${admin.id}",
        )
        call.respond(deps.buildAdminSettings(admin))
    }

    /**
     * Hand this deployment to another account (LNL-198). **The instance owner's alone.**
     *
     * ── One write to ownership, and a second row that is not part of it ──────
     *
     * Ownership is `instance_settings.owner_user_id`, a single-valued setting, so moving
     * it is **one** write and "exactly one owner, always" stays structural on both
     * backends — see `InstanceSettings.ownerUserId`. There is deliberately no
     * demote-then-promote pair here; a two-step could half-fail and leave a deployment
     * with two owners or none.
     *
     * The outgoing owner still needs `users.instance_role = 'admin'`, though, so they do
     * not drop out of administration entirely — and that *is* a second row, in a
     * different table, with no transaction spanning the two. So the order is chosen for
     * what a failure between them leaves behind:
     *
     *  1. **flag the outgoing owner as an administrator**, then
     *  2. **move ownership.**
     *
     * A failure after (1) changes nothing anybody can observe: ownership has not moved,
     * and the owner — who is senior to an administrator anyway — is now also flagged as
     * one, which is what re-running this will want and what `seatInstanceOwner` already
     * relies on. The retry is the same two writes. Reversed, a failure between them would
     * leave the new owner seated and the old owner demoted all the way to staff or member
     * — stripped of administration by a crash, which is precisely the outcome the second
     * write exists to prevent.
     *
     * The incoming owner gets no row written at all. Ownership is senior to
     * [InstanceRole.ADMIN], so the flag would grant them nothing today; giving it to them
     * would only pre-decide what they fall back to if they ever hand the instance on,
     * which is that handover's business and not this one's.
     *
     * ── The refusals ────────────────────────────────────────────────────────
     *
     * Eligibility is re-derived here, from the store and the deployment's own domain,
     * exactly as [ownership] derives the list the picker renders — so a hand-written
     * request naming an ineligible account is refused with the sentence the screen would
     * have shown, rather than a bare "no". See [mayBeHandedTheInstance].
     */
    post(ApiRoutes.ADMIN_OWNERSHIP) {
        val owner = call.ownershipCaller(deps, "hand this instance over") ?: return@post
        val body = call.receiveOrNull<HandOverInstanceRequest>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        val successor = deps.users.findById(body.userId) ?: run {
            call.respond(HttpStatusCode.NotFound, "No such account.")
            return@post
        }
        // Not an error worth a special sentence, but not a silent no-op either: a request
        // that would leave everything exactly as it is has misunderstood something.
        if (successor.id == owner.id) {
            call.respond(HttpStatusCode.Conflict, "You already own this instance.")
            return@post
        }
        if (!successor.mayBeHandedTheInstance(deps.identity)) {
            call.respond(HttpStatusCode.Conflict, ineligibleReason(successor, deps.identity))
            return@post
        }
        // (1) then (2) — see this route's doc for what a failure between them leaves.
        deps.users.setInstanceAdmin(owner.id, true)
        deps.instanceSettings.setOwnerUserId(successor.id)
        logger.info("Instance handed over from ${owner.id} to ${successor.id} (${successor.resolvedName})")
        // Built for the caller, who is an administrator now rather than the owner — so the
        // response they read is the one that puts the button away and moves the row's name.
        call.respond(deps.buildAdminSettings(owner))
    }

    /**
     * Put the instance's projects in a given order.
     *
     * **The instance owner's**, not an administrator's — see [projectSetCaller], and
     * AccessControl.canMutateProjects for why the two are not the same trust. The order is
     * the whole list, not a
     * delta, and [ProjectRepository.reorder] refuses one that does not name exactly
     * the projects that exist — a stale dialog that has lost or gained a project is
     * told so rather than half-applied. Answers with the refreshed directory so the
     * dialog never merges two objects.
     */
    post(ApiRoutes.ADMIN_PROJECT_ORDER) {
        val admin = call.projectSetCaller(deps, "reorder projects") ?: return@post
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
     * Delete a project from the instance's own list (LNL-93).
     *
     * **The instance owner's**, like the order above: this is the cross-project delete, for
     * a board the caller may hold nothing in. A board's *own* owner deletes it from its
     * General section instead (LNL-194), which is a different gate on the same act — and
     * both are permanent, with no trash, so both ask for a typed phrase first. Answers with
     * the refreshed directory.
     */
    delete("${ApiRoutes.ADMIN_PROJECTS}/{id}") {
        val admin = call.projectSetCaller(deps, "delete any project on this instance") ?: return@delete
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
    if (user == null || !deps.access.canAdministerInstance(user)) {
        respond(HttpStatusCode.Forbidden, "Only an instance administrator can $action.")
        return null
    }
    return user
}

/**
 * Resolve a caller who may change the *set* of projects, or respond and return null
 * (LNL-195).
 *
 * A second, stricter gate beside [adminCaller], and the pair is the point. Reading the
 * directory and flipping a policy switch is an administrator's; reordering every board on
 * the deployment and deleting somebody else's is the **owner's** — LNL-191 narrowed that
 * deliberately, and this is what keeps the narrowing while the rest of the surface opens
 * back up to administrators. See AccessControl.canMutateProjects, which says at length
 * why those are not the same trust.
 *
 * The refusal names the owner rather than saying "forbidden", because an administrator who
 * reaches it is not doing anything wrong — they are being told whose it is. The screen
 * greys the arrows and the Delete on the same fact, so this is very hard to reach; it is
 * the enforcement, and the greying is the explanation.
 */
private suspend fun ApplicationCall.projectSetCaller(
    deps: BoardDependencies,
    action: String,
): UserRecord? {
    val user = caller(deps)
    if (user == null || !deps.access.canMutateProjects(user)) {
        respond(HttpStatusCode.Forbidden, "Only the instance owner can $action.")
        return null
    }
    return user
}

/**
 * Resolve a caller who may hand the deployment away, or respond and return null
 * (LNL-198).
 *
 * A third gate beside [adminCaller] and [projectSetCaller], and it is a third rather than
 * a reuse of the second on purpose. The two answer identically today —
 * `AccessControl.canMutateProjects` and `canHandOverInstance` are both "the owner" — and
 * they are different questions: one is about the project list, one is about who owns the
 * place. A gate that borrowed the other's name would follow it the next time it moved,
 * and this is the one gate that must never widen by accident.
 *
 * The refusal names the owner, like [projectSetCaller]'s: an administrator reaching this
 * is not doing anything wrong, they are being told whose it is. The screen shows them the
 * ownership row with no button at all, so this is very hard to reach by hand — which is
 * exactly why it is worth having, and worth a test that calls it directly.
 */
private suspend fun ApplicationCall.ownershipCaller(
    deps: BoardDependencies,
    action: String,
): UserRecord? {
    val user = caller(deps)
    if (user == null || !deps.access.canHandOverInstance(user)) {
        respond(HttpStatusCode.Forbidden, "Only the instance owner can $action.")
        return null
    }
    return user
}

/**
 * Could this account be handed the instance (LNL-198)?
 *
 * **Staff who have signed in**, and nobody else. The one rule, read by the write and by
 * the list the picker renders, so the affordance cannot offer somebody the route would
 * refuse.
 *
 * Three conditions, and each rules out a real account:
 *
 *  - **[UserKind.STAFF]** — their address is on the deployment's own domain, so the
 *    deployment vouches for them. A member is somebody from outside it.
 *  - **[UserRecord.hasSignedIn]** — somebody has actually turned up holding the address.
 *    A row an administrator added ahead of time (LNL-194) holds rungs and is perfectly
 *    real, and ownership of a whole deployment must not land on an address that was typed
 *    once into a dialog and never claimed.
 *  - **[InstanceIdentity.hasStaffTier]** — the deployment still names a domain. Redundant
 *    against the first condition on a settled instance, because `UserKind.forEmail`
 *    returns [UserKind.MEMBER] whenever there is no domain and the startup stamp
 *    re-derives every row. It is here because `kind` is a stored column and this is not:
 *    a deployment whose `brand.json` lost its domain has stale STAFF rows until it next
 *    boots, and reading the live configuration means this rule cannot be satisfied by a
 *    row the configuration no longer agrees with.
 *
 * A previewed address ([UserRecord.isPreviewOnly]) cannot reach here — it is never in
 * `selectAll` and `findById` cannot resolve it — but it would fail [UserRecord.hasSignedIn]
 * anyway, which is the conservative direction.
 */
private fun UserRecord.mayBeHandedTheInstance(identity: InstanceIdentity): Boolean =
    identity.hasStaffTier && kind == UserKind.STAFF && hasSignedIn

/**
 * Why this account cannot be handed the instance, in the words the screen uses.
 *
 * The route's refusal rather than a bare 409, for the reason [ApiRoutes.ADMIN_ADMISSION]'s
 * does: somebody who reaches an enforcement the screen was supposed to keep them away from
 * should read the same explanation the screen would have given, not a second one.
 */
private fun ineligibleReason(user: UserRecord, identity: InstanceIdentity): String = when {
    !identity.hasStaffTier ->
        "This deployment names no domain of its own, so no account here is staff and there " +
            "is nobody it can be handed to."
    user.kind != UserKind.STAFF ->
        "${user.resolvedName} is a member here rather than staff, so the instance cannot be " +
            "handed to them. Only an account on ${identity.domain} can own this deployment."
    else ->
        "Nobody has ever signed in to ${user.resolvedName}'s account, so the instance cannot " +
            "be handed to it. An address that has never been claimed cannot own a deployment."
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

    // Every instance-wide setting, read together in one query. The write returns this
    // whole state, so a toggle re-renders every tab from the server's answer rather
    // than patching its own copy — the rule every write here follows.
    val switches = instanceSettings.current()
    val allUsers = users.selectAll()

    return AdminSettingsState(
        // The stored policy AND this deployment's verdict on each of the three
        // choices, computed here so the screen renders what it is handed. A client
        // that re-derived the greying would need to be shown the brand manifest, the
        // mail configuration and two environment variables, and would be a second copy
        // of the rule the write above enforces. See InstanceIdentity.admissionState.
        admission = identity.admissionState(switches.admission),
        // The inputs that greying is computed from, so an administrator can see why a
        // choice is dead rather than having to guess at a file they cannot read.
        deployment = DeploymentFacts(
            staffDomain = identity.domain,
            waysIn = identity.waysIn,
            googlePin = identity.googleHostedDomainPin,
            brandName = brandName,
        ),
        // One card per tier that exists here. Members always; Staff only where a domain
        // makes the tier real — a card for a tier nobody can be in is two switches over
        // an empty set. Guests never: a guest has no account to permit.
        //
        // Both counts below ask `storedInstanceRole` and are meant to: these are TIER
        // reads, not authority reads (LNL-201). Ownership is orthogonal to a tier — the
        // owner is also staff or a member, and which one is exactly what these two cards
        // are counting. Folding ownership in here would take the owner out of the card
        // whose per-tier switches still govern them.
        tiers = buildList {
            if (identity.hasStaffTier) {
                add(
                    TierCard(
                        key = InstanceRole.STAFF.key,
                        title = "Staff",
                        subtitle = "Accounts on ${identity.domain}.",
                        accountCount = allUsers.count { it.storedInstanceRole == InstanceRole.STAFF },
                        mayCreateProjects = switches.staffMayCreateProjects,
                        mayUseAgents = switches.staffMayUseAgents,
                        createKey = InstanceSettingKey.STAFF_MAY_CREATE_PROJECTS,
                        agentsKey = InstanceSettingKey.STAFF_MAY_USE_AGENTS,
                    ),
                )
            }
            add(
                TierCard(
                    key = InstanceRole.MEMBER.key,
                    title = "Members",
                    subtitle = if (identity.hasStaffTier) {
                        "Every other account — people from outside ${identity.domain}."
                    } else {
                        "Everybody with an account here. This deployment names no domain of " +
                            "its own, so there is no staff tier to tell them from."
                    },
                    accountCount = allUsers.count { it.storedInstanceRole == InstanceRole.MEMBER },
                    mayCreateProjects = switches.memberMayCreateProjects,
                    mayUseAgents = switches.memberMayUseAgents,
                    createKey = InstanceSettingKey.MEMBER_MAY_CREATE_PROJECTS,
                    agentsKey = InstanceSettingKey.MEMBER_MAY_USE_AGENTS,
                ),
            )
        },
        // What a new project starts with, in the same [AudienceRow] shape a project's own
        // Access list uses — so the two read as the same control, which is the point: this
        // is literally the list a new project is created with. The guest row carries the
        // publish veto's greying, because the write above refuses it for the same reason.
        newProjectAudiences = Audience.entries
            .filter { it != Audience.STAFF || identity.hasStaffTier }
            .map { audience ->
                val vetoed = audience == Audience.GUEST && !switches.allowPublicProjects
                AudienceRow(
                    key = audience.key,
                    title = audience.adminTitle,
                    subtitle = audience.adminSubtitle(identity.domain),
                    roleKey = switches.newProjectAudiences[audience]?.key,
                    isSelectable = !vetoed,
                    unavailableReason = if (vetoed) {
                        "This deployment does not allow a project to be made public, so a new " +
                            "one cannot start out admitting guests. The Policy switch on the " +
                            "Instance tab is what changes that."
                    } else {
                        null
                    },
                )
            },
        // The rung vocabulary, sent rather than compiled into the bundle — the same list
        // and the same type a project's Access section is handed, so the two surfaces
        // cannot describe a rung differently. Every rung is selectable here: this caller
        // holds the instance, and there is no project to be junior on.
        rungs = ProjectRole.entries.map {
            RungOption(key = it.key, label = it.label, description = it.description, isSelectable = true)
        },
        // The one narrowed capability on these three tabs: the project order and the
        // cross-project delete are the OWNER's, where everything else here is an
        // administrator's. Sent so the arrows grey with a reason rather than 403ing on
        // click — see projectSetCaller, which enforces the same answer.
        canReorderProjects = access.canMutateProjects(caller),
        // Parenthesised, and it matters: `"a" + "b".takeIf { … }` binds the takeIf to the
        // second literal alone, so a caller who MAY reorder would be handed "a" + "null" —
        // a read-only banner over a live list. buildAccess was bitten by exactly this.
        projectSetReadOnlyReason = (
            "The order of every board, and deleting one from here, is the instance owner's. " +
                "Yours is a board's own Delete, in its General section."
            ).takeIf { !access.canMutateProjects(caller) },
        // Who holds this instance, who administers it alongside them, and — for the owner
        // alone — who it could be handed to. See ownership.
        ownership = ownership(caller, switches.ownerUserId, allUsers, identity),
        allowPublicProjects = switches.allowPublicProjects,
        hideDisplayName = switches.hideDisplayName,
        // The instance's projects, in the arranged order `selectAll` now returns — the
        // same list `allProjects` above already holds, mapped to the wire type the
        // Instance tab reorders and deletes from. See AdminSettingsState.projects.
        projects = allProjects.map {
            ProjectSummary(
                id = it.id,
                name = it.name,
                namePrefix = it.namePrefix,
                // OWNER for every row: this list is only ever built for a caller who
                // holds the instance, and AccessControl.effectiveRole gives an instance
                // administrator or owner that rung on every project. See ProjectSummary.
                roleKey = ProjectRole.OWNER.key,
                roleLabel = ProjectRole.OWNER.label,
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
        users = allUsers.sortedWith(compareByDescending { it.isInstanceAdmin }).map { user ->
            // Ownership is a setting rather than a column, so no UserRecord carries it —
            // which is why the owner's tier is folded in here rather than in
            // storedInstanceRole. This was the inline spelling of instanceRoleWith and is
            // now the function (LNL-201); `switches` is the read it needs, already done.
            val tier = user.instanceRoleWith(switches.ownerUserId)
            AdminUser(
                userId = user.id,
                name = user.resolvedName,
                // The one field here that does not cross in ProjectMember. See
                // AdminUser's doc for why it does here — this response reaches
                // admins only, and telling two same-named accounts apart is the
                // screen's job.
                email = user.email,
                tierLabel = tier.adminLabel,
                isSysAdmin = tier.atLeast(InstanceRole.ADMIN),
                isSelf = user.id == caller.id,
                // A grant nobody has claimed looks exactly like one that has (LNL-194's
                // users.signed_in_at). Never expires and is never swept — see AdminUser.
                hasSignedIn = user.hasSignedIn,
                // Read-only, and derived: the permission is per tier, so this reports
                // which side of the tier cards an account falls on rather than a box an
                // administrator ticks for them. Answered off the snapshot already in hand
                // rather than a read per account.
                isMcpAllowed = switches.permitsAgents(tier),
                // Read-only too — the person's own answer, reported so an administrator
                // can see why a freshly-permitted account still has no agent running.
                isMcpEnabled = user.isMcpEnabled,
                projects = allProjects.map { project ->
                    val own = rungsByProject[project.id]?.get(user.id)
                    // What their audience gives them anyway, by the same one-comparison
                    // rule AccessControl.effectiveRole uses: the instance ladder ascends,
                    // so "matches this audience" is `their rank >= the audience's`.
                    val floor = audiencesByProject[project.id].orEmpty()
                        .filterKeys { tier.atLeast(it.instanceRole) }
                        .entries
                        .maxByOrNull { it.value.rank }
                    // Computed off the two maps already in hand rather than a per-user
                    // effectiveRole call, which would be two queries per (user, project).
                    val effective = when {
                        tier.atLeast(InstanceRole.ADMIN) -> ProjectRole.OWNER
                        else -> listOfNotNull(own, floor?.value).maxByOrNull { it.rank }
                    }
                    AdminProjectRights(
                        projectId = project.id,
                        projectName = project.name,
                        roleKey = own?.key,
                        effectiveRoleKey = effective?.key,
                        // Only when the audience is actually carrying some of the weight.
                        // Somebody whose own row is already senior to their audience is
                        // effectively their own row, and saying otherwise would be a
                        // sentence restating the rung beside it.
                        viaAudience = floor
                            ?.takeIf { !tier.atLeast(InstanceRole.ADMIN) && it.value.rank >= (own?.rank ?: -1) }
                            ?.let { "the ${it.key.adminTitle.lowercase()} row" },
                    )
                },
            )
        },
    )
}

/**
 * Who owns this instance, who administers it alongside them, and who it could be handed
 * to.
 *
 * The owner is resolved from the setting rather than from a column, because that is
 * where ownership lives — see `InstanceSettings.ownerUserId` for why it is a setting and
 * not a third value on `users.instance_role`. A stored id naming nobody (an owner whose
 * account was deleted) reads as "nobody owns this", which is the honest answer and is
 * the vacancy the startup pass re-seats into.
 *
 * The successor list is computed **only for the owner** (LNL-198). It is not a secret so
 * much as an answer to a question nobody else is asking: a non-owner cannot use it, and a
 * directory of eligible successors on every administrator's response would be a list with
 * no purpose. See [OwnerCandidate] for who is on it.
 */
private fun ownership(
    caller: UserRecord,
    ownerUserId: Long?,
    allUsers: List<UserRecord>,
    identity: InstanceIdentity,
): InstanceOwnership {
    val owner = ownerUserId?.let { id -> allUsers.firstOrNull { it.id == id } }
    val isSelf = owner != null && owner.id == caller.id
    // In `selectAll`'s order, which is by resolved display name — so two owners looking at
    // the same instance are offered the same list in the same order.
    val candidates = if (isSelf) {
        allUsers
            .filter { it.id != caller.id && it.mayBeHandedTheInstance(identity) }
            .map { OwnerCandidate(userId = it.id, name = it.resolvedName, email = it.email) }
    } else {
        emptyList()
    }
    return InstanceOwnership(
        ownerName = owner?.resolvedName,
        ownerEmail = owner?.email,
        isOwnerSelf = isSelf,
        // Everybody else who runs the place. The owner is named on their own row above,
        // so listing them twice would read as two people.
        adminNames = allUsers.filter { it.isInstanceAdmin && it.id != owner?.id }.map { it.resolvedName },
        // The owner's, and nobody else's — see AccessControl.canHandOverInstance. True even
        // with an empty candidate list, deliberately: see InstanceOwnership.canHandOver.
        canHandOver = isSelf,
        handOverBlockedReason = "Only the instance owner can hand it over.".takeIf { !isSelf },
        handOverCandidates = candidates,
        // Shown where the picker would be, so the dialog holds either a list or a reason.
        // Two different nothings, and they send an owner to two different places: a
        // deployment with no domain has a `brand.json` to change, where one whose staff
        // have not arrived yet has people to wait for.
        handOverEmptyReason = when {
            !isSelf || candidates.isNotEmpty() -> null
            !identity.hasStaffTier ->
                "There is nobody to hand this instance to. Only staff — accounts on the " +
                    "deployment's own domain — can own it, and this deployment names no domain, " +
                    "so every account here is a member. That is deploy-time configuration " +
                    "(brand.json), not a setting on this screen."
            else ->
                "There is nobody to hand this instance to. Only an account on ${identity.domain} " +
                    "that somebody has actually signed in to can own it — an address added ahead " +
                    "of time and never claimed cannot."
        },
    )
}

/** What to call an instance rung on the People tab. One word where one will do. */
private val InstanceRole.adminLabel: String
    get() = when (this) {
        InstanceRole.GUEST -> "Guest"
        InstanceRole.MEMBER -> "Member"
        InstanceRole.STAFF -> "Staff"
        InstanceRole.ADMIN -> "Instance admin"
        InstanceRole.OWNER -> "Instance owner"
    }

/**
 * What to call an audience on the Who-gets-in tab, and who they are.
 *
 * Deliberately the same words `ProjectSettingsRoutes` uses for a project's own audience
 * rows: this setting *is* that list, one project earlier, and two spellings of "Guests"
 * would suggest two different sets of people. They are separate private extensions
 * rather than one shared helper because the subtitle differs — here it has to say that
 * the row is a starting point rather than a current fact.
 */
private val Audience.adminTitle: String
    get() = when (this) {
        Audience.GUEST -> "Guests"
        Audience.MEMBER -> "Members"
        Audience.STAFF -> "Staff"
    }

private fun Audience.adminSubtitle(domain: String?): String = when (this) {
    Audience.GUEST -> "Anybody at all, without signing in."
    Audience.MEMBER -> "Everybody with an account on this deployment."
    Audience.STAFF -> domain?.let { "Accounts on $it." } ?: "Accounts on this organisation's domain."
}
