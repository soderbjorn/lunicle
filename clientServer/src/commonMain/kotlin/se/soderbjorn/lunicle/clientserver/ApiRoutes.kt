/**
 * The HTTP routes the server exposes and the client calls.
 *
 * Shared constants rather than string literals on each side, so a renamed route
 * is a compile error in both modules instead of a 404 discovered in a browser.
 *
 * The counter that used to live here is gone. It was Stage 1 and 2's payload —
 * the smallest thing that forced a real round-trip through the whole stack — and
 * the issue board is now the real thing it was rehearsing for.
 *
 * @see LunicleApi
 */
package se.soderbjorn.lunicle.clientserver

object ApiRoutes {
    /** `GET` — returns the caller's [SessionState]. Never 401s; signed out is a state, not an error. */
    const val SESSION: String = "/api/session"

    /** `POST` — exchanges a [GoogleCodeRequest] for a session. Returns the new [SessionState]. */
    const val AUTH_GOOGLE: String = "/api/auth/google"

    /**
     * `POST` — mail a sign-in code to an [EmailSignInRequest]'s address.
     *
     * **Unauthenticated, and answers identically whatever happens.** No body, 204,
     * whether or not the address has an account and whether or not the send
     * succeeded — anything else is an account-existence oracle for anyone who can
     * type. The one exception is a `429`, which reveals only that *you* have been
     * asking too often.
     *
     * This is the server's first unauthenticated side-effecting endpoint, and each
     * call spends real money and real sender reputation on an address the caller
     * chose. It is rate-limited on both the target address and the client; see
     * LNL-72.
     */
    const val AUTH_EMAIL_REQUEST: String = "/api/auth/email/request"

    /**
     * `POST` — exchange an [EmailSignInRedeemRequest] for a session. Returns the
     * new [SessionState].
     *
     * The e-mail twin of [AUTH_GOOGLE]: on success it find-or-creates the account,
     * mints a session and sets the cookie, exactly as that one does. Requesting a
     * code is therefore also registration, as open as Google sign-in already is.
     */
    const val AUTH_EMAIL_REDEEM: String = "/api/auth/email/redeem"

    /** `POST` — drops the caller's session. Returns the signed-out [SessionState]. */
    const val SIGN_OUT: String = "/api/auth/signout"

    /**
     * `POST` — start acting as another user. Admin only. Returns the new
     * [SessionState], whose `user` is now the impersonated one.
     *
     * The body names *who to become*, and that is the only thing a client may say
     * about identity here — the server takes who is *asking* from the session
     * cookie and refuses this unless that session's real user is an admin. A
     * route that accepted "I am user 7" rather than "let me become user 7" would
     * be the whole authorization system, undone. See the server's Impersonations.
     */
    const val IMPERSONATE: String = "/api/impersonate"

    /**
     * `POST` — stop impersonating and go back to being yourself.
     *
     * Authorised against the session's **real** user, not the effective one:
     * while impersonating, the effective user is an ordinary user, so an
     * effective-user check would make this route refuse the very person entitled
     * to call it — locking the admin in as whoever they became.
     */
    const val STOP_IMPERSONATING: String = "/api/impersonate/stop"

    /**
     * `POST` — set or clear the caller's display-name override, from a
     * [SetDisplayNameRequest]. Returns the refreshed [SessionState].
     *
     * Acts on the effective caller from the session cookie, like every write —
     * the body carries the new name, never who is setting it.
     */
    const val USER_DISPLAY_NAME: String = "/api/user/display-name"

    /**
     * `POST` — **clear** the caller's own e-mail, from a [SetEmailRequest].
     * Returns the refreshed [SessionState].
     *
     * Only clearing. Setting an address goes through [USER_EMAIL_REQUEST] and
     * [USER_EMAIL_CONFIRM], because it needs proof that the caller can receive
     * mail there; this route used to do both and that is the hole LNL-71 closed.
     * Giving a mailbox up proves nothing and needs no proof, so it stays here and
     * stays immediate. A non-null address is refused rather than ignored.
     */
    const val USER_EMAIL: String = "/api/user/email"

    /**
     * `POST` — mail a confirmation code to a [RequestEmailChangeRequest]'s
     * address. Returns the refreshed [SessionState], whose `pendingEmail` is now
     * that address.
     *
     * Writes nothing to the account. The address lives as a pending row until the
     * code is spent, which is the whole of what makes a claim not a change.
     */
    const val USER_EMAIL_REQUEST: String = "/api/user/email/request"

    /**
     * `POST` — spend a [ConfirmEmailRequest]'s code and, only then, write the
     * address. Returns the refreshed [SessionState].
     *
     * The address is not in the body: it is held with the pending row, so this
     * writes what was actually mailed to rather than what the client repeats
     * back.
     */
    const val USER_EMAIL_CONFIRM: String = "/api/user/email/confirm"

    /**
     * `POST` — drop a pending address change. No body. Returns the refreshed
     * [SessionState].
     *
     * Exists so a mistyped address is not a fifteen-minute wait. Nothing is
     * undone by it — the pending row never touched the account — so it needs no
     * confirmation and cannot fail in an interesting way.
     */
    const val USER_EMAIL_CANCEL: String = "/api/user/email/cancel"

