/**
 * Keeping the app live: the one owner of the change stream (LNL-225).
 *
 * ── One stream, fanned out ───────────────────────────────────────────────────
 *
 * The page holds at most one connection — the server's combined `/api/v1/events`,
 * for every project with a board pane open plus this person's notifications —
 * however many boards, issue windows and bells are on screen. This class owns it and
 * turns what arrives into [LiveSignal]s; the bootstrap routes each to the view model
 * that cares (the board, an open issue window, the bell). One connection rather than
 * one per board because a browser on HTTP/1.1 allows six per host, and every board
 * pane holding one would starve the app's own requests.
 *
 * ── What it deliberately ignores ─────────────────────────────────────────────
 *
 * An event this tab caused (`self`) is dropped on arrival. The write that caused it
 * already refreshed whatever it touched (each window's `onWritten`), so echoing it
 * would be a second fetch at best and, on an issue window, a "changed by you" note
 * about your own save at worst.
 *
 * ── Coalesced ────────────────────────────────────────────────────────────────
 *
 * One save writes the issue and then its labels, and a drag rewrites a column: the
 * server announces each. They are gathered for [debounceMillis] and handed on as one
 * signal per board and one per issue, so a burst is one re-read rather than five.
 *
 * ── When it is not connected ─────────────────────────────────────────────────
 *
 * Signed out, a hidden tab (the stream closes, and on return it resumes from the last
 * event it saw — replayed, or `reset` if that is too long ago), or a stream the server
 * refused (an older proxy, the per-session limit): [State.isConnected] is false and
 * the app behaves as it did before any of this — nothing polls but the bell, which
 * reads this flag to decide whether it still has to. A refused stream is retried with
 * a backoff.
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.soderbjorn.lunicle.clientserver.ChangeStreamKinds
import se.soderbjorn.lunicle.clientserver.ClientOrigin
import se.soderbjorn.lunicle.clientserver.changeStreamUrl
import se.soderbjorn.lunicle.clientserver.parseChangeStreamEvent

/**
 * The platform's way of holding an SSE connection — the browser's `EventSource` on
 * the web. An interface so the logic here can be tested without one.
 */
interface ChangeStreamTransport {
    /** Open [url]; events and state changes arrive through [handler] until [ChangeStreamConnection.close]. */
    fun open(url: String, handler: ChangeStreamHandler): ChangeStreamConnection
}

/** What a [ChangeStreamTransport] reports. All calls arrive on the transport's own thread. */
interface ChangeStreamHandler {
    /** The stream is open and listening. */
    fun onOpen()

    /** One event: its `id:`, `event:` kind and raw `data:` line. */
    fun onEvent(id: String?, kind: String, data: String)

    /** The connection dropped and the transport is retrying it by itself. */
    fun onInterrupted()

    /** The connection is gone and the transport will not retry — refused, or failed for good. */
    fun onClosed()
}

/** A held connection. */
fun interface ChangeStreamConnection {
    fun close()
}

/** What the stream means for the rest of the app, after coalescing. */
sealed interface LiveSignal {
    /**
     * Something on this project's board changed.
     *
     * @property issueIds the issues somebody else changed, for the brief highlight —
     *   empty when the change was to the board as a whole (its columns or sprints).
     */
    data class BoardChanged(val projectId: Long, val issueIds: Set<Long>) : LiveSignal

    /**
     * Somebody else changed this issue — or deleted it, when [deleted].
     *
     * @property actor who, as the server names them, or null if it does not say.
     */
    data class IssueChanged(val issueId: Long, val actor: String?, val deleted: Boolean) : LiveSignal

    /** This person's notifications changed. */
    data object NotificationsChanged : LiveSignal

    /**
     * Events were missed that cannot be replayed: re-read everything that is open.
     */
    data object Resync : LiveSignal
}

/**
 * Owns the change stream for this page.
 *
 * @param transport the platform connection, or null where there is no server to
 *   listen to (demo mode) — then this never connects and nothing changes.
 * @param origin this tab's name; see [ClientOrigin].
 * @param retryDelaysMillis how long to wait before each retry of a refused stream,
 *   the last repeated.
 */
