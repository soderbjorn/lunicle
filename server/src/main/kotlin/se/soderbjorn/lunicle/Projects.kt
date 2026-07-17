/**
 * The projects table, and the per-project vocabularies that hang off it.
 *
 * Four stores, all of them the same shape as [UserStore] and [SessionStore]:
 * one class per table, SQL in, data class out, no decisions. Everything that
 * *decides* — trimming, casing, uniqueness, seeding — lives in
 * [ProjectRepository], one layer up. That split is the whole of the schema
 * doc's §1: a route never mentions a transaction, and a store never mentions a
 * rule.
 *
 * @see ProjectRepository
 * @see Database
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * A project as this server knows it.
 *
 * @property namePrefix the "FOO" in FOO-123. Unique across all projects, so a
 *   ticket reference in a commit message names exactly one issue.
 * @property isPublic whether a caller with no session at all may read this
 *   project's issues. The one rule that says yes to nobody; see
 *   [AccessControl.canReadProject].
 */
data class ProjectRecord(
    val id: Long,
    val name: String,
    val namePrefix: String,
    val isPublic: Boolean,
    val createdAt: Long,
)

/** A label or a component: an id, a project, a name. */
data class VocabularyRecord(
    val id: Long,
    val projectId: Long,
    val name: String,
)

/** A board column. */
data class StatusRecord(
    val id: Long,
    val projectId: Long,
    val name: String,
    val position: Long,
    /**
     * Whether landing in this column demands a resolution — the "magic" in
     * "Closed is a magic status", read from the database rather than from the
     * name. See Statuses.sq.
     *
     * Always false for a priority or a resolution, which reuse this type: the
     * column only exists on `statuses`, and neither of the other two stores has
     * anything to put here. That is the one wrinkle in sharing the type, and it
     * is cheaper than two more near-identical records.
     */
    val requiresResolution: Boolean = false,
)

/** Reads and writes the projects table. */
class ProjectStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Insert a project and return it.
     *
     * Callers should be going through [ProjectRepository.create] instead: a
     * project inserted here alone has no labels, no components and no board
     * columns, which is a project you cannot file an issue in and cannot fix
     * from the UI.
     */
    suspend fun insert(name: String, namePrefix: String, isPublic: Boolean): ProjectRecord =
        withContext(DatabaseDispatcher) {
            database.projectsQueries
                .insert(name, namePrefix, if (isPublic) 1L else 0L, now())
                .executeAsOne()
                .let { ProjectRecord(it.id, it.name, it.name_prefix, it.is_public != 0L, it.created_at) }
        }

    suspend fun update(id: Long, name: String, namePrefix: String, isPublic: Boolean): Unit =
        withContext(DatabaseDispatcher) {
            database.projectsQueries.update(name, namePrefix, if (isPublic) 1L else 0L, id)
        }

    /** Delete the project. Every row that hangs off it cascades; the files do not — see [IssueRepository.deleteProject]. */
    suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.projectsQueries.delete(id)
    }

    suspend fun findById(id: Long): ProjectRecord? = withContext(DatabaseDispatcher) {
        database.projectsQueries.findById(id).executeAsOneOrNull()
            ?.let { ProjectRecord(it.id, it.name, it.name_prefix, it.is_public != 0L, it.created_at) }
    }

    /** Case-insensitive by way of the column's `COLLATE NOCASE`; see Projects.sq. */
    suspend fun findByName(name: String): ProjectRecord? = withContext(DatabaseDispatcher) {
        database.projectsQueries.findByName(name).executeAsOneOrNull()
            ?.let { ProjectRecord(it.id, it.name, it.name_prefix, it.is_public != 0L, it.created_at) }
    }

    /**
     * Every project, unfiltered.
     *
     * Filtering by who may see what is [AccessControl]'s job, and the route's
     * to apply — see `projectRoutes`. A store that filtered would be a second
     * place permissions live, which is the thing §2 exists to prevent.
     */
    suspend fun selectAll(): List<ProjectRecord> = withContext(DatabaseDispatcher) {
        database.projectsQueries.selectAll().executeAsList()
            .map { ProjectRecord(it.id, it.name, it.name_prefix, it.is_public != 0L, it.created_at) }
    }
}

/** Reads and writes the labels table. */
class LabelStore(private val database: LunicleDatabase) {
    suspend fun insert(projectId: Long, name: String): Unit = withContext(DatabaseDispatcher) {
        database.labelsQueries.insert(projectId, name)
    }

