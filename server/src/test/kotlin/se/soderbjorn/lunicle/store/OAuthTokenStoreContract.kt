/**
 * The behaviour every [OAuthTokenStore] implementation must exhibit.
 *
 * This is the parity-critical heart of the OAuth stores, because it is where a
 * document backend is most likely to drift from the reference in a way no compiler
 * would catch:
 *
 *  - **Expiry lives in the lookup.** [OAuthTokenStore.validateAccessToken] accepts
 *    a live access token and rejects an expired or wrong-type one, with no branch
 *    downstream that could forget either check.
 *  - **Rotation spends the old token, once.** [OAuthTokenStore.rotateRefresh] mints
 *    a new pair in the same family and marks the old refresh consumed.
 *  - **A replay is theft.** Presenting an already-consumed refresh token revokes
 *    the *whole family* — the thief's tokens and the victim's — and answers
 *    `ReuseDetected`.
 *  - **The MCP toggle refuses before it consumes.** When the owner cannot use MCP,
 *    rotation answers `Refused` and spends nothing, so flipping the toggle back on
 *    restores the grant rather than tripping the theft path.
 *  - **Revoking a token takes its family.**
 *
 * A subclass per backend supplies the store, a controllable clock, a registered
 * client, and two users — one who may use MCP and one who may not; the assertions
 * live here.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.OAuthTokenStore.RefreshResult

abstract class OAuthTokenStoreContract {
    protected abstract val store: OAuthTokenStore

    /** Move the store's clock forward by [millis]. */
    protected abstract fun advanceTime(millis: Long)

    /** A registered client id a token can reference. */
    protected abstract suspend fun clientId(): String

    /** A seeded user who is permitted to use MCP and has it enabled. */
    protected abstract suspend fun mcpUserId(): Long

    /** A seeded user who cannot use MCP (the toggle is off, or it is not permitted). */
    protected abstract suspend fun nonMcpUserId(): Long

    /** Well past the 60-minute access-token lifetime, well short of the 30-day refresh one. */
    private val twoHoursMillis = 2L * 60 * 60 * 1000

    private suspend fun issue(user: Long, client: String = "") =
        store.issueTokens(
            userId = user,
            clientId = client.ifEmpty { clientId() },
            scope = "mcp",
            resource = "https://example.test/mcp",
        )

    @Test
    fun `a fresh access token validates to its facts, and garbage does not`() = runBlocking {
        val user = mcpUserId()
        val client = clientId()
        val issued = issue(user, client)

        val record = assertNotNull(store.validateAccessToken(issued.accessToken))
        assertEquals(user, record.userId)
        assertEquals(client, record.clientId)
        assertEquals("mcp", record.scope)
        assertEquals("https://example.test/mcp", record.resource)

        assertNull(store.validateAccessToken("not-a-token"))
    }

    @Test
    fun `an expired access token is rejected, while its refresh token is not`(): Unit = runBlocking {
        val issued = issue(mcpUserId())
        advanceTime(twoHoursMillis)

        assertNull(store.validateAccessToken(issued.accessToken))
        // The refresh token lives 30 days, so it is still good.
        assertIs<RefreshResult.Rotated>(store.rotateRefresh(issued.refreshToken))
    }

    @Test
    fun `rotation mints a new usable pair in the same family`(): Unit = runBlocking {
        val issued = issue(mcpUserId())

        val result = store.rotateRefresh(issued.refreshToken)
        assertIs<RefreshResult.Rotated>(result)
        assertNotNull(store.validateAccessToken(result.tokens.accessToken))
    }

    @Test
    fun `replaying a consumed refresh token is theft and revokes the whole family`() = runBlocking {
        val issued = issue(mcpUserId())

        val first = store.rotateRefresh(issued.refreshToken)
        assertIs<RefreshResult.Rotated>(first)

        // The old refresh token, presented again after rotation, has no innocent
        // explanation.
        assertEquals(RefreshResult.ReuseDetected, store.rotateRefresh(issued.refreshToken))

        // The family is dead: even the freshly rotated access token no longer validates.
        assertNull(store.validateAccessToken(first.tokens.accessToken))
    }

    @Test
    fun `rotation refuses, consuming nothing, when the owner cannot use MCP`() = runBlocking {
        val issued = issue(nonMcpUserId())

        assertEquals(RefreshResult.Refused, store.rotateRefresh(issued.refreshToken))
        // Nothing was consumed: a second attempt is still a refusal, not the
        // reuse path a spent-then-retried token would trip.
        assertEquals(RefreshResult.Refused, store.rotateRefresh(issued.refreshToken))
    }

    @Test
    fun `rotateRefresh is invalid for a wrong-prefix or wrong-type token`() = runBlocking {
        val issued = issue(mcpUserId())
        assertEquals(RefreshResult.Invalid, store.rotateRefresh("garbage"))
        // An access token presented at the refresh path is refused on its prefix.
        assertEquals(RefreshResult.Invalid, store.rotateRefresh(issued.accessToken))
    }

    @Test
    fun `revokeByToken kills the whole grant`() = runBlocking {
        val issued = issue(mcpUserId())

        store.revokeByToken(issued.accessToken)

        assertNull(store.validateAccessToken(issued.accessToken))
        assertEquals(RefreshResult.Invalid, store.rotateRefresh(issued.refreshToken))
    }

    @Test
    fun `deleteExpired removes the expired access token and spares the live refresh`() = runBlocking {
        val issued = issue(mcpUserId())
        advanceTime(twoHoursMillis)

        assertEquals(1L, store.deleteExpired())
        assertNull(store.validateAccessToken(issued.accessToken))
        // The refresh survived and is still live.
        assertEquals(1L, store.size())
    }

    @Test
    fun `listGrants reports the connected client`() = runBlocking {
        val user = mcpUserId()
        val client = clientId()
        issue(user, client)

        val grants = store.listGrants(user)
        assertEquals(1, grants.size)
        assertEquals(client, grants.single().clientId)
    }
}
