package se.soderbjorn.lunicle

import se.soderbjorn.lunula.core.Appearance
import se.soderbjorn.lunula.core.DEFAULT_DARK_THEME
import se.soderbjorn.lunula.core.ThemeSnapshotV2
import se.soderbjorn.lunicle.demo.DEMO_DEFAULT_APPEARANCE
import se.soderbjorn.lunicle.demo.DEMO_DEFAULT_DARK_THEME
import se.soderbjorn.lunicle.demo.DEMO_DEFAULT_LIGHT_THEME
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The embed's look hints, tested off the pure functions rather than through
 * [ThemePersister] (which needs an HTTP client to construct) or main.kt's readers
 * (which read window.location).
 *
 * The point of the parameters: a host embedding the tracker asks the signed-out
 * default off Lunicle's GitHub Light and onto its own look, so the frame does not
 * clash with the chrome around it. Two halves — `?theme=` picks the appearance
 * ([appearanceFromThemeParam]), and `?darkTheme=`/`?lightTheme=` name the theme
 * each slot lands on, which is the half that actually matches colours.
 */
class ThemeDefaultAppearanceTest {

    @Test
    fun the_three_appearance_names_map() {
        assertEquals(Appearance.Dark, appearanceFromThemeParam("dark"))
        assertEquals(Appearance.Light, appearanceFromThemeParam("light"))
        assertEquals(Appearance.Auto, appearanceFromThemeParam("auto"))
    }

    @Test
    fun absent_or_unknown_is_no_hint() {
        // null (parameter absent) and anything not an appearance name both yield
        // null, so the caller leaves Lunicle's own default untouched.
        assertNull(appearanceFromThemeParam(null))
        assertNull(appearanceFromThemeParam(""))
        assertNull(appearanceFromThemeParam("Dark"), "case-sensitive: only the toolkit's own spelling")
        assertNull(appearanceFromThemeParam("GitHub Dark"), "a theme name is not an appearance")
        assertNull(appearanceFromThemeParam("1"))
    }

    // ── the slot names the embed asks for ────────────────────────────────────
    //
    // ThemePersister feeds these through applyBrandDefaultSelectionJson in the
    // embed-over-brand position, so what is asserted here is the tier's behaviour:
    // it fills an unchosen slot and yields to a chosen one.

    private fun resolve(stored: String?, dark: String?, light: String? = null) =
        ThemeSnapshotV2.fromStrings(
            selectionJson = applyBrandDefaultSelectionJson(stored, dark, light),
            customThemesJson = null,
        )

    @Test
    fun the_embeds_slot_name_fills_an_unchosen_slot() {
        // Nothing stored ⇒ the host's theme, not Lunicle's GitHub Dark.
        assertEquals("Lunamux Dark", resolve(null, "Lunamux Dark").darkThemeName)
    }

    @Test
    fun a_users_explicit_choice_beats_the_embeds_slot_name() {
        val stored = ThemeSnapshotV2(darkThemeName = "Solarized Dark").selectionJson()
        assertEquals("Solarized Dark", resolve(stored, "Lunamux Dark").darkThemeName)
    }

    @Test
    fun no_embed_name_leaves_the_lunicle_default() {
        // An un-embedded load sends nothing, and must be unchanged by this feature.
        assertEquals(LUNICLE_DEFAULT_DARK_THEME, resolve(null, null).darkThemeName)
        assertEquals(LUNICLE_DEFAULT_LIGHT_THEME, resolve(null, null).lightThemeName)
    }

    // ── the demo's own look ──────────────────────────────────────────────────
    //
    // ?demo=1 has no brand dir to read a default off, so DemoWorld declares one and
    // main.kt feeds it through the same tier as the embed parameters, one rung below
    // them. Asserted here because the constants are only meaningful if they survive
    // that tier: a name it mistook for "unset" would be swung to GitHub.

    @Test
    fun the_demo_defaults_to_the_classic_lunamux_pair_dark_side_up() {
        val snap = resolve(null, DEMO_DEFAULT_DARK_THEME, DEMO_DEFAULT_LIGHT_THEME)
        assertEquals("Lunamux Classic Dark", snap.darkThemeName)
        assertEquals("Lunamux Classic Light", snap.lightThemeName)
        assertEquals(Appearance.Dark, DEMO_DEFAULT_APPEARANCE)
    }

    @Test
    fun a_theme_picked_in_the_demo_beats_the_demos_default() {
        // Demo edits live in the in-memory world until reload, theme included — so a
        // stored selection must still win, exactly as a signed-in user's does.
        val stored = ThemeSnapshotV2(darkThemeName = "Solarized Dark").selectionJson()
        assertEquals("Solarized Dark", resolve(stored, DEMO_DEFAULT_DARK_THEME).darkThemeName)
    }

    @Test
    fun the_embed_can_ask_for_the_toolkits_own_default_theme() {
        // The Lunamux site's case specifically: "Lunamux Dark" IS the toolkit's
        // built-in sentinel (DEFAULT_DARK_THEME), which is what LNL-149 moved
        // Lunicle *off*. Asking for it by name must land on it rather than being
        // mistaken for "unset" and swung back to Lunicle's GitHub default.
        assertEquals(DEFAULT_DARK_THEME, "Lunamux Dark", "guards the premise of this test")
        assertEquals("Lunamux Dark", resolve(null, "Lunamux Dark").darkThemeName)
    }
}
