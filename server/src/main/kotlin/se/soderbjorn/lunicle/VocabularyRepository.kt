/**
 * The rules about a project's vocabularies that belong in neither a route nor a
 * store.
 *
 * [ProjectRepository] owns making a project; this owns changing what is *in* one
 * after the fact — the labels, components, statuses, priorities and resolutions
 * that `ProjectRepository.create` seeded and that nothing, until now, could edit.
 *
 * Three things live here that a store cannot hold:
 *
 *  - **Deleting is not a DELETE.** Two of these five tables are pointed at by
 *    `issues` with NOT NULL composite foreign keys and no ON DELETE clause, so
 *    SQLite's default RESTRICT already refuses to orphan an issue. That is the
 *    guarantee, and it is not the feature: a raw constraint violation surfaces as
 *    a 500, which tells an admin that the server broke when what happened is that
 *    three issues are still in that column. So this counts first and says so.
 *  - **A project must stay able to take an issue.** `IssueRepository.createDraft`
 *    reads the leftmost status and the middle priority, and errors if there are
 *    none. A project with no statuses cannot be repaired from the UI — you cannot
 *    file the issue that would tell you it is broken — so the last one of either
 *    cannot go. RESTRICT does not cover this: an *unused* status is deletable as
 *    far as the database is concerned, and the last status of an empty project is
 *    exactly that.
 *  - **Reordering is one transaction.** `position` has no UNIQUE constraint (it
 *    cannot: every swap passes through a duplicate), so nothing at the schema
 *    level stops a half-applied reorder from leaving two columns sharing a
 *    position and the board ordering them arbitrarily.
 *
 * @see ProjectRepository
 * @see AccessControl
 * @see projectSettingsRoutes
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * One vocabulary row, whichever of the five tables it came from.
 *
 * [StatusRecord] almost fits and is deliberately not reused: this carries
 * [usageCount], which is not a property of the row but of the issues pointing at
 * it, and putting it on the record every store returns would mean every board
 * read paying for a count nothing renders.
 *
 * @property position 0 for a label or a component, which have no order. Not
 *   "unknown" — those two tables genuinely have no position column; they sort by
 *   name. See [VocabularyKind.isOrdered].
 * @property usageCount how many issues hold this row, drafts included. A refusal
 *   for a status or a priority, a consequence for a label. See
 *   [IssueStore.usageByStatus].
 */
data class VocabularyRow(
    val id: Long,
    val projectId: Long,
    val name: String,
    val position: Long = 0,
    val requiresResolution: Boolean = false,
    val usageCount: Long = 0,
)

/**
 * A rejected name, carrying the sentence the dialog shows. [ProjectConflict]'s
 * twin, one level down, and a 409 for the same reason: the name is taken.
 */
class VocabularyConflict(val userMessage: String) : Exception(userMessage)

/**
 * A write refused for a reason that is not a name clash: the row is in use, it is
 * the last one, or the request does not make sense for this kind.
 *
 * A 400 rather than a 409 — nothing here is a race with another writer, it is a
 * request that was wrong when it was sent. Separate from [VocabularyConflict] so
 * the route does not have to read the sentence to know which status to use.
 */
class VocabularyRefusal(val userMessage: String) : Exception(userMessage)

/**
 * Adds, renames, reorders and deletes the rows of a project's five vocabularies.
 *
 * @param database needed directly, not just through the stores: a reorder is one
 *   transaction across a whole table, and a transaction is exactly the thing a
 *   store cannot express. Same reason [ProjectRepository] takes it.
 * @param issues for the usage counts, and only for them. This repository never
 *   writes an issue — it refuses writes that would strand one.
 */
