/**
 * Storage for the authorization server: clients, codes, tokens, login state.
 *
 * ── What this file is ───────────────────────────────────────────────────────
 *
 * A port of Framnaflow's `repository/OAuthRepositories.kt`, which has been
 * running this exact flow in production. The shapes, the prefixes, the TTLs and
 * the refresh-family algorithm are all deliberately the same, because token
 * endpoints punish cleverness and the reference has already paid for the
 * mistakes. Where this diverges it is because the storage underneath is
 * different, and every divergence is an improvement forced by that:
 *
 *  - **`consume` is not a race.** Framnaflow reads a Firestore document and then
 *    deletes it, and documents the window between the two. Ours is one
 *    transaction on [DatabaseDispatcher]'s single thread, so two agents
 *    presenting the same code cannot both succeed — the second finds nothing.
 *  - **`rotateRefresh` is not a race**, for the same reason and more sharply: the
 *    read, the reuse check, the mark-consumed and the re-issue are one
 *    transaction. A concurrent replay cannot slip between the check and the mark.
 *  - **Expiry is a WHERE clause**, not a Kotlin comparison after the read. See
 *    OAuthLoginState.sq's `find`.
 *
 * ── The rule every store here follows ───────────────────────────────────────
 *
 * **A secret is returned once and stored only as a hash.** Nothing below has a
 * method that returns a code or a token from storage, because nothing could:
 * the tables hold SHA-256 hashes, and the raw value exists only in the moment it
 * is minted. That is what makes a leaked copy of these tables useless rather than
 * a set of live credentials — which matters more here than for `sessions`, whose
 * ids live in a browser jar rather than in a config file on a laptop.
 *
 * @see OAuthServer
 * @see McpServer
 * @see Database
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.db.LunicleDatabase
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private val logger = LoggerFactory.getLogger("OAuthStores")

/**
 * The one scope this server issues.
 *
 * Flat, and deliberately the only one. An earlier draft proposed
 * `lunicle:read` / `lunicle:write` / `lunicle:admin`; that was rejected because
 * it is two permission systems that can disagree. The token says *who you are*;
 * [AccessControl] says what that means — and it already answers that question for
 * the web app, correctly, from the user's roles. A scope mechanism would be a
 * second opinion, and the interesting failure is the one where the two differ.
 */
const val MCP_SCOPE = "mcp"

/**
 * Prefixes, so a value says what it is before anything looks it up.
 *
 * Not decoration. Each of these lets validation reject a wrong-type credential
 * without touching storage — an access token presented at the refresh endpoint is
 * refused by a string comparison rather than by a database round-trip that
 * happens to find nothing. They are also what makes one of these legible when it
 * turns up in a log or a config file that somebody is trying to debug.
 */
private const val CLIENT_ID_PREFIX = "lun_client_"
private const val LOGIN_STATE_PREFIX = "ls_"
private const val CODE_PREFIX = "lun_code_"
private const val ACCESS_TOKEN_PREFIX = "lun_at_"
private const val REFRESH_TOKEN_PREFIX = "lun_rt_"
private const val FAMILY_PREFIX = "fam_"

/** Token row types, as stored in `oauth_tokens.type`. */
private const val TYPE_ACCESS = "access"
private const val TYPE_REFRESH = "refresh"

/** How long an access token lives. 60 minutes, as the reference. */
private const val ACCESS_TOKEN_LIFETIME_MILLIS: Long = 60L * 60 * 1000

/**
 * How long a refresh token lives.
 *
 * Thirty days, matching [SESSION_LIFETIME_MILLIS] — which is not a coincidence
 * worth leaving implicit. An agent's grant should not outlive the browser session
 * of the person who approved it by an order of magnitude; the two are the same
 * human's continued involvement, expressed twice.
 */
private const val REFRESH_TOKEN_LIFETIME_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

