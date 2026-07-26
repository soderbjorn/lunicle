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
     * [visibleToAllSignedIn] defaults false, the tier a project has until its owner
     * opts in (LNL-138) — so callers that predate the middle read tier need not
     * mention it, exactly as the row's own DEFAULT lets the SQL omit it.
     */
    suspend fun insert(
        name: String,
        namePrefix: String,
        isPublic: Boolean,
        visibleToAllSignedIn: Boolean = false,
    ): ProjectRecord

    suspend fun update(id: Long, name: String, namePrefix: String, isPublic: Boolean, visibleToAllSignedIn: Boolean)

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
     * Toggle whether the board shows each card's author on a muted footer line
     * (LNL-157) — a per-project display setting, not a requirement.
     */
    suspend fun setShowIssueAuthor(id: Long, showIssueAuthor: Boolean)

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
