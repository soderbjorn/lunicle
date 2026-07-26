/**
 * The UiSettings contract, run against the **Firestore** implementation on the
 * emulator — the mirror of [SqlDelightUiSettingsStoreContractTest].
 *
 * Same assertions ([UiSettingsStoreContract]), different backend. If the emulator
 * is not configured for this run (no `-Dlunicle.firestoreEmulatorHost=…`), every
 * test skips rather than fails, so the SQLite suite is unaffected.
 *
 * UiSettings has no cross-entity foreign key, so a fresh user is just a fresh id.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreUiSettingsStore

class FirestoreUiSettingsStoreContractTest : UiSettingsStoreContract() {
    private val fixture = FirestoreContractFixture()
    private var seq = 0L

    override val store: UiSettingsStore by lazy { FirestoreUiSettingsStore(fixture.firestore) }

    override suspend fun newUser(): Long = ++seq

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
