/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.UserStore] — where
 * LNL-111's subtlest parity case lives: identity keying without a unique index.
 *
 * **Why this store is delicate.** In SQLite two guarantees came free from the
 * schema — `UNIQUE(provider, provider_id)` and `UNIQUE(email)` — and one rule came
 * free from an `INSERT … ON CONFLICT` that evaluated `EXISTS(SELECT 1 FROM users)`
 * atomically with the insert: the first account created is the instance admin. A
 * document store has neither a unique index nor an atomic "is the table empty".
 * So [upsert] does the whole find-or-create *inside one transaction*: it reads by
 * the account key, and only if nothing is found reads whether any user exists at
 * all, and only then allocates an id and writes. The transaction is what makes
 * "reunite a returning user with their row" and "the first user is admin" hold
 * under a racing second sign-in, standing in for the two indexes and the subquery.
 *
 * **The keying, reproduced exactly.** A returning user is found by **verified
 * e-mail first** — so signing in through a different provider but proving the same
 * address reunites them with their existing row rather than minting a second — and
 * only falls back to the `(provider, provider_id)` pair for an account whose
 * address was never confirmed. This mirrors `Users.sq`'s `findByEmail` branch and
 * its `ON CONFLICT (provider, provider_id)` branch, including the `COALESCE` that
 * never erases a known address on a later sign-in that carries none.
 *
 * ── Document model ──────────────────────────────────────────────────────────
 *
 * One document per user in `users/{id}`, `{id}` the global `Long` id allocated
 * from `_counters/users` (see [FirestoreCounters]). Every field is a plain scalar;
 * there is no join. The address is stored already-normalised (trimmed, lowered)
 * by [normalizeEmail] — the same function the SQLite path uses — because the
 * e-mail lookup is an equality query and a lookup that normalised differently
 * from the write would miss the row and create the second account this store
 * exists to prevent.
 *
 * ── Composite index ─────────────────────────────────────────────────────────
 *
 * The two lookup queries filter a single field each (`email`, and `provider` +
 * `providerId`). The provider-pair query is two equality filters, which needs a
 * composite index in production — declared in `server/firestore.indexes.json`
 * (the one composite index the whole backend requires). The emulator serves it
 * unindexed, so the contract and smoke tests pass here regardless.
 *
 * @see FirestoreProvider
 * @see FirestoreCounters
 * @see se.soderbjorn.lunicle.store.UserStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.store.UserStore

