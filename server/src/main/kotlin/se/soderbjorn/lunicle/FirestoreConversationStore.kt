/**
 * The Firestore implementations of [se.soderbjorn.lunicle.store.ConversationStore]
 * and [se.soderbjorn.lunicle.store.MessageStore] — private conversations and the
 * messages in them, part of the LNL-117 collaboration fan-out.
 *
 * Kept in one file for the reason the interfaces are (see `MessageStore.kt`): they
 * are one feature, and [FirestoreConversationStore.forUser] — the Messages-tab list
 * — must read the message collection to answer "spoken in, and when", so the two
 * share the message field constants at the foot of this file.
 *
 * ── Document model ──────────────────────────────────────────────────────────
 *
 * `conversations/{id}` holds the thin record — `createdAt`, `createdBy`, and the
 * frozen membership as a `participantIds` array — and `messages/{id}` holds each
 * message with a denormalised `conversationId`. Ids are the global `Long`s the
 * system addresses these by, from `_counters/conversations` and `_counters/messages`
 * (see [FirestoreCounters]). The two SQLite join tables collapse: membership becomes
 * an array on the conversation (so [participantIds], [isParticipant] and
 * [participantsForUser] are a single document read or `array-contains` query, the
 * shape [FirestoreIssueStore] uses for an issue's labels), and a message is a flat
 * document with a parent ref rather than a subcollection.
 *
 * ── The list rules the Messages tab turns on ────────────────────────────────
 *
 * [forUser] must show only conversations with a **published** message, newest-spoken
 * first, and only to the people in them — the one place this store reaches across to
 * the message collection, mirroring the SQLite `forUser` subquery. It runs one
 * `array-contains` query for the user's conversations, then one equality query per
 * conversation for its messages, drops drafts and sorts in memory, and omits any
 * conversation with nothing published. A message is born a draft ([insertDraft]) so
 * an upload has an owner and [publish] is what makes it visible and countable here,
 * exactly as [FirestoreIssueStore] models a draft. [maxPublishedId] is the newest
 * published id (or 0), read-mark and discard leaning on it and on [hasPublished].
 *
 * Sorting and draft-filtering in memory keeps every query a single equality or
 * array-contains filter, so **no composite index is required** by either store.
 *
 * @see FirestoreProvider
 * @see FirestoreCounters
 * @see se.soderbjorn.lunicle.store.ConversationStoreContract
 * @see se.soderbjorn.lunicle.store.MessageStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore

class FirestoreConversationStore(
    private val firestore: Firestore,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.ConversationStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(CONVERSATIONS_COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    /**
     * Create a conversation with exactly [participantIds] in it, for ever.
     *
     * Membership is an array written with the document itself, in one transaction —
     * the document-model equivalent of the SQLite insert-plus-loop, and atomic for
     * the same reason: a conversation with no members is unreadable by everybody
     * including its creator, and membership cannot be changed later, so a
     * half-populated one is a state nobody wants. Stored sorted for a stable read.
     */
    override suspend fun insert(author: Author, participantIds: Set<Long>): Long =
        firestore.runTransaction { txn ->
            val id = counters.next(txn, CONVERSATIONS_COUNTER).getValue(CONVERSATIONS_COUNTER)
            txn.set(
                doc(id),
                mapOf(
                    ID to id,
                    CREATED_AT to now(),
                    CREATED_BY to author.accountId,
                    PARTICIPANT_IDS to participantIds.sorted(),
                ),
            )
            id
        }.await()

    override suspend fun findById(id: Long): ConversationRecord? =
        doc(id).get().await().takeIf { it.exists() }?.let {
            ConversationRecord(
                id = it.getLong(ID)!!,
                createdAt = it.getLong(CREATED_AT)!!,
                author = authorOf(it.getLong(CREATED_BY), null),
            )
        }

    override suspend fun delete(id: Long) {
        doc(id).delete().await()
    }

    /** Who is in it. Empty when the conversation is gone. */
    override suspend fun participantIds(conversationId: Long): Set<Long> =
        doc(conversationId).get().await().takeIf { it.exists() }?.longList(PARTICIPANT_IDS)?.toSet().orEmpty()

    override suspend fun isParticipant(conversationId: Long, userId: Long): Boolean =
        userId in participantIds(conversationId)

    /** This user's conversations, most recently spoken in first. See the class preamble. */
    override suspend fun forUser(userId: Long): List<ConversationListing> =
        collection()
            .whereArrayContains(PARTICIPANT_IDS, userId)
            .get().await()
            .documents.mapNotNull { conversation ->
                val published = firestore.collection(MESSAGES_COLLECTION)
                    .whereEqualTo(MESSAGE_CONVERSATION_ID, conversation.getLong(ID))
                    .get().await()
                    .documents.filter { (it.getBoolean(IS_DRAFT) ?: false).not() }
                val latest = published.maxByOrNull { it.getLong(CREATED_AT) ?: 0L } ?: return@mapNotNull null
                ConversationListing(
                    id = conversation.getLong(ID)!!,
                    lastMessageAt = latest.getLong(CREATED_AT)!!,
                    lastMessageBody = latest.getString(BODY).orEmpty(),
                )
            }
            .sortedByDescending { it.lastMessageAt }

    /** Everybody in every conversation [userId] is in, as conversation id → member ids. */
    override suspend fun participantsForUser(userId: Long): Map<Long, Set<Long>> =
        collection()
            .whereArrayContains(PARTICIPANT_IDS, userId)
            .get().await()
            .documents.associate { it.getLong(ID)!! to it.longList(PARTICIPANT_IDS).toSet() }

    private companion object {
        const val CONVERSATIONS_COLLECTION = "conversations"
        const val CONVERSATIONS_COUNTER = "conversations"

        const val ID = "id"
        const val CREATED_AT = "createdAt"
        const val CREATED_BY = "createdBy"
        const val PARTICIPANT_IDS = "participantIds"
    }
}

