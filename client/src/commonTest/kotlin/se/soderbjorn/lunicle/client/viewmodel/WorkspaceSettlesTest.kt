/**
 * The workspace's two "ready" signals, and the difference between them (LNL-165).
 *
 * [WorkspaceBackingViewModel.State.isRestored] means *something* is showable;
 * [WorkspaceBackingViewModel.State.isSettled] means it is the last word. Signed
 * in they are not the same moment, and the gap between them is a real one: the
 * project list arrives first and seeds the default layout, the stored layout
 * lands afterwards and replaces the whole workspace. Anything applied in that gap
 * — the boot deep links `?projectId=` and `?issue=`, which is how this was found —
 * is silently thrown away.
 *
 * So the property under test is a timing one: a restore that resolves LATE must
 * not be preceded by a claim that the layout has settled. The fetch is held open
 * on a [CompletableDeferred] and released by hand, which makes "late" exact rather
 * than a race the test happens to win.
 *
 * @see WorkspaceBackingViewModel.State.isSettled
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.clientserver.HttpLunicleApi
import se.soderbjorn.lunicle.clientserver.LunicleApi
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.UiSettingKeys
import se.soderbjorn.lunicle.clientserver.UiSettingsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A transport that answers exactly one question — the stored settings — and only
 * when told to.
 *
 * `by HttpLunicleApi(...)` supplies the ninety-odd members this test never calls,
 * without a page of stubs. The delegate is never asked anything: every call this
 * test makes lands on the override below, and any other would attempt a request
 * to a host that does not resolve and fail loudly, which is the right answer for a
 * fake that is being used for something it does not model.
 */
private class HeldSettingsApi(
    private val blob: String?,
    val released: CompletableDeferred<Unit> = CompletableDeferred(),
) : LunicleApi by HttpLunicleApi(baseUrl = "http://workspace-settles.invalid") {

    override suspend fun uiSettings(): UiSettingsState {
        released.await()
        return UiSettingsState(
            userId = USER_ID,
            settings = blob?.let { mapOf(UiSettingKeys.WORKSPACE to it) } ?: emptyMap(),
        )
    }
}

private const val USER_ID: Long = 7

private val PROJECTS = listOf(
    ProjectSummary(id = 1, name = "Lunamux", namePrefix = "LMX"),
    ProjectSummary(id = 2, name = "Lunicle", namePrefix = "LNL"),
)

/** The layout this account arranged for itself: one tab, holding project 2. */
private fun storedWorkspace(): Workspace {
    val pane = PaneRef.Board(2)
    return Workspace(
        tabs = listOf(WorkspaceTab(id = "t9", name = "Mine", panes = listOf(pane), activePaneId = pane.paneId)),
        activeTabId = "t9",
    )
}

class WorkspaceSettlesTest {

    /**
     * `Dispatchers.Unconfined` rather than a test dispatcher: the view model's one
     * coroutine suspends on the fetch and resumes on the release, and unconfined
     * resumption happens inline on the releasing call — so every assertion below
     * reads a state that is already final, with no scheduler to pump.
     */
    private fun viewModelOn(api: HeldSettingsApi) =
        WorkspaceBackingViewModel(StorageRepository(api), CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `the seeded default is restored but not settled while the stored layout is in flight`() {
        val api = HeldSettingsApi(WorkspaceCodec.encode(storedWorkspace()))
        val vm = viewModelOn(api)

        vm.onSessionChanged(identity = USER_ID, isKnown = true)
        vm.onProjectsChanged(PROJECTS)

        // The default is up, so the app has something to draw and `reconcile` may
        // run — but the account's own layout has not answered yet.
        assertTrue(vm.stateFlow.value.isRestored, "the seeded default is showable")
        assertFalse(
            vm.stateFlow.value.isSettled,
            "a stored layout still in flight will replace this one; nothing may act on it yet",
        )
        assertEquals(2, vm.stateFlow.value.workspace.tabs.size, "one tab per accessible project")

        // ...and this is the clobber the deep links used to walk into: the layout
        // that was on screen when `isRestored` went true is gone.
        api.released.complete(Unit)
        assertTrue(vm.stateFlow.value.isSettled, "the stored layout has answered")
        assertEquals(listOf("t9"), vm.stateFlow.value.workspace.tabs.map { it.id })

        // The point of the flag: a board opened AFTER it survives.
        vm.onBoardOpened(1)
        assertTrue(
            vm.stateFlow.value.workspace.tabs.any { it.hasBoardFor(1) },
            "a deep link applied once settled is not overwritten",
        )
    }

    @Test
    fun `an account with nothing stored settles on the answer, not on the layout`() {
        val api = HeldSettingsApi(blob = null)
        val vm = viewModelOn(api)

        vm.onSessionChanged(identity = USER_ID, isKnown = true)
        vm.onProjectsChanged(PROJECTS)
        assertFalse(vm.stateFlow.value.isSettled, "the fetch has not answered yet")

        // "Nothing stored" commits no workspace at all — the default the list
        // already seeded stands — so settling cannot be a side effect of a commit.
        api.released.complete(Unit)
        assertTrue(vm.stateFlow.value.isSettled, "an empty answer is still an answer")
        assertEquals(2, vm.stateFlow.value.workspace.tabs.size)
    }

    @Test
    fun `a visitor settles at once, having nothing to wait for`() {
        val vm = viewModelOn(HeldSettingsApi(blob = null))

        vm.onSessionChanged(identity = null, isKnown = true)

        assertTrue(vm.stateFlow.value.isSettled, "signed out there is no stored layout to fetch")
        assertFalse(vm.stateFlow.value.isRestored, "...but nothing is showable until the list arrives")
        vm.onProjectsChanged(PROJECTS)
        assertTrue(vm.stateFlow.value.isRestored)
    }

    @Test
    fun `a restore that answers after a sign-out is dropped rather than applied`() {
        val api = HeldSettingsApi(WorkspaceCodec.encode(storedWorkspace()))
        val vm = viewModelOn(api)

        vm.onSessionChanged(identity = USER_ID, isKnown = true)
        vm.onSessionChanged(identity = null, isKnown = true)
        vm.onProjectsChanged(PROJECTS.take(1))
        api.released.complete(Unit)

        assertEquals(
            listOf(1L),
            vm.stateFlow.value.workspace.tabs.flatMap { it.panes.map { pane -> pane.projectId } },
            "the account's tab must not appear under the visitor who replaced them",
        )
    }
}
