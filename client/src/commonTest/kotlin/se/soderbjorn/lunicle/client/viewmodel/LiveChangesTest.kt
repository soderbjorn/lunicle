/**
 * The web app staying live (LNL-225): the stream owner, and what the board and an
 * open issue window do with what it hears.
 *
 * The claims worth pinning, from the ticket:
 *
 *  - **Each kind lands where it should**, coalesced: a burst is one board re-read,
 *    with the cards somebody else touched marked.
 *  - **Own echoes are dropped.** An event this tab caused changes nothing.
 *  - **An edited field is never overwritten**; it is noted, and Reload takes theirs.
 *  - **Reset re-reads**, and drops whatever was queued.
 *  - **The connection follows the page**: signed in, visible, and the open boards.
 */
package se.soderbjorn.lunicle.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import se.soderbjorn.lunicle.client.StorageRepository
import se.soderbjorn.lunicle.clientserver.BoardState
import se.soderbjorn.lunicle.clientserver.HttpLunicleApi
import se.soderbjorn.lunicle.clientserver.IssueDetail
import se.soderbjorn.lunicle.clientserver.IssueSummary
import se.soderbjorn.lunicle.clientserver.LunicleApi
import se.soderbjorn.lunicle.clientserver.ProjectSummary
import se.soderbjorn.lunicle.clientserver.StatusItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PROJECT = 7L
private const val ISSUE = 41L

/** A transport that records what was opened and lets the test speak for the server. */
private class FakeTransport : ChangeStreamTransport {
    val opened = mutableListOf<String>()
    var handler: ChangeStreamHandler? = null
    var closed = 0

    override fun open(url: String, handler: ChangeStreamHandler): ChangeStreamConnection {
        opened += url
        this.handler = handler
        handler.onOpen()
        return ChangeStreamConnection { closed++ }
    }

    fun send(id: Long, kind: String, data: String) = handler!!.onEvent(id.toString(), kind, data)
}

/** One board and one issue, both editable by the test between reads. */
private class LiveServer : LunicleApi by HttpLunicleApi(baseUrl = "http://live.invalid") {
    var boardReads = 0
    var title = "Original"
    var description = "First"

    override suspend fun board(projectId: Long): BoardState {
        boardReads++
        return BoardState(
            project = ProjectSummary(PROJECT, "Test", "TST"),
            statuses = listOf(StatusItem(1, "New", 0)),
            issues = listOf(IssueSummary(id = ISSUE, number = 1, title = title, statusId = 1)),
        )
    }

    override suspend fun issue(id: Long): IssueDetail = IssueDetail(
        id = ISSUE,
        projectId = PROJECT,
        number = 1,
        title = title,
        description = description,
        statusId = 1,
        isDraft = false,
        canEdit = true,
    )
}

class LiveChangesTest {
    private val transport = FakeTransport()

    /**
     * Unconfined, and a debounce long enough never to fire inside a test: the tests
     * flush by hand ([LiveChangesBackingViewModel.flushNow]), which is what lets this
     * run on every target without a blocking runner.
     */
    private fun live(retry: List<Long> = listOf(600_000L)) = LiveChangesBackingViewModel(
        transport = transport,
        scope = CoroutineScope(Dispatchers.Unconfined),
        origin = "tab-1",
        debounceMillis = 600_000L,
        retryDelaysMillis = retry,
    )

