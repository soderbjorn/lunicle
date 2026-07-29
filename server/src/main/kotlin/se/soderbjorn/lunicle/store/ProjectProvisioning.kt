/**
 * The backend-agnostic seam for making, renaming, deleting and reordering a whole
 * *project* — the row plus the vocabulary a project cannot be usable without.
 *
 * The one LNL-122 addition to the store-interface set. Unlike the low-level
 * [ProjectStore], which inserts a bare project row, this owns the orchestration a
 * project needs to exist at all: seeding its labels, components, statuses,
 * priorities and resolutions, folding the name-uniqueness check the way a human
 * would, and unlinking attachment *files* on delete. That orchestration is exactly
 * what a store cannot express, and on SQLite it is one transaction across six
 * tables — see [se.soderbjorn.lunicle.ProjectRepository], the reference
 * implementation. The Firestore backend seeds the same defaults over the vocabulary
 * stores' `insert` — see [se.soderbjorn.lunicle.FirestoreProjectRepository].
 *
 * The interface exists so `Application.module` can hand the board routes one
 * project-provisioning collaborator whichever backend is selected, without the
 * routes knowing there is a database (or that there is not).
 *
 * @see se.soderbjorn.lunicle.ProjectRepository the SQLite reference implementation.
 * @see se.soderbjorn.lunicle.FirestoreProjectRepository the Firestore implementation.
 */
package se.soderbjorn.lunicle.store

import se.soderbjorn.lunicle.ProjectRecord

interface ProjectProvisioning {
    /**
     * Create a project and everything it needs to be usable — its row and its five
     * seeded vocabularies.
     *
     * Visibility is not a parameter, and not a column: who may see a project is its
     * audience rows in `project_audience_roles` (LNL-191). A newly created project
     * has none — it admits nobody but its owner until somebody says otherwise, which
     * is the safe default and the one a fresh row has always had.
     *
     * @throws se.soderbjorn.lunicle.ProjectConflict if the name or the prefix is taken, or either is blank.
     */
    suspend fun create(name: String, namePrefix: String): ProjectRecord

    /**
     * Rename or re-configure a project.
     *
     * @throws se.soderbjorn.lunicle.ProjectConflict if the new name or prefix belongs to a different project.
     */
    suspend fun update(id: Long, name: String, namePrefix: String): ProjectRecord

    /** Delete a project, everything in it, and every file behind any of it. */
    suspend fun delete(id: Long)

    /**
     * Put the instance's projects in the given order.
     *
     * @throws se.soderbjorn.lunicle.ProjectConflict if [ids] is not exactly the projects that exist.
     */
    suspend fun reorder(ids: List<Long>)
}