    /**
     * `GET` — the caller's shell settings as a [UiSettingsState]; `POST` — one of
     * them, from a [SetUiSettingRequest].
     *
     * Like [SESSION] and pointedly unlike the other `/api/user` routes, the `GET`
     * never 401s: signed out is an empty [UiSettingsState], because the shell
     * asks this before it knows whether anyone is signed in and "nobody has
     * chosen a theme" is the same answer either way. The `POST` does refuse,
     * since there is nowhere to put a signed-out user's preference.
     */
    const val USER_UI_SETTINGS: String = "/api/user/ui-settings"

    /**
     * `GET` — the caller's [McpState]: the toggle, the server URL, and what is
     * connected.
     *
     * Session-cookie authenticated like every other `/api` route, and pointedly
     * unlike the OAuth endpoints and `/mcp`, which are deliberately
     * unauthenticated (they are how an agent gets a credential in the first
     * place). This is the human's view of that machinery.
     */
    const val MCP: String = "/api/mcp"

    /** `POST` — set the toggle from an [McpEnabledRequest]. Returns the new [McpState]. */
    const val MCP_ENABLED: String = "/api/mcp/enabled"

    /**
     * `DELETE` — disconnect one agent. Returns the new [McpState].
     *
     * Scoped to the calling session's user server-side: one client registration is
     * shared by everyone who connected that agent, so a route that revoked by
     * client id alone would disconnect it for the whole instance.
     */
    fun mcpConnection(clientId: String): String = "$MCP/connections/$clientId"

    /**
     * `GET` — every account on this instance, as an [AdminSettingsState].
     *
     * Admin only, and refused outright — 403, not a narrowed payload. Unlike
     * [projectSettings], which every signed-in reader opens because it holds their
     * own notification toggle, there is nothing in this response that belongs to a
     * non-admin. A signed-out visitor gets the same 403: the alternative leaks
     * whether a given deployment has an admin session going.
     */
    const val ADMIN_SETTINGS: String = "/api/admin/settings"

    /**
     * `POST` — set one instance-wide switch, from a [SetInstanceSettingRequest].
     * Returns the whole refreshed [AdminSettingsState]. Admin only.
     *
     * The home for the General tab's switches: whether projects may be published,
     * and what each tier of signed-in person may do (LNL-192). One route naming the
     * switch in its body rather than a route per switch, because unlike
     * [MCP_ENABLED] there is no second caller with a different
     * permission to keep apart — every switch here answers to the one admin gate —
     * so the body naming which switch is a value, not an authorization decision.
     * Answers with the same state the dialog loaded, so the write never merges two
     * objects.
     */
    const val ADMIN_INSTANCE_SETTINGS: String = "/api/admin/instance-settings"

    /**
     * `POST` — set who may hold an account here, from a [SetAdmissionPolicyRequest].
     * Returns the whole refreshed [AdminSettingsState]. Admin only.
     *
     * Its own route rather than a sixth [InstanceSettingKey], because admission is
     * not a switch: it has three values, and — alone among the things on this
     * screen — the deployment's configuration can make one of them unhonourable, so
     * the write has a refusal to make that no boolean setter has. See
     * [AdmissionState].
     */
    const val ADMIN_ADMISSION: String = "/api/admin/admission"

    /**
     * `POST` — say what rung one audience arrives at in a **newly created** project,
     * from an [AudienceGrant]. Returns the whole refreshed [AdminSettingsState]. Admin
     * only (LNL-195).
     *
     * Its own route for [ADMIN_ADMISSION]'s reason and one more: it is neither a boolean
     * nor a single value but one of three rows, and — like a project's own audience
     * write — it has a refusal to make, since the guest row answers to the
     * public-projects veto here exactly as it does there. One audience per request, so
     * two administrators editing different rows cannot revert each other.
     */
    const val ADMIN_NEW_PROJECT_AUDIENCE: String = "/api/admin/new-project-audience"

    /**
     * `POST` — put the instance's projects in a given order, from a [ProjectOrder].
     * Returns the whole refreshed [AdminSettingsState]. Admin only.
     *
     * Under `/api/admin` rather than beside the picker's `/api/projects`, for two
     * reasons that point the same way: the whole surface is admin-only, so it sits
     * with the other admin writes that share [adminCaller]'s single gate; and the
     * reorder is an instance-wide statement — "these are the projects, in this
     * order" — not a change to any one project, so a `/api/projects/{id}`-shaped
     * route would be the wrong shape. It answers with the same state the dialog
     * loaded, so the write never merges two objects. See ADMIN_SETTINGS.
     *
     * The base path a [DELETE] on one project hangs off too — [adminProject].
     */
    const val ADMIN_PROJECTS: String = "/api/admin/projects"

    /** `POST` — put the instance's projects in a given order. See [ADMIN_PROJECTS]. */
    const val ADMIN_PROJECT_ORDER: String = "$ADMIN_PROJECTS/order"

    /**
     * `DELETE` — remove a project, everything in it, and every file behind any of
     * it. Returns the whole refreshed [AdminSettingsState]. Admin only.
     *
     * The instance-settings twin of the picker's `DELETE /api/projects/{id}`, which
     * predates it and is also admin-gated. This one exists so the deletion the
     * Projects tab offers returns the fresh directory in one round-trip, the way
     * every other write from that dialog does, rather than a bare 204 the dialog
     * would then have to chase with a reload. See ProjectRepository.delete.
     */
    fun adminProject(id: Long): String = "$ADMIN_PROJECTS/$id"

