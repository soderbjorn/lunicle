/**
 * The persistence seam for a signed-in user's shell settings.
 *
 * One of the domain store interfaces introduced by LNL-111. Every backend the
 * server can run on — today's SQLite ([se.soderbjorn.lunicle.UiSettingsStore] is
 * the reference implementation), a future Firestore — satisfies this same
 * contract, and the store contract test suite pins the behaviour once so the two
 * cannot drift.
 *
 * Deliberately the *domain* surface, not the SQL: `suspend` methods over plain
 * values, nothing about tables, key-value blobs or upserts leaking through. That
 * is what lets a document backend implement it without pretending to be
 * relational.
 *
 * @see se.soderbjorn.lunicle.store.UiSettingsStoreContract for the behaviour every
 *   implementation must exhibit.
 */
package se.soderbjorn.lunicle.store

/**
 * Reads and writes the appearance settings a signed-in user carries between
 * browsers, keyed by lunula persistence key.
 */
interface UiSettingsStore {
    /**
     * Everything stored for [userId], keyed by toolkit persistence key.
     *
     * Empty — not null — for an account that has never stored anything, which is
     * the state every session was in before this existed.
     */
    suspend fun forUser(userId: Long): Map<String, String>

    /**
     * Store one value under [key], replacing whatever was there.
     *
     * Idempotent and unconditional: a preference has no history worth keeping and
     * no conflict worth reporting — last write wins.
     */
    suspend fun put(userId: Long, key: String, value: String)
}
