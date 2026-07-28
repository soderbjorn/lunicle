/**
 * The Statistics dialog: how much has been happening in this project.
 *
 * ── The two-step load, and why it is not one ───────────────────────────────
 *
 * Opening the dialog does two things in sequence: it reads whatever was last
 * compiled and paints it, and *then*, only if the server says those numbers have
 * aged out, asks for a recompile. The recompile can take as long as github.com
 * does.
 *
 * Collapsing that into a single call would mean an empty dialog for the whole of
 * that wait, which is precisely the moment a user most wants something on screen —
 * and what they want is usually already known, because fifteen-minute-old counts
 * answer "how are we doing" perfectly well. So the old numbers stay up, visibly
 * labelled as old, while the new ones are counted behind them.
 *
 * ── What this view model is honest about ───────────────────────────────────
 *
 * Two things, both of which a less careful dashboard would hide. The numbers have
 * an age and it is always on screen. And when the commit counts cannot be
 * answered, it says which of the four reasons applies rather than showing a zero —
 * a zero is a claim that nothing happened, and that is not what "your token
 * expired" means.
 *
 * All the logic, one immutable [State] over a [StateFlow], no platform in sight —
 * the project convention.
 *
 * @see se.soderbjorn.lunicle.clientserver.StatisticsState
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
import se.soderbjorn.lunicle.clientserver.ProjectStatistics
import se.soderbjorn.lunicle.clientserver.StatisticWindow
import se.soderbjorn.lunicle.clientserver.StatisticsState

/** The dialog's title. */
const val STATISTICS_TITLE: String = "Statistics"

/** The column headers, left to right. */
const val STATISTICS_WEEK_HEADER: String = "Past 7 days"
const val STATISTICS_MONTH_HEADER: String = "Past 30 days"
const val STATISTICS_ALL_TIME_HEADER: String = "All time"

/**
 * What the dialog says when nothing has ever been compiled.
 *
 * Distinct from a table of zeroes, which would claim somebody counted and found
 * nothing. This says nobody has counted yet — which is true of every project until
 * the first person opens this dialog, and resolves itself seconds later.
 */
const val STATISTICS_EMPTY_MESSAGE: String = "No statistics have been compiled for this project yet."

/**
 * Owns the statistics round-trips.
 *
 * @param now the clock the age label is measured against. Injectable so a test
 *   need not sleep to watch a snapshot get old.
 */
