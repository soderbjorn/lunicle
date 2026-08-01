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
    override suspend fun upsert(identity: ProviderIdentity, kind: UserKind, isProbe: Boolean): UserRecord {
        // Normalised once, here, so the lookup and the write it may lead to are
        // looking at the same spelling — see the class preamble.
        val email = normalizeEmail(identity.email)
        val createdAt = now()
        // Null under an owner-impersonation probe: nobody arrived, so nothing may
        // record that they did (LUS-6). The same value the SQLite store binds — see
        // Users.sq's refreshKindOnProbeSignIn for what this field decides.
        val signedInAt = createdAt.takeUnless { isProbe }
        return firestore.runTransaction { txn ->
            // ── Find by the account key: verified e-mail first ────────────────
            if (email != null) {
                val byEmail = txn.get(collection().whereEqualTo(EMAIL, email).limit(1)).get()
                    .documents.firstOrNull()
                if (byEmail != null) {
                    // Refresh exactly what refreshOnSignIn does: the provider's name,
                    // and the verified flag (arriving here proved the address again).
                    // provider/providerId keep the values the row was created with.
                    //
                    // Under a probe, exactly what refreshKindOnProbeSignIn does
                    // instead: the derived kind and nothing else, because none of the
                    // other three actually happened.
                    txn.update(
                        byEmail.reference,
                        if (isProbe) {
                            mapOf(KIND to kind.key)
                        } else {
                            mapOf(
                                PROVIDER_NAME to identity.providerName,
                                EMAIL_VERIFIED to true,
                                // Follows, because it is derived rather than chosen — see
                                // Users.sq's refreshOnSignIn.
                                KIND to kind.key,
                                // The arrival stamp (LNL-194). Written on every sign-in, so
                                // a row an administrator added ahead of time stops being
                                // pending the moment its owner turns up here.
                                SIGNED_IN_AT to createdAt,
                            )
                        },
                    )
                    val stored = byEmail.toUser()!!
                    return@runTransaction if (isProbe) {
                        stored.copy(kind = kind)
                    } else {
                        stored.copy(
                            providerName = identity.providerName,
                            isEmailVerified = true,
                            kind = kind,
                            signedInAt = createdAt,
                        )
                    }
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
                // COALESCE again, for the arrival stamp: a probe passing null leaves
                // whatever was there rather than claiming an arrival, and never
                // clears one that genuinely happened. The same shape Users.sq's
                // ON CONFLICT branch takes (LUS-6).
                val newSignedInAt = signedInAt ?: byPair.getLong(SIGNED_IN_AT)
                txn.update(
                    byPair.reference,
                    mapOf(
                        PROVIDER_NAME to identity.providerName,
                        EMAIL to newEmail,
                        EMAIL_VERIFIED to newVerified,
                        KIND to kind.key,
                        SIGNED_IN_AT to newSignedInAt,
                    ),
                )
                return@runTransaction byPair.toUser()!!
                    .copy(
                        providerName = identity.providerName,
                        email = newEmail,
                        isEmailVerified = newVerified,
                        kind = kind,
                        signedInAt = newSignedInAt,
                    )
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
                kind = kind,
                isInstanceAdmin = instanceIsEmpty,
                isMcpEnabled = false,
                // Reaching this branch is a sign-in, so the arrival stamp and the added
                // stamp are the same instant. Two things write a row without one:
                // addByEmail, and a probe (LUS-6) — nobody arrived at either.
                signedInAt = signedInAt,
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
                    KIND to kind.key,
                    // The document form of the same 'admin'-or-null the column holds:
                    // ownership is a setting, not a value here. See Users.sq.
                    INSTANCE_ROLE to InstanceRole.ADMIN.key.takeIf { instanceIsEmpty },
                    MCP_ENABLED to false,
                    ADDED_AT to createdAt,
                    SIGNED_IN_AT to signedInAt,
                ),
            )
            record
        }.await()
    }

    /**
     * Add an account for [email] without a sign-in, or return the one that already
     * holds the address (LNL-194).
     *
     * One transaction, for [upsert]'s reason: the address lookup is the stand-in for
     * SQLite's partial unique index on `email`, so "find then maybe create" has to be
     * serialised or two administrators adding the same person would make two rows.
     *
     * `SIGNED_IN_AT` is written as an explicit null rather than left absent, and the
     * distinction is load-bearing — see [DocumentSnapshot.toUser], which reads an
     * *absent* field as "this document predates LNL-194, and every account that did
     * has signed in".
     */
    override suspend fun addByEmail(email: String, kind: UserKind): UserRecord {
        val address = normalizeEmail(email)
            ?: error("addByEmail was given an address that normalizes to nothing: \"$email\"")
        val addedAt = now()
        return firestore.runTransaction { txn ->
            val existing = txn.get(collection().whereEqualTo(EMAIL, address).limit(1)).get()
                .documents.firstOrNull()
            if (existing != null) return@runTransaction existing.toUser()!!
            val id = counters.next(txn, COUNTER).getValue(COUNTER)
            // Deliberately never the instance administrator, however empty the
            // collection is: being added by somebody is the opposite of being the
            // first person through the door. Mirrors addPending's missing subquery.
            val record = UserRecord(
                id = id,
                provider = AuthProvider.EMAIL,
                providerId = address,
                providerName = address.substringBefore('@'),
                displayNameOverride = null,
                email = address,
                isEmailVerified = false,
                kind = kind,
                isInstanceAdmin = false,
                isMcpEnabled = false,
                signedInAt = null,
            )
            txn.set(
                doc(id),
                mapOf(
                    ID to id,
                    PROVIDER to AuthProvider.EMAIL.name,
                    PROVIDER_ID to address,
                    PROVIDER_NAME to record.providerName,
                    DISPLAY_NAME to null,
                    EMAIL to address,
                    EMAIL_VERIFIED to false,
                    KIND to kind.key,
                    INSTANCE_ROLE to null,
                    MCP_ENABLED to false,
                    ADDED_AT to addedAt,
                    SIGNED_IN_AT to null,
                ),
            )
            record
        }.await()
    }

    /**
     * The document [upsert] would find for this identity, or null when it would
     * create one — the same two lookups in the same order, outside a transaction
     * because nothing is written. See the interface for why admission needs it.
     */
    override suspend fun findExisting(identity: ProviderIdentity): UserRecord? {
        val email = normalizeEmail(identity.email)
        if (email != null) {
            collection().whereEqualTo(EMAIL, email).limit(1).get().await()
                .documents.firstOrNull()?.let { return it.toUser() }
        }
        return collection()
            .whereEqualTo(PROVIDER, identity.provider.name)
            .whereEqualTo(PROVIDER_ID, identity.providerId)
            .limit(1)
            .get().await()
            .documents.firstOrNull()?.toUser()
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

    override suspend fun setKind(id: Long, kind: UserKind) {
        doc(id).update(KIND, kind.key).await()
    }

    override suspend fun setInstanceAdmin(id: Long, isAdmin: Boolean) {
        doc(id).update(INSTANCE_ROLE, InstanceRole.ADMIN.key.takeIf { isAdmin }).await()
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
        const val KIND = "kind"
        const val INSTANCE_ROLE = "instanceRole"
        const val MCP_ENABLED = "mcpEnabled"

        /**
         * When the row appeared — renamed from `createdAt` in LNL-191, because a row
         * need no longer be the residue of a sign-in. The Firestore backfill rewrites
         * the field name; see FirestorePermissionModelMigration.
         */
        const val ADDED_AT = "addedAt"

        /**
         * When somebody last signed in, or an explicit null because nobody ever has
         * (LNL-194).
         *
         * **Absent is not null here.** A document written before this field existed
         * got there by somebody signing in — that was the only way to make one — so
         * [DocumentSnapshot.toUser] falls back to [ADDED_AT] when the field is
         * missing, which is exactly what 34.sqm's `UPDATE users SET signed_in_at =
         * added_at` does on the relational side. That is why there is no backfill for
         * this field: the read does the migration's work, and `addByEmail` writes the
         * null explicitly so the two cases stay distinguishable.
         */
        const val SIGNED_IN_AT = "signedInAt"
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
        // Absent on a document the backfill has not reached — reads as `member`, the
        // lesser answer, which is the safe direction while a backfill is in flight.
        kind = UserKind.byKey(getString("kind")),
        // Any value but 'admin' — including a rung a newer build invented — reads as
        // "not an administrator". Ownership is never here; it is a setting.
        isInstanceAdmin = getString("instanceRole") == InstanceRole.ADMIN.key,
        isMcpEnabled = getBoolean("mcpEnabled") ?: false,
        // Absent means "written before LNL-194", and every account that predates it
        // arrived by signing in, so its added stamp is the honest arrival date. An
        // explicit null means added-and-not-yet-arrived. See SIGNED_IN_AT.
        signedInAt = if (contains("signedInAt")) getLong("signedInAt") else getLong("addedAt"),
    )
}
