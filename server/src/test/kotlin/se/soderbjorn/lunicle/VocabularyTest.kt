/**
 * The refusals that keep the settings dialog from breaking a project.
 *
 * Three of these guard things that are either invisible or unrecoverable when
 * they break, which is why they are here rather than left to a pass through the
 * browser:
 *
 *  - **Deleting a status that is in use.** The database refuses this on its own —
 *    RESTRICT, via a NOT NULL composite foreign key with no ON DELETE clause. So
 *    the risk is not corruption, it is the *shape* of the refusal: a raw
 *    constraint violation is a 500 that says the server broke, about a request
 *    that was merely wrong. A regression here looks like a working feature until
 *    an admin tries it.
 *  - **Deleting the last status, or the last priority.** Nothing in the schema
 *    objects: an unused status is deletable as far as SQLite is concerned, and the
 *    last status of an empty project is exactly that. The result is a project that
 *    cannot take an issue — `IssueRepository.createDraft` errors — and cannot be
 *    repaired from the UI, because you cannot file the issue that would tell you.
 *    This is the one rule here that has no backstop at all.
 *  - **A non-admin doing any of it.** Through the real route with a real session
 *    cookie, because "the client is a renderer of permissions" is only true if the
 *    server actually refuses. A test against `AccessControl` alone would pass on a
 *    route that never called it.
 *
 * Through the real driver, the real pragmas and the real stores, for the reason
 * ForeignKeyTest's preamble gives at length: RESTRICT is a foreign key, and a
 * test that opened its own connection with its own settings would have passed
 * throughout the bug that file exists for.
 *
 * @see VocabularyRepository
 * @see projectSettingsRoutes
 */
package se.soderbjorn.lunicle

