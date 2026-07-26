/**
 * The in-app notification list (LNL-109) — the shared shape of a stored
 * notification and the two responses the bell and its sidebar read.
 *
 * ── Why this is a peer of the e-mail path, not a slice of it ─────────────────
 *
 * Every notification here has a twin e-mail (see EmailNotifier.kt): wherever the
 * server mails somebody about something, it also stores one of these so the same
 * news is reachable inside the app, from the alarm bell, without opening a mailbox.
 * The two are adapted differently on purpose — an e-mail may quote a whole message
 * or post body, a notification never does (it is a line and a destination), which
 * is why the wording lives in its own builders rather than being the e-mail's
 * subject reused.
 *
 * ── The destination is metadata, not a link ─────────────────────────────────
 *
 * A notification does not carry a URL. It carries the *ids* of where it points and
 * the client navigates there directly — switching tab and opening the window from
 * the same view-model entry points a board click uses — rather than round-tripping
 * through the `?issue=` / `?conversation=` / `?forum=` deep-link parameters. Which
 * of the id fields are populated is a function of [kind]; see each [NotificationKind]
 * value. Storing ids rather than a link also means a renamed project prefix or a
 * moved window never strands an old notification on a stale address.
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.Serializable

/**
 * What a notification is about — and, with it, which destination fields on
 * [NotificationSummary] are populated.
 *
 * One value per event site in EmailNotifier.kt that sends a notification e-mail.
 * The transactional/auth mails (sign-in codes, e-mail-change codes, the
 * address-changed notice) are deliberately absent: they are not notifications for a
 * signed-in in-app reader and store no row.
 *
 * Serialized by name, so the server persists the enum name as the `kind` column and
 * an older client bundle that has not heard of a newer value fails to parse only
 * that one row rather than the whole list — see [NotificationListState].
 */
enum class NotificationKind {
    /** A new issue was created in a project you watch. Destination: [NotificationSummary.projectId], [NotificationSummary.issueId]. */
    ISSUE_CREATED,

    /** An issue you watch changed. Destination: project + issue. */
    ISSUE_UPDATED,

    /** An issue was assigned to you. Destination: project + issue. */
    ISSUE_ASSIGNED,

    /** You were @mentioned on an issue. Destination: project + issue. */
    ISSUE_MENTIONED,

    /** Somebody sent you a private message. Destination: [NotificationSummary.conversationId] (+ [NotificationSummary.messageId]). */
    MESSAGE,

    /** A new post in a forum you watch. Destination: [NotificationSummary.forumId] + [NotificationSummary.postId] (+ project). */
    FORUM_POST,

    /** A new comment on a post you watch. Destination: forum + post (+ project). */
    FORUM_COMMENT,
}

/**
 * One stored notification, as the client sees it.
 *
 * No user ids and no addresses cross: [title] is the whole human-readable line,
 * with names already resolved server-side the way every other screen resolves them.
 * The destination ids are the app's own — the ids the client already routes by —
 * and are not secrets: a notification only ever names a place its owner may go.
 *
 * @property title the short line the row shows — "Grace assigned LNL-42 to you". It
 *   never contains a message or comment body; that is the difference from the twin
 *   e-mail, argued in EmailNotifier.kt.
 * @property createdAt epoch millis, for the relative time the row shows and the
 *   newest-first order the list arrives in.
 * @property isRead whether the owner has already seen it. Drives the unread emphasis
 *   and the bell's count; a click marks it read.
 */
@Serializable
data class NotificationSummary(
    val id: Long,
    val kind: NotificationKind,
    val title: String,
    val createdAt: Long,
    val isRead: Boolean = false,
    val projectId: Long? = null,
    val issueId: Long? = null,
    val conversationId: Long? = null,
    val messageId: Long? = null,
    val forumId: Long? = null,
    val postId: Long? = null,
)

/**
 * The whole notification list for the panel, plus the unread count for the bell.
 *
 * Fetched when the sidebar opens (and after an action taken inside it). The bell
 * itself does not poll this — that would be the whole list every five minutes — it
 * polls [NotificationCountState]. Every field defaults so an older cached bundle can
 * parse a newer server's response.
 *
 * @property items newest first. Empty for a signed-out caller, who owns none.
 * @property unreadCount how many of the owner's notifications are unread, in total —
 *   not just those in [items] — so the panel and the bell agree even if the list is
 *   ever capped.
 */
@Serializable
data class NotificationListState(
    val items: List<NotificationSummary> = emptyList(),
    val unreadCount: Int = 0,
)

/**
 * The bell's poll answer: just the unread count, nothing else.
 *
 * Its own tiny response rather than a field on [NotificationListState] because the
 * bell asks this every five minutes for every signed-in user, and the whole point
 * is that it is one indexed count — no list built, no bodies read. Signed-out is
 * zero, never a 401: the shell polls before it knows who is signed in.
 */
@Serializable
data class NotificationCountState(
    val unreadCount: Int = 0,
)
