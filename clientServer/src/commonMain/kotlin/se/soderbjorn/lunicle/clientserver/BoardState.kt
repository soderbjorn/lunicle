/**
 * Wire types for projects, issues and comments.
 *
 * Everything here is what a client is *allowed* to know, which is not the same
 * as what the server stores. The server's own records ([se.soderbjorn.lunicle.IssueRecord]
 * and friends) carry things these deliberately do not — a user's email, a
 * provider id, an attachment's storage key. The conversion goes one way, at the
 * route, and keeping the two type families apart is what stops a private field
 * from drifting onto the wire by accident.
 *
 * @see LunicleApi
 * @see SessionState
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.Serializable

/**
 * A project, as the picker and the top bar need it.
 *
 * @property namePrefix the "FOO" in FOO-123.
 * @property isPublic whether a signed-out visitor may read it. An affordance
 *   here — the checkbox's state in the edit dialog — never a permission.
 */
@Serializable
data class ProjectSummary(
    val id: Long,
    val name: String,
    val namePrefix: String,
    val isPublic: Boolean,
)

/**
 * The projects this caller may see.
 *
 * Already filtered by the server: signed out means public projects only, not
 * "all projects, hidden in the UI". The distinction is the whole of §2's second
 * half — quietly shipping a private project's name to a signed-out visitor and
 * trusting the bundle to hide it is the failure that gets forgotten.
 *
 * @property canCreateProject whether to render "New project…" in the picker. An
 *   affordance; `POST /api/projects` re-derives it from the session regardless.
 */
@Serializable
data class ProjectListState(
    val projects: List<ProjectSummary> = emptyList(),
    val canCreateProject: Boolean = false,
)

/** A label or a component: an id and a name, scoped to one project. */
@Serializable
data class VocabularyItem(
    val id: Long,
    val name: String,
)

/**
 * A board column — or a priority, or a resolution. All three are an id, a name
 * and an order, so all three ride on this.
 *
 * @property requiresResolution whether dropping an issue into this column demands
 *   a resolution. Only ever true for a status; a priority and a resolution have
 *   no such notion and leave it false.
 *
 *   An affordance, like every other flag on the wire: it exists so the board can
 *   ask for a resolution *before* sending a move that would otherwise be refused.
 *   The server re-reads the column's own flag on every write and refuses
 *   regardless of what the client believed. See BoardRoutes' resolveResolution.
 */
@Serializable
data class StatusItem(
    val id: Long,
    val name: String,
    val position: Int,
    val requiresResolution: Boolean = false,
)

/**
 * What the caller may do in one project.
 *
 * **An affordance, not a grant.** Every route recomputes its own answer from the
 * session before it writes, so editing this object in a console buys nothing but
 * a 403 with extra steps. It exists so a user is not invited to do something
 * that will fail. See the server's `AccessControl` preamble.
 *
 * Defaults are all false, so a signed-out caller and a route that forgets to
 * fill this in produce the same, safe, UI.
 */
@Serializable
data class ProjectPermissionsView(
    val canCreateIssue: Boolean = false,
    val canComment: Boolean = false,
    val canChangeUnownedIssues: Boolean = false,
    val canMutateProject: Boolean = false,
)

/**
 * One card on the board.
 *
 * @property number the per-project number. The card renders "FOO-123: title"
 *   from this and the project's prefix.
 * @property canEdit whether this caller may edit *this* issue — which is per
 *   issue rather than per project, because authorship is one of the three ways
 *   to yes (see `AccessControl.canEditIssue`). Computed server-side per card so
 *   the client never has to reimplement that rule to decide whether a card
 *   drags.
 * @property updatedAt when the issue was last touched — created, edited, or
 *   dragged. On the wire even though no card shows it, because the board's order
 *   is priority-then-recency and a client that re-sorted would need it. The
 *   server sends the issues already sorted (see Issues.sq's `forProject`), so
 *   this is here for a client that wants to *re*-sort without a round-trip, not
 *   for one that must.
 */
@Serializable
data class IssueSummary(
    val id: Long,
    val number: Long,
    val title: String,
    val statusId: Long,
    val priorityId: Long = 0,
    val resolutionId: Long? = null,
    val labelIds: List<Long> = emptyList(),
    val componentIds: List<Long> = emptyList(),
    val authorName: String? = null,
    val agentName: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val canEdit: Boolean = false,
)

/**
 * Everything one board needs, in one round-trip.
 *
 * One state rather than five endpoints because the board cannot render without
 * all of it: a card shows a label's *name*, so the vocabularies are not optional
 * extras. Five requests would also be five chances for the screen to paint
 * half-populated.
 *
 * @property priorities the project's priority scale, highest first — [StatusItem]
 *   rather than [VocabularyItem] because, like a status, a priority *is* its
 *   order. The two are the same shape for the same reason; see Priorities.sq.
 */
