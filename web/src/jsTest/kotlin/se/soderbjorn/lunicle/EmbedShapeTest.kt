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
 *    the toolkit's Classic / Both. A `?style=` hint names a whole look, whose
 *    values replace the brand's style fields.
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
import se.soderbjorn.lunula.core.ChromeStyle
import se.soderbjorn.lunula.core.FocusStyle
import se.soderbjorn.lunula.core.LabelCase
import se.soderbjorn.lunula.core.Looks
import se.soderbjorn.lunula.core.SurfaceStyle
import se.soderbjorn.lunula.core.WindowLayout
import se.soderbjorn.lunula.core.appearanceLadder
import se.soderbjorn.lunula.core.resolveAppearance
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
        assertEquals(ShellStyle.Calm, shellStyleFromParam("calm"))
        assertEquals(ShellStyle.Calm, shellStyleFromParam("clean"), "the look's old name, from links made before the rename")
        assertEquals(ShellStyle.Classic, shellStyleFromParam("classic"))
        assertEquals(ShellStyle.Calm, shellStyleFromParam(" Calm "), "typed by hand into a link")
    }

    @Test
    fun an_absent_or_unknown_style_is_no_hint() {
        assertNull(shellStyleFromParam(null))
        assertNull(shellStyleFromParam(""))
        assertNull(shellStyleFromParam("brutalist"))
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

    /**
     * The look the toolkit paints for [user]'s values over this persister's
     * default shape, through the toolkit's own resolution ladder: Classic or
     * Calm, or null for a combination that is neither.
     */
    private fun ThemePersister.paintedStyle(user: AppearanceShape): ShellStyle? {
        val resolved = resolveAppearance(*appearanceLadder(user, defaultAppearanceShape()))
        return when {
            Looks.Calm.matches(resolved) -> ShellStyle.Calm
            Looks.Classic.matches(resolved) -> ShellStyle.Classic
            else -> null
        }
    }

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
            AppearanceShape(shellStyle = ShellStyle.Calm, navigationMode = NavigationMode.Sidebar),
        )

        assertEquals(ShellStyle.Calm, persister.paintedStyle(AppearanceShape()))
        assertEquals(NavigationMode.Sidebar, persister.paintedNav(AppearanceShape()))
    }

    @Test
    fun the_urls_look_replaces_the_brands_style_and_its_nav_replaces_the_brands() = scope.promise {
        val persister = AccountSettingsApi(userId = null).persister()
        persister.setBrand(
            emptyList(), null, null,
            AppearanceShape(
                cornerRadiusPx = 2,
                uiDensity = UiDensity.Compact,
                shellStyle = ShellStyle.Calm,
                navigationMode = NavigationMode.Sidebar,
            ),
        )
        persister.setEmbedDefaults(null, null, null, ShellStyle.Classic, NavigationMode.TabStrip)

        val shape = persister.defaultAppearanceShape()
        assertEquals(NavigationMode.TabStrip, shape.navigationMode)
        // The link asked for the Classic look as a whole, the more specific claim:
        // the brand's own radius does not survive under it.
        assertEquals(Looks.Classic.shape.cornerRadiusPx, shape.cornerRadiusPx)
        assertEquals(ShellStyle.Classic, persister.paintedStyle(AppearanceShape()))
    }

    @Test
    fun without_a_url_hint_the_brands_fields_win_over_its_own_look() = scope.promise {
        val persister = AccountSettingsApi(userId = null).persister()
        persister.setBrand(
            emptyList(), null, null,
            AppearanceShape(cornerRadiusPx = 2, shellStyle = ShellStyle.Calm),
        )
        val resolved = resolveAppearance(*appearanceLadder(AppearanceShape(), persister.defaultAppearanceShape()))
        assertEquals(2, resolved.cornerRadiusPx, "a field named beside defaultStyle wins")
        assertEquals(Looks.Calm.shape.windowLayout, resolved.windowLayout, "the rest is the look's")
    }

    @Test
    fun the_users_pick_beats_the_url() = scope.promise {
        val stored = AppearanceShape(shellStyle = ShellStyle.Classic, navigationMode = NavigationMode.Both)
        val api = AccountSettingsApi(
            userId = 7,
            stored = mapOf(UiSettingKeys.APPEARANCE_SHAPE to stored.toJson()),
        )
        val persister = api.persister()
        persister.setBrand(emptyList(), null, null, AppearanceShape(shellStyle = ShellStyle.Calm))
        persister.setEmbedDefaults(null, null, null, ShellStyle.Calm, NavigationMode.TabStrip)
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
            AppearanceShape(shellStyle = ShellStyle.Calm, navigationMode = NavigationMode.Sidebar),
        )
        persister.setEmbedDefaults(null, null, null, ShellStyle.Calm, NavigationMode.TabStrip)
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
            api.sent.none { "calm" in it.second || "tabstrip" in it.second || "sidebar" in it.second },
            "A hint leaked into a stored setting: ${api.sent}",
        )
    }

    @Test
    fun the_users_own_pick_is_sent() = scope.promise {
        val api = AccountSettingsApi(userId = 7, stored = mapOf(UiSettingKeys.THEME_SELECTION to "{}"))
        val persister = api.persister()
        persister.setEmbedDefaults(null, null, null, ShellStyle.Calm, null)
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
            manifest("""{"defaultStyle": "calm", "defaultNavigation": "tabstrip", "defaultSelectionStyle": "fill"}"""),
        )
        assertEquals(ShellStyle.Calm, shape.shellStyle)
        assertEquals(NavigationMode.TabStrip, shape.navigationMode)
        assertEquals(SelectionStyle.Fill, shape.selectionStyle, "the existing keys still parse beside them")
    }

    @Test
    fun the_brand_names_each_style_setting() {
        val shape = brandAppearanceShape(
            manifest(
                """{"defaultSurfaceStyle": "soft", "defaultChromeStyle": "blended",
                   "defaultWindowLayout": "sheet", "defaultFocusStyle": "tone",
                   "defaultLabelCase": "sentence", "defaultSelectionStyle": "soft"}""",
            ),
        )
        assertEquals(SurfaceStyle.Soft, shape.surfaceStyle)
        assertEquals(ChromeStyle.Blended, shape.chromeStyle)
        assertEquals(WindowLayout.Sheet, shape.windowLayout)
        assertEquals(FocusStyle.Tone, shape.focusStyle)
        assertEquals(LabelCase.Sentence, shape.labelCase)
        assertEquals(SelectionStyle.Soft, shape.selectionStyle)
        val unknown = brandAppearanceShape(manifest("""{"defaultChromeStyle": "glass", "defaultFocusStyle": ""}"""))
        assertNull(unknown.chromeStyle)
        assertNull(unknown.focusStyle)
    }

    @Test
    fun unset_or_unknown_brand_keys_leave_the_toolkit_default() {
        val unset = brandAppearanceShape(manifest("""{"title": "Acme"}"""))
        assertTrue(unset.isEmpty)

        val typos = brandAppearanceShape(
            manifest("""{"defaultStyle": "brutalist", "defaultNavigation": "tabs", "defaultUiDensity": "compact"}"""),
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
