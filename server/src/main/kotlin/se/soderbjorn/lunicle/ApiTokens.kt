/**
 * Personal access tokens: minting, and the SQLite reference store (LNL-222).
 *
 * A token is a credential a person makes for their own scripts and integrations, and
 * every request made with it is made **as them** — resolved to the same [UserRecord] a
 * session cookie produces, so every rule AccessControl already enforces applies without
 * being restated. What a token adds is a narrower ceiling (its [ApiTokenScope]) and a
 * name that history can carry, never a capability its owner lacks.
 *
 * @see se.soderbjorn.lunicle.store.ApiTokenStore
 * @see RestApi
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.clientserver.ApiTokenScope
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * A stored token's facts. Never the token — the tables hold only its hash.
 *
 * @property prefix the display prefix, e.g. `lnl_pat_3f9a1c`. See ApiTokens.sq.
 * @property expiresAt epoch millis, or null for never.
 */
data class ApiTokenRecord(
    val id: Long,
    val userId: Long,
    val name: String,
    val prefix: String,
    val scope: ApiTokenScope,
    val createdAt: Long,
    val lastUsedAt: Long?,
    val expiresAt: Long?,
)

/**
 * Minting and recognising the raw value. Above both backends, for the reason the
 * interface's preamble gives: a store sees hashes and nothing else.
 */
object ApiTokenCrypto {
    /**
     * What every personal access token starts with.
     *
     * A fixed, greppable prefix is worth more than it looks: secret scanners (GitHub's
     * included) can be taught to recognise it, a person can tell at a glance which of
     * their credentials this is, and [isApiToken] can turn away an MCP access token or a
     * session id before any lookup. Distinct from the OAuth prefixes so the two kinds of
     * bearer token can never be mistaken for each other.
     */
    const val PREFIX: String = "lnl_pat_"

    /**
     * How much of the random part the display prefix keeps. Six hex characters is 24
     * bits — enough to tell a person's handful of tokens apart, nowhere near enough to
     * shorten a 256-bit guess.
     */
    private const val DISPLAY_CHARACTERS = 6

    /** A new token: [PREFIX] and 32 random bytes, from [OAuthCrypto]'s SecureRandom. */
    fun mint(): String = OAuthCrypto.randomToken(PREFIX)

    /** The hash a token is stored and looked up by. */
    fun hash(token: String): String = OAuthCrypto.sha256Hex(token)

    /** What the person's list shows for [token]. */
    fun displayPrefix(token: String): String = token.take(PREFIX.length + DISPLAY_CHARACTERS)

    /** Whether [value] is shaped like a personal access token at all. */
    fun isApiToken(value: String): Boolean = value.startsWith(PREFIX) && value.length > PREFIX.length
}

/**
 * The SQLite reference implementation of [se.soderbjorn.lunicle.store.ApiTokenStore].
 *
 * @param database the open database.
 * @param now the clock, injectable so a test can expire a token without sleeping.
 */
class ApiTokenStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.ApiTokenStore {
    override suspend fun create(
        userId: Long,
        name: String,
        tokenHash: String,
        tokenPrefix: String,
        scope: ApiTokenScope,
        expiresAt: Long?,
    ): ApiTokenRecord = withContext(DatabaseDispatcher) {
        val createdAt = now()
        val id = database.apiTokensQueries.insert(
            user_id = userId,
            name = name,
            token_hash = tokenHash,
            token_prefix = tokenPrefix,
            scope = scope.key,
            created_at = createdAt,
            expires_at = expiresAt,
        ).executeAsOne()
        ApiTokenRecord(
            id = id,
            userId = userId,
            name = name,
            prefix = tokenPrefix,
            scope = scope,
            createdAt = createdAt,
            lastUsedAt = null,
            expiresAt = expiresAt,
        )
    }

    override suspend fun authenticate(tokenHash: String): ApiTokenRecord? = withContext(DatabaseDispatcher) {
        val timestamp = now()
        val row = database.apiTokensQueries.findByHash(tokenHash, timestamp).executeAsOneOrNull()
            ?: return@withContext null
        // After the lookup, and never a precondition of it — see the interface.
        runCatching { database.apiTokensQueries.touch(timestamp, row.id) }
        ApiTokenRecord(
            id = row.id,
            userId = row.user_id,
            name = row.name,
            prefix = row.token_prefix,
            scope = ApiTokenScope.byKey(row.scope),
            createdAt = row.created_at,
            lastUsedAt = timestamp,
            expiresAt = row.expires_at,
        )
    }

    override suspend fun forUser(userId: Long): List<ApiTokenRecord> = withContext(DatabaseDispatcher) {
        database.apiTokensQueries.forUser(userId).executeAsList().map { row ->
            ApiTokenRecord(
                id = row.id,
                userId = row.user_id,
                name = row.name,
                prefix = row.token_prefix,
                scope = ApiTokenScope.byKey(row.scope),
                createdAt = row.created_at,
                lastUsedAt = row.last_used_at,
                expiresAt = row.expires_at,
            )
        }
    }

    override suspend fun revoke(userId: Long, id: Long): Unit = withContext(DatabaseDispatcher) {
        database.apiTokensQueries.deleteForUser(id, userId)
    }

    override suspend fun deleteExpired(): Long = withContext(DatabaseDispatcher) {
        database.apiTokensQueries.deleteExpired(now()).value
    }
}
