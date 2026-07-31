/**
 * What 36.sqm does to the rows — the half [MigrationTest] cannot see (LNL-215).
 *
 * [MigrationTest] compares PRAGMA output, so the *seeding* half of a migration is
 * invisible to it: `36.db` and `37.db` describe the same tables whether the three
 * `INSERT ... SELECT id FROM projects` statements run, do nothing, or write the wrong
 * flags. [StatusMigrationTest] exists for exactly that gap in 4.sqm, and this is its
 * twin for 36.sqm.
 *
 * ── Why this back-fill is the one that has to be right ──────────────────────
 *
 * Every other column LNL-215 adds back-fills to an absence that means something: a
 * null estimate is "nobody has said", `assignee_is_agent = 0` is "a person does this",
 * `estimate_mode = 'none'` is "we do not estimate". Each is indistinguishable from the
 * state before the column existed, so a migration that wrote nothing at all would
 * still be correct.
 *
 * Relation kinds are not like that. A project with **no** kinds cannot create a
 * relation at all — there is no vocabulary to pick from and the picker renders
 * nothing — so a project the migration skipped does not start empty, it loses the
 * feature outright, silently, and with no way for its administrator to notice
 * anything is missing. And the migration runs once, against a volume holding real
 * boards. That is the shape of failure worth a test.
 *
 * Each test builds a version-36 database from the committed snapshot, fills it with
 * the projects it is about, migrates to the current version, and reads the kinds back
 * in order.
 *
 * @see MigrationTest for the schema half, and StatusMigrationTest for the plumbing
 *   this borrows almost verbatim.
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

/** The version 36.sqm migrates from. */
private const val BEFORE = 36L

/** One relation kind, as the assertions want to read it. */
private data class Kind(val name: String, val inverseName: String?, val marksBlocked: Boolean)

/**
 * What every migrated project must end up with — the same three
 * [DEFAULT_RELATION_KINDS] seeds into a fresh one, in the same order.
 *
 * Spelled out here rather than derived from that constant, and the duplication is the
 * point: this test's job is to catch the two lists drifting apart. Reading the
 * constant would make it agree with whatever the constant said, including a mistake.
 */
private val EXPECTED = listOf(
    Kind("Blocked by", "Blocks", marksBlocked = true),
    Kind("Duplicate of", "Duplicated by", marksBlocked = false),
    // Symmetric: the null inverse is the whole encoding, and it is the field most
    // likely to be quietly written as an empty string by a hand-edited migration.
    Kind("Related to", null, marksBlocked = false),
)

class RelationKindMigrationTest {

    /** The ordinary case: one project, three kinds, in order, with the flags right. */
    @Test
    fun `seeds the three kinds into an existing project`() {
        assertEquals(listOf(EXPECTED), migrateProjects(1))
    }

    /**
     * Several projects in one run, which is what the deployed volume actually is.
     *
     * The statements in 36.sqm are set-based (`SELECT id FROM projects`) rather than a
     * loop, which is what makes them a migration — and also what makes "it worked on
     * the first project" a misleading signal. Every project must get its own three, and
     * they must be its own: the composite key on `issue_relations` means a kind
     * belonging to the wrong project would refuse every link filed against it later.
     */
    @Test
    fun `seeds every project, with each project's kinds its own`() {
        val projects = migrateProjects(3)
        assertEquals(listOf(EXPECTED, EXPECTED, EXPECTED), projects)
    }

    /**
     * An instance with no projects at all — a deployment somebody stood up and never
     * used.
     *
     * `INSERT ... SELECT` over an empty table inserts nothing, which is correct rather
     * than a no-op to be worked around. It is here because the alternative spelling —
     * three unconditional `INSERT ... VALUES` — would fail on the foreign key, and a
     * migration that aborts is a startup that aborts.
     */
    @Test
    fun `writes nothing when there are no projects`() {
        assertTrue(migrateProjects(0).isEmpty())
    }

