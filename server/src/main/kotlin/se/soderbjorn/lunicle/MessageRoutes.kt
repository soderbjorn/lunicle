/**
 * The private-message routes: the caller's conversations, one conversation, and
 * writing in it.
 *
 * Its own file for the reason [ForumPostRoutes] is: every route in it runs one
 * gate, and it is a gate that appears nowhere else in this server. Everything in
 * `BoardRoutes` and its siblings asks "may this caller see that project?"; every
 * route here asks "is this caller in that conversation?", because a conversation
 * is instance-wide and belongs to no project. Two gates in one file is how a
 * handler ends up under the wrong one — and this pair is the least alike of any
 * two in the codebase.
 *
 * ── The one project question, asked once ────────────────────────────────────
 *
 * There is exactly one place a project is consulted: **who may be put in a new
 * conversation**. LNL-30's rule is "a user may message anyone who has visibility
 * of a project that they themselves can see", which is [ProjectAudience
 * .forProjects] over the projects the caller can read, and it is enforced at
 * `POST /api/conversations` rather than only in the picker. That distinction is
 * the acceptance criterion: the picker is a list the browser was handed, and a
 * browser can send an id it was never offered.
 *
 * It is asked **once, at creation**, and never again. Somebody who later loses
 * the last project they shared with you stays in the conversation, and both of
 * you keep reading it. That follows from membership being fixed (LNL-30) and it
 * is the right answer anyway: a thread you are already in silently emptying
 * itself because an administrator revoked a role elsewhere would be a worse
 * surprise than the one it prevents.
 *
 * ── The two-step write, and the extra step at the top ───────────────────────
 *
 * `POST /api/conversations/{id}/messages` creates an **empty** message and
 * answers with its id; `PUT .../messages/{id}` fills it in. The issue editor, the
 * comment modal and the forum composer have all done exactly this since they were
 * written, and reusing the shape is what let the whole attachment machinery be
 * reused unchanged: the image button needs a row to hang an attachment off, and a
 * body being typed has no row yet.
 *
 * A *new* conversation adds one step, because there is nothing to hang the first
 * draft off either: `POST /api/conversations` creates the conversation and its
 * first empty message together, and answers with both ids. See
 * `ConversationRepository.startConversation`.
 *
 * ── What is deliberately not here ───────────────────────────────────────────
 *
 * **No editing.** A published message cannot be changed by anybody, its author
 * included, so unlike [forumPostRoutes] the `PUT` below is publish-only. See
 * Messages.sq for why.
 *
 * **No history, and no read state.** LNL-30 settles the first; the second is
 * LNL-64, and nothing below writes a high-water mark or reads one.
 *
 * **No MCP.** LNL-30 is explicit that the forum feature gets no MCP tools, and it
 * is more emphatic here than anywhere: `McpTools` is untouched, so there is no
 * path by which an agent holding somebody's token can read their private
 * conversations. That is worth stating rather than leaving as an absence.
 *
 * **No "leave conversation".** Membership is fixed at creation, in both
 * directions. The one `DELETE` on a conversation is the composer's Cancel; see
 * `ConversationRepository.discardUnsentConversation`.
 *
 * @see Conversations
 * @see AccessControl.canReadConversation
 */
package se.soderbjorn.lunicle

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.ConversationDetail
import se.soderbjorn.lunicle.clientserver.ConversationDraft
import se.soderbjorn.lunicle.clientserver.ConversationListState
import se.soderbjorn.lunicle.clientserver.ConversationStart
import se.soderbjorn.lunicle.clientserver.ConversationSummary
import se.soderbjorn.lunicle.clientserver.MessageEdit
import se.soderbjorn.lunicle.clientserver.MessageView
import se.soderbjorn.lunicle.clientserver.UserOption

