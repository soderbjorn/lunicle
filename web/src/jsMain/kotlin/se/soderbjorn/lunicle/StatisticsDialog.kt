/**
 * The statistics modal, opened by the bar-chart button in the board toolbar.
 *
 * One table: a row per measurement, a column per window. Read-only — there is
 * nothing here to change, which makes this the simplest dialog in the app and the
 * one place a plain table is the whole design.
 *
 * ── The progress line, and why the table stays up behind it ────────────────
 *
 * When the numbers have aged out the dialog paints the old ones first and counts
 * the new ones behind them, so the busy state is a line above a legible table
 * rather than a spinner in an empty box. Blanking the table while refreshing
 * would take away information the user already had, in exchange for nothing — the
 * old numbers were true fifteen minutes ago and the age label says so.
 *
 * A dumb renderer, like every view here: every string and every number's grouping
 * comes from [StatisticsBackingViewModel].
 *
 * @see StatisticsBackingViewModel
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunicle.client.viewmodel.STATISTICS_ALL_TIME_HEADER
import se.soderbjorn.lunicle.client.viewmodel.STATISTICS_EMPTY_MESSAGE
import se.soderbjorn.lunicle.client.viewmodel.STATISTICS_MONTH_HEADER
import se.soderbjorn.lunicle.client.viewmodel.STATISTICS_TITLE
import se.soderbjorn.lunicle.client.viewmodel.STATISTICS_WEEK_HEADER
import se.soderbjorn.lunicle.client.viewmodel.StatisticsBackingViewModel

/** What the progress line says while a recompile is out. */
private const val REFRESHING_MESSAGE = "Counting…"

/**
 * Renders the statistics modal.
 *
 * @param projectId which project to count. Passed to [StatisticsBackingViewModel.start]
 *   rather than held by the view model's constructor, so the view model is
 *   constructed the same way every other one here is.
 */
class StatisticsDialog(
    private val viewModel: StatisticsBackingViewModel,
    private val projectId: Long,
    private val scope: CoroutineScope,
    private val onDismiss: () -> Unit,
) {
    private val modal = Modal(STATISTICS_TITLE, onDismiss = { onDismiss() })

    private lateinit var statusElement: HTMLElement
    private lateinit var tableElement: HTMLElement
    private lateinit var emptyElement: HTMLElement
    private lateinit var commitsNoteElement: HTMLElement
    private lateinit var errorElement: HTMLElement

    /**
     * What the table was last built from.
     *
     * The signature guard the other dialogs use. Needed here because the state
     * flow emits twice for one open — once with the cached numbers, once with the
     * recompiled ones — and those two are usually identical in shape. Without
     * this the table would be torn down and rebuilt for a repaint of the same
     * pixels.
     */
    private var tableSignature: String? = null

    fun mount(host: HTMLElement) {
        modal.body.classList.add("settings-body")

        statusElement = element("div", "statistics-status")
        emptyElement = element("p", "statistics-empty", STATISTICS_EMPTY_MESSAGE)
        tableElement = element("div", "statistics-table")
        commitsNoteElement = element("p", "statistics-note")
        errorElement = element("p", "modal-error")

        modal.body.children(statusElement, emptyElement, tableElement, commitsNoteElement, errorElement)
        modal.footer.children(
            element("div", "modal-footer-spacer"),
            button("Close", "btn btn-quiet") { onDismiss() },
        )
        modal.mount(host)

        scope.launch { viewModel.stateFlow.collect { render(it) } }
        // After mount, like every other dialog here: the empty frame is on screen
        // before the request goes out, so the wait is a rendered dialog rather
        // than a moment of nothing.
        viewModel.start(projectId)
    }

    fun dismiss() = modal.dismiss()

    private fun render(state: StatisticsBackingViewModel.State) {
        // The age and the progress line share one slot, because they answer the
        // same question — "how current is this?" — and only one of them is ever
        // the true answer. Two lines would leave "Updated 20 minutes ago" sitting
        // above "Counting…", which reads as two contradictory claims.
        statusElement.setTextIfChanged(
            when {
                state.isRefreshing -> REFRESHING_MESSAGE
                else -> state.ageLabel
            },
        )
        statusElement.visible(state.isRefreshing || state.ageLabel.isNotEmpty())

        // Empty only while there is genuinely nothing — never during a refresh,
        // which has a table to keep showing.
        emptyElement.visible(!state.hasStatistics && !state.isBusy)
        tableElement.visible(state.hasStatistics)

        renderTable(state)

        commitsNoteElement.setTextIfChanged(state.commitsUnavailable.orEmpty())
        commitsNoteElement.visible(state.commitsUnavailable != null)

        errorElement.setTextIfChanged(state.errorMessage.orEmpty())
        errorElement.visible(state.errorMessage != null)
    }

    /**
     * Build the table, if it has changed.
     *
     * A grid of divs rather than a `<table>`: the layout is four fixed columns
     * whose widths come from CSS, the rows carry a footnote element that a table
     * row has nowhere to put, and nothing here is tabular data a screen reader
     * gains from being told is a table — it is three labelled measurements.
     */
    private fun renderTable(state: StatisticsBackingViewModel.State) {
        val signature = state.rows.joinToString("|") {
            "${it.label}/${it.week}/${it.month}/${it.allTime}"
        }
        if (signature == tableSignature) return
        tableSignature = signature

        tableElement.clear()
        if (state.rows.isEmpty()) return

        tableElement.children(
            headerRow(),
            // One element per row now the closed-row footnote is gone (LNL-69) —
            // a plain map rather than the flatMap/buildList that let a row emit an
            // extra note paragraph under itself.
            *state.rows.map { row ->
                element("div", "statistics-row").children(
                    element("div", "statistics-cell statistics-label", row.label),
                    element("div", "statistics-cell statistics-value", row.week),
                    element("div", "statistics-cell statistics-value", row.month),
                    element("div", "statistics-cell statistics-value", row.allTime),
                )
            }.toTypedArray(),
        )
    }

    private fun headerRow(): HTMLElement = element("div", "statistics-row statistics-header").children(
        // An empty leading cell: the row labels have no header of their own, and
        // inventing one ("Measurement") would be a word that earns nothing.
        element("div", "statistics-cell statistics-label"),
        element("div", "statistics-cell statistics-value", STATISTICS_WEEK_HEADER),
        element("div", "statistics-cell statistics-value", STATISTICS_MONTH_HEADER),
        element("div", "statistics-cell statistics-value", STATISTICS_ALL_TIME_HEADER),
    )
}
