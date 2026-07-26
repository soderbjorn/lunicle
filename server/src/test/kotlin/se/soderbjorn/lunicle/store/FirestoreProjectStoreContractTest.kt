/**
 * The Project contract, run against the **Firestore** implementation on the
 * emulator — the mirror of [SqlDelightProjectStoreContractTest].
 *
 * Same assertions ([ProjectStoreContract]), different backend. If the emulator is
 * not configured for this run (no `-Dlunicle.firestoreEmulatorHost=…`), every test
 * skips rather than fails, so the SQLite suite is unaffected.
 *
 * No seeding hook: [se.soderbjorn.lunicle.store.ProjectStore.insert] is the seed.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreProjectStore

class FirestoreProjectStoreContractTest : ProjectStoreContract() {
    private val fixture = FirestoreContractFixture()

    override val store: ProjectStore by lazy { FirestoreProjectStore(fixture.firestore) }

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