/** Mount the private-message routes. Called by [boardRoutes]. */
fun Route.messageRoutes(deps: BoardDependencies) {
    /**
     * The caller's conversations, and who they may start one with.
     *
     * Signed out is an empty list rather than a 401, matching [ApiRoutes.SESSION]
     * and the ui-settings `GET`: the Messages tab asks this before it knows
     * whether anyone is signed in, and "you have no conversations" is the true
     * answer either way. `canMessage` is false, so nothing is offered.
     */
    get(ApiRoutes.CONVERSATIONS) {
        val user = call.caller(deps)
        if (user == null) {
            call.respond(ConversationListState())
            return@get
        }
        call.respond(deps.conversationListFor(user))
    }

    /**
     * Start a conversation: the empty pair a composer writes into.
     *
     * The recipient rule is enforced *here*, not in the picker — see this file's
     * preamble. Note the order of the two refusals: a body that names nobody is a
     * 409 with words (the person can fix it by choosing somebody), and a body that
     * names somebody they may not reach is a 403 (they cannot).
     */
    post(ApiRoutes.CONVERSATIONS) {
        val user = call.caller(deps) ?: run {
            call.respond(HttpStatusCode.Forbidden, "You have to be signed in to send a message.")
            return@post
        }
        val body = call.receiveOrNull<ConversationStart>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed conversation.")
            return@post
        }
        // Deduplicated, and the caller's own id dropped rather than refused: a
        // client that includes the sender is describing the same conversation, and
        // the repository puts them in regardless. Naming *only* yourself does then
        // reach the empty-set refusal below, which is the correct answer — a
        // private note to yourself is a different feature nobody asked for.
        val wanted = body.participantIds.toSet() - user.id
        val allowed = deps.messageableUsers(user).map { it.id }.toSet()
        if (!allowed.containsAll(wanted)) {
            // One answer for "no such account" and "not somebody you share a
            // project with", for readableProject's reason: distinguishing them
            // would turn this route into a way to ask whether a given id exists.
            call.respond(HttpStatusCode.Forbidden, "You cannot message everybody on that list.")
            return@post
        }
        deps.runMessageWrite(call) {
            val (conversationId, messageId) = deps.conversations.startConversation(user.asAuthor(), wanted)
            call.respond(ConversationDraft(conversationId = conversationId, messageId = messageId))
        }
    }

    /** One conversation and everything said in it. */
    get(CONVERSATION_PATTERN) {
        val scope = call.conversationScope(deps) ?: return@get
        call.respond(deps.conversationDetailFor(scope))
    }

    /**
     * Throw away a conversation that was started and never sent.
     *
     * The composer's Cancel. Both conditions live in the repository rather than
     * here, because "has anything been published in it" is a question about rows;
     * what this route owns is turning a `false` into a sentence. 409 rather than
     * 403 deliberately: for the caller who started it, the reason it is refused is
     * that somebody has since spoken, which is a thing that changed rather than a
     * thing they are not allowed to do.
     */
    delete(CONVERSATION_PATTERN) {
        val scope = call.conversationScope(deps) ?: return@delete
        val user = scope.user
        if (user == null || !deps.conversations.discardUnsentConversation(scope.conversation, user.id)) {
            call.respond(HttpStatusCode.Conflict, "That conversation cannot be discarded.")
            return@delete
        }
        call.respond(deps.conversationListFor(user))
    }

    /**
     * Record that the caller has read this conversation.
     *
     * The mark is the **newest published message in the thread**, read from the
     * database rather than taken from the request. A client that named its own
     * high-water mark could mark a conversation read past what has been written —
     * or, more likely, name a stale id it happened to be holding and un-read
     * nothing at all. What the reader is actually asserting is "I have the
     * transcript in front of me", and the transcript arrives whole.
     *
     * Answers with the refreshed *list*, not the conversation: the count this
     * clears is on a list row, and since LNL-64 that list is on screen beside the
     * thread's window rather than behind it.
     *
     * Note the gate is `conversationScope` and nothing more — reading, not writing.
     * A system administrator reading somebody else's thread may therefore mark it
     * read, which is correct and invisible to everybody: a read mark is per-user
     * bookkeeping and appears in no response but their own.
     */
    post(CONVERSATION_READ_PATTERN) {
        val scope = call.conversationScope(deps) ?: return@post
        val user = scope.user ?: run {
            call.respond(HttpStatusCode.Forbidden, "You have to be signed in for that.")
            return@post
        }
        // Two statements that have to agree, so they are one line here rather than
        // a value the client sent: what the reader is asserting is "the transcript
        // was in front of me", which is a claim about the conversation.
        deps.reads.markConversationRead(
            userId = user.id,
            conversationId = scope.conversation.id,
            lastMessageId = deps.conversations.newestPublishedMessageId(scope.conversation.id),
        )
        call.respond(deps.conversationListFor(user))
    }

    /** Start a reply: the empty row an inline image can hang off. */
    post(MESSAGES_PATTERN) {
        val scope = call.conversationScope(deps) ?: return@post
        val user = scope.user
        if (!deps.access.canWriteInConversation(user, scope.participantIds) || user == null) {
            call.respond(HttpStatusCode.Forbidden, "You cannot write in this conversation.")
            return@post
        }
        val id = deps.conversations.createMessageDraft(scope.conversation.id, user.asAuthor())
        call.respond(ConversationDraft(conversationId = scope.conversation.id, messageId = id))
    }

    /**
     * Publish a message, and e-mail everybody else in the conversation.
     *
     * The author's own draft, and **only** the author's — not a system
     * administrator's, unlike deletion below. See
     * [AccessControl.canWriteInConversation] and [AccessControl.canDeleteMessage],
     * whose asymmetry is the point.
     *
     * The notification is fired from the route rather than from the repository,
     * which is where [IssueRepository] fires its own. The difference is that
     * publishing an issue happens on several paths (the editor, a drag, the MCP
     * tools) and publishing a message happens on exactly this one — so a notifier
     * inside the repository would buy nothing and would need the participant set
     * threaded into it, when the set is already resolved here to answer the
     * permission question. Firing it from the same scope that authorised the write
     * is also what makes "the people who are mailed are the people the write was
     * checked against" true by construction.
     */
    put(MESSAGE_PATTERN) {
        val scope = call.messageScope(deps) ?: return@put
        if (!deps.access.canWriteInConversation(scope.conversation.user, scope.conversation.participantIds)) {
            call.respond(HttpStatusCode.Forbidden, "You cannot write in this conversation.")
            return@put
        }
        if (!deps.access.canDeleteMessage(scope.conversation.user, scope.message.author) ||
            !scope.message.isDraft
        ) {
            // Two refusals in one line, and they are the same sentence to the
            // caller: this is somebody else's draft, or it is a message that has
            // already been sent. Neither is editable, and a message has no edit
            // route at all — see Messages.sq. Authorship is borrowed from the
            // deletion rule rather than given a rule of its own, because "is this
            // yours" has one answer and a second copy of it would drift.
            call.respond(HttpStatusCode.Forbidden, "That is not a message you are writing.")
            return@put
        }
        val body = call.receiveOrNull<MessageEdit>() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed message.")
            return@put
        }
        deps.runMessageWrite(call) {
            val published = deps.conversations.publishMessage(scope.message, body.body)
            deps.messageNotifications.messageSent(
                message = published,
                participantIds = scope.conversation.participantIds,
                actorId = scope.conversation.user?.id,
            )
            call.respond(deps.conversationDetailFor(scope.conversation))
        }
    }

    /**
     * Delete a message. The author, or a system administrator.
     *
     * Answers with the refreshed conversation, not the list: the thread is still
     * there and whoever deleted the message is still reading it.
     */
    delete(MESSAGE_PATTERN) {
        val scope = call.messageScope(deps) ?: return@delete
        if (!deps.access.canDeleteMessage(scope.conversation.user, scope.message.author)) {
            call.respond(HttpStatusCode.Forbidden, "You cannot delete this message.")
            return@delete
        }
        deps.conversations.deleteMessage(scope.message)
        call.respond(deps.conversationDetailFor(scope.conversation))
    }
}

