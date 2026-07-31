/**
 * The toolkit's persistence, pointed at the signed-in user's account.
 *
 * The lunula shell writes everything it wants remembered through one
 * interface — [Persister], a flat string-to-string store. Lunicle used to hand
 * it an [InMemoryPersister], which is why every reload landed on the same
 * default layout and the same Lunamux Dark theme no matter what anyone chose.
 * This replaces that with a store that keeps *some* of those keys on the server,
 * under the account.
 *
 * ── Which keys, and why not all of them ─────────────────────────────────────
 *
 * Only [UiSettingKeys.persisted] travels: the theme selection, the user's own
 * themes, where the panes sit, and how wide they dragged the sidebar. Everything
 * else the shell writes — collapsed sidebar sections, hotkeys — stays in memory
 * and dies with the tab, deliberately: a key reaches this list when some part of
 * the app would be *wrong* without it across a reload, not merely different.
 *
 * That the *custom themes* key is on the list is not a bonus. The toolkit's
 * theme manager lets a user build and edit themes, and the selection names the
 * chosen one by string — so persisting the choice without the themes would store
 * a reference to something that no longer exists anywhere. On the next load the
 * toolkit would find an unknown name, fall back to a built-in, and the user would
 * have kept their appearance and lost the theme they made. The two keys are one
 * fact and are written as such.
 *
 * ── Signed out ──────────────────────────────────────────────────────────────
 *
 * Everything still works; nothing is kept. Writes land in the in-memory map and
 * are not sent, so a visitor can flip to light mode and read comfortably without
 * the server having anywhere to put that. Signing in then *adopts* what they
 * were looking at rather than throwing it away — see [onIdentityChanged].
 *
 * @see main
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import se.soderbjorn.lunula.core.Appearance
import se.soderbjorn.lunula.core.DEFAULT_DARK_THEME
import se.soderbjorn.lunula.core.DEFAULT_LIGHT_THEME
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunula.core.Theme
import se.soderbjorn.lunula.core.ThemeSnapshotV2
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.clientserver.UiSettingKeys

/**
 * A [Persister] that keeps the theme keys on the server and the rest in memory.
 *
 * @param storage the one repository every other view model shares, so this adds
 *   no second HTTP client.
 * @param scope the app scope. Writes are launched into it rather than awaited:
 *   the toolkit calls [write] from inside its own paint path, and a preference
 *   that has not finished being stored must not hold up the pixel that shows it.
 */
