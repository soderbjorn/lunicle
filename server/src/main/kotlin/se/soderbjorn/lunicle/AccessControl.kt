/**
 * The only thing that answers a permission question.
 *
 * ── The client is a renderer of permissions, never a source of them ──────────
 *
 * Everything the browser knows about what it may do — a flag on the wire, a
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
 * ── Two ladders and one rule (LNL-191) ──────────────────────────────────────
 *
 * This file used to answer each question against a *set* of role keys, with
 * `isSysAdmin ||` in front of every one of them. It now answers every project
 * question against a single rung, and it is shorter for it:
 *
 *     effectiveRole(user, project) = max(the audience rows the user matches,
 *                                        the user's own row)
 *
 * and every rule below is `effectiveRole(...) atLeast SOMETHING`. There is one
 * place the rungs are compared and one place the max is taken, so widening a rung
 * is one line here rather than a migration of everybody who holds it. See
 * [ProjectRole] for what each rung means and why the lines fall where they do.
 *
 * The instance ladder is the same shape: guest, member, staff, administrator,
 * owner. An instance administrator holds [ProjectRole.OWNER] on every project
 * without a row, exactly as the system administrator used to — but the handful of
 * powers that are about the *deployment* rather than about a board narrowed to the
 * instance **owner** alone. Those are named where they occur.
 *
 * @see ProjectRole
 * @see InstanceRole
 * @see projectRoutes
 */
package se.soderbjorn.lunicle

/**
 * Answers "may this caller do that?", and nothing else.
 *
 * @param roles the rung table: a person's own row, and a project's audience rows.
 * @param instanceSettings consulted for one fact and one only — **who owns this
 *   deployment**, which is a setting rather than a column (see
 *   [se.soderbjorn.lunicle.store.InstanceSettings.ownerUserId]) and is therefore
 *   not on the [UserRecord] the way administrator-ness is.
 *
 *   This is a second collaborator where the class had exactly one, so it is worth
 *   saying what stops it from becoming a read on every check: everything that only
 *   needs to tell guest from member from staff from administrator is answered by
 *   [storedInstanceRole] with no read at all, and [effectiveRole] consults
 *   ownership only when the answer could still change — that is, when the caller is
 *   not already an administrator and has not already reached [ProjectRole.OWNER] by
 *   some other route. On the common path — an ordinary user on an ordinary board —
 *   it is one extra read of one tiny document; on the paths that would loop, the
 *   short-circuits fire first.
 */
