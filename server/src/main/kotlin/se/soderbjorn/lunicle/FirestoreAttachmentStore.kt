/**
 * The Firestore implementation of [se.soderbjorn.lunicle.store.AttachmentStore] —
 * the *metadata* half of the attachment domain (owners, storage keys, mime types).
 * The bytes live behind [AttachmentRepository]'s blob-store seam; this class knows
 * nothing about them.
 *
 * ── The modelling problem this class solves ─────────────────────────────────
 *
 * The SQLite store answers its `keysFor…` reads with joins: `keysForIssue` is
 * "files on the issue OR on a comment of the issue", `keysForProject` walks
 * issues → comments and forums → posts → comments, and so on down every cascade
 * an attachment can hang under. Those joins are exactly what a document store
 * cannot express — Firestore has no join, and an attachment document carries only
 * its *direct* owner id (the `commentId`, never the comment's issue). A
 * query-time walk over sibling collections would also fail the contract, which
 * seeds synthetic owner ids with no parent documents behind them.
 *
 * So the ancestry is **denormalised onto the document at insert time**. Every
 * attachment carries, besides its one owner id, the ids of every parent the
 * cascade reads name: `scopeProjectId`, `scopeIssueId`, `scopeForumId`,
 * `scopePostId`, `scopeConversationId`. That collapses every `keysFor…` read to a
 * single **single-field equality** query — `keysForIssue(id)` is
 * `scopeIssueId == id`, `keysForProject(id)` is `scopeProjectId == id` — which
 * Firestore serves from its automatic single-field indexes. **No composite index
 * is required by this store** (the note LNL-122 collects): the two full-collection
 * reads, [allStorageKeys] and [allMimeTypes], are unindexed scans, and everything
 * else is one equality.
 *
 * Where does the ancestry come from? The insert interface hands only the direct
 * owner id (`insertForComment(commentId, …)` never sees the issue), so an
 * [AttachmentScopeResolver] is injected to turn an owner id into its full
 * [AttachmentScope]. In production that resolver reads the sibling Firestore
 * stores; the contract test supplies one backed by the synthetic ids it seeded.
 *
 * ── Document model ──────────────────────────────────────────────────────────
 *
 * One document per attachment in `attachments/{id}`, `{id}` the global `Long` id
 * allocated from `_counters/attachments` (see [FirestoreCounters]). Exactly one of
 * the five owner fields is non-null — the document-model echo of the `CHECK` in
 * Attachments.sq that makes an orphan and a doubly-owned row both unrepresentable.
 * Here that invariant is upheld by construction: each insert sets one owner field
 * and leaves the other four null, and nothing else writes an owner.
 *
 * @see FirestoreProvider
 * @see FirestoreCounters
 * @see AttachmentScopeResolver
 * @see se.soderbjorn.lunicle.store.AttachmentStoreContract
 */
package se.soderbjorn.lunicle

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import se.soderbjorn.lunicle.store.AttachmentStore

/**
 * The denormalised ancestry of an attachment — the parent ids the cascade
 * `keysFor…` reads need, but a single owner id does not carry.
 *
 * A resolver returns the *complete* scope for an owner, including that owner's own
 * place in the hierarchy: [AttachmentScopeResolver.forIssue] returns `issueId` set
 * to the issue itself, [AttachmentScopeResolver.forForumPost] returns `postId` set
 * to the post itself, and so on. The store writes the scope verbatim, so a scope
 * that omits a field it should carry silently drops that attachment out of the
 * corresponding cascade read — which is why the resolver, not the store, owns the
 * whole answer.
 *
 * A `null` field means "this attachment does not hang under one of those": a
 * message-owned attachment reaches no project ([projectId] null) and no issue, an
 * issue-owned one reaches no forum.
 */
data class AttachmentScope(
    val projectId: Long? = null,
    val issueId: Long? = null,
    val forumId: Long? = null,
    val postId: Long? = null,
    val conversationId: Long? = null,
)

/**
 * Turns an attachment's direct owner id into its full [AttachmentScope].
 *
 * The seam that supplies the denormalised ancestry [FirestoreAttachmentStore]
 * writes onto each document. The insert interface only ever names the direct owner
 * (`insertForComment` sees the comment, not its issue or project), so this is
 * where the walk up to the project — and to every intermediate cascade parent —
 * lives. In production it reads the sibling Firestore stores; in the contract test
 * it answers from the synthetic ids the fixture seeded.
 */
interface AttachmentScopeResolver {
    suspend fun forIssue(issueId: Long): AttachmentScope
    suspend fun forComment(commentId: Long): AttachmentScope
    suspend fun forForumPost(forumPostId: Long): AttachmentScope
    suspend fun forForumComment(forumCommentId: Long): AttachmentScope
    suspend fun forMessage(messageId: Long): AttachmentScope
}

