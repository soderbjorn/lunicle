/**
 * The OAuth token contract, run against the SQLite reference implementation.
 *
 * The assertions live in [OAuthTokenStoreContract]; this file wires SQLite to
 * them — a temp database, a real [se.soderbjorn.lunicle.OAuthTokenStore] on a
 * controllable clock (so expiry and rotation can be driven deterministically), a
 * registered client, and two users seeded through the production stores: one with
 * MCP permitted and enabled, one without. The MCP-enabled seeding is what lets the
 * `Rotated` and `ReuseDetected` paths run at all — a token whose owner cannot use
 * MCP is refused before rotation, which is itself one of the pinned behaviours.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import se.soderbjorn.lunicle.ProviderIdentity
import se.soderbjorn.lunicle.UserStore
import se.soderbjorn.lunicle.clientserver.AuthProvider

class SqlDelightOAuthTokenStoreContractTest : OAuthTokenStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private var clockMillis = 0L
    private var seq = 0

    private val clients = se.soderbjorn.lunicle.OAuthClientStore(db)
    private val users = UserStore(db)

    // The MCP gate is a seam now (LNL-192): the permission is a per-tier instance
    // setting rather than a column, so the store can no longer answer it from the row
    // it already has. Wired here to the account's own switch, which is what the two
    // seeders below set — matching the Firestore twin's `it in mcpUsers`.
    override val store: OAuthTokenStore =
        se.soderbjorn.lunicle.OAuthTokenStore(
            db,
            now = { clockMillis },
            canUseMcp = { id -> users.findById(id)?.isMcpEnabled == true },
        )

    override fun advanceTime(millis: Long) {
        clockMillis += millis
    }

    override suspend fun clientId(): String {
        val n = seq++
        return clients.register("Agent $n", listOf("http://127.0.0.1:$n/cb"), listOf("authorization_code")).clientId
    }

    override suspend fun mcpUserId(): Long {
        val n = seq++
        val id = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "mcp-$n", "User $n", null)).id
        users.setMcpEnabled(id, true)
        return id
    }

    override suspend fun nonMcpUserId(): Long {
        val n = seq++
        return users.upsert(ProviderIdentity(AuthProvider.GITHUB, "nomcp-$n", "User $n", null)).id
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }
}
