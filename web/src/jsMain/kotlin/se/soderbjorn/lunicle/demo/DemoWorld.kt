/**
 * The in-memory world the browser demo (`?demo=1`, LNL-146) runs against.
 *
 * Lunicle's frontend is a pure renderer of server state — every screen is drawn
 * from a wire DTO the server computes. The demo re-implements that computation in
 * the browser: [DemoWorld] holds the *entities* (users, one or more projects with
 * their vocabularies, issues, comments, history, notifications) as plain mutable
 * objects, and the projection methods below recompute the wire DTOs from them on
 * every call, exactly as the server's routes do. [DemoLunicleApi] is the thin shell
 * that turns each API method into a mutation of this world followed by a projection.
 *
 * Nothing here persists. The world is built once by [seedDemoWorld] when the page
 * loads and lives only in memory, so a reload starts over from the seed — which is
 * the whole point of a demo you can poke at without consequence.
 *
 * The demo user (Captain Janeway) is the instance's system administrator and the
 * project's owner, so every affordance flag this file projects is simply `true`:
 * that is what surfaces the admin surfaces, the drag handles and the edit controls
 * a visitor is meant to explore. The real server would re-derive each of those from
 * the session; here there is one fixed session and it may do everything.
 *
 * @see DemoLunicleApi
 * @see se.soderbjorn.lunicle.clientserver.LunicleApi
 */
package se.soderbjorn.lunicle.demo

import kotlin.js.Date
import se.soderbjorn.lunicle.clientserver.AdminProjectRights
import se.soderbjorn.lunicle.clientserver.AdmissionOption
import se.soderbjorn.lunicle.clientserver.AdmissionPolicy
import se.soderbjorn.lunicle.clientserver.AdmissionState
import se.soderbjorn.lunicle.clientserver.AdminSettingsState
import se.soderbjorn.lunicle.clientserver.AdminUser
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.BoardState
import se.soderbjorn.lunicle.clientserver.CommentView
import se.soderbjorn.lunicle.clientserver.IssueDetail
import se.soderbjorn.lunicle.clientserver.IssueEventKind
import se.soderbjorn.lunicle.clientserver.IssueEventView
import se.soderbjorn.lunicle.clientserver.IssueRef
import se.soderbjorn.lunicle.clientserver.IssueSummary
import se.soderbjorn.lunicle.clientserver.NotificationKind
import se.soderbjorn.lunicle.clientserver.NotificationListState
import se.soderbjorn.lunicle.clientserver.NotificationSummary
import se.soderbjorn.lunicle.clientserver.ProjectMember
import se.soderbjorn.lunicle.clientserver.ProjectPermissionsView
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.RoleDescription
import se.soderbjorn.lunicle.clientserver.SessionState
import se.soderbjorn.lunicle.clientserver.SignedInUser
import se.soderbjorn.lunicle.clientserver.SprintItem
import se.soderbjorn.lunicle.clientserver.StatisticWindow
import se.soderbjorn.lunicle.clientserver.StatisticsState
import se.soderbjorn.lunicle.clientserver.ProjectStatistics
import se.soderbjorn.lunicle.clientserver.StatusItem
import se.soderbjorn.lunicle.clientserver.TokenModes
import se.soderbjorn.lunicle.clientserver.UserOption
import se.soderbjorn.lunicle.clientserver.VocabularyEntry
import se.soderbjorn.lunicle.clientserver.VocabularyItem
import se.soderbjorn.lunicle.clientserver.VocabularyKind

// ── Role vocabulary ─────────────────────────────────────────────────────────
//
// The server's `Role` enum lives in the JVM-only `:server` module, so the demo
// carries its own copy of the keys and descriptions the settings dialog renders.
// These strings are wire format on the real server; here they are just what the
// privileges table shows. Kept in declaration order, weakest grant first, the way
// the real dialog draws them.

internal object DemoRoleKeys {
    const val VIEW_PROJECT = "view_project"
    const val CREATE_ISSUE = "create_issue"
    const val COMMENT_ON_ISSUE = "comment_on_issue"
    const val CHANGE_UNOWNED_ISSUES = "change_unowned_issues"
    const val BE_ASSIGNED_ISSUE = "be_assigned_issue"
    const val PROJECT_ADMIN = "project_admin"
    const val PROJECT_OWNER = "project_owner"
}

