/**
 * The Firestore implementations of the four authorization-server stores —
 * [se.soderbjorn.lunicle.store.OAuthClientStore],
 * [se.soderbjorn.lunicle.store.OAuthLoginStateStore],
 * [se.soderbjorn.lunicle.store.OAuthCodeStore] and
 * [se.soderbjorn.lunicle.store.OAuthTokenStore] — kept together for the same reason
 * their SQLite reference ([se.soderbjorn.lunicle.OAuthStores]) is: they are one
 * subsystem, and a token points at a client, a code carries a login state's PKCE
 * challenge forward, and a client is what `/authorize` touches.
 *
 * ── What is reused, and what is re-modelled ─────────────────────────────────
 *
 * The crypto is backend-agnostic and shared verbatim: [OAuthCrypto] mints every
 * secret, hashes it, and verifies PKCE, here as in SQLite. **A secret is returned
 * once and stored only as a hash** — nothing below reads a raw code or token back,
 * because nothing holds one; the documents hold SHA-256 hashes.
 *
 * The *storage* is re-modelled onto documents, and the model is chosen so that
 * every uniqueness and expiry rule the SQLite schema got from a UNIQUE index or a
 * WHERE clause is got here from the document key or a range query:
 *
 *  - **Clients** — `oauthClients/{clientId}`; the redirect URIs are an array on the
 *    document (the SQLite child table collapses onto it), so the exact-match check
 *    is a membership test rather than a join.
 *  - **Login state** — `oauthLoginState/{id}`; expiry is enforced by [find] itself.
 *  - **Codes** — `oauthCodes/{sha256(code)}`; the hash is the document key, which
 *    makes single-use free: [consume] deletes the document inside a transaction, so
 *    the second presentation of a code finds nothing.
 *  - **Tokens** — `oauthTokens/{sha256(token)}`; the refresh-family algorithm runs
 *    in a Firestore transaction, so the read, the reuse check, the mark-consumed and
 *    the re-issue are atomic — the same guarantee [DatabaseDispatcher]'s single
 *    thread gives the SQLite store.
 *
 * ── Composite indexes ───────────────────────────────────────────────────────
 *
 * None are relied on. Every query here is a single-field equality or a single-field
 * range (`expiresAt <=`), each of which Firestore serves from an automatic
 * single-field index; the few places that would otherwise want a two-field query
 * (a user's grants, a client's tokens) filter the second field in memory, because
 * those are startup or Connections-list reads over a handful of rows rather than a
 * hot path. The emulator serves everything regardless.
 *
 * @see FirestoreProvider
 * @see OAuthCrypto
 * @see se.soderbjorn.lunicle.store.OAuthClientStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.QueryDocumentSnapshot
import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.OAuthTokenStore.RefreshResult

private val firestoreOAuthLogger = LoggerFactory.getLogger("FirestoreOAuthStores")

// ── Shared constants, private to this file ───────────────────────────────────
// The SQLite reference keeps these file-private in OAuthStores.kt, so they cannot
// be imported; they are re-stated here rather than shared through a new helper file
// a parallel Firestore ticket might also add. The values are deliberately identical
// — the flow is the same, only the storage differs.

private const val CLIENT_ID_PREFIX = "lun_client_"
private const val LOGIN_STATE_PREFIX = "ls_"
private const val CODE_PREFIX = "lun_code_"
private const val ACCESS_TOKEN_PREFIX = "lun_at_"
private const val REFRESH_TOKEN_PREFIX = "lun_rt_"
private const val FAMILY_PREFIX = "fam_"

private const val TYPE_ACCESS = "access"
private const val TYPE_REFRESH = "refresh"

private const val ACCESS_TOKEN_LIFETIME_MILLIS: Long = 60L * 60 * 1000
private const val REFRESH_TOKEN_LIFETIME_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
private const val AUTH_CODE_LIFETIME_MILLIS: Long = 2L * 60 * 1000
private const val LOGIN_STATE_LIFETIME_MILLIS: Long = 10L * 60 * 1000
private const val STALE_CLIENT_AGE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

// ── Clients ──────────────────────────────────────────────────────────────────

/**
 * The Firestore [se.soderbjorn.lunicle.store.OAuthClientStore].
 *
 * One document per client in `oauthClients/{clientId}`, keyed by the opaque
 * `lun_client_…` id itself — a natural, unique key, so [find] is a direct get. The
 * redirect URIs are an array on the document rather than a child collection: nothing
 * queries *for* a redirect URI, it is only ever matched against a known client, so
 * [isRegisteredRedirectUri] is exact membership in that array.
 */
