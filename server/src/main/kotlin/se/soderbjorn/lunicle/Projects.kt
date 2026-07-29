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
 * Whether a project offers discussions or private messages — retired, and so
 * false for every project on every backend (LNL-190).
 *
 * Both features were too unfinished to carry through the permission rework, and
 * between them they accounted for seven access rules and fifteen MCP tools that
 * the rework would otherwise have had to keep coherent. Rather than migrate the
 * data, every store maps the stored column onto this constant: a SQLite row or a
 * Firestore document written when the switches still existed reads as off, and no
 * deployment needs a roll-forward to make that true.
 *
 * Nothing was deleted for it. The tables, the routes, the stores and the views all
 * still stand, so finishing discussions later means putting the column read back
 * where this constant is and restoring the two switches in the project settings
 * dialog — not an excavation.
 */
internal const val PROJECT_FORUM_FEATURES_ENABLED = false

/**
 * A project as this server knows it.
 *
 * @property namePrefix the "FOO" in FOO-123. Unique across all projects, so a
 *   ticket reference in a commit message names exactly one issue.
 * @property isPublic whether a caller with no session at all may read this
 *   project's issues. The one rule that says yes to nobody; see
 *   [AccessControl.canReadProject].
 * @property visibleToAllSignedIn whether any signed-in account may read this
 *   project — the middle read tier between members-only and [isPublic] (LNL-138).
 *   Grants reading only; every write gate stays membership-scoped, so a project
 *   with this on is read-only to the signed-in users it admits. ORs with [isPublic]
 *   in [AccessControl.canReadProject], so "public" already implies it.
 */
data class ProjectRecord(
    val id: Long,
    val name: String,
    val namePrefix: String,
    val isPublic: Boolean,
    val visibleToAllSignedIn: Boolean,
    /**
     * Whether this project offers a discussion forum and private messages —
     * independent per-project switches a project administrator sets (LNL-96).
     *
     * Carried on the record, unlike the repository and active-sprint fields that
     * have their own queries: these two reach [ProjectSummary] and ride to the tab
     * shell on every board, so every read that builds a summary needs them in
     * hand. Both default enabled — the state every project had before the columns
     * existed. See Projects.sq.
     *
     * Since LNL-190 neither is read from its column: every store fills both from
     * [PROJECT_FORUM_FEATURES_ENABLED], so both are always false. The columns and
     * the setter are still there, untouched, for whoever re-enables discussions.
     */
    val discussionsEnabled: Boolean,
    val messagesEnabled: Boolean,
    /**
     * Whether filing a new ticket here must carry a label, and whether it must
     * carry a component — two switches a project administrator sets independently
     * (LNL-106). On the record for the forum flags' reason: they ride to
     * [ProjectSummary] and the issue editor reads them off the board it already
     * loads. Both default off. See Projects.sq.
     */
    val requireLabel: Boolean,
    val requireComponent: Boolean,
    /**
     * Whether closing an issue with a *done* resolution must carry a fixed version
     * (LNL-134). A third requirement flag beside the two above, on the record for
     * their reason — it reaches [ProjectSummary] so the board and the resolution
     * dialog read it off what they already load. Default off. See Projects.sq.
     */
    val requireFixedVersionOnResolve: Boolean,
    /**
     * Whether the board shows each card's author on a muted footer line (LNL-157).
     * A per-project display setting a project administrator flips — not a
     * requirement like the three above. On the record for their reason: it reaches
     * [ProjectSummary] so the board reads it off what it already loads and the card
     * render gates on it. Default off. See Projects.sq.
     */
    val showIssueAuthor: Boolean,
    val createdAt: Long,
)

/** A label or a component: an id, a project, a name, and where it sits. */
data class VocabularyRecord(
    val id: Long,
    val projectId: Long,
    val name: String,
    /**
     * Its place in this project's list, 0 first — the same convention statuses
     * and priorities use, and for the same reason: the order these appear in on
     * a card and in a picker is the project owner's decision, not the alphabet's.
     * See Labels.sq.
     */
    val position: Long,
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
    /**
     * Whether this resolution means the work was actually done (LNL-134) —
     * "Done" is, "Duplicate" is not. Its mirror-image wrinkle to [requiresResolution]:
     * meaningful only on `resolutions`, always false for a status or a priority
     * that reuse this type. It is what "require a fixed version when resolving"
     * consults. See Resolutions.sq's is_done.
     */
    val isDone: Boolean = false,
)

