/**
 * Read/unread state: what counts as unread, what marking read does, and what the
 * two badges are allowed to say.
 *
 * ── The interesting failures this file exists to catch ──────────────────────
 *
 *  - **A badge that counts your own writing.** The natural implementation counts
 *    every published row past the mark, and the author's own is a published row
 *    past the mark. It goes wrong in the direction nobody reports as a bug: the
 *    Messages badge ticks up every time you press Send, which reads as "somebody
 *    replied instantly" and is the fastest possible way to make a badge
 *    untrustworthy. Pinned with the actor genuinely a participant, because a test
 *    that asserts it from the *recipient's* side is true whatever the code does.
 *  - **`created_by IS NOT :userId` written as `!=`.** The column is nullable — an
 *    author whose account is gone, an imported row — and `NULL != 5` is NULL,
 *    which is not true, so `!=` silently drops every authorless message from the
 *    count. Invisible on any instance nobody has ever deleted an account on, which
 *    is every instance until it is not.
 *  - **Deleting a message resurrecting a conversation's unread state.**
 *    `last_read_message_id` deliberately has no foreign key: a cascade would take
 *    the read row with the message and quietly mark a whole thread unread again
 *    because somebody removed the last thing they said. The failure is a badge that
 *    appears from nowhere, which nobody can reproduce.
 *  - **The Discussion badge scoped to the project on screen.** It is instance-wide
 *    on purpose, because it lives on a tab strip; a per-project one would go out
 *    when somebody switched project, which reads as "you have read it". This pins
 *    the spanning *and* the narrowing that has to come with it — losing sight of a
 *    project must remove its contribution — which is the criterion with no backstop
 *    anywhere in the UI, exactly as `ForumWatchTest` says about its own.
 *  - **A visibility check written at the wrong moment.** LNL-63's lesson, one
 *    ticket later: it is tempting to decide what contributes to a badge when
 *    something is *written*, and a role is revoked long afterwards by a gesture that
 *    knows nothing about badges. So the narrowing is asserted by revoking a role
 *    after the post exists.
 *  - **The forum's high-water mark rolling backwards.** Two reads out of order —
 *    the newest post, then an older one — must leave the mark where the first put
 *    it. The `MAX` in the upsert is one word, and without it re-reading an old
 *    thread would un-read everything since.
 *  - **Signed-out state.** A visitor has no read marks, so the naive answer is
 *    "everything is unread", which would put a dot on a tab that nothing can clear.
 *
 * Every request goes through the `ApiRoutes` builders rather than hand-written
 * strings, so a route pattern that drifts from the path the client calls fails here
 * rather than in a browser — `ForumPostTest` and `MessageTest` say the same.
 *
 * What is deliberately *not* here: how the badge is drawn. The cap at `99+` is the
 * toolkit's `formatTabBadgeCount`, tested upstream in `FixedTabStripTest`, and the
 * client's summing is a one-line derived property. Re-asserting either would be
 * testing somebody else's code through three layers of HTTP.
 *
 * @see ReadStore
 * @see unreadRoutes
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import se.soderbjorn.lunicle.clientserver.ConversationDraft
import se.soderbjorn.lunicle.clientserver.ConversationListState
import se.soderbjorn.lunicle.clientserver.ConversationStart
import se.soderbjorn.lunicle.clientserver.DiscussionUnreadState
import se.soderbjorn.lunicle.clientserver.ForumPostListState
import se.soderbjorn.lunicle.clientserver.MessageEdit
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class UnreadTest {
    private val file: File = Files.createTempFile("lunicle-unread", ".db").toFile().also { it.delete() }
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
    private val attachmentsDirectory = File(file.parentFile, "attachments-${file.name}")
    private val attachments = AttachmentRepository(attachmentStore, attachmentsDirectory)
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
    private val issueRepository =
        IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)
    private val sprintRepository = SprintRepository(database, sprints, projects, issues, statuses)
    private val vocabularies =
        VocabularyRepository(database, labels, components, statuses, priorities, resolutions, sprints, versions, issues)
    private val forumStore = ForumStore(database)
    private val forums = ForumRepository(forumStore, attachments, attachmentStore)
    private val forumPostStore = ForumPostStore(database)
    private val forumCommentStore = ForumCommentStore(database)
    private val forumPosts =
        ForumPostRepository(forumPostStore, forumCommentStore, attachments, attachmentStore)
    private val conversationStore = ConversationStore(database)
    private val messageStore = MessageStore(database)
    private val conversations =
        ConversationRepository(conversationStore, messageStore, attachments, attachmentStore)
    private val instanceSettings = InMemoryInstanceSettingsStore()
    private val access = AccessControl(roles, instanceSettings)
    private val reads = ReadStore(database)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
        attachmentsDirectory.deleteRecursively()
    }

    // ── Messages ─────────────────────────────────────────────────────────────



    /**
     * A conversation that has been started and not sent counts as nothing.
     *
     * The draft row is a real `messages` row, published only by the `PUT`, so a
     * count over rows rather than over *published* rows would show the recipient a
     * badge for a message nobody has sent — and, worse, would show the author one
     * for their own unsent text. Both halves are checked because the two clauses
     * that prevent them are different (`is_draft = 0` and the author exclusion).
     */
    @Test
    fun `an unsent draft counts for nobody`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val draft: ConversationDraft = client.post(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Application.Json)
                setBody(ConversationStart(listOf(f.graceId)))
            }.body()

            assertEquals(
                emptyList(),
                client.conversations(f.graceCookie).conversations,
                "An unsent conversation appeared in the recipient's list at all.",
            )
            assertEquals(0L, client.totalUnread(f.graceCookie), "An unsent draft raised the recipient's badge.")
            assertEquals(0L, client.totalUnread(f.adaCookie), "An unsent draft raised its own author's badge.")
            assertTrue(draft.messageId > 0, "The fixture did not actually create a draft.")
        }
    }


    // ── The Discussion tab ───────────────────────────────────────────────────

    /**
     * A post is unread until it is opened; your own never is; and the dot follows.
     *
     * The row flag and the instance-wide boolean are asserted together because they
     * are two answers to one question computed by two different queries, and the way
     * this goes wrong is that one of them is fixed and the other is not — leaving a
     * tab with a dot over a list with nothing marked, or the reverse.
     */
    @Test
    fun `a post is unread until it is read, and never for its author`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val post = publishedPost(f, f.adaId, "Something new")

            assertTrue(client.isUnread(f.graceCookie, f, post), "A new post was not unread for a member.")
            assertFalse(client.isUnread(f.adaCookie, f, post), "The author's own post was unread for them.")
            assertTrue(client.discussionDot(f.graceCookie), "The Discussion badge missed a new post.")
            assertFalse(client.discussionDot(f.adaCookie), "The author's own post raised their own badge.")

            val afterReading: ForumPostListState = client.post(ApiRoutes.forumPostRead(f.projectId, f.forumId, post)) {
                cookie(SESSION_COOKIE, f.graceCookie)
            }.body()
            assertFalse(
                afterReading.posts.single { it.id == post }.isUnread,
                "The read response still reported the post as unread.",
            )
            assertFalse(
                client.isUnread(f.graceCookie, f, post),
                "The mark did not survive a fresh read, so nothing was written.",
            )
            assertFalse(client.discussionDot(f.graceCookie), "The dot outlived the last unread post.")
        }
    }

    /**
     * Reading the newest post marks the older ones read, and reading an older one
     * does not un-read the newer.
     *
     * **This is the case the high-water mark cannot express**, and LNL-30 asked for
     * such cases to be recorded rather than quietly turned into a row per user per
     * post. It is recorded here as a test rather than only as a comment, because a
     * later "fix" that made it per-post would be a silent change of storage shape
     * and this is what would notice.
     *
     * The second half is the `MAX` in the upsert doing its work: without it, opening
     * an old thread would roll the mark backwards and everything since would appear
     * unread again — which is the same bug two tabs on one account would cause.
     */
    @Test
    fun `the forum mark moves forward only, and sweeps older posts with it`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val older = publishedPost(f, f.adaId, "Older", at = 1_000)
            val newer = publishedPost(f, f.adaId, "Newer", at = 2_000)
            val newest = publishedPost(f, f.adaId, "Newest", at = 3_000)

            client.post(ApiRoutes.forumPostRead(f.projectId, f.forumId, newer)) {
                cookie(SESSION_COOKIE, f.graceCookie)
            }
            assertFalse(client.isUnread(f.graceCookie, f, older), "An older post survived the mark moving past it.")
            assertFalse(client.isUnread(f.graceCookie, f, newer))
            assertTrue(client.isUnread(f.graceCookie, f, newest), "A newer post was swept up by reading an older one.")

            // Back to the oldest one. The mark must not follow.
            client.post(ApiRoutes.forumPostRead(f.projectId, f.forumId, older)) {
                cookie(SESSION_COOKIE, f.graceCookie)
            }
            assertFalse(client.isUnread(f.graceCookie, f, newer), "Re-reading an older post rolled the mark backwards.")
        }
    }

    /**
     * The Discussion badge spans projects, and losing sight of one removes its
     * contribution.
     *
     * LNL-64's acceptance criterion, and the one with no backstop in the UI: nothing
     * on any screen shows a badge computed from a project the reader cannot open, so
     * a build that counted every project would look perfect and would be telling
     * people there is something to read in a room they cannot enter.
     *
     * The role is revoked **after** the post exists, which is the shape LNL-63
     * learned to test for: a visibility check written at write time looks correct and
     * is asked at exactly the wrong moment, because a grant is revoked long
     * afterwards by a gesture that knows nothing about badges.
     */
    @Test
    fun `the Discussion badge spans visible projects and drops one that is revoked`(): Unit = runBlocking {
        val f = seed()
        val second = projectRepository.create("Second", "SEC")
        val secondForum = forums.create(second.id, "Elsewhere", null)
        roles.setRole(f.graceId, second.id, ProjectRole.VIEWER)
        withRoutes { client ->
            // Nothing in the project Grace is looking at; the post is in the other
            // one, which is exactly the case a per-project badge would miss.
            val id = forumPosts.createPostDraft(secondForum.id, Author.Account(f.adaId))
            forumPosts.publishPost(forumPosts.findPost(id)!!, "Over here", "Body.")
            roles.setRole(f.adaId, second.id, ProjectRole.VIEWER)

            assertTrue(client.discussionDot(f.graceCookie), "The badge did not span projects.")

            roles.setRole(f.graceId, second.id, null)

            assertFalse(
                client.discussionDot(f.graceCookie),
                "A project Grace can no longer see went on contributing to her badge.",
            )
        }
    }

    /**
     * A draft post counts for nobody, its own author included.
     *
     * The mirror of the unsent-message test one feature over, and it matters more
     * here: a draft post is created the moment somebody presses "New post", so a
     * count over rows rather than published rows would give every author a dot the
     * instant they opened a composer — on their own unwritten text.
     */
    @Test
    fun `a draft post raises nobody's dot`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            forumPosts.createPostDraft(f.forumId, Author.Account(f.adaId))
            assertFalse(client.discussionDot(f.graceCookie), "An unpublished post raised a member's dot.")
            assertFalse(client.discussionDot(f.adaCookie), "An unpublished post raised its own author's dot.")
        }
    }

    // ── Signed out ───────────────────────────────────────────────────────────

    /**
     * A signed-out visitor has no badge state at all.
     *
     * Both halves, because they fail differently. The conversation list is empty
     * because there is nobody to have conversations; the Discussion dot is false
     * because a visitor has **no read marks**, so the naive comparison makes every
     * post on the instance new to them for ever — a dot on a tab that nothing can
     * clear, on a public project, for everybody who is not signed in.
     *
     * This is also the "no badge state leaks between accounts after sign-out"
     * criterion at the server: the client clears its own copy, and this is what
     * makes the answer it then fetches the right one.
     */
    @Test
    fun `a signed-out visitor has no badges`(): Unit = runBlocking {
        val f = seed()
        val public = projectRepository.createOpenToAll("Open", "OPN", roles)
        val publicForum = forums.create(public.id, "Lobby", null)
        withRoutes { client ->
            val id = forumPosts.createPostDraft(publicForum.id, Author.Account(f.adaId))
            forumPosts.publishPost(forumPosts.findPost(id)!!, "Anybody can read this", "Body.")
            client.conversationFrom(f.adaCookie, listOf(f.graceId), "Private.")

            val listed: ConversationListState = client.get(ApiRoutes.CONVERSATIONS).body()
            assertEquals(emptyList(), listed.conversations, "A signed-out visitor was shown conversations.")

            val dot: DiscussionUnreadState = client.get(ApiRoutes.DISCUSSION_UNREAD).body()
            assertFalse(dot.hasUnreadPosts, "A signed-out visitor was given a permanent unread dot.")
        }
    }

    /**
     * Marking something read needs a session, and says so.
     *
     * A public project's post is readable without one, so the read route is
     * reachable by a caller there is nowhere to record anything against. Refused
     * rather than silently succeeding, so a client that stopped sending its cookie
     * does not appear to work while writing nothing.
     */
    @Test
    fun `a signed-out visitor cannot mark a public post read`(): Unit = runBlocking {
        val f = seed()
        val public = projectRepository.createOpenToAll("Open", "OPN", roles)
        val publicForum = forums.create(public.id, "Lobby", null)
        withRoutes { client ->
            val id = forumPosts.createPostDraft(publicForum.id, Author.Account(f.adaId))
            forumPosts.publishPost(forumPosts.findPost(id)!!, "Anybody can read this", "Body.")

            val refusal = client.post(ApiRoutes.forumPostRead(public.id, publicForum.id, id))
            assertEquals(HttpStatusCode.Forbidden, refusal.status)
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(
        val adaCookie: String,
        val graceCookie: String,
        val adaId: Long,
        val graceId: Long,
        val projectId: Long,
        val forumId: Long,
    )

    /**
     * Two ordinary members of one private project, and a system administrator who
     * appears in no assertion.
     *
     * The administrator is created only to spend the "first account on the instance
     * becomes the system administrator" badge on somebody harmless —
     * `ForumWatchTest.seed` explains why at length: an admin can see every project,
     * so an admin standing in for a member would make the visibility test pass on a
     * build that never checks visibility.
     */
    private suspend fun seed(): Fixture {
        users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-sys", "Sys", "sys@example.com"))
        val ada = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ada", "Ada", "ada@example.com"))
        val grace = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-gra", "Grace", "grace@example.com"))
        assertFalse(ada.isInstanceAdmin, "The fixture's ordinary user is an administrator.")

        val project = projectRepository.create("Lunamux", "LMX")
        roles.setRole(ada.id, project.id, ProjectRole.VIEWER)
        roles.setRole(grace.id, project.id, ProjectRole.VIEWER)

        // Production seats the instance owner at boot (see InstanceLadder.kt), and
        // four rules — creating and managing projects, backfilling authorship, agent
        // mail, out-of-band attachment deletes — are the owner's alone rather than an
        // administrator's. A fixture that skipped this would be testing an instance
        // nobody runs: one with an administrator and no owner.
        seatInstanceOwner(users, instanceSettings)
        return Fixture(
            adaCookie = sessions.create(ada.id),
            graceCookie = sessions.create(grace.id),
            adaId = ada.id,
            graceId = grace.id,
            projectId = project.id,
            forumId = forums.create(project.id, "General", null).id,
        )
    }

    /**
     * A published post, optionally claiming a specific instant.
     *
     * Written through the store rather than the routes, because these tests are
     * about what is *unread* rather than about publishing — and because [at] has no
     * route that would accept it: nothing in the forum backfills a timestamp, by
     * LNL-30's "no MCP" decision. The timestamps matter: the forum mark is a moment,
     * so two posts a millisecond apart would make "older" and "newer" a coin toss.
     */
    private suspend fun publishedPost(f: Fixture, authorId: Long, title: String, at: Long? = null): Long {
        val id = forumPostStore.insertDraft(f.forumId, Author.Account(authorId), createdAt = at)
        forumPosts.publishPost(forumPosts.findPost(id)!!, title, "Body of $title.")
        return id
    }

    private suspend fun HttpClient.conversationFrom(
        cookie: String,
        recipientIds: List<Long>,
        body: String,
    ): Long {
        val draft: ConversationDraft = post(ApiRoutes.CONVERSATIONS) {
            cookie(SESSION_COOKIE, cookie)
            contentType(ContentType.Application.Json)
            setBody(ConversationStart(recipientIds))
        }.body()
        put(ApiRoutes.conversationMessage(draft.conversationId, draft.messageId)) {
            cookie(SESSION_COOKIE, cookie)
            contentType(ContentType.Application.Json)
            setBody(MessageEdit(body))
        }
        return draft.conversationId
    }

    /** @return the id of the message that was sent, for the delete test. */
    private suspend fun HttpClient.reply(cookie: String, conversationId: Long, body: String): Long {
        val draft: ConversationDraft = post(ApiRoutes.conversationMessages(conversationId)) {
            cookie(SESSION_COOKIE, cookie)
        }.body()
        put(ApiRoutes.conversationMessage(conversationId, draft.messageId)) {
            cookie(SESSION_COOKIE, cookie)
            contentType(ContentType.Application.Json)
            setBody(MessageEdit(body))
        }
        return draft.messageId
    }

    private suspend fun HttpClient.conversations(cookie: String): ConversationListState =
        get(ApiRoutes.CONVERSATIONS) { cookie(SESSION_COOKIE, cookie) }.body()

    private suspend fun HttpClient.unreadCount(cookie: String, conversationId: Long): Long =
        conversations(cookie).conversations.single { it.id == conversationId }.unreadCount

    /** The Messages tab's badge, summed the way the client sums it. */
    private suspend fun HttpClient.totalUnread(cookie: String): Long =
        conversations(cookie).conversations.sumOf { it.unreadCount }

    private suspend fun HttpClient.isUnread(cookie: String, f: Fixture, postId: Long): Boolean {
        val list: ForumPostListState = get(ApiRoutes.forumPosts(f.projectId, f.forumId)) {
            cookie(SESSION_COOKIE, cookie)
        }.body()
        return list.posts.single { it.id == postId }.isUnread
    }

    private suspend fun HttpClient.discussionDot(cookie: String): Boolean {
        val state: DiscussionUnreadState = get(ApiRoutes.DISCUSSION_UNREAD) {
            cookie(SESSION_COOKIE, cookie)
        }.body()
        return state.hasUnreadPosts
    }

    private fun withRoutes(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing { boardRoutes(dependencies()) }
        }
        block(createClient { install(ClientContentNegotiation) { json() } })
    }

    private fun dependencies() = BoardDependencies(
        access = access,
        projects = projects,
        projectRepository = projectRepository,
        roles = roles,
        vocabularies = vocabularies,
        forums = forums,
        forumPosts = forumPosts,
        audience = ProjectAudience(users, roles, instanceSettings),
        conversations = conversations,
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
        reads = reads,
    )
}
