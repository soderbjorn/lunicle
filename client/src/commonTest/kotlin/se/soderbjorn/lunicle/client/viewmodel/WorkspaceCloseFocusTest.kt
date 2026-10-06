/**
 * Where focus goes when the focused window closes (LNL-227).
 *
 * Two boards filling one tab, an issue read from the first: closing the issue
 * must bring back the first board — where the reader came from — and not the
 * second one merely because it sits later in the tab's pane list.
 *
 * @see WorkspaceTab.recentPaneIds
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.clientserver.HttpLunicleApi
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import kotlin.test.Test
import kotlin.test.assertEquals

private val TWO_PROJECTS = listOf(
    ProjectSummary(id = 1, name = "Lunamux", namePrefix = "LMX"),
    ProjectSummary(id = 2, name = "Lunicle", namePrefix = "LNL"),
)

/** Signed out, so nothing is fetched or stored; see WorkspacePaneMenuTest. */
private fun twoBoardsInOneTab(): WorkspaceBackingViewModel {
    val vm = WorkspaceBackingViewModel(
        StorageRepository(HttpLunicleApi(baseUrl = "http://workspace-close-focus.invalid")),
        CoroutineScope(Dispatchers.Unconfined),
    )
    vm.onSessionChanged(identity = null, isKnown = true)
    vm.onProjectsChanged(TWO_PROJECTS)
    // The first tab holds board 1; add board 2 beside it, then go back to board 1.
    vm.onTabSelected(vm.stateFlow.value.workspace.tabs.first().id)
    vm.onBoardAdded(2)
    vm.onPaneFocused(PaneRef.Board(1).paneId)
    return vm
}

private val WorkspaceBackingViewModel.activeTab get() = stateFlow.value.workspace.activeTab!!

class WorkspaceCloseFocusTest {

    @Test
    fun `closing an issue returns focus to the board it was opened from`() {
        val vm = twoBoardsInOneTab()
        vm.onIssueOpened(issueId = 42, projectId = 1)
        assertEquals(PaneRef.Issue(42, 1).paneId, vm.activeTab.activePaneId)

        vm.onIssueClosed(42)

        assertEquals(PaneRef.Board(1).paneId, vm.activeTab.activePaneId)
    }

    @Test
    fun `closing via the pane cross also returns to the previous pane`() {
        val vm = twoBoardsInOneTab()
        vm.onIssueOpened(issueId = 42, projectId = 1)

        vm.onPaneClosed(vm.activeTab.id, PaneRef.Issue(42, 1).paneId)

        assertEquals(PaneRef.Board(1).paneId, vm.activeTab.activePaneId)
    }

    @Test
    fun `focus history follows the reader across several windows`() {
        val vm = twoBoardsInOneTab()
        vm.onIssueOpened(issueId = 42, projectId = 1)
        vm.onPaneFocused(PaneRef.Board(2).paneId)
        vm.onIssueOpened(issueId = 43, projectId = 2)

        vm.onIssueClosed(43)
        assertEquals(PaneRef.Board(2).paneId, vm.activeTab.activePaneId)

        // Closing a window that is not focused leaves focus alone.
        vm.onIssueClosed(42)
        assertEquals(PaneRef.Board(2).paneId, vm.activeTab.activePaneId)
    }
}
