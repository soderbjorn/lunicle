/**
 * Impersonation through the real routes: who may, keyed on what, and leaving what
 * behind (LNL-197).
 *
 * Three properties, and each of them has already been shipped broken by somebody
 * reasoning about the code instead of running it:
 *
 *  - **The owner alone.** An instance *administrator* is refused on all three routes.
 *    That is the assertion the tightening is for: an administrator who could
 *    impersonate would make it pointless, and an administrator is the caller most
 *    likely to be left holding a stale affordance, because so many other
 *    instance-wide surfaces do open for them.
 *  - **Keyed on addresses.** The list is addresses with what each resolves to, and the
 *    route takes one — including an address with no account at all, which is a state
 *    no list of accounts can name and the reason the key changed.
 *  - **A previewed address leaves no trace.** No `users` row appears, no `added_at` is
 *    stamped, and it turns up in no project's People list. Asserted by counting the
 *    table before and after, because a green build cannot tell you this and the
 *    failure mode — a helpful `INSERT OR IGNORE` somewhere on the resolve path — would
 *    look completely fine from the outside.
 *
 * Full powers stay full, writes included, which is the other half of the trade: a
 * read-only preview cannot answer "could a stranger file this?", so the write test
 * below files an issue as an address that does not exist and checks it landed —
 * authored by nobody, because there is no row for `created_by` to point at.
 *
 * @see Impersonations
 * @see AccessControl.canImpersonate
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AddressPreview
import se.soderbjorn.lunicle.clientserver.AddressStanding
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.ImpersonateRequest
import se.soderbjorn.lunicle.clientserver.IssueDraft
import se.soderbjorn.lunicle.clientserver.ProjectListState
import se.soderbjorn.lunicle.clientserver.SessionState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class ImpersonationRoutesTest {
    private val file: File = Files.createTempFile("lunicle-impersonation", ".db").toFile().also { it.delete() }
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
    private val attachments = AttachmentRepository(attachmentStore, File(file.parentFile, "att-${file.name}"))
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
    private val issueRepository =
        IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)
    private val sprintRepository = SprintRepository(database, sprints, projects, issues, statuses)
    private val vocabularies =
        VocabularyRepository(database, labels, components, statuses, priorities, resolutions, sprints, versions, issues)
    private val instanceSettings = InstanceSettingsStore(database)
    private val access = AccessControl(roles, instanceSettings)

    /**
     * A deployment that names its own domain, because without one there is no staff
     * tier and half the states worth previewing collapse into "member". See
     * InstanceIdentity.domain.
     */
    private val identity = InstanceIdentity(domain = "acme.com", isGoogleAvailable = false)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── Who may ─────────────────────────────────────────────────────────────

    /** The owner is offered the menu, and every address on the instance is in it. */
    @Test
    fun `the owner is offered every address, with what each resolves to`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client ->
            val state = client.session(f.ownerCookie)

            assertTrue(state.canImpersonate, "The instance owner was not offered impersonation.")
            assertEquals(
                mapOf(
                    "owner@acme.com" to AddressStanding.SELF,
                    "admin@acme.com" to AddressStanding.STAFF,
                    "outsider@example.com" to AddressStanding.MEMBER,
                    "pending@acme.com" to AddressStanding.NOT_SIGNED_IN,
                ),
                state.impersonatableAddresses.associate { it.email to it.standing },
                "The menu is not a list of addresses and what they resolve to.",
            )
        }
    }

    /**
     * An instance **administrator** is refused, on all three routes and in the menu.
     *
     * The whole tightening, in one test. An administrator holds Owner on every project
     * and reads the account directory, so every other instance-wide surface opens for
     * them — which is exactly why the affordance and the enforcement both have to be
     * checked here rather than assumed to have moved together.
     */
    @Test
    fun `an instance administrator may not impersonate at all`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client ->
            val state = client.session(f.adminCookie)
            assertFalse(state.canImpersonate, "An administrator was offered the impersonation menu.")
            assertTrue(state.impersonatableAddresses.isEmpty(), "An administrator was handed the address list.")

            assertEquals(
                HttpStatusCode.Forbidden,
                client.impersonate(f.adminCookie, "outsider@example.com").status,
                "An administrator became somebody else by calling the route directly.",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.preview(f.adminCookie, "stranger@acme.com").status,
                "An administrator read an address resolution by calling the route directly.",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post(ApiRoutes.STOP_IMPERSONATING) { cookie(SESSION_COOKIE, f.adminCookie) }.status,
                "An administrator was let into the stop route.",
            )
        }
    }

    /**
     * Ownership taken away mid-session drops the impersonation, rather than merely
     * ignoring it.
     *
     * `resolveCaller` re-derives the entitlement on **every** request, which is the
     * property that stops authority being removable without removing what it granted.
     * The impersonation is started through the route — so this is not a test of a map,
     * it is a test that the route's own effect does not outlive the right to have
     * caused it.
     */
    @Test
    fun `ownership taken away mid-session ends the impersonation`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client ->
            client.impersonate(f.ownerCookie, "outsider@example.com")
            assertEquals(
                "Outsider",
                client.session(f.ownerCookie).user?.displayName,
                "The impersonation never took, so the interesting half cannot be tested.",
            )

            // Ownership transferred to somebody else. Nothing else about the session
            // changed, and nothing told the impersonation.
            instanceSettings.setOwnerUserId(f.adminId)

            val after = client.session(f.ownerCookie)
            assertEquals("Owner", after.user?.displayName, "A former owner was still wearing somebody's face.")
            assertFalse(after.canImpersonate, "A former owner was still offered the menu.")
            assertFalse(after.isImpersonating)
        }
    }

    // ── An address with no account ──────────────────────────────────────────

    /**
     * Previewing an address that has no row says so, and **writes nothing**.
     *
     * The single most important assertion in the ticket, and the one a green build
     * cannot make: the `users` count is taken before and after, and the address is
     * looked for by hand afterwards.
     */
    @Test
    fun `previewing an unknown address creates nothing`(): Unit = runBlocking {
        val f = seed()
        val before = users.selectAll().map { it.id }.toSet()

        withRoutes { client ->
            val preview: AddressPreview = client.preview(f.ownerCookie, "stranger@acme.com").body()
            assertEquals(AddressStanding.NO_ACCOUNT, preview.standing)
            assertTrue(
                preview.summary.contains("staff"),
                "A stranger at the deployment's own domain was not reported as arriving staff: ${preview.summary}",
            )
        }

        assertEquals(before, users.selectAll().map { it.id }.toSet(), "Previewing an address created an account.")
        assertNull(
            users.selectAll().firstOrNull { it.email == "stranger@acme.com" },
            "The previewed address turned up in the users table.",
        )
    }

    /**
     * The three states that are not accounts, each reported as itself.
     *
     * They are the reason `Any address…` exists: a stranger at the staff domain, an
     * outside address with no row, and — the one that *has* a row and still is not an
     * arrival — a member added and never signed in. A preview that collapsed any two of
     * them would look right and answer the wrong question.
     */
    @Test
    fun `the three states hardest to reach are told apart`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client ->
            val strangerAtDomain: AddressPreview = client.preview(f.ownerCookie, "stranger@acme.com").body()
            assertEquals(AddressStanding.NO_ACCOUNT, strangerAtDomain.standing)
            assertTrue(strangerAtDomain.summary.contains("staff"))

            val outsider: AddressPreview = client.preview(f.ownerCookie, "nobody@elsewhere.org").body()
            assertEquals(AddressStanding.NO_ACCOUNT, outsider.standing)
            assertTrue(
                outsider.summary.contains("a member"),
                "An outside address with no row was not reported as arriving a member: ${outsider.summary}",
            )

            val addedNeverArrived: AddressPreview = client.preview(f.ownerCookie, "pending@acme.com").body()
            assertEquals(AddressStanding.NOT_SIGNED_IN, addedNeverArrived.standing)
            assertTrue(
                addedNeverArrived.summary.contains("never signed in"),
                "A row that was added and never arrived read as an ordinary account: ${addedNeverArrived.summary}",
            )
        }
    }

    /**
     * Wearing an unknown staff address reaches what the `staff` audience opens, and a
     * member address does not.
     *
     * This is the test that says the previewed record's *kind* is live rather than
     * decorative: the two addresses differ only in their domain, and the board is
     * readable by staff alone. Nothing was granted to either address, which is the
     * whole point — it is the audience row doing the work.
     */
    @Test
    fun `an unknown address at the staff domain sees what staff see`(): Unit = runBlocking {
        val f = seed()
        roles.setAudienceRole(f.projectId, Audience.STAFF, ProjectRole.VIEWER)

        withRoutes { client ->
            client.impersonate(f.ownerCookie, "stranger@acme.com")
            val asStaff: ProjectListState = client.get(ApiRoutes.PROJECTS) { cookie(SESSION_COOKIE, f.ownerCookie) }
                .body()
            assertEquals(
                listOf("Lunamux"),
                asStaff.projects.map { it.name },
                "An unknown address at the staff domain could not see a board the staff audience opens.",
            )

            client.impersonate(f.ownerCookie, "nobody@elsewhere.org")
            val asMember: ProjectListState = client.get(ApiRoutes.PROJECTS) { cookie(SESSION_COOKIE, f.ownerCookie) }
                .body()
            assertTrue(
                asMember.projects.isEmpty(),
                "An outside address saw a staff-only board: ${asMember.projects.map { it.name }}",
            )
        }
    }

    /**
     * An impersonated unknown address can still **write**, and the write is authored by
     * nobody.
     *
     * Full powers is the point — "could a stranger file this?" is not a question a
     * read-only view can answer — and the authorship is the one concession the shape
     * forces: `issues.created_by` references `users`, there is no row to point at, and
     * the honest answer is the null the column already allows. Attributing it to the
     * impersonating owner would be a lie; refusing the write would be the read-only
     * preview this deliberately is not.
     */
    @Test
    fun `an impersonated unknown address may file an issue, authored by nobody`(): Unit = runBlocking {
        val f = seed()
        roles.setAudienceRole(f.projectId, Audience.STAFF, ProjectRole.CONTRIBUTOR)
        val before = users.selectAll().map { it.id }.toSet()

        var filedId = 0L
        withRoutes { client ->
            client.impersonate(f.ownerCookie, "stranger@acme.com")
            val filed = client.post("${ApiRoutes.PROJECTS}/${f.projectId}/issues") {
                cookie(SESSION_COOKIE, f.ownerCookie)
            }
            assertEquals(HttpStatusCode.OK, filed.status, "A stranger the audience admits as a contributor was refused.")
            filedId = filed.body<IssueDraft>().id
        }

        val issue = requireNotNull(issues.findById(filedId)) { "The route answered OK and wrote nothing." }
        assertEquals(
            Author.Nobody,
            issue.author,
            "The issue was attributed to somebody, and there is nobody it could honestly be.",
        )
        assertEquals(before, users.selectAll().map { it.id }.toSet(), "Filing as a previewed address created an account.")
    }

    /**
     * Stopping leaves nothing at all: no row, no `added_at`, no People entry.
     *
     * The ticket's headline promise, asserted the only way it can be — by looking at
     * the database rather than at the response. The project's grants are checked too,
     * because "appears in People" is a different table from "exists as an account" and
     * a preview that leaked into either would be a preview that changed the deployment.
     */
    @Test
    fun `stopping an unknown-address impersonation leaves no trace`(): Unit = runBlocking {
        val f = seed()
        val before = users.selectAll().map { it.id }.toSet()

        withRoutes { client ->
            client.impersonate(f.ownerCookie, "stranger@acme.com")
            val worn = client.session(f.ownerCookie)
            assertTrue(worn.isImpersonating, "The impersonation never took.")
            assertEquals("stranger@acme.com", worn.user?.displayName, "The worn address is not the one asked for.")
            assertFalse(worn.user?.isSysAdmin == true, "A previewed stranger arrived administering the instance.")
            assertTrue(worn.user?.isStaff == true, "A previewed address at the deployment's domain was not staff.")

            val stopped = client.post(ApiRoutes.STOP_IMPERSONATING) { cookie(SESSION_COOKIE, f.ownerCookie) }
                .body<SessionState>()
            assertFalse(stopped.isImpersonating, "Stopping did not stop.")
            assertEquals("Owner", stopped.user?.displayName)
        }

        assertEquals(before, users.selectAll().map { it.id }.toSet(), "An account survived the impersonation.")
        assertTrue(
            roles.rolesForProject(f.projectId).keys.none { it == PREVIEW_USER_ID },
            "The previewed address left a row in the project's People list.",
        )
    }

    // ── The ordinary cases, kept working ────────────────────────────────────

    /** Naming an address that has an account becomes that account. */
    @Test
    fun `an address with an account becomes that account`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client ->
            val state = client.impersonate(f.ownerCookie, "outsider@example.com").body<SessionState>()
            assertTrue(state.isImpersonating)
            assertEquals("Outsider", state.user?.displayName)
            // Still the owner's menu, or there would be no way back out.
            assertTrue(state.canImpersonate, "The way back out vanished with the identity.")
        }
    }

    /** Naming your own address is "stop", not a self-impersonation. */
    @Test
    fun `naming your own address stops instead`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client ->
            client.impersonate(f.ownerCookie, "outsider@example.com")
            val state = client.impersonate(f.ownerCookie, "owner@acme.com").body<SessionState>()
            assertFalse(state.isImpersonating, "Becoming yourself was recorded as an impersonation.")
            assertEquals("Owner", state.user?.displayName)
        }
    }

    /** No address at all is the signed-out visitor (LNL-103), still. */
    @Test
    fun `no address is the signed-out visitor`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client ->
            val state = client.post(ApiRoutes.IMPERSONATE) {
                cookie(SESSION_COOKIE, f.ownerCookie)
                contentType(ContentType.Application.Json)
                setBody(ImpersonateRequest(email = null))
            }.body<SessionState>()
            assertTrue(state.isImpersonating)
            assertNull(state.user, "Previewing the signed-out view left an account in place.")
            assertTrue(state.canImpersonate, "The way back out vanished with the identity.")
        }
    }

    /** A pasted sentence is a 400 rather than an impersonation of nobody. */
    @Test
    fun `something that is not an address is refused`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client ->
            assertEquals(HttpStatusCode.BadRequest, client.impersonate(f.ownerCookie, "not an address").status)
            assertFalse(
                client.session(f.ownerCookie).isImpersonating,
                "A malformed address left the session wearing something.",
            )
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private class Fixture(
        val ownerCookie: String,
        val adminCookie: String,
        val adminId: Long,
        val projectId: Long,
    )

    /**
     * An owner, an administrator who is not the owner, an outside member, and a row
     * that was added and never arrived.
     *
     * The administrator is the fixture that matters: on a fresh instance the first
     * account is both administrator and owner, so a test that seeded only one could not
     * tell the tightening from the thing it replaced.
     */
    private suspend fun seed(): Fixture {
        val owner = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-owner", "Owner", "owner@acme.com"))
        val admin = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-admin", "Admin", "admin@acme.com"))
        users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-out", "Outsider", "outsider@example.com"))
        users.addByEmail("pending@acme.com", UserKind.STAFF)
        users.setInstanceAdmin(admin.id, true)
        // The kinds sign-in would have stamped, applied by the same rule the boot pass
        // uses — so `staff` means staff here for the reason it does in production.
        stampUserKinds(users, identity.domain)
        val project = projectRepository.create("Lunamux", "LMX")
        instanceSettings.setOwnerUserId(owner.id)
        return Fixture(
            ownerCookie = sessions.create(owner.id),
            adminCookie = sessions.create(admin.id),
            adminId = admin.id,
            projectId = project.id,
        )
    }

    private suspend fun HttpClient.session(cookie: String): SessionState =
        get(ApiRoutes.SESSION) { cookie(SESSION_COOKIE, cookie) }.body()

    private suspend fun HttpClient.impersonate(cookie: String, email: String): HttpResponse =
        post(ApiRoutes.IMPERSONATE) {
            cookie(SESSION_COOKIE, cookie)
            contentType(ContentType.Application.Json)
            setBody(ImpersonateRequest(email))
        }

    private suspend fun HttpClient.preview(cookie: String, email: String): HttpResponse =
        post(ApiRoutes.IMPERSONATE_PREVIEW) {
            cookie(SESSION_COOKIE, cookie)
            contentType(ContentType.Application.Json)
            setBody(ImpersonateRequest(email))
        }

    /** Both route bundles over one settings store and one Impersonations. */
    private fun withRoutes(block: suspend (HttpClient) -> Unit) = testApplication {
        val impersonations = Impersonations()
        application {
            install(ServerContentNegotiation) { json() }
            routing {
                authRoutes(
                    config = OAuthConfig(google = null),
                    sessions = sessions,
                    users = users,
                    impersonations = impersonations,
                    instanceSettings = instanceSettings,
                    identity = identity,
                    access = access,
                    roles = roles,
                )
                boardRoutes(dependencies(impersonations))
            }
        }
        block(createClient { install(ClientContentNegotiation) { json() } })
    }

    private fun dependencies(impersonations: Impersonations) = BoardDependencies(
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
        impersonations = impersonations,
        instanceSettings = instanceSettings,
        identity = identity,
        subscriptions = SubscriptionStore(database),
        reads = ReadStore(database),
        history = IssueHistory(IssueEventStore(database), statuses, labels, components, users),
    )
}
