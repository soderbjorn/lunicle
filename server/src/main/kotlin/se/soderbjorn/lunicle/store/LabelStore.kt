/**
 * The persistence seam for one project's labels: a named, ordered vocabulary a
 * card may carry any number of.
 *
 * One of the LNL-111 domain store interfaces, and the plainest of the five typed
 * vocabulary stores — an id, a project, a name and a position, no per-kind extra.
 * The reference implementation is the SQLite gateway
 * [se.soderbjorn.lunicle.LabelStore] (named by its fully-qualified name in that
 * class's supertype clause, since the two share a simple name).
 *
 * This is the low-level persistence; the trimming, the case-insensitive
 * uniqueness and the delete rules live one layer up in `VocabularyRepository` and
 * are backend-agnostic. So the contract here is only what round-trips: [forProject]
 * in position order, [findByIdInProject] scoped to its project, and that
 * insert/rename/reorder/delete are read back.
 *
 * @see se.soderbjorn.lunicle.store.LabelStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.VocabularyRecord

interface LabelStore {
    /** Add a label at [position] (the end of the order; the repository owns that arithmetic). */
    suspend fun insert(projectId: Long, name: String, position: Long)

    /** Rename. Naming rules live in `VocabularyRepository.rename`. */
    suspend fun update(id: Long, name: String)

    /** Move one row — only ever from inside the repository's whole-list reorder transaction. */
    suspend fun setPosition(id: Long, position: Long)

    suspend fun delete(id: Long)

    /** The label with this id *in this project*, or null — so a route can prove it belongs before it writes. */
    suspend fun findByIdInProject(id: Long, projectId: Long): VocabularyRecord?

    /** This project's labels, in position order. */
    suspend fun forProject(projectId: Long): List<VocabularyRecord>
}
