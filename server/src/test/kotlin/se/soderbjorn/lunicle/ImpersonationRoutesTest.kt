/**
 * Impersonation through the real routes: who may arm one, what a grant is worth,
 * and what signing in on one actually does.
 *
 * ── The four things this file exists to pin ─────────────────────────────────
 *
 *  - **The owner alone, on a deployment that has switched it on.** An instance
 *    *administrator* is refused; so is the owner themselves where the deploy gate
 *    is off. Both terms, because an administrator holds Owner on every project and
 *    reads the account directory, so every other instance-wide surface opens for
 *    them — and because a gate nobody tests is a gate that quietly stops being one.
 *  - **The client never says who it is.** The address in the body is a petition,
 *    honoured only against a live grant in the probe cookie. Forged, expired,
 *    absent and no-longer-the-owner are four different failures and one flat 403.
 *  - **It is a REAL sign-in.** Admission answers with its own refusal, an address
 *    with no account gets one, and the account persists after stopping. That is the
 *    departure from the preview this replaced, and it is asserted by looking at the
 *    `users` table rather than at a response.
 *  - **The probe label is not authority.** A probe session is an ordinary session
 *    for an ordinary person — except that it cannot re-point that person's e-mail,
 *    and that it dies the moment its grant does.
 *
 * @see ProbeGrants
 * @see AccessControl.canImpersonate
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import se.soderbjorn.lunicle.clientserver.AdmissionPolicy
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.ImpersonateRequest
import se.soderbjorn.lunicle.clientserver.IssueDraft
import se.soderbjorn.lunicle.clientserver.IssueUpdate
import se.soderbjorn.lunicle.clientserver.ProjectListState
import se.soderbjorn.lunicle.clientserver.RequestEmailChangeRequest
import se.soderbjorn.lunicle.clientserver.SessionState
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

    // ── Who may arm one ─────────────────────────────────────────────────────

    /** The owner is offered the item; nobody is offered a directory any more. */
    @Test
    fun `the owner is offered impersonation on an armed deployment`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client, _ ->
            val state = client.session(f.ownerCookie)
            assertTrue(state.canImpersonate, "The instance owner was not offered impersonation.")
            assertFalse(state.isImpersonating, "A plain session reported itself as an impersonation.")
        }
    }

    /**
     * The deploy gate, which is off on almost every deployment there is.
     *
     * Both halves, because they fail differently: the affordance going missing is a
     * menu item nobody sees, and the route refusing is what makes that safe. A gate
     * that only reached the first would leave the whole facility live to anybody who
     * knew the URL.
     */
    @Test
    fun `an unarmed deployment offers nothing and refuses the routes`(): Unit = runBlocking {
        val f = seed()

        withRoutes(impersonation = OwnerImpersonation(isEnabled = false)) { client, _ ->
            assertFalse(
                client.session(f.ownerCookie).canImpersonate,
                "An unarmed deployment offered its own owner the impersonation item.",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.arm(f.ownerCookie).status,
                "The owner armed an impersonation on a deployment that has the feature switched off.",
            )
        }
    }

    /**
     * ...and it holds even for somebody presenting a valid-looking probe cookie.
     *
     * The state a deployment lands in when the switch is turned off under a live
     * probe: the browser still has its cookie, and this is the assertion that the
     * cookie is worth nothing without the gate.
     */
    @Test
    fun `an unarmed deployment refuses a probe cookie it would otherwise honour`(): Unit = runBlocking {
        val f = seed()
        // A grant armed by a genuinely entitled owner, in a store the routes below can
        // see — and refused anyway, because the gate is a term of every check.
        val grants = ProbeGrants()
        val probeId = grants.arm(f.ownerId)

        withRoutes(impersonation = OwnerImpersonation(isEnabled = false, grants = grants)) { client, _ ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.impersonate(probeId, "outsider@example.com").status,
                "A live grant worked on a deployment with the feature switched off.",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.stop(probeId).status,
                "The stop route answered on a deployment with the feature switched off.",
            )
        }
    }

    /**
     * An instance **administrator** may not arm one.
     *
     * The LNL-197 tightening, still the assertion it always was: an administrator is
     * the caller most likely to be left holding a stale affordance, because so many
     * other instance-wide surfaces do open for them.
     */
    @Test
    fun `an instance administrator may not arm an impersonation`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client, _ ->
            assertFalse(
                client.session(f.adminCookie).canImpersonate,
                "An administrator was offered the impersonation item.",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.arm(f.adminCookie).status,
                "An administrator armed an impersonation by calling the route directly.",
            )
        }
    }

    /** Arming signs the owner out, which is the part that surprises people. */
    @Test
    fun `arming signs the owner out and hands back a grant`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client, _ ->
            val armed = client.arm(f.ownerCookie)
            val state = armed.body<SessionState>()

            assertNull(state.user, "Arming left the owner signed in, so what follows would be a costume.")
            assertTrue(state.isImpersonationArmed, "Nothing on screen would say the browser is armed.")
            assertNotNull(armed.probeCookie(), "No probe cookie came back, so there is nothing to sign in with.")

            // ...and the owner's session is genuinely gone, not merely un-cookied.
            assertNull(
                sessions.lookup(f.ownerCookie),
                "The owner's session survived arming, so the impersonation would sit on top of it.",
            )
        }
    }

    /** One live grant per owner: arming twice invalidates the first id. */
    @Test
    fun `arming again invalidates the previous grant`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client, impersonation ->
            val first = requireNotNull(client.arm(f.ownerCookie).probeCookie())
            // Back to being the owner — which is the only way to reach the arm route
            // again, since arming requires a session that owns the instance.
            val ownerAgain = requireNotNull(client.stop(first).sessionCookie())
            val second = requireNotNull(client.arm(ownerAgain).probeCookie())

            assertNull(impersonation.grants.resolve(first), "An abandoned grant was still live beside the new one.")
            assertNotNull(impersonation.grants.resolve(second), "The new grant is not live.")
        }
    }

    // ── What a grant is worth ───────────────────────────────────────────────

    /**
     * Four ways to have no grant, and one answer to all of them.
     *
     * These routes are reachable by a signed-out stranger — that is the whole shape
     * of the flow — so telling the four apart would let one probe which it had hit.
     * The distinction goes in the log and nowhere else.
     */
    @Test
    fun `signing in without a live grant is refused, however it is missing`(): Unit = runBlocking {
        seed()

        withRoutes { client, _ ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.impersonate(probeId = null, email = "outsider@example.com").status,
                "An address alone was enough to become somebody.",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.impersonate(probeId = "forged-probe-id", email = "outsider@example.com").status,
                "A forged probe cookie was honoured.",
            )
        }
    }

    /** An expired grant is refused, and collected rather than merely ignored. */
    @Test
    fun `an expired grant is refused`(): Unit = runBlocking {
        val f = seed()
        var clock = 1_000L
        val grants = ProbeGrants(now = { clock })
        val probeId = grants.arm(f.ownerId)
        clock += PROBE_GRANT_LIFETIME_MILLIS + 1

        withRoutes(impersonation = OwnerImpersonation(isEnabled = true, grants = grants)) { client, _ ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.impersonate(probeId, "outsider@example.com").status,
                "A grant past its lifetime still signed somebody in.",
            )
        }
        assertEquals(0, grants.size(), "The expired grant was left in the map rather than collected.")
    }

    /**
     * Ownership taken away mid-probe kills the grant on the next request.
     *
     * The entitlement is re-derived per use rather than remembered, which is the
     * property that stops authority being removable without removing what it
     * granted. Note the grant was armed by somebody who genuinely was the owner —
     * this is not a test that a non-owner is refused, it is a test that a *former*
     * one is.
     */
    @Test
    fun `an owner who loses the instance mid-probe loses the grant`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client, _ ->
            val probeId = requireNotNull(client.arm(f.ownerCookie).probeCookie())
            assertEquals(
                HttpStatusCode.OK,
                client.impersonate(probeId, "outsider@example.com").status,
                "The grant never worked, so the interesting half cannot be tested.",
            )

            // Handed over. Nothing else changed, and nothing told the grant.
            instanceSettings.setOwnerUserId(f.adminId)

            assertEquals(
                HttpStatusCode.Forbidden,
                client.impersonate(probeId, "pending@acme.com").status,
                "A former owner went on signing in as other people.",
            )
        }
    }

    // ── It is a real sign-in ────────────────────────────────────────────────

    /**
     * Signing in as an address with no account **creates one**, and it stays.
     *
     * The headline departure from the preview this replaced, and the whole reason
     * the facility is worth having: the row is what makes "what happens the moment
     * this address exists?" a question anybody can answer. Asserted against the
     * table rather than the response, and asserted again *after* stopping, because
     * the persistence is the point rather than an oversight.
     */
    @Test
    fun `signing in as an unknown address creates a real account that outlives the probe`(): Unit = runBlocking {
        val f = seed()
        assertNull(users.selectAll().firstOrNull { it.email == "stranger@acme.com" }, "Fixture already has the address.")

        withRoutes { client, _ ->
            val probeId = requireNotNull(client.arm(f.ownerCookie).probeCookie())
            val state = client.impersonate(probeId, "stranger@acme.com").body<SessionState>()

            assertTrue(state.isImpersonating, "A probe session did not report itself as one.")
            assertEquals("stranger", state.user?.displayName, "The address did not arrive under its own local part.")
            assertTrue(state.user?.isStaff == true, "An address at the deployment's own domain did not arrive staff.")
            assertFalse(state.user?.isSysAdmin == true, "A first arrival came in administering the instance.")

            client.stop(probeId)
        }

        val created = assertNotNull(
            users.selectAll().firstOrNull { it.email == "stranger@acme.com" },
            "Signing in as an unknown address created no account, so nothing real was tested.",
        )
        assertEquals(UserKind.STAFF, created.kind, "The kind stamp did not run on the impersonated sign-in.")
        assertTrue(created.hasSignedIn, "The row exists but records no arrival.")
    }

    /**
     * An address the deployment's admission policy refuses is refused **here**, with
     * that policy's own words.
     *
     * One of the behaviours the whole facility exists to check, so it had better not
     * be the one thing the impersonation path skips. It cannot be: both real sign-ins
     * and this one go through the same `completeSignIn`, which is why that extraction
     * was its own work package.
     */
    @Test
    fun `an address admission refuses is refused, with the real refusal`(): Unit = runBlocking {
        val f = seed()
        instanceSettings.setAdmissionPolicy(AdmissionPolicy.STAFF_DOMAIN_ONLY)
        val before = users.selectAll().map { it.id }.toSet()

        withRoutes { client, _ ->
            val probeId = requireNotNull(client.arm(f.ownerCookie).probeCookie())
            val refused = client.impersonate(probeId, "nobody@elsewhere.org")

            assertEquals(HttpStatusCode.Forbidden, refused.status, "A deployment admitted an address it forbids.")
            assertTrue(
                refused.bodyAsText().contains("does not accept new accounts"),
                "The refusal is not the admission policy's own: ${refused.bodyAsText()}",
            )
        }

        assertEquals(before, users.selectAll().map { it.id }.toSet(), "A refused address still got a row.")
    }

    /**
     * The account being worn is exactly as powerful as it really is — no more.
     *
     * Two addresses differing only in their domain, against a board the `staff`
     * audience opens. Nothing was granted to either, which is the point: it is the
     * audience row doing the work, on a genuine account, exactly as it would for
     * whoever actually owns that address.
     */
    @Test
    fun `an impersonated address sees precisely what that address may see`(): Unit = runBlocking {
        val f = seed()
        roles.setAudienceRole(f.projectId, Audience.STAFF, ProjectRole.VIEWER)

        withRoutes { client, _ ->
            val probeId = requireNotNull(client.arm(f.ownerCookie).probeCookie())

            val staffSession = requireNotNull(client.impersonate(probeId, "stranger@acme.com").sessionCookie())
            val asStaff: ProjectListState = client.get(ApiRoutes.PROJECTS) { cookie(SESSION_COOKIE, staffSession) }.body()
            assertEquals(
                listOf("Lunamux"),
                asStaff.projects.map { it.name },
                "An address at the staff domain could not see a board the staff audience opens.",
            )

            val outsideSession = requireNotNull(client.impersonate(probeId, "nobody@elsewhere.org").sessionCookie())
            val asMember: ProjectListState =
                client.get(ApiRoutes.PROJECTS) { cookie(SESSION_COOKIE, outsideSession) }.body()
            assertTrue(
                asMember.projects.isEmpty(),
                "An outside address saw a staff-only board: ${asMember.projects.map { it.name }}",
            )
        }
    }

    /**
     * Switching target destroys the session being worn first.
     *
     * One grant, never two sessions. Without the destroy, an abandoned probe session
     * would stay usable for as long as somebody held its cookie — a way to keep
     * being an account after the impersonation that produced it had moved on.
     */
    @Test
    fun `switching target leaves no session behind`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client, _ ->
            val probeId = requireNotNull(client.arm(f.ownerCookie).probeCookie())
            val first = requireNotNull(client.impersonate(probeId, "outsider@example.com").sessionCookie())
            val second = requireNotNull(
                client.impersonate(probeId, "pending@acme.com", sessionId = first).sessionCookie(),
            )

            assertNull(sessions.lookup(first), "The previous probe session outlived the switch.")
            assertNotNull(sessions.lookup(second), "The new probe session was not minted.")
        }
    }

    /**
     * A write made while probing is signed with the account, because there is one.
     *
     * Full powers, writes included, is what makes the facility answer "could this
     * person file that?" — and a write you cannot finish is not one. Both requests
     * are asserted, because filing is a draft plus a save and an earlier design got
     * the pair wrong in a way only a browser found.
     */
    @Test
    fun `an impersonated address may file an issue and save it`(): Unit = runBlocking {
        val f = seed()
        roles.setAudienceRole(f.projectId, Audience.STAFF, ProjectRole.CONTRIBUTOR)

        var filedId = 0L
        var authorId = 0L
        withRoutes { client, _ ->
            val probeId = requireNotNull(client.arm(f.ownerCookie).probeCookie())
            val worn = requireNotNull(client.impersonate(probeId, "stranger@acme.com").sessionCookie())
            authorId = requireNotNull(sessions.lookup(worn)).id

            val filed = client.post("${ApiRoutes.PROJECTS}/${f.projectId}/issues") { cookie(SESSION_COOKIE, worn) }
            assertEquals(HttpStatusCode.OK, filed.status, "A stranger the audience admits as a contributor was refused.")
            filedId = filed.body<IssueDraft>().id

            val saved = client.put("/api/issues/$filedId") {
                cookie(SESSION_COOKIE, worn)
                contentType(ContentType.Application.Json)
                setBody(
                    IssueUpdate(
                        title = "Filed while impersonating a stranger",
                        description = "",
                        statusId = statuses.forProject(f.projectId).first().id,
                        priorityId = priorities.forProject(f.projectId).first().id,
                    ),
                )
            }
            assertEquals(
                HttpStatusCode.OK,
                saved.status,
                "The impersonated address filed a draft it could not then save, which is not a write.",
            )
        }

        val issue = requireNotNull(issues.findById(filedId)) { "The route answered OK and wrote nothing." }
        assertEquals("Filed while impersonating a stranger", issue.title)
        // An ACCOUNT, not an external name. There is a row now, so `created_by` has
        // something to point at — which is what the old preview could never manage.
        assertEquals(Author.Account(authorId), issue.author, "The write is not signed by the account it was made under.")
    }

    // ── The probe label is not authority ────────────────────────────────────

    /**
     * A probe session cannot re-point the worn account's e-mail address.
     *
     * The one thing full impersonation powers must not include: e-mail is the
     * account key, so redirecting where an account's mail goes is redirecting the
     * account. **This is the test that regresses silently if the guard is left
     * reading the old `isImpersonating`** — that field meant "effective differs from
     * real", and under a genuine probe session the two are the same account, so the
     * comparison reads false and the guard never fires.
     */
    @Test
    fun `a probe session cannot change the worn account's address`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client, _ ->
            val probeId = requireNotNull(client.arm(f.ownerCookie).probeCookie())
            val worn = requireNotNull(client.impersonate(probeId, "outsider@example.com").sessionCookie())

            val refused = client.post(ApiRoutes.USER_EMAIL_REQUEST) {
                cookie(SESSION_COOKIE, worn)
                contentType(ContentType.Application.Json)
                setBody(RequestEmailChangeRequest(email = "attacker@elsewhere.org"))
            }
            assertEquals(
                HttpStatusCode.Forbidden,
                refused.status,
                "A probe session re-pointed somebody else's e-mail, which is account takeover.",
            )
        }
        assertEquals(
            "outsider@example.com",
            users.selectAll().first { it.providerId == "g-out" }.email,
            "The address moved despite the refusal.",
        )
    }

    /**
     * A probe session whose grant is gone is **destroyed**, not merely disowned.
     *
     * Inside one process lifetime, this is what catches an expired or revoked grant.
     * You are signed out, which is the correct direction: the alternative is being
     * left as somebody else with the marker off.
     */
    @Test
    fun `a probe session with no live grant is destroyed on the next request`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client, impersonation ->
            val probeId = requireNotNull(client.arm(f.ownerCookie).probeCookie())
            val worn = requireNotNull(client.impersonate(probeId, "outsider@example.com").sessionCookie())

            // The grant goes — a restart is the ordinary way, and revoking is the
            // in-process one this branch is for.
            impersonation.grants.revoke(probeId)

            val after = client.session(worn)
            assertNull(after.user, "A probe session outlived the grant that authorised it.")
            assertNull(sessions.lookup(worn), "The orphaned session was left in the table for the next request.")
        }
    }

    /**
     * ...and the boot sweep takes every one of them, **with the gate off**.
     *
     * Not gated, deliberately, and this is the assertion that keeps it that way.
     * Turning the feature off is a restart, and if the sweep asked the gate first
     * then turning it off mid-probe would strand somebody as an *ordinary* session
     * for the person they were wearing, marker gone and nothing left to notice it.
     */
    @Test
    fun `the boot sweep takes probe sessions whatever the gate says`(): Unit = runBlocking {
        val f = seed()
        var worn = ""
        withRoutes { client, _ ->
            val probeId = requireNotNull(client.arm(f.ownerCookie).probeCookie())
            worn = requireNotNull(client.impersonate(probeId, "outsider@example.com").sessionCookie())
        }
        val ordinary = sessions.create(f.adminId)

        // What Application.module runs at startup, unconditionally. The gate is not
        // consulted and this test does not offer it one.
        assertEquals(1L, sessions.deleteProbeSessions(), "The sweep did not take the probe session.")
        assertNull(sessions.lookup(worn), "A probe session survived a restart.")
        assertNotNull(sessions.lookup(ordinary), "The sweep took an ordinary session with it.")
    }

    // ── Stopping ────────────────────────────────────────────────────────────

    /** Stop restores the owner, revokes the grant, and clears the probe cookie. */
    @Test
    fun `stopping restores the owner and spends the grant`(): Unit = runBlocking {
        val f = seed()

        withRoutes { client, impersonation ->
            val probeId = requireNotNull(client.arm(f.ownerCookie).probeCookie())
            val worn = requireNotNull(client.impersonate(probeId, "outsider@example.com").sessionCookie())

            val stopped = client.stop(probeId, sessionId = worn)
            val state = stopped.body<SessionState>()

            assertEquals("Owner", state.user?.displayName, "Stopping did not put the owner back.")
            assertFalse(state.isImpersonating, "The restored owner is still reported as impersonating.")
            assertTrue(state.canImpersonate, "The restored owner cannot arm another one.")
            assertNull(sessions.lookup(worn), "The impersonated session outlived the stop.")
            assertNull(impersonation.grants.resolve(probeId), "The spent grant is still live.")
            assertEquals("", stopped.probeCookie(), "The probe cookie was not cleared.")
        }
    }

    /** A pasted sentence is a 400 rather than a sign-in as nobody. */
    @Test
    fun `something that is not an address is refused`(): Unit = runBlocking {
        val f = seed()
        val before = users.selectAll().map { it.id }.toSet()

        withRoutes { client, _ ->
            val probeId = requireNotNull(client.arm(f.ownerCookie).probeCookie())
            assertEquals(HttpStatusCode.BadRequest, client.impersonate(probeId, "not an address").status)
        }
        assertEquals(before, users.selectAll().map { it.id }.toSet(), "A malformed address got a row anyway.")
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private class Fixture(
        val ownerCookie: String,
        val ownerId: Long,
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
            ownerId = owner.id,
            adminCookie = sessions.create(admin.id),
            adminId = admin.id,
            projectId = project.id,
        )
    }

    private suspend fun HttpClient.session(cookie: String): SessionState =
        get(ApiRoutes.SESSION) { cookie(SESSION_COOKIE, cookie) }.body()

    private suspend fun HttpClient.arm(sessionId: String): HttpResponse =
        post(ApiRoutes.IMPERSONATE_ARM) { cookie(SESSION_COOKIE, sessionId) }

    /**
     * Sign in as [email] on [probeId]'s authority.
     *
     * The probe cookie is the whole authorisation, which is why it is the first
     * parameter and the session cookie is an afterthought: for most of this flow the
     * caller is signed out, and the only request that carries both is the one that
     * switches target.
     */
    private suspend fun HttpClient.impersonate(
        probeId: String?,
        email: String,
        sessionId: String? = null,
    ): HttpResponse =
        post(ApiRoutes.IMPERSONATE) {
            probeId?.let { cookie(PROBE_COOKIE, it) }
            sessionId?.let { cookie(SESSION_COOKIE, it) }
            contentType(ContentType.Application.Json)
            setBody(ImpersonateRequest(email))
        }

    private suspend fun HttpClient.stop(probeId: String?, sessionId: String? = null): HttpResponse =
        post(ApiRoutes.STOP_IMPERSONATING) {
            probeId?.let { cookie(PROBE_COOKIE, it) }
            sessionId?.let { cookie(SESSION_COOKIE, it) }
        }

    /**
     * A cookie's value out of a response's `Set-Cookie` headers, or null.
     *
     * Parsed by hand rather than through the client's cookie storage, deliberately:
     * these tests pass cookies explicitly so that each request states exactly what
     * it is presenting, which is the only way to write "with a forged probe cookie
     * and no session" as a test at all. An automatic jar would quietly supply what
     * the assertion is trying to withhold.
     *
     * Returns "" for a cleared cookie, which several assertions want to tell apart
     * from an absent one.
     */
    private fun HttpResponse.cookieValue(name: String): String? =
        headers.getAll(HttpHeaders.SetCookie)
            ?.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.substringBefore(';')

    private fun HttpResponse.probeCookie(): String? = cookieValue(PROBE_COOKIE)
    private fun HttpResponse.sessionCookie(): String? = cookieValue(SESSION_COOKIE)

    /**
     * Both route bundles over one settings store and one [OwnerImpersonation].
     *
     * The block is handed the impersonation as well as the client, because half of
     * what is worth asserting is about the grant store rather than about a response
     * — that a spent grant is gone, that an abandoned one was revoked.
     *
     * Armed by default. Almost every test here is about behaviour that only exists
     * on an armed deployment, and the two that are about the gate say so.
     */
    private fun withRoutes(
        impersonation: OwnerImpersonation = OwnerImpersonation(isEnabled = true),
        block: suspend (HttpClient, OwnerImpersonation) -> Unit,
    ) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing {
                authRoutes(
                    config = OAuthConfig(google = null),
                    sessions = sessions,
                    users = users,
                    impersonation = impersonation,
                    instanceSettings = instanceSettings,
                    identity = identity,
                    access = access,
                )
                boardRoutes(dependencies(impersonation))
            }
        }
        block(createClient { install(ClientContentNegotiation) { json() } }, impersonation)
    }

    private fun dependencies(impersonation: OwnerImpersonation) = BoardDependencies(
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
        impersonation = impersonation,
        instanceSettings = instanceSettings,
        identity = identity,
        subscriptions = SubscriptionStore(database),
        reads = ReadStore(database),
        history = IssueHistory(IssueEventStore(database), statuses, labels, components, users),
    )
}
