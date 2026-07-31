/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.SessionStore] — the
 * ids browsers carry in their cookie, and who each one is, over documents.
 *
 * ── Document model ──────────────────────────────────────────────────────────
 *
 * One document per session in `sessions/{token}`, where `{token}` is the cookie
 * value itself — 32 random bytes, base64url, from a [SecureRandom], exactly as the
 * SQLite store mints them (a guessable id is the whole attack). The document holds
 * two fields: `userId`, and an `expiresAt` stamp computed once at [create] as
 * `now + lifetime`. There is no allocated numeric id here — a session is addressed
 * by its secret token, not by a `Long` — so this is the one store that needs no
 * [FirestoreCounters].
 *
 * Storing `expiresAt` rather than `createdAt` is the document analogue of the
 * SQLite `deleteOlderThan(now - lifetime)`: `created_at < now - lifetime` is
 * exactly `expiresAt < now`, so the sweep becomes a single inequality query and
 * needs no arithmetic at read time. The lifetime constant matches the SQLite one.
 *
 * ── The one join, made an injected lookup ───────────────────────────────────
 *
 * [lookup] returns a whole [UserRecord], which in SQLite is a JOIN back to `users`.
 * A document backend has no join, so the user is resolved through [resolveUser], a
 * `suspend (Long) -> UserRecord?` the module wires to the Firestore identity store
 * and the contract wires to a synthetic map. A session whose user the lookup cannot
 * resolve reads as null, exactly as the SQLite inner join drops a session pointing
 * at a user that is gone (the `ON DELETE CASCADE` normally removes it first).
 *
 * ── One strengthening over the reference: lookup filters expiry ─────────────
 *
 * The SQLite [lookup] does not itself check expiry — an aged session stays usable
 * until a restart sweeps it (a documented limitation of that store). Here [lookup]
 * also refuses an expired session, which is only ever *stricter*, never looser: a
 * session the reference would still honour for a while, this one may already
 * reject. The store contract pins neither direction of that window — it asserts a
 * fresh session resolves and a swept one is gone, both of which hold on either
 * backend — so the two remain in contract parity.
 *
 * ── Composite indexes ───────────────────────────────────────────────────────
 *
 * None. [lookup] and [destroy] are addressed by document id; [deleteExpired] is a
 * single-field inequality (`expiresAt`), served by an automatic index; [size] is a
 * plain collection count.
 *
 * @see FirestoreProvider
 * @see se.soderbjorn.lunicle.store.SessionStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore
import java.security.SecureRandom
import java.util.Base64
import se.soderbjorn.lunicle.store.SessionStore

/**
 * How long a session lasts. Mirrors the private `SESSION_LIFETIME_MILLIS` in the
 * SQLite store — thirty days — so a session swept on one backend is swept on the
 * other at the same age.
 */
private const val SESSION_LIFETIME_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

class FirestoreSessionStore(
    private val firestore: Firestore,
    private val resolveUser: suspend (Long) -> UserRecord?,
    private val now: () -> Long = System::currentTimeMillis,
) : SessionStore {
    // SecureRandom, not Random: a session id is a bearer credential for the whole
    // account, and a predictably-seeded generator would let ids be guessed from one
    // another. Same choice the SQLite store makes.
    private val random = SecureRandom()

    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(id: String) = collection().document(id)

    /**
     * Mint a session id for [userId] and store it, stamped with the moment it
     * expires. The token is 32 url-safe bytes — comfortably past guessing, and it
     * survives a cookie round-trip without encoding surprises.
     *
     * A null [probeId] — every ordinary sign-in — writes **no field at all** rather
     * than an explicit null. That is the document analogue of the SQLite column
     * being NULL, and it is what lets [deleteProbeSessions] below be a plain
     * existence query.
     */
    override suspend fun create(userId: Long, probeId: String?): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val fields = buildMap {
            put(USER_ID, userId)
            put(EXPIRES_AT, now() + SESSION_LIFETIME_MILLIS)
            probeId?.let { put(PROBE_ID, it) }
        }
        doc(id).set(fields).await()
        return id
    }

    /**
     * The user behind [id], or null if it is null, unknown, expired, or points at a
     * user the identity store cannot resolve. See the class preamble on the expiry
     * filter and the resolver seam.
     */
    override suspend fun lookup(id: String?): UserRecord? {
        if (id == null) return null
        val snapshot = doc(id).get().await()
        if (!snapshot.exists()) return null
        val expiresAt = snapshot.getLong(EXPIRES_AT) ?: return null
        if (expiresAt <= now()) return null
        val userId = snapshot.getLong(USER_ID) ?: return null
        return resolveUser(userId)
    }

    /**
     * The grant [id] was minted against, or null for a session somebody proved.
     *
     * Its own document read rather than a field [lookup] returns, matching the
     * reference: the hot read stays as narrow as it is, and this one is asked only
     * when the impersonation gate is on. Unlike [lookup] it does **not** filter on
     * expiry — the caller is asking what a session is labelled, not whether it is
     * still good, and it has already resolved the user through [lookup] to know
     * there is anybody to ask about.
     */
    override suspend fun probeIdFor(id: String?): String? {
        if (id == null) return null
        val snapshot = doc(id).get().await()
        if (!snapshot.exists()) return null
        return snapshot.getString(PROBE_ID)
    }

    /** Forget [id]. Idempotent — a delete of a missing document is a no-op, and a null id nothing at all. */
    override suspend fun destroy(id: String?) {
        if (id == null) return
        doc(id).delete().await()
    }

    /**
     * Delete every session whose [EXPIRES_AT] has passed, and report how many went.
     *
     * The document form of the SQLite `created_at < now - lifetime` sweep: one
     * inequality query, then a batch delete so the removals apply together.
     */
    override suspend fun deleteExpired(): Long {
        val expired = collection().whereLessThanOrEqualTo(EXPIRES_AT, now()).get().await().documents
        if (expired.isEmpty()) return 0
        val batch = firestore.batch()
        expired.forEach { batch.delete(it.reference) }
        batch.commit().await()
        return expired.size.toLong()
    }

    /**
     * Delete every session an owner-impersonation grant minted, and report how many
     * went.
     *
     * The document form of `DELETE FROM sessions WHERE probe_id IS NOT NULL`. A
     * field-exists filter is what [create] not writing the field at all buys: the
     * ordering query `whereNotEqualTo(PROBE_ID, null)` would be an inequality on a
     * field most documents do not have, and Firestore omits documents missing the
     * ordered field from such a query — which is exactly the behaviour wanted here,
     * but only by accident. `orderBy` on the field says the same thing outright and
     * needs no composite index, since it is one field on one collection.
     */
    override suspend fun deleteProbeSessions(): Long {
        val probes = collection().orderBy(PROBE_ID).get().await().documents
        if (probes.isEmpty()) return 0
        val batch = firestore.batch()
        probes.forEach { batch.delete(it.reference) }
        batch.commit().await()
        return probes.size.toLong()
    }

    /** How many sessions are stored, expired or not — the analogue of SQLite `countAll`. */
    override suspend fun size(): Long = collection().get().await().size().toLong()

    private companion object {
        const val COLLECTION = "sessions"
        const val USER_ID = "userId"
        const val EXPIRES_AT = "expiresAt"

        /**
         * The grant a session was minted against. **Absent** on an ordinary session
         * rather than present-and-null, which is what makes [deleteProbeSessions] a
         * plain ordered query. See [create].
         */
        const val PROBE_ID = "probeId"
    }
}
