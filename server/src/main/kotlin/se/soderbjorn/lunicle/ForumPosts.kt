/**
 * Forum posts and the flat comments on them, and the rules for writing them.
 *
 * Three classes, split the way [Forums] splits and for the same reason:
 * [ForumPostStore] and [ForumCommentStore] are SQL in, data class out, no
 * decisions; [ForumPostRepository] owns the title rules, the draft dance and the
 * one thing neither store can do — deleting the *files* a post or comment owns
 * before the rows that name them cascade away. A route never mentions a
 * transaction, and a store never mentions a rule.
 *
 * Nothing here answers a permission question — [AccessControl] does that, and the
 * routes ask it before they reach this file. See AccessControl's preamble for why
 * that split is absolute, and [AccessControl.canDeleteForumContent] for the one rule
 * in it that LNL-30 decided differently from issue comments.
 *
 * ── The draft dance, and why posts are created in two steps ─────────────────
 *
 * A post is inserted empty and published later, exactly as an issue and an issue
 * comment are. It looks like ceremony and it is not: the composer supports inline
 * image upload, an attachment row needs an owner that exists, and a body being
 * typed has no row yet. Creating the row first is what lets the entire existing
 * attachment machinery — including the ticketed-upload path — be reused unchanged
 * rather than re-invented for a body that is not saved. `is_draft` covers the
 * closed tab: a draft is invisible to every reader, and the startup sweep takes
 * its files.
 *
 * @see forumPostRoutes
 * @see Forums
 */
package se.soderbjorn.lunicle

import kotlinx.coroutines.withContext
import se.soderbjorn.lunicle.clientserver.MAX_POST_TITLE_LENGTH
import se.soderbjorn.lunicle.db.LunicleDatabase

/**
 * A forum post as this server knows it.
 *
 * @property author who wrote it: an account, an imported name, or nobody. See
 *   [Author]. [Author.External] is written by LNL-78's `create_forum_post` and
 *   `update_forum_post`, which is the importer this doc used to say did not
 *   exist; the column pair was here before it, which is why nothing had to change
 *   to receive it.
 * @property agentName the agent that wrote it on the author's behalf, or null
 *   when a human did. Orthogonal to [author], not a fourth kind of it. Written by
 *   the same two tools.
 * @property isDraft the row exists so an upload has an owner; it is invisible
 *   until published. See this file's preamble.
 */
data class ForumPostRecord(
    val id: Long,
    val forumId: Long,
    val title: String,
    val body: String,
    val createdAt: Long,
    val author: Author,
    val agentName: String?,
    val isDraft: Boolean,
)

/**
 * A post in a forum's list, with the derived facts the list needs.
 *
 * Its own type rather than a [ForumPostRecord] plus a second query, because all
 * of it comes back from the same statement — see ForumPosts.sq's `forForum`. A
 * list route that fetched the posts and then counted comments per post would be
 * N+1 round-trips to answer what one select already knows.
 *
 * @property lastCommentAt when the newest published comment landed, or null when
 *   there are none. Null rather than falling back to the post's own date: "nobody
 *   has replied" and "the last reply was as old as the post" are different things
 *   to say, and the card says the first of them by leaving the column blank.
 * @property lastCommentAuthor who wrote it, as an [Author] rather than a name —
 *   resolving account ids to display names is [BoardDependencies.authorNames]'
 *   job, done once for the whole page, and a store that did it per row would be a
 *   lookup per post.
 */
data class ForumPostListing(
    val post: ForumPostRecord,
    val commentCount: Long,
    val lastCommentAt: Long? = null,
    val lastCommentAuthor: Author = Author.Nobody,
)

/** A comment on a forum post. Flat: there is no parent. See ForumComments.sq. */
data class ForumCommentRecord(
    val id: Long,
    val postId: Long,
    val body: String,
    val createdAt: Long,
    val author: Author,
    val agentName: String?,
    val isDraft: Boolean,
)

