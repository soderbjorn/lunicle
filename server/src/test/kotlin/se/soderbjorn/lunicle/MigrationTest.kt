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
 * ── The baseline resets, and why they stopped ───────────────────────────────
 *
 * Twice, this schema was collapsed to a version-1 baseline and every volume
 * wiped rather than migrated — the first time discarding versions 1–6 and the
 * five migrations between them, the second time adding `created_by_external` to
 * issues, comments and attachments by editing the .sq files in place and
 * re-cutting `1.db`. An earlier version of this comment called the first reset a
 * one-off and promised a migration for everything after it. That promise was
 * made and then not kept, so it was not repeated.
 *
 * The rule that replaced it was narrower, and it was a rule about the *data*,
 * not about good intentions: while the volume held nothing anyone would miss,
 * re-cutting the baseline was cheaper than a migration and lost nothing. The
 * moment it held something real, that stopped being true instantly and
 * completely — there is no gradient between those two states. It said the
 * question to answer before changing the schema was not "is a migration
 * warranted" but "is the volume still disposable", and that the answer was not
 * in this repo but in the deployment.
 *
 * **That question is now answered, and the answer is no.** The deployed volume
 * holds boards people rely on. A wipe is data loss. Every schema change from
 * here needs an `<n>.sqm`, a version bump and a fresh snapshot — no exceptions,
 * and no more re-cutting the baseline. The migrations from version 1 onwards are
 * committed and this test is doing the job it was written for; do not read the
 * history above as licence to add to it.
 *
 * Note that "schema change" is not the only thing that earns a migration. 4.sqm
 * changes no schema at all — it gives every existing project the board column
 * that new projects get from ProjectRepository's seed. This test is blind to
 * that one by construction, since PRAGMA output cannot see a row; see
 * StatusMigrationTest, which covers it.
 *
 * One gap worth knowing, since a CHECK now carries real weight here: what this
 * test compares is `PRAGMA` output, and SQLite does not report CHECK
 * constraints through any pragma. The exclusivity between `created_by` and
 * `created_by_external` is therefore invisible to the comparison below. It is
 * covered by tests that try to insert both and expect a constraint violation —
 * which is the better check anyway, being about behaviour rather than shape.
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
     * A fresh volume grants nobody anything.
     *
     * This test used to say "creating the schema does not seed roles", about the
     * `roles` vocabulary table that LNL-191 removed — a rung's name is the only thing
     * stored and its description is on the [ProjectRole] enum, so there is no
     * vocabulary to seed and no startup seed to keep out of the schema.
     *
     * What it was really protecting is worth more now than it was then, so it is
     * kept pointed at the two tables that replaced it: **a fresh database contains no
     * grant of any kind.** A `.sq` that seeded a row here would hand somebody a rung
     * on every new deployment, which is the same class of mistake in a place where
     * it is much harder to see — and it is exactly what 33.sqm refuses to do on a
     * migrated volume, so the two paths agree by both being empty.
     */
    @Test
    fun `creating the schema grants nobody anything`() {
        val counts = withDriver { driver ->
            LunicleDatabase.Schema.create(driver).value
            listOf("project_roles", "project_audience_roles").map { table ->
                table to driver.executeQuery(
                    identifier = null,
                    sql = "SELECT count(*) FROM $table;",
                    mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
                    parameters = 0,
                ).value
            }
        }
        counts.forEach { (table, count) ->
            assertEquals(0L, count, "A fresh schema seeds $table; nothing should ever grant on creation.")
        }
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
