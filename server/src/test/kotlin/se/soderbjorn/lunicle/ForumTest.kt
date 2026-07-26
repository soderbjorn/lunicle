/**
 * Forums: who may make one, who may only read them, and who may not see them.
 *
 * The forum routes are the first thing built on LNL-57's narrowed visibility, so
 * this file's claims come in two halves and the second is the one that would go
 * quietly wrong:
 *
 *  - **Managing is project-administrator-only, at the route.** The ticket is
 *    explicit that the routes must refuse rather than the client merely hiding a
 *    button, which is this repository's standing position — see AccessControl's
 *    preamble. Asserted through real routes with real session cookies, because a
 *    test against [AccessControl] alone would pass on a route that never called
 *    it.
 *  - **A reader who cannot see the project cannot reach its forums**, and gets a
 *    404 rather than a 403 — a 403 would confirm that a project by that id
 *    exists, which is the thing being withheld. This is the half with no
 *    backstop anywhere else: nothing about forums is in `AttachmentTest`'s
 *    guard, and a `get` that forgot [ApplicationCall.readableProject] would look
 *    perfect on a public project, which is what every dev machine has.
 *
 * Two smaller things are pinned because they are easy to get subtly wrong:
 *
 *  - **Reordering is checked as a set.** A list that omits a forum, repeats one,
 *    or names one from *another project* must be refused outright rather than
 *    partly applied — the last of those being how one project's administrator
 *    would otherwise reach another's rows.
 *  - **Renaming a forum to its own name is not a duplicate.** The obvious
 *    uniqueness check compares against the whole list including the row being
 *    edited, and then saving a description without touching the name is a
 *    refusal.
 *
 * Every request goes through the `ApiRoutes` builders rather than hand-written
 * strings, so a route pattern that drifts from the path the client will call
 * fails here rather than in a browser.
 *
 * @see forumRoutes
 * @see ForumRepository
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
import se.soderbjorn.lunicle.clientserver.ForumEdit
import se.soderbjorn.lunicle.clientserver.ForumListState
import se.soderbjorn.lunicle.clientserver.ForumOrder
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class ForumTest {
    private val file: File = Files.createTempFile("lunicle-forums", ".db").toFile().also { it.delete() }
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
    private val forumStore = ForumStore(database)
    private val forums = ForumRepository(forumStore, attachments, attachmentStore)
    private val access = AccessControl(roles)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── Managing ─────────────────────────────────────────────────────────────

    /** The whole lifecycle, through the routes an administrator's UI will call. */
    @Test
    fun `a project administrator creates, edits and deletes a forum`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val created: ForumListState = client.post(ApiRoutes.forums(f.projectId)) {
                cookie(SESSION_COOKIE, f.adminCookie)
                contentType(ContentType.Application.Json)
                setBody(ForumEdit(name = "General", description = "Anything at all."))
            }.body()
            assertEquals(listOf("General"), created.forums.map { it.name })
            assertTrue(created.canManageForums)

            val id = created.forums.single().id
            val edited: ForumListState = client.put(ApiRoutes.forum(f.projectId, id)) {
                cookie(SESSION_COOKIE, f.adminCookie)
                contentType(ContentType.Application.Json)
                setBody(ForumEdit(name = "General chat", description = null))
            }.body()
            assertEquals("General chat", edited.forums.single().name)
            assertEquals(null, edited.forums.single().description, "A cleared description came back as empty rather than absent.")

            val deleted: ForumListState = client.delete(ApiRoutes.forum(f.projectId, id)) {
                cookie(SESSION_COOKIE, f.adminCookie)
            }.body()
            assertEquals(emptyList(), deleted.forums)
        }
    }

    /**
     * Saving a forum without changing its name is not a duplicate.
     *
     * The obvious uniqueness check compares the new name against the whole list
     * including the row being edited, and then editing only the description is a
     * refusal. Nothing else in this file would catch it.
     */
    @Test
    fun `renaming a forum to the name it already has is allowed`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.projectId, "General", null)
        withRoutes { client ->
            val response = client.put(ApiRoutes.forum(f.projectId, forum.id)) {
                cookie(SESSION_COOKIE, f.adminCookie)
                contentType(ContentType.Application.Json)
                setBody(ForumEdit(name = "General", description = "Now with a description."))
            }
            assertEquals(HttpStatusCode.OK, response.status, "Editing a forum's description was read as a rename clash.")
        }
        assertEquals("Now with a description.", forums.findById(forum.id)?.description)
    }

    /** A genuine clash is refused, with a sentence rather than a stack trace. */
    @Test
    fun `a duplicate forum name is refused as a conflict`(): Unit = runBlocking {
        val f = seed()
        forums.create(f.projectId, "General", null)
        withRoutes { client ->
            val response = client.post(ApiRoutes.forums(f.projectId)) {
                cookie(SESSION_COOKIE, f.adminCookie)
                contentType(ContentType.Application.Json)
                // Different case, same name — the column is COLLATE NOCASE and the
                // rule has to agree with it, or the route 500s on a constraint.
                setBody(ForumEdit(name = "general", description = null))
            }
            assertEquals(HttpStatusCode.Conflict, response.status)
        }
        assertEquals(1, forums.forProject(f.projectId).size)
    }

    /** Two projects may both have a "General"; uniqueness is within a project. */
    @Test
    fun `the same forum name in two projects is fine`(): Unit = runBlocking {
        val f = seed()
        forums.create(f.projectId, "General", null)
        forums.create(f.otherProjectId, "General", null)
        assertEquals(1, forums.forProject(f.projectId).size)
        assertEquals(1, forums.forProject(f.otherProjectId).size)
    }

    // ── Ordering ─────────────────────────────────────────────────────────────

    /** New forums append, rather than landing wherever the id order puts them. */
    @Test
    fun `forums come back in the administrators order`(): Unit = runBlocking {
        val f = seed()
        val a = forums.create(f.projectId, "Announcements", null)
        val b = forums.create(f.projectId, "Development", null)
        val c = forums.create(f.projectId, "General", null)
        assertEquals(listOf(a.id, b.id, c.id), forums.forProject(f.projectId).map { it.id })

        withRoutes { client ->
            val reordered: ForumListState = client.post(ApiRoutes.forumOrder(f.projectId)) {
                cookie(SESSION_COOKIE, f.adminCookie)
                contentType(ContentType.Application.Json)
                setBody(ForumOrder(listOf(c.id, a.id, b.id)))
            }.body()
            assertEquals(listOf("General", "Announcements", "Development"), reordered.forums.map { it.name })
        }
    }

    /**
     * A reorder that does not name exactly this project's forums is refused
     * whole, and — the part with real teeth — one naming another project's forum
     * cannot reach it.
     */
    @Test
    fun `a reorder naming the wrong forums is refused whole`(): Unit = runBlocking {
        val f = seed()
        val a = forums.create(f.projectId, "Announcements", null)
        val b = forums.create(f.projectId, "Development", null)
        val elsewhere = forums.create(f.otherProjectId, "Somebody elses", null)

        withRoutes { client ->
            suspend fun order(vararg ids: Long) = client.post(ApiRoutes.forumOrder(f.projectId)) {
                cookie(SESSION_COOKIE, f.adminCookie)
                contentType(ContentType.Application.Json)
                setBody(ForumOrder(ids.toList()))
            }.status

            assertEquals(HttpStatusCode.Conflict, order(a.id), "A short order was accepted.")
            assertEquals(HttpStatusCode.Conflict, order(a.id, a.id), "A repeated id was accepted.")
            assertEquals(
                HttpStatusCode.Conflict,
                order(a.id, elsewhere.id),
                "An administrator reordered a forum in a project they do not administer.",
            )
        }
        assertEquals(listOf(a.id, b.id), forums.forProject(f.projectId).map { it.id }, "A refused reorder was partly applied.")
        assertEquals(0L, forums.findById(elsewhere.id)?.position, "Another project's forum was moved.")
    }

    // ── Reading, and being refused ───────────────────────────────────────────

    /** A member who does not administer the project sees the forums and no controls. */
    @Test
    fun `a non-administrator reads the forums but is refused every write`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.projectId, "General", null)

        withRoutes { client ->
            val state: ForumListState = client.get(ApiRoutes.forums(f.projectId)) {
                cookie(SESSION_COOKIE, f.memberCookie)
            }.body()
            assertEquals(listOf("General"), state.forums.map { it.name })
            assertFalse(state.canManageForums, "A non-administrator was told they may manage forums.")

            assertEquals(
                HttpStatusCode.Forbidden,
                client.post(ApiRoutes.forums(f.projectId)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ForumEdit(name = "Mine", description = null))
                }.status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.put(ApiRoutes.forum(f.projectId, forum.id)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ForumEdit(name = "Renamed", description = null))
                }.status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.delete(ApiRoutes.forum(f.projectId, forum.id)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                }.status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post(ApiRoutes.forumOrder(f.projectId)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ForumOrder(listOf(forum.id)))
                }.status,
            )
        }
        assertEquals(listOf("General"), forums.forProject(f.projectId).map { it.name }, "A refused write landed anyway.")
    }

    /**
     * Somebody who cannot see the project cannot see, or reach, its forums — and
     * the refusal is 404 rather than 403.
     *
     * The claim with no backstop elsewhere. A `get` that forgot `readableProject`
     * would look perfect on a public project, which is what every dev machine
     * has one of.
     */
    @Test
    fun `a non-member cannot read or reach a private projects forums`(): Unit = runBlocking {
        val f = seed()
        val forum = forums.create(f.projectId, "General", null)

        withRoutes { client ->
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.forums(f.projectId)) { cookie(SESSION_COOKIE, f.outsiderCookie) }.status,
                "A 403 here would confirm that a private project with this id exists.",
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.put(ApiRoutes.forum(f.projectId, forum.id)) {
                    cookie(SESSION_COOKIE, f.outsiderCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ForumEdit(name = "Mine now", description = null))
                }.status,
            )
        }
        assertEquals("General", forums.findById(forum.id)?.name)
    }

    /** A public project's forums are readable by a signed-out visitor, with no controls. */
    @Test
    fun `a signed-out visitor reads a public projects forums`(): Unit = runBlocking {
        val f = seed()
        forums.create(f.publicProjectId, "Open house", null)
        withRoutes { client ->
            val state: ForumListState = client.get(ApiRoutes.forums(f.publicProjectId)).body()
            assertEquals(listOf("Open house"), state.forums.map { it.name })
            assertFalse(state.canManageForums)
        }
    }

    /** Naming another project's forum under this project's id is a 404, not an edit. */
    @Test
    fun `a forum from another project cannot be edited through this projects path`(): Unit = runBlocking {
        val f = seed()
        val elsewhere = forums.create(f.otherProjectId, "Theirs", null)
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.NotFound,
                client.put(ApiRoutes.forum(f.projectId, elsewhere.id)) {
                    cookie(SESSION_COOKIE, f.adminCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ForumEdit(name = "Stolen", description = null))
                }.status,
            )
        }
        assertEquals("Theirs", forums.findById(elsewhere.id)?.name)
    }

    /** Deleting the project takes its forums with it — the cascade in Forums.sq. */
    @Test
    fun `deleting a project deletes its forums`(): Unit = runBlocking {
        val f = seed()
        forums.create(f.projectId, "General", null)
        projectRepository.delete(f.projectId)
        assertEquals(emptyList(), forums.forProject(f.projectId))
    }

    // ── Names ────────────────────────────────────────────────────────────────

    /** Blank is refused, and a blank description is stored as absent rather than empty. */
    @Test
    fun `a blank name is refused and a blank description becomes null`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.Conflict,
                client.post(ApiRoutes.forums(f.projectId)) {
                    cookie(SESSION_COOKIE, f.adminCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ForumEdit(name = "   ", description = null))
                }.status,
            )
        }
        val forum = forums.create(f.projectId, "  Padded  ", "   ")
        assertEquals("Padded", forum.name, "The name was not trimmed.")
        assertEquals(null, forum.description, "A blank description was stored as empty rather than absent.")
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(
        val adminCookie: String,
        val memberCookie: String,
        val outsiderCookie: String,
        val projectId: Long,
        val otherProjectId: Long,
        val publicProjectId: Long,
    )

    private suspend fun seed(): Fixture {
        roles.seed()
        val sysAdmin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-sys", "Sys", "sys@example.com"))
        val projectAdmin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-pa", "Pat", "pat@example.com"))
        val member = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-mem", "Mem", "mem@example.com"))
        val outsider = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-out", "Out", "out@example.com"))
        assertFalse(projectAdmin.isSysAdmin, "The fixture's project administrator is a system one.")

        val project = projectRepository.create("Lunamux", "LMX", isPublic = false)
        val other = projectRepository.create("Elsewhere", "ELS", isPublic = false)
        val public = projectRepository.create("Open", "OPN", isPublic = true)
        roles.grant(projectAdmin.id, project.id, Role.PROJECT_ADMIN)
        roles.grant(projectAdmin.id, other.id, Role.PROJECT_ADMIN)
        // Bare visibility, so the member's refusals below are about administering
        // rather than about seeing. See Role.VIEW_PROJECT.
        roles.grant(member.id, project.id, Role.VIEW_PROJECT)
        // ...and deliberately nothing for the outsider anywhere.

        return Fixture(
            adminCookie = sessions.create(projectAdmin.id),
            memberCookie = sessions.create(member.id),
            outsiderCookie = sessions.create(outsider.id),
            projectId = project.id,
            otherProjectId = other.id,
            publicProjectId = public.id,
        )
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
