/**
 * Relation kinds and the relations that use them (LNL-215).
 *
 * Stores only: SQL in, data class out. The rules — same project, no self-relation,
 * no duplicate pair in either direction, both issues published — live in
 * [IssueRepository], and the add/rename/reorder/delete rules for the kinds live in
 * [VocabularyRepository] with every other vocabulary's.
 *
 * @see IssueRelationKinds.sq
 * @see IssueRelations.sq
 * @see IssueRepository
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * One of a project's ways for two issues to be related.
 *
 * Its own record rather than a reuse of [StatusRecord] — which statuses, priorities
 * and resolutions all share — because it is genuinely a different shape: two names
 * and a flag against one name and a flag. Folding it in would put a nullable second
 * name on every board column that could only ever be null, which is the wrinkle
 * [SprintRecord] declined for one nullable timestamp.
 *
 * @property name the **from**-side label — "Blocked by". Read as a sentence about
 *   the issue a relation is stored *from*: "this issue is Blocked by that one".
 * @property inverseName the **to**-side label — "Blocks" — or null because the kind
 *   reads the same in both directions.
 *
 *   **Null is the whole encoding of symmetry.** There is no `isSymmetric` flag that
 *   could disagree with a stale second name, in the same idiom as a null
 *   `resolution_id` meaning "not in a closing column". Use [labelFrom] and [labelTo]
 *   rather than reading this directly, so the `?: name` fallback is spelled once.
 * @property marksBlocked whether an issue on the *from* side of one of these counts
 *   as blocked on the board. A flag rather than `name == "Blocked by"`, for
 *   `statuses.requires_resolution`'s reason: the seed names the row and an admin may
 *   rename it.
 *
 *   Note what it does **not** say: whether any given issue is blocked *right now*.
 *   That also requires the issue on the other end to still be open, which is a fact
 *   about that issue's status and cannot live here. See `BoardDependencies.blockedBy`.
 */
data class IssueRelationKindRecord(
    val id: Long,
    val projectId: Long,
    val name: String,
    val inverseName: String?,
    val marksBlocked: Boolean,
    val position: Long,
) {
    /** Whether this kind reads the same in both directions. */
    val isSymmetric: Boolean get() = inverseName == null

    /** What to call this link when read from the *from* issue's side. */
    val labelFrom: String get() = name

    /**
     * What to call this link when read from the *to* issue's side — the inverse name,
     * or the name again when the kind is symmetric. The one place that `?:` is
     * spelled, so no renderer has to remember it.
     */
    val labelTo: String get() = inverseName ?: name

    /** [labelFrom] or [labelTo], for a reader on the from side or not. */
    fun labelFor(isFromSide: Boolean): String = if (isFromSide) labelFrom else labelTo
}

/**
 * One link between two issues.
 *
 * Stored once, from → to, and rendered in both directions — see IssueRelations.sq
 * for why storing the inverse as a second row is the trap it looks like a
 * convenience. Which end a reader is on is decided by comparing their issue id
 * against [fromIssueId]; nothing on this record pre-computes it, because a record
 * that carried "my side" would be a record that only meant something to one caller.
 */
data class IssueRelationRecord(
    val id: Long,
    val projectId: Long,
    val fromIssueId: Long,
    val toIssueId: Long,
    val kindId: Long,
    val createdAt: Long,
) {
    /** The issue at the other end, seen from [issueId]. */
    fun otherThan(issueId: Long): Long = if (fromIssueId == issueId) toIssueId else fromIssueId
}

