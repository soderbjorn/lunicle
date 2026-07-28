/**
 * The behaviour every [CommentStore] implementation must exhibit — the same
 * draft→publish shape as [IssueStoreContract] in miniature.
 *
 * The semantics pinned here: a comment is born a draft and is invisible on
 * [CommentStore.forIssue] until [CommentStore.publish] makes it visible;
 * [CommentStore.forIssue] lists an issue's published comments oldest-first and is
 * isolated per issue; [CommentStore.findById] round-trips one comment;
 * [CommentStore.update] rewrites the body alone while [CommentStore.edit] rewrites a
 * published comment whole (body, date, author, agent) without un-publishing it; and
 * [CommentStore.delete] removes the row.
 *
 * A backend seeding hook is needed because a comment hangs off an issue: [newIssue]
 * mints one however the backend under test makes them. The store validates no
 * foreign key — a comment stores its `issueId` as a plain field — so a synthetic id
 * is all a Firestore backend needs; the SQLite backend files a real issue so the
 * `issue_id` foreign key it does have is satisfied.
 */
package se.soderbjorn.lunicle.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunicle.Author

abstract class CommentStoreContract {
    protected abstract val store: CommentStore

    /** An issue for a comment to hang off, made the backend's own way. */
    protected abstract suspend fun newIssue(): Long

    @Test
    fun `a draft comment is invisible until published`() = runBlocking {
        val issue = newIssue()
        val id = store.insertDraft(issue, Author.Nobody, createdAt = 1_000)
        assertTrue(store.findById(id)!!.isDraft, "a fresh comment is a draft")
        assertEquals(emptyList(), store.forIssue(issue).map { it.id }, "a draft appears on nobody's issue")

        store.publish(id, "A reply")
        val published = store.findById(id)!!
        assertEquals(false, published.isDraft, "publishing clears the draft flag")
        assertEquals("A reply", published.body)
        assertEquals(listOf(id), store.forIssue(issue).map { it.id }, "publishing puts it on the issue read")
    }

    @Test
    fun `forIssue lists published comments oldest first`() = runBlocking {
        val issue = newIssue()
        val first = publishedComment(issue, createdAt = 1_000)
        val second = publishedComment(issue, createdAt = 2_000)
        val third = publishedComment(issue, createdAt = 3_000)
        assertEquals(listOf(first, second, third), store.forIssue(issue).map { it.id })
    }

    @Test
    fun `forIssue is isolated per issue`() = runBlocking {
        val issue = newIssue()
        val other = newIssue()
        val mine = publishedComment(issue, createdAt = 1_000)
        publishedComment(other, createdAt = 1_000)
        assertEquals(listOf(mine), store.forIssue(issue).map { it.id }, "one issue's comments never reach another's")
    }

    @Test
    fun `findById round-trips a comment`() = runBlocking {
        val issue = newIssue()
        val id = store.insertDraft(issue, Author.External("Imported"), createdAt = 1_000, agentName = "agent")
        store.publish(id, "Body")
        val read = store.findById(id)!!
        assertEquals(issue, read.issueId)
        assertEquals("Body", read.body)
        assertEquals(1_000, read.createdAt)
        assertEquals(Author.External("Imported"), read.author)
        assertEquals("agent", read.agentName)
    }

    @Test
    fun `update rewrites the body alone`() = runBlocking {
        val issue = newIssue()
        val id = publishedComment(issue, createdAt = 1_000)
        store.update(id, "Edited")
        val read = store.findById(id)!!
        assertEquals("Edited", read.body, "the body is rewritten")
        assertEquals(1_000, read.createdAt, "and nothing else — the date is untouched")
        assertEquals(false, read.isDraft, "a published comment stays published")
    }

    @Test
    fun `edit rewrites a published comment whole without un-publishing it`() = runBlocking {
        val issue = newIssue()
        val id = publishedComment(issue, createdAt = 1_000)
        store.edit(id, "Corrected", createdAt = 5_000, author = Author.External("Imported"), agentName = "agent")
        val read = store.findById(id)!!
        assertEquals("Corrected", read.body)
        assertEquals(5_000, read.createdAt)
        assertEquals(Author.External("Imported"), read.author)
        assertEquals("agent", read.agentName)
        assertEquals(false, read.isDraft, "edit leaves is_draft alone")
    }

    @Test
    fun `delete removes the comment`() = runBlocking {
        val issue = newIssue()
        val id = publishedComment(issue, createdAt = 1_000)
        store.delete(id)
        assertNull(store.findById(id))
        assertEquals(emptyList(), store.forIssue(issue).map { it.id })
    }

    /**
     * The issue-delete cascade, at this store's scale (LNL-177).
     *
     * Three things at once, and the last two are the ones that matter. Drafts go as
     * well as published comments — an unsent comment on a deleted issue is exactly
     * as unreachable, and SQLite's cascade makes no distinction either — and another
     * issue's comments are spared. Without that second issue a delete keyed on the
     * wrong field, or on nothing at all, empties the whole collection and still
     * passes everything above it.
     */
    @Test
    fun `deleteForIssue takes that issue's comments, drafts included, and spares another issue's`() = runBlocking {
        val issue = newIssue()
        val other = newIssue()
        val published = publishedComment(issue, createdAt = 1_000)
        val draft = store.insertDraft(issue, Author.Nobody, createdAt = 2_000)
        val spared = publishedComment(other, createdAt = 3_000)

        store.deleteForIssue(issue)

        assertNull(store.findById(published), "the published comment is gone")
        assertNull(store.findById(draft), "the draft went too")
        assertEquals(emptyList(), store.forIssue(issue).map { it.id })
        assertEquals(spared, store.findById(spared)?.id, "deleting one issue's comments took another's")
        assertEquals(listOf(spared), store.forIssue(other).map { it.id })
    }

    @Test
    fun `deleteForIssue on an issue with no comments is a no-op`() = runBlocking {
        val issue = newIssue()
        val other = newIssue()
        val spared = publishedComment(other, createdAt = 1_000)

        store.deleteForIssue(issue)

        assertEquals(listOf(spared), store.forIssue(other).map { it.id }, "an empty cascade deleted something")
    }

    @Test
    fun `withPossibleMentions and withAttachmentLinks find drafts too`() = runBlocking {
        val issue = newIssue()
        val mention = store.insertDraft(issue, Author.Nobody, createdAt = 1_000)
        store.publish(mention, "hey @robert look at this")
        val link = publishedComment(issue, createdAt = 2_000)
        store.update(link, "see /api/attachments/42/file.png")

        assertTrue(store.withPossibleMentions().any { it.first == mention }, "a body with an @ is a possible mention")
        assertTrue(store.withAttachmentLinks().any { it.first == link }, "a body with an attachment path is a link")
    }

    private suspend fun publishedComment(issueId: Long, createdAt: Long): Long {
        val id = store.insertDraft(issueId, Author.Nobody, createdAt = createdAt)
        store.publish(id, "Comment $id")
        return id
    }
}
