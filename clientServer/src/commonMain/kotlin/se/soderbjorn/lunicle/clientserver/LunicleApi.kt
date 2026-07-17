/**
 * The typed HTTP client every platform talks to the Lunicle server through.
 *
 * Constructed once by the app bootstrap (the browser bundle's `main()`) and
 * handed to the client's `StorageRepository`, which is the only caller. Keeping
 * the transport here rather than in `:client` means a view model never mentions
 * HTTP, and a future platform gets the same wire behaviour for free.
 *
 * @see BoardState
 * @see ApiRoutes
 */
package se.soderbjorn.lunicle.clientserver

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Build the [HttpClient] backing an [LunicleApi].
 *
 * No engine is named: each target has exactly one engine on its classpath (JS
 * for the browser bundle, CIO for the JVM), so Ktor resolves it via the
 * service loader. `ignoreUnknownKeys` keeps an older cached bundle from failing
 * to parse a response that a newer server has added a field to.
 *
 * @return a configured client the caller owns and is responsible for closing.
 */
fun createHttpClient(): HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

/**
 * A request the server refused, carrying the reason it gave.
 *
 * @property status the HTTP status.
 * @property serverMessage the server's own explanation, already written for a
 *   human — [SignInFailure]'s user message on the far side.
 */
class ApiFailure(
    val status: HttpStatusCode,
    val serverMessage: String,
) : Exception("$status: $serverMessage")

/**
 * Decode a successful response, or throw [ApiFailure] carrying what the server
 * actually said.
 *
 * Calling `.body<T>()` on a failed response is a trap: the server's errors are
 * plain text, so Ktor throws NoTransformationFoundException — "Expected response
 * body of the type 'class SessionState' but was 'class SourceByteReadChannel'" —
 * and the *real* message, which the server wrote specifically to be read, is
 * discarded. That turns "Google would not complete the sign-in" into a
 * serialization puzzle, one layer away from the actual fault. Check the status
 * first, always.
 */
private suspend inline fun <reified T> HttpResponse.requireSuccess(): T {
    if (!status.isSuccess()) throw ApiFailure(status, bodyAsText())
    return body()
}


/**
 * A response with no body worth parsing. Throws [ApiFailure] on a refusal, for
 * the same reason [requireSuccess] does: the server's 403s and 409s are written
 * for a human and are the whole point of asking.
 */
private suspend fun HttpResponse.requireSuccessNoBody() {
    if (!status.isSuccess()) throw ApiFailure(status, bodyAsText())
}

/**
 * Typed access to the Lunicle server. Transport and nothing else.
 *
 * No decisions here — not even "is this project readable". Every method is one
 * request, and the answers it gets have already been filtered and authorised
 * server-side (see `AccessControl`). The client's own logic lives one layer up,
 * in `StorageRepository`.
 *
 * @param baseUrl prefix for every request. Defaults to `""` — a relative URL —
 *   which is correct for the browser bundle: the server that serves the bundle
 *   is the server the bundle talks to, and that stays true inside the
 *   lunamux.dev iframe, where the frame's own origin is lunicle.lunamux.dev. So
 *   there is no cross-origin request here and no CORS to configure. Pass an
 *   absolute URL from a JVM caller or a test.
 * @param httpClient the transport; defaults to a fresh [createHttpClient].
 */
