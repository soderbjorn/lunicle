/**
 * The statistics tables.
 *
 * Two stores, both the same shape as everything in [Projects]: SQL in, data class
 * out, no decisions. What a window *is*, when a snapshot is too old, and what to
 * do when GitHub will not answer all live in [StatisticsRepository].
 *
 * @see StatisticsRepository
 * @see ProjectStatistics.sq
 * @see IssueStatistics.sq
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * One measurement across the three windows the dialog shows.
 *
 * @property allTime deliberately not "the sum of everything" — for closed issues
 *   it is a different measurement from [week] and [month] entirely, and
 *   IssueStatistics.sq argues why no single one can answer all three.
 */
data class WindowCounts(
    val week: Long,
    val month: Long,
    val allTime: Long,
)

/**
 * Everything the dialog shows, as of one moment.
 *
 * @property computedAt when this was compiled. Stamped only on success, so the
 *   age on screen is the age of the numbers beside it and never of the attempt.
 */
data class StatisticsSnapshot(
    val computedAt: Long,
    val commits: CommitCounts,
    val issuesCreated: WindowCounts,
    val issuesClosed: WindowCounts,
)

/** Reads and writes the cached snapshot. */
class ProjectStatisticsStore(private val database: LunicleDatabase) {
    /** The last snapshot, or null because none was ever compiled for this project. */
    suspend fun forProject(projectId: Long): StatisticsSnapshot? = withContext(DatabaseDispatcher) {
        database.projectStatisticsQueries.forProject(projectId).executeAsOneOrNull()?.let { row ->
            StatisticsSnapshot(
                computedAt = row.computed_at,
                // The three counts travel together or not at all — they are written
                // from one CommitCounts and read back into one. A row with two of
                // three set is not reachable through `upsert`, so the null check on
                // the first speaks for all three.
                //
                // Counts *and* a reason is a reachable row and not a contradiction
                // (LNL-175): the counts are the last ones GitHub answered with and
                // the reason says why they were not refreshed. See
                // CommitCounts.Counted.notRefreshed.
                commits = if (row.commits_week != null && row.commits_month != null && row.commits_all != null) {
                    CommitCounts.Counted(
                        row.commits_week,
                        row.commits_month,
                        row.commits_all,
                        notRefreshed = row.commits_unavailable,
                    )
                } else {
                    CommitCounts.Unavailable(row.commits_unavailable ?: "Commit counts are unavailable.")
                },
                issuesCreated = WindowCounts(
                    row.issues_created_week,
                    row.issues_created_month,
                    row.issues_created_all,
                ),
                issuesClosed = WindowCounts(
                    row.issues_closed_week,
                    row.issues_closed_month,
                    row.issues_closed_all,
                ),
            )
        }
    }

    /** Replace this project's snapshot. See ProjectStatistics.sq for why REPLACE. */
    suspend fun upsert(projectId: Long, snapshot: StatisticsSnapshot): Unit = withContext(DatabaseDispatcher) {
        val counted = snapshot.commits as? CommitCounts.Counted
        database.projectStatisticsQueries.upsert(
            project_id = projectId,
            computed_at = snapshot.computedAt,
            commits_week = counted?.week,
            commits_month = counted?.month,
            commits_all = counted?.allTime,
            // Set in both directions: the reason a compile could not answer, or —
            // when counts were carried forward from the last one that could — the
            // reason they are older than the row around them (LNL-175).
            commits_unavailable = when (val commits = snapshot.commits) {
                is CommitCounts.Unavailable -> commits.reason
                is CommitCounts.Counted -> commits.notRefreshed
            },
            issues_created_week = snapshot.issuesCreated.week,
            issues_created_month = snapshot.issuesCreated.month,
            issues_created_all = snapshot.issuesCreated.allTime,
            issues_closed_week = snapshot.issuesClosed.week,
            issues_closed_month = snapshot.issuesClosed.month,
            issues_closed_all = snapshot.issuesClosed.allTime,
        )
    }
}

/** Counts issues for one project. Read-only; see IssueStatistics.sq. */
class IssueStatisticsStore(private val database: LunicleDatabase) {
    suspend fun created(projectId: Long, weekStart: Long, monthStart: Long): WindowCounts =
        withContext(DatabaseDispatcher) {
            WindowCounts(
                week = database.issueStatisticsQueries.createdSince(projectId, weekStart).executeAsOne(),
                month = database.issueStatisticsQueries.createdSince(projectId, monthStart).executeAsOne(),
                allTime = database.issueStatisticsQueries.createdAll(projectId).executeAsOne(),
            )
        }

    /**
     * Closed issues.
     *
     * The two windows come from the history and [WindowCounts.allTime] from the
     * board's current state. That they are different measurements is the point
     * rather than an inconsistency to fix; IssueStatistics.sq's preamble is the
     * long version.
     */
    suspend fun closed(projectId: Long, weekStart: Long, monthStart: Long): WindowCounts =
        withContext(DatabaseDispatcher) {
            WindowCounts(
                week = database.issueStatisticsQueries.closedSince(projectId, weekStart, projectId).executeAsOne(),
                month = database.issueStatisticsQueries.closedSince(projectId, monthStart, projectId).executeAsOne(),
                allTime = database.issueStatisticsQueries.closedAll(projectId).executeAsOne(),
            )
        }
}
