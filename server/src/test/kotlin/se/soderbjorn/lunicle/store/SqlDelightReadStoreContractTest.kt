/**
 * The Read contract, run against the SQLite reference implementation
 * ([se.soderbjorn.lunicle.ReadStore]).
 *
 * The assertions live in [ReadStoreContract]; this file wires SQLite and — crucially
 * for the two derived reads — seeds **real** conversations, messages, forums and
 * posts through the real stores, so the joins `unreadMessageCounts` and
 * `hasUnreadPosts` run are genuinely exercised against live rows rather than mocked
 * out. Users, a project and its forums are created through the same machinery
 * production uses, so every foreign key is satisfied exactly as it is on the volume.
 *
 * The concrete gateways share their simple names with the store interfaces, so the
 * concrete conversation/message/forum/post/read stores are imported by their
 * fully-qualified names; `store` is the [ReadStore] interface.
 */
package se.soderbjorn.lunicle.store

import java.nio.file.Files
import kotlin.test.AfterTest
import se.soderbjorn.lunicle.AttachmentRepository
import se.soderbjorn.lunicle.AttachmentStore
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.ConversationStore
import se.soderbjorn.lunicle.ForumPostStore
import se.soderbjorn.lunicle.ForumStore
import se.soderbjorn.lunicle.MessageStore
import se.soderbjorn.lunicle.ProjectRepository
import se.soderbjorn.lunicle.ProjectStore
import se.soderbjorn.lunicle.ProviderIdentity
import se.soderbjorn.lunicle.UserStore
import se.soderbjorn.lunicle.clientserver.AuthProvider

class SqlDelightReadStoreContractTest : ReadStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val users = UserStore(db)
    private val projects = ProjectStore(db)
    private val attachmentStore = AttachmentStore(db)
    private val attachmentsDir = Files.createTempDirectory("lunicle-contract-att").toFile()
    private val attachments = AttachmentRepository(attachmentStore, attachmentsDir)
    private val projectRepository = ProjectRepository(db, projects, attachments, attachmentStore)
    private val conversations = ConversationStore(db)
    private val messages = MessageStore(db)
    private val forums = ForumStore(db)
    private val forumPosts = ForumPostStore(db)

    private var seq = 0

    override val store: ReadStore = se.soderbjorn.lunicle.ReadStore(db)

    override suspend fun newUser(): Long {
        val n = seq++
        return users.upsert(ProviderIdentity(AuthProvider.GITHUB, "sub-$n", "User $n", "user$n@example.com")).id
    }

    override suspend fun newProject(): Long =
        projectRepository.create("Project $seq", "RD${seq++}", isPublic = false).id

    override suspend fun newConversation(participantIds: Set<Long>): Long =
        conversations.insert(Author.Nobody, participantIds)

    override suspend fun postMessage(conversationId: Long, authorId: Long): Long {
        val id = messages.insertDraft(conversationId, Author.Account(authorId))
        messages.publish(id, "Message $id")
        return id
    }

    override suspend fun postDraftMessage(conversationId: Long, authorId: Long) {
        messages.insertDraft(conversationId, Author.Account(authorId))
    }

    override suspend fun newForum(projectId: Long): Long =
        forums.insert(projectId, "Forum ${seq++}", null).id

    override suspend fun postInForum(forumId: Long, authorId: Long, createdAt: Long): Long {
        val id = forumPosts.insertDraft(forumId, Author.Account(authorId), createdAt)
        forumPosts.publish(id, "Title", "Body")
        return id
    }

    override suspend fun postDraftInForum(forumId: Long, authorId: Long, createdAt: Long) {
        forumPosts.insertDraft(forumId, Author.Account(authorId), createdAt)
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
        attachmentsDir.deleteRecursively()
    }
}