/**
 * A timebox.
 *
 * Its own record rather than a sixth reuse of [StatusRecord], unlike the
 * priorities and resolutions that share it: [completedAt] is a column no status
 * has, and folding it in would put a nullable timestamp on every board column
 * that could only ever be null. The sharing above is cheap because those three
 * really are the same shape; this one is not.
 */
data class SprintRecord(
    val id: Long,
    val projectId: Long,
    val name: String,
    val position: Long,
    /**
     * When this was completed, or null because it has not been. See Sprints.sq
     * for why an instant rather than a flag.
     */
    val completedAt: Long?,
) {
    /** A sprint that has not been completed — the only kind that may be activated. */
    val isOpen: Boolean get() = completedAt == null
}

/** Reads and writes the projects table. */
class ProjectStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.ProjectStore {
    /**
     * Insert a project and return it.
     *
     * Callers should be going through [ProjectRepository.create] instead: a
     * project inserted here alone has no labels, no components and no board
     * columns, which is a project you cannot file an issue in and cannot fix
     * from the UI.
     */
    override suspend fun insert(
        name: String,
        namePrefix: String,
        isPublic: Boolean,
        visibleToAllSignedIn: Boolean,
    ): ProjectRecord =
        withContext(DatabaseDispatcher) {
            // Appended to the end of the list, like every other insert path — see
            // Projects.sq's nextPosition and ProjectRepository.create.
            val position = database.projectsQueries.nextPosition().executeAsOne()
            database.projectsQueries
                .insert(
                    name,
                    namePrefix,
                    if (isPublic) 1L else 0L,
                    if (visibleToAllSignedIn) 1L else 0L,
                    position,
                    now(),
                )
                .executeAsOne()
                .let { ProjectRecord(
                it.id,
                it.name,
                it.name_prefix,
                it.is_public != 0L,
                it.visible_to_all_signed_in != 0L,
                // Not it.discussions_enabled/it.messages_enabled: retired, see LNL-190.
                PROJECT_FORUM_FEATURES_ENABLED,
                PROJECT_FORUM_FEATURES_ENABLED,
                it.require_label != 0L,
                it.require_component != 0L,
                it.require_fixed_version_on_resolve != 0L,
                it.show_issue_author != 0L,
                it.created_at,
            ) }
        }

    override suspend fun update(
        id: Long,
        name: String,
        namePrefix: String,
        isPublic: Boolean,
        visibleToAllSignedIn: Boolean,
    ): Unit =
        withContext(DatabaseDispatcher) {
            database.projectsQueries.update(
                name,
                namePrefix,
                if (isPublic) 1L else 0L,
                if (visibleToAllSignedIn) 1L else 0L,
                id,
            )
        }

    /**
     * Set this project's two forum feature flags together (LNL-96).
     *
     * Both at once, never one at a time — the settings dialog sends the pair, and
     * a store makes no decisions about which of them the caller meant to change.
     * See Projects.sq's setFeatures.
     */
    override suspend fun setFeatures(id: Long, discussionsEnabled: Boolean, messagesEnabled: Boolean): Unit =
        withContext(DatabaseDispatcher) {
            database.projectsQueries.setFeatures(
                if (discussionsEnabled) 1L else 0L,
                if (messagesEnabled) 1L else 0L,
                id,
            )
        }

    /**
     * Set this project's two new-ticket requirement flags together (LNL-106).
     *
     * Both at once, like [setFeatures] and for its reason: the Structure tab sends
     * the pair, and a store makes no decisions about which the caller meant to
     * change. See Projects.sq's setRequirements.
     */
    override suspend fun setRequirements(
        id: Long,
        requireLabel: Boolean,
        requireComponent: Boolean,
        requireFixedVersionOnResolve: Boolean,
    ): Unit =
        withContext(DatabaseDispatcher) {
            database.projectsQueries.setRequirements(
                if (requireLabel) 1L else 0L,
                if (requireComponent) 1L else 0L,
                if (requireFixedVersionOnResolve) 1L else 0L,
                id,
            )
        }

