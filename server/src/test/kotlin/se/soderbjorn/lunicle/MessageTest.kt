/**
 * Private conversations: who may be written to, who may read a thread, who may
 * delete a message, and what a file attached to one is reachable by.
 *
 * ── The interesting failures this file exists to catch ──────────────────────
 *
 *  - **The recipient rule is enforced at the route, not only in the picker.**
 *    LNL-60 asks for exactly this and it is the criterion with the least
 *    backstop anywhere else: the picker is a list the browser was handed, and a
 *    browser can post an id it was never offered. A server that trusted the list
 *    would look perfect through the UI on every machine it was developed on.
 *  - **A stranger cannot read a conversation, and is refused with 404.** A 403
 *    would confirm one exists by that id — and conversation ids are consecutive
 *    integers, so the difference between the two answers is a way to count the
 *    private conversations on the instance and probe for them.
 *  - **Membership is fixed, in both directions.** There is no route that adds a
 *    participant, and this file asserts the absence by asserting the consequence:
 *    a second conversation between the same people is a *different* conversation,
 *    and neither can see the other. That is the property the whole "no new member
 *    sees prior history" question rests on, and it is the sort of thing a later
 *    convenience route would quietly undo.
 *  - **A message attachment is reached through a conversation, not a project.**
 *    `serveAttachment` walks four owners up to a project and asks
 *    `canReadProject`; the fifth has no project at all. The harmless version of
 *    getting that wrong is an unreachable file; the harmful one is a private
 *    conversation's screenshots served to anybody holding the URL, which is what
 *    this pins.
 *  - **A draft conversation is invisible.** A conversation is created together
 *    with the empty message row an attachment hangs off, so between "I chose
 *    recipients" and "I pressed Send" there is a real conversation with nothing in
 *    it. It must appear in nobody's list — including the recipients', who would
 *    otherwise see a message from somebody with no message in it.
 *  - **Deleting is the author's or the system administrator's, and nobody else's.**
 *    LNL-60's acceptance list said "a project admin can too", which a conversation
 *    has none of; see `AccessControl.canDeleteMessage` for what was done about
 *    that. The risk in any such correction is that it lands wider than intended,
 *    so this pins the negative case as well as the two positive ones.
 *
 * Every request goes through the `ApiRoutes` builders rather than hand-written
 * strings, so a route pattern that drifts from the path the client will call fails
 * here rather than in a browser. `ForumPostTest` says the same about its four.
 *
 * @see messageRoutes
 * @see AccessControl.canReadConversation
 */
