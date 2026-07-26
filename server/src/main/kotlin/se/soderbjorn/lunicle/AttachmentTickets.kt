/**
 * Upload tickets: how an agent's bytes reach the volume.
 *
 * ── The problem this exists for ─────────────────────────────────────────────
 *
 * An agent importing a tracker's history has screenshots to bring with it, and
 * no way to hand them over. The two obvious answers are both wrong:
 *
 *  - **Base64 in the tool call.** An MCP tool's arguments are JSON in a model's
 *    context. A 2 MB screenshot is ~2.7 million characters of base64 — several
 *    hundred thousand tokens, to move bytes the model never needs to look at.
 *    It does not scale to one image, let alone an import.
 *  - **The server fetches a URL the agent names.** Tidy for the GitHub case, and
 *    it makes this server issue outbound requests to addresses an argument chose.
 *    That is SSRF, on a host whose internal network includes the volume and the
 *    metadata endpoint, and the mitigations (block private ranges, re-check every
 *    redirect hop, cap the read) are a security surface bought for a convenience.
 *
 * The agent already has a shell, so bytes were never the hard part —
 * authorisation was. `POST /api/issues/{id}/attachments` does exactly the right
 * thing already, and the agent simply cannot call it: that route wants a session
 * cookie, and an MCP token belongs to the client, never to the model.
 *
 * So: mint a capability instead. [AttachmentTicketStore.mint] answers "may you,
 * and on whose behalf" once, up front, where [AccessControl] already lives; what
 * comes back is a URL that accepts bytes and nothing else. The agent curls the
 * file at it — from disk, or downloaded from GitHub first, identically. Nothing
 * enters the model's context and this server fetches nothing.
 *
 * ── What a ticket can do, which is as little as possible ───────────────────
 *
 * One file, one place, as one author, within five minutes, once. Every one of
 * those is decided at mint and none of them is an argument at redemption — the
 * redeem route takes bytes and a token and has no opinion about anything else.
 * That is the property that matters: **attribution cannot be set by the caller
 * who uploads.** An external author is admin-only (see
 * [AccessControl.canAttributeWrites]) and stays admin-only here, because by the
 * time bytes arrive the question has already been answered and stored.
 *
 * Contrast the shortcut this replaces — teaching the attachment route to accept
 * an MCP token as a bearer credential. One line, and it would hand the model a
 * credential for the user's entire account in order to let it upload one PNG.
 *
 * ── In memory, and why that is not a shrug ─────────────────────────────────
 *
 * These live in the heap, so a redeploy forgets them. That costs an agent one
 * retry inside a five-minute window, against a service that already has
 * downtime on every redeploy — volumes and replicas are mutually exclusive on
 * Railway, so a volume-backed deploy cannot hand over gracefully. See
 * docs/analysis.html §7.
 *
 * The failure that would matter is the other one: mint on one instance, redeem
 * on another. It cannot happen here, and not because we are careful — Railway
 * refuses replicas to any service with a volume, and the database is on the
 * volume. One instance is structural. If that ever stops being true, this map is
 * the first thing that breaks, and it must become a table before it does.
 *
 * @see McpTools
 * @see AttachmentRepository
 */
package se.soderbjorn.lunicle

import java.util.concurrent.ConcurrentHashMap

/**
 * Where an upload is going.
 *
 * The same "exactly one owner" the attachments table spells as a CHECK — see
 * Attachments.sq — said as a type, so a ticket for both or neither is not a
 * thing that can be minted.
 */
sealed interface AttachmentTarget {
    data class Issue(val issueId: Long) : AttachmentTarget
    data class Comment(val commentId: Long) : AttachmentTarget

    /**
     * A forum post, and below it one of its comments.
     *
     * Added by LNL-78 alongside the forum tools, and it is what makes importing a
     * forum a job that can actually finish: a discussion whose screenshots are all
     * dead links from wherever it came from is the same half-import
     * `start_attachment_upload` was written for on the issue side. The rows and
     * the storage already existed — see [AttachmentRepository.storeForForumPost] —
     * so this adds a way to *reach* them and nothing else.
     *
     * Four cases rather than a target holding an id and a kind, for the reason
     * this interface exists: the redeem route's `when` is exhaustive, so a fifth
     * owner added to the attachments table without a branch here is a compile
     * error rather than a file that lands nowhere.
     */
    data class ForumPost(val postId: Long) : AttachmentTarget