internal val DEMO_ROLE_DESCRIPTIONS: List<RoleDescription> = listOf(
    RoleDescription(DemoRoleKeys.VIEW_PROJECT, "See this project, without being able to change anything in it."),
    RoleDescription(DemoRoleKeys.CREATE_ISSUE, "Create issues in this project."),
    RoleDescription(DemoRoleKeys.COMMENT_ON_ISSUE, "Post comments on this project's issues."),
    RoleDescription(DemoRoleKeys.CHANGE_UNOWNED_ISSUES, "Edit issues they did not create."),
    RoleDescription(DemoRoleKeys.BE_ASSIGNED_ISSUE, "Be assigned this project's issues."),
    RoleDescription(
        DemoRoleKeys.PROJECT_ADMIN,
        "Administer this project: its sprints, its vocabulary and its privileges.",
    ),
    RoleDescription(
        DemoRoleKeys.PROJECT_OWNER,
        "Own this project: everything an administrator can do, plus its name, its repository, and deleting it.",
    ),
)

// ── Entities ────────────────────────────────────────────────────────────────

/** An account. The demo user is [isSysAdmin]; the rest of the crew never sign in. */
internal class DemoUser(
    val id: Long,
    var name: String,
    val email: String?,
    val provider: AuthProvider,
    val isSysAdmin: Boolean = false,
)

/**
 * A status, priority or resolution — all three are an id, a name and an order, so
 * all three ride on this, mirroring the wire's [StatusItem]. [requiresResolution]
 * is meaningful only for a status, [isDone] only for a resolution.
 */
internal class DemoStatus(
    val id: Long,
    var name: String,
    var position: Int,
    var requiresResolution: Boolean = false,
    var isDone: Boolean = false,
)

/** A label, component or version: an id, a name and an order. */
internal class DemoNamed(
    val id: Long,
    var name: String,
    var position: Int,
)

/** A timebox. [completedAt] null means still open. */
internal class DemoSprint(
    val id: Long,
    var name: String,
    var position: Int,
    var completedAt: Long? = null,
)

/** One issue. Mutable, because the demo edits it in place. */
internal class DemoIssue(
    val id: Long,
    val number: Long,
    var title: String,
    var description: String,
    var statusId: Long,
    var priorityId: Long,
    var resolutionId: Long? = null,
    val labelIds: MutableList<Long> = mutableListOf(),
    val componentIds: MutableList<Long> = mutableListOf(),
    var authorId: Long? = null,
    var agentName: String? = null,
    var assigneeId: Long? = null,
    var sprintId: Long? = null,
    var parentId: Long? = null,
    var plannedVersionId: Long? = null,
    var fixedVersionId: Long? = null,
    var isDraft: Boolean = false,
    var createdAt: Long = 0,
    var updatedAt: Long = 0,
    var notify: Boolean = false,
    // Order within a board group (status × priority). Reassigned on drag.
    var sortIndex: Double = 0.0,
    // Order among an epic's children (LNL-55). Reassigned on reorder.
    var childIndex: Double = 0.0,
)

/** A comment. A blank body is an unpublished draft and is never projected. */
internal class DemoComment(
    val id: Long,
    val issueId: Long,
    var body: String,
    var authorId: Long? = null,
    var agentName: String? = null,
    var createdAt: Long = 0,
)

/** One thing that happened to an issue, for its history sidebar. */
internal class DemoEvent(
    val id: Long,
    val issueId: Long,
    val kind: IssueEventKind,
    val value: String? = null,
    val values: List<String> = emptyList(),
    val authorId: Long? = null,
    val agentName: String? = null,
    val createdAt: Long = 0,
)

/** One stored notification, for the bell and its panel. */
internal class DemoNotification(
    val id: Long,
    val kind: NotificationKind,
    val title: String,
    val createdAt: Long,
    var isRead: Boolean = false,
    val projectId: Long? = null,
    val issueId: Long? = null,
)

/**
 * One project, with everything scoped to it — its vocabularies, its issues and
 * their comments and history, its sprints, and who holds what in it.
 */
