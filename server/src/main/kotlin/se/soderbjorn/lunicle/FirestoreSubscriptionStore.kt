/**
 * The Firestore [se.soderbjorn.lunicle.store.SubscriptionStore] — "who wants to be
 * notified about what", over documents.
 *
 * ── Presence is the state ───────────────────────────────────────────────────
 *
 * The SQLite reference stores a subscription as a *row's presence* — INSERT OR
 * IGNORE to subscribe, DELETE to unsubscribe, no "subscribed = 0". This keeps that
 * exactly: a subscription is a document's presence in the kind's collection, keyed
 * by `{userId}_{targetId}`. Subscribing writes it (idempotent — same key), and
 * unsubscribing deletes it (a delete of a missing document is a no-op). Four kinds,
 * four collections, deliberately spelled out rather than generalised, mirroring
 * Subscriptions.sq — a missing one is obvious that way.
 *
 * ── The one join, made an injected lookup ───────────────────────────────────
 *
 * The `audienceFor*` queries need each subscriber's display name and address, which
 * the SQLite store gets by joining `users`. A document backend has no such join, and
 * the address must not be denormalised onto a subscription (a user who edits their
 * address later would leave every subscription row stale — the very thing
 * Subscriptions.sq keeps off the subscription). So the name/address is resolved at
 * read time through [resolveContacts], which the module wires to the Firestore
 * identity store (a separate ticket) and the contract wires to its seeded users. A
 * subscriber the lookup cannot resolve is dropped, exactly as the reference's inner
 * join drops a subscription with no matching user.
 *
 * Since LNL-109 the audience is the *whole* audience, addressed or not: a subscriber
 * with no address ([Contact.email] null) is still returned (they get the in-app
 * notification), and it is [NotificationRecipient.asEmailRecipient] — not this store
 * — that drops them from the mail. The only narrowing here is the one about *who*,
 * not how they are reached: not the actor.
 *
 * ── Composite indexes ───────────────────────────────────────────────────────
 *
 * None. Every read is a single-field equality (`targetId ==`) served by an automatic
 * index; the actor exclusion and the contact resolution happen in memory over the
 * small result.
 *
 * @see FirestoreProvider
 * @see se.soderbjorn.lunicle.store.SubscriptionStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.CollectionReference
import com.google.cloud.firestore.Firestore

class FirestoreSubscriptionStore(
    private val firestore: Firestore,
    private val resolveContacts: suspend (Set<Long>) -> Map<Long, Contact>,
) : se.soderbjorn.lunicle.store.SubscriptionStore {
    /** One subscriber's display facts, resolved from the identity store at read time. */
    data class Contact(val name: String, val email: String?)

    private fun projectNewIssue() = firestore.collection(PROJECT_NEW_ISSUE)
    private fun issueUpdate() = firestore.collection(ISSUE_UPDATE)
    private fun forumNewPost() = firestore.collection(FORUM_NEW_POST)
    private fun forumPost() = firestore.collection(FORUM_POST)

    // ── Project new-issue subscriptions ──────────────────────────────────────

    override suspend fun isSubscribedToProjectNewIssues(userId: Long, projectId: Long): Boolean =
        isSubscribed(projectNewIssue(), userId, projectId)

    override suspend fun setProjectNewIssueSubscription(userId: Long, projectId: Long, subscribed: Boolean) =
        setSubscription(projectNewIssue(), userId, projectId, subscribed)

    override suspend fun audienceForProjectNewIssue(projectId: Long, actorId: Long?): List<NotificationRecipient> =
        audience(projectNewIssue(), projectId, actorId)

    // ── Issue-update subscriptions ───────────────────────────────────────────

    override suspend fun isSubscribedToIssueUpdates(userId: Long, issueId: Long): Boolean =
        isSubscribed(issueUpdate(), userId, issueId)

    override suspend fun setIssueUpdateSubscription(userId: Long, issueId: Long, subscribed: Boolean) =
        setSubscription(issueUpdate(), userId, issueId, subscribed)

    override suspend fun audienceForIssueUpdate(issueId: Long, actorId: Long?): List<NotificationRecipient> =
        audience(issueUpdate(), issueId, actorId)

    /** The display names of everyone watching the issue — names only, ordered for a human, no addresses. */
    override suspend fun watchersForIssue(issueId: Long): List<String> {
        val userIds = subscriberIds(issueUpdate(), issueId)
        val contacts = resolveContacts(userIds)
        return userIds.mapNotNull { contacts[it]?.name }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    // ── Forum new-post subscriptions ─────────────────────────────────────────

    override suspend fun isSubscribedToForumNewPosts(userId: Long, forumId: Long): Boolean =
        isSubscribed(forumNewPost(), userId, forumId)

    override suspend fun setForumNewPostSubscription(userId: Long, forumId: Long, subscribed: Boolean) =
        setSubscription(forumNewPost(), userId, forumId, subscribed)

    override suspend fun audienceForForumNewPost(forumId: Long, actorId: Long?): List<NotificationRecipient> =
        audience(forumNewPost(), forumId, actorId)

    // ── Forum post subscriptions ─────────────────────────────────────────────

    override suspend fun isSubscribedToForumPost(userId: Long, postId: Long): Boolean =
        isSubscribed(forumPost(), userId, postId)

    override suspend fun setForumPostSubscription(userId: Long, postId: Long, subscribed: Boolean) =
        setSubscription(forumPost(), userId, postId, subscribed)

    override suspend fun audienceForForumPost(postId: Long, actorId: Long?): List<NotificationRecipient> =
        audience(forumPost(), postId, actorId)

    // ── Cascade ──────────────────────────────────────────────────────────────
    //
    // Forget everyone watching a container that is being deleted. Load-bearing on
    // this backend: a subscription is a document's *presence*, so nothing but an
    // explicit sweep ever removes one, and a watch on a deleted issue would go on
    // naming an audience for something that no longer exists. One equality on
    // `targetId` each, chunked. See the interface's comments.

    override suspend fun deleteIssueSubscriptions(issueId: Long) =
        deleteWhere(issueUpdate(), TARGET_ID, issueId)

    override suspend fun deleteProjectSubscriptions(projectId: Long) =
        deleteWhere(projectNewIssue(), TARGET_ID, projectId)

    override suspend fun deleteForumSubscriptions(forumId: Long) =
        deleteWhere(forumNewPost(), TARGET_ID, forumId)

    override suspend fun deleteForumPostSubscriptions(postId: Long) =
        deleteWhere(forumPost(), TARGET_ID, postId)

    // ── Shared machinery ─────────────────────────────────────────────────────

    private fun key(userId: Long, targetId: Long) = "${userId}_$targetId"

    private suspend fun isSubscribed(collection: CollectionReference, userId: Long, targetId: Long): Boolean =
        collection.document(key(userId, targetId)).get().await().exists()

    private suspend fun setSubscription(
        collection: CollectionReference,
        userId: Long,
        targetId: Long,
        subscribed: Boolean,
    ) {
        val ref = collection.document(key(userId, targetId))
        if (subscribed) {
            ref.set(mapOf(USER_ID to userId, TARGET_ID to targetId)).await()
        } else {
            ref.delete().await()
        }
    }

    /** The user ids subscribed to [targetId] in [collection]. */
    private suspend fun subscriberIds(collection: CollectionReference, targetId: Long): Set<Long> =
        collection.whereEqualTo(TARGET_ID, targetId).get().await()
            .documents.mapNotNull { it.getLong(USER_ID) }.toSet()

    /**
     * The whole audience for [targetId], minus [actorId] — you are not notified about
     * your own action. Addressed or not: the address split is the notifier's, via
     * [NotificationRecipient.asEmailRecipient].
     */
    private suspend fun audience(
        collection: CollectionReference,
        targetId: Long,
        actorId: Long?,
    ): List<NotificationRecipient> {
        val candidates = subscriberIds(collection, targetId).filter { it != actorId }.toSet()
        val contacts = resolveContacts(candidates)
        // mapNotNull over the resolved contacts — a candidate the identity store
        // cannot resolve is dropped, as the reference's inner join drops it.
        return candidates.mapNotNull { id ->
            contacts[id]?.let { NotificationRecipient(id, it.name, it.email) }
        }
    }

    internal companion object {
        const val PROJECT_NEW_ISSUE = "projectNewIssueSubscriptions"
        const val ISSUE_UPDATE = "issueUpdateSubscriptions"
        const val FORUM_NEW_POST = "forumNewPostSubscriptions"
        const val FORUM_POST = "forumPostSubscriptions"
        const val USER_ID = "userId"
        const val TARGET_ID = "targetId"
    }
}