/**
 * The Ktor patterns, built from [ApiRoutes.CONVERSATIONS] so the shared prefix has
 * one spelling.
 *
 * The segment names are written out rather than taken from the `ApiRoutes`
 * builders, which build a *path* from real ids for the client to call. The two are
 * checked against each other by `MessageTest`, which drives every route above
 * through those builders — a pattern that drifts from the builder is a 404 there
 * rather than in a browser. ForumPostRoutes says the same about its four.
 */
private const val CONVERSATION_PATTERN = "${ApiRoutes.CONVERSATIONS}/{conversationId}"
private const val CONVERSATION_READ_PATTERN = "$CONVERSATION_PATTERN/read"
private const val MESSAGES_PATTERN = "$CONVERSATION_PATTERN/messages"
private const val MESSAGE_PATTERN = "$MESSAGES_PATTERN/{messageId}"

/** A conversation this caller may read, and who is in it. */
private class ConversationScope(
    val conversation: ConversationRecord,
    val participantIds: Set<Long>,
    val user: UserRecord?,
)

/** ...and the message named in the path. */
private class MessageScope(
    val conversation: ConversationScope,
    val message: MessageRecord,
)

/**
 * Resolve a conversation this caller may read, or respond and return null.
 *
 * 404 for both "no such conversation" and "you are not in it", exactly as
 * [ApplicationCall.readableProject] does and for its reason: a 403 would confirm
 * that a conversation exists by that id, and the ids are consecutive integers, so
 * the difference between the two answers is a way to count the private
 * conversations on the instance.
 *
 * The participant set is resolved here rather than by each handler, and carried on
 * the scope, because three separate things need it — the permission question, the
 * response's `mentionableUsers`, and the notifier's recipients — and reading it
 * three times would be three answers to "who is in this room" that could differ
 * from the one the write was authorised against.
 */
