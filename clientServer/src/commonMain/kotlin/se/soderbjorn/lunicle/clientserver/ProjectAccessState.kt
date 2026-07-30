/**
 * Who a project admits, as the Access section renders it (LNL-194).
 *
 * ── Two lists, and the shape is the argument ─────────────────────────────────
 *
 * **Audience rows** — one per instance role: Guests, Members, Staff. Each says at
 * what rung that whole audience arrives, or nothing. They replace the old `Public`
 * and `Signed-in` switches: "public" is the guest row holding Viewer and
 * "signed-in" is the members row, except that a row can now say *Contributor* — so
 * "everybody here may file bugs" is one row rather than a grant per person.
 *
 * **Person rows** — exceptions only. Somebody who needs more than their audience
 * gets a row; everybody who is adequately served by an audience row does not appear
 * at all. That is what keeps the list short enough to *be* the audit: a screen
 * listing every account on the instance beside a rung answers "who can get in here"
 * with a directory, which is not an answer.
 *
 * The effective rung is the **best** of the two, never the worst — see the server's
 * `AccessControl.effectiveRole`. So a person row can only raise somebody, and
 * lowering is done by lowering the audience, which is a statement about everybody
 * and is visible as one.
 *
 * ── Every refusal arrives worded, and nothing is hidden ──────────────────────
 *
 * A rung the caller may not hand out is **sent, greyed, with the sentence saying
 * why**; so is an audience row this deployment forbids. Two reasons. A control that
 * vanishes reads as a bug, where a dead one with a sentence beside it tells you who
 * to ask. And the greying and the refusal have to agree — so the greying is computed
 * where the refusal lives, on the server, and the screen renders what it is handed
 * rather than re-deriving a ladder it would eventually re-derive differently.
 *
 * @see ProjectSettingsState.access
 * @see se.soderbjorn.lunicle.AccessControl
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.Serializable

/**
 * One rung, as a picker offers it.
 *
 * @property key the rung, as [se.soderbjorn.lunicle.ProjectRole.key] — what a write
 *   names.
 * @property label the one-word name a menu shows.
 * @property description the rung's own sentence, written once on the server so no
 *   screen can describe a rung differently from the thing granting it.
 * @property isSelectable whether this rung may be chosen *here*, which is two questions
 *   folded into one flag because the picker draws one list: whether **this caller** may
 *   hand it out (an Admin grants up to Maintainer; Admin and Owner are an Owner's alone),
 *   and — on an [AudienceRow]'s own [AudienceRow.rungs] — whether **this audience** may
 *   ever hold it. The guests row stops at Viewer; see [AudienceRow.rungs].
 * @property unavailableReason why not, when [isSelectable] is false — "You are an
 *   Admin here, so Owner is not yours to hand out." Present exactly when the rung is
 *   dead, and shown beside it rather than replacing it. It says *which* of the two
 *   refusals applied, which is the only thing distinguishing them at the screen.
 */
@Serializable
data class RungOption(
    val key: String,
    val label: String,
    val description: String = "",
    val isSelectable: Boolean = true,
    val unavailableReason: String? = null,
)

/**
 * "This whole audience arrives at this rung."
 *
 * @property key the audience, as [se.soderbjorn.lunicle.Audience.key].
 * @property title what to call them — "Guests", "Members", "Staff".
 * @property subtitle who that is, in one sentence. Written server-side because the
 *   staff row's answer names the deployment's own domain.
 * @property roleKey the rung they arrive at, or null for "no access" — the row is
 *   present either way, since the absence of a grant is a state worth being able to
 *   see and to change back.
 * @property isSelectable whether this caller may change this row, and
 *   [unavailableReason] why not. Two different refusals land here: the instance's
 *   "allow projects to be public" switch being off, which greys the **guest** row for
 *   everybody including the deployment's owner, and the caller not owning this
 *   project. The first is enforced server-side by `AccessControl.canSetAudience`
 *   regardless of what this says — the greying is the explanation, not the
 *   enforcement.
 * @property rungs what **this row** may be handed, in ladder order (LNL-202).
 *
 *   Per row rather than one list on [ProjectAccessState], because what an audience may
 *   hold is a fact about the audience: the guest row offers Viewer and nothing above it,
 *   since a guest has no account and every higher rung describes writing. A single
 *   shared list could only express the *caller's* half of the answer, so the guest row
 *   offered Contributor — greyed nowhere and refused nowhere — which is how anonymous
 *   issue filing came to be one dropdown away.
 *
 *   Sent rather than derived, for this file's standing reason: a browser that greyed the
 *   rungs above a ceiling would be re-deriving the ladder, and the copy that renders and
 *   the copy that refuses would eventually disagree. Each option carries its own
 *   [RungOption.unavailableReason], so a rung out of reach is shown with the sentence
 *   saying why — never omitted.
 */
