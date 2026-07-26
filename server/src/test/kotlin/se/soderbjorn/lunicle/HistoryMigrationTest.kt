/**
 * What 5.sqm does to the rows — the half MigrationTest cannot see.
 *
 * MigrationTest compares PRAGMA output, so it covers the two tables 5.sqm
 * creates and is entirely blind to the back-fill underneath them: it would pass
 * identically if that INSERT were deleted, mis-scoped, or ran twice. The gap
 * matters for the same reason it mattered for 4.sqm — this runs exactly once,
 * against a volume holding real issues, and a mistake in it is discovered as
 * "every issue that existed before the feature has an empty history" after the
 * one chance to run it correctly has gone.
 *
 * Each test builds a version-5 database from the committed snapshot, fills it
 * with the issues it is about, migrates to the current version, and reads the
 * events back.
 *
 * @see StatusMigrationTest whose plumbing this borrows, and MigrationTest for the
 *   schema half.
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

/** The version 5.sqm migrates from. */
private const val BEFORE = 5L

/** One issue to seed, in the shape the back-fill cares about. */
private data class Seed(
    val title: String,
    val createdAt: Long,
    val isDraft: Boolean = false,
    val createdBy: Long? = null,
    val createdByExternal: String? = null,
    val agentName: String? = null,
)

/** One back-filled event, as the assertions want to read it. */
private data class Event(
    val issueId: Long,
    val kind: String,
    val createdAt: Long,
    val createdBy: Long?,
    val createdByExternal: String?,
    val agentName: String?,
)

class HistoryMigrationTest {

    /**
     * The ordinary case: an issue that existed gains the one event that can be
     * reconstructed honestly.
     *
     * The date is the issue's own, not the migration's. An event dated today for
     * an issue filed in March is not a weaker record than the real thing — it is
     * a false one, and it would pile every issue in the instance into a single
     * lump at the migration timestamp.
     */
    @Test
    fun `writes a CREATED event carrying the issue's own date`() {
        val events = migrateIssues(Seed("Login is broken", createdAt = 1_700_000_000_000, createdBy = 1))
        assertEquals(
            listOf(Event(1, "CREATED", 1_700_000_000_000, createdBy = 1, createdByExternal = null, agentName = null)),
            events,
        )
    }

    /**
     * An imported issue, whose author is a name rather than an account.
     *
     * The two author columns are copied as a *pair*. Copying `created_by` alone
     * would leave this event unowned while the issue above it is attributed —
     * the same issue, two authors, on one screen. The failure is silent, which is
     * why it is pinned rather than trusted to review.
     */
    @Test
    fun `carries an external author across`() {
        val events = migrateIssues(
            Seed("Imported from GitHub", createdAt = 42, createdByExternal = "octocat"),
        )
        assertEquals(
            listOf(Event(1, "CREATED", 42, createdBy = null, createdByExternal = "octocat", agentName = null)),
            events,
        )
    }

    /**
     * An issue an agent has touched keeps the agent label on its creation event.
     *
     * The known-imprecise case, and pinned so the imprecision is deliberate
     * rather than discovered: `issues.agent_name` means "an agent last touched
     * this", which for a never-edited issue is exactly "an agent created it" and
     * for an edited one may over-attribute. There is no better source — the edit
     * that set the column left no other trace — and the error is bounded to rows
     * written before this migration. See 5.sqm.
     */
    @Test
    fun `carries the agent label across`() {
        val events = migrateIssues(Seed("Filed by a bot", createdAt = 7, createdBy = 1, agentName = "Claude Code"))
        assertEquals(
            listOf(Event(1, "CREATED", 7, createdBy = 1, createdByExternal = null, agentName = "Claude Code")),
            events,
        )
    }

    /**
     * A draft gets nothing.
     *
     * Not merely "drafts are private": a draft will write its own CREATED event
     * through the normal path the moment it is published, so a row here would be
     * the second one and the issue would claim to have been created twice.
     */
    @Test
    fun `skips drafts`() {
        val events = migrateIssues(
            Seed("Published", createdAt = 1, createdBy = 1),
            Seed("Still being written", createdAt = 2, isDraft = true, createdBy = 1),
        )
        assertEquals(
            listOf(Event(1, "CREATED", 1, createdBy = 1, createdByExternal = null, agentName = null)),
            events,
        )
    }

    /**
     * Every issue gets exactly one, and each gets its own values.
     *
     * The statement is set-based rather than per-issue, which is what makes it a
     * migration rather than a loop — and also what makes a lost correlation easy
     * to write. A SELECT that read its date or its author from the wrong row
     * would look correct on a one-issue fixture and stamp every event with the
     * first issue's values here.
     */
    @Test
    fun `writes one event per issue, each with its own attribution`() {
        val events = migrateIssues(
            Seed("First", createdAt = 100, createdBy = 1),
            Seed("Second", createdAt = 200, createdByExternal = "someone"),
            Seed("Third", createdAt = 300, createdBy = 1, agentName = "Claude Code"),
        )
        assertEquals(
            listOf(
                Event(1, "CREATED", 100, 1, null, null),
                Event(2, "CREATED", 200, null, "someone", null),
                Event(3, "CREATED", 300, 1, null, "Claude Code"),
            ),
            events,
        )
    }

