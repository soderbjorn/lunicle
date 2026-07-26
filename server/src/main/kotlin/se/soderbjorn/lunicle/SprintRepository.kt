/**
 * The three things you can do to a sprint that you cannot do to a label.
 *
 * Adding, renaming, reordering and deleting a sprint are [VocabularyRepository]'s
 * job — a sprint is a per-project named ordered thing and that class already
 * knows how to keep one. What is left over is this file: activating one,
 * completing one, and saying which issues are in one. None of those has an
 * analogue among labels, components, statuses, priorities or resolutions, so
 * none of them belongs in the machinery that serves all five.
 *
 * ── The bulk writes deliberately notify nobody ──────────────────────────────
 *
 * `/api/issues/{id}/sprint` mails an issue's watchers, because moving one card
 * into next sprint is a discrete decision about that card. [setMembership] and
 * [complete] do not, though they write the same column on many rows at once, and
 * that asymmetry is a choice rather than an omission.
 *
 * A planning session that ticks twenty boxes is one decision, not twenty. Mailing
 * it as twenty would be the single loudest thing this tracker ever did, arriving
 * as a wall of near-identical messages from which no reader could tell what was
 * actually decided — and the reliable outcome of that is everybody muting issue
 * mail, which costs them the notifications that were worth having.
 *
 * The line is "did somebody make a decision about *this issue*", and for a sprint
 * plan the honest answer is no: they made a decision about the sprint. If that
 * ever needs announcing it wants its own message about the sprint, sent once —
 * not the per-issue notifier pressed into service.
 *
 * @see VocabularyRepository
 * @see Sprints.sq
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * A refused sprint write, carrying the sentence the caller should be shown.
 *
 * Deliberately not [VocabularyRefusal]: that one means "the vocabulary machinery
 * will not do this", and these mean "this sprint is not in a state where that
 * makes sense". They map to the same 400 and the routes translate both, but
 * conflating them would put sprint-specific rules inside the class that is
 * supposed to be kind-agnostic.
 */
class SprintRefusal(val userMessage: String) : Exception(userMessage)

/**
 * Activating, completing and populating sprints.
 *
 * @param database needed directly, not just through the stores: completing a
 *   sprint is four writes that must land together, and a transaction is exactly
 *   the thing a store cannot express. Same reason [ProjectRepository] holds it.
 * @param statuses read, never written — completion needs to know which of this
 *   project's columns count as finished, and that is `requires_resolution`.
 */
