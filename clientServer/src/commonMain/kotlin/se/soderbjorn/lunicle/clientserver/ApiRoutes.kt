/**
 * The HTTP routes the server exposes and the client calls.
 *
 * Shared constants rather than string literals on each side, so a renamed route
 * is a compile error in both modules instead of a 404 discovered in a browser.
 *
 * The counter that used to live here is gone. It was Stage 1 and 2's payload —
 * the smallest thing that forced a real round-trip through the whole stack — and
 * the issue board is now the real thing it was rehearsing for.
 *
 * @see LunicleApi
 */
package se.soderbjorn.lunicle.clientserver

object ApiRoutes {
    /** `GET` — returns the caller's [SessionState]. Never 401s; signed out is a state, not an error. */
    const val SESSION: String = "/api/session"

    /** `POST` — exchanges a [GoogleCodeRequest] for a session. Returns the new [SessionState]. */
    const val AUTH_GOOGLE: String = "/api/auth/google"

    /** `POST` — drops the caller's session. Returns the signed-out [SessionState]. */
    const val SIGN_OUT: String = "/api/auth/signout"

    /**
     * `POST` — start acting as another user. Admin only. Returns the new
     * [SessionState], whose `user` is now the impersonated one.
     *
     * The body names *who to become*, and that is the only thing a client may say
     * about identity here — the server takes who is *asking* from the session
     * cookie and refuses this unless that session's real user is an admin. A
     * route that accepted "I am user 7" rather than "let me become user 7" would
     * be the whole authorization system, undone. See the server's Impersonations.
     */
    const val IMPERSONATE: String = "/api/impersonate"

    /**
     * `POST` — stop impersonating and go back to being yourself.
     *
     * Authorised against the session's **real** user, not the effective one:
     * while impersonating, the effective user is an ordinary user, so an
     * effective-user check would make this route refuse the very person entitled
     * to call it — locking the admin in as whoever they became.
     */
    const val STOP_IMPERSONATING: String = "/api/impersonate/stop"

    /**
     * `GET` — where the GitHub popup is sent to begin. Not called by
     * [LunicleApi]; the browser navigates a popup window here, because GitHub
     * refuses to be framed and offers no JS SDK to hide the fact.
     */
    const val AUTH_GITHUB_START: String = "/auth/github/start"

    /**
     * `GET` — where GitHub returns the popup to. Registered as the OAuth app's
     * Authorization callback URL, so this string is duplicated in GitHub's
     * console and cannot be renamed unilaterally. See docs/oauth-instructions.html.
     */
    const val AUTH_GITHUB_CALLBACK: String = "/auth/github/callback"

    /**
     * `GET` — the caller's [McpState]: the toggle, the server URL, and what is
     * connected.
     *
     * Session-cookie authenticated like every other `/api` route, and pointedly
     * unlike the OAuth endpoints and `/mcp`, which are deliberately
     * unauthenticated (they are how an agent gets a credential in the first
     * place). This is the human's view of that machinery.
     */
    const val MCP: String = "/api/mcp"

    /** `POST` — set the toggle from an [McpEnabledRequest]. Returns the new [McpState]. */
    const val MCP_ENABLED: String = "/api/mcp/enabled"

    /**
     * `DELETE` — disconnect one agent. Returns the new [McpState].
     *
     * Scoped to the calling session's user server-side: one client registration is
     * shared by everyone who connected that agent, so a route that revoked by
     * client id alone would disconnect it for the whole instance.
     */
    fun mcpConnection(clientId: String): String = "$MCP/connections/$clientId"

    /**
     * `GET` — the projects this caller may see, as a [ProjectListState].
     *
     * Filtered server-side: a signed-out visitor gets public projects only, not
     * "all projects, hidden in the UI". `POST` here creates one (admin only).
     */
    const val PROJECTS: String = "/api/projects"

    /**
     * `GET` — a project's whole board as a [BoardState]: statuses, issues,
     * vocabularies and the caller's affordances, in one round-trip.
     *
     * `{id}` is the project id, or its *name* for the embed's `?project=`
     * parameter — see [BOARD_BY_NAME].
     */
    fun board(projectId: Long): String = "/api/projects/$projectId/board"

    /**
     * `GET` — resolve the embed's `?project=<name>` to a board.
     *
     * A separate route rather than an overload of [board], because a name is not
     * an id and a route that took either would have to guess which it was
     * handed. A project literally named "42" is not a stretch.
     */
    const val BOARD_BY_NAME: String = "/api/board-by-name"

    /** `PUT` to update, `DELETE` to remove. Admin only, enforced server-side. */
    fun project(id: Long): String = "$PROJECTS/$id"

    /**
     * `GET` — a project's [ProjectSettingsState]: its vocabularies, with usage
     * counts, and every account's grants.
     *
     * Admin only, and **refused** rather than filtered — unlike [board], which
     * narrows what it returns for a lesser caller. There is no useful narrower
     * version of this: the whole response is administrative, so the honest answer
     * to anyone else is 403. See ProjectSettingsState's preamble.
     */
    fun projectSettings(id: Long): String = "$PROJECTS/$id/settings"

