/**
 * The shell's shape — corner radius, density, surfaces, selection — follows the
 * signed-in account (LNA-26), and only the user's own picks are what follow it.
 *
 * Four properties, each pinned here because each would fail quietly:
 *
 *  - **The round-trip.** A shape the account stored is what the shell reads at
 *    mount, and a shape the shell writes while signed in reaches the server
 *    verbatim. That is the feature.
 *  - **Signed out stores nothing.** The write lands locally and is not sent, as
 *    for every other key.
 *  - **Defaults are not choices.** Booting an account that has picked nothing
 *    sends no shape: the brand's shape is a fallback the toolkit paints beneath
 *    the stored value, and nothing on this side seeds it.
 *  - **An identity change does not hand one person's shape to the next.** The
 *    shell cannot be told a new shape after mounting and re-writes the one on
 *    screen on every theme change, so without [ThemePersister]'s guard the first
 *    flick of the sun/moon control after signing in would overwrite the account's
 *    shape with whoever's was on screen before.
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.promise
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.clientserver.HttpLunicleApi
import se.soderbjorn.lunicle.clientserver.LunicleApi
import se.soderbjorn.lunicle.clientserver.UiSettingKeys
import se.soderbjorn.lunicle.clientserver.UiSettingsState
import se.soderbjorn.lunula.core.AppearanceShape
import se.soderbjorn.lunula.core.Looks
import se.soderbjorn.lunula.core.ModeStyles
import se.soderbjorn.lunula.core.SelectionStyle
import se.soderbjorn.lunula.core.SurfaceStyle
import se.soderbjorn.lunula.core.UiDensity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A settings endpoint with an account behind it that the test can swap, and a
 * record of every write that reached it.
 *
 * `by HttpLunicleApi(...)` supplies the members these tests never call, as
 * :client's own fakes do; the delegate points at a host that does not resolve, so
 * anything not modelled here fails loudly rather than reaching the network.
 *
 * Internal rather than private because FontsFollowTheAccountTest drives the same
 * account through the same persister.
 */
internal class AccountSettingsApi(
    var userId: Long?,
    var stored: Map<String, String> = emptyMap(),
) : LunicleApi by HttpLunicleApi(baseUrl = "http://shape-follows.invalid") {

    /** Every `(key, value)` the persister sent, in order. */
    val sent = mutableListOf<Pair<String, String>>()

    override suspend fun uiSettings(): UiSettingsState =
        UiSettingsState(userId = userId, settings = if (userId == null) emptyMap() else stored)

    override suspend fun setUiSetting(key: String, value: String) {
        sent += key to value
    }
}

class ShapeFollowsTheAccountTest {

    private val scope = CoroutineScope(Job())

    // Where the persister launches its fire-and-forget writes: a scope of its own,
    // so [settle] can wait for exactly those before anything is asserted about
    // what reached the server.
    private val writes = CoroutineScope(Job())

    private suspend fun settle() = writes.coroutineContext[Job]!!.children.toList().joinAll()

    private val squareAndRoomy = AppearanceShape(
        cornerRadiusPx = 0,
        uiDensity = UiDensity.Comfortable,
        selectionStyle = SelectionStyle.Fill,
        surfaceStyle = SurfaceStyle.Flat,
    ).toJson()

    private val roundish = AppearanceShape(cornerRadiusPx = 8).toJson()

    private fun AccountSettingsApi.persister() = ThemePersister(StorageRepository(this), writes)

    private suspend fun AccountSettingsApi.sentShapes(): List<String> {
        settle()
        return sent.filter { it.first == UiSettingKeys.APPEARANCE_SHAPE }.map { it.second }
    }

    @Test
    fun the_stored_shape_is_what_the_shell_reads_at_mount() = scope.promise {
        val api = AccountSettingsApi(userId = 7, stored = mapOf(UiSettingKeys.APPEARANCE_SHAPE to squareAndRoomy))
        val persister = api.persister()
        persister.start()

        assertEquals(squareAndRoomy, persister.read(UiSettingKeys.APPEARANCE_SHAPE))
    }

    @Test
    fun a_shape_picked_while_signed_in_is_sent_verbatim() = scope.promise {
        val api = AccountSettingsApi(userId = 7, stored = mapOf(UiSettingKeys.THEME_SELECTION to "{}"))
        val persister = api.persister()
        persister.start()

        persister.write(UiSettingKeys.APPEARANCE_SHAPE, squareAndRoomy)

        assertEquals(listOf(squareAndRoomy), api.sentShapes())
    }

