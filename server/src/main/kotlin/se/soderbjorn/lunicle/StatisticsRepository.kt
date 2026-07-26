/**
 * Compiling a project's statistics, and deciding when not to.
 *
 * The stores count and cache; this decides. Three rules live here and nowhere
 * else: how old a snapshot may be before it is recompiled, what happens when two
 * people ask for that recompile at the same moment, and what the commit counts
 * say when there is no repository, no token, or no answer from GitHub.
 *
 * ── Why a read recompiles at all ───────────────────────────────────────────
 *
 * Every other GET in this server is answered from SQLite. This one may make
 * network calls, which makes it the slowest route here and the only one whose
 * latency belongs to a third party. That is a deliberate trade against the
 * alternative — a background job on a timer — for two reasons. A repository
 * nobody looks at costs nothing, where a timer would poll every linked repository
 * forever whether or not anyone cared. And there is no scheduler in this server
 * to hang such a job on; the only background work is a one-shot startup sweep,
 * and adding a loop means adding a thing that can wedge silently at 3am.
 *
 * The cost, stated: the first person through the door after fifteen minutes waits
 * for GitHub. The dialog shows them that it is working rather than pretending it
 * is instant.
 *
 * @see Statistics
 * @see GitHubStatistics
 */
package se.soderbjorn.lunicle

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Compiles and caches statistics.
 *
 * @param tokenLookup how a variable name becomes a token. Injectable so tests
 *   never touch the real environment, and two-tier for [resolveEmailValue]'s
 *   reason: `:server:run` inherits the Gradle daemon's environment, so a system
 *   property is the only override that cannot silently go stale.
 * @param now injectable so the freshness window can be tested without sleeping.
 */
