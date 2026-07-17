/**
 * The only thing that answers a permission question.
 *
 * ── The client is a renderer of permissions, never a source of them ──────────
 *
 * Everything the browser knows about what it may do — `isAdmin` on the wire, a
 * greyed-out delete button, a card that will not drag — is an **affordance**: it
 * exists so a user is not invited to do something that will fail. None of it is
 * enforcement. The bundle is JavaScript on someone else's machine; every flag in
 * it is editable, every disabled button is re-enablable from the console, and
 * every request it declines to send can be sent by hand with `curl`. A
 * permission that lives in the client is not a permission.
 *
 * So every mutating route re-derives the answer here, from the session, before
 * it touches a store — never from anything the request body claims. A client
 * that lies to itself gets a 403; it does not get a deleted project.
 *
 * ── One place, one function ─────────────────────────────────────────────────
 *
 * The rules are small enough to fit in one class, and that is precisely why they
 * should not be scattered across route handlers: a check that exists in six
 * places is a check that is missing from the seventh. Every function below takes
 * the caller as a [UserRecord] resolved from the session cookie, and there is no
 * overload that takes a user id — passing one would mean some caller had a user
 * id that did not come from a session, which is the bug this shape prevents.
 *
 * @see Roles
 * @see projectRoutes
 */
package se.soderbjorn.lunicle

/**
 * Answers "may this caller do that?", and nothing else.
 *
 * @param roles the role table; the only collaborator, because the only fact
 *   these rules need that isn't already on the [UserRecord] is which roles
 *   someone holds where.
 */