    /**
     * `GET` — the projects this caller may see, as a [ProjectListState].
     *
     * Filtered server-side: a signed-out visitor gets public projects only, not
     * "all projects, hidden in the UI". `POST` here creates one (admin only).
     */
    const val PROJECTS: String = "/api/projects"

    /**
     * `GET` — a project's whole board as a [BoardState]: statuses, issues,
     * vocabularies and the caller's affordances, in one round-trip.
     *
     * `{id}` is the project id, or its *name* for the embed's `?project=`
     * parameter — see [BOARD_BY_NAME].
     */
    fun board(projectId: Long): String = "/api/projects/$projectId/board"

    /**
     * `GET` — resolve the embed's `?project=<name>` to a board.
     *
     * A separate route rather than an overload of [board], because a name is not
     * an id and a route that took either would have to guess which it was
     * handed. A project literally named "42" is not a stretch.
     */
    const val BOARD_BY_NAME: String = "/api/board-by-name"

    /** `PUT` to update, `DELETE` to remove. Admin only, enforced server-side. */
    fun project(id: Long): String = "$PROJECTS/$id"

    /**
     * `GET` — this project's forums as a [ForumListState]. `POST` — create one,
     * from a [ForumEdit].
     *
     * Reading needs only what reading the project needs; creating is project
     * administrator only, and enforced at the route rather than by the client
     * hiding a button. The forum feature's master toggle is *not* checked here,
     * deliberately: it is a client-side flag whose job is to keep the feature out
     * of public view rather than to protect anything. See LNL-30.
     */
    fun forums(projectId: Long): String = "$PROJECTS/$projectId/forums"

    /**
     * `PUT` to rename and re-describe, `DELETE` to remove. Project administrator
     * only.
     *
     * Scoped under the project rather than a bare `/api/forums/{id}`, so the
     * route can prove the forum it was handed really is this project's before it
     * writes — naming another project's forum answers 404 rather than editing
     * it. The vocabulary routes draw the same line; see Labels.sq's
     * `findByIdInProject`.
     */
    fun forum(projectId: Long, forumId: Long): String = "$PROJECTS/$projectId/forums/$forumId"

    /**
     * `POST` — put this project's forums in a given order, from a [ForumOrder].
     * Project administrator only.
     *
     * Its own route rather than a field on [forum], because reordering is a
     * statement about the whole list and applying it one row at a time would
     * pass through orders nobody asked for. See ForumRepository.reorder.
     */
    fun forumOrder(projectId: Long): String = "$PROJECTS/$projectId/forums/order"

    /**
     * `GET` — a forum's posts as a [ForumPostListState]. `POST` — start one,
     * answering with a [ForumDraftRef].
     *
     * `POST` creates an **empty** post rather than a finished one, and the shape
     * is load-bearing rather than clumsy: the composer's image button needs a row
     * to hang an attachment off before there is a body to put the image in. The
     * body arrives at [forumPost] below. Cancelling deletes the draft; see
     * ForumPosts.sq's `is_draft`.
     *
     * Nested under the forum, which is nested under the project, so that both ids
     * in the path are claims the route checks rather than decoration. Two levels
     * of that is a long URL and it is the same trade [forum] makes.
     */
    fun forumPosts(projectId: Long, forumId: Long): String =
        "$PROJECTS/$projectId/forums/$forumId/posts"

    /**
     * `GET` — one post and its comments as a [ForumPostDetail]. `PUT` — publish
     * or re-save it, from a [ForumPostEdit]. `DELETE` — remove it, and everything
     * under it.
     *
     * Reading needs only what reading the project needs. Writing is the author's
     * or a system administrator's; deleting is either of those **or** the project
     * administrator's. That asymmetry is LNL-30's decision rather than an
     * oversight — see `AccessControl.canDeleteForumContent`.
     */
    fun forumPost(projectId: Long, forumId: Long, postId: Long): String =
        "$PROJECTS/$projectId/forums/$forumId/posts/$postId"

    /**
     * `POST` — start a comment on this post, answering with a [ForumDraftRef].
     *
     * Empty, for [forumPosts]' reason: a comment can carry images too, so its row
     * exists before its text does.
     *
     * There is no `GET` here. A post's comments arrive with the post — see
     * [ForumPostDetail] — because a comment list is not something anyone reads
     * apart from the thing it is about, and a second round-trip would render the
     * post above an empty space that fills in a moment later.
     */
    fun forumComments(projectId: Long, forumId: Long, postId: Long): String =
        "$PROJECTS/$projectId/forums/$forumId/posts/$postId/comments"

    /**
     * `PUT` — publish a comment, from a [ForumCommentEdit]. `DELETE` — remove it.
     *
     * Same two rules as [forumPost], for the same reasons.
     */
    fun forumComment(projectId: Long, forumId: Long, postId: Long, commentId: Long): String =
        "$PROJECTS/$projectId/forums/$forumId/posts/$postId/comments/$commentId"