    /**
     * Set this project's board-display flag (LNL-157).
     *
     * Its own writer, not folded into [setRequirements] — a display choice is not a
     * requirement, so the two travel separately. See Projects.sq's setShowIssueAuthor.
     */
    override suspend fun setShowIssueAuthor(id: Long, showIssueAuthor: Boolean): Unit =
        withContext(DatabaseDispatcher) {
            database.projectsQueries.setShowIssueAuthor(if (showIssueAuthor) 1L else 0L, id)
        }

    /** Delete the project. Every row that hangs off it cascades; the files do not — see [IssueRepository.deleteProject]. */
    override suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.projectsQueries.delete(id)
    }

    /**
     * Rewrite the whole instance's project order in one transaction.
     *
     * The whole list rather than a pair of swaps, for [ForumStore.setOrder]'s
     * reason: every swap passes through a state where two rows share a position,
     * and a half-applied reorder is a picker nobody asked for. Whether [ids] names
     * exactly the projects that exist is [ProjectRepository.reorder]'s check; a
     * store makes no decisions.
     */
    override suspend fun setOrder(ids: List<Long>): Unit = withContext(DatabaseDispatcher) {
        database.transaction {
            ids.forEachIndexed { index, id ->
                database.projectsQueries.setPosition(index.toLong(), id)
            }
        }
    }

    override suspend fun findById(id: Long): ProjectRecord? = withContext(DatabaseDispatcher) {
        database.projectsQueries.findById(id).executeAsOneOrNull()
            ?.let { ProjectRecord(
                it.id,
                it.name,
                it.name_prefix,
                it.is_public != 0L,
                it.visible_to_all_signed_in != 0L,
                // Not it.discussions_enabled/it.messages_enabled: retired, see LNL-190.
                PROJECT_FORUM_FEATURES_ENABLED,
                PROJECT_FORUM_FEATURES_ENABLED,
                it.require_label != 0L,
                it.require_component != 0L,
                it.require_fixed_version_on_resolve != 0L,
                it.show_issue_author != 0L,
                it.created_at,
            ) }
    }

    /** Case-insensitive by way of the column's `COLLATE NOCASE`; see Projects.sq. */
    override suspend fun findByName(name: String): ProjectRecord? = withContext(DatabaseDispatcher) {
        database.projectsQueries.findByName(name).executeAsOneOrNull()
            ?.let { ProjectRecord(
                it.id,
                it.name,
                it.name_prefix,
                it.is_public != 0L,
                it.visible_to_all_signed_in != 0L,
                // Not it.discussions_enabled/it.messages_enabled: retired, see LNL-190.
                PROJECT_FORUM_FEATURES_ENABLED,
                PROJECT_FORUM_FEATURES_ENABLED,
                it.require_label != 0L,
                it.require_component != 0L,
                it.require_fixed_version_on_resolve != 0L,
                it.show_issue_author != 0L,
                it.created_at,
            ) }
    }

    /**
     * Every project, unfiltered.
     *
     * Filtering by who may see what is [AccessControl]'s job, and the route's
     * to apply — see `projectRoutes`. A store that filtered would be a second
     * place permissions live, which is the thing §2 exists to prevent.
     */
    override suspend fun selectAll(): List<ProjectRecord> = withContext(DatabaseDispatcher) {
        database.projectsQueries.selectAll().executeAsList()
            .map { ProjectRecord(
                it.id,
                it.name,
                it.name_prefix,
                it.is_public != 0L,
                it.visible_to_all_signed_in != 0L,
                // Not it.discussions_enabled/it.messages_enabled: retired, see LNL-190.
                PROJECT_FORUM_FEATURES_ENABLED,
                PROJECT_FORUM_FEATURES_ENABLED,
                it.require_label != 0L,
                it.require_component != 0L,
                it.require_fixed_version_on_resolve != 0L,
                it.show_issue_author != 0L,
                it.created_at,
            ) }
    }

    /**
     * Which sprint this project is working on, or null because none is.
     *
     * Read on its own rather than carried on [ProjectRecord] — see Projects.sq's
     * active_sprint_id for why the board asks for this and nothing else does.
     */
    override suspend fun activeSprintId(id: Long): Long? = withContext(DatabaseDispatcher) {
        database.projectsQueries.activeSprintId(id).executeAsOneOrNull()?.active_sprint_id
    }

    /**
     * Point the project at a sprint, or null to leave it with none active.
     *
     * Whether that sprint exists, belongs here and is still open is the route's
     * question — see [SprintRepository.activate]. A store makes no decisions.
     */
    override suspend fun setActiveSprint(id: Long, sprintId: Long?): Unit = withContext(DatabaseDispatcher) {
        database.projectsQueries.setActiveSprint(sprintId, id)
    }

    /**
     * Which repository this project tracks, and where its token lives.
     *
     * Read on its own for [activeSprintId]'s reason, and for a second one: these
     * columns are admin-only, and a query nothing else calls cannot be widened
     * into a response by accident. See Projects.sq.
     *
     * A half-configured row — an owner with no name — cannot be written through
     * [setRepositoryConfig], so the pair is read as all-or-nothing here.
     */
    override suspend fun repositoryConfig(id: Long): RepositoryConfig? = withContext(DatabaseDispatcher) {
        database.projectsQueries.repositoryConfig(id).executeAsOneOrNull()?.let { row ->
            RepositoryConfig(
                repository = row.repository_owner?.let { owner ->
                    row.repository_name?.let { name -> RepositoryRef(owner, name) }
                },
                // The source is read off which column holds a value — the two are
                // mutually exclusive by construction (see setRepositoryConfig), so
                // a literal wins only because an env name cannot be set alongside
                // it. There is no mode column to consult. See TokenSource.
                token = when {
                    row.github_token_literal != null -> TokenSource.Literal(row.github_token_literal)
                    row.github_token_env != null -> TokenSource.Env(row.github_token_env)
                    else -> TokenSource.None
                },
            )
        }
    }

    /**
     * Link a repository, or pass nulls to unlink one.
     *
     * Whether the URL parsed, and whether the token's source is well-formed — the
     * variable name's prefix, or the literal's shape — are the route's questions;
     * see [parseRepositoryUrl] and [parseTokenEnvName]. A store makes no decisions.
     * It only flattens the one [TokenSource] into the two mutually-exclusive
     * columns, so that at most one of them is ever non-null.
     */
    override suspend fun setRepositoryConfig(id: Long, config: RepositoryConfig): Unit = withContext(DatabaseDispatcher) {
        val envName = (config.token as? TokenSource.Env)?.variableName
        val literal = (config.token as? TokenSource.Literal)?.token
        database.projectsQueries.setRepositoryConfig(
            config.repository?.owner,
            config.repository?.name,
            envName,
            literal,
            id,
        )
    }
}

