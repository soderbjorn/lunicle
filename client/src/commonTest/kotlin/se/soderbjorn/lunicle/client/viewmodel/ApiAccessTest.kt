/**
 * The client half of personal access tokens (LNL-222): the API access section's view
 * model, and the history line that names a token.
 *
 * The claims worth pinning:
 *
 *  - **A token is held once and let go.** Creating one puts its value in the state and
 *    clears the form; dismissing it removes the value for good.
 *  - **The form refuses what the server would.** A blank name cannot be submitted.
 *  - **History says which token, and not as an agent.** A change made with a token is
 *    a sentence on the byline, never the agent badge — and two events that differ only
 *    in their token are not folded into one block.
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.clientserver.ApiAccessState
import se.soderbjorn.lunicle.clientserver.ApiTokenScope
import se.soderbjorn.lunicle.clientserver.ApiTokenView
import se.soderbjorn.lunicle.clientserver.CreateApiTokenRequest
import se.soderbjorn.lunicle.clientserver.CreatedApiToken
import se.soderbjorn.lunicle.clientserver.HttpLunicleApi
import se.soderbjorn.lunicle.clientserver.IssueEventKind
import se.soderbjorn.lunicle.clientserver.IssueEventView
import se.soderbjorn.lunicle.clientserver.LunicleApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW: Long = 1_000_000_000_000
private const val DAY: Long = 24L * 60 * 60 * 1000

/**
 * A server holding one person's tokens in a list. `by HttpLunicleApi(...)` supplies the
 * members this test never calls — see WorkspaceSettlesTest's fake for why that is safe.
 */
private class TokenServer : LunicleApi by HttpLunicleApi(baseUrl = "http://api-access.invalid") {
    val tokens = mutableListOf<ApiTokenView>()
    var requests = mutableListOf<CreateApiTokenRequest>()

    private fun state() = ApiAccessState(
        isAllowed = true,
        isEnabled = false,
        baseUrl = "https://lunicle.test/api/v1",
        docsUrl = "https://lunicle.test/api/v1/openapi.json",
        tokens = tokens.toList(),
    )

    override suspend fun apiAccessState(): ApiAccessState = state()

    override suspend fun createApiToken(request: CreateApiTokenRequest): CreatedApiToken {
        requests += request
        tokens += ApiTokenView(
            id = tokens.size + 1L,
            name = request.name,
            prefix = "lnl_pat_abc123",
            scope = request.scope,
            createdAt = NOW - 3 * DAY,
            expiresAt = request.expiresInDays?.let { NOW + it * DAY },
        )
        return CreatedApiToken(state(), token = "lnl_pat_abc123secret")
    }

    override suspend fun revokeApiToken(id: Long): ApiAccessState {
        tokens.removeAll { it.id == id }
        return state()
    }
}

class ApiAccessTest {
    private val server = TokenServer()

    /** Unconfined, so each round-trip has landed by the time the call returns. */
    private val viewModel = ApiAccessBackingViewModel(
        storage = StorageRepository(server),
        scope = CoroutineScope(Dispatchers.Unconfined),
        now = { NOW },
    )

    @Test
    fun `a blank name cannot be submitted`() {
        viewModel.start()
        viewModel.onDraftNameChanged("   ")
        assertFalse(viewModel.stateFlow.value.canCreate)
        viewModel.onCreateTapped()
        assertTrue(server.requests.isEmpty(), "A blank name reached the server.")
    }

    @Test
    fun `a new token is shown once, then let go`() {
        viewModel.start()
        viewModel.onDraftNameChanged("  CI  ")
        viewModel.onDraftScopeChanged(ApiTokenScope.WRITE)
        viewModel.onDraftExpiryChanged(30)
        viewModel.onCreateTapped()

        val request = server.requests.single()
        assertEquals(CreateApiTokenRequest("CI", ApiTokenScope.WRITE, 30), request, "The name was not trimmed, or a field was lost.")

        val shown = viewModel.stateFlow.value
        assertEquals("lnl_pat_abc123secret", shown.createdToken)
        assertEquals("", shown.draftName, "The form kept the name after making the token.")
        assertTrue(shown.isCreatedTokenDormant, "The panel does not warn that API access is off.")
        assertTrue(shown.curlExample.contains("lnl_pat_abc123secret"))

        viewModel.onCreatedTokenDismissed()
        assertNull(viewModel.stateFlow.value.createdToken, "The client held on to the token.")
        assertFalse(viewModel.stateFlow.value.curlExample.contains("secret"))
    }

    @Test
    fun `a row says what the token is and how long it has`() {
        viewModel.start()
        viewModel.onDraftNameChanged("Dashboard")
        viewModel.onCreateTapped()

        val row = viewModel.stateFlow.value.tokens.single()
        assertEquals("Dashboard", row.name)
        assertEquals(
            "Read-only · lnl_pat_abc123… · made 3 days ago · never used · expires in 90 days",
            row.detail,
        )
        assertFalse(row.isExpired)
    }

    @Test
    fun `an invented lifetime is ignored`() {
        viewModel.onDraftExpiryChanged(36_500)
        assertEquals(90, viewModel.stateFlow.value.draftExpiryDays)
    }

    @Test
    fun `revoking removes the row`() {
        viewModel.start()
        viewModel.onDraftNameChanged("Gone soon")
        viewModel.onCreateTapped()
        viewModel.onRevokeTapped(viewModel.stateFlow.value.tokens.single().id)
        assertTrue(viewModel.stateFlow.value.tokens.isEmpty())
    }

    // ── History ──────────────────────────────────────────────────────────────

    private fun event(id: Long, viaToken: String?) = IssueEventView(
        id = id,
        kind = IssueEventKind.STATUS_CHANGED,
        authorName = "Robert",
        createdAt = NOW,
        viaToken = viaToken,
    )

    @Test
    fun `history names the token on the byline, not as an agent`() {
        val state = IssueBackingViewModel.State(history = listOf(event(1, "CI")))
        val byline = state.historyByline(state.history.single())
        assertTrue(byline.endsWith(" · via API token “CI”"), byline)
        assertNull(state.historyAgentBadge(state.history.single()), "A token's change wears the agent badge.")
    }

    @Test
    fun `a change by hand and one by token in the same instant are separate blocks`() {
        val state = IssueBackingViewModel.State(history = listOf(event(1, null), event(2, "CI")))
        assertEquals(2, state.historyBlocksNewestFirst.size)
    }
}
