/**
 * The persistence seam for projects: the rows themselves, their feature and
 * requirement switches, their ordering, their active sprint, and their GitHub
 * repository configuration.
 *
 * One of the LNL-111 domain store interfaces. The reference implementation is the
 * SQLite gateway [se.soderbjorn.lunicle.ProjectStore] (named by its fully-qualified
 * name in that class's supertype clause, since the two share a simple name).
 *
 * This is the low-level project persistence; the validation, vocabulary seeding
 * and cascade-on-delete orchestration in `ProjectRepository` sits on top of it and
 * is backend-agnostic. So the contract here is about persistence: what round-trips,
 * what ordering `setOrder` produces, and that the repository config is stored and
 * read back as the same value.
 *
 * @see se.soderbjorn.lunicle.store.ProjectStoreContract
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.ProjectRecord
import se.soderbjorn.lunicle.RepositoryConfig

interface ProjectStore {
    /**
     * Insert a project row (no vocabulary seeding — that is the repository's job).
     *
     *
     * Visibility is not a parameter, and not a column: who may see a project is its
     * audience rows in `project_audience_roles` (LNL-191). A newly created project
     * has none — it admits nobody but its owner until somebody says otherwise, which
     * is the safe default and the one a fresh row has always had.
     */
    suspend fun insert(name: String, namePrefix: String): ProjectRecord

    suspend fun update(id: Long, name: String, namePrefix: String)

    /** Toggle the discussion-forum and private-message features. */
    suspend fun setFeatures(id: Long, discussionsEnabled: Boolean, messagesEnabled: Boolean)

    /**
     * Toggle whether a new issue must carry a label and/or a component, and whether
     * closing with a done resolution must carry a fixed version (LNL-134).
     */
    suspend fun setRequirements(
        id: Long,
        requireLabel: Boolean,
        requireComponent: Boolean,
        requireFixedVersionOnResolve: Boolean,
    )

    /**
     * Set both board-display settings: whether the board shows each card's author on
     * a muted footer line (LNL-157), and whether it hides the issue number (LNL-194).
     *
     * Per-project display settings, not requirements — how a shared board reads,
     * which is a decision about the project rather than about the person looking. The
     * second was a per-user preference until LNL-194.
     *
     * The pair together, never one at a time: the Board display group sends both.
     * Writing [hideIssueNumbers] is also what settles a project whose column is still
     * null — see [se.soderbjorn.lunicle.ProjectRecord.hideIssueNumbersStored].
     */
    suspend fun setBoardDisplay(id: Long, showIssueAuthor: Boolean, hideIssueNumbers: Boolean)

    suspend fun delete(id: Long)

    /** Put the whole project list in the given order. */
    suspend fun setOrder(ids: List<Long>)

    suspend fun findById(id: Long): ProjectRecord?

    suspend fun findByName(name: String): ProjectRecord?

    suspend fun selectAll(): List<ProjectRecord>

    /** Which sprint this project's board scopes to by default, or null. */
    suspend fun activeSprintId(id: Long): Long?

    suspend fun setActiveSprint(id: Long, sprintId: Long?)

    /** The linked GitHub repository and token source, or null when none is configured. */
    suspend fun repositoryConfig(id: Long): RepositoryConfig?

    suspend fun setRepositoryConfig(id: Long, config: RepositoryConfig)
}
