/**
 * The persistence seam for discussion forums: the per-project rooms posts live
 * in, their order within a project, and the reads that prove a forum really
 * belongs to the project a URL claims it does.
 *
 * One of the LNL-111 domain store interfaces. The reference implementation is the
 * SQLite gateway [se.soderbjorn.lunicle.ForumStore] (named by its fully-qualified
 * name in that class's supertype clause, since the two share a simple name).
 *
 * This is the low-level forum persistence; the trimming, uniqueness checks,
 * position assignment and cascade-on-delete orchestration in `ForumRepository`
 * sit on top of it and are backend-agnostic. So the contract here is about
 * persistence: what round-trips, that `forProject` follows the order `setOrder`
 * writes, that the project-scoped reads refuse a mismatched pair, and that a
 * delete removes the row.
 *
 * @see se.soderbjorn.lunicle.store.ForumStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.ForumRecord

interface ForumStore {
    /** This project's forums, in stored order (0 first). */
    suspend fun forProject(projectId: Long): List<ForumRecord>

    /** One forum, proving it is this project's, or null when the pair does not match. */
    suspend fun findByIdInProject(id: Long, projectId: Long): ForumRecord?

    /** One forum by id alone, leaving the project check to the caller. */
    suspend fun findById(id: Long): ForumRecord?

    /** Append a forum to the end of its project's list. */
    suspend fun insert(projectId: Long, name: String, description: String?): ForumRecord

    suspend fun update(id: Long, name: String, description: String?)

    suspend fun delete(id: Long)

    /** Rewrite a whole project's forum order in one pass; [ids] names exactly its forums. */
    suspend fun setOrder(ids: List<Long>)
}
