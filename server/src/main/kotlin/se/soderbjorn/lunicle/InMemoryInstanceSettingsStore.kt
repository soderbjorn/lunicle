/**
 * An in-memory [se.soderbjorn.lunicle.store.InstanceSettingsStore] (LNL-115).
 *
 * The default [BoardDependencies] and [authRoutes] fall back to one of these when
 * no persistent store is supplied, which is the pattern the notifiers use: a test
 * that does not care about instance switches gets the pre-LNL-115 behaviour — the
 * app usable signed out, creation an administrator's — without configuring
 * anything, and a test that *does* care constructs a fresh one and toggles it. It
 * is production-unreachable: [Application.module] always builds the SQLite-backed
 * store, because a require-sign-in switch that forgot itself on every redeploy
 * would be a switch nobody could trust.
 */
package se.soderbjorn.lunicle

import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
import se.soderbjorn.lunicle.store.InstanceSettings
import se.soderbjorn.lunicle.store.InstanceSettingsStore

/** A store that keeps the two switches in a field. Not persistent; see the file preamble. */
class InMemoryInstanceSettingsStore(
    initial: InstanceSettings = InstanceSettings(),
) : InstanceSettingsStore {
    private var settings = initial

    override suspend fun current(): InstanceSettings = settings

    override suspend fun set(key: InstanceSettingKey, isEnabled: Boolean) {
        settings = when (key) {
            InstanceSettingKey.REQUIRE_SIGN_IN -> settings.copy(requireSignIn = isEnabled)
            InstanceSettingKey.ANYONE_CAN_CREATE_PROJECT -> settings.copy(anyoneCanCreateProject = isEnabled)
            InstanceSettingKey.HIDE_DISPLAY_NAME -> settings.copy(hideDisplayName = isEnabled)
        }
    }
}
