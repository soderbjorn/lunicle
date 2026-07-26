/**
 * The instance-wide switches an administrator sets for the whole deployment: the
 * SQLite reference implementation of
 * [se.soderbjorn.lunicle.store.InstanceSettingsStore] (LNL-115).
 *
 * A thin store over one key-value table. See InstanceSettings.sq for why the two
 * flags live as rows rather than columns, and InstanceSettingKey for the closed set
 * of keys the admin route will ever ask this to store.
 *
 * @see Database
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
import se.soderbjorn.lunicle.db.LunicleDatabase
import se.soderbjorn.lunicle.store.InstanceSettings

/**
 * Reads and writes `instance_settings`.
 *
 * The interface is named in the supertype clause by its fully-qualified name so
 * this class can keep the bare `InstanceSettingsStore` name — the two would
 * otherwise collide, exactly as [UiSettingsStore] does with its interface.
 *
 * @param database the open database.
 */
class InstanceSettingsStore(
    private val database: LunicleDatabase,
) : se.soderbjorn.lunicle.store.InstanceSettingsStore {
    /**
     * Every switch, defaulting the absent ones to off.
     *
     * Reads the whole tiny table in one query and folds it into the snapshot. A
     * value other than "true" — including a row this build has never heard of, or
     * a hand-edited one — reads as off, which is the safe default for both switches
     * (the app stays usable, creation stays an admin's).
     */
    override suspend fun current(): InstanceSettings = withContext(DatabaseDispatcher) {
        val rows = database.instanceSettingsQueries.selectAll().executeAsList()
            .associate { it.key to it.value_ }
        InstanceSettings(
            requireSignIn = rows[InstanceSettingKey.REQUIRE_SIGN_IN.storageKey].isTrue(),
            anyoneCanCreateProject = rows[InstanceSettingKey.ANYONE_CAN_CREATE_PROJECT.storageKey].isTrue(),
            hideDisplayName = rows[InstanceSettingKey.HIDE_DISPLAY_NAME.storageKey].isTrue(),
        )
    }

    /**
     * Set one switch, replacing whatever was there.
     *
     * Idempotent and unconditional, like every preference write here: two admins
     * disagreeing about a switch is one of them being more recent, not a race.
     */
    override suspend fun set(key: InstanceSettingKey, isEnabled: Boolean): Unit =
        withContext(DatabaseDispatcher) {
            database.instanceSettingsQueries.upsert(key = key.storageKey, value_ = isEnabled.toString())
        }

    /** "true" is on; everything else — including a null, an unknown, a typo — is off. */
    private fun String?.isTrue(): Boolean = this == "true"
}
