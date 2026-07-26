/**
 * The shell settings a signed-in user carries between browsers: their appearance
 * choice and the themes behind it.
 *
 * A thin store over one key-value table, and thin on purpose — the values are
 * lunula blobs and nothing on this side reads inside one. See
 * UiSettings.sq for why they are stored that way, and UiSettingsRoutes for the
 * allowlist that decides which keys ever reach here.
 *
 * @see Database
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * Reads and writes `user_ui_settings`: the SQLite reference implementation of
 * [se.soderbjorn.lunicle.store.UiSettingsStore].
 *
 * The interface is named in the supertype clause by its fully-qualified name on
 * purpose — this class deliberately keeps the bare `UiSettingsStore` name it has
 * always had, so nothing that constructs it has to change, and the two names
 * would otherwise collide. See the interface for the contract.
 *
 * @param database the open database.
 */
class UiSettingsStore(
    private val database: LunicleDatabase,
) : se.soderbjorn.lunicle.store.UiSettingsStore {
    /**
     * Everything stored for [userId], keyed by toolkit persistence key.
     *
     * Empty for an account that has never chosen a theme, which is a state the
     * client already knows how to render — it is what every session looked like
     * before this table existed.
     */
    override suspend fun forUser(userId: Long): Map<String, String> = withContext(DatabaseDispatcher) {
        database.uiSettingsQueries.selectForUser(userId)
            .executeAsList()
            .associate { it.key to it.value_ }
    }

    /**
     * Store one value, replacing whatever was there.
     *
     * Idempotent, and deliberately unconditional: a preference has no history
     * worth keeping and no conflict worth reporting. Two tabs disagreeing about
     * the theme is not a race to be resolved, it is one person changing their
     * mind twice.
     */
    override suspend fun put(userId: Long, key: String, value: String): Unit = withContext(DatabaseDispatcher) {
        database.uiSettingsQueries.upsert(user_id = userId, key = key, value_ = value)
    }
}
