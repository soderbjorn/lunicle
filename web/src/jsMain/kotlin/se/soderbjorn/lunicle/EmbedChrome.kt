/**
 * How much of the app's chrome an embed asked for: `?chrome=full|minimal|none|bare`.
 *
 * For a host that wants ONE board in a block on its own page — a Notion page, say —
 * rather than the whole tracker in a frame. The default is the full app, exactly as
 * before; the other three strip the navigation a small block has no room for and no
 * use for, and then progressively the board window's own frame:
 *
 *  - `full` (or absent, or anything unrecognised): sidebar, tab strip and top bar.
 *  - `minimal`: no sidebar and no tab strip. The top bar stays, with the account
 *    menu and the notifications bell in it.
 *  - `none`: `minimal`, and the top bar goes too once somebody is signed in. A
 *    signed-out visitor keeps it, because the sign-in button lives there and a
 *    frame with no way to sign in would be a dead end for a private board. The
 *    board window also fills the frame edge to edge — no app background around it —
 *    and loses its window controls (the "more" menu with Rename, minimise, maximise
 *    and close): in a host's block there is nothing to arrange it against.
 *  - `bare`: `none`, and the board window loses its title bar and border as well,
 *    leaving only the board itself.
 *
 * The window rules apply to the BOARD pane only (`data-pane-id="board-…"`): an
 * issue opened from the board is still a window of its own, and without its close
 * button it could not be put away again.
 *
 * Paired with a board (`?projectId=` or `?project=`), anything short of `full` also
 * pins the workspace to that board and stops it being read or saved — see
 * [se.soderbjorn.lunicle.client.viewmodel.PinnedBoard] for why the embed must not be
 * the reader's workspace.
 *
 * Read once at boot by `main.kt`, which also sets [ATTRIBUTE] on the body so
 * `styles.css` can do the rest.
 */
package se.soderbjorn.lunicle

import se.soderbjorn.lunicle.client.viewmodel.PinnedBoard

/**
 * The chrome levels, in the order they strip things away.
 *
 * @property key the spelling on the URL and in [ATTRIBUTE].
 * @property showsNavigation whether the sidebar and tab strip are rendered. Passed
 *   straight to the toolkit's `showSidebar` / `showTabStrip`.
 */
internal enum class EmbedChrome(val key: String, val showsNavigation: Boolean) {
    Full("full", showsNavigation = true),
    Minimal("minimal", showsNavigation = false),
    None("none", showsNavigation = false),
    Bare("bare", showsNavigation = false),
    ;

    companion object {
        /**
         * The body attribute carrying [key], read by `styles.css`. Set for every
         * level but [Full]; the full app carries no attribute at all.
         */
        const val ATTRIBUTE: String = "data-lnl-chrome"
    }
}

/**
 * The [EmbedChrome] a page's `?chrome=` names.
 *
 * Pure, so `main.kt`'s parameter reading is tested without a DOM. Case-insensitive
 * and trimmed, because the value is typed by hand into somebody else's embed dialog.
 * Anything unrecognised is [EmbedChrome.Full]: an unknown value must never take the
 * navigation away from somebody.
 *
 * @param value the raw parameter, or null when absent.
 * @return the chrome level to render.
 */
internal fun embedChromeFromParam(value: String?): EmbedChrome {
    val wanted = value?.trim()?.lowercase()
    return EmbedChrome.entries.firstOrNull { it.key == wanted } ?: EmbedChrome.Full
}

/**
 * The board a page should be pinned to, or null for the full workspace.
 *
 * Pinned only when BOTH halves are there: chrome short of [EmbedChrome.Full], and a
 * board named by `?projectId=` or `?project=`. Chrome alone pins nothing (there is
 * no board to pin), and a board link on the full app is the ordinary deep link it
 * always was, opening that board inside the reader's own workspace.
 *
 * @param chrome the parsed `?chrome=`.
 * @param projectId the parsed `?projectId=`, or null.
 * @param projectName the parsed `?project=`, or null.
 * @return the pin to hand `WorkspaceBackingViewModel`, or null.
 */
internal fun pinnedBoardFor(chrome: EmbedChrome, projectId: Long?, projectName: String?): PinnedBoard? {
    if (chrome.showsNavigation) return null
    if (projectId == null && projectName.isNullOrBlank()) return null
    return PinnedBoard(projectId = projectId, projectName = projectName)
}