class VocabularyRepository(
    private val database: LunicleDatabase,
    private val labels: LabelStore,
    private val components: ComponentStore,
    private val statuses: StatusStore,
    private val priorities: PriorityStore,
    private val resolutions: ResolutionStore,
    private val issues: IssueStore,
) {
    /**
     * Every row of one kind, in the order it is rendered, with its usage count.
     *
     * Two queries, never one per row: see [IssueStore.usageByStatus].
     */
    suspend fun rows(projectId: Long, kind: VocabularyKind): List<VocabularyRow> {
        val uses = usage(projectId, kind)
        return when (kind) {
            VocabularyKind.LABEL -> labels.forProject(projectId).map { it.toRow(uses) }
            VocabularyKind.COMPONENT -> components.forProject(projectId).map { it.toRow(uses) }
            VocabularyKind.STATUS -> statuses.forProject(projectId).map { it.toRow(uses) }
            VocabularyKind.PRIORITY -> priorities.forProject(projectId).map { it.toRow(uses) }
            VocabularyKind.RESOLUTION -> resolutions.forProject(projectId).map { it.toRow(uses) }
        }
    }

    /**
     * The row with this id *in this project*, or null.
     *
     * Project-scoped on purpose: it is what lets a route answer 404 to
     * `/projects/7/vocabulary/status/42` naming project 9's status, rather than
     * renaming another project's column because admin is admin everywhere. See
     * Labels.sq's `findByIdInProject`.
     */
    suspend fun find(projectId: Long, kind: VocabularyKind, id: Long): VocabularyRow? {
        val uses = usage(projectId, kind)
        return when (kind) {
            VocabularyKind.LABEL -> labels.findByIdInProject(id, projectId)?.toRow(uses)
            VocabularyKind.COMPONENT -> components.findByIdInProject(id, projectId)?.toRow(uses)
            VocabularyKind.STATUS -> statuses.findByIdInProject(id, projectId)?.toRow(uses)
            VocabularyKind.PRIORITY -> priorities.findByIdInProject(id, projectId)?.toRow(uses)
            VocabularyKind.RESOLUTION -> resolutions.findByIdInProject(id, projectId)?.toRow(uses)
        }
    }

    /**
     * Add a row, at the end of the order.
     *
     * The end, always, and never the front: inserting a new board column at
     * position 0 would silently change where every future issue lands —
     * `IssueRepository.createDraft` files into the leftmost column — so "add"
     * would quietly be "change the default". Dragging it into place afterwards is
     * one action and says what it does.
     *
     * `max + 1` rather than `count`, which is the bug this line avoids: a project
     * whose middle status was deleted has positions 0,1,3,4 and a count of 4, so
     * the new row would collide with position 4 and the two would order
     * arbitrarily. There is no UNIQUE constraint to catch it — there cannot be;
     * see [reorder].
     *
     * @throws VocabularyConflict if the name is blank or already this project's.
     */
    suspend fun add(projectId: Long, kind: VocabularyKind, name: String): VocabularyRow {
        val clean = name.trim()
        val existing = rows(projectId, kind)
        validateName(kind, clean, existing, renamingId = null)

        val next = (existing.maxOfOrNull { it.position } ?: -1L) + 1L
        when (kind) {
            VocabularyKind.LABEL -> labels.insert(projectId, clean)
            VocabularyKind.COMPONENT -> components.insert(projectId, clean)
            // A new column never demands a resolution. The flag is the project's
            // most consequential switch — it decides which columns cannot be
            // entered without a reason — and defaulting it on would arm it by
            // accident. The admin turns it on deliberately, in the same dialog.
            VocabularyKind.STATUS -> statuses.insert(projectId, clean, next, requiresResolution = false)
            VocabularyKind.PRIORITY -> priorities.insert(projectId, clean, next)
            VocabularyKind.RESOLUTION -> resolutions.insert(projectId, clean, next)
        }
        return rows(projectId, kind).firstOrNull { it.name.equals(clean, ignoreCase = true) }
            ?: throw VocabularyConflict("That ${kind.noun} could not be saved.")
    }

    /**
     * Rename a row, and — for a status — set its closing flag.
     *
     * [requiresResolution] is ignored for every kind but a status rather than
     * refused, because the dialog sends back the row it is rendering and a
     * priority has nothing to put there. Refusing would make the client
     * responsible for knowing which kinds carry the flag, which is this file's
     * rule to keep.
     *
     * Nothing is checked about turning the flag *off* on the last closing column,
     * and that is deliberate: a project where no column demands a resolution is a
     * project that never asks why an issue was closed, which is a legitimate way
     * to run a board. `BoardRoutes.resolveResolution` reads the flag per status on
     * every write, so the issues that already carry a resolution keep it and no
     * new ones are demanded. Nothing is stranded.
     *
     * @throws VocabularyConflict if the name is blank or belongs to another row of
     *   the same kind in this project.
     */
    suspend fun rename(
        projectId: Long,
        kind: VocabularyKind,
        row: VocabularyRow,
        name: String,
        requiresResolution: Boolean,
    ) {
        val clean = name.trim()
        validateName(kind, clean, rows(projectId, kind), renamingId = row.id)
        when (kind) {
            VocabularyKind.LABEL -> labels.update(row.id, clean)
            VocabularyKind.COMPONENT -> components.update(row.id, clean)
            VocabularyKind.STATUS -> statuses.update(row.id, clean, requiresResolution)
            VocabularyKind.PRIORITY -> priorities.update(row.id, clean)
            VocabularyKind.RESOLUTION -> resolutions.update(row.id, clean)
        }
    }

    /**
     * Delete a row, or explain why not.
     *
     * The two refusals, in the order they are checked:
     *
     *  1. **The last status, or the last priority.** `IssueRepository.createDraft`
     *     errors without one, and a project that cannot take an issue cannot be
     *     repaired by filing one about it. Checked first because it is true even
     *     of a brand-new project where nothing is in use, and because "you cannot
     *     delete the only column" is a better sentence than the count would give.
     *
     *     Not the last resolution, and not the last label: a project with no
     *     resolutions can still take issues, and the dialog that just deleted the
     *     row is the same dialog that can add one back. The rule is not "never run
     *     out", it is "never become unrepairable".
     *  2. **In use.** RESTRICT would refuse this anyway — that is the guarantee
     *     and this is the message. Labels and components never reach here: their
     *     `issue_labels` rows cascade, so deleting one unlabels those issues and
     *     leaves them otherwise untouched, which is why [usageCount] is a sentence
     *     for those two rather than a gate. See IssueLabels.sq.
     *
     * The count is read, and then the delete is *also* wrapped: the two are
     * separate queries, so a draft filed in between would slip past the count and
     * hit the constraint instead. That window is narrow and real, and the whole
     * point of this method is that an admin never sees a raw violation.
     *
     * @throws VocabularyRefusal if the row is in use, or is the last of a kind a
     *   project cannot be without.
     */
    suspend fun delete(projectId: Long, kind: VocabularyKind, row: VocabularyRow) {
        val siblings = rows(projectId, kind)
        if (siblings.size <= 1 && kind.isLoadBearing) {
            throw VocabularyRefusal(
                "A project needs at least one ${kind.noun}, and \"${row.name}\" is the only one " +
                    "left. Add another before deleting this one.",
            )
        }
        if (kind.restrictsOnUse && row.usageCount > 0) {
            throw VocabularyRefusal(inUseMessage(kind, row))
        }
        try {
            when (kind) {
                VocabularyKind.LABEL -> labels.delete(row.id)
                VocabularyKind.COMPONENT -> components.delete(row.id)
                VocabularyKind.STATUS -> statuses.delete(row.id)
                VocabularyKind.PRIORITY -> priorities.delete(row.id)
                VocabularyKind.RESOLUTION -> resolutions.delete(row.id)
            }
        } catch (violation: Exception) {
            // The count said zero and the database disagreed, so something was
            // filed while this request was in flight. Re-read rather than repeat
            // the stale count: the number in the message is the reason an admin
            // trusts it.
            val now = find(projectId, kind, row.id)
            if (now != null && now.usageCount > 0) throw VocabularyRefusal(inUseMessage(kind, now))
            // Not a usage problem, then, and not one this class knows how to
            // phrase. Rethrow rather than invent an explanation — a 500 with a
            // stack trace beats a confident 400 that is wrong about why.
            throw violation
        }
    }

    /**
     * Put a whole vocabulary in the order given.
     *
     * The whole list, proved before anything is written: [ids] must be exactly
     * this kind's rows in this project — all of them, each once. A partial list
     * would leave the rows it omitted holding positions that now collide with the
     * ones it named, and there is no constraint to catch that.
     *
     * One transaction, because a half-applied reorder is a board with two columns
     * at position 3 — ordered by whatever SQLite feels like, differently on
     * different reads. `position` cannot be UNIQUE to catch it: every swap passes
     * through a duplicate on its way, so the constraint would refuse the legal
     * intermediate state of every single reorder.
     *
     * Positions are rewritten 0..n-1 rather than swapped, which also quietly
     * repairs the gaps that deleting a middle row leaves behind.
     *
     * @throws VocabularyRefusal if the kind has no order, or [ids] is not exactly
     *   this vocabulary.
     */
    suspend fun reorder(projectId: Long, kind: VocabularyKind, ids: List<Long>) {
        if (!kind.isOrdered) {
            throw VocabularyRefusal(
                "${kind.plural.replaceFirstChar { it.uppercase() }} have no order — " +
                    "they are listed by name.",
            )
        }
        val current = rows(projectId, kind).map { it.id }
        if (ids.size != current.size || ids.toSet() != current.toSet()) {
            throw VocabularyRefusal("That order does not name this project's ${kind.plural}.")
        }

        withContext(DatabaseDispatcher) {
            database.transaction {
                ids.forEachIndexed { index, id ->
                    val position = index.toLong()
                    when (kind) {
                        VocabularyKind.STATUS -> database.statusesQueries.setPosition(position, id)
                        VocabularyKind.PRIORITY -> database.prioritiesQueries.setPosition(position, id)
                        VocabularyKind.RESOLUTION -> database.resolutionsQueries.setPosition(position, id)
                        // Unreachable: isOrdered rejected these above. Exhaustive
                        // rather than an else branch, so a sixth kind is a compile
                        // error here and not a silent no-op.
                        VocabularyKind.LABEL, VocabularyKind.COMPONENT -> Unit
                    }
                }
            }
        }
    }

    /**
     * Is [name] blank, or already another row's?
     *
     * `lowercase()` rather than leaning on the column's `COLLATE NOCASE`, for
     * exactly [ProjectRepository.validate]'s reason: NOCASE folds ASCII A–Z only,
     * so `Färdig` and `färdig` would both get past the UNIQUE constraint and a
     * human would call that one status. Kotlin's fold is Unicode-aware, and it is
     * the same one the dialog uses — so the two agree about what a duplicate is.
     *
     * The UNIQUE (project_id, name) index still backstops the ASCII 95% if this is
     * ever skipped.
     */
    private fun validateName(
        kind: VocabularyKind,
        name: String,
        existing: List<VocabularyRow>,
        renamingId: Long?,
    ) {
        if (name.isBlank()) throw VocabularyConflict("A ${kind.noun} needs a name.")
        val clash = existing.firstOrNull {
            it.id != renamingId && it.name.lowercase() == name.lowercase()
        }
        if (clash != null) {
            throw VocabularyConflict("There is already a ${kind.noun} called \"${clash.name}\".")
        }
    }

    /**
     * "3 issues are still in that status." — the sentence this whole class exists
     * to produce.
     *
     * Both halves agree with the count, including the verb. That is fussier than
     * it looks worth being, and it is the difference between a message that reads
     * as written for you and one that reads as assembled: "1 issue are in that
     * status" is the tell that nobody looked.
     *
     * A status is somewhere an issue *is*; a priority and a resolution are
     * something it *has*. Worth the branch — "3 issues still have that status"
     * describes a property of the issues, when the point is that three cards are
     * sitting in the column about to be deleted.
     */
    private fun inUseMessage(kind: VocabularyKind, row: VocabularyRow): String {
        val isOne = row.usageCount == 1L
        val subject = if (isOne) "1 issue" else "${row.usageCount} issues"
        val verb = when {
            kind == VocabularyKind.STATUS && isOne -> "is still in"
            kind == VocabularyKind.STATUS -> "are still in"
            isOne -> "still has"
            else -> "still have"
        }
        val andThen = if (isOne) "Move it somewhere else first" else "Move them somewhere else first"
        return "$subject $verb that ${kind.noun}. $andThen, and then delete it."
    }

    /** The usage counts for one kind, as row id → how many issues hold it. */
    private suspend fun usage(projectId: Long, kind: VocabularyKind): Map<Long, Long> = when (kind) {
        VocabularyKind.LABEL -> issues.usageByLabel(projectId)
        VocabularyKind.COMPONENT -> issues.usageByComponent(projectId)
        VocabularyKind.STATUS -> issues.usageByStatus(projectId)
        VocabularyKind.PRIORITY -> issues.usageByPriority(projectId)
        VocabularyKind.RESOLUTION -> issues.usageByResolution(projectId)
    }

    private fun VocabularyRecord.toRow(uses: Map<Long, Long>) =
        VocabularyRow(id, projectId, name, usageCount = uses[id] ?: 0)

    private fun StatusRecord.toRow(uses: Map<Long, Long>) =
        VocabularyRow(id, projectId, name, position, requiresResolution, uses[id] ?: 0)
}
