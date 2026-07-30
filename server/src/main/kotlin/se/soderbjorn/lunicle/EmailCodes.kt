/**
 * Proving that somebody controls a mailbox.
 *
 * Two features need exactly this and need it identically. Passwordless sign-in
 * ([authRoutes]' e-mail branch, LNL-74) uses it to establish who someone is;
 * verified address changes (LNL-71) use it to stop a signed-in user claiming
 * somebody else's address. The proofs are the same, and only what happens
 * afterwards differs — so this is one purpose-parameterised primitive built
 * before either caller existed, rather than two near-copies of a
 * security-sensitive lifecycle. Two copies is how one of them quietly ends up
 * without the attempt cap.
 *
 * A caller issues a code for `(address, purpose)` and later redeems
 * `(address, purpose, code)` for a yes or a typed refusal. Everything about how a
 * code is generated, stored, expired, capped and consumed is decided here; a
 * caller decides only what the proof authorises.
 *
 * ── What is deliberately NOT here: rate limiting ────────────────────────────
 *
 * [EmailCodeService.issue] refuses nothing on volume, and that is a decision
 * rather than an omission. The two callers have genuinely different threat
 * models — the address-change path is authenticated and keyed by user, the
 * sign-in path is unauthenticated and keyed by address plus client — and a
 * limiter folded in here would fit neither. So this primitive stays cheap to
 * call, and the endpoints in front of it decide who may call. See [RateLimiter].
 *
 * ── And: a magic link ───────────────────────────────────────────────────────
 *
 * The proof is a typed code and never a clickable link, for a structural reason
 * rather than a taste one. The MCP authorize page signs a visitor in *in place*
 * and reloads the same `/oauth/authorize` URL — see `signInPage` in OAuthServer.
 * A link would land in a different tab and orphan the one holding the PKCE
 * `code_challenge`, which is the whole flow. A code drops into the existing
 * design; a link cannot.
 *
 * @see EmailCodes.sq
 * @see EmailTransport
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.db.LunicleDatabase
import se.soderbjorn.lunicle.store.PendingCodeAction
import se.soderbjorn.lunicle.store.PendingCodeDecision
import se.soderbjorn.lunicle.store.PendingEmailCode
import java.security.MessageDigest
import java.security.SecureRandom

private val logger = LoggerFactory.getLogger("EmailCodes")

/**
 * How long an issued code lives. Fifteen minutes.
 *
 * Long enough to walk to another device, find the mail and type six digits back
 * — which is the whole worst case, since the code goes in the subject line and
 * needs no app to be opened. Short enough that a code sitting unread in a
 * mailbox is not a standing credential.
 */
private const val CODE_LIFETIME_MILLIS: Long = 15L * 60 * 1000

/**
 * How many wrong codes one issued code survives.
 *
 * **The load-bearing number in this file.** Six digits is a space of 10^6, which
 * an unmetered script walks in minutes; it is this cap, and not the length, that
 * makes a short code safe. Five is generous for a human mistyping from a mail
 * and worth nothing to a script.
 *
 * Reaching it deletes the row rather than marking it, so an exhausted code and a
 * code that never existed are the same fact from outside.
 */
private const val ATTEMPT_LIMIT: Long = 5

/**
 * What a proof of mailbox control is *for*.
 *
 * Part of the storage key, not a label. A code issued to confirm an address
 * change must not be redeemable as a sign-in: LNL-71 mails a code to an address
 * the signed-in user has merely claimed, and if the sign-in endpoint accepted it,
 * that confirmation mail would be a way into the account it was being attached
 * to. Two live codes for one address under different purposes coexist and never
 * substitute for each other.
 *
 * Serialized by name into `email_codes.purpose`; adding a case is free, renaming
 * one strands any code in flight.
 */
enum class EmailCodePurpose {
    /** Redeeming this establishes an identity and mints a session. See LNL-74. */
    SIGN_IN,

    /** Redeeming this attaches an address to an account that already exists. See LNL-71. */
    EMAIL_CHANGE,
}

