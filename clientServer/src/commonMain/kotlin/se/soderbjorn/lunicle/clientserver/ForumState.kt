/**
 * The wire types for discussion forums — the Discussion tab's half of LNL-30.
 *
 * Same contract as [BoardState] and [ProjectSettingsState]: one response holds
 * everything the pane needs to render, including the caller's affordances,
 * because a pane cannot render half of itself. And the affordances are exactly
 * that — the server re-derives every one of them at the route before it writes.
 * See AccessControl's preamble, which is the long version of why a flag on this
 * object grants nothing.
 *
 * @see se.soderbjorn.lunicle.clientserver.ApiRoutes.forums
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.Serializable

/**
 * One forum in a project's list.
 *
 * @property description the one-liner under the name, or null. Null and empty
 *   are the same statement and the server only ever sends the former — it
 *   normalises blank to null on write, so the client has one case to render
 *   rather than two that look identical.
 */
@Serializable
data class ForumSummary(
    val id: Long,
    val name: String,
    val description: String? = null,
)

/**
 * A project's forums, and what this caller may do with them.
 *
 * @property forums in the project administrator's chosen order, which is the
 *   only order there is — the client renders the list as it arrives and never
 *   sorts it. See Forums.sq's `position`.
 * @property canManageForums whether to offer create, edit, delete and reorder.
 *   An affordance: the routes refuse a caller who lacks it regardless, so a
 *   client that flips this gets a 403 with extra steps.
 */
@Serializable
data class ForumListState(
    val forums: List<ForumSummary> = emptyList(),
    val canManageForums: Boolean = false,
)

/**
 * Create or rename a forum.
 *
 * One type for both, because they take the same two fields and a second type
 * would be two things to keep in step. Which operation it is, is the method and
 * the route.
 *
 * @property description null or blank both mean "no description"; the server
 *   normalises them to the same stored value.
 */
@Serializable
data class ForumEdit(
    val name: String,
    val description: String? = null,
)

/**
 * A whole project's forums, in the order they should be in.
 *
 * The complete list rather than a moved id and a destination: the server checks
 * it names exactly this project's forums, which is what stops one project's
 * administrator from reaching another's rows, and makes a half-applied reorder
 * impossible. See ForumRepository.reorder.
 */
@Serializable
data class ForumOrder(
    val ids: List<Long> = emptyList(),
)

// ── Posts and comments (LNL-61) ─────────────────────────────────────────────

/**
 * One post in a forum's list.
 *
 * Deliberately without the body. A forum list is a list of *titles* — the body is
 * markdown that may be pages long and carry a dozen images, and sending twenty of
 * them so the pane can render twenty headings would make opening the Discussion
 * tab cost more than opening every post in it. The detail route sends the body.
 *
 * @property authorName resolved server-side to `display_name ?: provider_name`,
 *   or null once the author's account is gone. The client never sees a user id;
 *   see [CommentView], which settled this the same way and for the same reasons.
 * @property agentName the agent that posted it on the author's behalf, or null
 *   when a human did. Nothing sets it today — forums have no MCP surface, by
 *   LNL-30's decision — and it is on the wire anyway so that the day one arrives,
 *   the badge is not a client change as well as a server one.
 * @property commentCount how many published comments the post has. Counted in the
 *   same select as the post; see ForumPosts.sq's `forForum`.
 * @property lastCommentAuthor who last replied, or null when nobody has — and also
 *   null when the last replier's account has since been deleted, which the client
 *   renders exactly as it renders any other missing author.
 * @property lastCommentAt when that reply landed, or null when there is none.
 *
 *   The pair exists because the design's post card is three columns — title, how
 *   many replies, and **who last spoke and how long ago** — and the third is what
 *   answers "is this thread still moving", which a count alone cannot. Sent rather
 *   than derived, because the client has no comments to derive it from: a post list
 *   deliberately carries no bodies. See ForumPosts.sq's `forForum` for what it
 *   costs.
 *
 *   Two fields rather than a nested `ForumCommentView`: a card needs a name and a
 *   date, and sending the whole comment would put every thread's newest body on
 *   the wire for a column that renders neither.
 * @property isUnread whether this post was written after the reader's high-water
 *   mark in this forum, and by somebody else.
 *
 *   A boolean rather than a count, because a post is one thing rather than a
 *   container of things — the count on a forum row would be of *comments*, and
 *   LNL-30 settles that the discussion side gets dots rather than numbers
 *   throughout. False for a signed-out reader, who has no mark and for whom
 *   "unread" would mean "everything".
 *
 *   Note that it says nothing about **comments**: a post you have read stays read
 *   when somebody replies to it. That is the high-water mark's shape rather than
 *   an oversight, and following replies is what LNL-63's Watch pill is for. See
 *   `ForumReads`' note in Reads.sq.
 * @property canDelete whether this caller may remove it. **False for everybody**
 *   since LNL-190 retired discussions: `AccessControl.canDeleteForumContent` answers
 *   no to every caller, the instance owner included. It was the author, a project
 *   administrator, or whoever ran the instance — and why the project administrator
 *   was in this rule and not in the editing one is a decision to re-make rather than
 *   a rung to restore. An affordance either way: the route re-derives it.
 */