/**
 * Why a post or comment write was refused, in words a user should see.
 *
 * [ForumRefusal]'s twin, and separate from it only so the two routes files can
 * catch their own. Anything that is *not* one of these is a bug and is allowed to
 * propagate.
 */
class ForumPostRefusal(message: String) : RuntimeException(message)

/** Reads and writes `forum_posts`. No rules; see [ForumPostRepository]. */
class ForumPostStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.ForumPostStore {
    /**
     * Create the draft row an upload can hang off.
     *
     * @param createdAt when the post should claim to have been written, or null —
     *   the web composer, always — for now. Non-null on exactly one path:
     *   LNL-78's `create_forum_post`, backfilling an imported forum. LNL-30 said
     *   forums would get no MCP tools; LNL-78 is the ticket that asked for them,
     *   and the parameter was already here.
     */
    override suspend fun insertDraft(
        forumId: Long,
        author: Author,
        createdAt: Long?,
        agentName: String?,
    ): Long = withContext(DatabaseDispatcher) {
        database.forumPostsQueries
            .insert(forumId, createdAt ?: now(), author.accountId, author.externalName, agentName)
            .executeAsOne()
    }

    override suspend fun publish(id: Long, title: String, body: String): Unit = withContext(DatabaseDispatcher) {
        database.forumPostsQueries.publish(title, body, id)
    }

    /**
     * Rewrite the body alone, leaving `is_draft` where it was.
     *
     * The two bulk markdown passes, and nothing else: [MentionRenamer] and
     * [AttachmentLinkRepair]. Neither is a user editing anything, which is why
     * this is not spelled as a general `edit`.
     */
    override suspend fun updateBody(id: Long, body: String): Unit = withContext(DatabaseDispatcher) {
        database.forumPostsQueries.updateBody(body, id)
    }

    /**
     * Rewrite everything a post says about itself, `is_draft` excepted.
     *
     * The counterpart to [insertDraft]'s parameters, and it has exactly one
     * caller: LNL-78's `update_forum_post`. See ForumPosts.sq's `edit`.
     */
    override suspend fun edit(
        id: Long,
        title: String,
        body: String,
        createdAt: Long,
        author: Author,
        agentName: String?,
    ): Unit = withContext(DatabaseDispatcher) {
        database.forumPostsQueries.edit(
            title,
            body,
            createdAt,
            author.accountId,
            author.externalName,
            agentName,
            id,
        )
    }

    override suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.forumPostsQueries.delete(id)
    }

    override suspend fun findById(id: Long): ForumPostRecord? = withContext(DatabaseDispatcher) {
        database.forumPostsQueries.findById(id).executeAsOneOrNull()?.toRecord()
    }

    /**
     * One post, proving it is this forum's.
     *
     * Everything reachable by a URL uses this rather than [findById], so a
     * mismatched pair is a 404 rather than a cross-forum write. See ForumPosts.sq.
     */
    override suspend fun findByIdInForum(id: Long, forumId: Long): ForumPostRecord? =
        withContext(DatabaseDispatcher) {
            database.forumPostsQueries.findByIdInForum(id, forumId).executeAsOneOrNull()?.toRecord()
        }

    /** A forum's published posts, newest first, each with its comment count. */
    override suspend fun forForum(forumId: Long): List<ForumPostListing> = withContext(DatabaseDispatcher) {
        database.forumPostsQueries.forForum(forumId).executeAsList().map { row ->
            ForumPostListing(
                post = ForumPostRecord(
                    id = row.id,
                    forumId = row.forum_id,
                    title = row.title,
                    body = row.body,
                    createdAt = row.created_at,
                    author = authorOf(row.created_by, row.created_by_external),
                    agentName = row.agent_name,
                    isDraft = row.is_draft != 0L,
                ),
                commentCount = row.comment_count,
                lastCommentAt = row.last_comment_at,
                lastCommentAuthor = authorOf(row.last_comment_by, row.last_comment_by_external),
            )
        }
    }

    /** Every post that might mention somebody. See ForumPosts.sq. */
    override suspend fun withPossibleMentions(): List<Pair<Long, String>> = withContext(DatabaseDispatcher) {
        database.forumPostsQueries.withPossibleMentions().executeAsList().map { it.id to it.body }
    }

    /** Every post whose body might link to an attachment. See [AttachmentLinkRepair]. */
    override suspend fun withAttachmentLinks(): List<Pair<Long, String>> = withContext(DatabaseDispatcher) {
        database.forumPostsQueries.withAttachmentLinks().executeAsList().map { it.id to it.body }
    }
}

