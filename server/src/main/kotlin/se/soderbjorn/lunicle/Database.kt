/**
 * Opening the SQLite database, and everything about *where* it lives.
 *
 * This is the file Stage 2's second half is really about. The counter and the
 * users table are small; the interesting part is that a file survives the
 * container being replaced, which is true only if the path below lands on a
 * mounted volume rather than in the container's own throwaway filesystem. A
 * wrong path here does not fail — it works perfectly until the next redeploy,
 * and then the data is simply gone. So this file logs loudly rather than
 * assuming.
 *
 * @see UserStore
 * @see SessionStore
 * @see CounterStore
 */
package se.soderbjorn.lunicle

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.db.LunicleDatabase
import java.io.File

private val logger = LoggerFactory.getLogger("Database")

/**
 * Where the SQLite file lives, and whether that location actually persists.
 *
 * @property file the database file.
 * @property isPersistent whether [file] is believed to survive a redeploy —
 *   false when we fell back to the container's own filesystem. Carried as a
 *   fact rather than logged and forgotten so [openDatabase] can say so at
 *   startup, in the one place someone reads when the data has vanished.
 * @property reason how the path was chosen, for that same log line.
 */
data class DatabaseLocation(
    val file: File,
    val isPersistent: Boolean,
    val reason: String,
)

/**
 * Decide where the database goes.
 *
 * The precedence mirrors [resolveFrameAncestors] and [resolveOAuthConfig], for
 * the reasons documented there — a system property for local runs, because a
 * Gradle `JavaExec` inherits the long-lived daemon's environment and an
 * environment variable would go stale; an environment variable for the
 * container, where the process is a plain `java -jar` and the environment is
 * exact.
 *
 * The Railway branch is what makes an un-configured deploy work: Railway
 * injects `RAILWAY_VOLUME_MOUNT_PATH` into the container whenever a volume is
 * attached, so a correctly-provisioned service needs no database variable at
 * all. Its *absence* is therefore the signal that no volume is attached, which
 * is the case worth shouting about.
 */
internal fun resolveDatabaseLocation(): DatabaseLocation {
    // Blank is treated as absent throughout, as elsewhere in this server: an
    // empty override is a misconfiguration, and quietly falling through to a
    // container-local path would be the exact silent data loss this file exists
    // to prevent.
    val fromProperty = System.getProperty("lunicle.databasePath")?.takeIf { it.isNotBlank() }
    if (fromProperty != null) {
        // A local run. "Persistent" in the only sense that matters here: it
        // outlives the process, which is what a developer is testing.
        return DatabaseLocation(File(fromProperty), isPersistent = true, reason = "lunicle.databasePath")
    }

    val fromEnv = System.getenv("LUNICLE_DB_PATH")?.takeIf { it.isNotBlank() }
    if (fromEnv != null) {
        return DatabaseLocation(File(fromEnv), isPersistent = true, reason = "LUNICLE_DB_PATH")
    }

    val volume = System.getenv("RAILWAY_VOLUME_MOUNT_PATH")?.takeIf { it.isNotBlank() }
    if (volume != null) {
        return DatabaseLocation(
            file = File(volume, "lunicle.db"),
            isPersistent = true,
            reason = "RAILWAY_VOLUME_MOUNT_PATH",
        )
    }

    // Nothing said where to put it. This is a working server whose data dies
    // with the container — fine for `docker run` with no volume, catastrophic
    // and invisible in production. openDatabase() escalates this to a warning.
    return DatabaseLocation(
        file = File("lunicle.db").absoluteFile,
        isPersistent = false,
        reason = "fallback (no volume configured)",
    )
}

/**
 * The dispatcher every database call is confined to.
 *
 * Two jobs in one line.
 *
 * *Blocking:* JDBC is blocking, and a blocking call on Netty's event loop
 * stalls unrelated requests. [Dispatchers.IO] is where that belongs.
 *
 * *Serialization:* `limitedParallelism(1)` pins all database work to a single
 * thread. [JdbcSqliteDriver] holds one JDBC connection, and a SQLite connection
 * is not safe to drive from several threads at once — but Netty serves requests
 * on a pool, so without this two concurrent increments genuinely could
 * interleave inside the driver. One thread makes that unrepresentable rather
 * than merely unlikely.
 *
 * The obvious objection is throughput, and it does not apply: this is a
 * single-writer SQLite database on one container — Railway forbids replicas on
 * a service with a volume — so requests were going to serialize at the file
 * lock regardless. All this changes is *where* they queue, and it buys a whole
 * class of bug not existing. If this ever becomes the bottleneck, the answer is
 * a connection pool and WAL readers, not more threads on one connection.
 */
private val databaseDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

/** The dispatcher the stores confine their calls to. See above. */
internal val DatabaseDispatcher: CoroutineDispatcher get() = databaseDispatcher

/**
 * An open database, and the driver holding the file.
 *
 * The two travel together because the queries are on one and the lifetime is on
 * the other: [LunicleDatabase] is a `Transacter` with no `close`, while the
 * driver owns the JDBC connection that must be closed for SQLite to checkpoint
 * its WAL and let go of the file.
 *
 * @property database what the stores query.
 */
class OpenDatabase(
    val database: LunicleDatabase,
    private val driver: SqlDriver,
) : AutoCloseable {
    /** Close the connection. Called when Ktor stops. */
    override fun close() {
        driver.close()
    }
}

