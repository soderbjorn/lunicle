/**
 * The API access section: personal access tokens for the REST API (LNL-222).
 *
 * The sibling of [ConnectionsBackingViewModel], and deliberately shaped like it — a
 * switch, the address to point things at, and a list with a Revoke on every row — so
 * the two halves of "what can act as me" read as one kind of thing. Where it differs is
 * the one thing a token has that an agent connection does not: **it is made here**, and
 * its value is shown exactly once. That moment is [State.createdToken], and the view
 * keeps it on screen until the person says they have copied it.
 *
 * All the logic, one immutable [State] over a [StateFlow], no platform in sight — the
 * project convention.
 *
 * @see se.soderbjorn.lunicle.clientserver.ApiAccessState
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
import se.soderbjorn.lunicle.client.userMessage
import se.soderbjorn.lunicle.clientserver.API_TOKEN_EXPIRY_CHOICES
import se.soderbjorn.lunicle.clientserver.API_TOKEN_NAME_MAX_LENGTH
import se.soderbjorn.lunicle.clientserver.ApiAccessState
import se.soderbjorn.lunicle.clientserver.ApiTokenScope
import se.soderbjorn.lunicle.clientserver.CreateApiTokenRequest

/** The section's heading. */
const val API_ACCESS_TITLE: String = "API access"

/** The switch's label. */
const val API_ENABLE_LABEL: String = "Let your scripts and apps use the API"

/**
 * The line under the switch. The second sentence is the security model, as it is for
 * agents: a token is you, through every rule that already applies to you.
 */
const val API_ENABLE_EXPLANATION: String =
    "Make a personal access token and other apps can read and change Lunicle through the REST " +
        "API. A token can do exactly what you can — nothing more — and a read-only one can only look."

/** What the person is told the one time a token is on screen. */
const val API_TOKEN_SHOWN_ONCE: String =
    "Copy this token now. It will not be shown again — Lunicle keeps only a fingerprint of it."

/** How one lifetime choice reads in the menu. */
fun expiryLabel(days: Int?): String = when (days) {
    null -> "Never expires"
    365 -> "1 year"
    else -> "$days days"
}

/** How a scope reads in the menu and on a row. */
fun scopeLabel(scope: ApiTokenScope): String = when (scope) {
    ApiTokenScope.READ -> "Read-only"
    ApiTokenScope.WRITE -> "Read and write"
}

/**
 * Owns the API access round-trips.
 *
 * @param storage the client's repository; the only collaborator.
 * @param now the clock the relative dates are measured against; injectable for tests.
 */
