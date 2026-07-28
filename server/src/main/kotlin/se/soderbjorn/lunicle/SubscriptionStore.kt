/**
 * Who wants an e-mail about what.
 *
 * Four subscriptions, all the same shape — a row's presence *is* the
 * subscription — over the four tables in Subscriptions.sq. This store answers two
 * kinds of question and nothing decides a permission from it: "is this user
 * subscribed?" (for the dialog's toggle state) and "who should be e-mailed?" (for
 * [EmailNotifier], at send time). See Subscriptions.sq for why presence, not a
 * boolean column, is the state.
 *
 * The forum pair added by LNL-63 is the issue pair one feature over — a forum's
 * new posts where a project has new issues, a post's comments where an issue has
 * updates — and it is deliberately spelled out rather than generalised. The
 * reasoning is in Subscriptions.sq's preamble, and the visible consequence here
 * is that the eight methods below are four pairs of two, which is what makes a
 * missing one obvious.
 *
 * ── The one thing the recipient queries do not answer ───────────────────────
 *
 * Whether the watcher can still *see* the project. LNL-63 requires that losing
 * sight of a project stops its forum e-mail, and that rule lives in
 * [AccessControl] and [ProjectAudience] rather than being re-spelled in SQL here
 * — [ForumNotificationService] narrows what these return. See Subscriptions.sq.
 *
 * @see EmailNotifier
 * @see Database
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * One person an e-mail could go to.
 *
 * Carries the address and the name to greet them by, resolved the same way every
 * other screen resolves it (`display_name ?: provider_name`). The [email] is
 * non-null by construction — this is the *addressed* half of an audience — but it
 * is no longer the SQL that guarantees that: since LNL-109 the recipient queries
 * return everyone (a subscriber with no address gets an in-app notification), and
 * the address filter is the notifier's `NotificationRecipient.asEmailRecipient`,
 * which yields one of these only when there is an address. See Subscriptions.sq and
 * [NotificationRecipient].
 */
data class EmailRecipient(
    val userId: Long,
    val email: String,
    val name: String,
)

/**
 * One person in a notification's audience — the whole audience, addressed or not.
 *
 * The superset LNL-109 introduced: a notification e-mail's recipients are the
 * subset of an audience that has an address, but the *in-app* notification reaches
 * everyone, because the bell needs no mailbox. So the recipient queries now return
 * this — [email] nullable — and the notifier writes an in-app row for every member
 * while mailing only [asEmailRecipient].
 */
data class NotificationRecipient(
    val userId: Long,
    val name: String,
    val email: String?,
) {
    /** The addressed view of this person, or null when they have no address on file. */
    fun asEmailRecipient(): EmailRecipient? = email?.let { EmailRecipient(userId, it, name) }
}

/**
 * Reads and writes the two subscription tables.
 *
 * @param database the open database.
 */
