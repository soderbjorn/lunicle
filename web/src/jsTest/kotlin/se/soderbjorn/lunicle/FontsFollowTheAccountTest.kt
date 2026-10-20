/**
 * The font picks follow the signed-in account, and an identity change does not
 * hand one person's fonts to the next (LNA-26).
 *
 * The fonts key has travelled since 88ead88; what this pins is the guard it now
 * shares with the shape (see ShapeFollowsTheAccountTest, which this mirrors). The
 * shell reads the fonts only at mount and re-writes them on every theme change
 * once the key is live, so without [ThemePersister]'s guard the first flick of
 * the sun/moon control after an in-place sign-in — or an admin starting or
 * stopping an impersonation — would overwrite the account's fonts with whoever's
 * were on screen before.
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.promise
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.clientserver.UiSettingKeys
import se.soderbjorn.lunula.core.AppearanceFonts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FontsFollowTheAccountTest {

    private val scope = CoroutineScope(Job())

    // The persister's fire-and-forget writes land here, so [settle] can wait for
    // exactly those before anything is asserted about what reached the server.
    private val writes = CoroutineScope(Job())

    private suspend fun settle() = writes.coroutineContext[Job]!!.children.toList().joinAll()

    private val serifReader = AppearanceFonts(
        proportionalFontFamily = "serif",
        proportionalFontSizePx = 16,
        monoFontSizePx = 13,
    ).toJson()

    private val bigSans = AppearanceFonts(sidebarFontFamily = "systemProp", sidebarFontSizePx = 15).toJson()

    private fun AccountSettingsApi.persister() = ThemePersister(StorageRepository(this), writes)

    private suspend fun AccountSettingsApi.sentFonts(): List<String> {
        settle()
        return sent.filter { it.first == UiSettingKeys.APPEARANCE_FONTS }.map { it.second }
    }

    @Test
    fun the_stored_fonts_are_what_the_shell_reads_at_mount() = scope.promise {
        val api = AccountSettingsApi(userId = 7, stored = mapOf(UiSettingKeys.APPEARANCE_FONTS to serifReader))
        val persister = api.persister()
        persister.start()

        assertEquals(serifReader, persister.read(UiSettingKeys.APPEARANCE_FONTS))
    }

    @Test
    fun fonts_picked_while_signed_in_are_sent_verbatim() = scope.promise {
        val api = AccountSettingsApi(userId = 7, stored = mapOf(UiSettingKeys.THEME_SELECTION to "{}"))
        val persister = api.persister()
        persister.start()

        persister.write(UiSettingKeys.APPEARANCE_FONTS, serifReader)

        assertEquals(listOf(serifReader), api.sentFonts())
    }

    @Test
    fun fonts_picked_while_signed_out_are_kept_for_the_tab_and_not_sent() = scope.promise {
        val api = AccountSettingsApi(userId = null)
        val persister = api.persister()
        persister.start()

        persister.write(UiSettingKeys.APPEARANCE_FONTS, serifReader)

        assertEquals(serifReader, persister.read(UiSettingKeys.APPEARANCE_FONTS))
        settle()
        assertTrue(api.sent.isEmpty(), "Signed-out fonts were sent: ${api.sent}")
    }

    @Test
    fun the_previous_identitys_fonts_are_not_written_to_the_next() = scope.promise {
        // Signed in as one account, which reads in a serif...
        val api = AccountSettingsApi(
            userId = 7,
            stored = mapOf(
                UiSettingKeys.THEME_SELECTION to "{}",
                UiSettingKeys.APPEARANCE_FONTS to serifReader,
            ),
        )
        val persister = api.persister()
        persister.start()

        // ...then the browser becomes another account, with fonts of its own.
        api.userId = 8
        api.stored = mapOf(
            UiSettingKeys.THEME_SELECTION to "{}",
            UiSettingKeys.APPEARANCE_FONTS to bigSans,
        )
        persister.onIdentityChanged()
        settle()
        api.sent.clear()

        // The shell still paints the first account's fonts and re-writes them with
        // the next theme change. That is not the second account choosing them.
        persister.write(UiSettingKeys.APPEARANCE_FONTS, serifReader)
        val stale = api.sentFonts()
        assertTrue(stale.isEmpty(), "One account's fonts were written to another: $stale")

        // A pick the user actually makes is theirs, and from then on stored.
        val picked = AppearanceFonts(monoFontSizePx = 12).toJson()
        persister.write(UiSettingKeys.APPEARANCE_FONTS, picked)
        persister.write(UiSettingKeys.APPEARANCE_FONTS, serifReader)
        assertEquals(listOf(picked, serifReader), api.sentFonts())
    }

    @Test
    fun a_stranger_s_fonts_are_not_adopted_by_an_empty_account() = scope.promise {
        // One account signs out, leaving its fonts painted; a visitor flicks the
        // theme (which re-writes them) and signs in to an account that has stored
        // nothing. The adoption takes the visitor's choices, not the stranger's.
        val api = AccountSettingsApi(userId = 7, stored = mapOf(UiSettingKeys.APPEARANCE_FONTS to serifReader))
        val persister = api.persister()
        persister.start()

        api.userId = null
        persister.onIdentityChanged()
        persister.write(UiSettingKeys.APPEARANCE_FONTS, serifReader)

        api.userId = 9
        api.stored = emptyMap()
        settle()
        api.sent.clear()
        persister.onIdentityChanged()

        val adopted = api.sentFonts()
        assertTrue(adopted.isEmpty(), "A stranger's fonts were adopted into an empty account: $adopted")
    }

    @Test
    fun a_visitors_own_font_pick_is_adopted_by_an_empty_account() = scope.promise {
        val api = AccountSettingsApi(userId = null)
        val persister = api.persister()
        persister.start()
        persister.write(UiSettingKeys.APPEARANCE_FONTS, serifReader)

        api.userId = 9
        persister.onIdentityChanged()

        assertEquals(listOf(serifReader), api.sentFonts())
    }
}