@Serializable
data class BoardState(
    val project: ProjectSummary,
    val statuses: List<StatusItem> = emptyList(),
    val priorities: List<StatusItem> = emptyList(),
    val resolutions: List<StatusItem> = emptyList(),
    val labels: List<VocabularyItem> = emptyList(),
    val components: List<VocabularyItem> = emptyList(),
    val issues: List<IssueSummary> = emptyList(),
    val permissions: ProjectPermissionsView = ProjectPermissionsView(),
)

/**
 * A comment, rendered.
 *
 * @property authorName resolved server-side to `display_name ?: provider_name`,
 *   or null once the author's account is gone. The client never sees a user id —
 *   it has nothing to do with one, and it would be one more identifier on the
 *   wire for no benefit.
 * @property agentName the agent that posted it on the author's behalf, or null
 *   when a human did. Shown as a badge beside [authorName], never in place of it:
 *   the comment is still the author's, and this says an agent held the pen. Only
 *   the MCP tools ever set it.
 * @property canEdit whether this caller may change or delete it. Authorship or
 *   admin; see `AccessControl.canEditComment`.
 */
@Serializable
data class CommentView(
    val id: Long,
    val body: String,
    val authorName: String? = null,
    val agentName: String? = null,
    val createdAt: Long = 0,
    val canEdit: Boolean = false,
)

/**
 * One issue, opened in the modal.
 *
 * @property description markdown, plus allow-listed inline HTML for `<u>`. The
 *   renderer must never pass raw HTML through — see the client's `Markdown.kt`.
 * @property isDraft whether this issue has never been published. The modal
 *   treats a draft as "new": Cancel deletes it outright rather than reverting
 *   it.
 */
@Serializable
data class IssueDetail(
    val id: Long,
    val projectId: Long,
    val number: Long,
    val title: String,
    val description: String,
    val statusId: Long,
    val priorityId: Long = 0,
    val resolutionId: Long? = null,
    val isDraft: Boolean,
    val labelIds: List<Long> = emptyList(),
    val componentIds: List<Long> = emptyList(),
    val authorName: String? = null,
    val agentName: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val comments: List<CommentView> = emptyList(),
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
    val canComment: Boolean = false,
)

/**
 * A freshly created draft issue.
 *
 * The row exists before the editor is filled in, so that an inline image upload
 * has an issue to attach to. That is also why a cancelled draft burns a number
 * and the board can go FOO-12, FOO-14 — gaps are cosmetic, reused numbers are
 * corruption. See Issues.sq.
 */
@Serializable
data class IssueDraft(
    val id: Long,
    val number: Long,
)

/** A freshly created draft comment, for the same reason as [IssueDraft]. */
@Serializable
data class CommentDraft(
    val id: Long,
)

/** What the editor sends when the user presses OK. */
@Serializable
data class IssueUpdate(
    val title: String,
    val description: String,
    val statusId: Long,
    val priorityId: Long,
    /**
     * Why it is being closed, or null because it is not.
     *
     * The server does not take this at face value: it re-reads the target
     * status's `requires_resolution` and refuses a save that omits one when the
     * column demands it, and forces this back to null when it does not. So a
     * client that forgets is refused, and a client that sends a stale one on a
     * reopened issue does not get to leave it behind. See BoardRoutes.
     */
    val resolutionId: Long? = null,
    val labelIds: List<Long> = emptyList(),
    val componentIds: List<Long> = emptyList(),
)

/**
 * Drag-and-drop's payload. A `status_id` write like any other — plus the reason,
 * when the column being dropped into demands one.
 *
 * @property resolutionId why the issue is being closed. Required when the target
 *   status has `requires_resolution`, refused when it does not, and both halves
 *   are checked server-side: the board is where an issue is closed most often, so
 *   this route enforces exactly the rule the editor does rather than being the
 *   convenient way around it.
 */
@Serializable
data class StatusUpdate(
    val statusId: Long,
    val resolutionId: Long? = null,
)

/**
 * A group of cards, in the order the user just dragged them into.
 *
 * The whole group, not "move issue X to index 3": a rank is a statement about
 * neighbours, and sending one card's new position would leave the server to
 * reconstruct the group's membership and guess at the rest. The client already
 * knows the group — it is what it just rendered.
 *
 * @property issueIds every issue in one group, first to last. The server checks
 *   they all belong to the project and are all genuinely in one group before it
 *   writes; a caller that mixes groups is refused rather than silently ranking
 *   issues against cards they will never be shown beside.
 */
@Serializable
data class IssueOrderUpdate(
    val issueIds: List<Long> = emptyList(),
)

/** What the comment modal sends on OK. */
@Serializable
data class CommentUpdate(
    val body: String,
)

/** What the project dialog sends. `id` is absent when creating. */
@Serializable
data class ProjectUpdate(
    val name: String,
    val namePrefix: String,
    val isPublic: Boolean,
)

/** An uploaded attachment's id, which the editor turns into a markdown image. */
@Serializable
data class AttachmentRef(
    val id: Long,
)
