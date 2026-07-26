/**
 * The blob at rest — [UserProjectPreferences]'s reading and writing of the
 * per-user, per-project preferences the LNL-100 board persists.
 *
 * Two properties earn a test each. TOTALITY: [UserProjectPreferences.decode] is
 * contracted never to throw, because the stored value is opaque to the server and
 * an unreadable one must degrade to the default board rather than a boot failure —
 * so absent, blank, malformed and future-shaped blobs all have to resolve to the
 * empty map. PRUNING: [UserProjectPreferences.encode] drops a project whose record
 * hides nothing, so "no preference here" is written as absence rather than as an
 * empty record that would grow the blob and answer "has this user a preference?"
 * wrongly.
 *
 * @see UserProjectPreferences
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserProjectPreferencesTest {

    @Test
    fun `a map survives the round-trip verbatim`() {
        val original = mapOf(
            2L to UserProjectPrefs(hiddenColumnIds = listOf(5L, 6L)),
            9L to UserProjectPrefs(hiddenColumnIds = listOf(1L)),
        )
        assertEquals(original, UserProjectPreferences.decode(UserProjectPreferences.encode(original)))
    }

    @Test
    fun `an absent or blank blob decodes to no preferences`() {
        assertEquals(emptyMap(), UserProjectPreferences.decode(null))
        assertEquals(emptyMap(), UserProjectPreferences.decode(""))
        assertEquals(emptyMap(), UserProjectPreferences.decode("   "))
    }

    @Test
    fun `a malformed blob decodes to no preferences rather than throwing`() {
        assertEquals(emptyMap(), UserProjectPreferences.decode("{not json"))
        assertEquals(emptyMap(), UserProjectPreferences.decode("[]"))
        assertEquals(emptyMap(), UserProjectPreferences.decode("""{"byProject":42}"""))
    }

    @Test
    fun `unknown fields from a newer client are ignored, not fatal`() {
        // A future client added a field this one has never heard of. The columns it
        // does understand must still come through.
        val blob = """{"byProject":{"3":{"hiddenColumnIds":[7],"collapsedGroups":["x"]}},"newTopLevel":1}"""
        assertEquals(
            mapOf(3L to UserProjectPrefs(hiddenColumnIds = listOf(7L))),
            UserProjectPreferences.decode(blob),
        )
    }

    @Test
    fun `a project whose record hides nothing is pruned on encode`() {
        val map = mapOf(
            2L to UserProjectPrefs(hiddenColumnIds = listOf(5L)),
            9L to UserProjectPrefs(hiddenColumnIds = emptyList()),
        )
        val decoded = UserProjectPreferences.decode(UserProjectPreferences.encode(map))
        assertEquals(mapOf(2L to UserProjectPrefs(hiddenColumnIds = listOf(5L))), decoded)
    }

    @Test
    fun `an all-empty map encodes to an empty blob`() {
        val encoded = UserProjectPreferences.encode(mapOf(9L to UserProjectPrefs()))
        assertTrue(UserProjectPreferences.decode(encoded).isEmpty())
    }
}
