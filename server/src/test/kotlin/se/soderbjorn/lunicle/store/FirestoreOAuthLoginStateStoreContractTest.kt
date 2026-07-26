/**
 * The OAuth login-state contract, run against the **Firestore** implementation on
 * the emulator — the mirror of [SqlDelightOAuthLoginStateStoreContractTest].
 *
 * Same assertions ([OAuthLoginStateStoreContract]), different backend; skipped when
 * no emulator is configured.
 *
 * **Synthetic seeding.** A login state stores a client id and a user id as plain
 * fields and validates neither (a document store has no foreign keys), so the client
 * and user a login state points at are just fresh ids minted here — no SQLite
 * repository is touched.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreOAuthLoginStateStore

class FirestoreOAuthLoginStateStoreContractTest : OAuthLoginStateStoreContract() {
    private val fixture = FirestoreContractFixture()
    private var clockMillis = 0L
    private var seq = 5_000L

    override val store: OAuthLoginStateStore by lazy {
        FirestoreOAuthLoginStateStore(fixture.firestore, now = { clockMillis })
    }

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
