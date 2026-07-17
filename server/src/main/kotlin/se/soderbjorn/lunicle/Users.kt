/**
 * The users table, and the server's own view of a signed-in person.
 *
 * The distinction this file draws is the one worth reading: [UserRecord] is who
 * someone *is* — including a database id, the provider's id for them, their
 * email and whether they are the instance admin — while
 * [se.soderbjorn.lunicle.clientserver.SignedInUser] is only what a client is
 * allowed to know. The conversion goes one way, deliberately, and it drops the
 * email and the provider id on the floor.
 *
 * @see Database
 * @see OAuthProviders
 * @see AccessControl
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
 * @property id the primary key, and what every ownership question is answered
 *   against.
 * @property provider which provider authenticates them.
 * @property providerId the provider's stable id — Google's `sub`, GitHub's
 *   numeric `id`. Never leaves the server; a client has no use for it and it
 *   identifies the account upstream.
 * @property providerName the name the provider gave us, refreshed on every
 *   sign-in. Never blank.
 * @property displayNameOverride the user's own choice, or null for "no
 *   override". Null is not "no name" — see [resolvedName].
 * @property email what we last learned, or null for "we do not know". Never
 *   crosses the wire; see [toSignedInUser].
 * @property isAdmin whether this is the instance admin. Global rather than
 *   per-project, which is why it is a column here rather than a fourth row in
 *   `roles` — the first user to sign in is the admin of the whole instance,
 *   before any project exists to grant a role in.
 * @property isMcpEnabled whether this user lets agents act for them over MCP.
 *   Global for the same reason [isAdmin] is, and — unlike [isAdmin] — it is not
 *   an affordance. It is checked server-side at /oauth/authorize before a flow may
 *   start and at /mcp before a token resolves to anybody, so switching it off cuts
 *   live agents off mid-conversation. Never crosses the wire on this type; the
 *   Connections section reads it from its own endpoint. See McpRoutes.
 */
data class UserRecord(
    val id: Long,
    val provider: AuthProvider,
    val providerId: String,
    val providerName: String,
    val displayNameOverride: String?,
    val email: String?,
    val isAdmin: Boolean,
    val isMcpEnabled: Boolean = false,
) {
    /**
     * The name to render: the user's override if they set one, else what the
     * provider calls them.
     *
     * Total, one line, and the reason `display_name` is nullable rather than
     * defaulted to the provider's name — copying it in once would freeze it,
     * and a later rename upstream would never follow.
     */
    val resolvedName: String get() = displayNameOverride?.takeIf { it.isNotBlank() } ?: providerName

    /**
     * Narrow to the wire type.
     *
     * [email] and [providerId] are deliberately absent: the client renders a
     * name, a provider and a permission affordance, so nothing else crosses.
     * [isAdmin] does cross, and is an affordance only — it greys out a button
     * the server would refuse anyway. See AccessControl's preamble.
     */
    fun toSignedInUser(): SignedInUser = SignedInUser(
        id = id,
        displayName = resolvedName,
        provider = provider,
        isAdmin = isAdmin,
        hasDisplayNameOverride = displayNameOverride != null,
    )
}

/**
 * Who wrote an issue, a comment or an attachment.
 *
 * ── Why a type, and not two nullable columns' worth of parameters ───────────
 *
 * Because `created_by` and `created_by_external` are exclusive, and the database
 * says so with a CHECK — see Issues.sq. A CHECK is the right backstop and the
 * wrong enforcement: it catches the illegal pair as a constraint violation,
 * which surfaces as a 500 rather than as a sentence anybody can act on. Passing
 * `(Long?, String?)` down through three stores would leave every one of those
 * call sites able to construct the pair, and would leave the reader of any one
 * of them unable to tell that it must not.
 *
 * So the pair is unconstructable instead. [Account] and [External] are the two
 * halves of the CHECK, spelled as a choice; the CHECK goes back to being a
 * backstop that should never fire.
 *
 * ── On [Nobody] ────────────────────────────────────────────────────────────
 *
 * Both columns null is legal and means what it has always meant: nobody. That is
 * what `ON DELETE SET NULL` leaves behind when an account goes, and it is what
 * an unauthenticated write has always recorded. It is a case here rather than a
 * nullable `Author?` so that "we have no author" is a thing a caller says on
 * purpose, rather than a thing it forgets to say.
 */
sealed interface Author {

    /** Somebody with an account. The ordinary case: every write from the web UI. */
    data class Account(val id: Long) : Author

    /**
     * A name with nobody behind it — history imported from another tracker,
     * written by somebody who has never signed in here.
     *
     * Admin-only, and only ever at creation. See `created_by_external` in
     * Issues.sq for why the name is held rather than an account minted for it,
     * and [AccessControl.canAttributeWrites] for who may say it.
     */
    data class External(val name: String) : Author

    /** No author: a deleted account, or a write nobody was signed in for. */
    data object Nobody : Author
}

/**
 * This user as the author of something they are writing now.
 *
 * The ordinary path: every write from the web UI goes through here. Nullable
 * receiver because the routes carry a nullable user, and "nobody was signed in"
 * has always been recordable — see [Author.Nobody].
 *
 * Note there is no way to reach [Author.External] from a [UserRecord], and that
 * is the point: an imported author is not a user and never becomes one.
 */
fun UserRecord?.asAuthor(): Author = this?.let { Author.Account(it.id) } ?: Author.Nobody

/** What goes in `created_by`. Null for both other cases; see [Author]. */
val Author.accountId: Long? get() = (this as? Author.Account)?.id