internal class DemoProject(
    val id: Long,
    var name: String,
    var prefix: String,
    var isPublic: Boolean = true,
    var visibleToAllSignedIn: Boolean = false,
    var discussionsEnabled: Boolean = false,
    var messagesEnabled: Boolean = false,
    var requireLabel: Boolean = false,
    var requireComponent: Boolean = false,
    var requireFixedVersionOnResolve: Boolean = false,
    var showIssueAuthor: Boolean = false,
    var notifyOnNewIssue: Boolean = false,
    val statuses: MutableList<DemoStatus> = mutableListOf(),
    val priorities: MutableList<DemoStatus> = mutableListOf(),
    val resolutions: MutableList<DemoStatus> = mutableListOf(),
    val labels: MutableList<DemoNamed> = mutableListOf(),
    val components: MutableList<DemoNamed> = mutableListOf(),
    val versions: MutableList<DemoNamed> = mutableListOf(),
    val sprints: MutableList<DemoSprint> = mutableListOf(),
    var activeSprintId: Long? = null,
    val issues: MutableList<DemoIssue> = mutableListOf(),
    val comments: MutableList<DemoComment> = mutableListOf(),
    val events: MutableList<DemoEvent> = mutableListOf(),
    var nextNumber: Long = 1,
    // userId -> the role keys they hold here.
    val members: MutableMap<Long, MutableSet<String>> = mutableMapOf(),
)

// ── The world ───────────────────────────────────────────────────────────────

/**
 * The whole in-memory world, plus the projections that turn it into wire DTOs.
 *
 * Instantiated once via [seedDemoWorld]. Everything mutable is a `var` or a
 * `MutableList`, mutated directly by [DemoLunicleApi]; ids come from a single
 * [allocId] counter so nothing ever collides.
 */
internal class DemoWorld {
    /** When the page opened, the anchor every fixture timestamp is relative to. */
    val startedAt: Long = Date().getTime().toLong()

    val users: MutableList<DemoUser> = mutableListOf()
    val projects: MutableList<DemoProject> = mutableListOf()
    val notifications: MutableList<DemoNotification> = mutableListOf()

    /** Toolkit theme blobs, stored in-session so a theme change sticks until reload. */
    val uiSettings: MutableMap<String, String> = mutableMapOf()

    var allowPublicProjects: Boolean = false
    var staffMayCreateProjects: Boolean = false
    var memberMayCreateProjects: Boolean = false
    var staffMayUseAgents: Boolean = false
    var memberMayUseAgents: Boolean = false
    var admission: AdmissionPolicy = AdmissionPolicy.ANYONE
    var hideDisplayName: Boolean = false

    var demoUserId: Long = 0

    private var nextId: Long = 1

    /** A fresh id, unique across every kind of entity in the world. */
    fun allocId(): Long = nextId++

    /** Milliseconds now, so mutations date themselves. */
    fun now(): Long = Date().getTime().toLong()

    // ── Lookups ──────────────────────────────────────────────────────────────

    val demoUser: DemoUser get() = users.first { it.id == demoUserId }

    fun userName(id: Long?): String? = id?.let { uid -> users.firstOrNull { it.id == uid }?.name }

    fun projectById(id: Long): DemoProject? = projects.firstOrNull { it.id == id }

    /** The project and issue for an issue id, or null if it is gone. */
    fun locateIssue(issueId: Long): Pair<DemoProject, DemoIssue>? {
        for (p in projects) {
            val issue = p.issues.firstOrNull { it.id == issueId }
            if (issue != null) return p to issue
        }
        return null
    }

    fun locateComment(commentId: Long): Pair<DemoProject, DemoComment>? {
        for (p in projects) {
            val c = p.comments.firstOrNull { it.id == commentId }
            if (c != null) return p to c
        }
        return null
    }

    // ── Session ──────────────────────────────────────────────────────────────

    fun sessionState(): SessionState {
        val u = demoUser
        return SessionState(
            user = SignedInUser(
                id = u.id,
                displayName = u.name,
                provider = u.provider,
                isSysAdmin = u.isSysAdmin,
                hasDisplayNameOverride = false,
                email = u.email,
                isEmailVerified = true,
            ),
            // No provider is configured in the demo, and none needs to be: the
            // session is already signed in, so the sign-in affordances never show.
            isGoogleAvailable = false,
            googleClientId = null,
            isImpersonating = false,
            // Fixed-account rule (LNL-146): impersonation stays off so the menu is
            // hidden, even though the demo user is an administrator.
            canImpersonate = false,
            impersonatableUsers = emptyList(),
            pendingEmail = null,
            isEmailSignInAvailable = false,
            isSignInRequired = false,
            isDisplayNameHidden = hideDisplayName,
        )
    }

    // ── Projects and the board ────────────────────────────────────────────────

