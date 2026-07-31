/**
 * Per-project visibility: what "private" narrowed to, and where it must hold.
 *
 * Until LNL-57, `private` meant "requires any signed-in account" — every account
 * on the instance could read every project. [AccessControl.canReadProject] now
 * asks for membership instead, and membership is "holds any role in this
 * project". The interesting failures are all of the same kind: a read path that
 * forgot to ask.
 *
 *  - **Every read path, not the obvious ones.** The board, one issue, its
 *    comments, the attachment *bytes*, the project list, and the MCP twins of all
 *    of those. A check that exists in six places is missing from the seventh, and
 *    the seventh here is whichever one nobody thought of — historically the
 *    attachment stream, which is why AttachmentTest has guarded it since before
 *    this change and why it is asserted again below.
 *  - **Membership is "reaches any rung", not "is exactly a Viewer".** The narrower
 *    reading is the one an implementation drifts towards, and it would reintroduce
 *    exactly the incoherence this change fixes: somebody seated as a Contributor on
 *    a private project who cannot see the project they may file in. Asserted
 *    directly, because nothing else in the suite would catch it — every other
 *    fixture seats its caller at the bottom rung too.
 *  - **The refusal is 404, never 403.** A 403 confirms that a project by that id
 *    exists, which is the thing being withheld. Asserted by status code rather
 *    than by "it failed".
 *  - **Public projects did not move.** The whole risk of narrowing a read rule is
 *    that it narrows further than intended, and the caller who would notice first
 *    is the signed-out one who has no session to fall back on.
 *  - **An instance administrator holds no rows.** Reaching [InstanceRole.ADMIN]
 *    short-circuits [AccessControl.effectiveRole] to [ProjectRole.OWNER], here as
 *    everywhere else; a build that made administrators grant themselves a row
 *    instead would lock whoever runs the instance out of a project on the day they
 *    made it private.
 *
 * Through the real routes with real session cookies, for ProjectAdminTest's
 * reason: a test against [AccessControl] alone would pass on a route that never
 * called it, and every claim here is about a route.
 *
 * @see AccessControl.canReadProject
 * @see ProjectRole.VIEWER
 * @see RoleStore.roleFor
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.BoardState
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.ProjectListState
import se.soderbjorn.lunicle.clientserver.RungGrant
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import se.soderbjorn.lunicle.store.InstanceSettings

class ProjectVisibilityTest {
    private val file: File = Files.createTempFile("lunicle-visibility", ".db").toFile().also { it.delete() }
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
    // Agent access is permitted per tier and defaults to off (LNL-192). These files
    // are about what an agent may *do*, not about who may bring one, so both tiers are
    // permitted here and the user's own switch stays the interesting half.
    private val instanceSettings = InMemoryInstanceSettingsStore(
        InstanceSettings(staffMayUseAgents = true, memberMayUseAgents = true),
    )
    private val access = AccessControl(roles, instanceSettings)

    private val clients = OAuthClientStore(database)
    private val loginStates = OAuthLoginStateStore(database)
    private val codes = OAuthCodeStore(database)
    private val tokens = OAuthTokenStore(database)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── The private project, from outside ────────────────────────────────────

    /**
     * The list, which is the one read that must *omit* rather than refuse.
     *
     * Every other path below answers 404 for a project it will not show. This one
     * has no id to refuse — it is asked "what is there?" — so the withholding has
     * to happen in the filter, and a build that dropped it would leak every
     * private project's name and prefix to every account on the instance.
     */
    @Test
    fun `the project list omits a private project the caller holds nothing in`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val listed: ProjectListState =
                client.get(ApiRoutes.PROJECTS) { cookie(SESSION_COOKIE, f.outsiderCookie) }.body()
            assertTrue(
                listed.projects.none { it.id == f.privateId },
                "A private project was listed to a non-member.",
            )
            assertTrue(listed.projects.any { it.id == f.publicId }, "The public project vanished from the list.")
        }
    }

    /** The board itself — 404, so the refusal does not confirm the project exists. */
    @Test
    fun `the board of a private project is 404 to a non-member`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val response = client.get(ApiRoutes.board(f.privateId)) { cookie(SESSION_COOKIE, f.outsiderCookie) }
            assertEquals(
                HttpStatusCode.NotFound,
                response.status,
                "A 403 here would confirm that a private project with this id exists.",
            )
        }
    }

    /** One issue, reached by its own id rather than through its board. */
    @Test
    fun `an issue in a private project is 404 to a non-member`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val response = client.get(ApiRoutes.issue(f.privateIssueId)) { cookie(SESSION_COOKIE, f.outsiderCookie) }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    /**
     * The attachment bytes, which are the path most easily forgotten.
     *
     * The row, the file and the project are three different things, and the only
     * one that knows about permissions is the third. AttachmentTest guards the
     * same line for its own reasons; this asserts it again for the new rule,
     * because "the check is present" and "the check now says no to this caller"
     * are different claims.
     */
    @Test
    fun `attachment bytes in a private project are 404 to a non-member`(): Unit = runBlocking {
        val f = seed()
        val stored = attachments.storeForIssue(
            issueId = f.privateIssueId,
            filename = "secret.txt",
            declaredMimeType = "text/plain",
            bytes = "not for you".toByteArray(),
            author = Author.Account(f.sysAdminId),
        )

        withRoutes { client ->
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.attachment(stored.publicId)) {
                    cookie(SESSION_COOKIE, f.outsiderCookie)
                }.status,
                "A non-member downloaded a private project's file.",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get(ApiRoutes.attachment(stored.publicId)) {
                    cookie(SESSION_COOKIE, f.memberCookie)
                }.status,
                "A member could not download a file from their own project.",
            )
        }
    }

    /**
     * And the MCP twin of the list, which hides a private project for the same reason.
     *
     * ── It is no longer the *same* filter, and the control had to move ──────
     *
     * This used to read "a second implementation of the same filter", with the public
     * project as its control: the outsider holds nothing anywhere, so Lunamux appearing
     * proved the list was filtering rather than simply empty. The agent floor makes that
     * control wrong — Lunamux admits guests as Viewers, which is below
     * [AGENT_PROJECT_FLOOR], so an agent correctly does not see it either. Left as it
     * was, this test would have failed for the right behaviour.
     *
     * So the control is a rung the outsider actually holds. What the two lists still
     * share is the claim this file is about: **a private project you hold nothing in is
     * absent, not refused**. Where they now differ — a Viewer-level or public board being
     * absent from the agent's list and present in the browser's — is
     * [McpAgentFloorTest]'s subject, and is deliberately not restated here.
     */
    @Test
    fun `MCP list_projects omits a private project the caller holds nothing in`(): Unit = runBlocking {
        val f = seed()
        // The control: a rung that clears the agent floor, so a correct build has
        // something to put in the list and an over-eager one has something to drop.
        roles.setRole(f.outsiderId, f.publicId, ProjectRole.CONTRIBUTOR)
        withMcp { client ->
            val result = client.callTool(tokenFor(f.outsiderId), "list_projects", "{}")
            assertFalse(result.isError, result.text)
            assertFalse(result.text.contains("Skunkworks"), "MCP listed a private project to a non-member.")
            assertTrue(result.text.contains("Lunamux"), "MCP dropped a project the caller contributes to.")
        }
    }

    // ── The private project, from inside ─────────────────────────────────────

    /** [ProjectRole.VIEWER] on its own is enough, which is the whole reason it exists. */
    @Test
    fun `a member holding only view_project reads the board`(): Unit = runBlocking {
        val f = seed()
        roles.setRole(f.outsiderId, f.privateId, ProjectRole.VIEWER)
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.get(ApiRoutes.board(f.privateId)) { cookie(SESSION_COOKIE, f.outsiderCookie) }.status,
                "The bottom rung did not grant the one thing it is for.",
            )
        }
    }

    /**
     * And a rung above the bottom is enough too — the claim that separates
     * "membership" from "is exactly a Viewer".
     *
     * Somebody seated as a Contributor on a private project and nothing else could
     * not see the project they were meant to file in. That incoherence predates
     * this change; phrasing membership as "reaches any rung here" is what retires
     * it, and this is the only test in the suite that would notice a build which
     * narrowed the rule to the bottom rung instead.
     */
    @Test
    fun `a member holding only an issue-scoped role reads the board`(): Unit = runBlocking {
        val f = seed()
        roles.setRole(f.outsiderId, f.privateId, ProjectRole.CONTRIBUTOR)
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.get(ApiRoutes.board(f.privateId)) { cookie(SESSION_COOKIE, f.outsiderCookie) }.status,
                "Membership was read as \"is exactly a Viewer\" rather than \"reaches any rung\".",
            )
        }
    }

    /** The instance administrator, who holds no row anywhere and reads everything anyway. */
    @Test
    fun `a system administrator reads a private project without holding a role`(): Unit = runBlocking {
        val f = seed()
        assertFalse(
            (roles.roleFor(f.sysAdminId, f.privateId) != null),
            "The fixture's instance administrator holds a row, so this test proves nothing.",
        )
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.get(ApiRoutes.board(f.privateId)) { cookie(SESSION_COOKIE, f.sysAdminCookie) }.status,
            )
        }
    }

    // ── The public project, which must not have moved ────────────────────────

    /**
     * A signed-out visitor still reads a public board.
     *
     * The caller with the most to lose from a rule that narrowed too far: they
     * have no session, so there is no membership they could possibly hold and no
     * fallback for the rule to find.
     */
    @Test
    fun `a signed-out visitor still reads a public project`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            assertEquals(HttpStatusCode.OK, client.get(ApiRoutes.board(f.publicId)).status)
            val listed: ProjectListState = client.get(ApiRoutes.PROJECTS).body()
            assertTrue(
                listed.projects.any { it.id == f.publicId },
                "A public project vanished for a signed-out visitor.",
            )
            assertTrue(listed.projects.none { it.id == f.privateId })
        }
    }

    /**
     * A signed-out visitor reads a public board and **writes nothing on it** (LNL-202).
     *
     * The other half of the test above, and the half that was missing. `is_public` could
     * only say "may look", but a guest audience row names a rung — and nothing capped which
     * rung, so an owner could set Guests → Contributor and this route would file an issue
     * for a caller with no session at all. The row is capped at Viewer now (see
     * [Audience.GUEST]), and both refusals are asserted **with no cookie on the request**,
     * which is the only way to make this claim: a browser tab you have signed into cannot.
     *
     * The board's own affordances are asserted alongside, because a screen offering the
     * buttons beside a route refusing them is the same bug wearing a 403.
     */
    @Test
    fun `a signed-out visitor cannot file, comment or be assigned on a public project`(): Unit = runBlocking {
        val f = seed()
        val issueId = publicIssue(f)
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("${ApiRoutes.PROJECTS}/${f.publicId}/issues").status,
                "A caller with no session filed an issue on a public board.",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/issues/$issueId/comments").status,
                "A caller with no session commented on a public board.",
            )
            val board: BoardState = client.get(ApiRoutes.board(f.publicId)).body()
            assertFalse(board.permissions.canCreateIssue, "The board invited a stranger to file an issue.")
            assertFalse(board.permissions.canComment, "The board invited a stranger to comment.")
            assertFalse(board.permissions.canBeAssigned, "The board offered a stranger the work.")
        }
    }

    /**
     * The same, with the guest row **forced above Viewer in the store** — the row a fresh
     * UI can no longer produce (LNL-202).
     *
     * Written with `setAudienceRole` rather than through the audience route, because that
     * route refuses it now. This is the row a hand-edit leaves behind, or one a build older
     * than the cap wrote, and it is the case that tells a capped write from a capped
     * *read*: with only the former, this test files an issue.
     */
    @Test
    fun `a guest row forced to contributor still lets a stranger do nothing but read`(): Unit = runBlocking {
        val f = seed()
        val issueId = publicIssue(f)
        roles.setAudienceRole(f.publicId, Audience.GUEST, ProjectRole.CONTRIBUTOR)
        assertEquals(
            ProjectRole.CONTRIBUTOR,
            roles.audienceRoles(f.publicId)[Audience.GUEST],
            "Precondition: the store was meant to hold the invalid row verbatim.",
        )

        withRoutes { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.get(ApiRoutes.board(f.publicId)).status,
                "Capping the row must not stop a published board being read.",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("${ApiRoutes.PROJECTS}/${f.publicId}/issues").status,
                "A stored `guest -> contributor` row let a caller with no session file an issue.",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/issues/$issueId/comments").status,
                "A stored `guest -> contributor` row let a caller with no session comment.",
            )
            val board: BoardState = client.get(ApiRoutes.board(f.publicId)).body()
            assertFalse(board.permissions.canCreateIssue)
            assertFalse(board.permissions.canComment)
            assertFalse(board.permissions.canBeAssigned)
        }
    }

    /** An issue on the public board, so the comment route has something to refuse. */
    private suspend fun publicIssue(f: Fixture): Long {
        val draft = issueRepository.createDraft(f.publicId, Author.Account(f.sysAdminId))
        issueRepository.save(
            issue = issues.findById(draft.first)!!,
            title = "Visible to everybody",
            description = "",
            statusId = statuses.forProject(f.publicId).first().id,
            priorityId = priorities.defaultForProject(f.publicId)!!.id,
            resolutionId = null,
            assigneeId = null,
            sprintId = null,
            plannedVersionId = null,
            fixedVersionId = null,
            labelIds = emptyList(),
            componentIds = emptyList(),
        )
        return draft.first
    }

    /** And a signed-in non-member reads it too — the guest audience row did not narrow. */
    @Test
    fun `a public project is readable by a signed-in non-member`(): Unit = runBlocking {
        val f = seed()
        assertFalse((roles.roleFor(f.outsiderId, f.publicId) != null), "The fixture's outsider is a member of the public one.")
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.get(ApiRoutes.board(f.publicId)) { cookie(SESSION_COOKIE, f.outsiderCookie) }.status,
            )
        }
    }

    // ── The middle tier: visible to all signed-in users (LNL-138) ────────────

    /**
     * A signed-in non-member reads a board turned on for all signed-in users.
     *
     * The whole feature in one assertion: the outsider holds no role here, so under
     * the old rule this would be a 404, and the new tier is what turns it into a 200.
     */
    @Test
    fun `a signed-in non-member reads a project visible to all signed-in users`(): Unit = runBlocking {
        val f = seed()
        assertFalse(
            (roles.roleFor(f.outsiderId, f.signedInVisibleId) != null),
            "The fixture's outsider holds a role on the signed-in-visible project.",
        )
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.get(ApiRoutes.board(f.signedInVisibleId)) { cookie(SESSION_COOKIE, f.outsiderCookie) }.status,
                "The signed-in-visibility tier did not admit a signed-in account.",
            )
        }
    }

    /** And it is listed to that non-member, unlike a private project. */
    @Test
    fun `a project visible to all signed-in users is listed to a signed-in non-member`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val listed: ProjectListState =
                client.get(ApiRoutes.PROJECTS) { cookie(SESSION_COOKIE, f.outsiderCookie) }.body()
            assertTrue(
                listed.projects.any { it.id == f.signedInVisibleId },
                "A signed-in-visible project was hidden from a signed-in non-member.",
            )
        }
    }

    /**
     * But a signed-out visitor gets neither the board nor a listing.
     *
     * The tier's edge: it says yes to every account and no to a stranger. A build
     * that widened it one step too far — treating it as public — would leak it to
     * the anonymous web, which is the one thing "signed-in" is meant to prevent.
     */
    @Test
    fun `a project visible to all signed-in users is 404 and unlisted to a signed-out visitor`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.board(f.signedInVisibleId)).status,
                "A signed-in-only project answered a signed-out visitor.",
            )
            val listed: ProjectListState = client.get(ApiRoutes.PROJECTS).body()
            assertTrue(
                listed.projects.none { it.id == f.signedInVisibleId },
                "A signed-in-only project leaked into a signed-out visitor's list.",
            )
        }
    }

    /**
     * The read-only guarantee: a `member → viewer` audience grants reading and
     * nothing else.
     *
     * Asserted against [AccessControl] directly rather than through a route, because
     * the claim is precisely about the rung: the audience row admits every account at
     * [ProjectRole.VIEWER], and every write in this codebase asks for
     * [ProjectRole.CONTRIBUTOR] or above. That is the ticket's "browse … but not make
     * changes", and it is now structural — there is no flag to forget to exclude from
     * a write gate, because a viewer simply does not reach the rung a write asks for.
     *
     * This test used to end on `canPostInProject`, which is false for everybody since
     * discussions were retired; the claim is re-pointed at issue writing rather than
     * dropped, because it is the same claim about the same tier.
     */
    @Test
    fun `an audience admitted as viewer may read and may not write`(): Unit = runBlocking {
        val f = seed()
        val project = projects.findById(f.signedInVisibleId)!!
        val outsider = users.findById(f.outsiderId)!!
        assertTrue(
            access.canReadProject(outsider, project),
            "A signed-in account could not read a project its member audience admits.",
        )
        assertEquals(
            ProjectRole.VIEWER,
            access.effectiveRole(outsider, project.id),
            "The audience row put them on a rung other than the one it names.",
        )
        assertFalse(
            access.canCreateIssue(outsider, project.id),
            "A viewer filed an issue — the audience grants reading only.",
        )
        assertFalse(
            access.canComment(outsider, project.id),
            "A viewer commented — the audience grants reading only.",
        )
    }

    // ── Granting it ──────────────────────────────────────────────────────────

    /**
     * A project administrator hands out visibility, and takes it back.
     *
     * Through the grant route rather than [RoleStore.grant], because the point of
     * reusing `project_roles` was to inherit the existing dialog, wire format and
     * tiering rather than build a second grant surface. If the new role needed
     * anything of its own, this is where that would show.
     */
    @Test
    fun `a project administrator grants and revokes visibility`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val granted = client.post(ApiRoutes.projectRoles(f.privateId)) {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
                contentType(ContentType.Application.Json)
                setBody(RungGrant(userId = f.outsiderId, roleKey = ProjectRole.VIEWER.key))
            }
            assertEquals(HttpStatusCode.OK, granted.status, "A project administrator could not grant visibility.")
            assertTrue((roles.roleFor(f.outsiderId, f.privateId) != null))

            val revoked = client.post(ApiRoutes.projectRoles(f.privateId)) {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
                contentType(ContentType.Application.Json)
                setBody(RungGrant(userId = f.outsiderId, roleKey = null))
            }
            assertEquals(HttpStatusCode.OK, revoked.status)
            assertFalse((roles.roleFor(f.outsiderId, f.privateId) != null), "Revoking visibility left the caller a member.")
        }
    }

    /**
     * And the settings dialog is offered the new role without being told about it.
     *
     * The rung menu is built from `ProjectRole.entries`, so a rung added to the enum
     * appears there for free — that inheritance was the argument for reusing
     * `project_roles` over a `project_members` table, and it is worth one assertion that
     * it actually happened.
     */
    @Test
    fun `the access section offers the rung that decides who sees the project`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val state: ProjectSettingsState = client.get(ApiRoutes.projectSettings(f.privateId)) {
                cookie(SESSION_COOKIE, f.projectAdminCookie)
            }.body()
            assertTrue(
                state.access?.rungs.orEmpty().any { it.key == ProjectRole.VIEWER.key },
                "The rung menu has no row for the rung that decides who sees the project.",
            )
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(
        val sysAdminId: Long,
        val sysAdminCookie: String,
        val projectAdminCookie: String,
        val memberCookie: String,
        val outsiderId: Long,
        val outsiderCookie: String,
        val publicId: Long,
        val privateId: Long,
        val privateIssueId: Long,
        val signedInVisibleId: Long,
    )

    private suspend fun seed(): Fixture {
        val sysAdmin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-sys", "Sys", "sys@example.com"))
        val projectAdmin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-pa", "Pat", "pat@example.com"))
        val member = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-mem", "Mem", "mem@example.com"))
        val outsider = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-out", "Out", "out@example.com"))
        assertTrue(sysAdmin.isInstanceAdmin, "The first account is meant to be the instance administrator.")
        assertFalse(outsider.isInstanceAdmin, "The fixture's outsider runs the instance.")

        val public = projectRepository.createOpenToAll("Lunamux", "LMX", roles, instanceSettings)
        val private = projectRepository.create("Skunkworks", "SKW")
        // The middle read tier (LNL-138): readable by any signed-in account, no
        // membership granted on it — the outsider holds nothing here, which is what
        // the tier tests below turn on.
        // The middle tier is a `member → viewer` audience row now (LNL-191), where it
        // was the `visible_to_all_signed_in` column: readable by any account, no
        // membership granted on it. The outsider holds nothing here, which is what the
        // tier tests below turn on.
        val signedInVisible = projectRepository.create("Browsable", "BRW")
            .also { roles.setAudienceRole(it.id, Audience.MEMBER, ProjectRole.VIEWER) }
        roles.setRole(projectAdmin.id, private.id, ProjectRole.ADMIN)
        roles.setRole(member.id, private.id, ProjectRole.VIEWER)
        // Deliberately no grant for the outsider anywhere, and none for the instance
        // administrator: both of those absences are what tests above assert against.

        val issue = issueRepository.createDraft(private.id, Author.Account(sysAdmin.id))
        issueRepository.save(
            issue = issues.findById(issue.first)!!,
            title = "Something private",
            description = "",
            statusId = statuses.forProject(private.id).first().id,
            priorityId = priorities.defaultForProject(private.id)!!.id,
            resolutionId = null,
            assigneeId = null,
            sprintId = null,
            plannedVersionId = null,
            fixedVersionId = null,
            labelIds = emptyList(),
            componentIds = emptyList(),
        )

        // Production seats the instance owner at boot (see InstanceLadder.kt), and
        // four rules — creating and managing projects, backfilling authorship, agent
        // mail, out-of-band attachment deletes — are the owner's alone rather than an
        // administrator's. A fixture that skipped this would be testing an instance
        // nobody runs: one with an administrator and no owner.
        seatInstanceOwner(users, instanceSettings)
        return Fixture(
            sysAdminId = sysAdmin.id,
            sysAdminCookie = sessions.create(sysAdmin.id),
            projectAdminCookie = sessions.create(projectAdmin.id),
            memberCookie = sessions.create(member.id),
            outsiderId = outsider.id,
            outsiderCookie = sessions.create(outsider.id),
            publicId = public.id,
            privateId = private.id,
            privateIssueId = issue.first,
            signedInVisibleId = signedInVisible.id,
        )
    }

    private fun withRoutes(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing {
                boardRoutes(boardDependencies())
                projectSettingsRoutes(boardDependencies())
            }
        }
        block(createClient { install(ClientContentNegotiation) { json() } })
    }

    /** A real access token for [userId], with MCP enabled — see McpDeleteTest.tokenFor. */
    private suspend fun tokenFor(userId: Long): String {
        users.setMcpEnabled(userId, true)
        val client =
            clients.register("Test agent", listOf("http://localhost:1234/callback"), listOf("authorization_code"))
        return tokens.issueTokens(userId, client.clientId, "mcp", "http://localhost/mcp").accessToken
    }

    private class ToolOutcome(val text: String, val isError: Boolean)

    private suspend fun HttpClient.callTool(token: String, name: String, arguments: String): ToolOutcome {
        val response = post(MCP_PATH) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"$name","arguments":$arguments}}
                """.trimIndent(),
            )
        }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["error"] == null, "JSON-RPC error rather than a tool result: $body")
        val result = body["result"]!!.jsonObject
        val text = result["content"]!!.jsonArray
            .joinToString("\n") { (it as JsonObject)["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        return ToolOutcome(text, result["isError"]?.jsonPrimitive?.contentOrNull == "true")
    }

    private fun withMcp(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing { mcpRoutes(mcpDependencies(), McpTools(boardDependencies())) }
        }
        block(createClient { })
    }

    private fun mcpDependencies() = McpDependencies(
        clients = clients,
        loginStates = loginStates,
        codes = codes,
        tokens = tokens,
        sessions = sessions,
        users = users,
        config = OAuthConfig(google = null),
        instanceSettings = instanceSettings,
    )

    private fun boardDependencies() = BoardDependencies(
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
