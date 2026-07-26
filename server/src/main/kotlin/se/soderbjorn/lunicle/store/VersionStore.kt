/**
 * The persistence seam for one project's release versions: a named, ordered
 * vocabulary an issue points at twice — as its planned version and as its fixed
 * version (LNL-134).
 *
 * One of the LNL-111 domain store interfaces, and — with labels and components —
 * one of the plainest: an id, a project, a name and a position, no per-kind extra.
 * A version's done-ness is not its own; it lives on the resolution. The reference
 * implementation is the SQLite gateway [se.soderbjorn.lunicle.VersionStore] (named
 * by its fully-qualified name in that class's supertype clause, since the two share
 * a simple name).
 *
 * This is the low-level persistence; the trimming, the case-insensitive uniqueness
 * and the delete rules live one layer up in `VocabularyRepository` and are
 * backend-agnostic. Deleting a version is never refused — both issue references are
 * SET NULL — so, like sprints, VERSION's `restrictsOnUse` is false.
 *
 * @see se.soderbjorn.lunicle.store.VersionStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.VocabularyRecord

interface VersionStore {
    /** Add a version at [position] (the end of the order; the repository owns that arithmetic). */
    suspend fun insert(projectId: Long, name: String, position: Long)

    /** Rename. Naming rules live in `VocabularyRepository.rename`. */
    suspend fun update(id: Long, name: String)

    /** Move one row — only ever from inside the repository's whole-list reorder transaction. */
    suspend fun setPosition(id: Long, position: Long)

    suspend fun delete(id: Long)

    /** The version with this id *in this project*, or null — so a route can prove it belongs before it writes. */
    suspend fun findByIdInProject(id: Long, projectId: Long): VocabularyRecord?

    /** This project's versions, in position order. */
    suspend fun forProject(projectId: Long): List<VocabularyRecord>
}
