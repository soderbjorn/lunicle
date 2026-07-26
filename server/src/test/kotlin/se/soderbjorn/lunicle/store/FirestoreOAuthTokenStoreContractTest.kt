/**
 * The OAuth token contract — the parity-critical heart of the OAuth stores — run
 * against the **Firestore** implementation on the emulator, the mirror of
 * [SqlDelightOAuthTokenStoreContractTest].
 *
 * Same assertions ([OAuthTokenStoreContract]), different backend; skipped when no
 * emulator is configured.
 *
 * **Seeding.** The client a token references is registered through the Firestore
 * client store (so `listGrants` resolves its name), on the same emulator namespace.
 * The MCP gate the SQLite store reads from the users table is supplied here as a
 * predicate over the seeded id sets — `mcpUserId` lands in `mcpUsers`, `nonMcpUserId`
 * does not — which is what lets the `Rotated`/`ReuseDetected` paths run and the
 * `Refused` path refuse without consuming. User ids are synthetic Longs; the token
 * store validates none of them.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.junit.Assume.assumeTrue
import se.soderbjorn.lunicle.FirestoreOAuthClientStore
import se.soderbjorn.lunicle.FirestoreOAuthTokenStore

class FirestoreOAuthTokenStoreContractTest : OAuthTokenStoreContract() {
    private val fixture = FirestoreContractFixture()
    private var clockMillis = 0L
    private var seq = 5_000L
    private val mcpUsers = mutableSetOf<Long>()

    private val clients by lazy { FirestoreOAuthClientStore(fixture.firestore, now = { clockMillis }) }

    override val store: OAuthTokenStore by lazy {
        FirestoreOAuthTokenStore(fixture.firestore, canUseMcp = { it in mcpUsers }, now = { clockMillis })
    }

    override fun advanceTime(millis: Long) {
        clockMillis += millis
    }

    override suspend fun clientId(): String {
        val n = ++seq
        return clients.register("Agent $n", listOf("http://127.0.0.1:$n/cb"), listOf("authorization_code")).clientId
    }

    override suspend fun mcpUserId(): Long {
        val id = ++seq
        mcpUsers += id
        return id
    }

    override suspend fun nonMcpUserId(): Long = ++seq

    @BeforeTest
    fun requireEmulator() = assumeTrue("Firestore emulator not configured", FirestoreEmulator.isAvailable)

    @AfterTest
    fun tearDown() = fixture.close()
}