    /** Run [block] against a signed-in stream on [PROJECT] and hand back what it signalled. */
    private fun signalsOf(block: (LiveChangesBackingViewModel) -> Unit): List<LiveSignal> {
        val vm = live()
        val got = mutableListOf<LiveSignal>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.signals.collect { got += it } }
        vm.onProjectsChanged(setOf(PROJECT))
        vm.onSessionChanged(signedIn = true)
        block(vm)
        vm.flushNow()
        collector.cancel()
        return got
    }

    // ── Each kind, coalesced ─────────────────────────────────────────────────

    @Test
    fun `a burst is one board signal naming the cards, one per issue, and one for the bell`() {
        val got = signalsOf {
            transport.send(1, "issue.updated", """{"projectId":7,"issueId":41,"actor":"Linus"}""")
            transport.send(2, "issue.moved", """{"projectId":7,"issueId":42,"actor":"Linus"}""")
            transport.send(3, "comment.added", """{"projectId":7,"issueId":41,"commentId":9}""")
            transport.send(4, "notification.changed", "{}")
        }
        assertEquals(LiveSignal.BoardChanged(PROJECT, setOf(41L, 42L)), got.filterIsInstance<LiveSignal.BoardChanged>().single())
        assertEquals(
            listOf(LiveSignal.IssueChanged(41, "Linus", false), LiveSignal.IssueChanged(42, "Linus", false)),
            got.filterIsInstance<LiveSignal.IssueChanged>(),
        )
        assertEquals(1, got.count { it == LiveSignal.NotificationsChanged })
    }

    @Test
    fun `a board change has no cards to flash, and a delete outranks an update`() {
        val got = signalsOf {
            transport.send(1, "board.changed", """{"projectId":7}""")
            transport.send(2, "issue.updated", """{"projectId":7,"issueId":41}""")
            transport.send(3, "issue.deleted", """{"projectId":7,"issueId":41,"actor":"Ada"}""")
        }
        assertEquals(setOf(41L), got.filterIsInstance<LiveSignal.BoardChanged>().single().issueIds)
        assertEquals(LiveSignal.IssueChanged(41, "Ada", deleted = true), got.filterIsInstance<LiveSignal.IssueChanged>().single())
    }

    @Test
    fun `this tab's own changes are not echoed`() {
        val got = signalsOf {
            transport.send(1, "issue.updated", """{"projectId":7,"issueId":41,"self":true}""")
            transport.send(2, "comment.added", """{"projectId":7,"issueId":41,"commentId":3,"self":true}""")
        }
        assertEquals(emptyList(), got)
    }

    @Test
    fun `a reset drops what was queued and asks for a re-read`() {
        val got = signalsOf {
            transport.send(1, "issue.updated", """{"projectId":7,"issueId":41}""")
            transport.send(2, "reset", "{}")
        }
        assertEquals(listOf<LiveSignal>(LiveSignal.Resync), got)
    }

    // ── The connection follows the page ──────────────────────────────────────

    @Test
    fun `it connects only while signed in and visible, and resumes where it left off`() {
        val vm = live()
        vm.onProjectsChanged(setOf(PROJECT))
        assertTrue(transport.opened.isEmpty(), "Nobody is signed in yet.")

        vm.onSessionChanged(signedIn = true)
        assertEquals("/api/v1/events?projects=7&origin=tab-1", transport.opened.single())
        assertTrue(vm.stateFlow.value.isConnected)

        transport.send(1234, "issue.updated", """{"projectId":7,"issueId":41,"self":true}""")
        vm.onVisibilityChanged(false)
        assertEquals(1, transport.closed)
        assertFalse(vm.stateFlow.value.isConnected, "A hidden tab holds no stream, so the bell's poll takes over.")

        vm.onVisibilityChanged(true)
        assertEquals("/api/v1/events?projects=7&origin=tab-1&last_event_id=1234", transport.opened.last())

        vm.onProjectsChanged(setOf(PROJECT, 3L))
        assertEquals("/api/v1/events?projects=3,7&origin=tab-1&last_event_id=1234", transport.opened.last())

        vm.onSessionChanged(signedIn = false)
        assertFalse(vm.stateFlow.value.isConnected)
        assertEquals(3, transport.opened.size, "Signing out must not reconnect.")
    }

    @Test
    fun `a refused stream is retried, not given up on`() {
        // A zero wait, so the retry runs inline on the unconfined scope.
        val vm = live(retry = listOf(0L))
        vm.onSessionChanged(signedIn = true)
        transport.handler!!.onClosed()
        assertEquals(2, transport.opened.size)
        assertTrue(vm.stateFlow.value.isConnected)
    }

    // ── What the board does with it ──────────────────────────────────────────

    @Test
    fun `the board re-reads and flashes the cards somebody else changed`() {
        val server = LiveServer()
        val main = MainScreenBackingViewModel(StorageRepository(server), CoroutineScope(Dispatchers.Unconfined))
        main.onOpenProjectsChanged(setOf(PROJECT))
        server.title = "Renamed by Linus"

        main.onRemoteBoardChange(PROJECT, setOf(ISSUE))

        val state = main.stateFlow.value
        assertEquals("Renamed by Linus", state.boards.getValue(PROJECT).issues.single().title)
        assertTrue(state.screen(PROJECT).isRemotelyChanged(ISSUE))
        assertEquals(1, server.boardReads)

        main.onRemoteBoardChange(99L, setOf(1L))
        assertEquals(1, server.boardReads, "A board nobody has open is not fetched.")
    }

    // ── What an open issue does with it ──────────────────────────────────────

    private fun openIssue(server: LiveServer): IssueBackingViewModel {
        val vm = IssueBackingViewModel(
            issueId = ISSUE,
            board = BoardState(project = ProjectSummary(PROJECT, "Test", "TST")),
            storage = StorageRepository(server),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onFinished = {},
        )
        vm.start()
        return vm
    }

    @Test
    fun `a field being edited is kept and noted, and the rest follow`() {
        val server = LiveServer()
        val vm = openIssue(server)
        vm.onEditTapped()
        vm.onTitleChanged("My new title")

        server.title = "Their title"
        server.description = "Their description"
        vm.onRemoteChange("Linus", deleted = false)

        val state = vm.stateFlow.value
        assertEquals("My new title", state.title, "An edit in progress was overwritten.")
        assertEquals("Their description", state.description, "An untouched field did not follow.")
        assertEquals("Changed by Linus", state.remoteChangeNote(IssueField.TITLE))
        assertNull(state.remoteChangeNote(IssueField.DESCRIPTION))
        assertTrue(state.isDirty, "Saving would no longer write the reader's title.")

        vm.onReloadField(IssueField.TITLE)
        assertEquals("Their title", vm.stateFlow.value.title)
        assertNull(vm.stateFlow.value.remoteChangeNote(IssueField.TITLE))
        assertFalse(vm.stateFlow.value.isDirty)
    }

    @Test
    fun `a window that is only reading simply follows`() {
        val server = LiveServer()
        val vm = openIssue(server)
        server.title = "Their title"
        vm.onRemoteChange("Linus", deleted = false)
        assertEquals("Their title", vm.stateFlow.value.title)
        assertTrue(vm.stateFlow.value.remoteChanges.isEmpty())
    }

    @Test
    fun `a deleted issue says who deleted it and stops offering edits`() {
        val vm = openIssue(LiveServer())
        vm.onRemoteChange("Ada", deleted = true)
        val state = vm.stateFlow.value
        assertEquals("Ada deleted this issue.", state.errorMessage)
        assertFalse(state.canEdit)
        assertFalse(state.canComment)
    }
}
