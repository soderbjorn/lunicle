/**
 * Statistics: the security boundary, the rate ceiling, and the counts.
 *
 * Three things here fail differently and each is worth its own section.
 *
 *  - **The token variable's prefix rule.** This is the one genuine security
 *    boundary the feature adds. Without it an admin can name `LUNICLE_GOOGLE_CLIENT_SECRET`
 *    in a settings field and have this server post it to github.com in an
 *    Authorization header. Nothing about a missing prefix check looks broken —
 *    every statistic still works — so the only thing that will ever catch its
 *    removal is a test that asserts the refusal.
 *  - **The fifteen-minute ceiling.** The promise is that this server calls GitHub
 *    at most once per project per window however hard the dialog is hammered.
 *    Losing it does not break a screen either; it exhausts a rate limit slowly,
 *    in production, and the symptom arrives days later as "statistics stopped
 *    working" with nothing in the code looking wrong.
 *  - **What "closed" counts.** Two different measurements sharing one row of the
 *    dialog — history for the windows, the board for all-time. A change that
 *    quietly unified them would still produce three plausible numbers.
 *
 * @see StatisticsRepository
 * @see IssueStatistics.sq
 */
package se.soderbjorn.lunicle

import io.ktor.client.call.body
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.StatisticsState
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class StatisticsTest {
    private val file: File = Files.createTempFile("lunicle-statistics", ".db").toFile().also { it.delete() }
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
    private val vocabularies =
        VocabularyRepository(database, labels, components, statuses, priorities, resolutions, sprints, versions, issues)
    private val events = IssueEventStore(database)
    private val history = IssueHistory(events, statuses, labels, components, users)
    private val snapshots = ProjectStatisticsStore(database)
    private val issueCounts = IssueStatisticsStore(database)
    private val instanceSettings = InMemoryInstanceSettingsStore()
    private val access = AccessControl(roles, instanceSettings)

    /** Moves under the test's control, so a window can age without anybody sleeping. */
    private var clock = 1_000_000_000_000L

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── The token variable's prefix rule ─────────────────────────────────────

    /**
     * The refusal this whole rule exists for.
     *
     * Named after the actual secrets on this deployment rather than a generic
     * "SOME_VAR", because that is the attack: these two variables are really set
     * in production, and naming either one here would hand it to github.com. They
     * now share the `LUNICLE_` prefix with the token variables, which makes this a
     * sharper test than it looks: the check must require the whole
     * `LUNICLE_GITHUB_TOKEN_` prefix, not merely `LUNICLE_`.
     */
    @Test
    fun `a token variable naming an unrelated secret is refused`() {
        assertNull(parseTokenEnvName("LUNICLE_GOOGLE_CLIENT_SECRET"))
        assertNull(parseTokenEnvName("LUNICLE_RESEND_API_KEY"))
        assertNull(parseTokenEnvName("PATH"))
    }

    /** The prefix alone is not a variable name. */
    @Test
    fun `the bare prefix is refused`() {
        assertNull(parseTokenEnvName(GITHUB_TOKEN_ENV_PREFIX))
    }

    /**
     * A conforming name is accepted, and upper-cased.
     *
     * Environment variables conventionally are, and an admin who typed lower case
     * meant the same variable — so folding it is what makes the field forgiving
     * without making the rule negotiable.
     */
    @Test
    fun `a conforming name is accepted and upper-cased`() {
        assertEquals("LUNICLE_GITHUB_TOKEN_LUNICLE", parseTokenEnvName("lunicle_github_token_lunicle"))
        assertEquals("LUNICLE_GITHUB_TOKEN_A1", parseTokenEnvName("  LUNICLE_GITHUB_TOKEN_A1  "))
    }

    /** Characters no shell would export are refused rather than stored to fail later. */
    @Test
    fun `a name with illegal characters is refused`() {
        assertNull(parseTokenEnvName("LUNICLE_GITHUB_TOKEN_A-B"))
        assertNull(parseTokenEnvName("LUNICLE_GITHUB_TOKEN_A B"))
    }

    // ── Parsing what somebody pasted ─────────────────────────────────────────

    @Test
    fun `every spelling of a repository parses to the same pair`() {
        val expected = RepositoryRef("soderbjorn", "lunicle")
        assertEquals(expected, parseRepositoryUrl("https://github.com/soderbjorn/lunicle"))
        assertEquals(expected, parseRepositoryUrl("https://github.com/soderbjorn/lunicle.git"))
        assertEquals(expected, parseRepositoryUrl("https://github.com/soderbjorn/lunicle/"))
        assertEquals(expected, parseRepositoryUrl("git@github.com:soderbjorn/lunicle.git"))
        assertEquals(expected, parseRepositoryUrl("soderbjorn/lunicle"))
    }

    /**
     * Anything that is not two segments is refused.
     *
     * Including the traversal attempt: these segments go straight into a URL path,
     * and a `..` would address a different endpoint than the one that was typed.
     */
    @Test
    fun `a non-repository is refused rather than guessed at`() {
        assertNull(parseRepositoryUrl("https://github.com/soderbjorn"))
        assertNull(parseRepositoryUrl("https://github.com/a/b/c"))
        assertNull(parseRepositoryUrl(""))
        assertNull(parseRepositoryUrl("../../etc/passwd"))
        assertNull(parseRepositoryUrl("soderbjorn/lun icle"))
    }

    // ── The fifteen-minute ceiling ───────────────────────────────────────────

    /**
     * A second refresh inside the window makes no further call.
     *
     * The ceiling, asserted at its narrowest. If this fails, every open of the
     * dialog reaches github.com.
     */
    @Test
    fun `refreshing twice inside the window calls GitHub once`(): Unit = runBlocking {
        val f = seed()
        val calls = AtomicInteger()
        val repository = repositoryWith(f, calls)

        repository.refresh(f.projectId)
        repository.refresh(f.projectId)

        assertEquals(1, calls.get())
    }

    /** And once the window has passed, it does call again. */
    @Test
    fun `refreshing after the window calls GitHub again`(): Unit = runBlocking {
        val f = seed()
        val calls = AtomicInteger()
        val repository = repositoryWith(f, calls)

        repository.refresh(f.projectId)
        clock += 16 * 60 * 1000
        repository.refresh(f.projectId)

        assertEquals(2, calls.get())
    }

    /**
     * Ten callers arriving together produce one compile, not ten.
     *
     * The re-check *inside* the lock is what this covers. Without it the mutex
     * would serialise the stampede rather than prevent it — every queued caller
     * would wake and go on to make its own calls, which is the same rate-limit
     * problem arriving in single file.
     */
    @Test
    fun `concurrent refreshes compile once`(): Unit = runBlocking {
        val f = seed()
        val calls = AtomicInteger()
        val repository = repositoryWith(f, calls)

        (1..10).map { async { repository.refresh(f.projectId) } }.awaitAll()

        assertEquals(1, calls.get())
    }

    /** A failed compile leaves no snapshot behind, so the next open tries again. */
    @Test
    fun `commit failure still stores the issue counts and a reason`(): Unit = runBlocking {
        val f = seed()
        val repository = StatisticsRepository(
            projects = projects,
            snapshots = snapshots,
            issueCounts = issueCounts,
            gitHub = { _, _, _, _ -> CommitCounts.Unavailable("GitHub rejected the token.") },
            tokenLookup = { "token" },
            now = { clock },
        )
        projects.setRepositoryConfig(
            f.projectId,
            RepositoryConfig(RepositoryRef("o", "r"), TokenSource.Env("LUNICLE_GITHUB_TOKEN_X")),
        )
        file(f, "One")

        val snapshot = repository.refresh(f.projectId)

        // The issue counts answered even though the commits did not — the whole
        // point of not failing the response over one panel.
        assertEquals(1, snapshot.issuesCreated.allTime)
        assertEquals(
            CommitCounts.Unavailable("GitHub rejected the token."),
            snapshot.commits,
        )
    }

    /**
     * A refusal from GitHub does not delete the counts it last answered with.
     *
     * LNL-175, and the reason it was filed: the commit row simply *vanished* from
     * Analytics. A compile whose GitHub half failed used to write Unavailable over
     * the whole commit half of the snapshot, so one transient refusal took the
     * numbers off the screen — for the freshness window at least, and for as long
     * as the refusals lasted. Nothing looked broken afterwards; there was a
     * sentence about a token where three numbers had been.
     *
     * So the counts are carried forward with the new reason riding along, and both
     * reach the browser. Asserted through two real compiles rather than on the
     * carry-forward helper alone, because the bug was in what got *stored*.
     */
    @Test
    fun `a GitHub refusal keeps the last counts and says why they are old`(): Unit = runBlocking {
        val f = seed()
        projects.setRepositoryConfig(
            f.projectId,
            RepositoryConfig(RepositoryRef("soderbjorn", "lunicle"), TokenSource.Env("LUNICLE_GITHUB_TOKEN_TEST")),
        )
        var answer: CommitCounts = CommitCounts.Counted(week = 7, month = 30, allTime = 100)
        val repository = StatisticsRepository(
            projects = projects,
            snapshots = snapshots,
            issueCounts = issueCounts,
            gitHub = { _, _, _, _ -> answer },
            tokenLookup = { "a-token" },
            now = { clock },
        )

        repository.refresh(f.projectId)
        answer = CommitCounts.Unavailable("GitHub could not answer just now.")
        clock += 16 * 60 * 1000
        val second = repository.refresh(f.projectId)

        assertEquals(
            CommitCounts.Counted(7, 30, 100, notRefreshed = "GitHub could not answer just now."),
            second.commits,
            "the last good counts survive a refusal, with the reason on them",
        )
        // And they survive the round trip through SQLite, which is where they were
        // being lost — the snapshot is read back, not merely returned.
        assertEquals(second.commits, snapshots.forProject(f.projectId)?.commits)
    }

    /**
     * Unlinking the repository *does* take the row away.
     *
     * The other half of the rule above, and the one that keeps it from becoming
     * "commit counts are forever". A configuration absence is a deliberate state
     * rather than a failure to reach GitHub, and a number left behind for a
     * repository this project no longer tracks would be a lie no note could fix.
     */
    @Test
    fun `unlinking the repository drops the carried-forward counts`(): Unit = runBlocking {
        val f = seed()
        val repository = repositoryWith(f, AtomicInteger())
        repository.refresh(f.projectId)

        projects.setRepositoryConfig(f.projectId, RepositoryConfig(null, TokenSource.None))
        clock += 16 * 60 * 1000
        val second = repository.refresh(f.projectId)

        assertEquals(
            CommitCounts.Unavailable("No GitHub repository is linked to this project."),
            second.commits,
        )
    }

    /**
     * A project with no repository reports why, rather than reporting zero.
     *
     * Zero would be a claim that nobody committed. The distinction is the reason
     * the commit columns are nullable at all.
     */
    @Test
    fun `no repository linked yields a reason and no counts`(): Unit = runBlocking {
        val f = seed()
        val snapshot = repositoryWith(f, AtomicInteger(), link = false).refresh(f.projectId)
        assertTrue(snapshot.commits is CommitCounts.Unavailable)
    }

    // ── What the counts count ────────────────────────────────────────────────

    /** Issues filed inside and outside the window, counted from `created_at`. */
    @Test
    fun `created counts respect the windows and exclude drafts`(): Unit = runBlocking {
        val f = seed()
        file(f, "Recent")
        // A draft is nobody's business but its author's, and it is the one issue
        // on the board that has not happened yet.
        issueRepository.createDraft(f.projectId, Author.Account(f.adminId))

        val counts = issueCounts.created(f.projectId, weekStart = 0, monthStart = 0)
        assertEquals(1, counts.allTime)
        assertEquals(1, counts.week)

        // A window that starts after everything was filed sees none of it.
        //
        // Measured from the real clock, not the test's [clock]: issues are stamped
        // by IssueStore's own `now`, which this fixture deliberately does not
        // control — the windows are the repository's business and the row's
        // timestamp is the store's.
        val afterEverything = System.currentTimeMillis() + 60_000
        val future = issueCounts.created(f.projectId, weekStart = afterEverything, monthStart = afterEverything)
        assertEquals(0, future.week)
        assertEquals(1, future.allTime)
    }

    /**
     * Closing an issue is counted from the history, and all-time from the board.
     *
     * The two halves are asserted together on purpose: they are different
     * measurements and this is the test that says so out loud.
     */
    @Test
    fun `closed counts come from history for windows and from the board for all-time`(): Unit = runBlocking {
        val f = seed()
        val issueId = file(f, "Closes")
        val issue = issues.findById(issueId)!!
        val closing = statuses.forProject(f.projectId).first { it.requiresResolution }

        issues.setStatus(issueId, closing.id, resolutions.forProject(f.projectId).first().id)
        history.recordStatusChanged(issue, closing.id, Author.Account(f.adminId), agentName = null)

        val counts = issueCounts.closed(f.projectId, weekStart = 0, monthStart = 0)
        assertEquals(1, counts.week, "the history knows when it was closed")
        assertEquals(1, counts.allTime, "and the board knows that it is")
    }

    /**
     * An issue closed *before* the history existed is invisible to the windows and
     * visible to all-time.
     *
     * The documented consequence, pinned as behaviour rather than left as a
     * comment: on a project older than its history, all-time can exceed anything
     * the windows would sum to. Somebody will eventually try to "fix" the
     * disagreement by making all-time sum the windows, and this is what says no.
     */
    @Test
    fun `a close with no history event counts only in all-time`(): Unit = runBlocking {
        val f = seed()
        val issueId = file(f, "Imported")
        val closing = statuses.forProject(f.projectId).first { it.requiresResolution }
        // No history event: exactly what a pre-history close, or an MCP create
        // straight into the closing column, leaves behind.
        issues.setStatus(issueId, closing.id, resolutions.forProject(f.projectId).first().id)

        val counts = issueCounts.closed(f.projectId, weekStart = 0, monthStart = 0)
        assertEquals(0, counts.week)
        assertEquals(1, counts.allTime)
    }

    // ── Who may see the repository configuration ─────────────────────────────

    /**
     * A project administrator does not receive the repository fields.
     *
     * The gate LNL-37 made subtle. Everything else in the settings response opened
     * up to project administrators when that rung arrived; these two did not,
     * because the token field names an environment variable on the deployment and
     * the route that writes it is the project owner's alone. Narrowing the
     * read on `canAdministerProject` — the obvious thing, and what the section
     * around it does — would send a project administrator a field they can see,
     * cannot change, and would be shown as editable until the save 403'd.
     *
     * Asserted against a project that genuinely has a repository configured, so a
     * regression cannot pass by there being nothing to leak.
     */
    @Test
    fun `a project administrator is not sent the repository configuration`(): Unit = runBlocking {
        val f = seed()
        projects.setRepositoryConfig(
            f.projectId,
            RepositoryConfig(RepositoryRef("soderbjorn", "lunicle"), TokenSource.Env("LUNICLE_GITHUB_TOKEN_TEST")),
        )
        val projectAdmin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-padmin", "Pat", null))
        roles.setRole(projectAdmin.id, f.projectId, ProjectRole.ADMIN)
        val cookie = sessions.create(projectAdmin.id)

        withRoutes { client ->
            val settings: ProjectSettingsState = client.get(ApiRoutes.projectSettings(f.projectId)) {
                cookie(SESSION_COOKIE, cookie)
            }.body()

            assertEquals("", settings.repositoryUrl)
            assertEquals("", settings.githubTokenEnv)
            assertEquals(false, settings.canConfigureRepository)
            // The rest of the admin half still reaches them — this narrows two
            // fields, not the section they sit in.
            assertTrue(settings.canMutateProject, "a project administrator still administers the project")
            assertTrue(settings.statuses.isNotEmpty())
        }
    }

    /** A system administrator does receive them. The other half of the gate. */
    @Test
    fun `a system administrator is sent the repository configuration`(): Unit = runBlocking {
        val f = seed()
        projects.setRepositoryConfig(
            f.projectId,
            RepositoryConfig(RepositoryRef("soderbjorn", "lunicle"), TokenSource.Env("LUNICLE_GITHUB_TOKEN_TEST")),
        )
        val cookie = sessions.create(f.adminId)

        withRoutes { client ->
            val settings: ProjectSettingsState = client.get(ApiRoutes.projectSettings(f.projectId)) {
                cookie(SESSION_COOKIE, cookie)
            }.body()

            assertEquals("soderbjorn/lunicle", settings.repositoryUrl)
            assertEquals("LUNICLE_GITHUB_TOKEN_TEST", settings.githubTokenEnv)
            assertEquals(true, settings.canConfigureRepository)
        }
    }

    // ── The routes ───────────────────────────────────────────────────────────

    /** A project nobody may read is a 404, not a 403. See readableProject. */
    @Test
    fun `statistics for an unreadable project are a 404`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val response = client.get(ApiRoutes.projectStatistics(f.projectId))
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    /**
     * The read route compiles nothing.
     *
     * A cold cache answers immediately with no statistics and `isStale`, which is
     * what lets the dialog paint before the slow call is even considered.
     */
    @Test
    fun `the read route answers a cold cache without compiling`(): Unit = runBlocking {
        val f = seed(isPublic = true)
        val calls = AtomicInteger()
        withRoutes(repositoryWith(f, calls)) { client ->
            val state: StatisticsState = client.get(ApiRoutes.projectStatistics(f.projectId)).body()
            assertNull(state.statistics)
            assertTrue(state.isStale)
        }
        assertEquals(0, calls.get(), "reading must never reach GitHub")
    }

    /** And after a compile it serves the snapshot, no longer stale. */
    @Test
    fun `the read route serves a compiled snapshot`(): Unit = runBlocking {
        val f = seed(isPublic = true)
        val repository = repositoryWith(f, AtomicInteger())
        repository.refresh(f.projectId)

        withRoutes(repository) { client ->
            val state: StatisticsState = client.get(ApiRoutes.projectStatistics(f.projectId)).body()
            assertNotNull(state.statistics)
            assertEquals(false, state.isStale)
            assertEquals(7, state.statistics!!.commits?.week)
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private data class Fixture(val adminId: Long, val projectId: Long)

    private suspend fun seed(prefix: String = "LMX", isPublic: Boolean = false): Fixture {
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin-$prefix", "Admin", null))
        val project = projectRepository.create("Lunamux", prefix)
            .also { if (isPublic) roles.setAudienceRole(it.id, Audience.GUEST, ProjectRole.VIEWER) }
        // Production seats the instance owner at boot (see InstanceLadder.kt), and
        // four rules — creating and managing projects, backfilling authorship, agent
        // mail, out-of-band attachment deletes — are the owner's alone rather than an
        // administrator's. A fixture that skipped this would be testing an instance
        // nobody runs: one with an administrator and no owner.
        seatInstanceOwner(users, instanceSettings)
        return Fixture(admin.id, project.id)
    }

    /**
     * A repository whose GitHub half is a counter rather than a network call.
     *
     * @param calls incremented per commit-count call, which is what the ceiling
     *   tests assert on.
     * @param link whether to configure a repository at all — false is the
     *   "nothing linked" state every project starts in.
     */
    private suspend fun repositoryWith(
        f: Fixture,
        calls: AtomicInteger,
        link: Boolean = true,
    ): StatisticsRepository {
        if (link) {
            projects.setRepositoryConfig(
                f.projectId,
                RepositoryConfig(RepositoryRef("soderbjorn", "lunicle"), TokenSource.Env("LUNICLE_GITHUB_TOKEN_TEST")),
            )
        }
        return StatisticsRepository(
            projects = projects,
            snapshots = snapshots,
            issueCounts = issueCounts,
            gitHub = { _, _, _, _ ->
                calls.incrementAndGet()
                CommitCounts.Counted(week = 7, month = 30, allTime = 100)
            },
            tokenLookup = { "a-token" },
            now = { clock },
        )
    }

    /** File a published issue and return its id. */
    private suspend fun file(f: Fixture, title: String): Long {
        val (id, _) = issueRepository.createDraft(f.projectId, Author.Account(f.adminId))
        val issue = issues.findById(id)!!
        issueRepository.save(
            issue = issue,
            title = title,
            description = "",
            statusId = issue.statusId,
            priorityId = issue.priorityId,
            resolutionId = null,
            assigneeId = null,
            sprintId = null,
            plannedVersionId = null,
            fixedVersionId = null,
            labelIds = emptyList(),
            componentIds = emptyList(),
        )
        return id
    }

    private fun withRoutes(
        statistics: StatisticsRepository? = null,
        block: suspend (io.ktor.client.HttpClient) -> Unit,
    ) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing { boardRoutes(dependencies(statistics)) }
        }
        val client = createClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
        }
        block(client)
    }

    private fun dependencies(statistics: StatisticsRepository?) = BoardDependencies(
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
        sprints = SprintRepository(database, sprints, projects, issues, statuses),
        sprintRepository = SprintRepository(database, sprints, projects, issues, statuses),
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
        statistics = statistics,
    )
}
