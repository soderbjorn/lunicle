/**
 * The behaviour every [InstanceSettingsStore] implementation must exhibit (LNL-115,
 * extended by LNL-192), specified once and run against each backend.
 *
 * The same linchpin [UiSettingsStoreContract] describes: SQLite and Firestore run
 * these same assertions, so the two cannot quietly diverge on the things easy to get
 * subtly different — what an unset setting reads as, whether one write disturbs
 * another, and whether a second write replaces or appends.
 *
 * Simpler than the UiSettings contract in one way: there is no user to mint, because
 * these belong to the instance and not to an account. Harder in one: [admission] is
 * not a boolean, and its unrecognised-value behaviour is a rule both backends have to
 * reach the same way.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AdmissionPolicy
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey

abstract class InstanceSettingsStoreContract {
    /** The store under test, over a freshly-prepared backend. */
    protected abstract val store: InstanceSettingsStore

    /**
     * Every permission off, and anybody who can sign in admitted.
     *
     * The whole-object comparison is the point: a field added to [InstanceSettings]
     * without a default, or with the wrong one, fails here rather than somewhere
     * downstream where it would read as a permission somebody was granted.
     */
    @Test
    fun `everything defaults to the closed answer, and admission to anyone`() = runBlocking {
        assertEquals(InstanceSettings(), store.current())
    }

    @Test
    fun `a set switch reads back on`() = runBlocking {
        store.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)
        assertTrue(store.current().allowPublicProjects)
    }

    @Test
    fun `the hide-display-name switch reads back on`() = runBlocking {
        store.set(InstanceSettingKey.HIDE_DISPLAY_NAME, true)
        assertTrue(store.current().hideDisplayName)
    }

    /**
     * Every switch, one at a time, each disturbing nothing else.
     *
     * Written as a loop over the enum rather than as one hand-picked pair, because
     * the failure this guards is a store that folds a *new* key onto an existing
     * field — which a fixed pair would never notice.
     */
    @Test
    fun `the switches are independent`() = runBlocking {
        for (key in InstanceSettingKey.entries) {
            store.set(key, true)
            val settings = store.current()
            val on = InstanceSettingKey.entries.filter { it.isOn(settings) }
            assertEquals(listOf(key), on, "Setting ${key.storageKey} did not set exactly one switch.")
            store.set(key, false)
            assertTrue(
                InstanceSettingKey.entries.none { it.isOn(store.current()) },
                "Turning ${key.storageKey} back off left something on.",
            )
        }
    }

    @Test
    fun `a second set replaces the value, last write wins`() = runBlocking {
        store.set(InstanceSettingKey.STAFF_MAY_USE_AGENTS, true)
        store.set(InstanceSettingKey.STAFF_MAY_USE_AGENTS, false)
        assertFalse(store.current().staffMayUseAgents)
    }

    // ── Admission (LNL-192) ─────────────────────────────────────────────────

    @Test
    fun `every admission policy round-trips`() = runBlocking {
        for (policy in AdmissionPolicy.entries) {
            store.setAdmissionPolicy(policy)
            assertEquals(policy, store.current().admission, "${policy.key} did not read back.")
        }
    }

    /**
     * Setting admission is not setting a switch, and vice versa.
     *
     * Both live in the same key-value space on both backends — a row in one table on
     * SQLite, an entry in one map on Firestore — which is exactly the arrangement in
     * which a key collision would be invisible until somebody flipped a switch and
     * lost their admission policy.
     */
    @Test
    fun `admission and the switches do not disturb one another`() = runBlocking {
        store.setAdmissionPolicy(AdmissionPolicy.STAFF_DOMAIN_ONLY)
        store.set(InstanceSettingKey.MEMBER_MAY_CREATE_PROJECTS, true)
        val settings = store.current()
        assertEquals(AdmissionPolicy.STAFF_DOMAIN_ONLY, settings.admission, "A switch write cost the policy.")
        assertTrue(settings.memberMayCreateProjects, "The policy write cost a switch.")
    }
}

/** Whether this switch reads as on in [settings]. Test-local, so the contract can sweep them all. */
private fun InstanceSettingKey.isOn(settings: InstanceSettings): Boolean = when (this) {
    InstanceSettingKey.ALLOW_PUBLIC_PROJECTS -> settings.allowPublicProjects
    InstanceSettingKey.STAFF_MAY_CREATE_PROJECTS -> settings.staffMayCreateProjects
    InstanceSettingKey.MEMBER_MAY_CREATE_PROJECTS -> settings.memberMayCreateProjects
    InstanceSettingKey.STAFF_MAY_USE_AGENTS -> settings.staffMayUseAgents
    InstanceSettingKey.MEMBER_MAY_USE_AGENTS -> settings.memberMayUseAgents
    InstanceSettingKey.HIDE_DISPLAY_NAME -> settings.hideDisplayName
}