class LiveChangesBackingViewModel(
    private val transport: ChangeStreamTransport?,
    private val scope: CoroutineScope,
    private val origin: String = ClientOrigin.id,
    private val debounceMillis: Long = 250L,
    private val retryDelaysMillis: List<Long> = listOf(5_000L, 15_000L, 60_000L, 300_000L),
) {
    /** @property isConnected whether events are arriving right now. */
    data class State(val isConnected: Boolean = false)

    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    private val _signals = MutableSharedFlow<LiveSignal>(extraBufferCapacity = 256)

    /** The signals, coalesced. Nothing is replayed to a late collector. */
    val signals: SharedFlow<LiveSignal> = _signals.asSharedFlow()

    private var projectIds: Set<Long> = emptySet()
    private var isSignedIn = false
    private var isVisible = true

    private var connection: ChangeStreamConnection? = null
    /** Which URL [connection] was opened for, so an unchanged wish does not reconnect. */
    private var connectedUrlKey: String? = null
    /** Bumped on every (re)connect, so a late callback from a closed connection is ignored. */
    private var generation = 0
    private var lastEventId: Long? = null
    private var retryJob: Job? = null
    private var failures = 0

    // ── Coalescing ───────────────────────────────────────────────────────────
    private val pendingBoards = mutableMapOf<Long, MutableSet<Long>>()
    private val pendingIssues = linkedMapOf<Long, LiveSignal.IssueChanged>()
    private var pendingNotifications = false
    private var flushJob: Job? = null

    /** The workspace's open boards. */
    fun onProjectsChanged(ids: Set<Long>) {
        if (ids == projectIds) return
        projectIds = ids
        reconcile()
    }

    /**
     * Who is signed in changed — including signing out, and including one person
     * becoming another. A fresh stream: the old one's events belong to whoever was
     * asking before, and the app re-reads everything on an identity change anyway.
     */
    fun onSessionChanged(signedIn: Boolean) {
        isSignedIn = signedIn
        lastEventId = null
        disconnect()
        reconcile()
    }

    /** The tab was hidden or shown. A hidden tab holds no connection. */
    fun onVisibilityChanged(visible: Boolean) {
        if (visible == isVisible) return
        isVisible = visible
        reconcile()
    }

    /** Connect, reconnect or disconnect so the connection matches what is wanted. */
    private fun reconcile() {
        val wanted = transport != null && isSignedIn && isVisible
        if (!wanted) {
            retryJob?.cancel()
            disconnect()
            return
        }
        val key = projectIds.sorted().joinToString(",")
        if (connection != null && key == connectedUrlKey) return
        if (retryJob?.isActive == true && connection == null) return
        connect(key)
    }

    private fun connect(key: String) {
        disconnect()
        val myGeneration = ++generation
        connectedUrlKey = key
        val url = changeStreamUrl(projectIds, origin, lastEventId)
        connection = transport?.open(
            url,
            object : ChangeStreamHandler {
                override fun onOpen() {
                    if (myGeneration != generation) return
                    failures = 0
                    _stateFlow.value = State(isConnected = true)
                }

                override fun onEvent(id: String?, kind: String, data: String) {
                    if (myGeneration != generation) return
                    handle(id, kind, data)
                }

                override fun onInterrupted() {
                    if (myGeneration != generation) return
                    _stateFlow.value = State(isConnected = false)
                }

                override fun onClosed() {
                    if (myGeneration != generation) return
                    connection = null
                    connectedUrlKey = null
                    _stateFlow.value = State(isConnected = false)
                    scheduleRetry()
                }
            },
        )
    }

    private fun disconnect() {
        generation++
        connection?.close()
        connection = null
        connectedUrlKey = null
        if (_stateFlow.value.isConnected) _stateFlow.value = State(isConnected = false)
    }

    private fun scheduleRetry() {
        val wait = retryDelaysMillis[minOf(failures, retryDelaysMillis.lastIndex)]
        failures++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            retryJob = null
            reconcile()
        }
    }

    /** One event off the wire. Public for the tests; the transport is the real caller. */
    internal fun handle(id: String?, kind: String, data: String) {
        val event = parseChangeStreamEvent(id, kind, data) ?: return
        event.id?.let { lastEventId = it }
        if (event.kind == ChangeStreamKinds.RESET) {
            // Everything queued is about to be re-read anyway.
            pendingBoards.clear()
            pendingIssues.clear()
            pendingNotifications = false
            _signals.tryEmit(LiveSignal.Resync)
            return
        }
        if (event.data.self) return
        val projectId = event.data.projectId
        val issueId = event.data.issueId
        when (event.kind) {
            ChangeStreamKinds.NOTIFICATION_CHANGED -> pendingNotifications = true
            ChangeStreamKinds.BOARD_CHANGED -> if (projectId != null) pendingBoards.getOrPut(projectId) { mutableSetOf() }
            else -> {
                if (projectId == null || issueId == null) return
                pendingBoards.getOrPut(projectId) { mutableSetOf() } += issueId
                val deleted = event.kind == ChangeStreamKinds.ISSUE_DELETED
                // A delete outranks an update that arrived before it in the same burst.
                val previous = pendingIssues[issueId]
                pendingIssues[issueId] = LiveSignal.IssueChanged(
                    issueId = issueId,
                    actor = event.data.actor ?: previous?.actor,
                    deleted = deleted || previous?.deleted == true,
                )
            }
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(debounceMillis)
            flush()
        }
    }

    /** Hand on whatever is queued now, without waiting out the debounce. For the tests. */
    internal fun flushNow() {
        flushJob?.cancel()
        flush()
    }

    private fun flush() {
        pendingBoards.forEach { (projectId, ids) -> _signals.tryEmit(LiveSignal.BoardChanged(projectId, ids.toSet())) }
        pendingIssues.values.forEach { _signals.tryEmit(it) }
        if (pendingNotifications) _signals.tryEmit(LiveSignal.NotificationsChanged)
        pendingBoards.clear()
        pendingIssues.clear()
        pendingNotifications = false
    }
}
