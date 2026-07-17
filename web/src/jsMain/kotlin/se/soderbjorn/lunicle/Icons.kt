/**
 * The hand-drawn icons.
 *
 * Every path in this file was plotted by hand on a 24×24 grid rather than pulled
 * from an icon set, for the reason the rest of the CSS gives: this app is a
 * terminal, and an icon font or a Material glyph reads as a control borrowed from
 * somewhere else. The two that were emoji before (⚙, and no profile icon at all)
 * rendered in whatever the OS felt like — colour, weight and baseline all outside
 * our reach, which is exactly why the cogwheel never sat right in its button.
 *
 * The house style, matching the picker's chevron:
 *
 *  - Stroke, never fill. `stroke: currentColor`, so an icon takes its colour from
 *    the button around it and hover/disabled work with no extra rules.
 *  - `stroke-width: 1.5`, round caps and joins — the same weight as the chevron.
 *  - A 24×24 `viewBox` regardless of the rendered size, so the numbers below can
 *    be read as a drawing rather than as a function of wherever it is used.
 *
 * `vector-effect: non-scaling-stroke` is deliberately absent: these render at
 * 18–20px, close enough to 24 that the stroke needs no compensating.
 */
package se.soderbjorn.lunicle

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * The gear.
 *
 * Drawn as a ring with eight radial teeth rather than the usual filled cog
 * outline, whose path is forty curves nobody can hand-check. Construction, all
 * about the centre (12, 12):
 *
 *  - the hub, r = 2.75
 *  - the ring, r = 7.5
 *  - eight teeth, r 7.5 → 10, every 45°
 *
 * The tooth coordinates are the 45° ones rounded to 2dp — 12 ± 7.5·cos45 = 17.30
 * / 6.70 at the ring, 12 ± 10·cos45 = 19.07 / 4.93 at the tip. They are written
 * out rather than computed because an SVG path is data, and a reader checking
 * this against the render needs the numbers, not the arithmetic.
 */
private const val GEAR_SVG = """
<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"
     fill="none" stroke="currentColor" stroke-width="1.5"
     stroke-linecap="round" stroke-linejoin="round">
  <circle cx="12" cy="12" r="7.5"/>
  <circle cx="12" cy="12" r="2.75"/>
  <path d="M19.5 12 L22 12
           M17.30 17.30 L19.07 19.07
           M12 19.5 L12 22
           M6.70 17.30 L4.93 19.07
           M4.5 12 L2 12
           M6.70 6.70 L4.93 4.93
           M12 4.5 L12 2
           M17.30 6.70 L19.07 4.93"/>
</svg>
"""

/**
 * The profile mark — a head over shoulders.
 *
 * A head circle at (12, 8.5) r = 3.75, and shoulders as a single half-circle arc
 * sweeping from (4.5, 20.5) to (19.5, 20.5). The arc's `sweep-flag` of 1 is what
 * bulges it upward into shoulders; flipping it to 0 draws a bowl.
 *
 * The arc stops at y = 20.5 rather than running to the viewBox edge, so the mark
 * has the same optical margin as the gear and the two sit level beside each other
 * in the top bar.
 */
private const val PROFILE_SVG = """
<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"
     fill="none" stroke="currentColor" stroke-width="1.5"
     stroke-linecap="round" stroke-linejoin="round">
  <circle cx="12" cy="8.5" r="3.75"/>
  <path d="M4.5 20.5 a7.5 7.5 0 0 1 15 0"/>
</svg>
"""

/**
 * The Lunicle mark — the board itself, in miniature.
 *
 * Three columns of cards stacking to different heights, in a frame. It is the
 * app's own screen at 20px: the thing the mark stands for is the thing the mark
 * draws, so there is nothing to explain and no metaphor to stretch.
 *
 * The uneven stack is the whole idea and not a flourish. Three equal columns read
 * as a table or a grid; it is the *ragged* heights that say these are things
 * moving through stages, which is what a tracker is. Drawn 3-2-1 rather than any
 * other arrangement so the eye reads it left-to-right as a slope — the same
 * direction the board's own columns run.
 *
 * Construction, on the usual 24×24 grid:
 *
 *  - the frame: a 19×17 rounded rectangle from (2.5, 3.5), r = 2.5
 *  - the columns: 4-wide cards at x = 4.5 / 10 / 15.5. Three cards and two
 *    1.5-unit gutters span 15 of the frame's 19, leaving 2 either side — the
 *    frame's own corner radius, so the cards clear the curve rather than
 *    crowding it.
 *  - the rows: y = 8, 12, 16. A 4-unit pitch centred on the frame's midline,
 *    which puts 4.5 of air above the first row and the same below the last.
 *
 * The cards are strokes with round caps rather than filled rects: at this size a
 * 1.5-tall filled rect and a 1.5-wide round-capped stroke draw the same shape,
 * and the stroke needs two numbers instead of five. It also means they take
 * `stroke-width` from the same place everything else here does, so the mark
 * thickens as one drawing rather than as a frame with fillings that stayed put.
 */
private const val LOGO_SVG = """
<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"
     fill="none" stroke="currentColor" stroke-width="1.5"
     stroke-linecap="round" stroke-linejoin="round">
  <rect x="2.5" y="3.5" width="19" height="17" rx="2.5"/>
  <path d="M4.5 8 h4 M10 8 h4 M15.5 8 h4
           M4.5 12 h4 M10 12 h4
           M4.5 16 h4"/>
</svg>
"""

/** The cogwheel, for the project-settings button. */
fun gearIcon(): HTMLElement = icon(GEAR_SVG, "icon-gear")

/** The Lunicle mark, for the footer. */
fun logoIcon(): HTMLElement = icon(LOGO_SVG, "icon-logo")

/** The profile mark, shown beside the signed-in user's name. */
fun profileIcon(): HTMLElement = icon(PROFILE_SVG, "icon-profile")

/**
 * Wrap one of the markup constants above in a span.
 *
 * `innerHTML` here is the third sanctioned use in this app, and the safest of the
 * three: the argument is always one of the `private const val`s in this file, so
 * there is no input to escape and no caller who could pass one. The alternative —
 * `createElementNS` plus `setAttribute` per node — would turn each drawing above
 * into thirty lines of DOM calls, and the paths would stop being readable as
 * paths, which is the whole point of keeping them as markup.
 *
 * The span exists because an `<svg>` is awkward to size from the outside; the
 * CSS sets the span's box and `.icon > svg` fills it. See styles.css.
 */
private fun icon(markup: String, className: String): HTMLElement {
    val host = document.createElement("span") as HTMLElement
    host.className = "icon $className"
    host.innerHTML = markup
    return host
}
