/**
 * The persistence seam for the in-app notification list (LNL-109): one stored row
 * per notification per user, and every read, mark and delete over it.
 *
 * A straightforward per-user list — the opposite of [ReadStore]'s derived badges.
 * A notification is not "have you caught up on this container" but "this specific
 * thing happened to you, once", so it is a real row carrying its own text and its
 * own destination ids, written when the event fires. The reference implementation
 * is the SQLite gateway [se.soderbjorn.lunicle.NotificationStore] (named by its
 * fully-qualified name in that class's supertype clause, since the two share a
 * simple name).
 *
 * No permission is decided here: a notification belongs to exactly one user, so
 * every user-scoped method carries that user's id in the query — an id alone is not
 * authority to mark or delete a row. That rule is what a document backend must keep
 * too, and the contract pins it.
 *
 * @see se.soderbjorn.lunicle.store.NotificationStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.NewNotification
import se.soderbjorn.lunicle.clientserver.NotificationSummary

interface NotificationStore {
    /** Store one notification for [userId], stamped now. */
    suspend fun record(userId: Long, notification: NewNotification)

    /** All of [userId]'s notifications, newest first. */
    suspend fun listForUser(userId: Long): List<NotificationSummary>

    /** How many of [userId]'s notifications are unread — the bell's poll. */
    suspend fun unreadCount(userId: Long): Int

    /** Mark [id] read, but only if it belongs to [userId]. */
    suspend fun markRead(userId: Long, id: Long)

    /** Mark all of [userId]'s notifications read. */
    suspend fun markAllRead(userId: Long)

    /** Dismiss [id] (hard delete), but only if it belongs to [userId]. */
    suspend fun dismiss(userId: Long, id: Long)

    /** Clear all of [userId]'s notifications. */
    suspend fun clear(userId: Long)

    /**
     * Drop every notification pointing at [projectId], for everybody (LUS-14).
     *
     * Notifications are deliberately excluded from the delete cascade, on the sound
     * reasoning that a notification pointing at a deleted issue is merely *stale* —
     * the pointer 404s, and that is the correct outcome. What that reasoning does
     * not address is the **payload**: a row stores the issue or post title verbatim,
     * so deleting a private project leaves its titles readable indefinitely in every
     * recipient's list.
     *
     * Which makes the project the right granularity for an exception, and only the
     * project. An issue deleted out of a project somebody can still read has leaked
     * nothing — they could read that title when it arrived and a moment before it
     * went.
     */
    suspend fun deleteForProject(projectId: Long)

    /**
     * Drop [userId]'s notifications for [projectId] — the other half of LUS-14.
     *
     * Revoking somebody's rung writes to the roles table and nothing else, so
     * without this they keep a permanent, indexed list of titles from a board they
     * can no longer open. The list route re-filters by *present* access, which is
     * what closes the leak; this is what stops the rows sitting there until somebody
     * is re-granted a rung and sees a year of history arrive at once.
     */
    suspend fun deleteForUserInProject(userId: Long, projectId: Long)
}
