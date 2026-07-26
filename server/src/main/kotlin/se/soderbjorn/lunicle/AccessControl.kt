/**
 * The only thing that answers a permission question.
 *
 * ── The client is a renderer of permissions, never a source of them ──────────
 *
 * Everything the browser knows about what it may do — `isSysAdmin` on the wire, a
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
    private val roles: se.soderbjorn.lunicle.store.RoleStore,
) {
    // ── Reading ──────────────────────────────────────────────────────────────

    /**
     * May [user] read [project]?
     *
     * This is the line the old doc reserved. It used to read `isPublic || user
     * != null`, on the argument that this instance's users are a known set and
     * per-project read grants were not in the MVP, and it ended: *"If reading
     * ever needs to be narrower than 'has an account', this is the line that
     * changes."* LNL-57 is that day. Private messages are addressed to "people
     * who can see a project you can see" (LNL-30), and a rule that says everyone
     * can see everything makes that sentence mean nothing.
     *
     * **Membership is "holds any role here", not "holds [Role.VIEW_PROJECT]".**
     * The narrower reading would have been the obvious one and it is wrong twice
     * over: it leaves someone with `create_issue` unable to see the project they
     * may file in — an incoherence that predates this change and is fixed by it
     * — and it makes every grant a two-box operation, where the second box is
     * the one that gets forgotten. `VIEW_PROJECT` is what a read-only member
     * holds, not what membership is.
     *
     * `is_public` is untouched and means exactly what it always did: yes to a
     * caller with no session at all. That is still the only rule here that says
     * yes to a stranger, which is why every read route runs this one, including
     * the ones that feel too small to matter.
     *
     * **The middle tier: `visible_to_all_signed_in` (LNL-138).** A project may be
     * readable by *any account* without being readable by a stranger — the tier
     * between members-only and public. It is added as an `||` clause here and
     * nowhere else, because reading is the only thing it grants: every write gate
     * below stays membership-scoped, so a project turned on here is genuinely
     * read-only to the signed-in users it newly admits. It ORs with `is_public`, so
     * a public project already satisfies it and the flag only ever *widens* who may
     * read — see [readableAsMemberOrPublic], the pre-tier rule the write side keeps.
     *
     * Suspend now, as [canAdministerProject] already was, because membership is
     * a row and not a field. The call sites inside `.filter { }` are fine —
     * `filter` is `inline`, so the lambda body compiles in its caller's suspend
     * context.
     *
     * @see Role.VIEW_PROJECT
     */
    suspend fun canReadProject(user: UserRecord?, project: ProjectRecord): Boolean =
        readableAsMemberOrPublic(user, project) || (user != null && project.visibleToAllSignedIn)

    /**
     * The read rule *before* the signed-in-visibility tier (LNL-138): public to a
     * stranger, or a signed-in member (or system administrator).
     *
     * Its own function because two questions need exactly this and not the widened
     * [canReadProject]: the write side, where [canPostInProject] must stay narrow so
     * the new tier grants reading only, and [canReadProject] itself, which ORs the
     * middle tier on top. Keeping the pre-tier rule in one place is what stops the
     * read gate and the forum-write gate from drifting apart — the failure the
     * class preamble exists to prevent.
     */
    private suspend fun readableAsMemberOrPublic(user: UserRecord?, project: ProjectRecord): Boolean =
        project.isPublic || (user != null && (user.isSysAdmin || roles.isMember(user.id, project.id)))

    // ── Projects ─────────────────────────────────────────────────────────────

    /**
     * May [user] bring a *new* project into existence, or manage the set of
     * projects from instance settings?
     *
     * System administrator only, and note how much narrower this is than the
     * sentence it used to carry. It once meant "create, rename, re-configure or
     * delete", where "re-configure" swept in the vocabularies and the role
     * grants — everything the settings dialog does. Those moved to
     * [canAdministerProject] when the project administrator arrived (LNL-37); then
     * renaming, deleting and repository configuration — a *specific* project's
     * existence and identity — moved to [canOwnProject] when the project owner
     * arrived (LNL-107), because they are the per-project questions that role
     * exists to answer.
     *
     * What is left is deliberately the *instance's* business and has no project to
     * be scoped to: **creating** a project (there is nothing to own before it
     * exists), and the instance-settings surface that reorders and deletes across
     * every board at once (see [AdminRoutes]). A board's owner settles their own
     * board; only the person who runs the deployment settles which boards there
     * are. That is why creating did not move with deleting, though the two once
     * shared this line.
     *
     * Note what sharing an answer does NOT mean: every route still asks. See
     * this file's preamble.
     */
    fun canMutateProjects(user: UserRecord?): Boolean = user?.isSysAdmin == true

    /**
     * May [user] bring a *new* project into existence?
     *
     * The narrow half of [canMutateProjects] — creating, and only creating — split
     * out because LNL-115 gave it a second answer. It used to be exactly
     * `canMutateProjects`: a system administrator, nobody else. It still is when the
     * instance leaves the switch off, which is the default and every deployment
     * before this; when an administrator turns "anyone can create a project" on,
     * [anyoneMayCreate] is true and any signed-in user may.
     *
     * The switch is passed in rather than read here because [AccessControl] holds no
     * store but the role table (see this class's constructor doc), and threading the
     * instance-settings store through every one of its two-dozen construction sites
     * to answer one question would be the wrong trade. The route reads the switch
     * from `deps.instanceSettings` and hands it in; the *rule* — signed in, and
     * either an admin or the switch is on — still lives here, which is the invariant
     * this file exists to keep.
     *
     * Note the deliberate asymmetry with [canMutateProjects]: this widens
     * *creating* only. Reordering and deleting across the instance stay an
     * administrator's, because "make a board" and "remove somebody's board" are not
     * the same trust. See [AdminRoutes] for those.
     */
    fun canCreateProject(user: UserRecord?, anyoneMayCreate: Boolean): Boolean =
        user != null && (user.isSysAdmin || anyoneMayCreate)

    /**
     * May [user] own [projectId] — rename it, re-prefix it, change its visibility,
     * configure its repository, delete it, and promote others to run or own it?
     *
     * The per-project half of what used to be [canMutateProjects], and the one
     * [Role.PROJECT_OWNER] was added to answer (LNL-107). Same `isSysAdmin ||`
     * shape as every rule here: a system administrator owns every project without
     * holding a row, exactly as they administer every project.
     *
     * **Strictly senior to [canAdministerProject], never a peer of it.** An
     * administrator runs the board — its vocabulary, its sprints, its issue-scoped
     * grants — and an owner does all of that (owning implies administering, see
     * [administers]) *and* the things that are about the board's identity and
     * existence rather than its day-to-day. The split is the same one LNL-37 drew
     * between administering and the identity fields; LNL-107 is what finally gave
     * those fields a per-project holder instead of reserving them to the instance.
     */
    suspend fun canOwnProject(user: UserRecord?, projectId: Long): Boolean =
        user != null && (user.isSysAdmin || roles.hasRole(user.id, projectId, Role.PROJECT_OWNER))

    /**
     * May [user] administer [projectId] — its vocabulary, its sprints, and who
     * holds what in it?
     *
     * The per-project administrative question, and the one [Role.PROJECT_ADMIN]
     * was added to answer. Same `isSysAdmin ||` shape as every other rule here:
     * a system administrator administers every project without holding a row.
     *
     * **Wholesale, not carved up.** This one function covers the statuses,
     * priorities, resolutions, labels and components, the sprint lifecycle, and
     * granting the issue-scoped roles. Sprints are *created* through the
     * vocabulary routes, so separating "may run sprints" from "may edit the
     * vocabulary" would mean carving one route set in half — more code, to draw
     * a line nobody asked for. "Administers this project" is one sentence and it
     * should be one line, for the reason the old [canMutateProjects] doc gave
     * about rules that drift.
     */
    suspend fun canAdministerProject(user: UserRecord?, projectId: Long): Boolean =
        user != null && (user.isSysAdmin || administers(user.id, projectId))

    /**
     * May [user] grant or revoke [role] in [projectId]?
     *
     * Three tiers, and the split is the point:
     *
     *  - The **issue-scoped** roles — view, create, comment, change-unowned, be-
     *    assigned — are handed out by anyone who administers the board, which is
     *    most of what running one *is*.
     *  - **[Role.PROJECT_ADMIN] and [Role.PROJECT_OWNER]** — the senior roles — are
     *    handed out only by an owner or a system administrator.
     *
     * Not symmetry for its own sake, and the second tier is a deliberate line
     * rather than an oversight. A role that can grant itself escalates: were an
     * administrator able to promote peers, one could make a second, who could make
     * a third, and nobody senior would have a say. LNL-37 named that risk and
     * deferred the decision; LNL-107 makes it, by seating the power one rung up —
     * an *owner* promotes administrators and other owners, an administrator
     * promotes neither. The owner is the project's answerable party, so the
     * escalation stops with somebody who was already trusted with the whole board.
     */
    suspend fun canGrant(user: UserRecord?, projectId: Long, role: Role): Boolean =
        when (role) {
            Role.PROJECT_ADMIN, Role.PROJECT_OWNER -> canOwnProject(user, projectId)
            else -> canAdministerProject(user, projectId)
        }

    /**
     * Does [userId] administer [projectId] by holding a role that runs it — either
     * [Role.PROJECT_ADMIN] or the senior [Role.PROJECT_OWNER]?
     *
     * The one place ownership's implication of administration lives, mirroring how
     * [holds] is the one place administration's implication of the issue roles
     * lives. Kept separate from [holds] because it answers about the two senior
     * roles specifically — [canOwnProject] and [canAdministerProject] both consult
     * it — where [holds] answers about one issue-scoped role plus this.
     */
    private suspend fun administers(userId: Long, projectId: Long): Boolean =
        roles.hasRole(userId, projectId, Role.PROJECT_ADMIN) ||
            roles.hasRole(userId, projectId, Role.PROJECT_OWNER)

    /**
     * Does [userId] hold [role] in [projectId], or something that includes it?
     *
     * The one place [Role.PROJECT_ADMIN]'s bundling lives. Every issue-scoped
     * rule below asks through here rather than calling `roles.hasRole` directly,
     * so "a project administrator may also do the ordinary things" is one line
     * that four rules share instead of four copies of `|| hasRole(PROJECT_ADMIN)`
     * — the fifth of which would be the one somebody forgets to add. It routes
     * through [administers], so an owner — who is senior to an administrator —
     * inherits the ordinary things too, by the same single line.
     *
     * Deliberately NOT applied to [canEditComment], which is about authorship
     * rather than a role, nor to anything instance-wide. Running a board does not
     * make other people's words yours.
     */
    private suspend fun holds(userId: Long, projectId: Long, role: Role): Boolean =
        roles.hasRole(userId, projectId, role) || administers(userId, projectId)

    // ── Issues ───────────────────────────────────────────────────────────────

    /** May [user] file an issue in [projectId]? */
    suspend fun canCreateIssue(user: UserRecord?, projectId: Long): Boolean =
        user != null && (user.isSysAdmin || holds(user.id, projectId, Role.CREATE_ISSUE))

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
            user.isSysAdmin ||
                issue.author == Author.Account(user.id) ||
                holds(user.id, issue.projectId, Role.CHANGE_UNOWNED_ISSUES)
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

    /**
     * May [user] be named as an assignee in [projectId]?
     *
     * ── Not a permission about the caller, and that is the whole subtlety ──────
     *
     * Every other function in this file asks "may this caller do that?". This one
     * asks "may this *person* have that done to them?", and it is asked about two
     * different people depending on the route:
     *
     *  - "Assign to me" asks it about the caller, who is also the subject. One
     *    question, one person.
     *  - The editor's Assignee dropdown asks it about the person being *chosen*,
     *    while [canEditIssue] separately gates the caller. Two questions, two
     *    people, and conflating them would be the bug: a check that only asked
     *    about the caller would let anyone who may edit an issue hand it to
     *    somebody who is not on this project at all.
     *
     * So callers must be explicit about whose id they are passing, which is why
     * this takes a [UserRecord] like everything else here rather than a bare id —
     * the record for the *subject*, resolved from the store, never from the
     * request body's claim about who they are.
     *
     * Admin always qualifies, as the issue asks and as everywhere else in this
     * file: admin short-circuits before a role is looked at.
     */
    suspend fun canBeAssigned(user: UserRecord?, projectId: Long): Boolean =
        user != null && (user.isSysAdmin || holds(user.id, projectId, Role.BE_ASSIGNED_ISSUE))

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
     * The exception is kept as small as it can be: `author`, `author_external` and
     * `created_at` on the three tools that create things, plus `updated_at` on
     * `update_issue`; no scope of its own, and no way to reach it that does not
     * come through here.
     *
     * This paragraph used to end "there is deliberately no backfill for *editing* —
     * an existing issue's author and timestamps are not rewritable by anyone", and
     * `update_issue`'s `updated_at` is a backfill for editing, so the claim is
     * retired rather than quietly left standing. It was drawn in the wrong place.
     * What it was protecting is the part that survives: an existing row's *author*
     * is still rewritable by nobody, and neither is its `created_at`. Those are
     * claims about what happened. `updated_at` is not — it is the board's sort key,
     * every edit stamps it whether the caller likes it or not, and the import that
     * must rewrite a description to point at a just-uploaded attachment had no way
     * to stop that edit from dragging a years-old issue to today. The choice was
     * never "rewritable or not"; it was "rewritable to the truth, or to the wall
     * clock". A parameter that cannot precede its own row's `created_at` and cannot
     * reach the future buys back the only history it can express.
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
    fun canAttributeWrites(user: UserRecord?): Boolean = user?.isSysAdmin == true

    // ── E-mail ───────────────────────────────────────────────────────────────

    /**
     * May an agent holding [user]'s token send [user] free-form e-mail?
     *
     * System administrator only, and this is the one function here whose subject
     * and object are the same person: the address is [UserRecord.email] on the
     * token's own account and `send_email` has no recipient parameter at all, so
     * what is being asked is not "may they reach somebody" but "may this account
     * be a place an agent puts text that leaves the building".
     *
     * That framing is why "they are only mailing themselves" was not enough on
     * its own. Every other capability on the MCP surface is one the person
     * already has in the web app, so an agent that misuses it does something its
     * user could have done and can see afterwards on the board. This one leaves
     * no row: mail that has gone has gone, it carries this deployment's sending
     * domain and reputation, and the volume is bounded by nothing but a model's
     * judgement. An instance where every account with MCP on is a live outbound
     * mail path is a deliverability problem for the whole instance, and the
     * person who owns that problem is whoever runs it — so it is theirs to opt
     * into, per account, rather than something an ordinary user's agent switches
     * on by deciding to.
     *
     * Admin, then, for the same reason as [canMutateProjects]: the question is
     * about the instance, not about one board, and no seeded [Role] can express
     * it. When somebody wants this for a named non-admin, the honest shape is a
     * per-account permission beside `isMcpAllowed` — not a project role, and not
     * this check quietly widening.
     *
     * Note what does NOT change if this is ever widened: the recipient stays out
     * of the arguments. See [McpTools]' preamble — that is the property that
     * keeps this from being a relay, and it is not the one this function is.
     */
    fun canSendAgentMail(user: UserRecord?): Boolean = user?.isSysAdmin == true

    /**
     * May [user] delete a stored attachment out of band — the `delete_attachment`
     * MCP tool?
     *
     * System administrator, like [canSendAgentMail], and for a kindred reason. The
     * web app has no standalone attachment delete at all: an attachment dies with
     * the issue, comment or post it hangs on, or is swept as an orphan at startup.
     * So this is not a capability the person already holds in the web app that the
     * agent merely reaches — it is a new, permanent, cross-project power to unlink
     * any file on the instance by its id. That is an instance-wide question no
     * seeded [Role] expresses, so it sits with whoever runs the instance, exactly
     * as agent mail does. See [McpTools.deleteAttachment].
     */
    fun canDeleteAttachment(user: UserRecord?): Boolean = user?.isSysAdmin == true

    // ── Comments ─────────────────────────────────────────────────────────────

    /** May [user] comment on an issue in [projectId]? */
    suspend fun canComment(user: UserRecord?, projectId: Long): Boolean =
        user != null && (user.isSysAdmin || holds(user.id, projectId, Role.COMMENT_ON_ISSUE))

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
        user != null && (user.isSysAdmin || comment.author == Author.Account(user.id))

    // ── Forums ───────────────────────────────────────────────────────────────

    /**
     * May [user] start a post in [project], or comment on one?
     *
     * ── Why there is no forum role, and no `canPostInForum` per forum ────────
     *
     * LNL-30 settles it in one sentence: *any signed-in user who can see the
     * project can post and comment.* So this is the read rule with the signed-out
     * case removed, and nothing else. It is deliberately NOT [Role.COMMENT_ON_ISSUE]
     * and deliberately not a new role of its own — the discussion side has one gate,
     * project visibility, and adding a second would mean somebody could read a forum
     * they may not answer in, which is a state nobody asked for and which the pane
     * has no way to explain.
     *
     * **This is [readableAsMemberOrPublic], NOT [canReadProject] (LNL-138).** The
     * two were the same until the signed-in-visibility tier arrived, and the
     * difference is the whole of that feature: LNL-138 is a *read-only* tier, so a
     * signed-in caller who can see a project only because it is
     * `visible_to_all_signed_in` may browse its forums but not post in them. That
     * does reintroduce the "read a forum you cannot answer in" state LNL-30 warned
     * of — but here it is the point rather than an oversight: the ticket asks for
     * exactly a read that carries no write, and the discussion pane is one more
     * thing the newly-admitted reader may look at without changing. A member (or a
     * fully public project) still gets LNL-30's "see it, post in it"; the middle
     * tier does not.
     *
     * Note what the `user != null` is doing, because it is the other half of the
     * difference: a public project is readable by a caller with no session at all,
     * and an anonymous post has no author to attribute it to, nobody to let delete
     * it, and no way to be answerable for. Reading anonymously is a feature;
     * writing anonymously is not.
     *
     * Per-project rather than per-forum, matching LNL-59: the design's per-forum
     * access list was replaced by project-level visibility, so there is no third
     * answer a forum could give.
     */
    suspend fun canPostInProject(user: UserRecord?, project: ProjectRecord): Boolean =
        user != null && readableAsMemberOrPublic(user, project)

    /**
     * May [user] change [author]'s post or comment — that is, publish the draft
     * they are writing, or re-save it?
     *
     * Authorship, exactly as [canEditComment] reads it, and for the same reason
     * this file has always given: a role is not authorship, and running a board
     * does not make other people's words yours. Admin overrides, as everywhere. A
     * post whose author's account is gone is admin-only, which is correct.
     *
     * **Note that a project administrator is absent here, and present in
     * [canDeleteForumContent].** That asymmetry is intentional and it is LNL-30's
     * decision rather than drift — see that function.
     */
    fun canEditForumContent(user: UserRecord?, author: Author): Boolean =
        user != null && (user.isSysAdmin || author == Author.Account(user.id))

    /**
     * May [user] delete a post or comment written by [author], in [projectId]?
     *
     * ── The one place this file's stance is deliberately relaxed ─────────────
     *
     * Three ways to yes: you are the system administrator, you wrote it, or you
     * administer this project.
     *
     * That third clause departs from [canEditComment], which has no equivalent,
     * and from the sentence in [holds] that says running a board does not make
     * other people's words yours. It is here because **LNL-30 decided it**: a
     * forum is a public room the project administrator is responsible for, and
     * moderation — removing what does not belong — is the one thing running such a
     * room actually requires. Without it, a project administrator faced with abuse
     * in their own forum has no answer but to delete the whole forum.
     *
     * It is stated here rather than left to look like an oversight, and the line
     * it draws is narrow on purpose: an administrator may **delete** somebody
     * else's post, never edit it. Deleting says "this does not belong here", which
     * is a moderator's judgement to make. Editing would let a moderator change
     * what somebody is recorded as having said, under that person's name, with no
     * history to show it happened — and forums record no history by the same
     * ticket's decision, so there would be nothing anywhere that noticed. Those
     * are different powers and only one of them was granted.
     *
     * Suspend, unlike [canEditComment], because the third clause is a row.
     */
    /**
     * May an agent holding [user]'s token reach the forums at all?
     *
     * System administrator only, and — unusually for this file — that is a
     * statement about the *transport* rather than about forums. Everything an
     * ordinary user may do in a forum they may do in the Discussion tab, and none
     * of it is gated here; what LNL-78 decided is that the **agent-driven** door
     * onto forums is an operator's tool, opened for importing and exporting a
     * project's discussion history and not for an assistant to join a
     * conversation.
     *
     * Two things make that a real narrowing rather than ceremony, and both are
     * why this is not simply [canPostInProject] asked over MCP:
     *
     *  - **A forum is a room full of other people's words.** The delete tools
     *    reach a whole forum, every post in it and every file under it in one
     *    call, and nothing in this feature records history — see ForumPosts.sq —
     *    so there is no trail afterwards saying what a confused agent removed.
     *    The issue side can afford `delete_issue` for anyone who could already
     *    have emptied the issue; a forum has no such equivalence.
     *  - **Posting is speech in front of an audience.** An issue comment is read
     *    by whoever opens the issue; a post mails every watcher of the forum. An
     *    agent that files a wrong issue has made a row somebody closes, and an
     *    agent that starts a wrong thread has interrupted a project.
     *
     * Admin, then, for [canSendAgentMail]'s reason: the question is about the
     * instance rather than about one board, and no seeded [Role] can express it.
     * When somebody wants an ordinary user's agent to be able to post, the honest
     * shape is a per-account permission beside `isMcpAllowed` — not a project
     * role, and not this check quietly widening.
     *
     * Note this gates reaching the tools, never who may be *named* by them:
     * writing under somebody else's name stays [canAttributeWrites]' question,
     * asked separately on every forum write that takes an `author`. The two are
     * the same answer today and are still two questions, exactly as they are on
     * `create_issue`.
     */
    fun canUseForumTools(user: UserRecord?): Boolean = user?.isSysAdmin == true

    suspend fun canDeleteForumContent(user: UserRecord?, author: Author, projectId: Long): Boolean =
        user != null && (
            user.isSysAdmin ||
                author == Author.Account(user.id) ||
                roles.hasRole(user.id, projectId, Role.PROJECT_ADMIN)
            )

    // ── Private messages ─────────────────────────────────────────────────────

    /**
     * May [user] read a conversation whose participants are [participantIds]?
     *
     * ── The one rule here that is not about a project ────────────────────────
     *
     * Every other function in this file takes a project, or something that
     * reaches one. Conversations are **instance-wide** by LNL-30's decision —
     * there is no project a private message belongs to — so this is the one place
     * the uniform `(user, projectId)` shape genuinely does not fit, and it is
     * answered against membership of the conversation instead. LNL-60 predicted
     * exactly this and asked for a conversation-scoped rule rather than a bent
     * project one; this is it.
     *
     * It takes the participant ids rather than a conversation id, so this class
     * keeps its one collaborator. That is the same reason [canDeleteForumContent]
     * takes an [Author] and not a post: the caller has already read the row it is
     * asking about, and a second store here would let a permission be answered
     * from something other than what the route is holding.
     *
     * ── The system administrator is in this rule, and it is not free ─────────
     *
     * `isSysAdmin ||`, as everywhere else in this file — and it deserves a
     * sentence rather than being inherited, because the feature is called private
     * messages and this means an instance administrator can read all of them.
     *
     * The alternative — participants only, with no admin clause anywhere in the
     * messages feature — was written out and rejected, for two reasons. It cannot
     * be held separately from deletion: a delete answers with the refreshed
     * thread, so an administrator able to remove a message would receive the
     * conversation in the response, and the power LNL-30 asked for would have
     * arrived carrying the one it did not. And it would make this the only rule in
     * this file where `isSysAdmin` does not win — the sort of exception that gets
     * un-made by the next person who writes a rule by copying its neighbour, and
     * whose un-making would be invisible.
     *
     * So the honest statement is: an instance administrator can read every private
     * conversation on the deployment. That is the same authority they already hold
     * over every private project — see [canReadProject] — and it is not advertised
     * anywhere in the UI, which is a fact about the UI and not a mitigation.
     */
    fun canReadConversation(user: UserRecord?, participantIds: Set<Long>): Boolean =
        user != null && (user.isSysAdmin || user.id in participantIds)

    /**
     * May [user] send a message into a conversation whose participants are
     * [participantIds]?
     *
     * Membership, and **not** [canReadConversation]: a system administrator may
     * read a thread and may not write in it. That is the narrowest useful split
     * and it is the same one [canDeleteForumContent] draws one feature over —
     * moderating is removing, never authoring. An administrator who could write
     * here would be able to put words into a private conversation that every
     * participant would read as coming from a peer, in a feature that records no
     * history and offers no way to tell.
     *
     * Membership is fixed at creation (LNL-30), so this can never become true for
     * somebody it was false for. There is nothing to grant.
     */
    fun canWriteInConversation(user: UserRecord?, participantIds: Set<Long>): Boolean =
        user != null && user.id in participantIds

    /**
     * May [user] delete a message written by [author]?
     *
     * ── A ticket that did not survive contact, corrected rather than obeyed ───
     *
     * LNL-60's acceptance list says *"a user can delete their own message; a
     * project admin can too"*, and that sentence cannot be executed: conversations
     * are instance-wide by the same ticket's decision, so there is no project
     * whose administrator this would be. The two clauses were written at different
     * times and only one of them can be kept.
     *
     * The instance-wide decision is kept, because everything else in the feature
     * rests on it, and the administrator clause is **read down to the system
     * administrator** — the only administrator a conversation has. That is the
     * nearest thing to what the ticket asked for that means anything: the reason a
     * project administrator may delete a forum post is that they are answerable
     * for that room, and the person answerable for a private conversation is
     * whoever runs the deployment.
     *
     * It is a narrow power on purpose, and it is a *deletion* power only. Deleting
     * says "this must not be on this instance" — abuse, a credential somebody
     * pasted, something legally required to go — which is an operator's judgement
     * to make. It is not accompanied by any way to edit: there is no edit route
     * for a message at all, for anybody, including the author. See Messages.sq.
     *
     * Authorship, as [canEditComment] reads it: a message written by an account
     * that has since been deleted is administrator-only, which is correct, and an
     * [Author.External] never equals an account, so nobody inherits a message by
     * sharing an imported name.
     *
     * Note this takes no participant set. The route resolves the conversation and
     * runs [canReadConversation] first, so being in the room is already proved by
     * the time this is asked; asking for it again here would be a second copy of
     * that check, drifting.
     */
    fun canDeleteMessage(user: UserRecord?, author: Author): Boolean =
        user != null && (user.isSysAdmin || author == Author.Account(user.id))

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
        // The bundle, mirrored from `administers`/`holds`/`canOwnProject`.
        // Computed once here from the one query above rather than re-derived per
        // line — and, crucially, without a second round-trip: every rule these
        // flags mirror is `role in held`, so an affordance that disagrees with the
        // rule (a button that 403s) cannot arise from the two reading different
        // rows. `owns` is senior to `administers`, so it feeds into it.
        val owns = user.isSysAdmin || Role.PROJECT_OWNER in held
        val administers = owns || Role.PROJECT_ADMIN in held
        return ProjectPermissions(
            canCreateIssue = administers || Role.CREATE_ISSUE in held,
            canComment = administers || Role.COMMENT_ON_ISSUE in held,
            canChangeUnownedIssues = administers || Role.CHANGE_UNOWNED_ISSUES in held,
            canMutateProject = administers,
            // Renaming, re-scoping, repository and deletion — the owner's tier, no
            // longer system-administrator only (LNL-107). See canOwnProject.
            canMutateProjectIdentity = owns,
            canBeAssigned = administers || Role.BE_ASSIGNED_ISSUE in held,
            // The two senior roles are handed out by an owner or a system
            // administrator, so the dialog needs to know that tier apart from the
            // issue roles to render the privileges table — see canGrant.
            canGrantSeniorRoles = owns,
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
    /**
     * Whether the caller administers this project — its vocabulary, its sprints
     * and its privileges. A system administrator, or a project administrator
     * here. What renders the settings dialog's admin sections and the board's
     * sprint controls.
     */
    val canMutateProject: Boolean = false,
    /**
     * Whether the caller may rename, re-prefix, re-scope, configure the repository
     * of, or delete the project itself. An owner or a system administrator
     * (LNL-107) — see canOwnProject, and the wire twin, which carries the full
     * reasoning.
     */
    val canMutateProjectIdentity: Boolean = false,
    /** Whether the caller may be handed an issue here — what renders "Assign to me". */
    val canBeAssigned: Boolean = false,
    /**
     * Whether the caller may hand out the two senior roles — project administrator
     * and project owner.
     *
     * Strictly narrower than [canMutateProject]: a project administrator hands out
     * the issue-scoped roles but may promote neither a peer nor an owner. Sent
     * separately because the privileges table renders one row per role and needs
     * to disable exactly the two senior ones. An owner or a system administrator.
     * See [AccessControl.canGrant].
     */
    val canGrantSeniorRoles: Boolean = false,
)
