/**
 * The migration gate, in place of SQLDelight's own.
 *
 * `verifyMigrations` is switched off in server/build.gradle.kts — it cannot
 * finish on a schema with this many cross-referenced tables, for the reason
 * documented there. This test does the job it was doing, in milliseconds, and
 * checks the same fact:
 *
 *   **applying every .sqm in order to the previous version's snapshot must land
 *   on exactly the schema the .sq files declare.**
 *
 * If that ever stops being true, the failure it prevents is the worst kind:
 * silent, production-only, and discovered as "every query against `projects`
 * fails" against a database that believes it is up to date. No local run
 * reproduces it, because a local volume gets wiped and takes the `create` path.
 *
 * ── The baseline reset ──────────────────────────────────────────────────────
 *
 * There are no .sqm files at the moment, so the migration half of this file
 * currently proves nothing: the schema was collapsed to a version-1 baseline,
 * every volume was wiped, and versions 1–6 and the five migrations between them
 * were deleted rather than carried. That is deliberate and it is a one-off —
 * everything from this baseline forward gets a migration, and the moment the
 * first `1.sqm` lands, the test below has something to check again.
 *
 * The file stays as it is in the meantime. It is not dead: `the current snapshot
 * matches the sq files` is what catches an edited .sq with no re-snapshot, which
 * is the ordinary mistake and is live today.
 *
 * What it compares is structure — columns, types, nullability, defaults, primary
 * keys, foreign keys (including their `ON DELETE` behaviour), and indexes — read
 * back out of SQLite via `PRAGMA`. Deliberately not the DDL *text*: the .sq
 * files carry their reasoning in comments and a .sqm does not, so the two differ
 * by several kilobytes of prose while describing identical tables. Text
 * comparison would fail on every one of those and prove nothing.
 */
package se.soderbjorn.lunicle

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import se.soderbjorn.lunicle.db.LunicleDatabase
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the committed `<version>.db` snapshots live.
 *
 * Same directory `schemaOutputDirectory` names in build.gradle.kts. Resolved
 * from the project directory rather than the classpath because these are build
 * *inputs* that are never packaged — they exist to be diffed against, not run.
 */
private val SCHEMA_DIR = File("src/main/sqldelight/databases")

/** One structural fact about the schema, as SQLite reports it. */
private typealias SchemaFacts = List<String>

class MigrationTest {

    /**
     * The headline: migrating an old volume produces the same schema as
     * creating a new one.
     *
     * The two paths this covers are the two that exist in production, and only
     * one of them is ever exercised locally:
     *  - `create` — a fresh volume. Every local run takes this.
     *  - `migrate` — the deployed volume, which already has `user_version` set.
     *    This is the path that only ever runs for real.
     */
    @Test
    fun `migrating from every earlier version lands on the current schema`() {
        val target = LunicleDatabase.Schema.version
        val expected = withDriver { driver ->
            LunicleDatabase.Schema.create(driver).value
            driver.schemaFacts()
        }

        val snapshots = snapshotVersions()
        assertTrue(
            snapshots.isNotEmpty(),
            "No <version>.db snapshots in $SCHEMA_DIR. Run :server:generateMainLunicleDatabaseSchema.",
        )

        snapshots.filter { it < target }.forEach { from ->
            val migrated = withSnapshot(from) { driver ->
                LunicleDatabase.Schema.migrate(driver, from, target).value
                driver.schemaFacts()
            }
            assertSchemaEquals(
                expected = expected,
                actual = migrated,
                message = "Migrating $from → $target does not produce the schema the .sq files declare",
            )
        }
    }

    /**
     * The snapshot for the *current* version must match the .sq files too.
     *
     * This is the half that catches the ordinary mistake: editing a .sq and
     * forgetting to re-run `generateMainLunicleDatabaseSchema`. Without it, the
     * test above would happily verify a migration against a stale snapshot and
     * both would agree with each other while disagreeing with the code.
     */
    @Test
    fun `the current snapshot matches the sq files`() {
        val target = LunicleDatabase.Schema.version
        val expected = withDriver { driver ->
            LunicleDatabase.Schema.create(driver).value
            driver.schemaFacts()
        }
        assertTrue(
            target in snapshotVersions(),
            "There is no $target.db in $SCHEMA_DIR. Run :server:generateMainLunicleDatabaseSchema " +
                "and commit the result beside the .sq files.",
        )
        val snapshot = withSnapshot(target) { it.schemaFacts() }
        assertSchemaEquals(
            expected = expected,
            actual = snapshot,
            message = "$target.db is stale — a .sq file changed without a matching " +
                "generateMainLunicleDatabaseSchema",
        )
    }

