/**
 * The OAuth client contract, run against the **Firestore** implementation on the
 * emulator — the mirror of [SqlDelightOAuthClientStoreContractTest].
 *
 * Same assertions ([OAuthClientStoreContract]), different backend. If the emulator
 * is not configured for this run (no `-Dlunicle.firestoreEmulatorHost=…`), every
 * test skips rather than fails, so the SQLite suite is unaffected.
 *
 * A client has no cross-entity foreign key, so nothing is seeded but the clock the
 * store reads for its `created_at`/`last_used_at` and the stale sweep.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreOAuthClientStore

class FirestoreOAuthClientStoreContractTest : OAuthClientStoreContract() {
    private val fixture = FirestoreContractFixture()
    private var clockMillis = 0L

    override val store: OAuthClientStore by lazy { FirestoreOAuthClientStore(fixture.firestore, now = { clockMillis }) }

    override fun advanceTime(millis: Long) {
        clockMillis += millis
    }

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
