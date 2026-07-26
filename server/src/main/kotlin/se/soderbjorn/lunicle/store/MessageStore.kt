/**
 * The two persistence seams for private conversations and the messages in them.
 *
 * Two of the LNL-111 domain store interfaces, in one file because they are one
 * feature split the way the reference implementations split it (see
 * [se.soderbjorn.lunicle.ConversationRepository]'s preamble): SQL in, data class
 * out, no decisions. The rules — what a body may be, when a conversation may be
 * discarded, what deleting either takes off the volume — live in the repository,
 * not here, and are deliberately outside the seam a backend must satisfy.
 *
 *  - [ConversationStore] reads and writes `conversations` and
 *    `conversation_participants`. Membership is frozen at [insert]; the surface has
 *    no way to add or remove a participant, mirroring Conversations.sq.
 *  - [MessageStore] reads and writes `messages`, including the draft dance
 *    ([insertDraft] then [publish]) an inline upload needs.
 *
 * The reference implementations are today's SQLite
 * [se.soderbjorn.lunicle.ConversationStore] and
 * [se.soderbjorn.lunicle.MessageStore]; a document backend is free to model the
 * rows differently as long as the behaviour the contracts pin holds.
 *
 * @see se.soderbjorn.lunicle.store.ConversationStoreContract
 * @see se.soderbjorn.lunicle.store.MessageStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.ConversationListing
import se.soderbjorn.lunicle.ConversationRecord
import se.soderbjorn.lunicle.MessageRecord

interface ConversationStore {
    /** Create a conversation with exactly [participantIds] in it, for ever. */
    suspend fun insert(author: Author, participantIds: Set<Long>): Long

    suspend fun findById(id: Long): ConversationRecord?

    suspend fun delete(id: Long)

    /** Who is in it. The whole permission model for messages; see AccessControl. */
    suspend fun participantIds(conversationId: Long): Set<Long>

    /** Is [userId] in [conversationId]? */
    suspend fun isParticipant(conversationId: Long, userId: Long): Boolean

    /** This user's conversations, most recently spoken in first. */
    suspend fun forUser(userId: Long): List<ConversationListing>

    /** Everybody in every conversation [userId] is in, as conversation id → member ids. */
    suspend fun participantsForUser(userId: Long): Map<Long, Set<Long>>
}

interface MessageStore {
    /** Create the draft row an upload can hang off. */
    suspend fun insertDraft(
        conversationId: Long,
        author: Author,
        createdAt: Long? = null,
        agentName: String? = null,
    ): Long

    suspend fun publish(id: Long, body: String)

    /** Rewrite the body alone, leaving `is_draft` where it was. */
    suspend fun updateBody(id: Long, body: String)

    suspend fun delete(id: Long)

    suspend fun findById(id: Long): MessageRecord?

    /** One message, proving it is this conversation's. */
    suspend fun findByIdInConversation(id: Long, conversationId: Long): MessageRecord?

    /** A conversation's published messages, oldest first. */
    suspend fun forConversation(conversationId: Long): List<MessageRecord>

    /** The newest published message here, as an id, or 0 if there is none. */
    suspend fun maxPublishedId(conversationId: Long): Long

    /** Has anything ever been sent here? */
    suspend fun hasPublished(conversationId: Long): Boolean

    /** Every message that might mention somebody. */
    suspend fun withPossibleMentions(): List<Pair<Long, String>>

    /** Every message whose body might link to an attachment. */
    suspend fun withAttachmentLinks(): List<Pair<Long, String>>
}