/**
 * Reads and writes the sprints table.
 *
 * Shaped like [StatusStore] — insert, rename, setPosition, delete, the two reads
 * — because a sprint is a per-project named ordered thing and that is what
 * [VocabularyRepository] drives. [complete] is the one method with no sibling
 * above it, and it is the only genuinely new verb the feature adds.
 */
class SprintStore(private val database: LunicleDatabase) {
    suspend fun insert(projectId: Long, name: String, position: Long): Unit =
        withContext(DatabaseDispatcher) {
            database.sprintsQueries.insert(projectId, name, position)
        }

    /** Rename only. Position and completion have their own writers; see Sprints.sq. */
    suspend fun update(id: Long, name: String): Unit = withContext(DatabaseDispatcher) {
        database.sprintsQueries.update(name, id)
    }

    /** See [StatusStore.setPosition] — only ever called from inside `reorder`'s transaction. */
    suspend fun setPosition(id: Long, position: Long): Unit = withContext(DatabaseDispatcher) {
        database.sprintsQueries.setPosition(position, id)
    }

    /**
     * Stamp a sprint finished.
     *
     * Not paired with an un-complete: reopening a sprint is a different feature
     * with a different question behind it (what happens to the work that rolled
     * forward?), and one nobody has asked for. Adding it later costs a query.
     */
    suspend fun complete(id: Long, at: Long): Unit = withContext(DatabaseDispatcher) {
        database.sprintsQueries.complete(at, id)
    }

