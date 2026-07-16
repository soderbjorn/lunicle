/**
 * The users table, and the server's own view of a signed-in person.
 *
 * The distinction this file draws is the one worth reading: [UserRecord] is who
 * someone *is* — including a database id and the provider's id for them —
 * while [se.soderbjorn.lunicle.clientserver.SignedInUser] is only what a client
 * is allowed to know, which is a name and a provider. The conversion goes one
 * way, deliberately.
 *
 * @see Database
 * @see OAuthProviders
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.SignedInUser
import se.soderbjorn.lunicle.db.LunicleDatabase

private val logger = LoggerFactory.getLogger("Users")

/**
 * A user as this server knows them.
 *
 * @property id the primary key, and the only thing a counter is keyed by.
 * @property provider which provider authenticates them.
 * @property providerId the provider's stable id — Google's `sub`, GitHub's
 *   numeric `id`. Never leaves the server; a client has no use for it and it
 *   identifies the account upstream.
 * @property displayName what to render.
 */
data class UserRecord(
    val id: Long,
    val provider: AuthProvider,
    val providerId: String,
    val displayName: String,
) {
    /** Narrow to the wire type — name and provider, nothing else. */
    fun toSignedInUser(): SignedInUser = SignedInUser(displayName, provider)
}

/**
 * Parse a provider name back out of the database.
 *
 * `valueOf` would throw, and this is a value read from storage rather than one
 * we just produced: a row written by a newer build could name a provider this
 * one has never heard of. That is a reason to ignore a row, not to fail a
 * request, so it comes back null and the caller treats the user as unknown.
 */
private fun parseProvider(name: String): AuthProvider? =
    AuthProvider.entries.firstOrNull { it.name == name }
        ?: run {
            logger.warn("Ignoring a user row with an unrecognised provider: $name")
            null
        }

/**
 * Build a [UserRecord] from the four columns every user query selects, or null
 * if the row cannot be understood. Shared so [UserStore] and [SessionStore]
 * agree on what a user row means.
 */
internal fun userRecordOf(
    id: Long,
    provider: String,
    providerId: String,
    displayName: String,
): UserRecord? = parseProvider(provider)?.let {
    UserRecord(id = id, provider = it, providerId = providerId, displayName = displayName)
}

/**
 * Reads and writes the users table.
 *
 * @param database the open database.
 * @param now supplies the creation timestamp; injectable so a test can pin it.
 */
class UserStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Find the user behind a provider identity, creating them on first sign-in
     * and refreshing their display name on every later one.
     *
     * Called by [authRoutes] once per completed sign-in. This is the moment an
     * OAuth identity becomes an account.
     *
     * @param identity who the provider says this is.
     * @return the stored user.
     * @throws IllegalStateException if the row we just wrote cannot be read
     *   back as a valid provider — impossible unless the enum changed under us,
     *   and not something to paper over.
     */
    suspend fun upsert(identity: ProviderIdentity): UserRecord = withContext(DatabaseDispatcher) {
        val row = database.usersQueries.upsert(
            provider = identity.provider.name,
            provider_id = identity.providerId,
            display_name = identity.displayName,
            created_at = now(),
        ).executeAsOne()

        userRecordOf(row.id, row.provider, row.provider_id, row.display_name)
            ?: error("Just wrote user ${row.id} with provider ${row.provider}, which cannot be parsed back.")
    }

    /** The user with [id], or null. */
    suspend fun findById(id: Long): UserRecord? = withContext(DatabaseDispatcher) {
        database.usersQueries.findById(id).executeAsOneOrNull()
            ?.let { userRecordOf(it.id, it.provider, it.provider_id, it.display_name) }
    }
}