/** Reads and writes `forum_comments`. No rules; see [ForumPostRepository]. */
class ForumCommentStore(
    private val database: LunicleDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : se.soderbjorn.lunicle.store.ForumCommentStore {
    /** See [ForumPostStore.insertDraft]. */
    override suspend fun insertDraft(
        postId: Long,
        author: Author,
        createdAt: Long?,
        agentName: String?,
    ): Long = withContext(DatabaseDispatcher) {
        database.forumCommentsQueries
            .insert(postId, createdAt ?: now(), author.accountId, author.externalName, agentName)
            .executeAsOne()
    }

    override suspend fun publish(id: Long, body: String): Unit = withContext(DatabaseDispatcher) {
        database.forumCommentsQueries.publish(body, id)
    }

    /** See [ForumPostStore.updateBody]. */
    override suspend fun updateBody(id: Long, body: String): Unit = withContext(DatabaseDispatcher) {
        database.forumCommentsQueries.updateBody(body, id)
    }

    /** See [ForumPostStore.edit]. */
    override suspend fun edit(
        id: Long,
        body: String,
        createdAt: Long,
        author: Author,
        agentName: String?,
    ): Unit = withContext(DatabaseDispatcher) {
        database.forumCommentsQueries.edit(
            body,
            createdAt,
            author.accountId,
            author.externalName,
            agentName,
            id,
        )
    }

    override suspend fun delete(id: Long): Unit = withContext(DatabaseDispatcher) {
        database.forumCommentsQueries.delete(id)
    }

    override suspend fun findById(id: Long): ForumCommentRecord? = withContext(DatabaseDispatcher) {
        database.forumCommentsQueries.findById(id).executeAsOneOrNull()?.toRecord()
    }

    /** One comment, proving it is this post's. See [ForumPostStore.findByIdInForum]. */
    override suspend fun findByIdInPost(id: Long, postId: Long): ForumCommentRecord? =
        withContext(DatabaseDispatcher) {
            database.forumCommentsQueries.findByIdInPost(id, postId).executeAsOneOrNull()?.toRecord()
        }

    /** A post's published comments, oldest first. Flat, in order. */
    override suspend fun forPost(postId: Long): List<ForumCommentRecord> = withContext(DatabaseDispatcher) {
        database.forumCommentsQueries.forPost(postId).executeAsList().map { it.toRecord() }
    }

    /** Every comment that might mention somebody. See ForumComments.sq. */
    override suspend fun withPossibleMentions(): List<Pair<Long, String>> = withContext(DatabaseDispatcher) {
        database.forumCommentsQueries.withPossibleMentions().executeAsList().map { it.id to it.body }
    }

    /** Every comment whose body might link to an attachment. See [AttachmentLinkRepair]. */
    override suspend fun withAttachmentLinks(): List<Pair<Long, String>> = withContext(DatabaseDispatcher) {
        database.forumCommentsQueries.withAttachmentLinks().executeAsList().map { it.id to it.body }
    }
}

/**
 * The rules: what a post's title and body may be, and what deleting one takes
 * with it.
 *
 * Also the only forum-content collaborator [BoardDependencies] carries, for the
 * reason [ForumRepository] gives at greater length: the reads below are
 * pass-throughs so that a route holds one thing rather than three, and cannot
 * eventually read through a store on a path that was meant to go through a rule.
 *
 * @param posts the posts table.
 * @param comments the comments table.
 * @param attachments the volume, for unlinking files whose rows are about to
 *   cascade away.
 * @param attachmentStore asked one question — which files are about to be orphaned
 *   — and asked *before* the delete, because afterwards nothing can name them.
 *   See [IssueRepository.delete], which is the same pairing for the same reason.
 */
class ForumPostRepository(
    private val posts: se.soderbjorn.lunicle.store.ForumPostStore,
    private val comments: se.soderbjorn.lunicle.store.ForumCommentStore,
    private val attachments: AttachmentRepository,
    private val attachmentStore: se.soderbjorn.lunicle.store.AttachmentStore,
) {
    // ── Reads ────────────────────────────────────────────────────────────────

    suspend fun listing(forumId: Long): List<ForumPostListing> = posts.forForum(forumId)

    suspend fun findPostInForum(postId: Long, forumId: Long): ForumPostRecord? =
        posts.findByIdInForum(postId, forumId)

    suspend fun findPost(postId: Long): ForumPostRecord? = posts.findById(postId)

    suspend fun commentsOn(postId: Long): List<ForumCommentRecord> = comments.forPost(postId)

    suspend fun findComment(commentId: Long): ForumCommentRecord? = comments.findById(commentId)

    suspend fun findCommentInPost(commentId: Long, postId: Long): ForumCommentRecord? =
        comments.findByIdInPost(commentId, postId)

    // ── Writes ───────────────────────────────────────────────────────────────

    /**
     * Create the hidden draft row an inline image can hang off.
     *
     * @param createdAt when the post should claim to have been written, or null
     *   for now. Only ever non-null on LNL-78's import path, where a system
     *   administrator is backfilling another forum's history; the web composer
     *   passes nothing and gets the clock.
     * @param agentName the agent writing on the author's behalf, or null.
     */
    suspend fun createPostDraft(
        forumId: Long,
        author: Author,
        createdAt: Long? = null,
        agentName: String? = null,
    ): Long = posts.insertDraft(forumId, author, createdAt, agentName)

    /** ...and the same for a comment. */
    suspend fun createCommentDraft(
        postId: Long,
        author: Author,
        createdAt: Long? = null,
        agentName: String? = null,
    ): Long = comments.insertDraft(postId, author, createdAt, agentName)

    /**
     * Publish a draft post, or re-save a published one.
     *
     * One method for both, because they are the same statement and the difference
     * — whether `is_draft` was already 0 — is not a difference the caller has to
     * make. Republishing a published post is a no-op on the flag.
     *
     * @throws ForumPostRefusal if the title is blank or too long, or the body is
     *   blank. All three are things the person typing can fix.
     */
    suspend fun publishPost(post: ForumPostRecord, title: String, body: String): ForumPostRecord {
        val cleanTitle = validTitle(title)
        val cleanBody = validBody(body)
        posts.publish(post.id, cleanTitle, cleanBody)
        return post.copy(title = cleanTitle, body = cleanBody, isDraft = false)
    }

    /** @throws ForumPostRefusal if the body is blank. */
    suspend fun publishComment(comment: ForumCommentRecord, body: String): ForumCommentRecord {
        val cleanBody = validBody(body)
        comments.publish(comment.id, cleanBody)
        return comment.copy(body = cleanBody, isDraft = false)
    }

    /**
     * Rewrite a published post in place: its words, and who is recorded as
     * having written them, when.
     *
     * ── Why this exists at all, when the web app has no edit button ──────────
     *
     * LNL-78 gave the forum surface over MCP a stated purpose of importing and
     * exporting, and an import that cannot be corrected is an import that has to be
     * deleted and redone from the top. `update_comment` on the issue side carries the
     * same argument at greater length; see [CommentStore.edit] and McpTools' preamble.
     *
     * Nothing reaches this today: discussions are retired (LNL-190) and
     * `AccessControl.canUseForumTools` answers false for every caller, whoever they
     * are. The argument above is why the function is still here to be re-enabled.
     *
     * The title and body rules are [publishPost]'s, unchanged — a correction
     * cannot leave a post with no title, and a caller who wants an empty one
     * wants a deletion.
     *
     * @throws ForumPostRefusal if the title is blank or too long, or the body is
     *   blank.
     */
    suspend fun editPost(
        post: ForumPostRecord,
        title: String,
        body: String,
        createdAt: Long,
        author: Author,
        agentName: String?,
    ): ForumPostRecord {
        val cleanTitle = validTitle(title)
        val cleanBody = validBody(body)
        posts.edit(post.id, cleanTitle, cleanBody, createdAt, author, agentName)
        return post.copy(
            title = cleanTitle,
            body = cleanBody,
            createdAt = createdAt,
            author = author,
            agentName = agentName,
        )
    }

    /** As [editPost], for one comment. @throws ForumPostRefusal if the body is blank. */
    suspend fun editComment(
        comment: ForumCommentRecord,
        body: String,
        createdAt: Long,
        author: Author,
        agentName: String?,
    ): ForumCommentRecord {
        val cleanBody = validBody(body)
        comments.edit(comment.id, cleanBody, createdAt, author, agentName)
        return comment.copy(
            body = cleanBody,
            createdAt = createdAt,
            author = author,
            agentName = agentName,
        )
    }

    /**
     * Delete a post, its comments, and every file either of them owned.
     *
     * The keys are read *first*: the rows go by `ON DELETE CASCADE`, and SQLite
     * has no way to reach the filesystem, so after the delete nothing on this
     * instance can name those files ever again. Getting the order backwards leaves
     * the volume holding every image in every deleted post, growing for ever, with
     * nothing able to identify them. [IssueRepository.delete] says the same.
     */
    suspend fun deletePost(post: ForumPostRecord) {
        val doomed = attachmentStore.keysForForumPost(post.id)
        posts.delete(post.id)
        doomed.forEach { attachments.deleteBlob(it) }
    }

    /** As [deletePost], for one comment. */
    suspend fun deleteComment(comment: ForumCommentRecord) {
        val doomed = attachmentStore.keysForForumComment(comment.id)
        comments.delete(comment.id)
        doomed.forEach { attachments.deleteBlob(it) }
    }

    private fun validTitle(title: String): String {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) throw ForumPostRefusal("A post needs a title.")
        if (trimmed.length > MAX_POST_TITLE_LENGTH) {
            throw ForumPostRefusal("A post title may be at most $MAX_POST_TITLE_LENGTH characters.")
        }
        return trimmed
    }

    /**
     * The body, refused when there is nothing in it.
     *
     * Trimmed but not truncated, unlike a forum description: a post body is the
     * point of the post, and silently cutting somebody's prose at a limit is worse
     * than storing a long one. The size ceiling that matters is on attachments,
     * where the bytes actually are.
     */
    private fun validBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) throw ForumPostRefusal("There is nothing in that yet.")
        return trimmed
    }
}

private fun se.soderbjorn.lunicle.db.Forum_posts.toRecord(): ForumPostRecord = ForumPostRecord(
    id = id,
    forumId = forum_id,
    title = title,
    body = body,
    createdAt = created_at,
    author = authorOf(created_by, created_by_external),
    agentName = agent_name,
    isDraft = is_draft != 0L,
)

private fun se.soderbjorn.lunicle.db.Forum_comments.toRecord(): ForumCommentRecord = ForumCommentRecord(
    id = id,
    postId = post_id,
    body = body,
    createdAt = created_at,
    author = authorOf(created_by, created_by_external),
    agentName = agent_name,
    isDraft = is_draft != 0L,
)
