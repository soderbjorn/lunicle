/**
 * The behaviour every [SubscriptionStore] implementation must exhibit.
 *
 * The parity-critical things here are the ones easy to get subtly different
 * between a relational and a document backend: that a subscription's *presence*
 * is its state (no third "unknown"), that toggling is idempotent, that one user's
 * subscription never leaks into another's, and — the notification-correctness
 * ones — that the recipient queries exclude the actor, exclude subscribers with
 * no address, and treat a null actor as excluding nobody.
 *
 * A subclass per backend supplies the store and seeds the entities a subscription
 * points at (users, a project, an issue); the assertions live here.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

abstract class SubscriptionStoreContract {
    protected abstract val store: SubscriptionStore

    /** A fresh user with (or without) a verified address, per the backend under test. */
    protected abstract suspend fun newUser(email: String?): Long

    /** A fresh project that a subscription can reference. */
    protected abstract suspend fun newProject(): Long

    /** A fresh published issue in [projectId] that a subscription can reference. */
    protected abstract suspend fun newPublishedIssue(projectId: Long): Long

    // ── Presence-is-state, on the project pair ───────────────────────────────

    @Test
    fun `a user is not subscribed to a project by default`() = runBlocking {
        assertFalse(store.isSubscribedToProjectNewIssues(newUser(null), newProject()))
    }

    @Test
    fun `subscribing then reading reports subscribed`() = runBlocking {
        val user = newUser(null)
        val project = newProject()
        store.setProjectNewIssueSubscription(user, project, true)
        assertTrue(store.isSubscribedToProjectNewIssues(user, project))
    }

    @Test
    fun `unsubscribing reports not subscribed`() = runBlocking {
        val user = newUser(null)
        val project = newProject()
        store.setProjectNewIssueSubscription(user, project, true)
        store.setProjectNewIssueSubscription(user, project, false)
        assertFalse(store.isSubscribedToProjectNewIssues(user, project))
    }

    @Test
    fun `subscribing twice is idempotent, and unsubscribing an absent subscription is a no-op`() = runBlocking {
        val user = newUser(null)
        val project = newProject()
        store.setProjectNewIssueSubscription(user, project, true)
        store.setProjectNewIssueSubscription(user, project, true)
        assertTrue(store.isSubscribedToProjectNewIssues(user, project))
        store.setProjectNewIssueSubscription(user, project, false)
        store.setProjectNewIssueSubscription(user, project, false)
        assertFalse(store.isSubscribedToProjectNewIssues(user, project))
    }

    @Test
    fun `subscriptions are isolated per user`() = runBlocking {
        val alice = newUser(null)
        val bob = newUser(null)
        val project = newProject()
        store.setProjectNewIssueSubscription(alice, project, true)
        assertTrue(store.isSubscribedToProjectNewIssues(alice, project))
        assertFalse(store.isSubscribedToProjectNewIssues(bob, project))
    }

    // ── Audience queries: the notification-correctness rules (LNL-109) ───────

    @Test
    fun `a subscriber is in the audience, and the actor is excluded`() = runBlocking {
        val project = newProject()
        val watcher = newUser("watcher@example.com")
        val actor = newUser("actor@example.com")
        store.setProjectNewIssueSubscription(watcher, project, true)
        store.setProjectNewIssueSubscription(actor, project, true)

        val audience = store.audienceForProjectNewIssue(project, actorId = actor)

        assertEquals(listOf(watcher), audience.map { it.userId }, "the actor is excluded")
        assertEquals(listOf("watcher@example.com"), audience.mapNotNull { it.asEmailRecipient()?.email })
    }

    @Test
    fun `an addressless subscriber is in the audience but yields no email recipient`() = runBlocking {
        val project = newProject()
        val addressless = newUser(null)
        store.setProjectNewIssueSubscription(addressless, project, true)

        val audience = store.audienceForProjectNewIssue(project, actorId = null)

        // In-app notification reaches everyone: the addressless subscriber is in the
        // audience (that changed in LNL-109) — but has no e-mail view.
        assertEquals(listOf(addressless), audience.map { it.userId })
        assertNull(audience.single().asEmailRecipient(), "no address → no e-mail recipient")
    }

    @Test
    fun `a null actor excludes nobody`() = runBlocking {
        val project = newProject()
        val watcher = newUser("watcher@example.com")
        store.setProjectNewIssueSubscription(watcher, project, true)

        val audience = store.audienceForProjectNewIssue(project, actorId = null)

        assertEquals(listOf(watcher), audience.map { it.userId })
    }

    // ── The issue pair, including the names-only watcher list ────────────────

    @Test
    fun `an issue watcher is in the update audience and reported by name`() = runBlocking {
        val project = newProject()
        val issue = newPublishedIssue(project)
        val watcher = newUser("iwatch@example.com")
        store.setIssueUpdateSubscription(watcher, issue, true)

        assertTrue(store.isSubscribedToIssueUpdates(watcher, issue))
        assertEquals(
            listOf("iwatch@example.com"),
            store.audienceForIssueUpdate(issue, actorId = null).mapNotNull { it.asEmailRecipient()?.email },
        )
        assertEquals(1, store.watchersForIssue(issue).size)
    }
}