    data class ForumComment(val commentId: Long) : AttachmentTarget
}

/**
 * One redeemable upload slot.
 *
 * Everything the redeem route needs, decided before the bytes exist. Note what
 * is *not* here: any way to change any of it. The route reads this and reads the
 * body, and that is the whole of its input.
 *
 * @property author who the file will belong to. Resolved at mint, behind
 *   [AccessControl.canAttributeWrites] if it is an [Author.External].
 * @property createdAt what the row will claim, or null for "whenever it lands".
 *   An imported screenshot belongs at its issue's moment, not the importer's.
 * @property expiresAt when this stops working, in epoch millis.
 */
class AttachmentTicket(
    val target: AttachmentTarget,
    val filename: String,
    val author: Author,
    val createdAt: Long?,
    val expiresAt: Long,
)

/**
 * The live tickets.
 *
 * @param now the clock, injectable so the expiry tests are not sleeps.
 */
class AttachmentTicketStore(
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Keyed by the token's SHA-256, never the token.
     *
     * The same rule [OAuthCrypto.sha256Hex] states for every other secret in
     * this system, and it costs nothing to keep here: a heap dump, a log of a
     * map, or an error that renders this object cannot then hand over live
     * credentials. It also means the lookup is on a digest, so there is no
     * string comparison against the secret to time.
     *
     * [ConcurrentHashMap] rather than a lock because `remove` is atomic, and
     * atomic removal *is* single-use redemption: two requests racing the same
     * token, one wins and one gets null. A `containsKey` followed by a `remove`
     * would be the same code with a window in it.
     */
    private val byTokenHash = ConcurrentHashMap<String, AttachmentTicket>()

    /**
     * Mint a ticket and return its token — the one time the token exists.
     *
     * The caller's right to do this, and the author being claimed, are settled
     * before this is reached. This function is bookkeeping; [McpTools] is where
     * the decision is.
     */
    fun mint(target: AttachmentTarget, filename: String, author: Author, createdAt: Long?): String {
        sweep()
        val token = OAuthCrypto.randomToken(TICKET_PREFIX)
        byTokenHash[OAuthCrypto.sha256Hex(token)] = AttachmentTicket(
            target = target,
            filename = filename,
            author = author,
            createdAt = createdAt,
            expiresAt = now() + TICKET_TTL_MILLIS,
        )
        return token
    }

    /**
     * Spend a token, or null if it was never valid, has already been spent, or
     * has expired.
     *
     * The three cases are deliberately one answer. A caller holding a token
     * either has a live one or does not, and telling an unauthenticated request
     * *which* kind of no it is turns this into an oracle for whether a given
     * token ever existed.
     *
     * Removed before the expiry check, not after: an expired token is spent
     * either way, and leaving it in the map on the "expired" branch would keep a
     * dead ticket alive until the next sweep for no reason.
     */
    fun redeem(token: String): AttachmentTicket? {
        val ticket = byTokenHash.remove(OAuthCrypto.sha256Hex(token)) ?: return null
        return ticket.takeIf { now() < it.expiresAt }
    }

    /** How many are live. For tests and nothing else. */
    internal fun size(): Int {
        sweep()
        return byTokenHash.size
    }

    /**
     * Drop what has expired.
     *
     * On mint rather than on a timer: tickets only accumulate when they are
     * being made, so the moment one is made is exactly when the map is worth
     * tidying, and a scheduled sweep would be a thread to own and shut down for
     * a map that is almost always empty. [redeem] does not need it — it checks
     * the one ticket it took.
     */
    private fun sweep() {
        val cutoff = now()
        byTokenHash.values.removeIf { cutoff >= it.expiresAt }
    }
}

/**
 * How long a ticket lives.
 *
 * Long enough to download a file from GitHub and push it here on a bad
 * connection; short enough that a token leaked into a shell history or a
 * transcript is worthless by the time anyone reads it. An agent that needs
 * longer should mint again — they are free.
 */
private const val TICKET_TTL_MILLIS = 5L * 60 * 1000

/** Marks the token in a log or a shell history as ours, and as an upload ticket. */
private const val TICKET_PREFIX = "lat_"
