/**
 * The browser-only [LunicleApi] the app runs against in demo mode (`?demo=1`,
 * LNL-146). No network, no persistence, no auth — every call reads or mutates the
 * in-memory [DemoWorld] and recomputes the wire DTO from it, mirroring what the
 * real server's routes do. A page reload rebuilds the world from [seedDemoWorld]
 * and starts over.
 *
 * The demo user is Captain Janeway — instance administrator and project owner — so
 * everything is permitted and every affordance flag the projections return is
 * `true`. Endpoints the demo does not simulate (forums, private messages, agent
 * connections, OAuth, e-mail changes, attachment uploads) are stubbed: they return
 * empty or signed-in state and mutate nothing. Forums and messages are switched off
 * on the project, and `?forums=1` is never passed, so those tabs never render and
 * their stubs are never called in practice.
 *
 * @see DemoWorld
 * @see se.soderbjorn.lunicle.clientserver.LunicleApi
 */
package se.soderbjorn.lunicle.demo

import io.ktor.http.HttpStatusCode
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.AdmissionPolicy
import se.soderbjorn.lunicle.clientserver.AdminSettingsState
import se.soderbjorn.lunicle.clientserver.ApiFailure
import se.soderbjorn.lunicle.clientserver.AttachmentRef
import se.soderbjorn.lunicle.clientserver.BoardState
import se.soderbjorn.lunicle.clientserver.CommentDraft
import se.soderbjorn.lunicle.clientserver.ConversationDetail
import se.soderbjorn.lunicle.clientserver.ConversationDraft
import se.soderbjorn.lunicle.clientserver.ConversationListState
import se.soderbjorn.lunicle.clientserver.DiscussionUnreadState
import se.soderbjorn.lunicle.clientserver.ForumDraftRef
import se.soderbjorn.lunicle.clientserver.ForumListState
import se.soderbjorn.lunicle.clientserver.ForumPostDetail
import se.soderbjorn.lunicle.clientserver.ForumPostListState
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
import se.soderbjorn.lunicle.clientserver.IssueDetail
import se.soderbjorn.lunicle.clientserver.IssueDraft
import se.soderbjorn.lunicle.clientserver.IssueEventKind
import se.soderbjorn.lunicle.clientserver.IssueUpdate
import se.soderbjorn.lunicle.clientserver.LunicleApi
import se.soderbjorn.lunicle.clientserver.McpState
import se.soderbjorn.lunicle.clientserver.NotificationCountState
import se.soderbjorn.lunicle.clientserver.NotificationListState
import se.soderbjorn.lunicle.clientserver.ProjectListState
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.ProjectUpdate
import se.soderbjorn.lunicle.clientserver.SessionState
import se.soderbjorn.lunicle.clientserver.StatisticsState
import se.soderbjorn.lunicle.clientserver.UiSettingsState
import se.soderbjorn.lunicle.clientserver.VocabularyKind

