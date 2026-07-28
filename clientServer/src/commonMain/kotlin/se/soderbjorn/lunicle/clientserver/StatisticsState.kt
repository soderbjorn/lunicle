/**
 * Wire types for the Statistics dialog.
 *
 * The dialog answers one question — *"how much has been happening here, lately
 * and overall?"* — so these types carry counts and the two facts a reader needs
 * to trust them: when they were compiled, and whether anything could not be
 * answered.
 *
 * ── Why the age travels, and is not hidden ─────────────────────────────────
 *
 * These numbers are up to fifteen minutes old by design, and a dashboard that
 * quietly presents stale figures as current is worse than one that shows nothing.
 * So [ProjectStatistics.computedAt] is not an implementation detail leaking onto
 * the wire; it is part of the answer, and the view is expected to render it.
 *
 * @see se.soderbjorn.lunicle.clientserver.LunicleApi.projectStatistics
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.Serializable

/**
 * One measurement across the three windows the dialog shows.
 *
 * @property week the last 7 days, as a rolling window rather than a calendar
 *   week — "the past seven days" is a question with the same answer whatever day
 *   you ask it, where "this week" means something different on Monday.
 * @property month the last 30 days, on the same reasoning.
 * @property allTime everything. Note for closed issues this is a *different
 *   measurement* from the two above rather than a longer window of the same one;
 *   see [ProjectStatistics.issuesClosed].
 */
@Serializable
data class StatisticWindow(
    val week: Long = 0,
    val month: Long = 0,
    val allTime: Long = 0,
)

/**
 * One compiled snapshot.
 *
 * @property computedAt when this was compiled, epoch millis. Stamped only on a
 *   successful compile, so it always dates the numbers beside it rather than the
 *   most recent attempt at them.
 * @property commits commits on the repository's default branch, or null because
 *   they could not be counted — see [commitsUnavailable]. Null rather than zeroes,
 *   because a quiet week and an unanswerable question are different facts and a
 *   zero would state the first while meaning the second.
 * @property commitsUnavailable why [commits] is null, in a sentence fit to show a
 *   user, or null because there is nothing to explain.
 *
 *   Carries the *reason* rather than a boolean because the four ways this happens
 *   — no repository linked, no token configured, the variable missing from the
 *   deployment, GitHub refusing — are fixed by different people doing different
 *   things. "Unavailable" alone tells none of them which errand is theirs.
 *
 *   **Not exclusive with [commits]** (LNL-175). Both set means "these are the last
 *   counts GitHub answered with, and here is why they are not this moment's" —
 *   which is what the server sends rather than deleting a working row over one
 *   refused call. The view draws the row from [commits] and the note from this,
 *   and neither has ever asked about the other.
 * @property issuesCreated issues filed in each window. Exact in all three:
 *   `created_at` is written once and never moves.
 * @property issuesClosed issues closed. [StatisticWindow.week] and
 *   [StatisticWindow.month] are counted from the issue history, and
 *   [StatisticWindow.allTime] from the board as it stands right now.
 *
 *   **They are deliberately not the same measurement.** An issue records the
 *   status it is in, not when it got there, so a window can only be answered from
 *   the history — which began partway through this instance's life and cannot see
 *   closes recorded under a since-renamed column. The board, by contrast, knows
 *   exactly how many issues are closed today. The consequence a reader may notice:
 *   on a project older than its history, all-time can exceed anything the windows
 *   would sum to. The view labels the two rather than implying one arithmetic.
 */
@Serializable
data class ProjectStatistics(
    val computedAt: Long = 0,
    val commits: StatisticWindow? = null,
    val commitsUnavailable: String? = null,
    val issuesCreated: StatisticWindow = StatisticWindow(),
    val issuesClosed: StatisticWindow = StatisticWindow(),
)

/**
 * The Statistics dialog, whole.
 *
 * Returned by both statistics routes rather than each sending a fragment,
 * matching [McpState]: the client re-renders from one object and can never hold a
 * half-updated view.
 *
 * @property statistics the last snapshot, or null because none has ever been
 *   compiled for this project. Null is the state every project starts in and is
 *   rendered as "no statistics yet" — distinct from a snapshot full of zeroes,
 *   which means somebody counted and there was nothing to count.
 * @property isStale whether [statistics] has aged past the refresh window and the
 *   client should ask for a recompile.
 *
 *   Sent rather than derived in the browser from [ProjectStatistics.computedAt],
 *   because the window is a server-side rule and a client that computed it would
 *   be a second place that rule lives — one that a clock-skewed laptop could
 *   disagree with. The server is also the only side that can honour it: a client
 *   that decided for itself could ask for a refresh every second, and the route
 *   would still refuse.
 *
 *   True with a null [statistics] too: nothing compiled yet is exactly the state
 *   that most needs compiling.
 */
@Serializable
data class StatisticsState(
    val statistics: ProjectStatistics? = null,
    val isStale: Boolean = true,
)