@Serializable
data class ForumPostSummary(
    val id: Long,
    val title: String,
    val authorName: String? = null,
    val agentName: String? = null,
    val createdAt: Long = 0,
    val commentCount: Long = 0,
    val lastCommentAuthor: String? = null,
    val lastCommentAt: Long? = null,
    val isUnread: Boolean = false,
    val canDelete: Boolean = false,
)

/**
 * A forum's posts, and what this caller may do in it.
 *
 * @property posts newest first, which is the order the server chose and the
 *   client never re-sorts. Unlike [ForumListState.forums] this is a chronology
 *   rather than a curation — nobody arranges a post list. See ForumPosts.sq.
 * @property canPost whether to offer "New post" and "Add comment". One flag for
 *   both, because LNL-30 gives them one rule: any signed-in user who can see the
 *   project may do either. An affordance; the routes refuse regardless.
 * @property mentionableUsers who the `@` autocomplete may offer in a **new post**.
 *
 *   It rides on the list rather than being fetched when the composer opens, and
 *   the reason is that there is nothing else for a new post to have got it from:
 *   a comment's autocomplete comes off [ForumPostDetail], because the post it is
 *   answering was fetched first, and a post being written has no such parent. The
 *   alternative — a request when the composer opens — would put the modal up with
 *   an autocomplete that silently does not work yet, which is worse than the
 *   handful of names travelling with a list that is fetched once per forum.
 *
 *   Empty when [canPost] is false, for the reason [ForumPostDetail] gives: a
 *   reader with no composer has no mention to complete, so shipping them the
 *   instance's account list would be a directory for nothing.
 * @property notifyOnNewPosts whether the caller has asked to be e-mailed when
 *   somebody posts in **this forum**. Drives the pane's Watch pill; false for a
 *   signed-out caller, who has nowhere to send.
 *
 *   It rides on the post list rather than on [ForumListState], and that is a
 *   choice rather than convenience: the watch is per-forum, and [ForumListState]
 *   describes the whole list at once. A flag there would either have to be a flag
 *   per row — a subscription read per forum on every render of a pane that shows
 *   one — or would silently mean "the selected one", which that response has no
 *   notion of. This response is fetched exactly when the selection changes.
 * @property canReceiveEmailNotifications whether the caller has an address at
 *   all. The pill is hidden without it, for [IssueDetail
 *   .canReceiveEmailNotifications]'s reason: promising an e-mail we cannot send
 *   would be a dead control.
 */
@Serializable
data class ForumPostListState(
    val posts: List<ForumPostSummary> = emptyList(),
    val canPost: Boolean = false,
    val mentionableUsers: List<UserOption> = emptyList(),
    val notifyOnNewPosts: Boolean = false,
    val canReceiveEmailNotifications: Boolean = false,
)

/**
 * Whether anything in the Discussion tab is new to the caller.
 *
 * One boolean, and its own response, which is worth two sentences of defence.
 *
 * It is a **boolean** because LNL-30 settles that the Discussion tab wears a dot
 * and not a number: forum volume is unbounded, and a count there creates
 * inbox-zero pressure for something nobody is obliged to read. Asking the question
 * as a boolean is also what lets the server stop at the first unread row rather
 * than counting a table.
 *
 * It is its **own response** rather than a field on [ForumListState] because the
 * badge is instance-wide and that list is per-project. The tab strip is app chrome:
 * a dot that appeared only while the right project happened to be selected would
 * tell the reader nothing they could not already see in the post list underneath
 * it, and LNL-64's "losing visibility of a project removes its contribution to
 * every badge" would have nothing to be true about. So this spans every project the
 * caller can read, narrowed by `AccessControl.canReadProject` at the route.
 *
 * @property hasUnreadPosts false for a signed-out caller, who has no read marks and
 *   for whom every post would otherwise be new for ever.
 */
