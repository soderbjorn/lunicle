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
     */
    override suspend fun create(userId: Long): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        doc(id).set(mapOf(USER_ID to userId, EXPIRES_AT to now() + SESSION_LIFETIME_MILLIS)).await()
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

    /** How many sessions are stored, expired or not — the analogue of SQLite `countAll`. */
    override suspend fun size(): Long = collection().get().await().size().toLong()

    private companion object {
        const val COLLECTION = "sessions"
        const val USER_ID = "userId"
        const val EXPIRES_AT = "expiresAt"
    }
}
