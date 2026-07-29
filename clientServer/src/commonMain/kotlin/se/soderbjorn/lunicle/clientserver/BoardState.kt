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
 * @property roleKey what the caller holds here, as
 *   [se.soderbjorn.lunicle.ProjectRole.key] — and [roleLabel] the same rung as a
 *   word. Never absent in practice: the server computed this rung to decide whether
 *   to put the project in the list at all, so sending it costs nothing and saves the
 *   settings rail a request per project.
 *
 *   **An affordance, like every other flag here.** It is what lets the Projects rail
 *   say "Maintainer" under a board's name and decide which sections to offer without
 *   fetching each project's settings; every write re-derives the rung from the
 *   session. The defaults are the least it could be, so a client talking to a server
 *   that omits them offers the narrowest surface rather than the widest.
 *
 *   `isPublic` and `visibleToAllSignedIn` were here and are **gone** (LNL-194, after
 *   LNL-191 stopped filling them). Who may see a project is its audience rows, which
 *   the Access section reads through `ProjectSettingsState.access`; two booleans that
 *   were always false were worse than nothing, because they read as an answer.
 * @property discussionsEnabled whether this project offers a discussion forum,
 *   and [messagesEnabled] whether it offers private messages (LNL-96). The tab
 *   shell reads these off the board it already loads and hides the Discussion or
 *   Messages tab for a project that has switched one off — on top of the forum
 *   master toggle, which decides whether the tabs can appear at all. Affordances,
 *   like [isPublic]: hiding a tab is not a lock on the data behind it, which
 *   LNL-30 settled is not access-controlled beyond project visibility.
 *
 *   Both features are retired (LNL-190) and the server now sends both as false for
 *   every project, so these default false too: a client talking to a server that
 *   omits them shows no forum either.
 */