class SprintRepository(
    private val database: LunicleDatabase,
    private val sprints: SprintStore,
    private val projects: se.soderbjorn.lunicle.store.ProjectStore,
    private val issues: se.soderbjorn.lunicle.store.IssueStore,
    private val statuses: se.soderbjorn.lunicle.store.StatusStore,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.SprintStore {
    /**
     * Point the project's board at a sprint.
     *
     * There is no "deactivate the previous one" step, and its absence is the
     * design: at-most-one-active is `projects.active_sprint_id` rather than a
     * flag per row, so writing the new id *is* the deactivation of the old. Two
     * people racing to activate different sprints produce one winner and one
     * loser, which is the worst case; with a per-row flag the worst case is two
     * active sprints and no error anywhere.
     *
     * @param sprintId the sprint to activate, or null to leave the project with
     *   none — which is not the same as completing the current one. "Nothing is
     *   active right now" is a real state between sprints, and it is the state
     *   every project starts in.
     * @throws SprintRefusal if the sprint is not this project's, or is completed.
     */
    override suspend fun activate(projectId: Long, sprintId: Long?) {
        // Re-activating the sprint that is already active is allowed and writes the
        // same value again, rather than being refused. It is what a second click on
        // a slow connection sends, and an error for a request whose desired end
        // state already holds is a worse answer than doing nothing.
        if (sprintId != null) requireOpen(projectId, sprintId, verb = "activate")
        projects.setActiveSprint(projectId, sprintId)
    }

    /**
     * Finish a sprint and roll its unfinished work forward.
     *
     * Four writes, one transaction:
     *
     *  1. Move the unfinished issues to [moveUnfinishedTo].
     *  2. Stamp `completed_at`.
     *  3. Clear `projects.active_sprint_id`, if this was the active sprint.
     *
     * They must land together. A completion that stamped the sprint but failed to
     * move the work would strand every unfinished issue in a sprint that is over
     * and no longer offered anywhere — reachable only by an admin who thought to
     * look, and not repairable from the board at all, because a completed sprint
     * is not in the dropdown you would need to get them out of it.
     *
     * "Unfinished" is read off `statuses.requires_resolution`, never off a status
     * named "Closed" — see Issues.sq's `moveUnfinished`. A project that renamed
     * its closing column keeps working; a project with two closing columns gets
     * both respected.
     *
     * @param moveUnfinishedTo where the unfinished work goes: another sprint's id,
     *   or null for the backlog. Not defaulted, because "next sprint" and "back to
     *   the backlog" are genuinely different intentions and the dialog asks.
     * @throws SprintRefusal if either sprint is not this project's, if the sprint
     *   is already completed, or if the work would be moved into itself or into a
     *   sprint that is also finished.
     */
    override suspend fun complete(projectId: Long, sprintId: Long, moveUnfinishedTo: Long?) {
        requireOpen(projectId, sprintId, verb = "complete")

        if (moveUnfinishedTo != null) {
            if (moveUnfinishedTo == sprintId) {
                // Would silently no-op: the UPDATE's WHERE and SET name the same
                // sprint, so every issue would stay put and then be stranded by
                // the completion two statements later. Refuse rather than let the
                // caller think the work went somewhere.
                throw SprintRefusal("Unfinished work cannot be moved into the sprint being completed.")
            }
            requireOpen(projectId, moveUnfinishedTo, verb = "move unfinished work into")
        }

        val timestamp = now()

        withContext(DatabaseDispatcher) {
            database.transaction {
                database.issuesQueries.moveUnfinished(moveUnfinishedTo, timestamp, sprintId, projectId)
                database.sprintsQueries.complete(timestamp, sprintId)
                // Read INSIDE the transaction, not before it. Outside, two admins
                // racing — one completing the active sprint, one activating the
                // next — can interleave so that the completion's stale "yes, it
                // was active" clears an activation that already returned 200,
                // leaving the project with no active sprint and nobody aware.
                //
                // Only when this was the active one: completing next quarter's
                // sprint early must not deactivate the one being worked in.
                val active = database.projectsQueries.activeSprintId(projectId).executeAsOneOrNull()
                if (active?.active_sprint_id == sprintId) {
                    database.projectsQueries.setActiveSprint(null, projectId)
                }
            }
        }
    }

    /**
     * Set exactly which issues are in a sprint.
     *
     * The complete set, not a delta — the same convention as `IssueOrderUpdate`
     * and `VocabularyOrder`, and for the same two reasons. Retries are idempotent,
     * and two people planning the same sprint at once cannot interleave their
     * additions and removals into a set neither of them chose: the second save
     * wins wholesale, which is at least a set somebody meant.
     *
     * One transaction, because the clear and the re-add are halves of one
     * statement. Between them the sprint is empty, and a reader that landed there
     * would see a sprint someone had just planned as having nothing in it.
     *
     * @param issueIds the issues that should be in this sprint afterwards. Any not
     *   named are released to the backlog, not to some other sprint — "not in this
     *   sprint" has exactly one meaning.
     * @throws SprintRefusal if the sprint is not this project's, or any issue is
     *   not. Checked rather than trusted: `issues.sprint_id` is single-column, so
     *   nothing in the schema stops an issue pointing at another project's sprint.
     *   See Issues.sq.
     */
    override suspend fun setMembership(projectId: Long, sprintId: Long, issueIds: List<Long>) {
        // Open, not merely present. Work put into a finished sprint can never
        // leave it: `complete` refuses an already-completed sprint, so the one
        // statement that would move it forward will not run again. That is the
        // stranding this class exists to prevent, and it has to be refused here
        // as well as in the clients that hide the option — a board tab rendered
        // before somebody else pressed Complete still carries the old id.
        requireOpen(projectId, sprintId, verb = "plan")

        // Distinct, because the same id twice is a caller's mistake rather than a
        // request to add it twice — and a set is what the parameter means.
        val wanted = issueIds.distinct()
        for (issueId in wanted) {
            val issue = issues.findById(issueId)
            if (issue == null || issue.projectId != projectId) {
                throw SprintRefusal("One of those issues is not in this project.")
            }
        }

        // Who is being dropped, computed before anything is written: after the
        // clear there is no way to tell "was in this sprint and is leaving" from
        // "was never in it". These are the rows that need an updated_at bump —
        // the ones staying get theirs from setSprint below, and Issues.sq's
        // clearSprint deliberately stamps nobody.
        val current = issues.forProject(projectId)
        val leaving = current
            .filter { it.sprintId == sprintId && it.id !in wanted }
            .map { it.id }

        // Only the ones actually arriving get a new timestamp. Stamping every
        // ticked box would make opening the dialog and pressing Save — which
        // changes nothing — report twenty freshly-touched issues to everything
        // that reads recency. The dialog promises it is safe to open just to
        // look, and this is what makes that true.
        val arriving = wanted.filter { id -> current.firstOrNull { it.id == id }?.sprintId != sprintId }

        val timestamp = now()
        withContext(DatabaseDispatcher) {
            database.transaction {
                leaving.forEach { database.issuesQueries.touchSprintMembers(timestamp, it) }
                database.issuesQueries.clearSprint(sprintId)
                // Re-added with their own timestamps: unchanged members keep the
                // one they had, which clearSprint deliberately did not disturb.
                wanted.forEach { id ->
                    val stamp = if (id in arriving) timestamp else current.first { it.id == id }.updatedAt
                    database.issuesQueries.setSprint(sprintId, stamp, id)
                }
            }
        }
    }

    /**
     * Schedule one issue, or send it back to the backlog.
     *
     * The card menu's and the issue editor's write. Single-issue rather than a
     * one-element [setMembership], because the two mean different things: this
     * says "put this issue here" and leaves the rest of the sprint alone, where a
     * one-element membership set would empty it.
     *
     * @throws SprintRefusal if the sprint is not this issue's project's.
     */
    override suspend fun setIssueSprint(issue: IssueRecord, sprintId: Long?) {
        // A draft is not on anybody's board yet, so there is nothing to schedule
        // and no plan it could be part of. Refused rather than allowed-and-
        // ignored because a scheduled draft is invisible to `forProject`, which
        // is what the planning dialog and its permission check both read — so it
        // would sit in a sprint that no plan can see and no check can vouch for.
        // The editor writes the sprint at the moment it publishes, which is the
        // path a draft is meant to take.
        if (issue.isDraft) throw SprintRefusal("That issue has not been filed yet, so it cannot be scheduled.")
        if (sprintId != null) requireOpen(issue.projectId, sprintId, verb = "schedule work into")
        issues.setSprint(issue.id, sprintId)
    }

    /**
     * Check a sprint is this project's and still open, or say why not.
     *
     * The completed check is the point. A finished sprint is kept — `completed_at`
     * is history, and the whole reason it is an instant — but it is not a thing
     * you can activate, complete again, or roll work into. Without this, "complete"
     * on an already-finished sprint would quietly overwrite the instant it
     * finished, which is the one value here that cannot be recomputed.
     */
    private suspend fun requireOpen(projectId: Long, sprintId: Long, verb: String): SprintRecord {
        val sprint = sprints.findByIdInProject(sprintId, projectId)
            ?: throw SprintRefusal("That sprint is not in this project.")
        if (!sprint.isOpen) {
            throw SprintRefusal("\"${sprint.name}\" has already been completed, so you cannot $verb it.")
        }
        return sprint
    }

    /** This project's sprints, planning order. */
    override suspend fun forProject(projectId: Long): List<SprintRecord> = sprints.forProject(projectId)

    /** Which sprint this project's board scopes to by default, or null. */
    override suspend fun activeSprintId(projectId: Long): Long? = projects.activeSprintId(projectId)

    /** Which of this project's columns mean "finished". Used by the completion preview. */
    override suspend fun closingStatusIds(projectId: Long): Set<Long> =
        statuses.forProject(projectId).filter { it.requiresResolution }.map { it.id }.toSet()
}
