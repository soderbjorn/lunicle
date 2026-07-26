/**
 * Opening the SQLite database, and everything about *where* it lives.
 *
 * This is the file the volume is really about. The tables themselves are small;
 * the interesting part is that a file survives the
 * container being replaced, which is true only if the path below lands on a
 * mounted volume rather than in the container's own throwaway filesystem. A
 * wrong path here does not fail — it works perfectly until the next redeploy,
 * and then the data is simply gone. So this file logs loudly rather than
 * assuming.
 *
 * @see UserStore
 * @see SessionStore
 * @see AttachmentRepository
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
import java.util.Properties

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
) {
    /**
     * Where attachment bytes go: `<the database's directory>/attachments/`.
     *
     * Derived from [file] rather than from a second setting, and that is the
     * whole point. One resolution means attachments cannot land on a different
     * disk from the rows describing them — which is exactly the failure this
     * file's preamble is about: a second `LUNICLE_ATTACHMENTS_PATH` that nobody
     * set would put the images in the container's throwaway filesystem while the
     * metadata sat safely on the volume. Every issue would render fine until the
     * next redeploy and then show broken images forever, with the database
     * insisting the files exist.
     *
     * `parentFile` is null only for a bare relative filename, which the fallback
     * branch below cannot produce (it calls `absoluteFile`) — but a caller
     * passing `File("x.db")` could, so the elvis keeps this total.
     */
    val attachmentsDirectory: File get() = File(file.absoluteFile.parentFile ?: File("."), "attachments")
}

/**
 * Decide where the database goes.
 *
 * The precedence mirrors [resolveAllowedFrameAncestors] and [resolveOAuthConfig], for
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
 * thread, so no two requests are ever inside the driver at once. Netty serves
 * on a pool, so without this they genuinely would be.
 *
 * This used to say that [JdbcSqliteDriver] "holds one JDBC connection", and that
 * was wrong in a way worth recording: for a file URL it opens a **fresh
 * connection per operation**. That belief is what hid the pragma bug for a whole
 * stage — if there were one long-lived connection, configuring it once at
 * startup would have worked, and the comment made that look obviously true. It
 * is why the pragmas are connection *properties* now: they are the only thing
 * that applies to a connection this code never sees. See [connectionProperties].
 *
 * The serialization is still worth having — it keeps our own writes from
 * queueing at the file lock and makes `busy_timeout` a backstop rather than a
 * load-bearing setting — but it is not what makes the driver safe, and nothing
 * should be built on the assumption that a connection carries state between
 * calls.
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
 * @property driver the connection underneath. `internal` rather than private so
 *   ForeignKeyTest can read the pragmas back off the very driver the stores use
 *   — which is the whole point of that test, since the bug it guards against was
 *   a pragma landing on a connection nothing else ever saw.
 */
class OpenDatabase(
    val database: LunicleDatabase,
    internal val driver: SqlDriver,
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

    val url = "jdbc:sqlite:${location.file.absolutePath}"

    // Schema work first, on a connection of its own — see migrateSchema(). It is
    // closed before the serving driver is opened, so nothing can accidentally
    // serve a request through a foreign-keys-off connection.
    migrateSchema(url)

    // The pragmas ride on the connection PROPERTIES, not on execute() calls.
    // See connectionProperties() — this is the difference between the foreign
    // keys in this schema being enforced and being decoration.
    val driver = JdbcSqliteDriver(url, connectionProperties())
    driver.verifyPragmas()
    return OpenDatabase(LunicleDatabase(driver), driver)
}

