/**
 * What 10.sqm does to the rows — the half MigrationTest cannot see.
 *
 * [StatusMigrationTest]'s twin, and it exists for the same reason: MigrationTest
 * compares PRAGMA output, so a migration that only inserts rows is invisible to
 * it. `10.db` and `11.db` describe the same shape and it passes whether 10.sqm
 * runs, does nothing, or does the wrong thing entirely. That gap matters here
 * because 10.sqm is the only thing that gives an *existing* project its
 * "Duplicate" resolution, it runs exactly once against a volume holding real
 * boards, and a mistake in it is discovered as "the new resolution is missing on
 * the projects anyone actually uses" after the one chance to run it has passed.
 *
 * Each test builds a version-10 database from the committed snapshot, fills it
 * with the vocabulary it is about, migrates to the current version, and reads the
 * resolutions back in order. The plumbing is StatusMigrationTest's, transposed to
 * a table with no `requires_resolution` on it; the two are deliberately not
 * shared, because the shapes they seed have nothing in common but the word
 * "position".
 *
 * @see MigrationTest for the schema half.
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

/** The version 10.sqm migrates from. */
private const val BEFORE = 10L

class ResolutionMigrationTest {

    /**
     * The ordinary project: the seeded two, untouched.
     *
     * "Duplicate" lands last. Appending rather than inserting is what keeps the
     * positions of "Done" and "Will not fix" exactly where they were — the board
     * groups closed issues by this order, so a shift would silently re-group
     * every closed issue on every board.
     */
    @Test
    fun `appends the new resolution`() {
        assertEquals(
            listOf("Done", "Will not fix", "Duplicate"),
            migrateResolutions("Done", "Will not fix"),
        )
    }

    /**
     * A project that added resolutions of its own through the settings dialog.
     *
     * The new position is read from MAX(position) rather than assumed to be 2,
     * which is what stops it colliding with the row already sitting there. A
     * collision would not be a duplicate row — `position` has no UNIQUE — it
     * would be two resolutions sharing a slot and ordering arbitrarily.
     */
    @Test
    fun `appends after resolutions the project added itself`() {
        assertEquals(
            listOf("Done", "Will not fix", "Cannot reproduce", "Duplicate"),
            migrateResolutions("Done", "Will not fix", "Cannot reproduce"),
        )
    }

    /**
     * A project whose admin already made the resolution by hand.
     *
     * `UNIQUE (project_id, name)` sits on a COLLATE NOCASE column, so this is not
     * a duplicate row but an aborted migration — and an aborted migration is an
     * aborted startup, on the deployed volume, for every project at once. The
     * lower-case name is the point: it is what makes the constraint fire and a
     * naive case-sensitive `NOT EXISTS` miss.
     */
    @Test
    fun `leaves a project that already has the name alone`() {
        val before = listOf("Done", "duplicate")
        assertEquals(before, migrateResolutions(*before.toTypedArray()))
    }

    /**
     * A project that deleted every resolution it had.
     *
     * Reachable — the settings dialog deletes a resolution no closed issue holds,
     * and nothing stops an admin doing that to all of them. The COALESCE tail is
     * what makes this a row at position 0 rather than a NULL `position`, which is
     * NOT NULL and would abort startup.
     */
    @Test
    fun `handles a project with no resolutions at all`() {
        assertEquals(listOf("Duplicate"), migrateResolutions())
    }

    /**
     * Two projects, migrated by one run, with different vocabularies.
     *
     * The statement in 10.sqm is set-based rather than per-project, which is what
     * makes it a migration rather than a loop — and also what makes a missing
     * correlation clause easy to write. A subquery that lost its `project_id`
     * match would compute one MAX across the whole table and land the new row at
     * the wrong position on the shorter board while looking right on the longer.
     */
    @Test
    fun `handles several projects with different vocabularies in one run`() {
        assertEquals(
            listOf(
                listOf("Done", "Will not fix", "Duplicate"),
                listOf("Shipped", "Duplicate"),
            ),
            migrateProjects(
                listOf("Done", "Will not fix"),
                listOf("Shipped"),
            ),
        )
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /** [migrateProjects] for the common case of one project. */
    private fun migrateResolutions(vararg names: String): List<String> =
        migrateProjects(names.toList()).single()

    /**
     * Seed [projects] into a version-10 database, migrate it, and read them back.
     *
     * Positions are the index within each project, which is what the running code
     * produces and therefore what the volume holds. The read-back orders by
     * `position` rather than by rowid on purpose: the inserted row is the newest
     * one in the table and would come last under rowid order in every case, which
     * would make every assertion above pass for the wrong reason.
     */
    private fun migrateProjects(vararg projects: List<String>): List<List<String>> =
        withSnapshot(BEFORE) { driver ->
            projects.forEachIndexed { project, names ->
                driver.exec(
                    "INSERT INTO projects (name, name_prefix, is_public, created_at) " +
                        "VALUES ('Project $project', 'P$project', 0, 0);",
                )
                names.forEachIndexed { position, name ->
                    driver.exec(
                        "INSERT INTO resolutions (project_id, name, position) " +
                            "VALUES (${project + 1}, '$name', $position);",
                    )
                }
            }

            LunicleDatabase.Schema.migrate(driver, BEFORE, LunicleDatabase.Schema.version).value

            projects.indices.map { project ->
                driver.queryNames(
                    "SELECT name FROM resolutions WHERE project_id = ${project + 1} ORDER BY position;",
                )
            }
        }

    /**
     * Run [block] against a writable copy of the committed `<version>.db`.
     *
     * StatusMigrationTest's helper, and duplicated from it for the reason that one
     * gives about MigrationTest's: foreign keys must be OFF here, because
     * `Database.migrateSchema` runs migrations on an unenforced connection and a
     * test that turned them on would be testing a connection production never
     * uses.
     */
    private fun <T> withSnapshot(version: Long, block: (SqlDriver) -> T): T {
        val copy = Files.createTempFile("lunicle-resolution-$version", ".db").toFile()
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

private fun SqlDriver.queryNames(sql: String): List<String> = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        val rows = mutableListOf<String>()
        while (cursor.next().value) {
            rows += cursor.getString(0).orEmpty()
        }
        QueryResult.Value(rows.toList())
    },
    parameters = 0,
).value
