/**
 * The issue history, as `get_issue` reports it.
 *
 * The history itself is [IssueHistoryTest]'s subject — what gets recorded and,
 * mostly, what does not. This file is about the other half of LNL-36: that an
 * agent can read it back. The gap it closes is worth stating, because nothing in
 * the recording tests would have noticed it — every event was being written
 * correctly and no MCP caller could see any of them, so an agent could close a
 * ticket and no agent afterwards could ask who had moved what, when, or out of
 * which column. `get_board`'s `updatedAt` is a last-touched stamp for any
 * mutation and cannot tell a close from a later typo fix.
 *
 * Two claims are load-bearing beyond "the array is there", and both are about a
 * name that changed after the fact:
 *
 *  - **A status is named as it was AT THE TIME.** Renaming a column does not
 *    rewrite what happened on Tuesday, and a history that re-resolved the name
 *    would silently rewrite the past every time somebody tidied a board.
 *  - **A person is named as they are NOW.** Renaming yourself does not make you
 *    somebody else. The asymmetry with the line above is deliberate, is the same
 *    one the web history renders with, and is the thing a build would most easily
 *    get uniformly wrong in either direction.
 *
 * Through the real `/mcp` endpoint with real tokens, as McpAgentNameTest: the
 * claim is about what an agent receives over the wire.
 *
 * @see McpTools.getIssue
 * @see IssueHistoryTest
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import se.soderbjorn.lunicle.clientserver.AuthProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import se.soderbjorn.lunicle.store.InstanceSettings

class McpHistoryTest {
    private val file: File = Files.createTempFile("lunicle-mcp-history", ".db").toFile().also { it.delete() }
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
    private val subscriptions = SubscriptionStore(database)
    private val attachmentStore = AttachmentStore(database)
    private val attachments = AttachmentRepository(attachmentStore, File(file.parentFile, "attachments-${file.name}"))
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
    private val history = IssueHistory(IssueEventStore(database), statuses, labels, components, users)
    private val issueRepository = IssueRepository(
        issues, comments, statuses, priorities, attachments, attachmentStore,
        history = history,
    )
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

    // ── The gap this closes ──────────────────────────────────────────────────

    /**
     * The question that filed LNL-36: which issues moved to which column, when,
     * and by whom.
     *
     * Asserted end to end — file over MCP, move over MCP, read it back — because
     * every link in that chain was individually working while the answer remained
     * unavailable. The `agentName` on the move is half the point: the reason to
     * ask at all is usually to audit an agent, and an event that named only the
     * human whose token was used would answer the question wrongly rather than
     * not at all.
     */
    @Test
    fun `get_issue reports the moves an agent made, with the column and the agent`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)
        val target = statuses.forProject(fixture.projectId)[1]

        withMcp { client ->
            client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Login is broken","agent_name":"Acme Assistant"}""",
            )
            val issueId = issues.forProject(fixture.projectId).single().id
            val moved = client.callTool(
                token,
                "move_issue",
                """{"issue_id":$issueId,"status":"${target.name}","agent_name":"Acme Assistant"}""",
            )
            assertTrue(!moved.isError, "The move was refused: ${moved.text}")

            val events = historyOf(client, token, issueId)
            assertEquals(
                listOf("CREATED", "STATUS_CHANGED"),
                events.map { it["kind"]?.jsonPrimitive?.contentOrNull },
                "get_issue did not report the issue's history, oldest first.",
            )
            val move = events.last()
            assertEquals(
                target.name,
                move["value"]?.jsonPrimitive?.contentOrNull,
                "The move did not say which column it went to.",
            )
            assertEquals("Admin", move["author"]?.jsonPrimitive?.contentOrNull, "The move lost its author.")
            assertEquals(
                "Acme Assistant",
                move["agentName"]?.jsonPrimitive?.contentOrNull,
                "A move an agent made was not attributed to the agent — see move_issue's agent_name.",
            )
            assertTrue(
                move["createdAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() != null,
                "The move did not say when it happened, which is the whole question.",
            )
        }
    }

    /** A human's move carries no badge, and no `"agentName": null` either. */
    @Test
    fun `a move with no agent_name is reported unmarked`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)
        val target = statuses.forProject(fixture.projectId)[1]

        withMcp { client ->
            client.callTool(token, "create_issue", """{"project_id":${fixture.projectId},"title":"By a human"}""")
            val issueId = issues.forProject(fixture.projectId).single().id
            client.callTool(token, "move_issue", """{"issue_id":$issueId,"status":"${target.name}"}""")

            val move = historyOf(client, token, issueId).last()
            assertTrue("agentName" !in move, "A human's move leaked an agentName key.")
        }
    }

    // ── The two names, and why they behave differently ───────────────────────

    /**
     * A column renamed since is reported by the name it had.
     *
     * The snapshot the event stores, never re-resolved. A build that looked the
     * status up by id at read time would pass the test above and quietly rewrite
     * history every time a board was tidied — the issue moved into the column
     * *then* called that, and no later rename makes that untrue.
     */
    @Test
    fun `a status renamed since is reported by the name it had at the time`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)
        val target = statuses.forProject(fixture.projectId)[1]

        withMcp { client ->
            client.callTool(token, "create_issue", """{"project_id":${fixture.projectId},"title":"Login is broken"}""")
            val issueId = issues.forProject(fixture.projectId).single().id
            client.callTool(token, "move_issue", """{"issue_id":$issueId,"status":"${target.name}"}""")

            statuses.update(target.id, "Renamed since", target.requiresResolution)

            assertEquals(
                target.name,
                historyOf(client, token, issueId).last()["value"]?.jsonPrimitive?.contentOrNull,
                "Renaming a column rewrote what the history said had happened.",
            )
        }
    }

    /**
     * A person renamed since is reported by the name they have now.
     *
     * The other half of the asymmetry, and the reason `value` is not simply
     * printed as stored: an `ASSIGNEE_CHANGED` points at an account, so renaming
     * yourself must move the whole history with you. Both fields are asserted at
     * once — the author of the change *and* the person it names — because they
     * resolve through the same map and a build that fixed one would usually have
     * left the other frozen.
     */
    @Test
    fun `an assignee renamed since is reported by their current name`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            client.callTool(token, "create_issue", """{"project_id":${fixture.projectId},"title":"Login is broken"}""")
            val issueId = issues.forProject(fixture.projectId).single().id
            val assigned = client.callTool(token, "update_issue", """{"issue_id":$issueId,"assignee":"Admin"}""")
            assertTrue(!assigned.isError, "The assignment was refused: ${assigned.text}")

            users.setDisplayName(fixture.adminId, "Ada Lovelace")

            val event = historyOf(client, token, issueId).last()
            assertEquals("ASSIGNEE_CHANGED", event["kind"]?.jsonPrimitive?.contentOrNull)
            assertEquals(
                "Ada Lovelace",
                event["value"]?.jsonPrimitive?.contentOrNull,
                "The assignee was frozen at the name they had when it happened.",
            )
            assertEquals(
                "Ada Lovelace",
                event["author"]?.jsonPrimitive?.contentOrNull,
                "The author of the change was frozen at their old name.",
            )
        }
    }

    // ── Sets, not deltas ─────────────────────────────────────────────────────

    /**
     * A labels change carries the whole set as it stood afterwards.
     *
     * The set rather than a delta, so a reader can answer "what did it have then"
     * from one event without replaying every earlier one — and so a gap in the
     * history does not corrupt everything after it. Two labels then one, so a
     * build that reported only what was added or removed reads visibly wrong.
     */
    @Test
    fun `a labels change carries the whole set afterwards`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Login is broken"}""",
            )
            val issueId = issues.forProject(fixture.projectId).single().id
            val names = labels.forProject(fixture.projectId).take(2).map { it.name }
            assertEquals(2, names.size, "The fixture project has fewer than two labels to move between.")
            val relabelled = client.callTool(
                token,
                "update_issue",
                """{"issue_id":$issueId,"labels":["${names[0]}","${names[1]}"]}""",
            )
            assertTrue(!relabelled.isError, "The relabel was refused: ${relabelled.text}")

            val event = historyOf(client, token, issueId).last()
            assertEquals("LABELS_CHANGED", event["kind"]?.jsonPrimitive?.contentOrNull)
            assertEquals(
                names.toSet(),
                event["values"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
                "The event did not carry the labels as they stood after the change.",
            )
        }
    }

    // ── Who an edit is filed under ───────────────────────────────────────────

    /**
     * An `update_issue` edit is recorded under the person who made it, not under
     * the issue's author.
     *
     * LNL-180, and the reason the two accounts here are different people: every
     * other test in this file edits an issue the same token created, so a build
     * that filed edits under the *author* passed all of them. One did, and an
     * agent moving somebody else's ticket recorded that the reporter had moved it
     * — a factual claim about a colleague, permanent and plausible, on the one
     * record documented as answering "by whom", and invisible unless somebody
     * happened to read the history.
     *
     * The issue's own author is asserted in the same breath, because the fix is
     * only correct if it does not overcorrect: an edit still leaves the row's
     * author exactly as it was, which is the reason the wrong value was in reach
     * at all.
     */
    @Test
    fun `an edit is recorded under the caller, not the issue's author`(): Unit = runBlocking {
        val fixture = seed()
        val reporter = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-otto", "Otto", "otto@example.com"))
        roles.setRole(reporter.id, fixture.projectId, ProjectRole.CONTRIBUTOR)
        val reporterToken = tokenFor(reporter.id)
        val adminToken = tokenFor(fixture.adminId)
        val target = statuses.forProject(fixture.projectId)[1]

        withMcp { client ->
            val filed = client.callTool(
                reporterToken,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Login is broken"}""",
            )
            assertTrue(!filed.isError, "The reporter could not file the issue: ${filed.text}")
            val issueId = issues.forProject(fixture.projectId).single().id

            val edited = client.callTool(
                adminToken,
                "update_issue",
                """{"issue_id":$issueId,"status":"${target.name}","agent_name":"Acme Assistant"}""",
            )
            assertTrue(!edited.isError, "The edit was refused: ${edited.text}")

            val event = historyOf(client, adminToken, issueId).last()
            assertEquals("STATUS_CHANGED", event["kind"]?.jsonPrimitive?.contentOrNull)
            assertEquals(
                "Admin",
                event["author"]?.jsonPrimitive?.contentOrNull,
                "The edit was filed under the issue's author rather than whoever made it.",
            )
            assertEquals(
                "Acme Assistant",
                event["agentName"]?.jsonPrimitive?.contentOrNull,
                "The agent that made the edit was not named on the event.",
            )

            val detail = Json.parseToJsonElement(
                client.callTool(adminToken, "get_issue", """{"issue_id":$issueId}""").text,
            ).jsonObject
            assertEquals(
                "Otto",
                detail["author"]?.jsonPrimitive?.contentOrNull,
                "Editing somebody else's issue re-authored the issue itself.",
            )
        }
    }

    /**
     * The badge on an event is the one THIS call carried — never the badge the
     * issue happens to wear.
     *
     * The other half of LNL-180, and the half that would survive a fix aimed only
     * at the author: an issue filed by an agent keeps `agent_name` on the row
     * forever, so an edit that defaulted to it would mark a human's later typo fix
     * as the agent's doing. Per-event is the whole point of the column. The row's
     * own badge is asserted too — leaving it alone is what made the default look
     * reasonable in the first place.
     */
    @Test
    fun `an edit with no agent_name is unmarked, even on an issue an agent filed`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)

        withMcp { client ->
            client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Filed by an agent","agent_name":"Acme Assistant"}""",
            )
            val issueId = issues.forProject(fixture.projectId).single().id

            val edited = client.callTool(token, "update_issue", """{"issue_id":$issueId,"title":"Renamed by a person"}""")
            assertTrue(!edited.isError, "The edit was refused: ${edited.text}")

            val event = historyOf(client, token, issueId).last()
            assertEquals("TITLE_CHANGED", event["kind"]?.jsonPrimitive?.contentOrNull)
            assertTrue(
                "agentName" !in event,
                "A person's edit wore the issue's standing agent badge: $event",
            )

            val detail = Json.parseToJsonElement(
                client.callTool(token, "get_issue", """{"issue_id":$issueId}""").text,
            ).jsonObject
            assertEquals(
                "Acme Assistant",
                detail["agentName"]?.jsonPrimitive?.contentOrNull,
                "An edit that said nothing about the badge stripped the issue's.",
            )
        }
    }

    /**
     * The one case where an edit is filed under somebody other than the caller: an
     * admin naming an `author`, which is what the parameter is for.
     *
     * Backfill is the whole reason the caller is not simply hard-wired in, so the
     * LNL-180 fix has to leave this path intact — an imported edit belongs to the
     * person who originally made it, not to whoever ran the migration.
     */
    @Test
    fun `an admin backfilling an edit files it under the author they name`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)
        users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-otto", "Otto", "otto@example.com"))
        val target = statuses.forProject(fixture.projectId)[1]

        withMcp { client ->
            client.callTool(token, "create_issue", """{"project_id":${fixture.projectId},"title":"Imported"}""")
            val issueId = issues.forProject(fixture.projectId).single().id

            val backfilled = client.callTool(
                token,
                "update_issue",
                """{"issue_id":$issueId,"status":"${target.name}","author":"otto@example.com"}""",
            )
            assertTrue(!backfilled.isError, "The backfill was refused: ${backfilled.text}")

            assertEquals(
                "Otto",
                historyOf(client, token, issueId).last()["author"]?.jsonPrimitive?.contentOrNull,
                "A backfilled edit was filed under the admin who ran it rather than the author they named.",
            )
        }
    }

    // ── Correcting attribution, the instance owner's ─────────────────────────

    /**
     * The job update_history_event exists for: an imported entry gets reattached
     * to a real account once its author signs in.
     *
     * The CREATED entry is filed by the admin here — standing in for an import — and
     * then moved onto a second account by email, exactly as a migration cleanup
     * would. Both halves are asserted: the author moves, and the *kind* does not, so
     * a build that let this rewrite what happened rather than only who did it reads
     * visibly wrong.
     */
    @Test
    fun `an admin reattributes a history entry to another account, without touching what it records`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)
        users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-otto", "Otto", "otto@example.com"))
        val target = statuses.forProject(fixture.projectId)[1]

        withMcp { client ->
            client.callTool(token, "create_issue", """{"project_id":${fixture.projectId},"title":"Imported from elsewhere"}""")
            val issueId = issues.forProject(fixture.projectId).single().id
            client.callTool(token, "move_issue", """{"issue_id":$issueId,"status":"${target.name}"}""")

            val created = historyOf(client, token, issueId)
                .single { it["kind"]?.jsonPrimitive?.contentOrNull == "CREATED" }
            val eventId = created["id"]!!.jsonPrimitive.content

            val moved = client.callTool(
                token,
                "update_history_event",
                """{"event_id":$eventId,"author":"otto@example.com"}""",
            )
            assertTrue(!moved.isError, "The reattribution was refused: ${moved.text}")

            val after = historyOf(client, token, issueId)
                .single { it["id"]!!.jsonPrimitive.content == eventId }
            assertEquals(
                "Otto",
                after["author"]?.jsonPrimitive?.contentOrNull,
                "The entry did not move to the named account.",
            )
            assertEquals(
                "CREATED",
                after["kind"]?.jsonPrimitive?.contentOrNull,
                "Reattributing an entry rewrote what it records.",
            )
            // The STATUS_CHANGED entry, which was not named, is left exactly alone.
            assertEquals(
                "Admin",
                historyOf(client, token, issueId)
                    .single { it["kind"]?.jsonPrimitive?.contentOrNull == "STATUS_CHANGED" }["author"]
                    ?.jsonPrimitive?.contentOrNull,
                "Reattributing one entry moved another the call never named.",
            )
        }
    }

    /**
     * A non-admin cannot edit history, and a refused attempt changes nothing.
     *
     * The enforcement, not the affordance: the tool is absent from a non-admin's
     * list, but an agent that names it anyway is refused at the call, and the entry
     * it tried to rewrite is byte-for-byte what it was. Asserted through the admin's
     * own read, because the point is the row, not the error.
     */
    @Test
    fun `a non-admin naming a history entry is refused, and the entry is untouched`(): Unit = runBlocking {
        val fixture = seed()
        val adminToken = tokenFor(fixture.adminId)
        val ordinary = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ordinary", "Ordinary", "ordinary@example.com"))
        assertTrue(!ordinary.isInstanceAdmin, "The fixture's second user is somehow an admin.")
        roles.setRole(ordinary.id, fixture.projectId, ProjectRole.CONTRIBUTOR)
        val ordinaryToken = tokenFor(ordinary.id)

        withMcp { client ->
            client.callTool(ordinaryToken, "create_issue", """{"project_id":${fixture.projectId},"title":"Mine"}""")
            val issueId = issues.forProject(fixture.projectId).single().id
            val created = historyOf(client, adminToken, issueId).single()
            val eventId = created["id"]!!.jsonPrimitive.content

            val refused = client.callTool(
                ordinaryToken,
                "update_history_event",
                """{"event_id":$eventId,"author":"Ordinary"}""",
            )
            assertTrue(refused.isError, "A non-admin was allowed to edit history: ${refused.text}")
            assertTrue(
                refused.text.contains("instance owner"),
                "The refusal did not say who may do this. Got: ${refused.text}",
            )

            assertEquals(
                created["author"]?.jsonPrimitive?.contentOrNull,
                historyOf(client, adminToken, issueId).single()["author"]?.jsonPrimitive?.contentOrNull,
                "A refused edit still changed the entry's author.",
            )
        }
    }

    /**
     * Reattaching a migrated entry can shed its agent badge in the same breath.
     *
     * The entry was filed by an agent standing in for a migration, so once it names
     * a real person it should not still read as an agent's doing. An empty
     * agent_name clears the badge, and get_issue then reports the entry with no
     * `agentName` key at all — not `"agentName": null`, absent — exactly as a
     * human's change is reported.
     */
    @Test
    fun `an admin clears a migrated entry's agent badge while reattributing it`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)
        users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-otto", "Otto", "otto@example.com"))
        val target = statuses.forProject(fixture.projectId)[1]

        withMcp { client ->
            client.callTool(
                token,
                "create_issue",
                """{"project_id":${fixture.projectId},"title":"Imported","agent_name":"Migrator"}""",
            )
            val issueId = issues.forProject(fixture.projectId).single().id
            client.callTool(token, "move_issue", """{"issue_id":$issueId,"status":"${target.name}","agent_name":"Migrator"}""")

            val moved = historyOf(client, token, issueId)
                .single { it["kind"]?.jsonPrimitive?.contentOrNull == "STATUS_CHANGED" }
            assertEquals("Migrator", moved["agentName"]?.jsonPrimitive?.contentOrNull, "The badge was not set to begin with.")
            val eventId = moved["id"]!!.jsonPrimitive.content

            val cleared = client.callTool(
                token,
                "update_history_event",
                """{"event_id":$eventId,"author":"otto@example.com","agent_name":""}""",
            )
            assertTrue(!cleared.isError, "Clearing the badge was refused: ${cleared.text}")

            val after = historyOf(client, token, issueId).single { it["id"]!!.jsonPrimitive.content == eventId }
            assertEquals("Otto", after["author"]?.jsonPrimitive?.contentOrNull, "The entry did not move to the real account.")
            assertTrue("agentName" !in after, "The agent badge survived an empty agent_name: $after")
        }
    }

    // ── Watching, and who may set it for whom ────────────────────────────────

    /**
     * An admin can add another person as a watcher, and get_issue then lists them.
     *
     * The migration case: a real account, once it exists, is put on the tickets it
     * came from so its owner hears about them. The subject need not be a member of
     * the (private) project to be subscribed — the same as an existing watcher who
     * loses access — so this asserts only that the row lands and that get_issue
     * reports the name back, which is how an agent confirms the result.
     */
    @Test
    fun `an admin can make another user watch an issue, and get_issue lists them`(): Unit = runBlocking {
        val fixture = seed()
        val token = tokenFor(fixture.adminId)
        val otto = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-otto", "Otto", "otto@example.com"))

        withMcp { client ->
            client.callTool(token, "create_issue", """{"project_id":${fixture.projectId},"title":"Imported"}""")
            val issueId = issues.forProject(fixture.projectId).single().id

            val watched = client.callTool(token, "watch_issue", """{"issue_id":$issueId,"user":"otto@example.com"}""")
            assertTrue(!watched.isError, "The watch was refused: ${watched.text}")
            assertTrue(subscriptions.isSubscribedToIssueUpdates(otto.id, issueId), "The subscription row was not written.")

            val detail = Json.parseToJsonElement(
                client.callTool(token, "get_issue", """{"issue_id":$issueId}""").text,
            ).jsonObject
            assertEquals(
                listOf("Otto"),
                detail["watchers"]!!.jsonArray.map { it.jsonPrimitive.content },
                "get_issue did not list the new watcher.",
            )

            val unwatched = client.callTool(token, "watch_issue", """{"issue_id":$issueId,"user":"otto@example.com","watching":false}""")
            assertTrue(!unwatched.isError, "The unwatch was refused: ${unwatched.text}")
            assertTrue(!subscriptions.isSubscribedToIssueUpdates(otto.id, issueId), "Unwatch did not remove the row.")
        }
    }

    /**
     * An ordinary user may watch and unwatch themselves — it is inbox management,
     * not an edit — but naming somebody else is refused.
     *
     * Both halves in one test because they are one rule seen from two sides: the
     * gate is "readable, and it is your own inbox". The ordinary user is a member
     * (so the private issue is readable) with an address (so a subscribe is not
     * refused for the other reason), watches themselves successfully, and is then
     * refused when they reach for another person's watch — which is left untouched.
     */
    @Test
    fun `an ordinary user watches only themselves`(): Unit = runBlocking {
        val fixture = seed()
        val ordinary = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ord", "Ordinary", "ordinary@example.com"))
        roles.setRole(ordinary.id, fixture.projectId, ProjectRole.CONTRIBUTOR)
        val otto = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-otto", "Otto", "otto@example.com"))
        val adminToken = tokenFor(fixture.adminId)
        val ordinaryToken = tokenFor(ordinary.id)

        withMcp { client ->
            client.callTool(adminToken, "create_issue", """{"project_id":${fixture.projectId},"title":"Shared"}""")
            val issueId = issues.forProject(fixture.projectId).single().id

            val mine = client.callTool(ordinaryToken, "watch_issue", """{"issue_id":$issueId}""")
            assertTrue(!mine.isError, "An ordinary user could not watch an issue they can see: ${mine.text}")
            assertTrue(subscriptions.isSubscribedToIssueUpdates(ordinary.id, issueId), "Watching themselves did nothing.")

            val theirs = client.callTool(ordinaryToken, "watch_issue", """{"issue_id":$issueId,"user":"otto@example.com"}""")
            assertTrue(theirs.isError, "An ordinary user changed somebody else's watch.")
            assertTrue(
                theirs.text.contains("instance owner"),
                "The refusal did not say who may do this. Got: ${theirs.text}",
            )
            assertTrue(!subscriptions.isSubscribedToIssueUpdates(otto.id, issueId), "A refused watch still subscribed the other user.")
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val adminId: Long, val projectId: Long)

    /** The instance admin — who can do everything a history test needs — and a project. */
    private suspend fun seed(): Fixture {
        val admin = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-admin", "Admin", "admin@example.com"))
        val project = projectRepository.create("Lunamux", "LMX")
        // Production seats the instance owner at boot (see InstanceLadder.kt), and
        // four rules — creating and managing projects, backfilling authorship, agent
        // mail, out-of-band attachment deletes — are the owner's alone rather than an
        // administrator's. A fixture that skipped this would be testing an instance
        // nobody runs: one with an administrator and no owner.
        seatInstanceOwner(users, instanceSettings)
        return Fixture(admin.id, project.id)
    }

    /** `get_issue`'s history array, parsed. */
    private suspend fun historyOf(client: HttpClient, token: String, issueId: Long): List<JsonObject> {
        val detail = Json.parseToJsonElement(
            client.callTool(token, "get_issue", """{"issue_id":$issueId}""").text,
        ).jsonObject
        val events = assertNotNull(detail["history"], "get_issue reported no history at all: $detail")
        return events.jsonArray.map { it.jsonObject }
    }

    /** A real access token for [userId], with MCP enabled — see McpAgentNameTest.tokenFor. */
    private suspend fun tokenFor(userId: Long): String {
        users.setMcpEnabled(userId, true)
        val client = clients.register("Test agent", listOf("http://localhost:1234/callback"), listOf("authorization_code"))
        return tokens.issueTokens(userId, client.clientId, "mcp", "http://localhost/mcp").accessToken
    }

    private class ToolOutcome(val text: String, val isError: Boolean)

    /** Call one tool over JSON-RPC and unwrap the result — see McpAgentNameTest.callTool. */
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
        val result = assertNotNull(body["result"], "No result in $body").jsonObject
        val text = result["content"]!!.jsonArray
            .joinToString("\n") { (it as JsonObject)["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        return ToolOutcome(text, result["isError"]?.jsonPrimitive?.contentOrNull == "true")
    }

    /** Mount the real `/mcp` and hand back a client — see McpAgentNameTest.withMcp. */
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
        subscriptions = subscriptions,
        reads = ReadStore(database),
        history = history,
    )
}
