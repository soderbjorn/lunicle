/**
 * Admission and the public-projects veto, through the real routes (LNL-192).
 *
 * [InstanceIdentityTest] pins the rules as functions of their inputs. This file pins
 * that the *routes* ask them — which is the property that matters, because a handler
 * that never consulted the rule would pass every assertion in that file and ship an
 * open endpoint. The standard here; see AdminSettingsTest's preamble.
 *
 * Three things:
 *
 *  - the settings response carries the greying **and its reasons**, so ticket 5's
 *    screen renders what it is handed rather than re-deriving a rule from
 *    configuration a browser has no business being shown;
 *  - the write refuses a policy the deployment cannot honour, so the greying is a
 *    rule and not an affordance;
 *  - the sign-in path refuses a *new* account the policy excludes, while a returning
 *    one is untouched — admission is asked once, at creation.
 *
 * @see InstanceIdentity
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.lunicle.clientserver.AdminSettingsState
import se.soderbjorn.lunicle.clientserver.AdmissionPolicy
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.EmailSignInRedeemRequest
import se.soderbjorn.lunicle.clientserver.EmailSignInRequest
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
import se.soderbjorn.lunicle.clientserver.SetAdmissionPolicyRequest
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

class AdmissionRoutesTest {
    private val file: File = Files.createTempFile("lunicle-admission", ".db").toFile().also { it.delete() }
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
    private val sent = mutableListOf<Pair<String, String>>()

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── What the settings response carries ──────────────────────────────────

    /**
     * An unbranded instance offers "anyone", and greys the two policies that name a
     * domain it does not have — each with the sentence explaining why.
     */
    @Test
    fun `the settings response carries the greying and its reasons`(): Unit = runBlocking {
        val fixture = seed()
        val cookie = sessions.create(fixture.adminId)

        withRoutes(InstanceIdentity()) { client ->
            val state = client.get(ApiRoutes.ADMIN_SETTINGS) { cookie(SESSION_COOKIE, cookie) }
                .body<AdminSettingsState>()

            assertEquals(AdmissionPolicy.ANYONE, state.admission.selected)
            assertEquals(
                AdmissionPolicy.entries,
                state.admission.options.map { it.policy },
                "An option was filtered out of the wire rather than greyed.",
            )
            assertTrue(state.admission.options.first { it.policy == AdmissionPolicy.ANYONE }.isSelectable)
            val staffOnly = state.admission.options.first { it.policy == AdmissionPolicy.STAFF_DOMAIN_ONLY }
            assertFalse(staffOnly.isSelectable)
            assertNotNull(staffOnly.unavailableReason, "A greyed option crossed the wire with no reason.")
        }
    }

    /**
     * A stored policy the configuration has since stranded is still the selection,
     * greyed, with its reason — rather than silently falling back to something the
     * administrator never chose.
     */
    @Test
    fun `a stored policy that became unreachable still reports as the selection`(): Unit = runBlocking {
        val fixture = seed()
        val cookie = sessions.create(fixture.adminId)
        instanceSettings.setAdmissionPolicy(AdmissionPolicy.ANYONE)

        // The configuration changes underneath it: the chooser is now pinned AND the
        // mailed code is gone. Both, because either alone leaves `anyone` perfectly
        // honourable — see InstanceIdentity.outsiderCanArrive (LNL-195).
        withRoutes(
            InstanceIdentity(domain = "acme.com", onlyHostedGoogleAccounts = true, isCodeSignInAvailable = false),
        ) { client ->
            val admission = client.get(ApiRoutes.ADMIN_SETTINGS) { cookie(SESSION_COOKIE, cookie) }
                .body<AdminSettingsState>().admission

            assertEquals(AdmissionPolicy.ANYONE, admission.selected, "The stranded selection was replaced.")
            val option = admission.options.first { it.policy == AdmissionPolicy.ANYONE }
            assertFalse(option.isSelectable)
            assertEquals(
                "Not available here: Google sign-in is locked to acme.com, and this deployment cannot mail a sign-in code.",
                option.unavailableReason,
            )
        }
    }

    // ── The write ───────────────────────────────────────────────────────────

    /** A policy this deployment can honour is stored, and the response reports it. */
    @Test
    fun `the admin sets an honourable policy`(): Unit = runBlocking {
        val fixture = seed()
        val cookie = sessions.create(fixture.adminId)

        withRoutes(InstanceIdentity(domain = "acme.com")) { client ->
            val response = client.post(ApiRoutes.ADMIN_ADMISSION) {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(SetAdmissionPolicyRequest(AdmissionPolicy.STAFF_DOMAIN_ONLY))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                AdmissionPolicy.STAFF_DOMAIN_ONLY,
                response.body<AdminSettingsState>().admission.selected,
                "The write did not report the policy it had just set.",
            )
        }
        assertEquals(AdmissionPolicy.STAFF_DOMAIN_ONLY, instanceSettings.current().admission)
    }

    /**
     * A greyed policy is refused, and nothing is stored.
     *
     * The whole point of computing the greying server-side: a hand-written POST that
     * could still set it would make the greying an affordance. Checked against the
     * store as well as the status, because a 409 that had already written would still
     * read as a 409.
     */
    @Test
    fun `a policy this deployment cannot honour is refused, and nothing is stored`(): Unit = runBlocking {
        val fixture = seed()
        val cookie = sessions.create(fixture.adminId)

        // The branded shape — pinned chooser, no mailed code — which is the one that
        // genuinely cannot admit an outsider. See LNL-195.
        withRoutes(
            InstanceIdentity(domain = "acme.com", onlyHostedGoogleAccounts = true, isCodeSignInAvailable = false),
        ) { client ->
            val response = client.post(ApiRoutes.ADMIN_ADMISSION) {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(SetAdmissionPolicyRequest(AdmissionPolicy.ANYONE))
            }
            assertEquals(HttpStatusCode.Conflict, response.status)
        }
        assertEquals(
            AdmissionPolicy.ANYONE,
            instanceSettings.current().admission,
            "The refused write stored the policy anyway.",
        )
    }

    // ── The public-projects veto ────────────────────────────────────────────

    /**
     * With "allow projects to be public" off — the default — **granting** a guest audience
     * row is refused, whoever asks.
     *
     * Asserted at [AccessControl], which is where the refusal lives and what every
     * future write will have to ask: the parameter is the audience, so a caller cannot
     * ask the general question and then write the guest row. The Access list that
     * greys it arrives in ticket 5; this is the half a POST cannot go around.
     *
     * Narrowed to a grant by LNL-203, and the withdrawal is asserted here beside it: this
     * used to refuse *any* write to the row, which cannot tell handing public access out
     * from taking it back — so the control meant to stop public projects removed the only
     * in-app way to close one. What actually stops strangers reading is the access rule
     * itself; see [se.soderbjorn.lunicle.store.PublicProjectVetoContract].
     */
    @Test
    fun `the public-projects veto refuses a guest audience row`(): Unit = runBlocking {
        val fixture = seed()
        val owner = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-owner", "Owner", null))
        roles.setRole(owner.id, fixture.projectId, ProjectRole.OWNER)

        assertFalse(
            access.canSetAudience(owner, fixture.projectId, Audience.GUEST, ProjectRole.VIEWER),
            "A project owner published a board while the instance forbids it.",
        )
        val admin = users.findById(fixture.adminId)!!
        assertFalse(
            access.canSetAudience(admin, fixture.projectId, Audience.GUEST, ProjectRole.VIEWER),
            "The veto is not a veto if the instance owner can walk past it.",
        )

        // The other two audiences are untouched: "nothing may be published" is a
        // statement about strangers, not about the people who already have accounts.
        assertTrue(access.canSetAudience(owner, fixture.projectId, Audience.MEMBER, ProjectRole.CONTRIBUTOR))
        assertTrue(access.canSetAudience(owner, fixture.projectId, Audience.STAFF, ProjectRole.CONTRIBUTOR))

        // And so is withdrawal, in the direction the policy is not about (LNL-203).
        assertTrue(
            access.canSetAudience(owner, fixture.projectId, Audience.GUEST, rung = null),
            "The veto refused to let an owner close their own board.",
        )
        assertTrue(
            access.canSetAudience(admin, fixture.projectId, Audience.GUEST, rung = null),
            "The veto refused the instance owner the withdrawal too.",
        )

        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)
        assertTrue(
            access.canSetAudience(owner, fixture.projectId, Audience.GUEST, ProjectRole.VIEWER),
            "Allowing public projects did not let the owner publish one.",
        )
    }

    /** And the rung question is still asked: the veto lifting does not make everybody an owner. */
    @Test
    fun `lifting the veto does not let a non-owner set the guest audience`(): Unit = runBlocking {
        val fixture = seed()
        val contributor = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-c", "Contributor", null))
        roles.setRole(contributor.id, fixture.projectId, ProjectRole.CONTRIBUTOR)
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)

        assertFalse(access.canSetAudience(contributor, fixture.projectId, Audience.GUEST, ProjectRole.VIEWER))
        assertFalse(access.canSetAudience(contributor, fixture.projectId, Audience.MEMBER, ProjectRole.VIEWER))
    }

    // ── Admission at the door ───────────────────────────────────────────────

    /**
     * Under "staff domain only", an outside address that has never been here is
     * refused an account — and no row is written.
     */
    @Test
    fun `a new outside account is refused under the staff-domain policy`(): Unit = runBlocking {
        seed()
        instanceSettings.setAdmissionPolicy(AdmissionPolicy.STAFF_DOMAIN_ONLY)

        withRoutes(InstanceIdentity(domain = "acme.com")) { client ->
            val response = client.signIn("outsider@example.com")
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
        assertNull(
            users.findExisting(ProviderIdentity(AuthProvider.EMAIL, "outsider@example.com", "o", "outsider@example.com")),
            "A refused sign-in created the account anyway.",
        )
    }

    /** An address on the deployment's own domain gets in under the same policy. */
    @Test
    fun `an address on the domain is admitted under the staff-domain policy`(): Unit = runBlocking {
        seed()
        instanceSettings.setAdmissionPolicy(AdmissionPolicy.STAFF_DOMAIN_ONLY)

        withRoutes(InstanceIdentity(domain = "acme.com")) { client ->
            assertEquals(HttpStatusCode.OK, client.signIn("boss@acme.com").status)
        }
        val created = users.findExisting(
            ProviderIdentity(AuthProvider.EMAIL, "boss@acme.com", "boss", "boss@acme.com"),
        )
        assertNotNull(created)
        assertEquals(UserKind.STAFF, created.kind, "The admitted account did not land on the staff tier.")
    }

    /**
     * Tightening the policy does not lock out somebody who already has an account.
     *
     * Admission is asked once, when a row would be created. Anything else would make
     * a configuration change a mass eviction, which is not what "who may hold an
     * account" means.
     */
    @Test
    fun `an existing outside account still signs in after the policy tightens`(): Unit = runBlocking {
        seed()
        // They arrived while the door was open.
        withRoutes(InstanceIdentity(domain = "acme.com")) { client ->
            assertEquals(HttpStatusCode.OK, client.signIn("outsider@example.com").status)
        }

        instanceSettings.setAdmissionPolicy(AdmissionPolicy.STAFF_DOMAIN_ONLY)
        withRoutes(InstanceIdentity(domain = "acme.com")) { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.signIn("outsider@example.com").status,
                "A policy change evicted an account that already existed.",
            )
        }
    }

    /**
     * THE test the plus-added policy never had (LNL-194).
     *
     * `staff domain plus added` behaved **identically** to `staff domain only` until this
     * ticket, because `isAlreadyAdded` was a parameter nothing set. So the two policies
     * are asserted side by side, on the same address, and the only difference between the
     * two halves is which policy is stored: under the strict one the added outsider is
     * refused, under the plus-added one they are let in.
     *
     * If somebody makes `admissionRefusal` stop passing the flag, the second half fails
     * and the first still passes — which is the shape that catches a regression rather
     * than merely covering the line.
     */
    @Test
    fun `an added outsider gets in under plus-added and not under staff-domain-only`(): Unit = runBlocking {
        seed()
        // The gesture from the Access section: a row for an address nobody has used.
        val added = users.addByEmail("added@example.com", UserKind.MEMBER)
        assertFalse(added.hasSignedIn, "addByEmail wrote a row that claims somebody has arrived.")

        instanceSettings.setAdmissionPolicy(AdmissionPolicy.STAFF_DOMAIN_ONLY)
        withRoutes(InstanceIdentity(domain = "acme.com")) { client ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.signIn("added@example.com").status,
                "The strict policy admitted an outside address merely because somebody added it.",
            )
        }

        instanceSettings.setAdmissionPolicy(AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED)
        withRoutes(InstanceIdentity(domain = "acme.com")) { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.signIn("added@example.com").status,
                "The plus-added policy refused an address that had been added, which is the one " +
                    "case it exists for.",
            )
        }
        // And they landed in the row that was already holding their grants, rather than
        // beside it.
        val after = users.findExisting(
            ProviderIdentity(AuthProvider.EMAIL, "added@example.com", "added", "added@example.com"),
        )
        assertNotNull(after)
        assertEquals(added.id, after.id, "Signing in made a second account instead of adopting the added row.")
        assertTrue(after.hasSignedIn, "The arrival was not stamped.")
    }

    /**
     * An address nobody added is still refused under plus-added — the policy widens by
     * exactly one case and not by "anybody who asks twice".
     */
    @Test
    fun `plus-added still refuses an outsider nobody added`(): Unit = runBlocking {
        seed()
        instanceSettings.setAdmissionPolicy(AdmissionPolicy.STAFF_DOMAIN_PLUS_ADDED)
        withRoutes(InstanceIdentity(domain = "acme.com")) { client ->
            assertEquals(HttpStatusCode.Forbidden, client.signIn("stranger@example.com").status)
        }
    }

    /** With no domain configured there is no staff tier, so the door stays open to everybody. */
    @Test
    fun `an unbranded instance admits anybody`(): Unit = runBlocking {
        seed()
        withRoutes(InstanceIdentity()) { client ->
            assertEquals(HttpStatusCode.OK, client.signIn("anyone@example.com").status)
        }
        val created = users.findExisting(
            ProviderIdentity(AuthProvider.EMAIL, "anyone@example.com", "anyone", "anyone@example.com"),
        )
        assertNotNull(created)
        assertEquals(UserKind.MEMBER, created.kind, "An instance with no domain invented a staff tier.")
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private class Fixture(val adminId: Long, val projectId: Long)

    private suspend fun seed(): Fixture {
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin", "Admin", null))
        val project = projectRepository.create("Lunamux", "LMX")
        seatInstanceOwner(users, instanceSettings)
        return Fixture(admin.id, project.id)
    }

    /** Ask for a code and spend it — the whole e-mail sign-in, as one call. */
    private suspend fun HttpClient.signIn(address: String): HttpResponse {
        post(ApiRoutes.AUTH_EMAIL_REQUEST) {
            contentType(ContentType.Application.Json)
            setBody(EmailSignInRequest(address))
        }
        val code = Regex("\\d{6}").find(sent.last().second)?.value ?: error("No code was mailed.")
        return post(ApiRoutes.AUTH_EMAIL_REDEEM) {
            contentType(ContentType.Application.Json)
            setBody(EmailSignInRedeemRequest(address, code))
        }
    }

    /**
     * Both route bundles over the one settings store, with [identity] as the
     * deployment's configuration — the parameter that is the whole subject here.
     */
    private fun withRoutes(identity: InstanceIdentity, block: suspend (HttpClient) -> Unit) = testApplication {
        val impersonation = OwnerImpersonation()
        application {
            install(ServerContentNegotiation) { json() }
            routing {
                authRoutes(
                    config = OAuthConfig(google = null, isEmailAvailable = true),
                    sessions = sessions,
                    users = users,
                    impersonation = impersonation,
                    emailCodes = EmailCodeService(database, capturingSender(), "https://issues.example.com"),
                    instanceSettings = instanceSettings,
                    identity = identity,
                )
                boardRoutes(dependencies(impersonation, identity))
            }
        }
        block(createClient { install(ClientContentNegotiation) { json() } })
    }

    /** A real transport whose HTTP client records rather than sends. See EmailSignInTest. */
    private fun capturingSender(): ResendEmailTransport = ResendEmailTransport(
        config = ResendConfig(apiKey = "test-key", from = "Lunicle <noreply@example.com>"),
        httpClient = HttpClient(
            MockEngine { request ->
                val payload = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
                sent += payload["to"]!!.jsonArray.first().jsonPrimitive.content to
                    payload["subject"]!!.jsonPrimitive.content
                respond(
                    content = """{"id":"test"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ClientContentNegotiation) { json() }
        },
    )

    private fun dependencies(impersonation: OwnerImpersonation, identity: InstanceIdentity) = BoardDependencies(
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
