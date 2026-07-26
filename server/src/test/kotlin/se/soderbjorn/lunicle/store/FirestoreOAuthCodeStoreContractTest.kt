/**
 * The OAuth authorization-code contract, run against the **Firestore**
 * implementation on the emulator — the mirror of [SqlDelightOAuthCodeStoreContractTest].
 *
 * Same assertions ([OAuthCodeStoreContract]), different backend; skipped when no
 * emulator is configured. The parity-critical one is single-use: [OAuthCodeStore.consume]
 * returns a code once and null forever after, which the Firestore store gets from a
 * transactional read-then-delete keyed by the code's hash.
 *
 * **Synthetic seeding.** A code stores its client id and user id as plain fields and
 * validates neither, so both are fresh ids minted here — no SQLite repository.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreOAuthCodeStore

class FirestoreOAuthCodeStoreContractTest : OAuthCodeStoreContract() {
    private val fixture = FirestoreContractFixture()
    private var clockMillis = 0L
    private var seq = 5_000L

    override val store: OAuthCodeStore by lazy { FirestoreOAuthCodeStore(fixture.firestore, now = { clockMillis }) }

    override fun advanceTime(millis: Long) {
        clockMillis += millis
    }

    override suspend fun clientId(): String = "lun_client_synthetic${++seq}"

    override suspend fun userId(): Long = ++seq

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
