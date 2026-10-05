/**
 * The behaviour every [ApiTokenStore] implementation must exhibit (LNL-222).
 *
 * The parts a document backend could quietly get wrong:
 *
 *  - **Expiry lives in the lookup.** [ApiTokenStore.authenticate] answers null for an
 *    expired token exactly as for an unknown one, so no caller can forget to check.
 *  - **A token that never expires never does.**
 *  - **Revoke is scoped to the owner.** Somebody else's id revokes nothing — ids are
 *    small integers, and the owner scope is the whole of the route's safety.
 *  - **The scope round-trips**, and a use is recorded.
 *  - **The sweep takes only expired tokens.**
 *
 * A subclass per backend supplies the store, a controllable clock and two user ids;
 * the assertions live here.
 */
package se.soderbjorn.lunicle.store

import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.ApiTokenScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

abstract class ApiTokenStoreContract {
    protected abstract val store: ApiTokenStore

    /** The store's clock, now. */
    protected abstract val now: Long

    /** Move the store's clock forward by [millis]. */
    protected abstract fun advanceTime(millis: Long)

    /** A user a token can belong to. Distinct on every call. */
    protected abstract suspend fun userId(): Long

    private val dayMillis = 24L * 60 * 60 * 1000

    @Test
    fun `a token authenticates by its hash and carries what it was made with`() = runBlocking<Unit> {
        val owner = userId()
        val made = store.create(owner, "CI", "hash-a", "lnl_pat_aaaaaa", ApiTokenScope.WRITE, expiresAt = null)

        val found = assertNotNull(store.authenticate("hash-a"))
        assertEquals(made.id, found.id)
        assertEquals(owner, found.userId)
        assertEquals("CI", found.name)
        assertEquals("lnl_pat_aaaaaa", found.prefix)
        assertEquals(ApiTokenScope.WRITE, found.scope)
        assertNull(store.authenticate("hash-unknown"), "An unknown hash authenticated.")
    }

    @Test
    fun `a use is recorded`() = runBlocking<Unit> {
        val owner = userId()
        store.create(owner, "Laptop", "hash-b", "lnl_pat_bbbbbb", ApiTokenScope.READ, expiresAt = null)
        assertNull(store.forUser(owner).single().lastUsedAt, "A token nobody used says it was used.")

        advanceTime(1_000)
        store.authenticate("hash-b")
        assertEquals(now, store.forUser(owner).single().lastUsedAt)
    }

    @Test
    fun `an expired token is refused exactly as an unknown one`() = runBlocking<Unit> {
        val owner = userId()
        store.create(owner, "Short", "hash-c", "lnl_pat_cccccc", ApiTokenScope.READ, expiresAt = now + dayMillis)
        store.create(owner, "Forever", "hash-d", "lnl_pat_dddddd", ApiTokenScope.READ, expiresAt = null)

        assertNotNull(store.authenticate("hash-c"))
        advanceTime(dayMillis)
        assertNull(store.authenticate("hash-c"), "A token authenticated on the instant it expired.")
        advanceTime(3_650 * dayMillis)
        assertNotNull(store.authenticate("hash-d"), "A token that never expires expired.")
    }

    @Test
    fun `revoke is scoped to the owner`() = runBlocking<Unit> {
        val owner = userId()
        val stranger = userId()
        val made = store.create(owner, "Mine", "hash-e", "lnl_pat_eeeeee", ApiTokenScope.WRITE, expiresAt = null)

        store.revoke(stranger, made.id)
        assertNotNull(store.authenticate("hash-e"), "Somebody else's id revoked a token that is not theirs.")

        store.revoke(owner, made.id)
        assertNull(store.authenticate("hash-e"), "Revoking did not stop the token.")
        assertEquals(emptyList(), store.forUser(owner))
    }

    @Test
    fun `a person's list is theirs, oldest first`() = runBlocking<Unit> {
        val owner = userId()
        val other = userId()
        store.create(owner, "First", "hash-f", "lnl_pat_ffffff", ApiTokenScope.READ, expiresAt = null)
        advanceTime(10)
        store.create(other, "Not yours", "hash-g", "lnl_pat_gggggg", ApiTokenScope.READ, expiresAt = null)
        advanceTime(10)
        store.create(owner, "Second", "hash-h", "lnl_pat_hhhhhh", ApiTokenScope.WRITE, expiresAt = now + dayMillis)

        assertEquals(listOf("First", "Second"), store.forUser(owner).map { it.name })
        assertEquals(now + dayMillis, store.forUser(owner).last().expiresAt)
    }

    @Test
    fun `the sweep takes expired tokens and nothing else`() = runBlocking<Unit> {
        val owner = userId()
        store.create(owner, "Old", "hash-i", "lnl_pat_iiiiii", ApiTokenScope.READ, expiresAt = now + dayMillis)
        store.create(owner, "Live", "hash-j", "lnl_pat_jjjjjj", ApiTokenScope.READ, expiresAt = now + 30 * dayMillis)
        store.create(owner, "Forever", "hash-k", "lnl_pat_kkkkkk", ApiTokenScope.READ, expiresAt = null)

        advanceTime(2 * dayMillis)
        assertEquals(1L, store.deleteExpired())
        assertEquals(listOf("Live", "Forever"), store.forUser(owner).map { it.name })
    }
}