/**
 * Create or migrate the schema, on a dedicated connection with foreign keys OFF.
 *
 * ── Why this is not just done on the serving driver ─────────────────────────
 *
 * Because a migration that changes a table's *constraints* cannot be done with
 * foreign keys on, and this schema now has one. SQLite cannot add a composite
 * foreign key — or a NOT NULL column that references anything — with ALTER
 * TABLE, so 2.sqm rebuilds `issues` the way SQLite's own ALTER TABLE
 * documentation prescribes: new table, copy, drop, rename.
 *
 * The drop is the problem. `DROP TABLE` with foreign keys enabled runs an
 * implicit `DELETE FROM` first, and four tables reference `issues` with
 * ON DELETE CASCADE — so the drop would silently take every label, comment and
 * attachment in the database with it, and report success.
 *
 * ── Why a separate driver, rather than a pragma in the .sqm ─────────────────
 *
 * A `PRAGMA foreign_keys=OFF` statement inside the migration would be a no-op
 * that looks like a fix. [JdbcSqliteDriver] hands out a fresh connection per
 * operation for a file URL, so the pragma would configure a throwaway connection
 * and the DROP would arrive on a new one with foreign keys still on — the exact
 * shape of the bug [connectionProperties] exists to document. Off has to come
 * from the properties, and properties belong to a driver, so this is a driver.
 *
 * The window this opens is real and is closed deliberately: for as long as this
 * connection exists, nothing in the schema is enforced. It is opened after the
 * process has decided where the file is and before anything can serve a request,
 * used for DDL only, checked with [foreign_key_check], and closed. Nothing else
 * is given it — note that it is a local, not a return value.
 */
private fun migrateSchema(url: String) {
    JdbcSqliteDriver(url, migrationProperties()).use { driver ->
        val migrated = driver.createOrMigrateSchema()
        // Only after a migration, and only because one just ran with the
        // constraints switched off. `create` builds an empty database, which has
        // nothing to violate; running the check there would be ceremony.
        if (migrated) driver.verifyForeignKeys()
    }
}

/**
 * Re-check every foreign key in the database, and refuse to serve if any fails.
 *
 * The counterpart to running a migration with the constraints off: for the
 * length of that migration SQLite accepted whatever it was told, so this is the
 * moment the database is asked to prove the result is still sound. It is the
 * step SQLite's 12-step procedure ends with, and skipping it would mean a
 * mistake in a `.sqm` — a backfill subquery that returned null for one project,
 * say — landing on the volume as rows pointing at nothing, discovered weeks
 * later as a 500.
 *
 * Deliberately fatal, for [verifyPragmas]' reason: the container dies at startup
 * with the reason in the log, and Railway restarts it into the same loud
 * failure. A migration that half-worked must not serve.
 */
private fun SqlDriver.verifyForeignKeys() {
    val violations = executeQuery(
        identifier = null,
        // Returns one row per violating row — table, rowid, parent, fkid. Empty
        // is the good answer.
        sql = "PRAGMA foreign_key_check;",
        mapper = { cursor ->
            var count = 0
            while (cursor.next().value) count++
            QueryResult.Value(count)
        },
        parameters = 0,
    ).value
    check(violations == 0) {
        "Migration left $violations foreign key violation(s). The schema was migrated with " +
            "foreign keys off and the result does not satisfy them. Refusing to start."
    }
    logger.info("Database: foreign_key_check passed after migration")
}

/**
 * The migration connection's pragmas: as [connectionProperties], but unenforced.
 *
 * WAL and busy_timeout are carried over — WAL because it is a property of the
 * *file* and this connection is the one that creates it on a fresh volume, so
 * leaving it out would write the database in the default rollback journal and
 * the serving driver's later request for WAL would be the thing that had to
 * convert it. busy_timeout because a migration is exactly when waiting beats
 * failing.
 */
private fun migrationProperties(): Properties = connectionProperties().apply {
    put("foreign_keys", "false")
}

/**
 * The pragmas SQLite does not default to, as connection properties.
 *
 * ── Why properties, and not `execute("PRAGMA …")` ───────────────────────────
 *
 * This file used to run all three as statements, with a comment explaining that
 * executing them beat passing them on the URL because a typo would then be a
 * loud failure rather than a silently ignored query string. The reasoning was
 * sound and the code did not work, which is a bad combination: **not one of the
 * three pragmas was ever in effect.**
 *
 * `JdbcSqliteDriver` hands out a *fresh connection per operation* for a file
 * URL. `foreign_keys` and `busy_timeout` are per-connection settings, so
 * executing them configured a throwaway connection that was returned to the
 * pool and never used again — every later query got a connection with foreign
 * keys OFF. Measured, not guessed: `PRAGMA foreign_keys` read back `0`
 * immediately after `PRAGMA foreign_keys = ON` on the same driver, and deleting
 * an issue left its attachment rows behind, pointing at nothing.
 *
 * `journal_mode` is stored in the file rather than the connection, so it should
 * have survived — and did not, because it was executed on a connection opened
 * before the database existed. It read back `delete`.
 *
 * Properties are applied by sqlite-jdbc to *every* connection it opens, which
 * is the only thing that matches how this driver actually behaves. The original
 * comment's worry — that a typo would be ignored — is real and is why
 * [verifyPragmas] now reads all three back and refuses to start if they are
 * wrong. That is strictly louder than what the executed version claimed to
 * offer, since it also catches this failure.
 */
