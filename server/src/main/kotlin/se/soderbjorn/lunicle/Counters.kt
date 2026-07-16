/**
 * The counter, per user, on the volume.
 *
 * Replaces Stage 1's process-wide `AtomicInteger`. The atomic was correct for
 * what it was — it kept two concurrent increments from losing an update — and
 * this keeps that same property by the same reasoning, one layer down: the
 * arithmetic happens inside SQLite under the row lock rather than inside the
 * JVM under a CAS. See the `increment` query in Counters.sq.
 *
 * What actually changed is ownership and lifetime. The count now has a user id
 * on it and lives in a file that outlives the container, which together are the
 * whole of this stage's exit criterion.
 *
 * @see Database
 * @see counterRoutes
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * Reads and increments per-user counters.
 *
 * @param database the open database.
 */
class CounterStore(
    private val database: LunicleDatabase,
) {
    /**
     * [userId]'s count.
     *
     * Zero for a user who has never incremented: no row is written until the
     * first tap, so "no row" and "zero" mean the same thing and there is no
     * reason to make signing in cost a write.
     */
    suspend fun get(userId: Long): Int = withContext(DatabaseDispatcher) {
        (database.countersQueries.select(userId).executeAsOneOrNull() ?: 0L).toInt()
    }

    /**
     * Increment [userId]'s count and return the new value.
     *
     * One statement — see Counters.sq. The value returned is the one this
     * increment produced, not a subsequent read, so the caller can never report
     * a number that belongs to someone else's concurrent tap.
     */
    suspend fun increment(userId: Long): Int = withContext(DatabaseDispatcher) {
        database.countersQueries.increment(userId).executeAsOne().toInt()
    }
}
