/**
 * The Firestore [se.soderbjorn.lunicle.store.ReadStore] — the two high-water marks
 * (LNL-30) over documents, and the two "still new to them" reads derived from them.
 *
 * ── Document model: the marks are the only stored state ─────────────────────
 *
 * Two collections, one document per mark, mirroring the two SQLite tables:
 *
 *  - `conversationReads/{userId}_{conversationId}` holds `watermark`, the newest
 *    message id this user has seen in that conversation.
 *  - `forumReads/{userId}_{forumId}` holds `lastReadAt`, the instant they have read
 *    up to in that forum.
 *
 * The composite key is the SQLite `PRIMARY KEY (user_id, container_id)` — one mark
 * per user per container, so a write is an upsert of a known document rather than a
 * query. Both `markConversationRead` and `markForumRead` are **monotonic**: each
 * reads the current value inside a transaction and writes the greater of it and the
 * new one, so two tabs on one account cannot un-read each other's progress — the
 * `MAX` the SQLite `ON CONFLICT ... DO UPDATE` applies. The stored value is a
 * watermark, never a reference: like the SQLite column it is a number compared
 * against ids, never resolved to a row, so a deleted message cannot drag a mark back.
 *
 * ── The two derived reads: a JOIN, made an injected lookup ──────────────────
 *
 * [unreadMessageCounts] and [hasUnreadPosts] compare a user's mark against messages
 * and forum posts in other collections — a join the SQLite reference runs in one
 * statement, and one a document store cannot express. So the cross-collection data
 * arrives through two injected lambdas, the pattern [FirestoreSubscriptionStore]'s
 * contact lookup set:
 *
 *  - [publishedMessagesFor] supplies, for a user, every *published* message in the
 *    conversations they participate in — the join's `conversation_participants` and
 *    `is_draft = 0` clauses, resolved on the message/conversation stores in
 *    production and synthetic in the contract. This store then compares each against
 *    its own marks: a message counts when its id is above the mark and its author is
 *    not the reader (`created_by IS NOT :userId`, which a null author survives — you
 *    are unread on an authorless message, and `null != userId` is true here as it is
 *    in SQL).
 *  - [publishedPostsIn] supplies, for a set of projects, every published post in
 *    their forums — the join's `forums`/`is_draft = 0` — which this store tests
 *    against the forum marks the same way.
 *
 * The marks are always this store's own data; only the things counted against them
 * come through the seam. The interface never exposes it.
 *
 * ── Composite indexes ───────────────────────────────────────────────────────
 *
 * None. Every read is a single-field equality (`userId ==`) served by an automatic
 * index; the comparisons happen in memory over one user's marks.
 *
 * @see FirestoreProvider
 * @see se.soderbjorn.lunicle.store.ReadStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.CollectionReference
import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.store.ReadStore

class FirestoreReadStore(
    private val firestore: Firestore,
    private val publishedMessagesFor: suspend (userId: Long) -> List<UnreadMessage>,
    private val publishedPostsIn: suspend (projectIds: Collection<Long>) -> List<UnreadPost>,
) : ReadStore {
    /** A published message in a conversation the reader is in — the seam's message row. */
    data class UnreadMessage(val conversationId: Long, val messageId: Long, val authorId: Long?)

    /** A published post in one of the queried projects' forums — the seam's post row. */
    data class UnreadPost(val forumId: Long, val createdAt: Long, val authorId: Long?)

    private fun conversationReads() = firestore.collection(CONVERSATION_READS)
    private fun forumReads() = firestore.collection(FORUM_READS)

    private fun markKey(userId: Long, containerId: Long) = "${userId}_$containerId"

    // ── Conversations ────────────────────────────────────────────────────────

    /** Move the conversation mark forward, never back — the SQLite `MAX`, in a transaction. */
    override suspend fun markConversationRead(userId: Long, conversationId: Long, lastMessageId: Long) {
        val ref = conversationReads().document(markKey(userId, conversationId))
        firestore.runTransaction { txn ->
            val current = txn.get(ref).get().getLong(WATERMARK) ?: 0L
            txn.set(
                ref,
                mapOf(
                    USER_ID to userId,
                    CONTAINER_ID to conversationId,
                    WATERMARK to maxOf(current, lastMessageId),
                ),
            )
        }.await()
    }

    /**
     * How many unread messages [userId] has, by conversation — absent means zero.
     *
     * The messages come from the seam (published, in the user's conversations); the
     * marks are this store's. A message is unread when it is above the conversation's
     * mark and not the reader's own.
     */
    override suspend fun unreadMessageCounts(userId: Long): Map<Long, Long> {
        val marks = marksFor(conversationReads(), userId, WATERMARK)
        return publishedMessagesFor(userId)
            .filter { it.authorId != userId && it.messageId > (marks[it.conversationId] ?: 0L) }
            .groupingBy { it.conversationId }
            .eachCount()
            .mapValues { it.value.toLong() }
    }

    // ── Forums ───────────────────────────────────────────────────────────────

    /** Move the forum mark forward, never back. */
    override suspend fun markForumRead(userId: Long, forumId: Long, readAt: Long) {
        val ref = forumReads().document(markKey(userId, forumId))
        firestore.runTransaction { txn ->
            val current = txn.get(ref).get().getLong(LAST_READ_AT) ?: 0L
            txn.set(
                ref,
                mapOf(
                    USER_ID to userId,
                    CONTAINER_ID to forumId,
                    LAST_READ_AT to maxOf(current, readAt),
                ),
            )
        }.await()
    }

    /** How far [userId] has read in [forumId], or null if they never have — null, not 0, for "never". */
    override suspend fun forumMark(userId: Long, forumId: Long): Long? =
        forumReads().document(markKey(userId, forumId)).get().await().getLong(LAST_READ_AT)

    /**
     * Is there a post [userId] has not read in any of [projectIds]?
     *
     * Empty short-circuits to false without a read (the SQLite `IN ()` guard, kept
     * for parity). Otherwise a post is unread when it is newer than its forum's mark
     * and not the reader's own.
     */
    override suspend fun hasUnreadPosts(userId: Long, projectIds: Collection<Long>): Boolean {
        if (projectIds.isEmpty()) return false
        val marks = marksFor(forumReads(), userId, LAST_READ_AT)
        return publishedPostsIn(projectIds)
            .any { it.authorId != userId && it.createdAt > (marks[it.forumId] ?: 0L) }
    }

    // ── Shared machinery ─────────────────────────────────────────────────────

    /** Every mark this user holds in [collection], as container id → value — one single-field query. */
    private suspend fun marksFor(collection: CollectionReference, userId: Long, valueField: String): Map<Long, Long> =
        collection.whereEqualTo(USER_ID, userId).get().await()
            .documents.associate { it.getLong(CONTAINER_ID)!! to (it.getLong(valueField) ?: 0L) }

    private companion object {
        const val CONVERSATION_READS = "conversationReads"
        const val FORUM_READS = "forumReads"

        const val USER_ID = "userId"
        const val CONTAINER_ID = "containerId"
        const val WATERMARK = "watermark"
        const val LAST_READ_AT = "lastReadAt"
    }
}