/** What goes in `created_by_external`. Null for both other cases; see [Author]. */
val Author.externalName: String? get() = (this as? Author.External)?.name

/**
 * The name to render for an author, given a map of resolved account names.
 *
 * Here rather than beside either caller because both BoardRoutes and McpTools
 * answer this question and must answer it identically — an issue's author is the
 * same fact whether a browser or an agent asks. The lookup that builds [names]
 * stays with each caller, since each has its own way to reach the user store;
 * this is the half that decides what the answer *means*, and there is one of it.
 *
 * Null means "no author", which is what the client already renders for an issue
 * whose account was deleted — so an unresolvable id and [Author.Nobody] land in
 * the same place on purpose.
 */
fun Author.displayName(names: Map<Long, String>): String? = when (this) {
    is Author.Account -> names[id]
    // Already a name. Looking it up in `names` would be looking up a string in a
    // map keyed by account id, which is the whole point of the column: there is
    // nothing to resolve, because there is no account.
    is Author.External -> name
    Author.Nobody -> null
}

/**
 * The author of a row, read back from its two columns.
 *
 * The inverse of [accountId]/[externalName], and the only place that decides
 * what an unexpected pair means. The CHECK makes "both set" unwritable, so
 * reaching it means the row predates the constraint or was written around it;
 * preferring the account is the conservative answer, because it is the one that
 * cannot invent an author who does not exist.
 */
fun authorOf(createdBy: Long?, createdByExternal: String?): Author = when {
    createdBy != null -> Author.Account(createdBy)
    createdByExternal != null -> Author.External(createdByExternal)
    else -> Author.Nobody
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
 * Build a [UserRecord] from the columns every user query selects, or null if the
 * row cannot be understood. Shared so [UserStore] and [SessionStore] agree on
 * what a user row means.
 */
internal fun userRecordOf(
    id: Long,
    provider: String,
    providerId: String,
    providerName: String,
    displayName: String?,
    email: String?,
    isAdmin: Long,
    mcpEnabled: Long,
): UserRecord? = parseProvider(provider)?.let {
    UserRecord(
        id = id,
        provider = it,
        providerId = providerId,
        providerName = providerName,
        displayNameOverride = displayName,
        email = email,
        isAdmin = isAdmin != 0L,
        isMcpEnabled = mcpEnabled != 0L,
    )
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
     * and refreshing their provider name and email on every later one.
     *
     * Called by [authRoutes] once per completed sign-in. This is the moment an
     * OAuth identity becomes an account — and, if the table was empty, the
     * moment someone becomes the instance admin. See the `upsert` query.
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
            provider_name = identity.providerName,
            email = identity.email,
            created_at = now(),
        ).executeAsOne()

        userRecordOf(
            row.id, row.provider, row.provider_id, row.provider_name,
            row.display_name, row.email, row.is_admin, row.mcp_enabled,
        ) ?: error("Just wrote user ${row.id} with provider ${row.provider}, which cannot be parsed back.")
    }

    /** The user with [id], or null. */
    suspend fun findById(id: Long): UserRecord? = withContext(DatabaseDispatcher) {
        database.usersQueries.findById(id).executeAsOneOrNull()?.let {
            userRecordOf(
                it.id, it.provider, it.provider_id, it.provider_name,
                it.display_name, it.email, it.is_admin, it.mcp_enabled,
            )
        }
    }

    /**
     * Every account, by name.
     *
     * For the admin's impersonation menu, and deliberately narrow in its one
     * caller: this returns whole [UserRecord]s, emails included, so the route
     * using it is responsible for sending onward only what the menu needs. See
     * Users.sq's `selectAll`.
     *
     * `mapNotNull`, matching [findById]'s tolerance: a row whose provider string
     * cannot be parsed is skipped rather than taking the whole list down with it.
     * A menu missing one entry is a smaller failure than an admin who cannot open
     * the menu at all.
     */
    suspend fun selectAll(): List<UserRecord> = withContext(DatabaseDispatcher) {
        database.usersQueries.selectAll().executeAsList().mapNotNull {
            userRecordOf(
                it.id, it.provider, it.provider_id, it.provider_name,
                it.display_name, it.email, it.is_admin, it.mcp_enabled,
            )
        }
    }

    /**
     * Set or clear this user's display-name override.
     *
     * @param name the override, or null to clear it and fall back to the
     *   provider's name. Blank is normalised to null — an override of "" is a
     *   request to be nameless, which [UserRecord.resolvedName] would quietly
     *   refuse anyway, so it is better not to store it at all.
     */
    suspend fun setDisplayName(id: Long, name: String?): Unit = withContext(DatabaseDispatcher) {
        database.usersQueries.setDisplayName(name?.trim()?.takeIf { it.isNotBlank() }, id)
    }

    /**
     * Turn this user's MCP access on or off.
     *
     * Deliberately does *not* touch their tokens either way. Off is a gate, not a
     * purge: [se.soderbjorn.lunicle.McpTokenResolver] and the authorize endpoint
     * both re-read this flag on every request, so switching it off stops every
     * agent within one request — and switching it back on restores them without a
     * second trip through the browser. Revoke is what deletes; conflating the two
     * would mean a user who toggled off to think about it came back to find every
     * connection gone.
     */
    suspend fun setMcpEnabled(id: Long, isEnabled: Boolean): Unit = withContext(DatabaseDispatcher) {
        database.usersQueries.setMcpEnabled(if (isEnabled) 1L else 0L, id)
    }
}