class AccessControl(
    private val roles: RoleStore,
) {
    // ── Reading ──────────────────────────────────────────────────────────────

    /**
     * May [user] read [project]?
     *
     * The one rule that says yes to a caller with no session at all — which is
     * exactly what `is_public` is for. Note that this is also the *only* thing
     * standing between a private project and a signed-out visitor, so every
     * read route runs it, including the ones that feel too small to matter.
     *
     * "Signed in" is deliberately enough for a private project: this instance's
     * users are a known set (they had to be granted a role to do anything), and
     * per-project read grants are not in the MVP. If reading ever needs to be
     * narrower than "has an account", this is the line that changes.
     */
    fun canReadProject(user: UserRecord?, project: ProjectRecord): Boolean =
        project.isPublic || user != null

    // ── Projects ─────────────────────────────────────────────────────────────

    /**
     * May [user] create, rename, re-configure or delete projects?
     *
     * All four are one rule — admin — so they are one function rather than four
     * that could drift apart. The three seeded roles are all issue-scoped, so
     * they have nothing to say about projects, which leaves admin as the only
     * answer the schema can give.
     *
     * **"Re-configure" is load-bearing, and covers more than the name.** Editing a
     * project's vocabularies and granting other people roles in it both come
     * through here — see `projectSettingsRoutes` — rather than through rules of
     * their own. Two reasons, and the second is the one that matters:
     *
     *  - There is nothing to say. The role vocabulary is issue-scoped; no seeded
     *    role has an opinion about who may rename a status, so a separate
     *    `canEditVocabulary` would be this function with a different name.
     *  - A separate rule is a rule that can drift. "Only an admin may configure a
     *    project" is one sentence, and it should be one line — otherwise the day
     *    someone widens project editing is the day granting privileges quietly
     *    stays admin-only, or worse, does not.
     *
     * Note what this being one function does NOT mean: every route still asks.
     * Sharing the answer is not sharing the check. See this file's preamble.
     *
     * This is the decision most likely to be revisited, and it is one line.
     */
    fun canMutateProjects(user: UserRecord?): Boolean = user?.isAdmin == true

    // ── Issues ───────────────────────────────────────────────────────────────

    /** May [user] file an issue in [projectId]? */
    suspend fun canCreateIssue(user: UserRecord?, projectId: Long): Boolean =
        user != null && (user.isAdmin || roles.hasRole(user.id, projectId, Role.CREATE_ISSUE))

    /**
     * May [user] change [issue]?
     *
     * Three ways to yes: you are the admin, you wrote it, or you hold
     * `change_unowned_issues` in its project.
     *
     * **Drag-and-drop is not a special case, and must not become one.** Moving a
     * card between columns is a `status_id` write on an issue, so it comes
     * through here — the same function the editor uses. Worth stating because a
     * board invites a shortcut: a "just move it" endpoint that skips the check
     * because dragging feels lighter than editing. It is not lighter. It is the
     * same write, and an unchecked one would let anyone drag anything to Closed.
     */
    suspend fun canEditIssue(user: UserRecord?, issue: IssueRecord): Boolean =
        user != null && (
            user.isAdmin ||
                issue.author == Author.Account(user.id) ||
                roles.hasRole(user.id, issue.projectId, Role.CHANGE_UNOWNED_ISSUES)
            )

    /**
     * May [user] delete [issue]?
     *
     * The same rule as editing, on purpose: the delete button lives inside the
     * issue modal, so anyone who can open that modal in edit mode can already
     * empty the issue of everything it said. Making deletion stricter would
     * protect the row and not its contents, which is ceremony rather than
     * security. Its own function anyway, so that changing one's mind later is
     * an edit here rather than a hunt through the routes.
     */
    suspend fun canDeleteIssue(user: UserRecord?, issue: IssueRecord): Boolean =
        canEditIssue(user, issue)

    // ── Backfilling ──────────────────────────────────────────────────────────

    /**
     * May [user] write a row that claims a different author, or a time other than
     * now?
     *
     * ── A deliberate, narrow exception, and it is named here so it is visible ──
     *
     * §3 of the MCP plan settles two things this rubs against: there is **one flat
     * `mcp` scope**, and **admin operations are not exposed over MCP at all**. The
     * instinct behind both is right and this does not overturn it — importing a
     * tracker's history is the one job that cannot be done by "the same rights you
     * have in the web app", because the web app has no way to say "Ada wrote this,
     * in 2019" either. It is not a capability an agent gained; it is one nobody had.
     *
     * The exception is kept as small as it can be: three optional parameters —
     * `author`, `author_external` and `created_at` — on the three tools that
     * create things, no scope of its own, and no way to reach it that does not
     * come through here. There is deliberately no backfill for *editing* — an
     * existing issue's author and timestamps are not rewritable by anyone, agent
     * or not.
     *
     * This paragraph used to say "no tool of its own", and `start_attachment_upload`
     * is one, so the claim is retired rather than quietly left standing. What it
     * was protecting still holds and is worth saying precisely: that tool is not
     * an admin tool. Anyone who may edit an issue may attach a file to it, exactly
     * as in the web app; only attaching one *as somebody else* comes through here.
     * A tool existing is not the thing that was dangerous — a capability nobody
     * had outside this check would be, and there still is not one.
     *
     * ── Why it is admin, and why it is here rather than in McpTools ────────────
     *
     * Admin, because writing under someone else's name is indistinguishable from
     * them having written it, forever. No seeded role says anything about
     * authorship — they are all issue-scoped and this is a fact about the whole
     * instance — so admin is the only answer the schema can give, exactly as with
     * [canMutateProjects].
     *
     * Here rather than inline in [McpTools], because this file's preamble is the
     * whole reason: a permission answered where it is used is a permission that
     * gets answered differently the second time somebody needs it. The caller is
     * a [UserRecord] like every other function here — resolved from the *token*,
     * server-side, never from anything the tool arguments claim. An agent is an
     * untrusted caller that happens to be authenticated.
     *
     * Note what this is NOT: impersonation. An impersonating admin's effective
     * user is an ordinary user with ordinary rights — see [Caller] — so a caller
     * reaching this function while impersonating gets `false`, which is correct
     * and is why the two mechanisms must not be wired together. A token never
     * impersonates at all; see McpServer's `resolveMcpUser`.
     */
    fun canAttributeWrites(user: UserRecord?): Boolean = user?.isAdmin == true

    // ── Comments ─────────────────────────────────────────────────────────────

    /** May [user] comment on an issue in [projectId]? */
    suspend fun canComment(user: UserRecord?, projectId: Long): Boolean =
        user != null && (user.isAdmin || roles.hasRole(user.id, projectId, Role.COMMENT_ON_ISSUE))

    /**
     * May [user] change or delete [comment]?
     *
     * Authorship, not the comment role: `comment_on_issue` grants writing your
     * own, never editing someone else's words. Admin overrides, as everywhere.
     * A comment whose author is deleted is admin-only, which is correct.
     *
     * An imported comment is admin-only too, and falls out of the same line
     * rather than needing a case: an [Author.External] is a name, and a name is
     * never equal to an account. Nobody can inherit an imported comment by
     * happening to share the name it was filed under.
     */
    fun canEditComment(user: UserRecord?, comment: CommentRecord): Boolean =
        user != null && (user.isAdmin || comment.author == Author.Account(user.id))

    // ── Affordances ──────────────────────────────────────────────────────────

    /**
     * What the client should render as available in [projectId].
     *
     * Read this as: the answers the server would give, sent ahead of time so the
     * browser does not have to invite a user to do something that will 403. It
     * is not a grant. Every route recomputes its own answer from the session,
     * so a client that edits this object gets nothing but a 403 with extra
     * steps. See this file's preamble.
     */
    suspend fun permissionsFor(user: UserRecord?, projectId: Long): ProjectPermissions {
        if (user == null) return ProjectPermissions()
        // One query for every role, rather than one per question: the three
        // hasRole() calls below would otherwise be three round-trips to answer
        // what a single select already knows.
        val held = roles.rolesFor(user.id, projectId)
        return ProjectPermissions(
            canCreateIssue = user.isAdmin || Role.CREATE_ISSUE in held,
            canComment = user.isAdmin || Role.COMMENT_ON_ISSUE in held,
            canChangeUnownedIssues = user.isAdmin || Role.CHANGE_UNOWNED_ISSUES in held,
            canMutateProject = user.isAdmin,
        )
    }
}

/**
 * The affordances for one project, as computed by [AccessControl.permissionsFor].
 *
 * Server-side twin of the wire type; converted at the route. Defaults are all
 * false, so a signed-out caller and a bug that forgets to fill this in produce
 * the same, safe, UI.
 */
data class ProjectPermissions(
    val canCreateIssue: Boolean = false,
    val canComment: Boolean = false,
    val canChangeUnownedIssues: Boolean = false,
    val canMutateProject: Boolean = false,
)