    /**
     * `POST` — add a row to one of a project's vocabularies, from a
     * [VocabularyAdd].
     *
     * `{kind}` is a [VocabularyKind.key]. One route family for all five rather
     * than five, because they differ only in rules the server owns — see
     * VocabularyKind.
     */
    fun vocabulary(projectId: Long, kind: VocabularyKind): String =
        "$PROJECTS/$projectId/vocabulary/${kind.key}"

    /**
     * `PUT` to rename (and set a status's closing flag) from a [VocabularyEdit],
     * `DELETE` to remove.
     *
     * Nested under the project rather than `/api/vocabulary/{id}`, so the server
     * can prove the row belongs to the project in the path — a row id from another
     * project answers 404 here rather than being edited. Admin is admin
     * everywhere, so this is not what stops an attack; it is what stops a
     * confused client from silently renaming the wrong project's "Closed".
     */
    fun vocabularyItem(projectId: Long, kind: VocabularyKind, itemId: Long): String =
        "${vocabulary(projectId, kind)}/$itemId"

    /**
     * `POST` — put one whole vocabulary in the order given, from a
     * [VocabularyOrder].
     *
     * Only the ordered kinds — statuses, priorities, resolutions — have anything
     * to say here; labels and components sort by name and the server refuses to
     * pretend otherwise. See VocabularyRepository.reorder.
     */
    fun vocabularyOrder(projectId: Long, kind: VocabularyKind): String =
        "${vocabulary(projectId, kind)}/order"

    /**
     * `POST` — grant or revoke one role for one user in this project, from a
     * [RoleGrant].
     *
     * One grant per request rather than "here is the whole matrix": a checkbox is
     * one intent, and a route that took the table would let a stale dialog revoke
     * a grant another admin made thirty seconds ago just by re-sending what it
     * had on screen.
     */
    fun projectRoles(projectId: Long): String = "$PROJECTS/$projectId/roles"

    /** `POST` — create a hidden draft issue, returning an [IssueDraft]. */
    fun issues(projectId: Long): String = "$PROJECTS/$projectId/issues"

    /** `GET` an [IssueDetail], `PUT` to publish/update it, `DELETE` to remove it. */
    fun issue(id: Long): String = "/api/issues/$id"

    /**
     * `POST` — move an issue to another column.
     *
     * Its own route because drag-and-drop has no title or description to send,
     * not because it is a lighter permission. It goes through the same
     * `canEditIssue` the editor does. See AccessControl.
     */
    fun issueStatus(id: Long): String = "/api/issues/$id/status"

    /**
     * `POST` — rank a board group, in the order given.
     *
     * `{id}` is the issue that was dragged, and it is there for the permission
     * check: reordering is an edit of that issue's placement, so it goes through
     * the same `canEditIssue` everything else does. The body carries the group.
     *
     * The one write in this API that does not touch `updated_at` — see Issues.sq.
     */
    fun issueOrder(id: Long): String = "/api/issues/$id/order"

    /** `POST` — create a hidden draft comment on this issue, returning its id. */
    fun comments(issueId: Long): String = "/api/issues/$issueId/comments"

    /** `PUT` to publish/update, `DELETE` to remove. */
    fun comment(id: Long): String = "/api/comments/$id"

    /** `POST` — upload bytes owned by this issue. See [LunicleApi.uploadIssueAttachment]. */
    fun issueAttachments(issueId: Long): String = "/api/issues/$issueId/attachments"

    /** `POST` — upload bytes owned by this comment. */
    fun commentAttachments(commentId: Long): String = "/api/comments/$commentId/attachments"

    /**
     * `GET` — the bytes.
     *
     * This URL appears inside rendered markdown, so it is trivially shareable
     * and will end up pasted places. The route resolves the owning issue and
     * runs `canReadProject` before streaming a byte — the `storage_key` being
     * random and never appearing in a URL is a second line of defence, not the
     * only one.
     */
    fun attachment(id: Long): String = "$ATTACHMENT_PREFIX$id"

    /**
     * What every attachment URL starts with.
     *
     * Public because the *renderer* has to recognise one. A link to
     * `/api/attachments/12` is a file the reader downloads and must be drawn as
     * one — filename, size, an icon — where a link to anywhere else is a link.
     * The URL is the only thing the renderer has to tell them apart with: it
     * reads markdown, and markdown has one spelling for both.
     *
     * Matching by prefix means a description that names an attachment of a
     * project the reader cannot see still renders as a download link. That is
     * the right answer — the route itself answers 404 to them, and the
     * alternative is the renderer asking the server about every link it draws.
     *
     * @see se.soderbjorn.lunicle.client.renderMarkdown
     */
    const val ATTACHMENT_PREFIX: String = "/api/attachments/"
}
