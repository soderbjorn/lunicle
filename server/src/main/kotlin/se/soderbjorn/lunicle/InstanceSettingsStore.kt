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
import se.soderbjorn.lunicle.clientserver.AdmissionPolicy
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
import se.soderbjorn.lunicle.db.LunicleDatabase
import se.soderbjorn.lunicle.store.InstanceSettings
import se.soderbjorn.lunicle.store.newProjectAudienceKey

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
            hideDisplayName = rows[InstanceSettingKey.HIDE_DISPLAY_NAME.storageKey].isTrue(),
            // Not a switch either: three values, stored as the policy's key. An
            // unrecognised value — a hand-edited row, or one written by a build that
            // knew a fourth policy — reads as the default rather than as "admit
            // nobody", because a deployment that cannot parse its own admission
            // setting must not lock every future account out on the strength of a
            // typo. The stored value is left alone, so the row survives a rollback.
            admission = AdmissionPolicy.byKey(rows[ADMISSION_KEY]) ?: AdmissionPolicy.ANYONE,
            allowPublicProjects = rows[InstanceSettingKey.ALLOW_PUBLIC_PROJECTS.storageKey].isTrue(),
            staffMayCreateProjects = rows[InstanceSettingKey.STAFF_MAY_CREATE_PROJECTS.storageKey].isTrue(),
            memberMayCreateProjects = rows[InstanceSettingKey.MEMBER_MAY_CREATE_PROJECTS.storageKey].isTrue(),
            staffMayUseAgents = rows[InstanceSettingKey.STAFF_MAY_USE_AGENTS.storageKey].isTrue(),
            memberMayUseAgents = rows[InstanceSettingKey.MEMBER_MAY_USE_AGENTS.storageKey].isTrue(),
            // Not a switch, so not read through isTrue(): the stored form is the id
            // as text. A value that is not a number — a hand-edited row, a row from
            // a build that meant something else by this key — reads as "nobody owns
            // this instance", which is the safe direction: it withholds authority
            // rather than handing it to whoever happens to have id 0.
            ownerUserId = rows[OWNER_USER_ID_KEY]?.toLongOrNull(),
            // Not switches either (LNL-195): one row per audience, holding a rung key.
            // An unrecognised rung — a hand-edited row, a build that knew a sixth rung
            // — drops the row rather than defaulting to one, because guessing here
            // would hand an audience a rung nobody chose on every project created
            // afterwards.
            newProjectAudiences = Audience.entries.mapNotNull { audience ->
                ProjectRole.byKey(rows[newProjectAudienceKey(audience)] ?: "")?.let { audience to it }
            }.toMap(),
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

    override suspend fun setOwnerUserId(userId: Long?): Unit = withContext(DatabaseDispatcher) {
        if (userId == null) {
            database.instanceSettingsQueries.delete(OWNER_USER_ID_KEY)
        } else {
            database.instanceSettingsQueries.upsert(key = OWNER_USER_ID_KEY, value_ = userId.toString())
        }
    }

    override suspend fun setAdmissionPolicy(policy: AdmissionPolicy): Unit = withContext(DatabaseDispatcher) {
        database.instanceSettingsQueries.upsert(key = ADMISSION_KEY, value_ = policy.key)
    }

    /**
     * One audience's row for future projects, or none.
     *
     * Deletes rather than storing a sentinel for null, so "this audience gets nothing"
     * is the absence of a row — the same thing a fresh instance has, which means a
     * deployment that sets a row and clears it again is indistinguishable from one that
     * never set it.
     */
    override suspend fun setNewProjectAudience(audience: Audience, role: ProjectRole?): Unit =
        withContext(DatabaseDispatcher) {
            val key = newProjectAudienceKey(audience)
            if (role == null) {
                database.instanceSettingsQueries.delete(key)
            } else {
                database.instanceSettingsQueries.upsert(key = key, value_ = role.key)
            }
        }

    /** "true" is on; everything else — including a null, an unknown, a typo — is off. */
    private fun String?.isTrue(): Boolean = this == "true"

    private companion object {
        /**
         * The key admission is stored under. Not an [InstanceSettingKey] for
         * [OWNER_USER_ID_KEY]'s reason: that enum is the closed set of *switches*
         * the admin dialog toggles, and this has three values.
         */
        const val ADMISSION_KEY = "admission"

        /**
         * The key ownership is stored under. Not an [InstanceSettingKey], because that
         * enum is the closed set of *switches* the admin dialog toggles and this is a
         * user id — see 33.sqm, which writes the same string.
         */
        const val OWNER_USER_ID_KEY = "owner_user_id"
    }
}
