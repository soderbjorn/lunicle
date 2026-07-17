/**
 * The client's repository. Above HTTP, below the view models.
 *
 * It shares a name with the server's repositories and nothing else, and the
 * collision is worth being blunt about because both halves are called
 * "repository" in the same codebase:
 *
 *  - **This one** sits *above* HTTP and cannot see a database. It exists so a
 *    view model never mentions transport, and so that logic spanning several
 *    calls — load the project list *and* the session before MainScreen can
 *    render — has somewhere to live that isn't the view model.
 *  - **The server's** ([se.soderbjorn.lunicle.ProjectRepository] and friends)
 *    sit *below* HTTP and above SQL. They exist so a route never mentions a
 *    transaction.
 *
 * Same motivation, opposite side of the wire, no shared code.
 *
 * Nothing here decides who may do what. It cannot: every answer it holds was
 * already filtered and authorised by the server, and a permission that lived
 * here would be a permission living in JavaScript on someone else's machine.
 * See the server's `AccessControl` preamble.
 *
 * @see LunicleApi
 */
package se.soderbjorn.lunicle.client

import se.soderbjorn.lunicle.clientserver.ApiFailure
import se.soderbjorn.lunicle.clientserver.BoardState
import se.soderbjorn.lunicle.clientserver.CommentDraft
import se.soderbjorn.lunicle.clientserver.IssueDetail
import se.soderbjorn.lunicle.clientserver.IssueDraft
import se.soderbjorn.lunicle.clientserver.IssueUpdate
import se.soderbjorn.lunicle.clientserver.LunicleApi
import se.soderbjorn.lunicle.clientserver.McpState
import se.soderbjorn.lunicle.clientserver.ProjectListState
import se.soderbjorn.lunicle.clientserver.ProjectSettingsState
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.ProjectUpdate
import se.soderbjorn.lunicle.clientserver.SessionState
import se.soderbjorn.lunicle.clientserver.VocabularyKind

/**
 * Everything the screens need from the server, in the shape they need it.
 *
 * @param api the transport.
 */
