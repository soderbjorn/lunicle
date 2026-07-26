/**
 * Which columns the board draws as lanes and which it collapses into the rail —
 * the derivation half of LNL-100.
 *
 * The mutation half (the ⋮ menu writing [MainScreenBackingViewModel.State.projectPrefs]
 * and persisting it) rides the storage repository and a coroutine scope; what is
 * worth pinning without either is the pure reading of the state that results:
 * given a set of hidden ids, what does the board show, what does it collapse, and
 * in what order. That is [State.hiddenColumnIds], [State.shownColumns] and
 * [State.hiddenColumns], and it has three ways to go wrong that a default board
 * would never reveal.
 *
 * The first is ORDER. Hidden columns must render in board order, not in the order
 * they were hidden — so the rail reads the same for two people who hid the same
 * columns in a different sequence, and a restored column comes back where it
 * always sat. The prefs list is handed in reversed here to catch an implementation
 * that trusted it.
 *
 * The second is the STALE id — a hidden column since deleted. Its id lingers in
 * the stored preference, and it must simply fall away rather than conjure a phantom
 * lane or need cleaning up to stop mattering.
 *
 * The third is SCOPE: the hidden set is per project, keyed by id, so a board with
 * no project on it hides nothing, and a preference filed under another project's id
 * touches neither list.
 *
 * @see MainScreenBackingViewModel.State.shownColumns
 */
package se.soderbjorn.lunicle.client.viewmodel

import se.soderbjorn.lunicle.clientserver.BoardState
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.StatusItem
import kotlin.test.Test
import kotlin.test.assertEquals

private const val PROJECT_ID = 1L
private const val NEW = 10L
private const val DOING = 11L
private const val DONE = 12L

/**
 * A three-column board for [PROJECT_ID], with [hidden] the ids that project's
 * user has hidden. Statuses are handed in out of position order, so a test that
 * passes proves the ordering came from `position` and not from arrival order.
 */
private fun board(hidden: List<Long> = emptyList()): MainScreenBackingViewModel.State =
    MainScreenBackingViewModel.State(
        board = BoardState(
            project = ProjectSummary(PROJECT_ID, "Test", "TST", isPublic = true),
            statuses = listOf(
                StatusItem(DONE, "Done", 2),
                StatusItem(NEW, "New", 0),
                StatusItem(DOING, "Doing", 1),
            ),
            issues = emptyList(),
        ),
        projectPrefs = if (hidden.isEmpty()) {
            emptyMap()
        } else {
            mapOf(PROJECT_ID to UserProjectPrefs(hiddenColumnIds = hidden))
        },
    )

private fun List<BoardColumn>.ids(): List<Long> = map { it.status.id }

class HiddenColumnsTest {

    @Test
    fun `with nothing hidden every column shows and the rail is empty`() {
        val state = board()
        assertEquals(emptySet(), state.hiddenColumnIds)
        assertEquals(listOf(NEW, DOING, DONE), state.shownColumns.ids())
        assertEquals(emptyList(), state.hiddenColumns.ids())
    }

    @Test
    fun `a hidden column leaves the shown lanes and joins the rail`() {
        val state = board(hidden = listOf(DOING))
        assertEquals(setOf(DOING), state.hiddenColumnIds)
        assertEquals(listOf(NEW, DONE), state.shownColumns.ids())
        assertEquals(listOf(DOING), state.hiddenColumns.ids())
    }

    @Test
    fun `hidden columns render in board order, not the order they were hidden`() {
        // Handed in reversed: Done before New. The rail must still read New, Done.
        val state = board(hidden = listOf(DONE, NEW))
        assertEquals(listOf(NEW, DONE), state.hiddenColumns.ids())
        assertEquals(listOf(DOING), state.shownColumns.ids())
    }

    @Test
    fun `a hidden id that names no current column falls away`() {
        // 999 was hidden, then the column was deleted. It must haunt neither list.
        val state = board(hidden = listOf(DOING, 999L))
        assertEquals(listOf(DOING), state.hiddenColumns.ids())
        assertEquals(listOf(NEW, DONE), state.shownColumns.ids())
    }

    @Test
    fun `a board with no project hides nothing`() {
        val state = MainScreenBackingViewModel.State(
            projectPrefs = mapOf(PROJECT_ID to UserProjectPrefs(hiddenColumnIds = listOf(NEW))),
        )
        assertEquals(emptySet(), state.hiddenColumnIds)
        assertEquals(emptyList(), state.shownColumns.ids())
        assertEquals(emptyList(), state.hiddenColumns.ids())
    }

    @Test
    fun `a preference filed under another project does not touch this board`() {
        val state = MainScreenBackingViewModel.State(
            board = board().board,
            projectPrefs = mapOf(2L to UserProjectPrefs(hiddenColumnIds = listOf(NEW))),
        )
        assertEquals(emptySet(), state.hiddenColumnIds)
        assertEquals(listOf(NEW, DOING, DONE), state.shownColumns.ids())
    }
}
