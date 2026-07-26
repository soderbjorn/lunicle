/**
 * The in-app notification store (LNL-109): that a notification round-trips, that
 * the unread count is what the bell will show, and — the property with teeth — that
 * a row is only ever the owner's to read, mark or delete.
 *
 * ── The failures this exists to catch ───────────────────────────────────────
 *
 *  - **A user with no address still gets a notification.** LNL-109's recipient
 *    decision, made at the store's front door: `record` takes a user id and asks
 *    nothing about an address, because the bell needs no mailbox. A regression that
 *    reintroduced an address gate here would silently drop the in-app half for
 *    exactly the accounts the feature was widened to reach.
 *  - **One user cannot touch another's row by id.** Every user-scoped statement
 *    carries `user_id` in its `WHERE`, so an id belonging to somebody else marks or
 *    deletes nothing. The id alone is not authority — the route never checks
 *    ownership because the store makes it structural, so this is where that claim
 *    has to be true.
 *  - **Newest first, and the count is the unread count.** What the panel and the
 *    bell rest on; an off-by-one or a stale order is invisible until a real list is
 *    read.
 *
 * @see NotificationStore
 * @see Notifications.sq
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.NotificationKind
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationStoreTest {
    private val file: File = Files.createTempFile("lunicle-notif-store", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database
    private val users = UserStore(database)

    // A hand-cranked clock so created_at is strictly increasing and the newest-first
    // order is a fact about the store rather than about how fast the test ran.
    private var clock = 1_000L
    private val store = NotificationStore(database) { clock++ }

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
    }

    /** Somebody with an address, and somebody without — both must be notifiable. */
    private fun user(providerId: String, email: String?): Long = runBlocking {
        users.upsert(ProviderIdentity(AuthProvider.GITHUB, providerId, providerId, email)).id
    }

    private fun issue(kind: NotificationKind, title: String) =
        NewNotification(kind = kind, title = title, projectId = 1, issueId = 7)

    @Test
    fun `a notification round-trips, newest first, with the unread count`() = runBlocking {
        val u = user("with-mail", "a@example.com")
        store.record(u, issue(NotificationKind.ISSUE_ASSIGNED, "first"))
        store.record(u, issue(NotificationKind.ISSUE_UPDATED, "second"))

        val list = store.listForUser(u)
        assertEquals(listOf("second", "first"), list.map { it.title }, "Not newest-first.")
        assertEquals(2, store.unreadCount(u), "Both should be unread.")
        val second = list.first()
        assertEquals(NotificationKind.ISSUE_UPDATED, second.kind)
        assertEquals(1L, second.projectId)
        assertEquals(7L, second.issueId)
        assertFalse(second.isRead)
    }

    @Test
    fun `a user with no address is still notified`() = runBlocking {
        // The whole point of the LNL-109 widening: no email, still a bell.
        val u = user("no-mail", null)
        store.record(u, issue(NotificationKind.ISSUE_MENTIONED, "hey you"))
        assertEquals(1, store.unreadCount(u))
        assertEquals("hey you", store.listForUser(u).single().title)
    }

    @Test
    fun `marking one read lowers the count and flips only that row`() = runBlocking {
        val u = user("reader", "r@example.com")
        store.record(u, issue(NotificationKind.ISSUE_ASSIGNED, "a"))
        store.record(u, issue(NotificationKind.ISSUE_ASSIGNED, "b"))
        val newest = store.listForUser(u).first()

        store.markRead(u, newest.id)

        assertEquals(1, store.unreadCount(u))
        val after = store.listForUser(u).associate { it.title to it.isRead }
        assertEquals(true, after["b"], "The marked row is not read.")
        assertEquals(false, after["a"], "The other row was marked too.")
    }

    @Test
    fun `mark all read clears the count but keeps the rows`() = runBlocking {
        val u = user("all", "all@example.com")
        store.record(u, issue(NotificationKind.ISSUE_ASSIGNED, "a"))
        store.record(u, issue(NotificationKind.ISSUE_ASSIGNED, "b"))
        store.markAllRead(u)
        assertEquals(0, store.unreadCount(u))
        assertEquals(2, store.listForUser(u).size, "Mark-all-read must not delete rows.")
    }

    @Test
    fun `dismiss removes one row and clear removes them all`() = runBlocking {
        val u = user("dismisser", "d@example.com")
        store.record(u, issue(NotificationKind.ISSUE_ASSIGNED, "a"))
        store.record(u, issue(NotificationKind.ISSUE_ASSIGNED, "b"))
        val victim = store.listForUser(u).first { it.title == "a" }

        store.dismiss(u, victim.id)
        assertEquals(listOf("b"), store.listForUser(u).map { it.title })

        store.clear(u)
        assertTrue(store.listForUser(u).isEmpty(), "Clear left rows behind.")
        assertEquals(0, store.unreadCount(u))
    }

    @Test
    fun `one user cannot mark or dismiss another user's row`() = runBlocking {
        val owner = user("owner", "o@example.com")
        val stranger = user("stranger", "s@example.com")
        store.record(owner, issue(NotificationKind.ISSUE_ASSIGNED, "mine"))
        val row = store.listForUser(owner).single()

        // The stranger holds the id but not the row. Both are no-ops, not errors:
        // a guessed id simply names nothing that is theirs.
        store.markRead(stranger, row.id)
        store.dismiss(stranger, row.id)

        val still = store.listForUser(owner).single()
        assertFalse(still.isRead, "A stranger marked the owner's row read.")
        assertEquals("mine", still.title, "A stranger deleted the owner's row.")
        assertEquals(1, store.unreadCount(owner))
        // ...and the stranger's own list never had it.
        assertTrue(store.listForUser(stranger).isEmpty())
    }
}