/**
 * What redeeming a code produced.
 *
 * Two cases and not five, deliberately. A wrong code, an expired one, one whose
 * attempts are exhausted and one that was never issued are distinct facts
 * *internally* — each is logged — and they must be indistinguishable to whoever
 * is looking at the screen. A caller that could tell them apart would eventually
 * render the difference, and "that code has expired" versus "no code was issued
 * for that address" is an account-existence oracle assembled by accident.
 */
sealed interface EmailCodeRedemption {

    /**
     * The code was right, live, and within its attempt cap. It is now gone.
     *
     * @property address the normalized address the proof was for — echoed back so
     *   a caller writes the value that was proved rather than the value that was
     *   typed at it.
     * @property userId the account context the issuer supplied, or null when
     *   there was none. See `email_codes.user_id`.
     */
    data class Redeemed(val address: String, val userId: Long?) : EmailCodeRedemption

    /** Anything else. See this interface's doc for why there is only one of these. */
    data object Refused : EmailCodeRedemption
}

/**
 * Issues and redeems mailbox-proof codes, and sends the mail itself.
 *
 * Sending is inside this rather than left to the caller so that a caller cannot
 * forget to — an issued code nobody was told about is a user watching a spinner
 * for a mail that is never coming.
 *
 * @param store where the pending codes live. The persistence half of this service,
 *   split out for LNL-111 so a Firestore deploy can supply a document-backed
 *   [se.soderbjorn.lunicle.store.EmailCodeStore] in place of the SQLite reference
 *   without this class knowing which. The generation, the attempt cap and the send
 *   order above stay here — only the storage crosses the seam.
 * @param sender the active transport, or null when this deployment configured no
 *   mail. Null is not a broken server: it is [chooseEmailTransport]'s
 *   absent-means-off convention, and [issue] answers [IssueOutcome.NotConfigured]
 *   rather than pretending it sent something.
 * @param now supplies timestamps for a code's [issue]-time lifetime; injectable so a
 *   test can expire a code without waiting a quarter of an hour. The store carries its
 *   own clock for the *read-side* expiry filtering (a code found is a code not yet
 *   past `expires_at`); the convenience constructor wires both to the same `now`.
 */
