/**
 * Private conversations and the messages in them, and the rules for writing both.
 *
 * Three classes, split the way [ForumPosts] splits and for the same reason:
 * [ConversationStore] and [MessageStore] are SQL in, data class out, no
 * decisions; [ConversationRepository] owns the body rule, the draft dance, and
 * the one thing neither store can do — deleting the *files* a message owns before
 * the rows that name them cascade away. A route never mentions a transaction, and
 * a store never mentions a rule.
 *
 * Nothing here answers a permission question — [AccessControl] does that, and the
 * routes ask it before they reach this file. See AccessControl's preamble for why
 * that split is absolute, and [AccessControl.canReadConversation] for the one rule
 * in this feature that is not shaped like every other rule in that file.
 *
 * ── The draft dance, with one extra step ────────────────────────────────────
 *
 * A message is inserted empty and published later, exactly as an issue, an issue
 * comment and a forum post are: the composer supports inline image upload, an
 * attachment row needs an owner that exists, and a body being typed has no row
 * yet. Creating the row first is what lets the whole existing attachment
 * machinery be reused unchanged.
 *
 * The extra step is at the top. A forum post's draft hangs off a forum that
 * already exists; a **new conversation's** first draft has nothing to hang off,
 * because the conversation is the thing being created. So [startConversation]
 * creates both in one transaction and answers with both ids — and that is the
 * moment membership freezes, since [Conversations.sq] has no way to add anybody
 * later. Everything the composer does afterwards is the ordinary dance.
 *
 * The cost of that is a conversation that exists before anything has been said in
 * it, which is why `forUser` filters on a published message and why
 * [discardUnsentConversation] exists. See both.
 *
 * @see messageRoutes
 * @see ProjectAudience for who may be put in one
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * A conversation as this server knows it.
 *
 * Deliberately thin: an id, when it started, and who started it. Everything that
 * makes a conversation interesting — who is in it, what was said — is a different
 * table, because it is a different cardinality. See Conversations.sq.
 *
 * @property author who started it. Not an owner: a conversation is not owned, and
 *   this is read by exactly one rule ([ConversationRepository
 *   .discardUnsentConversation]) and rendered by nothing.
 */
data class ConversationRecord(
    val id: Long,
    val createdAt: Long,
    val author: Author,
)

/**
 * One conversation in somebody's list, with the two derived facts the list needs.
 *
 * Its own type rather than a [ConversationRecord] plus two queries, for
 * [ForumPostListing]'s reason: both come back from the same statement — see
 * Conversations.sq's `forUser` — and a list route that fetched the conversations
 * and then asked each one for its last message would be N+1 round-trips to answer
 * what one select already knows.
 *
 * @property lastMessageAt when the most recent published message landed. Non-null
 *   by construction: the query only returns conversations that have one.
 * @property lastMessageBody the whole markdown of that message. The *whole* of it
 *   rather than a truncated preview, because how long a preview is and where it
 *   is cut are questions about a list row's width, which is the client's business
 *   and not this server's. See ConversationSummary.
 */
data class ConversationListing(
    val id: Long,
    val lastMessageAt: Long,
    val lastMessageBody: String,
)

/** A message in a conversation. See Messages.sq. */
data class MessageRecord(
    val id: Long,
    val conversationId: Long,
    val body: String,
    val createdAt: Long,
    val author: Author,
    val agentName: String?,
    val isDraft: Boolean,
)

/**
 * Why a conversation or message write was refused, in words a user should see.
 *
 * [ForumPostRefusal]'s twin, and separate from it only so the routes file can
 * catch its own. Anything that is *not* one of these is a bug and is allowed to
 * propagate.
 */
class MessageRefusal(message: String) : RuntimeException(message)

/** Reads and writes `conversations` and `conversation_participants`. No rules. */
class ConversationStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.ConversationStore {
    /**
     * Create a conversation with exactly [participantIds] in it, for ever.
     *
     * One method rather than an insert plus a loop the caller writes, because the
     * two halves are not separable: a conversation with no participants is
     * unreadable by everybody including its creator, and membership cannot be
     * changed afterwards, so there is no legitimate moment at which a
     * half-populated one is a state anybody wants. The transaction is what makes
     * that true rather than nearly true.
     */
    override suspend fun insert(author: Author, participantIds: Set<Long>): Long =
        withContext(DatabaseDispatcher) {
            database.transactionWithResult {
                val id = database.conversationsQueries
                    .insert(now(), author.accountId)
                    .executeAsOne()
                participantIds.forEach { database.conversationsQueries.addParticipant(id, it) }
                id
            }
        }