    /** Rename. Callers should be going through [VocabularyRepository.rename], which owns the naming rules. */
    suspend fun update(id: Long, name: String): Unit = withContext(DatabaseDispatcher) {
        database.labelsQueries.update(name, id)
    }

    suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.labelsQueries.delete(id)
    }

    /**
     * The label with this id *in this project*, or null.
     *
     * Scoped rather than by id alone, so a route can prove the row it was handed
     * belongs to the project in its path before it writes. Every vocabulary store
     * below repeats it; see Labels.sq's `findByIdInProject`.
     */
    suspend fun findByIdInProject(id: Long, projectId: Long): VocabularyRecord? =
        withContext(DatabaseDispatcher) {
            database.labelsQueries.findByIdInProject(id, projectId).executeAsOneOrNull()
                ?.let { VocabularyRecord(it.id, it.project_id, it.name) }
        }

    suspend fun forProject(projectId: Long): List<VocabularyRecord> = withContext(DatabaseDispatcher) {
        database.labelsQueries.forProject(projectId).executeAsList()
            .map { VocabularyRecord(it.id, it.project_id, it.name) }
    }
}

/** Reads and writes the components table. */
class ComponentStore(private val database: LunicleDatabase) {
    suspend fun insert(projectId: Long, name: String): Unit = withContext(DatabaseDispatcher) {
        database.componentsQueries.insert(projectId, name)
    }

    suspend fun update(id: Long, name: String): Unit = withContext(DatabaseDispatcher) {
        database.componentsQueries.update(name, id)
    }

    suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.componentsQueries.delete(id)
    }

    suspend fun findByIdInProject(id: Long, projectId: Long): VocabularyRecord? =
        withContext(DatabaseDispatcher) {
            database.componentsQueries.findByIdInProject(id, projectId).executeAsOneOrNull()
                ?.let { VocabularyRecord(it.id, it.project_id, it.name) }
        }

    suspend fun forProject(projectId: Long): List<VocabularyRecord> = withContext(DatabaseDispatcher) {
        database.componentsQueries.forProject(projectId).executeAsList()
            .map { VocabularyRecord(it.id, it.project_id, it.name) }
    }
}

/**
 * Reads and writes the priorities table.
 *
 * [StatusRecord]'s shape fits exactly — an id, a project, a name, a position —
 * so it is reused rather than copied into a PriorityRecord that would differ
 * only in its name. The two tables are twins on purpose; see Priorities.sq.
 */
class PriorityStore(private val database: LunicleDatabase) {
    suspend fun insert(projectId: Long, name: String, position: Long): Unit =
        withContext(DatabaseDispatcher) {
            database.prioritiesQueries.insert(projectId, name, position)
        }

    suspend fun update(id: Long, name: String): Unit = withContext(DatabaseDispatcher) {
        database.prioritiesQueries.update(name, id)
    }

    /**
     * Move one row in the order.
     *
     * A whole project's positions are rewritten together, and *that* is why this
     * is not the interesting method — [VocabularyRepository.reorder] is, because
     * it owns the transaction. A caller that used this alone would leave two
     * priorities sharing a position, which the schema permits and the board would
     * then order arbitrarily.
     */
    suspend fun setPosition(id: Long, position: Long): Unit = withContext(DatabaseDispatcher) {
        database.prioritiesQueries.setPosition(position, id)
    }

    /** Refused by the database while any issue holds it. See [VocabularyRepository.delete]. */
    suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.prioritiesQueries.delete(id)
    }

    suspend fun findByIdInProject(id: Long, projectId: Long): StatusRecord? =
        withContext(DatabaseDispatcher) {
            database.prioritiesQueries.findByIdInProject(id, projectId).executeAsOneOrNull()
                ?.let { StatusRecord(it.id, it.project_id, it.name, it.position) }
        }

    suspend fun forProject(projectId: Long): List<StatusRecord> = withContext(DatabaseDispatcher) {
        database.prioritiesQueries.forProject(projectId).executeAsList()
            .map { StatusRecord(it.id, it.project_id, it.name, it.position) }
    }

    /**
     * What a new issue gets: the middle of the scale, not the top.
     *
     * Read rather than hardcoded to "Normal", for [StatusStore.firstForProject]'s
     * reason. See Priorities.sq's `defaultForProject` for why it is the middle.
     */
    suspend fun defaultForProject(projectId: Long): StatusRecord? = withContext(DatabaseDispatcher) {
        database.prioritiesQueries.defaultForProject(projectId, projectId).executeAsOneOrNull()
            ?.let { StatusRecord(it.id, it.project_id, it.name, it.position) }
    }
}

