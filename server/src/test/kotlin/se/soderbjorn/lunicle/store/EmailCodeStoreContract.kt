/**
 * The behaviour every [EmailCodeStore] implementation must exhibit.
 *
 * This pins the *persistence* half of an email code, the half that is backend-specific:
 * a stored code becomes a live pending row, redemption is single-use, a wrong guess
 * charges the attempt cap until it deletes the row, expiry hides a row before the sweep
 * runs, issuing supersedes the outstanding code for both the address and (when there is
 * one) the account, and the two per-user accessors LNL-71 leans on behave. The code
 * generation, the hashing and the mail are the *service's* and are tested with it —
 * they never cross this seam.
 *
 * A subclass per backend supplies the store, a controllable clock (the store filters
 * expiry by it), the clock's current value (so a test can compute an absolute
 * `expiresAt`), and a user id a code may reference. The assertions live here.
 *
 * ── The store treats the hash opaquely, so this contract does ───────────────
 *
 * A code's "hash" is a field the store round-trips and never interprets, so this
 * contract uses a plain marker string in its place — [hash] — and "the right code" is
 * simply the one whose marker matches. That keeps the contract free of crypto it is not
 * testing, while still exercising the exact compare-then-decide flow the service runs.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

abstract class EmailCodeStoreContract {
    protected abstract val store: EmailCodeStore

    /** Move the store's clock forward by [millis]. */
    protected abstract fun advanceTime(millis: Long)

    /** The store's clock right now, so a test can compute an absolute expiry from it. */
    protected abstract fun currentTime(): Long

    /** A user id a code may reference (a real seeded row for SQLite, a synthetic id otherwise). */
    protected abstract suspend fun userId(): Long

    private val signIn = "SIGN_IN"
    private val emailChange = "EMAIL_CHANGE"
    private val lifetimeMillis = 15L * 60 * 1000
    private val pastLifetimeMillis = 16L * 60 * 1000

    /** The attempt cap the service applies; mirrored here to drive [redeem]. */
    private val attemptLimit = 5L

    /** The opaque stored form of [code] — a marker, not real crypto; see the class doc. */
    private fun hash(code: String): String = "H:$code"

    /** Store a live code, expiring [lifetimeMillis] from the store's current clock. */
    private suspend fun put(
        address: String,
        purpose: String,
        code: String,
        user: Long? = null,
        lifetime: Long = lifetimeMillis,
    ) = store.store(
        address = address,
        purpose = purpose,
        codeHash = hash(code),
        userId = user,
        createdAt = currentTime(),
        expiresAt = currentTime() + lifetime,
    )

    /**
     * Spend [code] against the live code for ([address], [purpose]), running the exact
     * compare-then-decide the service's `redeem` does: wrong code charges an attempt (or,
     * at the cap, deletes the row); the right code deletes it and reports success.
     *
     * @return true only on a correct, live, within-cap code.
     */
    private suspend fun redeem(address: String, purpose: String, code: String): Boolean =
        store.consumePending(address, purpose) { pending ->
            when {
                pending == null -> PendingCodeDecision(false, PendingCodeAction.Leave)
                pending.codeHash != hash(code) -> {
                    val attempts = pending.attempts + 1
                    if (attempts >= attemptLimit) {
                        PendingCodeDecision(false, PendingCodeAction.Delete)
                    } else {
                        PendingCodeDecision(false, PendingCodeAction.RecordAttempt)
                    }
                }
                else -> PendingCodeDecision(true, PendingCodeAction.Delete)
            }
        }

    @Test
    fun `a stored code redeems once, and never again`() = runBlocking {
        put("alice@example.com", signIn, "123456")
        assertEquals(1L, store.size())

        assertTrue(redeem("alice@example.com", signIn, "123456"), "The right code did not redeem.")
        // Single use: the redemption was the delete.
        assertFalse(redeem("alice@example.com", signIn, "123456"), "A redeemed code redeemed a second time.")
        assertEquals(0L, store.size())
    }

    @Test
    fun `a wrong guess charges an attempt but leaves the code redeemable`() = runBlocking {
        put("alice@example.com", signIn, "123456")

        assertFalse(redeem("alice@example.com", signIn, "000000"))
        assertEquals(1L, store.size(), "A single wrong guess deleted the code.")
        // The row is still there and the right code still works.
        assertTrue(redeem("alice@example.com", signIn, "123456"))
    }

    @Test
    fun `the attempt cap deletes the row on the fifth wrong guess`() = runBlocking {
        put("alice@example.com", signIn, "123456")

        // Four wrong guesses are survived...
        repeat(4) { assertFalse(redeem("alice@example.com", signIn, "000000")) }
        assertEquals(1L, store.size(), "The code died before the cap.")

        // ...the fifth trips the cap and deletes the row.
        assertFalse(redeem("alice@example.com", signIn, "000000"))
        assertEquals(0L, store.size(), "The attempt cap did not delete the row.")
        // And now even the right code finds nothing — exhausted and never-existed are one fact.
        assertFalse(redeem("alice@example.com", signIn, "123456"))
    }

    @Test
    fun `an expired code is invisible to redemption and to the pending lookup`() = runBlocking {
        val user = userId()
        put("alice@example.com", emailChange, "123456", user = user)
        advanceTime(pastLifetimeMillis)

        assertFalse(redeem("alice@example.com", emailChange, "123456"), "An expired code redeemed.")
        assertNull(store.pendingAddressFor(user, emailChange), "An expired code still showed as pending.")
        // Still on disk until the sweep — expiry is a read filter, not a delete.
        assertEquals(1L, store.size())
    }

    @Test
    fun `deleteExpired sweeps the expired and spares the live`() = runBlocking {
        // A short-lived code and a long-lived one, then advance past only the short one.
        put("stale@example.com", signIn, "111111", lifetime = lifetimeMillis)
        put("fresh@example.com", signIn, "222222", lifetime = 10L * lifetimeMillis)
        advanceTime(pastLifetimeMillis)

        assertEquals(1L, store.deleteExpired(), "The sweep removed the wrong number of rows.")
        assertEquals(1L, store.size())
        // The live one still redeems.
        assertTrue(redeem("fresh@example.com", signIn, "222222"))
    }

    @Test
    fun `pendingAddressFor answers per user and per purpose`() = runBlocking {
        val user = userId()
        put("new@example.com", emailChange, "123456", user = user)

        assertEquals("new@example.com", store.pendingAddressFor(user, emailChange))
        // A different purpose the same user has no code under.
        assertNull(store.pendingAddressFor(user, signIn))
    }

    @Test
    fun `cancelFor drops the user's pending code`() = runBlocking {
        val user = userId()
        put("new@example.com", emailChange, "123456", user = user)

        store.cancelFor(user, emailChange)
        assertNull(store.pendingAddressFor(user, emailChange))
        assertEquals(0L, store.size())
        // Idempotent: cancelling again is a no-op, not a failure.
        store.cancelFor(user, emailChange)
    }

    @Test
    fun `issuing again for an address supersedes the outstanding code`() = runBlocking {
        put("alice@example.com", signIn, "111111")
        put("alice@example.com", signIn, "222222")

        assertEquals(1L, store.size(), "Two live codes coexist for one address and purpose.")
        // The old code is gone; the new one redeems.
        assertTrue(redeem("alice@example.com", signIn, "222222"))
    }

    @Test
    fun `issuing again for a user supersedes the code at their old address`() = runBlocking {
        val user = userId()
        put("first@example.com", emailChange, "111111", user = user)
        put("second@example.com", emailChange, "222222", user = user)

        assertEquals(1L, store.size(), "A user had two pending address changes at once.")
        assertEquals("second@example.com", store.pendingAddressFor(user, emailChange))
    }

    @Test
    fun `two purposes for one address coexist`() = runBlocking {
        val user = userId()
        put("alice@example.com", signIn, "111111")
        put("alice@example.com", emailChange, "222222", user = user)

        assertEquals(2L, store.size(), "Different purposes for one address did not coexist.")
        assertTrue(redeem("alice@example.com", signIn, "111111"))
        assertTrue(redeem("alice@example.com", emailChange, "222222"))
    }
}
