/**
 * The persistence seam for links between issues (LNL-215).
 *
 * Stores only: one row per link, from → to, under one of the project's own relation
 * kinds. The reference implementation is the SQLite gateway
 * [se.soderbjorn.lunicle.IssueRelationStore] (named by its fully-qualified name in
 * that class's supertype clause, since the two share a simple name).
 *
 * **One row, rendered in both directions.** There is deliberately no "insert the
 * inverse too" method and no read that returns a pre-inverted pair: a link is one
 * fact, stored once, and which end you are looking at is decided where it is
 * rendered. Two stored rows would be two sources of truth that drift under partial
 * failure with nothing in either backend able to notice. See IssueRelations.sq.
 *
 * The rules — same project for both issues *and* the kind, no self-relation, no
 * duplicate pair in **either** direction under one kind, both issues published —
 * live one layer up in `IssueRepository` and are backend-agnostic. That placement
 * is not stylistic here: **Firestore cannot enforce uniqueness at all**, so the
 * repository check is the real guarantee on both backends and the SQLite index is a
 * backstop rather than the rule.
 *
 * @see se.soderbjorn.lunicle.store.IssueRelationStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.IssueRelationRecord

interface IssueRelationStore {
    /**
     * Write one link and return its id.
     *
     * @param createdAt when it was made, or null — every ordinary caller — for now.
     */
    suspend fun insert(
        projectId: Long,
        fromIssueId: Long,
        toIssueId: Long,
        kindId: Long,
        createdAt: Long? = null,
    ): Long

    /**
     * Remove one link, by id.
     *
     * By id, and not by "the relation between these two under this kind", because
     * that phrasing would have to say which direction it meant — which is precisely
     * the ambiguity the one-row rule exists to remove. The caller is looking at a
     * rendered row and has an id.
     */
    suspend fun delete(id: Long)

    suspend fun findById(id: Long): IssueRelationRecord?

    /**
     * Every link touching one issue, in **either** direction, oldest first.
     *
     * The reverse half — "who points at me" — is the whole reason this is one method
     * and not two: an issue's window shows both, and a caller that had to union two
     * reads would be a caller that could forget one. Ordered by id so a list does not
     * reshuffle itself between two loads.
     */
    suspend fun forIssue(issueId: Long): List<IssueRelationRecord>

    /**
     * Every link in one project — the board's blocked projection.
     *
     * Unfiltered by kind on purpose. The caller has already read the project's kinds
     * (it needs them for the picker anyway), so narrowing to the blocking ones is
     * free in Kotlin — where doing it here would mean either a variadic IN clause or
     * a join to the kinds table that the Firestore backend cannot make.
     */
    suspend fun forProject(projectId: Long): List<IssueRelationRecord>

    /**
     * How many links use each of this project's kinds, as kind id → count.
     *
     * Shown, never enforced: the delete cascades regardless, so this is a sentence
     * for the confirmation like a sprint's count rather than a gate like a status's.
     */
    suspend fun usageByKind(projectId: Long): Map<Long, Long>

    /**
     * Delete every link naming this issue, in either direction.
     *
     * Redundant on SQLite, where both references cascade, and load-bearing on
     * Firestore, which has no cascade at all — the relation documents would otherwise
     * outlive the issue, naming an id nothing resolves. The shape
     * [AttachmentStore.deleteForIssue] set and [IssueEventStore.deleteForIssue]
     * followed; called from the same backend-agnostic `IssueRepository.delete`.
     */
    suspend fun deleteForIssue(issueId: Long)

    /**
     * Delete every link using this kind — the cascade Firestore has to run by hand.
     *
     * Redundant on SQLite for `deleteForIssue`'s reason (the composite key is
     * `ON DELETE CASCADE`), and called by the kind store's own `delete` on both
     * backends so the two behave identically.
     */
    suspend fun deleteForKind(kindId: Long)
}
