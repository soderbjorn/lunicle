/**
 * Wire types for the instance settings dialog: the accounts on this server, what
 * each of them holds in each project, and who may bring an agent.
 *
 * The counterpart to [ProjectSettingsState], and the split between them is the
 * whole reason this file exists. That one is scoped by a project in its URL and
 * answers "who can do what *here*" — it is the thing an admin opens while
 * configuring one board. This one is scoped by the instance and answers "what is
 * this account, everywhere" — the question you have when somebody joins, or
 * leaves, or asks why their agent cannot connect. Neither view can be assembled
 * from the other without N requests, so there are two.
 *
 * Unlike [ProjectSettingsState] there is no narrowed half here. That state is sent
 * to every signed-in reader with the admin sections omitted, because a non-admin
 * still has a notification toggle of their own to manage in that dialog. Nothing
 * in *this* one belongs to a non-admin, so the route refuses them outright rather
 * than sending an empty shell — see AdminRoutes.
 *
 * @see ProjectSettingsState
 * @see se.soderbjorn.lunicle.clientserver.ApiRoutes.ADMIN_SETTINGS
 */
package se.soderbjorn.lunicle.clientserver

import kotlinx.serialization.Serializable

/**
 * What one account holds in one project.
 *
 * Sent for **every** project on the instance, including the ones where this user
 * holds nothing. "No rights in Lunamux" is the answer an admin is most often
 * looking for — it is what they check when somebody says they cannot file an issue
 * — and a list that omitted the empty rows would answer that question with
 * silence, which reads identically to a project that does not exist.
 *
 * @property projectName the project's name, so the detail pane can label the row
 *   without holding a second copy of the project list to look ids up in.
 * @property heldRoleKeys the roles this user holds here, as [RoleDescription.key].
 *   Keys rather than an enum, for [ProjectMember.roleKeys]' reason: the client
 *   renders them against [AdminSettingsState.roles] and has no business knowing
 *   what any of them mean. The **raw grant**, deliberately — see [canSeeProject]
 *   for why "can they see it" is not one of these keys.
 * @property canSeeProject whether this user can read this project *at all*, which
 *   is not the same as holding [RoleKeys.VIEWER]. The server's
 *   [se.soderbjorn.lunicle.AccessControl.canReadProject] says yes to a public
 *   project for everyone, and to anyone holding any role here — so a user with no
 *   `view_project` grant, or no grant at all on a public board, still sees it. The
 *   dialog draws the "see this project" row from this and every other row from
 *   [heldRoleKeys]; without it that one row shows a red cross where the server
 *   says yes. Effective, not raw, and the counterpart to [heldRoleKeys] the way
 *   [AdminUser.isMcpEnabled] is the effective counterpart to a bare permission.
 */
@Serializable
data class AdminProjectRights(
    val projectId: Long,
    val projectName: String,
    val heldRoleKeys: List<String> = emptyList(),
    val canSeeProject: Boolean = false,
)

/**
 * One account on this instance, whole.
 *
 * Richer than [ProjectMember] — that one is a row in a grant table and carries a
 * name and an id because a grant needs no more. This is the *subject* of a screen,
 * so it carries what somebody looking at that screen needs to be sure they are
 * looking at the right person.
 *
 * That includes the e-mail, which is the one field here worth stopping on. It
 * deliberately does not cross the wire in [UserRecord.toSignedInUser] or in
 * [ProjectMember], and the reason it does here is that this is the account
 * directory and nothing else: two people called "Robert" are not a hypothetical,
 * and an admin about to turn off somebody's agent access has to know which Robert.
 * It reaches admins only — the route is refused to everyone else, so this is not a
 * field that became visible, it is a field that became visible *to the one role
 * that can already impersonate any of these accounts*.
 *
 * @property email what we last learned, or null for "we do not know". Null is
 *   common and is a fact worth rendering — an account with no address receives no
 *   notifications, which is a thing an admin gets asked about.
 * @property isSysAdmin whether this account is an instance admin. Sent because it
 *   changes what the rights list *means*: [se.soderbjorn.lunicle.AccessControl]
 *   says yes to an admin before it looks at a single role, so an admin's empty
 *   project rows do not mean what they say. The detail pane writes a sentence
 *   instead of a table for them.
 * @property isSelf whether this is the caller, shown for the reason the
 *   impersonation menu shows it: the list is of everyone, and finding yourself in
 *   it should not take a moment's thought.
 * @property isMcpAllowed whether this account's **tier** is permitted agent access
 *   (LNL-192). **Read-only**, and derived: the permission is two switches on
 *   [AdminSettingsState] now, and there is no per-person override in this design —
 *   so this reports which side of them an account falls on rather than something
 *   this screen sets. **Not an affordance** — unlike almost everything else on this
 *   wire it mirrors a real server-side gate, re-read per request by both
 *   `/oauth/authorize` and `/mcp`.
 * @property isMcpEnabled whether the user has switched agent access on for
 *   themselves. **Read-only here**, and shown next to the permission rather than
 *   hidden because "permitted, and they have not switched it on" and "permitted,
 *   and running" are different situations for an admin to be looking at — the
 *   first explains why somebody who was just granted access still reports that
 *   their agent does not work.
 *
 *   An admin cannot set it. It is the user's own answer, and a screen that let
 *   somebody else give it would be recording a preference the user never
 *   expressed. See the server's canUseMcp for how the pair combines.
 * @property projects every project on the instance, with what this user holds in
 *   each. See [AdminProjectRights].
 */
