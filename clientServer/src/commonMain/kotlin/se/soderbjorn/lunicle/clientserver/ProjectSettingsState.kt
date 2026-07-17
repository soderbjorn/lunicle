/**
 * Wire types for the project settings dialog: the vocabularies, and who may do
 * what.
 *
 * Separate from [BoardState] rather than bolted onto it, and the split is the
 * point. The board sends every reader the vocabulary *names* — a card cannot
 * render "Bug" without them. This sends an admin the vocabulary's **shape**: how
 * many issues hold each row, which row is the magic closing column, and a
 * directory of every account on the instance with its grants. None of that is
 * needed to draw a board, and a signed-out visitor reading a public project has
 * no business receiving a list of everyone who has ever signed in.
 *
 * So this is its own request, refused outright to anyone who is not an admin —
 * not "sent to everyone and hidden by the bundle", which is the failure
 * BoardRoutes' preamble names as the one that gets forgotten.
 *
 * @see BoardState
 * @see se.soderbjorn.lunicle.clientserver.ApiRoutes.projectSettings
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.Serializable

/**
 * Which of a project's five vocabularies a request is about.
 *
 * One enum and one set of routes rather than five near-identical route families,
 * because the five differ in exactly two ways — whether they are ordered, and
 * what deleting one costs — and both of those are decisions the *server* makes.
 * Five copies of "add a row, rename a row, delete a row" would be five places to
 * forget the last-status rule.
 *
 * The four properties below are facts about the *schema*, and they live here
 * rather than on the server because both sides need the same answers and there
 * must not be two of them. The server refuses a delete that [restrictsOnUse]
 * covers; the client, holding the same property, declines to offer the button.
 * That is the affordance relationship this codebase runs on — one rule, enforced
 * on one side and rendered on the other — and it only works while it is literally
 * one expression. Two copies would drift, and the drift would show up as a button
 * that is offered and then refused.
 *
 * @property key what appears in the URL. Explicit rather than `name.lowercase()`:
 *   these strings are wire format, so a rename of the constant must not silently
 *   move a route. Changing one is a client-and-server change, which is precisely
 *   what having to edit this line makes you notice.
 * @property noun what to call one of these in a sentence — "There is already a
 *   label called…". Identical to [key] today, and deliberately not the same
 *   property: one is prose an admin reads and the other is a URL segment, and the
 *   day a kind needs a two-word name is the day conflating them would put a space
 *   in a route.
 * @property plural the same word, more than one of it. Carried rather than
 *   computed, because `noun + "s"` produces "statuss" and "prioritys" — which is
 *   not a hypothetical: it shipped as far as a manual pass, in the sentence
 *   refusing a bad reorder. English plurals are data, not a rule.
 */
@Serializable
enum class VocabularyKind(val key: String, val noun: String, val plural: String) {
    LABEL("label", "label", "labels"),
    COMPONENT("component", "component", "components"),
    STATUS("status", "status", "statuses"),
    PRIORITY("priority", "priority", "priorities"),
    RESOLUTION("resolution", "resolution", "resolutions");

    /**
     * Whether these rows have an order somebody chose.
     *
     * Statuses are board columns, priorities are a scale and resolutions are a
     * grouping — all three *are* their order, and carry a `position` column.
     * Labels and components are a set: `Labels.sq`'s `forProject` sorts them by
     * name, there is no column to write a position into, and an order would
     * answer no question anyone has.
     */
    val isOrdered: Boolean
        get() = when (this) {
            STATUS, PRIORITY, RESOLUTION -> true
            LABEL, COMPONENT -> false
        }

    /**
     * Whether the database refuses to delete one of these while an issue holds it.
     *
     * The three that `issues` points at directly — status, priority, resolution —
     * are composite-keyed with no ON DELETE clause, so SQLite's default RESTRICT
     * fires. Labels and components are reached through `issue_labels` /
     * `issue_components`, whose keys are ON DELETE CASCADE: deleting one of those
     * unlabels the issues and leaves them standing, which is a consequence to warn
     * about rather than a reason to refuse.
     *
     * This mirrors the schema. If an ON DELETE clause ever changes, this is the
     * line that changes with it — and both the server's refusal and the client's
     * disabled button follow, because both read this.
     */
    val restrictsOnUse: Boolean
        get() = when (this) {
            STATUS, PRIORITY, RESOLUTION -> true
            LABEL, COMPONENT -> false
        }