    override suspend fun findById(id: Long): ConversationRecord? = withContext(DatabaseDispatcher) {
        database.conversationsQueries.findById(id).executeAsOneOrNull()?.let {
            ConversationRecord(
                id = it.id,
                createdAt = it.created_at,
                author = authorOf(it.created_by, null),
            )
        }
    }

    override suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.conversationsQueries.delete(id)
    }

    /** Who is in it. The whole permission model for messages; see AccessControl. */
    override suspend fun participantIds(conversationId: Long): Set<Long> = withContext(DatabaseDispatcher) {
        database.conversationsQueries.participantIds(conversationId).executeAsList().toSet()
    }

    /**
     * Is [userId] in [conversationId]?
     *
     * An indexed existence check rather than `participantIds(...).contains(...)`,
     * for [RoleStore.isMember]'s reason: this is asked on every read of every
     * conversation, and `EXISTS` stops at the first row where the other builds a
     * set the caller then searches once and discards.
     */
    override suspend fun isParticipant(conversationId: Long, userId: Long): Boolean =
        withContext(DatabaseDispatcher) {
            database.conversationsQueries.isParticipant(conversationId, userId).executeAsOne()
        }

    /** This user's conversations, most recently spoken in first. See Conversations.sq. */
    override suspend fun forUser(userId: Long): List<ConversationListing> = withContext(DatabaseDispatcher) {
        database.conversationsQueries.forUser(userId).executeAsList().mapNotNull { row ->
            // `last_at` and `last_body` come back nullable because they are
            // subqueries over a set SQLite cannot prove is non-empty; the query's
            // own EXISTS proves it. A null here would mean the query changed
            // underneath this mapper, and dropping the row is the honest answer —
            // a conversation with no last message has nothing to render.
            val at = row.last_at ?: return@mapNotNull null
            ConversationListing(id = row.id, lastMessageAt = at, lastMessageBody = row.last_body.orEmpty())
        }
    }

    /**
     * Everybody in every conversation [userId] is in, as conversation id → member
     * ids.
     *
     * One query for the whole list, rather than one per conversation. See
     * Conversations.sq's `participantsForUser`.
     */
    override suspend fun participantsForUser(userId: Long): Map<Long, Set<Long>> =
        withContext(DatabaseDispatcher) {
            database.conversationsQueries.participantsForUser(userId).executeAsList()
                .groupBy({ it.conversation_id }, { it.user_id })
                .mapValues { (_, ids) -> ids.toSet() }
        }
}

/** Reads and writes `messages`. No rules; see [ConversationRepository]. */
class MessageStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.MessageStore {
    /**
     * Create the draft row an upload can hang off.
     *
     * @param createdAt when the message should claim to have been written, or null
     *   — every caller today — for now. The parameter exists for [CommentStore
     *   .insertDraft]'s reason and has no caller: there is no MCP tool and no
     *   importer for messages, by LNL-30's decision, and there is unlikely ever to
     *   be one for something this private.
     */
    override suspend fun insertDraft(
        conversationId: Long,
        author: Author,
        createdAt: Long?,
        agentName: String?,
    ): Long = withContext(DatabaseDispatcher) {
        database.messagesQueries
            .insert(conversationId, createdAt ?: now(), author.accountId, author.externalName, agentName)
            .executeAsOne()
    }

    override suspend fun publish(id: Long, body: String): Unit = withContext(DatabaseDispatcher) {
        database.messagesQueries.publish(body, id)
    }

    /**
     * Rewrite the body alone, leaving `is_draft` where it was.
     *
     * The two bulk markdown passes, and nothing else: [MentionRenamer] and
     * [AttachmentLinkRepair]. Neither is a user editing anything — and unlike a
     * post, a message has no editing route at all — which is why this is not
     * spelled as an `edit`.
     */
    override suspend fun updateBody(id: Long, body: String): Unit = withContext(DatabaseDispatcher) {
        database.messagesQueries.updateBody(body, id)
    }

