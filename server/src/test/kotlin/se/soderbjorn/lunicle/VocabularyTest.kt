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
import io.ktor.client.call.body
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.VocabularyAdd
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    private val sprints = SprintStore(database)
    private val versions = VersionStore(database)
    private val issues = IssueStore(database)
    private val comments = CommentStore(database)
    private val attachmentStore = AttachmentStore(database)
    private val attachments = AttachmentRepository(attachmentStore, File(file.parentFile, "attachments-${file.name}"))
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
    private val issueRepository =
        IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)
    private val sprintRepository = SprintRepository(database, sprints, projects, issues, statuses)
    private val vocabularies =
        VocabularyRepository(database, labels, components, statuses, priorities, resolutions, sprints, versions, issues)
    private val instanceSettings = InMemoryInstanceSettingsStore()
    private val access = AccessControl(roles, instanceSettings)

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
        // Two issues, both in the seeded leftmost column — which is where a new
        // issue lands. Published, because only published issues count: an unsaved
        // one is nobody's to move. See the draft test below.
        fileIssue(fixture)
        fileIssue(fixture)

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

    /** The singular sentence, which is the tell that somebody read it: "1 issue is", never "1 issue are". */
    @Test
    fun `the refusal agrees with a count of one`(): Unit = runBlocking {
        val fixture = seed()
        fileIssue(fixture)
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

    /**
     * Drafts do NOT count, and this is the test that says so (LNL-183).
     *
     * It used to assert the opposite, on reasoning that was sound and a result that
     * was not: a draft holds a `status_id`, so RESTRICT refuses the delete, so the
     * count had better include it or the dialog promises a delete that explodes.
     * What that produced in the field was a first column no admin could ever
     * delete — a draft lands in the leftmost one, an abandoned draft is never
     * collected, and the refusal named issues that are on nobody's board.
     *
     * So the count is about the visible board and the draft is taken with the
     * column. Both halves are asserted, because excluding drafts from the count
     * alone would trade a wrong refusal for a raw constraint violation.
     */
    @Test
    fun `a draft does not block deleting its status, and goes with it`(): Unit = runBlocking {
        val fixture = seed()
        val (draftId, _) = issueRepository.createDraft(fixture.projectId, Author.Account(fixture.adminId))
        val status = statuses.forProject(fixture.projectId).first()
        val row = vocabularies.find(fixture.projectId, VocabularyKind.STATUS, status.id)!!
        assertEquals(0L, row.usageCount, "an unpublished issue is on nobody's board and counts against nothing")

        vocabularies.delete(fixture.projectId, VocabularyKind.STATUS, row)

        assertTrue(
            statuses.forProject(fixture.projectId).none { it.id == status.id },
            "The column was refused over a draft nobody can see.",
        )
        assertNull(issues.findById(draftId), "The draft outlived the column it was sitting in.")
    }

    /** The sweep the drafts that are never deleted this way are collected by. */
    @Test
    fun `the startup sweep takes abandoned drafts and leaves fresh ones`(): Unit = runBlocking {
        val fixture = seed()
        val status = statuses.forProject(fixture.projectId).first()
        val priority = priorities.forProject(fixture.projectId).first()
        val (old, _) = issues.insertDraft(
            fixture.projectId, "", status.id, priority.id, Author.Account(fixture.adminId), createdAt = 1_000,
        )
        val (fresh, _) = issueRepository.createDraft(fixture.projectId, Author.Account(fixture.adminId))

        assertEquals(1L, issues.sweepAbandonedDrafts(cutoff = 2_000))
        assertNull(issues.findById(old))
        assertNotNull(issues.findById(fresh), "A draft somebody may still be typing into was swept.")
    }

    /** A resolution a closed issue holds is refused too — nullable is not SET NULL. */
    @Test
    fun `deleting a resolution that a closed issue holds is refused`(): Unit = runBlocking {
        val fixture = seed()
        val closing = statuses.forProject(fixture.projectId).first { it.requiresResolution }
        val resolution = resolutions.forProject(fixture.projectId).first()
        val issueId = fileIssue(fixture)
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
        val (issueId, _) = issueRepository.createDraft(fixture.projectId, Author.Account(fixture.adminId))
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
        issueRepository.createDraft(fixture.projectId, Author.Account(fixture.adminId))
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
        issueRepository.createDraft(fixture.projectId, Author.Account(fixture.adminId))
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
        issueRepository.createDraft(fixture.projectId, Author.Account(fixture.adminId))
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

    /**
     * Labels are arrangeable like every other vocabulary. They were the exception
     * once — sorted by name, with the reorder refused outright — which read as a
     * missing feature because it was one. See LNL-28.
     */
    @Test
    fun `labels can be reordered`(): Unit = runBlocking {
        val fixture = seed()
        val ids = labels.forProject(fixture.projectId).map { it.id }

        vocabularies.reorder(fixture.projectId, VocabularyKind.LABEL, ids.reversed())

        assertEquals(
            ids.reversed(),
            labels.forProject(fixture.projectId).map { it.id },
            "Labels did not take the order they were given.",
        )
    }

    /** And so are components, which share the table shape and the old exception. */
    @Test
    fun `components can be reordered`(): Unit = runBlocking {
        val fixture = seed()
        val ids = components.forProject(fixture.projectId).map { it.id }

        vocabularies.reorder(fixture.projectId, VocabularyKind.COMPONENT, ids.reversed())

        assertEquals(
            ids.reversed(),
            components.forProject(fixture.projectId).map { it.id },
            "Components did not take the order they were given.",
        )
    }

    /**
     * A new label goes to the end, not into alphabetical place.
     *
     * The list is somebody's arrangement now, and dropping a new row into the
     * middle of it would rearrange a list the admin had just finished ordering.
     */
    @Test
    fun `a new label lands last`(): Unit = runBlocking {
        val fixture = seed()

        vocabularies.add(fixture.projectId, VocabularyKind.LABEL, "Aardvark")

        assertEquals(
            "Aardvark",
            labels.forProject(fixture.projectId).last().name,
            "A new label sorted itself into the list instead of joining the end.",
        )
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
        assertEquals("That order does not name this project's labels.", labelsRefusal.userMessage)
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
            isDone = false,
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
            isDone = false,
        )
        assertEquals(
            2,
            statuses.forProject(fixture.projectId).count { it.requiresResolution },
            "A project must be able to have a second column that demands a resolution.",
        )
    }

    // ── Who may ──────────────────────────────────────────────────────────────

    /**
     * A signed-in non-admin is refused every *write* in the file, through HTTP,
     * and handed a settings read narrowed to nothing they may not see.
     *
     * Through the real routes with a real session cookie, because that is the only
     * thing being claimed. `AccessControl.canMutateProjects` returning false for a
     * non-admin is not the property that matters — a route that never called it
     * would pass that test and ship an open endpoint.
     *
     * The read is the interesting half. It is now *narrowed*, not refused — the
     * dialog opens for every signed-in user — so this asserts the shape of that
     * narrowing: `canMutateProject` false, and the member directory and vocabulary
     * counts absent. Shipping the admin response to a non-admin — the failure
     * BoardRoutes' preamble names — would hand over a directory of every account
     * on the instance, and this is what proves that does not happen.
     */
    @Test
    fun `a non-admin is refused every settings write but gets a narrowed read`(): Unit = runBlocking {
        val fixture = seed()
        val ordinary = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", null),
        )
        assertTrue(!ordinary.isInstanceAdmin, "The fixture's second user is somehow an admin.")
        val cookie = sessions.create(ordinary.id)
        // Since LNL-57 a private project is invisible to somebody holding
        // nothing in it, and an invisible project answers 404 to every route —
        // which would satisfy the refusals below whether or not the admin gates
        // still existed, and would defeat the "narrowed read" half outright.
        // The bottom rung grants no ability at all (see ProjectRole.VIEWER), so a
        // caller seated on it is still exactly the non-admin this test means.
        roles.setRole(ordinary.id, fixture.projectId, ProjectRole.VIEWER)
        val status = statuses.forProject(fixture.projectId).first()
        val label = labels.forProject(fixture.projectId).first()

        withRoutes { client ->
            val settings = client.get("/api/projects/${fixture.projectId}/settings") {
                cookie(SESSION_COOKIE, cookie)
            }
            assertEquals(
                HttpStatusCode.OK,
                settings.status,
                "The settings dialog no longer opens for a signed-in non-admin.",
            )
            val body = settings.body<ProjectSettingsState>()
            assertTrue(!body.canMutateProject, "A non-admin was told they may configure the project.")
            assertTrue(
                body.access == null,
                "A viewer was handed the Access section, which carries other people's addresses.",
            )
            assertTrue(
                body.labels.isEmpty() && body.statuses.isEmpty(),
                "A non-admin was handed the admin-only vocabulary sections.",
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
        // Still exactly what the test granted at the top and nothing more — the
        // attempted self-promotion did not land. Spelled as the whole set rather
        // than "is not a contributor", so a write that smuggled in some *other*
        // rung fails here too.
        assertEquals(setOf(ProjectRole.VIEWER), setOfNotNull(roles.roleFor(ordinary.id, fixture.projectId)))
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
        val project = projectRepository.create(name, prefix)
        // Production seats the instance owner at boot (see InstanceLadder.kt), and
        // four rules — creating and managing projects, backfilling authorship, agent
        // mail, out-of-band attachment deletes — are the owner's alone rather than an
        // administrator's. A fixture that skipped this would be testing an instance
        // nobody runs: one with an administrator and no owner.
        seatInstanceOwner(users, instanceSettings)
        return Fixture(admin.id, project.id)
    }

    /**
     * File a published issue, the way the editor does: create the draft, then save
     * it. The save is what these tests are usually after — `createDraft` alone
     * leaves a row nothing counts and a column delete takes with it (LNL-183) —
     * and it lands in the leftmost status unless [statusId] says otherwise.
     */
    private suspend fun fileIssue(fixture: Fixture, statusId: Long? = null, resolutionId: Long? = null): Long {
        val (id, _) = issueRepository.createDraft(fixture.projectId, Author.Account(fixture.adminId))
        val issue = issues.findById(id)!!
        issueRepository.save(
            issue = issue,
            title = "Issue $id",
            description = "",
            statusId = statusId ?: issue.statusId,
            priorityId = issue.priorityId,
            resolutionId = resolutionId,
            assigneeId = null,
            sprintId = null,
            plannedVersionId = null,
            fixedVersionId = null,
            labelIds = emptyList(),
            componentIds = emptyList(),
        )
        return id
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
        forums = ForumRepository(ForumStore(database), attachments, attachmentStore),
        forumPosts = ForumPostRepository(
            ForumPostStore(database), ForumCommentStore(database), attachments, attachmentStore,
        ),
        audience = ProjectAudience(users, roles, instanceSettings),
        // Not exercised by this file; here because a route bundle is one object
        // and there is no half of it. See MessageTest for the tests that do.
        conversations = ConversationRepository(
            ConversationStore(database), MessageStore(database), attachments, attachmentStore,
        ),
        labels = labels,
        components = components,
        statuses = statuses,
        priorities = priorities,
        resolutions = resolutions,
        versions = versions,
        sprints = sprintRepository,
        sprintRepository = sprintRepository,
        issues = issues,
        issueRepository = issueRepository,
        comments = comments,
        attachments = attachmentStore,
        attachmentRepository = attachments,
        attachmentTickets = AttachmentTicketStore(),
        sessions = sessions,
        users = users,
        subscriptions = SubscriptionStore(database),
        reads = ReadStore(database),
    )
}
