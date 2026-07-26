/**
 * Forum posts and their flat comments: who may write one, who may delete one, and
 * what a file attached to either is reachable by.
 *
 * ── The interesting failures this file exists to catch ──────────────────────
 *
 *  - **A third party can delete neither.** The acceptance criterion with the most
 *    ways to go subtly wrong, because there are three answers to "may you delete
 *    this" — author, project administrator, system administrator — and a rule that
 *    accidentally returned true for a fourth would look correct on every screen
 *    anybody develops against, where they are usually the author. Asserted at the
 *    route with a real session cookie: a test against [AccessControl] alone passes
 *    on a route that never called it.
 *  - **Deleting is broader than editing, and only by exactly one clause.** LNL-30
 *    decided a project administrator may delete other people's posts, which is a
 *    deliberate departure from `canEditComment`'s stance. The risk is that the
 *    departure quietly spreads: an administrator who could also *edit* somebody
 *    else's post could rewrite what they are recorded as having said, under their
 *    name, with no history to notice it — forums record none. So this file pins
 *    both halves, not just the permissive one.
 *  - **A non-member cannot reach any of it, and is refused with 404.** A 403 would
 *    confirm that a private project with that id exists. Nothing else backstops
 *    this for posts: every route here is new, and one that forgot
 *    `readableProject` would look perfect on a public project, which is what every
 *    dev machine has.
 *  - **A draft is invisible.** A post is created empty so that an image has an
 *    owner before there is a body — the shape that makes attachments work at all —
 *    and the cost of it is a window in which a row exists that nobody has agreed
 *    to publish. It must appear in no list and be readable by nobody but its
 *    author.
 *  - **A forum attachment resolves its project through two hops.** `serveAttachment`
 *    used to walk `comment → issue → project` and now has to walk `forum comment →
 *    post → forum → project` as well. A missed link there is not a 500: it is an
 *    attachment in a private project served to anybody who has the URL.
 *  - **Deleting a post takes its comments, and deleting a forum takes its posts.**
 *    Both are `ON DELETE CASCADE`, which is invisible in Kotlin, and the files
 *    behind them are not — SQLite cannot reach the filesystem, so the keys must be
 *    read *before* the delete or the volume keeps them for ever with nothing able
 *    to name them.
 *
 * Every request goes through the `ApiRoutes` builders rather than hand-written
 * strings, so a route pattern that drifts from the path the client will call fails
 * here rather than in a browser. `ForumTest` says the same about its three.
 *
 * @see forumPostRoutes
 * @see AccessControl.canDeleteForumContent
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
import se.soderbjorn.lunicle.clientserver.AttachmentRef
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.ForumCommentEdit
import se.soderbjorn.lunicle.clientserver.ForumDraftRef
import se.soderbjorn.lunicle.clientserver.ForumPostDetail
import se.soderbjorn.lunicle.clientserver.ForumPostEdit
import se.soderbjorn.lunicle.clientserver.ForumPostListState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class ForumPostTest {
    private val file: File = Files.createTempFile("lunicle-forum-posts", ".db").toFile().also { it.delete() }
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
    // Not exercised here; the two bulk markdown passes take it because messages
    // are the fifth place markdown is stored. See MessageTest.
    private val messages = MessageStore(database)
    private val access = AccessControl(roles)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
        attachmentsDirectory.deleteRecursively()
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    /**
     * The whole lifecycle a composer performs: draft, publish, comment, read back.
     *
     * One test rather than four, because the interesting property is that the
     * steps compose — a draft that publishes but does not then appear in the list
     * is a bug nothing else here would notice.
     */
    @Test
    fun `a member starts a post, publishes it and comments on it`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val draft: ForumDraftRef = client.post(ApiRoutes.forumPosts(f.projectId, f.forumId)) {
                cookie(SESSION_COOKIE, f.memberCookie)
            }.body()

            // Nothing is visible until it is published: the row exists only so an
            // upload has an owner.
            val whileDrafting: ForumPostListState =
                client.get(ApiRoutes.forumPosts(f.projectId, f.forumId)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                }.body()
            assertEquals(emptyList(), whileDrafting.posts, "An unpublished draft appeared in the post list.")

            val published: ForumPostDetail =
                client.put(ApiRoutes.forumPost(f.projectId, f.forumId, draft.id)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ForumPostEdit(title = "  Hello  ", body = "  First post.  "))
                }.body()
            assertEquals("Hello", published.title, "The title was not trimmed.")
            assertEquals("First post.", published.body)
            assertEquals("Mem", published.authorName)
            assertEquals("General", published.forumName, "The detail did not name the forum it is in.")

            val commentDraft: ForumDraftRef =
                client.post(ApiRoutes.forumComments(f.projectId, f.forumId, draft.id)) {
                    cookie(SESSION_COOKIE, f.adminCookie)
                }.body()
            val withComment: ForumPostDetail =
                client.put(ApiRoutes.forumComment(f.projectId, f.forumId, draft.id, commentDraft.id)) {
                    cookie(SESSION_COOKIE, f.adminCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ForumCommentEdit(body = "Welcome."))
                }.body()
            assertEquals(listOf("Welcome."), withComment.comments.map { it.body })
            assertEquals(listOf("Pat"), withComment.comments.map { it.authorName })

            val listed: ForumPostListState =
                client.get(ApiRoutes.forumPosts(f.projectId, f.forumId)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                }.body()
            assertEquals(listOf("Hello"), listed.posts.map { it.title })
            assertEquals(
                1L,
                listed.posts.single().commentCount,
                "The list's comment count did not follow the comment.",
            )
        }
    }

    /**
     * Comments come back flat and in the order they were written.
     *
     * Pinned because "flat, in order" is the whole of the shape LNL-30 decided on,
     * and it is expressed by an `ORDER BY` in one query — the sort of thing a later
     * change to that query can drop without any test noticing.
     */
    @Test
    fun `comments render flat and in order`(): Unit = runBlocking {
        val f = seed()
        val post = publishedPost(f, "Ordering")
        withRoutes { client ->
            listOf("One", "Two", "Three").forEach { body ->
                val draft: ForumDraftRef =
                    client.post(ApiRoutes.forumComments(f.projectId, f.forumId, post)) {
                        cookie(SESSION_COOKIE, f.memberCookie)
                    }.body()
                client.put(ApiRoutes.forumComment(f.projectId, f.forumId, post, draft.id)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ForumCommentEdit(body))
                }
            }
            val detail: ForumPostDetail = client.get(ApiRoutes.forumPost(f.projectId, f.forumId, post)) {
                cookie(SESSION_COOKIE, f.memberCookie)
            }.body()
            assertEquals(listOf("One", "Two", "Three"), detail.comments.map { it.body })
        }
    }

    /** A blank title or body is a sentence, not a 500. */
    @Test
    fun `a blank title or body is refused with words`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val draft: ForumDraftRef = client.post(ApiRoutes.forumPosts(f.projectId, f.forumId)) {
                cookie(SESSION_COOKIE, f.memberCookie)
            }.body()
            suspend fun publish(title: String, body: String) =
                client.put(ApiRoutes.forumPost(f.projectId, f.forumId, draft.id)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ForumPostEdit(title, body))
                }.status
            assertEquals(HttpStatusCode.Conflict, publish("   ", "Something"))
            assertEquals(HttpStatusCode.Conflict, publish("Something", "   "))
        }
    }

    // ── Reading, and being refused ───────────────────────────────────────────

    /**
     * Somebody who cannot see the project can neither read nor write any of it,
     * and every refusal is 404 rather than 403.
     *
     * The claim with no backstop anywhere else. Note the write attempts as well as
     * the reads: a route that gated only its `get` would leave the composer's
     * `post` as an open door into a private project's forum.
     */
    @Test
    fun `a non-member can neither read nor write a private projects posts`(): Unit = runBlocking {
        val f = seed()
        val post = publishedPost(f, "Private business")
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.forumPosts(f.projectId, f.forumId)) {
                    cookie(SESSION_COOKIE, f.outsiderCookie)
                }.status,
                "A 403 here would confirm that a private project with this id exists.",
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.forumPost(f.projectId, f.forumId, post)) {
                    cookie(SESSION_COOKIE, f.outsiderCookie)
                }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.post(ApiRoutes.forumPosts(f.projectId, f.forumId)) {
                    cookie(SESSION_COOKIE, f.outsiderCookie)
                }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.post(ApiRoutes.forumComments(f.projectId, f.forumId, post)) {
                    cookie(SESSION_COOKIE, f.outsiderCookie)
                }.status,
            )
        }
    }

    /**
     * A signed-out visitor reads a public project's posts, and cannot write.
     *
     * The two halves of `canPostInProject`, which is `canReadProject` minus the
     * signed-out case. Reading anonymously is a feature; writing anonymously would
     * be a post with no author to attribute it to and nobody able to delete it.
     */
    @Test
    fun `a signed-out visitor reads a public forum but cannot post in it`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.publicProjectId, "Open house", null)
        val draftId = forumPosts.createPostDraft(forum.id, Author.Account(f.memberId))
        forumPosts.publishPost(
            forumPosts.findPost(draftId)!!,
            "Anybody home",
            "Hello, world.",
        )

        withRoutes { client ->
            val listed: ForumPostListState =
                client.get(ApiRoutes.forumPosts(f.publicProjectId, forum.id)).body()
            assertEquals(listOf("Anybody home"), listed.posts.map { it.title })
            assertFalse(listed.canPost, "A signed-out visitor was told they may post.")
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post(ApiRoutes.forumPosts(f.publicProjectId, forum.id)).status,
            )
        }
    }

    /** A post named under another forum's id is a 404, not a read of it. */
    @Test
    fun `a post cannot be reached through the wrong forum`(): Unit = runBlocking {
        val f = seed()
        val post = publishedPost(f, "Mine")
        val other = forums.create(f.projectId, "Elsewhere", null)
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.forumPost(f.projectId, other.id, post)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                }.status,
            )
        }
    }

    /**
     * A draft is readable by its author and by nobody else.
     *
     * The cost of creating the row before the body exists. A guessed id must answer
     * exactly as an absent one does, or the two-step write becomes a way to read
     * what somebody is still writing.
     */
    @Test
    fun `an unpublished draft is readable only by its author`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val draft: ForumDraftRef = client.post(ApiRoutes.forumPosts(f.projectId, f.forumId)) {
                cookie(SESSION_COOKIE, f.memberCookie)
            }.body()
            assertEquals(
                HttpStatusCode.OK,
                client.get(ApiRoutes.forumPost(f.projectId, f.forumId, draft.id)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                }.status,
                "The author could not read the draft they are writing.",
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.forumPost(f.projectId, f.forumId, draft.id)) {
                    cookie(SESSION_COOKIE, f.secondMemberCookie)
                }.status,
                "Somebody else read a post that has not been published.",
            )
        }
    }

    // ── Deleting ─────────────────────────────────────────────────────────────

    /**
     * The acceptance criterion, in one test: author yes, project administrator yes,
     * a third party no.
     *
     * All three against the same post, in that order, because the property is
     * relative — a rule that said yes to everybody would pass the first two
     * assertions on its own.
     */
    @Test
    fun `an author and a project administrator may delete a post, and a third party may not`(): Unit =
        runBlocking {
            val f = seed()
            val post = publishedPost(f, "Contentious")
            withRoutes { client ->
                assertEquals(
                    HttpStatusCode.Forbidden,
                    client.delete(ApiRoutes.forumPost(f.projectId, f.forumId, post)) {
                        cookie(SESSION_COOKIE, f.secondMemberCookie)
                    }.status,
                    "A third party deleted somebody else's post.",
                )
                assertEquals(1, forumPosts.listing(f.forumId).size, "A refused delete landed anyway.")

                // The project administrator may, by LNL-30's decision. This is the
                // one place a role reaches somebody else's words.
                assertEquals(
                    HttpStatusCode.OK,
                    client.delete(ApiRoutes.forumPost(f.projectId, f.forumId, post)) {
                        cookie(SESSION_COOKIE, f.adminCookie)
                    }.status,
                )
                assertEquals(emptyList(), forumPosts.listing(f.forumId))

                val mine = publishedPost(f, "My own")
                assertEquals(
                    HttpStatusCode.OK,
                    client.delete(ApiRoutes.forumPost(f.projectId, f.forumId, mine)) {
                        cookie(SESSION_COOKIE, f.memberCookie)
                    }.status,
                    "An author could not delete their own post.",
                )
            }
        }

    /** The same three answers, for a comment. */
    @Test
    fun `an author and a project administrator may delete a comment, and a third party may not`(): Unit =
        runBlocking {
            val f = seed()
            val post = publishedPost(f, "Thread")
            val comment = publishedComment(f, post, "Something contentious")
            withRoutes { client ->
                assertEquals(
                    HttpStatusCode.Forbidden,
                    client.delete(ApiRoutes.forumComment(f.projectId, f.forumId, post, comment)) {
                        cookie(SESSION_COOKIE, f.secondMemberCookie)
                    }.status,
                )
                assertEquals(1, forumPosts.commentsOn(post).size, "A refused delete landed anyway.")
                assertEquals(
                    HttpStatusCode.OK,
                    client.delete(ApiRoutes.forumComment(f.projectId, f.forumId, post, comment)) {
                        cookie(SESSION_COOKIE, f.adminCookie)
                    }.status,
                )
                assertEquals(emptyList(), forumPosts.commentsOn(post))
            }
        }

    /**
     * A project administrator may delete somebody else's post and may **not** edit
     * it.
     *
     * The asymmetry LNL-30 asked for, pinned so it stays narrow. Editing would let
     * a moderator change what somebody is recorded as having said, under their
     * name, and forums record no history — so nothing anywhere would notice.
     */
    @Test
    fun `a project administrator cannot edit somebody elses post`(): Unit = runBlocking {
        val f = seed()
        val post = publishedPost(f, "Mine, and it stays mine")
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.put(ApiRoutes.forumPost(f.projectId, f.forumId, post)) {
                    cookie(SESSION_COOKIE, f.adminCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ForumPostEdit(title = "Rewritten", body = "Not what they said."))
                }.status,
            )
        }
        assertEquals("Mine, and it stays mine", forumPosts.findPost(post)?.title)
    }

    /** Deleting a post takes its comments with it — the cascade in ForumComments.sq. */
    @Test
    fun `deleting a post deletes its comments`(): Unit = runBlocking {
        val f = seed()
        val post = publishedPost(f, "Doomed")
        publishedComment(f, post, "Also doomed")
        forumPosts.deletePost(forumPosts.findPost(post)!!)
        assertEquals(emptyList(), forumPosts.commentsOn(post))
    }

    /** Deleting a forum takes its posts with it — the cascade in ForumPosts.sq. */
    @Test
    fun `deleting a forum deletes its posts`(): Unit = runBlocking {
        val f = seed()
        publishedPost(f, "Goes with the room")
        forums.delete(forums.findById(f.forumId)!!)
        assertEquals(emptyList(), forumPosts.listing(f.forumId))
    }

    // ── Attachments ──────────────────────────────────────────────────────────

    /**
     * A file uploaded into a post is served back to a member and withheld from an
     * outsider.
     *
     * `serveAttachment` resolves the owning project before it streams a byte, and
     * for a forum post that walk is `post → forum → project` — two hops it did not
     * have before this ticket. A missed link there does not throw; it serves a
     * private project's file to anybody holding the URL.
     */
    @Test
    fun `a post attachment is readable by a member and withheld from an outsider`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val draft: ForumDraftRef = client.post(ApiRoutes.forumPosts(f.projectId, f.forumId)) {
                cookie(SESSION_COOKIE, f.memberCookie)
            }.body()
            val ref: AttachmentRef = client.post(ApiRoutes.forumPostAttachments(draft.id) + "?filename=shot.png") {
                cookie(SESSION_COOKIE, f.memberCookie)
                contentType(ContentType.Image.PNG)
                setBody(PNG_BYTES)
            }.body()

            assertEquals(
                HttpStatusCode.OK,
                client.get(ApiRoutes.attachment(ref.id)) { cookie(SESSION_COOKIE, f.memberCookie) }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.attachment(ref.id)) { cookie(SESSION_COOKIE, f.outsiderCookie) }.status,
                "A private project's forum attachment was served to somebody who cannot see the project.",
            )
        }
    }

    /**
     * The same, one level down: a file uploaded into a comment.
     *
     * Its own test rather than a second assertion above, because the walk is a hop
     * longer — `comment → post → forum → project` — and it is the one most likely
     * to be left out.
     */
    @Test
    fun `a comment attachment resolves its project through the post`(): Unit = runBlocking {
        val f = seed()
        val post = publishedPost(f, "With pictures")
        withRoutes { client ->
            val draft: ForumDraftRef =
                client.post(ApiRoutes.forumComments(f.projectId, f.forumId, post)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                }.body()
            val ref: AttachmentRef = client.post(ApiRoutes.forumCommentAttachments(draft.id) + "?filename=shot.png") {
                cookie(SESSION_COOKIE, f.memberCookie)
                contentType(ContentType.Image.PNG)
                setBody(PNG_BYTES)
            }.body()
            assertEquals(
                HttpStatusCode.OK,
                client.get(ApiRoutes.attachment(ref.id)) { cookie(SESSION_COOKIE, f.memberCookie) }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.attachment(ref.id)) { cookie(SESSION_COOKIE, f.outsiderCookie) }.status,
            )
        }
    }

    /**
     * Somebody else cannot attach a file to your post.
     *
     * The upload route is a *write*, so it runs the editing rule and not the
     * reading one — and specifically not the deleting one, so a project
     * administrator who may remove a post may not put bytes inside it under its
     * author's name.
     */
    @Test
    fun `only the author may attach a file to a post`(): Unit = runBlocking {
        val f = seed()
        val post = publishedPost(f, "Hands off")
        withRoutes { client ->
            listOf(f.secondMemberCookie, f.adminCookie).forEach { cookie ->
                assertEquals(
                    HttpStatusCode.Forbidden,
                    client.post(ApiRoutes.forumPostAttachments(post) + "?filename=shot.png") {
                        cookie(SESSION_COOKIE, cookie)
                        contentType(ContentType.Image.PNG)
                        setBody(PNG_BYTES)
                    }.status,
                )
            }
        }
    }

    /**
     * Deleting a post unlinks the files it and its comments owned.
     *
     * The rows go by cascade and SQLite cannot reach the filesystem, so the keys
     * have to be read *before* the delete. Get that backwards and nothing fails —
     * the volume simply keeps every image in every deleted post, for ever, with
     * nothing able to identify them.
     */
    @Test
    fun `deleting a post unlinks its files and its comments files`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val draft: ForumDraftRef = client.post(ApiRoutes.forumPosts(f.projectId, f.forumId)) {
                cookie(SESSION_COOKIE, f.memberCookie)
            }.body()
            client.post(ApiRoutes.forumPostAttachments(draft.id) + "?filename=shot.png") {
                cookie(SESSION_COOKIE, f.memberCookie)
                contentType(ContentType.Image.PNG)
                setBody(PNG_BYTES)
            }
            client.put(ApiRoutes.forumPost(f.projectId, f.forumId, draft.id)) {
                cookie(SESSION_COOKIE, f.memberCookie)
                contentType(ContentType.Application.Json)
                setBody(ForumPostEdit("Illustrated", "See attached."))
            }
            val commentDraft: ForumDraftRef =
                client.post(ApiRoutes.forumComments(f.projectId, f.forumId, draft.id)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                }.body()
            client.post(ApiRoutes.forumCommentAttachments(commentDraft.id) + "?filename=reply.png") {
                cookie(SESSION_COOKIE, f.memberCookie)
                contentType(ContentType.Image.PNG)
                setBody(PNG_BYTES)
            }
            client.put(ApiRoutes.forumComment(f.projectId, f.forumId, draft.id, commentDraft.id)) {
                cookie(SESSION_COOKIE, f.memberCookie)
                contentType(ContentType.Application.Json)
                setBody(ForumCommentEdit("And another."))
            }

            assertEquals(2, attachmentsDirectory.listFiles()?.size, "The two uploads did not land.")
            assertEquals(
                HttpStatusCode.OK,
                client.delete(ApiRoutes.forumPost(f.projectId, f.forumId, draft.id)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                }.status,
            )
            assertEquals(
                0,
                attachmentsDirectory.listFiles()?.size,
                "Deleting a post left its files on the volume with nothing able to name them.",
            )
        }
    }

    // ── Mentions ─────────────────────────────────────────────────────────────

    /**
     * The `@` autocomplete offers everyone who can **see** the project, not
     * everyone who holds a role in it.
     *
     * The deliberate difference from the issue editor, which offers the narrower
     * "anyone involved with this project". LNL-30 chose visibility as the forum's
     * rule, and the case that separates the two is a *public* project, where the
     * set is every account on the instance — including one that holds nothing
     * anywhere.
     */
    @Test
    fun `mentionable users in a public forum are everyone with an account`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.publicProjectId, "Town square", null)
        val draftId = forumPosts.createPostDraft(forum.id, Author.Account(f.memberId))
        forumPosts.publishPost(forumPosts.findPost(draftId)!!, "Who is here", "Anybody?")

        withRoutes { client ->
            val detail: ForumPostDetail =
                client.get(ApiRoutes.forumPost(f.publicProjectId, forum.id, draftId)) {
                    // The outsider holds no role in any project, and can see this
                    // one only because it is public. They must still be offered
                    // everybody, and must appear in everybody else's list.
                    cookie(SESSION_COOKIE, f.outsiderCookie)
                }.body()
            assertTrue(
                detail.mentionableUsers.map { it.name }.containsAll(listOf("Sys", "Pat", "Mem", "Out")),
                "A public forum's autocomplete omitted accounts that can plainly read it.",
            )
        }
    }

    /** ...and a private project's is its members plus the system administrator. */
    @Test
    fun `mentionable users in a private forum are its members`(): Unit = runBlocking {
        val f = seed()
        val post = publishedPost(f, "Members only")
        withRoutes { client ->
            val detail: ForumPostDetail = client.get(ApiRoutes.forumPost(f.projectId, f.forumId, post)) {
                cookie(SESSION_COOKIE, f.memberCookie)
            }.body()
            val names = detail.mentionableUsers.map { it.name }.toSet()
            assertEquals(setOf("Sys", "Pat", "Mem", "Mo"), names, "A private forum's autocomplete was wrong.")
        }
    }

    /**
     * Renaming somebody rewrites the mentions of them in posts and comments.
     *
     * Forums are the fourth place markdown is stored, and the one added last. A
     * renamer that covered the first three would leave the discussion side as the
     * one place an old name survived — mentions that still look like mentions and
     * quietly notify nobody, which is the exact failure MentionRenamer exists to
     * prevent.
     */
    @Test
    fun `renaming a user rewrites mentions in forum posts and comments`(): Unit = runBlocking {
        val f = seed()
        val postId = forumPosts.createPostDraft(f.forumId, Author.Account(f.memberId))
        forumPosts.publishPost(forumPosts.findPost(postId)!!, "Ping", "Over to @Pat on this.")
        val commentId = forumPosts.createCommentDraft(postId, Author.Account(f.memberId))
        forumPosts.publishComment(forumPosts.findComment(commentId)!!, "Agreed with @Pat here.")

        users.setDisplayName(f.projectAdminId, "Patricia")
        MentionRenamer(users, issues, comments, forumPostStore, forumCommentStore, messages)
            .rename(f.projectAdminId, "Pat", "Patricia")

        assertEquals("Over to @Patricia on this.", forumPosts.findPost(postId)?.body)
        assertEquals("Agreed with @Patricia here.", forumPosts.findComment(commentId)?.body)
    }

    /**
     * The post list names who last replied, and when — not who posted, and not the
     * first reply.
     *
     * LNL-62's post card ends in a column saying whether a thread is still moving,
     * and it is answered by three correlated subqueries over the same table, each
     * of which has to pick the *same* row. The failure is silent and plausible: a
     * missing `ORDER BY` gives whichever comment SQLite reached first, which is the
     * oldest one, so a busy thread would show a name and a date from months ago and
     * nothing would look broken. Two comments by two different people is the
     * smallest case that catches it — with one comment, or one author, every wrong
     * answer is also the right one.
     *
     * The empty case is pinned in the same test rather than a second one, because
     * the two halves are the same claim: the column is a fact about a comment, so
     * with no comments there is nothing to say and both fields are null. A build
     * that defaulted them to the post's own author and date would render every
     * quiet thread as though somebody had just replied to it.
     */
    @Test
    fun `the post list names the last commenter and when they wrote`(): Unit = runBlocking {
        val f = seed()
        val post = publishedPost(f, "Busy thread")
        val quiet = publishedPost(f, "Nobody replied")
        // By the member first, then by the admin — so "the newest" and "the first"
        // are different rows with different authors.
        publishedComment(f, post, "First.")
        withRoutes { client ->
            val lastId = forumPosts.createCommentDraft(post, Author.Account(f.projectAdminId))
            forumPosts.publishComment(forumPosts.findComment(lastId)!!, "Last.")

            val listed: ForumPostListState =
                client.get(ApiRoutes.forumPosts(f.projectId, f.forumId)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                }.body()

            val busy = listed.posts.single { it.id == post }
            assertEquals(2L, busy.commentCount)
            assertEquals("Pat", busy.lastCommentAuthor, "The card named the wrong comment's author.")
            assertEquals(
                forumPosts.findComment(lastId)!!.createdAt,
                busy.lastCommentAt,
                "The card's date did not come from the comment it named.",
            )

            val silent = listed.posts.single { it.id == quiet }
            assertEquals(0L, silent.commentCount)
            assertEquals(null, silent.lastCommentAuthor, "A thread with no replies claimed one.")
            assertEquals(null, silent.lastCommentAt, "A thread with no replies claimed a date.")
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(
        val adminCookie: String,
        val memberCookie: String,
        val secondMemberCookie: String,
        val outsiderCookie: String,
        val memberId: Long,
        val projectAdminId: Long,
        val projectId: Long,
        val publicProjectId: Long,
        val forumId: Long,
    )

    private suspend fun seed(): Fixture {
        roles.seed()
        val sysAdmin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-sys", "Sys", "sys@example.com"))
        val projectAdmin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-pa", "Pat", "pat@example.com"))
        val member = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-mem", "Mem", "mem@example.com"))
        // A second ordinary member: the "third party" in every deletion test. It
        // has to be somebody who CAN see the project, or the refusal under test
        // would be the visibility one rather than the authorship one.
        val other = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-mo", "Mo", "mo@example.com"))
        val outsider = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-out", "Out", "out@example.com"))
        assertFalse(projectAdmin.isSysAdmin, "The fixture's project administrator is a system one.")

        val project = projectRepository.create("Lunamux", "LMX", isPublic = false)
        val public = projectRepository.create("Open", "OPN", isPublic = true)
        roles.grant(projectAdmin.id, project.id, Role.PROJECT_ADMIN)
        roles.grant(member.id, project.id, Role.VIEW_PROJECT)
        roles.grant(other.id, project.id, Role.VIEW_PROJECT)
        // ...and deliberately nothing for the outsider anywhere, so the only thing
        // they can see is the public project.

        val forum = forums.create(project.id, "General", null)

        return Fixture(
            adminCookie = sessions.create(projectAdmin.id),
            memberCookie = sessions.create(member.id),
            secondMemberCookie = sessions.create(other.id),
            outsiderCookie = sessions.create(outsider.id),
            memberId = member.id,
            projectAdminId = projectAdmin.id,
            projectId = project.id,
            publicProjectId = public.id,
            forumId = forum.id,
        )
    }

    /**
     * A published post by the ordinary member, written through the repository.
     *
     * Through the repository rather than the routes, deliberately: these are
     * *fixtures* for the tests below, and driving four HTTP calls to set one up
     * would make an unrelated route's failure look like this test's.
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

/**
 * The smallest thing a browser will call a PNG.
 *
 * Real bytes rather than a placeholder string, because the upload route reads the
 * declared type and the size and the body has to survive both.
 */
private val PNG_BYTES = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
)
