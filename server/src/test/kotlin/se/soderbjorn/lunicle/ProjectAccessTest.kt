/**
 * The Access section and the rail's sections, through the real routes (LNL-194).
 *
 * [AccessControlLadderTest] pins the rules as functions of their inputs. This file
 * pins that the *routes* ask them and that the *response* says the same thing the
 * write would — which is the property that matters here, because this ticket's whole
 * design is "the greying is computed where the refusal lives". A screen that decided
 * for itself would pass every assertion in that file and offer a control the route
 * refuses.
 *
 * Five things:
 *
 *  - **Sections are per project and per rung.** A Viewer gets one section, a
 *    Maintainer gets General read-only, an Admin gets Structure, an Owner gets Github.
 *    Asserted off the response, because the rail draws what it is handed.
 *  - **The Access section stops at Maintainer.** It carries e-mail addresses, so a
 *    Viewer must not receive it — omitted, not sent-and-hidden.
 *  - **Audiences are the owner's, and the veto outranks them.** Including for an
 *    instance administrator, which is the case a screen-level rule would miss.
 *  - **The guests row stops at Viewer** (LNL-202), whoever asks and whatever the switches
 *    say. Asserted at both ends — the POST refuses it with a reason, and the row's own rung
 *    list offers Viewer alone with the rest greyed — because the whole defect was a picker
 *    offering a rung nothing refused.
 *  - **A rung is refused at both ends.** An Admin may hand out Maintainer and may not
 *    write Viewer over an Owner's row, which is the same escalation running downhill.
 *  - **The veto refuses a grant and never a withdrawal** (LNL-203), and a guest row it has
 *    silenced is reported as *stored but not in effect* with a "Visible to" line that agrees.
 *    The board is published first in those tests, because a board with no guest row has
 *    nothing to withdraw and cannot reach the bug.
 *
 * @see AccessControl.canGrant
 * @see AccessControl.canSetAudience
 */
package se.soderbjorn.lunicle

import io.ktor.client.call.body
import io.ktor.client.request.cookie
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
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AudienceGrant
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.InstanceSettingKey
import se.soderbjorn.lunicle.clientserver.PersonAdd
import se.soderbjorn.lunicle.clientserver.ProjectDisplaySettings
import se.soderbjorn.lunicle.clientserver.ProjectSectionKeys
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.RungGrant
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

class ProjectAccessTest {
    private val file: File = Files.createTempFile("lunicle-project-access", ".db").toFile().also { it.delete() }
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
    private val instanceSettings = InstanceSettingsStore(database)
    private val access = AccessControl(roles, instanceSettings)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── The rail's sections ──────────────────────────────────────────────────

    /**
     * One section for a Viewer, and it is Access relabelled.
     *
     * A rail offering a Viewer General with every field dead would be four screens of
     * things that are not theirs; the one section they get is a statement about
     * themselves. Asserted on the *label* as well as the key, because relabelling is how
     * one section serves both readings.
     */
    @Test
    fun `a viewer gets one section, and it is their own access`(): Unit = runBlocking {
        val f = seed()
        val state = settingsFor(f.viewerCookie, f.projectId)
        assertEquals(
            listOf(ProjectSectionKeys.ACCESS),
            state.sections.map { it.key },
            "A viewer was offered sections they have nothing to do in.",
        )
        assertEquals("Your access", state.sections.single().label)
        assertTrue(state.yourAccessLine.contains("Viewer"), "The line did not name the rung they hold.")
    }

    /**
     * A Maintainer sees General, and it is read-only — which is the ticket's headline:
     * read-only is not absent.
     */
    @Test
    fun `a maintainer sees general and cannot change any of it`(): Unit = runBlocking {
        val f = seed()
        val state = settingsFor(f.maintainerCookie, f.projectId)
        assertEquals(
            listOf(
                ProjectSectionKeys.GENERAL,
                ProjectSectionKeys.SPRINTS,
                ProjectSectionKeys.VERSIONS,
                ProjectSectionKeys.ACCESS,
            ),
            state.sections.map { it.key },
            "A maintainer's sections are not the four their rung reaches.",
        )
        assertFalse(state.canMutateProject, "A maintainer was told they may configure the board.")
        // The half LNL-196 fixed: the two sections a maintainer is offered are the two they
        // may edit, and they used to arrive empty because both lists were behind the admin
        // gate. A section that renders nothing is worse than one that is not offered.
        assertTrue(
            state.canMutateProjectPlanning,
            "A maintainer was offered Sprints and Versions and told they may not change them.",
        )
        assertNull(state.planningReadOnlyReason)
        assertTrue(state.sprints.isNotEmpty(), "A maintainer's Sprints section arrived empty.")
        assertTrue(state.versions.isNotEmpty(), "A maintainer's Versions section arrived empty.")
        assertFalse(state.canMutateProjectIdentity, "A maintainer was offered the project's name.")
        assertFalse(state.canDeleteProject)
        assertNull(state.deleteBlockedReason, "A maintainer was explained a power two rungs up.")
    }