class ThemePersister(
    private val storage: StorageRepository,
    private val scope: CoroutineScope,
) : Persister {

    /**
     * Every key the shell has written or read this page load, server-backed or
     * not.
     *
     * The server-backed ones are seeded here by [load] before the shell mounts,
     * which is what makes [read] synchronous-in-effect: by the time the toolkit
     * asks, the answer is already local. It doubles as the record of what this
     * browser currently believes, which is what [onIdentityChanged] hands to an
     * account that has never stored anything.
     */
    private val memory = mutableMapOf<String, String>()

    /**
     * The user the contents of [memory] belong to, or null for nobody.
     *
     * Compared against the session's effective user id to notice that the person
     * in front of the browser changed — sign-in, sign-out, an admin starting or
     * stopping impersonation. The shell is mounted once and outlives all of
     * those.
     */
    var loadedFor: Long? = null
        private set

    // ── Deploy-time brand themes (LNL-110) ──────────────────────────────────
    //
    // A self-hosted instance can ship a set of themes on disk (see BrandConfig /
    // the server's /brand endpoints). They are read-only deployment config: made
    // *selectable* by layering them onto the effective custom-theme list, and made
    // the *default* by sitting beneath the user's explicit slot choice — but never
    // written to the datastore. Two consequences fall out of that "never persisted"
    // rule, both handled below:
    //   - [read] and [snapshot] MERGE brand themes in, so the shell offers them;
    //   - [write] STRIPS them back out before persisting THEME_CUSTOM, so a theme
    //     edit round-tripping through the toolkit never saves a brand definition.
    // The definition therefore comes from disk on every load: re-branding updates
    // everyone with no staleness, and a removed theme falls back cleanly (the
    // toolkit's resolve() already handles an unknown selected name).
    //
    // Brand theme names are a reserved namespace: a user's own theme sharing a
    // brand theme's name is shadowed by the brand one and not persisted under that
    // name. Company theme names ("Acme Dark") make that collision a non-issue.
    private var brandThemes: List<Theme> = emptyList()
    private var brandDefaultDark: String? = null
    private var brandDefaultLight: String? = null

    /**
     * Install the deployment's brand themes and default slot names. Call once at
     * boot, before [start], with what the brand dir yielded (empty ⇒ no-op, so an
     * unbranded instance behaves exactly as before).
     *
     * @param themes       the brand's selectable themes, layered onto the user's.
     * @param defaultDark  brand default for the dark slot, or null to leave it.
     * @param defaultLight brand default for the light slot, or null to leave it.
     */
    fun setBrand(themes: List<Theme>, defaultDark: String?, defaultLight: String?) {
        brandThemes = themes
        brandDefaultDark = defaultDark?.takeIf { it.isNotBlank() }
        brandDefaultLight = defaultLight?.takeIf { it.isNotBlank() }
    }

    // ── Embed defaults (?theme=, ?darkTheme=, ?lightTheme=) ─────────────────
    //
    // What a host page embedding the tracker in an iframe asks an *unchosen*
    // browser to land on, so the frame matches the surrounding chrome instead of
    // clashing with it. Three parameters, because the mismatch has two halves:
    //
    //   - `?theme=dark|light|auto` → [defaultAppearance]: which slot is active. A
    //     *light* host embedding the tracker gets a dark frame otherwise, Lunicle's
    //     own default now being Dark ([LUNICLE_DEFAULT_APPEARANCE]).
    //   - `?darkTheme=` / `?lightTheme=` → the slot *contents*. Picking a side is not
    //     enough on its own: the slot would still hold Lunicle's own Classic palette,
    //     green-glowing, inside (say) the cyan-and-navy Lunamux site. A host that
    //     knows which theme matches its chrome names it — "Lunamux Dark" — and the
    //     embed paints it.
    //
    // These are *defaults*, not overrides, and both halves land beneath the user:
    // the appearance only reaches [seedDefault], which runs only when nothing is
    // stored, and the slot names go through [applyBrandDefaultSelectionJson], which
    // fills only a slot still on the toolkit's built-in sentinel. So a signed-in
    // user's saved theme still wins. The full precedence, highest first:
    //
    //     the user's own choice  >  these embed hints  >  the deployment's
    //     brand.json default  >  Lunicle's Classic default  >  the toolkit's
    //
    // The embed sits above brand.json deliberately: brand.json is one instance-wide
    // default, while these describe *this frame on this site*, which is the more
    // specific claim and the only one that knows what it is being embedded into.
    //
    // Held as fields rather than threaded through [start] because a sign-*out*
    // while embedded reseeds ([onIdentityChanged] also calls [seedDefault]) and must
    // land back on the host's look, not on Lunicle's.
    private var defaultAppearance: Appearance = LUNICLE_DEFAULT_APPEARANCE
    private var embedDefaultDark: String? = null
    private var embedDefaultLight: String? = null

    /**
     * Install the host page's embed defaults. Call once at boot, before [start].
     * Every argument is independently optional — null leaves that half alone, so an
     * un-embedded load (no parameters at all) behaves exactly as before.
     *
     * @param appearance which slot an unchosen browser seeds on, or null for
     *   Lunicle's own default ([LUNICLE_DEFAULT_APPEARANCE]).
     * @param darkTheme  name for the dark slot when it was never chosen, or null to
     *   fall through to the brand/Lunicle default. Blank is treated as null.
     * @param lightTheme the same for the light slot.
     */
    fun setEmbedDefaults(appearance: Appearance?, darkTheme: String?, lightTheme: String?) {
        appearance?.let { defaultAppearance = it }
        embedDefaultDark = darkTheme?.takeIf { it.isNotBlank() }
        embedDefaultLight = lightTheme?.takeIf { it.isNotBlank() }
    }

    /**
     * The theme keys are answered from the brand-merged view; everything else is
     * the raw in-memory value. The shell reads [UiSettingKeys.THEME_CUSTOM] and
     * [UiSettingKeys.THEME_SELECTION] straight off the persister as it mounts, so
     * merging here is what makes brand themes selectable and the brand default
     * active without a single datastore write.
     */
    override suspend fun read(key: String): String? = when (key) {
        UiSettingKeys.THEME_CUSTOM -> effectiveCustomThemesJson()
        UiSettingKeys.THEME_SELECTION -> effectiveSelectionJson()
        else -> memory[key]
    }

    /**
     * Load the caller's theme, or fall back to Lunicle's default. Call this
     * once, before `mountAppShell`.
     *
     * The default is Lunamux Classic Dark — [LUNICLE_DEFAULT_APPEARANCE], on
     * Lunicle's own slot defaults (see [applyBrandDefaultSelectionJson]). Only the
     * appearance is named here; the slot names come from that merge, so naming them
     * again would be repeating it back to itself. This is a Lunicle choice, not a
     * Lunula one: the toolkit's own [ThemeSnapshotV2] still defaults to Auto for
     * every other app.
     */
    suspend fun start() {
        if (!load()) seedDefault()
    }

    /**
     * Remember [value] for [key] — locally always, on the server when it is one
     * of ours and there is an account to put it against.
     *
     * Failures are swallowed on purpose. This is called from the toolkit's paint
     * path on every flick of the dark/light control, and the honest consequence
     * of a failed write is that the preference does not survive the reload —
     * which is precisely the behaviour that existed before this class did. An
     * alert over a colour choice would be a worse outcome than the one it
     * reports, and rethrowing crosses back into toolkit code that logs and
     * rethrows again.
     */
    override suspend fun write(key: String, value: String) {
        // THEME_CUSTOM round-trips through the toolkit carrying the brand themes
        // that [read] merged in; strip them so only the user's own themes are
        // stored, locally and on the server. The rest is stored verbatim — a
        // selection that *names* a brand theme is fine to persist, only the
        // definition must not be. See the brand-themes note above.
        val stored = if (key == UiSettingKeys.THEME_CUSTOM) stripBrandThemes(value) else value
        memory[key] = stored
        if (key !in UiSettingKeys.persisted) return
        if (loadedFor == null) return
        scope.launch { runCatching { storage.setUiSetting(key, stored) } }
    }

    /**
     * Fetch the current caller's stored settings into [memory].
     *
     * Called once before `mountAppShell`, and again whenever the caller changes.
     * Awaited at boot rather than applied afterwards: the shell reads the
     * persister as it mounts, so having the answer first is the difference
     * between painting the user's theme and painting the default and then
     * flinching to the user's a moment later.
     *
     * A failure leaves [memory] as it was and [loadedFor] null, so the session
     * degrades to exactly the old in-memory behaviour instead of failing to boot.
     *
     * @return true if the account had something stored — false for a signed-out
     *   visitor and for an account that has never chosen anything, which are the
     *   two cases the caller answers with defaults.
     */
    suspend fun load(): Boolean {
        val state = runCatching { storage.uiSettings() }.getOrNull() ?: return false
        loadedFor = state.userId
        // Only the keys we are prepared to hand back. The server enforces the
        // same allowlist on the way in; this side does not trust that it did,
        // because a key that arrived here would go straight into the toolkit.
        val stored = state.settings.filterKeys { it in UiSettingKeys.persisted }
        // Replaced, not merged: this can be a *different* person's settings
        // arriving over the last one's. A user who has never opened the theme
        // manager has no custom-themes key, and merging would leave them holding
        // the themes of whoever was signed in before.
        UiSettingKeys.persisted.forEach { memory.remove(it) }
        memory.putAll(stored)
        return stored.isNotEmpty()
    }

    /**
     * The signed-in user changed. Re-read their settings and say what to paint.
     *
     * Three outcomes, and the middle one is the interesting one:
     *
     *  - **They have stored settings** — those.
     *  - **They are signed in and have none** — whatever is on screen right now
     *    is written to their account and kept. Someone who picked light mode
     *    while signed out, then signed in, keeps light mode; the alternative is
     *    to snap them back to the default at the exact moment they identified
     *    themselves, which reads as the app forgetting rather than as the account
     *    being empty. It is empty, and this is what fills it.
     *  - **Nobody is signed in** — the default. A signed-out browser must not go
     *    on wearing the theme of whoever used it last; that is a shared machine
     *    showing one person's choices to the next.
     *
     * @return the snapshot the caller should apply to the live shell.
     */
    suspend fun onIdentityChanged(): ThemeSnapshotV2 {
        val adopted = memory.filterKeys { it in UiSettingKeys.persisted }
        val hadStored = load()
        if (loadedFor == null) {
            memory.clear()
            seedDefault()
        } else if (!hadStored) {
            // Adopt, then persist. Writing through [write] rather than straight
            // to the API keeps the "is this one of ours" and "is anyone signed
            // in" rules in one place.
            adopted.forEach { (key, value) -> write(key, value) }
        }
        return snapshot()
    }

    /**
     * Lunicle's own default, for a browser with nothing stored. See [start].
     *
     * The appearance is [defaultAppearance] — [LUNICLE_DEFAULT_APPEARANCE] unless a
     * host embedding the tracker asked for another via `?theme=` (see
     * [setEmbedDefaults]). Only the appearance is named here; the slot names come
     * from the brand-default merge, so naming them again would repeat it back.
     */
    private suspend fun seedDefault() {
        write(
            UiSettingKeys.THEME_SELECTION,
            ThemeSnapshotV2(appearance = defaultAppearance).selectionJson(),
        )
    }

    /**
     * What the shell should be painting, from what this store currently holds.
     *
     * The toolkit's own parser does the reading, and it is total: a blob that is
     * missing, blank or malformed yields the defaults rather than an exception.
     */
    fun snapshot(): ThemeSnapshotV2 = ThemeSnapshotV2.fromStrings(
        selectionJson = effectiveSelectionJson(),
        customThemesJson = effectiveCustomThemesJson(),
    )

    // ── Brand merge/strip helpers ───────────────────────────────────────────
    //
    // The three behaviours the acceptance criteria hinge on are pure functions of
    // (stored value, brand), so they live as internal top-level functions below
    // and are unit-tested directly. These thin methods bind them to this store's
    // fields.

    private fun effectiveCustomThemesJson(): String? =
        mergeBrandThemesJson(memory[UiSettingKeys.THEME_CUSTOM], brandThemes)

    private fun stripBrandThemes(json: String): String =
        stripBrandThemesJson(json, brandThemes)

    // The embed's slot names take precedence over the deployment's brand default
    // for an unchosen slot; see the embed-defaults note above for why that way
    // round. Both still sit beneath the user's own choice, which is
    // [applyBrandDefaultSelectionJson]'s own guarantee rather than this line's.
    private fun effectiveSelectionJson(): String? =
        applyBrandDefaultSelectionJson(
            memory[UiSettingKeys.THEME_SELECTION],
            embedDefaultDark ?: brandDefaultDark,
            embedDefaultLight ?: brandDefaultLight,
        )
}

