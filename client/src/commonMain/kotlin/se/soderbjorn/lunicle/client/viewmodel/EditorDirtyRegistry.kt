/**
 * The one place that knows whether any editor anywhere has unsaved changes, and
 * how to close every open editor at once.
 *
 * LNL-84 made switching project a top-level move that redraws everything below it:
 * the board reloads, the open issue windows close, the Discussion tab resets. That
 * is fine for a reader, but it would silently throw away a half-written issue, a
 * comment being typed, or a forum post in progress. So a switch first asks — but
 * only when there is genuinely something to lose, which no single view model can
 * answer on its own: the dirty editor might be an issue window on one tab while
 * the picker sits on another. This registry is that shared answer.
 *
 * Every open editor registers itself while it lives and drops out when it closes
 * (its coroutine scope completing is the signal — see the editors' `init`), handing
 * over two closures:
 *
 *  - **isDirty** — read on demand, never cached, so the answer is always current.
 *  - **discardAndClose** — how to throw this editor away *through its own path*,
 *    which matters because a "New issue"/new-comment/new-post draft is a real row
 *    on the server that only the editor knows how to delete. Closing one by force
 *    from outside would orphan that row; routing through the editor lets its own
 *    scope finish the delete before the window goes. See
 *    [IssueBackingViewModel.onSwitchAway] and the composers' `onCancelTapped`.
 *
 * Held by [MainScreenBackingViewModel], which reads [hasDirtyEditors] to decide
 * whether a switch needs the confirmation dialog and calls [closeAllForSwitch]
 * once the switch is going ahead. Not a singleton: one instance is created at app
 * start and threaded to the view models that need it, so tests get their own.
 */
package se.soderbjorn.lunicle.client.viewmodel

/**
 * A live handle for one registered editor. The editor keeps it and calls [cancel]
 * when it closes — wired to its scope's completion, so a disposed window cannot
 * leave a stale "still dirty" entry behind.
 */
fun interface EditorRegistration {
    fun cancel()
}

/** See the file preamble. */
class EditorDirtyRegistry {
    private class Entry(val isDirty: () -> Boolean, val discardAndClose: () -> Unit)

    // Insertion-ordered and keyed by a private counter rather than a caller-supplied
    // name: there can be several editors of the same kind open at once (two issue
    // windows, a comment over one of them), and making each mint its own key would
    // be a second thing to get unique. The handle is the identity instead.
    private val entries = LinkedHashMap<Int, Entry>()
    private var nextId = 0

    /**
     * Register an open editor. Returns a handle the editor cancels when it closes.
     *
     * @param isDirty reports whether this editor has unsaved changes right now.
     * @param discardAndClose throws this editor away through its own close path,
     *   deleting any draft row it owns. Invoked by [closeAllForSwitch].
     */
    fun register(isDirty: () -> Boolean, discardAndClose: () -> Unit): EditorRegistration {
        val id = nextId++
        entries[id] = Entry(isDirty, discardAndClose)
        return EditorRegistration { entries.remove(id) }
    }

    /** Whether any registered editor has unsaved changes — the guard's whole question. */
    val hasDirtyEditors: Boolean get() = entries.values.any { it.isDirty() }

    /**
     * Close every open editor for a project switch, each through its own discard
     * path so drafts are deleted rather than orphaned.
     *
     * Iterates a snapshot: a `discardAndClose` may synchronously remove its own
     * entry (a clean window closes at once), and the drafts that delete
     * asynchronously drop out when their scopes complete a beat later. Closing an
     * already-clean editor is harmless, so this needs no dirty filter — a switch
     * resets everything on the tabs it leaves, dirty or not.
     */
    fun closeAllForSwitch() {
        entries.values.toList().forEach { it.discardAndClose() }
    }
}
