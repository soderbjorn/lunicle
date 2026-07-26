/**
 * The Session contract, run against the **Firestore** implementation on the
 * emulator — the mirror of [SqlDelightSessionStoreContractTest]. Same assertions
 * ([SessionStoreContract]), different backend; skipped when no emulator is
 * configured.
 *
 * **Seeding is synthetic.** A session document stores a user id and validates
 * nothing against a users collection (a document store has no join), so [newUser]
 * is just a fresh id — recorded in a map that backs the store's
 * [se.soderbjorn.lunicle.FirestoreSessionStore] user-resolver seam, standing in for
 * the identity store the module wires in production. The store runs over a mutable
 * clock, exactly as the SQLite fixture, so [advancePastSessionLifetime] can age a
 * session past its thirty-day lifetime without waiting.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreSessionStore
import se.soderbjorn.lunicle.UserRecord
import se.soderbjorn.lunicle.clientserver.AuthProvider

class FirestoreSessionStoreContractTest : SessionStoreContract() {
    private val fixture = FirestoreContractFixture()

    private var clock = 1_700_000_000_000L
    private var seq = 7_000L
    private val users = mutableMapOf<Long, UserRecord>()

    override val store: SessionStore by lazy {
        FirestoreSessionStore(
            fixture.firestore,
            resolveUser = { id -> users[id] },
            now = { clock },
        )
    }

    override suspend fun newUser(): Long {
        val id = ++seq
        users[id] = UserRecord(
            id = id,
            provider = AuthProvider.GITHUB,
            providerId = "sub-$id",
            providerName = "User $id",
            displayNameOverride = null,
            email = "user$id@example.com",
            isEmailVerified = true,
            isSysAdmin = false,
        )
        return id
    }

    override fun advancePastSessionLifetime() {
        clock += 31L * 24 * 60 * 60 * 1000
    }

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
