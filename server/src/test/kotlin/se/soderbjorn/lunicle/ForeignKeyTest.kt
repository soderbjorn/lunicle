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

        val commentId = comments.insertDraft(fixture.issueId, createdBy = fixture.userId)
        attachments.insertForIssue(fixture.issueId, "a.png", "image/png", 1, "key-issue", fixture.userId)
        attachments.insertForComment(commentId, "b.png", "image/png", 1, "key-comment", fixture.userId)

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
        val commentId = CommentStore(database).insertDraft(fixture.issueId, fixture.userId)

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
        assertEquals(null, issue.createdBy)
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
        val project = projects.create(name, prefix, isPublic = false)
        val issueRepository = IssueRepository(
            IssueStore(database),
            CommentStore(database),
            StatusStore(database),
            PriorityStore(database),
            attachmentRepository,
            AttachmentStore(database),
        )
        val (issueId, _) = issueRepository.createDraft(project.id, user.id)
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