/**
 * How long an authorization code lives. Two minutes.
 *
 * An agent exchanges a code the instant its callback fires, so this is already
 * generous by two orders of magnitude. A code still unredeemed after two minutes
 * has not been slow — it has been lost or intercepted.
 */
private const val AUTH_CODE_LIFETIME_MILLIS: Long = 2L * 60 * 1000

/**
 * How long a pending authorization lives. Ten minutes.
 *
 * It has to span the whole worst case in one go: a signed-out user meeting the
 * Google or GitHub popup, finding their password, and then reading the consent
 * page. Anything tighter expires under someone who is doing exactly what we asked.
 */
private const val LOGIN_STATE_LIFETIME_MILLIS: Long = 10L * 60 * 1000

/**
 * How long an unused client registration survives.
 *
 * The counterweight to `/oauth/register` being unauthenticated on a volume with a
 * half-gigabyte trial ceiling. Seven days is long enough that no real flow could
 * hit it — a client is touched at every `/authorize`, and one holding a live token
 * is exempt regardless (see OAuthClients.sq's `deleteStale`) — and short enough
 * that a bot spraying registrations gains nothing durable.
 */
private const val STALE_CLIENT_AGE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

/**
 * Random values and hashes, in one place.
 *
 * Everything secret in this design is minted here, so the choice of RNG is made
 * once rather than at five call sites — see [random].
 */
object OAuthCrypto {
    /**
     * [SecureRandom], never [kotlin.random.Random].
     *
     * The same rule [SessionStore] states for session ids, and for the same
     * reason: every value below is a bearer credential for somebody's whole
     * account. `Random` is seeded predictably enough that one token could be
     * derived from another, which is the entire attack.
     */
    private val random = SecureRandom()

    /** SHA-256, hex. What every secret is stored as, and never the secret itself. */
    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun randomHex(byteCount: Int): String =
        ByteArray(byteCount).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** A secret: 32 random bytes behind [prefix]. */
    fun randomToken(prefix: String): String = prefix + randomHex(32)

    /** An identifier: 16 random bytes behind [prefix]. Not a secret, but not guessable either. */
    fun randomId(prefix: String): String = prefix + randomHex(16)

    /**
     * PKCE S256: does `BASE64URL(SHA256(verifier))` equal the stored challenge?
     *
     * This is the check that makes the whole flow safe without a client secret,
     * and it is worth being precise about what it defends. The authorization code
     * travels through a browser redirect to a loopback port; anything that could
     * intercept it — another process on the laptop, a malicious app registered for
     * the same custom scheme — could redeem it. PKCE means it cannot: the code is
     * useless without the verifier, and the verifier never left the agent that
     * minted it. The challenge, which is all *we* ever hold, is a hash and is
     * public by design.
     *
     * A plain `==` rather than a constant-time compare, deliberately: the value
     * being compared against is the challenge, which the client transmitted in a
     * URL query parameter and is therefore not a secret at all. There is nothing
     * here for a timing attack to learn.
     */
    fun verifyPkceS256(codeVerifier: String, codeChallenge: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest) == codeChallenge
    }
}

// ── Clients ──────────────────────────────────────────────────────────────────

/**
 * A registered agent.
 *
 * @property clientName what the client called itself at registration. **Chosen by
 *   a stranger.** Escape it wherever it is rendered and never let it be the only
 *   thing a user sees before approving — anyone may register a client named
 *   "Claude Code". See OAuthClients.sq.
 * @property redirectUris the callbacks this client registered, and the only ones
 *   `/authorize` will redirect to. Exact matching; see [OAuthClientStore.isRegisteredRedirectUri].
 */
data class OAuthClientRecord(
    val clientId: String,
    val clientName: String,
    val redirectUris: List<String>,
    val grantTypes: List<String>,
    val createdAt: Long,
    val lastUsedAt: Long,
)