    /**
     * Delete a sprint, releasing its issues to the backlog.
     *
     * Never refused by the database, unlike [StatusStore.delete] — sprint_id is
     * SET NULL rather than RESTRICT. See Sprints.sq.
     */
    suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.sprintsQueries.delete(id)
    }

    suspend fun findByIdInProject(id: Long, projectId: Long): SprintRecord? =
        withContext(DatabaseDispatcher) {
            database.sprintsQueries.findByIdInProject(id, projectId).executeAsOneOrNull()?.toRecord()
        }

    suspend fun forProject(projectId: Long): List<SprintRecord> = withContext(DatabaseDispatcher) {
        database.sprintsQueries.forProject(projectId).executeAsList().map { it.toRecord() }
    }
}

private fun se.soderbjorn.lunicle.db.Sprints.toRecord() =
    SprintRecord(id, project_id, name, position, completed_at)

/** Reads and writes the labels table. */
class LabelStore(private val database: LunicleDatabase) : se.soderbjorn.lunicle.store.LabelStore {
    override suspend fun insert(projectId: Long, name: String, position: Long): Unit =
        withContext(DatabaseDispatcher) {
            database.labelsQueries.insert(projectId, name, position)
        }

    /** Rename. Callers should be going through [VocabularyRepository.rename], which owns the naming rules. */
    override suspend fun update(id: Long, name: String): Unit = withContext(DatabaseDispatcher) {
        database.labelsQueries.update(name, id)
    }

    /** Move one row. See [PriorityStore.setPosition] for why this is not the interesting method. */
    override suspend fun setPosition(id: Long, position: Long): Unit = withContext(DatabaseDispatcher) {
        database.labelsQueries.setPosition(position, id)
    }

    override suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.labelsQueries.delete(id)
    }

    /**
     * The label with this id *in this project*, or null.
     *
     * Scoped rather than by id alone, so a route can prove the row it was handed
     * belongs to the project in its path before it writes. Every vocabulary store
     * below repeats it; see Labels.sq's `findByIdInProject`.
     */
    override suspend fun findByIdInProject(id: Long, projectId: Long): VocabularyRecord? =
        withContext(DatabaseDispatcher) {
            database.labelsQueries.findByIdInProject(id, projectId).executeAsOneOrNull()
                ?.let { VocabularyRecord(it.id, it.project_id, it.name, it.position) }
        }

    override suspend fun forProject(projectId: Long): List<VocabularyRecord> = withContext(DatabaseDispatcher) {
        database.labelsQueries.forProject(projectId).executeAsList()
            .map { VocabularyRecord(it.id, it.project_id, it.name, it.position) }
    }
}

/** Reads and writes the components table. */
class ComponentStore(private val database: LunicleDatabase) : se.soderbjorn.lunicle.store.ComponentStore {
    override suspend fun insert(projectId: Long, name: String, position: Long): Unit =
        withContext(DatabaseDispatcher) {
            database.componentsQueries.insert(projectId, name, position)
        }

    override suspend fun update(id: Long, name: String): Unit = withContext(DatabaseDispatcher) {
        database.componentsQueries.update(name, id)
    }

    /** Move one row. See [PriorityStore.setPosition] for why this is not the interesting method. */
    override suspend fun setPosition(id: Long, position: Long): Unit = withContext(DatabaseDispatcher) {
        database.componentsQueries.setPosition(position, id)
    }