class AccessControl(
    private val roles: se.soderbjorn.lunicle.store.RoleStore,
    private val instanceSettings: se.soderbjorn.lunicle.store.InstanceSettingsStore,
) {
    // ── The two ladders ──────────────────────────────────────────────────────

    /**
     * Where [user] stands on the instance: the whole ladder, ownership included.
     *
     * [storedInstanceRole] answers the same question from the record alone and can
     * never say [InstanceRole.OWNER], because no row knows who owns the deployment.
     * This is the version that reads the setting, and it is the one to ask whenever
     * the difference between an administrator and the owner matters.
     *
     * The short-circuit is not an optimisation so much as the correct answer
     * arriving early: an administrator is already senior to every audience there is,
     * so nothing about ownership could raise them further within a project, and only
     * only the instance-scoped rules below care which of the two they are.
     */
    suspend fun instanceRole(user: UserRecord?): InstanceRole {
        if (user == null) return InstanceRole.GUEST
        return if (instanceSettings.current().ownerUserId == user.id) {
            InstanceRole.OWNER
        } else {
            user.storedInstanceRole
        }
    }

    /**
     * What [user] may do in [projectId], as one rung — or null for "nothing at all,
     * this project does not exist as far as they are concerned".
     *
     * ── The rule, and why it is a max ───────────────────────────────────────
     *
     * Two things can put somebody on a rung here: an **audience row** the project
     * wrote for everybody like them, and their **own row**. The answer is whichever
     * is higher.
     *
     * That a person's own row can only ever *raise* them is the load-bearing half.
     * The tempting alternative — an own row overrides the audience — reads as more
     * expressive and is a trap: it makes every audience row conditional on nobody
     * having written a smaller row for somebody, so "members may file bugs here"
     * stops being a sentence you can check by looking at one row. Under the max,
     * lowering somebody is done by lowering the audience, which is a statement about
     * everybody and is visible as such.
     *
     * ── Instance administrators, and the read they do not cost ──────────────
     *
     * An administrator (or the owner, who is senior to one) holds
     * [ProjectRole.OWNER] on every project without a row, which is what
     * `isSysAdmin ||` used to say in front of every rule in this file. It is one
     * line here now instead of a dozen there, and it is answered off the
     * [UserRecord] with no store read.
     *
     * @param projectId an id rather than a [ProjectRecord], deliberately: with
     *   visibility gone from the project row there is nothing on the record this
     *   depends on, and most callers reach here holding only an issue.
     */
    suspend fun effectiveRole(user: UserRecord?, projectId: Long): ProjectRole? {
        val stored = user.storedInstanceRole
        if (stored.atLeast(InstanceRole.ADMIN)) return ProjectRole.OWNER

        // The audience rows this caller matches. Because the instance ladder
        // ascends, "matches" is one comparison: a `guest` row matches everybody, a
        // `member` row matches members and staff, a `staff` row matches staff.
        var best: ProjectRole? = roles.audienceRoles(projectId)
            .filterKeys { stored.atLeast(it.instanceRole) }
            .values
            .maxByOrNull { it.rank }

        if (user != null) {
            val own = roles.roleFor(user.id, projectId)
            if (own != null && (best == null || own.rank > best.rank)) best = own
            // Only now is ownership worth a read: an owner who is not flagged as an
            // administrator would otherwise be treated as an ordinary member of their
            // own deployment. Skipped when the answer could not change.
            if (best != ProjectRole.OWNER && instanceRole(user).atLeast(InstanceRole.ADMIN)) {
                return ProjectRole.OWNER
            }
        }
        return best
    }

    /** Does [user] reach [rung] in [projectId]? The shape every project rule below takes. */
    private suspend fun holds(user: UserRecord?, projectId: Long, rung: ProjectRole): Boolean =
        effectiveRole(user, projectId)?.atLeast(rung) == true

    /** Is [user] the one account that owns this deployment? */
    private suspend fun ownsInstance(user: UserRecord?): Boolean =
        instanceRole(user) == InstanceRole.OWNER

    // ── Reading ──────────────────────────────────────────────────────────────

    /**
     * May [user] read [project]?
     *
     * Reaching any rung at all is reading: [ProjectRole.VIEWER] is the bottom of the
     * ladder and every other rung contains it, so there is no state where somebody
     * may file an issue in a project they cannot see. That incoherence was real once
     * — a `create_issue` grant did not imply visibility — and the ladder retires it
     * structurally rather than by remembering to tick a second box.
     *
     * **This is still the only rule that can say yes to a stranger.** It does so
     * through a `guest` audience row, which is what `is_public` became; the
     * difference is that the row also says at what rung, so "the world may read
     * this" and "the world may comment on this" are the same mechanism rather than a
     * boolean and a feature request.
     *
     * Takes the resolved [project] rather than an id because every caller has
     * already fetched it, and because taking one keeps the read gate looking like
     * what it is at the call sites.
     */
    suspend fun canReadProject(user: UserRecord?, project: ProjectRecord): Boolean =
        holds(user, project.id, ProjectRole.VIEWER)

    // ── Projects ─────────────────────────────────────────────────────────────

    /**
     * May [user] manage the *set* of projects from instance settings — reordering
     * and deleting across every board at once?
     *
     * **The instance owner, and not an instance administrator** (LNL-191). This is
     * the sharpest of the four narrowings this ticket makes, so it is worth being
     * plain about what changed: an administrator used to be able to delete any
     * board on the deployment from the settings dialog. That is not administering
     * an instance, it is disposing of other people's work, and the person who
     * should be able to do it is the one answerable for the deployment.
     *
     * A board's own owner still deletes their own board — see [canOwnProject]. What
     * is left here is the cross-project surface, which has no project to be scoped
     * to. Creating is not here either; see [canCreateProject], which split off when
     * LNL-115 gave it a second answer.
     */
    suspend fun canMutateProjects(user: UserRecord?): Boolean = ownsInstance(user)

    /**
     * May [user] see and change what is true of the whole deployment — the account
     * directory, admission, the per-tier permissions, and the policy switches?
     *
     * **An instance administrator, and the owner above them** (LNL-195). It is the
     * literal job of the role, and it is deliberately a *weaker* gate than
     * [canMutateProjects]: reading who exists here, and deciding whether members may
     * create boards, is administering the instance — where reordering and deleting other
     * people's boards is disposing of their work.
     *
     * Written as its own rule because the three instance tabs asked [canMutateProjects]
     * before this, which made every one of them **owner-only by accident**: an
     * administrator saw three tabs in the strip (the client offers them to anyone who is
     * one) and every request behind them answered 403, so the tabs rendered as empty
     * headings with a refusal at the bottom. Found by driving the app as an administrator
     * who is not the owner. The two rules now differ where they should.
     */
    suspend fun canAdministerInstance(user: UserRecord?): Boolean =
        instanceRole(user).atLeast(InstanceRole.ADMIN)

    /**
     * May [user] act as somebody else — an address, or a signed-out visitor?
     *
     * **The instance owner, and not an instance administrator** (LNL-197). It was any
     * administrator, which is the wrong audience for the one facility in the product
     * that hands you another person's rights *with their writes attached*. Every
     * other narrowing in this rework is about disposing of work; this one is about
     * becoming somebody, which is strictly more than administering them.
     *
     * Named rather than spelled [canMutateProjects] at the call sites, even though the
     * two answer identically today. They answer different questions — one is about the
     * project list, one is about identity — and a gate that borrows another gate's name
     * is a gate that silently follows it the next time it moves.
     *
     * Note what it deliberately does **not** do: weaken impersonation to a read-only
     * preview. Full powers, writes included, is the point — "could a stranger file
     * this?" is not a question a read-only view can answer — and owner-only is what
     * makes keeping them acceptable. See the server's Impersonations, and
     * `resolveCaller`, which asks this again on every single request so that somebody
     * who loses ownership mid-session stops acting as another person.
     */
    suspend fun canImpersonate(user: UserRecord?): Boolean = ownsInstance(user)

    /**
     * May [user] give this deployment to somebody else (LNL-198)?
     *
     * The owner, and **only** the owner — the one rule in this file that is not merely
     * narrower than an administrator's but self-referential: it decides who gets to
     * decide. An administrator who could hand the instance over could hand it to
     * themselves, which would make [InstanceRole.ADMIN] and [InstanceRole.OWNER] the same
     * rung with extra steps and quietly undo every narrowing above.
     *
     * Named rather than spelled [canMutateProjects] or [canImpersonate] at the call site,
     * even though all three answer identically today, for the reason [canImpersonate]
     * gives: a gate that borrows another's name follows it the next time it moves. This
     * one in particular must never widen by accident.
     *
     * Note what it does **not** say: who is eligible to *receive* it. That is a question
     * about the subject rather than the caller — the deployment's own domain and whether
     * anybody has ever signed into the account — and it lives at the route, beside the
     * list the picker renders, so the affordance and the enforcement are computed from
     * one rule. See `AdminRoutes.mayBeHandedTheInstance`.
     */
    suspend fun canHandOverInstance(user: UserRecord?): Boolean = ownsInstance(user)

    /**
     * May [user] bring a *new* project into existence?
     *
     * **Per tier** (LNL-192): signed in, and standing on a rung of the instance
     * ladder this deployment permits. Staff and member have a switch each; an
     * administrator and the owner may regardless, being senior to both. It replaces
     * the single `anyone_can_create_project` boolean, which could only say
     * "everybody or nobody" and so had no way to express the ordinary arrangement —
     * a company whose own people make boards while outside collaborators do not.
     *
     * The setting is read here rather than passed in, unlike the boolean it
     * replaces. Two switches threaded through every caller would be two chances to
     * read the wrong one, and this class already holds the settings store for
     * [instanceRole] — so this is one more question asked of a collaborator it has,
     * not a new one.
     *
     * Note the deliberate asymmetry with [canMutateProjects]: this widens *creating*
     * only. "Make a board" and "remove somebody's board" are not the same trust.
     */
    suspend fun canCreateProject(user: UserRecord?): Boolean =
        user != null && instanceSettings.current().permitsProjectCreation(instanceRole(user))

    /**
     * May [user] own [projectId] — rename it, re-prefix it, change who it admits,
     * configure its repository, delete it, and promote others to administer or own
     * it?
     *
     * The top rung. Strictly senior to [canAdministerProject], never a peer of it:
     * an administrator runs the board, an owner decides whether there is one.
     */
    suspend fun canOwnProject(user: UserRecord?, projectId: Long): Boolean =
        holds(user, projectId, ProjectRole.OWNER)

    /**
     * May [user] administer [projectId] — its vocabulary, its display settings, its
     * project settings, deleting its issues, and handing out rungs up to maintainer?
     *
     * **Not sprints and versions**, which moved down to [ProjectRole.MAINTAINER]:
     * planning the next two weeks is work on a board, where the set of statuses a
     * project *has* is a decision about the board. See [canEditVocabulary], which is
     * where the two part company.
     */
    suspend fun canAdministerProject(user: UserRecord?, projectId: Long): Boolean =
        holds(user, projectId, ProjectRole.ADMIN)

    /**
     * May [user] edit [kind] in [projectId]?
     *
     * The one place the vocabulary split lives. Sprints and versions are a
     * maintainer's — they are how work already in the project gets scheduled — and
     * the five vocabularies that define what the board *is* are an administrator's.
     * A single function so the seven kinds cannot drift into seven answers, and so
     * the routes ask one question instead of branching themselves.
     */
    suspend fun canEditVocabulary(
        user: UserRecord?,
        projectId: Long,
        kind: se.soderbjorn.lunicle.clientserver.VocabularyKind,
    ): Boolean = holds(user, projectId, kind.minimumRole)

    /**
     * May [user] put somebody on [role] in [projectId] — or take them off it?
     *
     * Two tiers, and the split is the point: rungs **up to and including
     * [ProjectRole.MAINTAINER]** are handed out by anyone who administers the board,
     * which is most of what running one is. [ProjectRole.ADMIN] and
     * [ProjectRole.OWNER] are handed out by an owner alone.
     *
     * Not symmetry for its own sake. A rung that can grant itself escalates: were an
     * administrator able to promote a peer, one could make a second, who could make
     * a third, and nobody senior would have a say. The escalation stops one rung up,
     * with the project's answerable party.
     *
     * Note this is about the rung being handed out, not the person receiving it —
     * and that taking a rung *away* asks the same question about the rung being
     * removed, so an administrator cannot demote an owner either.
     */
    suspend fun canGrant(user: UserRecord?, projectId: Long, role: ProjectRole): Boolean =
        if (role.atLeast(ProjectRole.ADMIN)) {
            canOwnProject(user, projectId)
        } else {
            canAdministerProject(user, projectId)
        }

    /**
     * May [user] decide whether [projectId] admits [audience], and at what rung?
     *
     * An owner's, with visibility: this is the row that can hand the entire internet
     * a rung on the board, so it sits with the same person who may delete it. It was
     * `is_public`, which was an owner's too (LNL-107) — the power did not move, only
     * its spelling.
     *
     * ── The one thing an owner cannot decide alone (LNL-192) ────────────────
     *
     * [Audience.GUEST] is refused outright while the instance's
     * "allow projects to be public" switch is off, whoever asks — an owner, an
     * instance administrator, the owner of the deployment. It is the veto that
     * replaces the blanket the retired require-sign-in switch provided, and it is
     * enforced here rather than only greyed in the Access list for the obvious
     * reason: a rule that lives in a screen is a rule a POST goes around.
     *
     * The other two audiences are unaffected. "Nothing may be published" is a
     * statement about strangers, not about whether a board may admit the people who
     * already have accounts on the instance.
     *
     * @param audience which audience the write names. It is a parameter rather than
     *   a separate "may publish" question so that a caller cannot ask the general
     *   version and then write the guest row — the gate and the write name the same
     *   thing.
     */
    suspend fun canSetAudience(user: UserRecord?, projectId: Long, audience: Audience): Boolean {
        if (audience == Audience.GUEST && !instanceSettings.current().allowPublicProjects) return false
        return canOwnProject(user, projectId)
    }

    // ── Issues ───────────────────────────────────────────────────────────────

    /** May [user] file an issue in [projectId]? */
    suspend fun canCreateIssue(user: UserRecord?, projectId: Long): Boolean =
        holds(user, projectId, ProjectRole.CONTRIBUTOR)

    /**
     * May [user] change [issue]?
     *
     * Two ways to yes: you wrote it (and are still a contributor here), or you reach
     * [ProjectRole.MAINTAINER], which is exactly the rung "edits anyone's issue"
     * names.
     *
     * **Drag-and-drop is not a special case, and must not become one.** Moving a
     * card between columns is a `status_id` write on an issue, so it comes through
     * here — the same function the editor uses. Worth stating because a board
     * invites a shortcut: a "just move it" endpoint that skips the check because
     * dragging feels lighter than editing. It is not lighter. It is the same write,
     * and an unchecked one would let anyone drag anything to Closed.
     */
    suspend fun canEditIssue(user: UserRecord?, issue: IssueRecord): Boolean {
        if (user == null) return false
        val rung = effectiveRole(user, issue.projectId) ?: return false
        return rung.atLeast(ProjectRole.MAINTAINER) ||
            (rung.atLeast(ProjectRole.CONTRIBUTOR) && user.wrote(issue.author))
    }

    /**
     * May [user] delete [issue]?
     *
     * **[ProjectRole.ADMIN], or your own.** No longer the same rule as editing, and
     * the split is [ProjectRole.ADMIN]'s doc in one sentence: a maintainer can
     * already empty an issue of everything it said and close it, and what they
     * cannot do is make it stop existing.
     *
     * The authorship clause is not a leftover. Discarding a draft *is* a delete —
     * see IssueBackingViewModel, where an unsaved issue is a real row and cancelling
     * removes it — so a contributor who could not delete their own issue could not
     * abandon a half-written one either. Deleting your own words is not the power
     * this rule narrows.
     */
    suspend fun canDeleteIssue(user: UserRecord?, issue: IssueRecord): Boolean {
        if (user == null) return false
        val rung = effectiveRole(user, issue.projectId) ?: return false
        return rung.atLeast(ProjectRole.ADMIN) ||
            (rung.atLeast(ProjectRole.CONTRIBUTOR) && user.wrote(issue.author))
    }

    /**
     * May [user] be named as an assignee in [projectId]?
     *
     * ── Not a permission about the caller, and that is the whole subtlety ──────
     *
     * Every other function in this file asks "may this caller do that?". This one
     * asks "may this *person* have that done to them?", and it is asked about two
     * different people depending on the route:
     *
     *  - "Assign to me" asks it about the caller, who is also the subject.
     *  - The editor's Assignee dropdown asks it about the person being *chosen*,
     *    while [canEditIssue] separately gates the caller. Conflating them would be
     *    the bug: a check that only asked about the caller would let anyone who may
     *    edit an issue hand it to somebody who is not on this project at all.
     *
     * So callers must be explicit about whose record they are passing — which is why
     * this takes a [UserRecord] like everything else here rather than a bare id: the
     * record for the *subject*, resolved from the store, never from the request
     * body's claim about who they are.
     */
    suspend fun canBeAssigned(user: UserRecord?, projectId: Long): Boolean =
        holds(user, projectId, ProjectRole.CONTRIBUTOR)

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
     * `update_issue`; no scope of its own, and no way to reach it that does not come
     * through here. An existing row's *author* is still rewritable by nobody, and
     * neither is its `created_at`; those are claims about what happened.
     * `updated_at` is not — it is the board's sort key, and an import that rewrites
     * a description to point at a just-uploaded attachment had no way to stop that
     * edit dragging a years-old issue to today.
     *
     * ── The instance OWNER, not an administrator (LNL-191) ─────────────────────
     *
     * Writing under someone else's name is indistinguishable from them having
     * written it, forever, and it is not scoped to a board — no project rung can
     * express it, which is why it is on the instance ladder at all. It sat with the
     * system administrator and now sits one rung higher, with the same reasoning
     * that moved [canMutateProjects]: this is a power over the deployment's record
     * of what happened, and the person answerable for that is its owner.
     *
     * Note what this is NOT: impersonation. An impersonating owner's effective user
     * is an ordinary user with ordinary rights — see [Caller] — so a caller reaching
     * this function while impersonating gets `false`, which is correct and is why
     * the two mechanisms must not be wired together. A token never impersonates at
     * all; see McpServer's `resolveMcpUser`.
     */
    suspend fun canAttributeWrites(user: UserRecord?): Boolean = ownsInstance(user)

    // ── E-mail ───────────────────────────────────────────────────────────────

    /**
     * May an agent holding [user]'s token send [user] free-form e-mail?
     *
     * The instance owner (LNL-191), and this is the one function here whose subject
     * and object are the same person: the address is [UserRecord.email] on the
     * token's own account and `send_email` has no recipient parameter at all, so
     * what is being asked is not "may they reach somebody" but "may this account be
     * a place an agent puts text that leaves the building".
     *
     * That framing is why "they are only mailing themselves" was not enough on its
     * own. Every other capability on the MCP surface is one the person already has
     * in the web app, so an agent that misuses it does something its user could have
     * done and can see afterwards on the board. This one leaves no row: mail that
     * has gone has gone, it carries this deployment's sending domain and reputation,
     * and the volume is bounded by nothing but a model's judgement. An instance
     * where every account with MCP on is a live outbound mail path is a
     * deliverability problem for the whole instance, and the person who owns that
     * problem is whoever owns the deployment.
     *
     * Note what does NOT change if this is ever widened: the recipient stays out of
     * the arguments. See [McpTools]' preamble — that is the property that keeps this
     * from being a relay, and it is not the one this function is.
     */
    suspend fun canSendAgentMail(user: UserRecord?): Boolean = ownsInstance(user)

    /**
     * May [user] delete a stored attachment out of band — the `delete_attachment`
     * MCP tool?
     *
     * The instance owner, like [canSendAgentMail], and for a kindred reason. The web
     * app has no standalone attachment delete at all: an attachment dies with the
     * issue or comment it hangs on, or is swept as an orphan at startup. So this is
     * not a capability the person already holds that the agent merely reaches — it
     * is a permanent, cross-project power to unlink any file on the instance by its
     * id. No project rung expresses that, so it sits with whoever owns the
     * deployment, exactly as agent mail does. See [McpTools.deleteAttachment].
     */
    suspend fun canDeleteAttachment(user: UserRecord?): Boolean = ownsInstance(user)

    // ── Comments ─────────────────────────────────────────────────────────────

    /** May [user] comment on an issue in [projectId]? */
    suspend fun canComment(user: UserRecord?, projectId: Long): Boolean =
        holds(user, projectId, ProjectRole.CONTRIBUTOR)

    /**
     * May [user] change or delete [comment]?
     *
     * Authorship, not a rung: reaching [ProjectRole.MAINTAINER] grants editing
     * anyone's *issue*, never their words. An instance administrator overrides, as
     * they do everywhere. A comment whose author is deleted is theirs alone, which
     * is correct.
     *
     * An imported comment is administrator-only too, and falls out of the same line
     * rather than needing a case: an [Author.External] is a name, and a name is never
     * equal to an account. Nobody can inherit an imported comment by happening to
     * share the name it was filed under.
     *
     * Not suspend, and that is a fact worth keeping: this needs nothing but the
     * caller's record and the comment the route is already holding.
     */
    fun canEditComment(user: UserRecord?, comment: CommentRecord): Boolean =
        user != null && (
            user.storedInstanceRole.atLeast(InstanceRole.ADMIN) ||
                user.wrote(comment.author)
            )

    // ── Forums and private messages: switched off ────────────────────────────
    //
    // Both features were retired in LNL-190, which turned their surfaces off and
    // left these checks alone so that this ticket could answer them properly. They
    // return false because **the feature is off**, not because the rung is unknown
    // — the distinction matters to whoever re-enables them: there is no rung to
    // restore here, there is a decision to re-make. Nothing computes an answer, so
    // nothing can drift into one.

    /** False: discussions are off (LNL-190). See the section comment. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun canPostInProject(user: UserRecord?, project: ProjectRecord): Boolean = false

    /** False: discussions are off (LNL-190). See the section comment. */
    fun canEditForumContent(user: UserRecord?, author: Author): Boolean = false

    /** False: the agent-driven door onto forums is off (LNL-190). See the section comment. */
    fun canUseForumTools(user: UserRecord?): Boolean = false

    /** False: discussions are off (LNL-190). See the section comment. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun canDeleteForumContent(user: UserRecord?, author: Author, projectId: Long): Boolean = false

    /** False: private messages are off (LNL-190). See the section comment. */
    fun canReadConversation(user: UserRecord?, participantIds: Set<Long>): Boolean = false

    /** False: private messages are off (LNL-190). See the section comment. */
    fun canWriteInConversation(user: UserRecord?, participantIds: Set<Long>): Boolean = false

    /** False: private messages are off (LNL-190). See the section comment. */
    fun canDeleteMessage(user: UserRecord?, author: Author): Boolean = false

    // ── Affordances ──────────────────────────────────────────────────────────

    /**
     * What the client should render as available in [projectId].
     *
     * Read this as: the answers the server would give, sent ahead of time so the
     * browser does not have to invite a user to do something that will 403. It is
     * not a grant. Every route recomputes its own answer from the session, so a
     * client that edits this object gets nothing but a 403 with extra steps. See
     * this file's preamble.
     *
     * Every flag is derived from **one** [effectiveRole] call — which is the whole
     * reason the rungs replaced a set of keys. An affordance that disagreed with a
     * rule (a button that 403s) cannot arise from the two reading different rows,
     * because there is one rung and both read it.
     */
    suspend fun permissionsFor(user: UserRecord?, projectId: Long): ProjectPermissions {
        val rung = effectiveRole(user, projectId) ?: return ProjectPermissions()
        return ProjectPermissions(
            rung = rung,
            canCreateIssue = rung.atLeast(ProjectRole.CONTRIBUTOR),
            canComment = rung.atLeast(ProjectRole.CONTRIBUTOR),
            canBeAssigned = rung.atLeast(ProjectRole.CONTRIBUTOR),
            canChangeUnownedIssues = rung.atLeast(ProjectRole.MAINTAINER),
            canManageSprintsAndVersions = rung.atLeast(ProjectRole.MAINTAINER),
            canMutateProject = rung.atLeast(ProjectRole.ADMIN),
            canMutateProjectIdentity = rung.atLeast(ProjectRole.OWNER),
            canGrantSeniorRoles = rung.atLeast(ProjectRole.OWNER),
        )
    }
}

