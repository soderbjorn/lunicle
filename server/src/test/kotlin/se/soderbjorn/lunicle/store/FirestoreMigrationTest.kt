/**
 * The Firestore migration framework, proven on the emulator.
 *
 * These are the tests the LNL-121 ticket names, one per guarantee the framework
 * makes: an old-shape collection migrates to the new shape and the version is
 * checkpointed; a second run is a no-op (idempotence); an interrupted backfill
 * resumes rather than re-doing or double-applying; and the single-writer lock stops
 * a second instance from migrating in parallel, while an instance that loses the
 * election observes the version advance instead of applying anything.
 *
 * Like every Firestore test in this suite they reuse [FirestoreContractFixture] for
 * an isolated per-run emulator namespace and skip — rather than fail — when no
 * emulator is configured (`-Dlunicle.firestoreEmulatorHost=…`), so the SQLite suite
 * and CI are unaffected.
 *
 * @see se.soderbjorn.lunicle.FirestoreMigrationRunner
 * @see se.soderbjorn.lunicle.FirestoreBackfill
 * @see se.soderbjorn.lunicle.FirestoreMigrationLock
 */
package se.soderbjorn.lunicle.store

import com.google.cloud.firestore.Firestore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.BackfillResult
import se.soderbjorn.lunicle.FirestoreBackfill
import se.soderbjorn.lunicle.FirestoreMeta
import se.soderbjorn.lunicle.FirestoreMigration
import se.soderbjorn.lunicle.FirestoreMigrationLock
import se.soderbjorn.lunicle.FirestoreMigrationRunner

class FirestoreMigrationTest {
    private val fixture = FirestoreContractFixture()
    private val db get() = fixture.firestore

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()

    // ── The migration under test ─────────────────────────────────────────────
    // An old-shape widget is `{ name }`; the migration adds `status = "active"` to
    // every widget via the resumable backfill helper. `applyCount` records how many
    // times the runner invoked `apply`, so idempotence and the lock are observable
    // from the outside; `lastResult` exposes the backfill's own accounting so a
    // resume shows up as skipped>0, written<total.

    private inner class AddStatusMigration(
        override val version: Int,
        private val pageSize: Int = 10,
    ) : FirestoreMigration {
        val applyCount = AtomicInteger(0)
        @Volatile var lastResult: BackfillResult? = null

        override suspend fun apply(db: Firestore) {
            applyCount.incrementAndGet()
            lastResult = FirestoreBackfill(db).run(WIDGETS, version, pageSize) { mapOf(STATUS to ACTIVE) }
        }
    }

