/**
 * The consent page and the click it collects — the security boundary of the whole
 * MCP design, through the real routes.
 *
 * Two findings of the 2026-07-31 review meet here, and both are about what a human
 * knows at the moment they approve:
 *
 *  - **LUS-2.** A probe session cannot complete a consent. Approving is the one act
 *    of impersonation that leaves a credential behind — a thirty-day rotating
 *    refresh token bound to the worn account, which the token path honours without
 *    ever asking about impersonation — so it survives the probe, the boot sweep and
 *    the feature switch being turned off. Everything short of the click still works,
 *    and that is asserted too: a refusal that also broke `/oauth/authorize` would
 *    take away the diagnostic value the facility exists for.
 *  - **LUS-17.** The card names where the code would go and whether this client has
 *    been approved here before. Registration is unauthenticated, so the client name
 *    is a string a stranger chose; before this, that name and the user's own were
 *    the only two facts on the page, and a client called `Claude Code` pointed at an
 *    attacker's host was indistinguishable from the real one.
 *
 * @see OAuthServer
 * @see ProbeGrants
 */
package se.soderbjorn.lunicle

import io.ktor.client.request.cookie
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.parameters
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AuthProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OAuthConsentTest {
    private val file: File = Files.createTempFile("lunicle-consent", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)
    private val sessions = SessionStore(database)
    private val clients = OAuthClientStore(database)
    private val loginStates = OAuthLoginStateStore(database)
    private val codes = OAuthCodeStore(database)
    private val instanceSettings = InstanceSettingsStore(database)
    private val tokens = OAuthTokenStore(
        database,
        canUseMcp = { id -> instanceSettings.canUseMcp(users.findById(id)) },
    )

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    // ── LUS-2: a probe session cannot consent ────────────────────────────────

    @Test
    fun `a probe session is refused at the consent POST`(): Unit = runBlocking {
        val user = seedAgentUser()
        val client = registerClient()
        // A session minted the way an owner-impersonation sign-in mints one: a real
        // row for a real person, carrying the grant it was minted against. That
        // label is the whole of what distinguishes it — see Caller.isProbe.
        val probeSession = sessions.create(user.id, probeId = "a-live-grant")
        val state = loginState(client.clientId, user.id)

        withRoutes { client_ ->
            val response = client_.submitForm(
                url = "/oauth/consent",
                formParameters = parameters {
                    append("login_state", state)
                    append("decision", "approve")
                },
            ) { cookie(SESSION_COOKIE, probeSession) }

            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "A probe session approved a consent — impersonation now mints a token that " +
                    "outlives the probe, the boot sweep and the feature switch.",
            )
            assertContains(response.bodyAsText(), "while impersonating")
        }

        assertTrue(
            tokens.listGrants(user.id).isEmpty(),
            "The refused consent minted a grant anyway.",
        )
    }

    /** The same request, from an ordinary session, still works. */
    @Test
    fun `an ordinary session still completes a consent`(): Unit = runBlocking {
        val user = seedAgentUser()
        val client = registerClient()
        val ordinary = sessions.create(user.id)
        val state = loginState(client.clientId, user.id)

        withRoutes { client_ ->
            val response = client_.submitForm(
                url = "/oauth/consent",
                formParameters = parameters {
                    append("login_state", state)
                    append("decision", "approve")
                },
            ) { cookie(SESSION_COOKIE, ordinary) }

            assertEquals(HttpStatusCode.Found, response.status, "An ordinary consent stopped working.")
            assertContains(
                response.headers["Location"].orEmpty(),
                "code=",
                message = "An approved consent did not redirect with a code.",
            )
        }
    }

    /**
     * The refusal is at the click and nowhere earlier.
     *
     * A probing owner may still drive authorize and watch the client resolve, the
     * redirect validate, `canUseMcp` evaluate and the card render — which is most of
     * what "can this person connect an agent?" means. Losing that would be paying
     * for the fix twice.
     */
    @Test
    fun `a probe session can still reach the consent page`(): Unit = runBlocking {
        val user = seedAgentUser()
        val client = registerClient()
        val probeSession = sessions.create(user.id, probeId = "a-live-grant")

        withRoutes { client_ ->
            val response = client_.get(authorizeUrl(client.clientId)) { cookie(SESSION_COOKIE, probeSession) }
            assertEquals(HttpStatusCode.OK, response.status, "Authorize refused a probe session.")
            assertContains(response.bodyAsText(), "Authorize access")
        }
    }

    // ── LUS-17: what the card says ───────────────────────────────────────────

    @Test
    fun `the consent card names where the code would be sent`(): Unit = runBlocking {
        val user = seedAgentUser()
        val client = registerClient()
        val session = sessions.create(user.id)

        withRoutes { client_ ->
            val body = client_.get(authorizeUrl(client.clientId)) { cookie(SESSION_COOKIE, session) }.bodyAsText()
            assertContains(
                body,
                "http://localhost:53211",
                message = "The consent card does not say where the code would go — the client's " +
                    "self-chosen name is the only identity on the page again.",
            )
            assertContains(body, "You have not approved this application before")
            assertFalse(
                "not an application on your own computer" in body,
                "A loopback callback was warned about; the warning has to stay rare to be read.",
            )
        }
    }

    /** A client the user already holds a token for does not read as new. */
    @Test
    fun `a client approved before is not marked as first use`(): Unit = runBlocking {
        val user = seedAgentUser()
        val client = registerClient()
        val session = sessions.create(user.id)
        tokens.issueTokens(user.id, client.clientId, MCP_SCOPE, "https://example.com/mcp")

        withRoutes { client_ ->
            val body = client_.get(authorizeUrl(client.clientId)) { cookie(SESSION_COOKIE, session) }.bodyAsText()
            assertContains(body, "You have approved this application before")
        }
    }

    /** An https callback to a host on the internet is the shape worth a second look. */
    @Test
    fun `a remote callback is warned about`(): Unit = runBlocking {
        val user = seedAgentUser()
        val client = registerClient("https://agents.evil.example/callback")
        val session = sessions.create(user.id)

        withRoutes { client_ ->
            val body = client_
                .get(authorizeUrl(client.clientId, "https://agents.evil.example/callback"))
                { cookie(SESSION_COOKIE, session) }
                .bodyAsText()
            assertContains(body, "agents.evil.example")
            assertContains(body, "not an application on your own computer")
        }
    }

    /**
     * Userinfo is not the host, and the card must not say it is.
     *
     * `https://localhost@evil.example/cb` is a registerable URI whose host is
     * `evil.example`. A summary that read the part before the `@` would print
     * exactly the string an attacker put there to be printed.
     */
    @Test
    fun `userinfo does not masquerade as the host`(): Unit = runBlocking {
        val user = seedAgentUser()
        val uri = "https://localhost@evil.example/cb"
        val client = registerClient(uri)
        val session = sessions.create(user.id)

        withRoutes { client_ ->
            val body = client_.get(authorizeUrl(client.clientId, uri)) { cookie(SESSION_COOKIE, session) }.bodyAsText()
            assertContains(body, "evil.example")
            assertContains(
                body,
                "not an application on your own computer",
                message = "A userinfo of `localhost` was read as the host, so a remote callback " +
                    "passed as local.",
            )
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /** An account an agent may act as: instance admin, so the tier is permitted, switch on. */
    private suspend fun seedAgentUser(): UserRecord {
        val user = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-1", "Ada", "ada@acme.com"))
        users.setInstanceAdmin(user.id, true)
        users.setMcpEnabled(user.id, true)
        return requireNotNull(users.findById(user.id))
    }

    private suspend fun registerClient(redirectUri: String = LOOPBACK) =
        clients.register("Claude Code", listOf(redirectUri), listOf("authorization_code"))

    private suspend fun loginState(clientId: String, userId: Long): String = loginStates.create(
        clientId = clientId,
        redirectUri = LOOPBACK,
        codeChallenge = "a-challenge",
        resource = "https://example.com/mcp",
        clientState = "",
        scope = MCP_SCOPE,
        userId = userId,
    )

    private fun authorizeUrl(clientId: String, redirectUri: String = LOOPBACK): String =
        "/oauth/authorize?response_type=code&code_challenge_method=S256&code_challenge=abc" +
            "&client_id=$clientId&redirect_uri=${redirectUri.encodeURLParameter()}"

    private fun withRoutes(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            routing { oauthRoutes(dependencies()) }
        }
        block(createClient { followRedirects = false })
    }

    private fun dependencies() = McpDependencies(
        clients = clients,
        loginStates = loginStates,
        codes = codes,
        tokens = tokens,
        sessions = sessions,
        users = users,
        config = OAuthConfig(google = null),
        instanceSettings = instanceSettings,
    )

    private companion object {
        const val LOOPBACK = "http://localhost:53211/callback"
    }
}
