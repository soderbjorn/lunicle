/**
 * What 25.sqm does to the rows — the half MigrationTest cannot see.
 *
 * MigrationTest compares PRAGMA output, so 25.sqm is invisible to it: it adds no
 * column, only a role row and a grant per (sys admin, project). But it is the
 * whole of LNL-107's promise that on migration the system administrator becomes
 * the owner of every existing board — it runs once, against a volume holding real
 * projects, and a mistake in it is discovered as "no board has an owner of record"
 * after the one chance to run it has passed. So it is pinned here the way 4.sqm's
 * board-column back-fill is pinned in StatusMigrationTest.
 *
 * Each test builds a version-25 database from the committed snapshot, fills it with
 * the users and projects it is about, migrates to the current version, and reads
 * `project_roles` back.
 *
 * @see MigrationTest for the schema half, and StatusMigrationTest for the pattern.
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

/** The version 25.sqm migrates from. */
private const val OWNER_BEFORE = 25L

class OwnerMigrationTest {

    /**
     * The ordinary case: one system administrator, one ordinary user, two boards.
     *
     * The administrator ends up owning both boards; the ordinary user owns neither.
     * That is the ticket's sentence, and the cross join is what turns "the sys
     * admin owns all projects" into a row per pair.
     */
    @Test
    fun `grants the system administrator ownership of every project`() {
        val owners = migrate(
            users = listOf(User("Sys", isSysAdmin = true), User("Ada", isSysAdmin = false)),
            projectCount = 2,
        )
        // user 1 (the sys admin) owns projects 1 and 2; user 2 owns nothing.
        assertEquals(setOf(1L to 1L, 1L to 2L), owners, "The sys admin did not become the owner of every project.")
    }

    /**
     * Two system administrators: both become owners of every board.
     *
     * The migration reads `is_sys_admin`, not "the first account", so an instance
     * that promoted a second administrator seats both as owners — the honest
     * reading of "the sys admin owns all projects" when there is more than one.
     */
    @Test
    fun `grants every system administrator ownership`() {
        val owners = migrate(
            users = listOf(User("Sys", isSysAdmin = true), User("Ops", isSysAdmin = true)),
            projectCount = 1,
        )
        assertEquals(setOf(1L to 1L, 2L to 1L), owners, "A second system administrator was not seated as owner.")
    }

    /**
     * No projects, or no administrators: nothing to seat, and no failure seating it.
     *
     * An empty cross join inserts nothing, which is correct — and a migration that
     * threw on it would take startup down on exactly the fresh-ish volume least
     * likely to have been tested.
     */
    @Test
    fun `seats nobody when there are no projects`() {
        val owners = migrate(users = listOf(User("Sys", isSysAdmin = true)), projectCount = 0)
        assertEquals(emptySet(), owners, "A grant was written with no project to hang it on.")
    }

    /**
     * The role row itself lands, with the description the enum declares.
     *
     * The verbatim match matters: RoleStore.seed() runs INSERT OR IGNORE after
     * migrations, so whichever writes the row first wins. If this migration's
     * description drifted from the enum's, a migrated instance would carry a
     * different sentence under the checkbox than a fresh one, forever.
     */
    @Test
    fun `seeds the project_owner role with the enum's description`() {
        withSnapshot(OWNER_BEFORE) { driver ->
            LunicleDatabase.Schema.migrate(driver, OWNER_BEFORE, LunicleDatabase.Schema.version).value
            val description = driver.queryOneString(
                "SELECT description FROM roles WHERE role_key = 'project_owner';",
            )
            assertEquals(Role.PROJECT_OWNER.description, description, "25.sqm's role description drifted from the enum.")
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private data class User(val name: String, val isSysAdmin: Boolean)

    /**
     * Seed [users] and [projectCount] projects into a version-25 database, migrate
     * it, and read back the (user_id, project_id) pairs that hold `project_owner`.
     *
     * Ids are 1-based in insertion order, which is what AUTOINCREMENT produces on a
     * table this test is the only writer of.
     */
    private fun migrate(users: List<User>, projectCount: Int): Set<Pair<Long, Long>> =
        withSnapshot(OWNER_BEFORE) { driver ->
            users.forEach { user ->
                driver.exec(
                    "INSERT INTO users (provider, provider_id, provider_name, created_at, is_sys_admin) " +
                        "VALUES ('email', '${user.name}', '${user.name}', 0, ${if (user.isSysAdmin) 1 else 0});",
                )
            }
            repeat(projectCount) { p ->
                driver.exec(
                    "INSERT INTO projects (name, name_prefix, is_public, created_at) " +
                        "VALUES ('Project $p', 'P$p', 0, 0);",
                )
            }

            LunicleDatabase.Schema.migrate(driver, OWNER_BEFORE, LunicleDatabase.Schema.version).value

            driver.queryPairs(
                """
                SELECT pr.user_id, pr.project_id
                FROM project_roles pr
                JOIN roles r ON r.id = pr.role_id
                WHERE r.role_key = 'project_owner';
                """.trimIndent(),
            ).toSet()
        }

    /**
     * Run [block] against a writable copy of the committed `<version>.db`, foreign
     * keys OFF — the connection a migration actually runs on. See
     * StatusMigrationTest.withSnapshot, which this mirrors.
     */
    private fun <T> withSnapshot(version: Long, block: (SqlDriver) -> T): T {
        val copy = Files.createTempFile("lunicle-owner-$version", ".db").toFile()
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

private fun SqlDriver.queryPairs(sql: String): List<Pair<Long, Long>> = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        val rows = mutableListOf<Pair<Long, Long>>()
        while (cursor.next().value) {
            rows += (cursor.getLong(0) ?: 0L) to (cursor.getLong(1) ?: 0L)
        }
        QueryResult.Value(rows.toList())
    },
    parameters = 0,
).value

private fun SqlDriver.queryOneString(sql: String): String? = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
    parameters = 0,
).value
