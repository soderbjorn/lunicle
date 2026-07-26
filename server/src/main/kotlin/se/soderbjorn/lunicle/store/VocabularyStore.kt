/**
 * The persistence seam for a project's five (plus sprints) editable vocabularies.
 *
 * One of the LNL-111 domain store interfaces. Its name does not collide with an
 * existing class: the reference implementation is
 * [se.soderbjorn.lunicle.VocabularyRepository], which implements this directly.
 *
 * This is the seam with the richest parity surface, and the reason the contract
 * suite matters most here: ordering, case-insensitive uniqueness, and the delete
 * refusals (last load-bearing row, in-use row) are exactly the semantics a
 * document backend is most likely to get subtly different from SQL. The refusals
 * are signalled the same way in every backend — [se.soderbjorn.lunicle.VocabularyConflict]
 * for a name clash, [se.soderbjorn.lunicle.VocabularyRefusal] otherwise.
 *
 * @see se.soderbjorn.lunicle.store.VocabularyStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.VocabularyRow
import se.soderbjorn.lunicle.clientserver.VocabularyKind

interface VocabularyStore {
    /** Every row of one kind, in render order, each with its usage count. */
    suspend fun rows(projectId: Long, kind: VocabularyKind): List<VocabularyRow>

    /** The row with this id *in this project*, or null — project-scoped on purpose. */
    suspend fun find(projectId: Long, kind: VocabularyKind, id: Long): VocabularyRow?

    /**
     * Add a row at the end of the order.
     *
     * @throws se.soderbjorn.lunicle.VocabularyConflict if the name is blank or already this project's.
     */
    suspend fun add(projectId: Long, kind: VocabularyKind, name: String): VocabularyRow

    /**
     * Rename a row, and set its per-kind flag: a status's closing flag
     * ([requiresResolution]) or a resolution's done flag ([isDone]). Each is
     * ignored for the kinds it does not belong to, rather than refused — the dialog
     * sends back the row it is rendering, and this layer owns knowing which flag a
     * kind carries.
     *
     * @throws se.soderbjorn.lunicle.VocabularyConflict if the name is blank or another row's.
     */
    suspend fun rename(
        projectId: Long,
        kind: VocabularyKind,
        row: VocabularyRow,
        name: String,
        requiresResolution: Boolean,
        isDone: Boolean,
    )

    /**
     * Delete a row, or refuse.
     *
     * @throws se.soderbjorn.lunicle.VocabularyRefusal if the row is in use, or is the last of a
     *   kind a project cannot be without.
     */
    suspend fun delete(projectId: Long, kind: VocabularyKind, row: VocabularyRow)

    /**
     * Put a whole vocabulary in the order given, atomically; positions rewritten
     * 0..n-1 (which also repairs gaps left by a deleted middle row).
     *
     * @throws se.soderbjorn.lunicle.VocabularyRefusal if [ids] is not exactly this vocabulary.
     */
    suspend fun reorder(projectId: Long, kind: VocabularyKind, ids: List<Long>)
}
