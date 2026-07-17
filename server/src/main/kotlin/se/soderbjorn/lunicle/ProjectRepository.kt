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
private val DEFAULT_LABELS = listOf("Bug", "Feature", "Improvement", "Codebase")
private val DEFAULT_COMPONENTS = listOf("Desktop", "Server", "Android", "iOS")
private val DEFAULT_STATUSES = listOf(
    "New", "Backlog", "Ready for development", "In progress", "Closed",
)

/**
 * Highest first — index 0 is the most urgent. See Priorities.sq's `position`.
 *
 * The order is the data here, not just presentation: the board sorts on the
 * position these indices become, and `defaultForProject` hands a new issue the
 * middle one. Reordering this list reorders every board; inserting into the
 * middle of it changes what "default" means.
 *
 * Copied into 2.sqm, which seeds the same five into every project that existed
 * before priorities did. Same drift hazard as local-db.sh's status list, and the
 * same answer: it is written down in both places and has to be changed in both.
 */
private val DEFAULT_PRIORITIES = listOf(
    "Very high", "High", "Normal", "Low", "Very low",
)

/**
 * Why an issue was closed. Order is data, as with the priorities: "Done" is 0 and
 * the board shows that group first.
 *
 * Copied into 3.sqm, which seeds the same two into every project that existed
 * before resolutions did.
 */
private val DEFAULT_RESOLUTIONS = listOf("Done", "Will not fix")

/**
 * The seeded status that demands a resolution.
 *
 * Named here, once, at the only moment naming it is safe: the instant a project
 * is created, when this list is what its statuses are. Everything afterwards
 * reads `statuses.requires_resolution` instead — see Statuses.sq for why a
 * running rule keyed on the name would switch itself off at the first rename.
 */
private const val CLOSING_STATUS = "Closed"

/** A rejected write, carrying the sentence the dialog should show. */
class ProjectConflict(val userMessage: String) : Exception(userMessage)

/**
 * Creates, renames and deletes projects — with their vocabularies.
 *
 * @param database needed directly, not just through the stores: seeding is one
 *   transaction across six tables, and a transaction is exactly the thing a
 *   store cannot express.
 */
class ProjectRepository(
    private val database: LunicleDatabase,
    private val projects: ProjectStore,
    private val attachments: AttachmentRepository,
    private val attachmentStore: AttachmentStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Create a project and everything it needs to be usable.
     *
     * @param name shown everywhere; trimmed, and unique case-insensitively.
     * @param namePrefix the "FOO" in FOO-123; trimmed and upper-cased.
     * @throws ProjectConflict if the name or the prefix is taken, or either is
     *   blank.
     */
    suspend fun create(name: String, namePrefix: String, isPublic: Boolean): ProjectRecord {
        val cleanName = name.trim()
        val cleanPrefix = namePrefix.trim().uppercase()
        validate(cleanName, cleanPrefix, existingId = null)

        return withContext(DatabaseDispatcher) {
            // One transaction for all six tables. The alternative — insert the
            // project, then seed — leaves a project with no board columns behind
            // whenever the second half fails, and nothing in the UI can repair
            // that.
            database.transactionWithResult {
                val row = database.projectsQueries
                    .insert(cleanName, cleanPrefix, if (isPublic) 1L else 0L, now())
                    .executeAsOne()
                DEFAULT_LABELS.forEach { database.labelsQueries.insert(row.id, it) }
                DEFAULT_COMPONENTS.forEach { database.componentsQueries.insert(row.id, it) }
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
                    database.resolutionsQueries.insert(row.id, resolution, index.toLong())
                }
                ProjectRecord(row.id, row.name, row.name_prefix, row.is_public != 0L, row.created_at)
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
    suspend fun update(id: Long, name: String, namePrefix: String, isPublic: Boolean): ProjectRecord {
        val cleanName = name.trim()
        val cleanPrefix = namePrefix.trim().uppercase()
        validate(cleanName, cleanPrefix, existingId = id)
        projects.update(id, cleanName, cleanPrefix, isPublic)
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
     */
    suspend fun delete(id: Long) {
        val doomed = attachmentStore.keysForProject(id)
        projects.delete(id)
        doomed.forEach { attachments.fileFor(it).delete() }
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
        if (namePrefix.isBlank()) throw ProjectConflict("A project needs a ticket prefix, like LMX.")

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
