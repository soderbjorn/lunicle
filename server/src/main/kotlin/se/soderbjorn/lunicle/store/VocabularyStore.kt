/**
 * The persistence seam for a project's editable vocabularies — the five that define
 * what the board is, plus sprints, versions and relation kinds.
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
     * @param inverseName a relation kind's to-side label, or null for a symmetric one
     *   (LNL-215). Ignored for every other kind, like the flags on [rename] and for
     *   the same reason. Accepted here rather than only on the rename that follows —
     *   unlike a status's closing flag — because the MCP tool creates a kind in one
     *   call, and a kind whose opposite could only be named by a second write would be
     *   briefly and visibly symmetric when it is not.
     * @param marksBlocked a relation kind's blocking flag. Defaults false: it decides
     *   which cards go grey on everybody's board, so arming it is a deliberate act.
     * @throws se.soderbjorn.lunicle.VocabularyConflict if the name is blank, or is
     *   already taken by another row of this kind — which for a relation kind means
     *   taken by either of another row's two labels.
     */
    suspend fun add(
        projectId: Long,
        kind: VocabularyKind,
        name: String,
        inverseName: String? = null,
        marksBlocked: Boolean = false,
    ): VocabularyRow

    /**
     * Rename a row, and set its per-kind extras: a status's closing flag
     * ([requiresResolution]), a resolution's done flag ([isDone]), or a relation
     * kind's opposite label and blocking flag ([inverseName], [marksBlocked]). Each is
     * ignored for the kinds it does not belong to, rather than refused — the dialog
     * sends back the row it is rendering, and this layer owns knowing which extras a
     * kind carries.
     *
     * A blank [inverseName] normalises to null rather than being stored, so "I cleared
     * the field" and "I ticked same-in-both-directions" cannot become two stored states
     * that render identically. Null IS symmetry; see IssueRelationKinds.sq.
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
        inverseName: String? = null,
        marksBlocked: Boolean = false,
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