// ── Brand theme merge / strip / default: pure, unit-tested ──────────────────

/** Lenient codec for the brand/user theme arrays — tolerant of toolkit field changes. */
private val brandThemeJson = Json { ignoreUnknownKeys = true }
private val themeListSerializer = ListSerializer(Theme.serializer())

private fun decodeThemes(json: String?): List<Theme> =
    json?.takeIf { it.isNotBlank() }
        ?.let { runCatching { brandThemeJson.decodeFromString(themeListSerializer, it) }.getOrNull() }
        ?: emptyList()

private fun encodeThemes(themes: List<Theme>): String =
    brandThemeJson.encodeToString(themeListSerializer, themes)

/**
 * The user's own custom themes ([ownJson]) with [brandThemes] layered on top, as
 * the JSON the toolkit consumes — or the raw [ownJson] when there is no brand, so
 * an unbranded instance is byte-for-byte unchanged.
 *
 * Brand themes win a name collision (they are a reserved namespace), so a brand
 * theme is never hidden behind a same-named user theme.
 */
internal fun mergeBrandThemesJson(ownJson: String?, brandThemes: List<Theme>): String? {
    if (brandThemes.isEmpty()) return ownJson
    val brandNames = brandThemes.map { it.name }.toSet()
    val own = decodeThemes(ownJson).filter { it.name !in brandNames }
    return encodeThemes(own + brandThemes)
}

