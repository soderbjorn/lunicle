/**
 * The behaviour every [OAuthClientStore] implementation must exhibit.
 *
 * The parity-critical things here are the ones easy to get subtly different
 * between a relational and a document backend: that a registration round-trips
 * intact (name, redirect URIs, grant types), that a wrong-prefix id is refused
 * before storage is touched, that [OAuthClientStore.isRegisteredRedirectUri] is
 * *exact* — the one check between this server and being an open redirector, so no
 * normalisation, no prefix, no port games — and that [OAuthClientStore.sweepStale]
 * removes an old, untouched, token-less registration while sparing a fresh one.
 *
 * A subclass per backend supplies the store and a controllable clock; the
 * assertions live here.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

abstract class OAuthClientStoreContract {
    protected abstract val store: OAuthClientStore

    /** Move the store's clock forward by [millis]. */
    protected abstract fun advanceTime(millis: Long)

    /** Comfortably past [se.soderbjorn.lunicle] `STALE_CLIENT_AGE_MILLIS` (7 days). */
    private val eightDaysMillis = 8L * 24 * 60 * 60 * 1000

    @Test
    fun `a registration round-trips through find`() = runBlocking {
        val registered = store.register(
            clientName = "Claude Code",
            redirectUris = listOf("http://127.0.0.1:41111/callback"),
            grantTypes = listOf("authorization_code", "refresh_token"),
        )
        val found = assertNotNull(store.find(registered.clientId))
        assertEquals("Claude Code", found.clientName)
        assertEquals(listOf("http://127.0.0.1:41111/callback"), found.redirectUris)
        assertEquals(listOf("authorization_code", "refresh_token"), found.grantTypes)
    }

    @Test
    fun `find is null for an unknown or wrong-prefix id`() = runBlocking {
        assertNull(store.find("lun_client_deadbeef"))
        assertNull(store.find("not-even-a-client-id"))
    }

    @Test
    fun `isRegisteredRedirectUri is exact, and false for a callback that was not registered`() = runBlocking {
        val client = store.register("Agent", listOf("http://127.0.0.1:5000/cb"), listOf("authorization_code"))
        assertTrue(store.isRegisteredRedirectUri(client.clientId, "http://127.0.0.1:5000/cb"))
        // A different port is a different callback: no port-insensitivity.
        assertFalse(store.isRegisteredRedirectUri(client.clientId, "http://127.0.0.1:5001/cb"))
        assertFalse(store.isRegisteredRedirectUri(client.clientId, "http://127.0.0.1:5000/cb/"))
    }

    @Test
    fun `sweepStale removes an old token-less client and spares a fresh one`() = runBlocking {
        val stale = store.register("Stale", listOf("http://127.0.0.1:1/cb"), listOf("authorization_code"))
        advanceTime(eightDaysMillis)
        val fresh = store.register("Fresh", listOf("http://127.0.0.1:2/cb"), listOf("authorization_code"))

        assertEquals(1L, store.sweepStale())

        assertNull(store.find(stale.clientId))
        assertNotNull(store.find(fresh.clientId))
        assertEquals(1L, store.size())
    }

    @Test
    fun `touching a client keeps it out of the stale sweep`() = runBlocking {
        val client = store.register("Busy", listOf("http://127.0.0.1:3/cb"), listOf("authorization_code"))
        advanceTime(eightDaysMillis)
        // A client touched at /authorize is in use, whatever its created_at says.
        store.touch(client.clientId)

        assertNotNull(store.find(client.clientId))
        assertEquals(0L, store.sweepStale())
    }
}
