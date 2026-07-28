/**
 * The rules about issues that belong in neither a route nor a store.
 *
 * Two of them, and both are the same shape — several stores, or a store and the
 * filesystem, that have to move together:
 *
 *  - **Publish** writes the issue, its labels and its components together.
 *  - **Delete** lets the rows cascade, and then the attachment *files* have to
 *    go too. SQLite has no way to reach the filesystem, so a cascade silently
 *    leaves the bytes behind; this is where database and filesystem become one
 *    operation.
 *
 * @see ProjectRepository
 * @see AttachmentRepository
 */
package se.soderbjorn.lunicle

/**
 * Creates, publishes and deletes issues and their comments.
 *
 * @param statuses needed for one question a new issue cannot answer itself:
 *   which column it lands in.
 * @param attachmentStore read directly rather than through [attachments],
 *   because the query that matters here — "every file under this issue,
 *   including its comments'" — spans two tables and belongs to neither
 *   repository.
 * @param notifications fired after a write lands, so the one seam both transports
 *   share — publish, edit and comment all pass through here whether a browser or
 *   an agent asked — is also the one place an e-mail notification is triggered.
 *   Best-effort by design: see [notify].
 */
class IssueRepository(
    private val issues: se.soderbjorn.lunicle.store.IssueStore,
    private val comments: se.soderbjorn.lunicle.store.CommentStore,
    private val statuses: se.soderbjorn.lunicle.store.StatusStore,
    private val priorities: se.soderbjorn.lunicle.store.PriorityStore,
    private val attachments: AttachmentRepository,
    private val attachmentStore: se.soderbjorn.lunicle.store.AttachmentStore,
    private val notifications: IssueNotifier = NoNotifications,
    /**
     * Where the creator's auto-watch is written. Null in tests with no
     * subscription concern; the auto-watch is simply skipped then.
     */
    private val subscriptions: se.soderbjorn.lunicle.store.SubscriptionStore? = null,
    /**
     * Where an issue's history is derived and written, or null in tests that have
     * no interest in one — in which case nothing is recorded and no test needs to
     * assemble five vocabulary stores to save an issue.
     *
     * Only [save] reaches it. The other three writes that produce history — a
     * drag, an "Assign to me", and MCP's `move_issue` — do not pass through this
     * class at all and call [IssueHistory] from their own call sites; see that
     * class's preamble for why that split exists rather than being a smell.
     */
    private val history: IssueHistory? = null,
) {
    /**
     * Create the hidden draft the editor writes into.
     *
     * The row exists before the editor is filled in so that an inline image
     * upload has an issue to attach to — which is what keeps the `CHECK` in
     * Attachments.sq (exactly one owner) true at every moment rather than
     * eventually. Cancel deletes the row outright; `is_draft` covers the
     * closed-tab case, where the draft simply stays invisible.
     *
     * The issue lands in the board's leftmost column and at the middle of the
     * priority scale, both read from the database rather than hardcoded to "New"
     * and "Normal": the seed names them, and a project whose vocabulary was
     * renamed should still be able to take an issue.
     *
     * @param createdAt when the issue should claim to have been written, or null
     *   for now. Only an admin backfilling history over MCP passes it, and it must
     *   pass the same value to [save] — see that function.
     * @param agentName the agent filing on the author's behalf, or null when a
     *   human is. Only the MCP tools pass it; the web route leaves it null.
     * @return the new issue's id and its number.
     * @throws IllegalStateException if the project has no statuses or no
     *   priorities at all. That would mean [ProjectRepository.create] was
     *   bypassed, since it seeds both in the same transaction as the project row
     *   — and a project with no columns can neither take an issue nor be repaired
     *   from the UI, so failing loudly beats inventing one here.
     */
    suspend fun createDraft(
        projectId: Long,
        author: Author,
        createdAt: Long? = null,
        agentName: String? = null,
    ): Pair<Long, Long> {
        val first = statuses.firstForProject(projectId) ?: error(
            "Project $projectId has no statuses, so it cannot take an issue. Every project gets " +
                "its board columns in the same transaction as its row — see ProjectRepository.create.",
        )
        val priority = priorities.defaultForProject(projectId) ?: error(
            "Project $projectId has no priorities, so it cannot take an issue. Seeded in the same " +
                "transaction as the project row — see ProjectRepository.create — and backfilled for " +
                "every pre-existing project by 2.sqm.",
        )
        return issues.insertDraft(
            projectId,
            title = "",
            statusId = first.id,
            priorityId = priority.id,
            author = author,
            createdAt = createdAt,
            agentName = agentName,
        )
    }

    /**
     * Save the editor's fields, and publish the issue if it was a draft.
     *
     * One call for both, because they are the same write: an edit to a published
     * issue and the first save of a draft differ only in what `is_draft` ends up
     * as, and `publish` sets it to 0 unconditionally — which is already correct
     * for an issue where it is 0 already.
     *
     * The labels and components are replaced in their own transaction rather
     * than sharing this one. That is a real seam: an issue can publish and its
     * labels fail. It is bounded — the issue appears with no labels, which the
     * user fixes by reopening the modal — and closing it would mean threading
     * one transaction through three stores to protect against a dropped
     * connection to a local file.
     *
     * @param assigneeId who is to work on it, or null for nobody. No default, on
     *   purpose: see [IssueStore.publish]. A caller that is not editing the field
     *   — the MCP tools, which do not expose it — passes `issue.assigneeId`
     *   straight back, which reads as the deliberate no-op it is.
     * @param updatedAt what to stamp, or null for now — which is every caller but
     *   the backfill one. An admin backfilling over MCP must pass the same value
     *   it gave [createDraft], because publishing stamps `updated_at`
     *   unconditionally and would otherwise drag a 2019 issue's "last touched" to
     *   today, straddling the two columns Issues.sq requires to agree on a
     *   never-edited issue.
     * @param actor who the history should say did this, or null for nobody —
     *   which is a signed-out caller, and only reachable on a public project.
     *
     *   The *write's* attribution rather than the caller's account, and the two
     *   differ in exactly one place: an admin backfilling over MCP, where the
     *   point of the exercise is that the imported edit belongs to the person who
     *   originally made it. So the MCP paths pass what they are attributing the
     *   write to, which for every non-backfill call is the caller anyway.
     *
     *   Distinct from [actorId], which stays what it was — "who to avoid mailing,
     *   because they already know". An [Author] can be an imported name with no
     *   account, and there is nowhere to send that.
     * @param agentName the agent that made this change on the author's behalf, or
     *   null when a human did. Threaded through to the history and nowhere else:
     *   this deliberately does not write `issues.agent_name`, which has only ever
     *   had two writers and gains none here. See IssueEvents.sq's `agent_name`
     *   for why per-event is the whole point.
     */
    suspend fun save(
        issue: IssueRecord,
        title: String,
        description: String,
        statusId: Long,
        priorityId: Long,
        resolutionId: Long?,
        assigneeId: Long?,
        sprintId: Long?,
        plannedVersionId: Long?,
        fixedVersionId: Long?,
        labelIds: List<Long>,
        componentIds: List<Long>,
        updatedAt: Long? = null,
        actorId: Long? = null,
        actor: Author = Author.Nobody,
        agentName: String? = null,
    ) {
        // Captured before publish flips it: a draft becoming visible is a new
        // issue, and a save to one already published is an edit. The two fire
        // different notifications — a new-issue e-mail to the project's watchers,
        // an update e-mail to the issue's — so the distinction is load-bearing.
        val wasDraft = issue.isDraft
        // Captured for the same reason, and compared rather than assumed: the
        // editor sends its whole field set on every save, so an unchanged assignee
        // arrives here looking exactly like a fresh one. Mailing on every save
        // would turn a typo fix into "you have been assigned this" for somebody who
        // has held the issue for a week. See IssueNotifier.issueAssigned.
        val previousAssignee = issue.assigneeId
        // Read before the write, because the write destroys them:
        // setLabelsAndComponents is wholesale — delete-then-insert — so once it
        // has run there is nowhere left to discover what the labels used to be,
        // and the history would have to either report every save as a label
        // change or report none of them. Skipped entirely when there is no
        // history to derive, so the ordinary two-query cost is not paid by tests
        // and callers that asked for none.
        val previousLabels = if (history != null && !issue.isDraft) issues.labelsFor(issue.id) else emptyList()
        val previousComponents = if (history != null && !issue.isDraft) issues.componentsFor(issue.id) else emptyList()
        issues.publish(
            issue.id, title, description, statusId, priorityId, resolutionId, assigneeId, sprintId,
            plannedVersionId, fixedVersionId, updatedAt,
        )
        issues.setLabelsAndComponents(issue.id, issue.projectId, labelIds, componentIds)

        // Before the notifications, and before the early return below: a re-read
        // that comes back empty means the issue vanished under us, and the
        // history of what just happened to it is the one thing still worth
        // having. Whether the issue was a draft decides which of the two shapes
        // this is — a creation, or a diff — for `wasDraft`'s reasons above.
        if (wasDraft) {
            history?.recordCreated(issue, actor, agentName, updatedAt)
        } else {
            history?.recordSaved(
                before = issue,
                beforeLabelIds = previousLabels,
                beforeComponentIds = previousComponents,
                title = title,
                description = description,
                statusId = statusId,
                assigneeId = assigneeId,
                labelIds = labelIds,
                componentIds = componentIds,
                author = actor,
                agentName = agentName,
                createdAt = updatedAt,
            )
        }

        // Re-read so the message carries the title and number as they now stand,
        // not the pre-save record's.
        val saved = issues.findById(issue.id) ?: return
        if (wasDraft) {
            // The creator watches their own issue from the moment it is published,
            // so they hear about everyone else's later changes to it. Keyed on the
            // author's account: an imported issue with an external author has none,
            // and auto-watches nobody. Idempotent, and best-effort like the notify.
            issue.author.accountId?.let { authorId ->
                notify { subscriptions?.setIssueUpdateSubscription(authorId, issue.id, true) }
            }
        }
        notify {
            if (wasDraft) notifications.issueCreated(saved, actorId)
            else notifications.issueUpdated(saved, actorId, "edited")
        }
        // Anyone newly named in the description. The *old* description is what
        // makes this fire once rather than on every save — and a draft has no old
        // one, because nobody has ever read it, so publishing one mentions
        // everybody in it for the first time. See IssueNotifier.issueMentioned.
        notify {
            notifications.issueMentioned(
                issue = saved,
                body = description,
                previousBody = if (wasDraft) "" else issue.description,
                actorId = actorId,
                context = "the description",
            )
        }
        // Separate from the branch above rather than folded into it, because it is
        // orthogonal to both: publishing a draft *with* an assignee already chosen
        // must tell that person, and so must an edit that hands an existing issue
        // over. The guard is the change, not the kind of save.
        if (assigneeId != null && assigneeId != previousAssignee) {
            notify { notifications.issueAssigned(saved, assigneeId, actorId) }
        }
    }

    /**
     * Attach an issue to an epic, or detach it with null — and enforce the three
     * rules the schema cannot (LNL-55).
     *
     * The rules live here, not in a route or the store, because they are exactly
     * "several reads that decide a write" and both callers — the web route and the
     * MCP `parent` field — must apply them identically:
     *
     *  - **Same project.** A parent in another project would put a child on a board
     *    it does not belong to; `parent_id` cannot be composite-keyed to forbid it
     *    (see Issues.sq), so it is checked here.
     *  - **One level.** An epic is a parent and a leaf, never a grandparent: the
     *    parent must not itself have a parent, and this issue must not already have
     *    children of its own. Those two together also make a cycle impossible
     *    without a walk — a 2-cycle needs both ends to be non-root, which the first
     *    half forbids — so the only cycle left to reject by hand is the self-parent.
     *  - **A real, published parent.** Attaching under a draft would hang a child
     *    off an issue nobody can see yet.
     *
     * Returns a [Result] carrying the refusal message rather than throwing, so the
     * route can turn it into a 400 that says which rule — the same shape
     * `resolveResolution` and friends use. Detaching (null) always succeeds.
     */
    suspend fun setParent(issue: IssueRecord, parentId: Long?): Result<Unit> {
        if (parentId == null) {
            issues.setParent(issue.id, null)
            return Result.success(Unit)
        }
        if (parentId == issue.id) {
            return Result.failure(IllegalArgumentException("An issue cannot be its own parent."))
        }
        val parent = issues.findById(parentId)
            ?: return Result.failure(IllegalArgumentException("No such parent issue."))
        if (parent.projectId != issue.projectId) {
            return Result.failure(IllegalArgumentException("A parent issue must be in the same project."))
        }
        if (parent.isDraft) {
            return Result.failure(IllegalArgumentException("That issue is not published yet, so it cannot be a parent."))
        }
        if (parent.parentId != null) {
            return Result.failure(
                IllegalArgumentException("That issue is already a child of an epic, and epics are one level deep."),
            )
        }
        if (issues.childrenOf(issue.id).isNotEmpty()) {
            return Result.failure(
                IllegalArgumentException("This issue has children of its own, so it cannot become a child."),
            )
        }
        issues.setParent(issue.id, parentId)
        // Append to the BOTTOM of the epic's work order, rather than leaving the
        // newcomer unranked — which would sort it to the top, since child_order 0
        // sorts before the ranked siblings. A child added to an epic is the next
        // thing to do, not the first, so renumber the set with it last. `childrenOf`
        // already returns the current order; move the newcomer to the end and
        // renumber 1..n, which also makes any previously-unranked siblings explicit.
        val ordered = issues.childrenOf(parentId).map { it.id }.filter { it != issue.id } + issue.id
        issues.setChildOrder(ordered)
        return Result.success(Unit)
    }

    /**
     * Rank one epic's children, in the order given — validated as the whole set
     * (LNL-55).
     *
     * The list must be exactly this epic's current children: same members, no
     * repeats. A list that adds, drops or borrows a child is refused whole rather
     * than half-applied, so a stale editor cannot silently detach a child by
     * omitting it — detaching is [setParent]'s job, done deliberately. Same
     * set-validated-then-renumber shape as the board's `setGroupOrder` route.
     */
    suspend fun reorderChildren(parent: IssueRecord, childIds: List<Long>): Result<Unit> {
        if (childIds.distinct() != childIds) {
            return Result.failure(IllegalArgumentException("That order repeats a child."))
        }
        val actual = issues.childrenOf(parent.id).map { it.id }.toSet()
        if (childIds.toSet() != actual) {
            return Result.failure(IllegalArgumentException("That is not exactly this issue's set of children."))
        }
        issues.setChildOrder(childIds)
        return Result.success(Unit)
    }

    /**
     * Delete an issue and everything that hangs off it — its comments, its history,
     * the watches on it, its attachment rows, and every file behind any of them.
     *
     * The files must be *found* first, because once the rows are gone nothing knows
     * which files they were. So: collect the keys, delete the rows, then unlink.
     *
     * ── Why every child is deleted explicitly ────────────────────────────────
     *
     * SQLite's `ON DELETE CASCADE` would take all of it for free. The Firestore
     * backend has no cascade at all — deleting a document deletes exactly that
     * document — and this is one function serving both, so each child is swept by
     * name. Left implicit, they simply stayed: attachment rows pointing at objects
     * nothing could name again (LNL-145, fixed there), and then comments, history
     * and watches on an issue that no longer existed (LNL-177, this). Those are
     * documents no query in the app can reach, billable forever, and — for a comment
     * on a deleted issue in a deleted private project — a privacy problem, not just
     * a storage one.
     *
     * Two things that hang off an issue are deliberately *not* swept, because SQLite
     * does not sweep them either and a cascade the reference backend lacks is a
     * divergence rather than a fix: **notifications**, whose `dest_issue_id` is
     * pointedly not a foreign key (a notification about a deleted issue is stale, and
     * the client says so, but it is still a record that something happened), and
     * **read marks**, which do not exist at issue level at all. See
     * [FirestoreCascade] for the long version.
     *
     * The labels and components need no sweep on either backend: SQLite cascades
     * their join tables, and Firestore keeps them as arrays on the issue document, so
     * they go when it does.
     *
     * ── Order, and what a crash leaves ───────────────────────────────────────
     *
     * Children before the issue itself, so a failure part-way leaves an issue that
     * still exists and can be deleted again — every step is "delete what names this
     * id", so re-running finishes the job. The reverse order would leave unreachable
     * debris instead.
     *
     * A file that fails to unlink is left on the volume and collected by
     * [AttachmentRepository.sweepOrphans] at the next restart — which is why
     * this deliberately does not try to be transactional. It cannot be.
     *
     * Any children are detached first (LNL-55). SQLite's `ON DELETE SET NULL` would
     * orphan them for free, but the Firestore backend has no cascade to lean on, so
     * doing it explicitly is what keeps the two backends behaving identically —
     * deleting an epic releases its children, it does not delete them.
     */
    suspend fun delete(issue: IssueRecord) {
        issues.childrenOf(issue.id).forEach { issues.setParent(it.id, null) }
        val doomed = attachmentStore.keysForIssue(issue.id)
        attachmentStore.deleteForIssue(issue.id)
        comments.deleteForIssue(issue.id)
        history?.deleteForIssue(issue.id)
        subscriptions?.deleteIssueSubscriptions(issue.id)
        issues.delete(issue.id)
        doomed.forEach { attachments.deleteBlob(it) }
    }

    /**
     * Create the hidden draft comment an inline image can hang off.
     *
     * @param createdAt when it should claim to have been written, or null for now.
     *   The backfill path's only lever here; see [CommentStore.insertDraft].
     * @param agentName the agent commenting on the author's behalf, or null when a
     *   human is. Only the MCP tools pass it; the web route leaves it null.
     */
    suspend fun createCommentDraft(
        issueId: Long,
        author: Author,
        createdAt: Long? = null,
        agentName: String? = null,
    ): Long = comments.insertDraft(issueId, author, createdAt, agentName)

    /**
     * Save a comment's body and publish it. Same reasoning as [save].
     *
     * A comment being published *for the first time* is a new comment, which is
     * an update to its issue — so it notifies the issue's watchers. Re-saving an
     * already-published comment is an edit and stays quiet: watchers do not need
     * an e-mail every time someone fixes a typo in a comment they already read.
     *
     * Mentions are the one thing an *edit* still notifies about, and deliberately
     * so: adding "@Ada, thoughts?" to a comment already posted is exactly how
     * somebody pulls a person in, and it must reach her. Only the newly added
     * names are mailed — which is why the previous body is read before the write
     * destroys it. See [IssueNotifier.issueMentioned].
     */
    suspend fun saveComment(id: Long, body: String, actorId: Long? = null) {
        val before = comments.findById(id)
        val wasDraft = before?.isDraft == true
        // A draft has never been read by anyone, so whatever is in it counts as
        // never-yet-mentioned however many times it was auto-saved.
        val previousBody = if (wasDraft) "" else before?.body.orEmpty()
        comments.publish(body = body, id = id)
        val comment = comments.findById(id) ?: return
        val issue = issues.findById(comment.issueId) ?: return
        if (wasDraft) {
            notify { notifications.issueUpdated(issue, actorId, "commented on") }
        }
        notify {
            notifications.issueMentioned(
                issue = issue,
                body = body,
                previousBody = previousBody,
                actorId = actorId,
                context = "a comment",
            )
        }
    }

    /**
     * Delete a comment and the files it owns. Same ordering rule as [delete].
     */
    suspend fun deleteComment(comment: CommentRecord) {
        val doomed = attachmentStore.keysForComment(comment.id)
        comments.delete(comment.id)
        doomed.forEach { attachments.deleteBlob(it) }
    }

    /**
     * Run a notification, swallowing anything it throws.
     *
     * A notification is a side effect of a write that has already succeeded — the
     * issue is saved, the comment is published — so a failure here must never
     * propagate back and make the write look like it failed. The write is the
     * commitment; the e-mail is a courtesy, and a courtesy that throws is still
     * only a missed courtesy. Logged, not raised.
     */
    private suspend fun notify(block: suspend () -> Unit) {
        runCatching { block() }.onFailure {
            logger.warn("Notification dispatch failed after a write; the write itself is unaffected", it)
        }
    }

    private companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger("IssueRepository")
    }
}