private suspend fun ApplicationCall.conversationScope(deps: BoardDependencies): ConversationScope? {
    val user = caller(deps)
    val id = longParam("conversationId") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad conversation id.")
        return null
    }
    val conversation = deps.conversations.findConversation(id)
    val participantIds = if (conversation == null) emptySet() else deps.conversations.participantIds(id)
    if (conversation == null || !deps.access.canReadConversation(user, participantIds)) {
        respond(HttpStatusCode.NotFound, "No such conversation.")
        return null
    }
    return ConversationScope(conversation, participantIds, user)
}

/**
 * As [conversationScope], plus the message named in the path.
 *
 * A draft **is** found here, and that is deliberate rather than an oversight: the
 * publish `PUT` addresses the row it is about to fill in, and there is no other
 * way for it to name it. What protects a draft is that it appears in no list — see
 * Messages.sq's `forConversation` — so the only way to learn its id is to be the
 * caller the server just minted it for, and the `PUT` refuses one whose author is
 * somebody else.
 *
 * There is no `GET` for a single message, so unlike the forum's post there is no
 * separate "reading a draft" guard to write: a draft is simply absent from the
 * only response that would carry it.
 */
private suspend fun ApplicationCall.messageScope(deps: BoardDependencies): MessageScope? {
    val scope = conversationScope(deps) ?: return null
    val messageId = longParam("messageId") ?: run {
        respond(HttpStatusCode.BadRequest, "Bad message id.")
        return null
    }
    val message = deps.conversations.findMessageInConversation(messageId, scope.conversation.id) ?: run {
        respond(HttpStatusCode.NotFound, "No such message.")
        return null
    }
    return MessageScope(scope, message)
}

/**
 * Everybody [user] may start a conversation with.
 *
 * LNL-30's rule, in one place: *"a user may message anyone who has visibility of a
 * project that they themselves can see."* Two steps, and neither is optional.
 *
 * The first narrows the projects to the ones this caller can read, through the
 * same [AccessControl.canReadProject] every read route runs — so a private project
 * the caller is not a member of contributes nobody, which is the whole point of
 * the rule. The second turns that set of projects into a set of accounts through
 * [ProjectAudience.forProjects], which LNL-61 built for exactly this call and
 * which short-circuits on the first public project: one public project makes the
 * answer every account on the instance, and reading membership for the rest could
 * only add people already in it.
 *
 * The caller is dropped. Messaging yourself is not a thing this feature does — see
 * `ConversationRepository.startConversation` — and offering your own name in the
 * picker would be inviting it.
 *
 * `internal` rather than file-private because `MessageTest` asserts the picker and
 * the route agree, which is only a meaningful assertion if they are the same
 * function. An autocomplete offering a name the route then refuses is the failure
 * this shape prevents; `mentionableUsersIn` is shared between the editor and the
 * mailer for the same reason.
 */
internal suspend fun BoardDependencies.messageableUsers(user: UserRecord): List<UserRecord> {
    val visible = projects.selectAll().filter { access.canReadProject(user, it) }
    return audience.forProjects(visible).filter { it.id != user.id }
}

/**
 * The caller's conversation list, with the names and the picker's options.
 *
 * @receiver the dependencies; a `BoardDependencies` extension for the reason
 *   `postListFor` is one — these read three stores and belong to no single class.
 */