    /**
     * The positions are 0, 1, 2 — not left to insertion order.
     *
     * `position` has no UNIQUE constraint (it cannot; see VocabularyRepository.reorder),
     * so three rows all at 0 would be accepted by the database and then ordered
     * arbitrarily by every read. The picker would show the three kinds in a different
     * sequence on different loads, which reads as the settings pane rearranging itself.
     */
    @Test
    fun `numbers the seeded kinds from zero`() {
        val positions = withMigrated(1) { driver ->
            driver.queryLongs("SELECT position FROM issue_relation_kinds WHERE project_id = 1 ORDER BY position;")
        }
        assertEquals(listOf(0L, 1L, 2L), positions)
    }

    /**
     * "Related to" is stored with a genuine NULL inverse, not an empty string.
     *
     * The distinction is invisible in most reads and is the whole encoding of symmetry:
     * `inverseName ?: name` resolves the to-side label, so an empty string would render
     * a relation row labelled with nothing at all rather than "Related to". A hand-written
     * migration is exactly where `''` slips in, so it is asserted directly rather than
     * through the record mapping that would paper over it.
     */
    @Test
    fun `stores symmetry as NULL and never as an empty string`() {
        val nulls = withMigrated(1) { driver ->
            driver.queryLongs(
                "SELECT COUNT(*) FROM issue_relation_kinds " +
                    "WHERE project_id = 1 AND inverse_name IS NULL;",
            )
        }
        assertEquals(listOf(1L), nulls)
    }

    /**
     * Exactly one kind marks blocked.
     *
     * "Duplicate of" deliberately does not, and it is the one somebody would reach for:
     * an issue that duplicates another is not *waiting* on it, it **is** it, and marking
     * it blocked would dim both halves of every duplicate pair on the board for no
     * reason anybody could act on. See DEFAULT_RELATION_KINDS.
     */
    @Test
    fun `marks exactly one kind as blocking`() {
        val blocking = withMigrated(1) { driver ->
            driver.queryStrings(
                "SELECT name FROM issue_relation_kinds WHERE project_id = 1 AND marks_blocked = 1;",
            )
        }
        assertEquals(listOf("Blocked by"), blocking)
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /** Seed [count] bare projects into a version-36 database, migrate, and read the kinds back. */
    private fun migrateProjects(count: Int): List<List<Kind>> = withMigrated(count) { driver ->
        (1..count).map { projectId ->
            driver.queryKinds(
                "SELECT name, inverse_name, marks_blocked FROM issue_relation_kinds " +
                    "WHERE project_id = $projectId ORDER BY position;",
            )
        }
    }

    /**
     * Seed [count] projects, migrate to the current version, and run [read].
     *
     * Only the columns a version-36 `projects` row cannot do without are named: the
     * migration's `SELECT id FROM projects` does not care what else is on the row, and
     * naming more of them would make this helper break every time an unrelated column
     * is added.
     */
    private fun <T> withMigrated(count: Int, read: (SqlDriver) -> T): T = withSnapshot(BEFORE) { driver ->
        repeat(count) { index ->
            driver.exec(
                "INSERT INTO projects (name, name_prefix, position, created_at) " +
                    "VALUES ('Project $index', 'P$index', $index, 0);",
            )
        }
        LunicleDatabase.Schema.migrate(driver, BEFORE, LunicleDatabase.Schema.version).value
        read(driver)
    }

    /**
     * Run [block] against a writable copy of the committed `<version>.db`.
     *
     * Foreign keys OFF, exactly as [StatusMigrationTest]'s twin of this helper does and
     * for the reason `Database.migrateSchema` documents: a migration runs on an
     * unenforced connection, so running one here with the constraints on would be
     * testing a connection production never uses.
     */
    private fun <T> withSnapshot(version: Long, block: (SqlDriver) -> T): T {
        val copy = Files.createTempFile("lunicle-relations-$version", ".db").toFile()
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

private fun SqlDriver.queryKinds(sql: String): List<Kind> = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        val rows = mutableListOf<Kind>()
        while (cursor.next().value) {
            rows += Kind(cursor.getString(0).orEmpty(), cursor.getString(1), cursor.getLong(2) == 1L)
        }
        QueryResult.Value(rows.toList())
    },
    parameters = 0,
).value

private fun SqlDriver.queryLongs(sql: String): List<Long> = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        val rows = mutableListOf<Long>()
        while (cursor.next().value) {
            cursor.getLong(0)?.let(rows::add)
        }
        QueryResult.Value(rows.toList())
    },
    parameters = 0,
).value

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
