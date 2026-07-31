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
 * Since owner impersonation stopped being a costume, one more: a session may carry
 * a **probe label** saying it was minted without anybody proving the identity. The
 * contract pins that the label round-trips through [SessionStore.probeIdFor],
 * defaults to absent, goes when the session does, and that
 * [SessionStore.deleteProbeSessions] takes every labelled session and no others —
 * the sweep that makes turning the feature off end every impersonation in flight.
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
import kotlin.test.assertNotNull
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

    // ── The probe label: a session minted without proof of identity ──────────

    @Test
    fun `an ordinary session carries no probe label`() = runBlocking {
        val token = store.create(newUser())
        assertNull(store.probeIdFor(token), "a session nobody impersonated into is unlabelled")
    }

    @Test
    fun `a probe label round-trips`() = runBlocking {
        val user = newUser()
        val token = store.create(user, probeId = "grant-abc")
        assertEquals("grant-abc", store.probeIdFor(token))
        // ...and the session is otherwise entirely ordinary, which is the point of
        // the design: everything downstream of the cookie is the same code path.
        assertEquals(user, store.lookup(token)?.id, "a probe session resolves like any other")
    }

    @Test
    fun `an unknown or null token has no probe label`() = runBlocking {
        assertNull(store.probeIdFor(null))
        assertNull(store.probeIdFor("not-a-real-session-id"))
    }

    @Test
    fun `a destroyed probe session leaves no label behind`() = runBlocking {
        val token = store.create(newUser(), probeId = "grant-abc")
        store.destroy(token)
        assertNull(store.probeIdFor(token), "destroy takes the label with the session")
    }

    /**
     * The sweep behind the off-switch: turning owner impersonation off restarts the
     * process, and this is what the new one runs. It has to take every labelled
     * session and leave every ordinary one, because the alternative is a person left
     * signed in as somebody they were only wearing.
     */
    @Test
    fun `deleteProbeSessions sweeps every labelled session and no others`() = runBlocking {
        val owner = newUser()
        val target = newUser()
        val ordinary = store.create(owner)
        val firstProbe = store.create(target, probeId = "grant-abc")
        val secondProbe = store.create(target, probeId = "grant-def")

        assertEquals(2L, store.deleteProbeSessions(), "both probe sessions went")
        assertNull(store.lookup(firstProbe))
        assertNull(store.lookup(secondProbe))
        assertEquals(owner, store.lookup(ordinary)?.id, "the ordinary session is untouched")
    }

    @Test
    fun `deleteProbeSessions is a no-op when nobody has been impersonating`(): Unit = runBlocking {
        val token = store.create(newUser())
        assertEquals(0L, store.deleteProbeSessions(), "nothing to sweep on an ordinary instance")
        assertNotNull(store.lookup(token), "and the sweep is harmless where it finds nothing")
    }
}
