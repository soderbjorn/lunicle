/**
 * The API token contract, run against the SQLite reference implementation (LNL-222).
 * The assertions live in [ApiTokenStoreContract].
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.ProviderIdentity
import se.soderbjorn.lunicle.UserStore
import se.soderbjorn.lunicle.clientserver.AuthProvider
import kotlin.test.AfterTest

class SqlDelightApiTokenStoreContractTest : ApiTokenStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private var clockMillis = 1_000_000L
    private var seq = 0

    private val users = UserStore(fixture.database)

    override val store: ApiTokenStore = se.soderbjorn.lunicle.ApiTokenStore(fixture.database, now = { clockMillis })

    override val now: Long get() = clockMillis

    override fun advanceTime(millis: Long) {
        clockMillis += millis
    }

    /** A real account: the table's foreign key would refuse a token for anybody else. */
    override suspend fun userId(): Long {
        val n = seq++
        return users.upsert(ProviderIdentity(AuthProvider.GITHUB, "api-$n", "User $n", null)).id
    }

    @AfterTest
    fun tearDown() = fixture.close()
}
