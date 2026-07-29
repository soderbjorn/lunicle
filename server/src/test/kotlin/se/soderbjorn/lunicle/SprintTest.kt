/**
 * Sprints: the rules that are not in the schema, and the promise that is.
 *
 * Most of a sprint is machinery that already had tests — [VocabularyRepository]
 * does the add/rename/reorder/delete, and VocabularyTest covers that. What is
 * left is what this file is for, and it splits into two halves that fail very
 * differently:
 *
 *  - **The invariant nothing enforces.** `issues.sprint_id` is single-column, so
 *    unlike every other reference on that table there is no composite foreign key
 *    stopping an issue pointing at another project's sprint. That check exists in
 *    exactly one place — route validation — and if it is ever removed nothing
 *    breaks loudly: the write succeeds, the board silently drops a card it cannot
 *    name, and the damage is discovered much later by somebody who cannot explain
 *    it. See Issues.sq's sprint_id for why the key had to give way.
 *  - **The promise to projects that never opt in.** "A pure-kanban board is
 *    untouched" is the claim the whole design rests on, and it is a claim about
 *    absence — which is the kind that rots quietly, because nothing about a board
 *    with no sprints will ever look wrong until somebody adds a stray default.
 *
 * Plus completion, which is the only genuinely new business logic here and the
 * one write that can strand work: it moves issues, stamps the sprint and
 * deactivates the project, and a partial application leaves unfinished work in a
 * sprint that is over and no longer offered anywhere — unreachable from the board
 * entirely, because a completed sprint is not in the dropdown you would need to
 * get it out of.
 *
 * @see SprintRepository
 * @see Sprints.sq
 */
package se.soderbjorn.lunicle

