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
 * @property isMcpAllowed whether an admin permits this user to have agent access.
 *   The one thing this screen can change. **Not an affordance** — unlike almost
 *   everything else on this wire it mirrors a real server-side gate, re-read per
 *   request by both `/oauth/authorize` and `/mcp`.
 * @property isMcpEnabled whether the user has switched agent access on for
 *   themselves. **Read-only here**, and shown next to the permission rather than
 *   hidden because "permitted, and they have not switched it on" and "permitted,
 *   and running" are different situations for an admin to be looking at — the
 *   first explains why somebody who was just granted access still reports that
 *   their agent does not work.
 *
 *   An admin cannot set it. It is the user's own answer, and a screen that let
 *   somebody else give it would be recording a preference the user never
 *   expressed. See the server's UserRecord.canUseMcp for how the pair combines.
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
 * [LunicleApi.setUserMcpEnabled] — so the dialog never merges two objects.
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
 * @property requireSignIn whether signing in is required to use the app at all
 *   (LNL-115). The General tab's first switch. Its own field rather than reading it
 *   back off [SessionState] because this is the admin's editable copy — the write
 *   returns a whole fresh state with it rewritten, the same way every other switch
 *   in this dialog does. See [InstanceSettingKey.REQUIRE_SIGN_IN].
 * @property anyoneCanCreateProject whether any signed-in user may create a project,
 *   rather than only a system administrator (LNL-115). The General tab's second
 *   switch. See [InstanceSettingKey.ANYONE_CAN_CREATE_PROJECT].
 * @property hideDisplayName whether the display-name override in the profile dialog
 *   is hidden (LNL-137). The General tab's third switch. Its own field rather than
 *   reading it back off [SessionState] because this is the admin's editable copy —
 *   the write returns a whole fresh state with it rewritten, the same way every
 *   other switch in this dialog does. See [InstanceSettingKey.HIDE_DISPLAY_NAME].
 */
@Serializable
data class AdminSettingsState(
    val roles: List<RoleDescription> = emptyList(),
    val users: List<AdminUser> = emptyList(),
    val projects: List<ProjectSummary> = emptyList(),
    val requireSignIn: Boolean = false,
    val anyoneCanCreateProject: Boolean = false,
    val hideDisplayName: Boolean = false,
)

/**
 * "Permit this user to have agent access, or withdraw it."
 *
 * Sets the **permission**, never the user's own switch. The two are different
 * columns answered by different people, and this route can only ever reach the
 * admin's one — see the server's Users.sq.
 *
 * Names the desired state rather than saying "toggle", for [McpEnabledRequest]'s
 * reason — a retry says the same thing, and two admins with the dialog open cannot
 * flip one account's access back and forth by both clicking once.
 *
 * Note what this does not say: who is asking. That comes from the session cookie,
 * server-side, on every request. A field for it would be the authorization system
 * asking the caller to authorize themselves. [RoleGrant] is the same shape for the
 * same reason.
 *
 * @property userId whose permission to change. In the body rather than the path
 *   because it is the subject of the sentence, not a place — and because a route
 *   with the id in the path would invite a second one without it that meant
 *   "mine", which is [ApiRoutes.MCP_ENABLED] and is a different flag entirely.
 * @property isAllowed the state to move to.
 */
@Serializable
data class UserMcpAccess(
    val userId: Long,
    val isAllowed: Boolean,
)