class SubscriptionStore(
    private val database: LunicleDatabase,
) : se.soderbjorn.lunicle.store.SubscriptionStore {
    // ── Project new-issue subscriptions ──────────────────────────────────────

    /** Does [userId] want an e-mail when a new issue is created in [projectId]? */
    override suspend fun isSubscribedToProjectNewIssues(userId: Long, projectId: Long): Boolean =
        withContext(DatabaseDispatcher) {
            database.subscriptionsQueries.isSubscribedProjectNewIssues(userId, projectId).executeAsOne()
        }

    /** Subscribe or unsubscribe [userId] to [projectId]'s new issues. Idempotent either way. */
    override suspend fun setProjectNewIssueSubscription(userId: Long, projectId: Long, subscribed: Boolean): Unit =
        withContext(DatabaseDispatcher) {
            if (subscribed) {
                database.subscriptionsQueries.subscribeProjectNewIssues(userId, projectId)
            } else {
                database.subscriptionsQueries.unsubscribeProjectNewIssues(userId, projectId)
            }
        }

    /**
     * Everyone to notify about a new issue in [projectId], except [actorId] — you
     * are not notified about your own action. The whole audience, addressed or not;
     * the notifier writes an in-app row for all and mails those with an address. See
     * Subscriptions.sq and [NotificationRecipient].
     */
    override suspend fun audienceForProjectNewIssue(projectId: Long, actorId: Long?): List<NotificationRecipient> =
        withContext(DatabaseDispatcher) {
            database.subscriptionsQueries
                // -1 is never a real user id (AUTOINCREMENT starts at 1), so a null
                // actor — an unauthenticated or system write — excludes nobody.
                .recipientsForProjectNewIssue(projectId, actorId ?: -1L) { id, email, name ->
                    NotificationRecipient(id, name, email)
                }
                .executeAsList()
        }

    // ── Issue-update subscriptions ───────────────────────────────────────────

    /** Does [userId] want e-mails about updates to [issueId]? */
    override suspend fun isSubscribedToIssueUpdates(userId: Long, issueId: Long): Boolean =
        withContext(DatabaseDispatcher) {
            database.subscriptionsQueries.isSubscribedIssueUpdates(userId, issueId).executeAsOne()
        }

    /** Subscribe or unsubscribe [userId] to [issueId]'s updates. Idempotent either way. */
    override suspend fun setIssueUpdateSubscription(userId: Long, issueId: Long, subscribed: Boolean): Unit =
        withContext(DatabaseDispatcher) {
            if (subscribed) {
                database.subscriptionsQueries.subscribeIssueUpdates(userId, issueId)
            } else {
                database.subscriptionsQueries.unsubscribeIssueUpdates(userId, issueId)
            }
        }

    /**
     * Everyone to notify about an update to [issueId], except [actorId]. The whole
     * audience, addressed or not; see [audienceForProjectNewIssue].
     */
    override suspend fun audienceForIssueUpdate(issueId: Long, actorId: Long?): List<NotificationRecipient> =
        withContext(DatabaseDispatcher) {
            database.subscriptionsQueries
                .recipientsForIssueUpdate(issueId, actorId ?: -1L) { id, email, name ->
                    NotificationRecipient(id, name, email)
                }
                .executeAsList()
        }

    /**
     * The names of everyone watching [issueId], for the issue detail's watcher
     * list. Names only — no address crosses; see Subscriptions.sq.
     */
    override suspend fun watchersForIssue(issueId: Long): List<String> =
        withContext(DatabaseDispatcher) {
            database.subscriptionsQueries
                .watchersForIssue(issueId) { _, name -> name }
                .executeAsList()
        }

    // ── Forum new-post subscriptions (LNL-63) ────────────────────────────────

    /** Does [userId] want an e-mail when somebody posts in [forumId]? */
    override suspend fun isSubscribedToForumNewPosts(userId: Long, forumId: Long): Boolean =
        withContext(DatabaseDispatcher) {
            database.subscriptionsQueries.isSubscribedForumNewPosts(userId, forumId).executeAsOne()
        }

    /** Subscribe or unsubscribe [userId] to [forumId]'s new posts. Idempotent either way. */
    override suspend fun setForumNewPostSubscription(userId: Long, forumId: Long, subscribed: Boolean): Unit =
        withContext(DatabaseDispatcher) {
            if (subscribed) {
                database.subscriptionsQueries.subscribeForumNewPosts(userId, forumId)
            } else {
                database.subscriptionsQueries.unsubscribeForumNewPosts(userId, forumId)
            }
        }

    /**
     * Everyone who has asked about new posts in [forumId] and could be notified,
     * except [actorId].
     *
     * **Candidates, not recipients**: actor is narrowed here, in SQL; project
     * visibility is narrowed by [ForumNotificationService], the only caller, and the
     * address split is made there too. Notifying (in-app or by mail) straight from
     * this list would reach somebody whose access to the project has since been
     * revoked — the acceptance criterion LNL-63 is most likely to fail silently.
     */
    override suspend fun audienceForForumNewPost(forumId: Long, actorId: Long?): List<NotificationRecipient> =
        withContext(DatabaseDispatcher) {
            database.subscriptionsQueries
                // -1 for a null actor, as above: never a real id, so nobody is excluded.
                .recipientsForForumNewPost(forumId, actorId ?: -1L) { id, email, name ->
                    NotificationRecipient(id, name, email)
                }
                .executeAsList()
        }

    // ── Forum post subscriptions ─────────────────────────────────────────────

    /** Does [userId] want e-mails about comments on [postId]? */
    override suspend fun isSubscribedToForumPost(userId: Long, postId: Long): Boolean =
        withContext(DatabaseDispatcher) {
            database.subscriptionsQueries.isSubscribedForumPost(userId, postId).executeAsOne()
        }

    /** Subscribe or unsubscribe [userId] to [postId]'s comments. Idempotent either way. */
    override suspend fun setForumPostSubscription(userId: Long, postId: Long, subscribed: Boolean): Unit =
        withContext(DatabaseDispatcher) {
            if (subscribed) {
                database.subscriptionsQueries.subscribeForumPost(userId, postId)
            } else {
                database.subscriptionsQueries.unsubscribeForumPost(userId, postId)
            }
        }

    /**
     * Everyone watching [postId] who could be notified of a comment, except
     * [actorId]. Candidates rather than recipients; see [audienceForForumNewPost].
     */
    override suspend fun audienceForForumPost(postId: Long, actorId: Long?): List<NotificationRecipient> =
        withContext(DatabaseDispatcher) {
            database.subscriptionsQueries
                .recipientsForForumPost(postId, actorId ?: -1L) { id, email, name ->
                    NotificationRecipient(id, name, email)
                }
                .executeAsList()
        }

    // ── Cascade ──────────────────────────────────────────────────────────────
    //
    // Four cascades the schema would have run anyway, a moment early. See the
    // interface's comments on why they are called at all, and the queries' on why
    // they are harmless here.

    override suspend fun deleteIssueSubscriptions(issueId: Long): Unit = withContext(DatabaseDispatcher) {
        database.subscriptionsQueries.deleteIssueSubscriptions(issueId)
    }

    override suspend fun deleteProjectSubscriptions(projectId: Long): Unit = withContext(DatabaseDispatcher) {
        database.subscriptionsQueries.deleteProjectSubscriptions(projectId)
    }

    override suspend fun deleteForumSubscriptions(forumId: Long): Unit = withContext(DatabaseDispatcher) {
        database.subscriptionsQueries.deleteForumSubscriptions(forumId)
    }

    override suspend fun deleteForumPostSubscriptions(postId: Long): Unit = withContext(DatabaseDispatcher) {
        database.subscriptionsQueries.deleteForumPostSubscriptions(postId)
    }
}
