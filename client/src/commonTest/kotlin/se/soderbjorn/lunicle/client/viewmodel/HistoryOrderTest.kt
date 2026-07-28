/**
 * Which end of the history the read face starts at — LNL-186.
 *
 * The history is stored, and sent, oldest first: a log is written in the order
 * it happened. On screen it sits below the description and the whole comment
 * thread, so drawn in that order the one event a reader usually wants — what
 * just happened — is the one furthest down the page. The read face therefore
 * draws [IssueBackingViewModel.State.historyNewestFirst], and this pins what
 * that means.
 *
 * Three ways it can go wrong, and a plain "reverse the list" assertion catches
 * only the first:
 *
 * 1. ORDER. Newest first, oldest last.
 * 2. The SAME-MILLISECOND tie. One save can record several events at once —
 *    change a title and a set of labels together and every row lands on the same
 *    timestamp. Sorting alone leaves those in written order, which puts the
 *    oldest of them at the top of the page; only the reversal underneath the
 *    sort gets them right.
 * 3. A DATE THAT DISAGREES WITH THE WRITE ORDER. Back-filled and reattributed
 *    events carry a `createdAt` set by hand, so id order need not be date order
 *    (see `McpTools.updateHistoryEvent`). Every row shows its date, so the list
 *    has to follow the dates rather than the ids.
 *
 * And [IssueBackingViewModel.State.history] itself must not move: it is the wire
 * order, and the derivation is a view of it, not a rewrite.
 *
 * @see IssueBackingViewModel.State.historyNewestFirst
 */
package se.soderbjorn.lunicle.client.viewmodel

import se.soderbjorn.lunicle.clientserver.IssueEventKind
import se.soderbjorn.lunicle.clientserver.IssueEventView
import kotlin.test.Test
import kotlin.test.assertEquals

/** One event, identified by its [id] — the only field these assertions read. */
private fun event(id: Long, createdAt: Long): IssueEventView =
    IssueEventView(id = id, kind = IssueEventKind.STATUS_CHANGED, createdAt = createdAt)

/** An issue whose history is [events], in the oldest-first order the API sends. */
private fun state(vararg events: IssueEventView): IssueBackingViewModel.State =
    IssueBackingViewModel.State(history = events.toList())

class HistoryOrderTest {

    @Test
    fun `the newest event comes first and the oldest last`() {
        val state = state(
            event(id = 1, createdAt = 1_000),
            event(id = 2, createdAt = 2_000),
            event(id = 3, createdAt = 3_000),
        )

        assertEquals(listOf(3L, 2L, 1L), state.historyNewestFirst.map { it.id })
    }

    @Test
    fun `events written in the same millisecond keep last-written first`() {
        val state = state(
            event(id = 1, createdAt = 1_000),
            // One save, three events, one timestamp.
            event(id = 2, createdAt = 2_000),
            event(id = 3, createdAt = 2_000),
            event(id = 4, createdAt = 2_000),
        )

        assertEquals(listOf(4L, 3L, 2L, 1L), state.historyNewestFirst.map { it.id })
    }

    @Test
    fun `a back-filled event sits where its date says, not where it was written`() {
        val state = state(
            event(id = 1, createdAt = 2_000),
            event(id = 2, createdAt = 3_000),
            // Imported later, reattributed to a date before either of the above.
            event(id = 3, createdAt = 1_000),
        )

        assertEquals(listOf(2L, 1L, 3L), state.historyNewestFirst.map { it.id })
    }

    @Test
    fun `an empty history derives an empty list`() {
        assertEquals(emptyList(), state().historyNewestFirst)
    }

    @Test
    fun `the wire order is left alone`() {
        val state = state(
            event(id = 1, createdAt = 1_000),
            event(id = 2, createdAt = 2_000),
        )

        state.historyNewestFirst

        assertEquals(listOf(1L, 2L), state.history.map { it.id })
    }
}
