/**
 * The users table, and the server's own view of a signed-in person.
 *
 * The distinction this file draws is the one worth reading: [UserRecord] is who
 * someone *is* — including a database id, the provider's id for them, their
 * email and whether they are the instance admin — while
 * [se.soderbjorn.lunicle.clientserver.SignedInUser] is only what a client is
 * allowed to know. The conversion goes one way, deliberately, and it drops the
 * provider id on the floor. It carries the email — but only the caller's *own*,
 * which is what the User tab edits and the notification toggles check; a
 * directory of everyone's addresses still never crosses. See [toSignedInUser].
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
 * @property isEmailVerified whether anybody ever proved control of [email] —
 *   by confirming it with a mailed code, or by Google saying so. False for every
 *   address that predates LNL-71, because none of them was ever checked.
 *
 *   Moves with [email] and never independently: [UserStore.setEmail] writes both,
 *   because a flag left describing the previous address is precisely the lie this
 *   is for. See Users.sq.
 * @property kind whether this account belongs to the deployment's own domain —
 *   staff, or member for everybody else. Derived, never chosen: see
 *   [UserKind.forEmail], which sign-in and the startup stamp both call, so the two
 *   cannot reach different answers.
 * @property isInstanceAdmin whether this account runs the instance
 *   (`users.instance_role = 'admin'`). Global rather than per-project, which is
 *   why it is a column here rather than a project rung — the first user to sign in
 *   runs the whole instance, before any project exists to hold a rung in.
 *
 *   **Not the top of the ladder.** The instance *owner* is senior to this and is
 *   not on this record at all: it is `instance_settings.owner_user_id`, and only
 *   [AccessControl.instanceRole] can see it. See [storedInstanceRole], which is
 *   what this record can honestly answer on its own.
 * @property isMcpEnabled whether this user has **turned on** agent access. Their
 *   own choice, made from the MCP tab of their profile dialog. Global for the same
 *   reason [isInstanceAdmin] is, and — unlike it — not an affordance: it is checked
 *   server-side, so switching it off cuts live agents off mid-conversation. Never
 *   crosses the wire on this type; the Connections section reads it from its own
 *   endpoint. See McpRoutes.
 */
