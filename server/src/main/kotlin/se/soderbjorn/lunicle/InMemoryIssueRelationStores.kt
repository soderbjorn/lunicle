/**
 * A relation store and a relation-kind store that remember nothing beyond this
 * process — the default a [BoardDependencies] gets when nobody wired real ones
 * (LNL-215).
 *
 * ── Why a forgetful object rather than a nullable ───────────────────────────
 *
 * Exactly [InMemoryInstanceSettingsStore]'s bargain, and for its reason. The two
 * relation stores are read on the hottest path in the server — `buildBoard` asks for
 * a project's links on every board paint, and `buildIssueDetail` asks for an issue's
 * on every open — so making them nullable would put a `?.` and an `.orEmpty()` at
 * every one of those call sites, forever, to serve a case that only arises in a test
 * that has no interest in relations at all.
 *
 * An empty store answers the *right* thing instead of nothing: no links, no blocked
 * cards, no relation kinds. That is not a degraded reading — it is precisely the
 * state of a project nobody has linked anything in, which is most of them. So a
 * component assembled without these behaves like a real deployment whose boards
 * happen to be unlinked, rather than like a component with a hole in it.
 *
 * ── Where the real ones come from ───────────────────────────────────────────
 *
 * [Application.module] always passes the backend's own — SQLite's
 * [IssueRelationStore] / [IssueRelationKindStore], or their Firestore twins — because
 * a feature that forgot itself on redeploy is a feature nobody could trust. These are
 * reached only by a test that assembled a route bundle for an unrelated question, and
 * by nothing in production. A test that *is* about relations wires the real pair.
 *
 * They are deliberately not general-purpose fakes: there is no ordering guarantee
 * worth relying on beyond insertion order, no id reuse policy, and no attempt to
 * enforce the rules `IssueRepository` owns. Anything that needs those needs the real
 * store and the contract suite that pins it.
 *
 * @see InMemoryInstanceSettingsStore
 */
package se.soderbjorn.lunicle

import java.util.concurrent.atomic.AtomicLong

/** An [se.soderbjorn.lunicle.store.IssueRelationStore] backed by a list. See this file's preamble. */
class InMemoryIssueRelationStore(
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.IssueRelationStore {
    private val rows = mutableListOf<IssueRelationRecord>()
    private val nextId = AtomicLong(1)

    override suspend fun insert(
        projectId: Long,
        fromIssueId: Long,
        toIssueId: Long,
        kindId: Long,
        createdAt: Long?,
    ): Long {
        val id = nextId.getAndIncrement()
        rows += IssueRelationRecord(id, projectId, fromIssueId, toIssueId, kindId, createdAt ?: now())
        return id
    }

    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun findById(id: Long): IssueRelationRecord? = rows.firstOrNull { it.id == id }

    /** Both directions, as the real stores do — see IssueRelations.sq's `forIssue`. */
    override suspend fun forIssue(issueId: Long): List<IssueRelationRecord> =
        rows.filter { it.fromIssueId == issueId || it.toIssueId == issueId }.sortedBy { it.id }

    override suspend fun forProject(projectId: Long): List<IssueRelationRecord> =
        rows.filter { it.projectId == projectId }

    override suspend fun usageByKind(projectId: Long): Map<Long, Long> =
        rows.filter { it.projectId == projectId }.groupingBy { it.kindId }.eachCount()
            .mapValues { (_, count) -> count.toLong() }

    override suspend fun deleteForIssue(issueId: Long) {
        rows.removeAll { it.fromIssueId == issueId || it.toIssueId == issueId }
    }

    override suspend fun deleteForKind(kindId: Long) {
        rows.removeAll { it.kindId == kindId }
    }
}

/**
 * An [se.soderbjorn.lunicle.store.IssueRelationKindStore] backed by a list.
 *
 * Takes the relation store for the same reason the two real implementations do: its
 * [delete] sweeps the links that used the kind, so that the cascade behaves the same
 * whichever store is in play rather than being a property only SQLite's schema has.
 */
class InMemoryIssueRelationKindStore(
    private val relations: se.soderbjorn.lunicle.store.IssueRelationStore,
) : se.soderbjorn.lunicle.store.IssueRelationKindStore {
    private val rows = mutableListOf<IssueRelationKindRecord>()
    private val nextId = AtomicLong(1)

    override suspend fun insert(
        projectId: Long,
        name: String,
        position: Long,
        inverseName: String?,
        marksBlocked: Boolean,
    ) {
        rows += IssueRelationKindRecord(
            nextId.getAndIncrement(), projectId, name, inverseName, marksBlocked, position,
        )
    }

    override suspend fun update(id: Long, name: String, inverseName: String?, marksBlocked: Boolean) {
        replace(id) { it.copy(name = name, inverseName = inverseName, marksBlocked = marksBlocked) }
    }

    override suspend fun setPosition(id: Long, position: Long) {
        replace(id) { it.copy(position = position) }
    }

    override suspend fun delete(id: Long) {
        relations.deleteForKind(id)
        rows.removeAll { it.id == id }
    }

    override suspend fun findByIdInProject(id: Long, projectId: Long): IssueRelationKindRecord? =
        rows.firstOrNull { it.id == id && it.projectId == projectId }

    override suspend fun forProject(projectId: Long): List<IssueRelationKindRecord> =
        rows.filter { it.projectId == projectId }.sortedBy { it.position }

    private fun replace(id: Long, block: (IssueRelationKindRecord) -> IssueRelationKindRecord) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = block(rows[index])
    }
}