    /**
     * No values rows. CREATED carries none.
     *
     * Asserted rather than assumed, because `issue_event_values` is written by a
     * different statement in the running code and an over-eager back-fill that
     * decided to record the issue's current labels as a LABELS_CHANGED would be
     * inventing an event nobody performed — the exact thing 5.sqm's comment
     * forbids, and nothing else here would catch it.
     */
    @Test
    fun `writes no values rows`() {
        val values = withSnapshot(BEFORE) { driver ->
            seed(driver, listOf(Seed("Anything", createdAt = 1, createdBy = 1)))
            LunicleDatabase.Schema.migrate(driver, BEFORE, LunicleDatabase.Schema.version).value
            driver.queryLong("SELECT COUNT(*) FROM issue_event_values;")
        }
        assertEquals(0L, values)
    }

    /**
     * The `NOT EXISTS` guard: an issue that already has a CREATED event does not
     * gain a second one.
     *
     * Nothing should run a migration twice. The guard is there because the
     * failure without it — a history in which every issue was created twice — is
     * silent, permanent, and unfixable from the UI, and the guard costs one
     * clause.
     */
    @Test
    fun `does not duplicate an event that already exists`() {
        val events = withSnapshot(BEFORE) { driver ->
            seed(driver, listOf(Seed("Already recorded", createdAt = 5, createdBy = 1)))
            LunicleDatabase.Schema.migrate(driver, BEFORE, LunicleDatabase.Schema.version).value
            // Run the back-fill's own statement a second time, rather than
            // re-running the migration — SQLDelight would refuse the second
            // CREATE TABLE long before reaching the INSERT this is about.
            driver.exec(
                "INSERT INTO issue_events (issue_id, kind, value_text, value_user_id, created_at, " +
                    "created_by, created_by_external, agent_name) " +
                    "SELECT i.id, 'CREATED', NULL, NULL, i.created_at, i.created_by, " +
                    "i.created_by_external, i.agent_name FROM issues i WHERE i.is_draft = 0 " +
                    "AND NOT EXISTS (SELECT 1 FROM issue_events e WHERE e.issue_id = i.id " +
                    "AND e.kind = 'CREATED');",
            )
            driver.queryEvents()
        }
        assertEquals(
            listOf(Event(1, "CREATED", 5, createdBy = 1, createdByExternal = null, agentName = null)),
            events,
        )
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /** Seed [issues] into a version-5 database, migrate it, and read the events back. */
    private fun migrateIssues(vararg issues: Seed): List<Event> = withSnapshot(BEFORE) { driver ->
        seed(driver, issues.toList())
        LunicleDatabase.Schema.migrate(driver, BEFORE, LunicleDatabase.Schema.version).value
        driver.queryEvents()
    }

    /**
     * The minimum a version-5 database needs to hold an issue: a user, a project,
     * a status and a priority, because `issues` has NOT NULL composite references
     * to the last two.
     *
     * Foreign keys are off on this connection — see [withSnapshot] — so these are
     * not strictly required to make the INSERTs succeed. They are here anyway
     * because the back-fill copies `created_by` and the assertions are about
     * whether it copied the *right* one; seeding a real user makes the id in an
     * expectation mean something.
     */
    private fun seed(driver: SqlDriver, issues: List<Seed>) {
        driver.exec(
            "INSERT INTO users (provider, provider_id, provider_name, is_admin, created_at) " +
                "VALUES ('GOOGLE', 'g1', 'Robert', 1, 0);",
        )
        driver.exec(
            "INSERT INTO projects (name, name_prefix, is_public, created_at) VALUES ('P', 'P', 0, 0);",
        )
        driver.exec(
            "INSERT INTO statuses (project_id, name, position, requires_resolution) VALUES (1, 'New', 0, 0);",
        )
        driver.exec("INSERT INTO priorities (project_id, name, position) VALUES (1, 'Normal', 0);")
        issues.forEachIndexed { index, issue ->
            driver.exec(
                "INSERT INTO issues (project_id, number, title, description, status_id, priority_id, " +
                    "is_draft, created_at, updated_at, created_by, created_by_external, agent_name) VALUES " +
                    "(1, ${index + 1}, '${issue.title}', '', 1, 1, ${if (issue.isDraft) 1 else 0}, " +
                    "${issue.createdAt}, ${issue.createdAt}, ${issue.createdBy ?: "NULL"}, " +
                    "${issue.createdByExternal?.let { "'$it'" } ?: "NULL"}, " +
                    "${issue.agentName?.let { "'$it'" } ?: "NULL"});",
            )
        }
    }

    /**
     * Run [block] against a writable copy of the committed `<version>.db`, with
     * foreign keys OFF.
     *
     * A near-copy of StatusMigrationTest's helper, and not shared with it for the
     * reason that one gives: a migration runs on an unenforced connection, so
     * enforcing them here would be testing a connection production never uses.
     */
    private fun <T> withSnapshot(version: Long, block: (SqlDriver) -> T): T {
        val copy = Files.createTempFile("lunicle-history-$version", ".db").toFile()
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

/** Ordered by id, which is the order IssueEvents.sq reads them in. */
private fun SqlDriver.queryEvents(): List<Event> = executeQuery(
    identifier = null,
    sql = "SELECT issue_id, kind, created_at, created_by, created_by_external, agent_name " +
        "FROM issue_events ORDER BY id;",
    mapper = { cursor ->
        val rows = mutableListOf<Event>()
        while (cursor.next().value) {
            rows += Event(
                issueId = cursor.getLong(0) ?: 0,
                kind = cursor.getString(1).orEmpty(),
                createdAt = cursor.getLong(2) ?: 0,
                createdBy = cursor.getLong(3),
                createdByExternal = cursor.getString(4),
                agentName = cursor.getString(5),
            )
        }
        QueryResult.Value(rows.toList())
    },
    parameters = 0,
).value

private fun SqlDriver.queryLong(sql: String): Long = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        cursor.next()
        QueryResult.Value(cursor.getLong(0) ?: 0)
    },
    parameters = 0,
).value
