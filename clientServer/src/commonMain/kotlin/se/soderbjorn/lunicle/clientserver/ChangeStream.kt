/**
 * The change stream's wire shapes, as a client reads them (LNL-224, LNL-225).
 *
 * The server sends Server-Sent Events: an `id:`, an `event:` kind and one JSON
 * `data:` line, described in full by the server's `ChangeStreamRoutes`. This file is
 * the client's half — the kinds by name, the data line's shape, and the two things a
 * client sends: the origin header on its writes and the stream URL.
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * The header a client names itself in on every write, so the change stream can say
 * which events it caused (`self: true`) rather than the client guessing from timing.
 */
const val CHANGE_ORIGIN_HEADER: String = "X-Lunicle-Origin"

/** The event kinds, as the `event:` field spells them. */
object ChangeStreamKinds {
    const val ISSUE_CREATED: String = "issue.created"
    const val ISSUE_UPDATED: String = "issue.updated"
    const val ISSUE_MOVED: String = "issue.moved"
    const val ISSUE_DELETED: String = "issue.deleted"
    const val COMMENT_ADDED: String = "comment.added"
    const val COMMENT_EDITED: String = "comment.edited"
    const val COMMENT_DELETED: String = "comment.deleted"
    const val BOARD_CHANGED: String = "board.changed"
    const val NOTIFICATION_CHANGED: String = "notification.changed"

    /** Not a change: "you missed something I can no longer replay — re-read". */
    const val RESET: String = "reset"
}

/**
 * One event's `data:` line. Every field is optional because which ones ride depends
 * on the kind — see the server's description.
 *
 * @property self this client caused it: the write carried this client's
 *   [CHANGE_ORIGIN_HEADER] and the stream was opened with the same origin.
 */
@Serializable
data class ChangeStreamData(
    val projectId: Long? = null,
    val issueId: Long? = null,
    val commentId: Long? = null,
    val updatedAt: Long? = null,
    val actor: String? = null,
    val self: Boolean = false,
)

/** One parsed event. */
data class ChangeStreamEvent(
    val id: Long?,
    val kind: String,
    val data: ChangeStreamData,
)

private val changeStreamJson = Json { ignoreUnknownKeys = true }

/**
 * Parse one event as the transport hands it over, or null for a data line that is
 * not the JSON object it should be — a newer server's event this build cannot read is
 * skipped, not fatal.
 */
fun parseChangeStreamEvent(id: String?, kind: String, data: String): ChangeStreamEvent? {
    val parsed = runCatching {
        if (data.isBlank()) ChangeStreamData() else changeStreamJson.decodeFromString(ChangeStreamData.serializer(), data)
    }.getOrNull() ?: return null
    return ChangeStreamEvent(id?.trim()?.toLongOrNull(), kind, parsed)
}

/**
 * This page's own name, sent as [CHANGE_ORIGIN_HEADER] on every request and as the
 * stream's `origin`. One per page load — per tab — which is the unit the ticket asks
 * about: the same person in two tabs is two origins, and each sees the other's edits.
 */
object ClientOrigin {
    val id: String = buildString {
        repeat(16) { append("0123456789abcdef"[Random.nextInt(16)]) }
    }
}

/**
 * The combined stream's URL: these projects, this client's origin, and where to
 * resume from. The project ids are sorted so one set is always one URL.
 */
fun changeStreamUrl(projectIds: Collection<Long>, origin: String, lastEventId: Long?): String = buildString {
    append("/api/v1/events?projects=")
    append(projectIds.sorted().joinToString(","))
    append("&origin=").append(origin)
    if (lastEventId != null) append("&last_event_id=").append(lastEventId)
}
