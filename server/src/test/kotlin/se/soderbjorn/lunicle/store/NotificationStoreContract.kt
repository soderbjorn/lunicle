/**
 * The behaviour every [NotificationStore] implementation must exhibit — the in-app
 * notification list (LNL-109).
 *
 * The parity-critical things pinned here are the ones a document backend keying by
 * a global id can get subtly different from the SQLite `WHERE user_id = ?`: that a
 * recorded notification comes back in the owner's list newest-first with its
 * destination intact, that the unread count tracks reads, that [markRead] and
 * [dismiss] touch a row *only* when it belongs to the asking user (an id is not
 * authority), that [markAllRead]/[clear] act on exactly the owner's rows, and that
 * one user's notifications never leak into another's list.
 *
 * A subclass per backend supplies the store and a way to mint the users a
 * notification is recorded for; the assertions live here.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.NewNotification
import se.soderbjorn.lunicle.clientserver.NotificationKind

abstract class NotificationStoreContract {
    protected abstract val store: NotificationStore

    /** A fresh user a notification can be recorded for, per the backend under test. */
    protected abstract suspend fun newUser(): Long

    private fun issueNotification(title: String, projectId: Long = 1L, issueId: Long = 2L) =
        NewNotification(NotificationKind.ISSUE_ASSIGNED, title, projectId = projectId, issueId = issueId)

    @Test
    fun `a recorded notification appears in the owner's list, newest first, with its destination`() = runBlocking {
        val user = newUser()
        store.record(user, issueNotification("First", projectId = 7L, issueId = 42L))
        store.record(user, issueNotification("Second", projectId = 8L, issueId = 43L))

        val list = store.listForUser(user)
        assertEquals(listOf("Second", "First"), list.map { it.title }, "newest first")
        val second = list.first()
        assertEquals(NotificationKind.ISSUE_ASSIGNED, second.kind)
        assertEquals(8L, second.projectId, "the destination round-trips")
        assertEquals(43L, second.issueId)
        assertTrue(list.all { !it.isRead }, "a fresh notification is unread")
    }

    @Test
    fun `unread count tracks reads`() = runBlocking {
        val user = newUser()
        store.record(user, issueNotification("A"))
        store.record(user, issueNotification("B"))
        assertEquals(2, store.unreadCount(user))

        val first = store.listForUser(user).first()
        store.markRead(user, first.id)
        assertEquals(1, store.unreadCount(user), "reading one drops the count")
    }

    @Test
    fun `markRead touches a row only when it belongs to the asking user`() = runBlocking {
        val owner = newUser()
        val stranger = newUser()
        store.record(owner, issueNotification("Owned"))
        val id = store.listForUser(owner).single().id

        store.markRead(stranger, id)
        assertTrue(store.listForUser(owner).single().let { !it.isRead }, "a stranger's mark does nothing")

        store.markRead(owner, id)
        assertTrue(store.listForUser(owner).single().isRead, "the owner's mark takes")
    }

    @Test
    fun `markAllRead marks exactly the user's rows`() = runBlocking {
        val user = newUser()
        val other = newUser()
        store.record(user, issueNotification("A"))
        store.record(user, issueNotification("B"))
        store.record(other, issueNotification("Theirs"))

        store.markAllRead(user)
        assertEquals(0, store.unreadCount(user), "all of the user's are read")
        assertEquals(1, store.unreadCount(other), "another user's are untouched")
    }

    @Test
    fun `dismiss deletes a row only when it belongs to the asking user`() = runBlocking {
        val owner = newUser()
        val stranger = newUser()
        store.record(owner, issueNotification("Owned"))
        val id = store.listForUser(owner).single().id

        store.dismiss(stranger, id)
        assertEquals(1, store.listForUser(owner).size, "a stranger cannot dismiss it")

        store.dismiss(owner, id)
        assertTrue(store.listForUser(owner).isEmpty(), "the owner can")
    }

    @Test
    fun `clear removes exactly the user's rows`() = runBlocking {
        val user = newUser()
        val other = newUser()
        store.record(user, issueNotification("A"))
        store.record(user, issueNotification("B"))
        store.record(other, issueNotification("Theirs"))

        store.clear(user)
        assertTrue(store.listForUser(user).isEmpty(), "the user's list is emptied")
        assertEquals(1, store.listForUser(other).size, "another user's list is untouched")
    }

    @Test
    fun `notifications are isolated per user`() = runBlocking {
        val alice = newUser()
        val bob = newUser()
        store.record(alice, issueNotification("Alice's"))

        assertEquals(listOf("Alice's"), store.listForUser(alice).map { it.title })
        assertFalse(store.listForUser(bob).any { it.title == "Alice's" }, "Bob does not see Alice's")
        assertEquals(0, store.unreadCount(bob))
    }

    // ── The two project-scoped deletes (LUS-14) ──────────────────────────────

    /**
     * A deleted project takes its titles with it, for everybody.
     *
     * The row stores the issue or post title verbatim, so without this a deleted
     * private project stays readable — as metadata — in every recipient's list
     * indefinitely. Notifications are otherwise outside the delete cascade on
     * purpose, and this is the one deliberate exception.
     */
    @Test
    fun `deleteForProject removes that project's rows and no others`() = runBlocking {
        val alice = newUser()
        val bob = newUser()
        store.record(alice, issueNotification("Doomed", projectId = 90L))
        store.record(alice, issueNotification("Kept", projectId = 91L))
        store.record(bob, issueNotification("Also doomed", projectId = 90L))

        store.deleteForProject(90L)

        assertEquals(listOf("Kept"), store.listForUser(alice).map { it.title })
        assertTrue(store.listForUser(bob).isEmpty(), "another recipient kept the deleted project's title")
    }

    /** A withdrawn rung takes one person's rows for one project, and nobody else's. */
    @Test
    fun `deleteForUserInProject is scoped to the user and the project`() = runBlocking {
        val leaver = newUser()
        val stayer = newUser()
        store.record(leaver, issueNotification("Theirs, gone", projectId = 95L))
        store.record(leaver, issueNotification("Theirs, elsewhere", projectId = 96L))
        store.record(stayer, issueNotification("Somebody else's", projectId = 95L))

        store.deleteForUserInProject(leaver, 95L)

        assertEquals(listOf("Theirs, elsewhere"), store.listForUser(leaver).map { it.title })
        assertEquals(
            listOf("Somebody else's"),
            store.listForUser(stayer).map { it.title },
            "one person losing a rung emptied somebody else's list",
        )
    }
}