@Serializable
data class ProjectSummary(
    val id: Long,
    val name: String,
    val namePrefix: String,
    val roleKey: String = RoleKeys.VIEWER,
    val roleLabel: String = "Viewer",
    val discussionsEnabled: Boolean = false,
    val messagesEnabled: Boolean = false,
    /**
     * Whether filing a new ticket here must carry a label, and whether it must
     * carry a component (LNL-106). Ride on the summary for the forum flags' reason:
     * the issue editor reads them off the board it already loads, to refuse a
     * label-less or component-less new ticket before it is sent. Both default off,
     * so an older server that does not send them, or a project made before the
     * columns existed, requires nothing.
     */
    val requireLabel: Boolean = false,
    val requireComponent: Boolean = false,
    /**
     * Whether closing an issue with a done resolution must carry a fixed version
     * (LNL-134). Rides on the summary for [requireLabel]'s reason: the board's
     * resolution dialog and the issue editor read it off what they already load,
     * to demand a fixed version before sending a move that would otherwise be
     * refused. Default off, so an older server or a pre-column project requires
     * nothing.
     */
    val requireFixedVersionOnResolve: Boolean = false,
    /**
     * Whether the board shows each card's author on a muted footer line (LNL-157).
     * A per-project display setting a project administrator flips — not a
     * requirement and not a per-user preference. Rides on the summary like the flags
     * above so the board reads it off what it already loads and the card render gates
     * on it. Default off, so an older server that does not send it, or a project made
     * before the column existed, hides the author — the opt-in default.
     */
    val showIssueAuthor: Boolean = false,
    /**
     * Whether the board and its issue windows hide the issue number — the FOO-123
     * key, prefix and all (LNL-194).
     *
     * The second board-display setting beside [showIssueAuthor], and it rides here
     * for the same reason: the board reads it off what it already loads. It was a
     * **per-user** preference until LNL-194, kept in each account's stored view
     * choices; it describes how a shared board reads, so it belongs to the project
     * and is an administrator's switch. Default off — numbers shown, the state every
     * board had before anybody chose.
     */
    val hideIssueNumbers: Boolean = false,
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

/**
 * "Put the instance's projects in this order."
 *
 * The whole new order, not a "moved X to slot N" delta — the same shape
 * [VocabularyOrder] and [ForumOrder] use, and for the same reasons: it is
 * idempotent, so a retry says the same thing rather than moving a project twice,
 * and the server validates it as a set, so a stale client that has lost or gained
 * a project is refused whole rather than partially applied. See
 * [ApiRoutes.ADMIN_PROJECT_ORDER] and the server's ProjectRepository.reorder.
 *
 * @property ids every project id, in the order they should sit in the picker.
 */
@Serializable
data class ProjectOrder(
    val ids: List<Long> = emptyList(),
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
 * @property isDone whether this resolution means the work was actually done
 *   (LNL-134). Only ever meaningful for a resolution — the mirror of
 *   [requiresResolution]. It is what the resolution dialog reads to decide whether
 *   to demand a fixed version; an affordance, re-checked server-side. See
 *   Resolutions.sq.
 */
@Serializable
data class StatusItem(
    val id: Long,
    val name: String,
    val position: Int,
    val requiresResolution: Boolean = false,
    val isDone: Boolean = false,
)

/**
 * A sprint, as the board needs it.
 *
 * Its own type rather than a fourth reuse of [StatusItem], which statuses,
 * priorities and resolutions all ride on: those three really are just an id, a
 * name and an order, and a sprint is that plus [completedAt]. Folding it in
 * would put a nullable timestamp on every board column that could only ever be
 * null, so the client would have to know which of the four kinds it meant
 * something for — which is exactly the knowledge a shared type is supposed to
 * remove.
 *
 * @property completedAt when this sprint finished, or null because it has not.
 *   The client uses it as a predicate, not a date: a completed sprint is still
 *   offered as a board scope — you may well want to see what shipped — but is
 *   not offered as somewhere to schedule work into. Sent as the instant rather
 *   than a boolean because it is the one value in this feature that could not be
 *   recomputed after the fact; see Sprints.sq.
 */
@Serializable
data class SprintItem(
    val id: Long,
    val name: String,
    val position: Int,
    val completedAt: Long? = null,
) {
    /** Whether work may still be scheduled into this sprint. */
    val isOpen: Boolean get() = completedAt == null
}

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
    /**
     * Whether the caller may add, rename, reorder and complete this project's
     * sprints and versions.
     *
     * A **maintainer**'s, one rung below the rest of the settings dialog (LNL-191):
     * planning the next two weeks is work on a board somebody already edits every
     * issue on, where the set of statuses a project *has* is a decision about the
     * board. Its own field precisely because that is where [canMutateProject] and
     * this now differ — the scope picker's sprint actions read this, and the
     * vocabulary sections read that one.
     */
    val canManageSprintsAndVersions: Boolean = false,
    /**
     * Whether the caller administers this project — its vocabulary, its display
     * settings and its privileges up to maintainer. An instance administrator, or
     * a project administrator here. What renders the settings dialog's admin
     * sections. Note it no longer covers sprints; see
     * [canManageSprintsAndVersions].
     */
    val canMutateProject: Boolean = false,
    /**
     * Whether the caller may rename this project, change its prefix, decide which
     * audiences it admits, or delete it outright.
     *
     * Strictly narrower than [canMutateProject], and split off from it by LNL-37
     * for a reason you can see in the dialog: an administrator gets the whole
     * settings dialog, but a project's *name, prefix, audiences and existence* are
     * the owner's. Without this field the dialog showed an administrator a name
     * field and a Delete button that both 403 on save, which is exactly the
     * "invited to do something that will fail" this whole object exists to prevent.
     */
    val canMutateProjectIdentity: Boolean = false,
    /**
     * Whether the caller may be handed an issue in this project — what decides
     * that "Assign to me" is rendered at all.
     *
     * Note it is a fact about the *caller*, not about the issue: the button is
     * offered on every issue they can read here, and the server re-derives the
     * same answer on the write. See the server's `AccessControl.canBeAssigned`.
     */
    val canBeAssigned: Boolean = false,
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
    /**
     * Which sprint this is scheduled into, or null for the backlog.
     *
     * Null for every issue in a project that has no sprints, so a kanban board
     * receives this field carrying no information and renders nothing for it.
     * Defaulted like everything else here, which is what makes an older client
     * reading a newer server — and the reverse — a non-event.
     */
    val sprintId: Long? = null,
    /**
     * The epic this card belongs under, or null because it belongs under none
     * (LNL-55). A card with a non-null parent is a *child* — it wears the "↳ epic"
     * back-reference (LNL-154). Null for the common case and for every project
     * nobody has arranged into epics, so a board that uses no epics receives this
     * carrying no information. See Issues.sq's parent_id.
     */
    val parentId: Long? = null,
    /**
     * How many children this card has — non-zero makes it an *epic*, and the card
     * wears a filled count badge (LNL-154).
     *
     * Computed server-side over ALL of the project's non-draft issues, NOT derived
     * client-side from this board's [BoardState.issues] list. That distinction is
     * the whole reason this field exists: a child in a column the reader has hidden,
     * or one scoped out of the view, is absent from the list the client holds, so
     * counting there would silently *undercount* an epic's children. The server
     * counts over the authoritative project-wide set instead — see
     * BoardRoutes.buildBoard.
     *
     * Defaulted to 0 like everything else here, so an older client reading a newer
     * server — and the reverse — is a non-event: a zero means "no children", which
     * is exactly what a client that never learned about this field should render.
     */
    val childCount: Int = 0,
    /**
     * The *number* of this card's [parentId] — the 98 in "LMX-98" — or null because
     * it has no parent (LNL-154).
     *
     * Shipped alongside [parentId] so the "↳ LMX-98" link renders without the client
     * scanning its board list to resolve the id: the parent may not be loaded (it can
     * sit in a hidden column, or be scoped out), so a client-side lookup would fail
     * exactly where [childCount] would undercount. The project prefix is the reader's
     * own — a parent shares its child's project — so number + prefix names the key and
     * the open action. Null whenever [parentId] is, and defaulted for the same
     * older-client reason as [childCount].
     */
    val parentNumber: Long? = null,
)

/**
 * A compact reference to another issue — the parent chip and the children list on
 * [IssueDetail] are built from these (LNL-55).
 *
 * Deliberately not [IssueSummary]: this exists to render "FOO-123: title" as a
 * clickable line and, for a child, to reorder and detach it — so it carries the
 * number and title to show, the id to navigate/act on, and the status and
 * resolution to hint state (a closed child can read struck-through). It does not
 * carry labels, authorship or permissions, because a reference to an issue is not
 * that issue and shipping those would be the private-field drift this file's
 * preamble warns against. The prefix that turns [number] into FOO-123 is the one
 * the reader already has — parent and children share this issue's project.
 *
 * @property canEdit whether this caller may edit the *referenced* issue — computed
 *   per reference, like [IssueSummary.canEdit], so the client knows without a
 *   round-trip whether a child's remove/reorder controls should be live.
 */
@Serializable
data class IssueRef(
    val id: Long,
    val number: Long,
    val title: String,
    val statusId: Long,
    val resolutionId: Long? = null,
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
    /**
     * The project's sprints, planning order. Empty unless somebody has made one.
     *
     * Emptiness is the whole contract with the client: no sprints means no scope
     * control renders, no sprint field in the editor, no menu item on a card.
     * There is no separate "sprints are enabled" flag that could get out of step
     * with this list, because presence of sprints *is* the flag. See Sprints.sq.
     */
    val sprints: List<SprintItem> = emptyList(),
    /**
     * Which sprint the board scopes to when it opens, or null for none.
     *
     * Always one of [sprints], and always an open one — the server clears it when
     * that sprint is completed or deleted. The client still checks rather than
     * assumes: a board rendered against a stale id would show an empty scope,
     * which reads as "no work" rather than as "wrong sprint".
     */
    val activeSprintId: Long? = null,
    /**
     * The project's release versions, in the order the admin arranged (LNL-134).
     * Empty unless somebody has made one — presence *is* the flag, like [sprints]:
     * no versions means no version pickers anywhere. Sent on the board because the
     * resolution dialog's fixed-version picker and the reusable version dropdown
     * read it off what they already load. See Versions.sq.
     */
    val versions: List<VocabularyItem> = emptyList(),
    val issues: List<IssueSummary> = emptyList(),
    val permissions: ProjectPermissionsView = ProjectPermissionsView(),
    /**
     * The GitHub repository this project is linked to, `owner/name`, or null when
     * none is configured — what turns a `#123` in an issue or a comment into a link
     * to that pull request (LNL-178).
     *
     * The address itself rather than a "has a repository" flag, because the client
     * is what builds the link and cannot build it from a boolean. It goes to every
     * reader of the project, not only to the administrators who can *set* it — a
     * rendered reference is no use to a reader who is not allowed to know where it
     * points, and the repository a project's work lands in is not a secret from the
     * people reading that work. The token behind it stays where it was: never on the
     * wire, in either direction, for anybody. See `ProjectSettingsState.repositoryUrl`
     * for the settings form's copy, which is still owner-only because *editing* it is.
     */
    val gitHubRepository: String? = null,
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
 * What kind of thing happened to an issue.
 *
 * Serialized by name, so these strings are wire format and outlive any rename —
 * they are also what the `kind` column stores, so a renamed constant would move
 * the meaning of every row already written. Changing one is a migration, not a
 * refactor. See AuthProvider, which settled this the same way, and IssueEvents.sq
 * for why the column is TEXT.
 *
 * ── Why this is not a closed set the client may assume ─────────────────────
 *
 * A browser holds a cached bundle for as long as it likes, so a client one
 * deploy behind will meet a kind it has no constant for. `IssueEventView` is
 * therefore decoded with the unknown kind mapped to null and the event dropped
 * rather than the whole issue failing to load — an issue that will not open
 * because something happened to it is a far worse outcome than a history that is
 * briefly one line short. See the server's `IssueEventStore.forIssue`, which
 * does the same on the way out for a row written by a newer build.
 *
 * ── What is deliberately absent ────────────────────────────────────────────
 *
 * Priority and resolution. Neither is recorded, and resolution is the one worth
 * naming because it travels *with* a status change — a `STATUS_CHANGED` event
 * says which column the issue moved to and is silent about why it was closed.
 * That is scope, not oversight; see LNL-8.
 */
@Serializable
enum class IssueEventKind {
    /** The issue was filed. Carries no value. */
    CREATED,

    /** Carries the new title in [IssueEventView.value]. */
    TITLE_CHANGED,

    /**
     * Carries nothing.
     *
     * The one field whose new value is deliberately not stored. A description is
     * unbounded markdown, so keeping every revision would grow the history table
     * without limit and turn a sidebar into a document store — and the history's
     * job here is to say *that* the description changed and who by. The current
     * text is on the issue itself, one scroll up.
     */
    DESCRIPTION_CHANGED,

    /** Carries the labels as they stood after the change, in [IssueEventView.values]. */
    LABELS_CHANGED,

    /** Carries the components as they stood after the change, in [IssueEventView.values]. */
    COMPONENTS_CHANGED,

    /** Carries the status's name as it stood at that moment, in [IssueEventView.value]. */
    STATUS_CHANGED,

    /**
     * Carries the new assignee's name in [IssueEventView.value], or null when the
     * issue was unassigned. See [IssueEventView.value] for why null is load-bearing.
     */
    ASSIGNEE_CHANGED,
}

/**
 * One thing that happened to an issue, rendered.
 *
 * The sentence is not on the wire. The server sends the fact — a [kind] and
 * whatever that kind carries — and the client writes the words; see
 * `IssueBackingViewModel.historyDescription`. That split is the whole reason this
 * type is shaped the way it is rather than being a `List<String>`: a rendered
 * sentence cannot be re-worded, filtered, counted or translated after the fact,
 * and it would be written by whichever build was deployed the day it happened, so
 * a history spanning a phrasing change would speak in two voices forever.
 *
 * @property value the single value this [kind] carries, or null when it carries
 *   none. Its meaning is decided by the kind — the new title, the status's name,
 *   the assignee's name — which is why there is one column here rather than a
 *   nullable field per kind that is null six times out of seven.
 *
 *   **Null is meaningful on [IssueEventKind.ASSIGNEE_CHANGED]**, where it means
 *   the issue was unassigned rather than "we don't know who". A deleted assignee
 *   still has a name here — see IssueEvents.sq's `value_user_id`, which explains
 *   why those two cases must not collapse.
 *
 *   For statuses, labels and components this is a **snapshot**: the name as it
 *   stood at the time, not the name now. So a renamed column reads by its old
 *   name in history, on purpose — the issue was moved into the column then called
 *   that. See IssueEvents.sq.
 * @property values the set this [kind] carries, in the order it was written, or
 *   empty when it carries none. The whole set rather than a delta, so a gap in
 *   the history — and the migration's back-fill opens one by design — does not
 *   corrupt everything after it.
 * @property authorName who did it, resolved server-side to `display_name ?:
 *   provider_name`, or null once their account is gone. Resolved live rather than
 *   snapshotted, unlike [value]: renaming yourself does not change who did the
 *   thing. Same rule, and the same "A deleted account" fallback, as [CommentView].
 * @property agentName the agent that made this change on the author's behalf, or
 *   null when a human did. Per-event, which is the point — `IssueDetail.agentName`
 *   can only say an agent touched the issue at some point, never which change was
 *   theirs.
 */
@Serializable
data class IssueEventView(
    val id: Long,
    val kind: IssueEventKind,
    val value: String? = null,
    val values: List<String> = emptyList(),
    val authorName: String? = null,
    val agentName: String? = null,
    val createdAt: Long = 0,
)

/**
 * One issue, opened in the modal.
 *
 * @property history what has happened to this issue, oldest first. Rides on the
 *   detail beside [comments] rather than behind an endpoint of its own, for this
 *   file's usual reason: the modal cannot render half of itself, and a second
 *   round-trip would be a second chance to paint incomplete. Empty for a draft,
 *   which has no history worth the name yet.
 * @property description markdown, plus allow-listed inline HTML for `<u>`. The
 *   renderer must never pass raw HTML through — see the client's `Markdown.kt`.
 * @property isDraft whether this issue has never been published. The modal
 *   treats a draft as "new": Cancel deletes it outright rather than reverting
 *   it.
 * @property notifyOnUpdates whether the caller has asked to be e-mailed about
 *   updates to this issue. Drives the toggle's state; false for a signed-out
 *   caller, who has nowhere to send.
 * @property canReceiveEmailNotifications whether the caller has an address at all.
 *   The toggle is hidden without it — promising an e-mail we cannot send would be
 *   a dead control. Computed from the caller's own record server-side.
 * @property watchers the names of everyone watching this issue, for the "who is
 *   watching" line. Names only — anyone who can read the issue sees who watches
 *   it, but never where they'd be mailed. See the server's Subscriptions.sq.
 * @property assigneeId who is working on this, or null for nobody. An id as well
 *   as [assigneeName] because this one has to round-trip: the editor sends it
 *   back on save, and "Assign to me" compares it against the caller's own id to
 *   decide whether the button reads Assign or Unassign. Sending an id is not a
 *   capability here — see `SignedInUser.id`, whose reasoning this shares.
 * @property assigneeName the assignee's display name, resolved server-side, or
 *   null when nobody is assigned. The read face renders this and never looks a
 *   name up itself.
 * @property assignableUsers who this caller may choose from, for the editor's
 *   dropdown.
 *
 *   **Empty unless [canEdit].** It is a directory of accounts, narrow but real,
 *   and a reader who cannot change the field has no business receiving one — this
 *   is BoardRoutes' "reads are filtered, not just writes refused", applied to the
 *   one new list this feature adds. It rides on the issue rather than on
 *   [BoardState] for exactly that reason: the board goes to signed-out visitors,
 *   and there is no per-issue `canEdit` to narrow it by out there.
 * @property mentionableUsers who the editor's `@` autocomplete may offer —
 *   everyone with any role on this project, plus the admin. A wider set than
 *   [assignableUsers] on purpose; see the server's `mentionableUsers`.
 *
 *   **Empty unless the caller can write here** — that is, unless [canEdit] or
 *   [canComment]. Same narrowing rule as [assignableUsers], applied to the
 *   question this list actually serves: a reader who cannot put text on this
 *   issue has no mention to complete, so shipping them a directory would be a
 *   directory shipped for nothing.
 *
 *   Names only, and matched by name on the way back — see `mentionedNames` for
 *   why a mention is plain text rather than markup carrying these ids. The ids
 *   ride along because [UserOption] has one, not because anything needs it.
 * @property canBeAssigned whether the caller may take this issue themselves —
 *   what renders "Assign to me". Distinct from [canEdit]: someone may be eligible
 *   to hold an issue without being able to edit it, which is the whole point of
 *   the separate right.
 * @property callerId who the server resolved this request to be, or null when
 *   signed out. The **effective** user under impersonation, like everything else
 *   on this type.
 *
 *   It earns its place by answering two questions the assignee button has and
 *   nothing else on the wire can: whether the issue is already mine (so the button
 *   reads "Unassign me" rather than "Assign to me"), and what id to send when I
 *   take it. The obvious alternative — a derived `isAssignedToMe` boolean — answers
 *   only the first, and would leave the client unable to name itself on the write.
 *
 *   `SessionState.user.id` is the same number, and this is deliberately not asking
 *   the issue window to go and find it: that window is constructed with a board and
 *   an issue id and no session, and threading one through so the client could
 *   re-derive what the server already knew would be more moving parts for the same
 *   answer. Sending an id costs nothing — see `SignedInUser.id`, which spells out
 *   why a user id is not a capability in this API.
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
    val history: List<IssueEventView> = emptyList(),
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
    val canComment: Boolean = false,
    val notifyOnUpdates: Boolean = false,
    val canReceiveEmailNotifications: Boolean = false,
    val watchers: List<String> = emptyList(),
    val assigneeId: Long? = null,
    val assigneeName: String? = null,
    val assignableUsers: List<UserOption> = emptyList(),
    val mentionableUsers: List<UserOption> = emptyList(),
    val canBeAssigned: Boolean = false,
    val callerId: Long? = null,
    /** Which sprint this is scheduled into, or null for the backlog. */
    val sprintId: Long? = null,
    /**
     * The sprints this issue could be moved into, planning order.
     *
     * Sent with the issue rather than read off the board, because the issue
     * window can be opened from a deep link with no board loaded — the same
     * reason [assignableUsers] rides along.
     *
     * Completed sprints are omitted, since this list answers "where can this work
     * go" and a finished sprint is not an answer. The sprint the issue is
     * *currently* in is included even if completed, so the dropdown shows where
     * it actually is rather than silently reading as unscheduled.
     */
    val sprints: List<SprintItem> = emptyList(),
    /** Which release this is planned for, or null. See Issues.sq's planned_version_id. */
    val plannedVersionId: Long? = null,
    /** Which release this was fixed in, or null. See Issues.sq's fixed_version_id. */
    val fixedVersionId: Long? = null,
    /**
     * The project's release versions, for the planned- and fixed-version dropdowns
     * (LNL-134). Sent with the issue rather than read off the board for [sprints]'
     * reason — the issue window opens from a deep link with no board loaded. Both
     * version fields draw from this one list.
     */
    val versions: List<VocabularyItem> = emptyList(),
    /**
     * The epic this issue belongs under, or null because it belongs under none
     * (LNL-55). Both the id and the rendered [parent] ride here: the id to compare
     * and send back, the reference to draw the clickable "belongs to FOO-123" chip
     * without a second load. Null and null for the common case.
     */
    val parentId: Long? = null,
    val parent: IssueRef? = null,
    /**
     * This issue's children, in their work order, or empty because it has none —
     * which is to say, whether this issue is an epic (LNL-55). Each is a clickable
     * reference the editor can also reorder (↑/↓) and detach; the order is the one
     * the epic's editor arranged. Empty for the overwhelming majority of issues, so
     * an ordinary ticket carries this field saying nothing.
     */
    val children: List<IssueRef> = emptyList(),
    /**
     * The issues in this project this caller could link to or from — candidates for
     * the parent picker and the "add child" autocomplete (LNL-55). Published issues
     * of the project as compact [IssueRef]s; the client filters per gesture (a
     * parent candidate is not this issue, is not already an epic, and has no parent
     * of its own; a child candidate excludes the existing children and this issue).
     * The server re-checks every rule on the write — see BoardRoutes' parent route —
     * so this is an affordance, not a grant.
     *
     * **Empty unless [canEdit].** A reader cannot reparent anything, so shipping
     * them the project's issue list here would be a directory shipped for nothing —
     * the same narrowing [assignableUsers] takes. Rides on the issue rather than the
     * board for [sprints]' reason: a deep-linked window has no board to read.
     */
    val linkableIssues: List<IssueRef> = emptyList(),
)

/**
 * A subscribe/unsubscribe toggle, for a project's new issues or one issue's
 * updates. The same shape serves both — the target is in the path.
 *
 * [subscribed] is the state to move to rather than a toggle, for
 * [se.soderbjorn.lunicle.clientserver.RoleGrant]'s reason: a retry says the same
 * thing, and the server treats subscribe/unsubscribe as idempotent so a
 * double-tap cannot land in the state nobody chose.
 */
@Serializable
data class NotificationSubscriptionRequest(
    val subscribed: Boolean,
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
    /**
     * Who should work on it, or null for nobody.
     *
     * Null is "unassigned" and not "leave it alone": this is the editor's whole
     * field set, sent every save, so an omitted assignee genuinely means the user
     * cleared it. That is the same reading `labelIds` takes of an empty list, and
     * the opposite of what the MCP tools do with an absent argument — a difference
     * that is safe here because a form always knows every one of its own fields.
     *
     * The server checks the named user may actually hold an issue in this project
     * before it writes; the dropdown only offers eligible people, which is an
     * affordance and not the check. See `AccessControl.canBeAssigned`.
     */
    val assigneeId: Long? = null,
    /**
     * Which sprint to schedule it into, or null for the backlog.
     *
     * Null is "the backlog", not "leave it alone", for [assigneeId]'s reason:
     * this is the editor's whole field set and it is sent on every save. The
     * server checks the sprint is in this issue's project before it writes —
     * nothing in the schema will, see Issues.sq's sprint_id.
     */
    val sprintId: Long? = null,
    /**
     * Which release to plan this for, and which release it was fixed in, or null
     * for each (LNL-134). Null is "cleared", not "leave alone", for [sprintId]'s
     * reason — the editor sends its whole field set every save. The server checks
     * each version is in this issue's project before it writes; nothing in the
     * schema will, see Issues.sq's planned_version_id / fixed_version_id.
     */
    val plannedVersionId: Long? = null,
    val fixedVersionId: Long? = null,
    val labelIds: List<Long> = emptyList(),
    val componentIds: List<Long> = emptyList(),
)

/**
 * "Assign this issue to this person", or to nobody.
 *
 * Its own request rather than a reuse of [IssueUpdate], because it travels under
 * a different permission and must not be able to carry anything else: the route
 * behind it says yes to callers who may *not* edit the issue — see
 * `AccessControl.canBeAssigned` — so a body with a title field in it would be a
 * way to rewrite an issue you cannot edit.
 *
 * @property assigneeId who takes it, or null to leave it unassigned. Naming
 *   somebody other than yourself needs the right to edit the issue as well; the
 *   server decides, and the client's button only ever sends its own user or null.
 */
@Serializable
data class IssueAssignment(
    val assigneeId: Long? = null,
)

/**
 * "Schedule this issue here", or nowhere.
 *
 * Its own request rather than a reuse of [IssueUpdate] for [IssueAssignment]'s
 * shape of reason, arriving at the opposite conclusion about permission: this
 * one is *not* a lighter grant — scheduling is editing — but it is a lighter
 * *payload*. The card menu has a sprint and an issue id and nothing else, and a
 * body that could also carry a title would make it a way to overwrite the fields
 * it never asked the user about.
 *
 * @property sprintId where it goes, or null for the backlog. Null is a real
 *   destination here and not an omission — "take this out of the sprint" is the
 *   second most common thing this route is asked to do.
 */
@Serializable
data class IssueSprintUpdate(
    val sprintId: Long? = null,
)

/**
 * "This issue belongs under that epic", or under none (LNL-55).
 *
 * Its own request rather than a field on [IssueUpdate], for [IssueSprintUpdate]'s
 * shape of reason: reparenting is an immediate gesture — the parent picker, the
 * "remove from parent" button, the epic editor's add/remove-a-child — not one of
 * the fields the editor's Save commits, and a body that could also carry a title
 * would make it a way to overwrite fields nobody edited. Posted to the *child's*
 * id: adding a child to an epic and setting an issue's parent are the one write,
 * so the epic-side "add child" posts this to the chosen child, and both remove
 * gestures post it with null.
 *
 * @property parentId the epic to attach under, or null to detach. The server
 *   enforces same-project, one-level (a parent may not itself have a parent, and an
 *   issue that has children may not become a child) and no self-parent; a refused
 *   reparent is a 400 that says which rule. See IssueRepository.setParent.
 */
@Serializable
data class IssueParentUpdate(
    val parentId: Long? = null,
)

/**
 * One epic's children, in the order the user just arranged them (LNL-55).
 *
 * The whole set, not "move child X to slot N" — [IssueOrderUpdate]'s and
 * [SprintMembership]'s convention, and for the same reasons: idempotent on retry,
 * and validated as a set so a stale client is refused whole. Posted to the
 * *epic's* id.
 *
 * @property childIds every child of this epic, first to last. The server checks
 *   they are exactly this epic's children before it writes; a list that adds,
 *   drops or borrows a child is refused rather than half-applied. See BoardRoutes'
 *   children-order route.
 */
@Serializable
data class ChildOrder(
    val childIds: List<Long> = emptyList(),
)

/**
 * Which sprint the board should scope to.
 *
 * @property sprintId the sprint to activate, or null to leave the project with
 *   none active. Null is a state a project can legitimately sit in — between
 *   sprints, and before the first one — rather than a way of saying nothing.
 */
@Serializable
data class SprintActivation(
    val sprintId: Long? = null,
)

/**
 * "This sprint is over; put what did not get done over there."
 *
 * @property moveUnfinishedTo another sprint's id, or null for the backlog. Not
 *   defaulted at the call site even though it is defaulted here: "roll it into
 *   the next sprint" and "put it back in the backlog" are different intentions
 *   and the dialog asks which. The default exists so an omitted field decodes to
 *   the more conservative of the two — the backlog, which loses no information,
 *   rather than guessing at a sprint the caller never named.
 *
 *   What counts as unfinished is the server's answer, read from each status's
 *   `requires_resolution` rather than from a column called "Closed". See
 *   Issues.sq's moveUnfinished.
 */
@Serializable
data class SprintCompletion(
    val moveUnfinishedTo: Long? = null,
)

/**
 * Exactly which issues are in a sprint, after this request.
 *
 * The whole set, not a delta — see [VocabularyOrder] and [IssueOrderUpdate] for
 * the convention and why it is the one this API keeps. Any issue not named is
 * released to the backlog rather than left where it was: "not in this sprint"
 * has one meaning, and a membership write that quietly preserved omitted rows
 * would be a delta wearing a set's clothes.
 */
@Serializable
data class SprintMembership(
    val issueIds: List<Long> = emptyList(),
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
 * @property fixedVersionId which release it was fixed in, chosen in the resolution
 *   dialog when the resolution is a *done* one (LNL-134). Required when the project
 *   has `require_fixed_version_on_resolve` on and the chosen resolution is done —
 *   checked server-side, the same rule the editor enforces. Null for an ordinary
 *   move, and left alone unless a resolution is being set: dragging a card between
 *   open columns never touches the fixed version.
 */
@Serializable
data class StatusUpdate(
    val statusId: Long,
    val resolutionId: Long? = null,
    val fixedVersionId: Long? = null,
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
 * @property priorityId the group being ranked, when the drop MOVED the dragged
 *   card into it — a drag across a header in a column grouped by priority, which
 *   the board now honours rather than swallowing (LNL-40). The server applies it
 *   to the dragged issue first and only then checks the group, so `issueIds` is
 *   read as the destination group, not the one the card came from.
 *
 *   Null — the default, and what every caller sent before this existed — means
 *   "leave the priority alone", so an old payload still says exactly what it
 *   used to. Refused when the issue has a resolution: the groups in a closing
 *   column are resolutions, so a priority there names no group at all.
 */
@Serializable
data class IssueOrderUpdate(
    val issueIds: List<Long> = emptyList(),
    val priorityId: Long? = null,
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
    /**
     * The GitHub repository, however the admin cared to write it — a browser URL,
     * an ssh remote, or a bare `owner/name`. Empty unlinks it.
     *
     * Sent as typed rather than parsed in the browser, so the one parse lives
     * server-side and a second client cannot disagree with it. Refused with a
     * message when it is not a repository, at the moment somebody is still on the
     * screen to be told; see the server's `parseRepositoryUrl`.
     *
     * Defaulted, so the create dialog — which does not offer these fields — sends
     * a body that means "no repository" without having to say so.
     */
    val repositoryUrl: String = "",
    /**
     * The name of the environment variable holding the token — read only when
     * [githubTokenMode] is `env`. Empty clears it.
     *
     * Refused unless it carries the required prefix. That refusal is the whole
     * defence against this field being used to read an unrelated secret out of
     * the deployment, so it is enforced on the server and the browser's copy of
     * the rule is only there to say so sooner.
     */
    val githubTokenEnv: String = "",
    /**
     * Which source the token comes from: `none`, `env`, or `literal` (LNL-107).
     *
     * The dialog's radio, sent as the mode string [TokenSource] maps to rather than
     * inferred server-side from which of the two value fields is filled — so that
     * an owner who picks "literal" and leaves the field blank to keep the stored
     * token is understood as keeping it, not as clearing to "none". Defaulted to
     * `env`, the only source that existed before this field, so an old client's
     * body still means what it used to.
     */
    val githubTokenMode: String = TokenModes.ENV,
    /**
     * The literal token — read only when [githubTokenMode] is `literal` (LNL-107).
     *
     * **Write-only, and blank means keep.** The server never sends this back (a
     * stored secret does not travel to the browser), so on every save the field
     * starts empty; an empty value while the mode is `literal` is read as "leave
     * the stored token as it is", and a non-empty one replaces it. That is the only
     * way to edit an unrelated repository field without being made to re-paste the
     * token, and it matches how every other write-only secret field behaves. See
     * ProjectSettingsState.githubTokenMode.
     */
    val githubTokenLiteral: String = "",
)

/**
 * An uploaded attachment's id, which the editor turns into a markdown image.
 *
 * The **public** id, and a `String` since LNL-51 — the row id it used to be was
 * countable, so the URL built from it let anyone enumerate the instance's
 * attachments. The client never learns the row id and has no use for one: all it
 * does with this is spell a URL.
 */
@Serializable
data class AttachmentRef(
    val id: String,
)