class StorageRepository(
    private val api: LunicleApi = LunicleApi(),
) {
    // ── Session ──────────────────────────────────────────────────────────────

    suspend fun session(): SessionState = api.session()
    suspend fun signInWithGoogle(code: String): SessionState = api.signInWithGoogle(code)
    suspend fun signOut(): SessionState = api.signOut()
    suspend fun impersonate(userId: Long): SessionState = api.impersonate(userId)
    suspend fun stopImpersonating(): SessionState = api.stopImpersonating()

    // ── Agent connections ────────────────────────────────────────────────────

    suspend fun mcpState(): McpState = api.mcpState()
    suspend fun setMcpEnabled(isEnabled: Boolean): McpState = api.setMcpEnabled(isEnabled)
    suspend fun revokeMcpConnection(clientId: String): McpState = api.revokeMcpConnection(clientId)

    // ── The opening move ─────────────────────────────────────────────────────

    /**
     * Everything MainScreen needs before it can paint: who you are, what you may
     * see, and — if a project resolves — its board.
     *
     * This is why this class exists. The view model wants one answer to "what am
     * I rendering?", and getting it means three calls whose results depend on
     * each other: the session decides which projects come back, the project list
     * decides which board to ask for, and a `?project=` name has to resolve
     * before either. Doing that in the view model would put request sequencing
     * next to display state; doing it in the API would put display logic in the
     * transport.
     *
     * @param preferredName the embed's `?project=` value, if any. A name that
     *   does not resolve — because it does not exist, or because it is private
     *   and this caller is signed out, which are deliberately the same answer —
     *   falls back to no project rather than failing. The embed asking for
     *   something it cannot have should show the picker, not an error page.
     * @param preferredId a project already chosen in this session, so a refresh
     *   does not bounce the user back to whatever sorts first.
     */
    suspend fun load(
        preferredName: String? = null,
        preferredId: Long? = null,
        preferredTicket: Ticket? = null,
    ): Loaded {
        val session = api.session()
        val projects = api.projects()

        val chosen = resolve(projects, preferredName, preferredId, preferredTicket)
        val board = chosen?.let { runCatching { api.board(it.id) }.getOrNull() }

        return Loaded(session = session, projects = projects, board = board)
    }

    /**
     * Which project to show, in priority order: the one asked for by id, then
     * the one named by the embed, then the first one there is.
     *
     * The last step used to be "then nothing", on the reading that the spec's "We
     * might not be in any project" asked for it. It does not: that sentence
     * describes a state that must *exist*, and it still does — landing on the
     * first project is impossible when the list is empty, which is exactly when
     * having no project is the honest answer. What the old behaviour actually
     * produced was an empty board and a picker, shown to someone with exactly one
     * project, who then had to name the thing they already had. The rule now is
     * that no-project means no projects.
     *
     * It needs no signed-in/signed-out branch, and that is the point rather than
     * an accident: `api.projects()` returns what this caller may read, so the
     * "first project" is the first they have access to, and for a visitor with no
     * session that is the first *public* one. See BoardRoutes' /projects, which
     * filters through canReadProject. Deciding it here would mean the browser
     * holding an opinion about who may see what, which is the one thing this
     * client is never allowed to do.
     *
     * "First" is alphabetical, not arbitrary: `Projects.sq`'s `all` is
     * `ORDER BY name`. It is worth that being stable — this now picks for people,
     * so the pick has to be the same one tomorrow.
     */
    private suspend fun resolve(
        projects: ProjectListState,
        preferredName: String?,
        preferredId: Long?,
        preferredTicket: Ticket?,
    ): ProjectSummary? {
        // A deep link wins over everything, and over `preferredId` in particular:
        // an issue link names the project implicitly and completely, so honouring
        // a previously-selected project instead would open the wrong board and
        // then fail to find the issue in it. On the first load preferredId is null
        // anyway; this ordering is what makes a link *pasted into an open tab*
        // behave the same as one opened cold.
        //
        // Matched against the list rather than asked of the server: prefixes are
        // unique across every project (Projects.sq), and the list is already
        // filtered to what this caller may read — so a link to a private project
        // simply does not resolve for someone signed out, which is the same answer
        // the server would give and one round-trip cheaper.
        preferredTicket?.let { ticket ->
            projects.projects.firstOrNull { it.namePrefix.equals(ticket.prefix, ignoreCase = true) }
                ?.let { return it }
        }
        preferredId?.let { id ->
            projects.projects.firstOrNull { it.id == id }?.let { return it }
        }
        if (!preferredName.isNullOrBlank()) {
            // Resolved server-side rather than by scanning the list, because the
            // list is already filtered: a signed-out visitor naming a private
            // project must get "no such project", and the server is the only
            // thing that can say so without confirming it exists.
            runCatching { api.boardByName(preferredName) }.getOrNull()?.let { return it.project }
        }
        // Deliberately below the embed's name and not merged with it: an embed
        // that asked for a project it cannot have gets the picker, not a
        // different project silently swapped in. Falling through to "first" there
        // would answer a specific request with the wrong answer rather than with
        // no answer — see this function's @param preferredName.
        if (preferredName.isNullOrBlank()) return projects.projects.firstOrNull()
        return null
    }

    /** The three facts MainScreen opens with. */
    data class Loaded(
        val session: SessionState,
        val projects: ProjectListState,
        /** Null when no project resolved, or when its board could not be read. */
        val board: BoardState?,
    )

    // ── Boards ───────────────────────────────────────────────────────────────

    suspend fun board(projectId: Long): BoardState = api.board(projectId)
    suspend fun projects(): ProjectListState = api.projects()

    // ── Projects ─────────────────────────────────────────────────────────────

    suspend fun createProject(name: String, namePrefix: String, isPublic: Boolean): ProjectSummary =
        api.createProject(ProjectUpdate(name, namePrefix, isPublic))

    suspend fun updateProject(id: Long, name: String, namePrefix: String, isPublic: Boolean): ProjectSummary =
        api.updateProject(id, ProjectUpdate(name, namePrefix, isPublic))

    suspend fun deleteProject(id: Long) = api.deleteProject(id)

    // ── Project settings ─────────────────────────────────────────────────────

    /**
     * The settings dialog's whole subject, and every write to it.
     *
     * Pass-throughs, unlike [load] above: the dialog wants one answer and one
     * request answers it, so there is nothing here for this layer to sequence. They
     * are here rather than in the view model for the reason everything else is —
     * a view model never mentions transport.
     */
    suspend fun projectSettings(projectId: Long): ProjectSettingsState = api.projectSettings(projectId)

    suspend fun addVocabulary(projectId: Long, kind: VocabularyKind, name: String): ProjectSettingsState =
        api.addVocabulary(projectId, kind, name)

    suspend fun editVocabulary(
        projectId: Long,
        kind: VocabularyKind,
        itemId: Long,
        name: String,
        requiresResolution: Boolean,
    ): ProjectSettingsState = api.editVocabulary(projectId, kind, itemId, name, requiresResolution)

    suspend fun deleteVocabulary(projectId: Long, kind: VocabularyKind, itemId: Long): ProjectSettingsState =
        api.deleteVocabulary(projectId, kind, itemId)

    suspend fun reorderVocabulary(projectId: Long, kind: VocabularyKind, ids: List<Long>): ProjectSettingsState =
        api.reorderVocabulary(projectId, kind, ids)

    suspend fun setProjectRole(
        projectId: Long,
        userId: Long,
        roleKey: String,
        isGranted: Boolean,
    ): ProjectSettingsState = api.setProjectRole(projectId, userId, roleKey, isGranted)

    // ── Issues ───────────────────────────────────────────────────────────────

    suspend fun createIssueDraft(projectId: Long): IssueDraft = api.createIssueDraft(projectId)
    suspend fun issue(id: Long): IssueDetail = api.issue(id)

    suspend fun saveIssue(
        id: Long,
        title: String,
        description: String,
        statusId: Long,
        priorityId: Long,
        resolutionId: Long?,
        labelIds: List<Long>,
        componentIds: List<Long>,
    ): IssueDetail = api.saveIssue(
        id,
        IssueUpdate(title, description, statusId, priorityId, resolutionId, labelIds, componentIds),
    )

    suspend fun setIssueStatus(id: Long, statusId: Long, resolutionId: Long? = null) =
        api.setIssueStatus(id, statusId, resolutionId)
    suspend fun setIssueOrder(id: Long, issueIds: List<Long>) = api.setIssueOrder(id, issueIds)
    suspend fun deleteIssue(id: Long) = api.deleteIssue(id)

    // ── Comments ─────────────────────────────────────────────────────────────

    suspend fun createCommentDraft(issueId: Long): CommentDraft = api.createCommentDraft(issueId)
    suspend fun saveComment(id: Long, body: String) = api.saveComment(id, body)
    suspend fun deleteComment(id: Long) = api.deleteComment(id)

    // ── Attachments ──────────────────────────────────────────────────────────

    suspend fun uploadIssueAttachment(issueId: Long, filename: String, mimeType: String, bytes: ByteArray): Long =
        api.uploadIssueAttachment(issueId, filename, mimeType, bytes).id

    suspend fun uploadCommentAttachment(commentId: Long, filename: String, mimeType: String, bytes: ByteArray): Long =
        api.uploadCommentAttachment(commentId, filename, mimeType, bytes).id
}

/**
 * What to show the user for a failure.
 *
 * The server writes its refusals for a human — "There is already a project
 * called \"Lunamux\"", "You cannot edit this issue" — so prefer that to a
 * generic line invented on this side, which is by definition less specific than
 * what the server already knows. Anything that isn't an [ApiFailure] is a
 * transport or parse problem with no message worth showing, so it gets the
 * fallback.
 *
 * Shared by every view model, because every one of them has the same choice to
 * make and they should not each make it differently.
 */
fun Throwable.userMessage(fallback: String): String =
    (this as? ApiFailure)?.serverMessage?.takeIf { it.isNotBlank() } ?: fallback