    /**
     * The `roles` table starts empty and is filled at startup.
     *
     * Pinned as a test because it is the one deliberate departure from the
     * schema doc, which put the seed in the schema *and* in the create branch.
     * If someone later adds the INSERTs back to a .sq, this fails and points at
     * RoleStore.seed — where the single, idempotent seed lives.
     *
     * Against `create` rather than against a migration, which is where it used
     * to point: the .sqm that used to be the risk is gone, and `create` is now
     * the only path that builds the table at all. The invariant is the same one
     * and it is worth more here — every fresh volume takes this path.
     */
    @Test
    fun `creating the schema does not seed roles`() {
        val count = withDriver { driver ->
            LunicleDatabase.Schema.create(driver).value
            driver.executeQuery(
                identifier = null,
                sql = "SELECT count(*) FROM roles;",
                mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
                parameters = 0,
            ).value
        }
        assertEquals(0L, count, "The schema seeds roles; RoleStore.seed() is the only place that should.")
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /** Every `<version>.db` in the schema directory, as numbers. */
    private fun snapshotVersions(): List<Long> =
        SCHEMA_DIR.listFiles().orEmpty()
            .mapNotNull { it.name.removeSuffix(".db").toLongOrNull()?.takeIf { _ -> it.name.endsWith(".db") } }
            .sorted()

    /**
     * Run [block] against an empty database.
     *
     * A real file rather than `:memory:`, because these tests exist to check
     * what SQLite actually writes to a volume, and an in-memory database is one
     * more way for the test's SQLite to differ from production's.
     */
    private fun <T> withDriver(block: (SqlDriver) -> T): T {
        val file = Files.createTempFile("lunicle-schema", ".db").toFile()
        file.delete()
        return openAndUse(file, block)
    }

    /** Run [block] against a working copy of the committed `<version>.db`. */
    private fun <T> withSnapshot(version: Long, block: (SqlDriver) -> T): T {
        val source = File(SCHEMA_DIR, "$version.db")
        assertTrue(source.isFile, "Missing schema snapshot ${source.path}")
        val copy = Files.createTempFile("lunicle-$version", ".db").toFile()
        source.copyTo(copy, overwrite = true)
        return openAndUse(copy, block)
    }

    private fun <T> openAndUse(file: File, block: (SqlDriver) -> T): T {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        return try {
            // The same pragma Database.kt sets. Without it SQLite parses foreign
            // keys and does not enforce them — and this test reads them back, so
            // it would be checking decoration.
            driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
            block(driver)
        } finally {
            driver.close()
            file.delete()
            // SQLite leaves these beside the file; a temp directory slowly
            // filling with them is nobody's idea of a good time.
            File("${file.absolutePath}-wal").delete()
            File("${file.absolutePath}-shm").delete()
        }
    }
}

/** Every table, alphabetically. SQLite's own bookkeeping excluded. */
private fun SqlDriver.tableNames(): List<String> = queryStrings(
    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name;",
)

/**
 * The schema, as a sorted list of structural facts.
 *
 * One string per column, foreign key and index, phrased so that a failure reads
 * as a sentence rather than a diff of two blobs. Sorted so that the order
 * SQLite happens to return things in — which is not part of the schema — cannot
 * fail the test.
 */
private fun SqlDriver.schemaFacts(): SchemaFacts {
    val tables = tableNames()
    return tables.flatMap { table ->
        queryStrings(
            """
            SELECT '$table.' || name || ': ' || type
                || ' notnull=' || "notnull"
                || ' default=' || COALESCE(dflt_value, '-')
                || ' pk=' || pk
            FROM pragma_table_info('$table');
            """.trimIndent(),
        ) + queryStrings(
            """
            SELECT '$table FK ' || "from" || ' -> ' || "table" || '.' || COALESCE("to", 'rowid')
                || ' on_delete=' || on_delete || ' on_update=' || on_update
            FROM pragma_foreign_key_list('$table');
            """.trimIndent(),
        ) + queryStrings(
            """
            SELECT '$table INDEX on (' || (
                SELECT group_concat(COALESCE(ii.name, '<expr>'), ',')
                FROM pragma_index_info(il.name) ii
            ) || ') unique=' || il."unique" || ' origin=' || il.origin
            FROM pragma_index_list('$table') il;
            """.trimIndent(),
        )
    }.sorted()
}

/** Run a query returning one text column. */
private fun SqlDriver.queryStrings(sql: String): List<String> = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        val rows = mutableListOf<String>()
        while (cursor.next().value) {
            cursor.getString(0)?.let(rows::add)
        }
        QueryResult.Value(rows.toList())
    },
    parameters = 0,
).value

/**
 * Compare two schemas and, on failure, say precisely what differs.
 *
 * A bare `assertEquals` on two 140-element lists prints both in full and leaves
 * the reader to spot the line that moved. This prints only the difference, in
 * both directions, because "missing" and "unexpected" are different bugs:
 * missing means the migration forgot something, unexpected means it added
 * something the .sq files do not declare.
 */
private fun assertSchemaEquals(expected: SchemaFacts, actual: SchemaFacts, message: String) {
    if (expected == actual) return
    val missing = expected - actual.toSet()
    val unexpected = actual - expected.toSet()
    val detail = buildString {
        appendLine(message)
        if (missing.isNotEmpty()) {
            appendLine()
            appendLine("Declared by the .sq files but MISSING after migrating:")
            missing.forEach { appendLine("  - $it") }
        }
        if (unexpected.isNotEmpty()) {
            appendLine()
            appendLine("Present after migrating but NOT declared by the .sq files:")
            unexpected.forEach { appendLine("  + $it") }
        }
    }
    throw AssertionError(detail)
}
