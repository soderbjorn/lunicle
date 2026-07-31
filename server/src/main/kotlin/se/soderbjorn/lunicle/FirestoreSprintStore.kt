/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.SprintStore] — the
 * sprint-specific lifecycle (activate, complete, membership) that a label has no
 * analogue for. Adding/renaming/reordering/deleting a sprint is
 * [FirestoreVocabularyStore]'s job; this is the leftover, mirroring
 * `SprintRepository` over the SQLite stores.
 *
 * ── The parity-critical rule: anti-stranding ────────────────────────────────
 *
 * A completed sprint accepts no activation and no membership change, and
 * completing one rolls its unfinished work somewhere reachable rather than
 * leaving it behind. That is what [requireOpen] guards and what [complete]
 * guarantees, the same two things `SprintRepository` guards and guarantees. Work
 * left in a finished sprint could never leave it — [complete] refuses an
 * already-completed sprint, so the one write that moves work forward would not run
 * again — which is why the check is here and not only in the clients that hide the
 * option.
 *
 * ── What it reads, and from where ───────────────────────────────────────────
 *
 * A sprint is a `kind == SPRINT` row in the `vocabulary` collection, carrying the
 * one field no other vocabulary has — `completedAt` (null while open). This store
 * reads those rows, and the `kind == STATUS` rows (for which columns are
 * *closing*, i.e. `requiresResolution`), directly from that collection through
 * [FirestoreVocabularyStore]'s shared constants. It reaches the *issues* through
 * an injected [IssueStore] (membership, and rolling unfinished work forward), and
 * the project's active-sprint pointer through an injected [ProjectStore] — the
 * same collaborators, in the same roles, `SprintRepository` takes.
 *
 * "Unfinished" is read off `requiresResolution`, never a status literally named
 * "Closed": a project that renamed its closing column keeps working, and one with
 * two closing columns has both respected. See Issues.sq's `moveUnfinished`.
 *
 * @see FirestoreProvider
 * @see FirestoreVocabularyStore
 * @see se.soderbjorn.lunicle.store.SprintStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.store.IssueStore
import se.soderbjorn.lunicle.store.ProjectStore
import se.soderbjorn.lunicle.store.SprintStore