data class UserRecord(
    val id: Long,
    val provider: AuthProvider,
    val providerId: String,
    val providerName: String,
    val displayNameOverride: String?,
    val email: String?,
    val isEmailVerified: Boolean = false,
    val kind: UserKind = UserKind.MEMBER,
    val isInstanceAdmin: Boolean = false,
    val isMcpEnabled: Boolean = false,
) {
    /**
     * Does this account have permission to hold agent access at all?
     *
     * **Everybody, for now.** `users.mcp_allowed` — an administrator's per-account
     * grant — was dropped in LNL-191 along with the rest of the old privilege
     * columns, and what replaces it is a permission attached to a rung on the
     * instance ladder rather than a box ticked per person.
     *
     * The property survives the column deliberately, and is still what every gate
     * reads (see [canUseMcp]): the five MCP gates already go through here, so
     * restoring the permission is an edit to this one line rather than five.
     *
     * TODO(LNL-192): make this a per-tier rule on [InstanceRole] — the ticket that
     *  brings the deployment's `domain` field brings this with it.
     */
    val isMcpPermitted: Boolean get() = true

    /**
     * May an agent act as this user right now?
     *
     * **The only thing an MCP gate should read.** There are five of them — `/mcp`
     * per request, `/oauth/authorize`, the consent POST, the token exchange and
     * refresh rotation — and five hand-written `isMcpPermitted && isMcpEnabled`
     * pairs would be five chances for one to be written as a single term. The
     * failure mode of that typo is not a broken build, it is a gate that silently
     * lets an unpermitted user through on one of five paths.
     *
     * Both terms, and the order is meaningless: the account has to be permitted
     * ([isMcpPermitted] — an admin granted it, or it is the admin), and the account
     * has to have switched it on. Neither implies the other, and neither
     * substitutes for the other.
     *
     * Admin-ness reaches this property through [isMcpPermitted] and no further. It
     * clears the *permission* an admin would otherwise have to grant; it does not
     * clear [isMcpEnabled], the account's own server-checked switch. That switch
     * has to stay real for an admin too — it is what cuts their agents off
     * mid-conversation when they turn it off — so an admin who has never enabled
     * their own agent access still, correctly, cannot use MCP until they do.
     */
    val canUseMcp: Boolean get() = isMcpPermitted && isMcpEnabled

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
     * [providerId] is deliberately absent — the client has no use for the
     * provider's own id and it identifies the account upstream. [email] *does*
     * cross now, and only here: this type is only ever the caller's own record
     * (or, under impersonation, the account being worn), never a directory, so
     * this is the person's own address going back to them — the User tab shows and
     * edits it, and the notification toggles need to know it exists. Other
     * people's addresses still stop at the server; see this file's preamble and
     * SignedInUser.email. `isSysAdmin` on the wire is an affordance only — it greys out a button
     * the server would refuse anyway. See AccessControl's preamble.
     */
    fun toSignedInUser(): SignedInUser = SignedInUser(
        id = id,
        displayName = resolvedName,
        provider = provider,
        isSysAdmin = isInstanceAdmin,
        hasDisplayNameOverride = displayNameOverride != null,
        email = email,
        isEmailVerified = isEmailVerified,
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
    emailVerified: Long,
    kind: String,
    instanceRole: String?,
    mcpEnabled: Long,
): UserRecord? = parseProvider(provider)?.let {
    UserRecord(
        id = id,
        provider = it,
        providerId = providerId,
        providerName = providerName,
        displayNameOverride = displayName,
        email = email,
        isEmailVerified = emailVerified != 0L,
        kind = UserKind.byKey(kind),
        // Any value but 'admin' — including one written by a newer build — reads as
        // "not an administrator", which is the safe direction for an unknown rung.
        isInstanceAdmin = instanceRole == InstanceRole.ADMIN.key,
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
) : se.soderbjorn.lunicle.store.UserStore {
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
    override suspend fun upsert(identity: ProviderIdentity, kind: UserKind): UserRecord = withContext(DatabaseDispatcher) {
        // Normalized once, here, so both branches below and the write they lead to
        // are all looking at the same spelling. A lookup that normalized
        // differently from its write would miss the row and create a second
        // account, which is the failure this whole ticket exists to prevent.
        val email = normalizeEmail(identity.email)

        database.transactionWithResult {
            // ── Find, by the account key ──────────────────────────────────
            //
            // Before (provider, provider_id), because that pair is now provenance
            // rather than identity: somebody who registered with Google and comes
            // back with a mailed code has a different pair and the same address,
            // and reuniting them with their own row is the entire point.
            if (email != null) {
                val existing = database.usersQueries.findByEmail(email).executeAsOneOrNull()
                if (existing != null) {
                    database.usersQueries.refreshOnSignIn(identity.providerName, kind.key, existing.id)
                    // Built from the row we already have plus the two values the
                    // update just wrote, rather than re-reading. A second SELECT
                    // would be a round-trip to learn what this statement was told
                    // — and `provider`/`provider_id` deliberately keep the values
                    // the row was created with, so they are the existing ones and
                    // not the identity's.
                    return@transactionWithResult userRecordOf(
                        existing.id, existing.provider, existing.provider_id, identity.providerName,
                        existing.display_name, existing.email, 1L,
                        kind.key, existing.instance_role, existing.mcp_enabled,
                    ) ?: error("User ${existing.id} has provider ${existing.provider}, which cannot be parsed.")
                }
            }

            // ── Or create — falling back to the provider pair ─────────────
            //
            // Reached by a genuinely new account, and by a returning *unkeyed* one
            // — a Google account whose address Google would not confirm, which has
            // no key to be found by and must still come back to its own row. The
            // statement's ON CONFLICT is what does that, unchanged.
            //
            // The first-user-becomes-admin rule lives inside that statement as a
            // subquery and is deliberately not lifted into Kotlin here, where it
            // would stop being atomic with the insert.
            val row = database.usersQueries.upsert(
                provider = identity.provider.name,
                providerId = identity.providerId,
                providerName = identity.providerName,
                email = email,
                kind = kind.key,
                addedAt = now(),
            ).executeAsOne()

            userRecordOf(
                row.id, row.provider, row.provider_id, row.provider_name,
                row.display_name, row.email, row.email_verified,
                row.kind, row.instance_role, row.mcp_enabled,
            ) ?: error("Just wrote user ${row.id} with provider ${row.provider}, which cannot be parsed back.")
        }
    }

    /** The user with [id], or null. */
    override suspend fun findById(id: Long): UserRecord? = withContext(DatabaseDispatcher) {
        database.usersQueries.findById(id).executeAsOneOrNull()?.let {
            userRecordOf(
                it.id, it.provider, it.provider_id, it.provider_name,
                it.display_name, it.email, it.email_verified, it.kind, it.instance_role, it.mcp_enabled,
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
    override suspend fun selectAll(): List<UserRecord> = withContext(DatabaseDispatcher) {
        database.usersQueries.selectAll().executeAsList().mapNotNull {
            userRecordOf(
                it.id, it.provider, it.provider_id, it.provider_name,
                it.display_name, it.email, it.email_verified, it.kind, it.instance_role, it.mcp_enabled,
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
    override suspend fun setDisplayName(id: Long, name: String?): Unit = withContext(DatabaseDispatcher) {
        database.usersQueries.setDisplayName(name?.trim()?.takeIf { it.isNotBlank() }, id)
    }

    /**
     * Set or clear this user's e-mail, saying whether it has been proved.
     *
     * ── Why [isVerified] has no default ────────────────────────────────────
     *
     * Because there is no answer that would be right more often than it was
     * wrong. Defaulting to false would let the two paths that *do* prove an
     * address — confirming a mailed code, and Google's own claim — silently
     * discard what they established by forgetting one argument. Defaulting to
     * true would be worse in the obvious direction. So every caller says, and
     * there are only three.
     *
     * The pair is written in one statement for the same reason: an update that
     * moved the address and left the flag behind would leave `email_verified`
     * describing the *previous* address, which is the exact lie the column exists
     * to prevent and a completely silent one. See Users.sq.
     *
     * @param email the address, or null to remove it. Put through
     *   [normalizeEmail] — the same function every other write and every lookup
     *   uses, which is what makes the account key mean anything. Blank becomes
     *   null: an address of "" is not an address, and storing one would make
     *   `email IS NOT NULL` — which the notification recipient queries lean on —
     *   answer true for someone with nowhere to receive mail.
     * @param isVerified whether control of [email] was proved. Forced to false
     *   when the address is being cleared: there is nothing left to have
     *   verified, and a true flag over a null address is a state no reader should
     *   have to consider.
     * @throws org.sqlite.SQLiteException if another account already holds
     *   [email]. Not caught here — a store has no sentence to offer a user — but
     *   it is a real outcome now that the address is unique, and the confirm
     *   endpoint handles it. See `users_email_unique`.
     */
    override suspend fun setEmail(id: Long, email: String?, isVerified: Boolean): Unit = withContext(DatabaseDispatcher) {
        val normalized = normalizeEmail(email)
        database.usersQueries.setEmail(
            email = normalized,
            email_verified = if (normalized != null && isVerified) 1L else 0L,
            id = id,
        )
    }

    /**
     * Turn this user's own MCP switch on or off. **Theirs**, not an admin's — see
     * [setMcpAllowed] for the other half.
     *
     * Deliberately does *not* touch their tokens either way. Off is a gate, not a
     * purge: [se.soderbjorn.lunicle.McpTokenResolver] and the authorize endpoint
     * both re-read this flag on every request, so switching it off stops every
     * agent within one request — and switching it back on restores them without a
     * second trip through the browser. Revoke is what deletes; conflating the two
     * would mean a user who toggled off to think about it came back to find every
     * connection gone.
     */
    override suspend fun setMcpEnabled(id: Long, isEnabled: Boolean): Unit = withContext(DatabaseDispatcher) {
        database.usersQueries.setMcpEnabled(if (isEnabled) 1L else 0L, id)
    }

    /**
     * Set this user's derived staff/member kind.
     *
     * Not a switch anybody sets: the only two callers are sign-in and the startup
     * stamp, and both get their answer from [UserKind.forEmail]. It exists as a
     * store method rather than being folded into the upsert because the stamp has
     * to be able to correct a row whose owner has not signed in since the
     * deployment learned its own domain.
     */
    override suspend fun setKind(id: Long, kind: UserKind): Unit = withContext(DatabaseDispatcher) {
        database.usersQueries.setKind(kind.key, id)
    }

    /**
     * Put this user on the instance ladder, or take them off it.
     *
     * Only [InstanceRole.ADMIN] and null are storable — see Users.sq's
     * `instance_role`. Ownership is a setting and does not come through here, which
     * is why this takes a nullable rung rather than the whole enum.
     */
    override suspend fun setInstanceAdmin(id: Long, isAdmin: Boolean): Unit = withContext(DatabaseDispatcher) {
        database.usersQueries.setInstanceRole(InstanceRole.ADMIN.key.takeIf { isAdmin }, id)
    }
}