    /**
     * The same backfill, but its first `apply` throws partway — the earlier pages
     * have committed, the rest have not — to simulate an instance recycled mid-pass.
     * Its second `apply` runs cleanly and must resume over the unfinished remainder.
     */
    private inner class CrashOnceThenBackfillMigration(
        override val version: Int,
        private val pageSize: Int = 10,
        private val failAfter: Int = 25,
    ) : FirestoreMigration {
        val attempts = AtomicInteger(0)
        @Volatile var resumeResult: BackfillResult? = null

        override suspend fun apply(db: Firestore) {
            val attempt = attempts.incrementAndGet()
            val backfill = FirestoreBackfill(db)
            if (attempt == 1) {
                val seen = AtomicInteger(0)
                backfill.run(WIDGETS, version, pageSize) {
                    check(seen.incrementAndGet() < failAfter) { "simulated crash mid-backfill" }
                    mapOf(STATUS to ACTIVE)
                }
            } else {
                resumeResult = backfill.run(WIDGETS, version, pageSize) { mapOf(STATUS to ACTIVE) }
            }
        }
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `an old-shape collection migrates to the new shape and checkpoints the version`() = runBlocking {
        seedWidgets(5)
        val migration = AddStatusMigration(version = 1)

        val outcome = runner(migration).run()

        assertEquals(true, outcome.migratedHere, "this instance held the lock and migrated")
        assertEquals(listOf(1), outcome.applied)
        assertEquals(1L, outcome.versionAfter)
        assertEquals(1L, storedVersion(), "the version marker document is checkpointed")
        assertEquals(5, widgetsWithStatus(), "every widget carries the new field")
    }

    @Test
    fun `running the same migration twice is idempotent`() = runBlocking {
        seedWidgets(5)
        val migration = AddStatusMigration(version = 1)
        val runner = runner(migration)

        runner.run()
        val second = runner.run()

        assertEquals(false, second.migratedHere, "the schema is already current on the second run")
        assertTrue(second.applied.isEmpty(), "nothing is re-applied")
        assertEquals(1, migration.applyCount.get(), "apply ran exactly once across both runs")
        assertEquals(1L, storedVersion())
        assertEquals(5, widgetsWithStatus())
    }

    @Test
    fun `an interrupted backfill resumes and completes without double-applying`() = runBlocking {
        seedWidgets(50)
        val migration = CrashOnceThenBackfillMigration(version = 1, pageSize = 10, failAfter = 25)
        val runner = runner(migration)

        // First run: apply throws mid-backfill. The version is NOT checkpointed (the
        // step never completed) and the lock is released, so run() surfaces the crash.
        assertFailsWith<IllegalStateException> { runner.run() }
        assertEquals(0L, storedVersion(), "an interrupted step does not advance the version")
        val migratedBeforeResume = widgetsWithStatus()
        assertTrue(migratedBeforeResume in 1 until 50, "a partial backfill left some — not all — widgets migrated")

        // Second run: resumes over the unfinished remainder, skipping the committed pages.
        val outcome = runner.run()

        assertEquals(2, migration.attempts.get(), "apply was invoked once to crash, once to resume")
        assertEquals(true, outcome.migratedHere)
        assertEquals(1L, storedVersion(), "the resumed step checkpoints the version")
        assertEquals(50, widgetsWithStatus(), "every widget is migrated after the resume")

        val resume = migration.resumeResult!!
        assertEquals(50, resume.scanned)
        assertEquals(migratedBeforeResume, resume.skipped, "the already-done widgets are skipped on resume")
        assertEquals(50 - migratedBeforeResume, resume.written, "only the remainder is written")
        assertTrue(resume.written < 50, "the resume did not re-do the whole collection")
    }

    @Test
    fun `the lock stops a second instance from migrating in parallel`() = runBlocking {
        seedWidgets(5)
        // A live lock held by another instance — as if a peer is already migrating.
        holdLock(owner = "peer-instance", acquiredAt = System.currentTimeMillis())

        val migration = AddStatusMigration(version = 1)
        // A short wait so the test does not sit for the default five minutes; the peer
        // never advances the version, so the runner must give up rather than migrate.
        val blocked = runner(migration, waitTimeoutMs = 400, pollIntervalMs = 50)

        assertFailsWith<IllegalStateException> { blocked.run() }
        assertEquals(0, migration.applyCount.get(), "the second instance never applied the migration")
        assertEquals(0L, storedVersion(), "and never advanced the version")
        assertEquals(0, widgetsWithStatus(), "the collection is untouched")

        // Once the lock is free the same instance migrates normally.
        releaseLock()
        val outcome = runner(migration).run()
        assertEquals(true, outcome.migratedHere)
        assertEquals(1L, storedVersion())
        assertEquals(5, widgetsWithStatus())
    }

    @Test
    fun `an instance that loses the election observes the advanced version without re-applying`() = runBlocking {
        seedWidgets(5)
        holdLock(owner = "peer-instance", acquiredAt = System.currentTimeMillis())

        val migration = AddStatusMigration(version = 1)
        val waiter = runner(migration, waitTimeoutMs = 10_000, pollIntervalMs = 50)

        coroutineScope {
            // The "peer" finishes migrating after a moment: it advances the version to
            // the target. Our runner, which lost the lock, should notice and stand down.
            launch(Dispatchers.IO) {
                delay(200)
                advanceVersionExternally(1)
            }
            val outcome = waiter.run()
            assertEquals(false, outcome.migratedHere, "this instance did not migrate")
            assertTrue(outcome.applied.isEmpty())
            assertEquals(1L, outcome.versionAfter, "it observed the version the peer advanced")
            assertEquals(0, migration.applyCount.get(), "and never applied the step itself")
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun runner(
        migration: FirestoreMigration,
        waitTimeoutMs: Long = FirestoreMigrationRunner.DEFAULT_WAIT_TIMEOUT_MS,
        pollIntervalMs: Long = 50,
    ) = FirestoreMigrationRunner(
        firestore = db,
        migrations = listOf(migration),
        waitTimeoutMs = waitTimeoutMs,
        pollIntervalMs = pollIntervalMs,
    )

    private fun seedWidgets(n: Int) {
        // Under one page-size at a time is fine here; a handful of blocking sets keep
        // the seed simple, and the emulator is local.
        for (i in 1..n) {
            db.collection(WIDGETS).document("w%04d".format(i)).set(mapOf(NAME to "widget-$i")).get()
        }
    }

    private fun widgetsWithStatus(): Int =
        db.collection(WIDGETS).get().get().documents.count { it.getString(STATUS) != null }

    private fun storedVersion(): Long =
        db.collection(FirestoreMeta.COLLECTION).document(FirestoreMeta.SCHEMA_DOC).get().get()
            .getLong(FirestoreMeta.VERSION) ?: 0L

    private fun advanceVersionExternally(version: Long) {
        db.collection(FirestoreMeta.COLLECTION).document(FirestoreMeta.SCHEMA_DOC)
            .set(mapOf(FirestoreMeta.VERSION to version)).get()
    }

    private fun holdLock(owner: String, acquiredAt: Long) {
        db.collection(FirestoreMeta.COLLECTION).document(FirestoreMeta.LOCK_DOC)
            .set(mapOf(FirestoreMigrationLock.OWNER to owner, FirestoreMigrationLock.ACQUIRED_AT to acquiredAt)).get()
    }

    private fun releaseLock() {
        db.collection(FirestoreMeta.COLLECTION).document(FirestoreMeta.LOCK_DOC).delete().get()
    }

    private companion object {
        const val WIDGETS = "widgets"
        const val NAME = "name"
        const val STATUS = "status"
        const val ACTIVE = "active"
    }
}