class FirestoreUserStore(
    private val firestore: Firestore,
    private val now: () -> Long = System::currentTimeMillis,
) : UserStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    /**
     * Find the user behind a provider identity, creating them on first sign-in and
     * refreshing their provider name (and verified flag) on every later one.
     *
     * The whole find-or-create is one transaction, for the reason the class
     * preamble gives: it is the stand-in for the two unique indexes and the
     * first-user-is-admin subquery SQLite got from the schema. The order matches
     * `Users.sq` exactly — verified e-mail, then the provider pair, then a fresh
     * account whose admin-ness is decided by whether any user already exists.
     *
     * All reads precede all writes, as a Firestore transaction requires: the two
     * lookup queries and the "is anyone here" query run first, the counter is read
     * and then bumped, and the document is written last.
     */
    override suspend fun upsert(identity: ProviderIdentity): UserRecord {
        // Normalised once, here, so the lookup and the write it may lead to are
        // looking at the same spelling — see the class preamble.
        val email = normalizeEmail(identity.email)
        val createdAt = now()
        return firestore.runTransaction { txn ->
            // ── Find by the account key: verified e-mail first ────────────────
            if (email != null) {
                val byEmail = txn.get(collection().whereEqualTo(EMAIL, email).limit(1)).get()
                    .documents.firstOrNull()
                if (byEmail != null) {
                    // Refresh exactly what refreshOnSignIn does: the provider's name,
                    // and the verified flag (arriving here proved the address again).
                    // provider/providerId keep the values the row was created with.
                    txn.update(byEmail.reference, mapOf(PROVIDER_NAME to identity.providerName, EMAIL_VERIFIED to true))
                    return@runTransaction byEmail.toUser()!!
                        .copy(providerName = identity.providerName, isEmailVerified = true)
                }
            }

            // ── Or fall back to the provider pair ─────────────────────────────
            val byPair = txn.get(
                collection()
                    .whereEqualTo(PROVIDER, identity.provider.name)
                    .whereEqualTo(PROVIDER_ID, identity.providerId)
                    .limit(1),
            ).get().documents.firstOrNull()
            if (byPair != null) {
                // COALESCE, not assignment: a sign-in that carries no address must
                // not erase one an earlier sign-in learned, and one that carries an
                // address has, by construction, proved it. See Users.sq's upsert.
                val newEmail = email ?: byPair.getString(EMAIL)
                val newVerified = if (email != null) true else (byPair.getBoolean(EMAIL_VERIFIED) ?: false)
                txn.update(
                    byPair.reference,
                    mapOf(PROVIDER_NAME to identity.providerName, EMAIL to newEmail, EMAIL_VERIFIED to newVerified),
                )
                return@runTransaction byPair.toUser()!!
                    .copy(providerName = identity.providerName, email = newEmail, isEmailVerified = newVerified)
            }

            // ── Or create — the first ever becomes the instance admin ─────────
            //
            // Read whether any user exists inside the same transaction the insert
            // rides in, the document equivalent of the ON CONFLICT subquery: a
            // second sign-in racing on an empty instance is serialised by the
            // transaction, so exactly one of them sees an empty collection and
            // becomes admin.
            val instanceIsEmpty = txn.get(collection().limit(1)).get().documents.isEmpty()
            val id = counters.next(txn, COUNTER).getValue(COUNTER)
            val record = UserRecord(
                id = id,
                provider = identity.provider,
                providerId = identity.providerId,
                providerName = identity.providerName,
                displayNameOverride = null,
                email = email,
                // Derived from the address, never passed beside it — a non-null
                // normalised address reaching here is one a provider confirmed or a
                // code proved. See ProviderIdentity.email and Users.sq.
                isEmailVerified = email != null,
                isSysAdmin = instanceIsEmpty,
                isMcpEnabled = false,
                isMcpAllowed = false,
            )
            txn.set(
                doc(id),
                mapOf(
                    ID to id,
                    PROVIDER to identity.provider.name,
                    PROVIDER_ID to identity.providerId,
                    PROVIDER_NAME to identity.providerName,
                    DISPLAY_NAME to null,
                    EMAIL to email,
                    EMAIL_VERIFIED to (email != null),
                    IS_SYS_ADMIN to instanceIsEmpty,
                    MCP_ENABLED to false,
                    MCP_ALLOWED to false,
                    CREATED_AT to createdAt,
                ),
            )
            record
        }.await()
    }

    override suspend fun findById(id: Long): UserRecord? =
        doc(id).get().await().takeIf { it.exists() }?.toUser()

    override suspend fun selectAll(): List<UserRecord> =
        // mapNotNull, matching the SQLite store's tolerance: a row whose provider
        // string cannot be parsed is skipped rather than taking the list down.
        collection().get().await().documents.mapNotNull { it.toUser() }

    /** Set or clear the display-name override; blank normalises to cleared (null). */
    override suspend fun setDisplayName(id: Long, name: String?) {
        doc(id).update(DISPLAY_NAME, name?.trim()?.takeIf { it.isNotBlank() }).await()
    }

    /**
     * Set or clear the e-mail and its verified flag together.
     *
     * The pair is written in one update for `setEmail`'s SQLite reason: a flag left
     * describing the previous address is precisely the lie the column exists to
     * prevent. Verified is forced false when the address is cleared or blank, and
     * the address is put through the same [normalizeEmail] every lookup uses.
     */
    override suspend fun setEmail(id: Long, email: String?, isVerified: Boolean) {
        val normalized = normalizeEmail(email)
        doc(id).update(
            mapOf(
                EMAIL to normalized,
                EMAIL_VERIFIED to (normalized != null && isVerified),
            ),
        ).await()
    }

    override suspend fun setMcpEnabled(id: Long, isEnabled: Boolean) {
        doc(id).update(MCP_ENABLED, isEnabled).await()
    }

    override suspend fun setMcpAllowed(id: Long, isAllowed: Boolean) {
        doc(id).update(MCP_ALLOWED, isAllowed).await()
    }

    private companion object {
        const val COLLECTION = "users"
        const val COUNTER = "users"

        const val ID = "id"
        const val PROVIDER = "provider"
        const val PROVIDER_ID = "providerId"
        const val PROVIDER_NAME = "providerName"
        const val DISPLAY_NAME = "displayName"
        const val EMAIL = "email"
        const val EMAIL_VERIFIED = "emailVerified"
        const val IS_SYS_ADMIN = "isSysAdmin"
        const val MCP_ENABLED = "mcpEnabled"
        const val MCP_ALLOWED = "mcpAllowed"
        const val CREATED_AT = "createdAt"
    }
}

/**
 * A user document read back, or null when its provider string names a provider
 * this build has never heard of — a row a newer build could have written, and a
 * reason to ignore a row rather than fail a request. Mirrors `parseProvider`.
 */
private fun DocumentSnapshot.toUser(): UserRecord? {
    val provider = AuthProvider.entries.firstOrNull { it.name == getString("provider") } ?: return null
    return UserRecord(
        id = getLong("id")!!,
        provider = provider,
        providerId = getString("providerId").orEmpty(),
        providerName = getString("providerName").orEmpty(),
        displayNameOverride = getString("displayName"),
        email = getString("email"),
        isEmailVerified = getBoolean("emailVerified") ?: false,
        isSysAdmin = getBoolean("isSysAdmin") ?: false,
        isMcpEnabled = getBoolean("mcpEnabled") ?: false,
        isMcpAllowed = getBoolean("mcpAllowed") ?: false,
    )
}