import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.VocabularyAdd
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class VocabularyTest {
    private val file: File = Files.createTempFile("lunicle-vocab", ".db").toFile().also { it.delete() }
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

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── In use ───────────────────────────────────────────────────────────────

    /**
     * The one that turns a 500 into an explanation.
     *
     * Asserting on the *count in the sentence* rather than merely on the type:
     * "some issues are still in that status" would satisfy a weaker test and
     * would not tell an admin how much work moving them is. The number is the
     * feature.
     */
    @Test
    fun `deleting a status that issues are in is refused, and says how many`(): Unit = runBlocking {
        val fixture = seed()
        val status = statuses.forProject(fixture.projectId).first()
        // Two issues, both in the seeded leftmost column — which is where
        // createDraft puts them.
        issueRepository.createDraft(fixture.projectId, fixture.adminId)
        issueRepository.createDraft(fixture.projectId, fixture.adminId)

        val row = vocabularies.find(fixture.projectId, VocabularyKind.STATUS, status.id)
        assertNotNull(row)
        val refusal = assertFailsWith<VocabularyRefusal> {
            vocabularies.delete(fixture.projectId, VocabularyKind.STATUS, row)
        }

        assertEquals(
            "2 issues are still in that status. Move them somewhere else first, and then delete it.",
            refusal.userMessage,
            "The refusal must name the count; an admin cannot act on \"it is in use\".",
        )
        assertTrue(
            statuses.forProject(fixture.projectId).any { it.id == status.id },
            "The status was deleted despite the refusal.",
        )
    }

    /**
     * Drafts count, and this is the test that says so.
     *
     * Every other query on `issues` excludes drafts, so filtering them here is the
     * obvious-looking change — and it would produce the worst outcome available: a
     * dialog that says nothing uses the status, followed by a constraint violation
     * nobody can explain from the UI. `createDraft` alone leaves the issue a draft,
     * so this is exactly that scenario.
     *
     * The singular sentence is pinned here rather than in the test above, since
     * this is the only fixture with exactly one issue in a column — and a message
     * that reads "1 issue are in that status" is how you tell nobody looked.
     */
    @Test
    fun `a draft issue is enough to block deleting its status`(): Unit = runBlocking {
        val fixture = seed()
        issueRepository.createDraft(fixture.projectId, fixture.adminId)
        val status = statuses.forProject(fixture.projectId).first()
        val row = vocabularies.find(fixture.projectId, VocabularyKind.STATUS, status.id)!!

        val refusal = assertFailsWith<VocabularyRefusal> {
            vocabularies.delete(fixture.projectId, VocabularyKind.STATUS, row)
        }
        assertEquals(
            "1 issue is still in that status. Move it somewhere else first, and then delete it.",
            refusal.userMessage,
        )
    }

    /** A resolution a closed issue holds is refused too — nullable is not SET NULL. */
    @Test
    fun `deleting a resolution that a closed issue holds is refused`(): Unit = runBlocking {
        val fixture = seed()
        val closing = statuses.forProject(fixture.projectId).first { it.requiresResolution }
        val resolution = resolutions.forProject(fixture.projectId).first()
        val (issueId, _) = issueRepository.createDraft(fixture.projectId, fixture.adminId)
        issues.setStatus(issueId, closing.id, resolution.id)

        val row = vocabularies.find(fixture.projectId, VocabularyKind.RESOLUTION, resolution.id)!!
        assertFailsWith<VocabularyRefusal>("A resolution in use was deleted, orphaning a closed issue.") {
            vocabularies.delete(fixture.projectId, VocabularyKind.RESOLUTION, row)
        }
    }

    /**
     * A label in use is NOT refused — it cascades, and the issues survive.
     *
     * The mirror of the tests above, and worth having: the rule is "do not strand
     * an issue", not "never delete anything in use". A refusal here would be a
     * label vocabulary nobody could ever tidy.
     */
    @Test
    fun `deleting a label that issues wear unlabels them and leaves the issues`(): Unit = runBlocking {
        val fixture = seed()
        val label = labels.forProject(fixture.projectId).first()
        val (issueId, _) = issueRepository.createDraft(fixture.projectId, fixture.adminId)
        issues.setLabelsAndComponents(issueId, fixture.projectId, listOf(label.id), emptyList())
        assertEquals(listOf(label.id), issues.labelsFor(issueId))

        val row = vocabularies.find(fixture.projectId, VocabularyKind.LABEL, label.id)!!
        assertEquals(1L, row.usageCount, "The count the confirmation shows is wrong.")
        vocabularies.delete(fixture.projectId, VocabularyKind.LABEL, row)

        assertTrue(labels.forProject(fixture.projectId).none { it.id == label.id })
        assertNotNull(issues.findById(issueId), "Deleting a label deleted the issue wearing it.")
        assertEquals(emptyList(), issues.labelsFor(issueId), "The issue_labels row outlived its label.")
    }

    // ── The last one ─────────────────────────────────────────────────────────

    /**
     * The rule with no backstop.
     *
     * Nothing in the schema objects to this — the statuses are unused, so RESTRICT
     * has nothing to say — and the result is a project that cannot take an issue
     * and cannot be repaired from the dialog that broke it.
     */
    @Test
    fun `the last status cannot be deleted`(): Unit = runBlocking {
        val fixture = seed()
        // Empty every column but one. All unused, so each of these must succeed —
        // which is also the assertion that the rule is "the last one", not "any
        // one".
        val all = statuses.forProject(fixture.projectId)
        all.drop(1).forEach { status ->
            val row = vocabularies.find(fixture.projectId, VocabularyKind.STATUS, status.id)!!
            vocabularies.delete(fixture.projectId, VocabularyKind.STATUS, row)
        }
        assertEquals(1, statuses.forProject(fixture.projectId).size)

        val last = vocabularies.find(fixture.projectId, VocabularyKind.STATUS, all.first().id)!!
        val refusal = assertFailsWith<VocabularyRefusal> {
            vocabularies.delete(fixture.projectId, VocabularyKind.STATUS, last)
        }
        assertTrue(refusal.userMessage.contains("at least one status"), refusal.userMessage)

        // The point of the rule, asserted rather than implied: the project can
        // still take an issue.
        issueRepository.createDraft(fixture.projectId, fixture.adminId)
    }

    /** The same rule for priorities, which `createDraft` also cannot do without. */
    @Test
    fun `the last priority cannot be deleted`(): Unit = runBlocking {
        val fixture = seed()
        val all = priorities.forProject(fixture.projectId)
        all.drop(1).forEach { priority ->
            val row = vocabularies.find(fixture.projectId, VocabularyKind.PRIORITY, priority.id)!!
            vocabularies.delete(fixture.projectId, VocabularyKind.PRIORITY, row)
        }

        val last = vocabularies.find(fixture.projectId, VocabularyKind.PRIORITY, all.first().id)!!
        assertFailsWith<VocabularyRefusal> {
            vocabularies.delete(fixture.projectId, VocabularyKind.PRIORITY, last)
        }
        issueRepository.createDraft(fixture.projectId, fixture.adminId)
    }

    /**
     * The last resolution CAN go, and that asymmetry is deliberate.
     *
     * A project with no resolutions still takes issues, and the dialog that
     * emptied the list can refill it. The rule is "never become unrepairable", not
     * "never run out" — see VocabularyKind.isLoadBearing.
     */
    @Test
    fun `the last resolution can be deleted`(): Unit = runBlocking {
        val fixture = seed()
        resolutions.forProject(fixture.projectId).forEach { resolution ->
            val row = vocabularies.find(fixture.projectId, VocabularyKind.RESOLUTION, resolution.id)!!
            vocabularies.delete(fixture.projectId, VocabularyKind.RESOLUTION, row)
        }
        assertEquals(0, resolutions.forProject(fixture.projectId).size)
        issueRepository.createDraft(fixture.projectId, fixture.adminId)
    }

    // ── Names and order ──────────────────────────────────────────────────────

    /**
     * Case-insensitively, and beyond ASCII.
     *
     * The UNIQUE index would catch "closed" against "Closed" on its own. It would
     * NOT catch this: `COLLATE NOCASE` folds ASCII A–Z only, so the constraint
     * lets `FÄRDIG` and `färdig` both in, and a human calls those one status. This
     * is the half of the rule that only exists in Kotlin.
     */
    @Test
    fun `a name that differs only by case is refused, including outside ASCII`(): Unit = runBlocking {
        val fixture = seed()
        vocabularies.add(fixture.projectId, VocabularyKind.LABEL, "Färdig")

        val conflict = assertFailsWith<VocabularyConflict> {
            vocabularies.add(fixture.projectId, VocabularyKind.LABEL, "färdig")
        }
        assertTrue(conflict.userMessage.contains("Färdig"), conflict.userMessage)
    }

    /**
     * A new row goes last, and its position does not collide with a survivor's.
     *
     * `max + 1`, not `count`: this project's positions have a hole in them after
     * the delete, so a count-based position would land on top of an existing row —
     * and nothing would complain, because `position` cannot be UNIQUE. The board
     * would simply order two columns arbitrarily.
     */
    @Test
    fun `adding after deleting a middle row does not reuse a position`(): Unit = runBlocking {
        val fixture = seed()
        val middle = statuses.forProject(fixture.projectId)[2]
        vocabularies.delete(
            fixture.projectId,
            VocabularyKind.STATUS,
            vocabularies.find(fixture.projectId, VocabularyKind.STATUS, middle.id)!!,
        )
        vocabularies.add(fixture.projectId, VocabularyKind.STATUS, "Blocked")

        val positions = statuses.forProject(fixture.projectId).map { it.position }
        assertEquals(positions.distinct(), positions, "Two statuses share a position.")
        assertEquals("Blocked", statuses.forProject(fixture.projectId).last().name)
    }

    /** Reordering rewrites every position, and closes the gaps a delete left. */
    @Test
    fun `reordering rewrites positions from zero`(): Unit = runBlocking {
        val fixture = seed()
        val before = statuses.forProject(fixture.projectId)
        val reversed = before.map { it.id }.reversed()

        vocabularies.reorder(fixture.projectId, VocabularyKind.STATUS, reversed)

        val after = statuses.forProject(fixture.projectId)
        assertEquals(reversed, after.map { it.id }, "The order is not what was asked for.")
        assertEquals((0L until after.size.toLong()).toList(), after.map { it.position })
    }

    /**
     * A partial order is refused rather than partly applied.
     *
     * The rows left out would keep positions that now collide with the ones named,
     * and — again — nothing at the schema level would object.
     */
    @Test
    fun `an order that is missing rows is refused`(): Unit = runBlocking {
        val fixture = seed()
        val ids = statuses.forProject(fixture.projectId).map { it.id }

        assertFailsWith<VocabularyRefusal> {
            vocabularies.reorder(fixture.projectId, VocabularyKind.STATUS, ids.drop(1))
        }
        assertEquals(ids, statuses.forProject(fixture.projectId).map { it.id }, "A refused reorder still wrote.")
    }

    /** Labels have no order, and asking to give them one is a refusal, not a no-op. */
    @Test
    fun `reordering an unordered kind is refused`(): Unit = runBlocking {
        val fixture = seed()
        val ids = labels.forProject(fixture.projectId).map { it.id }
        assertFailsWith<VocabularyRefusal> {
            vocabularies.reorder(fixture.projectId, VocabularyKind.LABEL, ids)
        }
    }

    /**
     * Every refusal is a sentence, so every plural has to be a word.
     *
     * `noun + "s"` is the obvious way to write these and it produces "statuss" and
     * "prioritys". That is not hypothetical: it shipped, and got as far as a
     * manual pass through the running server before anybody saw it — which is
     * exactly how far a refusal nobody triggers in a test would get. English
     * plurals are data; this pins them.
     */
    @Test
    fun `a bad reorder names the vocabulary in English`(): Unit = runBlocking {
        val fixture = seed()

        val statuses = assertFailsWith<VocabularyRefusal> {
            vocabularies.reorder(fixture.projectId, VocabularyKind.STATUS, emptyList())
        }
        assertEquals("That order does not name this project's statuses.", statuses.userMessage)

        val priorities = assertFailsWith<VocabularyRefusal> {
            vocabularies.reorder(fixture.projectId, VocabularyKind.PRIORITY, emptyList())
        }
        assertEquals("That order does not name this project's priorities.", priorities.userMessage)

        val labelsRefusal = assertFailsWith<VocabularyRefusal> {
            vocabularies.reorder(fixture.projectId, VocabularyKind.LABEL, emptyList())
        }
        assertEquals(
            "Labels have no order — they are listed by name.",
            labelsRefusal.userMessage,
        )
    }

    /**
     * The magic flag is data, and it moves.
     *
     * The whole reason `requires_resolution` is a column rather than
     * `name = 'Closed'` — see Statuses.sq — and therefore the reason the dialog is
     * allowed to edit it. Renaming the seeded column must not switch the rule off.
     */
    @Test
    fun `renaming the closing status keeps its flag, and the flag can move`(): Unit = runBlocking {
        val fixture = seed()
        val closing = statuses.forProject(fixture.projectId).first { it.requiresResolution }
        val other = statuses.forProject(fixture.projectId).first { !it.requiresResolution }

        vocabularies.rename(
            fixture.projectId,
            VocabularyKind.STATUS,
            vocabularies.find(fixture.projectId, VocabularyKind.STATUS, closing.id)!!,
            name = "Avslutad",
            requiresResolution = true,
        )
        assertTrue(
            statuses.forProject(fixture.projectId).first { it.id == closing.id }.requiresResolution,
            "Renaming the closing column switched off the rule keyed on it.",
        )

        vocabularies.rename(
            fixture.projectId,
            VocabularyKind.STATUS,
            vocabularies.find(fixture.projectId, VocabularyKind.STATUS, other.id)!!,
            name = other.name,
            requiresResolution = true,
        )
        assertEquals(
            2,
            statuses.forProject(fixture.projectId).count { it.requiresResolution },
            "A project must be able to have a second column that demands a resolution.",
        )
    }

    // ── Who may ──────────────────────────────────────────────────────────────

    /**
     * A signed-in non-admin is refused every route in the file, through HTTP.
     *
     * Through the real routes with a real session cookie, because that is the only
     * thing being claimed. `AccessControl.canMutateProjects` returning false for a
     * non-admin is not the property that matters — a route that never called it
     * would pass that test and ship an open endpoint.
     *
     * The read is included on purpose: refusing the writes and shipping the
     * response anyway would hand every signed-in user a directory of every account
     * on the instance. That is the half BoardRoutes' preamble names as the one
     * that gets forgotten.
     */
    @Test
    fun `a non-admin is refused every settings route`(): Unit = runBlocking {
        val fixture = seed()
        val ordinary = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", null),
        )
        assertTrue(!ordinary.isAdmin, "The fixture's second user is somehow an admin.")
        val cookie = sessions.create(ordinary.id)
        val status = statuses.forProject(fixture.projectId).first()
        val label = labels.forProject(fixture.projectId).first()

        withRoutes { client ->
            val settings = client.get("/api/projects/${fixture.projectId}/settings") {
                cookie(SESSION_COOKIE, cookie)
            }
            assertEquals(
                HttpStatusCode.Forbidden,
                settings.status,
                "A non-admin was handed the settings — including every account on the instance.",
            )

            val added = client.post("/api/projects/${fixture.projectId}/vocabulary/label") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(VocabularyAdd("Sneaky"))
            }
            assertEquals(HttpStatusCode.Forbidden, added.status)

            val deleted = client.delete(
                "/api/projects/${fixture.projectId}/vocabulary/status/${status.id}",
            ) { cookie(SESSION_COOKIE, cookie) }
            assertEquals(HttpStatusCode.Forbidden, deleted.status)

            val reordered = client.post("/api/projects/${fixture.projectId}/vocabulary/status/order") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody("""{"ids":[${status.id}]}""")
            }
            assertEquals(HttpStatusCode.Forbidden, reordered.status)

            val granted = client.post("/api/projects/${fixture.projectId}/roles") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody("""{"userId":${ordinary.id},"roleKey":"create_issue","isGranted":true}""")
            }
            assertEquals(
                HttpStatusCode.Forbidden,
                granted.status,
                "A non-admin granted themselves a role.",
            )
        }

        // Nothing was written by any of it.
        assertTrue(labels.forProject(fixture.projectId).none { it.name == "Sneaky" })
        assertTrue(labels.forProject(fixture.projectId).any { it.id == label.id })
        assertTrue(statuses.forProject(fixture.projectId).any { it.id == status.id })
        assertEquals(emptySet(), roles.rolesFor(ordinary.id, fixture.projectId))
    }

    /** No session at all is refused too, rather than falling through to a null user. */
    @Test
    fun `a signed-out caller is refused the settings`(): Unit = runBlocking {
        val fixture = seed()
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/projects/${fixture.projectId}/settings").status,
            )
        }
    }

    /** The admin gets through — otherwise the tests above prove only that nothing works. */
    @Test
    fun `the admin can read the settings and add a label`(): Unit = runBlocking {
        val fixture = seed()
        val cookie = sessions.create(fixture.adminId)

        withRoutes { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/projects/${fixture.projectId}/settings") {
                    cookie(SESSION_COOKIE, cookie)
                }.status,
            )
            val added = client.post("/api/projects/${fixture.projectId}/vocabulary/label") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(VocabularyAdd("Regression"))
            }
            assertEquals(HttpStatusCode.OK, added.status)
        }
        assertTrue(labels.forProject(fixture.projectId).any { it.name == "Regression" })
    }

    /**
     * A row id from another project answers 404 rather than being edited.
     *
     * Admin is admin everywhere, so this is not what stops an attacker — it is
     * what stops a confused client from renaming the wrong project's column
     * because it sent an id it had lying around.
     */
    @Test
    fun `a status from another project is not reachable through this project's URL`(): Unit = runBlocking {
        val a = seed()
        val b = seed(name = "Beta", prefix = "BET")
        val foreign = statuses.forProject(b.projectId).first()
        val cookie = sessions.create(a.adminId)

        withRoutes { client ->
            assertEquals(
                HttpStatusCode.NotFound,
                client.delete("/api/projects/${a.projectId}/vocabulary/status/${foreign.id}") {
                    cookie(SESSION_COOKIE, cookie)
                }.status,
            )
        }
        assertTrue(statuses.forProject(b.projectId).any { it.id == foreign.id })
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val adminId: Long, val projectId: Long)

    /**
     * A project, seeded exactly as the real one is, and the instance admin.
     *
     * `ProjectRepository.create` rather than hand-inserted rows: the whole subject
     * of this file is what happens when a *seeded* project is edited, and a
     * fixture that made up its own five statuses would be testing a project shape
     * that never exists.
     *
     * The admin is whoever signs in first — see Users.sq's upsert — so the first
     * call here produces one, and every later user in a test is ordinary.
     */
    private suspend fun seed(name: String = "Lunamux", prefix: String = "LMX"): Fixture {
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin", "Admin", null))
        val project = projectRepository.create(name, prefix, isPublic = false)
        return Fixture(admin.id, project.id)
    }

    /**
     * Mount the real routes and hand back a client.
     *
     * Only `boardRoutes` and content negotiation — not `Application.module`, which
     * would want OAuth configuration, a static bundle and a database path, none of
     * which any test here has an opinion about. The routes under test are reached
     * exactly as they are in production: same handlers, same session cookie, same
     * AccessControl.
     */
    private fun withRoutes(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing { boardRoutes(dependencies()) }
        }
        val client = createClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
        }
        block(client)
    }

    private fun dependencies() = BoardDependencies(
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
        sessions = sessions,
        users = users,
        impersonations = Impersonations(),
    )
}