    /**
     * Whether a project stops working without at least one of these.
     *
     * `IssueRepository.createDraft` reads the leftmost status and the middle
     * priority, and errors if either is missing — so a project with none of either
     * cannot take an issue, and cannot be fixed by filing one about it. Deleting
     * the last one is refused for that reason and no other.
     *
     * Nothing else is load-bearing that way. A project with no labels, no
     * components or no resolutions is unremarkable, and every one of them can be
     * added back from the dialog that emptied it. The rule is not "never run out",
     * it is "never become unrepairable".
     */
    val isLoadBearing: Boolean
        get() = this == STATUS || this == PRIORITY
}

/**
 * One row of one vocabulary, as the settings dialog needs it.
 *
 * Richer than the [VocabularyItem] the board gets, and deliberately so — see this
 * file's preamble.
 *
 * @property position where it sits in the order, 0 first. Meaningless for labels
 *   and components, which have no order at all (they sort by name); the dialog
 *   knows which kinds are ordered and does not render arrows for the others.
 * @property requiresResolution whether landing in this column demands a
 *   resolution. Only ever meaningful for a status; see Statuses.sq.
 * @property usageCount how many issues hold this row *right now*. What it means
 *   depends on the kind, and the client must not guess: for a status or a
 *   priority a non-zero count means the delete will be refused, and for a label it
 *   means that many issues will be unlabelled. [VocabularyKind] does not carry
 *   that rule either — [ProjectSettingsState] is rendered by a view model that
 *   phrases both sentences, and the server refuses regardless of what the client
 *   believed.
 *
 *   Drafts are counted. A draft issue holds a status like any other, so it is a
 *   draft that can make a delete fail — see Issues.sq's usageByStatus.
 */
@Serializable
data class VocabularyEntry(
    val id: Long,
    val name: String,
    val position: Int = 0,
    val requiresResolution: Boolean = false,
    val usageCount: Int = 0,
)

/**
 * One role this instance has, and what holding it grants.
 *
 * Sent rather than compiled into the bundle, for the reason the provider flags
 * are: the roles are the server's [se.soderbjorn.lunicle.Role] enum, and a client
 * that hardcoded the list would offer a checkbox for a role a rolled-back server
 * does not have — or, worse, quietly stop offering one it does.
 *
 * @property description the enum's own sentence, shown under the checkbox. Written
 *   once, on the server, so the dialog cannot describe a grant differently from
 *   the thing granting it.
 */
@Serializable
data class RoleDescription(
    val key: String,
    val description: String,
)

/**
 * One account, and what it holds in this project.
 *
 * Every account on the instance, not only the ones with a grant: "assign
 * privileges to other users" needs a list of the users you could assign to, and a
 * table that only showed people who already have a role would have no row to tick
 * for the person you are trying to add.
 *
 * A name and an id, like [UserOption], and for the same reason — no email, no
 * provider. The id is unavoidable: it is what a grant has to name.
 *
 * @property isAdmin whether this account is the instance admin. Sent because an
 *   admin's checkboxes would be meaningless — [se.soderbjorn.lunicle.AccessControl]
 *   says yes to an admin before it ever looks at a role — so the dialog gives the
 *   row a sentence instead of boxes, and sorts it to the top.
 * @property isSelf whether this is the caller. The dialog shows it, like the
 *   impersonation menu does, so the table matches the user list an admin is
 *   looking at.
 * @property roleKeys the roles this user holds *here*. Keys rather than an enum,
 *   because the client renders them against [ProjectSettingsState.roles] and has
 *   no business knowing what any of them mean.
 */
