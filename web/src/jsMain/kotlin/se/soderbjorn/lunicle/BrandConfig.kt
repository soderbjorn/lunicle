/**
 * The client half of deploy-time branding (LNL-110): fetching a self-hosted
 * instance's brand directory off the server's `/brand` endpoints and parsing it
 * into a [Brand] the app applies at boot.
 *
 * Nothing here persists: the themes are injected into the in-memory effective
 * theme list (see [ThemePersister.setBrand]), the font is applied as a runtime
 * CSS variable, and the logo is inlined into the topbar and sign-in. On an
 * unbranded instance the very first fetch — `/brand/brand.json` — 404s and
 * [loadBrand] returns null, so the app is identical to today.
 *
 * @see ThemePersister
 * @see se.soderbjorn.lunicle.brandRoutes on the server, which serves these files.
 */
package se.soderbjorn.lunicle

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.w3c.fetch.Response
import se.soderbjorn.lunula.core.AppearanceShape
import se.soderbjorn.lunula.core.SelectionStyle
import se.soderbjorn.lunula.core.Theme
import se.soderbjorn.lunula.core.UiDensity
import kotlin.js.Promise

/**
 * A deployment's resolved branding. Every field is independently optional, so a
 * brand dir that ships only a logo yields a [Brand] with everything else empty.
 *
 * @property themes            the selectable brand themes (parsed lunula Themes).
 * @property defaultDarkTheme  Theme name for the dark slot's default, or null.
 * @property defaultLightTheme Theme name for the light slot's default, or null.
 * @property logoSvg           inline `<svg>` markup for the topbar + sign-in, or null.
 * @property fonts             the company font faces, each covering one or more
 *   surfaces. A deployment may ship several (e.g. a sans for chrome + prose and a
 *   serif for headings); [fontForSurface] resolves which face owns a surface.
 * @property title             optional document-title override (applied server-side;
 *   carried here only for completeness).
 * @property appearanceShape  the deployment's default corner roundness, spacing
 *   density and selection language, from `defaultCornerRadiusPx` /
 *   `defaultUiDensity` / `defaultSelectionStyle`. Handed to the toolkit as
 *   [AppShellSpec.defaultAppearanceShape], so each field sits *beneath* the
 *   user's own pick and above the toolkit default — the same precedence the
 *   brand fonts and the brand theme defaults already use. Every field is
 *   independently optional; an empty shape means "toolkit defaults throughout".
 * @property chromeFontSizePx  default chrome (sidebar/tab bar/window title) font
 *   size in px, or null. Like the families, each size sits beneath the user's
 *   own Appearance pick and above the toolkit default.
 * @property monoFontSizePx    default monospaced (code) font size in px, or null.
 * @property proseFontSizePx   default proportional (prose) font size in px, or null.
 * @property displayFontSizePx default display (heading) font size in px, or null.
 * @property googleHostedDomain optional Google Workspace domain the deployment
 *   pins sign-in to (LNL-125). When set, it is passed as the `hd` hint to Google's
 *   `initCodeClient` so the account chooser is pre-filtered to that one domain.
 *   The hint is only UX — the binding boundary is the matching server-side gate in
 *   `exchangeGoogleCode`. Null ⇒ open chooser, exactly as an unbranded install.
 * @property landingNote optional prose the signed-out landing page carries under
 *   its headline, from `landingNote` in the manifest. What a deployment says about
 *   itself to somebody who has arrived and cannot get in — which instance this is,
 *   who runs it, what Lunicle is — and the only place a brand speaks in sentences
 *   rather than in colours. Links may be written either way — `[the docs](https://…)`
 *   for a labelled one, or a bare `http(s)` URL, which shows itself — and a blank
 *   line starts a paragraph. That is the whole of the markup it understands, and
 *   deliberately: see `noteParagraph`, which builds nodes rather than rendering
 *   HTML from a config file. Null on an unbranded instance, and on a branded
 *   one with nothing to add: the landing then carries the headline and the way in,
 *   and nothing else. See EmptyTabSurface.
 * @property landingHeadline what the signed-out landing says in place of "No projects
 *   to show", from `landingHeadline` in the manifest. The stock line is written for an
 *   instance that happens to have nothing public on it, and it is the wrong sentence
 *   for a deployment that is private on purpose: it reports an absence where the truth
 *   is a door. Null keeps the stock line, which is right for an unbranded install.
 * @property landingDetail likewise for "Sign in if you have an account here." — the
 *   line under the headline, above the button.
 *
 *   Both are the landing's ALONE, and deliberately: the other empty states name a
 *   control the reader is looking at ("Make the first one to get started."), and a
 *   deployment overriding those would be writing instructions for a screen it cannot
 *   see. See MainScreenBackingViewModel.EmptyTab, which still decides which reader
 *   gets which card — a brand may reword the visitor's, not claim somebody else's.
 */
