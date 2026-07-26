/**
 * The theme really does follow the account, and only the account.
 *
 * Four properties, each of which would fail silently rather than loudly:
 *
 *  - **A round-trip.** Written by one session, read back by another for the same
 *    user. That is the whole feature — "my theme is still my theme on the next
 *    machine" — and nothing in a browser distinguishes it from an in-memory
 *    persister until the day someone signs in somewhere else.
 *  - **Isolation.** One user's theme is not another's. The table is keyed on the
 *    pair, so this pins the `WHERE user_id = ?` that makes the key mean anything.
 *  - **The allowlist.** An unknown key is refused rather than stored. Without it
 *    this is not a settings table but per-user storage any signed-in browser may
 *    name its own keys in — see UiSettingKeys.
 *  - **Signed out.** The read answers, the write refuses. Those are deliberately
 *    different: the shell asks for settings before it knows whether anyone is
 *    signed in, but a preference with no account to hang on is not something to
 *    accept and drop on the floor.
 *
 * Through the real routes with real session cookies, for AdminSettingsTest's
 * reason: a store-level test would pass just as happily against a route that
 * never consulted the session at all.
 *
 * @see uiSettingsRoutes
 */
package se.soderbjorn.lunicle

import io.ktor.client.call.body
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.SetUiSettingRequest
import se.soderbjorn.lunicle.clientserver.UiSettingKeys
import se.soderbjorn.lunicle.clientserver.UiSettingsState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/** A selection blob shaped like the toolkit's, though nothing here parses it. */
private const val LIGHT_SELECTION =
    """{"darkThemeName":"Lunamux Dark","lightThemeName":"Lunamux Light","appearance":"Light"}"""

class UiSettingsTest {
    private val file: File = Files.createTempFile("lunicle-ui-settings", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)
    private val sessions = SessionStore(database)
    private val uiSettings = UiSettingsStore(database)

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    /**
     * Write it in one session, read it back in another.
     *
     * Two cookies for the same user, because one cookie proves only that the
     * process remembered something for the length of a request. The second
     * session is the stand-in for the next browser.
     */
    @Test
    fun `a stored theme comes back for the same user in a new session`(): Unit = runBlocking {
        val user = user("gh-one", "One")
        val first = sessions.create(user.id)
        val second = sessions.create(user.id)

        withRoutes { client ->
            assertEquals(
                HttpStatusCode.NoContent,
                client.post(ApiRoutes.USER_UI_SETTINGS) {
                    cookie(SESSION_COOKIE, first)
                    contentType(ContentType.Application.Json)
                    setBody(SetUiSettingRequest(UiSettingKeys.THEME_SELECTION, LIGHT_SELECTION))
                }.status,
            )

            val state: UiSettingsState =
                client.get(ApiRoutes.USER_UI_SETTINGS) { cookie(SESSION_COOKIE, second) }.body()
            assertEquals(user.id, state.userId, "The settings came back without saying whose they are.")
            assertEquals(
                LIGHT_SELECTION,
                state.settings[UiSettingKeys.THEME_SELECTION],
                "The stored blob did not survive the round-trip verbatim.",
            )
        }
    }

    /**
     * The user's own themes travel with the selection.
     *
     * Not a second copy of the test above: the two keys are stored as one fact
     * for a reason — the selection names a custom theme by string, so a server
     * that kept the choice and dropped the themes would hand back a reference to
     * something that no longer exists. See ThemePersister.
     */
    @Test
    fun `hand-built themes are stored alongside the selection`(): Unit = runBlocking {
        val user = user("gh-themer", "Themer")
        val cookie = sessions.create(user.id)
        val custom = """[{"name":"Midnight","colors":{"bg":"#000000"}}]"""

        withRoutes { client ->
            client.post(ApiRoutes.USER_UI_SETTINGS) {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(SetUiSettingRequest(UiSettingKeys.THEME_SELECTION, LIGHT_SELECTION))
            }
            client.post(ApiRoutes.USER_UI_SETTINGS) {
                cookie(SESSION_COOKIE, cookie)
                contentType(ContentType.Application.Json)
                setBody(SetUiSettingRequest(UiSettingKeys.THEME_CUSTOM, custom))
            }

            val state: UiSettingsState =
                client.get(ApiRoutes.USER_UI_SETTINGS) { cookie(SESSION_COOKIE, cookie) }.body()
            assertEquals(custom, state.settings[UiSettingKeys.THEME_CUSTOM])
            assertEquals(LIGHT_SELECTION, state.settings[UiSettingKeys.THEME_SELECTION])
        }
    }

