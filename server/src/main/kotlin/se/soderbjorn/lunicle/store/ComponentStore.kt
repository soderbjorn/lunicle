/**
 * The persistence seam for one project's components: a named, ordered vocabulary
 * a card may carry any number of — [LabelStore]'s twin in every respect but the
 * table it reads.
 *
 * One of the LNL-111 domain store interfaces. The reference implementation is the
 * SQLite gateway [se.soderbjorn.lunicle.ComponentStore] (named by its
 * fully-qualified name in that class's supertype clause, since the two share a
 * simple name). Kept a separate interface from [LabelStore] rather than folded
 * into one, to mirror the concrete shape and keep every call site naming the
 * vocabulary it means.
 *
 * The trimming, uniqueness and delete rules live one layer up in
 * `VocabularyRepository` and are backend-agnostic; the contract here is only what
 * round-trips.
 *
 * @see se.soderbjorn.lunicle.store.ComponentStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.VocabularyRecord

interface ComponentStore {
    /** Add a component at [position] (the end of the order; the repository owns that arithmetic). */
    suspend fun insert(projectId: Long, name: String, position: Long)

    /** Rename. Naming rules live in `VocabularyRepository.rename`. */
    suspend fun update(id: Long, name: String)

    /** Move one row — only ever from inside the repository's whole-list reorder transaction. */
    suspend fun setPosition(id: Long, position: Long)

    suspend fun delete(id: Long)

    /** The component with this id *in this project*, or null — so a route can prove it belongs before it writes. */
    suspend fun findByIdInProject(id: Long, projectId: Long): VocabularyRecord?

    /** This project's components, in position order. */
    suspend fun forProject(projectId: Long): List<VocabularyRecord>
}