class EmailCodeService(
    private val store: se.soderbjorn.lunicle.store.EmailCodeStore,
    private val sender: EmailTransport?,
    private val baseUrl: String = resolvePublicBaseUrl() ?: "",
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * The reference wiring: build the service over the SQLite
     * [se.soderbjorn.lunicle.EmailCodeStore], on the same clock. This is what
     * `Application.module` and the existing tests construct; the store-injecting primary
     * constructor is for the Firestore deploy (LNL-122).
     */
    constructor(
        database: LunicleDatabase,
        sender: EmailTransport?,
        baseUrl: String = resolvePublicBaseUrl() ?: "",
        now: () -> Long = System::currentTimeMillis,
    ) : this(EmailCodeStore(database, now), sender, baseUrl, now)

    // SecureRandom, never kotlin.random.Random — the same rule SessionStore and
    // OAuthCrypto state, and for the same reason. A code is a bearer credential
    // for an account, and Random is seeded predictably enough that one code could
    // be derived from another.
    private val random = SecureRandom()

    /** Whether this deployment can send at all. Drives what the sign-in surfaces offer. */
    val isAvailable: Boolean get() = sender != null

    /**
     * What asking for a code produced.
     *
     * A caller needs to distinguish these three because they mean different
     * things to an operator, but note that the *sign-in* endpoint deliberately
     * renders [Sent] and [SendFailed] identically — see LNL-74. That is the
     * endpoint's decision to make, not this primitive's, which is why the
     * distinction exists here at all.
     */
    sealed interface IssueOutcome {
        /** The mail left the building. */
        data object Sent : IssueOutcome

        /** No mail is configured on this deployment. Nothing was stored. */
        data object NotConfigured : IssueOutcome

        /**
         * The provider refused, or could not be reached. **Nothing was stored.**
         *
         * @property message safe to show a user; the provider's own words are
         *   logged by the [EmailTransport] and stay off the screen.
         */
        data class SendFailed(val message: String) : IssueOutcome
    }

    /**
     * Mint a code for [address] under [purpose], store its hash, and mail it.
     *
     * Any outstanding code for the same pair is superseded, so a stale code in an
     * older mail stops working the moment a new one is asked for.
     *
     * ── Send-then-store, and why the order is the whole point ───────────────
     *
     * The mail goes out *before* the row lands, and a failed send therefore
     * leaves nothing redeemable behind. The other order is the tempting one — it
     * reads as "record the intent, then act on it" — and it produces the failure
     * this design exists to avoid: a live code for a mail that never arrived,
     * which is a lockout with no diagnosis, indistinguishable from the user
     * mistyping their own address.
     *
     * The cost of this order is the mirror failure: a mail that arrives holding a
     * code the server did not manage to store, if the process dies between the
     * two. That one is self-describing — the code simply does not work, the user
     * asks for another, and the second one does. A failure a user can walk out of
     * beats one only a log can explain.
     *
     * @param address the mailbox, already normalized by the caller. Not
     *   normalized here: normalization is the identity layer's policy (see
     *   [normalizeEmail]) and a primitive that silently rewrote its argument
     *   would leave the caller unsure which spelling it had proved.
     * @param userId the account this proof is on behalf of, or null when there is
     *   none yet. Stored verbatim and handed back on redemption; never
     *   interpreted here.
     */
    suspend fun issue(
        address: String,
        purpose: EmailCodePurpose,
        userId: Long? = null,
    ): IssueOutcome {
        val sender = sender ?: return IssueOutcome.NotConfigured
        val code = newCode()

        try {
            sender.send(
                to = address,
                subject = emailCodeSubject(code, purpose),
                html = emailCodeBody(code, purpose, baseUrl),
                text = emailCodeText(code, purpose, baseUrl),
            )
        } catch (failure: EmailSendFailure) {
            logger.warn("Could not send a $purpose code to <$address>: ${failure.message}")
            return IssueOutcome.SendFailed(failure.message ?: "The code could not be sent.")
        }

        val issuedAt = now()
        // Supersede-then-insert lives in the store now, atomically: any outstanding
        // code for this address, and — for a caller with an account behind it — any
        // outstanding code for that account under this purpose, is dropped as the new
        // one lands. Asking to move your address to `new1@` and then thinking better of
        // it and asking for `new2@` must leave one pending change, not two, because
        // `pendingFor` asks that in the singular. The lifetime is this service's policy,
        // so the absolute `expiresAt` is computed here and stored verbatim.
        store.store(
            address = address,
            purpose = purpose.name,
            codeHash = hashOf(code),
            userId = userId,
            createdAt = issuedAt,
            expiresAt = issuedAt + CODE_LIFETIME_MILLIS,
        )
        logger.info("Issued a $purpose code to <$address>")
        return IssueOutcome.Sent
    }

    /**
     * Spend [code] against the live code for [address] and [purpose].
     *
     * The whole check is one transaction on [DatabaseDispatcher]'s single thread,
     * so the read, the comparison, the attempt increment and the delete cannot be
     * interleaved with a concurrent attempt — the same property `rotateRefresh`
     * relies on in [OAuthTokenStore], and for the sharper version of its reason:
     * two racing guesses that both incremented from the same read would be an
     * attempt cap that counts wrong in the attacker's favour.
     *
     * @return [EmailCodeRedemption.Redeemed] with the issuer's context, or
     *   [EmailCodeRedemption.Refused] for every other outcome. The reason is
     *   logged and never returned; see [EmailCodeRedemption].
     */
    suspend fun redeem(
        address: String,
        purpose: EmailCodePurpose,
        code: String,
    ): EmailCodeRedemption = store.consumePending(address, purpose.name) { pending ->
        // The store has read the live row (expiry already applied — a code past
        // `expires_at` is handed back as null, the WHERE-clause rule Sessions.kt
        // documents the trap of skipping) inside a transaction and is asking what to
        // do with it. The comparison and the attempt cap are this service's to decide;
        // the store applies the PendingCodeAction we return, atomically with the read,
        // so two racing guesses cannot both increment from the same attempts count.
        if (pending == null) {
            // Absent, expired, already spent, or exhausted — all one branch from here,
            // because deleting on exhaustion made them one fact.
            logger.info("Refused a $purpose code for <$address>: no live code")
            PendingCodeDecision(EmailCodeRedemption.Refused, PendingCodeAction.Leave)
        } else if (!matches(code, pending.codeHash)) {
            val attempts = pending.attempts + 1
            if (attempts >= ATTEMPT_LIMIT) {
                // The cap. Deleting rather than marking is what keeps "exhausted" and
                // "never existed" the same answer for the null branch above.
                logger.warn(
                    "Refused a $purpose code for <$address>: wrong code, " +
                        "attempt cap reached — the code is now dead",
                )
                PendingCodeDecision(EmailCodeRedemption.Refused, PendingCodeAction.Delete)
            } else {
                logger.info("Refused a $purpose code for <$address>: wrong code ($attempts/$ATTEMPT_LIMIT)")
                PendingCodeDecision(EmailCodeRedemption.Refused, PendingCodeAction.RecordAttempt)
            }
        } else {
            // Single use: consumed on success, and gone.
            logger.info("Redeemed a $purpose code for <$address>")
            PendingCodeDecision(
                EmailCodeRedemption.Redeemed(address = pending.address, userId = pending.userId),
                PendingCodeAction.Delete,
            )
        }
    }

    /**
     * Delete codes past their expiry.
     *
     * Called once at startup beside the session and OAuth sweeps, and a disk-space
     * question rather than a security one for the reason [redeem] gives: the
     * lookup already refuses an expired row, so a missed sweep leaves litter and
     * never a usable credential.
     *
     * @return how many were removed.
     */
    suspend fun deleteExpired(): Long = store.deleteExpired()

    /**
     * The address [userId] is currently waiting to confirm, or null.
     *
     * What makes a pending address change survive the settings pane being closed
     * and reopened: the pending state is a row rather than a field in a view
     * model, so re-fetching the session is all "did I ask for this?" needs. See
     * `SessionState.pendingEmail`.
     */
    suspend fun pendingFor(userId: Long, purpose: EmailCodePurpose): String? =
        store.pendingAddressFor(userId, purpose.name)

    /**
     * Give up on [userId]'s pending proof.
     *
     * Idempotent, and it cannot fail in an interesting way: the pending row never
     * touched the account, so there is nothing to undo and nothing to confirm.
     */
    suspend fun cancelFor(userId: Long, purpose: EmailCodePurpose): Unit =
        store.cancelFor(userId, purpose.name)

    /** How many codes are outstanding. For the startup log, and for tests. */
    suspend fun size(): Long = store.size()

    /**
     * Six digits from [SecureRandom], zero-padded.
     *
     * The padding is what makes the space genuinely 10^6: without it `000123`
     * would be minted as `123`, and every code with a leading zero would be short
     * — which is both a smaller space and a code the user cannot type back as
     * shown. `nextInt(1_000_000)` rather than a modulus, because a modulus over a
     * full-range int is biased toward low values.
     */
    private fun newCode(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

    /** SHA-256, hex — never the code. The rule every secret in this server follows. */
    private fun hashOf(code: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * Is [candidate] the code behind [storedHash]?
     *
     * [MessageDigest.isEqual] rather than `==`, because it does not short-circuit
     * on the first differing byte. The timing signal from a string comparison of
     * two hex digests is small and the attack against six digits is a guessing
     * one anyway — but this is the comparison that decides whether somebody is
     * let in, and it costs a function name to be the right one.
     */
    private fun matches(candidate: String, storedHash: String): Boolean =
        MessageDigest.isEqual(hashOf(candidate).toByteArray(), storedHash.toByteArray())
}

/**
 * The SQLite [se.soderbjorn.lunicle.store.EmailCodeStore] — the persistence lifted out
 * of [EmailCodeService], unchanged, over `EmailCodes.sq`.
 *
 * Every method is the exact SQLDelight call the service used to make inline, on
 * [DatabaseDispatcher]'s single thread. That thread is what makes [consumePending]
 * atomic: the `find`, the caller's verdict, and the resulting `delete` or
 * `recordAttempt` are one `transactionWithResult` and cannot interleave with a racing
 * guess — the same property the service's `redeem` always relied on, now expressed as
 * the store contract rather than as a shape the service happened to have.
 *
 * The class name matches the interface's simple name deliberately (the reference
 * implementation *is* "the EmailCodeStore"), so the supertype is written fully
 * qualified; callers that want the interface type do the same. See the OAuth stores
 * for the same convention.
 *
 * @param now the read-side clock: `find`, `findForUser` and `deleteExpired` filter by
 *   it, so a test can expire a row by advancing it. Wired to the same lambda the
 *   service holds.
 */
class EmailCodeStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.EmailCodeStore {

    override suspend fun store(
        address: String,
        purpose: String,
        codeHash: String,
        userId: Long?,
        createdAt: Long,
        expiresAt: Long,
    ): Unit = withContext(DatabaseDispatcher) {
        database.transaction {
            // Supersede explicitly rather than leaning on INSERT OR REPLACE, so the
            // superseding is legible. The UNIQUE (address, purpose) makes omitting the
            // first a constraint violation rather than a second live code; the partial
            // unique (user_id, purpose) does the same for the second.
            database.emailCodesQueries.deleteFor(address, purpose)
            if (userId != null) database.emailCodesQueries.deleteForUser(userId, purpose)
            database.emailCodesQueries.insert(
                address = address,
                purpose = purpose,
                code_hash = codeHash,
                created_at = createdAt,
                expires_at = expiresAt,
                user_id = userId,
            )
        }
    }

    override suspend fun <T> consumePending(
        address: String,
        purpose: String,
        decide: (PendingEmailCode?) -> PendingCodeDecision<T>,
    ): T = withContext(DatabaseDispatcher) {
        database.transactionWithResult {
            // Expiry is in the WHERE clause, so a row past `expires_at` is handed to
            // the caller as null even before the sweep runs.
            val row = database.emailCodesQueries
                .find(address, purpose, now())
                .executeAsOneOrNull()
            val pending = row?.let {
                PendingEmailCode(
                    address = it.address,
                    userId = it.user_id,
                    codeHash = it.code_hash,
                    attempts = it.attempts,
                )
            }
            val decision = decide(pending)
            when (decision.action) {
                PendingCodeAction.Delete -> if (row != null) database.emailCodesQueries.delete(row.id)
                PendingCodeAction.RecordAttempt -> if (row != null) database.emailCodesQueries.recordAttempt(row.id)
                PendingCodeAction.Leave -> Unit
            }
            decision.value
        }
    }

    override suspend fun pendingAddressFor(userId: Long, purpose: String): String? =
        withContext(DatabaseDispatcher) {
            database.emailCodesQueries
                .findForUser(userId, purpose, now())
                .executeAsOneOrNull()
                ?.address
        }

    override suspend fun cancelFor(userId: Long, purpose: String): Unit =
        withContext(DatabaseDispatcher) {
            database.emailCodesQueries.deleteForUser(userId, purpose)
        }

    override suspend fun deleteExpired(): Long = withContext(DatabaseDispatcher) {
        database.emailCodesQueries.deleteExpired(now()).value
    }

    override suspend fun size(): Long = withContext(DatabaseDispatcher) {
        database.emailCodesQueries.countAll().executeAsOne()
    }
}