class FirestoreMessageStore(
    private val firestore: Firestore,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.MessageStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(MESSAGES_COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    override suspend fun insertDraft(
        conversationId: Long,
        author: Author,
        createdAt: Long?,
        agentName: String?,
    ): Long {
        val timestamp = createdAt ?: now()
        return firestore.runTransaction { txn ->
            val id = counters.next(txn, MESSAGES_COUNTER).getValue(MESSAGES_COUNTER)
            txn.set(
                doc(id),
                mapOf(
                    ID to id,
                    MESSAGE_CONVERSATION_ID to conversationId,
                    BODY to "",
                    CREATED_AT to timestamp,
                    CREATED_BY to author.accountId,
                    CREATED_BY_EXTERNAL to author.externalName,
                    AGENT_NAME to agentName,
                    IS_DRAFT to true,
                ),
            )
            id
        }.await()
    }

    override suspend fun publish(id: Long, body: String) {
        doc(id).update(mapOf(BODY to body, IS_DRAFT to false)).await()
    }

    override suspend fun updateBody(id: Long, body: String) {
        doc(id).update(mapOf(BODY to body)).await()
    }

    override suspend fun delete(id: Long) {
        doc(id).delete().await()
    }

    override suspend fun findById(id: Long): MessageRecord? =
        doc(id).get().await().takeIf { it.exists() }?.toMessageRecord()

    override suspend fun findByIdInConversation(id: Long, conversationId: Long): MessageRecord? =
        findById(id)?.takeIf { it.conversationId == conversationId }

    /** A conversation's published messages, oldest first. */
    override suspend fun forConversation(conversationId: Long): List<MessageRecord> =
        publishedIn(conversationId)
            .map { it.toMessageRecord() }
            .sortedWith(compareBy<MessageRecord> { it.createdAt }.thenBy { it.id })

    /** The newest published message here, as an id, or 0 if there is none. */
    override suspend fun maxPublishedId(conversationId: Long): Long =
        publishedIn(conversationId).mapNotNull { it.getLong(ID) }.maxOrNull() ?: 0L

    override suspend fun hasPublished(conversationId: Long): Boolean = publishedIn(conversationId).isNotEmpty()

    override suspend fun withPossibleMentions(): List<Pair<Long, String>> = messageBodiesContaining("@")

    override suspend fun withAttachmentLinks(): List<Pair<Long, String>> = messageBodiesContaining("/api/attachments/")

    /** This conversation's published messages, drafts dropped in memory so the query needs no index. */
    private suspend fun publishedIn(conversationId: Long): List<DocumentSnapshot> =
        collection()
            .whereEqualTo(MESSAGE_CONVERSATION_ID, conversationId)
            .get().await()
            .documents.filter { (it.getBoolean(IS_DRAFT) ?: false).not() }

    /**
     * Every message whose body contains [needle], drafts included — the full scan
     * the two startup maintenance passes need, Firestore having no substring
     * predicate. See [FirestoreIssueStore] for the same over issue descriptions.
     */
    private suspend fun messageBodiesContaining(needle: String): List<Pair<Long, String>> =
        collection().get().await().documents
            .map { it.getLong(ID)!! to it.getString(BODY).orEmpty() }
            .filter { it.second.contains(needle) }

    private companion object {
        const val MESSAGES_COUNTER = "messages"
    }
}

// ── Shared message document shape ────────────────────────────────────────────
// File-scoped because the conversation store's forUser reads the message collection
// to find each conversation's most recent published message.

private const val MESSAGES_COLLECTION = "messages"

private const val ID = "id"
private const val MESSAGE_CONVERSATION_ID = "conversationId"
private const val BODY = "body"
private const val CREATED_AT = "createdAt"
private const val CREATED_BY = "createdBy"
private const val CREATED_BY_EXTERNAL = "createdByExternal"
private const val AGENT_NAME = "agentName"
private const val IS_DRAFT = "isDraft"

private fun DocumentSnapshot.toMessageRecord() = MessageRecord(
    id = getLong(ID)!!,
    conversationId = getLong(MESSAGE_CONVERSATION_ID)!!,
    body = getString(BODY).orEmpty(),
    createdAt = getLong(CREATED_AT)!!,
    author = authorOf(getLong(CREATED_BY), getString(CREATED_BY_EXTERNAL)),
    agentName = getString(AGENT_NAME),
    isDraft = getBoolean(IS_DRAFT) ?: false,
)

/** An id-array field read back, or empty when absent. Firestore stores integer arrays as `List<Long>`. */
private fun DocumentSnapshot.longList(field: String): List<Long> {
    @Suppress("UNCHECKED_CAST")
    return (get(field) as? List<Long>).orEmpty()
}