/** Reads and writes the `issue_relation_kinds` table. */
class IssueRelationKindStore(
    private val database: LunicleDatabase,
    /**
     * The relations using these kinds, so [delete] can sweep them explicitly.
     *
     * SQLite would cascade them for free — the composite key says so — and this is
     * here anyway, for the reason `IssueRepository.delete` sweeps an issue's children
     * by name: the Firestore implementation has no cascade at all, and a sweep the
     * reference backend leaves to the schema is a sweep the contract suite cannot
     * prove both backends do. Calling it on both is what keeps them identical.
     */
    private val relations: se.soderbjorn.lunicle.store.IssueRelationStore,
) : se.soderbjorn.lunicle.store.IssueRelationKindStore {
    override suspend fun insert(
        projectId: Long,
        name: String,
        position: Long,
        inverseName: String?,
        marksBlocked: Boolean,
    ): Unit = withContext(DatabaseDispatcher) {
        database.issueRelationKindsQueries.insert(projectId, name, inverseName, if (marksBlocked) 1L else 0L, position)
    }

    override suspend fun update(
        id: Long,
        name: String,
        inverseName: String?,
        marksBlocked: Boolean,
    ): Unit = withContext(DatabaseDispatcher) {
        database.issueRelationKindsQueries.update(name, inverseName, if (marksBlocked) 1L else 0L, id)
    }

    override suspend fun setPosition(id: Long, position: Long): Unit = withContext(DatabaseDispatcher) {
        database.issueRelationKindsQueries.setPosition(position, id)
    }

    /**
     * Delete a kind and the links that used it.
     *
     * The sweep first, then the row, in one transaction: the reverse order would
     * leave the sweep nothing to find if it were the half that ran. Both statements
     * are redundant against SQLite's own cascade and are here for the reason the
     * constructor's `relations` parameter gives.
     */
    override suspend fun delete(id: Long) {
        relations.deleteForKind(id)
        withContext(DatabaseDispatcher) {
            database.issueRelationKindsQueries.delete(id)
        }
    }

    override suspend fun findByIdInProject(id: Long, projectId: Long): IssueRelationKindRecord? =
        withContext(DatabaseDispatcher) {
            database.issueRelationKindsQueries.findByIdInProject(id, projectId).executeAsOneOrNull()?.toRecord()
        }

    override suspend fun forProject(projectId: Long): List<IssueRelationKindRecord> =
        withContext(DatabaseDispatcher) {
            database.issueRelationKindsQueries.forProject(projectId).executeAsList().map { it.toRecord() }
        }
}

/** Reads and writes the `issue_relations` table. */
class IssueRelationStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.IssueRelationStore {
    override suspend fun insert(
        projectId: Long,
        fromIssueId: Long,
        toIssueId: Long,
        kindId: Long,
        createdAt: Long?,
    ): Long = withContext(DatabaseDispatcher) {
        database.issueRelationsQueries
            .insert(projectId, fromIssueId, toIssueId, kindId, createdAt ?: now())
            .executeAsOne()
    }

    override suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.issueRelationsQueries.delete(id)
    }

    override suspend fun findById(id: Long): IssueRelationRecord? = withContext(DatabaseDispatcher) {
        database.issueRelationsQueries.findById(id).executeAsOneOrNull()?.toRecord()
    }

    override suspend fun forIssue(issueId: Long): List<IssueRelationRecord> = withContext(DatabaseDispatcher) {
        database.issueRelationsQueries.forIssue(issueId, issueId).executeAsList().map { it.toRecord() }
    }

    override suspend fun forProject(projectId: Long): List<IssueRelationRecord> = withContext(DatabaseDispatcher) {
        database.issueRelationsQueries.forProject(projectId).executeAsList().map { it.toRecord() }
    }

    override suspend fun usageByKind(projectId: Long): Map<Long, Long> = withContext(DatabaseDispatcher) {
        database.issueRelationsQueries.usageByKind(projectId).executeAsList()
            .associate { it.kind_id to it.uses }
    }

    override suspend fun deleteForIssue(issueId: Long): Unit = withContext(DatabaseDispatcher) {
        database.issueRelationsQueries.deleteForIssue(issueId, issueId)
    }

    /**
     * Every link using one kind, deleted.
     *
     * Redundant here — the composite foreign key is `ON DELETE CASCADE`, so these rows
     * would go a moment later anyway — and called regardless, so the SQLite and
     * Firestore kind stores run the same two steps. See IssueRelationKindStore's
     * constructor.
     *
     * No dedicated statement: `usageByKind` proves the rows are findable by kind, and
     * a `DELETE ... WHERE kind_id = ?` would be a fourth statement on this table that
     * exists only to be redundant. One read and a delete per row is a handful of
     * writes on a set that is small by construction — a project's links under one kind
     * — and it keeps the SQL surface honest about what actually needs to exist.
     */
    override suspend fun deleteForKind(kindId: Long): Unit = withContext(DatabaseDispatcher) {
        database.transaction {
            database.issueRelationsQueries.forKind(kindId).executeAsList().forEach {
                database.issueRelationsQueries.delete(it)
            }
        }
    }
}

private fun se.soderbjorn.lunicle.db.Issue_relation_kinds.toRecord() = IssueRelationKindRecord(
    id = id,
    projectId = project_id,
    name = name,
    inverseName = inverse_name,
    marksBlocked = marks_blocked != 0L,
    position = position,
)

private fun se.soderbjorn.lunicle.db.Issue_relations.toRecord() = IssueRelationRecord(
    id = id,
    projectId = project_id,
    fromIssueId = from_issue_id,
    toIssueId = to_issue_id,
    kindId = kind_id,
    createdAt = created_at,
)
