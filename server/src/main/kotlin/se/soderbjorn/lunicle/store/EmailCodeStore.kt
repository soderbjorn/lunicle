/**
 * The persistence seam for mailbox-proof codes — the storage half of
 * [se.soderbjorn.lunicle.EmailCodeService].
 *
 * Only the *storage* crosses this seam. Everything that makes an email code what it
 * is — generating six digits, hashing them, the attempt cap, the send-then-store
 * order, mailing the code — is backend-agnostic and stays on the service. What is
 * left here is exactly what the SQLite reference did against `EmailCodes.sq`: put a
 * pending row, read/consume it, charge a failed attempt, sweep expiries, and the two
 * per-user accessors LNL-71's pending-address-change UI leans on.
 *
 * ── Why [consumePending] takes a decision callback ──────────────────────────
 *
 * Redemption is one indivisible step: read the live row, compare the code, and then
 * *either* delete it (right code, or the attempt cap just tripped) *or* bump its
 * attempt counter — and the read and that write must not be interleaved with a
 * racing guess, or two attackers incrementing from the same read would count the cap
 * in their favour. But the comparison and the cap are the service's logic, not the
 * store's. So the store owns the transaction and calls back into the service for the
 * verdict: [consumePending] reads the pending row inside a backend transaction, hands
 * it to [decide], and applies the [PendingCodeAction] the service returns — all
 * atomically. The SQLite reference runs [decide] inside `transactionWithResult` on
 * [se.soderbjorn.lunicle] `DatabaseDispatcher`'s single thread; the Firestore one runs
 * it inside a `runTransaction`. Same guarantee, two mechanisms.
 *
 * ── Expiry is enforced by the read, not the caller ──────────────────────────
 *
 * A row past its `expiresAt` is invisible to [consumePending] and [pendingAddressFor]
 * even before the sweep has run — the rule `EmailCodes.sq` gets from a WHERE clause —
 * so a server left up for a month never honours a code that expired weeks ago.
 * [deleteExpired] is only the disk-space sweep behind that.
 *
 * @see se.soderbjorn.lunicle.EmailCodeStore the SQLite reference implementation.
 * @see se.soderbjorn.lunicle.FirestoreEmailCodeStore the document implementation.
 * @see EmailCodeStoreContract the behaviour both must exhibit.
 */
package se.soderbjorn.lunicle.store

/**
 * A pending code as storage hands it back for a redemption decision.
 *
 * Holds only what the service needs to judge a presented code: the stored hash to
 * compare against, how many wrong guesses this row has survived, and the context the
 * issuer supplied and expects echoed back. The raw code is not here — nothing stored
 * one; the hash is all there is, as every secret in this server is kept.
 *
 * @property address the normalized address the proof was for, echoed back so a caller
 *   writes the value that was proved rather than the value typed at it.
 * @property userId the account the proof was requested on behalf of, or null for a
 *   sign-in code issued before any account existed.
 * @property codeHash SHA-256 hex of the code. The store never interprets it; the
 *   service compares it.
 * @property attempts how many wrong codes this row has already survived.
 */
data class PendingEmailCode(
    val address: String,
    val userId: Long?,
    val codeHash: String,
    val attempts: Long,
)

/**
 * What [EmailCodeStore.consumePending] should do to the row it just showed the caller.
 *
 * The names are storage verbs, not the service's ("redeem", "refuse"): the store does
 * not know or care *why* a row is being deleted — a successful redemption and an
 * exhausted attempt cap are the same [Delete] to it, which is exactly what keeps
 * "reused" and "never existed" one fact from outside.
 */
enum class PendingCodeAction {
    /** Delete the row. A redeemed code and a code whose attempt cap just tripped both land here. */
    Delete,

    /** Charge one more failed attempt against the row and leave it live. */
    RecordAttempt,

    /** Touch nothing. There was no live row, or there was nothing to do. */
    Leave,
}

/**
 * The verdict [EmailCodeStore.consumePending]'s caller returns: the value to hand back,
 * and what to persist. [value] is the caller's own result type (the service returns its
 * redemption outcome); the store only reads [action].
 */
class PendingCodeDecision<out T>(val value: T, val action: PendingCodeAction)

/**
 * Storage for pending mailbox-proof codes. One row is one code mailed and not yet
 * redeemed. At most one live row per (address, purpose), and — when the issuer named an
 * account — at most one live row per (userId, purpose); [store] enforces both by
 * superseding.
 */
interface EmailCodeStore {
    /**
     * Persist a fresh code for [address] under [purpose], superseding any outstanding
     * code for the same address, and — when [userId] is non-null — any outstanding code
     * for the same account under this purpose. One atomic supersede-then-insert, so a
     * stale code in an older mail stops working the moment a new one is stored.
     *
     * @param codeHash SHA-256 hex of the code; the raw code never reaches storage.
     * @param createdAt and [expiresAt] absolute timestamps the service computed from its
     *   own clock and lifetime policy; the store persists them verbatim.
     */
    suspend fun store(
        address: String,
        purpose: String,
        codeHash: String,
        userId: Long?,
        createdAt: Long,
        expiresAt: Long,
    )

    /**
     * Atomically read the live (non-expired) code for [address] and [purpose], hand it
     * to [decide] (null when there is none), and apply the [PendingCodeAction] it
     * returns — all in one transaction. Returns [PendingCodeDecision.value].
     *
     * [decide] is the service's verdict: it compares the code and applies the attempt
     * cap. It must stay pure of I/O — it runs inside a backend transaction that may
     * re-run it on contention.
     */
    suspend fun <T> consumePending(
        address: String,
        purpose: String,
        decide: (PendingEmailCode?) -> PendingCodeDecision<T>,
    ): T

    /** The address [userId] is currently waiting to confirm under [purpose], or null. */
    suspend fun pendingAddressFor(userId: Long, purpose: String): String?

    /** Delete any pending code for ([userId], [purpose]). Idempotent. */
    suspend fun cancelFor(userId: Long, purpose: String)

    /** Delete codes past their expiry; returns how many. */
    suspend fun deleteExpired(): Long

    /** How many codes are outstanding. For the startup log, and for tests. */
    suspend fun size(): Long
}