    override suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.componentsQueries.delete(id)
    }

    override suspend fun findByIdInProject(id: Long, projectId: Long): VocabularyRecord? =
        withContext(DatabaseDispatcher) {
            database.componentsQueries.findByIdInProject(id, projectId).executeAsOneOrNull()
                ?.let { VocabularyRecord(it.id, it.project_id, it.name, it.position) }
        }

    override suspend fun forProject(projectId: Long): List<VocabularyRecord> = withContext(DatabaseDispatcher) {
        database.componentsQueries.forProject(projectId).executeAsList()
            .map { VocabularyRecord(it.id, it.project_id, it.name, it.position) }
    }
}

/**
 * Reads and writes the priorities table.
 *
 * [StatusRecord]'s shape fits exactly — an id, a project, a name, a position —
 * so it is reused rather than copied into a PriorityRecord that would differ
 * only in its name. The two tables are twins on purpose; see Priorities.sq.
 */
class PriorityStore(private val database: LunicleDatabase) : se.soderbjorn.lunicle.store.PriorityStore {
    override suspend fun insert(projectId: Long, name: String, position: Long): Unit =
        withContext(DatabaseDispatcher) {
            database.prioritiesQueries.insert(projectId, name, position)
        }

    override suspend fun update(id: Long, name: String): Unit = withContext(DatabaseDispatcher) {
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
    override suspend fun setPosition(id: Long, position: Long): Unit = withContext(DatabaseDispatcher) {
        database.prioritiesQueries.setPosition(position, id)
    }

    /** Refused by the database while any issue holds it. See [VocabularyRepository.delete]. */
    override suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.prioritiesQueries.delete(id)
    }

    override suspend fun findByIdInProject(id: Long, projectId: Long): StatusRecord? =
        withContext(DatabaseDispatcher) {
            database.prioritiesQueries.findByIdInProject(id, projectId).executeAsOneOrNull()
                ?.let { StatusRecord(it.id, it.project_id, it.name, it.position) }
        }

    override suspend fun forProject(projectId: Long): List<StatusRecord> = withContext(DatabaseDispatcher) {
        database.prioritiesQueries.forProject(projectId).executeAsList()
            .map { StatusRecord(it.id, it.project_id, it.name, it.position) }
    }

    /**
     * What a new issue gets: the middle of the scale, not the top.
     *
     * Read rather than hardcoded to "Normal", for [StatusStore.firstForProject]'s
     * reason. See Priorities.sq's `defaultForProject` for why it is the middle.
     */
    override suspend fun defaultForProject(projectId: Long): StatusRecord? = withContext(DatabaseDispatcher) {
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
class ResolutionStore(private val database: LunicleDatabase) : se.soderbjorn.lunicle.store.ResolutionStore {
    // A new resolution is never done to begin with — the flag is set deliberately
    // in the same settings row, the way a new status never demands a resolution.
    // See VocabularyRepository.add and Resolutions.sq.
    override suspend fun insert(projectId: Long, name: String, position: Long): Unit =
        withContext(DatabaseDispatcher) {
            database.resolutionsQueries.insert(projectId, name, position, 0L)
        }

    /**
     * Rename, and set the done flag — both in one write, because they are one
     * decision made in the same settings row. [isDone] is the mirror of
     * [StatusStore.update]'s `requiresResolution`; see Resolutions.sq.
     */
    override suspend fun update(id: Long, name: String, isDone: Boolean): Unit = withContext(DatabaseDispatcher) {
        database.resolutionsQueries.update(name, if (isDone) 1L else 0L, id)
    }

    /** See [PriorityStore.setPosition] — only ever called from inside `reorder`'s transaction. */
    override suspend fun setPosition(id: Long, position: Long): Unit = withContext(DatabaseDispatcher) {
        database.resolutionsQueries.setPosition(position, id)
    }

    override suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.resolutionsQueries.delete(id)
    }

    override suspend fun findByIdInProject(id: Long, projectId: Long): StatusRecord? =
        withContext(DatabaseDispatcher) {
            database.resolutionsQueries.findByIdInProject(id, projectId).executeAsOneOrNull()
                ?.let { StatusRecord(it.id, it.project_id, it.name, it.position, isDone = it.is_done != 0L) }
        }

    override suspend fun forProject(projectId: Long): List<StatusRecord> = withContext(DatabaseDispatcher) {
        database.resolutionsQueries.forProject(projectId).executeAsList()
            .map { StatusRecord(it.id, it.project_id, it.name, it.position, isDone = it.is_done != 0L) }
    }
}

/**
 * Reads and writes the versions table.
 *
 * The plainest gateway there is — [VocabularyRecord]'s id/project/name/position
 * fits exactly, the same as labels and components, because a version carries no
 * per-kind extra (its done-ness lives on the resolution, not here). See Versions.sq.
 */
class VersionStore(private val database: LunicleDatabase) : se.soderbjorn.lunicle.store.VersionStore {
    override suspend fun insert(projectId: Long, name: String, position: Long): Unit =
        withContext(DatabaseDispatcher) {
            database.versionsQueries.insert(projectId, name, position)
        }

