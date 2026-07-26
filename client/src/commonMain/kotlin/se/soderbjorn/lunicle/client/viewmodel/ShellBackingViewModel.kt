/**
 * Which of Lunicle's top-level tabs exist, and which one is showing.
 *
 * The forum feature (LNL-30) adds Discussion and Messages beside the issue
 * tracker. All three live behind a **master toggle** that is deliberately
 * client-side only: its job is to keep the forum out of public view until it is
 * good enough, not to protect anything. Somebody who works out the parameter and
 * types it gets an early look at a feature, which is not a problem worth a
 * server-side gate — see LNL-30, which settles this explicitly.
 *
 * ── Why the tab set is fixed at construction ────────────────────────────────
 *
 * Everything except *which* tab is active is decided once, from the URL, and
 * never changes afterwards. Two of the three inputs cannot change without a
 * reload anyway — the master toggle is a query parameter, and whether the app is
 * inside somebody else's iframe is a fact about the page — and the third,
 * embedded mode's choice of *which* embed this is, must not change: a site that
 * embedded the issue tracker has to keep getting the issue tracker, and a
 * discussion embed that grew an Issues tab the first time somebody clicked
 * around would be putting a board on a page that never asked for one.
 *
 * So [onTabSelected] can only move within [State.tabs]; it cannot add to it.
 *
 * @see ShellTab
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One of the app's top-level tabs.
 *
 * @property key the `?tab=` spelling. Wire format in the weak sense that it
 *   appears in URLs people paste at each other, so renaming one breaks their
 *   links — but no server reads it, which is why this lives in the client rather
 *   than in `clientServer`.
 * @property label what the tab strip shows.
 */
enum class ShellTab(val key: String, val label: String) {
    ISSUES("issues", "Issues"),
    DISCUSSION("discussion", "Discussion"),
    MESSAGES("messages", "Messages"),
    ;

    companion object {
        /**
         * The tab [key] names, or null if it names none.
         *
         * Null rather than a default, so the caller decides what an absent
         * parameter and a misspelt one mean — which here is the same thing, and
         * is [ISSUES]. See [ShellBackingViewModel].
         */
        fun fromKey(key: String?): ShellTab? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Owns the tab set and the active tab.
 *
 * No collaborators at all — no repository, no scope. Nothing here is fetched;
 * the whole state is a function of three booleans-worth of URL, which is why
 * this view model is constructed with its inputs rather than given a `start()`
 * to call later. The view reads [stateFlow]'s current value synchronously while
 * building the shell spec, which it could not do if the answer arrived
 * asynchronously.
 *
 * @param forumsEnabled the master toggle, from `?forums=1`.
 * @param isEmbedded whether Lunicle is running inside somebody else's page.
 * @param preferredTab the tab from `?tab=`, or null when absent or unrecognised.
 *   In embedded mode this doubles as the embed's *view selector* — see [State].
 */
class ShellBackingViewModel(
    forumsEnabled: Boolean,
    isEmbedded: Boolean,
    preferredTab: ShellTab?,
) {
    /**
     * Immutable snapshot of the shell's tab configuration.
     *
     * @property tabs the tabs to render, in order. **Empty means no tab strip
     *   at all**, which is both "the toggle is off" and "this is an embed of the
     *   issue tracker alone" — two different reasons for the same, correct,
     *   answer of showing exactly what Lunicle showed before LNL-30.
     * @property activeTab the tab whose content is showing. Always a member of
     *   [tabs] when [tabs] is non-empty, and [ShellTab.ISSUES] when it is empty.
     * @property forumsEnabled whether the master toggle is on. Kept even though
     *   [tabs] mostly implies it, because the two genuinely differ in the one
     *   case that matters: an issue-tracker embed with the toggle *on* has no
     *   tabs, and the URL writer still has to keep `?forums=1` alive so a reload
     *   does not silently drop back to the old app.
     */
    data class State(
        val tabs: List<ShellTab> = emptyList(),
        val activeTab: ShellTab = ShellTab.ISSUES,
        val forumsEnabled: Boolean = false,
    ) {
        /** Whether the toolkit should render a tab strip at all. */
        val showTabStrip: Boolean get() = tabs.isNotEmpty()
    }

    private val _stateFlow = MutableStateFlow(initialState(forumsEnabled, isEmbedded, preferredTab))

    /** The current tab configuration, observed by the view. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * The user picked a tab.
     *
     * Ignored unless the tab is one this shell actually offers. Not defensive
     * for its own sake: the toolkit reports the id it was given, so a mismatch
     * would mean a bug here rather than hostile input — but silently switching
     * to a tab that is not in the strip would leave the strip highlighting
     * nothing while the content changed underneath it, which is worse than
     * ignoring the report.
     */
    fun onTabSelected(tab: ShellTab) {
        val state = _stateFlow.value
        if (tab !in state.tabs || tab == state.activeTab) return
        _stateFlow.value = state.copy(activeTab = tab)
    }

    private companion object {
        /**
         * The four rows of LNL-58's behaviour table, in one place.
         *
         * | Mode | Toggle | Result |
         * | --- | --- | --- |
         * | any | off | exactly as before LNL-30 — no tabs |
         * | embedded | on, `?tab=issues` or absent | the issue tracker alone, no tabs |
         * | embedded | on, `?tab=discussion\|messages` | two tabs: Discussion, Messages |
         * | full site | on | three tabs: Issues, Discussion, Messages |
         *
         * The embedded rows are the ones worth reading twice. `?tab=` is doing
         * two jobs there — it picks *which embed this is*, and then it is the
         * tab position within it — and the split falls between "issues" and
         * everything else because that is the choice the embedding site is
         * making: a board, or the discussion side. An embed asking for
         * `?tab=messages` gets the discussion side with Messages selected, which
         * is the only reading that does not silently ignore what it asked for.
         *
         * Absent and unrecognised are both [ShellTab.ISSUES], which is why this
         * takes a nullable rather than a default: a site that ships a typo gets
         * the old, safe app rather than a surprise.
         */
        fun initialState(forumsEnabled: Boolean, isEmbedded: Boolean, preferredTab: ShellTab?): State {
            if (!forumsEnabled) return State()
            val wanted = preferredTab ?: ShellTab.ISSUES
            return when {
                !isEmbedded -> State(
                    tabs = ShellTab.entries.toList(),
                    activeTab = wanted,
                    forumsEnabled = true,
                )
                wanted == ShellTab.ISSUES -> State(forumsEnabled = true)
                else -> State(
                    tabs = listOf(ShellTab.DISCUSSION, ShellTab.MESSAGES),
                    activeTab = wanted,
                    forumsEnabled = true,
                )
            }
        }
    }
}
