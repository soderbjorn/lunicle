package se.soderbjorn.lunicle

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import se.soderbjorn.lunula.core.DEFAULT_DARK_THEME
import se.soderbjorn.lunula.core.DEFAULT_LIGHT_THEME
import se.soderbjorn.lunula.core.Theme
import se.soderbjorn.lunula.core.ThemeGroup
import se.soderbjorn.lunula.core.ThemeSnapshotV2
import se.soderbjorn.lunula.core.builtinTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A toolkit built-in used as a stand-in for "a theme a deployment names but
 * does not ship". Deliberately NOT one of Lunicle's own slot defaults: those
 * are also where an unresolvable name lands, which would make the assertion
 * pass whether the lookup actually worked or not.
 */
private const val BUILTIN_DEFAULT: String = "Nord Split"

/**
 * The pure behaviours [ThemePersister]'s brand handling hinges on (LNL-110),
 * tested directly off the internal helpers rather than through the store,
 * which needs an HTTP client to construct.
 *
 * The headline one is [strip_removes_only_brand_themes]: a brand theme that the
 * merge layered in, round-tripping back through a write, must never be persisted
 * — that is what keeps the whole feature out of the datastore.
 */
class BrandThemeMergeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val listSer = ListSerializer(Theme.serializer())

    private fun theme(name: String, group: ThemeGroup = ThemeGroup.Dark, accent: String = "#ff00ff") = Theme(
        name = name, group = group, tag = "t", desc = "d",
        bg = "#000000", surface = "#111111", surfaceAlt = "#222222", border = "#333333",
        text = "#ffffff", textDim = "#aaaaaa", textBright = "#ffffff", accent = accent,
        warn = "#ffcc00", danger = "#ff0000", add = "#00ff00", addText = "#000000",
        synKeyword = "#010101", synString = "#020202", synNumber = "#030303", synComment = "#040404",
        synFunction = "#050505", synType = "#060606", synOperator = "#070707", synConstant = "#080808",
    )

    private fun names(themesJson: String?): List<String> =
        themesJson?.let { json.decodeFromString(listSer, it).map { t -> t.name } } ?: emptyList()

    private fun encode(vararg t: Theme): String = json.encodeToString(listSer, t.toList())

    // ── merge ────────────────────────────────────────────────────────────────

    @Test
    fun merge_layers_brand_on_top_of_own() {
        val own = encode(theme("My Theme"))
        val merged = mergeBrandThemesJson(own, listOf(theme("Acme Dark"), theme("Acme Light", ThemeGroup.Light)))
        assertEquals(listOf("My Theme", "Acme Dark", "Acme Light"), names(merged))
    }

    @Test
    fun merge_with_no_brand_returns_input_unchanged() {
        val own = encode(theme("My Theme"))
        assertEquals(own, mergeBrandThemesJson(own, emptyList()))
        assertEquals(null, mergeBrandThemesJson(null, emptyList()))
    }

    @Test
    fun merge_lets_brand_win_a_name_collision() {
        val own = encode(theme("Acme Dark", accent = "#111111"))
        val merged = mergeBrandThemesJson(own, listOf(theme("Acme Dark", accent = "#ff2fd0")))
        val themes = json.decodeFromString(listSer, merged!!)
        assertEquals(1, themes.size, "collision de-duplicated to one")
        assertEquals("#ff2fd0", themes.single().accent, "the brand definition wins")
    }

    // ── strip (the non-persistence guarantee) ─────────────────────────────────

    @Test
    fun strip_removes_only_brand_themes() {
        val brand = listOf(theme("Acme Dark"), theme("Acme Light", ThemeGroup.Light))
        val merged = mergeBrandThemesJson(encode(theme("My Theme")), brand)!!
        // What the toolkit would hand back to write() carries the brand themes;
        // strip must leave only the user's own for persistence.
        assertEquals(listOf("My Theme"), names(stripBrandThemesJson(merged, brand)))
    }

    @Test
    fun merge_then_strip_is_the_original_own_set() {
        val brand = listOf(theme("Acme Dark"))
        val own = encode(theme("Mine A"), theme("Mine B"))
        val roundTripped = stripBrandThemesJson(mergeBrandThemesJson(own, brand)!!, brand)
        assertEquals(listOf("Mine A", "Mine B"), names(roundTripped))
    }

    // ── default selection beneath the user's choice ───────────────────────────

    @Test
    fun brand_default_fills_an_unchosen_slot() {
        // No stored selection ⇒ both slots on the toolkit default ⇒ both swing to brand.
        val out = applyBrandDefaultSelectionJson(null, "Acme Dark", "Acme Light")
        val snap = ThemeSnapshotV2.fromStrings(selectionJson = out, customThemesJson = null)
        assertEquals("Acme Dark", snap.darkThemeName)
        assertEquals("Acme Light", snap.lightThemeName)
    }

    @Test
    fun a_users_explicit_choice_wins_over_the_brand_default() {
        val stored = ThemeSnapshotV2(darkThemeName = "Lunamux Ocean").selectionJson()
        val out = applyBrandDefaultSelectionJson(stored, "Acme Dark", "Acme Light")
        val snap = ThemeSnapshotV2.fromStrings(selectionJson = out, customThemesJson = null)
        assertEquals("Lunamux Ocean", snap.darkThemeName, "user's dark pick kept")
        // The light slot was never touched by the user, so it takes the brand default.
        assertEquals("Acme Light", snap.lightThemeName)
    }

    @Test
    fun no_brand_default_falls_back_to_lunicle_default() {
        // With no brand naming a default, an unchosen slot lands on Lunicle's own
        // default (GitHub Light/Dark, LNL-149) rather than the toolkit's Lunamux.
        val stored = ThemeSnapshotV2(darkThemeName = DEFAULT_DARK_THEME, lightThemeName = DEFAULT_LIGHT_THEME)
            .selectionJson()
        val snap = ThemeSnapshotV2.fromStrings(
            selectionJson = applyBrandDefaultSelectionJson(stored, null, null),
            customThemesJson = null,
        )
        assertEquals(LUNICLE_DEFAULT_DARK_THEME, snap.darkThemeName)
        assertEquals(LUNICLE_DEFAULT_LIGHT_THEME, snap.lightThemeName)
    }

    @Test
    fun lunicle_default_is_github() {
        // No stored selection at all still resolves to the Lunicle default.
        val snap = ThemeSnapshotV2.fromStrings(
            selectionJson = applyBrandDefaultSelectionJson(null, null, null),
            customThemesJson = null,
        )
        assertEquals("GitHub Dark", snap.darkThemeName)
        assertEquals("GitHub Light", snap.lightThemeName)
    }

    @Test
    fun a_brand_default_still_wins_over_the_lunicle_default() {
        // A branded deployment's default beats Lunicle's own for an unchosen slot.
        val snap = ThemeSnapshotV2.fromStrings(
            selectionJson = applyBrandDefaultSelectionJson(null, "Acme Dark", "Acme Light"),
            customThemesJson = null,
        )
        assertEquals("Acme Dark", snap.darkThemeName)
        assertEquals("Acme Light", snap.lightThemeName)
    }

    // ── a brand default naming a theme the brand does not ship ────────────────
    //
    // Every test above names a fictional theme and asserts only that the NAME
    // reaches the slot. That is sufficient while a brand ships the themes it
    // names, because the name cannot dangle.
    //
    // A deployment may instead point its default at a theme the TOOLKIT ships
    // and carry no themes of its own. The branded look then depends on that
    // name matching a string in another repository, and nothing above would
    // notice it stop matching: an unknown name is not an error, it silently
    // resolves to the slot fallback, so the instance would quietly serve its
    // generic default and every existing test would still pass.
    //
    // These two pin that chain end to end.

    @Test
    fun a_brand_default_naming_a_builtin_resolves_to_that_theme() {
        // A brand that names a toolkit built-in for both slots and ships no
        // themes of its own.
        val selection = applyBrandDefaultSelectionJson(null, BUILTIN_DEFAULT, BUILTIN_DEFAULT)
        val snap = ThemeSnapshotV2.fromStrings(selectionJson = selection, customThemesJson = null)
        assertEquals(BUILTIN_DEFAULT, snap.lightThemeName)
        assertEquals(BUILTIN_DEFAULT, snap.darkThemeName)

        // ...and it must RESOLVE, not merely be named. Compared against the
        // built-in's own palette rather than a hardcoded hex, so retuning the
        // theme upstream does not fail this — only losing or renaming it does.
        val builtin = builtinTheme(BUILTIN_DEFAULT)
        assertTrue(builtin != null, "the toolkit must still ship a built-in named '$BUILTIN_DEFAULT'")
        assertEquals(
            builtin.resolve().accent,
            snap.resolve(systemIsDark = false).accent,
            "the branded light slot must paint the named theme, not the fallback",
        )
        assertEquals(
            builtin.resolve().accent,
            snap.resolve(systemIsDark = true).accent,
            "and the dark slot too when the brand pins both",
        )
    }

    @Test
    fun a_brand_default_naming_nothing_falls_back_rather_than_failing() {
        // The other half of the contract, and the reason the test above has to
        // compare resolved colour rather than names: a dangling name degrades
        // silently. Pinning that here says the degradation is deliberate — a
        // typo in a brand.json must not white-screen a deployment — and makes
        // the assertion above the thing that catches it instead.
        val snap = ThemeSnapshotV2.fromStrings(
            selectionJson = applyBrandDefaultSelectionJson(null, "No Such Theme", "No Such Theme"),
            customThemesJson = null,
        )
        assertEquals("No Such Theme", snap.lightThemeName, "the name is carried through verbatim")
        assertEquals(
            builtinTheme(DEFAULT_LIGHT_THEME)!!.resolve().accent,
            snap.resolve(systemIsDark = false).accent,
            "an unresolvable name falls back to the slot default",
        )
    }
}
