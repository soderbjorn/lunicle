/**
 * The IssueRelation contract, run against the **Firestore** implementation on the
 * emulator — the mirror of [SqlDelightIssueRelationStoreContractTest] (LNL-215).
 *
 * Same assertions ([IssueRelationStoreContract]), different backend, and this is the
 * pairing that ticket needed most: everything SQLite gets from its schema here has to
 * be written by hand — the two-directional read, the issue cascade and the kind
 * cascade — so "both backends refuse and remove the same things" is a claim only this
 * class can support. If the emulator is not configured for this run, every test skips
 * rather than fails, so the SQLite suite is unaffected.
 *
 * **Why the seeding is synthetic.** A document store has no foreign keys, and this
 * store validates none of the three ids a link names: it stores `projectId`,
 * `fromIssueId`, `toIssueId` and `kindId` as plain fields, exactly as
 * [se.soderbjorn.lunicle.FirestoreIssueEventStore] stores an `issueId`. So a project,
 * an issue and a kind are each a fresh `Long`, minted from a base well above this
 * store's own id counter so a relation id is never mistaken for one of the things it
 * names. Whether those ids resolve to anything is `IssueRepository`'s rule, not this
 * seam's.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreIssueRelationStore

class FirestoreIssueRelationStoreContractTest : IssueRelationStoreContract() {
    private val fixture = FirestoreContractFixture()

    private var seq = 100_000L
    private fun nextId(): Long = ++seq

    override val store: IssueRelationStore by lazy { FirestoreIssueRelationStore(fixture.firestore) }

    override suspend fun newProject(): Long = nextId()

    override suspend fun newIssue(projectId: Long): Long = nextId()

    override suspend fun newKind(projectId: Long): Long = nextId()

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
