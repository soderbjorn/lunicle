/**
 * Are the foreign keys actually enforced?
 *
 * This file exists because the answer was **no** for the whole of Stage 2, and
 * nothing said so. `Database.kt` executed `PRAGMA foreign_keys = ON` at startup
 * and carried a comment explaining why that mattered; both were correct, and the
 * pragma still never reached a single query. `JdbcSqliteDriver` hands out a
 * fresh connection per operation for a file URL, so the setting landed on a
 * throwaway connection and every later statement ran with foreign keys off.
 *
 * The symptom was not an error. It was a database quietly accumulating rows
 * pointing at deleted parents, on a volume, for as long as nobody looked — and
 * the schema's central trick (composite foreign keys making a cross-project
 * label unstorable, see IssueLabels.sq) silently doing nothing at all.
 *
 * So these tests go through the real driver, the real pragmas and the real
 * stores. A unit test that opened its own connection with its own pragma would
 * have passed throughout the bug.
 *
 * @see Database
 */
package se.soderbjorn.lunicle

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ForeignKeyTest {
    private val file: File = Files.createTempFile("lunicle-fk", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    /**
     * The one that would have caught it.
     *
     * Read on the dispatcher every store actually uses, not on the thread that
     * happened to open the driver — that distinction is the entire bug.
     */
    @Test
    fun `foreign keys are on for the connection the stores use`(): Unit = runBlocking {
        val value = withContext(DatabaseDispatcher) { opened.driver.readPragma("foreign_keys") }
        assertEquals("1", value, "Foreign keys are off; every cascade in this schema is decoration.")
    }

    @Test
    fun `WAL is on`(): Unit = runBlocking {
        val value = withContext(DatabaseDispatcher) { opened.driver.readPragma("journal_mode") }
        assertEquals("wal", value.lowercase())
    }

    /**
     * Deleting an issue takes its comments and its attachment *rows* with it.
     *
     * The files are IssueRepository's problem — SQLite cannot reach the
     * filesystem — but the rows are the cascade's, and this is what failed.
     */
    @Test
    fun `deleting an issue cascades to its comments and attachments`(): Unit = runBlocking {
        val fixture = seed()
        val comments = CommentStore(database)
        val attachments = AttachmentStore(database)
        val issues = IssueStore(database)

        val commentId = comments.insertDraft(fixture.issueId, Author.Account(fixture.userId))
        attachments.insertForIssue(
            fixture.issueId, "a.png", "image/png", 1, "key-issue", "pub-issue", Author.Account(fixture.userId),
        )
        attachments.insertForComment(
            commentId, "b.png", "image/png", 1, "key-comment", "pub-comment", Author.Account(fixture.userId),
        )

        issues.delete(fixture.issueId)

        assertEquals(0, comments.forIssue(fixture.issueId).size)
        assertEquals(emptySet(), attachments.allStorageKeys(), "Attachment rows outlived their issue.")
        assertEquals(0, countOrphans(), "PRAGMA foreign_key_check found dangling rows.")
    }

    /** Deleting a project takes everything in it. */
    @Test
    fun `deleting a project cascades to its issues and vocabularies`(): Unit = runBlocking {
        val fixture = seed()
        ProjectStore(database).delete(fixture.projectId)

        assertEquals(0, IssueStore(database).forProject(fixture.projectId).size)
        assertEquals(0, LabelStore(database).forProject(fixture.projectId).size)
        assertEquals(0, StatusStore(database).forProject(fixture.projectId).size)
        assertEquals(0, countOrphans())
    }

    /**
     * The schema's central trick: a cross-project label is unstorable.
     *
     * Not "rejected by a route" — the routes check too, and return a friendlier
     * 400 — but refused by the database, which is the thing you cannot forget to
     * do on the seventh endpoint.
     */
    @Test
    fun `an issue cannot carry another project's label`(): Unit = runBlocking {
        val a = seed(name = "Alpha", prefix = "ALP")
        val b = seed(name = "Beta", prefix = "BET")
        val foreignLabel = LabelStore(database).forProject(b.projectId).first()

        // Claiming the label's project…
        assertFailsWith<Exception>("The database accepted a label from another project.") {
            withContext(DatabaseDispatcher) {
                database.issueLabelsQueries.insert(a.issueId, foreignLabel.id, b.projectId)
            }
        }
        // …and claiming the issue's. Both keys must agree on project_id, so
        // there is no way to phrase this that satisfies them at once.
        assertFailsWith<Exception>("The database accepted a label from another project.") {
            withContext(DatabaseDispatcher) {
                database.issueLabelsQueries.insert(a.issueId, foreignLabel.id, a.projectId)
            }
        }
        assertEquals(0, countOrphans())
    }

    /** An issue cannot land in another project's column. */
    @Test
    fun `an issue cannot take another project's status`(): Unit = runBlocking {
        val a = seed(name = "Gamma", prefix = "GAM")
        val b = seed(name = "Delta", prefix = "DEL")
        val foreignStatus = StatusStore(database).forProject(b.projectId).first()

        assertFailsWith<Exception>("The database accepted a status from another project.") {
            withContext(DatabaseDispatcher) {
                database.issuesQueries.setStatus(foreignStatus.id, null, 0L, a.issueId)
            }
        }
    }

    /** Exactly one owner, by the CHECK's boolean arithmetic. */
    @Test
    fun `an attachment cannot be owned by both an issue and a comment, or neither`(): Unit = runBlocking {
        val fixture = seed()
        val commentId = CommentStore(database).insertDraft(fixture.issueId, Author.Account(fixture.userId))

        assertFailsWith<Exception>("An attachment with two owners was stored.") {
            withContext(DatabaseDispatcher) {
                opened.driver.execute(
                    null,
                    "INSERT INTO attachments (issue_id, comment_id, filename, mime_type, byte_size, storage_key, created_at) " +
                        "VALUES (${fixture.issueId}, $commentId, 'x', 'image/png', 1, 'k-both', 0);",
                    0,
                )
            }
        }
        assertFailsWith<Exception>("An attachment with no owner was stored.") {
            withContext(DatabaseDispatcher) {
                opened.driver.execute(
                    null,
                    "INSERT INTO attachments (issue_id, comment_id, filename, mime_type, byte_size, storage_key, created_at) " +
                        "VALUES (NULL, NULL, 'x', 'image/png', 1, 'k-none', 0);",
                    0,
                )
            }
        }
    }

    /**
     * One author or the other, on all three tables that have one.
     *
     * ── Why this is asserted at the driver rather than through a store ─────────
     *
     * Because the [Author] type means no store *can* ask for both — that is the
     * point of it, and it is why this constraint should never fire in
     * production. Which is also why it needs a test that goes around the type:
     * an assertion made through the code that makes the state unreachable proves
     * only that the code is still there. This one asks SQLite directly, and it
     * is the only thing standing behind a row written by a future store, a
     * script, or a hand-typed UPDATE on the volume.
     *
     * It is also the *only* check on this constraint anywhere. MigrationTest
     * compares `PRAGMA` output and SQLite reports no CHECK through any pragma,
     * so the schema comparison is blind to it — see that file's note. Delete
     * this test and the CHECK could vanish from all three tables with the build
     * staying green.
     *
     * Both-null is deliberately not asserted as a failure: it is legal, it means
     * "nobody", and it is exactly what `ON DELETE SET NULL` leaves behind.
     */
    @Test
    fun `a row cannot have both an account author and an external one`(): Unit = runBlocking {
        val fixture = seed()
        val commentId = CommentStore(database).insertDraft(fixture.issueId, Author.Account(fixture.userId))

        val doubly = mapOf(
            "issue" to "INSERT INTO issues (project_id, number, title, description, status_id, " +
                "priority_id, is_draft, created_at, updated_at, created_by, created_by_external) " +
                "SELECT ${fixture.projectId}, 99, 'x', '', status_id, priority_id, 0, 0, 0, " +
                "${fixture.userId}, 'octocat' FROM issues WHERE id = ${fixture.issueId};",
            "comment" to "INSERT INTO comments (issue_id, body, created_at, created_by, created_by_external) " +
                "VALUES (${fixture.issueId}, 'x', 0, ${fixture.userId}, 'octocat');",
            // public_id is supplied even though this test is not about it: it is
            // NOT NULL, and leaving it out would make this row fail on that
            // instead of on the CHECK — which is precisely the false pass the
            // reason-assertion below exists to catch.
            "attachment" to "INSERT INTO attachments (issue_id, filename, mime_type, byte_size, " +
                "storage_key, public_id, created_at, created_by, created_by_external) " +
                "VALUES (${fixture.issueId}, 'x', 'image/png', 1, 'k-two-authors', 'p-two-authors', 0, " +
                "${fixture.userId}, 'octocat');",
        )

        doubly.forEach { (what, sql) ->
            val failure = assertFailsWith<Exception>(
                "A $what was stored with an account author AND an external one.",
            ) {
                withContext(DatabaseDispatcher) { opened.driver.execute(null, sql, 0) }
            }
            // Asserting on the *reason*, not merely that something threw. Without
            // this the test passes on a NOT NULL or a UNIQUE violation from a
            // typo in the SQL above — which is to say it would pass with the
            // CHECK deleted from all three tables, proving nothing while looking
            // exactly like proof.
            assertTrue(
                failure.message.orEmpty().contains("CHECK constraint failed", ignoreCase = true),
                "The $what insert failed, but not on the CHECK — so this test is not testing it. " +
                    "SQLite said: ${failure.message}",
            )
        }
    }

    /**
     * An issue outlives its author.
     *
     * SET NULL rather than CASCADE, and the difference is the board losing its
     * history every time someone leaves.
     */
    @Test
    fun `deleting a user nulls their issues' author rather than deleting the issues`(): Unit = runBlocking {
        val fixture = seed()
        withContext(DatabaseDispatcher) {
            opened.driver.execute(null, "DELETE FROM users WHERE id = ${fixture.userId};", 0)
        }
        val issue = IssueStore(database).findById(fixture.issueId)
        assertTrue(issue != null, "Deleting a user deleted their issue.")
        assertEquals(
            Author.Nobody,
            issue.author,
            "SET NULL left something other than an authorless issue behind.",
        )
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val userId: Long, val projectId: Long, val issueId: Long)

    private suspend fun seed(name: String = "Lunamux", prefix: String = "LMX"): Fixture {
        val users = UserStore(database)
        val user = users.upsert(
            ProviderIdentity(
                provider = se.soderbjorn.lunicle.clientserver.AuthProvider.GITHUB,
                providerId = "gh-$name",
                providerName = "tester",
                email = null,
            ),
        )
        val attachmentRepository = AttachmentRepository(
            AttachmentStore(database),
            File(file.parentFile, "attachments-${file.name}"),
        )
        val projects = ProjectRepository(database, ProjectStore(database), attachmentRepository, AttachmentStore(database))
        val project = projects.create(name, prefix)
        val issueRepository = IssueRepository(
            IssueStore(database),
            CommentStore(database),
            StatusStore(database),
            PriorityStore(database),
            attachmentRepository,
            AttachmentStore(database),
        )
        val (issueId, _) = issueRepository.createDraft(project.id, Author.Account(user.id))
        return Fixture(user.id, project.id, issueId)
    }

    /** How many rows point at a parent that is not there. Zero, always. */
    private suspend fun countOrphans(): Int = withContext(DatabaseDispatcher) {
        opened.driver.executeQuery(
            identifier = null,
            sql = "PRAGMA foreign_key_check;",
            mapper = { cursor ->
                var count = 0
                while (cursor.next().value) count++
                QueryResult.Value(count)
            },
            parameters = 0,
        ).value
    }
}
