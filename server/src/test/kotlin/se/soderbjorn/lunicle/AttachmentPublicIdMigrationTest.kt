/**
 * What 13.sqm does to the rows — the half MigrationTest cannot see.
 *
 * MigrationTest compares PRAGMA output, so it proves the `public_id` column
 * arrives and says nothing about what is in it. Here that gap is the entire
 * promise LNL-51 made: **existing attachments must keep working**. Their URLs
 * are already inside the markdown of issues and comments, inside notification
 * e-mails already sent, and inside links people have pasted at each other, and
 * `/api/attachments/80` resolves after this migration only because 80 is what
 * the back-fill wrote into row 80's `public_id`. A migration that generated
 * random ids for existing rows instead would compile, pass MigrationTest, and
 * break every attachment on the instance on the one run that ever happens.
 *
 * The second thing tested here is the rebuild itself. 13.sqm copies the table
 * rather than altering it, because the column is NOT NULL and UNIQUE and SQLite
 * can express neither in ALTER TABLE — and a copy is exactly the operation that
 * can silently drop a column, renumber a key, or lose a row.
 *
 * @see LabelOrderMigrationTest, whose plumbing this borrows
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

/** The version 13.sqm migrates from. */
private const val BEFORE = 13L

class AttachmentPublicIdMigrationTest {

    /**
     * The promise: the URL an existing attachment already has still names it.
     *
     * Asserted as the id a URL would be built from rather than merely "the column
     * is not null", because "not null" is satisfied by a random value, which is
     * the exact bug this file exists to prevent.
     */
    @Test
    fun `an existing attachment's public id is its old numeric one`() {
        assertEquals(
            listOf("1" to 1L, "2" to 2L, "3" to 3L),
            migrated(),
        )
    }

    /**
     * The rebuild carries every row across, and every column of it.
     *
     * A `SELECT` that lost a column in the copy would leave the data quietly
     * emptied rather than failing — `filename` and `storage_key` are the two that
     * matter most, being the download name and the file on the volume.
     */
    @Test
    fun `the rebuild preserves the rest of the row`() {
        assertEquals(
            listOf("a.png|image/png|key-a", "b.pdf|application/pdf|key-b", "c.zip|application/zip|key-c"),
            withMigratedDatabase {
                it.queryStrings(
                    "SELECT filename || '|' || mime_type || '|' || storage_key FROM attachments ORDER BY id;",
                )
            },
        )
    }

    /**
     * Row ids survive the copy.
     *
     * `id` is carried across explicitly rather than left to AUTOINCREMENT, and
     * this is why: the back-fill derives the public id from it, so a renumbered
     * row would both change its own URL and take an id another row's URL already
     * names. The ids below are deliberately non-contiguous — row 2 is deleted
     * before migrating — because a rebuild that renumbered would close that gap
     * and look perfectly healthy while doing it.
     */
    @Test
    fun `a gap in the ids is preserved rather than closed up`() {
        assertEquals(
            listOf("1" to 1L, "3" to 3L),
            migrated(deleteId = 2),
        )
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /** Each surviving row as (public_id, id), in id order. */
    private fun migrated(deleteId: Int? = null): List<Pair<String, Long>> =
        withMigratedDatabase(deleteId) { driver ->
            driver.queryStrings("SELECT public_id || ':' || id FROM attachments ORDER BY id;")
                .map { it.substringBefore(':') to it.substringAfter(':').toLong() }
        }

    /**
     * Seed three attachments into a version-13 database, migrate it, and read back.
     *
     * The owning issue and project are inserted by hand rather than through the
     * repositories: this runs against a *snapshot* driver, which has no generated
     * queries pointed at it, and the `CHECK` on `attachments` only needs an issue
     * id that exists.
     */
    private fun <T> withMigratedDatabase(deleteId: Int? = null, read: (SqlDriver) -> T): T =
        withSnapshot(BEFORE) { driver ->
            driver.exec(
                "INSERT INTO projects (name, name_prefix, is_public, created_at) VALUES ('P', 'P', 1, 0);",
            )
            driver.exec(
                "INSERT INTO statuses (project_id, name, position, requires_resolution) VALUES (1, 'New', 0, 0);",
            )
            driver.exec("INSERT INTO priorities (project_id, name, position) VALUES (1, 'Normal', 0);")
            driver.exec(
                "INSERT INTO issues (project_id, number, title, description, status_id, priority_id, " +
                    "is_draft, created_at, updated_at) VALUES (1, 1, 't', '', 1, 1, 0, 0, 0);",
            )
            listOf(
                Triple("a.png", "image/png", "key-a"),
                Triple("b.pdf", "application/pdf", "key-b"),
                Triple("c.zip", "application/zip", "key-c"),
            ).forEach { (filename, mimeType, key) ->
                driver.exec(
                    "INSERT INTO attachments (issue_id, filename, mime_type, byte_size, storage_key, created_at) " +
                        "VALUES (1, '$filename', '$mimeType', 1, '$key', 0);",
                )
            }
            if (deleteId != null) driver.exec("DELETE FROM attachments WHERE id = $deleteId;")

            LunicleDatabase.Schema.migrate(driver, BEFORE, LunicleDatabase.Schema.version).value

            read(driver)
        }

    /** See StatusMigrationTest's helper of the same name for why foreign keys are off. */
    private fun <T> withSnapshot(version: Long, block: (SqlDriver) -> T): T {
        val copy = Files.createTempFile("lunicle-attachment-public-id-$version", ".db").toFile()
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
