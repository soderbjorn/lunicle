/**
 * The admin-only backfill parameters on `create_issue` and `add_comment`.
 *
 * ── Why these are worth a file ──────────────────────────────────────────────
 *
 * `author` and `created_at` are the one place the MCP surface does something the
 * web app cannot — see [AccessControl.canAttributeWrites] — and every failure mode
 * here is silent:
 *
 *  - **A non-admin's `author` being ignored rather than refused.** This is the
 *    dangerous one, and it is the *default* behaviour of the obvious
 *    implementation: read the argument, check the flag, fall back to the token's
 *    user. Nothing looks broken. The agent reports "backfilled 40 issues under
 *    Ada's name" and the board says the agent's owner wrote all 40, and the only
 *    person who could notice is the one reading a report that says otherwise.
 *  - **An ambiguous author being guessed.** Two accounts named "Anna Karlsson" and
 *    a `first()` — somebody's imported history is attributed to a stranger,
 *    permanently, with no error anywhere.
 *  - **`updated_at` drifting off `created_at`.** `publish` stamps `updated_at`
 *    unconditionally, so the natural version of this feature backfills
 *    `created_at` and leaves the issue "last touched" today — pinning every
 *    imported issue above the real board. The test below asserts the two columns
 *    are equal rather than merely that `created_at` was stored, because storing
 *    `created_at` is exactly what the broken version also does.
 *  - **The default path quietly changing.** Two new optional parameters on the two
 *    most-used write tools; the regression that matters most is the one where
 *    nobody passes either.
 *
 * ── Through the real /mcp endpoint, with real tokens ────────────────────────
 *
 * Not against [McpTools] directly. The claim being made is "the admin check is on
 * the token's user, server-side, on every call", and a test that handed McpTools a
 * `UserRecord` it built itself would assert a weaker thing: that the function
 * refuses a record whose `isAdmin` is false. It would pass against a server whose
 * token path resolved to the wrong person entirely. So: a registered client, a
 * minted access token, `Authorization: Bearer`, and JSON-RPC over HTTP — the same
 * path Claude Code takes. This is VocabularyTest's reasoning about session
 * cookies, applied to the transport that replaced them.
 *
 * @see McpTools
 * @see AccessControl.canAttributeWrites
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.lunicle.clientserver.AuthProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class McpBackfillTest {
    private val file: File = Files.createTempFile("lunicle-mcp", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)
    private val sessions = SessionStore(database)
    private val roles = RoleStore(database)
    private val projects = ProjectStore(database)
    private val labels = LabelStore(database)
    private val components = ComponentStore(database)
    private val statuses = StatusStore(database)
    private val priorities = PriorityStore(database)
    private val resolutions = ResolutionStore(database)
    private val issues = IssueStore(database)
    private val comments = CommentStore(database)
    private val attachmentStore = AttachmentStore(database)
    private val attachments = AttachmentRepository(attachmentStore, File(file.parentFile, "attachments-${file.name}"))
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
    private val issueRepository =
        IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)
    private val vocabularies =
        VocabularyRepository(database, labels, components, statuses, priorities, resolutions, issues)
    private val access = AccessControl(roles)

    private val clients = OAuthClientStore(database)
    private val loginStates = OAuthLoginStateStore(database)
    private val codes = OAuthCodeStore(database)
    private val tokens = OAuthTokenStore(database)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    /** A moment in 2019, and the whole point: it is not now, and no clock produces it. */
    private val backfilledAt = 1_550_000_000_000L

    // ── A non-admin is refused, and nothing is written ───────────────────────

    /**
     * The test this file exists for.
     *
     * Asserting on *both* halves, because the failure being guarded is a success
     * response: the refusal, and then that no issue was created at all. A version
     * that filed the issue under the agent's own user and returned "Created LMX-1"
     * would satisfy any test that only looked at `created_by`, and it is precisely
     * the version an agent cannot detect.
     */
    @Test
    fun `a non-admin naming an author is refused, and no issue is written`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Imported","author":"Admin"}""",
            )
            assertTrue(result.isError, "A non-admin's `author` was accepted or silently ignored.")
            assertTrue(
                result.text.contains("Only an admin"),
                "The refusal must say who may do this. Got: ${result.text}",
            )
        }

        assertEquals(
            emptyList(),
            issues.forProject(fixture.projectId),
            "A refused create_issue wrote an issue anyway.",
        )
    }

    /** The same rule for the timestamp, and for the same reason. */
    @Test
    fun `a non-admin setting created_at is refused`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Imported","created_at":$backfilledAt}""",
            )
            assertTrue(result.isError, "A non-admin backdated an issue.")
            assertTrue(result.text.contains("created_at"), result.text)
        }
        assertEquals(emptyList(), issues.forProject(fixture.projectId))
    }

    /** And on comments, which is the other half of an import and the easier one to forget. */
    @Test
    fun `a non-admin naming an author on a comment is refused`(): Unit = runBlocking {
        val fixture = seed()
        val (issueId, _) = published(fixture)
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "add_comment",
                """{"issue_id":$issueId,"body":"Me too","author":"Admin","created_at":$backfilledAt}""",
            )
            assertTrue(result.isError, "A non-admin attributed a comment to somebody else.")
        }
        assertEquals(
            emptyList(),
            comments.forIssue(issueId),
            "A refused add_comment left a comment — or worse, a draft nobody can see.",
        )
    }

    /**
     * A garbage value is still *asking*, and is still refused.
     *
     * The reason [McpTools] checks presence rather than parseability. An
     * implementation that read the argument as a number first would see null here,
     * conclude nothing was asked for, and file the row under the agent's own user
     * stamped now — reporting success. That is the silent substitution, reached by
     * a caller who is merely sloppy rather than hostile.
     */
    @Test
    fun `a non-admin sending an unparseable created_at is refused rather than defaulted`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Imported","created_at":"last Tuesday"}""",
            )
            assertTrue(result.isError, "A bad `created_at` from a non-admin was quietly ignored.")
            assertTrue(result.text.contains("Only an admin"), result.text)
        }
        assertEquals(emptyList(), issues.forProject(fixture.projectId))
    }

    // ── An admin's backfill actually lands ───────────────────────────────────

    /**
     * The positive case — without which every test above proves only that the
     * feature does nothing.
     *
     * `updated_at` is asserted equal to `created_at`, not merely "not now": the
     * broken version stores `created_at` correctly and lets `publish` stamp
     * `updated_at` with the clock, which reads as a working backfill until you look
     * at the board and find every imported issue above everything real.
     */
    @Test
    fun `an admin backfills an issue under another user, at the given time`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Imported from Jira",
                   "author":"Ordinary","created_at":$backfilledAt}""",
            )
            assertTrue(!result.isError, "The admin's backfill was refused: ${result.text}")
        }

        val issue = issues.forProject(fixture.projectId).single()
        assertEquals(Author.Account(fixture.ordinaryId), issue.author, "The issue was not attributed to the named author.")
        assertEquals(backfilledAt, issue.createdAt, "The given created_at was not stored.")
        assertEquals(
            backfilledAt,
            issue.updatedAt,
            "updated_at straddled created_at — publish stamped the clock over the backfill, and " +
                "the board would sort this imported issue above everything real.",
        )
    }

    /** The comment half: `created_by` and `created_at`, both from the admin's request. */
    @Test
    fun `an admin backfills a comment under another user, at the given time`(): Unit = runBlocking {
        val fixture = seed()
        val (issueId, _) = published(fixture)
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "add_comment",
                """{"issue_id":$issueId,"body":"Imported reply","author":"Ordinary","created_at":$backfilledAt}""",
            )
            assertTrue(!result.isError, result.text)
        }

        val comment = comments.forIssue(issueId).single()
        assertEquals(Author.Account(fixture.ordinaryId), comment.author)
        assertEquals(backfilledAt, comment.createdAt)
    }

    /** An email address works, which is the escape hatch offered when a name is ambiguous. */
    @Test
    fun `an author can be named by the email address on their account`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"By email","author":"ORDINARY@example.com"}""",
            )
            assertTrue(!result.isError, result.text)
        }
        assertEquals(Author.Account(fixture.ordinaryId), issues.forProject(fixture.projectId).single().author)
    }

    // ── An author with no account ────────────────────────────────────────────

    /**
     * The case the whole `author_external` parameter exists for.
     *
     * A GitHub handle with nothing behind it: no account, no way to make one, and
     * the import is worthless if the board says the admin wrote all of it. Asserts
     * the row, not the response — "Created LMX-1" is what the broken version says
     * too.
     */
    @Test
    fun `an admin can attribute an issue to somebody with no account`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Imported","author_external":"octocat",""" +
                    """"created_at":$backfilledAt}""",
            )
            assertTrue(!result.isError, "The admin's external backfill was refused: ${result.text}")
        }

        val issue = issues.forProject(fixture.projectId).single()
        assertEquals(Author.External("octocat"), issue.author)
        assertEquals(backfilledAt, issue.createdAt, "The given created_at was not stored.")
        assertEquals(backfilledAt, issue.updatedAt, "updated_at straddled created_at on the external path.")
    }

    /**
     * A non-admin is refused, and nothing is written.
     *
     * The same failure the file's first test guards, on the parameter that is
     * strictly worse to get wrong: `author` can at least only name somebody who
     * already exists here, while `author_external` will write whatever string it
     * is handed. If this one is ever ignored rather than refused, any user can
     * put any name on the board and the agent will report that it worked.
     */
    @Test
    fun `a non-admin naming an external author is refused, and no issue is written`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Imported","author_external":"octocat"}""",
            )
            assertTrue(result.isError, "A non-admin wrote an issue under an invented author.")
            assertTrue(
                result.text.contains("Only an admin"),
                "The refusal must say why. Got: ${result.text}",
            )
        }
        assertEquals(
            emptyList(),
            issues.forProject(fixture.projectId),
            "The refusal was reported but the issue was written anyway.",
        )
    }

    /**
     * Two answers to one question.
     *
     * The database would refuse this anyway — see the CHECK in Issues.sq — but as
     * a constraint violation, which reaches the agent as "that failed inside
     * Lunicle" and reads as our bug rather than its mistake. Refused here, in a
     * sentence, before anything is written.
     */
    @Test
    fun `naming both an account author and an external one is refused`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Both","author":"Ordinary",""" +
                    """"author_external":"octocat"}""",
            )
            assertTrue(result.isError, "An issue was written with two authors.")
            assertTrue(
                !result.text.contains("inside Lunicle"),
                "The pair reached the database and surfaced as a server error rather than a refusal.",
            )
        }
        assertEquals(emptyList(), issues.forProject(fixture.projectId))
    }

    /**
     * An imported comment keeps its author too.
     *
     * Separate from the issue case because the two write through different stores
     * and only one of them was ever going to be remembered.
     */
    @Test
    fun `an admin can attribute a comment to somebody with no account`(): Unit = runBlocking {
        val fixture = seed()
        val (issueId, _) = published(fixture)
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "add_comment",
                """{"issue_id":$issueId,"body":"Imported reply","author_external":"octocat",""" +
                    """"created_at":$backfilledAt}""",
            )
            assertTrue(!result.isError, result.text)
        }

        val comment = comments.forIssue(issueId).single()
        assertEquals(Author.External("octocat"), comment.author)
        assertEquals(backfilledAt, comment.createdAt)
    }

    /**
     * The name reaches a reader, on both surfaces.
     *
     * The point of storing it. A `created_by_external` that is written and then
     * rendered as "no author" is the same board the import was trying to avoid,
     * and the two read paths decide this separately — see `Author.displayName`,
     * which exists so they cannot decide it differently.
     */
    @Test
    fun `an external author is rendered as the author over MCP`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Imported","author_external":"octocat"}""",
            )
            // Parsed rather than substring-matched: the tools pretty-print, so
            // `"author":"octocat"` is not a string that appears in the output
            // even when the answer is right.
            val board = client.callTool(token, "get_board", """{"project_id":${fixture.projectId}}""")
            val card = Json.parseToJsonElement(board.text).jsonObject["issues"]!!.jsonArray.single().jsonObject
            assertEquals(
                "octocat",
                card["author"]?.jsonPrimitive?.contentOrNull,
                "get_board did not report the imported author.",
            )

            val issueId = issues.forProject(fixture.projectId).single().id
            val detail = client.callTool(token, "get_issue", """{"issue_id":$issueId}""")
            assertEquals(
                "octocat",
                Json.parseToJsonElement(detail.text).jsonObject["author"]?.jsonPrimitive?.contentOrNull,
                "get_issue did not report the imported author.",
            )
        }
    }

    /**
     * An imported issue belongs to nobody, so nobody inherits it.
     *
     * The consequence of there being no account: `created_by` is null, which
     * [AccessControl.canEditIssue] already reads as unowned. Worth pinning
     * because the tempting "fix" — matching an external name against display
     * names at read time — would hand a stranger edit rights over imported
     * history by virtue of sharing a GitHub handle.
     */
    @Test
    fun `an imported issue is not editable by a user who happens to share the name`(): Unit = runBlocking {
        val fixture = seed()
        // An ordinary account whose display name IS the imported handle.
        val impostor = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "google-octocat", "octocat", null))
        withMcp { client ->
            client.callTool(
                tokenFor(fixture.adminId),
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Imported","author_external":"octocat"}""",
            )
        }

        val issue = issues.forProject(fixture.projectId).single()
        assertEquals(Author.External("octocat"), issue.author)
        assertTrue(
            !access.canEditIssue(impostor, issue),
            "A user called \"octocat\" inherited an issue imported from a GitHub user called \"octocat\".",
        )
    }

    // ── The author must be a real row ────────────────────────────────────────

    /**
     * An unknown name is a sentence, not a 500.
     *
     * `issues.created_by` has a foreign key, so the alternative to resolving this
     * here is a constraint violation — which reaches the agent as "that failed
     * inside Lunicle", the one message McpServer sends when the *server* is broken.
     * An agent told that has no reason to think it typed a name wrong.
     */
    @Test
    fun `an unknown author is refused with an explanation`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Ghost","author":"Nobody At All"}""",
            )
            assertTrue(result.isError, "An issue was attributed to an account that does not exist.")
            assertTrue(
                result.text.contains("no Lunicle account"),
                "The refusal must be about the name, not about the database. Got: ${result.text}",
            )
            assertTrue(
                !result.text.contains("inside Lunicle"),
                "A bad author reached the database and surfaced as a server error.",
            )
        }
        assertEquals(emptyList(), issues.forProject(fixture.projectId))
    }

    /**
     * Two accounts, one name: refused rather than guessed.
     *
     * `users` carries no UNIQUE on the display name and cannot — the same human via
     * Google and GitHub is two accounts by design (see Users.sq) — so this is a
     * fixture the real instance produces, not a contrived one. Picking either
     * account would attribute imported history to a stranger silently and forever.
     */
    @Test
    fun `an ambiguous author is refused rather than guessed`(): Unit = runBlocking {
        val fixture = seed()
        val twin = users.upsert(
            ProviderIdentity(AuthProvider.GOOGLE, "google-twin", "Ordinary", "twin@example.com"),
        )
        assertTrue(twin.id != fixture.ordinaryId)
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Which one?","author":"Ordinary"}""",
            )
            assertTrue(result.isError, "An ambiguous author was resolved to somebody by guessing.")
            assertTrue(result.text.contains("email address"), result.text)
        }
        assertEquals(emptyList(), issues.forProject(fixture.projectId))
    }

    // ── Nonsense timestamps ──────────────────────────────────────────────────

    /**
     * The year 3000, refused.
     *
     * The board's secondary sort is `updated_at DESC`, so this row would be one
     * card pinned to the top of its column forever, with nothing in the UI saying
     * why and no tool able to move it. Admin or not, this is not a thing to store.
     */
    @Test
    fun `a far-future created_at is refused even for an admin`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Year 3000","created_at":32503680000000}""",
            )
            assertTrue(result.isError, "An issue was stamped in the year 3000.")
            assertTrue(result.text.contains("future"), result.text)
        }
        assertEquals(emptyList(), issues.forProject(fixture.projectId))
    }

    /** Before 1970 is nonsense outright, and nonsense is refused rather than stored. */
    @Test
    fun `a negative created_at is refused`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "add_comment",
                """{"issue_id":${published(fixture).first},"body":"Before time","created_at":-5}""",
            )
            assertTrue(result.isError, "A comment was stamped before the epoch.")
        }
    }

    // ── The default path is untouched ────────────────────────────────────────

    /**
     * Neither parameter, and today's behaviour exactly — for a non-admin.
     *
     * The regression that matters most: two optional parameters landed on the two
     * most-used write tools, and the overwhelming majority of calls pass neither.
     * The author must be the token's own user and the stamp must be the clock.
     *
     * Note what is deliberately NOT asserted: that `created_at` equals
     * `updated_at`. It does not, and never did — `insertDraft` binds one `now()` to
     * both columns, and then `publish` stamps `updated_at` with a *second* reading a
     * millisecond or two later. That is correct and is the behaviour being
     * preserved: the single-`now()` rule in Issues.sq is about the two columns at
     * insert time, not a claim that a published issue is never touched after it is
     * created. The backfill test above asserts equality precisely because there the
     * two are one supplied value with no clock between them.
     */
    @Test
    fun `a non-admin filing an ordinary issue is authored by their token and stamped now`(): Unit = runBlocking {
        val fixture = seed()
        val before = System.currentTimeMillis()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"An ordinary bug"}""",
            )
            assertTrue(!result.isError, "The ordinary create_issue path broke: ${result.text}")
        }

        val issue = issues.forProject(fixture.projectId).single()
        assertEquals(Author.Account(fixture.ordinaryId), issue.author, "An ordinary issue lost its author.")
        assertTrue(
            issue.createdAt >= before && issue.createdAt <= System.currentTimeMillis(),
            "An ordinary issue was not stamped now: ${issue.createdAt}",
        )
    }

    /**
     * And for an admin, who *could* have attributed it and did not.
     *
     * Worth its own test: "may attribute" and "is attributing" are different
     * questions, and an implementation that answered the first would file every
     * admin's ordinary issue under a null author or a resolved-from-nothing name.
     */
    @Test
    fun `an admin filing an ordinary issue is authored by their token and stamped now`(): Unit = runBlocking {
        val fixture = seed()
        val before = System.currentTimeMillis()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"An admin's bug"}""",
            )
            assertTrue(!result.isError, result.text)
        }

        val issue = issues.forProject(fixture.projectId).single()
        assertEquals(Author.Account(fixture.adminId), issue.author)
        assertTrue(issue.createdAt >= before)
    }

    /**
     * `null` is not asking, and an ordinary user must not be refused for it.
     *
     * Models fill in every property a schema mentions and write `null` for the ones
     * they have nothing to say about. Treating that as "asked for attribution"
     * would make `add_comment` fail for a whole class of client, for nothing.
     */
    @Test
    fun `an explicit null author is treated as absent rather than as a request`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.ordinaryId)

        withMcp { client ->
            val result = client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Nulls","author":null,"created_at":null}""",
            )
            assertTrue(!result.isError, "A client that spelled out its nulls was refused: ${result.text}")
        }
        assertEquals(Author.Account(fixture.ordinaryId), issues.forProject(fixture.projectId).single().author)
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val adminId: Long, val ordinaryId: Long, val projectId: Long)

    /**
     * The instance admin, one ordinary user who may file and comment, and a project.
     *
     * The admin is whoever signs in first — see Users.sq's upsert — so the order
     * here is load-bearing rather than incidental. The ordinary user is granted
     * `create_issue` and `comment_on_issue` explicitly, because otherwise every
     * non-admin test below would be refused for the wrong reason and would pass
     * against a server with no backfill check at all.
     *
     * `roles.seed()` first, as Application.module does at every startup: `grant` is
     * an INSERT..SELECT that turns a role_key into a role_id, so against an
     * unseeded `roles` table it selects nothing, inserts nothing, and reports
     * success. Every non-admin test then fails with "You cannot create issues in
     * this project" — a refusal, from the wrong rule, that looks exactly like the
     * one being tested for.
     */
    private suspend fun seed(): Fixture {
        roles.seed()
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin", "Admin", "admin@example.com"))
        val ordinary = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", "ordinary@example.com"),
        )
        assertTrue(!ordinary.isAdmin, "The fixture's second user is somehow an admin.")
        val project = projectRepository.create("Lunamux", "LMX", isPublic = false)
        roles.grant(ordinary.id, project.id, Role.CREATE_ISSUE)
        roles.grant(ordinary.id, project.id, Role.COMMENT_ON_ISSUE)
        return Fixture(admin.id, ordinary.id, project.id)
    }

    /** A published issue to hang comments off. */
    private suspend fun published(fixture: Fixture): Pair<Long, Long> {
        val created = issueRepository.createDraft(fixture.projectId, Author.Account(fixture.adminId))
        val issue = issues.findById(created.first)!!
        issueRepository.save(
            issue = issue,
            title = "Something",
            description = "",
            statusId = statuses.forProject(fixture.projectId).first().id,
            priorityId = priorities.defaultForProject(fixture.projectId)!!.id,
            resolutionId = null,
            labelIds = emptyList(),
            componentIds = emptyList(),
        )
        return created
    }

    /**
     * A real access token for [userId], through the real store.
     *
     * The point of the file: what the server trusts is this string, and the admin
     * question is asked of whoever it resolves to. `setMcpEnabled` is not optional
     * — [resolveMcpUser] re-reads it on every request and a token whose owner has
     * MCP off is a 401, which would make every test here fail identically and for
     * the wrong reason.
     */
    private suspend fun tokenFor(userId: Long): String {
        users.setMcpEnabled(userId, true)
        val client = clients.register("Test agent", listOf("http://localhost:1234/callback"), listOf("authorization_code"))
        return tokens.issueTokens(userId, client.clientId, "mcp", "http://localhost/mcp").accessToken
    }

    private class ToolOutcome(val text: String, val isError: Boolean)

    /**
     * Call one tool over JSON-RPC and unwrap the result.
     *
     * `isError` is a *tool* error rather than a JSON-RPC one — the call was
     * understood and the answer is no — so this asserts the transport succeeded and
     * hands back only what an agent would actually read. See McpToolResult.
     */
    private suspend fun HttpClient.callTool(token: String, name: String, arguments: String): ToolOutcome {
        val response = post(MCP_PATH) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"$name","arguments":$arguments}}
                """.trimIndent(),
            )
        }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["error"] == null, "JSON-RPC error rather than a tool result: $body")
        val result = assertNotNull(body["result"], "No result in $body").jsonObject
        val text = result["content"]!!.jsonArray
            .joinToString("\n") { (it as JsonObject)["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        return ToolOutcome(text, result["isError"]?.jsonPrimitive?.contentOrNull == "true")
    }

    /**
     * Mount the real `/mcp` and hand back a client.
     *
     * Only `mcpRoutes` plus content negotiation — not `Application.module`, which
     * would want OAuth credentials and a static bundle that nothing here has an
     * opinion about. ContentNegotiation is installed because production installs it,
     * and McpServer's 405/401 handlers exist specifically to survive it.
     */
    private fun withMcp(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing { mcpRoutes(mcpDependencies(), McpTools(boardDependencies())) }
        }
        block(createClient { })
    }

    private fun mcpDependencies() = McpDependencies(
        clients = clients,
        loginStates = loginStates,
        codes = codes,
        tokens = tokens,
        sessions = sessions,
        users = users,
        impersonations = Impersonations(),
        config = OAuthConfig(google = null, github = null),
    )

    private fun boardDependencies() = BoardDependencies(
        access = access,
        projects = projects,
        projectRepository = projectRepository,
        roles = roles,
        vocabularies = vocabularies,
        labels = labels,
        components = components,
        statuses = statuses,
        priorities = priorities,
        resolutions = resolutions,
        issues = issues,
        issueRepository = issueRepository,
        comments = comments,
        attachments = attachmentStore,
        attachmentRepository = attachments,
        attachmentTickets = AttachmentTicketStore(),
        sessions = sessions,
        users = users,
        impersonations = Impersonations(),
    )
}
