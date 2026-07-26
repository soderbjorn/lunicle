/**
 * The Issue contract — the LNL-111 *hardest aggregate* — run against the
 * **Firestore** implementation on the emulator, the mirror of
 * [SqlDelightIssueStoreContractTest].
 *
 * Same assertions ([IssueStoreContract]), different backend. If the emulator is
 * not configured for this run (no `-Dlunicle.firestoreEmulatorHost=…`), every test
 * skips rather than fails, so the SQLite suite is unaffected.
 *
 * **Why the seeding is synthetic here.** The SQLite fixture seeds a project through
 * the real `ProjectRepository`/`IssueRepository` because those, and the vocabulary
 * stores, are SQLite-backed and already exist. Their Firestore counterparts are the
 * fan-out tickets (LNL-116…), not yet written — but the issue store never joins
 * against them: it stores status, priority, label, component and sprint ids as
 * plain fields and arrays, and validates none of them (a document store has no
 * foreign keys). So a "seeded project" here is just a bag of fresh ids, and
 * [fileIssue]/[createDraft] file issues through the store's own `insertDraft` +
 * `publish` — the same two writes `IssueRepository.save` drives. The ids start well
 * above the store's own global id counter so the two never read as the same number.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.FirestoreIssueStore

class FirestoreIssueStoreContractTest : IssueStoreContract() {
    private val fixture = FirestoreContractFixture()

    // Synthetic vocabulary ids, from a base well clear of the store's own issue-id
    // counter (which starts at 1) so a projectId is never mistaken for an issue id.
    private var seq = 1_000L
    private fun nextId(): Long = ++seq

    override val store: IssueStore by lazy { FirestoreIssueStore(fixture.firestore) }

    override suspend fun newProject(): Seeded = Seeded(
        projectId = nextId(),
        statusIds = listOf(nextId(), nextId(), nextId()),
        priorityId = nextId(),
        labelIds = listOf(nextId(), nextId()),
        componentIds = listOf(nextId(), nextId()),
        sprintId = nextId(),
    )

    override suspend fun fileIssue(project: Seeded, statusId: Long): Long {
        val (id, _) = store.insertDraft(project.projectId, "Issue", statusId, project.priorityId, Author.Nobody)
        store.publish(id, "Issue $id", "", statusId, project.priorityId, null, null, null, null, null)
        return id
    }

    override suspend fun createDraft(project: Seeded): Long =
        store.insertDraft(project.projectId, "Draft", project.statusIds.first(), project.priorityId, Author.Nobody).first

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
