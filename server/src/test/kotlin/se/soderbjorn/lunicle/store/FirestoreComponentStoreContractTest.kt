/**
 * The Component contract, run against the **Firestore** implementation on the
 * emulator — the mirror of [SqlDelightComponentStoreContractTest]. Same assertions
 * ([ComponentStoreContract]), different backend; skipped when no emulator is
 * configured. A "project" is a fresh synthetic `Long`, well above the shared
 * vocabulary id counter.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreComponentStore

class FirestoreComponentStoreContractTest : ComponentStoreContract() {
    private val fixture = FirestoreContractFixture()

    private var seq = 100_000L
    override val store: ComponentStore by lazy { FirestoreComponentStore(fixture.firestore) }

    override suspend fun newProject(): Long = ++seq

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
