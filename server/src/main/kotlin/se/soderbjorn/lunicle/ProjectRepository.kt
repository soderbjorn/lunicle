/**
 * The rules about projects that belong in neither a route nor a store.
 *
 * The server has repositories, and they are **not** the client's. The name
 * collides and the collision is worth being blunt about: the *client's*
 * `StorageRepository` sits above HTTP and cannot see a database — it exists so a
 * view model never mentions transport. The *server's* repositories sit below
 * HTTP and above SQL — they exist so a route never mentions a transaction. Same
 * motivation, opposite side of the wire, no shared code.
 *
 * This one owns two things a store cannot:
 *
 *  - **Create is not an insert.** A project is its name *and* its labels, its
 *    components and its board columns, in one transaction. A project that
 *    half-exists — a row with no board columns — is a project you cannot file an
 *    issue in and cannot fix from the UI.
 *  - **Naming.** Trim, upper-case the prefix, and fold the uniqueness check the
 *    way a human would. None of that is SQL, and none of it is HTTP.
 *
 * @see AccessControl
 * @see ProjectStore
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * The vocabularies every new project starts with.
 *
 * Per project rather than global, and seeded here rather than at database
 * creation, because every row carries a `project_id` — at database creation
 * there is no project to hang one on, and the first project anyone made would
 * come up with no board columns at all. (`roles` is the other half of the split:
 * global, no project to belong to, seeded once at startup. See RoleStore.seed.)
 */
internal val DEFAULT_LABELS = listOf("Bug", "Feature", "Improvement", "Codebase")
internal val DEFAULT_COMPONENTS = listOf("Desktop", "Server", "Android", "iOS")
/**
 * Copied into 4.sqm, which inserts "Ready for test" into every project that
 * existed before it did. Same drift hazard as local-db.sh's copy of this list,
 * and the same answer: it is written down in both places and has to be changed
 * in both.
 */
internal val DEFAULT_STATUSES = listOf(
    "New", "Backlog", "Ready for development", "In progress", "Ready for test", "Closed",
)

/**
 * Highest first — index 0 is the most urgent. See Priorities.sq's `position`.
 *
 * The order is the data here, not just presentation: the board sorts on the
 * position these indices become, and `defaultForProject` hands a new issue the
 * middle one. Reordering this list reorders every board; inserting into the
 * middle of it changes what "default" means.
 *
 * Not copied into any migration, unlike the statuses above: priorities are in
 * the 1.db baseline, so no project has ever existed without them and there was
 * never anything to back-fill. The drift hazard is only local-db.sh's copy.
 */
internal val DEFAULT_PRIORITIES = listOf(
    "Very high", "High", "Normal", "Low", "Very low",
)

/**
 * Why an issue was closed. Order is data, as with the priorities: "Done" is 0 and
 * the board shows that group first.
 *
 * The first two are in the 1.db baseline like the priorities, and were back-filled
 * by no migration because no project ever existed without them. "Duplicate" is the
 * exception and needed one — see 10.sqm, which appends it to every project that
 * predates it, and which carries the same drift hazard as 4.sqm: this list is
 * written down there too, and in local-db.sh, and has to be changed in all three.
 *
 * Last rather than beside "Done", because the order is what the board groups by
 * and inserting into the middle would silently re-group every closed issue on
 * every board.
 */
internal val DEFAULT_RESOLUTIONS = listOf("Done", "Will not fix", "Duplicate")

/**
 * One seeded relation kind: a from-side label, a to-side label, and whether it marks
 * the from side blocked (LNL-215).
 *
 * A triple rather than a bare list of names, because unlike every other vocabulary
 * here a relation kind is not just a word — it is a word, its opposite, and a flag,
 * and two of the three defaults differ per row. Nullable [inverse] is symmetry; see
 * IssueRelationKinds.sq.
 */
internal data class SeededRelationKind(val name: String, val inverse: String?, val marksBlocked: Boolean)

/**
 * The relation kinds every new project starts with.
 *
 * Three, chosen because between them they cover what trackers are actually asked for:
 * a dependency, a de-duplication, and a catch-all. All three are ordinary vocabulary
 * from the moment the project exists — renameable, reorderable, deletable — so this
 * list is a starting point rather than a set of built-ins.
 *
 * **Copied into 36.sqm**, which seeds the same three into every project that existed
 * before relation kinds did. That is the one real back-fill in LNL-215, and it is not
 * a convenience: a project with no kinds cannot create a relation at all, so an
 * unmigrated project would lose the feature outright rather than start it empty. Same
 * drift hazard as 4.sqm's copy of [DEFAULT_STATUSES], and the same answer — it is
 * written down in both places and has to be changed in both. Firestore gets the same
 * three through its own backfill.
 *
 * Only the first marks blocked. "Duplicate of" deliberately does not: an issue that
 * duplicates another is not waiting on it, it *is* it, and dimming both halves of
 * every duplicate pair would grey a board for no reason anybody could act on.
 */
