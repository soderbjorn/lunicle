/**
 * Who can see a project — as a *set*, rather than as a yes about one person.
 *
 * ── Why this exists at all ──────────────────────────────────────────────────
 *
 * [AccessControl.canReadProject] answers "may this caller read that project?", one
 * pair at a time, and it is the only thing that should ever answer it. But
 * features need the same rule turned around: a picker that offers "everyone who
 * can see this project" cannot be built out of `canReadProject`, because that
 * function answers about a request and a picker is a question about a project.
 *
 * It lives here rather than on any one feature because more than one needs it and
 * the two must not answer differently. **An autocomplete that suggests somebody
 * the mailer then declines to recognise is the failure this file exists to
 * prevent** — so the rule below has to agree with [AccessControl.effectiveRole]
 * exactly, and every change to one is a change to the other.
 *
 * ── The shape of the answer, which is the whole trap ────────────────────────
 *
 * This file's preamble used to warn about two wrong implementations, and both
 * warnings survive the permission rework with their reasoning intact — only the
 * spelling changed.
 *
 * **Wrong once: a filter over every account, asking the permission check per row.**
 * That is a query per account on the instance to compute an answer that two or
 * three queries already know. It was wrong when the question was `isMember` and it
 * is wrong now that the question is `effectiveRole`; if anything more so, since
 * that one reads two tables. So this reads the audience rows once, the own-row
 * holders once, and the accounts once, and decides in memory.
 *
 * **Wrong twice: assuming the answer is the project's members.** Under the old
 * model a public project's readers were not its members, and the branch that
 * forgot it returned a set that was far too small. Under audiences the same trap
 * has a new face: a project's *own rows* are not its audience either. A project
 * that admits every member as a Contributor may have no own rows at all, and a
 * reader who asked `memberIds` alone would find nobody in a room everybody is in.
 * So both routes in are read, and the answer is their union.
 *
 * ── What it deliberately is not ─────────────────────────────────────────────
 *
 * Not a permission check, and not reachable from one. Nothing in [AccessControl]
 * calls this, and nothing should: a decision about a caller is made from the
 * session, one user at a time, and a set built for a dropdown is not an
 * authorisation. This answers "who would be in the room", which is a question
 * about a project rather than about a request.
 *
 * Not signed-out visitors, either. A project with a `guest` audience row is
 * readable by somebody with no account at all, and this returns accounts — there
 * is nobody to name for the anonymous reader, no way to mention them and no
 * address to message them at. So "who can see it" here means "which accounts can
 * see it", which is the only reading a recipient list can have. That is why the
 * bar below is **Member or above**: [InstanceRole.GUEST] is the absence of an
 * account, so no row in `users` is ever below it.
 *
 * @see AccessControl.canReadProject
 * @see AccessControl.effectiveRole for the rule this must keep agreeing with.
 */
package se.soderbjorn.lunicle

/**
 * Turns "can see this project" into a list of accounts.
 *
 * @param users the account table; the answer is always a subset of it.
 * @param roles asked two questions per project — which audiences it admits, and
 *   who holds an own row in it.
 * @param instanceSettings asked once per call, for two answers: who owns the
 *   deployment, and whether public projects are allowed. The first is here for the
 *   same reason [AccessControl] has it — ownership is a setting rather than a
 *   column, so no [UserRecord] carries it — and it is here at all because leaving
 *   it out is precisely the disagreement this file exists to prevent: the owner
 *   reads every project, so a picker that omitted them would omit somebody the
 *   mailer recognises. The second is the publish veto (LNL-203), which silences a
 *   guest row and so **narrows** this set: the same disagreement in the other
 *   direction, a picker naming every account on the instance for a board where the
 *   row that named them grants nothing.
 */
