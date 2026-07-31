/**
 * What 33.sqm does to the rows — the half [MigrationTest] cannot see.
 *
 * MigrationTest compares PRAGMA output, so it proves the *shape* a migrated volume
 * ends up with and says nothing about what is in it. The whole of LNL-191's
 * migration promise is about what is in it: **it carries every person and grants
 * nobody anything**, with exactly two exceptions — the instance owner, and one
 * project owner per board so no board is unadministrable.
 *
 * That is a promise you get one chance to keep. A mistake here is discovered as
 * "everybody kept the rights the rework was supposed to take away", or as "nobody
 * can grant anything at all", on a volume, after the run.
 *
 * This file replaces OwnerMigrationTest, which pinned 25.sqm's grant of
 * `project_owner` to the system administrator on every project. That migration's
 * output is precisely this one's *input* — see the note in
 * [`the unambiguous old owner keeps the board`] — so the coverage moved forward
 * rather than being dropped.
 *
 * Each test builds a version-33 database from the committed snapshot, fills it with
 * the users, projects and grants it is about, migrates to the current version, and
 * reads the new tables back.
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The version 33.sqm migrates from. */
private const val PERMISSIONS_BEFORE = 33L

class PermissionMigrationTest {

    /**
     * Everybody carries over, and everybody arrives with nothing.
     *
     * The old volume here is as privileged as one gets: an ordinary user holding
     * four issue roles on one board and `project_admin` on another. Afterwards they
     * are on no rung anywhere. That is the ticket's decision — there is no honest
     * translation from a *set* of keys to a *cumulative* rung, and a guess in this
     * direction hands somebody a power nobody chose to give them.
     */
    @Test
    fun `every account carries over and nobody carries a privilege`() {
        val after = migrate {
            addUser("sys", isSysAdmin = true)
            addUser("ada")
            addProject("Alpha")
            addProject("Beta")
            grant(user = 2, project = 1, role = "create_issue")
            grant(user = 2, project = 1, role = "comment_on_issue")
            grant(user = 2, project = 1, role = "change_unowned_issues")
            grant(user = 2, project = 1, role = "be_assigned_issue")
            grant(user = 2, project = 2, role = "project_admin")
        }

        assertEquals(listOf("sys", "ada"), after.userNames, "an account was lost, or reordered")
        assertEquals(
            emptyMap(),
            after.rungs.filterKeys { it.first == 2L },
            "an ordinary account carried a privilege across; this migration grants nothing",
        )
        assertTrue(after.audiences.isEmpty(), "an audience row was invented; no project is published by a migration")
    }

    /**
     * The system administrator becomes the instance owner — the single exception,
     * and the reason the deployment is not a brick on the morning after.
     *
     * `instance_role` is left null even for them: they are the *owner*, which is
     * senior to administrator, and stating the same authority twice is how the two
     * come to disagree after a transfer.
     */
    @Test
    fun `the system administrator becomes the instance owner and nothing else`() {
        val after = migrate {
            addUser("sys", isSysAdmin = true)
            addUser("ada")
        }
        assertEquals(1L, after.ownerUserId, "the system administrator did not become the instance owner")
        assertEquals(listOf(null, null), after.instanceRoles, "an account was carried across as an administrator")
    }

    /** Two flagged accounts: the lowest id owns the instance, and the other arrives ordinary. */
    @Test
    fun `a second system administrator arrives as an ordinary account`() {
        val after = migrate {
            addUser("sys", isSysAdmin = true)
            addUser("ops", isSysAdmin = true)
        }
        assertEquals(1L, after.ownerUserId)
        assertEquals(listOf(null, null), after.instanceRoles)
    }

    /**
     * A board whose old `project_owner` is unambiguous keeps that person.
     *
     * Worth stating what "unambiguous" resolves to on a real volume: **25.sqm
     * granted `project_owner` to the system administrator on every project**, so
     * unless ownership was handed to somebody else since, the sole holder *is* the
     * system administrator and this branch and the fallback land on the same person.
     * The branch still earns its place — this test is the volume where ownership was
     * handed on, and there it is the only one that gets the right answer.
     */
    @Test
    fun `the unambiguous old owner keeps the board`() {
        val after = migrate {
            addUser("sys", isSysAdmin = true)
            addUser("ada")
            addProject("Alpha")
            grant(user = 2, project = 1, role = "project_owner")
        }
        assertEquals(mapOf((2L to 1L) to "owner"), after.rungs, "the old owner did not keep their board")
    }

    /** Two old owners: neither can be picked without inventing a rule, so the instance owner takes it. */
    @Test
    fun `an ambiguous board falls to the instance owner`() {
        val after = migrate {
            addUser("sys", isSysAdmin = true)
            addUser("ada")
            addUser("bo")
            addProject("Alpha")
            grant(user = 2, project = 1, role = "project_owner")
            grant(user = 3, project = 1, role = "project_owner")
        }
        assertEquals(mapOf((1L to 1L) to "owner"), after.rungs)
    }

    /** A board nobody owned falls to the instance owner too — no board is left unadministrable. */
    @Test
    fun `an unowned board falls to the instance owner`() {
        val after = migrate {
            addUser("sys", isSysAdmin = true)
            addProject("Alpha")
            addProject("Beta")
        }
        assertEquals(mapOf((1L to 1L) to "owner", (1L to 2L) to "owner"), after.rungs)
    }

    /**
     * An instance with no system administrator at all migrates without seating one.
     *
     * A deployment that already had nobody in charge is not one this migration
     * invents an owner for — and, more to the point, it must not *fail*, because it
     * is exactly the fresh-ish volume least likely to have been tested. Nothing is
     * seated and nothing throws.
     */
    @Test
    fun `an instance with no administrator seats nobody and does not fail`() {
        val after = migrate {
            addUser("ada")
            addProject("Alpha")
        }
        assertNull(after.ownerUserId)
        assertTrue(after.rungs.isEmpty())
    }