data class Brand(
    val themes: List<Theme>,
    val defaultDarkTheme: String?,
    val defaultLightTheme: String?,
    val logoSvg: String?,
    val fonts: List<BrandFont>,
    val title: String?,
    val googleHostedDomain: String?,
    val landingNote: String?,
    val landingHeadline: String?,
    val landingDetail: String?,
    val appearanceShape: AppearanceShape,
    val chromeFontSizePx: Int?,
    val monoFontSizePx: Int?,
    val proseFontSizePx: Int?,
    val displayFontSizePx: Int?,
) {
    /**
     * The brand face covering [surface] (a normalized surface name: `chrome`,
     * `prose` or `display`), or null when no shipped font names it. First match
     * wins, so a manifest that lists the sans before the serif keeps the sans on
     * whatever surfaces it claims.
     */
    fun fontForSurface(surface: String): BrandFont? =
        fonts.firstOrNull { f -> f.surfaces.any { normalizeSurface(it) == surface } }

    /** The face for the shell chrome (sidebar/tabbar/pane headers/topbar), or null. */
    val chromeFont: BrandFont? get() = fontForSurface("chrome")

    /**
     * The face defaulting the PROPORTIONAL main-content ("prose") surface — card
     * text, dialog copy, the rendered description — while leaving code/monospace
     * alone (LNL-118). Bound by the toolkit to `--dt-font-prop`, which Lunicle's
     * `--prose` consumes.
     */
    val proseFont: BrandFont? get() = fontForSurface("prose")

    /**
     * The face defaulting the DISPLAY (heading) surface — issue titles and board
     * column names. Bound to `--dt-font-display`, which Lunicle's `--display`
     * consumes; falls back to prose when no font claims `display`.
     */
    val displayFont: BrandFont? get() = fontForSurface("display")
}

/**
 * One brand font face and the surfaces it covers.
 *
 * @property family    the company font-family name (matches the `@font-face` in
 *   the deployment's served `fonts.css`).
 * @property surfaces  raw surface names from the manifest; normalized by
 *   [normalizeSurface] when matched. Recognised: `chrome`; the prose aliases
 *   `prose` / `content` / `content-prop`; and the display aliases `display` /
 *   `heading`. The monospaced content surface is never branded — code stays code.
 * @property fallback  the generic family the CSS stack ends in, so a missing
 *   `@font-face` degrades to a sane system face of the right category.
 */
data class BrandFont(
    val family: String,
    val surfaces: Set<String>,
    val fallback: FontFallback,
) {
    /** Stable [se.soderbjorn.lunula.web.themeeditor.FontPreset.key] this face registers under. */
    val presetKey: String get() = brandFontPresetKey(family)

    /** Full CSS `font-family` stack: the family, then its generic fallback. */
    val cssStack: String get() = "'$family', ${fallback.stack}"
}

/** The generic family a [BrandFont]'s CSS stack ends in. */
enum class FontFallback(val stack: String) {
    SansSerif("system-ui, -apple-system, BlinkMacSystemFont, sans-serif"),
    Serif("Georgia, 'Times New Roman', Times, serif"),
    Monospace("ui-monospace, SFMono-Regular, Menlo, Consolas, monospace");

    companion object {
        fun parse(raw: String?): FontFallback = when (raw?.lowercase()) {
            "serif" -> Serif
            "mono", "monospace" -> Monospace
            else -> SansSerif
        }
    }
}

/** Canonical surface name for a raw manifest value; unknown values pass through lowercased. */
private fun normalizeSurface(raw: String): String = when (raw.lowercase()) {
    "chrome" -> "chrome"
    "prose", "content", "content-prop" -> "prose"
    "display", "heading", "headings" -> "display"
    else -> raw.lowercase()
}

/** Derives a stable, unique preset key from a font [family] (e.g. "Framna Serif" → "brand-framna-serif"). */
fun brandFontPresetKey(family: String): String =
    "brand-" + family.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

/** Lenient codec — tolerant of manifest/theme fields this build does not know. */
private val brandJson = Json { ignoreUnknownKeys = true }

/**
 * Fetch and parse the deployment's branding, or null when the instance is
 * unbranded (the `/brand/brand.json` request 404s).
 *
 * The manifest response is enriched server-side with a `themes` array of the
 * theme filenames present on disk; each is fetched and parsed individually.
 * Every part is defensive — a missing or malformed piece is dropped, never
 * fatal — so a half-written brand dir degrades to whatever parsed cleanly.
 */