    fun projectSummary(p: DemoProject): ProjectSummary = ProjectSummary(
        id = p.id,
        name = p.name,
        namePrefix = p.prefix,
        isPublic = p.isPublic,
        visibleToAllSignedIn = p.visibleToAllSignedIn,
        discussionsEnabled = p.discussionsEnabled,
        messagesEnabled = p.messagesEnabled,
        requireLabel = p.requireLabel,
        requireComponent = p.requireComponent,
        requireFixedVersionOnResolve = p.requireFixedVersionOnResolve,
        showIssueAuthor = p.showIssueAuthor,
    )

    private fun statusItem(s: DemoStatus): StatusItem =
        StatusItem(s.id, s.name, s.position, s.requiresResolution, s.isDone)

    private fun vocabItem(v: DemoNamed): VocabularyItem = VocabularyItem(v.id, v.name)

    private fun sprintItem(s: DemoSprint): SprintItem = SprintItem(s.id, s.name, s.position, s.completedAt)

    /** Published issues, ordered the way the board groups them: status, priority, then drag order. */
    private fun orderedIssues(p: DemoProject): List<DemoIssue> {
        val statusPos = p.statuses.associate { it.id to it.position }
        val priorityPos = p.priorities.associate { it.id to it.position }
        return p.issues.filter { !it.isDraft }.sortedWith(
            compareBy(
                { statusPos[it.statusId] ?: Int.MAX_VALUE },
                { priorityPos[it.priorityId] ?: Int.MAX_VALUE },
                { it.sortIndex },
            ),
        )
    }

    fun issueSummary(issue: DemoIssue): IssueSummary = IssueSummary(
        id = issue.id,
        number = issue.number,
        title = issue.title,
        statusId = issue.statusId,
        priorityId = issue.priorityId,
        resolutionId = issue.resolutionId,
        labelIds = issue.labelIds.toList(),
        componentIds = issue.componentIds.toList(),
        authorName = userName(issue.authorId),
        agentName = issue.agentName,
        createdAt = issue.createdAt,
        updatedAt = issue.updatedAt,
        canEdit = true,
        sprintId = issue.sprintId,
        parentId = issue.parentId,
    )

    fun boardState(p: DemoProject): BoardState = BoardState(
        project = projectSummary(p),
        statuses = p.statuses.sortedBy { it.position }.map(::statusItem),
        priorities = p.priorities.sortedBy { it.position }.map(::statusItem),
        resolutions = p.resolutions.sortedBy { it.position }.map(::statusItem),
        labels = p.labels.sortedBy { it.position }.map(::vocabItem),
        components = p.components.sortedBy { it.position }.map(::vocabItem),
        sprints = p.sprints.sortedBy { it.position }.map(::sprintItem),
        activeSprintId = p.activeSprintId,
        versions = p.versions.sortedBy { it.position }.map(::vocabItem),
        issues = orderedIssues(p).map(::issueSummary),
        // Owner + sysadmin: every affordance is offered. See the file preamble.
        permissions = ProjectPermissionsView(
            canCreateIssue = true,
            canComment = true,
            canChangeUnownedIssues = true,
            canMutateProject = true,
            canMutateProjectIdentity = true,
            canBeAssigned = true,
        ),
    )

    // ── Issue detail ──────────────────────────────────────────────────────────

    fun issueRef(issue: DemoIssue): IssueRef = IssueRef(
        id = issue.id,
        number = issue.number,
        title = issue.title,
        statusId = issue.statusId,
        resolutionId = issue.resolutionId,
        canEdit = true,
    )

    fun commentView(c: DemoComment): CommentView = CommentView(
        id = c.id,
        body = c.body,
        authorName = userName(c.authorId),
        agentName = c.agentName,
        createdAt = c.createdAt,
        canEdit = true,
    )

    fun eventView(e: DemoEvent): IssueEventView = IssueEventView(
        id = e.id,
        kind = e.kind,
        value = e.value,
        values = e.values,
        authorName = userName(e.authorId),
        agentName = e.agentName,
        createdAt = e.createdAt,
    )

    /** Everyone who may be assigned or @mentioned — the whole crew. */
    fun crewOptions(): List<UserOption> =
        users.map { UserOption(id = it.id, name = it.name, isSelf = it.id == demoUserId) }