class ProjectAudience(
    private val users: se.soderbjorn.lunicle.store.UserStore,
    private val roles: se.soderbjorn.lunicle.store.RoleStore,
    private val instanceSettings: se.soderbjorn.lunicle.store.InstanceSettingsStore,
) {
    /**
     * Every account that can see [project].
     *
     * @param project the resolved record rather than an id, kept that way even
     *   though visibility is no longer a field on it: every caller has already
     *   fetched the project in order to run [AccessControl.canReadProject] against
     *   it, and taking an id would mean a second read of a row the caller is
     *   holding.
     * @return the records, sorted by name — [se.soderbjorn.lunicle.store.UserStore.selectAll]'s
     *   order, which the filter preserves. Deliberately [UserRecord]s rather than a
     *   wire type: these carry e-mail addresses, and narrowing to a name and an id
     *   is the route's job.
     *
     *   Instance administrators are always in the set, and so is the owner, including
     *   for a project they hold nothing in — matching [AccessControl.effectiveRole],
     *   which gives them [ProjectRole.OWNER] everywhere. Leaving either out would
     *   produce a picker that cannot name the one person who can definitely see the
     *   thing.
     */
    suspend fun forProject(project: ProjectRecord): List<UserRecord> = forProjects(listOf(project))

    /**
     * Every account that can see **any** of [projects], each listed once.
     *
     * The union: "people who can see a project you can see" is a sentence about a
     * set of projects, not one project, and the caller has already narrowed
     * [projects] to the ones it may ask about.
     *
     * Note there is no short-circuit for a wide-open project any more, and none is
     * needed: the widest audience across every project collapses to a single
     * comparison below, so one `guest` row *in effect* in the list makes the whole
     * union every account without any special case to write — which is what the old
     * `if (any { isPublic }) return all` was doing by hand. "In effect" is carrying
     * weight there since LNL-203: a guest row on a deployment that forbids public
     * projects is skipped, because it admits nobody.
     */
    suspend fun forProjects(projects: Collection<ProjectRecord>): List<UserRecord> {
        val all = users.selectAll()

        // The lowest rung on the INSTANCE ladder that any of these projects admits.
        // Because the ladder ascends and the audiences are its bottom three, the
        // whole "does this account match any audience row of any of these projects"
        // question is `their rank >= this`. A `guest` row makes it GUEST, which every
        // account clears; no row anywhere leaves it null, which nobody clears.
        var widestRank: Int? = null
        // Everyone with an own row in any of them — the other route in, and the one
        // an audience-only reading would miss.
        val ownRowHolders = mutableSetOf<Long>()
        // Read once, before the loop, for two things: who owns the deployment (LNL-201) and
        // whether public projects are allowed (LNL-203). The second is why the guest key is
        // skipped below rather than trusted — a guest row grants nothing while the switch is
        // off, so a set built off the stored key alone would put every account on the
        // instance into a picker for a board none of them reaches through that row. "Every
        // change to effectiveRole is a change to this" is this file's standing rule.
        val switches = instanceSettings.current()
        projects.forEach { project ->
            roles.audienceRoles(project.id).keys
                .filter { switches.allowPublicProjects || it != Audience.GUEST }
                .forEach { audience ->
                    val rank = audience.instanceRole.rank
                    if (widestRank == null || rank < widestRank!!) widestRank = rank
                }
            ownRowHolders += roles.memberIds(project.id)
        }

        val admitted = widestRank
        // Folded into each account's rung by the one named function that does it (LNL-201).
        // It was three clauses here — an administrator, then the owner separately, then the
        // audience match off the *stored* row — which answered correctly only because the
        // owner's own clause sat in front of the one that could not see them. One ladder per
        // account says the same thing once.
        val ownerUserId = switches.ownerUserId
        return all.filter { user ->
            val instanceRole = user.instanceRoleWith(ownerUserId)
            instanceRole.atLeast(InstanceRole.ADMIN) ||
                (admitted != null && instanceRole.rank >= admitted) ||
                user.id in ownRowHolders
        }
    }
}
