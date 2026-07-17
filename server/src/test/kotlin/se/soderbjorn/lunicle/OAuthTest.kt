/**
 * The authorization server's load-bearing rules, tested against the real database.
 *
 * Three of these guard properties that are invisible when they break, which is
 * why they are here rather than left to a manual pass through the browser flow:
 *
 *  - **PKCE verification.** If it silently passed, every code interception would
 *    succeed and nothing would look wrong from the outside.
 *  - **Refresh-token reuse detection.** The best security property in the design,
 *    and the one that is hardest to notice missing: a replayed token that quietly
 *    worked would be a permanent compromise with no symptom at all.
 *  - **Exact redirect-URI matching.** The check standing between this server and
 *    being an open redirector.
 *
 * Through the real driver, the real pragmas and the real stores, for the reason
 * ForeignKeyTest's preamble gives at length: a test that opened its own
 * connection with its own settings would have passed throughout the bug that file
 * exists for. These tests go through [openDatabase] for the same reason.
 *
 * @see OAuthStores
 * @see OAuthServer
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AuthProvider
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthTest {
    private val file: File = Files.createTempFile("lunicle-oauth", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    /**
     * A clock the tests move by hand.
     *
     * Every store below takes it, so "an expired token" is a variable assignment
     * rather than a sleep. Without this, testing the expiry rules would mean a
     * test that takes an hour, and expiry-on-read would go untested — which is the
     * one place this design deliberately departs from SessionStore.
     */
    private var now: Long = 1_000_000_000_000

    private val clients = OAuthClientStore(database) { now }
    private val loginStates = OAuthLoginStateStore(database) { now }
    private val codes = OAuthCodeStore(database) { now }
    private val tokens = OAuthTokenStore(database) { now }

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── PKCE ─────────────────────────────────────────────────────────────────

    /**
     * The real S256 computation, against a challenge produced the way a client
     * produces one.
     *
     * Deliberately not asserting against a hardcoded digest: that would test that
     * this function agrees with whatever it printed the day it was written. This
     * derives the challenge independently — base64url, no padding, of the SHA-256
     * — which is what an agent does, and is the thing that has to match.
     */
    @Test
    fun `PKCE accepts the verifier that produced the challenge`() {
        val verifier = "a-verifier-with-enough-entropy-to-be-real-1234567890"
        assertTrue(OAuthCrypto.verifyPkceS256(verifier, challengeFor(verifier)))
    }

    @Test
    fun `PKCE rejects a different verifier`() {
        val challenge = challengeFor("the-real-verifier")
        assertFalse(
            OAuthCrypto.verifyPkceS256("a-stolen-code-with-the-wrong-verifier", challenge),
            "PKCE passed a verifier that did not produce the challenge — code interception now works.",
        )
    }

    /**
     * Padding is not optional.
     *
     * Base64 *with* padding of the same digest differs only by trailing '='. If
     * this server ever compared padded against unpadded it would reject every real
     * client, and the failure would read as "PKCE is broken" rather than as an
     * encoding mismatch.
     */
    @Test
    fun `PKCE rejects a padded challenge for the same verifier`() {
        val verifier = "some-verifier"
        val padded = Base64.getUrlEncoder()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        assertFalse(OAuthCrypto.verifyPkceS256(verifier, padded))
    }

    /** Plain PKCE is not accepted by the back door: the verifier is not the challenge. */
    @Test
    fun `PKCE rejects a plain challenge`() {
        assertFalse(
            OAuthCrypto.verifyPkceS256("verifier", "verifier"),
            "A plain (non-S256) challenge verified — the S256-only metadata would be a lie.",
        )
    }

    // ── Exact redirect-URI matching ──────────────────────────────────────────

    @Test
    fun `a registered redirect URI matches exactly and nothing else does`(): Unit = runBlocking {
        val client = clients.register("Claude Code", listOf("http://localhost:53211/callback"), listOf("authorization_code"))

        assertTrue(clients.isRegisteredRedirectUri(client.clientId, "http://localhost:53211/callback"))

        // The port is part of the match. An earlier draft of the plan advised
        // ignoring it because Claude Code's loopback port varies; that was wrong,
        // and this is the assertion that keeps anyone from acting on it. The
        // client registers its actual callback before it ever uses one.
        assertFalse(
            clients.isRegisteredRedirectUri(client.clientId, "http://localhost:9999/callback"),
            "A different port matched — redirect matching is port-insensitive, which is an open redirector.",
        )
        assertFalse(clients.isRegisteredRedirectUri(client.clientId, "http://localhost:53211/callback/extra"))
        assertFalse(clients.isRegisteredRedirectUri(client.clientId, "https://evil.example.com/callback"))
    }

    /**
     * One client's callback is not another's.
     *
     * Without the client_id in the WHERE clause, any registered redirect URI
     * anywhere would validate for everyone — and since registration is
     * unauthenticated, an attacker could register their own callback and then use
     * it against a legitimate client's id.
     */
    @Test
    fun `a redirect URI registered by another client does not match`(): Unit = runBlocking {
        clients.register("Agent A", listOf("http://localhost:1111/cb"), listOf("authorization_code"))
        val second = clients.register("Agent B", listOf("http://localhost:2222/cb"), listOf("authorization_code"))

        assertFalse(
            clients.isRegisteredRedirectUri(second.clientId, "http://localhost:1111/cb"),
            "A callback registered by a different client matched.",
        )
    }

    /** The shape gate at registration. Loopback and app schemes in; plain http to a real host out. */
    @Test
    fun `only loopback http, https and app schemes may be registered`() {
        assertTrue(isAllowedRedirectUri("https://example.com/cb"))
        assertTrue(isAllowedRedirectUri("http://localhost:53211/cb"))
        assertTrue(isAllowedRedirectUri("http://127.0.0.1:53211/cb"))
        assertTrue(isAllowedRedirectUri("cursor://anysphere.cursor-retrieval/oauth/callback"))

        // An authorization code over cleartext to a real host.
        assertFalse(isAllowedRedirectUri("http://example.com/cb"))
        // The one that looks like loopback and is not — this is a real registrable
        // domain, and a naive `contains("localhost")` would wave it through.
        assertFalse(
            isAllowedRedirectUri("http://localhost.evil.example.com/cb"),
            "A domain merely starting with 'localhost' was treated as loopback.",
        )
        assertFalse(isAllowedRedirectUri("not a uri at all"))
    }

    // ── Authorization codes ──────────────────────────────────────────────────

    @Test
    fun `a code can be redeemed exactly once`(): Unit = runBlocking {
        val fixture = seed()
        val code = codes.create(
            userId = fixture.userId,
            clientId = fixture.clientId,
            redirectUri = "http://localhost:1/cb",
            codeChallenge = challengeFor("v"),
            resource = "http://localhost/mcp",
            scope = MCP_SCOPE,
        )

        assertNotNull(codes.consume(code), "A fresh code could not be redeemed.")
        assertNull(
            codes.consume(code),
            "A code was redeemed twice — one consent could mint two independent token families.",
        )
    }

    /**
     * Expiry is checked by the query, not by the caller.
     *
     * This is the rule that departs from SessionStore, whose lookup does not check
     * expiry and whose own comment calls that the next thing to fix. A token or
     * code that outlived its advertised life while sitting in a file on a laptop
     * is that bug with teeth.
     */
    @Test
    fun `an expired code is refused even though its row is still there`(): Unit = runBlocking {
        val fixture = seed()
        val code = codes.create(
            userId = fixture.userId,
            clientId = fixture.clientId,
            redirectUri = "http://localhost:1/cb",
            codeChallenge = challengeFor("v"),
            resource = "http://localhost/mcp",
            scope = MCP_SCOPE,
        )
        // Two minutes and a second later. The row has not been swept — nothing has
        // run — so this is exactly the "expiry checked on read" claim.
        now += 121_000

        assertNull(codes.consume(code), "An expired authorization code was redeemed.")
    }

    @Test
    fun `a code is never stored in the clear`(): Unit = runBlocking {
        val fixture = seed()
        val code = codes.create(
            userId = fixture.userId,
            clientId = fixture.clientId,
            redirectUri = "http://localhost:1/cb",
            codeChallenge = challengeFor("v"),
            resource = "http://localhost/mcp",
            scope = MCP_SCOPE,
        )
        assertFalse(
            dumpTable("oauth_auth_codes").contains(code),
            "The raw authorization code is in the table — a leaked copy would be live credentials.",
        )
    }

    // ── Access tokens ────────────────────────────────────────────────────────

    @Test
    fun `an access token resolves to the user it was issued for`(): Unit = runBlocking {
        val fixture = seed()
        val issued = tokens.issueTokens(fixture.userId, fixture.clientId, MCP_SCOPE, "http://localhost/mcp")

        val record = tokens.validateAccessToken(issued.accessToken)
        assertNotNull(record)
        // The sentence the whole design rests on: a token resolves to a user id,
        // which becomes the same UserRecord a session cookie produces.
        assertEquals(fixture.userId, record.userId)
    }

    @Test
    fun `an expired access token does not validate`(): Unit = runBlocking {
        val fixture = seed()
        val issued = tokens.issueTokens(fixture.userId, fixture.clientId, MCP_SCOPE, "http://localhost/mcp")
        now += 61L * 60 * 1000

        assertNull(tokens.validateAccessToken(issued.accessToken), "An access token outlived its expires_in.")
    }

    /**
     * A refresh token presented as an access token is not one.
     *
     * The type is in the query's WHERE clause, so this cannot be forgotten
     * downstream. Without it, the 30-day refresh token would work at /mcp as a
     * 30-day access token — collapsing the two lifetimes that exist precisely to
     * be different.
     */
    @Test
    fun `a refresh token is not accepted as an access token`(): Unit = runBlocking {
        val fixture = seed()
        val issued = tokens.issueTokens(fixture.userId, fixture.clientId, MCP_SCOPE, "http://localhost/mcp")

        assertNull(tokens.validateAccessToken(issued.refreshToken))
    }

    @Test
    fun `a token is never stored in the clear`(): Unit = runBlocking {
        val fixture = seed()
        val issued = tokens.issueTokens(fixture.userId, fixture.clientId, MCP_SCOPE, "http://localhost/mcp")
        val dump = dumpTable("oauth_tokens")

        assertFalse(dump.contains(issued.accessToken), "The raw access token is in the table.")
        assertFalse(dump.contains(issued.refreshToken), "The raw refresh token is in the table.")
    }

    // ── Refresh rotation and reuse detection ─────────────────────────────────

    @Test
    fun `rotating a refresh token issues a new pair and retires the old one`(): Unit = runBlocking {
        val fixture = seed()
        val first = tokens.issueTokens(fixture.userId, fixture.clientId, MCP_SCOPE, "http://localhost/mcp")

        val result = tokens.rotateRefresh(first.refreshToken)
        assertTrue(result is OAuthTokenStore.RefreshResult.Rotated, "A valid refresh token would not rotate.")
        assertEquals(fixture.userId, result.userId)

        // The new access token works…
        assertNotNull(tokens.validateAccessToken(result.tokens.accessToken))
        // …and the old refresh token is spent. Rotation that left the old one
        // usable would not be rotation, and the reuse detection below would never
        // fire because nothing would ever be a replay.
        assertTrue(
            tokens.rotateRefresh(first.refreshToken) is OAuthTokenStore.RefreshResult.ReuseDetected,
            "A consumed refresh token rotated again — rotation is not actually retiring anything.",
        )
    }

    /**
     * **The best security property in the design**, and the headline of this file.
     *
     * Presenting an already-consumed refresh token has exactly one innocent
     * explanation, which is none: the legitimate holder moved on to the new token,
     * so only a copy made before rotation could still be presenting the old one.
     * We cannot tell the thief from the victim, so the family dies — both of them
     * lose access, and the victim, unlike the thief, notices and re-authenticates.
     * A silent permanent compromise becomes a visible sign-out.
     */
    @Test
    fun `presenting a consumed refresh token kills the whole family`(): Unit = runBlocking {
        val fixture = seed()
        val first = tokens.issueTokens(fixture.userId, fixture.clientId, MCP_SCOPE, "http://localhost/mcp")

        // The legitimate agent refreshes. It now holds `second`.
        val rotated = tokens.rotateRefresh(first.refreshToken)
        assertTrue(rotated is OAuthTokenStore.RefreshResult.Rotated)
        val second = rotated.tokens

        // Everything is fine at this point.
        assertNotNull(tokens.validateAccessToken(second.accessToken))

        // Now a thief presents the OLD refresh token — the copy they made earlier.
        val replay = tokens.rotateRefresh(first.refreshToken)
        assertTrue(
            replay is OAuthTokenStore.RefreshResult.ReuseDetected,
            "A replayed refresh token was not recognised as theft.",
        )

        // The thief gets nothing…
        assertNull(
            tokens.validateAccessToken(second.accessToken),
            "The victim's access token still works after a detected replay — the family was not revoked.",
        )
        // …and neither does the victim, which is the point. Both tokens are dead,
        // so the legitimate agent must redo the browser flow and the human finds
        // out that something went wrong.
        assertTrue(
            tokens.rotateRefresh(second.refreshToken) is OAuthTokenStore.RefreshResult.Invalid,
            "The victim's refresh token survived the family revocation.",
        )
        assertEquals(0L, tokens.size(), "The family's rows are still in the table after revocation.")
    }

    /**
     * The toggle is refused at rotation — but the grant survives it.
     *
     * This is a regression test for a bug that only appeared when the real flow
     * was driven end to end, and it is the exact sequence a user performs:
     *
     *   toggle off → the agent (which is now getting 401s) retries a refresh →
     *   toggle back on → the agent works again.
     *
     * The first implementation checked `mcp_enabled` in the route, *after*
     * rotation, mirroring Framnaflow — which revokes the family there because its
     * check is a whitelist, and being de-whitelisted is permanent. Ours is a
     * toggle whose entire promise is that it is reversible. Checking after
     * rotation consumed the agent's token and handed back nothing, so the retry
     * looked like a replay and the reuse detection destroyed the family — via the
     * theft path, while the UI promised the connection would come back.
     *
     * So: refused, nothing consumed, nothing revoked, and it works again on the
     * way back. See OAuthTokenStore.rotateRefresh.
     */
    @Test
    fun `toggling MCP off refuses a refresh without destroying the grant`(): Unit = runBlocking {
        val fixture = seed()
        val issued = tokens.issueTokens(fixture.userId, fixture.clientId, MCP_SCOPE, "http://localhost/mcp")
        val users = UserStore(database)

        users.setMcpEnabled(fixture.userId, false)

        // Refused while off — the 30-day token does not outlive the toggle.
        assertTrue(
            tokens.rotateRefresh(issued.refreshToken) is OAuthTokenStore.RefreshResult.Refused,
            "A refresh succeeded while the owner had MCP switched off.",
        )
        // …and refusing must not have spent it. A second attempt while still off
        // is the agent retrying, and it must NOT read as a replay.
        assertTrue(
            tokens.rotateRefresh(issued.refreshToken) is OAuthTokenStore.RefreshResult.Refused,
            "Retrying while off was treated as reuse — the refusal consumed the token.",
        )
        assertEquals(2L, tokens.size(), "The refused rotation minted or destroyed rows; it must do neither.")

        // Back on: the same token the agent has been holding all along works, with
        // no second trip through the browser.
        users.setMcpEnabled(fixture.userId, true)
        assertTrue(
            tokens.rotateRefresh(issued.refreshToken) is OAuthTokenStore.RefreshResult.Rotated,
            "Turning MCP back on did not restore the grant — the toggle is not reversible after all.",
        )
    }

    /**
     * Revoking reaches the whole grant, from either half of it.
     *
     * Revoking one token while leaving its partner able to mint replacements would
     * be revocation that does not revoke.
     */
    @Test
    fun `revoking an access token kills its refresh token too`(): Unit = runBlocking {
        val fixture = seed()
        val issued = tokens.issueTokens(fixture.userId, fixture.clientId, MCP_SCOPE, "http://localhost/mcp")

        tokens.revokeByToken(issued.accessToken)

        assertNull(tokens.validateAccessToken(issued.accessToken))
        assertTrue(
            tokens.rotateRefresh(issued.refreshToken) is OAuthTokenStore.RefreshResult.Invalid,
            "The refresh token survived its access token's revocation and can mint a new pair.",
        )
    }

    /** RFC 7009: revoking something that was never a token is not an error. */
    @Test
    fun `revoking an unknown token is silent`(): Unit = runBlocking {
        tokens.revokeByToken("lun_at_nothing-was-ever-issued-with-this-value")
    }

    // ── Grants, as the Connections list sees them ────────────────────────────

    @Test
    fun `revoking one user's client does not disconnect another user's`(): Unit = runBlocking {
        val first = seed(providerId = "one")
        val second = seed(providerId = "two", clientName = "Shared Agent")
        // Both users connected the SAME client — which is the real case, because
        // DCR registers the software and not the person.
        val firstTokens = tokens.issueTokens(first.userId, second.clientId, MCP_SCOPE, "http://localhost/mcp")
        val secondTokens = tokens.issueTokens(second.userId, second.clientId, MCP_SCOPE, "http://localhost/mcp")

        tokens.revokeForUserAndClient(first.userId, second.clientId)

        assertNull(tokens.validateAccessToken(firstTokens.accessToken), "Revoke did not disconnect the caller.")
        assertNotNull(
            tokens.validateAccessToken(secondTokens.accessToken),
            "One user revoking a client disconnected it for everybody else on the instance.",
        )
    }

    @Test
    fun `a grant appears once per client however many times it has rotated`(): Unit = runBlocking {
        val fixture = seed(clientName = "Claude Code")
        val issued = tokens.issueTokens(fixture.userId, fixture.clientId, MCP_SCOPE, "http://localhost/mcp")
        val connectedAt = now

        // An hour of ordinary use: the agent refreshes twice.
        now += 30L * 60 * 1000
        val once = tokens.rotateRefresh(issued.refreshToken)
        assertTrue(once is OAuthTokenStore.RefreshResult.Rotated)
        now += 30L * 60 * 1000
        assertTrue(tokens.rotateRefresh(once.tokens.refreshToken) is OAuthTokenStore.RefreshResult.Rotated)

        val grants = tokens.listGrants(fixture.userId)
        assertEquals(1, grants.size, "Rotation multiplied one grant into several rows in the Connections list.")
        assertEquals("Claude Code", grants[0].clientName)
        // MIN(created_at), not MAX: otherwise every refresh would reset "connected
        // 3 days ago" to "connected just now" and the date would be meaningless.
        assertEquals(
            connectedAt,
            grants[0].connectedAt,
            "Refreshing reset the connection date — MIN(created_at) is not being used.",
        )
    }

    @Test
    fun `last used is null until the agent actually calls`(): Unit = runBlocking {
        val fixture = seed()
        val issued = tokens.issueTokens(fixture.userId, fixture.clientId, MCP_SCOPE, "http://localhost/mcp")

        assertNull(
            tokens.listGrants(fixture.userId).single().lastUsedAt,
            "A never-used connection reports a last-used time.",
        )

        now += 5_000
        tokens.validateAccessToken(issued.accessToken)

        assertEquals(
            now,
            tokens.listGrants(fixture.userId).single().lastUsedAt,
            "Presenting an access token did not record that the agent was used.",
        )
    }

    /**
     * Deleting a user takes their tokens with them.
     *
     * The cascade the plan calls load-bearing. A token row outliving its user
     * would authenticate as a user id that no longer resolves, and every lookup
     * downstream assumes that cannot happen.
     */
    @Test
    fun `deleting a user deletes their tokens`(): Unit = runBlocking {
        val fixture = seed()
        val issued = tokens.issueTokens(fixture.userId, fixture.clientId, MCP_SCOPE, "http://localhost/mcp")
        assertNotNull(tokens.validateAccessToken(issued.accessToken))

        kotlinx.coroutines.withContext(DatabaseDispatcher) {
            opened.driver.execute(null, "DELETE FROM users WHERE id = ${fixture.userId};", 0)
        }

        assertNull(tokens.validateAccessToken(issued.accessToken), "A deleted user's access token still works.")
        assertEquals(0L, tokens.size())
    }

    // ── Login state ──────────────────────────────────────────────────────────

    @Test
    fun `a pending authorization expires`(): Unit = runBlocking {
        val fixture = seed()
        val id = loginStates.create(
            clientId = fixture.clientId,
            redirectUri = "http://localhost:1/cb",
            codeChallenge = challengeFor("v"),
            resource = "http://localhost/mcp",
            clientState = "the-agent's-own-state",
            scope = MCP_SCOPE,
            userId = fixture.userId,
        )
        assertNotNull(loginStates.find(id))

        now += 11L * 60 * 1000
        assertNull(loginStates.find(id), "A pending authorization outlived its ten minutes.")
    }

    /**
     * The client's `state` survives the round trip byte for byte.
     *
     * It is the agent's CSRF protection and ours is a different value entirely —
     * conflating the two would break the client's half of the flow, and the
     * symptom would be an agent rejecting a callback it asked for.
     */
    @Test
    fun `the client's own state is stored verbatim`(): Unit = runBlocking {
        val fixture = seed()
        val clientState = "opaque/state+with=odd&chars"
        val id = loginStates.create(
            clientId = fixture.clientId,
            redirectUri = "http://localhost:1/cb",
            codeChallenge = challengeFor("v"),
            resource = "http://localhost/mcp",
            clientState = clientState,
            scope = MCP_SCOPE,
            userId = fixture.userId,
        )
        assertEquals(clientState, loginStates.find(id)?.clientState)
    }

    // ── Client sweeping ──────────────────────────────────────────────────────

    /**
     * The counterweight to unauthenticated registration — which must not sweep a
     * client somebody is actually using.
     */
    @Test
    fun `the sweep removes abandoned registrations but never one holding a token`(): Unit = runBlocking {
        val abandoned = clients.register("Drive-by", listOf("http://localhost:1/cb"), listOf("authorization_code"))
        val fixture = seed(providerId = "live", clientName = "In use")
        tokens.issueTokens(fixture.userId, fixture.clientId, MCP_SCOPE, "http://localhost/mcp")

        now += 8L * 24 * 60 * 60 * 1000
        val removed = clients.sweepStale()

        assertEquals(1L, removed, "The sweep removed the wrong number of registrations.")
        assertNull(clients.find(abandoned.clientId), "An abandoned registration survived the sweep.")
        assertNotNull(
            clients.find(fixture.clientId),
            "The sweep deleted a client holding a live token — someone's agent just broke.",
        )
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private class Fixture(val userId: Long, val clientId: String)

    /**
     * A user with MCP switched on, and a registered client.
     *
     * The toggle is set explicitly because the column defaults to 0 and
     * `upsert` deliberately never touches it — so a freshly seeded user has agent
     * access *off*, which is correct for production and wrong as a starting point
     * here. Every test below concerns a user who has a token, and a user can only
     * have one by having enabled MCP and clicked Approve.
     */
    private suspend fun seed(providerId: String = "tester", clientName: String = "Claude Code"): Fixture {
        val users = UserStore(database)
        val user = users.upsert(
            ProviderIdentity(
                provider = AuthProvider.GITHUB,
                providerId = providerId,
                providerName = "tester",
                email = null,
            ),
        )
        users.setMcpEnabled(user.id, true)
        val client = clients.register(clientName, listOf("http://localhost:1/cb"), listOf("authorization_code"))
        return Fixture(user.id, client.clientId)
    }

    /** S256, computed the way a client computes it. See the PKCE tests. */
    private fun challengeFor(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    /**
     * Every value in a table, as one string.
     *
     * For the "never stored in the clear" tests, and deliberately blunt: it dumps
     * whatever columns exist rather than naming the one that should hold the hash,
     * so adding a column that accidentally carries the raw secret would still be
     * caught.
     */
    private suspend fun dumpTable(table: String): String = kotlinx.coroutines.withContext(DatabaseDispatcher) {
        opened.driver.executeQuery(
            identifier = null,
            sql = "SELECT * FROM $table;",
            mapper = { cursor ->
                val text = StringBuilder()
                while (cursor.next().value) {
                    var column = 0
                    while (true) {
                        val value = runCatching { cursor.getString(column) }.getOrNull() ?: break
                        text.append(value).append(' ')
                        column++
                    }
                }
                app.cash.sqldelight.db.QueryResult.Value(text.toString())
            },
            parameters = 0,
        ).value
    }
}
