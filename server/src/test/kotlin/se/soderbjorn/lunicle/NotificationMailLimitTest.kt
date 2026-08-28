/**
 * Outbound notification mail is metered, and its subject is not attacker-composed
 * (LUS-23).
 *
 * Rate limiting existed in this codebase and was applied to exactly two endpoints,
 * with the limiter's own preamble naming the risk it guards: "a free mail cannon
 * pointed at arbitrary third parties from a verified domain". Notification mail had
 * none of it. Every save that introduces a mention, changes an assignee or touches
 * a watched issue sends — and the mention notifier's only throttle, mailing just
 * *new* mentions, is defeated by removing a mention and re-adding it on the next
 * save.
 *
 * Recipients are bounded to people already mentionable on that project, so this is
 * an inside attack rather than an open relay. It is still a mail bomb and a
 * phishing amplifier carrying the deployment's sender reputation.
 *
 * @see NotificationDispatcher.send
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.AuthProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationMailLimitTest {
    private val file: File = Files.createTempFile("lunicle-mail-limit", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database
    private val users = UserStore(database)

    /** Every message that would have gone out, as (address, subject). */
    private val sent = mutableListOf<Pair<String, String>>()

    private val transport = object : EmailTransport {
        override suspend fun send(to: String, subject: String, html: String, text: String?) {
            sent += to to subject
        }
    }

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
    }

    private fun dispatcher() = NotificationDispatcher(users, transport)

    private fun recipient(address: String) =
        EmailRecipient(userId = 1L, email = address, name = "Victim")

    /** One inbox cannot be filled by a loop, whoever is driving it. */
    @Test
    fun `mail to one recipient is capped`(): Unit = runBlocking {
        val dispatch = dispatcher()
        repeat(50) { dispatch.send(recipient("victim@example.com"), "Subject $it", "<p>body</p>") }

        assertEquals(
            30,
            sent.size,
            "Notification mail is still unmetered — a Contributor looping an edit can post " +
                "DKIM-signed mail from the organisation's own domain at HTTP speed.",
        )
    }

    /**
     * And an actor cannot spray many colleagues either.
     *
     * Per-recipient keying alone permits exactly that: one attacker, one message
     * each to everybody mentionable on a shared board.
     */
    @Test
    fun `mail from one actor is capped across recipients`(): Unit = runBlocking {
        val dispatch = dispatcher()
        repeat(50) { dispatch.send(recipient("colleague$it@example.com"), "Subject", "<p>body</p>", actorId = 7L) }

        assertEquals(30, sent.size, "One actor mailed fifty different colleagues inside one window.")
    }

    /** A send with nobody behind it is bounded by its recipient, and not by an actor. */
    @Test
    fun `an actorless send is still bounded by its recipient`(): Unit = runBlocking {
        val dispatch = dispatcher()
        repeat(50) { dispatch.send(recipient("victim@example.com"), "Subject", "<p>body</p>") }
        val toVictim = sent.size

        dispatch.send(recipient("somebody-else@example.com"), "Subject", "<p>body</p>")
        assertEquals(
            toVictim + 1,
            sent.size,
            "An unrelated recipient was locked out by somebody else's budget.",
        )
    }

    /**
     * The assignment subject carries no free text, through the real notifier.
     *
     * It used to carry up to 300 characters chosen by whoever last edited the issue,
     * after a short fixed prefix — delivered with the organisation's own
     * authentication alignment, which is a credible internal-phishing lure. The
     * reference alone reads fine and the title is in the body, escaped.
     */
    @Test
    fun `the assignment subject carries no writer-chosen text`(): Unit = runBlocking {
        val fixture = seedProjectWithAssignee()
        val lure = "URGENT: confirm your password at http://evil.example"

        fixture.notifications.issueAssigned(fixture.issue(lure), fixture.assigneeId, actorId = fixture.actorId)

        val (address, subject) = sent.single()
        assertEquals("assignee@acme.com", address)
        assertTrue(
            lure !in subject,
            "The subject still carries text the writer chose: \"$subject\".",
        )
        assertTrue(subject.startsWith("[PRJ-"), "The subject lost the reference that identifies the issue.")
    }

    /**
     * And it does not go to somebody who has lost the project (LUS-22).
     *
     * The assignment notifier resolved the assignee by id and never asked whether
     * they could still see the board. Assignment writes no subscription row, so this
     * is not a stale *subscription* — it is somebody holding an issue on a project
     * they were removed from, which is at least as easy to arrive at.
     */
    @Test
    fun `an assignee who lost the project is not mailed`(): Unit = runBlocking {
        val fixture = seedProjectWithAssignee()
        fixture.roles.setRole(fixture.assigneeId, fixture.projectId, null)

        fixture.notifications.issueAssigned(fixture.issue("Anything"), fixture.assigneeId, actorId = fixture.actorId)

        assertTrue(
            sent.isEmpty(),
            "A former member was mailed the reference and title of an issue on a board they " +
                "can no longer open.",
        )
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private class Fixture(
        val notifications: NotificationService,
        val roles: RoleStore,
        val projectId: Long,
        val assigneeId: Long,
        val actorId: Long,
        private val issues: IssueStore,
        private val issueId: Long,
    ) {
        /** The stored issue, with [title] written onto it — what the notifier reads. */
        suspend fun issue(title: String): IssueRecord {
            val stored = requireNotNull(issues.findById(issueId))
            return stored.copy(title = title)
        }
    }

    /**
     * A private project, an actor who owns it, and an assignee who is a Contributor
     * on it.
     *
     * The first account upserted takes the first-account-is-an-administrator slot, or
     * the assignee would read every project by instance role and withdrawing their
     * rung would decide nothing. See Users.sq's upsert.
     */
    private suspend fun seedProjectWithAssignee(): Fixture {
        users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-root", "Root", "root@acme.com"))
        val actor = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-actor", "Actor", "actor@acme.com"))
        val assignee = users.upsert(ProviderIdentity(AuthProvider.GOOGLE, "g-a", "Assignee", "assignee@acme.com"))

        val roles = RoleStore(database)
        val projects = ProjectStore(database)
        val instanceSettings = InstanceSettingsStore(database)
        val attachmentStore = AttachmentStore(database)
        val attachments = AttachmentRepository(attachmentStore, File(file.parentFile, "att-${file.name}"))
        val project = ProjectRepository(database, projects, attachments, attachmentStore).create("Project", "PRJ")
        roles.setRole(actor.id, project.id, ProjectRole.OWNER)
        roles.setRole(assignee.id, project.id, ProjectRole.CONTRIBUTOR)

        val issues = IssueStore(database)
        val (issueId, _) = IssueRepository(
            issues, CommentStore(database), StatusStore(database),
            PriorityStore(database), attachments, attachmentStore,
        ).createDraft(project.id, Author.Nobody)

        return Fixture(
            notifications = NotificationService(
                subscriptions = SubscriptionStore(database),
                projects = projects,
                users = users,
                roles = roles,
                instanceSettings = instanceSettings,
                audience = ProjectAudience(users, roles, instanceSettings),
                dispatch = dispatcher(),
                baseUrl = "https://issues.example.com",
            ),
            roles = roles,
            projectId = project.id,
            assigneeId = assignee.id,
            actorId = actor.id,
            issues = issues,
            issueId = issueId,
        )
    }
}
