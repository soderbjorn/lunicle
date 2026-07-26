/**
 * Everything a client can ask the Lunicle server, as an interface.
 *
 * One method per endpoint, in the shape the wire uses. The production
 * implementation is [HttpLunicleApi], which turns each of these into one HTTP
 * request; the browser's demo mode (LNL-146) supplies a second implementation
 * that answers from an in-memory world with no network at all. `StorageRepository`
 * depends on this interface and never on either implementation, which is the whole
 * point of the split: the same view models run against the real server or the demo
 * fixture depending only on which api instance the bootstrap hands them.
 *
 * The rich per-method contract — what each call means, what it throws, why a
 * response is shaped the way it is — lives on [HttpLunicleApi], where the transport
 * that actually enforces it lives. This file is the list of questions; that one is
 * the answers.
 *
 * Everything here is one request, and every answer a client receives has already
 * been filtered and authorised server-side (see the server's `AccessControl`). The
 * client's own orchestration lives one layer up, in `StorageRepository`.
 *
 * @see HttpLunicleApi
 * @see BoardState
 * @see ApiRoutes
 */
package se.soderbjorn.lunicle.clientserver

interface LunicleApi {
    // ── Session ──────────────────────────────────────────────────────────────

    suspend fun session(): SessionState
    suspend fun signInWithGoogle(code: String): SessionState
    suspend fun requestEmailSignIn(email: String)
    suspend fun signInWithEmailCode(email: String, code: String): SessionState
    suspend fun signOut(): SessionState
    suspend fun impersonate(userId: Long): SessionState
    suspend fun impersonateSignedOut(): SessionState
    suspend fun stopImpersonating(): SessionState

    // ── Profile ──────────────────────────────────────────────────────────────

    suspend fun setDisplayName(displayName: String?): SessionState
    suspend fun clearEmail(): SessionState
    suspend fun requestEmailChange(email: String): SessionState
    suspend fun confirmEmailChange(code: String): SessionState
    suspend fun cancelEmailChange(): SessionState

    // ── Shell settings ───────────────────────────────────────────────────────

    suspend fun uiSettings(): UiSettingsState
    suspend fun setUiSetting(key: String, value: String)

    // ── Agent connections ────────────────────────────────────────────────────

    suspend fun mcpState(): McpState
    suspend fun setMcpEnabled(isEnabled: Boolean): McpState
    suspend fun revokeMcpConnection(clientId: String): McpState

    // ── Instance administration ──────────────────────────────────────────────

    suspend fun adminSettings(): AdminSettingsState
    suspend fun setUserMcpAllowed(userId: Long, isAllowed: Boolean): AdminSettingsState
    suspend fun setInstanceSetting(key: InstanceSettingKey, isEnabled: Boolean): AdminSettingsState
    suspend fun reorderProjects(ids: List<Long>): AdminSettingsState
    suspend fun deleteProjectAsAdmin(id: Long): AdminSettingsState

    // ── Projects ─────────────────────────────────────────────────────────────

    suspend fun projects(): ProjectListState
    suspend fun createProject(update: ProjectUpdate): ProjectSummary
    suspend fun updateProject(id: Long, update: ProjectUpdate): ProjectSummary
    suspend fun deleteProject(id: Long)

    // ── Statistics ───────────────────────────────────────────────────────────

    suspend fun projectStatistics(projectId: Long): StatisticsState
    suspend fun refreshProjectStatistics(projectId: Long): StatisticsState

    // ── Project settings ─────────────────────────────────────────────────────

    suspend fun projectSettings(projectId: Long): ProjectSettingsState
    suspend fun setProjectNewIssueNotification(projectId: Long, subscribed: Boolean): ProjectSettingsState
    suspend fun addVocabulary(projectId: Long, kind: VocabularyKind, name: String): ProjectSettingsState
    suspend fun editVocabulary(
        projectId: Long,
        kind: VocabularyKind,
        itemId: Long,
        name: String,
        requiresResolution: Boolean,
        isDone: Boolean = false,
    ): ProjectSettingsState

    suspend fun deleteVocabulary(projectId: Long, kind: VocabularyKind, itemId: Long): ProjectSettingsState
    suspend fun reorderVocabulary(projectId: Long, kind: VocabularyKind, ids: List<Long>): ProjectSettingsState
    suspend fun setProjectFeatures(
        projectId: Long,
        discussionsEnabled: Boolean,
        messagesEnabled: Boolean,
    ): ProjectSettingsState

    suspend fun setProjectRequirements(
        projectId: Long,
        requireLabel: Boolean,
        requireComponent: Boolean,
        requireFixedVersionOnResolve: Boolean = false,
    ): ProjectSettingsState

    suspend fun setProjectDisplaySettings(
        projectId: Long,
        showIssueAuthor: Boolean,
    ): ProjectSettingsState

    // ── Forums ───────────────────────────────────────────────────────────────

