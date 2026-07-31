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
 *
 *   **What they arrive at, not what the row stores** (LNL-209), which differs in exactly
 *   one direction: never *below* [floorKey], because the audiences nest and a member
 *   matches the guest row too. `Guests → Viewer, Members → No access` was a members row
 *   reading "No access" on a board every stranger could read — true about the row and the
 *   opposite of true about the audience, said in the two words most likely to be read as
 *   the latter. So the Members row on a public board reads Viewer, and what it stores
 *   underneath stays stored: lower the guests row and it falls straight back. See
 *   [floorKey].
 *
 *   Otherwise **as stored**, capped, and not blanked when the instance's publish veto has
 *   silenced it (LNL-203): nothing withdrew the row, and turning the switch back on restores
 *   it exactly, so sending null would be the screen lying in the other direction — an owner
 *   would think their board was already closed. What the row is *not doing* is said in
 *   [unavailableReason] and in [ProjectAccessState.visibilityLine]. A vetoed guest row
 *   grants nothing and therefore floors nothing, so the two rules cannot both bite a row.
 * @property isSelectable whether this caller may change this row at all. One refusal
 *   reaches here and only one: the caller does not own this project.
 *
 *   The instance's "allow projects to be public" switch used to grey the guest row too,
 *   and must not (LNL-203): a dead row is a row nobody can set to "No access", so an owner
 *   whose board was published before the switch went off had no in-app way to close it.
 *   The veto kills the **rungs** instead — every entry in [rungs] arrives dead with the
 *   reason, which leaves "No access" as the one live choice in the menu. Refusing to grant
 *   public access is a policy; refusing to withdraw it is only a bug.
 * @property unavailableReason the sentence beside this row, or null when there is nothing
 *   to say. Present on a **live** row as well as a dead one, which is not a contradiction:
 *   a guest row that is stored while the deployment forbids public projects is live (so it
 *   can be withdrawn) and needs saying out loud that it is *not in effect*. The refusals it
 *   explains are enforced server-side by `AccessControl.canSetAudience` and by the access
 *   rule itself regardless of what this says — the wording is the explanation, not the
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
 *
 *   A rung below [floorKey] arrives dead here too, on the same terms and for the reason
 *   [floorKey] gives.
 * @property floorKey the rung a **wider** audience already gives this one, or null where
 *   none does (LNL-209) — the guests row's rung on the members row, and the better of the
 *   two on the staff row.
 *
 *   The audiences nest: an audience row is a statement about everybody at or above its
 *   tier, so everybody it describes is described by every wider row as well, and the
 *   server takes the **best** of them. A row can therefore never come to less than the row
 *   above it, and this is that sentence made visible — it is what [roleKey] cannot go
 *   below, what strikes the rungs beneath it, and what [withdrawRefusal] explains.
 *
 *   Computed from the rows **in effect**, so a guest row the deployment has vetoed floors
 *   nothing: a board that is not public must let its members row say "No access" and mean
 *   it (LNL-203).
 * @property withdrawRefusal why this row cannot be set to "No access", or null because it
 *   can. Present exactly when [floorKey] is — the picker's own "No access" entry is not a
 *   [RungOption] and so has nowhere else to carry its reason.
 *
 *   Note what is **not** refused: coming down *to* [floorKey]. That rung stays live, and an
 *   owner taking Members from Contributor back to nothing-of-its-own on a public board
 *   writes Viewer and is done. Without that the rule would be a trap — the only way back
 *   would be to close the board, withdraw, and publish it again, which is three writes and
 *   a board that goes briefly private to express one that never changed.
 * @property effectiveLine where [roleKey] comes from when it is not this row's own doing —
 *   "Members are inside Guests, which admits everybody as Viewer." — or null when the row
 *   is the whole story. [PersonRow.effectiveLine] for audiences, and the same `max` rule
 *   made visible at the second place somebody would otherwise be surprised by it.
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
    val floorKey: String? = null,
    val withdrawRefusal: String? = null,
    val effectiveLine: String? = null,
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
 * @property visibilityLine "Visible to …" — what the audience rows add up to, in one
 *   sentence, computed from the rows that are **in effect** rather than the rows as
 *   stored (LNL-203).
 *
 *   The two used to be the same thing. They stopped being it when the instance's "allow
 *   projects to be public" switch became a term in the access rule: a board can carry a
 *   stored `guest → viewer` row and still be readable by nobody outside its own people,
 *   and an owner reading a picker that says "Viewer" cannot tell. So this line never
 *   counts a guest row the deployment has vetoed, and the row itself says it is stored but
 *   not in effect — see [AudienceRow.unavailableReason]. A screen that went on claiming
 *   "public" would leave the original complaint intact in a different place.
 *
 *   Names only the **widest** audience in effect, because the audiences nest: everybody
 *   with an account is included in "anybody at all".
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
 * @property newAddressRefusal why a **brand-new** address off [staffDomain] cannot be
 *   added here, or null when any address may be. This is the admission policy's word,
 *   not the domain's: **a pinned domain by itself restricts nothing** — pinning the
 *   Google chooser is sign-in ergonomics, and it is the admission setting that decides
 *   who may hold an account. So this is null under `anyone` even on a deployment with a
 *   domain, and non-null under the two domain policies.
 *
 *   Sentence about an address **off the domain**, and never about one on it — which is
 *   why a screen asks [newAddressRefusalFor] rather than reading this. Reading it
 *   directly is the defect that shipped: a domain-restricted deployment has a non-null
 *   refusal standing at all times, so the picker refused every new address including the
 *   on-domain ones — the entire population such a deployment admits — and printed
 *   "admits framna.com addresses only" underneath a framna.com address.
 *
 *   Scoped to a *new* address on purpose. An account that already exists is addable
 *   whatever its domain and whatever the policy says — it is already through the door,
 *   and admission is checked once at account creation. That is why the picker can offer
 *   an off-domain colleague from the directory while refusing to invent one.
 *
 *   Sent as a sentence rather than as a flag plus a domain, for this file's standing
 *   reason: the wording distinguishes the two domain policies ("admits X addresses only"
 *   versus "outside X, only addresses that already have an account"), and a client
 *   assembling that from parts would be re-deriving the policy. The route refuses
 *   independently — see `ProjectSettingsRoutes`' people POST — so this is the
 *   explanation, never the enforcement.
 */
