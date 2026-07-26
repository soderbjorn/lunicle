/**
 * The typed HTTP client every platform talks to the Lunicle server through.
 *
 * Constructed once by the app bootstrap (the browser bundle's `main()`) and
 * handed to the client's `StorageRepository`, which is the only caller. Keeping
 * the transport here rather than in `:client` means a view model never mentions
 * HTTP, and a future platform gets the same wire behaviour for free.
 *
 * This is the production implementation of [LunicleApi]. The browser's demo mode
 * (LNL-146) supplies a second one that answers from an in-memory world; both are
 * hidden behind the interface so `StorageRepository` and every view model run
 * against either without knowing which.
 *
 * @see LunicleApi
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
 * Build the [HttpClient] backing an [HttpLunicleApi].
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
class HttpLunicleApi(
    private val baseUrl: String = "",
    private val httpClient: HttpClient = createHttpClient(),
) : LunicleApi {
    // ── Session ──────────────────────────────────────────────────────────────

    /**
     * Who, if anyone, this browser is signed in as — and which providers this
     * deployment can offer.
     *
     * Never throws for being signed out: that is a [SessionState] with a null
     * user, not a failure. Only transport and parse failures throw.
     */
    override suspend fun session(): SessionState =
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
    override suspend fun signInWithGoogle(code: String): SessionState =
        httpClient.post(baseUrl + ApiRoutes.AUTH_GOOGLE) {
            contentType(ContentType.Application.Json)
            setBody(GoogleCodeRequest(code))
        }.requireSuccess()

    /**
     * Ask for a sign-in code by e-mail.
     *
     * Returns nothing, and tells you nothing: the server answers identically
     * whether or not the address has an account and whether or not the mail
     * actually went, because anything else would be an account-existence oracle.
     * So a caller can only ever say "check your mail", which is the whole
     * intended vocabulary of this step.
     *
     * @throws ApiFailure 400 for an implausible address, 429 when asked too
     *   often. Both messages are meant to be shown verbatim.
     */
    override suspend fun requestEmailSignIn(email: String) {
        httpClient.post(baseUrl + ApiRoutes.AUTH_EMAIL_REQUEST) {
            contentType(ContentType.Application.Json)
            setBody(EmailSignInRequest(email))
        }.requireSuccessNoBody()
    }

    /**
     * Trade a mailed code for a session.
     *
     * The e-mail counterpart to [signInWithGoogle], and the same shape: a
     * one-time secret in, a [SessionState] with a session cookie already set out.
     *
     * @throws ApiFailure 400 for a wrong, expired or exhausted code — one message
     *   for all of them, deliberately.
     */
    override suspend fun signInWithEmailCode(email: String, code: String): SessionState =
        httpClient.post(baseUrl + ApiRoutes.AUTH_EMAIL_REDEEM) {
            contentType(ContentType.Application.Json)
            setBody(EmailSignInRedeemRequest(email, code))
        }.requireSuccess()

    /** Drop this browser's session. */
    override suspend fun signOut(): SessionState =
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
    override suspend fun impersonate(userId: Long): SessionState =
        httpClient.post(baseUrl + ApiRoutes.IMPERSONATE) {
            contentType(ContentType.Application.Json)
            setBody(ImpersonateRequest(userId))
        }.requireSuccess()

    /**
     * Act as a signed-out visitor — no account at all (LNL-103).
     *
     * The same route as [impersonate] with a null id, since "become nobody" and
     * "become that account" are one decision the server makes the same way. Refused
     * unless this session's real user is an admin.
     */
    override suspend fun impersonateSignedOut(): SessionState =
        httpClient.post(baseUrl + ApiRoutes.IMPERSONATE) {
            contentType(ContentType.Application.Json)
            setBody(ImpersonateRequest(userId = null))
        }.requireSuccess()

    /** Stop impersonating and go back to the account that signed in. */
    override suspend fun stopImpersonating(): SessionState =
        httpClient.post(baseUrl + ApiRoutes.STOP_IMPERSONATING).requireSuccess()

    // ── Profile ──────────────────────────────────────────────────────────────

    /**
     * Set or clear the caller's display-name override.
     *
     * @param displayName the override, or null/blank to clear it and fall back to
     *   the provider's name.
     * @return the refreshed [SessionState], so the top bar and dialog re-render
     *   from the resolved name without a second round-trip.
     */
    override suspend fun setDisplayName(displayName: String?): SessionState =
        httpClient.post(baseUrl + ApiRoutes.USER_DISPLAY_NAME) {
            contentType(ContentType.Application.Json)
            setBody(SetDisplayNameRequest(displayName))
        }.requireSuccess()

    /**
     * Remove the caller's own e-mail.
     *
     * **Clearing only.** Setting an address is [requestEmailChange] followed by
     * [confirmEmailChange], because it needs proof the caller can receive mail
     * there; this route used to do both with no verification at all, which LNL-71
     * closed. Giving a mailbox up proves nothing and so needs no proof.
     *
     * @return the refreshed [SessionState]. The absence of `user.email` is what
     *   makes the notification toggles disappear.
     */
    override suspend fun clearEmail(): SessionState =
        httpClient.post(baseUrl + ApiRoutes.USER_EMAIL) {
            contentType(ContentType.Application.Json)
            setBody(SetEmailRequest(null))
        }.requireSuccess()

    /**
     * Ask for a code that would attach [email] to the caller's account.
     *
     * Writes nothing to the account — the address comes back as
     * `SessionState.pendingEmail` and stays pending until [confirmEmailChange]
     * spends the code.
     *
     * @throws ApiFailure 400 for an implausible address or no mail configured,
     *   429 when asked too often, 502 when the provider refused the send. Every
     *   message is meant to be shown verbatim.
     */
    override suspend fun requestEmailChange(email: String): SessionState =
        httpClient.post(baseUrl + ApiRoutes.USER_EMAIL_REQUEST) {
            contentType(ContentType.Application.Json)
            setBody(RequestEmailChangeRequest(email))
        }.requireSuccess()

    /**
     * Spend the mailed code and write the pending address.
     *
     * Note the address is not sent: the server holds it with the pending row, so
     * what gets written is what was actually mailed to.
     *
     * @throws ApiFailure 400 for a wrong, expired or exhausted code — all one
     *   message, deliberately.
     */
    override suspend fun confirmEmailChange(code: String): SessionState =
        httpClient.post(baseUrl + ApiRoutes.USER_EMAIL_CONFIRM) {
            contentType(ContentType.Application.Json)
            setBody(ConfirmEmailRequest(code))
        }.requireSuccess()

    /** Drop a pending address change, so a mistyped address is not a fifteen-minute wait. */
    override suspend fun cancelEmailChange(): SessionState =
        httpClient.post(baseUrl + ApiRoutes.USER_EMAIL_CANCEL).requireSuccess()

    // ── Shell settings ───────────────────────────────────────────────────────

    /**
     * The caller's stored shell settings — appearance and themes — and which
     * user they belong to.
     *
     * Never throws for being signed out, exactly like [session]: the shell asks
     * this before the session has resolved, and an empty [UiSettingsState] is the
     * honest answer rather than an error the boot path would have to catch.
     */
    override suspend fun uiSettings(): UiSettingsState =
        httpClient.get(baseUrl + ApiRoutes.USER_UI_SETTINGS).requireSuccess()

    /**
     * Store one shell setting.
     *
     * No response body: the caller wrote this value and already has it. The whole
     * state comes back nowhere, unlike every other write here, because this one
     * runs on every flick of the dark/light control and a round-trip carrying
     * what the browser just said would be pure ceremony.
     *
     * @param key one of [UiSettingKeys.persisted]; the server refuses anything else.
     * @param value the toolkit's blob, stored verbatim.
     * @throws ApiFailure 403 when nobody is signed in — there is nowhere to put it.
     */
    override suspend fun setUiSetting(key: String, value: String) {
        httpClient.post(baseUrl + ApiRoutes.USER_UI_SETTINGS) {
            contentType(ContentType.Application.Json)
            setBody(SetUiSettingRequest(key, value))
        }.requireSuccessNoBody()
    }

    // ── Agent connections ────────────────────────────────────────────────────

    /** The caller's agent-access toggle, server URL, and connected agents. */
    override suspend fun mcpState(): McpState =
        httpClient.get(baseUrl + ApiRoutes.MCP).requireSuccess()

    /**
     * Turn agent access on or off.
     *
     * @param isEnabled the state to move to — not "toggle", so a retry says the
     *   same thing. See [McpEnabledRequest].
     * @return the whole new [McpState], because turning it off does not change the
     *   list of connections and the caller should not have to know that.
     */
    override suspend fun setMcpEnabled(isEnabled: Boolean): McpState =
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
    override suspend fun revokeMcpConnection(clientId: String): McpState =
        httpClient.delete(baseUrl + ApiRoutes.mcpConnection(clientId)).requireSuccess()

    // ── Instance administration ──────────────────────────────────────────────

    /**
     * Every account on this instance, with what each holds in each project.
     *
     * @throws ApiFailure 403 for anyone who is not an admin. Refused rather than
     *   narrowed, unlike [projectSettings] — nothing in this response belongs to a
     *   non-admin. See [AdminSettingsState].
     */
    override suspend fun adminSettings(): AdminSettingsState =
        httpClient.get(baseUrl + ApiRoutes.ADMIN_SETTINGS).requireSuccess()

    /**
     * Permit one user to have agent access, or withdraw it.
     *
     * A different flag from [setMcpEnabled], not merely a different caller:
     * that one is the user turning their own switch on, this one is an admin
     * deciding whether they have a switch. See [ApiRoutes.ADMIN_USER_MCP].
     *
     * @param isAllowed the state to move to, not "toggle". See [UserMcpAccess].
     * @return the whole new [AdminSettingsState] rather than the row it touched,
     *   for the reason the project-settings writes do it: the dialog re-renders
     *   from the server's answer and never patches its own copy.
     * @throws ApiFailure 403 for a non-admin, 404 if there is no such user.
     */
    override suspend fun setUserMcpAllowed(userId: Long, isAllowed: Boolean): AdminSettingsState =
        httpClient.post(baseUrl + ApiRoutes.ADMIN_USER_MCP) {
            contentType(ContentType.Application.Json)
            setBody(UserMcpAccess(userId, isAllowed))
        }.requireSuccess()

    /**
     * Set one instance-wide switch (LNL-115): require sign-in, or open project
     * creation.
     *
     * @param key which switch, @param isEnabled the state to move it to — named,
     *   not "toggle", see [SetInstanceSettingRequest].
     * @return the whole refreshed [AdminSettingsState], like [setUserMcpAllowed]:
     *   the General tab re-renders from the server's answer and never patches its
     *   own copy.
     * @throws ApiFailure 403 for a non-admin.
     */
    override suspend fun setInstanceSetting(key: InstanceSettingKey, isEnabled: Boolean): AdminSettingsState =
        httpClient.post(baseUrl + ApiRoutes.ADMIN_INSTANCE_SETTINGS) {
            contentType(ContentType.Application.Json)
            setBody(SetInstanceSettingRequest(key, isEnabled))
        }.requireSuccess()

    /**
     * Put the instance's projects in a given order.
     *
     * The whole new order, not a delta — see [ProjectOrder]. Admin only.
     *
     * @return the whole refreshed [AdminSettingsState], like [setUserMcpAllowed]:
     *   the Projects tab re-renders from the server's answer and never patches its
     *   own copy, so the picker order and the tab's list cannot drift apart.
     * @throws ApiFailure 403 for a non-admin, 409 carrying the server's sentence if
     *   the order no longer names exactly the instance's projects.
     */
    override suspend fun reorderProjects(ids: List<Long>): AdminSettingsState =
        httpClient.post(baseUrl + ApiRoutes.ADMIN_PROJECT_ORDER) {
            contentType(ContentType.Application.Json)
            setBody(ProjectOrder(ids))
        }.requireSuccess()

    /**
     * Delete a project from the instance settings dialog, returning the refreshed
     * directory.
     *
     * The admin-dialog twin of [deleteProject], which answers with no body because
     * its caller (the project dialog) reloads the whole board afterwards. This one
     * returns the fresh [AdminSettingsState] so the Projects tab updates in one
     * round-trip, the way every other write from that dialog does. Admin only.
     *
     * @throws ApiFailure 403 for a non-admin, 404 if the project is already gone.
     */
    override suspend fun deleteProjectAsAdmin(id: Long): AdminSettingsState =
        httpClient.delete(baseUrl + ApiRoutes.adminProject(id)).requireSuccess()

    // ── Projects ─────────────────────────────────────────────────────────────

    /** The projects this caller may see. Already filtered server-side. */
    override suspend fun projects(): ProjectListState =
        httpClient.get(baseUrl + ApiRoutes.PROJECTS).requireSuccess()

    /**
     * Create a project, seeded with its default labels, components and board
     * columns.
     *
     * @throws ApiFailure 403 if the caller is not the admin, 409 if the name or
     *   prefix is taken — carrying the server's own explanation, which the
     *   dialog shows verbatim.
     */
    override suspend fun createProject(update: ProjectUpdate): ProjectSummary =
        httpClient.post(baseUrl + ApiRoutes.PROJECTS) {
            contentType(ContentType.Application.Json)
            setBody(update)
        }.requireSuccess()

    /** Rename or re-configure a project. Admin only, enforced server-side. */
    override suspend fun updateProject(id: Long, update: ProjectUpdate): ProjectSummary =
        httpClient.put(baseUrl + ApiRoutes.project(id)) {
            contentType(ContentType.Application.Json)
            setBody(update)
        }.requireSuccess()

    /** Delete a project and everything in it. Admin only. */
    override suspend fun deleteProject(id: Long) {
        httpClient.delete(baseUrl + ApiRoutes.project(id)).requireSuccessNoBody()
    }

    // ── Statistics ───────────────────────────────────────────────────────────

    /**
     * The last compiled statistics, without compiling anything.
     *
     * Returns at once — it reads a cached row — so the dialog has numbers on
     * screen before [refreshProjectStatistics] is even considered. Whether they
     * have aged out is [StatisticsState.isStale].
     */
    override suspend fun projectStatistics(projectId: Long): StatisticsState =
        httpClient.get(baseUrl + ApiRoutes.projectStatistics(projectId)).requireSuccess()

    /**
     * Recompile the statistics and return them.
     *
     * **This one can be slow.** On an aged-out cache it makes several calls to
     * github.com before answering, which is why the dialog shows progress across
     * it and why it is a separate method rather than a flag on the one above — a
     * caller has to choose the slow path deliberately.
     *
     * Safe to call more often than it looks: the server refuses to recompile
     * inside its own window and simply returns the cached numbers, so a
     * double-click costs one request and no GitHub rate limit.
     */
    override suspend fun refreshProjectStatistics(projectId: Long): StatisticsState =
        httpClient.post(baseUrl + ApiRoutes.projectStatisticsRefresh(projectId)).requireSuccess()

    // ── Project settings ─────────────────────────────────────────────────────

    /**
     * A project's settings, as this caller may see them.
     *
     * Openable by any signed-in reader: an admin gets the vocabularies and grants,
     * a non-admin gets only the notification fields. Narrowed server-side rather
     * than refused. See ProjectSettingsState.
     */
    override suspend fun projectSettings(projectId: Long): ProjectSettingsState =
        httpClient.get(baseUrl + ApiRoutes.projectSettings(projectId)).requireSuccess()

    /**
     * Subscribe or unsubscribe the caller from this project's new-issue e-mails.
     *
     * @return the refreshed [ProjectSettingsState], so the dialog re-renders the
     *   toggle from the server's truth.
     * @throws ApiFailure 403 if the caller has no e-mail address to send to.
     */
    override suspend fun setProjectNewIssueNotification(projectId: Long, subscribed: Boolean): ProjectSettingsState =
        httpClient.post(baseUrl + ApiRoutes.projectNewIssueNotification(projectId)) {
            contentType(ContentType.Application.Json)
            setBody(NotificationSubscriptionRequest(subscribed))
        }.requireSuccess()

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
    override suspend fun addVocabulary(projectId: Long, kind: VocabularyKind, name: String): ProjectSettingsState =
        httpClient.post(baseUrl + ApiRoutes.vocabulary(projectId, kind)) {
            contentType(ContentType.Application.Json)
            setBody(VocabularyAdd(name))
        }.requireSuccess()

    /**
     * Rename a row, and set a status's closing flag or a resolution's done flag.
     *
     * @param requiresResolution ignored by the server for every kind but a status.
     * @param isDone ignored by the server for every kind but a resolution (LNL-134).
     *   See [VocabularyEdit].
     */
    override suspend fun editVocabulary(
        projectId: Long,
        kind: VocabularyKind,
        itemId: Long,
        name: String,
        requiresResolution: Boolean,
        isDone: Boolean,
    ): ProjectSettingsState =
        httpClient.put(baseUrl + ApiRoutes.vocabularyItem(projectId, kind, itemId)) {
            contentType(ContentType.Application.Json)
            setBody(VocabularyEdit(name, requiresResolution, isDone))
        }.requireSuccess()

    /**
     * Delete a row.
     *
     * @throws ApiFailure 400 when the row is still in use, or is the last status
     *   or priority — carrying the count: "3 issues are in that status." The
     *   dialog knows the counts too and disables the button, but that is an
     *   affordance; this is the answer. See the server's VocabularyRepository.
     */
    override suspend fun deleteVocabulary(projectId: Long, kind: VocabularyKind, itemId: Long): ProjectSettingsState =
        httpClient.delete(baseUrl + ApiRoutes.vocabularyItem(projectId, kind, itemId)).requireSuccess()

    /**
     * Put one whole vocabulary in this order.
     *
     * @param ids every row of that kind, first to last. The server refuses a list
     *   that is not exactly this vocabulary rather than applying the part it
     *   understands — see [VocabularyOrder].
     */
    override suspend fun reorderVocabulary(
        projectId: Long,
        kind: VocabularyKind,
        ids: List<Long>,
    ): ProjectSettingsState =
        httpClient.post(baseUrl + ApiRoutes.vocabularyOrder(projectId, kind)) {
            contentType(ContentType.Application.Json)
            setBody(VocabularyOrder(ids))
        }.requireSuccess()

    /**
     * Switch this project's discussions and messages on or off (LNL-96).
     *
     * Both flags together — see [ProjectFeatures]. Returns the refreshed
     * [ProjectSettingsState], so the dialog re-renders from the server's answer.
     *
     * @throws ApiFailure 403 for a caller who does not administer this project.
     */
    override suspend fun setProjectFeatures(
        projectId: Long,
        discussionsEnabled: Boolean,
        messagesEnabled: Boolean,
    ): ProjectSettingsState =
        httpClient.post(baseUrl + ApiRoutes.projectFeatures(projectId)) {
            contentType(ContentType.Application.Json)
            setBody(ProjectFeatures(discussionsEnabled, messagesEnabled))
        }.requireSuccess()

    /**
     * Set this project's ticket requirements — must a ticket carry a label, a
     * component (LNL-106), and must a done close carry a fixed version (LNL-134).
     * All flags together — see [ProjectRequirements]. Returns the refreshed
     * [ProjectSettingsState].
     *
     * @throws ApiFailure 403 for a caller who does not administer this project.
     */
    override suspend fun setProjectRequirements(
        projectId: Long,
        requireLabel: Boolean,
        requireComponent: Boolean,
        requireFixedVersionOnResolve: Boolean,
    ): ProjectSettingsState =
        httpClient.post(baseUrl + ApiRoutes.projectRequirements(projectId)) {
            contentType(ContentType.Application.Json)
            setBody(ProjectRequirements(requireLabel, requireComponent, requireFixedVersionOnResolve))
        }.requireSuccess()

    /**
     * Set this project's board-display settings — whether cards show the author
     * (LNL-157). Returns the refreshed [ProjectSettingsState].
     *
     * @throws ApiFailure 403 for a caller who does not administer this project.
     */
    override suspend fun setProjectDisplaySettings(
        projectId: Long,
        showIssueAuthor: Boolean,
    ): ProjectSettingsState =
        httpClient.post(baseUrl + ApiRoutes.projectDisplay(projectId)) {
            contentType(ContentType.Application.Json)
            setBody(ProjectDisplaySettings(showIssueAuthor))
        }.requireSuccess()

    // ── Forums ───────────────────────────────────────────────────────────────

    /**
     * This project's forums, and whether this caller may manage them.
     *
     * Readable by anyone who may read the project. The forum master toggle is
     * deliberately not sent — it is a client-side flag, and the server does not
     * gate on it. See LNL-30.
     */
    override suspend fun forums(projectId: Long): ForumListState =
        httpClient.get(baseUrl + ApiRoutes.forums(projectId)).requireSuccess()

    /** Create a forum at the end of the project's list. Project administrator only. */
    override suspend fun createForum(projectId: Long, name: String, description: String?): ForumListState =
        httpClient.post(baseUrl + ApiRoutes.forums(projectId)) {
            contentType(ContentType.Application.Json)
            setBody(ForumEdit(name, description))
        }.requireSuccess()

    /** Rename and re-describe a forum. Project administrator only. */
    override suspend fun editForum(
        projectId: Long,
        forumId: Long,
        name: String,
        description: String?,
    ): ForumListState =
        httpClient.put(baseUrl + ApiRoutes.forum(projectId, forumId)) {
            contentType(ContentType.Application.Json)
            setBody(ForumEdit(name, description))
        }.requireSuccess()

    /** Delete a forum. Project administrator only. */
    override suspend fun deleteForum(projectId: Long, forumId: Long): ForumListState =
        httpClient.delete(baseUrl + ApiRoutes.forum(projectId, forumId)).requireSuccess()

    /**
     * Put this project's forums in [ids] order. Project administrator only.
     *
     * @param ids the whole list, not a move: the server refuses an order that is
     *   not exactly this project's forums rather than applying the part it
     *   understands — see [ForumOrder].
     */
    override suspend fun reorderForums(projectId: Long, ids: List<Long>): ForumListState =
        httpClient.post(baseUrl + ApiRoutes.forumOrder(projectId)) {
            contentType(ContentType.Application.Json)
            setBody(ForumOrder(ids))
        }.requireSuccess()

    // ── Forum posts and comments ─────────────────────────────────────────────

    /** A forum's posts, newest first, and whether this caller may add one. */
    override suspend fun forumPosts(projectId: Long, forumId: Long): ForumPostListState =
        httpClient.get(baseUrl + ApiRoutes.forumPosts(projectId, forumId)).requireSuccess()

    /**
     * Start a post: create the empty row the composer writes into.
     *
     * Answers with the id alone, and nothing is visible to anyone until
     * [publishForumPost]. The two-step shape is what lets an image be uploaded
     * before the body exists — see ApiRoutes.forumPosts.
     */
    override suspend fun createForumPostDraft(projectId: Long, forumId: Long): ForumDraftRef =
        httpClient.post(baseUrl + ApiRoutes.forumPosts(projectId, forumId)).requireSuccess()

    /** One post, its comments, and who may be @mentioned in it. */
    override suspend fun forumPost(projectId: Long, forumId: Long, postId: Long): ForumPostDetail =
        httpClient.get(baseUrl + ApiRoutes.forumPost(projectId, forumId, postId)).requireSuccess()

    /** Publish a post, or re-save it. The author's own. */
    override suspend fun publishForumPost(
        projectId: Long,
        forumId: Long,
        postId: Long,
        title: String,
        body: String,
    ): ForumPostDetail =
        httpClient.put(baseUrl + ApiRoutes.forumPost(projectId, forumId, postId)) {
            contentType(ContentType.Application.Json)
            setBody(ForumPostEdit(title, body))
        }.requireSuccess()

    /**
     * Delete a post and everything under it.
     *
     * Answers with the forum's refreshed post *list* rather than the post, which
     * is deliberate: the post is gone, and whoever deleted it is looking at a
     * forum next. Also the author, a project administrator, or a system one — see
     * `AccessControl.canDeleteForumContent`.
     */
    override suspend fun deleteForumPost(projectId: Long, forumId: Long, postId: Long): ForumPostListState =
        httpClient.delete(baseUrl + ApiRoutes.forumPost(projectId, forumId, postId)).requireSuccess()

    /** Start a comment: the empty row the composer writes into. */
    override suspend fun createForumCommentDraft(projectId: Long, forumId: Long, postId: Long): ForumDraftRef =
        httpClient.post(baseUrl + ApiRoutes.forumComments(projectId, forumId, postId)).requireSuccess()

    /** Publish a comment. Answers with the whole post again, comments included. */
    override suspend fun publishForumComment(
        projectId: Long,
        forumId: Long,
        postId: Long,
        commentId: Long,
        body: String,
    ): ForumPostDetail =
        httpClient.put(baseUrl + ApiRoutes.forumComment(projectId, forumId, postId, commentId)) {
            contentType(ContentType.Application.Json)
            setBody(ForumCommentEdit(body))
        }.requireSuccess()

    /** Delete a comment. Answers with the whole post again. */
    override suspend fun deleteForumComment(
        projectId: Long,
        forumId: Long,
        postId: Long,
        commentId: Long,
    ): ForumPostDetail =
        httpClient.delete(baseUrl + ApiRoutes.forumComment(projectId, forumId, postId, commentId))
            .requireSuccess()

    /**
     * Watch or unwatch a forum: e-mail me when somebody posts here.
     *
     * @return the refreshed [ForumPostListState], so the pill re-renders from the
     *   server's truth rather than from what the click assumed.
     * @throws ApiFailure 403 if the caller has no e-mail address to send to.
     */
    override suspend fun setForumNotification(projectId: Long, forumId: Long, subscribed: Boolean): ForumPostListState =
        httpClient.post(baseUrl + ApiRoutes.forumNotification(projectId, forumId)) {
            contentType(ContentType.Application.Json)
            setBody(NotificationSubscriptionRequest(subscribed))
        }.requireSuccess()

    /**
     * Watch or unwatch one post: e-mail me about new comments.
     *
     * @return the refreshed [ForumPostDetail]. Same refusal as
     *   [setForumNotification].
     */
    override suspend fun setForumPostNotification(
        projectId: Long,
        forumId: Long,
        postId: Long,
        subscribed: Boolean,
    ): ForumPostDetail =
        httpClient.post(baseUrl + ApiRoutes.forumPostNotification(projectId, forumId, postId)) {
            contentType(ContentType.Application.Json)
            setBody(NotificationSubscriptionRequest(subscribed))
        }.requireSuccess()

    /**
     * Record that the caller has read this post, and get the refreshed list back.
     *
     * A `POST` rather than a side effect of [forumPost], and what counts as having
     * "read" a post is decided by the *caller* rather than by the fetch — see the
     * server's `forumPostRoutes`, which spells out what viewing means when the
     * window a post opens in is reused across posts.
     */
    override suspend fun markForumPostRead(projectId: Long, forumId: Long, postId: Long): ForumPostListState =
        httpClient.post(baseUrl + ApiRoutes.forumPostRead(projectId, forumId, postId)).requireSuccess()

    /**
     * Whether anything in the Discussion tab is new to the caller.
     *
     * Instance-wide rather than per-project, because the badge it drives is on the
     * tab strip. Never 401s: signed out is `false`. See ApiRoutes.DISCUSSION_UNREAD.
     */
    override suspend fun discussionUnread(): DiscussionUnreadState =
        httpClient.get(baseUrl + ApiRoutes.DISCUSSION_UNREAD).requireSuccess()

    // ── In-app notifications (LNL-109) ───────────────────────────────────────

    /**
     * The bell's poll: the caller's unread count alone.
     *
     * One indexed count, never the list — its own route so the five-minute poll
     * stays cheap. Never 401s: signed out is zero. See ApiRoutes.NOTIFICATIONS_UNREAD_COUNT.
     */
    override suspend fun notificationsUnreadCount(): NotificationCountState =
        httpClient.get(baseUrl + ApiRoutes.NOTIFICATIONS_UNREAD_COUNT).requireSuccess()

    /** The panel's list: the caller's notifications, newest first, with the count. */
    override suspend fun notifications(): NotificationListState =
        httpClient.get(baseUrl + ApiRoutes.NOTIFICATIONS).requireSuccess()

    /** Mark one notification read; answers with the refreshed list. */
    override suspend fun markNotificationRead(id: Long): NotificationListState =
        httpClient.post(baseUrl + ApiRoutes.notificationRead(id)).requireSuccess()

    /** Mark all notifications read; answers with the refreshed list. */
    override suspend fun markAllNotificationsRead(): NotificationListState =
        httpClient.post(baseUrl + ApiRoutes.NOTIFICATIONS_READ_ALL).requireSuccess()

    /** Dismiss (hard-delete) one notification; answers with the refreshed list. */
    override suspend fun dismissNotification(id: Long): NotificationListState =
        httpClient.delete(baseUrl + ApiRoutes.notification(id)).requireSuccess()

    /** Clear all the caller's notifications; answers with the now-empty list. */
    override suspend fun clearNotifications(): NotificationListState =
        httpClient.delete(baseUrl + ApiRoutes.NOTIFICATIONS).requireSuccess()

    // ── Private messages ─────────────────────────────────────────────────────

    /**
     * The caller's conversations, and who they may start one with.
     *
     * Never 401s: signed out is an empty list with `canMessage` false, matching
     * [session] and for the same reason — the Messages tab asks this before it
     * knows whether anyone is signed in.
     */
    override suspend fun conversations(): ConversationListState =
        httpClient.get(baseUrl + ApiRoutes.CONVERSATIONS).requireSuccess()

    /**
     * Start a conversation with [participantIds], and get back the empty pair the
     * composer writes into.
     *
     * **Membership is fixed by this call.** There is no way to add anybody
     * afterwards — LNL-30 settles that — so this is the moment the recipient list
     * stops being editable, which is why the composer delays it until there is
     * something that needs a row. See `MessageComposerBackingViewModel`.
     *
     * The caller is not in [participantIds] and must not be; the server adds them.
     */
    override suspend fun startConversation(participantIds: List<Long>): ConversationDraft =
        httpClient.post(baseUrl + ApiRoutes.CONVERSATIONS) {
            contentType(ContentType.Application.Json)
            setBody(ConversationStart(participantIds))
        }.requireSuccess()

    /** One conversation, everything said in it, and who may be @mentioned. */
    override suspend fun conversation(id: Long): ConversationDetail =
        httpClient.get(baseUrl + ApiRoutes.conversation(id)).requireSuccess()

    /**
     * Throw away a conversation that was started and never sent — the composer's
     * Cancel, and the only thing that deletes a conversation.
     *
     * Answers with the refreshed *list*, not the conversation, which is gone.
     * Refused with 409 if anything has since been published in it.
     */
    override suspend fun discardConversation(id: Long): ConversationListState =
        httpClient.delete(baseUrl + ApiRoutes.conversation(id)).requireSuccess()

    /**
     * Record that the caller has read this conversation, up to whatever is in it.
     *
     * Answers with the refreshed *list*, because the count it clears is on a list
     * row. The high-water mark is chosen server-side rather than sent: see
     * ApiRoutes.conversationRead.
     */
    override suspend fun markConversationRead(id: Long): ConversationListState =
        httpClient.post(baseUrl + ApiRoutes.conversationRead(id)).requireSuccess()

    /** Start a reply: the empty row the composer writes into. */
    override suspend fun createMessageDraft(conversationId: Long): ConversationDraft =
        httpClient.post(baseUrl + ApiRoutes.conversationMessages(conversationId)).requireSuccess()

    /**
     * Publish a message. Answers with the whole conversation again.
     *
     * Publish, never re-save: unlike [publishForumPost] there is no editing a
     * message once it is sent, for anybody. See the server's Messages.sq.
     */
    override suspend fun publishMessage(conversationId: Long, messageId: Long, body: String): ConversationDetail =
        httpClient.put(baseUrl + ApiRoutes.conversationMessage(conversationId, messageId)) {
            contentType(ContentType.Application.Json)
            setBody(MessageEdit(body))
        }.requireSuccess()

    /** Delete a message. The author, or a system administrator. */
    override suspend fun deleteMessage(conversationId: Long, messageId: Long): ConversationDetail =
        httpClient.delete(baseUrl + ApiRoutes.conversationMessage(conversationId, messageId))
            .requireSuccess()

    /** Grant or revoke one role for one user in one project. Admin only. */
    override suspend fun setProjectRole(
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
    override suspend fun board(projectId: Long): BoardState =
        httpClient.get(baseUrl + ApiRoutes.board(projectId)).requireSuccess()

    /**
     * Resolve the embed's `?project=<name>` to a board.
     *
     * @throws ApiFailure 404 when no such project exists *or* when it exists and
     *   this caller may not read it. The two are deliberately indistinguishable:
     *   answering "that project is private" would confirm it exists, which is
     *   the thing being withheld.
     */
    override suspend fun boardByName(name: String): BoardState =
        httpClient.get(baseUrl + ApiRoutes.BOARD_BY_NAME) {
            parameter("name", name)
        }.requireSuccess()

    // ── Issues ───────────────────────────────────────────────────────────────

    /** Create the hidden draft an inline image can be attached to. */
    override suspend fun createIssueDraft(projectId: Long): IssueDraft =
        httpClient.post(baseUrl + ApiRoutes.issues(projectId)).requireSuccess()

    override suspend fun issue(id: Long): IssueDetail =
        httpClient.get(baseUrl + ApiRoutes.issue(id)).requireSuccess()

    /**
     * Subscribe or unsubscribe the caller from this issue's update e-mails.
     *
     * @return the refreshed [IssueDetail], so the toggle re-renders from the
     *   server's truth.
     * @throws ApiFailure 403 if the caller has no e-mail address to send to.
     */
    override suspend fun setIssueNotification(id: Long, subscribed: Boolean): IssueDetail =
        httpClient.post(baseUrl + ApiRoutes.issueNotification(id)) {
            contentType(ContentType.Application.Json)
            setBody(NotificationSubscriptionRequest(subscribed))
        }.requireSuccess()

    /**
     * Assign an issue, or leave it to nobody.
     *
     * @param assigneeId who takes it, or null to unassign. The button that calls
     *   this only ever sends the caller's own id or null; naming anybody else
     *   needs edit rights on the issue, and the server is what decides that.
     * @return the refreshed [IssueDetail], so the read face re-renders from the
     *   server's truth rather than from what the click assumed.
     * @throws ApiFailure 403 if the caller may not be assigned issues here.
     */
    override suspend fun setIssueAssignee(id: Long, assigneeId: Long?): IssueDetail =
        httpClient.post(baseUrl + ApiRoutes.issueAssignee(id)) {
            contentType(ContentType.Application.Json)
            setBody(IssueAssignment(assigneeId))
        }.requireSuccess()

    /** Publish a draft, or save an edit to a published issue. Same call either way. */
    override suspend fun saveIssue(id: Long, update: IssueUpdate): IssueDetail =
        httpClient.put(baseUrl + ApiRoutes.issue(id)) {
            contentType(ContentType.Application.Json)
            setBody(update)
        }.requireSuccess()

    /**
     * Move an issue to another column.
     *
     * @param resolutionId why it is being closed. Required when the target column
     *   demands one — the server refuses the move otherwise, which is why the
     *   board asks before it sends. See BoardRoutes' resolveResolution.
     * @param fixedVersionId which release it was fixed in, when the resolution
     *   dialog collected one for a done resolution (LNL-134). Required when the
     *   project demands it and the resolution is done — the board asks in the same
     *   dialog before it sends. See BoardRoutes' resolveFixedVersion.
     */
    override suspend fun setIssueStatus(id: Long, statusId: Long, resolutionId: Long?, fixedVersionId: Long?) {
        httpClient.post(baseUrl + ApiRoutes.issueStatus(id)) {
            contentType(ContentType.Application.Json)
            setBody(StatusUpdate(statusId, resolutionId, fixedVersionId))
        }.requireSuccessNoBody()
    }

    /**
     * Schedule an issue into a sprint, or send it back to the backlog.
     *
     * The card menu's write, and the one the editor does *not* use — the editor
     * sends the sprint inside [IssueUpdate] with everything else, so that Cancel
     * discards it. See ApiRoutes.issueSprint.
     */
    override suspend fun setIssueSprint(id: Long, sprintId: Long?): IssueDetail =
        httpClient.post(baseUrl + ApiRoutes.issueSprint(id)) {
            contentType(ContentType.Application.Json)
            setBody(IssueSprintUpdate(sprintId))
        }.requireSuccess()

    /**
     * Attach an issue to an epic, or detach it with null (LNL-55).
     *
     * @param id the child whose parent is changing — the epic-side "add" gesture
     *   still posts to the child. See ApiRoutes.issueParent.
     * @return the refreshed [IssueDetail], so both the child's parent chip and any
     *   epic's children list re-render from the server's truth.
     * @throws ApiFailure 400 when the parent breaks a rule (wrong project, already
     *   a child, would cycle) — the message says which.
     */
    override suspend fun setIssueParent(id: Long, parentId: Long?): IssueDetail =
        httpClient.post(baseUrl + ApiRoutes.issueParent(id)) {
            contentType(ContentType.Application.Json)
            setBody(IssueParentUpdate(parentId))
        }.requireSuccess()

    /**
     * Rank one epic's children, first to last (LNL-55).
     *
     * @param id the epic. @param childIds its whole ordered set of children — the
     *   server proves it is exactly that before writing.
     * @return the refreshed [IssueDetail], so the reordered list re-renders from
     *   the server's truth.
     */
    override suspend fun reorderChildren(id: Long, childIds: List<Long>): IssueDetail =
        httpClient.post(baseUrl + ApiRoutes.issueChildrenOrder(id)) {
            contentType(ContentType.Application.Json)
            setBody(ChildOrder(childIds))
        }.requireSuccess()

    /**
     * Make a sprint the one the board scopes to, or pass null for none.
     *
     * Returns the whole refreshed board, not the sprint: activating changes which
     * issues are in scope, which is the only reason anybody called this.
     */
    override suspend fun activateSprint(projectId: Long, sprintId: Long?): BoardState =
        httpClient.post(baseUrl + ApiRoutes.sprintActivation(projectId)) {
            contentType(ContentType.Application.Json)
            setBody(SprintActivation(sprintId))
        }.requireSuccess()

    /** Finish a sprint, rolling unfinished work to [moveUnfinishedTo] or the backlog. */
    override suspend fun completeSprint(projectId: Long, sprintId: Long, moveUnfinishedTo: Long?): BoardState =
        httpClient.post(baseUrl + ApiRoutes.sprintCompletion(projectId, sprintId)) {
            contentType(ContentType.Application.Json)
            setBody(SprintCompletion(moveUnfinishedTo))
        }.requireSuccess()

    /** Set exactly which issues are in a sprint — the planning dialog's save. */
    override suspend fun setSprintIssues(projectId: Long, sprintId: Long, issueIds: List<Long>): BoardState =
        httpClient.post(baseUrl + ApiRoutes.sprintIssues(projectId, sprintId)) {
            contentType(ContentType.Application.Json)
            setBody(SprintMembership(issueIds))
        }.requireSuccess()

    /** Delete an issue. Also what Cancel does to a draft. */
    override suspend fun deleteIssue(id: Long) {
        httpClient.delete(baseUrl + ApiRoutes.issue(id)).requireSuccessNoBody()
    }

    /**
     * Rank a board group, first to last.
     *
     * @param id the issue that was dragged; the server authorises against it.
     * @param issueIds the whole group in its new order. The server proves they
     *   really are one group before writing — see BoardRoutes.
     * @param priorityId the priority the dragged issue should take, when the drop
     *   moved it into another group of the same column. Null leaves it alone,
     *   which is every drop within one group. See [IssueOrderUpdate].
     */
    override suspend fun setIssueOrder(id: Long, issueIds: List<Long>, priorityId: Long?) {
        httpClient.post(baseUrl + ApiRoutes.issueOrder(id)) {
            contentType(ContentType.Application.Json)
            setBody(IssueOrderUpdate(issueIds, priorityId))
        }.requireSuccessNoBody()
    }

    // ── Comments ─────────────────────────────────────────────────────────────

    override suspend fun createCommentDraft(issueId: Long): CommentDraft =
        httpClient.post(baseUrl + ApiRoutes.comments(issueId)).requireSuccess()

    override suspend fun saveComment(id: Long, body: String) {
        httpClient.put(baseUrl + ApiRoutes.comment(id)) {
            contentType(ContentType.Application.Json)
            setBody(CommentUpdate(body))
        }.requireSuccessNoBody()
    }

    override suspend fun deleteComment(id: Long) {
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
    override suspend fun uploadIssueAttachment(
        issueId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef = uploadAttachment(ApiRoutes.issueAttachments(issueId), filename, mimeType, bytes)

    /** As [uploadIssueAttachment], for a comment. */
    override suspend fun uploadCommentAttachment(
        commentId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef = uploadAttachment(ApiRoutes.commentAttachments(commentId), filename, mimeType, bytes)

    /** As [uploadIssueAttachment], for a forum post. */
    override suspend fun uploadForumPostAttachment(
        postId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef = uploadAttachment(ApiRoutes.forumPostAttachments(postId), filename, mimeType, bytes)

    /** As [uploadIssueAttachment], for a forum comment. */
    override suspend fun uploadForumCommentAttachment(
        commentId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef =
        uploadAttachment(ApiRoutes.forumCommentAttachments(commentId), filename, mimeType, bytes)

    /** As [uploadIssueAttachment], for a private message. */
    override suspend fun uploadMessageAttachment(
        messageId: Long,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ): AttachmentRef = uploadAttachment(ApiRoutes.messageAttachments(messageId), filename, mimeType, bytes)

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
