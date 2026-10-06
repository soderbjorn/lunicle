/**
 * The change stream (LNL-224): the bus's resume rule, the decorators' choice of event,
 * and the endpoints' auth, filtering and wire format.
 *
 * The decorators are driven through the real [IssueRepository] rather than called
 * directly, because the claim worth pinning is "a write through the ordinary path
 * announces itself" — a decorator that was correct in isolation but sat beneath a
 * repository that wrote around it would pass a direct test and announce nothing.
 *
 * @see ChangeBus
 * @see changeStreamRoutes
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import se.soderbjorn.lunicle.clientserver.ApiTokenScope
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.store.InstanceSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class ChangeStreamTest {
    private val file: File = Files.createTempFile("lunicle-change-stream", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val bus = ChangeBus()
    private val users = UserStore(database)
    private val sessions = SessionStore(database)
    private val roles = RoleStore(database)
    private val projects = ProjectStore(database)
    private val statuses = StatusStore(database)
    private val priorities = PriorityStore(database)
    private val rawIssues = IssueStore(database)
    private val issues = PublishingIssueStore(rawIssues, bus)
    private val comments = PublishingCommentStore(CommentStore(database), rawIssues, bus)
    private val attachmentStore = AttachmentStore(database)
    private val attachments = AttachmentRepository(attachmentStore, File(file.parentFile, "attachments-${file.name}"))
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
    private val issueRepository = IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)
    private val instanceSettings = InMemoryInstanceSettingsStore(InstanceSettings(memberMayUseApi = true))
    private val access = AccessControl(roles, instanceSettings)
    private val apiTokens = ApiTokenStore(database)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── The bus ──────────────────────────────────────────────────────────────

    @Test
    fun `a resume inside the buffer replays exactly what was missed`() {
        val first = bus.publish(ChangeKind.ISSUE_UPDATED, projectId = 1, issueId = 1)
        val second = bus.publish(ChangeKind.ISSUE_UPDATED, projectId = 1, issueId = 2)
        val third = bus.publish(ChangeKind.ISSUE_UPDATED, projectId = 1, issueId = 3)

        assertEquals(listOf(second.id, third.id), bus.open(first.id).replay?.map { it.id })
        assertEquals(emptyList(), bus.open(third.id).replay, "Up to date is nothing to replay, not a reset.")
        assertEquals(emptyList(), bus.open(null).replay, "A fresh stream starts from now.")
    }

    @Test
    fun `a gap older than the buffer, or an id from another process, is a reset`() {
        val small = ChangeBus(capacity = 2)
        val first = small.publish(ChangeKind.ISSUE_UPDATED, projectId = 1)
        repeat(3) { small.publish(ChangeKind.ISSUE_UPDATED, projectId = 1) }

        assertNull(small.open(first.id).replay, "The event after this id has been evicted.")
        assertNull(small.open(1).replay, "An id from a previous boot is far below this one's.")
        assertNull(small.open(Long.MAX_VALUE).replay, "An id from the future cannot be resumed from.")
    }

    @Test
    fun `a listener that falls behind is marked, not allowed to grow`() {
        val opened = bus.open(null)
        repeat(1100) { bus.publish(ChangeKind.ISSUE_UPDATED, projectId = 1) }
        assertTrue(opened.listener.overflowed)
        bus.close(opened.listener)
        assertEquals(0, bus.listenerCount)
    }

    // ── The decorators ───────────────────────────────────────────────────────

    @Test
    fun `an issue's life is announced, and its draft is not`(): Unit = runBlocking {
        val f = seed()
        val listener = bus.open(null).listener
        // One write through the repository can touch the row more than once (the issue,
        // then its labels), so each step reads everything it caused.
        fun drained(): List<ChangeKind> = generateSequence { listener.events.tryReceive().getOrNull() }.map { it.kind }.toList()

        val (id, _) = issueRepository.createDraft(f.projectId, Author.Account(f.ownerId))
        assertEquals(emptyList(), drained(), "A draft is invisible to everybody.")

        save(id, "First")
        assertEquals(ChangeKind.ISSUE_CREATED, drained().first())
        save(id, "Second")
        assertTrue(drained().all { it == ChangeKind.ISSUE_UPDATED })

        issues.setStatus(id, statuses.forProject(f.projectId)[1].id, null)
        assertEquals(listOf(ChangeKind.ISSUE_MOVED), drained())

        val commentId = issueRepository.createCommentDraft(id, Author.Account(f.ownerId))
        assertEquals(emptyList(), drained(), "A comment draft is invisible too.")
        issueRepository.saveComment(commentId, "Hello")
        val added = assertNotNull(listener.events.tryReceive().getOrNull())
        assertEquals(ChangeKind.COMMENT_ADDED, added.kind)
        assertEquals(commentId, added.commentId)
        assertEquals(f.projectId, added.projectId)
        issueRepository.saveComment(commentId, "Hello again")
        assertEquals(listOf(ChangeKind.COMMENT_EDITED), drained())

        issueRepository.delete(rawIssues.findById(id)!!)
        assertEquals(ChangeKind.ISSUE_DELETED, drained().last())
    }

    @Test
    fun `an event carries the request's actor and origin`(): Unit = runBlocking {
        val f = seed()
        val (id, _) = issueRepository.createDraft(f.projectId, Author.Account(f.ownerId))
        val listener = bus.open(null).listener

        val origin = ChangeOrigin("tab-1").apply { actor = users.findById(f.ownerId) }
        withContext(origin) { save(id, "Mine") }

        val event = assertNotNull(listener.events.tryReceive().getOrNull())
        assertEquals("Owner", event.actor)
        assertEquals("tab-1", event.origin)
    }

    @Test
    fun `an origin header is only taken if it looks like an id`() {
        assertEquals("abc-123_x", ChangeOrigin.sanitise(" abc-123_x "))
        assertNull(ChangeOrigin.sanitise("<script>"))
        assertNull(ChangeOrigin.sanitise("a".repeat(65)))
        assertNull(ChangeOrigin.sanitise(""))
    }

    // ── The endpoints ────────────────────────────────────────────────────────

    @Test
    fun `a signed-in session hears its projects, its own notifications, and which events were its own`(): Unit =
        runBlocking {
            val f = seed()
            withStream { client ->
                client.prepareGet("$REST_API_PATH/events?projects=${f.projectId},${f.hiddenProjectId}&origin=tab-9") {
                    header(HttpHeaders.Cookie, "$SESSION_COOKIE=${f.memberSession}")
                }.execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    val body = response.bodyAsChannel()
                    body.readUntil(": connected")

                    // A project they cannot read, somebody else's bell, then the three they should hear.
                    bus.publish(ChangeKind.ISSUE_UPDATED, projectId = f.hiddenProjectId, issueId = 99)
                    bus.publish(ChangeKind.NOTIFICATION_CHANGED, userId = f.ownerId)
                    bus.publish(ChangeKind.ISSUE_UPDATED, projectId = f.projectId, issueId = 7, actor = "Owner", origin = "tab-1")
                    bus.publish(ChangeKind.COMMENT_ADDED, projectId = f.projectId, issueId = 7, commentId = 3, origin = "tab-9")
                    bus.publish(ChangeKind.NOTIFICATION_CHANGED, userId = f.memberId)

                    val frames = body.readFrames(3)
                    assertEquals(listOf("issue.updated", "comment.added", "notification.changed"), frames.map { it.event })
                    assertTrue(frames[0].data.contains("\"issueId\":7") && frames[0].data.contains("\"actor\":\"Owner\""), frames[0].data)
                    assertTrue("\"self\"" !in frames[0].data, "Somebody else's tab is not this one.")
                    assertTrue(frames[1].data.contains("\"self\":true"), frames[1].data)
                    assertTrue(frames[1].data.contains("\"commentId\":3"), frames[1].data)
                    assertTrue("tab-" !in frames.joinToString { it.data }, "An origin must never be sent.")
                }
            }
        }

    @Test
    fun `a token can watch one project, and a stale Last-Event-ID is told to reset`(): Unit = runBlocking {
        val f = seed()
        val raw = ApiTokenCrypto.mint()
        apiTokens.create(f.memberId, "Watcher", ApiTokenCrypto.hash(raw), ApiTokenCrypto.displayPrefix(raw), ApiTokenScope.READ, null)
        users.setApiEnabled(f.memberId, true)
        withStream { client ->
            client.prepareGet("$REST_API_PATH/projects/${f.projectId}/events") {
                header(HttpHeaders.Authorization, "Bearer $raw")
                header("Last-Event-ID", "1")
            }.execute { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsChannel()
                assertEquals("reset", body.readFrames(1).single().event)
                bus.publish(ChangeKind.ISSUE_DELETED, projectId = f.projectId, issueId = 5)
                assertEquals("issue.deleted", body.readFrames(1).single().event)
            }
        }
    }

    @Test
    fun `no credentials is a 401 and a project out of reach is a 404`(): Unit = runBlocking {
        val f = seed()
        withStream { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("$REST_API_PATH/projects/${f.projectId}/events").status)
            val hidden = client.get("$REST_API_PATH/projects/${f.hiddenProjectId}/events") {
                header(HttpHeaders.Cookie, "$SESSION_COOKIE=${f.memberSession}")
            }
            assertEquals(HttpStatusCode.NotFound, hidden.status)
            assertEquals(0, bus.listenerCount, "A refused stream must not leave a listener behind.")
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(
        val ownerId: Long,
        val memberId: Long,
        val memberSession: String,
        val projectId: Long,
        val hiddenProjectId: Long,
    )

    private suspend fun seed(): Fixture {
        val owner = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-owner", "Owner", "owner@example.com"))
        val member = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-member", "Member", "member@example.com"))
        seatInstanceOwner(users, instanceSettings)
        val project = projectRepository.create("Lunamux", "LMX")
        val hidden = projectRepository.create("Hidden", "HID")
        roles.setRole(member.id, project.id, ProjectRole.VIEWER)
        return Fixture(owner.id, member.id, sessions.create(member.id), project.id, hidden.id)
    }

    private suspend fun save(id: Long, title: String) {
        val issue = rawIssues.findById(id)!!
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
    }

    private class Frame(val event: String?, val data: String)

    /** Read lines until one equals [line]. */
    private suspend fun ByteReadChannel.readUntil(line: String) = withTimeout(5_000) {
        while (true) if (readUTF8Line() == line) break
    }

    /** Read [count] SSE frames that carry an event or data, skipping comments and pings. */
    private suspend fun ByteReadChannel.readFrames(count: Int): List<Frame> = withTimeout(5_000) {
        val frames = mutableListOf<Frame>()
        var event: String? = null
        var data = ""
        while (frames.size < count) {
            val line = readUTF8Line() ?: break
            when {
                line.startsWith("event: ") -> event = line.removePrefix("event: ")
                line.startsWith("data: ") -> data = line.removePrefix("data: ")
                line.isEmpty() -> {
                    if (event != null) frames += Frame(event, data)
                    event = null
                    data = ""
                }
            }
        }
        frames
    }

    private fun withStream(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing {
                changeStreamRoutes(
                    ChangeStreamDependencies(
                        bus = bus,
                        sessions = sessions,
                        impersonation = OwnerImpersonation(),
                        access = access,
                        projects = projects,
                        tokens = apiTokens,
                        users = users,
                        instanceSettings = instanceSettings,
                    ),
                )
            }
        }
        block(createClient { })
    }
}
