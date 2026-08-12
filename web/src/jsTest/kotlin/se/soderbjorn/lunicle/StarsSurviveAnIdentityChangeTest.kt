/**
 * The stars are in the snapshot [ThemePersister] hands the shell.
 *
 * Boot does not go through that snapshot — the shell reads
 * `darkness.theme.v2.favorites` off the persister itself — so this is the one
 * path where the favourites can go missing, and going missing there is not a
 * cosmetic loss. `AppShellHandle.setThemeSnapshot` folds a pushed snapshot into
 * the theme manager's own state wholesale for an app that supplies no
 * `settingsHost`, which Lunicle does not; a snapshot without favourites empties
 * them, and the next write to the account — a flick of the sun/moon control — is
 * then a write of "nothing starred" over everything the account had starred.
 *
 * main.kt pushes exactly this snapshot every time the identity changes, so the
 * loss would have landed on signing in.
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.promise
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.clientserver.HttpLunicleApi
import se.soderbjorn.lunicle.clientserver.LunicleApi
import se.soderbjorn.lunicle.clientserver.UiSettingKeys
import se.soderbjorn.lunicle.clientserver.UiSettingsState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A transport that answers one question — what this account has stored.
 *
 * `by HttpLunicleApi(...)` supplies the members these tests never call, as
 * :client's own fakes do. The delegate is pointed at a host that does not
 * resolve, so anything this fake does not model fails loudly rather than
 * quietly reaching the network.
 */
private class StoredSettingsApi(
    private val stored: Map<String, String>,
) : LunicleApi by HttpLunicleApi(baseUrl = "http://stars-survive.invalid") {

    override suspend fun uiSettings(): UiSettingsState =
        UiSettingsState(userId = 7, settings = stored)
}

class StarsSurviveAnIdentityChangeTest {

    private val scope = CoroutineScope(Job())

    private fun persisterOver(stored: Map<String, String>) =
        ThemePersister(StorageRepository(StoredSettingsApi(stored)), scope)

    @Test
    fun the_pushed_snapshot_carries_what_the_account_starred() = scope.promise {
        val persister = persisterOver(
            mapOf(UiSettingKeys.THEME_FAVORITES to """["Solarized Dark","Ayu Light"]"""),
        )
        persister.load()

        assertEquals(listOf("Solarized Dark", "Ayu Light"), persister.snapshot().favorites)
    }

    @Test
    fun an_account_that_has_starred_nothing_pushes_nothing() = scope.promise {
        // The other half of the same guarantee: the snapshot reports the stored
        // set, so an account with no stars pushes an empty one rather than
        // whatever the tab happened to be holding.
        val persister = persisterOver(emptyMap())
        persister.load()

        assertEquals(emptyList(), persister.snapshot().favorites)
    }
}