/**
 * Reads and writes the resolutions table.
 *
 * [StatusRecord] again, for [PriorityStore]'s reason — an id, a project, a name,
 * a position. The three tables are triplets; see Resolutions.sq.
 */
class ResolutionStore(private val database: LunicleDatabase) {
    suspend fun insert(projectId: Long, name: String, position: Long): Unit =
        withContext(DatabaseDispatcher) {
            database.resolutionsQueries.insert(projectId, name, position)
        }

    suspend fun update(id: Long, name: String): Unit = withContext(DatabaseDispatcher) {
        database.resolutionsQueries.update(name, id)
    }

    /** See [PriorityStore.setPosition] — only ever called from inside `reorder`'s transaction. */
    suspend fun setPosition(id: Long, position: Long): Unit = withContext(DatabaseDispatcher) {
        database.resolutionsQueries.setPosition(position, id)
    }

    suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.resolutionsQueries.delete(id)
    }

    suspend fun findByIdInProject(id: Long, projectId: Long): StatusRecord? =
        withContext(DatabaseDispatcher) {
            database.resolutionsQueries.findByIdInProject(id, projectId).executeAsOneOrNull()
                ?.let { StatusRecord(it.id, it.project_id, it.name, it.position) }
        }

    suspend fun forProject(projectId: Long): List<StatusRecord> = withContext(DatabaseDispatcher) {
        database.resolutionsQueries.forProject(projectId).executeAsList()
            .map { StatusRecord(it.id, it.project_id, it.name, it.position) }
    }
}

/** Reads and writes the statuses table. */
class StatusStore(private val database: LunicleDatabase) {
    suspend fun insert(
        projectId: Long,
        name: String,
        position: Long,
        requiresResolution: Boolean = false,
    ): Unit = withContext(DatabaseDispatcher) {
        database.statusesQueries.insert(projectId, name, position, if (requiresResolution) 1L else 0L)
    }

    /**
     * Rename, and set the closing flag.
     *
     * Both in one write, because they are one decision — see Statuses.sq's
     * `update`. [requiresResolution] is the "magic" in "Closed is a magic status",
     * and it is *this* method that lets an admin move that magic to a column of
     * their own naming rather than being stuck with whatever the seed called it.
     */
    suspend fun update(id: Long, name: String, requiresResolution: Boolean): Unit =
        withContext(DatabaseDispatcher) {
            database.statusesQueries.update(name, if (requiresResolution) 1L else 0L, id)
        }

    /** See [PriorityStore.setPosition] — only ever called from inside `reorder`'s transaction. */
    suspend fun setPosition(id: Long, position: Long): Unit = withContext(DatabaseDispatcher) {
        database.statusesQueries.setPosition(position, id)
    }

    /** Refused by the database while any issue sits in it. See [VocabularyRepository.delete]. */
    suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.statusesQueries.delete(id)
    }

    suspend fun findByIdInProject(id: Long, projectId: Long): StatusRecord? =
        withContext(DatabaseDispatcher) {
            database.statusesQueries.findByIdInProject(id, projectId).executeAsOneOrNull()
                ?.let { StatusRecord(it.id, it.project_id, it.name, it.position, it.requires_resolution != 0L) }
        }

    suspend fun forProject(projectId: Long): List<StatusRecord> = withContext(DatabaseDispatcher) {
        database.statusesQueries.forProject(projectId).executeAsList()
            .map { StatusRecord(it.id, it.project_id, it.name, it.position, it.requires_resolution != 0L) }
    }

    /**
     * The leftmost column, which is where a new issue lands.
     *
     * Read rather than hardcoded to "New": the seed names it, and a project
     * whose statuses were renamed should still be able to take an issue.
     */
    suspend fun firstForProject(projectId: Long): StatusRecord? = withContext(DatabaseDispatcher) {
        database.statusesQueries.firstForProject(projectId).executeAsOneOrNull()
            ?.let { StatusRecord(it.id, it.project_id, it.name, it.position, it.requires_resolution != 0L) }
    }
}