    /**
     * `POST` — subscribe or unsubscribe the caller from this forum's new-post
     * e-mails, from a [NotificationSubscriptionRequest]. Returns the refreshed
     * [ForumPostListState].
     *
     * Signed-in and able to read the project is the check, plus an address to
     * send to — the same rule [issueNotification] applies, and pointedly **not**
     * the project administrator's: managing your own inbox is not managing the
     * forum. A caller with no address on their profile is refused rather than
     * silently subscribed, because a subscription that can never deliver anything
     * is a promise the server cannot keep.
     *
     * Answers with the post list rather than a bare acknowledgement, so the pane
     * re-renders the pill from the server's truth rather than from what the click
     * assumed. Every write in this API does the same.
     */
    fun forumNotification(projectId: Long, forumId: Long): String =
        "${forum(projectId, forumId)}/notification"

    /**
     * `POST` — subscribe or unsubscribe the caller from this post's comment
     * e-mails. Returns the refreshed [ForumPostDetail].
     *
     * Same rule and same refusal as [forumNotification]. Note that a post's
     * author is already subscribed when it is published, so pressing this on your
     * own post is normally an *un*subscribe — see the server's ForumPostRoutes.
     */
    fun forumPostNotification(projectId: Long, forumId: Long, postId: Long): String =
        "${forumPost(projectId, forumId, postId)}/notification"

    /**
     * `POST` — record that the caller has read this post. Returns the refreshed
     * [ForumPostListState], so the row's unread dot clears in the same round-trip.
     *
     * A `POST` rather than a side effect of the `GET` above, and the reasoning is
     * on the server's `ReadStore`: a `GET` that changes state can be performed on
     * somebody's behalf by a retry or a mail client rendering a link preview, and
     * the moment a post is *fetched* is not the moment it is *viewed* — LNL-62's
     * window is reused, so a post can arrive without a window opening at all.
     *
     * What it actually records is the forum's high-water mark, moved to **this
     * post's** creation time. So reading the newest post marks the older ones read
     * as well; see `ReadStore` and LNL-64's comment, where that consequence is
     * accepted rather than worked around.
     *
     * Answers with the list rather than the post because the dot is on a list row,
     * and — since LNL-62 — that list is on screen beside the window the post opened
     * in.
     */
    fun forumPostRead(projectId: Long, forumId: Long, postId: Long): String =
        "${forumPost(projectId, forumId, postId)}/read"

    /**
     * `GET` — whether anything in the Discussion tab is new, as a
     * [DiscussionUnreadState].
     *
     * Instance-wide, unlike everything else forum-shaped here, because the badge it
     * drives is on the tab strip rather than in a project. Narrowed server-side to
     * the projects the caller can read, so losing sight of one removes its
     * contribution — which is LNL-64's acceptance criterion and the reason this is
     * not a field on [forums].
     *
     * Never 401s: signed out is `false`, matching [SESSION] and the conversation
     * list. A visitor with no account has no read marks, and treating that as "all
     * of it is unread" would put a permanent dot on a tab.
     */
    const val DISCUSSION_UNREAD: String = "/api/discussion/unread"

    /**
     * `POST` — upload a file into a forum post's body, answering with an
     * [AttachmentRef].
     *
     * Not nested under the project and the forum, unlike everything above, and the
     * inconsistency is deliberate: this is the twin of [issueAttachments], which
     * is `/api/issues/{id}/attachments`, and the editor that calls it holds a post
     * id and nothing else. The route resolves the post's forum and project itself
     * and gates on the same rule the `PUT` does, so the shorter path proves
     * exactly as much — the ids in the longer form were only ever there to be
     * checked, and here there is nothing to check them against.
     */
    fun forumPostAttachments(postId: Long): String = "/api/forum-posts/$postId/attachments"

    /** `POST` — the same, for a forum comment. See [forumPostAttachments]. */
    fun forumCommentAttachments(commentId: Long): String = "/api/forum-comments/$commentId/attachments"

    // ── In-app notifications (LNL-109) ───────────────────────────────────────

    /**
     * `GET` — the caller's [NotificationListState] (newest first, with the total
     * unread count). `DELETE` — clear all of the caller's notifications, answering
     * with the now-empty refreshed [NotificationListState].
     *
     * Instance-wide, like [DISCUSSION_UNREAD] and the conversation list: a
     * notification belongs to a person, not a project, so there is no project id in
     * the path. Signed out is an empty state on the `GET`, never a 401 — the panel
     * asks before it knows who is signed in. The `DELETE` requires a caller (there
     * is nothing to clear for nobody).
     */
    const val NOTIFICATIONS: String = "/api/notifications"

    /**
     * `GET` — the caller's unread count alone, as a [NotificationCountState].
     *
     * The bell's five-minute poll (signed-in only). Its own route rather than a
     * field on the list so the poll is one indexed count, never the whole list.
     * Signed out is a zero count, never a 401.
     */
    const val NOTIFICATIONS_UNREAD_COUNT: String = "/api/notifications/unread-count"

    /**
     * `POST` — mark all of the caller's notifications read, answering with the
     * refreshed [NotificationListState]. Requires a caller.
     */
    const val NOTIFICATIONS_READ_ALL: String = "/api/notifications/read-all"

    /**
     * `POST` — mark one notification read, answering with the refreshed
     * [NotificationListState]. The row must belong to the caller; another user's id
     * is not found rather than forbidden, the same silence the store keeps.
     */
    fun notificationRead(id: Long): String = "/api/notifications/$id/read"

    /**
     * `DELETE` — dismiss (hard-delete) one notification, answering with the
     * refreshed [NotificationListState]. Caller-scoped like [notificationRead].
     */
    fun notification(id: Long): String = "/api/notifications/$id"

