/**
 * A notification list narrowed by **present** access (LUS-14).
 *
 * Every route in NotificationRoutes runs no project gate, on the reasoning that a
 * notification belongs to exactly one person — so "may I see this" was answered by
 * "is it mine". Sound about ownership, silent about time.
 *
 * A row stores the issue or post title **verbatim**, and revoking somebody's rung
 * writes to the roles table and nothing else. So a former Contributor kept a
 * permanent, indexed list of titles from a board they can no longer open. Clicking
 * through correctly 404s; the leak was never the content, it was the metadata.
 *
 * The discussion-unread badge next door already re-runs its read check at request
 * time, and its comment names this as a lesson learned once. These are the
 * assertions that keep it learned.
 *
 * @see NotificationRoutes
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.NotificationCountState
import se.soderbjorn.lunicle.clientserver.NotificationKind
import se.soderbjorn.lunicle.clientserver.NotificationListState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class NotificationVisibilityTest {
    private val file: File = Files.createTempFile("lunicle-notif-vis", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)
    private val sessions = SessionStore(database)
    private val roles = RoleStore(database)
    private val projects = ProjectStore(database)
    private val instanceSettings = InstanceSettingsStore(database)
    private val access = AccessControl(roles, instanceSettings)
    private val notifications = NotificationStore(database)
    private val attachmentStore = AttachmentStore(database)
    private val attachments = AttachmentRepository(attachmentStore, File(file.parentFile, "att-${file.name}"))
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    /**
     * The whole finding in one test: hold the rung, see the title; lose the rung,
     * stop seeing it — with no write to the notifications table in between.
     */
    @Test
    fun `losing a rung hides that project's titles`(): Unit = runBlocking {
        // Somebody else takes the first-account-is-an-administrator slot, or the
        // person under test reads every project by instance role and the rung being
        // withdrawn decides nothing. See Users.sq's upsert.
        users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-0", "Root", "root@acme.com"))
        val user = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-1", "Ada", "ada@acme.com"))
        val project = projectRepository.create("Private", "PRV")
        roles.setRole(user.id, project.id, ProjectRole.CONTRIBUTOR)
        notifications.record(
            user.id,
            NewNotification(
                NotificationKind.ISSUE_ASSIGNED,
                "The acquisition of Northwind",
                projectId = project.id,
                issueId = 1L,
            ),
        )
        val cookie = sessions.create(user.id)

        withRoutes { client ->
            assertEquals(
                listOf("The acquisition of Northwind"),
                client.list(cookie).items.map { it.title },
                "A Contributor could not see their own notification.",
            )
            assertEquals(1, client.count(cookie).unreadCount)

            // The revoke, exactly as ProjectSettingsRoutes performs it — and nothing
            // else. That is the point: no notification row is touched here.
            roles.setRole(user.id, project.id, null)

            assertEquals(
                emptyList(),
                client.list(cookie).items.map { it.title },
                "A former member still reads the titles of a board they cannot open.",
            )
            assertEquals(
                0,
                client.count(cookie).unreadCount,
                "The bell counts rows the panel withholds, which is a badge nobody can clear.",
            )
        }
    }

    /** A notification with no project is a private message, and is nobody's project to gate. */
    @Test
    fun `a notification with no project is untouched`(): Unit = runBlocking {
        val user = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-2", "Bo", "bo@acme.com"))
        notifications.record(
            user.id,
            NewNotification(NotificationKind.MESSAGE, "A message", conversationId = 5L, messageId = 6L),
        )
        val cookie = sessions.create(user.id)

        withRoutes { client ->
            assertEquals(listOf("A message"), client.list(cookie).items.map { it.title })
            assertEquals(1, client.count(cookie).unreadCount)
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private suspend fun HttpClient.list(cookie: String): NotificationListState =
        get(ApiRoutes.NOTIFICATIONS) { cookie(SESSION_COOKIE, cookie) }.body()

    private suspend fun HttpClient.count(cookie: String): NotificationCountState =
        get(ApiRoutes.NOTIFICATIONS_UNREAD_COUNT) { cookie(SESSION_COOKIE, cookie) }.body()

    private fun withRoutes(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing { notificationRoutes(dependencies()) }
        }
        block(createClient { install(ClientContentNegotiation) { json() } })
    }

    private fun dependencies() = BoardDependencies(
        access = access,
        projects = projects,
        projectRepository = projectRepository,
        roles = roles,
        vocabularies = VocabularyRepository(
            database,
            LabelStore(database), ComponentStore(database), StatusStore(database),
            PriorityStore(database), ResolutionStore(database), SprintStore(database),
            VersionStore(database), issues = IssueStore(database),
        ),
        forums = ForumRepository(ForumStore(database), attachments, attachmentStore),
        forumPosts = ForumPostRepository(
            ForumPostStore(database), ForumCommentStore(database), attachments, attachmentStore,
        ),
        audience = ProjectAudience(users, roles, instanceSettings),
        conversations = ConversationRepository(
            ConversationStore(database), MessageStore(database), attachments, attachmentStore,
        ),
        labels = LabelStore(database),
        components = ComponentStore(database),
        statuses = StatusStore(database),
        priorities = PriorityStore(database),
        resolutions = ResolutionStore(database),
        versions = VersionStore(database),
        sprints = SprintRepository(database, SprintStore(database), projects, IssueStore(database), StatusStore(database)),
        sprintRepository = SprintRepository(database, SprintStore(database), projects, IssueStore(database), StatusStore(database)),
        issues = IssueStore(database),
        issueRepository = IssueRepository(
            IssueStore(database), CommentStore(database), StatusStore(database),
            PriorityStore(database), attachments, attachmentStore,
        ),
        comments = CommentStore(database),
        attachments = attachmentStore,
        attachmentRepository = attachments,
        attachmentTickets = AttachmentTicketStore(),
        sessions = sessions,
        users = users,
        instanceSettings = instanceSettings,
        subscriptions = SubscriptionStore(database),
        reads = ReadStore(database),
        notificationStore = notifications,
    )
}
