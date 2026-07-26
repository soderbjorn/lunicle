/**
 * What 4.sqm does to the rows — the half MigrationTest cannot see.
 *
 * MigrationTest compares PRAGMA output, so a migration that only inserts rows is
 * invisible to it: `4.db` and `5.db` describe the same shape and it passes
 * whether 4.sqm runs, does nothing, or does the wrong thing entirely. That gap
 * would not matter for a schema migration. It matters here, because 4.sqm is the
 * only thing that gives an *existing* project its "Ready for test" column, it
 * runs exactly once against a volume holding real boards, and a mistake in it is
 * discovered as "the new column is missing on the projects anyone actually uses"
 * after the one chance to run it has passed.
 *
 * Each test builds a version-4 database from the committed snapshot, fills it
 * with the board it is about, migrates to the current version, and reads the
 * columns back in order. The cases are the four shapes the deployed volume could
 * plausibly be in — not hypotheticals: every one of them is reachable through
 * the project settings dialog.
 *
 * @see MigrationTest for the schema half, and for the plumbing this borrows.
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

/** The version 4.sqm migrates from. */
private const val BEFORE = 4L

/** One board column, as the assertions want to read it. */
private data class Column(val name: String, val requiresResolution: Boolean)

class StatusMigrationTest {

    /**
     * The ordinary project: the seeded five, untouched.
     *
     * "Ready for test" lands between "In progress" and "Closed", and Closed keeps
     * the resolution flag to itself — the new column is work handed over, not
     * work finished.
     */
    @Test
    fun `inserts the new column before Closed`() {
        val columns = migrateBoard(
            Column("New", false),
            Column("Backlog", false),
            Column("Ready for development", false),
            Column("In progress", false),
            Column("Closed", true),
        )
        assertEquals(
            listOf(
                Column("New", false),
                Column("Backlog", false),
                Column("Ready for development", false),
                Column("In progress", false),
                Column("Ready for test", false),
                Column("Closed", true),
            ),
            columns,
        )
    }

    /**
     * The reason 4.sqm anchors on `requires_resolution` instead of on the name.
     *
     * This project renamed its closing column to "Done". A migration written as
     * `WHERE name = 'Closed'` passes every other test in this file and silently
     * does nothing here — which is the failure the anchor exists to prevent, so
     * it is pinned.
     */
    @Test
    fun `anchors on the resolution flag, not the name Closed`() {
        val columns = migrateBoard(
            Column("New", false),
            Column("In progress", false),
            Column("Done", true),
        )
        assertEquals(
            listOf(
                Column("New", false),
                Column("In progress", false),
                Column("Ready for test", false),
                Column("Done", true),
            ),
            columns,
        )
    }

    /**
     * A project with no closing column at all — reachable, because the settings
     * dialog can turn the flag off everywhere.
     *
     * There is no "before Closed" to insert before, so the column goes on the
     * end. Appending is not the ideal answer; it is the only one that is not a
     * guess about which of these columns the admin thinks means "done".
     */
    @Test
    fun `appends when no column requires a resolution`() {
        val columns = migrateBoard(
            Column("New", false),
            Column("In progress", false),
        )
        assertEquals(
            listOf(
                Column("New", false),
                Column("In progress", false),
                Column("Ready for test", false),
            ),
            columns,
        )
    }

    /**
     * A project whose admin already made the column by hand.
     *
     * `UNIQUE (project_id, name)` sits on a COLLATE NOCASE column, so this is not
     * a duplicate row but an aborted migration — and an aborted migration is an
     * aborted startup, on the deployed volume, for every project at once. The
     * lower-case name is the point: it is what makes the constraint fire and the
     * naive `NOT EXISTS` on a case-sensitive comparison miss.
     *
     * The board must come back exactly as it went in. No second column, and no
     * shifted positions either — a project skipped by the insert but not by the
     * shift would come out with a gap where nothing sits.
     */
    @Test
    fun `leaves a project that already has the name alone`() {
        val before = listOf(
            Column("New", false),
            Column("ready for test", false),
            Column("Closed", true),
        )
        assertEquals(before, migrateBoard(*before.toTypedArray()))
    }

