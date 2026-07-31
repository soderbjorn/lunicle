/**
 * The IssueRelation contract, run against the SQLite reference implementation
 * ([se.soderbjorn.lunicle.IssueRelationStore]) — the mirror of
 * [FirestoreIssueRelationStoreContractTest] (LNL-215).
 *
 * Everything a link names is minted as a **real row**, because on this backend it has
 * to be: `issue_relations` has four foreign keys, one of them the composite
 * `(kind_id, project_id)` that is the whole structural gain of relation kinds being
 * vocabulary rather than an enum. A synthetic id would be refused by the database, and
 * that refusal is the feature.
 *
 * The seeding goes through the low-level stores rather than `ProjectRepository` —
 * which would seed a whole default board, three relation kinds included — because the
 * contract's [newKind] hook wants kinds it can count, and a project arriving with
 * three of them already would make "another kind in this project" ambiguous. A status
 * and a priority are still needed: an issue's `(status_id, project_id)` and
 * `(priority_id, project_id)` keys have to point somewhere.
 *
 * The concrete gateways share their simple names with the [IssueRelationStore] and
 * [IssueRelationKindStore] interfaces, so both are constructed by their
 * fully-qualified names; `store` and every other reference in this package is the
 * interface.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.AfterTest
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.PriorityStore
import se.soderbjorn.lunicle.ProjectStore
import se.soderbjorn.lunicle.StatusStore

class SqlDelightIssueRelationStoreContractTest : IssueRelationStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val projects = ProjectStore(db)
    private val statuses = StatusStore(db)
    private val priorities = PriorityStore(db)
    private val issues = se.soderbjorn.lunicle.IssueStore(db)

    override val store: IssueRelationStore = se.soderbjorn.lunicle.IssueRelationStore(db)

    /** The kind gateway, which is how [newKind] mints a row the composite key accepts. */
    private val kinds: IssueRelationKindStore = se.soderbjorn.lunicle.IssueRelationKindStore(db, store)

    private var seq = 0

    /** The leftmost status and the priority of each project, so [newIssue] can name them. */
    private val boards = mutableMapOf<Long, Pair<Long, Long>>()

    override suspend fun newProject(): Long {
        val projectId = projects.insert("Project $seq", "RL${seq++}").id
        statuses.insert(projectId, "New", 0, requiresResolution = false)
        priorities.insert(projectId, "Normal", 0)
        boards[projectId] = statuses.forProject(projectId).first().id to priorities.forProject(projectId).first().id
        return projectId
    }

    /**
     * A draft, which is a perfectly real `issues` row — publishing it would add
     * nothing a relation's foreign keys care about, and this contract is about the
     * links rather than about what may be linked. Whether a *draft* may be linked at
     * all is `IssueRepository.addRelation`'s rule, and is asserted where that rule
     * lives.
     */
    override suspend fun newIssue(projectId: Long): Long {
        val (statusId, priorityId) = boards.getValue(projectId)
        return issues.insertDraft(projectId, "Issue", statusId, priorityId, Author.Nobody).first
    }

    override suspend fun newKind(projectId: Long): Long {
        val position = kinds.forProject(projectId).size.toLong()
        kinds.insert(projectId, "Kind $position", position)
        return kinds.forProject(projectId).last().id
    }

    @AfterTest
    fun tearDown() = fixture.close()
}