@Serializable
data class AudienceRow(
    val key: String,
    val title: String,
    val subtitle: String = "",
    val roleKey: String? = null,
    val isSelectable: Boolean = true,
    val unavailableReason: String? = null,
    val rungs: List<RungOption> = emptyList(),
)

/**
 * One person who holds something different from what their audience gives them.
 *
 * @property email their address, which **does** cross here where it does not on
 *   [ProjectMember]. It has to: the list is an audit of who was let in by name, and
 *   two accounts can share a display name. The route sends this only to a caller who
 *   reaches Maintainer on this project, which is the narrowing that makes it
 *   acceptable — it is not a directory of the instance, it is the exceptions on one
 *   board.
 * @property roleKey the rung their **own row** holds, which is what the picker
 *   selects and what a write replaces. Null for somebody who holds no own row and is
 *   on this list for another reason — an instance administrator, who reaches Owner
 *   everywhere without one.
 * @property effectiveLine what they can actually do here when that is not simply
 *   [roleKey] — "Effectively Maintainer: the members row gives Contributor." — or
 *   null when the own row is the whole story. The `max` rule made visible at the one
 *   place somebody would otherwise be surprised by it.
 * @property hasSignedIn whether anybody has ever signed into this account. False for
 *   an address an administrator added ahead of time; the row wears a NOT SIGNED IN
 *   badge, because a grant nobody has claimed looks exactly like one that has.
 * @property isEditable whether this row's rung is this caller's to change. False for
 *   an instance administrator (whose rung here is not stored and cannot be lowered)
 *   and for anybody holding a rung above what the caller may hand out.
 * @property note why the row cannot be edited, or another fact worth stating beside
 *   the name. Shown instead of the picker.
 */
@Serializable
data class PersonRow(
    val userId: Long,
    val name: String,
    val email: String = "",
    val roleKey: String? = null,
    val effectiveLine: String? = null,
    val hasSignedIn: Boolean = true,
    val isSelf: Boolean = false,
    val isEditable: Boolean = true,
    val note: String? = null,
)

/**
 * A project's Access section, whole.
 *
 * Absent (null on [ProjectSettingsState.access]) for a caller below Maintainer: the
 * person rows carry addresses, and somebody who merely reads a board has no business
 * receiving the list of exceptions on it. They get
 * [ProjectSettingsState.yourAccessLine] instead, which is a statement about
 * themselves.
 *
 * @property rungs what a **person** row may be handed. The audience rows carry their own
 *   narrowed lists — see [AudienceRow.rungs] — because a ceiling is a fact about an
 *   audience and a person has an account, so nothing narrows theirs but the caller.
 * @property canGrant whether this caller may change anything here at all — Admin and
 *   above. False renders every control dead rather than hiding the lists: a
 *   Maintainer who can see who is on the board and cannot change it is being told the
 *   truth, and a Maintainer shown an empty pane would go looking for a bug.
 * @property readOnlyReason the sentence for that, or null when [canGrant].
 * @property addressAdvice what an administrator needs to know before typing an
 *   address, worded for *this* deployment: nothing is sent, and on a deployment that
 *   cannot mail a code only an address that can sign in with Google will ever arrive
 *   — so adding any other is a grant nobody can claim. Computed server-side, because
 *   the answer depends on whether a mail transport is configured.
 * @property staffDomain the deployment's own domain, or null when it has none. The
 *   add dialog says so when an address is not on it — not a refusal, a fact: adding
 *   an outside address is exactly what the gesture is for, and it is also exactly
 *   what the admission policy may go on to reject.
 */