/**
 * Reads a font-size field from the manifest, accepting a JSON number or a
 * numeric string and rejecting anything outside a sane range.
 *
 * Bounded rather than trusted: this value is hand-written in a deployment's
 * `brand.json`, and a stray zero or a typo'd `144` would render the instance
 * unusable with no obvious cause. Out of range degrades to "toolkit default"
 * for that one surface, like every other malformed brand field.
 *
 * @param key the manifest field name.
 * @return the size in px within 8..40, or null.
 */
private fun JsonObject.fontSizeOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it in 8..40 }

suspend fun loadBrand(): Brand? {
    val manifestText = fetchText("/brand/brand.json") ?: return null
    val manifest = runCatching { brandJson.parseToJsonElement(manifestText).jsonObjectOrNull() }
        .getOrNull() ?: return null

    val themeFiles = (manifest["themes"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?: emptyList()
    val themes = themeFiles.mapNotNull { file ->
        fetchText("/brand/themes/$file")?.let { text ->
            runCatching { brandJson.decodeFromString(Theme.serializer(), text) }.getOrNull()
        }
    }

    val fonts = parseBrandFonts(manifest)

    val logoSvg = fetchText("/brand/logo.svg")?.takeIf { it.contains("<svg", ignoreCase = true) }

    return Brand(
        themes = themes,
        defaultDarkTheme = manifest.stringOrNull("defaultDarkTheme"),
        defaultLightTheme = manifest.stringOrNull("defaultLightTheme"),
        logoSvg = logoSvg,
        fonts = fonts,
        title = manifest.stringOrNull("title"),
        googleHostedDomain = manifest.stringOrNull("googleHostedDomain"),
        landingNote = manifest.stringOrNull("landingNote")?.takeIf { it.isNotBlank() },
        // Blank is not an override. A manifest that names the key and leaves it empty
        // has said nothing, and honouring it would give the landing a headline of no
        // characters — the same rule landingNote above already follows.
        landingHeadline = manifest.stringOrNull("landingHeadline")?.takeIf { it.isNotBlank() },
        landingDetail = manifest.stringOrNull("landingDetail")?.takeIf { it.isNotBlank() },
        // Parsed leniently, field by field: a manifest naming a density this
        // build doesn't know must not also cost the deployment its selection
        // style. Both enums ignore anything unrecognised, and the radius is
        // range-checked, so a typo degrades to "toolkit default" for that one
        // setting rather than failing the brand.
        appearanceShape = AppearanceShape(
            cornerRadiusPx = (manifest["defaultCornerRadiusPx"] as? JsonPrimitive)
                ?.contentOrNull?.toIntOrNull()?.takeIf { it in 0..64 },
            uiDensity = UiDensity.fromRaw(manifest.stringOrNull("defaultUiDensity")),
            selectionStyle = SelectionStyle.fromRaw(manifest.stringOrNull("defaultSelectionStyle")),
        ),
        chromeFontSizePx = manifest.fontSizeOrNull("defaultChromeFontSizePx"),
        monoFontSizePx = manifest.fontSizeOrNull("defaultMonoFontSizePx"),
        proseFontSizePx = manifest.fontSizeOrNull("defaultProseFontSizePx"),
        displayFontSizePx = manifest.fontSizeOrNull("defaultDisplayFontSizePx"),
    )
}

/**
 * Parse the manifest's font declaration into [BrandFont]s. Accepts either a
 * `fonts` array (each entry `{ family, surfaces, fallback? }`) for multi-face
 * brands, or the legacy singular `font` object — so an existing single-font
 * brand.json keeps working unchanged. Entries without a non-blank `family` are
 * dropped.
 */
private fun parseBrandFonts(manifest: JsonObject): List<BrandFont> {
    val entries: List<JsonObject> = (manifest["fonts"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?: listOfNotNull(manifest["font"] as? JsonObject)
    return entries.mapNotNull { obj ->
        val family = (obj["family"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val surfaces = (obj["surfaces"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            ?: emptySet()
        val fallback = FontFallback.parse((obj["fallback"] as? JsonPrimitive)?.contentOrNull)
        BrandFont(family, surfaces, fallback)
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject =
    this as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * GET [url] and return its body text, or null on any non-OK response or error.
 *
 * Uses the browser Fetch API via `window` so it needs no HTTP client of its own
 * — the brand assets are same-origin static files. A network error, a 404 (the
 * unbranded case, and each independently-optional missing part) and a body-read
 * failure all collapse to null, which every caller reads as "absent".
 */
private suspend fun fetchText(url: String): String? {
    val response = runCatching {
        (window.asDynamic().fetch(url) as Promise<Response>).await()
    }.getOrNull() ?: return null
    if (!response.ok) return null
    return runCatching { response.text().await() }.getOrNull()
}
