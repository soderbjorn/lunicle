/**
 * The Firestore analog of [se.soderbjorn.lunicle.openDatabase]'s schema walk: read
 * the current version, apply the pending steps in order under a single-writer
 * lock, and checkpoint the version after each.
 *
 * ── The algorithm, and why it is a loop ─────────────────────────────────────
 *
 * On Cloud Run many instances boot at once and every one of them calls [run].
 * Exactly one should migrate; the rest should wait until the schema has caught up
 * and then serve. So [run] is a loop, not a straight line:
 *
 *  1. Read `/_meta/schema.version` (absent = 0). If it already meets the target
 *     (the highest registered migration), there is nothing to do — return.
 *  2. Try to take the [FirestoreMigrationLock]. If we get it, we are the migrator:
 *     apply every pending step in ascending version order, checkpointing the
 *     version document after each one completes, then release the lock in a
 *     `finally` so a failure never wedges the next deploy.
 *  3. If we did not get the lock, another instance is migrating. Poll the version
 *     until it reaches the target — then return, having *observed* the migration
 *     rather than run it. If the holder dies, its lock goes stale or is released
 *     and a later turn of the loop takes it, so a crashed migrator does not strand
 *     the rest; only a genuinely stuck migration (no progress before
 *     [waitTimeoutMs]) makes [run] give up and throw.
 *
 * ── Checkpoint after each step, not at the end ──────────────────────────────
 *
 * The version is written after *every* migration, so an interruption between steps
 * 3 and 4 of a five-step chain leaves the version at 3 and a re-run resumes at 4.
 * Within a single step, resumability is the migration's own job — see
 * [FirestoreBackfill] — because a data backfill is far more than one transaction's
 * worth of writes and cannot be checkpointed atomically with its own progress.
 *
 * ── Boot wiring ─────────────────────────────────────────────────────────────
 *
 * [migrateFirestore] is the entry point the Firestore boot path (LNL-122's backend
 * selection) calls, on the `LUNICLE_DB_BACKEND=firestore` branch only, after the
 * [FirestoreProvider] exists and before the server serves a request. It is *not*
 * wired into `Application.module` here: that method still refuses every non-SQLite
 * backend (LNL-111 Phase 1), and nothing in this file constructs a client or runs
 * at class-load, so the SQLite/Railway path is untouched and the Railway-safety
 * guarantee holds. LNL-122 calls `migrateFirestore(provider.firestore)` where it
 * wires the rest of the Firestore stores.
 *
 * @see FirestoreMigration a single step.
 * @see FirestoreMigrationLock the single-writer election.
 * @see FirestoreBackfill the resumable within-step backfill a migration uses.
 * @see se.soderbjorn.lunicle.openDatabase the SQLite counterpart this mirrors.
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("FirestoreMigrationRunner")

/**
 * The outcome of a [FirestoreMigrationRunner.run], for the caller to log.
 *
 * @property versionBefore the schema version when [run] was entered.
 * @property versionAfter the schema version when it returned.
 * @property applied the versions this instance itself migrated — empty when it
 *   found the schema already current or watched another instance do the work.
 * @property migratedHere whether this instance held the lock and did the migrating.
 */
data class MigrationOutcome(
    val versionBefore: Long,
    val versionAfter: Long,
    val applied: List<Int>,
    val migratedHere: Boolean,
)

/**
 * @property firestore the client to migrate.
 * @property migrations the chain to apply; defaults to [FirestoreMigrations.ALL].
 *   Injectable so tests drive their own ordered steps.
 * @property ownerId this instance's identity in the lock, unique per run so two
 *   instances never collide on it. A UUID by default.
 * @property lockStaleAfterMs how long a held lock may go stale before it is stolen
 *   — forwarded to [FirestoreMigrationLock].
 * @property waitTimeoutMs how long a non-migrating instance waits for the version
 *   to reach the target before giving up and throwing. Above the longest expected
 *   migration; a value exceeded means the migrator is genuinely stuck.
 * @property pollIntervalMs how often a waiting instance re-reads the version.
 * @property now the wall clock, injectable for deterministic tests.
 */
