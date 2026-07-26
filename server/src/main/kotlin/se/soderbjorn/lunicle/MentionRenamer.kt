/**
 * Keeps written `@mentions` pointing at the person who was mentioned, across a
 * change of display name.
 *
 * ── Why the text is rewritten rather than the reference ──────────────────────
 *
 * A mention is stored as the name it names — see Mentions.kt, which explains at
 * length why the alternative (markup carrying an account id) was rejected: it
 * cannot be typed by hand, so it would give two kinds of mention, one of which
 * notifies and one of which merely looks like it. That choice has one cost, and
 * this file is the payment: when a display name changes, every `@OldName`
 * already written stops resolving. It still *renders* as a mention, still reads
 * as one, and quietly notifies nobody ever again.
 *
 * So a rename rewrites the text. What the comment says afterwards is what it
 * always meant, and the reference works again for the same reason it worked
 * when it was typed.
 *
 * ── What it deliberately does not touch ──────────────────────────────────────
 *
 * Authors, assignees and subscriptions are account ids and follow a rename by
 * themselves. `issue_events.value_text` holds a snapshot of an assignee's name,
 * but its reader prefers `value_user_id`'s current name and falls back to the
 * snapshot only for a deleted account — so history follows too. `created_by_
 * external` names people who have no account at all and cannot be the one
 * renaming. Titles are not scanned: nothing renders a mention in a title, so a
 * `@` there is punctuation.
 *
 * Nothing here bumps `updated_at`, and no history event is written. Somebody
 * else changing their name is not an edit to your issue, and a board sorted on
 * "last touched" must not claim otherwise.
 *
 * @see renameMentions
 */
package se.soderbjorn.lunicle

import org.slf4j.LoggerFactory
import se.soderbjorn.lunicle.clientserver.renameMentions

private val logger = LoggerFactory.getLogger("MentionRenamer")

/**
 * Rewrites `@mentions` when somebody's display name changes.
 *
 * @param users needed for one question only, and it is the important one: does
 *   anybody *else* still answer to the old name? Display names are not unique
 *   (users has no UNIQUE on the column), so if a second account still resolves
 *   to it, rewriting would silently redirect their mentions to the person who
 *   just left the name behind. In that case nothing is rewritten — the old
 *   mentions still reach somebody, which is strictly better than reaching the
 *   wrong somebody.
 */
class MentionRenamer(
    private val users: se.soderbjorn.lunicle.store.UserStore,
    private val issues: se.soderbjorn.lunicle.store.IssueStore,
    private val comments: se.soderbjorn.lunicle.store.CommentStore,
    private val forumPosts: se.soderbjorn.lunicle.store.ForumPostStore,
    private val forumComments: se.soderbjorn.lunicle.store.ForumCommentStore,
    private val messages: se.soderbjorn.lunicle.store.MessageStore,
) {
    /**
     * Move every mention of [from] to [to], across issue descriptions, comment
     * bodies, forum post bodies, forum comment bodies and private messages.
     *
     * All five, not the first two. Each is prose somebody typed with the same
     * editor into the same mention grammar, so a rename that skipped one would
     * leave it as the one place an old name survived — mentions that still *look*
     * like mentions and quietly notify nobody, which is the exact failure this
     * file exists to prevent. Post and comment **titles** are not scanned,
     * matching the position above: nothing renders a mention in a title.
     *
     * Private messages are rewritten like everything else, and it is worth being
     * explicit that this is not a privacy leak by another name: nothing is *read*
     * out of them here — the body goes to [renameMentions] and comes back, and the
     * only thing that crosses is a count in a log line. A message that mentions
     * nobody by the old name is written back untouched, which for almost all of
     * them means not written at all.
     *
     * @param userId the account being renamed, excluded from the "does anybody
     *   else still hold this name" check — by the time this runs the rename has
     *   already been written, but excluding them explicitly means the answer does
     *   not depend on that ordering.
     * @param from the resolved display name before the change.
     * @param to the resolved display name after it.
     */
    suspend fun rename(userId: Long, from: String, to: String) {
        if (from.isBlank() || to.isBlank() || from.equals(to, ignoreCase = true)) return

        val stillTaken = users.selectAll()
            .any { it.id != userId && it.resolvedName.equals(from, ignoreCase = true) }
        if (stillTaken) {
            logger.info("Mentions: not rewriting \"$from\"; another account still answers to it")
            return
        }

        var rewritten = 0
        issues.withPossibleMentions().forEach { (id, description) ->
            val next = renameMentions(description, from, to)
            if (next != description) {
                issues.setDescription(id, next)
                rewritten++
            }
        }
        comments.withPossibleMentions().forEach { (id, body) ->
            val next = renameMentions(body, from, to)
            if (next != body) {
                comments.update(id, next)
                rewritten++
            }
        }
        forumPosts.withPossibleMentions().forEach { (id, body) ->
            val next = renameMentions(body, from, to)
            if (next != body) {
                forumPosts.updateBody(id, next)
                rewritten++
            }
        }
        forumComments.withPossibleMentions().forEach { (id, body) ->
            val next = renameMentions(body, from, to)
            if (next != body) {
                forumComments.updateBody(id, next)
                rewritten++
            }
        }
        messages.withPossibleMentions().forEach { (id, body) ->
            val next = renameMentions(body, from, to)
            if (next != body) {
                messages.updateBody(id, next)
                rewritten++
            }
        }
        if (rewritten > 0) {
            logger.info("Mentions: rewrote $rewritten reference(s) from \"$from\" to \"$to\"")
        }
    }
}
