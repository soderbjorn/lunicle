/**
 * Who can see a project — as a *set*, rather than as a yes about one person.
 *
 * ── Why this exists at all ──────────────────────────────────────────────────
 *
 * [AccessControl.canReadProject] answers "may this caller read that project?",
 * one pair at a time, and it is the only thing that should ever answer it. But
 * two features need the same rule turned around: the forum's `@` autocomplete
 * offers "everyone who can see this project" (LNL-61), and the private-message
 * recipient picker offers the union of that across every project the sender can
 * see (LNL-60). Neither can be built out of `canReadProject`, and LNL-57's
 * comment on LNL-61 said as much when it landed: the query did not exist.
 *
 * It lives here rather than on either feature because both need it and the two
 * must not answer differently. An autocomplete that suggests a name the mailer
 * then declines to recognise is the failure that motivated `mentionableUsersIn`
 * being shared between the editor and the notifier; this is the same hazard with
 * a wider set.
 *
 * ── The shape of the answer, which is the whole trap ────────────────────────
 *
 * **For a public project, the answer is every account on the instance.** That is
 * not an edge case to handle afterwards — it is the common case, since every
 * project on this deployment is public today. The obvious implementation, a
 * filter over every user asking [RoleStore.isMember] per row, is therefore wrong
 * twice: it is a query per account to compute an answer that needs none, and on a
 * public project it computes the wrong answer entirely, since a public project's
 * readers are not its members.
 *
 * So the public branch does not look at `project_roles` at all, and the private
 * branch asks [RoleStore.memberIds] — one select for the whole membership.
 *
 * ── What it deliberately is not ─────────────────────────────────────────────
 *
 * Not a permission check, and not reachable from one. Nothing in [AccessControl]
 * calls this, and nothing should: a decision about a caller is made from the
 * session, one user at a time, and a set built for a dropdown is not an
 * authorisation. This answers "who would be in the room", which is a question
 * about a project rather than about a request. `RoleStore.grantsForProject`
 * carries the same warning for the same reason.
 *
 * Not signed-out visitors, either. A public project is readable by somebody with
 * no account at all, and this returns accounts — there is nobody to name for the
 * anonymous reader, no way to mention them and no address to message them at. So
 * "who can see it" here means "which accounts can see it", which is the only
 * reading a recipient list can have.
 *
 * @see AccessControl.canReadProject
 * @see mentionableUsersIn for the *narrower* set the issue tracker uses, and why
 *   the two are deliberately different.
 */
package se.soderbjorn.lunicle

/**
 * Turns "can see this project" into a list of accounts.
 *
 * @param users the account table; the answer is always a subset of it, and for a
 *   public project it is the whole of it.
 * @param roles asked one question — who holds anything in this project — and only
 *   for a project that is not public.
 */
class ProjectAudience(
    private val users: se.soderbjorn.lunicle.store.UserStore,
    private val roles: se.soderbjorn.lunicle.store.RoleStore,
) {
    /**
     * Every account that can see [project].
     *
     * @param project the resolved record rather than an id, deliberately: the
     *   answer turns on `is_public`, and every caller has already fetched the
     *   project in order to run [AccessControl.canReadProject] against it. Taking
     *   an id would mean a second read of a row the caller is holding, and would
     *   hide the one field the result actually depends on from the call site.
     * @return the records, sorted by name — [UserStore.selectAll]'s order, which
     *   both filters below preserve. Deliberately [UserRecord]s rather than a wire
     *   type: these carry e-mail addresses, and narrowing to a name and an id is
     *   the route's job, exactly as with [assignableUsers] and [mentionableUsers].
     *
     *   System administrators are always in the set, including for a private
     *   project they hold no role in — matching [AccessControl.canReadProject],
     *   which lets them read everything. Leaving them out would produce an
     *   autocomplete that cannot name the one person who can definitely see the
     *   thread.
     */
    suspend fun forProject(project: ProjectRecord): List<UserRecord> {
        val all = users.selectAll()
        // A public project is readable by anyone with an account — and by anyone
        // without one, who is not an account and so is not here. A project visible
        // to all signed-in accounts (LNL-138) has the same answer: every account can
        // see it, so the audience is every account. Both short-circuit without a
        // membership read, because `project_roles` has nothing to say about a project
        // whose answer does not depend on it. This mirrors canReadProject, which ORs
        // the same two flags — the two must not disagree; see the class preamble.
        if (project.isPublic || project.visibleToAllSignedIn) return all
        val members = roles.memberIds(project.id)
        return all.filter { it.isSysAdmin || it.id in members }
    }

    /**
     * Every account that can see **any** of [projects], each listed once.
     *
     * The union, for LNL-60's recipient picker: "people who can see a project you
     * can see" is a sentence about a set of projects, not one project, and the
     * caller has already narrowed [projects] to the ones it may ask about.
     *
     * Short-circuits on the first public project, which is not an optimisation so
     * much as the correct answer arriving early — one public project in the list
     * makes the union every account on the instance, and reading membership for
     * the rest could only add people who are already in it.
     */
    suspend fun forProjects(projects: Collection<ProjectRecord>): List<UserRecord> {
        val all = users.selectAll()
        // One project readable by everyone — public, or visible to all signed-in
        // accounts (LNL-138) — makes the union every account on the instance, so the
        // rest need not be read. Mirrors canReadProject's two-flag OR.
        if (projects.any { it.isPublic || it.visibleToAllSignedIn }) return all
        val members = buildSet {
            projects.forEach { addAll(roles.memberIds(it.id)) }
        }
        return all.filter { it.isSysAdmin || it.id in members }
    }
}