    override suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.messagesQueries.delete(id)
    }

    override suspend fun findById(id: Long): MessageRecord? = withContext(DatabaseDispatcher) {
        database.messagesQueries.findById(id).executeAsOneOrNull()?.toRecord()
    }

    /**
     * One message, proving it is this conversation's.
     *
     * Everything reachable by a URL uses this rather than [findById], so a
     * mismatched pair is a 404 rather than a cross-conversation write. See
     * Messages.sq.
     */
    override suspend fun findByIdInConversation(id: Long, conversationId: Long): MessageRecord? =
        withContext(DatabaseDispatcher) {
            database.messagesQueries.findByIdInConversation(id, conversationId)
                .executeAsOneOrNull()?.toRecord()
        }

    /** A conversation's published messages, oldest first. */
    override suspend fun forConversation(conversationId: Long): List<MessageRecord> =
        withContext(DatabaseDispatcher) {
            database.messagesQueries.forConversation(conversationId).executeAsList().map { it.toRecord() }
        }

    /**
     * The newest published message here, as an id, or 0 if there is none.
     *
     * The value a read mark is set to. Zero is a real answer rather than a
     * sentinel — a conversation somebody started and abandoned has nothing in it —
     * and it is the correct mark for that case, being below every id.
     */
    override suspend fun maxPublishedId(conversationId: Long): Long = withContext(DatabaseDispatcher) {
        database.messagesQueries.maxPublishedId(conversationId).executeAsOne()
    }

    /** Has anything ever been sent here? See Messages.sq. */
    override suspend fun hasPublished(conversationId: Long): Boolean = withContext(DatabaseDispatcher) {
        database.messagesQueries.hasPublished(conversationId).executeAsOne()
    }

    /** Every message that might mention somebody. See Messages.sq. */
    override suspend fun withPossibleMentions(): List<Pair<Long, String>> = withContext(DatabaseDispatcher) {
        database.messagesQueries.withPossibleMentions().executeAsList().map { it.id to it.body }
    }

    /** Every message whose body might link to an attachment. See [AttachmentLinkRepair]. */
    override suspend fun withAttachmentLinks(): List<Pair<Long, String>> = withContext(DatabaseDispatcher) {
        database.messagesQueries.withAttachmentLinks().executeAsList().map { it.id to it.body }
    }
}

/**
 * The rules: what a message's body may be, when a conversation may be thrown
 * away, and what deleting either takes with it.
 *
 * Also the only conversation collaborator [BoardDependencies] carries, for the
 * reason [ForumPostRepository] gives: the reads below are pass-throughs so that a
 * route holds one thing rather than three, and cannot eventually read through a
 * store on a path that was meant to go through a rule.
 *
 * @param conversations the conversations and their membership.
 * @param messages the messages table.
 * @param attachments the volume, for unlinking files whose rows are about to
 *   cascade away.
 * @param attachmentStore asked one question — which files are about to be orphaned
 *   — and asked *before* the delete, because afterwards nothing can name them. See
 *   [IssueRepository.delete], which is the same pairing for the same reason.
 */