/** Reads and writes `oauth_clients` and `oauth_client_redirect_uris`. */
class OAuthClientStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.OAuthClientStore {
    /**
     * Register a client, unauthenticated, as RFC 7591 intends.
     *
     * The client and its redirect URIs land in one transaction. A client row with
     * no callbacks would be a registration that can never be used and can never be
     * completed — `/authorize` would refuse it forever — so the two are one write
     * or neither.
     */
    override suspend fun register(
        clientName: String,
        redirectUris: List<String>,
        grantTypes: List<String>,
    ): OAuthClientRecord = withContext(DatabaseDispatcher) {
        val clientId = OAuthCrypto.randomId(CLIENT_ID_PREFIX)
        val timestamp = now()
        database.transaction {
            database.oAuthClientsQueries.registerClient(
                client_id = clientId,
                client_name = clientName,
                grant_types = grantTypes.joinToString(" "),
                created_at = timestamp,
                last_used_at = timestamp,
            )
            // distinct(): the composite primary key would refuse a repeat, and a
            // client that listed the same callback twice is being sloppy rather
            // than hostile. Refusing the whole registration over it would be a
            // confusing failure for something we can simply absorb.
            redirectUris.distinct().forEach {
                database.oAuthClientsQueries.addRedirectUri(clientId, it)
            }
        }
        logger.info("MCP: registered client $clientId (${clientName.take(60)}) with ${redirectUris.size} redirect URI(s)")
        OAuthClientRecord(clientId, clientName, redirectUris, grantTypes, timestamp, timestamp)
    }

    /** The client with [clientId], or null. */
    override suspend fun find(clientId: String): OAuthClientRecord? = withContext(DatabaseDispatcher) {
        // Rejected on the prefix before a query runs. A client_id is not a secret,
        // so this is not a security check — it is one round-trip saved on every
        // piece of junk that arrives at an unauthenticated endpoint.
        if (!clientId.startsWith(CLIENT_ID_PREFIX)) return@withContext null
        database.oAuthClientsQueries.findClient(clientId).executeAsOneOrNull()?.let { row ->
            OAuthClientRecord(
                clientId = row.client_id,
                clientName = row.client_name,
                redirectUris = database.oAuthClientsQueries.redirectUrisFor(clientId).executeAsList(),
                grantTypes = row.grant_types.split(" ").filter { it.isNotBlank() },
                createdAt = row.created_at,
                lastUsedAt = row.last_used_at,
            )
        }
    }

    /**
     * Did [clientId] register exactly [redirectUri]?
     *
     * Exact string equality, in SQL. The one check standing between this server
     * and being an open redirector, so it does not do anything clever: no port
     * insensitivity, no prefix matching, no normalisation. An earlier draft of the
     * plan advised ignoring the port because Claude Code's loopback port varies —
     * that was wrong, and following it would have been a real weakening for no
     * gain. The client registers its actual callback via DCR before it ever uses
     * one, so the value presented here is a value this table already holds.
     */
    override suspend fun isRegisteredRedirectUri(clientId: String, redirectUri: String): Boolean =
        withContext(DatabaseDispatcher) {
            database.oAuthClientsQueries.isRegisteredRedirectUri(clientId, redirectUri).executeAsOne()
        }

    /** Note that this client is still in use. Called at `/authorize`. */
    override suspend fun touch(clientId: String): Unit = withContext(DatabaseDispatcher) {
        database.oAuthClientsQueries.touchClient(now(), clientId)
    }

    /**
     * Delete registrations that never became anything.
     *
     * Startup housekeeping, like every sweep in this server. See
     * [STALE_CLIENT_AGE_MILLIS] and OAuthClients.sq's `deleteStale`, which is
     * where the "and holds no tokens" guard lives.
     */
    override suspend fun sweepStale(): Long = withContext(DatabaseDispatcher) {
        val cutoff = now() - STALE_CLIENT_AGE_MILLIS
        database.oAuthClientsQueries.deleteStale(cutoff, cutoff).value
    }