    /** Rename. Callers should be going through [VocabularyRepository.rename], which owns the naming rules. */
    override suspend fun update(id: Long, name: String): Unit = withContext(DatabaseDispatcher) {
        database.versionsQueries.update(name, id)
    }

    /** See [PriorityStore.setPosition] — only ever called from inside `reorder`'s transaction. */
    override suspend fun setPosition(id: Long, position: Long): Unit = withContext(DatabaseDispatcher) {
        database.versionsQueries.setPosition(position, id)
    }

    /** Releases the issues that pointed at it — SET NULL, never RESTRICT. See Versions.sq. */
    override suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.versionsQueries.delete(id)
    }

    override suspend fun findByIdInProject(id: Long, projectId: Long): VocabularyRecord? =
        withContext(DatabaseDispatcher) {
            database.versionsQueries.findByIdInProject(id, projectId).executeAsOneOrNull()
                ?.let { VocabularyRecord(it.id, it.project_id, it.name, it.position) }
        }

    override suspend fun forProject(projectId: Long): List<VocabularyRecord> = withContext(DatabaseDispatcher) {
        database.versionsQueries.forProject(projectId).executeAsList()
            .map { VocabularyRecord(it.id, it.project_id, it.name, it.position) }
    }
}

/** Reads and writes the statuses table. */
class StatusStore(private val database: LunicleDatabase) : se.soderbjorn.lunicle.store.StatusStore {
    override suspend fun insert(
        projectId: Long,
        name: String,
        position: Long,
        requiresResolution: Boolean,
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
    override suspend fun update(id: Long, name: String, requiresResolution: Boolean): Unit =
        withContext(DatabaseDispatcher) {
            database.statusesQueries.update(name, if (requiresResolution) 1L else 0L, id)
        }

    /** See [PriorityStore.setPosition] — only ever called from inside `reorder`'s transaction. */
    override suspend fun setPosition(id: Long, position: Long): Unit = withContext(DatabaseDispatcher) {
        database.statusesQueries.setPosition(position, id)
    }

    /** Refused by the database while any issue sits in it. See [VocabularyRepository.delete]. */
    override suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.statusesQueries.delete(id)
    }

    override suspend fun findByIdInProject(id: Long, projectId: Long): StatusRecord? =
        withContext(DatabaseDispatcher) {
            database.statusesQueries.findByIdInProject(id, projectId).executeAsOneOrNull()
                ?.let { StatusRecord(it.id, it.project_id, it.name, it.position, it.requires_resolution != 0L) }
        }

    override suspend fun forProject(projectId: Long): List<StatusRecord> = withContext(DatabaseDispatcher) {
        database.statusesQueries.forProject(projectId).executeAsList()
            .map { StatusRecord(it.id, it.project_id, it.name, it.position, it.requires_resolution != 0L) }
    }

    /**
     * The leftmost column, which is where a new issue lands.
     *
     * Read rather than hardcoded to "New": the seed names it, and a project
     * whose statuses were renamed should still be able to take an issue.
     */
    override suspend fun firstForProject(projectId: Long): StatusRecord? = withContext(DatabaseDispatcher) {
        database.statusesQueries.firstForProject(projectId).executeAsOneOrNull()
            ?.let { StatusRecord(it.id, it.project_id, it.name, it.position, it.requires_resolution != 0L) }
    }
}
