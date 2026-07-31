/**
 * The LNL-122 proof: assemble the **Firestore** store graph exactly as
 * `Application.module` does on the `LUNICLE_DB_BACKEND=firestore` branch, run the
 * schema migration, and drive a real end-to-end flow — create a user, create a
 * project (row plus seeded vocabulary), file and publish an issue, and read the
 * board back — all against the emulator.
 *
 * This is the one test that exercises the *wiring*, not a single store's contract:
 * it uses [firestoreStoreGraph] (the same function the module calls) so the injected
 * seams, the repositories over the interfaces, and the migration runner are all the
 * production ones. If the emulator is not configured (no
 * `-Dlunicle.firestoreEmulatorHost=…`) it skips rather than fails, so the SQLite
 * suite is unaffected.
 *
 * Isolation comes from [FirestoreContractFixture]'s unique per-fixture project id —
 * an empty, private emulator namespace that vanishes when the emulator stops.
 */
package se.soderbjorn.lunicle

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.store.FirestoreContractFixture
import se.soderbjorn.lunicle.store.FirestoreEmulator

class FirestoreBootSmokeTest {
    private val fixture = FirestoreContractFixture()

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()

    @Test
    fun `firestore backend boots and serves a real create-and-read flow`() = runBlocking {
        // The exact graph Application.module builds on the firestore branch, over the
        // fixture's isolated emulator client (no mail configured — null sender).
        //
        // The GCS attachment blob store resolves its bucket and project id eagerly at
        // construction. This flow never uploads a byte, so GCS is never actually
        // contacted — the client is lazy — but the resolvers must find *a* value.
        // Provide dummies **only for the duration of construction** and restore the
        // prior values immediately, so the JVM-wide live GCS smoke test still sees them
        // unset and skips rather than pointing at a fake bucket.
        val priorProject = System.getProperty("lunicle.gcpProject")
        val priorBucket = System.getProperty("lunicle.attachmentsBucket")
        System.setProperty("lunicle.gcpProject", "lunicle-smoke")
        System.setProperty("lunicle.attachmentsBucket", "lunicle-smoke-attachments")
        val stores = try {
            firestoreStoreGraph(fixture.firestore, emailSender = null, emailBaseUrl = "")
        } finally {
            if (priorProject == null) System.clearProperty("lunicle.gcpProject")
            else System.setProperty("lunicle.gcpProject", priorProject)
            if (priorBucket == null) System.clearProperty("lunicle.attachmentsBucket")
            else System.setProperty("lunicle.attachmentsBucket", priorBucket)
        }

        // Boot step 1: the migration runner, as the startup coroutine runs it. Brings
        // the (empty) emulator schema to the build's target and returns without error.
        stores.migrate()

        // There is no role vocabulary to seed since LNL-191: a rung's name is the only
        // thing stored and its description lives on the ProjectRole enum, so the
        // startup seed that used to stand here has nothing to write.

        // A user — the first account on an empty instance, so it is the admin (the
        // upsert transaction's first-user rule).
        val user = stores.users.upsert(
            ProviderIdentity(AuthProvider.GOOGLE, providerId = "smoke-1", providerName = "Ada", email = "ada@example.test"),
        )
        assertTrue(user.isInstanceAdmin, "the first account is the instance admin")

        // Seam 1: a session resolves back to that user through the injected resolveUser.
        val token = stores.sessions.create(user.id)
        assertEquals(user.id, stores.sessions.lookup(token)?.id, "session should resolve to its user")

        // A project — the row plus its five seeded vocabularies, through
        // FirestoreProjectRepository (the store.ProjectProvisioning impl).
        val project = stores.projectRepository
            .createOpenToAll("Smoke Project", "SMK", stores.roles, stores.instanceSettings)
        assertEquals("Smoke Project", project.name)
        assertEquals("SMK", project.namePrefix)

        // The default board columns and vocabulary were seeded — the same defaults the
        // SQLite path seeds. Six statuses, five priorities, three resolutions, four
        // each of labels and components.
        val statuses = stores.statuses.forProject(project.id)
        assertEquals(DEFAULT_STATUSES, statuses.map { it.name }, "default statuses seeded in order")
        assertEquals(DEFAULT_PRIORITIES.size.toLong(), stores.priorities.forProject(project.id).size.toLong())
        assertEquals(DEFAULT_RESOLUTIONS.size.toLong(), stores.resolutions.forProject(project.id).size.toLong())
        assertEquals(DEFAULT_LABELS.size.toLong(), stores.labels.forProject(project.id).size.toLong())
        assertEquals(DEFAULT_COMPONENTS.size.toLong(), stores.components.forProject(project.id).size.toLong())
        assertTrue(statuses.any { it.requiresResolution }, "the closing status carries its resolution flag")

        // File an issue: the draft the editor writes into, then the publish that makes
        // it visible — through IssueRepository (the backend-agnostic orchestrator the
        // module builds above the graph) over the Firestore stores.
        val issueRepository = IssueRepository(
            stores.issues, stores.comments, stores.statuses, stores.priorities,
            stores.attachmentRepository, stores.attachments, subscriptions = stores.subscriptions,
        )
        val (issueId, _) = issueRepository.createDraft(project.id, Author.Account(user.id))
        val draft = assertNotNull(stores.issues.findById(issueId), "the draft should exist")
        assertTrue(draft.isDraft, "createDraft yields a draft")
        issueRepository.save(
            issue = draft,
            title = "Boots on Firestore",
            description = "Filed through the Firestore store graph.",
            statusId = draft.statusId,
            priorityId = draft.priorityId,
            resolutionId = null,
            assigneeId = null,
            sprintId = null,
            plannedVersionId = null,
            fixedVersionId = null,
            labelIds = emptyList(),
            componentIds = emptyList(),
        )

        // Read the board back: the published issue is on it, no longer a draft.
        val board = stores.issues.forProject(project.id)
        val published = assertNotNull(board.firstOrNull { it.id == issueId }, "the issue should be on the board")
        assertEquals("Boots on Firestore", published.title)
        assertTrue(!published.isDraft, "the saved issue is published")
        assertEquals(user.id, published.author.accountId, "authored by the user who filed it")

        // Seam 2: the derived unread reads run over the injected published-message /
        // published-post lookups without error (nothing unread here — a fresh user).
        assertTrue(stores.reads.unreadMessageCounts(user.id).isEmpty())
        assertTrue(!stores.reads.hasUnreadPosts(user.id, listOf(project.id)))

        stores.close()
    }
}