    /** Structure is an Admin's, Github is an Owner's, and both are asserted by absence. */
    @Test
    fun `structure arrives with admin and github with owner`(): Unit = runBlocking {
        val f = seed()
        val admin = settingsFor(f.adminCookie, f.projectId).sections.map { it.key }
        assertTrue(ProjectSectionKeys.STRUCTURE in admin, "An admin lost the vocabularies.")
        assertFalse(ProjectSectionKeys.GITHUB in admin, "An admin was offered the repository and its token.")
        assertNotNull(
            settingsFor(f.adminCookie, f.projectId).deleteBlockedReason,
            "An admin was not told why Delete is not theirs, which is the one rung that asks.",
        )

        val owner = settingsFor(f.ownerCookie, f.projectId).sections.map { it.key }
        assertEquals(
            listOf(
                ProjectSectionKeys.GENERAL,
                ProjectSectionKeys.GITHUB,
                ProjectSectionKeys.STRUCTURE,
                ProjectSectionKeys.SPRINTS,
                ProjectSectionKeys.VERSIONS,
                ProjectSectionKeys.ACCESS,
            ),
            owner,
            "An owner's rail is not the whole of it, in order.",
        )
    }

    // ── What the Access section carries, and to whom ─────────────────────────

    /**
     * The Access section stops at Maintainer, because it carries addresses.
     *
     * Omitted rather than sent-and-flagged: a field the caller may not have does not
     * travel and get hidden by the browser.
     */
    @Test
    fun `the access section is withheld below maintainer`(): Unit = runBlocking {
        val f = seed()
        assertNull(settingsFor(f.viewerCookie, f.projectId).access, "A viewer received other people's addresses.")
        val maintainer = settingsFor(f.maintainerCookie, f.projectId).access
        assertNotNull(maintainer, "A maintainer cannot see who is on the board they run.")
        assertFalse(maintainer.canGrant, "A maintainer was told they may change grants.")
        assertNotNull(maintainer.readOnlyReason, "The read-only Access section did not say why.")
    }

    /**
     * Person rows are the **exceptions**, not a directory.
     *
     * The outsider holds nothing and is admitted by no audience row, so they must not
     * appear; the three people with rungs must. This is the assertion that stops the old
     * "every account on the instance" table growing back.
     */
    @Test
    fun `person rows are the exceptions and not a directory`(): Unit = runBlocking {
        val f = seed()
        val listed = settingsFor(f.ownerCookie, f.projectId).access!!.people.map { it.userId }
        assertTrue(f.ownerId in listed && f.adminId in listed && f.maintainerId in listed)
        assertFalse(f.outsiderId in listed, "An account with nothing here was listed as an exception.")
        // The instance administrator is listed, with a note instead of a picker: they
        // hold Owner everywhere without a row, which is a fact worth seeing on the audit.
        val sysAdminRow = settingsFor(f.ownerCookie, f.projectId).access!!.people
            .firstOrNull { it.userId == f.sysAdminId }
        assertNotNull(sysAdminRow, "The instance administrator, who holds Owner here, was invisible.")
        assertFalse(sysAdminRow.isEditable)
        assertNotNull(sysAdminRow.note)
    }

    /** With no domain configured there is no staff audience to offer. Two rows, not three. */
    @Test
    fun `an unbranded deployment offers two audience rows`(): Unit = runBlocking {
        val f = seed()
        assertEquals(
            listOf(Audience.GUEST.key, Audience.MEMBER.key),
            settingsFor(f.ownerCookie, f.projectId).access!!.audiences.map { it.key },
            "A staff row was offered on a deployment that cannot tell its own people apart.",
        )
    }

