/**
 * Watching a forum and watching a post: who may take out a subscription, and who
 * an e-mail actually goes to once one exists.
 *
 * ── The interesting failures this file exists to catch ──────────────────────
 *
 *  - **A watcher who has lost sight of the project keeps getting mail.** LNL-63's
 *    hardest acceptance criterion, and the only one that cannot be seen from a
 *    screen: nothing in the UI shows a subscription belonging to somebody else, so
 *    a revoked member going on receiving a private project's posts by e-mail —
 *    each with a link that answers 404 — would be discovered by the person
 *    receiving them and nobody else. It is easy to get wrong in the plausible
 *    direction, too: checking visibility when the button is pressed looks correct
 *    and is checked at exactly the wrong moment, because a role is revoked long
 *    afterwards by a gesture that knows nothing about subscriptions.
 *  - **A user with no address is refused rather than silently subscribed.** Asked
 *    for in as many words. The failure is a control that appears to work and
 *    delivers nothing for ever.
 *  - **...but may still unsubscribe.** The natural implementation of the line
 *    above refuses both directions and locks somebody who cleared their address
 *    into a row they cannot remove. Pinned because it is a one-word difference
 *    from the wrong thing.
 *  - **The actor is not mailed about their own writing.** True in three different
 *    ways at once — SQL's `u.id != ?`, [NotificationDispatcher.audience], and
 *    the author auto-watch — which means it is also the property most likely to be
 *    *accidentally* true in a test that proves nothing. So it is asserted with the
 *    actor genuinely subscribed and genuinely holding an address.
 *  - **Publishing fires once; re-saving fires nothing.** The post `PUT` is
 *    publish-and-re-save, so a notifier hung off it unconditionally would announce
 *    a typo fix to the whole forum. Invisible in every manual flow, because nobody
 *    re-saves a post by hand — there is no UI for it.
 *  - **A forum's watchers are not mailed about comments.** The deliberate
 *    narrowing in [ForumNotifier.commentPublished]. A single wrong recipient query
 *    turns watching a room into subscribing to every reply in it, which is the
 *    kind of thing people notice by turning the feature off.
 *  - **The author is auto-watched, and a commenter is not.** LNL-63 asked for this
 *    decision to be made explicitly; pinning both halves is what makes it a
 *    decision rather than whatever the code happened to do.
 *
 * The subscription and refusal claims are driven through the real routes with real
 * session cookies, through the `ApiRoutes` builders like `ForumPostTest` — a route
 * pattern that drifts from the path the client calls fails here rather than in a
 * browser. The recipient claims go through the real [ForumNotificationService]
 * with a recording dispatcher, because "who receives this" is the question, and a
 * [ForumNotifier] recorded at the interface would answer a different one.
 *
 * What the mails *say* is [NotificationEmailTest]'s, which needs no database.
 *
 * @see ForumNotificationService
 * @see forumPostRoutes
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.cookie
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
import se.soderbjorn.lunicle.clientserver.ForumCommentEdit
import se.soderbjorn.lunicle.clientserver.ForumDraftRef
import se.soderbjorn.lunicle.clientserver.ForumPostDetail
import se.soderbjorn.lunicle.clientserver.ForumPostEdit
import se.soderbjorn.lunicle.clientserver.ForumPostListState
import se.soderbjorn.lunicle.clientserver.NotificationSubscriptionRequest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class ForumWatchTest {
    private val file: File = Files.createTempFile("lunicle-forum-watch", ".db").toFile().also { it.delete() }
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
    private val access = AccessControl(roles)
    private val subscriptions = SubscriptionStore(database)
    private val audience = ProjectAudience(users, roles)

    /**
     * Every message the notifier would have sent, in order.
     *
     * A recorder at the *dispatcher* rather than at the [ForumNotifier] interface,
     * which is the one structural decision in this file. `MessageTest` records at
     * the interface because its question is "was the notifier told, and told what"
     * — the recipient rules there are the dispatcher's and are the same for every
     * feature. Here the recipient rules are the point: the visibility narrowing
     * lives inside [ForumNotificationService], so a recorder above it would pass
     * happily on a build that mails everybody.
     *
     * So this is the real service, over the real subscription tables, with only
     * the last inch — the SMTP call — replaced. [NotificationDispatcher] takes a
     * null sender and logs; this overrides [send] instead so the same list can be
     * asserted against.
     */
    private class RecordingDispatcher(users: UserStore) : NotificationDispatcher(users, null) {
        val sent = mutableListOf<Pair<String, String>>()

        override suspend fun send(recipient: EmailRecipient, subject: String, html: String) {
            sent += recipient.email to subject
        }

        /** The addresses mailed, which is what nearly every assertion here is about. */
        fun addresses(): List<String> = sent.map { it.first }.sorted()
    }

    private val dispatcher = RecordingDispatcher(users)
    private val notifier = ForumNotificationService(
        subscriptions = subscriptions,
        audience = audience,
        dispatch = dispatcher,
        baseUrl = "https://example.com",
    )

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
        attachmentsDirectory.deleteRecursively()
    }

    // ── Taking out a subscription ────────────────────────────────────────────

    /**
     * The whole gesture, both directions, for both kinds of watch.
     *
     * One test rather than four, because the property worth pinning is that the
     * state *round-trips*: a route that wrote the row but answered with a stale
     * flag would leave a pill that snaps back on the next render, and a route that
     * answered correctly without writing would leave one that snaps back on the
     * next reload. Only reading the flag out of a fresh `GET` catches the second.
     */
    @Test
    fun `a member watches and unwatches a forum and a post`(): Unit = runBlocking {
        val f = seed()
        val post = publishedPost(f, "Something to follow")
        withRoutes { client ->
            val watchingForum: ForumPostListState = client.setForumWatch(f, f.memberCookie, true).body()
            assertTrue(watchingForum.notifyOnNewPosts, "The response did not report the new subscription.")

            val reloaded: ForumPostListState = client.get(ApiRoutes.forumPosts(f.projectId, f.forumId)) {
                cookie(SESSION_COOKIE, f.memberCookie)
            }.body()
            assertTrue(reloaded.notifyOnNewPosts, "The forum subscription did not survive a fresh read.")

            val offAgain: ForumPostListState = client.setForumWatch(f, f.memberCookie, false).body()
            assertFalse(offAgain.notifyOnNewPosts, "Unwatching a forum did not take.")

            // The post half. Watched by the *admin*, not the member — the member
            // wrote this post and is therefore already subscribed to it, which
            // would make "true" the answer before anything was pressed.
            val watchingPost: ForumPostDetail = client.setPostWatch(f, post, f.adminCookie, true).body()
            assertTrue(watchingPost.notifyOnComments, "The response did not report the new subscription.")
            assertTrue(
                client.post_(f, post, f.adminCookie).notifyOnComments,
                "The post subscription did not survive a fresh read.",
            )
            assertFalse(
                client.setPostWatch(f, post, f.adminCookie, false).body<ForumPostDetail>().notifyOnComments,
                "Unwatching a post did not take.",
            )
        }
    }

    /**
     * An account with no address is refused, and told what to do about it.
     *
     * The status is the assertion that matters; the sentence is checked because
     * this refusal is the only thing standing between somebody and a control that
     * appears to work for ever without delivering anything, and a 403 with no
     * explanation would send them looking at the forum rather than at their
     * profile.
     */
    @Test
    fun `an account with no e-mail address cannot subscribe`(): Unit = runBlocking {
        val f = seed()
        val post = publishedPost(f, "Unreachable")
        withRoutes { client ->
            val forumRefusal = client.setForumWatch(f, f.addresslessCookie, true)
            assertEquals(HttpStatusCode.Forbidden, forumRefusal.status)
            assertTrue(
                forumRefusal.body<String>().contains("e-mail address"),
                "The refusal did not say what to do about it.",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.setPostWatch(f, post, f.addresslessCookie, true).status,
            )
            // And nothing was written on the way to being refused.
            assertFalse(
                subscriptions.isSubscribedToForumNewPosts(f.addresslessId, f.forumId),
                "A refused subscribe wrote the row anyway.",
            )
        }
    }

    /**
     * ...but somebody who subscribed and *then* cleared their address can still
     * stop.
     *
     * The natural way to write the check above refuses both directions, which
     * turns the pill into a control that will not switch off. Reachable in the
     * ordinary way — the profile dialog lets anybody clear their address — and the
     * only way out of it would be an administrator editing the database.
     */
    @Test
    fun `unsubscribing works without an address`(): Unit = runBlocking {
        val f = seed()
        subscriptions.setForumNewPostSubscription(f.addresslessId, f.forumId, true)
        withRoutes { client ->
            val result = client.setForumWatch(f, f.addresslessCookie, false)
            assertEquals(HttpStatusCode.OK, result.status, "An addressless account could not unsubscribe.")
            assertFalse(subscriptions.isSubscribedToForumNewPosts(f.addresslessId, f.forumId))
        }
    }

    /**
     * Somebody who cannot see the project cannot subscribe to its forum, and the
     * refusal is 404.
     *
     * A 403 would confirm that a private project with this id exists — the same
     * position every other route in this feature takes, and worth pinning here
     * because these two routes are the newest and were written last.
     */
    @Test
    fun `an outsider cannot watch a private projects forum`(): Unit = runBlocking {
        val f = seed()
        val post = publishedPost(f, "Not for you")
        withRoutes { client ->
            assertEquals(HttpStatusCode.NotFound, client.setForumWatch(f, f.outsiderCookie, true).status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.setPostWatch(f, post, f.outsiderCookie, true).status,
            )
        }
    }

    /** A signed-out visitor is told to sign in, even on a public project. */
    @Test
    fun `a signed-out visitor cannot watch anything`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.publicProjectId, "Open house", null)
        withRoutes { client ->
            val refusal = client.post(ApiRoutes.forumNotification(f.publicProjectId, forum.id)) {
                contentType(ContentType.Application.Json)
                setBody(NotificationSubscriptionRequest(true))
            }
            assertEquals(HttpStatusCode.Forbidden, refusal.status)
            assertTrue(refusal.body<String>().contains("signed in"), "The refusal named the wrong problem.")
        }
    }

    // ── The auto-watch decision (LNL-63) ─────────────────────────────────────

    /**
     * Publishing a post subscribes its author; commenting on one does not.
     *
     * Both halves, because the decision is the *pair* — see `announcePost`, where
     * it is argued. The first mirrors an issue's author being subscribed at
     * publish. The second mirrors an issue's commenter *not* being, and is the
     * half somebody would "fix" for symmetry without noticing that it conscripts
     * everybody who ever said anything in a busy thread.
     */
    @Test
    fun `publishing a post watches its author, commenting does not`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val draft: ForumDraftRef = client.post(ApiRoutes.forumPosts(f.projectId, f.forumId)) {
                cookie(SESSION_COOKIE, f.memberCookie)
            }.body()
            assertFalse(
                subscriptions.isSubscribedToForumPost(f.memberId, draft.id),
                "A draft nobody has published subscribed its author already.",
            )

            client.put(ApiRoutes.forumPost(f.projectId, f.forumId, draft.id)) {
                cookie(SESSION_COOKIE, f.memberCookie)
                contentType(ContentType.Application.Json)
                setBody(ForumPostEdit("Mine", "Body."))
            }
            assertTrue(
                subscriptions.isSubscribedToForumPost(f.memberId, draft.id),
                "Publishing a post did not subscribe its author.",
            )

            client.comment(f, draft.id, f.adminCookie, "Replying.")
            assertFalse(
                subscriptions.isSubscribedToForumPost(f.projectAdminId, draft.id),
                "Commenting subscribed the commenter, which the issue side deliberately does not do.",
            )
        }
    }

    // ── Who actually gets mailed ─────────────────────────────────────────────

    /**
     * A forum's watchers are mailed about a new post; the person who wrote it is
     * not.
     *
     * The actor is subscribed here on purpose. Excluding them is enforced in three
     * separate places, and a test where the actor merely happens not to be a
     * watcher would pass on a build with none of them.
     */
    @Test
    fun `a new post mails the forums watchers except its author`(): Unit = runBlocking {
        val f = seed()
        subscriptions.setForumNewPostSubscription(f.projectAdminId, f.forumId, true)
        subscriptions.setForumNewPostSubscription(f.memberId, f.forumId, true)

        val post = publishedPost(f, "Announcement")
        notifier.postPublished(f.project, f.forum, forumPosts.findPost(post)!!, actorId = f.memberId)

        assertEquals(
            listOf("pat@example.com"),
            dispatcher.addresses(),
            "The author of the post was mailed about their own post, or a watcher was missed.",
        )
        assertTrue(
            dispatcher.sent.single().second.contains("Announcement"),
            "The subject did not name the post: ${dispatcher.sent}",
        )
    }

    /**
     * A watcher with no address on file is not a recipient, and does not stop the
     * others being mailed.
     *
     * The `email IS NOT NULL` in the recipient query. Its failure mode is not a
     * missing mail but a null destination reaching the sender, which would take
     * the whole batch down with it — hence the second assertion.
     */
    @Test
    fun `a watcher with no address is dropped without stopping the batch`(): Unit = runBlocking {
        val f = seed()
        subscriptions.setForumNewPostSubscription(f.addresslessId, f.forumId, true)
        subscriptions.setForumNewPostSubscription(f.projectAdminId, f.forumId, true)

        val post = publishedPost(f, "Anyone there")
        notifier.postPublished(f.project, f.forum, forumPosts.findPost(post)!!, actorId = f.memberId)

        assertEquals(listOf("pat@example.com"), dispatcher.addresses())
    }

    /**
     * A watcher who has lost sight of the project stops receiving its forum mail.
     *
     * LNL-63's acceptance criterion, and the reason the check is at send time
     * rather than at subscribe time: the subscription below is taken out perfectly
     * legitimately, and what invalidates it is an administrator revoking a role
     * somewhere else entirely — a gesture with no idea this row exists.
     *
     * Both events are asserted. They resolve their recipients through different
     * queries and could easily be narrowed in only one of them, and the one that
     * was forgotten would still look correct on a public project, which is what
     * every dev machine has.
     */
    @Test
    fun `a watcher who loses visibility stops being mailed`(): Unit = runBlocking {
        val f = seed()
        val post = publishedPost(f, "Members only")
        subscriptions.setForumNewPostSubscription(f.projectAdminId, f.forumId, true)
        subscriptions.setForumPostSubscription(f.projectAdminId, post, true)

        // While they are still a member, both mails reach them.
        notifier.postPublished(f.project, f.forum, forumPosts.findPost(post)!!, actorId = f.memberId)
        assertEquals(listOf("pat@example.com"), dispatcher.addresses(), "A member was not mailed.")
        dispatcher.sent.clear()

        roles.revoke(f.projectAdminId, f.projectId, Role.PROJECT_ADMIN)

        notifier.postPublished(f.project, f.forum, forumPosts.findPost(post)!!, actorId = f.memberId)
        val comment = publishedComment(f, post, "Still here?")
        notifier.commentPublished(
            f.project,
            f.forum,
            forumPosts.findPost(post)!!,
            forumPosts.findComment(comment)!!,
            actorId = f.memberId,
        )
        assertEquals(
            emptyList(),
            dispatcher.addresses(),
            "Somebody who can no longer see the project was still mailed its forum's contents.",
        )
        // The row is deliberately still there: losing access is not the same as
        // unsubscribing, and restoring the role restores what they asked for.
        assertTrue(
            subscriptions.isSubscribedToForumNewPosts(f.projectAdminId, f.forumId),
            "The send-time check deleted the subscription instead of skipping it.",
        )
    }

    /**
     * A comment mails the post's watchers and **not** the forum's.
     *
     * The narrowing on [ForumNotifier.commentPublished]: watching a room is a
     * request to hear when somebody starts something, not to receive every reply
     * in it. Asserted with somebody watching the forum and not the post, which is
     * the exact shape a single wrong query would over-serve.
     */
    @Test
    fun `a comment mails the posts watchers and not the forums`(): Unit = runBlocking {
        val f = seed()
        val post = publishedPost(f, "Thread")
        // The admin follows the room; the member wrote the post and so follows the
        // thread automatically — but the member is also the one commenting below,
        // so a third account does the following that should actually produce mail.
        subscriptions.setForumNewPostSubscription(f.projectAdminId, f.forumId, true)
        subscriptions.setForumPostSubscription(f.otherId, post, true)

        val comment = publishedComment(f, post, "Adding to this.")
        notifier.commentPublished(
            f.project,
            f.forum,
            forumPosts.findPost(post)!!,
            forumPosts.findComment(comment)!!,
            actorId = f.memberId,
        )

        assertEquals(
            listOf("mo@example.com"),
            dispatcher.addresses(),
            "A comment reached the forum's watchers, or missed the post's.",
        )
    }

    /**
     * Publishing announces once; re-saving the same post announces nothing.
     *
     * Driven through the route, because the guard is the route's — the notifier
     * has no way to tell a publish from a re-save, and the `PUT` is deliberately
     * both. Nobody re-saves a post by hand today (LNL-61 shipped no edit button),
     * so this failure would not appear in any manual flow and would arrive with
     * whoever adds one.
     */
    @Test
    fun `re-saving a published post announces nothing`(): Unit = runBlocking {
        val f = seed()
        subscriptions.setForumNewPostSubscription(f.projectAdminId, f.forumId, true)
        withRoutes(notifier) { client ->
            val draft: ForumDraftRef = client.post(ApiRoutes.forumPosts(f.projectId, f.forumId)) {
                cookie(SESSION_COOKIE, f.memberCookie)
            }.body()
            suspend fun save(title: String) =
                client.put(ApiRoutes.forumPost(f.projectId, f.forumId, draft.id)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ForumPostEdit(title, "Body."))
                }

            save("Frist post")
            assertEquals(1, dispatcher.sent.size, "Publishing did not announce exactly once.")

            save("First post")
            assertEquals(
                1,
                dispatcher.sent.size,
                "Fixing a typo announced the post to the whole forum a second time.",
            )
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(
        val adminCookie: String,
        val memberCookie: String,
        val outsiderCookie: String,
        val addresslessCookie: String,
        val memberId: Long,
        val otherId: Long,
        val projectAdminId: Long,
        val addresslessId: Long,
        val project: ProjectRecord,
        val publicProjectId: Long,
        val forum: ForumRecord,
    ) {
        val projectId: Long get() = project.id
        val forumId: Long get() = forum.id
    }

    /**
     * Four accounts and a private project, shaped by what the recipient rules need
     * to be distinguishable.
     *
     * The private project is the important half: on a public one, "can see it" is
     * true for every account whether the visibility check runs or not, so the
     * revocation test would pass on a build that never checks. Nobody here is a
     * system administrator, for the same reason — an admin sees every project, so
     * one standing in for a member would hide the same bug.
     *
     * The addressless account is a real state rather than a contrivance: an
     * address is set in the profile dialog and nothing requires one.
     */
    private suspend fun seed(): Fixture {
        roles.seed()
        // The first account on an instance becomes the system administrator, and
        // one is deliberately *not* wanted below — an admin can see every project,
        // so an admin standing in for a member would make the visibility tests pass
        // on a build that never checks visibility. So the badge is spent here, on
        // an account that appears in no assertion.
        users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-sys", "Sys", "sys@example.com"))
        val projectAdmin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-pa", "Pat", "pat@example.com"))
        val member = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-mem", "Mem", "mem@example.com"))
        val other = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-mo", "Mo", "mo@example.com"))
        val addressless = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-no", "Nomail", null))
        val outsider = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-out", "Out", "out@example.com"))
        assertFalse(projectAdmin.isSysAdmin, "The fixture's project administrator is a system one.")

        val project = projectRepository.create("Lunamux", "LMX", isPublic = false)
        val public = projectRepository.create("Open", "OPN", isPublic = true)
        roles.grant(projectAdmin.id, project.id, Role.PROJECT_ADMIN)
        roles.grant(member.id, project.id, Role.VIEW_PROJECT)
        roles.grant(other.id, project.id, Role.VIEW_PROJECT)
        roles.grant(addressless.id, project.id, Role.VIEW_PROJECT)

        return Fixture(
            adminCookie = sessions.create(projectAdmin.id),
            memberCookie = sessions.create(member.id),
            outsiderCookie = sessions.create(outsider.id),
            addresslessCookie = sessions.create(addressless.id),
            memberId = member.id,
            otherId = other.id,
            projectAdminId = projectAdmin.id,
            addresslessId = addressless.id,
            project = project,
            publicProjectId = public.id,
            forum = forums.create(project.id, "General", null),
        )
    }

    /**
     * A published post by the ordinary member, written through the repository.
     *
     * Through the repository rather than the routes, matching `ForumPostTest`: this
     * is a *fixture* for the assertions, and driving it through the `PUT` would
     * both fire the notifier and subscribe the author, which is precisely what
     * several tests below are trying to control.
     */
    private suspend fun publishedPost(f: Fixture, title: String): Long {
        val id = forumPosts.createPostDraft(f.forumId, Author.Account(f.memberId))
        forumPosts.publishPost(forumPosts.findPost(id)!!, title, "Body of $title.")
        return id
    }

    /** As [publishedPost], for a comment by the same member. */
    private suspend fun publishedComment(f: Fixture, postId: Long, body: String): Long {
        val id = forumPosts.createCommentDraft(postId, Author.Account(f.memberId))
        forumPosts.publishComment(forumPosts.findComment(id)!!, body)
        return id
    }

    // ── Request helpers ──────────────────────────────────────────────────────
    //
    // Named rather than inlined because the two watch routes are called eight
    // times between them and the `contentType`/`setBody` pair is noise in every
    // one. They still go through the ApiRoutes builders, which is the property
    // this file shares with ForumPostTest.

    private suspend fun HttpClient.setForumWatch(f: Fixture, sessionId: String, subscribed: Boolean) =
        post(ApiRoutes.forumNotification(f.projectId, f.forumId)) {
            cookie(SESSION_COOKIE, sessionId)
            contentType(ContentType.Application.Json)
            setBody(NotificationSubscriptionRequest(subscribed))
        }

    private suspend fun HttpClient.setPostWatch(
        f: Fixture,
        postId: Long,
        sessionId: String,
        subscribed: Boolean,
    ) = post(ApiRoutes.forumPostNotification(f.projectId, f.forumId, postId)) {
        cookie(SESSION_COOKIE, sessionId)
        contentType(ContentType.Application.Json)
        setBody(NotificationSubscriptionRequest(subscribed))
    }

    /** One post, read back. Underscored because `post` is the HTTP verb here. */
    private suspend fun HttpClient.post_(f: Fixture, postId: Long, sessionId: String): ForumPostDetail =
        get(ApiRoutes.forumPost(f.projectId, f.forumId, postId)) {
            cookie(SESSION_COOKIE, sessionId)
        }.body()

    /** Draft and publish a comment through the routes, as the composer does. */
    private suspend fun HttpClient.comment(f: Fixture, postId: Long, sessionId: String, body: String) {
        val draft: ForumDraftRef = post(ApiRoutes.forumComments(f.projectId, f.forumId, postId)) {
            cookie(SESSION_COOKIE, sessionId)
        }.body()
        put(ApiRoutes.forumComment(f.projectId, f.forumId, postId, draft.id)) {
            cookie(SESSION_COOKIE, sessionId)
            contentType(ContentType.Application.Json)
            setBody(ForumCommentEdit(body))
        }
    }

    /**
     * @param notifications the notifier the routes should fire, defaulting to the
     *   no-op. Most tests here drive the notifier *directly* — the recipient rules
     *   are the subject — and pass nothing; the one that is about the route's
     *   publish/re-save guard passes the real service.
     */
    private fun withRoutes(
        notifications: ForumNotifier = NoForumNotifications,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing { boardRoutes(dependencies(notifications)) }
        }
        block(createClient { install(ClientContentNegotiation) { json() } })
    }

    private fun dependencies(notifications: ForumNotifier) = BoardDependencies(
        access = access,
        projects = projects,
        projectRepository = projectRepository,
        roles = roles,
        vocabularies = vocabularies,
        forums = forums,
        forumPosts = forumPosts,
        audience = audience,
        // Not exercised here; a route bundle is one object and there is no half of
        // it. See MessageTest for the tests that do.
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
        subscriptions = subscriptions,
        reads = ReadStore(database),
        forumNotifications = notifications,
    )
}
