package se.soderbjorn.lunicle

import se.soderbjorn.lunicle.client.viewmodel.PinnedBoard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `?chrome=` and the board pin it implies, tested off the pure functions rather
 * than main.kt's readers (which read window.location).
 *
 * The property that matters most is the default: absent or unrecognised must be the
 * full app, because a typo in somebody's embed dialog must never take the navigation
 * away from a reader.
 */
class EmbedChromeTest {

    @Test
    fun the_three_levels_parse_case_insensitively() {
        assertEquals(EmbedChrome.Full, embedChromeFromParam("full"))
        assertEquals(EmbedChrome.Minimal, embedChromeFromParam("minimal"))
        assertEquals(EmbedChrome.None, embedChromeFromParam("none"))
        assertEquals(EmbedChrome.None, embedChromeFromParam(" NONE "), "typed by hand into an embed dialog")
    }

    @Test
    fun absent_or_unknown_is_the_full_app() {
        assertEquals(EmbedChrome.Full, embedChromeFromParam(null))
        assertEquals(EmbedChrome.Full, embedChromeFromParam(""))
        assertEquals(EmbedChrome.Full, embedChromeFromParam("minimial"))
        assertTrue(EmbedChrome.Full.showsNavigation)
        assertFalse(EmbedChrome.Minimal.showsNavigation)
        assertFalse(EmbedChrome.None.showsNavigation)
    }

    @Test
    fun a_board_is_pinned_only_with_reduced_chrome() {
        assertEquals(PinnedBoard(projectId = 3), pinnedBoardFor(EmbedChrome.Minimal, 3, null))
        assertEquals(PinnedBoard(projectName = "Ops"), pinnedBoardFor(EmbedChrome.None, null, "Ops"))
        assertNull(pinnedBoardFor(EmbedChrome.Full, 3, null), "a board link on the full app is the ordinary deep link")
        assertNull(pinnedBoardFor(EmbedChrome.None, null, null), "chrome alone has no board to pin")
        assertNull(pinnedBoardFor(EmbedChrome.Minimal, null, "  "))
    }
}
