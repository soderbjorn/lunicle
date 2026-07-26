/**
 * The InstanceSettings contract, run against the **Firestore** implementation on the
 * emulator — the mirror of [SqlDelightInstanceSettingsStoreContractTest].
 *
 * Same assertions ([InstanceSettingsStoreContract]), different backend, so the two
 * cannot quietly diverge on what an unset switch reads as or whether one switch's
 * write disturbs the other. If the emulator is not configured for this run (no
 * `-Dlunicle.firestoreEmulatorHost=…`), every test skips rather than fails, so the
 * SQLite suite is unaffected.
 *
 * Simpler than the UiSettings Firestore test: the switches are instance-wide, so
 * there is no user to mint — the store writes to one fixed document.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreInstanceSettingsStore

class FirestoreInstanceSettingsStoreContractTest : InstanceSettingsStoreContract() {
    private val fixture = FirestoreContractFixture()

    override val store: InstanceSettingsStore by lazy { FirestoreInstanceSettingsStore(fixture.firestore) }

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
