/**
 * The one place the copied persistence keys are checked against the originals.
 *
 * `UiSettingKeys` in :clientServer spells out two lunula constants as
 * plain strings, because that module is shared with the JVM server and has no
 * business depending on a browser shell. The copies are load-bearing on both
 * sides of the wire: they are the column values in `user_ui_settings` and the
 * keys the toolkit asks the persister for.
 *
 * :web is the only module that can see both, so the comparison lives here. If a
 * toolkit upgrade renames one, this fails — instead of everyone's stored theme
 * quietly becoming unreachable, filed under a key nothing reads any more, with
 * no error anywhere to say so.
 */
package se.soderbjorn.lunicle

import se.soderbjorn.lunula.core.PersistKeys
import se.soderbjorn.lunicle.clientserver.UiSettingKeys
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeKeysMatchToolkitTest {

    @Test
    fun `the selection key is still the toolkit's`() {
        assertEquals(PersistKeys.THEME_V2_SELECTION, UiSettingKeys.THEME_SELECTION)
    }

    @Test
    fun `the custom-themes key is still the toolkit's`() {
        assertEquals(PersistKeys.THEME_V2_CUSTOM, UiSettingKeys.THEME_CUSTOM)
    }

    @Test
    fun `the layout-state key is still the toolkit's`() {
        assertEquals(PersistKeys.LAYOUT_STATE, UiSettingKeys.LAYOUT_STATE)
    }

    @Test
    fun `the sidebar-width key is still the toolkit's`() {
        assertEquals(PersistKeys.SIDEBAR_WIDTH, UiSettingKeys.SIDEBAR_WIDTH)
    }

    /**
     * The allowlist is the four toolkit keys plus Lunicle's own two, and
     * nothing else.
     *
     * The set is what the server's allowlist is written against, so a key added
     * to a constant and forgotten in the set would be a setting the client writes
     * and the server silently refuses — and a key added to the set but to no real
     * feature would be storage nothing accounts for. Both halves of that are
     * pinned here.
     *
     * [UiSettingKeys.PROJECT_PREFS] and [UiSettingKeys.WORKSPACE] are
     * deliberately NOT checked against a `PersistKeys` constant the way the
     * toolkit keys are: they are Lunicle's own keys, parsed by Lunicle and never
     * handed to the toolkit persister, so there is no upstream original for them
     * to drift from.
     */
    @Test
    fun `the allowlist is the toolkit keys plus Lunicle's own`() {
        assertEquals(
            setOf(
                PersistKeys.THEME_V2_SELECTION,
                PersistKeys.THEME_V2_CUSTOM,
                PersistKeys.LAYOUT_STATE,
                PersistKeys.SIDEBAR_WIDTH,
                UiSettingKeys.PROJECT_PREFS,
                UiSettingKeys.WORKSPACE,
            ),
            UiSettingKeys.persisted,
        )
    }
}