/**
 * [json] with every brand-named theme removed, so a custom-themes blob that
 * round-tripped through the toolkit (and so carries the merged-in brand themes)
 * persists only the user's own — no brand definition ever reaches the datastore.
 */
internal fun stripBrandThemesJson(json: String, brandThemes: List<Theme>): String {
    if (brandThemes.isEmpty()) return json
    val brandNames = brandThemes.map { it.name }.toSet()
    return encodeThemes(decodeThemes(json).filter { it.name !in brandNames })
}

/**
 * Lunicle's own slot defaults: the Classic Lunamux pair. These sit *beneath* both a
 * deployment's brand default and the user's own choice — they are what an unbranded,
 * never-chosen slot lands on, in place of the toolkit's own Lunamux defaults. They
 * are a Lunicle choice, not a Lunula one: the toolkit still defaults to plain Lunamux
 * for every other app.
 *
 * Was GitHub Light/Dark (LNL-149), which is what a *neutral* default looks like — a
 * grey that belongs to nobody. Lunicle has a look of its own now and the site shows
 * it: the marketing frame, the demo and a bare `issues.lunicle.dev` all land on the
 * same green-glow Classic palette rather than three different first impressions.
 * The change reaches every passive user, by design — [applyBrandDefaultSelectionJson]
 * applies this beneath the stored selection rather than seeding it into one, so
 * nobody is pinned to the default that happened to be current when they first
 * loaded. Anyone who *picked* GitHub Dark keeps it.
 *
 * "Lunamux Classic Dark" is deliberately not the toolkit's [DEFAULT_DARK_THEME]
 * ("Lunamux Dark"): that name is the sentinel meaning "never chosen", so a slot
 * holding it is one this function is still entitled to move.
 */