@Serializable
data class AdminUser(
    val userId: Long,
    val name: String,
    val email: String? = null,
    val isSysAdmin: Boolean = false,
    val isSelf: Boolean = false,
    val isMcpAllowed: Boolean = false,
    val isMcpEnabled: Boolean = false,
    val projects: List<AdminProjectRights> = emptyList(),
)

/**
 * Everything the instance settings dialog needs, in one round-trip.
 *
 * One state rather than "a user list, then a detail fetch per click", which is the
 * shape this obviously suggests and is the wrong one here. The whole payload is a
 * few hundred bytes per account; a per-click fetch would buy nothing and would put
 * a spinner inside a master-detail pane, where the entire point is that picking a
 * name is instant. It also means a write can return the whole thing — see
 * [LunicleApi.setInstanceSetting] — so the dialog never merges two objects.
 *
 * @property roles what a role *is*, on this server. Sent rather than compiled into
 *   the bundle, for [RoleDescription]'s reason: the client renders
 *   [AdminProjectRights.heldRoleKeys] against this list and a hardcoded copy would
 *   describe a rolled-back server's roles wrongly.
 * @property users every account, ordered as the store returns them — by name, case
 *   insensitively. The order is the server's so that two admins looking at the same
 *   instance see the same list.
 * @property projects every project on the instance, in the order a system
 *   administrator arranged them (LNL-93) — the same order the picker shows. The
 *   Projects tab of the dialog reorders and deletes from this list; a reorder or a
 *   delete returns a whole fresh state with this field rewritten, so the dialog
 *   never merges two objects. Empty on a fresh instance with no projects yet, and
 *   for the many deployments where only one exists there is nothing to arrange —
 *   the tab says so rather than showing a single row with dead arrows.
 * @property admission who may hold an account on this deployment, and which of the
 *   three answers the deployment can actually honour (LNL-192). Computed
 *   server-side, greying and all — see [AdmissionState], which says at length why a
 *   client must not re-derive it.
 * @property allowPublicProjects whether a project's owner may hand its guest
 *   audience a rung — that is, publish a board to the world (LNL-192). Off by
 *   default, and a veto rather than a default: while it is off the server refuses a
 *   guest audience row whoever writes it, and every project's Access list greys that
 *   row. See [InstanceSettingKey.ALLOW_PUBLIC_PROJECTS].
 * @property staffMayCreateProjects whether an account on the deployment's own domain
 *   may create a project. See [InstanceSettingKey.STAFF_MAY_CREATE_PROJECTS]. Note
 *   an instance administrator may regardless, being senior to both tiers — so this
 *   being off does not mean nobody can.
 * @property memberMayCreateProjects the same, for everybody else signed in.
 * @property staffMayUseAgents whether an account on the deployment's own domain is
 *   permitted to connect an agent. **A permission, not the access**: the person
 *   still switches it on themselves. See [InstanceSettingKey.STAFF_MAY_USE_AGENTS].
 * @property memberMayUseAgents the same, for everybody else signed in.
 * @property hideDisplayName whether the display-name override in the profile dialog
 *   is hidden (LNL-137). Its own field rather than reading it back off
 *   [SessionState] because this is the admin's editable copy — the write returns a
 *   whole fresh state with it rewritten, the same way every other switch in this
 *   dialog does. See [InstanceSettingKey.HIDE_DISPLAY_NAME].
 */
@Serializable
data class AdminSettingsState(
    val roles: List<RoleDescription> = emptyList(),
    val users: List<AdminUser> = emptyList(),
    val projects: List<ProjectSummary> = emptyList(),
    val admission: AdmissionState = AdmissionState(),
    val allowPublicProjects: Boolean = false,
    val staffMayCreateProjects: Boolean = false,
    val memberMayCreateProjects: Boolean = false,
    val staffMayUseAgents: Boolean = false,
    val memberMayUseAgents: Boolean = false,
    val hideDisplayName: Boolean = false,
)