    suspend fun forums(projectId: Long): ForumListState
    suspend fun createForum(projectId: Long, name: String, description: String?): ForumListState
    suspend fun editForum(projectId: Long, forumId: Long, name: String, description: String?): ForumListState
    suspend fun deleteForum(projectId: Long, forumId: Long): ForumListState
    suspend fun reorderForums(projectId: Long, ids: List<Long>): ForumListState

    // ── Forum posts and comments ─────────────────────────────────────────────

    suspend fun forumPosts(projectId: Long, forumId: Long): ForumPostListState
    suspend fun createForumPostDraft(projectId: Long, forumId: Long): ForumDraftRef
    suspend fun forumPost(projectId: Long, forumId: Long, postId: Long): ForumPostDetail
    suspend fun publishForumPost(
        projectId: Long,
        forumId: Long,
        postId: Long,
        title: String,
        body: String,
    ): ForumPostDetail

    suspend fun deleteForumPost(projectId: Long, forumId: Long, postId: Long): ForumPostListState
    suspend fun createForumCommentDraft(projectId: Long, forumId: Long, postId: Long): ForumDraftRef
    suspend fun publishForumComment(
        projectId: Long,
        forumId: Long,
        postId: Long,
        commentId: Long,
        body: String,
    ): ForumPostDetail

    suspend fun deleteForumComment(
        projectId: Long,
        forumId: Long,
        postId: Long,
        commentId: Long,
    ): ForumPostDetail

    suspend fun setForumNotification(projectId: Long, forumId: Long, subscribed: Boolean): ForumPostListState
    suspend fun setForumPostNotification(
        projectId: Long,
        forumId: Long,
        postId: Long,
        subscribed: Boolean,
    ): ForumPostDetail

    suspend fun markForumPostRead(projectId: Long, forumId: Long, postId: Long): ForumPostListState
    suspend fun discussionUnread(): DiscussionUnreadState

    // ── In-app notifications (LNL-109) ───────────────────────────────────────

    suspend fun notificationsUnreadCount(): NotificationCountState
    suspend fun notifications(): NotificationListState
    suspend fun markNotificationRead(id: Long): NotificationListState
    suspend fun markAllNotificationsRead(): NotificationListState
    suspend fun dismissNotification(id: Long): NotificationListState
    suspend fun clearNotifications(): NotificationListState

    // ── Private messages ─────────────────────────────────────────────────────

    suspend fun conversations(): ConversationListState
    suspend fun startConversation(participantIds: List<Long>): ConversationDraft
    suspend fun conversation(id: Long): ConversationDetail
    suspend fun discardConversation(id: Long): ConversationListState
    suspend fun markConversationRead(id: Long): ConversationListState
    suspend fun createMessageDraft(conversationId: Long): ConversationDraft
    suspend fun publishMessage(conversationId: Long, messageId: Long, body: String): ConversationDetail
    suspend fun deleteMessage(conversationId: Long, messageId: Long): ConversationDetail
    suspend fun setProjectRole(
        projectId: Long,
        userId: Long,
        roleKey: String,
        isGranted: Boolean,
    ): ProjectSettingsState

    // ── The board ────────────────────────────────────────────────────────────

    suspend fun board(projectId: Long): BoardState
    suspend fun boardByName(name: String): BoardState

    // ── Issues ───────────────────────────────────────────────────────────────

    suspend fun createIssueDraft(projectId: Long): IssueDraft
    suspend fun issue(id: Long): IssueDetail
    suspend fun setIssueNotification(id: Long, subscribed: Boolean): IssueDetail
    suspend fun setIssueAssignee(id: Long, assigneeId: Long?): IssueDetail
    suspend fun saveIssue(id: Long, update: IssueUpdate): IssueDetail
    suspend fun setIssueStatus(id: Long, statusId: Long, resolutionId: Long? = null, fixedVersionId: Long? = null)
    suspend fun setIssueSprint(id: Long, sprintId: Long?): IssueDetail
    suspend fun setIssueParent(id: Long, parentId: Long?): IssueDetail
    suspend fun reorderChildren(id: Long, childIds: List<Long>): IssueDetail
    suspend fun activateSprint(projectId: Long, sprintId: Long?): BoardState
    suspend fun completeSprint(projectId: Long, sprintId: Long, moveUnfinishedTo: Long?): BoardState
    suspend fun setSprintIssues(projectId: Long, sprintId: Long, issueIds: List<Long>): BoardState
    suspend fun deleteIssue(id: Long)
    suspend fun setIssueOrder(id: Long, issueIds: List<Long>, priorityId: Long? = null)

    // ── Comments ─────────────────────────────────────────────────────────────

    suspend fun createCommentDraft(issueId: Long): CommentDraft
    suspend fun saveComment(id: Long, body: String)
    suspend fun deleteComment(id: Long)

    // ── Attachments ──────────────────────────────────────────────────────────

    suspend fun uploadIssueAttachment(
        issueId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef

    suspend fun uploadCommentAttachment(
        commentId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef

    suspend fun uploadForumPostAttachment(
        postId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef

    suspend fun uploadForumCommentAttachment(
        commentId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef

    suspend fun uploadMessageAttachment(
        messageId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef
}
