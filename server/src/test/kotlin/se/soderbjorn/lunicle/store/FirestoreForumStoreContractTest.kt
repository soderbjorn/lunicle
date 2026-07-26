/**
 * The Forum contract run against the **Firestore** implementation on the emulator,
 * the mirror of [SqlDelightForumStoreContractTest].
 *
 * Same assertions ([ForumStoreContract]), different backend. If the emulator is not
 * configured (no `-Dlunicle.firestoreEmulatorHost=…`), every test skips rather than
 * fails, leaving the SQLite suite untouched.
 *
 * **Why the seeding is synthetic.** The SQLite fixture seeds a project through the
 * real `ProjectRepository` because that is SQLite-backed and already exists; the
 * Firestore forum store never joins against a project — it stores `projectId` as a
 * plain field and validates no foreign key — so a "project" here is just a fresh
 * `Long`, minted well above the store's own forum-id counter (which starts at 1) so
 * a projectId is never mistaken for a forum id.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreForumStore

class FirestoreForumStoreContractTest : ForumStoreContract() {
    private val fixture = FirestoreContractFixture()

    private var seq = 1_000L
    private fun nextId(): Long = ++seq

    override val store: ForumStore by lazy { FirestoreForumStore(fixture.firestore) }

    override suspend fun newProject(): Long = nextId()

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
