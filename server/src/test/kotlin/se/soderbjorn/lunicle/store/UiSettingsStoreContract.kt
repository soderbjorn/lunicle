/**
 * The behaviour every [UiSettingsStore] implementation must exhibit, specified
 * once and run against each backend.
 *
 * This is the linchpin the ticket describes: the same assertions run against
 * SQLite today and Firestore later, so the two backends cannot quietly diverge on
 * the things that are easy to get subtly different — what an empty read returns,
 * whether a second write replaces or appends, and whether one user's settings can
 * leak into another's.
 *
 * A subclass per backend supplies the store and a way to mint a user id valid in
 * that backend; the tests live here.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

abstract class UiSettingsStoreContract {
    /** The store under test, over a freshly-prepared backend. */
    protected abstract val store: UiSettingsStore

    /**
     * A fresh, distinct user id that exists in the backend under test.
     *
     * Abstract because "a user exists" is backend-specific: SQLite needs a row in
     * `users` (the FK is enforced), a document backend needs whatever it needs.
     * The contract only cares that the id is real and unlike the last one.
     */
    protected abstract suspend fun newUser(): Long

    @Test
    fun `forUser is empty when nothing has been stored`() = runBlocking {
        assertEquals(emptyMap(), store.forUser(newUser()))
    }

    @Test
    fun `a stored value reads back under its key`() = runBlocking {
        val user = newUser()
        store.put(user, "darkness.theme.v2.selection", "dark")
        assertEquals(mapOf("darkness.theme.v2.selection" to "dark"), store.forUser(user))
    }

    @Test
    fun `forUser returns every key stored for that user`() = runBlocking {
        val user = newUser()
        store.put(user, "selection", "auto")
        store.put(user, "dark.theme", "midnight")
        assertEquals(
            mapOf("selection" to "auto", "dark.theme" to "midnight"),
            store.forUser(user),
        )
    }

    @Test
    fun `a second put on the same key replaces the value, last write wins`() = runBlocking {
        val user = newUser()
        store.put(user, "selection", "light")
        store.put(user, "selection", "dark")
        assertEquals(mapOf("selection" to "dark"), store.forUser(user))
    }

    @Test
    fun `settings are isolated per user`() = runBlocking {
        val alice = newUser()
        val bob = newUser()
        store.put(alice, "selection", "dark")
        store.put(bob, "selection", "light")
        assertEquals(mapOf("selection" to "dark"), store.forUser(alice))
        assertEquals(mapOf("selection" to "light"), store.forUser(bob))
    }
}