    // ── Private messages (LNL-60) ────────────────────────────────────────────

    /**
     * `GET` — the caller's [ConversationListState]. `POST` — start a conversation
     * from a [ConversationStart], answering with a [ConversationDraft].
     *
     * ── Not under `/api/projects`, and that is the whole shape of the feature ─
     *
     * Every other route above hangs off a project, because everything else in
     * Lunicle is *in* one. Conversations are instance-wide by LNL-30's decision —
     * see the server's Conversations.sq — so there is no project id to put in this
     * path, and inventing one would make the uniform `(user, project)` permission
     * shape look available where it is not. The gate here is membership of the
     * conversation; see `AccessControl.canReadConversation`.
     *
     * The project *does* bound who may be put in one — "anyone who can see a
     * project you can see" — and that is checked at the `POST`, against the
     * caller's session, not read out of the path.
     *
     * `POST` creates an **empty** conversation and an empty first message rather
     * than a finished one, for [forumPosts]' reason: the composer's image button
     * needs a row to hang an attachment off before there is a body to put the
     * image in. Both are created at once because a message needs a conversation
     * and a new conversation has nothing to hang off itself. **This is the moment
     * membership freezes** — LNL-30 settles that you cannot add somebody to an
     * existing conversation — so the body carries everyone who will ever be in it.
     * Cancelling deletes the pair; see [conversation].
     */
    const val CONVERSATIONS: String = "/api/conversations"

    /**
     * `GET` — one conversation and its messages as a [ConversationDetail].
     * `DELETE` — throw away a conversation that was **started and never sent**.
     *
     * The `DELETE` is the composer's Cancel and nothing else: it refuses unless
     * the caller started this conversation and nothing has ever been published in
     * it. There is deliberately no "leave" and no "delete this thread" — a
     * conversation with something in it belongs to everybody in it, and membership
     * is fixed, so one participant removing it would be one participant deciding
     * for the rest. See the server's `ConversationRepository`.
     */
    fun conversation(id: Long): String = "$CONVERSATIONS/$id"

    /**
     * `POST` — start a reply, answering with a [ConversationDraft].
     *
     * Empty, for [forumComments]' reason: a reply can carry images too, so its row
     * exists before its text does.
     *
     * There is no `GET` here. A conversation's messages arrive with the
     * conversation — see [ConversationDetail] — because a message list is not
     * something anyone reads apart from the thread it is in.
     */
    fun conversationMessages(conversationId: Long): String = "${conversation(conversationId)}/messages"

    /**
     * `PUT` — publish a message, from a [MessageEdit]. `DELETE` — remove it.
     * Both answer with the refreshed [ConversationDetail].
     *
     * There is no way to *edit* a published message, for anybody including its
     * author, and the `PUT` is publish-only rather than publish-or-re-save — which
     * is where this differs from [forumPost], whose `PUT` is both. A conversation
     * records no history, so an edited message would be indistinguishable from one
     * that always said that. See the server's Messages.sq.
     */
    fun conversationMessage(conversationId: Long, messageId: Long): String =
        "${conversationMessages(conversationId)}/$messageId"

    /**
     * `POST` — record that the caller has read this conversation. Returns the
     * refreshed [ConversationListState].
     *
     * The mark is set to the newest published message in the thread, so it says
     * "everything currently here" rather than naming a message the client chose —
     * which is the truthful thing for a transcript that arrives whole, and means a
     * client cannot mark a conversation read further than it has been written.
     *
     * A `POST` rather than a side effect of the `GET`, for [forumPostRead]'s
     * reasons. Answers with the *list* because the count it clears is on a list row.
     */
    fun conversationRead(id: Long): String = "${conversation(id)}/read"

    /**
     * `POST` — upload a file into a message's body, answering with an
     * [AttachmentRef].
     *
     * Not nested under the conversation, matching [forumPostAttachments] and for
     * its reason: the editor that calls this holds a message id and nothing else,
     * and the route resolves the conversation itself and runs the same gate the
     * `PUT` does.
     */
    fun messageAttachments(messageId: Long): String = "/api/messages/$messageId/attachments"

    /**
     * `GET` — a project's [ProjectSettingsState].
     *
     * Openable by every signed-in reader, but **narrowed** by role like [board]:
     * an admin gets the vocabularies (with usage counts) and every account's
     * grants; a non-admin gets none of that — those sections are omitted, not
     * flagged read-only — and only the notification fields that are theirs to
     * change. See ProjectSettingsState's preamble.
     */
    fun projectSettings(id: Long): String = "$PROJECTS/$id/settings"

    /**
     * `POST` — switch this project's discussions and messages on or off, from a
     * [ProjectFeatures]. Returns the refreshed [ProjectSettingsState]. Project
     * administrator only (LNL-96).
     *
     * Its own route rather than a field on the identity `PUT` [project], because
     * that write is system-administrator only and these two flags are a project
     * administrator's to set — folding them in would gate them on the wrong role.
     */
    fun projectFeatures(id: Long): String = "$PROJECTS/$id/features"

    /**
     * `POST` — set this project's new-ticket requirements (must a ticket carry a
     * label, a component). LNL-106. Its own route beside [projectFeatures] and for
     * its reason: a project administrator's to set, not the system owner's.
     */
    fun projectRequirements(id: Long): String = "$PROJECTS/$id/requirements"

