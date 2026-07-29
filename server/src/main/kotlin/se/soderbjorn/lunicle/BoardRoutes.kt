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
import se.soderbjorn.lunicle.clientserver.ChildOrder
import se.soderbjorn.lunicle.clientserver.IssueAssignment
import se.soderbjorn.lunicle.clientserver.IssueDetail
import se.soderbjorn.lunicle.clientserver.IssueEventView
import se.soderbjorn.lunicle.clientserver.IssueDraft
import se.soderbjorn.lunicle.clientserver.IssueOrderUpdate
import se.soderbjorn.lunicle.clientserver.IssueParentUpdate
import se.soderbjorn.lunicle.clientserver.IssueRef
import se.soderbjorn.lunicle.clientserver.IssueSummary
import se.soderbjorn.lunicle.clientserver.IssueUpdate
import se.soderbjorn.lunicle.clientserver.NotificationSubscriptionRequest
import se.soderbjorn.lunicle.clientserver.ProjectListState
import se.soderbjorn.lunicle.clientserver.ProjectPermissionsView
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.ProjectUpdate
import se.soderbjorn.lunicle.clientserver.SprintItem
import se.soderbjorn.lunicle.clientserver.StatusItem
import se.soderbjorn.lunicle.clientserver.StatusUpdate
import se.soderbjorn.lunicle.clientserver.UserOption
import se.soderbjorn.lunicle.clientserver.VocabularyItem
import se.soderbjorn.lunicle.clientserver.isInlineImageType
import se.soderbjorn.lunicle.clientserver.isSandboxedDocumentType
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

/** `Content-Security-Policy`, spelled out for the same reason as the above. */
private const val CONTENT_SECURITY_POLICY = "Content-Security-Policy"

