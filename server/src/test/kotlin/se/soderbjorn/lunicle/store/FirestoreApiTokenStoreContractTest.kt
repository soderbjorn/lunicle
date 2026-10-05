/**
 * The API token contract, run against the **Firestore** implementation on the emulator
 * (LNL-222) — the mirror of [SqlDelightApiTokenStoreContractTest]. Skipped when no
 * emulator is configured. User ids are synthetic: the token store validates none of them.
 */
package se.soderbjorn.lunicle.store

import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreApiTokenStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

class FirestoreApiTokenStoreContractTest : ApiTokenStoreContract() {
    private val fixture = FirestoreContractFixture()
    private var clockMillis = 1_000_000L
    private var seq = 7_000L

    override val store: ApiTokenStore by lazy { FirestoreApiTokenStore(fixture.firestore, now = { clockMillis }) }

    override val now: Long get() = clockMillis

    override fun advanceTime(millis: Long) {
        clockMillis += millis
    }

    override suspend fun userId(): Long = ++seq

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
