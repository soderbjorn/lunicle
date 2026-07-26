/**
 * The behaviour every [SessionStore] implementation must exhibit.
 *
 * The semantics pinned here: [SessionStore.create] mints a distinct token that
 * [SessionStore.lookup] resolves back to the user behind it; a null, unknown or
 * destroyed token resolves to null; [SessionStore.destroy] is idempotent and
 * null-safe; [SessionStore.size] counts stored sessions; and
 * [SessionStore.deleteExpired] sweeps sessions past their lifetime, returns how
 * many went, and leaves a fresh one alone.
 *
 * **What it deliberately does not pin: the expiry window on `lookup`.** The SQLite
 * reference does not itself refuse an aged-but-unswept session (a documented
 * limitation); the Firestore implementation does. That is a difference in one
 * direction only — stricter, never looser — so the contract asserts a *fresh*
 * session resolves and a *swept* one is gone (both true on either backend) and says
 * nothing about the window in between, which is the only place the two diverge.
 *
 * A subclass per backend supplies the store, a way to make a user its [lookup] will
 * resolve, and a hook that advances the store's clock past a session's lifetime so
 * the sweep has something to remove.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

abstract class SessionStoreContract {
    protected abstract val store: SessionStore

    /** A user the store's [SessionStore.lookup] will resolve, returning their id. */
    protected abstract suspend fun newUser(): Long

    /**
     * Advance the store's clock beyond a session's lifetime, so that
     * [SessionStore.deleteExpired] sweeps sessions created before the call. Each
     * backend moves its own injected clock forward by more than its own lifetime.
     */
    protected abstract fun advancePastSessionLifetime()

    @Test
    fun `a created session resolves to the user behind it`() = runBlocking {
        val user = newUser()
        val token = store.create(user)
        assertEquals(user, store.lookup(token)?.id, "the token resolves to its user")
    }

    @Test
    fun `a null token resolves to null`() = runBlocking {
        assertNull(store.lookup(null))
    }

    @Test
    fun `an unknown token resolves to null`() = runBlocking {
        assertNull(store.lookup("not-a-real-session-id"))
    }

    @Test
    fun `each create mints a distinct token`() = runBlocking {
        val user = newUser()
        assertNotEquals(store.create(user), store.create(user), "two sessions for one user do not collide")
    }

    @Test
    fun `destroy forgets a session`() = runBlocking {
        val user = newUser()
        val token = store.create(user)
        store.destroy(token)
        assertNull(store.lookup(token), "a destroyed session no longer resolves")
    }

    @Test
    fun `destroy is null-safe and idempotent`() = runBlocking {
        store.destroy(null)
        val token = store.create(newUser())
        store.destroy(token)
        store.destroy(token)
        assertNull(store.lookup(token), "destroying twice is not an error")
    }

    @Test
    fun `sessions resolve to their own user`() = runBlocking {
        val alice = newUser()
        val bob = newUser()
        val aliceToken = store.create(alice)
        val bobToken = store.create(bob)
        assertEquals(alice, store.lookup(aliceToken)?.id)
        assertEquals(bob, store.lookup(bobToken)?.id)
    }

    @Test
    fun `size counts stored sessions`() = runBlocking {
        val user = newUser()
        store.create(user)
        store.create(user)
        assertEquals(2L, store.size(), "both live sessions are counted")
    }

    @Test
    fun `deleteExpired sweeps sessions past their lifetime and returns the count`() = runBlocking {
        val user = newUser()
        val token = store.create(user)
        advancePastSessionLifetime()

        assertEquals(1L, store.deleteExpired(), "the aged session is swept")
        assertEquals(0L, store.size(), "and no longer counted")
        assertNull(store.lookup(token), "and no longer resolves")
    }

    @Test
    fun `deleteExpired leaves a fresh session alone`() = runBlocking {
        val user = newUser()
        val token = store.create(user)
        assertEquals(0L, store.deleteExpired(), "a session inside its lifetime is not swept")
        assertTrue(store.lookup(token)?.id == user, "and still resolves")
    }
}
