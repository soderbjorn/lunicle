/**
 * The Firestore [se.soderbjorn.lunicle.store.NotificationStore] — the in-app
 * notification list (LNL-109) over documents.
 *
 * ── Document model ──────────────────────────────────────────────────────────
 *
 * One document per notification in `notifications/{id}`, where `{id}` is the global
 * `Long` id the client marks and dismisses rows by, allocated from
 * `_counters/notifications` (see [FirestoreCounters]) so it is monotonic and never
 * reused — exactly the SQLite `AUTOINCREMENT` guarantee. The owner is denormalised
 * onto the row as `userId`, which is what turns every read into a single-field
 * equality query rather than a join: the panel list, the bell's unread count, and
 * the two bulk writes all filter on `userId ==`.
 *
 * ── Why the id belongs to the owner, in the query not just by convention ─────
 *
 * The SQLite store scopes [markRead]/[dismiss] with `AND user_id = ?` so a guessed
 * id cannot touch another user's row. A document keyed by the global id has no such
 * clause built in, so this reads the document first and acts only when its `userId`
 * matches — the same rule, enforced at read time. A row that is absent, or present
 * but owned by somebody else, is left untouched, which is the no-op the SQLite
 * `WHERE` produces.
 *
 * ── Composite indexes ───────────────────────────────────────────────────────
 *
 * None. Every read is a single-field equality (`userId ==`) served by an automatic
 * index; the newest-first ordering and the unread count are computed in memory over
 * one user's rows, which are bounded by notifications actually sent to them.
 *
 * @see FirestoreProvider
 * @see FirestoreCounters
 * @see se.soderbjorn.lunicle.store.NotificationStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.clientserver.NotificationKind
import se.soderbjorn.lunicle.clientserver.NotificationSummary
import se.soderbjorn.lunicle.store.NotificationStore

class FirestoreNotificationStore(
    private val firestore: Firestore,
    private val now: () -> Long = System::currentTimeMillis,
) : NotificationStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    /**
     * Store one notification for [userId], stamped now, its id allocated in the same
     * transaction that writes it — a crash can never advance the counter past a row
     * that was never written.
     */
    override suspend fun record(userId: Long, notification: NewNotification) {
        val createdAt = now()
        firestore.runTransaction { txn ->
            val id = counters.next(txn, ID_COUNTER).getValue(ID_COUNTER)
            txn.set(
                doc(id),
                mapOf(
                    ID to id,
                    USER_ID to userId,
                    CREATED_AT to createdAt,
                    IS_READ to false,
                    KIND to notification.kind.name,
                    TITLE to notification.title,
                    DEST_PROJECT_ID to notification.projectId,
                    DEST_ISSUE_ID to notification.issueId,
                    DEST_CONVERSATION_ID to notification.conversationId,
                    DEST_MESSAGE_ID to notification.messageId,
                    DEST_FORUM_ID to notification.forumId,
                    DEST_POST_ID to notification.postId,
                ),
            )
        }.await()
    }

    /**
     * All of [userId]'s notifications, newest first.
     *
     * The order is applied in memory — `createdAt` desc then `id` desc, the SQLite
     * `ORDER BY created_at DESC, id DESC` — over the one user's rows, so the query
     * stays a single-field equality with no composite index. A row whose stored
     * `kind` this build no longer knows is dropped rather than crashing the list,
     * exactly as the SQLite mapper drops it.
     */
    override suspend fun listForUser(userId: Long): List<NotificationSummary> =
        rowsFor(userId)
            .sortedWith(compareByDescending<DocumentSnapshot> { it.getLong(CREATED_AT) ?: 0 }.thenByDescending { it.getLong(ID) ?: 0 })
            .mapNotNull { it.toSummary() }

    /** How many of [userId]'s notifications are unread — counted in memory to avoid a composite index. */
    override suspend fun unreadCount(userId: Long): Int =
        rowsFor(userId).count { it.getBoolean(IS_READ) != true }

    /** Mark [id] read, but only if it belongs to [userId] — the ownership check the SQLite `WHERE` makes. */
    override suspend fun markRead(userId: Long, id: Long) {
        val snapshot = doc(id).get().await()
        if (snapshot.exists() && snapshot.getLong(USER_ID) == userId) {
            doc(id).update(IS_READ, true).await()
        }
    }

    /** Mark all of [userId]'s notifications read, in one batch. */
    override suspend fun markAllRead(userId: Long) {
        val batch = firestore.batch()
        rowsFor(userId).forEach { batch.update(it.reference, IS_READ, true) }
        batch.commit().await()
    }

    /** Dismiss [id] (hard delete), but only if it belongs to [userId]. */
    override suspend fun dismiss(userId: Long, id: Long) {
        val snapshot = doc(id).get().await()
        if (snapshot.exists() && snapshot.getLong(USER_ID) == userId) {
            doc(id).delete().await()
        }
    }

    /** Clear all of [userId]'s notifications, in one batch. */
    override suspend fun clear(userId: Long) {
        val batch = firestore.batch()
        rowsFor(userId).forEach { batch.delete(it.reference) }
        batch.commit().await()
    }

    /** One user's rows, unordered — the single-field equality every read is built on. */
    private suspend fun rowsFor(userId: Long): List<DocumentSnapshot> =
        collection().whereEqualTo(USER_ID, userId).get().await().documents

    private fun DocumentSnapshot.toSummary(): NotificationSummary? {
        val kind = NotificationKind.entries.firstOrNull { it.name == getString(KIND) } ?: return null
        return NotificationSummary(
            id = getLong(ID)!!,
            kind = kind,
            title = getString(TITLE).orEmpty(),
            createdAt = getLong(CREATED_AT)!!,
            isRead = getBoolean(IS_READ) ?: false,
            projectId = getLong(DEST_PROJECT_ID),
            issueId = getLong(DEST_ISSUE_ID),
            conversationId = getLong(DEST_CONVERSATION_ID),
            messageId = getLong(DEST_MESSAGE_ID),
            forumId = getLong(DEST_FORUM_ID),
            postId = getLong(DEST_POST_ID),
        )
    }

    private companion object {
        const val COLLECTION = "notifications"
        const val ID_COUNTER = "notifications"

        const val ID = "id"
        const val USER_ID = "userId"
        const val CREATED_AT = "createdAt"
        const val IS_READ = "isRead"
        const val KIND = "kind"
        const val TITLE = "title"
        const val DEST_PROJECT_ID = "destProjectId"
        const val DEST_ISSUE_ID = "destIssueId"
        const val DEST_CONVERSATION_ID = "destConversationId"
        const val DEST_MESSAGE_ID = "destMessageId"
        const val DEST_FORUM_ID = "destForumId"
        const val DEST_POST_ID = "destPostId"
    }
}