@Serializable
data class ProjectAccessState(
    val audiences: List<AudienceRow> = emptyList(),
    val visibilityLine: String = "",
    val people: List<PersonRow> = emptyList(),
    val rungs: List<RungOption> = emptyList(),
    val canGrant: Boolean = false,
    val readOnlyReason: String? = null,
    val addressAdvice: String = "",
    val staffDomain: String? = null,
    val newAddressRefusal: String? = null,
) {
    /**
     * [newAddressRefusal], asked of the address somebody actually typed: the sentence when
     * this deployment would refuse to create an account for [address], null when it would
     * take it.
     *
     * ── The refusal is about being outside, not about being new ─────────────────
     *
     * Both domain policies refuse exactly one thing — a new account **off** [staffDomain] —
     * and the server computes [newAddressRefusal] by probing a synthetic outside address, so
     * on a domain-restricted deployment it is non-null forever. Applying it to whatever was
     * typed therefore refuses everybody, on-domain colleagues included, which is the only
     * kind of person such a deployment can be asked to add.
     *
     * So this is the term that was missing, and it belongs here rather than in a screen: the
     * refusal and the reason it applies are one thought, and a panel re-deriving "is this
     * address one of ours" is a second opinion that can differ from the server's.
     *
     * The rule is the server's `UserKind.forEmail`, deliberately spelled the same way —
     * last `@`, whole domain, case-insensitive. No subdomain match: `a@x.acme.com` is not
     * staff at `acme.com` there, and it must not be addable here on a reading this file
     * invented.
     *
     * An **explanation, never enforcement.** The people POST asks `admitsNewAccount` again
     * and is the one that refuses; this decides which row to draw.
     */
    fun newAddressRefusalFor(address: String): String? {
        val refusal = newAddressRefusal ?: return null
        val domain = staffDomain?.trim()?.takeIf { it.isNotEmpty() } ?: return refusal
        val at = address.trim().lastIndexOf('@')
        if (at < 0) return refusal
        val typedDomain = address.trim().substring(at + 1)
        return if (typedDomain.equals(domain, ignoreCase = true)) null else refusal
    }
}

/**
 * One account the people picker may offer, as a row in its list.
 *
 * ── Why the picker is a search and not the whole list ────────────────────────
 *
 * [ProjectAccessState.people] is exceptions only, deliberately — a screen listing every
 * account beside a rung answers "who can get in here" with a directory. But *granting*
 * needs the directory, because the person you are about to add is by definition not an
 * exception yet, and asking an administrator to retype the address of somebody the
 * instance already knows is the defect this row exists to remove. So the directory
 * arrives through its own request, narrowed, rather than riding on the access state:
 * these carry addresses, and shipping every one of them to every project Admin on the
 * chance they open the picker is not a narrowing anybody chose.
 *
 * @property userId what a grant names — see [RungGrant]. The whole point of the picker:
 *   an account that exists is granted **by id**, so nobody has to know its address to
 *   hand it a rung. The address is shown to tell two people of the same name apart, not
 *   to be typed back in.
 * @property name their display name.
 * @property email their address, shown beneath the name. Sent only to a caller who may
 *   grant here — the same narrowing [PersonRow.email] is sent under, for the same
 *   reason.
 * @property badge the instance tier, as a word: "STAFF", "MEMBER". Derived from the
 *   address on the server (see `UserKind.forEmail`) and never re-derived here; a client
 *   comparing domains would be a second copy of that rule.
 * @property hasSignedIn false for an address added ahead of its owner's arrival. The row
 *   wears NOT SIGNED IN, which is also the answer to a mistyped-but-valid address: it
 *   never goes green, so a grant nobody claimed stays visible as one.
 * @property heldRoleLabel what they already hold here, or null when they hold nothing.
 *   Non-null makes the row **inert** rather than absent: silence reads as a broken
 *   search, so somebody already on the board is shown, dimmed, saying so.
 * @property inertReason why this row cannot be picked, or null when it can. Two cases
 *   reach here and they read differently on screen — already on the board
 *   ("Already Contributor"), and holding the instance ("Owner on every board"), which is
 *   not a grant that could be withdrawn from this screen at all.
 */
@Serializable
data class PersonCandidate(
    val userId: Long,
    val name: String,
    val email: String,
    val badge: String = "",
    val hasSignedIn: Boolean = true,
    val heldRoleLabel: String? = null,
    val inertReason: String? = null,
)

/**
 * The picker's answer: the rows to draw, and how much was left out.
 *
 * @property candidates the matches, already ordered and already truncated. Ordered as the
 *   prototype orders them — people not yet on this project first, then staff before
 *   member, then by name — because the top of the list should be who you are most likely
 *   to be reaching for.
 * @property totalMatches how many matched in total, so the footer can say "N more match
 *   — keep typing to narrow it". A count rather than the rows themselves: the point of
 *   the cap is not to send them.
 */
@Serializable
data class PersonCandidates(
    val candidates: List<PersonCandidate> = emptyList(),
    val totalMatches: Int = 0,
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
