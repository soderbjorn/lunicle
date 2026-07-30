/**
 * Wire types for the three instance-wide tabs of the settings pane — **Who gets in**,
 * **People** and **Instance** (LNL-195).
 *
 * The counterpart to [ProjectSettingsState], and the split between them is the whole
 * reason this file exists. That one is scoped by a project in its URL and answers "who
 * can do what *here*"; this one is scoped by the instance and answers "what is this
 * account, everywhere" and "what is true of this deployment". Neither view can be
 * assembled from the other without N requests, so there are two.
 *
 * Unlike [ProjectSettingsState] there is no narrowed half. That state is sent to every
 * signed-in reader with the admin sections omitted, because a non-admin still has a
 * notification toggle of their own in it. Nothing here belongs to a non-admin, so the
 * route refuses them outright rather than sending an empty shell — see AdminRoutes, and
 * see `SettingsPane.renderTabs`, which hides all three tabs rather than showing three
 * empty ones.
 *
 * ── One state, three tabs, and why it is not three requests ─────────────────
 *
 * The whole payload is a few hundred bytes per account. Splitting it would buy a
 * spinner per tab and cost the property every write here relies on: a write returns the
 * **whole** refreshed state, so no screen ever merges two objects or has to guess what
 * its own edit did elsewhere. Flipping "members may create projects" changes the tier
 * card, and nothing else on any tab has to be told.
 *
 * ── Everything rendered as rungs, not as ticks (LNL-195) ────────────────────
 *
 * The per-project rights table used to be a grid of seven ticks per project per person,
 * built from `RoleDescription` and a list of held keys. A person holds **one** rung per
 * project now, so the table is one row per project saying which rung — and the rung
 * vocabulary travels as [RungOption], the same type the project's own Access section
 * uses, so the two surfaces cannot describe a rung differently.
 *
 * @see ProjectSettingsState
 * @see ProjectAccessState for the audience/rung vocabulary this reuses
 * @see se.soderbjorn.lunicle.clientserver.ApiRoutes.ADMIN_SETTINGS
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.Serializable

/**
 * What one account holds in one project.
 *
 * Sent for **every** project on the instance, including the ones where this account
 * holds nothing. "No access to Lunamux" is the answer an administrator is most often
 * looking for — it is what they check when somebody says they cannot file an issue —
 * and a list that omitted the empty rows would answer that question with silence, which
 * reads identically to a project that does not exist.
 *
 * @property projectName the project's name, so the pane can label the row without
 *   holding a second copy of the project list to look ids up in.
 * @property roleKey the rung this account's **own row** holds here, as
 *   [se.soderbjorn.lunicle.ProjectRole.key], or null for no own row. At most one: a
 *   person holds one rung per project. Null is extremely common and is not the whole
 *   answer — see [effectiveRoleKey].
 * @property effectiveRoleKey what they can **actually do** here: the better of their own
 *   row and the audience rows this project admits them under, which is the server's
 *   `AccessControl.effectiveRole` rule. Null for "no access at all". This is the field
 *   the row renders, because it is the question being asked; [roleKey] is what an
 *   administrator would have to *change* to alter it, which is why both travel.
 * @property viaAudience where the effective rung comes from when it is not their own row
 *   — "the members row" — or null when the own row is the whole story. The `max` rule
 *   made visible at the one place somebody would otherwise be surprised by it, exactly
 *   as [PersonRow.effectiveLine] does on a project's own Access list.
 */
@Serializable
data class AdminProjectRights(
    val projectId: Long,
    val projectName: String,
    val roleKey: String? = null,
    val effectiveRoleKey: String? = null,
    val viaAudience: String? = null,
)