/**
 * The minimum rung that may edit one vocabulary.
 *
 * On [se.soderbjorn.lunicle.clientserver.VocabularyKind] as an extension rather
 * than a property of the enum, because the enum is a wire type shared with the
 * client and this is a server-side rule: the client renders what it is told it may
 * edit, it does not compute it. See [AccessControl.canEditVocabulary], the only
 * reader.
 */
internal val se.soderbjorn.lunicle.clientserver.VocabularyKind.minimumRole: ProjectRole
    get() = when (this) {
        // Scheduling work that already exists, on a board this person already edits
        // every issue on.
        se.soderbjorn.lunicle.clientserver.VocabularyKind.SPRINT,
        se.soderbjorn.lunicle.clientserver.VocabularyKind.VERSION,
        -> ProjectRole.MAINTAINER
        // What the board *is*: adding a status changes every view for everybody and
        // can strand issues in a column nobody planned.
        se.soderbjorn.lunicle.clientserver.VocabularyKind.LABEL,
        se.soderbjorn.lunicle.clientserver.VocabularyKind.COMPONENT,
        se.soderbjorn.lunicle.clientserver.VocabularyKind.STATUS,
        se.soderbjorn.lunicle.clientserver.VocabularyKind.PRIORITY,
        se.soderbjorn.lunicle.clientserver.VocabularyKind.RESOLUTION,
        -> ProjectRole.ADMIN
    }

