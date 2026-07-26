/**
 * What a card dropped on the board is allowed to mean.
 *
 * The order route grew a second job for LNL-40. It used to rank a group and
 * nothing else, and it proved the group's integrity before writing — every id in
 * the body sharing the dragged issue's status AND its priority. That check is
 * what made a drag across a group header impossible: the board drew an insertion
 * line for the whole column, the route refused the list because it named the
 * group the card was landing in rather than the one it came from, and the drop
 * silently did nothing.
 *
 * So the route now takes an optional `priorityId` and applies it FIRST. That
 * ordering is the whole design and it is invisible from the outside, which is why
 * it is pinned here: check-then-move would refuse every cross-group drop, and
 * move-then-check-against-the-old-group would corrupt the ranks. Both bugs look
 * identical to "the board is fine" until someone drags a card past a header.
 *
 * Through the real route with a real session cookie rather than against
 * [IssueStore] alone, for the reason VocabularyTest's preamble gives: a test that
 * called the store directly would pass on a route that never called it, and the
 * two refusals here (a closed issue, a priority from another project) live in the
 * route and nowhere else.
 *
 * @see boardRoutes
 * @see se.soderbjorn.lunicle.clientserver.IssueOrderUpdate
 */
package se.soderbjorn.lunicle

import io.ktor.client.call.body
import io.ktor.client.request.cookie
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
import se.soderbjorn.lunicle.clientserver.IssueOrderUpdate
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class IssueOrderTest {
    private val file: File = Files.createTempFile("lunicle-order", ".db").toFile().also { it.delete() }
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
    private val attachments =
        AttachmentRepository(attachmentStore, File(file.parentFile, "attachments-${file.name}"))
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
    private val issueRepository =
        IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)
    private val sprintRepository = SprintRepository(database, sprints, projects, issues, statuses)
    private val vocabularies =
        VocabularyRepository(database, labels, components, statuses, priorities, resolutions, sprints, versions, issues)
    private val access = AccessControl(roles)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── The drag that used to die ────────────────────────────────────────────

    /**
     * The bug, in one test: a card dropped into another priority group of the
     * same column lands there, and lands in the right place.
     *
     * Both halves are asserted because either alone would let a plausible
     * regression through. Only checking the priority passes for an
     * implementation that moves the card and then ignores the ranking; only
     * checking the order passes for one that ranks correctly and leaves the card
     * in the group it came from, where the ranks are meaningless.
     */
    @Test
    fun `a card dropped into another priority group takes that priority and that place`(): Unit = runBlocking {
        val f = seed()
        val high = priorities.forProject(f.projectId)[0]
        val normal = priorities.forProject(f.projectId)[1]
        val a = publish(f, "A", normal.id)
        val b = publish(f, "B", normal.id)
        val dragged = publish(f, "Dragged", high.id)

        withRoutes { client ->
            val response = client.post("/api/issues/$dragged/order") {
                cookie(SESSION_COOKIE, f.cookie)
                contentType(ContentType.Application.Json)
                // The DESTINATION group, with the dragged card slotted between
                // its two members — which is exactly what the board sends.
                setBody(IssueOrderUpdate(listOf(a, dragged, b), priorityId = normal.id))
            }
            assertEquals(HttpStatusCode.NoContent, response.status, "The cross-group drop was refused.")
        }

        assertEquals(
            normal.id,
            issues.findById(dragged)!!.priorityId,
            "The card kept the priority it was dragged out of, so it snapped back to its old group.",
        )
        assertEquals(
            listOf(a, dragged, b),
            issues.forProject(f.projectId).filter { it.priorityId == normal.id }.map { it.id },
            "The card landed in the right group but not where the insertion line promised.",
        )
    }

    /**
     * The ordering of the two operations, asserted from the outside.
     *
     * A body naming the destination group is, by construction, NOT a valid group
     * for the issue as it stands when the request arrives — that is what
     * "cross-group" means. So an implementation that ran the integrity check
     * before the move would answer 400 here, and this is the test that says which
     * of the two comes first.
     */
    @Test
    fun `the destination group is validated against the new priority, not the old one`(): Unit = runBlocking {
        val f = seed()
        val high = priorities.forProject(f.projectId)[0]
        val normal = priorities.forProject(f.projectId)[1]
        val resident = publish(f, "Resident", normal.id)
        val dragged = publish(f, "Dragged", high.id)

        withRoutes { client ->
            val response = client.post("/api/issues/$dragged/order") {
                cookie(SESSION_COOKIE, f.cookie)
                contentType(ContentType.Application.Json)
                setBody(IssueOrderUpdate(listOf(resident, dragged), priorityId = normal.id))
            }
            assertEquals(
                HttpStatusCode.NoContent,
                response.status,
                "The group was checked before the priority moved, so every cross-group drop is refused.",
            )
        }
    }

    // ── What stays refused ───────────────────────────────────────────────────

    /**
     * A closing column groups by RESOLUTION, so a priority names no group there.
     *
     * Deliberately still refused: the groups in a closed column say *why* an issue
     * was closed, which is a record of a decision rather than a rank, and a drag
     * two rows down is not how anyone should be able to rewrite one. The board
     * draws no insertion line for it either — this is the server half of that.
     */
    @Test
    fun `a priority move is refused for a closed issue`(): Unit = runBlocking {
        val f = seed()
        val closing = statuses.forProject(f.projectId).first { it.requiresResolution }
        val resolution = resolutions.forProject(f.projectId).first()
        val normal = priorities.forProject(f.projectId)[1]
        val closed = publish(f, "Closed", priorities.forProject(f.projectId)[0].id)
        issues.setStatus(closed, closing.id, resolution.id)
        val before = issues.findById(closed)!!.priorityId

        withRoutes { client ->
            val response = client.post("/api/issues/$closed/order") {
                cookie(SESSION_COOKIE, f.cookie)
                contentType(ContentType.Application.Json)
                setBody(IssueOrderUpdate(listOf(closed), priorityId = normal.id))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("A closed issue is not grouped by priority.", response.body<String>())
        }
        assertEquals(before, issues.findById(closed)!!.priorityId, "A closed issue's priority was rewritten.")
    }

    /** A priority from somewhere else is not a group of this board. */
    @Test
    fun `a priority from another project is refused`(): Unit = runBlocking {
        val f = seed()
        val other = seed(name = "Other", prefix = "OTH")
        val foreign = priorities.forProject(other.projectId).first()
        val dragged = publish(f, "Dragged", priorities.forProject(f.projectId)[0].id)
        val before = issues.findById(dragged)!!.priorityId

        withRoutes { client ->
            val response = client.post("/api/issues/$dragged/order") {
                cookie(SESSION_COOKIE, f.cookie)
                contentType(ContentType.Application.Json)
                setBody(IssueOrderUpdate(listOf(dragged), priorityId = foreign.id))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("No such priority in this project.", response.body<String>())
        }
        assertEquals(before, issues.findById(dragged)!!.priorityId)
        assertNotEquals(foreign.id, issues.findById(dragged)!!.priorityId)
    }

    // ── The old behaviour, unchanged ─────────────────────────────────────────

    /**
     * A plain reorder still works, and still leaves the priority alone.
     *
     * The regression guard for the whole change: `priorityId` defaults to null,
     * which is what every caller sent before it existed, and null has to keep
     * meaning "rank only". A payload that predates this field must still say
     * exactly what it used to.
     */
    @Test
    fun `an order with no priority ranks the group and changes nothing else`(): Unit = runBlocking {
        val f = seed()
        val normal = priorities.forProject(f.projectId)[1]
        val a = publish(f, "A", normal.id)
        val b = publish(f, "B", normal.id)

        withRoutes { client ->
            val response = client.post("/api/issues/$b/order") {
                cookie(SESSION_COOKIE, f.cookie)
                contentType(ContentType.Application.Json)
                setBody(IssueOrderUpdate(listOf(b, a)))
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }

        assertEquals(
            listOf(b, a),
            issues.forProject(f.projectId).filter { it.priorityId == normal.id }.map { it.id },
        )
        assertEquals(normal.id, issues.findById(b)!!.priorityId, "A rank-only write moved the card's priority.")
    }

    /** Mixing two groups is still gibberish, and is still refused. */
    @Test
    fun `an order naming two different groups is still refused`(): Unit = runBlocking {
        val f = seed()
        val high = priorities.forProject(f.projectId)[0]
        val normal = priorities.forProject(f.projectId)[1]
        val hot = publish(f, "Hot", high.id)
        val mild = publish(f, "Mild", normal.id)

        withRoutes { client ->
            val response = client.post("/api/issues/$hot/order") {
                cookie(SESSION_COOKIE, f.cookie)
                contentType(ContentType.Application.Json)
                // No priorityId, so `hot` stays High and `mild` is a foreigner.
                setBody(IssueOrderUpdate(listOf(hot, mild)))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Those issues are not all in one group.", response.body<String>())
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val adminId: Long, val projectId: Long, val cookie: String)

    private suspend fun seed(name: String = "Lunamux", prefix: String = "LMX"): Fixture {
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-$prefix", "Admin $prefix", null))
        val project = projectRepository.create(name, prefix, isPublic = false)
        return Fixture(admin.id, project.id, sessions.create(admin.id))
    }

    /** A published issue in the project's leftmost column, at the given priority. */
    private suspend fun publish(f: Fixture, title: String, priorityId: Long): Long {
        val (id, _) = issueRepository.createDraft(f.projectId, Author.Account(f.adminId))
        val status = statuses.forProject(f.projectId).first()
        issues.publish(
            id = id,
            title = title,
            description = "",
            statusId = status.id,
            priorityId = priorityId,
            resolutionId = null,
            assigneeId = null,
            sprintId = null,
            plannedVersionId = null,
            fixedVersionId = null,
        )
        return id
    }

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
        audience = ProjectAudience(users, roles),
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