    fun issueDetail(p: DemoProject, issue: DemoIssue): IssueDetail {
        val children = p.issues.filter { it.parentId == issue.id }.sortedBy { it.childIndex }
        val parent = issue.parentId?.let { pid -> p.issues.firstOrNull { it.id == pid } }
        // The parent picker / add-child candidates: every published issue; the
        // client filters per gesture, and the demo user may link anything.
        val linkable = p.issues.filter { !it.isDraft && it.id != issue.id }
        // Open sprints, plus the one the issue is currently in even if completed.
        val offerableSprints = p.sprints
            .filter { it.completedAt == null || it.id == issue.sprintId }
            .sortedBy { it.position }
            .map(::sprintItem)
        return IssueDetail(
            id = issue.id,
            projectId = p.id,
            number = issue.number,
            title = issue.title,
            description = issue.description,
            statusId = issue.statusId,
            priorityId = issue.priorityId,
            resolutionId = issue.resolutionId,
            isDraft = issue.isDraft,
            labelIds = issue.labelIds.toList(),
            componentIds = issue.componentIds.toList(),
            authorName = userName(issue.authorId),
            agentName = issue.agentName,
            createdAt = issue.createdAt,
            updatedAt = issue.updatedAt,
            comments = p.comments
                .filter { it.issueId == issue.id && it.body.isNotBlank() }
                .sortedBy { it.createdAt }
                .map(::commentView),
            history = p.events
                .filter { it.issueId == issue.id }
                .sortedBy { it.createdAt }
                .map(::eventView),
            canEdit = true,
            canDelete = true,
            canComment = true,
            notifyOnUpdates = issue.notify,
            canReceiveEmailNotifications = true,
            watchers = if (issue.notify) listOf(demoUser.name) else emptyList(),
            assigneeId = issue.assigneeId,
            assigneeName = userName(issue.assigneeId),
            assignableUsers = crewOptions(),
            mentionableUsers = crewOptions(),
            canBeAssigned = true,
            callerId = demoUserId,
            sprintId = issue.sprintId,
            sprints = offerableSprints,
            plannedVersionId = issue.plannedVersionId,
            fixedVersionId = issue.fixedVersionId,
            versions = p.versions.sortedBy { it.position }.map(::vocabItem),
            parentId = issue.parentId,
            parent = parent?.let(::issueRef),
            children = children.map(::issueRef),
            linkableIssues = linkable.map(::issueRef),
        )
    }

    // ── Project settings ──────────────────────────────────────────────────────

    // Drafts left out, as the server leaves them out (LNL-183): these numbers are
    // what the settings dialog shows beside a Delete it will not offer, and a
    // count that included the half-written issue in the demo's first column would
    // explain a greyed-out button with an issue that is on nobody's board.
    private fun usageOfStatus(p: DemoProject, id: Long) = p.published.count { it.statusId == id }
    private fun usageOfPriority(p: DemoProject, id: Long) = p.published.count { it.priorityId == id }
    private fun usageOfResolution(p: DemoProject, id: Long) = p.published.count { it.resolutionId == id }
    private fun usageOfLabel(p: DemoProject, id: Long) = p.published.count { id in it.labelIds }
    private fun usageOfComponent(p: DemoProject, id: Long) = p.published.count { id in it.componentIds }
    private fun usageOfSprint(p: DemoProject, id: Long) = p.published.count { it.sprintId == id }
    private fun usageOfVersion(p: DemoProject, id: Long) =
        p.published.count { it.plannedVersionId == id || it.fixedVersionId == id }

    private val DemoProject.published: List<DemoIssue> get() = issues.filter { !it.isDraft }

    private fun statusEntry(p: DemoProject, s: DemoStatus, usage: Int) =
        VocabularyEntry(s.id, s.name, s.position, s.requiresResolution, s.isDone, usage)

    private fun namedEntry(v: DemoNamed, usage: Int) =
        VocabularyEntry(v.id, v.name, v.position, usageCount = usage)

    private fun sprintEntry(s: DemoSprint, usage: Int) =
        VocabularyEntry(s.id, s.name, s.position, usageCount = usage)

    fun projectMembers(p: DemoProject): List<ProjectMember> = users.map { u ->
        ProjectMember(
            userId = u.id,
            name = u.name,
            isSysAdmin = u.isSysAdmin,
            isSelf = u.id == demoUserId,
            roleKeys = p.members[u.id]?.toList() ?: emptyList(),
        )
    }