class LunicleApi(
    private val baseUrl: String = "",
    private val httpClient: HttpClient = createHttpClient(),
) {
    // ── Session ──────────────────────────────────────────────────────────────

    /**
     * Who, if anyone, this browser is signed in as — and which providers this
     * deployment can offer.
     *
     * Never throws for being signed out: that is a [SessionState] with a null
     * user, not a failure. Only transport and parse failures throw.
     */
    suspend fun session(): SessionState =
        httpClient.get(baseUrl + ApiRoutes.SESSION).requireSuccess()

    /**
     * Trade a Google authorization code for a session.
     *
     * The code comes from the popup, which the *view* owns — Google's SDK is
     * browser-only, so the platform opens the popup and hands the code here.
     * That keeps this API and the view model free of anything Google-shaped.
     *
     * @param code the authorization code from `initCodeClient`'s callback.
     *   Useless without the client secret, which is why the browser may hold it.
     */
    suspend fun signInWithGoogle(code: String): SessionState =
        httpClient.post(baseUrl + ApiRoutes.AUTH_GOOGLE) {
            contentType(ContentType.Application.Json)
            setBody(GoogleCodeRequest(code))
        }.requireSuccess()

    /** Drop this browser's session. */
    suspend fun signOut(): SessionState =
        httpClient.post(baseUrl + ApiRoutes.SIGN_OUT).requireSuccess()

    /**
     * Start acting as another user. Admin only, enforced server-side.
     *
     * The returned [SessionState] is the whole result: its `user` is now the
     * impersonated one, and everything the caller may do has changed with it. The
     * caller does not get to hold "I am now user 7" locally — it asks and is told,
     * like every other question here.
     *
     * @param userId the account to act as. The server refuses unless this
     *   session's real user is an admin; nothing in this call says who is asking,
     *   because the cookie already does.
     */
    suspend fun impersonate(userId: Long): SessionState =
        httpClient.post(baseUrl + ApiRoutes.IMPERSONATE) {
            contentType(ContentType.Application.Json)
            setBody(ImpersonateRequest(userId))
        }.requireSuccess()

    /** Stop impersonating and go back to the account that signed in. */
    suspend fun stopImpersonating(): SessionState =
        httpClient.post(baseUrl + ApiRoutes.STOP_IMPERSONATING).requireSuccess()

    // ── Agent connections ────────────────────────────────────────────────────

    /** The caller's agent-access toggle, server URL, and connected agents. */
    suspend fun mcpState(): McpState =
        httpClient.get(baseUrl + ApiRoutes.MCP).requireSuccess()

    /**
     * Turn agent access on or off.
     *
     * @param isEnabled the state to move to — not "toggle", so a retry says the
     *   same thing. See [McpEnabledRequest].
     * @return the whole new [McpState], because turning it off does not change the
     *   list of connections and the caller should not have to know that.
     */
    suspend fun setMcpEnabled(isEnabled: Boolean): McpState =
        httpClient.post(baseUrl + ApiRoutes.MCP_ENABLED) {
            contentType(ContentType.Application.Json)
            setBody(McpEnabledRequest(isEnabled))
        }.requireSuccess()

    /**
     * Disconnect one agent, immediately and irreversibly.
     *
     * Kills the whole refresh family, so the agent cannot quietly refresh its way
     * back in — it must redo the browser flow. See the server's OAuthStores.
     */
    suspend fun revokeMcpConnection(clientId: String): McpState =
        httpClient.delete(baseUrl + ApiRoutes.mcpConnection(clientId)).requireSuccess()

    // ── Projects ─────────────────────────────────────────────────────────────

    /** The projects this caller may see. Already filtered server-side. */
    suspend fun projects(): ProjectListState =
        httpClient.get(baseUrl + ApiRoutes.PROJECTS).requireSuccess()

    /**
     * Create a project, seeded with its default labels, components and board
     * columns.
     *
     * @throws ApiFailure 403 if the caller is not the admin, 409 if the name or
     *   prefix is taken — carrying the server's own explanation, which the
     *   dialog shows verbatim.
     */
    suspend fun createProject(update: ProjectUpdate): ProjectSummary =
        httpClient.post(baseUrl + ApiRoutes.PROJECTS) {
            contentType(ContentType.Application.Json)
            setBody(update)
        }.requireSuccess()

    /** Rename or re-configure a project. Admin only, enforced server-side. */
    suspend fun updateProject(id: Long, update: ProjectUpdate): ProjectSummary =
        httpClient.put(baseUrl + ApiRoutes.project(id)) {
            contentType(ContentType.Application.Json)
            setBody(update)
        }.requireSuccess()

    /** Delete a project and everything in it. Admin only. */
    suspend fun deleteProject(id: Long) {
        httpClient.delete(baseUrl + ApiRoutes.project(id)).requireSuccessNoBody()
    }

    // ── Project settings ─────────────────────────────────────────────────────

    /**
     * A project's vocabularies and grants.
     *
     * @throws ApiFailure 403 for anyone who is not the admin. Refused rather than
     *   narrowed — there is no smaller true version of this response. See
     *   ProjectSettingsState.
     */
    suspend fun projectSettings(projectId: Long): ProjectSettingsState =
        httpClient.get(baseUrl + ApiRoutes.projectSettings(projectId)).requireSuccess()

    /**
     * Add a row to one of a project's vocabularies.
     *
     * Every write below returns the **whole** new [ProjectSettingsState] rather
     * than the row it touched, and that is the interesting thing about this
     * section. These edits change each other: deleting a status changes whether
     * the last remaining one may be deleted, and adding a priority moves the
     * middle of the scale a new issue lands on. A client that patched its own
     * state would be right about the row it just sent and wrong about the rest of
     * the dialog. So the server answers with everything, every time.
     *
     * @throws ApiFailure 409 if the name is blank or taken, carrying the server's
     *   own sentence, which the dialog shows verbatim.
     */
    suspend fun addVocabulary(projectId: Long, kind: VocabularyKind, name: String): ProjectSettingsState =
        httpClient.post(baseUrl + ApiRoutes.vocabulary(projectId, kind)) {
            contentType(ContentType.Application.Json)
            setBody(VocabularyAdd(name))
        }.requireSuccess()

    /**
     * Rename a row, and set a status's closing flag.
     *
     * @param requiresResolution ignored by the server for every kind but a status.
     *   See [VocabularyEdit].
     */
    suspend fun editVocabulary(
        projectId: Long,
        kind: VocabularyKind,
        itemId: Long,
        name: String,
        requiresResolution: Boolean,
    ): ProjectSettingsState =
        httpClient.put(baseUrl + ApiRoutes.vocabularyItem(projectId, kind, itemId)) {
            contentType(ContentType.Application.Json)
            setBody(VocabularyEdit(name, requiresResolution))
        }.requireSuccess()

    /**
     * Delete a row.
     *
     * @throws ApiFailure 400 when the row is still in use, or is the last status
     *   or priority — carrying the count: "3 issues are in that status." The
     *   dialog knows the counts too and disables the button, but that is an
     *   affordance; this is the answer. See the server's VocabularyRepository.
     */
    suspend fun deleteVocabulary(projectId: Long, kind: VocabularyKind, itemId: Long): ProjectSettingsState =
        httpClient.delete(baseUrl + ApiRoutes.vocabularyItem(projectId, kind, itemId)).requireSuccess()

    /**
     * Put one whole vocabulary in this order.
     *
     * @param ids every row of that kind, first to last. The server refuses a list
     *   that is not exactly this vocabulary rather than applying the part it
     *   understands — see [VocabularyOrder].
     */
    suspend fun reorderVocabulary(
        projectId: Long,
        kind: VocabularyKind,
        ids: List<Long>,
    ): ProjectSettingsState =
        httpClient.post(baseUrl + ApiRoutes.vocabularyOrder(projectId, kind)) {
            contentType(ContentType.Application.Json)
            setBody(VocabularyOrder(ids))
        }.requireSuccess()

    /** Grant or revoke one role for one user in one project. Admin only. */
    suspend fun setProjectRole(
        projectId: Long,
        userId: Long,
        roleKey: String,
        isGranted: Boolean,
    ): ProjectSettingsState =
        httpClient.post(baseUrl + ApiRoutes.projectRoles(projectId)) {
            contentType(ContentType.Application.Json)
            setBody(RoleGrant(userId, roleKey, isGranted))
        }.requireSuccess()

    // ── The board ────────────────────────────────────────────────────────────

    /** One project's whole board. */
    suspend fun board(projectId: Long): BoardState =
        httpClient.get(baseUrl + ApiRoutes.board(projectId)).requireSuccess()

    /**
     * Resolve the embed's `?project=<name>` to a board.
     *
     * @throws ApiFailure 404 when no such project exists *or* when it exists and
     *   this caller may not read it. The two are deliberately indistinguishable:
     *   answering "that project is private" would confirm it exists, which is
     *   the thing being withheld.
     */
    suspend fun boardByName(name: String): BoardState =
        httpClient.get(baseUrl + ApiRoutes.BOARD_BY_NAME) {
            parameter("name", name)
        }.requireSuccess()

    // ── Issues ───────────────────────────────────────────────────────────────

    /** Create the hidden draft an inline image can be attached to. */
    suspend fun createIssueDraft(projectId: Long): IssueDraft =
        httpClient.post(baseUrl + ApiRoutes.issues(projectId)).requireSuccess()

    suspend fun issue(id: Long): IssueDetail =
        httpClient.get(baseUrl + ApiRoutes.issue(id)).requireSuccess()

    /** Publish a draft, or save an edit to a published issue. Same call either way. */
    suspend fun saveIssue(id: Long, update: IssueUpdate): IssueDetail =
        httpClient.put(baseUrl + ApiRoutes.issue(id)) {
            contentType(ContentType.Application.Json)
            setBody(update)
        }.requireSuccess()

    /** Move a card to another column. */
    /**
     * Move an issue to another column.
     *
     * @param resolutionId why it is being closed. Required when the target column
     *   demands one — the server refuses the move otherwise, which is why the
     *   board asks before it sends. See BoardRoutes' resolveResolution.
     */
    suspend fun setIssueStatus(id: Long, statusId: Long, resolutionId: Long? = null) {
        httpClient.post(baseUrl + ApiRoutes.issueStatus(id)) {
            contentType(ContentType.Application.Json)
            setBody(StatusUpdate(statusId, resolutionId))
        }.requireSuccessNoBody()
    }

    /** Delete an issue. Also what Cancel does to a draft. */
    suspend fun deleteIssue(id: Long) {
        httpClient.delete(baseUrl + ApiRoutes.issue(id)).requireSuccessNoBody()
    }

    /**
     * Rank a board group, first to last.
     *
     * @param id the issue that was dragged; the server authorises against it.
     * @param issueIds the whole group in its new order. The server proves they
     *   really are one group before writing — see BoardRoutes.
     */
    suspend fun setIssueOrder(id: Long, issueIds: List<Long>) {
        httpClient.post(baseUrl + ApiRoutes.issueOrder(id)) {
            contentType(ContentType.Application.Json)
            setBody(IssueOrderUpdate(issueIds))
        }.requireSuccessNoBody()
    }

    // ── Comments ─────────────────────────────────────────────────────────────

    suspend fun createCommentDraft(issueId: Long): CommentDraft =
        httpClient.post(baseUrl + ApiRoutes.comments(issueId)).requireSuccess()

    suspend fun saveComment(id: Long, body: String) {
        httpClient.put(baseUrl + ApiRoutes.comment(id)) {
            contentType(ContentType.Application.Json)
            setBody(CommentUpdate(body))
        }.requireSuccessNoBody()
    }

    suspend fun deleteComment(id: Long) {
        httpClient.delete(baseUrl + ApiRoutes.comment(id)).requireSuccessNoBody()
    }

    // ── Attachments ──────────────────────────────────────────────────────────

    /**
     * Upload bytes owned by an issue, and get back the id the editor turns into
     * `![name](/api/attachments/<id>)`.
     *
     * Raw bytes with the filename in a query parameter, rather than a multipart
     * form. Multipart exists to carry several fields at once and there is only
     * one field here — the body *is* the file — so it would be a boundary, a
     * part header and an encoder to express "these bytes, that name". The
     * Content-Type header already says what the bytes are, which is the other
     * half of what a part header would have carried.
     *
     * The filename is decoration and is treated as such server-side: it is
     * stored for the download name and never reaches the filesystem, which
     * writes under a random `storage_key` instead. That is what makes a file
     * called "../../lunicle.db" a boring string rather than a path traversal.
     */
    suspend fun uploadIssueAttachment(
        issueId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef = uploadAttachment(ApiRoutes.issueAttachments(issueId), filename, mimeType, bytes)

    /** As [uploadIssueAttachment], for a comment. */
    suspend fun uploadCommentAttachment(
        commentId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef = uploadAttachment(ApiRoutes.commentAttachments(commentId), filename, mimeType, bytes)

    private suspend fun uploadAttachment(
        route: String,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef =
        httpClient.post(baseUrl + route) {
            parameter("filename", filename)
            // A mime type the browser did not recognise comes through as "", and
            // an empty Content-Type is a malformed request rather than a useful
            // "I don't know". The server sniffs and re-decides anyway — it must,
            // since this header is a claim by the caller — so the fallback here
            // only has to be well-formed.
            contentType(
                ContentType.parse(mimeType.takeIf { it.isNotBlank() } ?: "application/octet-stream"),
            )
            setBody(bytes)
        }.requireSuccess()
}
