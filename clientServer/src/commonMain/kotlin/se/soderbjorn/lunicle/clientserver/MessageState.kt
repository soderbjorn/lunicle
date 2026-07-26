/**
 * The wire types for private messages — the Messages tab's half of LNL-30.
 *
 * Same contract as [BoardState] and [ForumState]: one response holds everything
 * the pane needs to render, including the caller's affordances, because a pane
 * cannot render half of itself. And the affordances are exactly that — the server
 * re-derives every one of them at the route before it writes. See AccessControl's
 * preamble, which is the long version of why a flag on this object grants nothing.
 *
 * ── What is not here, and where it went instead ─────────────────────────────
 *
 * **No user ids on a conversation, only names.** [CommentView] settled that for
 * issues and the reasoning carries: the client has nothing to do with an id it
 * cannot act on, and a directory of who exists is the sort of thing that leaks by
 * being on the wire "just in case". The one place ids *do* cross is
 * [ConversationListState.recipients], because the picker has to send back who was
 * chosen and a display name is not unique enough to name a person with.
 *
 * **Unread state, since LNL-64,** is exactly one field:
 * [ConversationSummary.unreadCount]. LNL-60 predicted that shape and it held —
 * nothing else here needed restructuring. Note there is no `lastReadMessageId` on
 * the wire and no `isUnread` per message: the mark is the server's bookkeeping, and
 * a per-message flag would be the fan-out that Reads.sq exists to avoid, sent to a
 * client that renders one number per row.
 *
 * @see se.soderbjorn.lunicle.clientserver.ApiRoutes.CONVERSATIONS
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.Serializable

/**
 * One conversation in the reader's list.
 *
 * @property participantNames everybody in it **except the reader**, so the client
 *   can title the row without first working out which of the names is its own.
 *   Composed into a heading by the client, which is where the decision about how
 *   many to spell out before saying "and 2 others" belongs — that is a question
 *   about how wide a list row is.
 *
 *   A name may be null-shaped in one case that cannot happen here: a participant
 *   is an account by construction, so unlike an author there is nobody to render
 *   as "a deleted account". A deleted account leaves the conversation outright;
 *   see Conversations.sq.
 * @property lastMessageBody the whole markdown of the most recent message, not a
 *   truncated preview. Where to cut it — and whether to render it as markdown or
 *   flatten it to one line — is a question about the width of a list row, which
 *   is the client's business. A server that truncated would be choosing a
 *   character count for a layout it cannot see.
 * @property lastMessageAt when that message landed, for the row's timestamp and
 *   for the order the list arrives in. The server sorts; the client never
 *   re-sorts.
 * @property unreadCount how many published messages in this conversation the
 *   reader has not seen, **excluding their own** — a badge that counted your own
 *   writing would go up every time you pressed Send.
 *
 *   A number rather than a boolean because the design puts a pill with a count on
 *   the conversation card, and because the Messages *tab*'s badge is the sum of
 *   these: LNL-30 settles that Messages gets a number and Discussion gets a bare
 *   dot, on the grounds that a mailbox is finite and a forum is not. Summing here
 *   rather than sending a separate total is deliberate — one number derived from
 *   another in the same response cannot disagree with it, where a second field
 *   fetched separately eventually would.
 *
 *   Zero for a signed-out caller, who has no conversations at all.
 */
@Serializable
data class ConversationSummary(
    val id: Long,
    val participantNames: List<String> = emptyList(),
    val lastMessageBody: String = "",
    val lastMessageAt: Long = 0,
    val unreadCount: Long = 0,
)

/**
 * The Messages tab: the reader's conversations, and who they may start one with.
 *
 * @property conversations most recently spoken in first — the server's order. A
 * conversation with nothing published in it never appears, which covers both the
 *   one being composed right now and the one somebody abandoned by closing the
 *   tab. See Conversations.sq's `forUser`.
 * @property recipients everybody this caller may message: **anyone who can see a
 *   project the caller can see**, minus the caller. LNL-30's rule, answered by
 *   the server's `ProjectAudience.forProjects`, and enforced again at the route
 *   that creates a conversation — this list is what the picker offers, never what
 *   the server trusts.
 *
 *   It rides on the list rather than being fetched when the composer opens, for
 *   [ForumPostListState.mentionableUsers]'s reason: a modal that puts an
 *   autocomplete up and then makes it work a moment later is worse than one that
 *   works when it appears. For a public instance this is every account, which is
 *   the same size the forum's mention list already is.
 * @property canMessage whether to offer "New message" at all. False for a
 *   signed-out visitor, and — honestly — for a signed-in one who shares no
 *   project with anybody, which is a real state on a fresh instance.
 */
