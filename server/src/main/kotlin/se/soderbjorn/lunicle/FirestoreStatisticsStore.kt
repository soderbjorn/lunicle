/**
 * The Firestore [se.soderbjorn.lunicle.store.StatisticsStore] — a project's
 * statistics snapshot over documents.
 *
 * ── What crosses the seam, and what does not ────────────────────────────────
 *
 * The interface's contract is about *persistence*: a compiled snapshot is stored,
 * read back, and its issue counts reflect the issues the backend holds. The
 * freshness window, the anti-stampede lock and the GitHub fallbacks are
 * backend-agnostic *orchestration*; the SQLite reference is [StatisticsRepository],
 * and this class reuses that same logic — a fifteen-minute window, one in-process
 * mutex per project, commit counts that fall back to Unavailable — over Firestore
 * storage. The mutex is single-instance only, exactly as the reference's is; a
 * shared lock for multi-instance is an explicit out-of-scope follow-up (LNL-122).
 *
 * ── Document model ──────────────────────────────────────────────────────────
 *
 * One snapshot document per project in `statistics/{projectId}`, its fields flat —
 * `computedAt`, the three commit counts (nullable, with an `commitsUnavailable`
 * reason when they are absent), and the six issue-window counts — mirroring
 * ProjectStatistics.sq's row so the read reconstructs one [CommitCounts] and two
 * [WindowCounts]. The issue counts are computed by querying the `issues` collection
 * FirestoreIssueStore writes, one single-field query per project plus in-memory
 * aggregation, the same shape as FirestoreIssueStore's `usageBy*`.
 *
 * ── One parity gap, deliberately left ───────────────────────────────────────
 *
 * `issuesClosed.week`/`month` are 0 here. In SQLite those come from the issue
 * *history* (`issue_events`) — a STATUS_CHANGED into a closing column within the
 * window — because closing is not a column on the issue. The Firestore history store
 * is a separate ticket and this store does not depend on it, so the two history-based
 * windows cannot be computed yet; `issuesClosed.allTime` (read off `resolutionId`,
 * the board as it stands) and both `issuesCreated` windows are exact. The
 * StatisticsStore contract pins none of the closed windows, and wiring the history
 * source in is LNL-122's job — the same way FirestoreIssueStore left board ordering
 * to its caller.
 *
 * @see FirestoreProvider
 * @see StatisticsRepository
 * @see se.soderbjorn.lunicle.store.StatisticsStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FirestoreStatisticsStore(
    private val firestore: Firestore,
    private val repositoryConfigFor: suspend (Long) -> RepositoryConfig? = { null },
    private val gitHub: CommitCounter = GitHubClient(),
    private val tokenLookup: (String) -> String? = { name ->
        System.getProperty(name)?.takeIf { it.isNotBlank() } ?: System.getenv(name)?.takeIf { it.isNotBlank() }
    },
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.StatisticsStore {
    private fun snapshotDoc(projectId: Long) = firestore.collection(SNAPSHOTS).document(projectId.toString())

    override suspend fun cached(projectId: Long): StatisticsSnapshot? {
        val snapshot = snapshotDoc(projectId).get().await()
        if (!snapshot.exists()) return null
        val week = snapshot.getLong(COMMITS_WEEK)
        val month = snapshot.getLong(COMMITS_MONTH)
        val all = snapshot.getLong(COMMITS_ALL)
        return StatisticsSnapshot(
            computedAt = snapshot.getLong(COMPUTED_AT) ?: 0L,
            // The three counts travel together or not at all, so the null check on
            // the first speaks for all three — as ProjectStatisticsStore does. And,
            // as there, counts *with* a reason mean counts carried forward from the
            // last compile GitHub answered (LNL-175).
            commits = if (week != null && month != null && all != null) {
                CommitCounts.Counted(week, month, all, notRefreshed = snapshot.getString(COMMITS_UNAVAILABLE))
            } else {
                CommitCounts.Unavailable(snapshot.getString(COMMITS_UNAVAILABLE) ?: "Commit counts are unavailable.")
            },
            issuesCreated = WindowCounts(
                snapshot.getLong(CREATED_WEEK) ?: 0L,
                snapshot.getLong(CREATED_MONTH) ?: 0L,
                snapshot.getLong(CREATED_ALL) ?: 0L,
            ),
            issuesClosed = WindowCounts(
                snapshot.getLong(CLOSED_WEEK) ?: 0L,
                snapshot.getLong(CLOSED_MONTH) ?: 0L,
                snapshot.getLong(CLOSED_ALL) ?: 0L,
            ),
        )
    }

    override fun isStale(snapshot: StatisticsSnapshot?): Boolean =
        snapshot == null || now() - snapshot.computedAt >= FRESH_FOR.inWholeMilliseconds

    override suspend fun refresh(projectId: Long): StatisticsSnapshot {
        val cached = cached(projectId)
        if (!isStale(cached)) return cached!!
        // One refresh per project at a time, so two dialogs opened in the same second
        // do not both call GitHub. Single-instance guarantee, as the reference.
        return refreshLocks.getOrPut(projectId) { Mutex() }.withLock {
            // Re-read inside the lock: whoever was ahead has just written a fresh
            // snapshot, and the work we queued for is already done.
            val previous = cached(projectId)
            previous?.takeIf { !isStale(it) }?.let { return@withLock it }
            // Handed along so a refusal from GitHub does not delete the last counts
            // it did answer with — the reference's rule. See CommitCounts.orLastKnown.
            compile(projectId, previous).also { store(projectId, it) }
        }
    }

    private suspend fun compile(projectId: Long, previous: StatisticsSnapshot?): StatisticsSnapshot {
        val at = now()
        val weekStart = at - WEEK.inWholeMilliseconds
        val monthStart = at - MONTH.inWholeMilliseconds
        val issues = firestore.collection(ISSUES)
            .whereEqualTo(ISSUE_PROJECT_ID, projectId)
            .get().await()
            .documents
            // Drafts excluded — a half-written issue has not happened yet, as
            // IssueStatistics.sq's createdSince/createdAll do.
            .filter { it.getBoolean(ISSUE_IS_DRAFT) != true }
        val created = WindowCounts(
            week = issues.count { (it.getLong(ISSUE_CREATED_AT) ?: 0L) >= weekStart }.toLong(),
            month = issues.count { (it.getLong(ISSUE_CREATED_AT) ?: 0L) >= monthStart }.toLong(),
            allTime = issues.size.toLong(),
        )
        val closed = WindowCounts(
            // See the class preamble: the two windows need the issue history, which
            // this backend does not yet hold; all-time is exact off resolutionId.
            week = 0L,
            month = 0L,
            allTime = issues.count { it.get(ISSUE_RESOLUTION_ID) != null }.toLong(),
        )
        return StatisticsSnapshot(
            computedAt = at,
            commits = commitCounts(projectId, weekStart, monthStart, previous?.commits),
            issuesCreated = created,
            issuesClosed = closed,
        )
    }

    private suspend fun commitCounts(
        projectId: Long,
        weekStart: Long,
        monthStart: Long,
        previous: CommitCounts?,
    ): CommitCounts {
        val config = repositoryConfigFor(projectId)
        val repository = config?.repository
            ?: return CommitCounts.Unavailable("No GitHub repository is linked to this project.")
        val token = when (val source = config.token) {
            is TokenSource.None ->
                return CommitCounts.Unavailable("No access token is configured for this project.")
            is TokenSource.Literal -> source.token
            is TokenSource.Env -> tokenLookup(source.variableName)
                ?: return CommitCounts.Unavailable(
                    "The environment variable ${source.variableName} is not set on this server.",
                )
        }
        return gitHub.commitCounts(repository, token, weekStart, monthStart).orLastKnown(previous)
    }

    private suspend fun store(projectId: Long, snapshot: StatisticsSnapshot) {
        val counted = snapshot.commits as? CommitCounts.Counted
        snapshotDoc(projectId).set(
            mapOf(
                COMPUTED_AT to snapshot.computedAt,
                COMMITS_WEEK to counted?.week,
                COMMITS_MONTH to counted?.month,
                COMMITS_ALL to counted?.allTime,
                // Written in both directions, as ProjectStatisticsStore does: the
                // reason nothing could be counted, or the reason the counts beside
                // it are older than the rest of the row (LNL-175).
                COMMITS_UNAVAILABLE to when (val commits = snapshot.commits) {
                    is CommitCounts.Unavailable -> commits.reason
                    is CommitCounts.Counted -> commits.notRefreshed
                },
                CREATED_WEEK to snapshot.issuesCreated.week,
                CREATED_MONTH to snapshot.issuesCreated.month,
                CREATED_ALL to snapshot.issuesCreated.allTime,
                CLOSED_WEEK to snapshot.issuesClosed.week,
                CLOSED_MONTH to snapshot.issuesClosed.month,
                CLOSED_ALL to snapshot.issuesClosed.allTime,
            ),
        ).await()
    }

    private val refreshLocks = ConcurrentHashMap<Long, Mutex>()

    internal companion object {
        val FRESH_FOR = 15.minutes
        val WEEK = 7.days
        val MONTH = 30.days

        const val SNAPSHOTS = "statistics"
        const val ISSUES = "issues"
        const val ISSUE_PROJECT_ID = "projectId"
        const val ISSUE_IS_DRAFT = "isDraft"
        const val ISSUE_CREATED_AT = "createdAt"
        const val ISSUE_RESOLUTION_ID = "resolutionId"

        const val COMPUTED_AT = "computedAt"
        const val COMMITS_WEEK = "commitsWeek"
        const val COMMITS_MONTH = "commitsMonth"
        const val COMMITS_ALL = "commitsAll"
        const val COMMITS_UNAVAILABLE = "commitsUnavailable"
        const val CREATED_WEEK = "issuesCreatedWeek"
        const val CREATED_MONTH = "issuesCreatedMonth"
        const val CREATED_ALL = "issuesCreatedAll"
        const val CLOSED_WEEK = "issuesClosedWeek"
        const val CLOSED_MONTH = "issuesClosedMonth"
        const val CLOSED_ALL = "issuesClosedAll"
    }
}
