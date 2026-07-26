/**
 * The client's repository. Above HTTP, below the view models.
 *
 * It shares a name with the server's repositories and nothing else, and the
 * collision is worth being blunt about because both halves are called
 * "repository" in the same codebase:
 *
 *  - **This one** sits *above* HTTP and cannot see a database. It exists so a
 *    view model never mentions transport, and so that logic spanning several
 *    calls — load the project list *and* the session before MainScreen can
 *    render — has somewhere to live that isn't the view model.
 *  - **The server's** ([se.soderbjorn.lunicle.ProjectRepository] and friends)
 *    sit *below* HTTP and above SQL. They exist so a route never mentions a
 *    transaction.
 *
 * Same motivation, opposite side of the wire, no shared code.
 *
 * Nothing here decides who may do what. It cannot: every answer it holds was
 * already filtered and authorised by the server, and a permission that lived
 * here would be a permission living in JavaScript on someone else's machine.
 * See the server's `AccessControl` preamble.
 *
 * @see LunicleApi
 */
package se.soderbjorn.lunicle.client

import se.soderbjorn.lunicle.clientserver.AdminSettingsState
import se.soderbjorn.lunicle.clientserver.ApiFailure
import se.soderbjorn.lunicle.clientserver.BoardState
import se.soderbjorn.lunicle.clientserver.CommentDraft
import se.soderbjorn.lunicle.clientserver.DiscussionUnreadState
import se.soderbjorn.lunicle.clientserver.NotificationCountState
import se.soderbjorn.lunicle.clientserver.NotificationListState
import se.soderbjorn.lunicle.clientserver.IssueDetail
import se.soderbjorn.lunicle.clientserver.IssueDraft
import se.soderbjorn.lunicle.clientserver.IssueUpdate
import se.soderbjorn.lunicle.clientserver.LunicleApi
import se.soderbjorn.lunicle.clientserver.McpState
import se.soderbjorn.lunicle.clientserver.ProjectListState
import se.soderbjorn.lunicle.clientserver.ConversationDetail
import se.soderbjorn.lunicle.clientserver.ConversationDraft
import se.soderbjorn.lunicle.clientserver.ConversationListState
import se.soderbjorn.lunicle.clientserver.ForumListState
import se.soderbjorn.lunicle.clientserver.ForumPostDetail
import se.soderbjorn.lunicle.clientserver.ForumPostListState
import se.soderbjorn.lunicle.clientserver.HttpLunicleApi
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.ProjectUpdate
import se.soderbjorn.lunicle.clientserver.TokenModes
import se.soderbjorn.lunicle.clientserver.SessionState
import se.soderbjorn.lunicle.clientserver.StatisticsState
import se.soderbjorn.lunicle.clientserver.UiSettingsState
import se.soderbjorn.lunicle.clientserver.VocabularyKind

/**
 * Everything the screens need from the server, in the shape they need it.
 *
 * @param api the transport — the real [HttpLunicleApi] by default, or the
 *   browser's in-memory demo implementation when `?demo=1` is set (LNL-146). The
 *   repository and every view model above it depend on the [LunicleApi] interface
 *   and never on which one they were handed.
 */