class FirestoreAttachmentStore(
    private val firestore: Firestore,
    private val scopes: AttachmentScopeResolver,
    private val now: () -> Long = System::currentTimeMillis,
) : AttachmentStore {
    private val counters = FirestoreCounters(firestore)

    private fun collection() = firestore.collection(COLLECTION)
    private fun doc(id: Long) = collection().document(id.toString())

    override suspend fun insertForIssue(
        issueId: Long, filename: String, mimeType: String, byteSize: Long,
        storageKey: String, publicId: String, author: Author, createdAt: Long?,
    ): Long = insert(ISSUE_ID, issueId, scopes.forIssue(issueId), filename, mimeType, byteSize, storageKey, publicId, author, createdAt)

    override suspend fun insertForComment(
        commentId: Long, filename: String, mimeType: String, byteSize: Long,
        storageKey: String, publicId: String, author: Author, createdAt: Long?,
    ): Long = insert(COMMENT_ID, commentId, scopes.forComment(commentId), filename, mimeType, byteSize, storageKey, publicId, author, createdAt)

    override suspend fun insertForForumPost(
        forumPostId: Long, filename: String, mimeType: String, byteSize: Long,
        storageKey: String, publicId: String, author: Author, createdAt: Long?,
    ): Long = insert(FORUM_POST_ID, forumPostId, scopes.forForumPost(forumPostId), filename, mimeType, byteSize, storageKey, publicId, author, createdAt)

    override suspend fun insertForForumComment(
        forumCommentId: Long, filename: String, mimeType: String, byteSize: Long,
        storageKey: String, publicId: String, author: Author, createdAt: Long?,
    ): Long = insert(FORUM_COMMENT_ID, forumCommentId, scopes.forForumComment(forumCommentId), filename, mimeType, byteSize, storageKey, publicId, author, createdAt)

    override suspend fun insertForMessage(
        messageId: Long, filename: String, mimeType: String, byteSize: Long,
        storageKey: String, publicId: String, author: Author, createdAt: Long?,
    ): Long = insert(MESSAGE_ID, messageId, scopes.forMessage(messageId), filename, mimeType, byteSize, storageKey, publicId, author, createdAt)

    /**
     * Allocate the id and write the document in one transaction.
     *
     * The [AttachmentScope] is resolved by the caller *before* the transaction —
     * it is a read against sibling data, and folding it in would only widen the
     * transaction's read set for no atomicity this needs. As in the SQLite store,
     * `createdAt` is the caller's value or now: only an admin backfilling an
     * import passes one, so an imported screenshot claims its issue's moment
     * rather than the importer's.
     */
    private suspend fun insert(
        ownerField: String, ownerId: Long, scope: AttachmentScope,
        filename: String, mimeType: String, byteSize: Long,
        storageKey: String, publicId: String, author: Author, createdAt: Long?,
    ): Long {
        val timestamp = createdAt ?: now()
        return firestore.runTransaction { txn ->
            val id = counters.next(txn, ID_COUNTER).getValue(ID_COUNTER)
            txn.set(doc(id), document(id, ownerField, ownerId, scope, filename, mimeType, byteSize, storageKey, publicId, author, timestamp))
            id
        }.await()
    }

    /** The one owner field set, the other four left null — the "exactly one owner" invariant, by construction. */
    private fun document(
        id: Long, ownerField: String, ownerId: Long, scope: AttachmentScope,
        filename: String, mimeType: String, byteSize: Long,
        storageKey: String, publicId: String, author: Author, createdAt: Long,
    ): Map<String, Any?> = mapOf(
        ID to id,
        ISSUE_ID to null,
        COMMENT_ID to null,
        FORUM_POST_ID to null,
        FORUM_COMMENT_ID to null,
        MESSAGE_ID to null,
        ownerField to ownerId,
        FILENAME to filename,
        MIME_TYPE to mimeType,
        BYTE_SIZE to byteSize,
        STORAGE_KEY to storageKey,
        PUBLIC_ID to publicId,
        CREATED_AT to createdAt,
        CREATED_BY to author.accountId,
        CREATED_BY_EXTERNAL to author.externalName,
        SCOPE_PROJECT_ID to scope.projectId,
        SCOPE_ISSUE_ID to scope.issueId,
        SCOPE_FORUM_ID to scope.forumId,
        SCOPE_POST_ID to scope.postId,
        SCOPE_CONVERSATION_ID to scope.conversationId,
    )

    override suspend fun findById(id: Long): AttachmentRecord? =
        doc(id).get().await().takeIf { it.exists() }?.toRecord()

    override suspend fun findByPublicId(publicId: String): AttachmentRecord? =
        collection().whereEqualTo(PUBLIC_ID, publicId).get().await().documents.firstOrNull()?.toRecord()

    override suspend fun delete(id: Long) {
        doc(id).delete().await()
    }

    /**
     * Delete every attachment under an issue — its own and its comments' — in one
     * batch, keyed on the denormalised `scopeIssueId` that [keysForIssue] reads.
     *
     * The load-bearing half of the pair on this backend: there is no cascade here,
     * so without this the attachment documents outlive the issue document and the
     * files behind them can never be named again. The caller reads the keys first
     * and unlinks the objects afterwards — see `IssueRepository.delete`.
     *
     * Chunked at Firestore's 500-write batch limit by [deleteWhere], though an
     * issue's attachments are a handful and never approach it — the sibling
     * [deleteForProject] genuinely can, and one primitive serving both is worth
     * more than a bespoke single batch here.
     */
    override suspend fun deleteForIssue(issueId: Long) = deleteWhere(collection(), SCOPE_ISSUE_ID, issueId)

    /**
     * Every attachment anywhere under a project — its issues', their comments', its
     * forums', its posts' and those posts' comments' — in one equality on the
     * denormalised `scopeProjectId` that [keysForProject] reads.
     *
     * [deleteForIssue]'s project-level twin, and the reason the denormalisation
     * earns its keep twice: without `scopeProjectId` this would be a walk over four
     * collections to collect owner ids, and with it, it is one query. Chunked rather
     * than one batch, unlike [deleteForIssue] — a project's attachments are a whole
     * product's worth of files, not an issue's handful, and 500 is not a number to
     * bet against here.
     */
    override suspend fun deleteForProject(projectId: Long) = deleteWhere(collection(), SCOPE_PROJECT_ID, projectId)

    override suspend fun allStorageKeys(): Set<String> =
        collection().get().await().documents.mapNotNull { it.getString(STORAGE_KEY) }.toSet()

    override suspend fun allMimeTypes(): List<Pair<String, String>> =
        collection().get().await().documents.map { it.getString(PUBLIC_ID).orEmpty() to it.getString(MIME_TYPE).orEmpty() }

    // ── Cascade key reads — one single-field equality each, over a denormalised
    // scope field. See the class preamble on why the ancestry is on the document.

    override suspend fun keysForIssue(issueId: Long): List<String> = keysWhere(SCOPE_ISSUE_ID, issueId)
    override suspend fun keysForComment(commentId: Long): List<String> = keysWhere(COMMENT_ID, commentId)
    override suspend fun keysForForumPost(postId: Long): List<String> = keysWhere(SCOPE_POST_ID, postId)
    override suspend fun keysForForumComment(commentId: Long): List<String> = keysWhere(FORUM_COMMENT_ID, commentId)
    override suspend fun keysForForum(forumId: Long): List<String> = keysWhere(SCOPE_FORUM_ID, forumId)
    override suspend fun keysForMessage(messageId: Long): List<String> = keysWhere(MESSAGE_ID, messageId)
    override suspend fun keysForConversation(conversationId: Long): List<String> = keysWhere(SCOPE_CONVERSATION_ID, conversationId)
    override suspend fun keysForProject(projectId: Long): List<String> = keysWhere(SCOPE_PROJECT_ID, projectId)

    private suspend fun keysWhere(field: String, value: Long): List<String> =
        collection().whereEqualTo(field, value).get().await().documents.mapNotNull { it.getString(STORAGE_KEY) }

    internal companion object {
        const val COLLECTION = "attachments"
        const val ID_COUNTER = "attachments"

        const val ID = "id"
        const val ISSUE_ID = "issueId"
        const val COMMENT_ID = "commentId"
        const val FORUM_POST_ID = "forumPostId"
        const val FORUM_COMMENT_ID = "forumCommentId"
        const val MESSAGE_ID = "messageId"
        const val FILENAME = "filename"
        const val MIME_TYPE = "mimeType"
        const val BYTE_SIZE = "byteSize"
        const val STORAGE_KEY = "storageKey"
        const val PUBLIC_ID = "publicId"
        const val CREATED_AT = "createdAt"
        const val CREATED_BY = "createdBy"
        const val CREATED_BY_EXTERNAL = "createdByExternal"

        // The denormalised ancestry — see the class preamble.
        const val SCOPE_PROJECT_ID = "scopeProjectId"
        const val SCOPE_ISSUE_ID = "scopeIssueId"
        const val SCOPE_FORUM_ID = "scopeForumId"
        const val SCOPE_POST_ID = "scopePostId"
        const val SCOPE_CONVERSATION_ID = "scopeConversationId"
    }
}

private fun DocumentSnapshot.toRecord(): AttachmentRecord = AttachmentRecord(
    id = getLong("id")!!,
    issueId = getLong("issueId"),
    commentId = getLong("commentId"),
    forumPostId = getLong("forumPostId"),
    forumCommentId = getLong("forumCommentId"),
    messageId = getLong("messageId"),
    filename = getString("filename").orEmpty(),
    mimeType = getString("mimeType").orEmpty(),
    byteSize = getLong("byteSize") ?: 0L,
    storageKey = getString("storageKey").orEmpty(),
    publicId = getString("publicId").orEmpty(),
    createdAt = getLong("createdAt")!!,
    author = authorOf(getLong("createdBy"), getString("createdByExternal")),
)