/**
 * One account on this instance, whole.
 *
 * Richer than [ProjectMember] — that one is a row in a grant table and carries a name
 * and an id because a grant needs no more. This is the *subject* of a screen, so it
 * carries what somebody looking at that screen needs to be sure they are looking at the
 * right person.
 *
 * That includes the e-mail, which is the one field here worth stopping on. It
 * deliberately does not cross the wire in `UserRecord.toSignedInUser` or in
 * [ProjectMember], and the reason it does here is that this is the account directory and
 * nothing else: two people called "Robert" are not a hypothetical, and an administrator
 * looking at somebody's access has to know which Robert. It reaches administrators only
 * — the route is refused to everyone else.
 *
 * @property email what we last learned, or null for "we do not know". Null is common and
 *   is a fact worth rendering — an account with no address receives no notifications,
 *   which is a thing an administrator gets asked about.
 * @property tierLabel where this account stands on the instance ladder, in a word:
 *   "Member", "Staff", "Instance admin", "Instance owner". Written server-side because
 *   the ladder is the server's and because staff-ness is derived from a domain no client
 *   is shown (LNL-195).
 * @property isSysAdmin whether this account runs the instance — an administrator or the
 *   owner. Sent as well as [tierLabel] because it changes what the project rows *mean*:
 *   `AccessControl` says yes to an administrator before it looks at a single rung, so
 *   their empty rows do not mean what they say. The pane writes a sentence instead of a
 *   table for them.
 * @property isSelf whether this is the caller, shown for the reason the impersonation
 *   menu shows it: the list is of everyone, and finding yourself in it should not take a
 *   moment's thought.
 * @property hasSignedIn whether anybody has ever signed into this account (LNL-194 added
 *   `users.signed_in_at`). False for an address an administrator added ahead of time; the
 *   row wears a NOT SIGNED IN badge, because a grant nobody has claimed looks exactly
 *   like one that has.
 *
 *   **A never-signed-in row never expires.** There is no cleanup job, no age warning and
 *   no "added 90 days ago" nag anywhere in this design: the row is somebody's deliberate
 *   act, its rungs are live, and the only thing that should remove it is a person
 *   deciding to. See LNL-195.
 * @property isMcpAllowed whether this account's **tier** is permitted agent access.
 *   Read-only and derived — the permission is two switches per tier on this same state
 *   (see [TierCard.mayUseAgents]), and there is no per-person override in this design.
 *   The per-account switch that used to sit beside this is **gone**.
 * @property isMcpEnabled whether the person has switched agent access on for themselves.
 *   Read-only here too, and shown beside the permission rather than hidden, because
 *   "permitted, and they have not switched it on" and "permitted, and running" are
 *   different situations to be looking at — the first explains why somebody freshly
 *   permitted still reports that their agent does not work. An administrator cannot set
 *   it: it is the person's own answer, and a screen that let somebody else give it would
 *   record a preference the person never expressed.
 * @property projects every project on the instance, with what this account holds in each.
 *   See [AdminProjectRights].
 */
@Serializable
data class AdminUser(
    val userId: Long,
    val name: String,
    val email: String? = null,
    val tierLabel: String = "",
    val isSysAdmin: Boolean = false,
    val isSelf: Boolean = false,
    val hasSignedIn: Boolean = true,
    val isMcpAllowed: Boolean = false,
    val isMcpEnabled: Boolean = false,
    val projects: List<AdminProjectRights> = emptyList(),
)

/**
 * One tier of account, as the Who-gets-in tab draws it: how many there are, and the two
 * things the whole tier may do (LNL-195).
 *
 * A card per tier rather than four switches in a column, because the switches are only
 * meaningful next to the answer to "how many people is this?" — a deployment where every
 * account is staff and none is a member does not need to think hard about the members
 * row, and cannot tell that from a list of switches.
 *
 * **Guests get no card.** There is no account to permit: a guest is the absence of one,
 * so neither creating a project nor connecting an agent is a thing they could be
 * permitted to do. What a guest may *read* is a project's own guest audience row.
 *
 * @property title what to call them — "Staff", "Members".
 * @property subtitle who that is, in one sentence. Written server-side because the staff
 *   card's answer names the deployment's own domain.
 * @property accountCount how many accounts stand at this tier right now. Administrators
 *   and the owner are counted at *their* rung and not here — they are senior to both
 *   tiers and are permitted regardless, so counting them in would make the card claim
 *   people the switches do not govern.
 * @property createKey the switch a change to [mayCreateProjects] names. Carried rather
 *   than derived in the browser so the view keeps making no decisions, and so a tier
 *   added later needs no new branch in a renderer.
 * @property agentsKey likewise for [mayUseAgents].
 */
@Serializable
data class TierCard(
    val key: String,
    val title: String,
    val subtitle: String = "",
    val accountCount: Int = 0,
    val mayCreateProjects: Boolean = false,
    val mayUseAgents: Boolean = false,
    val createKey: InstanceSettingKey,
    val agentsKey: InstanceSettingKey,
)

