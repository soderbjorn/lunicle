/**
 * Who may read the account directory, and who may change what the whole instance does.
 *
 * Every route in AdminRoutes is admin-only with no narrowed half, and that is a
 * stronger claim than the one VocabularyTest makes about project settings — so it
 * is worth pinning rather than inferring. Two things would break it quietly:
 *
 *  - **A missing gate on the read.** The response is a directory of every account
 *    on the instance *including their e-mail addresses*, which nothing else on this
 *    wire carries. A handler that forgot [AccessControl] would not fail, throw or
 *    look wrong in a browser — the admin who wrote it sees exactly what they
 *    expect. It only shows up as a privacy failure for somebody else.
 *  - **A missing gate on a write.** These writes decide who may hold an account and
 *    which tier may connect an agent, so an ungated one is not "a user changes their
 *    own setting twice over", it is any signed-in user opening the instance. See
 *    McpServer.resolveMcpUser and canUseMcp.
 *
 * ── The route that used to be here (LNL-192) ────────────────────────────────
 *
 * `POST /api/admin/users/mcp` set one person's agent-access permission and is gone,
 * with its wire type and its client call. The permission is per tier now — two
 * switches on the instance-settings write — and there is no per-person override in
 * this design. What this file still pins is the separation the old route respected:
 * the *permission* is never the user's own `mcp_enabled`, and neither substitutes
 * for the other at the refresh gate.
 *
 * Through the real routes with real session cookies, for VocabularyTest's reason:
 * `canMutateProjects` returning false is not the property that matters, because a
 * route that never called it would pass that test and ship an open endpoint.
 *
 * @see adminRoutes
 * @see se.soderbjorn.lunicle.clientserver.AdminSettingsState
 */
package se.soderbjorn.lunicle

