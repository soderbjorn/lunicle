/**
 * The Connections section: agent access, and what is currently connected.
 *
 * ── Why the section is called "Connections" and not "MCP" ───────────────────
 *
 * Because the toggle is the least interesting thing in it after the first day.
 * What a user comes back for is the list of what is connected and the ability to
 * cut it off, so the section is named for that. "MCP" also means nothing to most
 * people, which is why [ENABLE_EXPLANATION] says what the feature *is* in plain
 * words rather than naming the protocol.
 *
 * ── What this view model is honest about ───────────────────────────────────
 *
 * The toggle is a real kill switch — the server re-reads it on every request, so
 * switching it off stops live agents — but it is **not** the security boundary.
 * The consent page is. This view model can therefore be wrong about nothing that
 * matters: every string here is a report, and every action is a request the server
 * decides.
 *
 * All the logic, one immutable [State] over a [StateFlow], no platform in sight —
 * the project convention. An iOS client would render this same state.
 *
 * @see se.soderbjorn.lunicle.clientserver.McpState
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.client.formatTimestamp
import se.soderbjorn.lunicle.client.userMessage
import se.soderbjorn.lunicle.clientserver.McpState

/** The section's heading. */
const val CONNECTIONS_TITLE: String = "Connections"

/** The toggle's label. Plain words: "MCP" means nothing to most people. */
const val ENABLE_LABEL: String = "Let AI agents act on your behalf"

/**
 * The one line under the toggle.
 *
 * Says what the feature is and, in the same breath, what its limit is. The second
 * sentence is the one that matters — "it can do exactly what you can, nothing
 * more" is the whole security model in eight words, and it is true rather than
 * reassuring: the token resolves to the user, and every rule is re-derived from
 * them per request.
 */
const val ENABLE_EXPLANATION: String =
    "Connect Claude Code, Claude Cowork, or another AI agent to Lunicle. " +
        "It will be able to do exactly what you can — nothing more."

/**
 * Turn an epoch-millis timestamp into "2 minutes ago" and friends.
 *
 * Relative rather than absolute, and only here: the Connections list is scanned
 * to answer "is this still in use?", and "17 Jul 2026, 14:32" does not answer
 * that without arithmetic. That is the opposite of the rule [formatTimestamp]
 * encodes for issue timestamps, which are compared with each other and so must be
 * a fixed shape — different questions, different formats, and both decided here
 * rather than in a view.
 *
 * Falls back to the absolute date past a week, where "23 days ago" has stopped
 * being easier to read than the date itself.
 */
internal fun formatRelative(millis: Long, now: Long): String {
    val elapsed = now - millis
    // A clock that disagrees with the server's by a few seconds would otherwise
    // produce "in -3 seconds". The server's timestamps are authoritative and the
    // browser's clock is not, so anything in the future is simply "just now".
    if (elapsed < 0) return "just now"
    val seconds = elapsed / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "just now"
        minutes < 60 -> "$minutes ${plural(minutes, "minute")} ago"
        hours < 24 -> "$hours ${plural(hours, "hour")} ago"
        days <= 7 -> "$days ${plural(days, "day")} ago"
        else -> formatTimestamp(millis)
    }
}

private fun plural(count: Long, word: String): String = if (count == 1L) word else "${word}s"

/**
 * Owns the Connections round-trips.
 *
 * @param storage the client's repository; the only collaborator, so this never
 *   mentions HTTP.
 * @param now supplies the clock the relative timestamps are measured against.
 *   Injectable so a test can pin it — the alternative is a test that passes for
 *   fifty-nine seconds.
 */
