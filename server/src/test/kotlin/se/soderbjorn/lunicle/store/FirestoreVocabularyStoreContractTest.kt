/**
 * The Vocabulary contract, run against the **Firestore** implementation on the
 * emulator — the mirror of [SqlDelightVocabularyStoreContractTest].
 *
 * Same assertions ([VocabularyStoreContract]), different backend. If the emulator
 * is not configured for this run (no `-Dlunicle.firestoreEmulatorHost=…`), every
 * test skips rather than fails, so the SQLite suite is unaffected.
 *
 * **Why the seeding is synthetic.** The SQLite fixture seeds a project's default
 * vocabularies through `ProjectRepository`; its Firestore counterpart does not
 * exist as a seeding root the vocabulary store can lean on, and the store validates
 * no project foreign key anyway. So a "project" here is a fresh synthetic `Long`,
 * and its defaults are written through the store's own [FirestoreVocabularyStore.add]
 * — a couple of labels (so the reorder-refusal test has a non-empty vocabulary to
 * take a subset of) and three statuses (so the load-bearing test can delete down to
 * the last). [fileIssue] files a real issue through [FirestoreIssueStore] into the
 * leftmost status, so the usage the delete refusals read is real. Synthetic ids
 * start well above the stores' own counters so a projectId is never mistaken for a
 * row id.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.FirestoreIssueStore
import se.soderbjorn.lunicle.FirestoreVocabularyStore
import se.soderbjorn.lunicle.clientserver.VocabularyKind

class FirestoreVocabularyStoreContractTest : VocabularyStoreContract() {
    private val fixture = FirestoreContractFixture()

    private var seq = 1_000L
    private fun nextId(): Long = ++seq

    private val issues by lazy { FirestoreIssueStore(fixture.firestore) }

    override val store: VocabularyStore by lazy { FirestoreVocabularyStore(fixture.firestore, issues) }

    override suspend fun newProject(): Long {
        val projectId = nextId()
        store.add(projectId, VocabularyKind.LABEL, "Bug")
        store.add(projectId, VocabularyKind.LABEL, "Feature")
        store.add(projectId, VocabularyKind.STATUS, "New")
        store.add(projectId, VocabularyKind.STATUS, "In progress")
        store.add(projectId, VocabularyKind.STATUS, "Done")
        store.add(projectId, VocabularyKind.PRIORITY, "Normal")
        return projectId
    }

    override suspend fun fileIssue(projectId: Long): Long {
        val statusId = store.rows(projectId, VocabularyKind.STATUS).first().id
        val priorityId = store.rows(projectId, VocabularyKind.PRIORITY).firstOrNull()?.id ?: nextId()
        val (id, _) = issues.insertDraft(projectId, "Issue", statusId, priorityId, Author.Nobody)
        issues.publish(id, "Issue $id", "", statusId, priorityId, null, null, null, null, null)
        return id
    }

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