import io.ktor.client.call.body
import io.ktor.client.request.cookie
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.BoardState
import se.soderbjorn.lunicle.clientserver.IssueSprintUpdate
import se.soderbjorn.lunicle.clientserver.IssueUpdate
import se.soderbjorn.lunicle.clientserver.SprintActivation
import se.soderbjorn.lunicle.clientserver.SprintCompletion
import se.soderbjorn.lunicle.clientserver.SprintMembership
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class SprintTest {
    private val file: File = Files.createTempFile("lunicle-sprint", ".db").toFile().also { it.delete() }
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

    // ── The promise to projects that never opt in ────────────────────────────

    /**
     * A fresh project has no sprints, and nothing seeded one.
     *
     * The cheapest test here and the one most worth having. Every other property
     * this feature claims — no board control, no editor field, no MCP surface —
     * is downstream of this list being empty, and all of them are rendered by
     * clients this test cannot see. If a default sprint is ever added to
     * `ProjectRepository.create` "for convenience", this is what says no.
     */
    @Test
    fun `a new project has no sprints and nothing is active`(): Unit = runBlocking {
        val f = seed()
        assertEquals(emptyList(), sprints.forProject(f.projectId))
        assertNull(projects.activeSprintId(f.projectId))
    }

    /**
     * And its issues are in no sprint.
     *
     * The other half of "invisible by absence": an issue filed in a project with
     * no sprints has `sprintId == null`, which is the backlog, which is a state
     * indistinguishable from the one that existed before this column did.
     */
    @Test
    fun `an issue in a project with no sprints is in no sprint`(): Unit = runBlocking {
        val f = seed()
        val issue = file(f, "Something")
        assertNull(issues.findById(issue)?.sprintId)
    }

    // ── The invariant nothing enforces ───────────────────────────────────────

    /**
     * An issue cannot be scheduled into another project's sprint.
     *
     * The check with no backstop. Every other vocabulary reference on `issues` is
     * composite-keyed, so SQLite refuses this on its own and the route validation
     * is only there to turn a 500 into a sentence. Here the route validation *is*
     * the rule — remove it and the write succeeds — so this is asserted through
     * the real route rather than against SprintRepository, because it is the route
     * that has to be the one asking.
     */
    @Test
    fun `an issue cannot be scheduled into another projects sprint`(): Unit = runBlocking {
        val mine = seed()
        val theirs = seed(name = "Other", prefix = "OTH")
        val foreign = makeSprint(theirs, "Their sprint")
        val issue = file(mine, "Mine")
        val cookie = sessions.create(mine.adminId)

        withRoutes { client ->
            val response = client.post(ApiRoutes.issueSprint(issue)) {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(IssueSprintUpdate(foreign))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        // And nothing was written, which is the part that matters: a refusal that
        // still wrote would be worse than no refusal at all.
        assertNull(issues.findById(issue)?.sprintId)
    }

    /** The same rule on the editor's `PUT`, which is a different handler. */
    @Test
    fun `saving an issue into another projects sprint is refused`(): Unit = runBlocking {
        val mine = seed()
        val theirs = seed(name = "Other", prefix = "OTH")
        val foreign = makeSprint(theirs, "Their sprint")
        val issue = file(mine, "Mine")
        val record = issues.findById(issue)!!
        val cookie = sessions.create(mine.adminId)

        withRoutes { client ->
            val response = client.put(ApiRoutes.issue(issue)) {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(
                    IssueUpdate(
                        title = "Mine",
                        description = "",
                        statusId = record.statusId,
                        priorityId = record.priorityId,
                        sprintId = foreign,
                    ),
                )
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        assertNull(issues.findById(issue)?.sprintId)
    }

    /**
     * Deleting a sprint releases its issues rather than refusing.
     *
     * The behaviour `VocabularyKind.SPRINT.restrictsOnUse = false` is asserting,
     * checked against the database rather than against the flag: the flag is a
     * claim *about* the schema, and a claim about a schema is worth exactly as
     * much as the last time somebody ran it. A status in the same position is
     * refused, which is what makes this worth stating rather than assuming.
     */
    @Test
    fun `deleting a sprint un-schedules its issues instead of being refused`(): Unit = runBlocking {
        val f = seed()
        val sprint = makeSprint(f, "Sprint 1")
        val issue = file(f, "Scheduled")
        sprintRepository.setIssueSprint(issues.findById(issue)!!, sprint)
        assertEquals(sprint, issues.findById(issue)?.sprintId)

        val row = vocabularies.find(f.projectId, VocabularyKind.SPRINT, sprint)!!
        vocabularies.delete(f.projectId, VocabularyKind.SPRINT, row)

        assertEquals(emptyList(), sprints.forProject(f.projectId))
        assertNull(issues.findById(issue)?.sprintId, "the issue should survive, un-scheduled")
    }

    /** And deleting the active sprint leaves the project with none, not with a dangling id. */
    @Test
    fun `deleting the active sprint deactivates the project`(): Unit = runBlocking {
        val f = seed()
        val sprint = makeSprint(f, "Sprint 1")
        sprintRepository.activate(f.projectId, sprint)
        assertEquals(sprint, projects.activeSprintId(f.projectId))

        val row = vocabularies.find(f.projectId, VocabularyKind.SPRINT, sprint)!!
        vocabularies.delete(f.projectId, VocabularyKind.SPRINT, row)

        assertNull(projects.activeSprintId(f.projectId))
    }

    // ── Activation ──────────────────────────────────────────────────────────

    /**
     * At most one sprint is active, and activating the second deactivates the
     * first without anybody saying so.
     *
     * That is the property `projects.active_sprint_id` buys over a per-row flag:
     * there is no "deactivate the old one" step to forget, because writing the new
     * id IS the deactivation. Worth a test precisely because there is no code to
     * point at — the assertion is that the absence of that code is fine.
     */
    @Test
    fun `activating a second sprint deactivates the first`(): Unit = runBlocking {
        val f = seed()
        val first = makeSprint(f, "Sprint 1")
        val second = makeSprint(f, "Sprint 2")

        sprintRepository.activate(f.projectId, first)
        sprintRepository.activate(f.projectId, second)

        assertEquals(second, projects.activeSprintId(f.projectId))
    }

    /** Deactivating entirely is a state a project can sit in — between sprints, and before the first. */
    @Test
    fun `a project can be left with no active sprint`(): Unit = runBlocking {
        val f = seed()
        val sprint = makeSprint(f, "Sprint 1")
        sprintRepository.activate(f.projectId, sprint)
        sprintRepository.activate(f.projectId, null)
        assertNull(projects.activeSprintId(f.projectId))
    }

    /** A finished sprint is not somewhere the board can be pointed. */
    @Test
    fun `a completed sprint cannot be activated`(): Unit = runBlocking {
        val f = seed()
        val sprint = makeSprint(f, "Sprint 1")
        sprintRepository.complete(f.projectId, sprint, moveUnfinishedTo = null)

        val refusal = assertFailsWith<SprintRefusal> {
            sprintRepository.activate(f.projectId, sprint)
        }
        assertTrue("already been completed" in refusal.userMessage, refusal.userMessage)
    }

    // ── Completion ──────────────────────────────────────────────────────────

    /**
     * Completing rolls the unfinished work forward and leaves the finished work
     * where it was.
     *
     * "Unfinished" is read off `statuses.requires_resolution`, and the finished
     * issue here is put in the seeded closing column *by that flag* rather than by
     * its name — see [closingStatus]. A version of this test that looked for a
     * status called "Closed" would pass on a build that had started keying the
     * rule on the name, which is the one discipline Sprints.sq asks never to
     * break.
     */
    @Test
    fun `completing a sprint moves the unfinished work and leaves the rest`(): Unit = runBlocking {
        val f = seed()
        val current = makeSprint(f, "Sprint 1")
        val next = makeSprint(f, "Sprint 2")

        val done = file(f, "Shipped")
        val notDone = file(f, "Still going")
        issues.setStatus(done, closingStatus(f), resolutions.forProject(f.projectId).first().id)
        sprintRepository.setMembership(f.projectId, current, listOf(done, notDone))

        sprintRepository.complete(f.projectId, current, moveUnfinishedTo = next)

        assertEquals(current, issues.findById(done)?.sprintId, "finished work stays where it shipped")
        assertEquals(next, issues.findById(notDone)?.sprintId, "unfinished work rolls forward")
    }

    /** Null means the backlog, which is the other thing the dialog offers. */
    @Test
    fun `completing a sprint can send unfinished work to the backlog`(): Unit = runBlocking {
        val f = seed()
        val sprint = makeSprint(f, "Sprint 1")
        val issue = file(f, "Still going")
        sprintRepository.setMembership(f.projectId, sprint, listOf(issue))

        sprintRepository.complete(f.projectId, sprint, moveUnfinishedTo = null)

        assertNull(issues.findById(issue)?.sprintId)
    }

    /**
     * Completing the active sprint leaves the project with none active.
     *
     * The board would otherwise be scoped to a sprint that is over — showing
     * whatever finished work stayed behind, and none of the work that rolled
     * forward, which reads as "the team has nothing to do".
     */
    @Test
    fun `completing the active sprint deactivates the project`(): Unit = runBlocking {
        val f = seed()
        val sprint = makeSprint(f, "Sprint 1")
        sprintRepository.activate(f.projectId, sprint)

        sprintRepository.complete(f.projectId, sprint, moveUnfinishedTo = null)

        assertNull(projects.activeSprintId(f.projectId))
    }

    /**
     * Completing a *different* sprint does not.
     *
     * The other side of the same `if`, and the one a naive implementation gets
     * wrong: finishing next quarter's sprint early must not yank the board out of
     * the sprint being worked in.
     */
    @Test
    fun `completing a non-active sprint leaves the active one alone`(): Unit = runBlocking {
        val f = seed()
        val active = makeSprint(f, "Sprint 1")
        val other = makeSprint(f, "Sprint 2")
        sprintRepository.activate(f.projectId, active)

        sprintRepository.complete(f.projectId, other, moveUnfinishedTo = null)

        assertEquals(active, projects.activeSprintId(f.projectId))
    }

    /**
     * Work cannot be rolled into the sprint being completed.
     *
     * The `UPDATE`'s `WHERE` and `SET` would name the same sprint, so every issue
     * would stay put and then be stranded by the completion two statements later
     * — a silent no-op that reports success. Refused instead.
     */
    @Test
    fun `unfinished work cannot be moved into the sprint being completed`(): Unit = runBlocking {
        val f = seed()
        val sprint = makeSprint(f, "Sprint 1")

        val refusal = assertFailsWith<SprintRefusal> {
            sprintRepository.complete(f.projectId, sprint, moveUnfinishedTo = sprint)
        }
        assertTrue("being completed" in refusal.userMessage, refusal.userMessage)
        assertNull(sprints.findByIdInProject(sprint, f.projectId)?.completedAt, "nothing was stamped")
    }

    /** Nor into one that is itself finished, which would strand it again immediately. */
    @Test
    fun `unfinished work cannot be moved into a completed sprint`(): Unit = runBlocking {
        val f = seed()
        val old = makeSprint(f, "Sprint 1")
        val current = makeSprint(f, "Sprint 2")
        sprintRepository.complete(f.projectId, old, moveUnfinishedTo = null)

        assertFailsWith<SprintRefusal> {
            sprintRepository.complete(f.projectId, current, moveUnfinishedTo = old)
        }
    }

    /**
     * A sprint cannot be completed twice.
     *
     * `completed_at` is the one value in this feature that cannot be recomputed
     * after the fact, and a second completion would overwrite it with today.
     */
    @Test
    fun `a completed sprint cannot be completed again`(): Unit = runBlocking {
        val f = seed()
        val sprint = makeSprint(f, "Sprint 1")
        sprintRepository.complete(f.projectId, sprint, moveUnfinishedTo = null)
        val stamped = sprints.findByIdInProject(sprint, f.projectId)?.completedAt

        assertFailsWith<SprintRefusal> {
            sprintRepository.complete(f.projectId, sprint, moveUnfinishedTo = null)
        }
        assertEquals(stamped, sprints.findByIdInProject(sprint, f.projectId)?.completedAt)
    }

    /**
     * Work cannot be scheduled into a finished sprint — on either write path.
     *
     * The refusal with the least obvious consequence, and the reason it is not
     * merely tidiness: `complete` refuses an already-completed sprint, so the one
     * statement that would ever move this work forward will not run again. An
     * issue placed here is stranded permanently, in a sprint the board offers as
     * a scope but the editor and the card menu both hide as a destination.
     *
     * Reachable without malice: a board tab rendered before somebody else pressed
     * Complete still carries the old id in its dropdown and its card menu. Both
     * clients filter completed sprints out of what they offer, which is why this
     * has to be the server's rule rather than theirs.
     */
    @Test
    fun `work cannot be scheduled into a completed sprint`(): Unit = runBlocking {
        val f = seed()
        val sprint = makeSprint(f, "Sprint 1")
        val issue = file(f, "Late arrival")
        sprintRepository.complete(f.projectId, sprint, moveUnfinishedTo = null)

        assertFailsWith<SprintRefusal> {
            sprintRepository.setIssueSprint(issues.findById(issue)!!, sprint)
        }
        assertFailsWith<SprintRefusal> {
            sprintRepository.setMembership(f.projectId, sprint, listOf(issue))
        }
        assertNull(issues.findById(issue)?.sprintId)
    }

    /**
     * A draft cannot be scheduled.
     *
     * Not a rule about drafts being unfinished — it is that a scheduled draft is
     * invisible to `forProject`, which is what both the planning dialog and its
     * permission check read. It would sit in a sprint no plan can see and no
     * check can vouch for, and then be silently released by the next membership
     * save. The editor writes the sprint at the moment it publishes, which is the
     * path a draft is meant to take.
     */
    @Test
    fun `a draft cannot be scheduled`(): Unit = runBlocking {
        val f = seed()
        val sprint = makeSprint(f, "Sprint 1")
        val (draftId, _) = issueRepository.createDraft(f.projectId, Author.Account(f.adminId))

        assertFailsWith<SprintRefusal> {
            sprintRepository.setIssueSprint(issues.findById(draftId)!!, sprint)
        }
        assertNull(issues.findById(draftId)?.sprintId)
    }

    /**
     * Completing the active sprint decides "was it active?" from inside the
     * transaction.
     *
     * Asserted indirectly, because the race itself is not reproducible here: what
     * is checked is that the decision reads the value the transaction sees rather
     * than one captured before it. Activating a *different* sprint between the
     * two must leave that activation standing — which is what fails if the read
     * moves back outside.
     */
    @Test
    fun `completing a sprint does not clobber an activation that overtook it`(): Unit = runBlocking {
        val f = seed()
        val finishing = makeSprint(f, "Sprint 1")
        val next = makeSprint(f, "Sprint 2")
        sprintRepository.activate(f.projectId, finishing)
        // Stands in for the racing writer: by the time the completion runs, the
        // project is pointing somewhere else.
        sprintRepository.activate(f.projectId, next)

        sprintRepository.complete(f.projectId, finishing, moveUnfinishedTo = null)

        assertEquals(next, projects.activeSprintId(f.projectId), "the newer activation must survive")
    }

    // ── Membership ──────────────────────────────────────────────────────────

    /**
     * Membership is a set, not a delta: an issue left out of the request leaves
     * the sprint.
     *
     * The convention `IssueOrderUpdate` and `VocabularyOrder` keep, and the reason
     * two people planning at once cannot interleave into a set neither chose. A
     * membership write that quietly preserved omitted rows would be a delta
     * wearing a set's clothes, and the planning dialog's unticked boxes would do
     * nothing.
     */
    @Test
    fun `setting membership removes the issues it does not name`(): Unit = runBlocking {
        val f = seed()
        val sprint = makeSprint(f, "Sprint 1")
        val kept = file(f, "Kept")
        val dropped = file(f, "Dropped")
        sprintRepository.setMembership(f.projectId, sprint, listOf(kept, dropped))

        sprintRepository.setMembership(f.projectId, sprint, listOf(kept))

        assertEquals(sprint, issues.findById(kept)?.sprintId)
        assertNull(issues.findById(dropped)?.sprintId, "dropped goes to the backlog, not elsewhere")
    }

    /** And an issue from another project cannot be planned into it. */
    @Test
    fun `membership refuses an issue from another project`(): Unit = runBlocking {
        val mine = seed()
        val theirs = seed(name = "Other", prefix = "OTH")
        val sprint = makeSprint(mine, "Sprint 1")
        val foreign = file(theirs, "Theirs")

        assertFailsWith<SprintRefusal> {
            sprintRepository.setMembership(mine.projectId, sprint, listOf(foreign))
        }
        assertNull(issues.findById(foreign)?.sprintId)
    }

    /**
     * Saving a plan that changes nothing changes nothing — timestamps included.
     *
     * The planning dialog tells the user it is safe to open just to look. That is
     * only true if Save is genuinely a no-op on the unchanged members: stamping
     * every ticked box would report twenty freshly-touched issues to `get_board`
     * and to every agent triaging on recency, which is a lie about twenty issues
     * told by a dialog somebody merely opened.
     */
    @Test
    fun `re-saving the same membership leaves updated_at alone`(): Unit = runBlocking {
        val f = seed()
        val sprint = makeSprint(f, "Sprint 1")
        val kept = file(f, "Already in it")
        sprintRepository.setMembership(f.projectId, sprint, listOf(kept))
        val stamped = issues.findById(kept)!!.updatedAt

        sprintRepository.setMembership(f.projectId, sprint, listOf(kept))

        assertEquals(sprint, issues.findById(kept)?.sprintId)
        assertEquals(stamped, issues.findById(kept)?.updatedAt)
    }

    // ── The board ───────────────────────────────────────────────────────────

    /**
     * The board a kanban project gets carries an empty sprint list and no active
     * sprint.
     *
     * Asserted on the wire object rather than on the stores, because emptiness
     * here is a contract with the client: it is what the scope control, the editor
     * field and the card menu all key off. A stray default that made this
     * non-empty would turn sprints on for every project on the instance.
     */
    @Test
    fun `a board with no sprints sends an empty list and no active sprint`(): Unit = runBlocking {
        val f = seed()
        file(f, "Something")
        val cookie = sessions.create(f.adminId)

        withRoutes { client ->
            val board: BoardState = client.post(ApiRoutes.sprintActivation(f.projectId)) {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(SprintActivation(null))
            }.body()
            assertEquals(emptyList(), board.sprints)
            assertNull(board.activeSprintId)
            assertTrue(board.issues.all { it.sprintId == null })
        }
    }

    /** And once a sprint exists, the board carries it and what is scheduled into it. */
    @Test
    fun `a board with a sprint reports it and the issues in it`(): Unit = runBlocking {
        val f = seed()
        val sprint = makeSprint(f, "Sprint 1")
        val scheduled = file(f, "Scheduled")
        file(f, "Backlogged")
        val cookie = sessions.create(f.adminId)

        withRoutes { client ->
            client.post(ApiRoutes.sprintIssues(f.projectId, sprint)) {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(SprintMembership(listOf(scheduled)))
            }
            val board: BoardState = client.post(ApiRoutes.sprintActivation(f.projectId)) {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(SprintActivation(sprint))
            }.body()

            assertEquals(listOf("Sprint 1"), board.sprints.map { it.name })
            assertEquals(sprint, board.activeSprintId)
            assertEquals(
                listOf(sprint, null),
                board.issues.sortedBy { it.number }.map { it.sprintId },
            )
        }
    }

    // ── Who may ─────────────────────────────────────────────────────────────

    /**
     * A signed-in non-admin cannot activate or complete a sprint.
     *
     * Through the real route with a real session cookie, for VocabularyTest's
     * reason: "shaping the sprint axis is admin" is only true if the server
     * refuses, and a test against `AccessControl` alone would pass on a route that
     * never called it.
     */
    @Test
    fun `a non-admin cannot activate or complete a sprint`(): Unit = runBlocking {
        val f = seed()
        val sprint = makeSprint(f, "Sprint 1")
        val ordinary = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", null))
        val cookie = sessions.create(ordinary.id)
        // Since LNL-57 a private project is invisible to somebody holding
        // nothing in it, and an invisible project answers 404 to everything —
        // including to a route whose admin gate had been deleted. `view_project`
        // grants no ability at all (see ProjectRole.VIEWER), so this is the
        // smallest grant that keeps the refusals below about administering the
        // sprint rather than about seeing the project.
        roles.setRole(ordinary.id, f.projectId, ProjectRole.VIEWER)

        withRoutes { client ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post(ApiRoutes.sprintActivation(f.projectId)) {
                    cookie(SESSION_COOKIE, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(SprintActivation(sprint))
                }.status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post(ApiRoutes.sprintCompletion(f.projectId, sprint)) {
                    cookie(SESSION_COOKIE, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(SprintCompletion(null))
                }.status,
            )
        }
        assertNull(projects.activeSprintId(f.projectId))
        assertNull(sprints.findByIdInProject(sprint, f.projectId)?.completedAt)
    }

    // ── Fixture ─────────────────────────────────────────────────────────────

    private data class Fixture(val adminId: Long, val projectId: Long)

    private suspend fun seed(name: String = "Lunamux", prefix: String = "LMX"): Fixture {
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin-$prefix", "Admin", null))
        val project = projectRepository.create(name, prefix)
        // Production seats the instance owner at boot (see InstanceLadder.kt), and
        // four rules — creating and managing projects, backfilling authorship, agent
        // mail, out-of-band attachment deletes — are the owner's alone rather than an
        // administrator's. A fixture that skipped this would be testing an instance
        // nobody runs: one with an administrator and no owner.
        seatInstanceOwner(users, instanceSettings)
        return Fixture(admin.id, project.id)
    }

    /** Add a sprint through the same machinery the settings dialog uses, and return its id. */
    private suspend fun makeSprint(f: Fixture, name: String): Long =
        vocabularies.add(f.projectId, VocabularyKind.SPRINT, name).id

    /** File a published issue and return its id. */
    private suspend fun file(f: Fixture, title: String): Long {
        val (id, _) = issueRepository.createDraft(f.projectId, Author.Account(f.adminId))
        val issue = issues.findById(id)!!
        issueRepository.save(
            issue = issue,
            title = title,
            description = "",
            statusId = issue.statusId,
            priorityId = issue.priorityId,
            resolutionId = null,
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
     * The project's closing column, found by its flag.
     *
     * Never by the name "Closed". The flag is the rule everywhere else in this
     * codebase — see Statuses.sq — and a test that hardcoded the name would go on
     * passing after somebody keyed the completion rule on it, which is exactly the
     * regression Sprints.sq asks to be protected from.
     */
    private suspend fun closingStatus(f: Fixture): Long =
        statuses.forProject(f.projectId).first { it.requiresResolution }.id

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
        impersonations = Impersonations(),
        subscriptions = SubscriptionStore(database),
        reads = ReadStore(database),
    )
}