internal val DEFAULT_RELATION_KINDS = listOf(
    SeededRelationKind("Blocked by", "Blocks", marksBlocked = true),
    SeededRelationKind("Duplicate of", "Duplicated by", marksBlocked = false),
    // Symmetric: null inverse, so it reads "Related to" from both ends.
    SeededRelationKind("Related to", null, marksBlocked = false),
)

/**
 * The seeded status that demands a resolution.
 *
 * Named here, once, at the only moment naming it is safe: the instant a project
 * is created, when this list is what its statuses are. Everything afterwards
 * reads `statuses.requires_resolution` instead — see Statuses.sq for why a
 * running rule keyed on the name would switch itself off at the first rename.
 */
internal const val CLOSING_STATUS = "Closed"

/**
 * The seeded resolution that means the work was actually done (LNL-134).
 *
 * Marked `is_done` at seed time so a fresh project's "require a fixed version when
 * resolving" toggle is immediately meaningful — closing as Done asks for a version,
 * closing as "Will not fix" or "Duplicate" never does. Named here at the same safe
 * instant as [CLOSING_STATUS], and read from `resolutions.is_done` everywhere after,
 * so a rename of "Done" keeps its meaning. Both backends seed it the same.
 */
internal const val DONE_RESOLUTION = "Done"

/** A rejected write, carrying the sentence the dialog should show. */
class ProjectConflict(val userMessage: String) : Exception(userMessage)

/**
 * Creates, renames and deletes projects — with their vocabularies.
 *
 * @param database needed directly, not just through the stores: seeding is one
 *   transaction across seven tables, and a transaction is exactly the thing a
 *   store cannot express.
 */
