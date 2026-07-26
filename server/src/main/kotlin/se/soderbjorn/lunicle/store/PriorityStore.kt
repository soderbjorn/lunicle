/**
 * The persistence seam for one project's priorities: a named, ordered scale every
 * issue holds exactly one of.
 *
 * One of the LNL-111 domain store interfaces. The reference implementation is the
 * SQLite gateway [se.soderbjorn.lunicle.PriorityStore] (named by its
 * fully-qualified name in that class's supertype clause, since the two share a
 * simple name). It reuses [se.soderbjorn.lunicle.StatusRecord] for its rows — an
 * id, a project, a name, a position — because a priority is the same shape as a
 * board column; the record's `requiresResolution` is always false here.
 *
 * The one thing a priority has that a label does not is [defaultForProject]: what
 * a new issue gets, which is the *middle* of the scale, not the top. The trimming,
 * uniqueness and delete rules live one layer up in `VocabularyRepository` and are
 * backend-agnostic.
 *
 * @see se.soderbjorn.lunicle.store.PriorityStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.StatusRecord

interface PriorityStore {
    /** Add a priority at [position] (the end of the order; the repository owns that arithmetic). */
    suspend fun insert(projectId: Long, name: String, position: Long)

    /** Rename. Naming rules live in `VocabularyRepository.rename`. */
    suspend fun update(id: Long, name: String)

    /** Move one row — only ever from inside the repository's whole-list reorder transaction. */
    suspend fun setPosition(id: Long, position: Long)

    suspend fun delete(id: Long)

    /** The priority with this id *in this project*, or null — so a route can prove it belongs before it writes. */
    suspend fun findByIdInProject(id: Long, projectId: Long): StatusRecord?

    /** This project's priorities, in position order — highest first. */
    suspend fun forProject(projectId: Long): List<StatusRecord>

    /**
     * What a new issue gets: the middle of the scale, not the top.
     *
     * The middle rather than the first, because a new issue landing at "Very high"
     * is a lie someone must then correct on every issue, where the middle claims
     * nothing. Read rather than hardcoded to "Normal": a renamed scale must still
     * take an issue. The middle is `position`-sorted index `count / 2` (integer
     * division; an even scale rounds to the calmer, lower half).
     */
    suspend fun defaultForProject(projectId: Long): StatusRecord?
}