class ApiAccessBackingViewModel(
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = { currentTimeMillis() },
) {
    private val _stateFlow = MutableStateFlow(State())

    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * One token, already turned into the strings the view draws.
     *
     * @property name what the person called it. Their own text, but rendered as text
     *   all the same.
     * @property detail "Read-only · lnl_pat_3f9a1c… · made 3 days ago · last used 2
     *   minutes ago · expires in 87 days".
     * @property isExpired whether it has run out — still listed, so the person sees why
     *   their integration stopped, and dimmed.
     */
    data class Token(
        val id: Long,
        val name: String,
        val detail: String,
        val isExpired: Boolean,
    )

    /**
     * @property isLoaded whether the first fetch has returned; nothing renders before.
     * @property draftName / [draftScope] / [draftExpiryDays] the form for a new token.
     *   Kept here rather than read off the inputs, so the Create button's enabled state
     *   is a decision this class makes.
     * @property createdToken the raw value of the token just made, or null. The only
     *   time the client ever holds one; cleared by [onCreatedTokenDismissed].
     * @property createdTokenName the name of that token, so the panel can say which one.
     */
    data class State(
        val isLoaded: Boolean = false,
        val isBusy: Boolean = false,
        val isAllowed: Boolean = false,
        val isEnabled: Boolean = false,
        val baseUrl: String = "",
        val docsUrl: String = "",
        val tokens: List<Token> = emptyList(),
        val draftName: String = "",
        val draftScope: ApiTokenScope = ApiTokenScope.READ,
        val draftExpiryDays: Int? = API_TOKEN_EXPIRY_CHOICES.first(),
        val createdToken: String? = null,
        val createdTokenName: String? = null,
        val errorMessage: String? = null,
    ) {
        /** Whether the form may be submitted. */
        val canCreate: Boolean
            get() = isLoaded && isAllowed && !isBusy && draftName.isNotBlank() &&
                draftName.trim().length <= API_TOKEN_NAME_MAX_LENGTH

        /**
         * Whether to show the address, the form and the list. Only once permitted:
         * there is nothing to make while the tier may not, and the switch says why.
         */
        val isSetupVisible: Boolean get() = isLoaded && isAllowed

        /** A ready-to-paste request, so the first thing a person tries is one that works. */
        val curlExample: String
            get() = "curl -H \"Authorization: Bearer ${createdToken ?: "<your token>"}\" $baseUrl/projects"

        /** A token made while the switch is off works nowhere yet; say so beside it. */
        val isCreatedTokenDormant: Boolean get() = createdToken != null && !isEnabled
    }

    private var started = false

    /** Fetch the section. Idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch { refresh() }
    }

    fun onEnabledToggled(isEnabled: Boolean) {
        if (_stateFlow.value.isBusy) return
        run("Could not change that setting.") { storage.setApiEnabled(isEnabled) }
    }

    fun onDraftNameChanged(name: String) {
        _stateFlow.value = _stateFlow.value.copy(draftName = name)
    }

    fun onDraftScopeChanged(scope: ApiTokenScope) {
        _stateFlow.value = _stateFlow.value.copy(draftScope = scope)
    }

    fun onDraftExpiryChanged(days: Int?) {
        if (days !in API_TOKEN_EXPIRY_CHOICES) return
        _stateFlow.value = _stateFlow.value.copy(draftExpiryDays = days)
    }

    /** Make the token. On success the form clears and the token is shown, once. */
    fun onCreateTapped() {
        val state = _stateFlow.value
        if (!state.canCreate) return
        val name = state.draftName.trim()
        _stateFlow.value = state.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching {
                storage.createApiToken(CreateApiTokenRequest(name, state.draftScope, state.draftExpiryDays))
            }
            _stateFlow.value = result.fold(
                onSuccess = { created ->
                    created.state.applyTo(_stateFlow.value).copy(
                        isBusy = false,
                        draftName = "",
                        createdToken = created.token,
                        createdTokenName = name,
                    )
                },
                onFailure = { t ->
                    _stateFlow.value.copy(isBusy = false, errorMessage = t.userMessage("Could not make that token."))
                },
            )
        }
    }

    /** The person has copied it (or chosen not to). It is gone from the client for good. */
    fun onCreatedTokenDismissed() {
        _stateFlow.value = _stateFlow.value.copy(createdToken = null, createdTokenName = null)
    }

    /**
     * Revoke one token. No confirmation, for [ConnectionsBackingViewModel.onRevokeTapped]'s
     * reason: the row names it, the button is on the row, and the recovery is a new token.
     */
    fun onRevokeTapped(id: Long) {
        if (_stateFlow.value.isBusy) return
        run("Could not revoke that token.") { storage.revokeApiToken(id) }
    }

    private fun run(failure: String, call: suspend () -> ApiAccessState) {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        scope.launch {
            val result = runCatching { call() }
            _stateFlow.value = result.fold(
                onSuccess = { it.applyTo(_stateFlow.value).copy(isBusy = false) },
                onFailure = { t -> _stateFlow.value.copy(isBusy = false, errorMessage = t.userMessage(failure)) },
            )
        }
    }

    private suspend fun refresh() {
        _stateFlow.value = _stateFlow.value.copy(isBusy = true, errorMessage = null)
        val result = runCatching { storage.apiAccessState() }
        _stateFlow.value = result.fold(
            onSuccess = { it.applyTo(_stateFlow.value).copy(isBusy = false) },
            onFailure = { _stateFlow.value.copy(isBusy = false, errorMessage = "Could not load your API tokens.") },
        )
    }

    /** Replace, never merge — every route returns the whole section. */
    private fun ApiAccessState.applyTo(previous: State): State {
        val timestamp = now()
        return previous.copy(
            isLoaded = true,
            errorMessage = null,
            isAllowed = isAllowed,
            isEnabled = isEnabled,
            baseUrl = baseUrl,
            docsUrl = docsUrl,
            tokens = tokens.map { token ->
                val expired = token.expiresAt?.let { it <= timestamp } == true
                Token(
                    id = token.id,
                    name = token.name,
                    isExpired = expired,
                    detail = buildList {
                        add(scopeLabel(token.scope))
                        add("${token.prefix}…")
                        add("made ${formatRelative(token.createdAt, timestamp)}")
                        add(token.lastUsedAt?.let { "last used ${formatRelative(it, timestamp)}" } ?: "never used")
                        val expiresAt = token.expiresAt
                        add(
                            when {
                                expiresAt == null -> "never expires"
                                expired -> "expired"
                                else -> "expires in ${formatRemaining(expiresAt - timestamp)}"
                            },
                        )
                    }.joinToString(" · "),
                )
            },
        )
    }
}

/**
 * "90 days", "1 day", "5 hours" — how long a token has left.
 *
 * Days round **up**: a 90-day token made a minute ago has 89 days and 23 hours left,
 * and "expires in 89 days" beside the "90 days" its maker just chose reads as a bug.
 * Hours, below a day, round down — the last hours are when being early matters.
 */
internal fun formatRemaining(millis: Long): String {
    val hourMillis = 60L * 60 * 1000
    val hours = millis / hourMillis
    val days = (millis + 24 * hourMillis - 1) / (24 * hourMillis)
    return when {
        hours >= 24 -> if (days == 1L) "1 day" else "$days days"
        hours >= 1 -> if (hours == 1L) "1 hour" else "$hours hours"
        else -> "less than an hour"
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun currentTimeMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