    @Test
    fun a_shape_picked_while_signed_out_is_kept_for_the_tab_and_not_sent() = scope.promise {
        val api = AccountSettingsApi(userId = null)
        val persister = api.persister()
        persister.start()

        persister.write(UiSettingKeys.APPEARANCE_SHAPE, squareAndRoomy)

        assertEquals(squareAndRoomy, persister.read(UiSettingKeys.APPEARANCE_SHAPE))
        settle()
        assertTrue(api.sent.isEmpty(), "A signed-out shape was sent: ${api.sent}")
    }

    @Test
    fun booting_an_account_that_picked_nothing_sends_no_shape() = scope.promise {
        // A branded instance included: the brand's shape is handed to the toolkit
        // as a fallback (AppShellSpec.defaultAppearanceShape), never to this store.
        val api = AccountSettingsApi(userId = 7)
        val persister = api.persister()
        persister.setBrand(emptyList(), defaultDark = null, defaultLight = null)
        persister.start()

        val sent = api.sentShapes()
        assertTrue(sent.isEmpty(), "A default was stored as if it were a choice: $sent")
    }

    @Test
    fun the_previous_identitys_shape_is_not_written_to_the_next() = scope.promise {
        // Signed in as one account, which squared its corners...
        val api = AccountSettingsApi(
            userId = 7,
            stored = mapOf(
                UiSettingKeys.THEME_SELECTION to "{}",
                UiSettingKeys.APPEARANCE_SHAPE to squareAndRoomy,
            ),
        )
        val persister = api.persister()
        persister.start()

        // ...then the browser becomes another account, which likes them round.
        api.userId = 8
        api.stored = mapOf(
            UiSettingKeys.THEME_SELECTION to "{}",
            UiSettingKeys.APPEARANCE_SHAPE to roundish,
        )
        persister.onIdentityChanged()
        settle()
        api.sent.clear()

        // The shell still paints the first account's shape and re-writes it with
        // the next theme change. That is not the second account choosing it.
        persister.write(UiSettingKeys.APPEARANCE_SHAPE, squareAndRoomy)
        val stale = api.sentShapes()
        assertTrue(stale.isEmpty(), "One account's shape was written to another: $stale")

        // A pick the user actually makes is theirs, and from then on stored.
        val picked = AppearanceShape(cornerRadiusPx = 4).toJson()
        persister.write(UiSettingKeys.APPEARANCE_SHAPE, picked)
        persister.write(UiSettingKeys.APPEARANCE_SHAPE, squareAndRoomy)
        assertEquals(listOf(picked, squareAndRoomy), api.sentShapes())
    }

    @Test
    fun a_visitors_own_pick_is_adopted_by_an_empty_account() = scope.promise {
        // The shape half of onIdentityChanged's adoption: picked signed out, then
        // signed in to an account that has stored nothing — kept, as the theme is.
        val api = AccountSettingsApi(userId = null)
        val persister = api.persister()
        persister.start()
        persister.write(UiSettingKeys.APPEARANCE_SHAPE, squareAndRoomy)

        api.userId = 9
        persister.onIdentityChanged()

        assertEquals(listOf(squareAndRoomy), api.sentShapes())
    }

    /**
     * The guard compares the whole blob, per-mode entries included: after an
     * identity change, a write that changes only one appearance mode's style is
     * the user choosing, not the toolkit re-writing stale state, and reaches the
     * account. Parsing the blob as a plain [AppearanceShape] would see no change
     * at all and hold it back.
     */
    @Test
    fun a_change_to_one_modes_style_after_an_identity_change_is_sent() = scope.promise {
        val api = AccountSettingsApi(
            userId = 7,
            stored = mapOf(
                UiSettingKeys.THEME_SELECTION to "{}",
                UiSettingKeys.APPEARANCE_SHAPE to squareAndRoomy,
            ),
        )
        val persister = api.persister()
        persister.start()

        api.userId = 8
        api.stored = mapOf(UiSettingKeys.THEME_SELECTION to "{}", UiSettingKeys.APPEARANCE_SHAPE to roundish)
        persister.onIdentityChanged()
        settle()
        api.sent.clear()

        // The stale blob, re-written: held back.
        persister.write(UiSettingKeys.APPEARANCE_SHAPE, squareAndRoomy)
        assertTrue(api.sentShapes().isEmpty())

        // The same top-level fields, plus a dark-mode style the user just picked.
        val darkPicked = ModeStyles.shapeBlob(
            AppearanceShape.fromJson(squareAndRoomy),
            ModeStyles(dark = Looks.Calm.shape),
        )
        persister.write(UiSettingKeys.APPEARANCE_SHAPE, darkPicked)
        assertEquals(listOf(darkPicked), api.sentShapes())
    }
}