@Serializable
data class ProjectAccessState(
    val audiences: List<AudienceRow> = emptyList(),
    val people: List<PersonRow> = emptyList(),
    val rungs: List<RungOption> = emptyList(),
    val canGrant: Boolean = false,
    val readOnlyReason: String? = null,
    val addressAdvice: String = "",
    val staffDomain: String? = null,
)

/**
 * "This audience arrives at this rung here", or at none.
 *
 * @property roleKey the rung, or null to withdraw the row entirely. Names the state
 *   to move to rather than "toggle", for [VocabularyEdit]'s reason: a retry says the
 *   same thing, and two owners cannot flip a board's visibility back and forth by
 *   both clicking once.
 */
@Serializable
data class AudienceGrant(
    val audienceKey: String,
    val roleKey: String? = null,
)

/**
 * "This person holds this rung here", or none.
 *
 * Replaces the tick-box `RoleGrant` LNL-191 left standing: a person holds **one**
 * rung per project, so a grant is a move to a rung and a revoke is a move to null.
 * The old shape had to guess what unticking one box among five meant, and answered
 * with a rule ("only if they hold exactly that one") that was correct and impossible
 * to explain.
 *
 * @property roleKey the rung, or null for "no access", which removes their row. The
 *   server checks the caller may hand out **both** the rung being written and the one
 *   being replaced — otherwise an Admin could demote an Owner by handing them Viewer.
 */
@Serializable
data class RungGrant(
    val userId: Long,
    val roleKey: String? = null,
)

/**
 * "Add this address, holding this rung."
 *
 * **No mail is sent.** The address gets a `users` row that has never been signed
 * into and can hold a rung immediately; whoever owns it picks the rung up on their
 * first sign-in. No token, no link, no expiry — so there is nothing to expire, and
 * nothing that has to be delivered for the grant to exist.
 *
 * Idempotent on the address: adding somebody who is already here writes their rung
 * and does not make a second account.
 *
 * @property roleKey the rung to hand them. Required — adding a person with no access
 *   would write an account and grant nothing, which is a way to fill the instance's
 *   user table by accident.
 */
@Serializable
data class PersonAdd(
    val email: String,
    val roleKey: String,
)

/**
 * One section of a project's settings, as the rail draws it.
 *
 * Sent rather than derived in the browser, and that is the point: which sections a
 * caller has is a function of the rung they hold **on this project**, so a rail that
 * decided for itself would be a second copy of the ladder — and the two would
 * disagree the first time a rung's powers moved. The client draws the list it is
 * handed, in the order it is handed, and asks for a section by [key].
 *
 * @property key what the address bar spells, and therefore wire format — see
 *   [ProjectSectionKeys].
 * @property label what the rail draws. The same section can be labelled differently
 *   for different callers: a Viewer's only section is "Your access", which is the
 *   Access section narrowed to a statement about them.
 */
@Serializable
data class ProjectSection(
    val key: String,
    val label: String,
)

/**
 * The section keys, spelled once.
 *
 * Wire format *and* URL format — `?settings=projects&projectId=7&section=access` —
 * so a rename here is a change to everybody's bookmarks. The client's
 * `SETTINGS_SECTION_*` constants forward to these rather than restating them.
 */
object ProjectSectionKeys {
    /** Name, prefix, how the board reads, and — for an owner — deleting it. */
    const val GENERAL: String = "general"

    /** The linked repository and where its token comes from. Owner only. */
    const val GITHUB: String = "github"

    /** The vocabularies that define what the board is, and the ticket requirements. */
    const val STRUCTURE: String = "structure"

    /** The timeboxes. A maintainer's, one rung below Structure. */
    const val SPRINTS: String = "sprints"

    /**
     * The releases. A maintainer's, beside [SPRINTS] (LNL-196).
     *
     * Lifted out of Structure, where it sat among the labels and components, because
     * it is the other of the two vocabularies whose *presence* is the feature flag:
     * make the first version and planned- and fixed-version fields appear across the
     * whole board, exactly as making the first sprint turns on the scope picker. The
     * two belong side by side, one rung below the vocabularies that define what the
     * board is.
     */
    const val VERSIONS: String = "versions"

    /** Who this project admits — or, below Maintainer, what you yourself hold in it. */
    const val ACCESS: String = "access"
}
