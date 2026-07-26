/**
 * The persistence seam for a project's statistics snapshot.
 *
 * One of the LNL-111 domain store interfaces. Unlike most, its name does not
 * collide with an existing class: the reference implementation is
 * [se.soderbjorn.lunicle.StatisticsRepository], which implements this directly.
 *
 * What crosses this seam is the *snapshot* — the persisted, cached result — not
 * how it is compiled. The freshness window, the anti-stampede lock and the GitHub
 * fallbacks are backend-agnostic orchestration that lives in the reference
 * implementation and is pinned by StatisticsTest; a document backend would reuse
 * that logic over its own snapshot storage. So the cross-backend contract here is
 * deliberately about persistence: a compiled snapshot is stored, read back, and
 * reflects the issues that were counted.
 *
 * @see se.soderbjorn.lunicle.store.StatisticsStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.StatisticsSnapshot

interface StatisticsStore {
    /** The last snapshot as it stands, compiling nothing. Null when none was ever compiled. */
    suspend fun cached(projectId: Long): StatisticsSnapshot?

    /** Whether a snapshot has aged out (or is absent) and is due to be recompiled. */
    fun isStale(snapshot: StatisticsSnapshot?): Boolean

    /** Recompile unless a fresh snapshot already exists inside the window, and store the result. */
    suspend fun refresh(projectId: Long): StatisticsSnapshot
}