/**
 * What this deployment is, as read-only fact (LNL-195).
 *
 * Every field here is deploy-time configuration that no screen can edit — `brand.json`,
 * two environment variables, a mail transport. It is sent because an administrator
 * asking "why is this person a member rather than staff" or "why can I not pick that
 * admission policy" needs the inputs visible *somewhere*, and the answer being invisible
 * is what makes the greying beside a policy look like a bug.
 *
 * @property staffDomain the organisation's own domain, or null when it has none. Null
 *   means there is no staff tier at all: everybody signed in is a member.
 * @property waysIn the doors, named — "Google", "mailed code". Empty means nobody can
 *   sign in here at all, which is a real (and usually accidental) configuration.
 * @property googlePin the domain Google's account chooser is pinned to, or null for an
 *   open chooser. A real gate and not a hint: the server refuses a Google account whose
 *   hosted-domain claim does not match.
 * @property brandName what a brand directory calls this deployment, or null for the
 *   default look. Independent of [staffDomain] — a deployment can be branded without
 *   naming a domain, and vice versa.
 */
@Serializable
data class DeploymentFacts(
    val staffDomain: String? = null,
    val waysIn: List<String> = emptyList(),
    val googlePin: String? = null,
    val brandName: String? = null,
)

/**
 * One account the instance could be handed to (LNL-198).
 *
 * ── Who is on this list, and why it is this narrow ──────────────────────────
 *
 * **Staff who have signed in**, and nobody else. Not a member, and not a row somebody
 * added ahead of time and nobody has ever arrived at ([AdminUser.hasSignedIn]) — so
 * ownership of a deployment cannot land on an address that was typed once into a dialog.
 * The eligible accounts are the ones the deployment itself vouches for: their address is
 * on its own domain, and somebody has actually turned up holding it.
 *
 * A consequence worth stating rather than discovering: on a deployment that names no
 * domain, `UserKind.forEmail` makes **everybody** a member, so this list is empty and
 * [InstanceOwnership.handOverEmptyReason] is what the screen shows instead of a picker.
 * That is the honest answer there — such a deployment cannot tell its own people from
 * anybody else's, so it has nobody to vouch for.
 *
 * Deliberately not [AdminUser]: this is a row in a picker, so it carries what a picker
 * needs to be read and no more. Re-derived on the server on the write as well as here —
 * an id in a request body is a claim, never an eligibility.
 *
 * @property name what to call them in the menu, and what the typed phrase names.
 * @property email the disambiguator, for [AdminUser.email]'s reason: two people called
 *   "Robert" are not a hypothetical, and this is the one pick that cannot be undone.
 */
@Serializable
data class OwnerCandidate(
    val userId: Long,
    val name: String,
    val email: String? = null,
)

/**
 * Who owns this instance, and who administers it alongside them (LNL-195).
 *
 * Shown to every administrator, because "who do I ask" is the question an administrator
 * who has just hit a refusal is holding. **Handing it over is the owner's alone** — see
 * [canHandOver], wired up by LNL-198.
 *
 * @property ownerName the owner's name, or null when nobody owns this instance yet —
 *   which is a real state on a volume that has never had an account, and one worth
 *   saying out loud rather than rendering as a blank.
 * @property ownerEmail the owner's address, for the reason [AdminUser.email] crosses:
 *   two accounts can share a display name and this is the one row where being sure
 *   matters most.
 * @property isOwnerSelf whether the caller is the owner. What decides whether Hand
 *   over… is offered at all.
 * @property adminNames every instance administrator other than the owner, in the
 *   directory's order. Names only: this is a statement about who holds the instance, and
 *   the People tab is where an account is looked at.
 * @property canHandOver whether the caller may hand the instance to somebody else. True
 *   for the owner alone; an administrator sees the row, and who holds it, and no button.
 *
 *   True for the owner **even when [handOverCandidates] is empty** (LNL-198), which is
 *   the one place this rework does not grey a control with a reason beside it. The
 *   reason lives one click in, where the picker would be: "nobody here is eligible" is a
 *   sentence about the *deployment's* domain configuration and takes three lines to say
 *   properly, and three lines of explanation under a permanent grey button on the
 *   Instance tab would be read by every owner forever rather than by the one who went
 *   looking. See [handOverEmptyReason].
 * @property handOverBlockedReason why not, when they may not — shown beside the dead
 *   control rather than instead of it, the rule this whole rework follows. Null for the
 *   owner, who may.
 * @property handOverCandidates the accounts this instance could be handed to. See
 *   [OwnerCandidate]. Empty for anybody who is not the owner: there is nothing for a
 *   non-owner to pick from, and sending a directory of eligible successors to somebody
 *   who cannot use it would be a list with no purpose.
 * @property handOverEmptyReason why there is nobody to hand it to, when there is nobody —
 *   naming this deployment's own domain, or its absence. Null when there is somebody, so
 *   the dialog shows either a picker or a sentence and never both.
 */