    /**
     * **A migrated database and a fresh one end up identical.**
     *
     * Not merely in shape — MigrationTest holds that line — but in *content*: an
     * empty version-33 volume migrated forward has exactly the rows a `Schema.create`
     * gives, which is none. If a future edit to 33.sqm ever seeded so much as a
     * default audience row, this is what would notice, and it would notice on the one
     * path where the difference is invisible: the deployment that has never had a
     * project.
     */
    @Test
    fun `an empty migrated database is indistinguishable from a fresh one`() {
        val migrated = migrate { }
        val fresh = withDatabase { driver ->
            LunicleDatabase.Schema.create(driver).value
            driver.readState()
        }
        assertEquals(fresh, migrated, "a migrated empty volume differs from a created one")
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /** The rows this migration is about, read back after it has run. */
    private data class State(
        val userNames: List<String>,
        val instanceRoles: List<String?>,
        val ownerUserId: Long?,
        /** (user_id, project_id) → rung key. */
        val rungs: Map<Pair<Long, Long>, String>,
        /** (project_id, audience) → rung key. */
        val audiences: Map<Pair<Long, String>, String>,
    )

    /** Statements against the *old* schema, so a test reads as the volume it describes. */
    private class Before(private val driver: SqlDriver) {
        fun addUser(name: String, isSysAdmin: Boolean = false) = driver.exec(
            "INSERT INTO users (provider, provider_id, provider_name, created_at, is_sys_admin) " +
                "VALUES ('email', '$name', '$name', 0, ${if (isSysAdmin) 1 else 0});",
        )

        fun addProject(name: String) = driver.exec(
            "INSERT INTO projects (name, name_prefix, is_public, created_at) " +
                "VALUES ('$name', '${name.take(3).uppercase()}', 0, 0);",
        )

        /**
         * A grant in the old shape, seeding the `roles` row it needs on the way.
         *
         * The seed is here rather than in each test because the old `project_roles`
         * names a `role_id`: a grant cannot exist before its vocabulary row does, and
         * that ordering is a fact about the schema being migrated *from*, not about
         * anything a test is asserting.
         */
        fun grant(user: Long, project: Long, role: String) {
            driver.exec("INSERT OR IGNORE INTO roles (role_key, description) VALUES ('$role', '$role');")
            driver.exec(
                "INSERT INTO project_roles (user_id, project_id, role_id) " +
                    "SELECT $user, $project, id FROM roles WHERE role_key = '$role';",
            )
        }
    }

    /** Seed a version-33 volume through [before], migrate it, and read the new tables back. */
    private fun migrate(before: Before.() -> Unit): State =
        withSnapshot(PERMISSIONS_BEFORE) { driver ->
            Before(driver).before()
            LunicleDatabase.Schema.migrate(driver, PERMISSIONS_BEFORE, LunicleDatabase.Schema.version).value
            driver.readState()
        }

    private fun SqlDriver.readState(): State = State(
        userNames = queryStrings("SELECT provider_name FROM users ORDER BY id;"),
        instanceRoles = queryNullableStrings("SELECT instance_role FROM users ORDER BY id;"),
        ownerUserId = queryStrings("SELECT value FROM instance_settings WHERE key = 'owner_user_id';")
            .firstOrNull()?.toLongOrNull(),
        rungs = queryStrings("SELECT user_id || '|' || project_id || '|' || role FROM project_roles;")
            .associate { row ->
                val (user, project, role) = row.split('|')
                (user.toLong() to project.toLong()) to role
            },
        audiences = queryStrings("SELECT project_id || '|' || audience || '|' || role FROM project_audience_roles;")
            .associate { row ->
                val (project, audience, role) = row.split('|')
                (project.toLong() to audience) to role
            },
    )

    /**
     * Run [block] against a writable copy of the committed `<version>.db`, foreign
     * keys OFF — the connection a migration actually runs on. See
     * StatusMigrationTest.withSnapshot, which this mirrors.
     */
    private fun <T> withSnapshot(version: Long, block: (SqlDriver) -> T): T =
        withFile { file ->
            File("src/main/sqldelight/databases/$version.db").copyTo(file, overwrite = true)
            open(file, block)
        }

    /** The same, against an empty file — for the fresh-schema half of the comparison. */
    private fun <T> withDatabase(block: (SqlDriver) -> T): T = withFile { open(it, block) }

    private fun <T> withFile(block: (File) -> T): T {
        val copy = Files.createTempFile("lunicle-permissions", ".db").toFile()
        copy.delete()
        return try {
            block(copy)
        } finally {
            copy.delete()
            File("${copy.absolutePath}-wal").delete()
            File("${copy.absolutePath}-shm").delete()
        }
    }

    private fun <T> open(file: File, block: (SqlDriver) -> T): T {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        return try {
            driver.execute(null, "PRAGMA foreign_keys = OFF;", 0)
            block(driver)
        } finally {
            driver.close()
        }
    }
}

private fun SqlDriver.exec(sql: String) = execute(null, sql, 0).value

private fun SqlDriver.queryStrings(sql: String): List<String> = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        val rows = mutableListOf<String>()
        while (cursor.next().value) cursor.getString(0)?.let { rows += it }
        QueryResult.Value(rows.toList())
    },
    parameters = 0,
).value

/** Nulls preserved, unlike [queryStrings] — `instance_role` being null is the assertion. */
private fun SqlDriver.queryNullableStrings(sql: String): List<String?> = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        val rows = mutableListOf<String?>()
        while (cursor.next().value) rows += cursor.getString(0)
        QueryResult.Value(rows.toList())
    },
    parameters = 0,
).value
