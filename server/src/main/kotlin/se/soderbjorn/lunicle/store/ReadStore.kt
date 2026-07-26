/**
 * The persistence seam for what each user has already read, and what follows from
 * it — the two high-water marks (LNL-30) and the two derived "still new to them"
 * questions.
 *
 * The opposite shape to [NotificationStore]'s stored rows: nothing is stored per
 * item. A conversation mark is one row per user per conversation holding the newest
 * message they have seen; a forum mark is one row per user per forum holding the
 * instant they have read up to. "Unread" is then a *comparison*, not a set
 * difference — see Reads.sq for why LNL-30 rejected a row-per-item schema. The
 * reference implementation is the SQLite gateway [se.soderbjorn.lunicle.ReadStore]
 * (named by its fully-qualified name in that class's supertype clause, since the two
 * share a simple name).
 *
 * ── The two derived reads are a JOIN in SQLite ──────────────────────────────
 *
 * [unreadMessageCounts] and [hasUnreadPosts] compare a user's mark against messages
 * and forum posts that live in *other* tables — a join the SQLite reference runs in
 * one statement. A document backend has no such join, so the Firestore
 * implementation is handed the cross-collection data through injected lambdas (the
 * pattern [SubscriptionStore]'s contact lookup set); this interface never exposes
 * that seam. Either way the marks themselves are this store's own data and the
 * comparison is against them.
 *
 * No permission is decided here. Who may see the things being counted is
 * [se.soderbjorn.lunicle.AccessControl]'s, which is why [hasUnreadPosts]'s project
 * ids arrive already narrowed to what the caller may see.
 *
 * @see se.soderbjorn.lunicle.store.ReadStoreContract
 */
package se.soderbjorn.lunicle.store

interface ReadStore {
    /**
     * Record that [userId] has read [conversationId] up to and including
     * [lastMessageId]. Idempotent and monotonic — a mark never moves backwards.
     */
    suspend fun markConversationRead(userId: Long, conversationId: Long, lastMessageId: Long)

    /**
     * How many unread messages [userId] has, by conversation. A conversation with
     * nothing unread is **absent** rather than present with a zero.
     */
    suspend fun unreadMessageCounts(userId: Long): Map<Long, Long>

    /** Move [userId]'s mark in [forumId] to [readAt], if that is further on. */
    suspend fun markForumRead(userId: Long, forumId: Long, readAt: Long)

    /** How far [userId] has read in [forumId], or null if they never have. */
    suspend fun forumMark(userId: Long, forumId: Long): Long?

    /**
     * Is there a post [userId] has not read in any of [projectIds]?
     *
     * @param projectIds already narrowed to what this caller may see — this store
     *   does not filter by visibility. An empty collection is false without a read.
     */
    suspend fun hasUnreadPosts(userId: Long, projectIds: Collection<Long>): Boolean
}
