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
 * @property position where the row sits in its kind's list, 0 first. Every one
 *   of the five tables carries one now — labels and components gained theirs in
 *   8.sqm, back-filled to the alphabetical order they used to be read in. See
 *   [VocabularyKind.isOrdered], which no longer distinguishes them.
 * @property usageCount how many *published* issues hold this row — drafts are not
 *   counted, and are cleared out of the way instead (LNL-183). A refusal for a
 *   status or a priority, a consequence for a label. See [IssueStore.usageByStatus].
 */
data class VocabularyRow(
    val id: Long,
    val projectId: Long,
    val name: String,
    val position: Long,
    val requiresResolution: Boolean = false,
    /** A resolution's "the work was done" flag (LNL-134); always false for other kinds. See [StatusRecord.isDone]. */
    val isDone: Boolean = false,
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
    private val labels: se.soderbjorn.lunicle.store.LabelStore,
    private val components: se.soderbjorn.lunicle.store.ComponentStore,
    private val statuses: se.soderbjorn.lunicle.store.StatusStore,
    private val priorities: se.soderbjorn.lunicle.store.PriorityStore,
    private val resolutions: se.soderbjorn.lunicle.store.ResolutionStore,
    private val sprints: SprintStore,
    private val versions: se.soderbjorn.lunicle.store.VersionStore,
    private val issues: se.soderbjorn.lunicle.store.IssueStore,
) : se.soderbjorn.lunicle.store.VocabularyStore {
    /**
     * Every row of one kind, in the order it is rendered, with its usage count.
     *
     * Two queries, never one per row: see [IssueStore.usageByStatus].
     */
    override suspend fun rows(projectId: Long, kind: VocabularyKind): List<VocabularyRow> {
        val uses = usage(projectId, kind)
        return when (kind) {
            VocabularyKind.LABEL -> labels.forProject(projectId).map { it.toRow(uses) }
            VocabularyKind.COMPONENT -> components.forProject(projectId).map { it.toRow(uses) }
            VocabularyKind.STATUS -> statuses.forProject(projectId).map { it.toRow(uses) }
            VocabularyKind.PRIORITY -> priorities.forProject(projectId).map { it.toRow(uses) }
            VocabularyKind.RESOLUTION -> resolutions.forProject(projectId).map { it.toRow(uses) }
            VocabularyKind.SPRINT -> sprints.forProject(projectId).map { it.toRow(uses) }
            VocabularyKind.VERSION -> versions.forProject(projectId).map { it.toRow(uses) }
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
    override suspend fun find(projectId: Long, kind: VocabularyKind, id: Long): VocabularyRow? {
        val uses = usage(projectId, kind)
        return when (kind) {
            VocabularyKind.LABEL -> labels.findByIdInProject(id, projectId)?.toRow(uses)
            VocabularyKind.COMPONENT -> components.findByIdInProject(id, projectId)?.toRow(uses)
            VocabularyKind.STATUS -> statuses.findByIdInProject(id, projectId)?.toRow(uses)
            VocabularyKind.PRIORITY -> priorities.findByIdInProject(id, projectId)?.toRow(uses)
            VocabularyKind.RESOLUTION -> resolutions.findByIdInProject(id, projectId)?.toRow(uses)
            VocabularyKind.SPRINT -> sprints.findByIdInProject(id, projectId)?.toRow(uses)
            VocabularyKind.VERSION -> versions.findByIdInProject(id, projectId)?.toRow(uses)
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
    override suspend fun add(projectId: Long, kind: VocabularyKind, name: String): VocabularyRow {
        val clean = name.trim()
        val existing = rows(projectId, kind)
        validateName(kind, clean, existing, renamingId = null)

        val next = (existing.maxOfOrNull { it.position } ?: -1L) + 1L
        when (kind) {
            VocabularyKind.LABEL -> labels.insert(projectId, clean, next)
            VocabularyKind.COMPONENT -> components.insert(projectId, clean, next)
            // A new column never demands a resolution. The flag is the project's
            // most consequential switch — it decides which columns cannot be
            // entered without a reason — and defaulting it on would arm it by
            // accident. The admin turns it on deliberately, in the same dialog.
            VocabularyKind.STATUS -> statuses.insert(projectId, clean, next, requiresResolution = false)
            VocabularyKind.PRIORITY -> priorities.insert(projectId, clean, next)
            VocabularyKind.RESOLUTION -> resolutions.insert(projectId, clean, next)
            // Not activated. Creating next quarter's sprints in advance must not
            // yank the board out from under whoever is working in this one, so
            // activation stays a separate deliberate act. See SprintRepository.
            VocabularyKind.SPRINT -> sprints.insert(projectId, clean, next)
            VocabularyKind.VERSION -> versions.insert(projectId, clean, next)
        }
        return rows(projectId, kind).firstOrNull { it.name.equals(clean, ignoreCase = true) }
            ?: throw VocabularyConflict("That ${kind.noun} could not be saved.")
    }

    /**
     * Rename a row, and — for a status — set its closing flag.
     *
     * [requiresResolution] (a status's) and [isDone] (a resolution's) are each
     * ignored for every kind but their own rather than refused, because the dialog
     * sends back the row it is rendering and a priority has nothing to put there.
     * Refusing would make the client responsible for knowing which kinds carry
     * which flag, which is this file's rule to keep.
     *
     * Nothing is checked about turning the closing flag *off* on the last closing
     * column, and that is deliberate: a project where no column demands a resolution
     * is a project that never asks why an issue was closed, which is a legitimate
     * way to run a board. `BoardRoutes.resolveResolution` reads the flag per status
     * on every write, so the issues that already carry a resolution keep it and no
     * new ones are demanded. Nothing is stranded. The same holds for [isDone]: a
     * resolution unmarked done stops requiring a fixed version, and the issues that
     * already carry one keep it.
     *
     * @throws VocabularyConflict if the name is blank or belongs to another row of
     *   the same kind in this project.
     */
    override suspend fun rename(
        projectId: Long,
        kind: VocabularyKind,
        row: VocabularyRow,
        name: String,
        requiresResolution: Boolean,
        isDone: Boolean,
    ) {
        val clean = name.trim()
        validateName(kind, clean, rows(projectId, kind), renamingId = row.id)
        when (kind) {
            VocabularyKind.LABEL -> labels.update(row.id, clean)
            VocabularyKind.COMPONENT -> components.update(row.id, clean)
            VocabularyKind.STATUS -> statuses.update(row.id, clean, requiresResolution)
            VocabularyKind.PRIORITY -> priorities.update(row.id, clean)
            VocabularyKind.RESOLUTION -> resolutions.update(row.id, clean, isDone)
            VocabularyKind.SPRINT -> sprints.update(row.id, clean)
            VocabularyKind.VERSION -> versions.update(row.id, clean)
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
     * And one thing that is deliberately *not* a refusal: the drafts sitting in
     * the row. [usageCount] counts published issues only, and [clearDrafts] takes
     * the unpublished ones with the row — see its comment for why an invisible
     * half-written issue must not be able to veto a column (LNL-183).
     *
     * The count is read, and then the delete is *also* wrapped: the two are
     * separate queries, so an issue filed in between would slip past the count and
     * hit the constraint instead. That window is narrow and real, and the whole
     * point of this method is that an admin never sees a raw violation.
     *
     * @throws VocabularyRefusal if the row is in use, or is the last of a kind a
     *   project cannot be without.
     */
    override suspend fun delete(projectId: Long, kind: VocabularyKind, row: VocabularyRow) {
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
        clearDrafts(projectId, kind, row.id)
        try {
            deleteRow(kind, row.id)
        } catch (violation: Exception) {
            // The count said zero and the database disagreed, so something was
            // filed while this request was in flight. Re-read rather than repeat
            // the stale count: the number in the message is the reason an admin
            // trusts it.
            val now = find(projectId, kind, row.id)
            if (now != null && now.usageCount > 0) throw VocabularyRefusal(inUseMessage(kind, now))
            // Still nothing published pointing at it, so what the constraint is
            // refusing over is a draft — one opened between the clear above and
            // this delete, which is a race that really happens: a new draft lands
            // in the leftmost column, and the leftmost column is exactly the one
            // an admin tidying a board deletes. Clear and try once more rather
            // than report a violation over a row nobody can be asked to move.
            if (clearDrafts(projectId, kind, row.id) > 0) {
                deleteRow(kind, row.id)
                return
            }
            // Not a usage problem, then, and not one this class knows how to
            // phrase. Rethrow rather than invent an explanation — a 500 with a
            // stack trace beats a confident 400 that is wrong about why.
            throw violation
        }
    }

    private suspend fun deleteRow(kind: VocabularyKind, id: Long) {
        when (kind) {
            VocabularyKind.LABEL -> labels.delete(id)
            VocabularyKind.COMPONENT -> components.delete(id)
            VocabularyKind.STATUS -> statuses.delete(id)
            VocabularyKind.PRIORITY -> priorities.delete(id)
            VocabularyKind.RESOLUTION -> resolutions.delete(id)
            VocabularyKind.SPRINT -> sprints.delete(id)
            VocabularyKind.VERSION -> versions.delete(id)
        }
    }

    /**
     * Take the drafts pointing at a row with the row, and say how many went.
     *
     * A draft is an issue whose editor has not been saved: it is on nobody's
     * board, it has no number anyone has seen, and its author may well have closed
     * the tab on it a month ago. Counting one as "in use" is what made the first
     * column of a long-lived project permanently undeletable — refused over rows
     * its admin could not see, could not move, and had no way to learn about
     * (LNL-183). Deleting them here is the answer, and it is a narrow one: only
     * the two kinds an issue points at with a NOT NULL column, only this project,
     * and only rows that were never published.
     *
     * This does not weaken RESTRICT. The constraint still stands behind the
     * delete, still refuses to orphan a published issue, and is still what the
     * `catch` above translates into a sentence.
     */
    private suspend fun clearDrafts(projectId: Long, kind: VocabularyKind, id: Long): Long = when (kind) {
        VocabularyKind.STATUS -> issues.deleteDraftsWithStatus(projectId, id)
        VocabularyKind.PRIORITY -> issues.deleteDraftsWithPriority(projectId, id)
        // A draft is created with no resolution, no sprint and no version, and
        // nothing writes any of them before publish — so there is nothing to
        // clear, and a statement that would delete somebody's draft for holding a
        // label is not one worth having.
        else -> 0
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
     * @throws VocabularyRefusal if [ids] is not exactly this vocabulary.
     */
    override suspend fun reorder(projectId: Long, kind: VocabularyKind, ids: List<Long>) {
        val current = rows(projectId, kind).map { it.id }
        if (ids.size != current.size || ids.toSet() != current.toSet()) {
            throw VocabularyRefusal("That order does not name this project's ${kind.plural}.")
        }

        withContext(DatabaseDispatcher) {
            database.transaction {
                ids.forEachIndexed { index, id ->
                    val position = index.toLong()
                    // Exhaustive rather than an else branch, so a seventh kind is
                    // a compile error here and not a silent no-op.
                    when (kind) {
                        VocabularyKind.LABEL -> database.labelsQueries.setPosition(position, id)
                        VocabularyKind.COMPONENT -> database.componentsQueries.setPosition(position, id)
                        VocabularyKind.STATUS -> database.statusesQueries.setPosition(position, id)
                        VocabularyKind.PRIORITY -> database.prioritiesQueries.setPosition(position, id)
                        VocabularyKind.RESOLUTION -> database.resolutionsQueries.setPosition(position, id)
                        VocabularyKind.SPRINT -> database.sprintsQueries.setPosition(position, id)
                        VocabularyKind.VERSION -> database.versionsQueries.setPosition(position, id)
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
        VocabularyKind.SPRINT -> issues.usageBySprint(projectId)
        VocabularyKind.VERSION -> issues.usageByVersion(projectId)
    }

    private fun VocabularyRecord.toRow(uses: Map<Long, Long>) =
        VocabularyRow(id, projectId, name, position, usageCount = uses[id] ?: 0)

    private fun StatusRecord.toRow(uses: Map<Long, Long>) =
        VocabularyRow(id, projectId, name, position, requiresResolution, isDone, uses[id] ?: 0)

    /**
     * A sprint's completion instant is dropped here, and that is deliberate.
     *
     * [VocabularyRow] is the shape of "a named row an admin can rename, reorder
     * and delete", and completion is none of those things — it is read by the
     * board and by the activate/complete routes, which go through [SprintStore]
     * directly rather than through this class. Widening the row so one kind in
     * six could carry one more nullable would put a column on labels that could
     * only ever be null, for no caller's benefit.
     *
     * The Sprints section does need it since LNL-196 — each row shows its completion
     * date with a Complete-or-Reopen beside it — and it joins the sprint records back in
     * for itself rather than reaching through here. See
     * `ProjectSettingsRoutes.sprintEntries`.
     */
    private fun SprintRecord.toRow(uses: Map<Long, Long>) =
        VocabularyRow(id, projectId, name, position, usageCount = uses[id] ?: 0)
}