class ConnectionsBackingViewModel(
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = { currentTimeMillis() },
) {
    private val _stateFlow = MutableStateFlow(State())

    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * One connected agent, already turned into the two strings the view draws.
     *
     * @property name what the agent calls itself. **Self-reported.** The view must
     *   render it as text and never as markup — anyone can register a client named
     *   "Claude Code", and this is the one string in this state that a stranger
     *   chose. It is deliberately not the only thing shown; [detail] carries the
     *   dates, which is what actually tells a user whether they recognise it.
     * @property detail "connected 3 days ago · last used 2 minutes ago".
     */
    data class Connection(
        val clientId: String,
        val name: String,
        val detail: String,
    )

    /**
     * @property isLoaded whether the first fetch has returned. Before it has, the
     *   section renders nothing — flashing an unchecked toggle at somebody who has
     *   agents connected would be a lie, briefly, about something they care about.
     * @property serverUrl the absolute `/mcp` URL, from the server. Never built
     *   here; see McpState.serverUrl.
     * @property pendingDisableConfirmation whether the "turning this off will stop
     *   N agents" confirmation is up. A decision, so it lives here — the view only
     *   draws it.
     */
    data class State(
        val isLoaded: Boolean = false,
        val isBusy: Boolean = false,
        val isEnabled: Boolean = false,
        val serverUrl: String = "",
        val connections: List<Connection> = emptyList(),
        val errorMessage: String? = null,
        val pendingDisableConfirmation: Boolean = false,
    ) {
        /** Whether to reveal the URL and the command. Nothing to copy while it is off. */
        val isSetupVisible: Boolean get() = isEnabled

        /** State C — on, with connections. The list is the point of the screen. */
        val hasConnections: Boolean get() = connections.isNotEmpty()

        /**
         * The ready-to-paste command.
         *
         * Built from [serverUrl] rather than hardcoded, so a local run copies its
         * own localhost URL and a deploy copies the deployed one. Nobody types
         * these correctly, which is why it exists at all.
         */
        val claudeCodeCommand: String
            get() = "claude mcp add --transport http lunicle $serverUrl"

        /** What the confirmation asks, naming the cost. */
        val disableConfirmationMessage: String
            get() = "${connections.size} connected ${plural(connections.size.toLong(), "agent")} " +
                "will stop working. ${if (connections.size == 1) "It" else "They"}'ll work again " +
                "if you turn this back on."
    }

    private var started = false

    /** Fetch the section. Idempotent. Called when the dialog opens. */
    fun start() {
        if (started) return
        started = true
        scope.launch { refresh() }
    }

    /**
     * The toggle was clicked.
     *
     * Turning it **on** is immediate — there is nothing to lose. Turning it
     * **off** while agents are connected asks first, because the cost is invisible
     * otherwise: the agents stop, and the person who flipped it finds out when
     * something they forgot they had wired up stops answering. With nothing
     * connected there is nothing to warn about, so it just happens.
     */
    fun onEnabledToggled(isEnabled: Boolean) {
        val state = _stateFlow.value
        if (state.isBusy) return
        if (!isEnabled && state.hasConnections) {
            _stateFlow.value = state.copy(pendingDisableConfirmation = true)
            return
        }
        setEnabled(isEnabled)
    }

    /** The confirmation was accepted. */
    fun onDisableConfirmed() {
        _stateFlow.value = _stateFlow.value.copy(pendingDisableConfirmation = false)
        setEnabled(false)
    }

    /**
     * The confirmation was dismissed.
     *
     * The toggle goes back to on, which it never actually left: nothing was sent,
     * so the server still says enabled. The view re-renders from [State.isEnabled]
     * and the checkbox the user clicked snaps back — which is the honest picture,
     * since cancelling means it did not happen.
     */
    fun onDisableCancelled() {
        _stateFlow.value = _stateFlow.value.copy(pendingDisableConfirmation = false)
    }

    private fun setEnabled(isEnabled: Boolean) {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.setMcpEnabled(isEnabled) }
            _stateFlow.value = result.fold(
                onSuccess = { it.applyTo(_stateFlow.value).copy(isBusy = false) },
                onFailure = { t ->
                    _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not change that setting."),
                    )
                },
            )
        }
    }

    /**
     * Disconnect one agent.
     *
     * No confirmation, deliberately — unlike the toggle. Revoke is immediate and
     * irreversible, but it is also *unambiguous*: the row names the agent, the
     * button is on that row, and the recovery is to reconnect it. The toggle's
     * confirmation exists because its cost is invisible and affects things not
     * named on screen; this one's is neither.
     */
    fun onRevokeTapped(clientId: String) {
        if (_stateFlow.value.isBusy) return
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { storage.revokeMcpConnection(clientId) }
            _stateFlow.value = result.fold(
                onSuccess = { it.applyTo(_stateFlow.value).copy(isBusy = false) },
                onFailure = { t ->
                    _stateFlow.value.copy(
                        isBusy = false,
                        errorMessage = t.userMessage("Could not disconnect that agent."),
                    )
                },
            )
        }
    }

    private suspend fun refresh() {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        val result = runCatching { storage.mcpState() }
        _stateFlow.value = result.fold(
            onSuccess = { it.applyTo(_stateFlow.value).copy(isBusy = false) },
            onFailure = {
                // isLoaded stays false, so the section keeps showing nothing
                // rather than an unchecked toggle it has no basis for.
                _stateFlow.value.copy(isBusy = false, errorMessage = "Could not load your connections.")
            },
        )
    }

    /**
     * Fold a server [McpState] into the view state.
     *
     * Every route returns the whole [McpState] — the toggle, the URL and the list
     * — so this replaces rather than merges, and a client that has drifted from
     * the server is corrected by any of the three calls. See McpState's docs.
     */
    private fun McpState.applyTo(previous: State): State {
        val timestamp = now()
        return previous.copy(
            isLoaded = true,
            errorMessage = null,
            isEnabled = isEnabled,
            serverUrl = serverUrl,
            connections = connections.map {
                Connection(
                    clientId = it.clientId,
                    name = it.clientName,
                    detail = buildString {
                        append("connected ${formatRelative(it.connectedAt, timestamp)}")
                        // "never used" rather than omitting the half: an agent
                        // connected in July that has never called is exactly the
                        // row a user should revoke, and silence would hide it.
                        append(" · ")
                        append(
                            it.lastUsedAt?.let { used -> "last used ${formatRelative(used, timestamp)}" }
                                ?: "never used",
                        )
                    },
                )
            },
        )
    }
}

/**
 * The current time, in common code.
 *
 * `kotlin.time.Clock` rather than a platform call, so this file stays free of
 * `Date.now()` on JS and `System.currentTimeMillis()` on the JVM — the whole
 * point of the state living here is that an iOS client reuses it.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
private fun currentTimeMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