package se.soderbjorn.lunicle

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.clientserver.ApiRoutes
import se.soderbjorn.lunicle.clientserver.AttachmentRef
import se.soderbjorn.lunicle.clientserver.AuthProvider
import se.soderbjorn.lunicle.clientserver.ConversationDetail
import se.soderbjorn.lunicle.clientserver.ConversationDraft
import se.soderbjorn.lunicle.clientserver.ConversationListState
import se.soderbjorn.lunicle.clientserver.ConversationStart
import se.soderbjorn.lunicle.clientserver.MessageEdit
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class MessageTest {
    private val file: File = Files.createTempFile("lunicle-messages", ".db").toFile().also { it.delete() }
    private val opened = openDatabase(DatabaseLocation(file, isPersistent = false, reason = "test"))
    private val database = opened.database

    private val users = UserStore(database)
    private val sessions = SessionStore(database)
    private val roles = RoleStore(database)
    private val projects = ProjectStore(database)
    private val labels = LabelStore(database)
    private val components = ComponentStore(database)
    private val statuses = StatusStore(database)
    private val priorities = PriorityStore(database)
    private val resolutions = ResolutionStore(database)
    private val sprints = SprintStore(database)
    private val versions = VersionStore(database)
    private val issues = IssueStore(database)
    private val comments = CommentStore(database)
    private val attachmentStore = AttachmentStore(database)
    private val attachmentsDirectory = File(file.parentFile, "attachments-${file.name}")
    private val attachments = AttachmentRepository(attachmentStore, attachmentsDirectory)
    private val projectRepository = ProjectRepository(database, projects, attachments, attachmentStore)
    private val issueRepository =
        IssueRepository(issues, comments, statuses, priorities, attachments, attachmentStore)
    private val sprintRepository = SprintRepository(database, sprints, projects, issues, statuses)
    private val vocabularies =
        VocabularyRepository(database, labels, components, statuses, priorities, resolutions, sprints, versions, issues)
    private val forumStore = ForumStore(database)
    private val forums = ForumRepository(forumStore, attachments, attachmentStore)
    private val forumPostStore = ForumPostStore(database)
    private val forumCommentStore = ForumCommentStore(database)
    private val forumPosts =
        ForumPostRepository(forumPostStore, forumCommentStore, attachments, attachmentStore)
    private val conversationStore = ConversationStore(database)
    private val messageStore = MessageStore(database)
    private val conversations =
        ConversationRepository(conversationStore, messageStore, attachments, attachmentStore)
    private val access = AccessControl(roles)

    /**
     * The notifier, recording rather than sending.
     *
     * A recorder rather than a `NoMessageNotifications`, because "each recipient
     * gets an e-mail" is an acceptance criterion and asserting it needs the call to
     * be visible. It records the *arguments* rather than composed messages: what
     * this file is about is who the server decided to tell, and what the mail says
     * is `NotificationEmailTest`'s, without a database.
     */
    private class RecordingNotifier : MessageNotifier {
        val sent = mutableListOf<Triple<Long, Set<Long>, Long?>>()
        override suspend fun messageSent(message: MessageRecord, participantIds: Set<Long>, actorId: Long?) {
            sent.add(Triple(message.id, participantIds, actorId))
        }
    }

    private val notifier = RecordingNotifier()

    @AfterTest
    fun tearDown() {
        opened.close()
        file.delete()
        File("${file.absolutePath}-wal").delete()
        File("${file.absolutePath}-shm").delete()
        attachmentsDirectory.deleteRecursively()
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    /**
     * The whole lifecycle a composer performs: start, send, read back, reply.
     *
     * One test rather than four, because the interesting property is that the steps
     * compose — a conversation that is created but never appears in the recipient's
     * list is a bug nothing else here would notice, and it is the specific bug the
     * `is_draft` filter in `forUser` could cause by being one clause too strict.
     */
    @Test
    fun `a user starts a conversation, sends, and gets a reply`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val draft: ConversationDraft = client.post(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Application.Json)
                setBody(ConversationStart(listOf(f.graceId)))
            }.body()

            // Nothing is visible until it is sent: the conversation exists only so
            // an upload has an owner. Asserted from the *recipient's* side, which is
            // where it would be embarrassing.
            val beforeSending: ConversationListState = client.get(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.graceCookie)
            }.body()
            assertEquals(
                emptyList(),
                beforeSending.conversations,
                "An unsent conversation appeared in the recipient's list.",
            )

            val sent: ConversationDetail =
                client.put(ApiRoutes.conversationMessage(draft.conversationId, draft.messageId)) {
                    cookie(SESSION_COOKIE, f.adaCookie)
                    contentType(ContentType.Application.Json)
                    setBody(MessageEdit(body = "  Hello Grace.  "))
                }.body()
            assertEquals(listOf("Hello Grace."), sent.messages.map { it.body }, "The body was not trimmed.")
            assertEquals(listOf("Ada"), sent.messages.map { it.authorName })
            assertEquals(listOf("Grace"), sent.participantNames, "The detail did not name who it is with.")
            assertTrue(sent.messages.single().isMine, "The sender's own message did not come back as theirs.")

            val listed: ConversationListState = client.get(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.graceCookie)
            }.body()
            assertEquals(listOf("Ada"), listed.conversations.single().participantNames)
            assertEquals(
                "Hello Grace.",
                listed.conversations.single().lastMessageBody,
                "The list's preview did not follow the message.",
            )

            val replyDraft: ConversationDraft =
                client.post(ApiRoutes.conversationMessages(draft.conversationId)) {
                    cookie(SESSION_COOKIE, f.graceCookie)
                }.body()
            val replied: ConversationDetail =
                client.put(ApiRoutes.conversationMessage(draft.conversationId, replyDraft.messageId)) {
                    cookie(SESSION_COOKIE, f.graceCookie)
                    contentType(ContentType.Application.Json)
                    setBody(MessageEdit(body = "Hello Ada."))
                }.body()
            // Oldest first: a thread is read from the top.
            assertEquals(listOf("Hello Grace.", "Hello Ada."), replied.messages.map { it.body })
        }
    }

    /**
     * A group conversation is three participants and no separate concept.
     *
     * Pinned because "group conversations are supported" is an acceptance criterion
     * that could plausibly be satisfied by a schema that stores one recipient and
     * a UI that hides the difference. The assertion that matters is the third
     * person's: they must be able to *read* it, not merely be named in it.
     */
    @Test
    fun `a group conversation reaches everybody in it`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val id = conversationBetween(client, f.adaCookie, listOf(f.graceId, f.charlesId), "Morning all.")

            listOf(f.graceCookie, f.charlesCookie).forEach { cookie ->
                val detail: ConversationDetail = client.get(ApiRoutes.conversation(id)) {
                    cookie(SESSION_COOKIE, cookie)
                }.body()
                assertEquals(listOf("Morning all."), detail.messages.map { it.body })
                assertTrue(detail.canReply, "A group member could not reply.")
            }

            // Each side is told who else is in the room, minus themselves.
            val grace: ConversationDetail = client.get(ApiRoutes.conversation(id)) {
                cookie(SESSION_COOKIE, f.graceCookie)
            }.body()
            assertEquals(listOf("Ada", "Charles"), grace.participantNames)
        }
    }

    /**
     * Membership is fixed at creation, asserted through its consequence.
     *
     * There is deliberately no route that adds a participant, so the thing to pin
     * is what happens when you write to the same people again: you get a *second*
     * conversation, and the two are separate transcripts. That is the property the
     * whole "does a new member see prior history?" question rests on — LNL-30 says
     * it does not arise — and it is exactly what a later "reuse the existing
     * conversation for these participants" convenience would undo.
     */
    @Test
    fun `writing to the same person again starts a second conversation`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val first = conversationBetween(client, f.adaCookie, listOf(f.graceId), "One.")
            val second = conversationBetween(client, f.adaCookie, listOf(f.graceId), "Two.")
            assertNotEquals(first, second, "A second message reused the first conversation.")

            val firstDetail: ConversationDetail = client.get(ApiRoutes.conversation(first)) {
                cookie(SESSION_COOKIE, f.graceCookie)
            }.body()
            assertEquals(listOf("One."), firstDetail.messages.map { it.body }, "The two threads ran together.")
        }
    }

    /** A blank body is a sentence, not a 500. */
    @Test
    fun `a blank message is refused with words`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val draft: ConversationDraft = client.post(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Application.Json)
                setBody(ConversationStart(listOf(f.graceId)))
            }.body()
            val status = client.put(ApiRoutes.conversationMessage(draft.conversationId, draft.messageId)) {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Application.Json)
                setBody(MessageEdit("   "))
            }.status
            assertEquals(HttpStatusCode.Conflict, status)
        }
    }

    /** ...and so is a conversation addressed to nobody. */
    @Test
    fun `a conversation with no recipients is refused with words`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val status = client.post(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Application.Json)
                setBody(ConversationStart(emptyList()))
            }.status
            assertEquals(HttpStatusCode.Conflict, status)
        }
    }

    /**
     * Naming only yourself is the same as naming nobody.
     *
     * The route drops the caller from the list before checking it, so this reaches
     * the "addressed to nobody" refusal rather than creating a conversation with
     * one participant. A private note to yourself is a different feature nobody
     * asked for, and the schema would happily have stored one.
     */
    @Test
    fun `a conversation addressed only to yourself is refused`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val status = client.post(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Application.Json)
                setBody(ConversationStart(listOf(f.adaId)))
            }.status
            assertEquals(HttpStatusCode.Conflict, status)
        }
    }

    // ── Who may be written to ────────────────────────────────────────────────

    /**
     * The picker offers everybody who can see a project the caller can see, and
     * nobody else.
     *
     * Ada and Grace are members of the private project; Charles is too. Mallory
     * holds nothing anywhere, and there is no public project in this fixture — so
     * she is invisible to them and they to her. That is LNL-30's rule stated as a
     * set, and it is the list the composer's autocomplete renders.
     */
    @Test
    fun `the recipient list is everybody who shares a visible project`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val ada: ConversationListState = client.get(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.adaCookie)
            }.body()
            assertEquals(
                setOf("Grace", "Charles", "Sys"),
                ada.recipients.map { it.name }.toSet(),
                "The recipient list was not everybody sharing a project — and never the caller.",
            )
            assertTrue(ada.canMessage)

            val mallory: ConversationListState = client.get(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.malloryCookie)
            }.body()
            // The system administrator can see every project, so she can reach him;
            // that is `ProjectAudience` agreeing with `canReadProject`, which lets an
            // administrator read everything. Nobody else shares anything with her.
            assertEquals(
                setOf("Sys"),
                mallory.recipients.map { it.name }.toSet(),
                "Somebody who shares no project saw people they cannot reach.",
            )
        }
    }

    /**
     * ...and the rule is enforced at the route, not only in the picker.
     *
     * The acceptance criterion with no backstop anywhere else. The browser is
     * handed a list and can post an id that was never in it, so this posts one by
     * hand: Mallory addressing Ada, whom she shares nothing with.
     *
     * 403 rather than 404 here, unlike everywhere else in this file, and the
     * difference is deliberate: the caller is not being told whether an account
     * with that id exists — the same answer comes back for a nonsense id — only
     * that this list is not one they may write to.
     */
    @Test
    fun `messaging somebody you share no project with is refused at the route`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val status = client.post(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.malloryCookie)
                contentType(ContentType.Application.Json)
                setBody(ConversationStart(listOf(f.adaId)))
            }.status
            assertEquals(HttpStatusCode.Forbidden, status, "A stranger was allowed to open a conversation.")

            // An id nobody holds answers the same way, so this route cannot be used
            // to ask whether a given account exists.
            val unknown = client.post(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Application.Json)
                setBody(ConversationStart(listOf(9_999L)))
            }.status
            assertEquals(HttpStatusCode.Forbidden, unknown)
        }
    }

    /**
     * One bad id in a group refuses the whole conversation.
     *
     * The partial-success reading would be worse than useless: a message the sender
     * believes went to three people and went to two, with nothing on screen saying
     * which. Membership is fixed at creation, so there is no repairing it
     * afterwards either.
     */
    @Test
    fun `one unreachable recipient refuses the whole group`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val status = client.post(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Application.Json)
                setBody(ConversationStart(listOf(f.graceId, f.malloryId)))
            }.status
            assertEquals(HttpStatusCode.Forbidden, status)
        }
    }

    /**
     * A public project puts everybody on the instance in reach of everybody else.
     *
     * Not an edge case: it is the state of the deployment today, and it is what
     * `ProjectAudience.forProjects` short-circuits on. Worth pinning because the
     * membership-based implementation of the same rule would answer "nobody" here
     * and would look correct on a fixture that had no public project — which is
     * every other test in this file.
     */
    @Test
    fun `one public project makes everybody reachable`(): Unit = runBlocking {
        val f = seed()
        projectRepository.create("Open", "OPN", isPublic = true)
        withRoutes { client ->
            val mallory: ConversationListState = client.get(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.malloryCookie)
            }.body()
            assertEquals(
                setOf("Ada", "Grace", "Charles", "Sys"),
                mallory.recipients.map { it.name }.toSet(),
                "A public project did not open the instance up.",
            )
        }
    }

    /** A signed-out visitor has no conversations and no way to start one. */
    @Test
    fun `a signed-out visitor gets an empty list rather than a 401`(): Unit = runBlocking {
        seed()
        withRoutes { client ->
            val response = client.get(ApiRoutes.CONVERSATIONS)
            assertEquals(HttpStatusCode.OK, response.status)
            val state: ConversationListState = response.body()
            assertEquals(emptyList(), state.conversations)
            assertFalse(state.canMessage, "A signed-out visitor was offered the composer.")
        }
    }

    // ── Reading, and being refused ───────────────────────────────────────────

    /**
     * Somebody outside a conversation can neither read it nor write in it, and
     * every refusal is 404 rather than 403.
     *
     * The claim with no backstop anywhere else, and the reason for the 404 is
     * sharper here than for a project: conversation ids are consecutive integers,
     * so a 403 would let anybody walk the instance counting private threads and
     * learning which ids are live.
     *
     * Note the write attempts as well as the read: a route that gated only its
     * `get` would leave the reply `post` as an open door into somebody else's
     * conversation.
     */
    @Test
    fun `an outsider can neither read nor write in a conversation`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val id = conversationBetween(client, f.adaCookie, listOf(f.graceId), "Private.")

            // Charles shares a project with both of them and is still not in this
            // conversation, which is the point: visibility of a project is what lets
            // you *start* a conversation, never what lets you read one.
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.conversation(id)) { cookie(SESSION_COOKIE, f.charlesCookie) }.status,
                "Somebody outside the conversation read it.",
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.post(ApiRoutes.conversationMessages(id)) {
                    cookie(SESSION_COOKIE, f.charlesCookie)
                }.status,
                "Somebody outside the conversation started a reply in it.",
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.conversation(id)).status,
                "A signed-out visitor read a private conversation.",
            )
            // It does not appear in their list either, which is the same fact from
            // the other end and the one a user would actually notice.
            val list: ConversationListState = client.get(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.charlesCookie)
            }.body()
            assertEquals(emptyList(), list.conversations)
        }
    }

    /**
     * A system administrator can read a conversation and cannot write in it.
     *
     * The asymmetry is the whole of `AccessControl`'s messages section and is
     * pinned here rather than left to the doc comment. Reading is `isSysAdmin ||`
     * like every other rule in that file — an honest cost, stated there — but
     * writing is membership alone, because an administrator putting words into a
     * private thread would be indistinguishable from a peer having written them in
     * a feature that records no history.
     */
    @Test
    fun `a system administrator reads a conversation but cannot write in it`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val id = conversationBetween(client, f.adaCookie, listOf(f.graceId), "Private.")

            val detail: ConversationDetail = client.get(ApiRoutes.conversation(id)) {
                cookie(SESSION_COOKIE, f.sysCookie)
            }.body()
            assertEquals(listOf("Private."), detail.messages.map { it.body })
            assertFalse(detail.canReply, "An administrator was offered a reply box in somebody else's thread.")
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post(ApiRoutes.conversationMessages(id)) { cookie(SESSION_COOKIE, f.sysCookie) }.status,
                "An administrator started a message in a conversation they are not in.",
            )
            // ...and it is not *theirs*: it stays out of their own list, which is
            // membership rather than readability.
            val list: ConversationListState = client.get(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.sysCookie)
            }.body()
            assertEquals(emptyList(), list.conversations, "A readable conversation appeared in an admin's inbox.")
        }
    }

    // ── Deleting ─────────────────────────────────────────────────────────────

    /**
     * The author may delete their own message; the system administrator may too;
     * the other participant may not.
     *
     * Three answers in one test because the interesting claim is the *shape* of the
     * set, not any one member of it. LNL-60 said "a project admin can too" and a
     * conversation has no project, so this is the corrected rule — and the risk in
     * any correction is that it lands wider than intended, which is why the
     * negative case is here and is the one that matters.
     *
     * Note who the third party is: Grace is *in* the conversation. A test whose
     * refused caller was an outsider would be asserting the visibility rule again
     * rather than the authorship one.
     */
    @Test
    fun `only the author and a system administrator may delete a message`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val id = conversationBetween(client, f.adaCookie, listOf(f.graceId), "Mine.")
            val detail: ConversationDetail = client.get(ApiRoutes.conversation(id)) {
                cookie(SESSION_COOKIE, f.adaCookie)
            }.body()
            val messageId = detail.messages.single().id

            // The recipient. In the room, and it is still not their sentence.
            val fromGrace: ConversationDetail = client.get(ApiRoutes.conversation(id)) {
                cookie(SESSION_COOKIE, f.graceCookie)
            }.body()
            assertFalse(fromGrace.messages.single().canDelete, "The recipient was offered a Delete button.")
            assertEquals(
                HttpStatusCode.Forbidden,
                client.delete(ApiRoutes.conversationMessage(id, messageId)) {
                    cookie(SESSION_COOKIE, f.graceCookie)
                }.status,
                "The recipient deleted somebody else's message.",
            )

            // The administrator, who is neither the author nor in the room.
            assertEquals(
                HttpStatusCode.OK,
                client.delete(ApiRoutes.conversationMessage(id, messageId)) {
                    cookie(SESSION_COOKIE, f.sysCookie)
                }.status,
                "A system administrator could not remove a message.",
            )

            // ...and the author, on a second message, through the ordinary path.
            val second = replyIn(client, f.adaCookie, id, "Also mine.")
            val afterDelete: ConversationDetail =
                client.delete(ApiRoutes.conversationMessage(id, second)) {
                    cookie(SESSION_COOKIE, f.adaCookie)
                }.body()
            assertEquals(emptyList(), afterDelete.messages.map { it.body }, "The author's delete did not take.")
        }
    }

    /**
     * Deleting the last message leaves the conversation standing.
     *
     * Deliberate, and the opposite of what tidiness suggests: membership is fixed
     * at creation, so a conversation that removed itself could not be re-created
     * between the same people — they would get a second thread and the one they had
     * would be gone. An emptied conversation is still writable by everybody in it.
     *
     * It does drop out of the *list*, which is the same `is_draft = 0` EXISTS that
     * hides an unsent one — an inbox row with no last message has nothing to render.
     */
    @Test
    fun `deleting every message leaves a conversation that can still be written in`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val id = conversationBetween(client, f.adaCookie, listOf(f.graceId), "Only one.")
            val detail: ConversationDetail = client.get(ApiRoutes.conversation(id)) {
                cookie(SESSION_COOKIE, f.adaCookie)
            }.body()
            client.delete(ApiRoutes.conversationMessage(id, detail.messages.single().id)) {
                cookie(SESSION_COOKIE, f.adaCookie)
            }

            val emptied: ConversationDetail = client.get(ApiRoutes.conversation(id)) {
                cookie(SESSION_COOKIE, f.graceCookie)
            }.body()
            assertEquals(emptyList(), emptied.messages)
            assertTrue(emptied.canReply, "An emptied conversation could not be written in.")

            val list: ConversationListState = client.get(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.graceCookie)
            }.body()
            assertEquals(emptyList(), list.conversations, "An emptied conversation stayed in the list.")

            // ...and writing in it brings it back, rather than starting a third.
            replyIn(client, f.graceCookie, id, "Still here.")
            val back: ConversationListState = client.get(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.graceCookie)
            }.body()
            assertEquals(listOf(id), back.conversations.map { it.id })
        }
    }

    /**
     * Discarding an unsent conversation is the composer's Cancel, and only that.
     *
     * Two refusals worth pinning, because the route is reachable by everybody in
     * the conversation and by anybody holding a stale id: the recipient cannot
     * discard the message being written to them, and nobody can discard a
     * conversation once something has been said in it.
     */
    @Test
    fun `only an unsent conversation can be discarded, and only by its author`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val draft: ConversationDraft = client.post(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Application.Json)
                setBody(ConversationStart(listOf(f.graceId)))
            }.body()

            assertEquals(
                HttpStatusCode.Conflict,
                client.delete(ApiRoutes.conversation(draft.conversationId)) {
                    cookie(SESSION_COOKIE, f.graceCookie)
                }.status,
                "The recipient discarded a conversation somebody was composing.",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.delete(ApiRoutes.conversation(draft.conversationId)) {
                    cookie(SESSION_COOKIE, f.adaCookie)
                }.status,
                "The author could not cancel their own unsent conversation.",
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.conversation(draft.conversationId)) {
                    cookie(SESSION_COOKIE, f.adaCookie)
                }.status,
                "A discarded conversation survived.",
            )

            val live = conversationBetween(client, f.adaCookie, listOf(f.graceId), "Said something.")
            assertEquals(
                HttpStatusCode.Conflict,
                client.delete(ApiRoutes.conversation(live)) { cookie(SESSION_COOKIE, f.adaCookie) }.status,
                "A conversation with something in it was discarded.",
            )
        }
    }

    // ── E-mail ───────────────────────────────────────────────────────────────

    /**
     * Every recipient is notified, and the sender is not.
     *
     * The acceptance criterion, asserted at the point the server decides *who* —
     * what the mail says is `NotificationEmailTest`'s, which needs no database. The
     * sender's exclusion is the half that would be noticed as noise rather than as
     * a bug, so it is pinned rather than assumed.
     */
    @Test
    fun `sending a message notifies everybody but the sender`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            conversationBetween(client, f.adaCookie, listOf(f.graceId, f.charlesId), "Morning all.")
            val (_, participants, actor) = notifier.sent.single()
            assertEquals(setOf(f.adaId, f.graceId, f.charlesId), participants)
            assertEquals(f.adaId, actor, "The notifier was not told who to leave out.")
        }
    }

    /** Starting a conversation without sending notifies nobody. */
    @Test
    fun `an unsent conversation notifies nobody`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            client.post(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Application.Json)
                setBody(ConversationStart(listOf(f.graceId)))
            }
            assertEquals(emptyList(), notifier.sent, "Creating the draft row sent an e-mail.")
        }
    }

    // ── Attachments ──────────────────────────────────────────────────────────

    /**
     * A file in a message is readable by the conversation, and by nobody else.
     *
     * The interesting one, and the reason `mayReadAttachment` exists: four of the
     * five owner columns reach a project and this one reaches a conversation, so a
     * download route that walked to a project and found none has to refuse rather
     * than wave it through. The harmful failure is silent — a private
     * conversation's screenshots served to anybody holding the URL — and the URL is
     * deliberately shareable, because it appears inside rendered markdown.
     *
     * Charles is the third party here rather than Mallory, on purpose: he shares a
     * project with both of them, so a check that had accidentally fallen back to
     * `canReadProject` would let him through and a stranger would not have caught
     * it.
     */
    @Test
    fun `a message attachment is readable by the conversation and nobody else`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val draft: ConversationDraft = client.post(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Application.Json)
                setBody(ConversationStart(listOf(f.graceId)))
            }.body()

            val stored: AttachmentRef =
                client.post("${ApiRoutes.messageAttachments(draft.messageId)}?filename=shot.png") {
                    cookie(SESSION_COOKIE, f.adaCookie)
                    contentType(ContentType.Image.PNG)
                    setBody(PNG_BYTES)
                }.body()
            client.put(ApiRoutes.conversationMessage(draft.conversationId, draft.messageId)) {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Application.Json)
                setBody(MessageEdit("![shot](${ApiRoutes.attachment(stored.id)})"))
            }

            listOf(f.adaCookie, f.graceCookie).forEach { cookie ->
                assertEquals(
                    HttpStatusCode.OK,
                    client.get(ApiRoutes.attachment(stored.id)) { cookie(SESSION_COOKIE, cookie) }.status,
                    "Somebody in the conversation could not read its attachment.",
                )
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.attachment(stored.id)) { cookie(SESSION_COOKIE, f.charlesCookie) }.status,
                "A message attachment was served to somebody outside the conversation.",
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(ApiRoutes.attachment(stored.id)).status,
                "A message attachment was served to a signed-out visitor.",
            )
        }
    }

    /**
     * Only the person writing a draft may attach to it — not the other participant.
     *
     * The upload route runs a *stricter* rule than reading does, and one that is
     * strict in a way no rule in `AccessControl` is: no administrator clause at
     * all. Putting bytes into somebody's unsent message is not moderation and is
     * not membership; it is writing as them.
     */
    @Test
    fun `only the draft's author may attach to it`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val draft: ConversationDraft = client.post(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Application.Json)
                setBody(ConversationStart(listOf(f.graceId)))
            }.body()

            listOf(f.graceCookie to HttpStatusCode.Forbidden, f.sysCookie to HttpStatusCode.Forbidden)
                .forEach { (cookie, expected) ->
                    assertEquals(
                        expected,
                        client.post("${ApiRoutes.messageAttachments(draft.messageId)}?filename=shot.png") {
                            cookie(SESSION_COOKIE, cookie)
                            contentType(ContentType.Image.PNG)
                            setBody(PNG_BYTES)
                        }.status,
                        "Somebody other than the author attached a file to a draft message.",
                    )
                }
            assertEquals(
                HttpStatusCode.NotFound,
                client.post("${ApiRoutes.messageAttachments(draft.messageId)}?filename=shot.png") {
                    cookie(SESSION_COOKIE, f.charlesCookie)
                    contentType(ContentType.Image.PNG)
                    setBody(PNG_BYTES)
                }.status,
                "An outsider learned that a draft message exists.",
            )
        }
    }

    /**
     * Deleting a message takes its file off the volume.
     *
     * `ON DELETE CASCADE` takes the row and SQLite cannot reach the filesystem, so
     * the keys have to be read *before* the delete or the volume keeps every image
     * from every deleted message for ever, with nothing able to name them. Invisible
     * in Kotlin, and silent when wrong — the disk merely never gets smaller.
     */
    @Test
    fun `deleting a message removes its file`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val draft: ConversationDraft = client.post(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Application.Json)
                setBody(ConversationStart(listOf(f.graceId)))
            }.body()
            client.post("${ApiRoutes.messageAttachments(draft.messageId)}?filename=shot.png") {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Image.PNG)
                setBody(PNG_BYTES)
            }
            client.put(ApiRoutes.conversationMessage(draft.conversationId, draft.messageId)) {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Application.Json)
                setBody(MessageEdit("With a picture."))
            }
            assertEquals(1, attachmentsDirectory.listFiles()?.size, "The upload did not land on the volume.")

            client.delete(ApiRoutes.conversationMessage(draft.conversationId, draft.messageId)) {
                cookie(SESSION_COOKIE, f.adaCookie)
            }
            assertEquals(0, attachmentsDirectory.listFiles()?.size ?: 0, "Deleting a message left its file behind.")
        }
    }

    /** ...and so does cancelling the composer that uploaded it. */
    @Test
    fun `discarding an unsent conversation removes its files`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val draft: ConversationDraft = client.post(ApiRoutes.CONVERSATIONS) {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Application.Json)
                setBody(ConversationStart(listOf(f.graceId)))
            }.body()
            client.post("${ApiRoutes.messageAttachments(draft.messageId)}?filename=shot.png") {
                cookie(SESSION_COOKIE, f.adaCookie)
                contentType(ContentType.Image.PNG)
                setBody(PNG_BYTES)
            }
            assertEquals(1, attachmentsDirectory.listFiles()?.size)

            client.delete(ApiRoutes.conversation(draft.conversationId)) { cookie(SESSION_COOKIE, f.adaCookie) }
            assertEquals(
                0,
                attachmentsDirectory.listFiles()?.size ?: 0,
                "Cancelling the composer left its uploads on the volume.",
            )
        }
    }

    // ── Mentions ─────────────────────────────────────────────────────────────

    /**
     * The `@` autocomplete inside a conversation offers the people in it, and
     * nobody else.
     *
     * Much narrower than the forum's, which is everyone who can see the project,
     * and deliberately so: mentioning somebody who cannot read the thread would be
     * writing a name that reaches nobody, in the one place where "who can see this"
     * is a closed set. Charles shares a project with both of them and is not
     * offered, which is the assertion that tells the two rules apart.
     */
    @Test
    fun `mentionable users in a conversation are its participants`(): Unit = runBlocking {
        val f = seed()
        withRoutes { client ->
            val id = conversationBetween(client, f.adaCookie, listOf(f.graceId), "Hello.")
            val detail: ConversationDetail = client.get(ApiRoutes.conversation(id)) {
                cookie(SESSION_COOKIE, f.adaCookie)
            }.body()
            assertEquals(setOf("Ada", "Grace"), detail.mentionableUsers.map { it.name }.toSet())
        }
    }

    /**
     * Renaming somebody rewrites the mentions of them in messages.
     *
     * Messages are the fifth place markdown is stored, and the one added last. A
     * renamer that covered the first four would leave private messages as the one
     * place an old name survived — mentions that still look like mentions and
     * quietly resolve to nobody, which is the exact failure `MentionRenamer` exists
     * to prevent.
     */
    @Test
    fun `renaming a user rewrites mentions in messages`(): Unit = runBlocking {
        val f = seed()
        val (conversationId, messageId) = conversations.startConversation(
            Author.Account(f.adaId),
            setOf(f.graceId),
        )
        conversations.publishMessage(
            conversations.findMessageInConversation(messageId, conversationId)!!,
            "Over to @Grace on this.",
        )

        users.setDisplayName(f.graceId, "Grace Hopper")
        MentionRenamer(users, issues, comments, forumPostStore, forumCommentStore, messageStore)
            .rename(f.graceId, "Grace", "Grace Hopper")

        assertEquals("Over to @{Grace Hopper} on this.", conversations.findMessage(messageId)?.body)
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    /**
     * Four ordinary accounts and an administrator, over one **private** project.
     *
     * Private on purpose, and it is the fixture decision that matters most in this
     * file: every project on the real deployment is public today, which makes
     * everybody reachable by everybody and would make half the assertions here
     * vacuous. The one test that wants the public case creates a public project of
     * its own — see `one public project makes everybody reachable`.
     *
     * Mallory holds nothing anywhere, so she is the person who shares no project
     * with the others. Charles is a member and is the *third party inside* the
     * project — the one who can start a conversation with either of them and still
     * cannot read theirs, which is the distinction between "may message" and "may
     * read" that this feature turns on.
     */
    private class Fixture(
        val sysCookie: String,
        val adaCookie: String,
        val graceCookie: String,
        val charlesCookie: String,
        val malloryCookie: String,
        val adaId: Long,
        val graceId: Long,
        val charlesId: Long,
        val malloryId: Long,
    )

    private suspend fun seed(): Fixture {
        roles.seed()
        val sys = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-sys", "Sys", "sys@example.com"))
        val ada = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-ada", "Ada", "ada@example.com"))
        val grace = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-gra", "Grace", "grace@example.com"))
        val charles = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-cha", "Charles", "cha@example.com"))
        val mallory = users.upsert(ProviderIdentity(AuthProvider.GITHUB, "gh-mal", "Mallory", "mal@example.com"))
        assertTrue(sys.isSysAdmin, "The fixture's administrator is not one.")
        assertFalse(ada.isSysAdmin, "The fixture's ordinary user is an administrator.")

        val project = projectRepository.create("Lunamux", "LMX", isPublic = false)
        listOf(ada, grace, charles).forEach { roles.grant(it.id, project.id, Role.VIEW_PROJECT) }
        // ...and deliberately nothing for Mallory anywhere.

        return Fixture(
            sysCookie = sessions.create(sys.id),
            adaCookie = sessions.create(ada.id),
            graceCookie = sessions.create(grace.id),
            charlesCookie = sessions.create(charles.id),
            malloryCookie = sessions.create(mallory.id),
            adaId = ada.id,
            graceId = grace.id,
            charlesId = charles.id,
            malloryId = mallory.id,
        )
    }

    /**
     * Start a conversation and send its first message, through the routes.
     *
     * Through the routes rather than the repository, unlike `ForumPostTest`'s
     * fixtures, and the difference is deliberate: the two-step create *is* what
     * most of these tests are about, so a helper that skipped it would set up a
     * state the real client cannot reach.
     */
    private suspend fun conversationBetween(
        client: HttpClient,
        cookie: String,
        recipientIds: List<Long>,
        body: String,
    ): Long {
        val draft: ConversationDraft = client.post(ApiRoutes.CONVERSATIONS) {
            cookie(SESSION_COOKIE, cookie)
            contentType(ContentType.Application.Json)
            setBody(ConversationStart(recipientIds))
        }.body()
        client.put(ApiRoutes.conversationMessage(draft.conversationId, draft.messageId)) {
            cookie(SESSION_COOKIE, cookie)
            contentType(ContentType.Application.Json)
            setBody(MessageEdit(body))
        }
        return draft.conversationId
    }

    /** Send one more message into an existing conversation. Answers with its id. */
    private suspend fun replyIn(
        client: HttpClient,
        cookie: String,
        conversationId: Long,
        body: String,
    ): Long {
        val draft: ConversationDraft = client.post(ApiRoutes.conversationMessages(conversationId)) {
            cookie(SESSION_COOKIE, cookie)
        }.body()
        client.put(ApiRoutes.conversationMessage(conversationId, draft.messageId)) {
            cookie(SESSION_COOKIE, cookie)
            contentType(ContentType.Application.Json)
            setBody(MessageEdit(body))
        }
        return draft.messageId
    }

    private fun withRoutes(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            routing { boardRoutes(dependencies()) }
        }
        block(createClient { install(ClientContentNegotiation) { json() } })
    }

    private fun dependencies() = BoardDependencies(
        access = access,
        projects = projects,
        projectRepository = projectRepository,
        roles = roles,
        vocabularies = vocabularies,
        forums = forums,
        forumPosts = forumPosts,
        audience = ProjectAudience(users, roles),
        conversations = conversations,
        labels = labels,
        components = components,
        statuses = statuses,
        priorities = priorities,
        resolutions = resolutions,
        versions = versions,
        sprints = sprintRepository,
        sprintRepository = sprintRepository,
        issues = issues,
        issueRepository = issueRepository,
        comments = comments,
        attachments = attachmentStore,
        attachmentRepository = attachments,
        attachmentTickets = AttachmentTicketStore(),
        sessions = sessions,
        users = users,
        impersonations = Impersonations(),
        subscriptions = SubscriptionStore(database),
        reads = ReadStore(database),
        messageNotifications = notifier,
    )
}

/**
 * The smallest thing a browser will call a PNG.
 *
 * Real bytes rather than a placeholder string, because the upload route reads the
 * declared type and the size and the body has to survive both.
 */
private val PNG_BYTES = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
)