    /** How many clients are registered. For the startup log only. */
    override suspend fun size(): Long = withContext(DatabaseDispatcher) {
        database.oAuthClientsQueries.countAll().executeAsOne()
    }
}

// ── Login state ──────────────────────────────────────────────────────────────

/**
 * An authorization request waiting on a human's consent click.
 *
 * @property userId who is authorizing, resolved from the session cookie when the
 *   consent page was rendered — never re-read at the POST. That is deliberate:
 *   the page said "act as you, <name>", and a cookie that changed in between
 *   would otherwise mint a code naming somebody the human was never shown. See
 *   OAuthServer's consent route.
 * @property clientState the agent's own `state` parameter, echoed back verbatim.
 *   Not ours; ours is [id]. Conflating the two would break the client's CSRF
 *   protection, which is what its `state` is for.
 */
data class OAuthLoginStateRecord(
    val id: String,
    val clientId: String,
    val redirectUri: String,
    val codeChallenge: String,
    val resource: String,
    val clientState: String,
    val scope: String,
    val userId: Long,
)

/** Reads and writes `oauth_login_state`. */
class OAuthLoginStateStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.OAuthLoginStateStore {
    /**
     * Remember an authorization request across the consent click.
     *
     * @param userId who the consent page is about. Bound here rather than read
     *   again at the POST — see [OAuthLoginStateRecord.userId].
     * @return its id, which the consent form carries back.
     */
    override suspend fun create(
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        resource: String,
        clientState: String,
        scope: String,
        userId: Long,
    ): String = withContext(DatabaseDispatcher) {
        val id = OAuthCrypto.randomId(LOGIN_STATE_PREFIX)
        database.oAuthLoginStateQueries.create(
            id = id,
            client_id = clientId,
            redirect_uri = redirectUri,
            code_challenge = codeChallenge,
            resource = resource,
            client_state = clientState,
            scope = scope,
            user_id = userId,
            expires_at = now() + LOGIN_STATE_LIFETIME_MILLIS,
        )
        id
    }

    /** The pending authorization with [id], or null if unknown or expired. */
    override suspend fun find(id: String?): OAuthLoginStateRecord? = withContext(DatabaseDispatcher) {
        if (id == null) return@withContext null
        database.oAuthLoginStateQueries.find(id, now()).executeAsOneOrNull()?.let {
            OAuthLoginStateRecord(
                id = it.id,
                clientId = it.client_id,
                redirectUri = it.redirect_uri,
                codeChallenge = it.code_challenge,
                resource = it.resource,
                clientState = it.client_state,
                scope = it.scope,
                userId = it.user_id,
            )
        }
    }

    /** Forget it. Idempotent. */
    override suspend fun delete(id: String): Unit = withContext(DatabaseDispatcher) {
        database.oAuthLoginStateQueries.delete(id)
    }

    /** Startup housekeeping. See [OAuthClientStore.sweepStale]. */
    override suspend fun deleteExpired(): Long = withContext(DatabaseDispatcher) {
        database.oAuthLoginStateQueries.deleteExpired(now()).value
    }
}

// ── Authorization codes ──────────────────────────────────────────────────────

/**
 * A redeemed authorization code's contents.
 *
 * Everything here was bound to the code when it was minted and is re-checked at
 * `/token` — the client, the redirect URI, and the PKCE challenge. See
 * OAuthAuthCodes.sq.
 */
data class OAuthCodeRecord(
    val userId: Long,
    val clientId: String,
    val redirectUri: String,
    val codeChallenge: String,
    val resource: String,
    val scope: String,
)

