/**
 * The persistence seam for one project's relation kinds: the ways two issues here
 * can be said to be related (LNL-215).
 *
 * The eighth typed vocabulary store, and the richest row of the lot — two names and
 * a flag, where a status has one name and a flag and a label has just the name. The
 * reference implementation is the SQLite gateway
 * [se.soderbjorn.lunicle.IssueRelationKindStore] (named by its fully-qualified name
 * in that class's supertype clause, since the two share a simple name).
 *
 * What sets it apart from [StatusStore] is the nullable second name. A null
 * `inverseName` means the kind reads the same in **both directions** — "Related to"
 * — and that absence is the whole encoding of symmetry: there is no separate flag
 * that could disagree with it, exactly as a null `resolution_id` is the whole
 * encoding of "not closed". Every renderer resolves the to-side label as
 * `inverseName ?: name`.
 *
 * The trimming, uniqueness and delete rules live one layer up in
 * `VocabularyRepository` and are backend-agnostic — including the one rule that is
 * wider here than for any other kind: a name must not collide with another kind's
 * *inverse* name either, so the picker can never offer two identical labels.
 *
 * @see se.soderbjorn.lunicle.store.VocabularyStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.IssueRelationKindRecord

interface IssueRelationKindStore {
    /**
     * Add a kind at [position].
     *
     * @param inverseName the to-side label, or null for a kind that reads the same
     *   in both directions.
     * @param marksBlocked whether an issue on the *from* side counts as blocked.
     *   Defaulted false, and the default matters: this is the most consequential
     *   switch here — it decides which cards go grey on everybody's board — so
     *   arming it has to be a deliberate act, exactly as a new status never demands
     *   a resolution.
     */
    suspend fun insert(
        projectId: Long,
        name: String,
        position: Long,
        inverseName: String? = null,
        marksBlocked: Boolean = false,
    )

    /**
     * Rename, set the inverse name, and set the blocking flag — all three in one
     * write, because they are one decision. Separate writes could each fail on their
     * own and leave a kind renamed but still marking cards blocked.
     */
    suspend fun update(id: Long, name: String, inverseName: String?, marksBlocked: Boolean)

    /** Move one row — only ever from inside the repository's whole-list reorder transaction. */
    suspend fun setPosition(id: Long, position: Long)

    /**
     * Delete a kind, taking the relations that used it with it.
     *
     * A cascade rather than a refusal or a release, and the three answers differ for
     * real reasons: a status cannot go while issues sit in it, a version releases the
     * issues that named it, and a relation row without its kind is *nothing at all* —
     * two issue ids and no statement about them. The count is still shown before the
     * fact; see `IssueRelationStore.usageByKind`.
     *
     * On SQLite the cascade is the schema's. On Firestore, which has none, the
     * implementation sweeps the relations itself — which is exactly the parity the
     * contract suite exists to prove.
     */
    suspend fun delete(id: Long)

    /** The kind with this id *in this project*, or null — so a route can prove it belongs before it writes. */
    suspend fun findByIdInProject(id: Long, projectId: Long): IssueRelationKindRecord?

    /** This project's relation kinds, in render order. */
    suspend fun forProject(projectId: Long): List<IssueRelationKindRecord>
}