    // ── Audiences ────────────────────────────────────────────────────────────

    /** An owner says at what rung a whole audience arrives. */
    @Test
    fun `an owner admits every member as a contributor`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val response = client.post(ApiRoutes.projectAudience(f.projectId)) {
                cookie(SESSION_COOKIE, f.ownerCookie)
                contentType(ContentType.Application.Json)
                setBody(AudienceGrant(Audience.MEMBER.key, ProjectRole.CONTRIBUTOR.key))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        assertEquals(ProjectRole.CONTRIBUTOR, roles.audienceRoles(f.projectId)[Audience.MEMBER])
        // And the outsider, who holds nothing, can now file issues here without a row.
        val outsider = users.findById(f.outsiderId)!!
        assertEquals(ProjectRole.CONTRIBUTOR, access.effectiveRole(outsider, f.projectId))
    }

    /** An Admin may not — visibility is the owner's, and the row says so rather than vanishing. */
    @Test
    fun `an admin cannot change who the project admits, and is told whose it is`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val response = client.post(ApiRoutes.projectAudience(f.projectId)) {
                cookie(SESSION_COOKIE, f.adminCookie)
                contentType(ContentType.Application.Json)
                setBody(AudienceGrant(Audience.MEMBER.key, ProjectRole.VIEWER.key))
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
        assertNull(roles.audienceRoles(f.projectId)[Audience.MEMBER], "The refused write landed anyway.")
        val row = settingsFor(f.adminCookie, f.projectId).access!!.audiences
            .first { it.key == Audience.MEMBER.key }
        assertFalse(row.isSelectable)
        assertNotNull(row.unavailableReason, "A dead audience row crossed the wire with no reason.")
    }

    /**
     * The publish veto outranks ownership — **and the instance administrator**.
     *
     * The case a screen-level rule would miss, and the reason the refusal is in
     * `canSetAudience` rather than only in the greying: a rule that lives in a screen is
     * a rule a POST goes around.
     *
     * Note what the greying now looks like (LNL-203). The **row** stays live and every
     * **rung** in it is dead, which leaves "No access" as the one thing the picker will
     * accept: refusing to hand out public access is the policy, and refusing to take it back
     * was only a bug. A dead row here left the owner of an already-published board with no
     * in-app way to close it.
     */
    @Test
    fun `the publish veto refuses a guest row for everybody`(): Unit = runBlocking {
        val f = seed()
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, false)
        withRoutes { client ->
            listOf("owner" to f.ownerCookie, "instance owner" to f.sysAdminCookie).forEach { (who, cookie) ->
                val response = client.post(ApiRoutes.projectAudience(f.projectId)) {
                    cookie(SESSION_COOKIE, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(AudienceGrant(Audience.GUEST.key, ProjectRole.VIEWER.key))
                }
                assertEquals(HttpStatusCode.Forbidden, response.status, "The veto did not hold against the $who.")
            }
            // The members row is unaffected: "nothing may be published" is a statement about
            // strangers, not about the people who already have accounts here.
            val members = client.post(ApiRoutes.projectAudience(f.projectId)) {
                cookie(SESSION_COOKIE, f.ownerCookie)
                contentType(ContentType.Application.Json)
                setBody(AudienceGrant(Audience.MEMBER.key, ProjectRole.VIEWER.key))
            }
            assertEquals(HttpStatusCode.OK, members.status, "The publish veto also closed the members row.")
        }
        val guests = settingsFor(f.ownerCookie, f.projectId).access!!.audiences
            .first { it.key == Audience.GUEST.key }
        assertTrue(
            guests.isSelectable,
            "The whole row was greyed, so nobody could set it to No access — which is LNL-203.",
        )
        assertTrue(
            guests.rungs.none { it.isSelectable },
            "A rung was offered on a deployment where every guest rung is refused.",
        )
        assertEquals(
            ProjectRole.entries.map { it.key },
            guests.rungs.map { it.key },
            "A refused rung was omitted rather than greyed, which reads as a bug.",
        )
        assertTrue(
            guests.rungs.all { it.unavailableReason != null },
            "A dead rung crossed the wire with no sentence saying why.",
        )
        assertTrue(
            guests.unavailableReason.orEmpty().contains("public"),
            "The row did not name the deployment's veto as the reason.",
        )
    }

