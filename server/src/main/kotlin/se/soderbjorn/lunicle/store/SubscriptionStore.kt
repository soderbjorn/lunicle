/**
 * The persistence seam for "who wants to be notified about what".
 *
 * One of the LNL-111 domain store interfaces. The reference implementation is
 * today's SQLite [se.soderbjorn.lunicle.SubscriptionStore], where a row's
 * *presence* is the subscription; a document backend is free to model it
 * differently as long as the behaviour the contract pins holds.
 *
 * The surface is four pairs of two — project-new-issue, issue-update,
 * forum-new-post, forum-post — deliberately spelled out rather than generalised,
 * mirroring the reference implementation and Subscriptions.sq. A missing one is
 * obvious that way.
 *
 * Since LNL-109 the `audienceFor*` queries return the WHOLE audience — addressed
 * or not — because the in-app notification reaches everyone while only those with
 * an address are mailed (via [NotificationRecipient.asEmailRecipient]). Two things
 * these queries still do NOT decide: whether a candidate can see the project (the
 * notification services narrow that), and the address split itself.
 *
 * @see se.soderbjorn.lunicle.store.SubscriptionStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.NotificationRecipient

interface SubscriptionStore {
    // ── Project new-issue subscriptions ──────────────────────────────────────

    suspend fun isSubscribedToProjectNewIssues(userId: Long, projectId: Long): Boolean

    /** Subscribe or unsubscribe; idempotent either way. */
    suspend fun setProjectNewIssueSubscription(userId: Long, projectId: Long, subscribed: Boolean)

    /** The whole audience (addressed or not) to notify about a new issue, except the actor. */
    suspend fun audienceForProjectNewIssue(projectId: Long, actorId: Long?): List<NotificationRecipient>

    // ── Issue-update subscriptions ───────────────────────────────────────────

    suspend fun isSubscribedToIssueUpdates(userId: Long, issueId: Long): Boolean

    /** Subscribe or unsubscribe; idempotent either way. */
    suspend fun setIssueUpdateSubscription(userId: Long, issueId: Long, subscribed: Boolean)

    /** The whole audience (addressed or not) to notify about an issue update, except the actor. */
    suspend fun audienceForIssueUpdate(issueId: Long, actorId: Long?): List<NotificationRecipient>

    /** The display names of everyone watching the issue — names only, no addresses. */
    suspend fun watchersForIssue(issueId: Long): List<String>

    // ── Forum new-post subscriptions ─────────────────────────────────────────

    suspend fun isSubscribedToForumNewPosts(userId: Long, forumId: Long): Boolean

    /** Subscribe or unsubscribe; idempotent either way. */
    suspend fun setForumNewPostSubscription(userId: Long, forumId: Long, subscribed: Boolean)

    /** Candidates (actor-narrowed, but NOT visibility-narrowed) for a new post. */
    suspend fun audienceForForumNewPost(forumId: Long, actorId: Long?): List<NotificationRecipient>

    // ── Forum post subscriptions ─────────────────────────────────────────────

    suspend fun isSubscribedToForumPost(userId: Long, postId: Long): Boolean

    /** Subscribe or unsubscribe; idempotent either way. */
    suspend fun setForumPostSubscription(userId: Long, postId: Long, subscribed: Boolean)

    /** Candidates (actor-narrowed, but NOT visibility-narrowed) for a post comment. */
    suspend fun audienceForForumPost(postId: Long, actorId: Long?): List<NotificationRecipient>
}
