/**
 * What each user has already read, and what follows from it.
 *
 * One store over the two high-water tables in Reads.sq. It answers two kinds of
 * question and decides no permission from either: "how far has this person got"
 * (for the marks) and "what is still new to them" (for the badges). Who may see
 * the things being counted is [AccessControl]'s, and every caller here has already
 * asked it — see [ReadStore.hasUnreadPosts], whose project ids arrive narrowed for
 * exactly that reason.
 *
 * ── Why marking read is a write the client asks for ─────────────────────────
 *
 * The alternative was to advance the mark as a side effect of the `GET` that
 * fetches a conversation or a post. It was rejected on two grounds. A `GET` that
 * changes state is a `GET` that a retry, a prefetch or a link preview can perform
 * on somebody's behalf — and this server already hands out deep links that a mail
 * client may well fetch to render a preview, which would silently mark a
 * conversation read before anybody opened it. And the moment a thing is *fetched*
 * is not the moment it is *viewed*: the two coincide for a conversation and
 * genuinely do not for a forum post, where LNL-62's window is reused and a post can
 * arrive without a window opening at all.
 *
 * So there are two small `POST`s, and the client decides when a view happened. See
 * `MessagesBackingViewModel.onConversationOpened` and
 * `ForumBackingViewModel.onPostOpened`, which are the two places that decision is
 * made.
 *
 * @see Reads.sq
 * @see AccessControl.canReadConversation
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * Reads and writes `conversation_reads` and `forum_reads`. No rules.
 *
 * @param database the open database.
 */
class ReadStore(
    private val database: LunicleDatabase,
) : se.soderbjorn.lunicle.store.ReadStore {
    // ── Conversations ────────────────────────────────────────────────────────

    /**
     * Record that [userId] has read [conversationId] up to and including
     * [lastMessageId].
     *
     * Idempotent, and monotonic: the statement takes the greater of the stored
     * value and this one, so two tabs on the same account cannot un-read each
     * other's progress. See Reads.sq.
     */
    override suspend fun markConversationRead(userId: Long, conversationId: Long, lastMessageId: Long): Unit =
        withContext(DatabaseDispatcher) {
            database.readsQueries.markConversationRead(userId, conversationId, lastMessageId)
        }

    /**
     * How many unread messages [userId] has, by conversation.
     *
     * One query for the whole tab. A conversation with nothing unread is **absent**
     * rather than present with a zero, which is why this is read through a map with
     * a default — see `conversationListFor`.
     */
    override suspend fun unreadMessageCounts(userId: Long): Map<Long, Long> = withContext(DatabaseDispatcher) {
        database.readsQueries.unreadMessageCounts(userId).executeAsList()
            .associate { it.conversation_id to it.unread }
    }

    // ── Forums ───────────────────────────────────────────────────────────────

    /**
     * Move [userId]'s mark in [forumId] to [readAt], if that is further on.
     *
     * The raw write; the interesting part is at the call site, because *which
     * instant* to pass is the decision. See `forumPostRoutes`' `POST .../read`,
     * which passes the post's own `created_at` rather than `now` — and says what
     * that costs.
     */
    override suspend fun markForumRead(userId: Long, forumId: Long, readAt: Long): Unit =
        withContext(DatabaseDispatcher) {
            database.readsQueries.markForumRead(userId, forumId, readAt)
        }

    /**
     * How far [userId] has read in [forumId], or null if they never have.
     *
     * Null rather than 0 for "never", though the two behave identically in every
     * comparison this codebase makes. Kept distinct because the callers do a
     * `?: 0`, which is one visible place, and collapsing it here would make "has
     * this person ever opened this forum" unanswerable if it is ever asked.
     */
    override suspend fun forumMark(userId: Long, forumId: Long): Long? = withContext(DatabaseDispatcher) {
        database.readsQueries.forumMark(userId, forumId).executeAsOneOrNull()
    }

    /**
     * Is there a post [userId] has not read in any of [projectIds]?
     *
     * @param projectIds **already narrowed to what this caller may see.** This
     *   store does not filter by visibility and must not start to: the rule is
     *   `AccessControl.canReadProject`'s, and a second copy of it in SQL is how the
     *   two come to disagree — the position Subscriptions.sq takes at length about
     *   its own recipient queries, and the one LNL-63 learned the hard way. See
     *   `discussionUnreadFor`.
     *
     *   An empty collection short-circuits here rather than reaching SQLite. It is
     *   the ordinary state of a signed-in user who is a member of nothing, and
     *   SQLDelight would expand it to `IN ()`, which is a syntax error rather than
     *   an empty result.
     */
    override suspend fun hasUnreadPosts(userId: Long, projectIds: Collection<Long>): Boolean {
        if (projectIds.isEmpty()) return false
        return withContext(DatabaseDispatcher) {
            database.readsQueries.hasUnreadPosts(projectIds, userId).executeAsOne()
        }
    }
}