private suspend fun BoardDependencies.conversationListFor(user: UserRecord): ConversationListState {
    val listings = conversations.listing(user.id)
    val membership = conversations.participantsFor(user.id)
    // One query for every row's unread count rather than one per row — the N+1 the
    // two reads above already avoid. A conversation with nothing unread is absent
    // from the map rather than present with a zero; see Reads.sq.
    val unread = reads.unreadMessageCounts(user.id)
    // One lookup for every distinct person on the page rather than one per row: a
    // list of twenty conversations with the same three colleagues would otherwise
    // be sixty. `authorNames` does the same for a board.
    val names = membership.values.flatten().distinct()
        .mapNotNull { id -> users.findById(id)?.let { id to it.resolvedName } }
        .toMap()
    val recipients = messageableUsers(user)
    return ConversationListState(
        conversations = listings.map { listing ->
            ConversationSummary(
                id = listing.id,
                // Everybody but the reader. Sorted so the row's heading reads the
                // same way twice; the set arrives in whatever order SQLite chose.
                participantNames = membership[listing.id].orEmpty()
                    .filter { it != user.id }
                    .mapNotNull { names[it] }
                    .sorted(),
                lastMessageBody = listing.lastMessageBody,
                lastMessageAt = listing.lastMessageAt,
                unreadCount = unread[listing.id] ?: 0,
            )
        },
        recipients = recipients.map { UserOption(id = it.id, name = it.resolvedName, isSelf = false) },
        // Not simply "signed in": somebody who shares no project with anybody has
        // nobody to write to, and offering the composer would put a modal up with
        // an empty picker and no way to explain itself. A real state on a fresh
        // instance with one private project.
        canMessage = recipients.isNotEmpty(),
    )
}

/** One conversation, its messages, and everything the reader needs to act on it. */
private suspend fun BoardDependencies.conversationDetailFor(scope: ConversationScope): ConversationDetail {
    val messages = conversations.messagesIn(scope.conversation.id)
    // The participants and the message authors are nearly the same set and are
    // deliberately not assumed to be: an author who has since deleted their
    // account is gone from the participants and still on their messages.
    val authorNames = authorNames(messages.map { it.author })
    val participants = scope.participantIds
        .mapNotNull { id -> users.findById(id)?.let { id to it } }
        .toMap()
    val canReply = access.canWriteInConversation(scope.user, scope.participantIds)
    return ConversationDetail(
        id = scope.conversation.id,
        participantNames = participants.filterKeys { it != scope.user?.id }
            .values.map { it.resolvedName }.sorted(),
        messages = messages.map { message ->
            MessageView(
                id = message.id,
                body = message.body,
                authorName = message.author.displayName(authorNames),
                agentName = message.agentName,
                createdAt = message.createdAt,
                // `wrote` rather than a comparison against a -1 sentinel for "nobody":
                // -1 is now the previewed-address id (LNL-197), so the sentinel had
                // started to mean something. Asking the caller what they wrote is also the
                // one place that answer lives. See UserRecord.wrote.
                isMine = scope.user?.wrote(message.author) == true,
                canDelete = access.canDeleteMessage(scope.user, message.author),
            )
        },
        // The people in the room, and only them — see ConversationDetail. Not
        // offered to a reader with no composer, for the reason ForumPostDetail
        // gives: there is no mention to complete.
        mentionableUsers = if (canReply) {
            participants.values.map {
                UserOption(id = it.id, name = it.resolvedName, isSelf = it.id == scope.user?.id)
            }.sortedBy { it.name }
        } else {
            emptyList()
        },
        canReply = canReply,
    )
}

/**
 * Run a message write, turning a [MessageRefusal] into a sentence.
 *
 * `runPostWrite`'s twin: a blank body, or a conversation addressed to nobody, is
 * something the person typing can fix, so it is a 409 with words rather than a 500
 * with a stack trace. Anything that is not a refusal is a bug and propagates.
 */
private suspend inline fun BoardDependencies.runMessageWrite(call: ApplicationCall, block: () -> Unit) {
    try {
        block()
    } catch (refusal: MessageRefusal) {
        call.respond(HttpStatusCode.Conflict, refusal.message ?: "That was refused.")
    }
}
