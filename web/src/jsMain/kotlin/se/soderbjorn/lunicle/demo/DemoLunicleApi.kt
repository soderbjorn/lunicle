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
import kotlinx.browser.window
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
import se.soderbjorn.lunicle.clientserver.Estimate
import se.soderbjorn.lunicle.clientserver.EstimateMode
import se.soderbjorn.lunicle.clientserver.EstimateUnit
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
import se.soderbjorn.lunicle.clientserver.PersonCandidates
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
    // sign-out button harmless.

    override suspend fun session(): SessionState = world.sessionState()
    override suspend fun signInWithGoogle(code: String): SessionState = world.sessionState()
    override suspend fun requestEmailSignIn(email: String) = Unit
    override suspend fun signInWithEmailCode(email: String, code: String): SessionState = world.sessionState()
    override suspend fun signOut(): SessionState = world.sessionState()
    /**
     * The three impersonation calls, all no-ops that hand back the same session.
     *
     * Unreachable by construction: the demo's `canImpersonate` is false by the
     * fixed-account rule (LNL-146), so nothing draws the menu item that would arm
     * one — and even if something did, the whole facility is a server switch and
     * this world has no server. The old preview stub that used to sit here answered
     * properly, resolving an address against the demo's accounts; it has gone with
     * the preview route it implemented, and nothing replaces it, because signing in
     * for real is exactly what a fixed-account demo must not do.
     */
    override suspend fun armImpersonation(): SessionState = world.sessionState()
    override suspend fun impersonate(email: String): SessionState = world.sessionState()
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

    // ── Agent connections ────────────────────────────────────────────────────

    /**
     * Agent access, which the demo now actually models (LNL-199).
     *
     * ── The contradiction this replaced ─────────────────────────────────────
     *
     * These three used to return `isAllowed = false` flat, and the You tab therefore
     * greyed the agent switch with "Not permitted for members on this instance." — about
     * an account the very next line of the same tab calls the instance owner. LNL-193
     * found it. It is not a wording bug: the permission is per tier and administrators and
     * the owner are always permitted, so `false` was the wrong answer rather than a
     * badly-explained one.
     *
     * So it is asked of [DemoWorld.permitsAgents], which is the rule. The visitor is
     * permitted because they own the place; a member or staff account would depend on
     * their tier card's switch, which is exactly what a visitor can go and toggle on the
     * Instance tab and then watch move in the People tab's MCP column.
     *
     * The switch itself is the person's own half and is stored, so it stays where the
     * visitor put it until they reload. There are no connections and never will be:
     * connecting an agent needs a server to hold a token, which is the one thing demo mode
     * does not have. An empty list is the honest answer and is also what a real account
     * that has just switched agent access on sees.
     */
    override suspend fun mcpState(): McpState = mcpStateForVisitor()

    override suspend fun setMcpEnabled(isEnabled: Boolean): McpState {
        world.mcpEnabled = isEnabled
        return mcpStateForVisitor()
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun revokeMcpConnection(clientId: String): McpState = mcpStateForVisitor()

    private fun mcpStateForVisitor(): McpState {
        val allowed = world.permitsAgents(world.tierOf(world.demoUser))
        return McpState(
            isAllowed = allowed,
            // A switch that is on for somebody no longer permitted would report an agent
            // access they do not have, so the person's own answer is reported only while
            // their tier still allows it — the same `isEnabled && permitted` conjunction
            // the server's canUseMcp applies.
            isEnabled = allowed && world.mcpEnabled,
            // This deployment's own origin, which is what the real route sends and is why
            // the client is careful never to build it itself. It resolves to whatever is
            // serving the demo bundle — so the address on screen is at least a real one,
            // rather than a fabricated host somebody might paste into a terminal.
            serverUrl = "${window.location.origin}/mcp",
        )
    }

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

    /**
     * What a new project starts with (LNL-195).
     *
     * Honours the same two refusals the server does — a guest row cannot be set while
     * public projects are off, and cannot go above Viewer at all (LNL-202) — because the
     * rows are greyed on both bases, and a demo that let a click through would be teaching
     * a rule the product does not have.
     */
    override suspend fun setNewProjectAudience(audienceKey: String, roleKey: String?): AdminSettingsState {
        if (audienceKey == DemoAudienceKeys.GUEST && roleKey != null && !world.allowPublicProjects) {
            return world.adminSettingsState()
        }
        if (roleKey != null && !demoAudiencePermits(audienceKey, roleKey)) {
            return world.adminSettingsState()
        }
        // And the floor, for the reason a project's own audience write has one (LNL-209):
        // these defaults become a project's rows.
        if (world.newProjectAudienceFloorFor(audienceKey).let { demoFloorRefusal(it, roleKey) } != null) {
            return world.adminSettingsState()
        }
        if (roleKey == null) {
            world.newProjectAudiences.remove(audienceKey)
        } else {
            world.newProjectAudiences[audienceKey] = roleKey
        }
        return world.adminSettingsState()
    }

    /**
     * Hand the whole deployment to another account (LNL-198), which the demo can now
     * genuinely do (LNL-199).
     *
     * ── Why this stopped being a no-op ──────────────────────────────────────
     *
     * It was one, honestly: the demo named no staff domain, so every account in the world
     * was a member, so nobody was eligible and the dialog showed a reason where the picker
     * would be. Nothing could ever call this, and moving ownership to an ineligible account
     * would have taught a rule the server does not have.
     *
     * LNL-199 gave the world a domain, which makes the crew who have signed in genuinely
     * eligible. A populated picker over a Confirm that did nothing would be a worse lie
     * than the empty one, so the seat moves for real — and the eligibility is re-derived
     * here rather than trusted from the request, exactly as the server's route re-derives
     * it, so a call naming a placeholder account is refused on the same grounds.
     *
     * The outgoing owner is left holding the administrator rung, as the server leaves them:
     * the visitor keeps the instance tabs and loses the owner-only controls, which is the
     * whole point of being able to watch it happen. They are already an administrator in
     * this world, so there is nothing to write for that — [DemoUser.isSysAdmin] said so
     * before the hand-over and says so after.
     *
     * A refusal returns the world unchanged rather than throwing. A demo should not be able
     * to raise an error dialog.
     */
    override suspend fun handOverInstance(userId: Long): AdminSettingsState {
        val successor = world.users.firstOrNull { it.id == userId }
        if (successor != null && world.mayBeHandedTheInstance(successor)) {
            world.ownerUserId = successor.id
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

    override suspend fun addVocabulary(
        projectId: Long,
        kind: VocabularyKind,
        name: String,
        inverseName: String?,
        marksBlocked: Boolean,
    ): ProjectSettingsState {
        val p = requireProject(projectId)
        addVocabularyRow(world, p, kind, name.trim(), inverseName, marksBlocked)
        return world.projectSettingsState(p)
    }

    override suspend fun editVocabulary(
        projectId: Long,
        kind: VocabularyKind,
        itemId: Long,
        name: String,
        requiresResolution: Boolean,
        isDone: Boolean,
        inverseName: String?,
        marksBlocked: Boolean,
    ): ProjectSettingsState {
        val p = requireProject(projectId)
        editVocabularyRow(p, kind, itemId, name.trim(), requiresResolution, isDone, inverseName, marksBlocked)
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

    /**
     * Whether this project estimates, and in what unit (LNL-215).
     *
     * The unrecognised-key fold happens here, at the write, exactly where the real route
     * does it — [EstimateMode.fromKey] answers `none` for anything it does not know
     * rather than refusing. That is the safe direction and it is worth the demo keeping:
     * `none` renders nothing, so a key from a newer build costs a visitor a setting that
     * does nothing, not a settings pane that will not open.
     *
     * Note what this deliberately does **not** touch: the estimates already on this
     * project's issues. The mode governs what the editor OFFERS and nothing else — every
     * stored estimate carries its own unit, so switching this board from points to time
     * leaves "3 points" reading as 3 points. That is the whole reason a unit is stamped
     * per issue; see DemoIssue.estimate.
     */
    override suspend fun setProjectEstimateMode(projectId: Long, mode: String): ProjectSettingsState {
        val p = requireProject(projectId)
        p.estimateMode = EstimateMode.fromKey(mode)
        return world.projectSettingsState(p)
    }

    override suspend fun setProjectRole(projectId: Long, userId: Long, roleKey: String?): ProjectSettingsState {
        val p = requireProject(projectId)
        // Null is "no access", which removes the row rather than storing an empty rung.
        if (roleKey == null) p.members.remove(userId) else p.members[userId] = roleKey
        return world.projectSettingsState(p)
    }

    /**
     * Who this project admits, wholesale.
     *
     * Carries the publish veto (LNL-199), which this was missing while its sibling
     * [setNewProjectAudience] had it: a guest row cannot be set while the instance forbids
     * public projects, whoever asks — the owner included. Without the check here a visitor
     * could publish a board from the Access section while the switch that governs it read
     * off, which is exactly the "two answers to who can see this, one of them enforced"
     * state the audience table exists to retire.
     *
     * And the audience's **ceiling** (LNL-202), which no switch lifts: the guest row stops
     * at Viewer, because a guest has nobody to attribute a write to. The picker already
     * greys the rungs above it, so this is the same defence-in-depth the real route makes
     * behind the same greying — a demo whose screen and whose write disagreed would be
     * demonstrating the bug this ticket fixed.
     */
    override suspend fun setProjectAudience(
        projectId: Long,
        audienceKey: String,
        roleKey: String?,
    ): ProjectSettingsState {
        val p = requireProject(projectId)
        if (audienceKey == DemoAudienceKeys.GUEST && roleKey != null && !world.allowPublicProjects) {
            return world.projectSettingsState(p)
        }
        if (roleKey != null && !demoAudiencePermits(audienceKey, roleKey)) {
            return world.projectSettingsState(p)
        }
        // And never below what a wider row already gives (LNL-209) — "No access" included,
        // which is the entry this rule exists for. The rows are struck through on this
        // basis, so letting a click through here would teach a rule the product does not
        // have; the server refuses the same write in `canSetAudience`.
        if (world.audienceFloorFor(p, audienceKey).let { demoFloorRefusal(it, roleKey) } != null) {
            return world.projectSettingsState(p)
        }
        if (roleKey == null) p.audiences.remove(audienceKey) else p.audiences[audienceKey] = roleKey
        return world.projectSettingsState(p)
    }

    /**
     * Add somebody by address.
     *
     * The new row has never been signed into, which is what puts the NOT SIGNED IN badge
     * on it — a rung nobody has collected yet. That is the one state this dialog can
     * create, and being able to watch it appear is the point of the badge.
     */
    override suspend fun addProjectPerson(projectId: Long, email: String, roleKey: String): ProjectSettingsState {
        val p = requireProject(projectId)
        val existing = world.users.firstOrNull { it.email.equals(email, ignoreCase = true) }
        // Admission, which the real route now asks here too (LNL-204). An address that
        // already has an account is past the door and stays addable whatever its domain;
        // only inventing a new outside one is refused. Refused by returning the state
        // unchanged, which is this file's idiom for "the server would not have done that" —
        // and the picker has already said why, from `newAddressRefusal`.
        if (existing == null && !world.demoAdmitsNewAddress(email)) {
            return world.projectSettingsState(p)
        }
        val person = existing ?: DemoUser(
            id = world.allocId(),
            name = email.substringBefore('@'),
            email = email,
            // EMAIL, matching what the real server writes for a row added by address: the
            // provider pair records how the row came to exist, and nobody chose a provider
            // for this one.
            provider = AuthProvider.EMAIL,
            hasSignedIn = false,
        ).also { world.users.add(it) }
        p.members[person.id] = roleKey
        return world.projectSettingsState(p)
    }

    /**
     * The directory the people picker searches (LNL-204).
     *
     * The demo's whole point for this feature: `?demo=1` signs you in as somebody who can
     * grant, against a world with a dozen accounts on two domains, so the picker can be
     * driven — searched, picked from, refused — with no server, no database and no sign-in.
     */
    override suspend fun projectPeopleCandidates(projectId: Long, query: String): PersonCandidates =
        world.projectCandidates(requireProject(projectId), query)

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

    /**
     * Hand the issue to somebody, or to nobody.
     *
     * The agent flag goes to false, always — and that is not this file taking a shortcut,
     * it is what the route does. `IssueAssignment.assigneeIsAgent` defaults false and
     * this is the *button's* endpoint: "Assign to me" and "Unassign me" have no opinion
     * about anybody's agent, so the flag they send is the default one and the server
     * writes `requested != null && false`. Giving work to an agent is the editor's
     * gesture; see [saveIssue], which is where the three-rule lifecycle lives.
     */
    override suspend fun setIssueAssignee(id: Long, assigneeId: Long?): IssueDetail {
        val (p, issue) = requireIssue(id)
        if (issue.assigneeId != assigneeId) {
            issue.assigneeId = assigneeId
            issue.assigneeIsAgent = false
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
        val oldSprintId = issue.sprintId
        val oldPlannedVersionId = issue.plannedVersionId
        val oldFixedVersionId = issue.fixedVersionId
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
        // ── The agent flag's lifecycle (LNL-215) ────────────────────────────────
        //
        // Two of the three rules `IssueRepository.save` documents, and they are rules
        // about how two columns move together rather than about what the client sent:
        //
        //  1. **Nobody assigned means nobody's agent.** A flag about no one is not a
        //     state, and allowing it would put a robot badge on a card with no avatar.
        //  2. **Closing keeps it**, which is the absence of a rule rather than one — the
        //     assignment has stopped being a claim about who will do the work and become
        //     a record of who did.
        //
        // The server's third rule — "changing the assignee clears it, unless this save
        // re-states it" — needs a caller that can leave the field *unstated*, which is
        // what the repository's nullable parameter is for. The editor is not such a
        // caller: `IssueUpdate` is its whole field set and always says, so on this path
        // the answer is simply the one the user gave. That is the same reading this
        // method already takes of a null `assigneeId`, and it is why the demo can honour
        // the rule without carrying the tri-state the MCP tools need.
        issue.assigneeIsAgent = update.assigneeId != null && update.assigneeIsAgent
        issue.estimate = resolveEstimate(p, issue, update.estimate)
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
            // The three fields that used to leave no trace at all (LNL-215). Scheduling
            // an issue, planning it for a release and recording which release it shipped
            // in are exactly the changes somebody asks "when did this happen" about, and
            // until now the answer was nowhere. Each carries the NAME as it stands, or
            // null because it was cleared — null being a real answer here, the way it is
            // on an assignee change, and not an absence of one.
            //
            // The estimate deliberately joins neither list: there is no estimate event
            // kind on the wire, so a demo that invented one would be writing history the
            // real server cannot.
            if (issue.sprintId != oldSprintId) {
                addEvent(p, issue, IssueEventKind.SPRINT_CHANGED, value = sprintName(p, issue.sprintId))
            }
            if (issue.plannedVersionId != oldPlannedVersionId) {
                addEvent(
                    p, issue, IssueEventKind.PLANNED_VERSION_CHANGED,
                    value = versionName(p, issue.plannedVersionId),
                )
            }
            if (issue.fixedVersionId != oldFixedVersionId) {
                addEvent(p, issue, IssueEventKind.FIXED_VERSION_CHANGED, value = versionName(p, issue.fixedVersionId))
            }
        }
        return world.issueDetail(p, issue)
    }

    /**
     * What estimate this save may actually write (LNL-215) — the demo's copy of
     * `BoardRoutes.resolveEstimate`.
     *
     * Three answers, and the third is the interesting one:
     *
     *  - **Null clears.** Legal on every project whatever its mode: taking an estimate
     *    off an issue is never something a setting should be able to forbid.
     *  - **A unit the project offers is written.** `time` stores minutes, `points`
     *    stores points, and a project on `none` offers neither.
     *  - **Anything else leaves the stored estimate exactly where it was.** The real
     *    route refuses the whole save with a 400; a demo must not raise an error dialog,
     *    so the closest honest thing is to refuse the one field. Note what the obvious
     *    alternative — dropping the mismatch to null — would do: it would *rewrite an
     *    estimate already stored* on the strength of a project setting, which is the one
     *    behaviour this whole feature is built to prevent. See `EstimateUnit`.
     *
     * A negative amount is refused the same way. Zero is not: "estimated at nothing" is
     * a thing somebody can mean, and the server allows it.
     */
    private fun resolveEstimate(p: DemoProject, issue: DemoIssue, requested: Estimate?): Estimate? {
        if (requested == null) return null
        val offered = when (p.estimateMode) {
            EstimateMode.NONE -> null
            EstimateMode.TIME -> EstimateUnit.MINUTES
            EstimateMode.POINTS -> EstimateUnit.POINTS
        }
        if (requested.amount < 0 || requested.unit != offered) return issue.estimate
        return requested
    }

    override suspend fun setIssueStatus(id: Long, statusId: Long, resolutionId: Long?, fixedVersionId: Long?) {
        val (p, issue) = requireIssue(id)
        val target = p.statuses.firstOrNull { it.id == statusId } ?: notFound("That status")
        val changed = issue.statusId != statusId
        val oldFixedVersionId = issue.fixedVersionId
        issue.statusId = statusId
        issue.resolutionId = if (target.requiresResolution) resolutionId else null
        if (target.requiresResolution && fixedVersionId != null) issue.fixedVersionId = fixedVersionId
        issue.updatedAt = world.now()
        if (changed) addEvent(p, issue, IssueEventKind.STATUS_CHANGED, value = target.name)
        // Guarded on the change rather than on the close, so dragging a card into the
        // closing column twice does not record the same version twice (LNL-215). The
        // resolution dialog is where most fixed versions are actually set, so a history
        // that only heard about the editor's would be silent about the common path.
        if (issue.fixedVersionId != oldFixedVersionId) {
            addEvent(p, issue, IssueEventKind.FIXED_VERSION_CHANGED, value = versionName(p, issue.fixedVersionId))
        }
    }

    override suspend fun setIssueSprint(id: Long, sprintId: Long?): IssueDetail {
        val (p, issue) = requireIssue(id)
        val changed = issue.sprintId != sprintId
        issue.sprintId = sprintId
        issue.updatedAt = world.now()
        // Null is "the backlog" and is recorded as such — see IssueEventKind.SPRINT_CHANGED,
        // where a null value is load-bearing rather than an absence. Guarded on the
        // change, so putting an issue in the sprint it is already in records nothing.
        if (changed) addEvent(p, issue, IssueEventKind.SPRINT_CHANGED, value = sprintName(p, sprintId))
        return world.issueDetail(p, issue)
    }

    /**
     * Move an issue under an epic, or out from under one (LNL-55), and record it on both
     * (LNL-215).
     *
     * One gesture, up to three events, and the asymmetry is deliberate: an issue has at
     * most one parent, so its own history records a *change*; an epic has many children,
     * so its history records arrivals and departures. Reparenting therefore writes
     * `PARENT_CHANGED` on the child, `CHILD_REMOVED` on the epic it left if there was
     * one, and `CHILD_ADDED` on the one it joined. Each carries the other end's KEY —
     * `AST-42` — snapshotted, because a title can be edited and a key cannot.
     */
    override suspend fun setIssueParent(id: Long, parentId: Long?): IssueDetail {
        val (p, issue) = requireIssue(id)
        if (parentId == id) return world.issueDetail(p, issue)
        val oldParentId = issue.parentId
        issue.parentId = parentId
        if (parentId != null) {
            // Append to the bottom of the epic's work order (LNL-55).
            val maxIndex = p.issues.filter { it.parentId == parentId }.maxOfOrNull { it.childIndex } ?: -1.0
            issue.childIndex = maxIndex + 1
        }
        issue.updatedAt = world.now()
        if (oldParentId != parentId) {
            addEvent(p, issue, IssueEventKind.PARENT_CHANGED, value = issueKey(p, parentId))
            val childKey = "${p.prefix}-${issue.number}"
            p.issues.firstOrNull { it.id == oldParentId }
                ?.let { addEvent(p, it, IssueEventKind.CHILD_REMOVED, value = childKey) }
            p.issues.firstOrNull { it.id == parentId }
                ?.let { addEvent(p, it, IssueEventKind.CHILD_ADDED, value = childKey) }
        }
        return world.issueDetail(p, issue)
    }

    /**
     * Link this issue to another under one of the project's relation kinds (LNL-215).
     *
     * The four rules `IssueRepository.addRelation` enforces, mirrored here — and mirrored
     * because they are not schema constraints anywhere: the real server enforces them in
     * the repository precisely because neither backend can. A demo that let any of them
     * through would be teaching a data model the product refuses.
     *
     *  - **Same project**, which this world gets by construction: both the far issue and
     *    the kind are looked up inside [p] alone, so an id from another board simply is
     *    not found.
     *  - **No self-relation.** "This issue is blocked by itself" is not a statement.
     *  - **No duplicate pair, in EITHER direction, under the same kind.** Adding "A
     *    blocked by B" when "B blocks A" already exists is the same fact said twice, and
     *    the whole one-row design rests on them not both being written. The reversed case
     *    is the one a unique index cannot catch; [DemoRelation.otherThan] is what makes
     *    checking it one comparison.
     *  - **Both issues published.** A link to a draft would point at an issue nobody can
     *    see yet.
     *
     * A refusal returns the issue unchanged rather than throwing, which is this file's
     * standing idiom — a demo should not be able to raise an error dialog — and the same
     * thing [setIssueParent] does with a self-parent.
     */
    override suspend fun addIssueRelation(id: Long, toIssueId: Long, kindId: Long): IssueDetail {
        val (p, issue) = requireIssue(id)
        val unchanged = { world.issueDetail(p, issue) }
        if (toIssueId == id) return unchanged()
        val other = p.issues.firstOrNull { it.id == toIssueId } ?: return unchanged()
        if (issue.isDraft || other.isDraft) return unchanged()
        val kind = p.relationKinds.firstOrNull { it.id == kindId } ?: return unchanged()
        val alreadyLinked = p.relations.any {
            it.kindId == kind.id &&
                (it.fromIssueId == issue.id || it.toIssueId == issue.id) &&
                it.otherThan(issue.id) == toIssueId
        }
        if (alreadyLinked) return unchanged()
        p.relations.add(DemoRelation(world.allocId(), issue.id, toIssueId, kind.id))
        recordRelationChanged(p, issue, other, kind, added = true)
        return world.issueDetail(p, issue)
    }

    /**
     * Unlink two issues, by the link's own id (LNL-215).
     *
     * By id because the caller is looking at a rendered row and has one — and because
     * "remove the link between these two under this kind" would have to say which
     * direction it meant, which is the ambiguity storing one row removes. What is checked
     * here is only that the link actually touches this issue: an id from another board
     * must not be removable from a window it has nothing to do with.
     */
    override suspend fun removeIssueRelation(id: Long, relationId: Long): IssueDetail {
        val (p, issue) = requireIssue(id)
        val relation = p.relations.firstOrNull { it.id == relationId }
        if (relation == null || (relation.fromIssueId != issue.id && relation.toIssueId != issue.id)) {
            return world.issueDetail(p, issue)
        }
        // Both read before the removal, so the two events can still name the kind's two
        // labels and the far issue's key. Afterwards there is a row id and nothing to say
        // about it.
        val kind = p.relationKinds.firstOrNull { it.id == relation.kindId }
        val other = p.issues.firstOrNull { it.id == relation.otherThan(issue.id) }
        p.relations.remove(relation)
        if (kind != null && other != null) recordRelationChanged(p, issue, other, kind, added = false)
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

    /**
     * Clear a sprint's completion stamp, and nothing else (LNL-196).
     *
     * Deliberately not the inverse of [completeSprint]: the work that was rolled forward
     * stays where it went and the active sprint is untouched. See the server's
     * SprintRepository.reopen.
     */
    override suspend fun reopenSprint(projectId: Long, sprintId: Long): BoardState {
        val p = requireProject(projectId)
        p.sprints.firstOrNull { it.id == sprintId }?.completedAt = null
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
        // And take its links with it, from both ends (LNL-215) — the cascade the schema
        // applies. A relation is a statement about two issues, so one of them going means
        // the statement goes; leaving the row would put an unresolvable far end on the
        // other issue's window, which the projection would then silently drop, which is a
        // link that exists in the data and nowhere on screen.
        p.relations.removeAll { it.fromIssueId == issue.id || it.toIssueId == issue.id }
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
        relationKind: String? = null,
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
                relationKind = relationKind,
            ),
        )
    }

    /**
     * A link's arrival or departure, recorded on **both** issues (LNL-215).
     *
     * Two events for one relation row, and that is deliberately not a contradiction of
     * the one-row rule [DemoRelation] states. That rule is about *state*, where a second
     * row would be a second source of truth that can drift; this is per-issue and
     * append-only, and both issues genuinely had something happen to them — somebody
     * reading the blocker's history needs to see that it now blocks something.
     *
     * Each event carries the label for **its own side**: `Blocked by` on the from issue,
     * `Blocks` on the to issue, the same word twice when the kind is symmetric. That is
     * what makes each history read as a sentence about the issue it belongs to rather
     * than about the pair.
     */
    private fun recordRelationChanged(
        p: DemoProject,
        from: DemoIssue,
        to: DemoIssue,
        kind: DemoRelationKind,
        added: Boolean,
    ) {
        val eventKind = if (added) IssueEventKind.RELATION_ADDED else IssueEventKind.RELATION_REMOVED
        addEvent(
            p, from, eventKind,
            value = "${p.prefix}-${to.number}",
            relationKind = kind.labelFor(isFromSide = true),
        )
        addEvent(
            p, to, eventKind,
            value = "${p.prefix}-${from.number}",
            relationKind = kind.labelFor(isFromSide = false),
        )
    }

    private fun labelNames(p: DemoProject, ids: List<Long>): List<String> =
        ids.mapNotNull { id -> p.labels.firstOrNull { it.id == id }?.name }

    private fun componentNames(p: DemoProject, ids: List<Long>): List<String> =
        ids.mapNotNull { id -> p.components.firstOrNull { it.id == id }?.name }

    /**
     * The name a history event should snapshot for a sprint or a version, or null.
     *
     * Null carries meaning on both of the kinds that use these — "moved to the backlog",
     * "the planned release was cleared" — so an absent id resolves to a null value
     * rather than to a placeholder word. The name is read *now* and frozen into the
     * event, which is what makes a renamed sprint read by its old name in history: the
     * issue was scheduled into the thing then called that. See IssueEvents.sq.
     */
    private fun sprintName(p: DemoProject, id: Long?): String? =
        id?.let { sid -> p.sprints.firstOrNull { it.id == sid }?.name }

    /** @see sprintName */
    private fun versionName(p: DemoProject, id: Long?): String? =
        id?.let { vid -> p.versions.firstOrNull { it.id == vid }?.name }

    /**
     * An issue's key — the `AST-42` a parent or child event snapshots — or null for no
     * issue at all, which on a `PARENT_CHANGED` means "detached".
     *
     * The key rather than the title, because a key survives a rename and a title does
     * not, and because it is what the reader can actually click. See
     * IssueEventKind.PARENT_CHANGED.
     */
    private fun issueKey(p: DemoProject, issueId: Long?): String? =
        issueId?.let { iid -> p.issues.firstOrNull { it.id == iid }?.let { "${p.prefix}-${it.number}" } }
}
