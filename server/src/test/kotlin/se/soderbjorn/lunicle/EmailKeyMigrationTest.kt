/**
 * What 21.sqm does to the rows — the half MigrationTest cannot see.
 *
 * MigrationTest compares PRAGMA output, so it proves the unique index arrives and
 * says nothing about the two `UPDATE`s that have to run first. Those are the
 * interesting part, and both fail in ways nothing else would catch:
 *
 *  - **Normalization.** Nothing lowercased anywhere before LNL-73, so a volume can
 *    hold `Alice@X.com`. If the migration does not rewrite it, that row's owner
 *    signs in, is looked up by `alice@x.com`, is not found, and gets a **second
 *    account** — with none of their issues in it and no error anywhere.
 *  - **The collision backstop.** Normalizing can make two rows equal, and
 *    `CREATE UNIQUE INDEX` over them fails — which aborts the migration, which
 *    aborts the boot. The deployed volume holds one user so this should never
 *    fire; it exists so that the day it does, the outcome is a support
 *    conversation rather than an outage. Untested, a backstop is just a comment.
 *
 * @see AttachmentPublicIdMigrationTest, whose plumbing this borrows
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
import kotlin.test.assertTrue

/** The version 21.sqm migrates from. */
private const val BEFORE = 21L

class EmailKeyMigrationTest {

    /**
     * Stored addresses come out trimmed and lowercased, and blank comes out NULL.
     *
     * The last of those matters more than it looks: `email IS NOT NULL` is what
     * the notification recipient queries join on, so an address of `""` surviving
     * would mean somebody with nowhere to receive mail counting as a recipient —
     * and, now, holding the account key.
     */
    @Test
    fun `existing addresses are normalized in place`() {
        assertEquals(
            listOf("1|alice@example.com", "2|bob@example.com", "3|NULL", "4|NULL"),
            migrated(
                "Alice@Example.COM",
                "  bob@example.com  ",
                "   ",
                null,
            ),
        )
    }

    /**
     * Two rows that collide after normalization: the lowest id keeps the address.
     *
     * The alternative outcomes are both worse. Failing the `CREATE UNIQUE INDEX`
     * takes the boot down, on a volume, unrecoverably by the person it happens to.
     * Keeping the *newest* would take the address away from whoever has been using
     * it longest. Neither row was ever verified by anything — nothing could verify
     * anything before LNL-71 — so the loser is left with a null address they can
     * type back in and, now, prove.
     */
    @Test
    fun `colliding addresses are resolved in favour of the oldest row`() {
        assertEquals(
            listOf("1|alice@example.com", "2|NULL", "3|NULL"),
            migrated("alice@example.com", "ALICE@example.com", "Alice@Example.Com"),
        )
    }

    /**
     * And the index really is there afterwards, refusing a duplicate.
     *
     * Asserted by trying the insert rather than by reading `sqlite_master`: an
     * index that exists and is not `UNIQUE`, or one whose partial predicate is
     * wrong, would both look right in the schema and enforce nothing.
     */
    @Test
    fun `the key is enforced after migrating`() {
        withMigratedDatabase(listOf("alice@example.com")) { driver ->
            // Post-migration columns: `is_sys_admin` and `created_at` are gone
            // (LNL-191, 33.sqm). The pre-migration seeds below the fold still name
            // them, because they run against the version-21 snapshot where they exist.
            val duplicate = runCatching {
                driver.exec(
                    "INSERT INTO users (provider, provider_id, provider_name, email, added_at) " +
                        "VALUES ('GOOGLE', 'sub-x', 'X', 'alice@example.com', 0);",
                )
            }
            assertTrue(duplicate.isFailure, "A second row took an address that was already spoken for.")

            // Two rows with no address must still be legal — a Google account whose
            // address Google would not confirm is one, and there may be many. This
            // is what `WHERE email IS NOT NULL` on the index buys.
            driver.exec(
                "INSERT INTO users (provider, provider_id, provider_name, email, added_at) " +
                    "VALUES ('GOOGLE', 'sub-y', 'Y', NULL, 0);",
            )
            val second = runCatching {
                driver.exec(
                    "INSERT INTO users (provider, provider_id, provider_name, email, added_at) " +
                        "VALUES ('GOOGLE', 'sub-z', 'Z', NULL, 0);",
                )
            }
            assertTrue(second.isSuccess, "Two accounts with no address collided on NULL.")
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /** Each row as `id|email`, in id order, with a literal NULL spelled out. */
    private fun migrated(vararg emails: String?): List<String> =
        withMigratedDatabase(emails.toList()) { driver ->
            driver.queryStrings("SELECT id || '|' || COALESCE(email, 'NULL') FROM users ORDER BY id;")
        }

    /** Seed one user per entry into a version-21 database, migrate it, and read back. */
    private fun <T> withMigratedDatabase(emails: List<String?>, read: (SqlDriver) -> T): T =
        withSnapshot(BEFORE) { driver ->
            emails.forEachIndexed { index, email ->
                val value = email?.let { "'${it.replace("'", "''")}'" } ?: "NULL"
                driver.exec(
                    "INSERT INTO users (provider, provider_id, provider_name, email, is_sys_admin, created_at) " +
                        "VALUES ('GITHUB', 'gh-$index', 'User $index', $value, 0, 0);",
                )
            }

            LunicleDatabase.Schema.migrate(driver, BEFORE, LunicleDatabase.Schema.version).value

            read(driver)
        }

    /** See StatusMigrationTest's helper of the same name for why foreign keys are off. */
    private fun <T> withSnapshot(version: Long, block: (SqlDriver) -> T): T {
        val copy = Files.createTempFile("lunicle-email-key-$version", ".db").toFile()
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
