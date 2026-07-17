/**
 * The icons.
 *
 * Every path here but one was plotted by hand on a 24×24 grid rather than pulled
 * from an icon set, for the reason the rest of the CSS gives: this app is a
 * terminal, and an icon font or a Material glyph reads as a control borrowed from
 * somewhere else. The two that were emoji before (⚙, and no profile icon at all)
 * rendered in whatever the OS felt like — colour, weight and baseline all outside
 * our reach.
 *
 * The one exception is the gear, which is the darkness toolkit's; see [GEAR_SVG]
 * for why an icon that also appears in the shell is not ours to redraw.
 *
 * The house style, matching the picker's chevron:
 *
 *  - Stroke, never fill. `stroke: currentColor`, so an icon takes its colour from
 *    the button around it and hover/disabled work with no extra rules.
 *  - `stroke-width: 1.5`, round caps and joins — the same weight as the chevron.
 *    The gear is the exception at 2, which is the weight the toolkit drew it at.
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
 * The gear — the darkness toolkit's own, copied glyph-for-glyph.
 *
 * The exception to this file's hand-drawn rule, and the reason is that this icon
 * is not ours to draw. The toolkit paints a cog in the topbar for its App
 * Settings sidebar (`ICON_APP_SETTINGS` in the toolkit's `TopBarActions.kt`), and
 * a *second*, differently-drawn cog two inches below it in the board pane reads
 * as two different controls rather than one idea. Ours was the ring-and-spokes
 * construction; the toolkit's is the classic notched cog. Whatever the merits,
 * matching the shell beats agreeing with this file's other paths.
 *
 * Copied rather than imported because the toolkit's constant is `private`. That
 * means it can drift: if the shell's cog changes, this one has to be re-copied by
 * hand. The paths are identical today, down to the stroke width of 2 — which is
 * heavier than the 1.5 the rest of this file uses, and is kept because the drawing
 * was tuned at that weight.
 */
private const val GEAR_SVG = """
<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"
     fill="none" stroke="currentColor" stroke-width="2"
     stroke-linecap="round" stroke-linejoin="round">
  <circle cx="12" cy="12" r="3"/>
  <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
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

/**
 * The check mark flagging the current project in the picker's menu — the
 * toolkit's, copied for the same reason as the gear.
 *
 * The world switcher's popover marks its live row with exactly this glyph, and
 * the project picker's popover is that popover: same chrome, same classes, same
 * job. A check drawn to our 1.5 house weight beside rows the toolkit's CSS is
 * painting would be a hairline where the shell's is not.
 *
 * Markup rather than an element, unlike everything else here: it goes into the
 * row's check slot as `innerHTML`, so a `span` around it would be a span too many
 * — the slot IS the span. See [BoardWindow].
 */
const val CHECK_SVG = """
<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true" focusable="false"
     fill="none" stroke="currentColor" stroke-width="2.2"
     stroke-linecap="round" stroke-linejoin="round">
  <polyline points="20 6 9 17 4 12"/>
</svg>
"""

/**
 * The agent mark — a robot's head — flagging an issue or comment an agent made.
 *
 * Hand-drawn to this file's rule rather than an emoji robot, for exactly the
 * reason the file header gives: a 🤖 renders in the OS's own colour, weight and
 * baseline, none of which a stylesheet can reach, and it would sit in a badge
 * whose whole job is to look deliberate. This one takes `currentColor` from the
 * badge around it like every other icon here.
 *
 * Construction on the usual 24×24 grid, kept plain so it still reads at the ~13px
 * a badge renders it: an antenna (a stalk from the crown to a dot above it), a
 * rounded-rect head, two dot eyes, and a straight mouth. The dot eyes and the
 * antenna are what make the head read as a machine rather than a face — a robot
 * is a face with the humanity drawn out of it, so the eyes are points, not
 * almonds, and there is a bolt on top.
 */
private const val BOT_SVG = """
<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"
     fill="none" stroke="currentColor" stroke-width="1.5"
     stroke-linecap="round" stroke-linejoin="round">
  <path d="M12 3 v2.5"/>
  <circle cx="12" cy="2.25" r="1"/>
  <rect x="4.5" y="5.5" width="15" height="12" rx="2.5"/>
  <circle cx="9.5" cy="11" r="1.1"/>
  <circle cx="14.5" cy="11" r="1.1"/>
  <path d="M9.5 14.25 h5"/>
</svg>
"""

/** The agent mark, for the "made by an agent" badge on an issue or comment. */
fun agentIcon(): HTMLElement = icon(BOT_SVG, "icon-agent")

/**
 * The close X — two strokes crossing, for the lightbox's corner button.
 *
 * Hand-drawn on the usual 24×24 grid at the file's 1.5 house weight, inset 6px
 * from every edge so the cross sits comfortably inside a 36px .icon-btn. An
 * element (via [closeIcon]), not a markup constant like [CHECK_SVG]: this one
 * goes into a real button that wants a sized span around it, the same as the
 * gear and profile marks.
 */
private const val CLOSE_SVG = """
<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"
     fill="none" stroke="currentColor" stroke-width="1.5"
     stroke-linecap="round" stroke-linejoin="round">
  <line x1="6" y1="6" x2="18" y2="18"/>
  <line x1="18" y1="6" x2="6" y2="18"/>
</svg>
"""

/** The cogwheel, for the project-settings button. */
fun gearIcon(): HTMLElement = icon(GEAR_SVG, "icon-gear")

/** The close X, for the lightbox's corner button. */
fun closeIcon(): HTMLElement = icon(CLOSE_SVG, "icon-close")

/** The Lunicle mark, for the topbar brand line. */
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