/** Reads and writes `oauth_auth_codes`. */
class OAuthCodeStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.OAuthCodeStore {
    /**
     * Mint a code for an approved authorization.
     *
     * @return the raw code, which is returned to the caller once and never stored
     *   — only its hash is. See this file's preamble.
     */
    override suspend fun create(
        userId: Long,
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        resource: String,
        scope: String,
    ): String = withContext(DatabaseDispatcher) {
        val code = OAuthCrypto.randomToken(CODE_PREFIX)
        database.oAuthAuthCodesQueries.create(
            code_hash = OAuthCrypto.sha256Hex(code),
            user_id = userId,
            client_id = clientId,
            redirect_uri = redirectUri,
            code_challenge = codeChallenge,
            resource = resource,
            scope = scope,
            expires_at = now() + AUTH_CODE_LIFETIME_MILLIS,
        )
        code
    }

    /**
     * Redeem a code, exactly once.
     *
     * The delete IS the consume step, and both halves are in one transaction —
     * which is the whole reason this is not the race Framnaflow documents. Two
     * agents presenting the same code cannot both be served: the transaction
     * serializes them on [DatabaseDispatcher]'s single thread, the first deletes
     * the row, and the second finds nothing.
     *
     * That matters beyond tidiness. A code that could be redeemed twice would mint
     * two independent token families from one consent, which is precisely the
     * shape of an interception attack succeeding alongside the legitimate
     * exchange.
     *
     * @return the code's contents, or null if it is unknown, already redeemed, or
     *   expired. All three are the same answer on purpose: `invalid_grant`, with
     *   nothing said about which.
     */
    override suspend fun consume(code: String): OAuthCodeRecord? = withContext(DatabaseDispatcher) {
        if (!code.startsWith(CODE_PREFIX)) return@withContext null
        val hash = OAuthCrypto.sha256Hex(code)
        database.transactionWithResult {
            val row = database.oAuthAuthCodesQueries.find(hash, now()).executeAsOneOrNull()
                ?: return@transactionWithResult null
            database.oAuthAuthCodesQueries.delete(hash)
            OAuthCodeRecord(
                userId = row.user_id,
                clientId = row.client_id,
                redirectUri = row.redirect_uri,
                codeChallenge = row.code_challenge,
                resource = row.resource,
                scope = row.scope,
            )
        }
    }

    /** Startup housekeeping. */
    override suspend fun deleteExpired(): Long = withContext(DatabaseDispatcher) {
        database.oAuthAuthCodesQueries.deleteExpired(now()).value
    }
}

// ── Tokens ───────────────────────────────────────────────────────────────────

/** A validated token's facts. Never carries the token itself — see this file's preamble. */
data class OAuthTokenRecord(
    val tokenHash: String,
    val userId: Long,
    val clientId: String,
    val scope: String,
    val resource: String,
    val familyId: String,
)

/**
 * A freshly minted pair, on their way to the client and nowhere else.
 *
 * The only place in this server where a raw token exists. It goes straight into
 * the `/token` JSON response and is never logged, never stored, and never
 * returned by a lookup.
 */
data class IssuedTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val scope: String,
)

/** One agent's grant, as the Connections list shows it. */
data class OAuthGrant(
    val clientId: String,
    val clientName: String,
    val connectedAt: Long,
    val lastUsedAt: Long?,
)

/** Reads and writes `oauth_tokens`. The refresh-family algorithm lives here. */
class OAuthTokenStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.OAuthTokenStore {
    /** Mint an access + refresh pair in a brand new family. What a redeemed code produces. */
    override suspend fun issueTokens(
        userId: Long,
        clientId: String,
        scope: String,
        resource: String,
    ): IssuedTokens = withContext(DatabaseDispatcher) {
        database.transactionWithResult {
            issueInFamily(userId, clientId, scope, resource, OAuthCrypto.randomId(FAMILY_PREFIX))
        }
    }