@Serializable
data class ConversationListState(
    val conversations: List<ConversationSummary> = emptyList(),
    val recipients: List<UserOption> = emptyList(),
    val canMessage: Boolean = false,
)

/**
 * One message, rendered.
 *
 * @property authorName resolved server-side, or null once the author's account is
 *   gone — which renders as "a deleted account", exactly as an issue comment
 *   does. Note this is reachable here in a way [ConversationSummary
 *   .participantNames] is not: the account leaves the conversation but its
 *   messages stay.
 * @property agentName the agent that wrote it, or null when a human did. Nothing
 *   sets it today — messages have no MCP surface, by LNL-30's decision — and it
 *   is on the wire anyway so that the day one arrives, the badge is not a client
 *   change as well as a server one.
 * @property isMine whether the reader wrote it. Purely presentational: the design
 *   draws your own messages differently from everybody else's, and the client
 *   cannot work this out for itself because no user ids cross. Emphatically not a
 *   permission — see [canDelete].
 * @property canDelete whether this caller may remove it: the author, or a system
 *   administrator. An affordance; the route re-derives it. LNL-60's acceptance
 *   list said "a project admin can too", which a conversation has none of — see
 *   `AccessControl.canDeleteMessage`, which explains what was done about that.
 */
@Serializable
data class MessageView(
    val id: Long,
    val body: String,
    val authorName: String? = null,
    val agentName: String? = null,
    val createdAt: Long = 0,
    val isMine: Boolean = false,
    val canDelete: Boolean = false,
)

/**
 * One conversation, everything in it, and everything the reader needs to act on
 * it.
 *
 * One response rather than three, for [BoardState]'s contract: a view cannot
 * render half of itself, and a conversation opened from an e-mail's deep link has
 * no list behind it to borrow the rest from.
 *
 * @property participantNames everybody in it except the reader, as on
 *   [ConversationSummary], so a window opened cold can title itself.
 * @property mentionableUsers who the `@` autocomplete may offer here: **the
 *   people in this conversation**, and nobody else. Deliberately much narrower
 *   than the forum's, which is everyone who can see the project — mentioning
 *   somebody who cannot read the thread would be writing a name that reaches
 *   nobody, in the one place where "who can see this" is a closed set.
 *
 *   Note that a mention here produces no e-mail of its own. Every participant is
 *   already being mailed about the message; a second one because their name
 *   appeared in it would be two notifications for one event.
 * @property canReply whether to offer the composer. Membership, and it is fixed
 *   at creation — so unlike most affordances in this codebase, this one can never
 *   change for a given reader. False for a system administrator reading somebody
 *   else's thread, which is the only case where it and the ability to *see* this
 *   response come apart.
 */
@Serializable
data class ConversationDetail(
    val id: Long,
    val participantNames: List<String> = emptyList(),
    val messages: List<MessageView> = emptyList(),
    val mentionableUsers: List<UserOption> = emptyList(),
    val canReply: Boolean = false,
)

/**
 * Start a conversation: who it is with.
 *
 * Ids rather than names, and this is the one place in this file where an id
 * crosses in either direction. A display name is not unique — `users` has no
 * UNIQUE on the column — so a picker that sent names would be a picker that
 * cannot express "that Ada, not the other one". [ConversationListState.recipients]
 * carries the ids for exactly this to send back.
 *
 * The caller is not in this list and must not be: the server adds them. See
 * `ConversationRepository.startConversation`.
 *
 * There is no body here. The conversation is created empty, together with the
 * draft message the composer writes into — see [ConversationDraft], and
 * `ApiRoutes.CONVERSATIONS` for why.
 */
@Serializable
data class ConversationStart(
    val participantIds: List<Long> = emptyList(),
)

/**
 * A conversation and a message that exist but have nothing in them yet — the
 * draft a composer writes into.
 *
 * Two ids, unlike the forum's [ForumDraftRef], because starting a conversation
 * creates both: the message needs a conversation to hang off, and a *new*
 * conversation has nothing to hang off itself. Replying answers with the same
 * type, carrying the conversation the caller already named — redundant there, and
 * one type is better than two that differ by a field the client can ignore.
 *
 * The ids are the whole payload, and the whole point: an inline image needs an
 * owner before there is a body to put it in.
 */
@Serializable
data class ConversationDraft(
    val conversationId: Long,
    val messageId: Long,
)

/** Publish a message: its markdown body. */
@Serializable
data class MessageEdit(
    val body: String,
)
