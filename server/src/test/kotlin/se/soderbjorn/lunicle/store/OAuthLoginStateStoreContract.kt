/**
 * The behaviour every [OAuthLoginStateStore] implementation must exhibit.
 *
 * The parity-critical thing here is that expiry is enforced by the *lookup*, not
 * by the caller: a [OAuthLoginStateStore.find] past a row's expiry returns null
 * even though the row is still on disk, and [OAuthLoginStateStore.deleteExpired]
 * is only the sweep behind that. Also that the request round-trips intact across
 * the consent click, that a null id is tolerated, and that
 * [OAuthLoginStateStore.delete] is idempotent.
 *
 * A subclass per backend supplies the store, a controllable clock, and the client
 * and user a login state points at (both are foreign keys); the assertions live
 * here.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

abstract class OAuthLoginStateStoreContract {
    protected abstract val store: OAuthLoginStateStore

    /** Move the store's clock forward by [millis]. */
    protected abstract fun advanceTime(millis: Long)

    /** A registered client id a login state can reference. */
    protected abstract suspend fun clientId(): String

    /** A seeded user id a login state can reference. */
    protected abstract suspend fun userId(): Long

    /** Comfortably past [se.soderbjorn.lunicle] `LOGIN_STATE_LIFETIME_MILLIS` (10 min). */
    private val elevenMinutesMillis = 11L * 60 * 1000

    private suspend fun create() = store.create(
        clientId = clientId(),
        redirectUri = "http://127.0.0.1:9/cb",
        codeChallenge = "challenge-abc",
        resource = "https://example.test/mcp",
        clientState = "the-agents-own-state",
        scope = "mcp",
        userId = userId(),
    )

    @Test
    fun `a pending authorization round-trips through find`() = runBlocking {
        val user = userId()
        val id = store.create(
            clientId = clientId(),
            redirectUri = "http://127.0.0.1:9/cb",
            codeChallenge = "challenge-abc",
            resource = "https://example.test/mcp",
            clientState = "the-agents-own-state",
            scope = "mcp",
            userId = user,
        )
        val found = assertNotNull(store.find(id))
        assertEquals(id, found.id)
        assertEquals("http://127.0.0.1:9/cb", found.redirectUri)
        assertEquals("challenge-abc", found.codeChallenge)
        assertEquals("https://example.test/mcp", found.resource)
        assertEquals("the-agents-own-state", found.clientState)
        assertEquals("mcp", found.scope)
        assertEquals(user, found.userId)
    }

    @Test
    fun `find tolerates a null id and an unknown id`() = runBlocking {
        assertNull(store.find(null))
        assertNull(store.find("ls_unknown"))
    }

    @Test
    fun `delete removes the row and is idempotent`() = runBlocking {
        val id = create()
        store.delete(id)
        assertNull(store.find(id))
        // A second delete of the same id is a no-op, not a failure.
        store.delete(id)
        assertNull(store.find(id))
    }

    @Test
    fun `an expired login state is not found, and deleteExpired sweeps it while sparing a live one`() = runBlocking {
        val old = create()
        advanceTime(elevenMinutesMillis)
        val live = create()

        // The lookup refuses the expired row before any sweep runs.
        assertNull(store.find(old))
        assertNotNull(store.find(live))

        assertEquals(1L, store.deleteExpired())
        assertEquals(live, store.find(live)?.id)
    }
}