@Serializable
data class ProjectMember(
    val userId: Long,
    val name: String,
    val isAdmin: Boolean = false,
    val isSelf: Boolean = false,
    val roleKeys: List<String> = emptyList(),
)

/**
 * Everything the settings dialog needs, in one round-trip.
 *
 * One state rather than seven endpoints, for [BoardState]'s reason: the dialog
 * cannot render half of this, and seven requests would be seven chances to paint
 * a dialog that is missing a section.
 *
 * @property canMutateProject whether the caller may write any of it. Always true
 *   in practice — the route refuses to *send* this to anyone else, rather than
 *   sending it with the flag false — but it is on the wire because the default is
 *   false, so a bug that forgets to fill it in produces a read-only dialog rather
 *   than one that invites writes the server will refuse. An affordance like every
 *   other flag here.
 */
@Serializable
data class ProjectSettingsState(
    val labels: List<VocabularyEntry> = emptyList(),
    val components: List<VocabularyEntry> = emptyList(),
    val statuses: List<VocabularyEntry> = emptyList(),
    val priorities: List<VocabularyEntry> = emptyList(),
    val resolutions: List<VocabularyEntry> = emptyList(),
    val roles: List<RoleDescription> = emptyList(),
    val members: List<ProjectMember> = emptyList(),
    val canMutateProject: Boolean = false,
) {
    /** The rows of one kind, so a caller with a [VocabularyKind] does not have to branch. */
    fun entriesFor(kind: VocabularyKind): List<VocabularyEntry> = when (kind) {
        VocabularyKind.LABEL -> labels
        VocabularyKind.COMPONENT -> components
        VocabularyKind.STATUS -> statuses
        VocabularyKind.PRIORITY -> priorities
        VocabularyKind.RESOLUTION -> resolutions
    }
}

/** What the "add" field sends. The kind is in the path. */
@Serializable
data class VocabularyAdd(
    val name: String,
)

/**
 * What a rename sends.
 *
 * @property requiresResolution the closing flag, for a status. Ignored by the
 *   server for every other kind rather than refused: the dialog sends the row it
 *   is rendering, and a priority row simply has nothing to put here. Refusing
 *   would make the client responsible for knowing which kinds have the flag,
 *   which is the server's rule to keep.
 *
 *   Sent on every rename rather than as its own toggle route, because a status's
 *   name and its flag are one edit — see Statuses.sq's `update`.
 */
@Serializable
data class VocabularyEdit(
    val name: String,
    val requiresResolution: Boolean = false,
)

/**
 * A whole vocabulary, in the order the admin just put it in.
 *
 * The whole list, not "move row 3 up one" — [IssueOrderUpdate]'s reasoning, for
 * the same reason: a position is a statement about neighbours, and a server told
 * only about the row that moved would have to reconstruct the rest and guess. The
 * client already knows the order; it is what it just rendered.
 *
 * The server checks that these are exactly this vocabulary's ids — all of them,
 * none repeated, nothing foreign — before it writes. A partial list would leave
 * the rows it omitted holding positions that now collide with the ones it named.
 *
 * @property ids every row of one kind, first to last.
 */
@Serializable
data class VocabularyOrder(
    val ids: List<Long> = emptyList(),
)

/**
 * "Give this user this role here", or take it away.
 *
 * [isGranted] is the state to move to rather than a toggle, for
 * [McpEnabledRequest]'s reason: a retry says the same thing, and two admins with
 * the dialog open cannot flip a grant back and forth by both clicking once.
 *
 * Note what this does not say: who is asking. That comes from the session cookie
 * server-side, on every request. A field for it would be the authorization system
 * asking the caller to authorize themselves — see [ImpersonateRequest], which is
 * the same shape for the same reason.
 *
 * @property userId whose privileges to change.
 * @property roleKey which role, as [RoleDescription.key]. A key this server does
 *   not have is a 400: the alternative is `INSERT OR IGNORE` quietly doing
 *   nothing while the dialog re-renders the box as ticked.
 */
@Serializable
data class RoleGrant(
    val userId: Long,
    val roleKey: String,
    val isGranted: Boolean,
)