import io.ktor.client.call.body
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AdminSettingsState
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.AdmissionPolicy
import se.soderbjorn.lunicle.clientserver.AudienceGrant
import se.soderbjorn.lunicle.clientserver.HandOverInstanceRequest
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
import se.soderbjorn.lunicle.clientserver.ProjectOrder
import se.soderbjorn.lunicle.clientserver.SetAdmissionPolicyRequest
import se.soderbjorn.lunicle.clientserver.SetInstanceSettingRequest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class AdminSettingsTest {
    private val file: File = Files.createTempFile("lunicle-admin", ".db").toFile().also { it.delete() }
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

    /**
     * The shape a branded deployment takes: a domain, a chooser pinned to it, no mail.
     *
     * Every door is shut to somebody outside the domain, so nobody who signs in is a
     * member — which is what the member-tier tests below are about. See
     * [InstanceIdentity.memberTierUnreachableReason].
     */
    private val BRANDED_IDENTITY = InstanceIdentity(
        domain = "example.com",
        onlyHostedGoogleAccounts = true,
        isCodeSignInAvailable = false,
    )

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── Who may ──────────────────────────────────────────────────────────────

    /**
     * A signed-in non-admin gets nothing: 403 on the read, 403 on the write, and
     * the flag they tried to set is unchanged afterwards.
     *
     * The last assertion is the one worth writing out. A 403 that had already
     * written would still be a 403, and the response alone cannot tell the two
     * apart — so the store is checked directly rather than through the route that
     * just refused.
     */
    @Test
    fun `a non-admin is refused the directory and cannot change the instance`(): Unit = runBlocking {
        val fixture = seed()
        val ordinary = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", null),
        )
        assertTrue(!ordinary.isInstanceAdmin, "The fixture's second user is somehow an admin.")
        val cookie = sessions.create(ordinary.id)

        withRoutes { client ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/admin/settings") { cookie(SESSION_COOKIE, cookie) }.status,
                "A non-admin was handed the account directory, e-mail addresses and all.",
            )

            // Themselves — the most sympathetic version of the request, and still
            // refused. A user cannot grant their own permission; that is the whole
            // point of it being a permission. See ApiRoutes.ADMIN_SETTINGS.
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/admin/instance-settings") {
                    cookie(SESSION_COOKIE, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(SetInstanceSettingRequest(InstanceSettingKey.MEMBER_MAY_USE_AGENTS, true))
                }.status,
                "A non-admin permitted their own tier's agent access.",
            )

            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/admin/admission") {
                    cookie(SESSION_COOKIE, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(SetAdmissionPolicyRequest(AdmissionPolicy.STAFF_DOMAIN_ONLY))
                }.status,
                "A non-admin decided who may hold an account here.",
            )
        }

        // Checked against the store, not only the status: a 403 that had already
        // written would still read as a 403.
        val stored = instanceSettings.current()
        assertFalse(stored.memberMayUseAgents, "A refused write permitted a tier anyway.")
        assertEquals(AdmissionPolicy.ANYONE, stored.admission, "A refused write changed admission anyway.")
    }

    /**
     * No session at all is a 403 too, not a 401.
     *
     * There is nothing here to sign in *for* unless you are already the admin, and
     * answering differently would make the route a probe for whether a given
     * deployment has an admin session going.
     */
    @Test
    fun `a signed-out caller is refused`(): Unit = runBlocking {
        seed()
        withRoutes { client ->
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/admin/settings").status)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/admin/admission") {
                    contentType(ContentType.Application.Json)
                    setBody(SetAdmissionPolicyRequest(AdmissionPolicy.STAFF_DOMAIN_ONLY))
                }.status,
            )
        }
    }

    /**
     * The admin gets through — otherwise the tests above prove only that the
     * routes are broken for everybody.
     *
     * Also pins the two things the directory is *for*: the e-mail that tells two
     * same-named accounts apart, and the per-project rights of somebody who holds
     * one role in one project and nothing in another.
     */
    @Test
    fun `the admin reads the directory, with e-mails and per-project rights`(): Unit = runBlocking {
        val fixture = seed()
        val other = projectRepository.create("Beta", "BET")
        val ordinary = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", "ordinary@example.com"),
        )
        roles.setRole(ordinary.id, fixture.projectId, ProjectRole.CONTRIBUTOR)
        val cookie = sessions.create(fixture.adminId)

        withRoutes { client ->
            val response = client.get("/api/admin/settings") { cookie(SESSION_COOKIE, cookie) }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.body<AdminSettingsState>()

            assertEquals(
                ProjectRole.entries.map { it.key },
                body.rungs.map { it.key },
                "The People tab renders a rung against this list, in ladder order; a short " +
                    "one hides rungs and a reordered one renames them.",
            )

            val row = body.users.firstOrNull { it.userId == ordinary.id }
            assertNotNull(row, "An account is missing from the directory.")
            assertEquals("ordinary@example.com", row.email)
            assertTrue(!row.isSysAdmin)
            assertTrue(!row.isSelf)
            assertFalse(
                row.isMcpAllowed,
                "An ordinary account was permitted agent access with its tier's switch off (LNL-192).",
            )
            assertTrue(!row.isMcpEnabled, "A fresh account has not switched agent access on.")

            assertEquals(
                setOf(fixture.projectId, other.id),
                row.projects.map { it.projectId }.toSet(),
                "Every project must be listed, including the ones they hold nothing in — " +
                    "\"no rights here\" is the answer this screen exists to give.",
            )
            val onFixture = row.projects.first { it.projectId == fixture.projectId }
            val onOther = row.projects.first { it.projectId == other.id }
            // One rung, not a set of keys (LNL-195). Their own row and what they
            // effectively reach are the same thing here, there being no audience row to
            // raise them — which is exactly why both fields travel.
            assertEquals(ProjectRole.CONTRIBUTOR.key, onFixture.roleKey)
            assertEquals(ProjectRole.CONTRIBUTOR.key, onFixture.effectiveRoleKey)
            assertNull(onFixture.viaAudience, "An audience was credited for an own row.")
            assertNull(onOther.roleKey)
            assertNull(
                onOther.effectiveRoleKey,
                "A project this account holds nothing in, and no audience admits it to, " +
                    "reported a rung.",
            )

            val self = body.users.firstOrNull { it.userId == fixture.adminId }
            assertNotNull(self)
            assertTrue(self.isSelf && self.isSysAdmin)
        }
    }

    /**
     * An audience row reaches somebody who holds nothing of their own — and the row
     * says which audience did it.
     *
     * The bug the old version of this pinned: the directory answered "can they see this"
     * from the grant list, so an account with no own row on a *public* board read as
     * having no access to a board it can plainly read. The rung shape inherits the fix
     * and states it more usefully — the account holds nothing, reaches Viewer, and the
     * row names the guest audience as the reason. Somebody wondering how an account with
     * no grants anywhere is reading a board gets the answer on the row rather than by
     * opening the project.
     */
    @Test
    fun `an audience row reaches somebody who holds nothing, and the row names it`(): Unit = runBlocking {
        val fixture = seed()
        val open = projectRepository.createOpenToAll("Open", "OPN", roles, instanceSettings)
        val ordinary = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", null),
        )
        val cookie = sessions.create(fixture.adminId)

        withRoutes { client ->
            val body = client.get("/api/admin/settings") { cookie(SESSION_COOKIE, cookie) }
                .body<AdminSettingsState>()
            val onOpen = body.users.first { it.userId == ordinary.id }
                .projects.first { it.projectId == open.id }

            assertNull(
                onOpen.roleKey,
                "The account is meant to hold nothing of its own here — that is the case under test.",
            )
            assertEquals(
                ProjectRole.VIEWER.key,
                onOpen.effectiveRoleKey,
                "A board an audience row admits this account to read reported no access.",
            )
            assertEquals("the guests row", onOpen.viaAudience)
        }
    }

    /**
     * Permitting a tier reaches every account standing on it (LNL-192).
     *
     * The per-account switch is gone, so what the directory now reports for a row is
     * which side of the instance's two switches that account falls on. Asserted
     * through the write's own response, which is the whole fresh state — a route that
     * wrote correctly and answered with a stale read would leave the dialog snapping
     * back.
     */
    @Test
    fun `permitting the member tier reaches every member's row in the directory`(): Unit = runBlocking {
        val fixture = seed()
        val ordinary = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", null),
        )
        val cookie = sessions.create(fixture.adminId)

        withRoutes { client ->
            val on = client.post("/api/admin/instance-settings") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(SetInstanceSettingRequest(InstanceSettingKey.MEMBER_MAY_USE_AGENTS, true))
            }
            assertEquals(HttpStatusCode.OK, on.status)
            assertTrue(
                on.body<AdminSettingsState>().users.first { it.userId == ordinary.id }.isMcpAllowed,
                "Permitting the member tier did not reach a member's row.",
            )

            val off = client.post("/api/admin/instance-settings") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(SetInstanceSettingRequest(InstanceSettingKey.MEMBER_MAY_USE_AGENTS, false))
            }
            assertEquals(HttpStatusCode.OK, off.status)
            assertFalse(
                off.body<AdminSettingsState>().users.first { it.userId == ordinary.id }.isMcpAllowed,
                "Withdrawing the tier's permission left the row permitted.",
            )
            assertFalse(
                off.body<AdminSettingsState>().users.first { it.userId == ordinary.id }.isMcpEnabled,
                "The tier's permission was written onto the user's own switch.",
            )
        }
    }


    /**
     * An admin's agent survives a token refresh, with `mcp_allowed` still 0.
     *
     * The regression this pins is the whole reason [UserRecord.canUseMcp] insists
     * on being the only thing a gate reads. There are five gates, and refresh
     * rotation is the one that lives inside a transaction on the generated row
     * type — so it was the one written by hand against the raw columns, and the
     * one left behind when admins became permitted by being admins. Nothing failed
     * to compile. The other four said yes and this one said no, which does not
     * present as a permission bug: the agent connects, works, and then dies at the
     * first refresh with `invalid_grant`, an hour later, looking like an expiry
     * problem.
     *
     * The column the admin was deliberately left at zero on is gone (LNL-191) and the
     * permission is per tier now (LNL-192) — with **both tiers off here**, which is
     * the point: an administrator is senior to both and is permitted without a switch.
     * What this still pins is the shape: the refresh path asks the same `canUseMcp`
     * every other gate asks, so a permission that moves reaches all five.
     */
    @Test
    fun `an admin can refresh an agent's token without ever being granted permission`(): Unit = runBlocking {
        val fixture = seed()
        val admin = users.findById(fixture.adminId)!!
        assertTrue(admin.isInstanceAdmin, "Precondition: the fixture's first user is the admin.")

        // Their own switch, which admin-ness does NOT substitute for.
        users.setMcpEnabled(admin.id, true)

        // The gate is a seam now (LNL-192): the permission is a per-tier instance
        // setting, so the store cannot answer it from the user row alone.
        val tokens = OAuthTokenStore(
            database,
            canUseMcp = { id -> instanceSettings.canUseMcp(users.findById(id)) },
        )
        val client = OAuthClientStore(database).register(
            clientName = "Claude Code",
            redirectUris = listOf("http://127.0.0.1:9999/callback"),
            grantTypes = listOf("authorization_code", "refresh_token"),
        )
        val issued = tokens.issueTokens(
            userId = admin.id,
            clientId = client.clientId,
            scope = "mcp",
            resource = "https://example.invalid/mcp",
        )

        val rotated = tokens.rotateRefresh(issued.refreshToken)
        assertTrue(
            rotated is OAuthTokenStore.RefreshResult.Rotated,
            "An admin's agent was refused at token refresh; got ${rotated::class.simpleName}. " +
                "The refresh gate has drifted from UserRecord.canUseMcp again.",
        )
    }

    /**
     * The other half: admin-ness permits, it does not enable.
     *
     * Guards the over-correction of the bug above — making the refresh gate ignore
     * admins entirely, rather than only their missing permission. An admin who has
     * not turned agent access on for themselves must still be refused, exactly
     * like anybody else who has not.
     */
    @Test
    fun `an admin who has not turned agent access on is still refused at refresh`(): Unit = runBlocking {
        val fixture = seed()
        val admin = users.findById(fixture.adminId)!!
        // Note what is NOT done here: setMcpEnabled. Permission without the
        // person's own switch is not access.
        assertTrue(!admin.isMcpEnabled, "Precondition: the admin has not enabled agent access.")

        // The gate is a seam now (LNL-192): the permission is a per-tier instance
        // setting, so the store cannot answer it from the user row alone.
        val tokens = OAuthTokenStore(
            database,
            canUseMcp = { id -> instanceSettings.canUseMcp(users.findById(id)) },
        )
        val client = OAuthClientStore(database).register(
            clientName = "Claude Code",
            redirectUris = listOf("http://127.0.0.1:9999/callback"),
            grantTypes = listOf("authorization_code", "refresh_token"),
        )
        val issued = tokens.issueTokens(
            userId = admin.id,
            clientId = client.clientId,
            scope = "mcp",
            resource = "https://example.invalid/mcp",
        )

        assertTrue(
            tokens.rotateRefresh(issued.refreshToken) is OAuthTokenStore.RefreshResult.Refused,
            "Admin-ness substituted for the user's own agent-access switch.",
        )
    }

    /**
     * An owner who is impersonating loses this, like every other admin affordance.
     *
     * Impersonation exists to see what somebody else sees, and the account directory
     * is precisely what they do not see. The gate reads the session's user — which
     * under a probe session genuinely *is* the ordinary account, since the owner was
     * signed out before being signed in as them.
     *
     * Worth keeping even though the design makes it hard to fail: it is the test
     * that would catch a handler which went looking for the grant's owner to widen
     * what the session may do. The probe label is right there on the row, and the
     * whole promise is that nothing reads it as authority.
     */
    @Test
    fun `an owner who is impersonating cannot read the directory`(): Unit = runBlocking {
        val fixture = seed()
        val ordinary = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", null),
        )
        val impersonation = OwnerImpersonation(isEnabled = true)
        // What arming and then signing in as `ordinary` leaves behind: a grant the
        // admin holds, and a session for the ordinary account labelled with it.
        val probeId = impersonation.grants.arm(fixture.adminId)
        val cookie = sessions.create(ordinary.id, probeId = probeId)

        withRoutes(impersonation) { client ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/admin/settings") { cookie(SESSION_COOKIE, cookie) }.status,
                "An owner wearing somebody else's face read the account directory.",
            )
        }
    }

    /**
     * The directory is ordered admins first, then by name.
     *
     * Two facts in one list, and the order of them is the point: an administrator
     * holds Owner on every project without a row, so they are the rows an
     * administrator opening this screen is most often looking for, and burying them
     * alphabetically among twenty ordinary accounts is what the ticket was about.
     *
     * The names are chosen so the two rules disagree: sorted by name alone the
     * admin would land in the middle, and grouped without a secondary sort the
     * ordinary accounts would come back in whatever order the table felt like. The
     * grouping leans on `sortedWith` being stable over `selectAll`'s own ORDER BY
     * — so an unstable sort, or a query that stopped ordering, fails here rather
     * than in a screenshot months later.
     */
    @Test
    fun `the directory lists admins first, then by name`(): Unit = runBlocking {
        val fixture = seed()
        // "Admin" is the seeded first account and therefore the instance admin.
        // Two of these sort *before* it, which is what makes the assertion below
        // fail on a directory that only sorts by name.
        listOf("Zoe", "Aaron", "Abby").forEach { name ->
            users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-${name.lowercase()}", name, null))
        }
        val cookie = sessions.create(fixture.adminId)

        withRoutes { client ->
            val body = client.get("/api/admin/settings") { cookie(SESSION_COOKIE, cookie) }
                .body<AdminSettingsState>()
            assertEquals(listOf("Admin", "Aaron", "Abby", "Zoe"), body.users.map { it.name })
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    // ── Projects: reorder and delete (LNL-93) ────────────────────────────────

    /**
     * The admin arranges the projects, and the order persists — in the store the
     * picker reads, and in the state the dialog re-renders from.
     *
     * The whole list is sent, reversed, and both surfaces are checked: a route
     * that answered with a fresh state built from a stale read would look right in
     * the response and be wrong on the next page load.
     */
    @Test
    fun `the admin reorders projects and the order sticks`(): Unit = runBlocking {
        val fixture = seed(name = "Alpha", prefix = "ALP")
        val beta = projectRepository.create("Beta", "BET")
        val gamma = projectRepository.create("Gamma", "GAM")
        val cookie = sessions.create(fixture.adminId)

        // Created in order, so they start Alpha, Beta, Gamma — the alphabetical
        // order the migration back-fills and the append-on-create keeps.
        assertEquals(
            listOf("Alpha", "Beta", "Gamma"),
            projects.selectAll().map { it.name },
            "Projects did not start in creation order.",
        )

        val reversed = listOf(gamma.id, beta.id, fixture.projectId)
        withRoutes { client ->
            val response = client.post("/api/admin/projects/order") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(ProjectOrder(reversed))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                reversed,
                response.body<AdminSettingsState>().projects.map { it.id },
                "The response did not report the new order.",
            )
        }

        assertEquals(
            reversed,
            projects.selectAll().map { it.id },
            "The picker's own read did not come back in the new order.",
        )
    }

    /**
     * A reorder that does not name exactly the instance's projects is refused
     * whole — a 409, and nothing moved.
     *
     * The usual cause is a second admin deleting a project while this dialog was
     * open; the stale client must be told rather than have its partial order
     * applied. See ProjectRepository.reorder.
     */
    @Test
    fun `a reorder that does not name the projects is refused`(): Unit = runBlocking {
        val fixture = seed(name = "Alpha", prefix = "ALP")
        val beta = projectRepository.create("Beta", "BET")
        val cookie = sessions.create(fixture.adminId)
        val before = projects.selectAll().map { it.id }

        withRoutes { client ->
            assertEquals(
                HttpStatusCode.Conflict,
                client.post("/api/admin/projects/order") {
                    cookie(SESSION_COOKIE, cookie)
                    contentType(ContentType.Application.Json)
                    // Missing Alpha entirely — not this instance's set.
                    setBody(ProjectOrder(listOf(beta.id)))
                }.status,
            )
        }

        assertEquals(before, projects.selectAll().map { it.id }, "A refused reorder moved something.")
    }

    /** The admin deletes a project through the settings dialog, and gets the fresh directory back. */
    @Test
    fun `the admin deletes a project from the settings dialog`(): Unit = runBlocking {
        val fixture = seed(name = "Alpha", prefix = "ALP")
        val beta = projectRepository.create("Beta", "BET")
        val cookie = sessions.create(fixture.adminId)

        withRoutes { client ->
            val response = client.delete("/api/admin/projects/${beta.id}") { cookie(SESSION_COOKIE, cookie) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                listOf(fixture.projectId),
                response.body<AdminSettingsState>().projects.map { it.id },
                "The deleted project was still in the returned directory.",
            )
        }

        assertNull(projects.findById(beta.id), "The project outlived its deletion.")
    }

    /**
     * A non-admin may neither reorder nor delete projects, and nothing they aimed
     * at is touched.
     *
     * The store is checked directly afterwards, for [`a non-admin is refused the
     * directory and cannot grant agent access`]'s reason: a 403 that had already
     * written would still read as a 403.
     */
    @Test
    fun `a non-admin cannot reorder or delete projects`(): Unit = runBlocking {
        val fixture = seed(name = "Alpha", prefix = "ALP")
        val beta = projectRepository.create("Beta", "BET")
        val ordinary = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", null))
        val cookie = sessions.create(ordinary.id)
        val before = projects.selectAll().map { it.id }

        withRoutes { client ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/admin/projects/order") {
                    cookie(SESSION_COOKIE, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(ProjectOrder(before.reversed()))
                }.status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.delete("/api/admin/projects/${beta.id}") { cookie(SESSION_COOKIE, cookie) }.status,
            )
        }

        assertEquals(before, projects.selectAll().map { it.id }, "A refused reorder moved something.")
        assertFalse(projects.findById(beta.id) == null, "A refused delete removed the project anyway.")
    }

    /**
     * An instance **administrator** who is not the owner reads the whole directory and may
     * flip a switch — and may still not reorder or delete across boards (LNL-195).
     *
     * The regression this exists for: every route in this file gated on
     * `canMutateProjects`, which is the *owner*, so the three instance tabs were owner-only
     * by accident. The client offers them to anybody who is an administrator, so what an
     * administrator actually got was three tabs rendering empty headings and a refusal.
     * Found by driving the app as one — and it failed no test here, because every test in
     * this file signs in as the fixture's admin, who is also the seated owner.
     *
     * LNL-191's narrowing survives, and is asserted rather than merely left alone: the order
     * and the cross-project delete stay the owner's, and the response says so on
     * `canReorderProjects` so the screen greys the arrows instead of collecting a 403.
     */
    @Test
    fun `an instance administrator who is not the owner reads and switches, but cannot reorder`(): Unit =
        runBlocking {
            val fixture = seed(name = "Alpha", prefix = "ALP")
            val beta = projectRepository.create("Beta", "BET")
            // A second administrator, who is not the seated owner. Promoted through the store
            // because there is no route that promotes anybody — see the view model's preamble,
            // which is why the People tab states adminship rather than offering it.
            val second = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-second", "Second", null))
            users.setInstanceAdmin(second.id, true)
            assertEquals(
                fixture.adminId,
                instanceSettings.current().ownerUserId,
                "Precondition: the fixture's first admin is the seated owner.",
            )
            val cookie = sessions.create(second.id)
            val before = projects.selectAll().map { it.id }

            withRoutes { client ->
                val read = client.get("/api/admin/settings") { cookie(SESSION_COOKIE, cookie) }
                assertEquals(HttpStatusCode.OK, read.status, "An instance administrator was refused the directory.")
                val body = read.body<AdminSettingsState>()
                assertTrue(body.users.isNotEmpty(), "The directory came back empty for an administrator.")
                assertTrue(body.projects.isNotEmpty(), "The project list came back empty for an administrator.")
                assertFalse(
                    body.canReorderProjects,
                    "An administrator was told they may reorder; that is the owner's (LNL-191).",
                )
                assertNotNull(
                    body.projectSetReadOnlyReason,
                    "The arrows would be greyed with no sentence saying whose they are.",
                )
                assertFalse(body.ownership.isOwnerSelf, "An administrator was reported as the owner.")

                // A policy switch: an administrator's, and it lands.
                val write = client.post("/api/admin/instance-settings") {
                    cookie(SESSION_COOKIE, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(SetInstanceSettingRequest(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true))
                }
                assertEquals(HttpStatusCode.OK, write.status, "An administrator was refused a policy switch.")
                assertTrue(write.body<AdminSettingsState>().allowPublicProjects)

                // The order and the cross-project delete: still the owner's.
                assertEquals(
                    HttpStatusCode.Forbidden,
                    client.post("/api/admin/projects/order") {
                        cookie(SESSION_COOKIE, cookie)
                        contentType(ContentType.Application.Json)
                        setBody(ProjectOrder(before.reversed()))
                    }.status,
                    "An administrator reordered every board on the instance.",
                )
                assertEquals(
                    HttpStatusCode.Forbidden,
                    client.delete("/api/admin/projects/${beta.id}") { cookie(SESSION_COOKIE, cookie) }.status,
                    "An administrator deleted a board they do not own.",
                )
            }

            assertEquals(before, projects.selectAll().map { it.id }, "A refused reorder moved something.")
            assertFalse(projects.findById(beta.id) == null, "A refused delete removed the project anyway.")
            assertTrue(instanceSettings.current().allowPublicProjects, "The administrator's switch did not persist.")
        }

    /**
     * The new-project guests row stops at Viewer too (LNL-202).
     *
     * The sibling of a project's own audience write, and it needs the same refusal for a
     * reason of its own: this setting is copied onto **every future board**, so a guest row
     * above Viewer here would be that row on projects that do not exist yet, written by
     * `seatNewProject` with no picker anywhere near it.
     *
     * Both ends again — the POST refuses it with the reason, and the row that comes back
     * offers Viewer alone with the rest greyed. Public projects are switched on first, so the
     * publish veto is not what is doing the refusing.
     */
    @Test
    fun `a new project cannot start out admitting guests above viewer`(): Unit = runBlocking {
        val fixture = seed()
        val cookie = sessions.create(fixture.adminId)
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)

        withRoutes { client ->
            val refused = client.post("/api/admin/new-project-audience") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(AudienceGrant(Audience.GUEST.key, ProjectRole.CONTRIBUTOR.key))
            }
            assertEquals(HttpStatusCode.Conflict, refused.status, "Every future board was made writable by guests.")
            assertTrue(
                refused.bodyAsText().contains("attribute"),
                "The refusal did not say what is missing: somebody to attribute the write to.",
            )
            assertNull(
                instanceSettings.current().newProjectAudiences[Audience.GUEST],
                "The refused setting landed anyway.",
            )

            // Viewer is accepted — a deployment whose boards are public by default is a
            // deployment, not a bug.
            val accepted = client.post("/api/admin/new-project-audience") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(AudienceGrant(Audience.GUEST.key, ProjectRole.VIEWER.key))
            }
            assertEquals(HttpStatusCode.OK, accepted.status)
            val guests = accepted.body<AdminSettingsState>().newProjectAudiences
                .first { it.key == Audience.GUEST.key }
            assertEquals(
                listOf(ProjectRole.VIEWER.key),
                guests.rungs.filter { it.isSelectable }.map { it.key },
                "The guests row offered a rung the write refuses.",
            )
            assertEquals(
                ProjectRole.entries.map { it.key },
                guests.rungs.map { it.key },
                "A rung out of reach was omitted rather than greyed, which reads as a bug.",
            )
        }
    }

    /**
     * The defaults cannot be a pair a project's own Access list would refuse (LNL-209).
     *
     * The sibling refusal again, and it earns its place the same way: `seatNewProject`
     * copies these rows onto every future board, where the floor then applies. A guests
     * Viewer beside a members No access here would arrive on a new project as a members row
     * the board reports as Viewer and this screen reported as nothing — the same sentence
     * said two ways, which is the defect one step earlier.
     */
    @Test
    fun `a new project cannot start out with members below guests`(): Unit = runBlocking {
        val fixture = seed()
        val cookie = sessions.create(fixture.adminId)
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)
        instanceSettings.setNewProjectAudience(Audience.GUEST, ProjectRole.VIEWER)

        withRoutes { client ->
            val refused = client.post("/api/admin/new-project-audience") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(AudienceGrant(Audience.MEMBER.key, null))
            }
            assertEquals(
                HttpStatusCode.Conflict,
                refused.status,
                "A default pair landed that a new board's own Access list refuses to express.",
            )
            assertTrue(
                refused.bodyAsText().contains("guests row"),
                "The refusal did not name the row that is giving the access.",
            )

            // And the row that comes back says what a member actually starts with, with the
            // entry that would have said otherwise struck through.
            val members = client.get("/api/admin/settings") { cookie(SESSION_COOKIE, cookie) }
                .body<AdminSettingsState>().newProjectAudiences
                .first { it.key == Audience.MEMBER.key }
            assertEquals(
                ProjectRole.VIEWER.key,
                members.roleKey,
                "The members default reported No access beside a guests default of Viewer.",
            )
            assertEquals(ProjectRole.VIEWER.key, members.floorKey)
            assertNotNull(members.withdrawRefusal, "The No access entry was left live.")
            assertNotNull(members.effectiveLine, "The row reported Viewer without saying where it comes from.")
        }
    }

    /**
     * Nothing may be *given* to a tier no arrival can stand in — on both surfaces (LNL-210).
     *
     * The branded shape — a domain, a pinned chooser, no mail — leaves every account that
     * signs in on the domain, so the Members card's two switches and the members row under
     * what a new project starts with both describe a set nobody arriving is in. A live
     * control there is the screen offering a permission the deployment will hand to nobody,
     * and the count beside the card cannot correct it: it can be non-zero from an address
     * added by hand, which is exactly the case where these still bite.
     *
     * The direction is the other half of the claim, and the half that would be easy to get
     * wrong in either direction. Granting is refused; withdrawing never is. A row stays
     * selectable so a default stored before the deployment pinned itself can be cleared,
     * and the rungs inside it die — which is precisely the shape LNL-203 settled for the
     * guest row under the public-projects veto.
     */
    @Test
    fun `a member tier nobody can arrive at refuses grants on both surfaces`(): Unit =
        runBlocking {
            val fixture = seed()
            users.setKind(fixture.adminId, UserKind.STAFF)
            val cookie = sessions.create(fixture.adminId)

            withRoutes(identity = BRANDED_IDENTITY) { client ->
                val body = client.get("/api/admin/settings") { cookie(SESSION_COOKIE, cookie) }
                    .body<AdminSettingsState>()

                val members = body.tiers.first { it.key == InstanceRole.MEMBER.key }
                val reason = assertNotNull(
                    members.grantRefusal,
                    "The Members card offered two switches over a tier nobody can arrive at.",
                )
                assertTrue(
                    reason.contains("every account that arrives is staff"),
                    "The card's sentence did not say what actually happens: $reason",
                )
                // Both default to off, so the card an administrator actually meets is dead.
                assertFalse(members.mayCreateProjects)
                assertFalse(members.mayUseAgents)

                // Staff is the tier everybody lands in here, so it refuses nothing.
                assertNull(
                    body.tiers.first { it.key == InstanceRole.STAFF.key }.grantRefusal,
                    "The tier every arrival stands in was refused a grant.",
                )

                val row = body.newProjectAudiences.first { it.key == Audience.MEMBER.key }
                assertNotNull(
                    row.unavailableReason,
                    "The new-project members row said nothing about admitting nobody.",
                )
                assertTrue(
                    row.rungs.none { it.isSelectable },
                    "A rung was offered to a tier no arrival is in.",
                )
                // ...and the row itself is not, so No access stays reachable. LNL-203.
                assertTrue(row.isSelectable, "The members row was killed along with its rungs.")
                assertNull(row.withdrawRefusal, "Clearing the members default was refused.")
            }
        }

    /**
     * ...and the routes refuse the same direction, so the greying is a rule (LNL-210).
     *
     * Both halves matter. A greyed control that a hand-written POST could still set makes
     * the greying an affordance — the argument the admission route already makes. And a
     * refusal that also caught the withdrawal would strand an administrator with a
     * permission they can see, cannot use, and cannot revoke before the day it wakes up.
     */
    @Test
    fun `granting to an unreachable member tier is refused, and withdrawing is not`(): Unit = runBlocking {
        val fixture = seed()
        users.setKind(fixture.adminId, UserKind.STAFF)
        val cookie = sessions.create(fixture.adminId)
        // Stored on before the deployment pinned itself — the state the withdrawal half of
        // the rule exists for, and the only way to reach it now.
        instanceSettings.set(InstanceSettingKey.MEMBER_MAY_USE_AGENTS, true)

        withRoutes(identity = BRANDED_IDENTITY) { client ->
            val refused = client.post("/api/admin/instance-settings") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(SetInstanceSettingRequest(InstanceSettingKey.MEMBER_MAY_CREATE_PROJECTS, true))
            }
            assertEquals(
                HttpStatusCode.Conflict,
                refused.status,
                "A permission was granted to a tier nobody arriving is in.",
            )
            assertFalse(
                instanceSettings.current().memberMayCreateProjects,
                "The refusal answered with a Conflict and stored the setting anyway.",
            )

            // The one already on comes back off, which is the whole point of the asymmetry.
            val withdrawn = client.post("/api/admin/instance-settings") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(SetInstanceSettingRequest(InstanceSettingKey.MEMBER_MAY_USE_AGENTS, false))
            }
            assertEquals(
                HttpStatusCode.OK,
                withdrawn.status,
                "An administrator was refused the withdrawal of a dormant permission.",
            )
            assertFalse(instanceSettings.current().memberMayUseAgents)

            // Same rule on the other surface: a rung is refused, clearing the row is not.
            val grant = client.post("/api/admin/new-project-audience") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(AudienceGrant(Audience.MEMBER.key, ProjectRole.VIEWER.key))
            }
            assertEquals(
                HttpStatusCode.Conflict,
                grant.status,
                "A new project was set to start out giving members access nobody can claim.",
            )
            val cleared = client.post("/api/admin/new-project-audience") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(AudienceGrant(Audience.MEMBER.key, null))
            }
            assertEquals(
                HttpStatusCode.OK,
                cleared.status,
                "Clearing the members default was refused along with setting it.",
            )
        }
    }

    /**
     * ...and an open deployment carries neither sentence.
     *
     * The guard against a message that reads as a warning appearing on every unbranded
     * install, which is the shape every default deployment and every test fixture takes.
     */
    @Test
    fun `an open deployment says nothing about its member tier`(): Unit = runBlocking {
        val fixture = seed()
        val cookie = sessions.create(fixture.adminId)

        withRoutes(identity = InstanceIdentity(domain = "example.com")) { client ->
            val body = client.get("/api/admin/settings") { cookie(SESSION_COOKIE, cookie) }
                .body<AdminSettingsState>()
            assertTrue(
                body.tiers.all { it.grantRefusal == null },
                "A deployment anybody can sign into refused a grant to a tier.",
            )
            val row = body.newProjectAudiences.first { it.key == Audience.MEMBER.key }
            assertNull(
                row.unavailableReason,
                "The members row carried a restriction this deployment does not impose.",
            )
            assertTrue(row.rungs.all { it.isSelectable }, "A members rung was greyed for no reason.")
        }
    }

    // ── Handing the instance over (LNL-198) ──────────────────────────────────

    /**
     * The owner is offered **staff who have signed in**, and nobody else.
     *
     * Three accounts that are not eligible, each for a different reason, and each a real
     * account somebody would expect to see: a member (signed in, but from outside the
     * domain), a staff address added ahead of time and never claimed, and the owner
     * themselves. A picker that offered any of them would be offering a route that refuses.
     *
     * The never-signed-in row is the one worth being explicit about. It holds rungs, it is
     * somebody's deliberate act, and it looks exactly like a claimed account on every other
     * screen — so "ownership can land on an address that was typed once into a dialog" is a
     * bug that would ship looking perfectly reasonable.
     */
    @Test
    fun `the picker offers signed-in staff and nobody else`(): Unit = runBlocking {
        val fixture = seed()
        users.setKind(fixture.adminId, UserKind.STAFF)
        val staff = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-staff", "Staff Signedin", "staff@example.com"),
            kind = UserKind.STAFF,
        )
        val member = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-member", "Outside Member", "outside@elsewhere.test"),
            kind = UserKind.MEMBER,
        )
        // An address somebody added and nobody has ever arrived at: staff by domain, and
        // `signed_in_at` is deliberately left null by addByEmail. See Users.sq.
        val unclaimed = users.addByEmail("never@example.com", kind = UserKind.STAFF)
        assertFalse(unclaimed.hasSignedIn, "Precondition: the added address has never been signed in to.")
        val cookie = sessions.create(fixture.adminId)

        withRoutes(identity = InstanceIdentity(domain = "example.com")) { client ->
            val body = client.get("/api/admin/settings") { cookie(SESSION_COOKIE, cookie) }
                .body<AdminSettingsState>()
            assertTrue(body.ownership.isOwnerSelf, "Precondition: the caller is the owner.")
            assertTrue(body.ownership.canHandOver, "The owner was not offered Hand over…")
            assertNull(body.ownership.handOverBlockedReason, "The owner was given a reason they may not.")
            assertEquals(
                listOf(staff.id),
                body.ownership.handOverCandidates.map { it.userId },
                "The picker offered somebody other than the signed-in staff account.",
            )
            assertNull(
                body.ownership.handOverEmptyReason,
                "A populated picker also carried the sentence for an empty one.",
            )
            // Named individually so a failure says which of the three leaked.
            val offered = body.ownership.handOverCandidates.map { it.userId }
            assertFalse(member.id in offered, "A member was offered the deployment.")
            assertFalse(unclaimed.id in offered, "An address nobody has signed in to was offered the deployment.")
            assertFalse(fixture.adminId in offered, "The owner was offered their own instance.")
        }
    }

    /**
     * The handover itself: one owner before, one owner after, and the outgoing owner left
     * administering rather than demoted out of the building.
     *
     * Checked in the **stores** rather than only in the response, for the reason the refusal
     * tests give in reverse: a response that reported the right thing while having written
     * something else would look identical. Four separate claims, and each of them is a way
     * this could ship broken:
     *
     *  - the setting names the successor, and *only* one account can be named, because it is
     *    one field (see `InstanceSettings.ownerUserId`);
     *  - the outgoing owner holds `instance_role = 'admin'`, so they did not fall out of
     *    administration by handing over;
     *  - the projects they owned are still theirs — nothing about a project rung is touched
     *    by an instance-ladder write, and it would be very easy to "tidy up" that way;
     *  - the response the caller reads describes an instance they no longer own, which is
     *    what puts the button away.
     */
    @Test
    fun `the owner hands the instance over and stays an administrator`(): Unit = runBlocking {
        val fixture = seed()
        users.setKind(fixture.adminId, UserKind.STAFF)
        val successor = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-successor", "Ada Lovelace", "ada@example.com"),
            kind = UserKind.STAFF,
        )
        // A rung the outgoing owner holds on their own board. It must survive: giving up the
        // instance is not giving up your projects.
        roles.setRole(fixture.adminId, fixture.projectId, ProjectRole.OWNER)
        val cookie = sessions.create(fixture.adminId)

        withRoutes(identity = InstanceIdentity(domain = "example.com")) { client ->
            val response = client.post("/api/admin/ownership") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(HandOverInstanceRequest(successor.id))
            }
            assertEquals(HttpStatusCode.OK, response.status, "The owner was refused their own handover.")
            val body = response.body<AdminSettingsState>()
            assertEquals(
                "Ada Lovelace",
                body.ownership.ownerName,
                "The response still named the old owner, so the screen would not have moved.",
            )
            assertFalse(body.ownership.isOwnerSelf, "The caller was still reported as the owner.")
            assertFalse(body.ownership.canHandOver, "The button would have stayed live for a non-owner.")
            assertTrue(
                body.ownership.handOverCandidates.isEmpty(),
                "A successor list was sent to somebody who can no longer use it.",
            )
        }

        assertEquals(successor.id, instanceSettings.current().ownerUserId, "Ownership did not move.")
        // Exactly one, and structurally so: ownership is one field, so this asserts the
        // invariant by counting the accounts the whole store agrees are the owner.
        val ownerId = instanceSettings.current().ownerUserId
        assertEquals(
            listOf(successor.id),
            users.selectAll().map { it.id }.filter { it == ownerId },
            "The instance has other than exactly one owner.",
        )
        assertTrue(
            users.findById(fixture.adminId)?.isInstanceAdmin == true,
            "The outgoing owner was left with no administration at all.",
        )
        assertFalse(
            users.findById(successor.id)?.isInstanceAdmin == true,
            "The incoming owner was flagged an administrator too; ownership is senior, so " +
                "that would pre-decide what they fall back to.",
        )
        assertEquals(
            ProjectRole.OWNER,
            roles.roleFor(fixture.adminId, fixture.projectId),
            "The outgoing owner lost a project they owned.",
        )
    }

    /**
     * An instance **administrator** cannot hand the instance over, and the route says so —
     * not merely the button being absent.
     *
     * The affordance and the enforcement are asserted separately on purpose. The screen
     * hides the button on `canHandOver`, which is JavaScript on somebody else's machine; the
     * property that matters is that a hand-written POST from the same session is refused,
     * and that nothing was written on the way to refusing. An administrator who could hand
     * the instance over could hand it to *themselves*, which would make administrator and
     * owner the same rung and quietly undo every narrowing this rework made.
     */
    @Test
    fun `an instance administrator sees the row, gets no button, and is refused the route`(): Unit = runBlocking {
        val fixture = seed()
        users.setKind(fixture.adminId, UserKind.STAFF)
        val second = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-second", "Second Admin", "second@example.com"),
            kind = UserKind.STAFF,
        )
        users.setInstanceAdmin(second.id, true)
        val cookie = sessions.create(second.id)

        withRoutes(identity = InstanceIdentity(domain = "example.com")) { client ->
            val body = client.get("/api/admin/settings") { cookie(SESSION_COOKIE, cookie) }
                .body<AdminSettingsState>()
            assertFalse(body.ownership.isOwnerSelf, "An administrator was reported as the owner.")
            assertFalse(body.ownership.canHandOver, "An administrator was offered Hand over…")
            assertNotNull(body.ownership.handOverBlockedReason, "No sentence said whose the handover is.")
            assertTrue(
                body.ownership.handOverCandidates.isEmpty(),
                "A successor list was sent to somebody who may not use it.",
            )

            // The enforcement, called directly — twice, because handing it to the owner's
            // account and to their own are different mistakes and both must be refused.
            listOf(second.id, fixture.adminId).forEach { target ->
                assertEquals(
                    HttpStatusCode.Forbidden,
                    client.post("/api/admin/ownership") {
                        cookie(SESSION_COOKIE, cookie)
                        contentType(ContentType.Application.Json)
                        setBody(HandOverInstanceRequest(target))
                    }.status,
                    "An instance administrator handed the deployment to $target.",
                )
            }
        }

        assertEquals(fixture.adminId, instanceSettings.current().ownerUserId, "A refused handover moved ownership.")
    }

    /**
     * A member and a never-claimed address are refused at the route as well as absent from
     * the picker, and the refusal carries the reason the screen would have shown.
     *
     * The greying-is-not-a-rule test this file already makes about admission, made again
     * here because the consequence is heavier: an ineligible account that the route accepted
     * would be a deployment owned by an address nobody has ever proved they control.
     */
    @Test
    fun `an ineligible account is refused with the sentence the screen would show`(): Unit = runBlocking {
        val fixture = seed()
        users.setKind(fixture.adminId, UserKind.STAFF)
        val member = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-member", "Outside Member", "outside@elsewhere.test"),
            kind = UserKind.MEMBER,
        )
        val unclaimed = users.addByEmail("never@example.com", kind = UserKind.STAFF)
        val cookie = sessions.create(fixture.adminId)

        withRoutes(identity = InstanceIdentity(domain = "example.com")) { client ->
            val toMember = client.post("/api/admin/ownership") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(HandOverInstanceRequest(member.id))
            }
            assertEquals(HttpStatusCode.Conflict, toMember.status, "A member was handed the deployment.")
            assertTrue(
                toMember.bodyAsText().contains("member here rather than staff"),
                "The refusal did not say why, so the reader learns nothing: ${toMember.bodyAsText()}",
            )

            val toUnclaimed = client.post("/api/admin/ownership") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(HandOverInstanceRequest(unclaimed.id))
            }
            assertEquals(
                HttpStatusCode.Conflict,
                toUnclaimed.status,
                "An address nobody has signed in to was handed the deployment.",
            )
            assertTrue(
                toUnclaimed.bodyAsText().contains("Nobody has ever signed in"),
                "The refusal did not name the unclaimed address as the reason: ${toUnclaimed.bodyAsText()}",
            )

            // And the two that are not about eligibility at all.
            assertEquals(
                HttpStatusCode.Conflict,
                client.post("/api/admin/ownership") {
                    cookie(SESSION_COOKIE, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(HandOverInstanceRequest(fixture.adminId))
                }.status,
                "Handing the instance to yourself was accepted as a no-op.",
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.post("/api/admin/ownership") {
                    cookie(SESSION_COOKIE, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(HandOverInstanceRequest(9_999L))
                }.status,
                "An account that does not exist was not reported as missing.",
            )
        }

        assertEquals(fixture.adminId, instanceSettings.current().ownerUserId, "A refused handover moved ownership.")
    }

    /**
     * On a deployment that names **no domain**, there is nobody to hand it to — and the
     * screen is told why rather than shown an empty menu.
     *
     * This is the default configuration, not an edge case: `brand.json` is optional, and
     * `UserKind.forEmail` makes every account a member without a domain, so an unbranded
     * install has an empty picker by construction. An owner looking at one needs to be sent
     * to the deployment's configuration, which is the one place that can change the answer —
     * so the sentence says `brand.json` out loud.
     */
    @Test
    fun `a deployment with no domain has nobody to hand the instance to`(): Unit = runBlocking {
        val fixture = seed()
        val other = users.upsert(
            ProviderIdentity(AuthProvider.GITHUB, "gh-other", "Somebody Else", "else@example.com"),
        )
        val cookie = sessions.create(fixture.adminId)

        // No identity argument: the unbranded default, which is what an install with no
        // brand directory runs as.
        withRoutes { client ->
            val body = client.get("/api/admin/settings") { cookie(SESSION_COOKIE, cookie) }
                .body<AdminSettingsState>()
            assertTrue(
                body.ownership.canHandOver,
                "Hand over… was hidden from the owner, so the reason has nowhere to be read.",
            )
            assertTrue(body.ownership.handOverCandidates.isEmpty(), "Somebody was eligible with no staff tier.")
            val reason = assertNotNull(
                body.ownership.handOverEmptyReason,
                "An empty picker with no sentence beside it reads as a bug.",
            )
            assertTrue(reason.contains("brand.json"), "The reason did not point at what could change it: $reason")

            // And the route agrees, so the empty picker is a rule and not a rendering.
            val refused = client.post("/api/admin/ownership") {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(HandOverInstanceRequest(other.id))
            }
            assertEquals(HttpStatusCode.Conflict, refused.status, "An ownerless-tier deployment handed itself over.")
            assertTrue(
                refused.bodyAsText().contains("names no domain"),
                "The refusal blamed the account rather than the deployment: ${refused.bodyAsText()}",
            )
        }

        assertEquals(fixture.adminId, instanceSettings.current().ownerUserId, "A refused handover moved ownership.")
    }

    private class Fixture(val adminId: Long, val projectId: Long)

    /**
     * A seeded project and the instance admin.
     *
     * The admin is whoever signs in first — see Users.sq's upsert — so the first
     * call here produces one and every later user in a test is ordinary.
     *
     * `roles.seed()` is not optional here, and the reason is worth writing down:
     * `Roles.sq`'s grant is an `INSERT OR IGNORE … SELECT … WHERE role_key = ?`,
     * which silently inserts *nothing* when the roles vocabulary is empty. Without
     * this line every grant in this file would quietly do nothing and the rights
     * assertions would be checking that an empty list is empty. Application.kt
     * seeds it at startup; a test that mounts the routes without the startup has
     * to do it too.
     */
    private suspend fun seed(name: String = "Lunamux", prefix: String = "LMX"): Fixture {
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin", "Admin", null))
        val project = projectRepository.create(name, prefix)
        // Production seats the instance owner at boot (see InstanceLadder.kt), and
        // four rules — creating and managing projects, backfilling authorship, agent
        // mail, out-of-band attachment deletes — are the owner's alone rather than an
        // administrator's. A fixture that skipped this would be testing an instance
        // nobody runs: one with an administrator and no owner.
        seatInstanceOwner(users, instanceSettings)
        return Fixture(admin.id, project.id)
    }

    /**
     * Mount the real routes and hand back a client. See VocabularyTest.withRoutes.
     *
     * @param identity the deployment's own configuration. Defaulted to the unbranded one —
     *   no domain, so every account is a member — because that is what most of this file
     *   wants. The handover tests pass one that names a domain, because "staff" does not
     *   exist without it and the whole eligibility rule is about staff (LNL-198).
     */
    private fun withRoutes(
        impersonation: OwnerImpersonation = OwnerImpersonation(),
        identity: InstanceIdentity = InstanceIdentity(),
        block: suspend (io.ktor.client.HttpClient) -> Unit,
    ) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing { boardRoutes(dependencies(impersonation, identity)) }
        }
        val client = createClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
        }
        block(client)
    }

    private fun dependencies(
        impersonation: OwnerImpersonation,
        identity: InstanceIdentity = InstanceIdentity(),
    ) = BoardDependencies(
        identity = identity,
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
        impersonation = impersonation,
        // The SAME store `access` and the fixture's `seatInstanceOwner` use. Omitted before
        // LNL-195, which meant BoardDependencies defaulted to a *second*, empty one — so
        // every switch a route wrote landed somewhere the test could not read, and the
        // ownership this file's fixture seats was invisible to the response being asserted.
        // The tests passed because none of them looked.
        instanceSettings = instanceSettings,
        subscriptions = SubscriptionStore(database),
        reads = ReadStore(database),
    )
}