@Serializable
data class InstanceOwnership(
    val ownerName: String? = null,
    val ownerEmail: String? = null,
    val isOwnerSelf: Boolean = false,
    val adminNames: List<String> = emptyList(),
    val canHandOver: Boolean = false,
    val handOverBlockedReason: String? = null,
    val handOverCandidates: List<OwnerCandidate> = emptyList(),
    val handOverEmptyReason: String? = null,
)

/**
 * "Hand this deployment to that account" (LNL-198).
 *
 * Names the account rather than a delta, for [SetInstanceSettingRequest]'s reason, and
 * carries nothing about who is asking — that is the session cookie's job, re-derived on
 * every request. See `AccessControl.canHandOverInstance`.
 *
 * The id is a **claim**, and the route treats it as one: it resolves the account,
 * re-derives eligibility against the deployment's own domain and the account's sign-in
 * stamp, and refuses anything else. A body cannot make somebody eligible by naming them.
 */
@Serializable
data class HandOverInstanceRequest(
    val userId: Long,
)

/**
 * Everything the three instance tabs need, in one round-trip.
 *
 * @property rungs what a rung *is*, on this server — the same [RungOption] list the
 *   project Access section is handed. Sent rather than compiled into the bundle so the
 *   client renders [AdminProjectRights] against the server's vocabulary and a rolled-back
 *   server cannot be described with a rung it does not have. This **replaced**
 *   `roles: List<RoleDescription>`, which described a world of independent privileges
 *   that no longer exists.
 * @property users every account, administrators first and then by name. The order is the
 *   server's so that two administrators looking at the same instance see the same list.
 * @property projects every project on the instance, in the order an administrator
 *   arranged them (LNL-93) — the order every picker and rail shows. Reordered from the
 *   **Instance** tab as of LNL-195: display order is an instance-wide fact, and the
 *   Projects rail is per-caller. Empty on a fresh instance, and with one project there
 *   is nothing to arrange, so the tab says so rather than showing dead arrows.
 * @property admission who may hold an account here, and which of the three answers this
 *   deployment can honour. Computed server-side, greying and all — see [AdmissionState]
 *   and `InstanceIdentity.outsiderCanArrive`, which says at length why a client must not
 *   re-derive it.
 * @property deployment the read-only facts the greying is computed from. See
 *   [DeploymentFacts].
 * @property tiers one card per tier of account that exists here — Members always, Staff
 *   only on a deployment that names a domain. See [TierCard].
 * @property newProjectAudiences the audience rows a **new** project is created with,
 *   using the same [AudienceRow] type a project's own Access list uses. Copied into the
 *   project at creation and never consulted again, so editing this changes nothing about
 *   any project that already exists. A guest row is greyed while [allowPublicProjects] is
 *   off, exactly as it is on a project.
 * @property canReorderProjects whether this caller may change the project order, or delete
 *   a project they do not own. **The instance owner's, not an administrator's** — LNL-191
 *   narrowed that on purpose, and everything else on these three tabs is an
 *   administrator's, so the one narrowed capability has to be sent rather than assumed.
 *   False greys the arrows and the Delete with [projectSetReadOnlyReason] beside them,
 *   which is how an administrator learns whose it is instead of collecting a 403.
 * @property projectSetReadOnlyReason the sentence for that, or null when they may.
 * @property ownership who owns and who administers this instance. See [InstanceOwnership].
 * @property allowPublicProjects whether a project's owner may hand its guest audience a
 *   rung — publishing a board to the world. **Off by default**, so out of the box every
 *   project's Guests row is greyed; that is deliberate. A veto rather than a default:
 *   while it is off the server refuses a guest row whoever writes it.
 * @property hideDisplayName whether the display-name override in the You tab is hidden
 *   (LNL-137). Its own field rather than read back off [SessionState] because this is the
 *   administrator's editable copy — the write returns a whole fresh state with it
 *   rewritten, as every switch here does.
 */
@Serializable
data class AdminSettingsState(
    val rungs: List<RungOption> = emptyList(),
    val users: List<AdminUser> = emptyList(),
    val projects: List<ProjectSummary> = emptyList(),
    val admission: AdmissionState = AdmissionState(),
    val deployment: DeploymentFacts = DeploymentFacts(),
    val tiers: List<TierCard> = emptyList(),
    val newProjectAudiences: List<AudienceRow> = emptyList(),
    val canReorderProjects: Boolean = false,
    val projectSetReadOnlyReason: String? = null,
    val ownership: InstanceOwnership = InstanceOwnership(),
    val allowPublicProjects: Boolean = false,
    val hideDisplayName: Boolean = false,
)