internal class FirestoreMigrationRunner(
    private val firestore: Firestore,
    private val migrations: List<FirestoreMigration> = FirestoreMigrations.ALL,
    private val ownerId: String = UUID.randomUUID().toString(),
    private val lockStaleAfterMs: Long = FirestoreMigrationLock.DEFAULT_STALE_AFTER_MS,
    private val waitTimeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = FirestoreMigrationLock(firestore, lockStaleAfterMs, now)

    private fun schemaRef() = firestore.collection(FirestoreMeta.COLLECTION).document(FirestoreMeta.SCHEMA_DOC)

    /**
     * Bring the Firestore schema up to the highest registered migration, electing
     * a single migrator via the lock and letting every other instance observe the
     * result. See the file preamble for the full algorithm.
     *
     * @throws IllegalStateException if the migration chain is malformed (a
     *   non-positive or duplicate version), or if a waiting instance times out
     *   because the elected migrator made no progress before [waitTimeoutMs].
     */
    suspend fun run(): MigrationOutcome {
        val ordered = validated(migrations)
        val target = ordered.lastOrNull()?.version ?: 0
        val startVersion = readVersion()

        if (startVersion >= target) {
            logger.info("Firestore schema is current at version $startVersion (target $target)")
            return MigrationOutcome(startVersion, startVersion, emptyList(), migratedHere = false)
        }

        val deadline = now() + waitTimeoutMs
        while (true) {
            val current = readVersion()
            if (current >= target) {
                // Another instance did the work while we waited — observe and serve.
                logger.info("Firestore schema reached version $current (target $target); migrated elsewhere")
                return MigrationOutcome(startVersion, current, emptyList(), migratedHere = false)
            }

            if (lock.tryAcquire(ownerId)) {
                val applied = try {
                    applyPending(ordered, target)
                } finally {
                    lock.release(ownerId)
                }
                val after = readVersion()
                return MigrationOutcome(startVersion, after, applied, migratedHere = true)
            }

            // Someone else holds the lock. Wait for the version to advance, unless
            // it has been stuck past the deadline — then the migrator is wedged.
            if (now() >= deadline) {
                val stuck = readVersion()
                check(stuck >= target) {
                    "Timed out after ${waitTimeoutMs}ms waiting for another instance to migrate Firestore " +
                        "(version stuck at $stuck, target $target). The migrating instance is not making " +
                        "progress. Refusing to serve on a schema behind this build."
                }
                return MigrationOutcome(startVersion, stuck, emptyList(), migratedHere = false)
            }
            delay(pollIntervalMs)
        }
    }

    /**
     * Apply every step above the current version, in order, checkpointing after
     * each. Re-reads the version first: another instance may have advanced it
     * between our pre-lock read and taking the lock, and a crashed predecessor may
     * have left us mid-chain to resume.
     */
    private suspend fun applyPending(ordered: List<FirestoreMigration>, target: Int): List<Int> {
        val applied = mutableListOf<Int>()
        var version = readVersion()
        for (migration in ordered) {
            if (migration.version <= version) continue
            logger.info("Applying Firestore migration ${migration.version} (schema $version → ${migration.version})")
            migration.apply(firestore)
            writeVersion(migration.version.toLong())
            version = migration.version.toLong()
            applied += migration.version
        }
        logger.info("Firestore schema migrated to version $version (target $target); applied=$applied")
        return applied
    }

    /** Read `/_meta/schema.version`; a missing document or field is version 0. */
    private suspend fun readVersion(): Long = schemaRef().get().await().getLong(FirestoreMeta.VERSION) ?: 0L

    /** Checkpoint the schema version. `merge` so the document keeps any other future `_meta/schema` fields. */
    private suspend fun writeVersion(version: Long) {
        schemaRef().set(mapOf(FirestoreMeta.VERSION to version), com.google.cloud.firestore.SetOptions.merge()).await()
    }

    /** Reject a malformed chain up front: versions must be positive and unique. Returns them sorted ascending. */
    private fun validated(migrations: List<FirestoreMigration>): List<FirestoreMigration> {
        val sorted = migrations.sortedBy { it.version }
        sorted.firstOrNull { it.version < 1 }?.let {
            error("Firestore migration versions must be >= 1, found ${it.version}")
        }
        val duplicate = sorted.groupBy { it.version }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) error("Duplicate Firestore migration version ${duplicate.key}")
        return sorted
    }

    companion object {
        /** Five minutes: a waiting instance's patience for the elected migrator. Above any inline boot migration. */
        const val DEFAULT_WAIT_TIMEOUT_MS = 5 * 60 * 1000L

        /** How often a waiting instance re-reads the version. */
        const val DEFAULT_POLL_INTERVAL_MS = 500L
    }
}

/**
 * The boot-time entry point for the Firestore backend, called by LNL-122's backend
 * selection on the `LUNICLE_DB_BACKEND=firestore` branch only — after the
 * [FirestoreProvider] is built and before the server serves.
 *
 * Deliberately a plain function taking the already-open client rather than
 * anything that constructs one: it must never run on, and never pull GCP
 * machinery into, the SQLite/Railway path. Fatal on a stuck migration for the same
 * reason [se.soderbjorn.lunicle.openDatabase] is fatal on a bad schema — a server
 * on a schema behind its build must refuse to serve rather than 500 on every
 * request.
 */
suspend fun migrateFirestore(firestore: Firestore): MigrationOutcome = FirestoreMigrationRunner(firestore).run()