    /**
     * The withdrawal the veto used to refuse, through the route (LNL-203).
     *
     * The board is published first, then the deployment changes its mind — which is the
     * order the ticket was filed in and the only order that reaches the bug: a board with no
     * guest row has nothing to withdraw. Both halves are asserted, because they were one
     * defect: the veto did not make the board private, *and* it took away the owner's way to.
     */
    @Test
    fun `an owner can close a published board after the deployment forbids public ones`(): Unit = runBlocking {
        val f = seed()
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)
        roles.setAudienceRole(f.projectId, Audience.GUEST, ProjectRole.VIEWER)
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, false)

        withRoutes { client ->
            // Nobody outside the board reads it any more, cookie or no cookie — the veto is a
            // term in the rule now rather than a guard on this route.
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.board(f.projectId)).status,
                "The switch went off and a caller with no session still read the board.",
            )
            // And the owner is not stuck: "No access" lands.
            val withdrawn = client.post(ApiRoutes.projectAudience(f.projectId)) {
                cookie(SESSION_COOKIE, f.ownerCookie)
                contentType(ContentType.Application.Json)
                setBody(AudienceGrant(Audience.GUEST.key, roleKey = null))
            }
            assertEquals(
                HttpStatusCode.OK,
                withdrawn.status,
                "The owner of a published board was refused the one write that closes it.",
            )
        }
        assertNull(roles.audienceRoles(f.projectId)[Audience.GUEST], "The withdrawal did not land.")
    }

    /**
     * A stored guest row the veto has silenced is reported as **stored but not in effect**,
     * and the summary agrees (LNL-203).
     *
     * The screen half of the fix. An owner reading this section has to be able to tell what
     * a stranger can actually see — a picker still reading "Viewer" beside a "Visible to
     * anybody at all" line would leave the original complaint intact in a different place.
     * So the rung is sent as stored (nothing withdrew it, and lifting the veto restores it),
     * the row says out loud that it is not in effect, and the summary refuses to count it.
     */
    @Test
    fun `a silenced guest row is reported as stored but not in effect`(): Unit = runBlocking {
        val f = seed()
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)
        roles.setAudienceRole(f.projectId, Audience.GUEST, ProjectRole.VIEWER)

        val published = settingsFor(f.ownerCookie, f.projectId).access!!
        assertTrue(
            published.visibilityLine.contains("anybody at all"),
            "A genuinely public board did not say so: \"${published.visibilityLine}\".",
        )

        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, false)
        val silenced = settingsFor(f.ownerCookie, f.projectId).access!!
        val guests = silenced.audiences.first { it.key == Audience.GUEST.key }
        assertEquals(
            ProjectRole.VIEWER.key,
            guests.roleKey,
            "The stored row was blanked, so an owner would think the board was already closed.",
        )
        assertTrue(
            guests.unavailableReason.orEmpty().contains("not in effect"),
            "The row claimed Viewer with nothing saying it is not in effect: " +
                "\"${guests.unavailableReason}\".",
        )
        assertFalse(
            silenced.visibilityLine.contains("anybody at all"),
            "The summary still called a private board public: \"${silenced.visibilityLine}\".",
        )
    }

    /**
     * The guest row stops at Viewer, and the picker says so (LNL-202).
     *
     * The other refusal on this row, and the one no switch lifts: public projects are
     * *allowed* here, the caller owns the board, and a rung above Viewer is still refused —
     * because a guest has no account and every rung above Viewer describes writing.
     *
     * Both ends asserted in one test on purpose. The write is what a POST cannot go around;
     * the response is what keeps the browser from inviting the click, and the two have to
     * agree or one of them is a lie. Note the row itself stays **live** with only its higher
     * rungs greyed, rather than the whole row dying: setting the guest row *to* Viewer is
     * exactly how a board is published.
     */
    @Test
    fun `the guest row offers viewer only, and refuses more with the reason`(): Unit = runBlocking {
        val f = seed()
        instanceSettings.set(InstanceSettingKey.ALLOW_PUBLIC_PROJECTS, true)
        withRoutes { client ->
            listOf(ProjectRole.CONTRIBUTOR, ProjectRole.MAINTAINER, ProjectRole.ADMIN, ProjectRole.OWNER)
                .forEach { rung ->
                    val response = client.post(ApiRoutes.projectAudience(f.projectId)) {
                        cookie(SESSION_COOKIE, f.ownerCookie)
                        contentType(ContentType.Application.Json)
                        setBody(AudienceGrant(Audience.GUEST.key, rung.key))
                    }
                    assertEquals(
                        HttpStatusCode.Forbidden,
                        response.status,
                        "An owner handed guests ${rung.key} — a write with nobody behind it.",
                    )
                    assertTrue(
                        response.bodyAsText().contains("attribute"),
                        "The refusal did not say what is missing: somebody to attribute the write to.",
                    )
                }
            assertNull(roles.audienceRoles(f.projectId)[Audience.GUEST], "A refused guest row landed anyway.")

            // And Viewer is accepted, because publishing a board is the feature.
            assertEquals(
                HttpStatusCode.OK,
                client.post(ApiRoutes.projectAudience(f.projectId)) {
                    cookie(SESSION_COOKIE, f.ownerCookie)
                    contentType(ContentType.Application.Json)
                    setBody(AudienceGrant(Audience.GUEST.key, ProjectRole.VIEWER.key))
                }.status,
                "The ceiling took away publishing, which is the one thing the row is for.",
            )
            assertEquals(ProjectRole.VIEWER, roles.audienceRoles(f.projectId)[Audience.GUEST])
        }

        val section = settingsFor(f.ownerCookie, f.projectId).access!!
        val guests = section.audiences.first { it.key == Audience.GUEST.key }
        assertTrue(guests.isSelectable, "The whole guest row died; only the rungs above Viewer should.")
        assertEquals(
            listOf(ProjectRole.VIEWER.key),
            guests.rungs.filter { it.isSelectable }.map { it.key },
            "The guests row offered a rung other than Viewer, or stopped offering Viewer.",
        )
        guests.rungs.filter { !it.isSelectable }.forEach {
            assertNotNull(it.unavailableReason, "A dead rung crossed the wire with no reason on it.")
        }
        assertEquals(
            ProjectRole.entries.map { it.key },
            guests.rungs.map { it.key },
            "A rung out of reach was omitted rather than greyed, which reads as a bug.",
        )
        // The members row is untouched: an account can be attributed, so nothing narrows it.
        assertEquals(
            ProjectRole.entries.map { it.key },
            section.audiences.first { it.key == Audience.MEMBER.key }.rungs
                .filter { it.isSelectable }.map { it.key },
            "The ceiling leaked onto an audience that has accounts behind it.",
        )
        // And the person rows are untouched, for the same reason.
        assertEquals(
            ProjectRole.entries.map { it.key },
            section.rungs.filter { it.isSelectable }.map { it.key },
            "The ceiling leaked onto the person rows, which are about accounts.",
        )
    }

    // ── Rungs ────────────────────────────────────────────────────────────────

    /** An Admin hands out up to Maintainer, and "no access" removes the row. */
    @Test
    fun `an admin grants a rung and takes it away`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.post(ApiRoutes.projectRoles(f.projectId)) {
                    cookie(SESSION_COOKIE, f.adminCookie)
                    contentType(ContentType.Application.Json)
                    setBody(RungGrant(f.outsiderId, ProjectRole.MAINTAINER.key))
                }.status,
            )
            assertEquals(ProjectRole.MAINTAINER, roles.roleFor(f.outsiderId, f.projectId))

            assertEquals(
                HttpStatusCode.OK,
                client.post(ApiRoutes.projectRoles(f.projectId)) {
                    cookie(SESSION_COOKIE, f.adminCookie)
                    contentType(ContentType.Application.Json)
                    setBody(RungGrant(f.outsiderId, null))
                }.status,
            )
        }
        assertNull(roles.roleFor(f.outsiderId, f.projectId), "\"No access\" left the row standing.")
    }

    /**
     * THE test of the two-ended check: an Admin cannot demote an Owner by handing them
     * Viewer.
     *
     * The same escalation as promoting a peer, running downhill — and the one the
     * grant route would miss if it only asked about the rung being written.
     */
    @Test
    fun `an admin cannot write a lesser rung over an owner`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val response = client.post(ApiRoutes.projectRoles(f.projectId)) {
                cookie(SESSION_COOKIE, f.adminCookie)
                contentType(ContentType.Application.Json)
                setBody(RungGrant(f.ownerId, ProjectRole.VIEWER.key))
            }
            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "An admin demoted an owner, which is promoting themselves by another route.",
            )
        }
        assertEquals(ProjectRole.OWNER, roles.roleFor(f.ownerId, f.projectId), "The demotion landed.")
    }

    // ── Adding a person ──────────────────────────────────────────────────────

    /**
     * Adding an address writes an account nobody has signed into, holding the rung.
     *
     * The badge the Access list shows rides on `hasSignedIn`, so it is asserted here
     * rather than only in the store's contract: a route that stamped an arrival would
     * make every added person look like somebody who had already turned up.
     */
    @Test
    fun `adding a person writes an unclaimed account holding the rung`(): Unit = runBlocking {
        val f = seed()
        val state = withRoutesResult { client ->
            client.post(ApiRoutes.projectPeople(f.projectId)) {
                cookie(SESSION_COOKIE, f.adminCookie)
                contentType(ContentType.Application.Json)
                setBody(PersonAdd("newcomer@example.com", ProjectRole.CONTRIBUTOR.key))
            }.body<ProjectSettingsState>()
        }
        val row = state.access!!.people.firstOrNull { it.email == "newcomer@example.com" }
        assertNotNull(row, "The added person is not on the list they were added to.")
        assertEquals(ProjectRole.CONTRIBUTOR.key, row.roleKey)
        assertFalse(row.hasSignedIn, "An added account was reported as one somebody has used.")
        assertEquals(ProjectRole.CONTRIBUTOR, roles.roleFor(row.userId, f.projectId))
    }

    /** Twice is once: adding somebody who is already here moves their rung, and makes no second account. */
    @Test
    fun `adding the same address twice does not make a second account`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            repeat(2) {
                assertEquals(
                    HttpStatusCode.OK,
                    client.post(ApiRoutes.projectPeople(f.projectId)) {
                        cookie(SESSION_COOKIE, f.adminCookie)
                        contentType(ContentType.Application.Json)
                        setBody(PersonAdd("twice@example.com", ProjectRole.VIEWER.key))
                    }.status,
                )
            }
        }
        assertEquals(
            1,
            users.selectAll().count { it.email == "twice@example.com" },
            "Adding the same address twice made two accounts.",
        )
    }

    /** A rung the caller may not hand out is refused, and no account is created for it. */
    @Test
    fun `an admin cannot add somebody as an admin`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val response = client.post(ApiRoutes.projectPeople(f.projectId)) {
                cookie(SESSION_COOKIE, f.adminCookie)
                contentType(ContentType.Application.Json)
                setBody(PersonAdd("peer@example.com", ProjectRole.ADMIN.key))
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
        assertTrue(
            users.selectAll().none { it.email == "peer@example.com" },
            "A refused grant created the account anyway, which is a way to fill the user table.",
        )
    }

    /** Not an address is a 400 with something to read, not a row. */
    @Test
    fun `a malformed address is refused`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val response = client.post(ApiRoutes.projectPeople(f.projectId)) {
                cookie(SESSION_COOKIE, f.adminCookie)
                contentType(ContentType.Application.Json)
                setBody(PersonAdd("not an address", ProjectRole.VIEWER.key))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        assertEquals(f.accountsAtSeed, users.selectAll().size, "A malformed address still made a row.")
    }

    // ── Board display ────────────────────────────────────────────────────────

    /**
     * Both board-display switches are an Admin's, and a Maintainer is refused.
     *
     * Hiding the issue number was a per-user preference with no gate at all (LNL-105), so
     * this asserts the gate it *arrived* at rather than the one it had.
     */
    @Test
    fun `board display is an admin's and a maintainer is refused`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.post(ApiRoutes.projectDisplay(f.projectId)) {
                    cookie(SESSION_COOKIE, f.adminCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ProjectDisplaySettings(showIssueAuthor = true, hideIssueNumbers = true))
                }.status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post(ApiRoutes.projectDisplay(f.projectId)) {
                    cookie(SESSION_COOKIE, f.maintainerCookie)
                    contentType(ContentType.Application.Json)
                    setBody(ProjectDisplaySettings(showIssueAuthor = false, hideIssueNumbers = false))
                }.status,
                "A maintainer changed how the board reads for everybody.",
            )
        }
        val stored = projects.findById(f.projectId)!!
        assertTrue(stored.showIssueAuthor && stored.hideIssueNumbers)
        // And a maintainer is still *told* what the board does — the switches are shown to
        // them, dead. That is the read-only rule, asserted on the response.
        assertTrue(settingsFor(f.maintainerCookie, f.projectId).hideIssueNumbers)
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(
        val sysAdminId: Long,
        val sysAdminCookie: String,
        val ownerId: Long,
        val ownerCookie: String,
        val adminId: Long,
        val adminCookie: String,
        val maintainerId: Long,
        val maintainerCookie: String,
        val viewerCookie: String,
        val outsiderId: Long,
        val projectId: Long,
        /** How many accounts exist after seeding, so "no row was written" is checkable. */
        val accountsAtSeed: Int,
    )

    private suspend fun seed(): Fixture {
        // The first account is the instance administrator — and, once seated below, its
        // owner — and deliberately holds none of the four rungs written below: every
        // assertion here is about a rung rather than about the administrator
        // short-circuit.
        val sysAdmin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-sys", "Sys", "sys@example.com"))
        val owner = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-own", "Ona", "ona@example.com"))
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-adm", "Adi", "adi@example.com"))
        val maintainer = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-mai", "Mai", "mai@example.com"))
        val viewer = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-vie", "Vic", "vic@example.com"))
        val outsider = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-out", "Out", "out@example.com"))
        assertTrue(sysAdmin.isInstanceAdmin, "The first account is meant to be the instance administrator.")

        val project = projectRepository.create("Lunamux", "LMX")
        roles.setRole(owner.id, project.id, ProjectRole.OWNER)
        roles.setRole(admin.id, project.id, ProjectRole.ADMIN)
        roles.setRole(maintainer.id, project.id, ProjectRole.MAINTAINER)
        roles.setRole(viewer.id, project.id, ProjectRole.VIEWER)
        // The outsider holds nothing on purpose: they are what proves the person list is
        // exceptions rather than a directory, and what an audience row admits.

        // One sprint and one version, because a project starts with neither and the
        // maintainer assertions are about the two lists arriving at all (LNL-196).
        vocabularies.add(project.id, se.soderbjorn.lunicle.clientserver.VocabularyKind.SPRINT, "Q3")
        vocabularies.add(project.id, se.soderbjorn.lunicle.clientserver.VocabularyKind.VERSION, "1.0")

        // Production seats the instance owner at boot; a fixture that skipped it would be
        // testing an instance nobody runs. See InstanceLadder.
        seatInstanceOwner(users, instanceSettings)
        return Fixture(
            sysAdminId = sysAdmin.id,
            sysAdminCookie = sessions.create(sysAdmin.id),
            ownerId = owner.id,
            ownerCookie = sessions.create(owner.id),
            adminId = admin.id,
            adminCookie = sessions.create(admin.id),
            maintainerId = maintainer.id,
            maintainerCookie = sessions.create(maintainer.id),
            viewerCookie = sessions.create(viewer.id),
            outsiderId = outsider.id,
            projectId = project.id,
            accountsAtSeed = users.selectAll().size,
        )
    }

    /** One settings read, as a given caller. The response is this ticket's whole contract. */
    private fun settingsFor(cookie: String, projectId: Long): ProjectSettingsState {
        lateinit var state: ProjectSettingsState
        withRoutes { client ->
            state = client.get(ApiRoutes.projectSettings(projectId)) {
                cookie(SESSION_COOKIE, cookie)
            }.body()
        }
        return state
    }

    private fun withRoutes(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing {
                boardRoutes(dependencies())
                projectSettingsRoutes(dependencies())
            }
        }
        val client = createClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
        }
        block(client)
    }

    /** [withRoutes], for the tests that want the response body rather than only its status. */
    private fun <T : Any> withRoutesResult(block: suspend (io.ktor.client.HttpClient) -> T): T {
        var held: T? = null
        withRoutes { client -> held = block(client) }
        return held ?: error("The block never ran, which means testApplication changed shape.")
    }

    private fun dependencies() = BoardDependencies(
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
        impersonations = Impersonations(),
        instanceSettings = instanceSettings,
        subscriptions = SubscriptionStore(database),
        reads = ReadStore(database),
    )
}
