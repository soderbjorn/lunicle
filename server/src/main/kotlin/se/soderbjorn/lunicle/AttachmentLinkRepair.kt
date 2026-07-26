/**
 * Moves already-written links to attachable documents onto the URL that opens
 * them, instead of the one that saves them.
 *
 * ── Why a repair exists rather than a smarter renderer ──────────────────────
 *
 * An HTML report gets two possible URLs: `/api/attachments/12`, which answers
 * with `Content-Disposition: attachment`, and `/api/attachments/12/view`, which
 * answers under a `Content-Security-Policy: sandbox` and so may be shown. Which
 * one an attachment is written with is decided at upload, by `attachmentMarkdown`
 * — because the renderer sees a URL and a label and has no way to ask what type a
 * file is, so the URL is the only place the answer can live.
 *
 * That makes the answer permanent. Every report attached before the view route
 * existed carries the download spelling, and no change to the renderer can reach
 * it: the markdown says download, the `<a download>` it produces overrides
 * whatever the server would send, and the reader gets a file on their disk. That
 * is what LNL-15 was reopened about — the feature worked for new uploads and did
 * nothing at all for the attachment the reporter actually clicked.
 *
 * So the stored documents are repaired. Once, on the way up, next to
 * [AttachmentRepository.sweepOrphans] — the other startup pass that reconciles
 * what is written down with what is true.
 *
 * ── Why it is safe to run on every boot ─────────────────────────────────────
 *
 * It is idempotent by construction: [retargetAttachmentLinks] rewrites the
 * download spelling and leaves the view spelling alone, so the second run finds
 * nothing to do and writes nothing. A boot on a repaired volume costs two
 * `LIKE '%/api/attachments/%'` scans and no writes.
 *
 * Nothing here bumps `updated_at` and no history event is written, on
 * [MentionRenamer]'s reasoning: correcting how a link is spelled is not an edit
 * anybody made, and a board sorted on "last touched" must not claim otherwise.
 *
 * ── What it deliberately does not do ────────────────────────────────────────
 *
 * It does not go the other way. An attachment whose type stopped being viewable —
 * because [SANDBOXED_DOCUMENT_MIME_TYPES] shrank — keeps its `/view` links, and
 * the route answers those exactly as the download route would, so the link still
 * works and merely opens a tab that saves a file. Rewriting backwards would mean
 * this pass edits documents whenever a constant changes in either direction,
 * which is a lot of authority for a startup task to hold over user text.
 *
 * @see retargetAttachmentLinks
 * @see MentionRenamer for the other bulk markdown rewrite, and the conventions
 *   this borrows from it.
 */
package se.soderbjorn.lunicle

import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.clientserver.isSandboxedDocumentType
import se.soderbjorn.lunicle.clientserver.retargetAttachmentLinks

private val logger = LoggerFactory.getLogger("AttachmentLinkRepair")

/**
 * Re-spells attachment links across issue descriptions, comment bodies, the
 * forum's posts and comments, and private messages.
 *
 * The last three cannot hold a *stale* spelling — forums and messages both
 * arrived after the view route did, so nothing was ever written there with the
 * download URL — and they are scanned anyway, for [MentionRenamer]'s reason: a
 * bulk pass over stored markdown that covers four of the five places markdown is
 * stored is a pass that will be wrong the first time somebody changes
 * SANDBOXED_DOCUMENT_MIME_TYPES. Each scan is a `LIKE` over a table that is empty
 * on most instances.
 *
 * @param attachments asked one question: which ids are documents the view route
 *   will serve. The types themselves stay in `SANDBOXED_DOCUMENT_MIME_TYPES`,
 *   which the uploader reads too — a second list here is the drift the
 *   `:clientServer` module exists to prevent.
 */
class AttachmentLinkRepair(
    private val attachments: se.soderbjorn.lunicle.store.AttachmentStore,
    private val issues: se.soderbjorn.lunicle.store.IssueStore,
    private val comments: se.soderbjorn.lunicle.store.CommentStore,
    private val forumPosts: se.soderbjorn.lunicle.store.ForumPostStore,
    private val forumComments: se.soderbjorn.lunicle.store.ForumCommentStore,
    private val messages: se.soderbjorn.lunicle.store.MessageStore,
) {
    /** Repair everything that needs it. Safe to call on every start. */
    suspend fun run() {
        val viewable = attachments.allMimeTypes()
            .filter { (_, mimeType) -> isSandboxedDocumentType(mimeType) }
            .map { (id, _) -> id }
            .toSet()
        // The common case on most instances: nothing has ever been attached that
        // this could be about, and the two scans below would find nothing.
        if (viewable.isEmpty()) return

        var rewritten = 0
        issues.withAttachmentLinks().forEach { (id, description) ->
            val next = retargetAttachmentLinks(description, viewable)
            if (next != description) {
                issues.setDescription(id, next)
                rewritten++
            }
        }
        comments.withAttachmentLinks().forEach { (id, body) ->
            val next = retargetAttachmentLinks(body, viewable)
            if (next != body) {
                comments.update(id, next)
                rewritten++
            }
        }
        forumPosts.withAttachmentLinks().forEach { (id, body) ->
            val next = retargetAttachmentLinks(body, viewable)
            if (next != body) {
                forumPosts.updateBody(id, next)
                rewritten++
            }
        }
        forumComments.withAttachmentLinks().forEach { (id, body) ->
            val next = retargetAttachmentLinks(body, viewable)
            if (next != body) {
                forumComments.updateBody(id, next)
                rewritten++
            }
        }
        messages.withAttachmentLinks().forEach { (id, body) ->
            val next = retargetAttachmentLinks(body, viewable)
            if (next != body) {
                messages.updateBody(id, next)
                rewritten++
            }
        }
        if (rewritten > 0) {
            logger.info("Attachments: re-spelled links in $rewritten document(s) to open rather than download")
        }
    }
}
