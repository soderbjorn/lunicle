/**
 * What Google says, and what we are willing to believe.
 *
 * `exchangeGoogleCode` had no test at all before this. That was survivable while
 * the address it produced was decorative; it stopped being survivable when LNL-73
 * made that address the account key, because the whole of the new protection is
 * one `takeIf` over a claim that Google has always sent and nothing had ever
 * read. `ignoreUnknownKeys` is on — see [createProviderHttpClient] — so deleting
 * the field, misspelling it, or dropping the `@SerialName` would compile, run,
 * and silently go back to trusting an unconfirmed address. Nothing else in the
 * suite would notice.
 *
 * A real [io.ktor.client.HttpClient] over `MockEngine`, so the JSON is actually
 * parsed by the code that ships rather than by a stub agreeing with it.
 *
 * @see exchangeGoogleCode
 * @see ProviderIdentity
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleClaimTest {

    /**
     * The claim is honoured, and the address survives it.
     *
     * The positive half exists mostly to make the negative half below mean
     * something: a resolver that dropped *every* address would pass that one on
     * its own while breaking Google sign-in completely.
     */
    @Test
    fun `a confirmed address becomes the identity`(): Unit = runBlocking {
        val identity = exchange(email = "Alice@Example.COM", emailVerified = true)
        assertEquals(
            "alice@example.com",
            identity.email,
            "A confirmed address was dropped, or reached the account key un-normalized.",
        )
        assertEquals("sub-123", identity.providerId)
    }

    /**
     * An unconfirmed address is not an identity — the assertion this file is for.
     *
     * Without it, anyone can create a Google account, type a victim's address into
     * its profile without confirming it, and sign in to the victim's Lunicle row.
     */
    @Test
    fun `an unconfirmed address is dropped rather than trusted`(): Unit = runBlocking {
        val identity = exchange(email = "victim@example.com", emailVerified = false)
        assertNull(
            identity.email,
            "An address Google would not vouch for became the account key — this is an account takeover.",
        )
    }

    /**
     * An absent claim reads as "not confirmed".
     *
     * The safe direction on both edges: a response that predates the field, and a
     * Google that stops sending it, both produce an unkeyed account rather than a
     * wrongly-keyed one.
     */
    @Test
    fun `an absent claim is treated as unconfirmed`(): Unit = runBlocking {
        val identity = exchange(userInfo = """{"sub":"sub-123","name":"Alice","email":"alice@example.com"}""")
        assertNull(identity.email, "A missing email_verified claim was read as if it said true.")
    }

    /**
     * The account still works, and is still named, without a usable address.
     *
     * Dropping the address must not turn into dropping the user: they get an
     * unkeyed row, found again by `(provider, provider_id)`. And the name still
     * falls back to the local part of the unconfirmed address, because a name is a
     * label rather than a claim about who somebody is — "Google user" for an
     * account that plainly told us a name would be worse rather than safer.
     */
    @Test
    fun `an unconfirmed account keeps its name`(): Unit = runBlocking {
        val identity = exchange(
            userInfo = """{"sub":"sub-123","email":"alice@example.com","email_verified":false}""",
        )
        assertNull(identity.email)
        assertEquals("alice", identity.providerName, "The name fell all the way through to the bare fallback.")
    }

    // ── The optional hosted-domain gate (LNL-125) ────────────────────────────

    /**
     * A matching Workspace domain is admitted — the positive half, so the refusals
     * below mean "wrong domain" rather than "domain gate rejects everything".
     */
    @Test
    fun `a matching hosted domain is admitted`(): Unit = runBlocking {
        val identity = exchange(
            userInfo = """{"sub":"s","name":"A","email":"a@framna.com","email_verified":true,"hd":"framna.com"}""",
            hostedDomain = "framna.com",
        )
        assertEquals("a@framna.com", identity.email)
    }

    /**
     * The account key of this feature: a personal Google account (no `hd`) is
     * refused when the deployment pins a domain, which is exactly the "it grabbed
     * my private Gmail" case the client-side hint alone cannot prevent.
     */
    @Test
    fun `a personal account is refused when a domain is pinned`(): Unit = runBlocking {
        val failure = assertFailsWith<SignInFailure> {
            exchange(email = "someone@gmail.com", emailVerified = true, hostedDomain = "framna.com")
        }
        assertTrue(
            failure.userMessage.contains("framna.com"),
            "The refusal should name the required domain, not leak internals.",
        )
    }

    /** A different Workspace domain is refused just the same. */
    @Test
    fun `a different hosted domain is refused`(): Unit = runBlocking {
        assertFailsWith<SignInFailure> {
            exchange(
                userInfo = """{"sub":"s","email":"a@evil.com","email_verified":true,"hd":"evil.com"}""",
                hostedDomain = "framna.com",
            )
        }
    }

    /** The domain is matched case-insensitively — domains are. */
    @Test
    fun `hosted domain match ignores case`(): Unit = runBlocking {
        val identity = exchange(
            userInfo = """{"sub":"s","email":"a@framna.com","email_verified":true,"hd":"Framna.COM"}""",
            hostedDomain = "framna.com",
        )
        assertEquals("a@framna.com", identity.email)
    }

    /**
     * No configured domain — every non-branded install — leaves the flow untouched:
     * a personal account with no `hd` still signs in.
     */
    @Test
    fun `no configured domain admits any account`(): Unit = runBlocking {
        val identity = exchange(email = "anyone@gmail.com", emailVerified = true, hostedDomain = null)
        assertEquals("anyone@gmail.com", identity.email)
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private suspend fun exchange(
        email: String? = null,
        emailVerified: Boolean = false,
        userInfo: String = """{"sub":"sub-123","name":"Alice","email":"$email","email_verified":$emailVerified}""",
        hostedDomain: String? = null,
    ): ProviderIdentity = exchangeGoogleCode(
        httpClient = googleAnswering(userInfo),
        credentials = ProviderCredentials("client-id", "client-secret"),
        code = "auth-code",
        redirectUri = "https://issues.example.com",
        hostedDomain = hostedDomain,
    )

    /** Google's two endpoints: the token exchange, then userinfo. */
    private fun googleAnswering(userInfo: String): HttpClient = HttpClient(
        MockEngine { request ->
            val body = if (request.url.host.contains("oauth2.googleapis.com")) {
                """{"access_token":"at-123","token_type":"Bearer"}"""
            } else {
                userInfo
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        },
    ) {
        // The same configuration createProviderHttpClient uses, ignoreUnknownKeys
        // included — which is precisely the setting that makes an unread claim
        // invisible, so a test that left it off would be testing a stricter parser
        // than the one that ships.
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
}
