/**
 * The pinned workspace of a chromeless embed: `?chrome=minimal|none` with a board
 * named (see [PinnedBoard]).
 *
 * Two properties, both about the reader's REAL workspace rather than the embed's:
 * the embed never reads the stored layout (which would put every tab they own
 * behind a frame that cannot show them) and never writes one (which would replace
 * the layout they use on the full site with a one-board arrangement). The fake
 * below counts both calls, so "never" is a number rather than a hope.
 *
 * @see PinnedBoard
 * @see WorkspaceBackingViewModel
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.clientserver.HttpLunicleApi
import se.soderbjorn.lunicle.clientserver.LunicleApi
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.UiSettingKeys
import se.soderbjorn.lunicle.clientserver.UiSettingsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A transport holding a stored workspace and counting every read and write of it.
 *
 * `by HttpLunicleApi(...)` supplies the members this test never calls; any other
 * call would hit a host that does not resolve and fail loudly.
 */
private class CountingSettingsApi(private val blob: String) :
    LunicleApi by HttpLunicleApi(baseUrl = "http://workspace-pinned.invalid") {

    var reads = 0
    var writes = 0

    override suspend fun uiSettings(): UiSettingsState {
        reads++
        return UiSettingsState(userId = USER_ID, settings = mapOf(UiSettingKeys.WORKSPACE to blob))
    }

    override suspend fun setUiSetting(key: String, value: String) {
        writes++
    }
}

private const val USER_ID: Long = 7

private val PROJECTS = listOf(
    ProjectSummary(id = 1, name = "Lunamux", namePrefix = "LMX"),
    ProjectSummary(id = 2, name = "Lunicle", namePrefix = "LNL"),
    ProjectSummary(id = 3, name = "Partner Growth", namePrefix = "PGT"),
)

/** The full-site layout this account arranged: two tabs, neither of them project 3. */
private fun storedWorkspace(): Workspace {
    val one = PaneRef.Board(1)
    val two = PaneRef.Board(2)
    return Workspace(
        tabs = listOf(
            WorkspaceTab(id = "t8", name = "Mine", panes = listOf(one), activePaneId = one.paneId),
            WorkspaceTab(id = "t9", name = "Also mine", panes = listOf(two), activePaneId = two.paneId),
        ),
        activeTabId = "t9",
    )
}

class WorkspacePinnedBoardTest {

    /** Unconfined, as in [WorkspaceSettlesTest]: every coroutine here runs inline. */
    private fun viewModelOn(api: CountingSettingsApi, pin: PinnedBoard?) =
        WorkspaceBackingViewModel(StorageRepository(api), CoroutineScope(Dispatchers.Unconfined), pin)

    private fun boardsIn(vm: WorkspaceBackingViewModel): List<List<Long?>> =
        vm.stateFlow.value.workspace.tabs.map { tab -> tab.panes.map { it.projectId } }

    @Test
    fun `a pinned embed shows only its board and never reads the stored layout`() {
        val api = CountingSettingsApi(WorkspaceCodec.encode(storedWorkspace()))
        val vm = viewModelOn(api, PinnedBoard(projectId = 3))

        vm.onSessionChanged(identity = USER_ID, isKnown = true)
        vm.onProjectsChanged(PROJECTS)

        assertEquals(listOf(listOf<Long?>(3)), boardsIn(vm), "one tab, holding the pinned board")
        assertEquals("Partner Growth", vm.stateFlow.value.workspace.tabs.single().name)
        assertTrue(vm.stateFlow.value.isSettled, "no stored layout is in flight to wait for")
        assertEquals(0, api.reads, "the reader's workspace is not the embed's business")
    }

    @Test
    fun `gestures inside a pinned embed are never saved`() {
        val api = CountingSettingsApi(WorkspaceCodec.encode(storedWorkspace()))
        val vm = viewModelOn(api, PinnedBoard(projectId = 3))
        vm.onSessionChanged(identity = USER_ID, isKnown = true)
        vm.onProjectsChanged(PROJECTS)

        // An issue opened from the board, then the deep link's own board-open: both
        // real changes to what is on screen, neither the reader's layout.
        vm.onIssueOpened(issueId = 40, projectId = 3)
        vm.onBoardOpened(3)

        assertEquals(1, vm.stateFlow.value.workspace.tabs.size, "the board was already open")
        assertEquals(0, api.writes, "saving would overwrite the full-site layout")
    }

    @Test
    fun `a pin by name resolves case-insensitively`() {
        val api = CountingSettingsApi(WorkspaceCodec.encode(storedWorkspace()))
        val vm = viewModelOn(api, PinnedBoard(projectName = "partner growth"))
        vm.onSessionChanged(identity = USER_ID, isKnown = true)
        vm.onProjectsChanged(PROJECTS)

        assertEquals(listOf(listOf<Long?>(3)), boardsIn(vm))
    }

    @Test
    fun `a private board appears once the visitor signs in`() {
        val api = CountingSettingsApi(WorkspaceCodec.encode(storedWorkspace()))
        val vm = viewModelOn(api, PinnedBoard(projectId = 3))

        // Signed out, the visitor can see only the public boards — not this one.
        vm.onSessionChanged(identity = null, isKnown = true)
        vm.onProjectsChanged(PROJECTS.take(2))
        assertEquals(listOf(emptyList<Long?>()), boardsIn(vm), "an empty frame, not some other board")

        vm.onSessionChanged(identity = USER_ID, isKnown = true)
        vm.onProjectsChanged(PROJECTS)
        assertEquals(listOf(listOf<Long?>(3)), boardsIn(vm))
        assertEquals(0, api.reads)
    }

    @Test
    fun `without a pin the stored layout is still restored`() {
        val api = CountingSettingsApi(WorkspaceCodec.encode(storedWorkspace()))
        val vm = viewModelOn(api, pin = null)
        vm.onSessionChanged(identity = USER_ID, isKnown = true)
        vm.onProjectsChanged(PROJECTS)

        assertEquals(listOf("t8", "t9"), vm.stateFlow.value.workspace.tabs.map { it.id })
        assertEquals(1, api.reads)
    }
}
