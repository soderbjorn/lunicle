/**
 * The two routes behind "my theme is still my theme on the next machine".
 *
 * Their own file rather than two more entries in AuthRoutes, because they are
 * not about signing in — they only share the question of *who is asking*, and
 * that question is answered by `resolveCaller` for every route in the server.
 *
 * Both act on the **effective** caller, like the display-name route and the MCP
 * toggle: an admin who is impersonating someone sees and sets that someone's
 * theme. That is consistent with wearing their face everywhere else, and it is
 * the reading that makes impersonation useful for "what does this user actually
 * see?".
 *
 * @see UiSettingsStore
 * @see se.soderbjorn.lunicle.clientserver.UiSettingKeys
 */
package se.soderbjorn.lunicle

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.SetUiSettingRequest
import se.soderbjorn.lunicle.clientserver.UiSettingKeys
import se.soderbjorn.lunicle.clientserver.UiSettingsState

/**
 * The biggest blob this will store, in characters.
 *
 * A theme snapshot is a few hundred characters and a pile of custom themes a few
 * thousand, so this is roughly a hundred times the realistic worst case — large
 * enough that no honest client will ever meet it, small enough that a signed-in
 * browser cannot quietly fill the volume through a settings endpoint. The limit
 * exists because this is the one route whose body Lunicle stores verbatim
 * without understanding it: everything else that reaches the database went
 * through a parser first that would have rejected nonsense long before length
 * mattered.
 */
private const val MAX_SETTING_LENGTH = 256 * 1024

/**
 * Mount the shell-settings routes.
 *
 * @param sessions, impersonation what `resolveCaller` needs — passed rather than
 *   reached for, so this file has no opinion about how a cookie becomes a user.
 * @param access the permission oracle `resolveCaller` asks whether this caller may
 *   arm an impersonation. Nullable for the reason it is nullable there: null answers
 *   "nobody may", which is the direction that withholds authority, and is what the
 *   one test mounting this file alone gets.
 * @param uiSettings where the blobs live.
 */
fun Route.uiSettingsRoutes(
    sessions: se.soderbjorn.lunicle.store.SessionStore,
    impersonation: OwnerImpersonation,
    uiSettings: se.soderbjorn.lunicle.store.UiSettingsStore,
    access: AccessControl? = null,
) {
    /**
     * What the shell should paint with.
     *
     * Does not 401, for the reason ApiRoutes.USER_UI_SETTINGS gives: the shell
     * mounts before the session has resolved and asks this on the way, so signed
     * out has to be an answer rather than a failure. It is the empty state,
     * which is exactly what a signed-in account that has chosen nothing returns
     * too — the client's fallback is the same in both cases.
     *
     * The `userId` in the response is the load-bearing part. The shell is mounted
     * once and lives across sign-in, sign-out and impersonation; carrying who
     * these settings belong to is what lets the client notice that the person in
     * front of it changed, without asking a second endpoint.
     */
    get(ApiRoutes.USER_UI_SETTINGS) {
        val user = call.resolveCaller(sessions, impersonation, access).user
        if (user == null) {
            call.respond(UiSettingsState())
            return@get
        }
        call.respond(UiSettingsState(userId = user.id, settings = uiSettings.forUser(user.id)))
    }

    /**
     * Remember one of them.
     *
     * Three refusals, and each is a different fact:
     *  - **403** — nobody is signed in. There is no row to write, and answering
     *    200 would be telling a browser its preference was kept when the next
     *    load will disagree.
     *  - **400, unknown key** — the allowlist. See [UiSettingKeys]: this is a
     *    settings table, not storage the client may name its own keys in.
     *  - **400, too long** — [MAX_SETTING_LENGTH].
     *
     * No body on success. The caller wrote this value; handing it back would be
     * a round-trip that proves nothing, and this runs on every flick of the
     * dark/light control.
     */
    post(ApiRoutes.USER_UI_SETTINGS) {
        val user = call.resolveCaller(sessions, impersonation, access).user ?: run {
            call.respond(HttpStatusCode.Forbidden, "You must be signed in for settings to be remembered.")
            return@post
        }
        val request = runCatching { call.receive<SetUiSettingRequest>() }.getOrNull() ?: run {
            call.respond(HttpStatusCode.BadRequest, "Malformed request.")
            return@post
        }
        if (request.key !in UiSettingKeys.persisted) {
            call.respond(HttpStatusCode.BadRequest, "That is not a setting this server keeps.")
            return@post
        }
        if (request.value.length > MAX_SETTING_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, "That setting is too large to store.")
            return@post
        }
        uiSettings.put(user.id, request.key, request.value)
        call.respond(HttpStatusCode.NoContent)
    }
}
