/**
 * The Sprint contract, run against the **Firestore** implementation on the
 * emulator — the mirror of [SqlDelightSprintStoreContractTest].
 *
 * Same assertions ([SprintStoreContract]), different backend. If the emulator is
 * not configured for this run (no `-Dlunicle.firestoreEmulatorHost=…`), every test
 * skips rather than fails, so the SQLite suite is unaffected.
 *
 * **Seeding.** A "project" is a fresh synthetic `Long` — the sprint store validates
 * no project foreign key, and points a project at a sprint through a merge-write
 * that creates the pointer for a project seeded by id alone. Sprints are added
 * through the real [FirestoreVocabularyStore] (a sprint is a vocabulary row), so
 * [FirestoreSprintStore] reads back the same documents it wrote. [fileIssue] files
 * a real published issue through [FirestoreIssueStore]; no status is seeded, so no
 * status is *closing*, which makes every filed issue "unfinished" and exercises the
 * roll-to-backlog path on completion. Synthetic ids start well above the stores'
 * own counters.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.FirestoreIssueStore
import se.soderbjorn.lunicle.FirestoreProjectStore
import se.soderbjorn.lunicle.FirestoreSprintStore
import se.soderbjorn.lunicle.FirestoreVocabularyStore
import se.soderbjorn.lunicle.clientserver.VocabularyKind

class FirestoreSprintStoreContractTest : SprintStoreContract() {
    private val fixture = FirestoreContractFixture()

    private var seq = 1_000L
    private fun nextId(): Long = ++seq

    private val issues by lazy { FirestoreIssueStore(fixture.firestore) }
    // The links between issues (LNL-215), which the vocabulary store needs for a
    // relation kind's usage count and for the cascade its delete performs. Real rather
    // than a stub, so this fixture holds the same two stores production wires together.
    private val relations by lazy { se.soderbjorn.lunicle.FirestoreIssueRelationStore(fixture.firestore) }

    private val vocabulary by lazy { FirestoreVocabularyStore(fixture.firestore, issues, relations) }
    private val projects by lazy { FirestoreProjectStore(fixture.firestore) }

    override val store: SprintStore by lazy { FirestoreSprintStore(fixture.firestore, projects, issues) }

    override suspend fun newProject(): Long = nextId()

    override suspend fun newSprint(projectId: Long, name: String): Long =
        vocabulary.add(projectId, VocabularyKind.SPRINT, name).id

    override suspend fun fileIssue(projectId: Long): Long {
        val statusId = nextId()
        val priorityId = nextId()
        val (id, _) = issues.insertDraft(projectId, "Issue", statusId, priorityId, Author.Nobody)
        issues.publish(
            id, "Issue $id", "", statusId, priorityId, null,
            assigneeId = null, assigneeIsAgent = false,
            sprintId = null, plannedVersionId = null, fixedVersionId = null, estimate = null,
        )
        return id
    }

    override suspend fun sprintOfIssue(issueId: Long): Long? = issues.findById(issueId)?.sprintId

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