    fun projectSettingsState(p: DemoProject): ProjectSettingsState = ProjectSettingsState(
        labels = p.labels.sortedBy { it.position }.map { namedEntry(it, usageOfLabel(p, it.id)) },
        components = p.components.sortedBy { it.position }.map { namedEntry(it, usageOfComponent(p, it.id)) },
        statuses = p.statuses.sortedBy { it.position }.map { statusEntry(p, it, usageOfStatus(p, it.id)) },
        priorities = p.priorities.sortedBy { it.position }.map { statusEntry(p, it, usageOfPriority(p, it.id)) },
        resolutions = p.resolutions.sortedBy { it.position }.map { statusEntry(p, it, usageOfResolution(p, it.id)) },
        sprints = p.sprints.sortedBy { it.position }.map { sprintEntry(it, usageOfSprint(p, it.id)) },
        versions = p.versions.sortedBy { it.position }.map { namedEntry(it, usageOfVersion(p, it.id)) },
        roles = DEMO_ROLE_DESCRIPTIONS,
        members = projectMembers(p),
        canMutateProject = true,
        canGrantSeniorRoles = true,
        notifyOnNewIssue = p.notifyOnNewIssue,
        canReceiveEmailNotifications = true,
        discussionsEnabled = p.discussionsEnabled,
        messagesEnabled = p.messagesEnabled,
        requireLabel = p.requireLabel,
        requireComponent = p.requireComponent,
        requireFixedVersionOnResolve = p.requireFixedVersionOnResolve,
        showIssueAuthor = p.showIssueAuthor,
        // No repository in the demo — the fields are offered (owner) but empty.
        repositoryUrl = "",
        canConfigureRepository = true,
        githubTokenEnv = "",
        githubTokenMode = TokenModes.NONE,
    )

    // ── Instance administration ───────────────────────────────────────────────

    fun adminSettingsState(): AdminSettingsState = AdminSettingsState(
        roles = DEMO_ROLE_DESCRIPTIONS,
        users = users.map { u ->
            AdminUser(
                userId = u.id,
                name = u.name,
                email = u.email,
                isSysAdmin = u.isSysAdmin,
                isSelf = u.id == demoUserId,
                isMcpAllowed = false,
                isMcpEnabled = false,
                projects = projects.map { p ->
                    AdminProjectRights(
                        projectId = p.id,
                        projectName = p.name,
                        heldRoleKeys = p.members[u.id]?.toList() ?: emptyList(),
                        canSeeProject = true,
                    )
                },
            )
        },
        projects = projects.map(::projectSummary),
        allowPublicProjects = allowPublicProjects,
        staffMayCreateProjects = staffMayCreateProjects,
        memberMayCreateProjects = memberMayCreateProjects,
        staffMayUseAgents = staffMayUseAgents,
        memberMayUseAgents = memberMayUseAgents,
        // Every option offered: the demo is an unbranded install with no domain,
        // so nothing is greyed. See the server's InstanceIdentity.
        admission = AdmissionState(
            selected = admission,
            options = AdmissionPolicy.entries.map { AdmissionOption(it) },
        ),
        hideDisplayName = hideDisplayName,
    )

    // ── Notifications ─────────────────────────────────────────────────────────

    fun notificationListState(): NotificationListState = NotificationListState(
        items = notifications.sortedByDescending { it.createdAt }.map { n ->
            NotificationSummary(
                id = n.id,
                kind = n.kind,
                title = n.title,
                createdAt = n.createdAt,
                isRead = n.isRead,
                projectId = n.projectId,
                issueId = n.issueId,
            )
        },
        unreadCount = notifications.count { !it.isRead },
    )

    fun unreadCount(): Int = notifications.count { !it.isRead }

    // ── Statistics ────────────────────────────────────────────────────────────

    fun statisticsState(p: DemoProject): StatisticsState {
        val closedStatusIds = p.statuses.filter { it.requiresResolution }.map { it.id }.toSet()
        val closed = p.issues.count { !it.isDraft && it.statusId in closedStatusIds }
        val created = p.issues.count { !it.isDraft }
        return StatisticsState(
            statistics = ProjectStatistics(
                computedAt = now(),
                commits = null,
                commitsUnavailable = "No repository is linked in this demo.",
                issuesCreated = StatisticWindow(week = 6, month = 18, allTime = created.toLong()),
                issuesClosed = StatisticWindow(week = 3, month = 9, allTime = closed.toLong()),
            ),
            isStale = false,
        )
    }
}