@Serializable
data class DiscussionUnreadState(
    val hasUnreadPosts: Boolean = false,
)

/**
 * A comment on a post, rendered. Flat — there is no parent id, by LNL-30's
 * settled decision. See [CommentView], whose fields these mirror.
 */
@Serializable
data class ForumCommentView(
    val id: Long,
    val body: String,
    val authorName: String? = null,
    val agentName: String? = null,
    val createdAt: Long = 0,
    val canDelete: Boolean = false,
)

/**
 * One post, everything in it, and everything the reader needs to act on it.
 *
 * One response rather than three (post, comments, who may be mentioned), for
 * [BoardState]'s contract: a view cannot render half of itself, and a post window
 * opened from a deep link has no board behind it to borrow the rest from.
 *
 * @property forumName carried so the view can say which room this is without a
 *   second request. LNL-62 opens this in a window whose title bar needs it, and a
 *   window opened from a deep link may have no forum list loaded at all.
 * @property mentionableUsers who the `@` autocomplete may offer here — **everyone
 *   who can see the project**, which for a public project is every account on the
 *   instance. Deliberately a wider set than the issue editor's, which is "anyone
 *   holding a role here": LNL-30 chose visibility as the rule for forums, and a
 *   public project's forum is a room anybody with an account is standing in. See
 *   the server's `ProjectAudience`.
 * @property canComment whether to offer "Add comment". Same rule as
 *   [ForumPostListState.canPost] and sent again here because this response is
 *   what a window opened by deep link will have.
 * @property canDelete whether this caller may delete the post itself.
 * @property notifyOnComments whether the caller has asked to be e-mailed about
 *   new comments on this post. Drives the Watch pill on the title row.
 *
 *   True without anybody having pressed anything, for the post's **author**:
 *   publishing a post subscribes its writer, exactly as publishing an issue does.
 *   See the server's ForumPostRoutes, where that decision is argued — including
 *   why it costs the author nothing on their own comment.
 * @property canReceiveEmailNotifications whether the caller has an address at
 *   all. See [ForumPostListState.canReceiveEmailNotifications].
 */
@Serializable
data class ForumPostDetail(
    val id: Long,
    val forumId: Long,
    val forumName: String = "",
    val title: String,
    val body: String,
    val authorName: String? = null,
    val agentName: String? = null,
    val createdAt: Long = 0,
    val comments: List<ForumCommentView> = emptyList(),
    val mentionableUsers: List<UserOption> = emptyList(),
    val canComment: Boolean = false,
    val canDelete: Boolean = false,
    val notifyOnComments: Boolean = false,
    val canReceiveEmailNotifications: Boolean = false,
)

/**
 * A row that exists but has nothing in it yet — the draft a composer writes into.
 *
 * The id is the whole payload, and the whole point: an inline image needs an owner
 * before there is a body to put it in, so the composer creates the row on open and
 * fills it in on save. See the server's `ForumPostRepository` preamble, and
 * `IssueBackingViewModel` for the same dance on the issue side.
 */
@Serializable
data class ForumDraftRef(
    val id: Long,
)

/** Publish a post: its title and its markdown body. */
@Serializable
data class ForumPostEdit(
    val title: String,
    val body: String,
)

/** Publish a comment: its markdown body. */
@Serializable
data class ForumCommentEdit(
    val body: String,
)

/**
 * How long a post title may be.
 *
 * Here rather than beside the server's other forum limits (`MAX_FORUM_NAME_LENGTH`
 * lives in `Forums.kt`) because this is the one of them the *client* also has to
 * know: the composer disables its own OK button on a title that is too long, and
 * a second copy of the number in the browser is the drift this module exists to
 * prevent. The forum name limit has no client-side check to disagree with, so it
 * stayed where it was rather than being moved for symmetry.
 *
 * Bounded because a title renders as a row in the post list, so a kilobyte of it
 * would not break the server — it would make the forum unreadable for everyone in
 * the project. Longer than a forum name because a title is a sentence and a forum
 * name is a label.
 */
const val MAX_POST_TITLE_LENGTH: Int = 200