internal class DemoLunicleApi(
    private val world: DemoWorld = seedDemoWorld(),
) : LunicleApi {

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun notFound(what: String): Nothing =
        throw ApiFailure(HttpStatusCode.NotFound, "$what does not exist in this demo.")

    private fun requireProject(id: Long): DemoProject =
        world.projectById(id) ?: notFound("That project")

    private fun requireIssue(id: Long): Pair<DemoProject, DemoIssue> =
        world.locateIssue(id) ?: notFound("That issue")

    /**
     * The project a stub with no project in hand answers about: the Amiga board,
     * being the first seeded. The world holds three, so this is a convention rather
     * than the only one there is — anything that knows its project id must use
     * [requireProject] instead.
     */
    private val primaryProject: DemoProject get() = world.projects.first()

    // ── Session ──────────────────────────────────────────────────────────────
    //
    // Always signed in as Janeway. Sign-in, sign-out and impersonation are all
    // no-ops that return the same signed-in session, which is what makes the
    // sign-out button harmless and the sign-in gate never appear.

    override suspend fun session(): SessionState = world.sessionState()
    override suspend fun signInWithGoogle(code: String): SessionState = world.sessionState()
    override suspend fun requestEmailSignIn(email: String) = Unit
    override suspend fun signInWithEmailCode(email: String, code: String): SessionState = world.sessionState()
    override suspend fun signOut(): SessionState = world.sessionState()
    override suspend fun impersonate(userId: Long): SessionState = world.sessionState()
    override suspend fun impersonateSignedOut(): SessionState = world.sessionState()
    override suspend fun stopImpersonating(): SessionState = world.sessionState()

    // ── Profile ──────────────────────────────────────────────────────────────

    override suspend fun setDisplayName(displayName: String?): SessionState {
        displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { world.demoUser.name = it }
        return world.sessionState()
    }

    override suspend fun clearEmail(): SessionState = world.sessionState()
    override suspend fun requestEmailChange(email: String): SessionState = world.sessionState()
    override suspend fun confirmEmailChange(code: String): SessionState = world.sessionState()
    override suspend fun cancelEmailChange(): SessionState = world.sessionState()

    // ── Shell settings ───────────────────────────────────────────────────────

    override suspend fun uiSettings(): UiSettingsState =
        UiSettingsState(userId = world.demoUserId, settings = world.uiSettings.toMap())

    override suspend fun setUiSetting(key: String, value: String) {
        world.uiSettings[key] = value
    }

    // ── Agent connections (stubbed: not permitted, so the section never renders) ─

    override suspend fun mcpState(): McpState = McpState(isAllowed = false)
    override suspend fun setMcpEnabled(isEnabled: Boolean): McpState = McpState(isAllowed = false)
    override suspend fun revokeMcpConnection(clientId: String): McpState = McpState(isAllowed = false)

    // ── Instance administration ──────────────────────────────────────────────

    override suspend fun adminSettings(): AdminSettingsState = world.adminSettingsState()

    override suspend fun setAdmissionPolicy(policy: AdmissionPolicy): AdminSettingsState {
        world.admission = policy
        return world.adminSettingsState()
    }

    override suspend fun setInstanceSetting(key: InstanceSettingKey, isEnabled: Boolean): AdminSettingsState {
        when (key) {
            InstanceSettingKey.ALLOW_PUBLIC_PROJECTS -> world.allowPublicProjects = isEnabled
            InstanceSettingKey.STAFF_MAY_CREATE_PROJECTS -> world.staffMayCreateProjects = isEnabled
            InstanceSettingKey.MEMBER_MAY_CREATE_PROJECTS -> world.memberMayCreateProjects = isEnabled
            InstanceSettingKey.STAFF_MAY_USE_AGENTS -> world.staffMayUseAgents = isEnabled
            InstanceSettingKey.MEMBER_MAY_USE_AGENTS -> world.memberMayUseAgents = isEnabled
            InstanceSettingKey.HIDE_DISPLAY_NAME -> world.hideDisplayName = isEnabled
        }
        return world.adminSettingsState()
    }

    override suspend fun reorderProjects(ids: List<Long>): AdminSettingsState {
        world.projects.sortBy { ids.indexOf(it.id) }
        return world.adminSettingsState()
    }

    override suspend fun deleteProjectAsAdmin(id: Long): AdminSettingsState {
        world.projects.removeAll { it.id == id }
        return world.adminSettingsState()
    }

    // ── Projects ─────────────────────────────────────────────────────────────

    override suspend fun projects(): ProjectListState =
        ProjectListState(projects = world.projects.map(world::projectSummary), canCreateProject = true)

    override suspend fun createProject(update: ProjectUpdate): ProjectSummary {
        val project = provisionProject(world, update.name, update.namePrefix)
        world.projects.add(project)
        return world.projectSummary(project)
    }

    override suspend fun updateProject(id: Long, update: ProjectUpdate): ProjectSummary {
        val p = requireProject(id)
        p.name = update.name
        p.prefix = update.namePrefix
        return world.projectSummary(p)
    }

    override suspend fun deleteProject(id: Long) {
        world.projects.removeAll { it.id == id }
    }

    // ── Statistics ───────────────────────────────────────────────────────────

    override suspend fun projectStatistics(projectId: Long): StatisticsState =
        world.statisticsState(requireProject(projectId))

    override suspend fun refreshProjectStatistics(projectId: Long): StatisticsState =
        world.statisticsState(requireProject(projectId))

    // ── Project settings ─────────────────────────────────────────────────────

    override suspend fun projectSettings(projectId: Long): ProjectSettingsState =
        world.projectSettingsState(requireProject(projectId))

    override suspend fun setProjectNewIssueNotification(projectId: Long, subscribed: Boolean): ProjectSettingsState {
        val p = requireProject(projectId)
        p.notifyOnNewIssue = subscribed
        return world.projectSettingsState(p)
    }

    override suspend fun addVocabulary(projectId: Long, kind: VocabularyKind, name: String): ProjectSettingsState {
        val p = requireProject(projectId)
        addVocabularyRow(world, p, kind, name.trim())
        return world.projectSettingsState(p)
    }

    override suspend fun editVocabulary(
        projectId: Long,
        kind: VocabularyKind,
        itemId: Long,
        name: String,
        requiresResolution: Boolean,
        isDone: Boolean,
    ): ProjectSettingsState {
        val p = requireProject(projectId)
        editVocabularyRow(p, kind, itemId, name.trim(), requiresResolution, isDone)
        return world.projectSettingsState(p)
    }

    override suspend fun deleteVocabulary(projectId: Long, kind: VocabularyKind, itemId: Long): ProjectSettingsState {
        val p = requireProject(projectId)
        deleteVocabularyRow(p, kind, itemId)
        return world.projectSettingsState(p)
    }

    override suspend fun reorderVocabulary(
        projectId: Long,
        kind: VocabularyKind,
        ids: List<Long>,
    ): ProjectSettingsState {
        val p = requireProject(projectId)
        reorderVocabularyRows(p, kind, ids)
        return world.projectSettingsState(p)
    }

    override suspend fun setProjectFeatures(
        projectId: Long,
        discussionsEnabled: Boolean,
        messagesEnabled: Boolean,
    ): ProjectSettingsState {
        val p = requireProject(projectId)
        p.discussionsEnabled = discussionsEnabled
        p.messagesEnabled = messagesEnabled
        return world.projectSettingsState(p)
    }

    override suspend fun setProjectRequirements(
        projectId: Long,
        requireLabel: Boolean,
        requireComponent: Boolean,
        requireFixedVersionOnResolve: Boolean,
    ): ProjectSettingsState {
        val p = requireProject(projectId)
        p.requireLabel = requireLabel
        p.requireComponent = requireComponent
        p.requireFixedVersionOnResolve = requireFixedVersionOnResolve
        return world.projectSettingsState(p)
    }

    override suspend fun setProjectDisplaySettings(
        projectId: Long,
        showIssueAuthor: Boolean,
        hideIssueNumbers: Boolean,
    ): ProjectSettingsState {
        val p = requireProject(projectId)
        p.showIssueAuthor = showIssueAuthor
        p.hideIssueNumbers = hideIssueNumbers
        return world.projectSettingsState(p)
    }

    override suspend fun setProjectRole(projectId: Long, userId: Long, roleKey: String?): ProjectSettingsState {
        val p = requireProject(projectId)
        // Null is "no access", which removes the row rather than storing an empty rung.
        if (roleKey == null) p.members.remove(userId) else p.members[userId] = roleKey
        return world.projectSettingsState(p)
    }

    override suspend fun setProjectAudience(
        projectId: Long,
        audienceKey: String,
        roleKey: String?,
    ): ProjectSettingsState {
        val p = requireProject(projectId)
        if (roleKey == null) p.audiences.remove(audienceKey) else p.audiences[audienceKey] = roleKey
        return world.projectSettingsState(p)
    }

    /**
     * Add somebody by address. The demo has no sign-in, so the new row is a crew member
     * who will never arrive — which is exactly what the NOT SIGNED IN badge is for.
     */
    override suspend fun addProjectPerson(projectId: Long, email: String, roleKey: String): ProjectSettingsState {
        val p = requireProject(projectId)
        val existing = world.users.firstOrNull { it.email.equals(email, ignoreCase = true) }
        val person = existing ?: DemoUser(
            id = world.allocId(),
            name = email.substringBefore('@'),
            email = email,
            // EMAIL, matching what the real server writes for a row added by address: the
            // provider pair records how the row came to exist, and nobody chose a provider
            // for this one.
            provider = AuthProvider.EMAIL,
        ).also { world.users.add(it) }
        p.members[person.id] = roleKey
        return world.projectSettingsState(p)
    }

    // ── Forums (stubbed — the tab is off) ─────────────────────────────────────

    override suspend fun forums(projectId: Long): ForumListState = ForumListState(canManageForums = true)
    override suspend fun createForum(projectId: Long, name: String, description: String?): ForumListState =
        ForumListState(canManageForums = true)

    override suspend fun editForum(
        projectId: Long,
        forumId: Long,
        name: String,
        description: String?,
    ): ForumListState = ForumListState(canManageForums = true)

    override suspend fun deleteForum(projectId: Long, forumId: Long): ForumListState =
        ForumListState(canManageForums = true)

    override suspend fun reorderForums(projectId: Long, ids: List<Long>): ForumListState =
        ForumListState(canManageForums = true)

    override suspend fun forumPosts(projectId: Long, forumId: Long): ForumPostListState = ForumPostListState()
    override suspend fun createForumPostDraft(projectId: Long, forumId: Long): ForumDraftRef = ForumDraftRef(0)
    override suspend fun forumPost(projectId: Long, forumId: Long, postId: Long): ForumPostDetail =
        ForumPostDetail(id = postId, forumId = forumId, title = "", body = "")

    override suspend fun publishForumPost(
        projectId: Long,
        forumId: Long,
        postId: Long,
        title: String,
        body: String,
    ): ForumPostDetail = ForumPostDetail(id = postId, forumId = forumId, title = title, body = body)

    override suspend fun deleteForumPost(projectId: Long, forumId: Long, postId: Long): ForumPostListState =
        ForumPostListState()

    override suspend fun createForumCommentDraft(projectId: Long, forumId: Long, postId: Long): ForumDraftRef =
        ForumDraftRef(0)

    override suspend fun publishForumComment(
        projectId: Long,
        forumId: Long,
        postId: Long,
        commentId: Long,
        body: String,
    ): ForumPostDetail = ForumPostDetail(id = postId, forumId = forumId, title = "", body = "")

    override suspend fun deleteForumComment(
        projectId: Long,
        forumId: Long,
        postId: Long,
        commentId: Long,
    ): ForumPostDetail = ForumPostDetail(id = postId, forumId = forumId, title = "", body = "")

    override suspend fun setForumNotification(
        projectId: Long,
        forumId: Long,
        subscribed: Boolean,
    ): ForumPostListState = ForumPostListState()

    override suspend fun setForumPostNotification(
        projectId: Long,
        forumId: Long,
        postId: Long,
        subscribed: Boolean,
    ): ForumPostDetail = ForumPostDetail(id = postId, forumId = forumId, title = "", body = "")

    override suspend fun markForumPostRead(projectId: Long, forumId: Long, postId: Long): ForumPostListState =
        ForumPostListState()

    override suspend fun discussionUnread(): DiscussionUnreadState = DiscussionUnreadState(hasUnreadPosts = false)

    // ── In-app notifications ──────────────────────────────────────────────────

    override suspend fun notificationsUnreadCount(): NotificationCountState =
        NotificationCountState(unreadCount = world.unreadCount())

    override suspend fun notifications(): NotificationListState = world.notificationListState()

    override suspend fun markNotificationRead(id: Long): NotificationListState {
        world.notifications.firstOrNull { it.id == id }?.isRead = true
        return world.notificationListState()
    }

    override suspend fun markAllNotificationsRead(): NotificationListState {
        world.notifications.forEach { it.isRead = true }
        return world.notificationListState()
    }

    override suspend fun dismissNotification(id: Long): NotificationListState {
        world.notifications.removeAll { it.id == id }
        return world.notificationListState()
    }

    override suspend fun clearNotifications(): NotificationListState {
        world.notifications.clear()
        return world.notificationListState()
    }

    // ── Private messages (stubbed — the tab is off) ───────────────────────────

    override suspend fun conversations(): ConversationListState = ConversationListState(canMessage = false)
    override suspend fun startConversation(participantIds: List<Long>): ConversationDraft = ConversationDraft(0, 0)
    override suspend fun conversation(id: Long): ConversationDetail = ConversationDetail(id = id)
    override suspend fun discardConversation(id: Long): ConversationListState = ConversationListState(canMessage = false)
    override suspend fun markConversationRead(id: Long): ConversationListState = ConversationListState(canMessage = false)
    override suspend fun createMessageDraft(conversationId: Long): ConversationDraft = ConversationDraft(conversationId, 0)
    override suspend fun publishMessage(conversationId: Long, messageId: Long, body: String): ConversationDetail =
        ConversationDetail(id = conversationId)

    override suspend fun deleteMessage(conversationId: Long, messageId: Long): ConversationDetail =
        ConversationDetail(id = conversationId)

    // ── The board ────────────────────────────────────────────────────────────

    override suspend fun board(projectId: Long): BoardState = world.boardState(requireProject(projectId))

    override suspend fun boardByName(name: String): BoardState {
        val p = world.projects.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: world.projects.firstOrNull()
            ?: notFound("That project")
        return world.boardState(p)
    }

    // ── Issues ───────────────────────────────────────────────────────────────

    override suspend fun createIssueDraft(projectId: Long): IssueDraft {
        val p = requireProject(projectId)
        val firstStatus = p.statuses.minByOrNull { it.position } ?: notFound("A status")
        val midPriority = p.priorities.sortedBy { it.position }.let { it[it.size / 2] }
        val issue = DemoIssue(
            id = world.allocId(),
            number = p.nextNumber++,
            title = "",
            description = "",
            statusId = firstStatus.id,
            priorityId = midPriority.id,
            authorId = world.demoUserId,
            isDraft = true,
            createdAt = world.now(),
            updatedAt = world.now(),
            sortIndex = -world.now().toDouble(),
        )
        p.issues.add(issue)
        return IssueDraft(id = issue.id, number = issue.number)
    }

    override suspend fun issue(id: Long): IssueDetail {
        val (p, issue) = requireIssue(id)
        return world.issueDetail(p, issue)
    }

    override suspend fun setIssueNotification(id: Long, subscribed: Boolean): IssueDetail {
        val (p, issue) = requireIssue(id)
        issue.notify = subscribed
        return world.issueDetail(p, issue)
    }

    override suspend fun setIssueAssignee(id: Long, assigneeId: Long?): IssueDetail {
        val (p, issue) = requireIssue(id)
        if (issue.assigneeId != assigneeId) {
            issue.assigneeId = assigneeId
            issue.updatedAt = world.now()
            addEvent(p, issue, IssueEventKind.ASSIGNEE_CHANGED, value = world.userName(assigneeId))
        }
        return world.issueDetail(p, issue)
    }

    override suspend fun saveIssue(id: Long, update: IssueUpdate): IssueDetail {
        val (p, issue) = requireIssue(id)
        val wasDraft = issue.isDraft
        val oldTitle = issue.title
        val oldDescription = issue.description
        val oldStatusId = issue.statusId
        val oldAssigneeId = issue.assigneeId
        val oldLabels = issue.labelIds.toSet()
        val oldComponents = issue.componentIds.toSet()

        val targetStatus = p.statuses.firstOrNull { it.id == update.statusId }
        issue.title = update.title
        issue.description = update.description
        issue.statusId = update.statusId
        issue.priorityId = update.priorityId
        // Honour the closing rule: a resolution only rides when the target column demands one.
        issue.resolutionId = if (targetStatus?.requiresResolution == true) update.resolutionId else null
        issue.assigneeId = update.assigneeId
        issue.sprintId = update.sprintId
        issue.plannedVersionId = update.plannedVersionId
        issue.fixedVersionId = update.fixedVersionId
        issue.labelIds.clear(); issue.labelIds.addAll(update.labelIds)
        issue.componentIds.clear(); issue.componentIds.addAll(update.componentIds)
        issue.isDraft = false
        issue.updatedAt = world.now()

        if (wasDraft) {
            addEvent(p, issue, IssueEventKind.CREATED)
        } else {
            if (issue.title != oldTitle) addEvent(p, issue, IssueEventKind.TITLE_CHANGED, value = issue.title)
            if (issue.description != oldDescription) addEvent(p, issue, IssueEventKind.DESCRIPTION_CHANGED)
            if (issue.statusId != oldStatusId) {
                addEvent(p, issue, IssueEventKind.STATUS_CHANGED, value = targetStatus?.name)
            }
            if (issue.assigneeId != oldAssigneeId) {
                addEvent(p, issue, IssueEventKind.ASSIGNEE_CHANGED, value = world.userName(issue.assigneeId))
            }
            if (issue.labelIds.toSet() != oldLabels) {
                addEvent(p, issue, IssueEventKind.LABELS_CHANGED, values = labelNames(p, issue.labelIds))
            }
            if (issue.componentIds.toSet() != oldComponents) {
                addEvent(p, issue, IssueEventKind.COMPONENTS_CHANGED, values = componentNames(p, issue.componentIds))
            }
        }
        return world.issueDetail(p, issue)
    }

    override suspend fun setIssueStatus(id: Long, statusId: Long, resolutionId: Long?, fixedVersionId: Long?) {
        val (p, issue) = requireIssue(id)
        val target = p.statuses.firstOrNull { it.id == statusId } ?: notFound("That status")
        val changed = issue.statusId != statusId
        issue.statusId = statusId
        issue.resolutionId = if (target.requiresResolution) resolutionId else null
        if (target.requiresResolution && fixedVersionId != null) issue.fixedVersionId = fixedVersionId
        issue.updatedAt = world.now()
        if (changed) addEvent(p, issue, IssueEventKind.STATUS_CHANGED, value = target.name)
    }

    override suspend fun setIssueSprint(id: Long, sprintId: Long?): IssueDetail {
        val (p, issue) = requireIssue(id)
        issue.sprintId = sprintId
        issue.updatedAt = world.now()
        return world.issueDetail(p, issue)
    }

    override suspend fun setIssueParent(id: Long, parentId: Long?): IssueDetail {
        val (p, issue) = requireIssue(id)
        if (parentId == id) return world.issueDetail(p, issue)
        issue.parentId = parentId
        if (parentId != null) {
            // Append to the bottom of the epic's work order (LNL-55).
            val maxIndex = p.issues.filter { it.parentId == parentId }.maxOfOrNull { it.childIndex } ?: -1.0
            issue.childIndex = maxIndex + 1
        }
        issue.updatedAt = world.now()
        return world.issueDetail(p, issue)
    }

    override suspend fun reorderChildren(id: Long, childIds: List<Long>): IssueDetail {
        val (p, epic) = requireIssue(id)
        childIds.forEachIndexed { index, childId ->
            p.issues.firstOrNull { it.id == childId }?.childIndex = index.toDouble()
        }
        return world.issueDetail(p, epic)
    }

    override suspend fun activateSprint(projectId: Long, sprintId: Long?): BoardState {
        val p = requireProject(projectId)
        p.activeSprintId = sprintId
        return world.boardState(p)
    }

    override suspend fun completeSprint(projectId: Long, sprintId: Long, moveUnfinishedTo: Long?): BoardState {
        val p = requireProject(projectId)
        p.sprints.firstOrNull { it.id == sprintId }?.completedAt = world.now()
        val closedStatusIds = p.statuses.filter { it.requiresResolution }.map { it.id }.toSet()
        p.issues.filter { it.sprintId == sprintId && it.statusId !in closedStatusIds }
            .forEach { it.sprintId = moveUnfinishedTo }
        if (p.activeSprintId == sprintId) p.activeSprintId = null
        return world.boardState(p)
    }

    override suspend fun setSprintIssues(projectId: Long, sprintId: Long, issueIds: List<Long>): BoardState {
        val p = requireProject(projectId)
        val chosen = issueIds.toSet()
        p.issues.forEach { issue ->
            when {
                issue.id in chosen -> issue.sprintId = sprintId
                issue.sprintId == sprintId -> issue.sprintId = null
            }
        }
        return world.boardState(p)
    }

    override suspend fun deleteIssue(id: Long) {
        val (p, issue) = requireIssue(id)
        // Detach any children so they do not dangle under a gone epic (LNL-55).
        p.issues.filter { it.parentId == issue.id }.forEach { it.parentId = null }
        p.comments.removeAll { it.issueId == issue.id }
        p.events.removeAll { it.issueId == issue.id }
        p.issues.remove(issue)
    }

    override suspend fun setIssueOrder(id: Long, issueIds: List<Long>, priorityId: Long?) {
        val (p, issue) = requireIssue(id)
        if (priorityId != null) issue.priorityId = priorityId
        issueIds.forEachIndexed { index, issueId ->
            p.issues.firstOrNull { it.id == issueId }?.sortIndex = index.toDouble()
        }
    }

    // ── Comments ─────────────────────────────────────────────────────────────

    override suspend fun createCommentDraft(issueId: Long): CommentDraft {
        val (p, issue) = requireIssue(issueId)
        val draft = DemoComment(world.allocId(), issue.id, body = "", authorId = world.demoUserId, createdAt = world.now())
        p.comments.add(draft)
        return CommentDraft(id = draft.id)
    }

    override suspend fun saveComment(id: Long, body: String) {
        val (_, comment) = world.locateComment(id) ?: notFound("That comment")
        comment.body = body
        comment.createdAt = world.now()
    }

    override suspend fun deleteComment(id: Long) {
        val located = world.locateComment(id) ?: return
        located.first.comments.remove(located.second)
    }

    // ── Attachments (stubbed — uploads are a no-op in the demo) ───────────────

    override suspend fun uploadIssueAttachment(
        issueId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef = AttachmentRef("demo-attachment")

    override suspend fun uploadCommentAttachment(
        commentId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef = AttachmentRef("demo-attachment")

    override suspend fun uploadForumPostAttachment(
        postId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef = AttachmentRef("demo-attachment")

    override suspend fun uploadForumCommentAttachment(
        commentId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef = AttachmentRef("demo-attachment")

    override suspend fun uploadMessageAttachment(
        messageId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef = AttachmentRef("demo-attachment")

    // ── Event / naming helpers ────────────────────────────────────────────────

    private fun addEvent(
        p: DemoProject,
        issue: DemoIssue,
        kind: IssueEventKind,
        value: String? = null,
        values: List<String> = emptyList(),
    ) {
        p.events.add(
            DemoEvent(
                id = world.allocId(),
                issueId = issue.id,
                kind = kind,
                value = value,
                values = values,
                authorId = world.demoUserId,
                createdAt = world.now(),
            ),
        )
    }

    private fun labelNames(p: DemoProject, ids: List<Long>): List<String> =
        ids.mapNotNull { id -> p.labels.firstOrNull { it.id == id }?.name }

    private fun componentNames(p: DemoProject, ids: List<Long>): List<String> =
        ids.mapNotNull { id -> p.components.firstOrNull { it.id == id }?.name }
}
