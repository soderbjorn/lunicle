/**
 * The browser's half of the change stream (LNL-225): an `EventSource`.
 *
 * Everything that decides anything lives in `LiveChangesBackingViewModel`; this only
 * holds the connection and reports what happens on it. `EventSource` reconnects by
 * itself after a dropped connection and sends `Last-Event-ID` when it does, which is
 * the resume the server's replay buffer is for — so a dropped connection is
 * [ChangeStreamHandler.onInterrupted], and only one the browser has given up on (a
 * refusal: 401, 429, a proxy that does not stream) is [ChangeStreamHandler.onClosed].
 */
package se.soderbjorn.lunicle

import org.w3c.dom.EventSource
import org.w3c.dom.MessageEvent
import se.soderbjorn.lunicle.client.viewmodel.ChangeStreamConnection
import se.soderbjorn.lunicle.client.viewmodel.ChangeStreamHandler
import se.soderbjorn.lunicle.client.viewmodel.ChangeStreamTransport
import se.soderbjorn.lunicle.clientserver.ChangeStreamKinds

/** Every `event:` kind there is a listener for. A kind not listed is never seen. */
private val STREAM_KINDS = listOf(
    ChangeStreamKinds.ISSUE_CREATED,
    ChangeStreamKinds.ISSUE_UPDATED,
    ChangeStreamKinds.ISSUE_MOVED,
    ChangeStreamKinds.ISSUE_DELETED,
    ChangeStreamKinds.COMMENT_ADDED,
    ChangeStreamKinds.COMMENT_EDITED,
    ChangeStreamKinds.COMMENT_DELETED,
    ChangeStreamKinds.BOARD_CHANGED,
    ChangeStreamKinds.NOTIFICATION_CHANGED,
    ChangeStreamKinds.RESET,
)

/** [ChangeStreamTransport] over the browser's `EventSource`. Same-origin, so the session cookie rides along. */
class EventSourceTransport : ChangeStreamTransport {
    override fun open(url: String, handler: ChangeStreamHandler): ChangeStreamConnection {
        val source = EventSource(url)
        source.onopen = { handler.onOpen() }
        source.onerror = {
            if (source.readyState == EventSource.CLOSED) handler.onClosed() else handler.onInterrupted()
        }
        STREAM_KINDS.forEach { kind ->
            source.addEventListener(kind, { event ->
                val message = event as MessageEvent
                handler.onEvent(message.lastEventId, kind, message.data as? String ?: "")
            })
        }
        return ChangeStreamConnection { source.close() }
    }
}