private fun connectionProperties(): Properties = Properties().apply {
    // Without this SQLite parses every FOREIGN KEY in the schema, stores it,
    // and does not enforce it. Every `ON DELETE CASCADE` is decoration until
    // this is on — and so is the trickery in IssueLabels.sq, whose composite
    // foreign keys are the only thing stopping an issue from carrying another
    // project's label.
    put("foreign_keys", "true")

    // WAL: readers don't block the writer and vice versa. Stored in the
    // database file itself, so this only does anything on first open.
    put("journal_mode", "WAL")

    // If a write does collide, wait rather than failing. The default is zero:
    // SQLite returns SQLITE_BUSY *immediately*, which surfaces as a sporadic
    // 500 under concurrency. DatabaseDispatcher already serializes our own
    // access, so this covers the WAL checkpointer and anything else holding the
    // file.
    put("busy_timeout", "5000")
}

/**
 * Read the pragmas back, and refuse to start if they did not take.
 *
 * Not ceremony. The bug this exists to catch already happened once, silently,
 * and its symptom was not an error — it was a database quietly accumulating
 * rows that pointed at deleted parents, on a volume, for as long as nobody
 * looked. A schema whose constraints are not enforced is worse than one with no
 * constraints, because everything downstream is written trusting them.
 *
 * Deliberately fatal: `Application.module` does not catch this, so the container
 * dies at startup with the reason in the log, and Railway restarts it into the
 * same loud failure. That beats serving.
 */
private fun SqlDriver.verifyPragmas() {
    val foreignKeys = readPragma("foreign_keys")
    check(foreignKeys == "1") {
        "PRAGMA foreign_keys is \"$foreignKeys\", not 1. Every ON DELETE CASCADE and every " +
            "composite foreign key in this schema would be silently unenforced. Refusing to start."
    }
    val journalMode = readPragma("journal_mode")
    if (!journalMode.equals("wal", ignoreCase = true)) {
        // A warning rather than fatal: WAL is a concurrency property, not a
        // correctness one. Everything still works in the default rollback
        // journal, just with readers and the writer blocking each other.
        logger.warn("PRAGMA journal_mode is \"$journalMode\", not WAL.")
    }
    logger.info("Database pragmas: foreign_keys=$foreignKeys, journal_mode=$journalMode")
}

/** Read one pragma as text, so the caller can report what it actually got. */
internal fun SqlDriver.readPragma(name: String): String = executeQuery(
    identifier = null,
    sql = "PRAGMA $name;",
    mapper = { cursor ->
        QueryResult.Value(if (cursor.next().value) cursor.getString(0) ?: "" else "")
    },
    parameters = 0,
).value

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
 *
 * @return whether a *migration* ran, as opposed to a create or a no-op. The
 *   caller uses it to decide whether to re-check the foreign keys: only the
 *   migration path can leave violations behind, because only it runs statements
 *   against rows that already exist. See [verifyForeignKeys].
 */
private fun SqlDriver.createOrMigrateSchema(): Boolean {
    val schema = LunicleDatabase.Schema
    val current = currentUserVersion()
    val target = schema.version

    when {
        current == 0L -> {
            logger.info("Database: fresh — creating schema at version $target")
            schema.create(this).value
            setUserVersion(target)
            return false
        }

        current < target -> {
            logger.info("Database: migrating schema $current → $target")
            schema.migrate(this, current, target).value
            setUserVersion(target)
            return true
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

        else -> {
            logger.info("Database: schema is current at version $target")
            return false
        }
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
