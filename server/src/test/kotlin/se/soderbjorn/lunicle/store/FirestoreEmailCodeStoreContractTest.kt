/**
 * The email-code contract, run against the **Firestore** implementation on the emulator
 * — the mirror of [SqlDelightEmailCodeStoreContractTest].
 *
 * Same assertions ([EmailCodeStoreContract]), different backend; skipped when no emulator
 * is configured. The parity-critical ones are single-use redemption and the attempt cap,
 * which the Firestore store gets from a transactional read-then-consume keyed by the
 * hash of (purpose, address).
 *
 * **Synthetic seeding.** A code stores its user id as a plain field and validates nothing
 * against a users collection, so the id is a fresh synthetic value — no SQLite repository.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreEmailCodeStore

class FirestoreEmailCodeStoreContractTest : EmailCodeStoreContract() {
    private val fixture = FirestoreContractFixture()
    private var clockMillis = 0L
    private var seq = 7_000L

    override val store: EmailCodeStore by lazy { FirestoreEmailCodeStore(fixture.firestore, now = { clockMillis }) }

    override fun advanceTime(millis: Long) {
        clockMillis += millis
    }

    override fun currentTime(): Long = clockMillis

    override suspend fun userId(): Long = ++seq

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
