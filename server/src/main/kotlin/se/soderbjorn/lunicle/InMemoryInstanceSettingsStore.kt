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

import se.soderbjorn.lunicle.clientserver.AdmissionPolicy
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
import se.soderbjorn.lunicle.store.InstanceSettings
import se.soderbjorn.lunicle.store.InstanceSettingsStore

/** A store that keeps the settings in a field. Not persistent; see the file preamble. */
class InMemoryInstanceSettingsStore(
    initial: InstanceSettings = InstanceSettings(),
) : InstanceSettingsStore {
    private var settings = initial

    override suspend fun current(): InstanceSettings = settings

    override suspend fun set(key: InstanceSettingKey, isEnabled: Boolean) {
        settings = when (key) {
            InstanceSettingKey.ALLOW_PUBLIC_PROJECTS -> settings.copy(allowPublicProjects = isEnabled)
            InstanceSettingKey.STAFF_MAY_CREATE_PROJECTS -> settings.copy(staffMayCreateProjects = isEnabled)
            InstanceSettingKey.MEMBER_MAY_CREATE_PROJECTS -> settings.copy(memberMayCreateProjects = isEnabled)
            InstanceSettingKey.STAFF_MAY_USE_AGENTS -> settings.copy(staffMayUseAgents = isEnabled)
            InstanceSettingKey.MEMBER_MAY_USE_AGENTS -> settings.copy(memberMayUseAgents = isEnabled)
            InstanceSettingKey.HIDE_DISPLAY_NAME -> settings.copy(hideDisplayName = isEnabled)
        }
    }

    override suspend fun setOwnerUserId(userId: Long?) {
        settings = settings.copy(ownerUserId = userId)
    }

    override suspend fun setAdmissionPolicy(policy: AdmissionPolicy) {
        // Stored, as in both real stores: a write puts a row there, and what makes
        // the boot's settle a no-op afterwards is that the row exists rather than
        // what it says. See InstanceSettings.isAdmissionStored.
        settings = settings.copy(admission = policy, isAdmissionStored = true)
    }

    /** One audience's row for future projects; null removes it, as in both real stores. */
    override suspend fun setNewProjectAudience(audience: Audience, role: ProjectRole?) {
        settings = settings.copy(
            newProjectAudiences = settings.newProjectAudiences.toMutableMap().apply {
                if (role == null) remove(audience) else put(audience, role)
            },
        )
    }
}
