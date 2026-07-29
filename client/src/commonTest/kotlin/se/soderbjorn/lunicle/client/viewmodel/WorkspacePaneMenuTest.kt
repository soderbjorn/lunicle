/**
 * The two gestures the pane overflow menu reports: renaming a window, and
 * moving it to another tab (LNA-5).
 *
 * The toolkit owns both affordances — it draws the `⋮`, arms its own inline
 * title editor, and builds the list of tabs a window can move to — and hands
 * back only the outcome. What the outcome *means* to a workspace of boards and
 * issues is decided here, and that is what these test:
 *
 *  - an empty rename **clears** the override rather than blanking the window,
 *    because a Lunicle pane title is derived and should go back to following
 *    what it names;
 *  - an override belongs to one tab, because a pane id names what the pane
 *    shows and the same board is `board-7` in every tab that holds it;
 *  - a name travels with the window it was given to, and dies with it;
 *  - a move does not drag the reader along with the window.
 *
 * No transport is exercised: the view model is fed a project list and its
 * gestures are called directly, which is all the rules above depend on.
 *
 * @see WorkspaceBackingViewModel.onPaneRenamed
 * @see WorkspaceBackingViewModel.onPaneMovedToTab
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.clientserver.HttpLunicleApi
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val PROJECTS = listOf(
    ProjectSummary(id = 1, name = "Lunamux", namePrefix = "LMX", isPublic = true),
    ProjectSummary(id = 2, name = "Lunicle", namePrefix = "LNL", isPublic = true),
)

/**
 * A signed-out view model seeded with [PROJECTS] — one tab per project, each
 * holding that project's board.
 *
 * Signed out on purpose: nothing is fetched and nothing is stored, so every
 * assertion below reads a state the gesture under test produced and nothing
 * else. The transport is never asked anything; a call that reached it would hit
 * a host that does not resolve, which is the right answer for a fake being used
 * for something it does not model.
 */
private fun seededViewModel(): WorkspaceBackingViewModel {
    val vm = WorkspaceBackingViewModel(
        StorageRepository(HttpLunicleApi(baseUrl = "http://workspace-pane-menu.invalid")),
        CoroutineScope(Dispatchers.Unconfined),
    )
    vm.onSessionChanged(identity = null, isKnown = true)
    vm.onProjectsChanged(PROJECTS)
    return vm
}

private val WorkspaceBackingViewModel.tabs get() = stateFlow.value.workspace.tabs

private fun WorkspaceBackingViewModel.tab(id: String): WorkspaceTab =
    stateFlow.value.workspace.tabs.first { it.id == id }

class WorkspacePaneMenuTest {

    @Test
    fun `a rename names the window in that tab and nowhere else`() {
        val vm = seededViewModel()
        val (first, second) = vm.tabs.map { it.id }
        val board = PaneRef.Board(1).paneId
        // The same board, open in both tabs — the case a workspace-wide override
        // would get wrong, since the pane id is the same string in each.
        vm.onTabSelected(second)
        vm.onBoardAdded(1)

        vm.onPaneRenamed(first, board, "Ship blockers")

        assertEquals("Ship blockers", vm.tab(first).paneLabel(board))
        assertNull(vm.tab(second).paneLabel(board), "the other tab's copy is a different window")
    }

    @Test
    fun `an empty commit clears the override rather than blanking the window`() {
        val vm = seededViewModel()
        val tabId = vm.tabs.first().id
        val board = PaneRef.Board(1).paneId

        vm.onPaneRenamed(tabId, board, "Ship blockers")
        vm.onPaneRenamed(tabId, board, "")

        assertNull(vm.tab(tabId).paneLabel(board), "the derived title takes over again")
        assertTrue(vm.tab(tabId).paneLabels.isEmpty(), "and nothing is left behind for it")
    }

