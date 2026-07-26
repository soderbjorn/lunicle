/**
 * The persistence behaviour every [StatisticsStore] implementation must exhibit.
 *
 * Scoped to what actually crosses the backend seam: a compiled snapshot is stored
 * and read back, and its issue counts reflect the issues the backend holds. The
 * freshness window and GitHub fallbacks are backend-agnostic orchestration pinned
 * by StatisticsTest, not parity concerns — so no repository is linked here and no
 * network is ever touched (commits come back Unavailable, which is itself asserted).
 *
 * A subclass per backend supplies the store and seeds a project with issues.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.CommitCounts

abstract class StatisticsStoreContract {
    protected abstract val store: StatisticsStore

    /** A fresh project with no linked repository. */
    protected abstract suspend fun newProject(): Long

    /** File one published issue in [projectId]. */
    protected abstract suspend fun fileIssue(projectId: Long)

    @Test
    fun `cached is null before anything is compiled`() = runBlocking {
        assertNull(store.cached(newProject()))
        assertTrue(store.isStale(null), "an absent snapshot is stale")
    }

    @Test
    fun `refresh compiles a snapshot that cached then reads back`() = runBlocking {
        val project = newProject()
        val refreshed = store.refresh(project)
        val cached = store.cached(project)
        assertNotNull(cached)
        assertEquals(refreshed.computedAt, cached.computedAt)
        assertEquals(false, store.isStale(cached), "a just-compiled snapshot is fresh")
    }

    @Test
    fun `the snapshot counts the issues the backend holds`() = runBlocking {
        val project = newProject()
        fileIssue(project)
        fileIssue(project)
        val snapshot = store.refresh(project)
        assertEquals(2, snapshot.issuesCreated.allTime)
    }

    @Test
    fun `with no repository linked, commits come back unavailable and no network is touched`() = runBlocking {
        val snapshot = store.refresh(newProject())
        assertTrue(snapshot.commits is CommitCounts.Unavailable)
    }
}
