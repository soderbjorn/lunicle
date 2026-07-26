/**
 * The OAuth authorization-code contract, run against the SQLite reference
 * implementation.
 *
 * The assertions live in [OAuthCodeStoreContract]; this file wires SQLite to
 * them — a temp database, a real [se.soderbjorn.lunicle.OAuthCodeStore] on a
 * controllable clock, and the client and user each code points at, seeded through
 * the same stores production uses so every foreign key is satisfied.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import se.soderbjorn.lunicle.ProviderIdentity
import se.soderbjorn.lunicle.UserStore
import se.soderbjorn.lunicle.clientserver.AuthProvider

class SqlDelightOAuthCodeStoreContractTest : OAuthCodeStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private var clockMillis = 0L
    private var seq = 0

    private val clients = se.soderbjorn.lunicle.OAuthClientStore(db)
    private val users = UserStore(db)

    override val store: OAuthCodeStore =
        se.soderbjorn.lunicle.OAuthCodeStore(db, now = { clockMillis })

    override fun advanceTime(millis: Long) {
        clockMillis += millis
    }

    override suspend fun clientId(): String {
        val n = seq++
        return clients.register("Agent $n", listOf("http://127.0.0.1:$n/cb"), listOf("authorization_code")).clientId
    }

    override suspend fun userId(): Long {
        val n = seq++
        return users.upsert(ProviderIdentity(AuthProvider.GITHUB, "code-$n", "User $n", null)).id
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }
}