class StatisticsRepository(
    private val projects: se.soderbjorn.lunicle.store.ProjectStore,
    private val snapshots: ProjectStatisticsStore,
    private val issueCounts: IssueStatisticsStore,
    private val gitHub: CommitCounter = GitHubClient(),
    private val tokenLookup: (String) -> String? = { name ->
        System.getProperty(name)?.takeIf { it.isNotBlank() } ?: System.getenv(name)?.takeIf { it.isNotBlank() }
    },
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.StatisticsStore {
    /**
     * The last snapshot as it stands, without compiling anything.
     *
     * What the dialog renders the instant it opens. Separate from [refresh]
     * because the two answer different questions and the split is what lets the
     * dialog show last week's numbers *while* this week's are being counted — a
     * single route that recomputed before answering would leave the dialog empty
     * for as long as GitHub took, which is exactly the moment a user most wants
     * something on screen.
     */
    override suspend fun cached(projectId: Long): StatisticsSnapshot? = snapshots.forProject(projectId)

    /**
     * Whether a snapshot has aged out and is due to be recompiled.
     *
     * A missing snapshot is stale by definition: nothing has ever been compiled
     * for this project, so the answer to "should we compile" is yes.
     */
    override fun isStale(snapshot: StatisticsSnapshot?): Boolean =
        snapshot == null || now() - snapshot.computedAt >= FRESH_FOR.inWholeMilliseconds

    /**
     * Recompile, unless somebody already did within the window.
     *
     * Deliberately has no "force" parameter. The fifteen-minute window is a
     * ceiling on how often this server will call GitHub for one project, and a
     * bypass would be the first thing an impatient reload — or a retry loop —
     * reached for. So this is idempotent inside the window: calling it twice in a
     * minute makes one set of network calls and returns the same numbers, which
     * is also what makes it safe to call from a dialog anyone can open.
     */
    override suspend fun refresh(projectId: Long): StatisticsSnapshot {
        val cached = snapshots.forProject(projectId)
        if (!isStale(cached)) return cached!!

        // One refresh per project at a time. Two people opening the dialog in the
        // same second must not both call GitHub — the second would spend a rate
        // limit to compute a number the first is already computing.
        //
        // Per project rather than one lock for the server, so a slow repository
        // cannot hold up a different project's dialog. Single replica, so an
        // in-process lock is the whole of the guarantee needed; see the volume
        // notes in docs/ for why there will never be a second writer.
        return refreshLocks.getOrPut(projectId) { Mutex() }.withLock {
            // Re-read inside the lock. Whoever was ahead of us in the queue has
            // just written a snapshot, and it is by definition fresher than the
            // one we read outside — so the work we queued for has already been
            // done. Without this the wait would be pointless: every queued caller
            // would go on to make the same calls anyway, which is the stampede the
            // lock exists to prevent rather than merely to serialise.
            snapshots.forProject(projectId)?.takeIf { !isStale(it) }?.let { return@withLock it }

            compile(projectId).also { snapshots.upsert(projectId, it) }
        }
    }

    /**
     * Count everything, once, against one instant.
     *
     * The instant is read once and used for every window, rather than each count
     * calling `now()` for itself. Two calls straddling a millisecond would give
     * the week and the month different starting points, and a snapshot's whole
     * claim is that its numbers describe one moment.
     */
    private suspend fun compile(projectId: Long): StatisticsSnapshot {
        val at = now()
        val weekStart = at - WEEK.inWholeMilliseconds
        val monthStart = at - MONTH.inWholeMilliseconds
        return StatisticsSnapshot(
            computedAt = at,
            commits = commitCounts(projectId, weekStart, monthStart),
            issuesCreated = issueCounts.created(projectId, weekStart, monthStart),
            issuesClosed = issueCounts.closed(projectId, weekStart, monthStart),
        )
    }

    /**
     * The commit counts, or the reason there are none.
     *
     * Four distinct absences, spelled out separately because each one is fixed by
     * a different person doing a different thing, and "commits unavailable" tells
     * none of them which. An admin who has linked a repository but not a token is
     * one settings field away; an admin whose variable is missing from Railway is
     * one deployment variable away; and those are not the same errand.
     */
    private suspend fun commitCounts(projectId: Long, weekStart: Long, monthStart: Long): CommitCounts {
        val config = projects.repositoryConfig(projectId)
        val repository = config?.repository
            ?: return CommitCounts.Unavailable("No GitHub repository is linked to this project.")
        // The token, resolved from whichever source the project configured. A
        // literal is already the token; an env source is one lookup away and adds a
        // fifth distinct absence — the variable naming nothing on this deployment,
        // which a literal source cannot suffer. See TokenSource.
        val token = when (val source = config.token) {
            is TokenSource.None ->
                return CommitCounts.Unavailable("No access token is configured for this project.")
            is TokenSource.Literal -> source.token
            is TokenSource.Env -> tokenLookup(source.variableName)
                ?: return CommitCounts.Unavailable(
                    "The environment variable ${source.variableName} is not set on this server.",
                )
        }
        return gitHub.commitCounts(repository, token, weekStart, monthStart)
    }

    private companion object {
        /**
         * How long a snapshot stands before it is recompiled.
         *
         * Fifteen minutes is a ceiling on how often this server will call GitHub
         * for one project, and therefore the thing that keeps a dialog somebody
         * leaves open — or reloads at — from becoming a rate-limit problem. It is
         * not a promise of freshness: a project nobody opens is never recompiled
         * at all, which is the intended behaviour and the reason there is no timer.
         */
        val FRESH_FOR = 15.minutes

        /** "Past week" and "past month", as rolling windows rather than calendar ones. */
        val WEEK = 7.days
        val MONTH = 30.days
    }

    /**
     * One mutex per project, created on first use.
     *
     * Never pruned. A mutex is a handful of bytes and the key set is bounded by
     * the number of projects on the instance, which is a number an admin creates
     * by hand — so the leak this would be on an unbounded key space is not one
     * here. Pruning would need its own lock to be safe against the `getOrPut`,
     * which is more machinery than the thing it is managing.
     */
    private val refreshLocks = ConcurrentHashMap<Long, Mutex>()
}
