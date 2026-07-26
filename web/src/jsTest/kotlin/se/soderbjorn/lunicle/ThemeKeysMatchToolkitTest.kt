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

    /**
     * The allowlist is the two toolkit theme keys plus Lunicle's own
     * project-preferences key, and nothing else.
     *
     * The set is what the server's allowlist is written against, so a key added
     * to a constant and forgotten in the set would be a setting the client writes
     * and the server silently refuses — and a key added to the set but to no real
     * feature would be storage nothing accounts for. Both halves of that are
     * pinned here.
     *
     * [UiSettingKeys.PROJECT_PREFS] is deliberately NOT checked against a
     * `PersistKeys` constant the way the theme keys are: it is Lunicle's own key,
     * parsed by Lunicle and never handed to the toolkit persister, so there is no
     * upstream original for it to drift from.
     */
    @Test
    fun `the allowlist is the two theme keys plus the project-prefs key`() {
        assertEquals(
            setOf(
                PersistKeys.THEME_V2_SELECTION,
                PersistKeys.THEME_V2_CUSTOM,
                UiSettingKeys.PROJECT_PREFS,
            ),
            UiSettingKeys.persisted,
        )
    }
}