class FirestoreSprintStore(
    private val firestore: Firestore,
    private val projects: ProjectStore,
    private val issues: IssueStore,
    private val now: () -> Long = System::currentTimeMillis,
) : SprintStore {
    private fun vocab() = firestore.collection(FirestoreVocabularyStore.COLLECTION)
    private fun sprintDoc(id: Long) = vocab().document(id.toString())

    /**
     * Point the project's board at a sprint, or null for none — which is a real
     * state between sprints, not the same as completing one.
     *
     * Re-activating the already-active sprint is allowed (it writes the same value)
     * rather than refused: an error for a request whose end state already holds is
     * a worse answer than doing nothing.
     */
    override suspend fun activate(projectId: Long, sprintId: Long?) {
        if (sprintId != null) requireOpen(projectId, sprintId, verb = "activate")
        projects.setActiveSprint(projectId, sprintId)
    }

    /**
     * Finish a sprint and roll its unfinished work forward.
     *
     * The unfinished issues move first, the sprint is stamped completed, and the
     * project's active pointer is cleared only if it named this sprint. In SQLite
     * these are one transaction; here they are ordered writes with the completion
     * stamp last, so a crash leaves the sprint still open (and its work still
     * reachable) rather than stamped-but-not-emptied — the safe half to fail on.
     *
     * @throws SprintRefusal if either sprint is not this project's, is already
     *   completed, or the work would move into itself or a finished sprint.
     */
    override suspend fun complete(projectId: Long, sprintId: Long, moveUnfinishedTo: Long?) {
        requireOpen(projectId, sprintId, verb = "complete")
        if (moveUnfinishedTo != null) {
            if (moveUnfinishedTo == sprintId) {
                throw SprintRefusal("Unfinished work cannot be moved into the sprint being completed.")
            }
            requireOpen(projectId, moveUnfinishedTo, verb = "move unfinished work into")
        }

        // "Unfinished" = in this sprint and not in a closing status. Moved to the
        // target sprint, or to the backlog (null). setSprint bumps updated_at, as
        // moveUnfinished does.
        val closing = closingStatusIds(projectId)
        issues.forProject(projectId)
            .filter { it.sprintId == sprintId && it.statusId !in closing }
            .forEach { issues.setSprint(it.id, moveUnfinishedTo) }

        sprintDoc(sprintId).update(FirestoreVocabularyStore.COMPLETED_AT, now()).await()

        // Only when this was the active one: completing next quarter's sprint early
        // must not deactivate the one being worked in.
        if (projects.activeSprintId(projectId) == sprintId) {
            projects.setActiveSprint(projectId, null)
        }
    }

    /**
     * Clear a sprint's completion stamp, and nothing else (LNL-196).
     *
     * Not the inverse of [complete]: the rolled-forward work stays where it went and
     * the project's active pointer is untouched. Idempotent on an already-open sprint,
     * like [activate]. See `SprintRepository.reopen` for the full reasoning.
     *
     * Existence and ownership are checked by hand rather than through [requireOpen],
     * which is the one call site that must NOT insist on openness — a completed sprint
     * is exactly what this is for.
     *
     * @throws SprintRefusal if the sprint is not this project's.
     */
    override suspend fun reopen(projectId: Long, sprintId: Long) {
        val snap = sprintDoc(sprintId).get().await()
        if (!snap.exists() ||
            snap.getString(FirestoreVocabularyStore.KIND) != VocabularyKind.SPRINT.name ||
            snap.getLong(FirestoreVocabularyStore.PROJECT_ID) != projectId
        ) {
            throw SprintRefusal("That sprint is not in this project.")
        }
        // An explicit null rather than a field delete: `toSprint` reads the field with
        // getLong, which answers null for both — but a missing field and a null one are
        // different documents, and the SQLite side writes NULL into a column that is
        // always present. Keeping the field makes the two backends round-trip the same.
        sprintDoc(sprintId).update(FirestoreVocabularyStore.COMPLETED_AT, null).await()
    }

    /**
     * Set exactly which issues are in a sprint — the complete set, not a delta.
     *
     * Refused for a completed sprint (work put there could never leave it) or one
     * that is not this project's, and for any issue that is not this project's —
     * checked rather than trusted, since an issue's `sprintId` is a single field
     * with no composite key to stop it pointing across projects.
     */
    override suspend fun setMembership(projectId: Long, sprintId: Long, issueIds: List<Long>) {
        requireOpen(projectId, sprintId, verb = "plan")

        val wanted = issueIds.distinct()
        for (issueId in wanted) {
            val issue = issues.findById(issueId)
            if (issue == null || issue.projectId != projectId) {
                throw SprintRefusal("One of those issues is not in this project.")
            }
        }

        // Whoever is in this sprint but not named is released to the backlog; the
        // named ones are set into it. Between the two a reader could see a partial
        // set — the one asymmetry from the SQLite single-transaction replace, and
        // harmless to the membership the contract pins.
        val current = issues.forProject(projectId)
        current.filter { it.sprintId == sprintId && it.id !in wanted }
            .forEach { issues.setSprint(it.id, null) }
        wanted.forEach { issues.setSprint(it, sprintId) }
    }

    /**
     * Schedule one issue, or send it to the backlog — leaving the rest of the
     * sprint alone (unlike a one-element [setMembership], which would empty it).
     *
     * A draft is refused: it is invisible to [IssueStore.forProject], which the
     * planning dialog and its permission check both read, so a scheduled draft
     * would sit where no plan can see it. The editor writes the sprint when it
     * publishes, which is the path a draft is meant to take.
     */
    override suspend fun setIssueSprint(issue: IssueRecord, sprintId: Long?) {
        if (issue.isDraft) throw SprintRefusal("That issue has not been filed yet, so it cannot be scheduled.")
        if (sprintId != null) requireOpen(issue.projectId, sprintId, verb = "schedule work into")
        issues.setSprint(issue.id, sprintId)
    }

    /** This project's sprints, in planning order. */
    override suspend fun forProject(projectId: Long): List<SprintRecord> =
        vocab().whereEqualTo(FirestoreVocabularyStore.PROJECT_ID, projectId).get().await()
            .documents
            .filter { it.getString(FirestoreVocabularyStore.KIND) == VocabularyKind.SPRINT.name }
            .sortedBy { it.getLong(FirestoreVocabularyStore.POSITION) ?: 0L }
            .map { it.toSprint() }

    override suspend fun activeSprintId(projectId: Long): Long? = projects.activeSprintId(projectId)

    /** Which of this project's columns mean "finished" — those that require a resolution. */
    override suspend fun closingStatusIds(projectId: Long): Set<Long> =
        vocab().whereEqualTo(FirestoreVocabularyStore.PROJECT_ID, projectId).get().await()
            .documents
            .filter {
                it.getString(FirestoreVocabularyStore.KIND) == VocabularyKind.STATUS.name &&
                    (it.getBoolean(FirestoreVocabularyStore.REQUIRES_RESOLUTION) ?: false)
            }
            .mapNotNull { it.getLong(FirestoreVocabularyStore.ID) }
            .toSet()

    /**
     * Check a sprint is this project's and still open, or say why not — the
     * completed check being the point, since a finished sprint is kept as history
     * but cannot be activated, completed again, or rolled work into.
     */
    private suspend fun requireOpen(projectId: Long, sprintId: Long, verb: String): SprintRecord {
        val snap = sprintDoc(sprintId).get().await()
        if (!snap.exists() ||
            snap.getString(FirestoreVocabularyStore.KIND) != VocabularyKind.SPRINT.name ||
            snap.getLong(FirestoreVocabularyStore.PROJECT_ID) != projectId
        ) {
            throw SprintRefusal("That sprint is not in this project.")
        }
        val sprint = snap.toSprint()
        if (!sprint.isOpen) {
            throw SprintRefusal("\"${sprint.name}\" has already been completed, so you cannot $verb it.")
        }
        return sprint
    }

    private fun com.google.cloud.firestore.DocumentSnapshot.toSprint(): SprintRecord = SprintRecord(
        id = getLong(FirestoreVocabularyStore.ID)!!,
        projectId = getLong(FirestoreVocabularyStore.PROJECT_ID)!!,
        name = getString(FirestoreVocabularyStore.NAME).orEmpty(),
        position = getLong(FirestoreVocabularyStore.POSITION) ?: 0L,
        completedAt = getLong(FirestoreVocabularyStore.COMPLETED_AT),
    )
}
