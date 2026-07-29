/**
 * Moving "hide issue numbers" from every person to every project (LNL-194).
 *
 * The switch was a per-user preference, stored inside each account's `ui_settings`
 * PROJECT_PREFS blob under the project's id. It describes how a *shared* board
 * reads, so it moved onto the project row and became an administrator's switch
 * beside `show_issue_author`. This is the one-way trip of the old values.
 *
 * ── Whose answer wins, and why it is stated rather than clever ───────────────
 *
 * **The owner's.** Not a majority of the project's readers, not the loudest, not
 * "on if anybody had it on". A majority is defensible right up until somebody asks
 * why their board changed, at which point the answer is a tally nobody can see and
 * nobody voted in; the owner is arbitrary and *legible* — one person, nameable, who
 * could have changed it themselves. Defensible-and-surprising is worse than
 * arbitrary-and-stated, so this is arbitrary and this paragraph is the statement.
 *
 * Where there is no owner to ask — no `owner` rung, or an owner who never set the
 * preference — the project lands on the default, which is numbers shown: the state
 * every board had before the preference existed.
 *
 * ── Why it is a startup pass and not in 34.sqm ───────────────────────────────
 *
 * Two reasons, either of which would be enough. The value lives inside a JSON blob
 * the migration would have to parse. And "who owns this project" is a
 * `project_roles` read with an `instance_settings` fallback — the same reason
 * [stampUserKinds] is not in a migration: a migration that guessed would be a
 * migration that decided. This also makes it work identically on Firestore, which
 * has no migration file to put it in at all.
 *
 * ── Idempotent by construction, and interruptible ───────────────────────────
 *
 * There is no marker and no resume state. A project is copied into only while its
 * `hide_issue_numbers` is **null**, which is the "nobody has decided yet" state
 * 34.sqm leaves every migrated row in; writing anything at all — this pass, or an
 * administrator flipping the switch — takes it out of that state permanently. So
 * the second boot copies nothing, an interrupted boot is finished by the next one,
 * and an administrator's later change is never reverted by a stale preference.
 * [stampUserKinds]' property, reached a different way: it is idempotent because
 * re-running produces the same answer, this is idempotent because re-running is
 * skipped.
 *
 * The stale field is left in each account's blob rather than being rewritten out.
 * [UserProjectPrefs] no longer declares it, `decode` ignores unknown keys and
 * `encode` does not emit them, so the next time any user changes any board
 * preference their blob loses the field on the way through — which drops it for the
 * only readers that exist without a pass over every account on the instance to
 * rewrite JSON nobody reads. The rows are dead the moment this pass runs; they are
 * collected lazily.
 *
 * @see stampUserKinds for the shape this follows
 * @see se.soderbjorn.lunicle.ProjectRecord.hideIssueNumbersStored
 */
package se.soderbjorn.lunicle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.lunicle.clientserver.UiSettingKeys
import se.soderbjorn.lunicle.store.InstanceSettingsStore
import se.soderbjorn.lunicle.store.ProjectStore
import se.soderbjorn.lunicle.store.RoleStore
import se.soderbjorn.lunicle.store.UiSettingsStore

/**
 * Give every undecided project the board-display answer its owner used to carry.
 *
 * @return how many projects were settled, for the startup log — 0 on every boot
 *   after the first.
 */
suspend fun copyBoardDisplayFromOwners(
    projects: ProjectStore,
    roles: RoleStore,
    users: se.soderbjorn.lunicle.store.UserStore,
    uiSettings: UiSettingsStore,
    instanceSettings: InstanceSettingsStore,
): Int {
    val undecided = projects.selectAll().filter { it.hideIssueNumbersStored == null }
    if (undecided.isEmpty()) return 0

    // Read once for the whole pass rather than per project: the fallback owner is the
    // same account for every board, and a settings read per project would be a query
    // to learn a value that cannot change mid-boot.
    val instanceOwnerId = instanceSettings.current().ownerUserId
    // Memoised per account, because one person owning several boards is the ordinary
    // case on a small instance and their blob holds every project's answer anyway.
    val blobs = mutableMapOf<Long, Map<Long, Boolean>>()

    var settled = 0
    undecided.forEach { project ->
        val ownerId = ownerOf(project.id, roles, users, instanceOwnerId)
        val hide = ownerId
            ?.let { id -> blobs.getOrPut(id) { legacyHideIssueNumbers(uiSettings.forUser(id)) } }
            ?.get(project.id)
            ?: false
        projects.setBoardDisplay(project.id, project.showIssueAuthor, hide)
        settled++
    }
    return settled
}

/**
 * Whose preference to copy: this project's owner, else whoever owns the deployment.
 *
 * The lowest id among several owners, which is a tie-break rather than a decision —
 * a project with two owners has no first one, and picking the earliest at least
 * gives the same answer on a re-run. An owner whose account has since been deleted
 * falls through to the instance owner, and an instance with neither leaves the
 * project on the default.
 */
private suspend fun ownerOf(
    projectId: Long,
    roles: RoleStore,
    users: se.soderbjorn.lunicle.store.UserStore,
    instanceOwnerId: Long?,
): Long? {
    val owners = roles.rolesForProject(projectId)
        .filterValues { it == ProjectRole.OWNER }
        .keys
    val seated = owners.filter { users.findById(it) != null }.minOrNull()
    return seated ?: instanceOwnerId
}

/**
 * Pull `hideIssueNumbers` out of one account's stored PROJECT_PREFS blob, by project.
 *
 * Reads the JSON by hand rather than through [UserProjectPrefs], and that is the
 * point: the field is **gone** from that type, because leaving it there so a
 * migration could read it would leave it there for the client to keep writing. So
 * this is a reader for a shape that no longer exists anywhere else — the whole
 * reason it is private to this file, and the whole reason it is written against
 * [JsonObject] rather than a `@Serializable` copy of the retired record.
 *
 * Total, like [UserProjectPreferences.decode]: anything unparseable yields no
 * preferences at all, which lands the project on the default. A blob this code
 * cannot read must not fail a boot.
 */
private fun legacyHideIssueNumbers(settings: Map<String, String>): Map<Long, Boolean> {
    val blob = settings[UiSettingKeys.PROJECT_PREFS]?.takeIf { it.isNotBlank() } ?: return emptyMap()
    return runCatching {
        val byProject = Json.parseToJsonElement(blob).jsonObject["byProject"]?.jsonObject
            ?: return@runCatching emptyMap<Long, Boolean>()
        byProject.entries.mapNotNull { (key, value) ->
            val id = key.toLongOrNull() ?: return@mapNotNull null
            val hide = value.jsonObject["hideIssueNumbers"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                ?: return@mapNotNull null
            id to hide
        }.toMap()
    }.getOrDefault(emptyMap())
}