class StatisticsBackingViewModel(
    private val storage: StorageRepository = StorageRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = { statisticsNow() },
) {
    private val _stateFlow = MutableStateFlow(State())

    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * One line of the table, already rendered.
     *
     * Strings rather than numbers, because the formatting is a decision — thousands
     * separators, and the em dash that stands in for a count that could not be
     * made — and decisions belong here rather than in three platform views that
     * would each make it slightly differently.
     */
    data class Row(
        val label: String,
        val week: String,
        val month: String,
        val allTime: String,
    )

    /**
     * @property isLoading whether the first read is still out. Distinct from
     *   [isRefreshing]: this one means there is nothing to show yet, where that one
     *   means there is something on screen and better numbers are coming.
     * @property isRefreshing whether a recompile is in flight. What the progress
     *   indicator is bound to, and deliberately *not* a reason to blank the table —
     *   the old rows stay legible underneath it.
     * @property rows the table, empty before anything has ever been compiled.
     * @property commitsUnavailable why there is no commit row, in the server's own
     *   words, or null because there is one.
     * @property ageLabel "Updated 4 minutes ago", or empty before the first
     *   snapshot exists.
     */
    data class State(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val rows: List<Row> = emptyList(),
        val commitsUnavailable: String? = null,
        val ageLabel: String = "",
        val errorMessage: String? = null,
    ) {
        /**
         * Whether there is a table to draw.
         *
         * False on a project nobody has ever opened this dialog for, which renders
         * as [EMPTY_MESSAGE] rather than as a table of zeroes. "Nothing has been
         * counted" and "we counted, and the answer was nothing" are different
         * statements and the second one would be a lie here.
         */
        val hasStatistics: Boolean get() = rows.isNotEmpty()

        /**
         * Whether to say the dialog is working.
         *
         * True for the first load *and* for a recompile, because from the user's
         * side those are the same event — something is happening and the numbers
         * are not final yet. What differs is whether there is a table underneath,
         * and [hasStatistics] answers that separately.
         */
        val isBusy: Boolean get() = isLoading || isRefreshing
    }

    private var started = false

    /**
     * Open the dialog. Idempotent.
     *
     * Idempotent because the dialog can be reopened without the view model being
     * rebuilt, and a second open must not fire a second recompile — the server
     * would refuse it anyway, but a request whose only possible outcome is
     * "refused" is one not worth making.
     */
    fun start(projectId: Long) {
        if (started) return
        started = true
        scope.launch { load(projectId) }
    }

    private suspend fun load(projectId: Long) {
        val cached = runCatching { storage.projectStatistics(projectId) }
        cached.fold(
            onSuccess = { state ->
                _stateFlow.value = state.applyTo(_stateFlow.value).copy(isLoading = false)
                // Only now, with something on screen, is the slow call worth
                // making. If the server says the numbers are current we are done
                // and github.com was never touched.
                if (state.isStale) refresh(projectId)
            },
            onFailure = { t ->
                _stateFlow.value = _stateFlow.value.copy(
                    isLoading = false,
                    errorMessage = t.userMessage("Could not load statistics."),
                )
            },
        )
    }

    private suspend fun refresh(projectId: Long) {
        _stateFlow.value = _stateFlow.value.copy(isRefreshing = true, errorMessage = null)
        val result = runCatching { storage.refreshProjectStatistics(projectId) }
        _stateFlow.value = result.fold(
            onSuccess = { it.applyTo(_stateFlow.value).copy(isRefreshing = false) },
            onFailure = { t ->
                // The previously loaded rows are deliberately left in place. A
                // failed recompile did not invalidate them — they are exactly as
                // true as they were a second ago, and the age label already says
                // how old that is. Blanking the table would turn a failure to get
                // *newer* numbers into a failure to show any.
                _stateFlow.value.copy(
                    isRefreshing = false,
                    errorMessage = t.userMessage("Could not refresh statistics."),
                )
            },
        )
    }

    /** Fold a server [StatisticsState] into the view state. */
    private fun StatisticsState.applyTo(previous: State): State {
        val snapshot = statistics ?: return previous.copy(errorMessage = null)
        return previous.copy(
            errorMessage = null,
            rows = snapshot.toRows(),
            commitsUnavailable = snapshot.commitsNote(),
            ageLabel = "Updated ${formatRelative(snapshot.computedAt, now())}",
        )
    }

    /**
     * The sentence under the table, or null because there is nothing to say.
     *
     * Two different sentences share this one slot, and which one it is depends on
     * whether there is a commit row above it (LNL-175):
     *
     *  - **No row.** The reason stands alone and explains the row's absence, which
     *    is what it has always done.
     *  - **A row.** The counts are the last ones GitHub answered with and the
     *    reason says why they were not refreshed — so the sentence has to say that
     *    the numbers above are old, or it reads as an explanation for numbers that
     *    are plainly right there.
     */
    private fun ProjectStatistics.commitsNote(): String? {
        val reason = commitsUnavailable ?: return null
        if (commits == null) return reason
        return "$reason The commit counts above are the last that could be read."
    }

    private fun ProjectStatistics.toRows(): List<Row> = buildList {
        // Omitted rather than shown as dashes when unavailable: the reason is
        // shown instead, in its place, and a row of three em dashes beside a
        // sentence explaining them would be saying the same thing twice.
        commits?.let { add(Row("Commits on the default branch", it.week.count(), it.month.count(), it.allTime.count())) }
        add(issuesCreated.row("Issues created"))
        // No footnote on the closed row any more (LNL-69): it explained that the
        // 7-/30-day closed counts come from issue history while all-time counts the
        // live board, but the column headers already say which window each number
        // is, and the note read as clutter under a two-row table.
        add(issuesClosed.row("Issues closed"))
    }

    private fun StatisticWindow.row(label: String) =
        Row(label, week.count(), month.count(), allTime.count())

    private companion object {
        /**
         * A count, with thin spaces between thousands.
         *
         * Grouped because these are read at a glance and compared against each
         * other — "1 284" and "128" are instantly different lengths where "1284"
         * and "128" need a second look. A space rather than a comma or a period,
         * because those two mean opposite things in different locales and a space
         * means the same thing everywhere.
         */
        fun Long.count(): String = toString()
            .reversed()
            .chunked(3)
            .joinToString(" ")
            .reversed()
    }
}

/** The current time, in common code. See ConnectionsBackingViewModel. */
@OptIn(kotlin.time.ExperimentalTime::class)
private fun statisticsNow(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