const val LUNICLE_DEFAULT_DARK_THEME: String = "Lunamux Classic Dark"
const val LUNICLE_DEFAULT_LIGHT_THEME: String = "Lunamux Classic Light"

/**
 * The appearance a browser with nothing stored seeds on: dark.
 *
 * The other half of the default look, and the half that decides which of the two
 * slots above is actually showing. Dark because the house look is the dark one —
 * the site is committed dark and passed `?theme=dark` to say so, which was the
 * embed papering over an app default that disagreed with it.
 *
 * Not [Appearance.Auto]: following the OS would make the first impression a
 * property of the visitor's machine, and half of them would still see a palette
 * nothing on the site had shown them. A visitor who wants their system followed
 * has the Appearance control, and that choice is stored and wins over this.
 */
val LUNICLE_DEFAULT_APPEARANCE: Appearance = Appearance.Dark

/**
 * The [Appearance] a host embedding the tracker named on the frame URL's
 * `?theme=`, or null for absent and for anything that is not one of the three
 * names — which the caller then treats as "no hint", leaving Lunicle's own
 * default. Pure so main.kt's parameter reading can be tested without a store.
 *
 * Only the three appearance names are accepted, spelt as the toolkit spells them
 * lowercased. A theme *name* is deliberately not accepted here: the mismatch a
 * host is fixing is light-vs-dark against its own chrome, which is the appearance;
 * which specific theme fills each slot stays Lunicle's (or the brand's) to decide.
 */
internal fun appearanceFromThemeParam(value: String?): Appearance? = when (value) {
    "dark" -> Appearance.Dark
    "light" -> Appearance.Light
    "auto" -> Appearance.Auto
    else -> null
}

/**
 * The [stored] selection with a default applied as a fallback *beneath* the
 * user's choice: a slot still on the toolkit's built-in default (i.e. never
 * chosen) is swung to the brand default when the deployment names one, and
 * otherwise to Lunicle's own default ([LUNICLE_DEFAULT_DARK_THEME] /
 * [LUNICLE_DEFAULT_LIGHT_THEME]); a slot the user set to anything else is left
 * alone.
 *
 * Applied here, non-persisted, rather than through a persisting seed — so a
 * passive user is never pinned to today's default, and a re-brand (or a change to
 * Lunicle's own default) moves them.
 */
internal fun applyBrandDefaultSelectionJson(
    stored: String?,
    defaultDark: String?,
    defaultLight: String?,
): String? {
    val snap = ThemeSnapshotV2.fromStrings(selectionJson = stored, customThemesJson = null)
    val dark = if (snap.darkThemeName == DEFAULT_DARK_THEME) {
        defaultDark ?: LUNICLE_DEFAULT_DARK_THEME
    } else {
        snap.darkThemeName
    }
    val light = if (snap.lightThemeName == DEFAULT_LIGHT_THEME) {
        defaultLight ?: LUNICLE_DEFAULT_LIGHT_THEME
    } else {
        snap.lightThemeName
    }
    return ThemeSnapshotV2(darkThemeName = dark, lightThemeName = light, appearance = snap.appearance).selectionJson()
}