    /**
     * `POST` — set this project's board-display settings (whether cards show the
     * author). LNL-157. Returns the refreshed [ProjectSettingsState]. Its own route
     * beside [projectRequirements] and for its reason — a project administrator's to
     * set — but distinct from it because a display choice is not a requirement.
     */
    fun projectDisplay(id: Long): String = "$PROJECTS/$id/display"

    /**
     * `POST` — subscribe or unsubscribe the caller from this project's new-issue
     * e-mails, from a [NotificationSubscriptionRequest]. Returns the refreshed
     * [ProjectSettingsState].
     *
     * Not admin-gated: managing your own subscription is not configuring the
     * project. Signed-in and able to read the project is the whole check, plus an
     * address to send to.
     */
    fun projectNewIssueNotification(id: Long): String = "$PROJECTS/$id/notifications/new-issue"

    /**
     * `GET` — this project's last compiled [StatisticsState], without compiling
     * anything.
     *
     * Answers from the cache and returns immediately, so the dialog has something
     * on screen before any network call to GitHub is attempted. Whether those
     * numbers have aged out is [StatisticsState.isStale], and acting on that is
     * [projectStatisticsRefresh]'s job.
     *
     * Readable by anyone who may read the project — the counts are not
     * admin-only, though the repository that feeds them is configured by an admin.
     * Worth naming: for a private repository the commit count tells every
     * signed-in reader that the repository exists and roughly how busy it is.
     */
    fun projectStatistics(id: Long): String = "$PROJECTS/$id/statistics"

    /**
     * `POST` — recompile this project's statistics, and return the fresh
     * [StatisticsState].
     *
     * Idempotent inside the server's refresh window: called again a minute later
     * it makes no network calls and returns the same numbers. That is what makes
     * it safe on a route any reader can reach — the ceiling on how often this
     * server calls GitHub is enforced here, not in the browser, and there is
     * deliberately no parameter to bypass it.
     *
     * A `POST` rather than a `GET` because it writes: on a cold or aged-out cache
     * it makes outbound calls and replaces the stored snapshot. Nothing about it
     * is safe to retry blindly or prefetch, which is precisely what `GET` invites.
     */
    fun projectStatisticsRefresh(id: Long): String = "$PROJECTS/$id/statistics/refresh"

    /**
     * `POST` — add a row to one of a project's vocabularies, from a
     * [VocabularyAdd].
     *
     * `{kind}` is a [VocabularyKind.key]. One route family for all five rather
     * than five, because they differ only in rules the server owns — see
     * VocabularyKind.
     */
    fun vocabulary(projectId: Long, kind: VocabularyKind): String =
        "$PROJECTS/$projectId/vocabulary/${kind.key}"

    /**
     * `PUT` to rename (and set a status's closing flag) from a [VocabularyEdit],
     * `DELETE` to remove.
     *
     * Nested under the project rather than `/api/vocabulary/{id}`, so the server
     * can prove the row belongs to the project in the path — a row id from another
     * project answers 404 here rather than being edited. Admin is admin
     * everywhere, so this is not what stops an attack; it is what stops a
     * confused client from silently renaming the wrong project's "Closed".
     */
    fun vocabularyItem(projectId: Long, kind: VocabularyKind, itemId: Long): String =
        "${vocabulary(projectId, kind)}/$itemId"

    /**
     * `POST` — put one whole vocabulary in the order given, from a
     * [VocabularyOrder].
     *
     * Every kind, labels and components included. The body must name the whole
     * vocabulary, not a move. See VocabularyRepository.reorder.
     */
    fun vocabularyOrder(projectId: Long, kind: VocabularyKind): String =
        "${vocabulary(projectId, kind)}/order"

    /**
     * `POST` — make this sprint the one the board scopes to, or pass a null
     * [SprintActivation.sprintId] to leave the project with none active. Returns
     * the refreshed [BoardState].
     *
     * Not part of the vocabulary family above even though a sprint is one of its
     * kinds, because there is nothing to activate about a label: these three
     * routes are the residue of what makes a sprint more than a name in a list.
     * See SprintRepository.
     *
     * Activation is separate from creation on purpose — writing next quarter's
     * sprints in advance must not yank the board out from under whoever is
     * working in this one.
     */
    fun sprintActivation(projectId: Long): String = "$PROJECTS/$projectId/sprints/active"

    /**
     * `POST` — finish a sprint and roll its unfinished work forward, from a
     * [SprintCompletion]. Returns the refreshed [BoardState].
     *
     * The one genuinely new piece of business logic in sprints, which is why it
     * is a verb in the path rather than a field on a `PUT`: it stamps the
     * completion, deactivates the project if this was the active sprint, and
     * moves the unfinished issues — three writes that mean nothing apart. See
     * SprintRepository.complete.
     */
    fun sprintCompletion(projectId: Long, sprintId: Long): String =
        "$PROJECTS/$projectId/sprints/$sprintId/complete"

