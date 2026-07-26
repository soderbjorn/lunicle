/**
 * What 8.sqm does to the rows — the half MigrationTest cannot see.
 *
 * MigrationTest compares PRAGMA output, so it proves the `position` column
 * arrives and says nothing about what is in it. That is the whole risk here:
 * `ALTER TABLE ADD COLUMN` can only supply a constant, so without the back-fill
 * every label in a project sits at 0 and the list comes back in whatever order
 * SQLite feels like — a visible, silent scramble of somebody's board, on the one
 * run that ever happens.
 *
 * The promise being tested is that the upgrade changes nothing anybody can see:
 * the seeded order is exactly the alphabetical list the old `ORDER BY name`
 * returned.
 *
 * @see StatusMigrationTest, whose plumbing this borrows
 * @see MigrationTest for the schema half
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

/** The version 8.sqm migrates from. */
private const val BEFORE = 8L

class LabelOrderMigrationTest {

    /** Whatever order the rows were inserted in, the positions come out alphabetical. */
    @Test
    fun `labels keep the alphabetical order they used to be read in`() {
        assertEquals(
            listOf("Bug", "Codebase", "Feature", "Improvement"),
            migrateLabels(listOf("Improvement", "Bug", "Feature", "Codebase")).single(),
        )
    }

    /**
     * `name` is COLLATE NOCASE, and the back-fill compares on that collation.
     *
     * A migration that sorted case-sensitively would put every capitalised name
     * before every lower-case one — a different list from the one the project was
     * being shown the day before.
     */
    @Test
    fun `the back-fill sorts the way the column collates`() {
        assertEquals(
            listOf("apple", "Banana", "cherry"),
            migrateLabels(listOf("cherry", "Banana", "apple")).single(),
        )
    }

    /**
     * Two projects in one run.
     *
     * The back-fill is one set-based UPDATE over the whole table, correlated on
     * `project_id`. A version that lost the correlation would rank every label
     * against every other one and hand the second project positions starting
     * wherever the first left off — which still *sorts* right, so the failure
     * only shows with a project whose labels interleave another's alphabetically.
     */
    @Test
    fun `each project is ranked against its own labels alone`() {
        assertEquals(
            listOf(listOf("Alpha", "Gamma"), listOf("Beta", "Delta")),
            migrateLabels(listOf("Gamma", "Alpha"), listOf("Delta", "Beta")),
        )
    }

    /** Positions start at 0 and have no gaps — what reorder assumes it inherits. */
    @Test
    fun `positions are dense from zero`() {
        assertEquals(
            listOf(0L, 1L, 2L),
            migratePositions(listOf("C", "A", "B")).single(),
        )
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private fun migrateLabels(vararg projects: List<String>): List<List<String>> =
        migrate(projects.toList()) { driver, project ->
            driver.queryStrings(
                "SELECT name FROM labels WHERE project_id = $project ORDER BY position;",
            )
        }

    private fun migratePositions(vararg projects: List<String>): List<List<Long>> =
        migrate(projects.toList()) { driver, project ->
            driver.queryLongs(
                "SELECT position FROM labels WHERE project_id = $project ORDER BY position;",
            )
        }

    /**
     * Seed [projects] into a version-8 database, migrate it, and read each back.
     *
     * The labels are inserted in the order given — deliberately not alphabetical
     * — so that a back-fill which quietly did nothing would leave them all at
     * position 0 and the read-back would return the insertion order, failing.
     */
    private fun <T> migrate(
        projects: List<List<String>>,
        read: (SqlDriver, Int) -> List<T>,
    ): List<List<T>> = withSnapshot(BEFORE) { driver ->
        projects.forEachIndexed { index, labels ->
            driver.exec(
                "INSERT INTO projects (name, name_prefix, is_public, created_at) " +
                    "VALUES ('Project $index', 'P$index', 0, 0);",
            )
            labels.forEach { label ->
                driver.exec("INSERT INTO labels (project_id, name) VALUES (${index + 1}, '$label');")
            }
        }

        LunicleDatabase.Schema.migrate(driver, BEFORE, LunicleDatabase.Schema.version).value

        projects.indices.map { read(driver, it + 1) }
    }

    /** See StatusMigrationTest's helper of the same name for why foreign keys are off. */
    private fun <T> withSnapshot(version: Long, block: (SqlDriver) -> T): T {
        val copy = Files.createTempFile("lunicle-labels-$version", ".db").toFile()
        File("src/main/sqldelight/databases/$version.db").copyTo(copy, overwrite = true)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}")
        return try {
            driver.execute(null, "PRAGMA foreign_keys = OFF;", 0)
            block(driver)
        } finally {
            driver.close()
            copy.delete()
            File("${copy.absolutePath}-wal").delete()
            File("${copy.absolutePath}-shm").delete()
        }
    }
}

private fun SqlDriver.exec(sql: String) = execute(null, sql, 0).value

private fun SqlDriver.queryStrings(sql: String): List<String> = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        val rows = mutableListOf<String>()
        while (cursor.next().value) rows += cursor.getString(0).orEmpty()
        QueryResult.Value(rows.toList())
    },
    parameters = 0,
).value

private fun SqlDriver.queryLongs(sql: String): List<Long> = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        val rows = mutableListOf<Long>()
        while (cursor.next().value) rows += cursor.getLong(0) ?: 0L
        QueryResult.Value(rows.toList())
    },
    parameters = 0,
).value
