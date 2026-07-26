/**
 * The behaviour every [OAuthCodeStore] implementation must exhibit.
 *
 * The parity-critical thing here is single-use: [OAuthCodeStore.consume] returns a
 * code's contents exactly once and null forever after, because the consume *is*
 * the delete. Getting this subtly wrong between a relational and a document
 * backend — a read-then-delete with a window between the halves — would let one
 * consent mint two token families, which is exactly the shape of an interception
 * attack succeeding alongside the legitimate exchange. Also that a wrong-prefix or
 * expired code consumes as null, and that [OAuthCodeStore.deleteExpired] is the
 * sweep behind the expiry the lookup already enforces.
 *
 * A subclass per backend supplies the store, a controllable clock, and the client
 * and user a code points at (both are foreign keys); the assertions live here.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

abstract class OAuthCodeStoreContract {
    protected abstract val store: OAuthCodeStore

    /** Move the store's clock forward by [millis]. */
    protected abstract fun advanceTime(millis: Long)

    /** A registered client id a code can reference. */
    protected abstract suspend fun clientId(): String

    /** A seeded user id a code can reference. */
    protected abstract suspend fun userId(): Long

    /** Comfortably past [se.soderbjorn.lunicle] `AUTH_CODE_LIFETIME_MILLIS` (2 min). */
    private val threeMinutesMillis = 3L * 60 * 1000

    private suspend fun create(user: Long = 0L): String = store.create(
        userId = if (user != 0L) user else userId(),
        clientId = clientId(),
        redirectUri = "http://127.0.0.1:9/cb",
        codeChallenge = "challenge-abc",
        resource = "https://example.test/mcp",
        scope = "mcp",
    )

    @Test
    fun `a code carries its request through, and consume returns it exactly once`() = runBlocking {
        val user = userId()
        val code = create(user)

        val redeemed = assertNotNull(store.consume(code))
        assertEquals(user, redeemed.userId)
        assertEquals("http://127.0.0.1:9/cb", redeemed.redirectUri)
        assertEquals("challenge-abc", redeemed.codeChallenge)
        assertEquals("https://example.test/mcp", redeemed.resource)
        assertEquals("mcp", redeemed.scope)

        // The second presentation of the same code finds nothing: the consume was
        // the delete.
        assertNull(store.consume(code))
    }

    @Test
    fun `consume is null for a wrong-prefix code`() = runBlocking {
        assertNull(store.consume("not-a-code"))
    }

    @Test
    fun `an expired code cannot be consumed`() = runBlocking {
        val code = create()
        advanceTime(threeMinutesMillis)
        assertNull(store.consume(code))
    }

    @Test
    fun `deleteExpired sweeps an expired code`() = runBlocking {
        create()
        advanceTime(threeMinutesMillis)
        assertEquals(1L, store.deleteExpired())
    }
}