    @Test
    fun `renaming a pane that is not in that tab does nothing`() {
        val vm = seededViewModel()
        val tabId = vm.tabs.first().id

        vm.onPaneRenamed(tabId, PaneRef.Board(2).paneId, "Not here")
        vm.onPaneRenamed("no-such-tab", PaneRef.Board(1).paneId, "Nor here")

        assertTrue(vm.tabs.all { it.paneLabels.isEmpty() })
    }

    @Test
    fun `a closed window forgets the name it was given`() {
        val vm = seededViewModel()
        val tabId = vm.tabs.first().id
        val board = PaneRef.Board(1).paneId

        vm.onPaneRenamed(tabId, board, "Ship blockers")
        vm.onPaneClosed(tabId, board)

        assertTrue(
            vm.tab(tabId).paneLabels.isEmpty(),
            "re-opening the board later is a new window, not the one that was named",
        )
    }

    @Test
    fun `a move relocates the window and focuses it where it lands`() {
        val vm = seededViewModel()
        val (source, target) = vm.tabs.map { it.id }
        val board = PaneRef.Board(1).paneId

        vm.onPaneMovedToTab(source, board, target)

        assertTrue(vm.tab(source).pane(board) == null, "it left")
        assertEquals(board, vm.tab(target).panes.last().paneId, "and arrived at the end")
        assertEquals(board, vm.tab(target).activePaneId, "focused, so visiting the tab lands on it")
    }

    @Test
    fun `a move does not drag the reader along with the window`() {
        val vm = seededViewModel()
        val (source, target) = vm.tabs.map { it.id }
        vm.onTabSelected(source)

        vm.onPaneMovedToTab(source, PaneRef.Board(1).paneId, target)

        assertEquals(source, vm.stateFlow.value.workspace.activeTabId)
    }

    @Test
    fun `the name travels with the window`() {
        val vm = seededViewModel()
        val (source, target) = vm.tabs.map { it.id }
        val board = PaneRef.Board(1).paneId

        vm.onPaneRenamed(source, board, "Ship blockers")
        vm.onPaneMovedToTab(source, board, target)

        assertEquals("Ship blockers", vm.tab(target).paneLabel(board))
        assertTrue(vm.tab(source).paneLabels.isEmpty())
    }

    @Test
    fun `moving onto a tab that already holds the pane collapses instead of duplicating`() {
        val vm = seededViewModel()
        val (source, target) = vm.tabs.map { it.id }
        val board = PaneRef.Board(1).paneId
        vm.onTabSelected(target)
        vm.onBoardAdded(1)

        vm.onPaneMovedToTab(source, board, target)

        assertTrue(vm.tab(source).pane(board) == null)
        assertEquals(
            1,
            vm.tab(target).panes.count { it.paneId == board },
            "one pane per thing per tab is structural — the ids would collide",
        )
    }

    @Test
    fun `a move to nowhere, or to where it already is, is nothing`() {
        val vm = seededViewModel()
        val source = vm.tabs.first().id
        val board = PaneRef.Board(1).paneId
        val before = vm.stateFlow.value.workspace

        vm.onPaneMovedToTab(source, board, source)
        vm.onPaneMovedToTab(source, board, "no-such-tab")
        vm.onPaneMovedToTab(source, "no-such-pane", vm.tabs.last().id)

        assertEquals(before, vm.stateFlow.value.workspace)
    }

    @Test
    fun `a named window comes back named`() {
        val vm = seededViewModel()
        val tabId = vm.tabs.first().id
        val board = PaneRef.Board(1).paneId
        vm.onPaneRenamed(tabId, board, "Ship blockers")

        // The round trip the stored blob makes: what survives a reload is exactly
        // what the codec keeps, and a name the codec dropped would come back as
        // the derived title with nothing to say it had ever been changed.
        val restored = WorkspaceCodec.decode(WorkspaceCodec.encode(vm.stateFlow.value.workspace))

        assertEquals("Ship blockers", restored?.tabs?.first { it.id == tabId }?.paneLabel(board))
    }
}