    /**
     * Mint a pair into [familyId]. Must be called inside a transaction.
     *
     * Not suspend and not public: it is the shared tail of [issueTokens] and
     * [rotateRefresh], and the second of those must do its read, its reuse check
     * and this write atomically. A suspend function here would be an invitation to
     * call it outside the transaction that makes rotation safe.
     */
    private fun issueInFamily(
        userId: Long,
        clientId: String,
        scope: String,
        resource: String,
        familyId: String,
    ): IssuedTokens {
        val accessToken = OAuthCrypto.randomToken(ACCESS_TOKEN_PREFIX)
        val refreshToken = OAuthCrypto.randomToken(REFRESH_TOKEN_PREFIX)
        val timestamp = now()
        store(accessToken, TYPE_ACCESS, userId, clientId, scope, resource, familyId, timestamp, ACCESS_TOKEN_LIFETIME_MILLIS)
        store(refreshToken, TYPE_REFRESH, userId, clientId, scope, resource, familyId, timestamp, REFRESH_TOKEN_LIFETIME_MILLIS)
        return IssuedTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresInSeconds = ACCESS_TOKEN_LIFETIME_MILLIS / 1000,
            scope = scope,
        )
    }

    private fun store(
        token: String,
        type: String,
        userId: Long,
        clientId: String,
        scope: String,
        resource: String,
        familyId: String,
        timestamp: Long,
        lifetimeMillis: Long,
    ) {
        database.oAuthTokensQueries.insert(
            token_hash = OAuthCrypto.sha256Hex(token),
            user_id = userId,
            client_id = clientId,
            type = type,
            scope = scope,
            resource = resource,
            family_id = familyId,
            created_at = timestamp,
            expires_at = timestamp + lifetimeMillis,
        )
    }

    /**
     * Validate an access token.
     *
     * The /mcp hot path. Type and expiry are both in the query, so there is no
     * branch here that could forget either — see OAuthTokens.sq's `findValid`.
     *
     * Does *not* answer "may this user do anything". It answers "whose token is
     * this", and that is the whole point of the design: the answer is a user id,
     * which the caller turns into a [UserRecord] and hands to the same
     * [AccessControl] the web app uses. See McpServer.
     *
     * @return the token's facts, or null if unknown, expired, or the wrong type.
     */
    override suspend fun validateAccessToken(token: String): OAuthTokenRecord? = withContext(DatabaseDispatcher) {
        if (!token.startsWith(ACCESS_TOKEN_PREFIX)) return@withContext null
        val hash = OAuthCrypto.sha256Hex(token)
        val row = database.oAuthTokensQueries.findValid(hash, TYPE_ACCESS, now()).executeAsOneOrNull()
            ?: return@withContext null
        // Best-effort, and deliberately after the lookup rather than part of it:
        // "last used" is a fact for the Connections list, not a precondition for
        // anything, so a failure to record it must not fail the request.
        database.oAuthTokensQueries.touch(now(), hash)
        OAuthTokenRecord(
            tokenHash = row.token_hash,
            userId = row.user_id,
            clientId = row.client_id,
            scope = row.scope,
            resource = row.resource,
            familyId = row.family_id,
        )
    }

    /** What [rotateRefresh] can say. */
    sealed interface RefreshResult {
        /** A new pair was minted in the same family, and the old one is spent. */
        data class Rotated(val tokens: IssuedTokens, val userId: Long, val familyId: String) : RefreshResult

        /** Unknown, expired, or not a refresh token. Answered as `invalid_grant`. */
        data object Invalid : RefreshResult

        /**
         * The token is real, but its owner has MCP switched off.
         *
         * **Nothing was consumed and nothing was revoked**, and both halves of that
         * are load-bearing — see [rotateRefresh].
         */
        data object Refused : RefreshResult

        /**
         * An already-consumed refresh token was presented, and the family is now
         * revoked.
         *
         * Deliberately distinct from [Invalid] even though both answer the client
         * with the same `invalid_grant`: they are different events, and this one
         * is worth a log line and a metric if there is ever a metric. Collapsing
         * them would throw away the only theft signal this design has.
         */
        data object ReuseDetected : RefreshResult
    }

    /**
     * Rotate a refresh token, and treat a replay as theft.
     *
     * ── The fifteen lines that are the best part of the design ──────────────
     *
     * Rotation means the old refresh token stops working the moment a new one is
     * issued. So if an already-consumed token is presented, exactly one thing has
     * happened: somebody has a copy that was made before the rotation. The
     * legitimate holder moved on — only a thief still has the old value.
     *
     * We cannot tell which of the two is calling right now, and that is the point:
     * revoking the *whole family* covers both. The thief loses access, the victim
     * loses access, and the victim — unlike the thief — notices, because their
     * agent asks them to authenticate again. A silent permanent compromise becomes
     * a visible sign-out.
     *
     * All of it in one transaction: read, toggle-check, reuse-check, mark
     * consumed, re-issue. A concurrent replay cannot slip between the check and
     * the mark, which is exactly the window a read-then-write would leave open.
     *
     * ── Why the toggle is checked HERE, before anything is consumed ─────────
     *
     * This started as a check in the route, *after* rotation, mirroring
     * Framnaflow. It is wrong there, and the way it is wrong is invisible in a
     * unit test and obvious the moment you drive the real flow:
     *
     * Rotation consumes the old token and mints a new pair. If the route then
     * refuses, the agent never receives the new pair — but the old one is now
     * marked consumed. The agent retries with the only token it has, that
     * presentation looks exactly like a **replay**, and the reuse detection above
     * revokes the whole family. So "toggle off, agent retries, toggle on" would
     * permanently destroy every connection, via the theft path, while the UI
     * cheerfully promised that turning it back on restores them.
     *
     * Checking before consuming makes the refusal a no-op: nothing is spent, the
     * agent keeps a token that becomes valid again the instant the toggle flips
     * back, and the reversibility the Connections section advertises is real. The
     * toggle still cannot be outlived, because this refuses on every path while it
     * is off.
     *
     * The user cannot be missing here: `oauth_tokens.user_id` cascades, so a
     * deleted user's tokens are already gone and [findValid] would not have
     * returned. The elvis is what makes that reasoning explicit rather than a
     * `!!` that would crash if the cascade ever changed.
     */
    override suspend fun rotateRefresh(refreshToken: String): RefreshResult = withContext(DatabaseDispatcher) {
        if (!refreshToken.startsWith(REFRESH_TOKEN_PREFIX)) return@withContext RefreshResult.Invalid
        val hash = OAuthCrypto.sha256Hex(refreshToken)
        database.transactionWithResult {
            val row = database.oAuthTokensQueries.findValid(hash, TYPE_REFRESH, now()).executeAsOneOrNull()
                ?: return@transactionWithResult RefreshResult.Invalid

            val owner = database.usersQueries.findById(row.user_id).executeAsOneOrNull()
                ?: return@transactionWithResult RefreshResult.Invalid
            // [UserRecord.canUseMcp] itself, via the shared row mapper, rather
            // than the raw columns rewritten to match it.
            //
            // This gate is the fifth of the five that property's KDoc enumerates,
            // and it is the one that proves the point it makes: it *was* a
            // hand-written `mcp_enabled != 0 && mcp_allowed != 0`, and when admins
            // became permitted by virtue of being admins (see isMcpPermitted) the
            // other four moved and this one did not. Nothing failed to build. An
            // admin simply got a working agent that died at the first token
            // refresh — the worst shape a permission bug can take, because it
            // looks like an expiry problem and not like a permission one.
            //
            // Building the record costs one already-loaded row's worth of mapping
            // and makes the drift impossible rather than merely unlikely.
            val record = userRecordOf(
                owner.id, owner.provider, owner.provider_id, owner.provider_name,
                owner.display_name, owner.email, owner.email_verified, owner.is_sys_admin,
                owner.mcp_enabled, owner.mcp_allowed,
            ) ?: return@transactionWithResult RefreshResult.Refused
            if (!record.canUseMcp) {
                return@transactionWithResult RefreshResult.Refused
            }

            if (row.consumed != 0L) {
                logger.warn(
                    "MCP: refresh token reuse detected for client ${row.client_id}, user ${row.user_id} " +
                        "— revoking family ${row.family_id}. Every token in it is now dead; the agent must " +
                        "redo the browser flow.",
                )
                database.oAuthTokensQueries.deleteFamily(row.family_id)
                return@transactionWithResult RefreshResult.ReuseDetected
            }

            database.oAuthTokensQueries.markConsumed(hash)
            val tokens = issueInFamily(
                userId = row.user_id,
                clientId = row.client_id,
                scope = row.scope,
                resource = row.resource,
                familyId = row.family_id,
            )
            RefreshResult.Rotated(tokens, row.user_id, row.family_id)
        }
    }

    /** Kill a whole grant. The theft response, and what a re-check failure at refresh does. */
    override suspend fun revokeFamily(familyId: String): Unit = withContext(DatabaseDispatcher) {
        database.oAuthTokensQueries.deleteFamily(familyId)
    }

    /**
     * Revoke a presented token (RFC 7009).
     *
     * A refresh token takes its whole family with it, and so does an access token:
     * revoking one half of a pair while leaving the other able to mint replacements
     * would be revocation that does not revoke. The client asked for this grant to
     * end; ending it is the only honest reading.
     *
     * Silent on an unknown token, because RFC 7009 requires a 200 either way and
     * this is where that starts: an endpoint that behaved differently for a token
     * it recognised would be an oracle for whether a token is real.
     */
    override suspend fun revokeByToken(token: String): Unit = withContext(DatabaseDispatcher) {
        val hash = OAuthCrypto.sha256Hex(token)
        database.transaction {
            val row = database.oAuthTokensQueries.findAny(hash).executeAsOneOrNull()
                ?: return@transaction
            database.oAuthTokensQueries.deleteFamily(row.family_id)
        }
    }

    /**
     * Every agent [userId] has connected.
     *
     * The Connections list. Grouped by client, because that is the unit a human
     * thinks in and the unit Revoke acts on — see OAuthTokens.sq's `listGrantsFor`
     * for why "last used" is a second query rather than another aggregate.
     */
    override suspend fun listGrants(userId: Long): List<OAuthGrant> = withContext(DatabaseDispatcher) {
        database.oAuthTokensQueries.listGrantsFor(userId).executeAsList().map { row ->
            OAuthGrant(
                clientId = row.client_id,
                clientName = row.client_name,
                // connected_at is MIN() over a non-empty group, so SQLite cannot
                // return null here — but the generated type is nullable because
                // the compiler cannot know that. now() is a nonsense fallback that
                // will never be used; it is here so this is total rather than a
                // `!!` that would be a crash if the invariant ever changed.
                connectedAt = row.connected_at ?: now(),
                lastUsedAt = database.oAuthTokensQueries.lastUsedFor(userId, row.client_id)
                    .executeAsOneOrNull()?.MAX,
            )
        }
    }

    /**
     * Revoke one agent, for one user.
     *
     * Scoped by user as well as client, and that is load-bearing rather than
     * belt-and-braces: one `oauth_clients` row is shared by everyone who connected
     * that agent, because DCR registers the software and not the person. Without
     * the user filter, one person clicking Revoke on Claude Code would disconnect
     * it for the whole instance.
     */
    override suspend fun revokeForUserAndClient(userId: Long, clientId: String): Unit =
        withContext(DatabaseDispatcher) {
            database.oAuthTokensQueries.deleteForUserAndClient(userId, clientId)
            logger.info("MCP: user $userId revoked client $clientId")
        }

    /** Startup housekeeping. */
    override suspend fun deleteExpired(): Long = withContext(DatabaseDispatcher) {
        database.oAuthTokensQueries.deleteExpired(now()).value
    }

    /** How many token rows exist. For the startup log only. */
    override suspend fun size(): Long = withContext(DatabaseDispatcher) {
        database.oAuthTokensQueries.countAll().executeAsOne()
    }
}
