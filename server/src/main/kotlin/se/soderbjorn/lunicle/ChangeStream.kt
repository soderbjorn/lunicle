/**
 * The change stream's server half: one bus every write publishes to, and the store
 * decorators that do the publishing (LNL-224).
 *
 * ── Thin events, so permissions stay where they are ─────────────────────────
 *
 * An event says *that* something changed and where — an issue id, a comment id, the
 * issue's new `updatedAt`, who did it — and never *what* it now says. A client that
 * wants the content re-reads it through the read path it already uses, and that read
 * asks AccessControl exactly as it always has. So the stream cannot leak a field the
 * caller could not already read: the worst it can say is "issue 41 changed", about a
 * project the stream re-checks the caller may see (see `ChangeStreamRoutes`).
 *
 * ── Published from the stores, not from the routes ──────────────────────────
 *
 * The obvious seam is [IssueRepository], and it is the wrong one: a drag, an
 * "Assign to me", MCP's `move_issue`, a sprint completion and a vocabulary delete all
 * write without passing through it (IssueRepository's own `history` note lists the
 * first three). The store interfaces are the one layer *every* write crosses —
 * web, REST and MCP alike, and every repository built above them — so the decorators
 * here wrap them once, in `Application.module`, and no call site can forget. Drafts
 * are invisible to everybody, so a write that leaves its issue or comment a draft
 * publishes nothing.
 *
 * ── Who did it, and from which tab ───────────────────────────────────────────
 *
 * A store does not know who is asking, and threading a user through every store
 * method would be a change to forty signatures for one label. So the request carries
 * it instead, as [ChangeOrigin] in the coroutine context — the same trick
 * [ApiTokenAttribution] uses for history. An application-level interceptor puts one
 * on every request, with the `X-Lunicle-Origin` header if the client sent one; the
 * three places a request is authenticated ([resolveCaller], the REST API's and MCP's
 * token checks) fill in the actor. A write made outside any request — a boot sweep —
 * has no origin and publishes an event with no actor, which is the truth.
 *
 * ── One process (a stated v1 limit) ──────────────────────────────────────────
 *
 * The bus is in memory, like [RateLimiter]. On a deployment with more than one server
 * instance a stream sees only the writes its own instance served. Both live
 * deployments run one instance; one that scales out needs a shared channel (Firestore
 * listeners, Pub/Sub) behind [ChangeBus.publish], and nothing above that function
 * would change.
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import se.soderbjorn.lunicle.clientserver.Estimate
import se.soderbjorn.lunicle.clientserver.VocabularyKind
import se.soderbjorn.lunicle.store.CommentStore
import se.soderbjorn.lunicle.store.IssueRelationStore
import se.soderbjorn.lunicle.store.IssueStore
import se.soderbjorn.lunicle.store.NotificationStore
import se.soderbjorn.lunicle.store.SprintStore
import se.soderbjorn.lunicle.store.VocabularyStore
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What kinds of change there are. The wire name is [key], and it is the SSE `event:`
 * field verbatim.
 */
enum class ChangeKind(val key: String) {
    ISSUE_CREATED("issue.created"),
    ISSUE_UPDATED("issue.updated"),
    /** Its column or its place in the column changed. */
    ISSUE_MOVED("issue.moved"),
    ISSUE_DELETED("issue.deleted"),
    COMMENT_ADDED("comment.added"),
    COMMENT_EDITED("comment.edited"),
    COMMENT_DELETED("comment.deleted"),
    /**
     * Something about the board as a whole: its vocabulary (a column renamed, added,
     * deleted or reordered) or its sprints. No issue id; re-read the board.
     */
    BOARD_CHANGED("board.changed"),
    /**
     * The one user-scoped kind: this person's notifications changed — one arrived, or
     * one was read or dismissed in another tab. Carries nothing but itself.
     */
    NOTIFICATION_CHANGED("notification.changed"),
}

/**
 * One change, as the bus holds it.
 *
 * Exactly one of [projectId] and [userId] is set: a project event goes to anybody
 * watching that project, a user event to that user's own streams and nobody else's.
 *
 * @property id monotonic across the whole process, and the SSE `id:`.
 * @property updatedAt the issue's `updatedAt` after the write, or the time of the
 *   change where there is no issue row left to ask (a delete) or none at all.
 * @property origin the writing tab's `X-Lunicle-Origin`, compared with a stream's own
 *   and never sent: see `ChangeStreamRoutes`.
 */
