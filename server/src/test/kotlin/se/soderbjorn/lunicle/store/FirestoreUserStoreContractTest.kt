/**
 * The User contract, run against the **Firestore** implementation on the emulator
 * — the mirror of [SqlDelightUserStoreContractTest].
 *
 * Same assertions ([UserStoreContract]), different backend. If the emulator is not
 * configured for this run (no `-Dlunicle.firestoreEmulatorHost=…`), every test
 * skips rather than fails, so the SQLite suite is unaffected.
 *
 * No seeding hook: [se.soderbjorn.lunicle.store.UserStore.upsert] is itself how a
 * user comes to exist, and a fresh fixture is an empty instance — so the first
 * upsert in each test is the instance's first account, and the admin rule bites.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreUserStore

class FirestoreUserStoreContractTest : UserStoreContract() {
    private val fixture = FirestoreContractFixture()

    override val store: UserStore by lazy { FirestoreUserStore(fixture.firestore) }

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