/**
 * Open the database, creating or migrating the schema as needed.
 *
 * Called once by `Application.module`. Runs at startup rather than at build
 * time because it has to: Railway mounts the volume when the container starts,
 * not during the build and not during pre-deploy, so there is no earlier moment
 * at which the file could even be created.
 *
 * @return the opened database, for the caller to close on shutdown.
 */
fun openDatabase(location: DatabaseLocation = resolveDatabaseLocation()): OpenDatabase {
    // The volume is mounted at the mount path, but nothing guarantees a
    // subdirectory below it exists, and SQLite will not create one.
    location.file.parentFile?.mkdirs()

    if (location.isPersistent) {
        logger.info("Database: ${location.file} (via ${location.reason})")
    } else {
        // Deliberately a warning, and deliberately explicit about the
        // consequence. The failure this prevents is a deploy that looks
        // completely healthy for days and then loses every account on an
        // unrelated redeploy, with nothing in the logs to explain it.
        logger.warn(
            "Database: ${location.file} — NO PERSISTENT VOLUME. Data will be lost on redeploy. " +
                "Attach a Railway volume, or set LUNICLE_DB_PATH. See docs/volume-instructions.html.",
        )
    }

    val driver = JdbcSqliteDriver("jdbc:sqlite:${location.file.absolutePath}")
    driver.applyPragmas()
    driver.createOrMigrateSchema()
    return OpenDatabase(LunicleDatabase(driver), driver)
}

/**
 * Set the connection-level pragmas SQLite does not default to.
 *
 * All three are executed rather than passed as JDBC URL parameters, so that a
 * failure is a startup exception naming the pragma rather than a silently
 * ignored query string — sqlite-jdbc does not complain about a URL parameter it
 * does not recognise, which makes a typo there invisible.
 */
private fun SqlDriver.applyPragmas() {
    // Without this SQLite silently ignores every FOREIGN KEY in the schema —
    // it parses them, stores them, and does not enforce them. The `ON DELETE
    // CASCADE`s in Sessions.sq and Counters.sq are decoration until this runs.
    // Per-connection, not stored in the file.
    execute(null, "PRAGMA foreign_keys = ON;", 0)

    // WAL: readers don't block the writer and vice versa. Stored in the
    // database file itself, so this only does anything on first open — running
    // it every time is harmless and keeps the setting from depending on which
    // deploy happened to create the file.
    execute(null, "PRAGMA journal_mode = WAL;", 0)

    // If a write does collide, wait rather than failing. The default is zero:
    // SQLite returns SQLITE_BUSY *immediately*, which surfaces as a sporadic
    // 500 under concurrency. DatabaseDispatcher already serializes our own
    // access, so this is the belt to that pair of braces — it covers the
    // WAL checkpointer and anything else holding the file.
    execute(null, "PRAGMA busy_timeout = 5000;", 0)
}

/**
 * Bring the schema up to date: create it on a fresh volume, migrate it on an
 * existing one, do nothing when it is already current.
 *
 * Version tracking is SQLite's own `user_version` — a single integer in the
 * database header, which is exactly what it is there for. SQLDelight does not
 * manage it for us with this driver, so the bookkeeping is here and explicit.
 *
 * `user_version` is 0 on a database that has never been written, which is how
 * "fresh volume" is detected. That makes 0 an unusable schema version, which is
 * fine — SQLDelight numbers from 1.
 *
 * Not wrapped in a transaction: SQLite cannot run every DDL statement
 * transactionally, and a half-applied migration that *reported* success would
 * be worse than one that crashes the container on startup — Railway will
 * restart it, and the logs will name the migration.
 */
private fun SqlDriver.createOrMigrateSchema() {
    val schema = LunicleDatabase.Schema
    val current = currentUserVersion()
    val target = schema.version

    when {
        current == 0L -> {
            logger.info("Database: fresh — creating schema at version $target")
            schema.create(this).value
            setUserVersion(target)
        }

        current < target -> {
            logger.info("Database: migrating schema $current → $target")
            schema.migrate(this, current, target).value
            setUserVersion(target)
        }

        current > target -> {
            // The database was written by a newer build than this one. Almost
            // always a rollback. Refuse rather than guess: this build's queries
            // do not match what is on the volume, and the damage a wrong write
            // does here is not recoverable from.
            error(
                "Database schema is version $current but this build expects $target. " +
                    "The volume was written by a newer deploy — roll forward rather than back.",
            )
        }

        else -> logger.info("Database: schema is current at version $target")
    }
}

/** Read SQLite's `user_version`. Zero on a database nothing has created yet. */
private fun SqlDriver.currentUserVersion(): Long =
    executeQuery(
        identifier = null,
        sql = "PRAGMA user_version;",
        mapper = { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        },
        parameters = 0,
    ).value

/**
 * Write SQLite's `user_version`.
 *
 * Interpolated rather than bound, because `PRAGMA` will not take a bound
 * parameter — SQLite parses the pragma value at prepare time. Safe here in the
 * way string interpolation into SQL almost never is: [version] is a Long that
 * came from generated code, not from anything a caller supplies.
 */
private fun SqlDriver.setUserVersion(version: Long) {
    execute(null, "PRAGMA user_version = $version;", 0)
}