data class ChangeEvent(
    val id: Long,
    val kind: ChangeKind,
    val projectId: Long?,
    val userId: Long?,
    val issueId: Long?,
    val commentId: Long?,
    val updatedAt: Long,
    val actor: String?,
    val origin: String?,
)

/**
 * The request a write is being made for: which tab sent it, and who is signed in.
 *
 * Mutable on purpose, and only in [actor]: the interceptor that creates it runs before
 * authentication, so it cannot know the user, and the authentication that does know
 * runs inside its scope. One request is one coroutine, so there is no race to guard.
 */
class ChangeOrigin(val origin: String?) : AbstractCoroutineContextElement(Key) {
    @Volatile
    var actor: UserRecord? = null

    companion object Key : CoroutineContext.Key<ChangeOrigin> {
        /** Accept an origin only if it looks like an id — never echo arbitrary header text anywhere. */
        fun sanitise(raw: String?): String? =
            raw?.trim()?.takeIf { it.length in 1..64 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
    }
}

/** Note who this request is, for any change it goes on to publish. A no-op outside a request. */
internal suspend fun noteChangeActor(user: UserRecord?) {
    currentCoroutineContext()[ChangeOrigin]?.actor = user
}

/**
 * Where every change goes, and where every stream reads from.
 *
 * Holds the last [capacity] events across all projects and users, which is the replay
 * buffer `Last-Event-ID` resumes from; a client further behind than that is told to
 * start again (see [open]). Ids start at the boot time in microseconds, so an id from
 * a previous process can never be mistaken for one from this one: it is either below
 * the buffer or, if the clock went backwards, above the newest, and both read as a gap.
 */
class ChangeBus(
    private val capacity: Int = 2048,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val recent = ArrayDeque<ChangeEvent>()
    private val listeners = LinkedHashSet<Listener>()
    private var lastId: Long = now() * 1000

    /**
     * One open stream's inbox. Bounded: a stream that cannot keep up is marked
     * [overflowed] rather than allowed to hold the server's memory hostage, and the
     * stream answers that by telling its client to re-read (`event: reset`).
     */
    class Listener internal constructor() {
        val events: Channel<ChangeEvent> = Channel(LISTENER_CAPACITY)

        @Volatile
        var overflowed: Boolean = false
    }

    /**
     * What [open] hands back.
     *
     * @property replay the events after the client's `Last-Event-ID`, oldest first —
     *   or null when they cannot all be supplied, which means "send a reset".
     */
    class Opened(val listener: Listener, val replay: List<ChangeEvent>?)

    /** Publish one change. Never suspends and never throws at the writer. */
    fun publish(
        kind: ChangeKind,
        projectId: Long? = null,
        userId: Long? = null,
        issueId: Long? = null,
        commentId: Long? = null,
        updatedAt: Long? = null,
        actor: String? = null,
        origin: String? = null,
    ): ChangeEvent = synchronized(lock) {
        val event = ChangeEvent(
            id = ++lastId,
            kind = kind,
            projectId = projectId,
            userId = userId,
            issueId = issueId,
            commentId = commentId,
            updatedAt = updatedAt ?: now(),
            actor = actor,
            origin = origin,
        )
        recent.addLast(event)
        while (recent.size > capacity) recent.removeFirst()
        // Inside the lock, so every listener sees events in id order.
        listeners.forEach { if (it.events.trySend(event).isFailure) it.overflowed = true }
        event
    }

    /**
     * Start listening, resuming after [lastEventId] if there is one.
     *
     * Registration and the replay snapshot happen under one lock, so no event can fall
     * between the two: anything published after the snapshot is in the listener's
     * channel, and nothing in the snapshot is.
     */
    fun open(lastEventId: Long?): Opened = synchronized(lock) {
        val listener = Listener()
        listeners += listener
        val replay = when {
            lastEventId == null -> emptyList()
            lastEventId == lastId -> emptyList()
            lastEventId > lastId -> null
            // Resumable only if the event right after it is still held.
            recent.isEmpty() || lastEventId < recent.first().id - 1 -> null
            else -> recent.filter { it.id > lastEventId }
        }
        Opened(listener, replay)
    }

    /** Stop listening. */
    fun close(listener: Listener) {
        synchronized(lock) { listeners -= listener }
        listener.events.close()
    }

    /** How many streams are open, for the log and for tests. */
    val listenerCount: Int get() = synchronized(lock) { listeners.size }

    private companion object {
        const val LISTENER_CAPACITY = 1024
    }
}

/** Publish [kind] for the current request's actor and origin. */
private suspend fun ChangeBus.publishHere(
    kind: ChangeKind,
    projectId: Long? = null,
    userId: Long? = null,
    issueId: Long? = null,
    commentId: Long? = null,
    updatedAt: Long? = null,
) {
    val origin = currentCoroutineContext()[ChangeOrigin]
    publish(
        kind = kind,
        projectId = projectId,
        userId = userId,
        issueId = issueId,
        commentId = commentId,
        updatedAt = updatedAt,
        actor = origin?.actor?.resolvedName,
        origin = origin?.origin,
    )
}

// ── The decorators ───────────────────────────────────────────────────────────
//
// Each delegates everything and overrides only the writes. Each write reads the row
// afterwards (and, where the answer depends on it, before) — one extra read per write,
// which is what it costs to know whether the row is a draft and what its project is.

/** Publishes the issue kinds. See the file preamble. */
class PublishingIssueStore(
    private val delegate: IssueStore,
    private val bus: ChangeBus,
) : IssueStore by delegate {

    private suspend fun announce(id: Long, kind: ChangeKind) {
        val issue = delegate.findById(id) ?: return
        if (issue.isDraft) return
        bus.publishHere(kind, projectId = issue.projectId, issueId = issue.id, updatedAt = issue.updatedAt)
    }

    override suspend fun publish(
        id: Long,
        title: String,
        description: String,
        statusId: Long,
        priorityId: Long,
        resolutionId: Long?,
        assigneeId: Long?,
        assigneeIsAgent: Boolean,
        sprintId: Long?,
        plannedVersionId: Long?,
        fixedVersionId: Long?,
        estimate: Estimate?,
        updatedAt: Long?,
    ) {
        val wasDraft = delegate.findById(id)?.isDraft == true
        delegate.publish(
            id, title, description, statusId, priorityId, resolutionId, assigneeId, assigneeIsAgent,
            sprintId, plannedVersionId, fixedVersionId, estimate, updatedAt,
        )
        announce(id, if (wasDraft) ChangeKind.ISSUE_CREATED else ChangeKind.ISSUE_UPDATED)
    }

    override suspend fun setSprint(id: Long, sprintId: Long?) {
        delegate.setSprint(id, sprintId)
        announce(id, ChangeKind.ISSUE_UPDATED)
    }

    override suspend fun setFixedVersion(id: Long, fixedVersionId: Long?) {
        delegate.setFixedVersion(id, fixedVersionId)
        announce(id, ChangeKind.ISSUE_UPDATED)
    }

    override suspend fun setAssignee(id: Long, assigneeId: Long?, assigneeIsAgent: Boolean) {
        delegate.setAssignee(id, assigneeId, assigneeIsAgent)
        announce(id, ChangeKind.ISSUE_UPDATED)
    }

    override suspend fun setDescription(id: Long, description: String) {
        delegate.setDescription(id, description)
        announce(id, ChangeKind.ISSUE_UPDATED)
    }

    override suspend fun update(
        id: Long,
        title: String,
        description: String,
        statusId: Long,
        priorityId: Long,
        resolutionId: Long?,
        sprintId: Long?,
        plannedVersionId: Long?,
        fixedVersionId: Long?,
        estimate: Estimate?,
    ) {
        delegate.update(
            id, title, description, statusId, priorityId, resolutionId, sprintId, plannedVersionId,
            fixedVersionId, estimate,
        )
        announce(id, ChangeKind.ISSUE_UPDATED)
    }

    override suspend fun editAttribution(id: Long, createdAt: Long, author: Author, agentName: String?) {
        delegate.editAttribution(id, createdAt, author, agentName)
        announce(id, ChangeKind.ISSUE_UPDATED)
    }

    override suspend fun setStatus(id: Long, statusId: Long, resolutionId: Long?) {
        delegate.setStatus(id, statusId, resolutionId)
        announce(id, ChangeKind.ISSUE_MOVED)
    }

    override suspend fun setPriority(id: Long, priorityId: Long) {
        delegate.setPriority(id, priorityId)
        announce(id, ChangeKind.ISSUE_UPDATED)
    }

    /**
     * A drag within or into a column rewrites the order of the whole column. Only the
     * cards whose place actually changed are announced — usually one, the one dragged —
     * rather than every card in the column.
     */
    override suspend fun setGroupOrder(issueIds: List<Long>) {
        val before = issueIds.mapNotNull { delegate.findById(it) }.associate { it.id to it.sortOrder }
        delegate.setGroupOrder(issueIds)
        issueIds.forEach { id ->
            val after = delegate.findById(id) ?: return@forEach
            if (!after.isDraft && before[id] != after.sortOrder) {
                bus.publishHere(ChangeKind.ISSUE_MOVED, projectId = after.projectId, issueId = id, updatedAt = after.updatedAt)
            }
        }
    }

    override suspend fun setParent(id: Long, parentId: Long?) {
        val previousParent = delegate.findById(id)?.parentId
        delegate.setParent(id, parentId)
        announce(id, ChangeKind.ISSUE_UPDATED)
        // Both epics' child lists changed too.
        setOfNotNull(previousParent, parentId).forEach { announce(it, ChangeKind.ISSUE_UPDATED) }
    }

    override suspend fun setChildOrder(childIds: List<Long>) {
        delegate.setChildOrder(childIds)
        // The order is the parent's property — its child list is what reads differently.
        childIds.firstNotNullOfOrNull { delegate.findById(it)?.parentId }
            ?.let { announce(it, ChangeKind.ISSUE_UPDATED) }
    }

    override suspend fun setLabelsAndComponents(
        issueId: Long,
        projectId: Long,
        labelIds: List<Long>,
        componentIds: List<Long>,
    ) {
        delegate.setLabelsAndComponents(issueId, projectId, labelIds, componentIds)
        announce(issueId, ChangeKind.ISSUE_UPDATED)
    }

    override suspend fun delete(id: Long) {
        val doomed = delegate.findById(id)
        delegate.delete(id)
        if (doomed != null && !doomed.isDraft) {
            bus.publishHere(ChangeKind.ISSUE_DELETED, projectId = doomed.projectId, issueId = id)
        }
    }
}

/** Publishes the comment kinds, under the issue's project. */
class PublishingCommentStore(
    private val delegate: CommentStore,
    private val issues: IssueStore,
    private val bus: ChangeBus,
) : CommentStore by delegate {

    private suspend fun announce(commentId: Long, kind: ChangeKind, comment: CommentRecord? = null) {
        val row = comment ?: delegate.findById(commentId) ?: return
        if (row.isDraft) return
        val issue = issues.findById(row.issueId) ?: return
        if (issue.isDraft) return
        bus.publishHere(kind, projectId = issue.projectId, issueId = issue.id, commentId = commentId, updatedAt = issue.updatedAt)
    }

    override suspend fun publish(id: Long, body: String) {
        val wasDraft = delegate.findById(id)?.isDraft == true
        delegate.publish(id, body)
        announce(id, if (wasDraft) ChangeKind.COMMENT_ADDED else ChangeKind.COMMENT_EDITED)
    }

    override suspend fun update(id: Long, body: String) {
        delegate.update(id, body)
        announce(id, ChangeKind.COMMENT_EDITED)
    }

    override suspend fun edit(id: Long, body: String, createdAt: Long, author: Author, agentName: String?) {
        delegate.edit(id, body, createdAt, author, agentName)
        announce(id, ChangeKind.COMMENT_EDITED)
    }

    override suspend fun delete(id: Long) {
        val doomed = delegate.findById(id)
        delegate.delete(id)
        if (doomed != null) announce(id, ChangeKind.COMMENT_DELETED, comment = doomed)
    }
}

/**
 * A link added or removed changes both ends — the blocked badge on the board, the
 * links list in each issue — so both are announced as updated.
 */
class PublishingIssueRelationStore(
    private val delegate: IssueRelationStore,
    private val issues: IssueStore,
    private val bus: ChangeBus,
) : IssueRelationStore by delegate {

    private suspend fun announce(vararg issueIds: Long) {
        issueIds.distinct().forEach { id ->
            val issue = issues.findById(id) ?: return@forEach
            if (!issue.isDraft) {
                bus.publishHere(ChangeKind.ISSUE_UPDATED, projectId = issue.projectId, issueId = id, updatedAt = issue.updatedAt)
            }
        }
    }

    override suspend fun insert(projectId: Long, fromIssueId: Long, toIssueId: Long, kindId: Long, createdAt: Long?): Long {
        val id = delegate.insert(projectId, fromIssueId, toIssueId, kindId, createdAt)
        announce(fromIssueId, toIssueId)
        return id
    }

    override suspend fun delete(id: Long) {
        val doomed = delegate.findById(id)
        delegate.delete(id)
        if (doomed != null) announce(doomed.fromIssueId, doomed.toIssueId)
    }
}

/** Publishes `notification.changed` to the one person whose bell it is. */
class PublishingNotificationStore(
    private val delegate: NotificationStore,
    private val bus: ChangeBus,
) : NotificationStore by delegate {

    private suspend fun announce(userId: Long) = bus.publishHere(ChangeKind.NOTIFICATION_CHANGED, userId = userId)

    override suspend fun record(userId: Long, notification: NewNotification) {
        delegate.record(userId, notification)
        announce(userId)
    }

    override suspend fun markRead(userId: Long, id: Long) {
        delegate.markRead(userId, id)
        announce(userId)
    }

    override suspend fun markAllRead(userId: Long) {
        delegate.markAllRead(userId)
        announce(userId)
    }

    override suspend fun dismiss(userId: Long, id: Long) {
        delegate.dismiss(userId, id)
        announce(userId)
    }

    override suspend fun clear(userId: Long) {
        delegate.clear(userId)
        announce(userId)
    }
}

/**
 * Sprint changes reshape the board — which sprint is active, which issues are in it —
 * so they are `board.changed`; moving one issue into or out of a sprint is that
 * issue's `issue.updated`.
 */
class PublishingSprintStore(
    private val delegate: SprintStore,
    private val bus: ChangeBus,
) : SprintStore by delegate {

    private suspend fun boardChanged(projectId: Long) = bus.publishHere(ChangeKind.BOARD_CHANGED, projectId = projectId)

    override suspend fun activate(projectId: Long, sprintId: Long?) {
        delegate.activate(projectId, sprintId)
        boardChanged(projectId)
    }

    override suspend fun complete(projectId: Long, sprintId: Long, moveUnfinishedTo: Long?) {
        delegate.complete(projectId, sprintId, moveUnfinishedTo)
        boardChanged(projectId)
    }

    override suspend fun reopen(projectId: Long, sprintId: Long) {
        delegate.reopen(projectId, sprintId)
        boardChanged(projectId)
    }

    override suspend fun setMembership(projectId: Long, sprintId: Long, issueIds: List<Long>) {
        delegate.setMembership(projectId, sprintId, issueIds)
        boardChanged(projectId)
    }

    override suspend fun setIssueSprint(issue: IssueRecord, sprintId: Long?) {
        delegate.setIssueSprint(issue, sprintId)
        if (!issue.isDraft) {
            bus.publishHere(ChangeKind.ISSUE_UPDATED, projectId = issue.projectId, issueId = issue.id)
        }
    }
}

/** Any vocabulary edit is `board.changed`: columns, priorities and labels are how a board reads. */
class PublishingVocabularyStore(
    private val delegate: VocabularyStore,
    private val bus: ChangeBus,
) : VocabularyStore by delegate {

    private suspend fun boardChanged(projectId: Long) = bus.publishHere(ChangeKind.BOARD_CHANGED, projectId = projectId)

    override suspend fun add(
        projectId: Long,
        kind: VocabularyKind,
        name: String,
        inverseName: String?,
        marksBlocked: Boolean,
        unblocks: Boolean,
    ): VocabularyRow =
        delegate.add(projectId, kind, name, inverseName, marksBlocked, unblocks).also { boardChanged(projectId) }

    override suspend fun rename(
        projectId: Long,
        kind: VocabularyKind,
        row: VocabularyRow,
        name: String,
        requiresResolution: Boolean,
        isDone: Boolean,
        inverseName: String?,
        marksBlocked: Boolean,
        unblocks: Boolean,
    ) {
        delegate.rename(projectId, kind, row, name, requiresResolution, isDone, inverseName, marksBlocked, unblocks)
        boardChanged(projectId)
    }

    override suspend fun delete(projectId: Long, kind: VocabularyKind, row: VocabularyRow) {
        delegate.delete(projectId, kind, row)
        boardChanged(projectId)
    }

    override suspend fun reorder(projectId: Long, kind: VocabularyKind, ids: List<Long>) {
        delegate.reorder(projectId, kind, ids)
        boardChanged(projectId)
    }
}
