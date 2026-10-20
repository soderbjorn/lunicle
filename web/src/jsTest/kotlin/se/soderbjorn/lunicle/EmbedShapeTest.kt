/**
 * `?style=` and `?nav=` (LNA-26), and brand.json's `defaultStyle` /
 * `defaultNavigation` beneath them.
 *
 * Four properties, each pinned here:
 *
 *  - **Parsing.** Both parameters go through the toolkit's lenient `fromRaw`; an
 *    unknown value is no hint at all, so it changes nothing.
 *  - **Chrome wins over nav.** With `?chrome=minimal|none|bare` the navigation is
 *    gone, and a `?nav=` hint must not bring it back.
 *  - **Precedence.** The user's stored pick > the URL hint > the brand default >
 *    the toolkit's Classic / Both.
 *  - **Hints are not choices.** Neither the URL's nor the brand's style and
 *    navigation ever reach the account; only what the user picks does.
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.promise
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.clientserver.UiSettingKeys
import se.soderbjorn.lunula.core.AppearanceShape
import se.soderbjorn.lunula.core.NavigationMode
import se.soderbjorn.lunula.core.SelectionStyle
import se.soderbjorn.lunula.core.ShellStyle
import se.soderbjorn.lunula.core.ThemeSnapshotV2
import se.soderbjorn.lunula.core.UiDensity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbedShapeTest {

    // ── Parsing ──────────────────────────────────────────────────────────────

    @Test
    fun the_style_parameter_parses_both_styles_leniently() {
        assertEquals(ShellStyle.Clean, shellStyleFromParam("clean"))
        assertEquals(ShellStyle.Classic, shellStyleFromParam("classic"))
        assertEquals(ShellStyle.Clean, shellStyleFromParam(" Clean "), "typed by hand into a link")
    }

    @Test
    fun an_absent_or_unknown_style_is_no_hint() {
        assertNull(shellStyleFromParam(null))
        assertNull(shellStyleFromParam(""))
        assertNull(shellStyleFromParam("calm"))
    }

    @Test
    fun the_nav_parameter_parses_every_mode_leniently() {
        assertEquals(NavigationMode.Sidebar, navigationFromParam("sidebar", EmbedChrome.Full))
        assertEquals(NavigationMode.TabStrip, navigationFromParam("tabstrip", EmbedChrome.Full))
        assertEquals(NavigationMode.TabStrip, navigationFromParam("tab-strip", EmbedChrome.Full))
        assertEquals(NavigationMode.Both, navigationFromParam("BOTH", EmbedChrome.Full))
    }

    @Test
    fun an_absent_or_unknown_nav_is_no_hint() {
        assertNull(navigationFromParam(null, EmbedChrome.Full))
        assertNull(navigationFromParam("", EmbedChrome.Full))
        assertNull(navigationFromParam("tabs", EmbedChrome.Full))
    }

    // ── chrome × nav ─────────────────────────────────────────────────────────

    /**
     * Every chrome level against every nav value. Only the full app takes a hint;
     * the reduced levels drop it, and main.kt passes the same
     * [EmbedChrome.showsNavigation] as the toolkit's hard `showSidebar` /
     * `showTabStrip`, which no mode can override.
     */
    @Test
    fun nav_only_counts_when_the_chrome_shows_navigation() {
        val navs = listOf(null, "sidebar", "tabstrip", "both", "nonsense")
        for (chrome in EmbedChrome.entries) {
            for (nav in navs) {
                val hint = navigationFromParam(nav, chrome)
                if (chrome.showsNavigation) {
                    assertEquals(NavigationMode.fromRaw(nav), hint, "chrome=${chrome.key} nav=$nav")
                } else {
                    assertNull(hint, "chrome=${chrome.key} nav=$nav must not revive the navigation")
                }
            }
        }
    }

    @Test
    fun a_reduced_chrome_leaves_no_nav_in_the_default_shape() = scope.promise {
        val persister = AccountSettingsApi(userId = null).persister()
        persister.setBrand(emptyList(), null, null, AppearanceShape(navigationMode = NavigationMode.Sidebar))
        persister.setEmbedDefaults(null, null, null, navigation = navigationFromParam("tabstrip", EmbedChrome.Bare))

        // The brand's mode is still there (and still loses to the hard opt-out in the
        // toolkit), but the URL hint is not.
        assertEquals(NavigationMode.Sidebar, persister.defaultAppearanceShape().navigationMode)
    }

    // ── Precedence: user > URL > brand > toolkit ─────────────────────────────

    /** What the toolkit paints for a field: its own resolution, re-stated. */
    private fun ThemePersister.paintedStyle(user: AppearanceShape) =
        user.shellStyle ?: defaultAppearanceShape().shellStyle ?: ShellStyle.Default

    private fun ThemePersister.paintedNav(user: AppearanceShape) =
        user.navigationMode ?: defaultAppearanceShape().navigationMode ?: NavigationMode.Default

    @Test
    fun nothing_anywhere_is_classic_and_both() = scope.promise {
        val persister = AccountSettingsApi(userId = null).persister()
        persister.setEmbedDefaults(null, null, null)

        assertTrue(persister.defaultAppearanceShape().isEmpty)
        assertEquals(ShellStyle.Classic, persister.paintedStyle(AppearanceShape()))
        assertEquals(NavigationMode.Both, persister.paintedNav(AppearanceShape()))
    }

    @Test
    fun the_brand_beats_the_toolkit() = scope.promise {
        val persister = AccountSettingsApi(userId = null).persister()
        persister.setBrand(
            emptyList(), null, null,
            AppearanceShape(shellStyle = ShellStyle.Clean, navigationMode = NavigationMode.Sidebar),
        )

        assertEquals(ShellStyle.Clean, persister.paintedStyle(AppearanceShape()))
        assertEquals(NavigationMode.Sidebar, persister.paintedNav(AppearanceShape()))
    }

    @Test
    fun the_url_beats_the_brand_and_keeps_the_brands_other_fields() = scope.promise {
        val persister = AccountSettingsApi(userId = null).persister()
        persister.setBrand(
            emptyList(), null, null,
            AppearanceShape(
                cornerRadiusPx = 2,
                uiDensity = UiDensity.Compact,
                shellStyle = ShellStyle.Clean,
                navigationMode = NavigationMode.Sidebar,
            ),
        )
        persister.setEmbedDefaults(null, null, null, ShellStyle.Classic, NavigationMode.TabStrip)

        val shape = persister.defaultAppearanceShape()
        assertEquals(ShellStyle.Classic, shape.shellStyle)
        assertEquals(NavigationMode.TabStrip, shape.navigationMode)
        assertEquals(2, shape.cornerRadiusPx, "the URL has no say over the brand's radius")
        assertEquals(UiDensity.Compact, shape.uiDensity)
    }

    @Test
    fun the_users_pick_beats_the_url() = scope.promise {
        val stored = AppearanceShape(shellStyle = ShellStyle.Classic, navigationMode = NavigationMode.Both)
        val api = AccountSettingsApi(
            userId = 7,
            stored = mapOf(UiSettingKeys.APPEARANCE_SHAPE to stored.toJson()),
        )
        val persister = api.persister()
        persister.setBrand(emptyList(), null, null, AppearanceShape(shellStyle = ShellStyle.Clean))
        persister.setEmbedDefaults(null, null, null, ShellStyle.Clean, NavigationMode.TabStrip)
        persister.start()

        val user = AppearanceShape.fromJson(persister.read(UiSettingKeys.APPEARANCE_SHAPE))
        assertEquals(ShellStyle.Classic, persister.paintedStyle(user))
        assertEquals(NavigationMode.Both, persister.paintedNav(user))
    }

    // ── Hints are not choices ────────────────────────────────────────────────

    @Test
    fun url_and_brand_hints_never_reach_the_account() = scope.promise {
        val api = AccountSettingsApi(userId = 7)
        val persister = api.persister()
        persister.setBrand(
            emptyList(), null, null,
            AppearanceShape(shellStyle = ShellStyle.Clean, navigationMode = NavigationMode.Sidebar),
        )
        persister.setEmbedDefaults(null, null, null, ShellStyle.Clean, NavigationMode.TabStrip)
        persister.start()

        // The shell boots on what it read (nothing) and re-persists its own state —
        // which holds no style or navigation — on a flick of the sun/moon control.
        val mounted = persister.read(UiSettingKeys.APPEARANCE_SHAPE) ?: AppearanceShape().toJson()
        persister.write(UiSettingKeys.THEME_SELECTION, ThemeSnapshotV2().selectionJson())
        persister.write(UiSettingKeys.APPEARANCE_SHAPE, mounted)

        settle()
        val shapes = api.sent.filter { it.first == UiSettingKeys.APPEARANCE_SHAPE }
            .map { AppearanceShape.fromJson(it.second) }
        assertTrue(
            shapes.all { it.shellStyle == null && it.navigationMode == null },
            "A hint was stored as if it were a choice: $shapes",
        )
        assertTrue(
            api.sent.none { "clean" in it.second || "tabstrip" in it.second || "sidebar" in it.second },
            "A hint leaked into a stored setting: ${api.sent}",
        )
    }

    @Test
    fun the_users_own_pick_is_sent() = scope.promise {
        val api = AccountSettingsApi(userId = 7, stored = mapOf(UiSettingKeys.THEME_SELECTION to "{}"))
        val persister = api.persister()
        persister.setEmbedDefaults(null, null, null, ShellStyle.Clean, null)
        persister.start()

        val picked = AppearanceShape(shellStyle = ShellStyle.Classic, navigationMode = NavigationMode.TabStrip).toJson()
        persister.write(UiSettingKeys.APPEARANCE_SHAPE, picked)

        settle()
        assertEquals(
            listOf(picked),
            api.sent.filter { it.first == UiSettingKeys.APPEARANCE_SHAPE }.map { it.second },
        )
    }

    // ── brand.json ───────────────────────────────────────────────────────────

    private fun manifest(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun the_brand_names_its_style_and_navigation() {
        val shape = brandAppearanceShape(
            manifest("""{"defaultStyle": "clean", "defaultNavigation": "tabstrip", "defaultSelectionStyle": "fill"}"""),
        )
        assertEquals(ShellStyle.Clean, shape.shellStyle)
        assertEquals(NavigationMode.TabStrip, shape.navigationMode)
        assertEquals(SelectionStyle.Fill, shape.selectionStyle, "the existing keys still parse beside them")
    }

    @Test
    fun unset_or_unknown_brand_keys_leave_the_toolkit_default() {
        val unset = brandAppearanceShape(manifest("""{"title": "Acme"}"""))
        assertTrue(unset.isEmpty)

        val typos = brandAppearanceShape(
            manifest("""{"defaultStyle": "calm", "defaultNavigation": "tabs", "defaultUiDensity": "compact"}"""),
        )
        assertNull(typos.shellStyle)
        assertNull(typos.navigationMode)
        assertEquals(UiDensity.Compact, typos.uiDensity, "one typo must not cost the brand its other keys")
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(Job())
    private val writes = CoroutineScope(Job())

    private suspend fun settle() = writes.coroutineContext[Job]!!.children.toList().joinAll()

    private fun AccountSettingsApi.persister() = ThemePersister(StorageRepository(this), writes)
}