/**
 * The affordances for one project, as computed by [AccessControl.permissionsFor].
 *
 * Server-side twin of the wire type; converted at the route. Defaults are all
 * false, so a caller who reaches no rung at all and a bug that forgets to fill this
 * in produce the same, safe, UI.
 */
data class ProjectPermissions(
    /**
     * The rung itself, beside the flags derived from it.
     *
     * Carried so a caller that has already asked for the affordances does not ask for
     * the rung a second time — the board response names it on
     * [se.soderbjorn.lunicle.clientserver.ProjectSummary.roleKey], which is what lets
     * the settings rail say what somebody holds. Defaults to the bottom rung, matching
     * every flag below defaulting false: a caller who reached no rung at all gets a
     * record that claims nothing.
     */
    val rung: ProjectRole = ProjectRole.VIEWER,
    val canCreateIssue: Boolean = false,
    val canComment: Boolean = false,
    /** Whether the caller may edit issues they did not write — [ProjectRole.MAINTAINER]. */
    val canChangeUnownedIssues: Boolean = false,
    /**
     * Whether the caller may add, rename, reorder and complete sprints and versions
     * — [ProjectRole.MAINTAINER], one rung below the rest of the settings dialog.
     * Sent separately from [canMutateProject] because that is exactly where the two
     * now differ; see [AccessControl.canEditVocabulary].
     */
    val canManageSprintsAndVersions: Boolean = false,
    /**
     * Whether the caller administers this project — its vocabulary, its display
     * settings, and its grants up to maintainer. [ProjectRole.ADMIN]. What renders
     * the settings dialog's admin sections.
     */
    val canMutateProject: Boolean = false,
    /**
     * Whether the caller may rename, re-prefix, re-audience, configure the
     * repository of, or delete the project itself. [ProjectRole.OWNER].
     */
    val canMutateProjectIdentity: Boolean = false,
    /** Whether the caller may be handed an issue here — what renders "Assign to me". */
    val canBeAssigned: Boolean = false,
    /**
     * Whether the caller may hand out the two senior rungs — administrator and
     * owner.
     *
     * Strictly narrower than [canMutateProject]: an administrator hands out the
     * rungs up to maintainer but may promote neither a peer nor an owner. Sent
     * separately because the privileges table renders a row per rung and needs to
     * disable exactly the two senior ones. See [AccessControl.canGrant].
     */
    val canGrantSeniorRoles: Boolean = false,
)