    /**
     * The board-preferences key (LNL-100) is allowlisted and stored verbatim.
     *
     * It is not a theme key and does not travel through the toolkit persister, so
     * this checks the one thing the route cares about — that it is on the
     * allowlist — and that the blob comes back exactly as sent, since the client is
     * the only thing that parses it.
     */
    @Test
    fun `project preferences round-trip through the settings route`(): Unit = runBlocking {
        val user = user("gh-prefs", "Prefs")
        val cookie = sessions.create(user.id)
        val blob = """{"byProject":{"2":{"hiddenColumnIds":[5,6]}}}"""

        withRoutes { client ->
            assertEquals(
                HttpStatusCode.NoContent,
                client.post(ApiRoutes.USER_UI_SETTINGS) {
                    cookie(SESSION_COOKIE, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(SetUiSettingRequest(UiSettingKeys.PROJECT_PREFS, blob))
                }.status,
            )

            val state: UiSettingsState =
                client.get(ApiRoutes.USER_UI_SETTINGS) { cookie(SESSION_COOKIE, cookie) }.body()
            assertEquals(blob, state.settings[UiSettingKeys.PROJECT_PREFS])
        }
    }

    /** Last write wins, and does not accumulate rows. */
    @Test
    fun `writing the same key twice replaces it`(): Unit = runBlocking {
        val user = user("gh-twice", "Twice")
        val cookie = sessions.create(user.id)

        withRoutes { client ->
            listOf(LIGHT_SELECTION, """{"appearance":"Auto"}""").forEach { blob ->
                client.post(ApiRoutes.USER_UI_SETTINGS) {
                    cookie(SESSION_COOKIE, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(SetUiSettingRequest(UiSettingKeys.THEME_SELECTION, blob))
                }
            }
        }

        assertEquals(
            mapOf(UiSettingKeys.THEME_SELECTION to """{"appearance":"Auto"}"""),
            uiSettings.forUser(user.id),
        )
    }

    /** One account's theme is not another's. */
    @Test
    fun `settings do not leak between users`(): Unit = runBlocking {
        val mine = user("gh-mine", "Mine")
        val theirs = user("gh-theirs", "Theirs")
        val myCookie = sessions.create(mine.id)
        val theirCookie = sessions.create(theirs.id)

        withRoutes { client ->
            client.post(ApiRoutes.USER_UI_SETTINGS) {
                cookie(SESSION_COOKIE, myCookie)
                contentType(ContentType.Application.Json)
                setBody(SetUiSettingRequest(UiSettingKeys.THEME_SELECTION, LIGHT_SELECTION))
            }

            val state: UiSettingsState =
                client.get(ApiRoutes.USER_UI_SETTINGS) { cookie(SESSION_COOKIE, theirCookie) }.body()
            assertEquals(theirs.id, state.userId)
            assertTrue(state.settings.isEmpty(), "One user was handed another's theme.")
        }
    }

    /**
     * A key nobody allowlisted is refused, and nothing is written.
     *
     * The store is checked directly afterwards, for the reason AdminSettingsTest
     * gives: a 400 that had already written would still be a 400.
     */
    @Test
    fun `an unknown key is refused`(): Unit = runBlocking {
        val user = user("gh-sneaky", "Sneaky")
        val cookie = sessions.create(user.id)

        withRoutes { client ->
            assertEquals(
                HttpStatusCode.BadRequest,
                client.post(ApiRoutes.USER_UI_SETTINGS) {
                    cookie(SESSION_COOKIE, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(SetUiSettingRequest("lunicle.notes", "a place to keep things"))
                }.status,
            )
        }

        assertTrue(
            uiSettings.forUser(user.id).isEmpty(),
            "A refused key was stored anyway, making this a scratch space rather than a settings table.",
        )
    }

    /**
     * Signed out: the read answers with nothing, the write is refused.
     *
     * The asymmetry is the point, so both halves are asserted together — a
     * later hand that made the `GET` 403 "for consistency" would break the boot
     * path of every signed-out visitor, and this says why in one place.
     */
    @Test
    fun `a signed-out caller is told nothing and may store nothing`(): Unit = runBlocking {
        withRoutes { client ->
            val state: UiSettingsState = client.get(ApiRoutes.USER_UI_SETTINGS).body()
            assertNull(state.userId, "A signed-out caller was given a user id.")
            assertTrue(state.settings.isEmpty())

            assertEquals(
                HttpStatusCode.Forbidden,
                client.post(ApiRoutes.USER_UI_SETTINGS) {
                    contentType(ContentType.Application.Json)
                    setBody(SetUiSettingRequest(UiSettingKeys.THEME_SELECTION, LIGHT_SELECTION))
                }.status,
                "A signed-out preference was accepted, with nowhere to put it.",
            )
        }
    }

    private suspend fun user(providerId: String, name: String) =
        users.upsert(ProviderIdentity(AuthProvider.GITHUB, providerId, name, null))

    private fun withRoutes(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing { uiSettingsRoutes(sessions, users, Impersonations(), uiSettings) }
        }
        val client = createClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
        }
        block(client)
    }
}