    /**
     * Two projects, migrated by one run, with different shapes.
     *
     * The statements in 4.sqm are set-based rather than per-project, which is
     * what makes them a migration rather than a loop — and also what makes a
     * missing correlation clause easy to write. A correlated subquery that lost
     * its `project_id` match would compute one anchor across the whole table and
     * put the new column in the wrong place on the second board while looking
     * right on the first.
     */
    @Test
    fun `handles several projects with different boards in one run`() {
        val boards = migrateBoards(
            listOf(Column("New", false), Column("In progress", false), Column("Closed", true)),
            listOf(Column("Triage", false), Column("Shipped", true), Column("Archived", false)),
        )
        assertEquals(
            listOf(
                listOf(
                    Column("New", false),
                    Column("In progress", false),
                    Column("Ready for test", false),
                    Column("Closed", true),
                ),
                // The anchor is the flagged column, so the new one goes before
                // "Shipped" — not at the end, and not before "Archived".
                listOf(
                    Column("Triage", false),
                    Column("Ready for test", false),
                    Column("Shipped", true),
                    Column("Archived", false),
                ),
            ),
            boards,
        )
    }

    /**
     * The closing column at position 0 — the arithmetic edge in 4.sqm's INSERT.
     *
     * The insert reads the anchor's position back *after* the shift and
     * subtracts one, which is what keeps this at 0 rather than at -1. A version
     * that used the pre-shift value would produce a negative position here: not
     * a constraint violation, since `position` is a plain INTEGER, but a column
     * that sorts before everything on a board it should sort last on.
     */
    @Test
    fun `handles a closing column at position zero`() {
        val columns = migrateBoard(
            Column("Closed", true),
            Column("New", false),
        )
        assertEquals(
            listOf(
                Column("Ready for test", false),
                Column("Closed", true),
                Column("New", false),
            ),
            columns,
        )
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /** [migrateBoards] for the common case of one project. */
    private fun migrateBoard(vararg columns: Column): List<Column> =
        migrateBoards(columns.toList()).single()

    /**
     * Seed [boards] into a version-4 database, migrate it, and read them back.
     *
     * Positions are the index within each board, which is what the running code
     * produces and therefore what the volume holds. The read-back orders by
     * `position` rather than by rowid on purpose: the inserted row is the newest
     * one in the table and would come last under rowid order in every case,
     * which would make every assertion above pass for the wrong reason.
     */
    private fun migrateBoards(vararg boards: List<Column>): List<List<Column>> =
        withSnapshot(BEFORE) { driver ->
            boards.forEachIndexed { project, columns ->
                driver.exec(
                    "INSERT INTO projects (name, name_prefix, is_public, created_at) " +
                        "VALUES ('Project $project', 'P$project', 0, 0);",
                )
                columns.forEachIndexed { position, column ->
                    driver.exec(
                        "INSERT INTO statuses (project_id, name, position, requires_resolution) " +
                            "VALUES (${project + 1}, '${column.name}', $position, " +
                            "${if (column.requiresResolution) 1 else 0});",
                    )
                }
            }

            LunicleDatabase.Schema.migrate(driver, BEFORE, LunicleDatabase.Schema.version).value

            boards.indices.map { project ->
                driver.queryRows(
                    "SELECT name, requires_resolution FROM statuses " +
                        "WHERE project_id = ${project + 1} ORDER BY position;",
                )
            }
        }

    /**
     * Run [block] against a writable copy of the committed `<version>.db`.
     *
     * A near-copy of MigrationTest's helper of the same name, and deliberately
     * not shared with it: that one opens with foreign keys ON, because checking
     * that they are enforced is its whole job. This one must open them OFF, for
     * the reason Database.migrateSchema documents — a migration runs on an
     * unenforced connection, and running one here with the constraints on would
     * be testing a connection production never uses.
     */
    private fun <T> withSnapshot(version: Long, block: (SqlDriver) -> T): T {
        val copy = Files.createTempFile("lunicle-status-$version", ".db").toFile()
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

private fun SqlDriver.queryRows(sql: String): List<Column> = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        val rows = mutableListOf<Column>()
        while (cursor.next().value) {
            rows += Column(cursor.getString(0).orEmpty(), cursor.getLong(1) == 1L)
        }
        QueryResult.Value(rows.toList())
    },
    parameters = 0,
).value
