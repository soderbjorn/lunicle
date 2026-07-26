/**
 * The behaviour every [InstanceSettingsStore] implementation must exhibit (LNL-115),
 * specified once and run against each backend.
 *
 * The same linchpin [UiSettingsStoreContract] describes: SQLite today and Firestore
 * later run these same assertions, so the two cannot quietly diverge on the things
 * easy to get subtly different — what an unset switch reads as, whether one switch's
 * write disturbs the other, and whether a second write replaces or appends.
 *
 * Simpler than the UiSettings contract in one way: there is no user to mint, because
 * these switches belong to the instance, not to an account.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey

abstract class InstanceSettingsStoreContract {
    /** The store under test, over a freshly-prepared backend. */
    protected abstract val store: InstanceSettingsStore

    @Test
    fun `every switch defaults to off when nothing has been stored`() = runBlocking {
        assertEquals(
            InstanceSettings(requireSignIn = false, anyoneCanCreateProject = false, hideDisplayName = false),
            store.current(),
        )
    }

    @Test
    fun `a set switch reads back on`() = runBlocking {
        store.set(InstanceSettingKey.REQUIRE_SIGN_IN, true)
        assertTrue(store.current().requireSignIn)
    }

    @Test
    fun `the hide-display-name switch reads back on`() = runBlocking {
        store.set(InstanceSettingKey.HIDE_DISPLAY_NAME, true)
        assertTrue(store.current().hideDisplayName)
    }

    @Test
    fun `the switches are independent`() = runBlocking {
        store.set(InstanceSettingKey.ANYONE_CAN_CREATE_PROJECT, true)
        val settings = store.current()
        assertTrue(settings.anyoneCanCreateProject, "The switch that was set is off.")
        assertFalse(settings.requireSignIn, "Setting one switch turned another on.")
        assertFalse(settings.hideDisplayName, "Setting one switch turned another on.")
    }

    @Test
    fun `a second set replaces the value, last write wins`() = runBlocking {
        store.set(InstanceSettingKey.REQUIRE_SIGN_IN, true)
        store.set(InstanceSettingKey.REQUIRE_SIGN_IN, false)
        assertFalse(store.current().requireSignIn)
    }
}
