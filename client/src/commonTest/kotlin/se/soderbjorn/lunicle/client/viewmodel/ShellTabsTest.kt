/**
 * LNL-58's behaviour table, asserted row by row.
 *
 * The table has four rows and three of them produce a tab strip that is either
 * absent or two tabs wide, so the interesting mistakes are not "the tabs are
 * wrong" but "the tabs appeared where they should not have":
 *
 *  - **With the toggle off, nothing changed.** This is the strongest claim in
 *    the ticket and the easiest to break by accident, because every other row
 *    is about producing tabs.
 *  - **An issue-tracker embed stays an issue-tracker embed.** A site that framed
 *    the board must not grow a Discussion tab, and — more subtly — a *discussion*
 *    embed must not grow an Issues tab, which would put a board on a page that
 *    never asked for one. That is why the tab set is fixed at construction and
 *    [ShellBackingViewModel.onTabSelected] cannot widen it.
 *  - **A misspelt tab falls back to the old app**, not to a surprise.
 *
 * @see ShellBackingViewModel
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellTabsTest {
    // ── Row 1: the toggle is off ─────────────────────────────────────────────

    /** Nothing new, anywhere, in either mode — the ticket's byte-for-byte row. */
    @Test
    fun `with the toggle off there are no tabs, embedded or not`() {
        listOf(true, false).forEach { embedded ->
            val state = shell(forumsEnabled = false, isEmbedded = embedded, tab = ShellTab.DISCUSSION).stateFlow.value
            assertEquals(emptyList(), state.tabs, "The toggle was off and tabs appeared (embedded=$embedded).")
            assertFalse(state.showTabStrip)
            assertEquals(ShellTab.ISSUES, state.activeTab)
            assertFalse(state.forumsEnabled)
        }
    }

    /**
     * Including when `?tab=discussion` is present, which is the combination a
     * stale link produces.
     *
     * Asserted separately from the loop above because it is the one that would
     * pass by accident if the implementation read the tab first and the toggle
     * second.
     */
    @Test
    fun `a tab parameter without the toggle is inert`() {
        val state = shell(forumsEnabled = false, isEmbedded = false, tab = ShellTab.MESSAGES).stateFlow.value
        assertEquals(emptyList(), state.tabs)
        assertEquals(ShellTab.ISSUES, state.activeTab)
    }

    // ── Rows 2 and 3: embedded ───────────────────────────────────────────────

    /** The default embed: the issue tracker, alone, exactly as today. */
    @Test
    fun `an embed with no tab parameter is the issue tracker alone`() {
        val state = shell(forumsEnabled = true, isEmbedded = true, tab = null).stateFlow.value
        assertEquals(emptyList(), state.tabs, "An issue-tracker embed grew a tab strip.")
        assertEquals(ShellTab.ISSUES, state.activeTab)
        assertTrue(state.forumsEnabled, "The toggle was lost, so the URL writer would drop ?forums=1.")
    }

    /** And `?tab=issues` said out loud means the same thing. */
    @Test
    fun `an embed asking for issues is the issue tracker alone`() {
        assertEquals(
            emptyList(),
            shell(forumsEnabled = true, isEmbedded = true, tab = ShellTab.ISSUES).stateFlow.value.tabs,
        )
    }

    /** The discussion embed: two tabs, and pointedly no Issues among them. */
    @Test
    fun `an embed asking for discussion gets exactly Discussion and Messages`() {
        val state = shell(forumsEnabled = true, isEmbedded = true, tab = ShellTab.DISCUSSION).stateFlow.value
        assertEquals(listOf(ShellTab.DISCUSSION, ShellTab.MESSAGES), state.tabs)
        assertEquals(ShellTab.DISCUSSION, state.activeTab)
        assertTrue(state.showTabStrip)
    }

    /**
     * `?tab=messages` gets the same two tabs with Messages selected.
     *
     * The alternative reading — treat anything that is not `discussion` as the
     * issue tracker — would silently ignore what the embedding site asked for.
     */
    @Test
    fun `an embed asking for messages gets the discussion side with Messages active`() {
        val state = shell(forumsEnabled = true, isEmbedded = true, tab = ShellTab.MESSAGES).stateFlow.value
        assertEquals(listOf(ShellTab.DISCUSSION, ShellTab.MESSAGES), state.tabs)
        assertEquals(ShellTab.MESSAGES, state.activeTab)
    }

    // ── Row 4: the full site ─────────────────────────────────────────────────

    /** Three tabs, Issues first and active by default. */
    @Test
    fun `the full site with the toggle on gets all three tabs`() {
        val state = shell(forumsEnabled = true, isEmbedded = false, tab = null).stateFlow.value
        assertEquals(listOf(ShellTab.ISSUES, ShellTab.DISCUSSION, ShellTab.MESSAGES), state.tabs)
        assertEquals(ShellTab.ISSUES, state.activeTab)
        assertTrue(state.showTabStrip)
    }

    /** Deep-linking straight to a tab on a cold load. */
    @Test
    fun `the full site honours a tab parameter on a cold load`() {
        assertEquals(
            ShellTab.MESSAGES,
            shell(forumsEnabled = true, isEmbedded = false, tab = ShellTab.MESSAGES).stateFlow.value.activeTab,
        )
    }

    // ── Selection ────────────────────────────────────────────────────────────

    /** The ordinary case. */
    @Test
    fun `selecting a tab moves to it`() {
        val model = shell(forumsEnabled = true, isEmbedded = false, tab = null)
        model.onTabSelected(ShellTab.DISCUSSION)
        assertEquals(ShellTab.DISCUSSION, model.stateFlow.value.activeTab)
    }

    /**
     * A discussion embed cannot be talked into showing the board.
     *
     * The whole reason the tab set is fixed at construction: an embedding site
     * asked for the discussion side, and no gesture inside the frame should put
     * a project board on their page.
     */
    @Test
    fun `an embed cannot be selected into a tab it does not have`() {
        val model = shell(forumsEnabled = true, isEmbedded = true, tab = ShellTab.DISCUSSION)
        model.onTabSelected(ShellTab.ISSUES)
        assertEquals(ShellTab.DISCUSSION, model.stateFlow.value.activeTab, "An embed switched to a tab it does not offer.")
        assertEquals(listOf(ShellTab.DISCUSSION, ShellTab.MESSAGES), model.stateFlow.value.tabs)
    }

    /** And with no strip at all, selection does nothing rather than something odd. */
    @Test
    fun `selecting a tab with the toggle off does nothing`() {
        val model = shell(forumsEnabled = false, isEmbedded = false, tab = null)
        model.onTabSelected(ShellTab.MESSAGES)
        assertEquals(ShellTab.ISSUES, model.stateFlow.value.activeTab)
    }

    // ── The key mapping, which is URL-visible ────────────────────────────────

    /** Absent and misspelt are the same answer, and it is the safe one. */
    @Test
    fun `an unrecognised tab key is null, which reads as Issues`() {
        assertEquals(null, ShellTab.fromKey("forum"))
        assertEquals(null, ShellTab.fromKey(null))
        assertEquals(null, ShellTab.fromKey(""))
        assertEquals(
            emptyList(),
            shell(forumsEnabled = true, isEmbedded = true, tab = ShellTab.fromKey("dicsussion")).stateFlow.value.tabs,
        )
    }

    /** The keys themselves, because they are in URLs people paste at each other. */
    @Test
    fun `the tab keys are the ones the URL scheme promises`() {
        assertEquals(ShellTab.ISSUES, ShellTab.fromKey("issues"))
        assertEquals(ShellTab.DISCUSSION, ShellTab.fromKey("discussion"))
        assertEquals(ShellTab.MESSAGES, ShellTab.fromKey("messages"))
    }

    private fun shell(forumsEnabled: Boolean, isEmbedded: Boolean, tab: ShellTab?) =
        ShellBackingViewModel(forumsEnabled = forumsEnabled, isEmbedded = isEmbedded, preferredTab = tab)
}