class FirestoreOAuthClientStore(
    private val firestore: Firestore,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.OAuthClientStore {
    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(clientId: String) = collection().document(clientId)

    override suspend fun register(
        clientName: String,
        redirectUris: List<String>,
        grantTypes: List<String>,
    ): OAuthClientRecord {
        val clientId = OAuthCrypto.randomId(CLIENT_ID_PREFIX)
        val timestamp = now()
        // distinct(), as the reference: a client that listed a callback twice is
        // sloppy, not hostile, and refusing the whole registration over it would be
        // a confusing failure for something we can simply absorb.
        val uris = redirectUris.distinct()
        doc(clientId).set(
            mapOf(
                CLIENT_ID to clientId,
                CLIENT_NAME to clientName,
                REDIRECT_URIS to uris,
                GRANT_TYPES to grantTypes,
                CREATED_AT to timestamp,
                LAST_USED_AT to timestamp,
            ),
        ).await()
        firestoreOAuthLogger.info(
            "MCP: registered client $clientId (${clientName.take(60)}) with ${uris.size} redirect URI(s)",
        )
        return OAuthClientRecord(clientId, clientName, redirectUris, grantTypes, timestamp, timestamp)
    }

    override suspend fun find(clientId: String): OAuthClientRecord? {
        // Rejected on the prefix before a read runs — one round-trip saved on every
        // piece of junk that arrives at an unauthenticated endpoint. Not a security
        // check: a client_id is not a secret.
        if (!clientId.startsWith(CLIENT_ID_PREFIX)) return null
        val snapshot = doc(clientId).get().await()
        return snapshot.takeIf { it.exists() }?.toClientRecord()
    }

    override suspend fun isRegisteredRedirectUri(clientId: String, redirectUri: String): Boolean {
        val snapshot = doc(clientId).get().await()
        if (!snapshot.exists()) return false
        // Exact string equality, the one check between this server and being an open
        // redirector: no normalisation, no port games. See the reference.
        return redirectUri in snapshot.stringList(REDIRECT_URIS)
    }

    override suspend fun touch(clientId: String) {
        doc(clientId).update(LAST_USED_AT, now()).await()
    }

    /**
     * Delete registrations that never became anything.
     *
     * The SQLite `deleteStale` requires BOTH timestamps old AND no live token — the
     * NOT EXISTS is the real guard, since a client holding a token is in use whatever
     * its timestamps say. Reproduced here: candidates are filtered in memory (the
     * client set is small, an admin-scale count) and each is spared if any token
     * references it.
     */
    override suspend fun sweepStale(): Long {
        val cutoff = now() - STALE_CLIENT_AGE_MILLIS
        val candidates = collection().get().await().documents.filter {
            (it.getLong(CREATED_AT) ?: 0L) < cutoff && (it.getLong(LAST_USED_AT) ?: 0L) < cutoff
        }
        var removed = 0L
        for (candidate in candidates) {
            val clientId = candidate.getString(CLIENT_ID) ?: candidate.id
            val hasToken = !firestore.collection(TOKENS_COLLECTION)
                .whereEqualTo(TOKEN_CLIENT_ID, clientId)
                .limit(1)
                .get().await().isEmpty
            if (hasToken) continue
            candidate.reference.delete().await()
            removed++
        }
        return removed
    }

    override suspend fun size(): Long = collection().get().await().size().toLong()

    private fun DocumentSnapshot.toClientRecord() = OAuthClientRecord(
        clientId = getString(CLIENT_ID) ?: id,
        clientName = getString(CLIENT_NAME).orEmpty(),
        redirectUris = stringList(REDIRECT_URIS),
        grantTypes = stringList(GRANT_TYPES),
        createdAt = getLong(CREATED_AT) ?: 0L,
        lastUsedAt = getLong(LAST_USED_AT) ?: 0L,
    )

    private companion object {
        const val COLLECTION = "oauthClients"
        const val TOKENS_COLLECTION = "oauthTokens"
        const val TOKEN_CLIENT_ID = "clientId"
        const val CLIENT_ID = "clientId"
        const val CLIENT_NAME = "clientName"
        const val REDIRECT_URIS = "redirectUris"
        const val GRANT_TYPES = "grantTypes"
        const val CREATED_AT = "createdAt"
        const val LAST_USED_AT = "lastUsedAt"
    }
}

// ── Login state ──────────────────────────────────────────────────────────────

/**
 * The Firestore [se.soderbjorn.lunicle.store.OAuthLoginStateStore].
 *
 * One document per pending authorization in `oauthLoginState/{id}`, keyed by the
 * opaque `ls_…` id. Expiry is enforced by [find] itself — a lookup past `expiresAt`
 * returns null though the document is still on disk — exactly as the reference's
 * WHERE clause does; [deleteExpired] is only the sweep behind that.
 */
class FirestoreOAuthLoginStateStore(
    private val firestore: Firestore,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.OAuthLoginStateStore {
    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(id: String) = collection().document(id)

    override suspend fun create(
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        resource: String,
        clientState: String,
        scope: String,
        userId: Long,
    ): String {
        val id = OAuthCrypto.randomId(LOGIN_STATE_PREFIX)
        doc(id).set(
            mapOf(
                ID to id,
                CLIENT_ID to clientId,
                REDIRECT_URI to redirectUri,
                CODE_CHALLENGE to codeChallenge,
                RESOURCE to resource,
                CLIENT_STATE to clientState,
                SCOPE to scope,
                USER_ID to userId,
                EXPIRES_AT to now() + LOGIN_STATE_LIFETIME_MILLIS,
            ),
        ).await()
        return id
    }

    override suspend fun find(id: String?): OAuthLoginStateRecord? {
        if (id == null) return null
        val snapshot = doc(id).get().await()
        if (!snapshot.exists()) return null
        // Expiry in the lookup: a row past its expiry is not found, even though the
        // sweep has not run yet.
        if ((snapshot.getLong(EXPIRES_AT) ?: 0L) <= now()) return null
        return OAuthLoginStateRecord(
            id = snapshot.getString(ID) ?: snapshot.id,
            clientId = snapshot.getString(CLIENT_ID).orEmpty(),
            redirectUri = snapshot.getString(REDIRECT_URI).orEmpty(),
            codeChallenge = snapshot.getString(CODE_CHALLENGE).orEmpty(),
            resource = snapshot.getString(RESOURCE).orEmpty(),
            clientState = snapshot.getString(CLIENT_STATE).orEmpty(),
            scope = snapshot.getString(SCOPE).orEmpty(),
            userId = snapshot.getLong(USER_ID) ?: 0L,
        )
    }

    /** Forget it. A delete of a missing document is a no-op in Firestore, so this is idempotent. */
    override suspend fun delete(id: String) {
        doc(id).delete().await()
    }

    override suspend fun deleteExpired(): Long = sweepExpired(collection(), EXPIRES_AT, now())

    private companion object {
        const val COLLECTION = "oauthLoginState"
        const val ID = "id"
        const val CLIENT_ID = "clientId"
        const val REDIRECT_URI = "redirectUri"
        const val CODE_CHALLENGE = "codeChallenge"
        const val RESOURCE = "resource"
        const val CLIENT_STATE = "clientState"
        const val SCOPE = "scope"
        const val USER_ID = "userId"
        const val EXPIRES_AT = "expiresAt"
    }
}

// ── Authorization codes ──────────────────────────────────────────────────────

/**
 * The Firestore [se.soderbjorn.lunicle.store.OAuthCodeStore].
 *
 * One document per code in `oauthCodes/{sha256(code)}` — the hash *is* the document
 * key, so a code is stored only as a hash (this file's rule) and uniqueness is free.
 * [consume] runs inside a Firestore transaction: it reads the document, checks
 * expiry, and deletes it, so single-use holds — two agents presenting the same code
 * cannot both be served, and the delete IS the consume, which is the parity the
 * contract pins.
 */
class FirestoreOAuthCodeStore(
    private val firestore: Firestore,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.OAuthCodeStore {
    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(hash: String) = collection().document(hash)

    override suspend fun create(
        userId: Long,
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        resource: String,
        scope: String,
    ): String {
        val code = OAuthCrypto.randomToken(CODE_PREFIX)
        doc(OAuthCrypto.sha256Hex(code)).set(
            mapOf(
                USER_ID to userId,
                CLIENT_ID to clientId,
                REDIRECT_URI to redirectUri,
                CODE_CHALLENGE to codeChallenge,
                RESOURCE to resource,
                SCOPE to scope,
                EXPIRES_AT to now() + AUTH_CODE_LIFETIME_MILLIS,
            ),
        ).await()
        return code
    }

    override suspend fun consume(code: String): OAuthCodeRecord? {
        if (!code.startsWith(CODE_PREFIX)) return null
        val ref = doc(OAuthCrypto.sha256Hex(code))
        return firestore.runTransaction { txn ->
            val snapshot = txn.get(ref).get()
            if (!snapshot.exists()) return@runTransaction null
            // Expiry and single-use in one read: an expired code, and a code
            // presented a second time (already deleted), are the same answer — null.
            if ((snapshot.getLong(EXPIRES_AT) ?: 0L) <= now()) return@runTransaction null
            txn.delete(ref)
            OAuthCodeRecord(
                userId = snapshot.getLong(USER_ID) ?: 0L,
                clientId = snapshot.getString(CLIENT_ID).orEmpty(),
                redirectUri = snapshot.getString(REDIRECT_URI).orEmpty(),
                codeChallenge = snapshot.getString(CODE_CHALLENGE).orEmpty(),
                resource = snapshot.getString(RESOURCE).orEmpty(),
                scope = snapshot.getString(SCOPE).orEmpty(),
            )
        }.await()
    }

    override suspend fun deleteExpired(): Long = sweepExpired(collection(), EXPIRES_AT, now())

    private companion object {
        const val COLLECTION = "oauthCodes"
        const val USER_ID = "userId"
        const val CLIENT_ID = "clientId"
        const val REDIRECT_URI = "redirectUri"
        const val CODE_CHALLENGE = "codeChallenge"
        const val RESOURCE = "resource"
        const val SCOPE = "scope"
        const val EXPIRES_AT = "expiresAt"
    }
}

// ── Tokens ───────────────────────────────────────────────────────────────────

/**
 * The Firestore [se.soderbjorn.lunicle.store.OAuthTokenStore] — the refresh-family
 * algorithm over documents.
 *
 * One document per token in `oauthTokens/{sha256(token)}`. Access and refresh
 * tokens share the collection and carry a `type`; a shared `familyId` ties an
 * access+refresh pair and its rotations into one grant.
 *
 * ── The MCP gate, and why it is injected ────────────────────────────────────
 *
 * The SQLite [rotateRefresh] reads the owner's user row *inside* its transaction to
 * ask whether they may use MCP. A document backend has no join to the users
 * collection — and depending on the Firestore identity store (a separate ticket)
 * would couple these subsystems — so the permission is supplied by [canUseMcp], a
 * predicate the module wires to the identity store in production and the contract
 * wires to its seeded users. It is consulted *before* anything is consumed (a
 * pre-read learns the owner), so a refusal spends nothing and flipping the toggle
 * back on restores the grant rather than tripping the theft path — the exact
 * ordering the reference argues for at length.
 *
 * ── Atomicity ───────────────────────────────────────────────────────────────
 *
 * The consume-and-reissue is a Firestore transaction: it re-reads the refresh token,
 * checks `consumed`, and either revokes the family (reuse detected) or marks the old
 * token consumed and mints a new pair — all atomically, so a concurrent replay
 * cannot slip between the check and the mark.
 */
class FirestoreOAuthTokenStore(
    private val firestore: Firestore,
    private val canUseMcp: suspend (Long) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.OAuthTokenStore {
    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(hash: String) = collection().document(hash)

    override suspend fun issueTokens(
        userId: Long,
        clientId: String,
        scope: String,
        resource: String,
    ): IssuedTokens {
        val batch = firestore.batch()
        val issued = mintPair(userId, clientId, scope, resource, OAuthCrypto.randomId(FAMILY_PREFIX)) { ref, data ->
            batch.set(ref, data)
        }
        batch.commit().await()
        return issued
    }

    /**
     * Build an access+refresh pair in [familyId] and hand each document to [write]
     * (a batch's or a transaction's setter). Returns the raw pair — the only moment
     * these values exist unhashed.
     */
    private fun mintPair(
        userId: Long,
        clientId: String,
        scope: String,
        resource: String,
        familyId: String,
        write: (com.google.cloud.firestore.DocumentReference, Map<String, Any?>) -> Unit,
    ): IssuedTokens {
        val accessToken = OAuthCrypto.randomToken(ACCESS_TOKEN_PREFIX)
        val refreshToken = OAuthCrypto.randomToken(REFRESH_TOKEN_PREFIX)
        val timestamp = now()
        write(
            doc(OAuthCrypto.sha256Hex(accessToken)),
            tokenData(accessToken, TYPE_ACCESS, userId, clientId, scope, resource, familyId, timestamp, ACCESS_TOKEN_LIFETIME_MILLIS),
        )
        write(
            doc(OAuthCrypto.sha256Hex(refreshToken)),
            tokenData(refreshToken, TYPE_REFRESH, userId, clientId, scope, resource, familyId, timestamp, REFRESH_TOKEN_LIFETIME_MILLIS),
        )
        return IssuedTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresInSeconds = ACCESS_TOKEN_LIFETIME_MILLIS / 1000,
            scope = scope,
        )
    }

    private fun tokenData(
        token: String,
        type: String,
        userId: Long,
        clientId: String,
        scope: String,
        resource: String,
        familyId: String,
        timestamp: Long,
        lifetimeMillis: Long,
    ): Map<String, Any?> = mapOf(
        TOKEN_HASH to OAuthCrypto.sha256Hex(token),
        USER_ID to userId,
        CLIENT_ID to clientId,
        TYPE to type,
        SCOPE to scope,
        RESOURCE to resource,
        FAMILY_ID to familyId,
        CONSUMED to false,
        CREATED_AT to timestamp,
        LAST_USED_AT to null,
        EXPIRES_AT to timestamp + lifetimeMillis,
    )

    override suspend fun validateAccessToken(token: String): OAuthTokenRecord? {
        if (!token.startsWith(ACCESS_TOKEN_PREFIX)) return null
        val hash = OAuthCrypto.sha256Hex(token)
        val snapshot = doc(hash).get().await()
        if (!snapshot.exists()) return null
        // Type and expiry, both checked — a wrong-type or stale token is rejected,
        // as the reference's WHERE clause does.
        if (snapshot.getString(TYPE) != TYPE_ACCESS) return null
        if ((snapshot.getLong(EXPIRES_AT) ?: 0L) <= now()) return null
        // Best-effort "last used", after the lookup and never a precondition: a
        // failure to record it must not fail the request.
        runCatching { doc(hash).update(LAST_USED_AT, now()).await() }
        return snapshot.toTokenRecord()
    }

    override suspend fun rotateRefresh(refreshToken: String): RefreshResult {
        if (!refreshToken.startsWith(REFRESH_TOKEN_PREFIX)) return RefreshResult.Invalid
        val hash = OAuthCrypto.sha256Hex(refreshToken)

        // Pre-read to learn the owner, so the MCP gate can be checked before anything
        // is consumed. The authoritative reuse check happens again inside the
        // transaction below; this read only decides Invalid/Refused.
        val pre = doc(hash).get().await()
        if (!pre.exists() || pre.getString(TYPE) != TYPE_REFRESH || (pre.getLong(EXPIRES_AT) ?: 0L) <= now()) {
            return RefreshResult.Invalid
        }
        val userId = pre.getLong(USER_ID) ?: return RefreshResult.Invalid
        if (!canUseMcp(userId)) return RefreshResult.Refused

        return firestore.runTransaction { txn ->
            val row = txn.get(doc(hash)).get()
            if (!row.exists() || row.getString(TYPE) != TYPE_REFRESH || (row.getLong(EXPIRES_AT) ?: 0L) <= now()) {
                return@runTransaction RefreshResult.Invalid
            }
            val familyId = row.getString(FAMILY_ID).orEmpty()
            if (row.getBoolean(CONSUMED) == true) {
                // A replay: the legitimate holder moved on, so only a copy made
                // before rotation could still present this. Revoke the whole family
                // — the thief's tokens and the victim's. Reads (the family query)
                // precede the writes (the deletes), as a transaction requires.
                val family = txn.get(collection().whereEqualTo(FAMILY_ID, familyId)).get()
                firestoreOAuthLogger.warn(
                    "MCP: refresh token reuse detected for client ${row.getString(CLIENT_ID)}, user " +
                        "${row.getLong(USER_ID)} — revoking family $familyId.",
                )
                family.documents.forEach { txn.delete(it.reference) }
                return@runTransaction RefreshResult.ReuseDetected
            }
            txn.update(doc(hash), CONSUMED, true)
            val tokens = mintPair(
                userId = row.getLong(USER_ID) ?: 0L,
                clientId = row.getString(CLIENT_ID).orEmpty(),
                scope = row.getString(SCOPE).orEmpty(),
                resource = row.getString(RESOURCE).orEmpty(),
                familyId = familyId,
            ) { ref, data -> txn.set(ref, data) }
            RefreshResult.Rotated(tokens, row.getLong(USER_ID) ?: 0L, familyId)
        }.await()
    }

    override suspend fun revokeFamily(familyId: String) {
        deleteFamily(familyId)
    }

    override suspend fun revokeByToken(token: String) {
        // No type or expiry filter: revoking an expired token must still succeed, and
        // a client revoking "a token" need not say which kind. Silent on an unknown
        // token — RFC 7009.
        val snapshot = doc(OAuthCrypto.sha256Hex(token)).get().await()
        if (!snapshot.exists()) return
        deleteFamily(snapshot.getString(FAMILY_ID).orEmpty())
    }

    override suspend fun listGrants(userId: Long): List<OAuthGrant> {
        // One single-field query, then group in memory — no composite index. Refresh
        // tokens are the grant (an access token would vanish an hour after its last
        // call); "last used" is the MAX across every token, including access ones.
        val mine = collection().whereEqualTo(USER_ID, userId).get().await().documents
        val byClient = mine.groupBy { it.getString(CLIENT_ID).orEmpty() }
        return byClient.mapNotNull { (clientId, rows) ->
            val refreshRows = rows.filter { it.getString(TYPE) == TYPE_REFRESH }
            if (refreshRows.isEmpty()) return@mapNotNull null
            val connectedAt = refreshRows.mapNotNull { it.getLong(CREATED_AT) }.minOrNull() ?: now()
            val lastUsedAt = rows.mapNotNull { it.getLong(LAST_USED_AT) }.maxOrNull()
            val clientName = firestore.collection(CLIENTS_COLLECTION).document(clientId).get().await()
                .getString(CLIENT_NAME_FIELD).orEmpty()
            OAuthGrant(clientId = clientId, clientName = clientName, connectedAt = connectedAt, lastUsedAt = lastUsedAt)
        }.sortedBy { it.connectedAt }
    }

    override suspend fun revokeForUserAndClient(userId: Long, clientId: String) {
        val mine = collection().whereEqualTo(USER_ID, userId).get().await().documents
            .filter { it.getString(CLIENT_ID) == clientId }
        deleteAll(mine)
        firestoreOAuthLogger.info("MCP: user $userId revoked client $clientId")
    }

    override suspend fun deleteExpired(): Long = sweepExpired(collection(), EXPIRES_AT, now())

    override suspend fun size(): Long = collection().get().await().size().toLong()

    private suspend fun deleteFamily(familyId: String) {
        val family = collection().whereEqualTo(FAMILY_ID, familyId).get().await().documents
        deleteAll(family)
    }

    private suspend fun deleteAll(docs: List<QueryDocumentSnapshot>) {
        if (docs.isEmpty()) return
        val batch = firestore.batch()
        docs.forEach { batch.delete(it.reference) }
        batch.commit().await()
    }

    private fun DocumentSnapshot.toTokenRecord() = OAuthTokenRecord(
        tokenHash = getString(TOKEN_HASH) ?: id,
        userId = getLong(USER_ID) ?: 0L,
        clientId = getString(CLIENT_ID).orEmpty(),
        scope = getString(SCOPE).orEmpty(),
        resource = getString(RESOURCE).orEmpty(),
        familyId = getString(FAMILY_ID).orEmpty(),
    )

    private companion object {
        const val COLLECTION = "oauthTokens"
        const val CLIENTS_COLLECTION = "oauthClients"
        const val CLIENT_NAME_FIELD = "clientName"
        const val TOKEN_HASH = "tokenHash"
        const val USER_ID = "userId"
        const val CLIENT_ID = "clientId"
        const val TYPE = "type"
        const val SCOPE = "scope"
        const val RESOURCE = "resource"
        const val FAMILY_ID = "familyId"
        const val CONSUMED = "consumed"
        const val CREATED_AT = "createdAt"
        const val LAST_USED_AT = "lastUsedAt"
        const val EXPIRES_AT = "expiresAt"
    }
}

// ── Shared reads ──────────────────────────────────────────────────────────────

/** A string-array field read back, or empty when absent. */
private fun DocumentSnapshot.stringList(field: String): List<String> {
    @Suppress("UNCHECKED_CAST")
    return (get(field) as? List<String>).orEmpty()
}

/**
 * Delete every document in [collection] whose [expiresField] is at or before [now],
 * returning how many. The single-field range query needs only an automatic index;
 * the sweep runs at startup, so a batched delete over the (small) expired set is
 * ample.
 */
private suspend fun sweepExpired(
    collection: com.google.cloud.firestore.CollectionReference,
    expiresField: String,
    now: Long,
): Long {
    val expired = collection.whereLessThanOrEqualTo(expiresField, now).get().await().documents
    if (expired.isEmpty()) return 0L
    val batch = collection.firestore.batch()
    expired.forEach { batch.delete(it.reference) }
    batch.commit().await()
    return expired.size.toLong()
}
