/**
 * The Version contract, run against the **Firestore** implementation on the emulator
 * — the mirror of [SqlDelightVersionStoreContractTest] (LNL-134).
 *
 * Same assertions ([VersionStoreContract]), different backend. If the emulator is not
 * configured for this run, every test skips rather than fails, so the SQLite suite is
 * unaffected. A "project" is a fresh synthetic `Long` from a base well above the
 * shared vocabulary id counter, so a projectId is never mistaken for a row id.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreVersionStore

class FirestoreVersionStoreContractTest : VersionStoreContract() {
    private val fixture = FirestoreContractFixture()

    private var seq = 100_000L
    override val store: VersionStore by lazy { FirestoreVersionStore(fixture.firestore) }

    override suspend fun newProject(): Long = ++seq

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