class StorageRepository(
    private val api: LunicleApi = HttpLunicleApi(),
) {
    // ── Session ──────────────────────────────────────────────────────────────

    suspend fun session(): SessionState = api.session()
    suspend fun signInWithGoogle(code: String): SessionState = api.signInWithGoogle(code)
    suspend fun signOut(): SessionState = api.signOut()
    suspend fun impersonate(userId: Long): SessionState = api.impersonate(userId)
    suspend fun impersonateSignedOut(): SessionState = api.impersonateSignedOut()
    suspend fun stopImpersonating(): SessionState = api.stopImpersonating()
    suspend fun setDisplayName(displayName: String?): SessionState = api.setDisplayName(displayName)
    suspend fun requestEmailSignIn(email: String) = api.requestEmailSignIn(email)
    suspend fun signInWithEmailCode(email: String, code: String): SessionState =
        api.signInWithEmailCode(email, code)

    suspend fun clearEmail(): SessionState = api.clearEmail()
    suspend fun requestEmailChange(email: String): SessionState = api.requestEmailChange(email)
    suspend fun confirmEmailChange(code: String): SessionState = api.confirmEmailChange(code)
    suspend fun cancelEmailChange(): SessionState = api.cancelEmailChange()

    /** Send a diagnostic test email. Admin only, enforced server-side. */

    // ── Shell settings ───────────────────────────────────────────────────────

    /** The caller's stored appearance and themes, and whose they are. */
    suspend fun uiSettings(): UiSettingsState = api.uiSettings()

    /** Remember one shell setting. Refused, not ignored, when signed out. */
    suspend fun setUiSetting(key: String, value: String) = api.setUiSetting(key, value)

    // ── Agent connections ────────────────────────────────────────────────────

    suspend fun mcpState(): McpState = api.mcpState()
    suspend fun setMcpEnabled(isEnabled: Boolean): McpState = api.setMcpEnabled(isEnabled)
    suspend fun revokeMcpConnection(clientId: String): McpState = api.revokeMcpConnection(clientId)

    // ── The opening move ─────────────────────────────────────────────────────

    /**
     * Everything MainScreen needs before it can paint: who you are, what you may
     * see, and — if a project resolves — its board.
     *
     * This is why this class exists. The view model wants one answer to "what am
     * I rendering?", and getting it means three calls whose results depend on
     * each other: the session decides which projects come back, the project list
     * decides which board to ask for, and a `?project=` name has to resolve
     * before either. Doing that in the view model would put request sequencing
     * next to display state; doing it in the API would put display logic in the
     * transport.
     *
     * @param preferredName the embed's `?project=` value, if any. A name that
     *   does not resolve — because it does not exist, or because it is private
     *   and this caller is signed out, which are deliberately the same answer —
     *   falls back to no project rather than failing. The embed asking for
     *   something it cannot have should show the picker, not an error page.
     * @param preferredId a project already chosen in this session, so a refresh
     *   does not bounce the user back to whatever sorts first.
     */
    suspend fun load(
        preferredName: String? = null,
        preferredId: Long? = null,
        preferredTicket: Ticket? = null,
    ): Loaded {
        val session = api.session()
        val projects = api.projects()

        val chosen = resolve(projects, preferredName, preferredId, preferredTicket)
        val board = chosen?.let { runCatching { api.board(it.id) }.getOrNull() }

        return Loaded(session = session, projects = projects, board = board)
    }

    /**
     * Which project to show, in priority order: the one asked for by id, then
     * the one named by the embed, then the first one there is.
     *
     * The last step used to be "then nothing", on the reading that the spec's "We
     * might not be in any project" asked for it. It does not: that sentence
     * describes a state that must *exist*, and it still does — landing on the
     * first project is impossible when the list is empty, which is exactly when
     * having no project is the honest answer. What the old behaviour actually
     * produced was an empty board and a picker, shown to someone with exactly one
     * project, who then had to name the thing they already had. The rule now is
     * that no-project means no projects.
     *
     * It needs no signed-in/signed-out branch, and that is the point rather than
     * an accident: `api.projects()` returns what this caller may read, so the
     * "first project" is the first they have access to, and for a visitor with no
     * session that is the first *public* one. See BoardRoutes' /projects, which
     * filters through canReadProject. Deciding it here would mean the browser
     * holding an opinion about who may see what, which is the one thing this
     * client is never allowed to do.
     *
     * "First" is alphabetical, not arbitrary: `Projects.sq`'s `all` is
     * `ORDER BY name`. It is worth that being stable — this now picks for people,
     * so the pick has to be the same one tomorrow.
     */
    private suspend fun resolve(
        projects: ProjectListState,
        preferredName: String?,
        preferredId: Long?,
        preferredTicket: Ticket?,
    ): ProjectSummary? {
        // A deep link wins over everything, and over `preferredId` in particular:
        // an issue link names the project implicitly and completely, so honouring
        // a previously-selected project instead would open the wrong board and
        // then fail to find the issue in it. On the first load preferredId is null
        // anyway; this ordering is what makes a link *pasted into an open tab*
        // behave the same as one opened cold.
        //
        // Matched against the list rather than asked of the server: prefixes are
        // unique across every project (Projects.sq), and the list is already
        // filtered to what this caller may read — so a link to a private project
        // simply does not resolve for someone signed out, which is the same answer
        // the server would give and one round-trip cheaper.
        preferredTicket?.let { ticket ->
            projects.projects.firstOrNull { it.namePrefix.equals(ticket.prefix, ignoreCase = true) }
                ?.let { return it }
        }
        preferredId?.let { id ->
            projects.projects.firstOrNull { it.id == id }?.let { return it }
        }
        if (!preferredName.isNullOrBlank()) {
            // Resolved server-side rather than by scanning the list, because the
            // list is already filtered: a signed-out visitor naming a private
            // project must get "no such project", and the server is the only
            // thing that can say so without confirming it exists.
            runCatching { api.boardByName(preferredName) }.getOrNull()?.let { return it.project }
        }
        // Deliberately below the embed's name and not merged with it: an embed
        // that asked for a project it cannot have gets the picker, not a
        // different project silently swapped in. Falling through to "first" there
        // would answer a specific request with the wrong answer rather than with
        // no answer — see this function's @param preferredName.
        if (preferredName.isNullOrBlank()) return projects.projects.firstOrNull()
        return null
    }

    /** The three facts MainScreen opens with. */
    data class Loaded(
        val session: SessionState,
        val projects: ProjectListState,
        /** Null when no project resolved, or when its board could not be read. */
        val board: BoardState?,
    )

    // ── Boards ───────────────────────────────────────────────────────────────

    suspend fun board(projectId: Long): BoardState = api.board(projectId)
    suspend fun projects(): ProjectListState = api.projects()

    // ── Projects ─────────────────────────────────────────────────────────────

    suspend fun createProject(
        name: String,
        namePrefix: String,
        isPublic: Boolean,
        visibleToAllSignedIn: Boolean,
    ): ProjectSummary =
        api.createProject(ProjectUpdate(name, namePrefix, isPublic, visibleToAllSignedIn))

    /**
     * Save the project dialog's form.
     *
     * The repository fields are defaulted so the create path — which does not offer
     * them — need not mention them. They travel as typed; parsing a URL into an
     * owner and a name, and deciding what a blank literal token means, are the
     * server's jobs, so that one parse cannot disagree with another. See
     * ProjectUpdate.
     */
    suspend fun updateProject(
        id: Long,
        name: String,
        namePrefix: String,
        isPublic: Boolean,
        visibleToAllSignedIn: Boolean,
        repositoryUrl: String = "",
        githubTokenEnv: String = "",
        githubTokenMode: String = TokenModes.ENV,
        githubTokenLiteral: String = "",
    ): ProjectSummary =
        api.updateProject(
            id,
            ProjectUpdate(
                name,
                namePrefix,
                isPublic,
                visibleToAllSignedIn,
                repositoryUrl,
                githubTokenEnv,
                githubTokenMode,
                githubTokenLiteral,
            ),
        )

    suspend fun deleteProject(id: Long) = api.deleteProject(id)

    // ── Statistics ───────────────────────────────────────────────────────────

    /**
     * The statistics dialog's two calls. Pass-throughs, like the settings below.
     *
     * Two rather than one because they cost wildly different amounts and the view
     * model sequences them deliberately: read the cache, paint, then recompile if
     * the server says the numbers have aged out. See StatisticsBackingViewModel.
     */
    suspend fun projectStatistics(projectId: Long): StatisticsState = api.projectStatistics(projectId)

    suspend fun refreshProjectStatistics(projectId: Long): StatisticsState =
        api.refreshProjectStatistics(projectId)

    // ── Instance administration ──────────────────────────────────────────────

    /**
     * The account directory, and the one thing an admin may change about a row in
     * it. Pass-throughs, like the project settings below.
     */
    suspend fun adminSettings(): AdminSettingsState = api.adminSettings()

    suspend fun setUserMcpAllowed(userId: Long, isAllowed: Boolean): AdminSettingsState =
        api.setUserMcpAllowed(userId, isAllowed)

    /** Set one instance-wide switch — require sign-in, or open project creation (LNL-115). */
    suspend fun setInstanceSetting(
        key: se.soderbjorn.lunicle.clientserver.InstanceSettingKey,
        isEnabled: Boolean,
    ): AdminSettingsState = api.setInstanceSetting(key, isEnabled)

    /** Reorder the instance's projects, and delete one — both from the Projects tab (LNL-93). */
    suspend fun reorderProjects(ids: List<Long>): AdminSettingsState = api.reorderProjects(ids)

    suspend fun deleteProjectAsAdmin(id: Long): AdminSettingsState = api.deleteProjectAsAdmin(id)

    // ── Project settings ─────────────────────────────────────────────────────

    /**
     * The settings dialog's whole subject, and every write to it.
     *
     * Pass-throughs, unlike [load] above: the dialog wants one answer and one
     * request answers it, so there is nothing here for this layer to sequence. They
     * are here rather than in the view model for the reason everything else is —
     * a view model never mentions transport.
     */
    suspend fun projectSettings(projectId: Long): ProjectSettingsState = api.projectSettings(projectId)

    /** Switch this project's discussions and messages on or off (LNL-96). Project administrator only. */
    suspend fun setProjectFeatures(
        projectId: Long,
        discussionsEnabled: Boolean,
        messagesEnabled: Boolean,
    ): ProjectSettingsState = api.setProjectFeatures(projectId, discussionsEnabled, messagesEnabled)

    /** Set this project's new-ticket label/component requirements (LNL-106). Project administrator only. */
    suspend fun setProjectRequirements(
        projectId: Long,
        requireLabel: Boolean,
        requireComponent: Boolean,
        requireFixedVersionOnResolve: Boolean = false,
    ): ProjectSettingsState =
        api.setProjectRequirements(projectId, requireLabel, requireComponent, requireFixedVersionOnResolve)

    /** Set this project's board-display settings — whether cards show the author (LNL-157). Project administrator only. */
    suspend fun setProjectDisplaySettings(
        projectId: Long,
        showIssueAuthor: Boolean,
    ): ProjectSettingsState = api.setProjectDisplaySettings(projectId, showIssueAuthor)

    /**
     * The Discussion tab's forums.
     *
     * Pass-throughs for the same reason as the settings calls above: the pane
     * wants one answer and one request answers it, so there is nothing here to
     * sequence. Note that every write returns the whole refreshed list rather
     * than the row it touched — the server decides the order and the caller's
     * affordances, and a client that patched its own copy would be holding an
     * opinion about both.
     */
    suspend fun forums(projectId: Long): ForumListState = api.forums(projectId)

    suspend fun createForum(projectId: Long, name: String, description: String?): ForumListState =
        api.createForum(projectId, name, description)

    suspend fun editForum(projectId: Long, forumId: Long, name: String, description: String?): ForumListState =
        api.editForum(projectId, forumId, name, description)

    suspend fun deleteForum(projectId: Long, forumId: Long): ForumListState =
        api.deleteForum(projectId, forumId)

    suspend fun reorderForums(projectId: Long, ids: List<Long>): ForumListState =
        api.reorderForums(projectId, ids)

    /**
     * What is in a forum: the post list, one post with its comments, and the
     * writes on both.
     *
     * Pass-throughs for the reason above. Note the two-step create — a draft, then
     * a publish — which is not this layer being awkward but the shape that lets an
     * image be uploaded before there is a body to put it in. `IssueBackingViewModel`
     * has done exactly this since it was written; see ApiRoutes.forumPosts.
     *
     * Note also what each write answers with, because it is not uniform and the
     * difference is deliberate: publishing gives back the whole post, deleting a
     * post gives back the forum's list. Whoever deleted a post is looking at a
     * forum next, and there is no post left to describe.
     */
    suspend fun forumPosts(projectId: Long, forumId: Long): ForumPostListState =
        api.forumPosts(projectId, forumId)

    suspend fun createForumPostDraft(projectId: Long, forumId: Long): Long =
        api.createForumPostDraft(projectId, forumId).id

    suspend fun forumPost(projectId: Long, forumId: Long, postId: Long): ForumPostDetail =
        api.forumPost(projectId, forumId, postId)

    suspend fun publishForumPost(
        projectId: Long,
        forumId: Long,
        postId: Long,
        title: String,
        body: String,
    ): ForumPostDetail = api.publishForumPost(projectId, forumId, postId, title, body)

    suspend fun deleteForumPost(projectId: Long, forumId: Long, postId: Long): ForumPostListState =
        api.deleteForumPost(projectId, forumId, postId)

    suspend fun createForumCommentDraft(projectId: Long, forumId: Long, postId: Long): Long =
        api.createForumCommentDraft(projectId, forumId, postId).id

    suspend fun publishForumComment(
        projectId: Long,
        forumId: Long,
        postId: Long,
        commentId: Long,
        body: String,
    ): ForumPostDetail = api.publishForumComment(projectId, forumId, postId, commentId, body)

    suspend fun deleteForumComment(
        projectId: Long,
        forumId: Long,
        postId: Long,
        commentId: Long,
    ): ForumPostDetail = api.deleteForumComment(projectId, forumId, postId, commentId)

    /**
     * Watching a forum, and watching a post (LNL-63).
     *
     * Two pass-throughs, and each answers with the state its own control lives in
     * — the forum's pill is on the post *list*, the post's pill is on the post —
     * which is why they do not share a return type despite being one idea.
     */
    suspend fun setForumNotification(projectId: Long, forumId: Long, subscribed: Boolean): ForumPostListState =
        api.setForumNotification(projectId, forumId, subscribed)

    /**
     * Read/unread (LNL-64): marking a post read, and the Discussion tab's badge.
     *
     * Pass-throughs like everything above, and note what each answers with — the
     * mark answers with the post *list*, because the dot it clears is on a list
     * row, and the badge is its own instance-wide question rather than a field on
     * any list, because it is about a tab rather than about a project. See
     * ApiRoutes.DISCUSSION_UNREAD.
     */
    suspend fun markForumPostRead(projectId: Long, forumId: Long, postId: Long): ForumPostListState =
        api.markForumPostRead(projectId, forumId, postId)

    suspend fun discussionUnread(): DiscussionUnreadState = api.discussionUnread()

    // ── In-app notifications (LNL-109) ───────────────────────────────────────

    suspend fun notificationsUnreadCount(): NotificationCountState = api.notificationsUnreadCount()

    suspend fun notifications(): NotificationListState = api.notifications()

    suspend fun markNotificationRead(id: Long): NotificationListState = api.markNotificationRead(id)

    suspend fun markAllNotificationsRead(): NotificationListState = api.markAllNotificationsRead()

    suspend fun dismissNotification(id: Long): NotificationListState = api.dismissNotification(id)

    suspend fun clearNotifications(): NotificationListState = api.clearNotifications()

    suspend fun setForumPostNotification(
        projectId: Long,
        forumId: Long,
        postId: Long,
        subscribed: Boolean,
    ): ForumPostDetail = api.setForumPostNotification(projectId, forumId, postId, subscribed)

    /**
     * Private conversations: the list, one conversation, and the writes on both.
     *
     * Pass-throughs for the reason above, with the same two-step create the forum
     * has — a draft, then a publish — plus one extra step at the top:
     * [startConversation] creates a conversation *and* its first empty message,
     * because a new conversation has nothing to hang a draft off. See
     * ApiRoutes.CONVERSATIONS.
     *
     * Note what each write answers with, because it is not uniform: publishing and
     * deleting a message both give back the whole conversation, and discarding an
     * unsent conversation gives back the list — whoever cancelled a composer is
     * looking at a list next, and there is no conversation left to describe. The
     * forum draws exactly the same line one feature over.
     */
    suspend fun conversations(): ConversationListState = api.conversations()

    /** @return the conversation id and the id of the empty first message. */
    suspend fun startConversation(participantIds: List<Long>): ConversationDraft =
        api.startConversation(participantIds)

    suspend fun conversation(id: Long): ConversationDetail = api.conversation(id)

    suspend fun discardConversation(id: Long): ConversationListState = api.discardConversation(id)

    /**
     * Mark a conversation read. Answers with the refreshed list, whose rows carry
     * the counts this clears.
     */
    suspend fun markConversationRead(id: Long): ConversationListState = api.markConversationRead(id)

    suspend fun createMessageDraft(conversationId: Long): Long =
        api.createMessageDraft(conversationId).messageId

    suspend fun publishMessage(conversationId: Long, messageId: Long, body: String): ConversationDetail =
        api.publishMessage(conversationId, messageId, body)

    suspend fun deleteMessage(conversationId: Long, messageId: Long): ConversationDetail =
        api.deleteMessage(conversationId, messageId)

    suspend fun addVocabulary(projectId: Long, kind: VocabularyKind, name: String): ProjectSettingsState =
        api.addVocabulary(projectId, kind, name)

    suspend fun editVocabulary(
        projectId: Long,
        kind: VocabularyKind,
        itemId: Long,
        name: String,
        requiresResolution: Boolean,
        isDone: Boolean = false,
    ): ProjectSettingsState = api.editVocabulary(projectId, kind, itemId, name, requiresResolution, isDone)

    suspend fun deleteVocabulary(projectId: Long, kind: VocabularyKind, itemId: Long): ProjectSettingsState =
        api.deleteVocabulary(projectId, kind, itemId)

    suspend fun reorderVocabulary(projectId: Long, kind: VocabularyKind, ids: List<Long>): ProjectSettingsState =
        api.reorderVocabulary(projectId, kind, ids)

    suspend fun setProjectRole(
        projectId: Long,
        userId: Long,
        roleKey: String,
        isGranted: Boolean,
    ): ProjectSettingsState = api.setProjectRole(projectId, userId, roleKey, isGranted)

    suspend fun setProjectNewIssueNotification(projectId: Long, subscribed: Boolean): ProjectSettingsState =
        api.setProjectNewIssueNotification(projectId, subscribed)

    // ── Issues ───────────────────────────────────────────────────────────────

    suspend fun createIssueDraft(projectId: Long): IssueDraft = api.createIssueDraft(projectId)
    suspend fun issue(id: Long): IssueDetail = api.issue(id)
    suspend fun setIssueNotification(id: Long, subscribed: Boolean): IssueDetail =
        api.setIssueNotification(id, subscribed)

    /** Take an issue, or put it down. See [LunicleApi.setIssueAssignee]. */
    suspend fun setIssueAssignee(id: Long, assigneeId: Long?): IssueDetail =
        api.setIssueAssignee(id, assigneeId)

    suspend fun saveIssue(
        id: Long,
        title: String,
        description: String,
        statusId: Long,
        priorityId: Long,
        resolutionId: Long?,
        assigneeId: Long?,
        sprintId: Long?,
        plannedVersionId: Long?,
        fixedVersionId: Long?,
        labelIds: List<Long>,
        componentIds: List<Long>,
    ): IssueDetail = api.saveIssue(
        id,
        IssueUpdate(
            title = title,
            description = description,
            statusId = statusId,
            priorityId = priorityId,
            resolutionId = resolutionId,
            assigneeId = assigneeId,
            sprintId = sprintId,
            plannedVersionId = plannedVersionId,
            fixedVersionId = fixedVersionId,
            labelIds = labelIds,
            componentIds = componentIds,
        ),
    )

    /** Schedule an issue into a sprint, or send it to the backlog. See [LunicleApi.setIssueSprint]. */
    suspend fun setIssueSprint(id: Long, sprintId: Long?): IssueDetail = api.setIssueSprint(id, sprintId)

    /** Attach an issue to an epic, or detach it. See [LunicleApi.setIssueParent] (LNL-55). */
    suspend fun setIssueParent(id: Long, parentId: Long?): IssueDetail = api.setIssueParent(id, parentId)

    /** Rank one epic's children, first to last. See [LunicleApi.reorderChildren] (LNL-55). */
    suspend fun reorderChildren(id: Long, childIds: List<Long>): IssueDetail = api.reorderChildren(id, childIds)

    // ── Sprints ──────────────────────────────────────────────────────────────
    //
    // All three return the refreshed board rather than the sprint they touched,
    // the same convention the settings writes keep: activating one sprint changes
    // which issues the board shows, and completing one moves work between sprints.
    // A client that patched its own state would be right about the sprint and
    // wrong about the cards.

    suspend fun activateSprint(projectId: Long, sprintId: Long?): BoardState =
        api.activateSprint(projectId, sprintId)

    suspend fun completeSprint(projectId: Long, sprintId: Long, moveUnfinishedTo: Long?): BoardState =
        api.completeSprint(projectId, sprintId, moveUnfinishedTo)

    suspend fun setSprintIssues(projectId: Long, sprintId: Long, issueIds: List<Long>): BoardState =
        api.setSprintIssues(projectId, sprintId, issueIds)

    suspend fun setIssueStatus(id: Long, statusId: Long, resolutionId: Long? = null, fixedVersionId: Long? = null) =
        api.setIssueStatus(id, statusId, resolutionId, fixedVersionId)
    suspend fun setIssueOrder(id: Long, issueIds: List<Long>, priorityId: Long? = null) =
        api.setIssueOrder(id, issueIds, priorityId)
    suspend fun deleteIssue(id: Long) = api.deleteIssue(id)

    // ── Comments ─────────────────────────────────────────────────────────────

    suspend fun createCommentDraft(issueId: Long): CommentDraft = api.createCommentDraft(issueId)
    suspend fun saveComment(id: Long, body: String) = api.saveComment(id, body)
    suspend fun deleteComment(id: Long) = api.deleteComment(id)

    // ── Attachments ──────────────────────────────────────────────────────────

    // Both answer with the attachment's PUBLIC id — an opaque string, and the
    // only name for an attachment this side ever learns. See AttachmentRef.
    suspend fun uploadIssueAttachment(issueId: Long, filename: String, mimeType: String, bytes: ByteArray): String =
        api.uploadIssueAttachment(issueId, filename, mimeType, bytes).id

    suspend fun uploadCommentAttachment(commentId: Long, filename: String, mimeType: String, bytes: ByteArray): String =
        api.uploadCommentAttachment(commentId, filename, mimeType, bytes).id

    suspend fun uploadForumPostAttachment(
        postId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): String = api.uploadForumPostAttachment(postId, filename, mimeType, bytes).id

    suspend fun uploadMessageAttachment(
        messageId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): String = api.uploadMessageAttachment(messageId, filename, mimeType, bytes).id

    suspend fun uploadForumCommentAttachment(
        commentId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): String = api.uploadForumCommentAttachment(commentId, filename, mimeType, bytes).id
}

/**
 * What to show the user for a failure.
 *
 * The server writes its refusals for a human — "There is already a project
 * called \"Lunamux\"", "You cannot edit this issue" — so prefer that to a
 * generic line invented on this side, which is by definition less specific than
 * what the server already knows. Anything that isn't an [ApiFailure] is a
 * transport or parse problem with no message worth showing, so it gets the
 * fallback.
 *
 * Shared by every view model, because every one of them has the same choice to
 * make and they should not each make it differently.
 */
fun Throwable.userMessage(fallback: String): String =
    (this as? ApiFailure)?.serverMessage?.takeIf { it.isNotBlank() } ?: fallback
