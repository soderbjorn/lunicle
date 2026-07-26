/**
 * The persistence seam for one project's statuses: the board columns, in order,
 * one of which every issue sits in.
 *
 * One of the LNL-111 domain store interfaces, and the richest of the five typed
 * vocabulary stores. The reference implementation is the SQLite gateway
 * [se.soderbjorn.lunicle.StatusStore] (named by its fully-qualified name in that
 * class's supertype clause, since the two share a simple name).
 *
 * Two things set a status apart from a label. Its rows
 * ([se.soderbjorn.lunicle.StatusRecord]) carry `requiresResolution` — the "magic"
 * in "Closed is a magic status", read from data rather than the column's name so a
 * renamed column keeps its meaning — which [insert] and [update] write. And
 * [firstForProject] answers the leftmost column, where a new issue lands, read
 * rather than hardcoded to "New". The trimming, uniqueness and delete rules live
 * one layer up in `VocabularyRepository` and are backend-agnostic.
 *
 * @see se.soderbjorn.lunicle.store.StatusStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.StatusRecord

interface StatusStore {
    /** Add a column at [position], optionally one that demands a resolution to enter. */
    suspend fun insert(projectId: Long, name: String, position: Long, requiresResolution: Boolean = false)

    /**
     * Rename, and set the closing flag — both in one write, because they are one
     * decision. [requiresResolution] is what lets an admin move the "Closed is
     * magic" behaviour to a column of their own naming.
     */
    suspend fun update(id: Long, name: String, requiresResolution: Boolean)

    /** Move one row — only ever from inside the repository's whole-list reorder transaction. */
    suspend fun setPosition(id: Long, position: Long)

    suspend fun delete(id: Long)

    /** The status with this id *in this project*, or null — so a route can prove it belongs before it writes. */
    suspend fun findByIdInProject(id: Long, projectId: Long): StatusRecord?

    /** This project's columns, left to right (position order). */
    suspend fun forProject(projectId: Long): List<StatusRecord>

    /**
     * The leftmost column, where a new issue lands, or null for a project with no
     * columns. Read rather than hardcoded to "New": a renamed board still takes an
     * issue.
     */
    suspend fun firstForProject(projectId: Long): StatusRecord?
}
