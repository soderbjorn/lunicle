/**
 * Which priorities the board draws loudly. See LNL-49.
 *
 * ── Why this is worth a file ─────────────────────────────────────────────────
 *
 * The rule has one job — emphasise the top of the scale — and two ways to get it
 * wrong that no one would notice by looking at the default board, because the
 * default board has five priorities and every case below behaves identically on
 * five.
 *
 * The first is testing the NAME. A project may rename "Very high" to "P0", or
 * drop to three priorities, or invert the vocabulary entirely; every one of those
 * leaves a name-matching implementation silently drawing nothing, on a board that
 * still looks plausible.
 *
 * The second is the short scale. Emphasis is only a signal while something is
 * left unemphasised: on a two-priority scale, colouring both means the board has
 * a highlight on literally every card, which reads as decoration and tells the
 * reader nothing. The tests for one- and two-priority projects are the ones that
 * pin that down, and they are the reason this is a table rather than a spot check.
 *
 * The closing-column test guards a *structural* claim rather than a visual one:
 * emphasis is derived per group, and a closing column's groups are resolutions,
 * so it comes out unemphasised without anything checking for it by name. If
 * someone later moves the derivation onto the card, that test fails — which is
 * the point, because the card knows its priority even when closed.
 *
 * @see MainScreenBackingViewModel.State.emphasisFor
 */
package se.soderbjorn.lunicle.client.viewmodel

import se.soderbjorn.lunicle.clientserver.BoardState
import se.soderbjorn.lunicle.clientserver.IssueSummary
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.StatusItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val OPEN_STATUS = 1L
private const val CLOSED_STATUS = 2L

/**
 * A board with [priorityCount] priorities and one issue at each, plus one closed
 * issue carrying the top priority and a resolution.
 *
 * Priority ids run 10, 11, 12 … in scale order, so a test can name the bucket it
 * means without counting. Deliberately handed to the state in REVERSE order: the
 * scale's order is `position`, and passing them backwards is what would catch an
 * implementation that trusted the order the list arrived in.
 */
private fun boardWith(priorityCount: Int): MainScreenBackingViewModel.State {
    val priorities = (0 until priorityCount).map { StatusItem(10L + it, "P$it", it) }
    val issues = priorities.map {
        IssueSummary(id = it.id, number = it.id, title = "at ${it.name}", statusId = OPEN_STATUS, priorityId = it.id)
    } + IssueSummary(
        id = 99, number = 99, title = "closed one", statusId = CLOSED_STATUS,
        priorityId = priorities.first().id, resolutionId = 7,
    )
    return MainScreenBackingViewModel.State(
        board = BoardState(
            project = ProjectSummary(1, "Test", "TST", isPublic = true),
            statuses = listOf(
                StatusItem(OPEN_STATUS, "Open", 0),
                StatusItem(CLOSED_STATUS, "Closed", 1, requiresResolution = true),
            ),
            priorities = priorities.reversed(),
            resolutions = listOf(StatusItem(7, "Fixed", 0)),
            issues = issues,
        ),
    )
}

/** The emphasis on the group holding the issue whose priority id is [priorityId]. */
private fun MainScreenBackingViewModel.State.emphasisAt(priorityId: Long): PriorityEmphasis? =
    columns.first { it.status.id == OPEN_STATUS }
        .groups.first { group -> group.issues.any { it.priorityId == priorityId } }
        .emphasis

class PriorityEmphasisTest {

    @Test
    fun `the top of a full scale is urgent and the second is high`() {
        val board = boardWith(priorityCount = 5)
        assertEquals(PriorityEmphasis.URGENT, board.emphasisAt(10))
        assertEquals(PriorityEmphasis.HIGH, board.emphasisAt(11))
    }

    @Test
    fun `the ordinary majority is not emphasised`() {
        val board = boardWith(priorityCount = 5)
        assertNull(board.emphasisAt(12))
        assertNull(board.emphasisAt(13))
        assertNull(board.emphasisAt(14))
    }

    @Test
    fun `three priorities is the shortest scale that still has a high`() {
        val board = boardWith(priorityCount = 3)
        assertEquals(PriorityEmphasis.URGENT, board.emphasisAt(10))
        assertEquals(PriorityEmphasis.HIGH, board.emphasisAt(11))
        assertNull(board.emphasisAt(12))
    }

    @Test
    fun `a two priority scale emphasises only its top, leaving something unemphasised`() {
        val board = boardWith(priorityCount = 2)
        assertEquals(PriorityEmphasis.URGENT, board.emphasisAt(10))
        assertNull(board.emphasisAt(11))
    }

    @Test
    fun `a single priority draws nothing, there being no distinction to draw`() {
        val board = boardWith(priorityCount = 1)
        assertNull(board.emphasisAt(10))
    }

    @Test
    fun `a closing column groups by resolution and so is never emphasised`() {
        val closed = boardWith(priorityCount = 5)
            .columns.first { it.status.id == CLOSED_STATUS }
        // The issue in here carries the TOP priority, which would be urgent in any
        // other column. Grouping by resolution is what drops it, not a check.
        assertEquals(1, closed.groups.size)
        assertEquals("Fixed", closed.groups.single().label)
        assertNull(closed.groups.single().emphasis)
    }

    @Test
    fun `a priority id matching no row is not emphasised`() {
        val board = boardWith(priorityCount = 5)
        // The em-dash group groupsFor falls back to — the board and its vocabulary
        // disagreeing. indexOfFirst answers -1, which must not read as "the top".
        val orphan = MainScreenBackingViewModel.State(
            board = board.board!!.copy(
                issues = listOf(
                    IssueSummary(id = 1, number = 1, title = "orphan", statusId = OPEN_STATUS, priorityId = 404),
                ),
            ),
        )
        val group = orphan.columns.first { it.status.id == OPEN_STATUS }.groups.single()
        assertEquals("—", group.label)
        assertNull(group.emphasis)
    }
}