/** The bundle everything below needs. Passed as one object so the route signatures stay readable. */
class BoardDependencies(
    val access: AccessControl,
    val projects: se.soderbjorn.lunicle.store.ProjectStore,
    val projectRepository: se.soderbjorn.lunicle.store.ProjectProvisioning,
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
    val roles: se.soderbjorn.lunicle.store.RoleStore,
    /** The rules for changing what is in a project's vocabularies. */
    val vocabularies: se.soderbjorn.lunicle.store.VocabularyStore,
    /**
     * The Discussion tab's forums: reading them, and the rules for writing them.
     *
     * One collaborator rather than a store and a repository, unlike the
     * vocabularies above. Those have a second audience — the board and the issue
     * editor read them for reasons that have nothing to do with writing — and
     * forums do not, so a route that held the store as well would eventually
     * read through it on a path that was meant to go through a rule. See
     * ForumRepository, whose read methods exist for exactly this.
     */
    val forums: ForumRepository,
    /**
     * What is *in* those forums: posts, the flat comments on them, and the rules
     * for writing both.
     *
     * A second forum collaborator rather than more methods on [forums], because
     * the two answer to different gates — a forum is a project administrator's to
     * create, and a post is anybody's who can see the project — and one class
     * holding both sets of rules is how a route ends up under the wrong one. The
     * routes files are split along the same line; see ForumPostRoutes.
     */
    val forumPosts: ForumPostRepository,
    /**
     * Who can see a project, as a set.
     *
     * Read by exactly one thing today — the forum's `@` autocomplete, which offers
     * everyone who can see the project — and built to be read by LNL-60's
     * recipient picker as well, which needs the union across several projects. It
     * is a collaborator rather than a free function over the two stores, unlike
     * [mentionableUsersIn], because it will have a second caller with no
     * [BoardDependencies] to hand and no reason to grow one. See ProjectAudience.
     */
    val audience: ProjectAudience,
    /**
     * Private conversations and the messages in them: reading them, and the rules
     * for writing them.
     *
     * One collaborator rather than two stores and a repository, for [forums]'
     * reason: nothing outside `MessageRoutes` reads a conversation, so a route
     * that held the stores as well would eventually read through one on a path
     * that was meant to go through a rule. See ConversationRepository, whose read
     * methods exist for exactly this.
     *
     * Note what it is *not* beside: there is no conversation entry in
     * [ProjectAudience]'s neighbourhood here, because a conversation has no
     * project. It is the one thing in this bundle whose permission question is not
     * about one. See Conversations.sq.
     */
    val conversations: ConversationRepository,
    val labels: se.soderbjorn.lunicle.store.LabelStore,
    val components: se.soderbjorn.lunicle.store.ComponentStore,
    val statuses: se.soderbjorn.lunicle.store.StatusStore,
    val priorities: se.soderbjorn.lunicle.store.PriorityStore,
    val resolutions: se.soderbjorn.lunicle.store.ResolutionStore,
    /** The project's release versions, for the version pickers and the fix-version rule (LNL-134). */
    val versions: se.soderbjorn.lunicle.store.VersionStore,
    val sprints: se.soderbjorn.lunicle.store.SprintStore,
    /** Activating, completing and populating sprints — the verbs a vocabulary has no name for. */
    val sprintRepository: se.soderbjorn.lunicle.store.SprintStore,
    val issues: se.soderbjorn.lunicle.store.IssueStore,
    val issueRepository: IssueRepository,
    val comments: se.soderbjorn.lunicle.store.CommentStore,
    val attachments: se.soderbjorn.lunicle.store.AttachmentStore,
    val attachmentRepository: AttachmentRepository,
    /**
     * Live upload tickets, for the one attachment route that has no session.
     *
     * In memory and therefore not a store like the rest of these — see
     * [AttachmentTicketStore] for why that is safe here and exactly when it
     * would stop being.
     */
    val attachmentTickets: AttachmentTicketStore,
    val sessions: se.soderbjorn.lunicle.store.SessionStore,
    val users: se.soderbjorn.lunicle.store.UserStore,
    // Read on every request, to turn the session cookie into an EFFECTIVE user.
    // See Impersonations.
    val impersonations: Impersonations,
    /**
     * The deployment-wide settings (LNL-115, reshaped by LNL-192): admission,
     * whether projects may be published, and what each tier may do. Read by the
     * project routes to answer "may this caller create one", by the MCP gates, and
     * by the admin routes to show and set them all.
     *
     * Defaulted to an in-memory store for tests, which the notifiers' nullability
     * would suggest but is done with a real (if forgetful) object instead: a null
     * would make every reader guard, where an empty in-memory store simply answers
     * the defaults — every permission off, and anyone who can sign in admitted.
     * [Application.module] always passes the SQLite-backed one, because a switch
     * that forgot itself on redeploy is a switch nobody could trust. See
     * InMemoryInstanceSettingsStore.
     */
    val instanceSettings: se.soderbjorn.lunicle.store.InstanceSettingsStore = InMemoryInstanceSettingsStore(),
    /**
     * What this deployment says about itself: its own domain, whether the Google
     * chooser is pinned to it, and whether a mailed code is a way in (LNL-192).
     *
     * Deploy-time configuration read from `brand.json` at boot, never a stored
     * setting — which is why it is a value here and not a store. Read by the admin
     * routes alone today, to compute which admission policies this deployment can
     * honour; the sign-in path takes the same object by another route (see
     * `authRoutes`). Defaulted to the unbranded shape, which is what every test and
     * every default deployment is: no domain, no pin, and code sign-in available
     * if a transport is.
     */
    val identity: InstanceIdentity = InstanceIdentity(),
    /** Who wants an e-mail about which project or issue. */
    val subscriptions: se.soderbjorn.lunicle.store.SubscriptionStore,
    /**
     * How far each user has read, in each conversation and each forum.
     *
     * A store rather than a repository, unlike its neighbours [forums] and
     * [conversations], because there is genuinely no rule to hold: a mark is a
     * number that goes forwards, the question of *who may* mark anything is
     * answered by the same gates that guard reading the thing at all, and the two
     * routes that write one have already run them. See ReadStore.
     *
     * Defaulted for tests, which is the pattern the notifiers use — except that
     * this default is a real store over the real tables rather than a no-op,
     * because unread state is not a courtesy on top of a write: a test with a
     * silently-absent one would see every badge read zero and pass.
     */
    val reads: se.soderbjorn.lunicle.store.ReadStore,
    /**
     * The in-app notification list (LNL-109) — the store the bell and its sidebar
     * read, and the same one [notifications] writes through its dispatcher.
     *
     * Nullable and defaulted for tests, but the null is not otherwise reachable:
     * [Application.module] always constructs it, because a notification list is not
     * a courtesy on top of a write the way an e-mail is — it is the feature. When a
     * test leaves it null, [notificationRoutes] answers as if the caller owns
     * nothing (an empty list, a zero count), which is the same shape a signed-out
     * caller gets and lets a test that does not care about notifications ignore
     * them entirely. See NotificationStore.
     */
    val notificationStore: se.soderbjorn.lunicle.store.NotificationStore? = null,
    /**
     * Fires notification e-mails. Here as well as inside [issueRepository] on
     * purpose: the repository fires the events that pass through it — publish,
     * edit, new comment — while a status move goes through [issues] directly and
     * is fired from the route. Both hold the same instance, so there is one
     * notifier, not two. Defaults to [NoNotifications] for tests. See
     * NotificationService.
     */
    val notifications: IssueNotifier = NoNotifications,
    /**
     * Fires the private-message notification.
     *
     * A second notifier rather than a wider first one, and the split is described
     * at length in EmailNotifier's preamble: the two features share their
     * *plumbing* (`NotificationDispatcher`) and share nothing about what happened.
     * LNL-63 added a third alongside this one — see [forumNotifications] — and, as
     * predicted, needed no change to either.
     *
     * Unlike [notifications], this is held only here and not inside a repository:
     * a message is published on exactly one route, so there is nowhere else it
     * could be fired from. See MessageRoutes' `PUT`.
     *
     * Defaults to [NoMessageNotifications] for tests, exactly as above.
     */
    val messageNotifications: MessageNotifier = NoMessageNotifications,
    /**
     * Fires the forum's two notifications: a new post, and a new comment.
     *
     * The third notifier, and the last one LNL-30's plan calls for. It is here for
     * [messageNotifications]' reason rather than [notifications]': a post and a
     * comment are each published on exactly one route, so there is nowhere else it
     * could be fired from and a notifier inside `ForumPostRepository` would have to
     * be handed the project and the forum that the route has already resolved.
     *
     * Defaults to [NoForumNotifications] for tests, exactly as the two above.
     */
    val forumNotifications: ForumNotifier = NoForumNotifications,
    /**
     * Derives and writes an issue's history. Here as well as inside
     * [issueRepository] for exactly [notifications]' reason, and the parallel is
     * not a coincidence — the two have the same set of call sites, because they
     * answer the same question about the same writes. A drag and an "Assign to
     * me" reach [issues] directly and record from the route; a save passes
     * through the repository and records there. One instance, shared.
     *
     * Null in tests that have no interest in a history; nothing is recorded then.
     */
    val history: IssueHistory? = null,
    /**
     * Compiles and caches the statistics dialog's numbers.
     *
     * Null in tests with no interest in them, and — unlike [history] — that null
     * is also reachable in production by nothing at all: the repository needs no
     * external configuration to construct, because a project with no GitHub
     * repository linked is a supported state rather than an unconfigured one. So
     * [Application.module] always passes it, and the nullability here exists for
     * the tests. A null makes the statistics routes answer 404, which is the same
     * thing a project nobody may read answers — see StatisticsRoutes.
     */
    val statistics: se.soderbjorn.lunicle.store.StatisticsStore? = null,
    /**
     * The sender behind the `send_email` MCP tool, or null when this deployment
     * configured no mail.
     *
     * The same instance [notifications] holds, for the reason every other shared
     * dependency here is shared. Null is the ordinary local-dev state rather than
     * a fault, and unlike a notification — which is a courtesy on top of a write
     * that already happened, and so is logged and forgotten — the tool *refuses*
     * when this is null. An agent that was asked to send a report and cannot must
     * say so; silently logging it would have the agent report success for a mail
     * nobody will ever receive. See McpTools.sendEmail.
     *
     * Nothing but that tool reads it: there is no HTTP route that sends free-form
     * mail, which is [McpTools]' own doc's third exception and is discussed there.
     */
    val agentMail: EmailTransport? = null,
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
 * The `isSysAdmin` re-check is the important line. An admin who starts impersonating
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
    sessions: se.soderbjorn.lunicle.store.SessionStore,
    users: se.soderbjorn.lunicle.store.UserStore,
    impersonations: Impersonations,
): Caller {
    val sessionId = request.cookies[SESSION_COOKIE] ?: return Caller(null, null)
    val real = sessions.lookup(sessionId) ?: return Caller(null, null)

    val impersonation = impersonations.current(sessionId)
        ?: return Caller(effective = real, real = real)

    if (!real.isInstanceAdmin) {
        // Demoted while impersonating. Drop it and be themselves.
        impersonations.stop(sessionId)
        return Caller(effective = real, real = real)
    }
    return when (impersonation) {
        // A signed-out visitor has no account: the effective user is null, exactly
        // as it is for a caller with no cookie, so every AccessControl call sees the
        // public view. The real user stays the admin, so the impersonation is still
        // theirs to stop. See Impersonation.AsSignedOut (LNL-103).
        is Impersonation.AsSignedOut ->
            Caller(effective = null, real = real, isImpersonating = true)
        is Impersonation.AsUser -> {
            val target = users.findById(impersonation.userId) ?: run {
                // The impersonated account was deleted out from under them.
                impersonations.stop(sessionId)
                return Caller(effective = real, real = real)
            }
            Caller(effective = target, real = real, isImpersonating = true)
        }
    }
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
internal suspend fun ApplicationCall.readableProject(
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
 *
 * `internal` rather than file-private because the forum routes resolve their
 * authors the same way (LNL-61). One implementation, so a post's byline and an
 * issue's are the same sentence about the same person.
 */
internal suspend fun BoardDependencies.authorNames(authors: Collection<Author>): Map<Long, String> =
    // distinct() first: a board where one person filed forty issues would
    // otherwise be forty identical lookups. Small, but this is the one query in
    // the response that scales with the number of cards.
    authors.mapNotNull { it.accountId }.distinct()
        .mapNotNull { id -> users.findById(id)?.let { id to it.resolvedName } }.toMap()


/**
 * Everyone who may be handed an issue in [projectId], by name.
 *
 * The whole grants table plus the whole account list, rather than a query that
 * joins the two: an admin qualifies without holding the role at all — see
 * [AccessControl.canBeAssigned] — so no single `project_roles` select can answer
 * this. That is the same reason [AccessControl] short-circuits on `isSysAdmin`
 * everywhere else, surfacing here as two reads instead of one.
 *
 * Both reads are already one query each and this instance's account list is
 * small, so the cost is a constant rather than the per-issue N+1 it would be if
 * this were asked per card. It is asked once, per open of one issue's editor.
 *
 * Sorted by name so the dropdown reads the same way twice. `selectAll` orders by
 * name already; the filter preserves it, and this comment is here so a later
 * change to either notices that the order is load-bearing for the UI.
 *
 * `internal` rather than file-private because [McpTools] resolves an `assignee`
 * argument against this same set: "who may hold an issue here" must have exactly
 * one definition, or the dropdown and the tool would disagree about who exists.
 *
 * @return the records, for the caller to narrow. Deliberately not [UserOption]s:
 *   this returns [UserRecord]s with e-mail addresses on them, and the route above
 *   is responsible for sending onward only a name and an id — the same division
 *   [UserStore.selectAll] documents for the impersonation menu.
 */
internal suspend fun BoardDependencies.assignableUsers(projectId: Long): List<UserRecord> {
    val rungs = roles.rolesForProject(projectId)
    val audiences = roles.audienceRoles(projectId)
    return users.selectAll().filter { candidate ->
        candidate.effectiveRungAmong(rungs, audiences)?.atLeast(ProjectRole.CONTRIBUTOR) == true
    }
}

/**
 * Everyone who may be @mentioned in [projectId] — anyone holding *any* role
 * here, plus the admin, who holds every role everywhere.
 *
 * Deliberately a wider set than [assignableUsers], and the width is the point.
 * Assignment is "who can be given this work", which is one specific eligibility;
 * a mention is "who is involved with this project at all", and the person worth
 * pulling into a thread is very often somebody who comments but never holds an
 * issue. Narrowing this to the assignable set would produce an autocomplete that
 * silently omits half the people in the room.
 *
 * Same two-read shape as [assignableUsers] and for the same reason — an admin
 * qualifies without holding a role, so no single `project_roles` select answers
 * this — and the same cost note applies: asked once, when one issue's window
 * opens.
 *
 * @return the records. Narrowing to a name and an id is the caller's job, as
 *   ever: these carry e-mail addresses and the wire does not.
 */
internal suspend fun BoardDependencies.mentionableUsers(projectId: Long): List<UserRecord> =
    mentionableUsersIn(projectId, users, roles)

/**
 * [BoardDependencies.mentionableUsers], as a free function over the two stores
 * it actually needs.
 *
 * It exists in this shape because the second caller has no [BoardDependencies]
 * and should not grow one: [NotificationService] has to resolve `@Ada` to an
 * account when it decides who to mail, and the set it resolves against must be
 * *the same set* the autocomplete offered. Two definitions of "who may be
 * mentioned here" would be an autocomplete that suggests a name and a mailer
 * that then quietly declines to recognise it.
 */
internal suspend fun mentionableUsersIn(
    projectId: Long,
    users: se.soderbjorn.lunicle.store.UserStore,
    roles: se.soderbjorn.lunicle.store.RoleStore,
): List<UserRecord> {
    val rungs = roles.rolesForProject(projectId)
    val audiences = roles.audienceRoles(projectId)
    return users.selectAll().filter { candidate ->
        candidate.effectiveRungAmong(rungs, audiences) != null
    }
}

/**
 * [AccessControl.effectiveRole]'s rule, applied to a whole directory from two maps
 * already in hand.
 *
 * ── Why a second spelling of the rule exists at all ─────────────────────────
 *
 * Every *decision* goes through [AccessControl], one caller at a time, from the
 * session — that is this codebase's whole permission story and nothing here
 * changes it. The two callers above are not decisions: they build a **set** for a
 * dropdown, over every account on the instance, and asking `effectiveRole` per row
 * would be two queries per account to compute what two queries already know.
 *
 * So this is the same `max(audience, own row)` in the same order, over maps rather
 * than over reads, and it is written directly beneath its callers rather than
 * hidden in a helpers file so that a change to the rule and a change to this are
 * the same edit. It is deliberately *not* exported: nothing outside this file may
 * reach it, and nothing may decide a write with it.
 *
 * The instance owner is not consulted here, and does not need to be: an owner
 * reaches [ProjectRole.OWNER] everywhere, and the only thing these two sets are
 * used for is offering names in a picker — where an administrator already appears
 * by the first clause and the owner appears by whichever of the two routes seats
 * them. See ProjectAudience, which does read ownership, because its answer is a
 * *membership* claim rather than a picker.
 */
private fun UserRecord.effectiveRungAmong(
    rungs: Map<Long, ProjectRole>,
    audiences: Map<Audience, ProjectRole>,
): ProjectRole? {
    if (storedInstanceRole.atLeast(InstanceRole.ADMIN)) return ProjectRole.OWNER
    val fromAudience = audiences.entries
        .filter { storedInstanceRole.atLeast(it.key.instanceRole) }
        .maxByOrNull { it.value.rank }
        ?.value
    val own = rungs[id]
    return listOfNotNull(fromAudience, own).maxByOrNull { it.rank }
}

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

/** A refused version reference, carrying the sentence the client should show (LNL-134). */
internal class VersionRefusal(override val message: String) : Exception(message)

/**
 * Validate a fixed-version reference and enforce the "require a fixed version when
 * resolving" rule — [resolveResolution]'s twin, and shared by the editor's PUT and
 * the board's drag POST for the same reason: closing an issue by dragging it is the
 * common path, and a rule the editor enforced and the board did not would be a hole
 * exactly where it is used most.
 *
 * Two things, in order:
 *  - The version, if one is named, must belong to this project. The composite key
 *    would not catch a foreign version — `fixed_version_id` is single-column so it
 *    can be SET NULL (see Issues.sq) — so this is the only check there is.
 *  - A close that omits one is refused *only when* the project requires it and the
 *    resolution is a done one — closing as Duplicate or Won't fix never demands a
 *    fixed version (LNL-134). And only when the project has versions to pick: a
 *    requirement on a project with none would be an unsatisfiable trap, so it is a
 *    no-op there, exactly as the label requirement is on a project with no labels.
 *
 * [resolutionId] is the *resolved* resolution — null for a non-closing status — so
 * an ordinary move never triggers the requirement.
 *
 * @return the fixed-version id to store, or a [VersionRefusal] naming what is wrong.
 */
internal suspend fun BoardDependencies.resolveFixedVersion(
    projectId: Long,
    resolutionId: Long?,
    fixedVersionId: Long?,
): Result<Long?> {
    val projectVersions = versions.forProject(projectId)
    if (fixedVersionId != null && projectVersions.none { it.id == fixedVersionId }) {
        return Result.failure(VersionRefusal("That version does not belong to this project."))
    }
    if (fixedVersionId == null && resolutionId != null && projectVersions.isNotEmpty()) {
        val requires = projects.findById(projectId)?.requireFixedVersionOnResolve == true
        val isDoneResolution = resolutions.forProject(projectId).firstOrNull { it.id == resolutionId }?.isDone == true
        if (requires && isDoneResolution) {
            return Result.failure(VersionRefusal("Closing this issue as done needs a fixed version."))
        }
    }
    return Result.success(fixedVersionId)
}

/**
 * Validate a planned-version reference belongs to this project, or is null (LNL-134).
 *
 * No requirement attaches to a planned version — it is intent, not a gate — so this
 * is only the project-scope check `fixed_version_id`'s composite key cannot make.
 */
internal suspend fun BoardDependencies.resolvePlannedVersion(
    projectId: Long,
    plannedVersionId: Long?,
): Result<Long?> {
    if (plannedVersionId != null && versions.forProject(projectId).none { it.id == plannedVersionId }) {
        return Result.failure(VersionRefusal("That version does not belong to this project."))
    }
    return Result.success(plannedVersionId)
}

/** Mount every board route. Called by [Application.module]. */
fun Route.boardRoutes(deps: BoardDependencies) {
    projectRoutes(deps)
    // The Discussion tab's forums. Its own file for projectSettingsRoutes'
    // reason: every write in it runs one gate, which is worth being able to read
    // off a file rather than infer from four handlers among forty.
    forumRoutes(deps)
    // ...and what is in them. A separate file because it runs a different gate:
    // creating a forum is a project administrator's, posting in one is anybody's
    // who can see the project. See ForumPostRoutes' preamble.
    forumPostRoutes(deps)
    // The Messages tab. Its own file for the strongest version of the reason the
    // two above have one: every route in it runs a gate that appears nowhere else
    // in this server, because a conversation belongs to no project. See
    // MessageRoutes' preamble.
    messageRoutes(deps)
    // Read/unread state's one instance-wide read (LNL-64). Not folded into either
    // of the two above: it is not scoped to a forum the way ForumPostRoutes is, and
    // it is about the Discussion tab rather than about a conversation. See
    // UnreadRoutes.
    unreadRoutes(deps)
    // The in-app notification list (LNL-109). Instance-wide like unreadRoutes and
    // for the same reason — a notification belongs to a person, not a project — and
    // its own file because its routes run no project gate at all: a notification is
    // the caller's own, addressed by an id the store only returns to its owner. See
    // NotificationRoutes.
    notificationRoutes(deps)
    // The admin's half of a project: its vocabularies, and who may do what in it.
    // Its own file because every route in it is admin-only and enforces that
    // through one gate — see ProjectSettingsRoutes' preamble — which is a property
    // worth being able to read off a file rather than infer from six handlers
    // scattered among forty.
    projectSettingsRoutes(deps)
    // The admin's half of the *instance*, as opposed to of a project: who has an
    // account here, and who may bring an agent. Mounted from the same place and
    // off the same dependencies, because it reads the same three stores — but its
    // own file for the reason above, and one more: every route in it is admin-only
    // with no narrowed half at all, which is a stronger property than project
    // settings has and is worth not diluting.
    adminRoutes(deps)
    issueRoutes(deps)
    // The three sprint verbs a vocabulary has no name for, plus the per-issue
    // schedule. Its own file because its two permission gates are drawn in
    // different places from each other — see SprintRoutes' preamble.
    sprintRoutes(deps)
    // How much has been happening here. Its own file because its two gates are
    // drawn in different places — the numbers are readable by every reader, the
    // repository that feeds them is admin-only and configured elsewhere — which is
    // the same reason sprints have one.
    statisticsRoutes(deps)
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
                // The affordance and the POST gate below ask the same function,
                // which reads the per-tier setting itself (LNL-192) — so the button
                // and the route cannot disagree about who may create a board.
                canCreateProject = deps.access.canCreateProject(user),
            ),
        )
    }

    post(ApiRoutes.PROJECTS) {
        val user = call.caller(deps)
        // Asked again here rather than trusting the GET's affordance — the client
        // cannot be believed about what it may do (see AccessControl's preamble),
        // so the create gate re-derives from the instance setting and the session
        // on every request.
        if (!deps.access.canCreateProject(user)) {
            call.respond(
                HttpStatusCode.Forbidden,
                if (user == null) "You must sign in to create a project."
                else "Your account is not permitted to create a project here.",
            )
            return@post
        }
        val body = call.receiveOrNull<ProjectUpdate>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed project.")
            return@post
        }
        try {
            val created = deps.projectRepository.create(body.name, body.namePrefix)
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
        val id = call.longParam("id") ?: run {
            call.respond(HttpStatusCode.BadRequest, "Bad project id.")
            return@put
        }
        // Ownership, not the old system-administrator gate: renaming, re-scoping
        // and repository configuration are the owner's tier now (LNL-107). Asked
        // with the id in hand, since the answer is per-project. A 403 here rather
        // than the create route's, because the project exists to be owned.
        if (!deps.access.canOwnProject(user, id)) {
            call.respond(HttpStatusCode.Forbidden, "Only a project owner or system administrator can change a project.")
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
        // Parsed before the name write, so a project whose repository field was
        // mistyped is refused whole rather than renamed and then refused. The two
        // are one gesture on one form and should succeed or fail as one. The
        // existing config is handed along so a blank literal-token field is read as
        // "keep the stored token" rather than clearing it. See parseRepositoryConfig.
        val repositoryConfig = call.parseRepositoryConfig(body, deps.projects.repositoryConfig(id)) ?: return@put
        try {
            val updated = deps.projectRepository.update(id, body.name, body.namePrefix)
            deps.projects.setRepositoryConfig(id, repositoryConfig)
            call.respond(updated.toSummary())
        } catch (conflict: ProjectConflict) {
            call.respond(HttpStatusCode.Conflict, conflict.userMessage)
        }
    }

    delete("${ApiRoutes.PROJECTS}/{id}") {
        val user = call.caller(deps)
        val id = call.longParam("id") ?: run {
            call.respond(HttpStatusCode.BadRequest, "Bad project id.")
            return@delete
        }
        // An owner may destroy their own board (LNL-107); the instance-settings
        // delete that only a system administrator reaches is a separate route, over
        // in AdminRoutes. Both end in the same ProjectRepository.delete.
        if (!deps.access.canOwnProject(user, id)) {
            call.respond(HttpStatusCode.Forbidden, "Only a project owner or system administrator can delete a project.")
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
internal suspend fun BoardDependencies.buildBoard(project: ProjectRecord, user: UserRecord?): BoardState {
    val issueRows = issues.forProject(project.id)
    // Two queries for the whole board rather than two per card. See
    // IssueStore.labelsForProject.
    val labelsByIssue = issues.labelsForProject(project.id)
    val componentsByIssue = issues.componentsForProject(project.id)
    val names = authorNames(issueRows.map { it.author })
    val permissions = access.permissionsFor(user, project.id)

    // The epic/child indicators (LNL-154), computed here over the WHOLE project
    // rather than derived per card from the list this response carries — because
    // that list is not authoritative for either fact. `forProject` returns every
    // non-draft issue in the project (its only filter is `is_draft = 0`; no column,
    // sprint or status scoping, no paging — see Issues.sq), so `issueRows` IS the
    // project-wide set. Counting a parent's children over it therefore catches
    // children the reader has hidden or scoped out of their board view — the ones a
    // client counting over its own rendered list would silently miss. Same for the
    // parent's number: an id -> number map over the full set resolves "↳ LMX-98"
    // even when the parent itself is not among the cards the reader is looking at.
    // Draft rows are excluded, matching Issues.sq's `childrenOf` — a half-written
    // issue is its author's business, not a child anyone else's epic counts.
    val childCounts: Map<Long, Int> = issueRows.mapNotNull { it.parentId }.groupingBy { it }.eachCount()
    val numberById: Map<Long, Long> = issueRows.associate { it.id to it.number }

    return BoardState(
        project = project.toSummary(),
        statuses = statuses.forProject(project.id)
            .map { StatusItem(it.id, it.name, it.position.toInt(), it.requiresResolution) },
        priorities = priorities.forProject(project.id).map { StatusItem(it.id, it.name, it.position.toInt()) },
        resolutions = resolutions.forProject(project.id)
            .map { StatusItem(it.id, it.name, it.position.toInt(), isDone = it.isDone) },
        labels = labels.forProject(project.id).map { VocabularyItem(it.id, it.name) },
        components = components.forProject(project.id).map { VocabularyItem(it.id, it.name) },
        // Empty for every project that has never made one, and that emptiness is
        // the whole of what keeps a kanban board unchanged: the client renders no
        // scope control, no sprint field and no card menu item when this is empty.
        // See Sprints.sq.
        sprints = sprints.forProject(project.id)
            .map { SprintItem(it.id, it.name, it.position.toInt(), it.completedAt) },
        activeSprintId = projects.activeSprintId(project.id),
        // The address only, and never the token beside it — the client builds a
        // `#123` link out of it and has no use for anything else (LNL-178). Null
        // for the projects that have no repository configured, which is most of
        // them, and their `#123`s stay the text they always were.
        gitHubRepository = projects.repositoryConfig(project.id)?.repository?.toString(),
        // Empty unless somebody has made one — presence is the flag, like sprints.
        // The resolution dialog's fixed-version picker and the reusable version
        // dropdown read it off the board. See Versions.sq.
        versions = versions.forProject(project.id).map { VocabularyItem(it.id, it.name) },
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
                sprintId = issue.sprintId,
                parentId = issue.parentId,
                childCount = childCounts[issue.id] ?: 0,
                parentNumber = issue.parentId?.let { numberById[it] },
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
        // A NEW ticket must carry a label and/or a component when this project's
        // administrator has turned that on (LNL-106). Gated on isDraft — the first
        // publish — so switching a requirement on never blocks a later edit of an
        // issue filed before it existed. And only when there is something to pick:
        // a requirement on a project with no labels (or components) would make it
        // unfileable, so it is a no-op there rather than a trap the admin cannot see.
        if (issue.isDraft) {
            val project = deps.projects.findById(issue.projectId)
            if (project?.requireLabel == true && validLabels.isNotEmpty() && body.labelIds.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "This project requires a label on a new ticket.")
                return@put
            }
            if (project?.requireComponent == true && validComponents.isNotEmpty() && body.componentIds.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "This project requires a component on a new ticket.")
                return@put
            }
        }
        // The assignee is checked against the *subject's* rights, not the caller's:
        // this handler has already established the caller may edit the issue, and
        // the separate question is whether the person they named may hold one here.
        // Without this an editor could hand an issue to any account on the
        // instance, and the schema would not object — `assignee_id` cannot be
        // composite-keyed against the project. See Issues.sq.
        //
        // Null skips it, and means unassigned rather than unchanged. See IssueUpdate.
        // Bound to a local because a wire type's property is a different module's
        // public API and so cannot be smart-cast — the read below must be of the
        // same value the check was about.
        // Same shape of check as the assignee below and for the same schema
        // reason: `sprint_id` cannot be composite-keyed against the project (see
        // Issues.sq), so nothing in the database stops an issue being scheduled
        // into another project's sprint. Completed sprints are allowed here —
        // this is a whole-field-set save, so refusing one would make every other
        // edit to an issue in a finished sprint impossible.
        val requestedSprint = body.sprintId
        if (requestedSprint != null) {
            val validSprints = deps.sprints.forProject(issue.projectId).map { it.id }.toSet()
            if (requestedSprint !in validSprints) {
                call.respond(HttpStatusCode.BadRequest, "That sprint does not belong to this project.")
                return@put
            }
        }
        val requestedAssignee = body.assigneeId
        if (requestedAssignee != null) {
            val assignee = deps.users.findById(requestedAssignee)
            if (assignee == null || !deps.access.canBeAssigned(assignee, issue.projectId)) {
                // One answer for "no such account" and for "not assignable here",
                // as readableProject does: a distinct 404 would let anyone with
                // edit rights on one issue enumerate the instance's user ids.
                call.respond(HttpStatusCode.BadRequest, "That person cannot be assigned issues in this project.")
                return@put
            }
        }
        // Both version fields are validated against this project, and the fixed one
        // additionally against the fix-version rule — the same helper the drag
        // POST uses, so the editor and the board demand a fixed version in exactly
        // the same cases. `resolution` (already resolved above) is what decides
        // whether the requirement fires: an open save carries a null resolution and
        // never triggers it. See resolveFixedVersion.
        val plannedVersion = deps.resolvePlannedVersion(issue.projectId, body.plannedVersionId)
            .getOrElse { failure ->
                call.respond(HttpStatusCode.BadRequest, failure.message ?: "Bad version.")
                return@put
            }
        val fixedVersion = deps.resolveFixedVersion(issue.projectId, resolution, body.fixedVersionId)
            .getOrElse { failure ->
                call.respond(HttpStatusCode.BadRequest, failure.message ?: "Bad version.")
                return@put
            }

        deps.issueRepository.save(
            issue = issue,
            title = title,
            description = body.description,
            statusId = body.statusId,
            priorityId = body.priorityId,
            resolutionId = resolution,
            assigneeId = body.assigneeId,
            sprintId = body.sprintId,
            plannedVersionId = plannedVersion,
            fixedVersionId = fixedVersion,
            labelIds = body.labelIds,
            componentIds = body.componentIds,
            actorId = user?.id,
            actor = user.asAuthor(),
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
        // The fixed version the resolution dialog collected, validated and gated by
        // the same rule the editor uses — so dragging to Done is not the way around
        // a required fixed version (LNL-134). `resolution` is what decides whether
        // one is demanded; a move between open columns carries a null resolution and
        // is never asked for one.
        val fixedVersion = deps.resolveFixedVersion(issue.projectId, resolution, body.fixedVersionId)
            .getOrElse { failure ->
                call.respond(HttpStatusCode.BadRequest, failure.message ?: "Bad version.")
                return@post
            }
        deps.issues.setStatus(issue.id, body.statusId, resolution)
        // Written only on a close, and in the same gesture as the move — dragging a
        // card between open columns leaves the fixed version alone, but closing it
        // records (or clears, for a non-done resolution) the release it went out in.
        if (resolution != null) {
            deps.issues.setFixedVersion(issue.id, fixedVersion)
        }
        // A column move is an update to the issue like any other — a status move
        // goes through the store directly rather than issueRepository.save, so it
        // is fired here rather than there. See BoardDependencies.notifications.
        deps.notifications.issueUpdated(issue, user?.id, "moved")
        // `issue` is the record as it stood BEFORE the write above, which is what
        // lets this tell a real move from a card dropped back where it started.
        // See IssueHistory.recordStatusChanged.
        deps.history?.recordStatusChanged(issue, body.statusId, user.asAuthor(), agentName = null)
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
     *
     * `priorityId` moves the dragged issue into another group of the same column
     * first — a drag across a group header, which the board now honours (LNL-40).
     * It is applied BEFORE the integrity check, deliberately: the list being sent
     * is the group the card is landing in, so checking it against the group the
     * card is leaving would refuse every such drop. The move is authorised by the
     * same `canEditIssue` above, which is the check the editor's own priority
     * change goes through.
     */
    post("/api/issues/{id}/order") {
        val user = call.caller(deps)
        val existing = call.readableIssue(deps, user) ?: return@post
        if (!deps.access.canEditIssue(user, existing)) {
            call.respond(HttpStatusCode.Forbidden, "You cannot reorder this issue.")
            return@post
        }
        val body = call.receiveOrNull<IssueOrderUpdate>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed order.")
            return@post
        }

        // Land the priority change first, so everything below reads the issue as
        // it will be. `issue` is that issue from here on.
        val issue = when (val wanted = body.priorityId) {
            null, existing.priorityId -> existing
            else -> {
                if (existing.resolutionId != null) {
                    // A closing column groups by resolution, so a priority names
                    // no group there and the drop cannot mean what it says.
                    call.respond(HttpStatusCode.BadRequest, "A closed issue is not grouped by priority.")
                    return@post
                }
                if (deps.priorities.findByIdInProject(wanted, existing.projectId) == null) {
                    call.respond(HttpStatusCode.BadRequest, "No such priority in this project.")
                    return@post
                }
                deps.issues.setPriority(existing.id, wanted)
                deps.issues.findById(existing.id) ?: run {
                    call.respond(HttpStatusCode.NotFound, "That issue is gone.")
                    return@post
                }
            }
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

    /**
     * Subscribe or unsubscribe from this issue's update e-mails.
     *
     * Not `canEditIssue` — this is not an edit to the issue, it is the caller
     * managing their own inbox. Anyone who can *read* the issue may ask to hear
     * about it, so the gate is "signed in and the issue is readable", plus an
     * address to actually send to.
     */
    post("/api/issues/{id}/notification") {
        val user = call.caller(deps)
        val issue = call.readableIssue(deps, user) ?: return@post
        if (user == null) {
            call.respond(HttpStatusCode.Forbidden, "You must be signed in to change notifications.")
            return@post
        }
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
        deps.subscriptions.setIssueUpdateSubscription(user.id, issue.id, body.subscribed)
        call.respond(deps.buildIssueDetail(issue, user))
    }

    /**
     * Assign an issue — the read-mode "Assign to me" button, and its undo.
     *
     * ── The one route in this file with a permission of its own ───────────────
     *
     * Everything else that writes to an issue asks `canEditIssue`, and the drag
     * route's comment is emphatic that a lighter-feeling gesture does not earn a
     * lighter check. This is not a counter-example to that, and it is worth being
     * precise about why, because it looks like one.
     *
     * Dragging is a `status_id` write: the same column the editor writes, so the
     * same rule. Taking an issue is a write to a column the editor's rule was
     * never about — and the whole point of `be_assigned_issue` is that it names
     * people who are expected to pick work up *without* being able to rewrite it.
     * A shared check would collapse the two rights back into one and make the new
     * grant do nothing on its own.
     *
     * So there are two rules, and which applies depends on **who ends up holding
     * it**:
     *
     *  - Taking it yourself, or putting down something you are holding, needs only
     *    that you may be assigned here.
     *  - Naming somebody *else*, or clearing somebody else's assignment, is a
     *    decision about another person's workload: it needs edit rights on the
     *    issue, and the person named must be assignable here.
     *
     * Note what the first rule permits, because it is easy to read past: taking an
     * issue **somebody else is already holding** falls under it, so anyone who may
     * be assigned here can pick up work another person had. That follows the issue
     * as written — the button is offered to anyone with the right, and it says
     * nothing about the issue already being assigned — and it is the behaviour a
     * small team wants, where "I'll take that" is a thing people say. It is
     * deliberate rather than an oversight, and it is the line to change if
     * assignment should ever be something only a lead hands out.
     *
     * Note the second rule's second half is checked even for an admin, and that is
     * not redundant: `canBeAssigned` says yes to an admin, so an admin naming
     * another admin passes, while an admin naming an ordinary account with no
     * grant is refused. The refusal is the useful one — it is the typo case.
     */
    post("/api/issues/{id}/assignee") {
        val user = call.caller(deps)
        val issue = call.readableIssue(deps, user) ?: return@post
        if (user == null) {
            call.respond(HttpStatusCode.Forbidden, "You must be signed in to assign an issue.")
            return@post
        }
        val body = call.receiveOrNull<IssueAssignment>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed assignment.")
            return@post
        }

        // Bound to a local for the reason the PUT above binds one: a wire type's
        // property belongs to another module and cannot be smart-cast.
        val requested = body.assigneeId

        // "Am I only moving myself?" — taking it, or putting down my own. Anything
        // else touches another person and takes the stricter path below.
        val aboutSelfOnly = requested == user.id ||
            (requested == null && issue.assigneeId == user.id)

        if (aboutSelfOnly) {
            if (!deps.access.canBeAssigned(user, issue.projectId)) {
                call.respond(HttpStatusCode.Forbidden, "You cannot be assigned issues in this project.")
                return@post
            }
        } else {
            if (!deps.access.canEditIssue(user, issue)) {
                call.respond(HttpStatusCode.Forbidden, "You cannot change who this issue is assigned to.")
                return@post
            }
            if (requested != null) {
                val assignee = deps.users.findById(requested)
                if (assignee == null || !deps.access.canBeAssigned(assignee, issue.projectId)) {
                    // One answer for both, as in the PUT above: a distinct 404
                    // would make this an oracle for which user ids exist.
                    call.respond(
                        HttpStatusCode.BadRequest,
                        "That person cannot be assigned issues in this project.",
                    )
                    return@post
                }
            }
        }

        deps.issues.setAssignee(issue.id, requested)
        // An assignment is an update to the issue, and it goes through the store
        // directly rather than issueRepository.save — so the notification is fired
        // here, exactly as the drag route does. See BoardDependencies.notifications.
        deps.notifications.issueUpdated(issue, user.id, "assigned")
        // And the direct one, to the person who now has the work. Guarded on the
        // assignee actually changing, so that re-clicking a button whose state was
        // stale does not re-mail somebody; `issueAssigned` separately declines to
        // mail a self-assignment, which is the common case for this very route.
        if (requested != null && requested != issue.assigneeId) {
            deps.notifications.issueAssigned(issue, requested, user.id)
        }
        // Guarded on the same change, inside recordAssigneeChanged rather than
        // here: unassigning is a real event and would be dropped by the `!= null`
        // guard above, which exists only because there is nobody to mail.
        deps.history?.recordAssigneeChanged(issue, requested, user.asAuthor(), agentName = null)
        // Re-read, so the response carries the name that was just written rather
        // than the pre-write record's. The client renders the byline off this.
        val saved = deps.issues.findById(issue.id) ?: run {
            call.respond(HttpStatusCode.NotFound, "That issue no longer exists.")
            return@post
        }
        call.respond(deps.buildIssueDetail(saved, user))
    }

    /**
     * Attach an issue to an epic, or detach it (LNL-55).
     *
     * `canEditIssue` on **this** issue — the one being reparented. That is the
     * right subject even for the epic-side "add a child" gesture, because that
     * gesture posts here to the *child's* id: whoever adds FOO-9 to an epic is
     * editing FOO-9's place in the world, so it is FOO-9's edit right that gates
     * it. The parent's edit right is not asked — belonging to an epic is a fact
     * about the child, and an epic does not own who points at it.
     *
     * The same-project, one-level and no-cycle rules are IssueRepository's; a
     * refusal comes back as the 400 it returns, saying which rule.
     */
    post("/api/issues/{id}/parent") {
        val user = call.caller(deps)
        val issue = call.readableIssue(deps, user) ?: return@post
        if (!deps.access.canEditIssue(user, issue)) {
            call.respond(HttpStatusCode.Forbidden, "You cannot change this issue's parent.")
            return@post
        }
        val body = call.receiveOrNull<IssueParentUpdate>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        deps.issueRepository.setParent(issue, body.parentId).getOrElse { failure ->
            call.respond(HttpStatusCode.BadRequest, failure.message ?: "That parent is not allowed.")
            return@post
        }
        val saved = deps.issues.findById(issue.id) ?: run {
            call.respond(HttpStatusCode.NotFound, "That issue no longer exists.")
            return@post
        }
        call.respond(deps.buildIssueDetail(saved, user))
    }

    /**
     * Rank one epic's children, in the order the user dragged the arrows into
     * (LNL-55). `canEditIssue` on the epic — like every write, and like the board's
     * `/order`, reordering is an edit others see. The list is validated as exactly
     * this epic's children by IssueRepository; a stale one is a 400.
     */
    post("/api/issues/{id}/children/order") {
        val user = call.caller(deps)
        val issue = call.readableIssue(deps, user) ?: return@post
        if (!deps.access.canEditIssue(user, issue)) {
            call.respond(HttpStatusCode.Forbidden, "You cannot reorder this issue's children.")
            return@post
        }
        val body = call.receiveOrNull<ChildOrder>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed order.")
            return@post
        }
        deps.issueRepository.reorderChildren(issue, body.childIds).getOrElse { failure ->
            call.respond(HttpStatusCode.BadRequest, failure.message ?: "That order is not allowed.")
            return@post
        }
        call.respond(deps.buildIssueDetail(issue, user))
    }
}

/**
 * Resolve an issue whose project this caller may read, or respond and return
 * null.
 *
 * Every issue route starts here: an issue is only as readable as its project,
 * and there is no route that reaches one without asking this question first.
 */
internal suspend fun ApplicationCall.readableIssue(
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

internal suspend fun BoardDependencies.buildIssueDetail(issue: IssueRecord, user: UserRecord?): IssueDetail {
    val commentRows = comments.forIssue(issue.id)
    // Skipped for a draft, which by definition has none: its CREATED event is
    // written when it is published, so asking would be a query guaranteed to come
    // back empty on the one screen that opens most often — a new issue.
    val eventRows = if (issue.isDraft) emptyList() else history?.forIssue(issue.id).orEmpty()
    // One map for every account named anywhere on this response — the issue's
    // author, its comments', its history's, and the people its history says were
    // assigned — resolved in a single pass. History is where this matters most:
    // an issue with fifty events has fifty names to find, and almost all of them
    // are the same two or three people. See authorNames' distinct().
    //
    // An assignee is folded in as an Author.Account purely to reach that same
    // lookup. It is not a claim that they authored anything — the map is keyed on
    // account id and has no opinion about why an id is in it.
    val names = authorNames(
        commentRows.map { it.author } +
            eventRows.map { it.author } +
            eventRows.mapNotNull { it.valueUserId?.let(Author::Account) } +
            issue.author,
    )
    val canEdit = access.canEditIssue(user, issue)
    // Only for a caller who can actually change the field. A reader gets an empty
    // list rather than a filtered-in-the-browser one — see IssueDetail's
    // assignableUsers, and BoardRoutes' preamble on reads being narrowed.
    val assignable = if (canEdit) assignableUsers(issue.projectId) else emptyList()
    // Everyone who can be named in text this caller is able to write. Gated on
    // "can you write here at all" rather than on canEdit alone: a commenter who
    // may not touch the issue itself still types into an editor, and an
    // autocomplete that is empty for them is the feature simply missing.
    val canComment = access.canComment(user, issue.projectId)
    val mentionable = if (canEdit || canComment) mentionableUsers(issue.projectId) else emptyList()
    // Resolved separately from `names`, which only covers authors: the assignee is
    // very often somebody who has never written on this issue, so they would not
    // be in that map at all.
    val assigneeName = issue.assigneeId?.let { users.findById(it)?.resolvedName }
    // A compact reference to another issue in this project, with the caller's edit
    // right on *it* — so the client knows without a round-trip whether a child's
    // reorder/detach controls are live. See IssueRef.
    suspend fun IssueRecord.toRef() = IssueRef(
        id = id,
        number = number,
        title = title,
        statusId = statusId,
        resolutionId = resolutionId,
        canEdit = access.canEditIssue(user, this),
    )
    // The epic this belongs under, loaded once for the clickable chip. Same project
    // by construction (the parent route enforces it), so the reader's prefix serves.
    val parent = issue.parentId?.let { issues.findById(it)?.toRef() }
    val children = issues.childrenOf(issue.id).map { it.toRef() }
    // Candidates for the parent picker and the "add child" autocomplete — every
    // published issue of the project bar this one, for a caller who can edit here;
    // the client filters per gesture and the server re-checks on the write. Empty
    // for a reader, the assignableUsers narrowing. See IssueDetail.linkableIssues.
    val linkableIssues =
        if (canEdit) issues.forProject(issue.projectId).filter { it.id != issue.id }.map { it.toRef() }
        else emptyList()
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
        history = eventRows.map { event ->
            IssueEventView(
                id = event.id,
                kind = event.kind,
                // The snapshot, except for an assignee whose account still
                // exists — where the live name wins.
                //
                // That asymmetry is the one from IssueEvents.sq made concrete: a
                // status or label is named as it was *then*, because renaming a
                // column does not rewrite what happened on Tuesday; a person is
                // named as they are *now*, because renaming yourself does not
                // make you somebody else. `names` is empty for a deleted account,
                // and the stored snapshot is what is left — which is exactly the
                // case the two columns exist to keep legible.
                value = event.valueUserId?.let { names[it] } ?: event.value,
                values = event.values,
                authorName = event.author.displayName(names),
                agentName = event.agentName,
                createdAt = event.createdAt,
            )
        },
        canEdit = canEdit,
        canDelete = access.canDeleteIssue(user, issue),
        canComment = canComment,
        assigneeId = issue.assigneeId,
        assigneeName = assigneeName,
        // A name and an id, never the UserRecord: the records above carry e-mail
        // addresses, and this is the line they stop at. Same narrowing the
        // impersonation menu does; see UserStore.selectAll.
        assignableUsers = assignable.map {
            UserOption(id = it.id, name = it.resolvedName, isSelf = it.id == user?.id)
        },
        // Same narrowing, one list further. Sorted by name because selectAll is,
        // and the autocomplete reads better in a stable order than in one that
        // reshuffles as roles are granted.
        mentionableUsers = mentionable.map {
            UserOption(id = it.id, name = it.resolvedName, isSelf = it.id == user?.id)
        },
        canBeAssigned = access.canBeAssigned(user, issue.projectId),
        callerId = user?.id,
        sprintId = issue.sprintId,
        // Where this work could go: the open sprints, plus whichever one it is
        // already in even if that one is finished. Without the second half the
        // dropdown would have no entry matching `sprintId` and would fall back to
        // showing the issue as unscheduled — which is a lie about a real column.
        sprints = sprints.forProject(issue.projectId)
            .filter { it.isOpen || it.id == issue.sprintId }
            .map { SprintItem(it.id, it.name, it.position.toInt(), it.completedAt) },
        plannedVersionId = issue.plannedVersionId,
        fixedVersionId = issue.fixedVersionId,
        // Every version this project has, for the planned- and fixed-version
        // dropdowns. Sent with the issue rather than read off the board so a
        // deep-linked issue window has them, like `sprints`. Both fields draw from
        // this one list, and it stays empty for a project that never uses versions.
        versions = versions.forProject(issue.projectId).map { VocabularyItem(it.id, it.name) },
        // A signed-out reader has no subscription and nowhere to send, so both
        // fall to false without a query.
        notifyOnUpdates = user?.let { subscriptions.isSubscribedToIssueUpdates(it.id, issue.id) } ?: false,
        canReceiveEmailNotifications = user?.email != null,
        // Visible to every reader — who watches an issue is not a secret, only
        // where they'd be mailed is. See Subscriptions.sq.
        watchers = subscriptions.watchersForIssue(issue.id),
        parentId = issue.parentId,
        parent = parent,
        children = children,
        linkableIssues = linkableIssues,
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
        deps.issueRepository.saveComment(comment.id, body.body, actorId = user?.id)
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

/**
 * The project an attachment belongs to, whichever of the four owners it has.
 *
 * One function because the walk is the *only* thing that differs between the four
 * kinds, and every reader of an attachment needs the same answer from it: which
 * project's visibility governs these bytes. A second copy of this in the forum
 * routes would be a second place for the access question to be asked slightly
 * differently, which is exactly what AccessControl's preamble is about.
 *
 * @return null when the chain is broken at any link, which the callers treat as a
 *   404 — the same answer an unreadable project gets, deliberately.
 */
private suspend fun BoardDependencies.projectBehind(record: AttachmentRecord): ProjectRecord? {
    val issueId = record.issueId ?: record.commentId?.let { comments.findById(it)?.issueId }
    if (issueId != null) {
        return issues.findById(issueId)?.let { projects.findById(it.projectId) }
    }
    val postId = record.forumPostId
        ?: record.forumCommentId?.let { forumPosts.findComment(it)?.postId }
        ?: return null
    val forumId = forumPosts.findPost(postId)?.forumId ?: return null
    val projectId = forums.findById(forumId)?.projectId ?: return null
    return projects.findById(projectId)
}

/**
 * May [user] have these bytes?
 *
 * ── Why this exists rather than one more branch in [projectBehind] ───────────
 *
 * Four of the five owners in Attachments.sq reach a project, and for those the
 * question is [AccessControl.canReadProject] — which is what [projectBehind] was
 * built to feed. The fifth, a private message, reaches no project at all, so
 * "which project is this in" has no answer for it and a function that returned one
 * would have to invent it. What it reaches instead is a *conversation*, and the
 * question there is membership.
 *
 * So the shape had to change: the decision moved here, and [projectBehind] went
 * back to answering only what its name says. The alternative — a nullable project
 * plus a special case at the call site — is how the interesting failure happens,
 * and it is worth naming because it is silent. `serveAttachment` used to read *"if
 * the project is null, 404"*, and a message attachment's project is null, so
 * adding the column without changing that line would have made every message
 * attachment unreachable — which is the harmless direction. The harmful one is the
 * same mistake written the other way: a `null` treated as "nothing to check"
 * serves a private conversation's screenshots to anybody holding the URL.
 *
 * Note the message branch asks [AccessControl.canReadConversation] rather than
 * comparing ids here, so the file that owns permissions still owns this one —
 * including the system administrator clause, which a hand-written `user.id in
 * participants` would silently have dropped.
 *
 * @return false for an owner this build does not recognise, which is the only safe
 *   reading of "the CHECK says exactly one owner and none of the five matched".
 */
private suspend fun BoardDependencies.mayReadAttachment(
    user: UserRecord?,
    record: AttachmentRecord,
): Boolean {
    val messageId = record.messageId
    if (messageId != null) {
        val message = conversations.findMessage(messageId) ?: return false
        return access.canReadConversation(user, conversations.participantIds(message.conversationId))
    }
    val project = projectBehind(record) ?: return false
    return access.canReadProject(user, project)
}

/**
 * Resolve a forum post this caller may put a file into, or respond and return
 * null.
 *
 * The gate is [AccessControl.canEditForumContent] — the author, or a system
 * administrator — which is the same rule that publishes the post. Deliberately
 * *not* the delete rule: a project administrator moderates a forum by removing
 * what does not belong in it, which is not the same authority as adding bytes to
 * somebody else's post under their name. See that function.
 *
 * The 404s come before the 403 for `readableProject`'s reason, and the project
 * check is not skipped just because the post id was found: a post in a project
 * this caller cannot see must answer as though it does not exist.
 */
private suspend fun ApplicationCall.writableForumPost(
    deps: BoardDependencies,
    user: UserRecord?,
): ForumPostRecord? {
    val id = longParam("id") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad post id.")
        return null
    }
    val post = deps.forumPosts.findPost(id)
    val forum = post?.let { deps.forums.findById(it.forumId) }
    val project = forum?.let { deps.projects.findById(it.projectId) }
    if (post == null || project == null || !deps.access.canReadProject(user, project)) {
        respond(HttpStatusCode.NotFound, "No such post.")
        return null
    }
    if (!deps.access.canEditForumContent(user, post.author)) {
        respond(HttpStatusCode.Forbidden, "You cannot attach files to this post.")
        return null
    }
    return post
}

/** As [writableForumPost], one level down. */
private suspend fun ApplicationCall.writableForumComment(
    deps: BoardDependencies,
    user: UserRecord?,
): ForumCommentRecord? {
    val id = longParam("id") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad comment id.")
        return null
    }
    val comment = deps.forumPosts.findComment(id)
    val post = comment?.let { deps.forumPosts.findPost(it.postId) }
    val forum = post?.let { deps.forums.findById(it.forumId) }
    val project = forum?.let { deps.projects.findById(it.projectId) }
    if (comment == null || project == null || !deps.access.canReadProject(user, project)) {
        respond(HttpStatusCode.NotFound, "No such comment.")
        return null
    }
    if (!deps.access.canEditForumContent(user, comment.author)) {
        respond(HttpStatusCode.Forbidden, "You cannot attach files to this comment.")
        return null
    }
    return comment
}

/**
 * Resolve a message this caller may put a file into, or respond and return null.
 *
 * The 404 comes before the 403 for `readableProject`'s reason, and the
 * conversation check is not skipped just because the message id was found: a
 * message in a conversation this caller is not in must answer as though it does
 * not exist, and the ids are consecutive integers.
 */
private suspend fun ApplicationCall.writableMessage(
    deps: BoardDependencies,
    user: UserRecord?,
): MessageRecord? {
    val id = longParam("id") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad message id.")
        return null
    }
    val message = deps.conversations.findMessage(id)
    val participantIds = message?.let { deps.conversations.participantIds(it.conversationId) }.orEmpty()
    if (message == null || !deps.access.canReadConversation(user, participantIds)) {
        respond(HttpStatusCode.NotFound, "No such message.")
        return null
    }
    // Being in the room, and having written this draft. The second clause is
    // spelled here rather than borrowed from AccessControl because it is strict
    // authorship with no administrator clause at all — the two rules there that
    // come closest both have one, and using either would have quietly let a system
    // administrator put bytes into somebody's unsent message.
    if (!deps.access.canWriteInConversation(user, participantIds) || message.author != user.asAuthor()) {
        respond(HttpStatusCode.Forbidden, "You cannot attach files to this message.")
        return null
    }
    return message
}

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
            val stored = deps.attachmentRepository.storeForIssue(
                issueId = issue.id,
                filename = upload.filename,
                declaredMimeType = upload.mimeType,
                bytes = upload.bytes,
                author = user.asAuthor(),
            )
            call.respond(AttachmentRef(stored.publicId))
        } catch (rejected: AttachmentRejected) {
            call.respond(HttpStatusCode.BadRequest, rejected.userMessage)
        }
    }

    post("/api/comments/{id}/attachments") {
        val user = call.caller(deps)
        val comment = call.editableComment(deps, user) ?: return@post
        val upload = call.receiveUpload() ?: return@post
        try {
            val stored = deps.attachmentRepository.storeForComment(
                commentId = comment.id,
                filename = upload.filename,
                declaredMimeType = upload.mimeType,
                bytes = upload.bytes,
                author = user.asAuthor(),
            )
            call.respond(AttachmentRef(stored.publicId))
        } catch (rejected: AttachmentRejected) {
            call.respond(HttpStatusCode.BadRequest, rejected.userMessage)
        }
    }

    /**
     * Upload a file into a forum post's body.
     *
     * The same rule as publishing the post — [AccessControl.canEditForumContent],
     * so the author and nobody else. Note that this is deliberately *not* the
     * delete rule: a project administrator may remove somebody's post and may not
     * put a file inside it. See that function's twin.
     *
     * Two lookups rather than one because a post reaches its project through its
     * forum, and the project is what visibility is a fact about. Both are 404 on
     * failure, which is the same answer an unreadable project gives.
     */
    post("/api/forum-posts/{id}/attachments") {
        val user = call.caller(deps)
        val post = call.writableForumPost(deps, user) ?: return@post
        val upload = call.receiveUpload() ?: return@post
        try {
            val stored = deps.attachmentRepository.storeForForumPost(
                forumPostId = post.id,
                filename = upload.filename,
                declaredMimeType = upload.mimeType,
                bytes = upload.bytes,
                author = user.asAuthor(),
            )
            call.respond(AttachmentRef(stored.publicId))
        } catch (rejected: AttachmentRejected) {
            call.respond(HttpStatusCode.BadRequest, rejected.userMessage)
        }
    }

    /** The same, for a forum comment. */
    post("/api/forum-comments/{id}/attachments") {
        val user = call.caller(deps)
        val comment = call.writableForumComment(deps, user) ?: return@post
        val upload = call.receiveUpload() ?: return@post
        try {
            val stored = deps.attachmentRepository.storeForForumComment(
                forumCommentId = comment.id,
                filename = upload.filename,
                declaredMimeType = upload.mimeType,
                bytes = upload.bytes,
                author = user.asAuthor(),
            )
            call.respond(AttachmentRef(stored.publicId))
        } catch (rejected: AttachmentRejected) {
            call.respond(HttpStatusCode.BadRequest, rejected.userMessage)
        }
    }

    /**
     * Upload a file into a private message's body.
     *
     * Gated on being the author of the draft *and* being in the conversation —
     * both, because they are different facts and each covers a hole the other
     * leaves. Authorship alone would let somebody who has since been... well, it
     * would not, since membership is fixed; but the pair is what makes this route
     * independent of that decision rather than quietly relying on it. Membership
     * alone would let one participant put bytes into another's unsent draft.
     *
     * Note this is deliberately *not* the delete rule: a system administrator may
     * remove a message and may not add a file to somebody's unsent one. Same line
     * `writableForumPost` draws, and for the same reason.
     */
    post("/api/messages/{id}/attachments") {
        val user = call.caller(deps)
        val message = call.writableMessage(deps, user) ?: return@post
        val upload = call.receiveUpload() ?: return@post
        try {
            val stored = deps.attachmentRepository.storeForMessage(
                messageId = message.id,
                filename = upload.filename,
                declaredMimeType = upload.mimeType,
                bytes = upload.bytes,
                author = user.asAuthor(),
            )
            call.respond(AttachmentRef(stored.publicId))
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
            val stored = when (val target = ticket.target) {
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

                is AttachmentTarget.ForumPost -> deps.attachmentRepository.storeForForumPost(
                    forumPostId = target.postId,
                    filename = upload.filename,
                    declaredMimeType = upload.mimeType,
                    bytes = upload.bytes,
                    author = ticket.author,
                    createdAt = ticket.createdAt,
                )

                is AttachmentTarget.ForumComment -> deps.attachmentRepository.storeForForumComment(
                    forumCommentId = target.commentId,
                    filename = upload.filename,
                    declaredMimeType = upload.mimeType,
                    bytes = upload.bytes,
                    author = ticket.author,
                    createdAt = ticket.createdAt,
                )
            }
            call.respond(
                TicketedUpload(
                    attachmentId = stored.id,
                    publicId = stored.publicId,
                    // The view URL for the types that have one, for the reason
                    // TicketedUpload's KDoc gives about rendersInline: which of
                    // the two spellings an attachment gets is a fact this server
                    // owns, and an agent left to work it out from the extension
                    // would be a second copy of the rule. An HTML report attached
                    // by an agent opens the same way as one attached by hand.
                    url = if (isSandboxedDocumentType(upload.mimeType)) {
                        ApiRoutes.attachmentView(stored.publicId)
                    } else {
                        ApiRoutes.attachment(stored.publicId)
                    },
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
     * `canReadProject` before streaming a byte. `{id}` is the random `public_id`
     * rather than the row id (LNL-51), so the attachments on this instance can no
     * longer be walked by counting — but that is the second line of defence, and
     * this check is the first. Attachments predating that change carry their old
     * id as their public id, so their URLs still resolve and are still as
     * guessable as they were; the access check is what has always been load
     * bearing for them.
     */
    get("/api/attachments/{id}") { serveAttachment(deps, call, asPage = false) }

    /**
     * Serve the same bytes as a page to read.
     *
     * See `ApiRoutes.attachmentView`, and `serveAttachment` for what the two
     * responses actually differ by.
     */
    get("/api/attachments/{id}/view") { serveAttachment(deps, call, asPage = true) }
}

/**
 * The body of both attachment routes.
 *
 * One function because everything up to the last two headers is identical —
 * the lookup, the issue-to-project walk, the `canReadProject` check, the missing
 * file — and a second copy of that is a second place for the access check to
 * drift out of step with this one.
 */
private suspend fun serveAttachment(deps: BoardDependencies, call: ApplicationCall, asPage: Boolean) {
    val user = call.caller(deps)
    // The public id, as a string, and NOT validated for shape before the lookup.
    // Since LNL-51 it is a random token rather than the row id, so there is no
    // number to parse and nothing a malformed one could reach: the column is
    // queried with it as a parameter, and the path that is later opened comes
    // from the found row's own `storage_key`, never from anything here. An id of
    // "../../lunicle.db" therefore matches no row and takes the same 404 as any
    // other id that does not exist. Rejecting on shape first would only mean
    // answering 400 where 404 is both correct and less talkative.
    val id = call.parameters["id"].orEmpty()
    val record = deps.attachments.findByPublicId(id) ?: run {
        call.respond(HttpStatusCode.NotFound, "No such attachment.")
        return
    }
    // An attachment hangs off exactly one of five things, and each reaches its
    // audience by a different walk: an issue directly, an issue comment through
    // its issue, a forum post through its forum, a forum comment through both —
    // and a message through its conversation, which is the one that does not end
    // at a project at all. See `mayReadAttachment`, which is where that difference
    // is decided, and Attachments.sq's CHECK, which is what makes "exactly one of
    // five" true rather than hopeful.
    if (!deps.mayReadAttachment(user, record)) {
        call.respond(HttpStatusCode.NotFound, "No such attachment.")
        return
    }

    val file = deps.attachmentRepository.fileFor(record.storageKey)
    if (!file.isFile) {
        // The row exists and the file does not: the write half-failed, or a
        // sweep was wrong. Worth a warning — it is the one failure mode the
        // file-not-BLOB decision accepts, so a rash of these is the signal
        // that the trade went bad.
        logger.warn("Attachment ${record.id} has no file at ${record.storageKey}")
        call.respond(HttpStatusCode.NotFound, "That attachment is missing.")
        return
    }
    // ── The most dangerous lines in the server ───────────────────────────────
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
    // a broken image. **Everything else is a download.**
    //
    // There is now a third case, and it is the only one there may ever be: the
    // /view route below, which renders a document *without* rendering it as us.
    // Read `sandboxed` before adding a fourth.
    val sandboxed = asPage && isSandboxedDocumentType(record.mimeType)
    val isInline = sandboxed || isInlineImageType(record.mimeType)
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

    // What makes serving a document from our origin survivable, and the only
    // reason the third case above exists. Every token is load-bearing:
    //
    //  - `sandbox` with NO `allow-same-origin`. This is the whole control. The
    //    document is loaded into an opaque origin of its own, so it is not
    //    lunicle.lunamux.dev to anything it does: no `document.cookie`, no
    //    `localStorage`, and any fetch back at our API is a cross-origin request
    //    without the session cookie. Adding `allow-same-origin` reopens exactly
    //    the stored-XSS hole this whole file is arranged around. Do not.
    //  - No `allow-scripts` either, so it does not run script at all. Belt and
    //    braces on purpose: the two together are what make the opaque origin
    //    something no bug in one clause can undo. An attached report is read,
    //    not used.
    //  - `default-src 'none'` with inline styles and data: images allowed back
    //    in. A saved HTML report is self-contained — that is what makes it worth
    //    opening — and this lets it look like itself while denying it any way to
    //    reach the network, which is what a page full of someone else's data
    //    must not be able to do.
    //  - `frame-ancestors 'none'`, so the page cannot be framed back into our UI
    //    where its chrome could be borrowed for a convincing prompt.
    //
    // On the download path this header is absent, and absent is right: the bytes
    // are never parsed there, and a CSP on a saved file means nothing.
    if (sandboxed) {
        call.response.header(
            CONTENT_SECURITY_POLICY,
            "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:; " +
                "font-src data:; form-action 'none'; frame-ancestors 'none'",
        )
    }

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
 * [url] carries the same kind of answer without a flag of its own: it is already
 * either the download URL or the view one, decided here from the stored type, so
 * an agent writes `[report.html](...)` and gets the right behaviour without
 * knowing the two spellings exist. See `ApiRoutes.attachmentView`.
 *
 * Not in :clientServer with the other wire types: nothing on the client reads
 * this. The audience is an agent, and the house rule for that module is "what
 * both sides must agree on", which this is not.
 */
@Serializable
private data class TicketedUpload(
    /**
     * The row id. Kept, and kept numeric, for an importer reconciling what it has
     * uploaded against its own records — it is a stable handle, and the agent
     * flow is the one caller that has records to reconcile.
     *
     * Not what any URL is built from; see [publicId]. The two were the same value
     * until LNL-51 and are now deliberately not.
     */
    @SerialName("attachment_id") val attachmentId: Long,
    /**
     * What the attachment is called in a URL — the random token, not the row id.
     *
     * Reported alongside [url] rather than instead of it because an agent may
     * want to spell a URL this server did not anticipate; [url] answers the
     * common case, this answers the rest.
     */
    @SerialName("public_id") val publicId: String,
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
    ProjectSummary(
        id = id,
        name = name,
        namePrefix = namePrefix,
        // Retired on the wire pending tickets 3–5, which rebuild the dialog around
        // audience rows. Sent false rather than removed so a client that still reads
        // the fields keeps deserialising. See ProjectSummary.
        isPublic = false,
        visibleToAllSignedIn = false,
        discussionsEnabled = discussionsEnabled,
        messagesEnabled = messagesEnabled,
        requireLabel = requireLabel,
        requireComponent = requireComponent,
        requireFixedVersionOnResolve = requireFixedVersionOnResolve,
        showIssueAuthor = showIssueAuthor,
    )

private fun ProjectPermissions.toView(): ProjectPermissionsView = ProjectPermissionsView(
    canCreateIssue = canCreateIssue,
    canComment = canComment,
    canChangeUnownedIssues = canChangeUnownedIssues,
    canManageSprintsAndVersions = canManageSprintsAndVersions,
    canMutateProject = canMutateProject,
    canMutateProjectIdentity = canMutateProjectIdentity,
    canBeAssigned = canBeAssigned,
)