class ProjectRepository(
    private val database: LunicleDatabase,
    private val projects: se.soderbjorn.lunicle.store.ProjectStore,
    private val attachments: AttachmentRepository,
    private val attachmentStore: se.soderbjorn.lunicle.store.AttachmentStore,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.ProjectProvisioning {
    /**
     * Create a project and everything it needs to be usable.
     *
     * @param name shown everywhere; trimmed, and unique case-insensitively.
     * @param namePrefix the "FOO" in FOO-123; trimmed and upper-cased.
     * @throws ProjectConflict if the name or the prefix is taken, or either is
     *   blank.
     */
    override suspend fun create(name: String, namePrefix: String): ProjectRecord {
        val cleanName = name.trim()
        val cleanPrefix = namePrefix.trim().uppercase()
        validate(cleanName, cleanPrefix, existingId = null)

        return withContext(DatabaseDispatcher) {
            // One transaction for all seven tables. The alternative — insert the
            // project, then seed — leaves a project with no board columns behind
            // whenever the second half fails, and nothing in the UI can repair
            // that.
            database.transactionWithResult {
                // Appended to the end of the instance's list rather than dropped
                // at position 0, so making a project does not silently re-sort the
                // picker for everyone. See Projects.sq's nextPosition and LNL-93.
                val position = database.projectsQueries.nextPosition().executeAsOne()
                val row = database.projectsQueries
                    .insert(
                        cleanName,
                        cleanPrefix,
                        position,
                        now(),
                    )
                    .executeAsOne()
                // Seeded in the order they are listed, not alphabetically: the
                // list is the order somebody chose, and a new project starts on
                // it. See Labels.sq's `position`.
                DEFAULT_LABELS.forEachIndexed { index, label ->
                    database.labelsQueries.insert(row.id, label, index.toLong())
                }
                DEFAULT_COMPONENTS.forEachIndexed { index, component ->
                    database.componentsQueries.insert(row.id, component, index.toLong())
                }
                DEFAULT_STATUSES.forEachIndexed { index, status ->
                    database.statusesQueries.insert(
                        row.id,
                        status,
                        index.toLong(),
                        if (status == CLOSING_STATUS) 1L else 0L,
                    )
                }
                DEFAULT_PRIORITIES.forEachIndexed { index, priority ->
                    database.prioritiesQueries.insert(row.id, priority, index.toLong())
                }
                DEFAULT_RESOLUTIONS.forEachIndexed { index, resolution ->
                    database.resolutionsQueries.insert(
                        row.id,
                        resolution,
                        index.toLong(),
                        if (resolution == DONE_RESOLUTION) 1L else 0L,
                    )
                }
                DEFAULT_RELATION_KINDS.forEachIndexed { index, kind ->
                    database.issueRelationKindsQueries.insert(
                        row.id,
                        kind.name,
                        kind.inverse,
                        if (kind.marksBlocked) 1L else 0L,
                        index.toLong(),
                    )
                }
                ProjectRecord(
                    row.id,
                    row.name,
                    row.name_prefix,
                    // Not row.discussions_enabled/row.messages_enabled: retired, see LNL-190.
                    PROJECT_FORUM_FEATURES_ENABLED,
                    PROJECT_FORUM_FEATURES_ENABLED,
                    row.require_label != 0L,
                    row.require_component != 0L,
                    row.require_fixed_version_on_resolve != 0L,
                    row.show_issue_author != 0L,
                    row.created_at,
                    row.hide_issue_numbers?.let { stored -> stored != 0L },
                    se.soderbjorn.lunicle.clientserver.EstimateMode.fromKey(row.estimate_mode),
                )
            }
        }
    }

    /**
     * Rename or re-configure a project.
     *
     * @throws ProjectConflict if the new name or prefix belongs to a *different*
     *   project. Renaming a project to what it is already called is not a
     *   conflict, which is why [validate] takes the id it is allowed to ignore.
     */
    override suspend fun update(
        id: Long,
        name: String,
        namePrefix: String,
    ): ProjectRecord {
        val cleanName = name.trim()
        val cleanPrefix = namePrefix.trim().uppercase()
        validate(cleanName, cleanPrefix, existingId = id)
        projects.update(id, cleanName, cleanPrefix)
        return projects.findById(id) ?: throw ProjectConflict("That project no longer exists.")
    }

    /**
     * Delete a project, everything in it, and every file behind any of it.
     *
     * The rows go by cascade — issues, comments, labels, components, statuses,
     * role grants, attachment *rows*. The attachment **files** do not: SQLite
     * cannot reach the filesystem. So the keys are collected first, while the
     * rows that name them still exist, and the files unlinked afterwards. Same
     * ordering rule as [IssueRepository.delete], one level up.
     *
     * A file that fails to unlink is left for
     * [AttachmentRepository.sweepOrphans] at the next restart.
     *
     * The attachment rows are dropped explicitly rather than left to the cascade
     * that would take them a moment later, for the reason
     * [IssueRepository.delete] drops an issue's explicitly: the Firestore backend
     * has none, and both implementations of this seam are held to the same
     * emptiness afterwards by `ProjectProvisioningContract`. Redundant here, and
     * cheap; load-bearing there. See LNL-177.
     */
    override suspend fun delete(id: Long) {
        val doomed = attachmentStore.keysForProject(id)
        attachmentStore.deleteForProject(id)
        projects.delete(id)
        doomed.forEach { attachments.deleteBlob(it) }
    }

    /**
     * Put the instance's projects in the given order.
     *
     * @throws ProjectConflict if [ids] is not exactly the projects that exist.
     *   Checked as a *set*, so a caller that omits one, repeats one, or names a
     *   project that has since been deleted is refused rather than partially
     *   applied — the same line [ForumRepository.reorder] and
     *   [VocabularyRepository.reorder] draw. Unlike those two there is no project
     *   scope to prove membership against: the list is the whole instance, so the
     *   set it must match is every project there is.
     */
    override suspend fun reorder(ids: List<Long>) {
        val current = projects.selectAll().map { it.id }
        if (ids.size != current.size || ids.toSet() != current.toSet()) {
            throw ProjectConflict("That order does not name this instance's projects.")
        }
        projects.setOrder(ids)
    }

    /**
     * Is [name] already taken by someone other than [existingId]?
     *
     * Why this exists at all, given `COLLATE NOCASE UNIQUE` on the column:
     * NOCASE folds **ASCII A–Z only**. `Ärenden` and `ärenden` would both get in
     * on the constraint alone, and a human would call that the same project.
     * Kotlin's `lowercase()` is Unicode-aware, so this catches åäö in practice.
     *
     * It is a check rather than a constraint, so it races in theory — and does
     * not in fact, because [DatabaseDispatcher] pins every query to one thread.
     * If that ever changes, this is on the list of things that quietly stop
     * being true. The UNIQUE index still backstops the ASCII 95%.
     */
    private suspend fun validate(name: String, namePrefix: String, existingId: Long?) {
        if (name.isBlank()) throw ProjectConflict("A project needs a name.")
        if (namePrefix.isBlank()) throw ProjectConflict("A project needs an issue prefix, like LMX.")

        val all = projects.selectAll()
        val nameClash = all.firstOrNull { it.id != existingId && it.name.lowercase() == name.lowercase() }
        if (nameClash != null) {
            throw ProjectConflict("There is already a project called \"${nameClash.name}\".")
        }
        val prefixClash = all.firstOrNull {
            it.id != existingId && it.namePrefix.lowercase() == namePrefix.lowercase()
        }
        if (prefixClash != null) {
            // Naming the other project matters: the prefix is short and the
            // clash is usually with something the admin forgot exists.
            throw ProjectConflict("\"${prefixClash.namePrefix}\" is already used by \"${prefixClash.name}\".")
        }
    }
}
