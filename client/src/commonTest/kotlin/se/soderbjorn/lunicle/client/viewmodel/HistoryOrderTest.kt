/**
 * Which end of the history the read face starts at — LNL-186.
 *
 * The history is stored, and sent, oldest first: a log is written in the order
 * it happened. On screen it sits below the description and the whole comment
 * thread, so drawn in that order the one event a reader usually wants — what
 * just happened — is the one furthest down the page. The read face therefore
 * draws [IssueBackingViewModel.State.historyBlocksNewestFirst], and this pins
 * what that means.
 *
 * The thing that moves is the **block** — one save, one byline, however many
 * sentences (LNL-215) — and not the event. Four ways it can go wrong, and a
 * plain "reverse the list" assertion catches only the first:
 *
 * 1. ORDER. Newest block first, oldest last.
 * 2. THE SENTENCES INSIDE A BLOCK. One save that changes a title and a set of
 *    labels writes two events under one byline, and they still read in the order
 *    that save made them. Reversing events rather than blocks would turn a save
 *    into its own sentences backwards.
 * 3. TWO BLOCKS SHARING AN INSTANT. A person and their agent can save in the same
 *    millisecond; that is two bylines and one date. Sorting alone leaves those in
 *    written order, which puts the older of them on top; only the reversal
 *    underneath the sort gets them right.
 * 4. A DATE THAT DISAGREES WITH THE WRITE ORDER. Back-filled and reattributed
 *    events carry a `createdAt` set by hand, so id order need not be date order
 *    (see `McpTools.updateHistoryEvent`). Every block shows its date, so the list
 *    has to follow the dates rather than the ids.
 *
 * And [IssueBackingViewModel.State.history] itself must not move: it is the wire
 * order, and the derivation is a view of it, not a rewrite.
 *
 * @see IssueBackingViewModel.State.historyBlocksNewestFirst
 */
package se.soderbjorn.lunicle.client.viewmodel

import se.soderbjorn.lunicle.clientserver.IssueEventKind
import se.soderbjorn.lunicle.clientserver.IssueEventView
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One event, identified by its [id] — that and the two fields the grouping reads
 * are all these assertions care about.
 */
private fun event(id: Long, createdAt: Long, authorName: String? = "Robert"): IssueEventView =
    IssueEventView(
        id = id,
        kind = IssueEventKind.STATUS_CHANGED,
        authorName = authorName,
        createdAt = createdAt,
    )

/** An issue whose history is [events], in the oldest-first order the API sends. */
private fun state(vararg events: IssueEventView): IssueBackingViewModel.State =
    IssueBackingViewModel.State(history = events.toList())

/** The ids each block holds, in order — the shape every assertion below is about. */
private fun IssueBackingViewModel.State.blockIds(): List<List<Long>> =
    historyBlocksNewestFirst.map { block -> block.events.map { it.id } }

class HistoryOrderTest {

    @Test
    fun `the newest block comes first and the oldest last`() {
        val state = state(
            event(id = 1, createdAt = 1_000),
            event(id = 2, createdAt = 2_000),
            event(id = 3, createdAt = 3_000),
        )

        assertEquals(listOf(listOf(3L), listOf(2L), listOf(1L)), state.blockIds())
    }

    @Test
    fun `one save stays one block, read in the order it was written`() {
        val state = state(
            event(id = 1, createdAt = 1_000),
            // One save, three events, one timestamp, one byline.
            event(id = 2, createdAt = 2_000),
            event(id = 3, createdAt = 2_000),
            event(id = 4, createdAt = 2_000),
        )

        assertEquals(listOf(listOf(2L, 3L, 4L), listOf(1L)), state.blockIds())
    }

    @Test
    fun `two blocks in the same millisecond keep last-written first`() {
        val state = state(
            event(id = 1, createdAt = 1_000, authorName = "Robert"),
            // Same instant, different hand: two blocks, one date.
            event(id = 2, createdAt = 2_000, authorName = "Robert"),
            event(id = 3, createdAt = 2_000, authorName = "Alex"),
        )

        assertEquals(listOf(listOf(3L), listOf(2L), listOf(1L)), state.blockIds())
    }

    @Test
    fun `a back-filled block sits where its date says, not where it was written`() {
        val state = state(
            event(id = 1, createdAt = 2_000),
            event(id = 2, createdAt = 3_000),
            // Imported later, reattributed to a date before either of the above.
            event(id = 3, createdAt = 1_000),
        )

        assertEquals(listOf(listOf(2L), listOf(1L), listOf(3L)), state.blockIds())
    }

    @Test
    fun `an empty history derives an empty list`() {
        assertEquals(emptyList(), state().historyBlocksNewestFirst)
    }

    @Test
    fun `the wire order is left alone`() {
        val state = state(
            event(id = 1, createdAt = 1_000),
            event(id = 2, createdAt = 2_000),
        )

        state.historyBlocksNewestFirst

        assertEquals(listOf(1L, 2L), state.history.map { it.id })
    }
}