class ConversationRepository(
    private val conversations: se.soderbjorn.lunicle.store.ConversationStore,
    private val messages: se.soderbjorn.lunicle.store.MessageStore,
    private val attachments: AttachmentRepository,
    private val attachmentStore: se.soderbjorn.lunicle.store.AttachmentStore,
) {
    // ── Reads ────────────────────────────────────────────────────────────────

    suspend fun listing(userId: Long): List<ConversationListing> = conversations.forUser(userId)

    suspend fun participantsFor(userId: Long): Map<Long, Set<Long>> =
        conversations.participantsForUser(userId)

    suspend fun findConversation(id: Long): ConversationRecord? = conversations.findById(id)

    suspend fun participantIds(conversationId: Long): Set<Long> = conversations.participantIds(conversationId)

    suspend fun isParticipant(conversationId: Long, userId: Long): Boolean =
        conversations.isParticipant(conversationId, userId)

    suspend fun messagesIn(conversationId: Long): List<MessageRecord> = messages.forConversation(conversationId)

    suspend fun findMessage(id: Long): MessageRecord? = messages.findById(id)

    suspend fun findMessageInConversation(id: Long, conversationId: Long): MessageRecord? =
        messages.findByIdInConversation(id, conversationId)

    /**
     * The newest published message in [conversationId], or 0 if there is none.
     *
     * The value a read mark is set to. A read pass-through like the six above, and
     * here rather than reached through [MessageStore] directly for this class's
     * stated reason: a route holds one thing, so it cannot eventually read through
     * a store on a path that was meant to go through a rule.
     */
    suspend fun newestPublishedMessageId(conversationId: Long): Long =
        messages.maxPublishedId(conversationId)

    // ── Writes ───────────────────────────────────────────────────────────────

    /**
     * Start a conversation between [author] and [recipientIds], with the empty
     * first message an inline image can hang off.
     *
     * The author is added to the participant set here rather than by the caller,
     * and it is not politeness: a conversation whose creator is not in it is
     * unreadable by the person who just wrote it, and it is exactly the sort of
     * thing a route forgets once. The set is a `Set`, so naming yourself as a
     * recipient collapses rather than failing — the route refuses it too, with
     * words, but a duplicate row is not what should enforce it.
     *
     * @throws MessageRefusal if nobody was named. The one rule here that is not
     *   about permission: a conversation with a single participant is a private
     *   note to yourself, which is a different feature nobody asked for.
     */
    suspend fun startConversation(author: Author, recipientIds: Set<Long>): Pair<Long, Long> {
        if (recipientIds.isEmpty()) throw MessageRefusal("A message needs somebody to go to.")
        val members = recipientIds + setOfNotNull(author.accountId)
        val conversationId = conversations.insert(author, members)
        val messageId = messages.insertDraft(conversationId, author)
        return conversationId to messageId
    }

    /** Create the hidden draft row a reply is written into. */
    suspend fun createMessageDraft(conversationId: Long, author: Author): Long =
        messages.insertDraft(conversationId, author)

    /**
     * Publish a draft message.
     *
     * @throws MessageRefusal if the body is blank. Trimmed but not truncated, for
     *   [ForumPostRepository]'s reason: the body is the point of the message, and
     *   silently cutting somebody's prose at a limit is worse than storing a long
     *   one. The ceiling that matters is on attachments, where the bytes are.
     */
    suspend fun publishMessage(message: MessageRecord, body: String): MessageRecord {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) throw MessageRefusal("There is nothing in that yet.")
        messages.publish(message.id, trimmed)
        return message.copy(body = trimmed, isDraft = false)
    }

    /**
     * Delete a message and every file it owned.
     *
     * The keys are read *first*: the rows go by `ON DELETE CASCADE`, and SQLite
     * has no way to reach the filesystem, so after the delete nothing on this
     * instance can name those files ever again. [IssueRepository.delete] says the
     * same, at greater length.
     *
     * The conversation stays, even when this was its last message. Deliberate:
     * membership is fixed at creation, so a conversation that tidied itself away
     * could not be re-created between the same people — they would get a *second*
     * thread, and the one they had would be gone. An empty conversation is
     * invisible in the list (see Conversations.sq's `forUser`) and can still be
     * written into by anybody in it, which is the state the participants would
     * expect after deleting everything they had said.
     */
    suspend fun deleteMessage(message: MessageRecord) {
        val doomed = attachmentStore.keysForMessage(message.id)
        messages.delete(message.id)
        doomed.forEach { attachments.fileFor(it).delete() }
    }

    /**
     * Throw away a conversation that was started and never sent, with whatever was
     * uploaded into its unfinished first message.
     *
     * The Cancel button of the new-message composer, and the only path that
     * deletes a conversation at all. Two conditions, both load-bearing:
     *
     *  - **Nothing has ever been published in it.** Not "it is empty now" — see
     *    [deleteMessage], which deliberately leaves an emptied thread standing.
     *    `hasPublished` is over published rows, so the draft being discarded does
     *    not count itself as content.
     *  - **The caller started it.** Everybody in a conversation can reach this
     *    route, and without this clause the recipient of a message being composed
     *    could delete the composer out from under the sender — which they cannot
     *    see, but could reach by id.
     *
     * @return true if it went. False is not a failure: it is "there is something
     *   in this conversation", which the route turns into a refusal.
     */
    suspend fun discardUnsentConversation(conversation: ConversationRecord, userId: Long): Boolean {
        if (conversation.author != Author.Account(userId)) return false
        if (messages.hasPublished(conversation.id)) return false
        val doomed = attachmentStore.keysForConversation(conversation.id)
        conversations.delete(conversation.id)
        doomed.forEach { attachments.fileFor(it).delete() }
        return true
    }
}

private fun se.soderbjorn.lunicle.db.Messages.toRecord(): MessageRecord = MessageRecord(
    id = id,
    conversationId = conversation_id,
    body = body,
    createdAt = created_at,
    author = authorOf(created_by, created_by_external),
    agentName = agent_name,
    isDraft = is_draft != 0L,
)
