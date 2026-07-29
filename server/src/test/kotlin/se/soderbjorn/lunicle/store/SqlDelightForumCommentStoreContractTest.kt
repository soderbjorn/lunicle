/**
 * The Forum-comment contract, run against the SQLite gateway reference
 * implementation ([se.soderbjorn.lunicle.ForumCommentStore]).
 *
 * The assertions live in [ForumCommentStoreContract]; this file wires SQLite — a
 * temp database, the gateway stores over it, and a project (through the real
 * [ProjectRepository]) with a forum on it and a published post in that forum
 * (through the gateway [ForumStore]/[ForumPostStore]) so each comment's `post_id`
 * foreign key is satisfied exactly as it is on the volume.
 */
package se.soderbjorn.lunicle.store

import java.nio.file.Files
import kotlin.test.AfterTest
import se.soderbjorn.lunicle.AttachmentRepository
import se.soderbjorn.lunicle.AttachmentStore
import se.soderbjorn.lunicle.Author
import se.soderbjorn.lunicle.ProjectRepository
import se.soderbjorn.lunicle.ProjectStore

class SqlDelightForumCommentStoreContractTest : ForumCommentStoreContract() {
    private val fixture = SqlDelightContractFixture()
    private val db get() = fixture.database

    private val projects = ProjectStore(db)
    private val forums = se.soderbjorn.lunicle.ForumStore(db)
    private val posts = se.soderbjorn.lunicle.ForumPostStore(db)
    private val attachmentStore = AttachmentStore(db)
    private val attachmentsDir = Files.createTempDirectory("lunicle-contract-att").toFile()
    private val attachments = AttachmentRepository(attachmentStore, attachmentsDir)
    private val projectRepository = ProjectRepository(db, projects, attachments, attachmentStore)

    private var seq = 0

    override val store: ForumCommentStore = se.soderbjorn.lunicle.ForumCommentStore(db)

    override suspend fun newPost(): Long {
        val n = seq++
        val project = projectRepository.create("Project ${n}", "FC${n}").id
        val forum = forums.insert(project, "Forum ${n}", null).id
        val post = posts.insertDraft(forum, Author.Nobody, createdAt = 1_000)
        posts.publish(post, "Post ${n}", "Body ${n}")
        return post
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
        attachmentsDir.deleteRecursively()
    }
}
