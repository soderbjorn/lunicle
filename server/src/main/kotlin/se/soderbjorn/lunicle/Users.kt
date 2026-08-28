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
 *   own choice, made from the Connections half of the settings pane's You tab.
 *   Global for the same reason [isInstanceAdmin] is, and — unlike it — not an
 *   affordance: it is checked server-side, so switching it off cuts live agents off
 *   mid-conversation. Never crosses the wire on this type; the Connections section
 *   reads it from its own endpoint. See McpRoutes.
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
    /**
     * When somebody last signed into this account, or null because nobody ever has
     * (LNL-194).
     *
     * Null is the account an administrator **added** and whose owner has not turned
     * up: it holds rungs, it appears in the Access list badged NOT SIGNED IN, and it
     * is the case that makes the `staff domain plus already added` admission policy
     * differ from `staff domain only`. Read [hasSignedIn] rather than comparing to
     * null — the timestamp itself is only interesting to the People tab.
     *
     * Defaulted, and last, so the fixtures and tests that build a record positionally
     * did not all have to change. Note the default is the *pending* answer, which is
     * the conservative direction: a caller that forgets to carry it through reports an
     * account as not-yet-arrived rather than claiming an arrival that never happened.
     */
    val signedInAt: Long? = null,
) {
    /** Has anybody ever signed into this account? See [signedInAt]. */
    val hasSignedIn: Boolean get() = signedInAt != null

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
        // The tier, as a fact the caller may read about themselves (LNL-193). Not a
        // grant and not an affordance either: nothing on the client is gated on it.
        // The settings pane states it, and words the greyed agent-access switch with
        // it. See SignedInUser.isStaff.
        isStaff = kind == UserKind.STAFF,
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
     * See `created_by_external` in Issues.sq for why the name is held rather than an
     * account minted for it, and [AccessControl.canAttributeWrites] for who may
     * *claim* one: naming somebody else as the author is an administrator's, and only
     * ever at creation.
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
 * ── It never produces [Author.External], and that is no longer a caveat ─────
 *
 * It briefly could. While impersonation was a costume there was a *caller* who was
 * not a user — a previewed address with no `users` row — and `created_by` cannot
 * point at a row that does not exist, so such a caller signed its writes as an
 * external name.
 *
 * There is no such caller any more. An owner probing an address that holds no
 * account signs in for real and the row is created, so every caller that can reach a
 * write is an account and this is total again. Nothing but an import produces
 * [Author.External], which is what its own documentation always wanted to say.
 */
fun UserRecord?.asAuthor(): Author =
    if (this == null) Author.Nobody else Author.Account(id)

/**
 * Did this caller write something [author] describes?
 *
 * The one place "it is mine" is decided, so the two issue gates and the comment gate
 * cannot answer it three ways. Expressed against [asAuthor] rather than by comparing
 * to [Author.Account] directly, which is what makes it correct for a previewed
 * address for free: whatever this caller's writes are attributed *to* is exactly what
 * counts as theirs, so the two can never drift apart.
 *
 * [Author.Nobody] belongs to nobody, and this returns false for it — an authorless
 * row is not everybody's to edit.
 */
fun UserRecord.wrote(author: Author): Boolean = author == asAuthor()

/**
 * Is this account **permitted** to hold agent access — the tier's half of the question?
 *
 * Per tier, and per tier only (LNL-192). `users.mcp_allowed` — an administrator's
 * per-account grant — went with the rest of the old privilege columns in LNL-191, and
 * what replaces it is one switch per rung of the instance ladder. There is no
 * per-person override anywhere in this design, deliberately: a rule an administrator
 * can read off one screen is a thing they can be sure of, and a column of exceptions
 * beside it is not.
 *
 * An instance administrator and the owner are permitted without a switch, being senior
 * to both tiers — see [InstanceSettings.permitsAgents].
 */
suspend fun se.soderbjorn.lunicle.store.InstanceSettingsStore.permitsAgentsFor(user: UserRecord?): Boolean {
    if (user == null) return false
    val settings = current()
    // The owner is a setting rather than a column, so it is folded in here rather than
    // taken off the record — see storedInstanceRole, which cannot see it. This was the
    // inline spelling of instanceRoleWith and is now the function (LNL-201); `settings`
    // is the read it needs, already done.
    return settings.permitsAgents(user.instanceRoleWith(settings.ownerUserId))
}

/**
 * May an agent act as this user right now?
 *
 * **The only thing an MCP gate should read.** There are five of them — `/mcp` per
 * request, `/oauth/authorize`, the consent POST, the token exchange and refresh
 * rotation — and five hand-written "permitted && enabled" pairs would be five chances
 * for one to be written as a single term. The failure mode of that typo is not a broken
 * build, it is a gate that silently lets an unpermitted user through on one of five
 * paths.
 *
 * Both terms, and the order is meaningless: the account's tier has to be permitted
 * ([permitsAgentsFor]), and the account has to have switched agent access on for itself
 * ([UserRecord.isMcpEnabled]). Neither implies the other, and neither substitutes for
 * the other.
 *
 * Admin-ness reaches this through the permission and no further. It clears the
 * *permission* a switch would otherwise have to grant; it does not clear the account's
 * own server-checked switch. That switch has to stay real for an admin too — it is what
 * cuts their agents off mid-conversation when they turn it off — so an admin who has
 * never enabled their own agent access still, correctly, cannot use MCP until they do.
 *
 * A suspending function over a store rather than a property on the record, because the
 * permission moved from a column on the row to a setting on the instance. That is one
 * small read on a path that already makes several, and it is what makes widening the
 * permission a switch rather than a pass over every account.
 */
suspend fun se.soderbjorn.lunicle.store.InstanceSettingsStore.canUseMcp(user: UserRecord?): Boolean =
    user != null && user.isMcpEnabled && permitsAgentsFor(user)

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
    signedInAt: Long? = null,
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
        signedInAt = signedInAt,
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
     * @param isProbe an owner-impersonation sign-in, which writes no proof — see the
     *   interface, and `refreshKindOnProbeSignIn` in Users.sq for what
     *   `signed_in_at` decides.
     * @return the stored user.
     * @throws IllegalStateException if the row we just wrote cannot be read
     *   back as a valid provider — impossible unless the enum changed under us,
     *   and not something to paper over.
     */
    override suspend fun upsert(
        identity: ProviderIdentity,
        kind: UserKind,
        isProbe: Boolean,
    ): UserRecord = withContext(DatabaseDispatcher) {
        // Normalized once, here, so both branches below and the write they lead to
        // are all looking at the same spelling. A lookup that normalized
        // differently from its write would miss the row and create a second
        // account, which is the failure this whole ticket exists to prevent.
        val email = normalizeEmail(identity.email)
        // Read once, before the branch, so the row's arrival stamp and the record
        // handed back describe the same instant on both paths — and so a pinned clock
        // in a test sees one value rather than two.
        val addedAt = now()
        // Null under a probe: nobody arrived, so nothing may say they did (LUS-6).
        val signedInAt = addedAt.takeUnless { isProbe }

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
                    // Two statements, and which one runs is the whole of LUS-6. The
                    // probe variant writes the derived `kind` and nothing else, so
                    // the row keeps saying what it honestly knows: whatever proof it
                    // last had, whatever name it last showed, and whether anybody has
                    // ever actually arrived at it.
                    if (isProbe) {
                        database.usersQueries.refreshKindOnProbeSignIn(kind.key, existing.id)
                    } else {
                        database.usersQueries.refreshOnSignIn(identity.providerName, kind.key, signedInAt, existing.id)
                    }
                    // Built from the row we already have plus the values the update
                    // just wrote, rather than re-reading. A second SELECT would be a
                    // round-trip to learn what this statement was told — and
                    // `provider`/`provider_id` deliberately keep the values the row
                    // was created with, so they are the existing ones and not the
                    // identity's.
                    //
                    // Under a probe the three columns the probe variant leaves alone
                    // are echoed back from `existing` for the same reason: the record
                    // handed to the caller has to describe the row that is now there.
                    return@transactionWithResult userRecordOf(
                        existing.id, existing.provider, existing.provider_id,
                        if (isProbe) existing.provider_name else identity.providerName,
                        existing.display_name, existing.email,
                        if (isProbe) existing.email_verified else 1L,
                        kind.key, existing.instance_role, existing.mcp_enabled,
                        if (isProbe) existing.signed_in_at else signedInAt,
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
                addedAt = addedAt,
                // The two are the same instant for every proved sign-in and differ
                // only under a probe, where this is null — see the statement.
                signedInAt = signedInAt,
            ).executeAsOne()

            userRecordOf(
                row.id, row.provider, row.provider_id, row.provider_name,
                row.display_name, row.email, row.email_verified,
                row.kind, row.instance_role, row.mcp_enabled, row.signed_in_at,
            ) ?: error("Just wrote user ${row.id} with provider ${row.provider}, which cannot be parsed back.")
        }
    }

    /**
     * The row [upsert] would find for this identity, or null when it would create
     * one. The same two steps in the same order, as a read — see the interface.
     */
    override suspend fun findExisting(identity: ProviderIdentity): UserRecord? = withContext(DatabaseDispatcher) {
        val email = normalizeEmail(identity.email)
        // Mapped in each branch rather than after the elvis: the two queries have
        // distinct generated row types, so a shared `row` would be typed as their
        // nearest common supertype and lose every column.
        val byEmail = email?.let { address ->
            database.usersQueries.findByEmail(address).executeAsOneOrNull()?.let {
                userRecordOf(
                    it.id, it.provider, it.provider_id, it.provider_name,
                    it.display_name, it.email, it.email_verified, it.kind, it.instance_role, it.mcp_enabled,
                    it.signed_in_at,
                )
            }
        }
        byEmail ?: database.usersQueries
            .findByProviderPair(identity.provider.name, identity.providerId)
            .executeAsOneOrNull()?.let {
                userRecordOf(
                    it.id, it.provider, it.provider_id, it.provider_name,
                    it.display_name, it.email, it.email_verified, it.kind, it.instance_role, it.mcp_enabled,
                    it.signed_in_at,
                )
            }
    }

    /**
     * Add an account for [email] without anybody signing in, or return the one that
     * already holds the address (LNL-194).
     *
     * The persistence half of "Add a person…". Idempotent, because the honest answer
     * to "add somebody who is already here" is the row they already have — the
     * caller's next move either way is to write a rung against the id this returns.
     *
     * @param kind derived by the caller through [UserKind.forEmail], from the same
     *   function sign-in uses, so a row added ahead of time and the same row after its
     *   owner arrives agree about whether they are staff. Passed rather than derived
     *   here for [upsert]'s reason: the deployment's domain is configuration, and a
     *   store does not read configuration.
     * @return the row, never null — the insert is `OR IGNORE` and the read that
     *   follows it cannot miss, both being inside one transaction on a single-threaded
     *   dispatcher.
     * @throws IllegalStateException if the row cannot be read back, matching [upsert].
     */
    override suspend fun addByEmail(email: String, kind: UserKind): UserRecord =
        withContext(DatabaseDispatcher) {
            // Normalized by the same function every lookup uses, so the row this
            // writes is the row a sign-in will find. A different spelling here would
            // hand somebody a rung they never pick up.
            val address = normalizeEmail(email)
                ?: error("addByEmail was given an address that normalizes to nothing: \"$email\"")
            database.transactionWithResult {
                database.usersQueries.addPending(
                    email = address,
                    // The local part, matching what a code sign-in seeds a row with —
                    // so the Access list can name the person before they arrive
                    // without the row claiming a display name they never chose.
                    providerName = address.substringBefore('@'),
                    kind = kind.key,
                    addedAt = now(),
                )
                database.usersQueries.findByEmail(address).executeAsOneOrNull()?.let {
                    userRecordOf(
                        it.id, it.provider, it.provider_id, it.provider_name,
                        it.display_name, it.email, it.email_verified, it.kind, it.instance_role,
                        it.mcp_enabled, it.signed_in_at,
                    )
                } ?: error("Added \"$address\" and could not read the row back.")
            }
        }

    /** The user with [id], or null. */
    override suspend fun findById(id: Long): UserRecord? = withContext(DatabaseDispatcher) {
        database.usersQueries.findById(id).executeAsOneOrNull()?.let {
            userRecordOf(
                it.id, it.provider, it.provider_id, it.provider_name,
                it.display_name, it.email, it.email_verified, it.kind, it.instance_role, it.mcp_enabled,
                it.signed_in_at,
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
                it.signed_in_at,
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