    /**
     * `POST` — un-finish a sprint: clear its completion stamp. No body. Returns the
     * refreshed [BoardState].
     *
     * The counterpart [sprintCompletion] never had, added with the Sprints section's
     * per-row action (LNL-196). Completing was a one-way door up to then, and the door
     * had a wrong side: the stamp is the *only* value on a sprint that nobody typed, so
     * a mis-click could not be corrected from any screen.
     *
     * Deliberately **not** the inverse of completion. Reopening clears the stamp and
     * does nothing else — it does not fetch the rolled-forward work back, and it does
     * not re-activate the project's board. Both would be guesses: the issues have been
     * looked at and possibly re-planned since, and "which sprint is being worked in" is
     * its own decision with its own route. See SprintRepository.reopen.
     */
    fun sprintReopening(projectId: Long, sprintId: Long): String =
        "$PROJECTS/$projectId/sprints/$sprintId/reopen"

    /**
     * `POST` — set exactly which issues are in a sprint, from a
     * [SprintMembership]. Returns the refreshed [BoardState].
     *
     * The complete set rather than a delta, the same convention as
     * [IssueOrderUpdate] and [VocabularyOrder]: a retry says the same thing, and
     * two people planning at once cannot interleave into a set neither chose.
     */
    fun sprintIssues(projectId: Long, sprintId: Long): String =
        "$PROJECTS/$projectId/sprints/$sprintId/issues"

    /**
     * `POST` — put one person on one rung in this project, from a [RungGrant].
     *
     * One person per request rather than "here is the whole list": a picker is one
     * intent, and a route that took the table would let a stale screen revoke a grant
     * another administrator made thirty seconds ago just by re-sending what it had on
     * screen.
     */
    fun projectRoles(projectId: Long): String = "$PROJECTS/$projectId/roles"

    /**
     * `POST` — say at what rung a whole audience arrives here, from an
     * [AudienceGrant]. The project's owner's, and refused for guests outright while the
     * instance forbids publishing.
     */
    fun projectAudience(projectId: Long): String = "$PROJECTS/$projectId/audience"

    /**
     * `POST` — add an address holding a rung, from a [PersonAdd]. Nothing is sent; the
     * grant waits to be claimed by a sign-in.
     */
    fun projectPeople(projectId: Long): String = "$PROJECTS/$projectId/people"

    /** `POST` — create a hidden draft issue, returning an [IssueDraft]. */
    fun issues(projectId: Long): String = "$PROJECTS/$projectId/issues"

    /** `GET` an [IssueDetail], `PUT` to publish/update it, `DELETE` to remove it. */
    fun issue(id: Long): String = "/api/issues/$id"

    /**
     * `POST` — move an issue to another column.
     *
     * Its own route because drag-and-drop has no title or description to send,
     * not because it is a lighter permission. It goes through the same
     * `canEditIssue` the editor does. See AccessControl.
     */
    fun issueStatus(id: Long): String = "/api/issues/$id/status"

    /**
     * `POST` — rank a board group, in the order given.
     *
     * `{id}` is the issue that was dragged, and it is there for the permission
     * check: reordering is an edit of that issue's placement, so it goes through
     * the same `canEditIssue` everything else does. The body carries the group.
     *
     * The one write in this API that does not touch `updated_at` — see Issues.sq.
     */
    fun issueOrder(id: Long): String = "/api/issues/$id/order"

    /**
     * `POST` — subscribe or unsubscribe the caller from this issue's update
     * e-mails, from a [NotificationSubscriptionRequest]. Returns the refreshed
     * [IssueDetail].
     *
     * Signed-in and able to read the issue is the check — anyone who can see an
     * issue may ask to hear about it — plus an address to send to.
     */
    fun issueNotification(id: Long): String = "/api/issues/$id/notification"

    /**
     * `POST` — assign an issue, from an [IssueAssignment]. Returns the refreshed
     * [IssueDetail].
     *
     * Its own route for the reason [issueStatus] is not: this one really *is* a
     * lighter permission, and the only one in this API that is. Taking an issue
     * yourself needs the right to be assigned here, which is not the right to edit
     * the issue — that is the whole point of the separate grant, and it is why
     * this cannot simply be a field on the `PUT`. Handing an issue to somebody
     * else, or taking one off them, needs edit rights as well; the server draws
     * that line, not the caller.
     */
    fun issueAssignee(id: Long): String = "/api/issues/$id/assignee"

    /**
     * `POST` — schedule one issue into a sprint, or send it back to the backlog,
     * from an [IssueSprintUpdate]. Returns the refreshed [IssueDetail].
     *
     * Its own route rather than a field on the `PUT` *as well as* being a field on
     * the `PUT`, which is the same arrangement status has: the editor stages the
     * sprint with everything else and saves it in one go, and the card menu writes
     * it immediately without opening the editor. Two gestures, one column.
     *
     * Gated by the ordinary `canEditIssue`, unlike [issueAssignee]: scheduling
     * somebody else's work is editing it, and there is no lighter grant that
     * should let you.
     */
    fun issueSprint(id: Long): String = "/api/issues/$id/sprint"

    /**
     * `POST` — attach this issue to an epic, or detach it, from an
     * [IssueParentUpdate]. Returns the refreshed [IssueDetail] (LNL-55).
     *
     * `{id}` is the *child* — the issue whose parent is changing. The epic-side
     * "add a child" gesture posts here to the chosen child's id, and both remove
     * gestures post null: belonging to an epic is a fact about the child, so it is
     * the child's `canEditIssue` that gates it. Its own route, not a field on the
     * `PUT`, because reparenting is an immediate gesture rather than one of the
     * fields the editor's Save commits — the same shape [issueSprint] has minus the
     * editor half.
     */
    fun issueParent(id: Long): String = "/api/issues/$id/parent"

