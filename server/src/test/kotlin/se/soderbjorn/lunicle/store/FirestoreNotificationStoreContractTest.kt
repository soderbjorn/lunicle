/**
 * The Notification contract, run against the **Firestore** implementation on the
 * emulator — the mirror of [SqlDelightNotificationStoreContractTest].
 *
 * Same assertions ([NotificationStoreContract]), different backend; skipped when no
 * emulator is configured (no `-Dlunicle.firestoreEmulatorHost=…`).
 *
 * **Seeding is synthetic.** A notification stores its owner as a plain `userId`
 * field and validates it against nothing (a document store has no foreign key), so a
 * "user" here is just a fresh id. The store allocates its own notification ids from
 * its counter starting at 1; the synthetic user ids start well clear of that range
 * so the two never read as the same number.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreNotificationStore

class FirestoreNotificationStoreContractTest : NotificationStoreContract() {
    private val fixture = FirestoreContractFixture()
    private var seq = 10_000L

    override val store: NotificationStore by lazy { FirestoreNotificationStore(fixture.firestore) }

    override suspend fun newUser(): Long = ++seq

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
