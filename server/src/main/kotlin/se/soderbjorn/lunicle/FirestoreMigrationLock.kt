/**
 * The single-writer election for migrations: a lock document that only one
 * instance can hold at a time, acquired and released in a Firestore transaction.
 *
 * ── Why a lock is needed here and not on SQLite ─────────────────────────────
 *
 * SQLite on Railway is one container behind a volume, so its migration runs once
 * by construction. Firestore on Cloud Run is many instances that boot together
 * and scale to zero — every one of them runs [FirestoreMigrationRunner] at
 * startup, and without coordination they would all apply the same backfill at
 * once, racing each other's writes and the version checkpoint. This document is
 * the coordination: whoever writes it first migrates; everyone else waits for the
 * version to advance and then serves.
 *
 * ── Correctness rests on the transaction, not on the read ───────────────────
 *
 * [tryAcquire] does its check-and-set inside `runTransaction`, so the read of the
 * lock and the write that claims it are atomic against every other instance's
 * attempt. Two instances that call it in the same millisecond cannot both see the
 * document absent and both create it — Firestore serialises the transactions and
 * one of them retries against the other's write, sees it held, and returns false.
 * A plain "get, then set if absent" would have exactly the race the lock exists to
 * prevent.
 *
 * ── The stale-lock escape hatch ─────────────────────────────────────────────
 *
 * A holder that crashes mid-migration never runs its `release`, so the lock would
 * be held forever and no future deploy could ever migrate. So the lock carries the
 * wall-clock time it was taken, and [tryAcquire] treats a lock older than
 * [staleAfterMs] as abandoned and steals it. The timeout is set well above the
 * longest expected migration so a *slow* holder is never mistaken for a dead one;
 * the cost of stealing too early is two instances backfilling at once, which the
 * idempotent [FirestoreBackfill] tolerates but should not be invited.
 *
 * @see FirestoreMigrationRunner the sole caller — acquires before applying, releases in a `finally`.
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore

/**
 * @property firestore the client whose `_meta/migrationLock` document is the lock.
 * @property staleAfterMs how long a held lock may go un-refreshed before
 *   [tryAcquire] treats it as abandoned and steals it. Above the longest expected
 *   migration, below "forever": a crashed holder must not wedge every future
 *   deploy, and a slow holder must not be robbed mid-backfill.
 * @property now the wall clock, injectable so tests can drive staleness
 *   deterministically rather than by sleeping.
 */
internal class FirestoreMigrationLock(
    private val firestore: Firestore,
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private fun ref() = firestore.collection(FirestoreMeta.COLLECTION).document(FirestoreMeta.LOCK_DOC)

    /**
     * Try to claim the lock for [owner], atomically.
     *
     * Succeeds when the lock is absent (nobody holds it) or stale (a crashed
     * holder past [staleAfterMs]); in both cases it writes [owner] and the current
     * time and returns true. Fails — returns false, writes nothing — when a live
     * holder already has it. Reacquiring while already the owner refreshes the
     * timestamp and succeeds, so a long migration that re-checks the lock is never
     * locked out by its own earlier claim.
     */
    suspend fun tryAcquire(owner: String): Boolean =
        firestore.runTransaction { txn ->
            val snap = txn.get(ref()).get()
            val heldBy = snap.getString(OWNER)
            val acquiredAt = snap.getLong(ACQUIRED_AT) ?: 0L
            val free = !snap.exists() || heldBy == null
            val stale = now() - acquiredAt >= staleAfterMs
            val mine = heldBy == owner
            if (free || stale || mine) {
                txn.set(ref(), mapOf(OWNER to owner, ACQUIRED_AT to now()))
                true
            } else {
                false
            }
        }.await()

    /**
     * Release the lock, but only if [owner] still holds it.
     *
     * The owner check makes release safe against the stale-steal race: if this
     * instance stalled long enough for another to steal the lock and start its own
     * migration, this instance's late `release` must not delete *that* instance's
     * lock out from under it. Releasing a lock we no longer own is a no-op.
     */
    suspend fun release(owner: String) {
        firestore.runTransaction { txn ->
            val snap = txn.get(ref()).get()
            if (snap.getString(OWNER) == owner) txn.delete(ref())
            null
        }.await()
    }

    companion object {
        /** Ten minutes: comfortably above any migration this framework should run inline at boot. */
        const val DEFAULT_STALE_AFTER_MS = 10 * 60 * 1000L

        const val OWNER = "owner"
        const val ACQUIRED_AT = "acquiredAt"
    }
}
