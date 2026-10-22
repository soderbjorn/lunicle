/**
 * The shell's style and navigation a page's URL asked for: `?style=classic|calm`
 * (`clean`, the Calm look's old name, still works) and
 * `?nav=sidebar|tabstrip|both` (LNA-26).
 *
 * For a link or an embed that wants the tracker to open in the Calm look, or with
 * only a tab strip, without anybody having picked that in Appearance. Both are
 * HINTS, in the same tier as `?theme=` / `?darkTheme=` / `?lightTheme=`:
 *
 *  - they sit beneath the user's own stored choice, which always wins;
 *  - they sit above the deployment's brand.json default (`defaultStyle` /
 *    `defaultNavigation`), because they describe this page load, the more specific
 *    claim;
 *  - they are never written to the account. They reach the toolkit only through
 *    `AppShellSpec.defaultAppearanceShape` (see [ThemePersister.defaultAppearanceShape]),
 *    which the toolkit paints beneath the stored shape and never persists.
 *
 * `?nav=` has no effect when `?chrome=minimal|none|bare` has taken the navigation
 * away ([EmbedChrome.showsNavigation] false): a board in a block on somebody else's
 * page has no room for a sidebar or a tab strip, and a nav hint must not put one
 * back. The toolkit's own precedence already lets `showSidebar` / `showTabStrip =
 * false` win over any mode; [navigationFromParam] drops the hint as well, so the
 * rule does not rest on that alone.
 *
 * Read once at boot by `main.kt`, next to `?chrome=`, and carried across address-bar
 * rewrites by `nextSearch`, which preserves every parameter it was not told about.
 *
 * @see EmbedChrome
 */
package se.soderbjorn.lunicle

import se.soderbjorn.lunula.core.AppearanceShape
import se.soderbjorn.lunula.core.Looks
import se.soderbjorn.lunula.core.NavigationMode
import se.soderbjorn.lunula.core.ShellStyle

/**
 * The [ShellStyle] a page's `?style=` names, or null for no hint.
 *
 * The toolkit's own lenient parser: trimmed, case-insensitive, the persisted token
 * or the enum name. Anything unrecognised is null, which leaves the user's stored
 * choice, then the brand's, then Classic — an unknown value must never change the
 * look on anybody.
 *
 * @param value the raw parameter, or null when absent.
 * @return the hinted style, or null.
 */
internal fun shellStyleFromParam(value: String?): ShellStyle? = ShellStyle.fromRaw(value)

/**
 * The [NavigationMode] a page's `?nav=` names, or null for no hint.
 *
 * Null as well whenever [chrome] hides the navigation, whatever [value] says: the
 * embed's chrome level is the stronger statement (see the file note).
 *
 * @param value  the raw parameter, or null when absent.
 * @param chrome the page's parsed `?chrome=`.
 * @return the hinted mode, or null.
 */
internal fun navigationFromParam(value: String?, chrome: EmbedChrome): NavigationMode? =
    if (chrome.showsNavigation) NavigationMode.fromRaw(value) else null

/**
 * The shape handed to the toolkit as its app default: the URL's hints over the
 * brand's.
 *
 * A `?style=` hint names a whole look (Lunula's `Looks`: Classic or Calm), and
 * it is the more specific claim — this frame on this site — so the look's
 * values replace every style field the brand names (corner radius, spacing,
 * selection, surfaces, chrome, windows, active window, labels) rather than
 * sitting beneath them. Without a hint the brand's shape is handed on as it
 * is, including its own `defaultStyle` shorthand, which the toolkit expands
 * beneath the brand's individual fields. `?nav=` replaces the brand's
 * navigation alone. A field nobody names stays null, which the toolkit reads
 * as its own default. The user's stored values are not an input here: the
 * toolkit layers them, per appearance mode, above whatever this returns.
 *
 * @param brand    the deployment's brand.json shape; empty when unbranded.
 * @param urlStyle the `?style=` hint, or null.
 * @param urlNav   the `?nav=` hint, or null.
 * @return the default shape.
 */
internal fun layeredDefaultShape(
    brand: AppearanceShape,
    urlStyle: ShellStyle?,
    urlNav: NavigationMode?,
): AppearanceShape {
    val styled = if (urlStyle == null) brand else Looks.forShellStyle(urlStyle).shape
    return styled.copy(navigationMode = urlNav ?: brand.navigationMode)
}
