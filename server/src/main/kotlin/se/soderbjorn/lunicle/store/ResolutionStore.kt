/**
 * The persistence seam for one project's resolutions: the named, ordered reasons
 * an issue is closed.
 *
 * One of the LNL-111 domain store interfaces. The reference implementation is the
 * SQLite gateway [se.soderbjorn.lunicle.ResolutionStore] (named by its
 * fully-qualified name in that class's supertype clause, since the two share a
 * simple name). It reuses [se.soderbjorn.lunicle.StatusRecord] for its rows — an
 * id, a project, a name, a position — the same triplet [PriorityStore] does; the
 * record's `requiresResolution` is always false here.
 *
 * A separate interface from [PriorityStore] despite the identical surface, to
 * mirror the concrete shape and keep every call site naming the vocabulary it
 * means. The trimming, uniqueness and delete rules live one layer up in
 * `VocabularyRepository` and are backend-agnostic.
 *
 * @see se.soderbjorn.lunicle.store.ResolutionStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.StatusRecord

interface ResolutionStore {
    /** Add a resolution at [position] (the end of the order; the repository owns that arithmetic). */
    suspend fun insert(projectId: Long, name: String, position: Long)

    /**
     * Rename, and set the done flag — both in one write, because they are one
     * decision. [isDone] is what "require a fixed version when resolving" consults;
     * the mirror of [StatusStore.update]'s `requiresResolution`. Naming rules live
     * in `VocabularyRepository.rename`.
     */
    suspend fun update(id: Long, name: String, isDone: Boolean)

    /** Move one row — only ever from inside the repository's whole-list reorder transaction. */
    suspend fun setPosition(id: Long, position: Long)

    suspend fun delete(id: Long)

    /** The resolution with this id *in this project*, or null — so a route can prove it belongs before it writes. */
    suspend fun findByIdInProject(id: Long, projectId: Long): StatusRecord?

    /** This project's resolutions, in position order. */
    suspend fun forProject(projectId: Long): List<StatusRecord>
}