    /**
     * `POST` — rank one epic's children, in the order given, from a [ChildOrder]
     * (LNL-55). Returns the refreshed [IssueDetail].
     *
     * `{id}` is the *epic*; the body is the whole ordered set of its children.
     * Gated by `canEditIssue` on the epic, like [issueOrder] for a board group.
     */
    fun issueChildrenOrder(id: Long): String = "/api/issues/$id/children/order"

    /** `POST` — create a hidden draft comment on this issue, returning its id. */
    fun comments(issueId: Long): String = "/api/issues/$issueId/comments"

    /** `PUT` to publish/update, `DELETE` to remove. */
    fun comment(id: Long): String = "/api/comments/$id"

    /** `POST` — upload bytes owned by this issue. See [LunicleApi.uploadIssueAttachment]. */
    fun issueAttachments(issueId: Long): String = "/api/issues/$issueId/attachments"

    /** `POST` — upload bytes owned by this comment. */
    fun commentAttachments(commentId: Long): String = "/api/comments/$commentId/attachments"

    /**
     * `GET` — the bytes.
     *
     * This URL appears inside rendered markdown, so it is trivially shareable
     * and will end up pasted places. The route resolves the owning issue and
     * runs `canReadProject` before streaming a byte — the id being unguessable
     * is a second line of defence, not the only one.
     *
     * @param id the attachment's **public** id, not its row id. Since LNL-51
     *   those are different things: the row id was countable, so the whole
     *   instance's attachments could be enumerated by adding one. See
     *   `attachments.public_id` and [isAttachmentId].
     */
    fun attachment(id: String): String = "$ATTACHMENT_PREFIX$id"

    /**
     * `GET` — the same bytes, as a page to read instead of a file to save.
     *
     * The same access check and the same stored `Content-Type`; what differs is
     * `Content-Disposition: inline` plus a `Content-Security-Policy: sandbox`
     * that opens the document in an opaque origin with no script. Only the types
     * in [SANDBOXED_DOCUMENT_MIME_TYPES] are served that way — ask for this URL
     * on anything else and the route answers exactly as [attachment] would, so a
     * hand-typed `/view` can never talk it into rendering a document it would
     * not have rendered anyway.
     *
     * A separate URL rather than a flag on the response because the *renderer*
     * has to know which of the two a link is before the reader clicks it — a
     * download gets `download`, a view gets `target="_blank"` — and the URL is
     * the only thing it has to go on. See [ATTACHMENT_PREFIX].
     */
    fun attachmentView(id: String): String = "$ATTACHMENT_PREFIX$id$ATTACHMENT_VIEW_SUFFIX"

    /**
     * What an attachment URL ends with when it is meant to be read, not saved.
     *
     * Public for the renderer, same as the prefix: it is what tells the two
     * spellings apart in a stored markdown link.
     */
    const val ATTACHMENT_VIEW_SUFFIX: String = "/view"

    /**
     * What every attachment URL starts with.
     *
     * Public because the *renderer* has to recognise one. A link to
     * `/api/attachments/12` is a file the reader downloads and must be drawn as
     * one — filename, size, an icon — where a link to anywhere else is a link.
     * The URL is the only thing the renderer has to tell them apart with: it
     * reads markdown, and markdown has one spelling for both.
     *
     * Matching by prefix means a description that names an attachment of a
     * project the reader cannot see still renders as a download link. That is
     * the right answer — the route itself answers 404 to them, and the
     * alternative is the renderer asking the server about every link it draws.
     *
     * @see se.soderbjorn.lunicle.client.renderMarkdown
     */
    const val ATTACHMENT_PREFIX: String = "/api/attachments/"

    /**
     * Could this be an attachment id at all?
     *
     * The renderer's guard, and it lives here so there is one definition of the
     * shape rather than one per module. It answers about *shape only* — whether
     * such a row exists is the server's question, and the download route answers
     * it with a 404.
     *
     * ── What it is really defending ─────────────────────────────────────────
     *
     * Not the server: nothing derived from a URL segment reaches the filesystem
     * (the path comes from the found row's own `storage_key`), so an id of
     * `../../lunicle.db` finds no row and is a 404 like any other nonsense.
     * What this defends is the *renderer's* decision. A markdown link is drawn as
     * a download affordance — filename, size, an icon, a `download` attribute —
     * when and only when it looks like one of ours, and `[click me](/api/
     * attachments/../../secret)` must not borrow that chrome to look like a file
     * this app is offering.
     *
     * ── Why the alphabet, and not "digits" ──────────────────────────────────
     *
     * It used to be `all { it in '0'..'9' }`, which was right while the URL held
     * a row id and became wrong with LNL-51: a public id is base64url, so letters,
     * `-` and `_` are now perfectly ordinary in one, and the old test would have
     * rendered every newly uploaded attachment as a bare link. The alphabet below
     * is base64url exactly — which still excludes `/` and `.`, so every traversal
     * spelling this has ever been asked to reject is still rejected, and the
     * decimal ids backfilled onto pre-LNL-51 rows still pass, digits being part
     * of base64url.
     */
    fun isAttachmentId(id: String): Boolean =
        id.isNotEmpty() && id.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
}
