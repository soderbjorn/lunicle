/**
 * The in-app notification list (LNL-109): reads and writes the `notifications`
 * table, and decides no permission from any of it.
 *
 * One store over Notifications.sq, shaped like [ReadStore] — every method `suspend`
 * and pinned to [DatabaseDispatcher], no business rule anywhere. Who may read or
 * touch a notification is settled at the route: a notification belongs to exactly
 * one user, so the routes pass that user's id and every user-scoped statement here
 * carries it in the `WHERE`, which is why an id alone is not authority to mark or
 * delete a row.
 *
 * The write side has one caller — the notification plumbing in EmailNotifier.kt,
 * which records a row for each recipient at the same moment it composes the twin
 * e-mail. The read/mark/delete side has one caller too: [notificationRoutes].
 *
 * @see Notifications.sq
 * @see NotificationDispatcher
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.clientserver.NotificationKind
import se.soderbjorn.lunicle.clientserver.NotificationSummary
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * One notification to store, before it has an id or a timestamp.
 *
 * The flat set of destination ids mirrors the `dest_*` columns rather than a sealed
 * per-kind hierarchy: the table is flat for the reason Subscriptions.sq gives about
 * its four tables — a destination that varied its *shape* by kind could not be one
 * set of columns — and a draft that mirrors the table is the draft that cannot
 * disagree with it. Which ids a given [kind] populates is documented on
 * [NotificationKind]; the builders in EmailNotifier.kt are the only place they are
 * filled, so the pairing is checked in one file.
 */
data class NewNotification(
    val kind: NotificationKind,
    val title: String,
    val projectId: Long? = null,
    val issueId: Long? = null,
    val conversationId: Long? = null,
    val messageId: Long? = null,
    val forumId: Long? = null,
    val postId: Long? = null,
)

/**
 * Reads and writes `notifications`. No rules.
 *
 * @param database the open database.
 * @param now the clock, injectable for tests, as everywhere else in this server
 *   ([Comments], [Attachments], …). One `now()` per stored row.
 */
class NotificationStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.NotificationStore {
    /** Store one notification for [userId], stamped now. */
    override suspend fun record(userId: Long, notification: NewNotification): Unit =
        withContext(DatabaseDispatcher) {
            database.notificationsQueries.insert(
                user_id = userId,
                created_at = now(),
                kind = notification.kind.name,
                title = notification.title,
                dest_project_id = notification.projectId,
                dest_issue_id = notification.issueId,
                dest_conversation_id = notification.conversationId,
                dest_message_id = notification.messageId,
                dest_forum_id = notification.forumId,
                dest_post_id = notification.postId,
            )
        }

    /**
     * All of [userId]'s notifications, newest first.
     *
     * A row whose stored `kind` is one this build no longer knows is dropped rather
     * than crashing the whole list — the same forgiving stance [NotificationListState]
     * takes on the wire. In practice this server wrote every row and knows every
     * kind; the guard is for a kind removed in a future version, not a live case.
     */
    override suspend fun listForUser(userId: Long): List<NotificationSummary> = withContext(DatabaseDispatcher) {
        // The generated row type is mapped in Kotlin rather than in a SQLDelight
        // mapper lambda: that lambda's result must be non-null (`T : Any`), and a
        // row whose stored `kind` is unknown to this build maps to null and is
        // dropped here.
        database.notificationsQueries.listForUser(userId).executeAsList().mapNotNull { row ->
            val kind = NotificationKind.entries.firstOrNull { it.name == row.kind } ?: return@mapNotNull null
            NotificationSummary(
                id = row.id,
                kind = kind,
                title = row.title,
                createdAt = row.created_at,
                isRead = row.is_read != 0L,
                projectId = row.dest_project_id,
                issueId = row.dest_issue_id,
                conversationId = row.dest_conversation_id,
                messageId = row.dest_message_id,
                forumId = row.dest_forum_id,
                postId = row.dest_post_id,
            )
        }
    }

    /** How many of [userId]'s notifications are unread — the bell's poll. */
    override suspend fun unreadCount(userId: Long): Int = withContext(DatabaseDispatcher) {
        database.notificationsQueries.unreadCount(userId).executeAsOne().toInt()
    }

    /** Mark [id] read, but only if it belongs to [userId]. */
    override suspend fun markRead(userId: Long, id: Long): Unit = withContext(DatabaseDispatcher) {
        database.notificationsQueries.markRead(id, userId)
    }

    /** Mark all of [userId]'s notifications read. */
    override suspend fun markAllRead(userId: Long): Unit = withContext(DatabaseDispatcher) {
        database.notificationsQueries.markAllRead(userId)
    }

    /** Dismiss [id] (hard delete), but only if it belongs to [userId]. */
    override suspend fun dismiss(userId: Long, id: Long): Unit = withContext(DatabaseDispatcher) {
        database.notificationsQueries.delete(id, userId)
    }

    /** Clear all of [userId]'s notifications. */
    override suspend fun clear(userId: Long): Unit = withContext(DatabaseDispatcher) {
        database.notificationsQueries.deleteAllForUser(userId)
    }
}
